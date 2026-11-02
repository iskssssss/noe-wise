import { onScopeDispose, ref, watch, type Ref } from 'vue'
import {
  CHAT_GOAL_DEFAULT_MAX_ROUNDS,
  CHAT_GOAL_MAX_OBJECTIVE_LENGTH,
  CHAT_GOAL_MAX_ROUNDS,
  CHAT_GOAL_MIN_ROUNDS,
  chatGoalStatusLabel,
  type ChatGoal,
  type ChatGoalControl,
  type ChatGoalCreate,
  type ChatGoalQuery,
  type ChatGoalStatus
} from './chatGoal'
import type { ChatRunDeliveryQuery, ChatRunDeliveryStatus } from './chatDelivery'

/** 聊天输入框可显式触发的控制命令，用于宿主菜单展示。 */
export interface ChatCommand {
  command: string
  title: string
  description: string
}

export type ChatControlCommand =
  | { kind: 'create'; objective: string; maxRounds: number }
  | { kind: 'status' | 'pause' | 'resume' | 'delivery' }
  | { kind: 'help'; message: string; draft?: string }

/** 宿主提供已授权的聊天 Goal 与送达状态 API；helper 不拥有会话创建职责。 */
export interface ChatControlApi {
  goals: ChatGoalQuery
  createGoal: ChatGoalCreate
  pauseGoal: ChatGoalControl
  resumeGoal: ChatGoalControl
  delivery: ChatRunDeliveryQuery
}

interface PendingCommand {
  commandId: string
  expectedRevision?: number
}

const GOAL_USAGE = `请在当前输入框补充目标。\n用法：/goal [--rounds ${CHAT_GOAL_MIN_ROUNDS}-${CHAT_GOAL_MAX_ROUNDS}] <目标>\n查询：/goal status；暂停：/goal pause；继续：/goal resume`
const DELIVERY_USAGE = '用法：/delivery'

/** 只识别精确的控制命令前缀，普通对话和 /plan 保留给现有聊天流程。 */
export function isChatControlCommand(text: string): boolean {
  return /^\/(?:goal|delivery)(?=\s|$)/.test(text)
}

/** 将显式控制命令规范化；任何无法确认的 Goal 输入都不会被当作创建请求。 */
export function parseChatControlCommand(text: string): ChatControlCommand | undefined {
  if (!isChatControlCommand(text)) return undefined

  if (text.startsWith('/delivery')) {
    return text.slice('/delivery'.length).trim()
      ? { kind: 'help', message: DELIVERY_USAGE, draft: text }
      : { kind: 'delivery' }
  }

  const input = text.slice('/goal'.length).trim()
  if (!input) return goalHelp('/goal ')

  const control = /^(status|pause|resume)(?:\s|$)/.exec(input)
  if (control) {
    if (input.slice(control[0].length).trim()) return goalHelp(text)
    return { kind: control[1] as 'status' | 'pause' | 'resume' }
  }

  let maxRounds = CHAT_GOAL_DEFAULT_MAX_ROUNDS
  let objective = input
  if (input.startsWith('--rounds')) {
    const rounds = /^--rounds\s+(\S+)(?:\s+([\s\S]*))?$/.exec(input)
    if (!rounds || !/^\d+$/.test(rounds[1]!)) return goalHelp(text)
    maxRounds = Number(rounds[1])
    objective = rounds[2]?.trim() ?? ''
  }

  if (
    !objective ||
    objective.startsWith('--') ||
    maxRounds < CHAT_GOAL_MIN_ROUNDS ||
    maxRounds > CHAT_GOAL_MAX_ROUNDS ||
    objective.length > CHAT_GOAL_MAX_OBJECTIVE_LENGTH
  )
    return goalHelp(!objective ? '/goal ' : text)

  return { kind: 'create', objective, maxRounds }
}

/**
 * 执行显式聊天控制命令。
 *
 * scope 改变时会丢弃迟到响应和未完成命令，避免旧会话的事实覆盖当前会话。
 */
export function useChatControlCommands(scope: () => string): {
  busy: Ref<boolean>
  feedback: Ref<string>
  refreshKey: Ref<number>
  clearFeedback: () => void
  reset: () => void
  execute: (
    api: ChatControlApi,
    sessionId: string,
    text: string,
    runId?: string
  ) => Promise<{ draft?: string; runId?: string } | undefined>
} {
  const busy = ref(false)
  const feedback = ref('')
  const refreshKey = ref(0)
  const pending = new Map<string, PendingCommand>()
  let scopeRevision = 0
  let disposed = false

  function reset(): void {
    scopeRevision++
    pending.clear()
    busy.value = false
    feedback.value = ''
  }

  watch(scope, reset, { flush: 'sync' })
  onScopeDispose(() => {
    disposed = true
    scopeRevision++
    pending.clear()
  })

  function clearFeedback(): void {
    feedback.value = ''
  }

  function current(requestScope: string, requestRevision: number): boolean {
    return !disposed && scope() === requestScope && scopeRevision === requestRevision
  }

  function setFeedback(requestScope: string, requestRevision: number, message: string): void {
    if (current(requestScope, requestRevision)) feedback.value = message
  }

  function pendingCommand(sessionId: string, text: string): [string, PendingCommand] {
    const key = pendingKey(sessionId, text)
    let command = pending.get(key)
    if (!command) {
      command = { commandId: commandId() }
      pending.set(key, command)
    }
    return [key, command]
  }

  async function execute(
    api: ChatControlApi,
    sessionId: string,
    text: string,
    runId?: string
  ): Promise<{ draft?: string; runId?: string } | undefined> {
    const command = parseChatControlCommand(text)
    if (!command) return undefined

    const requestScope = scope()
    const requestRevision = scopeRevision
    if (command.kind === 'help') {
      setFeedback(requestScope, requestRevision, command.message)
      return command.draft === undefined ? undefined : { draft: command.draft }
    }
    if (busy.value) {
      setFeedback(requestScope, requestRevision, '正在等待命令回执，请稍候')
      return undefined
    }
    if (command.kind === 'delivery' && !runId?.trim()) {
      setFeedback(requestScope, requestRevision, '当前没有可查询送达状态的运行')
      return undefined
    }
    if (!sessionId.trim()) {
      setFeedback(
        requestScope,
        requestRevision,
        command.kind === 'status' ? '当前会话还没有持续目标' : '请先创建会话后再执行该命令'
      )
      return undefined
    }

    busy.value = true
    try {
      if (command.kind === 'create') {
        return await createGoal(api, sessionId, text, command, requestScope, requestRevision)
      }
      if (command.kind === 'status') {
        return await showGoalStatus(api, sessionId, requestScope, requestRevision)
      }
      if (command.kind === 'pause' || command.kind === 'resume') {
        return await controlGoal(api, sessionId, text, command.kind, requestScope, requestRevision)
      }
      return await showDelivery(api, sessionId, runId!, requestScope, requestRevision)
    } catch (error) {
      if (
        current(requestScope, requestRevision) &&
        (command.kind === 'pause' || command.kind === 'resume') &&
        isGoalRevisionConflict(error)
      ) {
        pending.delete(pendingKey(sessionId, text))
        setFeedback(
          requestScope,
          requestRevision,
          '目标状态已变化，本次操作未受理。请先发送 /goal status 核对，再决定是否重新发送原命令'
        )
        return undefined
      }
      const message =
        command.kind === 'delivery'
          ? '暂时无法读取送达状态，可再次发送 /delivery 查询'
          : command.kind === 'status'
            ? '暂时无法读取目标状态，可再次发送 /goal status 查询'
            : '操作结果尚未确认。请先发送 /goal status 核对；仍需重试时，保持当前会话并原样发送原命令'
      setFeedback(requestScope, requestRevision, message)
      return undefined
    } finally {
      if (current(requestScope, requestRevision)) busy.value = false
    }
  }

  async function createGoal(
    api: ChatControlApi,
    sessionId: string,
    text: string,
    command: Extract<ChatControlCommand, { kind: 'create' }>,
    requestScope: string,
    requestRevision: number
  ): Promise<{ runId?: string } | undefined> {
    const [key, record] = pendingCommand(sessionId, text)
    const status = await api.createGoal(
      sessionId,
      record.commandId,
      command.objective,
      command.maxRounds
    )
    if (!current(requestScope, requestRevision)) return undefined
    pending.delete(key)
    refreshKey.value++
    setFeedback(
      requestScope,
      requestRevision,
      status.goal
        ? `持续目标已提交：${goalSummary(status.goal)}`
        : '目标请求已提交，可发送 /goal status 查询当前状态'
    )
    return status.goal?.last_run_id ? { runId: status.goal.last_run_id } : {}
  }

  async function showGoalStatus(
    api: ChatControlApi,
    sessionId: string,
    requestScope: string,
    requestRevision: number
  ): Promise<Record<string, never> | undefined> {
    const status = await api.goals(sessionId)
    if (!current(requestScope, requestRevision)) return undefined
    refreshKey.value++
    setFeedback(
      requestScope,
      requestRevision,
      status.goal
        ? `目标：${shortText(status.goal.objective)}\n状态：${chatGoalStatusLabel(status)}，${status.goal.rounds_started}/${status.goal.max_rounds} 轮`
        : '当前会话还没有持续目标'
    )
    return {}
  }

  async function controlGoal(
    api: ChatControlApi,
    sessionId: string,
    text: string,
    kind: 'pause' | 'resume',
    requestScope: string,
    requestRevision: number
  ): Promise<Record<string, never> | undefined> {
    const [key, record] = pendingCommand(sessionId, text)
    let status: ChatGoalStatus
    if (record.expectedRevision === undefined) {
      status = await api.goals(sessionId)
      if (!current(requestScope, requestRevision)) return undefined
      const blocked = controlBlockedMessage(kind, status)
      if (blocked) {
        pending.delete(key)
        setFeedback(requestScope, requestRevision, blocked)
        return {}
      }
      record.expectedRevision = status.goal!.revision
    }
    const response =
      kind === 'pause'
        ? await api.pauseGoal(sessionId, record.commandId, record.expectedRevision)
        : await api.resumeGoal(sessionId, record.commandId, record.expectedRevision)
    if (!current(requestScope, requestRevision)) return undefined
    pending.delete(key)
    refreshKey.value++
    setFeedback(requestScope, requestRevision, goalControlFeedback(kind, response))
    return {}
  }

  async function showDelivery(
    api: ChatControlApi,
    sessionId: string,
    runId: string,
    requestScope: string,
    requestRevision: number
  ): Promise<Record<string, never> | undefined> {
    const delivery = await api.delivery(sessionId, runId)
    if (!current(requestScope, requestRevision)) return undefined
    if (delivery.run_id !== runId) {
      setFeedback(requestScope, requestRevision, '返回的送达状态不属于当前运行，请稍后重试')
      return undefined
    }
    setFeedback(requestScope, requestRevision, deliverySummary(delivery))
    return {}
  }

  return { busy, feedback, refreshKey, clearFeedback, reset, execute }
}

function goalHelp(draft: string): Extract<ChatControlCommand, { kind: 'help' }> {
  return { kind: 'help', message: GOAL_USAGE, draft }
}

function pendingKey(sessionId: string, text: string): string {
  return `${sessionId}\u0000${text}`
}

function isGoalRevisionConflict(error: unknown): boolean {
  if (!error || typeof error !== 'object') return false
  const failure = error as { status?: unknown; technicalDetails?: unknown }
  return (
    failure.status === 409 &&
    (failure.technicalDetails === 'goal_revision_conflict' ||
      failure.technicalDetails === 'goal_state_conflict')
  )
}

function controlBlockedMessage(
  kind: 'pause' | 'resume',
  status: ChatGoalStatus
): string | undefined {
  const goal = status.goal
  if (!goal) return '当前会话还没有持续目标'
  if (kind === 'pause' && goal.state === 'ACTIVE') return undefined
  if (kind === 'pause' && goal.state !== 'ACTIVE') return '当前目标不是进行中，无法暂停'
  if (kind === 'resume' && goal.state === 'PAUSED') return undefined
  if (kind === 'resume' && goal.state === 'ACTIVE' && status.completion_pending)
    return '目标正在收尾，等待成果发布确认'
  if (kind === 'resume' && goal.state === 'ACTIVE' && !status.continuation_enabled) return undefined
  return '当前目标无需继续'
}

function goalControlFeedback(kind: 'pause' | 'resume', status: ChatGoalStatus): string {
  const goal = status.goal
  if (!goal) return '当前会话还没有持续目标'
  if (kind === 'pause' && goal.state === 'PAUSED') return '已暂停持续目标'
  if (kind === 'resume' && goal.state === 'ACTIVE' && status.continuation_enabled)
    return '已继续持续目标'
  return `目标当前${chatGoalStatusLabel(status)}`
}

function goalSummary(goal: ChatGoal): string {
  return `${shortText(goal.objective)}（${goal.rounds_started}/${goal.max_rounds} 轮）`
}

function shortText(value: string): string {
  return value.length > 160 ? `${value.slice(0, 160)}…` : value
}

function deliverySummary(delivery: ChatRunDeliveryStatus): string {
  const sse = {
    replay_available: '可恢复读取，不能确认已读',
    unknown: '状态未知'
  }[delivery.sse.status]
  const webhook = {
    pending: '投递中',
    delivered: '已获订阅端确认',
    failed: '存在失败记录',
    unknown: '状态未知，不能确认送达'
  }[delivery.webhook.status]
  const failure = delivery.failures.length ? `\n外部通知失败：${delivery.failures.length} 条` : ''
  return `运行：${runtimeLabel(delivery.runtime_status)}\n会话消息：${sse}\n外部通知：${webhook}${failure}`
}

function runtimeLabel(status: string): string {
  return (
    (
      {
        queued: '等待执行',
        running: '执行中',
        waiting_hitl: '等待确认',
        completed: '已完成',
        failed: '已失败',
        cancelled: '已取消',
        terminated: '已终止'
      } as Record<string, string>
    )[status] ?? '状态未知'
  )
}

function commandId(): string {
  return typeof globalThis.crypto?.randomUUID === 'function'
    ? globalThis.crypto.randomUUID()
    : `chat-command-${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`
}
