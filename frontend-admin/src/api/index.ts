import {
  createSecurityApi,
  createSecurityAuthApi,
  type SecurityAuthUser
} from '@skillforge/shared/foundation/security/api'
import { MEMBER_DETAILS_PAGE_LIMIT } from '@skillforge/shared/components/teamWork'
import type { RunBudget } from '@skillforge/shared/components/chatRunBudget'
import type { ChatSteeringState } from '@skillforge/shared/components/chatSteering'
import type { ChatRunDeliveryStatus } from '@skillforge/shared/components/chatDelivery'
import type { ChatGoalStatus } from '@skillforge/shared/components/chatGoal'
import type { ChatCommand } from '@skillforge/shared/components/chatCommands'
import type { ChatProcessItem } from '@skillforge/shared/components/chatProcess'
export type {
  ChatProcessItem,
  ChatProcessStatus,
  ChatProcessType
} from '@skillforge/shared/components/chatProcess'
import type { ModelBillingMonthly } from '@skillforge/shared/types/billing'
export type { ModelBillingMonthly } from '@skillforge/shared/types/billing'
import type {
  ChatCollaborationState,
  PlanReviewPendingInteraction
} from '@skillforge/shared/types/chatCollaboration'
export type {
  ChatCollaborationState,
  ChatCollaborationUpdate,
  PlanReviewDecision,
  PlanReviewPendingInteraction,
  PlanReviewResponse
} from '@skillforge/shared/types/chatCollaboration'
/**
 * 灵知 API 客户端 —— 统一请求封装
 *
 * 所有前端页面的后端数据交互经由此模块，
 * 提供类型安全、错误处理、请求/响应拦截。
 *
 * 请求内核（401 单飞刷新 / AbortSignal 透传 / 统一错误提示）位于
 * frontend-shared/src/utils/http.ts（createHttpClient 配置注入）。
 */

import type { KnowledgeRebuildPolicy } from './knowledgeRebuild'
import { createHttpClient, type RequestOptions } from '@skillforge/shared/utils/http'
import { commonZhCN } from '@skillforge/shared/i18n/common.zh-CN'
import { createChatTransport, type ChatResult } from '@skillforge/shared/utils/stream'
import { message } from '@skillforge/shared/feedback'
import { useAuthStore } from '../stores/auth'
import router from '../router'
import { createWorkflowDraftApi } from './workflowDrafts'
import { createWorkflowTestsApi } from './workflowTests'
import { createTrajectoryApi } from './trajectories'
import {
  createSkillDraftApi,
  skillDraftSavePayload
} from '@skillforge/shared/capabilities/skillDrafts'
import { createConversationTaskApi } from './conversationTasks'
import { createUsageApi } from '@skillforge/shared/integration/usage'
import { createAgentDeploymentsApi } from './agentDeployments'
import { createInstitutionManagementApi } from './institutionManagement'
import { createAgentDefinitionsApi } from '@skillforge/shared/integration/agentDefinitions'
import {
  createCapabilityCatalogApi,
  createCapabilityMountApi,
  mapCapabilityMountSet,
  type CapabilityMount,
  type CapabilityMountSetWire
} from './capabilityMounts'
export type {
  CapabilityDefinition,
  CapabilityDeployment,
  CapabilityStatus,
  CapabilityMount,
  CapabilityMountRequest,
  CapabilityMountSet,
  CapabilityVersion,
  SkillVersionSummary,
  CapabilityValidation,
  CapabilityCatalogError,
  CapabilityDefinitionQuery,
  ToolDeploymentProviderType,
  ToolDeploymentEnvironment,
  ToolDeploymentStatus,
  CreateCapabilityDefinitionInput,
  CreateCapabilityVersionInput,
  UpdateCapabilityVersionInput,
  CreateCapabilityDeploymentInput,
  SkillPackageImportInput,
  SkillFile,
  PlatformTool,
  CapabilityMountStatus,
  CapabilityType,
  KnowledgeBindingMode,
  KnowledgeMountConfig,
  SkillActivationMode,
  SkillMountConfig,
  ToolMountConfig,
  WorkflowMountConfig,
  WorkflowMountOption
} from './capabilityMounts'
export type {
  WorkflowBuilderSnapshot,
  WorkflowDefinitionDiffEntry,
  WorkflowDraft,
  WorkflowPublication,
  WorkflowDraftDiff,
  WorkflowDraftList,
  WorkflowDraftListItem,
  WorkflowPublishResult,
  WorkflowValidationProblem,
  BuilderWorkflowInstance,
  BuilderWorkflowInstanceList,
  WorkflowCapabilityBinding,
  WorkflowChildBinding,
  WorkflowAgentBinding,
  WorkflowInstallationBindings,
  WorkflowInstallResult
} from './workflowDrafts'
export type {
  SaveSkillDraftInput,
  SkillDraftApi,
  SkillDraftContent,
  SkillDraftPublishResult,
  SkillDraftValidation,
  SkillRequirement,
  SkillValidationIssue
} from '@skillforge/shared/capabilities/skillDrafts'

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

export type AuthTokens = import('@skillforge/shared/foundation/security/api').SecurityAuthTokens

/** 请求客户端：token 管理 + 401 刷新 + 非 2xx 全局提示（规范 §3）。 */
const http = createHttpClient({
  base: '/api/v1',
  accessTokenKey: 'skillforge.access_token',
  refreshTokenKey: 'skillforge.refresh_token',
  authHeader: (token) => ({ Authorization: `Bearer ${token}` }),
  refreshPath: '/auth/refresh',
  isAuthPath: (path) => path.startsWith('/auth/'),
  onUnauthorized: () => {
    useAuthStore().logout(false)
    void router.replace({ path: '/login', query: { redirect: router.currentRoute.value.fullPath } })
  },
  // 鉴权接口错误由页面内联展示（登录/改密码各有页内错误区），跳过全局 toast 避免双提示
  onError: (_status, userMessage, path, error) => {
    if (!path.startsWith('/auth/')) {
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

export const { get, post, put, patch, del } = http

// ---- 类型定义 ----
/** 会话详情聚合（右侧会话详情面板数据）。 */
export interface SessionDetail {
  end_user_id: string | null
  target_agent_id: string | null
  closed: boolean
  session: {
    session_id: string
    summary?: string | null
    message_count: number
    updated_at: string
    agent_id?: number | null
    team_id?: string | null
  }
  agent?: {
    id: number
    agent_key: string
    name: string
    status: string
  } | null
  executions: Array<{
    execution_id: string
    workflow_id: string
    workflow_name?: string | null
    state: string
    created_at: string
    updated_at: string
  }>
  tasks: Array<{
    execution_id: string
    node_id: string
    task_type: string
    status: string
    hitl_expires_at?: string | null
    created_at: string
  }>
  callbacks: Array<{
    callback_id: string
    execution_id?: string | null
    workflow_id?: string | null
    action: string
    status: string
    created_at: string
  }>
}

export interface MarketWorkflow {
  workflow_id: string
  name: string
  type: string
  version: string
  description: string
  author: string
  category: string
  install_count: number
  price_type: string
  tags?: string[]
}
export interface MarketSearch {
  total: number
  page: number
  page_size: number
  items: MarketWorkflow[]
}

/** 渠道绑定摘要项（ChannelResponse.BindingItem）。 */
export interface ChannelBindingItem {
  id: string
  name: string
  priority?: number
  traffic_percentage?: number
  is_default?: boolean
}

export interface ChannelItem {
  api_key_issued: boolean
  id?: string
  name: string
  type: string
  status: string
  channel_token?: string
  config?: Record<string, unknown>
  ui_config?: Record<string, unknown>
  agent_bindings?: ChannelBindingItem[]
}

/** 创建渠道时唯一一次返回明文令牌。 */
export interface CreateChannelResult {
  id: string
  name: string
  type: string
  status: string
  channel_token: string
  config: Record<string, unknown>
  ui_config: Record<string, unknown>
}

export interface ChannelOnboardingResult {
  request_id: string
  channel_id: string
  channel?: CreateChannelResult
}

/** API Key 签发/轮换结果（api_key / api_secret 明文仅此一次返回）。 */
export interface ApiKeyIssueResult {
  channel_id: string
  api_key: string
  api_secret: string
}

/** 渠道 Agent 绑定请求项。 */
export interface ChannelAgentBindingPayload {
  agent_id: string
  priority?: number
  is_default?: boolean
  traffic_percentage?: number
}

export interface ConnectorInstanceItem {
  id: string
  instance_id: string
  name: string
  base_url: string
  executor_type?: string
  capabilities?: string[]
  status: string
  auth_type?: string
  auth_credential?: string
}
export interface ConnectorInstanceDetail extends ConnectorInstanceItem {
  auth_type?: string
  auth_credential?: string
  vault_secret_path?: string
  headers?: Record<string, unknown>
  visibility?: string
  created_at?: string
  updated_at?: string
}
/** 机构管理面创建 MCP 连接器所需的配置。 */
export interface McpConnectorInstanceInput {
  executor_type: 'mcp'
  name: string
  base_url: string
  auth_type?: string
  auth_credential?: string
  vault_secret_path?: string
  headers?: Record<string, unknown>
}
/** MCP 连接器局部编辑请求；省略的配置保留原值，能力和状态仅在更新时提交。 */
export interface UpdateMcpConnectorInstanceInput extends Partial<McpConnectorInstanceInput> {
  capabilities?: string[]
  status?: string
}
/** MCP server 暴露的工具项（tools/list 发现结果）。 */
export interface McpToolItem {
  name: string
  qualified_name?: string
  title?: string | null
  description?: string
  input_schema?: Record<string, unknown> | null
  output_schema?: Record<string, unknown> | null
  icons?: Array<Record<string, unknown>>
  annotations?: Record<string, unknown> | null
  unsupported_reason?: string | null
}
export interface HandoffCreateReceipt {
  id: string
  status: string
  session_id: string
  command_id: string
}
export interface HandoffTicket {
  id: string
  user: string
  reason: string
  priority: number
  status: string
  summary?: string
  session_id?: string
  assigned_to?: string
  revision: number
  resolution_note?: string
}
export interface HandoffActionReceipt {
  command_id: string
  ticket_id: string
  action: 'accept' | 'resolve'
  status: 'assigned' | 'resolved'
}
export interface HandoffReply {
  command_id: string
  ticket_id: string
  operator_name: string
  content: string
  status: 'accepted' | 'committed' | 'rejected'
  source_event_id?: string
  error_code?: string
}
export interface HandoffDetail {
  ticket: HandoffTicket
  messages: Array<{
    message_id: string
    role: string
    content: string
    created_at: string
    human_author?: string
  }>
  has_more: boolean
  next_before_message_id?: string
  replies: HandoffReply[]
}
export interface KnowledgeUploadReceipt {
  request_id: string
  status: 'PROCESSING' | 'SUCCEEDED' | 'REJECTED'
  document?: { document_id: string; chunks: number; source_version: string } | null
  error_message?: string | null
}

export interface KnowledgeBaseCreateInput {
  name: string
  description?: string
  indexing_mode?: string
  retrieval_mode?: string
  rerank_enabled?: boolean
  chunk_size?: number
  chunk_overlap?: number
  top_k?: number
  score_threshold_enabled?: boolean
  score_threshold?: number
  vector_weight?: number
}
export interface KnowledgeBase {
  id: string
  name: string
  description?: string
  status: string
  document_count?: number
  chunk_count?: number
}

export interface KnowledgeBaseDetail extends KnowledgeBase {
  /** 当前 active published revision 主键；没有已发布 revision 时为空。 */
  active_revision_id?: string | null
  chunk_size?: number | null
  chunk_overlap?: number | null
  indexing_mode?: string | null
  effective_indexing_mode?: string | null
  retrieval_mode?: string | null
  top_k?: number | null
  score_threshold_enabled?: boolean | null
  score_threshold?: number | null
  vector_weight?: number | null
  rerank_enabled?: boolean | null
  created_at?: string
}
export interface KnowledgeDocumentItem {
  id: string
  kb_id: string
  title: string
  source_type: string
  status: string
  chunk_count: number
  /** 文档摄取版本，用于 revision manifest。 */
  source_version?: string | null
  created_at: string
}
export interface KnowledgeDocumentDetail extends KnowledgeDocumentItem {
  content: string
  original_filename?: string | null
  extension?: string | null
  size_bytes?: number | null
  parsed_by?: string | null
}
export interface KnowledgeBaseRevision {
  id: string
  kb_id: string
  revision: number
  status: 'draft' | 'ready' | 'published' | 'archived' | string
  source_versions: string[]
  /** 服务端关联时的已发布 revision 不可变 CapabilityVersion 身份。 */
  capability_version_id?: string | null
  created_at?: string | null
  ready_at?: string | null
  published_at?: string | null
}
export interface KnowledgePrepareAndPublishReceipt {
  request_id: string
  status: 'PROCESSING' | 'SUCCEEDED' | 'REJECTED'
  revision?: KnowledgeBaseRevision
  error_message?: string
}
export interface KnowledgeChunkItem {
  id: string
  chunk_index: number
  content: string
  has_embedding: boolean
  source_version: string
  created_at: string
}
export interface KnowledgeSearchParams {
  kb_id: string
  query: string
  source_version?: string
  top_k?: number
  retrieval_mode?: string
  score_threshold_enabled?: boolean
  score_threshold?: number
  vector_weight?: number
  rerank_enabled?: boolean
}

/** 机构端对话工作区可选终端用户。 */
export interface ChatEndUserItem {
  id: string
  display_name?: string | null
  status?: string | null
}
export interface AgentTemplate {
  id: string
  template_key: string
  name: string
  version: string
  description?: string
  icon_url?: string | null
  definition: Record<string, unknown>
}
type AgentTemplateWire = Omit<AgentTemplate, 'id'> & { id: string | number }

/** 后端 Long 在 JSON 中为数字；选择器及请求标识在客户端统一使用字符串。 */
function mapAgentTemplate(wire: AgentTemplateWire): AgentTemplate {
  return { ...wire, id: String(wire.id) }
}
export interface DelegationConfig {
  enabled: boolean
  target_agent_ids: string[]
}
export interface AgentItem {
  revision: number
  published_revision_id: number | null
  published_revision: number | null
  has_draft_changes: boolean
  role_description?: string | null
  icon_url?: string | null
  id: string
  agent_key: string
  name: string
  template_id: string | null
  template_name?: string | null
  status: string
  created_at?: string
  delegation_config: DelegationConfig
}
/** 保存当前配置并发布的原请求；恢复时正文和请求号保持不变。 */
export interface AgentSaveAndPublishCommand {
  agent_id: string
  request_id: string
  payload: {
    name: string
    role_description: string
    system_prompt: string
    model_config_id: string
    execution_config: AgentExecutionConfig
    delegation_config: DelegationConfig
    config: Record<string, unknown>
  }
}
export interface AgentSaveAndPublishReceipt {
  request_id: string
  agent_id: string
  publication_id: string
  revision: number
  published_at: string
}
export interface AgentPublication {
  revision_id: number
  revision: number
  published_at: string
  name: string
  current: boolean
}
type AgentItemWire = Omit<AgentItem, 'id' | 'template_id'> & {
  id: string | number
  template_id: string | number | null
}

function mapAgentItem(wire: AgentItemWire): AgentItem {
  return {
    ...wire,
    id: String(wire.id),
    template_id:
      wire.template_id === null || wire.template_id === undefined ? null : String(wire.template_id)
  }
}
/** 工作室编排配置；发布时服务端固化每个协作成员的配置、模型与能力快照摘要。 */
export interface Team {
  id: string
  name: string
  description: string
  status: 'draft' | 'published' | 'disabled'
  revision: number
  members: Array<{
    agent_id: string
    agent_version: string
    name: string
    role_description?: string
    capabilities?: Array<{ type: string; name: string; description: string }>
  }>
  primary_agent_id: string
}
export interface TeamSaveAndPublishReceipt {
  request_id: string
  status: 'PROCESSING' | 'SUCCEEDED' | 'REJECTED'
  team?: Team
  error_message?: string
}
export type ChatTarget =
  { agent_id: string; team_id?: never } | { team_id: string; agent_id?: never }
/** 写入只接受协作成员标识，人设、名称和能力由服务端捕获。 */
export interface TeamDraft {
  revision: number
  name: string
  description: string
  status: Team['status']
  members: Array<{ agent_id: string }>
  primary_agent_id: string
}
export interface AgentDetail extends AgentItem {
  /** 空值表示继承机构默认 LLM 模型。 */
  model_config_id?: string | null
  system_prompt?: string | null
  role_description?: string | null
  execution_config?: AgentExecutionConfig | null
  llm_provider_id?: string | null
  llm_service_id?: string | null
  llm_profile_version?: string | null
  llm_protocol?: string | null
  llm_authentication_scheme?: string | null
  llm_dialect_version?: string | null
  config?: Record<string, unknown>
  published_at?: string | null
  delegation_config: DelegationConfig
  mountSetGeneration: number
  mounts: CapabilityMount[]
}

type AgentDetailWire = {
  published_revision_id: number | null
  published_revision: number | null
  has_draft_changes: boolean
  revision: number
  id: string | number
  agent_key: string
  name: string
  template_id: string | number | null
  template_name?: string | null
  status: string
  created_at?: string
  system_prompt?: string | null
  role_description?: string | null
  icon_url?: string | null
  execution_config?: AgentExecutionConfig | null
  llm_provider_id?: string | null
  llm_service_id?: string | null
  llm_profile_version?: string | null
  llm_protocol?: string | null
  llm_authentication_scheme?: string | null
  llm_dialect_version?: string | null
  model_config_id?: string | number | null
  config?: Record<string, unknown>
  published_at?: string | null
  delegation_config: DelegationConfig
  mount_set_generation: number
  mounts: CapabilityMountSetWire['mounts']
}

function mapAgentDetail(wire: AgentDetailWire): AgentDetail {
  const mountSet = mapCapabilityMountSet({
    generation: wire.mount_set_generation,
    mounts: wire.mounts
  })
  return {
    id: String(wire.id),
    agent_key: wire.agent_key,
    revision: wire.revision,
    published_revision_id: wire.published_revision_id,
    published_revision: wire.published_revision,
    has_draft_changes: wire.has_draft_changes,
    name: wire.name,
    template_id:
      wire.template_id === null || wire.template_id === undefined ? null : String(wire.template_id),
    template_name: wire.template_name,
    status: wire.status,
    created_at: wire.created_at,
    system_prompt: wire.system_prompt,
    role_description: wire.role_description,
    icon_url: wire.icon_url,
    execution_config: wire.execution_config,
    llm_provider_id: wire.llm_provider_id,
    llm_service_id: wire.llm_service_id,
    llm_profile_version: wire.llm_profile_version,
    llm_protocol: wire.llm_protocol,
    llm_authentication_scheme: wire.llm_authentication_scheme,
    llm_dialect_version: wire.llm_dialect_version,
    model_config_id:
      wire.model_config_id === null || wire.model_config_id === undefined
        ? null
        : String(wire.model_config_id),
    config: wire.config,
    published_at: wire.published_at,
    delegation_config: {
      enabled: wire.delegation_config.enabled,
      target_agent_ids: wire.delegation_config.target_agent_ids.map(String)
    },
    mountSetGeneration: mountSet.generation,
    mounts: mountSet.mounts
  }
}
export interface AgentExecutionConfig {
  max_business_model_calls?: number | null
  max_tool_calls?: number | null
  max_knowledge_calls?: number | null
  max_total_tokens?: number | null
  max_wall_time_seconds?: number | null
}

export interface AgentTemplateInstallRequest {
  template_id: string
  name?: string
  request_id: string
  resource_bindings: Array<{ slot_key: string; mount: Record<string, unknown> }>
  delegation_bindings: Array<{ slot_key: string; target_agent_id: string }>
}
/** 用量账本最小单元；金额以分为单位，精确金额字段由服务端按需返回。 */
export interface BillingRecordItem {
  id: number
  event_type: string
  provider?: string | null
  model?: string | null
  quantity: number
  image_count?: string | null
  video_count?: string | null
  characters?: string | null
  duration_seconds?: string | null
  pricing_model_id?: string | null
  pricing_model_revision?: number | null
  tokens?: number | null
  total_amount?: string | null
  total_amount_cents?: number | null
  currency?: string | null
  usage_event_kind?: string | null
  amount_status?: string | null
  event_id?: string | null
  request_id?: string | null
  root_input_id?: string | null
  run_id?: string | null
  execution_id?: string | null
  scope_id?: string
  scope_type?: string
  occurred_at?: string
  created_at: string
}
export interface BillingRequestItem {
  request_id: string
  occurred_at: string
  session_id?: string | null
  end_user_id?: number | null
  channel_id?: string | null
  agent_id?: number | null
  run_id?: string | null
  scope_id?: string | null
  scope_type?: string | null
  llm_calls: number
  tokens: number
  total_amount_cents?: number | null
  currency?: string | null
  confirmed_cost: string
  missing_cost_count: number
  usage_status: string
}
export interface BillingRequestDetail {
  request_id: string
  occurred_at: string
  summary: {
    confirmed_cost: string
    missing_cost_count: number
    usage_status: string
    llm_calls: number
    tokens: number
    prompt_tokens: number
    completion_tokens: number
    total_amount_cents?: number | null
    currency?: string | null
  }
  items: BillingRecordItem[]
}
export interface ServicePrincipal {
  id: number
  principal_id: string
  integration_id: string
  api_key_prefix: string
  scopes: string[]
  status: string
  credential_version: number
  created_at: string
}
export type ServicePrincipalStatusAction = 'suspend' | 'revoke'
export interface ServicePrincipalStatusReceipt {
  request_id: string
  principal_id: string
  action: ServicePrincipalStatusAction
  changed: boolean
}
/** 明文凭证仅在签发或轮换响应中出现；页面不得缓存或重新请求。 */
export interface IssuedServicePrincipal {
  principal: ServicePrincipal
  api_key: string
  signing_secret: string
}
export interface WebhookCreateReceipt {
  request_id: string
  subscription_id: string
}
export interface WebhookPauseReceipt {
  request_id: string
  subscription_id: string
  changed: boolean
}
export interface WebhookSubscription {
  id: number
  session_id?: string | null
  endpoint_url: string
  status: string
  created_at: string
}
export interface AuditLog {
  id: number
  action: string
  actor_id?: string | null
  resource_type?: string | null
  resource_id?: string | null
  message?: string | null
  trace_id?: string | null
  payload?: Record<string, unknown> | null
  created_at: string
}
export interface UsageReconciliationConfig {
  provider?: string | null
  endpoint?: string | null
  api_key_configured: boolean
  api_key_header?: string | null
  response_schema: Record<string, unknown>
}
export interface KnowledgeWriteProposal {
  proposal_id: string
  target: string
  source_event_id: string
  bundle_id: string
  payload: Record<string, unknown>
  status: string
}
export type MemoryScope = 'SESSION' | 'USER_AGENT' | 'USER'

export interface MemoryFact {
  ref: { tenant_id: { value: string }; fact_id: string }
  scope: MemoryScope
  type: { value: string }
  subject: string
  predicate: string
  object: { schema_id: string; canonical_json: string }
  source_event_ids: Array<{ value: string }>
  revision: number
  confidence: number
  importance: number
  decay_score: number
  review_required: boolean
  status: string
  created_at: string
}

// 未确认的治理提交保留原请求键；同一正文恢复不因页面再次点击建立新操作。
const pendingMemoryMutationKeys = new Map<string, string>()
async function memoryMutation(path: string, body: unknown): Promise<MemoryFact> {
  const signature = JSON.stringify({ path, body })
  let key = pendingMemoryMutationKeys.get(signature)
  if (!key) {
    key = crypto.randomUUID()
    pendingMemoryMutationKeys.set(signature, key)
  }
  const result = await post<MemoryFact>(path, body, { headers: { 'Idempotency-Key': key } })
  if (pendingMemoryMutationKeys.get(signature) === key) pendingMemoryMutationKeys.delete(signature)
  return result
}

/** GET /memories 可选过滤；空白字段表示不限制。 */
export interface MemoryListQuery {
  status?: string
  scope?: MemoryScope
  type?: string
  agent_id?: string
  end_user_id?: string | number
  limit?: number
}

export interface MemoryPageQuery extends MemoryListQuery {
  q?: string
  cursor?: string
}

export interface MemoryPageResult {
  items: MemoryFact[]
  next_cursor: string | null
}

function memoryListQuery(query: MemoryPageQuery): string {
  const parts: string[] = []
  const append = (key: string, value: string | number | undefined) => {
    if (value === undefined || value === '') return
    parts.push(`${key}=${encodeURIComponent(String(value))}`)
  }
  append('status', query.status)
  append('scope', query.scope)
  append('type', query.type)
  append('agent_id', query.agent_id)
  append('end_user_id', query.end_user_id)
  append('limit', query.limit)
  append('q', query.q)
  append('cursor', query.cursor)
  return parts.length ? `?${parts.join('&')}` : ''
}

/** 问题到结果的脱敏轨迹投影；仅用于只读调试工作台，不包含 checkpoint 或凭据。 */
export interface TraceView {
  root_input_id: string
  timeline: TraceEntry[]
  usage_summary: TraceUsageSummary
  unresolved_items: string[]
}
export interface TraceEntry {
  source_domain: string
  source_event_id: string
  event_type: string
  status?: string | null
  trace_id?: string | null
  started_at?: string | null
  ended_at?: string | null
  usage_refs: string[]
  sanitized_detail: Record<string, unknown>
}
export interface TraceUsageSummary {
  calls: number
  tokens: string | null
  prompt_tokens: string | null
  completion_tokens: string | null
  amount_by_currency: Record<string, number>
  unknown_usage_calls: number
}
export interface HITLCardAction {
  id?: string
  type?: string
  label: string
  value?: unknown
}
export interface HITLCard {
  card_id: string
  type: 'confirm' | 'quota_exceeded' | string
  title?: string
  body?: string
  message?: string
  actions?: HITLCardAction[]
  [key: string]: unknown
}

export type {
  ChatStreamRequest,
  ChatSubmitOptions,
  StreamEvent
} from '@skillforge/shared/utils/stream'

export interface ChatHistoryMessage {
  message_id: string
  message_version: number
  references: import('@skillforge/shared/components/chatReferences').ChatReferenceView[]
  run_id?: string | null
  role: 'user' | 'assistant' | 'system'
  content: string
  created_at: string
  metadata: Record<string, unknown> & {
    attachments?: Array<Pick<SessionAttachment, 'id' | 'name'>>
  }
}
/** 会话内可在后续输入中引用的受控文件元数据。 */
export interface SessionAttachment {
  id: string
  name: string
  input_path: string
  size: number
  sha256: string
  expires_at: string
}

export interface RuntimePendingInteraction {
  interaction_type?: 'tool_input' | 'user_input' | 'workflow_input'
  tool_wait?: { action_id: string; generation: string }
  action_id?: string
  invocation_id?: string
  pending_id?: string
  wake_id?: string
  wait_revision?: number | string
  execution_id: string
  checkpoint_revision?: number | string
  run_revision?: number | string
  pending_ref?: string
  question?: string
  suspend_ref?: string
  expected_revision?: number | string
  workflow_execution_id?: string
  /** 原 Workflow 挂起的准确业务版本，十进制字符串。 */
  execution_revision?: string
  resume_action_key?: string
  card?: HITLCard
}

export interface ChatHistoryResponse {
  event_cursor: string | null
  has_more?: boolean
  next_before_message_id?: string
  session_id: string
  messages: ChatHistoryMessage[]
  /** 会话存在挂起执行（waiting_hitl）时的交互卡恢复信息（execution_id + card），无挂起为空数组 */
  pending_interactions: (RuntimePendingInteraction | PlanReviewPendingInteraction)[]
  /** 当前会话的 Plan 协作投影。 */
  collaboration: ChatCollaborationState
  /** 按 input_id 关联发起用户消息、按持久顺序恢复的公开执行过程。 */
  processes?: ChatProcessItem[]
  /** 会话节点执行历史（跨执行聚合）：卡数据按 message_id 关联，历史渲染精确取卡 */
  activities?: Array<{
    node_id: string
    node_type?: string | null
    status: string
    card?: Record<string, unknown> | null
    message_id?: string | null
    started_at: string
    render?: {
      root_run_id?: string | null
      root_input_id?: string | null
      updated_sequence?: number | null
      reason?: string | null
    } | null
  }>
}
/** 机构侧仅可见的平台启用模型的安全摘要，绝不包含连接或凭据。 */
export interface ModelSummary {
  id: string
  name: string
  kind: 'LLM' | 'EMBEDDING' | 'RERANK'
  model_id: string
  max_output_tokens?: number | null
  reasoning?: 'DEFAULT' | 'ENABLED' | 'DISABLED' | null
  dimensions?: number | null
  revision: number
}
export interface ModelSelections {
  llm_model_config_id: string | null
  embedding_model_config_id: string | null
  rerank_model_config_id: string | null
}

export type {
  ArtifactDiffRequest,
  ArtifactDiffResult,
  ArtifactInputReference,
  ArtifactPreviewTicket,
  ArtifactProvenance,
  ArtifactRecoveryRequest,
  ArtifactRecoveryResult,
  ChatArtifact
} from '@skillforge/shared/components/chatArtifact'
import type {
  ArtifactDiffRequest,
  ArtifactDiffResult,
  ArtifactPreviewTicket,
  ArtifactRecoveryRequest,
  ArtifactRecoveryResult,
  ChatArtifact
} from '@skillforge/shared/components/chatArtifact'
import {
  normalizeArtifactRecoveryResult,
  normalizeChatArtifact
} from '@skillforge/shared/components/chatArtifact'

function streamHeaders(lastEventId?: string): Headers {
  const headers = http.authHeaders({ Accept: 'text/event-stream' })
  if (lastEventId) headers.set('Last-Event-ID', lastEventId)
  return headers
}

async function openChatEvents(
  sessionId: string,
  lastEventId: string | undefined,
  signal?: AbortSignal
): Promise<Response> {
  const init = () => ({
    method: 'GET',
    headers: streamHeaders(lastEventId),
    signal
  })
  let response = await fetch(
    `${http.base}/chat/events?${new URLSearchParams({ session_id: sessionId })}`,
    init()
  )
  if (response.status === 401) {
    const token = await http.refreshAccessToken()
    if (token)
      response = await fetch(
        `${http.base}/chat/events?${new URLSearchParams({ session_id: sessionId })}`,
        init()
      )
  }
  return response
}

/** 对话按“建会话 → 一次提交 → GET 订阅”执行，重连只建立订阅。 */
const chatTransport = createChatTransport({
  createSession: (body, signal) => post<{ session_id: string }>('/chat/sessions', body, { signal }),
  submit: (body, signal) => post<ChatResult>('/chat', body, { signal, retryOnUnauthorized: false }),
  fetchEvents: openChatEvents,
  fetchHistory: (sessionId, signal) =>
    get<ChatHistoryResponse>(`/chat/history?${new URLSearchParams({ session_id: sessionId })}`, {
      signal,
      silent: true
    })
})

const capabilityMountApi = createCapabilityMountApi({ get, post, put, del })
const capabilityCatalogApi = createCapabilityCatalogApi({ get, post, put, del })
const skillDraftApi = createSkillDraftApi({ get, post, put }, '/capabilities')

// ---- API 方法 ----
export const api = {
  trajectories: createTrajectoryApi(get),
  agentDeployments: createAgentDeploymentsApi({ get, post, put }),
  institutionManagement: createInstitutionManagementApi({ get, post }),
  agentDefinitions: {
    ...createAgentDefinitionsApi({ get, post, put }, '/tenant/agent-definitions'),
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
        '/tenant/agent-definitions/preview-content',
        { definition_id: id, expected_revision: revision, definition },
        options
      )
  },
  headquartersUsage: createUsageApi({ get }, '/tenant/usage'),
  usage: createUsageApi({ get }, '/admin/usage'),
  conversationTasks: createConversationTaskApi({ get, post }),
  security: createSecurityApi(get, post, '/tenant/foundation/security'),
  auth: {
    ...createSecurityAuthApi(get, post, '/auth'),
    login: (data: { tenant_slug: string; username: string; password: string }) =>
      post<AuthTokens>('/auth/login', data, { anonymous: true }),
    me: () => get<SecurityAuthUser>('/auth/me'),
    changePassword: (data: { old_password: string; new_password: string }) =>
      post<{ status: string }>('/auth/password', data)
  },
  /** Builder 设计态 API：草稿、验证与发布，不能经此路径运行 Workflow。 */
  workflowDrafts: createWorkflowDraftApi({ get, post, put }),
  workflowTests: createWorkflowTestsApi({ get, post }),

  // Agent
  agents: {
    templates: async () => {
      const response = await get<{ total: number; items: AgentTemplateWire[] }>('/agents/templates')
      return { ...response, items: response.items.map(mapAgentTemplate) }
    },
    templateDetail: async (templateId: string) =>
      mapAgentTemplate(
        await get<AgentTemplateWire>(
          `/agents/templates/detail?templateId=${encodeURIComponent(templateId)}`
        )
      ),
    list: async () => {
      const response = await get<{ total: number; items: AgentItemWire[] }>('/agents')
      return { ...response, items: response.items.map(mapAgentItem) }
    },
    get: (id: string) =>
      get<AgentDetailWire>(`/agents/detail?agentId=${encodeURIComponent(id)}`).then(mapAgentDetail),
    create: (data: {
      name: string
      /** 模板 ID，可选（null/缺省为直接创建，无模板初始值） */
      agent_key?: string
      system_prompt?: string
      /** 空字符串表示显式恢复继承；缺省表示不修改。 */
      model_config_id?: string
      config?: Record<string, unknown>
    }) => post<AgentItemWire>('/agents', data).then(mapAgentItem),
    createWithReceipt: (
      requestId: string,
      payload: { name: string; agent_key?: string; system_prompt: string }
    ) =>
      post<{ request_id: string; agent_id: string }>(
        '/agents/create-with-receipt',
        { request_id: requestId, payload },
        { retryOnUnauthorized: false }
      ),
    creationResult: (requestId: string) =>
      get<{ request_id: string; agent_id: string } | undefined>(
        `/agents/create-with-receipt/result?request_id=${encodeURIComponent(requestId)}`
      ),
    installTemplate: async (data: AgentTemplateInstallRequest) => {
      const response = await post<{ agent: AgentItemWire; mounts: CapabilityMountSetWire }>(
        '/agents/install-template',
        data
      )
      return { ...response, agent: mapAgentItem(response.agent) }
    },
    installationResult: (requestId: string) =>
      get<{ request_id: string; agent_id: string } | undefined>(
        `/agents/install-template/result?request_id=${encodeURIComponent(requestId)}`
      ),
    update: (id: string, data: Record<string, unknown>) =>
      patch<AgentItemWire>(`/agents/update`, { agent_id: id, payload: data }).then(mapAgentItem),
    saveAndPublish: (command: AgentSaveAndPublishCommand) =>
      post<AgentSaveAndPublishReceipt>('/agents/save-and-publish', command, {
        retryOnUnauthorized: false
      }),
    saveAndPublishResult: (agentId: string, requestId: string) =>
      get<AgentSaveAndPublishReceipt | undefined>(
        `/agents/save-and-publish/result?agentId=${encodeURIComponent(agentId)}&requestId=${encodeURIComponent(requestId)}`
      ),
    revisions: (id: string) =>
      get<{ total: number; items: AgentPublication[] }>(
        `/agents/revisions?agentId=${encodeURIComponent(id)}`
      ),
    revisionDetail: (id: string, revision: number) =>
      get<AgentDetailWire>(
        `/agents/revisions/detail?agentId=${encodeURIComponent(id)}&revision=${revision}`
      ).then(mapAgentDetail),
    restoreRevision: (id: string, revision: number) =>
      post<AgentItemWire>('/agents/restore-revision', { agent_id: id, revision }).then(
        mapAgentItem
      ),
    publish: (id: string) =>
      post<{ status: string; agent_id: string }>(`/agents/publish`, { agent_id: id }),
    remove: (id: string) => del<{ status: string }>(`/agents/delete`, { agent_id: id }),
    capabilityMounts: capabilityMountApi
  },

  teams: {
    list: () => get<Team[]>('/teams'),
    create: (data: TeamDraft) => post<Team>('/teams', data),
    update: (id: string, data: TeamDraft) => put<Team>('/teams/update', { id, payload: data }),
    publish: (id: string) => post<Team>('/teams/publish', { id }),
    publishWithReceipt: (request_id: string, id: string, expected_revision: number) =>
      post<TeamSaveAndPublishReceipt>(
        '/teams/publish-with-receipt',
        { request_id, id, expected_revision },
        { retryOnUnauthorized: false }
      ),
    saveWithReceipt: (request_id: string, id: string | null, payload: TeamDraft) =>
      post<{ request_id: string; team_id: string; revision: number }>(
        '/teams/save-with-receipt',
        { request_id, id, payload },
        { retryOnUnauthorized: false }
      ),
    saveReceipt: (requestId: string) =>
      get<{ request_id: string; team_id: string; revision: number } | undefined>(
        `/teams/save-with-receipt/result?request_id=${encodeURIComponent(requestId)}`
      ),
    saveAndPublish: (request_id: string, id: string | null, payload: TeamDraft) =>
      post<TeamSaveAndPublishReceipt>(
        '/teams/save-and-publish',
        { request_id, id, payload },
        { retryOnUnauthorized: false }
      ),
    saveAndPublishResult: (requestId: string) =>
      get<TeamSaveAndPublishReceipt | undefined>(
        `/teams/save-and-publish/result?requestId=${encodeURIComponent(requestId)}`
      )
  },

  memories: {
    list: (query: MemoryListQuery = {}) => get<MemoryFact[]>(`/memories${memoryListQuery(query)}`),
    detail: (factId: string) =>
      get<MemoryFact>(`/memories/detail?fact_id=${encodeURIComponent(factId)}`),
    reviews: () => get<MemoryFact[]>('/memories/reviews'),
    page: (query: MemoryPageQuery = {}, signal?: AbortSignal) =>
      get<MemoryPageResult>(`/memories/page${memoryListQuery(query)}`, { signal }),
    review: (
      factId: string,
      expectedRevision: number,
      approved: boolean,
      confirmedScope?: MemoryScope
    ) =>
      memoryMutation(`/memories/review`, {
        fact_id: factId,
        payload: {
          expected_revision: expectedRevision,
          approved,
          ...(confirmedScope ? { confirmed_scope: confirmedScope } : {})
        }
      }),
    forget: (factId: string, expectedRevision: number) =>
      memoryMutation(`/memories/forget`, {
        fact_id: factId,
        payload: { expected_revision: expectedRevision }
      }),
    restore: (factId: string, expectedRevision: number) =>
      memoryMutation(`/memories/restore`, {
        fact_id: factId,
        payload: { expected_revision: expectedRevision }
      }),
    correct: (
      factId: string,
      data: {
        expected_revision: number
        subject?: string
        predicate?: string
        object_json: string
        confidence: number
        importance: number
      }
    ) => memoryMutation(`/memories/correct`, { fact_id: factId, payload: data })
  },

  /** Agent 挂载使用的已发布 Capability Catalog 版本与租户 ToolDeployment。 */
  capabilities: capabilityCatalogApi,
  skillDrafts: {
    ...skillDraftApi,
    saveAndPublish: (
      requestId: string,
      definitionRevision: number,
      revision: number,
      payload: ReturnType<typeof skillDraftSavePayload>
    ) =>
      post<SkillSaveAndPublishReceipt>(
        '/capabilities/skill-drafts/save-and-publish',
        {
          request_id: requestId,
          revision,
          definition_revision: definitionRevision,
          ...payload
        },
        { retryOnUnauthorized: false }
      ),
    saveAndPublishResult: (requestId: string) =>
      get<SkillSaveAndPublishReceipt | undefined>(
        `/capabilities/skill-drafts/save-and-publish/result?request_id=${encodeURIComponent(requestId)}`
      )
  },

  // 渠道
  channels: {
    list: () => get<{ total: number; items: ChannelItem[] }>('/channels'),
    get: (id: string) =>
      get<ChannelItem & { ui_config?: Record<string, unknown> }>(
        `/channels/detail?channelId=${encodeURIComponent(id)}`
      ),
    create: (data: Record<string, unknown>) => post<CreateChannelResult>('/channels', data),
    onboard: (requestId: string, data: Record<string, unknown>) =>
      post<ChannelOnboardingResult>(
        '/channels/onboarding',
        { request_id: requestId, channel: data },
        { retryOnUnauthorized: false }
      ),
    onboardingResult: (requestId: string) =>
      get<ChannelOnboardingResult | undefined>(
        `/channels/onboarding/result?request_id=${encodeURIComponent(requestId)}`
      ),
    update: (id: string, data: Record<string, unknown>) =>
      patch<ChannelItem>(`/channels/update`, { channel_id: id, payload: data }),
    remove: (id: string) => del<{ status: string }>(`/channels/delete`, { channel_id: id }),
    /** 轮换渠道令牌，返回明文（仅此一次）。 */
    rotateToken: (id: string) =>
      post<{ id: string; channel_token: string }>(
        `/channels/rotate-token`,
        { channel_id: id },
        { retryOnUnauthorized: false }
      ),
    rotateTokenWithReceipt: (requestId: string, channelId: string) =>
      post<{ request_id: string; channel_id: string; channel_token?: string }>(
        '/channels/tokens/rotate-with-receipt',
        { request_id: requestId, channel_id: channelId },
        { retryOnUnauthorized: false }
      ),
    tokenRotationResult: (requestId: string) =>
      get<{ request_id: string; channel_id: string } | undefined>(
        `/channels/tokens/rotate-with-receipt/result?request_id=${encodeURIComponent(requestId)}`
      ),
    issueApiKeyWithReceipt: (requestId: string, channelId: string) =>
      post<{ request_id: string; channel_id: string; api_key?: string; api_secret?: string }>(
        '/channels/api-keys/issue-with-receipt',
        { request_id: requestId, channel_id: channelId },
        { retryOnUnauthorized: false }
      ),
    apiKeyIssueResult: (requestId: string) =>
      get<{ request_id: string; channel_id: string } | undefined>(
        `/channels/api-keys/issue-with-receipt/result?request_id=${encodeURIComponent(requestId)}`
      ),
    /** 签发 OpenAPI API Key（明文仅此一次）。 */
    issueApiKey: (id: string) =>
      post<ApiKeyIssueResult>(
        `/channels/api-keys`,
        { channel_id: id },
        { retryOnUnauthorized: false }
      ),
    /** 轮换 OpenAPI API Key（旧 key 立即失效，明文仅此一次）。 */
    rotateApiKey: (id: string) =>
      post<ApiKeyIssueResult>(
        `/channels/api-keys/rotate`,
        { channel_id: id },
        { retryOnUnauthorized: false }
      ),
    /** 吊销 OpenAPI API Key。 */
    deleteApiKey: (id: string) =>
      del<{ channel_id: string; status: string }>(`/channels/api-keys`, { channel_id: id }),
    /** 完整替换渠道 Agent 集合；空数组清空绑定。 */
    bindAgents: (id: string, items: ChannelAgentBindingPayload[]) =>
      put<{ status: string; count: number }>(`/channels/agents`, {
        channel_id: id,
        payload: items
      }),
    /** 解绑渠道 Agent。 */
    unbindAgent: (id: string, agentId: string) =>
      del<{ status: string; channelId: string; agentId: string }>(`/channels/agents/delete`, {
        channel_id: id,
        agent_id: agentId
      })
  },

  // 连接器（详情/更新/删除的查询参数一律传 instance_id 业务键，非数值 id）
  connectors: {
    instances: () =>
      get<{ total: number; items: ConnectorInstanceItem[] }>('/connectors/instances'),
    instanceDetail: (instanceId: string) =>
      get<ConnectorInstanceDetail>(
        `/connectors/instances/detail?instanceId=${encodeURIComponent(instanceId)}`
      ),
    createInstance: (data: McpConnectorInstanceInput) =>
      post<{ status: string; instance_id: string }>('/connectors/instances', data),
    createInstanceWithReceipt: (requestId: string, payload: McpConnectorInstanceInput) =>
      post<{ request_id: string; instance_id: string }>(
        '/connectors/instances/create-with-receipt',
        {
          request_id: requestId,
          payload
        },
        { retryOnUnauthorized: false }
      ),
    creationResult: (requestId: string) =>
      get<{ request_id: string; instance_id: string } | null>(
        `/connectors/instances/create-with-receipt/result?request_id=${encodeURIComponent(requestId)}`
      ),
    updateInstanceWithReceipt: (
      requestId: string,
      instanceId: string,
      payload: UpdateMcpConnectorInstanceInput
    ) =>
      post<{ request_id: string; instance_id: string }>(
        '/connectors/instances/update-with-receipt',
        { request_id: requestId, instance_id: instanceId, payload },
        { retryOnUnauthorized: false }
      ),
    updateResult: (requestId: string) =>
      get<{ request_id: string; instance_id: string } | undefined>(
        `/connectors/instances/update-with-receipt/result?request_id=${encodeURIComponent(requestId)}`
      ),
    updateInstance: (instanceId: string, data: UpdateMcpConnectorInstanceInput) =>
      patch<ConnectorInstanceDetail>(`/connectors/instances/update`, {
        instance_id: instanceId,
        payload: data
      }),
    // 注:createInstance 不含 capabilities——MCP 类型后端按地址+凭证自动 tools/list 发现;或创建后在编辑弹窗「拉取清单→导入能力」
    removeInstance: (instanceId: string) =>
      del<{ status: string }>(`/connectors/instances/delete`, { instance_id: instanceId }),
    /** 拉取 @mcp 类型实例暴露的工具清单（MCP tools/list 标准发现）。 */
    mcpTools: (instanceId: string) =>
      get<{ total: number; items: McpToolItem[] }>(
        `/connectors/instances/mcp-tools?instanceId=${encodeURIComponent(instanceId)}`
      )
  },

  // 对话
  chat: {
    limits: (sessionId: string, signal?: AbortSignal) =>
      get<import('@skillforge/shared/components/chatLimits').ChatLimits>(
        `/chat/limits?${new URLSearchParams({ session_id: sessionId })}`,
        { signal }
      ),
    commands: (target: ChatTarget, signal?: AbortSignal) =>
      get<{ items: ChatCommand[] }>(
        `/chat/commands?${new URLSearchParams(target.team_id ? { team_id: target.team_id } : { agent_id: target.agent_id! })}`,
        { signal }
      ).then((result) => result.items),
    createSession: (
      endUserId: string | number,
      target: ChatTarget,
      scope?: { scope_id?: string; scope_type?: string }
    ) =>
      post<{ session_id: string }>('/chat/sessions', {
        end_user_id: endUserId,
        ...target,
        ...scope
      }),
    createSessionWithReceipt: (requestId: string, endUserId: string | number, target: ChatTarget) =>
      post<{ request_id: string; session_id: string }>(
        '/chat/sessions/create-with-receipt',
        {
          request_id: requestId,
          payload: { end_user_id: endUserId, ...target }
        },
        { retryOnUnauthorized: false }
      ),
    sessionCreationResult: (requestId: string) =>
      get<{ request_id: string; session_id: string } | null>(
        `/chat/sessions/create-with-receipt/result?request_id=${encodeURIComponent(requestId)}`
      ),
    browserConnectionDetail: (
      request: string,
      selector: import('@skillforge/shared/utils/browserConnection').ConnectionSelector,
      signal: AbortSignal
    ) =>
      get<import('@skillforge/shared/utils/browserConnection').BrowserConnectionDetail>(
        `/chat/browser/connection-requests/detail?${new URLSearchParams(selector.client_ref ? { request_id: request, client_ref: selector.client_ref } : { request_id: request, session_id: selector.session_id! })}`,
        { signal }
      ),
    browserConnectionPair: (
      body: import('@skillforge/shared/utils/browserConnection').ConnectionSelector & {
        connection_request_id: string
        make_default: boolean
      },
      signal: AbortSignal
    ) => post<{ pairing_url: string }>('/chat/browser/pair', body, { signal }),
    browserConnectionSelect: (
      body: import('@skillforge/shared/utils/browserConnection').ConnectionSelector & {
        connection_request_id: string
        device_id: string
        make_default: boolean
      },
      signal: AbortSignal
    ) => post<unknown>('/chat/browser/select', body, { signal }),
    browserConnectionCancel: (
      body: import('@skillforge/shared/utils/browserConnection').ConnectionSelector & {
        connection_request_id: string
      },
      signal: AbortSignal
    ) => post<unknown>('/chat/browser/connection-requests/cancel', body, { signal }),
    browserDevices: (
      selector: import('@skillforge/shared/components/chatBrowser').BrowserSelector,
      signal?: AbortSignal
    ) =>
      get<import('@skillforge/shared/components/chatBrowser').BrowserDevices>(
        `/chat/browser/devices?${new URLSearchParams(selector.client_ref ? { client_ref: selector.client_ref } : { session_id: selector.session_id! })}`,
        { signal }
      ),
    browserTasks: (
      selector: import('@skillforge/shared/components/chatBrowser').BrowserSelector,
      signal?: AbortSignal
    ) =>
      get<import('@skillforge/shared/components/chatBrowser').BrowserTask[]>(
        `/chat/browser/tasks?${new URLSearchParams(selector.client_ref ? { client_ref: selector.client_ref } : { session_id: selector.session_id! })}`,
        { signal }
      ),
    browserPair: (selector: import('@skillforge/shared/components/chatBrowser').BrowserSelector) =>
      post<{ pairing_url: string }>('/chat/browser/pair', selector),
    browserRename: (
      selector: import('@skillforge/shared/components/chatBrowser').BrowserSelector,
      device: string,
      label: string
    ) =>
      post<import('@skillforge/shared/components/chatBrowser').BrowserDevices>(
        '/chat/browser/rename',
        { ...selector, device_id: device, label }
      ),
    browserSelect: (
      selector: import('@skillforge/shared/components/chatBrowser').BrowserSelector,
      device: string
    ) =>
      post<import('@skillforge/shared/components/chatBrowser').BrowserDevices>(
        '/chat/browser/select',
        { ...selector, device_id: device }
      ),
    browserCancel: (
      selector: import('@skillforge/shared/components/chatBrowser').BrowserSelector,
      invocation: string
    ) =>
      post<import('@skillforge/shared/components/chatBrowser').BrowserTask['receipt']>(
        '/chat/browser/cancel',
        { ...selector, invocation }
      ),
    browserRevoke: (
      selector: import('@skillforge/shared/components/chatBrowser').BrowserSelector,
      device: string
    ) => post<unknown>('/chat/browser/revoke', { ...selector, device_id: device }),
    uploadMedia: (sessionId: string, uploadId: string, file: File) => {
      const form = new FormData()
      form.append('session_id', sessionId)
      form.append('upload_id', uploadId)
      form.append('file', file, file.name)
      return post<import('@skillforge/shared/components/chatMedia').ChatMedia>(
        '/chat/sessions/media/upload',
        form,
        { retryOnUnauthorized: false }
      )
    },
    mediaUploadReceipt: (sessionId: string, uploadId: string) =>
      get<{
        status: 'FOUND' | 'UNKNOWN'
        media: import('@skillforge/shared/components/chatMedia').ChatMedia | null
      }>(
        `/chat/sessions/media/upload-receipt?${new URLSearchParams({ session_id: sessionId, upload_id: uploadId })}`
      ),
    mediaLimits: (signal?: AbortSignal) =>
      get<import('@skillforge/shared/components/chatMedia').ChatMediaLimits>(
        '/chat/sessions/media/limits',
        { signal }
      ),
    mediaContent: (session: string, media: string, signal?: AbortSignal) =>
      get<Blob>(
        `/chat/sessions/media/content?${new URLSearchParams({ session_id: session, media_id: media })}`,
        { signal, responseType: 'blob' }
      ),
    uploadAttachment: (sessionId: string, uploadId: string, file: File) => {
      const form = new FormData()
      form.append('session_id', sessionId)
      form.append('upload_id', uploadId)
      form.append('file', file, file.name)
      return post<SessionAttachment>('/chat/sessions/attachments/upload', form, {
        retryOnUnauthorized: false
      })
    },
    attachments: (sessionId: string) =>
      get<SessionAttachment[]>(
        `/chat/sessions/attachments?${new URLSearchParams({ session_id: sessionId })}`
      ),
    teamWork: (sessionId: string, signal?: AbortSignal, workId?: string) =>
      get<import('@skillforge/shared/components/teamWork').TeamWorkSnapshot | null>(
        `/chat/work?${new URLSearchParams({ session_id: sessionId, ...(workId ? { work_id: workId } : {}) })}`,
        { signal }
      ),
    memberTaskDetails: (
      selector: import('@skillforge/shared/components/teamWork').TeamMemberSelector,
      cursor: string | undefined,
      signal: AbortSignal
    ) =>
      get<import('@skillforge/shared/components/teamWork').TeamMemberDetails>(
        `/chat/work/tasks/detail?${new URLSearchParams({ ...selector, ...(cursor ? { cursor } : {}), limit: String(MEMBER_DETAILS_PAGE_LIMIT) })}`,
        { signal }
      ),
    teamWorkHistory: (sessionId: string, signal?: AbortSignal) =>
      get<{ items: import('@skillforge/shared/components/teamWork').TeamWorkSnapshot[] }>(
        `/chat/work/history?${new URLSearchParams({ session_id: sessionId })}`,
        { signal }
      ),
    controlTeamWork: (
      sessionId: string,
      body: {
        input_id: string
        work_id: string
        task_id?: string
        operation:
          | 'supplement'
          | 'adjust'
          | 'cancel'
          | 'approve_plan'
          | 'approve_operation'
          | 'reject_operation'
          | 'reply_input'
          | 'workflow_response'
        content?: string
        plan_revision?: number
        pending_id?: string
        parameters_version?: string
      },
      signal?: AbortSignal
    ) =>
      post<import('@skillforge/shared/components/teamWork').TeamWorkSnapshot>(
        `/chat/work/controls`,
        { session_id: sessionId, ...body },
        { signal }
      ),
    steer: (sessionId: string, runId: string, commandId: string, text: string) =>
      post<ChatSteeringState>('/chat/runs/steer', {
        session_id: sessionId,
        run_id: runId,
        command_id: commandId,
        text
      }),
    steering: (sessionId: string, runId: string) =>
      get<ChatSteeringState>(
        `/chat/runs/steering?${new URLSearchParams({ session_id: sessionId, run_id: runId })}`
      ),
    submit: chatTransport.submit,
    observeSession: chatTransport.observeSession,
    cancelRun: (sessionId: string, runId: string) =>
      post<{ status: string }>('/chat/runs/cancel', { session_id: sessionId, run_id: runId }),
    runStatus: (sessionId: string, runId: string) =>
      get<{ status: string }>(
        `/chat/runs/status?${new URLSearchParams({ session_id: sessionId, run_id: runId })}`
      ),
    runBudget: (sessionId: string, runId: string) =>
      get<RunBudget>(
        `/chat/runs/budget?${new URLSearchParams({ session_id: sessionId, run_id: runId })}`
      ),
    delivery: (sessionId: string, runId: string) =>
      get<ChatRunDeliveryStatus>(
        `/chat/runs/delivery?${new URLSearchParams({ session_id: sessionId, run_id: runId })}`
      ),
    goals: (sessionId: string) =>
      get<ChatGoalStatus>(`/chat/goals?${new URLSearchParams({ session_id: sessionId })}`),
    createGoal: (sessionId: string, commandId: string, objective: string, maxRounds: number) =>
      post<ChatGoalStatus>('/chat/goals/create', {
        session_id: sessionId,
        command_id: commandId,
        objective,
        max_rounds: maxRounds
      }),
    pauseGoal: (sessionId: string, commandId: string, expectedRevision: number) =>
      post<ChatGoalStatus>('/chat/goals/pause', {
        session_id: sessionId,
        command_id: commandId,
        expected_revision: expectedRevision
      }),
    resumeGoal: (sessionId: string, commandId: string, expectedRevision: number) =>
      post<ChatGoalStatus>('/chat/goals/resume', {
        session_id: sessionId,
        command_id: commandId,
        expected_revision: expectedRevision
      }),
    collaboration: (sessionId: string) =>
      get<ChatCollaborationState>(
        `/chat/runs/collaboration?${new URLSearchParams({ session_id: sessionId })}`
      ),
    setPlanMode: (sessionId: string, active: boolean, commandId: string = crypto.randomUUID()) =>
      post<ChatCollaborationState>('/chat/runs/plan-mode', {
        session_id: sessionId,
        command_id: commandId,
        active
      }),
    /** 当前租户可用于对话的终端用户；入口不会自动选择默认用户。 */
    endUsers: () => get<ChatEndUserItem[]>('/chat/end-users'),
    createEndUser: (displayName: string) =>
      post<ChatEndUserItem>('/chat/end-users', { display_name: displayName }),
    history: (sessionId: string, before?: string) =>
      get<ChatHistoryResponse>(
        `/chat/history?${new URLSearchParams({ session_id: sessionId, limit: '30', ...(before ? { before_message_id: before } : {}) })}`
      ),
    /** 会话最终成果的修订视图；包含历史以支持当前成果的版本详情。 */
    artifacts: (sessionId: string) =>
      get<ChatArtifact[]>(
        `/chat/sessions/deliverables?${new URLSearchParams({ sessionId, includeHistory: 'true' })}`
      ).then((items) => items.map(normalizeChatArtifact)),
    /** 获取浏览器原生流式下载入口，不在前端读取或缓存产物字节。 */
    downloadTicket: (revisionId: string, fileId: string) =>
      post<{ url: string }>('/chat/deliverables/download-ticket', {
        id: revisionId,
        file_id: fileId
      }),
    /** 获取独立 HTML origin 或只读文本的短期入口。 */
    previewTicket: (revisionId: string, fileId: string) =>
      post<ArtifactPreviewTicket>('/chat/deliverables/preview-ticket', {
        id: revisionId,
        file_id: fileId
      }),
    /** 在服务端重新鉴权和核验后比较同一会话的两个文本文件。 */
    diffArtifacts: (request: ArtifactDiffRequest) =>
      post<ArtifactDiffResult>('/chat/artifacts/diff', request),
    /** 只验证下一次新执行可使用的产物输入，不恢复或重放旧执行。 */
    recoveryInputs: (request: ArtifactRecoveryRequest) =>
      post<ArtifactRecoveryResult>('/chat/deliverables/recovery-inputs', request).then(
        normalizeArtifactRecoveryResult
      ),
    /** 会话列表：终端用户和 agentId 均可选；按最新消息时间倒序。 */
    sessions: (params: { endUserId?: string; agentId?: string; teamId?: string } = {}) => {
      const q = new URLSearchParams()
      if (params.endUserId) q.set('end_user_id', params.endUserId)
      if (params.teamId) q.set('team_id', params.teamId)
      if (params?.agentId !== undefined) q.set('agentId', String(params.agentId))
      return get<
        Array<{
          session_id: string
          summary?: string | null
          message_count: number
          updated_at: string
          agent_id?: number | null
          team_id?: string | null
        }>
      >(`/chat/sessions?${q.toString()}`)
    },
    closeSession: (sessionId: string) =>
      post<{ session_id: string; status: 'closed' }>('/chat/sessions/close', {
        session_id: sessionId
      }),
    /** 会话详情聚合：会话本体 / 绑定 Agent / 工作流执行记录 / 运行时任务 / 回调注册 */
    sessionDetail: (sessionId: string) =>
      get<SessionDetail>(`/chat/sessions/detail?sessionId=${encodeURIComponent(sessionId)}`)
  },

  handoff: {
    list: (status?: string) =>
      get<{ total: number; items: HandoffTicket[] }>(
        `/handoff/tickets${status ? `?status=${encodeURIComponent(status)}` : ''}`
      ),
    create: (sessionId: string, reason: string, commandId: string) =>
      post<HandoffCreateReceipt>(
        '/handoff/tickets',
        { session_id: sessionId, reason, command_id: commandId },
        { retryOnUnauthorized: false }
      ),
    createReceipt: (commandId: string) =>
      get<HandoffCreateReceipt>(
        `/handoff/tickets/create-receipt?command_id=${encodeURIComponent(commandId)}`
      ),
    replyReceipt: (ticketId: string, commandId: string) =>
      get<HandoffReply>(
        `/handoff/tickets/reply-receipt?ticket_id=${encodeURIComponent(ticketId)}&command_id=${encodeURIComponent(commandId)}`
      ),
    detail: (id: string, before?: string) =>
      get<HandoffDetail>(
        `/handoff/tickets/detail?ticket_id=${encodeURIComponent(id)}${before ? `&before_message_id=${encodeURIComponent(before)}` : ''}`
      ),
    reply: (id: string, revision: number, commandId: string, content: string) =>
      post<HandoffReply>(
        `/handoff/tickets/replies`,
        {
          ticket_id: id,
          expected_revision: revision,
          command_id: commandId,
          content
        },
        { retryOnUnauthorized: false }
      ),
    actionReceipt: (ticketId: string, commandId: string) =>
      get<HandoffActionReceipt | null>(
        `/handoff/tickets/action-receipt?ticket_id=${encodeURIComponent(ticketId)}&command_id=${encodeURIComponent(commandId)}`
      ),
    accept: (id: string, revision: number, commandId: string) =>
      post<HandoffActionReceipt>(
        `/handoff/tickets/accept`,
        {
          ticket_id: id,
          expected_revision: revision,
          command_id: commandId
        },
        { retryOnUnauthorized: false }
      ),
    resolve: (id: string, revision: number, commandId: string, note: string) =>
      post<HandoffActionReceipt>(
        `/handoff/tickets/resolve`,
        {
          ticket_id: id,
          expected_revision: revision,
          command_id: commandId,
          payload: { resolution_note: note }
        },
        { retryOnUnauthorized: false }
      )
  },

  knowledge: {
    listBases: () => get<{ total: number; items: KnowledgeBase[] }>('/knowledge/bases'),
    getBase: (id: string) =>
      get<KnowledgeBaseDetail>(`/knowledge/bases/detail?kbId=${encodeURIComponent(id)}`),
    mountOption: (id: string) =>
      get<{
        kb_id: string
        active_revision_id: string | null
        definition_id: string | null
        version_id: string | null
        version_key: string | null
        version_name: string | null
      }>(`/knowledge/bases/mount-option?kbId=${encodeURIComponent(id)}`),
    createBase: (data: KnowledgeBaseCreateInput) =>
      post<{ id: string; name: string }>('/knowledge/bases', data),
    createWithReceipt: (requestId: string, payload: KnowledgeBaseCreateInput) =>
      post<{ request_id: string; kb_id: string }>(
        '/knowledge/bases/create-with-receipt',
        { request_id: requestId, payload },
        { retryOnUnauthorized: false }
      ),
    createReceipt: (requestId: string) =>
      get<{ request_id: string; kb_id: string } | undefined>(
        `/knowledge/bases/create-with-receipt/result?request_id=${encodeURIComponent(requestId)}`
      ),
    updateBase: (id: string, data: Record<string, unknown>) =>
      patch<KnowledgeBaseDetail>(`/knowledge/bases/update`, { kb_id: id, payload: data }),
    removeBase: (id: string) => del<{ status: string }>(`/knowledge/bases/delete`, { kb_id: id }),
    listRevisions: (kbId: string) =>
      get<{ total: number; items: KnowledgeBaseRevision[] }>(
        `/knowledge/bases/revisions?kbId=${encodeURIComponent(kbId)}`
      ),
    prepareRevision: (kbId: string, sourceVersions: string[], policy: KnowledgeRebuildPolicy) =>
      post<KnowledgeBaseRevision>('/knowledge/bases/revisions/prepare', {
        kb_id: kbId,
        payload: { source_versions: sourceVersions, policy }
      }),
    prepareAndPublish: (
      requestId: string,
      kbId: string,
      sourceVersions: string[],
      policy: KnowledgeRebuildPolicy
    ) =>
      post<KnowledgePrepareAndPublishReceipt>(
        '/knowledge/bases/revisions/prepare-and-publish',
        {
          request_id: requestId,
          kb_id: kbId,
          payload: { source_versions: sourceVersions, policy }
        },
        { retryOnUnauthorized: false }
      ),
    prepareAndPublishResult: (requestId: string) =>
      get<KnowledgePrepareAndPublishReceipt | undefined>(
        `/knowledge/bases/revisions/prepare-and-publish/result?requestId=${encodeURIComponent(requestId)}`
      ),
    createRevision: (kbId: string) =>
      post<KnowledgeBaseRevision>(`/knowledge/bases/revisions`, { kb_id: kbId }),
    markRevisionReady: (
      kbId: string,
      revision: number,
      sourceVersions: string[],
      policy: KnowledgeRebuildPolicy
    ) =>
      post<KnowledgeBaseRevision>(`/knowledge/bases/revisions/ready`, {
        kb_id: kbId,
        revision: String(revision),
        payload: { source_versions: sourceVersions, policy }
      }),
    publishRevision: (kbId: string, revision: number) =>
      post<KnowledgeBaseRevision>(`/knowledge/bases/revisions/publish`, {
        kb_id: kbId,
        revision: String(revision)
      }),
    listDocuments: (
      kbId: string,
      params?: { page?: number; page_size?: number; status?: string; titleQuery?: string }
    ) => {
      const q = new URLSearchParams()
      if (params?.page) q.set('page', String(params.page))
      if (params?.page_size) q.set('page_size', String(params.page_size))
      if (params?.status) q.set('status', params.status)
      if (params?.titleQuery) q.set('titleQuery', params.titleQuery)
      return get<{ total: number; items: KnowledgeDocumentItem[] }>(
        `/knowledge/bases/documents?kbId=${encodeURIComponent(kbId)}${q.toString() ? `&${q.toString()}` : ''}`
      )
    },
    latestReadySources: (kbId: string) =>
      get<string[]>(
        `/knowledge/bases/documents/latest-ready-sources?kbId=${encodeURIComponent(kbId)}`
      ),
    uploadFile: (kbId: string, form: FormData, requestId: string) => {
      const query = new URLSearchParams({ kb_id: kbId, request_id: requestId })
      return post<KnowledgeUploadReceipt>(`/knowledge/bases/documents/upload?${query}`, form, {
        retryOnUnauthorized: false
      })
    },
    uploadResult: (kbId: string, requestId: string) =>
      get<KnowledgeUploadReceipt | undefined>(
        `/knowledge/bases/documents/upload/result?${new URLSearchParams({ kb_id: kbId, request_id: requestId })}`
      ),
    getDocument: (id: string) =>
      get<KnowledgeDocumentDetail>(`/knowledge/documents/detail?docId=${encodeURIComponent(id)}`),
    updateDocument: (id: string, expectedSourceVersion: string, data: Record<string, unknown>) =>
      patch<KnowledgeDocumentDetail>(`/knowledge/documents/update`, {
        doc_id: id,
        payload: { ...data, expected_source_version: expectedSourceVersion }
      }),
    removeDocument: (id: string, expectedSourceVersion: string) =>
      del<{ status: string }>(`/knowledge/documents/delete`, {
        doc_id: id,
        expected_source_version: expectedSourceVersion
      }),
    reindexDocument: (id: string, expectedSourceVersion: string) =>
      post<{ document_id: string; chunks: number }>(`/knowledge/documents/reindex`, {
        doc_id: id,
        expected_source_version: expectedSourceVersion
      }),
    listChunks: (docId: string, params?: { page?: number; page_size?: number }) => {
      const q = new URLSearchParams()
      if (params?.page) q.set('page', String(params.page))
      if (params?.page_size) q.set('page_size', String(params.page_size))
      return get<{ total: number; items: KnowledgeChunkItem[] }>(
        `/knowledge/documents/chunks?docId=${encodeURIComponent(docId)}${q.toString() ? `&${q.toString()}` : ''}`
      )
    },
    updateChunk: (id: string, expectedSourceVersion: string, data: { content: string }) =>
      patch<KnowledgeChunkItem>(`/knowledge/chunks/update`, {
        chunk_id: id,
        payload: { ...data, expected_source_version: expectedSourceVersion }
      }),
    removeChunk: (id: string, expectedSourceVersion: string) =>
      del<{ status: string }>(`/knowledge/chunks/delete`, {
        chunk_id: id,
        expected_source_version: expectedSourceVersion
      }),
    search: (data: KnowledgeSearchParams) =>
      post<{
        items: Array<{
          content: string
          score: number
          chunk_id: string
          document_id: string
          vector_score?: number | null
        }>
      }>('/knowledge/search', data)
  },

  settings: {
    availableModels: () => get<ModelSummary[]>('/settings/available-models'),
    getModelSelections: () => get<ModelSelections>('/settings/model-selections'),
    updateModelSelections: (data: ModelSelections) =>
      put<ModelSelections>('/settings/model-selections', data)
  },

  billing: {
    usage: () => get<Record<string, unknown>>('/billing/usage'),
    monthly: (
      params: { month: string; page?: number; page_size?: number },
      opts?: RequestOptions
    ) => {
      const query = new URLSearchParams({
        month: params.month,
        page: String(params.page ?? 1),
        page_size: String(params.page_size ?? 20)
      })
      return get<ModelBillingMonthly>(`/billing/monthly?${query}`, opts)
    },
    trace: (rootInputId: string, opts?: RequestOptions) =>
      get<TraceView>(
        `/billing/traces/detail?root_input_id=${encodeURIComponent(rootInputId)}`,
        opts
      ),
    /** 查询参数用 camelCase：Spring record 组件绑定按属性名匹配，snake_case 参数不生效 */
    records: (
      params?: {
        eventType?: string
        scopeId?: string
        model?: string
        amountStatus?: string
        from?: string
        to?: string
        page?: number
        pageSize?: number
      },
      opts?: RequestOptions
    ) => {
      const q = new URLSearchParams()
      if (params?.eventType) q.set('eventType', params.eventType)
      if (params?.model) q.set('model', params.model)
      if (params?.amountStatus) q.set('amountStatus', params.amountStatus)
      if (params?.scopeId) q.set('scopeId', params.scopeId)
      if (params?.from) q.set('from', params.from)
      if (params?.to) q.set('to', params.to)
      if (params?.page) q.set('page', String(params.page))
      if (params?.pageSize) q.set('pageSize', String(params.pageSize))
      return get<{ total: number; items: BillingRecordItem[] }>(
        `/billing/records${q.toString() ? `?${q.toString()}` : ''}`,
        opts
      )
    },
    requests: (
      params?: {
        endUserId?: number
        agentId?: number
        runId?: string
        from?: string
        to?: string
        page?: number
        pageSize?: number
      },
      opts?: RequestOptions
    ) => {
      const q = new URLSearchParams()
      if (params?.endUserId) q.set('endUserId', String(params.endUserId))
      if (params?.agentId) q.set('agentId', String(params.agentId))
      if (params?.runId) q.set('runId', params.runId)
      if (params?.from) q.set('from', params.from)
      if (params?.to) q.set('to', params.to)
      if (params?.page) q.set('page', String(params.page))
      if (params?.pageSize) q.set('pageSize', String(params.pageSize))
      return get<{ total: number; items: BillingRequestItem[] }>(
        `/billing/requests${q.toString() ? `?${q.toString()}` : ''}`,
        opts
      )
    },
    requestDetail: (requestId: string) =>
      get<BillingRequestDetail>(
        `/billing/requests/detail?request_id=${encodeURIComponent(requestId)}`
      ),
    reconciliationProvider: {
      get: () => get<UsageReconciliationConfig>('/billing/reconciliation/provider'),
      save: (data: {
        provider?: string
        endpoint?: string
        api_key?: string
        api_key_header?: string
        response_schema?: Record<string, unknown>
        clear_api_key: boolean
      }) => put<UsageReconciliationConfig>('/billing/reconciliation/provider', data)
    }
  },

  governance: {
    servicePrincipals: {
      list: () => get<ServicePrincipal[]>('/service-principals'),
      issueWithReceipt: (requestId: string, data: { integration_id: string; scopes: string[] }) =>
        post<{
          request_id: string
          principal_id: string
          api_key?: string
          signing_secret?: string
        }>(
          '/service-principals/issue-with-receipt',
          { request_id: requestId, ...data },
          { retryOnUnauthorized: false }
        ),
      issueResult: (requestId: string) =>
        get<{ request_id: string; principal_id: string } | undefined>(
          `/service-principals/issue-with-receipt/result?request_id=${encodeURIComponent(requestId)}`
        ),
      issue: (data: { integration_id: string; scopes: string[] }) =>
        post<IssuedServicePrincipal>('/service-principals', data, { retryOnUnauthorized: false }),
      rotateWithReceipt: (requestId: string, id: number) =>
        post<{
          request_id: string
          principal_id: string
          api_key?: string
          signing_secret?: string
        }>(
          '/service-principals/rotate-with-receipt',
          { request_id: requestId, principal_id: id },
          { retryOnUnauthorized: false }
        ),
      rotationResult: (requestId: string) =>
        get<{ request_id: string; principal_id: string } | undefined>(
          `/service-principals/rotate-with-receipt/result?request_id=${encodeURIComponent(requestId)}`
        ),
      rotate: (id: number) =>
        post<IssuedServicePrincipal>(
          `/service-principals/rotate`,
          { id: id },
          { retryOnUnauthorized: false }
        ),
      changeStatusWithReceipt: (
        requestId: string,
        id: number,
        action: ServicePrincipalStatusAction
      ) =>
        post<ServicePrincipalStatusReceipt>(
          '/service-principals/status-with-receipt',
          { request_id: requestId, principal_id: id, action },
          { retryOnUnauthorized: false }
        ),
      statusResult: (requestId: string) =>
        get<ServicePrincipalStatusReceipt | undefined>(
          `/service-principals/status-with-receipt/result?request_id=${encodeURIComponent(requestId)}`
        ),
      suspend: (id: number) =>
        post<{ id: number; suspended: boolean }>(
          `/service-principals/suspend`,
          { id: id },
          { retryOnUnauthorized: false }
        ),
      revoke: (id: number) =>
        post<{ id: number; revoked: boolean }>(
          `/service-principals/revoke`,
          { id: id },
          { retryOnUnauthorized: false }
        )
    },
    webhooks: {
      list: () => get<WebhookSubscription[]>('/webhook-subscriptions'),
      create: (data: { session_id?: string; endpoint_url: string; signing_secret: string }) =>
        post<WebhookSubscription>('/webhook-subscriptions', data, { retryOnUnauthorized: false }),
      createWithReceipt: (
        requestId: string,
        data: { session_id?: string; endpoint_url: string; signing_secret: string }
      ) =>
        post<WebhookCreateReceipt>(
          '/webhook-subscriptions/create-with-receipt',
          { request_id: requestId, ...data },
          { retryOnUnauthorized: false }
        ),
      createResult: (requestId: string) =>
        get<WebhookCreateReceipt | undefined>(
          `/webhook-subscriptions/create-with-receipt/result?request_id=${encodeURIComponent(requestId)}`
        ),
      pause: (id: number) =>
        patch<{ id: number; paused: boolean }>(
          `/webhook-subscriptions/pause`,
          {
            subscription_id: id
          },
          { retryOnUnauthorized: false }
        ),
      pauseWithReceipt: (requestId: string, id: number) =>
        post<WebhookPauseReceipt>(
          '/webhook-subscriptions/pause-with-receipt',
          { request_id: requestId, subscription_id: id },
          { retryOnUnauthorized: false }
        ),
      pauseResult: (requestId: string) =>
        get<WebhookPauseReceipt | undefined>(
          `/webhook-subscriptions/pause-with-receipt/result?request_id=${encodeURIComponent(requestId)}`
        )
    },
    auditLogs: (params?: { action?: string; tenantId?: string; limit?: number }) => {
      const q = new URLSearchParams()
      if (params?.action) q.set('action', params.action)
      if (params?.tenantId) q.set('tenantId', params.tenantId)
      if (params?.limit) q.set('limit', String(params.limit))
      return get<{ total: number; items: AuditLog[] }>(
        `/audit/logs${q.toString() ? `?${q.toString()}` : ''}`
      )
    },
    knowledgeProposals: {
      pending: () => get<KnowledgeWriteProposal[]>('/knowledge/write-proposals/pending'),
      approve: (proposalId: string, data: { reviewer_id: string; note?: string }) =>
        post<KnowledgeWriteProposal>(`/knowledge/write-proposals/approve`, {
          proposal_id: proposalId,
          payload: data
        }),
      reject: (proposalId: string, data: { reviewer_id: string; note?: string }) =>
        post<KnowledgeWriteProposal>(`/knowledge/write-proposals/reject`, {
          proposal_id: proposalId,
          payload: data
        })
    }
  },

  marketplace: {
    search: (q = '', type?: string, category?: string, page = 1, opts?: RequestOptions) =>
      get<MarketSearch>(
        `/marketplace/search?q=${encodeURIComponent(q)}&page=${page}${type ? `&workflow_type=${encodeURIComponent(type)}` : ''}${category ? `&category=${encodeURIComponent(category)}` : ''}`,
        opts
      )
  },

  health: () =>
    fetch('/health/deps').then(async (res) => {
      if (!res.ok) throw new Error(commonZhCN.http.healthCheckFailed)
      return res.json() as Promise<{
        status: string
        deps: Record<string, { ok: boolean; error?: string }>
      }>
    })
}

/** 基础管理页面复用当前宿主的认证、刷新及错误语义。 */
export const foundationRequest = http.request
