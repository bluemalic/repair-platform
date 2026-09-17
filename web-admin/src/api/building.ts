import { http } from './http'
import type { BuildingVO } from '@/types'

/** @param status 1 只要启用中的（表单下拉）；不传返回全部（管理列表要能看到已停用的） */
export function listBuildings(status?: number) {
  return http.get<BuildingVO[]>('/admin/buildings', { status })
}

/** 新增：新建的楼栋一律为启用状态，所以没有 status 字段。 */
export interface BuildingCreateBody {
  name: string
  area?: string
  sort: number
}

/** 修改：PUT 是"提交最终状态"，sort 与 status 都必填。 */
export interface BuildingUpdateBody extends BuildingCreateBody {
  status: number
}

export function createBuilding(body: BuildingCreateBody) {
  return http.post<BuildingVO>('/admin/buildings', body)
}

export function updateBuilding(id: string, body: BuildingUpdateBody) {
  return http.put<void>(`/admin/buildings/${id}`, body)
}

/** 逻辑删除。只允许删"从未被引用"的楼栋：有工单 / 有师傅负责 / 有报修码都会被后端拒绝并说明原因。 */
export function deleteBuilding(id: string) {
  return http.del<void>(`/admin/buildings/${id}`)
}
