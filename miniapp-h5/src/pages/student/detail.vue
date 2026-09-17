<script setup lang="ts">
import { computed, ref } from 'vue'
import { onLoad, onShow } from '@dcloudio/uni-app'
import TicketDetail from '@/components/TicketDetail.vue'
import { cancelTicket, getTicketDetail } from '@/api/ticket'
import type { TicketDetailVO } from '@/types'

const detail = ref<TicketDetailVO | null>(null)
const loading = ref(true)
const ticketId = ref('')

/** 只有"待派单"能撤（docs/02 状态机：10 → 70）。 */
const canCancel = computed(() => detail.value?.status === 10)

/** 待验收（40）才能评价（40 → 50）。 */
const canEvaluate = computed(() => detail.value?.status === 40)

// 参数只在 onLoad 里取一次；**每次页面显示都重新拉数据**：
// 评价页提交后是 navigateBack 回来的，onLoad 不会重跑 —— 只 load 在 onLoad 的话，
// 用户会看到"评价成功了，页面还写着待验收"（真机验证时就是这么发现的）。
onLoad((options) => {
  ticketId.value = options?.id ?? ''
})

onShow(async () => {
  if (!ticketId.value) {
    uni.showToast({ title: '缺少工单参数', icon: 'none' })
    loading.value = false
    return
  }
  await load(ticketId.value)
})

async function load(id: string) {
  loading.value = true
  try {
    detail.value = await getTicketDetail(id)
  } finally {
    loading.value = false
  }
}

function goEvaluate() {
  uni.navigateTo({ url: `/pages/student/evaluate?id=${detail.value?.id}` })
}

async function doCancel() {
  if (!detail.value) {
    return
  }
  const confirmed = await uni.showModal({
    title: '撤销工单',
    content: '撤销后后勤不会再派单，确认撤销？',
  })
  if (!confirmed.confirm) {
    return
  }
  await cancelTicket(detail.value.id)
  uni.showToast({ title: '已撤销', icon: 'success' })
  await load(detail.value.id)
}
</script>

<template>
  <view class="page">
    <view v-if="loading" class="tip">加载中…</view>
    <template v-else-if="detail">
      <TicketDetail :detail="detail" />
      <button v-if="canEvaluate" class="primary" @click="goEvaluate">验收评价</button>
      <button v-if="canCancel" class="cancel" @click="doCancel">撤销这张报修</button>
    </template>
    <view v-else class="tip">没有查到这张工单</view>
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  padding: 24rpx;
  box-sizing: border-box;
  background: #f5f6f8;
}
.tip {
  margin-top: 160rpx;
  text-align: center;
  font-size: 28rpx;
  color: #909399;
}
.primary {
  margin-top: 16rpx;
  color: #fff;
  background: #2c6cf6;
  border-radius: 8rpx;
}
.cancel {
  margin-top: 16rpx;
  color: #f56c6c;
  font-size: 28rpx;
  background: #fff;
  border-radius: 8rpx;
}
</style>
