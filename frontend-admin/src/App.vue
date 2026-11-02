<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElConfigProvider } from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import { useAuthStore } from './stores/auth'
const auth = useAuthStore(),
  route = useRoute(),
  router = useRouter(),
  switching = ref(false)
const visible = computed(
  () => !route.meta.requiresAuth || (auth.isAuthenticated && !switching.value)
)
let navigationVersion = 0
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
        auth.isAuthenticated ? '/admin/home' : { path: '/login', query: { redirect } }
      )
    } finally {
      if (version === navigationVersion) switching.value = false
    }
  },
  { flush: 'post' }
)
</script>
<template>
  <ElConfigProvider :locale="zhCn"
    ><RouterView v-if="visible" :key="auth.identityVersion"
  /></ElConfigProvider>
</template>
