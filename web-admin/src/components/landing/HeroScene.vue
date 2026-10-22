<script setup lang="ts">
/**
 * 落地页 Hero 的主视觉：一块"工单管理"界面示意 + 扫码报修浮层。
 *
 * <p>不做假数据大屏：这里只出现产品里真实存在的字段（楼栋 / 房间 / 类别 / 状态 / 紧急度），
 * 并且父级会标注"界面示意"。将来如果有真实截图，可以直接替换这个组件。
 */
</script>

<template>
  <div class="hero-scene" aria-hidden="true">
    <span class="scene-blob scene-blob--sky"></span>
    <span class="scene-blob scene-blob--orange"></span>

    <div class="console-card">
      <div class="console-bar">
        <span class="console-dots"><i></i><i></i><i></i></span>
        <span class="console-title">工单管理</span>
        <span class="console-filter">全部 · 本租户</span>
      </div>

      <div class="console-body">
        <div class="console-head">
          <span>位置 / 类别</span>
          <span>状态</span>
        </div>

        <div class="ticket-row">
          <div class="ticket-main">
            <strong>3 号楼 · 412</strong>
            <span>空调不制冷 · 紧急</span>
          </div>
          <span class="chip chip--amber">待派单</span>
        </div>

        <div class="ticket-row ticket-row--active">
          <div class="ticket-main">
            <strong>教学楼 B203</strong>
            <span>投影仪无信号 · 普通</span>
          </div>
          <span class="chip chip--green">处理中</span>
        </div>

        <div class="ticket-row">
          <div class="ticket-main">
            <strong>图书馆 1F 大厅</strong>
            <span>照明故障 · 普通</span>
          </div>
          <span class="chip chip--blue">待验收</span>
        </div>
      </div>
    </div>

    <div class="float-card float-card--qr">
      <svg viewBox="0 0 64 64" aria-hidden="true">
        <rect width="64" height="64" rx="10" fill="#0b1f33" />
        <g fill="#fff">
          <rect x="8" y="8" width="16" height="16" rx="3" />
          <rect x="40" y="8" width="16" height="16" rx="3" />
          <rect x="8" y="40" width="16" height="16" rx="3" />
          <rect x="30" y="30" width="8" height="8" rx="1" />
          <rect x="44" y="30" width="8" height="8" rx="1" />
          <rect x="30" y="44" width="8" height="8" rx="1" />
          <rect x="44" y="44" width="8" height="8" rx="1" />
        </g>
      </svg>
      <div>
        <strong>扫码报修</strong>
        <span>6 位码也能手输</span>
      </div>
    </div>

    <div class="float-card float-card--notice">
      <span class="notice-check">
        <svg viewBox="0 0 16 16" aria-hidden="true">
          <path d="M3.5 8.5l3 3 6-7" fill="none" stroke="#fff" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" />
        </svg>
      </span>
      <div>
        <strong>已接单</strong>
        <span>师傅正在赶来</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.hero-scene {
  position: relative;
  min-height: 360px;
  padding: 30px 22px;
  isolation: isolate;
}

.scene-blob {
  position: absolute;
  border-radius: 50%;
  filter: blur(2px);
  z-index: -1;
}
.scene-blob--sky {
  width: 280px;
  height: 280px;
  top: -20px;
  right: 10px;
  background: rgba(56, 189, 248, 0.18);
}
.scene-blob--orange {
  width: 190px;
  height: 190px;
  bottom: -30px;
  left: -10px;
  background: rgba(249, 115, 22, 0.16);
}

.console-card {
  position: relative;
  width: min(100%, 470px);
  margin: 0 auto;
  border: 1px solid #dce5ec;
  border-radius: 18px;
  background: #fff;
  box-shadow: 0 24px 60px rgba(11, 31, 51, 0.14);
  overflow: hidden;
}

.console-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 16px;
  border-bottom: 1px solid #e8eff5;
  background: #f7fafc;
}
.console-dots {
  display: inline-flex;
  gap: 5px;
}
.console-dots i {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #d7e1e9;
}
.console-dots i:first-child {
  background: #f97316;
}
.console-title {
  font-size: 12px;
  font-weight: 700;
  color: #64748b;
}
.console-filter {
  margin-left: auto;
  font-size: 10px;
  color: #64748b;
}

.console-body {
  padding: 14px 16px 16px;
}
.console-head {
  display: flex;
  justify-content: space-between;
  padding: 0 2px 8px;
  font-size: 10px;
  color: #64748b;
}
.ticket-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 11px 12px;
  border: 1px solid #edf2f6;
  border-radius: 12px;
  background: #fff;
}
.ticket-row + .ticket-row {
  margin-top: 8px;
}
.ticket-row--active {
  border-color: #a9d6f2;
  background: #f1f9ff;
}
.ticket-main {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
}
.ticket-main strong {
  font-size: 12px;
  color: #1b3145;
  white-space: nowrap;
}
.ticket-main span {
  font-size: 11px;
  color: #64748b;
}

.chip {
  flex: none;
  padding: 3px 9px;
  border-radius: 999px;
  font-size: 10px;
  font-weight: 600;
}
.chip--amber {
  color: #b45309;
  background: #fef3c7;
}
.chip--green {
  color: #15803d;
  background: #dcfce7;
}
.chip--blue {
  color: #1d4ed8;
  background: #dbeafe;
}

.float-card {
  position: absolute;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 11px 14px;
  border: 1px solid #dce5ec;
  border-radius: 14px;
  background: #fff;
  box-shadow: 0 14px 34px rgba(11, 31, 51, 0.13);
  animation: float 6s ease-in-out infinite;
}
.float-card strong {
  display: block;
  font-size: 12px;
  color: #0b1f33;
}
.float-card span {
  display: block;
  margin-top: 2px;
  font-size: 10px;
  color: #64748b;
}

.float-card--qr {
  left: -10px;
  bottom: -12px;
}
.float-card--qr svg {
  width: 38px;
  height: 38px;
  border-radius: 8px;
}

.float-card--notice {
  right: 0;
  top: 18px;
  animation-delay: -3s;
}
.notice-check {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border-radius: 50%;
  background: #16a34a;
  flex: none;
}
.notice-check svg {
  width: 16px;
  height: 16px;
}

@keyframes float {
  0%,
  100% {
    transform: translateY(0);
  }
  50% {
    transform: translateY(-7px);
  }
}

@media (max-width: 1100px) {
  .float-card--qr {
    left: -6px;
  }
  .float-card--notice {
    right: -6px;
  }
}

@media (max-width: 768px) {
  .hero-scene {
    min-height: 0;
    padding: 18px 0 8px;
  }
  .console-card {
    width: 100%;
  }
  .float-card {
    position: static;
    display: inline-flex;
    margin-top: 12px;
    animation: none;
  }
  .float-card--notice {
    margin-left: 10px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .float-card {
    animation: none;
  }
}
</style>
