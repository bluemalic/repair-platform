<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { login, ssoAvailable, ssoExchange } from '@/api/auth'
import { setLogin } from '@/store/auth'

/**
 * 学校登录与平台运营登录共用的表单。
 *
 * <p>两个入口拆成独立路由后，不需要再用 `?platform=1` 记模式——也就不会再出现
 * “离开登录页再回来，静默退回学校模式”那类问题（拆之前踩过）。
 * 角色校验仍然在拿到 LoginVO 之后做：不是目标角色就不写登录态，避免带着错身份进后台。
 */
const props = defineProps<{ mode: 'school' | 'platform' }>()

const isSchool = computed(() => props.mode === 'school')
const route = useRoute()
const router = useRouter()
const loading = ref(false)

/**
 * 演示账号提示是**构建时**注入的：不设 `VITE_DEMO_ACCOUNTS` 就没有这一行，
 * 两种部署共用同一份代码。预填也只在有提示时做——生产站不该默认出现 gdou / admin。
 */
const demoHint = import.meta.env.VITE_DEMO_ACCOUNTS as string | undefined

const form = reactive({
  tenantCode: isSchool.value && demoHint ? 'gdou' : '',
  username: isSchool.value && demoHint ? 'admin' : '',
  password: '',
})

/** 后端回调里的参数可能是 string / string[]，统一取第一个字符串。 */
function firstQuery(value: unknown): string {
  if (Array.isArray(value)) {
    return typeof value[0] === 'string' ? value[0] : ''
  }
  return typeof value === 'string' ? value : ''
}

/** 只接受站内后台路径，避免把 redirect 参数当成任意跳转。 */
function consoleTarget(): string {
  const redirect = firstQuery(route.query.redirect)
  return redirect === '/console' || redirect.startsWith('/console/') ? redirect : '/console/tickets'
}

function submit() {
  if (loading.value) {
    return
  }
  if (isSchool.value && !form.tenantCode.trim()) {
    ElMessage.warning('请输入学校编码')
    return
  }
  if (!form.username || !form.password) {
    ElMessage.warning('请输入账号与密码')
    return
  }
  loading.value = true
  void doLogin()
}

async function doLogin() {
  try {
    const tenantCode = isSchool.value ? form.tenantCode.trim() : 'platform'
    const vo = await login(tenantCode, form.username, form.password)
    if (isSchool.value) {
      if (vo.userType !== 3) {
        ElMessage.error('该账号不是后勤管理角色，请使用平台运营登录')
        return
      }
      setLogin(vo)
      router.push(consoleTarget())
      return
    }
    if (vo.userType !== 4) {
      ElMessage.error('该账号不是平台运营账号')
      return
    }
    setLogin(vo)
    router.push('/console/platform/tenants')
  } catch {
    // http 层已经弹过提示
  } finally {
    loading.value = false
  }
}

/**
 * 统一认证：**整页跳转**到后端，由它把浏览器送去学校的 IdP（不是 ajax）。
 * 先问一下有没有开通，没开通就直接提示，而不是跳到一个报错页。
 */
async function ssoLogin() {
  const code = form.tenantCode.trim()
  if (!code) {
    ElMessage.warning('请先填写学校编码')
    return
  }
  try {
    const availability = await ssoAvailable(code)
    if (!availability.enabled) {
      ElMessage.warning('这所学校还没有开通统一认证登录')
      return
    }
    location.href = `/api/auth/sso/authorize?tenantCode=${encodeURIComponent(code)}&target=ADMIN`
  } catch {
    // http 层已经弹过提示
  }
}

function forgotPassword() {
  ElMessage.info('忘记密码功能正在准备中，请联系平台运营或学校后勤重置。')
}

/**
 * 统一认证回来时带着一次性 `ssoCode`。回调可能落在 `/login`，也可能落在站点根路径
 * 再被守卫带 query 转发过来（见 router/index.ts）；history 模式下两者都能从 route.query 读到。
 * 码只用一次，读完立刻从地址栏清掉。
 */
onMounted(() => {
  if (!isSchool.value) {
    return
  }
  const ssoCode = firstQuery(route.query.ssoCode)
  const ssoError = firstQuery(route.query.ssoError)
  const ssoMessage = firstQuery(route.query.ssoMessage)
  if (!ssoCode && !ssoError) {
    return
  }
  // 一次性码立刻从地址栏清掉：它已经进过浏览器历史，换完/失败都不该继续留着。
  // 这里用原生 replaceState 而不是 router.replace，避免和下面的 router.push 抢一次导航。
  window.history.replaceState(null, '', window.location.pathname)
  if (ssoError) {
    ElMessage.error(ssoMessage || '统一认证登录失败')
    return
  }
  loading.value = true
  void (async () => {
    try {
      const vo = await ssoExchange(ssoCode)
      if (vo.userType !== 3) {
        ElMessage.error('该账号不是后勤管理角色，无法登录管理端')
        return
      }
      setLogin(vo)
      router.push('/console/tickets')
    } catch {
      // http 层已经弹过提示
    } finally {
      loading.value = false
    }
  })()
})
</script>

<template>
  <el-form class="login-form" :model="form" label-position="top" @submit.prevent="submit">
    <el-form-item v-if="isSchool" label="学校编码">
      <el-input v-model="form.tenantCode" autocomplete="organization" />
    </el-form-item>
    <!--
      平台登录没有学校编码字段，但两个页面要能无缝切换：
      用一个无内容、无标签的空白块占住第一个字段的高度（98px = 一个带标签字段 80px + 18px 间距）。
      这样平台页只显示第二、第三个框，但位置与学校端的账号、密码一致，按钮也对齐。
    -->
    <div v-else class="login-field-spacer" aria-hidden="true"></div>
    <el-form-item :label="isSchool ? '账号' : '平台运营账号'">
      <el-input v-model="form.username" autocomplete="username" />
    </el-form-item>
    <el-form-item label="密码">
      <el-input
        v-model="form.password"
        type="password"
        autocomplete="current-password"
        show-password
      />
    </el-form-item>

    <el-button class="login-submit" type="primary" :loading="loading" native-type="submit">
      登录
    </el-button>

    <div v-if="isSchool" class="login-links">
      <el-button link type="primary" @click="ssoLogin">统一认证登录</el-button>
      <RouterLink class="login-link" to="/platform/login">平台运营登录</RouterLink>
    </div>
    <div v-else class="login-links login-links--single">
      <RouterLink class="login-link" to="/login">返回学校登录</RouterLink>
    </div>

    <p v-if="isSchool" class="login-forgot">
      <button type="button" class="login-link login-link--plain" @click="forgotPassword">忘记密码</button>
    </p>
    <!-- 演示账号提示只在有注入时显示；平台登录页永远不显示（见 docs/05 §4.4） -->
    <p v-if="isSchool && demoHint" class="login-hint">{{ demoHint }}</p>
    <p v-if="!isSchool" class="login-hint">
      平台运营账号用于开通 / 停用学校，看不到任何学校的业务数据。
    </p>
  </el-form>
</template>

<style scoped>
.login-form {
  /* Element Plus 的变量会向下继承：只把登录按钮与链接换成品牌墨蓝，不改全局主题 */
  --el-color-primary: #0f2a44;
}

.login-form :deep(.el-form-item) {
  margin-bottom: 18px;
}
.login-field-spacer {
  height: 98px;
}
.login-form :deep(.el-form-item__label) {
  padding-bottom: 6px;
  color: #3f5568;
  font-size: 13px;
  font-weight: 600;
}
.login-form :deep(.el-input__wrapper) {
  min-height: 42px;
  border-radius: 10px;
  box-shadow: 0 0 0 1px #dce5ec inset;
}
.login-form :deep(.el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 1px #1d4ed8 inset;
}

.login-submit {
  width: 100%;
  height: 42px;
  margin-top: 4px;
  border-radius: 10px;
  font-weight: 600;
}

.login-links {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 10px;
}
.login-links--single {
  justify-content: flex-start;
}
/* 两个入口统一成同一种文字链接：14px / 500 / 蓝色，不加下划线 */
.login-links :deep(.el-button.is-link),
.login-link {
  height: auto;
  padding: 0;
  color: #1d4ed8;
  font-size: 14px;
  font-weight: 500;
  line-height: 20px;
  text-decoration: none;
}
.login-links :deep(.el-button.is-link:hover),
.login-link:hover {
  color: #0f2a44;
  text-decoration: none;
}
.login-link--plain {
  border: 0;
  background: transparent;
  cursor: pointer;
  font-family: inherit;
}
.login-forgot {
  margin: 12px 0 0;
  line-height: 20px;
}

.login-hint {
  margin: 14px 0 0;
  color: #64748b;
  font-size: 12px;
  line-height: 1.7;
}
</style>
