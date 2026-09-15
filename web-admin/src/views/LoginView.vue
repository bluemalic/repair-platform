<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { login } from '@/api/auth'
import { setLogin } from '@/store/auth'

const route = useRoute()
const router = useRouter()
const loading = ref(false)
const form = reactive({ tenantCode: 'gdou', username: 'admin', password: '' })

async function submit() {
  if (!form.username || !form.password) {
    ElMessage.warning('请输入账号与密码')
    return
  }
  loading.value = true
  try {
    const vo = await login(form.tenantCode, form.username, form.password)
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
      <h2>后勤报修 · 管理端</h2>
      <el-form :model="form" label-width="72px" @submit.prevent="submit">
        <el-form-item label="学校编码">
          <el-input v-model="form.tenantCode" />
        </el-form-item>
        <el-form-item label="账号">
          <el-input v-model="form.username" placeholder="后勤管理账号" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="form.password" type="password" show-password @keyup.enter="submit" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="submit">登录</el-button>
        </el-form-item>
      </el-form>
      <p class="hint">本地演示账号见 docs/dev-seed.sql（admin / Repair@2026）</p>
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
.hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  margin: 0;
}
</style>
