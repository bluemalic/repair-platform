<script setup lang="ts">
import AuthShell from '@/components/auth/AuthShell.vue'
import LoginForm from '@/components/auth/LoginForm.vue'
</script>

<template>
  <AuthShell heading="管理端登录">
    <LoginForm mode="school" />
  </AuthShell>
</template>
