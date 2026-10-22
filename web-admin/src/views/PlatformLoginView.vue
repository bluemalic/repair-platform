<script setup lang="ts">
import AuthShell from '@/components/auth/AuthShell.vue'
import LoginForm from '@/components/auth/LoginForm.vue'
</script>

<template>
  <AuthShell heading="平台运营登录">
    <LoginForm mode="platform" />
  </AuthShell>
</template>
