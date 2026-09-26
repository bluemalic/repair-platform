import { createRouter, createWebHashHistory } from 'vue-router'
import { auth, getToken } from '@/store/auth'

/** 平台运营账号只有「租户管理」一个页面；它调租户接口一律 10003（ADR-012）。 */
const isPlatformUser = () => auth.user?.userType === 4

// history 模式需要 nginx try_files 配合（已配）；这里用 hash 模式，
// 好处是构建产物直接丢进任意静态服务器都能跑，演示时不挑环境。
const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/login', name: 'login', component: () => import('@/views/LoginView.vue') },
    {
      path: '/',
      component: () => import('@/views/LayoutView.vue'),
      // 首页按身份分：落到 /tickets 的平台账号只会看到一屏"无权限"（它进不了租户域）
      redirect: () => (isPlatformUser() ? '/platform/tenants' : '/tickets'),
      children: [
        { path: 'tickets', name: 'tickets', component: () => import('@/views/TicketListView.vue') },
        { path: 'dashboard', name: 'dashboard', component: () => import('@/views/DashboardView.vue') },
        { path: 'ai-query', name: 'aiQuery', component: () => import('@/views/AiQueryView.vue') },
        { path: 'notifications', name: 'notifications', component: () => import('@/views/NotificationsView.vue') },
        { path: 'workers', name: 'workers', component: () => import('@/views/WorkersView.vue') },
        { path: 'students', name: 'students', component: () => import('@/views/StudentsView.vue') },
        { path: 'repair-codes', name: 'repairCodes', component: () => import('@/views/RepairCodesView.vue') },
        { path: 'buildings', name: 'buildings', component: () => import('@/views/BuildingsView.vue') },
        { path: 'categories', name: 'categories', component: () => import('@/views/CategoriesView.vue') },
        // 平台运营端：与上面这些**不是同一个域**——这个页面只给平台运营账号（userType = 4），
        // 它调的是 /api/platform/**；平台账号调 /api/admin/** 一律 10003（ADR-012）
        {
          path: 'platform/tenants',
          name: 'platformTenants',
          component: () => import('@/views/PlatformTenantsView.vue'),
        },
      ],
    },
  ],
})

router.beforeEach((to) => {
  if (to.path !== '/login' && !getToken()) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  // 平台账号进租户域、租户账号进平台域，两者都没有意义：后端会回 10003，
  // 前端不如直接送回它该在的地方（尤其是刷新页面时）
  if (isPlatformUser() && !to.path.startsWith('/platform')) {
    return { path: '/platform/tenants' }
  }
  if (!isPlatformUser() && to.path.startsWith('/platform')) {
    return { path: '/tickets' }
  }
  return true
})

export default router
