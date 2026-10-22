<script setup lang="ts">
/**
 * 品牌标记：一个内联 SVG 图标 + 文字。
 *
 * <p>不引图标库、不依赖图片文件：落地页、登录页、后台侧栏共用同一个组件，
 * 以后换品牌只要改这里。图标语义是"校园里的房子 + 报修单"，不是具体某所学校的校徽。
 */
withDefaults(
  defineProps<{
    /** 副标题，如 SaaS / 管理端 / 平台运营 */
    subtitle?: string
    /** sm 用于后台侧栏，md 用于落地页与登录页 */
    size?: 'sm' | 'md'
  }>(),
  { subtitle: '', size: 'sm' },
)
</script>

<template>
  <span class="brand-mark" :class="`brand-mark--${size}`">
    <svg class="brand-icon" viewBox="0 0 48 48" aria-hidden="true">
      <!-- 贴纸底：白底 + 深色描边，保证在浅色导航和深色登录侧栏上都成立 -->
      <rect
        x="1.5"
        y="1.5"
        width="45"
        height="45"
        rx="12"
        fill="var(--logo-tile, #fff)"
        stroke="var(--logo-ink, #0f2a44)"
        stroke-width="2.4"
      />

      <!-- 叶子与果柄：保留你上传图里的蓝果 + 绿叶 + 棕柄识别点 -->
      <path
        d="M23.5 10.8C19.5 9.4 16.4 6.4 17.2 4.1c3.7-.1 6.5 2.6 7.3 6.5"
        fill="var(--logo-leaf, #84cc16)"
        stroke="var(--logo-ink, #0f2a44)"
        stroke-width="2"
        stroke-linejoin="round"
      />
      <path
        d="M25.5 10.2c2.6-2.9 6.2-4.1 8.1-2.4-.4 3.2-3.6 4.6-8.1 3.4"
        fill="var(--logo-leaf-2, #a3e635)"
        stroke="var(--logo-ink, #0f2a44)"
        stroke-width="2"
        stroke-linejoin="round"
      />
      <path
        d="M24.5 11.5c0-2.6 1-4.5 2.9-5.4"
        fill="none"
        stroke="var(--logo-stem, #8b5a2b)"
        stroke-width="3.4"
        stroke-linecap="round"
      />

      <!-- 果体：苹果形轮廓，保留原图的两个底瓣 -->
      <path
        d="M24 11.2c-8.2 0-13.4 5.8-13.4 13.3 0 8.3 4.4 14.3 9.1 14.3 2.3 0 3.3-1.7 4.3-2.9 1 1.2 2 2.9 4.3 2.9 4.7 0 9.1-6 9.1-14.3 0-7.5-5.2-13.3-13.4-13.3Z"
        fill="var(--logo-body, #3b82f6)"
        stroke="var(--logo-ink, #0f2a44)"
        stroke-width="2.6"
        stroke-linejoin="round"
      />
      <ellipse
        cx="17.5"
        cy="21.5"
        rx="3.2"
        ry="5.2"
        transform="rotate(-22 17.5 21.5)"
        fill="var(--logo-body-light, #93c5fd)"
        opacity="0.7"
      />

      <!-- 扳手：斜跨在果体前，橙色与蓝色形成互补，32px 下也能一眼认出 -->
      <path
        d="M33.8 14.2a5.8 5.8 0 0 0-7.6 7.2L15.1 32.5a2.9 2.9 0 0 0 4.1 4.1l11.1-11.1a5.8 5.8 0 0 0 7.2-7.6l-3.4 3.4-2.8-.7-.7-2.8 3.2-3.4Z"
        fill="var(--logo-tool, #f59e0b)"
        stroke="var(--logo-ink, #0f2a44)"
        stroke-width="2.2"
        stroke-linejoin="round"
      />

      <!-- 两只手抓住扳手手柄；没有加脸，避免 32px 下变成一团细节 -->
      <circle
        cx="18.8"
        cy="32.2"
        r="3.1"
        fill="var(--logo-body, #3b82f6)"
        stroke="var(--logo-ink, #0f2a44)"
        stroke-width="2"
      />
      <circle
        cx="22.8"
        cy="28.2"
        r="3.1"
        fill="var(--logo-body, #3b82f6)"
        stroke="var(--logo-ink, #0f2a44)"
        stroke-width="2"
      />
    </svg>
    <span class="brand-copy">
      <strong class="brand-name">后勤报修</strong>
      <small v-if="subtitle" class="brand-subtitle">{{ subtitle }}</small>
    </span>
  </span>
</template>

<style scoped>
.brand-mark {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  /* Logo 的取色独立于页面主题：换配色时改这一组即可，图标与文字不用重画 */
  --logo-tile: #fff;
  --logo-ink: #0f2a44;
  --logo-body: #3b82f6;
  --logo-body-light: #93c5fd;
  --logo-leaf: #84cc16;
  --logo-leaf-2: #a3e635;
  --logo-stem: #8b5a2b;
  --logo-tool: #f59e0b;
}
.brand-icon {
  width: 32px;
  height: 32px;
  flex: none;
}
.brand-copy {
  display: flex;
  align-items: baseline;
  gap: 6px;
  min-width: 0;
}
.brand-name {
  color: var(--logo-ink, #0f2a44);
  font-size: 17px;
  font-weight: 700;
  letter-spacing: 0.01em;
  white-space: nowrap;
}
.brand-subtitle {
  color: #64748b;
  font-size: 12px;
  font-weight: 500;
  white-space: nowrap;
}
.brand-mark--md .brand-icon {
  width: 40px;
  height: 40px;
}
.brand-mark--md .brand-name {
  font-size: 22px;
}
.brand-mark--md .brand-subtitle {
  font-size: 13px;
}
</style>
