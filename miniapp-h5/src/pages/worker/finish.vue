<script setup lang="ts">
import { ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { uploadImage } from '@/api/file'
import { finishTicket } from '@/api/ticket'

/**
 * 完工上报：维修结果说明（必填，≤500 字）+ 维修后照片（选填，最多 3 张）。
 * 提交成功回上一页（详情会自己 onShow 刷新，状态变成"待验收"）。
 */
const ticketId = ref('')
const resultDesc = ref('')
const imageUrls = ref<string[]>([])
const uploading = ref(false)
const submitting = ref(false)

onLoad((options) => {
  ticketId.value = options?.id ?? ''
})

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
    // uploadImage 里已提示；已成功的那些保留
  } finally {
    uploading.value = false
  }
}

function removeImage(index: number) {
  imageUrls.value.splice(index, 1)
}

async function submit() {
  if (!ticketId.value) {
    uni.showToast({ title: '缺少工单参数', icon: 'none' })
    return
  }
  if (!resultDesc.value.trim()) {
    uni.showToast({ title: '请填写维修结果', icon: 'none' })
    return
  }
  submitting.value = true
  try {
    await finishTicket(
      ticketId.value,
      resultDesc.value.trim(),
      imageUrls.value.length ? imageUrls.value : undefined,
    )
    uni.showToast({ title: '已上报，等待验收', icon: 'success' })
    setTimeout(() => uni.navigateBack(), 800)
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
      <text class="label">维修结果</text>
      <textarea v-model="resultDesc" class="textarea" maxlength="500" placeholder="例如：更换了门锁锁芯，测试开关正常" />
    </view>

    <view class="card">
      <text class="label">维修后照片（选填，最多 3 张）</text>
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

    <button class="submit" :loading="submitting" :disabled="submitting" @click="submit">提交完工</button>
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
.textarea {
  width: 100%;
  height: 200rpx;
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
