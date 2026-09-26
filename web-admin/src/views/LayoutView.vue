<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { changePassword } from '@/api/auth'
import { auth, clearLogin } from '@/store/auth'
import { refreshUnread, unread } from '@/store/notification'

const route = useRoute()
const router = useRouter()
const activeMenu = computed(() => route.path)

/** 平台运营账号：菜单只有一项，且没有站内通知（它不属于任何租户）。 */
const isPlatform = computed(() => auth.user?.userType === 4)
const roleLabel = computed(() => (isPlatform.value ? '平台运营' : '后勤管理'))

/**
 * 还在用初始口令。这个标记必须在前端也认，否则用户看到的是"登录成功了，但每个页面都报无权限"，
 * 却不知道要去哪改——**服务端才是真正的拦截**（未改密时只放行 `/api/auth/**`），
 * 这里做的是"别让他看到一屏 10003"。
 */
const mustChangePassword = computed(() => auth.user?.mustChangePassword === true)

/**
 * 未读数轮询（docs/03 §5.1 就是这么设计的：前端轮询 unread-count）。
 * 间隔取 60 秒：通知本身是"超时提醒/状态变更"，不需要秒级实时；太密只是白烧请求。
 * 组件卸载时清掉定时器——HMR 与来回切页面时不清会叠出一堆计时器。
 *
 * 平台运营不轮询：它的通知查询会带上 `receiver_id = 0`，永远是 0，白烧请求。
 */
let timer: number | undefined

onMounted(() => {
  if (mustChangePassword.value) {
    // 首登强制改密：直接把框弹出来
    openPasswordDialog()
  }
  if (isPlatform.value) {
    return
  }
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
  goLogin()
}

/**
 * 清登录态并回登录页。**平台运营账号要带着登录模式回去**（`?platform=1`）——
 * 否则登录页退回学校模式（学校编码默认 `gdou`），而那个租户下没有 `platform` 这个账号，
 * 现象是"新旧口令都不对"，很难从现象倒推回来。踩过一次。
 *
 * 注意顺序：先读 `isPlatform`（它读的是 `auth.user`）再 `clearLogin()`。
 */
function goLogin() {
  const query = isPlatform.value ? { platform: '1' } : {}
  clearLogin()
  router.push({ path: '/login', query })
}

// ---------- 修改密码 ----------

const pwdVisible = ref(false)
const pwdFormRef = ref<FormInstance>()
const pwdForm = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })

const pwdRules: FormRules = {
  oldPassword: [{ required: true, message: '请输入当前密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 8, max: 32, message: '新密码长度需为 8-32 位', trigger: 'blur' },
  ],
  confirmPassword: [
    { required: true, message: '请再输入一次新密码', trigger: 'blur' },
    {
      // 两次输入一致是前端防手滑，后端不校验（它只收一个值）——这一条必须写在前端
      validator: (_rule, value: string, callback) =>
        value === pwdForm.newPassword ? callback() : callback(new Error('两次输入的新密码不一致')),
      trigger: 'blur',
    },
  ],
}

function openPasswordDialog() {
  pwdForm.oldPassword = ''
  pwdForm.newPassword = ''
  pwdForm.confirmPassword = ''
  pwdVisible.value = true
}

async function submitPassword() {
  if (!(await pwdFormRef.value?.validate().catch(() => false))) {
    return
  }
  await changePassword(pwdForm.oldPassword, pwdForm.newPassword)
  pwdVisible.value = false
  // 改密后所有会话都被服务端注销了（含当前这次），所以必须重新登录。
  // 这不是"顺手退出"，是设计：改密的动机之一就是怀疑账号被别人用着。
  ElMessage.success('密码已修改，请用新密码重新登录')
  goLogin()
}
</script>

<template>
  <el-container class="layout">
    <el-aside width="200px" class="aside">
      <div class="brand">{{ isPlatform ? '后勤报修 · 平台运营' : '后勤报修 · 管理端' }}</div>
      <!-- 待改密时藏起菜单：服务端此时只放行 /api/auth/**，点任何一项都只会拿到一屏 10003 -->
      <el-menu v-if="!mustChangePassword" :default-active="activeMenu" router>
        <template v-if="isPlatform">
          <el-menu-item index="/platform/tenants">租户管理</el-menu-item>
        </template>
        <template v-else>
          <el-menu-item index="/tickets">工单管理</el-menu-item>
          <el-menu-item index="/notifications">
            <el-badge :value="unread" :max="99" :hidden="unread === 0">通知</el-badge>
          </el-menu-item>
          <el-menu-item index="/dashboard">统计看板</el-menu-item>
          <el-menu-item index="/ai-query">AI 问数</el-menu-item>
          <el-sub-menu index="base">
            <template #title>基础数据</template>
            <!-- 菜单项随页面一起加：指向未注册路由的菜单点了会是一片空白 -->
            <el-menu-item index="/workers">维修工管理</el-menu-item>
            <el-menu-item index="/students">学生管理</el-menu-item>
            <el-menu-item index="/repair-codes">报修码管理</el-menu-item>
            <el-menu-item index="/buildings">楼栋管理</el-menu-item>
            <el-menu-item index="/categories">类别管理</el-menu-item>
          </el-sub-menu>
        </template>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="header">
        <span>{{ auth.user?.realName }}（{{ roleLabel }}）</span>
        <span class="header-actions">
          <el-button link type="primary" @click="openPasswordDialog">修改密码</el-button>
          <el-button link type="primary" @click="logout">退出登录</el-button>
        </span>
      </el-header>
      <el-main>
        <router-view />
      </el-main>
    </el-container>

    <el-dialog
      v-model="pwdVisible"
      :title="mustChangePassword ? '首次登录，请修改初始口令' : '修改密码'"
      width="440px"
      :show-close="!mustChangePassword"
      :close-on-click-modal="!mustChangePassword"
      :close-on-press-escape="!mustChangePassword"
    >
      <p v-if="mustChangePassword" class="pwd-tip">
        这是管理员（或平台运营）设的初始口令，只能使用一次。改完需要用新密码重新登录。
      </p>
      <el-form ref="pwdFormRef" :model="pwdForm" :rules="pwdRules" label-width="90px">
        <el-form-item label="当前密码" prop="oldPassword">
          <el-input v-model="pwdForm.oldPassword" type="password" show-password />
        </el-form-item>
        <el-form-item label="新密码" prop="newPassword">
          <el-input v-model="pwdForm.newPassword" type="password" show-password />
        </el-form-item>
        <el-form-item label="确认新密码" prop="confirmPassword">
          <el-input v-model="pwdForm.confirmPassword" type="password" show-password />
        </el-form-item>
      </el-form>
      <template #footer>
        <!-- 待改密时不给"取消"：服务端已经拦着其它接口了，留着这个按钮只是让人以为能跳过 -->
        <el-button v-if="!mustChangePassword" @click="pwdVisible = false">取消</el-button>
        <el-button type="primary" @click="submitPassword">保存</el-button>
      </template>
    </el-dialog>
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
.header-actions {
  display: flex;
  gap: 4px;
}
.pwd-tip {
  margin: 0 0 12px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}
</style>
