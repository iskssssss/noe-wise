<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useAuthStore } from '../../stores/auth'
const auth = useAuthStore(),
  router = useRouter()
function switchAccount() {
  auth.logout()
  void router.replace('/login?switchAccount=1')
}
</script>
<template>
  <ElResult
    icon="warning"
    title="无访问权限"
    sub-title="当前账号没有此页面的访问权限，可返回已授权页面或切换账号。"
  >
    <template #extra>
      <RouterLink to="/platform/home">
        <ElButton>返回首页</ElButton>
      </RouterLink>
      <ElButton type="primary" @click="switchAccount">切换账号</ElButton>
    </template>
  </ElResult>
</template>
