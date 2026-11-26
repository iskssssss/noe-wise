<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { AdminPage } from '@skillforge/shared/admin'
import UsagePanel from '@skillforge/shared/integration/UsagePanel.vue'
import type { UsageApi, UsageEvent } from '@skillforge/shared/integration/usage'
import UsageTrajectory from './UsageTrajectory.vue'
import { api } from '../../api'
import { useAuthStore } from '../../stores/auth'
const auth = useAuthStore()
const identity = computed(() =>
  JSON.stringify([auth.user?.sub, auth.user?.authorization_version, auth.user?.permissions])
)
const allowed = (action: string) => auth.user?.permissions.includes(action) ?? false
const tenantNames = ref<Record<string, string>>({})
const tenantLabel = (id: string | null | undefined) =>
  id ? (tenantNames.value[id] ?? id) : '未记录'
watch(identity, () => {
  tenantNames.value = {}
})
// 机构名称复用当前用量候选，不额外依赖机构维护目录或扩大读取权限。
const usageApi = computed<UsageApi>(() => {
  const requestedIdentity = identity.value
  return {
    ...api.usage,
    async filterOptions(query, options) {
      const result = await api.usage.filterOptions(query, options)
      if (
        query.field === 'tenant_id' &&
        requestedIdentity === identity.value &&
        !options?.signal?.aborted
      )
        for (const option of result) tenantNames.value[option.id] = option.label
      return result
    }
  }
})
</script>
<template>
  <AdminPage title="平台用量" description="按机构、接入、部署与任务查询实际用量。"
    ><UsagePanel
      :api="usageApi"
      :identity="identity"
      location-key="platform-usage"
      show-tenant
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
