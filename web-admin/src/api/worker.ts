import { http } from './http'
import type { PageResult, WorkerVO } from '@/types'

export interface WorkerQuery {
  pageNum: number
  pageSize: number
  status?: number
  /** 工号或姓名的模糊匹配，服务端过滤 */
  keyword?: string
}

/** 新增：工号即登录名；用户类型由服务端写死为维修工，前端不传。 */
export interface WorkerCreateBody {
  username: string
  realName: string
  phone?: string
  password: string
}

/** 修改：PUT 是"提交最终状态"。密码留空表示不改（填了就是帮师傅重置）。 */
export interface WorkerUpdateBody {
  realName: string
  phone?: string
  status: number
  password?: string
}

export function pageWorkers(query: WorkerQuery) {
  return http.get<PageResult<WorkerVO>>('/admin/workers', { ...query })
}

export function createWorker(body: WorkerCreateBody) {
  return http.post<WorkerVO>('/admin/workers', body)
}

export function updateWorker(id: string, body: WorkerUpdateBody) {
  return http.put<void>(`/admin/workers/${id}`, body)
}

/** 设置负责楼栋（全量替换：传什么就是他的全部；空数组 = 不负责任何楼栋）。 */
export function setWorkerBuildings(id: string, buildingIds: string[]) {
  return http.put<void>(`/admin/workers/${id}/buildings`, { buildingIds })
}
