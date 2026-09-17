import { createRouter, createWebHashHistory } from 'vue-router'
import { getToken } from '@/store/auth'

// history 模式需要 nginx try_files 配合（已配）；这里用 hash 模式，
// 好处是构建产物直接丢进任意静态服务器都能跑，演示时不挑环境。
const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/login', name: 'login', component: () => import('@/views/LoginView.vue') },
    {
      path: '/',
      component: () => import('@/views/LayoutView.vue'),
      redirect: '/tickets',
      children: [
        { path: 'tickets', name: 'tickets', component: () => import('@/views/TicketListView.vue') },
        { path: 'dashboard', name: 'dashboard', component: () => import('@/views/DashboardView.vue') },
        { path: 'workers', name: 'workers', component: () => import('@/views/WorkersView.vue') },
        { path: 'buildings', name: 'buildings', component: () => import('@/views/BuildingsView.vue') },
        { path: 'categories', name: 'categories', component: () => import('@/views/CategoriesView.vue') },
      ],
    },
  ],
})

router.beforeEach((to) => {
  if (to.path !== '/login' && !getToken()) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  return true
})

export default router
