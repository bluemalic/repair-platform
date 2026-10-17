import { http } from './http'
import type { PageResult, RepairCodeDetailVO } from '@/types'

export interface RepairCodeQuery {
  pageNum: number
  pageSize: number
  buildingId?: string
  status?: number
}

export function pageRepairCodes(query: RepairCodeQuery) {
  return http.get<PageResult<RepairCodeDetailVO>>('/admin/repair-codes', { ...query })
}

/** 生成：只给楼栋 + 房间，**码由服务端随机生成**（不接受指定，否则顺序码又回来了）。 */
/**
 * 生成报修码。`locationType`：1 宿舍房间（room=房间号）/ 2 公共区域（room=区域名）。
 * 公共区域也是"一个区域一张码"——到场核验要靠它（docs/01 §4.6）。
 */
export function createRepairCode(buildingId: string, room: string, locationType = 1) {
  return http.post<RepairCodeDetailVO>('/admin/repair-codes', { buildingId, room, locationType })
}

export interface RepairCodeUpdateBody {
  room: string
  status: number
  /** true 时服务端另外生成一个新码，旧码立即失效 */
  regenerate?: boolean
}

export function updateRepairCode(id: string, body: RepairCodeUpdateBody) {
  return http.put<void>(`/admin/repair-codes/${id}`, body)
}
