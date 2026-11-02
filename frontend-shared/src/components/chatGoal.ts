/** 可续行目标的持久状态；目标创建与暂停都由用户显式操作触发。 */
export type ChatGoalState = 'ACTIVE' | 'PAUSED' | 'COMPLETE' | 'BLOCKED'

export interface ChatGoal {
  goal_id: string
  revision: number
  objective: string
  state: ChatGoalState
  max_rounds: number
  rounds_started: number
  pending_round_input_id?: string | null
  last_run_id?: string | null
  reason?: string | null
}

export interface ChatGoalStatus {
  goal: ChatGoal | null
  continuation_enabled: boolean
  /** 根据当前 Goal 和已冻结完成请求派生；发布前不改变 Goal 状态。 */
  completion_pending: boolean
  publication: ChatGoalPublication | null
}

/** 精确绑定本次成果结果的发布事实，正式版本只在发布成功后出现。 */
export interface ChatGoalPublication {
  run_id: string
  result_ref: string
  status: 'pending' | 'published' | 'failed' | 'cancelled'
  reason: string | null
  revision_refs: string[]
}

export function chatGoalStatusLabel(status: ChatGoalStatus): string {
  const goal = status.goal
  if (!goal) return '无目标'
  if (goal.state === 'ACTIVE' && status.completion_pending) return '收尾中'
  if (goal.state === 'ACTIVE' && !status.continuation_enabled) return '需恢复'
  return { ACTIVE: '进行中', PAUSED: '已暂停', COMPLETE: '已完成', BLOCKED: '已阻塞' }[goal.state]
}

export type ChatGoalQuery = (sessionId: string) => Promise<ChatGoalStatus>
export type ChatGoalCreate = (
  sessionId: string,
  commandId: string,
  objective: string,
  maxRounds: number
) => Promise<ChatGoalStatus>
export type ChatGoalControl = (
  sessionId: string,
  commandId: string,
  expectedRevision: number
) => Promise<ChatGoalStatus>

export const CHAT_GOAL_DEFAULT_MAX_ROUNDS = 256
export const CHAT_GOAL_MIN_ROUNDS = 1
export const CHAT_GOAL_MAX_ROUNDS = 1024
export const CHAT_GOAL_MAX_OBJECTIVE_LENGTH = 8000
