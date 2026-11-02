<script setup lang="ts">
import { acceptUnhighlightedCreate } from '@skillforge/shared/admin/creatableOption'
import FeedbackNotice from '@skillforge/shared/feedback/FeedbackNotice.vue'
import { vDescribedBy } from '@skillforge/shared/directives/describedBy'
import { computed, nextTick, onMounted, onScopeDispose, reactive, ref, watch } from 'vue'
import type { FormInstance } from 'element-plus'
import {
  AdminPage,
  statusLabel,
  guardFormEnter,
  useDrawerClose,
  useResource,
  useTask,
  type EntityOption
} from '@skillforge/shared/admin'
import { HttpClientError } from '@skillforge/shared/utils/http'
import { confirm, message } from '@skillforge/shared/feedback'
import { api } from '../../api'
import { createOperationOptions, loadNamespaceOptions } from '../../api/entityOptions'
import { useActionPermissions } from '../../composables/actionPermissions'
import type { IssuedManagementPrincipal, ManagementPrincipal } from '../../api/platformIntegration'

const { allowed, checked } = useActionPermissions()
const page = ref(1)
const list = useResource((signal) =>
  api.platformIntegration.managementPrincipals(page.value, { signal, silent: true })
)
const action = useTask()
const formRef = ref<FormInstance>()
const open = ref(false)
const issued = ref<IssuedManagementPrincipal>()
const uncertain = ref(false)
const form = reactive({
  name: '',
  scopes: [] as string[],
  tenants: [] as string[],
  definitions: [] as string[],
  namespaces: [] as string[]
})
const namespaces = useResource((signal) =>
  loadNamespaceOptions('platform.management_principals.create', [...form.scopes], signal)
)
function onNamespaceEnter(event: KeyboardEvent) {
  acceptUnhighlightedCreate(
    event,
    !!namespaces.data.value?.allow_create && !namespaces.loading.value && !namespaces.error.value,
    (namespaces.data.value?.items ?? []).map((item) => item.label),
    (value) => {
      if (!form.namespaces.includes(value)) form.namespaces.push(value)
    }
  )
}
watch(
  () => [open.value, form.scopes.join('|')],
  () => {
    if (open.value) void namespaces.reload()
  }
)
const baseline = ref('')
const close = useDrawerClose({
  open: () => open.value,
  busy: () => action.busy.value,
  dirty: () => JSON.stringify(form) !== baseline.value,
  close: () => {
    open.value = false
  }
})
const managementScopes = [
  'platform.institutions.read',
  'platform.institutions.provision',
  'platform.institutions.associate',
  'platform.institution_credentials.read',
  'platform.institution_credentials.issue',
  'platform.institution_credentials.rotate',
  'platform.institution_credentials.revoke',
  'platform.agent_definitions.read',
  'platform.agent_definitions.edit',
  'platform.agent_definitions.validate',
  'platform.agent_definitions.publish',
  'platform.agent_definitions.deprecate',
  'platform.agent_definitions.restore',
  'platform.agent_grants.read',
  'platform.agent_grants.create',
  'platform.agent_grants.narrow',
  'platform.agent_grants.revoke',
  'platform.agent_deployments.read',
  'platform.agent_deployments.create',
  'platform.agent_deployments.bind',
  'platform.agent_deployments.switch_authorization',
  'platform.usage.events.read',
  'platform.usage.summary.read',
  'platform.usage.details.read'
]
const canWrite = computed(() => allowed('platform.management_principals.create'))
const tenantOptions = createOperationOptions('tenants', 'platform.management_principals.create')
const definitionOptions = createOperationOptions(
  'definitions',
  'platform.management_principals.create'
)
const tenantChoices = ref<EntityOption[]>([])
const definitionChoices = ref<EntityOption[]>([])
const targetLoading = ref(false)
const targetError = ref('')
let targetController: AbortController | undefined
let targetGeneration = 0
/** 只读取本次管理身份签发可授予的目标，不依赖宽泛目录权限。 */
async function searchTargets(query = '') {
  const generation = ++targetGeneration
  targetController?.abort()
  targetController = new AbortController()
  targetLoading.value = true
  targetError.value = ''
  try {
    const signal = targetController.signal
    const [tenants, definitions] = await Promise.all([
      tenantOptions.load(query, signal),
      definitionOptions.load(query, signal)
    ])
    if (generation !== targetGeneration || !open.value) return
    tenantChoices.value = [
      ...tenantChoices.value.filter(
        (option) =>
          form.tenants.includes(option.id) && !tenants.some((item) => item.id === option.id)
      ),
      ...tenants
    ]
    definitionChoices.value = [
      ...definitionChoices.value.filter(
        (option) =>
          form.definitions.includes(option.id) && !definitions.some((item) => item.id === option.id)
      ),
      ...definitions
    ]
  } catch (reason) {
    if (reason instanceof HttpClientError) reason.claimFeedback()
    if (generation === targetGeneration && open.value) {
      targetError.value = reason instanceof Error ? reason.message : '目标目录读取失败'
    }
  } finally {
    if (generation === targetGeneration) targetLoading.value = false
  }
}
onScopeDispose(() => {
  targetGeneration++
  targetController?.abort()
})
/** 新建表单和一次交付秘密均只存当前页面内存。 */
function begin() {
  if (!canWrite.value || action.busy.value || uncertain.value) return
  Object.assign(form, {
    name: '',
    scopes: [] as string[],
    tenants: [] as string[],
    definitions: [] as string[],
    namespaces: [] as string[]
  })
  baseline.value = JSON.stringify(form)
  action.error.value = ''
  open.value = true
  void nextTick(() => formRef.value?.clearValidate())
  searchTargets()
}
/** 结果未知时禁止新写，先通过安全目录核对，秘密不会以重读方式恢复。 */
async function write<T>(operation: () => Promise<T>) {
  try {
    return await operation()
  } catch (reason) {
    if (reason instanceof HttpClientError && (reason.status === 0 || reason.status >= 500))
      uncertain.value = true
    throw reason
  }
}
/** 安全目录核对成功后允许显式轮换；不重新签发未知的首次秘密。 */
async function verify() {
  if (await list.reload()) {
    uncertain.value = false
    action.error.value = ''
  }
}
/** 平台人员显式声明动作和目标范围，确认后再次校验当前授权。 */
function save() {
  if (!canWrite.value || uncertain.value) return
  void action.run(async () => {
    await formRef.value?.validate()
    const input = {
      name: form.name.trim(),
      scopes: [...form.scopes],
      target_tenant_ids: [...form.tenants],
      target_definition_ids: [...form.definitions],
      target_namespaces: [...new Set(form.namespaces.map((value) => value.trim()).filter(Boolean))]
    }
    if (input.target_namespaces.length) {
      if (namespaces.loading.value || !namespaces.data.value || namespaces.error.value)
        throw Error('请先读取当前管理动作的命名空间授权')
      if (
        !namespaces.data.value.allow_create &&
        input.target_namespaces.some(
          (value) => !namespaces.data.value!.items.some((option) => option.id === value)
        )
      )
        throw Error('所选命名空间不在当前管理动作共同授予范围，请重新选择')
    }
    if (
      !input.target_tenant_ids.length &&
      !input.target_definition_ids.length &&
      !input.target_namespaces.length
    )
      throw Error('请至少声明一项目标范围')
    if (!input.name || !input.scopes.length) throw Error('请填写名称和至少一项管理动作')
    issued.value = await write(() =>
      checked('platform.management_principals.create', () =>
        api.platformIntegration.createManagementPrincipal(input, crypto.randomUUID())
      )
    )
    baseline.value = JSON.stringify(form)
    open.value = false
    message.success('管理身份已创建')
    void list.reload()
  })
}
/** 轮换和撤销绑定列表所见精确修订；冲突保留目录供管理员主动刷新。 */
function change(id: ManagementPrincipal['principal_id'], operation: 'rotate' | 'revoke') {
  const row = list.data.value?.items.find((item) => item.principal_id === id)
  if (!row) return
  const permission = `platform.management_principals.${operation}`
  if (!allowed(permission) || uncertain.value) return
  void action.run(async () => {
    if (
      !(await confirm({
        title: operation === 'rotate' ? '轮换管理凭证' : '撤销管理身份',
        content:
          operation === 'rotate'
            ? `轮换“${row.name}”的管理凭证？旧凭证立即失效，新秘密仅在本次成功响应中交付，请安排调用方更新。`
            : `撤销管理身份“${row.name}”？该身份的旧凭证立即失效，调用方将无法继续使用。`,
        confirmText: operation === 'rotate' ? '轮换' : '撤销',
        cancelText: '取消',
        danger: true
      }))
    )
      return
    issued.value = undefined
    const input = { principal_id: row.principal_id, expected_revision: row.revision }
    const result = await write(() =>
      checked(permission, () =>
        operation === 'rotate'
          ? api.platformIntegration.rotateManagementPrincipal(input, crypto.randomUUID())
          : api.platformIntegration.revokeManagementPrincipal(input, crypto.randomUUID())
      )
    )
    if (operation === 'rotate') issued.value = result
    message.success(operation === 'rotate' ? '管理凭证已轮换' : '管理身份已撤销')
    void list.reload()
  })
}
/** 当前身份失效和页面离开时立即丢弃一次秘密。 */
function clearSecret() {
  issued.value = undefined
}
watch(
  [
    () => allowed('platform.management_principals.create'),
    () => allowed('platform.management_principals.rotate')
  ],
  clearSecret
)
onScopeDispose(clearSecret)
onMounted(() => {
  void list.reload()
})
</script>

<template>
  <AdminPage
    title="平台管理身份"
    description="为受限平台管理动作签发管理机器身份，显式限制目标机构、定义和命名空间。"
  >
    <template #extra
      ><ElButton
        v-if="canWrite"
        type="primary"
        :disabled="action.busy.value || uncertain"
        @click="begin"
        >创建管理身份</ElButton
      ></template
    >
    <FeedbackNotice
      v-if="list.error.value || action.error.value"
      :title="action.error.value || list.error.value"
      type="error"
      :closable="false"
    />
    <ElAlert
      v-if="uncertain"
      title="写入结果待核对。请先刷新安全目录；首次秘密不能回读，需要时显式轮换。"
      type="warning"
      :closable="false"
    />
    <ElButton :loading="list.loading.value" @click="verify">刷新安全目录</ElButton>
    <ElTable
      :data="list.data.value?.items ?? []"
      v-loading="list.loading.value"
      row-key="principal_id"
      :empty-text="
        list.loading.value
          ? '正在读取管理身份…'
          : list.error.value
            ? '管理身份目录尚未读取，请查看上方错误'
            : '当前可见范围内没有管理身份'
      "
    >
      <ElTableColumn prop="name" label="名称" min-width="160" show-overflow-tooltip /><ElTableColumn
        prop="principal_id"
        label="身份标识"
        min-width="180"
        show-overflow-tooltip
      />
      <ElTableColumn prop="status" label="状态" width="110"
        ><template #default="{ row }">{{ statusLabel(row.status) }}</template></ElTableColumn
      ><ElTableColumn prop="revision" label="修订" width="90" /><ElTableColumn
        prop="credential_version"
        label="凭证版本"
        width="110"
      />
      <ElTableColumn label="管理动作" min-width="220"
        ><template #default="{ row }">{{ row.scopes.join('、') }}</template></ElTableColumn
      >
      <ElTableColumn label="目标范围" min-width="240"
        ><template #default="{ row }"
          ><p>机构：{{ row.target_tenant_ids.join('、') || '未授予' }}</p>
          <p>定义：{{ row.target_definition_ids.join('、') || '未授予' }}</p>
          <p>命名空间：{{ row.target_namespaces.join('、') || '未授予' }}</p></template
        ></ElTableColumn
      >
      <ElTableColumn label="操作" width="150" fixed="right"
        ><template #default="{ row }"
          ><ElButton
            v-if="allowed('platform.management_principals.rotate')"
            link
            :disabled="action.busy.value || uncertain || row.status !== 'active'"
            @click="change(row.principal_id, 'rotate')"
            >轮换</ElButton
          ><ElButton
            v-if="allowed('platform.management_principals.revoke')"
            link
            type="danger"
            :disabled="action.busy.value || uncertain || row.status !== 'active'"
            @click="change(row.principal_id, 'revoke')"
            >撤销</ElButton
          ></template
        ></ElTableColumn
      >
    </ElTable>
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
      title="创建管理身份"
      size="680px"
      :before-close="close.requestClose"
      :close-on-click-modal="false"
      :close-on-press-escape="!action.busy.value"
      :show-close="!action.busy.value"
      append-to-body
    >
      <FeedbackNotice
        v-if="action.error.value"
        :title="action.error.value"
        type="error"
        :closable="false"
      />
      <ElForm
        ref="formRef"
        :model="form"
        label-position="top"
        :disabled="action.busy.value || uncertain"
        @submit.prevent="save"
        @keydown.capture="guardFormEnter"
      >
        <ElFormItem
          label="名称"
          prop="name"
          :rules="[{ required: true, whitespace: true, message: '请填写管理身份名称' }]"
          ><ElInput v-model="form.name"
        /></ElFormItem>
        <ElFormItem
          label="管理动作"
          prop="scopes"
          :rules="[{ type: 'array', required: true, min: 1, message: '请至少选择一项管理动作' }]"
          ><ElSelect
            v-model="form.scopes"
            multiple
            filterable
            aria-describedby="management-scope-help"
            ><ElOption
              v-for="scope in managementScopes.filter(allowed)"
              :key="scope"
              :value="scope"
              :label="scope" /></ElSelect
        ></ElFormItem>
        <p id="management-scope-help">
          只显示当前账号可授予的精确管理动作。按名称选择有权管理的机构和定义；命名空间输入后按回车添加。留空表示无该类目标，至少声明一项。
        </p>
        <FeedbackNotice v-if="targetError" :title="targetError" type="error" :closable="false" />
        <ElButton v-if="targetError" link @click="searchTargets()">重新读取目标选项</ElButton>
        <ElFormItem label="目标机构标识">
          <ElSelect
            v-model="form.tenants"
            multiple
            filterable
            remote
            :remote-method="searchTargets"
            :loading="targetLoading"
            placeholder="搜索机构名称并选择"
          >
            <ElOption
              v-for="option in tenantChoices"
              :key="option.id"
              :value="option.id"
              :label="option.label"
              ><span>{{ option.label }}</span>
              <small class="admin-muted">{{ option.description }}</small></ElOption
            >
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="目标定义标识">
          <ElSelect
            v-model="form.definitions"
            multiple
            filterable
            remote
            :remote-method="searchTargets"
            :loading="targetLoading"
            placeholder="搜索定义名称并选择"
          >
            <ElOption
              v-for="option in definitionChoices"
              :key="option.id"
              :value="option.id"
              :label="option.label"
              ><span>{{ option.label }}</span>
              <small class="admin-muted">{{ option.description }}</small></ElOption
            >
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="目标命名空间">
          <ElSelect
            v-model="form.namespaces"
            @keydown.enter.capture="onNamespaceEnter"
            v-described-by="'management-namespace-target-help'"
            aria-describedby="management-namespace-target-help"
            multiple
            filterable
            :allow-create="namespaces.data.value?.allow_create ?? false"
            :loading="namespaces.loading.value"
            :disabled="namespaces.loading.value || !!namespaces.error.value"
            default-first-option
            :placeholder="
              namespaces.data.value?.allow_create
                ? '输入命名空间并按回车添加'
                : '选择当前动作共同授予的命名空间'
            "
            ><ElOption
              v-for="option in namespaces.data.value?.items ?? []"
              :key="option.id"
              :value="option.id"
              :label="option.label"
          /></ElSelect>
          <p id="management-namespace-target-help" class="admin-field-help">
            {{
              namespaces.data.value?.allow_create
                ? '当前数据范围允许新建命名空间，输入后按回车添加。'
                : '仅可选择所有已选开通或关联动作共同授予的命名空间。未选择这些动作时无可授予值。'
            }}
          </p>
          <FeedbackNotice
            v-if="namespaces.error.value"
            :title="namespaces.error.value"
            type="error"
            :closable="false"
          /><ElButton v-if="namespaces.error.value" link @click="namespaces.reload"
            >重新读取命名空间</ElButton
          >
        </ElFormItem>
        <ElButton native-type="submit" type="primary" :loading="action.busy.value"
          >签发管理身份</ElButton
        ><ElButton :disabled="action.busy.value" @click="close.requestClose">取消</ElButton>
      </ElForm>
    </ElDrawer>
    <ElDialog
      :model-value="!!issued"
      title="一次性交付管理凭证"
      :close-on-click-modal="false"
      @update:model-value="clearSecret"
      @closed="clearSecret"
    >
      <ElAlert
        title="关闭后立即清除秘密。安全目录和幂等重读不会再次返回秘密。"
        type="warning"
        :closable="false"
      />
      <template v-if="issued?.management_key && issued.signing_secret"
        ><ElForm label-position="top"
          ><ElFormItem label="Management Key"
            ><ElInput
              :model-value="issued.management_key"
              readonly
              type="password"
              show-password
              autocomplete="off" /></ElFormItem
          ><ElFormItem label="签名秘密"
            ><ElInput
              :model-value="issued.signing_secret"
              readonly
              type="password"
              show-password
              autocomplete="off" /></ElFormItem></ElForm
      ></template>
      <p v-else>已读取安全回执，本次没有可再次交付的秘密；需要新秘密时请显式轮换。</p>
      <template #footer><ElButton @click="clearSecret">已安全保存并关闭</ElButton></template>
    </ElDialog>
  </AdminPage>
</template>
