<script setup lang="ts">
import { useRoute } from 'vue-router'
import FoundationPage from '@skillforge/shared/foundation/FoundationPage.vue'
import { api, foundationRequest } from '../api'
import { useAuthStore } from '../stores/auth'
import type { SecurityAuthTokens } from '@skillforge/shared/foundation/security/api'
const route = useRoute(),
  auth = useAuthStore()
defineProps<{ area?: string; embedded?: boolean; tenantId?: string }>()
async function accept(tokens: SecurityAuthTokens) {
  auth.applyTokens(tokens)
  await auth.fetchMe()
}
</script>
<template>
  <FoundationPage
    v-if="!tenantId || tenantId === auth.user?.tenant_id"
    :area="area ?? String(route.meta.foundationArea)"
    :platform="false"
    :request="foundationRequest"
    :permissions="auth.user?.permissions ?? []"
    :security-api="api.security"
    :security-auth-api="api.auth"
    :on-authentication="accept"
    :on-logout="() => auth.logout(false)"
  />
  <ElAlert
    v-else
    title="账号、部门与本机构配置由目标机构管理员维护；当前管理关系不提供这些业务权限。"
    type="info"
    :closable="false"
  />
</template>
