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
const locked = computed(() => busy.value || !!uncertain.value)
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
  void loadConnectors()
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
        void loadConnectors()
      }
    } catch {
      // 通知通道不可用时仍可在返回页面后读取连接器。
    }
  }
  window.addEventListener('focus', refreshAfterConnectorSettings)
  document.addEventListener('visibilitychange', refreshWhenVisible)
  if (!props.embedded && allowed('foundation.capabilities.read')) void refreshAvailable()
  if (allowed('foundation.connectors.read')) void loadConnectors()
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
  selection: NonNullable<typeof uncertain.value>
) {
  if (!matchesReceipt(receipt, selection.requestId, selection)) {
    message.warning('暂未查到完整匹配的原请求回执，结果仍未确认，请稍后再次核对')
    return
  }
  if (receipt!.status === 'PROCESSING') {
    uncertain.value = {
      requestId: selection.requestId,
      connectorId: receipt!.connector_id,
      names: [...receipt!.tool_names]
    }
    message.warning('原请求结果尚未确认，请稍后再次核对')
    return
  }
  uncertain.value = undefined
  receiptRequestId.value = ''
  if (receipt!.status === 'REJECTED') {
    message.error(`接入未完成：${receipt!.error_message}，请修正后重新接入`)
    return
  }
  await acceptConfirmed(receipt!)
}
async function confirm(names: string[]) {
  if (!mcpConnectors.value.some((connector) => connector.instance_id === connectorId.value)) return
  await run(async () => {
    const selection = {
      requestId: crypto.randomUUID(),
      connectorId: connectorId.value,
      names: [...names]
    }
    uncertain.value = selection
    const receipt = await toolsApi.onboard(
      selection.requestId,
      selection.connectorId,
      selection.names,
      true
    )
    await handleReceipt(receipt, selection)
  })
}
async function inspect() {
  const requestId = uncertain.value?.requestId ?? receiptRequestId.value.trim()
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(requestId)) {
    message.warning('请输入原请求的 UUID')
    return
  }
  const selection = uncertain.value ?? { requestId, connectorId: '', names: [] }
  await run(async () => {
    const receipt = await toolsApi.onboarding(requestId)
    await handleReceipt(receipt, selection)
  })
}
</script>

<template>
  <AdminPage
    :embedded="embedded"
    title="工具"
    description="从连接器选择需要的工具，接入后即可在智能体中使用。"
  >
    <template #extra>
      <ElButton
        v-if="!embedded && canOnboard"
        type="primary"
        :disabled="busy || !!uncertain"
        @click="open = !open"
        >接入工具</ElButton
      >
      <ElButton
        v-if="!embedded && allowed('foundation.capabilities.read')"
        :loading="loading"
        :disabled="busy"
        @click="refreshAvailable"
        >刷新工具</ElButton
      >
    </template>
    <FeedbackNotice
      v-if="error || listError || connectorsError"
      :title="
        error ||
        (listError && confirmedChange
          ? '工具接入已确认，但目录刷新失败，可刷新工具，无需重复接入。'
          : listError) ||
        connectorsError
      "
      :type="!error && listError && confirmedChange ? 'warning' : 'error'"
      :closable="false"
    />
    <section v-if="uncertain" class="admin-card">
      <p role="status">接入结果尚未确认。可核对原请求回执，暂未查到时请稍后再试。</p>
      <p>
        请求 ID：{{ uncertain.requestId
        }}<template v-if="uncertain.connectorId"
          >；连接器：{{ uncertain.connectorId }}；工具：{{ uncertain.names.join('、') }}</template
        >
      </p>
      <ElButton :loading="busy" @click="inspect">核对接入结果</ElButton>
    </section>
    <section v-if="canReadReceipt && !uncertain" class="admin-card">
      <p>已离开接入页面时，可使用原请求 ID 查询接入回执。</p>
      <ElInput
        v-model="receiptRequestId"
        aria-label="原请求 ID"
        placeholder="输入原请求 ID"
        :disabled="busy"
      />
      <ElButton :disabled="busy || !receiptRequestId.trim()" :loading="busy" @click="inspect"
        >查询原接入回执</ElButton
      >
    </section>
    <section v-if="open && canOnboard" v-show="!uncertain" class="admin-card">
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
      <ElButton :disabled="busy" @click="loadConnectors">刷新连接器</ElButton>
      <RouterLink
        v-if="allowed('foundation.connectors.read')"
        to="/admin/connectors"
        target="_blank"
        rel="noopener noreferrer"
        @click="connectorSettingsOpened"
        >在新标签页设置连接器</RouterLink
      >
    </section>
    <p v-if="confirmedReceipt && confirmedChange" class="admin-field-help" role="status">
      工具接入已确认，目录尚未刷新。请求 ID：{{ confirmedReceipt.request_id }}；工具：{{
        confirmedReceipt.tool_names.join('、')
      }}
    </p>
    <ElInput
      v-if="!embedded && allowed('foundation.capabilities.read')"
      v-model="search"
      clearable
      aria-label="搜索可用工具"
      placeholder="搜索工具名称、说明或服务来源"
    />
    <ElTable
      v-if="!embedded && allowed('foundation.capabilities.read') && available"
      :data="filteredTools"
      v-loading="loading"
      row-key="definition_id"
      :empty-text="
        available?.items.length
          ? '没有匹配的工具，请调整搜索词'
          : '尚无可用工具，接入工具后即可供智能体选择'
      "
    >
      <ElTableColumn prop="name" label="工具" min-width="180" show-overflow-tooltip />
      <ElTableColumn prop="description" label="说明" min-width="240" show-overflow-tooltip />
      <ElTableColumn label="服务来源" min-width="200"
        ><template #default="{ row }">{{
          row.deployments.map((item: { provider_label: string }) => item.provider_label).join('、')
        }}</template></ElTableColumn
      >
    </ElTable>
    <ElCollapse v-if="!embedded">
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
