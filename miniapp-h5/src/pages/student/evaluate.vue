<script setup lang="ts">
import { onLoad, onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'
import { evaluateTicket, getTicketDetail } from '@/api/ticket'
import type { TicketDetailVO } from '@/types'

/**
 * 验收评价：待验收 → 已完成（40 → 50）。
 *
 * <p>页面上先把**维修结果**摆出来再让人打分——评价要有依据，不然就是凭印象点星。
 */
const ticketId = ref('')
const detail = ref<TicketDetailVO | null>(null)
/** 不预选：预选 5 星等于把"懒得点"的人也算成满分，满意度数据会变假。 */
const score = ref(0)
const content = ref('')
const submitting = ref(false)

const SCORE_TEXT: Record<number, string> = {
  1: '很不满意',
  2: '不太满意',
  3: '一般',
  4: '比较满意',
  5: '非常满意',
}

onLoad((options) => {
  ticketId.value = options?.id ?? ''
})

onShow(async () => {
  if (!ticketId.value) {
    uni.showToast({ title: '缺少工单参数', icon: 'none' })
    return
  }
  detail.value = await getTicketDetail(ticketId.value)
})

function previewImages(urls: string[] | null, index: number) {
  if (!urls?.length) {
    return
  }
  uni.previewImage({ urls, current: index })
}

async function submit() {
  if (score.value < 1) {
    uni.showToast({ title: '请先选择评分', icon: 'none' })
    return
  }
  submitting.value = true
  try {
    await evaluateTicket(ticketId.value, score.value, content.value.trim() || undefined)
    uni.showToast({ title: '感谢评价', icon: 'success' })
    setTimeout(() => uni.navigateBack(), 800)
  } catch {
    // request 里已提示（20005 重复评价、20002 状态不许等）
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <view class="page">
    <view class="card">
      <text class="label">维修结果</text>
      <text class="text">{{ detail?.resultDesc || '（师傅未填写）' }}</text>
      <view v-if="detail?.resultImages?.length" class="images">
        <image v-for="(url, i) in detail.resultImages" :key="i" class="thumb" :src="url" mode="aspectFill"
               @click="previewImages(detail?.resultImages ?? null, i)" />
      </view>
    </view>

    <view class="card">
      <text class="label">这次维修怎么样？</text>
      <view class="stars">
        <text v-for="n in 5" :key="n" class="star" :class="{ on: n <= score }" @click="score = n">★</text>
        <text class="score-text">{{ SCORE_TEXT[score] ?? '' }}</text>
      </view>
      <textarea v-model="content" class="textarea" maxlength="500" placeholder="想说点什么？（选填）" />
    </view>

    <button class="submit" :loading="submitting" :disabled="submitting" @click="submit">提交评价</button>
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  padding: 24rpx;
  box-sizing: border-box;
  background: #f5f6f8;
}
.card {
  margin-bottom: 20rpx;
  padding: 28rpx;
  background: #fff;
  border-radius: 16rpx;
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
.stars {
  display: flex;
  align-items: center;
  gap: 16rpx;
  margin-bottom: 20rpx;
}
.star {
  font-size: 64rpx;
  line-height: 1;
  color: #dcdfe6;
}
.star.on {
  color: #f7ba2a;
}
.score-text {
  margin-left: 8rpx;
  font-size: 26rpx;
  color: #909399;
}
.textarea {
  width: 100%;
  height: 180rpx;
  padding: 16rpx 20rpx;
  box-sizing: border-box;
  font-size: 28rpx;
  background: #f5f6f8;
  border-radius: 8rpx;
}
.submit {
  margin-top: 8rpx;
  color: #fff;
  background: #2c6cf6;
  border-radius: 8rpx;
}
</style>
