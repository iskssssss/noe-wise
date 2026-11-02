import { describe, expect, it, vi } from 'vitest'
import {
  createProjection,
  emptyHistory,
  mergeMessages,
  mergeProcesses,
  pendingIdentity,
  provisionalMessage
} from './projection'
import { command } from './commands'
import type { Message, Pending, Process } from './protocol'
const process = (status: string, updated_sequence: number): Process => ({
  process_id: 'step',
  run_id: 'run',
  root_run_id: 'run',
  sequence: 2,
  ordinal: 0,
  updated_sequence,
  status,
  type: 'tool',
  presentation: { title: '查找订单', summary: status }
})
describe('会话公开投影', () => {
  it('模型流进展完成时保留首次过程位置和开始时间', () => {
    const first = { ...process('running', 3), sequence: 3, started_at: '2026-10-22T09:00:00+08:00' }
    const finished = {
      ...process('succeeded', 7),
      sequence: 7,
      started_at: '2026-10-22T09:00:03+08:00',
      finished_at: '2026-10-22T09:00:03+08:00'
    }
    expect(mergeProcesses([first], [finished])[0]).toMatchObject({
      sequence: 3,
      started_at: first.started_at,
      finished_at: finished.finished_at,
      updated_sequence: 7,
      status: 'succeeded'
    })
  })
  it('原等待的响应只关闭其准确代次并用新输入身份确认受理', () => {
    const history = emptyHistory()
    const changed = vi.fn(),
      accepted = vi.fn()
    const receive = createProjection('s', history, changed, undefined, accepted)
    const current: Pending = {
      execution_id: 'run',
      run_revision: '7',
      interaction_type: 'user_input',
      tool_wait: { action_id: 'ask', generation: '2' },
      question: '请补充'
    }
    history.pending_interactions = [current]
    receive({
      id: 'old-resolved',
      event: 'interaction.resolved',
      data: {
        execution_id: 'run',
        input_id: 'old-input',
        tool_wait: { action_id: 'ask', generation: '1' }
      }
    })
    expect(history.pending_interactions).toHaveLength(1)
    expect(changed).not.toHaveBeenCalled()
    receive({
      id: 'resolved',
      event: 'interaction.resolved',
      data: {
        execution_id: 'run',
        input_id: 'reply-input',
        tool_wait: { action_id: 'ask', generation: '2' }
      }
    })
    expect(history.pending_interactions).toHaveLength(0)
    expect(changed).toHaveBeenCalledWith('run', 'running')
    expect(accepted).toHaveBeenLastCalledWith('reply-input', 'run')
  })
  it('交互回复SSE使用真实输入身份合并为用户记录，同键重放不重复', () => {
    const history = emptyHistory()
    const receive = createProjection('s', history, vi.fn())
    const event = {
      id: 'reply-source',
      event: 'message.delta',
      data: {
        session_id: 's',
        message_id: 'reply-input',
        message_version: 1,
        role: 'user',
        content: '税率为13%，单价100元是不含税单价。',
        metadata: {
          input_id: 'reply-input',
          root_input_id: 'root',
          root_run_id: 'run',
          interaction_reply_kind: 'supplement',
          source_sequence: 24
        }
      }
    }
    receive(event)
    receive(event)
    expect(history.messages).toHaveLength(1)
    expect(history.messages[0]).toMatchObject({
      role: 'user',
      content: event.data.content,
      metadata: event.data.metadata
    })
  })
  it('将公开用户事件的媒体和附件摘要归入历史一致的 metadata', () => {
    const history = emptyHistory()
    const receive = createProjection('s', history, vi.fn())
    receive({
      id: 'user-event',
      event: 'message.delta',
      data: {
        session_id: 's',
        message_id: 'u',
        message_version: 1,
        role: 'user',
        content: '',
        content_parts: [{ type: 'image', media_id: 'media-1' }],
        attachment_ids: ['private-id'],
        attachments: [{ id: 'private-id', name: '所选资料' }]
      }
    })
    expect(history.messages[0]?.metadata).toMatchObject({
      content_parts: [{ type: 'image', media_id: 'media-1' }],
      attachment_ids: ['private-id'],
      attachments: [{ id: 'private-id', name: '所选资料' }]
    })
  })
  it('权威 SSE 同版本替换本地确认正文和所有资源，重复或旧版本不能恢复原文', () => {
    const history = emptyHistory()
    history.messages = [
      {
        message_id: 'u',
        message_version: 1,
        role: 'user',
        content: '敏感原文',
        [provisionalMessage]: true,
        sequence: 5,
        metadata: { content_parts: [{ type: 'image', media_id: 'm' }], attachment_ids: ['a'] }
      }
    ]
    const receive = createProjection('s', history, vi.fn())
    const safe = {
      session_id: 's',
      message_id: 'u',
      message_version: 1,
      role: 'user',
      content: '公开安全正文'
    }
    receive({ id: 'safe', event: 'message.delta', data: safe })
    expect(history.messages[0]).toMatchObject({
      content: '公开安全正文',
      sequence: 5,
      metadata: {}
    })
    expect(history.messages[0]?.metadata?.content_parts).toBeUndefined()
    expect(history.messages[0]?.metadata?.attachment_ids).toBeUndefined()
    receive({ id: 'repeat', event: 'message.delta', data: { ...safe, content: '迟到原文' } })
    expect(history.messages[0]?.content).toBe('公开安全正文')
  })
  it('权威历史同版本取代临时消息，旧版本不能覆盖且保留位置', () => {
    const local = {
      message_id: 'u',
      message_version: 2,
      role: 'user',
      content: '敏感原文',
      sequence: 5,
      [provisionalMessage]: true as const,
      metadata: {
        content_parts: [{ type: 'image' as const, media_id: 'm' }],
        attachment_ids: ['a']
      }
    }
    const safe = {
      message_id: 'u',
      message_version: 2,
      role: 'user',
      content: '公开安全正文',
      metadata: {}
    }
    expect(mergeMessages([local], [{ ...safe, message_version: 1 }])[0]).toBe(local)
    const result = mergeMessages([local], [safe])[0]!
    expect(result).toMatchObject({ content: '公开安全正文', sequence: 5, metadata: {} })
    expect(result[provisionalMessage]).toBeUndefined()
    expect(mergeMessages([result], [{ ...safe, content: '迟到原文' }])[0]?.content).toBe(
      '公开安全正文'
    )
  })
  it('退休连接快照不消耗cursor或替换已保留消息，迟到状态不能撤回退休事实', () => {
    const history = emptyHistory()
    history.event_cursor = 'output:original'
    history.messages = [
      {
        message_id: 'kept',
        message_version: 7,
        role: 'assistant',
        content: '保留回复',
        sequence: 5
      }
    ]
    const receive = createProjection('s', history, vi.fn())
    const state = {
      session_id: 's',
      source_session_revision: 8,
      closed_at: '2026-09-01T00:00:00Z',
      retired_through_sequence: 20,
      retired_event_count: 12,
      retired_event_types: ['agent.model-reasoning'],
      complete: true
    }
    receive({ event: 'history.retired', id: 'output:original', data: state })
    expect(history.history_retention).toMatchObject({ retired_event_count: 12, complete: true })
    expect(history.event_cursor).toBe('output:original')
    expect(history.messages[0]).toMatchObject({
      message_version: 7,
      content: '保留回复',
      sequence: 5
    })
    receive({
      event: 'history.retired',
      id: '',
      data: { ...state, retired_event_count: 2, complete: false }
    })
    receive({ event: 'history.retired', id: '', data: { ...state, retired_event_count: -1 } })
    receive({
      event: 'history.retired',
      id: '',
      data: { ...state, session_id: 'foreign', retired_event_count: 15 }
    })
    expect(history.history_retention?.retired_event_count).toBe(12)
    receive({
      event: 'message.delta',
      id: 'output:next',
      data: {
        session_id: 's',
        session_sequence: 21,
        message_id: 'next',
        message_version: 1,
        role: 'assistant',
        content: '后续',
        sequence: 21
      }
    })
    expect(history.event_cursor).toBe('output:next')
    expect(history.messages.map((value) => value.message_id)).toEqual(['kept', 'next'])
  })
  it('pending段立即展示，commentary收口不完成Run，最终答复使用独立消息身份', () => {
    const history = emptyHistory(),
      changed = vi.fn(),
      receive = createProjection('s', history, changed)
    const snapshot = (version: number, content: string, phase: string, state: string) => ({
      session_id: 's',
      session_sequence: version,
      message_id: 'attempt-1',
      message_version: version,
      role: 'assistant',
      root_run_id: 'r',
      sequence: 1,
      phase,
      state,
      content,
      created_at: '2026-10-11T00:00:00Z'
    })
    receive({ event: 'message.delta', id: '1', data: snapshot(1, '先', 'pending', 'streaming') })
    receive({
      event: 'message.delta',
      id: '2',
      data: snapshot(2, '先查询', 'pending', 'streaming')
    })
    expect(history.messages[0]).toMatchObject({
      content: '先查询',
      phase: 'pending',
      state: 'streaming'
    })
    receive({
      event: 'message.delta',
      id: '3',
      data: snapshot(3, '先查询资料。', 'commentary', 'completed')
    })
    expect(changed).not.toHaveBeenCalled()
    receive({
      event: 'message.delta',
      id: '4',
      data: {
        ...snapshot(4, '根据查询结果', 'pending', 'streaming'),
        message_id: 'attempt-2',
        sequence: 4
      }
    })
    expect(history.messages.map((message) => message.message_id)).toEqual([
      'attempt-1',
      'attempt-2'
    ])
    expect(history.messages[0]).toMatchObject({ state: 'completed', phase: 'commentary' })
  })
  it('累计快照保持首次时间和位置，同版本重放不替换正文，迟到时间仍可补齐', () => {
    const original: Message = {
      message_id: 'answer',
      message_version: 1,
      role: 'assistant',
      content: '开始',
      phase: 'pending',
      state: 'streaming',
      sequence: 2,
      created_at: '2026-10-11T00:00:00Z',
      metadata: { source_sequence: 2 }
    }
    const current = mergeMessages(
      [original],
      [
        {
          ...original,
          message_version: 2,
          content: '开始查询',
          sequence: 20,
          created_at: '2026-10-11T00:01:00Z',
          metadata: { source_sequence: 20 }
        }
      ]
    )
    expect(current[0]).toMatchObject({
      content: '开始查询',
      sequence: 2,
      created_at: original.created_at,
      metadata: { source_sequence: 2 }
    })
    const replay = mergeMessages(current, [{ ...current[0]!, content: '同版本不得替换' }])
    expect(replay[0]).toBe(current[0])
    const provisional = { message_id: 'user', message_version: 1, role: 'user', content: '你好' }
    expect(
      mergeMessages([provisional], [{ ...provisional, created_at: original.created_at }])[0]
    ).toMatchObject({ content: '你好', created_at: original.created_at })
  })
  it('重新创建投影后LastEventId重放仍然跳过，更新快照不会拼接已有正文', () => {
    const history = emptyHistory()
    history.event_cursor = 'already-seen'
    const changed = vi.fn(),
      receive = createProjection('s', history, changed)
    receive({
      id: 'already-seen',
      event: 'execution.accepted',
      data: { session_id: 's', run_id: 'r' }
    })
    expect(changed).not.toHaveBeenCalled()
    receive({
      id: 'next',
      event: 'message.delta',
      data: {
        session_id: 's',
        session_sequence: 10,
        message_id: 'a',
        message_version: 2,
        role: 'assistant',
        phase: 'pending',
        state: 'streaming',
        content: '完整累计前缀'
      }
    })
    receive({
      id: 'next-duplicate',
      event: 'message.delta',
      data: {
        session_id: 's',
        session_sequence: 11,
        message_id: 'a',
        message_version: 2,
        role: 'assistant',
        phase: 'pending',
        state: 'streaming',
        content: '完整累计前缀'
      }
    })
    expect(history.messages).toHaveLength(1)
    expect(history.messages[0]?.content).toBe('完整累计前缀')
  })
  it('提交确认只使用公开执行事实的精确身份，过滤跨会话、重复和未知事件', () => {
    const accepted = vi.fn()
    const receive = createProjection('s', emptyHistory(), vi.fn(), vi.fn(), accepted)
    receive({
      event: 'execution.accepted',
      id: 'wrong',
      data: { session_id: 'other', input_id: 'input' }
    })
    receive({ event: 'execution.accepted', id: 'root-only', data: { root_input_id: 'input' } })
    receive({ event: 'unknown', id: 'unknown', data: { input_id: 'input' } })
    receive({
      event: 'message.delta',
      id: 'private',
      data: { input_id: 'input', role: 'reasoning', message_id: 'private' }
    })
    expect(accepted).not.toHaveBeenCalled()
    const event = {
      event: 'execution.accepted',
      id: 'receipt',
      data: { session_id: 's', input_id: 'input', run_id: 'run' }
    }
    receive(event)
    receive(event)
    expect(accepted).toHaveBeenCalledExactlyOnceWith('input', 'run')
  })
  it('真实累计正文复用同一消息，终态更新版本且历史旧快照不能回退；私有推理不进入消息', () => {
    const history = emptyHistory()
    const receive = createProjection('s', history, () => {})
    const snapshot = (version: number, content: string, state: string) => ({
      message_id: 'answer',
      message_version: version,
      role: 'assistant',
      root_run_id: 'r',
      root_input_id: 'i',
      phase: 'final',
      content,
      state,
      session_id: 's',
      session_sequence: version
    })
    receive({ event: 'message.delta', id: 'first', data: snapshot(1, '安全', 'streaming') })
    receive({ event: 'message.delta', id: 'second', data: snapshot(2, '安全正文', 'streaming') })
    expect(history.messages.map((message) => message.content)).toEqual(['安全正文'])
    receive({
      event: 'message.delta',
      id: 'final',
      data: snapshot(3, '安全正文已完成', 'completed')
    })
    const older = {
      ...history.messages[0]!,
      message_version: 1,
      content: '旧内容',
      state: 'streaming' as const
    }
    history.messages = mergeMessages(history.messages, [older], true)
    receive({
      event: 'message.delta',
      id: 'reasoning',
      data: { message_id: 'private', role: 'reasoning', content: '内部推理', session_sequence: 4 }
    })
    expect(history.messages).toHaveLength(1)
    expect(history.messages[0]).toMatchObject({
      message_id: 'answer',
      message_version: 3,
      state: 'completed',
      content: '安全正文已完成'
    })
  })
  it('成果变化按当前会话的新事件通知，重复事件不重复刷新', () => {
    const changed = vi.fn()
    const receive = createProjection('mine', emptyHistory(), () => {}, changed)
    const event = {
      event: 'process.finished',
      id: 'artifact-event',
      data: {
        ...process('succeeded', 4),
        session_id: 'other',
        session_sequence: 4,
        changes: { artifacts: true }
      }
    }
    receive(event)
    expect(changed).not.toHaveBeenCalled()
    const current = { ...event, data: { ...event.data, session_id: 'mine' } }
    receive(current)
    receive(current)
    expect(changed).toHaveBeenCalledTimes(1)
  })
  it('两个等待任务各自恢复，结束一个任务不清除另一个待办', () => {
    const history = emptyHistory()
    const receive = createProjection('s', history, () => {})
    const waiting = (run: string, seq: number) =>
      receive({
        id: String(seq),
        event: 'hitl.required',
        data: {
          session_id: 's',
          session_sequence: seq,
          execution_id: run,
          run_revision: 2,
          checkpoint_revision: 2
        }
      })
    waiting('first', 1)
    waiting('second', 2)
    expect(history.pending_interactions.map((item) => item.execution_id)).toEqual([
      'first',
      'second'
    ])
    receive({
      id: '3',
      event: 'execution.completed',
      data: { run_id: 'first', status: 'completed', session_sequence: 3 }
    })
    expect(history.pending_interactions.map((item) => item.execution_id)).toEqual(['second'])
    waiting('second', 4)
    expect(history.pending_interactions).toHaveLength(1)
  })
  it('两次计划待批保留真实根元数据，恢复清原因，旧根序列不能回退', () => {
    const history = emptyHistory(),
      changed = vi.fn()
    const receive = createProjection('s', history, changed)
    const waiting = (id: string, sequence: number) => ({
      id,
      event: 'execution.completed',
      data: {
        session_id: 's',
        run_id: 'root',
        root_run_id: 'root',
        root_input_id: 'input',
        status: 'waiting_hitl',
        reason: 'plan_approval_required',
        updated_sequence: sequence
      }
    })
    receive(waiting('first', 46))
    expect(history.activities?.[0]).toMatchObject({
      status: 'waiting_hitl',
      render: {
        root_run_id: 'root',
        root_input_id: 'input',
        reason: 'plan_approval_required',
        updated_sequence: 46
      }
    })
    receive({
      id: 'resume',
      event: 'execution.accepted',
      data: { session_id: 's', run_id: 'root' }
    })
    expect(history.activities?.[0]?.render).not.toHaveProperty('reason', 'plan_approval_required')
    receive(waiting('second', 161))
    receive(waiting('late-first', 46))
    expect(history.activities?.[0]).toMatchObject({
      status: 'waiting_hitl',
      render: {
        reason: 'plan_approval_required',
        updated_sequence: 161
      }
    })
    expect(changed).toHaveBeenCalledTimes(3)
    expect(history.pending_interactions).toEqual([])
    receive({
      id: 'member',
      event: 'execution.completed',
      data: {
        ...waiting('', 162).data,
        run_id: 'member'
      }
    })
    expect(history.activities).toHaveLength(1)
  })

  it('成功完成后刷新正式成果，等待或失败不触发，重复终态只刷新一次', () => {
    const changed = vi.fn()
    const receive = createProjection('mine', emptyHistory(), () => {}, changed)
    const completion = (id: string, status: string) => ({
      id,
      event: 'execution.completed',
      data: { session_id: 'mine', run_id: 'run', status, session_sequence: Number(id) }
    })
    receive(completion('1', 'waiting_hitl'))
    receive(completion('2', 'failed'))
    expect(changed).not.toHaveBeenCalled()
    receive(completion('3', 'completed'))
    receive(completion('3', 'completed'))
    expect(changed).toHaveBeenCalledTimes(1)
  })
  it('消息累计快照按版本合并，历史分页不能回退正文', () => {
    const current = [
      { message_id: 'msg', message_version: 3, role: 'assistant', content: '完整回复' }
    ]
    expect(
      mergeMessages(current, [{ ...current[0]!, message_version: 1, content: '旧回复' }], true)
    ).toEqual(current)
  })
  it('已知工具失败不被晚到成功覆盖', () => {
    const failed = process('failed', 3)
    expect(mergeProcesses([failed], [process('succeeded', 4)])).toEqual([failed])
  })
  it('不同会话和重复/倒退事件不污染当前会话', () => {
    const history = emptyHistory(),
      receive = createProjection('mine', history, () => {})
    const event = {
      event: 'message.delta',
      id: 'z',
      data: { session_id: 'other', session_sequence: 10, message_id: 'a', content: 'private' }
    }
    receive(event)
    expect(history.messages).toEqual([])
    receive({ ...event, data: { ...event.data, session_id: 'mine' } })
    receive({
      ...event,
      id: 'alphabetically-later',
      data: { ...event.data, session_id: 'mine', session_sequence: 9, content: 'old' }
    })
    expect(history.messages[0]?.content).toBe('private')
    expect(history.event_cursor).toBe('z')
  })
  it('终态按 run 关联而不是当前 input ID 关联', () => {
    const update = vi.fn(),
      receive = createProjection('s', emptyHistory(), update)
    receive({
      id: 'opaque',
      event: 'execution.completed',
      data: {
        session_id: 's',
        run_id: 'run',
        input_id: 'different-cause',
        status: 'cancelled',
        session_sequence: 1
      }
    })
    expect(update).toHaveBeenCalledWith('run', 'cancelled')
  })
  it('读取真实扁平 collaboration.updated 事件', () => {
    const history = emptyHistory()
    createProjection('s', history, () => {})({
      id: '',
      event: 'collaboration.updated',
      data: {
        session_id: 's',
        active: true,
        pending: false,
        selected_active: true
      }
    })
    expect(history.collaboration.selected_active).toBe(true)
  })
  it('补参保留完整等待身份并拒绝缺少版本', () => {
    const pending: Pending = {
      execution_id: 'run',
      run_revision: '3',
      interaction_type: 'tool_input',
      tool_wait: { action_id: 'a', generation: '9007199254740993' },
      card: { type: 'tool_input' }
    }
    expect(pendingIdentity(pending)).toEqual({ execution_id: 'run', run_revision: '3' })
    expect(() =>
      pendingIdentity({ ...pending, run_revision: undefined } as unknown as Pending)
    ).toThrow('身份不完整')
  })
})
describe('控制命令', () => {
  it('目标默认 256 轮并允许显式轮数至 1024', () => {
    expect(command('/goal 完成报告')).toEqual({
      kind: 'goal-create',
      objective: '完成报告',
      rounds: 256
    })
    expect(command('/goal --rounds 8 完成报告')).toEqual({
      kind: 'goal-create',
      objective: '完成报告',
      rounds: 8
    })
    expect(command('/goal --rounds 1024 完成报告')).toEqual({
      kind: 'goal-create',
      objective: '完成报告',
      rounds: 1024
    })
  })
  it('无效目标在创建会话前拒绝', () => {
    expect(() => command('/goal')).toThrow()
    expect(() => command('/goal --rounds 0 工作')).toThrow()
    expect(() => command('/goal --rounds 1025 工作')).toThrow()
  })
  it('只有完整控制命令截获，带正文的 plan 交给后端', () => {
    expect(command('/plan')).toEqual({ kind: 'plan', active: true })
    expect(command('/plan 请制定计划')).toBeUndefined()
    expect(command('/goalkeeper')).toBeUndefined()
  })
})

describe('计划持久事件', () => {
  it('只按授权会话的work_id触发快照读取，重复回放不再触发', () => {
    const changed = vi.fn(),
      history = emptyHistory()
    const receive = createProjection('s', history, vi.fn(), vi.fn(), vi.fn(), changed)
    const event = {
      id: 'event:work',
      event: 'work.updated',
      data: { session_id: 's', work_id: 'w', session_sequence: 1 }
    }
    receive(event)
    receive(event)
    receive({
      ...event,
      id: 'other',
      data: { ...event.data, session_id: 'other', session_sequence: 2 }
    })
    expect(changed).toHaveBeenCalledExactlyOnceWith('w')
    expect(history.event_cursor).toBe('event:work')
  })
})
