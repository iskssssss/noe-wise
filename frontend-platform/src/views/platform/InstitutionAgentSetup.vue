<script setup lang="ts">
import FeedbackNotice from '@skillforge/shared/feedback/FeedbackNotice.vue'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import {
  AdminPage,
  EntitySelect,
  guardFormEnter,
  useResource,
  useTask,
  useUnsavedChanges
} from '@skillforge/shared/admin'
import type { EntityOption } from '@skillforge/shared/admin'
import { confirm } from '@skillforge/shared/feedback'
import { api } from '../../api'
import { createOperationOptions } from '../../api/entityOptions'
import { useActionPermissions } from '../../composables/actionPermissions'
import { useAuthStore } from '../../stores/auth'
import {
  AgentSetupProgress,
  usableGrant,
  type PlatformDeploymentInput
} from '../../institution/agentSetup'

const props = defineProps<{ tenantId: string; integrationId?: string; definitionId?: string }>()
const emit = defineEmits<{
  changed: []
  completed: [
    value: { tenantId: string; integrationId?: string; agentId: string; deploymentId: string }
  ]
}>()
const { allowed, requireAction, checked } = useActionPermissions()
const auth = useAuthStore()
const tenant = props.tenantId
const targetMatches = computed(() => props.tenantId === tenant)
const action = useTask()
const storageFailed = ref(false)
const form = reactive({
  mode: allowed('platform.agent_deployments.read') ? 'existing' : 'new',
  definition_id: '',
  grant_id: '',
  expires_at: '',
  integration_id: props.integrationId ?? '',
  external_agent_ref: '',
  name: ''
})
let baseline = JSON.stringify(form)
const modeTouched = ref(false)
function guard() {
  if (!targetMatches.value) throw Error('目标机构已变化，请重新打开添加流程')
  requireAction('platform.agent_deployments.create')
}
const progress = reactive(
  new AgentSetupProgress(
    {
      grant: (input, key) =>
        checked('platform.agent_grants.create', () => api.agentGrants.create(input, key)),
      deploy: (input, key) =>
        checked('platform.agent_deployments.create', () =>
          api.agentDeployments.create(input, key, { retryOnUnauthorized: false })
        )
    },
    guard,
    () => crypto.randomUUID(),
    () => {
      if (storageFailed.value) throw Error('无法保存原请求，请检查浏览器存储后重试')
    }
  )
)
// 仅保存本流程请求与回执，不保存令牌、凭据或模型配置；同一标签页按主体和目标恢复。
const storageKey = auth.user?.sub
  ? `skillforge.agent-setup:${encodeURIComponent(JSON.stringify(props.definitionId ? [auth.user.tenant_id, auth.user.sub, tenant, props.definitionId] : [auth.user.tenant_id, auth.user.sub, tenant, 'onboarding', props.integrationId ?? null]))}`
  : ''
if (!storageKey) storageFailed.value = true
if (storageKey) {
  try {
    const saved = sessionStorage.getItem(storageKey)
    if (saved) {
      const state = JSON.parse(saved) as {
        form: typeof form
        grant?: typeof progress.grant
        grantCommand?: typeof progress.grantCommand
        deploymentCommand?: typeof progress.deploymentCommand
      }
      Object.assign(form, state.form)
      progress.grant = state.grant
      progress.grantCommand = state.grantCommand
      progress.deploymentCommand = state.deploymentCommand
      if (progress.grantCommand?.status === 'pending') progress.grantCommand.status = 'unknown'
      if (progress.deploymentCommand?.status === 'pending')
        progress.deploymentCommand.status = 'unknown'
    }
  } catch {
    storageFailed.value = true
  }
  watch(
    [form, progress],
    () => {
      try {
        if (
          progress.deployment ||
          (!progress.grant &&
            !progress.unknown &&
            progress.grantCommand?.status !== 'pending' &&
            progress.deploymentCommand?.status !== 'pending')
        )
          sessionStorage.removeItem(storageKey)
        else
          sessionStorage.setItem(
            storageKey,
            JSON.stringify({
              form,
              grant: progress.grant,
              grantCommand: progress.grantCommand,
              deploymentCommand: progress.deploymentCommand
            })
          )
        storageFailed.value = false
      } catch {
        storageFailed.value = true
      }
    },
    { deep: true, flush: 'sync' }
  )
}
const catalogue = useResource(async (signal) => {
  requireAction('platform.agent_deployments.read')
  const [definitions, grants] = await Promise.all([
    api.agentDeployments.definitions({ signal, silent: true }, tenant),
    api.agentDeployments.grants({ signal, silent: true }, tenant)
  ])
  requireAction('platform.agent_deployments.read')
  if (!targetMatches.value) throw Error('目标机构已变化')
  return { definitions: definitions.items, grants: grants.items }
})
let catalogueRead: ReturnType<typeof catalogue.reload> | undefined
/** 选项首次读取与面板挂载复用同一请求，不相互取消而伪造读取失败。 */
function reloadCatalogue() {
  if (catalogueRead) return catalogueRead
  const current = catalogue.reload()
  catalogueRead = current
  void current.finally(() => {
    if (catalogueRead === current) catalogueRead = undefined
  })
  return current
}
const sourceOptions = createOperationOptions('definitions', 'platform.agent_grants.create')
const deploymentTenants = createOperationOptions('tenants', 'platform.agent_deployments.create')
const grantTenants = createOperationOptions('tenants', 'platform.agent_grants.create')
const integrationOptions = createOperationOptions(
  'integrations',
  'platform.agent_deployments.create',
  tenant
)
async function loadDefinitions(query: string, signal?: AbortSignal): Promise<EntityOption[]> {
  if (form.mode === 'new') {
    requireAction('platform.agent_grants.create')
    return checked('platform.agent_grants.create', () => sourceOptions.load(query, signal))
  }
  requireAction('platform.agent_deployments.read')
  if (!catalogue.data.value) await reloadCatalogue()
  if (!catalogue.data.value) throw Error(catalogue.error.value || '可部署定义尚未读到')
  const search = query.trim().toLocaleLowerCase()
  return catalogue.data.value.definitions
    .filter((item) => !search || `${item.name} ${item.id}`.toLocaleLowerCase().includes(search))
    .map((item) => ({ id: String(item.id), label: item.name, description: item.version }))
}
async function resolveDefinition(id: string) {
  if (form.mode === 'new')
    return checked('platform.agent_grants.create', () => sourceOptions.resolve(id))
  return (await loadDefinitions('')).find((item) => item.id === id)
}
const availableGrants = computed(() =>
  (catalogue.data.value?.grants ?? []).filter((item) =>
    usableGrant(item, tenant, form.definition_id)
  )
)
const grantSelectionRequired = ref(false)
const automaticGrantId = ref('')
function onGrantChange() {
  grantSelectionRequired.value = false
  automaticGrantId.value = ''
}
let prefillPending = false
function prefillContext() {
  if (!props.definitionId) return
  if (action.busy.value) {
    prefillPending = true
    return
  }
  prefillPending = false
  void action.run(() => validateContext(true))
}
watch(action.busy, (busy) => {
  if (!busy && prefillPending) prefillContext()
})
watch([() => form.definition_id, availableGrants], ([definition], [previousDefinition]) => {
  if (progress.grant || progress.unknown) return
  if (definition !== previousDefinition) {
    grantSelectionRequired.value = false
    automaticGrantId.value = ''
  }
  if (
    automaticGrantId.value &&
    definition === previousDefinition &&
    (availableGrants.value.length !== 1 ||
      availableGrants.value[0]!.grant_id !== automaticGrantId.value)
  ) {
    automaticGrantId.value = ''
    form.grant_id = ''
    grantSelectionRequired.value = true
    return
  }
  if (!availableGrants.value.some((item) => item.grant_id === form.grant_id)) {
    if (form.grant_id && definition === previousDefinition) grantSelectionRequired.value = true
    automaticGrantId.value =
      !grantSelectionRequired.value && availableGrants.value.length === 1
        ? availableGrants.value[0]!.grant_id
        : ''
    form.grant_id = automaticGrantId.value
  }
})
watch(
  () => form.mode,
  () => {
    if (progress.grant || progress.unknown) return
    form.definition_id = ''
    form.grant_id = ''
    prefillContext()
  }
)
watch(
  () => props.tenantId,
  () => {
    catalogue.invalidate()
    catalogue.data.value = undefined
  }
)
watch(
  () => allowed('platform.agent_deployments.read'),
  (current) => {
    if (!current) {
      catalogue.invalidate()
      catalogue.data.value = undefined
    }
  }
)
const accessCurrent = computed(
  () =>
    allowed('platform.agent_deployments.read') ||
    allowed('platform.agent_deployments.create') ||
    allowed('platform.agent_grants.read')
)
const locked = computed(
  () => action.busy.value || progress.unknown || !!progress.deployment || !targetMatches.value
)
const canSubmit = computed(
  () =>
    allowed('platform.agent_deployments.create') &&
    targetMatches.value &&
    (!props.definitionId || !!form.definition_id) &&
    !progress.unknown &&
    (!progress.grant || usableGrant(progress.grant, tenant, form.definition_id)) &&
    (!progress.grant && form.mode === 'new' ? allowed('platform.agent_grants.create') : true)
)
useUnsavedChanges({
  dirty: () =>
    !progress.deployment &&
    (JSON.stringify(form) !== baseline || !!progress.grant || progress.unknown),
  busy: () => action.busy.value || (progress.unknown && (!storageKey || storageFailed.value)),
  leaveMessage: () =>
    progress.unknown && storageKey
      ? '原请求结果未知，已保存在当前标签页。返回原接入流程后可核对原回执；关闭标签页会丢失恢复信息。确认离开？'
      : progress.grant && storageKey
        ? '授权已成功，进度保存在当前标签页。返回原接入流程可继续部署；关闭标签页会丢失恢复信息。确认离开？'
        : undefined
})
function deploymentInput(): PlatformDeploymentInput {
  const grantId = progress.grant?.grant_id ?? form.grant_id
  const integrationId = form.integration_id.trim() || null
  let externalRef = form.external_agent_ref.trim()
  if (!externalRef && integrationId === null)
    externalRef =
      progress.deploymentCommand?.input.integration_id === null
        ? progress.deploymentCommand.input.external_agent_ref
        : `agent-${crypto.randomUUID()}`
  if (!form.definition_id || !grantId || !externalRef)
    throw Error('请选择定义、授权并填写外部智能体标识')
  if (progress.grant && !usableGrant(progress.grant, tenant, form.definition_id))
    throw Error('原授权当前不能用于部署，请在授权目录核对，不会自动切换授权链')
  if (!progress.grant && !availableGrants.value.some((item) => item.grant_id === grantId))
    throw Error('请重新读取并选择当前机构的有效授权')
  return {
    tenant_id: tenant,
    integration_id: integrationId,
    definition_id: form.definition_id,
    grant_id: grantId,
    external_agent_ref: externalRef,
    name: form.name.trim() || undefined,
    resource_bindings: [],
    delegation_bindings: [],
    parameters: {}
  }
}
/** 模板上下文仅在服务端当前发布状态和实际操作目录均确认后使用。 */
async function validateContext(prefill = false) {
  const definitionId = props.definitionId
  const mode = form.mode
  if (!definitionId) return
  const checkContext = () => {
    guard()
    if (props.definitionId !== definitionId || form.mode !== mode)
      throw Error('模板或授权方式已变化，请重新核对')
  }
  checkContext()
  const detail = await api.agentTemplates.detail(definitionId, { silent: true })
  checkContext()
  if (
    detail.definition_id !== definitionId ||
    detail.owner_kind !== 'platform' ||
    detail.status !== 'published' ||
    !detail.current_published_revision ||
    detail.distribution_policy !== 'AUTHORIZED_DEPLOY'
  )
    throw Error('该定义当前不是可授权部署的已发布公共模板，请重新核对')
  if (!(await deploymentTenants.resolve(tenant)))
    throw Error('当前机构不在可部署范围内，请重新选择机构')
  checkContext()
  if (!progress.grant && mode === 'new') {
    requireAction('platform.agent_grants.create')
    const tenantOption = await grantTenants.resolve(tenant)
    checkContext()
    requireAction('platform.agent_grants.create')
    const definitionOption = await sourceOptions.resolve(definitionId)
    checkContext()
    requireAction('platform.agent_grants.create')
    if (!tenantOption || !definitionOption) throw Error('当前机构或定义不在授权范围内，请重新核对')
  } else if (!progress.grant) {
    await reloadCatalogue()
    checkContext()
    if (
      catalogue.error.value ||
      !catalogue.data.value?.definitions.some((item) => String(item.id) === definitionId)
    )
      throw Error('当前机构的可部署目录中没有此定义，请刷新授权与定义')
  }
  checkContext()
  if (prefill) form.definition_id = definitionId
  else if (form.definition_id !== definitionId) throw Error('模板身份已变化，请重新打开流程')
}
function completed() {
  const value = progress.deployment
  if (!value) return
  emit('changed')
  emit('completed', {
    tenantId: tenant,
    integrationId: value.integration_id ?? undefined,
    agentId: value.agent_id,
    deploymentId: value.deployment_id
  })
}
function submit() {
  if (!canSubmit.value || action.busy.value) return
  void action.run(async () => {
    guard()
    if (!form.definition_id) throw Error('请选择定义')
    if (form.integration_id.trim() && !form.external_agent_ref.trim())
      throw Error('请选择接入对应的外部智能体标识')
    if (!progress.grant && form.mode === 'new') {
      if (
        !(await confirm({
          title: '授权并部署智能体',
          content: `向机构 ${tenant} 授予所选定义的部署与执行权限，然后创建本地实例。授权成功后不会因部署失败而自动撤销。`,
          confirmText: '授权并部署',
          cancelText: '取消'
        }))
      )
        return
      guard()
      await validateContext()
      await progress.authorize({
        definition_id: form.definition_id,
        target_tenant_id: tenant,
        actions: ['deploy', 'execute'],
        expires_at: form.expires_at || null,
        allow_regrant: false,
        remaining_depth: 0
      })
      emit('changed')
    } else await validateContext()
    await progress.deploy(deploymentInput())
    completed()
  })
}
function verifyOriginal() {
  if (!progress.unknown || action.busy.value) return
  void action.run(async () => {
    if (!targetMatches.value) throw Error('目标机构已变化，请重新打开添加流程')
    // 只读查询原回执，未找到或查询失败都保留未知状态。
    if (progress.grantCommand?.status === 'unknown') {
      const command = progress.grantCommand
      const receipt = await checked('platform.agent_grants.read', () =>
        api.agentGrants.receipt(command.input, command.key)
      )
      if (receipt.status !== 'FOUND' || !receipt.result)
        throw Error('尚未查到原授权回执，结果仍未知，请稍后核对')
      progress.acceptGrantReceipt(receipt.result)
      emit('changed')
    } else if (progress.deploymentCommand?.status === 'unknown') {
      const command = progress.deploymentCommand
      const receipt = await checked('platform.agent_deployments.read', () =>
        api.agentDeployments.receipt(command.input, command.key)
      )
      if (receipt.status !== 'FOUND' || !receipt.result)
        throw Error('尚未查到原部署回执，结果仍未知，请稍后核对')
      progress.acceptDeploymentReceipt(receipt.result)
      completed()
    }
  })
}
onMounted(() => {
  if (progress.unknown || progress.grant || !allowed('platform.agent_deployments.read')) {
    if (props.definitionId && !progress.unknown && !progress.grant) prefillContext()
    else if (allowed('platform.agent_deployments.read')) void reloadCatalogue()
    return
  }
  const initialForm = JSON.stringify(form)
  void reloadCatalogue().then((result) => {
    const selectNew =
      result &&
      targetMatches.value &&
      allowed('platform.agent_grants.create') &&
      !modeTouched.value &&
      !progress.grant &&
      !progress.unknown &&
      form.mode === 'existing' &&
      JSON.stringify(form) === initialForm &&
      !result.grants.some((grant) =>
        usableGrant(grant, tenant, props.definitionId ?? grant.definition_id)
      )
    if (selectNew) {
      form.mode = 'new'
      baseline = JSON.stringify(form)
    } else if (result && props.definitionId && !progress.unknown && !progress.grant)
      prefillContext()
  })
})
</script>

<template>
  <AdminPage
    title="添加智能体"
    description="在当前机构内完成授权与部署，已有成功结果会保留。"
    embedded
  >
    <ElAlert
      v-if="!targetMatches || !accessCurrent"
      :title="
        !targetMatches
          ? '目标机构已变化，请重新打开添加流程。'
          : '当前账号已没有此流程权限，请刷新授权资料。'
      "
      type="warning"
      :closable="false"
    />
    <template v-else>
      <p class="admin-field-help">
        目标机构：<code>{{ tenant }}</code
        >。这里创建本地实例，不启动模型或工具。
      </p>
      <ElButton
        v-if="definitionId && !form.definition_id"
        :loading="action.busy.value"
        @click="action.run(() => validateContext(true))"
        >重新核对模板与机构</ElButton
      >
      <FeedbackNotice
        v-if="action.error.value || catalogue.error.value"
        :title="action.error.value || catalogue.error.value"
        type="error"
        :closable="false"
      />
      <ElAlert
        v-if="progress.unknown"
        title="原请求结果未知。请核对原请求，不能更换标识或重新授权。"
        type="warning"
        :closable="false"
      >
        <ElButton :loading="action.busy.value" @click="verifyOriginal">核对原请求</ElButton>
      </ElAlert>
      <ElAlert
        v-if="progress.grant && !progress.deployment"
        :title="
          usableGrant(progress.grant, tenant, form.definition_id)
            ? '授权已成功，下一步只继续部署，不会再次创建授权。'
            : '已读回原授权，但当前已不可部署；请在授权目录核对。'
        "
        :type="usableGrant(progress.grant, tenant, form.definition_id) ? 'success' : 'warning'"
        :closable="false"
      >
        授权标识：<code>{{ progress.grant.grant_id }}</code>
      </ElAlert>
      <ElForm
        v-if="!progress.deployment"
        label-position="top"
        class="setup-form"
        :disabled="locked"
        @submit.prevent="submit"
        @keydown.capture="guardFormEnter"
      >
        <ElFormItem label="授权方式">
          <ElRadioGroup
            v-model="form.mode"
            :disabled="!!progress.grant"
            @change="modeTouched = true"
          >
            <ElRadioButton value="existing" :disabled="!allowed('platform.agent_deployments.read')"
              >使用已有授权</ElRadioButton
            >
            <ElRadioButton value="new" :disabled="!allowed('platform.agent_grants.create')"
              >新增授权并部署</ElRadioButton
            >
          </ElRadioGroup>
        </ElFormItem>
        <ElFormItem label="公共定义" required>
          <EntitySelect
            :key="form.mode"
            v-model="form.definition_id"
            :load="loadDefinitions"
            :resolve="resolveDefinition"
            :disabled="!!progress.grant || !!definitionId"
            placeholder="搜索定义名称"
          />
        </ElFormItem>
        <ElFormItem v-if="form.mode === 'existing'" label="部署与执行授权" required>
          <ElSelect
            v-model="form.grant_id"
            @change="onGrantChange"
            :loading="catalogue.loading.value"
            :disabled="!form.definition_id"
            placeholder="选择明确授权"
          >
            <ElOption
              v-for="grant in availableGrants"
              :key="grant.grant_id"
              :value="grant.grant_id"
              :label="`${grant.definition_name} · ${grant.grant_id}`"
            />
          </ElSelect>
          <p v-if="availableGrants.length === 1 && form.grant_id" class="admin-field-help">
            当前选择唯一有效授权，提交时将使用此授权。
          </p>
          <p v-else-if="availableGrants.length === 1" class="admin-field-help" role="status">
            原授权不可用，请确认并选择当前有效授权。
          </p>
          <p v-else-if="availableGrants.length > 1" class="admin-field-help">
            存在多条授权链，请明确选择，不会自动切换。
          </p>
          <ElButton
            v-if="allowed('platform.agent_deployments.read')"
            link
            :loading="catalogue.loading.value"
            @click="reloadCatalogue"
            >刷新授权与定义</ElButton
          >
        </ElFormItem>
        <ElFormItem v-else label="授权到期时间（可选）">
          <ElDatePicker
            v-model="form.expires_at"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ssZ"
            :disabled="!!progress.grant"
            placeholder="不填则无到期时间"
          />
          <p class="admin-field-help">仅授予部署与执行权限，不允许转授。</p>
        </ElFormItem>
        <ElFormItem label="接入来源（可选）">
          <code v-if="integrationId">{{ integrationId }}</code>
          <EntitySelect
            v-else
            v-model="form.integration_id"
            :load="integrationOptions.load"
            :resolve="integrationOptions.resolve"
            placeholder="可选择当前机构已登记的接入"
            clearable
          />
        </ElFormItem>
        <ElFormItem
          :label="form.integration_id ? '外部智能体标识' : '实例标识（可选）'"
          :required="!!form.integration_id"
        >
          <ElInput
            v-model="form.external_agent_ref"
            maxlength="128"
            :placeholder="
              form.integration_id ? '填写接入系统中的智能体标识' : '留空自动生成稳定标识'
            "
          />
          <p class="admin-field-help">
            {{
              form.integration_id
                ? '请填写外部系统的真实标识。'
                : '未选择外部接入时，系统会自动生成实例标识。'
            }}
          </p>
        </ElFormItem>
        <ElFormItem label="实例名称（可选）"
          ><ElInput v-model="form.name" placeholder="留空使用定义名称"
        /></ElFormItem>
        <div class="setup-actions">
          <ElButton
            native-type="submit"
            type="primary"
            :loading="action.busy.value"
            :disabled="!canSubmit"
          >
            {{ progress.grant ? '继续部署' : form.mode === 'new' ? '授权并部署' : '部署智能体' }}
          </ElButton>
          <span v-if="!allowed('platform.agent_deployments.create')" class="admin-field-help"
            >当前账号没有部署创建权限。</span
          >
        </div>
      </ElForm>
      <div v-if="progress.deployment" class="setup-result" role="status">
        <ElAlert
          :title="
            progress.deployment.ready
              ? '智能体已部署，可以运行'
              : '智能体已部署，等待机构完成本地配置'
          "
          :type="progress.deployment.ready ? 'success' : 'warning'"
          :closable="false"
        />
        <p>
          实例：<code>{{ progress.deployment.agent_id }}</code> · 部署：<code>{{
            progress.deployment.deployment_id
          }}</code>
        </p>
        <p>
          发布版本：{{ progress.deployment.definition_version }} · 绑定修订：{{
            progress.deployment.deployment_revision
          }}
        </p>
        <ul v-if="progress.deployment.issues.length">
          <li v-for="issue in progress.deployment.issues" :key="issue.path + issue.code">
            {{ issue.message }} <span class="admin-field-help">{{ issue.path }}</span>
          </li>
        </ul>
        <p v-if="!progress.deployment.ready" class="admin-field-help">
          请机构管理员在机构后台“公共智能体实例”中定位上述实例并配置本地资源。平台不会代用机构身份。
        </p>
      </div>
    </template>
  </AdminPage>
</template>

<style scoped>
.setup-form {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 280px), 1fr));
  gap: 0 24px;
}
.setup-form :deep(.el-form-item) {
  min-width: 0;
}
.setup-form :deep(.el-select),
.setup-form :deep(.el-date-editor) {
  width: 100%;
}
.setup-actions {
  grid-column: 1 / -1;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
}
.setup-result {
  padding: 16px 0;
  overflow-wrap: anywhere;
}
.setup-result ul {
  padding-left: 20px;
}
</style>
