<script setup lang="ts">
import { computed } from 'vue'
import { AdminPage } from '@skillforge/shared/admin'
import UsagePanel from '@skillforge/shared/integration/UsagePanel.vue'
import { api } from '../../api'
import { useAuthStore } from '../../stores/auth'
const props = defineProps<{ tenantId?: string; embedded?: boolean }>()
const auth = useAuthStore()
const identity = computed(() =>
  JSON.stringify([
    auth.user?.sub,
    auth.user?.tenant_id,
    auth.user?.authorization_version,
    auth.user?.permissions,
    props.tenantId
  ])
)
</script>
<template>
  <AdminPage
    :embedded="embedded"
    title="用量查询"
    description="核对本机构调用用量、成本确认与调整记录。"
  >
    <UsagePanel
      v-if="!tenantId || tenantId === auth.user?.tenant_id"
      :api="api.usage"
      :identity="identity"
      :fixed-tenant-id="auth.user?.tenant_id"
      scope-label="本机构用量"
    />
    <ElAlert
      v-else
      title="本机构用量入口不能读取其他机构，请使用获授权的下级机构用量入口。"
      type="warning"
      :closable="false"
    />
  </AdminPage>
</template>
