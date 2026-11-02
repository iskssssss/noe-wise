<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { AdminPage } from '@skillforge/shared/admin'
import UsagePanel from '@skillforge/shared/integration/UsagePanel.vue'
import { api } from '../../api'
import { useAuthStore } from '../../stores/auth'
import type { UsageApi } from '@skillforge/shared/integration/usage'

const props = defineProps<{ tenantId?: string; embedded?: boolean }>()
const auth = useAuthStore()
const route = useRoute()
const initialTenantId = computed(
  () =>
    props.tenantId ||
    (typeof route.query.tenant_id === 'string' ? route.query.tenant_id : undefined)
)
const identity = computed(() =>
  JSON.stringify([
    auth.user?.sub,
    auth.user?.tenant_id,
    auth.user?.authorization_version,
    auth.user?.permissions,
    props.tenantId
  ])
)
const allowed = (action: string) => auth.user?.permissions.includes(action) ?? false
// 固定目标由请求适配器收窄，隐藏筛选和重置也不能扩大到其他下级。
const scopedApi: UsageApi = {
  summary: (query, options) =>
    api.headquartersUsage.summary(
      { ...query, ...(props.tenantId ? { tenant_id: props.tenantId } : {}) },
      options
    ),
  details: (query, options) =>
    api.headquartersUsage.details(
      { ...query, ...(props.tenantId ? { tenant_id: props.tenantId } : {}) },
      options
    ),
  events: (query, options) =>
    api.headquartersUsage.events(
      { ...query, ...(props.tenantId ? { tenant_id: props.tenantId } : {}) },
      options
    )
}
</script>

<template>
  <AdminPage
    :embedded="embedded"
    title="下级机构用量"
    description="读取当前独立获准的下级机构用量；机构关系本身不授予用量读取权。"
  >
    <UsagePanel
      :api="scopedApi"
      :identity="identity"
      :show-tenant="!tenantId"
      :initial-tenant-id="initialTenantId"
      :fixed-tenant-id="props.tenantId"
      :scope-label="tenantId ? '目标机构用量' : '下级机构用量'"
      :can-summary="allowed('tenant.sub_institutions.usage_summary.read')"
      :can-details="allowed('tenant.sub_institutions.usage_details.read')"
      :can-events="allowed('tenant.sub_institutions.usage_events.read')"
    />
  </AdminPage>
</template>
