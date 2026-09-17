<script setup lang="ts">
import { ref } from 'vue'
import { onUnload } from '@dcloudio/uni-app'
// #ifdef H5
import { decodeQrFrame } from '@/utils/qr'
// #endif

/**
 * 扫码页：扫房间门口的二维码，拿到 6 位报修码后交回上一页。
 *
 * <p><b>两端两套实现，写在条件编译里</b>（这正是 uni-app 条件编译的用途）：
 * <ul>
 *   <li>H5：`getUserMedia` 取摄像头 + `jsqr` 解码。**浏览器只在 HTTPS 或 localhost 下给摄像头**，
 *       所以 http 域名下这里会明确提示改用"手输"（不是静默失败）</li>
 *   <li>小程序 / App：`uni.scanCode` 一步到位（H5 没有这个 API，这也是文档里写"H5 用手输为主"的原因）</li>
 * </ul>
 *
 * <p>手输兜底**永远显示**：摄像头被拒、贴纸磨损、环境不支持时都靠它。
 * 结果用 `uni.$emit('scan:result', code)` 回传给调用页，避免在页面间共享可变状态。
 */
const manualCode = ref('')

// #ifdef H5
const cameraOn = ref(false)
const cameraError = ref('')
let stream: MediaStream | null = null
let timer: ReturnType<typeof setInterval> | undefined
/** 取帧用的画布，不进 DOM；每次取帧前按视频实际尺寸调整。 */
const canvas = document.createElement('canvas')

function cameraSupported(): boolean {
  return typeof navigator !== 'undefined' && !!navigator.mediaDevices?.getUserMedia
}

async function startCamera() {
  cameraError.value = ''
  if (!cameraSupported()) {
    cameraError.value = '当前环境不能用摄像头（浏览器要求 HTTPS 或 localhost），请用手输'
    return
  }
  try {
    stream = await navigator.mediaDevices.getUserMedia({
      video: { facingMode: 'environment' },
      audio: false,
    })
  } catch {
    cameraError.value = '没能打开摄像头（可能未授权），请用手输'
    return
  }

  // 视频元素用 JS 建、塞进容器：模板里写 <video> / <canvas> 会被 uni 编译成它自己的组件，
  // 拿不到 HTMLVideoElement，也就没法把 MediaStream 挂上去。
  const box = document.getElementById('scan-box')
  const video = document.createElement('video')
  video.setAttribute('playsinline', 'true')
  video.muted = true
  video.style.width = '100%'
  video.style.display = 'block'
  video.style.borderRadius = '12rpx'
  video.style.background = '#000'
  video.srcObject = stream
  await video.play()
  box?.appendChild(video)

  cameraOn.value = true
  // 5 帧/秒足够（人要把码对进框里要几百毫秒），比逐帧解码省电
  timer = setInterval(() => grabAndDecode(video), 200)
}

function grabAndDecode(video: HTMLVideoElement) {
  if (!video.videoWidth) {
    return
  }
  canvas.width = video.videoWidth
  canvas.height = video.videoHeight
  const ctx = canvas.getContext('2d', { willReadFrequently: true })
  if (!ctx) {
    return
  }
  ctx.drawImage(video, 0, 0, canvas.width, canvas.height)
  const code = decodeQrFrame(ctx.getImageData(0, 0, canvas.width, canvas.height))
  if (code) {
    finish(code)
  }
}

/** 必须真的停轨：不然返回上一页后摄像头还亮着（手机上的指示灯会一直亮）。 */
function stopCamera() {
  if (timer !== undefined) {
    clearInterval(timer)
    timer = undefined
  }
  stream?.getTracks().forEach((track) => track.stop())
  stream = null
  cameraOn.value = false
}
// #endif

/** 拿到码（扫码或手输都走这里）：回传给上一页并返回。 */
function finish(code: string) {
  const value = code.trim()
  if (value.length !== 6) {
    uni.showToast({ title: '二维码里不是 6 位报修码', icon: 'none' })
    return
  }
  // #ifdef H5
  stopCamera()
  // #endif
  uni.$emit('scan:result', value)
  uni.navigateBack()
}

function submitManual() {
  if (manualCode.value.trim().length !== 6) {
    uni.showToast({ title: '请输入 6 位报修码', icon: 'none' })
    return
  }
  finish(manualCode.value)
}

// #ifndef H5
/** 小程序 / App 端：系统扫码。H5 没有 uni.scanCode，这段在 H5 构建里会被编译掉。 */
function scanByNative() {
  uni.scanCode({
    success: (res) => finish(res.result),
    fail: () => uni.showToast({ title: '没有扫到二维码', icon: 'none' }),
  })
}
// #endif

onUnload(() => {
  // #ifdef H5
  stopCamera()
  // #endif
})
</script>

<template>
  <view class="page">
    <!-- #ifdef H5 -->
    <view class="card">
      <text class="label">扫码</text>
      <text class="hint">把房间门口的二维码对准取景框，识别到会自动填好报修码</text>
      <view id="scan-box" class="scan-box" />
      <button v-if="!cameraOn" class="primary" @click="startCamera">开启摄像头</button>
      <button v-else class="plain" @click="stopCamera">关闭摄像头</button>
      <text v-if="cameraError" class="error">{{ cameraError }}</text>
    </view>
    <!-- #endif -->

    <view class="card">
      <!-- #ifndef H5 -->
      <button class="primary" @click="scanByNative">扫一扫</button>
      <text class="hint">也可以手输下面的报修码</text>
      <!-- #endif -->
      <text class="label">手输报修码</text>
      <input v-model="manualCode" class="input" type="number" maxlength="6" placeholder="房间门口贴的 6 位数字" />
      <button class="primary" @click="submitManual">确定</button>
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
.hint {
  display: block;
  margin-bottom: 16rpx;
  font-size: 24rpx;
  color: #909399;
  line-height: 1.5;
}
.scan-box {
  margin-bottom: 20rpx;
}
.input {
  height: 80rpx;
  margin-bottom: 20rpx;
  padding: 0 20rpx;
  font-size: 32rpx;
  letter-spacing: 4rpx;
  background: #f5f6f8;
  border-radius: 8rpx;
}
.primary {
  color: #fff;
  background: #2c6cf6;
  border-radius: 8rpx;
}
.plain {
  color: #606266;
  background: #f5f6f8;
  border-radius: 8rpx;
}
.error {
  display: block;
  margin-top: 12rpx;
  font-size: 24rpx;
  color: #f56c6c;
  line-height: 1.5;
}
</style>
