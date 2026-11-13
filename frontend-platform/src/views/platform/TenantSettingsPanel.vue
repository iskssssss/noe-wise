<script setup lang="ts">
import FeedbackNotice from '@skillforge/shared/feedback/FeedbackNotice.vue'
import { vDescribedBy } from '@skillforge/shared/directives/describedBy'

import FieldTip from '@skillforge/shared/components/FieldTip.vue'

import { guardFormEnter } from '@skillforge/shared/admin'
import { message, confirm } from '@skillforge/shared/feedback'
import { computed, nextTick, onScopeDispose, reactive, ref, watch, useId } from 'vue'
import { type FormInstance } from 'element-plus'
import {
  AdminPage,
  statusLabel,
  useDrawerClose,
  useTask,
  useUnsavedChanges,
  useResource
} from '@skillforge/shared/admin'
import { api, type TenantDetail, type TenantMemberItem } from '../../api'
import { useAuthStore } from '../../stores/auth'
import { useActionPermissions } from '../../composables/actionPermissions'
import { accountPasswordError, accountPasswordRule } from '@skillforge/shared/utils/accountPassword'
import { useInstitutionPanelScope } from './institutionPanelScope'
const props = defineProps<{ tenantId?: string; embedded?: boolean }>()
const emit = defineEmits<{ changed: [] }>()
const scope = useInstitutionPanelScope(() => props.tenantId ?? '')
const auth = useAuthStore()
const { allowed, checked } = useActionPermissions()
const canCreate = computed(() => allowed('foundation.tenants.create'))
const canUpdate = computed(() => allowed('foundation.tenants.update'))
const tenantFormRef = ref<FormInstance>()
const memberFormRef = ref<FormInstance>()
const passwordFormRef = ref<FormInstance>()
const passwordForm = reactive({ password: '' })
const quotas = [
  ['override_quota_api_calls_per_month', '每月 API 调用数'],
  ['override_rate_limit_per_minute', '每分钟请求数']
] as const
type TenantQuotaKey = (typeof quotas)[number][0]
type TenantForm = { name: string; plan_code: string; deployment_mode: string } & Pick<
  TenantDetail,
  TenantQuotaKey
>
const id = computed(() => props.tenantId ?? ''),
  action = useTask(),
  tenant = ref<TenantDetail>(),
  form = reactive<TenantForm>({ name: '', plan_code: '', deployment_mode: '' }),
  baseline = ref(''),
  page = ref(1),
  memberOpen = ref(false),
  editing = ref<TenantMemberItem>(),
  passwordOpen = ref(false)
const newPassword = computed({
  get: () => passwordForm.password,
  set: (value: string) => {
    passwordForm.password = value
  }
})
const plans = ref<Array<{ plan_code: string; display_name: string }>>([])
const planLoad = useTask()
const memberBaseline = ref('')
const member = reactive({
  username: '',
  password: '',
  roles: ['developer'] as string[],
  email: '',
  status: 'active'
})
// 已有自定义角色必须保留为可见选项，编辑其他字段不能隐式删掉原角色。
const roleOptions = computed(() => [
  ...new Set(['tenant_admin', 'developer', 'auditor', ...(editing.value?.roles ?? [])])
])
const dirty = computed(() => !!tenant.value && JSON.stringify(form) !== baseline.value)
useUnsavedChanges({
  dirty: () =>
    dirty.value ||
    (memberOpen.value && JSON.stringify(member) !== memberBaseline.value) ||
    (passwordOpen.value && !!newPassword.value),
  busy: () => action.busy.value
})
function accept(value: TenantDetail) {
  tenant.value = value
  Object.assign(form, {
    name: value.name,
    plan_code: value.plan_code ?? '',
    deployment_mode: value.deployment_mode ?? '',
    ...Object.fromEntries(quotas.map(([key]) => [key, value[key] ?? null]))
  })
  baseline.value = JSON.stringify(form)
}
const membersLoad = useResource((signal) =>
  api.admin.listMembers(id.value, { page: page.value, limit: 20 }, { signal })
)
const members = computed(() => membersLoad.data.value?.items ?? []),
  total = computed(() => membersLoad.data.value?.total ?? 0)
async function loadMembers() {
  return await membersLoad.reload()
}
async function changeMemberPage(nextPage: number) {
  if (membersLoad.loading.value || action.busy.value || nextPage === page.value) return
  const previous = page.value
  const target = id.value
  page.value = nextPage
  if ((await loadMembers()) === undefined && id.value === target) page.value = previous
}
const load = useResource((signal) => api.admin.getTenant(id.value, { signal }))
async function reload() {
  if (!id.value) return
  const value = await load.reload()
  if (value) {
    accept(value)
    await loadMembers()
  }
  reloadPlans()
}
function reloadPlans() {
  void planLoad.run(async () => {
    plans.value = await api.admin.plans()
  })
}
async function checkedTarget<T>(
  requireTarget: () => void,
  permission: string,
  operation: () => Promise<T>
) {
  requireTarget()
  const result = await checked(permission, operation)
  requireTarget()
  return result
}
function save() {
  if (!canUpdate.value) return
  const requireTarget = scope.capture()
  void action.run(async () => {
    requireTarget()
    await tenantFormRef.value?.validate()
    if (!canUpdate.value) return
    if (!form.name?.trim()) throw new Error('机构名称不能为空')
    accept(
      await checkedTarget(requireTarget, 'foundation.tenants.update', () =>
        api.admin.updateTenant(id.value, {
          ...form,
          reset_quota_overrides: quotas
            .filter(([key]) => form[key] === null || form[key] === undefined)
            .map(([key]) => key)
        })
      )
    )
    requireTarget()
    emit('changed')
    message.success('机构配置已保存')
  })
}
function toggle() {
  if (!canUpdate.value) return
  const requireTarget = scope.capture()
  void action.run(async () => {
    requireTarget()
    if (
      !(await confirm({
        title: '机构状态',
        content:
          tenant.value?.status === 'active'
            ? '暂停后机构将无法正常使用服务。确认暂停？'
            : '确认恢复该机构？',
        confirmText: tenant.value?.status === 'active' ? '暂停机构' : '恢复机构',
        cancelText: '取消',
        danger: true
      }))
    )
      return
    if (!canUpdate.value) return
    requireTarget()
    const result =
      tenant.value?.status === 'active'
        ? await checkedTarget(requireTarget, 'foundation.tenants.update', () =>
            api.admin.suspendTenant(id.value)
          )
        : await checkedTarget(requireTarget, 'foundation.tenants.update', () =>
            api.admin.activateTenant(id.value)
          )
    if (dirty.value) tenant.value = result
    else accept(result)
    requireTarget()
    emit('changed')
    message.success(dirty.value ? '机构状态已更新，未保存的机构配置仍保留' : '机构状态已更新')
  })
}
function editMember(value?: TenantMemberItem) {
  if (action.busy.value || !(value ? canUpdate.value : canCreate.value)) return
  action.error.value = ''
  editing.value = value
  Object.assign(member, {
    username: value?.username ?? '',
    password: '',
    roles: [...(value?.roles ?? ['developer'])],
    email: value?.email ?? '',
    status: value?.status ?? 'active'
  })
  memberBaseline.value = JSON.stringify(member)
  memberOpen.value = true
  void nextTick(() => memberFormRef.value?.clearValidate())
}
function saveMember() {
  if (!(editing.value ? canUpdate.value : canCreate.value)) return
  const requireTarget = scope.capture()
  void action.run(async () => {
    requireTarget()
    await memberFormRef.value?.validate()
    if (!(editing.value ? canUpdate.value : canCreate.value)) return
    const target = editing.value
    if (target)
      await checkedTarget(requireTarget, 'foundation.tenants.update', () =>
        api.admin.updateMember(id.value, target.id, {
          roles: member.roles,
          email: member.email,
          status: member.status
        })
      )
    else {
      if (!member.username.trim()) throw new Error('请输入账号')
      const passwordError = accountPasswordError(member.password)
      if (passwordError) throw new Error(passwordError)
      await checkedTarget(requireTarget, 'foundation.tenants.create', () =>
        api.admin.createMember(id.value, { ...member })
      )
    }
    memberOpen.value = false
    member.password = ''
    requireTarget()
    emit('changed')
    message.success(editing.value ? '成员已更新' : '成员已创建')
    void loadMembers()
  })
}
function resetPassword() {
  if (!canUpdate.value) return
  const requireTarget = scope.capture()
  void action.run(async () => {
    requireTarget()
    const passwordError = accountPasswordError(newPassword.value)
    if (passwordError) {
      void passwordFormRef.value?.validate().catch(() => undefined)
      throw new Error(passwordError)
    }
    await passwordFormRef.value?.validate()
    await checkedTarget(requireTarget, 'foundation.tenants.update', () =>
      api.admin.resetPassword(id.value, editing.value!.id, newPassword.value)
    )
    passwordOpen.value = false
    newPassword.value = ''
    requireTarget()
    emit('changed')
    message.success(`“${editing.value!.username}”的密码已重置，请通过安全渠道交付新密码`)
  })
}
function editPassword(value: TenantMemberItem) {
  if (!canUpdate.value || action.busy.value) return
  editing.value = value
  action.error.value = ''
  newPassword.value = ''
  passwordOpen.value = true
  void nextTick(() => passwordFormRef.value?.clearValidate())
}
watch(
  () => [auth.identityVersion, auth.user?.permissions.join('|')],
  () => {
    memberOpen.value = false
    passwordOpen.value = false
    member.password = ''
    newPassword.value = ''
  }
)
watch(
  () => props.tenantId,
  () => {
    load.invalidate()
    membersLoad.invalidate()
    load.data.value = undefined
    membersLoad.data.value = undefined
    tenant.value = undefined
    memberOpen.value = false
    passwordOpen.value = false
    member.password = ''
    newPassword.value = ''
    page.value = 1
    baseline.value = ''
    void reload()
  },
  { immediate: true }
)
onScopeDispose(() => {
  member.password = ''
  newPassword.value = ''
})

const { requestClose: memberOpenClose } = useDrawerClose({
  open: () => memberOpen.value,
  busy: () => action.busy.value,
  dirty: () => JSON.stringify(member) !== memberBaseline.value,
  close: () => {
    memberOpen.value = false
    member.password = ''
  }
})
const { requestClose: passwordOpenClose } = useDrawerClose({
  open: () => passwordOpen.value,
  busy: () => action.busy.value,
  dirty: () => !!newPassword.value,
  close: () => {
    passwordOpen.value = false
    newPassword.value = ''
  }
})

const formTipId = useId()
</script>

<template>
  <AdminPage
    :embedded="props.embedded"
    :title="props.embedded ? '机构设置' : (tenant?.name ?? '机构设置')"
    description="机构配置、配额与账号管理"
  >
    <template #extra>
      <RouterLink v-if="!props.embedded" to="/platform/tenants">
        <ElButton>返回列表</ElButton>
      </RouterLink>
      <ElButton
        v-if="tenant"
        v-permission="['foundation.tenants.update']"
        :disabled="action.busy.value"
        @click="toggle"
        >{{ tenant.status === 'active' ? '暂停机构' : '恢复机构' }}</ElButton
      >
    </template>
    <FeedbackNotice
      v-if="load.error.value || action.error.value"
      :title="load.error.value || action.error.value"
      :closable="false"
      type="error"
    />
    <ElButton v-if="load.error.value && !tenant" :loading="load.loading.value" @click="reload"
      >重新读取机构</ElButton
    >
    <ElSkeleton v-if="load.loading.value" :rows="6" />
    <template v-else-if="tenant">
      <ElCard class="admin-card">
        <ElForm
          @keydown.capture="guardFormEnter"
          ref="tenantFormRef"
          :model="form"
          label-position="top"
          :disabled="!canUpdate || action.busy.value"
          @submit.prevent="save"
        >
          <div class="admin-form-grid">
            <ElFormItem
              label="机构名称"
              prop="name"
              :rules="[
                { required: true, whitespace: true, message: '请输入机构名称', trigger: 'blur' }
              ]"
            >
              <ElInput
                v-described-by="formTipId + '-field-0'"
                :aria-describedby="formTipId + '-field-0'"
                v-model="form.name"
              />

              <FieldTip :id="formTipId + '-field-0'">填写机构的展示名称。</FieldTip>
            </ElFormItem>
            <ElFormItem label="机构标识">
              <ElInput
                v-described-by="formTipId + '-field-1'"
                :aria-describedby="formTipId + '-field-1'"
                :model-value="tenant.slug"
                disabled
              />

              <FieldTip :id="formTipId + '-field-1'"
                >机构登录和隔离使用的稳定标识，此处只读。</FieldTip
              >
            </ElFormItem>
            <ElFormItem label="套餐">
              <ElSelect
                v-described-by="formTipId + '-field-2'"
                :aria-describedby="formTipId + '-field-2'"
                v-model="form.plan_code"
                :loading="planLoad.busy.value"
                filterable
                ><ElOption
                  v-for="plan in plans"
                  :key="plan.plan_code"
                  :value="plan.plan_code"
                  :label="plan.display_name"
              /></ElSelect>
              <FeedbackNotice
                v-if="planLoad.error.value"
                :title="planLoad.error.value"
                type="error"
                :closable="false"
              />
              <ElButton
                v-if="planLoad.error.value"
                link
                :loading="planLoad.busy.value"
                @click="reloadPlans"
                >重新读取套餐</ElButton
              >

              <FieldTip :id="formTipId + '-field-2'"
                >选择机构套餐；具体额度可在下方按项目覆盖。</FieldTip
              >
            </ElFormItem>
            <ElFormItem label="部署模式">
              <ElSelect
                v-described-by="formTipId + '-field-3'"
                :aria-describedby="formTipId + '-field-3'"
                v-model="form.deployment_mode"
                ><ElOption value="cloud" label="云端部署" /><ElOption
                  value="hybrid"
                  label="混合部署" /><ElOption value="on_premise" label="私有部署"
              /></ElSelect>

              <FieldTip :id="formTipId + '-field-3'"
                >选择机构部署模式，应与实际部署安排一致。</FieldTip
              >
            </ElFormItem>
            <ElFormItem v-for="[key, label] in quotas" :key="key" :label="label">
              <ElSelect
                v-described-by="formTipId + '-field-4' + '-' + encodeURIComponent(String(key))"
                :aria-describedby="formTipId + '-field-4' + '-' + encodeURIComponent(String(key))"
                :model-value="form[key] === null || form[key] === undefined ? 'default' : 'custom'"
                @change="form[key] = $event === 'default' ? null : 0"
                ><ElOption value="default" label="使用套餐默认" /><ElOption
                  value="custom"
                  label="自定义"
              /></ElSelect>
              <ElInputNumber
                v-described-by="formTipId + '-field-4' + '-' + encodeURIComponent(String(key))"
                :aria-describedby="formTipId + '-field-4' + '-' + encodeURIComponent(String(key))"
                v-if="form[key] != null"
                v-model="form[key]"
                :min="0"
                :precision="0"
              />

              <FieldTip :id="formTipId + '-field-4' + '-' + encodeURIComponent(String(key))"
                >选择继承套餐额度或填写自定义非负整数；0 表示该项额度为零。</FieldTip
              >
            </ElFormItem>
          </div>
          <ElButton
            v-permission="['foundation.tenants.update']"
            native-type="submit"
            type="primary"
            :loading="action.busy.value"
            :disabled="!dirty"
            >保存机构配置</ElButton
          >
        </ElForm>
      </ElCard>
      <ElCard class="admin-card">
        <div class="admin-actions">
          <h2>机构成员</h2>
          <ElButton
            :loading="membersLoad.loading.value"
            :disabled="action.busy.value"
            @click="loadMembers"
            >刷新成员</ElButton
          >
          <ElButton
            v-permission="['foundation.tenants.create']"
            type="primary"
            :disabled="action.busy.value"
            @click="editMember()"
            >新增成员</ElButton
          >
        </div>
        <FeedbackNotice
          v-if="membersLoad.error.value"
          :title="membersLoad.error.value"
          type="error"
          :closable="false"
        />
        <ElTable
          table-layout="auto"
          :empty-text="
            membersLoad.loading.value
              ? '正在读取机构成员…'
              : membersLoad.error.value
                ? '成员列表尚未读取，请查看上方错误'
                : '该机构当前没有成员记录'
          "
          v-loading="membersLoad.loading.value"
          :data="members"
          style="width: 100%"
        >
          <ElTableColumn label="成员" min-width="220">
            <template #default="{ row }">
              <div>{{ row.username }}</div>
              <div class="admin-field-help">{{ row.email || '未登记邮箱' }}</div>
              <div class="admin-field-help">状态：{{ statusLabel(row.status) }}</div>
            </template>
          </ElTableColumn>
          <ElTableColumn prop="roles" label="角色" width="120"
            ><template #default="{ row }">{{
              row.roles.map((role: string) => statusLabel(role)).join('、')
            }}</template></ElTableColumn
          >
          <ElTableColumn
            prop="actions"
            label="操作"
            width="180"
            fixed="right"
            class-name="admin-table-actions"
          >
            <template #default="{ row }">
              <ElButton
                v-permission="['foundation.tenants.update']"
                link
                type="primary"
                :disabled="action.busy.value"
                @click="editMember(row as TenantMemberItem)"
                >编辑</ElButton
              >
              <ElButton
                v-permission="['foundation.tenants.update']"
                link
                :disabled="action.busy.value"
                @click="editPassword(row as TenantMemberItem)"
                >重置密码</ElButton
              >
            </template>
          </ElTableColumn>
        </ElTable>
        <ElPagination
          class="admin-actions"
          layout="total, prev, pager, next"
          :current-page="page"
          :page-size="20"
          :total="total"
          :disabled="membersLoad.loading.value || action.busy.value"
          @current-change="changeMemberPage"
        />
      </ElCard>
    </template>
    <ElDrawer
      class="admin-drawer"
      direction="rtl"
      append-to-body
      v-model="memberOpen"
      :title="editing ? '编辑成员：' + editing.username : '新增成员'"
      :before-close="memberOpenClose"
      size="680px"
      :close-on-click-modal="false"
    >
      <FeedbackNotice
        v-if="action.error.value"
        :title="action.error.value"
        :closable="false"
        type="error"
      />
      <ElForm
        @keydown.capture="guardFormEnter"
        ref="memberFormRef"
        :model="member"
        id="member-form"
        @submit.prevent="saveMember"
        label-position="top"
        :disabled="!(editing ? canUpdate : canCreate) || action.busy.value"
      >
        <ElFormItem
          label="账号"
          prop="username"
          :rules="[{ required: true, whitespace: true, message: '请输入账号' }]"
        >
          <ElInput
            v-described-by="'member-username-help'"
            aria-describedby="member-username-help"
            v-model="member.username"
            :disabled="!!editing"
          />
          <p id="member-username-help" class="admin-field-help">
            账号在机构内唯一，创建后不可修改。
          </p>
        </ElFormItem>
        <ElFormItem v-if="!editing" label="初始密码" prop="password" :rules="[accountPasswordRule]">
          <ElInput
            v-described-by="formTipId + '-field-6'"
            :aria-describedby="formTipId + '-field-6'"
            v-model="member.password"
            type="password"
            show-password
          />

          <FieldTip :id="formTipId + '-field-6'"
            >至少 12 个字符，包含英文字母和数字，UTF-8 最多 72 字节。</FieldTip
          >
        </ElFormItem>
        <ElFormItem label="角色">
          <ElSelect
            v-described-by="formTipId + '-field-7'"
            :aria-describedby="formTipId + '-field-7'"
            v-model="member.roles"
            multiple
          >
            <ElOption
              v-for="role in roleOptions"
              :key="role"
              :value="role"
              :label="statusLabel(role)"
            />
          </ElSelect>

          <FieldTip :id="formTipId + '-field-7'"
            >选择成员在机构后台中的角色，决定可执行的管理操作。</FieldTip
          >
        </ElFormItem>
        <ElFormItem
          label="邮箱"
          prop="email"
          :rules="[{ type: 'email', message: '请输入有效邮箱', trigger: 'blur' }]"
        >
          <ElInput
            v-described-by="formTipId + '-field-8'"
            :aria-describedby="formTipId + '-field-8'"
            v-model="member.email"
            type="email"
          />

          <FieldTip :id="formTipId + '-field-8'">填写成员联系邮箱，格式应为有效邮箱地址。</FieldTip>
        </ElFormItem>
        <ElFormItem v-if="editing" label="状态">
          <ElSelect
            v-described-by="formTipId + '-field-9'"
            :aria-describedby="formTipId + '-field-9'"
            v-model="member.status"
          >
            <ElOption value="active" label="正常" />
            <ElOption value="disabled" label="禁用" />
          </ElSelect>

          <FieldTip :id="formTipId + '-field-9'">选择成员启用或停用状态。</FieldTip>
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton :disabled="action.busy.value" @click="memberOpenClose()">取消</ElButton>
        <ElButton
          v-permission="editing ? ['foundation.tenants.update'] : ['foundation.tenants.create']"
          type="primary"
          :loading="action.busy.value"
          :disabled="!!editing && JSON.stringify(member) === memberBaseline"
          native-type="submit"
          form="member-form"
          >保存成员</ElButton
        >
      </template>
    </ElDrawer>
    <ElDrawer
      class="admin-drawer"
      direction="rtl"
      append-to-body
      v-model="passwordOpen"
      :title="'重置密码：' + (editing?.username ?? '')"
      :before-close="passwordOpenClose"
      size="680px"
      :close-on-click-modal="false"
    >
      <FeedbackNotice
        v-if="action.error.value"
        :title="action.error.value"
        :closable="false"
        type="error"
      />
      <ElForm
        @keydown.capture="guardFormEnter"
        id="password-form"
        ref="passwordFormRef"
        :model="passwordForm"
        :disabled="!canUpdate || action.busy.value"
        @submit.prevent="resetPassword"
        ><ElFormItem label="新密码" prop="password" :rules="[accountPasswordRule]">
          <ElInput
            v-described-by="'password-reset-help' + ' ' + (formTipId + '-field-10')"
            :aria-describedby="'password-reset-help' + ' ' + (formTipId + '-field-10')"
            v-model="newPassword"
            type="password"
            show-password
            autocomplete="new-password"
            placeholder="新密码"
          />
          <FieldTip :id="formTipId + '-field-10'"
            >设置该成员后续登录使用的新密码；重置会替换原密码。</FieldTip
          >
        </ElFormItem>
        <p id="password-reset-help" class="admin-field-help">
          至少 12 个字符，包含英文字母和数字，UTF-8 最多 72 字节；保存后替换原密码。
        </p></ElForm
      >
      <template #footer>
        <ElButton :disabled="action.busy.value" @click="passwordOpenClose()">取消</ElButton>
        <ElButton
          v-permission="['foundation.tenants.update']"
          type="primary"
          :loading="action.busy.value"
          native-type="submit"
          form="password-form"
          >重置密码</ElButton
        >
      </template>
    </ElDrawer>
  </AdminPage>
</template>
