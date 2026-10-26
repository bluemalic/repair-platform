<script setup lang="ts">
import { ref } from 'vue'
import { onPullDownRefresh, onReachBottom, onShow } from '@dcloudio/uni-app'
import { pageMyTickets } from '@/api/ticket'
import { auth, clearLogin } from '@/store/auth'
import type { TicketVO } from '@/types'

/** 状态字典：与后端 TicketStatus 一一对应（docs/02）。 */
const STATUS: Record<number, { label: string; color: string }> = {
  10: { label: '待派单', color: '#e6a23c' },
  20: { label: '待接单', color: '#409eff' },
  30: { label: '处理中', color: '#409eff' },
  35: { label: '待料', color: '#e6a23c' },
  40: { label: '待验收', color: '#909399' },
  45: { label: '无法维修', color: '#f56c6c' },
  50: { label: '已完成', color: '#67c23a' },
  60: { label: '已关闭', color: '#909399' },
  70: { label: '已撤单', color: '#909399' },
  75: { label: '已合并', color: '#909399' },
  80: { label: '已驳回', color: '#f56c6c' },
}

const rows = ref<TicketVO[]>([])
const pageNum = ref(1)
const loading = ref(false)
const hasMore = ref(true)

/**
 * 列表分页逻辑在两个角色页里各写一份，**故意不抽公共组件**：
 * 抽的话得把"取数函数"当 props 传进去，而小程序端对函数 props 不友好（要跨原生组件边界）。
 * 等下一批两端的差异（学生要"撤单"、维修工要"接单/到场/完工"）出来后再看值不值得抽。
 */
async function load(reset = false) {
  if (loading.value) {
    return
  }
  if (!reset && !hasMore.value) {
    return
  }
  loading.value = true
  try {
    const page = await pageMyTickets(reset ? 1 : pageNum.value)
    rows.value = reset ? page.list : [...rows.value, ...page.list]
    pageNum.value = page.pageNum + 1
    hasMore.value = page.pageNum < page.pages
  } catch {
    // request 已提示
  } finally {
    loading.value = false
  }
}

onShow(() => load(true))

onPullDownRefresh(async () => {
  await load(true)
  uni.stopPullDownRefresh()
})

onReachBottom(() => load())

function logout() {
  clearLogin()
  uni.reLaunch({ url: '/pages/login/login' })
}

function goPassword() {
  uni.navigateTo({ url: '/pages/common/password' })
}

function openDetail(row: TicketVO) {
  uni.navigateTo({ url: `/pages/student/detail?id=${row.id}` })
}

function goSubmit() {
  uni.navigateTo({ url: '/pages/student/submit' })
}

function goAgent() {
  uni.navigateTo({ url: '/pages/student/agent' })
}
</script>

<template>
  <view class="page">
    <view class="header">
      <text class="hello">{{ auth.user?.realName }}，你好</text>
      <view class="header-actions">
        <text class="action" @click="goPassword">修改密码</text>
        <text class="action" @click="logout">退出</text>
      </view>
    </view>

    <button class="primary" @click="goSubmit">新建报修</button>

    <view v-if="rows.length === 0 && !loading" class="empty">
      <text>还没有报修记录</text>
      <text class="empty-tip">点上面的「新建报修」，输入房间门口的 6 位报修码</text>
    </view>

    <view v-for="row in rows" :key="row.id" class="card" @click="() => openDetail(row)">
      <view class="card-head">
        <text class="position">{{ row.buildingName }} {{ row.room }}</text>
        <text class="status" :style="{ color: STATUS[row.status]?.color }">
          {{ STATUS[row.status]?.label ?? row.status }}
        </text>
      </view>
      <view class="card-body">
        <text>{{ row.categoryName }}</text>
        <text class="dot">·</text>
        <text>{{ row.submitTime }}</text>
      </view>
      <view class="card-foot">
        <text class="ticket-no">{{ row.ticketNo }}</text>
        <text v-if="row.arriveMinutes !== null" class="muted">响应 {{ row.arriveMinutes }} 分钟</text>
      </view>
    </view>

    <view v-if="rows.length > 0" class="footer">
      <text>{{ hasMore ? (loading ? '加载中…' : '上拉加载更多') : '没有更多了' }}</text>
    </view>

    <view class="ai-fab" hover-class="ai-fab-hover" aria-label="AI 助手" @click="goAgent">
      <view class="ai-bubble">
        <text>AI 助手</text>
        <view class="ai-bubble-tail"></view>
      </view>
      <view class="ai-fab-button">
        <image class="ai-logo" src="/static/ai-logo.png" mode="aspectFit" />
      </view>
    </view>
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  padding: 24rpx 24rpx 200rpx;
  box-sizing: border-box;
  background: #f5f8fa;
}
.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8rpx 8rpx 24rpx;
}
.hello {
  font-size: 32rpx;
  font-weight: 600;
  color: #0b1f33;
}
.header-actions {
  display: flex;
  gap: 24rpx;
}
.action {
  font-size: 26rpx;
  color: #1d4ed8;
}
.empty {
  margin-top: 160rpx;
  text-align: center;
  font-size: 28rpx;
  color: #64748b;
}
.empty-tip {
  display: block;
  margin-top: 12rpx;
  font-size: 24rpx;
  color: #64748b;
}
.primary {
  margin-bottom: 24rpx;
  color: #fff;
  background: #0f2a44;
  border-radius: 12rpx;
}
.card {
  margin-bottom: 20rpx;
  padding: 28rpx;
  background: #fff;
  border: 1rpx solid #dce5ec;
  border-radius: 16rpx;
}
.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.position {
  font-size: 32rpx;
  font-weight: 600;
  color: #0b1f33;
}
.status {
  font-size: 26rpx;
}
.card-body {
  margin-top: 16rpx;
  font-size: 26rpx;
  color: #5b7083;
}
.dot {
  margin: 0 12rpx;
  color: #94a3b8;
}
.card-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 16rpx;
}
.ticket-no {
  font-size: 24rpx;
  color: #64748b;
}
.muted {
  font-size: 24rpx;
  color: #64748b;
}
.footer {
  padding: 24rpx 0 48rpx;
  text-align: center;
  font-size: 24rpx;
  color: #64748b;
}

/* 右下角 AI 助手：固定悬浮 + 气泡提示 */
.ai-fab {
  position: fixed;
  right: 32rpx;
  bottom: calc(40rpx + env(safe-area-inset-bottom));
  z-index: 30;
  display: flex;
  align-items: center;
  gap: 12rpx;
}
.ai-fab-hover {
  transform: scale(0.96);
}
.ai-bubble {
  position: relative;
  padding: 12rpx 20rpx;
  border-radius: 999rpx;
  color: #0f2a44;
  background: #fff;
  font-size: 24rpx;
  font-weight: 600;
  line-height: 1.4;
  white-space: nowrap;
  box-shadow: 0 8rpx 24rpx rgba(11, 31, 51, 0.14);
}
.ai-bubble-tail {
  position: absolute;
  top: 50%;
  right: -7rpx;
  width: 14rpx;
  height: 14rpx;
  background: #fff;
  transform: translateY(-50%) rotate(45deg);
}
.ai-fab-button {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 108rpx;
  height: 108rpx;
  border: 2rpx solid #dce5ec;
  border-radius: 50%;
  background: #fff;
  box-shadow: 0 12rpx 30rpx rgba(11, 31, 51, 0.18);
}
.ai-logo {
  width: 84rpx;
  height: 84rpx;
}
</style>
