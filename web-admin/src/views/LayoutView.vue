<script setup lang="ts">
import { computed, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { auth, clearLogin } from '@/store/auth'
import { refreshUnread, unread } from '@/store/notification'

const route = useRoute()
const router = useRouter()
const activeMenu = computed(() => route.path)

/**
 * 未读数轮询（docs/03 §5.1 就是这么设计的：前端轮询 unread-count）。
 * 间隔取 60 秒：通知本身是"超时提醒/状态变更"，不需要秒级实时；太密只是白烧请求。
 * 组件卸载时清掉定时器——HMR 与来回切页面时不清会叠出一堆计时器。
 */
let timer: number | undefined

onMounted(() => {
  refreshUnread()
  timer = window.setInterval(refreshUnread, 60_000)
})

onUnmounted(() => {
  if (timer !== undefined) {
    window.clearInterval(timer)
  }
})

async function logout() {
  await ElMessageBox.confirm('确认退出登录？', '提示', { type: 'warning' })
  clearLogin()
  router.push('/login')
}
</script>

<template>
  <el-container class="layout">
    <el-aside width="200px" class="aside">
      <div class="brand">后勤报修 · 管理端</div>
      <el-menu :default-active="activeMenu" router>
        <el-menu-item index="/tickets">工单管理</el-menu-item>
        <el-menu-item index="/notifications">
          <el-badge :value="unread" :max="99" :hidden="unread === 0">通知</el-badge>
        </el-menu-item>
        <el-menu-item index="/dashboard">统计看板</el-menu-item>
        <el-sub-menu index="base">
          <template #title>基础数据</template>
          <!-- 菜单项随页面一起加：指向未注册路由的菜单点了会是一片空白 -->
          <el-menu-item index="/workers">维修工管理</el-menu-item>
          <el-menu-item index="/repair-codes">报修码管理</el-menu-item>
          <el-menu-item index="/buildings">楼栋管理</el-menu-item>
          <el-menu-item index="/categories">类别管理</el-menu-item>
        </el-sub-menu>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="header">
        <span>{{ auth.user?.realName }}（后勤管理）</span>
        <el-button link type="primary" @click="logout">退出登录</el-button>
      </el-header>
      <el-main>
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.layout {
  height: 100vh;
}
.aside {
  border-right: 1px solid var(--el-border-color-light);
}
.brand {
  height: 60px;
  line-height: 60px;
  text-align: center;
  font-weight: 600;
}
.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid var(--el-border-color-light);
}
</style>
