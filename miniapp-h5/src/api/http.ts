import { clearLogin, getToken } from '@/store/auth'
import type { Result } from '@/types'

/**
 * 请求封装。
 *
 * <p><b>用 `uni.request` 而不是 axios</b>：小程序端没有 `XMLHttpRequest`，axios 在里面根本不可用。
 * 现在只编 H5，但"先 H5、备案后可编小程序"是选 uni-app 的原因（AGENTS §3），
 * 所以从第一行代码起就不用只有浏览器才有的东西。
 *
 * <p>baseURL：H5 走同源 `/api`（开发由 vite 代理、线上由 nginx 反代）；
 * 将来编小程序必须换成完整域名（小程序不允许相对路径），用 `VITE_API_BASE` 覆盖即可。
 */
const BASE_URL = import.meta.env.VITE_API_BASE ?? '/api'

interface RequestOptions {
  url: string
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE'
  data?: Record<string, unknown>
  /** 默认 true：除登录外都要带 token */
  auth?: boolean
}

/** 业务码与 HTTP 状态的处理口径与 docs/03 §2.3 一致：业务失败看 code，401/403 也走同一处提示。 */
function handleFailure(code: number, message: string): void {
  if (code === 10002) {
    clearLogin()
    uni.showToast({ title: '登录已过期，请重新登录', icon: 'none' })
    uni.reLaunch({ url: '/pages/login/login' })
    return
  }
  uni.showToast({ title: message || '请求失败', icon: 'none' })
}

export function request<T>(options: RequestOptions): Promise<T> {
  const { url, method = 'GET', data, auth = true } = options
  return new Promise<T>((resolve, reject) => {
    const header: Record<string, string> = { 'Content-Type': 'application/json' }
    if (auth) {
      const token = getToken()
      if (token) {
        header.Authorization = `Bearer ${token}`
      }
    }
    uni.request({
      url: BASE_URL + url,
      method,
      data,
      header,
      success: (res) => {
        const body = res.data as Result<T> | undefined
        if (body && typeof body.code === 'number') {
          if (body.code === 0) {
            resolve(body.data)
            return
          }
          handleFailure(body.code, body.message)
          reject(new Error(body.message))
          return
        }
        uni.showToast({ title: `请求失败（HTTP ${res.statusCode}）`, icon: 'none' })
        reject(new Error(`HTTP ${res.statusCode}`))
      },
      fail: (err) => {
        uni.showToast({ title: '网络异常，请检查网络后重试', icon: 'none' })
        reject(new Error(err.errMsg))
      },
    })
  })
}
