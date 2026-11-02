/** 后台双端共同消费的只读轨迹契约；身份、序号与精确计量不转换为 Number。 */
export type ObservationKind =
  | 'ENTRY'
  | 'AGENT'
  | 'STEP'
  | 'LLM'
  | 'TOOL'
  | 'RETRIEVAL'
  | 'MEMORY'
  | 'GUARDRAIL'
  | 'COMPACTION'
  | 'WORKFLOW'

export interface TokenTotals {
  prompt_tokens: string | null
  completion_tokens: string | null
  cached_input_tokens: string | null
  cache_write_input_tokens: string | null
  reasoning_output_tokens: string | null
}

export interface UsageCoverage {
  status: string
  total_attempts: number
  reported_attempts: number
  unknown_attempts: number
  partial_attempts: number
  zero_usage_attempts: number
}

export interface CacheStats {
  availability: string
  paired_attempt_count: number
  eligible_attempt_count: number
  paired_input_tokens: string | null
  paired_cached_input_tokens: string | null
  hit_ratio: string | null
}

export interface ObservationContentRef {
  ref: string | null
  availability: string
}

export interface ObservationAttempt {
  attempt_id: string
  input: ObservationContentRef
  output: ObservationContentRef
  diagnostic_available: boolean
  invocation_id?: string | null
  provider: string | null
  model: string | null
  status: string | null
  started_at: string | null
  finished_at: string | null
  duration_ms: string | null
  report_status: string | null
  totals: TokenTotals | null
}

export interface ObservationNode {
  node_id: string
  parent_node_id: string | null
  kind: ObservationKind
  name: string
  status: string
  source_session_id: string
  root_input_id: string
  input_id: string | null
  run_id: string | null
  action_id: string | null
  step_id: string | null
  iteration: string | null
  trace_id: string | null
  span_id: string | null
  started_at: string | null
  finished_at: string | null
  duration_ms: string | null
  sequence: string
  summary: string | null
  input: ObservationContentRef
  output: ObservationContentRef
  source_event_ids: string[]
  attempts: ObservationAttempt[]
}

export interface TrajectorySummary {
  tenant_id: string
  tenant_name: string | null
  session_id: string
  root_input_id: string
  agent_id: string | null
  agent_name: string | null
  input: string | null
  output: string | null
  status: string
  answer_status: string
  started_at: string | null
  finished_at: string | null
  duration_ms: string | null
  trace_ids: string[]
  models: string[]
  tools: string[]
  step_count: number
  model_calls: number
  tool_calls: number
  totals: TokenTotals | null
  coverage: UsageCoverage | null
}

export interface TrajectoryMessage {
  message_id: string
  role: string
  content: string | null
  root_input_id: string | null
  run_id: string | null
  phase: string | null
  state: string | null
  message_version: string
  created_at: string | null
}

export interface TrajectoryUsageAttempt {
  attempt_id: string
  diagnostic_available: boolean
  source_session_id: string
  source_root_input_id: string | null
  invocation_id: string | null
  trace_node_id: string | null
  run_id: string | null
  provider: string | null
  model: string | null
  call_point: string | null
  report_status: string
  usage_source: string | null
  totals: TokenTotals | null
  ingested_at: string | null
}

export interface TrajectoryUsage {
  tenant_id: string
  session_id: string
  root_input_id: string | null
  read_at: string
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
  attempts: TrajectoryUsageAttempt[]
  next_cursor: string | null
}

export interface TrajectoryCostCoverage {
  status: 'NO_COSTS' | 'COMPLETE' | 'PARTIAL' | 'UNKNOWN'
  total_events: number
  priced_events: number
  unconfirmed_events: number
  missing_model_attempts: number
}

export interface TrajectoryUsageSummary {
  calls: number
  tokens: string | null
  prompt_tokens: string | null
  completion_tokens: string | null
  /** 已确认金额的精确十进制分，允许负调整和小于一分的值。 */
  amount_by_currency: Record<string, string>
  unknown_usage_calls: number
  cost_coverage: TrajectoryCostCoverage
}

export interface TrajectoryDetail {
  summary: TrajectorySummary
  messages: TrajectoryMessage[]
  usage: TrajectoryUsage | null
  usage_summary: TrajectoryUsageSummary
  termination: TrajectoryTermination | null
  snapshot: string
  read_at: string
}

export interface BudgetFailure {
  schema_id: string
  code: string
  phase: 'BUSINESS' | 'CLOSING'
  shortfalls: Array<{
    scope: 'ROOT' | 'RUN'
    dimension:
      | 'ACTIVE_MILLIS'
      | 'BUSINESS_MODELS'
      | 'AUXILIARY_MODELS'
      | 'TOOLS'
      | 'KNOWLEDGE'
      | 'INPUT_TOKENS'
      | 'OUTPUT_TOKENS'
      | 'TOTAL_TOKENS'
    unit: 'ms' | 'count' | 'tokens'
    required: string
    available: string
    deficit: string
    consumed: string
    reserved: string
    limit: string
  }>
  time_calculation: {
    model_calls: string
    model_timeout_ms: string
    model_window_ms: string
    local_processing_ms: string
    required_ms: string
  } | null
}

export interface TrajectoryTermination {
  run_id: string
  reason_code: string
  business_model_attempts: string
  tool_attempts: string
  auxiliary_model_attempts: string
  budget_failure: BudgetFailure | null
}

export interface ObservationPage {
  items: ObservationNode[]
  ancestors: ObservationNode[]
  next_cursor: string | null
  snapshot: string
  coverage: string
  read_at: string
}

export interface TrajectoryContent {
  content_ref: string
  availability: string
  schema_id: string | null
  value: unknown
}

export interface TrajectoryListQuery {
  tenant_id?: string
  trace_id?: string
  session_id?: string
  agent_id?: string
  model?: string
  tool?: string
  status?: string
  started_from?: string
  started_to?: string
  duration_min_ms?: string
  duration_max_ms?: string
  input_keyword?: string
  output_keyword?: string
  page: number
  page_size: 20 | 50 | 100
}

export interface TrajectoryListPage {
  items: TrajectorySummary[]
  page: number
  page_size: number
  total: number
  read_at: string
}
