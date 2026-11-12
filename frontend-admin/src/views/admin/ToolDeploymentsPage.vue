<template>
  <AdminPage title="工具部署" description="绑定工具身份与机构服务实例，新执行跟随工具当前发布版本。"
    ><template #extra
      ><ElButton
        type="primary"
        @click="openCreate"
        :disabled="busy || !!creationUnknown || !!creationStorageError"
        >新增部署</ElButton
      ></template
    >
    <section
      v-for="operation in creationOperations"
      :key="operation.requestId"
      class="admin-card"
      role="status"
    >
      {{
        operation.deploymentKey ? `部署“${operation.deploymentKey}”` : '工具部署'
      }}的创建结果尚未确认，请求号：{{ operation.requestId }}。
      查不到原回执时结果仍未确认，请勿重复创建。
      <ElButton :disabled="busy || !canRead" @click="checkCreation(false, operation)"
        >核对原创建结果</ElButton
      >
      <ElButton
        v-if="operation.deploymentKey"
        :disabled="busy || !canRead"
        @click="checkDeployment(operation)"
        >查看当前部署</ElButton
      >
      <ElButton
        v-if="creationInputRequestId === operation.requestId"
        :disabled="busy"
        @click="open = true"
        >查看原创建输入</ElButton
      >
      <p v-if="checkedDeployment && checkedRequestId === operation.requestId">
        当前部署：{{ checkedDeployment.deploymentKey }} · 工具版本
        {{ checkedDeployment.toolVersionId }} · {{ checkedDeployment.providerType }} /
        {{ checkedDeployment.providerRef }} · {{ checkedDeployment.environment }} · 修订
        {{ checkedDeployment.revision }}
      </p>
    </section>
    <section
      v-for="operation in stateOperations"
      :key="operation.requestId"
      class="admin-card"
      role="status"
    >
      部署“{{ operation.deploymentKey }}”的{{
        operation.active ? '启用' : '停用'
      }}结果尚未确认，原修订 {{ operation.revision }}，请求号：{{ operation.requestId }}。
      当前部署状态不能代替原请求回执，请勿重复提交。
      <ElButton :disabled="busy || !canRead" @click="checkState(operation)"
        >核对原启停结果</ElButton
      >
      <ElButton
        v-if="canContinueState(operation)"
        :disabled="busy || !!stateStorageError"
        @click="continueState(operation)"
        >继续原启停操作</ElButton
      >
    </section>
    <p v-if="stateStorageError" role="status">{{ stateStorageError }}</p>
    <p v-if="confirmedStateReceipt" role="status">
      原启停回执：请求 {{ confirmedStateReceipt.request_id }} · 部署
      {{ confirmedStateReceipt.deployment_key }} · 提交时状态
      {{ statusLabel(confirmedStateReceipt.deployment_status || '') }} · 修订
      {{ confirmedStateReceipt.revision }}。 此回执不代表当前状态，当前部署以列表为准。
    </p>
    <p v-if="creationStorageError" role="status">{{ creationStorageError }}</p>
    <p v-if="confirmedChange" role="status">{{ confirmedChange }}</p>
    <p v-if="loadError" role="status">
      当前列表刷新失败，显示的是上次确认的部署状态，可能已过时；请刷新核对。原启停回执仅说明原请求结果。
    </p>
    <div class="admin-toolbar admin-filters">
      <ElInput
        v-model="query"
        clearable
        aria-label="搜索工具部署"
        placeholder="搜索部署、工具或提供方引用"
      />
      <ElSelect v-model="environment" clearable aria-label="运行环境" placeholder="全部环境">
        <ElOption label="开发" value="DEVELOPMENT" /><ElOption label="测试" value="TEST" /><ElOption
          label="生产"
          value="PRODUCTION"
        />
      </ElSelect>
      <ElSelect v-model="deploymentStatus" clearable aria-label="部署状态" placeholder="全部状态">
        <ElOption label="启用" value="ACTIVE" /><ElOption label="停用" value="INACTIVE" />
      </ElSelect>
      <ElButton :loading="loading || busy" :disabled="busy" @click="refresh">刷新</ElButton>
      <span class="admin-muted" role="status">{{
        loading
          ? '正在读取部署…'
          : !directoryLoaded
            ? '部署目录尚未完整读取'
            : `${filtered.length} 项部署`
      }}</span>
    </div>
    <ElTable
      table-layout="auto"
      :data="filtered"
      v-loading="loading"
      :empty-text="
        !directoryLoaded && loadError
          ? '部署列表读取失败，请点击刷新重试'
          : !directoryLoaded
            ? '部署目录尚未完整读取'
            : query || environment || deploymentStatus
              ? '没有匹配的部署，请调整筛选条件'
              : '尚无部署，先为已发布工具绑定提供方'
      "
      ><ElTableColumn
        min-width="200"
        show-overflow-tooltip
        prop="deploymentKey"
        label="部署标识"
      /><ElTableColumn min-width="200" show-overflow-tooltip label="工具（跟随当前发布）"
        ><template #default="{ row }"
          ><div>
            {{ row.toolName ? `${row.toolName} / ${row.toolVersionKey}` : '版本信息不可用' }}
          </div>
          <small class="muted">{{ row.toolVersionId }}</small></template
        ></ElTableColumn
      ><ElTableColumn min-width="140" show-overflow-tooltip label="提供方"
        ><template #default="{ row }">{{ statusLabel(row.providerType) }}</template></ElTableColumn
      ><ElTableColumn
        min-width="200"
        show-overflow-tooltip
        prop="providerRef"
        label="引用"
      /><ElTableColumn min-width="140" show-overflow-tooltip label="环境"
        ><template #default="{ row }">{{ statusLabel(row.environment) }}</template></ElTableColumn
      ><ElTableColumn width="128" show-overflow-tooltip label="状态"
        ><template #default="{ row }">{{ statusLabel(row.status) }}</template></ElTableColumn
      ><ElTableColumn width="80" class-name="admin-table-actions" label="操作"
        ><template #default="{ row }"
          ><ElButton
            v-permission="[
              row.status === 'ACTIVE'
                ? 'foundation.capabilities.cancel'
                : 'foundation.capabilities.run'
            ]"
            link
            :disabled="
              busy ||
              !!stateStorageError ||
              stateOperations.some((operation) => operation.deploymentKey === row.deploymentKey)
            "
            @click="toggle(row as CapabilityDeployment)"
            >{{ row.status === 'ACTIVE' ? '停用' : '启用' }}</ElButton
          ></template
        ></ElTableColumn
      ></ElTable
    ><ElDrawer
      v-model="open"
      title="新增工具部署"
      :before-close="requestClose"
      append-to-body
      direction="rtl"
      :close-on-click-modal="false"
      :close-on-press-escape="!busy"
      :show-close="!busy"
      class="admin-drawer"
      size="680px"
      ><p v-if="creationUnknown" role="status">创建结果尚未确认，以下原输入仅供核对。</p>
      <ElForm
        ref="formRef"
        :disabled="busy || !!creationUnknown"
        :model="form"
        :rules="rules"
        label-position="top"
        scroll-to-error
        @submit.prevent="create"
        @keydown.capture="guardFormEnter"
        ><ElFormItem label="工具（跟随当前发布）" prop="toolVersionId"
          ><ElSelect
            v-described-by="formTipId + '-field-1'"
            :aria-describedby="formTipId + '-field-1'"
            v-model="form.toolVersionId"
            filterable
            ><ElOption v-for="v in versions" :key="v.id" :value="v.id" :label="v.name"
          /></ElSelect>
          <FieldTip :id="formTipId + '-field-1'"
            >选择已发布工具；新执行跟随其当前发布版本。</FieldTip
          > </ElFormItem
        ><ElDivider content-position="left">连接与运行环境</ElDivider
        ><ElFormItem label="提供方"
          ><ElSelect
            v-described-by="formTipId + '-field-2'"
            :aria-describedby="formTipId + '-field-2'"
            v-model="form.providerType"
            ><ElOption value="PLATFORM" label="平台内置工具" /><ElOption
              v-if="canReadConnectors"
              value="MCP"
              label="MCP" /><ElOption v-if="canReadConnectors" value="CONNECTOR" label="连接器"
          /></ElSelect>
          <FieldTip :id="formTipId + '-field-2'"
            >选择实际提供工具的平台能力或 MCP 连接器。</FieldTip
          > </ElFormItem
        ><ElFormItem label="提供方引用" prop="providerRef"
          ><EntitySelect
            v-if="form.providerType !== 'PLATFORM'"
            v-described-by="'deployment-provider-help'"
            aria-describedby="deployment-provider-help"
            v-model="form.providerRef"
            :disabled="busy || !!creationUnknown"
            :options="providerOptions"
            placeholder="选择连接器实例"
          />
          <p v-else>{{ form.providerRef || '所选工具没有匹配的平台处理器' }}</p>
          <p id="deployment-provider-help" class="admin-field-help">
            平台处理器按所选工具契约自动匹配；MCP 和连接器从当前机构的实例目录选择。
          </p></ElFormItem
        ><ElAlert
          v-if="!providerOptions.length"
          title="当前提供方没有可用引用，请先配置对应连接器或选择其他提供方。"
          type="warning"
          :closable="false"
        />
        <ElFormItem label="运行环境"
          ><ElSelect
            v-described-by="formTipId + '-field-4'"
            :aria-describedby="formTipId + '-field-4'"
            v-model="form.environment"
            ><ElOption value="DEVELOPMENT" label="开发" /><ElOption
              value="TEST"
              label="测试" /><ElOption value="PRODUCTION" label="生产"
          /></ElSelect>
          <FieldTip :id="formTipId + '-field-4'">选择该部署使用的运行环境。</FieldTip>
        </ElFormItem></ElForm
      ><template #footer
        ><ElButton @click="requestClose" :disabled="busy">取消</ElButton
        ><ElButton
          v-permission="['foundation.capabilities.create']"
          type="primary"
          :loading="busy"
          :disabled="!!creationUnknown || !!creationStorageError"
          @click="create"
          >创建</ElButton
        ></template
      ></ElDrawer
    ></AdminPage
  >
</template>
<script setup lang="ts">
import { vDescribedBy } from '@skillforge/shared/directives/describedBy'
import { HttpClientError } from '@skillforge/shared/utils/http'

import FieldTip from '@skillforge/shared/components/FieldTip.vue'

import { message } from '@skillforge/shared/feedback'
import { guardFormEnter } from '@skillforge/shared/admin'
import { computed, onMounted, onScopeDispose, reactive, ref, watch, useId } from 'vue'
import {
  AdminPage,
  EntitySelect,
  statusLabel,
  useResource,
  useTask,
  useDrawerClose,
  useUnsavedChanges
} from '@skillforge/shared/admin'
import type { FormInstance, FormRules } from 'element-plus'
import {
  api,
  getSessionId,
  type CapabilityDeployment,
  type CreateCapabilityDeploymentInput,
  type CapabilityVersion
} from '../../api'
import { useAuthStore } from '../../stores/auth'
const auth = useAuthStore()
const owner = computed(() =>
  auth.user?.tenant_id && auth.user?.sub ? JSON.stringify([auth.user.tenant_id, auth.user.sub]) : ''
)
const canRead = computed(
  () => auth.user?.permissions.includes('foundation.capabilities.read') === true
)
const canCreate = computed(
  () => auth.user?.permissions.includes('foundation.capabilities.create') === true
)
const canReadConnectors = computed(
  () => auth.user?.permissions.includes('foundation.connectors.read') === true
)
let disposed = false
let directoryRequest = 0
const {
  data,
  loading,
  error: loadError,
  reload,
  invalidate
} = useResource(async () => {
  const subject = owner.value,
    identity = auth.identityVersion,
    session = getSessionId(),
    request = ++directoryRequest
  const current = () =>
    !disposed &&
    owner.value === subject &&
    auth.identityVersion === identity &&
    getSessionId() === session &&
    canRead.value
  try {
    const result = await api.capabilities.deployments()
    if (!current()) throw Error('部署目录读取已失效')
    return result
  } catch (cause) {
    if (!current()) {
      if (cause instanceof HttpClientError) cause.claimFeedback()
      if (request === directoryRequest) invalidate()
    }
    throw cause
  }
})
onScopeDispose(() => {
  disposed = true
})
const formRef = ref<FormInstance>()
const rules: FormRules = {
  toolVersionId: [
    {
      type: 'string',
      pattern: /^[1-9]\d*$/,
      required: true,
      message: '请选择已发布工具版本',
      trigger: 'change'
    }
  ],
  providerRef: [{ required: true, message: '请选择提供方引用', trigger: 'change' }]
}
const query = ref(''),
  environment = ref(''),
  deploymentStatus = ref('')
const filtered = computed(() =>
  (data.value ?? []).filter(
    (item) =>
      (
        item.deploymentKey +
        (item.toolName ? `${item.toolName} / ${item.toolVersionKey}` : '') +
        item.providerRef
      )
        .toLowerCase()
        .includes(query.value.trim().toLowerCase()) &&
      (!environment.value || item.environment === environment.value) &&
      (!deploymentStatus.value ||
        (deploymentStatus.value === 'ACTIVE' ? item.status === 'ACTIVE' : item.status !== 'ACTIVE'))
  )
)
const platformTools = ref<Awaited<ReturnType<typeof api.capabilities.platformTools>>>([])
const connectors = ref<Awaited<ReturnType<typeof api.connectors.instances>>['items']>([])
const directoryLoaded = ref(false)
onMounted(async () => {
  await refresh()
  const recoveryOwner = owner.value
  for (const operation of [...creationOperations.value]) {
    if (disposed || !canRead.value || owner.value !== recoveryOwner) break
    await checkCreation(true, operation)
  }
})
async function refresh() {
  const subject = owner.value,
    identity = auth.identityVersion,
    session = getSessionId()
  const current = () =>
    !disposed &&
    owner.value === subject &&
    auth.identityVersion === identity &&
    getSessionId() === session &&
    canRead.value
  if (!current()) return
  confirmedChange.value = ''
  const deployments = await reload()
  if (deployments === undefined || !current()) return
  directoryLoaded.value = true
  for (const operation of [...stateOperations.value]) {
    if (!current()) break
    await checkState(operation, true)
  }
}
type StateOperation = {
  requestId: string
  deploymentKey: string
  revision: number
  active: boolean
}
const stateOperations = ref<StateOperation[]>([])
const stateStorageError = ref('')
const confirmedStateReceipt =
  ref<Awaited<ReturnType<typeof api.capabilities.deploymentStateReceipt>>>()
const statePrefix = (subject: string) => `skillforge.tool-deployment-state:${subject}:`
const stateKey = (subject: string, requestId: string) => statePrefix(subject) + requestId
function syncStateOperations(subject: string, preserveExisting = false) {
  const operations: StateOperation[] = []
  stateStorageError.value = ''
  if (!subject) {
    stateOperations.value = []
    return
  }
  try {
    for (let i = 0; i < localStorage.length; i++) {
      const key = localStorage.key(i)
      if (!key?.startsWith(statePrefix(subject))) continue
      const operation = JSON.parse(localStorage.getItem(key)!) as StateOperation
      if (
        !operation ||
        !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(
          operation.requestId
        ) ||
        typeof operation.deploymentKey !== 'string' ||
        !operation.deploymentKey ||
        !Number.isSafeInteger(operation.revision) ||
        operation.revision < 1 ||
        typeof operation.active !== 'boolean' ||
        key !== stateKey(subject, operation.requestId)
      )
        throw Error('invalid')
      operations.push(operation)
    }
  } catch {
    stateStorageError.value = '无法读取原启停请求记录，请恢复浏览器存储后重新进入。'
  }
  if (preserveExisting) {
    for (const existing of stateOperations.value) {
      if (!operations.some((operation) => operation.requestId === existing.requestId))
        operations.push(existing)
    }
  }
  stateOperations.value = operations
}
watch(
  owner,
  (subject) => {
    confirmedStateReceipt.value = undefined
    syncStateOperations(subject)
  },
  { immediate: true, flush: 'sync' }
)
function onStateStorage(event: StorageEvent) {
  if (
    event.storageArea === localStorage &&
    (!event.key || event.key.startsWith(statePrefix(owner.value)))
  )
    syncStateOperations(owner.value, true)
}
window.addEventListener('storage', onStateStorage)
onScopeDispose(() => window.removeEventListener('storage', onStateStorage))
async function acceptStateReceipt(
  receipt: Awaited<ReturnType<typeof api.capabilities.deploymentStateReceipt>>,
  operation: StateOperation,
  subject: string,
  current: () => boolean
) {
  if (
    !current() ||
    !receipt ||
    receipt.request_id !== operation.requestId ||
    receipt.deployment_key !== operation.deploymentKey ||
    !['SUCCEEDED', 'REJECTED'].includes(receipt.status) ||
    (receipt.status === 'SUCCEEDED' &&
      (!Number.isSafeInteger(receipt.revision) ||
        receipt.revision! <= operation.revision ||
        receipt.deployment_status !== (operation.active ? 'ACTIVE' : 'DISABLED'))) ||
    (receipt.status === 'REJECTED' && !receipt.error_code)
  )
    return false
  try {
    localStorage.removeItem(stateKey(subject, operation.requestId))
  } catch {
    stateStorageError.value = '启停回执已确认，但无法清除本地请求记录，请恢复存储后重新核对。'
    return false
  }
  stateOperations.value = stateOperations.value.filter(
    (item) => item.requestId !== operation.requestId
  )
  stateStorageError.value = ''
  if (receipt.status === 'REJECTED') {
    message.error(receipt.error_message || '原启停请求已拒绝，请核对当前部署后决定下一步。')
    return true
  }
  confirmedStateReceipt.value = receipt
  data.value = data.value?.map((item) =>
    item.deploymentKey === operation.deploymentKey && item.revision < receipt.revision!
      ? {
          ...item,
          status: receipt.deployment_status as CapabilityDeployment['status'],
          revision: receipt.revision!
        }
      : item
  )
  confirmedChange.value = `原${operation.active ? '启用' : '停用'}请求已确认（修订 ${receipt.revision}）；当前状态以列表为准`
  if (!canRead.value) message.success(confirmedChange.value)
  else if ((await reload()) !== undefined && current()) {
    directoryLoaded.value = true
    message.success(confirmedChange.value)
  }
  return true
}
async function checkState(operation: StateOperation, automatic = false) {
  const subject = owner.value,
    identity = auth.identityVersion,
    session = getSessionId()
  if (!subject || !session || !canRead.value) return
  const current = () =>
    !disposed &&
    owner.value === subject &&
    auth.identityVersion === identity &&
    getSessionId() === session &&
    canRead.value
  await run(async () => {
    const receipt = await api.capabilities.deploymentStateReceipt(operation.requestId)
    if (!current()) return
    if (!(await acceptStateReceipt(receipt, operation, subject, current)) && !automatic)
      message.warning('尚未查到匹配的原启停回执，结果仍未确认。')
  })
}
function canContinueState(operation: StateOperation) {
  return (
    auth.user?.permissions.includes(
      operation.active ? 'foundation.capabilities.run' : 'foundation.capabilities.cancel'
    ) === true
  )
}
async function continueState(operation: StateOperation) {
  if (
    busy.value ||
    stateStorageError.value ||
    !canContinueState(operation) ||
    !stateOperations.value.some((item) => item.requestId === operation.requestId)
  )
    return
  const subject = owner.value,
    identity = auth.identityVersion,
    session = getSessionId()
  if (!subject || !session) return
  const current = () =>
    !disposed &&
    owner.value === subject &&
    auth.identityVersion === identity &&
    getSessionId() === session
  await run(async () => {
    const receipt = await api.capabilities.setDeploymentActiveWithReceipt(
      operation.requestId,
      operation.deploymentKey,
      operation.revision,
      operation.active
    )
    await acceptStateReceipt(receipt, operation, subject, current)
  })
}
type CreationOperation = { requestId: string; deploymentKey?: string }
const creationOperations = ref<CreationOperation[]>([])
const creationUnknown = computed(() => creationOperations.value[0])
const creationInputRequestId = ref('')
const creationStorageError = ref('')
const storagePrefix = (subject: string) => `skillforge.tool-deployment-create:${subject}:`
const storageKey = (subject: string, requestId: string) => storagePrefix(subject) + requestId
const checkedDeployment = ref<CapabilityDeployment>()
const checkedRequestId = ref('')
const { busy, error, run } = useTask(),
  open = ref(false),
  saved = ref(''),
  confirmedChange = ref(''),
  versions = ref<CapabilityVersion[]>([]),
  form = reactive<CreateCapabilityDeploymentInput>({
    toolVersionId: '',
    providerType: 'MCP',
    providerRef: '',
    environment: 'DEVELOPMENT'
  })
function syncOperations(subject: string, preserveExisting = false) {
  const operations: CreationOperation[] = []
  creationStorageError.value = ''
  if (!subject) {
    creationOperations.value = operations
    return
  }
  try {
    for (let i = 0; i < localStorage.length; i++) {
      const key = localStorage.key(i)
      if (!key?.startsWith(storagePrefix(subject))) continue
      const text = localStorage.getItem(key)
      if (!text) throw Error('invalid')
      const record = JSON.parse(text) as CreationOperation
      if (
        !record ||
        !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(record.requestId) ||
        (record.deploymentKey !== undefined &&
          (typeof record.deploymentKey !== 'string' || !record.deploymentKey)) ||
        key !== storageKey(subject, record.requestId)
      )
        throw Error('invalid')
      operations.push({ requestId: record.requestId, deploymentKey: record.deploymentKey })
    }
  } catch {
    creationStorageError.value = '无法读取原创建记录，请恢复浏览器存储后重新进入。'
  }
  if (preserveExisting) {
    for (const existing of creationOperations.value) {
      if (!operations.some((item) => item.requestId === existing.requestId))
        operations.push(existing)
    }
  }
  creationOperations.value = operations
}
function onCreationStorage(event: StorageEvent) {
  if (
    event.storageArea === localStorage &&
    (!event.key || event.key.startsWith(storagePrefix(owner.value)))
  )
    syncOperations(owner.value, true)
}
window.addEventListener('storage', onCreationStorage)
onScopeDispose(() => window.removeEventListener('storage', onCreationStorage))
useUnsavedChanges({
  dirty: () => !!creationUnknown.value,
  leaveMessage: () => '创建结果尚未确认，请保留原请求并核对，勿重复创建。'
})
watch(
  owner,
  (subject, previous) => {
    if (previous && subject !== previous) {
      open.value = false
      checkedDeployment.value = undefined
      Object.assign(form, {
        toolVersionId: '',
        providerType: 'MCP',
        providerRef: '',
        environment: 'DEVELOPMENT'
      })
      invalidate()
    }
    creationInputRequestId.value = ''
    syncOperations(subject)
  },
  { immediate: true, flush: 'sync' }
)
watch(
  [owner, () => auth.identityVersion, () => auth.sessionIdentity, canRead],
  () => {
    invalidate()
    data.value = undefined
    directoryLoaded.value = false
    confirmedChange.value = ''
    checkedDeployment.value = undefined
  },
  { flush: 'sync' }
)
watch(error, (value) => {
  if (value) message.error(value)
})
watch(loadError, (value) => {
  if (!value) return
  if (confirmedChange.value)
    message.warning(`${confirmedChange.value}；列表刷新失败。请点击“刷新”，不必重复操作。`)
  else message.error(`工具部署列表读取失败：${value}。请点击“刷新”重试。`)
})
const providerOptions = computed(() =>
  form.providerType === 'PLATFORM'
    ? platformTools.value
        .filter(
          (tool) =>
            tool.name ===
            versions.value.find((version) => version.id === form.toolVersionId)?.descriptor
              .tool_name
        )
        .map((tool) => ({
          id: tool.name,
          label: tool.name,
          description: tool.description
        }))
    : connectors.value
        .filter(
          (connector) =>
            form.providerType !== 'MCP' || connector.executor_type?.toLowerCase() === 'mcp'
        )
        .map((connector) => ({ id: connector.instance_id, label: connector.name }))
)
watch([() => form.providerType, () => form.toolVersionId], ([provider], [previousProvider]) => {
  if (provider === 'PLATFORM') form.providerRef = providerOptions.value[0]?.id ?? ''
  else if (provider !== previousProvider) form.providerRef = ''
})
const { requestClose } = useDrawerClose({
  open: () => open.value,
  busy: () => busy.value,
  dirty: () => open.value && !creationUnknown.value && JSON.stringify(form) !== saved.value,
  close: () => {
    open.value = false
  }
})
async function openCreate() {
  if (creationUnknown.value || creationStorageError.value || !canCreate.value) return
  const subject = owner.value,
    identity = auth.identityVersion,
    session = getSessionId()
  const current = () =>
    !disposed &&
    owner.value === subject &&
    auth.identityVersion === identity &&
    getSessionId() === session &&
    canCreate.value
  await run(async () => {
    let defs: Awaited<ReturnType<typeof api.capabilities.definitions>>,
      tools: Awaited<ReturnType<typeof api.capabilities.platformTools>>,
      instances: Awaited<ReturnType<typeof api.connectors.instances>>,
      loadedVersions: CapabilityVersion[]
    try {
      ;[defs, tools, instances] = await Promise.all([
        api.capabilities.definitions('TOOL', 'PUBLISHED'),
        api.capabilities.platformTools(),
        canReadConnectors.value
          ? api.connectors.instances()
          : Promise.resolve({ total: 0, items: [] })
      ])
      loadedVersions = await Promise.all(
        defs
          .filter((d) => d.defaultVersionId)
          .map((d) => api.capabilities.getVersion(d.defaultVersionId!))
      )
    } catch (cause) {
      if (!current()) {
        if (cause instanceof HttpClientError) cause.claimFeedback()
        return
      }
      throw cause
    }
    if (!current()) return
    platformTools.value = tools
    connectors.value = instances.items
    versions.value = loadedVersions
    Object.assign(form, {
      toolVersionId: '',
      providerType: canReadConnectors.value ? 'MCP' : 'PLATFORM',
      providerRef: '',
      environment: 'DEVELOPMENT'
    })
    saved.value = JSON.stringify(form)
    open.value = true
  })
}
async function create() {
  if (creationUnknown.value || creationStorageError.value || !canCreate.value) return
  if (!(await formRef.value?.validate().catch(() => false))) return
  let checkAfterWrite = false
  await run(async () => {
    if (!form.providerRef.trim() || !form.toolVersionId)
      throw Error('请选择已发布工具和对应的连接器或平台工具')
    if (!providerOptions.value.some((option) => option.id === form.providerRef))
      throw Error('所选连接器或平台工具已不可用，请从当前列表重新选择')
    const subject = owner.value,
      identity = auth.identityVersion,
      session = getSessionId()
    if (!subject || !session) throw Error('当前登录状态不可用，请重新登录')
    syncOperations(subject, true)
    if (creationUnknown.value || creationStorageError.value) return
    const operation: CreationOperation = {
      requestId: crypto.randomUUID()
    }
    try {
      localStorage.setItem(storageKey(subject, operation.requestId), JSON.stringify(operation))
    } catch {
      creationStorageError.value = '无法保存原创建记录，尚未发送请求。'
      return
    }
    creationOperations.value = [...creationOperations.value, operation]
    creationInputRequestId.value = operation.requestId
    const current = () =>
      !disposed &&
      owner.value === subject &&
      auth.identityVersion === identity &&
      getSessionId() === session &&
      canCreate.value &&
      creationOperations.value.some(
        (item) =>
          item.requestId === operation.requestId && item.deploymentKey === operation.deploymentKey
      )
    let receipt: Awaited<ReturnType<typeof api.capabilities.createDeploymentWithReceipt>>
    try {
      receipt = await api.capabilities.createDeploymentWithReceipt(operation.requestId, { ...form })
    } catch (cause) {
      if (cause instanceof HttpClientError) cause.claimFeedback()
      if (current()) {
        checkAfterWrite = true
        message.warning('工具部署创建结果尚未确认，请核对原请求，不要重复创建。')
      }
      return
    }
    if (current() && !(await acceptCreation(receipt, operation, subject, current))) {
      checkAfterWrite = true
      message.warning('创建回执不匹配，请核对原请求，不要重复创建。')
    }
  })
  if (checkAfterWrite) {
    const operation = creationOperations.value.find(
      (item) => item.requestId === creationInputRequestId.value
    )
    if (operation) await checkCreation(true, operation)
  }
}
async function acceptCreation(
  receipt: Awaited<ReturnType<typeof api.capabilities.deploymentCreateReceipt>>,
  operation: CreationOperation,
  subject: string,
  current: () => boolean
) {
  if (
    !receipt ||
    receipt.request_id !== operation.requestId ||
    (operation.deploymentKey !== undefined && receipt.deployment_key !== operation.deploymentKey) ||
    !['SUCCEEDED', 'REJECTED'].includes(receipt.status) ||
    (receipt.status === 'SUCCEEDED' &&
      (typeof receipt.deployment_id !== 'string' ||
        !receipt.deployment_id ||
        !receipt.deployment_key)) ||
    (receipt.status === 'REJECTED' && receipt.error_code !== 'TOOL_DEPLOYMENT_CREATE_REJECTED')
  )
    return false
  if (!current()) return false
  const acceptedIdentity = auth.identityVersion,
    acceptedSession = getSessionId()
  const sameIdentity = () =>
    !disposed &&
    owner.value === subject &&
    auth.identityVersion === acceptedIdentity &&
    getSessionId() === acceptedSession
  try {
    const key = storageKey(subject, operation.requestId)
    const raw = localStorage.getItem(key)
    if (raw) {
      const saved = JSON.parse(raw) as CreationOperation
      if (
        saved.requestId !== operation.requestId ||
        saved.deploymentKey !== operation.deploymentKey
      )
        throw Error('invalid')
      localStorage.removeItem(key)
    }
  } catch {
    creationStorageError.value = '创建已确认，但无法清除本地请求记录，请恢复浏览器存储后核对。'
    return false
  }
  creationOperations.value = creationOperations.value.filter(
    (item) => item.requestId !== operation.requestId
  )
  creationStorageError.value = ''
  const ownsInput = creationInputRequestId.value === operation.requestId
  if (receipt.status === 'REJECTED') {
    if (ownsInput) creationInputRequestId.value = ''
    message.error(receipt.message || '工具部署创建被拒绝，请检查工具与提供方配置。')
    return true
  }
  if (ownsInput) {
    creationInputRequestId.value = ''
    open.value = false
  }
  confirmedChange.value = `工具部署已创建：${receipt.deployment_key}`
  invalidate()
  if (!canRead.value) {
    if (sameIdentity()) message.success(confirmedChange.value)
  } else if ((await reload()) !== undefined && sameIdentity() && canRead.value) {
    directoryLoaded.value = true
    message.success(confirmedChange.value)
  }
  return true
}
async function checkCreation(automatic = false, operation = creationUnknown.value) {
  const subject = owner.value,
    identity = auth.identityVersion,
    session = getSessionId()
  if (
    !operation ||
    !subject ||
    !session ||
    !canRead.value ||
    !creationOperations.value.some(
      (item) =>
        item.requestId === operation.requestId && item.deploymentKey === operation.deploymentKey
    )
  )
    return
  const current = () =>
    !disposed &&
    owner.value === subject &&
    auth.identityVersion === identity &&
    getSessionId() === session &&
    canRead.value &&
    creationOperations.value.some(
      (item) =>
        item.requestId === operation.requestId && item.deploymentKey === operation.deploymentKey
    )
  await run(async () => {
    let receipt: Awaited<ReturnType<typeof api.capabilities.deploymentCreateReceipt>>
    try {
      receipt = await api.capabilities.deploymentCreateReceipt(operation.requestId)
    } catch (cause) {
      if (!current() && cause instanceof HttpClientError) cause.claimFeedback()
      if (current()) throw cause
      return
    }
    if (!current()) return
    if (!(await acceptCreation(receipt, operation, subject, current)) && !automatic)
      message.warning('尚未查到匹配的原创建回执，结果仍待核对。')
  })
}
async function checkDeployment(operation: CreationOperation) {
  await run(async () => {
    const subject = owner.value,
      identity = auth.identityVersion,
      session = getSessionId()
    if (!operation?.deploymentKey || !session || !canRead.value) return
    checkedDeployment.value = undefined
    checkedRequestId.value = ''
    const current = () =>
      !disposed &&
      owner.value === subject &&
      auth.identityVersion === identity &&
      getSessionId() === session &&
      canRead.value &&
      creationOperations.value.some(
        (item) =>
          item.requestId === operation.requestId && item.deploymentKey === operation.deploymentKey
      )
    try {
      const deployment = await api.capabilities.getDeployment(operation.deploymentKey, {
        silent: true
      })
      if (!current()) return
      checkedDeployment.value = deployment
      checkedRequestId.value = operation.requestId
    } catch (cause) {
      if (!current()) {
        if (cause instanceof HttpClientError) cause.claimFeedback()
        return
      }
      throw cause
    }
  })
}
async function toggle(row: CapabilityDeployment) {
  const active = row.status !== 'ACTIVE'
  if (
    stateStorageError.value ||
    stateOperations.value.some((operation) => operation.deploymentKey === row.deploymentKey) ||
    !auth.user?.permissions.includes(
      active ? 'foundation.capabilities.run' : 'foundation.capabilities.cancel'
    )
  )
    return
  const subject = owner.value,
    identity = auth.identityVersion,
    session = getSessionId()
  if (!subject || !session) return
  const current = () =>
    !disposed &&
    owner.value === subject &&
    auth.identityVersion === identity &&
    getSessionId() === session
  await run(async () => {
    confirmedChange.value = ''
    const operation = {
      requestId: crypto.randomUUID(),
      deploymentKey: row.deploymentKey,
      revision: row.revision,
      active
    }
    try {
      localStorage.setItem(stateKey(subject, operation.requestId), JSON.stringify(operation))
    } catch {
      stateStorageError.value = '无法保存原启停请求记录，尚未发送请求。'
      return
    }
    stateOperations.value.push(operation)
    const receipt = await api.capabilities.setDeploymentActiveWithReceipt(
      operation.requestId,
      operation.deploymentKey,
      operation.revision,
      active
    )
    await acceptStateReceipt(receipt, operation, subject, current)
  })
}

const formTipId = useId()
</script>
