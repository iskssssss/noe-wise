<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from './stores/auth'
const auth = useAuthStore()
const router = useRouter()
const route = useRoute()
const switching = ref(false)
const visible = computed(
  () => !route.meta.requiresAuth || (auth.isAuthenticated && !switching.value)
)
let navigationVersion = 0
// 其他浏览器标签换号时先撤销当前页面；新身份必须重新通过平台权限校验。
watch(
  () => auth.sessionIdentity,
  async () => {
    const version = ++navigationVersion
    if (!route.meta.requiresAuth) {
      switching.value = false
      return
    }
    const redirect = route.fullPath
    switching.value = true
    await nextTick()
    if (version !== navigationVersion) return
    try {
      await router.replace(
        auth.isAuthenticated
          ? '/platform/home'
          : { path: '/login', query: { redirect, switchAccount: '1' } }
      )
    } finally {
      if (version === navigationVersion) switching.value = false
    }
  },
  { flush: 'post' }
)
</script>
<template><RouterView v-if="visible" :key="auth.identityVersion" /></template>
