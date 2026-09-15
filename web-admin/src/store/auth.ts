import { reactive } from 'vue'
import type { LoginVO } from '@/types'

const TOKEN_KEY = 'repair_admin_token'
const USER_KEY = 'repair_admin_user'

export const auth = reactive({
  token: localStorage.getItem(TOKEN_KEY) ?? '',
  user: JSON.parse(localStorage.getItem(USER_KEY) ?? 'null') as LoginVO | null,
})

export function getToken(): string {
  return auth.token
}

export function setLogin(vo: LoginVO): void {
  auth.token = vo.tokenValue
  auth.user = vo
  localStorage.setItem(TOKEN_KEY, vo.tokenValue)
  localStorage.setItem(USER_KEY, JSON.stringify(vo))
}

export function clearLogin(): void {
  auth.token = ''
  auth.user = null
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
}
