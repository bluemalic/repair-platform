import { http } from './http'
import type { LoginVO } from '@/types'

export function login(tenantCode: string, username: string, password: string) {
  return http.post<LoginVO>('/auth/login', { tenantCode, username, password })
}

/**
 * 修改自己的密码。**成功之后该账号的所有会话（含当前这次）都已失效**——
 * 调用方必须清本地登录态并跳登录页，否则下一个请求会拿到 401。
 */
export function changePassword(oldPassword: string, newPassword: string) {
  return http.put<void>('/auth/password', { oldPassword, newPassword })
}
