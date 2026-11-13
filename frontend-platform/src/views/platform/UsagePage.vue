<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { AdminPage } from '@skillforge/shared/admin'
import UsagePanel from '@skillforge/shared/integration/UsagePanel.vue'
import type { UsageEvent } from '@skillforge/shared/integration/usage'
import UsageTrajectory from './UsageTrajectory.vue'
import { api } from '../../api'
import { loadTenantOptions, resolveTenantOption } from '../../api/entityOptions'
import { useAuthStore } from '../../stores/auth'
const auth = useAuthStore()
const identity = computed(() =>
  JSON.stringify([auth.user?.sub, auth.user?.authorization_version, auth.user?.permissions])
)
const allowed = (action: string) => auth.user?.permissions.includes(action) ?? false
const tenantOptions = { load: loadTenantOptions, resolve: resolveTenantOption }
const tenantNames = ref<Record<string, string>>({})
const tenantLabel = (id: string | null | undefined) =>
  id ? (tenantNames.value[id] ?? id) : '未记录'
onMounted(async () => {
  try {
    const options = await loadTenantOptions('')
    tenantNames.value = Object.fromEntries(options.map((option) => [option.id, option.label]))
  } catch {
    tenantNames.value = {}
  }
})
</script>
<template>
  <AdminPage title="平台用量" description="按机构、接入、部署与任务查询实际用量。"
    ><UsagePanel
      :api="api.usage"
      :identity="identity"
      show-tenant
      :tenant-options="tenantOptions"
      :tenant-label="tenantLabel"
      :can-events="allowed('platform.usage.events.read')"
      :can-summary="allowed('platform.usage.summary.read')"
      :can-details="allowed('platform.usage.details.read')"
    >
      <template #record-actions="{ row }">
        <UsageTrajectory v-if="allowed('foundation.sessions.read')" :row="row as UsageEvent" />
      </template> </UsagePanel
  ></AdminPage>
</template>
