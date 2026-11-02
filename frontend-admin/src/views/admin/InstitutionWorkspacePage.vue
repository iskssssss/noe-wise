<script setup lang="ts">
import { computed, defineAsyncComponent, onMounted, onScopeDispose, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import InstitutionWorkspace from '@skillforge/shared/institution/InstitutionWorkspace.vue'
import type { InstitutionNode } from '@skillforge/shared/institution/workspace'
import { api } from '../../api'
import { useAuthStore } from '../../stores/auth'
import { useManagementIdentity } from '../../composables/managementIdentity'
import { institutionSections, loadInstitutionDirectory } from '../../institution/workspace'

const AgentsPage = defineAsyncComponent(() => import('./AgentsPage.vue'))
const DefinitionsPage = defineAsyncComponent(() => import('./InstitutionDefinitionsPage.vue'))
const GrantsPage = defineAsyncComponent(() => import('./InstitutionGrantsPage.vue'))
const DeploymentsPage = defineAsyncComponent(() => import('./AgentDeploymentsPage.vue'))
const SubInstitutionsPage = defineAsyncComponent(() => import('./SubInstitutionsPage.vue'))
const UsagePage = defineAsyncComponent(() => import('./UsagePage.vue'))
const HeadquartersUsagePage = defineAsyncComponent(() => import('./HeadquartersUsagePage.vue'))
const FoundationPage = defineAsyncComponent(() => import('../FoundationPage.vue'))
const GovernancePage = defineAsyncComponent(() => import('./GovernancePage.vue'))
const IncomingGrants = defineAsyncComponent(
  () => import('../../institution/IncomingGrantsPanel.vue')
)
const route = useRoute(),
  router = useRouter(),
  auth = useAuthStore()
const identity = useManagementIdentity()
const actorTenant = computed(() => auth.user?.tenant_id ?? '')
const targetTenant = computed(() =>
  typeof route.params.tenantId === 'string' ? route.params.tenantId : actorTenant.value
)
const self = computed(() => targetTenant.value === actorTenant.value)
const permissions = computed(() => auth.user?.permissions ?? [])
const nodes = ref<InstitutionNode[]>([])
const loading = ref(false),
  error = ref(''),
  directoryComplete = ref(false)
let controller: AbortController | undefined
let generation = 0
const current = computed(() =>
  identity.current() ? nodes.value.find((node) => node.id === targetTenant.value) : undefined
)
const sections = computed(() =>
  institutionSections(permissions.value, actorTenant.value, targetTenant.value)
)
const section = computed(() =>
  typeof route.query.section === 'string' ? route.query.section : 'overview'
)
const validSection = computed(() => sections.value.some((item) => item.key === section.value))
const allowed = (permission: string) => identity.allowed(permission)
const views = computed(() => {
  if (section.value === 'settings')
    return [
      ...(allowed('foundation.access.read') ? [{ key: 'members', label: '成员与权限' }] : []),
      ...([
        'foundation.preferences.manage',
        'foundation.entitlements.read',
        'foundation.settings.read'
      ].some(allowed)
        ? [{ key: 'preferences', label: '机构配置与配额' }]
        : [])
    ]
  return [
    ...(self.value && allowed('foundation.agents.read')
      ? [{ key: 'owned', label: '自有智能体' }]
      : []),
    ...(self.value && allowed('tenant.agent_definitions.read')
      ? [{ key: 'definitions', label: '共享定义' }]
      : []),
    ...(self.value && allowed('tenant.agent_deployments.read')
      ? [{ key: 'incoming', label: '收到的授权' }]
      : []),
    ...(allowed('tenant.agent_grants.read') ? [{ key: 'grants', label: '发出的授权' }] : []),
    ...(allowed('tenant.agent_deployments.read') ? [{ key: 'deployments', label: '部署实例' }] : [])
  ]
})
const requestedView = computed(() => (typeof route.query.view === 'string' ? route.query.view : ''))
const view = computed(() => requestedView.value || views.value[0]?.key || '')
const validView = computed(() => views.value.some((item) => item.key === view.value))

async function refresh() {
  const version = ++generation
  controller?.abort()
  controller = new AbortController()
  const signal = controller.signal
  const tenant = actorTenant.value
  if (!tenant || !identity.current()) return
  loading.value = true
  error.value = ''
  directoryComplete.value = false
  if (!nodes.value.length)
    nodes.value = [{ id: tenant, label: '本机构', description: '当前登录机构' }]
  try {
    const result = await loadInstitutionDirectory(
      api.institutionManagement,
      tenant,
      [...permissions.value],
      signal,
      () => {
        if (!identity.current()) throw Error('当前登录身份或授权已变化')
      }
    )
    if (version !== generation || !identity.current() || signal.aborted) return
    nodes.value = result
    directoryComplete.value = true
  } catch (failure) {
    if (version !== generation || !identity.current() || signal.aborted) return
    error.value = failure instanceof Error ? failure.message : '机构目录读取失败，请重试'
    const status =
      typeof failure === 'object' && failure !== null && 'status' in failure
        ? failure.status
        : undefined
    if (status === 401 || status === 403)
      nodes.value = [{ id: tenant, label: '本机构', description: '当前登录机构' }]
  } finally {
    if (version === generation && identity.current()) loading.value = false
  }
}
function selectTarget(id: string) {
  if (!nodes.value.some((node) => node.id === id) || !identity.current()) return
  const available = institutionSections(permissions.value, actorTenant.value, id)
  const next = available.some((item) => item.key === section.value) ? section.value : 'overview'
  void router.push({ path: `/admin/institutions/${id}`, query: { section: next } })
}
function selectSection(value: string) {
  if (!sections.value.some((item) => item.key === value)) return
  void router.push({ path: `/admin/institutions/${targetTenant.value}`, query: { section: value } })
}
function selectView(value: string) {
  if (!views.value.some((item) => item.key === value)) return
  void router.push({
    path: `/admin/institutions/${targetTenant.value}`,
    query: { section: section.value, view: value }
  })
}
onMounted(refresh)
onScopeDispose(() => {
  generation++
  controller?.abort()
})
</script>

<template>
  <InstitutionWorkspace
    :nodes="identity.current() ? nodes : []"
    :selected-id="targetTenant"
    :sections="sections"
    :section="section"
    title="机构工作台"
    :actor-label="`${auth.user?.username || '当前账号'} · 登录机构 ${actorTenant}`"
    :loading="loading"
    :error="error"
    :loaded-count="nodes.length"
    :total="nodes.length"
    :directory-complete="directoryComplete"
    @select="selectTarget"
    @section="selectSection"
    @refresh="refresh"
  >
    <template #extra>
      <ElButton v-if="!self" @click="selectTarget(actorTenant)">返回本机构</ElButton>
    </template>
    <template #summary>
      <p v-if="current" class="admin-muted">
        {{ self ? '当前维护本机构' : '当前以总部身份查看目标机构；具体操作另行核验授权' }}
      </p>
    </template>
    <ElAlert
      v-if="!identity.current()"
      title="登录身份或授权已变化，请重新进入工作台。"
      type="warning"
      :closable="false"
    />
    <ElAlert
      v-else-if="!current && !loading"
      title="当前机构不在可见或可操作目录中，请选择已有授权的机构。"
      type="warning"
      :closable="false"
    />
    <ElAlert
      v-else-if="current && !validSection"
      title="当前身份不能维护此机构的这个分区，请选择有权访问的分区。"
      type="warning"
      :closable="false"
    />
    <template v-else-if="current && validSection">
      <div v-if="section === 'overview'" class="institution-overview">
        <div class="admin-card institution-overview__intro">
          <h2>{{ self ? '本机构日常维护' : '目标机构维护' }}</h2>
          <p>
            {{
              self
                ? '在同一机构上下文中维护账号、接入和智能体，再核对实际用量。'
                : '管理目标与登录身份分别保留，所有动作继续核验当前总部及目标机构授权。'
            }}
          </p>
          <p class="admin-muted">
            {{
              self
                ? '业务工具、知识库和工作流仍由本机构原有入口维护。'
                : '目标机构的成员、知识、工具与本地资源绑定由目标机构管理员维护。机构关系不会授予这些业务权限。'
            }}
          </p>
        </div>
        <div class="institution-overview__actions">
          <button
            v-for="item in sections.filter((item) => item.key !== 'overview')"
            :key="item.key"
            class="institution-overview__action"
            @click="selectSection(item.key)"
          >
            <strong>{{ item.label }}</strong>
            <span>在当前机构继续办理</span>
          </button>
        </div>
        <div v-if="self" class="admin-card">
          <h3>机构业务资源</h3>
          <p class="admin-muted">进入原有维护页时保留当前机构身份；浏览器返回可回到工作台。</p>
          <div class="admin-actions">
            <RouterLink
              v-if="allowed('foundation.knowledge.read')"
              :to="{ path: '/admin/knowledge', query: { institution_id: targetTenant } }"
              >知识库</RouterLink
            >
            <RouterLink
              v-if="allowed('foundation.capabilities.read')"
              :to="{ path: '/admin/tools', query: { institution_id: targetTenant } }"
              >工具</RouterLink
            >
            <RouterLink
              v-if="allowed('foundation.workflows.read')"
              :to="{ path: '/admin/workflows', query: { institution_id: targetTenant } }"
              >工作流</RouterLink
            >
            <RouterLink
              v-if="allowed('foundation.channels.read')"
              :to="{ path: '/admin/channels', query: { institution_id: targetTenant } }"
              >渠道</RouterLink
            >
          </div>
        </div>
      </div>
      <template v-else-if="section === 'settings' || section === 'agents'">
        <div class="institution-views" aria-label="当前维护分区">
          <ElRadioGroup
            :model-value="view"
            @update:model-value="(value) => selectView(String(value))"
          >
            <ElRadioButton v-for="item in views" :key="item.key" :value="item.key">{{
              item.label
            }}</ElRadioButton>
          </ElRadioGroup>
        </div>
        <ElAlert
          v-if="!validView"
          title="当前身份不能读取此内容，请选择可用的维护入口。"
          type="warning"
          :closable="false"
        />
        <FoundationPage
          v-else-if="section === 'settings'"
          :key="`${targetTenant}:${view}`"
          :area="view === 'members' ? 'access' : 'catalog'"
          :tenant-id="targetTenant"
          embedded
        />
        <AgentsPage v-else-if="view === 'owned'" :tenant-id="targetTenant" embedded />
        <DefinitionsPage v-else-if="view === 'definitions'" :tenant-id="targetTenant" embedded />
        <IncomingGrants
          v-else-if="view === 'incoming'"
          :tenant-id="targetTenant"
          @deploy="selectView('deployments')"
        />
        <GrantsPage
          v-else-if="view === 'grants'"
          :key="targetTenant"
          :tenant-id="self ? undefined : targetTenant"
          embedded
        />
        <DeploymentsPage
          v-else-if="view === 'deployments'"
          :key="targetTenant"
          :tenant-id="targetTenant"
          embedded
        />
      </template>
      <GovernancePage
        v-else-if="section === 'access'"
        :key="targetTenant"
        :tenant-id="targetTenant"
        initial-tab="principals"
        embedded
      />
      <SubInstitutionsPage
        v-else-if="section === 'children'"
        :tenant-id="targetTenant"
        embedded
        @changed="refresh"
        @provisioned="refresh"
      />
      <UsagePage v-else-if="section === 'usage' && self" :tenant-id="targetTenant" embedded />
      <HeadquartersUsagePage
        v-else-if="section === 'usage'"
        :key="targetTenant"
        :tenant-id="targetTenant"
        embedded
      />
    </template>
  </InstitutionWorkspace>
</template>

<style scoped>
.institution-overview {
  display: grid;
  gap: var(--space-4, 16px);
}
.institution-overview__intro h2 {
  margin-top: 0;
}
.institution-overview__actions {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: var(--space-3, 12px);
}
.institution-overview__action {
  display: flex;
  flex-direction: column;
  gap: 8px;
  text-align: left;
  padding: 18px;
  border: 1px solid var(--el-border-color);
  border-radius: var(--el-border-radius-base);
  background: var(--el-bg-color);
  color: var(--el-text-color-primary);
  cursor: pointer;
  font: inherit;
}
.institution-overview__action:hover {
  border-color: var(--el-color-primary);
}
.institution-overview__action:focus-visible {
  outline: 2px solid var(--el-color-primary);
  outline-offset: 2px;
}
.institution-overview__action span {
  color: var(--admin-muted);
  font-size: var(--el-font-size-small);
}
.institution-views {
  margin-bottom: var(--space-4, 16px);
}
.institution-views :deep(.el-radio-group) {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
</style>
