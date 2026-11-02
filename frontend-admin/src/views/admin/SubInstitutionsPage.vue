<script setup lang="ts">
import FeedbackNotice from '@skillforge/shared/feedback/FeedbackNotice.vue'
import { onMounted, reactive, ref } from 'vue'
import {
  AdminPage,
  EntitySelect,
  type EntityOption,
  guardFormEnter,
  useDrawerClose,
  useResource,
  useTask
} from '@skillforge/shared/admin'
import { message } from '@skillforge/shared/feedback'
import { api } from '../../api'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../../stores/auth'
import { useManagementIdentity } from '../../composables/managementIdentity'
import { useManagementCommand } from '../../composables/managementCommand'
const identity = useManagementIdentity()
const router = useRouter()
const auth = useAuthStore()
defineProps<{ tenantId?: string; embedded?: boolean }>()
const emit = defineEmits<{ changed: []; provisioned: [tenantId: string] }>()
const page = ref(1)
function changePage(value: number) {
  page.value = value
  void list.reload()
}
const list = useResource(async (signal) => {
  const value = await api.institutionManagement.subInstitutions.list(page.value, {
    signal,
    silent: true
  })
  if (!identity.current()) throw Error('账号已变化，请重新进入页面')
  return value
})
const action = useTask(),
  write = useManagementCommand()
const open = ref(false)
const form = reactive({
  namespace: '',
  external_institution_ref: '',
  name: '',
  default_plan_code: ''
})
const baseline = ref('')
const close = useDrawerClose({
  open: () => open.value,
  busy: () => action.busy.value || !!write.pending.value,
  dirty: () => JSON.stringify(form) !== baseline.value,
  close: () => {
    open.value = false
  }
})
async function loadPlans(query: string, signal?: AbortSignal): Promise<EntityOption[]> {
  const options: EntityOption[] = []
  for (let page = 1; ; page++) {
    identity.requirePermission('tenant.sub_institutions.provision')
    const result = await api.institutionManagement.subInstitutions.planOptions(
      { q: query, page, page_size: 200 },
      { signal, silent: true }
    )
    identity.requirePermission('tenant.sub_institutions.provision')
    const next = result.items.filter((item) => !options.some((option) => option.id === item.id))
    if (!next.length && options.length < result.total) throw Error('套餐目录已变化，请重新搜索')
    options.push(...next)
    if (options.length >= result.total) return options
  }
}
async function resolvePlan(id: string): Promise<EntityOption | undefined> {
  identity.requirePermission('tenant.sub_institutions.provision')
  const result = await api.institutionManagement.subInstitutions.planOptions(
    { id },
    { silent: true }
  )
  identity.requirePermission('tenant.sub_institutions.provision')
  return result.items.find((option) => option.id === id)
}
function create() {
  Object.assign(form, {
    namespace: '',
    external_institution_ref: '',
    name: '',
    default_plan_code: ''
  })
  baseline.value = JSON.stringify(form)
  action.error.value = ''
  open.value = true
}
function save() {
  void action.run(async () => {
    const input = Object.fromEntries(
      Object.entries(form).map(([key, value]) => [key, value.trim()])
    ) as typeof form
    if (Object.values(input).some((value) => !value))
      throw Error('请填写机构名称、命名空间、外部机构标识并选择初始套餐')
    identity.requirePermission('tenant.sub_institutions.provision')
    await write.execute(
      (key) => api.institutionManagement.subInstitutions.provision(input, key),
      (result) => {
        baseline.value = JSON.stringify(form)
        open.value = false
        message.success('直属机构已开通')
        emit('changed')
        emit('provisioned', result.tenant_id)
        void list.reload()
      },
      () => identity.requirePermission('tenant.sub_institutions.provision')
    )
  })
}
onMounted(() => {
  void list.reload()
})
</script>
<template>
  <AdminPage
    :embedded="embedded"
    title="下级机构"
    description="查看明确获准的机构目录，或开通新的直属机构。业务管理、智能体部署和用量权限分别授权。"
  >
    <template #extra
      ><ElButton
        :disabled="!!write.pending.value"
        v-if="identity.allowed('tenant.sub_institutions.provision')"
        type="primary"
        @click="create"
        >开通直属机构</ElButton
      ></template
    >
    <FeedbackNotice
      v-if="list.error.value"
      :title="'目录刷新失败，保留上次结果：' + list.error.value"
      type="error"
      :closable="false"
    />
    <div class="admin-toolbar admin-card">
      <div>
        <strong>从机构开始办理日常事务</strong>
        <p class="admin-muted">选择机构后直接授权、部署或核对用量，各项业务仍按独立权限校验。</p>
      </div>
      <div class="admin-actions">
        <span class="admin-muted" role="status">{{ list.data.value?.total ?? 0 }} 家可查看机构</span
        ><ElButton :loading="list.loading.value" @click="list.reload">刷新目录</ElButton>
      </div>
    </div>
    <ElTable
      :data="list.data.value?.items ?? []"
      table-layout="auto"
      row-key="tenant_id"
      v-loading="list.loading.value"
      empty-text="暂无获准查看的下级机构"
    >
      <ElTableColumn
        prop="name"
        label="机构名称"
        min-width="190"
        show-overflow-tooltip
      /><ElTableColumn prop="tenant_id" label="机构标识" /><ElTableColumn
        prop="parent_tenant_id"
        label="上级机构"
      /><ElTableColumn prop="status" label="状态" /><ElTableColumn
        prop="revision"
        label="关系修订"
        width="100"
      />
      <ElTableColumn label="办理业务" min-width="250" class-name="admin-table-actions"
        ><template #default="{ row }">
          <ElButton link @click="router.push('/admin/institutions/' + row.tenant_id)"
            >进入工作台</ElButton
          >
          <ElButton
            v-if="
              identity.allowed('tenant.agent_grants.read') &&
              identity.allowed('tenant.agent_grants.create') &&
              row.parent_tenant_id === auth.user?.tenant_id
            "
            link
            @click="
              router.push({ path: '/admin/agent-grants', query: { tenant_id: row.tenant_id } })
            "
            >授权智能体</ElButton
          >
          <ElButton
            v-if="identity.allowed('tenant.agent_deployments.read')"
            link
            @click="
              router.push({ path: '/admin/agent-deployments', query: { tenant_id: row.tenant_id } })
            "
            >管理实例</ElButton
          >
          <ElButton
            v-if="
              ['summary', 'details', 'events'].some((kind) =>
                identity.allowed('tenant.sub_institutions.usage_' + kind + '.read')
              )
            "
            link
            @click="
              router.push({
                path: '/admin/headquarters-usage',
                query: { tenant_id: row.tenant_id }
              })
            "
            >查用量</ElButton
          >
        </template></ElTableColumn
      >
    </ElTable>
    <ElPagination
      :current-page="page"
      :page-size="20"
      :total="list.data.value?.total ?? 0"
      layout="prev, pager, next, total"
      @current-change="changePage"
    />
    <ElDrawer
      v-model="open"
      title="开通直属机构"
      size="680px"
      append-to-body
      :before-close="close.requestClose"
      :close-on-click-modal="false"
      :show-close="!action.busy.value && !write.pending.value"
      :close-on-press-escape="!action.busy.value && !write.pending.value"
    >
      <ElAlert
        title="创建新的直属机构；不会认领既有机构，也不会自动签发账号、凭据或智能体授权。"
        type="info"
        :closable="false"
      />
      <FeedbackNotice
        v-if="action.error.value"
        :title="action.error.value"
        type="error"
        :closable="false"
      />
      <ElAlert
        v-if="write.pending.value"
        title="开通结果尚未确认，原请求已保留。请核对原请求后再开始其它开通。"
        type="warning"
        :closable="false"
      /><ElButton
        v-if="write.pending.value"
        :loading="action.busy.value"
        @click="action.run(write.reconcile)"
        >核对原请求</ElButton
      ><ElForm
        :model="form"
        label-position="top"
        :disabled="action.busy.value || !!write.pending.value"
        @submit.prevent="save"
        @keydown.capture="guardFormEnter"
      >
        <h3>机构信息</h3>
        <ElFormItem label="机构名称" required
          ><ElInput v-model="form.name" maxlength="128"
        /></ElFormItem>
        <h3>业务系统关联</h3>
        <ElFormItem label="命名空间" required
          ><ElInput v-model="form.namespace" maxlength="128" />
          <p class="admin-field-help">
            填写业务系统使用的命名空间，与外部机构标识共同定位机构。
          </p></ElFormItem
        >
        <ElFormItem label="外部机构标识" required
          ><ElInput v-model="form.external_institution_ref" maxlength="128" />
          <p class="admin-field-help">
            业务系统中的稳定标识，同一开通命令重试返回原回执。
          </p></ElFormItem
        >
        <ElFormItem label="初始套餐" required
          ><EntitySelect
            v-model="form.default_plan_code"
            :load="loadPlans"
            :resolve="resolvePlan"
            :clearable="false"
            :disabled="action.busy.value || !!write.pending.value"
            placeholder="搜索并选择启用的套餐"
            aria-describedby="sub-institution-plan-help"
          />
          <p id="sub-institution-plan-help" class="admin-field-help">
            选择机构开通后采用的初始套餐，配额继承所选套餐。
          </p></ElFormItem
        >
      </ElForm>
      <template #footer
        ><ElButton
          :disabled="action.busy.value || !!write.pending.value"
          @click="close.requestClose"
          >取消</ElButton
        ><ElButton
          type="primary"
          :loading="action.busy.value"
          :disabled="!!write.pending.value"
          @click="save"
          >开通机构</ElButton
        ></template
      >
    </ElDrawer>
  </AdminPage>
</template>
