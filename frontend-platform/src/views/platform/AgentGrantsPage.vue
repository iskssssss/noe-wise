<script setup lang="ts">
import FeedbackNotice from '@skillforge/shared/feedback/FeedbackNotice.vue'
import { computed, reactive, ref, watch } from 'vue'
import type { FormInstance } from 'element-plus'
import { formatDateTime } from '@skillforge/shared/utils/datetime'
import {
  EntitySelect,
  statusLabel,
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
import type { AgentGrant } from '@skillforge/shared/integration/agentDeployments'
import { useInstitutionPanelScope, useInstitutionPanelCommand } from './institutionPanelScope'
const props = defineProps<{ tenantId?: string; embedded?: boolean }>()
const emit = defineEmits<{ changed: [] }>()
const scope = useInstitutionPanelScope(() => props.tenantId ?? '')
const { allowed, checked } = useActionPermissions()
const tenantOptions = createOperationOptions('tenants', 'platform.agent_grants.read')
const definitionOptions = createOperationOptions('definitions', 'platform.agent_grants.create')
const tenant = ref(props.tenantId ?? '')
const appliedTenant = ref('')
const refreshRequired = ref(false)
const targetChanged = computed(
  () => !!appliedTenant.value && tenant.value.trim() !== appliedTenant.value
)
const form = reactive({ definition_id: '', expires_at: '' })
const list = useResource((signal) =>
  api.agentGrants.list(appliedTenant.value, { signal, silent: true })
)
const action = useTask()
const formRef = ref<FormInstance>()
const command = useInstitutionPanelCommand()
const uncertain = computed(() => !!command.pending.value)
/** 无草稿时选择机构即可查询；有草稿时确认后才切换目标。 */
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
  if (targetChanged.value && (form.definition_id || form.expires_at)) {
    if (
      !(await confirm({
        title: '切换目标机构',
        content: '切换机构会清除当前未保存的授权输入。',
        confirmText: '放弃并切换'
      }))
    )
      return
    if (tenant.value.trim() !== target || action.busy.value || uncertain.value) return
  }
  if (appliedTenant.value !== tenant.value.trim()) {
    appliedTenant.value = tenant.value.trim()
    Object.assign(form, { definition_id: '', expires_at: '' })
    list.data.value = undefined
    refreshRequired.value = false
  }
  void verify()
}
/** 只刷新当前授权目录；未知写入必须另沿原请求核对。 */
async function verify() {
  if (action.busy.value || !appliedTenant.value) return
  if (await list.reload()) {
    refreshRequired.value = false
    action.error.value = ''
    emit('changed')
  }
}
/** 定向授权完整 deploy/execute，不提供首版之外的转授权选项。 */
function create() {
  if (
    !allowed('platform.agent_grants.create') ||
    uncertain.value ||
    targetChanged.value ||
    refreshRequired.value
  )
    return
  const requireTarget = scope.capture()
  void action.run(async () => {
    await formRef.value?.validate()
    requireTarget()
    if (!appliedTenant.value || !list.data.value || !form.definition_id.trim())
      throw Error('请先查询目标机构并选择公共定义')
    const input = {
      definition_id: form.definition_id.trim(),
      target_tenant_id: appliedTenant.value,
      actions: ['deploy', 'execute'] as Array<'deploy' | 'execute'>,
      expires_at: form.expires_at || null,
      allow_regrant: false as const,
      remaining_depth: 0 as const
    }
    if (
      !(await confirm({
        title: '授予定义部署与执行权限',
        content: `向机构 ${input.target_tenant_id} 授予定义 ${input.definition_id} 的部署和执行权限？`,
        confirmText: '授权',
        cancelText: '取消'
      }))
    )
      return
    requireTarget()
    await command.execute(
      (key) => checked('platform.agent_grants.create', () => api.agentGrants.create(input, key)),
      () => {
        Object.assign(form, { definition_id: '', expires_at: '' })
        emit('changed')
        message.success('定义已直接授权')
        refreshRequired.value = true
        void list.reload().then((value) => {
          if (value) refreshRequired.value = false
        })
      },
      requireTarget
    )
  })
}
/** 撤销绑定目录所见修订，失败不清除当前授权事实。 */
function revoke(id: AgentGrant['grant_id']) {
  if (targetChanged.value || list.loading.value || refreshRequired.value) return
  const row = list.data.value?.items.find((item) => item.grant_id === id)
  if (!row || !allowed('platform.agent_grants.revoke') || uncertain.value) return
  const requireTarget = scope.capture()
  void action.run(async () => {
    if (
      !(await confirm({
        title: '撤销直接授权',
        content:
          '撤销后，后续部署和执行将重新检查权限，此授权不再有效；已发生的历史记录保留。确认撤销？',
        confirmText: '撤销',
        cancelText: '取消',
        danger: true
      }))
    )
      return
    requireTarget()
    const input = { grant_id: row.grant_id, expected_revision: row.revision }
    await command.execute(
      (key) => checked('platform.agent_grants.revoke', () => api.agentGrants.revoke(input, key)),
      () => {
        emit('changed')
        message.success('授权已撤销')
        refreshRequired.value = true
        void list.reload().then((value) => {
          if (value) refreshRequired.value = false
        })
      },
      requireTarget
    )
  })
}
function reconcile() {
  void action.run(() => command.reconcile())
}
useUnsavedChanges({
  dirty: () => !!form.definition_id || !!form.expires_at,
  busy: () => action.busy.value || uncertain.value
})
watch(tenant, (value) => {
  if (
    props.tenantId ||
    !value.trim() ||
    action.busy.value ||
    uncertain.value ||
    form.definition_id ||
    form.expires_at
  )
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
    tenant.value = value ?? ''
    if (value) query()
  },
  { immediate: true }
)
</script>
<template>
  <AdminPage
    :embedded="props.embedded"
    title="定义直接授权"
    description="平台向目标机构直接授予受控定义的部署与执行权限。"
  >
    <ElForm
      v-if="!props.tenantId"
      class="admin-filters"
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
      ><ElButton native-type="submit" :loading="list.loading.value">查询授权</ElButton
      ><ElButton v-if="appliedTenant" @click="verify">刷新授权事实</ElButton></ElForm
    >
    <ElButton
      v-if="props.tenantId"
      :loading="list.loading.value"
      :disabled="action.busy.value"
      @click="verify"
      >刷新授权事实</ElButton
    >
    <p v-if="appliedTenant" class="admin-field-help">当前查询与操作目标机构：{{ appliedTenant }}</p>
    <ElAlert
      v-if="targetChanged"
      title="机构选择已变化；查询后才切换操作机构并清除未保存输入，当前授权仍属于上方已查询机构。"
      type="info"
      :closable="false"
    />
    <FeedbackNotice
      v-if="action.error.value || list.error.value"
      :title="action.error.value || list.error.value"
      type="error"
      :closable="false"
    /><ElAlert
      v-if="uncertain"
      title="授权结果待核对。刷新目录不会解除阻止，请核对原请求。"
      type="warning"
      :closable="false"
    />
    <ElButton v-if="uncertain" :loading="action.busy.value" @click="reconcile">核对原请求</ElButton>
    <p v-if="refreshRequired" class="admin-field-help" role="status">
      授权操作已完成，但列表尚未更新；请刷新授权事实后继续。
    </p>
    <ElForm
      v-if="allowed('platform.agent_grants.create')"
      ref="formRef"
      :model="form"
      class="admin-filters"
      inline
      :disabled="
        action.busy.value || uncertain || targetChanged || refreshRequired || !list.data.value
      "
      @submit.prevent="create"
      @keydown.capture="guardFormEnter"
      ><ElFormItem
        label="公共定义"
        prop="definition_id"
        :rules="[{ required: true, message: '请选择公共定义' }]"
        ><EntitySelect
          v-model="form.definition_id"
          :load="definitionOptions.load"
          :resolve="definitionOptions.resolve"
          :disabled="action.busy.value"
          placeholder="搜索公共定义名称" /></ElFormItem
      ><ElFormItem label="过期时间"
        ><ElDatePicker
          v-model="form.expires_at"
          type="datetime"
          placeholder="不过期（可选）"
          value-format="YYYY-MM-DDTHH:mm:ssZ"
          :disabled="action.busy.value"
          aria-label="授权过期时间，留空表示不过期" /></ElFormItem
      ><ElButton
        type="primary"
        native-type="submit"
        :loading="action.busy.value"
        :disabled="!list.data.value || uncertain || targetChanged || refreshRequired"
        >授予部署与执行权限</ElButton
      ></ElForm
    >
    <ElTable
      :data="list.data.value?.items ?? []"
      v-loading="list.loading.value"
      row-key="grant_id"
      :empty-text="
        !appliedTenant
          ? '选择目标机构后，查看直接授权'
          : list.loading.value
            ? '正在读取该机构授权…'
            : list.error.value
              ? '授权目录尚未读取，请查看上方错误后刷新授权事实'
              : '该机构当前没有直接授权'
      "
      ><ElTableColumn prop="definition_name" label="定义名称" min-width="170" /><ElTableColumn
        prop="definition_id"
        label="定义标识"
        min-width="160"
        show-overflow-tooltip
      /><ElTableColumn
        prop="grant_id"
        label="授权标识"
        min-width="160"
        show-overflow-tooltip
      /><ElTableColumn prop="definition_version" label="发布版本" width="110" /><ElTableColumn
        prop="status"
        label="状态"
        width="100"
        ><template #default="{ row }">{{ statusLabel(row.status) }}</template></ElTableColumn
      ><ElTableColumn prop="revision" label="修订" width="90" /><ElTableColumn
        prop="expires_at"
        label="过期时间"
        min-width="180"
        ><template #default="{ row }">{{
          row.expires_at ? formatDateTime(row.expires_at) : '不过期'
        }}</template></ElTableColumn
      ><ElTableColumn label="操作" width="110"
        ><template #default="{ row }"
          ><ElButton
            v-if="allowed('platform.agent_grants.revoke')"
            link
            type="danger"
            :disabled="
              action.busy.value ||
              list.loading.value ||
              targetChanged ||
              refreshRequired ||
              uncertain ||
              row.status !== 'active'
            "
            @click="revoke(row.grant_id)"
            >撤销授权</ElButton
          ></template
        ></ElTableColumn
      ></ElTable
    >
  </AdminPage>
</template>
