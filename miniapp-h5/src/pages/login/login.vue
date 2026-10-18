<script setup lang="ts">
import { ref } from 'vue'
// #ifdef H5
import { onMounted } from 'vue'
// #endif
import { login, ssoAvailable, ssoExchange } from '@/api/auth'
import { goHomeByRole, setLogin } from '@/store/auth'

const tenantCode = ref('gdou')
const username = ref('')
const password = ref('')
const loading = ref(false)

/**
 * 演示部署才显示的账号提示：构建前用 `VITE_DEMO_ACCOUNTS` 注入（见 docs/05 §演示部署）。
 * 不注入就没有这一行——生产站不该提示任何账号信息，而演示站的访客必须知道拿什么登录。
 */
const demoHint = import.meta.env.VITE_DEMO_ACCOUNTS as string | undefined

// #ifdef H5
/**
 * H5：统一认证回来时带着一次性 `ssoCode`（在 `location.search` 上），立刻换 token。
 * 小程序端没有 `location`，这一整段只在 H5 编译（统一认证目前先支持 H5）。
 */
onMounted(async () => {
  const params = new URLSearchParams(location.search)
  const ssoCode = params.get('ssoCode')
  const ssoError = params.get('ssoError')
  if (!ssoCode && !ssoError) {
    return
  }
  history.replaceState(null, '', location.pathname + location.hash)
  if (ssoError) {
    uni.showToast({ title: params.get('ssoMessage') || '统一认证登录失败', icon: 'none' })
    return
  }
  loading.value = true
  try {
    const vo = await ssoExchange(ssoCode as string)
    setLogin(vo)
    goHomeByRole(vo.userType, vo.mustChangePassword)
  } finally {
    loading.value = false
  }
})

/** 统一认证登录：整页跳到后端，由它把浏览器送去学校的 IdP */
async function ssoLogin() {
  const code = tenantCode.value.trim()
  if (!code) {
    uni.showToast({ title: '请先填写学校编码', icon: 'none' })
    return
  }
  try {
    const availability = await ssoAvailable(code)
    if (!availability.enabled) {
      uni.showToast({ title: '这所学校还没有开通统一认证', icon: 'none' })
      return
    }
    location.href = `/api/auth/sso/authorize?tenantCode=${encodeURIComponent(code)}&target=H5`
  } catch {
    // request 里已经弹过提示
  }
}
// #endif

async function submit() {
  if (!tenantCode.value || !username.value || !password.value) {
    uni.showToast({ title: '请填写完整', icon: 'none' })
    return
  }
  loading.value = true
  try {
    const vo = await login(tenantCode.value.trim(), username.value.trim(), password.value)
    setLogin(vo)
    // 初始口令是一次性的：服务端会把这类账号挡在改密页之前
    goHomeByRole(vo.userType, vo.mustChangePassword)
  } catch {
    // request 里已经弹过错误提示（账号密码错误 / 租户不存在等），这里不重复提示
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <view class="page">
    <view class="brand">后勤报修</view>
    <view class="subtitle">学生 / 维修工</view>

    <view class="form">
      <view class="field">
        <text class="label">学校编码</text>
        <input v-model="tenantCode" class="input" placeholder="如 gdou" />
      </view>
      <view class="field">
        <text class="label">学号 / 工号</text>
        <input v-model="username" class="input" placeholder="请输入学号或工号" />
      </view>
      <view class="field">
        <text class="label">密码</text>
        <input v-model="password" class="input" password placeholder="请输入密码" />
      </view>

      <button class="submit" :loading="loading" :disabled="loading" @click="submit">登录</button>
      <!-- #ifdef H5 -->
      <button class="sso" @click="ssoLogin">统一认证登录（学校账号）</button>
      <!-- #endif -->
    </view>

    <view class="tip">后勤管理账号请用电脑端管理后台登录</view>
    <view class="tip">忘记密码请联系学校后勤重置</view>
    <view v-if="demoHint" class="demo-tip">{{ demoHint }}</view>
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  padding: 80rpx 48rpx 0;
  box-sizing: border-box;
  background: #f5f6f8;
}
.brand {
  font-size: 48rpx;
  font-weight: 600;
  color: #2c6cf6;
  text-align: center;
}
.subtitle {
  margin-top: 8rpx;
  font-size: 26rpx;
  color: #909399;
  text-align: center;
}
.form {
  margin-top: 64rpx;
  padding: 40rpx 32rpx;
  background: #fff;
  border-radius: 16rpx;
}
.field {
  margin-bottom: 32rpx;
}
.label {
  display: block;
  margin-bottom: 12rpx;
  font-size: 26rpx;
  color: #606266;
}
.input {
  height: 80rpx;
  padding: 0 20rpx;
  font-size: 30rpx;
  background: #f5f6f8;
  border-radius: 8rpx;
}
.sso {
  margin-top: 16rpx;
  background: transparent;
  color: #2c6cf6;
  font-size: 28rpx;
}
.submit {
  margin-top: 16rpx;
  color: #fff;
  background: #2c6cf6;
  border-radius: 8rpx;
}
.tip {
  margin-top: 32rpx;
  font-size: 24rpx;
  color: #c0c4cc;
  text-align: center;
}
.demo-tip {
  margin-top: 16rpx;
  padding: 0 16rpx;
  font-size: 24rpx;
  line-height: 1.6;
  color: #2c6cf6;
  text-align: center;
}
</style>
