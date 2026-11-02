<script setup lang="ts">
import FeedbackNotice from '@skillforge/shared/feedback/FeedbackNotice.vue'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import {
  AdminPage,
  EntitySelect,
  guardFormEnter,
  useDrawerClose,
  useResource,
  useTask
} from '@skillforge/shared/admin'
import { message, confirm } from '@skillforge/shared/feedback'
import type { AgentGrant } from '@skillforge/shared/integration/agentDeployments'
import type { AgentTemplateItem } from '@skillforge/shared/integration/agentDefinitions'
import type { GrantBoundary } from '../../api/institutionManagement'
import { api } from '../../api'
import { createInstitutionTargetOptions } from '../../api/institutionTargetOptions'
import { useRoute } from 'vue-router'
import { useManagementIdentity } from '../../composables/managementIdentity'
import { useManagementCommand } from '../../composables/managementCommand'
const identity = useManagementIdentity()
const route = useRoute()
const props = defineProps<{ tenantId?: string; embedded?: boolean }>()
const emit = defineEmits<{ changed: [] }>()
const targets = createInstitutionTargetOptions('tenant.agent_grants.create', () =>
  identity.requirePermission('tenant.agent_grants.create')
)
const list = useResource(async (signal) => {
  const value = await api.institutionManagement.grants.list({ signal, silent: true })
  if (!identity.current()) throw Error('账号已变化，请重新进入页面')
  return value
})
const ownedQuery = ref(''),
  ownedPage = ref(1),
  selectedOwned = ref<AgentTemplateItem>()
const owned = useResource(async (signal) => {
  const query = ownedQuery.value,
    page = ownedPage.value
  const result = identity.allowed('tenant.agent_definitions.read')
    ? await api.agentDefinitions.list(
        {
          page,
          limit: 20,
          name: query,
          status: 'published',
          distribution_policy: 'AUTHORIZED_DEPLOY'
        },
        { signal, silent: true }
      )
    : { items: [], total: 0 }
  if (!identity.current()) throw Error('账号已变化，请重新进入页面')
  return { ...result, query, page }
})
const ownedChoices = computed(() => {
  const result = owned.data.value
  const items =
    result?.query === ownedQuery.value && result.page === ownedPage.value ? result.items : []
  return selectedOwned.value &&
    !items.some((item) => item.definition_id === selectedOwned.value!.definition_id)
    ? [selectedOwned.value, ...items]
    : items
})
function selectOwned(id: string) {
  selectedOwned.value = ownedChoices.value.find((item) => item.definition_id === id)
}
function searchOwned(query: string) {
  ownedQuery.value = query.trim()
  ownedPage.value = 1
  void owned.reload()
}
function pageOwned(page: number) {
  ownedPage.value = page
  void owned.reload()
}
const sources = useResource(async (signal) => {
  const incoming = identity.allowed('tenant.agent_deployments.read')
    ? await api.agentDeployments.grants({ signal, silent: true })
    : { items: [] }
  if (!identity.current()) throw Error('账号已变化，请重新进入页面')
  return { incoming: incoming.items }
})
const write = useManagementCommand()
const action = useTask(),
  open = ref(false),
  editing = ref<AgentGrant>()
const visibleGrants = computed(() =>
  (list.data.value?.items ?? []).filter(
    (row) => !props.tenantId || row.target_tenant_id === props.tenantId
  )
)
const form = reactive({
  source: 'owned' as 'owned' | 'delegated',
  definition_id: '',
  parent_grant_id: '',
  target_tenant_id: '',
  actions: ['deploy', 'execute'] as GrantBoundary['actions'],
  expires_at: '',
  allow_regrant: false,
  remaining_depth: 0
})
const baseline = ref('')
const close = useDrawerClose({
  open: () => open.value,
  busy: () => action.busy.value || !!write.pending.value,
  dirty: () => JSON.stringify(form) !== baseline.value,
  close: () => {
    open.value = false
    editing.value = undefined
  }
})
const parents = computed(() =>
  (sources.data.value?.incoming ?? []).filter(
    (g) =>
      g.status === 'active' &&
      g.allow_regrant &&
      g.remaining_depth > 0 &&
      (!g.expires_at || Date.parse(g.expires_at) > Date.now())
  )
)
const parent = computed(() => parents.value.find((g) => g.grant_id === form.parent_grant_id))
function reloadSources() {
  return form.source === 'owned' ? owned.reload() : sources.reload()
}
const sourceError = computed(() =>
  form.source === 'owned' ? owned.error.value : sources.error.value
)
const sourceLoading = computed(() =>
  form.source === 'owned' ? owned.loading.value : sources.loading.value
)
function begin(row?: AgentGrant) {
  editing.value = row
  const sourceChanged = form.source !== (row?.parent_grant_id ? 'delegated' : 'owned')
  if (!row) {
    owned.invalidate()
    owned.data.value = undefined
    owned.error.value = ''
    ownedQuery.value = ''
    ownedPage.value = 1
    selectedOwned.value = undefined
  }
  Object.assign(form, {
    source: row?.parent_grant_id ? 'delegated' : 'owned',
    definition_id: row?.definition_id ?? '',
    parent_grant_id: row?.parent_grant_id ?? '',
    target_tenant_id:
      row?.target_tenant_id ??
      props.tenantId ??
      (typeof route.query.tenant_id === 'string' ? route.query.tenant_id : ''),
    actions: row ? [...row.actions] : ['deploy', 'execute'],
    expires_at: row?.expires_at ?? '',
    allow_regrant: row?.allow_regrant ?? false,
    remaining_depth: row?.remaining_depth ?? 0
  })
  baseline.value = JSON.stringify(form)

  action.error.value = ''
  open.value = true
  if (!row && !sourceChanged) void reloadSources()
}
async function reloadEditing() {
  if (!editing.value || action.busy.value) return
  const id = editing.value.grant_id
  void action.run(async () => {
    if (
      JSON.stringify(form) !== baseline.value &&
      !(await confirm({
        title: '重新读取授权',
        content: '将丢弃未保存的收窄内容，读取当前修订。',
        confirmText: '丢弃并重新读取',
        cancelText: '继续编辑'
      }))
    )
      return
    identity.requirePermission('tenant.agent_grants.read')
    const data = await api.institutionManagement.grants.list({ silent: true })
    identity.requirePermission('tenant.agent_grants.read')
    const row = data.items.find((g) => g.grant_id === id)
    if (!row) throw Error('授权已不可读取，请刷新目录')
    begin(row)
  })
}
watch(
  () => form.parent_grant_id,
  () => {
    if (editing.value || form.source !== 'delegated') return
    const selected = parent.value
    if (!selected) return
    form.actions = form.actions.filter((action) => selected.actions.includes(action))
    form.allow_regrant = false
    form.remaining_depth = 0
    form.expires_at = selected.expires_at ?? ''
  }
)
watch(
  () => form.source,
  () => {
    if (editing.value) return
    form.definition_id = ''
    form.parent_grant_id = ''
    form.allow_regrant = false
    form.remaining_depth = 0
    form.actions = ['deploy', 'execute']
    form.expires_at = ''
    selectedOwned.value = undefined
    owned.invalidate()
    if (open.value) void reloadSources()
  }
)
function save() {
  void action.run(async () => {
    const permission = editing.value ? 'tenant.agent_grants.narrow' : 'tenant.agent_grants.create'
    const bounds: GrantBoundary = {
      actions: [...form.actions],
      expires_at: form.expires_at || null,
      allow_regrant: form.allow_regrant,
      remaining_depth: form.allow_regrant ? form.remaining_depth : 0
    }
    if (!bounds.actions.length) throw Error('请选择至少一个动作')
    if (!Number.isInteger(bounds.remaining_depth) || bounds.remaining_depth < 0)
      throw Error('转授深度须为非负整数')
    if (
      !editing.value &&
      (!form.target_tenant_id.trim() ||
        (form.source === 'owned' ? !form.definition_id : !parent.value))
    )
      throw Error('请选择明确的定义来源、父授权及目标直属机构')
    if (!editing.value && sourceError.value) throw Error('请先重新读取来源目录')
    const input = editing.value
      ? { ...bounds, grant_id: editing.value.grant_id, expected_revision: editing.value.revision }
      : {
          ...bounds,
          definition_id:
            form.source === 'delegated' ? parent.value!.definition_id : form.definition_id,
          parent_grant_id: form.source === 'delegated' ? form.parent_grant_id : null,
          target_tenant_id: form.target_tenant_id.trim()
        }
    if (
      !(await confirm({
        title: editing.value ? '收窄授权' : '创建显式授权',
        content: `目标机构：${form.target_tenant_id}。${editing.value ? '收窄后不能扩大回原范围，依赖该授权的后续执行与恢复按当前链重新校验。' : '下级仅获得所选定义与动作；转授依据不会自动替换。'}`,
        confirmText: '确认保存',
        cancelText: '取消'
      }))
    )
      return
    identity.requirePermission(permission)
    await write.execute(
      (key) =>
        'grant_id' in input
          ? api.institutionManagement.grants.narrow(input, key)
          : api.institutionManagement.grants.create(input, key),
      () => {
        baseline.value = JSON.stringify(form)
        open.value = false
        message.success(editing.value ? '授权已收窄' : '授权已创建')
        emit('changed')
        editing.value = undefined
        void list.reload()
      },
      () => identity.requirePermission(permission)
    )
  })
}
function revoke(row: AgentGrant) {
  void action.run(async () => {
    const input = { grant_id: row.grant_id, expected_revision: row.revision }
    if (
      !(await confirm({
        title: '撤销授权',
        content: `撤销 ${row.grant_id}（目标 ${row.target_tenant_id}）及其依赖的有效授权链。实例不会自动切换到同源其他授权，已开始任务的恢复也会重查当前链。`,
        confirmText: '撤销授权',
        cancelText: '取消'
      }))
    )
      return
    identity.requirePermission('tenant.agent_grants.revoke')
    await write.execute(
      (key) => api.institutionManagement.grants.revoke(input, key),
      () => {
        message.success('授权已撤销')
        emit('changed')
        void list.reload()
      },
      () => identity.requirePermission('tenant.agent_grants.revoke')
    )
  })
}
onMounted(() => {
  void list.reload()
  if (typeof route.query.tenant_id === 'string' && identity.allowed('tenant.agent_grants.create'))
    begin()
})
</script>
<template>
  <AdminPage
    :embedded="embedded"
    title="智能体授权"
    description="管理本机构发出的原授与转授。转授必须显式选择父授权，目录可见性不会自动赋予业务权限。"
  >
    <template #extra
      ><ElButton
        :disabled="!!write.pending.value"
        v-if="identity.allowed('tenant.agent_grants.create')"
        type="primary"
        @click="begin()"
        >新增授权</ElButton
      ></template
    >
    <div class="admin-card admin-toolbar" role="status">
      <div>
        <strong>定义 → 授权 → 机构实例</strong>
        <p class="admin-muted">
          先选择共享定义和接收机构，再限定可用动作。转授会继续受父授权约束。
        </p>
      </div>
      <ElTag>{{ visibleGrants.length }} 条已发出授权</ElTag>
    </div>
    <FeedbackNotice
      v-if="list.error.value || (!open && action.error.value)"
      :title="action.error.value || '刷新失败，保留上次结果：' + list.error.value"
      type="error"
      :closable="false"
    />
    <ElAlert
      v-if="write.pending.value"
      title="本次授权操作的结果尚未确认，后续写入已暂停。请点击“核对原请求”确认结果，避免重复授权。"
      type="warning"
      :closable="false"
    /><ElButton
      v-if="write.pending.value"
      :loading="action.busy.value"
      @click="action.run(write.reconcile)"
      >核对原请求</ElButton
    ><ElButton :loading="list.loading.value" @click="list.reload">刷新授权</ElButton>
    <ElTable
      :data="visibleGrants"
      table-layout="auto"
      row-key="grant_id"
      v-loading="list.loading.value"
      empty-text="暂无本机构发出的授权"
    >
      <ElTableColumn type="expand" width="48"
        ><template #default="{ row }"
          ><ElDescriptions :column="1" border class="grant-identifiers">
            <ElDescriptionsItem label="授权标识">{{ row.grant_id }}</ElDescriptionsItem>
            <ElDescriptionsItem label="授权来源">{{
              row.parent_grant_id ? '转授，父授权 ' + row.parent_grant_id : '本机构自有定义原授'
            }}</ElDescriptionsItem>
            <ElDescriptionsItem label="当前修订">{{ row.revision }}</ElDescriptionsItem>
          </ElDescriptions></template
        ></ElTableColumn
      >
      <ElTableColumn
        prop="definition_name"
        label="共享定义"
        min-width="190"
        show-overflow-tooltip
      /><ElTableColumn
        prop="target_tenant_id"
        label="目标机构"
        min-width="170"
        show-overflow-tooltip
      /><ElTableColumn label="范围" min-width="210"
        ><template #default="{ row }"
          >{{
            row.actions
              .map((action: string) =>
                action === 'deploy' ? '部署' : action === 'execute' ? '执行' : action
              )
              .join('、')
          }}
          ·
          {{
            row.allow_regrant ? '可转授，剩余 ' + row.remaining_depth + ' 层' : '不可转授'
          }}</template
        ></ElTableColumn
      ><ElTableColumn label="到期时间" min-width="180"
        ><template #default="{ row }">{{ row.expires_at || '无固定期限' }}</template></ElTableColumn
      ><ElTableColumn label="状态" width="100"
        ><template #default="{ row }"
          ><ElTag :type="row.status === 'active' ? 'success' : 'info'">{{
            row.status === 'active'
              ? '生效中'
              : row.status === 'revoked'
                ? '已撤销'
                : row.status === 'expired'
                  ? '已到期'
                  : row.status
          }}</ElTag></template
        ></ElTableColumn
      >
      <ElTableColumn label="操作" width="170" class-name="admin-table-actions"
        ><template #default="{ row }"
          ><ElButton
            v-if="identity.allowed('tenant.agent_grants.narrow') && row.status === 'active'"
            link
            :disabled="action.busy.value || !!write.pending.value"
            @click="begin(row as AgentGrant)"
            >收窄范围</ElButton
          ><ElButton
            v-if="identity.allowed('tenant.agent_grants.revoke') && row.status === 'active'"
            type="danger"
            link
            :disabled="action.busy.value || !!write.pending.value"
            @click="revoke(row as AgentGrant)"
            >撤销</ElButton
          ></template
        ></ElTableColumn
      >
    </ElTable>
    <ElDrawer
      v-model="open"
      :title="editing ? '收窄授权范围' : '新增显式授权'"
      class="admin-drawer"
      size="680px"
      append-to-body
      :before-close="close.requestClose"
      :close-on-click-modal="false"
      :show-close="!action.busy.value && !write.pending.value"
      :close-on-press-escape="!action.busy.value && !write.pending.value"
    >
      <FeedbackNotice
        v-if="action.error.value"
        :title="action.error.value"
        type="error"
        :closable="false"
      />
      <ElButton
        v-if="editing"
        :disabled="action.busy.value || !!write.pending.value"
        @click="reloadEditing"
        >重新读取授权</ElButton
      >
      <FeedbackNotice
        v-if="sourceError && !editing"
        :title="sourceError"
        type="error"
        :closable="false"
      /><ElButton v-if="sourceError && !editing" @click="reloadSources">重试来源目录</ElButton>
      <ElAlert
        v-if="write.pending.value"
        title="授权结果尚未确认，已保留本次选择。请点击“核对原请求”确认结果，不要重复提交。"
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
        <ElFormItem v-if="!editing" label="授权来源"
          ><ElRadioGroup v-model="form.source"
            ><ElRadioButton value="owned">本机构自有定义</ElRadioButton
            ><ElRadioButton value="delegated">获授权定义转授</ElRadioButton></ElRadioGroup
          ></ElFormItem
        >
        <ElFormItem v-if="!editing && form.source === 'owned'" label="自有共享定义" required
          ><ElSelect
            fit-input-width
            v-model="form.definition_id"
            filterable
            remote
            :remote-method="searchOwned"
            :loading="owned.loading.value"
            @change="selectOwned"
            placeholder="按共享定义名称搜索"
          >
            <ElOption
              v-for="item in ownedChoices"
              :key="item.definition_id"
              :value="item.definition_id"
              :label="item.name + ' · ' + item.definition_id"
            />
            <template #footer>
              <div class="owned-definition-pagination" role="group" aria-label="自有共享定义分页">
                <ElPagination
                  size="small"
                  :pager-count="5"
                  :current-page="ownedPage"
                  :page-size="20"
                  :total="owned.data.value?.query === ownedQuery ? owned.data.value.total : 0"
                  layout="prev, pager, next, total"
                  :disabled="owned.loading.value"
                  @current-change="pageOwned"
                />
              </div>
            </template> </ElSelect
        ></ElFormItem>
        <ElFormItem v-if="!editing && form.source === 'delegated'" label="显式父授权" required
          ><ElSelect v-model="form.parent_grant_id" filterable :loading="sources.loading.value"
            ><ElOption
              v-for="g in parents"
              :key="g.grant_id"
              :value="g.grant_id"
              :label="
                g.definition_name + ' · ' + g.grant_id + ' · 剩余 ' + g.remaining_depth + ' 层'
              "
          /></ElSelect>
          <p class="admin-field-help">
            读取父授权需要部署目录读取权限；父授权须允许转授。服务端校验完整授权链、动作、期限与层数。
          </p></ElFormItem
        >
        <ElFormItem label="目标直属机构" required>
          <ElInput v-if="editing || tenantId" :model-value="form.target_tenant_id" readonly />
          <EntitySelect
            v-else
            v-model="form.target_tenant_id"
            :load="targets.load"
            :resolve="targets.resolve"
            :clearable="false"
            :disabled="action.busy.value || !!write.pending.value"
            placeholder="搜索可接收授权的直属机构名称"
            aria-describedby="grant-target-help"
          />
          <p id="grant-target-help" class="admin-field-help">
            显示当前授权创建动作可选择的直属机构；按名称搜索，不需要机构目录读取权限。
          </p>
        </ElFormItem>
        <ElDescriptions v-if="!editing && parent" :column="1" border class="admin-card">
          <ElDescriptionsItem label="授权路径"
            >{{ parent.definition_name }} → 本机构 →
            {{ form.target_tenant_id || '选择目标机构' }}</ElDescriptionsItem
          >
          <ElDescriptionsItem label="父授权范围"
            >{{
              parent.actions.map((action) => (action === 'deploy' ? '部署' : '执行')).join('、')
            }}
            · {{ parent.expires_at || '无固定期限' }} · 可再转授
            {{ Math.max(0, parent.remaining_depth - 1) }} 层</ElDescriptionsItem
          >
        </ElDescriptions>
        <ElFormItem label="允许动作" required
          ><ElCheckboxGroup v-model="form.actions"
            ><ElCheckbox
              value="deploy"
              :disabled="
                (!!editing && !editing.actions.includes('deploy')) ||
                (!editing && form.source === 'delegated' && !parent?.actions.includes('deploy'))
              "
              >部署</ElCheckbox
            ><ElCheckbox
              value="execute"
              :disabled="
                (!!editing && !editing.actions.includes('execute')) ||
                (!editing && form.source === 'delegated' && !parent?.actions.includes('execute'))
              "
              >执行</ElCheckbox
            ></ElCheckboxGroup
          ></ElFormItem
        >
        <ElCollapse
          ><ElCollapseItem title="期限与继续转授" name="boundary">
            <ElFormItem label="到期时间"
              ><ElDatePicker
                v-model="form.expires_at"
                type="datetime"
                value-format="YYYY-MM-DDTHH:mm:ssZ"
                placeholder="留空为无固定期限"
                clearable
            /></ElFormItem>
            <ElFormItem label="允许继续转授"
              ><ElSwitch
                v-model="form.allow_regrant"
                :disabled="!!editing && !editing.allow_regrant"
            /></ElFormItem>
            <ElFormItem v-if="form.allow_regrant" label="剩余转授深度"
              ><ElInputNumber
                v-model="form.remaining_depth"
                :min="0"
                :max="
                  editing?.remaining_depth ??
                  (parent ? Math.max(0, parent.remaining_depth - 1) : undefined)
                "
                :precision="0"
            /></ElFormItem> </ElCollapseItem
        ></ElCollapse>
      </ElForm>
      <template #footer
        ><ElButton
          :disabled="action.busy.value || !!write.pending.value"
          @click="close.requestClose"
          >取消</ElButton
        ><ElButton
          type="primary"
          :loading="action.busy.value"
          :disabled="!!write.pending.value || (!editing && sourceLoading)"
          @click="save"
          >保存授权</ElButton
        ></template
      >
    </ElDrawer>
  </AdminPage>
</template>

<style scoped>
.grant-identifiers {
  margin: 16px;
  overflow-wrap: anywhere;
}
</style>
