import type { RequestOptions } from '@skillforge/shared/utils/http'

export type Decimal = string
export type SessionAnswerStatus = 'NONE' | 'IN_PROGRESS' | 'COMPLETED' | 'FAILED'
export type SessionExecutionStatus =
  'IDLE' | 'RUNNING' | 'WAITING' | 'COMPLETED' | 'FAILED' | 'CANCELLED' | 'OUTCOME_UNKNOWN'
export interface SessionListQuery {
  tenant_id?: string
  session_id?: string
  user_id?: string
  agent_id?: string
  team_id?: string
  channel?: string
  status?: string
  started_from?: string
  started_to?: string
  summary?: string
  page: number
  page_size: 20 | 50 | 100
}
export interface SessionFilterOption {
  kind: 'session' | 'user' | 'agent' | 'team' | 'channel'
  value: string
  label: string
  description: string | null
}
export interface SessionSummary {
  tenant_id: string
  tenant_name: string | null
  session_id: string
  user_id: string | null
  user_name: string | null
  target_type: string | null
  agent_id: string | null
  agent_name: string | null
  team_id: string | null
  team_name: string | null
  channel_id: string | null
  channel_type: string | null
  channel_name: string | null
  answer_status: SessionAnswerStatus
  execution_status: SessionExecutionStatus
  summary: string | null
  message_count: number
  root_input_count: number
  created_at: string | null
  updated_at: string | null
  last_activity_at: string | null
}
export interface SessionListResult {
  items: SessionSummary[]
  page: number
  page_size: number
  total: number
  read_at: string
}
export interface SessionDetail extends SessionSummary {
  active_execution_id: string | null
  expired_at: string | null
  first_message_at: string | null
  last_message_at: string | null
  usage_snapshot: TokenTotals | null
}
export interface TokenTotals {
  prompt_tokens: Decimal | null
  completion_tokens: Decimal | null
  cached_input_tokens: Decimal | null
  cache_write_input_tokens: Decimal | null
  reasoning_output_tokens: Decimal | null
}
export interface SessionRound {
  root_input_id: string
  round_number: Decimal
  first_sequence: Decimal
  last_sequence: Decimal
  answer_status: SessionAnswerStatus
  execution_status: SessionExecutionStatus
  started_at: string | null
  updated_at: string | null
  run_count: number
  runs: Array<{
    run_id: string
    loops: Array<{
      loop_id: string | null
      iteration: Decimal | null
      association_status: 'ASSOCIATED' | 'UNASSOCIATED'
      first_sequence: Decimal
      last_sequence: Decimal
      started_at: string | null
      updated_at: string | null
    }>
  }>
}
export interface SessionRoundsResult {
  tenant_id: string
  session_id: string
  items: SessionRound[]
  next_cursor: string | null
  read_at: string
}
export interface SessionMessage {
  message_id: string
  role: string
  content: string | null
  root_input_id: string
  run_id: string | null
  phase: string | null
  state: string | null
  message_version: Decimal | null
  created_at: string | null
}
export interface SessionMessagesResult {
  tenant_id: string
  session_id: string
  root_input_id: string | null
  items: SessionMessage[]
  next_cursor: string | null
  read_at: string
}
export interface CacheStats {
  availability: 'AVAILABLE' | 'UNKNOWN' | 'NOT_APPLICABLE'
  paired_attempt_count: number
  eligible_attempt_count: number
  paired_input_tokens: Decimal | null
  paired_cached_input_tokens: Decimal | null
  hit_ratio: string | null
}
export interface TraceUsageSummary {
  coverage: UsageCoverage
  totals: TokenTotals | null
  cache: CacheStats | null
  attempt_ids: string[]
}
export interface TraceEvent {
  node_id: string
  node_type:
    'INPUT' | 'MODEL' | 'TOOL' | 'WORKFLOW' | 'KNOWLEDGE' | 'DELEGATION' | 'RUNTIME' | 'EVENT'
  parent_node_id: string | null
  session_sequence: Decimal
  event_id: string
  event_type: string
  run_id: string | null
  loop_id: string | null
  iteration: Decimal | null
  association_status: 'ASSOCIATED' | 'UNASSOCIATED'
  execution_id: string | null
  action_id: string | null
  trace_id: string | null
  caused_by_event_id: string | null
  occurred_at: string | null
  content_ref: string | null
  content_availability: 'FULL' | 'REDACTED' | 'NOT_RECORDED'
  status: string | null
  detail: string | null
  usage_summary: TraceUsageSummary | null
}
export interface TraceLoopGroup {
  run_id: string | null
  loop_id: string | null
  iteration: Decimal | null
  association_status: 'ASSOCIATED' | 'UNASSOCIATED'
  first_sequence: Decimal
  last_sequence: Decimal
  node_ids: string[]
  usage_summary: TraceUsageSummary | null
}
export interface SessionTraceResult {
  tenant_id: string
  session_id: string
  root_input_id: string
  sequence_upper_bound: Decimal
  next_cursor: string | null
  items: TraceEvent[]
  loop_groups: TraceLoopGroup[]
  unassociated_usage_summary: TraceUsageSummary | null
  read_at: string
}
/** 每条真实 attempt 独立展示；Token 与版本保留十进制字符串。 */
export type SessionUsageAttempt = SessionUsage['attempts'][number]
export type RecordedContentAvailability =
  'FULL' | 'PARTIAL' | 'NOT_RECORDED' | 'NOT_RETURNED' | 'UNKNOWN'
export interface RecordedContent {
  availability: RecordedContentAvailability
  source: string | null
  schema_id: string | null
  value: unknown
}
export interface ModelAttemptCapabilityCall {
  call_id: string | null
  action_id: string | null
  attempt_id: string | null
  kind: string | null
  title: string | null
  status: string | null
  input: RecordedContent
  output: RecordedContent
}
/** 管理员按 attempt 精确读取；冻结请求材料不是逐字节供应商 wire。 */
export interface ModelAttemptDetail {
  tenant_id: string
  session_id: string
  root_input_id: string | null
  attempt_id: string
  invocation_id: string | null
  run_id: string | null
  action_id: string | null
  loop_id: string | null
  iteration: number | null
  provider: string | null
  model: string | null
  call_point: string | null
  status: string | null
  finish_reason: string | null
  started_at: string | null
  finished_at: string | null
  read_at: string
  usage: SessionUsageAttempt | null
  input: RecordedContent
  reasoning: RecordedContent
  /** 所属Session owner范围的退役事实，不证明此attempt曾返回或丢失推理。 */
  reasoning_history_retention: {
    scope: 'SESSION_OWNER'
    retired_reasoning_event_count: Decimal
    description: string
  } | null
  output: RecordedContent
  decision: RecordedContent
  capability_calls_availability: RecordedContentAvailability
  capability_calls: ModelAttemptCapabilityCall[]
}
export interface TraceContent {
  content_ref: string
  availability: 'FULL' | 'REDACTED' | 'NOT_RECORDED'
  schema_id: string | null
  value: unknown
}
export interface UsageCoverage {
  status: 'NO_CALLS' | 'UNKNOWN' | 'PARTIAL' | 'COMPLETE'
  total_attempts: number
  reported_attempts: number
  unknown_attempts: number
  partial_attempts: number
  zero_usage_attempts: number
}
export interface SessionUsage {
  tenant_id: string
  session_id: string
  root_input_id: string | null
  read_at: string
  next_cursor: string | null
  coverage: UsageCoverage
  totals: TokenTotals | null
  cache: CacheStats | null
  breakdowns: Array<{
    provider: string | null
    model: string | null
    call_point: string | null
    attempt_count: number
    totals: TokenTotals | null
  }>
  attempts: Array<{
    attempt_id: string
    diagnostic_available: boolean
    source_session_id: string
    source_root_input_id: string | null
    invocation_id: string | null
    run_id: string | null
    trace_node_id: string | null
    provider: string | null
    model: string | null
    call_point: string | null
    report_status: 'REPORTED' | 'UNKNOWN' | 'PARTIAL' | 'ZERO'
    usage_source: string | null
    totals: TokenTotals | null
    ingested_at: string | null
  }>
}

function query(params: object) {
  const value = new URLSearchParams()
  Object.entries(params).forEach(([key, item]) => {
    if (item !== undefined && item !== null && item !== '') value.set(key, String(item))
  })
  return value.toString()
}
export function createSessionObservationApi(
  get: <T>(path: string, opts?: RequestOptions) => Promise<T>
) {
  return {
    options: (q = '', opts?: RequestOptions) =>
      get<SessionFilterOption[]>(`/platform/sessions/options?${query({ q })}`, opts),
    modelAttemptDetail: (
      tenantId: string,
      sessionId: string,
      attemptId: string,
      rootInputId?: string,
      opts?: RequestOptions,
      parent?: { session_id: string; root_input_id: string }
    ) =>
      get<ModelAttemptDetail>(
        `/platform/sessions/model-attempt/detail?${query({ tenant_id: tenantId, session_id: sessionId, attempt_id: attemptId, root_input_id: rootInputId, parent_session_id: parent?.session_id, parent_root_input_id: parent?.root_input_id })}`,
        opts
      ),
    list: (input: SessionListQuery, opts?: RequestOptions) =>
      get<SessionListResult>(`/platform/sessions?${query(input)}`, opts),
    detail: (tenantId: string, sessionId: string, opts?: RequestOptions) =>
      get<SessionDetail>(
        `/platform/sessions/detail?${query({ tenant_id: tenantId, session_id: sessionId })}`,
        opts
      ),
    rounds: (
      tenantId: string,
      sessionId: string,
      cursor?: string,
      pageSize?: number,
      rootInputId?: string,
      opts?: RequestOptions
    ) =>
      get<SessionRoundsResult>(
        `/platform/sessions/rounds?${query({ tenant_id: tenantId, session_id: sessionId, cursor, page_size: pageSize, root_input_id: rootInputId })}`,
        opts
      ),
    messages: (
      tenantId: string,
      sessionId: string,
      rootInputId?: string,
      cursor?: string,
      pageSize?: number,
      opts?: RequestOptions
    ) =>
      get<SessionMessagesResult>(
        `/platform/sessions/messages?${query({ tenant_id: tenantId, session_id: sessionId, root_input_id: rootInputId, cursor, page_size: pageSize })}`,
        opts
      ),
    trace: (
      tenantId: string,
      sessionId: string,
      rootInputId: string,
      cursor?: string,
      pageSize?: number,
      nodeId?: string,
      opts?: RequestOptions
    ) =>
      get<SessionTraceResult>(
        `/platform/sessions/trace?${query({ tenant_id: tenantId, session_id: sessionId, root_input_id: rootInputId, cursor, page_size: pageSize, node_id: nodeId })}`,
        opts
      ),
    traceContent: (
      tenantId: string,
      sessionId: string,
      rootInputId: string,
      contentRef: string,
      opts?: RequestOptions
    ) =>
      get<TraceContent>(
        `/platform/sessions/trace/content?${query({ tenant_id: tenantId, session_id: sessionId, root_input_id: rootInputId, content_ref: contentRef })}`,
        opts
      ),
    usage: (
      tenantId: string,
      sessionId: string,
      rootInputId?: string,
      cursor?: string,
      pageSize?: number,
      opts?: RequestOptions
    ) =>
      get<SessionUsage>(
        `/platform/sessions/usage?${query({ tenant_id: tenantId, session_id: sessionId, root_input_id: rootInputId, cursor, page_size: pageSize })}`,
        opts
      )
  }
}

/** Decimal strings stay strings until presentation; never coerce token values through Number. */
export function formatToken(value: Decimal | null | undefined): string {
  return value && /^(0|[1-9]\d*)$/.test(value) ? value.replace(/\B(?=(\d{3})+(?!\d))/g, ',') : '—'
}

/** 比例仅用于展示百分比，不把缺报或非法值解释成零命中。 */
export function formatCacheHitRatio(value: string | null | undefined): string {
  if (value === null || value === undefined || value.trim() === '') return '—'
  const ratio = Number(value)
  return Number.isFinite(ratio) && ratio >= 0 && ratio <= 1 ? (ratio * 100).toFixed(2) + '%' : '—'
}
