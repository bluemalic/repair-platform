<script setup lang="ts">
/**
 * 备案页脚。落地页只在 FAQ 折叠区保留“个人技术演示”说明，所以允许单独隐藏这一行；
 * 登录页与后台仍默认展示（它们没有 FAQ 可以承载这句说明）。
 */
withDefaults(defineProps<{ showDisclaimer?: boolean }>(), { showDisclaimer: true })
</script>

<template>
  <div class="beian">
    <a href="https://beian.miit.gov.cn/" target="_blank" rel="noopener">粤ICP备2026159937号-1</a>
    <a
      href="https://beian.mps.gov.cn/#/query/webSearch?code=44098202441218"
      target="_blank"
      rel="noreferrer"
    >粤公网安备44098202441218号</a>
    <span v-if="showDisclaimer">个人技术演示项目，非学校官方系统</span>
  </div>
</template>

<style scoped>
.beian {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: center;
  gap: 12px;
  font-size: 12px;
  line-height: 1.6;
  color: #5b7083;
}
.beian a {
  color: inherit;
  text-decoration: none;
}
.beian a:hover {
  text-decoration: underline;
}
</style>
