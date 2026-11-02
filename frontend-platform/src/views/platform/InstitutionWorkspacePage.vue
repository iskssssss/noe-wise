<script setup lang="ts">
import FeedbackNotice from '@skillforge/shared/feedback/FeedbackNotice.vue'
import { computed, defineAsyncComponent, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useResource } from '@skillforge/shared/admin'
import InstitutionWorkspace from '@skillforge/shared/institution/InstitutionWorkspace.vue'
import UsagePanel from '@skillforge/shared/integration/UsagePanel.vue'
import type { UsageApi } from '@skillforge/shared/integration/usage'
import { HttpClientError } from '@skillforge/shared/utils/http'
import { api } from '../../api'
import { useAuthStore } from '../../stores/auth'
import { useActionPermissions } from '../../composables/actionPermissions'
import { loadInstitutionDirectory } from '../../workspace/directory'
import { institutionSection, platformInstitutionSections } from '../../workspace/sections'
const TenantSettingsPanel = defineAsyncComponent(() => import('./TenantSettingsPanel.vue'))
const InstitutionIntegrationsPage = defineAsyncComponent(
  () => import('./InstitutionIntegrationsPage.vue')
)
const InstitutionCredentialsPage = defineAsyncComponent(
  () => import('./InstitutionCredentialsPage.vue')
)
const InstitutionHierarchyPanel = defineAsyncComponent(
  () => import('./InstitutionHierarchyPanel.vue')
)
const AgentGrantsPage = defineAsyncComponent(() => import('./AgentGrantsPage.vue'))
const AgentDeploymentsPage = defineAsyncComponent(() => import('./AgentDeploymentsPage.vue'))
const InstitutionOnboardingFlow = defineAsyncComponent(
  () => import('./InstitutionOnboardingFlow.vue')
)
const InstitutionAgentSetup = defineAsyncComponent(() => import('./InstitutionAgentSetup.vue'))

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const { allowed } = useActionPermissions()
const selectedId = computed(() => (typeof route.params.id === 'string' ? route.params.id : ''))
const section = computed(() => institutionSection(route.query.section))
// 向导只更新展示目标，输入 prop 和命令身份继续固定在进入流程时的目标。
const onboardingTarget = ref('')
const displayId = computed(() =>
  section.value === 'onboarding' && onboardingTarget.value
    ? onboardingTarget.value
    : selectedId.value
)
watch([section, selectedId], () => {
  onboardingTarget.value = ''
})
const integrationId = computed(() =>
  typeof route.query.integration_id === 'string' ? route.query.integration_id : ''
)
const accessView = computed(() =>
  typeof route.query.view === 'string'
    ? route.query.view
    : allowed('platform.institutions.read')
      ? 'mappings'
      : 'credentials'
)
const agentView = computed(() =>
  typeof route.query.view === 'string'
    ? route.query.view
    : allowed('platform.agent_deployments.read')
      ? 'deployments'
      : 'grants'
)
const progress = ref({ loaded: 0, total: 0 })
const directory = useResource(async (signal) => {
  try {
    const result = await loadInstitutionDirectory(allowed, signal, (loaded, total) => {
      if (!signal.aborted) progress.value = { loaded, total }
    })
    if (!signal.aborted)
      progress.value = { loaded: result.nodes.length, total: result.nodes.length }
    return result
  } catch (reason) {
    if (reason instanceof HttpClientError && [401, 403].includes(reason.status))
      directory.data.value = undefined
    throw reason
  }
})
const nodes = computed(() => directory.data.value?.nodes ?? [])
const selected = computed(() => nodes.value.find((node) => node.id === displayId.value))
const details = useResource(async (signal) => {
  const requested = selectedId.value
  if (!requested || !allowed('foundation.tenants.read')) return undefined
  const result = await api.admin.getTenant(requested, { signal, silent: true })
  if (!allowed('foundation.tenants.read') || requested !== selectedId.value)
    throw Error('机构读取目标已变化')
  return result
})
const canCreate = computed(
  () =>
    allowed('foundation.tenants.create') ||
    allowed('platform.institutions.provision') ||
    allowed('platform.institutions.associate')
)
const canAccess = computed(
  () => allowed('platform.institutions.read') || allowed('platform.institution_credentials.read')
)
const canAgents = computed(
  () => allowed('platform.agent_deployments.read') || allowed('platform.agent_grants.read')
)
const canUsage = computed(() =>
  ['summary', 'details', 'events'].some((kind) => allowed(`platform.usage.${kind}.read`))
)
const visibleSections = computed(() =>
  platformInstitutionSections.filter(({ key }) => {
    if (key === 'settings') return allowed('foundation.tenants.read')
    if (key === 'access') return canAccess.value
    if (key === 'agents') return canAgents.value
    if (key === 'relations') return allowed('platform.institution_relations.read')
    if (key === 'usage') return canUsage.value
    return true
  })
)
const title = computed(
  () =>
    selected.value?.label ||
    details.data.value?.name ||
    (selectedId.value ? `机构 ${selectedId.value}` : '选择机构开始维护')
)
const usageIdentity = computed(() => `${auth.identityVersion}:${selectedId.value}`)
/** 固定当前机构覆盖所有查询及翻页，隐藏筛选器不会扩大到平台全部用量。 */
const usageApi = computed<UsageApi>(() => {
  const tenant = selectedId.value
  return {
    summary: (query, options) => api.usage.summary({ ...query, tenant_id: tenant }, options),
    details: (query, options) => api.usage.details({ ...query, tenant_id: tenant }, options),
    events: (query, options) => api.usage.events({ ...query, tenant_id: tenant }, options)
  }
})
function navigate(
  target: string,
  nextSection = section.value,
  extras: Record<string, string> = {}
) {
  void router.push({
    path: target ? `/platform/tenants/${encodeURIComponent(target)}` : '/platform/tenants',
    query: { section: nextSection, ...extras }
  })
}
function changeSection(value: string) {
  const extra: Record<string, string> = {}
  if (value === 'access' && integrationId.value) extra.integration_id = integrationId.value
  navigate(selectedId.value, value, extra)
}
function selectIntegration(value: string, tenantId?: string) {
  navigate(tenantId || selectedId.value, 'access', { view: 'credentials', integration_id: value })
}
function completed(value: { tenantId: string; integrationId?: string }) {
  void directory.reload()
  navigate(
    value.tenantId,
    'overview',
    value.integrationId ? { integration_id: value.integrationId } : {}
  )
}
function refresh() {
  void directory.reload()
  if (selectedId.value) void details.reload()
}
watch(
  selectedId,
  () => {
    details.invalidate()
    details.data.value = undefined
    if (selectedId.value && allowed('foundation.tenants.read')) void details.reload()
  },
  { immediate: true, flush: 'sync' }
)
onMounted(() => {
  void directory.reload()
})
</script>

<template>
  <InstitutionWorkspace
    :nodes="nodes"
    :selected-id="displayId"
    :sections="visibleSections"
    :section="section"
    title="机构工作台"
    :actor-label="`平台人员：${auth.user?.username ?? ''}`"
    :loading="directory.loading.value"
    :error="directory.error.value"
    :loaded-count="progress.loaded"
    :total="progress.total"
    :directory-complete="directory.data.value?.complete ?? false"
    @select="(id) => navigate(id, section === 'onboarding' ? 'overview' : section)"
    @section="changeSection"
    @refresh="refresh"
  >
    <template #extra>
      <ElButton v-if="canCreate" type="primary" @click="navigate('', 'onboarding')"
        >新增或接入机构</ElButton
      >
    </template>
    <template #summary>
      <p v-if="details.data.value?.plan_code" class="admin-field-help">
        套餐：{{ details.data.value.plan_code }}
      </p>
      <p v-if="!directory.data.value?.hierarchy && directory.data.value" class="admin-field-help">
        当前显示有权操作的机构目录；机构关系按独立权限读取。
      </p>
    </template>
    <InstitutionOnboardingFlow
      v-if="section === 'onboarding' && canCreate"
      :key="`onboarding:${selectedId}`"
      :tenant-id="selectedId || undefined"
      @completed="completed"
      @changed="refresh"
      @target-selected="(value) => (onboardingTarget = value.tenantId)"
    />
    <ElEmpty v-else-if="!selectedId" description="从左侧选择机构，或新增、接入一个机构。" />
    <div v-else :key="`${selectedId}:${section}:${route.query.view ?? ''}:${integrationId}`">
      <FeedbackNotice
        v-if="details.error.value"
        :title="details.error.value"
        type="error"
        :closable="false"
      />
      <div v-if="section === 'overview'" class="institution-overview">
        <div class="institution-overview__intro">
          <p>在当前机构上下文中继续维护，下面的入口会自动带入机构身份。</p>
          <p v-if="selected?.parentId" class="admin-field-help">
            上级机构：{{
              nodes.find((node) => node.id === selected?.parentId)?.label ?? selected.parentId
            }}
          </p>
        </div>
        <div class="institution-overview__tasks">
          <button
            v-if="allowed('foundation.tenants.read')"
            type="button"
            @click="changeSection('settings')"
          >
            <strong>机构设置</strong><span>资料、套餐配额与机构账号</span>
          </button>
          <button v-if="canAccess" type="button" @click="changeSection('access')">
            <strong>接入与凭证</strong><span>关联外部系统，维护固定机构执行凭证</span>
          </button>
          <button v-if="canAgents" type="button" @click="changeSection('agents')">
            <strong>智能体</strong><span>查看授权、部署及需要机构配置的事项</span>
          </button>
          <button
            v-if="allowed('platform.institution_relations.read')"
            type="button"
            @click="changeSection('relations')"
          >
            <strong>下级机构</strong><span>查看当前关系，明确挂接、迁移或解除</span>
          </button>
          <button v-if="canUsage" type="button" @click="changeSection('usage')">
            <strong>机构用量</strong><span>读取该机构的真实用量和供应商成本</span>
          </button>
        </div>
        <ElAlert
          title="接入检查只读取现有状态，不会自动调用模型、工具或外部发送。"
          type="info"
          :closable="false"
        />
      </div>
      <template v-else-if="section === 'settings'">
        <TenantSettingsPanel
          v-if="allowed('foundation.tenants.read')"
          :tenant-id="selectedId"
          embedded
          @changed="refresh"
        />
        <ElEmpty v-else description="当前账号没有该机构设置读取权限，其他获准维护仍可继续。" />
      </template>
      <template v-else-if="section === 'access'">
        <div class="institution-subnav" role="group" aria-label="接入维护分区">
          <ElButton
            v-if="allowed('platform.institutions.read')"
            :type="accessView === 'mappings' ? 'primary' : 'default'"
            @click="navigate(selectedId, 'access')"
            >外部映射</ElButton
          >
          <ElButton
            v-if="allowed('platform.institution_credentials.read')"
            :type="accessView === 'credentials' ? 'primary' : 'default'"
            @click="
              navigate(selectedId, 'access', {
                view: 'credentials',
                ...(integrationId ? { integration_id: integrationId } : {})
              })
            "
            >执行凭证</ElButton
          >
          <ElButton
            v-if="allowed('platform.institutions.associate')"
            @click="navigate(selectedId, 'onboarding')"
            >继续接入配置</ElButton
          >
        </div>
        <InstitutionIntegrationsPage
          v-if="accessView === 'mappings' && allowed('platform.institutions.read')"
          :tenant-id="selectedId"
          embedded
          @integration-selected="selectIntegration"
          @provisioned="completed"
        />
        <InstitutionCredentialsPage
          v-else-if="
            accessView === 'credentials' && allowed('platform.institution_credentials.read')
          "
          :tenant-id="selectedId"
          :integration-id="integrationId || undefined"
          embedded
        />
        <ElEmpty v-else description="当前账号没有所选接入分区的读取权限。" />
      </template>
      <template v-else-if="section === 'agents'">
        <div class="institution-subnav" role="group" aria-label="智能体维护分区">
          <ElButton
            v-if="allowed('platform.agent_deployments.read')"
            :type="agentView === 'deployments' ? 'primary' : 'default'"
            @click="navigate(selectedId, 'agents')"
            >已部署智能体</ElButton
          >
          <ElButton
            v-if="allowed('platform.agent_grants.read')"
            :type="agentView === 'grants' ? 'primary' : 'default'"
            @click="navigate(selectedId, 'agents', { view: 'grants' })"
            >定义授权</ElButton
          >
          <ElButton
            v-if="allowed('platform.agent_deployments.create')"
            type="primary"
            @click="navigate(selectedId, 'agents', { view: 'setup' })"
            >添加智能体</ElButton
          >
        </div>
        <InstitutionAgentSetup
          v-if="agentView === 'setup' && allowed('platform.agent_deployments.create')"
          :tenant-id="selectedId"
          :integration-id="integrationId || undefined"
          @completed="navigate(selectedId, 'agents')"
        />
        <AgentGrantsPage
          v-else-if="agentView === 'grants' && allowed('platform.agent_grants.read')"
          :tenant-id="selectedId"
          embedded
        />
        <AgentDeploymentsPage
          v-else-if="agentView === 'deployments' && allowed('platform.agent_deployments.read')"
          :tenant-id="selectedId"
          embedded
        />
        <ElEmpty v-else description="当前账号没有所选智能体分区的读取权限。" />
        <p class="admin-field-help">
          工具、知识和协作对象的本地绑定由机构管理员完成；平台部署权限不授予私有业务资源访问权。
        </p>
      </template>
      <template v-else-if="section === 'relations'">
        <InstitutionHierarchyPanel
          v-if="allowed('platform.institution_relations.read')"
          :tenant-id="selectedId"
          embedded
          @changed="refresh"
        />
        <ElEmpty v-else description="当前账号没有机构关系读取权限。" />
      </template>
      <UsagePanel
        v-else-if="section === 'usage' && canUsage"
        :api="usageApi"
        :fixed-tenant-id="selectedId"
        :identity="usageIdentity"
        :scope-label="`${title}的用量`"
        :can-events="allowed('platform.usage.events.read')"
        :can-details="allowed('platform.usage.details.read')"
        :can-summary="allowed('platform.usage.summary.read')"
      />
      <ElEmpty v-else description="当前账号没有该分区的维护权限。" />
    </div>
  </InstitutionWorkspace>
</template>

<style scoped>
.institution-overview {
  display: grid;
  gap: 20px;
}
.institution-overview__intro h2 {
  margin: 0 0 8px;
}
.institution-overview__intro p {
  color: var(--admin-muted);
}
.institution-overview__tasks {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 220px), 1fr));
  gap: 12px;
}
.institution-overview__tasks button {
  display: grid;
  gap: 8px;
  padding: 18px;
  text-align: left;
  border: 1px solid var(--el-border-color);
  border-radius: var(--el-border-radius-base);
  background: var(--el-fill-color-blank);
  color: var(--el-text-color-primary);
  cursor: pointer;
  font: inherit;
}
.institution-overview__tasks button:hover {
  border-color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}
.institution-overview__tasks button:focus-visible {
  outline: 2px solid var(--el-color-primary);
  outline-offset: 3px;
}
.institution-overview__tasks span {
  color: var(--admin-muted);
  font-size: var(--el-font-size-small);
}
.institution-subnav {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 20px;
}
.institution-subnav :deep(.el-button + .el-button) {
  margin-left: 0;
}
</style>
