<script setup lang="ts">
import { computed, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import TicketDetail from '@/components/TicketDetail.vue'
import { cancelTicket, getTicketDetail } from '@/api/ticket'
import type { TicketDetailVO } from '@/types'

const detail = ref<TicketDetailVO | null>(null)
const loading = ref(true)

/** 只有"待派单"能撤（docs/02 状态机：10 → 70）。 */
const canCancel = computed(() => detail.value?.status === 10)

onLoad(async (options) => {
  const id = options?.id
  if (!id) {
    uni.showToast({ title: '缺少工单参数', icon: 'none' })
    loading.value = false
    return
  }
  await load(id)
})

async function load(id: string) {
  loading.value = true
  try {
    detail.value = await getTicketDetail(id)
  } finally {
    loading.value = false
  }
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
.cancel {
  margin-top: 16rpx;
  color: #f56c6c;
  font-size: 28rpx;
  background: #fff;
  border-radius: 8rpx;
}
</style>
