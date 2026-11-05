/** 独立会话端的公开 HTTP / SSE 数据契约；与后台源码没有依赖关系。 */
export type Credentials =
  { mode: 'token'; token: string } | { mode: 'hmac'; apiKey: string; secret: string }
export type Target = { agent_id: string; team_id?: never } | { team_id: string; agent_id?: never }
export interface Agent {
  id: string
  name: string
  is_default: boolean
  role_description?: string
}
export interface Team {
  id: string
  revision: number
  name: string
  description: string
  status: string
  primary_agent_id: string
  members: { agent_id: string; name?: string }[]
}
export interface Session {
  session_id: string
  summary?: string
  title?: string | null
  message_count: number
  agent_id?: string
  team_id?: string
}
export interface Attachment {
  id: string
  name: string
  input_path: string
  size: number
  sha256: string
  expires_at: string
}
export interface Media {
  media_id: string
  mime_type: string
  byte_length: number
  width: number
  height: number
}
export type Content = { type: 'text'; text: string } | { type: 'image'; media_id: string }
export interface Reference {
  label: string
  source: Record<string, unknown>
}
/** 实际采用的知识依据标记；可定位消息内依据，不授予材料读取权限。 */
export interface Citation {
  ordinal: number
  item_id: string
  source_id: string
  source_version: string
  label: string
  citation_id: string
}
export type MessageState = 'streaming' | 'completed' | 'failed' | 'cancelled' | 'outcome_unknown'
export type MessagePhase = 'pending' | 'commentary' | 'final'
/** 标识仅存在于浏览器内的回执占位消息。 */
export const provisionalMessage = Symbol('provisional-message')
/** 仅用于当前页面的安全候选，不参与正式消息历史。 */
export interface MessagePreview {
  preview_message_id: string
  session_id: string
  run_id: string
  root_input_id: string
  input_id: string
  attempt_id: string
  ordinal: number
  preview_sequence: number
  role: 'assistant'
  content: string
  state: 'streaming' | 'rejected' | 'unavailable' | 'failed' | 'unknown' | 'cancelled'
  authoritative: false
}
export interface Message {
  [provisionalMessage]?: true
  message_id: string
  message_version: number
  role: string
  content: string
  citations?: Citation[]
  run_id?: string
  root_run_id?: string
  root_input_id?: string
  input_id?: string
  phase?: MessagePhase
  state?: MessageState
  sequence?: number
  created_at?: string
  metadata?: {
    content_parts?: Content[]
    citations?: Citation[]
    phase?: MessagePhase
    root_run_id?: string
    root_input_id?: string
    source_sequence?: number
    [key: string]: unknown
  }
  card?: Card
  references?: Reference[]
}
export interface Process {
  process_id: string
  run_id: string
  root_run_id: string
  root_input_id?: string
  input_id?: string
  reference_id?: string
  actor_agent_id?: string
  actor_name?: string
  stage?: { id: string; index: number }
  started_at?: string
  finished_at?: string
  changes?: { artifacts?: boolean; team_work?: boolean }
  sequence: number
  ordinal: number
  updated_sequence: number
  status: string
  type: string
  presentation: {
    title: string
    summary: string
    results?: (
      | { kind: 'text'; text: string; format: string }
      | { kind: 'table'; columns: { key: string; label: string }[]; rows: unknown[][] }
    )[]
    references?: Reference[]
  }
}
export interface Schema {
  type?: string
  title?: string
  description?: string
  properties?: Record<string, Schema>
  required?: string[]
  enum?: (string | number | boolean)[]
  enumNames?: string[]
  minimum?: number
  maximum?: number
  minLength?: number
  maxLength?: number
  format?: string
  default?: unknown
}
export interface Card {
  type?: string
  title?: string
  body?: string
  plan?: string
  actions?: { id?: string; type?: string; label?: string; [key: string]: unknown }[]
  requests?: { id: string; message: string; requested_schema: Schema }[]
  [key: string]: unknown
}
export interface Pending {
  execution_id: string
  run_revision: number | string
  checkpoint_revision?: number | string
  tool_wait?: { action_id: string; generation: string }
  interaction_type?: string
  action_id?: string
  invocation_id?: string
  pending_id?: string
  wake_id?: string
  wait_revision?: number | string
  pending_ref?: string
  question?: string
  workflow_execution_id?: string
  execution_revision?: string
  suspend_ref?: string
  expected_revision?: number | string
  resume_action_key?: string
  card?: Card
}
export interface Collaboration {
  active: boolean
  pending: boolean
  selected_active: boolean
}
export interface History {
  closed: boolean
  history_retention?: HistoryRetention | null
  session_id: string
  messages: Message[]
  processes: Process[]
  pending_interactions: Pending[]
  collaboration: Collaboration
  work_plans?: Work[]
  event_cursor: string | null
  has_more: boolean
  next_before_message_id?: string
  activities?: {
    node_id?: string
    node_type?: string
    status?: string
    render?: {
      root_run_id?: string
      root_input_id?: string
      updated_sequence?: number
      reason?: string
    }
    message_id?: string
    card?: Card
  }[]
}
/** 已退休展示流的公开状态；缺少推理delta时不能视为完整历史。 */
export interface HistoryRetention {
  source_session_revision: number
  closed_at: string
  retired_through_sequence: number
  retired_event_count: number
  retired_event_types: string[]
  complete: boolean
}
export interface ChatInput {
  session_id: string
  agent_id?: string
  team_id?: string
  client_message_id: string
  content: Content[]
  context: { end_user_id: string; attachment_ids?: string[] }
  hitl_response?: Record<string, unknown>
  references?: Record<string, unknown>[]
}
export interface ChatResult {
  status: string
  session_id: string
  execution_id?: string
  input_id?: string
  message?: string
  card?: Card
  collaboration?: Collaboration
}
export interface SteeringInput {
  session_id: string
  run_id: string
  command_id: string
  text: string
}
export interface SteeringState {
  run_id: string
  accepting: boolean
  items: { command_id: string; text: string; status: 'PENDING' | 'INCLUDED' | 'CONSUMED' }[]
}
export interface StreamEvent {
  id: string
  event: string
  data: Record<string, unknown>
}
export interface Limits {
  attachments: {
    max_file_bytes: number
    max_files: number
    max_total_bytes: number
    extensions: string[]
  }
  references: { max_items: number; max_file_selectors: number }
}
export interface GoalStatus {
  goal: null | {
    goal_id: string
    revision: number
    objective: string
    state: string
    max_rounds: number
    rounds_started: number
    reason?: string
  }
  continuation_enabled: boolean
  completion_pending: boolean
}
export interface Deliverable {
  id: string
  deliverable_id: string
  session_id: string
  run_id: string
  result_run_ids: string[]
  type: 'file' | 'website'
  name: string
  size: number
  sha256: string
  expires_at: string | null
  revision: number
  current: boolean
  status: string
  files: {
    file_id: string
    artifact_id: string
    path: string
    download_name: string
    format: string
    size: number
    sha256: string
    representation_id: string
    primary_preview: boolean
    entry: boolean
  }[]
  artifact_inputs: { artifact_id: string; path: string; size: number; sha256: string }[]
  evidence: { artifact_id: string; path: string; summary: string }[]
}
export interface Devices {
  items: {
    device_id: string
    browser_id: string
    label: string
    connected: boolean
    expires_at: string
  }[]
  installation_url: string
  selected_browser_id?: string
}
export type { BrowserConnectionDetail } from './ui/browserConnection'
export interface WorkPending {
  pending_id: string
  parameters_version: string
  prompt: string
  kind: string
  execution_id?: string
  run_revision?: string
  tool_wait?: { action_id: string; generation: string }
  card?: Card
}
export interface WorkTask {
  task_id: string
  run_id?: string | null
  task_kind: 'plan' | 'direct'
  title: string
  agent_name: string
  executor_type?: 'PRIMARY' | 'MEMBER'
  goal?: string
  expected_output?: string
  constraints?: string[]
  depends_on?: string[]
  wait_reason?: string | null
  status: string
  pending?: WorkPending
  details_access: string
}
export interface Work {
  work_id: string
  session_id: string
  input_id: string
  title?: string
  run_revision?: number | null
  run_status?: string | null
  stage?: string | null
  change_reason?: string | null
  notice?: string | null
  revision: number
  status: string
  run_id: string
  plan_revision: number
  approved_revision?: number | null
  approval_required: boolean
  control_inputs?: {
    input_id: string
    task_id: string
    operation: string
    plan_revision: number | null
    state: 'ACCEPTED' | 'PENDING' | 'APPLIED' | 'REJECTED'
    detail?: string | null
  }[]
  tasks: WorkTask[]
  results: {
    result_id: string
    title: string
    content?: string
    summary?: string
    limitations?: string[]
  }[]
  pending?: WorkPending
}
