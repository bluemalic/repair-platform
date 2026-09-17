import { http } from './http'
import type { PageResult, WorkerVO } from '@/types'

export interface WorkerQuery {
  pageNum: number
  pageSize: number
  status?: number
  /** 工号或姓名的模糊匹配，服务端过滤 */
  keyword?: string
}

export function pageWorkers(query: WorkerQuery) {
  return http.get<PageResult<WorkerVO>>('/admin/workers', { ...query })
}
