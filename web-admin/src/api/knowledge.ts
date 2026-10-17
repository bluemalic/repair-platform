import { http } from './http'
import type { KnowledgeEntryVO, PageResult } from '@/types'

/**
 * 维修知识库（阶段 2 · ADR-018）。
 *
 * 它是师傅端「排查建议」的原料：**检索命中什么，建议就只能依据什么**——
 * 所以这里的两个输入要写对（页面上也做了提示）：
 * · `symptom` 写"用户会说的那种话"（检索主字段）
 * · `tags` 补同义词（"漏水 渗水 滴水"），关键词检索的召回全靠它
 */

export interface KnowledgeQuery {
  pageNum?: number
  pageSize?: number
  /** 关键词：匹配标题或现象 */
  keyword?: string
  categoryId?: string
  status?: number
}

export interface KnowledgeBody {
  title: string
  symptom: string
  /** 处理步骤，一行一步 */
  solution: string
  /** 留空 = 通用条目（任何类别都可能命中） */
  categoryId: string | null
  tags: string | null
  status: number
}

export function pageKnowledge(query: KnowledgeQuery) {
  return http.get<PageResult<KnowledgeEntryVO>>('/admin/knowledge', { ...query })
}

export function createKnowledge(body: KnowledgeBody) {
  return http.post<KnowledgeEntryVO>('/admin/knowledge', body)
}

export function updateKnowledge(id: string, body: KnowledgeBody) {
  return http.put<void>(`/admin/knowledge/${id}`, body)
}

/** 启用 / 停用。**没有删除**：条目会被历史建议引用（docs/02 §3.7）。 */
export function updateKnowledgeStatus(id: string, status: number) {
  // status 走 query 参数（与后端契约一致）；http.put 的第三个参数是请求选项，不是 query
  return http.put<void>(`/admin/knowledge/${id}/status?status=${status}`)
}
