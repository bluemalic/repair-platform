import { http } from './http'
import type { LoginVO } from '@/types'

export function login(tenantCode: string, username: string, password: string) {
  return http.post<LoginVO>('/auth/login', { tenantCode, username, password })
}
