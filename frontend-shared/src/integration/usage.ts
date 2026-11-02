import type { RequestOptions } from '../utils/http'

/** 精确金额原样展示，供应商成本与客户钱包价格各自归属。 */
export interface UsageSummary {
  request_count: number | string
  invocation_count: number | string
  prompt_tokens: number | string | null
  completion_tokens: number | string | null
  unknown_usage_count: number | string
  unresolved_cost_count: number | string
  costs: Array<{ currency: string; amount_cents: string }>
}
export interface UsageEvent {
  cursor: string
  event_id: string
  source_event_id: string
  origin_kind: string | null
  actor_ref: string | null
  responsibility_ref: string | null
  deployment_id: string | null
  tenant_id: string
  integration_id: string | null
  kind: string
  revision: number
  adjustment_of_event_id: string | null
  event_type: string
  quantity: string | number | null
  tokens: number | string | null
  prompt_tokens: number | string | null
  completion_tokens: number | string | null
  usage_source: string | null
  measurement_unit: string | null
  image_count: string | null
  video_count: string | null
  characters: string | null
  duration_seconds: string | null
  pricing_model_id: string | null
  pricing_model_revision: number | null
  group_binding_id: string | null
  requester_kind: string | null
  requester_ref: string | null
  origin_session_id: string | null
  origin_event_id: string | null
  responsibility_agent_id: string | null
  system_agent_run_id: string | null
  system_agent_configuration_ref: string | null
  system_agent_invocation_ref: string | null
  amount_status: string
  amount_cents: string | null
  currency: string | null
  invocation_id: string | null
  attempt_id: string | null
  request_id: string | null
  root_input_id: string | null
  source_owner_kind: string | null
  source_owner_tenant_id: string | null
  agent_definition_id: string | null
  agent_revision_id: string | null
  agent_revision: number | null
  deployment_revision: number | null
  grant_id: string | null
  grant_revision: number | null
  grant_chain: Array<{
    ordinal: number
    grant_id: string
    revision: number
    grantor_kind: string
    grantor_tenant_id: string | null
    target_tenant_id: string
    parent_grant_id: string | null
    parent_grant_revision: number | null
    relation_ref: string | null
    relation_revision: number | null
  }>
  agent_id: string | null
  agent_definition_version: string | null
  end_user_id: string | null
  channel_id: string | null
  business_scope_id: string | null
  session_id: string | null
  run_id: string | null
  occurred_at: string
  published_at: string
}
export interface UsagePage {
  items: UsageEvent[]
  next_cursor: string | null
  has_more: boolean
}
export interface UsageQuery {
  integration_id?: string
  deployment_id?: string
  tenant_id?: string
  agent_id?: string
  agent_definition_version?: string
  end_user_id?: string
  session_id?: string
  run_id?: string
  channel_id?: string
  business_scope_id?: string
  group_binding_id?: string
  from?: string
  to?: string
}
type UsageHttp = { get: <T>(path: string, options?: RequestOptions) => Promise<T> }
export interface UsageApi {
  summary: (query: UsageQuery, options?: RequestOptions) => Promise<UsageSummary>
  events: (query: UsageQuery & { cursor?: string }, options?: RequestOptions) => Promise<UsagePage>
  details: (query: UsageQuery & { cursor?: string }, options?: RequestOptions) => Promise<UsagePage>
}
/** 仅注入机构或平台读取入口，不提供 HMAC 凭证或钱包写入。 */
export function createUsageApi(http: UsageHttp, base: string): UsageApi {
  const queryString = (query: UsageQuery & { cursor?: string }) => {
    const params = new URLSearchParams()
    for (const [key, value] of Object.entries(query)) if (value) params.set(key, value)
    return params.size ? `?${params}` : ''
  }
  return {
    events: (query, options) => http.get(`${base}/events${queryString(query)}`, options),
    summary: (query, options) => http.get(`${base}/summary${queryString(query)}`, options),
    details: (query, options) => http.get(`${base}/details${queryString(query)}`, options)
  }
}
