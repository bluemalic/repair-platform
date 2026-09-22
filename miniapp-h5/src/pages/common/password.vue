<script setup lang="ts">
import { ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { changePassword } from '@/api/auth'
import { clearLogin } from '@/store/auth'

const oldPassword = ref('')
const newPassword = ref('')
const confirmPassword = ref('')
const loading = ref(false)

/**
 * 首次登录被强制跳过来的（`?force=1`）：文案要说清"为什么非改不可"，
 * 否则用户看到的是一个没头没尾的改密页——他会以为进错了地方。
 */
const force = ref(false)

onLoad((query) => {
  force.value = query?.force === '1'
})

async function submit() {
  if (!oldPassword.value || !newPassword.value || !confirmPassword.value) {
    uni.showToast({ title: '请填写完整', icon: 'none' })
    return
  }
  if (newPassword.value.length < 8) {
    uni.showToast({ title: '新密码至少 8 位', icon: 'none' })
    return
  }
  if (newPassword.value !== confirmPassword.value) {
    // 两次输入一致是前端防手滑：后端只收一个新密码，它没法替你比这一下
    uni.showToast({ title: '两次输入的新密码不一致', icon: 'none' })
    return
  }

  loading.value = true
  try {
    await changePassword(oldPassword.value, newPassword.value)
  } catch {
    // request 已提示（当前密码不正确 / 请求过于频繁等）
    return
  } finally {
    loading.value = false
  }

  // 改密后服务端把该账号的所有会话都注销了（含当前这次），所以必须回登录页重新登录。
  // 这不是"顺手退出"，是设计：改密的动机之一就是怀疑账号被别人用着。
  clearLogin()
  uni.showToast({ title: '密码已修改，请重新登录', icon: 'none' })
  // 等提示露个面再跳，否则 reLaunch 会把它一起带走
  setTimeout(() => uni.reLaunch({ url: '/pages/login/login' }), 800)
}
</script>

<template>
  <view class="page">
    <view class="form">
      <view class="field">
        <text class="label">当前密码</text>
        <input v-model="oldPassword" class="input" password placeholder="请输入当前密码" />
      </view>
      <view class="field">
        <text class="label">新密码</text>
        <input v-model="newPassword" class="input" password placeholder="8-32 位" />
      </view>
      <view class="field">
        <text class="label">确认新密码</text>
        <input v-model="confirmPassword" class="input" password placeholder="再输入一次新密码" />
      </view>

      <button class="submit" :loading="loading" :disabled="loading" @click="submit">保存</button>
    </view>

    <view class="tip">
      {{ force ? '这是管理员发的初始口令，改成你自己的之后才能使用其它功能。' : '改完之后需要用新密码重新登录一次。' }}
    </view>
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  padding: 48rpx 32rpx 0;
  box-sizing: border-box;
  background: #f5f6f8;
}
.form {
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
.submit {
  margin-top: 16rpx;
  color: #fff;
  background: #2c6cf6;
  border-radius: 8rpx;
}
.tip {
  margin-top: 32rpx;
  font-size: 24rpx;
  color: #909399;
  text-align: center;
}
</style>
