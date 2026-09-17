import { http } from './http'
import type { BuildingVO } from '@/types'

/** @param status 1 只要启用中的（表单下拉）；不传返回全部（管理列表要能看到已停用的） */
export function listBuildings(status?: number) {
  return http.get<BuildingVO[]>('/admin/buildings', { status })
}
