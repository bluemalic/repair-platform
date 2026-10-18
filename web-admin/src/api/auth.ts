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

/** 学校统一认证的可用性（登录页据此决定是否显示入口；不含任何 IdP 地址） */
export interface SsoAvailability {
  tenantName?: string
  enabled: boolean
  protocol?: string | null
}

export function ssoAvailable(tenantCode: string) {
  return http.get<SsoAvailability>('/auth/sso/available', { tenantCode })
}

/**
 * 用统一认证回调带回来的一次性登录码换 token。
 *
 * <p>为什么不是回调直接把 token 带回来：URL 会进浏览器历史、Referer 与代理日志。
 * 码一次有效，换完即废。
 */
export function ssoExchange(code: string) {
  return http.post<LoginVO>('/auth/sso/exchange', { code })
}
