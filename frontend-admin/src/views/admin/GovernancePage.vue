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
      :title="
        pendingIssue
          ? '本次凭证签发结果未知，请核对原签发；确认前不能再次操作。'
          : pendingRotation
            ? '本次凭证轮换结果未知，请核对原轮换；确认前不能再次操作。'
            : pendingStatus
              ? '本次服务身份状态操作结果未知，请核对原操作；确认前不能再次操作。'
              : '凭证操作记录不可用，请恢复浏览器存储后重试。'
      "
      type="warning"
      :closable="false"
    />
    <div v-if="issueRecovered" class="admin-field-help" role="status">
      原签发已确认（{{
        recoveredPrincipal
          ? `集成“${recoveredPrincipal.integration_id}”`
          : `服务身份 ${issueRecovered}`
      }}），首次凭据无法恢复。
      <ElButton
        v-if="recoveredPrincipal"
        v-permission="['foundation.service_principals.update']"
        :disabled="busy || credentialUncertain"
        @click="rotate(recoveredPrincipal)"
        >轮换该身份凭据</ElButton
      >
      <span v-else>请刷新列表后轮换该身份。</span>
    </div>
    <div v-if="pendingIssue || issueStorageError" class="admin-card" role="status">
      <p v-if="pendingIssue">
        服务身份签发结果尚未确认，请求 ID：{{ pendingIssue }}。未查到回执不表示失败。
      </p>
      <p v-if="issueStorageError">{{ issueStorageError }}</p>
      <p v-if="pendingIssue && !identity.allowed('foundation.service_principals.read')">
        当前账号没有服务身份读取权限，请联系管理员授权后核对原签发。
      </p>
      <ElButton
        v-if="pendingIssue && identity.allowed('foundation.service_principals.read')"
        :disabled="busy"
        @click="checkIssue()"
        >核对原签发</ElButton
      >
      <ElButton v-if="issueStorageError && !pendingIssue" :disabled="busy" @click="loadIssue"
        >重新读取签发记录</ElButton
      >
    </div>
    <p v-if="rotationRecovered" class="admin-field-help" role="status">
      原轮换已确认，但明文无法恢复。如需新凭据，请重新轮换。
    </p>
    <div v-if="pendingRotation || rotationStorageError" class="admin-card" role="status">
      <p v-if="pendingRotation">
        服务身份轮换结果尚未确认，请求 ID：{{ pendingRotation.requestId }}。未查到回执不表示失败。
      </p>
      <p v-if="rotationStorageError">{{ rotationStorageError }}</p>
      <p v-if="pendingRotation && !identity.allowed('foundation.service_principals.read')">
        当前账号没有服务身份读取权限，请联系管理员授权后核对原轮换。
      </p>
      <ElButton
        v-if="pendingRotation && identity.allowed('foundation.service_principals.read')"
        :disabled="busy"
        @click="checkRotation()"
        >核对原轮换</ElButton
      >
      <ElButton
        v-if="rotationStorageError && !pendingRotation"
        :disabled="busy"
        @click="loadRotation"
        >重新读取操作记录</ElButton
      >
    </div>
    <p v-if="statusOutcome" class="admin-field-help" role="status">{{ statusOutcome }}</p>
    <div v-if="pendingStatus || statusStorageError" class="admin-card" role="status">
      <p v-if="pendingStatus">
        服务身份{{ pendingStatus.action === 'suspend' ? '暂停' : '吊销' }}结果尚未确认，请求 ID：{{
          pendingStatus.requestId
        }}。未查到回执不表示失败。
      </p>
      <p v-if="statusStorageError">{{ statusStorageError }}</p>
      <p v-if="pendingStatus && !identity.allowed('foundation.service_principals.read')">
        当前账号没有服务身份读取权限，请联系管理员授权后核对原操作。
      </p>
      <ElButton
        v-if="pendingStatus && identity.allowed('foundation.service_principals.read')"
        :disabled="busy"
        @click="checkStatus()"
        >核对原状态操作</ElButton
      >
      <ElButton v-if="statusStorageError && !pendingStatus" :disabled="busy" @click="loadStatus"
        >重新读取状态操作记录</ElButton
      >
    </div>
    <ElTabs v-if="!embedded" v-model="tab" @tab-change="reload"
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
            :disabled="busy || credentialUncertain || row.status !== 'active'"
            @click="rotate(row as ServicePrincipal)"
            >轮换</ElButton
          ><ElButton
            v-permission="['foundation.service_principals.update']"
            link
            :disabled="busy || credentialUncertain || row.status !== 'active'"
            @click="changeStatus(row as ServicePrincipal, 'suspend')"
            >暂停</ElButton
          ><ElButton
            v-permission="['foundation.service_principals.update']"
            link
            type="danger"
            :disabled="busy || credentialUncertain || row.status === 'revoked'"
            @click="changeStatus(row as ServicePrincipal, 'revoke')"
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
      ><FeedbackNotice v-if="error" :title="error" type="error" :closable="false" role="alert" />
      <div v-if="pendingIssue" role="status">
        服务身份签发结果尚未确认，原请求 ID：{{ pendingIssue }}。
        <ElButton
          v-if="identity.allowed('foundation.service_principals.read')"
          :disabled="busy"
          @click="checkIssue()"
          >核对原签发</ElButton
        >
        <span v-else>请联系管理员授权后核对原签发。</span>
      </div>
      <ElForm
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
import { computed, onMounted, reactive, ref, useId } from 'vue'
import {
  AdminPage,
  EntitySelect,
  statusLabel,
  useResource,
  useTask,
  useDrawerClose
} from '@skillforge/shared/admin'
import { confirm, message } from '@skillforge/shared/feedback'
import { HttpClientError } from '@skillforge/shared/utils/http'
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
  type IssuedServicePrincipal,
  type ServicePrincipalStatusAction,
  type ServicePrincipalStatusReceipt
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
  issued = ref<Pick<IssuedServicePrincipal, 'api_key' | 'signing_secret'>>(),
  form = reactive({
    integration_id: '',
    scopes: [] as string[],
    endpoint_url: '',
    session_id: '',
    signing_secret: ''
  })
type PendingStatus = {
  requestId: string
  principalId: string
  action: ServicePrincipalStatusAction
}
const pendingStatus = ref<PendingStatus>()
const statusStorageError = ref('')
const statusReceipt = ref<ServicePrincipalStatusReceipt>()
const statusOutcome = computed(() => {
  const receipt = statusReceipt.value
  if (!receipt) return ''
  const principal = principals.value.find((item) => String(item.id) === receipt.principal_id)
  const subject = principal
    ? `集成“${principal.integration_id}”`
    : `服务身份 ${receipt.principal_id}`
  const label = receipt.action === 'suspend' ? '暂停' : '吊销'
  return receipt.changed
    ? `原${label}操作已确认（${subject}）；当前状态以列表为准。`
    : `原${label}操作未改变服务身份（${subject}），请核对当前状态。`
})
const statusStorageKey = `skillforge.principal-status:${JSON.stringify([auth.user?.tenant_id, auth.user?.sub])}`
function loadStatus() {
  if (!auth.user?.tenant_id || !auth.user?.sub) {
    statusStorageError.value = '无法确认当前机构和成员，请重新登录。'
    return
  }
  try {
    const raw = sessionStorage.getItem(statusStorageKey)
    const value = raw !== null ? JSON.parse(raw) : undefined
    if (
      raw !== null &&
      (!value ||
        typeof value !== 'object' ||
        Array.isArray(value) ||
        typeof value.principalId !== 'string' ||
        !/^\d+$/.test(value.principalId) ||
        typeof value.requestId !== 'string' ||
        !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value.requestId) ||
        (value.action !== 'suspend' && value.action !== 'revoke'))
    )
      throw Error('invalid')
    pendingStatus.value = value
    statusStorageError.value = ''
  } catch {
    statusStorageError.value = '无法读取状态操作记录，请恢复浏览器存储后重试。'
  }
}
function saveStatus(value?: PendingStatus) {
  try {
    if (value) sessionStorage.setItem(statusStorageKey, JSON.stringify(value))
    else sessionStorage.removeItem(statusStorageKey)
    pendingStatus.value = value
    statusStorageError.value = ''
    return true
  } catch {
    statusStorageError.value = '无法保存状态操作记录，请恢复浏览器存储后重试。'
    return false
  }
}
function matchesStatus(
  receipt: ServicePrincipalStatusReceipt | undefined,
  original: PendingStatus
) {
  return (
    !!receipt &&
    receipt.request_id === original.requestId &&
    receipt.principal_id === original.principalId &&
    receipt.action === original.action &&
    typeof receipt.changed === 'boolean'
  )
}
function showStatusOutcome(receipt: ServicePrincipalStatusReceipt) {
  statusReceipt.value = receipt
  if (receipt.changed) message.success(statusOutcome.value)
  else message.warning(statusOutcome.value)
}
async function checkStatus(automatic = false) {
  const original = pendingStatus.value
  if (!original || !identity.allowed('foundation.service_principals.read')) return
  await run(async () => {
    const receipt = await api.governance.servicePrincipals.statusResult(original.requestId)
    if (!identity.current() || pendingStatus.value?.requestId !== original.requestId) return
    if (!matchesStatus(receipt, original)) {
      if (!automatic) message.warning('尚未查到匹配的状态操作回执，结果仍未确认。')
      return
    }
    if (!saveStatus()) return
    showStatusOutcome(receipt!)
    await reload()
  })
}
const pendingIssue = ref<string>()
const issueRecovered = ref('')
const recoveredPrincipal = computed(() =>
  principals.value.find((principal) => String(principal.id) === issueRecovered.value)
)
const issueStorageError = ref('')
const issueStorageKey = `skillforge.principal-issue:${JSON.stringify([auth.user?.tenant_id, auth.user?.sub])}`
function loadIssue() {
  if (!auth.user?.tenant_id || !auth.user?.sub) {
    issueStorageError.value = '无法确认当前机构和成员，请重新登录。'
    return
  }
  try {
    const raw = sessionStorage.getItem(issueStorageKey)
    if (
      raw !== null &&
      !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(raw)
    )
      throw Error('invalid')
    pendingIssue.value = raw ?? undefined
    issueStorageError.value = ''
  } catch {
    issueStorageError.value = '无法读取签发操作记录，请恢复浏览器存储后重试。'
  }
}
function saveIssue(value?: string) {
  try {
    if (value) sessionStorage.setItem(issueStorageKey, value)
    else sessionStorage.removeItem(issueStorageKey)
    pendingIssue.value = value
    issueStorageError.value = ''
    return true
  } catch {
    issueStorageError.value = '无法保存签发操作记录，请恢复浏览器存储后重试。'
    return false
  }
}
async function checkIssue(automatic = false) {
  const original = pendingIssue.value
  if (!original || !identity.allowed('foundation.service_principals.read')) return
  await run(async () => {
    const receipt = await api.governance.servicePrincipals.issueResult(original)
    if (!identity.current() || pendingIssue.value !== original) return
    if (!receipt || receipt.request_id !== original || !/^\d+$/.test(receipt.principal_id)) {
      if (!automatic) message.warning('尚未查到匹配的签发回执，结果仍未确认。')
      return
    }
    if (!saveIssue()) return
    issueRecovered.value = issued.value?.api_key ? '' : receipt.principal_id
    if (open.value) open.value = false
    if (issueRecovered.value)
      message.warning('原签发已确认，首次凭据无法恢复；如需凭据，请轮换该身份。')
    else message.success('原签发已确认')
    await reload()
  })
}
const rotationRecovered = ref(false)
const pendingRotation = ref<{ requestId: string; principalId: string }>()
const rotationStorageError = ref('')
const rotationStorageKey = `skillforge.principal-rotation:${JSON.stringify([auth.user?.tenant_id, auth.user?.sub])}`
const credentialUncertain = computed(
  () =>
    !!pendingStatus.value ||
    !!statusStorageError.value ||
    !!pendingIssue.value ||
    !!issueStorageError.value ||
    !!pendingRotation.value ||
    !!rotationStorageError.value
)
function loadRotation() {
  if (!auth.user?.tenant_id || !auth.user?.sub) {
    rotationStorageError.value = '无法确认当前机构和成员，请重新登录。'
    return
  }
  try {
    const raw = sessionStorage.getItem(rotationStorageKey)
    const value = raw !== null ? JSON.parse(raw) : undefined
    if (
      raw !== null &&
      (!value ||
        typeof value !== 'object' ||
        Array.isArray(value) ||
        typeof value.principalId !== 'string' ||
        !/^\d+$/.test(value.principalId) ||
        typeof value.requestId !== 'string' ||
        !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value.requestId))
    )
      throw Error('invalid')
    pendingRotation.value = value
    rotationStorageError.value = ''
  } catch {
    rotationStorageError.value = '无法读取轮换操作记录，请恢复浏览器存储后重试。'
  }
}
function saveRotation(value?: { requestId: string; principalId: string }) {
  try {
    if (value) sessionStorage.setItem(rotationStorageKey, JSON.stringify(value))
    else sessionStorage.removeItem(rotationStorageKey)
    pendingRotation.value = value
    rotationStorageError.value = ''
    return true
  } catch {
    rotationStorageError.value = '无法保存轮换操作记录，请恢复浏览器存储后重试。'
    return false
  }
}
onMounted(() => {
  loadIssue()
  loadRotation()
  loadStatus()
  void (async () => {
    if (pendingIssue.value && identity.allowed('foundation.service_principals.read'))
      await checkIssue(true)
    if (pendingRotation.value && identity.allowed('foundation.service_principals.read'))
      await checkRotation(true)
    if (pendingStatus.value && identity.allowed('foundation.service_principals.read'))
      await checkStatus(true)
  })()
})
async function checkRotation(automatic = false) {
  const original = pendingRotation.value
  if (!original || !identity.allowed('foundation.service_principals.read')) return
  await run(async () => {
    const receipt = await api.governance.servicePrincipals.rotationResult(original.requestId)
    if (!identity.current() || pendingRotation.value?.requestId !== original.requestId) return
    if (
      !receipt ||
      receipt.request_id !== original.requestId ||
      receipt.principal_id !== original.principalId
    ) {
      if (!automatic) message.warning('尚未查到匹配的轮换回执，结果仍未确认。')
      return
    }
    if (!saveRotation()) return
    rotationRecovered.value = !issued.value?.api_key
    if (rotationRecovered.value)
      message.warning('原轮换已确认，明文无法恢复；如需新凭据，请重新轮换。')
    else message.success('原轮换已确认')
    await reload()
  })
}
useUnsavedChanges({
  dirty: () => secretOpen.value,
  busy: () => busy.value || credentialUncertain.value
})
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
      if (credentialUncertain.value) throw Error('原凭证操作结果尚未核实，不能再次派发')
      const requestId = crypto.randomUUID()
      if (!saveIssue(requestId)) return
      issueRecovered.value = ''
      let result
      try {
        result = await api.governance.servicePrincipals.issueWithReceipt(requestId, {
          integration_id: form.integration_id,
          scopes: form.scopes
        })
        identity.requirePermission('foundation.service_principals.create')
        if (
          !result ||
          result.request_id !== requestId ||
          !/^\d+$/.test(result.principal_id) ||
          ((result.api_key !== undefined || result.signing_secret !== undefined) &&
            (typeof result.api_key !== 'string' ||
              !result.api_key.trim() ||
              typeof result.signing_secret !== 'string' ||
              !result.signing_secret.trim()))
        )
          throw Error('签发响应不完整，请核对原请求。')
      } catch (reason) {
        if (
          identity.current() &&
          reason instanceof HttpClientError &&
          reason.code === 'PRINCIPAL_ISSUE_REJECTED'
        )
          saveIssue()
        throw reason
      }
      const cleared = saveIssue()
      if (result.api_key && result.signing_secret) {
        issued.value = { api_key: result.api_key, signing_secret: result.signing_secret }
        secretOpen.value = true
      } else {
        issueRecovered.value = result.principal_id
        message.warning('原签发已确认，首次凭据无法恢复；如需凭据，请轮换该身份。')
      }
      if (!cleared) message.warning('原签发已确认，但本地记录尚未清除，请恢复浏览器存储后核对。')
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
    if (tab.value !== 'principals' || !issueRecovered.value) message.success(createdMessage)
  })
}
async function rotate(row: ServicePrincipal) {
  if (credentialUncertain.value) return
  identity.requirePermission('foundation.service_principals.update')
  if (
    !(await confirm({
      title: '轮换凭据',
      confirmText: '轮换凭据',
      content: `集成“${row.integration_id}”的现有凭据将立即失效，请同步更新调用方。`
    }))
  )
    return
  identity.requirePermission('foundation.service_principals.update')
  await run(async () => {
    const original = { requestId: crypto.randomUUID(), principalId: String(row.id) }
    if (!saveRotation(original)) return
    rotationRecovered.value = false
    let result
    try {
      result = await api.governance.servicePrincipals.rotateWithReceipt(original.requestId, row.id)
      identity.requirePermission('foundation.service_principals.update')
      if (
        !result ||
        result.request_id !== original.requestId ||
        result.principal_id !== original.principalId ||
        ((result.api_key !== undefined || result.signing_secret !== undefined) &&
          (typeof result.api_key !== 'string' ||
            !result.api_key.trim() ||
            typeof result.signing_secret !== 'string' ||
            !result.signing_secret.trim()))
      )
        throw Error('轮换响应不完整，请核对原请求。')
    } catch (reason) {
      if (
        identity.current() &&
        reason instanceof HttpClientError &&
        reason.code === 'PRINCIPAL_ROTATION_REJECTED'
      ) {
        saveRotation()
      } else {
        issued.value = undefined
        secretOpen.value = false
      }
      throw reason
    }
    const cleared = saveRotation()
    if (result.api_key && result.signing_secret) {
      if (issueRecovered.value === String(row.id)) issueRecovered.value = ''
      issued.value = {
        api_key: result.api_key,
        signing_secret: result.signing_secret
      }
      secretOpen.value = true
      message.success('凭据已轮换，旧凭据已失效；请保存新凭据并更新调用方')
    } else {
      rotationRecovered.value = true
      message.warning('原轮换已确认，明文无法恢复；如需新凭据，请重新轮换。')
    }
    if (!cleared) message.warning('原轮换已确认，但本地记录尚未清除，请恢复浏览器存储后核对。')
    await reload()
  })
}
async function changeStatus(row: ServicePrincipal, action: ServicePrincipalStatusAction) {
  if (credentialUncertain.value) return
  identity.requirePermission('foundation.service_principals.update')
  const label = action === 'suspend' ? '暂停' : '吊销'
  if (
    !(await confirm({
      title: `${label}服务身份`,
      confirmText: action === 'suspend' ? '暂停身份' : '吊销服务身份',
      danger: true,
      content:
        action === 'suspend'
          ? `暂停“${row.integration_id}”的服务身份？该身份将暂时无法继续调用 API。`
          : `集成“${row.integration_id}”的凭据将失效，调用方将无法继续调用 API。`
    }))
  )
    return
  identity.requirePermission('foundation.service_principals.update')
  await run(async () => {
    const original: PendingStatus = {
      requestId: crypto.randomUUID(),
      principalId: String(row.id),
      action
    }
    if (!saveStatus(original)) return
    statusReceipt.value = undefined
    const receipt = await api.governance.servicePrincipals.changeStatusWithReceipt(
      original.requestId,
      row.id,
      action
    )
    identity.requirePermission('foundation.service_principals.update')
    if (!matchesStatus(receipt, original)) throw Error('状态操作响应不完整，请核对原请求。')
    const cleared = saveStatus()
    showStatusOutcome(receipt)
    if (!cleared) message.warning('原操作已确认，但本地记录尚未清除，请恢复浏览器存储后核对。')
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
