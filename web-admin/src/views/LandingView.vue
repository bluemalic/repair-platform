<script setup lang="ts">
import { computed, ref } from 'vue'
import { auth } from '@/store/auth'
import BrandMark from '@/components/BrandMark.vue'
import HeroScene from '@/components/landing/HeroScene.vue'
import BeianFooter from '@/components/BeianFooter.vue'

/**
 * 公开落地页。
 *
 * <p>只做产品介绍与入口引导，不调任何接口——未登录访客打开首页不应该产生业务请求。
 * 已登录用户仍可停留在这里（方便回看介绍），主按钮直接进后台。
 */
const isLoggedIn = computed(() => Boolean(auth.token))
const isPlatform = computed(() => auth.user?.userType === 4)

const consoleEntry = computed(() =>
  isPlatform.value ? '/console/platform/tenants' : '/console/tickets',
)
const primaryTarget = computed(() => (isLoggedIn.value ? consoleEntry.value : '/login'))
const primaryLabel = computed(() => {
  if (isPlatform.value) return '进入平台运营'
  return isLoggedIn.value ? '进入管理端' : '进入管理端演示'
})
/** 窄屏导航里放不下长按钮：只换文案，保留同一个跳转目标 */
const primaryLabelShort = computed(() => {
  if (isPlatform.value) return '平台运营'
  return isLoggedIn.value ? '进入后台' : '进入演示'
})

/** H5 与 web-admin 是两个独立工程：开发时各跑各的端口，线上由 nginx 挂在 /h5/。 */
const h5Url = import.meta.env.DEV ? 'http://localhost:5175/h5/' : '/h5/'
const repoUrl = 'https://github.com/bluemalic/repair-platform'

const mobileOpen = ref(false)
const navLinks = [
  { href: '#scenes', label: '适用场景' },
  { href: '#flow', label: '报修流程' },
  { href: '#roles', label: '角色能力' },
  { href: '#ai', label: 'AI 问数' },
  { href: '#faq', label: '常见问题' },
]

function closeMenu() {
  mobileOpen.value = false
}
</script>

<template>
  <div class="landing">
    <a class="skip-link" href="#main">跳到主要内容</a>

    <header class="landing-nav">
      <div class="container nav-inner">
        <RouterLink to="/" class="nav-brand" aria-label="后勤报修首页">
          <BrandMark subtitle="SaaS" />
        </RouterLink>

        <nav class="nav-links" aria-label="页面导航">
          <a v-for="link in navLinks" :key="link.href" :href="link.href">{{ link.label }}</a>
        </nav>

        <div class="nav-actions">
          <a class="nav-h5" :href="h5Url">学生 / 维修工</a>
          <RouterLink class="btn btn-primary btn-sm nav-cta" :to="primaryTarget">
            <span class="nav-cta-long">{{ primaryLabel }}</span>
            <span class="nav-cta-short">{{ primaryLabelShort }}</span>
          </RouterLink>
        </div>

        <button
          class="nav-toggle"
          type="button"
          aria-label="打开导航"
          :aria-expanded="mobileOpen"
          @click="mobileOpen = !mobileOpen"
        >
          <span></span><span></span><span></span>
        </button>
      </div>

      <div v-if="mobileOpen" class="mobile-panel">
        <a v-for="link in navLinks" :key="link.href" :href="link.href" @click="closeMenu">{{ link.label }}</a>
        <a :href="h5Url" @click="closeMenu">学生 / 维修工入口</a>
        <RouterLink :to="primaryTarget" @click="closeMenu">{{ primaryLabel }}</RouterLink>
      </div>
    </header>

    <main id="main">
      <!-- Hero -->
      <section class="hero">
        <div class="container hero-grid">
          <div class="hero-copy">
            <p class="eyebrow">高校后勤报修 SaaS</p>
            <h1>报修这件事，<br /><em>三个角色</em>都省心</h1>
            <p class="hero-lead">
              学生扫码报修，后勤按技能与负载派单，师傅扫码到场，验收后数据自动汇总。
              一条工单，从提交走到归档。
            </p>
            <div class="hero-actions">
              <RouterLink class="btn btn-primary" :to="primaryTarget">{{ primaryLabel }}</RouterLink>
              <a class="btn btn-ghost" :href="h5Url">学生 / 维修工入口</a>
            </div>
          </div>

          <div class="hero-visual">
            <HeroScene />
          </div>
        </div>
      </section>

      <section class="role-strip" aria-label="三端协作">
        <div class="container role-strip-inner">
          <span class="strip-label">一套系统，三端协作</span>
          <div class="role-pills">
            <span>学生端 · H5</span>
            <span>维修工端 · H5</span>
            <span>后勤管理端 · PC</span>
          </div>
        </div>
      </section>

      <!-- 适用场景 -->
      <section id="scenes" class="section">
        <div class="container">
          <div class="section-head">
            <p class="eyebrow">适用场景</p>
            <h2>校园里的每一处，都能被定位</h2>
            <p>
              一张报修码对应一个房间或公共区域。学生扫码就知道报哪里，后勤不用再问
              “是几号楼、哪个房间”。
            </p>
          </div>

          <div class="scenes-grid">
            <article class="scene-card">
              <span class="scene-index">01</span>
              <h3>学生公寓</h3>
              <p>空调、热水、门锁、下水道。扫码定位到房间，紧急情况可以标记紧急度。</p>
            </article>
            <article class="scene-card">
              <span class="scene-index">02</span>
              <h3>教学楼</h3>
              <p>投影、桌椅、照明。按教室位置派单，师傅不用先在教学楼里找一圈。</p>
            </article>
            <article class="scene-card">
              <span class="scene-index">03</span>
              <h3>实验楼</h3>
              <p>水电、通风、门禁。类别与紧急度先分好，派单时更容易匹配到合适的人。</p>
            </article>
            <article class="scene-card">
              <span class="scene-index">04</span>
              <h3>公共区域</h3>
              <p>走廊、大厅、卫生间。没有码可扫时，学生在报修页手动选择区域。</p>
            </article>
          </div>
        </div>
      </section>

      <!-- 对比 -->
      <section class="section section--soft">
        <div class="container">
          <div class="section-head">
            <p class="eyebrow">为什么需要它</p>
            <h2>以前的报修，卡在哪</h2>
            <p>不是多一个系统，而是把已经在发生的事，收进一条能追踪的流程。</p>
          </div>

          <div class="compare">
            <div class="compare-row">
              <div class="compare-before">
                <span class="compare-label">以前</span>
                电话、微信群、口头说一声，信息散在不同地方
              </div>
              <span class="compare-arrow" aria-hidden="true">→</span>
              <div class="compare-after">
                <span class="compare-label">现在</span>
                扫码或手输 6 位码，位置、类别、紧急度一次填清
              </div>
            </div>
            <div class="compare-row">
              <div class="compare-before">
                <span class="compare-label">以前</span>
                凭印象找人派单，跨楼栋还要先问一圈
              </div>
              <span class="compare-arrow" aria-hidden="true">→</span>
              <div class="compare-after">
                <span class="compare-label">现在</span>
                候选列表按技能、满意度、负载、距离排序，并给出理由
              </div>
            </div>
            <div class="compare-row">
              <div class="compare-before">
                <span class="compare-label">以前</span>
                月底翻记录、手工汇总，口径还常常对不上
              </div>
              <span class="compare-arrow" aria-hidden="true">→</span>
              <div class="compare-after">
                <span class="compare-label">现在</span>
                看板实时汇总，报表可导出，页面与导出复用同一批口径
              </div>
            </div>
          </div>
        </div>
      </section>

      <!-- 流程 -->
      <section id="flow" class="section">
        <div class="container">
          <div class="section-head">
            <p class="eyebrow">报修流程</p>
            <h2>一条工单的四步</h2>
            <p>每个节点都有明确的下一位处理人，超时会被提醒或升级。</p>
          </div>

          <div class="flow-grid">
            <article class="flow-step">
              <span class="flow-num">01</span>
              <span class="flow-role">学生</span>
              <h3>扫码报修</h3>
              <p>扫码或手输 6 位码定位位置，填现象、类别与紧急度，可附照片。</p>
            </article>
            <article class="flow-step">
              <span class="flow-num">02</span>
              <span class="flow-role">后勤</span>
              <h3>派单</h3>
              <p>看到候选师傅的评分与理由，选择派单、转派，或驳回补充信息。</p>
            </article>
            <article class="flow-step">
              <span class="flow-num">03</span>
              <span class="flow-role">维修工</span>
              <h3>接单上门</h3>
              <p>接单后扫码报到，处理完填写结果；需要多人时加协作者或拆单。</p>
            </article>
            <article class="flow-step">
              <span class="flow-num">04</span>
              <span class="flow-role">学生</span>
              <h3>验收归档</h3>
              <p>学生确认完成并评价；验收不通过会打回重做，全程留流转记录。</p>
            </article>
          </div>
        </div>
      </section>

      <!-- 角色能力 -->
      <section id="roles" class="section section--soft">
        <div class="container">
          <div class="section-head">
            <p class="eyebrow">角色能力</p>
            <h2>每个角色，看到的都是自己该看的</h2>
            <p>
              数据权限先按学校隔离，再按角色收口：学生只看自己的单，师傅看负责楼栋与派给自己的单。
            </p>
          </div>

          <div class="roles-grid">
            <article class="role-card">
              <header>
                <span class="role-badge role-badge--blue">学生</span>
                <h3>报修与跟踪</h3>
              </header>
              <ul>
                <li>扫码 / 手输 6 位码定位房间</li>
                <li>公共区域手动选择位置</li>
                <li>进度与站内通知</li>
                <li>验收评价、未派单前可撤销</li>
              </ul>
            </article>

            <article class="role-card">
              <header>
                <span class="role-badge role-badge--orange">维修工</span>
                <h3>接单与上门</h3>
              </header>
              <ul>
                <li>接单、驳回、完工</li>
                <li>扫码报到，记录到场时间</li>
                <li>协同处理、拆单</li>
                <li>维修知识库排查建议</li>
              </ul>
            </article>

            <article class="role-card">
              <header>
                <span class="role-badge role-badge--ink">后勤</span>
                <h3>调度与治理</h3>
              </header>
              <ul>
                <li>派单候选评分、转派</li>
                <li>超时提醒与升级</li>
                <li>统计看板、Excel 导出</li>
                <li>账号、楼栋、类别、报修码维护</li>
              </ul>
            </article>
          </div>
        </div>
      </section>

      <!-- AI -->
      <section id="ai" class="section">
        <div class="container ai-grid">
          <div class="ai-copy">
            <p class="eyebrow">AI 数据助手</p>
            <h2>不会写 SQL，也能问数据</h2>
            <p class="section-lead">
              用自然语言问业务库，系统生成只读查询，给出表格、图和一句结论。
              查询范围仍然受学校与角色限制，不是“什么都能看”。
            </p>
            <ul class="ai-points">
              <li>只读数据库账号 + SQL 白名单</li>
              <li>查询自动带上租户条件</li>
              <li>超时中断、超量截断</li>
              <li>结论明确标注由 AI 生成</li>
            </ul>
          </div>

          <div class="ai-demo">
            <div class="ai-question">上个月各楼栋的报修量？</div>
            <div class="ai-answer">
              <div class="ai-answer-head">
                <span class="ai-dot"></span>
                AI 查询结果
                <span class="ai-tag">AI 生成</span>
              </div>
              <div class="ai-bars" aria-hidden="true">
                <div class="ai-bar"><span style="height: 74%"></span><small>3 号楼</small></div>
                <div class="ai-bar"><span style="height: 52%"></span><small>教学楼</small></div>
                <div class="ai-bar"><span style="height: 36%"></span><small>图书馆</small></div>
                <div class="ai-bar"><span style="height: 24%"></span><small>实验楼</small></div>
              </div>
              <p class="ai-conclusion">3 号楼报修量最高，建议优先关注空调与热水类工单。</p>
              <p class="ai-note">示意图 · AI 生成内容，仅供参考</p>
            </div>
          </div>
        </div>
      </section>

      <!-- FAQ -->
      <section id="faq" class="section section--soft">
        <div class="container faq-wrap">
          <div class="section-head">
            <p class="eyebrow">常见问题</p>
            <h2>你可能还想知道</h2>
          </div>

          <div class="faq-list">
            <details>
              <summary>这是某所学校的官方系统吗？</summary>
              <p>
                不是。这是一个个人技术演示项目，不是任何学校的官方系统；演示数据每日重置。
              </p>
            </details>
            <details>
              <summary>怎么体验？</summary>
              <p>
                管理端从「进入管理端演示」登录，演示账号见登录页提示；学生 / 维修工端从
                「学生 / 维修工入口」进入 H5。
              </p>
            </details>
            <details>
              <summary>支持多所学校吗？</summary>
              <p>
                支持。平台运营可以开通 / 停用学校，每所学校的数据独立存放；平台运营域与租户域
                互不越界，平台账号看不到任何学校的业务数据。
              </p>
            </details>
            <details>
              <summary>AI 会不会看到别的学校的数据？</summary>
              <p>
                不会。生成的查询会经过只读账号、SQL 白名单、强制租户条件与超时 / 截断四层闸门；
                越权查询会被拦下，而不是执行后再过滤。
              </p>
            </details>
            <details>
              <summary>手机上能用吗？</summary>
              <p>
                可以，学生 / 维修工端是 H5。扫码取摄像头需要 HTTPS 或 localhost；
                环境不支持、摄像头被拒或贴纸磨损时，可以手输 6 位报修码。
              </p>
            </details>
            <details>
              <summary>用了什么技术？</summary>
              <p>
                Spring Boot 3 + MySQL 8 + Redis 7 + MinIO，前端 Vue 3 + Element Plus，
                移动端 uni-app（当前编译 H5）。源码与设计文档在
                <a :href="repoUrl" target="_blank" rel="noopener">GitHub 仓库</a>。
              </p>
            </details>
          </div>
        </div>
      </section>

      <!-- 结尾 CTA -->
      <section class="closing-action">
        <div class="container closing-inner">
          <p>想先看看它怎么运转？</p>
          <a class="closing-link" href="#flow">看看工单流程 <span aria-hidden="true">→</span></a>
        </div>
      </section>
    </main>

    <footer class="landing-footer">
      <div class="container footer-grid">
        <div class="footer-brand">
          <BrandMark subtitle="SaaS" size="md" />
          <p>高校后勤报修 SaaS：扫码报修、派单、上门、验收与数据汇总。</p>
        </div>

        <div class="footer-col">
          <h3>产品</h3>
          <a href="#scenes">适用场景</a>
          <a href="#flow">报修流程</a>
          <a href="#roles">角色能力</a>
          <a href="#ai">AI 问数</a>
          <a href="#faq">常见问题</a>
        </div>

        <div class="footer-col">
          <h3>体验</h3>
          <RouterLink to="/login">管理端登录</RouterLink>
          <a :href="h5Url">学生 / 维修工入口</a>
          <RouterLink to="/platform/login">平台运营登录</RouterLink>
          <a :href="repoUrl" target="_blank" rel="noopener">源码与文档</a>
        </div>
      </div>

      <div class="container footer-bottom">
        <BeianFooter :show-disclaimer="false" />
        <a href="mailto:panpanhuipang@outlook.com">邮箱：panpanhuipang@outlook.com</a>
        <a :href="repoUrl" target="_blank" rel="noopener">GitHub</a>
      </div>
    </footer>
  </div>
</template>

<style scoped>
.landing {
  --primary: #0f2a44;
  --primary-strong: #081827;
  --link: #1d4ed8;
  --sky: #38bdf8;
  --accent: #f97316;
  --accent-strong: #ea580c;
  --accent-soft: #fff1e6;
  --bg: #f5f8fa;
  --surface-alt: #eef6fb;
  --ink: #0b1f33;
  --body: #5b7083;
  --muted: #64748b;
  --line: #dce5ec;
  --card: #ffffff;
  --radius: 18px;

  min-height: 100vh;
  background: var(--bg);
  color: var(--body);
  font-family: 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', 'Noto Sans SC', system-ui,
    -apple-system, sans-serif;
  font-size: 16px;
  line-height: 1.75;
}

.container {
  width: min(100% - 48px, 1160px);
  margin-inline: auto;
}

.skip-link {
  position: absolute;
  top: -60px;
  left: 16px;
  z-index: 100;
  padding: 10px 16px;
  border-radius: 8px;
  background: var(--primary);
  color: #fff;
  text-decoration: none;
}
.skip-link:focus {
  top: 12px;
}

/* ---------- 导航 ---------- */
.landing-nav {
  position: sticky;
  top: 0;
  z-index: 50;
  border-bottom: 1px solid rgba(220, 229, 236, 0.8);
  background: rgba(245, 248, 250, 0.9);
  backdrop-filter: blur(14px);
}
.nav-inner {
  display: flex;
  align-items: center;
  gap: 28px;
  height: 68px;
}
.nav-brand {
  flex: none;
  text-decoration: none;
}
.nav-links {
  display: flex;
  align-items: center;
  gap: 24px;
  margin-left: auto;
}
.nav-links a {
  color: var(--body);
  font-size: 14px;
  text-decoration: none;
  white-space: nowrap;
}
.nav-links a:hover {
  color: var(--link);
}
.nav-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}
.nav-h5 {
  flex: none;
  color: var(--body);
  font-size: 13px;
  text-decoration: none;
  white-space: nowrap;
}
.nav-h5:hover {
  color: var(--link);
}
.nav-toggle {
  display: none;
  flex-direction: column;
  gap: 4px;
  margin-left: auto;
  padding: 8px;
  border: 0;
  background: transparent;
  cursor: pointer;
}
.nav-toggle span {
  display: block;
  width: 20px;
  height: 2px;
  border-radius: 2px;
  background: var(--ink);
}
.mobile-panel {
  display: none;
}

/* ---------- 按钮 ---------- */
.btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  height: 46px;
  padding: 0 24px;
  border: 1px solid transparent;
  border-radius: 999px;
  font-size: 14px;
  font-weight: 600;
  text-decoration: none;
  white-space: nowrap;
  transition: transform 0.2s ease, background 0.2s ease, border-color 0.2s ease, color 0.2s ease;
}
.btn-sm {
  height: 38px;
  padding: 0 18px;
  font-size: 13px;
}
.btn-primary {
  color: #fff;
  background: var(--primary);
  box-shadow: 0 10px 24px rgba(15, 42, 68, 0.22);
}
.btn-primary:hover {
  background: var(--primary-strong);
  transform: translateY(-1px);
}
.btn-ghost {
  color: var(--ink);
  border-color: var(--line);
  background: #fff;
}
.btn-ghost:hover {
  color: var(--link);
  border-color: #9cc9e8;
}
.nav-cta-short {
  display: none;
}

/* ---------- 通用区块 ---------- */
.section {
  padding: 88px 0;
  scroll-margin-top: 82px;
}
.section--soft {
  background: var(--surface-alt);
}
.eyebrow {
  margin: 0 0 14px;
  color: var(--link);
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.14em;
}
.section-head {
  max-width: 760px;
  margin-bottom: 42px;
}
.section-head h2,
.ai-copy h2 {
  margin: 0;
  color: var(--ink);
  font-size: clamp(27px, 3.2vw, 38px);
  line-height: 1.28;
  letter-spacing: -0.01em;
}
.section-head p:not(.eyebrow),
.section-lead {
  margin: 14px 0 0;
  color: var(--body);
  font-size: 15px;
  line-height: 1.8;
}

/* ---------- Hero ---------- */
.hero {
  padding: 64px 0 44px;
  background:
    radial-gradient(circle at 78% 18%, rgba(56, 189, 248, 0.16), transparent 32%),
    radial-gradient(circle at 12% 82%, rgba(249, 115, 22, 0.09), transparent 30%),
    var(--bg);
}
.hero-grid {
  display: grid;
  grid-template-columns: 1.04fr 0.96fr;
  align-items: center;
  gap: 48px;
}
.hero h1 {
  margin: 0;
  color: var(--ink);
  font-size: clamp(36px, 4.8vw, 56px);
  line-height: 1.16;
  letter-spacing: -0.02em;
}
.hero h1 em {
  position: relative;
  font-style: normal;
  color: var(--link);
  white-space: nowrap;
  background: linear-gradient(transparent 70%, rgba(56, 189, 248, 0.3) 70%);
}
.hero-lead {
  max-width: 560px;
  margin: 22px 0 0;
  font-size: 17px;
  line-height: 1.8;
  color: var(--body);
}
.hero-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-top: 30px;
}
.hero-visual {
  position: relative;
}

/* ---------- 三端条 ---------- */
.role-strip {
  border-block: 1px solid var(--line);
  background: #fff;
}
.role-strip-inner {
  display: flex;
  align-items: center;
  gap: 20px;
  min-height: 74px;
  padding-block: 14px;
}
.strip-label {
  flex: none;
  color: var(--ink);
  font-size: 13px;
  font-weight: 700;
}
.role-pills {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.role-pills span {
  padding: 5px 12px;
  border: 1px solid var(--line);
  border-radius: 999px;
  color: var(--body);
  font-size: 12px;
  background: #f7fafc;
}

/* ---------- 场景 ---------- */
.scenes-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
}
.scene-card {
  padding: 24px 22px;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--card);
  transition: transform 0.2s ease, box-shadow 0.2s ease, border-color 0.2s ease;
}
.scene-card:hover {
  transform: translateY(-3px);
  border-color: #a9d6f2;
  box-shadow: 0 16px 36px rgba(11, 31, 51, 0.08);
}
.scene-index {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  border-radius: 10px;
  color: var(--primary);
  background: var(--surface-alt);
  font-size: 12px;
  font-weight: 700;
}
.scene-card h3 {
  margin: 16px 0 8px;
  color: var(--ink);
  font-size: 17px;
}
.scene-card p {
  margin: 0;
  font-size: 13px;
  line-height: 1.8;
  color: var(--body);
}

/* ---------- 对比 ---------- */
.compare {
  display: grid;
  gap: 12px;
}
.compare-row {
  display: grid;
  grid-template-columns: 1fr auto 1fr;
  align-items: stretch;
  gap: 14px;
}
.compare-before,
.compare-after {
  position: relative;
  padding: 20px 22px;
  border-radius: 14px;
  font-size: 14px;
  line-height: 1.7;
}
.compare-before {
  border: 1px dashed #d5e0e8;
  color: #5b7083;
  background: rgba(255, 255, 255, 0.55);
}
.compare-after {
  border: 1px solid #a9d6f2;
  color: #10324a;
  background: #f1f9ff;
}
.compare-label {
  display: block;
  margin-bottom: 4px;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.08em;
}
.compare-before .compare-label {
  color: #64748b;
}
.compare-after .compare-label {
  color: var(--link);
}
.compare-arrow {
  align-self: center;
  color: var(--accent);
  font-size: 18px;
  font-weight: 700;
}

/* ---------- 流程 ---------- */
.flow-grid {
  position: relative;
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
}
.flow-grid::before {
  content: '';
  position: absolute;
  top: 34px;
  right: 8%;
  left: 8%;
  height: 2px;
  background: linear-gradient(90deg, #cfe7f5, #f8c98a, #cfe7f5);
}
.flow-step {
  position: relative;
  padding: 0 10px;
}
.flow-num {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  border: 4px solid var(--bg);
  border-radius: 50%;
  color: #fff;
  background: var(--primary);
  font-size: 13px;
  font-weight: 700;
  box-shadow: 0 0 0 1px #cfe7f5;
}
.flow-role {
  display: inline-block;
  margin: 14px 0 0;
  padding: 2px 9px;
  border-radius: 999px;
  color: #a34b0a;
  background: var(--accent-soft);
  font-size: 11px;
  font-weight: 600;
}
.flow-step h3 {
  margin: 8px 0 6px;
  color: var(--ink);
  font-size: 17px;
}
.flow-step p {
  margin: 0;
  font-size: 13px;
  line-height: 1.8;
  color: var(--body);
}

/* ---------- 角色 ---------- */
.roles-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 18px;
}
.role-card {
  padding: 26px 24px;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: #fff;
}
.role-card header {
  margin-bottom: 16px;
}
.role-badge {
  display: inline-block;
  padding: 3px 10px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 700;
}
.role-badge--blue {
  color: #1d4ed8;
  background: #dbeafe;
}
.role-badge--orange {
  color: #b45309;
  background: #fef3c7;
}
.role-badge--ink {
  color: #fff;
  background: var(--ink);
}
.role-card h3 {
  margin: 10px 0 0;
  color: var(--ink);
  font-size: 19px;
}
.role-card ul {
  display: grid;
  gap: 9px;
  margin: 0;
  padding: 0;
  list-style: none;
}
.role-card li {
  position: relative;
  padding-left: 22px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--body);
}
.role-card li::before {
  content: '';
  position: absolute;
  top: 7px;
  left: 0;
  width: 11px;
  height: 6px;
  border-left: 2px solid var(--primary);
  border-bottom: 2px solid var(--primary);
  transform: rotate(-45deg);
}

/* ---------- AI ---------- */
.ai-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  align-items: center;
  gap: 56px;
}
.ai-points {
  display: grid;
  gap: 8px;
  margin: 22px 0 0;
  padding: 0;
  list-style: none;
}
.ai-points li {
  position: relative;
  padding-left: 22px;
  font-size: 14px;
  color: var(--body);
}
.ai-points li::before {
  content: '';
  position: absolute;
  top: 8px;
  left: 0;
  width: 10px;
  height: 6px;
  border-left: 2px solid var(--accent);
  border-bottom: 2px solid var(--accent);
  transform: rotate(-45deg);
}

.ai-demo {
  padding: 26px;
  border: 1px solid var(--line);
  border-radius: 24px;
  background:
    radial-gradient(circle at 90% 0%, rgba(56, 189, 248, 0.16), transparent 40%),
    #fff;
  box-shadow: 0 22px 54px rgba(11, 31, 51, 0.09);
}
.ai-question {
  width: fit-content;
  max-width: 100%;
  margin-left: auto;
  padding: 10px 16px;
  border-radius: 14px 14px 4px 14px;
  color: #fff;
  background: var(--primary);
  font-size: 13px;
}
.ai-answer {
  margin-top: 16px;
  padding: 18px;
  border: 1px solid #e2ecf3;
  border-radius: 16px;
  background: #f7fafc;
}
.ai-answer-head {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--ink);
  font-size: 12px;
  font-weight: 700;
}
.ai-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--sky);
}
.ai-tag {
  margin-left: auto;
  padding: 2px 8px;
  border-radius: 999px;
  color: #a34b0a;
  background: var(--accent-soft);
  font-size: 10px;
  font-weight: 600;
}
.ai-bars {
  display: flex;
  align-items: flex-end;
  gap: 14px;
  height: 150px;
  margin: 18px 0 10px;
  padding: 0 6px;
  border-bottom: 1px solid #dce5ec;
}
.ai-bar {
  display: flex;
  flex: 1;
  flex-direction: column;
  justify-content: flex-end;
  align-items: center;
  height: 100%;
  gap: 6px;
}
.ai-bar span {
  display: block;
  width: 100%;
  max-width: 38px;
  border-radius: 8px 8px 2px 2px;
  background: linear-gradient(180deg, #7dd3fc, #0ea5e9);
}
.ai-bar:nth-child(2) span {
  background: linear-gradient(180deg, #93c5fd, #2563eb);
}
.ai-bar:nth-child(3) span {
  background: linear-gradient(180deg, #fdba74, #f97316);
}
.ai-bar:nth-child(4) span {
  background: linear-gradient(180deg, #e5e7eb, #9ca3af);
}
.ai-bar small {
  color: var(--muted);
  font-size: 10px;
  white-space: nowrap;
}
.ai-conclusion {
  margin: 8px 0 0;
  color: #10324a;
  font-size: 12px;
  line-height: 1.7;
}
.ai-note {
  margin: 8px 0 0;
  color: #64748b;
  font-size: 10px;
}

/* ---------- FAQ ---------- */
.faq-wrap {
  max-width: 880px;
}
.faq-list {
  border-top: 1px solid var(--line);
}
.faq-list details {
  border-bottom: 1px solid var(--line);
}
.faq-list summary {
  position: relative;
  padding: 20px 42px 20px 0;
  color: var(--ink);
  font-size: 15px;
  font-weight: 600;
  cursor: pointer;
  list-style: none;
}
.faq-list summary::-webkit-details-marker {
  display: none;
}
.faq-list summary::after {
  content: '+';
  position: absolute;
  top: 18px;
  right: 6px;
  color: var(--link);
  font-size: 20px;
  font-weight: 400;
  line-height: 1;
  transition: transform 0.2s ease;
}
.faq-list details[open] summary::after {
  transform: rotate(45deg);
}
.faq-list details p {
  margin: -4px 0 20px;
  padding-right: 42px;
  color: var(--body);
  font-size: 14px;
  line-height: 1.8;
}
.faq-list a {
  color: var(--link);
}

/* ---------- 结尾次级动作 ---------- */
.closing-action {
  padding: 0 0 56px;
}
.closing-inner {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
}
.closing-inner p {
  margin: 0;
  color: var(--muted);
  font-size: 14px;
}
.closing-link {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--link);
  font-size: 14px;
  font-weight: 600;
  text-decoration: none;
}
.closing-link span {
  display: inline-block;
  transition: transform 0.2s ease;
}
.closing-link:hover {
  text-decoration: underline;
}
.closing-link:hover span {
  transform: translateX(3px);
}

/* ---------- 页脚 ---------- */
.landing-footer {
  padding: 56px 0 26px;
  border-top: 1px solid var(--line);
  background: #f1f6fa;
}
.footer-grid {
  display: grid;
  grid-template-columns: 1.6fr 1fr 1fr;
  gap: 32px;
}
.footer-brand p {
  max-width: 320px;
  margin: 14px 0 0;
  color: var(--body);
  font-size: 13px;
  line-height: 1.8;
}
.footer-col {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.footer-col h3 {
  margin: 4px 0 4px;
  color: var(--ink);
  font-size: 13px;
}
.footer-col a,
.footer-col span {
  color: var(--body);
  font-size: 12px;
  line-height: 1.7;
  text-decoration: none;
}
.footer-col a:hover {
  color: var(--link);
  text-decoration: underline;
}
.footer-bottom {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 40px;
  padding-top: 20px;
  border-top: 1px solid var(--line);
}
.footer-bottom > a {
  color: var(--body);
  font-size: 12px;
  text-decoration: none;
}
.footer-bottom > a:hover {
  color: var(--link);
}

/* ---------- 响应式 ---------- */
@media (max-width: 1200px) {
  .nav-h5 {
    display: none;
  }
}

@media (max-width: 860px) {
  .nav-inner {
    gap: 12px;
  }
  .nav-cta-long {
    display: none;
  }
  .nav-cta-short {
    display: inline;
  }
  .nav-cta {
    padding: 0 16px;
  }
  .nav-links {
    display: none;
  }
  .nav-toggle {
    display: flex;
    margin-left: 0;
  }
  .nav-actions {
    margin-left: auto;
    gap: 10px;
  }
  .mobile-panel {
    display: grid;
    gap: 2px;
    padding: 10px 24px 18px;
    border-top: 1px solid var(--line);
    background: #f7fafc;
  }
  .mobile-panel a {
    padding: 10px 0;
    color: var(--body);
    font-size: 14px;
    text-decoration: none;
    border-bottom: 1px solid #e8eff5;
  }
  .mobile-panel a:last-child {
    color: var(--link);
    font-weight: 600;
    border-bottom: 0;
  }
}

@media (max-width: 1024px) {
  .hero-grid {
    grid-template-columns: 1fr;
    gap: 32px;
  }
  .hero-visual {
    max-width: 560px;
    width: 100%;
    margin-inline: auto;
  }
  .scenes-grid {
    grid-template-columns: repeat(2, 1fr);
  }
  .roles-grid {
    grid-template-columns: 1fr;
  }
  .ai-grid {
    grid-template-columns: 1fr;
    gap: 32px;
  }
  .flow-grid {
    grid-template-columns: repeat(2, 1fr);
    row-gap: 30px;
  }
  .flow-grid::before {
    display: none;
  }
  .footer-grid {
    grid-template-columns: 1.4fr 1fr 1fr;
  }
}

@media (max-width: 768px) {
  .container {
    width: min(100% - 36px, 1160px);
  }
  .nav-h5 {
    display: none;
  }
  .section {
    padding: 58px 0;
  }
  .hero {
    padding: 42px 0 30px;
  }
  .hero h1 {
    font-size: clamp(32px, 9vw, 44px);
  }
  .hero-lead {
    font-size: 15px;
  }
  .role-strip-inner {
    flex-wrap: wrap;
    gap: 8px 16px;
  }
  .compare-row {
    grid-template-columns: 1fr;
    gap: 8px;
  }
  .compare-arrow {
    justify-self: center;
    transform: rotate(90deg);
  }
  .flow-grid {
    grid-template-columns: 1fr;
  }
  .footer-grid {
    grid-template-columns: 1fr 1fr;
  }
  .footer-brand {
    grid-column: 1 / -1;
  }
  .closing-action {
    padding-bottom: 40px;
  }
}

@media (max-width: 480px) {
  .scenes-grid {
    grid-template-columns: 1fr;
  }
  .hero-actions .btn {
    width: 100%;
  }
  .closing-inner {
    flex-direction: column;
    gap: 4px;
  }
  .footer-grid {
    grid-template-columns: 1fr;
  }
  .ai-demo {
    padding: 18px;
  }
  .ai-bars {
    height: 120px;
    gap: 8px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .btn,
  .scene-card,
  .faq-list summary::after {
    transition: none;
  }
}
</style>
