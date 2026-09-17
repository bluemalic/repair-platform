<script setup lang="ts">
import { onLaunch } from '@dcloudio/uni-app'

onLaunch(() => {
  // 入口只做一件事：什么都没登录就先去登录页。
  // 页面内的"要什么角色"由各页自己在 onLoad 里判断，避免在这里堆一套路由守卫。
  const token = uni.getStorageSync('repair_h5_token')
  if (!token) {
    uni.reLaunch({ url: '/pages/login/login' })
  }
})
</script>

<template>
  <!-- 页面由 pages.json 决定，App.vue 不渲染内容（小程序端也不支持在这里写布局） -->
  <view />
</template>
