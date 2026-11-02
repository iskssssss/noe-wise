import type { HttpClient, RequestOptions } from '../utils/http'
import { consumeSse, SsePayloadError, type StreamEvent } from '../utils/stream'
import type { ChatContent } from '../components/chatMedia'
import type { ChatInputReference, ChatReferenceView } from '../components/chatReferences'
import type {
  ChatHumanInputResponse,
  ChatToolInputResponse,
  ChatWorkflowResponse
} from '../types/chatCollaboration'
import type {
  AgentDeployment,
  AgentGrant,
  DeploymentAuthorization,
  DeploymentBindings,
  DeploymentTemplate
} from '../integration/agentDeployments'
import type { UsagePage, UsageQuery, UsageSummary } from '../integration/usage'

export type OpenPlatformResourceType =
  | 'agent'
  | 'workflow'
  | 'skill'
  | 'persona'
  | 'policy'
  | 'tool'
  | 'connector'
  | 'channel'
  | 'knowledge_base'

export type OpenPlatformResourceStatus = 'draft' | 'ready' | 'published' | 'archived'

export interface OpenPlatformResourceCatalogItem {
  resource_type: OpenPlatformResourceType
  resource_id: string
  revision: number
  status: OpenPlatformResourceStatus
  content_hash: string
  etag: string
  created_at?: string
  updated_at?: string
  published_at?: string | null
}

export interface OpenPlatformResourceRevision extends OpenPlatformResourceCatalogItem {
  content: Record<string, unknown>
}

export interface OpenPlatformDeployment {
  deployment_id: string
  resource_type: OpenPlatformResourceType
  resource_id: string
  resource_revision: number
  environment: string
  status: string
  deployment_revision: number
  etag: string
}

export interface OpenPlatformChannelBinding {
  channel_id: number
  deployment_id: string
  resource_id: string
  resource_revision: number
  binding_revision: number
  status: string
  etag: string
}

export interface OpenPlatformResourceMapping {
  integration_id: string
  resource_type: OpenPlatformResourceType
  external_ref: string
  resource_id: string
  revision: number
  etag: string
  content_hash: string
}

export interface OpenPlatformControlOperation {
  operation_id: string
  root_operation_id: string
  integration_id: string
  operation_type: string
  status: string
  idempotency_key: string
  created: boolean
  items: Array<{
    item_key: string
    resource_ref: string
    status: string
    result_ref: string | null
    error_code: string | null
    retryable: boolean
  }>
  accepted_at: string
  completed_at: string | null
}

export interface OpenPlatformInboundReceipt {
  session_id: string
  input_id: string
  root_input_id: string
  event_id: string
  status: string
  run_id: string | null
  replayed: boolean
}

/** 会话归属与本次操作者分开；群不能借成员或服务 Agent 充当 owner。 */
export type SessionSubject =
  { kind: 'user'; end_user_id: string } | { kind: 'group'; group_binding_id: string }

/** 群绑定的安全元数据；成员与服务 Agent 投影通过独立对象回执维护。 */
export interface OpenPlatformGroup {
  group_binding_id: string
  session_id: string
  namespace: string
  external_group_ref: string
  lifecycle_ref: string
  business_scope_id: string
  status: 'active' | 'closed'
  revision: number
  subject: Extract<SessionSubject, { kind: 'group' }>
}

export type GroupMemoryScope = 'GROUP' | 'GROUP_MEMBER'
export type GroupMemoryStatus =
  'ACTIVE' | 'DECAYED' | 'PENDING_CONFIRM' | 'REJECTED' | 'ARCHIVED' | 'FORGOTTEN'

/** 当前访问者、执行 Agent 与被描述成员分别表达；公开事实归属不随服务 Agent 变化。 */
export interface GroupMemoryQuery {
  group_binding_id: string
  reader_end_user_id: string
  agent_id: string
  subject_end_user_id?: string
  scope?: GroupMemoryScope
  status?: GroupMemoryStatus
  limit?: number
}

export interface GroupMemoryOperator {
  group_binding_id: string
  operator_end_user_id: string
  agent_id: string
}

/** 策略读取只核当前群管理员；描述对象与原提取来源不作为管理身份。 */
export interface GroupMemoryExtractionPolicyQuery {
  group_binding_id: string
  operator_end_user_id: string
}

/** expected_revision 为首次配置的 0 或当前策略修订；失配后读取原策略再决定。 */
export interface UpdateGroupMemoryExtractionPolicyInput extends GroupMemoryExtractionPolicyQuery {
  enabled: boolean
  extractor_agent_id: string
  expected_revision: number
}

export interface GroupMemoryExtractionPolicy {
  group_binding_id: string
  enabled: boolean
  extractor_agent_id: string | null
  revision: number
}

export type CreateGroupMemoryInput = GroupMemoryOperator & {
  source_message_ids: string[]
  type: 'preference' | 'episode'
  subject: string
  predicate: string
  object_json: string
  confidence?: number
  importance?: number
  review_required?: boolean
} & (
    | { scope: 'GROUP'; subject_end_user_id?: null }
    | { scope: 'GROUP_MEMBER'; subject_end_user_id: string }
  )

export interface GroupMemoryRevisionInput extends GroupMemoryOperator {
  fact_id: string
  expected_revision: number
}

export interface ReviewGroupMemoryInput extends GroupMemoryRevisionInput {
  approved: boolean
}

export interface CorrectGroupMemoryInput extends GroupMemoryRevisionInput {
  subject?: string | null
  predicate?: string | null
  object_json: string
  confidence?: number
  importance?: number
}

/** 保留正式消息来源、原事实演进和当前治理状态，不以新请求替换未知结果。 */
export interface GroupMemoryFact {
  fact_id: string
  group_binding_id: string
  scope: GroupMemoryScope
  subject_end_user_id: string | null
  type: string
  subject: string
  predicate: string
  object_json: string
  source_message_ids: string[]
  source_event_ids: string[]
  source_session_id: string
  extractor_version: string
  revision: number
  created_at: string
  valid_from: string
  valid_to: string | null
  expires_at: string | null
  confidence: number
  importance: number
  review_required: boolean
  derived_from: string | null
  superseded_by: string | null
  status: GroupMemoryStatus
}

/** 当前执行域内的外部群生命周期，不接受用户或固定 Agent 归属。 */
export interface EnsureGroupInput {
  namespace: string
  external_group_ref: string
  lifecycle_ref: string
  business_scope_id: string
}

/** 关闭使用当前绑定 revision，已关闭的生命周期不能重新启用。 */
export interface CloseGroupInput {
  group_binding_id: string
  expected_revision: number
}

/** expected_revision=0 表示首次成员投影；来源版本不能倒退。 */
export interface UpdateGroupMemberInput extends CloseGroupInput {
  end_user_id: string
  role: 'member' | 'manager'
  status: 'active' | 'left'
  source_event_id: string
  source_revision: number
}

/** Agent 是本群当前服务实例；其来源授权和就绪状态仍由服务端检查。 */
export interface UpdateGroupAgentInput extends CloseGroupInput {
  agent_id: string
  status: 'active' | 'removed'
  source_event_id: string
  source_revision: number
}

/** 稳定对象身份的目录翻页；页大小不限制群总规模。 */
export interface GroupDirectoryQuery {
  group_binding_id: string
  after_id?: string
  limit?: number
}
export interface GroupDirectoryPage<T> {
  items: T[]
  next_after: string | null
  has_more: boolean
}

/** 当前成员状态与来源版本的持久回执，不改变群 Session owner。 */
export interface OpenPlatformGroupMember {
  group_binding_id: string
  end_user_id: string
  role: 'member' | 'manager'
  status: 'active' | 'left'
  revision: number
  source_event_id: string
  source_revision: number
}

/** 当前服务 Agent 状态与来源版本的持久回执。 */
export interface OpenPlatformGroupAgent {
  group_binding_id: string
  agent_id: string
  status: 'active' | 'removed'
  revision: number
  source_event_id: string
  source_revision: number
}

/** 作者引用原来源；Agent 关联本群已完成的正式助手消息，缺少可核验来源的分支拒绝。 */
export type GroupMessageAuthor =
  | { kind: 'end_user'; end_user_id: string }
  | { kind: 'agent'; agent_id: string; run_id: string; source_ref: string }
  | { kind: 'system'; source_ref: string }
  | { kind: 'tool'; agent_id: string; run_id: string; source_ref: string }

/** 记录不可变来源；独立决定是否提交回复触发，不在记录时选择执行 Agent。 */
export interface RecordGroupMessageInput {
  group_binding_id: string
  source_event_id: string
  source_revision: number
  author: GroupMessageAuthor
  content: ChatContent[]
}

/** 消息已持久化的来源身份与顺序；没有 InputReceipt 或 Run 身份。 */
export interface GroupMessageReceipt {
  group_binding_id: string
  session_id: string
  source_message_id: string
  source_sequence: number
  source_event_id: string
  source_revision: number
}

/** 真实来源作者显式选择本群当前服务 Agent，来源在群内只形成一个逻辑触发。 */
export interface CreateGroupTriggerInput {
  group_binding_id: string
  source_message_id: string
  requester_end_user_id: string
  agent_id: string
}

/** POST 返回原持久回执；detail 返回当前投影，queued 时尚无 Input/Run。 */
export interface GroupTriggerReceipt {
  group_binding_id: string
  session_id: string
  requester_end_user_id: string
  agent_id: string
  trigger_id: string
  trigger_sequence: number
  source_message_id: string
  status: 'queued' | 'accepted' | 'rejected'
  input_id: string | null
  run_id: string | null
  rejection_code: string | null
}

/** 原请求者或当前管理者回答精确等待；复用既有 Chat 的三种恢复载荷。 */
export interface RespondGroupRunInput {
  group_binding_id: string
  run_id: string
  input_id: string
  responder_end_user_id: string
  hitl_response: ChatHumanInputResponse | ChatToolInputResponse | ChatWorkflowResponse
}

/** 定向原 Run 的取消或补充输入；input_id 同时作为幂等头，不经过普通触发队列。 */
export type GroupRunCommandInput = {
  group_binding_id: string
  run_id: string
  input_id: string
  operator_end_user_id: string
} & ({ action: 'cancel_execution'; text?: null } | { action: 'steer'; text: string })

/** 当前公开状态和等待投影；revision 保持规范十进制字符串。 */
export interface GroupRunDetail {
  group_binding_id: string
  session_id: string
  run_id: string
  agent_id: string
  status: string
  phase: 'READY' | 'AWAITING_RESULT' | 'WAITING' | 'UNCERTAIN' | 'FINISHED'
  revision: string
  pending_interactions: Record<string, unknown>[]
}

/** 原 Run 的准确等待响应回执，不改变原请求者和执行 Agent。 */
export interface GroupRunResponse {
  group_binding_id: string
  session_id: string
  run_id: string
  input_id: string
  status: string
  replayed: boolean
}

/** 公开历史沿既有 ChatMessageRecord 投影，私有 SessionLog 不进入接入 SDK。 */
export interface OpenPlatformChatMessageRecord {
  message_id: string
  role: string
  content: string
  created_at: string
  metadata: Record<string, unknown> | null
  message_version: number
  references: ChatReferenceView[]
  state?: string
  phase?: string
}

/** 原游标绑定读取者、群和快照；更换读取条件应显式从新历史起点读取。 */
export interface GroupReadQuery {
  group_binding_id: string
  reader_end_user_id: string
  cursor?: string
  page_size?: number
}

export interface GroupHistoryPage {
  group_binding_id: string
  session_id: string
  items: OpenPlatformChatMessageRecord[]
  next_cursor: string | null
  has_more: boolean
  event_cursor: string | null
}

export interface GroupEventsPage {
  group_binding_id: string
  session_id: string
  items: OpenPlatformSessionEvents['events']
  next_cursor: string | null
  has_more: boolean
}

/** 观察仅消费一次流；不可披露时保留最后确认游标，交由用户人工核对。 */
export type GroupObservation =
  | { state: 'ended'; cursor: string | null }
  | { state: 'identity_undisclosable' | 'invalid_event'; cursor: string | null; message: string }

const GROUP_IDENTITY_MESSAGE = '群事件身份无法公开，请保留原游标并联系机构管理员人工核对。'

export type GroupControlScope = 'execution' | 'business_write' | 'send' | 'memory_extraction'

/** 新命令表达新意图；返回受理不等于所有跨服务参与方已经应用。 */
export interface GroupControlCommand {
  command_id: string
  group_binding_id: string
  run_id?: string | null
  expected_revision: number
  desired_state: 'paused' | 'running'
  scopes: GroupControlScope[]
}

export interface GroupControlAuthorize {
  group_binding_id: string
  action: 'write' | 'read' | 'reconcile' | 'release' | 'ack' | 'manage'
}

export interface GroupControlAuthorization {
  group_binding_id: string
  action: string
  principal_id: string
  authorized: boolean
  control_revision: number
  latest_trigger_sequence: number
}

export interface GroupControlTerminalize {
  group_binding_id: string
  command_id: string
  fingerprint: string
  local_intent_id: string
  run_id?: string | null
  expected_revision: number
}

export interface GroupReleaseMessage {
  message_id: string
  message_version: number
  delivery_id: string
}

export interface GroupControlRelease {
  group_binding_id: string
  run_id: string
  expected_revision: number
  messages: GroupReleaseMessage[]
  blocked_boundary_refs: string[]
}

export interface GroupControlReleaseReceipt {
  release_id: string
  group_binding_id: string
  control_revision: number
  messages: GroupReleaseMessage[]
  blocked_boundary_refs: string[]
}

export interface GroupControlState {
  run_id: string | null
  desired_state: string
  scopes: string[]
  revision: number
}

export interface GroupControlBoundary {
  boundary_ref: string
  run_id: string | null
  through_trigger_sequence: number | null
}

export interface GroupControlInFlight {
  delivery_id: string
  status: string
  result_ref: string | null
}

export interface GroupControlParticipant {
  endpoint_id: string
  generation: number
  required_revision: number
  status: string
  applied_boundary_ref: string | null
}

export interface GroupControlDurableAck {
  endpoint_id: string
  generation: number
  control_revision: number
  applied_boundary_ref: string
  ledger_ref: string
  fence_revision: number
}

export interface GroupControlReceipt {
  command_id: string
  group_binding_id: string
  run_id: string | null
  fingerprint: string
  control_revision: number
  desired_state: string | null
  scopes: string[]
  status: 'ACCEPTED' | 'PARTIAL' | 'APPLIED' | 'SUPERSEDED' | 'REJECTED' | 'TERMINAL_NOT_ACCEPTED'
  result_kind: 'accepted' | 'rejected' | 'terminal_not_accepted'
  result_ref: string
  required_participants: GroupControlParticipant[]
  durable_acks: GroupControlDurableAck[]
  in_flight: GroupControlInFlight[]
  boundary_ref: string | null
  through_trigger_sequence: number | null
}

export interface GroupControlCurrent {
  group_binding_id: string
  control_revision: number
  latest_trigger_sequence: number
  states: GroupControlState[]
  blocked_boundaries: GroupControlBoundary[]
  endpoints: GroupSenderEndpoint[]
  latest_command: GroupControlReceipt | null
}

export interface GroupSenderRegister {
  group_binding_id: string
  endpoint_id: string
  generation: number
  expected_generation: number
  ledger_ref: string
}

export interface GroupSenderRetire {
  group_binding_id: string
  endpoint_id: string
  old_generation: number
  new_generation: number
  ledger_ref: string
  fence_revision: number
  retirement_receipt_ref: string
  applied_boundary_ref: string
  in_flight: GroupControlInFlight[]
}

export interface GroupSenderAck {
  group_binding_id: string
  endpoint_id: string
  generation: number
  command_id: string
  control_revision: number
  ledger_ref: string
  applied_boundary_ref: string
  fence_revision: number
  in_flight: GroupControlInFlight[]
}

export interface GroupSenderEndpoint {
  endpoint_id: string
  generation: number
  ledger_ref: string
  status: string
  applied_revision: number
  fence_revision: number
  retirement_receipt_ref: string | null
  /** 最后一次持久确认时间；ACTIVE 仅表示注册和门禁状态，连接状态仍未知。 */
  last_confirmed_at: string | null
  connection_state: 'UNKNOWN'
}

export interface GroupSenderRegistration {
  endpoint_id: string
  generation: number
  ledger_ref: string
  status: string
  control_revision: number
  retiring_generations: number[]
  current_state: GroupControlCurrent
}

export interface GroupDeliveryQualify {
  group_binding_id: string
  run_id: string
  message_id: string
  message_version: number
  delivery_id: string
  endpoint_id: string
  generation: number
}

export interface GroupDeliveryQualification {
  eligible: boolean
  reason: string
  delivery_id: string
  group_binding_id: string
  run_id: string
  root_trigger_id: string
  trigger_sequence: number
  message_id: string
  message_version: number
  content: string
  content_digest: string
  control_revision: number
  blocked_boundary_refs: string[]
}

export interface OpenPlatformSession {
  session_id: string
  integration_id: string
  external_conversation_ref: string
  end_user_id: string
  agent_id: string | null
  subject: Extract<SessionSubject, { kind: 'user' }>
}

/** 外部用户与执行身份的稳定映射，十进制字符串保持雪花ID精度。 */
export interface OpenPlatformEndUser {
  end_user_id: string
  integration_id: string
  external_user_ref: string
}

/** 响应直接使用后端 wire 字段；业务内容对象保持原样。 */
export interface OpenPlatformSessionEvents {
  session_id: string
  next_cursor: string | null
  events: Array<{
    event: string
    event_id: string
    session_id: string
    session_sequence: number | null
    input_id: string | null
    root_input_id: string | null
    caused_by_event_id: string | null
    event_version: number
    occurred_at: string
    data: Record<string, unknown>
  }>
}

export interface ResourceMappingInput {
  resourceId: string
  contentHash: string
}
export interface DeploymentInput {
  resourceType: OpenPlatformResourceType
  resourceId: string
  resourceRevision: number
  environment: string
}
export interface ChannelBindingInput {
  deploymentId: string
}
export interface ControlOperationInput {
  operationId: string
  rootOperationId?: string | null
  operationType: string
  items?: Array<{ itemKey: string; resourceRef: string }>
}
export interface CreateSessionInput {
  sessionId?: string | null
  externalUserRef: string
  externalConversationRef: string
  agentId: string
  endUserId: string
  businessScopeType?: string
  businessScopeRef?: string
}

/** 输入沿用聊天内容与授权选择器；业务context保留键名且无权覆盖认证来源。 */
export interface MessageInput {
  content: ChatContent[]
  context?: Record<string, unknown>
  references?: ChatInputReference[]
}
export interface ActionInput {
  executionId: string
  actionKey: string
  context?: Record<string, unknown>
}
export interface CallbackInput {
  callbackId: string
  eventType: 'business_event'
  executionId?: string | null
  workflowId?: string | null
  payload?: Record<string, unknown>
}
export interface CommandInput {
  command: 'cancel_execution' | 'close_session'
  executionId?: string | null
  reason?: string
}

/** 固定当前 ServicePrincipal 的机构/集成范围，声明业务范围不会扩大执行权限。 */
export interface OpenPlatformBusinessScope {
  business_scope_id: string
  tenant_id: string
  integration_id: string
  scope_type: string
  external_ref: string
  display_name: string
  status: 'active' | 'suspended' | 'revoked'
  revision: number
}

/** 受控部署必须显式选择定义和当前机构授权，不接受机构身份覆写。 */
export interface OpenPlatformAgentDeployInput extends DeploymentBindings {
  definition_id: string
  grant_id: string
  integration_id: string
  external_agent_ref: string
  name?: string
}

/** 同源实例显式切换授权；保留稳定实例和本地绑定，CAS对应当前部署修订。 */
export type OpenPlatformSwitchAuthorizationInput = Omit<DeploymentAuthorization, 'tenant_id'>

export interface OpenPlatformClient {
  groups: {
    memories: {
      extractionPolicy: {
        get(
          query: GroupMemoryExtractionPolicyQuery,
          options?: RequestOptions
        ): Promise<GroupMemoryExtractionPolicy>
        update(
          request: UpdateGroupMemoryExtractionPolicyInput,
          options?: RequestOptions
        ): Promise<GroupMemoryExtractionPolicy>
      }
      list(query: GroupMemoryQuery, options?: RequestOptions): Promise<GroupMemoryFact[]>
      create(
        request: CreateGroupMemoryInput,
        idempotencyKey: string,
        options?: RequestOptions
      ): Promise<GroupMemoryFact>
      review(
        request: ReviewGroupMemoryInput,
        idempotencyKey: string,
        options?: RequestOptions
      ): Promise<GroupMemoryFact>
      correct(
        request: CorrectGroupMemoryInput,
        idempotencyKey: string,
        options?: RequestOptions
      ): Promise<GroupMemoryFact>
      forget(
        request: GroupMemoryRevisionInput,
        idempotencyKey: string,
        options?: RequestOptions
      ): Promise<GroupMemoryFact>
      restore(
        request: GroupMemoryRevisionInput,
        idempotencyKey: string,
        options?: RequestOptions
      ): Promise<GroupMemoryFact>
    }
    senderEndpoints: {
      register(
        request: GroupSenderRegister,
        idempotencyKey: string,
        options?: RequestOptions
      ): Promise<GroupSenderRegistration>
      retire(
        request: GroupSenderRetire,
        idempotencyKey: string,
        options?: RequestOptions
      ): Promise<GroupSenderEndpoint>
      ack(
        request: GroupSenderAck,
        idempotencyKey: string,
        options?: RequestOptions
      ): Promise<GroupControlDurableAck>
    }
    controls: {
      command(
        request: GroupControlCommand,
        idempotencyKey: string,
        options?: RequestOptions
      ): Promise<GroupControlReceipt>
      authorize(
        request: GroupControlAuthorize,
        options?: RequestOptions
      ): Promise<GroupControlAuthorization>
      detail(
        groupBindingId: string,
        commandId: string,
        options?: RequestOptions
      ): Promise<GroupControlReceipt>
      current(groupBindingId: string, options?: RequestOptions): Promise<GroupControlCurrent>
      terminalize(
        request: GroupControlTerminalize,
        idempotencyKey: string,
        options?: RequestOptions
      ): Promise<GroupControlReceipt>
      release(
        request: GroupControlRelease,
        idempotencyKey: string,
        options?: RequestOptions
      ): Promise<GroupControlReleaseReceipt>
    }
    deliveries: {
      qualify(
        request: GroupDeliveryQualify,
        options?: RequestOptions
      ): Promise<GroupDeliveryQualification>
    }
    history(query: GroupReadQuery, options?: RequestOptions): Promise<GroupHistoryPage>
    events(query: GroupReadQuery, options?: RequestOptions): Promise<GroupEventsPage>
    /** 返回原 SSE Response 供增量消费；关闭连接只结束观察，不取消 Run。 */
    stream(query: Omit<GroupReadQuery, 'page_size'>, options?: RequestOptions): Promise<Response>
    /** 不自动重连；收到不可披露状态后返回原游标供人工核对。 */
    observe(
      query: Omit<GroupReadQuery, 'page_size'>,
      onEvent: (event: StreamEvent) => void,
      options?: RequestOptions
    ): Promise<GroupObservation>
    messages: {
      record(
        request: RecordGroupMessageInput,
        idempotencyKey: string,
        options?: RequestOptions
      ): Promise<GroupMessageReceipt>
    }
    triggers: {
      create(
        request: CreateGroupTriggerInput,
        idempotencyKey: string,
        options?: RequestOptions
      ): Promise<GroupTriggerReceipt>
      detail(
        groupBindingId: string,
        triggerId: string,
        options?: RequestOptions
      ): Promise<GroupTriggerReceipt>
    }
    runs: {
      detail(
        groupBindingId: string,
        runId: string,
        options?: RequestOptions
      ): Promise<GroupRunDetail>
      respond(request: RespondGroupRunInput, options?: RequestOptions): Promise<GroupRunResponse>
      command(request: GroupRunCommandInput, options?: RequestOptions): Promise<GroupRunResponse>
    }
    ensure(
      request: EnsureGroupInput,
      idempotencyKey: string,
      options?: RequestOptions
    ): Promise<OpenPlatformGroup>
    close(
      request: CloseGroupInput,
      idempotencyKey: string,
      options?: RequestOptions
    ): Promise<OpenPlatformGroup>
    detail(groupBindingId: string, options?: RequestOptions): Promise<OpenPlatformGroup>
    members: {
      list(
        query: GroupDirectoryQuery,
        options?: RequestOptions
      ): Promise<GroupDirectoryPage<OpenPlatformGroupMember>>
      update(
        request: UpdateGroupMemberInput,
        idempotencyKey: string,
        options?: RequestOptions
      ): Promise<OpenPlatformGroupMember>
    }
    agents: {
      list(
        query: GroupDirectoryQuery,
        options?: RequestOptions
      ): Promise<GroupDirectoryPage<OpenPlatformGroupAgent>>
      update(
        request: UpdateGroupAgentInput,
        idempotencyKey: string,
        options?: RequestOptions
      ): Promise<OpenPlatformGroupAgent>
    }
  }
  businessScopes: {
    ensure(
      request: { scope_type: string; external_ref: string; display_name: string },
      idempotencyKey: string,
      options?: RequestOptions
    ): Promise<OpenPlatformBusinessScope>
    status(
      request: {
        scope_id: string
        status: 'active' | 'suspended' | 'revoked'
        expected_revision: number
      },
      idempotencyKey: string,
      options?: RequestOptions
    ): Promise<OpenPlatformBusinessScope>
  }
  agentDeployments: {
    list(options?: RequestOptions): Promise<{ total: number; items: AgentDeployment[] }>
    detail(deploymentId: string, options?: RequestOptions): Promise<AgentDeployment>
    definitions(options?: RequestOptions): Promise<{ total: number; items: DeploymentTemplate[] }>
    grants(options?: RequestOptions): Promise<{ total: number; items: AgentGrant[] }>
    create(
      request: OpenPlatformAgentDeployInput,
      idempotencyKey: string,
      options?: RequestOptions
    ): Promise<AgentDeployment>
    bindings(
      request: DeploymentBindings & { deployment_id: string; expected_revision: number },
      idempotencyKey: string,
      options?: RequestOptions
    ): Promise<AgentDeployment>
    authorization(
      request: OpenPlatformSwitchAuthorizationInput,
      idempotencyKey: string,
      options?: RequestOptions
    ): Promise<AgentDeployment>
  }
  usage: {
    events(
      query?: UsageQuery & { cursor?: string; limit?: number },
      options?: RequestOptions
    ): Promise<UsagePage>
    summary(query?: UsageQuery, options?: RequestOptions): Promise<UsageSummary>
    details(
      query?: UsageQuery & { cursor?: string; limit?: number },
      options?: RequestOptions
    ): Promise<UsagePage>
  }
  endUsers: {
    ensure(
      externalUserRef: string,
      displayName?: string,
      options?: RequestOptions
    ): Promise<OpenPlatformEndUser>
  }
  resources: {
    list(
      filters?: {
        resourceType?: OpenPlatformResourceType
        status?: OpenPlatformResourceStatus
        limit?: number
      },
      options?: RequestOptions
    ): Promise<OpenPlatformResourceCatalogItem[]>
    createDraft(
      resourceType: OpenPlatformResourceType,
      resourceId: string,
      content: Record<string, unknown>,
      options?: RequestOptions
    ): Promise<OpenPlatformResourceRevision>
    latest(
      resourceType: OpenPlatformResourceType,
      resourceId: string,
      options?: RequestOptions
    ): Promise<OpenPlatformResourceRevision>
    revision(
      resourceType: OpenPlatformResourceType,
      resourceId: string,
      revision: number,
      options?: RequestOptions
    ): Promise<OpenPlatformResourceRevision>
    updateDraft(
      resourceType: OpenPlatformResourceType,
      resourceId: string,
      revision: number,
      content: Record<string, unknown>,
      ifMatch?: string,
      options?: RequestOptions
    ): Promise<OpenPlatformResourceRevision>
    ready(
      resourceType: OpenPlatformResourceType,
      resourceId: string,
      revision: number,
      ifMatch?: string,
      options?: RequestOptions
    ): Promise<OpenPlatformResourceRevision>
    publish(
      resourceType: OpenPlatformResourceType,
      resourceId: string,
      revision: number,
      ifMatch?: string,
      options?: RequestOptions
    ): Promise<OpenPlatformResourceRevision>
    getMapping(
      resourceType: OpenPlatformResourceType,
      externalRef: string,
      options?: RequestOptions
    ): Promise<OpenPlatformResourceMapping>
    upsertMapping(
      resourceType: OpenPlatformResourceType,
      externalRef: string,
      request: ResourceMappingInput,
      expectedRevision?: number,
      ifMatch?: string,
      options?: RequestOptions
    ): Promise<OpenPlatformResourceMapping>
    deploy(
      deploymentId: string,
      request: DeploymentInput,
      expectedRevision?: number,
      ifMatch?: string,
      options?: RequestOptions
    ): Promise<OpenPlatformDeployment>
    bindChannel(
      channelId: string,
      request: ChannelBindingInput,
      expectedRevision?: number,
      ifMatch?: string,
      options?: RequestOptions
    ): Promise<OpenPlatformChannelBinding>
  }
  operations: {
    accept(
      request: ControlOperationInput,
      idempotencyKey: string,
      options?: RequestOptions
    ): Promise<OpenPlatformControlOperation>
    get(operationId: string, options?: RequestOptions): Promise<OpenPlatformControlOperation>
  }
  sessions: {
    create(request: CreateSessionInput, options?: RequestOptions): Promise<OpenPlatformSession>
    message(
      sessionId: string,
      request: MessageInput,
      idempotencyKey: string,
      options?: RequestOptions
    ): Promise<OpenPlatformInboundReceipt>
    action(
      sessionId: string,
      request: ActionInput,
      idempotencyKey: string,
      options?: RequestOptions
    ): Promise<OpenPlatformInboundReceipt>
    callback(
      sessionId: string,
      request: CallbackInput,
      idempotencyKey: string,
      options?: RequestOptions
    ): Promise<OpenPlatformInboundReceipt>
    command(
      sessionId: string,
      request: CommandInput,
      idempotencyKey: string,
      options?: RequestOptions
    ): Promise<OpenPlatformInboundReceipt>
    events(
      sessionId: string,
      cursor?: string,
      limit?: number,
      options?: RequestOptions
    ): Promise<OpenPlatformSessionEvents>
  }
}

function encode(value: string): string {
  return encodeURIComponent(value)
}

function withHeader(
  options: RequestOptions | undefined,
  name: string,
  value: string
): RequestOptions {
  const headers = new Headers(options?.headers)
  headers.set(name, value)
  return { ...options, headers }
}

function withOptionalHeaders(
  options: RequestOptions | undefined,
  values: Record<string, string | undefined>
): RequestOptions {
  const headers = new Headers(options?.headers)
  for (const [name, value] of Object.entries(values)) {
    if (value !== undefined) headers.set(name, value)
  }
  return { ...options, headers }
}

export function createOpenPlatformClient(
  http: Pick<HttpClient, 'get' | 'post' | 'put'>
): OpenPlatformClient {
  /** 过滤值原样编码，游标始终不透明；未传 cursor 从当前授权历史起点开始。 */
  const queryString = (query: object = {}) => {
    const params = new URLSearchParams()
    for (const [key, value] of Object.entries(query))
      if (value !== undefined) params.set(key, String(value))
    return params.size ? `?${params}` : ''
  }
  return {
    groups: {
      memories: {
        extractionPolicy: {
          get: (query, options) =>
            http.get<GroupMemoryExtractionPolicy>(
              `/open-platform/groups/memories/extraction-policy${queryString(query)}`,
              options
            ),
          update: (request, options) =>
            http.put<GroupMemoryExtractionPolicy>(
              '/open-platform/groups/memories/extraction-policy',
              request,
              options
            )
        },
        list: (query, options) =>
          http.get<GroupMemoryFact[]>(
            `/open-platform/groups/memories${queryString(query)}`,
            options
          ),
        create: (request, key, options) =>
          http.post<GroupMemoryFact>(
            '/open-platform/groups/memories/create',
            request,
            withHeader(options, 'Idempotency-Key', key)
          ),
        review: (request, key, options) =>
          http.post<GroupMemoryFact>(
            '/open-platform/groups/memories/review',
            request,
            withHeader(options, 'Idempotency-Key', key)
          ),
        correct: (request, key, options) =>
          http.post<GroupMemoryFact>(
            '/open-platform/groups/memories/correct',
            request,
            withHeader(options, 'Idempotency-Key', key)
          ),
        forget: (request, key, options) =>
          http.post<GroupMemoryFact>(
            '/open-platform/groups/memories/forget',
            request,
            withHeader(options, 'Idempotency-Key', key)
          ),
        restore: (request, key, options) =>
          http.post<GroupMemoryFact>(
            '/open-platform/groups/memories/restore',
            request,
            withHeader(options, 'Idempotency-Key', key)
          )
      },
      senderEndpoints: {
        register: (request, key, options) =>
          http.post<GroupSenderRegistration>(
            '/open-platform/groups/sender-endpoints/register',
            request,
            withHeader(options, 'Idempotency-Key', key)
          ),
        retire: (request, key, options) =>
          http.post<GroupSenderEndpoint>(
            '/open-platform/groups/sender-endpoints/retire',
            request,
            withHeader(options, 'Idempotency-Key', key)
          ),
        ack: (request, key, options) =>
          http.post<GroupControlDurableAck>(
            '/open-platform/groups/sender-endpoints/ack',
            request,
            withHeader(options, 'Idempotency-Key', key)
          )
      },
      controls: {
        command: (request, key, options) =>
          http.post<GroupControlReceipt>(
            '/open-platform/groups/controls',
            request,
            withHeader(options, 'Idempotency-Key', key)
          ),
        authorize: (request, options) =>
          http.post<GroupControlAuthorization>(
            '/open-platform/groups/controls/authorize',
            request,
            options
          ),
        detail: (group, command, options) =>
          http.get<GroupControlReceipt>(
            `/open-platform/groups/controls/detail?group_binding_id=${encode(group)}&command_id=${encode(command)}`,
            options
          ),
        current: (group, options) =>
          http.get<GroupControlCurrent>(
            `/open-platform/groups/controls/current?group_binding_id=${encode(group)}`,
            options
          ),
        terminalize: (request, key, options) =>
          http.post<GroupControlReceipt>(
            '/open-platform/groups/controls/terminalize',
            request,
            withHeader(options, 'Idempotency-Key', key)
          ),
        release: (request, key, options) =>
          http.post<GroupControlReleaseReceipt>(
            '/open-platform/groups/controls/release',
            request,
            withHeader(options, 'Idempotency-Key', key)
          )
      },
      deliveries: {
        qualify: (request, options) =>
          http.post<GroupDeliveryQualification>(
            '/open-platform/groups/deliveries/qualify',
            request,
            options
          )
      },
      history: (query, options) =>
        http.get<GroupHistoryPage>(`/open-platform/groups/history${queryString(query)}`, options),
      events: (query, options) =>
        http.get<GroupEventsPage>(`/open-platform/groups/events${queryString(query)}`, options),
      stream: (query, options) =>
        http.get<Response>(`/open-platform/groups/stream${queryString(query)}`, {
          ...withHeader(options, 'Accept', 'text/event-stream'),
          responseType: 'stream'
        }),
      observe: async (query, onEvent, options) => {
        const controller = new AbortController()
        const abort = () => controller.abort(options?.signal?.reason)
        options?.signal?.addEventListener('abort', abort, { once: true })
        if (options?.signal?.aborted) abort()
        let cursor = query.cursor ?? new Headers(options?.headers).get('Last-Event-ID') ?? null
        let blocked = false
        try {
          const response = await http.get<Response>(
            `/open-platform/groups/stream${queryString(query)}`,
            {
              ...withHeader(options, 'Accept', 'text/event-stream'),
              responseType: 'stream',
              signal: controller.signal
            }
          )
          if (options?.signal?.aborted) throw options.signal.reason
          if (response.status === 204)
            return { state: 'identity_undisclosable', cursor, message: GROUP_IDENTITY_MESSAGE }
          await consumeSse(
            response,
            (event) => {
              if (
                event.event === 'error' &&
                event.data.error_code === 'GROUP_EVENT_ID_UNDISCLOSABLE' &&
                event.data.retryable === false
              ) {
                blocked = true
                controller.abort()
                return
              }
              if (
                !event.id ||
                event.data.event_id !== event.id ||
                typeof event.data.session_id !== 'string' ||
                !event.data.session_id ||
                typeof event.data.session_sequence !== 'number' ||
                !Number.isInteger(event.data.session_sequence) ||
                event.data.session_sequence < 1 ||
                event.data.event_version !== 1
              )
                throw new SsePayloadError()
              onEvent(event)
              if (event.id) cursor = event.id
            },
            controller.signal,
            undefined,
            true
          )
          if (options?.signal?.aborted) throw options.signal.reason
          return blocked
            ? { state: 'identity_undisclosable', cursor, message: GROUP_IDENTITY_MESSAGE }
            : { state: 'ended', cursor }
        } catch (failure) {
          if (failure instanceof SsePayloadError) {
            controller.abort()
            return { state: 'invalid_event', cursor, message: failure.message }
          }
          throw failure
        } finally {
          options?.signal?.removeEventListener('abort', abort)
        }
      },
      messages: {
        record: (request, key, options) =>
          http.post<GroupMessageReceipt>(
            '/open-platform/groups/messages',
            request,
            withHeader(options, 'Idempotency-Key', key)
          )
      },
      triggers: {
        create: (request, key, options) =>
          http.post<GroupTriggerReceipt>(
            '/open-platform/groups/triggers',
            request,
            withHeader(options, 'Idempotency-Key', key)
          ),
        detail: (group, trigger, options) =>
          http.get<GroupTriggerReceipt>(
            `/open-platform/groups/triggers/detail?group_binding_id=${encode(group)}&trigger_id=${encode(trigger)}`,
            options
          )
      },
      runs: {
        detail: (group, run, options) =>
          http.get<GroupRunDetail>(
            `/open-platform/groups/runs/detail?group_binding_id=${encode(group)}&run_id=${encode(run)}`,
            options
          ),
        respond: (request, options) =>
          http.post<GroupRunResponse>(
            '/open-platform/groups/runs/respond',
            request,
            withHeader(options, 'Idempotency-Key', request.input_id)
          ),
        command: (request, options) =>
          http.post<GroupRunResponse>(
            '/open-platform/groups/runs/commands',
            request,
            withHeader(options, 'Idempotency-Key', request.input_id)
          )
      },
      ensure: (request, key, options) =>
        http.post<OpenPlatformGroup>(
          '/open-platform/groups/ensure',
          request,
          withHeader(options, 'Idempotency-Key', key)
        ),
      close: (request, key, options) =>
        http.post<OpenPlatformGroup>(
          '/open-platform/groups/close',
          request,
          withHeader(options, 'Idempotency-Key', key)
        ),
      detail: (id, options) =>
        http.get<OpenPlatformGroup>(
          `/open-platform/groups/detail?group_binding_id=${encode(id)}`,
          options
        ),
      members: {
        list: (query, options) =>
          http.get<GroupDirectoryPage<OpenPlatformGroupMember>>(
            `/open-platform/groups/members${queryString(query)}`,
            options
          ),
        update: (request, key, options) =>
          http.post<OpenPlatformGroupMember>(
            '/open-platform/groups/members/update',
            request,
            withHeader(options, 'Idempotency-Key', key)
          )
      },
      agents: {
        list: (query, options) =>
          http.get<GroupDirectoryPage<OpenPlatformGroupAgent>>(
            `/open-platform/groups/agents${queryString(query)}`,
            options
          ),
        update: (request, key, options) =>
          http.post<OpenPlatformGroupAgent>(
            '/open-platform/groups/agents/update',
            request,
            withHeader(options, 'Idempotency-Key', key)
          )
      }
    },
    businessScopes: {
      ensure: (request, key, options) =>
        http.post<OpenPlatformBusinessScope>(
          '/open-platform/business-scopes/ensure',
          request,
          withHeader(options, 'Idempotency-Key', key)
        ),
      status: (request, key, options) =>
        http.post<OpenPlatformBusinessScope>(
          '/open-platform/business-scopes/status',
          request,
          withHeader(options, 'Idempotency-Key', key)
        )
    },
    agentDeployments: {
      list: (options) =>
        http.get<{ total: number; items: AgentDeployment[] }>(
          '/open-platform/agent-deployments',
          options
        ),
      detail: (id, options) =>
        http.get<AgentDeployment>(
          `/open-platform/agent-deployments/detail?deployment_id=${encode(id)}`,
          options
        ),
      definitions: (options) =>
        http.get<{ total: number; items: DeploymentTemplate[] }>(
          '/open-platform/agent-deployments/definitions',
          options
        ),
      grants: (options) =>
        http.get<{ total: number; items: AgentGrant[] }>(
          '/open-platform/agent-deployments/grants',
          options
        ),
      create: (request, key, options) =>
        http.post<AgentDeployment>(
          '/open-platform/agent-deployments',
          request,
          withHeader(options, 'Idempotency-Key', key)
        ),
      bindings: (request, key, options) =>
        http.put<AgentDeployment>(
          '/open-platform/agent-deployments/bindings',
          request,
          withHeader(options, 'Idempotency-Key', key)
        ),
      authorization: (request, key, options) =>
        http.post<AgentDeployment>(
          '/open-platform/agent-deployments/authorization',
          request,
          withHeader(options, 'Idempotency-Key', key)
        )
    },
    usage: {
      events: (query, options) =>
        http.get<UsagePage>(`/open-platform/usage/events${queryString(query)}`, options),
      summary: (query, options) =>
        http.get<UsageSummary>(`/open-platform/usage/summary${queryString(query)}`, options),
      details: (query, options) =>
        http.get<UsagePage>(`/open-platform/usage/details${queryString(query)}`, options)
    },
    endUsers: {
      ensure(externalUserRef, displayName, options) {
        return http.post<OpenPlatformEndUser>(
          '/open-platform/end-users/ensure',
          { external_user_ref: externalUserRef, display_name: displayName },
          options
        )
      }
    },
    resources: {
      list(filters = {}, options) {
        const query = new URLSearchParams()
        if (filters.resourceType) query.set('resourceType', filters.resourceType)
        if (filters.status) query.set('status', filters.status)
        if (filters.limit !== undefined) query.set('limit', String(filters.limit))
        const suffix = query.toString()
        return http.get<OpenPlatformResourceCatalogItem[]>(
          `/open-platform/resources${suffix ? `?${suffix}` : ''}`,
          options
        )
      },
      createDraft(resourceType, resourceId, content, options) {
        return http.post<OpenPlatformResourceRevision>(
          '/open-platform/resources/revisions',
          { resource_type: resourceType, resource_id: resourceId, payload: { content } },
          options
        )
      },
      latest(resourceType, resourceId, options) {
        return http.get<OpenPlatformResourceRevision>(
          `/open-platform/resources/revisions/latest?resourceType=${encode(resourceType)}&resourceId=${encode(resourceId)}`,
          options
        )
      },
      revision(resourceType, resourceId, revision, options) {
        return http.get<OpenPlatformResourceRevision>(
          `/open-platform/resources/revisions/detail?resourceType=${encode(resourceType)}&resourceId=${encode(resourceId)}&revision=${revision}`,
          options
        )
      },
      updateDraft(resourceType, resourceId, revision, content, ifMatch, options) {
        return http.put<OpenPlatformResourceRevision>(
          '/open-platform/resources/revisions/update',
          { resource_type: resourceType, resource_id: resourceId, revision, payload: { content } },
          withOptionalHeaders(options, { 'If-Match': ifMatch })
        )
      },
      ready(resourceType, resourceId, revision, ifMatch, options) {
        return http.post<OpenPlatformResourceRevision>(
          '/open-platform/resources/revisions/ready',
          { resource_type: resourceType, resource_id: resourceId, revision },
          withOptionalHeaders(options, { 'If-Match': ifMatch })
        )
      },
      publish(resourceType, resourceId, revision, ifMatch, options) {
        return http.post<OpenPlatformResourceRevision>(
          '/open-platform/resources/revisions/publish',
          { resource_type: resourceType, resource_id: resourceId, revision },
          withOptionalHeaders(options, { 'If-Match': ifMatch })
        )
      },
      getMapping(resourceType, externalRef, options) {
        return http.get<OpenPlatformResourceMapping>(
          `/open-platform/resources/detail?resourceType=${encode(resourceType)}&externalRef=${encode(externalRef)}`,
          options
        )
      },
      upsertMapping(resourceType, externalRef, request, expectedRevision, ifMatch, options) {
        return http.put<OpenPlatformResourceMapping>(
          '/open-platform/resources/update',
          {
            resource_type: resourceType,
            external_ref: externalRef,
            payload: { resource_id: request.resourceId, content_hash: request.contentHash }
          },
          withOptionalHeaders(options, {
            'X-Resource-Revision':
              expectedRevision === undefined ? undefined : String(expectedRevision),
            'If-Match': ifMatch
          })
        )
      },
      deploy(deploymentId, request, expectedRevision, ifMatch, options) {
        return http.put<OpenPlatformDeployment>(
          '/open-platform/deployments/update',
          {
            deployment_id: deploymentId,
            payload: {
              resource_type: request.resourceType,
              resource_id: request.resourceId,
              resource_revision: request.resourceRevision,
              environment: request.environment
            }
          },
          withOptionalHeaders(options, {
            'X-Deployment-Revision':
              expectedRevision === undefined ? undefined : String(expectedRevision),
            'If-Match': ifMatch
          })
        )
      },
      bindChannel(channelId, request, expectedRevision, ifMatch, options) {
        return http.put<OpenPlatformChannelBinding>(
          '/open-platform/channels/binding',
          { channel_id: channelId, payload: { deployment_id: request.deploymentId } },
          withOptionalHeaders(options, {
            'X-Binding-Revision':
              expectedRevision === undefined ? undefined : String(expectedRevision),
            'If-Match': ifMatch
          })
        )
      }
    },
    operations: {
      accept(request, idempotencyKey, options) {
        return http.post<OpenPlatformControlOperation>(
          '/open-platform/control-operations',
          {
            operation_id: request.operationId,
            root_operation_id: request.rootOperationId,
            operation_type: request.operationType,
            items: request.items?.map((item) => ({
              item_key: item.itemKey,
              resource_ref: item.resourceRef
            }))
          },
          withHeader(options, 'Idempotency-Key', idempotencyKey)
        )
      },
      get(operationId, options) {
        return http.get<OpenPlatformControlOperation>(
          `/open-platform/control-operations/detail?operationId=${encode(operationId)}`,
          options
        )
      }
    },
    sessions: {
      create(request, options) {
        return http.post<OpenPlatformSession>(
          '/open-platform/sessions',
          {
            session_id: request.sessionId,
            external_user_ref: request.externalUserRef,
            external_conversation_ref: request.externalConversationRef,
            agent_id: request.agentId,
            end_user_id: request.endUserId,
            business_scope_type: request.businessScopeType,
            business_scope_ref: request.businessScopeRef
          },
          options
        )
      },
      message(sessionId, request, idempotencyKey, options) {
        return http.post<OpenPlatformInboundReceipt>(
          '/open-platform/sessions/messages',
          {
            session_id: sessionId,
            payload: {
              content: request.content,
              context: request.context ?? {},
              references: request.references ?? []
            }
          },
          withHeader(options, 'Idempotency-Key', idempotencyKey)
        )
      },
      action(sessionId, request, idempotencyKey, options) {
        return http.post<OpenPlatformInboundReceipt>(
          '/open-platform/sessions/actions',
          {
            session_id: sessionId,
            payload: {
              execution_id: request.executionId,
              action_key: request.actionKey,
              context: request.context
            }
          },
          withHeader(options, 'Idempotency-Key', idempotencyKey)
        )
      },
      callback(sessionId, request, idempotencyKey, options) {
        return http.post<OpenPlatformInboundReceipt>(
          '/open-platform/sessions/callbacks',
          {
            session_id: sessionId,
            payload: {
              callback_id: request.callbackId,
              event_type: request.eventType,
              execution_id: request.executionId,
              workflow_id: request.workflowId,
              payload: request.payload
            }
          },
          withHeader(options, 'Idempotency-Key', idempotencyKey)
        )
      },
      command(sessionId, request, idempotencyKey, options) {
        return http.post<OpenPlatformInboundReceipt>(
          '/open-platform/sessions/commands',
          {
            session_id: sessionId,
            payload: {
              command: request.command,
              execution_id: request.executionId,
              reason: request.reason
            }
          },
          withHeader(options, 'Idempotency-Key', idempotencyKey)
        )
      },
      events(sessionId, cursor, limit, options) {
        const query = new URLSearchParams({ sessionId })
        if (cursor) query.set('cursor', cursor)
        if (limit !== undefined) query.set('limit', String(limit))
        const suffix = query.toString()
        return http.get<OpenPlatformSessionEvents>(
          `/open-platform/sessions/events?${suffix}`,
          options
        )
      }
    }
  }
}
