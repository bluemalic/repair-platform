import axios, { AxiosError } from 'axios'
import { ElMessage } from 'element-plus'
import { auth, clearLogin, getToken } from '@/store/auth'
import type { Result } from '@/types'

const DEFAULT_TIMEOUT = 15000
const instance = axios.create({ baseURL: '/api', timeout: DEFAULT_TIMEOUT })

instance.interceptors.request.use((config) => {
  const token = getToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

/** 401（未登录/过期）与 403（无权限）都由这里统一处理，业务层不用各写一遍。 */
function handleError(code: number, message: string): void {
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

export const http = {
  get: <T>(url: string, params?: Record<string, unknown>) =>
    request<T>('get', url + toQuery(params)),
  post: <T>(url: string, data?: unknown, options?: RequestOptions) => request<T>('post', url, data, options),
  put: <T>(url: string, data?: unknown, options?: RequestOptions) => request<T>('put', url, data, options),
  // 命名用 del 不用 delete：delete 是保留字，写成对象方法虽然合法，但读起来容易被当成操作符
  del: <T>(url: string) => request<T>('delete', url),
}

function toQuery(params?: Record<string, unknown>): string {
  if (!params) return ''
  const pairs = Object.entries(params)
    .filter(([, v]) => v !== undefined && v !== null && v !== '')
    .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(String(v))}`)
  return pairs.length ? `?${pairs.join('&')}` : ''
}
