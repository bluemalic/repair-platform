import axios, { AxiosError } from 'axios'
import { ElMessage } from 'element-plus'
import { auth, clearLogin, getToken } from '@/store/auth'
import type { Result } from '@/types'

const DEFAULT_TIMEOUT = 15000
/**
 * 下载（Excel 导出）单独放宽到 60 秒：后端要先跑完四组聚合查询才写第一个字节，
 * 而 15 秒的默认值在慢的时候会把"其实正在生成"判成超时。
 */
const DOWNLOAD_TIMEOUT = 60000
const instance = axios.create({ baseURL: '/api', timeout: DEFAULT_TIMEOUT })

instance.interceptors.request.use((config) => {
  const token = getToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

/**
 * 401（未登录/过期）与 403（无权限）都由这里统一处理，业务层不用各写一遍。
 *
 * <p>导出是因为**流式接口绕开了 axios**（见 `api/ai.ts`：`EventSource` 带不了 Authorization 头，
 * 所以用 fetch 手动读流），但"错误长什么样"必须与其它接口完全一致——尤其是 10002 要回登录页
 * 这一条，两套提示会让用户以为出了两种问题。
 */
export function handleError(code: number, message: string): void {
  if (code === 10002) {
    ElMessage.warning('登录已过期，请重新登录')
    // 与 LayoutView 的 goLogin 同一条理由：平台运营账号要**带着登录模式**回登录页。
    // 不带的后果是静默退回学校模式（学校编码默认 gdou），而那个租户下没有 platform 这个账号，
    // 表现为"新旧口令都不对"——很难从现象倒推回来。所以这里要先读 userType 再清登录态。
    const wasPlatform = auth.user?.userType === 4
    clearLogin()
    location.hash = wasPlatform ? '#/login?platform=1' : '#/login'
    return
  }
  ElMessage.error(message || '请求失败')
}

async function request<T>(
  method: 'get' | 'post' | 'put' | 'delete',
  url: string,
  payload?: unknown,
  options?: RequestOptions,
): Promise<T> {
  try {
    const resp = await instance.request<Result<T>>({
      method,
      url,
      data: payload,
      // 默认 15 秒；AI 问数要等两次模型调用，单独放宽（见 RequestOptions 的说明）
      timeout: options?.timeout ?? DEFAULT_TIMEOUT,
    })
    const body = resp.data
    if (body.code !== 0) {
      handleError(body.code, body.message)
      throw new Error(body.message)
    }
    return body.data
  } catch (e) {
    // 网络错误 / 4xx：axios 会进这里；已由 handleError 处理过的业务错误不重复提示
    if (e instanceof AxiosError) {
      const body = e.response?.data as Result<unknown> | undefined
      if (body?.code !== undefined) {
        handleError(body.code, body.message)
      } else {
        ElMessage.error(e.message || '网络异常')
      }
    }
    throw e
  }
}

/**
 * 单请求覆盖项。
 *
 * <p>为什么需要：全局超时是 15 秒，而 AI 问数要等"生成 SQL + 执行 + 生成结论"三次往返
 * （两次模型调用），十几秒很正常——不单独放宽的话，请求会在 axios 层被判超时，
 * 而那时后端其实还在跑，用户看到的是"网络异常"而不是"稍等"。
 */
export interface RequestOptions {
  timeout?: number
}

/**
 * 文件下载（目前只有统计报表导出）。为什么不复用 {@link request}：那条路径的响应体是
 * `Result<T>`，axios 直接解包成对象；这里要的是**原始字节**，必须设 `responseType: 'blob'`。
 *
 * <p>但"错误长什么样"必须与其它接口完全一致：后端失败时回的是普通 `Result` JSON
 * （**不是**附件），只是被 axios 当 blob 收下来了。所以这里按 `Content-Type` 认出来、
 * 解成 `Result` 再交给 {@link handleError}——不这么做的话，用户看到的现象是
 * "下载了个打不开的文件"，而真正的原因（没权限 / 参数不合法 / 登录过期）被吞掉。
 *
 * <p>**它不抛异常**：调用方拿不到有意义的返回值，失败信息已经通过 `handleError` 弹出来了，
 * 再往外抛只会变成控制台里的未处理拒绝。
 */
async function download(url: string, params?: Record<string, unknown>): Promise<void> {
  try {
    const resp = await instance.request<Blob>({
      method: 'get',
      url: url + toQuery(params),
      responseType: 'blob',
      timeout: DOWNLOAD_TIMEOUT,
    })
    if (String(resp.headers['content-type'] ?? '').includes('application/json')) {
      const body = await asResult(resp.data)
      if (body) {
        handleError(body.code, body.message)
      } else {
        ElMessage.error('导出失败，请稍后重试')
      }
      return
    }
    saveFile(resp.data, fileNameOf(resp.headers['content-disposition']))
  } catch (e) {
    // 4xx/5xx（401 登录过期、403 无权限）同样会把 Result JSON 包成 blob，这里按同样的方式还原
    if (e instanceof AxiosError) {
      const body = await asResult(e.response?.data)
      if (body?.code !== undefined) {
        handleError(body.code, body.message)
      } else {
        ElMessage.error(e.message || '网络异常')
      }
    }
  }
}

/** 把响应体读成 Result：blob 里可能是 JSON（错误），也可能是别的（文件），所以解析失败不算错。 */
async function asResult(data: unknown): Promise<Result<unknown> | undefined> {
  if (!(data instanceof Blob)) {
    return data as Result<unknown> | undefined
  }
  try {
    return JSON.parse(await data.text()) as Result<unknown>
  } catch {
    return undefined
  }
}

/**
 * 从 `Content-Disposition` 取文件名：优先 RFC 5987 的 `filename*`（中文名只在这里），
 * 退回 ASCII 的 `filename`——两者后端都会给（见 StatisticsController.attachmentName）。
 */
function fileNameOf(disposition?: string): string {
  if (!disposition) return 'statistics.xlsx'
  const encoded = /filename\*=UTF-8''([^;]+)/i.exec(disposition)
  if (encoded) return decodeURIComponent(encoded[1])
  const plain = /filename="?([^";]+)"?/i.exec(disposition)
  return plain ? plain[1] : 'statistics.xlsx'
}

function saveFile(blob: Blob, fileName: string): void {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = fileName
  link.click()
  // 立刻 revoke 在部分浏览器上会把还没开始读的 blob 掐掉，放到下一个事件循环再回收
  setTimeout(() => URL.revokeObjectURL(url), 0)
}

export const http = {
  get: <T>(url: string, params?: Record<string, unknown>) =>
    request<T>('get', url + toQuery(params)),
  post: <T>(url: string, data?: unknown, options?: RequestOptions) => request<T>('post', url, data, options),
  put: <T>(url: string, data?: unknown, options?: RequestOptions) => request<T>('put', url, data, options),
  // 命名用 del 不用 delete：delete 是保留字，写成对象方法虽然合法，但读起来容易被当成操作符
  del: <T>(url: string) => request<T>('delete', url),
  download,
}

function toQuery(params?: Record<string, unknown>): string {
  if (!params) return ''
  const pairs = Object.entries(params)
    .filter(([, v]) => v !== undefined && v !== null && v !== '')
    .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(String(v))}`)
  return pairs.length ? `?${pairs.join('&')}` : ''
}
