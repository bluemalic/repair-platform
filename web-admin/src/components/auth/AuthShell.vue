<script setup lang="ts">
import BrandMark from '@/components/BrandMark.vue'
import BeianFooter from '@/components/BeianFooter.vue'

/**
 * 两个登录页共用的双栏外壳。
 *
 * <p>左边是品牌与产品说明，右边只放认证表单——登录页不承担营销职责，
 * 但也不该是一张孤零零的卡片（访客从落地页点进来时需要连续的视觉）。
 * 移动端隐藏左栏，只保留紧凑品牌头。
 */
defineProps<{
  heading: string
}>()
</script>

<template>
  <div class="auth-page">
    <aside class="auth-aside">
      <RouterLink to="/" class="auth-brand" aria-label="返回首页">
        <BrandMark subtitle="SaaS" size="md" />
      </RouterLink>

      <div class="auth-aside-copy">
        <p class="auth-eyebrow">高校后勤报修</p>
        <p class="auth-aside-title">让校园报修<br />少一点来回</p>
        <p class="auth-aside-lead">
          学生扫码报修，后勤按技能与负载派单，师傅扫码到场，验收后数据自动汇总。
        </p>
        <ul class="auth-points">
          <li>报修码定位房间，公共区域也能手选</li>
          <li>派单候选带评分与理由，不是凭印象</li>
          <li>进度、通知、统计在同一套系统里</li>
        </ul>
      </div>

      <svg class="auth-scene" viewBox="0 0 320 210" aria-hidden="true">
        <ellipse cx="160" cy="192" rx="124" ry="16" fill="rgba(255,255,255,.12)" />
        <rect
          x="66"
          y="54"
          width="118"
          height="132"
          rx="10"
          fill="rgba(255,255,255,.14)"
          stroke="rgba(255,255,255,.55)"
          stroke-width="2"
        />
        <path d="M54 58 125 20l71 38" fill="none" stroke="rgba(255,255,255,.75)" stroke-width="3" stroke-linecap="round" stroke-linejoin="round" />
        <g fill="rgba(255,255,255,.75)">
          <rect x="82" y="74" width="18" height="16" rx="3" />
          <rect x="112" y="74" width="18" height="16" rx="3" />
          <rect x="142" y="74" width="18" height="16" rx="3" />
          <rect x="82" y="104" width="18" height="16" rx="3" />
          <rect x="112" y="104" width="18" height="16" rx="3" />
          <rect x="142" y="104" width="18" height="16" rx="3" />
          <rect x="82" y="134" width="18" height="16" rx="3" />
          <rect x="142" y="134" width="18" height="16" rx="3" />
        </g>
        <rect x="110" y="146" width="30" height="40" rx="4" fill="rgba(255,255,255,.85)" />

        <rect x="204" y="86" width="76" height="94" rx="12" fill="#f1f9ff" />
        <rect x="216" y="99" width="52" height="52" rx="6" fill="#0b1f33" />
        <g fill="#fff">
          <rect x="222" y="105" width="10" height="10" rx="2" />
          <rect x="252" y="105" width="10" height="10" rx="2" />
          <rect x="222" y="135" width="10" height="10" rx="2" />
          <rect x="238" y="119" width="8" height="8" rx="1" />
          <rect x="250" y="133" width="8" height="8" rx="1" />
        </g>
        <text x="242" y="169" text-anchor="middle" font-size="11" fill="#5b7083">扫码报修</text>

        <circle cx="252" cy="62" r="22" fill="#f97316" />
        <path d="M243 62.5l6 6 12-13" fill="none" stroke="#fff" stroke-width="4" stroke-linecap="round" stroke-linejoin="round" />
      </svg>
    </aside>

    <main class="auth-main">
      <div class="auth-mobile-brand">
        <RouterLink to="/" aria-label="返回首页">
          <BrandMark subtitle="SaaS" />
        </RouterLink>
      </div>

      <section class="auth-panel">
        <header class="auth-panel-head">
          <h1>{{ heading }}</h1>
        </header>
        <slot />
      </section>

      <BeianFooter class="auth-beian" />
    </main>
  </div>
</template>

<style scoped>
.auth-page {
  box-sizing: border-box;
  display: grid;
  grid-template-columns: minmax(360px, 46%) minmax(0, 1fr);
  min-height: 100vh;
  overflow-x: hidden;
  background: #f5f8fa;
}

.auth-aside {
  box-sizing: border-box;
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 28px;
  padding: 40px clamp(32px, 5vw, 72px);
  overflow: hidden;
  color: #fff;
  background: #0f2a44;
}

.auth-brand {
  align-self: flex-start;
}
.auth-brand :deep(.brand-name),
.auth-brand :deep(.brand-subtitle) {
  color: #fff;
}
.auth-brand :deep(.brand-subtitle) {
  color: rgba(255, 255, 255, 0.7);
}

.auth-aside-copy {
  max-width: 440px;
}
.auth-eyebrow {
  margin: 0 0 12px;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.18em;
  color: rgba(255, 255, 255, 0.72);
}
.auth-aside-title {
  margin: 0;
  font-size: clamp(30px, 3.2vw, 44px);
  line-height: 1.22;
  letter-spacing: -0.01em;
  font-weight: 700;
}
.auth-aside-lead {
  margin: 18px 0 0;
  max-width: 390px;
  font-size: 15px;
  line-height: 1.8;
  color: rgba(255, 255, 255, 0.82);
}
.auth-points {
  margin: 22px 0 0;
  padding: 0;
  list-style: none;
  display: grid;
  gap: 10px;
  font-size: 14px;
  color: rgba(255, 255, 255, 0.92);
}
.auth-points li {
  position: relative;
  padding-left: 24px;
}
.auth-points li::before {
  content: '';
  position: absolute;
  top: 7px;
  left: 0;
  width: 12px;
  height: 7px;
  border-left: 2px solid #fbbf24;
  border-bottom: 2px solid #fbbf24;
  transform: rotate(-45deg);
}

.auth-scene {
  width: min(100%, 360px);
  margin-top: auto;
  align-self: center;
}

.auth-main {
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 24px;
  min-width: 0;
  padding: 48px 24px 20px;
}
.auth-mobile-brand {
  display: none;
  align-self: center;
}
.auth-panel {
  box-sizing: border-box;
  width: min(100%, 400px);
  max-width: 100%;
  min-width: 0;
  padding: 36px 34px;
  border: 1px solid #dce5ec;
  border-radius: 20px;
  background: #fff;
  box-shadow: 0 18px 50px rgba(11, 31, 51, 0.08);
}
.auth-panel-head {
  margin-bottom: 26px;
}
.auth-panel-head h1 {
  margin: 0;
  font-size: 24px;
  line-height: 1.3;
  color: #0b1f33;
}
.auth-beian {
  margin-top: auto;
}

/*
 * 桌面端把两栏锁定在视口高度：学校登录与平台登录的表单高度不同，
 * 如果让页面跟着内容长高，左栏的插画与分隔会随切换跳动。
 * 右栏单独滚动，左栏尺寸保持不变。
 */
@media (min-width: 901px) {
  .auth-page {
    height: 100vh;
    min-height: 0;
  }
  .auth-aside {
    height: 100vh;
  }
  .auth-main {
    height: 100vh;
    overflow-y: auto;
  }
  .auth-panel {
    min-height: 560px;
  }
}

@media (min-width: 901px) and (max-height: 760px) {
  .auth-scene {
    display: none;
  }
}

@media (min-width: 901px) and (max-height: 720px) {
  .auth-main {
    justify-content: flex-start;
  }
}

@media (max-width: 900px) {
  .auth-page {
    grid-template-columns: minmax(0, 1fr);
  }
  .auth-aside {
    display: none;
  }
  .auth-main {
    justify-content: flex-start;
    padding-top: 40px;
  }
  .auth-mobile-brand {
    display: block;
  }
  .auth-panel {
    margin-top: 20px;
  }
}

@media (max-width: 480px) {
  .auth-panel {
    padding: 28px 22px;
    border-radius: 16px;
  }
}
</style>
