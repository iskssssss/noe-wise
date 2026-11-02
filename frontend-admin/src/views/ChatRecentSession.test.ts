import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { ElDrawer, ElInput, ElSelect } from 'element-plus'
import ChatPage from './ChatPage.vue'

const mocks = vi.hoisted(() => ({
  query: {} as Record<string, string>,
  auth: {
    identityVersion: 1,
    user: {
      tenant_id: '1',
      sub: 'member-1',
      permissions: [
        'foundation.sessions.read',
        'foundation.sessions.create',
        'foundation.sessions.run',
        'foundation.agents.read',
        'foundation.teams.read'
      ]
    }
  },
  history: vi.fn(),
  detail: vi.fn(),
  observe: vi.fn(),
  submit: vi.fn(),
  confirm: vi.fn()
}))
vi.mock('../stores/auth', () => ({ useAuthStore: () => mocks.auth }))
vi.mock('../api', () => ({
  api: {
    chat: {
      endUsers: async () => [],
      sessions: async () => [{ session_id: 'A' }, { session_id: 'B' }],
      history: mocks.history,
      sessionDetail: mocks.detail,
      observeSession: mocks.observe,
      submit: mocks.submit
    },
    agents: { list: async () => ({ items: [] }) },
    teams: { list: async () => [] }
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
vi.mock('@skillforge/shared/components/MarkdownContent.vue', () => ({
  default: { props: ['content'], template: '<div>{{ content }}</div>' }
}))

const key = 'skillforge.chat-recent-session:1:member-1'
const history = (id: string) => ({
  session_id: id,
  messages: [{ message_id: id, role: 'assistant', content: `${id}历史` }],
  pending_interactions: [],
  has_more: false
})
const detail = (id: string) => ({
  session: { session_id: id },
  end_user_id: '42',
  target_agent_id: '101',
  closed: false
})
let wrapper: ReturnType<typeof mount>
beforeEach(() => {
  sessionStorage.clear()
  window.history.replaceState({ routeState: 'preserved' }, '')
  vi.resetAllMocks()
  mocks.query = {}
  mocks.auth.identityVersion = 1
  mocks.auth.user.tenant_id = '1'
  mocks.auth.user.sub = 'member-1'
  mocks.history.mockImplementation(async (id: string) => history(id))
  mocks.detail.mockImplementation(async (id: string) => detail(id))
  mocks.observe.mockResolvedValue(undefined)
  mocks.confirm.mockResolvedValue(true)
})
afterEach(() => {
  wrapper?.unmount()
  sessionStorage.clear()
  vi.restoreAllMocks()
  document.body.innerHTML = ''
})
async function setup() {
  wrapper = mount(ChatPage, { attachTo: document.body })
  await flushPromises()
}
const selected = () =>
  wrapper.findAllComponents(ElSelect).find((item) => item.props('placeholder') === '历史会话')!

describe('最近会话只读恢复', () => {
  it('重建页面时恢复原会话及身份，不发送消息；新会话清除记录', async () => {
    sessionStorage.setItem(key, 'A')
    await setup()
    expect(mocks.history).toHaveBeenCalledWith('A')
    expect(mocks.detail).toHaveBeenCalledWith('A')
    expect(selected().props('modelValue')).toBe('A')
    expect(wrapper.text()).toContain('A历史')
    expect(mocks.submit).not.toHaveBeenCalled()
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '新会话')!
      .trigger('click')
    await flushPromises()
    expect(sessionStorage.getItem(key)).toBeNull()
    expect(selected().props('modelValue')).toBe('')
  })
  it('手动切换成功后记录新会话，隔离其他成员', async () => {
    sessionStorage.setItem('skillforge.chat-recent-session:1:other', 'A')
    await setup()
    expect(mocks.history).not.toHaveBeenCalled()
    selected().vm.$emit('change', 'B')
    await flushPromises()
    expect(sessionStorage.getItem(key)).toBe('B')
  })
  it('最近会话存储写入失败不阻断手动读取', async () => {
    const original = Storage.prototype.setItem
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(function (
      this: Storage,
      name,
      value
    ) {
      if (name === key) throw new Error('storage unavailable')
      original.call(this, name, value)
    })
    await setup()
    selected().vm.$emit('change', 'B')
    await flushPromises()
    expect(selected().props('modelValue')).toBe('B')
    expect(wrapper.text()).toContain('B历史')
  })
  it.each<Record<string, string>>([{ agent_id: 'agent-1' }, { team_id: 'team-1' }])(
    '显式试用入口 %s 不恢复旧会话',
    async (query) => {
      sessionStorage.setItem(key, 'A')
      mocks.query = query
      await setup()
      expect(mocks.history).not.toHaveBeenCalled()
    }
  )
  it.each<Record<string, string>>([{ agent_id: '101' }, { team_id: 'team-1' }])(
    '当前试用历史条目 %s 刷新后恢复明确选中的会话并保留其他状态',
    async (query) => {
      mocks.query = query
      await setup()
      selected().vm.$emit('change', 'B')
      await flushPromises()
      expect(selected().props('modelValue')).toBe('B')
      expect(window.history.state.routeState).toBe('preserved')
      wrapper.unmount()
      mocks.history.mockClear()
      await setup()
      expect(mocks.history).toHaveBeenCalledWith('B')
      expect(selected().props('modelValue')).toBe('B')
      expect(mocks.submit).not.toHaveBeenCalled()
      await wrapper
        .findAll('button')
        .find((button) => button.text() === '新会话')!
        .trigger('click')
      await flushPromises()
      expect(window.history.state.noeWiseTrialSession).toBeUndefined()
      expect(sessionStorage.getItem(key)).toBeNull()
    }
  )
  it('进入新的同目标试用条目不恢复旧会话', async () => {
    mocks.query = { agent_id: '101' }
    await setup()
    selected().vm.$emit('change', 'B')
    await flushPromises()
    wrapper.unmount()
    window.history.pushState({ routeState: 'new-entry' }, '')
    mocks.history.mockClear()
    await setup()
    expect(mocks.history).not.toHaveBeenCalled()
    expect(selected().props('modelValue')).toBe('')
  })
  it('试用目标或身份不同的条目标记不会恢复', async () => {
    sessionStorage.setItem(key, 'B')
    window.history.replaceState(
      {
        noeWiseTrialSession: {
          actor: '1:other',
          trialContext: JSON.stringify(['101', '']),
          session: 'B'
        }
      },
      ''
    )
    mocks.query = { agent_id: '101' }
    await setup()
    expect(mocks.history).not.toHaveBeenCalled()
    wrapper.unmount()
    window.history.replaceState(
      {
        noeWiseTrialSession: {
          actor: '1:member-1',
          trialContext: JSON.stringify(['other', '']),
          session: 'B'
        }
      },
      ''
    )
    await setup()
    expect(mocks.history).not.toHaveBeenCalled()
  })
  it('旧条目标记与最近确认会话不一致时不恢复', async () => {
    sessionStorage.setItem(key, 'B')
    window.history.replaceState(
      {
        noeWiseTrialSession: {
          actor: '1:member-1',
          trialContext: JSON.stringify(['101', '']),
          session: 'A'
        }
      },
      ''
    )
    mocks.query = { agent_id: '101' }
    await setup()
    expect(mocks.history).not.toHaveBeenCalled()
  })
  it('历史条目不可写不阻断手动读取会话', async () => {
    mocks.query = { agent_id: '101' }
    vi.spyOn(window.history, 'replaceState').mockImplementation(() => {
      throw new Error('unavailable')
    })
    await setup()
    selected().vm.$emit('change', 'B')
    await flushPromises()
    expect(selected().props('modelValue')).toBe('B')
    expect(wrapper.text()).toContain('B历史')
  })
  it('手动历史读取期间身份变化不写入新身份的缓存或试用条目', async () => {
    let resolveHistory!: (value: ReturnType<typeof history>) => void
    mocks.history.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          resolveHistory = resolve
        })
    )
    mocks.query = { agent_id: '101' }
    await setup()
    selected().vm.$emit('change', 'B')
    await flushPromises()
    mocks.auth.identityVersion++
    mocks.auth.user.sub = 'member-2'
    resolveHistory(history('B'))
    await flushPromises()
    expect(wrapper.text()).not.toContain('B历史')
    expect(sessionStorage.getItem(key)).toBeNull()
    expect(sessionStorage.getItem('skillforge.chat-recent-session:1:member-2')).toBeNull()
    expect(window.history.state.noeWiseTrialSession).toBeUndefined()
  })
  it('手动历史读取期间离开页面不写入试用条目', async () => {
    let resolveHistory!: (value: ReturnType<typeof history>) => void
    mocks.history.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          resolveHistory = resolve
        })
    )
    mocks.query = { agent_id: '101' }
    await setup()
    selected().vm.$emit('change', 'B')
    await flushPromises()
    wrapper.unmount()
    resolveHistory(history('B'))
    await flushPromises()
    expect(sessionStorage.getItem(key)).toBeNull()
    expect(window.history.state.noeWiseTrialSession).toBeUndefined()
  })
  it('待核对的会话创建优先，不读取旧会话', async () => {
    sessionStorage.setItem(key, 'A')
    sessionStorage.setItem(
      'skillforge.chat-session-create:1:member-1',
      JSON.stringify({
        requestId: '49ef4062-e3b1-4869-b976-389cdb2d47c4',
        endUserId: '42',
        targetKey: 'agent:101'
      })
    )
    await setup()
    expect(mocks.history).not.toHaveBeenCalled()
  })
  it('新增用户时打开抽屉并取消迟到的会话恢复', async () => {
    let resolveHistory!: (value: ReturnType<typeof history>) => void
    mocks.history.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          resolveHistory = resolve
        })
    )
    sessionStorage.setItem(key, 'A')
    await setup()
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '新增用户')!
      .trigger('click')
    await flushPromises()
    const drawer = wrapper
      .findAllComponents(ElDrawer)
      .find((item) => item.props('title') === '新增终端用户')!
    expect(drawer.props('modelValue')).toBe(true)
    expect(document.body.textContent).toContain('用于识别终端用户')
    resolveHistory(history('A'))
    await flushPromises()
    expect(drawer.props('modelValue')).toBe(true)
    expect(selected().props('modelValue')).toBe('')
    expect(wrapper.text()).not.toContain('A历史')
  })
  it('迟到响应不覆盖用户输入', async () => {
    let resolveHistory!: (value: ReturnType<typeof history>) => void
    mocks.history.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          resolveHistory = resolve
        })
    )
    sessionStorage.setItem(key, 'A')
    await setup()
    const input = wrapper
      .findAllComponents(ElInput)
      .find((item) => item.props('placeholder') === '输入消息')!
    input.vm.$emit('update:modelValue', '尚未发送')
    await flushPromises()
    resolveHistory(history('A'))
    await flushPromises()
    expect(input.props('modelValue')).toBe('尚未发送')
    expect(selected().props('modelValue')).toBe('')
    expect(wrapper.text()).not.toContain('A历史')
  })
  it('迟到响应不覆盖手动选中的其他会话', async () => {
    let resolveHistory!: (value: ReturnType<typeof history>) => void
    mocks.history.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          resolveHistory = resolve
        })
    )
    sessionStorage.setItem(key, 'A')
    await setup()
    selected().vm.$emit('change', 'B')
    await flushPromises()
    resolveHistory(history('A'))
    await flushPromises()
    expect(selected().props('modelValue')).toBe('B')
    expect(wrapper.text()).toContain('B历史')
    expect(sessionStorage.getItem(key)).toBe('B')
  })
  it('身份变化后的迟到读取不展示旧会话', async () => {
    let resolveHistory!: (value: ReturnType<typeof history>) => void
    mocks.history.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          resolveHistory = resolve
        })
    )
    sessionStorage.setItem(key, 'A')
    await setup()
    mocks.auth.identityVersion++
    mocks.auth.user.sub = 'member-2'
    resolveHistory(history('A'))
    await flushPromises()
    expect(selected().props('modelValue')).toBe('')
    expect(wrapper.text()).not.toContain('A历史')
  })
  it('待核对上传优先，不读取最近会话', async () => {
    sessionStorage.setItem(key, 'A')
    sessionStorage.setItem(
      'skillforge.chat-upload:1:member-1',
      JSON.stringify([
        { session: 'B', uploadId: '49ef4062-e3b1-4869-b976-389cdb2d47c4', name: 'x', kind: 'file' }
      ])
    )
    await setup()
    expect(mocks.history).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('上传结果尚未确认')
  })
  it('读取失败后可重试，且不接受不匹配的会话结果', async () => {
    sessionStorage.setItem(key, 'A')
    mocks.history.mockResolvedValueOnce(history('B'))
    await setup()
    expect(wrapper.text()).toContain('上次会话读取失败')
    expect(selected().props('modelValue')).toBe('')
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '重新读取上次会话')!
      .trigger('click')
    await flushPromises()
    expect(selected().props('modelValue')).toBe('A')
    expect(wrapper.text()).not.toContain('上次会话读取失败')
  })
})
