<script setup lang="ts">
import { computed, onMounted, onScopeDispose, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { AdminPage, useResource, useTask, useUnsavedChanges } from '@skillforge/shared/admin'
import { message } from '@skillforge/shared/feedback'
import FeedbackNotice from '@skillforge/shared/feedback/FeedbackNotice.vue'
import ToolOnboarding from '../../components/ToolOnboarding.vue'
import { api, type McpToolItem } from '../../api'
import { toolsApi, type ToolOnboardingReceipt } from '../../api/tools'
import { useAuthStore } from '../../stores/auth'

const props = withDefaults(defineProps<{ embedded?: boolean }>(), { embedded: false })
const emit = defineEmits<{
  confirmed: [receipt: ToolOnboardingReceipt]
  locked: [value: boolean]
  unknown: [value: boolean]
  busy: [value: boolean]
}>()

const route = useRoute(),
  auth = useAuthStore()
const allowed = (permission: string) => auth.user?.permissions.includes(permission) === true
const canOnboard = computed(() =>
  [
    'foundation.connectors.read',
    'foundation.capabilities.read',
    'foundation.connectors.update',
    'foundation.capabilities.create',
    'foundation.capabilities.publish'
  ].every(allowed)
)
const { data: available, loading, error: listError, reload } = useResource(toolsApi.available)
const {
  data: directory,
  loading: connectorsLoading,
  error: connectorsError,
  reload: loadConnectors
} = useResource(() => api.connectors.instances())
const {
  data: platformCatalog,
  loading: platformLoading,
  error: platformError,
  reload: loadPlatformCatalog
} = useResource(async () => {
  const [tools, definitions] = await Promise.all([
    api.capabilities.platformTools(),
    api.capabilities.definitions('TOOL', 'PUBLISHED')
  ])
  return tools.map((tool) => ({
    ...tool,
    definition: definitions.find(
      (definition) =>
        definition.tenantId === null && definition.capabilityKey === `tool:${tool.name}`
    )
  }))
})
const { busy, error, run } = useTask()
const connectorId = ref(String(route.query.connectorId ?? ''))
const mcpConnectors = computed(() =>
  (directory.value?.items ?? []).filter(
    (connector) => connector.executor_type?.toLowerCase() === 'mcp'
  )
)
watch(mcpConnectors, (connectors) => {
  if (!connectorId.value && connectors.length === 1) connectorId.value = connectors[0]!.instance_id
})
const search = ref('')
const platformSearch = ref('')
const filteredPlatformTools = computed(() => {
  const keyword = platformSearch.value.trim().toLocaleLowerCase()
  if (!keyword) return platformCatalog.value ?? []
  return (platformCatalog.value ?? []).filter((tool) =>
    [tool.title, tool.name, tool.description].some((value) =>
      value.toLocaleLowerCase().includes(keyword)
    )
  )
})
const enabledToolNames = computed(
  () => new Set((available.value?.items ?? []).map((tool) => tool.tool_name))
)
const platformTitleByName = computed(
  () => new Map((platformCatalog.value ?? []).map((tool) => [tool.name, tool.title]))
)
function availableToolLabel(tool: { name: string; tool_name: string }) {
  const title = platformTitleByName.value.get(tool.tool_name)
  return title ? `${title}（${tool.name}）` : tool.name
}
const canEnablePlatformTools = computed(() => allowed('foundation.capabilities.create'))
const platformBusy = ref('')
const platformErrors = ref<Record<string, string>>({})
const platformPending = ref<Record<string, string>>({})
const platformStorageError = ref('')
const platformStorageKey = (subject: string) => `skillforge.platform-tool-enable.${subject}`
const platformEffectLabels: Record<string, string> = {
  READ_ONLY: '只读',
  IDEMPOTENT_WRITE: '可重复写入',
  NON_IDEMPOTENT_WRITE: '写入（不可重复）',
  DESTRUCTIVE: '破坏性操作'
}
const platformConfirmationLabels: Record<string, string> = {
  NEVER: '无需确认',
  POLICY: '按策略确认',
  ALWAYS: '每次调用需确认'
}
const recommendedPlatformTools = new Set([
  'get_current_time',
  'calculate_expression',
  'convert_datetime',
  'json_query',
  'json_schema_validate',
  'template_render',
  'search_memory',
  'search_tasks',
  'read_task',
  'get_goal',
  'search_tools'
])
const meteredPlatformTools = new Set([
  'generate_image',
  'generate_video',
  'transcribe_audio',
  'synthesize_speech'
])
function platformRisk(tool: { name: string; descriptor: Record<string, unknown> }) {
  return [
    platformEffectLabels[String(tool.descriptor.effect)] ?? '影响未知',
    platformConfirmationLabels[String(tool.descriptor.confirmation_policy)] ?? '',
    meteredPlatformTools.has(tool.name) ? '可能产生费用' : ''
  ]
    .filter(Boolean)
    .join(' · ')
}
const filteredTools = computed(() => {
  const keyword = search.value.trim().toLocaleLowerCase()
  return (available.value?.items ?? []).filter((tool) =>
    [
      tool.name,
      tool.tool_name,
      tool.description,
      ...tool.deployments.map((item) => item.provider_label)
    ].some((value) => value.toLocaleLowerCase().includes(keyword))
  )
})
const catalog = ref<McpToolItem[]>()
const catalogStale = ref(false)
const open = ref(props.embedded || Boolean(route.query.connectorId))
let active = true
onScopeDispose(() => {
  active = false
  window.removeEventListener('focus', refreshAfterConnectorSettings)
  document.removeEventListener('visibilitychange', refreshWhenVisible)
  connectorChannel?.close()
})
const receiptRequestId = ref('')
const canReadReceipt = computed(
  () => allowed('foundation.connectors.read') && allowed('foundation.capabilities.read')
)
const uncertain = ref<{ requestId: string; connectorId: string; names: string[] }>()
const storageError = ref('')
const owner = computed(() =>
  auth.user?.tenant_id && auth.user?.sub ? JSON.stringify([auth.user.tenant_id, auth.user.sub]) : ''
)
const storageKey = (subject: string) => `skillforge.tool-onboarding.${subject}`
watch(
  owner,
  (subject) => {
    uncertain.value = undefined
    storageError.value = ''
    if (!subject) {
      storageError.value = '无法确认当前机构和成员，暂不能接入工具。'
      return
    }
    try {
      const raw = sessionStorage.getItem(storageKey(subject))
      if (!raw) return
      const value = JSON.parse(raw)
      if (
        !value ||
        !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value.requestId) ||
        typeof value.connectorId !== 'string' ||
        !value.connectorId ||
        !Array.isArray(value.names) ||
        !value.names.length ||
        !value.names.every((name: unknown) => typeof name === 'string' && !!name)
      )
        throw Error('invalid')
      uncertain.value = value
    } catch {
      storageError.value = '无法读取原工具接入记录，请恢复浏览器存储后重新进入。'
    }
  },
  { immediate: true, flush: 'sync' }
)
watch(
  owner,
  (subject) => {
    platformPending.value = {}
    platformStorageError.value = ''
    if (!subject) return
    try {
      const raw = sessionStorage.getItem(platformStorageKey(subject))
      if (!raw) return
      const value = JSON.parse(raw)
      if (!value || typeof value !== 'object' || Array.isArray(value)) throw Error('invalid')
      platformPending.value = Object.fromEntries(
        Object.entries(value).filter(
          ([name, requestId]) =>
            !!name &&
            typeof requestId === 'string' &&
            /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(requestId)
        )
      )
    } catch {
      platformStorageError.value = '无法读取原平台工具启用记录，请恢复浏览器存储后重试。'
    }
  },
  { immediate: true, flush: 'sync' }
)
const locked = computed(() => busy.value || !!uncertain.value || !!storageError.value)
watch(locked, (value) => emit('locked', value), { immediate: true, flush: 'sync' })
watch(busy, (value) => emit('busy', value), { immediate: true, flush: 'sync' })
watch(uncertain, (value) => emit('unknown', !!value), { immediate: true, flush: 'sync' })
useUnsavedChanges({
  dirty: () => !!uncertain.value,
  busy: () => busy.value,
  leaveMessage: () =>
    uncertain.value
      ? `工具接入结果未知。原操作可能已经完成，请记录请求 ID，之后可用它查询原回执。请求 ID：${uncertain.value.requestId}；连接器：${uncertain.value.connectorId}；工具：${uncertain.value.names.join('、')}。`
      : undefined
})
const confirmedReceipt = ref<ToolOnboardingReceipt>()
const confirmedChange = ref(false)
watch(connectorId, () => {
  catalog.value = undefined
  catalogStale.value = false
})
watch([open, connectorId, mcpConnectors], ([opened, selected, connectors]) => {
  if (
    opened &&
    canOnboard.value &&
    !busy.value &&
    !uncertain.value &&
    catalog.value === undefined &&
    connectors.some((connector) => connector.instance_id === selected)
  )
    void discover()
})
let refreshOnReturn = false
let connectorChannel: BroadcastChannel | undefined
function connectorSettingsOpened() {
  refreshOnReturn = true
}
async function refreshConnectorSettings() {
  const refreshCatalog = catalog.value !== undefined
  if (refreshCatalog) catalogStale.value = true
  const result = await loadConnectors()
  if (
    refreshCatalog &&
    result &&
    active &&
    open.value &&
    canOnboard.value &&
    !busy.value &&
    !uncertain.value
  )
    await discover()
}
function refreshAfterConnectorSettings() {
  if (
    !refreshOnReturn ||
    !open.value ||
    busy.value ||
    uncertain.value ||
    !allowed('foundation.connectors.read')
  )
    return
  refreshOnReturn = false
  void refreshConnectorSettings()
}
function refreshWhenVisible() {
  if (document.visibilityState === 'visible') refreshAfterConnectorSettings()
}
onMounted(() => {
  if (typeof BroadcastChannel !== 'undefined') {
    try {
      connectorChannel = new BroadcastChannel('skillforge.connector.changed')
      connectorChannel.onmessage = (event) => {
        const actor =
          auth.user?.tenant_id && auth.user?.sub ? `${auth.user.tenant_id}:${auth.user.sub}` : ''
        if (
          !actor ||
          event.data !== actor ||
          !open.value ||
          busy.value ||
          uncertain.value ||
          !allowed('foundation.connectors.read')
        )
          return
        refreshOnReturn = false
        void refreshConnectorSettings()
      }
    } catch {
      // 通知通道不可用时仍可在返回页面后读取连接器。
    }
  }
  window.addEventListener('focus', refreshAfterConnectorSettings)
  document.addEventListener('visibilitychange', refreshWhenVisible)
  if (!props.embedded && allowed('foundation.capabilities.read')) {
    void refreshAvailable()
    void loadPlatformCatalog()
  }
  if (allowed('foundation.connectors.read')) void loadConnectors()
  if (uncertain.value && canReadReceipt.value)
    void (async () => {
      await inspect(true)
      if (
        active &&
        open.value &&
        canOnboard.value &&
        !busy.value &&
        !uncertain.value &&
        !storageError.value &&
        catalog.value === undefined
      )
        await discover()
    })()
})
async function discover() {
  if (!mcpConnectors.value.some((connector) => connector.instance_id === connectorId.value)) return
  const selected = connectorId.value
  const identity = auth.identityVersion
  catalogStale.value = true
  await run(async () => {
    const value = await api.connectors.mcpTools(selected)
    if (!active || auth.identityVersion !== identity || selected !== connectorId.value) return
    catalog.value = [...value.items]
    catalogStale.value = false
  })
}
async function refreshAvailable() {
  const value = await reload()
  if (value) confirmedChange.value = false
  return value
}
async function refreshPageTools() {
  await Promise.all([refreshAvailable(), loadPlatformCatalog()])
}
function savePlatformPending(subject: string) {
  try {
    if (Object.keys(platformPending.value).length)
      sessionStorage.setItem(platformStorageKey(subject), JSON.stringify(platformPending.value))
    else sessionStorage.removeItem(platformStorageKey(subject))
    platformStorageError.value = ''
  } catch {
    platformStorageError.value = '无法保存平台工具启用记录，请恢复浏览器存储后重试。'
  }
}
async function enablePlatformTool(tool: {
  name: string
  title: string
  definition?: { defaultVersionId?: string | null }
}) {
  const subject = owner.value
  if (!subject || !canEnablePlatformTools.value || platformBusy.value) return
  if (!tool.definition?.defaultVersionId) {
    message.warning('该平台工具当前没有可用发布版本，请刷新后重试')
    return
  }
  platformBusy.value = tool.name
  platformErrors.value = { ...platformErrors.value, [tool.name]: '' }
  try {
    const version = await api.capabilities.getVersion(tool.definition.defaultVersionId)
    if (String(version.descriptor.tool_name ?? '') !== tool.name)
      throw Error('平台工具发布版本与目录不一致，请刷新后重试')
    const requestId = crypto.randomUUID()
    platformPending.value = { ...platformPending.value, [tool.name]: requestId }
    savePlatformPending(subject)
    if (platformStorageError.value) return
    const receipt = await api.capabilities.createDeploymentWithReceipt(requestId, {
      deploymentKey: `platform-${tool.name}`,
      toolVersionId: version.id,
      providerType: 'PLATFORM',
      providerRef: tool.name,
      environment: 'PRODUCTION',
      status: 'ACTIVE'
    })
    platformPending.value = Object.fromEntries(
      Object.entries(platformPending.value).filter(([name]) => name !== tool.name)
    )
    savePlatformPending(subject)
    if (receipt.status === 'SUCCEEDED') {
      platformErrors.value = { ...platformErrors.value, [tool.name]: '' }
      await refreshAvailable()
      message.success(`${tool.title || tool.name}已启用，智能体可以选择该工具`)
      return
    }
    platformErrors.value = {
      ...platformErrors.value,
      [tool.name]: receipt.message || '平台工具启用被拒绝'
    }
  } catch {
    platformErrors.value = {
      ...platformErrors.value,
      [tool.name]: platformPending.value[tool.name]
        ? '未收到确认，结果尚未确定；请点击“核对结果”确认是否已启用。'
        : '启用前检查失败，请刷新后重试。'
    }
  } finally {
    platformBusy.value = ''
  }
}
async function inspectPlatformTool(tool: { name: string; title: string }) {
  const subject = owner.value
  const requestId = platformPending.value[tool.name]
  if (!subject || !requestId || platformBusy.value) return
  platformBusy.value = tool.name
  try {
    const receipt = await api.capabilities.deploymentCreateReceipt(requestId)
    if (!receipt) {
      message.warning('尚未查到回执，原操作可能仍在处理中，请稍后再次核对')
      return
    }
    platformPending.value = Object.fromEntries(
      Object.entries(platformPending.value).filter(([name]) => name !== tool.name)
    )
    savePlatformPending(subject)
    if (receipt.status === 'SUCCEEDED') {
      platformErrors.value = { ...platformErrors.value, [tool.name]: '' }
      await refreshAvailable()
      message.success(`${tool.title || tool.name}已启用，智能体可以选择该工具`)
      return
    }
    platformErrors.value = {
      ...platformErrors.value,
      [tool.name]: receipt.message || '平台工具启用被拒绝'
    }
  } catch {
    message.warning('回执查询失败，结果仍未确定，请稍后再次核对')
  } finally {
    platformBusy.value = ''
  }
}
async function acceptConfirmed(receipt: ToolOnboardingReceipt) {
  confirmedReceipt.value = receipt
  catalog.value = undefined
  catalogStale.value = false
  if (props.embedded) {
    emit('confirmed', receipt)
    return
  }
  confirmedChange.value = true
  if (await refreshAvailable()) message.success('原请求已成功，已刷新当前工具目录')
}
function matchesReceipt(
  receipt: ToolOnboardingReceipt | undefined,
  requestId: string,
  selection?: NonNullable<typeof uncertain.value>
) {
  if (
    receipt?.request_id !== requestId ||
    typeof receipt.connector_id !== 'string' ||
    !receipt.connector_id ||
    !Array.isArray(receipt.tool_names) ||
    !receipt.tool_names.length ||
    !receipt.tool_names.every((name) => typeof name === 'string' && !!name) ||
    (selection?.connectorId &&
      (receipt.connector_id !== selection.connectorId ||
        receipt.tool_names.length !== selection.names.length ||
        !receipt.tool_names.every((name, index) => name === selection.names[index])))
  )
    return false
  if (receipt.status === 'PROCESSING') return true
  if (receipt.status === 'REJECTED')
    return (
      typeof receipt.error_code === 'string' &&
      !!receipt.error_code &&
      typeof receipt.error_message === 'string' &&
      !!receipt.error_message
    )
  return (
    receipt.status === 'SUCCEEDED' &&
    Array.isArray(receipt.items) &&
    receipt.items.length === receipt.tool_names.length &&
    receipt.items.every(
      (item, index) =>
        item.tool_name === receipt.tool_names[index] &&
        [item.definition_id, item.version_id, item.deployment_key, item.environment].every(
          (value) => typeof value === 'string' && !!value
        )
    )
  )
}
async function handleReceipt(
  receipt: ToolOnboardingReceipt | undefined,
  selection: NonNullable<typeof uncertain.value>,
  subject: string,
  automatic = false
) {
  if (!matchesReceipt(receipt, selection.requestId, selection)) {
    if (!automatic) message.warning('暂未查到完整匹配的原请求回执，结果仍未确认，请稍后再次核对')
    return
  }
  if (!active || owner.value !== subject) return
  if (receipt!.status === 'PROCESSING') {
    const pending = {
      requestId: selection.requestId,
      connectorId: receipt!.connector_id,
      names: [...receipt!.tool_names]
    }
    try {
      sessionStorage.setItem(storageKey(subject), JSON.stringify(pending))
      uncertain.value = pending
    } catch {
      storageError.value = '无法保存原工具接入记录，请恢复浏览器存储后核对。'
    }
    if (!automatic) message.warning('原请求结果尚未确认，请稍后再次核对')
    return
  }
  if (uncertain.value?.requestId === selection.requestId) {
    try {
      sessionStorage.removeItem(storageKey(subject))
    } catch {
      storageError.value = '无法清除原工具接入记录，请恢复浏览器存储后重新核对。'
      return
    }
  }
  uncertain.value = undefined
  storageError.value = ''
  receiptRequestId.value = ''
  if (receipt!.status === 'REJECTED') {
    message.error(`接入未完成：${receipt!.error_message}，请修正后重新接入`)
    return
  }
  await acceptConfirmed(receipt!)
}
async function confirm(names: string[]) {
  if (
    !owner.value ||
    storageError.value ||
    uncertain.value ||
    !mcpConnectors.value.some((connector) => connector.instance_id === connectorId.value)
  )
    return
  await run(async () => {
    const subject = owner.value
    const selection = {
      requestId: crypto.randomUUID(),
      connectorId: connectorId.value,
      names: [...names]
    }
    try {
      sessionStorage.setItem(storageKey(subject), JSON.stringify(selection))
    } catch {
      storageError.value = '无法保存原工具接入记录，尚未发送请求；请恢复浏览器存储。'
      return
    }
    uncertain.value = selection
    const receipt = await toolsApi.onboard(
      selection.requestId,
      selection.connectorId,
      selection.names,
      true
    )
    await handleReceipt(receipt, selection, subject)
  })
}
async function inspect(automatic = false) {
  const requestId = uncertain.value?.requestId ?? receiptRequestId.value.trim()
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(requestId)) {
    message.warning('请输入原请求的 UUID')
    return
  }
  const selection = uncertain.value ?? { requestId, connectorId: '', names: [] }
  const subject = owner.value
  if (!subject) return
  await run(async () => {
    const receipt = await toolsApi.onboarding(requestId)
    await handleReceipt(receipt, selection, subject, automatic)
  })
}
</script>

<template>
  <AdminPage
    :embedded="embedded"
    title="工具"
    description="启用平台工具或接入 MCP 工具，启用后即可在智能体中使用。"
  >
    <template #extra>
      <ElButton
        v-if="!embedded && canOnboard"
        type="primary"
        :disabled="busy || !!uncertain || !!storageError"
        @click="open = !open"
        >接入 MCP 工具</ElButton
      >
      <ElButton
        v-if="!embedded && allowed('foundation.capabilities.read')"
        :loading="loading || platformLoading"
        :disabled="busy"
        @click="refreshPageTools"
        >刷新工具</ElButton
      >
    </template>
    <FeedbackNotice
      v-if="error || listError || connectorsError || platformError"
      :title="
        error ||
        (listError && confirmedChange
          ? '工具接入已确认，但目录刷新失败，可刷新工具，无需重复接入。'
          : listError) ||
        connectorsError ||
        platformError
      "
      :type="!error && listError && confirmedChange ? 'warning' : 'error'"
      :closable="false"
    />
    <p v-if="storageError" role="status">{{ storageError }}</p>
    <p v-if="platformStorageError" role="status">{{ platformStorageError }}</p>
    <section v-if="uncertain" class="admin-card">
      <p role="status">接入结果尚未确认。可核对原请求回执，暂未查到时请稍后再试。</p>
      <p>
        请求 ID：{{ uncertain.requestId
        }}<template v-if="uncertain.connectorId"
          >；连接器：{{ uncertain.connectorId }}；工具：{{ uncertain.names.join('、') }}</template
        >
      </p>
      <ElButton v-if="canReadReceipt" :loading="busy" @click="inspect()">核对接入结果</ElButton>
    </section>
    <section v-if="!embedded && allowed('foundation.capabilities.read')" class="admin-card">
      <h2>平台工具</h2>
      <p class="admin-field-help">
        平台提供的通用工具。启用后才会出现在智能体的可选工具中；每个机构显式启用，不会自动获得全部工具。
      </p>
      <ElInput
        v-model="platformSearch"
        clearable
        aria-label="搜索平台工具"
        placeholder="搜索平台工具名称或用途"
      />
      <ElTable
        :data="filteredPlatformTools"
        v-loading="platformLoading"
        row-key="name"
        :empty-text="
          platformCatalog?.length ? '没有匹配的平台工具，请调整搜索词' : '平台尚未发布默认工具'
        "
      >
        <ElTableColumn label="工具" min-width="260">
          <template #default="{ row }">
            <div>{{ row.title || row.name }}</div>
            <div class="admin-field-help">
              {{ row.name
              }}<template v-if="recommendedPlatformTools.has(row.name)"> · 推荐</template>
            </div>
            <div class="admin-field-help tool-description">{{ row.description }}</div>
          </template>
        </ElTableColumn>
        <ElTableColumn label="风险" width="170">
          <template #default="{ row }">{{ platformRisk(row) }}</template>
        </ElTableColumn>
        <ElTableColumn label="状态" width="88">
          <template #default="{ row }">
            <ElTag :type="enabledToolNames.has(row.name) ? 'success' : 'info'" size="small">
              {{ enabledToolNames.has(row.name) ? '已启用' : '未启用' }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="操作" width="130">
          <template #default="{ row }">
            <span
              v-if="enabledToolNames.has(row.name) && !platformPending[row.name]"
              class="admin-field-help"
              >—</span
            >
            <ElButton
              v-else
              link
              type="primary"
              :loading="platformBusy === row.name"
              :disabled="!canEnablePlatformTools || !!platformBusy"
              @click="enablePlatformTool(row)"
              >启用</ElButton
            >
            <ElButton
              v-if="platformPending[row.name]"
              link
              :loading="platformBusy === row.name"
              :disabled="!!platformBusy"
              @click="inspectPlatformTool(row)"
              >核对结果</ElButton
            >
            <div v-if="platformErrors[row.name]" class="admin-field-help" role="status">
              {{ platformErrors[row.name] }}
            </div>
          </template>
        </ElTableColumn>
      </ElTable>
    </section>
    <section v-if="open && canOnboard" v-show="!uncertain && !storageError" class="admin-card">
      <h2>通过 MCP 连接器接入</h2>
      <ToolOnboarding
        v-model:connector-id="connectorId"
        :connectors="mcpConnectors"
        :tools="catalog ?? []"
        :stale="catalogStale"
        :has-catalog="catalog !== undefined"
        :loading="connectorsLoading"
        :busy="busy"
        @discover="discover"
        @confirm="confirm"
      />
      <p
        v-if="directory && !connectorsLoading && !connectorsError && !mcpConnectors.length"
        class="admin-field-help"
        role="status"
      >
        当前没有可选的 MCP 连接器。配置后返回此处即可读取并接入工具。
      </p>
      <ElButton :disabled="busy" :loading="connectorsLoading" @click="refreshConnectorSettings">
        刷新连接器及工具
      </ElButton>
      <RouterLink
        v-if="allowed('foundation.connectors.read')"
        to="/admin/connectors"
        @click="connectorSettingsOpened"
        >配置 MCP 连接器</RouterLink
      >
    </section>
    <p v-if="confirmedReceipt && confirmedChange" class="admin-field-help" role="status">
      工具接入已确认，目录尚未刷新。请求 ID：{{ confirmedReceipt.request_id }}；工具：{{
        confirmedReceipt.tool_names.join('、')
      }}
    </p>
    <section v-if="!embedded && allowed('foundation.capabilities.read')" class="admin-card">
      <h2>已启用工具</h2>
      <ElInput
        v-model="search"
        clearable
        aria-label="搜索可用工具"
        placeholder="搜索工具名称、说明或服务来源"
      />
      <ElTable
        :data="filteredTools"
        v-loading="loading"
        row-key="definition_id"
        :empty-text="
          available?.items.length
            ? '没有匹配的工具，请调整搜索词'
            : '尚无已启用工具。可在上方启用平台工具，或通过 MCP 连接器接入。'
        "
      >
        <ElTableColumn label="工具" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ availableToolLabel(row) }}</template>
        </ElTableColumn>
        <ElTableColumn prop="description" label="说明" min-width="240" show-overflow-tooltip />
        <ElTableColumn label="服务来源" min-width="200"
          ><template #default="{ row }">{{
            row.deployments
              .map((item: { provider_label: string }) => item.provider_label)
              .join('、')
          }}</template></ElTableColumn
        >
      </ElTable>
    </section>
    <ElCollapse v-if="!embedded">
      <ElCollapseItem title="查询历史接入请求" name="receipt">
        <p class="admin-field-help">
          仅在系统提示接入结果未知且原页面已关闭时使用。正常接入不需要请求 ID。
        </p>
        <ElInput
          v-if="canReadReceipt"
          v-model="receiptRequestId"
          aria-label="原请求 ID"
          placeholder="输入原请求 ID"
          :disabled="busy"
        />
        <ElButton
          v-if="canReadReceipt"
          :disabled="busy || !receiptRequestId.trim()"
          :loading="busy"
          @click="inspect()"
          >查询原接入回执</ElButton
        >
      </ElCollapseItem>
      <ElCollapseItem title="高级管理" name="advanced">
        <div class="admin-actions">
          <RouterLink v-if="allowed('foundation.connectors.read')" to="/admin/connectors"
            >连接设置</RouterLink
          >
          <RouterLink v-if="allowed('foundation.capabilities.read')" to="/admin/capabilities"
            >工具定义与版本</RouterLink
          >
          <RouterLink v-if="allowed('foundation.capabilities.read')" to="/admin/tool-deployments"
            >环境与服务绑定</RouterLink
          >
        </div>
      </ElCollapseItem>
    </ElCollapse>
  </AdminPage>
</template>

<style scoped>
.tool-description {
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  overflow: hidden;
}
</style>
