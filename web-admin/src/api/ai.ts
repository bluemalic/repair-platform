import { http } from './http'
import type { AiQueryResult } from '@/types'

/**
 * AI 问数（docs/03 §5.6）。
 *
 * 超时单独放宽到 60 秒：一次问数要等"生成 SQL → 执行 → 生成结论"，**两次模型调用**，
 * 十几秒很正常。全局的 15 秒会让它在 axios 层就被判超时——而那时后端其实还在跑，
 * 用户看到的是"网络异常"，不是"稍等"。
 */
const AI_TIMEOUT_MS = 60_000

export function askAi(question: string) {
  return http.post<AiQueryResult>('/ai/query', { question }, { timeout: AI_TIMEOUT_MS })
}
