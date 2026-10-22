<template>
  <view class="beian">
    <!-- #ifdef H5 -->
    <a class="beian-link" href="https://beian.miit.gov.cn/" target="_blank" rel="noopener">粤ICP备2026159937号-1</a>
    <a
      class="beian-link"
      href="https://beian.mps.gov.cn/#/query/webSearch?code=44098202441218"
      target="_blank"
      rel="noreferrer"
    >粤公网安备44098202441218号</a>
    <!-- #endif -->
    <!-- #ifndef H5 -->
    <text class="beian-link">粤ICP备2026159937号-1</text>
    <text class="beian-link">粤公网安备44098202441218号</text>
    <!-- #endif -->
    <text class="beian-note">个人技术演示项目，非学校官方系统</text>
  </view>
</template>

<style scoped>
.beian {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: center;
  gap: 16rpx;
  padding: 24rpx 24rpx 32rpx;
  font-size: 22rpx;
  line-height: 1.6;
  color: #5b7083;
}
.beian-link {
  color: #5b7083;
  text-decoration: none;
}
.beian-note {
  color: #64748b;
}
</style>
