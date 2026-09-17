import { http } from './http'
import type { CategoryVO } from '@/types'

export function listCategories(status?: number) {
  return http.get<CategoryVO[]>('/admin/categories', { status })
}

/** 新增：新建的类别一律为启用状态，所以没有 status 字段。 */
export interface CategoryCreateBody {
  name: string
  defaultUrgency: number
  sort: number
}

/** 修改：PUT 是"提交最终状态"。 */
export interface CategoryUpdateBody extends CategoryCreateBody {
  status: number
}

export function createCategory(body: CategoryCreateBody) {
  return http.post<CategoryVO>('/admin/categories', body)
}

export function updateCategory(id: string, body: CategoryUpdateBody) {
  return http.put<void>(`/admin/categories/${id}`, body)
}

/** 逻辑删除。只允许删"从未被工单引用"的类别；已被引用会被后端拒绝并说明原因。 */
export function deleteCategory(id: string) {
  return http.del<void>(`/admin/categories/${id}`)
}
