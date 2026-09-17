<script setup lang="ts">
import type { TicketDetailVO } from '@/types'

/**
 * 工单详情的**纯展示**部分：基本信息 / 描述与图片 / 维修结果 / 驳回原因 / 评价 / 流转时间线。
 *
 * <p>学生端与维修工端共用它，各自的**操作区**（学生：撤单；维修工：接单 / 到场 / 完工）留在各自页面里，
 * 用 `#actions` 插槽塞进来。
 *
 * <p>做成组件而不是两端各写一遍：两端这里**确实完全一样**，而差异（操作）本来就在别处。
 * 注意只传数据、不传函数——小程序端对"函数当 props"不友好（AGENTS §3.2）。
 */
defineProps<{ detail: TicketDetailVO }>()

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

const ACTION: Record<string, string> = {
  SUBMIT: '提交报修',
  DISPATCH: '派单',
  ACCEPT: '接单',
  ARRIVE: '到场打卡',
  FINISH: '完工上报',
  EVALUATE: '验收评价',
  CANCEL: '撤销工单',
  CLOSE: '关闭工单',
  REJECT: '驳回',
  AUTO_CLOSE: '超时自动关闭',
  ACCEPT_TIMEOUT: '接单超时提醒',
  PROCESS_TIMEOUT: '处理超时升级',
}

/** 模板里不能直接写 `uni.xxx`（模板作用域取不到这个全局），所以包一层方法。 */
function previewImages(urls: string[] | null, index: number) {
  if (!urls?.length) {
    return
  }
  uni.previewImage({ urls, current: index })
}

function statusLabel(status: number | null): string {
  if (status === null || status === undefined) {
    return '—'
  }
  return STATUS[status]?.label ?? String(status)
}
</script>

<template>
  <view>
    <view class="card">
      <view class="head">
        <text class="position">{{ detail.buildingName }} {{ detail.room }}</text>
        <text class="status" :style="{ color: STATUS[detail.status]?.color }">
          {{ STATUS[detail.status]?.label ?? detail.status }}
        </text>
      </view>
      <view class="meta">
        <text>{{ detail.categoryName }}</text>
        <text class="dot">·</text>
        <text>{{ detail.ticketNo }}</text>
      </view>
      <view class="meta">
        <text>提交 {{ detail.submitTime }}</text>
      </view>
      <view class="meta">
        <text v-if="detail.dispatchTime">派单 {{ detail.dispatchTime }}</text>
        <text v-if="detail.arriveTime"> · 到场 {{ detail.arriveTime }}</text>
      </view>
      <view v-if="detail.arriveMinutes !== null || detail.handleMinutes !== null" class="meta">
        <text v-if="detail.arriveMinutes !== null">响应 {{ detail.arriveMinutes }} 分钟</text>
        <text v-if="detail.handleMinutes !== null">　处理 {{ detail.handleMinutes }} 分钟</text>
      </view>
    </view>

    <view class="card">
      <text class="label">问题描述</text>
      <text class="text">{{ detail.description || '（未填写）' }}</text>
      <view v-if="detail.images?.length" class="images">
        <image v-for="(url, i) in detail.images" :key="i" class="thumb" :src="url" mode="aspectFill"
               @click="previewImages(detail.images, i)" />
      </view>
    </view>

    <view v-if="detail.resultDesc || detail.resultImages?.length" class="card">
      <text class="label">维修结果</text>
      <text class="text">{{ detail.resultDesc || '（未填写）' }}</text>
      <view v-if="detail.resultImages?.length" class="images">
        <image v-for="(url, i) in detail.resultImages" :key="i" class="thumb" :src="url" mode="aspectFill"
               @click="previewImages(detail.resultImages, i)" />
      </view>
    </view>

    <view v-if="detail.rejectReason" class="card">
      <text class="label">驳回原因</text>
      <text class="text">{{ detail.rejectReason }}</text>
    </view>

    <view v-if="detail.evaluation" class="card">
      <text class="label">验收评价</text>
      <text class="text">{{ detail.evaluation.score }} 星 · {{ detail.evaluation.content || '未留言' }}</text>
    </view>

    <view class="card">
      <text class="label">进度</text>
      <view v-for="log in detail.logs" :key="log.id" class="log">
        <view class="log-dot" />
        <view class="log-body">
          <view class="log-head">
            <text class="log-action">{{ ACTION[log.action] ?? log.action }}</text>
            <text v-if="log.fromStatus !== log.toStatus" class="log-status">
              {{ statusLabel(log.fromStatus) }} → {{ statusLabel(log.toStatus) }}
            </text>
          </view>
          <text class="log-time">{{ log.createTime }}</text>
          <text v-if="log.remark" class="log-remark">{{ log.remark }}</text>
        </view>
      </view>
    </view>
  </view>
</template>

<style scoped>
.card {
  margin-bottom: 20rpx;
  padding: 28rpx;
  background: #fff;
  border-radius: 16rpx;
}
.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.position {
  font-size: 34rpx;
  font-weight: 600;
  color: #303133;
}
.status {
  font-size: 28rpx;
}
.meta {
  margin-top: 12rpx;
  font-size: 26rpx;
  color: #909399;
}
.dot {
  margin: 0 12rpx;
}
.label {
  display: block;
  margin-bottom: 12rpx;
  font-size: 28rpx;
  font-weight: 600;
  color: #303133;
}
.text {
  font-size: 28rpx;
  color: #606266;
  line-height: 1.6;
}
.images {
  display: flex;
  flex-wrap: wrap;
  gap: 16rpx;
  margin-top: 16rpx;
}
.thumb {
  width: 180rpx;
  height: 180rpx;
  border-radius: 8rpx;
}
.log {
  display: flex;
  gap: 16rpx;
  padding: 16rpx 0;
  border-bottom: 1rpx solid #f0f0f0;
}
.log-dot {
  width: 12rpx;
  height: 12rpx;
  margin-top: 12rpx;
  background: #2c6cf6;
  border-radius: 50%;
}
.log-body {
  flex: 1;
}
.log-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.log-action {
  font-size: 28rpx;
  color: #303133;
}
.log-status {
  font-size: 24rpx;
  color: #909399;
}
.log-time {
  display: block;
  margin-top: 6rpx;
  font-size: 24rpx;
  color: #c0c4cc;
}
.log-remark {
  display: block;
  margin-top: 6rpx;
  font-size: 26rpx;
  color: #606266;
}
</style>
