import { request } from './http'
import type { CategoryVO } from '@/types'

/** 启用中的报修类别（各端共用，只读）。报修表单要用它给用户选。 */
export function listEnabledCategories() {
  return request<CategoryVO[]>({ url: '/categories' })
}
