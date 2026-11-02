import type { History, Message, Process } from '../protocol'
import { isPublicMessage } from '../projection'

export interface ConversationTurn {
  id: string
  runIds: string[]
  messages: Message[]
  processes: Process[]
}

export type ConversationEntry =
  | { kind: 'message'; key: string; message: Message }
  | { kind: 'process'; key: string; turn: ConversationTurn }

function field(message: Message, key: string): string {
  const value = (message as unknown as Record<string, unknown>)[key] ?? message.metadata?.[key]
  return typeof value === 'string' ? value : ''
}

/** 输入身份优先，消息顺序仅用于尚无运行身份的用户消息。过程绝不跨运行合并。 */
export function conversationTurns(
  history: Pick<History, 'messages' | 'processes'>
): ConversationTurn[] {
  const turns: ConversationTurn[] = []
  const runs = new Map<string, ConversationTurn>()
  const inputs = new Map<string, ConversationTurn>()
  let current: ConversationTurn | undefined
  for (const message of history.messages.filter(isPublicMessage)) {
    const run = field(message, 'root_run_id') || field(message, 'run_id')
    const input = field(message, 'root_input_id') || field(message, 'input_id')
    let turn = (run && runs.get(run)) || (input && inputs.get(input)) || undefined
    if (!turn && !input && message.role !== 'user' && current && (!run || !current.runIds.length))
      turn = current
    if (!turn) {
      turn = {
        id: message.role !== 'user' && run ? `run:${run}` : message.message_id,
        runIds: [],
        messages: [],
        processes: []
      }
      turns.push(turn)
    }
    if (message.role === 'user') current = turn
    turn.messages.push(message)
    if (run) {
      runs.set(run, turn)
      if (!turn.runIds.includes(run)) turn.runIds.push(run)
    }
    if (input) inputs.set(input, turn)
    // 用户消息的公开 ID 可直接作为输入身份使用。
    if (message.role === 'user') inputs.set(message.message_id, turn)
  }
  for (const process of history.processes) {
    const run = process.root_run_id || process.run_id
    const input = (process as Process & { root_input_id?: string }).root_input_id
    let turn = runs.get(run) || (input ? inputs.get(input) : undefined)
    if (!turn) {
      turn = { id: `run:${run}`, runIds: [run], messages: [], processes: [] }
      turns.push(turn)
    }
    runs.set(run, turn)
    if (!turn.runIds.includes(run)) turn.runIds.push(run)
    turn.processes.push(process)
  }
  for (const turn of turns)
    turn.processes.sort((a, b) => a.sequence - b.sequence || a.ordinal - b.ordinal)
  return turns
}

export function finalReady(turn: ConversationTurn): boolean {
  const finalMessages = turn.messages.filter(
    (message) =>
      message.role === 'assistant' && (message.phase ?? message.metadata?.phase) === 'final'
  )
  return (
    finalMessages.length > 0 &&
    finalMessages.every(
      (message) =>
        message.state === 'completed' && (Boolean(message.content.trim()) || Boolean(message.card))
    )
  )
}

/** 每个根 Run 在首次过程位置显示一次完整轨迹；正文保留源序且永远不进入折叠轨迹。 */
export function conversationEntries(turn: ConversationTurn): ConversationEntry[] {
  const messages = turn.messages.map((message, index) => ({
    kind: 'message' as const,
    sequence:
      message.sequence ??
      message.metadata?.source_sequence ??
      (message.role === 'user' ? -Infinity : Infinity),
    ordinal: index,
    message
  }))
  const processes = turn.processes.map((process) => ({
    kind: 'process' as const,
    sequence: process.sequence,
    ordinal: process.ordinal,
    process
  }))
  const ordered = [...messages, ...processes].sort(
    (a, b) =>
      (a.sequence === b.sequence ? 0 : a.sequence < b.sequence ? -1 : 1) ||
      (a.kind === b.kind ? a.ordinal - b.ordinal : a.kind === 'process' ? -1 : 1)
  )
  const entries: ConversationEntry[] = []
  const groups = new Map<string, ConversationTurn>()
  for (const item of ordered) {
    if (item.kind === 'message') {
      entries.push({ kind: 'message', key: item.message.message_id, message: item.message })
      continue
    }
    const run = item.process.root_run_id || item.process.run_id
    const existing = groups.get(run)
    if (existing) existing.processes.push(item.process)
    else {
      const group = {
        ...turn,
        runIds: [run],
        messages: turn.messages.filter((message) => {
          const messageRun = field(message, 'root_run_id') || field(message, 'run_id')
          return messageRun === run || (!messageRun && turn.runIds.length === 1)
        }),
        processes: [item.process]
      }
      groups.set(run, group)
      entries.push({ kind: 'process', key: item.process.process_id, turn: group })
    }
  }
  return entries
}

/** 仅省略没有公开内容的成功模型步骤；公开摘要、结果和异常仍保留在默认过程列表。 */
export function visibleProcesses(turn: ConversationTurn, fullTrace = false): Process[] {
  if (fullTrace) return turn.processes
  return turn.processes.filter(
    (process) =>
      !['reasoning', 'commentary'].includes(process.type) ||
      !['succeeded', 'completed'].includes(process.status) ||
      Boolean(process.presentation.summary?.trim()) ||
      Boolean(process.presentation.results?.length) ||
      Boolean(process.presentation.references?.length)
  )
}

export function modelCallCount(turn: ConversationTurn): number {
  return turn.processes.filter((process) => process.type === 'reasoning').length
}

export interface ProcessStageGroup {
  key: string
  index?: number
  processes: Process[]
}

/** 仅合并相邻且明确同阶段、同 Run 的记录，跨阶段交错不能被重新排序。 */
export function processStages(turn: ConversationTurn): ProcessStageGroup[] {
  const groups: ProcessStageGroup[] = []
  for (const process of turn.processes) {
    const previous = groups.at(-1)
    const last = previous?.processes.at(-1)
    if (process.stage?.id && last?.stage?.id === process.stage.id && last.run_id === process.run_id)
      previous!.processes.push(process)
    else
      groups.push({
        key: process.process_id,
        index: process.stage?.id ? process.stage.index : undefined,
        processes: [process]
      })
  }
  return groups
}

const successful = new Set(['succeeded', 'completed'])
const terminalRuns = new Set(['completed', 'failed', 'cancelled', 'outcome_unknown'])
const actionable = new Set(['tool', 'skill', 'workflow', 'memory', 'knowledge', 'delegation'])
const attentionStates = new Set(['failed', 'outcome_unknown', 'waiting_hitl', 'waiting_input'])

/** 工作说明、模型调用和公开校验不计作业务操作；完整轨迹开关不改变统计。 */
export function operationCount(processes: Process[]) {
  const operations = processes.filter((process) => actionable.has(process.type))
  return {
    total: operations.length,
    completed: operations.filter((process) => successful.has(process.status)).length
  }
}

/** 阶段默认展开未收敛或异常项，已完成阶段可由用户再次展开。 */
export function stageNeedsExpansion(group: ProcessStageGroup) {
  return group.processes.some((process) => !successful.has(process.status))
}

/** 根 Run 的终态优先于尚未同步的步骤状态，禁止在结束后继续推算耗时。 */
export function processIsLive(turn: ConversationTurn, runStates: Record<string, string>) {
  return !turn.runIds.length || turn.runIds.some((run) => !terminalRuns.has(runStates[run] ?? ''))
}

/** 整轮终态以 Run 为准；历史步骤失败保留在轨迹，未知外部结果仍需核实。 */
export function processProgress(
  turn: ConversationTurn,
  runStates: Record<string, string>,
  pending = false
) {
  const states = turn.runIds.map((run) => runStates[run]).filter(Boolean)
  const has = (state: string) =>
    states.includes(state) || turn.processes.some((process) => process.status === state)
  const messageHas = (state: string) => turn.messages.some((message) => message.state === state)
  const active = processIsLive(turn, runStates)
    ? [...turn.processes]
        .reverse()
        .find((process) => ['running', 'waiting'].includes(process.status))
    : undefined
  if (has('outcome_unknown') || messageHas('outcome_unknown'))
    return { label: '结果待核实', tone: 'error', active: undefined }
  const allStatesKnown = states.length > 0 && states.length === turn.runIds.length
  if (allStatesKnown && states.every((state) => state === 'completed'))
    return {
      label: finalReady(turn) ? '执行完成' : '正在准备答复',
      tone: 'muted',
      active: undefined
    }
  if (allStatesKnown && states.every((state) => terminalRuns.has(state))) {
    if (states.includes('failed'))
      return { label: '执行遇到问题', tone: 'error', active: undefined }
    if (states.includes('cancelled')) return { label: '已停止', tone: 'muted', active: undefined }
  }
  if (states.includes('cancelling')) return { label: '正在停止', tone: 'warning', active }
  if (pending || has('waiting_hitl') || has('waiting_input'))
    return { label: '等待你的输入', tone: 'warning', active: undefined }
  if (has('suspended') || has('waiting')) return { label: '等待执行结果', tone: 'muted', active }
  if (states.includes('queued')) return { label: '排队中', tone: 'muted', active: undefined }
  if (states.includes('running') || active)
    return { label: active?.presentation.title || '正在处理', tone: 'active', active }
  if (has('failed') || messageHas('failed')) return { label: '执行遇到问题', tone: 'error', active }
  if (states.includes('cancelled') || messageHas('cancelled'))
    return { label: '已停止', tone: 'muted', active: undefined }
  return { label: '执行记录', tone: 'muted', active: undefined }
}

/** 只解析有明确时区的公开绝对时刻，缺失或反向时间不显示虚构耗时。 */
export function processElapsed(process: Process | undefined, now: number, live = true): string {
  const parse = (value?: string) =>
    value && /(?:Z|[+-]\d{2}:\d{2})$/i.test(value) ? Date.parse(value) : NaN
  if (!process) return ''
  const start = parse(process.started_at)
  const end = process.finished_at
    ? parse(process.finished_at)
    : live && ['running', 'waiting'].includes(process.status)
      ? now
      : NaN
  if (!Number.isFinite(start) || !Number.isFinite(end) || end < start) return ''
  const seconds = Math.floor((end - start) / 1000)
  if (seconds < 60) return `${seconds} 秒`
  if (seconds < 3600) return `${Math.floor(seconds / 60)} 分 ${seconds % 60} 秒`
  return `${Math.floor(seconds / 3600)} 小时 ${Math.floor((seconds % 3600) / 60)} 分`
}

export function needsAttention(turn: ConversationTurn): boolean {
  return (
    turn.messages.some(
      (message) => message.state === 'failed' || message.state === 'outcome_unknown'
    ) || turn.processes.some((process) => attentionStates.has(process.status))
  )
}
