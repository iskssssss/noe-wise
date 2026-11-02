<script setup lang="ts">
import { computed, nextTick, onMounted, onScopeDispose, ref, watch } from 'vue'
import { useTask, useUnsavedChanges } from '@skillforge/shared/admin'
import { confirm, message } from '@skillforge/shared/feedback'
import { HttpClientError } from '@skillforge/shared/utils/http'
import { useAuthStore } from '../stores/auth'
import WorkflowInstallationFields from './builder/WorkflowInstallationFields.vue'
import { defaultInstallationSlots } from './builder/workflowInstallationDefaults'
import {
  api,
  type BuilderWorkflowInstance,
  type CapabilityDefinition,
  type CapabilityDeployment,
  type CapabilityMount,
  type CapabilityVersion
} from '../api'
import {
  workflowOnboardingApi,
  type WorkflowOnboardingCommand,
  type WorkflowOnboardingReceipt
} from '../api/workflowOnboarding'
import type { WorkflowDraftListItem, WorkflowInstallationBindings } from '../api/workflowDrafts'
import {
  matchesWorkflowVersion,
  workflowConfigFields,
  workflowInstallationConfig,
  type WorkflowConfigField
} from '../utils/workflow'

interface Slot {
  slot: string
  label: string
  type: string
  capability_id: string
  version_constraint: string
  required?: boolean
  versionId?: string
  deploymentKey?: string
}
const props = defineProps<{
  agentId: string
  mounts: CapabilityMount[]
  canCreate: boolean
  canUpdate: boolean
  canInstall: boolean
  refreshAgent: () => Promise<boolean>
  receiptOnly?: boolean
}>()
const emit = defineEmits<{
  locked: [value: boolean]
  busy: [value: boolean]
  unknown: [value: boolean]
  confirmed: []
}>()
const { busy, error, run } = useTask()
const auth = useAuthStore()
const workflows = ref<WorkflowDraftListItem[]>([])
const instances = ref<BuilderWorkflowInstance[]>([])
const selected = ref('')
const mode = ref<'REUSE' | 'INSTALL' | 'UPDATE'>()
const expectedDefinition = ref('')
const slots = ref<Slot[]>([])
const definitions = ref<CapabilityDefinition[]>([])
const versions = ref<CapabilityVersion[]>([])
const deployments = ref<CapabilityDeployment[]>([])
const fields = ref<WorkflowConfigField[]>([])
const config = ref<Record<string, string | number | boolean | undefined>>({})
const customPrompt = ref('')
const unsupported = ref('')
const ready = ref(false)
const catalogReady = ref(false)
const dependenciesReady = ref(false)
const instancesRefreshRequired = ref(false)
const agentRefreshRequired = ref(false)
const refreshingSelection = ref(false)
const selectionStale = ref(false)
const pending = ref<WorkflowOnboardingCommand>()
type OperationIdentity = Pick<WorkflowOnboardingCommand, 'request_id' | 'agent_id' | 'workflow_id'>
const original = ref<OperationIdentity>()
const storageError = ref('')
const owner = computed(() =>
  auth.user?.tenant_id && auth.user?.sub && props.agentId
    ? JSON.stringify([auth.user.tenant_id, auth.user.sub, props.agentId])
    : ''
)
const storageKey = (value: string) => `skillforge.workflow-onboarding.${value}`
let active = true
onScopeDispose(() => {
  active = false
})
watch(
  owner,
  (value) => {
    original.value = undefined
    pending.value = undefined
    storageError.value = ''
    if (props.receiptOnly) return
    if (!value) {
      storageError.value = '无法确认当前机构和成员，暂不能接入工作流。'
      return
    }
    try {
      const raw = sessionStorage.getItem(storageKey(value))
      if (!raw) return
      const saved: OperationIdentity = JSON.parse(raw)
      if (
        !saved ||
        !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(saved.request_id) ||
        saved.agent_id !== props.agentId ||
        typeof saved.workflow_id !== 'string' ||
        !saved.workflow_id
      )
        throw Error('invalid')
      original.value = saved
    } catch {
      storageError.value = '无法读取原工作流接入记录，请恢复浏览器存储后重新进入。'
    }
  },
  { immediate: true, flush: 'sync' }
)
const receipt = ref<WorkflowOnboardingReceipt>()
const readRequestId = ref('')
const manualUnknown = ref('')
// 组合接入后端按 workflowId_机构ID 定位共享安装，其他实例由高级挂载选择。
const currentInstance = computed(() =>
  instances.value.find(
    (item) =>
      auth.user?.tenant_id &&
      item.workflowId === selected.value &&
      item.instanceId === `${selected.value}_${auth.user.tenant_id}`
  )
)
const currentMount = computed(() =>
  props.mounts.find(
    (mount) =>
      mount.capabilityType === 'WORKFLOW' &&
      mount.workflowConfig.workflowInstanceId === currentInstance.value?.id
  )
)
const allowed = computed(
  () =>
    !!auth.user?.tenant_id &&
    props.canInstall &&
    (currentMount.value ? props.canUpdate : props.canCreate)
)
const locked = computed(
  () =>
    busy.value ||
    refreshingSelection.value ||
    !!pending.value ||
    !!original.value ||
    !!storageError.value ||
    !!manualUnknown.value ||
    instancesRefreshRequired.value ||
    agentRefreshRequired.value
)
watch(error, (value) => {
  if (value) message.error(value)
})
watch(locked, (value) => emit('locked', value), { immediate: true, flush: 'sync' })
watch(busy, (value) => emit('busy', value), { immediate: true, flush: 'sync' })
watch(
  [pending, original, manualUnknown],
  ([value, saved, manual]) => emit('unknown', !!value || !!saved || !!manual),
  {
    immediate: true,
    flush: 'sync'
  }
)
useUnsavedChanges({
  dirty: () => !!pending.value || !!original.value || !!manualUnknown.value,
  busy: () => busy.value,
  leaveMessage: () =>
    pending.value || original.value || manualUnknown.value
      ? `工作流接入结果尚未确认。请记录原请求 ID ${pending.value?.request_id ?? original.value?.request_id ?? manualUnknown.value}，之后只查询原回执。`
      : undefined
})
async function loadCatalog() {
  catalogReady.value = false
  return await run(async () => {
    const previousInstance = JSON.stringify(currentInstance.value ?? null)
    const [list, installed] = await Promise.all([
      api.workflowDrafts.list(),
      api.workflowDrafts.instances()
    ])
    workflows.value = list.items.filter(
      (item) => !!item.activeDefinitionVersionId && item.status !== 'archived'
    )
    instances.value = installed.items
    if (
      selected.value &&
      ready.value &&
      (workflows.value.find((item) => item.workflowId === selected.value)
        ?.activeDefinitionVersionId !== expectedDefinition.value ||
        JSON.stringify(currentInstance.value ?? null) !== previousInstance)
    ) {
      ready.value = false
      selectionStale.value = true
    }
    catalogReady.value = true
    return true
  })
}
async function refreshSelection() {
  if (refreshingSelection.value || busy.value) return
  refreshingSelection.value = true
  try {
    if (!(await props.refreshAgent())) return
    await nextTick()
    if (!(await loadCatalog())) return
    agentRefreshRequired.value = false
    await chooseWorkflow()
  } catch (cause) {
    if (cause instanceof HttpClientError) cause.claimFeedback()
    message.error('读取智能体挂载失败，请重试')
  } finally {
    refreshingSelection.value = false
  }
}
onMounted(async () => {
  if (!props.receiptOnly) {
    await loadCatalog()
    if (active && original.value) await inspect(true)
  }
})
function candidates(slot: Slot) {
  return versions.value.filter((version) => {
    const definition = definitions.value.find((item) => item.id === version.definitionId)
    return (
      definition?.capabilityType === slot.type.toUpperCase() &&
      definition.capabilityKey === slot.capability_id &&
      definition.defaultVersionId === version.id &&
      matchesWorkflowVersion(slot.version_constraint, version.versionKey)
    )
  })
}
async function chooseWorkflow() {
  if (receipt.value?.status === 'REJECTED') receipt.value = undefined
  ready.value = false
  dependenciesReady.value = false
  mode.value = undefined
  unsupported.value = ''
  selectionStale.value = false
  if (!selected.value) return
  const workflowId = selected.value
  await run(async () => {
    const versionId = workflows.value.find(
      (item) => item.workflowId === workflowId
    )?.activeDefinitionVersionId
    if (!versionId) {
      selectionStale.value = true
      return
    }
    const publication = await api.workflowDrafts.publicationDetail(workflowId, versionId)
    if (selected.value !== workflowId) return
    if (
      !publication.current ||
      publication.workflowId !== workflowId ||
      publication.definitionVersionId !== versionId
    ) {
      selectionStale.value = true
      return
    }
    expectedDefinition.value = String(publication.definitionVersionId)
    const nodes = Array.isArray(publication.definition.nodes) ? publication.definition.nodes : []
    const blocked = nodes.find(
      (node) =>
        node &&
        typeof node === 'object' &&
        ['parallel', 'subgraph', 'agentic'].includes(String((node as Record<string, unknown>).type))
    ) as Record<string, unknown> | undefined
    if (blocked) {
      unsupported.value = `当前版本暂不支持执行 ${String(blocked.type)} 节点（${String(blocked.id ?? '未命名')}），不能安装。`
      return
    }
    const meta = publication.definition.meta as
      { capability_requirements?: Slot[]; config_schema?: WorkflowConfigField[] } | undefined
    slots.value = (meta?.capability_requirements ?? []).map((slot) => ({
      ...slot,
      label: slot.label ?? slot.slot
    }))
    fields.value = workflowConfigFields(JSON.stringify(meta?.config_schema ?? []))
    config.value = Object.fromEntries(
      fields.value
        .filter((field) => field.default !== undefined)
        .map((field) => [field.key, field.default])
    )
    const installed = currentInstance.value
    if (installed) {
      for (const field of fields.value) {
        const value = installed.config[field.key]
        if (typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean')
          config.value[field.key] = value
      }
    }
    customPrompt.value = installed?.customPrompt ?? ''
    ready.value = true
    if (!currentInstance.value) {
      mode.value = 'INSTALL'
      await readDependencies()
    }
  })
}
async function readDependencies() {
  const owner = auth.identityVersion,
    workflow = selected.value
  const nextDefinitions = slots.value.length
    ? (await api.capabilities.definitions(undefined, 'PUBLISHED')).filter(
        (definition) =>
          definition.defaultVersionId &&
          slots.value.some(
            (slot) =>
              definition.capabilityType === slot.type.toUpperCase() &&
              definition.capabilityKey === slot.capability_id
          )
      )
    : []
  const nextVersions = (
    await Promise.all(
      nextDefinitions.map((definition) => api.capabilities.versions(definition.id, 'PUBLISHED'))
    )
  ).flat()
  const nextDeployments = slots.value.some((slot) => slot.type === 'tool')
    ? await api.capabilities.deployments('ACTIVE')
    : []
  if (owner !== auth.identityVersion || workflow !== selected.value) return
  definitions.value = nextDefinitions
  versions.value = nextVersions
  deployments.value = nextDeployments
  slots.value = defaultInstallationSlots(slots.value, candidates, deployments.value)
  dependenciesReady.value = true
}
async function changeMode() {
  if (mode.value && mode.value !== 'REUSE' && !dependenciesReady.value) await run(readDependencies)
}
async function bindings(): Promise<WorkflowInstallationBindings> {
  const capabilities = []
  for (const slot of slots.value.filter((item) => item.required !== false || item.versionId)) {
    const version = candidates(slot).find((item) => item.id === slot.versionId)
    if (!version) throw Error(`请选择 ${slot.label} 的已发布能力`)
    const deployment = deployments.value.find(
      (item) =>
        item.deploymentKey === slot.deploymentKey &&
        item.toolVersionId === version.id &&
        ['MCP', 'PLATFORM'].includes(item.providerType)
    )
    if (slot.type === 'tool' && !deployment) throw Error('请选择工具部署')
    let knowledge: number | undefined
    if (slot.type === 'knowledge_source') {
      const source = String(version.descriptor.source_id ?? '')
      if (!source) throw Error('所选知识能力缺少知识库身份')
      const revisions = await api.knowledge.listRevisions(source)
      knowledge = revisions.items
        .filter(
          (item) => item.status === 'published' && String(item.capability_version_id) === version.id
        )
        .reduce((latest, item) => Math.max(latest, item.revision), 0)
      if (!knowledge) throw Error('所选知识能力没有匹配的已发布修订')
    }
    capabilities.push({
      slot: slot.slot,
      capability: {
        type: slot.type.toUpperCase() as 'TOOL' | 'KNOWLEDGE_SOURCE',
        capabilityId: version.definitionId,
        versionId: version.id
      },
      ...(deployment && slot.type === 'tool'
        ? {
            tool_deployment_id: deployment.deploymentKey,
            tool_deployment_revision: deployment.revision
          }
        : {}),
      ...(slot.type === 'knowledge_source' ? { knowledge_revision: knowledge } : {})
    })
  }
  return { capabilities, workflows: [], agents: [] }
}
function matches(value: WorkflowOnboardingReceipt | undefined, command: OperationIdentity) {
  if (
    !value ||
    value.request_id !== command.request_id ||
    value.agent_id !== command.agent_id ||
    value.workflow_id !== command.workflow_id
  )
    return false
  if (value.status === 'PROCESSING') return true
  if (value.status === 'REJECTED')
    return typeof value.error_message === 'string' && !!value.error_message
  return (
    value.status === 'SUCCEEDED' &&
    typeof value.instance_id === 'string' &&
    !!value.instance_id &&
    Number.isSafeInteger(value.installation_revision) &&
    typeof value.mount_id === 'string' &&
    !!value.mount_id &&
    Number.isSafeInteger(value.mount_revision)
  )
}
async function accept(
  value: WorkflowOnboardingReceipt | undefined,
  command: OperationIdentity,
  subject: string,
  identity: number,
  automatic = false
) {
  if (!active || owner.value !== subject || auth.identityVersion !== identity) return
  if (!matches(value, command) || value?.status === 'PROCESSING') {
    if (!automatic) message.warning('原操作结果尚未确认，请稍后核对原回执')
    return
  }
  if (original.value?.request_id === command.request_id) {
    try {
      sessionStorage.removeItem(storageKey(subject))
    } catch {
      storageError.value = '无法清除原工作流接入记录，请恢复浏览器存储后重新核对。'
      return
    }
    original.value = undefined
    storageError.value = ''
  }
  pending.value = undefined
  receipt.value = value
  if (value!.status === 'REJECTED') {
    if (!props.receiptOnly) agentRefreshRequired.value = true
    message.error(`接入未完成：${value!.error_message}`)
  } else {
    message.success('工作流已接入智能体草稿')
    emit('confirmed')
    if (!props.receiptOnly) {
      instancesRefreshRequired.value = true
      ready.value = false
      try {
        await readConfirmedInstances()
      } catch (cause) {
        if (cause instanceof HttpClientError) cause.claimFeedback()
        message.warning('接入已确认，但安装列表未刷新，请重新读取，无需重复提交')
      }
    }
  }
}
async function readConfirmedInstances() {
  const items = (await api.workflowDrafts.instances()).items
  if (
    !receipt.value?.instance_id ||
    !items.some(
      (item) =>
        item.id === receipt.value!.instance_id && item.workflowId === receipt.value!.workflow_id
    )
  )
    throw Error('已确认的安装实例尚未出现在当前授权列表')
  instances.value = items
  instancesRefreshRequired.value = false
}
async function refreshInstances() {
  await run(readConfirmedInstances)
}
async function submit() {
  if (locked.value || !catalogReady.value || !ready.value || !mode.value || !allowed.value) return
  const subject = owner.value
  const identity = auth.identityVersion
  await run(async () => {
    const instance = currentInstance.value
    const revision = instance?.installationRevision ? Number(instance.installationRevision) : 0
    if (
      !Number.isSafeInteger(revision) ||
      revision < 0 ||
      (mode.value !== 'INSTALL' && revision < 1)
    )
      throw Error('安装修订不可用，请重新读取')
    const mount = currentMount.value
    if (
      !(await confirm({
        title: '接入工作流',
        confirmText: '确认接入',
        content:
          mode.value === 'UPDATE'
            ? '将更新该工作流的共享安装并接入当前智能体草稿；其他使用此安装的对象也会受到影响。发布仍需单独操作。'
            : mount
              ? '将更新当前智能体已有的工作流挂载；发布仍需单独操作。'
              : '将工作流接入当前智能体草稿，发布后生效。'
      }))
    )
      return
    const command: WorkflowOnboardingCommand = {
      request_id: crypto.randomUUID(),
      agent_id: props.agentId,
      workflow_id: selected.value,
      expected_definition_version_id: expectedDefinition.value,
      mode: mode.value!,
      expected_installation_revision: revision,
      config: mode.value === 'REUSE' ? {} : workflowInstallationConfig(fields.value, config.value),
      custom_prompt: mode.value === 'REUSE' ? '' : customPrompt.value,
      bindings:
        mode.value === 'REUSE' ? { capabilities: [], workflows: [], agents: [] } : await bindings(),
      ...(mount ? { mount_id: mount.mountId, expected_mount_revision: mount.revision } : {})
    }
    if (!active || owner.value !== subject || auth.identityVersion !== identity) return
    const operation = {
      request_id: command.request_id,
      agent_id: command.agent_id,
      workflow_id: command.workflow_id
    }
    try {
      sessionStorage.setItem(storageKey(subject), JSON.stringify(operation))
    } catch {
      storageError.value = '无法保存原工作流接入记录，本次未发送。请恢复浏览器存储后重新进入。'
      return
    }
    original.value = operation
    pending.value = command
    await accept(await workflowOnboardingApi.submit(command), operation, subject, identity)
  })
}
async function inspect(automatic = false) {
  const command = original.value ?? pending.value
  const subject = owner.value
  const identity = auth.identityVersion
  const requestId = command?.request_id ?? manualUnknown.value ?? readRequestId.value.trim()
  const key = (requestId || readRequestId.value.trim()).toLowerCase()
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(key)) {
    message.warning('请输入原请求的 UUID')
    return
  }
  if (!command) manualUnknown.value = key
  await run(async () => {
    const value = await workflowOnboardingApi.result(key)
    if (!active || owner.value !== subject || auth.identityVersion !== identity) return
    if (command) {
      await accept(value, command, subject, identity, automatic)
      return
    }
    if (
      !value ||
      value.request_id !== key ||
      value.agent_id !== props.agentId ||
      !value.workflow_id ||
      (value.status !== 'PROCESSING' && value.status !== 'REJECTED' && value.status !== 'SUCCEEDED')
    ) {
      message.warning('暂未查到匹配的原回执，结果仍未确认')
      return
    }
    if (value.status === 'PROCESSING') {
      message.warning('原操作结果尚未确认，请稍后再次核对')
      return
    }
    const operation: OperationIdentity = {
      request_id: key,
      agent_id: value.agent_id,
      workflow_id: value.workflow_id
    }
    if (!matches(value, operation)) {
      message.warning('原回执不完整，结果仍未确认')
      return
    }
    manualUnknown.value = ''
    readRequestId.value = ''
    await accept(value, operation, subject, identity)
  })
}
</script>

<template>
  <section class="admin-card" aria-label="工作流接入">
    <p v-if="!receiptOnly">选择已发布工作流，完成安装后接入当前智能体草稿；发布仍需单独操作。</p>
    <p v-if="pending || original || manualUnknown" role="status">
      操作结果尚未确认，原请求 ID：{{
        pending?.request_id || original?.request_id || manualUnknown
      }}。未查到回执不代表失败，请勿重复提交。
    </p>
    <ElInput
      v-if="!pending && !original && !manualUnknown"
      v-model="readRequestId"
      placeholder="原请求 UUID"
      aria-label="原请求 UUID"
    />
    <p v-if="storageError" role="status">{{ storageError }}</p>
    <ElButton :disabled="busy" @click="inspect()">核对原操作</ElButton>
    <p v-if="receipt?.status === 'SUCCEEDED'" role="status">
      接入已确认，安装修订 {{ receipt.installation_revision }} · 挂载 {{ receipt.mount_id }}。
    </p>
    <ElButton v-if="!receiptOnly && !catalogReady" :disabled="busy" @click="loadCatalog"
      >重新读取工作流目录</ElButton
    >
    <p v-if="agentRefreshRequired" role="status">
      请重新读取智能体挂载和工作流安装状态，读取完成前不能提交。
    </p>
    <ElButton
      v-if="!receiptOnly && receipt?.status === 'REJECTED'"
      :disabled="busy || refreshingSelection || !!pending || !!original || !!manualUnknown"
      @click="refreshSelection"
      >重新读取并选择工作流</ElButton
    >
    <p v-if="instancesRefreshRequired" role="status">
      接入已确认，安装列表尚未刷新。<ElButton :disabled="busy" @click="refreshInstances"
        >重新读取安装列表</ElButton
      >
    </p>
    <ElButton
      v-if="receipt?.status === 'SUCCEEDED' && !receiptOnly && !instancesRefreshRequired && !ready"
      :disabled="busy"
      @click="chooseWorkflow"
      >重新选择当前工作流</ElButton
    >
    <ElForm v-if="!receiptOnly" label-position="top" :disabled="locked" @submit.prevent>
      <ElFormItem label="已发布工作流">
        <ElSelect v-model="selected" :disabled="locked" filterable @change="chooseWorkflow">
          <ElOption
            v-for="workflow in workflows"
            :key="workflow.workflowId"
            :value="workflow.workflowId"
            :label="workflow.name || workflow.workflowId"
          />
        </ElSelect>
      </ElFormItem>
      <p v-if="catalogReady && !workflows.length" class="admin-field-help" role="status">
        当前没有可接入的已发布工作流。
        <template v-if="canInstall">可前往工作流创建并发布，然后返回刷新。</template>
        <template v-else>请联系管理员创建并发布工作流。</template>
        <RouterLink to="/admin/workflows" target="_blank" rel="noopener noreferrer"
          >前往工作流（新标签页）</RouterLink
        >
        <ElButton link :disabled="locked" @click="loadCatalog">刷新工作流选项</ElButton>
      </p>
      <p v-if="catalogReady && workflows.length" class="admin-field-help">
        <RouterLink to="/admin/workflows" target="_blank" rel="noopener noreferrer"
          >前往工作流（新标签页）</RouterLink
        >
        <ElButton link :disabled="locked" @click="loadCatalog">刷新工作流选项</ElButton>
      </p>
      <p v-if="selectionStale" class="admin-field-help" role="status">
        所选工作流的发布版本或共享安装已变化，请重新读取后确认配置。
      </p>
      <p v-if="unsupported" role="status">{{ unsupported }}</p>
      <ElButton
        v-if="
          selected &&
          workflows.some((item) => item.workflowId === selected) &&
          !ready &&
          !unsupported &&
          catalogReady &&
          !instancesRefreshRequired &&
          receipt?.status !== 'SUCCEEDED'
        "
        :disabled="busy"
        @click="selectionStale ? refreshSelection() : chooseWorkflow()"
        >重新读取当前工作流</ElButton
      >
      <template v-if="ready">
        <p v-if="currentInstance">
          已有共享安装：{{ currentInstance.instanceId }} · 修订
          {{ currentInstance.installationRevision }}。
        </p>
        <details v-if="currentInstance">
          <summary>查看当前安装配置</summary>
          <pre>{{ JSON.stringify(currentInstance.config, null, 2) }}</pre>
          <p>当前自定义提示：{{ currentInstance.customPrompt || '无' }}</p>
        </details>
        <ElFormItem v-if="currentInstance" label="安装方式">
          <ElRadioGroup v-model="mode" :disabled="locked" @change="changeMode">
            <ElRadio value="REUSE">沿用当前安装</ElRadio
            ><ElRadio value="UPDATE">更新共享安装</ElRadio>
          </ElRadioGroup>
        </ElFormItem>
        <p v-if="mode === 'UPDATE'">
          更新将影响使用该共享安装的对象。请重新选择所有需要保留的能力槽；未选择的可选槽不会保留。
        </p>
        <p v-if="currentMount">该智能体已挂载此工作流，确认接入将更新该挂载。</p>
        <p v-if="!auth.user?.tenant_id" role="status">机构身份不可用，请重新登录后接入工作流。</p>
        <p v-else-if="!allowed" role="status">
          当前账号缺少工作流安装或{{
            currentMount ? '更新' : '创建'
          }}智能体能力挂载的权限，可以核对原回执。
        </p>
      </template>
    </ElForm>
    <WorkflowInstallationFields
      v-if="ready && mode && mode !== 'REUSE' && dependenciesReady"
      v-model:slots="slots"
      v-model:config="config"
      v-model:custom-prompt="customPrompt"
      :fields="fields"
      :deployments="deployments"
      :candidates="candidates"
      :disabled="locked"
    />
    <ElButton
      v-if="ready && mode && mode !== 'REUSE' && dependenciesReady && slots.length"
      :disabled="locked"
      @click="run(readDependencies)"
      >刷新依赖选项</ElButton
    >
    <ElButton
      v-if="ready && mode && mode !== 'REUSE' && !dependenciesReady"
      :disabled="busy"
      @click="changeMode"
      >重新读取安装选项</ElButton
    >
    <ElButton
      v-if="ready"
      type="primary"
      :disabled="
        locked || !catalogReady || !mode || !allowed || (mode !== 'REUSE' && !dependenciesReady)
      "
      :loading="busy"
      @click="submit"
      >{{ currentMount ? '更新接入' : '接入智能体' }}</ElButton
    >
  </section>
</template>
