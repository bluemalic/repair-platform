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
  40: { label: '待验收', color: '#909399' },
  50: { label: '已完成', color: '#67c23a' },
  60: { label: '已关闭', color: '#909399' },
  70: { label: '已撤单', color: '#909399' },
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

function openDetail() {
  // 下一批：工单详情 + 撤单
  uni.showToast({ title: '工单详情下一批做', icon: 'none' })
}
</script>

<template>
  <view class="page">
    <view class="header">
      <text class="hello">{{ auth.user?.realName }}，你好</text>
      <text class="logout" @click="logout">退出</text>
    </view>

    <view v-if="rows.length === 0 && !loading" class="empty">
      <text>还没有报修记录</text>
      <text class="empty-tip">扫码报修下一批做（也可以先让后勤帮你建单）</text>
    </view>

    <view v-for="row in rows" :key="row.id" class="card" @click="openDetail">
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
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  padding: 24rpx;
  box-sizing: border-box;
  background: #f5f6f8;
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
  color: #303133;
}
.logout {
  font-size: 26rpx;
  color: #2c6cf6;
}
.empty {
  margin-top: 160rpx;
  text-align: center;
  font-size: 28rpx;
  color: #909399;
}
.empty-tip {
  display: block;
  margin-top: 12rpx;
  font-size: 24rpx;
  color: #c0c4cc;
}
.card {
  margin-bottom: 20rpx;
  padding: 28rpx;
  background: #fff;
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
  color: #303133;
}
.status {
  font-size: 26rpx;
}
.card-body {
  margin-top: 16rpx;
  font-size: 26rpx;
  color: #606266;
}
.dot {
  margin: 0 12rpx;
  color: #c0c4cc;
}
.card-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 16rpx;
}
.ticket-no {
  font-size: 24rpx;
  color: #909399;
}
.muted {
  font-size: 24rpx;
  color: #909399;
}
.footer {
  padding: 24rpx 0 48rpx;
  text-align: center;
  font-size: 24rpx;
  color: #c0c4cc;
}
</style>
