import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { ElSelect } from 'element-plus'
import ChatPage from './ChatPage.vue'

const mocks = vi.hoisted(() => ({
  query: { agent_id: 'agent-1' } as Record<string, string>,
  permissions: [
    'foundation.sessions.read',
    'foundation.sessions.create',
    'foundation.sessions.run',
    'foundation.agents.read',
    'foundation.teams.read'
  ],
  users: vi.fn(),
  agents: vi.fn(),
  teams: vi.fn(),
  sessions: vi.fn(),
  history: vi.fn(),
  detail: vi.fn(),
  observe: vi.fn(),
  submit: vi.fn(),
  close: vi.fn(),
  confirm: vi.fn()
}))
vi.mock('../stores/auth', () => ({
  useAuthStore: () => ({
    identityVersion: 1,
    user: { tenant_id: '1', sub: 'member-1', permissions: mocks.permissions }
  })
}))
vi.mock('../api', () => ({
  api: {
    chat: {
      endUsers: mocks.users,
      sessions: mocks.sessions,
      history: mocks.history,
      sessionDetail: mocks.detail,
      observeSession: mocks.observe,
      submit: mocks.submit,
      closeSession: mocks.close
    },
    agents: { list: mocks.agents },
    teams: { list: mocks.teams }
  }
}))
vi.mock('@skillforge/shared/feedback', async (original) => ({
  ...(await original<typeof import('@skillforge/shared/feedback')>()),
  confirm: mocks.confirm
}))
vi.mock('vue-router', () => ({
  useRoute: () => ({ query: mocks.query }),
  onBeforeRouteLeave: vi.fn(),
  onBeforeRouteUpdate: vi.fn()
}))
vi.mock('../components/TeamDebugPanel.vue', () => ({ default: { template: '<div />' } }))
let wrapper: ReturnType<typeof mount>
const select = (placeholder: string) =>
  wrapper.findAllComponents(ElSelect).find((item) => item.props('placeholder') === placeholder)!
beforeEach(() => {
  sessionStorage.clear()
  vi.clearAllMocks()
  mocks.query = { agent_id: 'agent-1' }
  mocks.permissions.splice(
    0,
    mocks.permissions.length,
    'foundation.sessions.read',
    'foundation.sessions.create',
    'foundation.sessions.run',
    'foundation.agents.read',
    'foundation.teams.read'
  )
  mocks.users.mockResolvedValue([{ id: 'user-1', display_name: '用户' }])
  mocks.agents.mockResolvedValue({
    items: [
      { id: 'agent-1', name: '一号', status: 'published' },
      { id: 'agent-2', name: '二号', status: 'published' }
    ]
  })
  mocks.teams.mockResolvedValue([{ id: 'team-1', name: '团队', status: 'published' }])
  mocks.sessions.mockResolvedValue([{ session_id: 'session-1' }])
  mocks.history.mockResolvedValue({
    session_id: 'session-1',
    messages: [],
    pending_interactions: []
  })
  mocks.detail.mockResolvedValue({
    session: { session_id: 'session-1', team_id: 'team-1' },
    end_user_id: 'user-1',
    closed: false
  })
  mocks.observe.mockResolvedValue(undefined)
  mocks.close.mockResolvedValue({ session_id: 'session-1', status: 'closed' })
  mocks.confirm.mockResolvedValue(true)
})
afterEach(() => {
  wrapper?.unmount()
  document.body.innerHTML = ''
})
async function setup() {
  wrapper = mount(ChatPage, { attachTo: document.body })
  await flushPromises()
}
describe('智能体试用目录恢复与权限', () => {
  it('历史读取失败不丢失已加载的用户和预选智能体，单独重试历史', async () => {
    mocks.sessions.mockRejectedValueOnce(new Error('连接中断'))
    await setup()
    expect(select('选择智能体或团队').props('modelValue')).toBe('agent:agent-1')
    expect(wrapper.text()).toContain('已预选智能体')
    expect(select('终端用户').findAllComponents({ name: 'ElOption' }).length).toBeGreaterThan(0)
    const retry = [...document.querySelectorAll('button')].find((button) =>
      button.textContent?.includes('重新读取历史会话')
    )!
    retry.click()
    await flushPromises()
    expect(mocks.sessions).toHaveBeenCalledTimes(2)
    expect(mocks.agents).toHaveBeenCalledTimes(1)
    expect(select('选择智能体或团队').props('modelValue')).toBe('agent:agent-1')
  })
  it('四项读取同时失败只产生一条顶部消息，手动关闭后恢复入口仍可用', async () => {
    mocks.users.mockRejectedValueOnce(new Error('用户失败'))
    mocks.agents.mockRejectedValueOnce(new Error('智能体失败'))
    mocks.teams.mockRejectedValueOnce(new Error('团队失败'))
    mocks.sessions.mockRejectedValueOnce(new Error('历史失败'))
    await setup()
    const messages = document.querySelectorAll('.el-message')
    expect(messages).toHaveLength(1)
    expect(messages[0]!.textContent).toContain('用户失败')
    expect(messages[0]!.textContent).toContain('历史失败')
    ;(messages[0]!.querySelector('.el-message__closeBtn') as HTMLElement).click()
    await flushPromises()
    expect(wrapper.findAll('[aria-label="读取恢复"] button')).toHaveLength(4)
    await wrapper.findAll('[aria-label="读取恢复"] button')[0]!.trigger('click')
    await flushPromises()
    expect(mocks.users).toHaveBeenCalledTimes(2)
    expect(wrapper.findAll('[aria-label="读取恢复"] button')).toHaveLength(3)
  })
  it('无团队读取权限不请求团队目录，已有团队会话仍可恢复绑定', async () => {
    mocks.permissions.splice(mocks.permissions.indexOf('foundation.teams.read'), 1)
    await setup()
    expect(mocks.teams).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('没有团队读取权限')
    select('历史会话').vm.$emit('change', 'session-1')
    await flushPromises()
    expect(select('选择智能体或团队').props('modelValue')).toBe('team:team-1')
    expect(wrapper.text()).not.toContain('已预选智能体')
  })
  it('改选对象后不再显示预选提示，重读目录不覆盖当前选择', async () => {
    await setup()
    select('选择智能体或团队').vm.$emit('update:modelValue', 'agent:agent-2')
    await flushPromises()
    expect(wrapper.text()).not.toContain('已预选智能体')
  })
  it('智能体目录迟到时不覆盖已加载会话的目标', async () => {
    let resolveAgents!: (value: {
      items: Array<{ id: string; name: string; status: string }>
    }) => void
    mocks.agents.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          resolveAgents = resolve
        })
    )
    await setup()
    select('历史会话').vm.$emit('change', 'session-1')
    await flushPromises()
    resolveAgents({ items: [{ id: 'agent-1', name: '一号', status: 'published' }] })
    await flushPromises()
    expect(select('选择智能体或团队').props('modelValue')).toBe('team:team-1')
    expect(wrapper.text()).not.toContain('已预选智能体')
  })
  it('仅有关闭权限时仍可关闭已有会话', async () => {
    mocks.permissions.splice(mocks.permissions.indexOf('foundation.sessions.run'), 1)
    mocks.permissions.push('foundation.sessions.cancel')
    await setup()
    select('历史会话').vm.$emit('change', 'session-1')
    await flushPromises()
    const close = wrapper.findAll('button').find((item) => item.text() === '关闭会话')!
    expect(close.attributes('disabled')).toBeUndefined()
    await close.trigger('click')
    await flushPromises()
    expect(mocks.close).toHaveBeenCalledExactlyOnceWith('session-1')
    expect(wrapper.text()).toContain('此会话已关闭')
    expect(wrapper.text()).not.toContain('关闭结果尚待核对')
  })
  it('筛选历史请求在途时锁定身份和会话切换，结果仍属于原条件', async () => {
    let resolveSessions!: (value: Array<{ session_id: string }>) => void
    await setup()
    select('终端用户').vm.$emit('update:modelValue', 'user-1')
    mocks.sessions.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          resolveSessions = resolve
        })
    )
    select('历史范围').vm.$emit('change', 'selected')
    await flushPromises()
    expect(select('终端用户').props('disabled')).toBe(true)
    expect(select('选择智能体或团队').props('disabled')).toBe(true)
    expect(select('历史会话').props('disabled')).toBe(true)
    expect(
      wrapper
        .findAll('button')
        .find((item) => item.text() === '新会话')!
        .attributes('disabled')
    ).toBeDefined()
    resolveSessions([{ session_id: 'filtered' }])
    await flushPromises()
    expect(mocks.sessions).toHaveBeenLastCalledWith({
      endUserId: 'user-1',
      agentId: 'agent-1',
      teamId: undefined
    })
    expect(select('选择智能体或团队').props('modelValue')).toBe('agent:agent-1')
  })
  it('用户目录读取未结束时不能新增，避免迟到目录覆盖创建结果', async () => {
    let resolveUsers!: (value: Array<{ id: string; display_name: string }>) => void
    mocks.users.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          resolveUsers = resolve
        })
    )
    await setup()
    const create = wrapper.findAll('button').find((item) => item.text() === '新增用户')!
    expect(create.attributes('disabled')).toBeDefined()
    resolveUsers([{ id: 'user-1', display_name: '用户' }])
    await flushPromises()
    expect(create.attributes('disabled')).toBeUndefined()
  })
  it('筛选历史未结束时不能新增用户，用户选择不被后来的创建结果更改', async () => {
    let resolveSessions!: (value: Array<{ session_id: string }>) => void
    await setup()
    select('终端用户').vm.$emit('update:modelValue', 'user-1')
    mocks.sessions.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          resolveSessions = resolve
        })
    )
    select('历史范围').vm.$emit('change', 'selected')
    await flushPromises()
    const create = wrapper.findAll('button').find((item) => item.text() === '新增用户')!
    expect(create.attributes('disabled')).toBeDefined()
    resolveSessions([{ session_id: 'filtered' }])
    await flushPromises()
    expect(create.attributes('disabled')).toBeUndefined()
    expect(select('终端用户').props('modelValue')).toBe('user-1')
  })
  it('缺少创建或执行权限时禁用相应动作并说明', async () => {
    mocks.permissions.splice(mocks.permissions.indexOf('foundation.sessions.create'), 1)
    await setup()
    expect(wrapper.text()).toContain('没有会话创建权限')
    expect(
      wrapper
        .findAll('button')
        .find((item) => item.text() === '新增用户')!
        .attributes('disabled')
    ).toBeDefined()
    expect(
      wrapper
        .findAll('button')
        .find((item) => item.text() === '发送')!
        .attributes('disabled')
    ).toBeDefined()
    wrapper.unmount()
    mocks.permissions.splice(mocks.permissions.indexOf('foundation.sessions.run'), 1)
    await setup()
    expect(wrapper.text()).toContain('没有会话执行权限')
    expect(
      wrapper
        .findAll('button')
        .find((item) => item.text() === '发送')!
        .attributes('disabled')
    ).toBeDefined()
    expect(mocks.submit).not.toHaveBeenCalled()
  })
})

describe('团队试用入口', () => {
  it('只预选目录中已发布团队，终端用户保持空且不创建或发送', async () => {
    mocks.query = { team_id: 'team-1' }
    await setup()
    expect(select('选择智能体或团队').props('modelValue')).toBe('team:team-1')
    expect(select('终端用户').props('modelValue')).toBe('')
    expect(wrapper.text()).toContain('已预选团队')
    expect(mocks.submit).not.toHaveBeenCalled()
  })
  it('未发布或没有团队读取权限不预选', async () => {
    mocks.query = { team_id: 'team-1' }
    mocks.teams.mockResolvedValueOnce([{ id: 'team-1', name: '团队', status: 'draft' }])
    await setup()
    expect(select('选择智能体或团队').props('modelValue')).toBe('')
    expect(wrapper.text()).toContain('团队当前不可试用')
    wrapper.unmount()
    mocks.permissions.splice(mocks.permissions.indexOf('foundation.teams.read'), 1)
    await setup()
    expect(select('选择智能体或团队').props('modelValue')).toBe('')
    expect(mocks.teams).toHaveBeenCalledTimes(1)
  })
})
