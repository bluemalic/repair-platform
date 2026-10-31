import { createRouter, createWebHistory } from 'vue-router'
import { auth, getToken } from '@/store/auth'

/** 平台运营账号只有「租户管理」一个页面；它调租户接口一律 10003（ADR-012）。 */
const isPlatformUser = () => auth.user?.userType === 4

const consoleHome = () => (isPlatformUser() ? '/console/platform/tenants' : '/console/tickets')

/**
 * 公开页面：落地页与两个登录入口。其余路径都要登录。
 *
 * <p>落地页必须公开，否则访客先看到的是登录框——那正是改造前的问题。
 */
const PUBLIC_PATHS = new Set(['/', '/login', '/platform/login'])

/**
 * 兼容 history 模式上线前分享出去的 hash 链接（`/#/login`、`/#/tickets`）。
 * 必须在创建 router 之前改写：createWebHistory 只在初始化时读一次 location。
 * 只认 `#/` 开头，避免把落地页的锚点（`#scenes`）当成路由。
 */
const LEGACY_CONSOLE_PATHS = new Set([
  '/tickets',
  '/dashboard',
  '/ai-query',
  '/notifications',
  '/workers',
  '/students',
  '/repair-codes',
  '/buildings',
  '/categories',
  '/assets',
  '/schedule',
  '/knowledge',
  '/audit-logs',
  '/platform/tenants',
])

if (window.location.hash.startsWith('#/')) {
  const legacy = window.location.hash.slice(1)
  const [legacyPath, legacyQuery = ''] = legacy.split('?')
  // 旧平台入口是 `#/login?platform=1`：拆成独立登录页后直接送到新地址
  if (legacyPath === '/login' && new URLSearchParams(legacyQuery).get('platform') === '1') {
    window.history.replaceState(null, '', '/platform/login')
  } else if (LEGACY_CONSOLE_PATHS.has(legacyPath)) {
    // 旧后台页面整体下移到 /console 下；收藏链接里的查询参数（如 ?ticketId=）原样保留
    window.history.replaceState(
      null,
      '',
      `/console${legacyPath}${legacyQuery ? `?${legacyQuery}` : ''}`,
    )
  } else {
    window.history.replaceState(null, '', legacy + window.location.search)
  }
}

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      name: 'landing',
      component: () => import('@/views/LandingView.vue'),
      meta: { title: '高校后勤报修 SaaS 平台｜扫码报修 · 派单 · AI 问数' },
    },
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/LoginView.vue'),
      meta: { title: '管理端登录 · 后勤报修平台' },
    },
    {
      path: '/platform/login',
      name: 'platformLogin',
      component: () => import('@/views/PlatformLoginView.vue'),
      meta: { title: '平台运营登录 · 后勤报修平台' },
    },
    {
      path: '/console',
      component: () => import('@/views/LayoutView.vue'),
      // 首页按身份分：落到工单页的平台账号只会看到一屏"无权限"（它进不了租户域）
      redirect: consoleHome,
      children: [
        { path: 'tickets', name: 'tickets', component: () => import('@/views/TicketListView.vue'), meta: { title: '工单管理 · 后勤报修管理端' } },
        { path: 'dashboard', name: 'dashboard', component: () => import('@/views/DashboardView.vue'), meta: { title: '统计看板 · 后勤报修管理端' } },
        { path: 'ai-query', name: 'aiQuery', component: () => import('@/views/AiQueryView.vue'), meta: { title: 'AI 问数 · 后勤报修管理端' } },
        { path: 'notifications', name: 'notifications', component: () => import('@/views/NotificationsView.vue'), meta: { title: '通知 · 后勤报修管理端' } },
        { path: 'workers', name: 'workers', component: () => import('@/views/WorkersView.vue'), meta: { title: '维修工管理 · 后勤报修管理端' } },
        { path: 'students', name: 'students', component: () => import('@/views/StudentsView.vue'), meta: { title: '学生管理 · 后勤报修管理端' } },
        { path: 'repair-codes', name: 'repairCodes', component: () => import('@/views/RepairCodesView.vue'), meta: { title: '报修码管理 · 后勤报修管理端' } },
        { path: 'buildings', name: 'buildings', component: () => import('@/views/BuildingsView.vue'), meta: { title: '楼栋管理 · 后勤报修管理端' } },
        { path: 'categories', name: 'categories', component: () => import('@/views/CategoriesView.vue'), meta: { title: '类别管理 · 后勤报修管理端' } },
        { path: 'assets', name: 'assets', component: () => import('@/views/AssetsView.vue'), meta: { title: '设备台账 · 后勤报修管理端' } },
        { path: 'schedule', name: 'schedule', component: () => import('@/views/ScheduleView.vue'), meta: { title: '调度设置 · 后勤报修管理端' } },
        { path: 'knowledge', name: 'knowledge', component: () => import('@/views/KnowledgeView.vue'), meta: { title: '维修知识库 · 后勤报修管理端' } },
        // 操作日志放在基础数据一组里：它记的主要是基础数据与账号的改动（docs/01 §4.4）
        { path: 'audit-logs', name: 'auditLogs', component: () => import('@/views/AuditLogView.vue'), meta: { title: '操作日志 · 后勤报修管理端' } },
        // 平台运营端：与上面这些**不是同一个域**——这个页面只给平台运营账号（userType = 4），
        // 它调的是 /api/platform/**；平台账号调 /api/admin/** 一律 10003（ADR-012）
        {
          path: 'platform/tenants',
          name: 'platformTenants',
          component: () => import('@/views/PlatformTenantsView.vue'),
          meta: { title: '租户管理 · 平台运营' },
        },
      ],
    },
    // 未知路径回首页：history 模式下用户可能手改地址，给一个明确落点而不是空白
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})

router.beforeEach((to) => {
  /*
   * 统一认证回调落在站点根路径（`/?ssoCode=...`）。history 模式下 `router.push` 会连 search 一起
   * 换掉，所以这里**必须把 query 一起带到登录页**——只写 `{ path: '/login' }` 会让登录页读不到
   * 一次性码，现象是"认证回来了但一直停在登录页"。
   */
  if (to.path === '/' && (to.query.ssoCode || to.query.ssoError)) {
    return { path: '/login', query: to.query }
  }
  if (!PUBLIC_PATHS.has(to.path) && !getToken()) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  // 平台账号进租户域、租户账号进平台域，两者都没有意义：后端会回 10003，
  // 前端不如直接送回它该在的地方（尤其是刷新页面时）
  if (isPlatformUser() && to.path.startsWith('/console') && !to.path.startsWith('/console/platform')) {
    return '/console/platform/tenants'
  }
  if (!isPlatformUser() && to.path.startsWith('/console/platform')) {
    return '/console/tickets'
  }
  return true
})

router.afterEach((to) => {
  document.title = typeof to.meta.title === 'string' ? to.meta.title : '后勤报修平台'
})

export default router
