import { reactive } from 'vue'
import type { LoginVO } from '@/types'

const TOKEN_KEY = 'repair_h5_token'
const USER_KEY = 'repair_h5_user'

/**
 * 登录态。用 `uni.getStorageSync` 而不是 `localStorage`：H5 端它内部就是 localStorage，
 * 小程序端会换成自己的存储——同一套代码两端可用（这也是不用 `window.localStorage` 的原因）。
 */
export const auth = reactive({
  token: (uni.getStorageSync(TOKEN_KEY) as string) || '',
  user: (uni.getStorageSync(USER_KEY) as LoginVO | '') || null,
})

export function getToken(): string {
  return auth.token
}

export function setLogin(vo: LoginVO): void {
  auth.token = vo.tokenValue
  auth.user = vo
  uni.setStorageSync(TOKEN_KEY, vo.tokenValue)
  uni.setStorageSync(USER_KEY, vo)
}

export function clearLogin(): void {
  auth.token = ''
  auth.user = null
  uni.removeStorageSync(TOKEN_KEY)
  uni.removeStorageSync(USER_KEY)
}

/**
 * 登录后按角色跳到对应首页。后勤管理（3）不走移动端，提示去管理端。
 *
 * <p>**还在用初始口令的账号先去改密页**：管理员设的初始口令是一次性的——学生那边口令只能
 * 统一发放，而学号在班里是公开的，同学之间可以互相登录。
 *
 * <p>这里只是引导，真正的强制在服务端：未改密的账号调业务接口会被回 10003
 * （只放行 /api/auth/**），所以就算有人绕过这个跳转也做不了别的。
 */
export function goHomeByRole(userType: number, mustChangePassword?: boolean): void {
  if (mustChangePassword) {
    uni.reLaunch({ url: '/pages/common/password?force=1' })
    return
  }
  if (userType === 1) {
    uni.reLaunch({ url: '/pages/student/home' })
    return
  }
  if (userType === 2) {
    uni.reLaunch({ url: '/pages/worker/home' })
    return
  }
  uni.showModal({
    title: '请使用管理端',
    content: '后勤管理账号请用电脑端管理后台（web-admin）登录，移动端只面向学生与维修工。',
    showCancel: false,
  })
}
