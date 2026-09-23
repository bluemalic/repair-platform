import axios, { AxiosError } from 'axios'
import { ElMessage } from 'element-plus'
import { auth, clearLogin, getToken } from '@/store/auth'
import type { Result } from '@/types'

const instance = axios.create({ baseURL: '/api', timeout: 15000 })

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
): Promise<T> {
  try {
    const resp = await instance.request<Result<T>>({ method, url, data: payload })
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

export const http = {
  get: <T>(url: string, params?: Record<string, unknown>) =>
    request<T>('get', url + toQuery(params)),
  post: <T>(url: string, data?: unknown) => request<T>('post', url, data),
  put: <T>(url: string, data?: unknown) => request<T>('put', url, data),
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
