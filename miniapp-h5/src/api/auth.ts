import { request } from './http'
import type { LoginVO } from '@/types'

/** 登录：账号密码（学号 / 工号），与三端共用同一个接口。 */
export function login(tenantCode: string, username: string, password: string) {
  return request<LoginVO>({
    url: '/auth/login',
    method: 'POST',
    auth: false,
    data: { tenantCode, username, password },
  })
}

/**
 * 修改自己的密码。**成功之后该账号的所有会话（含当前这次）都已失效**——
 * 调用方必须清本地登录态并回登录页，否则下一个请求会拿到 10002。
 */
export function changePassword(oldPassword: string, newPassword: string) {
  return request<void>({
    url: '/auth/password',
    method: 'PUT',
    data: { oldPassword, newPassword },
  })
}

/** 学校统一认证的可用性（登录页据此决定是否显示入口） */
export interface SsoAvailability {
  tenantName?: string
  enabled: boolean
  protocol?: string | null
}

export function ssoAvailable(tenantCode: string) {
  return request<SsoAvailability>({
    url: '/auth/sso/available',
    auth: false,
    data: { tenantCode },
  })
}

/** 用统一认证回调带回来的一次性登录码换 token（码一次有效） */
export function ssoExchange(code: string) {
  return request<LoginVO>({
    url: '/auth/sso/exchange',
    method: 'POST',
    auth: false,
    data: { code },
  })
}
