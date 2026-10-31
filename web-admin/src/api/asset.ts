import { http } from './http'
import type { AssetVO, PageResult } from '@/types'

/** 设备分页（docs/03 §5.4）：楼栋 / 类别 / 状态筛选，keyword 同时匹配编号与名称。 */
export interface AssetQuery {
  pageNum: number
  pageSize: number
  buildingId?: string
  categoryId?: string
  status?: number
  keyword?: string
}

export function pageAssets(query: AssetQuery) {
  return http.get<PageResult<AssetVO>>('/admin/assets', { ...query })
}

export function getAsset(id: string) {
  return http.get<AssetVO>(`/admin/assets/${id}`)
}

/** 新增：新建一律为「在用」，所以没有 status 字段。 */
export interface AssetCreateBody {
  assetNo: string
  name: string
  categoryId?: string
  buildingId?: string
  room?: string
  purchaseDate?: string
  warrantyUntil?: string
  supplier?: string
  remark?: string
}

/** 修改：PUT 是"提交最终状态"，status 必填。 */
export interface AssetUpdateBody extends AssetCreateBody {
  status: number
}

export function createAsset(body: AssetCreateBody) {
  return http.post<AssetVO>('/admin/assets', body)
}

export function updateAsset(id: string, body: AssetUpdateBody) {
  return http.put<void>(`/admin/assets/${id}`, body)
}

/**
 * 逻辑删除。**只允许删从未被工单引用的设备**；有维修历史的后端会拒绝，
 * 下线一台在用的设备请走「编辑 → 停用/报废」。
 */
export function deleteAsset(id: string) {
  return http.del<void>(`/admin/assets/${id}`)
}
