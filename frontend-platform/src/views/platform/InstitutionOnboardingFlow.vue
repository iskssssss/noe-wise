<script setup lang="ts">
import FeedbackNotice from '@skillforge/shared/feedback/FeedbackNotice.vue'
import { computed, reactive, ref, watch } from 'vue'
import type { FormInstance } from 'element-plus'
import {
  AdminPage,
  confirmPageLeave,
  guardFormEnter,
  useResource,
  useTask,
  useUnsavedChanges
} from '@skillforge/shared/admin'
import { accountPasswordError, accountPasswordRule } from '@skillforge/shared/utils/accountPassword'
import { confirm, message } from '@skillforge/shared/feedback'
import { HttpClientError } from '@skillforge/shared/utils/http'
import { api, type TenantItem } from '../../api'
import { useAuthStore } from '../../stores/auth'
import { useActionPermissions } from '../../composables/actionPermissions'
import InstitutionIntegrationsPage from './InstitutionIntegrationsPage.vue'
import InstitutionCredentialsPage from './InstitutionCredentialsPage.vue'
import InstitutionAgentSetup from './InstitutionAgentSetup.vue'

const props = defineProps<{ tenantId?: string }>()
const emit = defineEmits<{
  changed: []
  targetSelected: [value: { tenantId: string; integrationId?: string }]
  completed: [value: { tenantId: string; integrationId?: string }]
}>()
const auth = useAuthStore()
const { allowed, requireAction, checked } = useActionPermissions()
const identityVersion = auth.identityVersion
const originalTenant = props.tenantId ?? ''
const target = ref(originalTenant)
const integration = ref('')
const step = ref(0)
const mode = ref<'ordinary' | 'mapping'>(
  originalTenant || !allowed('foundation.tenants.create') ? 'mapping' : 'ordinary'
)
const steps = ['机构创建与接入', '执行凭证', '添加智能体', '接入检查']
const action = useTask()
const formRef = ref<FormInstance>()
const formValidationError = ref('')
const createdName = ref('')
const createdAgentId = ref('')
const ordinaryUnknown = ref(false)
const candidateAccepted = ref(false)
const originalSlug = ref('')
const originalRequestId = ref('')
const ordinaryStorageError = ref('')
const ordinaryStorageKey = () => {
  if (!auth.user?.sub) throw Error('当前身份不可用')
  return `skillforge.platform-tenant-create:${auth.user.sub}`
}
if (!originalTenant) {
  try {
    const stored = sessionStorage.getItem(ordinaryStorageKey())
    if (stored) {
      if (stored.startsWith('{')) {
        const record = JSON.parse(stored) as { slug: string; requestId: string }
        originalSlug.value = record.slug
        originalRequestId.value = record.requestId
      } else originalSlug.value = stored
      ordinaryUnknown.value = true
      mode.value = 'ordinary'
    }
  } catch {
    ordinaryStorageError.value = '无法读取原机构创建记录，请恢复浏览器会话存储后重新进入。'
  }
}
const candidate = ref<TenantItem | null>()
const form = reactive({
  tenant_name: '',
  tenant_slug: '',
  plan_code: '',
  admin_username: '',
  admin_password: '',
  admin_email: ''
})
const baseline = ref(JSON.stringify(form))
const identityCurrent = computed(
  () => identityVersion === auth.identityVersion && (props.tenantId ?? '') === originalTenant
)
const plans = useResource(async (signal) => {
  requireAction('foundation.tenants.read')
  return checked('foundation.tenants.read', () => api.admin.plans({ signal, silent: true }))
})
useUnsavedChanges({
  dirty: () => JSON.stringify(form) !== baseline.value || ordinaryUnknown.value,
  busy: () => action.busy.value || ordinaryUnknown.value
})
watch(identityCurrent, (current) => {
  if (!current) {
    form.admin_password = ''
    candidate.value = undefined
    plans.invalidate()
    inspection.invalidate()
  }
})
watch(
  () => allowed('foundation.tenants.create'),
  (current) => {
    if (!current) form.admin_password = ''
  }
)
watch(
  () => allowed('foundation.tenants.read'),
  (current) => {
    if (!current) candidate.value = undefined
  }
)
function requireIdentity() {
  if (!identityCurrent.value) throw Error('账号或目标机构已变化，请重新打开维护流程')
}
async function selectMode(value: string | number | boolean | undefined) {
  if ((value !== 'ordinary' && value !== 'mapping') || value === mode.value) return
  if (!(await confirmPageLeave())) return
  form.admin_password = ''
  Object.assign(form, {
    tenant_name: '',
    tenant_slug: '',
    plan_code: '',
    admin_username: '',
    admin_email: ''
  })
  baseline.value = JSON.stringify(form)
  formValidationError.value = ''
  mode.value = value
  if (value === 'ordinary' && allowed('foundation.tenants.read') && !plans.data.value)
    void plans.reload()
}
async function go(next: number, selectedTarget?: { tenantId: string; integrationId?: string }) {
  if (
    next < 0 ||
    next >= steps.length ||
    (next > 0 && !target.value && !selectedTarget) ||
    action.busy.value
  )
    return
  if (!(await confirmPageLeave())) return
  if (selectedTarget) acceptTarget(selectedTarget)
  step.value = next
  if (next === 3) void inspection.reload()
}
function acceptTarget(value: { tenantId: string; integrationId?: string }) {
  requireIdentity()
  if (originalTenant && value.tenantId !== originalTenant) throw Error('接入回执不属于当前机构')
  if (target.value !== value.tenantId || integration.value !== (value.integrationId ?? '')) {
    createdAgentId.value = ''
    inspection.invalidate()
    inspection.data.value = undefined
  }
  target.value = value.tenantId
  integration.value = value.integrationId ?? ''
  emit('targetSelected', value)
  emit('changed')
}
function mappingCompleted(value: { tenantId: string; integrationId?: string }) {
  acceptTarget(value)
  if (value.integrationId) void go(1)
}
function createOrdinary() {
  if (
    target.value ||
    ordinaryUnknown.value ||
    ordinaryStorageError.value ||
    !allowed('foundation.tenants.create')
  )
    return
  void action.run(async () => {
    requireIdentity()
    formValidationError.value = ''
    // Element Plus 校验失败会拒绝字段对象；这是本地输入错误，尚未发起创建请求。
    const valid = await formRef.value?.validate().catch(() => false)
    if (!valid) {
      formValidationError.value = '请检查表单中标出的字段'
      return
    }
    const error = accountPasswordError(form.admin_password)
    if (
      error ||
      !form.tenant_name.trim() ||
      !form.tenant_slug.trim() ||
      !form.admin_username.trim()
    ) {
      formValidationError.value = error || '请填写机构名称、机构标识和管理员账号'
      return
    }
    originalSlug.value = form.tenant_slug.trim()
    originalRequestId.value = crypto.randomUUID()
    let storageKey: string
    try {
      storageKey = ordinaryStorageKey()
      sessionStorage.setItem(
        storageKey,
        JSON.stringify({ slug: originalSlug.value, requestId: originalRequestId.value })
      )
    } catch {
      ordinaryStorageError.value = '无法保存创建记录，未发送请求；请恢复浏览器会话存储。'
      throw Error(ordinaryStorageError.value)
    }
    let rejected = false
    try {
      const value = await checked('foundation.tenants.create', () =>
        api.admin.createTenant(
          originalRequestId.value,
          {
            ...form,
            tenant_name: form.tenant_name.trim(),
            tenant_slug: originalSlug.value,
            admin_username: form.admin_username.trim(),
            admin_email: form.admin_email.trim()
          },
          { silent: true }
        )
      )
      requireIdentity()
      if (value?.request_id !== originalRequestId.value)
        throw Error('机构创建响应不完整，请核对原请求')
      if (value.status === 'REJECTED') {
        rejected = true
        throw Error(value.error_message || '机构创建已拒绝')
      }
      if (
        value.status !== 'SUCCEEDED' ||
        !value.tenant_id ||
        !/^[1-9]\d*$/.test(value.tenant_id) ||
        !value.tenant_name?.trim()
      )
        throw Error('机构创建结果尚未确认，请核对原请求')
      createdName.value = value.tenant_name
      form.admin_password = ''
      baseline.value = JSON.stringify(form)
      acceptTarget({ tenantId: value.tenant_id })
      try {
        sessionStorage.removeItem(storageKey)
      } catch {
        ordinaryStorageError.value = '机构已创建，但无法清除本地创建记录；请恢复浏览器会话存储。'
      }
    } catch (reason) {
      // 只有原请求的确定拒绝回执可以解除未知状态；其他响应不可证明未执行。
      const preDispatch =
        reason instanceof HttpClientError && reason.code === 'TENANT_CREATE_PRE_DISPATCH_REJECTED'
      ordinaryUnknown.value = !rejected && !preDispatch
      if (rejected || preDispatch) {
        try {
          sessionStorage.removeItem(storageKey)
        } catch {
          ordinaryStorageError.value = '创建已被拒绝，但无法清除本地创建记录。'
        }
      }
      throw reason
    }
  })
}
function verifyOrdinary() {
  if (!ordinaryUnknown.value || !allowed('foundation.tenants.read')) return
  void action.run(async () => {
    requireIdentity()
    if (originalRequestId.value) {
      const receipt = await checked('foundation.tenants.read', () =>
        api.admin.tenantCreateResult(originalRequestId.value, { silent: true })
      )
      requireIdentity()
      if (
        !receipt ||
        receipt.request_id !== originalRequestId.value ||
        receipt.status === 'PROCESSING'
      )
        throw Error('原请求结果尚未确认，请稍后再次核对，不要重新创建。')
      if (receipt.status === 'REJECTED' || receipt.status === 'FAILED') {
        sessionStorage.removeItem(ordinaryStorageKey())
        ordinaryUnknown.value = false
        throw Error(receipt.error_message || '原机构创建未成功')
      }
      if (
        receipt.status !== 'SUCCEEDED' ||
        !receipt.tenant_id ||
        !/^[1-9]\d*$/.test(receipt.tenant_id) ||
        !receipt.tenant_name?.trim()
      )
        throw Error('原请求回执不完整，请稍后再次核对。')
      createdName.value = receipt.tenant_name
      form.admin_password = ''
      baseline.value = JSON.stringify(form)
      acceptTarget({ tenantId: receipt.tenant_id })
      ordinaryUnknown.value = false
      sessionStorage.removeItem(ordinaryStorageKey())
      return
    }
    candidate.value = await checked('foundation.tenants.read', () =>
      api.admin.findTenantBySlug(originalSlug.value, { silent: true })
    )
    requireIdentity()
    if (!candidate.value)
      throw Error('尚未找到该标识的机构，创建结果仍未知。请保留当前状态核对，不要重新创建。')
  })
}
function acceptCandidate() {
  if (!candidate.value || !ordinaryUnknown.value) return
  void action.run(async () => {
    requireIdentity()
    const value = candidate.value!
    if (
      !(await confirm({
        title: '使用已找到的机构继续',
        content: `已找到机构“${value.name}”（${value.slug}）。这只证明机构存在，不证明原管理员账号已经按预期创建；请在机构设置中核对账号。`,
        confirmText: '使用该机构',
        cancelText: '继续核对'
      }))
    )
      return
    requireAction('foundation.tenants.read')
    requireIdentity()
    createdName.value = value.name
    ordinaryUnknown.value = false
    candidateAccepted.value = true
    form.admin_password = ''
    baseline.value = JSON.stringify(form)
    acceptTarget({ tenantId: String(value.id) })
  })
}
const inspection = useResource(async (signal) => {
  requireIdentity()
  if (!target.value) throw Error('请先明确目标机构')
  const current = target.value
  const result: {
    deploymentRead: boolean
    credentialRead: boolean
    deploymentFailed: boolean
    credentialFailed: boolean
    deployments: number
    ready: number
    issues: string[]
    activeCredentialFound: boolean
    integration: string
  } = {
    deploymentRead: false,
    credentialRead: false,
    deploymentFailed: false,
    credentialFailed: false,
    deployments: 0,
    ready: 0,
    issues: [],
    activeCredentialFound: false,
    integration: integration.value
  }
  const [deployments, credentials] = await Promise.allSettled([
    allowed('platform.agent_deployments.read')
      ? checked('platform.agent_deployments.read', () =>
          api.agentDeployments.list({ signal, silent: true }, current)
        )
      : Promise.resolve(undefined),
    integration.value && allowed('platform.institution_credentials.read')
      ? checked('platform.institution_credentials.read', () =>
          api.platformIntegration.institutionCredentials(
            { page: 1, tenant_id: current, integration_id: integration.value },
            { signal, silent: true }
          )
        )
      : Promise.resolve(undefined)
  ])
  for (const entry of [deployments, credentials])
    if (entry.status === 'rejected' && entry.reason instanceof HttpClientError)
      entry.reason.claimFeedback()
  requireIdentity()
  if (signal.aborted || target.value !== current) throw Error('目标机构已变化')
  if (deployments.status === 'rejected') result.deploymentFailed = true
  else if (deployments.value) {
    result.deploymentRead = true
    result.deployments = deployments.value.items.length
    result.ready = deployments.value.items.filter((item) => item.ready).length
    result.issues = deployments.value.items.flatMap((item) =>
      item.issues.map((issue) => `${item.definition_name}：${issue.message}`)
    )
  }
  if (credentials.status === 'rejected') result.credentialFailed = true
  else if (credentials.value) {
    result.credentialRead = true
    result.activeCredentialFound = credentials.value.items.some((item) => item.status === 'active')
  }
  if (result.deploymentFailed || result.credentialFailed)
    message.error('部分接入状态读取失败，请刷新检查。')
  requireIdentity()
  if (target.value !== current) throw Error('目标机构已变化')
  return result
})
function finish() {
  if (!target.value || !identityCurrent.value || action.busy.value) return
  void (async () => {
    if (!(await confirmPageLeave())) return
    emit('completed', { tenantId: target.value, integrationId: integration.value || undefined })
  })()
}
async function setIntegration(value: string, tenantId?: string) {
  requireIdentity()
  if (tenantId) {
    if (target.value && tenantId !== target.value) {
      action.error.value = '所选接入不属于当前机构，请重新读取接入目录'
      return
    }
    // 选择既有映射即可继续交付凭证；先保护当前输入，再采纳目标与切换步骤。
    await go(1, { tenantId, integrationId: value })
    return
  }
  if (integration.value === value) return
  integration.value = value
  createdAgentId.value = ''
  inspection.invalidate()
  inspection.data.value = undefined
}
function agentCompleted(value: { agentId: string }) {
  createdAgentId.value = value.agentId
  emit('changed')
}
function stepState(index: number): 'process' | 'success' | 'wait' {
  if (index === step.value) return 'process'
  if (
    (index === 0 && target.value) ||
    (index === 1 && inspection.data.value?.activeCredentialFound) ||
    (index === 2 && createdAgentId.value)
  )
    return 'success'
  return 'wait'
}
if (mode.value === 'ordinary' && allowed('foundation.tenants.read')) void plans.reload()
</script>

<template>
  <AdminPage
    title="新增或接入机构"
    description="围绕同一机构继续处理接入、凭证和智能体，每一步保留自己的结果。"
    embedded
  >
    <ElAlert
      v-if="!identityCurrent"
      title="账号或目标机构已变化，请重新打开维护流程。"
      type="warning"
      :closable="false"
    />
    <template v-else>
      <ElSteps :active="step" align-center class="onboarding-steps"
        ><ElStep
          v-for="(label, index) in steps"
          :key="label"
          :title="label"
          :status="stepState(index)"
          :description="
            index !== step && index > 0 && stepState(index) === 'wait'
              ? '待配置或待核对'
              : undefined
          "
      /></ElSteps>
      <p v-if="target" class="admin-field-help">
        本次目标：{{ createdName || '机构' }} <code>{{ target }}</code
        ><template v-if="integration">
          · 接入 <code>{{ integration }}</code></template
        >
      </p>
      <p v-if="candidateAccepted" class="admin-field-help" role="status">
        已使用找到的机构继续；原创建请求是否结束仍未确认，原标识
        {{ originalSlug }} 会保留在当前浏览器标签页。
      </p>
      <FeedbackNotice
        v-if="action.error.value || formValidationError || ordinaryStorageError"
        :title="action.error.value || formValidationError || ordinaryStorageError"
        type="error"
        :closable="false"
      />
      <section v-if="step === 0">
        <ElRadioGroup
          v-if="!originalTenant && !target"
          :model-value="mode"
          @change="selectMode"
          class="onboarding-intent"
        >
          <ElRadioButton value="ordinary" :disabled="!allowed('foundation.tenants.create')"
            >创建普通机构</ElRadioButton
          >
          <ElRadioButton
            value="mapping"
            :disabled="
              !allowed('platform.institutions.provision') &&
              !allowed('platform.institutions.associate')
            "
            >接入外部机构</ElRadioButton
          >
        </ElRadioGroup>
        <template v-if="mode === 'ordinary' && !target">
          <p class="admin-field-help">
            创建独立机构及首位管理员；外部系统映射、执行凭证和 Agent 授权随后分别配置。
          </p>
          <ElAlert
            v-if="ordinaryUnknown"
            :title="
              originalRequestId
                ? '机构创建结果未知，请核对原请求。'
                : '机构创建结果未知，不能再次提交创建。请按原机构标识只读核对。'
            "
            type="warning"
            :closable="false"
          >
            <p>原机构标识：{{ originalSlug }}；刷新后初始密码不会保留。</p>
            <ElButton
              v-if="allowed('foundation.tenants.read')"
              :loading="action.busy.value"
              @click="verifyOrdinary"
              >{{ originalRequestId ? '核对原请求' : '按原机构标识核对' }}</ElButton
            >
            <span v-else
              >当前账号没有机构查询权限；需要原账号获得查询权限才能读取原请求，其他管理员只能按标识
              {{ originalSlug }} 人工核对。</span
            >
            <p v-if="candidate">
              已找到：{{ candidate.name }} · {{ candidate.slug }}
              <ElButton @click="acceptCandidate">使用该机构继续</ElButton>
            </p>
          </ElAlert>
          <ElForm
            ref="formRef"
            :model="form"
            label-position="top"
            class="ordinary-form"
            :disabled="
              action.busy.value ||
              ordinaryUnknown ||
              !!ordinaryStorageError ||
              !allowed('foundation.tenants.create')
            "
            @submit.prevent="createOrdinary"
            @keydown.capture="guardFormEnter"
          >
            <ElFormItem
              v-for="field in [
                { key: 'tenant_name', label: '机构名称' },
                { key: 'tenant_slug', label: '机构标识' },
                { key: 'admin_username', label: '管理员账号' },
                { key: 'admin_email', label: '管理员邮箱（可选）' }
              ] as const"
              :key="field.key"
              :label="field.label"
              :prop="field.key"
              :rules="
                field.key === 'admin_email'
                  ? [{ type: 'email', message: '请输入有效邮箱' }]
                  : [{ required: true, whitespace: true, message: '请填写' + field.label }]
              "
            >
              <ElInput
                v-model="form[field.key]"
                :autocomplete="field.key === 'admin_username' ? 'username' : undefined"
              />
              <p v-if="field.key === 'tenant_slug'" class="admin-field-help">
                用于区分机构，创建后不可修改。
              </p>
            </ElFormItem>
            <ElFormItem label="套餐（可选）"
              ><ElSelect
                v-model="form.plan_code"
                clearable
                :loading="plans.loading.value"
                placeholder="免费套餐"
                ><ElOption
                  v-for="plan in plans.data.value ?? []"
                  :key="plan.plan_code"
                  :value="plan.plan_code"
                  :label="plan.display_name"
              /></ElSelect>
              <p v-if="plans.error.value" class="admin-field-help">
                {{ plans.error.value }}；留空沿用免费套餐。
              </p>
              <ElButton v-if="plans.error.value" link @click="plans.reload"
                >重新读取套餐</ElButton
              ></ElFormItem
            >
            <ElFormItem label="管理员初始密码" prop="admin_password" :rules="[accountPasswordRule]"
              ><ElInput
                v-model="form.admin_password"
                type="password"
                show-password
                autocomplete="new-password"
              />
              <p class="admin-field-help">
                至少 12 个字符，包含英文字母和数字，UTF-8 最多 72 字节；仅在当前表单保存。
              </p></ElFormItem
            >
            <div class="ordinary-submit">
              <ElButton native-type="submit" type="primary" :loading="action.busy.value"
                >创建机构</ElButton
              >
            </div>
          </ElForm>
        </template>
        <ElAlert
          v-else-if="mode === 'ordinary' && target"
          title="机构已明确，后续操作将继续使用同一机构。"
          type="success"
          :closable="false"
          ><ElButton @click="mode = 'mapping'">继续关联外部系统</ElButton></ElAlert
        >
        <InstitutionIntegrationsPage
          v-else
          :tenant-id="target || undefined"
          embedded
          @provisioned="mappingCompleted"
          @integration-selected="setIntegration"
          @changed="emit('changed')"
        />
      </section>
      <section v-else-if="step === 1">
        <p class="admin-field-help">
          执行凭证独立签发，一次性秘密只在凭证交付窗口显示。此步骤可跳过，稍后仍可在接入与凭证页处理。
        </p>
        <InstitutionCredentialsPage
          v-if="
            allowed('platform.institution_credentials.read') ||
            allowed('platform.institution_credentials.issue')
          "
          :key="target + ':' + integration"
          :tenant-id="target"
          :integration-id="integration || undefined"
          embedded
          @changed="emit('changed')"
          @integration-selected="setIntegration"
        />
        <ElAlert
          v-else
          title="当前账号没有执行凭证维护权限，请交由获授权管理员处理。"
          type="info"
          :closable="false"
        />
      </section>
      <section v-else-if="step === 2">
        <InstitutionAgentSetup
          v-if="allowed('platform.agent_deployments.create')"
          :key="target + ':' + integration"
          :tenant-id="target"
          :integration-id="integration || undefined"
          @completed="agentCompleted"
          @changed="emit('changed')"
        />
        <ElAlert
          v-else
          title="当前账号没有添加智能体权限；可跳过并交由获授权管理员配置。"
          type="info"
          :closable="false"
        />
      </section>
      <section v-else class="inspection">
        <div class="inspection-header">
          <h3>只读接入检查</h3>
          <ElButton :loading="inspection.loading.value" @click="inspection.reload"
            >刷新检查</ElButton
          >
        </div>
        <p class="admin-field-help">
          读取当前凭证与部署就绪事实；不调用模型、工具或外部消息供应商。
        </p>
        <FeedbackNotice
          v-if="inspection.error.value"
          :title="inspection.error.value"
          type="error"
          :closable="false"
        />
        <template v-if="inspection.data.value">
          <p v-if="inspection.data.value.deploymentRead">
            已读取 {{ inspection.data.value.deployments }} 个稳定实例，其中
            {{ inspection.data.value.ready }} 个可运行。
            <ElButton
              v-if="
                inspection.data.value.deployments === 0 &&
                allowed('platform.agent_deployments.create')
              "
              link
              :disabled="action.busy.value"
              @click="go(2)"
              >添加智能体</ElButton
            >
          </p>
          <p
            v-if="
              inspection.data.value.deploymentRead &&
              inspection.data.value.deployments > inspection.data.value.ready
            "
            class="admin-field-help"
          >
            请查看下方未就绪原因；涉及本地资源时，请机构管理员在机构后台完成配置。
          </p>
          <p v-else-if="inspection.data.value.deploymentFailed">部署状态读取失败，请刷新检查。</p>
          <p v-else>部署状态：当前账号没有读取权限，不能判断是否已配置。</p>
          <p v-if="!inspection.data.value.integration">
            接入来源尚未选择，可返回机构接入步骤核对。
          </p>
          <p v-else-if="inspection.data.value.credentialRead">
            凭证检查：{{
              inspection.data.value.activeCredentialFound
                ? '已在读取页确认有效执行凭证'
                : '尚未在读取页确认有效凭证，请在凭证目录核对完整结果'
            }}。
            <ElButton
              v-if="!inspection.data.value.activeCredentialFound"
              link
              :disabled="action.busy.value"
              @click="go(1)"
              >查看凭证目录</ElButton
            >
          </p>
          <p v-else-if="inspection.data.value.credentialFailed">凭证状态读取失败，请刷新检查。</p>
          <p v-else>凭证状态：当前账号没有读取权限，不能判断是否已签发。</p>
          <ul v-if="inspection.data.value.issues.length">
            <li v-for="(issue, index) in inspection.data.value.issues" :key="index">{{ issue }}</li>
          </ul>
        </template>
      </section>
      <footer class="onboarding-actions">
        <ElButton v-if="step > 0" :disabled="action.busy.value" @click="go(step - 1)"
          >上一步</ElButton
        >
        <ElButton
          v-if="step < 3"
          type="primary"
          :disabled="!target || action.busy.value || ordinaryUnknown"
          @click="go(step + 1)"
          >{{ step === 0 ? '继续配置' : '继续下一步 / 稍后处理' }}</ElButton
        >
        <ElButton v-if="target" :disabled="action.busy.value || ordinaryUnknown" @click="finish"
          >返回机构工作台</ElButton
        >
      </footer>
    </template>
  </AdminPage>
</template>

<style scoped>
.onboarding-steps {
  margin: 8px 0 24px;
}
.onboarding-intent {
  margin-bottom: 20px;
}
.ordinary-form {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 280px), 1fr));
  gap: 0 24px;
}
.ordinary-form :deep(.el-form-item) {
  min-width: 0;
}
.ordinary-form :deep(.el-select) {
  width: 100%;
}
.ordinary-submit {
  grid-column: 1 / -1;
}
.onboarding-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  border-top: 1px solid var(--el-border-color-lighter);
  padding-top: 20px;
  margin-top: 24px;
}
.inspection-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.inspection {
  overflow-wrap: anywhere;
}
@media (max-width: 800px) {
  .onboarding-steps :deep(.el-step__title) {
    font-size: 12px;
    line-height: 1.5;
  }
}
</style>
