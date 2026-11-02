<script setup lang="ts">
import FeedbackNotice from '@skillforge/shared/feedback/FeedbackNotice.vue'
import { computed, onMounted, onScopeDispose, reactive, ref, useId, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  AdminPage,
  EntitySelect,
  guardFormEnter,
  useDrawerClose,
  useResource,
  useTask
} from '@skillforge/shared/admin'
import FieldTip from '@skillforge/shared/components/FieldTip.vue'
import { vDescribedBy } from '@skillforge/shared/directives/describedBy'
import { catalogDecimalIdentity } from '@skillforge/shared/integration/decimalIdentity'
import { message, confirm } from '@skillforge/shared/feedback'
import { api } from '../../api'
import { createInstitutionTargetOptions } from '../../api/institutionTargetOptions'
import { useAuthStore } from '../../stores/auth'
import { useManagementCommand } from '../../composables/managementCommand'
import type {
  AgentDeployment,
  DeploymentBindings,
  ResourceSlotBinding
} from '../../api/agentDeployments'

const auth = useAuthStore()
const props = defineProps<{ tenantId?: string; embedded?: boolean }>()
const emit = defineEmits<{ changed: [] }>()
const route = useRoute(),
  router = useRouter()
const requestedTarget =
  props.tenantId ?? (typeof route.query.tenant_id === 'string' ? route.query.tenant_id : undefined)
const targetTenant = requestedTarget === auth.user?.tenant_id ? undefined : requestedTarget
const targetInput = ref(targetTenant ?? auth.user?.tenant_id ?? '')
const targets = createInstitutionTargetOptions('tenant.agent_deployments.read', () =>
  requireCurrentPermission('tenant.agent_deployments.read')
)
function selectTarget(value: string) {
  targetInput.value = value
  void router.push({
    path: route.path,
    query: value && value !== auth.user?.tenant_id ? { tenant_id: value } : {}
  })
}
const active = ref(true)
const identity = () =>
  JSON.stringify([
    auth.identityVersion,
    auth.user?.sub,
    auth.user?.tenant_id,
    auth.user?.authorization_version
  ])
const initialIdentity = identity()
onScopeDispose(() => {
  active.value = false
})
/** 等待确认或请求期间身份发生变化后，旧页面不能再发写请求或采用迟到回包。 */
function requireCurrentPermission(permission: string) {
  if (
    !active.value ||
    identity() !== initialIdentity ||
    !auth.user?.permissions.includes(permission)
  )
    throw Error('当前账号没有该操作权限，请联系机构管理员确认权限后重新进入页面')
}
const canCreate = computed(
  () => auth.user?.permissions.includes('tenant.agent_deployments.create') ?? false
)
const canUpdate = computed(
  () => !targetTenant && (auth.user?.permissions.includes('tenant.agent_deployments.bind') ?? false)
)
const canSwitch = computed(
  () => auth.user?.permissions.includes('tenant.agent_deployments.switch_authorization') ?? false
)
const help = useId()
const list = useResource(async (signal) => {
  const value = await api.agentDeployments.list({ signal, silent: true }, targetTenant)
  requireCurrentPermission('tenant.agent_deployments.read')
  return value
})
const grants = useResource(async (signal) => {
  const value = await api.agentDeployments.grants({ signal, silent: true }, targetTenant)
  if (!active.value || identity() !== initialIdentity) throw Error('账号已变化，请重新进入页面')
  return value
})
const action = useTask(),
  write = useManagementCommand()
const open = ref(false)
const editing = ref<AgentDeployment>()
const automaticDefinitionId = ref('')
const automaticName = ref('')
const canSave = computed(() => (editing.value ? canUpdate.value : canCreate.value))
const form = reactive({
  integration_id: '',
  definition_id: '',
  grant_id: '',
  external_agent_ref: '',
  name: ''
})
const availableGrants = computed(() =>
  (grants.data.value?.items ?? []).filter(
    (grant) =>
      grant.definition_id === form.definition_id &&
      grant.status === 'active' &&
      grant.actions.includes('deploy') &&
      grant.actions.includes('execute') &&
      (!grant.expires_at || Date.parse(grant.expires_at) > Date.now())
  )
)
watch([() => form.definition_id, availableGrants], ([definition, available], [previous, old]) => {
  if (editing.value || write.pending.value) return
  if (definition !== previous || (old.length === 1 && available.length > 1)) form.grant_id = ''
  if (!available.some((grant) => grant.grant_id === form.grant_id))
    form.grant_id = available.length === 1 ? available[0]!.grant_id : ''
})
const templates = useResource(async (signal) => {
  const value = await api.agentDeployments.definitions({ signal, silent: true }, targetTenant)
  if (!active.value || identity() !== initialIdentity) throw Error('账号已变化，请重新进入页面')
  return value
})
const selectedTemplate = useResource(async (signal) => {
  // 授权部署定义只从授权部署目录读取，普通模板详情不具有此读取权限。
  const templateId = form.definition_id
  const value =
    templates.data.value ??
    (await api.agentDeployments.definitions({ signal, silent: true }, targetTenant))
  if (!active.value || identity() !== initialIdentity) throw Error('账号已变化，请重新进入页面')
  const template = value.items.find((item) => String(item.id) === templateId)
  if (!template) throw Error('当前机构未获授权使用该公共智能体定义')
  return template
})
type ResourceSelection = AgentDeployment['resource_slots'][number] & {
  definitionId?: string
  deploymentKey?: string
  instanceId?: string
  /** 编辑其他字段不能丢失读回的完整挂载版本、顺序或停用状态。 */
  originalMount?: ResourceSlotBinding['mount']
}
const resources = ref<ResourceSelection[]>([])
const delegates = ref<Array<AgentDeployment['delegation_slots'][number] & { agentId?: string }>>([])
const parameters = ref<
  Array<{
    key: string
    type: 'string' | 'number' | 'boolean'
    value: string
    booleanValue: boolean
  }>
>([])
const baseline = ref('')
const options = useResource(async (signal) => {
  // 槽位决定表单实际需要的目录；无关目录的权限或故障不阻断当前绑定。
  const types = new Set<string>(resources.value.map((slot) => slot.capability_type))
  const [definitions, deployments, instances, agents] = await Promise.all([
    types.size ? api.capabilities.listDefinitions({ status: 'PUBLISHED' }) : [],
    types.has('TOOL') ? api.capabilities.deployments('ACTIVE') : [],
    types.has('WORKFLOW') ? api.workflowDrafts.instances() : { items: [] },
    delegates.value.length ? api.agents.list() : { items: [] }
  ])
  signal.throwIfAborted()
  const published = definitions.filter(
    (value) => types.has(value.capabilityType) && value.defaultVersionId
  )
  for (const definition of published) {
    catalogDecimalIdentity(definition.id, '能力定义标识')
    catalogDecimalIdentity(definition.defaultVersionId, '能力发布版本标识')
  }
  const versions = (
    await Promise.all(
      published
        .filter((value) => value.capabilityType === 'TOOL' || value.capabilityType === 'WORKFLOW')
        .map((value) => api.capabilities.versions(value.id, 'PUBLISHED'))
    )
  ).flat()
  if (!active.value || identity() !== initialIdentity) throw Error('账号已变化，请重新进入页面')
  return {
    definitions: published.map((value) => ({
      ...value,
      id: catalogDecimalIdentity(value.id, '能力定义标识')
    })),
    deployments,
    instances: instances.items,
    agents: agents.items,
    versions: versions.map((value) => ({
      ...value,
      id: catalogDecimalIdentity(value.id, '能力版本标识'),
      definitionId: catalogDecimalIdentity(value.definitionId, '能力定义标识')
    }))
  }
})
const dirtySnapshot = () => {
  const automatic =
    !editing.value &&
    !!automaticDefinitionId.value &&
    form.definition_id === automaticDefinitionId.value
  return JSON.stringify({
    form: automatic
      ? {
          ...form,
          definition_id: '',
          grant_id:
            availableGrants.value.length === 1 &&
            form.grant_id === availableGrants.value[0]?.grant_id
              ? ''
              : form.grant_id,
          name: automaticName.value && form.name === automaticName.value ? '' : form.name
        }
      : form,
    resources: automatic
      ? resources.value.filter((slot) => slot.definitionId || slot.deploymentKey || slot.instanceId)
      : resources.value,
    delegates: automatic ? delegates.value.filter((slot) => slot.agentId) : delegates.value,
    parameters: parameters.value
  })
}
const close = useDrawerClose({
  open: () => open.value,
  busy: () => action.busy.value || !!write.pending.value,
  dirty: () => dirtySnapshot() !== baseline.value,
  close: () => {
    open.value = false
    editing.value = undefined
  }
})

/** 模板声明转为机构绑定字段；服务端就绪原因负责指出当前缺项。 */
function acceptSlots(
  value: Pick<AgentDeployment, 'resource_slots' | 'delegation_slots'>,
  bindings?: DeploymentBindings
) {
  resources.value = value.resource_slots.map((slot) => {
    const mount = bindings?.resource_bindings.find(
      (binding) => binding.slot_key === slot.key
    )?.mount
    return {
      ...slot,
      definitionId: mount?.capability_definition_id,
      deploymentKey: mount?.tool_config?.tool_deployment_id,
      instanceId: mount?.workflow_config?.workflow_instance_id,
      originalMount: mount ? { ...mount } : undefined
    }
  })
  delegates.value = value.delegation_slots.map((slot) => ({
    ...slot,
    agentId: bindings?.delegation_bindings.find((binding) => binding.slot_key === slot.key)
      ?.target_agent_id
  }))
  parameters.value = Object.entries(bindings?.parameters ?? {}).map(([key, value]) => ({
    key,
    type: typeof value as 'string' | 'number' | 'boolean',
    value: String(value),
    booleanValue: value === true
  }))
}
/** 定义切换使旧授权和槽位失效；后发读取才拥有写权。 */
watch(
  () => form.definition_id,
  async (id) => {
    if (editing.value) return
    options.invalidate()
    options.data.value = undefined
    options.error.value = ''
    selectedTemplate.invalidate()
    selectedTemplate.data.value = undefined
    resources.value = []
    delegates.value = []
    parameters.value = []
    if (!id) return
    const template = await selectedTemplate.reload()
    if (template && !editing.value && form.definition_id === id) {
      acceptSlots({
        resource_slots: template.definition.resource_slots ?? [],
        delegation_slots: template.definition.delegation_slots ?? []
      })
      if (!targetTenant) void options.reload()
      if (!form.name) {
        form.name = template.name
        if (id === automaticDefinitionId.value) automaticName.value = template.name
      }
    }
  }
)
/** 模板读取失败后的重试仍更新槽位，避免只更新缓存但表单空白。 */
async function retryTemplate() {
  const id = form.definition_id
  if (!(await templates.reload()) || form.definition_id !== id) return
  const template = await selectedTemplate.reload()
  if (template && !editing.value && form.definition_id === id) {
    acceptSlots({
      resource_slots: template.definition.resource_slots ?? [],
      delegation_slots: template.definition.delegation_slots ?? []
    })
    if (!targetTenant) void options.reload()
  }
}

async function loadTemplates() {
  const catalog = await templates.reload()
  if (
    catalog?.items.length === 1 &&
    open.value &&
    !editing.value &&
    !form.definition_id &&
    !write.pending.value
  ) {
    automaticDefinitionId.value = String(catalog.items[0]!.id)
    form.definition_id = automaticDefinitionId.value
  }
}

/** 新建采用当前机构授权目录；编辑加载真实修订和全部绑定。 */
function create() {
  if (!canCreate.value || action.busy.value) return
  editing.value = undefined
  automaticDefinitionId.value = ''
  automaticName.value = ''
  Object.assign(form, {
    integration_id: '',
    definition_id: '',
    grant_id: '',
    external_agent_ref: crypto.randomUUID(),
    name: ''
  })
  resources.value = []
  delegates.value = []
  parameters.value = []
  templates.data.value = undefined
  selectedTemplate.data.value = undefined
  baseline.value = dirtySnapshot()
  action.error.value = ''
  open.value = true
  options.invalidate()
  options.data.value = undefined
  options.error.value = ''
  void loadTemplates()
  void grants.reload()
}
function edit(row: AgentDeployment) {
  if (!canUpdate.value || action.busy.value) return
  void action.run(async () => {
    const value = await api.agentDeployments.detail(row.deployment_id, { silent: true })
    requireCurrentPermission('tenant.agent_deployments.bind')
    automaticDefinitionId.value = ''
    automaticName.value = ''
    editing.value = value
    selectedTemplate.invalidate()
    selectedTemplate.error.value = ''
    selectedTemplate.data.value = undefined
    Object.assign(form, {
      integration_id: value.integration_id ?? '',
      grant_id: value.grant_id,
      definition_id: value.definition_id,
      external_agent_ref: value.external_agent_ref,
      name: value.definition_name
    })
    acceptSlots(value, value)
    baseline.value = dirtySnapshot()
    open.value = true
    void options.reload()
  })
}
/** 冲突恢复由用户明确丢弃本地编辑后重读修订，取消确认保留当前绑定。 */
function reloadEditing() {
  if (!editing.value || action.busy.value) return
  const deploymentId = editing.value.deployment_id
  void action.run(async () => {
    if (
      dirtySnapshot() !== baseline.value &&
      !(await confirm({
        title: '重新读取机构绑定',
        content: '重新读取将丢弃当前未保存的绑定与参数。确认继续？',
        confirmText: '丢弃并重新读取',
        cancelText: '继续编辑'
      }))
    )
      return
    const value = await api.agentDeployments.detail(deploymentId, { silent: true })
    requireCurrentPermission('tenant.agent_deployments.bind')
    editing.value = value
    acceptSlots(value, value)
    baseline.value = dirtySnapshot()
    void options.reload()
  })
}
/** 部署实例目录与工作流实例仅可绑定同能力定义，不用标识文本猜测关联。 */
function versionDefinition(id: string | null) {
  if (id === null || id === undefined) return undefined
  return options.data.value?.versions.find(
    (value) => value.id === catalogDecimalIdentity(id, '能力版本标识')
  )?.definitionId
}
function compatibleTools(slot: ResourceSelection) {
  return (options.data.value?.deployments ?? []).filter(
    (value) => versionDefinition(value.toolVersionId) === slot.definitionId
  )
}
function compatibleWorkflows(slot: ResourceSelection) {
  return (options.data.value?.instances ?? []).filter(
    (value) =>
      value.status === 'published' &&
      versionDefinition(value.capabilityVersionId) === slot.definitionId
  )
}
function changeDefinition(slot: ResourceSelection) {
  const tools = compatibleTools(slot)
  const workflows = compatibleWorkflows(slot)
  slot.deploymentKey =
    slot.capability_type === 'TOOL' && tools.length === 1 ? tools[0]!.deploymentKey : undefined
  slot.instanceId =
    slot.capability_type === 'WORKFLOW' && workflows.length === 1
      ? workflows[0]!.instanceId
      : undefined
  slot.originalMount = undefined
}
/** 标量参数供公共模板使用，重复键或无效数字不会提交。 */
function bindings(): DeploymentBindings {
  const values: AgentDeployment['parameters'] = {}
  for (const parameter of parameters.value) {
    const key = parameter.key.trim()
    if (!/^[a-z][a-z0-9_]{0,63}$/.test(key))
      throw Error('参数名称须以小写字母开头，仅含小写字母、数字或下划线，最多 64 位')
    if (Object.hasOwn(values, key)) throw Error(`参数名称重复：${key}`)
    if (parameter.type === 'number') {
      if (!parameter.value.trim() || !Number.isFinite(Number(parameter.value)))
        throw Error(`参数“${key}”请输入有效数字`)
      values[key] = Number(parameter.value)
    } else values[key] = parameter.type === 'boolean' ? parameter.booleanValue : parameter.value
  }
  return {
    parameters: values,
    resource_bindings: resources.value
      .filter((slot) => slot.definitionId)
      .map((slot) => {
        const original = slot.originalMount
        const sameTarget =
          original?.capability_type === slot.capability_type &&
          String(original.capability_definition_id) === slot.definitionId &&
          (slot.capability_type === 'TOOL'
            ? original.tool_config?.tool_deployment_id === slot.deploymentKey
            : slot.capability_type === 'WORKFLOW'
              ? original.workflow_config?.workflow_instance_id === slot.instanceId
              : true)
        // 引用未变保留服务端解析的精确版本；变更目标后不携带另一目标的冻结修订。
        const base = {
          capability_type: slot.capability_type,
          capability_definition_id: slot.definitionId!,
          ...(original?.priority === undefined ? {} : { priority: original.priority }),
          ...(original?.status === undefined ? {} : { status: original.status }),
          ...(sameTarget && original?.capability_version_id !== undefined
            ? { capability_version_id: original.capability_version_id }
            : {})
        }
        if (slot.capability_type === 'TOOL') {
          if (!compatibleTools(slot).some((value) => value.deploymentKey === slot.deploymentKey))
            throw Error(`请选择“${slot.label || slot.key}”匹配的工具部署`)
          return {
            slot_key: slot.key,
            mount: {
              ...base,
              tool_config: {
                ...(sameTarget ? original?.tool_config : {}),
                tool_deployment_id: slot.deploymentKey!
              }
            }
          }
        }
        if (slot.capability_type === 'WORKFLOW') {
          if (!compatibleWorkflows(slot).some((value) => value.instanceId === slot.instanceId))
            throw Error(`请选择“${slot.label || slot.key}”匹配的工作流实例`)
          return {
            slot_key: slot.key,
            mount: {
              ...base,
              workflow_config: {
                ...(sameTarget ? original?.workflow_config : {}),
                workflow_instance_id: slot.instanceId!
              }
            }
          }
        }
        return {
          slot_key: slot.key,
          mount: {
            ...base,
            knowledge_config:
              sameTarget && original?.knowledge_config
                ? { ...original.knowledge_config }
                : { mode: 'FOLLOW_ACTIVE' as const, pinned_revision_id: null }
          }
        }
      }),
    delegation_bindings: delegates.value
      .filter((slot) => slot.agentId)
      .map((slot) => ({ slot_key: slot.key, target_agent_id: slot.agentId! }))
  }
}
/** 编辑使用实例CAS修订；失败保留绑定，成功读取刷新与写入反馈分离。 */
function save() {
  if (!canSave.value) return
  void action.run(async () => {
    if (!form.grant_id || !form.definition_id || !form.external_agent_ref.trim())
      throw Error('请选择公共定义、显式授权依据并填写外部智能体标识')
    if (
      !editing.value &&
      (grants.loading.value ||
        grants.error.value ||
        !availableGrants.value.some((grant) => grant.grant_id === form.grant_id))
    )
      throw Error('请先读取并选择当前可用的授权依据')
    if (
      options.loading.value ||
      options.error.value ||
      selectedTemplate.loading.value ||
      selectedTemplate.error.value
    )
      throw Error('请先完成资源目录与模板读取')
    const snapshot = bindings()
    const permission = editing.value
      ? 'tenant.agent_deployments.bind'
      : 'tenant.agent_deployments.create'
    requireCurrentPermission(permission)
    const target = editing.value
    const input = target
      ? {
          ...snapshot,
          deployment_id: target.deployment_id,
          expected_revision: target.deployment_revision
        }
      : {
          ...snapshot,
          ...form,
          integration_id: form.integration_id.trim() || null,
          external_agent_ref: form.external_agent_ref.trim(),
          name: form.name.trim() || undefined,
          ...(targetTenant ? { tenant_id: targetTenant } : {})
        }
    await write.execute(
      (key) =>
        'deployment_id' in input
          ? api.agentDeployments.bindings(input, key)
          : api.agentDeployments.create(input, key),
      (result) => {
        baseline.value = dirtySnapshot()
        open.value = false
        editing.value = undefined
        message.success(
          result.ready
            ? '公共智能体实例已保存，可以开始新任务'
            : '实例已保存，请根据就绪提示补齐绑定'
        )
        emit('changed')
        void list.reload()
      },
      () => requireCurrentPermission(permission)
    )
  })
}
const authorizationOpen = ref(false),
  authorizationTarget = ref<AgentDeployment>(),
  nextGrant = ref(''),
  authorizationBaseline = ref('')
const authorizationClose = useDrawerClose({
  open: () => authorizationOpen.value,
  busy: () => action.busy.value || !!write.pending.value,
  dirty: () => nextGrant.value !== authorizationBaseline.value,
  close: () => {
    authorizationOpen.value = false
  }
})
const alternativeGrants = computed(() =>
  (grants.data.value?.items ?? []).filter(
    (g) =>
      g.definition_id === authorizationTarget.value?.definition_id &&
      g.status === 'active' &&
      g.actions.includes('deploy') &&
      g.actions.includes('execute') &&
      (!g.expires_at || Date.parse(g.expires_at) > Date.now())
  )
)
function authorization(row: AgentDeployment) {
  void action.run(async () => {
    const value = await api.agentDeployments.detail(
      row.deployment_id,
      { silent: true },
      targetTenant
    )
    requireCurrentPermission('tenant.agent_deployments.read')
    authorizationTarget.value = value
    nextGrant.value = ''
    authorizationBaseline.value = ''

    action.error.value = ''
    authorizationOpen.value = true
    void grants.reload()
  })
}
function reloadAuthorization() {
  if (!authorizationTarget.value) return
  const id = authorizationTarget.value.deployment_id
  void action.run(async () => {
    if (
      nextGrant.value !== authorizationBaseline.value &&
      !(await confirm({
        title: '重新读取授权依据',
        content: '重新读取将丢弃当前未保存的授权选择。',
        confirmText: '丢弃并重新读取',
        cancelText: '继续选择'
      }))
    )
      return
    const value = await api.agentDeployments.detail(id, { silent: true }, targetTenant)
    requireCurrentPermission('tenant.agent_deployments.read')
    authorizationTarget.value = value
    nextGrant.value = ''
    authorizationBaseline.value = ''

    void grants.reload()
  })
}
function switchAuthorization() {
  void action.run(async () => {
    if (
      !authorizationTarget.value ||
      !nextGrant.value ||
      grants.loading.value ||
      grants.error.value ||
      !alternativeGrants.value.some((g) => g.grant_id === nextGrant.value)
    )
      throw Error('请从当前可用目录明确选择同源授权')
    const target = authorizationTarget.value,
      selected = nextGrant.value
    const input = {
      deployment_id: target.deployment_id,
      expected_revision: target.deployment_revision,
      grant_id: selected,
      ...(targetTenant ? { tenant_id: targetTenant } : {})
    }
    if (
      !(await confirm({
        title: '切换实例授权依据',
        content: `目标机构 ${target.tenant_id}，实例 ${target.deployment_id}，改用授权 ${selected}。后续新任务采用新依据；已开始的任务保留原授权链，恢复时仍核验该链是否有效。`,
        confirmText: '切换依据',
        cancelText: '取消'
      }))
    )
      return
    requireCurrentPermission('tenant.agent_deployments.switch_authorization')
    if (nextGrant.value !== selected || authorizationTarget.value !== target)
      throw Error('选择已变化，请重新确认')
    await write.execute(
      (key) => api.agentDeployments.authorization(input, key),
      () => {
        authorizationBaseline.value = nextGrant.value
        authorizationOpen.value = false
        message.success('实例授权依据已切换，旧任务保留原冻结依据')
        emit('changed')
        void list.reload()
      },
      () => requireCurrentPermission('tenant.agent_deployments.switch_authorization')
    )
  })
}
onMounted(() => {
  void list.reload()
  void grants.reload()
})
</script>

<template>
  <AdminPage
    :embedded="embedded"
    title="公共智能体实例"
    description="新任务跟随最新发布，已开始任务保留原版本。每个机构实例使用明确的授权依据。"
  >
    <template #extra
      ><ElButton
        v-if="canCreate"
        type="primary"
        :disabled="action.busy.value || !!write.pending.value"
        @click="create"
        >部署公共智能体</ElButton
      ></template
    >
    <FeedbackNotice
      v-if="list.error.value || action.error.value"
      :title="action.error.value || list.error.value"
      type="error"
      :closable="false"
    />
    <ElAlert
      v-if="write.pending.value"
      title="本次部署操作的结果尚未确认，原内容已保留，后续写入已暂停。请点击“核对原请求”确认结果，避免重复部署。"
      type="warning"
      :closable="false"
    /><ElButton
      v-if="write.pending.value"
      :loading="action.busy.value"
      @click="action.run(write.reconcile)"
      >核对原请求</ElButton
    >
    <div class="admin-toolbar admin-card">
      <div v-if="!tenantId" class="admin-filters">
        <EntitySelect
          :model-value="targetInput"
          :load="targets.load"
          :resolve="targets.resolve"
          :clearable="false"
          aria-label="管理机构"
          placeholder="搜索当前可管理的机构名称"
          :disabled="action.busy.value || !!write.pending.value"
          @update:model-value="selectTarget"
        />
      </div>
      <div class="admin-actions">
        <span class="admin-muted" role="status"
          >{{ list.data.value?.items.length ?? 0 }} 个机构实例</span
        ><ElButton @click="list.reload" :loading="list.loading.value">刷新实例</ElButton>
      </div>
    </div>
    <p v-if="targetTenant" class="admin-field-help">
      当前目标机构：{{
        targetTenant
      }}。下级目标需要独立部署管理授权；可先保存为待完善，资源绑定由目标机构维护。
    </p>
    <ElTable
      class="deployment-table"
      :data="list.data.value?.items ?? []"
      v-loading="list.loading.value"
      table-layout="auto"
      empty-text="暂无公共智能体实例"
      row-key="agent_id"
    >
      <ElTableColumn type="expand" width="48"
        ><template #default="{ row }"
          ><ElDescriptions :column="2" border class="deployment-identifiers">
            <ElDescriptionsItem label="机构实例标识">{{ row.agent_id }}</ElDescriptionsItem>
            <ElDescriptionsItem label="部署标识">{{ row.deployment_id }}</ElDescriptionsItem>
            <ElDescriptionsItem label="外部智能体标识">{{
              row.external_agent_ref
            }}</ElDescriptionsItem>
            <ElDescriptionsItem label="接入标识">{{
              row.integration_id || '原生机构部署'
            }}</ElDescriptionsItem>
            <ElDescriptionsItem label="发布策略"
              >新任务跟随最新发布，既有任务保留原版本</ElDescriptionsItem
            >
            <ElDescriptionsItem label="当前授权依据">{{ row.grant_id }}</ElDescriptionsItem>
          </ElDescriptions></template
        ></ElTableColumn
      >
      <ElTableColumn
        prop="definition_name"
        label="公共智能体"
        min-width="190"
        show-overflow-tooltip
      />
      <ElTableColumn prop="grant_id" label="当前授权依据" min-width="170" show-overflow-tooltip />
      <ElTableColumn
        prop="definition_version"
        label="当前发布版本"
        min-width="136"
        class-name="deployment-nowrap"
        show-overflow-tooltip
      />
      <ElTableColumn label="就绪状态" min-width="200"
        ><template #default="{ row }"
          ><ElTag :type="row.ready ? 'success' : 'warning'">{{
            row.ready ? '可运行' : '待完善'
          }}</ElTag>
          <ul v-if="!row.ready" class="readiness-issues">
            <li v-for="issue in row.issues" :key="issue.path + issue.code">{{ issue.message }}</li>
          </ul></template
        ></ElTableColumn
      >
      <ElTableColumn label="操作" width="250" fixed="right" class-name="deployment-actions"
        ><template #default="{ row }"
          ><ElButton
            v-if="canUpdate"
            link
            :disabled="action.busy.value || !!write.pending.value"
            @click="edit(row as AgentDeployment)"
            >配置机构绑定</ElButton
          ><ElButton
            link
            :disabled="action.busy.value || !!write.pending.value"
            @click="authorization(row as AgentDeployment)"
            >授权链{{ canSwitch ? '与切换' : '' }}</ElButton
          ></template
        ></ElTableColumn
      >
    </ElTable>
    <ElDrawer
      v-model="open"
      :title="editing ? '配置机构绑定' : '部署公共智能体'"
      size="760px"
      class="admin-drawer"
      append-to-body
      :before-close="close.requestClose"
      :close-on-click-modal="false"
      :close-on-press-escape="!action.busy.value && !write.pending.value"
      :show-close="!action.busy.value && !write.pending.value"
    >
      <ElAlert
        v-if="write.pending.value"
        title="请求结果尚未确认，请核对原请求。"
        type="warning"
        :closable="false"
      /><ElButton
        v-if="write.pending.value"
        :loading="action.busy.value"
        @click="action.run(write.reconcile)"
        >核对原请求</ElButton
      ><FeedbackNotice
        v-if="action.error.value"
        :title="action.error.value"
        type="error"
        :closable="false"
      />
      <ElButton
        v-if="editing"
        :disabled="action.busy.value || !!write.pending.value"
        @click="reloadEditing"
        >重新读取机构绑定</ElButton
      >
      <FeedbackNotice
        v-if="grants.error.value"
        :title="'授权目录读取失败：' + grants.error.value"
        type="error"
        :closable="false"
      /><ElButton v-if="grants.error.value" @click="grants.reload">重试授权目录</ElButton>
      <FeedbackNotice
        v-if="options.error.value"
        :title="'机构资源目录读取失败：' + options.error.value"
        type="error"
        :closable="false"
      /><ElButton v-if="options.error.value" @click="options.reload">重试机构资源目录</ElButton>
      <ElForm
        :model="form"
        :disabled="action.busy.value || !!write.pending.value"
        label-position="top"
        @submit.prevent="save"
        @keydown.capture="guardFormEnter"
      >
        <ElCollapse
          ><ElCollapseItem title="部署身份与外部系统（自动配置）" name="integration"
            ><ElFormItem label="接入标识"
              ><ElInput
                v-model="form.integration_id"
                :readonly="!!editing"
                :aria-describedby="help + '-integration'"
              /><FieldTip :id="help + '-integration'"
                >原生机构部署留空；接入域在创建后保持稳定。</FieldTip
              ></ElFormItem
            >
            <ElFormItem label="外部智能体标识" required
              ><ElInput
                v-model="form.external_agent_ref"
                :readonly="!!editing"
                maxlength="128"
                v-described-by="help + '-ref'"
                :aria-describedby="help + '-ref'"
              /><FieldTip :id="help + '-ref'"
                >已自动生成稳定身份，通常无需修改；需映射外部业务系统时可在创建前改为实际标识，最多
                128 位。创建后保持不变。</FieldTip
              ></ElFormItem
            >
          </ElCollapseItem></ElCollapse
        >
        <FeedbackNotice
          v-if="templates.error.value"
          :title="templates.error.value"
          type="error"
          :closable="false"
        /><ElButton v-if="templates.error.value" @click="loadTemplates">重试公共模板目录</ElButton>
        <ElFormItem label="公共智能体模板" required
          ><ElSelect
            v-if="!editing"
            v-model="form.definition_id"
            :loading="templates.loading.value"
            :disabled="templates.loading.value"
            filterable
            v-described-by="help + '-template'"
            :aria-describedby="help + '-template'"
            ><ElOption
              v-for="item in templates.data.value?.items ?? []"
              :key="item.id"
              :value="String(item.id)"
              :label="item.name + ' · ' + item.version" /></ElSelect
          ><ElInput
            v-else
            :model-value="editing.definition_name"
            readonly
            v-described-by="help + '-template'"
            :aria-describedby="help + '-template'"
          /><FieldTip :id="help + '-template'"
            >只显示目标机构当前获原授或转授的定义。新任务跟随最新发布；已运行任务保持原版本。</FieldTip
          ></ElFormItem
        >
        <FeedbackNotice
          v-if="selectedTemplate.error.value"
          :title="selectedTemplate.error.value"
          type="error"
          :closable="false"
        /><ElButton v-if="selectedTemplate.error.value" @click="retryTemplate"
          >重试模板读取</ElButton
        >
        <ElFormItem v-if="!editing" label="显式授权依据" required
          ><span
            v-if="availableGrants.length === 1 && form.grant_id === availableGrants[0]?.grant_id"
            class="admin-field-help"
            >已自动选择唯一可用授权</span
          ><ElSelect
            v-else
            v-model="form.grant_id"
            :loading="grants.loading.value"
            :disabled="!form.definition_id"
            :aria-describedby="help + '-grant'"
            ><ElOption
              v-for="grant in availableGrants"
              :key="grant.grant_id"
              :value="grant.grant_id"
              :label="
                grant.definition_name +
                ' · ' +
                grant.grant_id +
                (grant.parent_grant_id ? ' · 转授' : ' · 原授')
              " /></ElSelect
          ><FieldTip :id="help + '-grant'"
            >唯一可用授权自动选择；存在多个授权时需选择。撤权与授权修订由服务端在每次受理时校验。</FieldTip
          ></ElFormItem
        >
        <ElFormItem v-if="!editing" label="实例名称"
          ><ElInput
            v-model="form.name"
            maxlength="128"
            v-described-by="help + '-name'"
            :aria-describedby="help + '-name'"
          /><FieldTip :id="help + '-name'"
            >本机构展示名称，最多 128 位；留空采用公共模板名称。</FieldTip
          ></ElFormItem
        >
        <ElAlert
          v-if="editing && !editing.ready"
          title="请完善以下就绪项，保存后重新检查。"
          type="warning"
          :closable="false"
        />
        <ul v-if="editing && !editing.ready" class="readiness-issues">
          <li v-for="issue in editing.issues" :key="issue.path + issue.code">
            {{ issue.message }}
          </li>
        </ul>
        <h3>机构资源绑定</h3>
        <ElAlert
          v-if="targetTenant"
          title="资源属于目标机构。此处先创建部署，目标机构管理员再配置资源；可运行性由服务端就绪结果说明。"
          type="info"
          :closable="false"
        />
        <div v-if="!targetTenant">
          <p v-if="!resources.length" class="admin-muted">当前模板没有机构资源槽位。</p>
          <ElFormItem
            v-for="slot in resources"
            :key="slot.key"
            :label="slot.label || slot.key"
            required
          >
            <ElSelect
              v-model="slot.definitionId"
              filterable
              clearable
              :loading="options.loading.value"
              @change="changeDefinition(slot)"
              v-described-by="help + slot.key"
              :aria-describedby="help + slot.key"
              ><ElOption
                v-for="item in (options.data.value?.definitions ?? []).filter(
                  (item) => item.capabilityType === slot.capability_type
                )"
                :key="item.id"
                :value="String(item.id)"
                :label="item.name"
            /></ElSelect>
            <ElSelect
              v-if="
                slot.capability_type === 'TOOL' &&
                !(
                  compatibleTools(slot).length === 1 &&
                  slot.deploymentKey === compatibleTools(slot)[0]?.deploymentKey
                )
              "
              v-model="slot.deploymentKey"
              clearable
              :disabled="!slot.definitionId"
              aria-label="选择匹配的工具部署"
              v-described-by="help + slot.key"
              :aria-describedby="help + slot.key"
              ><ElOption
                v-for="item in compatibleTools(slot)"
                :key="item.deploymentKey"
                :value="item.deploymentKey"
                :label="item.deploymentKey"
            /></ElSelect>
            <ElSelect
              v-if="
                slot.capability_type === 'WORKFLOW' &&
                !(
                  compatibleWorkflows(slot).length === 1 &&
                  slot.instanceId === compatibleWorkflows(slot)[0]?.instanceId
                )
              "
              v-model="slot.instanceId"
              clearable
              :disabled="!slot.definitionId"
              aria-label="选择匹配的工作流实例"
              v-described-by="help + slot.key"
              :aria-describedby="help + slot.key"
              ><ElOption
                v-for="item in compatibleWorkflows(slot)"
                :key="item.instanceId"
                :value="item.instanceId"
                :label="item.workflowId"
            /></ElSelect>
            <span
              v-if="
                (slot.capability_type === 'TOOL' &&
                  compatibleTools(slot).length === 1 &&
                  slot.deploymentKey === compatibleTools(slot)[0]?.deploymentKey) ||
                (slot.capability_type === 'WORKFLOW' &&
                  compatibleWorkflows(slot).length === 1 &&
                  slot.instanceId === compatibleWorkflows(slot)[0]?.instanceId)
              "
              class="admin-field-help"
              >已选择唯一可用实例</span
            >
            <FieldTip :id="help + slot.key"
              >{{
                slot.description || '绑定本机构已发布资源。'
              }}
              唯一匹配的工具或工作流实例自动选择；有多个实例时请选择。留空保存后实例将提示待完善。</FieldTip
            >
          </ElFormItem>
          <h3>协作对象</h3>
          <p v-if="!delegates.length" class="admin-muted">当前模板没有协作槽位。</p>
          <ElFormItem
            v-for="slot in delegates"
            :key="slot.key"
            :label="slot.label || slot.key"
            required
            ><ElSelect
              v-model="slot.agentId"
              clearable
              filterable
              v-described-by="help + '-delegate-' + slot.key"
              :aria-describedby="help + '-delegate-' + slot.key"
              ><ElOption
                v-for="item in (options.data.value?.agents ?? []).filter(
                  (item) => String(item.id) !== editing?.agent_id && item.status === 'published'
                )"
                :key="item.id"
                :value="String(item.id)"
                :label="item.name" /></ElSelect
            ><FieldTip :id="help + '-delegate-' + slot.key"
              >{{
                slot.description || '选择本机构已发布的协作智能体。'
              }}
              留空保存后实例将提示待完善。</FieldTip
            ></ElFormItem
          >
          <ElCollapse
            ><ElCollapseItem :title="'业务参数（' + parameters.length + '）'" name="parameters">
              <FieldTip :id="help + '-parameters'"
                >填写模板需要的文本、数字或开关参数，不填写连接凭据。名称以小写字母开头，仅含小写字母、数字和下划线，最多
                64 位。</FieldTip
              >
              <div v-for="(parameter, index) in parameters" :key="index" class="parameter-row">
                <ElInput
                  v-model="parameter.key"
                  aria-label="参数名称"
                  placeholder="参数名称"
                  maxlength="64"
                  v-described-by="help + '-parameters'"
                  :aria-describedby="help + '-parameters'"
                />
                <ElSelect
                  v-model="parameter.type"
                  aria-label="参数类型"
                  v-described-by="help + '-parameters'"
                  :aria-describedby="help + '-parameters'"
                  ><ElOption value="string" label="文本" /><ElOption
                    value="number"
                    label="数字" /><ElOption value="boolean" label="开关"
                /></ElSelect>
                <ElSwitch
                  v-if="parameter.type === 'boolean'"
                  v-model="parameter.booleanValue"
                  aria-label="参数值"
                  v-described-by="help + '-parameters'"
                  :aria-describedby="help + '-parameters'"
                /><ElInput
                  v-else
                  v-model="parameter.value"
                  aria-label="参数值"
                  placeholder="参数值"
                  v-described-by="help + '-parameters'"
                  :aria-describedby="help + '-parameters'"
                />
                <ElButton
                  type="danger"
                  link
                  :aria-label="'删除参数 ' + (parameter.key || index + 1)"
                  @click="parameters.splice(index, 1)"
                  >删除</ElButton
                >
              </div>
              <ElButton
                @click="
                  parameters.push({ key: '', type: 'string', value: '', booleanValue: false })
                "
                >新增参数</ElButton
              >
            </ElCollapseItem></ElCollapse
          >
        </div></ElForm
      ><template #footer
        ><ElButton
          @click="close.requestClose"
          :disabled="action.busy.value || !!write.pending.value"
          >取消</ElButton
        ><ElButton
          v-if="canSave"
          type="primary"
          :loading="action.busy.value"
          :disabled="
            !!write.pending.value ||
            options.loading.value ||
            selectedTemplate.loading.value ||
            !form.definition_id
          "
          @click="save"
          >保存机构实例</ElButton
        ></template
      >
    </ElDrawer>
    <ElDrawer
      v-model="authorizationOpen"
      title="实例授权链与显式依据"
      size="760px"
      class="admin-drawer"
      :before-close="authorizationClose.requestClose"
      :close-on-click-modal="false"
      :show-close="!action.busy.value && !write.pending.value"
      :close-on-press-escape="!action.busy.value && !write.pending.value"
    >
      <ElAlert
        v-if="write.pending.value"
        title="请求结果尚未确认，请核对原请求。"
        type="warning"
        :closable="false"
      /><ElButton
        v-if="write.pending.value"
        :loading="action.busy.value"
        @click="action.run(write.reconcile)"
        >核对原请求</ElButton
      ><FeedbackNotice
        v-if="action.error.value"
        :title="action.error.value"
        type="error"
        :closable="false"
      />
      <template v-if="authorizationTarget">
        <p>
          机构 {{ authorizationTarget.tenant_id }} · 实例 {{ authorizationTarget.deployment_id }} ·
          部署修订 {{ authorizationTarget.deployment_revision }}
        </p>
        <p>
          当前依据 {{ authorizationTarget.grant_id }} · 授权修订
          {{ authorizationTarget.grant_revision }}
        </p>
        <p>
          定义所有方 {{ authorizationTarget.grant_chain?.owner_kind }}
          {{ authorizationTarget.grant_chain?.owner_tenant_id ?? '平台' }}
        </p>
        <ElTable
          :data="authorizationTarget.grant_chain?.hops ?? []"
          table-layout="auto"
          empty-text="暂无可展示的链节点"
        >
          <ElTableColumn prop="grant_id" label="授权" /><ElTableColumn
            prop="revision"
            label="修订"
          /><ElTableColumn prop="grantor_kind" label="授权方类型" /><ElTableColumn
            prop="grantor_tenant_id"
            label="授权方机构"
          /><ElTableColumn prop="target_tenant_id" label="目标机构" /><ElTableColumn
            prop="parent_grant_id"
            label="父授权"
          /><ElTableColumn prop="parent_grant_revision" label="父修订" /><ElTableColumn
            prop="relation_ref"
            label="关系依据"
          /><ElTableColumn prop="relation_revision" label="关系修订" />
        </ElTable>
        <ElAlert
          title="这里展示实例当前授权依据。已开始的任务保留受理时的完整授权链；撤销授权仍会阻止旧任务恢复，系统不会自动换用同一来源的其他授权。"
          type="info"
          :closable="false"
        />
        <ElButton
          :disabled="action.busy.value || !!write.pending.value"
          @click="reloadAuthorization"
          >重新读取授权依据</ElButton
        >
        <FeedbackNotice
          v-if="grants.error.value"
          :title="'授权目录读取失败，当前选择保留：' + grants.error.value"
          type="error"
          :closable="false"
        /><ElButton v-if="grants.error.value" @click="grants.reload">重试授权目录</ElButton>
        <ElForm
          v-if="canSwitch"
          label-position="top"
          :disabled="action.busy.value || !!write.pending.value"
          @submit.prevent="switchAuthorization"
          @keydown.capture="guardFormEnter"
          ><ElFormItem label="新的同源授权依据" required
            ><ElSelect
              v-model="nextGrant"
              :loading="grants.loading.value"
              filterable
              placeholder="明确选择，系统不自动回退"
              ><ElOption
                v-for="grant in alternativeGrants"
                :key="grant.grant_id"
                :value="grant.grant_id"
                :label="
                  grant.grant_id +
                  ' · 修订 ' +
                  grant.revision +
                  ' · ' +
                  (grant.parent_grant_id ? '转授自 ' + grant.parent_grant_id : '原授')
                " /></ElSelect></ElFormItem
        ></ElForm>
      </template>
      <template #footer
        ><ElButton
          :disabled="action.busy.value || !!write.pending.value"
          @click="authorizationClose.requestClose"
          >关闭</ElButton
        ><ElButton
          v-if="canSwitch"
          type="primary"
          :loading="action.busy.value"
          :disabled="
            !!write.pending.value || !nextGrant || grants.loading.value || !!grants.error.value
          "
          @click="switchAuthorization"
          >切换授权依据</ElButton
        ></template
      >
    </ElDrawer>
  </AdminPage>
</template>
<style scoped>
.deployment-table :deep(th.el-table__cell .cell),
.deployment-table :deep(.deployment-nowrap .cell),
.deployment-table :deep(.deployment-actions .cell) {
  white-space: nowrap;
}
.deployment-table :deep(.deployment-actions .cell) {
  overflow: visible;
}
.readiness-issues {
  margin: 8px 0;
  padding-left: 20px;
  color: var(--admin-muted);
}
.deployment-identifiers {
  margin: 16px;
  overflow-wrap: anywhere;
}
.parameter-row {
  display: grid;
  grid-template-columns: minmax(140px, 1fr) 100px minmax(160px, 1fr) 60px;
  gap: 8px;
  align-items: center;
  margin: 8px 0;
}
@media (max-width: 600px) {
  .parameter-row {
    grid-template-columns: minmax(0, 1fr) 100px;
  }
  .parameter-row > :nth-child(3) {
    grid-column: 1;
  }
  .parameter-row > :nth-child(4) {
    justify-self: end;
  }
}
</style>
