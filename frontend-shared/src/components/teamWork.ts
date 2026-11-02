export const TEAM_TASK_KINDS = { PLAN: 'plan', DIRECT: 'direct' } as const
export const TEAM_RUN_STATUSES = {
  QUEUED: 'queued',
  RUNNING: 'running',
  SUSPENDED: 'suspended',
  RESUMING: 'resuming',
  COMPLETED: 'completed',
  FAILED: 'failed',
  CANCELLED: 'cancelled',
  TERMINATED: 'terminated'
} as const
export type TeamRunStatus = (typeof TEAM_RUN_STATUSES)[keyof typeof TEAM_RUN_STATUSES]
export type TeamTaskKind = (typeof TEAM_TASK_KINDS)[keyof typeof TEAM_TASK_KINDS]
export const TEAM_DETAILS_ACCESS = { AVAILABLE: 'available', UNAVAILABLE: 'unavailable' } as const
export const TEAM_PENDING_KINDS = {
  INPUT: 'input',
  OPERATION: 'operation',
  WORKFLOW: 'workflow'
} as const
export const TEAM_PENDING_OPERATIONS = {
  APPROVE: 'approve_operation',
  REJECT: 'reject_operation',
  REPLY: 'reply_input',
  WORKFLOW_RESPONSE: 'workflow_response'
} as const
export const MEMBER_DETAILS_PAGE_LIMIT = 50
export const MEMBER_DETAILS_POLL_MS = 1500
/** 工作室协作运行在会话中的投影，由宿主负责刷新与权限控制。 */
export interface TeamWorkSnapshot {
  work_id: string
  session_id: string
  /** 主助手原始轮次；不使用列表位置推断所属消息。 */
  input_id: string
  run_id: string
  /** Run 与工作有各自的修订；成员完成后仍需跟随主助手答复。 */
  title?: string
  run_revision: number | null
  run_status: TeamRunStatus | null
  revision: number
  status: 'queued' | 'active' | 'completed' | 'cancelled'
  plan_revision: number
  approved_revision: number | null
  approval_required?: boolean
  stage?: string
  notice?: string | null
  handoff?: string | null
  pending?: TeamPending | null
  queued_count?: number
  members?: Array<{ agent_id: string; name: string }>
  control_inputs?: Array<{
    input_id: string
    task_id: string
    state: 'ACCEPTED' | 'APPLIED' | 'REJECTED'
    detail: string
  }>
  coordinator: { agent_id: string; name: string }
  tasks: TeamTask[]
  results: TeamDeliverable[]
}

export interface TeamTask {
  task_id: string
  task_kind: TeamTaskKind
  details_access: (typeof TEAM_DETAILS_ACCESS)[keyof typeof TEAM_DETAILS_ACCESS]
  replaces_task_id?: string | null
  title: string
  agent_id: string
  agent_name: string
  executor_type?: 'PRIMARY' | 'MEMBER'
  goal?: string
  expected_output?: string
  constraints?: string[]
  depends_on: string[]
  status:
    | 'pending'
    | 'blocked'
    | 'running'
    | 'waiting'
    | 'cancelling'
    | 'completed'
    | 'failed'
    | 'cancelled'
    | 'outcome_unknown'
  wait_reason?: string | null
  phase?: string | null
  run_id?: string | null
  pending?: TeamPending | null
  result_ids: string[]
}

export type TeamDeliverableKind =
  | 'text'
  | 'document'
  | 'spreadsheet'
  | 'presentation'
  | 'image'
  | 'design'
  | 'website'
  | 'application'
  | 'code'
  | 'media'
  | 'operation'
  | 'configuration'

export interface TeamDeliverable {
  result_id: string
  task_id: string
  title: string
  kind: TeamDeliverableKind
  status: 'ready' | 'unavailable' | 'failed'
  summary?: string | null
  representations: Array<{
    label: string
    media_type: string
    artifact_id?: string | null
    path?: string | null
    url?: string | null
    role: 'source' | 'preview' | 'export' | 'external'
  }>
  limitations: string[]
}

export interface TeamPending {
  pending_id: string
  parameters_version: string
  prompt: string
  kind: (typeof TEAM_PENDING_KINDS)[keyof typeof TEAM_PENDING_KINDS]
  card?: Record<string, unknown>
  execution_id?: string
  run_revision?: string
  tool_wait?: { action_id: string; generation: string }
}

export interface TeamMemberSelector {
  session_id: string
  work_id: string
  task_kind: TeamTaskKind
  task_id: string
}
export interface TeamMemberDetails {
  task: TeamTask
  run: { status: TeamRunStatus; revision: number; cancel_requested: boolean }
  processes: import('./chatProcess').ChatProcessItem[]
  next_cursor: string | null
  has_more: boolean
  pending: TeamPending | null
  results: TeamDeliverable[]
}
export type TeamMemberDetailsLoader = (
  selector: TeamMemberSelector,
  cursor: string | undefined,
  signal: AbortSignal
) => Promise<TeamMemberDetails>

export function teamWorkPhaseLabel(work: TeamWorkSnapshot): string {
  if (work.run_status === TEAM_RUN_STATUSES.COMPLETED) return '主助手已回复'
  if (work.run_status === TEAM_RUN_STATUSES.FAILED) return '主助手执行失败'
  if (
    work.run_status === TEAM_RUN_STATUSES.CANCELLED ||
    work.run_status === TEAM_RUN_STATUSES.TERMINATED
  )
    return '主助手已结束'
  if (work.status === 'completed' && work.run_status) return '主助手正在汇总'
  return teamWorkStatusLabel(work.status)
}

export function teamMemberRunStatusLabel(status: TeamRunStatus): string {
  return {
    queued: '排队中',
    running: '进行中',
    suspended: '等待中',
    resuming: '正在继续',
    completed: '已完成',
    failed: '失败',
    cancelled: '已取消',
    terminated: '已结束'
  }[status]
}

export type TeamTaskOperation = 'supplement' | 'cancel' | 'adjust'

/** 卡片控制始终携带所属工作，历史卡不得借用当前最新 work_id。 */
export interface TeamTaskControl {
  work_id: string
  task_id: string
  operation: TeamTaskOperation
  content?: string
}

export interface TeamWorkControl {
  work_id: string
  operation: 'approve_plan' | 'cancel'
  plan_revision?: number
}

export interface TeamPendingControl {
  work_id: string
  task_id?: string
  operation: 'approve_operation' | 'reject_operation' | 'reply_input' | 'workflow_response'
  pending_id: string
  parameters_version: string
  content?: string
}

export interface TeamDagNode {
  task: TeamTask
  layer: number
  index: number
}

export interface TeamDagEdge {
  from: string
  to: string
}

export interface TeamDagLayout {
  nodes: TeamDagNode[]
  edges: TeamDagEdge[]
  errors: string[]
}

/**
 * 用输入顺序稳定地做分层拓扑排序。异常图仍返回全部节点，供只读视图说明问题。
 */
export function buildTeamDagLayout(tasks: TeamTask[]): TeamDagLayout {
  const taskById = new Map<string, TeamTask>()
  const indexById = new Map<string, number>()
  const errors: string[] = []
  tasks.forEach((task, index) => {
    if (taskById.has(task.task_id)) {
      errors.push(`任务“${task.title}”出现重复记录，关系图可能不完整。请刷新后核对。`)
      return
    }
    taskById.set(task.task_id, task)
    indexById.set(task.task_id, index)
  })

  const indegree = new Map<string, number>()
  const layerById = new Map<string, number>()
  const dependents = new Map<string, string[]>()
  const edges: TeamDagEdge[] = []
  for (const task of taskById.values()) {
    indegree.set(task.task_id, 0)
    layerById.set(task.task_id, 0)
    dependents.set(task.task_id, [])
  }
  for (const task of taskById.values()) {
    for (const dependencyId of task.depends_on) {
      if (!taskById.has(dependencyId)) {
        errors.push(
          `任务“${task.title}”的依赖任务未出现在当前列表中，关系图可能不完整。请刷新后核对。`
        )
        continue
      }
      indegree.set(task.task_id, (indegree.get(task.task_id) ?? 0) + 1)
      dependents.get(dependencyId)!.push(task.task_id)
      edges.push({ from: dependencyId, to: task.task_id })
    }
  }

  const queue = [...taskById.keys()]
    .filter((id) => indegree.get(id) === 0)
    .sort((left, right) => indexById.get(left)! - indexById.get(right)!)
  const processed = new Set<string>()
  while (queue.length) {
    const id = queue.shift()!
    processed.add(id)
    for (const dependentId of dependents.get(id) ?? []) {
      layerById.set(
        dependentId,
        Math.max(layerById.get(dependentId) ?? 0, (layerById.get(id) ?? 0) + 1)
      )
      const remaining = (indegree.get(dependentId) ?? 1) - 1
      indegree.set(dependentId, remaining)
      if (remaining === 0) {
        queue.push(dependentId)
        queue.sort((left, right) => indexById.get(left)! - indexById.get(right)!)
      }
    }
  }

  const cyclicIds = [...taskById.keys()].filter((id) => !processed.has(id))
  if (cyclicIds.length) {
    errors.push(
      `以下任务存在循环依赖或受其影响：${cyclicIds.map((id) => taskById.get(id)!.title).join('、')}。请检查任务依赖关系。`
    )
    const fallbackLayer = Math.max(0, ...[...layerById.values()]) + 1
    cyclicIds.forEach((id) => layerById.set(id, fallbackLayer))
  }
  return {
    nodes: [...taskById.values()].map((task) => ({
      task,
      layer: layerById.get(task.task_id) ?? 0,
      index: indexById.get(task.task_id)!
    })),
    edges,
    errors
  }
}

export function teamTaskStatusLabel(status: TeamTask['status']): string {
  return {
    pending: '待开始',
    blocked: '依赖受阻',
    running: '进行中',
    waiting: '等待中',
    cancelling: '正在取消',
    completed: '已完成',
    failed: '失败',
    cancelled: '已取消',
    outcome_unknown: '结果待核实'
  }[status]
}

export function teamWorkStatusLabel(status: TeamWorkSnapshot['status']): string {
  return { queued: '排队中', active: '执行中', completed: '已完成', cancelled: '已取消' }[status]
}

export function teamDeliverableKindLabel(kind: TeamDeliverableKind): string {
  return {
    text: '文本',
    document: '文档',
    spreadsheet: '表格',
    presentation: '演示文稿',
    image: '图片',
    design: '设计',
    website: '网站',
    application: '应用',
    code: '代码',
    media: '媒体',
    operation: '操作',
    configuration: '配置'
  }[kind]
}

export function teamDeliverableKindIcon(kind: TeamDeliverableKind): string {
  return {
    text: '文',
    document: '▤',
    spreadsheet: '▦',
    presentation: '▧',
    image: '▣',
    design: '◇',
    website: '⌘',
    application: '◫',
    code: '</>',
    media: '▶',
    operation: '⚙',
    configuration: '≡'
  }[kind]
}

/** 工作修订和主 Run 修订分别合并，成员完成不回退主助手状态。 */
export function mergeTeamWorkSnapshot(
  previous: TeamWorkSnapshot | null | undefined,
  next: TeamWorkSnapshot
): TeamWorkSnapshot {
  if (!previous || previous.work_id !== next.work_id) return next
  const work = next.revision >= previous.revision ? next : previous
  const run = (next.run_revision ?? -1) >= (previous.run_revision ?? -1) ? next : previous
  return { ...work, run_status: run.run_status, run_revision: run.run_revision }
}
