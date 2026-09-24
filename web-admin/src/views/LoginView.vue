<script setup lang="ts">
import { reactive, ref, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { login } from '@/api/auth'
import { setLogin } from '@/store/auth'

const route = useRoute()
const router = useRouter()
const loading = ref(false)

/**
 * 平台运营登录：学校编码固定填 `platform`（那是"平台自身"这个租户的编码），所以这个框直接藏起来。
 * 平台账号与学校账号走的是**同一个登录接口**，只是它挂在租户 0 上——登录契约没有任何特殊分支
 * （ADR-012）。
 *
 * <p><b>模式放在 URL 的 query 上（`?platform=1`），不放组件内部状态</b>：状态一卸载就没了，
 * 而"离开登录页再回来"在这个应用里有三条路——登出、改完口令（服务端会注销会话，必须重登）、
 * token 过期。用内部状态时，那三条路都会静默退回学校模式（学校编码默认 gdou），
 * 于是平台账号怎么登都是"用户名或密码错误"——**踩过一次**，很难从现象倒推回来。
 * 放 URL 上还有个附带好处：平台入口可以被收藏。
 */
const isPlatformLogin = computed(() => route.query.platform === '1')

const form = reactive({
  tenantCode: 'gdou',
  // 平台模式下不预填演示账号：预填 `admin` 会引导人拿**学校**的账号去登平台入口，
  // 现象是"用户名或密码错误"，而真正的原因是这个入口不认那个账号（踩过一次）
  username: isPlatformLogin.value ? '' : 'admin',
  password: '',
})

/**
 * 切换登录模式时**把账号也清掉**：默认值是演示用的 `admin`，留着它就会让人在平台入口
 * 拿学校的账号去登——现象是"用户名或密码错误"，而真正的原因是这个入口根本不认那个账号。
 * 踩过一次，所以这里不只是清口令。
 */
function togglePlatformLogin() {
  form.username = ''
  form.password = ''
  router.replace(isPlatformLogin.value ? { query: {} } : { query: { platform: '1' } })
}

/**
 * 演示部署才显示的账号提示。
 *
 * <p>演示站必须让访客知道拿什么登录（否则点进来是一片登录页，谁也不知道账号），
 * 但生产站提示账号信息是错的、还会误导。所以做成**构建时注入**：
 * 构建前设 `VITE_DEMO_ACCOUNTS="管理端 admin / H5 worker01、20260001，口令 xxx"` 才会有这一行，
 * 不设就没有——两种部署用同一份代码。
 */
const demoHint = import.meta.env.VITE_DEMO_ACCOUNTS as string | undefined

async function submit() {
  if (!form.username || !form.password) {
    ElMessage.warning('请输入账号与密码')
    return
  }
  loading.value = true
  try {
    const tenantCode = isPlatformLogin.value ? 'platform' : form.tenantCode
    const vo = await login(tenantCode, form.username, form.password)
    if (isPlatformLogin.value) {
      if (vo.userType !== 4) {
        ElMessage.error('该账号不是平台运营账号')
        return
      }
      setLogin(vo)
      router.push('/platform/tenants')
      return
    }
    if (vo.userType !== 3) {
      ElMessage.error('该账号不是后勤管理角色，无法登录管理端')
      return
    }
    setLogin(vo)
    router.push((route.query.redirect as string) || '/tickets')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <el-card class="login-card">
      <h2 class="title">{{ isPlatformLogin ? '后勤报修 · 平台运营' : '后勤报修 · 管理端' }}</h2>
      <el-form :model="form" label-width="72px" @submit.prevent="submit">
        <el-form-item v-if="!isPlatformLogin" label="学校编码">
          <el-input v-model="form.tenantCode" />
        </el-form-item>
        <el-form-item label="账号">
          <el-input
            v-model="form.username"
            :placeholder="isPlatformLogin ? '平台运营账号' : '后勤管理账号'"
          />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="form.password" type="password" show-password @keyup.enter="submit" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="submit">登录</el-button>
          <el-button link type="primary" @click="togglePlatformLogin">
            {{ isPlatformLogin ? '返回学校登录' : '平台运营登录' }}
          </el-button>
        </el-form-item>
      </el-form>
      <p v-if="!isPlatformLogin" class="hint">
        忘记口令？后勤管理员请联系平台运营；学生 / 维修工请联系学校后勤。
      </p>
      <!--
        演示账号提示只在**学校登录**模式下显示：它写的是学校那套账号（admin / 11111111），
        摆在平台入口上等于在误导人拿它去登平台（必然"用户名或密码错误"）。平台模式下什么都不显示。
      -->
      <p v-if="demoHint && !isPlatformLogin" class="hint">{{ demoHint }}</p>
    </el-card>
  </div>
</template>

<style scoped>
.login-page {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100vh;
  background: var(--el-fill-color-light);
}
.login-card {
  width: 380px;
}
/* 标题居中：卡片的宽度是定值，标题左对齐时右边会空出一大块，看着像没做完 */
.title {
  text-align: center;
}
.hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.6;
  margin: 0;
}
</style>
