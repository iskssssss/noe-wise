<script setup lang="ts">
import FeedbackNotice from '@skillforge/shared/feedback/FeedbackNotice.vue'
import { vDescribedBy } from '@skillforge/shared/directives/describedBy'
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import type { FormInstance } from 'element-plus'
import {
  AdminPage,
  EntitySelect,
  guardFormEnter,
  useDrawerClose,
  useResource,
  useTask,
  useUnsavedChanges
} from '@skillforge/shared/admin'
import { message } from '@skillforge/shared/feedback'
import { api } from '../../api'
import {
  createOperationOptions,
  createProvisionPlanOptions,
  loadNamespaceOptions
} from '../../api/entityOptions'
import { useActionPermissions } from '../../composables/actionPermissions'
import type { ProvisionInstitution } from '../../api/platformIntegration'
import { useInstitutionPanelScope, useInstitutionPanelCommand } from './institutionPanelScope'
import { acceptUnhighlightedCreate } from '@skillforge/shared/admin/creatableOption'
const props = defineProps<{ tenantId?: string; embedded?: boolean }>()
const emit = defineEmits<{
  changed: []
  integrationSelected: [integrationId: string, tenantId?: string]
  provisioned: [value: { tenantId: string; integrationId?: string }]
}>()
const scope = useInstitutionPanelScope(() => props.tenantId ?? '')
const { allowed, checked } = useActionPermissions()
const tenantOptions = createOperationOptions('tenants', 'platform.institutions.read')
const associationOptions = createOperationOptions('tenants', 'platform.institutions.associate')
const page = ref(1)
const filters = reactive({ tenant_id: props.tenantId ?? '', namespace: '' })
const applied = ref({ ...filters })
const filtersChanged = computed(
  () =>
    filters.tenant_id !== applied.value.tenant_id ||
    filters.namespace.trim() !== applied.value.namespace
)
const list = useResource((signal) =>
  api.platformIntegration.institutions(
    { page: page.value, ...applied.value },
    { signal, silent: true }
  )
)
const planOptions = createProvisionPlanOptions()
const action = useTask()
const open = ref(false)
const formRef = ref<FormInstance>()
const mode = ref<'provision' | 'associate'>('provision')
const namespaces = useResource((signal) =>
  loadNamespaceOptions(`platform.institutions.${mode.value}`, [], signal)
)
const form = reactive<ProvisionInstitution & { tenant_id: string }>({
  tenant_id: '',
  namespace: '',
  external_institution_ref: '',
  name: '',
  default_plan_code: '',
  quota_api_calls_per_month: null,
  quota_max_knowledge_bases: null,
  quota_max_members: null,
  quota_max_channels: null,
  rate_limit_per_minute: null
})
function onNamespaceEnter(event: KeyboardEvent) {
  acceptUnhighlightedCreate(
    event,
    !!namespaces.data.value?.allow_create && !namespaces.loading.value && !namespaces.error.value,
    (namespaces.data.value?.items ?? []).map((item) => item.label),
    (value) => {
      form.namespace = value
    }
  )
}
const quotaFields = [
  ['quota_api_calls_per_month', '月 API 调用配额'],
  ['quota_max_knowledge_bases', '知识库配额'],
  ['quota_max_members', '成员配额'],
  ['quota_max_channels', '渠道配额'],
  ['rate_limit_per_minute', '每分钟请求上限']
] as const
const command = useInstitutionPanelCommand()
const uncertain = computed(() => !!command.pending.value)
const baseline = ref('')
const completed = ref<{ tenantId: string; integrationId: string }>()
const canSave = computed(() => allowed(`platform.institutions.${mode.value}`))
const close = useDrawerClose({
  open: () => open.value,
  busy: () => action.busy.value,
  dirty: () => JSON.stringify(form) !== baseline.value,
  close: () => {
    open.value = false
  }
})
/** 分页沿用已提交条件，不把未查询输入混入当前结果。 */
function query() {
  page.value = 1
  if (props.tenantId) filters.tenant_id = props.tenantId
  applied.value = { tenant_id: filters.tenant_id, namespace: filters.namespace.trim() }
  list.data.value = undefined
  void list.reload()
}
/** 读取权威映射核对未知结果，不用重新提交代替查询。 */
async function verify() {
  if (await list.reload()) {
    action.error.value = ''
    emit('changed')
  }
}
/** 开通与关联拥有不同动作，机构执行凭证另行显式签发。 */
function begin(value: 'provision' | 'associate') {
  if (!allowed(`platform.institutions.${value}`) || action.busy.value || uncertain.value) return
  mode.value = value
  Object.assign(form, {
    tenant_id: value === 'associate' ? (props.tenantId ?? applied.value.tenant_id) : '',
    namespace: applied.value.namespace,
    external_institution_ref: '',
    name: '',
    default_plan_code: '',
    quota_api_calls_per_month: null,
    quota_max_knowledge_bases: null,
    quota_max_members: null,
    quota_max_channels: null,
    rate_limit_per_minute: null
  })
  baseline.value = JSON.stringify(form)
  action.error.value = ''
  open.value = true
  void nextTick(() => formRef.value?.clearValidate())
  void namespaces.reload()
}
/** 创建快照只传本次命令字段；失败和修订冲突均保留用户输入。 */
function save() {
  if (!canSave.value || uncertain.value) return
  const requireTarget = scope.capture()
  void action.run(async () => {
    await formRef.value?.validate()
    requireTarget()
    const common = {
      namespace: form.namespace.trim(),
      external_institution_ref: form.external_institution_ref.trim()
    }
    if (namespaces.loading.value || !namespaces.data.value)
      throw Error('请等待命名空间授权选项读取成功')
    if (
      !namespaces.data.value.allow_create &&
      !namespaces.data.value.items.some((item) => item.id === common.namespace)
    )
      throw Error('请选择当前操作获准的命名空间')
    if (!common.namespace || !common.external_institution_ref)
      throw Error('请填写命名空间和外部机构标识')
    if (mode.value === 'provision' && (!form.name.trim() || !form.default_plan_code))
      throw Error('请选择套餐并填写机构名称')
    if (mode.value === 'associate' && !form.tenant_id.trim()) throw Error('请填写已有机构标识')
    const operation = mode.value
    const associationInput = { ...common, tenant_id: props.tenantId ?? form.tenant_id.trim() }
    const provisionInput: ProvisionInstitution = {
      ...common,
      name: form.name.trim(),
      default_plan_code: form.default_plan_code,
      ...Object.fromEntries(quotaFields.map(([key]) => [key, form[key] ?? null]))
    }
    await command.execute(
      (key) =>
        checked(`platform.institutions.${operation}`, () =>
          operation === 'associate'
            ? api.platformIntegration.associate(associationInput, key)
            : api.platformIntegration.provision(provisionInput, key)
        ),
      (result) => {
        baseline.value = JSON.stringify(form)
        open.value = false
        message.success(
          operation === 'provision' ? '机构已开通，执行凭证需单独签发' : '机构映射已关联'
        )
        completed.value = { tenantId: result.tenant_id, integrationId: result.integration_id }
        void list.reload()
      },
      requireTarget
    )
  })
}
function reconcile() {
  void action.run(() => command.reconcile())
}
useUnsavedChanges({
  dirty: () => open.value && JSON.stringify(form) !== baseline.value,
  busy: () => action.busy.value || uncertain.value
})
watch(
  () => props.tenantId,
  (value) => {
    list.invalidate()
    list.data.value = undefined
    completed.value = undefined
    filters.tenant_id = value ?? ''
    query()
  }
)
watch(
  action.busy,
  (busy) => {
    if (!busy && completed.value) {
      const value = completed.value
      completed.value = undefined
      emit('provisioned', value)
      emit('integrationSelected', value.integrationId)
      emit('changed')
    }
  },
  { flush: 'post' }
)
onMounted(() => {
  void list.reload()
})
defineExpose({ beginProvision: () => begin('provision'), beginAssociate: () => begin('associate') })
</script>
<template>
  <AdminPage
    :embedded="props.embedded"
    title="机构开通与映射"
    description="直接开通机构或关联已有机构，维护外部机构身份与接入域。"
  >
    <template #extra
      ><ElButton
        v-if="allowed('platform.institutions.provision')"
        type="primary"
        :disabled="action.busy.value || uncertain"
        @click="begin('provision')"
        >开通机构</ElButton
      ><ElButton
        v-if="allowed('platform.institutions.associate')"
        :disabled="action.busy.value || uncertain"
        @click="begin('associate')"
        >关联已有机构</ElButton
      ></template
    >
    <p v-if="filtersChanged" class="admin-field-help" role="status">
      筛选条件尚未应用，点击查询后更新结果；开通与关联沿用当前已查询范围。
    </p>
    <ElForm class="admin-filters" inline @submit.prevent="query" @keydown.capture="guardFormEnter"
      ><ElFormItem v-if="!props.tenantId" label="机构标识"
        ><EntitySelect
          v-model="filters.tenant_id"
          :load="tenantOptions.load"
          :resolve="tenantOptions.resolve"
          placeholder="搜索机构名称"
        /> </ElFormItem
      ><ElFormItem label="命名空间"><ElInput v-model="filters.namespace" clearable /></ElFormItem
      ><ElButton native-type="submit" :loading="list.loading.value">查询</ElButton
      ><ElButton
        @click="
          () => {
            filters.tenant_id = props.tenantId ?? ''
            filters.namespace = ''
            query()
          }
        "
        >重置</ElButton
      ></ElForm
    >
    <FeedbackNotice
      v-if="action.error.value || list.error.value"
      :title="action.error.value || list.error.value"
      type="error"
      :closable="false"
    />
    <ElAlert
      v-if="uncertain"
      title="开通或关联结果待核对，请核对原请求。刷新目录不会解除阻止。"
      type="warning"
      :closable="false"
    /><ElButton v-if="uncertain" :loading="action.busy.value" @click="reconcile"
      >核对原请求</ElButton
    >
    <ElButton v-if="list.error.value" @click="verify">刷新映射目录</ElButton>
    <ElTable
      :data="list.data.value?.items ?? []"
      v-loading="list.loading.value"
      row-key="mapping_id"
      :empty-text="
        list.loading.value
          ? '正在读取机构映射…'
          : list.error.value
            ? '机构映射尚未读取，请查看上方错误'
            : '当前已查询范围没有机构映射，可调整机构或命名空间后查询'
      "
      ><ElTableColumn prop="tenant_name" label="机构名称" min-width="160" /><ElTableColumn
        prop="tenant_id"
        label="机构标识"
        min-width="180"
        show-overflow-tooltip
      /><ElTableColumn
        prop="namespace"
        label="命名空间"
        min-width="160"
        show-overflow-tooltip
      /><ElTableColumn
        prop="external_institution_ref"
        label="外部机构标识"
        min-width="180"
        show-overflow-tooltip
      /><ElTableColumn
        prop="integration_id"
        label="接入标识"
        min-width="180"
        show-overflow-tooltip
      /><ElTableColumn prop="revision" label="修订" width="90" /><ElTableColumn
        label="执行凭证"
        width="130"
        ><template #default="{ row }"
          ><ElButton
            v-if="props.embedded && allowed('platform.institution_credentials.read')"
            link
            @click="emit('integrationSelected', row.integration_id, row.tenant_id)"
            >管理执行凭证</ElButton
          ><RouterLink
            v-else-if="allowed('platform.institution_credentials.read')"
            :to="{
              path: '/platform/institution-credentials',
              query: { tenant_id: row.tenant_id, integration_id: row.integration_id }
            }"
            >管理执行凭证</RouterLink
          ></template
        ></ElTableColumn
      ></ElTable
    >
    <ElPagination
      layout="total, prev, pager, next"
      :total="list.data.value?.total ?? 0"
      :current-page="page"
      :page-size="20"
      @current-change="
        (value: number) => {
          page = value
          void list.reload()
        }
      "
    />
    <ElDrawer
      v-model="open"
      :title="mode === 'provision' ? '开通机构' : '关联已有机构'"
      size="680px"
      append-to-body
      :before-close="close.requestClose"
      :close-on-click-modal="false"
      :close-on-press-escape="!action.busy.value"
      :show-close="!action.busy.value"
    >
      <FeedbackNotice
        v-if="action.error.value"
        :title="action.error.value"
        type="error"
        :closable="false"
      />
      <FeedbackNotice
        v-if="namespaces.error.value"
        :title="namespaces.error.value"
        type="error"
        :closable="false"
      />
      <ElButton v-if="namespaces.error.value" @click="namespaces.reload">重新读取命名空间</ElButton>
      <ElForm
        ref="formRef"
        :model="form"
        class="admin-form-grid"
        label-position="top"
        :disabled="action.busy.value || uncertain"
        @submit.prevent="save"
        @keydown.capture="guardFormEnter"
      >
        <ElFormItem
          v-if="mode === 'associate' && !props.tenantId"
          label="已有机构"
          prop="tenant_id"
          :rules="[{ required: true, message: '请选择已有机构' }]"
          ><EntitySelect
            v-model="form.tenant_id"
            :load="associationOptions.load"
            :resolve="associationOptions.resolve"
            placeholder="搜索机构名称"
          /> </ElFormItem
        ><ElFormItem
          label="命名空间"
          prop="namespace"
          :rules="[{ required: true, whitespace: true, message: '请选择命名空间' }]"
          ><ElSelect
            v-model="form.namespace"
            @keydown.enter.capture="onNamespaceEnter"
            v-described-by="'institution-provision-namespace-help'"
            aria-describedby="institution-provision-namespace-help"
            filterable
            :allow-create="namespaces.data.value?.allow_create ?? false"
            default-first-option
            :loading="namespaces.loading.value"
            :disabled="!namespaces.data.value || !!namespaces.error.value"
            placeholder="选择命名空间"
          >
            <ElOption
              v-for="option in namespaces.data.value?.items ?? []"
              :key="option.id"
              :value="option.id"
              :label="option.label"
            />
          </ElSelect>
          <p id="institution-provision-namespace-help" class="admin-field-help">
            {{
              namespaces.data.value?.allow_create
                ? '可输入新的命名空间，按回车选择或点击下方选项。'
                : '仅可选择当前操作明确获准的命名空间。'
            }}
          </p> </ElFormItem
        ><ElFormItem
          label="外部机构标识"
          prop="external_institution_ref"
          :rules="[{ required: true, whitespace: true, message: '请填写外部机构标识' }]"
          ><ElInput v-model="form.external_institution_ref"
        /></ElFormItem>
        <template v-if="mode === 'provision'"
          ><ElFormItem
            label="机构名称"
            prop="name"
            :rules="[{ required: true, whitespace: true, message: '请填写机构名称' }]"
            ><ElInput v-model="form.name" /></ElFormItem
          ><ElFormItem
            label="默认套餐"
            prop="default_plan_code"
            :rules="[{ required: true, message: '请选择启用套餐' }]"
            ><EntitySelect
              v-model="form.default_plan_code"
              :load="planOptions.load"
              :resolve="planOptions.resolve"
              placeholder="搜索并选择启用套餐"
          /></ElFormItem>
          <ElCollapse class="quota-overrides full-width">
            <ElCollapseItem name="quotas" title="自定义配额（可选，默认继承套餐）">
              <p>配额留空表示继承所选套餐；显式值由服务端校验，不会静默修正。</p>
              <ElFormItem v-for="[key, label] in quotaFields" :key="key" :label="label"
                ><ElInputNumber v-model="form[key]" :precision="0" :aria-label="label"
              /></ElFormItem>
            </ElCollapseItem>
          </ElCollapse>
        </template>
        <ElButton v-if="canSave" native-type="submit" type="primary" :loading="action.busy.value">{{
          mode === 'provision' ? '确认开通' : '确认关联'
        }}</ElButton
        ><ElButton :disabled="action.busy.value" @click="close.requestClose">取消</ElButton>
      </ElForm>
    </ElDrawer>
  </AdminPage>
</template>
