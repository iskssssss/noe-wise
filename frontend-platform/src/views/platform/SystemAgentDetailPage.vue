<script setup lang="ts">
import FeedbackNotice from '@skillforge/shared/feedback/FeedbackNotice.vue'
import { useActionPermissions } from '../../composables/actionPermissions'
import { vDescribedBy } from '@skillforge/shared/directives/describedBy'

import FieldTip from '@skillforge/shared/components/FieldTip.vue'
import { memoryExtractionDebugExample } from './systemAgentDebugInput'

import { guardFormEnter } from '@skillforge/shared/admin'
import { message, confirm } from '@skillforge/shared/feedback'
import { HttpClientError } from '@skillforge/shared/utils/http'
import { computed, onMounted, ref, useId, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { formatDateTime } from '@skillforge/shared/utils/datetime'
import { type FormInstance } from 'element-plus'
import { useAuthStore } from '../../stores/auth'
import SystemAgentToolsPage from './SystemAgentToolsPage.vue'
import {
  AdminPage,
  statusLabel,
  useTask,
  useResource,
  useUnsavedChanges,
  AgentConfigFields
} from '@skillforge/shared/admin'
import {
  api,
  type ModelView,
  type SystemAgentView,
  type SystemAgentConfig,
  type SystemAgentVersion,
  type SystemAgentRunView,
  type SystemAgentMountOptions,
  type SystemAgentToolDeploymentView
} from '../../api'
const { allowed, checked } = useActionPermissions()
const auth = useAuthStore()
type AgentMount = SystemAgentConfig['mounts'][number]
const toolSetup = ref<{
  mount: AgentMount
  form: SystemAgentConfig
  toolId: string
  initialDeploymentId: string | null
  agentRevision: number
  identityVersion: number
  published?: SystemAgentToolDeploymentView
  candidate?: SystemAgentMountOptions['deployments'][number]
  error?: string
}>()
const toolSetupMounted = ref(false)
const toolSetupPage = ref<InstanceType<typeof SystemAgentToolsPage>>()
const mountCatalog = useResource((signal) =>
  api.systemAgents.mountOptions({ signal, silent: true })
)
const options = computed<SystemAgentMountOptions>(
  () => mountCatalog.data.value ?? { skills: [], tools: [], deployments: [] }
)
function agentEditLocked() {
  return (
    !allowed('foundation.system_agents.update') ||
    action.busy.value ||
    !!debugKey.value ||
    !!storageError.value ||
    item.value?.status === 'archived'
  )
}
function setupCurrent(target: NonNullable<typeof toolSetup.value>) {
  return (
    auth.identityVersion === target.identityVersion &&
    item.value?.id === id &&
    item.value.revision === target.agentRevision &&
    form.value === target.form &&
    form.value.mounts.includes(target.mount) &&
    target.mount.capability_type === 'TOOL' &&
    target.mount.capability_definition_id === target.toolId &&
    allowed('foundation.system_agents.read') &&
    !agentEditLocked()
  )
}
function startToolSetup(mount: AgentMount) {
  if (
    !form.value ||
    !item.value ||
    agentEditLocked() ||
    !mount.capability_definition_id ||
    toolSetupMounted.value ||
    !allowed('foundation.system_agents.read') ||
    !allowed('foundation.system_agents.update') ||
    !allowed('foundation.system_agent_tools.read') ||
    !allowed('foundation.system_agent_tools.create') ||
    !allowed('foundation.capabilities.read') ||
    !allowed('foundation.system_agent_tools.publish')
  )
    return
  toolSetup.value = {
    mount,
    form: form.value,
    toolId: mount.capability_definition_id,
    initialDeploymentId: mount.tool_deployment_id,
    agentRevision: item.value.revision,
    identityVersion: auth.identityVersion
  }
  toolSetupMounted.value = true
}
function selectCapability(mount: AgentMount) {
  const candidates =
    mount.capability_type === 'TOOL'
      ? options.value.deployments.filter(
          (item) => item.tool_definition_id === mount.capability_definition_id
        )
      : []
  mount.tool_deployment_id = candidates.length === 1 ? candidates[0]!.id : null
}
async function refreshToolCandidate() {
  const target = toolSetup.value
  if (!target?.published || !setupCurrent(target)) return
  const result = await mountCatalog.reload()
  if (toolSetup.value !== target || !setupCurrent(target)) return
  if (!result) {
    target.error = '可挂载目录未更新，请重新读取；已发布部署回执已保留。'
    return
  }
  target.candidate = result.deployments.find(
    (value) =>
      value.id === target.published!.id &&
      value.tool_definition_id === target.toolId &&
      value.environment === target.published!.environment
  )
  target.error = target.candidate ? '' : '已发布部署当前不在可挂载目录中；请检查启用状态后重读。'
  if (target.candidate && target.mount.tool_deployment_id === target.initialDeploymentId)
    selectToolCandidate(target)
}
function refreshMountOptions() {
  if (!allowed('foundation.system_agents.read')) return
  const target = toolSetup.value
  if (target?.published && setupCurrent(target)) void refreshToolCandidate()
  else void mountCatalog.reload()
}
function toolPublished(value: SystemAgentToolDeploymentView) {
  const target = toolSetup.value
  if (!target || !setupCurrent(target) || value.tool_definition_id !== target.toolId) return
  target.published = value
  target.candidate = undefined
  void refreshToolCandidate()
}
function selectToolCandidate(target: NonNullable<typeof toolSetup.value>) {
  if (
    !setupCurrent(target) ||
    !target.candidate ||
    !target.published ||
    !options.value.deployments.some(
      (value) => value.id === target.candidate!.id && value.tool_definition_id === target.toolId
    )
  )
    return
  target.mount.tool_deployment_id = target.candidate.id
  target.candidate = undefined
  target.error = ''
  message.success('已选择工具部署；请保存智能体草稿后再发布')
}
const formRef = ref<FormInstance>()
const modelOptions = useResource((signal) => api.admin.models.list({ signal, silent: true }))
const models = computed<ModelView[]>(() => modelOptions.data.value ?? [])
const readErrors = computed(() =>
  [
    ...new Set(
      [load.error.value, modelOptions.error.value, mountCatalog.error.value].filter(Boolean)
    )
  ].join('；')
)
function refreshModels() {
  if (allowed('foundation.models.read')) void modelOptions.reload()
}
const id = String(useRoute().params.id),
  router = useRouter(),
  load = useTask(),
  action = useTask(),
  item = ref<SystemAgentView>(),
  form = ref<SystemAgentConfig>(),
  baseline = ref(''),
  versions = ref<SystemAgentVersion[]>([]),
  runs = ref<SystemAgentRunView[]>([]),
  selectedRun = ref<SystemAgentRunView>(),
  lookupKey = ref(''),
  input = ref(''),
  source = ref<'DRAFT' | 'PUBLISHED'>('DRAFT'),
  pendingDebug = ref<Parameters<typeof api.systemAgents.debug>[0]>(),
  debugKey = ref(''),
  storageError = ref('')
const storageKey = (actor: string) => `skillforge.system-agent-debug:${JSON.stringify([actor, id])}`
function loadDebugKey() {
  try {
    debugKey.value = sessionStorage.getItem(storageKey(auth.user?.sub ?? '')) ?? ''
    storageError.value = ''
  } catch {
    debugKey.value = ''
    storageError.value = '无法读取原调试记录，请恢复浏览器存储后重新进入页面。'
  }
}
function clearDebugKey(actor: string, identity: number) {
  try {
    sessionStorage.removeItem(storageKey(actor))
    if (identity === auth.identityVersion) {
      pendingDebug.value = undefined
      debugKey.value = ''
      storageError.value = ''
    }
  } catch {
    if (identity === auth.identityVersion)
      storageError.value = '原调试已确认，但无法清理本地记录；请恢复浏览器存储后重新核对。'
  }
}
loadDebugKey()
watch(
  () => auth.identityVersion,
  () => {
    pendingDebug.value = undefined
    input.value = ''
    lookupKey.value = ''
    item.value = undefined
    form.value = undefined
    baseline.value = ''
    modelOptions.invalidate()
    modelOptions.data.value = undefined
    modelOptions.error.value = ''
    mountCatalog.invalidate()
    mountCatalog.data.value = undefined
    mountCatalog.error.value = ''
    versions.value = []
    runs.value = []
    selectedRun.value = undefined
    toolSetup.value = undefined
    toolSetupMounted.value = false
    loadDebugKey()
    reload(true)
  },
  { flush: 'sync' }
)
const committedNavigation = ref(false)
const mountKeys = new WeakMap<object, string>()
let mountSequence = 0
function mountKey(mount: object) {
  if (!mountKeys.has(mount)) mountKeys.set(mount, 'mount-' + ++mountSequence)
  return mountKeys.get(mount)!
}
const dirty = computed(() => !!form.value && JSON.stringify(form.value) !== baseline.value)
const unpublished = computed(
  () => !!item.value && JSON.stringify(item.value.draft) !== JSON.stringify(item.value.published)
)
useUnsavedChanges({
  dirty: () => dirty.value || !!debugKey.value || !!storageError.value,
  busy: () => action.busy.value && !committedNavigation.value,
  leaveMessage: () =>
    debugKey.value
      ? `调试请求 ${debugKey.value} 的结果尚未确认。离开不会撤销原执行，请保留操作标识并到运行记录核对，不要当作失败重新运行。`
      : undefined
})
function accept(value: SystemAgentView) {
  item.value = value
  form.value = structuredClone(value.draft)
  baseline.value = JSON.stringify(form.value)
}
let reloadAfterIdentity = false
function reload(identityChanged = false) {
  if (load.busy.value) {
    if (identityChanged) reloadAfterIdentity = true
    return
  }
  if ((!identityChanged && action.busy.value) || dirty.value) return
  const identity = auth.identityVersion
  void load
    .run(async () => {
      const results = await Promise.allSettled([
        api.systemAgents.detail(id, { silent: true }),
        allowed('foundation.models.read')
          ? modelOptions.reload()
          : Promise.resolve([] as ModelView[]),
        mountCatalog.reload(),
        api.systemAgents.versions(id, { silent: true }),
        api.systemAgents.runs(id, { silent: true })
      ])
      if (identity !== auth.identityVersion) return
      const [detail, , , history, runList] = results
      if (detail.status === 'fulfilled') accept(detail.value)
      if (history.status === 'fulfilled') versions.value = history.value
      if (runList.status === 'fulfilled') runs.value = runList.value
      const failures = results.flatMap((result) => {
        if (result.status === 'fulfilled') return []
        if (result.reason instanceof HttpClientError) result.reason.claimFeedback()
        return [result.reason instanceof Error ? result.reason.message : '资料读取失败']
      })
      if (failures.length) throw new Error([...new Set(failures)].join('；'))
    })
    .then(() => {
      if (reloadAfterIdentity) {
        reloadAfterIdentity = false
        reload(true)
      }
    })
}
function save(continueToPublish = false) {
  if (
    debugKey.value ||
    storageError.value ||
    !allowed('foundation.system_agents.update') ||
    item.value?.status === 'archived'
  )
    return
  void action.run(async () => {
    await formRef.value?.validate()
    for (const mount of form.value?.mounts ?? []) {
      const catalog = mount.capability_type === 'SKILL' ? options.value.skills : options.value.tools
      if (!catalog.some((option) => option.id === mount.capability_definition_id))
        throw new Error('请选择有效的已发布能力')
      if (
        mount.capability_type === 'TOOL' &&
        !options.value.deployments.some(
          (deployment) =>
            deployment.id === mount.tool_deployment_id &&
            deployment.tool_definition_id === mount.capability_definition_id
        )
      )
        throw new Error('工具部署必须属于所选工具')
    }
    const config = form.value
    if (!config?.name.trim() || !config.system_prompt.trim())
      throw new Error('名称和系统提示词不能为空')
    accept(
      await checked('foundation.system_agents.update', () =>
        api.systemAgents.update({
          agent_id: id,
          expected_revision: item.value!.revision,
          config
        })
      )
    )
    message.success('系统智能体草稿已保存，发布后用于新的正式执行')
    if (continueToPublish && allowed('foundation.system_agents.publish'))
      await changeSaved('publish')
  })
}
function change(actionName: 'publish' | 'disable' | 'enable' | 'archive' | 'remove') {
  if (debugKey.value || storageError.value) return
  if (
    !allowed(
      actionName === 'publish'
        ? 'foundation.system_agents.publish'
        : actionName === 'remove'
          ? 'foundation.system_agents.delete'
          : 'foundation.system_agents.update'
    )
  )
    return
  void action.run(() => changeSaved(actionName))
}
async function changeSaved(actionName: 'publish' | 'disable' | 'enable' | 'archive' | 'remove') {
  if (dirty.value) throw new Error('请先保存当前修改')
  const target = item.value!
  if (target.status === 'archived' && actionName !== 'remove')
    throw new Error('已归档智能体仅供查看')
  if (
    !(await confirm({
      title: '系统智能体',
      content: `确认${{ publish: '发布', disable: '停用', enable: '启用', archive: '归档', remove: '删除' }[actionName]}“${target.draft.name}”（${target.agent_key}，修订 ${target.revision}）？${
        {
          publish: '新执行将采用当前草稿，进行中的执行保持原配置。',
          disable: '停用后，当前发布版本不能接受新执行；草稿内容保留。',
          enable: '启用当前发布版本；未发布的草稿修改不会随之生效。',
          archive: '归档后不能再编辑、发布或调试，历史配置仍可查看。',
          remove: '删除后无法通过此页面恢复，请确认选中的智能体。'
        }[actionName]
      }`,
      confirmText: {
        publish: '发布',
        disable: '停用',
        enable: '启用',
        archive: '归档',
        remove: '删除'
      }[actionName],
      cancelText: '取消',
      danger: ['disable', 'archive', 'remove'].includes(actionName)
    }))
  )
    return
  if (item.value?.revision !== target.revision || dirty.value)
    throw new Error('确认期间配置已变化，请重新确认')
  const result = await checked(
    actionName === 'publish'
      ? 'foundation.system_agents.publish'
      : actionName === 'remove'
        ? 'foundation.system_agents.delete'
        : 'foundation.system_agents.update',
    () =>
      api.systemAgents[actionName]({
        agent_id: id,
        expected_revision: target.revision
      })
  )
  if (actionName === 'remove') {
    message.success('系统智能体已删除')
    baseline.value = JSON.stringify(form.value)
    committedNavigation.value = true
    try {
      await router.replace('/platform/system-agents')
    } catch {
      message.warning('系统智能体已删除，但未能返回列表；请使用“返回列表”，不要重复删除')
    } finally {
      committedNavigation.value = false
    }
  } else if (result) {
    accept(result)
    message.success(
      {
        publish: '系统智能体已发布，新的正式执行采用此版本',
        disable: '系统智能体已停用，当前发布版本不再接受新执行',
        enable: '系统智能体已启用，继续使用当前发布版本',
        archive: '系统智能体已归档，历史配置可继续查看'
      }[actionName]
    )
    void load.run(async () => {
      versions.value = await api.systemAgents.versions(id)
    })
  }
}
function fillMemoryDebugExample() {
  if (!debugKey.value && !storageError.value) input.value = memoryExtractionDebugExample
}
/** 核对只发送已冻结的原命令，服务端按原幂等键返回同一调试执行。 */
async function dispatchDebug(reconciling = false) {
  const command = pendingDebug.value
  if (!command) return
  const identity = auth.identityVersion
  const actor = auth.user?.sub ?? ''
  let result: SystemAgentRunView
  let dispatched = false
  try {
    result = await checked('foundation.system_agents.run', () => {
      dispatched = true
      return api.systemAgents.debug(command)
    })
  } catch (reason) {
    if (identity !== auth.identityVersion) {
      if (reason instanceof HttpClientError) reason.claimFeedback()
      return
    }
    if (!reconciling && !dispatched) clearDebugKey(actor, identity)
    if (
      reason instanceof HttpClientError &&
      !reconciling &&
      (reason.status === 400 || reason.status === 422) &&
      reason.code === 'VALIDATION_ERROR'
    )
      clearDebugKey(actor, identity)
    throw reason
  }
  if (identity !== auth.identityVersion) return
  runs.value = [result, ...runs.value.filter((run) => run.id !== result.id)]
  if (
    result.status === 'COMPLETED' ||
    (result.status === 'FAILED' && result.error_code !== 'system_agent_outcome_unknown')
  ) {
    clearDebugKey(actor, identity)
    message.info(
      result.status === 'COMPLETED' ? '原调试已完成，结果已更新' : '原调试已失败，请查看运行记录'
    )
  }
}
function debug() {
  if (
    debugKey.value ||
    storageError.value ||
    !allowed('foundation.system_agents.run') ||
    item.value?.status === 'archived'
  )
    return
  void action.run(async () => {
    if ((dirty.value && source.value === 'DRAFT') || !input.value.trim())
      throw new Error('请保存草稿并填写调试输入')
    const command = {
      agent_id: id,
      expected_revision: item.value!.revision,
      source: source.value,
      input: input.value,
      idempotency_key: crypto.randomUUID()
    }
    try {
      sessionStorage.setItem(storageKey(auth.user?.sub ?? ''), command.idempotency_key)
    } catch {
      storageError.value = '无法保存原调试标识，尚未发起调试；请恢复浏览器存储后重新进入页面。'
      throw new Error(storageError.value)
    }
    debugKey.value = command.idempotency_key
    pendingDebug.value = command
    await dispatchDebug()
  })
}
function reconcileDebug() {
  if (!allowed('foundation.system_agents.run')) return
  void action.run(() => dispatchDebug(true))
}
function lookupDebug(key = lookupKey.value) {
  if (!allowed('foundation.system_agents.read')) return
  if (!key.trim()) {
    message.warning('请填写原调试的操作标识')
    return
  }
  const identity = auth.identityVersion
  const actor = auth.user?.sub ?? ''
  void action.run(async () => {
    let result: SystemAgentRunView
    try {
      result = await api.systemAgents.debugResult(id, key, { silent: true })
    } catch (reason) {
      if (identity !== auth.identityVersion) {
        if (reason instanceof HttpClientError) reason.claimFeedback()
        return
      }
      if (reason instanceof HttpClientError && reason.status === 404) {
        reason.claimFeedback()
        message.warning('未查到原调试记录，不能据此认定原请求未执行；请保留操作标识继续核对。')
        return
      }
      throw reason
    }
    if (identity !== auth.identityVersion) return
    runs.value = [result, ...runs.value.filter((run) => run.id !== result.id)]
    if (
      debugKey.value === key &&
      (result.status === 'COMPLETED' ||
        (result.status === 'FAILED' && result.error_code !== 'system_agent_outcome_unknown'))
    )
      clearDebugKey(actor, identity)
    message.info(
      result.error_code === 'system_agent_outcome_unknown'
        ? '原调试结果仍未知，请保留操作标识继续核对'
        : '已读取原调试记录，请查看运行记录中的状态和结果'
    )
  })
}
function restore(version: SystemAgentVersion) {
  if (
    debugKey.value ||
    storageError.value ||
    !allowed('foundation.system_agents.update') ||
    item.value?.status === 'archived'
  )
    return
  void action.run(async () => {
    if (
      !(await confirm({
        title: '恢复草稿',
        content: `将版本 ${version.version} 恢复到当前草稿？覆盖工作草稿，${dirty.value ? '未保存的修改也将丢弃。' : ''}发布后新执行生效。`,
        confirmText: '恢复为草稿',
        cancelText: '取消',
        danger: true
      }))
    )
      return
    accept(
      await checked('foundation.system_agents.update', () =>
        api.systemAgents.update({
          agent_id: id,
          expected_revision: item.value!.revision,
          config: version.config
        })
      )
    )
    message.success('历史配置已恢复为草稿，发布后用于新的正式执行')
  })
}
onMounted(reload)

const formTipId = useId()
</script>

<template>
  <AdminPage :title="item?.draft.name ?? '系统智能体'" :description="item?.agent_key">
    <template #extra>
      <RouterLink to="/platform/system-agents">
        <ElButton>返回列表</ElButton>
      </RouterLink>
      <ElTag v-if="dirty" type="warning">未保存</ElTag
      ><span v-if="dirty" class="admin-field-help"
        >可保存并继续发布；切换状态或调试草稿需先保存。已发布版本仍可调试。</span
      >
      <template v-if="item"
        ><span>当前发布修订 {{ item.published_version ?? '尚未发布' }}</span
        ><ElTag v-if="unpublished" type="warning">草稿待发布</ElTag>
        <ElButton
          v-permission="['foundation.system_agents.publish']"
          :disabled="
            action.busy.value ||
            !!debugKey ||
            !!storageError ||
            dirty ||
            !unpublished ||
            item.status === 'archived'
          "
          type="primary"
          @click="change('publish')"
          >发布</ElButton
        >
        <ElButton
          v-permission="['foundation.system_agents.update']"
          :disabled="action.busy.value || !!debugKey || !!storageError || dirty"
          v-if="item.status !== 'archived'"
          @click="change(item.status === 'disabled' ? 'enable' : 'disable')"
          >{{ item.status === 'disabled' ? '启用' : '停用' }}</ElButton
        >
        <ElDropdown
          v-if="
            allowed('foundation.system_agents.update') || allowed('foundation.system_agents.delete')
          "
          :disabled="action.busy.value || !!debugKey || !!storageError || dirty"
        >
          <ElButton :disabled="action.busy.value || !!debugKey || !!storageError || dirty"
            >更多操作</ElButton
          >
          <template #dropdown>
            <ElDropdownMenu>
              <ElDropdownItem
                v-if="allowed('foundation.system_agents.update') && item.status !== 'archived'"
                @click="change('archive')"
                >归档智能体</ElDropdownItem
              >
              <ElDropdownItem
                v-if="allowed('foundation.system_agents.delete')"
                @click="change('remove')"
                >删除智能体</ElDropdownItem
              >
            </ElDropdownMenu>
          </template>
        </ElDropdown>
      </template>
    </template>
    <FeedbackNotice
      v-if="readErrors || action.error.value"
      :title="[readErrors, action.error.value].filter(Boolean).join('；')"
      :closable="false"
      type="error"
    />
    <ElButton
      v-if="load.error.value"
      :loading="load.busy.value"
      :disabled="action.busy.value || dirty"
      @click="reload()"
      >重新读取资料</ElButton
    >
    <ElAlert v-if="storageError" :title="storageError" type="error" :closable="false" />
    <section v-if="debugKey" class="admin-card" role="status" aria-label="原调试结果待核对">
      <p>原调试尚未取得最终结果，请先核对原调试，再发起新的执行。</p>
      <p>
        离开此页不会撤销原调试；请保留操作标识，并在该智能体的运行记录中核对，不能把未知结果当作失败重新发起。
      </p>
      <p>
        操作标识：<code>{{ debugKey }}</code>
      </p>
      <ElButton
        v-if="pendingDebug && !storageError"
        :loading="action.busy.value"
        @click="reconcileDebug"
        >重试原请求</ElButton
      >
      <ElButton
        :loading="action.busy.value"
        :disabled="!allowed('foundation.system_agents.read')"
        @click="lookupDebug(debugKey)"
        >只读查询原调试</ElButton
      >
    </section>
    <ElAlert
      v-if="item?.status === 'archived'"
      title="智能体已归档，配置和历史仅供查看。"
      type="info"
      :closable="false"
    />
    <ElSkeleton v-if="load.busy.value && !form" :rows="8" />
    <ElTabs v-else-if="form">
      <ElTabPane label="配置草稿">
        <ElForm
          @keydown.capture="guardFormEnter"
          ref="formRef"
          :model="form"
          label-position="top"
          :disabled="agentEditLocked()"
          @submit.prevent="save()"
        >
          <AgentConfigFields
            v-model="form"
            :models="models.filter((m) => m.kind === 'LLM' && m.enabled)"
            :disabled="agentEditLocked()"
          />
          <p v-if="allowed('foundation.models.read')" class="admin-field-help">
            <RouterLink to="/platform/models" target="_blank" rel="noopener noreferrer"
              >管理模型（新标签页）</RouterLink
            >
            <ElButton link :loading="modelOptions.loading.value" @click="refreshModels"
              >刷新模型选项</ElButton
            >
          </p>
          <p
            v-if="
              modelOptions.data.value &&
              form.model_config_id &&
              !models.some(
                (model) =>
                  model.id === form?.model_config_id && model.kind === 'LLM' && model.enabled
              )
            "
            class="admin-field-help"
            role="status"
          >
            当前选择不在已启用的对话模型目录中；原选择已保留，请刷新目录或选择其他模型。
          </p>
          <ElCollapse>
            <ElCollapseItem
              name="limits"
              :title="`执行限制 · ${form.timeout_seconds} 秒 · 最多 ${form.max_business_model_calls} 次业务模型调用`"
            >
              <div class="admin-form-grid">
                <ElFormItem label="超时（秒）">
                  <ElInputNumber
                    v-described-by="'agent-timeout-help'"
                    aria-describedby="agent-timeout-help"
                    v-model="form.timeout_seconds"
                    :min="1"
                    :precision="0"
                  />
                  <p id="agent-timeout-help" class="admin-field-help">
                    单次执行的最长运行时间，单位为秒。
                  </p>
                </ElFormItem>
                <ElFormItem label="最大业务模型调用次数">
                  <ElInputNumber
                    v-described-by="'agent-business-calls-help'"
                    aria-describedby="agent-business-calls-help"
                    v-model="form.max_business_model_calls"
                    :min="1"
                    :max="item?.execution_mode === 'MODEL_TASK' ? 1 : 100"
                    :precision="0"
                  />
                  <p id="agent-business-calls-help" class="admin-field-help">
                    限制本次执行的业务模型调用次数；AGENT_RUN 允许 1–100 次，MODEL_TASK 固定为 1
                    次。此项不表示工作流步骤数。
                  </p>
                </ElFormItem>
              </div>
            </ElCollapseItem>
          </ElCollapse>
          <h3>能力挂载 · {{ form.mounts.length }} 项</h3>
          <p class="admin-field-help">
            挂载绑定稳定能力身份，新执行采用能力当前发布版本。
            <RouterLink
              v-if="allowed('foundation.capabilities.read')"
              to="/platform/skills"
              target="_blank"
              rel="noopener noreferrer"
              >管理技能（新标签页）</RouterLink
            >
            <ElButton
              v-if="allowed('foundation.system_agents.read')"
              link
              :loading="mountCatalog.loading.value"
              @click="refreshMountOptions"
              >刷新能力选项</ElButton
            >
          </p>
          <ElCard class="admin-card" v-for="(mount, index) in form.mounts" :key="mountKey(mount)"
            ><template #header
              ><strong
                >{{ index + 1 }}.
                {{
                  [...options.skills, ...options.tools].find(
                    (option) => option.id === mount.capability_definition_id
                  )?.name || '选择挂载能力'
                }}</strong
              ></template
            >
            <div class="admin-form-grid">
              <ElFormItem label="类型">
                <ElSelect
                  v-described-by="formTipId + '-field-2' + '-' + mountKey(mount)"
                  :aria-describedby="formTipId + '-field-2' + '-' + mountKey(mount)"
                  v-model="mount.capability_type"
                  @change="
                    () => {
                      mount.capability_definition_id = ''
                      mount.tool_deployment_id = null
                      mount.activation_mode = null
                    }
                  "
                >
                  <ElOption value="SKILL" label="技能" />
                  <ElOption value="TOOL" label="工具" />
                </ElSelect>

                <FieldTip :id="formTipId + '-field-2' + '-' + mountKey(mount)"
                  >选择系统智能体要挂载的能力类别。</FieldTip
                >
              </ElFormItem>
              <ElFormItem label="能力（跟随当前发布）">
                <ElSelect
                  v-described-by="formTipId + '-field-3' + '-' + mountKey(mount)"
                  :aria-describedby="formTipId + '-field-3' + '-' + mountKey(mount)"
                  v-model="mount.capability_definition_id"
                  filterable
                  @change="selectCapability(mount)"
                >
                  <ElOption
                    v-for="option in mount.capability_type === 'SKILL'
                      ? options.skills
                      : options.tools"
                    :key="option.id"
                    :value="option.id"
                    :label="option.name"
                  />
                </ElSelect>

                <FieldTip :id="formTipId + '-field-3' + '-' + mountKey(mount)"
                  >选择已发布能力，新执行跟随其当前发布版本。</FieldTip
                >
                <p
                  v-if="
                    mountCatalog.data.value &&
                    mount.capability_definition_id &&
                    !(mount.capability_type === 'SKILL' ? options.skills : options.tools).some(
                      (option) => option.id === mount.capability_definition_id
                    )
                  "
                  class="admin-field-help"
                  role="status"
                >
                  当前能力不在可挂载目录中；原选择已保留，请刷新或重新选择。
                </p>
              </ElFormItem>
              <ElFormItem v-if="mount.capability_type === 'SKILL'" label="激活方式">
                <span>由技能当前发布配置决定</span>

                <FieldTip :id="formTipId + '-field-4' + '-' + mountKey(mount)"
                  >新执行采用技能当前发布版本，激活方式随该版本生效。</FieldTip
                >
              </ElFormItem>
              <ElFormItem v-if="mount.capability_type === 'TOOL'" label="工具部署">
                <ElSelect
                  v-described-by="formTipId + '-field-5' + '-' + mountKey(mount)"
                  :aria-describedby="formTipId + '-field-5' + '-' + mountKey(mount)"
                  v-model="mount.tool_deployment_id"
                >
                  <ElOption
                    v-for="deployment in options.deployments.filter(
                      (d) => d.tool_definition_id === mount.capability_definition_id
                    )"
                    :key="deployment.id"
                    :value="deployment.id"
                    :label="deployment.name + ' · ' + deployment.environment"
                  />
                </ElSelect>

                <FieldTip :id="formTipId + '-field-5' + '-' + mountKey(mount)"
                  >仅有一个可用部署时自动选择；多个部署请自行选择。测试部署仅供调试，生产运行需使用生产部署。</FieldTip
                >
                <p
                  v-if="
                    mountCatalog.data.value &&
                    mount.tool_deployment_id &&
                    !options.deployments.some(
                      (deployment) =>
                        deployment.id === mount.tool_deployment_id &&
                        deployment.tool_definition_id === mount.capability_definition_id
                    )
                  "
                  class="admin-field-help"
                  role="status"
                >
                  当前服务实例不在可挂载目录中；原选择已保留，请刷新或重新选择。
                </p>
                <ElButton
                  v-if="allowed('foundation.system_agent_tools.create')"
                  :disabled="
                    agentEditLocked() ||
                    !mount.capability_definition_id ||
                    toolSetupMounted ||
                    !allowed('foundation.system_agents.read') ||
                    !allowed('foundation.system_agent_tools.read') ||
                    !allowed('foundation.system_agent_tools.publish') ||
                    !allowed('foundation.capabilities.read')
                  "
                  @click="startToolSetup(mount)"
                  >配置此工具部署</ElButton
                >
                <p
                  v-if="
                    allowed('foundation.system_agent_tools.create') &&
                    (!allowed('foundation.system_agents.read') ||
                      !allowed('foundation.system_agent_tools.read') ||
                      !allowed('foundation.system_agent_tools.publish') ||
                      !allowed('foundation.capabilities.read'))
                  "
                  class="admin-field-help"
                >
                  配置并发布部署需要系统智能体读取、工具部署读取、创建、发布及能力读取权限。
                </p>
                <div
                  v-if="
                    toolSetup?.mount === mount && toolSetup.published && setupCurrent(toolSetup)
                  "
                  class="admin-field-help"
                >
                  <p>
                    已发布部署：{{ toolSetup.published.name }}（{{ toolSetup.published.id }} ·
                    {{ toolSetup.published.environment === 'PRODUCTION' ? '生产' : '测试' }}）。{{
                      mount.tool_deployment_id === toolSetup.published.id
                        ? '已选择，请保存智能体草稿。'
                        : '选择后仍需保存智能体草稿。'
                    }}
                  </p>
                  <p v-if="toolSetup.error">{{ toolSetup.error }}</p>
                  <ElButton
                    v-if="
                      !toolSetup.candidate && mount.tool_deployment_id !== toolSetup.published.id
                    "
                    :loading="mountCatalog.loading.value"
                    @click="refreshToolCandidate"
                    >重新读取可挂载部署</ElButton
                  >
                  <ElButton v-if="toolSetup.candidate" @click="selectToolCandidate(toolSetup)"
                    >使用此部署</ElButton
                  >
                </div>
              </ElFormItem>
            </div>
            <ElCollapse
              ><ElCollapseItem
                name="mount-settings"
                :title="`挂载额度与顺序 · 优先级 ${mount.priority} · ${mount.token_budget == null ? '未指定 Token 预算' : mount.token_budget + ' Tokens'}`"
                ><div class="admin-form-grid">
                  <ElFormItem label="优先级">
                    <ElInputNumber
                      v-described-by="formTipId + '-field-6' + '-' + mountKey(mount)"
                      :aria-describedby="formTipId + '-field-6' + '-' + mountKey(mount)"
                      v-model="mount.priority"
                      :precision="0"
                    />

                    <FieldTip :id="formTipId + '-field-6' + '-' + mountKey(mount)"
                      >设置挂载的装配优先级。</FieldTip
                    >
                  </ElFormItem>
                  <ElFormItem label="Token 预算">
                    <ElInputNumber
                      v-described-by="formTipId + '-field-7' + '-' + mountKey(mount)"
                      :aria-describedby="formTipId + '-field-7' + '-' + mountKey(mount)"
                      v-model="mount.token_budget"
                      :min="1"
                      :precision="0"
                    />

                    <FieldTip :id="formTipId + '-field-7' + '-' + mountKey(mount)"
                      >限制该技能挂载使用的 Token 额度，不代表整次执行的累计预算。</FieldTip
                    >
                  </ElFormItem>
                </div></ElCollapseItem
              ></ElCollapse
            >
            <ElButton type="danger" link @click="form.mounts.splice(index, 1)">移除挂载</ElButton>
          </ElCard>
          <div class="admin-actions">
            <ElButton
              @click="
                form.mounts.push({
                  capability_type: 'SKILL',
                  capability_definition_id: '',
                  priority: 0,
                  token_budget: null,
                  activation_mode: null,
                  tool_deployment_id: null
                })
              "
              >添加挂载</ElButton
            >
            <ElButton
              v-permission="['foundation.system_agents.update']"
              :type="dirty && allowed('foundation.system_agents.publish') ? 'default' : 'primary'"
              native-type="submit"
              :loading="action.busy.value"
              >保存草稿</ElButton
            >
            <ElButton
              v-if="
                dirty &&
                allowed('foundation.system_agents.update') &&
                allowed('foundation.system_agents.publish')
              "
              type="primary"
              :loading="action.busy.value"
              :disabled="
                action.busy.value || !!debugKey || !!storageError || item?.status === 'archived'
              "
              @click="save(true)"
              >保存并继续发布</ElButton
            >
          </div>
        </ElForm>
      </ElTabPane>
      <ElTabPane label="调试与运行">
        <ElForm
          @keydown.capture="guardFormEnter"
          label-position="top"
          :disabled="
            !allowed('foundation.system_agents.run') ||
            action.busy.value ||
            !!debugKey ||
            !!storageError ||
            item?.status === 'archived'
          "
          @submit.prevent="debug"
        >
          <ElFormItem label="调试来源">
            <ElRadioGroup
              v-described-by="formTipId + '-field-8'"
              :aria-describedby="formTipId + '-field-8'"
              v-model="source"
            >
              <ElRadio value="DRAFT">已保存草稿</ElRadio>
              <ElRadio value="PUBLISHED" :disabled="!item?.published_version">已发布版本</ElRadio>
            </ElRadioGroup>

            <FieldTip :id="formTipId + '-field-8'"
              >选择调试当前草稿或已发布配置，核对实际测试来源。</FieldTip
            >
          </ElFormItem>
          <p v-if="!item?.published_version" class="admin-field-help">
            尚未发布版本，当前只能调试已保存草稿。
          </p>
          <p v-if="dirty && source === 'PUBLISHED'" class="admin-field-help" role="status">
            本次调试使用已发布版本，不包含当前未保存的草稿修改。
          </p>
          <ElAlert
            v-if="item?.purpose === 'memory-extraction'"
            type="info"
            :closable="false"
            title="记忆提取调试需要完整 JSON：schemaVersion 为 2，包含用户、带原文及时间的来源和已有事实。请核对示例后编辑本次真实输入。"
          />
          <ElButton
            v-if="item?.purpose === 'memory-extraction'"
            :disabled="action.busy.value || !!debugKey || !!storageError"
            @click="fillMemoryDebugExample"
            >填入记忆输入示例</ElButton
          >
          <ElFormItem label="调试输入">
            <ElInput
              v-described-by="formTipId + '-field-9'"
              :aria-describedby="formTipId + '-field-9'"
              v-model="input"
              type="textarea"
              :rows="5"
            />

            <FieldTip :id="formTipId + '-field-9'"
              >填写本次调试的业务输入，提交后会发起真实执行。</FieldTip
            >
          </ElFormItem>
          <ElButton
            v-permission="['foundation.system_agents.run']"
            native-type="submit"
            type="primary"
            :disabled="(dirty && source === 'DRAFT') || !!debugKey || !!storageError"
            :loading="action.busy.value"
            >运行调试</ElButton
          >
        </ElForm>
        <ElForm label-position="top" @submit.prevent="lookupDebug()">
          <ElFormItem label="按操作标识查询原调试">
            <ElInput
              v-model="lookupKey"
              v-described-by="formTipId + '-lookup'"
              :aria-describedby="formTipId + '-lookup'"
              :disabled="action.busy.value"
              maxlength="256"
            />
            <FieldTip :id="formTipId + '-lookup'"
              >离开页面后可用原操作标识只读查询，不会重新发起调试。</FieldTip
            >
          </ElFormItem>
          <ElButton native-type="submit" :loading="action.busy.value">查询原调试</ElButton>
        </ElForm>
        <ElTable
          table-layout="auto"
          empty-text="尚无调试记录；选择调试来源并填写输入后，可发起一次真实执行"
          :data="runs"
          style="width: 100%"
        >
          <ElTableColumn prop="status" label="状态" width="110"
            ><template #default="{ row }">{{
              row.error_code === 'system_agent_outcome_unknown'
                ? '结果未知'
                : statusLabel(row.status)
            }}</template></ElTableColumn
          >
          <ElTableColumn prop="duration_ms" label="耗时 ms" width="120"> </ElTableColumn>
          <ElTableColumn prop="input_tokens" label="输入 Token" width="130"> </ElTableColumn>
          <ElTableColumn prop="output_tokens" label="输出 Token" width="130"> </ElTableColumn>
          <ElTableColumn prop="output" label="结果" min-width="300" show-overflow-tooltip>
            <template #default="{ row }">
              <span>{{ row.output }}</span>
            </template>
          </ElTableColumn>
          <ElTableColumn prop="error_code" label="错误" min-width="180" show-overflow-tooltip>
          </ElTableColumn>
          <ElTableColumn prop="created_at" label="时间" min-width="190">
            <template #default="{ row }">{{ formatDateTime(row.created_at) }}</template>
          </ElTableColumn>
          <ElTableColumn label="操作" width="110">
            <template #default="{ row }"
              ><ElButton link @click="selectedRun = row as SystemAgentRunView"
                >查看结果</ElButton
              ></template
            >
          </ElTableColumn>
        </ElTable>
      </ElTabPane>
      <ElTabPane label="发布历史">
        <ElCard class="admin-card" v-for="version in versions" :key="version.version">
          <h3>版本 {{ version.version }} · {{ version.config.name }}</h3>
          <p>{{ formatDateTime(version.created_at) }}</p>
          <ElTag v-if="version.version === item?.published_version">当前发布</ElTag>
          <ElCollapse
            ><ElCollapseItem :name="version.version" title="完整发布配置">
              <pre class="prewrap">{{ JSON.stringify(version.config, null, 2) }}</pre>
            </ElCollapseItem></ElCollapse
          >
          <ElButton
            v-permission="['foundation.system_agents.update']"
            :disabled="
              action.busy.value || !!debugKey || !!storageError || item?.status === 'archived'
            "
            @click="restore(version)"
            >恢复为草稿</ElButton
          >
        </ElCard>
      </ElTabPane>
    </ElTabs>
    <ElDrawer
      :model-value="!!selectedRun"
      title="调试运行结果"
      size="min(720px, 100%)"
      append-to-body
      @update:model-value="selectedRun = undefined"
    >
      <template v-if="selectedRun">
        <p>
          状态：{{
            selectedRun.error_code === 'system_agent_outcome_unknown'
              ? '结果未知'
              : statusLabel(selectedRun.status)
          }}
        </p>
        <p v-if="selectedRun.error_code">错误：{{ selectedRun.error_code }}</p>
        <p class="admin-field-help">运行标识：{{ selectedRun.id }}</p>
        <h3>输出</h3>
        <pre v-if="selectedRun.output" class="prewrap">{{ selectedRun.output }}</pre>
        <p v-else class="admin-field-help">当前记录没有输出。</p>
      </template>
    </ElDrawer>
    <p v-if="toolSetupMounted && toolSetup && !setupCurrent(toolSetup)" class="admin-field-help">
      原挂载或账号已变化，原工具部署不会自动回填。
      <ElButton @click="toolSetupPage?.openEmbedded()">查看工具部署处理状态</ElButton>
      <ElButton @click="toolSetupPage?.releaseEmbedded()">结束旧处理上下文</ElButton>
    </p>
    <SystemAgentToolsPage
      v-if="toolSetupMounted && toolSetup"
      ref="toolSetupPage"
      :tool-definition-id="toolSetup.toolId"
      @closed="toolSetupMounted = false"
      @published="toolPublished"
    />
  </AdminPage>
</template>
