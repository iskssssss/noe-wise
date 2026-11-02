<script setup lang="ts">
import { useRoute } from 'vue-router'
import FoundationPage from '@skillforge/shared/foundation/FoundationPage.vue'
import { api, foundationRequest } from '../api'
import { useAuthStore } from '../stores/auth'
import type { SecurityAuthTokens } from '@skillforge/shared/foundation/security/api'
const route = useRoute(),
  auth = useAuthStore()
async function accept(tokens: SecurityAuthTokens) {
  auth.applyTokens(tokens)
  await auth.fetchMe()
}
</script>
<template>
  <FoundationPage
    :area="String(route.meta.foundationArea)"
    :platform="true"
    :request="foundationRequest"
    :permissions="auth.user?.permissions ?? []"
    :security-api="api.security"
    :security-auth-api="api.auth"
    :on-authentication="accept"
    :on-logout="() => auth.logout(false)"
  />
</template>
