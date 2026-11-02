<template>
  <AdminPage
    :embedded="embedded"
    :title="embedded ? '接入与执行凭证' : '机构治理'"
    description="维护服务凭据、回调订阅、审计与知识写入审批。"
    ><FeedbackNotice
      v-if="error || loadError"
      :title="error || loadError"
      :closable="false"
      type="error"
    /><ElAlert
      v-if="credentialUncertain"
      title="本次凭证操作结果未知，原输入已保留，请联系平台核对签发或变更审计。核实前不要重复操作；刷新目录仅查询当前事实，不会重新签发。"
      type="warning"
      :closable="false"
    /><ElTabs v-if="!embedded" v-model="tab" @tab-change="reload"
      ><ElTabPane name="principals" label="服务身份" /><ElTabPane
        name="webhooks"
        label="Webhook" /><ElTabPane name="audit" label="审计日志" /><ElTabPane
        name="proposals"
        label="知识写入审批"
    /></ElTabs>
    <div class="admin-toolbar admin-card">
      <ElButton
        v-if="
          (tab === 'principals' &&
            auth.user?.permissions.includes('foundation.service_principals.create')) ||
          tab === 'webhooks'
        "
        type="primary"
        @click="openCreate"
        :disabled="busy || credentialUncertain"
        >{{ tab === 'principals' ? '创建服务身份' : '订阅回调' }}</ElButton
      ><ElInput
        v-described-by="formTipId + '-group-0'"
        :aria-describedby="formTipId + '-group-0'"
        clearable
        v-if="tab === 'audit'"
        v-model="action"
        placeholder="筛选操作类型，按 Enter 查询"
        @keyup.enter="reload"
        @clear="reload"
      /><ElButton @click="reload" :disabled="busy">刷新</ElButton>

      <FieldTip v-if="tab === 'audit'" :id="formTipId + '-group-0'"
        >审计页可按操作类型筛选；留空不限制操作类型。</FieldTip
      >
    </div>
    <p class="admin-muted" role="status">
      {{
        tab === 'principals'
          ? '为业务系统分配最小可用权限；新凭据只在创建或轮换后展示一次。'
          : tab === 'webhooks'
            ? '维护事件回调订阅与投递目标。'
            : tab === 'audit'
              ? '查看最近 100 条匹配操作的审计记录。'
              : '仅展示待审批提案；展开内容后核对事实，再批准或拒绝。'
      }}
    </p>
    <ElTable
      table-layout="auto"
      v-if="tab === 'principals'"
      :data="principals"
      v-loading="loading"
      empty-text="暂无服务身份，可点击“创建服务身份”为集成分配访问权限"
      ><ElTableColumn
        min-width="200"
        show-overflow-tooltip
        prop="integration_id"
        label="集成标识"
      /><ElTableColumn
        min-width="200"
        show-overflow-tooltip
        prop="principal_id"
        label="服务身份"
      /><ElTableColumn
        min-width="200"
        show-overflow-tooltip
        prop="api_key_prefix"
        label="Key 前缀"
      /><ElTableColumn
        min-width="200"
        show-overflow-tooltip
        prop="scopes"
        label="权限"
      /><ElTableColumn width="128" show-overflow-tooltip label="状态"
        ><template #default="{ row }">{{ statusLabel(row.status) }}</template></ElTableColumn
      ><ElTableColumn width="160" class-name="admin-table-actions" label="操作"
        ><template #default="{ row }"
          ><ElButton
            v-permission="['foundation.service_principals.update']"
            link
            :disabled="busy || credentialUncertain"
            @click="rotate(row as ServicePrincipal)"
            >轮换</ElButton
          ><ElButton
            v-permission="['foundation.service_principals.update']"
            link
            :disabled="busy || credentialUncertain"
            @click="suspend(row as ServicePrincipal)"
            >暂停</ElButton
          ><ElButton
            v-permission="['foundation.service_principals.update']"
            link
            type="danger"
            :disabled="busy || credentialUncertain"
            @click="revoke(row as ServicePrincipal)"
            >吊销</ElButton
          ></template
        ></ElTableColumn
      ></ElTable
    >
    <ElTable
      table-layout="auto"
      v-if="tab === 'webhooks'"
      :data="webhooks"
      v-loading="loading"
      empty-text="暂无回调订阅，可点击“订阅回调”添加事件接收地址"
      ><ElTableColumn
        min-width="280"
        show-overflow-tooltip
        prop="endpoint_url"
        label="回调地址"
      /><ElTableColumn
        min-width="200"
        show-overflow-tooltip
        prop="session_id"
        label="会话"
      /><ElTableColumn width="128" show-overflow-tooltip label="状态"
        ><template #default="{ row }">{{ statusLabel(row.status) }}</template></ElTableColumn
      ><ElTableColumn width="80" class-name="admin-table-actions" label="操作"
        ><template #default="{ row }"
          ><ElButton
            v-permission="['foundation.webhooks.update']"
            link
            :disabled="busy"
            @click="pauseWebhook(row as WebhookSubscription)"
            >暂停</ElButton
          ></template
        ></ElTableColumn
      ></ElTable
    >
    <ElTable
      table-layout="auto"
      v-if="tab === 'audit'"
      :data="audit"
      v-loading="loading"
      empty-text="最近记录中没有匹配的审计操作，可清除操作类型筛选后查看"
      ><ElTableColumn
        min-width="160"
        show-overflow-tooltip
        prop="action"
        label="操作"
      /><ElTableColumn
        min-width="200"
        show-overflow-tooltip
        prop="actor_id"
        label="操作者"
      /><ElTableColumn
        min-width="140"
        show-overflow-tooltip
        prop="resource_type"
        label="资源类型"
      /><ElTableColumn
        min-width="200"
        show-overflow-tooltip
        prop="resource_id"
        label="资源"
      /><ElTableColumn
        min-width="280"
        show-overflow-tooltip
        prop="message"
        label="说明"
      /><ElTableColumn
        min-width="200"
        show-overflow-tooltip
        prop="trace_id"
        label="Trace ID"
      /><ElTableColumn width="190" show-overflow-tooltip prop="created_at" label="时间"
        ><template #default="{ row }">{{ formatDateTime(row.created_at) }}</template></ElTableColumn
      ></ElTable
    >
    <ElTable
      table-layout="auto"
      v-if="tab === 'proposals'"
      :data="proposals"
      v-loading="loading"
      empty-text="暂无待审批的知识写入提案"
      ><ElTableColumn
        min-width="200"
        show-overflow-tooltip
        prop="proposal_id"
        label="提案"
      /><ElTableColumn
        min-width="200"
        show-overflow-tooltip
        prop="target"
        label="目标"
      /><ElTableColumn
        min-width="200"
        show-overflow-tooltip
        prop="source_event_id"
        label="来源事件"
      /><ElTableColumn min-width="280" label="内容"
        ><template #default="{ row }"
          ><ElDescriptions :column="1" border
            ><template v-for="(value, key) in row.payload" :key="key"
              ><ElDescriptionsItem :label="String(key)">{{ value }}</ElDescriptionsItem></template
            ></ElDescriptions
          ></template
        ></ElTableColumn
      ><ElTableColumn width="112" class-name="admin-table-actions" label="审批"
        ><template #default="{ row }"
          ><ElButton
            v-permission="['foundation.knowledge.publish']"
            link
            :disabled="busy"
            @click="decide(row as KnowledgeWriteProposal, true)"
            >批准</ElButton
          ><ElButton
            v-permission="['foundation.knowledge.update']"
            link
            type="danger"
            :disabled="busy"
            @click="decide(row as KnowledgeWriteProposal, false)"
            >拒绝</ElButton
          ></template
        ></ElTableColumn
      ></ElTable
    >
    <ElDrawer
      v-model="open"
      :title="tab === 'principals' ? '签发服务身份' : '新增 Webhook'"
      :before-close="requestClose"
      append-to-body
      direction="rtl"
      :close-on-click-modal="false"
      :close-on-press-escape="!busy && !credentialUncertain"
      :show-close="!busy && !credentialUncertain"
      class="admin-drawer"
      size="680px"
      ><FeedbackNotice
        v-if="error"
        :title="error"
        type="error"
        :closable="false"
        role="alert"
      /><ElForm
        ref="formRef"
        :model="form"
        label-position="top"
        @submit.prevent="create"
        :disabled="busy || credentialUncertain"
        scroll-to-error
        @keydown.capture="guardFormEnter"
        ><template v-if="tab === 'principals'"
          ><ElFormItem
            label="集成标识"
            prop="integration_id"
            :rules="[
              { required: true, whitespace: true, message: '请输入集成标识', trigger: 'blur' }
            ]"
            ><ElInput
              v-described-by="formTipId + '-field-0'"
              :aria-describedby="formTipId + '-field-0'"
              v-model="form.integration_id"
            />
            <FieldTip :id="formTipId + '-field-0'"
              >填写外部系统的集成标识，用于识别服务身份或 Webhook 订阅。</FieldTip
            > </ElFormItem
          ><ElFormItem
            label="授权 Scope"
            prop="scopes"
            :rules="[
              {
                type: 'array',
                min: 1,
                required: true,
                message: '请选择至少一项权限',
                trigger: 'change'
              }
            ]"
            ><ElSelect
              v-described-by="'principal-scope-help'"
              aria-describedby="principal-scope-help"
              v-model="form.scopes"
              multiple
              filterable
              placeholder="选择允许调用的权限"
              ><ElOption
                v-for="scope in scopeOptions"
                :key="scope.id"
                :value="scope.id"
                :label="scope.label"
                ><span>{{ scope.label }}</span
                ><small class="admin-muted"> {{ scope.id }}</small></ElOption
              ></ElSelect
            >
            <p id="principal-scope-help" class="admin-field-help">
              只授予此集成需要的调用权限；这里仅列出当前开放接口契约支持的权限。
            </p></ElFormItem
          ></template
        ><template v-else
          ><ElFormItem
            label="回调地址"
            prop="endpoint_url"
            :rules="[
              { required: true, whitespace: true, message: '请输入回调地址', trigger: 'blur' }
            ]"
            ><ElInput
              v-described-by="formTipId + '-field-2'"
              :aria-describedby="formTipId + '-field-2'"
              v-model="form.endpoint_url"
              placeholder="https://"
            />
            <FieldTip :id="formTipId + '-field-2'"
              >填写接收事件的 Webhook 地址，确保该端点可接收回调。</FieldTip
            > </ElFormItem
          ><ElFormItem label="会话 ID"
            ><EntitySelect
              v-described-by="formTipId + '-field-3'"
              :aria-describedby="formTipId + '-field-3'"
              v-model="form.session_id"
              :load="loadSessions"
              placeholder="留空订阅所有允许的会话"
            />
            <FieldTip :id="formTipId + '-field-3'"
              >选择订阅的会话；留空订阅权限范围内的所有会话。</FieldTip
            > </ElFormItem
          ><ElFormItem
            label="签名密钥"
            prop="signing_secret"
            :rules="[
              { required: true, whitespace: true, message: '请输入签名密钥', trigger: 'blur' }
            ]"
            ><ElInput
              v-described-by="'webhook-secret-help'"
              aria-describedby="webhook-secret-help"
              v-model="form.signing_secret"
              type="password"
              show-password
            />
            <p id="webhook-secret-help" class="admin-field-help">
              请与接收回调的服务约定签名密钥，并妥善保存。
            </p></ElFormItem
          ></template
        ></ElForm
      ><template #footer
        ><ElButton @click="requestClose" :disabled="busy">取消</ElButton
        ><ElButton
          v-permission="['foundation.service_principals.create']"
          type="primary"
          :loading="busy"
          :disabled="credentialUncertain"
          @click="create"
          >创建</ElButton
        ></template
      ></ElDrawer
    >
    <ElDialog
      append-to-body
      class="admin-dialog"
      :close-on-click-modal="false"
      v-model="secretOpen"
      title="请保存本次签发凭据"
      width="650px"
      @closed="issued = undefined"
      ><ElAlert
        title="明文凭据只显示一次；关闭后无法再次读取。"
        type="warning"
        :closable="false"
      /><ElDescriptions :column="1" border
        ><ElDescriptionsItem label="API Key">{{ issued?.api_key }}</ElDescriptionsItem
        ><ElDescriptionsItem label="签名密钥">{{
          issued?.signing_secret
        }}</ElDescriptionsItem></ElDescriptions
      ><template #footer
        ><ElButton type="primary" @click="secretOpen = false" :disabled="busy"
          >已保存</ElButton
        ></template
      ></ElDialog
    >
  </AdminPage>
</template>
<script setup lang="ts">
import FeedbackNotice from '@skillforge/shared/feedback/FeedbackNotice.vue'
import { formatDateTime } from '@skillforge/shared/utils/datetime'
import { vDescribedBy } from '@skillforge/shared/directives/describedBy'

import FieldTip from '@skillforge/shared/components/FieldTip.vue'

import { guardFormEnter } from '@skillforge/shared/admin'
import type { FormInstance } from 'element-plus'
import { onMounted, reactive, ref, useId } from 'vue'
import {
  AdminPage,
  EntitySelect,
  statusLabel,
  useResource,
  useTask,
  useDrawerClose
} from '@skillforge/shared/admin'
import { confirm, message } from '@skillforge/shared/feedback'
import { OPEN_PLATFORM_V1_OPERATIONS } from '@skillforge/shared/open-platform/generated/v1-contract'
import { useAuthStore } from '../../stores/auth'
import { useManagementIdentity } from '../../composables/managementIdentity'
import { useUnsavedChanges } from '@skillforge/shared/admin'
const props = defineProps<{ tenantId?: string; embedded?: boolean; initialTab?: string }>()
const identity = useManagementIdentity()
import {
  api,
  type ServicePrincipal,
  type WebhookSubscription,
  type AuditLog,
  type KnowledgeWriteProposal,
  type IssuedServicePrincipal
} from '../../api'
const scopeDescriptions: Record<string, string> = {
  'control.operations.read': '读取控制操作结果',
  'control.operations.write': '执行控制操作',
  'resources.read': '读取资源及修订',
  'resources.write': '维护资源、发布与绑定',
  'sessions.events.read': '读取会话事件',
  'sessions.write': '创建会话、发送消息与操作'
}
const scopeOptions = [
  ...new Set(OPEN_PLATFORM_V1_OPERATIONS.map((operation) => operation.requiredScope))
]
  .filter(Boolean)
  .map((id) => ({ id, label: scopeDescriptions[id] || id }))
async function loadSessions() {
  return (await api.chat.sessions()).map((session) => ({
    id: session.session_id,
    label: session.summary || '未命名会话'
  }))
}
const formRef = ref<FormInstance>()
const auth = useAuthStore(),
  tab = ref(props.embedded ? 'principals' : (props.initialTab ?? 'principals')),
  action = ref(''),
  principals = ref<ServicePrincipal[]>([]),
  webhooks = ref<WebhookSubscription[]>([]),
  audit = ref<AuditLog[]>([]),
  proposals = ref<KnowledgeWriteProposal[]>([])
const {
  loading,
  error: loadError,
  reload
} = useResource(async () => {
  if (props.tenantId && props.tenantId !== auth.user?.tenant_id)
    throw Error('执行凭证只能由所属机构维护')
  if (tab.value === 'principals') {
    identity.requirePermission('foundation.service_principals.read')
    const value = await api.governance.servicePrincipals.list()
    identity.requirePermission('foundation.service_principals.read')
    principals.value = value
  } else if (tab.value === 'webhooks') webhooks.value = await api.governance.webhooks.list()
  else if (tab.value === 'audit')
    audit.value = (
      await api.governance.auditLogs({ action: action.value || undefined, limit: 100 })
    ).items
  else proposals.value = await api.governance.knowledgeProposals.pending()
  return true
})
onMounted(reload)
const { busy, error, run } = useTask(),
  open = ref(false),
  secretOpen = ref(false),
  issued = ref<IssuedServicePrincipal>(),
  form = reactive({
    integration_id: '',
    scopes: [] as string[],
    endpoint_url: '',
    session_id: '',
    signing_secret: ''
  })
const credentialUncertain = ref(false)
useUnsavedChanges({
  dirty: () => secretOpen.value,
  busy: () => busy.value || credentialUncertain.value
})
/** 原生凭证接口没有可重放意图回执，未知结果只能核对，不能借刷新或重试再次派发。 */
async function credentialCommand<T>(permission: string, command: () => Promise<T>): Promise<T> {
  if (credentialUncertain.value) throw Error('原凭证操作结果尚未核实，不能再次派发')
  identity.requirePermission(permission)
  try {
    const result = await command()
    identity.requirePermission(permission)
    return result
  } catch (reason) {
    const status =
      typeof reason === 'object' && reason !== null && 'status' in reason
        ? Number(reason.status)
        : 0
    if (status === 0 || status === 408 || status >= 500) credentialUncertain.value = true
    throw reason
  }
}
const { requestClose } = useDrawerClose({
  open: () => open.value,
  busy: () => busy.value || credentialUncertain.value,
  dirty: () =>
    open.value &&
    !!(
      form.integration_id ||
      form.scopes.length ||
      form.endpoint_url ||
      form.session_id ||
      form.signing_secret
    ),
  close: () => {
    open.value = false
    form.signing_secret = ''
  }
})
function openCreate() {
  error.value = ''
  formRef.value?.clearValidate()
  Object.assign(form, {
    integration_id: '',
    scopes: [],
    endpoint_url: '',
    session_id: '',
    signing_secret: ''
  })
  open.value = true
}
async function create() {
  await run(async () => {
    if (!(await formRef.value?.validate().catch(() => false))) return
    const createdMessage =
      tab.value === 'principals' ? '服务身份已创建，请保存仅展示一次的新凭据' : '回调订阅已创建'
    if (tab.value === 'principals') {
      identity.requirePermission('foundation.service_principals.create')
      if (
        !form.integration_id.trim() ||
        !form.scopes.length ||
        form.scopes.some((scope) => !scopeOptions.some((option) => option.id === scope))
      )
        throw Error('请填写集成标识并选择权限')
      const result = await credentialCommand('foundation.service_principals.create', () =>
        api.governance.servicePrincipals.issue({
          integration_id: form.integration_id,
          scopes: form.scopes
        })
      )
      identity.requirePermission('foundation.service_principals.create')
      issued.value = result
      secretOpen.value = true
    } else {
      if (!/^https?:\/\//.test(form.endpoint_url) || !form.signing_secret)
        throw Error('请填写有效回调地址和签名密钥')
      await api.governance.webhooks.create({
        endpoint_url: form.endpoint_url,
        session_id: form.session_id || undefined,
        signing_secret: form.signing_secret
      })
    }
    open.value = false
    form.signing_secret = ''
    await reload()
    message.success(createdMessage)
  })
}
async function rotate(row: ServicePrincipal) {
  if (
    await confirm({
      title: '轮换凭据',
      confirmText: '轮换凭据',
      content: `集成“${row.integration_id}”的现有凭据将立即失效，请同步更新调用方。`
    })
  )
    await run(async () => {
      identity.requirePermission('foundation.service_principals.update')
      const result = await credentialCommand('foundation.service_principals.update', () =>
        api.governance.servicePrincipals.rotate(row.id)
      )
      identity.requirePermission('foundation.service_principals.update')
      issued.value = result
      message.success('凭据已轮换，旧凭据已失效；请保存新凭据并更新调用方')
      secretOpen.value = true
      await reload()
    })
}
async function suspend(row: ServicePrincipal) {
  if (
    !(await confirm({
      title: '暂停服务身份',
      content: `暂停“${row.integration_id}”的服务身份？该身份将暂时无法继续调用 API。`,
      confirmText: '暂停身份',
      danger: true
    }))
  )
    return
  await run(async () => {
    identity.requirePermission('foundation.service_principals.update')
    await credentialCommand('foundation.service_principals.update', () =>
      api.governance.servicePrincipals.suspend(row.id)
    )
    await reload()
    message.success('服务身份已暂停')
  })
}
async function revoke(row: ServicePrincipal) {
  if (
    await confirm({
      title: '吊销服务身份',
      confirmText: '吊销服务身份',
      danger: true,
      content: `集成“${row.integration_id}”的凭据将失效，调用方将无法继续调用 API。`
    })
  )
    await run(async () => {
      identity.requirePermission('foundation.service_principals.update')
      await credentialCommand('foundation.service_principals.update', () =>
        api.governance.servicePrincipals.revoke(row.id)
      )
      message.success('服务身份已吊销')
      await reload()
    })
}
async function pauseWebhook(row: WebhookSubscription) {
  if (
    !(await confirm({
      title: '暂停回调订阅',
      content: `暂停发送到“${row.endpoint_url}”的回调？后续事件将不再向该订阅推送。`,
      confirmText: '暂停订阅'
    }))
  )
    return
  await run(async () => {
    await api.governance.webhooks.pause(row.id)
    await reload()
    message.success('回调订阅已暂停')
  })
}
async function decide(row: KnowledgeWriteProposal, approve: boolean) {
  await run(async () => {
    const reviewer = auth.user?.sub
    if (!reviewer) throw Error('当前登录信息不完整，请重新登录后审核')
    await (
      approve ? api.governance.knowledgeProposals.approve : api.governance.knowledgeProposals.reject
    )(row.proposal_id, { reviewer_id: reviewer })
    await reload()
    message.success(approve ? '知识写入提案已批准' : '知识写入提案已拒绝')
  })
}

const formTipId = useId()
</script>
