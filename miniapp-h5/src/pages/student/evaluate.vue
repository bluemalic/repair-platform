<script setup lang="ts">
import { onLoad, onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'
import { evaluateTicket, getTicketDetail, reworkTicket } from '@/api/ticket'
import type { TicketDetailVO } from '@/types'

/**
 * 验收：待验收 →（通过）已完成 50 /（不通过）处理中 30。
 *
 * <p>页面上先把**维修结果**摆出来再让人判断——评价要有依据，不然就是凭印象点星。
 *
 * <p>两条出路（`docs/01` §4.1）：**打分通过**是"修完了、质量如何"；**打回重做**是"没修好、重来"。
 * 后者不写评价、师傅不变（还是他返工），所以要说清理由——不写理由师傅只能猜。
 */
const ticketId = ref('')
const detail = ref<TicketDetailVO | null>(null)
/** 不预选：预选 5 星等于把"懒得点"的人也算成满分，满意度数据会变假。 */
const score = ref(0)
const content = ref('')
const submitting = ref(false)

/** 打回重做的理由区：默认收起，避免"看起来像主操作"。 */
const reworkOpen = ref(false)
const reworkReason = ref('')
const reworking = ref(false)

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

/** 打回重做：二次确认，因为它会把这单退回给师傅重新上门。 */
async function submitRework() {
  const reason = reworkReason.value.trim()
  if (reason.length < 2) {
    uni.showToast({ title: '请说明哪里没修好', icon: 'none' })
    return
  }
  const confirmed = await new Promise<boolean>((resolve) => {
    uni.showModal({
      title: '打回重做',
      content: '工单会退回给这位师傅重新处理，确认提交？',
      confirmText: '确认打回',
      cancelText: '再想想',
      success: (res) => resolve(!!res.confirm),
      fail: () => resolve(false),
    })
  })
  if (!confirmed) {
    return
  }
  reworking.value = true
  try {
    await reworkTicket(ticketId.value, reason)
    uni.showToast({ title: '已打回，师傅会重新处理', icon: 'none' })
    setTimeout(() => uni.navigateBack(), 1000)
  } catch {
    // request 里已提示（20002 状态不许等）
  } finally {
    reworking.value = false
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

    <!-- 不通过：修得不行就退回去重做，不用"打低分接受"。默认收起，别让它看起来像主操作 -->
    <view class="card rework">
      <text v-if="!reworkOpen" class="rework-link" @click="reworkOpen = true">修得不行？打回重做</text>
      <template v-else>
        <text class="label">哪里没修好？</text>
        <text class="hint">工单会退回给这位师傅重新处理（不是撤销，也不是换个师傅）</text>
        <textarea v-model="reworkReason" class="textarea" maxlength="255"
                  placeholder="例如：水管还在滴，接头没拧紧" />
        <button class="rework-submit" :loading="reworking" :disabled="reworking" @click="submitRework">
          确认打回
        </button>
      </template>
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
/* 打回重做：次要出口，视觉上不与"提交评价"抢主位 */
.rework {
  background: transparent;
}
.rework-link {
  display: block;
  padding: 8rpx 0;
  font-size: 26rpx;
  color: #909399;
  text-align: center;
  text-decoration: underline;
}
.hint {
  display: block;
  margin-bottom: 16rpx;
  font-size: 24rpx;
  color: #909399;
  line-height: 1.5;
}
.rework-submit {
  margin-top: 20rpx;
  color: #f56c6c;
  background: #fef0f0;
  border-radius: 8rpx;
}
</style>
