<template>
  <view class="page">
    <scroll-view class="chat" scroll-y :scroll-into-view="anchor">
      <view v-for="(m, i) in messages" :key="i" :id="`m${i}`" class="row" :class="m.role">
        <view class="bubble">{{ m.text }}</view>
      </view>
      <view v-if="thinking" class="row assistant">
        <view class="bubble thinking">正在想…</view>
      </view>
      <view id="bottom" class="bottom-pad" />
    </scroll-view>

    <view class="ai-note">本页回复与工单草稿由 AI 生成，仅供参考</view>

    <!-- 位置上下文：扫了码/输过码就常驻显示，之后每轮都带给助手，不必重复说 -->
    <view v-if="repairCode" class="context">
      <text class="context-text">位置 {{ repairCode }}</text>
      <text class="context-clear" @click="clearCode">清除</text>
    </view>

    <view v-if="draft" class="draft">
      <view class="draft-row"><text class="label">类别</text><text>{{ draft.categoryName || '—' }}</text></view>
      <view class="draft-row"><text class="label">位置</text><text>{{ locationText }}</text></view>
      <view class="draft-row"><text class="label">紧急度</text><text>{{ urgencyText }}</text></view>
      <view class="draft-row"><text class="label">问题</text><text>{{ draft.description || '—' }}</text></view>
      <button class="confirm" :disabled="submitting" @click="confirm">
        {{ submitting ? '提交中…' : '确认提交' }}
      </button>
    </view>

    <view class="bar">
      <button class="ghost" @click="goScan">扫码</button>
      <input
        v-model="input"
        class="input"
        placeholder="说说哪里坏了"
        :disabled="thinking"
        confirm-type="send"
        @confirm="send"
      />
      <button class="send" :disabled="thinking || !input.trim()" @click="send()">发送</button>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import { onLoad, onUnload } from '@dcloudio/uni-app'
import { intake } from '@/api/agent'
import { createTicket } from '@/api/ticket'
import type { AgentDraft } from '@/types'

/**
 * 跟助手说一句，它帮你把报修填好。
 *
 * <p><b>落单不是它干的</b>：助手只产出草稿，点"确认提交"调的是与报修页**同一个** createTicket
 * （ADR-017）——所以这里没有任何新的写入路径，数据权限与审计都在原链路上。
 *
 * <p><b>位置始终由用户提供</b>：报修码只作为上下文交给助手（让它别问楼栋房间），
 * 提交时也原样带上、由服务端再解一次（不信任前端，与报修页同口径）。
 */
interface Message {
  role: 'user' | 'assistant'
  text: string
}

const messages = ref<Message[]>([{ role: 'assistant', text: '哪儿坏了？跟我说就行。' }])
const input = ref('')
const thinking = ref(false)
const submitting = ref(false)
const sessionId = ref('')
const repairCode = ref('')
const draft = ref<AgentDraft | null>(null)
const anchor = ref('bottom')

const urgencyText = computed(() => {
  const map: Record<number, string> = { 1: '普通', 2: '紧急', 3: '特急' }
  return draft.value?.urgency ? (map[draft.value.urgency] ?? '普通') : '普通'
})

const locationText = computed(() => {
  const d = draft.value
  if (!d) {
    return '—'
  }
  const parts = [d.buildingName, d.room].filter(Boolean)
  return parts.length ? parts.join(' ') : repairCode.value || '—'
})

onLoad(() => {
  // 扫码页识别成功后回传（与报修页同一套：不共享可变状态，卸载时记得解绑）
  uni.$on('scan:result', onScanResult)
})

onUnload(() => {
  uni.$off('scan:result', onScanResult)
})

function onScanResult(scanned: string) {
  repairCode.value = scanned
  // 扫到码就把这条信息作为一轮发出去：助手据此不必再问位置
  void send('我用报修码定位了位置')
}

function goScan() {
  uni.navigateTo({ url: '/pages/common/scan' })
}

function clearCode() {
  repairCode.value = ''
}

function push(message: Message) {
  messages.value.push(message)
  void nextTick(() => {
    anchor.value = ''
    void nextTick(() => {
      anchor.value = 'bottom'
    })
  })
}

async function send(preset?: string) {
  const text = (preset ?? input.value).trim()
  if (!text || thinking.value) {
    return
  }
  input.value = ''
  push({ role: 'user', text })
  thinking.value = true
  try {
    const res = await intake(text, sessionId.value || undefined, repairCode.value || undefined)
    sessionId.value = res.sessionId
    push({ role: 'assistant', text: res.reply })
    // 草稿跟着返回走：可能这一轮刚补齐，也可能仍是上一次的（服务端合并过）
    draft.value = res.ready && res.draft ? res.draft : null
  } catch {
    // intake 内部已按 code 提示（模型不可用 40004 / 限流 10004 等），这里不再重复弹
  } finally {
    thinking.value = false
  }
}

async function confirm() {
  const d = draft.value
  if (!d || !d.categoryId) {
    return
  }
  if (!repairCode.value) {
    uni.showToast({ title: '请先扫码或输入报修码', icon: 'none' })
    return
  }
  submitting.value = true
  try {
    await createTicket({
      repairCode: repairCode.value,
      categoryId: d.categoryId,
      description: d.description ?? '',
      urgency: d.urgency ?? 1,
    })
    uni.showToast({ title: '已提交', icon: 'success' })
    setTimeout(() => uni.reLaunch({ url: '/pages/student/home' }), 600)
  } catch {
    // createTicket 内部已提示
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  height: 100vh;
  background: #f5f6f8;
}

.chat {
  flex: 1;
  padding: 24rpx;
}

.row {
  display: flex;
  margin-bottom: 20rpx;
}

.row.user {
  justify-content: flex-end;
}

.bubble {
  max-width: 76%;
  padding: 18rpx 24rpx;
  border-radius: 16rpx;
  font-size: 28rpx;
  line-height: 1.5;
  background: #fff;
  color: #1f2d3d;
}

.row.user .bubble {
  background: #1f6feb;
  color: #fff;
}

.bubble.thinking {
  color: #909399;
}

.ai-note {
  padding: 0 24rpx 12rpx;
  font-size: 22rpx;
  color: #909399;
}

.bottom-pad {
  height: 8rpx;
}

.context {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12rpx 24rpx;
  background: #eef4ff;
}

.context-text {
  font-size: 24rpx;
  color: #1f6feb;
}

.context-clear {
  font-size: 24rpx;
  color: #909399;
}

.draft {
  margin: 16rpx 24rpx 0;
  padding: 20rpx 24rpx;
  border-radius: 16rpx;
  background: #fff;
}

.draft-row {
  display: flex;
  margin-bottom: 12rpx;
  font-size: 26rpx;
  color: #1f2d3d;
}

.label {
  width: 120rpx;
  color: #909399;
}

.confirm {
  margin-top: 8rpx;
  background: #1f6feb;
  color: #fff;
  font-size: 28rpx;
}

.bar {
  display: flex;
  align-items: center;
  gap: 12rpx;
  padding: 16rpx 24rpx 32rpx;
  background: #fff;
}

.input {
  flex: 1;
  height: 72rpx;
  padding: 0 20rpx;
  border-radius: 36rpx;
  background: #f5f6f8;
  font-size: 28rpx;
}

.ghost,
.send {
  margin: 0;
  padding: 0 24rpx;
  height: 72rpx;
  line-height: 72rpx;
  font-size: 26rpx;
  border-radius: 36rpx;
}

.ghost {
  background: #f5f6f8;
  color: #1f2d3d;
}

.send {
  background: #1f6feb;
  color: #fff;
}

.send[disabled] {
  opacity: 0.5;
}
</style>
