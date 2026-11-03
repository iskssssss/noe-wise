<template>
  <AdminPage title="MCP 连接器" description="通过标准 MCP 服务发现并部署工具。"
    ><template #extra
      ><ElButton
        type="primary"
        @click="edit()"
        :disabled="busy || !!pendingCreate || !!pendingUpdate || !!storageError"
        >新增连接器</ElButton
      ></template
    ><FeedbackNotice
      v-if="error || loadError"
      :title="error || loadError"
      :closable="false"
      type="error"
    />
    <section v-if="pendingCreate || storageError" class="admin-card" role="status">
      <p v-if="storageError">{{ storageError }}</p>
      <p v-else>连接器“{{ pendingCreate?.name }}”创建结果尚未确认，请核对原请求，不要重复创建。</p>
      <p v-if="pendingCreate && !canRead">
        当前账号没有连接器读取权限，请联系管理员授权后核对原请求。
      </p>
      <ElButton v-if="pendingCreate" :disabled="busy || !canRead" @click="checkCreation()"
        >核对创建结果</ElButton
      >
    </section>
    <section v-if="pendingUpdate && !open" class="admin-card" role="status">
      连接器“{{ pendingUpdate.name }}”的更新结果尚未确认，请核对原请求，不要重复保存。
      <span v-if="!canRead">当前账号没有连接器读取权限，请联系管理员授权。</span>
      <ElButton :disabled="busy || !canRead" @click="checkUpdate()">核对更新结果</ElButton>
    </section>
    <section v-if="recoveryConnector && canOnboard" class="admin-card" role="status">
      连接器已保存，可继续接入工具。
      <ElButton
        link
        :disabled="busy || !!pendingUpdate || !!storageError"
        @click="navigateToTools(recoveryConnector)"
        >继续接入原连接器</ElButton
      >
    </section>
    <div class="admin-toolbar admin-card">
      <ElInput
        v-model="query"
        clearable
        placeholder="查找连接器名称或服务地址"
        aria-label="查找连接器"
      />
      <div class="admin-actions">
        <span class="admin-muted" role="status">{{ filtered.length }} 个连接器</span
        ><ElButton :loading="loading" @click="reload">刷新连接器</ElButton>
      </div>
    </div>
    <ElTable
      table-layout="auto"
      :data="filtered"
      v-loading="loading"
      row-key="id"
      stripe
      :empty-text="
        query ? '没有匹配的连接器，试试其他关键词' : '尚未接入 MCP 服务，新增连接器后发现工具'
      "
      ><ElTableColumn min-width="200" show-overflow-tooltip prop="name" label="名称"></ElTableColumn
      ><ElTableColumn
        min-width="280"
        show-overflow-tooltip
        prop="base_url"
        label="服务地址"
      ></ElTableColumn
      ><ElTableColumn width="128" show-overflow-tooltip label="状态"
        ><template #default="{ row }">{{ statusLabel(row.status) }}</template></ElTableColumn
      ><ElTableColumn width="196" class-name="admin-table-actions" prop="actions" label="操作"
        ><template #default="{ row }"
          ><ElButton
            link
            @click="edit(row as ConnectorInstanceItem)"
            :disabled="busy || !!pendingCreate || !!pendingUpdate || !!storageError"
            >编辑</ElButton
          ><ElButton
            link
            @click="router.push({ path: '/admin/tools', query: { connectorId: row.instance_id } })"
            :disabled="busy || !!pendingUpdate || !!storageError"
            >接入工具</ElButton
          ><ElButton
            v-permission="['foundation.connectors.delete']"
            type="danger"
            link
            @click="remove(row as ConnectorInstanceItem)"
            :disabled="busy || !!pendingCreate || !!pendingUpdate || !!storageError"
            >删除</ElButton
          ></template
        ></ElTableColumn
      ></ElTable
    >
    <ElDrawer
      v-model="open"
      :before-close="closeDrawer0"
      :title="editing ? '编辑 MCP 连接器' : '新增 MCP 连接器'"
      size="680px"
      append-to-body
      direction="rtl"
      :close-on-click-modal="false"
      :close-on-press-escape="!busy"
      :show-close="!busy"
      class="admin-drawer"
      ><p v-if="pendingUpdate" role="status">
        更新结果尚未确认，请核对原请求，不要重复保存。
        <span v-if="!canRead">当前账号没有连接器读取权限，请联系管理员授权。</span>
        <ElButton :disabled="busy || !canRead" @click="checkUpdate()">核对更新结果</ElButton>
      </p>
      <p v-if="storageError" role="status">{{ storageError }}</p>
      <ElForm
        ref="formRef"
        :model="form"
        label-position="top"
        @submit.prevent="save()"
        :disabled="busy || !!pendingCreate || !!pendingUpdate || !!storageError"
        scroll-to-error
        @keydown.capture="guardFormEnter"
        ><ElFormItem label="名称" prop="name" :rules="[{ required: true, message: '请输入名称' }]"
          ><ElInput
            v-described-by="formTipId + '-field-0'"
            :aria-describedby="formTipId + '-field-0'"
            v-model="form.name"
          />
          <FieldTip :id="formTipId + '-field-0'"
            >为外部 MCP 服务填写易识别的名称。</FieldTip
          > </ElFormItem
        ><ElFormItem
          label="服务地址"
          prop="base_url"
          :rules="[
            { required: true, message: '请输入 MCP 服务地址' },
            { type: 'url', message: '请输入完整 URL' }
          ]"
          ><ElInput
            v-described-by="formTipId + '-field-1'"
            :aria-describedby="formTipId + '-field-1'"
            v-model="form.base_url"
            placeholder="https://mcp.example.com/mcp"
          />
          <FieldTip :id="formTipId + '-field-1'"
            >填写 MCP 服务的实际连接地址。</FieldTip
          > </ElFormItem
        ><ElFormItem label="认证方式"
          ><ElSelect
            v-described-by="formTipId + '-field-2'"
            :aria-describedby="formTipId + '-field-2'"
            v-model="form.auth_type"
            ><ElOption label="无认证" value="none" /><ElOption label="Bearer Token" value="bearer"
          /></ElSelect>
          <FieldTip :id="formTipId + '-field-2'"
            >当前支持无认证或 Bearer Token，凭据仅通过受保护的凭据字段保存。</FieldTip
          > </ElFormItem
        ><ElFormItem v-if="form.auth_type !== 'none'" label="访问凭据"
          ><ElInput
            v-described-by="formTipId + '-field-3'"
            :aria-describedby="formTipId + '-field-3'"
            v-model="form.auth_credential"
            type="password"
            :placeholder="editing ? '留空保留当前凭据' : '填写访问凭据'"
            show-password
            autocomplete="new-password"
          />
          <FieldTip :id="formTipId + '-field-3'"
            >填写 Bearer Token；编辑时留空保留现有凭据。</FieldTip
          > </ElFormItem
        ><ElFormItem label="MCP 协议版本">
          <ElSelect
            v-model="form.protocol"
            aria-label="MCP 协议版本"
            v-described-by="formTipId + '-protocol'"
            :aria-describedby="formTipId + '-protocol'"
          >
            <ElOption label="2026-07-28（无状态）" value="2026-07-28" />
            <ElOption label="2025-11-25（握手）" value="2025-11-25" />
          </ElSelect>
          <FieldTip :id="formTipId + '-protocol'"
            >按服务声明选择协议版本；当前不支持其他附加请求头或 Vault 路径。</FieldTip
          > </ElFormItem
        ><ElFormItem v-if="editing" label="状态"
          ><ElSelect
            v-described-by="formTipId + '-field-6'"
            :aria-describedby="formTipId + '-field-6'"
            v-model="form.status"
            ><ElOption value="active" label="启用" /><ElOption value="disabled" label="停用"
          /></ElSelect>
          <FieldTip :id="formTipId + '-field-6'">选择启用或停用，控制连接器是否可供调用。</FieldTip>
        </ElFormItem></ElForm
      ><template #footer
        ><ElButton @click="closeDrawer0" :disabled="busy">取消</ElButton
        ><ElButton
          v-permission="[editing ? 'foundation.connectors.update' : 'foundation.connectors.create']"
          type="primary"
          :loading="busy"
          :disabled="!!pendingCreate || !!pendingUpdate || !!storageError"
          @click="save()"
          >保存</ElButton
        ><ElButton
          v-if="!editing && canOnboard"
          v-permission="['foundation.connectors.create']"
          type="primary"
          :loading="busy"
          :disabled="!!pendingCreate || !!pendingUpdate || !!storageError"
          @click="save(true)"
          >保存并接入工具</ElButton
        ></template
      ></ElDrawer
    >
  </AdminPage>
</template>
<script setup lang="ts">
import FeedbackNotice from '@skillforge/shared/feedback/FeedbackNotice.vue'
import { vDescribedBy } from '@skillforge/shared/directives/describedBy'

import FieldTip from '@skillforge/shared/components/FieldTip.vue'

import { guardFormEnter } from '@skillforge/shared/admin'
import { statusLabel } from '@skillforge/shared/admin'
import { computed, onMounted, onScopeDispose, ref, reactive, useId, watch } from 'vue'
import { HttpClientError } from '@skillforge/shared/utils/http'
import { useRouter } from 'vue-router'
import type { FormInstance } from 'element-plus'
import {
  AdminPage,
  useTask,
  useResource,
  useUnsavedChanges,
  useDrawerClose
} from '@skillforge/shared/admin'
import { confirm, message } from '@skillforge/shared/feedback'
import { api, type ConnectorInstanceItem, type ConnectorInstanceDetail } from '../../api'
import { useAuthStore } from '../../stores/auth'
const router = useRouter()
const auth = useAuthStore()
type PendingCreate = { requestId: string; name: string; onboard: boolean }
type PendingUpdate = { requestId: string; instanceId: string; name: string }
const pendingCreate = ref<PendingCreate>()
const pendingUpdate = ref<PendingUpdate>()
const storageError = ref('')
const recoveryConnector = ref('')
const actor = computed(() =>
  auth.user?.tenant_id && auth.user?.sub ? `${auth.user.tenant_id}:${auth.user.sub}` : ''
)
const storageKey = (subject: string) => `skillforge.connector-create:${subject}`
const updateStorageKey = (subject: string) => `skillforge.connector-update:${subject}`
function notifyConnectorCreated(subject: string) {
  if (typeof BroadcastChannel === 'undefined') return
  try {
    const channel = new BroadcastChannel('skillforge.connector.changed')
    channel.postMessage(subject)
    channel.close()
  } catch {
    // 通知失败不影响已确认的创建结果；工具页仍可在返回时刷新。
  }
}
const canRead = computed(
  () => auth.user?.permissions.includes('foundation.connectors.read') === true
)
let disposed = false
onScopeDispose(() => {
  disposed = true
})
watch(
  () => auth.identityVersion,
  () => {
    pendingCreate.value = undefined
    pendingUpdate.value = undefined
    storageError.value = ''
    recoveryConnector.value = ''
    if (!actor.value) return
    try {
      const raw = sessionStorage.getItem(storageKey(actor.value))
      if (raw) {
        const operation = JSON.parse(raw) as PendingCreate
        if (
          !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(
            operation.requestId
          ) ||
          typeof operation.name !== 'string' ||
          typeof operation.onboard !== 'boolean'
        )
          throw Error('invalid')
        pendingCreate.value = operation
      }
      const savedUpdate = sessionStorage.getItem(updateStorageKey(actor.value))
      if (savedUpdate) {
        const operation = JSON.parse(savedUpdate) as PendingUpdate
        if (
          !operation ||
          !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(
            operation.requestId
          ) ||
          typeof operation.instanceId !== 'string' ||
          !operation.instanceId ||
          typeof operation.name !== 'string'
        )
          throw Error('invalid')
        pendingUpdate.value = operation
      }
    } catch {
      storageError.value = '无法读取原连接器操作记录，请恢复浏览器存储后重新进入。'
    }
  },
  { immediate: true, flush: 'sync' }
)
const canOnboard = computed(() =>
  [
    'foundation.connectors.read',
    'foundation.connectors.update',
    'foundation.capabilities.read',
    'foundation.capabilities.create',
    'foundation.capabilities.publish'
  ].every((permission) => auth.user?.permissions.includes(permission))
)
const { data, loading, error: loadError, reload } = useResource(() => api.connectors.instances())
onMounted(() => {
  void reload()
  void (async () => {
    if (pendingCreate.value && canRead.value) await checkCreation(true)
    if (!disposed && pendingUpdate.value && canRead.value) await checkUpdate(true)
  })()
})
const { busy, error, run } = useTask(),
  open = ref(false),
  editing = ref<ConnectorInstanceDetail>(),
  saved = ref(''),
  formRef = ref<FormInstance>()
const form = reactive({
  name: '',
  base_url: '',
  auth_type: 'none',
  auth_credential: '',
  protocol: '2026-07-28',
  status: 'active'
})
watch(
  () => auth.identityVersion,
  () => {
    form.auth_credential = ''
    open.value = false
    editing.value = undefined
  }
)
const snapshot = () => JSON.stringify(form)
const dirty = computed(() => open.value && snapshot() !== saved.value)
useUnsavedChanges({
  dirty: () => dirty.value,
  busy: () => busy.value || !!pendingCreate.value || !!pendingUpdate.value || !!storageError.value
})
const query = ref('')
const filtered = computed(() =>
  (data.value?.items ?? []).filter((item) =>
    (item.name + ' ' + item.base_url).toLowerCase().includes(query.value.trim().toLowerCase())
  )
)
async function edit(row?: ConnectorInstanceItem) {
  if (pendingCreate.value || pendingUpdate.value || storageError.value) return
  const subject = actor.value,
    identity = auth.identityVersion
  await run(async () => {
    const detail = row ? await api.connectors.instanceDetail(row.instance_id) : undefined
    if (disposed || actor.value !== subject || auth.identityVersion !== identity) return
    editing.value = detail
    Object.assign(form, {
      name: detail?.name ?? '',
      base_url: detail?.base_url ?? '',
      auth_type: detail?.auth_type === 'bearer' ? 'bearer' : 'none',
      auth_credential: '',
      protocol: String(detail?.headers?.['MCP-Protocol-Version'] ?? '2026-07-28'),
      status: detail?.status ?? 'active'
    })
    saved.value = snapshot()
    open.value = true
  })
}
function clearPendingUpdate(subject: string) {
  try {
    sessionStorage.removeItem(updateStorageKey(subject))
  } catch {
    storageError.value = '无法清除原连接器更新记录，请恢复浏览器存储后重新核对。'
    return false
  }
  pendingUpdate.value = undefined
  storageError.value = ''
  return true
}
async function save(onboard = false) {
  if (pendingCreate.value || pendingUpdate.value || storageError.value) return
  const owner = actor.value,
    ownerIdentity = auth.identityVersion
  const connectorId = await run(async () => {
    if (!(await formRef.value?.validate().catch(() => false))) return
    if (disposed || !open.value || actor.value !== owner || auth.identityVersion !== ownerIdentity)
      return
    const body = {
      name: form.name,
      base_url: form.base_url,
      auth_type: form.auth_type,
      auth_credential: form.auth_type === 'bearer' ? form.auth_credential : '',
      vault_secret_path: '',
      status: form.status,
      executor_type: 'mcp' as const,
      headers: { 'MCP-Protocol-Version': form.protocol }
    }
    if (editing.value) {
      const subject = actor.value,
        identity = auth.identityVersion
      if (!subject) throw Error('当前身份不可用，请重新登录')
      const operation = {
        requestId: crypto.randomUUID(),
        instanceId: editing.value.instance_id,
        name: body.name
      }
      try {
        sessionStorage.setItem(updateStorageKey(subject), JSON.stringify(operation))
      } catch {
        storageError.value = '无法保存连接器更新记录，本次未发送。请恢复浏览器存储后重试。'
        throw Error(storageError.value)
      }
      pendingUpdate.value = operation
      try {
        const result = await api.connectors.updateInstanceWithReceipt(
          operation.requestId,
          operation.instanceId,
          {
            ...body,
            capabilities: editing.value.capabilities
          }
        )
        if (disposed || actor.value !== subject || auth.identityVersion !== identity) return
        if (
          !result ||
          result.request_id !== operation.requestId ||
          result.instance_id !== operation.instanceId
        )
          throw Error('更新回执不完整，请核对原请求')
        if (!clearPendingUpdate(subject)) return
        form.auth_credential = ''
        saved.value = snapshot()
        open.value = false
        await reload()
        if (!disposed && actor.value === subject && auth.identityVersion === identity)
          message.success('连接器已保存')
      } catch (cause) {
        if (
          !disposed &&
          actor.value === subject &&
          auth.identityVersion === identity &&
          cause instanceof HttpClientError &&
          cause.code === 'CONNECTOR_UPDATE_REJECTED'
        ) {
          clearPendingUpdate(subject)
        }
        throw cause
      }
      return
    }
    const subject = actor.value,
      identity = auth.identityVersion
    if (!subject) throw Error('当前身份不可用，请重新登录')
    const operation = { requestId: crypto.randomUUID(), name: body.name, onboard }
    try {
      sessionStorage.setItem(storageKey(subject), JSON.stringify(operation))
    } catch {
      storageError.value = '无法保存连接器创建记录，请恢复浏览器存储后重试。'
      throw Error(storageError.value)
    }
    pendingCreate.value = operation
    try {
      const receipt = await api.connectors.createInstanceWithReceipt(operation.requestId, body)
      if (disposed || actor.value !== subject || auth.identityVersion !== identity) return
      if (!receipt || receipt.request_id !== operation.requestId || !receipt.instance_id)
        throw Error('创建回执不完整，请核对原请求')
      sessionStorage.removeItem(storageKey(subject))
      pendingCreate.value = undefined
      notifyConnectorCreated(subject)
      form.auth_credential = ''
      saved.value = snapshot()
      open.value = false
      await reload()
      if (disposed || actor.value !== subject || auth.identityVersion !== identity) return
      if (!onboard) message.success('连接器已保存')
      return receipt.instance_id
    } catch (cause) {
      if (
        !disposed &&
        actor.value === subject &&
        auth.identityVersion === identity &&
        cause instanceof HttpClientError &&
        cause.code === 'CONNECTOR_CREATE_REJECTED'
      ) {
        sessionStorage.removeItem(storageKey(subject))
        pendingCreate.value = undefined
      }
      throw cause
    }
  })
  if (onboard && connectorId) await navigateToTools(connectorId)
}
async function navigateToTools(connectorId: string) {
  const identity = auth.identityVersion
  try {
    const failure = await router.push({ path: '/admin/tools', query: { connectorId } })
    if (identity !== auth.identityVersion) return
    if (!failure) {
      if (!disposed) recoveryConnector.value = ''
      message.success('连接器已保存，请选择要接入的工具')
      return
    }
    if (disposed) return
  } catch {
    if (disposed || identity !== auth.identityVersion) return
  }
  recoveryConnector.value = connectorId
  message.warning('连接器已保存，未能进入工具接入，请使用原连接器入口继续。')
}
async function checkCreation(automatic = false) {
  const operation = pendingCreate.value,
    subject = actor.value,
    identity = auth.identityVersion
  if (!operation || !subject || !canRead.value) return
  const receipt = await run(async () => {
    const result = await api.connectors.creationResult(operation.requestId)
    if (
      disposed ||
      actor.value !== subject ||
      auth.identityVersion !== identity ||
      pendingCreate.value !== operation
    )
      return
    if (!result || result.request_id !== operation.requestId || !result.instance_id) {
      if (!automatic) message.warning('尚未查到原创建结果，请继续核对，不要重复创建。')
      return
    }
    sessionStorage.removeItem(storageKey(subject))
    pendingCreate.value = undefined
    notifyConnectorCreated(subject)
    form.auth_credential = ''
    saved.value = snapshot()
    open.value = false
    await reload()
    if (disposed || actor.value !== subject || auth.identityVersion !== identity) return
    message.success('连接器创建已确认')
    return result
  })
  if (receipt && operation.onboard && canOnboard.value) await navigateToTools(receipt.instance_id)
}
async function checkUpdate(automatic = false) {
  const operation = pendingUpdate.value,
    subject = actor.value,
    identity = auth.identityVersion
  if (!operation || !subject || !canRead.value) return
  await run(async () => {
    const result = await api.connectors.updateResult(operation.requestId)
    if (
      disposed ||
      actor.value !== subject ||
      auth.identityVersion !== identity ||
      pendingUpdate.value !== operation
    )
      return
    if (
      !result ||
      result.request_id !== operation.requestId ||
      result.instance_id !== operation.instanceId
    ) {
      if (!automatic) message.warning('尚未查到原更新结果，请继续核对，不要重复保存。')
      return
    }
    if (!clearPendingUpdate(subject)) return
    form.auth_credential = ''
    saved.value = snapshot()
    open.value = false
    await reload()
    if (!disposed && actor.value === subject && auth.identityVersion === identity)
      message.success('连接器更新已确认')
  })
}
async function remove(row: ConnectorInstanceItem) {
  const subject = actor.value,
    identity = auth.identityVersion
  const current = () =>
    !disposed &&
    !!subject &&
    actor.value === subject &&
    auth.identityVersion === identity &&
    auth.user?.permissions.includes('foundation.connectors.delete') === true
  if (!current() || busy.value || pendingCreate.value || pendingUpdate.value || storageError.value)
    return
  if (
    await confirm({
      title: '删除连接器',
      confirmText: '删除连接器',
      danger: true,
      content: '删除“' + row.name + '”？已挂载工具将无法继续访问。'
    })
  ) {
    if (
      !current() ||
      busy.value ||
      pendingCreate.value ||
      pendingUpdate.value ||
      storageError.value
    )
      return
    await run(async () => {
      try {
        await api.connectors.removeInstance(row.instance_id)
      } catch (cause) {
        if (!current()) {
          if (cause instanceof HttpClientError) cause.claimFeedback()
          throw 'cancel'
        }
        throw cause
      }
      if (!current()) return
      if (recoveryConnector.value === row.instance_id) recoveryConnector.value = ''
      message.success('连接器已删除')
      await reload()
    })
  }
}
const { requestClose: closeDrawer0 } = useDrawerClose({
  open: () => open.value,
  busy: () => busy.value,
  dirty: () => open.value && dirty.value,
  close: () => {
    open.value = false
  }
})
const formTipId = useId()
</script>
