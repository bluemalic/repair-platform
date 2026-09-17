<script setup lang="ts">
import { computed, ref } from 'vue'
import { onLoad, onUnload } from '@dcloudio/uni-app'
import { listEnabledCategories } from '@/api/category'
import { uploadImage } from '@/api/file'
import { createTicket, getPositionByCode } from '@/api/ticket'
import type { CategoryVO, RepairCodeVO } from '@/types'

/**
 * 扫码/手输报修。
 *
 * <p>**H5 端没有 `uni.scanCode`**（该 API 只在 App / 小程序端有），所以这里的主入口是**手输 6 位码**；
 * 摄像头扫码要 `getUserMedia` + 二维码解析库，且必须 HTTPS，属于后补的能力（AGENTS §3.2）。
 * 手输这一路还必须留着——摄像头权限被拒、贴纸磨损时只能靠它。
 */
const code = ref('')
const position = ref<RepairCodeVO | null>(null)
const looking = ref(false)

const categories = ref<CategoryVO[]>([])
const categoryIndex = ref(-1)
const categoryNames = computed(() => categories.value.map((c) => c.name))

const description = ref('')
const urgency = ref(1)
const urgencyOptions = ['普通', '紧急', '特急']
const imageUrls = ref<string[]>([])
const uploading = ref(false)
const submitting = ref(false)

onLoad(async () => {
  try {
    categories.value = await listEnabledCategories()
  } catch {
    // request 里已提示
  }
  // 扫码页识别成功后把码 emit 回来（不共享可变状态，页面卸载时记得解绑）
  uni.$on('scan:result', onScanResult)
})

onUnload(() => {
  uni.$off('scan:result', onScanResult)
})

/** 扫到码就填上并直接定位，少一步点击。 */
function onScanResult(scanned: string) {
  code.value = scanned
  lookup()
}

function goScan() {
  uni.navigateTo({ url: '/pages/common/scan' })
}

/** 查位置：拿到楼栋+房间后前端只做展示，提交时只把码给后端（服务端再解一次，不信任前端）。 */
async function lookup() {
  const value = code.value.trim()
  if (value.length !== 6) {
    uni.showToast({ title: '请输入 6 位报修码', icon: 'none' })
    return
  }
  looking.value = true
  try {
    position.value = await getPositionByCode(value)
    uni.showToast({ title: '已定位到房间', icon: 'none' })
  } catch {
    position.value = null
  } finally {
    looking.value = false
  }
}

function onUrgencyChange(event: { detail: { value: number | string } }) {
  urgency.value = Number(event.detail.value) + 1
}

function onCategoryChange(event: { detail: { value: number | string } }) {
  const index = Number(event.detail.value)
  categoryIndex.value = index
  // 类别自带默认紧急度（如水电默认紧急），选了类别就先按它的来，用户仍可改
  urgency.value = categories.value[index]?.defaultUrgency ?? 1
}

async function chooseImages() {
  const remain = 3 - imageUrls.value.length
  if (remain <= 0) {
    uni.showToast({ title: '最多 3 张', icon: 'none' })
    return
  }
  const res = await uni.chooseImage({ count: remain, sizeType: ['compressed'] })
  const paths = res.tempFilePaths as string[]
  uploading.value = true
  try {
    for (const path of paths) {
      const uploaded = await uploadImage(path)
      imageUrls.value.push(uploaded.url)
    }
  } catch {
    // uploadImage 里已提示，这里保住已成功的那些
  } finally {
    uploading.value = false
  }
}

function removeImage(index: number) {
  imageUrls.value.splice(index, 1)
}

async function submit() {
  if (!position.value) {
    uni.showToast({ title: '请先扫码或输入报修码', icon: 'none' })
    return
  }
  if (categoryIndex.value < 0) {
    uni.showToast({ title: '请选择报修类别', icon: 'none' })
    return
  }
  if (!description.value.trim()) {
    uni.showToast({ title: '请描述一下问题', icon: 'none' })
    return
  }
  submitting.value = true
  try {
    await createTicket({
      repairCode: code.value.trim(),
      categoryId: categories.value[categoryIndex.value].id,
      description: description.value.trim(),
      images: imageUrls.value.length ? imageUrls.value : undefined,
      urgency: urgency.value,
    })
    uni.showToast({ title: '报修已提交', icon: 'success' })
    setTimeout(() => uni.reLaunch({ url: '/pages/student/home' }), 800)
  } catch {
    // request 里已提示
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <view class="page">
    <view class="card">
      <view class="field">
        <text class="label">报修码</text>
        <view class="code-row">
          <input v-model="code" class="input" type="number" maxlength="6" placeholder="房间门口贴的 6 位数字" />
          <button class="lookup" :loading="looking" :disabled="looking" @click="lookup">定位</button>
        </view>
        <button class="scan" @click="goScan">扫二维码</button>
        <text class="hint">扫不出来就手输：摄像头需要 HTTPS 或 localhost，且要授权</text>
      </view>

      <view v-if="position" class="position">
        <text class="position-title">{{ position.buildingName }} {{ position.room }}</text>
        <text class="hint">位置由报修码确定，不需要手填</text>
      </view>
    </view>

    <view class="card">
      <view class="field">
        <text class="label">报修类别</text>
        <picker mode="selector" :range="categoryNames" @change="onCategoryChange">
          <view class="picker">
            {{ categoryIndex < 0 ? '请选择' : categoryNames[categoryIndex] }}
          </view>
        </picker>
      </view>

      <view class="field">
        <text class="label">紧急度</text>
        <picker mode="selector" :range="urgencyOptions" :value="urgency - 1" @change="onUrgencyChange">
          <view class="picker">{{ urgencyOptions[urgency - 1] }}</view>
        </picker>
      </view>

      <view class="field">
        <text class="label">问题描述</text>
        <textarea v-model="description" class="textarea" maxlength="500" placeholder="例如：洗手池下水管漏水" />
      </view>

      <view class="field">
        <text class="label">现场照片（选填，最多 3 张）</text>
        <view class="images">
          <view v-for="(url, i) in imageUrls" :key="url" class="thumb-wrap">
            <image class="thumb" :src="url" mode="aspectFill" />
            <text class="remove" @click="removeImage(i)">×</text>
          </view>
          <view v-if="imageUrls.length < 3" class="add" @click="chooseImages">
            <text>{{ uploading ? '上传中…' : '+ 添加' }}</text>
          </view>
        </view>
      </view>
    </view>

    <button class="submit" :loading="submitting" :disabled="submitting" @click="submit">提交报修</button>
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
.field {
  margin-bottom: 28rpx;
}
.label {
  display: block;
  margin-bottom: 12rpx;
  font-size: 28rpx;
  color: #303133;
  font-weight: 600;
}
.code-row {
  display: flex;
  align-items: center;
  gap: 16rpx;
}
.input {
  flex: 1;
  height: 80rpx;
  padding: 0 20rpx;
  font-size: 32rpx;
  letter-spacing: 4rpx;
  background: #f5f6f8;
  border-radius: 8rpx;
}
.lookup {
  width: 160rpx;
  height: 80rpx;
  margin: 0;
  color: #fff;
  font-size: 28rpx;
  line-height: 80rpx;
  background: #2c6cf6;
  border-radius: 8rpx;
}
.scan {
  margin-top: 16rpx;
  color: #2c6cf6;
  font-size: 28rpx;
  background: #f0f7ff;
  border-radius: 8rpx;
}
.hint {
  display: block;
  margin-top: 10rpx;
  font-size: 24rpx;
  color: #c0c4cc;
  line-height: 1.5;
}
.position {
  padding: 20rpx;
  background: #f0f7ff;
  border-radius: 8rpx;
}
.position-title {
  font-size: 32rpx;
  font-weight: 600;
  color: #2c6cf6;
}
.picker {
  height: 80rpx;
  padding: 0 20rpx;
  font-size: 30rpx;
  line-height: 80rpx;
  background: #f5f6f8;
  border-radius: 8rpx;
}
.textarea {
  width: 100%;
  height: 160rpx;
  padding: 16rpx 20rpx;
  box-sizing: border-box;
  font-size: 28rpx;
  background: #f5f6f8;
  border-radius: 8rpx;
}
.images {
  display: flex;
  flex-wrap: wrap;
  gap: 16rpx;
}
.thumb-wrap {
  position: relative;
}
.thumb {
  width: 160rpx;
  height: 160rpx;
  border-radius: 8rpx;
}
.remove {
  position: absolute;
  top: -12rpx;
  right: -12rpx;
  width: 40rpx;
  height: 40rpx;
  font-size: 28rpx;
  line-height: 40rpx;
  text-align: center;
  color: #fff;
  background: #f56c6c;
  border-radius: 50%;
}
.add {
  width: 160rpx;
  height: 160rpx;
  font-size: 26rpx;
  line-height: 160rpx;
  text-align: center;
  color: #909399;
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
