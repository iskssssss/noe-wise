/**
 * 会话级 规划模式 协作状态。
 *
 * pending 为 true 时，selected_active 表示下一轮选中的目标状态；
 * active 仍表示当前运行实际使用的状态。
 */
export interface ChatCollaborationState {
  active: boolean
  pending: boolean
  selected_active: boolean
}

/** SSE collaboration.updated；command_completed 只标记无 Run 的纯 Plan 命令。 */
export interface ChatCollaborationUpdate extends ChatCollaborationState {
  session_id: string
  command_completed?: true
}

export type RuntimeRevision = number | string

/** Plan Review 专用展示卡，plan 为完整 Markdown 文档。 */
export interface PlanReviewCard {
  type: 'plan_review'
  title: string
  plan: string
}

/** Runtime 挂起并等待用户评审 Plan 时返回的精确恢复身份。 */
export interface PlanReviewPendingInteraction {
  interaction_type: 'plan_review'
  execution_id: string
  run_revision: RuntimeRevision
  checkpoint_revision: RuntimeRevision
  action_id: string
  invocation_id: string
  pending_id: string
  wake_id: string
  wait_revision: RuntimeRevision
  title: string
  plan: string
  card: PlanReviewCard
}

export type PlanReviewDecision = 'approve' | 'keep_planning'

/** POST /chat 的 Plan Review 专用回答载荷。 */
export interface PlanReviewResponse {
  execution_id: string
  run_revision: RuntimeRevision
  checkpoint_revision: RuntimeRevision
  action_id: string
  invocation_id: string
  pending_id: string
  wake_id: string
  wait_revision: RuntimeRevision
  plan_review: {
    decision: PlanReviewDecision
    feedback: string
  }
}

export interface ChatClarificationResponse {
  execution_id: string
  run_revision?: RuntimeRevision
  pending_ref: string
  checkpoint_revision?: RuntimeRevision
  response: string
}

/** AskUser的真实回答绑定原逻辑Action与准确等待代次。 */
export interface ChatHumanInputResponse {
  execution_id: string
  run_revision: string
  human_input: {
    wait: { action_id: string; generation: string }
    text: string
  }
}

export interface ChatWorkflowResponse {
  execution_id: string
  run_revision: string
  workflow_input: {
    wait: { action_id: string; generation: string }
    execution_ref: string
    suspend_ref: string
    execution_revision: string
    action_key: string
    response: { schema_id: string; canonical_json: string }
  }
}

export interface ChatWakeCandidateResponse {
  candidate_set_id: string
  candidate_index: number
}

/** 普通 Agent 工具补参沿用当前 Action/Wait 身份，不生成新用户输入。 */
export interface ChatToolInputResponse {
  execution_id: string
  run_revision: string
  tool_input: {
    wait: { action_id: string; generation: string }
    responses: Record<
      string,
      {
        method: string
        result: { schema_id: string; canonical_json: string }
      }
    >
  }
}

export type ChatHitlResponse =
  | PlanReviewResponse
  | ChatClarificationResponse
  | ChatHumanInputResponse
  | ChatWorkflowResponse
  | ChatWakeCandidateResponse
  | ChatToolInputResponse
