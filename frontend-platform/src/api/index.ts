import {
  createSecurityApi,
  createSecurityAuthApi,
  type SecurityAuthUser
} from '@skillforge/shared/foundation/security/api'
import type { ModelBillingMonthly, MediaPriceUnit } from '@skillforge/shared/types/billing'
export type { ModelBillingMonthly } from '@skillforge/shared/types/billing'
/**
 * 灵知 平台端 API 客户端 —— 统一请求封装
 *
 * 所有平台运营页面的后端数据交互经由此模块。
 * 请求内核（401 单飞刷新 / AbortSignal 透传 / 统一错误提示）位于
 * frontend-shared/src/utils/http.ts（createHttpClient 配置注入）。
 *
 * 所有只读方法均支持末尾可选 RequestOptions：页面级加载可通过 signal 取消
 * 上一次未完成请求，并通过 silent 避免与页内错误重复提示（规范 §3）。
 * opts 可选。
 */

import { createHttpClient, type RequestOptions } from '@skillforge/shared/utils/http'
import { commonZhCN } from '@skillforge/shared/i18n/common.zh-CN'
import { message } from '@skillforge/shared/feedback'
import { useAuthStore } from '../stores/auth'
import router from '../router'
import { createCapabilityCatalogApi } from './capabilities'
import {
  createSkillDraftApi,
  skillDraftSavePayload
} from '@skillforge/shared/capabilities/skillDrafts'
import { createSessionObservationApi } from './sessionObservation'
import { createTrajectoryApi } from './trajectories'
import { createUsageApi } from '@skillforge/shared/integration/usage'
import { createAgentGrantsApi } from './agentGrants'
import { createPlatformIntegrationApi } from './platformIntegration'
import { createInstitutionRelationsApi } from './institutionRelations'
import { createAgentDefinitionsApi } from '@skillforge/shared/integration/agentDefinitions'
import {
  createAgentDeploymentsApi,
  type AgentDeployment
} from '@skillforge/shared/integration/agentDeployments'
import type { PlatformDeploymentInput } from '../institution/agentSetup'
export * from './sessionObservation'
export type {
  CapabilityCatalogApi,
  CapabilityCatalogError,
  CapabilityDefinition,
  SkillCatalogItem,
  CapabilityDefinitionQuery,
  CapabilityDefinitionWire,
  CapabilityStatus,
  CapabilityType,
  CapabilityValidation,
  CapabilityValidationWire,
  CapabilityVersion,
  CapabilityVersionWire,
  CreateCapabilityDefinitionInput,
  CreateCapabilityVersionInput,
  UpdateCapabilityVersionInput,
  SkillPackageImportInput,
  SkillFile
} from './capabilities'
export type {
  SaveSkillDraftInput,
  SkillDraftApi,
  SkillDraftContent,
  SkillDraftPublishResult,
  SkillDraftValidation,
  SkillRequirement,
  SkillValidationIssue
} from '@skillforge/shared/capabilities/skillDrafts'

export type AuthTokens = import('@skillforge/shared/foundation/security/api').SecurityAuthTokens

export interface SkillSaveAndPublishReceipt {
  request_id: string
  status: 'PROCESSING' | 'SUCCEEDED' | 'REJECTED'
  draft_id?: string
  definition_id?: string
  revision?: number
  publication?: { version_id: string; definition_revision: number }
  error_message?: string
  validation?: import('@skillforge/shared/capabilities/skillDrafts').SkillDraftValidation
}

/** 请求客户端：token 管理 + 401 刷新 + 非 2xx 全局提示（规范 §3）。 */
const http = createHttpClient({
  base: '/api/v1',
  accessTokenKey: 'skillforge.platform.access_token',
  refreshTokenKey: 'skillforge.platform.refresh_token',
  authHeader: (token) => ({ 'X-Platform-Token': `Bearer ${token}` }),
  refreshPath: '/platform/auth/refresh',
  isAuthPath: (path) => path.startsWith('/platform/auth/'),
  onUnauthorized: () => {
    useAuthStore().logout(false)
    void router.replace({ path: '/login', query: { redirect: router.currentRoute.value.fullPath } })
  },
  // 鉴权接口错误由页面内联展示（登录页错误区），跳过全局 toast 避免双提示
  onError: (_status, userMessage, path, error) => {
    if (!path.startsWith('/platform/auth/')) {
      const diagnostics = [
        error.technicalDetails,
        error.traceId ? `Trace ID: ${error.traceId}` : undefined
      ]
        .filter(Boolean)
        .join('\n')
      message.error(userMessage, {
        details: diagnostics ? { text: diagnostics } : undefined,
        dedupeKey: `http:${path}:${error.status}`
      })
    }
  }
})

export const { getAccessToken, getSessionId, authSessionKey, saveTokens, clearTokens } = http

const { get, post, put, patch, del } = http

// ---- 类型定义 ----

/** 机构（租户）列表项。 */
export interface TenantItem {
  id: number
  name: string
  slug: string
  plan_code?: string | null
  status: string
  created_at?: string
}
/** 机构详情（含配额覆盖）。 */
export interface TenantCreateReceipt {
  request_id: string
  status: 'PROCESSING' | 'SUCCEEDED' | 'REJECTED' | 'FAILED'
  tenant_id?: string
  tenant_name?: string
  error_message?: string
}

export interface TenantDetail extends TenantItem {
  plan_id?: string | null
  deployment_mode?: string
  member_count: number
  override_quota_api_calls_per_month?: number | null
  override_quota_max_workflows?: number | null
  override_quota_max_knowledge_bases?: number | null
  override_quota_max_members?: number | null
  override_quota_max_channels?: number | null
  override_rate_limit_per_minute?: number | null
  updated_at?: string
}
/** 机构成员（租户账号）。 */
export interface TenantMemberItem {
  id: string
  username: string
  roles: string[]
  email?: string | null
  status: string
  last_login_at?: string | null
  created_at?: string
}
export type {
  AgentTemplateItem,
  AgentTemplateDetail,
  AgentTemplateExecutionConfig,
  AgentTemplateCapability,
  AgentTemplateResourceSlot,
  AgentTemplateDelegationSlot,
  AgentTemplateDefinition,
  AgentTemplateValidation,
  AgentDefinitionImpact,
  AgentDefinitionCreateInput,
  AgentDefinitionUpdateInput
} from '@skillforge/shared/integration/agentDefinitions'
/** 平台审计日志项。 */
export interface AuditLogItem {
  id: number
  action: string
  actor_id?: string | null
  resource_type?: string | null
  resource_id?: string | null
  message?: string | null
  trace_id?: string | null
  payload?: Record<string, unknown> | null
  created_at?: string | null
}
/** 欠费状态机扫描统计。 */
export interface BillingStateProcessResult {
  operation_id: string
  scanned: number
  warning: number
  active: number
  completed_at: string
}
/** 工作流市场审核项。 */
export interface MarketplaceReview {
  id: string
  workflow_name: string
  author: string
  status: string
  revision_at: string
  version: string
  updated_at: string
  reason?: string
  reviewer_id?: string
  reviewed_at?: string
}
export interface MarketplaceReviewDetail {
  workflow_id: string
  name: string
  version: string
  status: string
  updated_at: string
  description: string | null
  author: string | null
  config_schema: Array<Record<string, unknown>> | null
  required_permissions: string[] | null
  tools: Array<Record<string, unknown>> | null
  connector_requirements: Array<Record<string, unknown>> | null
  files: Array<{ name: string; size_bytes: number; content: string | null }>
}
export type ModelKind =
  | 'LLM'
  | 'EMBEDDING'
  | 'RERANK'
  | 'IMAGE_GENERATION'
  | 'VIDEO_GENERATION'
  | 'SPEECH_RECOGNITION'
  | 'SPEECH_SYNTHESIS'
export interface ModelImagePolicy {
  enabled: boolean
  tool_images: boolean
  max_images: number
  max_image_bytes: number
  max_request_bytes: number
  max_dimension: number
  tile_size: number
  base_tokens: number
  tile_tokens: number
  version: string
}
export type ModelReasoningPolicy =
  'DEFAULT' | 'DISABLED' | 'ENABLED' | 'MINIMAL' | 'LOW' | 'MEDIUM' | 'HIGH' | 'XHIGH' | 'MAX'
export interface ModelReasoningOptions {
  options: ModelReasoningPolicy[]
  source: 'OFFICIAL_DOCUMENTATION' | 'PROTOCOL_DEFINITION' | 'UNVERIFIED'
  source_url: string | null
  image_support?: {
    user_images: ModelCapabilitySupport
    tool_images: ModelCapabilitySupport
    defaults: ModelImagePolicy | null
  }
}
export type ModelReasoningSelection = Required<
  Pick<
    ModelWrite,
    | 'provider_id'
    | 'service_id'
    | 'profile_version'
    | 'protocol'
    | 'authentication_scheme'
    | 'endpoint'
    | 'model_id'
  >
>
export type ModelCapabilitySupport = 'SUPPORTED' | 'UNSUPPORTED' | 'UNVERIFIED'
export interface ModelPrefixCapabilities {
  system_updates_in_history: ModelCapabilitySupport
  tool_updates: 'NONE' | 'ADDITION_ONLY' | 'ADD_REMOVE' | 'UNVERIFIED'
  retain_tools_with_tool_choice_none: ModelCapabilitySupport
}
export type ModelMediaPriceUnit = MediaPriceUnit
export interface ModelMediaPricing {
  unit: ModelMediaPriceUnit
  price_per_unit_cents: string | null
  input_price_per_million_cents: string | null
  output_price_per_million_cents: string | null
  video_resolution: '480P' | '720P' | '1080P' | null
}
export interface ModelCachePricing {
  mode: 'CACHE_READ' | 'CACHE_READ_WRITE'
  read_price_per_million_cents: string | null
  write_price_per_million_cents: string | null
}
export type ModelPricingWeekday =
  'MONDAY' | 'TUESDAY' | 'WEDNESDAY' | 'THURSDAY' | 'FRIDAY' | 'SATURDAY' | 'SUNDAY'
export interface ModelTimePricingPolicy {
  time_zone: string
  valid_from: string
  valid_through: string
  peak_weekdays: ModelPricingWeekday[]
  peak_periods: { start: string; end: string }[]
  peak_date_ranges: { from: string; through: string }[]
  off_peak_date_ranges: { from: string; through: string }[]
  peak_multiplier: string | null
  off_peak_multiplier: string | null
  applicable_models: {
    provider_id: string | null
    service_id: string | null
    profile_version: string | null
    model_id: string | null
  }[]
  source_url: string | null
  source_version: string | null
  calendar_source_url: string | null
}
export interface ModelTimePricingPresetWrite {
  name: string
  policy: ModelTimePricingPolicy
}
export interface ModelTimePricingPreset extends ModelTimePricingPresetWrite {
  id: string
  revision: number
  published_revision: number | null
  has_unpublished_changes: boolean
}
export interface ModelWrite {
  prefix_capabilities?: ModelPrefixCapabilities | null
  image_policy?: ModelImagePolicy
  name: string
  kind: ModelKind
  enabled: boolean
  provider_id: string
  endpoint: string
  model_id: string
  api_key?: string
  service_id?: string | null
  profile_version?: string | null
  protocol?: string | null
  authentication_scheme?: string | null
  supported_max_output_tokens?: number | null
  context_window_tokens?: number | null
  max_output_tokens?: number | null
  temperature?: number | null
  reasoning?: ModelReasoningPolicy | null
  timeout_seconds?: number | null
  dimensions?: number | null
  path?: string | null
  input_price_per_million_cents?: string | null
  output_price_per_million_cents?: string | null
  price_per_quantity_cents?: string | null
  currency: string
  media_pricing?: ModelMediaPricing | null
  cache_pricing?: ModelCachePricing | null
  time_pricing?: ModelTimePricingPolicy | null
}
export type ModelProbeStatus = 'PASSED' | 'FAILED' | 'UNKNOWN' | 'NOT_RUN'
export interface ModelProbeCheck {
  status: ModelProbeStatus
  duration_ms: number
  reason_code: string | null
}
export interface ModelCompatibilityReport {
  draft_revision: number
  tested_at: string
  status: ModelProbeStatus
  configuration_acceptance: ModelProbeCheck
  streaming: ModelProbeCheck
  tool_continuation: ModelProbeCheck
  duration_ms: number
  reason_code: string | null
}
/** 模型目录只包含展示和本地筛选所需元数据。 */
export interface ModelSummary {
  id: string
  name: string
  kind: ModelKind
  provider_id: string
  model_id: string
  enabled: boolean
  published_revision: number | null
  has_unpublished_changes: boolean
}
export interface ModelView extends Omit<ModelWrite, 'api_key'> {
  compatibility_report?: ModelCompatibilityReport | null
  published_revision: number | null
  has_unpublished_changes: boolean
  id: string
  revision: number
  credential_configured: boolean
}
export interface ModelTestResult {
  compatibility_report?: ModelCompatibilityReport | null
  ok: boolean
  latency_ms: number
  error?: string | null
  dimensions?: number | null
  ranking?: { index: number; score: number }[] | null
  usage?: { input_tokens: string | null; total_tokens: string | null } | null
}
export type ModelMediaInput =
  | {
      type: 'image'
      prompt: string
      reference_images?: string[]
      size?: string
      count?: number
      negative_prompt?: string
      seed?: number
      prompt_extend?: boolean
      watermark?: boolean
    }
  | {
      type: 'video'
      prompt?: string
      media?: ModelMediaReference[]
      resolution?: string
      ratio?: string
      duration_seconds?: number
      audio?: boolean
      seed?: number
      prompt_extend?: boolean
      watermark?: boolean
    }
  | {
      type: 'transcription'
      file_urls: string[]
      channels?: number[]
      language_hints?: string[]
      diarization?: boolean
      speaker_count?: number
    }
  | { type: 'qwen-transcription'; audio: string; language?: string; enable_itn?: boolean }
  | { type: 'speech'; text: string; voice: string; language?: string }
export interface ModelMediaReference {
  kind:
    | 'FIRST_FRAME'
    | 'LAST_FRAME'
    | 'REFERENCE_IMAGE'
    | 'REFERENCE_VIDEO'
    | 'REFERENCE_AUDIO'
    | 'FILE'
    | 'LINK'
  source: string
}
export interface ModelMediaCommand {
  operation_id: string
  id?: string
  payload?: ModelWrite & { expected_revision?: number }
  input: ModelMediaInput
}
export type ModelMediaStatus =
  'NOT_SUBMITTED' | 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED' | 'UNKNOWN'
export interface ModelMediaAsset {
  id: string
  kind: string
  media_type: string
  size_bytes: number
  name: string
  available: boolean
}
export interface ModelMediaJob {
  id: string
  operation_id: string
  model_config_id: string | null
  model_id: string
  model_revision: number
  kind: ModelKind
  status: ModelMediaStatus
  waiting_stopped: boolean
  provider_task_id?: string | null
  reason_code?: string | null
  created_at: string
  updated_at: string
  usage?: {
    input_tokens?: number | null
    output_tokens?: number | null
    image_count?: number | null
    video_count?: number | null
    characters?: number | null
    duration_seconds?: string | null
  } | null
  assets: ModelMediaAsset[]
}
export interface ModelDiscoveryMetadata {
  messages?: { system_prompt_update?: string | null; tool_update?: string | null } | null
  name?: string | null
  context?: {
    context_window?: number | null
    max_input_tokens?: number | null
    max_output_tokens?: number | null
    max_reasoning_tokens?: number | null
    reasoning_max_input_tokens?: number | null
    reasoning_max_output_tokens?: number | null
  } | null
  reasoning?: { supported_levels?: string[] | null; default_level?: string | null } | null
}
export interface ModelDiscoveredModel {
  model_id: string
  owned_by?: string | null
  metadata?: ModelDiscoveryMetadata | null
}
export interface ModelProviderProtocol {
  protocol: string
  dialect_version: string
  authentication_schemes: string[]
  capabilities: {
    json_object: string
    strict_json_schema: string
    tool_calling: string
    streaming: string
    reasoning: string
    user_images: ModelCapabilitySupport
    tool_images: ModelCapabilitySupport
  }
  image_policy_defaults: ModelImagePolicy | null
}
export interface ModelProviderService {
  service_id: string
  display_name: string
  profile_version: string
  availability: string
  endpoint: string
  custom_endpoint: boolean
  region: 'CHINA_MAINLAND' | 'LOCAL'
  model_kinds: ModelKind[]
  model_discovery?: {
    path: string
    dialect_version: string
    authentication_schemes: string[]
    verification_status: string
  } | null
  protocols: ModelProviderProtocol[]
}
export interface ModelProvider {
  provider_id: string
  display_name: string
  category: string
  services: ModelProviderService[]
}
export interface ModelProviderCatalog {
  providers: ModelProvider[]
}
export interface SystemSettingField {
  key: string
  label: string
  type: 'number' | 'boolean' | 'string' | 'text' | 'select'
  unit?: string | null
  minimum?: number | null
  maximum?: number | null
  choices?: string[]
  secret?: boolean
}
export interface SystemSettingGroup {
  key: string
  label: string
  revision: number
  values: Record<string, unknown>
  derived_budgets: Array<{
    scenario: string
    role: string
    business_models: number
    auxiliary_models: number
    tools: number
    knowledge: number
    total_tokens: number
    active_seconds: number
  }>
  fields: SystemSettingField[]
}
export interface SystemSettingVersion {
  revision: number
  created_at: string
  actor_id?: string | null
  values: Record<string, unknown>
  contract_status: 'SUPPORTED' | 'RETIRED'
}
export type SystemAgentExecutionMode = 'AGENT_RUN' | 'MODEL_TASK'
export type SystemAgentStatus = 'draft' | 'published' | 'disabled' | 'archived'
export interface SystemAgentMount {
  capability_definition_id: string
  capability_type: 'SKILL' | 'TOOL'
  priority: number
  token_budget: number | null
  activation_mode: 'ALWAYS' | 'ON_DEMAND' | null
  tool_deployment_id: string | null
}
export interface SystemAgentConfig {
  name: string
  description: string
  system_prompt: string
  model_config_id: string | null
  timeout_seconds: number
  max_business_model_calls: number
  mounts: SystemAgentMount[]
}
export interface SystemAgentView {
  id: string
  agent_key: string
  purpose: string | null
  execution_mode: SystemAgentExecutionMode
  status: SystemAgentStatus
  revision: number
  published_version: number | null
  draft: SystemAgentConfig
  published: SystemAgentConfig | null
  updated_at: string
}
export interface SystemAgentVersion {
  version: number
  config: SystemAgentConfig
  created_at: string
}
export interface SystemAgentRunView {
  id: string
  agent_id: string
  version: number | null
  debug: boolean
  status: string
  output: string | null
  error_code: string | null
  input_tokens: string | null
  output_tokens: string | null
  duration_ms: number
  created_at: string
}
export interface SystemAgentMountOptions {
  skills: Array<{ id: string; name: string }>
  tools: Array<{ id: string; name: string }>
  deployments: Array<{
    id: string
    name: string
    tool_definition_id: string
    tool_version_id: string
    environment: string
  }>
}
export interface SystemAgentToolDeploymentWrite {
  name: string
  tool_definition_id: string
  provider_type: 'PLATFORM'
  provider_ref: string
  environment: 'TEST' | 'PRODUCTION'
  active: boolean
}
export interface SystemAgentToolDeploymentView extends SystemAgentToolDeploymentWrite {
  tool_version_id: string | null
  published_revision: number | null
  has_unpublished_changes: boolean
  id: string
  revision: number
}

// ---- API 方法 ----
export const api = {
  trajectories: createTrajectoryApi(get),
  agentGrants: createAgentGrantsApi({ get, post }),
  platformIntegration: createPlatformIntegrationApi({ get, post }),
  institutionRelations: createInstitutionRelationsApi({ get, post }),
  agentDeployments: {
    ...createAgentDeploymentsApi({ get, post, put }, '/platform/agent-deployments'),
    receipt: (input: PlatformDeploymentInput, key: string) => {
      const { tenant_id, ...payload } = input
      return post<{ status: 'FOUND' | 'NOT_FOUND'; result: AgentDeployment | null }>(
        '/platform/agent-deployments/receipt/query',
        { tenant_id, payload },
        { headers: { 'Idempotency-Key': key }, silent: true }
      )
    }
  },
  usage: createUsageApi({ get }, '/platform/usage'),
  sessions: createSessionObservationApi(get),
  security: createSecurityApi(get, post, '/admin/foundation/security'),
  auth: {
    ...createSecurityAuthApi(get, post, '/platform/auth'),
    login: (data: { username: string; password: string }) =>
      post<AuthTokens>('/platform/auth/login', data, { anonymous: true }),
    me: (opts?: RequestOptions) => get<SecurityAuthUser>('/platform/auth/me', opts)
  },

  // 机构与机构账号管理
  admin: {
    models: {
      timePricingPresets: {
        list: (opts?: RequestOptions) =>
          get<ModelTimePricingPreset[]>('/admin/models/time-pricing-presets', opts),
        detail: (id: string, opts?: RequestOptions) =>
          get<ModelTimePricingPreset>(
            `/admin/models/time-pricing-presets/detail?id=${encodeURIComponent(id)}`,
            opts
          ),
        create: (data: ModelTimePricingPresetWrite) =>
          post<ModelTimePricingPreset>('/admin/models/time-pricing-presets', data),
        update: (id: string, data: ModelTimePricingPresetWrite & { expected_revision: number }) =>
          put<ModelTimePricingPreset>('/admin/models/time-pricing-presets/update', {
            id,
            payload: data
          }),
        publish: (id: string, expectedRevision: number) =>
          post<ModelTimePricingPreset>('/admin/models/time-pricing-presets/publish', {
            id,
            expected_revision: expectedRevision
          }),
        history: (id: string, opts?: RequestOptions) =>
          get<ModelTimePricingPreset[]>(
            `/admin/models/time-pricing-presets/history?id=${encodeURIComponent(id)}`,
            opts
          )
      },
      publish: (id: string, expectedRevision: number) =>
        post<ModelView>('/admin/models/publish', { id, expected_revision: expectedRevision }),
      summaries: (opts?: RequestOptions) => get<ModelSummary[]>('/admin/models/summaries', opts),
      list: (opts?: RequestOptions) => get<ModelView[]>('/admin/models', opts),
      detail: (id: string, opts?: RequestOptions) =>
        get<ModelView>(`/admin/models/detail?id=${encodeURIComponent(id)}`, opts),
      providers: (opts?: RequestOptions) =>
        get<ModelProviderCatalog>('/admin/models/providers', opts),
      create: (data: ModelWrite) => post<ModelView>('/admin/models', data),
      createWithReceipt: (requestId: string, payload: ModelWrite) =>
        post<{ request_id: string; model_id: string }>(
          '/admin/models/create-with-receipt',
          { request_id: requestId, payload },
          { retryOnUnauthorized: false }
        ),
      createReceipt: (requestId: string) =>
        get<{ request_id: string; model_id: string } | undefined>(
          `/admin/models/create-with-receipt/result?request_id=${encodeURIComponent(requestId)}`
        ),
      update: (id: string, data: ModelWrite & { expected_revision: number }) =>
        put<ModelView>('/admin/models/update', { id: id, payload: data }),
      history: (id: string, opts?: RequestOptions) =>
        get<ModelView[]>(`/admin/models/history?id=${encodeURIComponent(id)}`, opts),
      rollback: (id: string, data: { revision: number; expected_revision: number }) =>
        post<ModelView>('/admin/models/rollback', { id: id, payload: data }),
      test: (data: { id?: string; payload?: ModelWrite }) =>
        post<ModelTestResult>('/admin/models/test', data),
      reasoningOptions: (data: ModelReasoningSelection) =>
        post<ModelReasoningOptions>('/admin/models/reasoning-options', data),
      media: {
        create: (data: ModelMediaCommand) =>
          post<ModelMediaJob>('/admin/models/media/jobs/create', data),
        detail: (id: string) =>
          get<ModelMediaJob>(`/admin/models/media/jobs/detail?id=${encodeURIComponent(id)}`),
        query: (id: string) => post<ModelMediaJob>('/admin/models/media/jobs/query', { id }),
        stopWaiting: (id: string) =>
          post<ModelMediaJob>('/admin/models/media/jobs/stop-waiting', { id }),
        download: (jobId: string, assetId: string) =>
          get<Blob>(
            `/admin/models/media/assets/content?job_id=${encodeURIComponent(jobId)}&asset_id=${encodeURIComponent(assetId)}`,
            { responseType: 'blob' }
          )
      },
      discover: (data: {
        id?: string
        expected_revision?: number
        provider_id: string
        service_id: string
        profile_version: string
        protocol: string
        authentication_scheme: string
        endpoint: string
        api_key?: string
      }) => post<{ models: ModelDiscoveredModel[] }>('/admin/models/discover', data)
    },
    systemSettings: {
      contextPolicy: () => get<{ version: string }>('/admin/system-settings/context-policy'),
      list: (opts?: RequestOptions) => get<SystemSettingGroup[]>('/admin/system-settings', opts),
      update: (
        group: string,
        data: { expected_revision: number; values: Record<string, unknown> }
      ) =>
        put<SystemSettingGroup>('/admin/system-settings/update', { group: group, payload: data }),
      history: (group: string, opts?: RequestOptions) =>
        get<SystemSettingVersion[]>(
          `/admin/system-settings/history?group=${encodeURIComponent(group)}`,
          opts
        ),
      rollback: (group: string, data: { expected_revision: number; target_revision: number }) =>
        post<SystemSettingGroup>('/admin/system-settings/rollback', { group: group, payload: data })
    },
    listTenants: (
      params: { page?: number; limit?: number; status?: string; q?: string } = {},
      opts?: RequestOptions
    ) => {
      const query = new URLSearchParams()
      if (params.page) query.set('page', String(params.page))
      if (params.limit) query.set('limit', String(params.limit))
      if (params.status) query.set('status', params.status)
      if (params.q) query.set('q', params.q)
      return get<{ total: number; items: TenantItem[] }>(
        `/admin/tenants${query.toString() ? `?${query.toString()}` : ''}`,
        opts
      )
    },
    plans: (opts?: RequestOptions) =>
      get<Array<{ plan_code: string; display_name: string }>>('/admin/tenants/plans', opts),
    getTenant: (id: string, opts?: RequestOptions) =>
      get<TenantDetail>(`/admin/tenants/detail?tenantId=${encodeURIComponent(id)}`, opts),
    updateTenant: (id: string, data: Record<string, unknown>) =>
      patch<TenantDetail>('/admin/tenants/update', { tenant_id: id, payload: data }),
    createTenant: (
      requestId: string,
      data: {
        tenant_name: string
        tenant_slug: string
        plan_code?: string
        admin_username: string
        admin_password: string
        admin_email?: string
      },
      opts?: RequestOptions
    ) =>
      post<TenantCreateReceipt>(
        '/admin/tenants/create-with-receipt',
        { request_id: requestId, payload: data },
        { ...opts, retryOnUnauthorized: false }
      ),
    tenantCreateResult: async (requestId: string, opts?: RequestOptions) =>
      (await get<TenantCreateReceipt | undefined>(
        `/admin/tenants/create-with-receipt/result?request_id=${encodeURIComponent(requestId)}`,
        opts
      )) ?? null,
    findTenantBySlug: async (slug: string, opts?: RequestOptions) =>
      (await get<TenantItem | undefined>(
        `/admin/tenants/find-by-slug?slug=${encodeURIComponent(slug)}`,
        opts
      )) ?? null,
    suspendTenant: (id: string) => post<TenantDetail>('/admin/tenants/suspend', { tenant_id: id }),
    activateTenant: (id: string) =>
      post<TenantDetail>('/admin/tenants/activate', { tenant_id: id }),
    listMembers: (
      tenantId: string,
      params: { page?: number; limit?: number; role?: string; status?: string } = {},
      opts?: RequestOptions
    ) => {
      const query = new URLSearchParams()
      if (params.page) query.set('page', String(params.page))
      if (params.limit) query.set('limit', String(params.limit))
      if (params.role) query.set('role', params.role)
      if (params.status) query.set('status', params.status)
      return get<{ total: number; items: TenantMemberItem[] }>(
        `/admin/tenants/members?tenantId=${encodeURIComponent(tenantId)}${query.toString() ? `&${query.toString()}` : ''}`,
        opts
      )
    },
    createMember: (
      tenantId: string,
      data: { username: string; password: string; roles: string[]; email?: string }
    ) => post<TenantMemberItem>('/admin/tenants/members', { tenant_id: tenantId, payload: data }),
    updateMember: (
      tenantId: string,
      memberId: string,
      data: { status?: string; roles?: string[]; email?: string }
    ) =>
      patch<TenantMemberItem>('/admin/tenants/members/update', {
        tenant_id: tenantId,
        member_id: memberId,
        payload: data
      }),
    resetPassword: (tenantId: string, memberId: string, newPassword: string) =>
      post<TenantMemberItem>('/admin/tenants/members/reset-password', {
        tenant_id: tenantId,
        member_id: memberId,
        payload: { new_password: newPassword }
      })
  },

  // 智能体模板管理（平台运营，/api/v1/platform/agent-definitions）
  agentTemplates: {
    ...createAgentDefinitionsApi({ get, post, put }),
    previewContent: (
      id: string,
      revision: number,
      definition: import('@skillforge/shared/integration/agentDefinitions').AgentTemplateDefinition,
      options?: RequestOptions
    ) =>
      post<{
        validation: import('@skillforge/shared/integration/agentDefinitions').AgentTemplateValidation
        impact:
          import('@skillforge/shared/integration/agentDefinitions').AgentDefinitionImpact | null
      }>(
        '/platform/agent-definitions/preview-content',
        { definition_id: id, expected_revision: revision, definition },
        options
      )
  },

  // 系统智能体管理（平台运营，/api/v1/admin/system-agents）
  systemAgents: {
    list: (opts?: RequestOptions) => get<SystemAgentView[]>('/admin/system-agents', opts),
    detail: (id: string, opts?: RequestOptions) =>
      get<SystemAgentView>(`/admin/system-agents/detail?agentId=${encodeURIComponent(id)}`, opts),
    create: (data: {
      agent_key: string
      name: string
      description: string
      execution_mode: SystemAgentExecutionMode
    }) => post<SystemAgentView>('/admin/system-agents', data),
    createWithReceipt: (
      requestId: string,
      payload: {
        agent_key?: string
        name: string
        description: string
        execution_mode: SystemAgentExecutionMode
        system_prompt?: string
        model_config_id?: string
      }
    ) =>
      post<{ request_id: string; agent_id: string }>(
        '/admin/system-agents/create-with-receipt',
        { request_id: requestId, payload },
        { retryOnUnauthorized: false }
      ),
    createReceipt: (requestId: string) =>
      get<{ request_id: string; agent_id: string } | undefined>(
        `/admin/system-agents/create-with-receipt/result?request_id=${encodeURIComponent(requestId)}`
      ),
    update: (data: { agent_id: string; expected_revision: number; config: SystemAgentConfig }) =>
      put<SystemAgentView>('/admin/system-agents/update', data),
    publish: (data: { agent_id: string; expected_revision: number }) =>
      post<SystemAgentView>('/admin/system-agents/publish', data),
    disable: (data: { agent_id: string; expected_revision: number }) =>
      post<SystemAgentView>('/admin/system-agents/disable', data),
    enable: (data: { agent_id: string; expected_revision: number }) =>
      post<SystemAgentView>('/admin/system-agents/enable', data),
    archive: (data: { agent_id: string; expected_revision: number }) =>
      post<SystemAgentView>('/admin/system-agents/archive', data),
    remove: (data: { agent_id: string; expected_revision: number }) =>
      del<void>('/admin/system-agents/delete', data),
    versions: (id: string, opts?: RequestOptions) =>
      get<SystemAgentVersion[]>(
        `/admin/system-agents/versions?agentId=${encodeURIComponent(id)}`,
        opts
      ),
    runs: (id: string, opts?: RequestOptions) =>
      get<SystemAgentRunView[]>(
        `/admin/system-agents/runs?agentId=${encodeURIComponent(id)}`,
        opts
      ),
    debugResult: (id: string, key: string, opts?: RequestOptions) =>
      get<SystemAgentRunView>(
        `/admin/system-agents/debug-result?agentId=${encodeURIComponent(id)}&idempotencyKey=${encodeURIComponent(key)}`,
        opts
      ),
    debug: (data: {
      agent_id: string
      source: 'DRAFT' | 'PUBLISHED'
      expected_revision: number
      input: string
      idempotency_key: string
    }) => post<SystemAgentRunView>('/admin/system-agents/debug', data),
    mountOptions: (opts?: RequestOptions) =>
      get<SystemAgentMountOptions>('/admin/system-agents/mount-options', opts)
  },

  // 平台工具部署（供系统智能体的 TOOL 挂载引用）
  systemAgentTools: {
    publish: (id: string, expectedRevision: number, key: string) =>
      post<SystemAgentToolDeploymentView>(
        '/admin/system-agent-tools/publish',
        { id, expected_revision: expectedRevision },
        { retryOnUnauthorized: false, headers: { 'Idempotency-Key': key } }
      ),
    receipt: (key: string) =>
      get<{ status: 'UNKNOWN' | 'SUCCEEDED'; result: SystemAgentToolDeploymentView | null }>(
        `/admin/system-agent-tools/receipt?request_id=${encodeURIComponent(key)}`,
        { silent: true }
      ),
    history: (id: string) =>
      get<SystemAgentToolDeploymentView[]>(
        `/admin/system-agent-tools/history?id=${encodeURIComponent(id)}`
      ),
    list: (opts?: RequestOptions) =>
      get<SystemAgentToolDeploymentView[]>('/admin/system-agent-tools', opts),
    create: (data: SystemAgentToolDeploymentWrite, key: string) =>
      post<SystemAgentToolDeploymentView>('/admin/system-agent-tools', data, {
        headers: { 'Idempotency-Key': key },
        retryOnUnauthorized: false
      }),
    update: (
      id: string,
      expectedRevision: number,
      payload: SystemAgentToolDeploymentWrite,
      key: string
    ) =>
      put<SystemAgentToolDeploymentView>(
        '/admin/system-agent-tools/update',
        { id, expected_revision: expectedRevision, payload },
        { retryOnUnauthorized: false, headers: { 'Idempotency-Key': key } }
      )
  },

  // 平台运营：审计查询与全租户欠费状态扫描
  billing: {
    monthly: (
      tenantId: string,
      params: { month: string; page?: number; page_size?: number },
      opts?: RequestOptions
    ) => {
      const query = new URLSearchParams({
        tenant_id: tenantId,
        month: params.month,
        page: String(params.page ?? 1),
        page_size: String(params.page_size ?? 20)
      })
      return get<ModelBillingMonthly>(`/platform/billing/monthly?${query}`, opts)
    }
  },
  operations: {
    auditLogs: (
      params: {
        action?: string
        tenant_id?: string
        limit?: number
        page?: number
        actor_id?: string
        resource_type?: string
        from?: string
        to?: string
      } = {},
      opts?: RequestOptions
    ) => {
      const query = new URLSearchParams()
      if (params.action?.trim()) query.set('action', params.action.trim())
      if (params.tenant_id?.trim()) query.set('tenantId', params.tenant_id.trim())
      if (params.limit) query.set('limit', String(params.limit))
      if (params.page) query.set('page', String(params.page))
      if (params.actor_id?.trim()) query.set('actorId', params.actor_id.trim())
      if (params.resource_type?.trim()) query.set('resourceType', params.resource_type.trim())
      if (params.from) query.set('from', params.from)
      if (params.to) query.set('to', params.to)
      return get<{ total: number; items: AuditLogItem[] }>(
        `/platform/audit/logs${query.size ? `?${query}` : ''}`,
        opts
      )
    },
    processBillingStates: (operationId: string, opts?: RequestOptions) =>
      post<BillingStateProcessResult>(
        '/platform/billing/process-states',
        { operation_id: operationId },
        opts
      ),
    billingStateResult: async (operationId: string, opts?: RequestOptions) =>
      (await get<BillingStateProcessResult | undefined>(
        `/platform/billing/result?operationId=${encodeURIComponent(operationId)}`,
        opts
      )) ?? null
  },

  // 工作流市场审核（平台运营）
  marketplace: {
    detail: (workflowId: string, expectedUpdatedAt: string, opts?: RequestOptions) =>
      get<MarketplaceReviewDetail>(
        `/marketplace/reviews/detail?${new URLSearchParams({ workflowId, expectedUpdatedAt })}`,
        opts
      ),
    reviews: (
      params: { status?: string; search?: string; page?: number; limit?: number } = {},
      opts?: RequestOptions
    ) => {
      const query = new URLSearchParams({
        status: params.status ?? 'pending',
        search: params.search ?? '',
        page: String(params.page ?? 1),
        limit: String(params.limit ?? 20)
      })
      return get<{ total: number; page: number; limit: number; items: MarketplaceReview[] }>(
        `/marketplace/reviews?${query}`,
        opts
      )
    },
    approve: (id: string, expectedUpdatedAt: string) =>
      post<void>(
        '/marketplace/reviews/approve',
        { workflow_id: id, expected_updated_at: expectedUpdatedAt },
        { silent: true }
      ),
    reject: (id: string, expectedUpdatedAt: string, reason: string) =>
      post<void>(
        '/marketplace/reviews/reject',
        { workflow_id: id, expected_updated_at: expectedUpdatedAt, reason },
        { silent: true }
      )
  },

  // 平台 Capability Definition/Version 治理（平台资源 tenant_id=NULL）
  capabilities: createCapabilityCatalogApi({ get, post, put }),
  skillDrafts: {
    ...createSkillDraftApi({ get, post, put }, '/admin/capabilities'),
    saveAndPublish: (
      requestId: string,
      definitionRevision: number,
      revision: number,
      payload: ReturnType<typeof skillDraftSavePayload>
    ) =>
      post<SkillSaveAndPublishReceipt>(
        '/admin/capabilities/skill-drafts/save-and-publish',
        { request_id: requestId, revision, definition_revision: definitionRevision, ...payload },
        { retryOnUnauthorized: false, silent: true }
      ),
    saveAndPublishResult: (requestId: string) =>
      get<SkillSaveAndPublishReceipt | undefined>(
        `/admin/capabilities/skill-drafts/save-and-publish/result?request_id=${encodeURIComponent(requestId)}`,
        { silent: true }
      )
  },

  health: (opts?: RequestOptions) =>
    fetch('/health/deps', { signal: opts?.signal }).then(async (res) => {
      if (!res.ok && res.status !== 503) throw new Error(commonZhCN.http.healthCheckFailed)
      const result = await res.json()
      if (
        !result ||
        typeof result.status !== 'string' ||
        !result.deps ||
        typeof result.deps !== 'object' ||
        Array.isArray(result.deps)
      )
        throw new Error(commonZhCN.http.healthCheckFailed)
      const deps: Record<string, { ok: boolean; error?: string }> = {}
      for (const [key, value] of Object.entries(result.deps)) {
        if (
          !value ||
          typeof value !== 'object' ||
          typeof (value as { ok?: unknown }).ok !== 'boolean'
        )
          throw new Error(commonZhCN.http.healthCheckFailed)
        const ok = (value as { ok: boolean }).ok
        // 健康端点的原始异常可能含连接凭据，只向 UI 提供固定安全文案。
        deps[key] = { ok, ...(ok ? {} : { error: commonZhCN.http.healthCheckFailed }) }
      }
      return { status: result.status as string, deps }
    })
}

/** 基础管理页面复用当前宿主的认证、刷新及错误语义。 */
export const foundationRequest = http.request
