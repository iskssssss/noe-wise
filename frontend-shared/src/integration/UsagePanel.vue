<script setup lang="ts">
import FeedbackNotice from '../feedback/FeedbackNotice.vue'
import { computed, reactive, ref, useId, watch } from 'vue'
import { EntitySelect, guardFormEnter, useResource, type EntityOption } from '../admin'
import { statusLabel } from '../admin/presentation'
import FieldTip from '../components/FieldTip.vue'
import { vDescribedBy } from '../directives/describedBy'
import { formatDate, formatDateTime, formatTime, toBeijingDateTime } from '../utils/datetime'
import { HttpClientError } from '../utils/http'
import type { UsageApi, UsageEvent, UsageQuery } from './usage'

const props = withDefaults(
  defineProps<{
    api: UsageApi
    identity?: string
    initialTenantId?: string
    fixedTenantId?: string
    showTenant?: boolean
    tenantOptions?: {
      load: (query: string, signal?: AbortSignal) => Promise<EntityOption[]>
      resolve: (id: string) => Promise<EntityOption | undefined>
    }
    tenantLabel?: (id: string | null | undefined) => string
    canEvents?: boolean
    canSummary?: boolean
    canDetails?: boolean
    scopeLabel?: string
  }>(),
  { canEvents: true, canSummary: true, canDetails: true, scopeLabel: '用量查询' }
)
const help = useId()
const dateRange = ref<string[] | null>([])
const advancedOpen = ref(false)
const filters = reactive({
  tenant_id: '',
  integration_id: '',
  group_binding_id: '',
  deployment_id: '',
  agent_id: '',
  session_id: '',
  run_id: '',
  business_scope_id: '',
  channel_id: '',
  end_user_id: '',
  agent_definition_version: ''
})
const applied = ref<UsageQuery>({})
const filterLabels: Record<keyof typeof filters, string> = {
  tenant_id: '机构',
  integration_id: '接入',
  group_binding_id: '群',
  deployment_id: '部署',
  agent_id: '智能体',
  session_id: '会话',
  run_id: '任务',
  business_scope_id: '业务范围',
  channel_id: '渠道',
  end_user_id: '用户',
  agent_definition_version: '定义版本'
}
const advancedKeys = [
  'integration_id',
  'group_binding_id',
  'deployment_id',
  'agent_id',
  'session_id',
  'run_id',
  'business_scope_id',
  'channel_id',
  'end_user_id',
  'agent_definition_version'
] as const
const advancedCount = computed(() => advancedKeys.filter((key) => filters[key]).length)
const appliedLabels = computed(() =>
  Object.entries(applied.value)
    .filter(([key, value]) => value && key !== 'from' && key !== 'to')
    .map(([key, value]) => `${filterLabels[key as keyof typeof filters]}：${value}`)
)
/** 固定机构由工作台显式提供，输入、查询和重置均不能扩大该范围。 */
function currentQuery(): UsageQuery {
  const candidate: UsageQuery = {
    ...filters,
    from: toBeijingDateTime(dateRange.value?.[0]),
    to: toBeijingDateTime(dateRange.value?.[1])
  }
  if (props.fixedTenantId) candidate.tenant_id = props.fixedTenantId
  else if (!props.showTenant) delete candidate.tenant_id
  return candidate
}
const pendingFilters = computed(() =>
  Object.entries(currentQuery()).some(
    ([key, value]) => (value || '') !== (applied.value[key as keyof UsageQuery] || '')
  )
)
const canReadRecords = computed(() => props.canDetails !== false || props.canEvents !== false)
const recordView = ref<'details' | 'events'>('details')
const cursor = ref('')
const cursors = ref<string[]>([])
const validation = ref('')
const cursorConflict = ref(false)
const summary = useResource((signal) =>
  props.api.summary({ ...applied.value }, { signal, silent: true })
)
const details = useResource(async (signal) => {
  const requested = cursor.value
  let result
  try {
    result = await props.api[recordView.value](
      { ...applied.value, cursor: requested },
      { signal, silent: true }
    )
  } catch (reason) {
    // 游标授权或查询绑定失效后只能显式清空重建，不把旧游标自动重试。
    if (reason instanceof HttpClientError && reason.status === 409 && !signal.aborted)
      cursorConflict.value = true
    throw reason
  }
  return { ...result, requested }
})
const rows = computed(() => details.data.value?.items ?? [])
const kindLabels: Record<string, string> = {
  measured: '实际计量',
  usage_report: '用量报告',
  confirmation: '成本确认',
  adjustment: '调整',
  unknown: '待确认用量'
}
const requesterLabels: Record<string, string> = { END_USER: '终端用户', SYSTEM: '系统主体' }
const originLabels: Record<string, string> = {
  native: '平台原生',
  external: '外部接入',
  system: '系统后台'
}
const usageSourceLabels: Record<string, string> = {
  PROVIDER_REPORTED: '供应商报告',
  ESTIMATED: '平台估算',
  UNKNOWN: '待确认'
}
const ownerKindLabels: Record<string, string> = { platform: '平台', tenant: '机构' }
const unitLabels: Record<string, string> = {
  token: 'Token',
  request: '次',
  call: '次',
  document: '篇'
}
const amountLabels: Record<string, string> = {
  effective: '有效',
  confirmed: '已确认',
  unknown: '待确认',
  provisional: '暂估',
  unresolved: '待确认',
  unavailable: '暂无成本',
  unpriced: '未计价',
  priced: '已计价',
  not_applicable: '不适用'
}

/** 查询条件使用提交快照；翻页保留同一条件，读取失败不会重发写入。 */
function query() {
  validation.value = ''
  if (filters.group_binding_id && !/^[1-9]\d*$/.test(filters.group_binding_id)) {
    validation.value = '群标识必须为完整的正整数，不要使用小数、空格或科学计数法。'
    return
  }
  const from = toBeijingDateTime(dateRange.value?.[0])
  const to = toBeijingDateTime(dateRange.value?.[1])
  if (from && to && from >= to) {
    validation.value = '开始时间必须早于结束时间'
    return
  }
  cursorConflict.value = false
  applied.value = currentQuery()
  cursor.value = ''
  cursors.value = []
  summary.data.value = undefined
  summary.error.value = ''
  details.invalidate()
  details.data.value = undefined
  details.error.value = ''
  summary.invalidate()
  if (props.canSummary !== false) void summary.reload()
  if (recordView.value === 'details' && props.canDetails === false && props.canEvents !== false)
    recordView.value = 'events'
  if (recordView.value === 'events' ? props.canEvents !== false : props.canDetails !== false)
    void details.reload()
}
/** 切换记录视图沿用已提交条件，不把尚未查询的输入隐式应用。 */
function changeView() {
  cursorConflict.value = false
  cursor.value = ''
  cursors.value = []
  details.invalidate()
  details.data.value = undefined
  details.error.value = ''
  if (recordView.value === 'events' ? props.canEvents !== false : props.canDetails !== false)
    void details.reload()
}
/** 独立后台任务保留来源作者；其他记录不把入口用户擅自解释为原消息作者。 */
function sourceUserLabel(
  originKind: UsageEvent['origin_kind'],
  systemAgentRunId: UsageEvent['system_agent_run_id'],
  responsibilityAgentId: UsageEvent['responsibility_agent_id']
) {
  return originKind === 'system' && systemAgentRunId && responsibilityAgentId
    ? '来源作者（终端用户）'
    : '入口用户'
}
function requesterLabel(kind: UsageEvent['requester_kind']) {
  return kind ? (requesterLabels[kind] ?? kind) : '未记录'
}
function eventTypeLabel(value: UsageEvent['event_type']) {
  return !value ? '未记录' : value === 'request' ? '请求' : statusLabel(value)
}
function unitLabel(value: UsageEvent['measurement_unit']) {
  return value ? (unitLabels[value] ?? value) : ''
}
/** 长标识只保留尾部，完整值放在悬停说明和展开详情中。 */
function compactRef(value: string | null | undefined) {
  if (!value) return ''
  return value.length <= 12 ? value : `…${value.slice(-6)}`
}
/** 计数只做展示分组，不经过 Number，避免大整数精度丢失。 */
function formatCount(value: string | number) {
  return String(value).replace(/\B(?=(\d{3})+(?!\d))/g, ',')
}
function usageLabel(row: UsageEvent) {
  const parts: string[] = []
  if (row.quantity !== null && row.quantity !== undefined && row.quantity !== '') {
    parts.push(`${formatCount(row.quantity)} ${unitLabel(row.measurement_unit)}`.trim())
  }
  if (row.tokens !== null && row.tokens !== undefined && row.tokens !== '') {
    parts.push(`Token ${formatCount(row.tokens)}`)
  }
  return parts.length ? parts.join(' · ') : row.kind === 'unknown' ? '用量待确认' : '—'
}
function costLabel(row: UsageEvent) {
  const amount =
    row.amount_cents !== null && row.amount_cents !== undefined && row.amount_cents !== ''
      ? `${row.amount_cents} ${row.currency ?? ''}`.trim()
      : row.currency
        ? `— ${row.currency}`
        : '—'
  return `${amount} · ${amountLabels[row.amount_status] ?? row.amount_status}`
}
/** 重置其他条件时保留固定机构；全局入口恢复原有的初始授权范围。 */
function reset() {
  for (const key of Object.keys(filters) as Array<keyof typeof filters>) filters[key] = ''
  filters.tenant_id = props.fixedTenantId || (props.showTenant ? (props.initialTenantId ?? '') : '')
  dateRange.value = []
  advancedOpen.value = false
  query()
}
/** 下一页仅采用已返回的发布游标，不用业务时间构造水位。 */
function next() {
  const value = details.data.value
  if (!value?.has_more || !value.next_cursor || details.loading.value || cursorConflict.value)
    return
  cursors.value.push(value.requested)
  cursor.value = value.next_cursor
  details.data.value = undefined
  void details.reload()
}
function previous() {
  if (!cursors.value.length || details.loading.value || cursorConflict.value) return
  cursor.value = cursors.value.pop()!
  details.data.value = undefined
  void details.reload()
}
watch(
  () => [
    props.api,
    props.identity,
    props.initialTenantId,
    props.fixedTenantId,
    props.showTenant,
    props.canSummary,
    props.canDetails,
    props.canEvents
  ],
  reset,
  {
    immediate: true
  }
)
</script>

<template>
  <section class="usage-panel" :aria-label="scopeLabel">
    <ElAlert
      v-if="!canSummary && !canDetails && !canEvents"
      title="当前身份没有此范围的用量读取权限，请联系管理员授权。"
      type="info"
      :closable="false"
    />
    <div class="usage-context">
      <strong>{{ scopeLabel }}</strong>
      <span>实际调用与供应商成本 · 金额单位：分</span>
    </div>
    <ElForm
      class="usage-filters"
      label-position="top"
      @submit.prevent="query"
      @keydown.capture="guardFormEnter"
    >
      <ElFormItem class="usage-range" label="时间范围（北京时间）" v-described-by="help">
        <ElDatePicker
          v-model="dateRange"
          type="datetimerange"
          value-format="YYYY-MM-DD HH:mm:ss"
          format="YYYY-MM-DD HH:mm:ss"
          start-placeholder="开始时间"
          end-placeholder="结束时间"
          :aria-describedby="help"
        />
      </ElFormItem>
      <ElFormItem v-if="showTenant && !fixedTenantId" label="机构"
        ><EntitySelect
          v-if="tenantOptions"
          v-model="filters.tenant_id"
          :load="tenantOptions.load"
          :resolve="tenantOptions.resolve"
          placeholder="选择机构"
          aria-label="机构"
          v-described-by="help"
          :aria-describedby="help"
        />
        <ElInput
          v-else
          v-model="filters.tenant_id"
          clearable
          v-described-by="help"
          :aria-describedby="help"
        />
      </ElFormItem>
      <details
        class="usage-advanced"
        :open="advancedOpen"
        @toggle="advancedOpen = ($event.target as HTMLDetailsElement).open"
      >
        <summary>
          按历史标识定位<span v-if="advancedCount"> · {{ advancedCount }} 项已填写</span
          ><span class="usage-advanced-hint">接入、群、部署、智能体、会话、任务、用户与版本</span>
        </summary>
        <div class="usage-advanced-fields">
          <ElFormItem label="接入标识"
            ><ElInput v-model="filters.integration_id" clearable :aria-describedby="help"
          /></ElFormItem>
          <ElFormItem label="群标识"
            ><ElInput
              v-model="filters.group_binding_id"
              clearable
              inputmode="numeric"
              v-described-by="help"
              :aria-describedby="help"
          /></ElFormItem>
          <ElFormItem label="部署标识"
            ><ElInput v-model="filters.deployment_id" clearable :aria-describedby="help"
          /></ElFormItem>
          <ElFormItem label="智能体标识"
            ><ElInput
              v-model="filters.agent_id"
              clearable
              v-described-by="help"
              :aria-describedby="help"
          /></ElFormItem>
          <ElFormItem label="会话标识"
            ><ElInput
              v-model="filters.session_id"
              clearable
              v-described-by="help"
              :aria-describedby="help"
          /></ElFormItem>
          <ElFormItem label="任务标识"
            ><ElInput
              v-model="filters.run_id"
              clearable
              v-described-by="help"
              :aria-describedby="help"
          /></ElFormItem>
          <ElFormItem label="业务范围标识"
            ><ElInput
              v-model="filters.business_scope_id"
              clearable
              v-described-by="help"
              :aria-describedby="help"
          /></ElFormItem>
          <ElFormItem label="渠道标识"
            ><ElInput
              v-model="filters.channel_id"
              clearable
              v-described-by="help"
              :aria-describedby="help"
          /></ElFormItem>
          <ElFormItem label="用户标识"
            ><ElInput
              v-model="filters.end_user_id"
              clearable
              v-described-by="help"
              :aria-describedby="help"
          /></ElFormItem>
          <ElFormItem label="定义版本"
            ><ElInput
              v-model="filters.agent_definition_version"
              clearable
              v-described-by="help"
              :aria-describedby="help"
          /></ElFormItem>
        </div>
      </details>
      <div class="usage-query-actions">
        <ElButton
          type="primary"
          native-type="submit"
          :loading="summary.loading.value || details.loading.value"
          >查询</ElButton
        ><ElButton @click="reset">重置</ElButton
        ><span v-if="pendingFilters" class="admin-field-help">条件已修改，点击查询后生效</span>
      </div>
      <FieldTip :id="help" class="usage-filter-help">
        <template v-if="fixedTenantId"
          >查询限定于机构
          {{
            fixedTenantId
          }}，时间和其他历史标识留空表示该机构授权范围内不限制。历史标识用于精确定位既有记录，正常查询无需填写；范围不受当前资源目录限制。</template
        >
        <template v-else
          >时间与机构为常规筛选；其他历史标识用于精确定位既有记录，正常查询无需填写，范围不受当前资源目录限制。</template
        >
      </FieldTip>
    </ElForm>
    <FeedbackNotice v-if="validation" :title="validation" type="error" :closable="false" />
    <FeedbackNotice
      v-if="summary.error.value"
      :title="'汇总读取失败：' + summary.error.value"
      type="error"
      :closable="false"
    />
    <ElButton v-if="summary.error.value" @click="summary.reload">重试汇总</ElButton>
    <div class="usage-results-heading">
      <h3>查询结果</h3>
      <span class="admin-field-help"
        >{{ applied.from || '不限开始时间' }} — {{ applied.to || '不限结束时间' }}</span
      >
    </div>
    <div class="usage-applied-scope" aria-label="已应用查询范围">
      <ElTag v-for="label in appliedLabels" :key="label" type="info" effect="plain">{{
        label
      }}</ElTag>
      <span v-if="!appliedLabels.length" class="admin-field-help">当前身份的全部授权范围</span>
    </div>
    <ElDescriptions v-if="summary.data.value" class="usage-summary" :column="3" border>
      <ElDescriptionsItem label="请求数">{{
        formatCount(summary.data.value.request_count)
      }}</ElDescriptionsItem>
      <ElDescriptionsItem label="实际调用数">{{
        formatCount(summary.data.value.invocation_count)
      }}</ElDescriptionsItem>
      <ElDescriptionsItem label="待确认用量">{{
        formatCount(summary.data.value.unknown_usage_count)
      }}</ElDescriptionsItem>
      <ElDescriptionsItem label="输入 Token">{{
        summary.data.value.prompt_tokens === null
          ? '—'
          : formatCount(summary.data.value.prompt_tokens)
      }}</ElDescriptionsItem>
      <ElDescriptionsItem label="输出 Token">{{
        summary.data.value.completion_tokens === null
          ? '—'
          : formatCount(summary.data.value.completion_tokens)
      }}</ElDescriptionsItem>
      <ElDescriptionsItem label="待确认成本">{{
        formatCount(summary.data.value.unresolved_cost_count)
      }}</ElDescriptionsItem>
      <ElDescriptionsItem label="供应商成本（分）"
        ><span v-for="cost in summary.data.value.costs" :key="cost.currency" class="usage-cost"
          >{{ cost.amount_cents }} {{ cost.currency }}</span
        ><span v-if="!summary.data.value.costs.length">暂无已确认成本</span></ElDescriptionsItem
      >
    </ElDescriptions>
    <FeedbackNotice
      v-if="details.error.value"
      :title="'明细读取失败：' + details.error.value"
      type="error"
      :closable="false"
    />
    <ElButton v-if="details.error.value && !cursorConflict" @click="details.reload"
      >重试明细</ElButton
    ><ElButton v-if="details.error.value" @click="changeView">清空游标并重读当前范围首页</ElButton>
    <ElRadioGroup
      v-if="canReadRecords"
      v-model="recordView"
      aria-label="用量记录视图"
      @change="changeView"
    >
      <ElRadioButton v-if="canDetails !== false" value="details">用量明细</ElRadioButton>
      <ElRadioButton v-if="canEvents !== false" value="events">稳定事件</ElRadioButton>
    </ElRadioGroup>
    <p v-if="canReadRecords" class="admin-field-help usage-record-help">
      {{
        recordView === 'details'
          ? '明细含成本确认和调整记录；汇总按有效计量去重。'
          : '稳定事件按原始发布记录展示，保留计量、确认与调整过程。'
      }}
      展开记录可查看调用时的身份、来源和授权依据。客户价格与钱包扣款由业务系统管理。
    </p>
    <ElTable
      v-if="recordView === 'details' ? canDetails !== false : canEvents !== false"
      class="usage-table"
      :data="rows"
      v-loading="details.loading.value"
      table-layout="fixed"
      row-key="event_id"
      :empty-text="
        details.loading.value
          ? '正在读取用量…'
          : details.error.value
            ? '明细读取失败，请重试'
            : '此查询范围内没有用量，可调整时间或清空标识后重查'
      "
    >
      <ElTableColumn type="expand">
        <template #default="{ row }">
          <div class="usage-source-detail">
            <h3>调用时来源与责任</h3>
            <ElDescriptions :column="2" border>
              <ElDescriptionsItem label="事件标识">{{ row.event_id }}</ElDescriptionsItem>
              <ElDescriptionsItem label="来源类型">{{
                row.origin_kind ? (originLabels[row.origin_kind] ?? row.origin_kind) : '未记录'
              }}</ElDescriptionsItem>
              <ElDescriptionsItem label="机构">{{
                tenantLabel?.(row.tenant_id) ?? row.tenant_id
              }}</ElDescriptionsItem>
              <ElDescriptionsItem label="接入标识">{{
                row.integration_id ?? '—'
              }}</ElDescriptionsItem>
              <ElDescriptionsItem label="部署标识">{{
                row.deployment_id ?? '—'
              }}</ElDescriptionsItem>
              <ElDescriptionsItem label="群标识">{{
                row.group_binding_id ?? '—'
              }}</ElDescriptionsItem>
              <ElDescriptionsItem label="实际请求者类型">{{
                requesterLabel(row.requester_kind)
              }}</ElDescriptionsItem>
              <ElDescriptionsItem label="实际请求者">{{
                row.requester_ref ?? '未记录'
              }}</ElDescriptionsItem>
              <ElDescriptionsItem
                :label="
                  sourceUserLabel(
                    row.origin_kind,
                    row.system_agent_run_id,
                    row.responsibility_agent_id
                  )
                "
                >{{ row.end_user_id ?? '未知' }}</ElDescriptionsItem
              >
              <ElDescriptionsItem label="实际调用主体">{{
                row.actor_ref ?? '—'
              }}</ElDescriptionsItem>
              <ElDescriptionsItem label="执行智能体">{{ row.agent_id ?? '—' }}</ElDescriptionsItem>
              <ElDescriptionsItem label="后台责任智能体">{{
                row.responsibility_agent_id ?? '不适用'
              }}</ElDescriptionsItem>
              <ElDescriptionsItem label="责任主体">{{
                row.responsibility_ref ?? '—'
              }}</ElDescriptionsItem>
              <ElDescriptionsItem label="来源会话">{{
                row.origin_session_id ?? '—'
              }}</ElDescriptionsItem>
              <ElDescriptionsItem label="来源事件">{{
                row.origin_event_id ?? '—'
              }}</ElDescriptionsItem>
              <ElDescriptionsItem label="系统用途运行">{{
                row.system_agent_run_id ?? '不适用'
              }}</ElDescriptionsItem>
              <ElDescriptionsItem label="系统冻结配置">{{
                row.system_agent_configuration_ref ?? '不适用'
              }}</ElDescriptionsItem>
              <ElDescriptionsItem label="系统逻辑调用">{{
                row.system_agent_invocation_ref ?? '不适用'
              }}</ElDescriptionsItem>
            </ElDescriptions>
            <p v-if="row.system_agent_run_id && row.responsibility_agent_id" class="muted">
              独立后台提取由系统主体发起，保留原消息作者与承担责任的机构智能体；原消息作者不等于系统调用者。
            </p>
            <h3>原执行与计量来源</h3>
            <ElDescriptions :column="2" border>
              <ElDescriptionsItem label="会话标识">{{ row.session_id ?? '—' }}</ElDescriptionsItem>
              <ElDescriptionsItem label="任务标识">{{ row.run_id ?? '—' }}</ElDescriptionsItem>
              <ElDescriptionsItem label="业务范围">{{
                row.business_scope_id ?? '—'
              }}</ElDescriptionsItem>
              <ElDescriptionsItem label="渠道标识">{{ row.channel_id ?? '—' }}</ElDescriptionsItem>
              <ElDescriptionsItem label="定义版本">{{
                row.agent_definition_version ?? '未记录'
              }}</ElDescriptionsItem>
              <ElDescriptionsItem label="用量来源">{{
                row.usage_source
                  ? (usageSourceLabels[row.usage_source] ?? row.usage_source)
                  : '未记录'
              }}</ElDescriptionsItem>
              <ElDescriptionsItem label="来源用量事件">{{
                row.source_event_id ?? '—'
              }}</ElDescriptionsItem>
              <ElDescriptionsItem label="调整对应事件">{{
                row.adjustment_of_event_id ?? '不适用'
              }}</ElDescriptionsItem>
              <ElDescriptionsItem label="请求标识">{{ row.request_id ?? '—' }}</ElDescriptionsItem>
              <ElDescriptionsItem label="根输入标识">{{
                row.root_input_id ?? '—'
              }}</ElDescriptionsItem>
              <ElDescriptionsItem label="发布时间">{{
                formatDateTime(row.published_at)
              }}</ElDescriptionsItem>
              <ElDescriptionsItem label="事件修订">{{ row.revision }}</ElDescriptionsItem>
              <ElDescriptionsItem label="调用标识">{{
                row.invocation_id ?? '—'
              }}</ElDescriptionsItem>
              <ElDescriptionsItem label="Attempt 标识">{{
                row.attempt_id ?? '—'
              }}</ElDescriptionsItem>
              <ElDescriptionsItem label="定义所有者"
                >{{
                  row.source_owner_kind
                    ? (ownerKindLabels[row.source_owner_kind] ?? row.source_owner_kind)
                    : '—'
                }}
                {{ row.source_owner_tenant_id ?? '' }}</ElDescriptionsItem
              >
              <ElDescriptionsItem label="定义标识">{{
                row.agent_definition_id ?? '—'
              }}</ElDescriptionsItem>
              <ElDescriptionsItem label="冻结发布"
                >{{ row.agent_revision_id ?? '—' }} /
                {{ row.agent_revision ?? '—' }}</ElDescriptionsItem
              >
              <ElDescriptionsItem label="冻结部署修订">{{
                row.deployment_revision ?? '—'
              }}</ElDescriptionsItem>
              <ElDescriptionsItem label="冻结授权"
                >{{ row.grant_id ?? '—' }} / {{ row.grant_revision ?? '—' }}</ElDescriptionsItem
              >
              <ElDescriptionsItem label="计量单位">{{
                unitLabel(row.measurement_unit) || '—'
              }}</ElDescriptionsItem>
            </ElDescriptions>
            <p class="muted">
              此处保留调用发生时的来源与授权依据，不随当前机构关系或部署切换改写。
            </p>
            <slot name="record-actions" :row="row" />
            <ElTable v-if="row.grant_chain?.length" :data="row.grant_chain" row-key="grant_id">
              <ElTableColumn prop="ordinal" label="顺序" width="80" />
              <ElTableColumn prop="grant_id" label="授权" min-width="180" show-overflow-tooltip />
              <ElTableColumn prop="revision" label="修订" width="80" />
              <ElTableColumn
                prop="grantor_tenant_id"
                label="授予机构"
                min-width="160"
                show-overflow-tooltip
              />
              <ElTableColumn
                prop="target_tenant_id"
                label="获授权机构"
                min-width="160"
                show-overflow-tooltip
              />
              <ElTableColumn
                prop="relation_ref"
                label="关系依据"
                min-width="180"
                show-overflow-tooltip
              />
              <ElTableColumn prop="relation_revision" label="关系修订" width="100" />
            </ElTable>
          </div>
        </template>
      </ElTableColumn>
      <ElTableColumn label="发生时间" :width="showTenant ? 110 : 118"
        ><template #default="{ row }"
          ><div class="usage-cell-primary">{{ formatDate(row.occurred_at) }}</div>
          <div class="usage-cell-secondary">{{ formatTime(row.occurred_at) }}</div></template
        ></ElTableColumn
      >
      <ElTableColumn v-if="showTenant" label="机构" width="120" show-overflow-tooltip
        ><template #default="{ row }">{{
          tenantLabel?.(row.tenant_id) ?? row.tenant_id
        }}</template></ElTableColumn
      >
      <ElTableColumn label="调用" :min-width="showTenant ? 200 : 220">
        <template #default="{ row }">
          <div class="usage-cell-primary">
            {{ requesterLabel(row.requester_kind) }}
            <span v-if="row.agent_id" :title="row.agent_id">
              · 智能体 {{ compactRef(row.agent_id) }}
            </span>
            <span v-else-if="row.actor_ref" :title="row.actor_ref">
              · {{ compactRef(row.actor_ref) }}
            </span>
          </div>
          <div class="usage-cell-secondary">
            {{ eventTypeLabel(row.event_type) }} · {{ kindLabels[row.kind] ?? row.kind }}
          </div>
        </template>
      </ElTableColumn>
      <ElTableColumn label="用量与成本" :min-width="showTenant ? 175 : 180">
        <template #default="{ row }">
          <div class="usage-cell-primary usage-amount">{{ usageLabel(row as UsageEvent) }}</div>
          <div class="usage-cell-secondary usage-amount">{{ costLabel(row as UsageEvent) }}</div>
        </template>
      </ElTableColumn>
    </ElTable>
    <div v-if="canReadRecords" class="actions">
      <ElButton
        :disabled="!cursors.length || details.loading.value || cursorConflict"
        @click="previous"
        >上一页</ElButton
      ><ElButton
        :disabled="!details.data.value?.has_more || details.loading.value || cursorConflict"
        @click="next"
        >下一页</ElButton
      ><span class="muted" role="status" aria-live="polite"
        >当前第 {{ cursors.length + 1 }} 页，{{
          details.loading.value
            ? '正在读取'
            : details.error.value
              ? '读取失败'
              : `显示 ${rows.length} 条记录`
        }}</span
      >
    </div>
  </section>
</template>

<style scoped>
.usage-filters {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 210px), 1fr));
  align-items: start;
  gap: 16px;
}
.usage-filters :deep(.el-form-item) {
  min-width: 0;
  margin-bottom: 0;
}
.usage-filters :deep(.el-input),
.usage-filters :deep(.el-select),
.usage-filters :deep(.el-date-editor) {
  width: 100%;
}
.usage-range,
.usage-filter-help {
  grid-column: 1 / -1;
}
.usage-query-actions {
  display: flex;
  align-items: center;
  align-self: end;
  gap: 8px;
}
.usage-query-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}
.usage-table :deep(th.el-table__cell .cell) {
  white-space: nowrap;
}
.usage-table :deep(.el-table__cell) {
  padding-block: 9px;
}
.usage-cell-primary {
  line-height: 1.45;
}
.usage-cell-secondary {
  margin-top: 2px;
  color: var(--admin-muted);
  font-size: 12px;
  line-height: 1.4;
}
.usage-amount {
  font-variant-numeric: tabular-nums;
}
.usage-cost {
  display: block;
  font-variant-numeric: tabular-nums;
}
.usage-source-detail :deep(.el-descriptions__table) {
  table-layout: fixed;
}
.usage-source-detail :deep(.el-descriptions__cell) {
  overflow-wrap: anywhere;
}
.usage-source-detail {
  padding: 16px;
  overflow-wrap: anywhere;
}
</style>

<style scoped>
.usage-panel {
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-width: 0;
}
.usage-context,
.usage-results-heading,
.usage-applied-scope {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 8px 16px;
}
.usage-context span {
  color: var(--admin-muted);
  font-size: 13px;
}
.usage-results-heading h3,
.usage-record-help {
  margin: 0;
}
.usage-applied-scope :deep(.el-tag) {
  height: auto;
  white-space: normal;
  overflow-wrap: anywhere;
  padding-block: 5px;
}
.usage-filters {
  padding: 20px;
  background: var(--admin-surface);
  border: 1px solid var(--admin-border);
  border-radius: 10px;
}
.usage-advanced,
.usage-query-actions {
  grid-column: 1 / -1;
}
.usage-query-actions {
  flex-wrap: wrap;
}
.usage-advanced summary {
  cursor: pointer;
  font-size: 14px;
  padding: 8px 0;
  min-height: 32px;
}
.usage-advanced summary:focus-visible {
  outline: 2px solid var(--admin-accent);
  outline-offset: 4px;
}
.usage-advanced-hint {
  color: var(--admin-muted);
  margin-left: 12px;
  font-size: 12px;
}
.usage-advanced-fields {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 210px), 1fr));
  gap: 16px;
  padding-top: 12px;
}
</style>
