<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { distribution, overview, trend, workerWorkload } from '@/api/statistics'
import type { DistributionItem, StatisticsOverview, WorkerWorkload } from '@/types'

const params = ref({ start: '', end: '' })
const cards = ref<StatisticsOverview | null>(null)
const dimension = ref<'category' | 'building' | 'urgency'>('category')
const workload = ref<WorkerWorkload[]>([])

const trendRef = ref<HTMLDivElement>()
const pieRef = ref<HTMLDivElement>()
let trendChart: echarts.ECharts | null = null
let pieChart: echarts.ECharts | null = null

/** 平均值/比率为 null 表示样本不足，显示 "-" 而不是 0（后端口径，见 docs/03）。 */
function show(value: number | null | undefined, suffix = ''): string {
  return value === null || value === undefined ? '-' : `${value}${suffix}`
}

async function loadCards() {
  cards.value = await overview(params.value)
  workload.value = await workerWorkload(params.value)
}

async function loadTrend() {
  const points = await trend({ ...params.value, granularity: 'day' })
  trendChart?.setOption({
    tooltip: { trigger: 'axis' },
    grid: { left: 40, right: 16, top: 24, bottom: 32 },
    xAxis: { type: 'category', data: points.map((p) => p.date) },
    yAxis: { type: 'value', minInterval: 1 },
    series: [{ type: 'line', smooth: true, areaStyle: {}, data: points.map((p) => p.count) }],
  })
}

async function loadPie() {
  const items: DistributionItem[] = await distribution(dimension.value, params.value)
  pieChart?.setOption({
    tooltip: { trigger: 'item' },
    series: [
      {
        type: 'pie',
        radius: ['40%', '68%'],
        data: items.map((i) => ({ name: i.name, value: i.count })),
      },
    ],
  })
}

async function loadAll() {
  await Promise.all([loadCards(), loadTrend(), loadPie()])
}

onMounted(async () => {
  if (trendRef.value) trendChart = echarts.init(trendRef.value)
  if (pieRef.value) pieChart = echarts.init(pieRef.value)
  await loadAll()
  window.addEventListener('resize', resize)
})

function resize() {
  trendChart?.resize()
  pieChart?.resize()
}

onBeforeUnmount(() => {
  window.removeEventListener('resize', resize)
  trendChart?.dispose()
  pieChart?.dispose()
})

watch(dimension, loadPie)
</script>

<template>
  <div>
    <div class="toolbar">
      <el-date-picker v-model="params.start" type="date" value-format="YYYY-MM-DD" placeholder="起始日期" />
      <el-date-picker v-model="params.end" type="date" value-format="YYYY-MM-DD" placeholder="结束日期" />
      <el-button type="primary" @click="loadAll">查询</el-button>
      <span class="hint">不填则默认近 30 天</span>
    </div>

    <el-row :gutter="12">
      <el-col :span="6"><el-card><div class="metric">{{ cards?.total ?? '-' }}</div><div class="label">工单总量</div></el-card></el-col>
      <el-col :span="6"><el-card><div class="metric">{{ show(cards?.avgResponseMinutes, ' 分') }}</div><div class="label">平均响应时长</div></el-card></el-col>
      <el-col :span="6"><el-card><div class="metric">{{ show(cards?.avgHandleMinutes, ' 分') }}</div><div class="label">平均处理时长</div></el-card></el-col>
      <el-col :span="6"><el-card><div class="metric">{{ show(cards?.timeoutRate, '%') }}</div><div class="label">超时率（{{ cards?.timeoutCount ?? 0 }} 单）</div></el-card></el-col>
    </el-row>

    <el-row :gutter="12" class="mt">
      <el-col :span="14"><el-card><template #header>报修量趋势（按天）</template><div ref="trendRef" class="chart" /></el-card></el-col>
      <el-col :span="10">
        <el-card>
          <template #header>
            <div class="card-header">
              <span>分布统计</span>
              <el-radio-group v-model="dimension" size="small">
                <el-radio-button value="category">类别</el-radio-button>
                <el-radio-button value="building">楼栋</el-radio-button>
                <el-radio-button value="urgency">紧急度</el-radio-button>
              </el-radio-group>
            </div>
          </template>
          <div ref="pieRef" class="chart" />
        </el-card>
      </el-col>
    </el-row>

    <el-card class="mt">
      <template #header>师傅工作量与效率</template>
      <el-table :data="workload" border>
        <el-table-column prop="workerName" label="师傅" width="140" />
        <el-table-column prop="finishedCount" label="完工数" width="100" />
        <el-table-column label="平均处理时长" width="140">
          <template #default="{ row }">{{ show(row.avgHandleMinutes, ' 分') }}</template>
        </el-table-column>
        <el-table-column prop="processTimeoutCount" label="处理超时次数" width="130" />
        <el-table-column label="按时完成率">
          <template #default="{ row }">{{ show(row.onTimeRate, '%') }}</template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 12px;
}
.hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.metric {
  font-size: 26px;
  font-weight: 600;
}
.label {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.chart {
  height: 300px;
}
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.mt {
  margin-top: 12px;
}
</style>
