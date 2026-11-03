<script setup lang="ts">
import FeedbackNotice from '@skillforge/shared/feedback/FeedbackNotice.vue'
import { computed, reactive, ref, watch } from 'vue'
import type { FormInstance } from 'element-plus'
import {
  EntitySelect,
  AdminPage,
  guardFormEnter,
  useResource,
  useTask,
  useUnsavedChanges
} from '@skillforge/shared/admin'
import { confirm, message } from '@skillforge/shared/feedback'
import { api } from '../../api'
import { createOperationOptions } from '../../api/entityOptions'
import { useActionPermissions } from '../../composables/actionPermissions'
import { usableGrant, type PlatformDeploymentInput } from '../../institution/agentSetup'
import type { AgentDeployment } from '@skillforge/shared/integration/agentDeployments'
import { HttpClientError } from '@skillforge/shared/utils/http'
import { useAuthStore } from '../../stores/auth'
import { useInstitutionPanelScope } from './institutionPanelScope'
const props = defineProps<{ tenantId?: string; embedded?: boolean }>()
const emit = defineEmits<{ changed: [] }>()
const scope = useInstitutionPanelScope(() => props.tenantId ?? '')
const auth = useAuthStore()
const { allowed, checked } = useActionPermissions()
const tenantOptions = createOperationOptions('tenants', 'platform.agent_deployments.read')
const tenant = ref(props.tenantId ?? '')
const appliedTenant = ref('')
const refreshRequired = ref(false)
const targetChanged = computed(
  () => !!appliedTenant.value && tenant.value.trim() !== appliedTenant.value
)
const list = useResource((signal) =>
  api.agentDeployments.list({ signal, silent: true }, appliedTenant.value)
)
const definitions = useResource((signal) =>
  api.agentDeployments.definitions({ signal, silent: true }, appliedTenant.value)
)
const grants = useResource((signal) =>
  api.agentDeployments.grants({ signal, silent: true }, appliedTenant.value)
)
const action = useTask()
const formRef = ref<FormInstance>()
const storageKey = auth.user?.sub
  ? `noewise:platform-deployment-create:${encodeURIComponent(JSON.stringify([auth.user.tenant_id, auth.user.sub]))}`
  : ''
const pending = ref<{ key: string; input: PlatformDeploymentInput }>()
const storageFailed = ref(!storageKey)
if (storageKey) {
  try {
    const raw = sessionStorage.getItem(storageKey)
    if (raw) {
      const saved = JSON.parse(raw) as { key: string; input: PlatformDeploymentInput }
      if (
        !saved.key ||
        !saved.input?.tenant_id ||
        !saved.input.definition_id ||
        !saved.input.grant_id
      )
        throw Error('invalid')
      pending.value = saved
    }
  } catch {
    storageFailed.value = true
  }
}
const uncertain = computed(() => !!pending.value || storageFailed.value)
const form = reactive({
  definition_id: '',
  grant_id: '',
  integration_id: '',
  external_agent_ref: '',
  name: ''
})
const automaticDefinitionId = ref('')
const hasDraft = computed(
  () =>
    !!form.integration_id ||
    !!form.external_agent_ref ||
    !!form.name ||
    (!!form.definition_id && form.definition_id !== automaticDefinitionId.value) ||
    (!!form.grant_id && !automaticDefinitionId.value)
)
watch(
  [definitions.data, grants.data, list.data, definitions.loading, grants.loading, list.loading],
  () => {
    if (
      !allowed('platform.agent_deployments.create') ||
      !appliedTenant.value ||
      targetChanged.value ||
      uncertain.value ||
      !list.data.value ||
      !definitions.data.value ||
      !grants.data.value ||
      definitions.loading.value ||
      grants.loading.value ||
      list.loading.value ||
      definitions.error.value ||
      grants.error.value ||
      list.error.value
    )
      return
    const candidates = definitions.data.value.items.filter((definition) =>
      grants.data.value!.items.some((grant) =>
        usableGrant(grant, appliedTenant.value, String(definition.id))
      )
    )
    if (automaticDefinitionId.value) {
      if (candidates.length !== 1 || String(candidates[0]!.id) !== automaticDefinitionId.value) {
        automaticDefinitionId.value = ''
        form.definition_id = ''
        message.warning('可部署定义已变化，请重新选择；其他输入已保留。')
      }
      return
    }
    if (!hasDraft.value && !form.definition_id && candidates.length === 1) {
      automaticDefinitionId.value = String(candidates[0]!.id)
      form.definition_id = automaticDefinitionId.value
    }
  }
)
const availableGrants = computed(() =>
  (grants.data.value?.items ?? []).filter((grant) =>
    usableGrant(grant, appliedTenant.value, form.definition_id)
  )
)
watch(
  [() => form.definition_id, availableGrants],
  ([definition, available], [previous, oldAvailable]) => {
    if (uncertain.value) return
    if (definition !== previous || (oldAvailable.length === 1 && available.length > 1))
      form.grant_id = ''
    if (!available.some((grant) => grant.grant_id === form.grant_id))
      form.grant_id = available.length === 1 ? available[0]!.grant_id : ''
  }
)
const integrationOptions = computed(() =>
  createOperationOptions('integrations', 'platform.agent_deployments.create', appliedTenant.value)
)
/** 一次查询同时绑定部署目录及该机构可用定义/授权，禁止混用别的机构目录。 */
async function query() {
  if (action.busy.value) return
  if (!tenant.value.trim()) {
    action.error.value = '请选择目标机构'
    return
  }
  if (uncertain.value && targetChanged.value) {
    action.error.value = '请先核对原机构的请求结果后再切换机构'
    return
  }
  const target = tenant.value.trim()
  if (targetChanged.value && hasDraft.value) {
    if (
      !(await confirm({
        title: '切换目标机构',
        content: '切换机构会清除当前未保存的部署输入。',
        confirmText: '放弃并切换'
      }))
    )
      return
    if (tenant.value.trim() !== target || action.busy.value || uncertain.value) return
  }
  if (appliedTenant.value !== tenant.value.trim()) {
    appliedTenant.value = tenant.value.trim()
    automaticDefinitionId.value = ''
    Object.assign(form, {
      definition_id: '',
      grant_id: '',
      integration_id: '',
      external_agent_ref: '',
      name: ''
    })
    list.data.value = undefined
    definitions.data.value = undefined
    grants.data.value = undefined
    refreshRequired.value = false
  }
  reloadSetup()
}
/** 仅重新读取已确认的机构，不因表单中的待查询机构切换目标。 */
function reloadSetup() {
  if (action.busy.value || !appliedTenant.value || targetChanged.value) return
  void verify()
  if (allowed('platform.agent_deployments.create')) {
    void definitions.reload()
    void grants.reload()
  }
}
/** 刷新当前稳定部署映射；未知写入另沿原请求核对。 */
async function verify() {
  if (action.busy.value || !appliedTenant.value) return
  if (await list.reload()) {
    refreshRequired.value = false
    action.error.value = ''
    emit('changed')
  }
}
/** 平台显式选择目标及授权部署稳定实例；机构声明值由机构后台继续维护。 */
function create() {
  if (
    !allowed('platform.agent_deployments.create') ||
    uncertain.value ||
    targetChanged.value ||
    refreshRequired.value
  )
    return
  const requireTarget = scope.capture()
  void action.run(async () => {
    await formRef.value?.validate()
    requireTarget()
    if (
      !appliedTenant.value ||
      !list.data.value ||
      !form.definition_id ||
      !form.grant_id ||
      (!!form.integration_id.trim() && !form.external_agent_ref.trim())
    )
      throw Error('请先查询机构并选择定义、直接授权和外部智能体标识')
    if (!availableGrants.value.some((grant) => grant.grant_id === form.grant_id))
      throw Error('所选授权与定义不匹配，请重新选择')
    const input = {
      ...form,
      tenant_id: appliedTenant.value,
      integration_id: form.integration_id.trim() || null,
      external_agent_ref: form.integration_id.trim() ? form.external_agent_ref.trim() : null,
      name: form.name.trim() || undefined,
      resource_bindings: [],
      delegation_bindings: [],
      parameters: {}
    }
    const operation = { key: crypto.randomUUID(), input }
    try {
      sessionStorage.setItem(storageKey, JSON.stringify(operation))
    } catch {
      storageFailed.value = true
      throw Error('无法保存原请求，未发送部署；请恢复浏览器会话存储')
    }
    pending.value = operation
    try {
      const result = await checked('platform.agent_deployments.create', () =>
        api.agentDeployments.create(input, operation.key, { retryOnUnauthorized: false })
      )
      requireTarget()
      acceptDeployment(operation, result)
    } catch (reason) {
      if (reason instanceof HttpClientError && [400, 401, 403, 422].includes(reason.status)) {
        try {
          sessionStorage.removeItem(storageKey)
          pending.value = undefined
        } catch {
          storageFailed.value = true
        }
      }
      throw reason
    }
  })
}
function acceptDeployment(
  operation: { key: string; input: PlatformDeploymentInput },
  result: AgentDeployment
) {
  const input = operation.input
  if (
    !result?.deployment_id ||
    !result.agent_id ||
    result.tenant_id !== input.tenant_id ||
    result.definition_id !== input.definition_id ||
    result.grant_id !== input.grant_id ||
    result.integration_id !== input.integration_id ||
    !result.external_agent_ref ||
    (input.external_agent_ref !== null && result.external_agent_ref !== input.external_agent_ref)
  )
    throw Error('原部署回执身份无法核实')
  try {
    sessionStorage.removeItem(storageKey)
  } catch {
    storageFailed.value = true
    throw Error('部署已确认，但无法清除原请求记录；请恢复浏览器会话存储')
  }
  storageFailed.value = false
  pending.value = undefined
  automaticDefinitionId.value = ''
  Object.assign(form, {
    definition_id: '',
    grant_id: '',
    integration_id: '',
    external_agent_ref: '',
    name: ''
  })
  emit('changed')
  message.success('机构部署已保存；是否可运行请查看列表中的就绪状态')
  refreshRequired.value = true
  void list.reload().then((value) => {
    if (value) refreshRequired.value = false
  })
}
function reconcile() {
  void action.run(async () => {
    const operation = pending.value
    if (!operation) throw Error('无法读取原请求，请恢复浏览器会话存储')
    if (props.tenantId && props.tenantId !== operation.input.tenant_id)
      throw Error('请返回原机构核对部署请求')
    const requireTarget = scope.capture()
    const receipt = await checked('platform.agent_deployments.read', () =>
      api.agentDeployments.receipt(operation.input, operation.key)
    )
    requireTarget()
    if (pending.value !== operation) throw Error('原请求已变化，请重新核对')
    if (receipt.status === 'NOT_FOUND') throw Error('尚未查到原请求回执，请稍后继续核对')
    if (!receipt.result) throw Error('原部署回执身份无法核实')
    acceptDeployment(operation, receipt.result)
  })
}
useUnsavedChanges({
  dirty: () => hasDraft.value,
  busy: () => action.busy.value || uncertain.value
})
// 没有待保留的输入时，选定机构即可读取；有草稿或未知请求时保留显式切换。
watch(tenant, (value) => {
  if (props.tenantId || !value.trim() || action.busy.value || uncertain.value || hasDraft.value)
    return
  query()
})
watch(
  () => props.tenantId,
  (value) => {
    list.invalidate()
    list.data.value = undefined
    refreshRequired.value = false
    appliedTenant.value = ''
    tenant.value = value ?? pending.value?.input.tenant_id ?? ''
    if (tenant.value) query()
  },
  { immediate: true }
)
</script>
<template>
  <AdminPage
    :embedded="props.embedded"
    title="机构智能体部署"
    description="平台向明确目标机构部署已授权定义；机构后台维护本地资源、协作对象与业务参数。"
  >
    <ElForm
      v-if="!props.tenantId"
      inline
      :disabled="action.busy.value"
      @submit.prevent="query"
      @keydown.capture="guardFormEnter"
      ><ElFormItem label="目标机构" required
        ><EntitySelect
          v-model="tenant"
          :load="tenantOptions.load"
          :resolve="tenantOptions.resolve"
          placeholder="搜索机构名称"
        /> </ElFormItem
      ><ElButton native-type="submit" :loading="list.loading.value">查询机构部署</ElButton
      ><ElButton v-if="appliedTenant" @click="verify">刷新稳定部署</ElButton></ElForm
    >
    <ElButton
      v-if="props.tenantId"
      :loading="list.loading.value"
      :disabled="action.busy.value"
      @click="verify"
      >刷新稳定部署</ElButton
    >
    <p v-if="appliedTenant" class="admin-field-help">当前查询与操作目标机构：{{ appliedTenant }}</p>
    <ElAlert
      v-if="targetChanged"
      title="机构选择已变化；查询后才切换操作机构并清除未保存输入，当前部署仍属于上方已查询机构。"
      type="info"
      :closable="false"
    />
    <FeedbackNotice
      v-if="action.error.value || list.error.value || definitions.error.value || grants.error.value"
      :title="
        action.error.value || list.error.value || definitions.error.value || grants.error.value
      "
      type="error"
      :closable="false"
    /><ElAlert
      v-if="uncertain"
      :title="
        pending
          ? `机构 ${pending.input.tenant_id} 的部署结果待核对；未查到回执不代表原请求失败。`
          : '无法读取或保存原请求，请恢复浏览器会话存储。'
      "
      type="warning"
      :closable="false"
    />
    <ElButton v-if="pending" :loading="action.busy.value" @click="reconcile">核对原请求</ElButton>
    <p v-if="refreshRequired" class="admin-field-help" role="status">
      部署已保存，但列表尚未更新；请刷新稳定部署后继续，避免重复创建。
    </p>
    <ElButton
      v-if="list.error.value || definitions.error.value || grants.error.value"
      :disabled="action.busy.value || targetChanged"
      :loading="list.loading.value || definitions.loading.value || grants.loading.value"
      @click="reloadSetup"
      >重新读取部署及配置</ElButton
    >
    <ElForm
      v-if="allowed('platform.agent_deployments.create')"
      ref="formRef"
      :model="form"
      class="admin-card admin-form-grid"
      label-position="top"
      :disabled="
        action.busy.value || uncertain || targetChanged || refreshRequired || !list.data.value
      "
      @submit.prevent="create"
      @keydown.capture="guardFormEnter"
    >
      <ElFormItem
        label="公共定义"
        prop="definition_id"
        :rules="[{ required: true, message: '请选择公共定义' }]"
        ><ElSelect
          v-model="form.definition_id"
          @change="automaticDefinitionId = ''"
          :loading="definitions.loading.value"
          filterable
          ><ElOption
            v-for="definition in definitions.data.value?.items ?? []"
            :key="definition.id"
            :value="String(definition.id)"
            :label="definition.name + ' · ' + definition.version"
        /></ElSelect>
        <p v-if="automaticDefinitionId" class="admin-field-help">
          已预选唯一有有效授权的公共定义。
        </p>
      </ElFormItem>
      <ElFormItem
        label="直接授权"
        prop="grant_id"
        :rules="[{ required: true, message: '请选择当前定义的直接授权' }]"
        ><ElSelect
          v-model="form.grant_id"
          @change="automaticDefinitionId = ''"
          :loading="grants.loading.value"
          :disabled="!form.definition_id"
          ><ElOption
            v-for="grant in availableGrants"
            :key="grant.grant_id"
            :value="grant.grant_id"
            :label="grant.definition_name + ' · ' + grant.grant_id"
        /></ElSelect>
        <p v-if="form.grant_id && availableGrants.length === 1" class="admin-field-help">
          已预选唯一有效授权，提交时将使用此授权。
        </p></ElFormItem
      >
      <ElFormItem label="接入标识"
        ><EntitySelect
          v-model="form.integration_id"
          :load="integrationOptions.load"
          :resolve="integrationOptions.resolve"
          :disabled="!appliedTenant"
          placeholder="原生部署，或选择目标机构接入"
          aria-describedby="platform-deploy-integration-help"
      /></ElFormItem>
      <p id="platform-deploy-integration-help" class="full-width admin-field-help">
        原生部署留空；指定接入时必须属于目标机构。稳定部署映射创建后保持归属。
      </p>
      <ElFormItem
        v-if="form.integration_id"
        label="外部智能体标识"
        prop="external_agent_ref"
        :rules="[{ required: true, whitespace: true, message: '请填写外部智能体标识' }]"
        ><ElInput v-model="form.external_agent_ref" maxlength="128" /></ElFormItem
      ><ElFormItem label="实例名称"><ElInput v-model="form.name" maxlength="128" /></ElFormItem>
      <ElButton
        native-type="submit"
        type="primary"
        :loading="action.busy.value"
        :disabled="
          !list.data.value ||
          definitions.loading.value ||
          grants.loading.value ||
          !!definitions.error.value ||
          !!grants.error.value
        "
        >保存机构部署</ElButton
      >
    </ElForm>
    <ElTable
      :data="list.data.value?.items ?? []"
      v-loading="list.loading.value"
      row-key="deployment_id"
      :empty-text="
        !appliedTenant
          ? '选择目标机构后，查看机构部署'
          : list.loading.value
            ? '正在读取该机构部署…'
            : list.error.value
              ? '部署目录尚未读取，请查看上方错误后刷新稳定部署'
              : '该机构当前没有部署'
      "
      ><ElTableColumn
        prop="definition_name"
        label="公共定义"
        min-width="170"
        fixed="left"
        show-overflow-tooltip
      />
      <ElTableColumn label="定义来源" min-width="170"
        ><template #default="{ row }">{{
          row.grant_chain?.owner_kind === 'tenant'
            ? '机构 ' + row.grant_chain.owner_tenant_id
            : row.grant_chain?.owner_kind === 'platform'
              ? '平台公共定义'
              : '来源待核对'
        }}</template></ElTableColumn
      >
      <ElTableColumn
        prop="deployment_id"
        label="部署标识"
        min-width="180"
        show-overflow-tooltip
      /><ElTableColumn
        prop="agent_id"
        label="稳定 Agent 标识"
        min-width="180"
        show-overflow-tooltip
      /><ElTableColumn
        prop="integration_id"
        label="接入标识"
        min-width="170"
        show-overflow-tooltip
      /><ElTableColumn
        prop="grant_id"
        label="授权标识"
        min-width="160"
        show-overflow-tooltip
      /><ElTableColumn prop="grant_revision" label="授权修订" width="110" /><ElTableColumn
        prop="deployment_revision"
        label="部署修订"
        width="110"
      /><ElTableColumn prop="definition_version" label="当前发布" width="110" /><ElTableColumn
        label="就绪状态"
        min-width="240"
        ><template #default="{ row }"
          ><ElTag :type="row.ready ? 'success' : 'warning'">{{
            row.ready ? '可运行' : '待完善'
          }}</ElTag>
          <ul v-if="!row.ready">
            <li v-for="issue in row.issues" :key="issue.path + issue.code">{{ issue.message }}</li>
          </ul></template
        ></ElTableColumn
      ></ElTable
    >
  </AdminPage>
</template>
