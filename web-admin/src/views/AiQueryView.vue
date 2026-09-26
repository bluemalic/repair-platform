<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import * as echarts from 'echarts'
import { askAiStream } from '@/api/ai'
import type { AiQueryResult } from '@/types'

const question = ref('')
const streaming = ref(false)
const result = ref<Partial<AiQueryResult> | null>(null)
/** 当前阶段的那句话（见下方"为什么分阶段"）。空串 = 已经结束，由真实内容接管这一行。 */
const stage = ref('')
/** el-collapse 的 v-model 是"展开项名字的数组"，所以这里是数组而不是布尔 */
const sqlOpen = ref<string[]>([])

/**
 * 问数要等两次模型调用（出 SQL、出结论），十几秒里用户看不到任何东西。所以走流式接口，
 * 把"SQL 已经定下来""数据已经取到"这两段先摆出来——**page 上只有一行状态字**，
 * 内容随帧逐段长出来，不是一句解释。
 */
const examples = [
  '上月各楼栋的报修量',
  '哪个师傅的平均处理时长最长',
  '各类别的报修占比',
  '现在还有多少工单没派单',
]

const chartRef = ref<HTMLDivElement>()
let chart: echarts.ECharts | null = null
let controller: AbortController | null = null

/**
 * 能不能画图：模型给的类型不是 none，且它给的两个列名**确实在结果里**。
 * 对不上就不画——画一张错图比不画更糟（用户会以为数据就是这样）。
 */
const canChart = computed(() => {
  const data = result.value
  if (!data?.chart || !data.columns || !data.rows) return false
  if (!data.chart.type || data.chart.type === 'none') return false
  return data.columns.includes(data.chart.x ?? '') && data.columns.includes(data.chart.y ?? '')
})

/** 逐段合并：每帧只带来一部分字段，拼起来才是完整结果。 */
function patch(next: Partial<AiQueryResult>) {
  result.value = { ...(result.value ?? {}), ...next }
}

function ask(text?: string) {
  const q = (text ?? question.value).trim()
  if (!q || streaming.value) return
  question.value = q
  void startStream(q)
}

async function startStream(q: string) {
  // 上一次还没跑完就再问一次：先掐掉旧的（同一个 controller 被 abort 后不能再复用）
  controller?.abort()
  controller = new AbortController()

  streaming.value = true
  stage.value = '正在理解问题…'
  result.value = null
  sqlOpen.value = []
  // 容器会随结果重建，先销毁旧的，否则图表拿到的是已脱离文档的 canvas
  chart?.dispose()
  chart = null

  try {
    await askAiStream(
      q,
      {
        onSql: (frame) => {
          patch({ sql: frame.sql, chart: frame.chart })
          stage.value = '正在取数…'
        },
        onData: async (frame) => {
          patch({ columns: frame.columns, rows: frame.rows, rowLimited: frame.rowLimited })
          stage.value = '正在总结…'
          // ⚠️ 图表容器由 `canChart` 控制，此刻它刚变成 true、**还没渲染到 DOM**：
          // 必须先等一次 nextTick 拿到容器再 init。顺序反了会静默拿到空 ref，
          // 页面上只留一块空白，typecheck 与构建都查不出来。
          await nextTick()
          renderChart()
        },
        onDone: (frame) => {
          patch({ conclusion: frame.conclusion, elapsedMs: frame.elapsedMs })
          stage.value = ''
        },
      },
      controller.signal,
    )
  } catch (e) {
    // 提示已经弹过（含后端给的 40001~40004 与 10002 回登录页），这里只负责收状态：
    // 已经推过来的那几段**保留在页面上**——SQL 是排障时最有用的一条信息，不该连它也一起清掉。
    if ((e as Error)?.name !== 'AbortError') {
      stage.value = ''
    }
  } finally {
    if (!controller.signal.aborted) {
      streaming.value = false
    }
  }
}

function renderChart() {
  const data = result.value
  if (!canChart.value || !chartRef.value || !data?.columns || !data.chart) return
  const xIndex = data.columns.indexOf(data.chart.x ?? '')
  const yIndex = data.columns.indexOf(data.chart.y ?? '')
  const rows = data.rows ?? []
  const labels = rows.map((row) => String(row[xIndex]))
  const values = rows.map((row) => Number(row[yIndex]))

  chart = echarts.init(chartRef.value)
  chart.setOption(data.chart.type === 'pie'
      ? {
          tooltip: { trigger: 'item' },
          series: [{ type: 'pie', radius: ['40%', '68%'], data: labels.map((name, i) => ({ name, value: values[i] })) }],
        }
      : {
          tooltip: { trigger: 'axis' },
          grid: { left: 48, right: 16, top: 24, bottom: 48 },
          xAxis: { type: 'category', data: labels, axisLabel: { rotate: labels.length > 6 ? 20 : 0 } },
          yAxis: { type: 'value', minInterval: 1 },
          series: [{ type: data.chart.type === 'line' ? 'line' : 'bar', smooth: true, data: values }],
        })
}

function resize() {
  chart?.resize()
}

// 监听器在任何 await 之前注册：写在取数之后的话，取数一抛错它就永远挂不上了
onMounted(() => window.addEventListener('resize', resize))

onBeforeUnmount(() => {
  window.removeEventListener('resize', resize)
  // 切路由/关页面时把流掐掉：不掐的话后端会继续跑完那一轮（钱已经花了，但没必要把连接也留着）
  controller?.abort()
  chart?.dispose()
})
</script>

<template>
  <div>
    <div class="toolbar">
      <el-input
        v-model="question"
        placeholder="用一句中文问数据，例如：上月各楼栋的报修量"
        clearable
        @keyup.enter="ask()"
      />
      <el-button type="primary" :loading="streaming" @click="ask()">问一下</el-button>
    </div>

    <div class="examples">
      <span class="muted">试试：</span>
      <el-button v-for="e in examples" :key="e" link type="primary" @click="ask(e)">{{ e }}</el-button>
    </div>

    <!-- 这一行是"进行中"的状态位：结束（成功或失败）后由结论接管，不留多余提示 -->
    <p v-if="stage" class="stage"><span class="spinner" />{{ stage }}</p>
    <el-skeleton v-if="stage && !result?.sql" :rows="4" animated />

    <template v-if="result">
      <p v-if="!stage && result.conclusion" class="conclusion">{{ result.conclusion }}</p>

      <div v-if="canChart" ref="chartRef" class="chart" />

      <el-table v-if="result.columns && result.rows" :data="result.rows" border>
        <el-table-column
          v-for="(column, index) in result.columns"
          :key="index"
          :label="column"
        >
          <template #default="{ row }">{{ row[index] ?? '—' }}</template>
        </el-table-column>
      </el-table>

      <p v-if="result.columns" class="meta">
        <el-tag v-if="result.rowLimited" type="warning" size="small">
          结果超过上限，已截断
        </el-tag>
        <span class="muted">
          {{ result.rows?.length ?? 0 }} 行<template v-if="result.elapsedMs !== undefined">
            · {{ result.elapsedMs }} ms</template>
        </span>
      </p>

      <el-collapse v-if="result.sql" v-model="sqlOpen" class="sql">
        <el-collapse-item name="sql" title="查看生成的 SQL">
          <pre class="sql-text">{{ result.sql }}</pre>
        </el-collapse-item>
      </el-collapse>
    </template>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 8px;
}
.examples {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}
.stage {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 12px;
  font-size: 14px;
  color: var(--el-text-color-regular);
}
.spinner {
  width: 12px;
  height: 12px;
  border: 2px solid var(--el-color-primary-light-5);
  border-top-color: var(--el-color-primary);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
.conclusion {
  margin: 0 0 12px;
  font-size: 15px;
  line-height: 1.7;
  color: var(--el-text-color-primary);
}
.chart {
  height: 320px;
  margin-bottom: 16px;
}
.meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 12px 0 0;
}
.muted {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.sql {
  margin-top: 8px;
}
.sql-text {
  margin: 0;
  padding: 12px;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
  background: var(--el-fill-color-light);
  border-radius: 4px;
}
</style>
