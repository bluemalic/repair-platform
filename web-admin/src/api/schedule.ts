import { http } from './http'
import type { WorkerGroupVO, WorkerShiftVO } from '@/types'

/** 工种分组列表（字典数据量小，不分页）。 */
export function listWorkerGroups() {
  return http.get<WorkerGroupVO[]>('/admin/worker-groups')
}

export interface WorkerGroupCreateBody {
  name: string
  sort: number
}

export interface WorkerGroupUpdateBody extends WorkerGroupCreateBody {
  status: number
}

export function createWorkerGroup(body: WorkerGroupCreateBody) {
  return http.post<WorkerGroupVO>('/admin/worker-groups', body)
}

export function updateWorkerGroup(id: string, body: WorkerGroupUpdateBody) {
  return http.put<void>(`/admin/worker-groups/${id}`, body)
}

/** 组内有人、或仍被报修类别引用时，后端会拒绝并说明原因。 */
export function deleteWorkerGroup(id: string) {
  return http.del<void>(`/admin/worker-groups/${id}`)
}

/** 设置组内成员（全量替换，空数组=清空）。 */
export function setWorkerGroupMembers(id: string, workerIds: string[]) {
  return http.put<void>(`/admin/worker-groups/${id}/workers`, { workerIds })
}

/** 某天的排班：只返回有明确记录的行。 */
export function listWorkerShifts(date: string) {
  return http.get<WorkerShiftVO[]>('/admin/worker-shifts', { date })
}

export interface WorkerShiftSaveBody {
  workerId: string
  shiftDate: string
  /** 1白班 2夜班 */
  shiftType: number
  /** 1在班 0请假/休息 */
  status: number
  remark?: string
}

/** 按 (维修工, 日期, 班次) 唯一，重复提交即覆盖。 */
export function saveWorkerShift(body: WorkerShiftSaveBody) {
  return http.put<void>('/admin/worker-shifts', body)
}
