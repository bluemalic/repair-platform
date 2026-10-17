<script setup lang="ts">
import { computed, ref } from 'vue'
import { onLoad, onShow, onUnload } from '@dcloudio/uni-app'
import TicketDetail from '@/components/TicketDetail.vue'
import { acceptTicket, arriveTicket, getAdvice, getWorkerTicketDetail, rejectTicket } from '@/api/ticket'
import type { KnowledgeAdviceVO, TicketDetailVO } from '@/types'

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

/**
 * AI 排查建议（阶段 2 · ADR-018）：**按需触发**，不做成自动加载——
 * 每次都要花钱，而且师傅多数时候不需要它。失败（含模型不可用 40004）由 request 统一提示。
 */
const advice = ref<KnowledgeAdviceVO | null>(null)
const adviceLoading = ref(false)

async function loadAdvice() {
  if (adviceLoading.value) {
    return
  }
  adviceLoading.value = true
  try {
    advice.value = await getAdvice(ticketId.value)
  } catch {
    // request 已提示；保持 advice 为 null，师傅可以再点一次
  } finally {
    adviceLoading.value = false
  }
}
</script>

<template>
  <view class="page">
    <view v-if="loading" class="tip">加载中…</view>

    <template v-else-if="detail">
      <TicketDetail :detail="detail" />

      <!-- 排查建议：依据是本校知识库里检索到的条目，引用都摆出来（哪来的、匹配度多少） -->
      <view class="card">
        <text class="label">AI 排查建议</text>
        <text class="hint">按这张单的描述，去本校维修知识库里找处理办法；给不出依据的地方会标出来</text>
        <button v-if="!advice" class="advice-btn" :disabled="adviceLoading" @click="loadAdvice">
          {{ adviceLoading ? '正在查知识库…' : '让助手看看' }}
        </button>

        <template v-else>
          <text class="advice-suggestion">{{ advice.suggestion }}</text>

          <view v-for="(step, index) in advice.steps" :key="index" class="step">
            <text class="step-text">{{ index + 1 }}. {{ step.text }}</text>
            <view class="cites">
              <text v-if="!step.cited" class="cite-warn">没有知识库依据</text>
              <text v-for="cid in step.citations" :key="cid" class="cite">依据 #{{ cid }}</text>
            </view>
          </view>

          <view v-if="advice.sources.length" class="sources">
            <text class="sources-title">参考的条目（匹配度）</text>
            <text v-for="source in advice.sources" :key="source.id" class="source">
              · {{ source.title }}（{{ source.score.toFixed(1) }}）
            </text>
          </view>
          <text v-else class="hint">这次没有检索到相关条目</text>
        </template>
      </view>

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
.advice-btn {
  margin-top: 4rpx;
  color: #2c6cf6;
  font-size: 28rpx;
  background: #f0f7ff;
  border-radius: 8rpx;
}
.advice-suggestion {
  display: block;
  margin-bottom: 16rpx;
  font-size: 28rpx;
  color: #303133;
  line-height: 1.6;
}
.step {
  padding: 16rpx 0;
  border-top: 1rpx solid #f0f2f5;
}
.step-text {
  display: block;
  font-size: 28rpx;
  color: #303133;
  line-height: 1.6;
}
.cites {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
  margin-top: 8rpx;
}
.cite {
  padding: 2rpx 12rpx;
  font-size: 22rpx;
  color: #2c6cf6;
  background: #f0f7ff;
  border-radius: 6rpx;
}
.cite-warn {
  padding: 2rpx 12rpx;
  font-size: 22rpx;
  color: #e6a23c;
  background: #fdf6ec;
  border-radius: 6rpx;
}
.sources {
  margin-top: 16rpx;
  padding-top: 16rpx;
  border-top: 1rpx solid #f0f2f5;
}
.sources-title {
  display: block;
  margin-bottom: 8rpx;
  font-size: 24rpx;
  color: #909399;
}
.source {
  display: block;
  font-size: 24rpx;
  color: #606266;
  line-height: 1.6;
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
