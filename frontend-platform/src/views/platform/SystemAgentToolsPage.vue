<script setup lang="ts">
import FeedbackNotice from '@skillforge/shared/feedback/FeedbackNotice.vue'
import { useActionPermissions } from '../../composables/actionPermissions'
import { vDescribedBy } from '@skillforge/shared/directives/describedBy'

import FieldTip from '@skillforge/shared/components/FieldTip.vue'

import { message, confirm } from '@skillforge/shared/feedback'
import { HttpClientError } from '@skillforge/shared/utils/http'
import { guardFormEnter } from '@skillforge/shared/admin'
import { computed, onMounted, reactive, ref, watch, useId } from 'vue'
import { type FormInstance } from 'element-plus'
import {
  AdminPage,
  useDrawerClose,
  useResource,
  useTask,
  useUnsavedChanges
} from '@skillforge/shared/admin'
import { useAuthStore } from '../../stores/auth'
import {
  api,
  type SystemAgentToolDeploymentView,
  type SystemAgentToolDeploymentWrite,
  type SystemAgentMountOptions
} from '../../api'
const props = defineProps<{ toolDefinitionId?: string }>()
const emit = defineEmits<{ closed: []; published: [value: SystemAgentToolDeploymentView] }>()
const { allowed, checked } = useActionPermissions()
const auth = useAuthStore()
const filters = reactive({ search: '', environment: '', active: '' })
const formRef = ref<FormInstance>()
const toolError = ref('')
const toolLoading = ref(false)
const load = useTask(),
  action = useTask(),
  rows = ref<SystemAgentToolDeploymentView[]>([]),
  tools = ref<SystemAgentMountOptions['tools']>([]),
  open = ref(false),
  editing = ref<SystemAgentToolDeploymentView>()
const toolOptions = useResource((signal) => api.systemAgents.mountOptions({ signal, silent: true }))
watch(
  open,
  (value) => {
    if (!value) {
      toolOptions.invalidate()
      toolOptions.error.value = ''
    }
  },
  { flush: 'sync' }
)
const filteredRows = computed(() => {
  const search = filters.search.trim().toLocaleLowerCase()
  return rows.value.filter(
    (row) =>
      (!search ||
        [
          row.name,
          row.id,
          row.tool_definition_id,
          row.provider_ref ?? '',
          tools.value.find((tool) => tool.id === row.tool_definition_id)?.name ?? ''
        ].some((value) => value.toLocaleLowerCase().includes(search))) &&
      (!filters.environment || row.environment === filters.environment) &&
      (!filters.active || String(row.active) === filters.active)
  )
})
const selectedToolName = ref('')
const suggestedName = ref('')
let toolRequest = 0
const baseline = ref('')
const saved = ref<SystemAgentToolDeploymentView>()
type UnknownWrite = {
  operationId: string
  serverTracked?: boolean
  publishedRevision?: number | null
  stage: string
  actor: string
  id?: string
  revision?: number
  input?: SystemAgentToolDeploymentWrite
}
const unknown = ref<UnknownWrite>()
const unknownStored = ref(false)
const storageError = ref('')
type StoredReceipt = {
  pending: UnknownWrite
  status: 'succeeded' | 'rejected'
  result?: SystemAgentToolDeploymentView
}
const archived = ref<StoredReceipt>()
const unarchived = new Map<string, StoredReceipt>()
useUnsavedChanges({ dirty: () => false, busy: () => unarchived.size > 0 })
const storageKey = (actor: string) => `skillforge.system-tool-unknown:${actor}`
function recordUnknown(value: UnknownWrite, identity: number) {
  try {
    sessionStorage.setItem(storageKey(value.actor), JSON.stringify(value))
  } catch {
    storageError.value = '无法保存原操作记录，尚未发出写请求。请恢复浏览器存储后重试。'
    throw new Error(storageError.value)
  }
  if (identity === auth.identityVersion) {
    unknown.value = value
    unknownStored.value = true
  }
}
function clearUnknown(actor: string) {
  sessionStorage.removeItem(storageKey(actor))
  if (actor === auth.user?.sub) {
    unknown.value = undefined
    unknownStored.value = false
  }
}
function archiveReceipt(receipt: StoredReceipt) {
  const actor = receipt.pending.actor
  unarchived.set(actor, receipt)
  try {
    sessionStorage.setItem(storageKey(actor) + ':receipt', JSON.stringify(receipt))
    clearUnknown(actor)
    unarchived.delete(actor)
    if (actor === auth.user?.sub) archived.value = receipt
  } catch {
    if (actor === auth.user?.sub) {
      unknown.value = undefined
      unknownStored.value = false
      archived.value = receipt
      storageError.value =
        '已收到确定结果，但无法保存或清理本地记录；请保留本页面并重试处理，暂不能继续写入。'
    }
  }
}
function loadUnknown() {
  unknown.value = undefined
  unknownStored.value = false
  archived.value = undefined
  try {
    const pendingReceipt = unarchived.get(auth.user?.sub ?? '')
    if (pendingReceipt) {
      archived.value = pendingReceipt
      storageError.value =
        '已收到确定结果，但无法保存或清理本地记录；请保留本页面并重试处理，暂不能继续写入。'
      return
    }
    const value = sessionStorage.getItem(storageKey(auth.user?.sub ?? ''))
    const parsed: unknown = value ? JSON.parse(value) : undefined
    if (
      value &&
      (!parsed ||
        typeof parsed !== 'object' ||
        !('stage' in parsed) ||
        typeof parsed.stage !== 'string' ||
        !('operationId' in parsed) ||
        typeof parsed.operationId !== 'string' ||
        !('actor' in parsed) ||
        typeof parsed.actor !== 'string')
    )
      throw new Error('invalid')
    unknown.value = parsed as UnknownWrite | undefined
    unknownStored.value = !!value
    const receipt = sessionStorage.getItem(storageKey(auth.user?.sub ?? '') + ':receipt')
    const previous = receipt ? JSON.parse(receipt) : undefined
    if (
      previous &&
      (!previous.pending ||
        typeof previous.pending.operationId !== 'string' ||
        !['succeeded', 'rejected'].includes(previous.status) ||
        (previous.status === 'succeeded' &&
          (!previous.result || typeof previous.result.id !== 'string')))
    )
      throw new Error('invalid receipt')
    if (
      parsed &&
      previous &&
      (parsed as UnknownWrite).operationId === previous.pending.operationId
    ) {
      unknown.value = undefined
      unknownStored.value = false
      archived.value = previous
      unarchived.set(previous.pending.actor, previous)
      storageError.value = '已收到确定结果，但原操作记录尚未清理；请重试处理，暂不能继续写入。'
      return
    }
    archived.value = previous
    storageError.value = ''
  } catch {
    unknown.value = undefined
    unknownStored.value = false
    storageError.value =
      '原操作记录无法读取或已损坏，不能确认是否存在待核对请求；请联系管理员处理。'
  }
}
function retryStorage() {
  const actor = auth.user?.sub ?? ''
  const receipt = unarchived.get(actor)
  if (receipt) archiveReceipt(receipt)
  loadUnknown()
  if (
    props.toolDefinitionId &&
    receipt?.status === 'succeeded' &&
    receipt.pending.stage === '发布' &&
    receipt.result &&
    !unarchived.has(actor)
  )
    emit('published', receipt.result)
}
watch(
  () => auth.identityVersion,
  () => {
    open.value = false
    saved.value = undefined
    editing.value = undefined
    rows.value = []
    tools.value = []
    toolOptions.invalidate()
    toolOptions.error.value = ''
    selectedToolName.value = ''
    toolRequest++
    toolLoading.value = false
    toolError.value = ''
    historyOpen.value = false
    publications.value = []
    historyDeployment.value = undefined
    loadUnknown()
  },
  { flush: 'sync' }
)
loadUnknown()
function validReceipt(
  value: SystemAgentToolDeploymentView,
  input: SystemAgentToolDeploymentWrite,
  id?: string,
  expected?: number
) {
  return (
    !!value &&
    typeof value.id === 'string' &&
    !!value.id &&
    Number.isSafeInteger(value.revision) &&
    value.revision > 0 &&
    (!id || value.id === id) &&
    (!expected || value.revision === expected + 1) &&
    value.name === input.name.trim() &&
    value.tool_definition_id === input.tool_definition_id &&
    value.provider_type === input.provider_type &&
    value.provider_ref === input.provider_ref.trim() &&
    value.environment === input.environment &&
    value.active === input.active &&
    typeof value.has_unpublished_changes === 'boolean'
  )
}
function checkOriginalReceipt() {
  const pending = unknown.value
  if (!pending?.serverTracked || !allowed('foundation.system_agent_tools.read')) return
  const identity = auth.identityVersion
  void action.run(async () => {
    const receipt = await api.systemAgentTools.receipt(pending.operationId)
    if (identity !== auth.identityVersion || unknown.value?.operationId !== pending.operationId)
      return
    if (receipt.status !== 'SUCCEEDED' || !receipt.result) {
      message.info('尚未查到原操作的确定结果，请稍后核对')
      return
    }
    const result = receipt.result
    const valid =
      pending.input &&
      (pending.stage === '发布'
        ? validReceipt(result, pending.input) &&
          result.id === pending.id &&
          result.revision === pending.revision &&
          !result.has_unpublished_changes &&
          result.published_revision === (pending.publishedRevision ?? 0) + 1
        : validReceipt(result, pending.input, pending.id, pending.revision))
    if (!valid) throw new Error('原操作回执的身份或内容无法核实，请联系管理员处理')
    archiveReceipt({ pending, status: 'succeeded', result })
    editing.value = result
    saved.value = result
    Object.assign(form, pending.input)
    baseline.value = JSON.stringify(form)
    void resolveTool(result.tool_definition_id)
    message.success('已核对原操作成功回执')
    if (props.toolDefinitionId && pending.stage === '发布' && !unarchived.has(pending.actor))
      emit('published', result)
    reload()
  })
}
const form = reactive<SystemAgentToolDeploymentWrite>({
  name: '',
  tool_definition_id: '',
  provider_type: 'PLATFORM',
  provider_ref: '',
  environment: 'TEST',
  active: true
})
watch([() => form.tool_definition_id, () => form.environment, tools], () => {
  if (editing.value) return
  const pristine = !!props.toolDefinitionId && JSON.stringify(form) === baseline.value
  const tool = tools.value.find((value) => value.id === form.tool_definition_id)
  const next = tool ? `${tool.name} · ${form.environment === 'PRODUCTION' ? '生产' : '测试'}` : ''
  if (!form.name.trim() || form.name === suggestedName.value) form.name = next
  suggestedName.value = next
  if (pristine) baseline.value = JSON.stringify(form)
})
async function resolveTool(id: string) {
  const request = ++toolRequest
  selectedToolName.value = ''
  form.provider_ref = editing.value?.tool_definition_id === id ? editing.value.provider_ref : ''
  toolError.value = ''
  toolLoading.value = !!id
  if (!id) return
  try {
    const definition = await api.capabilities.getDefinition(id)
    if (!definition.defaultVersionId)
      throw new Error('工具尚未发布，请先在能力目录发布工具，再选择部署')
    const version = await api.capabilities.getVersion(definition.defaultVersionId)
    if (request !== toolRequest) return
    if (typeof version.descriptor.tool_name !== 'string' || !version.descriptor.tool_name)
      throw new Error('工具的当前发布版本缺少工具名称，请在能力目录修正并重新发布')
    selectedToolName.value = version.descriptor.tool_name
    const pristine = !!props.toolDefinitionId && JSON.stringify(form) === baseline.value
    form.provider_ref = selectedToolName.value
    if (pristine) baseline.value = JSON.stringify(form)
  } catch (error) {
    if (error instanceof HttpClientError) error.claimFeedback()
    if (request === toolRequest) {
      toolError.value =
        error instanceof Error ? error.message : '未能读取工具的当前发布配置，暂不能保存部署'
    }
  } finally {
    if (request === toolRequest) toolLoading.value = false
  }
}
watch(() => form.tool_definition_id, resolveTool)
function reload() {
  const identity = auth.identityVersion
  void load.run(async () => {
    const results = await Promise.allSettled([
      api.systemAgentTools.list({ silent: true }),
      allowed('foundation.system_agents.read')
        ? api.systemAgents.mountOptions({ silent: true })
        : Promise.resolve({ skills: [], tools: [], deployments: [] })
    ])
    if (identity !== auth.identityVersion) return
    const [list, options] = results
    if (list.status === 'fulfilled') rows.value = list.value
    if (options.status === 'fulfilled') tools.value = options.value.tools
    const failures = results.flatMap((result) => {
      if (result.status === 'fulfilled') return []
      if (result.reason instanceof HttpClientError) result.reason.claimFeedback()
      return [result.reason instanceof Error ? result.reason.message : '资料读取失败']
    })
    if (failures.length) throw new Error([...new Set(failures)].join('；'))
  })
}
async function refreshToolOptions() {
  if (!allowed('foundation.system_agents.read')) return
  const identity = auth.identityVersion
  const value = await toolOptions.reload()
  if (value && identity === auth.identityVersion && open.value) tools.value = value.tools
}
function edit(row?: SystemAgentToolDeploymentView) {
  if (
    !allowed(row ? 'foundation.system_agent_tools.update' : 'foundation.system_agent_tools.create')
  )
    return
  if (action.busy.value || unknown.value || storageError.value) return
  saved.value = undefined
  action.error.value = ''
  editing.value = row
  suggestedName.value = ''
  const sameTool = form.tool_definition_id === (row?.tool_definition_id ?? '')
  Object.assign(
    form,
    row
      ? {
          name: row.name,
          tool_definition_id: row.tool_definition_id,
          provider_type: row.provider_type,
          provider_ref: row.provider_ref,
          environment: row.environment,
          active: row.active
        }
      : {
          name: '',
          tool_definition_id: '',
          provider_type: 'PLATFORM',
          provider_ref: '',
          environment: 'TEST',
          active: true
        }
  )
  baseline.value = JSON.stringify(form)
  open.value = true
  if (sameTool) void resolveTool(form.tool_definition_id)
}
function save(continueToPublish = false) {
  if (
    !allowed(
      editing.value
        ? 'foundation.system_agent_tools.update'
        : 'foundation.system_agent_tools.create'
    )
  )
    return
  if (unknown.value || storageError.value) return
  const identity = auth.identityVersion
  const actor = auth.user?.sub ?? ''
  void action.run(async () => {
    await formRef.value?.validate()
    if (identity !== auth.identityVersion) throw new Error('账号已变化，请重新打开表单')
    if (!selectedToolName.value || form.provider_ref !== selectedToolName.value)
      throw new Error('请等待工具加载完成')
    if (!form.name.trim() || !form.tool_definition_id || !form.provider_ref.trim())
      throw new Error('请填写部署名称并选择已发布工具；平台工具名称会由所选版本自动读取')
    const target = editing.value
    const input = { ...form }
    let result: SystemAgentToolDeploymentView
    let dispatched = false
    let received = false
    const pending: UnknownWrite = {
      operationId: crypto.randomUUID(),
      serverTracked: true,
      stage: target ? '保存草稿' : '创建草稿',
      actor,
      id: target?.id,
      revision: target?.revision,
      input
    }
    try {
      result = target
        ? await checked('foundation.system_agent_tools.update', async () => {
            recordUnknown(pending, identity)
            dispatched = true
            const value = await api.systemAgentTools.update(
              target.id,
              target.revision,
              input,
              pending.operationId
            )
            if (!validReceipt(value, input, target.id, target.revision))
              throw new Error('保存响应身份或内容无法核实，请保留原请求核对')
            archiveReceipt({ pending, status: 'succeeded', result: value })
            received = true
            return value
          })
        : await checked('foundation.system_agent_tools.create', async () => {
            recordUnknown(pending, identity)
            dispatched = true
            const value = await api.systemAgentTools.create(input, pending.operationId)
            if (!validReceipt(value, input))
              throw new Error('保存响应身份或内容无法核实，请保留原请求核对')
            archiveReceipt({ pending, status: 'succeeded', result: value })
            received = true
            return value
          })
      if (identity !== auth.identityVersion) return
    } catch (reason) {
      if (
        dispatched &&
        !received &&
        reason instanceof HttpClientError &&
        reason.code === 'SYSTEM_AGENT_TOOL_WRITE_REJECTED'
      )
        archiveReceipt({ pending, status: 'rejected' })
      if (identity !== auth.identityVersion) return
      throw reason
    }
    editing.value = result
    saved.value = result
    baseline.value = JSON.stringify(form)
    message.success('工具部署草稿已保存，发布后生效')
    reload()
    if (
      continueToPublish &&
      !unarchived.has(actor) &&
      identity === auth.identityVersion &&
      allowed('foundation.system_agent_tools.publish')
    )
      await publishSaved(result, identity)
  })
}
async function publishSaved(row: SystemAgentToolDeploymentView, identity: number) {
  if (!row.has_unpublished_changes) {
    message.info('当前草稿与发布版本一致，无需发布')
    return
  }
  if (
    !(await confirm({
      title: '发布工具部署',
      confirmText: '发布',
      content: `发布${row.environment === 'PRODUCTION' ? '生产' : '测试'}环境的“${row.name}”？新执行采用此部署配置，当前执行继续原版本。`,
      danger: row.environment === 'PRODUCTION'
    }))
  )
    return
  if (identity !== auth.identityVersion || !allowed('foundation.system_agent_tools.publish'))
    throw new Error('账号或发布权限已变化，草稿已保存，请重新确认发布')
  let dispatched = false
  let received = false
  const actor = auth.user?.sub ?? ''
  const pending: UnknownWrite = {
    operationId: crypto.randomUUID(),
    serverTracked: true,
    input: {
      name: row.name,
      tool_definition_id: row.tool_definition_id,
      provider_type: row.provider_type,
      provider_ref: row.provider_ref,
      environment: row.environment,
      active: row.active
    },
    publishedRevision: row.published_revision,
    stage: '发布',
    actor,
    id: row.id,
    revision: row.revision
  }
  try {
    const result = await checked('foundation.system_agent_tools.publish', async () => {
      recordUnknown(pending, identity)
      dispatched = true
      const value = await api.systemAgentTools.publish(row.id, row.revision, pending.operationId)
      if (
        !validReceipt(value, row) ||
        value.id !== row.id ||
        value.revision !== row.revision ||
        value.has_unpublished_changes ||
        value.published_revision !== (row.published_revision ?? 0) + 1
      )
        throw new Error('发布响应身份或内容无法核实，请保留原请求核对')
      archiveReceipt({ pending, status: 'succeeded', result: value })
      received = true
      return value
    })
    if (identity !== auth.identityVersion) return
    if (open.value) {
      saved.value = result
      editing.value = result
    }
    message.success('工具部署已发布')
    if (props.toolDefinitionId && !unarchived.has(actor)) emit('published', result)
    reload()
  } catch (reason) {
    if (
      dispatched &&
      !received &&
      reason instanceof HttpClientError &&
      reason.code === 'SYSTEM_AGENT_TOOL_WRITE_REJECTED'
    )
      archiveReceipt({ pending, status: 'rejected' })
    if (identity !== auth.identityVersion) return
    throw reason
  }
}
function continuePublish() {
  const row = saved.value
  if (
    !row ||
    unknown.value ||
    storageError.value ||
    !allowed('foundation.system_agent_tools.publish')
  )
    return
  if (JSON.stringify(form) !== baseline.value) {
    save(true)
    return
  }
  void action.run(() => publishSaved(row, auth.identityVersion))
}
const historyOpen = ref(false),
  publications = ref<SystemAgentToolDeploymentView[]>([]),
  historyDeployment = ref<SystemAgentToolDeploymentView>()
function publish(row: SystemAgentToolDeploymentView) {
  if (!allowed('foundation.system_agent_tools.publish') || unknown.value || storageError.value)
    return
  void action.run(() => publishSaved(row, auth.identityVersion))
}
function loadHistory(row: SystemAgentToolDeploymentView) {
  const identity = auth.identityVersion
  void action.run(async () => {
    historyDeployment.value = row
    const value = await api.systemAgentTools.history(row.id)
    if (identity !== auth.identityVersion) return
    publications.value = value
    historyOpen.value = true
  })
}
function restorePublication(value: SystemAgentToolDeploymentView) {
  if (!allowed('foundation.system_agent_tools.update') || unknown.value || storageError.value)
    return
  void action.run(async () => {
    const current = historyDeployment.value
    const identity = auth.identityVersion
    if (!current) return
    if (
      !(await confirm({
        title: '恢复工具部署草稿',
        confirmText: '恢复为草稿',
        danger: true,
        content: `将发布修订 ${value.revision} 恢复到“${current.name}”的当前草稿？覆盖草稿，发布后才会生效。`
      }))
    )
      return
    if (identity !== auth.identityVersion || !allowed('foundation.system_agent_tools.update'))
      return
    const actor = auth.user?.sub ?? ''
    const input = {
      name: value.name,
      tool_definition_id: value.tool_definition_id,
      provider_type: value.provider_type,
      provider_ref: value.provider_ref,
      environment: value.environment,
      active: value.active
    }
    const pending: UnknownWrite = {
      operationId: crypto.randomUUID(),
      serverTracked: true,
      stage: '恢复草稿',
      actor,
      id: current.id,
      revision: current.revision,
      input
    }
    let dispatched = false
    let received = false
    try {
      await checked('foundation.system_agent_tools.update', async () => {
        recordUnknown(pending, identity)
        dispatched = true
        const result = await api.systemAgentTools.update(
          current.id,
          current.revision,
          input,
          pending.operationId
        )
        if (!validReceipt(result, input, current.id, current.revision))
          throw new Error('恢复响应身份或内容无法核实，请保留原请求核对')
        archiveReceipt({ pending, status: 'succeeded', result })
        received = true
      })
    } catch (reason) {
      if (
        dispatched &&
        !received &&
        reason instanceof HttpClientError &&
        reason.code === 'SYSTEM_AGENT_TOOL_WRITE_REJECTED'
      )
        archiveReceipt({ pending, status: 'rejected' })
      if (identity !== auth.identityVersion) return
      throw reason
    }
    if (identity !== auth.identityVersion) return
    historyOpen.value = false
    message.success('工具部署已恢复为草稿，发布后生效')
    reload()
  })
}
function openEmbedded() {
  if (!props.toolDefinitionId) return
  if (unknown.value || storageError.value) {
    open.value = true
    return
  }
  edit()
  if (open.value) {
    form.tool_definition_id = props.toolDefinitionId
    baseline.value = JSON.stringify(form)
  }
}
function releaseEmbedded() {
  if (action.busy.value || unarchived.size) {
    message.warning('原操作或回执处理尚未完成，请先核对。')
    return
  }
  emit('closed')
}
defineExpose({ openEmbedded, releaseEmbedded })
onMounted(() => {
  reload()
  if (props.toolDefinitionId) openEmbedded()
})

const { requestClose: openClose } = useDrawerClose({
  open: () => open.value,
  busy: () => action.busy.value || unarchived.has(auth.user?.sub ?? ''),
  dirty: () => JSON.stringify(form) !== baseline.value,
  close: () => {
    open.value = false
    if (props.toolDefinitionId) emit('closed')
  }
})
async function requestDrawerClose() {
  if (!unknown.value) return openClose()
  if (!unknownStored.value || action.busy.value) return
  if (
    await confirm({
      title: '保留未知结果并关闭',
      content: '原操作仍待核对，将保留在当前浏览器标签页中，关闭不会认定操作失败或解除写入限制。',
      confirmText: '保留并关闭',
      cancelText: '继续查看'
    })
  ) {
    open.value = false
    if (props.toolDefinitionId) emit('closed')
  }
}

const formTipId = useId()
</script>

<template>
  <component
    :is="props.toolDefinitionId ? 'div' : AdminPage"
    v-show="!props.toolDefinitionId"
    title="系统工具部署"
    description="维护系统智能体可挂载的工具部署实例"
  >
    <template #extra>
      <ElButton
        v-permission="['foundation.system_agent_tools.create']"
        type="primary"
        :disabled="action.busy.value || !!unknown || !!storageError"
        @click="edit()"
        >新增部署</ElButton
      >
      <ElButton :loading="load.busy.value" @click="reload">刷新</ElButton>
    </template>
    <ElForm @keydown.capture="guardFormEnter" class="admin-filters filters" @submit.prevent="reload"
      ><ElInput
        v-model="filters.search"
        aria-label="搜索工具部署"
        clearable
        placeholder="搜索部署、工具或提供方引用"
      /><ElSelect
        v-model="filters.environment"
        aria-label="部署环境"
        clearable
        placeholder="全部环境"
        ><ElOption value="TEST" label="测试" /><ElOption
          value="PRODUCTION"
          label="生产" /></ElSelect
      ><ElSelect v-model="filters.active" aria-label="部署状态" clearable placeholder="全部状态"
        ><ElOption value="true" label="启用" /><ElOption value="false" label="停用" /></ElSelect
      ><ElButton @click="Object.assign(filters, { search: '', environment: '', active: '' })"
        >重置</ElButton
      >
    </ElForm>
    <FeedbackNotice
      v-if="load.error.value || (action.error.value && !open && !historyOpen)"
      :title="load.error.value || action.error.value"
      :closable="false"
      type="error"
    />
    <ElAlert v-if="storageError && !open" type="error" :closable="false" :title="storageError">
      <ElButton @click="retryStorage">重试处理原操作记录</ElButton>
    </ElAlert>
    <ElAlert
      v-if="archived && !open"
      type="info"
      :closable="false"
      :title="`原账号的${archived.pending.stage}${archived.status === 'succeeded' ? '已收到确定回执' : '已确定拒绝'}`"
    >
      <template v-if="archived.result">
        部署 {{ archived.result.id }}，草稿修订 {{ archived.result.revision }}，发布修订
        {{ archived.result.published_revision ?? '未发布' }}。
      </template>
      <template v-else
        >部署 {{ archived.pending.id ?? '尚无部署标识' }}，原修订
        {{ archived.pending.revision ?? '无' }}。</template
      >
    </ElAlert>
    <ElAlert
      v-if="unknown && !open"
      type="warning"
      :closable="false"
      title="原工具部署操作结果未知，写入仍被阻止。"
    >
      <ElButton @click="open = true">查看原操作并核对</ElButton>
    </ElAlert>
    <ElTable
      table-layout="auto"
      :empty-text="
        load.busy.value
          ? '正在读取工具部署…'
          : load.error.value
            ? '工具部署列表尚未读取，请查看上方错误'
            : rows.length
              ? '没有匹配的工具部署，可调整搜索词、环境或启用状态'
              : '当前没有系统工具部署'
      "
      v-loading="load.busy.value"
      :data="filteredRows"
      style="width: 100%"
    >
      <ElTableColumn prop="name" label="部署名称" min-width="220" show-overflow-tooltip>
      </ElTableColumn>
      <ElTableColumn
        prop="tool_definition_id"
        label="工具（跟随当前发布）"
        min-width="220"
        show-overflow-tooltip
        ><template #default="{ row }">{{
          tools.find((tool) => tool.id === row.tool_definition_id)?.name ?? '工具不在当前可选目录中'
        }}</template></ElTableColumn
      >
      <ElTableColumn prop="provider_ref" label="提供方引用" min-width="220" show-overflow-tooltip>
      </ElTableColumn>
      <ElTableColumn prop="environment" label="环境" min-width="140" show-overflow-tooltip>
        <template #default="{ row }">{{
          row.environment === 'PRODUCTION' ? '生产' : '测试'
        }}</template>
      </ElTableColumn>
      <ElTableColumn prop="active" label="启用" width="90">
        <template #default="{ row }">{{ row.active ? '是' : '否' }}</template>
      </ElTableColumn>
      <ElTableColumn label="发布修订" width="120"
        ><template #default="{ row }">{{
          row.published_revision ?? '尚未发布'
        }}</template></ElTableColumn
      >
      <ElTableColumn label="工作草稿" width="120"
        ><template #default="{ row }">{{
          row.has_unpublished_changes ? '待发布' : '与发布一致'
        }}</template></ElTableColumn
      >
      <ElTableColumn prop="actions" label="操作" width="220" class-name="admin-table-actions">
        <template #default="{ row }">
          <ElButton
            v-permission="['foundation.system_agent_tools.publish']"
            link
            type="primary"
            :disabled="action.busy.value || !!unknown || !!storageError"
            @click="publish(row as SystemAgentToolDeploymentView)"
            v-if="row.has_unpublished_changes"
            >发布</ElButton
          >
          <ElButton
            link
            :disabled="action.busy.value"
            @click="loadHistory(row as SystemAgentToolDeploymentView)"
            >历史</ElButton
          >
          <ElButton
            v-permission="['foundation.system_agent_tools.update']"
            link
            type="primary"
            @click="edit(row as SystemAgentToolDeploymentView)"
            >编辑</ElButton
          >
        </template>
      </ElTableColumn>
    </ElTable>
    <ElDrawer
      class="admin-drawer"
      direction="rtl"
      append-to-body
      v-model="open"
      :title="editing ? '编辑工具部署' : '新增工具部署'"
      :before-close="requestDrawerClose"
      size="680px"
      :close-on-click-modal="false"
    >
      <FeedbackNotice
        v-if="action.error.value"
        :title="action.error.value"
        :closable="false"
        type="error"
      />
      <ElAlert v-if="storageError" type="error" :closable="false" :title="storageError">
        <ElButton @click="retryStorage">重试处理原操作记录</ElButton>
      </ElAlert>
      <ElAlert
        v-if="unknown"
        type="warning"
        :closable="false"
        :title="`${unknown.stage}结果未知，不能重新提交或将列表当前状态当作原请求回执。`"
      >
        <p v-if="unknown.id">原部署：{{ unknown.id }}，原修订：{{ unknown.revision }}</p>
        <p>原操作者：{{ unknown.actor || '当前账号' }}</p>
        <pre v-if="unknown.input">{{ JSON.stringify(unknown.input, null, 2) }}</pre>
        <p v-if="unknown.serverTracked">可查询原操作回执；未查到时请稍后重试核对。</p>
        <p v-else>此操作未记录服务端标识，请保留信息并由管理员核对；刷新仅供查看当前目录。</p>
        <ElButton
          v-if="unknown.serverTracked && allowed('foundation.system_agent_tools.read')"
          :loading="action.busy.value"
          @click="checkOriginalReceipt"
          >核对原操作</ElButton
        >
        <ElButton :loading="load.busy.value" @click="reload">只读刷新目录</ElButton>
        <ElCollapse>
          <ElCollapseItem title="查看当前目录（不代表原请求回执）">
            <ElTable :data="rows" v-loading="load.busy.value">
              <ElTableColumn prop="id" label="部署标识" />
              <ElTableColumn prop="name" label="名称" />
              <ElTableColumn prop="revision" label="当前草稿修订" />
              <ElTableColumn prop="published_revision" label="当前发布修订" />
            </ElTable>
          </ElCollapseItem>
        </ElCollapse>
      </ElAlert>
      <p v-if="saved" class="admin-field-help" role="status">
        已保存部署 {{ saved.name }}（{{ saved.id }}），草稿修订 {{ saved.revision }}。
        {{ saved.has_unpublished_changes ? '等待发布。' : '当前草稿与发布一致。' }}
      </p>
      <p
        v-if="!allowed('foundation.system_agents.read') || !allowed('foundation.capabilities.read')"
        class="admin-field-help"
      >
        选择工具需要系统智能体读取和能力读取权限，请联系管理员授予相应权限。
      </p>
      <div v-if="load.error.value && saved" class="admin-field-help">
        列表刷新失败；已保存回执仍保留在此。
        <ElButton :loading="load.busy.value" @click="reload">重新读取目录</ElButton>
      </div>
      <FeedbackNotice v-if="toolError" :title="toolError" :closable="false" type="error" />
      <FeedbackNotice
        v-if="toolOptions.error.value"
        :title="toolOptions.error.value"
        :closable="false"
        type="error"
      />
      <ElButton v-if="toolError" @click="resolveTool(form.tool_definition_id)"
        >重试读取工具</ElButton
      >
      <ElForm
        @keydown.capture="guardFormEnter"
        ref="formRef"
        id="tool-deployment-form"
        @submit.prevent="save(false)"
        :model="form"
        label-position="top"
        :disabled="
          !allowed(
            editing
              ? 'foundation.system_agent_tools.update'
              : 'foundation.system_agent_tools.create'
          ) ||
          action.busy.value ||
          !!unknown ||
          !!storageError
        "
      >
        <ElFormItem
          label="部署名称"
          prop="name"
          :rules="[{ required: true, message: '请填写部署名称' }]"
        >
          <ElInput v-model="form.name" />
        </ElFormItem>
        <ElFormItem
          label="工具（跟随当前发布）"
          prop="tool_definition_id"
          :rules="[{ required: true, message: '请选择已发布工具' }]"
        >
          <ElSelect
            v-described-by="formTipId + '-field-1'"
            :aria-describedby="formTipId + '-field-1'"
            v-model="form.tool_definition_id"
            filterable
            :disabled="!!props.toolDefinitionId"
          >
            <ElOption
              v-if="
                form.tool_definition_id &&
                !tools.some((tool) => tool.id === form.tool_definition_id)
              "
              :value="form.tool_definition_id"
              :label="form.tool_definition_id + '（当前不可选）'"
              disabled
            />
            <ElOption v-for="tool in tools" :key="tool.id" :value="tool.id" :label="tool.name" />
          </ElSelect>

          <FieldTip :id="formTipId + '-field-1'"
            >选择已发布工具；新执行跟随其当前发布版本。</FieldTip
          >
          <p class="admin-field-help">
            <RouterLink
              v-if="allowed('foundation.capabilities.read')"
              to="/platform/capabilities"
              target="_blank"
              rel="noopener noreferrer"
              >管理能力（新标签页）</RouterLink
            >
            <ElButton
              v-if="allowed('foundation.system_agents.read')"
              link
              :disabled="action.busy.value"
              :loading="toolOptions.loading.value"
              @click="refreshToolOptions"
              >刷新工具选项</ElButton
            >
          </p>
        </ElFormItem>
        <p class="admin-muted" role="status">
          平台工具：{{
            toolLoading ? '正在读取当前发布配置…' : form.provider_ref || '选择工具后自动读取'
          }}
        </p>
        <ElButton
          v-if="form.tool_definition_id && !toolError"
          link
          :disabled="toolLoading || action.busy.value"
          @click="resolveTool(form.tool_definition_id)"
          >重新读取工具发布配置</ElButton
        >
        <ElFormItem label="环境">
          <ElSelect
            v-described-by="formTipId + '-field-3'"
            :aria-describedby="formTipId + '-field-3'"
            v-model="form.environment"
          >
            <ElOption label="测试" value="TEST" />
            <ElOption label="生产" value="PRODUCTION" />
          </ElSelect>

          <FieldTip :id="formTipId + '-field-3'">选择部署对应的运行环境。</FieldTip>
        </ElFormItem>
        <ElFormItem label="启用">
          <ElSwitch
            v-described-by="formTipId + '-field-4'"
            :aria-describedby="formTipId + '-field-4'"
            v-model="form.active"
          />

          <FieldTip :id="formTipId + '-field-4'">发布后控制该部署是否可用于新执行。</FieldTip>
        </ElFormItem>
      </ElForm>
      <p class="admin-field-help">保存仅更新草稿；发布后新执行才会采用此部署配置。</p>
      <template #footer>
        <ElButton
          :disabled="
            action.busy.value || (!!unknown && !unknownStored) || (!!storageError && !!archived)
          "
          @click="requestDrawerClose()"
          >{{ unknown ? '保留未知结果并关闭' : saved ? '关闭' : '取消' }}</ElButton
        >
        <ElButton
          v-permission="[
            editing
              ? 'foundation.system_agent_tools.update'
              : 'foundation.system_agent_tools.create'
          ]"
          type="primary"
          :loading="action.busy.value"
          :disabled="toolLoading || !!toolError || !selectedToolName || !!unknown || !!storageError"
          native-type="submit"
          form="tool-deployment-form"
          >保存草稿</ElButton
        >
        <ElButton
          v-if="allowed('foundation.system_agent_tools.publish')"
          type="primary"
          :loading="action.busy.value"
          :disabled="
            !!unknown ||
            !!storageError ||
            toolLoading ||
            !!toolError ||
            !selectedToolName ||
            (!saved &&
              !allowed(
                editing
                  ? 'foundation.system_agent_tools.update'
                  : 'foundation.system_agent_tools.create'
              )) ||
            (!!saved &&
              JSON.stringify(form) !== baseline &&
              !allowed('foundation.system_agent_tools.update'))
          "
          @click="saved ? continuePublish() : save(true)"
          >{{ saved ? '继续发布' : '保存并继续发布' }}</ElButton
        >
      </template>
    </ElDrawer>
    <ElDrawer
      v-model="historyOpen"
      :title="'工具部署发布历史 · ' + historyDeployment?.name"
      size="760px"
      append-to-body
    >
      <FeedbackNotice
        v-if="action.error.value"
        :title="action.error.value"
        type="error"
        :closable="false"
      />
      <ElEmpty v-if="!publications.length" description="尚无发布记录" />
      <ElCard class="admin-card" v-for="publication in publications" :key="publication.revision">
        <h3>发布修订 {{ publication.revision }} · {{ publication.name }}</h3>
        <ElTag v-if="publication.revision === historyDeployment?.published_revision"
          >当前发布</ElTag
        >
        <ElCollapse
          ><ElCollapseItem title="查看配置">
            <pre class="readable">{{ JSON.stringify(publication, null, 2) }}</pre>
          </ElCollapseItem></ElCollapse
        >
        <ElButton
          v-permission="['foundation.system_agent_tools.update']"
          :disabled="action.busy.value || !!unknown || !!storageError"
          @click="restorePublication(publication)"
          >恢复为草稿</ElButton
        >
      </ElCard>
    </ElDrawer>
  </component>
</template>
