<script setup lang="ts">
import FeedbackNotice from '@skillforge/shared/feedback/FeedbackNotice.vue'
import { onMounted } from 'vue'
import { AdminPage, statusLabel, useResource } from '@skillforge/shared/admin'
import { api } from '../api'
import { useManagementIdentity } from '../composables/managementIdentity'
import type { AgentGrant } from '@skillforge/shared/integration/agentDeployments'
const props = defineProps<{ tenantId: string }>()
const emit = defineEmits<{ deploy: [] }>()
const identity = useManagementIdentity()
function sourceLabel(row: AgentGrant) {
  if (row.owner_kind?.toLowerCase() === 'platform') return '平台公共定义'
  if (row.owner_kind?.toLowerCase() === 'tenant') return `机构共享定义 · ${row.owner_tenant_id}`
  return '来源信息未返回'
}
const grants = useResource(async (signal) => {
  identity.requirePermission('tenant.agent_deployments.read')
  const result = await api.agentDeployments.grants({ signal, silent: true })
  identity.requirePermission('tenant.agent_deployments.read')
  if (result.items.some((item) => item.target_tenant_id !== props.tenantId))
    throw Error('收到的授权不属于当前机构，请刷新权限后重试')
  return result
})
onMounted(() => void grants.reload())
</script>
<template>
  <AdminPage
    embedded
    title="收到的授权"
    description="授权只允许消费定义，部署后才会形成稳定机构实例。"
  >
    <template #extra
      ><ElButton :loading="grants.loading.value" @click="grants.reload">刷新授权</ElButton
      ><ElButton
        v-if="identity.allowed('tenant.agent_deployments.create')"
        type="primary"
        @click="emit('deploy')"
        >继续部署</ElButton
      ></template
    >
    <FeedbackNotice
      v-if="grants.error.value"
      :title="grants.error.value"
      type="error"
      :closable="false"
    />
    <ElTable
      :data="grants.data.value?.items ?? []"
      row-key="grant_id"
      v-loading="grants.loading.value"
      empty-text="尚无当前有效的定义消费授权"
    >
      <ElTableColumn prop="definition_name" label="定义" min-width="180" show-overflow-tooltip />
      <ElTableColumn label="定义来源" min-width="170"
        ><template #default="{ row }">{{ sourceLabel(row as AgentGrant) }}</template></ElTableColumn
      >
      <ElTableColumn label="可用动作" min-width="160"
        ><template #default="{ row }">{{
          row.actions.map((action: string) => (action === 'deploy' ? '部署' : '执行')).join('、')
        }}</template></ElTableColumn
      >
      <ElTableColumn label="状态" width="100"
        ><template #default="{ row }">{{ statusLabel(row.status) }}</template></ElTableColumn
      >
      <ElTableColumn prop="grant_id" label="授权依据" min-width="180" show-overflow-tooltip />
    </ElTable>
  </AdminPage>
</template>
