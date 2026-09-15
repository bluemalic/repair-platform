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
