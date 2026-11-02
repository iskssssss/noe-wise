<script setup lang="ts">
import FeedbackNotice from '@skillforge/shared/feedback/FeedbackNotice.vue'
import { computed, onMounted, reactive, ref } from 'vue'
import {
  AdminPage,
  guardFormEnter,
  useDrawerClose,
  useResource,
  useTask,
  useUnsavedChanges
} from '@skillforge/shared/admin'
import { confirm, message } from '@skillforge/shared/feedback'
import { HttpClientError } from '@skillforge/shared/utils/http'
import { acceptUnhighlightedCreate } from '@skillforge/shared/admin/creatableOption'
import type {
  AgentTemplateDetail,
  AgentTemplateDefinition,
  AgentTemplateItem,
  AgentDefinitionImpact,
  AgentTemplateValidation,
  AgentDefinitionSaveResolution
} from '@skillforge/shared/integration/agentDefinitions'
import { api, getSessionId } from '../../api'
import { useAuthStore } from '../../stores/auth'
import { useManagementIdentity } from '../../composables/managementIdentity'
import { useManagementCommand } from '../../composables/managementCommand'
const identity = useManagementIdentity(),
  page = ref(1),
  search = ref('')
const auth = useAuthStore()
type PendingSave = {
  operation: 'create' | 'update'
  key: string
  definitionId?: string
  expectedRevision?: number
  fenceAttempted?: boolean
}
const pendingSave = ref<PendingSave>()
const saveStorageError = ref('')
const saveSession = getSessionId()
const saveOwner = JSON.stringify([auth.user?.tenant_id, auth.user?.sub])
const saveKey = `noewise:institution-definition-save:${saveOwner}`
let pendingSnapshot = ''
function currentSave() {
  return (
    identity.current() &&
    !!auth.user?.tenant_id &&
    !!auth.user?.sub &&
    !!saveSession &&
    getSessionId() === saveSession &&
    JSON.stringify([auth.user.tenant_id, auth.user.sub]) === saveOwner
  )
}
function clearSave() {
  sessionStorage.removeItem(saveKey)
  pendingSave.value = undefined
  saveStorageError.value = ''
}
defineProps<{ tenantId?: string; embedded?: boolean }>()
function changePage(value: number) {
  page.value = value
  void list.reload()
}
function query() {
  page.value = 1
  void list.reload()
}
const list = useResource(async (signal) => {
  const value = await api.agentDefinitions.list(
    { page: page.value, limit: 20, name: search.value.trim() },
    { signal, silent: true }
  )
  if (!identity.current()) throw Error('账号已变化，请重新进入页面')
  return value
})
const options = useResource(async () => {
  const [models, capabilities] = await Promise.all([
    api.settings.availableModels(),
    api.capabilities.listDefinitions({ status: 'PUBLISHED' })
  ])
  if (!identity.current()) throw Error('账号已变化，请重新进入页面')
  return {
    models: models.filter((m) => m.kind === 'LLM'),
    capabilities: capabilities.filter(
      (c) => c.defaultVersionId && (c.capabilityType === 'SKILL' || c.capabilityType === 'TOOL')
    )
  }
})
const write = useManagementCommand()
const commandPending = computed(
  () => !!write.pending.value || !!pendingSave.value || !!saveStorageError.value
)
const action = useTask(),
  open = ref(false),
  item = ref<AgentTemplateDetail>(),
  history = ref<AgentTemplateDetail[]>([]),
  historyRevision = ref('')
const validation = ref<AgentTemplateValidation>(),
  impact = ref<AgentDefinitionImpact>()
const refreshRequired = ref(false)
const saveRevisionConflict = ref(false)
const saveRejectedConflict = ref(false)
function blank(): AgentTemplateDefinition {
  return {
    system_prompt: '',
    role_description: '',
    model_config_id: null,
    execution_config: {
      max_business_model_calls: null,
      max_tool_calls: null,
      max_knowledge_calls: null,
      max_total_tokens: null,
      max_wall_time_seconds: null
    },
    capabilities: [],
    resource_slots: [],
    delegation_slots: []
  }
}
const definition = ref<AgentTemplateDefinition>(blank()),
  form = reactive({ name: '', description: '', category: '', tags: [] as string[] })
function onTagEnter(event: KeyboardEvent) {
  acceptUnhighlightedCreate(
    event,
    canEdit.value && !action.busy.value && !commandPending.value,
    [],
    (value) => {
      if (!form.tags.includes(value)) form.tags.push(value)
    }
  )
}
const baseline = ref('')
const snapshot = () => JSON.stringify({ form, definition: definition.value })
const dirty = computed(() => snapshot() !== baseline.value)
useUnsavedChanges({
  dirty: () => open.value && dirty.value,
  busy: () => action.busy.value || !!pendingSave.value
})
const previewSnapshot = ref('')
const previewCurrent = computed(
  () => !!previewSnapshot.value && previewSnapshot.value === snapshot()
)
const canEdit = computed(() =>
  identity.allowed(item.value ? 'tenant.agent_definitions.edit' : 'tenant.agent_definitions.create')
)
const close = useDrawerClose({
  open: () => open.value,
  busy: () => action.busy.value || !!commandPending.value,
  dirty: () => dirty.value,
  close: () => {
    open.value = false
  }
})
const budgets = [
  ['max_business_model_calls', '业务模型调用次数'],
  ['max_tool_calls', '最大工具调用'],
  ['max_knowledge_calls', '最大知识检索'],
  ['max_total_tokens', '累计总 Tokens'],
  ['max_wall_time_seconds', '活跃执行时长（秒）']
] as const
function accept(value: AgentTemplateDetail) {
  item.value = value
  Object.assign(form, {
    name: value.name,
    description: value.description ?? '',
    category: value.category ?? '',
    tags: [...(value.tags ?? [])]
  })
  definition.value = JSON.parse(JSON.stringify(value.definition))
  baseline.value = snapshot()
  validation.value = undefined
  impact.value = undefined
  previewSnapshot.value = ''
  refreshRequired.value = false
  saveRevisionConflict.value = false
  saveRejectedConflict.value = false
}
function begin(row?: AgentTemplateItem) {
  if (action.busy.value || commandPending.value) return
  void action.run(async () => {
    action.error.value = ''
    history.value = []
    historyRevision.value = ''
    if (row) {
      const value = await api.agentDefinitions.detail(row.definition_id, { silent: true })
      identity.requirePermission('tenant.agent_definitions.read')
      accept(value)
    } else {
      identity.requirePermission('tenant.agent_definitions.create')
      item.value = undefined
      definition.value = blank()
      Object.assign(form, { name: '', description: '', category: '', tags: [] })
      baseline.value = snapshot()
      validation.value = undefined
      impact.value = undefined
      previewSnapshot.value = ''
      refreshRequired.value = false
    }
    open.value = true
    if (canEdit.value) void options.reload()
  })
}
function selectCapability(index: number) {
  const mount = definition.value.capabilities[index]!
  const selected = options.data.value?.capabilities.find(
    (c) => c.id === mount.capability_definition_id
  )
  if (!selected) return
  mount.capability_type = selected.capabilityType as 'SKILL' | 'TOOL'
  mount.capability_version_id = selected.defaultVersionId ?? null
  mount.platform_tool_name = null
  mount.skill_config =
    mount.capability_type === 'SKILL' ? { activation_mode: 'ON_DEMAND', token_budget: null } : null
}
async function executeSave(
  operation: PendingSave['operation'],
  targetId: string | undefined,
  expectedRevision: number | undefined,
  send: (key: string) => Promise<AgentTemplateItem>,
  acceptResult: (value: AgentTemplateItem) => Promise<void>,
  permission: string
) {
  if (commandPending.value) throw Error('原保存结果尚未确认，请先核对')
  identity.requirePermission(permission)
  if (!currentSave()) throw Error('无法确认当前账号会话，未发送保存请求')
  const record: PendingSave = {
    operation,
    key: crypto.randomUUID(),
    ...(targetId ? { definitionId: targetId, expectedRevision } : {})
  }
  try {
    sessionStorage.setItem(saveKey, JSON.stringify(record))
  } catch {
    saveStorageError.value = '无法保存请求标识，未发送保存请求。'
    return
  }
  pendingSave.value = record
  pendingSnapshot = snapshot()
  let result: AgentTemplateItem
  try {
    result = await send(record.key)
  } catch (reason) {
    if (
      operation === 'update' &&
      reason instanceof HttpClientError &&
      reason.code === 'AGENT_DEFINITION_REVISION_CONFLICT' &&
      currentSave()
    ) {
      try {
        clearSave()
        refreshRequired.value = true
        saveRejectedConflict.value = true
      } catch {
        saveStorageError.value = '无法清除原保存记录，请恢复浏览器存储后核对。'
      }
    }
    throw reason
  }
  if (!currentSave() || !identity.allowed(permission)) return
  if (
    !result?.definition_id ||
    !Number.isSafeInteger(result.draft_revision) ||
    result.draft_revision < 1 ||
    (targetId && targetId !== result.definition_id)
  )
    return
  try {
    clearSave()
  } catch {
    saveStorageError.value = '无法清除原保存记录，请恢复浏览器存储后核对。'
    return
  }
  await acceptResult(result)
}
async function checkOriginalSave() {
  const operation = pendingSave.value
  if (!operation || !currentSave()) return
  identity.requirePermission('tenant.agent_definitions.read')
  const resolution = await api.agentDefinitions.saveResolution(
    operation.operation,
    operation.key,
    operation.definitionId,
    { silent: true }
  )
  if (
    !currentSave() ||
    !identity.allowed('tenant.agent_definitions.read') ||
    pendingSave.value !== operation
  )
    return
  await acceptResolution(operation, resolution)
}
async function acceptResolution(
  operation: PendingSave,
  resolution: AgentDefinitionSaveResolution | null
) {
  if (
    !currentSave() ||
    !identity.allowed('tenant.agent_definitions.read') ||
    pendingSave.value !== operation
  )
    return
  if (resolution?.state === 'FENCED' && resolution.receipt === null) {
    let detail: AgentTemplateDetail | undefined
    if (operation.operation === 'update') {
      detail = await api.agentDefinitions.detail(operation.definitionId!, { silent: true })
      if (
        !currentSave() ||
        !identity.allowed('tenant.agent_definitions.read') ||
        pendingSave.value !== operation
      )
        return
      if (
        detail.definition_id !== operation.definitionId ||
        !Number.isSafeInteger(detail.draft_revision)
      )
        return
    }
    try {
      clearSave()
    } catch {
      saveStorageError.value = '无法清除原保存记录，请恢复浏览器存储后核对。'
      return
    }
    if (
      detail &&
      (operation.expectedRevision === undefined ||
        detail.draft_revision !== operation.expectedRevision)
    ) {
      if (!item.value) item.value = detail
      refreshRequired.value = true
      saveRejectedConflict.value = true
      open.value = true
    } else if (detail && !item.value) {
      accept(detail)
      open.value = true
    }
    message.success(
      pendingSnapshot
        ? '原保存已结束，未写入；当前编辑内容已保留。'
        : '原保存已结束且未写入；刷新前的未保存内容无法恢复。'
    )
    return
  }
  const receipt = resolution?.state === 'COMMITTED' ? resolution.receipt : null
  if (
    !receipt ||
    !receipt.definition_id ||
    !Number.isSafeInteger(receipt.draft_revision) ||
    receipt.draft_revision < 1 ||
    (operation.definitionId && operation.definitionId !== receipt.definition_id)
  ) {
    message.warning('尚未查到匹配的原保存回执，结果仍未确认')
    return
  }
  const detail = await api.agentDefinitions.detail(receipt.definition_id, { silent: true })
  if (
    !currentSave() ||
    !identity.allowed('tenant.agent_definitions.read') ||
    pendingSave.value !== operation
  )
    return
  if (
    detail.definition_id !== receipt.definition_id ||
    !Number.isSafeInteger(detail.draft_revision) ||
    detail.draft_revision < receipt.draft_revision
  ) {
    message.warning('原保存已确认，但尚未读取到对应草稿修订，请稍后核对')
    return
  }
  if (detail.draft_revision > receipt.draft_revision) {
    try {
      clearSave()
    } catch {
      saveStorageError.value = '无法清除原保存记录，请恢复浏览器存储后核对。'
      return
    }
    if (!item.value) item.value = { ...receipt, definition: definition.value }
    refreshRequired.value = true
    saveRevisionConflict.value = true
    open.value = true
    message.warning('原保存已确认，但草稿已有更新修订；请保留当前内容后重新读取。')
    return
  }
  if (open.value && pendingSnapshot && snapshot() !== pendingSnapshot) {
    message.warning('原保存已确认，当前编辑内容已变化，请保留内容后核对')
    return
  }
  try {
    clearSave()
  } catch {
    saveStorageError.value = '无法清除原保存记录，请恢复浏览器存储后核对。'
    return
  }
  accept(detail)
  open.value = true
  void list.reload()
  message.success('原保存已确认')
}
async function fenceOriginalSave() {
  const operation = pendingSave.value
  if (!operation || !currentSave()) return
  identity.requirePermission('tenant.agent_definitions.read')
  if (
    !(await confirm({
      title: '结束未确认的保存',
      content:
        '服务端将阻止原请求之后生效；若原请求已经保存，将显示原结果。当前页面中的编辑内容会保留。',
      confirmText: '结束原保存',
      cancelText: '继续核对'
    }))
  )
    return
  if (
    !currentSave() ||
    pendingSave.value !== operation ||
    !identity.allowed('tenant.agent_definitions.read')
  )
    return
  const attempted = { ...operation, fenceAttempted: true }
  try {
    sessionStorage.setItem(saveKey, JSON.stringify(attempted))
  } catch {
    saveStorageError.value = '无法保存结束请求状态，未发送结束请求。'
    return
  }
  pendingSave.value = attempted
  const currentAttempt = pendingSave.value
  const resolution = await api.agentDefinitions.fenceSave(
    attempted.operation,
    attempted.key,
    attempted.definitionId
  )
  await acceptResolution(currentAttempt, resolution)
}
function save(validateAfterSave = false) {
  if (refreshRequired.value || commandPending.value) return
  void action.run(async () => {
    const permission = item.value
      ? 'tenant.agent_definitions.edit'
      : 'tenant.agent_definitions.create'
    if (!form.name.trim() || !definition.value.system_prompt.trim())
      throw Error('请填写定义名称与系统提示词')
    const payload = {
      ...form,
      name: form.name.trim(),
      definition: JSON.parse(JSON.stringify(definition.value)) as AgentTemplateDefinition,
      distribution_policy: 'AUTHORIZED_DEPLOY' as const
    }
    identity.requirePermission(permission)
    const target = item.value
    await executeSave(
      target ? 'update' : 'create',
      target?.definition_id,
      target?.draft_revision,
      (key) =>
        target
          ? api.agentDefinitions.update(target.definition_id, target.draft_revision, payload, key)
          : api.agentDefinitions.create(payload, key),
      async (result) => {
        accept({ ...result, definition: payload.definition })
        refreshRequired.value = true
        if (!validateAfterSave) message.success('共享定义草稿已保存')
        void list.reload()
        if (!identity.allowed('tenant.agent_definitions.read')) {
          message.warning('草稿已保存，请有查看权限的管理员重新读取后继续维护')
          return
        }
        try {
          const saved = await api.agentDefinitions.detail(result.definition_id, { silent: true })
          identity.requirePermission('tenant.agent_definitions.read')
          if (
            saved.definition_id !== result.definition_id ||
            saved.draft_revision !== result.draft_revision
          )
            throw Error('草稿已被其他操作更新')
          accept(saved)
        } catch {
          if (identity.current())
            message.warning('草稿已保存，但未能读取保存结果；请重新读取，不要重复保存')
          return
        }
        if (validateAfterSave) {
          if (
            !identity.allowed('tenant.agent_definitions.validate') ||
            !identity.allowed('tenant.agent_definitions.read')
          ) {
            if (identity.current()) message.warning('草稿已保存，请由有权限的管理员校验。')
            return
          }
          try {
            await performPreview()
            if (previewCurrent.value && validation.value?.valid && impact.value)
              message.success('草稿已保存并校验，发布影响已更新')
            else message.warning('草稿已保存，但校验未通过或影响不可用，请检查结果。')
          } catch (reason) {
            if (reason instanceof HttpClientError) reason.claimFeedback()
            if (identity.current()) message.warning('草稿已保存，但校验未完成，请重新校验。')
          }
        }
      },
      permission
    )
  })
}
function reload() {
  void action.run(async () => {
    if (!item.value) return
    const id = item.value.definition_id
    if (
      dirty.value &&
      !(await confirm({
        title: '重新读取草稿',
        content: '将丢弃未保存的字段内容并读取最新修订。',
        confirmText: '丢弃并重新读取',
        cancelText: '继续编辑'
      }))
    )
      return
    identity.requirePermission('tenant.agent_definitions.read')
    const value = await api.agentDefinitions.detail(id, { silent: true })
    identity.requirePermission('tenant.agent_definitions.read')
    accept(value)
  })
}
function preview() {
  if (refreshRequired.value) return
  void action.run(performPreview)
}
async function performPreview() {
  if (!item.value) return
  const target = { ...item.value },
    frozen = snapshot(),
    candidate = JSON.parse(JSON.stringify(definition.value)) as AgentTemplateDefinition
  identity.requirePermission('tenant.agent_definitions.validate')
  const result = dirty.value
    ? await api.agentDefinitions.previewContent(
        target.definition_id,
        target.draft_revision,
        candidate,
        { silent: true }
      )
    : await api.agentDefinitions.previewSaved(target.definition_id, target.draft_revision, {
        silent: true
      })
  identity.requirePermission('tenant.agent_definitions.validate')
  if (result.impact) identity.requirePermission('tenant.agent_definitions.read')
  if (
    snapshot() !== frozen ||
    item.value?.definition_id !== target.definition_id ||
    item.value.draft_revision !== target.draft_revision
  )
    return
  validation.value = result.validation
  impact.value = result.impact ?? undefined
  previewSnapshot.value = frozen
}
function publish() {
  void action.run(async () => {
    if (
      !item.value ||
      refreshRequired.value ||
      dirty.value ||
      !previewCurrent.value ||
      !validation.value?.valid ||
      impact.value?.draft_revision !== item.value.draft_revision
    )
      throw Error('请先保存并校验当前草稿，读取其影响预览')
    const target = { ...item.value },
      frozen = snapshot(),
      previewed = impact.value
    if (
      !(await confirm({
        title: '发布机构共享定义',
        content: `发布草稿修订 ${target.draft_revision}，当前可见 ${previewed.visible_deployment_count} 个部署将跟随此发布。存在缺项的部署会转为待完善；旧任务保留冻结版本。`,
        confirmText: '发布此修订',
        cancelText: '继续编辑'
      }))
    )
      return
    identity.requirePermission('tenant.agent_definitions.publish')
    if (snapshot() !== frozen) throw Error('草稿内容已变化，请重新校验预览')
    await write.execute(
      (key) => api.agentDefinitions.publish(target.definition_id, target.draft_revision, key),
      (result) => {
        item.value = { ...result, definition: definition.value }
        baseline.value = snapshot()
        impact.value = undefined
        validation.value = undefined
        previewSnapshot.value = ''
        message.success('机构共享定义已发布')
        void list.reload()
      },
      () => identity.requirePermission('tenant.agent_definitions.publish')
    )
  })
}
function loadHistory() {
  void action.run(async () => {
    if (!item.value) return
    const values = await api.agentDefinitions.history(item.value.definition_id, { silent: true })
    identity.requirePermission('tenant.agent_definitions.read')
    history.value = values
  })
}
function restore() {
  if (refreshRequired.value) return
  void action.run(async () => {
    if (!item.value || !historyRevision.value) throw Error('请选择发布历史')
    const target = { ...item.value },
      revision = historyRevision.value
    if (
      !(await confirm({
        title: '从发布历史恢复草稿',
        content: '所选历史内容将成为新的工作草稿，当前未保存修改会丢弃。恢复不会直接发布。',
        confirmText: '恢复为草稿',
        cancelText: '取消'
      }))
    )
      return
    identity.requirePermission('tenant.agent_definitions.restore')
    await write.execute(
      (key) =>
        api.agentDefinitions.restore(target.definition_id, target.draft_revision, revision, key),
      async (result) => {
        const restored = history.value.find((v) => v.current_published_revision === revision)
        if (restored) accept({ ...result, definition: restored.definition })
        else {
          item.value = { ...result, definition: definition.value }
          baseline.value = snapshot()
          impact.value = undefined
          validation.value = undefined
        }
        message.success('已恢复为工作草稿')
        void list.reload()
        try {
          const detail = await api.agentDefinitions.detail(target.definition_id, { silent: true })
          identity.requirePermission('tenant.agent_definitions.read')
          accept(detail)
        } catch {
          message.warning('恢复已成功，草稿刷新失败，请主动重新读取后继续编辑')
        }
      },
      () => identity.requirePermission('tenant.agent_definitions.restore')
    )
  })
}
function deprecate() {
  if (refreshRequired.value) return
  void action.run(async () => {
    if (!item.value) return
    const target = { ...item.value }
    if (
      !(await confirm({
        title: '停用共享定义',
        content: '停用后按服务端当前可用性阻止新的部署与执行。确认停用？',
        confirmText: '停用定义',
        cancelText: '取消'
      }))
    )
      return
    identity.requirePermission('tenant.agent_definitions.deprecate')
    await write.execute(
      (key) => api.agentDefinitions.deprecate(target.definition_id, target.draft_revision, key),
      (result) => {
        item.value = { ...result, definition: definition.value }
        message.success('共享定义已停用')
        void list.reload()
      },
      () => identity.requirePermission('tenant.agent_definitions.deprecate')
    )
  })
}
onMounted(() => {
  void list.reload()
  if (!currentSave()) return
  try {
    const raw = sessionStorage.getItem(saveKey)
    if (!raw) return
    const value: PendingSave = JSON.parse(raw)
    if (
      !value ||
      !['create', 'update'].includes(value.operation) ||
      !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value.key) ||
      (value.operation === 'update' && !value.definitionId) ||
      (value.operation === 'create' && value.definitionId) ||
      (value.expectedRevision !== undefined &&
        (!Number.isSafeInteger(value.expectedRevision) || value.expectedRevision < 1)) ||
      (value.fenceAttempted !== undefined && typeof value.fenceAttempted !== 'boolean')
    )
      throw Error('invalid')
    pendingSave.value = value
    if (identity.allowed('tenant.agent_definitions.read')) void action.run(checkOriginalSave)
  } catch {
    saveStorageError.value = '无法读取原保存记录，请恢复浏览器存储后重新进入。'
  }
})
</script>
<template>
  <AdminPage
    :embedded="embedded"
    title="机构共享定义"
    description="维护本机构拥有的共享源。保存工作草稿、查看发布影响后发布；授权及下级部署分别管理。"
  >
    <template #extra
      ><ElButton
        v-if="identity.allowed('tenant.agent_definitions.create')"
        type="primary"
        :disabled="action.busy.value || commandPending"
        @click="begin()"
        >新建共享定义</ElButton
      ></template
    >
    <section v-if="!open && (pendingSave || saveStorageError)" role="status">
      原保存结果尚未确认，已暂停新的写入。{{ saveStorageError }}
      <ElButton
        v-if="pendingSave && identity.allowed('tenant.agent_definitions.read')"
        :loading="action.busy.value"
        @click="action.run(checkOriginalSave)"
        >核对原保存</ElButton
      >
      <ElButton
        v-if="pendingSave && identity.allowed('tenant.agent_definitions.read')"
        :disabled="action.busy.value"
        @click="action.run(fenceOriginalSave)"
        >结束未确认的保存</ElButton
      >
    </section>
    <FeedbackNotice
      v-if="list.error.value || (!open && action.error.value)"
      :title="action.error.value || '刷新失败，保留上次内容：' + list.error.value"
      type="error"
      :closable="false"
    />
    <div class="admin-card admin-toolbar">
      <div>
        <strong>编写定义 · 校验影响 · 发布共享</strong>
        <p class="admin-muted">可预览当前编辑内容对现有机构实例的影响，保存并确认后发布。</p>
      </div>
      <ElTag>{{ list.data.value?.total ?? 0 }} 个自有定义</ElTag>
    </div>
    <ElForm class="admin-filters" inline @submit.prevent="query"
      ><ElFormItem label="名称筛选"><ElInput v-model="search" clearable /></ElFormItem
      ><ElButton native-type="submit" :loading="list.loading.value">查询</ElButton></ElForm
    >
    <ElTable
      :data="list.data.value?.items ?? []"
      table-layout="auto"
      row-key="definition_id"
      v-loading="list.loading.value"
      empty-text="暂无本机构自有共享定义"
    >
      <ElTableColumn prop="name" label="名称" /><ElTableColumn
        prop="definition_id"
        label="定义标识"
      /><ElTableColumn prop="draft_revision" label="草稿修订" /><ElTableColumn
        prop="current_published_revision"
        label="当前发布"
      /><ElTableColumn prop="status" label="状态" /><ElTableColumn label="草稿"
        ><template #default="{ row }">{{
          row.has_unpublished_changes ? '有未发布变更' : '与发布一致'
        }}</template></ElTableColumn
      ><ElTableColumn label="操作"
        ><template #default="{ row }"
          ><ElButton
            link
            :disabled="action.busy.value || commandPending"
            @click="begin(row as AgentTemplateItem)"
            >查看与维护</ElButton
          ></template
        ></ElTableColumn
      >
    </ElTable>
    <ElPagination
      :current-page="page"
      :page-size="20"
      :total="list.data.value?.total ?? 0"
      layout="prev, pager, next, total"
      @current-change="changePage"
    />
    <ElDrawer
      v-model="open"
      :title="item ? '维护机构共享定义' : '新建机构共享定义'"
      size="820px"
      append-to-body
      :before-close="close.requestClose"
      :close-on-click-modal="false"
      :show-close="!action.busy.value && !commandPending"
      :close-on-press-escape="!action.busy.value && !commandPending"
    >
      <FeedbackNotice
        v-if="action.error.value"
        :title="action.error.value"
        type="error"
        :closable="false"
      />
      <section v-if="pendingSave || saveStorageError" role="status">
        原保存结果尚未确认，已暂停新的写入；当前草稿已保留。{{ saveStorageError }}
        <ElButton
          v-if="pendingSave && identity.allowed('tenant.agent_definitions.read')"
          :loading="action.busy.value"
          @click="action.run(checkOriginalSave)"
          >核对原保存</ElButton
        >
        <ElButton
          v-if="pendingSave && identity.allowed('tenant.agent_definitions.read')"
          :disabled="action.busy.value"
          @click="action.run(fenceOriginalSave)"
          >结束未确认的保存</ElButton
        >
      </section>
      <p v-if="item">
        定义 {{ item.definition_id }} · 草稿修订 {{ item.draft_revision }} · 当前发布
        {{ item.current_published_revision ?? '尚未发布' }}
      </p>
      <p v-if="saveRevisionConflict" role="status">
        原保存已确认，草稿已有更新修订。当前编辑内容已保留；请确认后重新读取，继续维护最新修订。
      </p>
      <p v-else-if="saveRejectedConflict" role="status">
        保存未受理，草稿修订已变化。当前编辑内容已保留；请确认后重新读取最新修订。
      </p>
      <p v-else-if="refreshRequired" role="status">草稿已保存，需重新读取保存结果后继续维护。</p>
      <ElButton
        v-if="item && identity.allowed('tenant.agent_definitions.read')"
        :disabled="action.busy.value || commandPending"
        @click="reload"
        >重新读取草稿</ElButton
      >
      <FeedbackNotice
        v-if="options.error.value"
        :title="'模型与能力目录读取失败，现有字段保留：' + options.error.value"
        type="warning"
        :closable="false"
      /><ElButton v-if="options.error.value" @click="options.reload">重试目录</ElButton>
      <ElAlert
        v-if="write.pending.value"
        title="本次定义操作的结果尚未确认，原内容已保留，后续写入已暂停。请点击“核对原请求”确认结果，避免重复操作。"
        type="warning"
        :closable="false"
      /><ElButton
        v-if="write.pending.value"
        :loading="action.busy.value"
        @click="action.run(write.reconcile)"
        >核对原请求</ElButton
      ><ElForm
        :model="form"
        label-position="top"
        :disabled="action.busy.value || commandPending || refreshRequired || !canEdit"
        @submit.prevent="save()"
        @keydown.capture="guardFormEnter"
      >
        <h3>基本信息</h3>
        <ElFormItem label="定义名称" required
          ><ElInput v-model="form.name" maxlength="200" /></ElFormItem
        ><ElFormItem label="用途说明"
          ><ElInput v-model="form.description" type="textarea" :rows="2" /></ElFormItem
        ><ElFormItem label="分类"><ElInput v-model="form.category" /></ElFormItem
        ><ElFormItem label="标签"
          ><ElSelect
            v-model="form.tags"
            @keydown.enter.capture="onTagEnter"
            multiple
            filterable
            allow-create
            default-first-option
        /></ElFormItem>
        <ElFormItem label="角色描述"
          ><ElInput v-model="definition.role_description" type="textarea" :rows="2" /></ElFormItem
        ><ElFormItem label="系统提示词" required
          ><ElInput v-model="definition.system_prompt" type="textarea" :rows="6"
        /></ElFormItem>
        <ElFormItem label="执行模型"
          ><ElSelect
            v-model="definition.model_config_id"
            clearable
            filterable
            placeholder="继承机构默认模型"
            :loading="options.loading.value"
            ><ElOption
              v-for="model in options.data.value?.models ?? []"
              :key="model.id"
              :value="model.id"
              :label="model.name" /></ElSelect
        ></ElFormItem>
        <ElCollapse
          ><ElCollapseItem title="执行预算（可选）" name="budget">
            <p class="admin-field-help">留空继承平台默认预算。</p>
            <ElFormItem v-for="[key, label] in budgets" :key="key" :label="label"
              ><ElInputNumber v-model="definition.execution_config[key]" :min="1" :precision="0"
            /></ElFormItem> </ElCollapseItem
        ></ElCollapse>
        <h3>基础能力</h3>
        <p class="admin-field-help">选择能力后，保存时自动采用当前发布配置。</p>
        <div v-for="(mount, index) in definition.capabilities" :key="index" class="field-block">
          <ElFormItem label="已发布能力"
            ><ElSelect
              v-model="mount.capability_definition_id"
              filterable
              @change="selectCapability(index)"
              ><ElOption
                v-for="cap in options.data.value?.capabilities ?? []"
                :key="cap.id"
                :value="cap.id"
                :label="cap.name" /></ElSelect
          ></ElFormItem>
          <ElFormItem label="优先级"
            ><ElInputNumber v-model="mount.priority" :precision="0" /></ElFormItem
          ><ElFormItem v-if="mount.skill_config" label="技能 Token 预算"
            ><ElInputNumber
              v-model="mount.skill_config.token_budget"
              :min="1"
              :precision="0" /></ElFormItem
          ><ElButton type="danger" link @click="definition.capabilities.splice(index, 1)"
            >删除基础能力</ElButton
          >
        </div>
        <ElButton
          @click="
            definition.capabilities.push({
              capability_type: 'SKILL',
              capability_definition_id: '',
              capability_version_id: null,
              skill_config: { activation_mode: 'ON_DEMAND', token_budget: null },
              platform_tool_name: null,
              priority: 0
            })
          "
          >新增基础能力</ElButton
        >
        <ElCollapse
          ><ElCollapseItem
            :title="'机构资源槽位（' + definition.resource_slots.length + '）'"
            name="resources"
          >
            <div
              v-for="(slot, index) in definition.resource_slots"
              :key="index"
              class="field-block admin-form-grid"
            >
              <ElFormItem label="资源槽键"><ElInput v-model="slot.key" maxlength="64" /></ElFormItem
              ><ElFormItem label="资源槽名称"><ElInput v-model="slot.label" /></ElFormItem
              ><ElFormItem label="资源槽说明"><ElInput v-model="slot.description" /></ElFormItem
              ><ElFormItem label="资源类型"
                ><ElSelect v-model="slot.capability_type"
                  ><ElOption value="TOOL" label="工具" /><ElOption
                    value="WORKFLOW"
                    label="工作流" /><ElOption
                    value="KNOWLEDGE_SOURCE"
                    label="知识源" /></ElSelect></ElFormItem
              ><ElButton type="danger" link @click="definition.resource_slots.splice(index, 1)"
                >删除资源槽</ElButton
              >
            </div>
            <ElButton
              @click="
                definition.resource_slots.push({
                  key: '',
                  label: '',
                  description: '',
                  capability_type: 'TOOL'
                })
              "
              >新增资源槽</ElButton
            > </ElCollapseItem
          ><ElCollapseItem
            :title="'协作槽位（' + definition.delegation_slots.length + '）'"
            name="delegates"
          >
            <div
              v-for="(slot, index) in definition.delegation_slots"
              :key="index"
              class="field-block admin-form-grid"
            >
              <ElFormItem label="协作槽键"><ElInput v-model="slot.key" maxlength="64" /></ElFormItem
              ><ElFormItem label="协作槽名称"><ElInput v-model="slot.label" /></ElFormItem
              ><ElFormItem label="协作槽说明"><ElInput v-model="slot.description" /></ElFormItem
              ><ElButton type="danger" link @click="definition.delegation_slots.splice(index, 1)"
                >删除协作槽</ElButton
              >
            </div>
            <ElButton
              @click="definition.delegation_slots.push({ key: '', label: '', description: '' })"
              >新增协作槽</ElButton
            >
          </ElCollapseItem></ElCollapse
        >
      </ElForm>
      <section v-if="item">
        <h3>校验与发布影响</h3>
        <ElButton
          v-if="identity.allowed('tenant.agent_definitions.validate')"
          :disabled="action.busy.value || commandPending || refreshRequired"
          @click="preview"
          >校验并预览影响</ElButton
        ><ElAlert
          v-if="validation && previewCurrent"
          :title="validation.valid ? '定义配置校验通过' : '定义配置校验未通过'"
          :type="validation.valid ? 'success' : 'warning'"
          :closable="false"
        />
        <ul v-if="previewCurrent && validation?.issues.length">
          <li v-for="issue in validation.issues" :key="issue.path + issue.code">
            {{ issue.path }}：{{ issue.message }}
          </li>
        </ul>
        <div v-if="impact && previewCurrent">
          <p v-if="dirty">当前为未保存内容预览；保存后需重新校验确认才能发布。</p>
          <p>
            新增参数 {{ impact.changes.added_parameter_keys?.join('、') || '无' }}；移除参数
            {{ impact.changes.removed_parameter_keys?.join('、') || '无' }}
          </p>
          <p>
            预览草稿修订 {{ impact.draft_revision }} · 可见部署
            {{ impact.visible_deployment_count }} 个
          </p>
          <p>
            新增资源槽
            {{
              impact.changes.added_resource_slots.map((s) => s.key).join('、') || '无'
            }}；变更资源槽
            {{
              impact.changes.changed_resource_slots.map((s) => s.key).join('、') || '无'
            }}；移除资源槽 {{ impact.changes.removed_resource_slot_keys.join('、') || '无' }}
          </p>
          <p>
            新增协作槽
            {{
              impact.changes.added_delegation_slots.map((s) => s.key).join('、') || '无'
            }}；移除协作槽 {{ impact.changes.removed_delegation_slot_keys.join('、') || '无' }}
          </p>
          <ElTable :data="impact.deployments" table-layout="auto"
            ><ElTableColumn prop="tenant_id" label="机构" /><ElTableColumn
              prop="deployment_id"
              label="部署"
            /><ElTableColumn label="发布后声明"
              ><template #default="{ row }"
                >{{ row.draft_declarations_satisfied ? '满足' : '待完善' }}
                <ul>
                  <li v-for="issue in row.draft_issues" :key="issue.path + issue.code">
                    {{ issue.message }}
                  </li>
                </ul></template
              ></ElTableColumn
            ></ElTable
          >
        </div>
        <ElButton
          v-if="identity.allowed('tenant.agent_definitions.publish')"
          type="primary"
          :disabled="
            action.busy.value ||
            commandPending ||
            refreshRequired ||
            dirty ||
            !previewCurrent ||
            !validation?.valid ||
            !impact
          "
          @click="publish"
          >发布此修订</ElButton
        >
        <ElCollapse
          ><ElCollapseItem title="发布历史与生命周期" name="history">
            <ElButton :disabled="action.busy.value || commandPending" @click="loadHistory"
              >读取发布历史</ElButton
            ><ElSelect v-model="historyRevision" placeholder="选择历史发布"
              ><ElOption
                v-for="version in history"
                :key="version.current_published_revision ?? version.version"
                :value="version.current_published_revision ?? version.version"
                :label="
                  version.version + ' · ' + (version.current_published_revision ?? '')
                " /></ElSelect
            ><ElButton
              v-if="identity.allowed('tenant.agent_definitions.restore')"
              :disabled="action.busy.value || commandPending || refreshRequired || !historyRevision"
              @click="restore"
              >恢复为草稿</ElButton
            ><ElButton
              v-if="identity.allowed('tenant.agent_definitions.deprecate')"
              type="danger"
              :disabled="action.busy.value || commandPending || refreshRequired"
              @click="deprecate"
              >停用定义</ElButton
            >
          </ElCollapseItem></ElCollapse
        >
      </section>
      <template #footer
        ><ElButton :disabled="action.busy.value || commandPending" @click="close.requestClose"
          >关闭</ElButton
        ><ElButton
          v-if="canEdit"
          :type="
            dirty &&
            identity.allowed('tenant.agent_definitions.read') &&
            identity.allowed('tenant.agent_definitions.validate')
              ? undefined
              : 'primary'
          "
          :loading="action.busy.value"
          :disabled="commandPending || refreshRequired"
          @click="save()"
          >保存草稿</ElButton
        ><ElButton
          v-if="
            canEdit &&
            dirty &&
            identity.allowed('tenant.agent_definitions.read') &&
            identity.allowed('tenant.agent_definitions.validate')
          "
          type="primary"
          :loading="action.busy.value"
          :disabled="commandPending || refreshRequired"
          @click="save(true)"
          >保存并校验</ElButton
        ></template
      >
    </ElDrawer>
  </AdminPage>
</template>
<style scoped>
.field-block {
  margin-block: 12px;
  padding: 12px;
  border: 1px solid var(--el-border-color);
  border-radius: var(--el-border-radius-base);
}
</style>
