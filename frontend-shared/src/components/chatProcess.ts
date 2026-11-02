import type { ProcessPresentation } from './chatReferences'
export const CHAT_PROCESS_STATUSES = {
  RUNNING: 'running',
  WAITING: 'waiting',
  SUCCEEDED: 'succeeded',
  FAILED: 'failed',
  CANCELLED: 'cancelled',
  UNKNOWN: 'outcome_unknown'
} as const
export type ChatProcessType =
  'commentary' | 'reasoning' | 'skill' | 'tool' | 'workflow' | 'memory' | 'knowledge' | 'delegation'
export type ChatProcessStatus = (typeof CHAT_PROCESS_STATUSES)[keyof typeof CHAT_PROCESS_STATUSES]
export const CHAT_PROCESS_ATTENTION_STATUSES: readonly ChatProcessStatus[] = [
  CHAT_PROCESS_STATUSES.WAITING,
  CHAT_PROCESS_STATUSES.FAILED,
  CHAT_PROCESS_STATUSES.CANCELLED,
  CHAT_PROCESS_STATUSES.UNKNOWN
]

export interface ChatRunStatusSnapshot {
  status: ChatProcessStatus
  updatedSequence?: number
}

function terminalChatRunStatus(status: ChatProcessStatus): boolean {
  return (
    status === CHAT_PROCESS_STATUSES.SUCCEEDED ||
    status === CHAT_PROCESS_STATUSES.FAILED ||
    status === CHAT_PROCESS_STATUSES.CANCELLED ||
    status === CHAT_PROCESS_STATUSES.UNKNOWN
  )
}

/**
 * 合并同一根 Run 的权威状态。带来源顺序的旧快照不能回退新状态；最终终态保持单调，
 * 只有等待态可以由后续恢复事件重新进入 running。
 */
export function mergeChatRunStatus(
  current: ChatRunStatusSnapshot | undefined,
  incoming: ChatRunStatusSnapshot,
  allowResume = false
): ChatRunStatusSnapshot {
  if (!current) return incoming
  if (
    incoming.updatedSequence !== undefined &&
    current.updatedSequence !== undefined &&
    incoming.updatedSequence < current.updatedSequence
  )
    return current
  if (terminalChatRunStatus(current.status)) {
    return incoming.status === current.status &&
      (incoming.updatedSequence ?? -1) > (current.updatedSequence ?? -1)
      ? incoming
      : current
  }
  if (
    current.status === CHAT_PROCESS_STATUSES.WAITING &&
    incoming.status === CHAT_PROCESS_STATUSES.RUNNING &&
    !allowResume &&
    !(
      incoming.updatedSequence !== undefined &&
      current.updatedSequence !== undefined &&
      incoming.updatedSequence > current.updatedSequence
    )
  )
    return current
  return {
    status: incoming.status,
    updatedSequence: incoming.updatedSequence ?? current.updatedSequence
  }
}

/** Runtime 状态仅映射展示标签；缺失或未知状态不构成整轮成功事实。 */
export function chatRunStatus(status: unknown): ChatProcessStatus | undefined {
  switch (status) {
    case 'queued':
    case 'resuming':
    case 'running':
      return CHAT_PROCESS_STATUSES.RUNNING
    case 'waiting_hitl':
    case 'suspended':
      return CHAT_PROCESS_STATUSES.WAITING
    case 'completed':
      return CHAT_PROCESS_STATUSES.SUCCEEDED
    case 'failed':
      return CHAT_PROCESS_STATUSES.FAILED
    case 'cancelled':
    case 'terminated':
      return CHAT_PROCESS_STATUSES.CANCELLED
    case 'outcome_unknown':
      return CHAT_PROCESS_STATUSES.UNKNOWN
    default:
      return undefined
  }
}

/** 公共过程身份用于恢复和排序；presentation 是唯一可见正文。 */
export interface ChatProcessItem {
  process_id: string
  run_id: string
  input_id: string
  root_run_id: string
  root_input_id: string
  reference_id?: string | null
  type: ChatProcessType
  /** 来源事实首次出现的位置；后续状态更新不能改变它。 */
  sequence: number
  /** 同一来源事实中的稳定次序：普通过程为 0，附属 commentary 为 1。 */
  ordinal: number
  /** 来源 SessionLog 的最新更新顺序，用于拒绝迟到快照。 */
  updated_sequence: number
  status: ChatProcessStatus
  presentation: ProcessPresentation
  actor_agent_id?: string | null
  actor_name?: string | null
  started_at?: string | null
  finished_at?: string | null
  /** 服务端冻结准备事实。缺失时作为独立过程，不从消息位置推测阶段。 */
  stage?: { id: string; index: number } | null
}

export interface ChatProcessRun {
  rootRunId: string
  rootInputId: string
  items: ChatProcessItem[]
}

export function compareChatProcessItems(left: ChatProcessItem, right: ChatProcessItem): number {
  return (
    left.sequence - right.sequence ||
    left.ordinal - right.ordinal ||
    left.process_id.localeCompare(right.process_id)
  )
}

function compatibleChatProcessUpdate(
  existing: ChatProcessItem,
  incoming: ChatProcessItem
): boolean {
  return (
    incoming.process_id === existing.process_id &&
    incoming.run_id === existing.run_id &&
    incoming.input_id === existing.input_id &&
    incoming.root_run_id === existing.root_run_id &&
    incoming.root_input_id === existing.root_input_id &&
    !(
      existing.stage &&
      incoming.stage &&
      (existing.stage.id !== incoming.stage.id || existing.stage.index !== incoming.stage.index)
    )
  )
}

/** 状态说明和业务进度只消费 canonical 过程可受理的版本，与历史合并使用同一规则。 */
export function acceptsChatProcessUpdate(
  existing: ChatProcessItem | undefined,
  incoming: ChatProcessItem
): boolean {
  return (
    !existing ||
    (compatibleChatProcessUpdate(existing, incoming) &&
      incoming.updated_sequence >= existing.updated_sequence)
  )
}

/**
 * 合并实时、历史、分页和补投快照。process_id 是公开投影身份，updated_sequence
 * 决定新旧；sequence/ordinal 始终保留首次位置，避免状态更新导致时间线跳位。
 */
export function mergeChatProcessItems(
  current: readonly ChatProcessItem[],
  incoming: readonly ChatProcessItem[]
): ChatProcessItem[] {
  const merged = new Map(current.map((item) => [item.process_id, item]))
  for (const item of incoming) {
    const existing = merged.get(item.process_id)
    if (!existing) {
      merged.set(item.process_id, item)
      continue
    }
    if (!compatibleChatProcessUpdate(existing, item)) continue
    const earlier = compareChatProcessItems(item, existing) < 0 ? item : existing
    const latest = acceptsChatProcessUpdate(existing, item) ? item : existing
    merged.set(item.process_id, {
      ...existing,
      ...latest,
      root_run_id: existing.root_run_id,
      root_input_id: existing.root_input_id,
      sequence: earlier.sequence,
      ordinal: earlier.ordinal,
      stage: existing.stage ?? latest.stage
    })
  }
  return [...merged.values()].sort(compareChatProcessItems)
}

/** 一个根任务只形成一个过程容器，子 Run 过程按服务端给出的根归属进入同一时间线。 */
export function groupChatProcessRuns(items: readonly ChatProcessItem[]): ChatProcessRun[] {
  const groups = new Map<string, ChatProcessItem[]>()
  for (const item of items) {
    const key = `${item.root_run_id}\u0000${item.root_input_id}`
    const group = groups.get(key) ?? []
    group.push(item)
    groups.set(key, group)
  }
  return [...groups.values()]
    .map((group) => ({
      rootRunId: group[0]!.root_run_id,
      rootInputId: group[0]!.root_input_id,
      items: [...group].sort(compareChatProcessItems)
    }))
    .sort((left, right) => compareChatProcessItems(left.items[0]!, right.items[0]!))
}

export function chatProcessTaskCount(items: readonly ChatProcessItem[]): number {
  return items.filter((item) => item.type !== 'commentary' && item.type !== 'reasoning').length
}

export interface ChatProcessStageGroup {
  key: string
  stage?: ChatProcessItem['stage']
  items: ChatProcessItem[]
}
export function groupChatProcessStages(items: readonly ChatProcessItem[]): ChatProcessStageGroup[] {
  const groups = new Map<string, ChatProcessStageGroup>()
  for (const item of [...items].sort(compareChatProcessItems)) {
    const key = item.stage ? `stage:${item.stage.id}` : `process:${item.process_id}`
    const group = groups.get(key) ?? { key, stage: item.stage, items: [] }
    group.items.push(item)
    groups.set(key, group)
  }
  return [...groups.values()]
}

export function chatProcessNeedsAttention(
  items: readonly ChatProcessItem[],
  runStatus?: ChatProcessStatus
): boolean {
  return (
    Boolean(runStatus && CHAT_PROCESS_ATTENTION_STATUSES.includes(runStatus)) ||
    items.some(
      (item) => item.type !== 'commentary' && CHAT_PROCESS_ATTENTION_STATUSES.includes(item.status)
    )
  )
}

/** 只有显式 final 消息能证明最终结果已就绪。 */
export function chatFinalRootRunId(value: {
  run_id?: unknown
  root_run_id?: unknown
  phase?: unknown
  metadata?: Record<string, unknown>
}): string | undefined {
  const phase = value.phase ?? value.metadata?.phase
  if (phase !== 'final') return undefined
  const rootRunId = value.root_run_id ?? value.metadata?.root_run_id
  return typeof rootRunId === 'string' && rootRunId ? rootRunId : undefined
}

/** 只有公开结果或引用可以展开；模型内部推理不属于此展示契约。 */
export function hasChatProcessDetail(item: ChatProcessItem): boolean {
  return (
    item.presentation.results.some((result) =>
      result.kind === 'text' ? Boolean(result.text.trim()) : result.columns.length > 0
    ) || item.presentation.references.length > 0
  )
}
export function isVisibleChatProcessItem(item: ChatProcessItem): boolean {
  return (
    item.type !== 'reasoning' ||
    item.status !== 'succeeded' ||
    Boolean(item.presentation.summary?.trim()) ||
    hasChatProcessDetail(item)
  )
}

/** 展示密度只取公开执行事实，不根据用户输入猜测任务类型。 */
export function chatProcessDisplayMode(
  items: readonly ChatProcessItem[]
): 'reply' | 'single' | 'multi' {
  const meaningful = items.filter(
    (item) =>
      item.type !== 'reasoning' ||
      Boolean(item.presentation.summary?.trim()) ||
      hasChatProcessDetail(item) ||
      CHAT_PROCESS_ATTENTION_STATUSES.includes(item.status)
  )
  if (!meaningful.length) return 'reply'
  const stages = new Set(meaningful.flatMap((item) => (item.stage ? [item.stage.id] : [])))
  return chatProcessTaskCount(items) <= 1 && stages.size <= 1 ? 'single' : 'multi'
}

/** 复用调用时冻结的公开业务标题，不从工具句柄推断能力或生成新的业务结论。 */
export function chatProcessCompletedLabel(items: readonly ChatProcessItem[]): string {
  const titles = [
    ...new Set(
      [...items]
        .sort(compareChatProcessItems)
        .filter(
          (item) =>
            item.type !== 'reasoning' && item.type !== 'commentary' && item.status === 'succeeded'
        )
        .map((item) => item.presentation.title.trim())
        .filter(Boolean)
    )
  ]
  if (!titles.length) return '已完成回复准备'
  if (titles.length === 1) return `已${titles[0]}`
  if (titles.length > 3) return `已完成${titles.slice(0, 2).join('、')}等操作`
  return `已${titles.slice(0, -1).join('、')}并${titles.at(-1)}`
}
