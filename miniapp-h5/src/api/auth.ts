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
