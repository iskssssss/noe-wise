import {
  provisionalMessage,
  type History,
  type Message,
  type Pending,
  type Process,
  type StreamEvent
} from './protocol'
export { provisionalMessage } from './protocol'

export const emptyHistory = (): History => ({
  session_id: '',
  messages: [],
  processes: [],
  work_plans: [],
  pending_interactions: [],
  collaboration: { active: false, pending: false, selected_active: false },
  event_cursor: null,
  has_more: false
})
/** 会话消息只接收公开用户输入和助手输出，内部推理不进入展示投影。 */
export function isPublicMessage(message: Message) {
  if (message.role !== 'user' && message.role !== 'assistant') return false
  const phase = message.phase ?? message.metadata?.phase
  return phase === undefined || phase === 'pending' || phase === 'final' || phase === 'commentary'
}

/** 累计快照的正文和终态按版本前进，时间与源序号保留首次确认的身份，不随重连移动。 */
export function mergeMessages(current: Message[], incoming: Message[], prepend = false) {
  const visibleCurrent = current.filter(isPublicMessage)
  const visibleIncoming = incoming.filter(isPublicMessage)
  const map = new Map(visibleCurrent.map((message) => [message.message_id, message]))
  for (const message of visibleIncoming) {
    const old = map.get(message.message_id)
    if (!old) {
      map.set(message.message_id, message)
      continue
    }
    const version = message.message_version ?? 1,
      previousVersion = old.message_version ?? 1
    if (version < previousVersion) continue
    // 本地回执只是临时显示；公开用户快照有权替换整条内容和资源。
    if (old[provisionalMessage] && message.role === 'user') {
      const next = {
        ...message,
        created_at: old.created_at ?? message.created_at,
        sequence: old.sequence ?? message.sequence
      }
      delete next[provisionalMessage]
      map.set(message.message_id, next)
      continue
    }
    // 相同版本只补齐迟到的时间或身份字段，不把重放正文当作一次新的输出。
    const next = version > previousVersion ? { ...old, ...message } : { ...message, ...old }
    next.created_at = old.created_at ?? message.created_at
    next.sequence = old.sequence ?? message.sequence
    next.metadata = {
      ...message.metadata,
      ...old.metadata,
      ...(version > previousVersion ? message.metadata : {}),
      ...((old.metadata?.source_sequence ?? message.metadata?.source_sequence) !== undefined
        ? { source_sequence: old.metadata?.source_sequence ?? message.metadata?.source_sequence }
        : {})
    }
    if (
      version === previousVersion &&
      next.created_at === old.created_at &&
      next.sequence === old.sequence &&
      Object.keys(message).every((key) => key in old) &&
      JSON.stringify(next.metadata) === JSON.stringify(old.metadata ?? {})
    )
      continue
    map.set(message.message_id, next)
  }
  const ids = prepend
    ? [...visibleIncoming.map((m) => m.message_id), ...visibleCurrent.map((m) => m.message_id)]
    : [...visibleCurrent.map((m) => m.message_id), ...visibleIncoming.map((m) => m.message_id)]
  return [...new Set(ids)].map((id) => map.get(id)!)
}
export function mergeProcesses(current: Process[], incoming: Process[]) {
  const map = new Map(current.map((item) => [item.process_id, item]))
  for (const item of incoming) {
    const old = map.get(item.process_id)
    if (!old || item.updated_sequence > old.updated_sequence) {
      if (
        old &&
        ['failed', 'outcome_unknown'].includes(old.status) &&
        ['succeeded', 'cancelled'].includes(item.status)
      )
        continue
      map.set(
        item.process_id,
        old
          ? { ...item, sequence: old.sequence, started_at: old.started_at ?? item.started_at }
          : item
      )
    }
  }
  return [...map.values()].sort((a, b) => a.sequence - b.sequence || a.ordinal - b.ordinal)
}
export function pendingIdentity(pending: Pending): Record<string, unknown> {
  if (
    pending.suspend_ref ||
    ['tool_input', 'user_input'].includes(pending.interaction_type ?? '')
  ) {
    if (
      !pending.execution_id ||
      typeof pending.run_revision !== 'string' ||
      !/^[1-9][0-9]*$/.test(pending.run_revision) ||
      !pending.tool_wait?.action_id ||
      !/^[1-9][0-9]*$/.test(pending.tool_wait.generation)
    )
      throw new Error('交互身份不完整，请重新读取会话后再提交。')
    if (
      pending.suspend_ref &&
      (!pending.workflow_execution_id ||
        !pending.resume_action_key ||
        typeof pending.execution_revision !== 'string' ||
        !/^[1-9][0-9]*$/.test(pending.execution_revision))
    )
      throw new Error('工作流等待身份不完整，请重新读取会话后再提交。')
    return { execution_id: pending.execution_id, run_revision: pending.run_revision }
  }
  const fields = [
    'execution_id',
    'run_revision',
    'checkpoint_revision',
    'interaction_type',
    'action_id',
    'invocation_id',
    'pending_id',
    'wake_id',
    'wait_revision',
    'pending_ref',
    'workflow_execution_id',
    'suspend_ref',
    'expected_revision',
    'resume_action_key'
  ] as const
  const required = [
    'execution_id',
    'run_revision',
    'checkpoint_revision',
    ...(pending.interaction_type === 'plan_review'
      ? ['action_id', 'invocation_id', 'pending_id', 'wake_id', 'wait_revision']
      : [])
  ]
  const record = pending as unknown as Record<string, unknown>
  if (required.some((key) => record[key] === null || record[key] === undefined))
    throw new Error('交互身份不完整，请重新读取会话后再提交。')
  return Object.fromEntries(
    fields.filter((key) => pending[key] !== undefined).map((key) => [key, pending[key]])
  )
}
/** 序号属于 Session；事件 ID 只作等值去重，从不解释不透明 cursor。 */
export function createProjection(
  session: string,
  history: History,
  runChanged: (run: string, status: string) => void,
  artifactsChanged: () => void = () => {},
  inputAccepted: (input: string, run: string) => void = () => {},
  workChanged: (work: string) => void = () => {},
  runProgress: (run: string, sourceSequence: number, rootInput: string) => void = () => {}
) {
  let sequence = 0
  const seen = new Set<string>(history.event_cursor ? [history.event_cursor] : [])
  return (event: StreamEvent) => {
    const data = event.data
    if (data.session_id && data.session_id !== session) return
    // 退休状态是历史可用性的声明，不分配或推进原durable事件cursor。
    if (event.event === 'history.retired') {
      if (
        typeof data.source_session_revision === 'number' &&
        Number.isSafeInteger(data.source_session_revision) &&
        data.source_session_revision > 0 &&
        typeof data.closed_at === 'string' &&
        Number.isFinite(Date.parse(data.closed_at)) &&
        typeof data.retired_through_sequence === 'number' &&
        Number.isSafeInteger(data.retired_through_sequence) &&
        data.retired_through_sequence >= 0 &&
        typeof data.retired_event_count === 'number' &&
        Number.isSafeInteger(data.retired_event_count) &&
        data.retired_event_count >= 0 &&
        Array.isArray(data.retired_event_types) &&
        data.retired_event_types.every((value) => typeof value === 'string') &&
        typeof data.complete === 'boolean'
      ) {
        const previous = history.history_retention
        if (
          previous &&
          (data.source_session_revision < previous.source_session_revision ||
            (data.source_session_revision === previous.source_session_revision &&
              (data.retired_event_count < previous.retired_event_count ||
                data.retired_through_sequence < previous.retired_through_sequence ||
                (previous.complete && !data.complete))))
        )
          return
        history.history_retention = {
          source_session_revision: data.source_session_revision,
          closed_at: data.closed_at,
          retired_through_sequence: data.retired_through_sequence,
          retired_event_count: data.retired_event_count,
          retired_event_types: data.retired_event_types as string[],
          complete: data.complete
        }
      }
      return
    }
    const next = typeof data.session_sequence === 'number' ? data.session_sequence : 0
    if (event.id && (seen.has(event.id) || (next > 0 && next <= sequence))) return
    if (event.id) {
      seen.add(event.id)
      if (seen.size > 1024) seen.delete(seen.values().next().value!)
      sequence = Math.max(sequence, next)
      history.event_cursor = event.id
    }
    if (
      event.event === 'message.delta' ||
      event.event === 'message.card' ||
      event.event === 'message.card.update'
    ) {
      if (typeof data.message_id === 'string') {
        const previous = history.messages.find((message) => message.message_id === data.message_id)
        history.messages = mergeMessages(history.messages, [
          {
            ...(previous?.[provisionalMessage] ? {} : previous),
            ...data,
            metadata: {
              ...(previous?.[provisionalMessage] ? {} : previous?.metadata),
              ...(data.metadata as Message['metadata']),
              ...Object.fromEntries(
                ['content_parts', 'attachment_ids', 'attachments']
                  .filter((key) => Object.hasOwn(data, key))
                  .map((key) => [key, data[key]])
              )
            },
            message_id: data.message_id,
            message_version: Number(data.message_version ?? 1),
            role: String(data.role ?? previous?.role ?? 'assistant'),
            content: String(data.content ?? previous?.content ?? '')
          } as Message
        ])
      }
    } else if (event.event.startsWith('process.')) {
      if (typeof data.process_id === 'string' && data.presentation)
        history.processes = mergeProcesses(history.processes, [data as unknown as Process])
      if (
        typeof data.process_id === 'string' &&
        data.presentation &&
        data.status === 'running' &&
        typeof data.run_id === 'string' &&
        data.run_id === data.root_run_id &&
        typeof data.updated_sequence === 'number' &&
        typeof data.root_input_id === 'string'
      )
        runProgress(data.run_id, data.updated_sequence, data.root_input_id)
      if ((data.changes as Process['changes'])?.artifacts === true) artifactsChanged()
    } else if (event.event === 'work.updated') {
      if (typeof data.work_id === 'string') workChanged(data.work_id)
    } else if (event.event === 'hitl.required') {
      const pending = data as unknown as Pending
      history.pending_interactions = [
        ...history.pending_interactions.filter(
          (item) => item.execution_id !== pending.execution_id
        ),
        pending
      ]
      runChanged(String(data.execution_id ?? data.run_id ?? ''), 'waiting_hitl')
    } else if (event.event === 'interaction.resolved') {
      const wait = data.tool_wait as Pending['tool_wait']
      const run = String(data.execution_id ?? data.run_id ?? '')
      const current = history.pending_interactions.find((item) => item.execution_id === run)
      history.pending_interactions = history.pending_interactions.filter(
        (item) =>
          item.execution_id !== run ||
          item.tool_wait?.action_id !== wait?.action_id ||
          item.tool_wait?.generation !== wait?.generation
      )
      if (
        !current ||
        (current.tool_wait?.action_id === wait?.action_id &&
          current.tool_wait?.generation === wait?.generation)
      )
        runChanged(run, 'running')
    } else if (event.event === 'interaction.rejected') {
      runChanged(String(data.run_id ?? ''), 'suspended')
    } else if (event.event === 'execution.accepted') {
      history.activities = history.activities?.map((item) =>
        item.node_id === data.run_id
          ? { ...item, status: 'running', render: { ...item.render, reason: undefined } }
          : item
      )
      runChanged(String(data.run_id ?? ''), 'running')
    } else if (event.event === 'execution.completed') {
      const run = String(data.run_id ?? '')
      // Live 与历史读取共用同一公开根状态；不要丢掉计划审批等待的可信原因。
      if (
        run &&
        data.root_run_id === run &&
        typeof data.root_input_id === 'string' &&
        typeof data.updated_sequence === 'number' &&
        Number.isSafeInteger(data.updated_sequence)
      ) {
        const previous = history.activities?.find((item) => item.node_id === run)
        const render = previous?.render as
          { updated_sequence?: number; root_input_id?: string } | undefined
        if (render?.root_input_id && render.root_input_id !== data.root_input_id) return
        if ((render?.updated_sequence ?? 0) > data.updated_sequence) return
        const activity = {
          ...previous,
          node_id: run,
          node_type: 'agent_runtime',
          status: String(data.status),
          render: {
            ...previous?.render,
            root_run_id: run,
            root_input_id: data.root_input_id,
            updated_sequence: data.updated_sequence,
            reason: typeof data.reason === 'string' ? data.reason : undefined
          }
        }
        history.activities = [
          ...(history.activities ?? []).filter((item) => item.node_id !== run),
          activity
        ]
      }
      if (data.status !== 'waiting_hitl')
        history.pending_interactions = history.pending_interactions.filter(
          (item) => item.execution_id !== run
        )
      runChanged(run, String(data.status))
      // 冻结清单的工具回执早于正式发布；成功终态后重新读取实际成果。
      if (data.status === 'completed') artifactsChanged()
    } else if (
      event.event === 'collaboration.updated' &&
      typeof data.active === 'boolean' &&
      typeof data.pending === 'boolean' &&
      typeof data.selected_active === 'boolean'
    )
      history.collaboration = data as unknown as History['collaboration']
    else if (event.event === 'error')
      throw new Error(typeof data.message === 'string' ? data.message : '实时事件暂时不可用。')
    // 只消费已提交执行事实的精确 input_id；root_input_id 不能确认同一运行的后续人工回复。
    if (
      typeof data.input_id === 'string' &&
      ([
        'execution.accepted',
        'execution.completed',
        'hitl.required',
        'interaction.resolved'
      ].includes(event.event) ||
        (event.event.startsWith('process.') &&
          typeof data.process_id === 'string' &&
          data.presentation))
    )
      inputAccepted(
        data.input_id,
        String(data.root_run_id ?? data.run_id ?? data.execution_id ?? '')
      )
  }
}
