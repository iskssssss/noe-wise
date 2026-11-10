import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { ElButton, ElDrawer, ElSelect } from 'element-plus'
import { EntitySelect } from '@skillforge/shared/admin'
import { clearFeedback } from '@skillforge/shared/feedback'
import CitationDetail from '../../components/CitationDetail.vue'
import SessionsPage from './SessionsPage.vue'

const calls = vi.hoisted(() => ({
  agents: vi.fn(),
  users: vi.fn(),
  sessions: vi.fn(),
  detail: vi.fn(),
  history: vi.fn(),
  get: vi.fn()
}))
vi.mock('../../stores/auth', () => ({ useAuthStore: () => ({ identityVersion: 0 }) }))
vi.mock('../../api', () => ({
  get: calls.get,
  api: {
    agents: { list: calls.agents },
    chat: {
      endUsers: calls.users,
      sessions: calls.sessions,
      sessionDetail: calls.detail,
      history: calls.history
    }
  }
}))
const wrappers: VueWrapper[] = []
function button(wrapper: VueWrapper, label: string) {
  const result = wrapper.findAllComponents(ElButton).find((item) => item.text() === label)
  if (!result) throw Error('未找到按钮：' + label)
  return result
}
async function render() {
  const wrapper = mount(SessionsPage, {
    attachTo: document.body,
    global: { stubs: { RouterLink: { props: ['to'], template: '<a :href="to"><slot /></a>' } } }
  })
  wrappers.push(wrapper)
  await flushPromises()
  return wrapper
}
beforeEach(() => {
  vi.resetAllMocks()
  calls.agents.mockResolvedValue({ items: [{ id: 'agent-a', name: '客服助手' }] })
  calls.users.mockResolvedValue([{ id: 'user-a', display_name: '客户甲' }])
  calls.sessions.mockResolvedValue([
    {
      session_id: 'session-a',
      summary: '资料会话',
      message_count: 2,
      updated_at: '2026-10-30T00:00:00Z'
    }
  ])
  calls.detail.mockResolvedValue({
    session: { session_id: 'session-a', message_count: 2, updated_at: '2026-10-30T00:00:00Z' },
    agent: { name: '客服助手' },
    executions: [],
    tasks: [],
    callbacks: []
  })
  calls.history.mockResolvedValue({
    session_id: 'session-a',
    has_more: true,
    next_before_message_id: 'message:2',
    messages: [
      {
        message_id: 'message:2',
        role: 'user',
        content: '已读取的会话正文',
        created_at: '2026-10-30T00:00:00Z'
      }
    ]
  })
})
afterEach(async () => {
  clearFeedback()
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  await flushPromises()
  document.body.innerHTML = ''
})

describe('会话筛选目录独立恢复', () => {
  it('筛选查询失败显示上次成功条件，保留新条件并可重试', async () => {
    const wrapper = await render()
    const selectors = wrapper.findAllComponents(EntitySelect)
    selectors[0]!.getComponent(ElSelect).vm.$emit('update:modelValue', 'agent-a')
    await flushPromises()
    expect(wrapper.text()).toContain('筛选条件已更改')
    calls.sessions.mockRejectedValueOnce(new Error('会话读取失败'))
    await wrapper.get('form.toolbar').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('智能体：全部')
    expect(wrapper.text()).toContain('仍显示上次结果')
    expect(wrapper.text()).toContain('资料会话')
    expect(selectors[0]!.props('modelValue')).toBe('agent-a')
    await button(wrapper, '重新读取会话').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('智能体：客服助手')
    expect(wrapper.text()).not.toContain('筛选条件已更改')
  })
  it('从会话详情直接打开该会话的执行轨迹', async () => {
    const wrapper = await render()
    await button(wrapper, '资料会话').trigger('click')
    await flushPromises()
    const link = [...document.querySelectorAll<HTMLAnchorElement>('a')].find(
      (item) => item.textContent === '查看执行轨迹'
    )!
    expect(link.getAttribute('href')).toBe('/admin/chat/sessions/session-a/trace')
    expect(calls.detail).toHaveBeenCalledTimes(1)
    expect(calls.history).toHaveBeenCalledTimes(1)
  })
  it.each(['agents', 'users'] as const)(
    '%s失败时成功目录仍可选，重读只请求失败项并保筛选和已开详情',
    async (failed) => {
      calls[failed].mockRejectedValueOnce(
        new Error(failed === 'agents' ? '403：智能体目录未获授权' : '终端用户目录暂不可用')
      )
      const wrapper = await render()
      const selectors = wrapper.findAllComponents(EntitySelect)
      const successfulIndex = failed === 'agents' ? 1 : 0
      expect(selectors[successfulIndex]!.props('options')).toHaveLength(1)
      selectors[successfulIndex]!.getComponent(ElSelect).vm.$emit(
        'update:modelValue',
        failed === 'agents' ? 'user-a' : 'agent-a'
      )
      await flushPromises()
      await button(wrapper, '资料会话').trigger('click')
      await flushPromises()
      expect(wrapper.findComponent(ElDrawer).props('modelValue')).toBe(true)
      expect(document.body.textContent).toContain('已读取的会话正文')
      await button(
        wrapper,
        failed === 'agents' ? '重新读取智能体选项' : '重新读取终端用户选项'
      ).trigger('click')
      await flushPromises()
      expect(calls[failed]).toHaveBeenCalledTimes(2)
      expect(calls[failed === 'agents' ? 'users' : 'agents']).toHaveBeenCalledTimes(1)
      expect(calls.sessions).toHaveBeenCalledTimes(1)
      expect(calls.detail).toHaveBeenCalledTimes(1)
      expect(calls.history).toHaveBeenCalledTimes(1)
      expect(selectors[successfulIndex]!.props('modelValue')).toBe(
        failed === 'agents' ? 'user-a' : 'agent-a'
      )
      expect(wrapper.text()).toContain('资料会话')
      expect(document.body.textContent).toContain('已读取的会话正文')
      expect(wrapper.findComponent(ElDrawer).props('modelValue')).toBe(true)
      selectors[0]!.getComponent(ElSelect).vm.$emit('update:modelValue', 'agent-a')
      selectors[1]!.getComponent(ElSelect).vm.$emit('update:modelValue', 'user-a')
      await wrapper.get('form.toolbar').trigger('submit')
      await flushPromises()
      expect(calls.sessions).toHaveBeenLastCalledWith({ agentId: 'agent-a', endUserId: 'user-a' })
      await button(wrapper, '加载较早消息').trigger('click')
      await flushPromises()
      expect(calls.history).toHaveBeenLastCalledWith('session-a', 'message:2')
      expect(calls.detail).toHaveBeenCalledTimes(1)
    }
  )
  it('双目录错误只有一条顶部反馈，查询会话不会代替目录恢复', async () => {
    calls.agents.mockRejectedValueOnce(new Error('智能体目录拒绝'))
    calls.users.mockRejectedValueOnce(new Error('终端用户目录拒绝'))
    const wrapper = await render()
    const notices = document.querySelectorAll('.el-message')
    expect(notices).toHaveLength(1)
    expect(notices[0]!.textContent).toContain('智能体目录拒绝')
    expect(notices[0]!.textContent).toContain('终端用户目录拒绝')
    expect(button(wrapper, '重新读取智能体选项').exists()).toBe(true)
    expect(button(wrapper, '重新读取终端用户选项').exists()).toBe(true)
    await wrapper.get('form.toolbar').trigger('submit')
    await flushPromises()
    expect(calls.agents).toHaveBeenCalledTimes(1)
    expect(calls.users).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('资料会话')
  })
  it('会话主列表失败仍取得两个目录，主列表独立重读保留目录选中身份', async () => {
    calls.sessions.mockRejectedValueOnce(new Error('会话列表暂不可用'))
    const wrapper = await render()
    expect(wrapper.text()).toContain('会话列表尚未读取，请重新读取')
    expect(wrapper.text()).not.toContain('没有符合查询条件的会话')
    const selectors = wrapper.findAllComponents(EntitySelect)
    expect(selectors.map((selector) => selector.props('options')?.length)).toEqual([1, 1])
    selectors[1]!.getComponent(ElSelect).vm.$emit('update:modelValue', 'user-a')
    await button(wrapper, '重新读取会话').trigger('click')
    await flushPromises()
    expect(calls.sessions).toHaveBeenLastCalledWith({ agentId: undefined, endUserId: 'user-a' })
    expect(calls.agents).toHaveBeenCalledTimes(1)
    expect(calls.users).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('资料会话')
  })
  it('已打开详情的较早消息失败只显示一个同源错误，不清已读正文', async () => {
    const wrapper = await render()
    await button(wrapper, '资料会话').trigger('click')
    await flushPromises()
    calls.history.mockRejectedValueOnce(new Error('较早消息暂不可用'))
    await button(wrapper, '加载较早消息').trigger('click')
    await flushPromises()
    expect(document.querySelectorAll('.el-message')).toHaveLength(1)
    expect(document.querySelector('.el-message')?.textContent).toContain('较早消息暂不可用')
    expect(document.body.textContent).toContain('已读取的会话正文')
    expect(wrapper.findComponent(ElDrawer).props('modelValue')).toBe(true)
  })
  it('消息读取失败仍能查看会话详情，重读消息不重新请求详情', async () => {
    calls.history.mockRejectedValueOnce(new Error('消息暂不可用'))
    const wrapper = await render()
    await button(wrapper, '资料会话').trigger('click')
    await flushPromises()
    expect(wrapper.findComponent(ElDrawer).props('modelValue')).toBe(true)
    expect(document.body.textContent).toContain('客服助手')
    expect(document.body.textContent).not.toContain('本会话暂无可见消息')
    expect(document.querySelector('.el-message')?.textContent).toContain('消息暂不可用')
    await button(wrapper, '重新读取消息').trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('已读取的会话正文')
    expect(calls.detail).toHaveBeenCalledTimes(1)
    expect(calls.history).toHaveBeenCalledTimes(2)
  })
  it('详情和消息并行读取，消息尚在读取时可先查看详情', async () => {
    let finishHistory!: (value: unknown) => void
    let finishDetail!: (value: unknown) => void
    calls.history.mockReturnValueOnce(
      new Promise((resolve) => {
        finishHistory = resolve
      })
    )
    calls.detail.mockReturnValueOnce(
      new Promise((resolve) => {
        finishDetail = resolve
      })
    )
    const wrapper = await render()
    await button(wrapper, '资料会话').trigger('click')
    await flushPromises()
    expect(calls.detail).toHaveBeenCalledWith('session-a')
    expect(calls.history).toHaveBeenCalledWith('session-a')
    finishDetail({
      session: { session_id: 'session-a', updated_at: '2026-10-30T00:00:00Z' },
      agent: { name: '客服助手' },
      executions: [],
      tasks: [],
      callbacks: []
    })
    await flushPromises()
    expect(wrapper.findComponent(ElDrawer).props('modelValue')).toBe(true)
    expect(document.body.textContent).toContain('正在读取消息')
    finishHistory({ session_id: 'session-a', has_more: false, messages: [] })
    await flushPromises()
    expect(document.body.textContent).toContain('本会话暂无可见消息')
  })
})

describe('会话详情知识依据', () => {
  beforeEach(() => {
    calls.sessions.mockResolvedValue([
      { session_id: 'session-a', summary: '资料会话' },
      { session_id: 'session-b', summary: '另一会话' }
    ])
    calls.detail.mockImplementation(async (id: string) => ({
      session: { session_id: id },
      agent: { name: '客服助手' },
      executions: [],
      tasks: [],
      callbacks: []
    }))
    calls.history.mockImplementation(async (id: string) => ({
      session_id: id,
      has_more: false,
      messages: [
        {
          message_id: `reply-${id}`,
          role: 'assistant',
          content: '回答正文',
          metadata: {
            citations: [
              {
                ordinal: 1,
                item_id: 'item-1',
                source_id: 'source-1',
                source_version: 'version-1',
                citation_id: 'k1234567890abcdef',
                label: '知识依据 1'
              }
            ]
          }
        }
      ]
    }))
    calls.get.mockResolvedValue({
      label: '知识依据 1',
      source_version: 'version-1',
      text: '原始知识片段'
    })
  })

  it('按所选消息读取来源，关闭外层详情清除片段且重开后重新读取', async () => {
    const wrapper = await render()
    await button(wrapper, '资料会话').trigger('click')
    await flushPromises()
    await button(wrapper, '知识依据 1').trigger('click')
    await flushPromises()
    const sourceUrl =
      '/chat/citations/content?session_id=session-a&message_id=reply-session-a&citation_id=k1234567890abcdef'
    expect(calls.get).toHaveBeenCalledExactlyOnceWith(sourceUrl, {
      signal: expect.any(AbortSignal),
      silent: true
    })
    expect(document.body.textContent).toContain('原始知识片段')
    expect(document.body.textContent).toContain('来源版本：version-1')
    wrapper.getComponent(ElDrawer).vm.$emit('update:modelValue', false)
    await flushPromises()
    expect(wrapper.findComponent(CitationDetail).exists()).toBe(false)
    expect(document.body.textContent).not.toContain('原始知识片段')
    expect((calls.get.mock.calls[0]![1] as { signal: AbortSignal }).signal.aborted).toBe(true)
    await button(wrapper, '资料会话').trigger('click')
    await flushPromises()
    expect(wrapper.findComponent(CitationDetail).exists()).toBe(false)
    expect(calls.get).toHaveBeenCalledTimes(1)
    await button(wrapper, '知识依据 1').trigger('click')
    await flushPromises()
    expect(calls.get).toHaveBeenCalledTimes(2)
    expect(calls.get.mock.calls[1]![0]).toBe(sourceUrl)
    expect(document.body.textContent).toContain('原始知识片段')
  })

  it('读取另一会话前清除已选来源，不等新详情读取完成', async () => {
    const wrapper = await render()
    await button(wrapper, '资料会话').trigger('click')
    await flushPromises()
    await button(wrapper, '知识依据 1').trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('原始知识片段')
    let finishDetail!: (value: unknown) => void
    calls.detail.mockReturnValueOnce(
      new Promise((resolve) => {
        finishDetail = resolve
      })
    )
    await button(wrapper, '另一会话').trigger('click')
    await flushPromises()
    expect(calls.detail).toHaveBeenLastCalledWith('session-b')
    expect(wrapper.findComponent(CitationDetail).exists()).toBe(false)
    expect(document.body.textContent).not.toContain('原始知识片段')
    expect((calls.get.mock.calls[0]![1] as { signal: AbortSignal }).signal.aborted).toBe(true)
    finishDetail({
      session: { session_id: 'session-b' },
      agent: { name: '客服助手' },
      executions: [],
      tasks: [],
      callbacks: []
    })
    await flushPromises()
    await button(wrapper, '知识依据 1').trigger('click')
    await flushPromises()
    expect(calls.get.mock.calls[1]![0]).toBe(
      '/chat/citations/content?session_id=session-b&message_id=reply-session-b&citation_id=k1234567890abcdef'
    )
  })
})
