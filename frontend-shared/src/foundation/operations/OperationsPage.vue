<script setup lang="ts">
import FeedbackNotice from '../../feedback/FeedbackNotice.vue'
import { computed, onBeforeUnmount, reactive, ref, useId, watch } from 'vue'
import AdminPage from '../../admin/AdminPage.vue'
import { ElMessageBox } from 'element-plus'
import { useResource, useTask } from '../../admin/tasks'
import { confirm } from '../../feedback'
import { useUnsavedChanges } from '../../admin/leave'
import { formatDateTime } from '../../utils/datetime'
import FieldTip from '../../components/FieldTip.vue'
import { vDescribedBy } from '../../directives/describedBy'
import { saveAuditCsv } from './contracts'
import type {
  AuditSummary,
  AuditSummaryPage,
  AuditFilterOption,
  AuditOptions,
  AuditHold,
  CleanupRun,
  OperationsRequest,
  RetentionPolicy,
  RuntimeStatus
} from './contracts'

/** 相同组件由双宿主注入API和实时权限，apiBase为foundation/operations绝对接口根。 */
const props = defineProps<{
  request: OperationsRequest
  apiBase: string
  auditOptionsPath?: string
  permissions: readonly string[]
}>()
const tabs = computed(() =>
  [
    { name: 'audit', title: '安全审计', permission: 'audit_read' },
    { name: 'retention', title: '保留与清理', permission: 'retention_read' },
    { name: 'status', title: '运维状态', permission: 'status_read' }
  ].filter((tab) => can(tab.permission))
)
const active = ref('audit')
const advancedFilters = ref(false)
const formTipId = useId()
const page = ref(1)
const filters = reactive({
  action: '',
  actor_id: '',
  category: '',
  outcome: '',
  resource_type: '',
  trace_id: '',
  from: '',
  to: ''
})
const applied = ref<Record<string, string>>({})
const policy = reactive<RetentionPolicy>({
  resource: 'audit',
  retention_days: 180,
  enabled: false,
  version: 0
})
const policyBaseline = ref('')
const policyDirty = computed(
  () => !!policyBaseline.value && JSON.stringify(policy) !== policyBaseline.value
)
const preview = ref<CleanupRun>()
const holdForm = reactive({ audit_id: '', reason: '', expires_at: '' })
const holdBaseline = ref(JSON.stringify(holdForm))
const holdDirty = computed(() => JSON.stringify(holdForm) !== holdBaseline.value)
useUnsavedChanges({
  dirty: () => policyDirty.value || holdDirty.value,
  busy: () => task.busy.value
})
const validation = ref('')
const feedback = ref('')
const feedbackType = ref<'success' | 'warning' | 'error'>('success')
let viewGeneration = 0
onBeforeUnmount(() => {
  viewGeneration++
})
const task = useTask()
const audit = useResource((signal) =>
  props.request<AuditSummaryPage>(
    'GET',
    `${props.apiBase}/audit/summaries?${query()}`,
    undefined,
    false,
    {
      signal,
      silent: true
    }
  )
)
const auditOptions = useResource((signal) =>
  props.auditOptionsPath
    ? props.request<AuditOptions>('GET', props.auditOptionsPath, undefined, false, {
        signal,
        silent: true
      })
    : Promise.resolve(undefined)
)
const retention = useResource(async (signal) => {
  const [current, runs, holds] = await Promise.all([
    props.request<RetentionPolicy>('GET', `${props.apiBase}/retention`, undefined, false, {
      signal,
      silent: true
    }),
    props.request<CleanupRun[]>('GET', `${props.apiBase}/retention/runs`, undefined, false, {
      signal,
      silent: true
    }),
    props.request<AuditHold[]>('GET', `${props.apiBase}/retention/holds`, undefined, false, {
      signal,
      silent: true
    })
  ])
  return { current, runs, holds }
})
const status = useResource((signal) =>
  props.request<RuntimeStatus>('GET', `${props.apiBase}/status`, undefined, false, {
    signal,
    silent: true
  })
)
const categories = [
  { value: 'authentication', label: '认证' },
  { value: 'authorization', label: '授权' },
  { value: 'sensitive', label: '敏感访问' },
  { value: 'export', label: '导出' },
  { value: 'operation', label: '业务操作' }
]
const outcomes = [
  { value: 'success', label: '成功' },
  { value: 'failure', label: '失败' },
  { value: 'denied', label: '拒绝' }
]
const categoryChoices = computed<AuditFilterOption[]>(
  () => auditOptions.data.value?.categories ?? categories
)
const outcomeChoices = computed<AuditFilterOption[]>(
  () => auditOptions.data.value?.outcomes ?? outcomes
)
const actionChoices = computed(() => auditOptions.data.value?.actions ?? [])
const actorChoices = computed(() => auditOptions.data.value?.actors ?? [])
const resourceChoices = computed(() => auditOptions.data.value?.resource_types ?? [])
const stateNames: Record<string, string> = {
  ready: '待执行',
  succeeded: '已完成',
  failed: '失败且已回滚',
  expired: '预览已过期'
}
/** 显示权限直接来自当前me，服务端仍重新检查。 */
function can(action: string) {
  return props.permissions.includes(`foundation.operations.${action}`)
}
function optionLabel(
  choices: AuditFilterOption[] | undefined,
  value: string | null | undefined,
  fallback = '—'
) {
  if (!value) return fallback
  return choices?.find((option) => option.value === value)?.label ?? value
}
function actionLabel(row: AuditSummaryPage['items'][number]) {
  return optionLabel(actionChoices.value, row.action, row.action)
}
function categoryLabel(row: AuditSummaryPage['items'][number]) {
  return optionLabel(categoryChoices.value, row.category, row.category)
}
function outcomeLabel(row: AuditSummaryPage['items'][number]) {
  return optionLabel(outcomeChoices.value, row.outcome, row.outcome)
}
function resourceLabel(row: AuditSummaryPage['items'][number]) {
  return optionLabel(resourceChoices.value, row.resource_type, '无特定资源')
}
function actorLabel(row: AuditSummaryPage['items'][number]) {
  return optionLabel(
    actorChoices.value,
    row.actor_id,
    row.actor_id ? '平台或历史操作者' : '系统或未知来源'
  )
}
function shortIdentifier(value: string) {
  return value.length > 18 ? `${value.slice(0, 8)}…${value.slice(-4)}` : value
}
/** 页码与已提交条件保持同一查询快照。 */
function query() {
  return new URLSearchParams({
    ...applied.value,
    page: String(page.value),
    page_size: '50'
  }).toString()
}
/** 只加载当前权限内的选项卡，不请求隐藏能力。 */
async function load() {
  const generation = viewGeneration
  if (active.value === 'audit' && can('audit_read')) {
    await Promise.all([audit.reload(), props.auditOptionsPath ? auditOptions.reload() : undefined])
  }
  if (active.value === 'retention' && can('retention_read')) {
    const value = await retention.reload()
    if (value && generation === viewGeneration && !policyDirty.value) {
      Object.assign(policy, value.current)
      policyBaseline.value = JSON.stringify(policy)
    }
  }
  if (active.value === 'status' && can('status_read')) await status.reload()
}
/** 验证本地时间并转换带时区的瞬间，非法输入不发请求。 */
function search() {
  validation.value = ''
  const snapshot: Record<string, string> = {}
  for (const [key, value] of Object.entries(filters)) if (value) snapshot[key] = value
  for (const key of ['from', 'to'])
    if (snapshot[key]) {
      const time = new Date(snapshot[key]!).getTime()
      if (!Number.isFinite(time)) {
        validation.value = '请输入有效时间'
        return
      }
      snapshot[key] = new Date(time).toISOString()
    }
  if (snapshot.from && snapshot.to && snapshot.from >= snapshot.to) {
    validation.value = '截止时间必须晚于开始时间'
    return
  }
  applied.value = snapshot
  page.value = 1
  void audit.reload()
}
/** 从已授权的审计记录带入保护目标，避免再次查找和复制身份。 */
async function protectAudit(id: string) {
  if (!can('retention_manage') || !can('retention_read')) return
  if (holdForm.audit_id === id) {
    active.value = 'retention'
    return
  }
  const generation = viewGeneration
  if (
    holdDirty.value &&
    !(await confirm({
      title: '切换调查保护对象',
      content: `切换到审计 ${id} 将放弃当前未登记的调查依据和到期时间。`,
      confirmText: '放弃草稿并切换',
      cancelText: '保留当前草稿'
    }))
  )
    return
  if (generation !== viewGeneration) return
  holdForm.audit_id = id
  holdForm.reason = ''
  holdForm.expires_at = ''
  holdBaseline.value = JSON.stringify(holdForm)
  active.value = 'retention'
}
function resetFilters() {
  for (const key of Object.keys(filters) as Array<keyof typeof filters>) filters[key] = ''
  search()
}
/** 导出当前提交条件，仅发送一次；CSV结果通过注入认证客户端读取。 */
async function exportAudit() {
  const generation = viewGeneration
  const request = props.request
  const apiBase = props.apiBase
  await task.run(async () => {
    const blob = await request<Blob>('POST', `${apiBase}/audit/export`, applied.value, false, {
      responseType: 'blob'
    })
    if (generation !== viewGeneration) return
    saveAuditCsv(blob)
    feedbackType.value = 'success'
    feedback.value = '已导出当前范围审计'
  })
}
/** 策略完整替换携带版本，成功后旧预览失效。 */
async function savePolicy() {
  if (!can('retention_manage') || !policyDirty.value || task.busy.value) return
  const generation = viewGeneration
  const result = await task.run(() =>
    props.request<RetentionPolicy>('PUT', `${props.apiBase}/retention`, {
      retention_days: policy.retention_days,
      enabled: policy.enabled,
      expected_version: policy.version
    })
  )
  if (result && generation === viewGeneration) {
    Object.assign(policy, result)
    policyBaseline.value = JSON.stringify(policy)
    preview.value = undefined
    feedbackType.value = 'success'
    feedback.value = '保留策略已生效'
    await load()
    if (generation === viewGeneration && retention.error.value)
      feedback.value = `保留策略已生效，最新记录读取失败：${retention.error.value}。可重新查询，无需再次保存策略。`
  }
}
/** 取消只恢复当前编辑基线，不发策略写入。 */
function cancelPolicy() {
  if (!policyBaseline.value || task.busy.value) return
  Object.assign(policy, JSON.parse(policyBaseline.value) as RetentionPolicy)
}
/** 预览由服务器冻结候选，保护事实不能被客户端取消。 */
async function prepare() {
  if (policyDirty.value || !policy.enabled || task.busy.value) return
  const generation = viewGeneration
  const value = await task.run(() =>
    props.request<CleanupRun>('POST', `${props.apiBase}/retention/preview`)
  )
  if (value && generation === viewGeneration) {
    preview.value = value
    await load()
  }
}
/** 明确确认已冻结数量后发送一次执行请求；网络失败不自动重发写入。 */
async function execute() {
  const value = preview.value
  if (!value || policyDirty.value || !policy.enabled || value.status !== 'ready' || task.busy.value)
    return
  const generation = viewGeneration
  const request = props.request
  const apiBase = props.apiBase
  const result = await task.run(async () => {
    await ElMessageBox.confirm(
      `清理此次预览中 ${value.candidates} 条到期审计？当前 ${value.protected_count} 条受调查引用保护，执行时会再次检查。删除后无法恢复。`,
      '执行到期清理',
      { type: 'warning', autofocus: false, confirmButtonText: '执行', cancelButtonText: '取消' }
    )
    if (generation !== viewGeneration) return
    return request<CleanupRun>('POST', `${apiBase}/retention/execute`, { id: value.id })
  })
  if (result && generation === viewGeneration) {
    preview.value = result
    feedbackType.value =
      result.status === 'succeeded' ? 'success' : result.status === 'failed' ? 'error' : 'warning'
    feedback.value = `${stateNames[result.status]}，实际删除 ${result.deleted_count} 条`
    await load()
  }
}
/** 登记具体事实保护，不允许从表单指定其他租户。 */
async function createHold() {
  const generation = viewGeneration
  const result = await task.run(() => {
    const time = holdForm.expires_at ? new Date(holdForm.expires_at).getTime() : null
    if (time !== null && !Number.isFinite(time)) throw new Error('请输入有效保护到期时间')
    const body = {
      audit_id: holdForm.audit_id,
      reason: holdForm.reason,
      expires_at: time === null ? null : new Date(time).toISOString()
    }
    return props.request<AuditHold>('POST', `${props.apiBase}/retention/holds`, body)
  })
  if (result && generation === viewGeneration) {
    holdForm.audit_id = ''
    holdForm.reason = ''
    holdForm.expires_at = ''
    holdBaseline.value = JSON.stringify(holdForm)
    feedbackType.value = 'success'
    feedback.value = '调查保护已登记'
    await load()
  }
}
/** 解除保护只操作精确ID，服务器保留历史解除事实。 */
async function releaseHold(id: string, auditId: string) {
  const generation = viewGeneration
  const request = props.request
  const apiBase = props.apiBase
  const result = await task.run(async () => {
    await ElMessageBox.confirm(
      `解除审计 ${auditId} 的调查保护？解除后，已到保留期限且没有其他保护的审计可被后续清理删除。`,
      '解除保护',
      {
        type: 'warning',
        autofocus: false
      }
    )
    if (generation !== viewGeneration) return
    await request('POST', `${apiBase}/retention/holds/release`, { id })
    return true
  })
  if (result && generation === viewGeneration) {
    feedbackType.value = 'success'
    feedback.value = '保护已解除'
    await load()
  }
}
/** 切换界面丢弃迟到请求；身份或权限变化清除旧数据再加载获授权的页面。 */
watch(
  () => [props.apiBase, props.request, props.permissions.join(',')],
  () => {
    viewGeneration++
    for (const resource of [audit, auditOptions, retention, status]) {
      resource.invalidate()
      resource.data.value = undefined
    }
    preview.value = undefined
    Object.assign(holdForm, { audit_id: '', reason: '', expires_at: '' })
    holdBaseline.value = JSON.stringify(holdForm)
    policyBaseline.value = ''
    feedback.value = ''
    page.value = 1
    if (!tabs.value.some((tab) => tab.name === active.value))
      active.value = tabs.value[0]?.name ?? ''
    void load()
  },
  { immediate: true }
)
watch(active, () => {
  viewGeneration++
  for (const resource of [audit, auditOptions, retention, status]) resource.invalidate()
  feedback.value = ''
  void load()
})
</script>

<template>
  <AdminPage class="operations-page" title="安全审计与运维" aria-label="安全审计与运维">
    <el-alert v-if="!tabs.length" title="当前账号没有此页面权限" type="warning" :closable="false" />
    <FeedbackNotice
      v-if="task.error.value || validation"
      :title="task.error.value || validation"
      type="error"
      :closable="false"
    />
    <FeedbackNotice v-if="feedback" :title="feedback" :type="feedbackType" :closable="false" />
    <el-tabs v-model="active" :before-leave="() => !task.busy.value">
      <el-tab-pane
        v-for="tab in tabs"
        :key="tab.name"
        :label="tab.title"
        :name="tab.name"
        :disabled="task.busy.value"
      />
    </el-tabs>
    <template v-if="active === 'audit' && can('audit_read')">
      <form class="filters" v-described-by="formTipId + '-filters'" @submit.prevent="search">
        <template v-if="advancedFilters"
          ><template v-if="auditOptionsPath"
            ><label
              >操作类型<el-select v-model="filters.action" clearable filterable
                ><el-option
                  v-for="item in actionChoices"
                  :key="item.value"
                  :value="item.value"
                  :label="item.label" /></el-select
            ></label>
            <label
              >操作者<el-select v-model="filters.actor_id" clearable filterable
                ><el-option
                  v-for="item in actorChoices"
                  :key="item.value"
                  :value="item.value"
                  :label="item.label" /></el-select
            ></label>
            <label
              >资源类型<el-select v-model="filters.resource_type" clearable
                ><el-option
                  v-for="item in resourceChoices"
                  :key="item.value"
                  :value="item.value"
                  :label="item.label" /></el-select
            ></label>
          </template>
          <template v-else
            ><label
              >动作编码<el-input v-model="filters.action" placeholder="auth.login" clearable
            /></label>
            <label>操作者ID<el-input v-model="filters.actor_id" clearable /></label>
            <label
              >资源类型<el-input v-model="filters.resource_type" clearable />
            </label> </template
          ><label
            >追踪ID<el-input
              v-model="filters.trace_id"
              placeholder="仅在已知 Trace ID 时填写"
              clearable
          /></label>
        </template>
        <label
          >分类<el-select v-model="filters.category" clearable
            ><el-option
              v-for="item in categoryChoices"
              :key="item.value"
              :value="item.value"
              :label="item.label" /></el-select
        ></label>
        <label
          >结果<el-select v-model="filters.outcome" clearable
            ><el-option
              v-for="item in outcomeChoices"
              :key="item.value"
              :value="item.value"
              :label="item.label" /></el-select
        ></label>
        <label>开始时间<input v-model="filters.from" type="datetime-local" /></label>
        <label>截止时间<input v-model="filters.to" type="datetime-local" /></label>
        <FieldTip :id="formTipId + '-filters'">
          时间按本地时区填写，包含开始、不包含截止。翻页和导出沿用已检索条件。
        </FieldTip>
        <el-button native-type="submit" type="primary" :loading="audit.loading.value"
          >检索</el-button
        >
        <el-button @click="advancedFilters = !advancedFilters" :aria-expanded="advancedFilters">{{
          advancedFilters ? '收起诊断条件' : '更多条件'
        }}</el-button>
        <el-button v-if="Object.values(filters).some(Boolean)" @click="resetFilters"
          >重置</el-button
        >
        <el-button v-if="can('audit_export')" :loading="task.busy.value" @click="exportAudit"
          >导出当前范围</el-button
        >
      </form>
      <FeedbackNotice
        v-if="audit.error.value"
        :title="audit.error.value"
        type="error"
        :closable="false"
      />
      <el-table
        :data="audit.data.value?.items ?? []"
        v-loading="audit.loading.value"
        row-key="id"
        table-layout="fixed"
        class="audit-table"
        :empty-text="
          audit.error.value
            ? '审计记录尚未读取成功'
            : audit.loading.value
              ? '正在读取审计记录…'
              : '当前查询范围没有审计事件'
        "
      >
        <el-table-column label="审计事件" min-width="300"
          ><template #default="{ row }">
            <div>{{ row.message || actionLabel(row as AuditSummary) }}</div>
            <div class="admin-field-help">
              {{ actionLabel(row as AuditSummary) }} · {{ categoryLabel(row as AuditSummary) }} ·
              {{ resourceLabel(row as AuditSummary) }}
            </div>
            <div class="admin-field-help" :title="String(row.id)">
              审计 {{ shortIdentifier(String(row.id)) }}
              <template v-if="row.trace_id">
                · 追踪
                <span :title="row.trace_id">{{ shortIdentifier(row.trace_id) }}</span>
              </template>
            </div>
          </template></el-table-column
        >
        <el-table-column label="操作与结果" width="200"
          ><template #default="{ row }">
            <div :title="row.actor_id || undefined">{{ actorLabel(row as AuditSummary) }}</div>
            <el-tag
              size="small"
              :type="
                row.outcome === 'success'
                  ? 'success'
                  : row.outcome === 'denied'
                    ? 'warning'
                    : 'danger'
              "
              >{{ outcomeLabel(row as AuditSummary) }}</el-tag
            >
            <div class="admin-field-help">{{ formatDateTime(row.created_at) }}</div>
          </template></el-table-column
        >
        <el-table-column
          v-if="can('retention_manage') && can('retention_read')"
          label="操作"
          width="110"
          fixed="right"
          ><template #default="{ row }"
            ><el-button link @click="protectAudit(String(row.id))">调查保护</el-button></template
          ></el-table-column
        >
      </el-table>
      <el-pagination
        v-model:current-page="page"
        :page-size="50"
        :total="audit.data.value?.total ?? 0"
        layout="total, prev, pager, next"
        @current-change="audit.reload()"
      />
    </template>
    <template v-if="active === 'retention' && can('retention_read')">
      <FeedbackNotice
        v-if="retention.error.value"
        :title="retention.error.value"
        type="error"
        :closable="false"
      />
      <el-form
        class="policy"
        label-position="top"
        :disabled="task.busy.value"
        @submit.prevent="savePolicy"
      >
        <el-form-item label="审计保留天数"
          ><el-input-number
            v-model="policy.retention_days"
            v-described-by="formTipId + '-retention-days'"
            :min="30"
            :max="3650"
            :disabled="task.busy.value || !can('retention_manage')"
          />
          <FieldTip :id="formTipId + '-retention-days'">
            单位为天，填写 30 至 3650 的整数；保存后生效，修改策略后需重新生成清理预览。
          </FieldTip>
        </el-form-item>
        <el-form-item label="允许到期清理"
          ><el-switch
            v-model="policy.enabled"
            v-described-by="formTipId + '-retention-enabled'"
            :disabled="task.busy.value || !can('retention_manage')"
          />
          <FieldTip :id="formTipId + '-retention-enabled'">
            默认关闭；开启并保存后才允许清理预览，不会立即删除审计，执行仍需另行确认。
          </FieldTip>
        </el-form-item>
        <el-button
          v-if="can('retention_manage')"
          type="primary"
          native-type="submit"
          :loading="task.busy.value"
          :disabled="task.busy.value || !policyDirty || !policyBaseline"
          >保存策略</el-button
        >
        <el-button
          v-if="can('retention_manage')"
          :disabled="!policyDirty || task.busy.value"
          @click="cancelPolicy"
          >取消修改</el-button
        >
        <el-button
          v-if="can('retention_execute')"
          :disabled="task.busy.value || !policy.enabled || policyDirty || !policyBaseline"
          :loading="task.busy.value"
          @click="prepare"
          >生成清理预览</el-button
        >
      </el-form>
      <p v-if="policyDirty" role="status" class="admin-field-help">
        策略有未保存修改；保存后才能生成或执行清理预览。
      </p>
      <el-alert
        v-if="preview"
        :title="`预览 ${preview.id}：候选${preview.candidates}，受保护${preview.protected_count}，${stateNames[preview.status]}`"
        :description="`到期截止 ${formatDateTime(preview.cutoff)}；预览有效期至 ${formatDateTime(preview.expires_at)}`"
        type="info"
        :closable="false"
      />
      <el-button
        v-if="preview?.status === 'ready' && can('retention_execute')"
        type="danger"
        :disabled="policyDirty || !policy.enabled"
        :loading="task.busy.value"
        @click="execute"
        >执行这次预览</el-button
      >
      <h2>执行回执</h2>
      <el-table
        :data="retention.data.value?.runs ?? []"
        row-key="id"
        :empty-text="
          retention.error.value
            ? '清理记录尚未读取成功'
            : retention.loading.value
              ? '正在读取清理记录…'
              : '暂无清理预览或回执'
        "
      >
        <el-table-column prop="id" label="回执ID" /><el-table-column
          prop="candidates"
          label="候选"
        /><el-table-column prop="protected_count" label="受保护" /><el-table-column
          prop="deleted_count"
          label="实际删除"
        />
        <el-table-column label="状态"
          ><template #default="{ row }">{{ stateNames[row.status] }}</template></el-table-column
        >
        <el-table-column prop="failure_code" label="失败原因" /><el-table-column label="登记时间"
          ><template #default="{ row }">{{
            formatDateTime(row.created_at)
          }}</template></el-table-column
        >
      </el-table>
      <h2>调查引用保护</h2>
      <form
        v-if="can('retention_manage')"
        class="filters"
        v-described-by="formTipId + '-hold'"
        @submit.prevent="createHold"
      >
        <label>审计ID<el-input v-model="holdForm.audit_id" required /></label
        ><label
          >调查或法务依据<el-input v-model="holdForm.reason" required maxlength="500"
        /></label>
        <label>可选到期时间<input v-model="holdForm.expires_at" type="datetime-local" /></label>
        <FieldTip :id="formTipId + '-hold'">
          填写当前作用域的完整审计 ID 和调查或法务依据；依据必填、最多 500
          字符，不填写秘密。到期时间可留空，留空在显式解除前持续保护；填写时须晚于当前时刻。点击登记保护后生效。
        </FieldTip>
        <el-button native-type="submit" :loading="task.busy.value">登记保护</el-button>
      </form>
      <el-table
        :data="retention.data.value?.holds ?? []"
        row-key="id"
        :empty-text="
          retention.error.value
            ? '调查保护记录尚未读取成功'
            : retention.loading.value
              ? '正在读取调查保护记录…'
              : '暂无调查保护'
        "
      >
        <el-table-column prop="audit_id" label="审计ID" /><el-table-column
          prop="reason"
          label="保护原因"
        />
        <el-table-column label="到期"
          ><template #default="{ row }">{{
            row.expires_at ? formatDateTime(row.expires_at) : '显式解除前保留'
          }}</template></el-table-column
        >
        <el-table-column label="状态"
          ><template #default="{ row }">{{
            row.released_at ? '已解除' : '保护中'
          }}</template></el-table-column
        >
        <el-table-column v-if="can('retention_manage')" label="操作"
          ><template #default="{ row }"
            ><el-button
              v-if="!row.released_at"
              link
              type="danger"
              @click="releaseHold(String(row.id), String(row.audit_id))"
              >解除保护</el-button
            ></template
          ></el-table-column
        >
      </el-table>
    </template>
    <template v-if="active === 'status' && can('status_read')">
      <el-button :loading="status.loading.value" @click="status.reload()">重新采样</el-button>
      <FeedbackNotice
        v-if="status.error.value"
        :title="status.error.value"
        type="error"
        :closable="false"
      />
      <el-descriptions v-if="status.data.value" border :column="3">
        <el-descriptions-item label="采样时间">{{
          formatDateTime(status.data.value.observed_at)
        }}</el-descriptions-item>
        <el-descriptions-item label="JVM运行时长"
          >{{ Math.floor(status.data.value.uptime_millis / 1000) }}秒</el-descriptions-item
        >
        <el-descriptions-item label="线程数">{{ status.data.value.threads }}</el-descriptions-item>
        <el-descriptions-item label="堆使用"
          >{{ (status.data.value.heap_used_bytes / 1048576).toFixed(1) }} MiB</el-descriptions-item
        >
        <el-descriptions-item label="连接池活跃">{{
          status.data.value.database_pool_active ?? '未采集'
        }}</el-descriptions-item>
        <el-descriptions-item label="连接池空闲">{{
          status.data.value.database_pool_idle ?? '未采集'
        }}</el-descriptions-item>
      </el-descriptions>
      <el-table
        :data="status.data.value?.probes ?? []"
        row-key="name"
        empty-text="点击重新采样获取真实状态"
      >
        <el-table-column prop="name" label="组件" /><el-table-column
          prop="status"
          label="实测状态"
        /><el-table-column prop="duration_millis" label="耗时（毫秒）" /><el-table-column
          prop="detail"
          label="说明"
          min-width="300"
        />
      </el-table>
    </template>
  </AdminPage>
</template>

<style scoped>
.operations-page {
  display: grid;
  gap: 18px;
}
.operations-page h2 {
  font-size: 18px;
  margin-top: 18px;
}
.operations-page header p {
  color: var(--el-text-color-secondary);
}
.filters,
.policy {
  display: flex;
  align-items: end;
  gap: 14px;
  flex-wrap: wrap;
}
.filters label {
  display: grid;
  gap: 7px;
  width: 195px;
  max-width: 100%;
  min-width: 0;
  font-size: 14px;
}
/* 宿主筛选条的固定控件宽度不能越过本页字段，遮挡相邻输入。 */
.filters label :deep(.el-input),
.filters label :deep(.el-select) {
  width: 100%;
  max-width: 100%;
  min-width: 0;
}
.filters input[type='datetime-local'] {
  width: 100%;
  min-width: 0;
  box-sizing: border-box;
  border: 1px solid var(--el-border-color);
  padding: 7px;
  border-radius: 4px;
  background: var(--el-bg-color);
  color: var(--el-text-color-primary);
}
.audit-table :deep(.el-table__cell > .cell) {
  white-space: normal;
}
</style>
