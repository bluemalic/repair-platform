<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import * as echarts from 'echarts'
import { askAi } from '@/api/ai'
import type { AiQueryResult } from '@/types'

const question = ref('')
const loading = ref(false)
const result = ref<AiQueryResult | null>(null)
/** el-collapse 的 v-model 是"展开项名字的数组"，所以这里是数组而不是布尔 */
const sqlOpen = ref<string[]>([])

/** 问数最难的是"不知道该问什么"，给几个能直接点的问题。这些都是白名单表能回答的。 */
const examples = [
  '上月各楼栋的报修量',
  '哪个师傅的平均处理时长最长',
  '各类别的报修占比',
  '现在还有多少工单没派单',
]

const chartRef = ref<HTMLDivElement>()
let chart: echarts.ECharts | null = null

/**
 * 能不能画图：模型给的类型不是 none，且它给的两个列名**确实在结果里**。
 * 对不上就不画——画一张错图比不画更糟（用户会以为数据就是这样）。
 */
const canChart = computed(() => {
  const data = result.value
  if (!data || !data.chart?.type || data.chart.type === 'none') return false
  return data.columns.includes(data.chart.x ?? '') && data.columns.includes(data.chart.y ?? '')
})

async function ask(text?: string) {
  const q = (text ?? question.value).trim()
  if (!q || loading.value) return
  question.value = q
  loading.value = true
  sqlOpen.value = []
  try {
    result.value = await askAi(q)
  } catch {
    // http.ts 已经弹过后端给的提示（40001/40002/40003/40004 各有各的文案），这里保留上一次结果
    loading.value = false
    return
  }
  // ⚠️ 这两行的顺序要紧：结果块由 `v-if="result && !loading"` 控制，**loading 为 true 时那个块整段
  // 不在 DOM 里**——所以要先把 loading 关掉、等它渲染出来，图表容器才存在。反过来（先 init 再关
  // loading）会拿到空的 ref，图表静默不画、页面上只留一块 320px 的空白。这类问题 typecheck 与构建
  // 都查不出来，只有在浏览器里看一眼才会发现。
  loading.value = false
  await nextTick()
  if (result.value) {
    renderChart(result.value)
  }
}

function renderChart(data: AiQueryResult) {
  // 每次结果的容器可能被重建，先销毁旧的再初始化（否则会拿到已脱离文档的 canvas）
  chart?.dispose()
  chart = null
  if (!canChart.value || !chartRef.value) {
    return
  }
  const xIndex = data.columns.indexOf(data.chart.x ?? '')
  const yIndex = data.columns.indexOf(data.chart.y ?? '')
  const labels = data.rows.map((row) => String(row[xIndex]))
  const values = data.rows.map((row) => Number(row[yIndex]))

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
      <el-button type="primary" :loading="loading" @click="ask()">问一下</el-button>
    </div>

    <div class="examples">
      <span class="muted">试试：</span>
      <el-button v-for="e in examples" :key="e" link type="primary" @click="ask(e)">{{ e }}</el-button>
    </div>

    <el-skeleton v-if="loading" :rows="6" animated />

    <template v-if="result && !loading">
      <p class="conclusion">{{ result.conclusion }}</p>

      <div v-if="canChart" ref="chartRef" class="chart" />

      <el-table :data="result.rows" border>
        <el-table-column
          v-for="(column, index) in result.columns"
          :key="index"
          :label="column"
        >
          <template #default="{ row }">{{ row[index] ?? '—' }}</template>
        </el-table-column>
      </el-table>

      <p class="meta">
        <el-tag v-if="result.rowLimited" type="warning" size="small">
          结果超过上限，已截断
        </el-tag>
        <span class="muted">{{ result.rows.length }} 行 · {{ result.elapsedMs }} ms</span>
      </p>

      <el-collapse v-model="sqlOpen" class="sql">
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
