<script setup lang="ts">
import FeedbackNotice from '../feedback/FeedbackNotice.vue'
import { computed, reactive, ref, useId, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { EntitySelect, guardFormEnter, statusLabel, useResource, type EntityOption } from '../admin'
import FieldTip from '../components/FieldTip.vue'
import { formatDateTime, toBeijingDateTime } from '../utils/datetime'
import type { RequestOptions } from '../utils/http'
import {
  formatObservationDuration,
  formatObservationTokens,
  observationTokenTotal
} from '../observation/presentation'
import type {
  TrajectoryListPage,
  TrajectoryListQuery,
  TrajectorySummary
} from '../observation/types'

const props = defineProps<{
  load: (query: TrajectoryListQuery, options?: RequestOptions) => Promise<TrajectoryListPage>
  identity: string
  detailPath: string
  showTenant?: boolean
  loadTenants?: (query: string, signal?: AbortSignal) => Promise<EntityOption[]>
  resolveTenant?: (id: string) => Promise<EntityOption | undefined>
}>()
const helpId = useId()
const advancedOpen = ref(false)
const validation = ref('')
const dateRange = ref<[string, string] | null>(null)
const traceOptions = ref<EntityOption[]>([])
const selectedTrace = ref<EntityOption>()
const traceLoading = ref(false)
const traceError = ref('')
let traceGeneration = 0
let defaultRange: [string, string]
let appliedDefaultRange = true
const filters = reactive({
  tenant_id: '',
  trace_id: '',
  session_id: '',
  agent_id: '',
  model: '',
  tool: '',
  status: '',
  duration_min_ms: '',
  duration_max_ms: '',
  input_keyword: '',
  output_keyword: ''
})
const applied = ref<TrajectoryListQuery>({ page: 1, page_size: 20 })
const displayed = ref<TrajectoryListQuery>()
const result = useResource((signal) => props.load({ ...applied.value }, { signal, silent: true }))
const rows = computed(() => result.data.value?.items ?? [])
const advancedFields = [
  { key: 'session_id', label: 'SessionID' },
  { key: 'agent_id', label: 'Agent ID' },
  { key: 'model', label: '模型' },
  { key: 'tool', label: '工具名' },
  { key: 'duration_min_ms', label: '最短耗时（毫秒）' },
  { key: 'duration_max_ms', label: '最长耗时（毫秒）' }
] as const
const advancedCount = computed(
  () => advancedFields.filter((field) => filters[field.key] !== '').length
)
const statuses = [
  'QUEUED',
  'RUNNING',
  'WAITING',
  'COMPLETED',
  'FAILED',
  'TERMINATED',
  'CANCELLED',
  'OUTCOME_UNKNOWN'
]
const traceChoices = computed(() => {
  const selected = selectedTrace.value
  return selected && !traceOptions.value.some((item) => item.id === selected.id)
    ? [selected, ...traceOptions.value]
    : traceOptions.value
})
function shortIdentifier(value: string) {
  return value.length > 18 ? `${value.slice(0, 8)}…${value.slice(-4)}` : value
}
function traceChoicesFrom(pages: TrajectoryListPage[]) {
  const values = new Map<string, EntityOption>()
  for (const row of pages.flatMap((page) => page.items)) {
    for (const traceId of row.trace_ids) {
      if (!traceId || values.has(traceId)) continue
      values.set(traceId, {
        id: traceId,
        label: row.input || '未记录输入',
        description: [
          row.tenant_name ?? row.tenant_id,
          formatDateTime(row.started_at),
          shortIdentifier(traceId)
        ]
          .filter(Boolean)
          .join(' · ')
      })
    }
  }
  return [...values.values()]
}
async function loadTraceOptions(query = '', signal?: AbortSignal) {
  const current = ++traceGeneration
  const keyword = query.trim()
  if (!keyword && traceOptions.value.length) {
    traceLoading.value = false
    traceError.value = ''
    return
  }
  traceLoading.value = true
  traceError.value = ''
  try {
    const exact = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(keyword)
    const requests = exact
      ? [props.load({ page: 1, page_size: 20, trace_id: keyword }, { signal, silent: true })]
      : keyword
        ? [
            props.load(
              { page: 1, page_size: 20, input_keyword: keyword },
              { signal, silent: true }
            ),
            props.load({ page: 1, page_size: 100 }, { signal, silent: true })
          ]
        : [props.load({ page: 1, page_size: 100 }, { signal, silent: true })]
    const pages = await Promise.all(requests)
    if (current !== traceGeneration) return
    const values = traceChoicesFrom(pages)
    traceOptions.value = keyword
      ? values.filter(
          (item) =>
            item.id.toLowerCase().includes(keyword.toLowerCase()) ||
            item.label.toLowerCase().includes(keyword.toLowerCase())
        )
      : values
  } catch (cause) {
    if (current === traceGeneration)
      traceError.value = cause instanceof Error ? cause.message : 'TraceID 选项读取失败'
  } finally {
    if (current === traceGeneration) traceLoading.value = false
  }
}
function selectTrace(value: string) {
  filters.trace_id = value
  selectedTrace.value = value
    ? (traceOptions.value.find((item) => item.id === value) ?? { id: value, label: value })
    : undefined
}
watch(
  () => result.data.value,
  (page) => {
    if (page && !traceOptions.value.length) traceOptions.value = traceChoicesFrom([page])
  },
  { immediate: true }
)

/** 查询输入保持字符串；翻页只采用上一次提交的筛选快照。 */
function candidate(): TrajectoryListQuery {
  const query: TrajectoryListQuery = {
    ...filters,
    page: 1,
    page_size: applied.value.page_size,
    started_from: toBeijingDateTime(dateRange.value?.[0]),
    started_to: toBeijingDateTime(dateRange.value?.[1])
  }
  if (!props.showTenant) delete query.tenant_id
  return query
}
const pendingFilters = computed(() => {
  const next = candidate()
  return Object.entries(next).some(
    ([key, value]) =>
      key !== 'page' &&
      (value || '') !== ((displayed.value ?? applied.value)[key as keyof TrajectoryListQuery] || '')
  )
})

const resultQueryChanged = computed(
  () =>
    !!displayed.value &&
    Object.entries(applied.value).some(
      ([key, value]) =>
        key !== 'page' &&
        key !== 'page_size' &&
        (value || '') !== (displayed.value?.[key as keyof TrajectoryListQuery] || '')
    )
)

function submit() {
  validation.value = ''
  const useDefaultRange = isDefaultRange()
  if (useDefaultRange) updateDefaultRange()
  const next = candidate()
  if (next.started_from && next.started_to && next.started_from >= next.started_to) {
    validation.value = '开始时间必须早于结束时间。'
    return
  }
  if (
    [next.duration_min_ms, next.duration_max_ms].some(
      (value) => value !== undefined && value !== '' && !/^(0|[1-9]\d*)$/.test(value)
    )
  ) {
    validation.value = '耗时范围请填写非负整数毫秒，不使用小数或科学计数法。'
    return
  }
  if (
    next.duration_min_ms &&
    next.duration_max_ms &&
    BigInt(next.duration_min_ms) > BigInt(next.duration_max_ms)
  ) {
    validation.value = '最短耗时不能大于最长耗时。'
    return
  }
  applied.value = next
  appliedDefaultRange = useDefaultRange
  void loadApplied()
}

/** 只在读取成功时更新结果对应的筛选快照。 */
async function loadApplied() {
  const query = { ...applied.value }
  if (await result.reload()) displayed.value = query
}
const displayedFilters = computed(() => {
  if (!displayed.value) return ''
  const labels: Array<[keyof TrajectoryListQuery, string]> = [
    ['tenant_id', '机构'],
    ['trace_id', 'TraceID'],
    ['session_id', 'SessionID'],
    ['agent_id', 'Agent ID'],
    ['model', '模型'],
    ['tool', '工具名'],
    ['status', '状态'],
    ['duration_min_ms', '最短耗时'],
    ['duration_max_ms', '最长耗时'],
    ['input_keyword', '输入关键词'],
    ['output_keyword', '输出关键词']
  ]
  const values = labels.flatMap(([key, label]) =>
    displayed.value?.[key] ? [`${label}：${displayed.value[key]}`] : []
  )
  const range = [displayed.value.started_from, displayed.value.started_to]
  if (range.some(Boolean))
    values.push(
      `开始时间：${range.map((value) => (value ? formatDateTime(value) : '不限')).join(' 至 ')}`
    )
  return values.length ? values.join(' · ') : '全部条件'
})

function isDefaultRange() {
  return dateRange.value?.[0] === defaultRange?.[0] && dateRange.value?.[1] === defaultRange?.[1]
}
function updateDefaultRange() {
  const through = Date.now()
  defaultRange = [formatDateTime(through - 7 * 24 * 60 * 60 * 1000), formatDateTime(through)]
  dateRange.value = [...defaultRange]
}
function refresh() {
  if (!result.error.value && appliedDefaultRange) {
    const updateInput = isDefaultRange()
    const through = Date.now()
    const range: [string, string] = [
      formatDateTime(through - 7 * 24 * 60 * 60 * 1000),
      formatDateTime(through)
    ]
    applied.value = {
      ...applied.value,
      page: 1,
      started_from: toBeijingDateTime(range[0]),
      started_to: toBeijingDateTime(range[1])
    }
    if (updateInput) {
      defaultRange = range
      dateRange.value = [...range]
    }
  }
  void loadApplied()
}

/** 账号或授权身份切换立即清除旧结果与在途读取，默认查询最近七天。 */
function reset() {
  result.invalidate()
  result.data.value = undefined
  displayed.value = undefined
  result.error.value = ''
  validation.value = ''
  for (const key of Object.keys(filters) as Array<keyof typeof filters>) filters[key] = ''
  selectedTrace.value = undefined
  traceOptions.value = []
  traceError.value = ''
  updateDefaultRange()
  appliedDefaultRange = true
  advancedOpen.value = false
  applied.value = { ...candidate(), page: 1, page_size: 20 }
  if (props.identity) void loadApplied()
}
function page(value: number) {
  if (
    result.loading.value ||
    resultQueryChanged.value ||
    (value === applied.value.page && !result.error.value)
  )
    return
  applied.value = { ...applied.value, page: value }
  void loadApplied()
}
function pageSize(value: number) {
  if (
    resultQueryChanged.value ||
    ![20, 50, 100].includes(value) ||
    (value === applied.value.page_size && !result.error.value)
  )
    return
  applied.value = { ...applied.value, page: 1, page_size: value as 20 | 50 | 100 }
  void loadApplied()
}
function rowKey(row: TrajectorySummary) {
  return JSON.stringify([row.tenant_id, row.session_id, row.root_input_id])
}
function target(row: TrajectorySummary) {
  return {
    path: props.detailPath,
    query: {
      tenant_id: row.tenant_id,
      session_id: row.session_id,
      root_input_id: row.root_input_id
    }
  }
}
/** 汇总只使用已报告的两个主字段，缓存与推理子集不重复累计。 */
function tokens(row: TrajectorySummary) {
  const total = observationTokenTotal(row.totals)
  return total === null ? '未完整报告' : formatObservationTokens(total)
}
function scrollTable(event: KeyboardEvent) {
  if (event.target !== event.currentTarget || !['ArrowLeft', 'ArrowRight'].includes(event.key))
    return
  event.preventDefault()
  const region = event.currentTarget as HTMLElement
  region.scrollLeft += event.key === 'ArrowLeft' ? -160 : 160
}
watch(() => [props.identity, props.showTenant], reset, { immediate: true })
</script>

<template>
  <section class="trajectory-list" aria-label="轨迹检索">
    <ElForm
      class="trajectory-filters"
      label-position="top"
      @submit.prevent="submit"
      @keydown.capture="guardFormEnter"
    >
      <ElFormItem v-if="showTenant" label="机构">
        <EntitySelect
          v-if="loadTenants && resolveTenant"
          v-model="filters.tenant_id"
          :load="loadTenants"
          :resolve="resolveTenant"
          placeholder="搜索并选择机构"
          aria-label="机构"
        />
        <ElInput v-else v-model="filters.tenant_id" clearable aria-label="机构 ID" />
      </ElFormItem>
      <ElFormItem label="TraceID">
        <ElSelect
          :model-value="filters.trace_id || undefined"
          filterable
          remote
          clearable
          :remote-method="loadTraceOptions"
          :loading="traceLoading"
          placeholder="搜索输入内容或选择 TraceID"
          aria-label="TraceID"
          data-testid="trajectory-trace"
          @update:model-value="selectTrace"
          @visible-change="
            (visible: boolean) => {
              if (visible && !traceOptions.length) void loadTraceOptions()
            }
          "
        >
          <ElOption
            v-for="option in traceChoices"
            :key="option.id"
            :value="option.id"
            :label="option.label"
          >
            <span>{{ option.label }}</span>
            <small v-if="option.description" class="trace-option-description">{{
              option.description
            }}</small>
          </ElOption>
        </ElSelect>
      </ElFormItem>
      <ElFormItem label="输入关键词">
        <ElInput v-model="filters.input_keyword" clearable aria-label="输入关键词" />
      </ElFormItem>
      <ElFormItem label="输出关键词">
        <ElInput v-model="filters.output_keyword" clearable aria-label="输出关键词" />
      </ElFormItem>
      <ElFormItem label="执行状态">
        <ElSelect v-model="filters.status" clearable placeholder="全部状态" aria-label="执行状态">
          <ElOption
            v-for="status in statuses"
            :key="status"
            :value="status"
            :label="statusLabel(status)"
          />
        </ElSelect>
      </ElFormItem>
      <ElFormItem class="trajectory-range" label="开始时间范围（北京时间）">
        <ElDatePicker
          v-model="dateRange"
          type="datetimerange"
          format="YYYY-MM-DD HH:mm:ss"
          value-format="YYYY-MM-DD HH:mm:ss"
          start-placeholder="开始时间"
          end-placeholder="结束时间"
          :aria-describedby="helpId"
        />
      </ElFormItem>
      <details
        class="trajectory-advanced"
        :open="advancedOpen"
        @toggle="advancedOpen = ($event.target as HTMLDetailsElement).open"
      >
        <summary>更多筛选{{ advancedCount ? ` · ${advancedCount} 项已填写` : '' }}</summary>
        <div class="trajectory-advanced-fields">
          <ElFormItem v-for="field in advancedFields" :key="field.key" :label="field.label">
            <ElInput
              v-model="filters[field.key]"
              clearable
              :aria-label="field.label"
              :inputmode="field.key.startsWith('duration_') ? 'numeric' : undefined"
            />
          </ElFormItem>
        </div>
      </details>
      <div class="trajectory-actions">
        <ElButton type="primary" native-type="submit" :loading="result.loading.value"
          >查询</ElButton
        >
        <ElButton @click="reset">重置</ElButton>
        <span v-if="pendingFilters" class="muted">条件已修改，点击查询后生效</span>
      </div>
      <FeedbackNotice
        v-if="traceError"
        class="trajectory-help"
        :title="'TraceID 选项读取失败：' + traceError"
        type="error"
        :closable="false"
      />
      <FieldTip :id="helpId" class="trajectory-help">
        多个条件同时匹配；TraceID、SessionID、模型与工具名精确查找，输入与输出按关键词匹配。默认查询近七天，耗时包含主执行的排队和等待，未记录耗时不匹配耗时范围。
      </FieldTip>
    </ElForm>

    <FeedbackNotice
      v-if="validation"
      :title="validation"
      type="error"
      :closable="false"
      role="alert"
    />
    <FeedbackNotice
      v-if="result.error.value"
      :title="result.error.value"
      type="error"
      :closable="false"
      role="alert"
    />
    <div class="trajectory-results">
      <span role="status" aria-live="polite">{{
        result.loading.value
          ? '正在读取轨迹…'
          : result.data.value
            ? `找到 ${result.data.value.total} 条轨迹`
            : '尚未取得轨迹列表'
      }}</span>
      <span v-if="result.data.value?.read_at" class="muted"
        >读取时间 {{ formatDateTime(result.data.value.read_at) }}</span
      >
      <ElButton :loading="result.loading.value" :disabled="!identity" @click="refresh">{{
        result.error.value ? '重新读取' : '刷新'
      }}</ElButton>
    </div>
    <p v-if="result.data.value" class="muted" role="status">
      当前结果条件：{{ displayedFilters }}。
    </p>
    <p v-if="result.error.value && result.data.value" class="muted">
      当前保留上次成功读取的结果，本次刷新未完成；未成功的筛选或页码尚未生效。
    </p>
    <div
      class="trajectory-table-region"
      role="region"
      aria-label="轨迹查询结果，可使用左右方向键横向滚动"
      tabindex="0"
      @keydown="scrollTable"
    >
      <ElTable
        :data="rows"
        :row-key="rowKey"
        v-loading="result.loading.value"
        table-layout="fixed"
        class="trajectory-table"
        :empty-text="
          result.loading.value
            ? '正在读取轨迹…'
            : result.error.value
              ? '轨迹读取失败，请使用上方重新读取'
              : '当前查询范围没有轨迹，可清空关键词或调整时间范围后查询'
        "
      >
        <ElTableColumn label="输入与结果" width="240">
          <template #default="{ row }">
            <RouterLink :to="target(row as TrajectorySummary)" class="trajectory-input">{{
              row.input || '输入未记录'
            }}</RouterLink>
            <div class="trajectory-output">{{ row.output || '尚无公开结果' }}</div>
            <div class="trajectory-root" :title="row.root_input_id">
              <template v-if="showTenant">{{ row.tenant_name ?? row.tenant_id }} · </template>
              <code>{{ shortIdentifier(row.root_input_id) }}</code>
            </div>
          </template>
        </ElTableColumn>
        <ElTableColumn label="执行" width="170">
          <template #default="{ row }">
            <div>{{ row.agent_name ?? row.agent_id ?? '未记录' }}</div>
            <div class="muted">{{ row.models.length ? row.models.join('、') : '模型未记录' }}</div>
            <ElTag
              size="small"
              :type="
                ['FAILED', 'OUTCOME_UNKNOWN', 'UNKNOWN'].includes(row.status) ? 'warning' : 'info'
              "
              >{{ statusLabel(row.status) }}</ElTag
            >
            <div class="muted">{{ row.step_count }} Step · {{ row.model_calls }} 模型</div>
            <div class="muted">{{ row.tool_calls }} 工具调用</div>
          </template>
        </ElTableColumn>
        <ElTableColumn label="用量 / 时间" width="120" align="right">
          <template #default="{ row }">
            <div class="numeric">{{ tokens(row as TrajectorySummary) }}</div>
            <span class="muted">{{
              row.coverage?.status === 'COMPLETE'
                ? '完整报告'
                : row.coverage?.status === 'NO_CALLS'
                  ? '无模型调用'
                  : '部分或未知用量'
            }}</span>
            <div class="numeric">{{ formatObservationDuration(row.duration_ms) }}</div>
            <span class="muted">{{ formatDateTime(row.started_at) }}</span>
          </template>
        </ElTableColumn>
        <ElTableColumn label="操作" width="90" fixed="right">
          <template #default="{ row }"
            ><RouterLink :to="target(row as TrajectorySummary)">查看轨迹</RouterLink></template
          >
        </ElTableColumn>
      </ElTable>
    </div>
    <ElPagination
      :key="result.error.value ? 'failed' : 'ready'"
      class="trajectory-pagination"
      layout="total, sizes, prev, pager, next"
      :page-sizes="[20, 50, 100]"
      :current-page="result.data.value?.page ?? applied.page"
      :page-size="result.data.value?.page_size ?? applied.page_size"
      :total="result.data.value?.total ?? 0"
      :disabled="result.loading.value || !result.data.value || resultQueryChanged"
      @current-change="page"
      @size-change="pageSize"
    />
  </section>
</template>

<style scoped>
.trajectory-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-width: 0;
}
.trajectory-filters {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 220px), 1fr));
  align-items: start;
  gap: 16px;
}
.trajectory-filters :deep(.el-form-item) {
  min-width: 0;
  margin-bottom: 0;
}
.trajectory-filters :deep(.el-input),
.trajectory-filters :deep(.el-select),
.trajectory-filters :deep(.el-date-editor) {
  width: 100%;
}
.trajectory-range,
.trajectory-advanced,
.trajectory-actions,
.trajectory-help {
  grid-column: 1 / -1;
}
.trajectory-advanced summary {
  padding-block: 8px;
  min-height: 32px;
  cursor: pointer;
}
.trajectory-advanced-fields {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 220px), 1fr));
  gap: 16px;
  padding-top: 12px;
}
.trajectory-actions,
.trajectory-results {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 16px;
}
.trajectory-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}
.trajectory-results .el-button {
  margin-left: auto;
}
.trajectory-table-region {
  max-width: 100%;
  overflow-x: auto;
}
.trajectory-table :deep(.el-table__cell > .cell) {
  white-space: normal;
}
.trajectory-table-region:focus-visible,
.trajectory-advanced summary:focus-visible {
  outline: 2px solid var(--admin-accent);
  outline-offset: 3px;
}
.trajectory-input,
.trajectory-output {
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  overflow: hidden;
  overflow-wrap: anywhere;
}
.trajectory-output {
  margin-top: 4px;
}
.trajectory-root {
  display: block;
  margin-top: 4px;
  overflow-wrap: anywhere;
}
.trace-option-description {
  display: block;
  color: var(--admin-muted);
  font-size: 12px;
  overflow-wrap: anywhere;
}
.muted,
.trajectory-root {
  color: var(--admin-muted);
  font-size: 13px;
}
.numeric {
  font-variant-numeric: tabular-nums;
  overflow-wrap: anywhere;
}
.trajectory-pagination {
  max-width: 100%;
  overflow-x: auto;
}
</style>
