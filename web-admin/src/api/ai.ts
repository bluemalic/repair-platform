import { ElMessage } from 'element-plus'
import { handleError, http } from './http'
import { getToken } from '@/store/auth'
import type { AiDataFrame, AiDoneFrame, AiQueryResult, AiSqlFrame, Result } from '@/types'

/**
 * AI 问数（docs/03 §5.6）。
 *
 * 超时单独放宽到 60 秒：一次问数要等"生成 SQL → 执行 → 生成结论"，**两次模型调用**，
 * 十几秒很正常。全局的 15 秒会让它在 axios 层就被判超时——而那时后端其实还在跑，
 * 用户看到的是"网络异常"，不是"稍等"。
 */
const AI_TIMEOUT_MS = 60_000

/** 非流式：一次拿到全部结果。页面走的是流式那条（{@link askAiStream}），这个留给 curl / 文档 / 评测脚本。 */
export function askAi(question: string) {
  return http.post<AiQueryResult>('/ai/query', { question }, { timeout: AI_TIMEOUT_MS })
}

/** 流式问数的三段回调（与 SSE 帧一一对应）。 */
export interface AiStreamHandlers {
  onSql?: (payload: AiSqlFrame) => void
  onData?: (payload: AiDataFrame) => void
  onDone?: (payload: AiDoneFrame) => void
}

/** SSE 帧之间用空行分隔（`\r\n` 与 `\n` 都要认：中间可能隔着 nginx）。 */
const FRAME_SEPARATOR = /\r?\n\r?\n/

/**
 * 流式问数。
 *
 * <p>**为什么不用 `EventSource`**：它无法设置 `Authorization` 头，token 只能拼进 URL，
 * 而 URL 会进 nginx 的访问日志与浏览器历史。所以用 `fetch` 手动读流（服务端并不关心客户端是什么）。
 *
 * <p>**两条错误通道**（与后端的分界一致）：工作开始**之前**的失败（未登录 / 无权限 / 限流 / 参数不合法）
 * 后端回的是普通 JSON，走 {@link handleError}；开始之后才是一个 `error` 帧——两种都收敛成同一个提示，
 * 调用方只需要 catch 一次。
 *
 * <p>这个函数**不返回结果**：它把三段分别交给回调（这就是流式的意义），
 * 全部结束后才 resolve；失败时抛出（消息已经给用户看过了）。
 */
export async function askAiStream(
  question: string,
  handlers: AiStreamHandlers,
  signal?: AbortSignal,
): Promise<void> {
  const token = getToken()
  const resp = await fetch(`/api/ai/query/stream?question=${encodeURIComponent(question)}`, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
    signal,
  })

  if (!resp.headers.get('Content-Type')?.includes('text/event-stream') || !resp.body) {
    // 还没开始流就失败了：后端回的是统一返回体，照它解包（HTTP 状态可能是 401 / 403 / 429）
    throw await rejectFromJson(resp)
  }

  const reader = resp.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  let sawDone = false
  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) {
        break
      }
      buffer += decoder.decode(value, { stream: true })
      let separator = FRAME_SEPARATOR.exec(buffer)
      while (separator) {
        const block = buffer.slice(0, separator.index)
        buffer = buffer.slice(separator.index + separator[0].length)
        sawDone = dispatchFrame(block, handlers) || sawDone
        separator = FRAME_SEPARATOR.exec(buffer)
      }
    }
  } finally {
    // 提前 throw（error 帧）时把连接还回去，别让它挂到超时
    reader.cancel().catch(() => undefined)
  }

  if (!sawDone) {
    // 没收到收尾帧就断了：后端超时、nginx 切断、或网络掉了。用户需要知道"这次没跑完"，
    // 否则页面上留着半截结果，看起来像是查完了。
    throw rejectWith(10005, '连接中断，请重试')
  }
}

/** 解析一个帧块并分发；返回是否收到了收尾帧。无法识别的字段与事件名直接跳过（旧前端不该被新事件打翻）。 */
function dispatchFrame(block: string, handlers: AiStreamHandlers): boolean {
  let event = 'message'
  const dataLines: string[] = []
  for (const line of block.split(/\r?\n/)) {
    if (line.startsWith(':')) {
      continue // 注释行（SSE 的心跳）
    }
    const colon = line.indexOf(':')
    const field = colon === -1 ? line : line.slice(0, colon)
    const value = colon === -1 ? '' : line.slice(colon + 1).replace(/^ /, '')
    if (field === 'event') {
      event = value
    } else if (field === 'data') {
      dataLines.push(value)
    }
  }
  if (!dataLines.length) {
    return false
  }
  const payload = JSON.parse(dataLines.join('\n'))
  switch (event) {
    case 'sql':
      handlers.onSql?.(payload as AiSqlFrame)
      return false
    case 'data':
      handlers.onData?.(payload as AiDataFrame)
      return false
    case 'done':
      handlers.onDone?.(payload as AiDoneFrame)
      return true
    case 'error':
      // 工作开始之后的失败：code 与同步接口一致（40001~40004），提示方式也一致
      throw rejectWith(payload.code ?? 10005, payload.message ?? '问数失败')
    default:
      return false
  }
}

/** 把后端的统一返回体变成"提示 + 抛出"（与 axios 那条路给用户看到的东西一样）。 */
async function rejectFromJson(resp: Response): Promise<Error> {
  const body = (await resp.json().catch(() => null)) as Result<unknown> | null
  if (body && typeof body.code === 'number') {
    return rejectWith(body.code, body.message)
  }
  ElMessage.error(`请求失败（HTTP ${resp.status}）`)
  return new Error(`HTTP ${resp.status}`)
}

function rejectWith(code: number, message: string): Error {
  handleError(code, message)
  return new Error(message)
}
