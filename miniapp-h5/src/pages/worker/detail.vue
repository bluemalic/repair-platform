<script setup lang="ts">
import { computed, ref } from 'vue'
import { onLoad, onShow, onUnload } from '@dcloudio/uni-app'
import TicketDetail from '@/components/TicketDetail.vue'
import { acceptTicket, arriveTicket, getWorkerTicketDetail, rejectTicket } from '@/api/ticket'
import type { TicketDetailVO } from '@/types'

/**
 * 维修工侧的工单详情与三个动作：**接单 → 到场打卡 → 完工上报**（外加驳回）。
 * 按状态显示对应的操作，不做"能点但服务端拒绝"的假按钮。
 */
const detail = ref<TicketDetailVO | null>(null)
const loading = ref(true)
const busy = ref(false)
const ticketId = ref('')

/** 到场打卡要扫/输房间门口的码：服务端会拿它跟工单的楼栋房间比对，不一致返回 20007。 */
const arriveCode = ref('')

const canAccept = computed(() => detail.value?.status === 20)
const canReject = computed(() => detail.value?.status === 20 || detail.value?.status === 30)
const canArrive = computed(() => detail.value?.status === 30 && !detail.value?.arriveTime)
const canFinish = computed(() => detail.value?.status === 30 && !!detail.value?.arriveTime)

onLoad((options) => {
  ticketId.value = options?.id ?? ''
  // 扫码页识别成功后把码 emit 回来（到场要求扫门口那张码：扫码是主入口，手输是兜底）
  uni.$on('scan:result', onScanResult)
})

onUnload(() => {
  uni.$off('scan:result', onScanResult)
})

function onScanResult(code: string) {
  arriveCode.value = code
}

function goScan() {
  uni.navigateTo({ url: '/pages/common/scan' })
}

onShow(async () => {
  if (!ticketId.value) {
    uni.showToast({ title: '缺少工单参数', icon: 'none' })
    loading.value = false
    return
  }
  await load()
})

async function load() {
  loading.value = true
  try {
    detail.value = await getWorkerTicketDetail(ticketId.value)
  } finally {
    loading.value = false
  }
}

/** 动作统一包一层：防重复点击 + 成功后刷新（失败提示由 request 负责）。 */
async function run(action: () => Promise<void>, successText: string) {
  if (busy.value) {
    return
  }
  busy.value = true
  try {
    await action()
    uni.showToast({ title: successText, icon: 'success' })
    await load()
  } catch {
    // request 里已提示（20003 被抢先、20007 码不对、20002 状态不许等）
  } finally {
    busy.value = false
  }
}

function doAccept() {
  return run(() => acceptTicket(ticketId.value), '已接单，别忘了到场打卡')
}

async function doReject() {
  const res = await uni.showModal({
    title: '驳回工单',
    editable: true,
    placeholderText: '驳回理由（必填）',
  })
  const reason = (res.content ?? '').trim()
  if (!res.confirm) {
    return
  }
  if (!reason) {
    uni.showToast({ title: '请填写驳回理由', icon: 'none' })
    return
  }
  await run(() => rejectTicket(ticketId.value, reason), '已驳回')
}

async function doArrive() {
  const code = arriveCode.value.trim()
  if (code.length !== 6) {
    uni.showToast({ title: '请输入房间门口的 6 位报修码', icon: 'none' })
    return
  }
  await run(() => arriveTicket(ticketId.value, code), '已到场')
}

function goFinish() {
  uni.navigateTo({ url: `/pages/worker/finish?id=${ticketId.value}` })
}
</script>

<template>
  <view class="page">
    <view v-if="loading" class="tip">加载中…</view>

    <template v-else-if="detail">
      <TicketDetail :detail="detail" />

      <!-- 到场打卡：单独一块输入，因为它是"人要真的到房间"的凭据，不该做成一个随手可点的按钮 -->
      <view v-if="canArrive" class="card">
        <text class="label">到场打卡</text>
        <text class="hint">输入房间门口的 6 位报修码（与工单位置不一致会被拒绝）</text>
        <input v-model="arriveCode" class="input" type="number" maxlength="6" placeholder="如 482913" />
        <button class="scan" @click="goScan">扫房间门口的码</button>
      </view>

      <view class="actions">
        <button v-if="canAccept" class="primary" :disabled="busy" @click="doAccept">接单</button>
        <button v-if="canArrive" class="primary" :disabled="busy" @click="doArrive">确认到场</button>
        <button v-if="canFinish" class="primary" :disabled="busy" @click="goFinish">完工上报</button>
        <button v-if="canReject" class="danger" :disabled="busy" @click="doReject">驳回</button>
      </view>
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
.card {
  margin-bottom: 20rpx;
  padding: 28rpx;
  background: #fff;
  border-radius: 16rpx;
}
.label {
  display: block;
  font-size: 28rpx;
  font-weight: 600;
  color: #303133;
}
.hint {
  display: block;
  margin: 10rpx 0 16rpx;
  font-size: 24rpx;
  color: #c0c4cc;
  line-height: 1.5;
}
.input {
  height: 80rpx;
  padding: 0 20rpx;
  font-size: 32rpx;
  letter-spacing: 4rpx;
  background: #f5f6f8;
  border-radius: 8rpx;
}
.scan {
  margin-top: 16rpx;
  color: #2c6cf6;
  font-size: 28rpx;
  background: #f0f7ff;
  border-radius: 8rpx;
}
.actions {
  display: flex;
  flex-direction: column;
  gap: 16rpx;
  margin-top: 8rpx;
}
.primary {
  color: #fff;
  background: #2c6cf6;
  border-radius: 8rpx;
}
.danger {
  color: #f56c6c;
  background: #fff;
  border-radius: 8rpx;
}
</style>
