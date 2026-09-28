import { http } from './http'
import type { DistributionItem, StatisticsOverview, TrendPoint, WorkerWorkload } from '@/types'

export interface RangeQuery {
  start?: string
  end?: string
}

export function overview(query: RangeQuery = {}) {
  return http.get<StatisticsOverview>('/admin/statistics/overview', { ...query })
}

export function trend(query: RangeQuery & { granularity?: 'day' | 'week' } = {}) {
  return http.get<TrendPoint[]>('/admin/statistics/trend', { ...query })
}

export function distribution(dimension: 'category' | 'building' | 'urgency', query: RangeQuery = {}) {
  return http.get<DistributionItem[]>('/admin/statistics/distribution', { dimension, ...query })
}

export function workerWorkload(query: RangeQuery = {}) {
  return http.get<WorkerWorkload[]>('/admin/statistics/worker-workload', { ...query })
}

/**
 * 导出统计报表（Excel）。四张工作表对应上面四个接口，**数字与页面一致**（后端取数走的就是它们）；
 * 文件名由后端从 `Content-Disposition` 给，所以这里不接收也不返回文件名。
 */
export function exportStatistics(query: RangeQuery = {}) {
  return http.download('/admin/statistics/export', { ...query })
}
