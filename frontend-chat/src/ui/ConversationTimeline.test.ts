import s05History from './fixtures/s05-clarification-history.json'
import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { ChatMessage } from './controls'
import ConversationTimeline from './ConversationTimeline.vue'
import { createProjection, emptyHistory, provisionalMessage } from '../projection'
import type { Client } from '../client'
import type { History, Message, MessageState, Work } from '../protocol'
import { conversationEntries, conversationTurns, finalReady } from './conversationTimeline'

function timestampTimeline(messages: Message[]) {
  return mount(ConversationTimeline, {
    attachTo: document.body,
    props: {
      history: { ...emptyHistory(), messages },
      client: {} as Client,
      session: 's',
      locked: false,
      runStates: {},
      artifacts: []
    },
    global: { stubs: { ArtifactCards: true } }
  })
}

describe('正文消息时间', () => {
  it('按正式完成原因展示具体预算资源，并在重放后保留原未知原因', async () => {
    const wrapper = timestampTimeline([
      {
        message_id: 'failed-answer',
        message_version: 1,
        role: 'assistant',
        run_id: 'r',
        root_run_id: 'r',
        content: '本次未完成。',
        state: 'failed',
        phase: 'final'
      }
    ])
    try {
      const history = {
        ...emptyHistory(),
        messages: [
          {
            message_id: 'failed-answer',
            message_version: 1,
            role: 'assistant',
            run_id: 'r',
            root_run_id: 'r',
            content: '本次未完成。',
            state: 'failed' as const,
            phase: 'final' as const
          }
        ],
        activities: [
          {
            node_id: 'r',
            node_type: 'agent_runtime',
            status: 'failed',
            render: { reason: 'execution_budget_root_knowledge_calls_limit' }
          }
        ]
      }
      await wrapper.setProps({ history })
      expect(wrapper.text()).toContain('任务根的知识调用额度已耗尽。')
      await wrapper.setProps({
        history: {
          ...history,
          activities: [
            {
              ...history.activities[0]!,
              render: { reason: 'execution_output_closing_budget_insufficient' }
            }
          ]
        }
      })
      expect(wrapper.text()).toContain('无法为任务收尾预留足够额度，请联系管理员检查运行额度。')
      await wrapper.setProps({
        history: {
          ...history,
          activities: [{ ...history.activities[0]!, render: { reason: 'tool_output_invalid' } }]
        }
      })
      expect(wrapper.text()).toContain('工具已执行，但返回内容未通过格式校验')
      await wrapper.setProps({
        history: {
          ...history,
          activities: [
            {
              ...history.activities[0]!,
              status: 'terminated',
              render: { reason: 'tool_progress_stopped' }
            }
          ]
        }
      })
      expect(wrapper.text()).toContain('工具调用和结果反复相同，本次执行已停止')
      expect(wrapper.text()).not.toContain('tool_progress_stopped')
      await wrapper.setProps({
        history: {
          ...history,
          activities: [
            { ...history.activities[0]!, render: { reason: 'custom_unsettled_resource' } }
          ]
        }
      })
      expect(wrapper.text()).toContain('custom_unsettled_resource')
    } finally {
      wrapper.unmount()
    }
  })
  it('历史中的真实知识依据可从正文定位本消息锚点，不生成私有材料链接', () => {
    const wrapper = timestampTimeline([
      {
        message_id: 'answer',
        message_version: 1,
        role: 'assistant',
        content: '依据实际知识结果回答。[知识依据 1](#citation-k0123456789abcdef)',
        metadata: {
          citations: [
            {
              ordinal: 1,
              item_id: 'actual-item',
              source_id: 'actual-source',
              source_version: '7',
              label: '知识依据 1',
              citation_id: 'k0123456789abcdef'
            }
          ]
        }
      }
    ])
    try {
      const citations = wrapper.get('[aria-label="知识依据"]')
      expect(citations.text()).toBe('知识依据 1')
      expect(citations.find('a').exists()).toBe(false)
      expect(citations.find('button').exists()).toBe(false)
      expect(citations.get('li').attributes('id')).toBe('citation-k0123456789abcdef')
      expect(wrapper.get('a').attributes('href')).toBe('#citation-k0123456789abcdef')
      expect(wrapper.text()).not.toContain('actual-source')
    } finally {
      wrapper.unmount()
    }
  })
  it('零模型与工具的预算失败在历史、实时和重放中保留唯一安全说明且不显示成功', async () => {
    const history = emptyHistory()
    history.messages = [
      {
        message_id: 'budget-input',
        message_version: 1,
        role: 'user',
        content: '请简短回复预算门禁验证。'
      }
    ]
    const runStates: Record<string, string> = {}
    const receive = createProjection('s', history, (run, status) => {
      runStates[run] = status
    })
    const data = {
      message_id: 'runtime-message:budget-run:3:failed-1',
      message_version: 1,
      run_id: 'budget-run',
      root_run_id: 'budget-run',
      root_input_id: 'budget-input',
      phase: 'final',
      state: 'failed',
      source_sequence: 8,
      updated_sequence: 8,
      content: '执行预算不足，本次请求未能完成。尚无模型调用或工具执行记录。'
    }
    const wrapper = mount(ConversationTimeline, {
      props: {
        history: structuredClone(history),
        client: {} as Client,
        session: 's',
        locked: false,
        runStates,
        artifacts: []
      },
      global: { stubs: { ArtifactCards: true } }
    })
    for (let replay = 0; replay < 2; replay++) {
      receive({ id: `failure-message-${replay}`, event: 'message.delta', data })
      receive({
        id: `failure-completion-${replay}`,
        event: 'execution.completed',
        data: { run_id: 'budget-run', status: 'failed' }
      })
      await wrapper.setProps({ history: structuredClone(history), runStates: { ...runStates } })
      expect(wrapper.findAll('.conversation-turn')).toHaveLength(1)
      expect(wrapper.findAll('.process-group')).toHaveLength(0)
      expect(wrapper.findAll('.message.assistant')).toHaveLength(1)
      expect(wrapper.text()).toContain(data.content)
      expect(wrapper.get('[data-output-state="failed"]').text()).toContain('执行未完成')
      expect(wrapper.text()).not.toContain('执行完成')
      expect(finalReady(conversationTurns(history)[0]!)).toBe(false)
    }
    await wrapper.setProps({ history: structuredClone(history) })
    expect(wrapper.findAll('.message.assistant')).toHaveLength(1)
    expect(wrapper.text()).toContain(data.content)
    wrapper.unmount()
  })

  it('S05真实历史中提问与补充不重复挂载同Run汇总，刷新保留全部过程', async () => {
    const history = {
      ...emptyHistory(),
      ...(s05History as Pick<History, 'messages' | 'processes'>)
    }
    const run = history.processes[0]!.root_run_id!
    const wrapper = mount(ConversationTimeline, {
      props: {
        history,
        client: {} as Client,
        session: 's05-session',
        locked: false,
        runStates: { [run]: 'completed' },
        artifacts: []
      },
      global: { stubs: { ArtifactCards: true } }
    })
    const assertHistory = () => {
      expect(wrapper.findAll('.conversation-turn')).toHaveLength(1)
      expect(wrapper.findAll('.process-group')).toHaveLength(1)
      expect(wrapper.get('.process-operation-count').text()).toBe('1 项执行记录')
      expect(wrapper.findAll('[data-process-trace-toggle]')).toHaveLength(1)
      expect(wrapper.get('[data-process-trace-toggle]').text()).toContain('3 次模型调用')
      expect(
        wrapper.findAll('[data-message-id]').map((node) => node.attributes('data-message-id'))
      ).toEqual(history.messages.map((message) => message.message_id))
      for (const message of history.messages)
        expect(wrapper.findAll(`[data-message-id="${message.message_id}"]`)).toHaveLength(1)
      expect(wrapper.text()).toContain('补充回复')
      expect(wrapper.text()).toContain('339')
    }
    assertHistory()
    await wrapper.get('[data-process-trace-toggle]').trigger('click')
    expect(
      wrapper.findAll('[data-process-id]').map((node) => node.attributes('data-process-id'))
    ).toEqual(history.processes.map((process) => process.process_id))
    await wrapper.setProps({ history: structuredClone(history) })
    assertHistory()
    expect(
      wrapper.findAll('[data-process-id]').map((node) => node.attributes('data-process-id'))
    ).toEqual(history.processes.map((process) => process.process_id))
    wrapper.unmount()
  })

  it('共享输入的不同根Run保留独立轨迹和各自统计', () => {
    const history = {
      ...emptyHistory(),
      ...structuredClone(s05History as Pick<History, 'messages' | 'processes'>)
    }
    const original = history.processes[0]!
    history.processes.push({
      ...original,
      process_id: 'other-run-call',
      run_id: 'other-run',
      root_run_id: 'other-run',
      sequence: 70,
      ordinal: 0
    })
    const entries = conversationEntries(conversationTurns(history)[0]!).filter(
      (entry) => entry.kind === 'process'
    )
    expect(entries).toHaveLength(2)
    expect(entries.map((entry) => entry.turn.processes.length)).toEqual([5, 1])
    expect(entries.map((entry) => entry.turn.runIds)).toEqual([
      [original.root_run_id],
      ['other-run']
    ])
  })

  it('同Run补充使用原因果输入归属，历史刷新不重复挂载同一执行轨迹', async () => {
    const history = {
      ...emptyHistory(),
      messages: [
        { message_id: 'original', message_version: 1, role: 'user', content: '计算含税价格' },
        {
          message_id: 'runtime-message:run:7:suspended',
          message_version: 1,
          role: 'assistant',
          content: '请提供税率',
          metadata: {
            root_input_id: 'original',
            root_run_id: 'run',
            phase: 'pending' as const,
            state: 'completed',
            source_sequence: 22
          }
        },
        {
          message_id: 'reply',
          message_version: 1,
          role: 'user',
          content: '税率13%',
          metadata: {
            input_id: 'reply',
            root_input_id: 'original',
            root_run_id: 'run',
            source_sequence: 24
          }
        },
        {
          message_id: 'answer',
          message_version: 1,
          role: 'assistant',
          content: '339元',
          phase: 'final' as const,
          state: 'completed' as const,
          metadata: { root_input_id: 'original', root_run_id: 'run' }
        }
      ],
      processes: [
        {
          process_id: 'call',
          run_id: 'run',
          root_run_id: 'run',
          root_input_id: 'original',
          input_id: 'original',
          type: 'reasoning',
          status: 'succeeded',
          sequence: 5,
          ordinal: 0,
          updated_sequence: 8,
          presentation: { title: '模型调用', summary: '' }
        }
      ]
    }
    const wrapper = mount(ConversationTimeline, {
      props: {
        history,
        session: 's',
        client: {} as Client,
        locked: false,
        runStates: { run: 'completed' },
        artifacts: []
      },
      global: { stubs: { ArtifactCards: true } }
    })
    expect(wrapper.findAll('.conversation-turn')).toHaveLength(1)
    expect(wrapper.findAll('.process-group')).toHaveLength(1)
    expect(wrapper.findAll('[data-message-id="reply"]')).toHaveLength(1)
    expect(wrapper.findAll('[data-message-id="runtime-message:run:7:suspended"]')).toHaveLength(1)
    expect(wrapper.get('[data-message-id="runtime-message:run:7:suspended"]').text()).toContain(
      '请提供税率'
    )
    await wrapper.setProps({
      history: { ...history, messages: history.messages.map((message) => ({ ...message })) }
    })
    expect(wrapper.findAll('.conversation-turn')).toHaveLength(1)
    expect(wrapper.findAll('.process-group')).toHaveLength(1)
    expect(wrapper.findAll('[data-message-id="runtime-message:run:7:suspended"]')).toHaveLength(1)
    wrapper.unmount()
  })
  it('真实补充回复与操作确认各有标识，刷新后仍按原输入归属保留在同一轮', () => {
    const messages: Message[] = [
      {
        message_id: 'root',
        message_version: 1,
        role: 'user',
        content: '计算含税价格',
        metadata: { root_input_id: 'root' }
      },
      {
        message_id: 'question',
        message_version: 1,
        role: 'assistant',
        content: '请提供税率',
        metadata: { root_input_id: 'root', root_run_id: 'run' }
      },
      {
        message_id: 'reply',
        message_version: 1,
        role: 'user',
        content: '税率为13%，单价100元是不含税单价。',
        metadata: {
          root_input_id: 'root',
          root_run_id: 'run',
          interaction_reply_kind: 'supplement',
          source_sequence: 24
        }
      },
      {
        message_id: 'auth',
        message_version: 1,
        role: 'user',
        content: '已提交批准计划',
        metadata: {
          root_input_id: 'root',
          root_run_id: 'run',
          interaction_reply_kind: 'operation_authorization',
          source_sequence: 25
        }
      },
      {
        message_id: 'final',
        message_version: 1,
        role: 'assistant',
        content: '339元',
        phase: 'final',
        state: 'completed',
        metadata: { root_input_id: 'root', root_run_id: 'run' }
      }
    ]
    const wrapper = timestampTimeline(messages)
    expect(conversationTurns({ messages, processes: [] })).toHaveLength(1)
    expect(wrapper.get('[data-message-id="reply"]').text()).toContain('补充回复')
    expect(wrapper.get('[data-message-id="reply"]').text()).toContain(
      '税率为13%，单价100元是不含税单价。'
    )
    expect(wrapper.get('[data-message-id="auth"]').text()).toContain('操作确认记录')
    expect(wrapper.findAll('[data-message-id="reply"]')).toHaveLength(1)
    wrapper.unmount()
  })
  it('公开安全快照替换本地回执时移除图片并释放 Blob', async () => {
    const revoke = vi.fn()
    const originalURL = URL
    vi.stubGlobal(
      'URL',
      Object.assign(class extends originalURL {}, {
        createObjectURL: vi.fn(() => 'blob:temporary'),
        revokeObjectURL: revoke
      })
    )
    const history = emptyHistory()
    history.messages = [
      {
        message_id: 'u',
        message_version: 1,
        role: 'user',
        content: '敏感原文',
        [provisionalMessage]: true,
        metadata: { content_parts: [{ type: 'image', media_id: 'm' }], attachment_ids: ['private'] }
      }
    ]
    const wrapper = mount(ConversationTimeline, {
      props: {
        history,
        client: { blob: vi.fn(async () => new Blob(['image'])) } as unknown as Client,
        session: 's',
        locked: false,
        runStates: {},
        artifacts: []
      },
      global: { stubs: { ArtifactCards: true } }
    })
    try {
      await flushPromises()
      expect(wrapper.findComponent({ name: 'MediaImage' }).exists()).toBe(true)
      createProjection(
        's',
        history,
        vi.fn()
      )({
        event: 'message.delta',
        id: 'safe',
        data: {
          session_id: 's',
          message_id: 'u',
          message_version: 1,
          role: 'user',
          content: '公开安全正文'
        }
      })
      await wrapper.setProps({ history: { ...history } })
      await flushPromises()
      expect(wrapper.text()).toContain('公开安全正文')
      expect(wrapper.text()).not.toContain('敏感原文')
      expect(wrapper.findComponent({ name: 'MediaImage' }).exists()).toBe(false)
      expect(wrapper.find('.message-attachment-summary').exists()).toBe(false)
      expect(revoke).toHaveBeenCalledWith('blob:temporary')
    } finally {
      wrapper.unmount()
      vi.unstubAllGlobals()
    }
  })
  it('公开快照移除图片时取消在途读取，迟到 Blob 不再创建 URL', async () => {
    const create = vi.fn(() => 'blob:late')
    const originalURL = URL
    vi.stubGlobal(
      'URL',
      Object.assign(class extends originalURL {}, {
        createObjectURL: create,
        revokeObjectURL: vi.fn()
      })
    )
    let finish!: (value: Blob) => void
    let signal!: AbortSignal
    const blob = vi.fn((_url: string, value: AbortSignal) => {
      signal = value
      return new Promise<Blob>((resolve) => {
        finish = resolve
      })
    })
    const history = emptyHistory()
    history.messages = [
      {
        message_id: 'u',
        message_version: 1,
        role: 'user',
        content: '原文',
        [provisionalMessage]: true,
        metadata: { content_parts: [{ type: 'image', media_id: 'm' }] }
      }
    ]
    const wrapper = mount(ConversationTimeline, {
      props: {
        history,
        client: { blob } as unknown as Client,
        session: 's',
        locked: false,
        runStates: {},
        artifacts: []
      },
      global: { stubs: { ArtifactCards: true } }
    })
    try {
      expect(signal.aborted).toBe(false)
      createProjection(
        's',
        history,
        vi.fn()
      )({
        event: 'message.delta',
        id: 'safe',
        data: {
          session_id: 's',
          message_id: 'u',
          message_version: 1,
          role: 'user',
          content: '安全正文'
        }
      })
      await wrapper.setProps({ history: { ...history } })
      expect(signal.aborted).toBe(true)
      finish(new Blob(['late']))
      await flushPromises()
      expect(create).not.toHaveBeenCalled()
      expect(wrapper.text()).toContain('安全正文')
    } finally {
      wrapper.unmount()
      vi.unstubAllGlobals()
    }
  })
  it('历史展示已公开的冻结附件名称，不展示内部标识', () => {
    const wrapper = timestampTimeline([
      {
        message_id: 'uploaded',
        message_version: 1,
        role: 'user',
        content: '',
        metadata: {
          content_parts: [],
          attachment_ids: ['private-id-one', 'private-id-two'],
          attachments: [
            { id: 'private-id-one', name: '报告.txt' },
            { id: 'private-id-two', name: '所选资料' }
          ]
        }
      }
    ])
    expect(wrapper.get('.message-attachment-summary').text()).toContain('报告.txt、所选资料')
    expect(wrapper.text()).not.toContain('private-id')
    wrapper.unmount()
  })
  it('纯附件用户消息显示真实附件数量，不生成占位正文或暴露文件 ID', () => {
    const wrapper = timestampTimeline([
      {
        message_id: 'uploaded',
        message_version: 1,
        role: 'user',
        content: '',
        metadata: { content_parts: [], attachment_ids: ['private-id-one', 'private-id-two'] }
      }
    ])
    try {
      expect(wrapper.get('.message.user .message-attachment-summary').text()).toBe('2 个附件')
      expect(wrapper.text()).not.toContain('private-id')
      expect(wrapper.find('.message.user .markdown').exists()).toBe(false)
    } finally {
      wrapper.unmount()
    }
  })
  it('用户和助手时间延迟显示在正文下方，统一北京时间格式且不再弹出浮层', async () => {
    vi.useFakeTimers()
    const wrapper = timestampTimeline([
      {
        message_id: 'u',
        message_version: 1,
        role: 'user',
        content: '你好',
        created_at: '2026-10-10T16:20:00Z'
      },
      {
        message_id: 'a',
        message_version: 1,
        role: 'assistant',
        content: '收到',
        created_at: '2026-10-11T00:20:03+08:00'
      }
    ])
    try {
      expect(document.querySelector('[role="tooltip"]')).toBeNull()
      for (const [role, time, expected] of [
        ['user', '2026-10-10T16:20:00Z', '2026-10-11 00:20:00'],
        ['assistant', '2026-10-11T00:20:03+08:00', '2026-10-11 00:20:03']
      ]) {
        const body = wrapper.get(`.message.${role} .message-body`)
        expect(body.attributes('tabindex')).toBe('0')
        expect(body.attributes('title')).toBeUndefined()
        const tip = body.get('time')
        expect(tip.text()).toBe(expected)
        expect(tip.attributes('datetime')).toBe(time)
        expect(tip.classes()).not.toContain('is-visible')
        await body.trigger('pointerenter', { pointerType: 'mouse' })
        await vi.advanceTimersByTimeAsync(699)
        expect(tip.classes()).not.toContain('is-visible')
        await vi.advanceTimersByTimeAsync(1)
        expect(tip.classes()).toContain('is-visible')
        expect(document.querySelector('[role="tooltip"]')).toBeNull()
        expect(body.element.contains(tip.element)).toBe(true)
        expect(body.attributes('aria-describedby')).toBe(tip.attributes('id'))
        await body.trigger('pointerleave')
        expect(tip.classes()).not.toContain('is-visible')
        await body.trigger('focus')
        expect(tip.classes()).toContain('is-visible')
        await body.trigger('keydown', { key: 'Escape' })
        await body.trigger('mousemove')
        expect(tip.classes()).not.toContain('is-visible')
        await body.trigger('focus')
        await body.trigger('blur')
        expect(tip.classes()).not.toContain('is-visible')
      }
    } finally {
      wrapper.unmount()
      vi.useRealTimers()
    }
    expect(document.querySelector('[role="tooltip"]')).toBeNull()
  })

  it.each([undefined, ''])('缺失时间不新增提示：%s', async (created_at) => {
    const wrapper = timestampTimeline([
      { message_id: 'a', message_version: 1, role: 'assistant', content: '收到', created_at }
    ])
    try {
      const body = wrapper.get('.message-body')
      expect(body.attributes('tabindex')).toBeUndefined()
      await body.trigger('pointerenter', { pointerType: 'mouse' })
      await body.trigger('focus')
      expect(document.querySelector('[role="tooltip"]')).toBeNull()
      expect(body.find('time').exists()).toBe(false)
      expect(body.attributes('aria-describedby')).toBeUndefined()
    } finally {
      wrapper.unmount()
    }
  })

  it.each([
    ['2026-10-11T00:20:03', '2026-10-11 00:20:03'],
    ['2026-10-11', '2026-10-11'],
    ['invalidZ', '—'],
    ['2026-02-30T00:20:00Z', '—']
  ])('已有时间使用北京时间格式或占位符：%s', (created_at, expected) => {
    const wrapper = timestampTimeline([
      { message_id: 'a', message_version: 1, role: 'assistant', content: '收到', created_at }
    ])
    try {
      const body = wrapper.get('.message-body')
      expect(body.attributes('tabindex')).toBe('0')
      expect(body.get('time').text()).toBe(expected)
      expect(body.get('time').attributes('datetime')).toBe(
        expected === '—' ? undefined : created_at
      )
    } finally {
      wrapper.unmount()
    }
  })
})

describe('过程自动收起', () => {
  it('流式状态和完成引用使用同一稳定页脚，收口立即停止加载指示', async () => {
    const message: Message = {
      message_id: 'a',
      message_version: 1,
      role: 'assistant',
      content: '已经收到的正文',
      phase: 'pending',
      state: 'streaming'
    }
    const wrapper = timestampTimeline([message])
    try {
      const footer = wrapper.get('.message-footer').element
      expect(wrapper.get('.stream-indicator').attributes('aria-hidden')).toBe('true')
      expect(wrapper.get('[data-output-state="streaming"]').text()).toBe('正在输出')
      await wrapper.setProps({
        history: {
          ...emptyHistory(),
          messages: [{ ...message, message_version: 2, phase: 'final', state: 'completed' }]
        }
      })
      expect(wrapper.get('.message-footer').element).toBe(footer)
      expect(wrapper.find('.stream-indicator').exists()).toBe(false)
      expect(wrapper.find('[data-output-state="streaming"]').exists()).toBe(false)
      expect(wrapper.get('.message-reference').text()).toBe('引用')
      expect(wrapper.get('.markdown').text()).toBe(message.content)
    } finally {
      wrapper.unmount()
    }
  })
  it('pending正文即时更新同一节点，commentary和后续工具按首次源序保留在独立时间线位置', async () => {
    const history = emptyHistory()
    history.messages = [
      {
        message_id: 'u',
        message_version: 1,
        role: 'user',
        content: '查询资料',
        sequence: 1,
        root_run_id: 'r'
      },
      {
        message_id: 'a-1',
        message_version: 1,
        role: 'assistant',
        content: '先查',
        sequence: 3,
        root_run_id: 'r',
        phase: 'pending',
        state: 'streaming',
        created_at: '2026-10-11T00:00:00Z'
      }
    ]
    history.processes = [
      {
        process_id: 'model',
        run_id: 'r',
        root_run_id: 'r',
        sequence: 2,
        ordinal: 0,
        updated_sequence: 2,
        type: 'reasoning',
        status: 'running',
        presentation: { title: '模型调用', summary: '' }
      }
    ]
    const wrapper = mount(ConversationTimeline, {
      props: {
        history,
        client: {} as Client,
        session: 's',
        locked: false,
        runStates: { r: 'running' },
        artifacts: []
      },
      global: { stubs: { ArtifactCards: true } }
    })
    const body = wrapper.get('[data-message-id="a-1"] .message-body').element
    expect(wrapper.get('[data-output-state="streaming"]').text()).toBe('正在输出')
    expect(wrapper.find('[data-message-id="a-1"] .message-reference').exists()).toBe(false)
    expect(body.closest('.process-group')).toBeNull()
    await wrapper.setProps({
      history: {
        ...history,
        messages: [
          history.messages[0]!,
          {
            ...history.messages[1]!,
            content: '先查询资料。',
            message_version: 2
          }
        ]
      }
    })
    expect(wrapper.get('[data-message-id="a-1"] .message-body').element).toBe(body)
    const updated = {
      ...history,
      messages: [
        history.messages[0]!,
        {
          ...history.messages[1]!,
          content: '先查询资料。',
          message_version: 3,
          phase: 'commentary' as const,
          state: 'completed' as const
        },
        {
          message_id: 'a-2',
          message_version: 1,
          role: 'assistant',
          content: '正在总结',
          sequence: 8,
          root_run_id: 'r',
          phase: 'pending' as const,
          state: 'streaming' as const
        }
      ],
      processes: [
        { ...history.processes[0]!, status: 'succeeded', updated_sequence: 4 },
        {
          process_id: 'tool',
          run_id: 'r',
          root_run_id: 'r',
          sequence: 6,
          ordinal: 0,
          updated_sequence: 7,
          type: 'knowledge',
          status: 'succeeded',
          presentation: { title: '检索资料', summary: '' }
        }
      ]
    }
    await wrapper.setProps({ history: updated })
    expect(
      wrapper
        .findAll('[data-message-id], .process-group')
        .map((item) => item.attributes('data-message-id') || 'process')
    ).toEqual(['u', 'process', 'a-1', 'a-2'])
    expect(wrapper.get('[data-message-id="a-1"] .message-body').element).toBe(body)
    const turn = conversationTurns(updated)[0]!
    expect(finalReady(turn)).toBe(false)
    expect(conversationEntries(turn).map((item) => item.key)).toEqual(['u', 'model', 'a-1', 'a-2'])
    expect(wrapper.text()).not.toContain('执行完成')
    await wrapper.setProps({
      history: {
        ...updated,
        messages: updated.messages.map((message) =>
          message.message_id === 'a-2'
            ? {
                ...message,
                message_version: 2,
                content: '最终答复',
                phase: 'final' as const,
                state: 'completed' as const
              }
            : message
        )
      },
      runStates: { r: 'completed' }
    })
    expect(
      wrapper
        .findAll('[data-message-id], .process-group')
        .map((item) => item.attributes('data-message-id') || 'process')
    ).toEqual(['u', 'process', 'a-1', 'a-2'])
    expect(wrapper.get('[data-message-id="a-1"] .message-body').element).toBe(body)
    expect(
      wrapper
        .findAll('.process-toggle')
        .every((item) => item.attributes('aria-expanded') === 'false')
    ).toBe(true)
    wrapper.unmount()
  })
  it('正文结束不等同Run完成，pending与commentary即使completed也不能收起最终轨迹', () => {
    for (const phase of ['pending', 'commentary'] as const) {
      const turn = {
        id: 'r',
        runIds: ['r'],
        processes: [],
        messages: [
          {
            message_id: 'a',
            message_version: 2,
            role: 'assistant',
            content: '中间说明',
            phase,
            state: 'completed' as const
          }
        ]
      }
      expect(finalReady(turn)).toBe(false)
    }
    expect(
      finalReady({
        id: 'r',
        runIds: ['r'],
        processes: [],
        messages: [
          {
            message_id: 'a',
            message_version: 2,
            role: 'assistant',
            content: '最终答复',
            state: 'completed',
            metadata: { phase: 'final' }
          }
        ]
      })
    ).toBe(true)
  })
  it('说明正文保留原消息节点，同Run过程只显示一组完整轨迹', async () => {
    const history = emptyHistory()
    history.messages = [
      {
        message_id: 'u',
        message_version: 1,
        role: 'user',
        content: '升级效果',
        sequence: 1,
        root_run_id: 'r'
      },
      {
        message_id: 'note',
        message_version: 3,
        role: 'assistant',
        content: '将增强视觉效果。',
        sequence: 3,
        root_run_id: 'r',
        phase: 'commentary',
        state: 'completed'
      },
      {
        message_id: 'final',
        message_version: 1,
        role: 'assistant',
        content: '已保留现有页面。',
        sequence: 7,
        root_run_id: 'r',
        phase: 'final',
        state: 'cancelled'
      }
    ]
    const process = (id: string, sequence: number, type: string) => ({
      process_id: id,
      run_id: 'r',
      root_run_id: 'r',
      sequence,
      ordinal: 0,
      updated_sequence: sequence,
      type,
      status: 'succeeded',
      presentation: { title: id, summary: '' }
    })
    history.processes = [
      process('model-before', 2, 'reasoning'),
      process('model-after', 4, 'reasoning'),
      {
        ...process('登记计划', 5, 'tool'),
        status: 'failed',
        presentation: { title: '登记计划', summary: '参数缺失，本次未执行。' }
      },
      process('检查页面', 6, 'tool')
    ]
    const wrapper = mount(ConversationTimeline, {
      props: {
        history,
        client: {} as Client,
        session: 's',
        locked: false,
        runStates: { r: 'cancelled' },
        artifacts: []
      },
      global: { stubs: { ArtifactCards: true } }
    })
    const message = wrapper.get('[data-message-id="note"] .message-body').element
    const segments = wrapper.findAll('.process-group')
    expect(segments[0]!.get('.process-progress-label').text()).toBe('已停止')
    expect(segments[0]!.get('.process-operation-count').text()).toBe('2 项执行记录')
    expect(segments).toHaveLength(1)
    expect(wrapper.findAll('[data-process-trace-toggle]')).toHaveLength(1)
    expect(wrapper.get('[data-process-trace-toggle]').text()).toContain('2 次模型调用')
    expect(segments[0]!.classes()).toContain('is-execution-start')
    expect(wrapper.get('[data-message-id="note"]').classes()).toContain('is-execution-continuation')
    expect(wrapper.get('[data-message-id="note"]').classes()).toContain('is-execution-end')
    expect(wrapper.find('[data-process-id="model-before"]').exists()).toBe(false)
    expect(wrapper.find('[data-process-id="model-after"]').exists()).toBe(false)
    await wrapper.get('[data-process-trace-toggle]').trigger('click')
    expect(
      wrapper.findAll('[data-process-id]').map((item) => item.attributes('data-process-id'))
    ).toEqual(['model-before', 'model-after', '登记计划', '检查页面'])
    expect(
      wrapper
        .findAll('[data-message-id], .process-group')
        .map((item) => item.attributes('data-message-id') || 'process')
    ).toEqual(['u', 'process', 'note', 'final'])
    await wrapper.get('[data-message-id="note"] .message-reference').trigger('click')
    expect(wrapper.emitted('reference')?.[0]?.[0]).toMatchObject({
      source: { message_id: 'note', message_version: 3 }
    })
    await segments[0]!.get('.process-toggle').trigger('click')
    expect(wrapper.get('[data-message-id="note"] .message-body').element).toBe(message)
    expect(wrapper.get('[data-message-id="note"]').isVisible()).toBe(true)
    expect(wrapper.get('[data-message-id="final"]').isVisible()).toBe(true)
    wrapper.unmount()
  })
  it('默认保留公开摘要和失败说明，空模型步骤在完整轨迹按原身份引用', async () => {
    const history = emptyHistory()
    history.processes = [
      {
        process_id: 'model-1',
        run_id: 'r',
        root_run_id: 'r',
        sequence: 1,
        ordinal: 0,
        updated_sequence: 2,
        type: 'reasoning',
        status: 'succeeded',
        presentation: { title: '模型调用', summary: '' }
      },
      {
        process_id: 'summary-1',
        run_id: 'r',
        root_run_id: 'r',
        sequence: 2,
        ordinal: 1,
        updated_sequence: 2,
        type: 'commentary',
        status: 'succeeded',
        presentation: { title: '工作摘要', summary: '正在准备演示内容。' }
      },
      {
        process_id: 'rejected-1',
        run_id: 'r',
        root_run_id: 'r',
        sequence: 3,
        ordinal: 0,
        updated_sequence: 4,
        type: 'tool',
        status: 'failed',
        presentation: { title: '执行代码', summary: '生成参数存在冲突，本次尚未执行。' }
      }
    ]
    const wrapper = mount(ConversationTimeline, {
      props: {
        history,
        client: {} as Client,
        session: 's',
        locked: false,
        runStates: { r: 'completed' },
        artifacts: []
      },
      global: { stubs: { ArtifactCards: true } }
    })
    expect(
      wrapper.findAll('[data-process-id]').map((item) => item.attributes('data-process-id'))
    ).toEqual([])
    expect(wrapper.text()).toContain('1 项执行记录')
    expect(wrapper.text()).toContain('1 次模型调用')
    expect(wrapper.text()).not.toContain('生成参数存在冲突，本次尚未执行。')
    expect(wrapper.get('.process-progress-label').text()).toBe('正在准备答复')
    await wrapper.get('.process-toggle').trigger('click')
    expect(wrapper.find('[data-process-id="rejected-1"]').exists()).toBe(true)
    expect(wrapper.find('[data-process-id="summary-1"]').exists()).toBe(true)
    expect(wrapper.find('[data-process-id="model-1"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('正在准备演示内容。')
    await wrapper.get('[data-process-trace-toggle]').trigger('click')
    expect(
      wrapper.findAll('[data-process-id]').map((item) => item.attributes('data-process-id'))
    ).toEqual(['model-1', 'summary-1', 'rejected-1'])
    await wrapper.get('[data-process-id="model-1"] .process-reference').trigger('click')
    expect(wrapper.emitted('reference')?.[0]).toEqual([
      {
        label: '模型调用',
        source: {
          kind: 'process',
          source: { kind: 'session' },
          process_id: 'model-1',
          updated_sequence: 2
        }
      }
    ])
    await wrapper.setProps({ session: 'another' })
    expect(wrapper.find('[data-process-id="model-1"]').exists()).toBe(false)
    expect(wrapper.find('[data-process-id="rejected-1"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('生成参数存在冲突，本次尚未执行。')
    wrapper.unmount()
  })

  it('正文版本更新后完成折叠一次，手动展开后不重复折叠', async () => {
    const history = emptyHistory()
    history.messages = [
      {
        message_id: 'a',
        role: 'assistant',
        message_version: 1,
        content: '正在生成的安全前缀',
        root_run_id: 'r',
        phase: 'final',
        state: 'streaming',
        metadata: { content_parts: [{ type: 'text', text: '不可继续显示的旧正文' }] }
      }
    ]
    history.processes = [
      {
        process_id: 'p',
        run_id: 'r',
        root_run_id: 'r',
        sequence: 1,
        ordinal: 0,
        updated_sequence: 1,
        type: 'tool',
        status: 'succeeded',
        presentation: { title: '检查资料', summary: '' }
      }
    ]
    const wrapper = mount(ConversationTimeline, {
      props: {
        history,
        client: {} as Client,
        session: 's',
        targetName: '助手',
        locked: false,
        runStates: { r: 'completed' },
        artifacts: []
      },
      global: { stubs: { ArtifactCards: true } }
    })
    expect(wrapper.get('.process-toggle').attributes('aria-expanded')).toBe('false')
    expect(wrapper.get('[data-output-state="streaming"]').text()).toContain('正在生成回复')
    expect(wrapper.text()).toContain('正在生成的安全前缀')
    expect(wrapper.text()).not.toContain('不可继续显示的旧正文')
    await wrapper.setProps({
      history: {
        ...history,
        messages: [
          { ...history.messages[0]!, content: '最终答复', message_version: 2, state: 'completed' }
        ]
      }
    })
    expect(wrapper.get('.process-toggle').attributes('aria-expanded')).toBe('false')
    expect(wrapper.findAll('article.assistant')).toHaveLength(1)
    expect(wrapper.find('[data-output-state="streaming"]').exists()).toBe(false)
    await wrapper.get('.process-toggle').trigger('click')
    await wrapper.setProps({ runStates: { r: 'completed' } })
    expect(wrapper.get('.process-toggle').attributes('aria-expanded')).toBe('true')
    wrapper.unmount()
  })
  it('失败、取消及未知状态保留正文，提示始终在折叠过程外且不展示内部推理', async () => {
    const history = emptyHistory()
    history.processes = [
      {
        process_id: 'p',
        run_id: 'r',
        root_run_id: 'r',
        sequence: 1,
        ordinal: 0,
        updated_sequence: 1,
        type: 'tool',
        status: 'succeeded',
        presentation: { title: '检查资料', summary: '' }
      }
    ]
    history.messages = [
      {
        message_id: 'a',
        role: 'assistant',
        message_version: 2,
        content: '已生成的安全正文',
        root_run_id: 'r',
        phase: 'final',
        state: 'failed'
      },
      {
        message_id: 'private',
        role: 'reasoning',
        message_version: 1,
        content: '不应显示的内部推理',
        root_run_id: 'r'
      }
    ]
    const wrapper = mount(ConversationTimeline, {
      props: {
        history,
        client: {} as Client,
        session: 's',
        locked: false,
        runStates: { r: 'failed' },
        artifacts: []
      },
      global: { stubs: { ArtifactCards: true } }
    })
    await wrapper.get('.process-toggle').trigger('click')
    for (const state of ['failed', 'cancelled', 'outcome_unknown'] as MessageState[]) {
      await wrapper.setProps({
        history: {
          ...history,
          messages: [{ ...history.messages[0]!, state }, history.messages[1]!]
        },
        runStates: { r: state }
      })
      const status = wrapper.get(`[data-output-state="${state}"]`)
      expect(status.element.closest('.process-group')).toBeNull()
      expect(wrapper.text()).toContain('已生成的安全正文')
      expect(wrapper.text()).not.toContain('不应显示的内部推理')
      expect(wrapper.find('[data-output-state="streaming"]').exists()).toBe(false)
    }
    wrapper.unmount()
  })
  it('子 Agent 的 Pending 关联本轮，折叠后仍可见且不影响其他轮', async () => {
    const history = emptyHistory()
    history.processes = ['root', 'other'].map((run, index) => ({
      process_id: `p-${run}`,
      run_id: `${run}-child`,
      root_run_id: run,
      sequence: index + 1,
      ordinal: 0,
      updated_sequence: index + 1,
      type: 'delegation',
      status: 'running',
      presentation: { title: '协作任务', summary: '' }
    }))
    history.pending_interactions = [
      { execution_id: 'root-child', run_revision: 1, checkpoint_revision: 2 }
    ]
    const wrapper = mount(ConversationTimeline, {
      props: {
        history,
        client: {} as Client,
        session: 's',
        locked: false,
        runStates: { root: 'running', other: 'running' },
        artifacts: []
      },
      global: { stubs: { ArtifactCards: true } }
    })
    const turns = wrapper.findAll('.conversation-turn')
    expect(turns[0]!.text()).toContain('等待你的输入')
    expect(turns[1]!.text()).not.toContain('等待你的输入')
    await turns[0]!.get('.process-toggle').trigger('click')
    expect(turns[0]!.get('.pending-summary').text()).toContain('确认或补充后继续')
    wrapper.unmount()
  })
})

describe('历史轮次计划恢复', () => {
  it('计划依据input_id绑定用户轮次，翻页中的其他轮次不串卡', () => {
    const plan: Work = {
      work_id: 'w2',
      input_id: 'input2',
      session_id: 's',
      run_id: 'run2',
      revision: 1,
      status: 'active',
      plan_revision: 1,
      approval_required: false,
      tasks: [],
      results: []
    }
    const history = {
      ...emptyHistory(),
      work_plans: [plan],
      messages: [
        {
          message_id: 'm1',
          input_id: 'input1',
          role: 'user',
          message_version: 1,
          content: '第一轮'
        },
        {
          message_id: 'm2',
          input_id: 'input2',
          role: 'user',
          message_version: 1,
          content: '第二轮'
        }
      ]
    }
    const wrapper = mount(ConversationTimeline, {
      props: {
        history,
        client: {} as Client,
        session: 's',
        locked: false,
        runStates: {},
        artifacts: []
      },
      global: { stubs: { ArtifactCards: true } }
    })
    const turns = wrapper.findAll('.conversation-turn')
    expect(turns[0]!.find('[data-work-id]').exists()).toBe(false)
    expect(turns[1]!.get('[data-work-id]').attributes('data-work-id')).toBe('w2')
    wrapper.unmount()
  })
})

describe('公开回答复制', () => {
  it.each(['普通回答', '聚合回答'])('%s复制完整正文并反馈成功，不包含内部材料', async (kind) => {
    const text = `# ${kind}\n\n${'完整回答内容'.repeat(30)}\n\n[公开依据](#citation-k0123456789abcdef)`
    const writeText = vi.fn().mockResolvedValue(undefined)
    vi.stubGlobal('navigator', { clipboard: { writeText } })
    const success = vi.spyOn(ChatMessage, 'success').mockImplementation(() => () => {})
    const wrapper = timestampTimeline([
      {
        message_version: 1,
        message_id: 'answer',
        role: 'assistant',
        content: text,
        state: 'completed',
        phase: 'final',
        metadata: { internal_material: '不可导出的内部材料' }
      }
    ])
    try {
      await wrapper.get('.message-copy').trigger('click')
      await flushPromises()
      expect(writeText).toHaveBeenCalledWith(text)
      expect(success).toHaveBeenCalledWith('回答已复制。')
    } finally {
      wrapper.unmount()
      vi.unstubAllGlobals()
      success.mockRestore()
    }
  })
  it('剪贴板拒绝后展示可操作失败反馈', async () => {
    vi.stubGlobal('navigator', {
      clipboard: { writeText: vi.fn().mockRejectedValue(new Error('denied')) }
    })
    const error = vi.spyOn(ChatMessage, 'error').mockImplementation(() => () => {})
    const wrapper = timestampTimeline([
      {
        message_version: 1,
        message_id: 'answer',
        role: 'assistant',
        content: '正文',
        state: 'completed'
      }
    ])
    try {
      await wrapper.get('.message-copy').trigger('click')
      await flushPromises()
      expect(error).toHaveBeenCalledWith('复制失败，请选中回答正文手动复制。')
    } finally {
      wrapper.unmount()
      vi.unstubAllGlobals()
      error.mockRestore()
    }
  })
  it('用户消息、执行说明、空回答和生成中的回答不提供复制入口', () => {
    const wrapper = timestampTimeline([
      { message_version: 1, message_id: 'u', role: 'user', content: '用户输入' },
      {
        message_version: 1,
        message_id: 'c',
        role: 'assistant',
        phase: 'commentary',
        content: '执行说明'
      },
      {
        message_version: 1,
        message_id: 'empty',
        role: 'assistant',
        content: '  ',
        state: 'completed'
      },
      {
        message_version: 1,
        message_id: 'streaming',
        role: 'assistant',
        content: '生成中',
        state: 'streaming'
      }
    ])
    try {
      expect(wrapper.findAll('.message-copy')).toHaveLength(0)
    } finally {
      wrapper.unmount()
    }
  })
})
