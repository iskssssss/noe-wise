<script setup lang="ts">
import { useRoute } from 'vue-router'
import CapabilityCatalog from '@skillforge/shared/components/CapabilityCatalog.vue'
import { api } from '../../api'
import { useAuthStore } from '../../stores/auth'
const route = useRoute(),
  auth = useAuthStore()
</script>
<template>
  <CapabilityCatalog
    :key="String(route.params.id ?? 'catalog')"
    :api="api.capabilities"
    :permissions="auth.user?.permissions ?? []"
    base-path="/platform/capabilities"
    skill-base-path="/platform/skills"
  />
</template>
