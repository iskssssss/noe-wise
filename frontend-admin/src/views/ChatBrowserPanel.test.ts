import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { ElSelect } from 'element-plus'
import ChatPage from './ChatPage.vue'
vi.mock('../stores/auth', () => ({
  useAuthStore: () => ({
    user: {
      permissions: [
        'foundation.sessions.read',
        'foundation.sessions.create',
        'foundation.sessions.cancel',
        'foundation.sessions.run',
        'foundation.agents.read',
        'foundation.teams.read'
      ]
    }
  })
}))

const mocks = vi.hoisted(() => ({
  devices: vi.fn(),
  tasks: vi.fn(),
  pair: vi.fn(),
  cancel: vi.fn(),
  bridge: vi.fn(),
  observe: vi.fn()
}))
vi.mock('../api', () => ({
  api: {
    chat: {
      endUsers: async () => [],
      sessions: async () => [{ session_id: 'A' }, { session_id: 'B' }],
      sessionDetail: async (id: string) => ({
        session: { session_id: id },
        end_user_id: '42',
        target_agent_id: '101',
        closed: false
      }),
      history: async () => ({ messages: [], pending_interactions: [], has_more: false }),
      observeSession: mocks.observe,
      browserDevices: mocks.devices,
      browserTasks: mocks.tasks,
      browserPair: mocks.pair,
      browserCancel: mocks.cancel
    },
    agents: { list: async () => ({ items: [] }) },
    teams: { list: async () => [] }
  }
}))
vi.mock('@skillforge/shared/utils/browserConnection', async (original) => ({
  ...(await original<typeof import('@skillforge/shared/utils/browserConnection')>()),
  browserBridge: mocks.bridge
}))
vi.mock('vue-router', () => ({
  useRoute: () => ({ query: {} }),
  onBeforeRouteLeave: vi.fn(),
  onBeforeRouteUpdate: vi.fn()
}))
vi.mock('../components/TeamDebugPanel.vue', () => ({ default: { template: '<div />' } }))
vi.mock('../components/SessionMediaImage.vue', () => ({
  default: {
    props: ['session', 'media'],
    template: '<div class="receipt-image" :data-session="session" :data-media="media" />'
  }
}))
vi.mock('@skillforge/shared/components/MarkdownContent.vue', () => ({
  default: { template: '<div />' }
}))

let wrapper: ReturnType<typeof mount> | undefined
const receipt = (task: string) => ({
  task,
  invocation: `invocation:${task}`,
  operation: 'screenshot',
  status: 'unknown',
  session: 'A',
  run: 'run:A',
  created_at: '2026-10-20T17:08:09Z'
})
beforeEach(() => {
  vi.resetAllMocks()
  sessionStorage.clear()
  mocks.devices.mockResolvedValue({ items: [], installation_url: '' })
  mocks.tasks.mockResolvedValue([
    {
      receipt: receipt('original-task'),
      content: [{ session_id: 'image-origin-session', media_id: 'image' }]
    }
  ])
  mocks.observe.mockResolvedValue(undefined)
  mocks.pair.mockResolvedValue({ pairing_url: 'ws://127.0.0.1:18321/pair#private-secret' })
  mocks.bridge.mockResolvedValue({ ok: true })
})
afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  document.body.innerHTML = ''
  sessionStorage.clear()
  vi.restoreAllMocks()
})
async function selectSession(id: string) {
  wrapper!
    .findAllComponents(ElSelect)
    .find((field) => field.props('placeholder') === '历史会话')!
    .vm.$emit('change', id)
  await flushPromises()
}
async function setup() {
  wrapper = mount(ChatPage, { attachTo: document.body })
  await flushPromises()
  await selectSession('A')
  await wrapper
    .findAll('[role="tab"]')
    .find((tab) => tab.text() === '浏览器设备')!
    .trigger('click')
  await flushPromises()
}
function button(label: string) {
  return wrapper!.findAll('button').find((item) => item.text() === label)!
}

it('打开设备页自动GET设备及任务，截图使用服务端返回的准确媒体会话', async () => {
  await setup()
  expect(mocks.devices).toHaveBeenCalledWith({ session_id: 'A' }, expect.any(AbortSignal))
  expect(mocks.tasks).toHaveBeenCalledWith({ session_id: 'A' }, expect.any(AbortSignal))
  expect(wrapper!.text()).toContain('original-task')
  expect(wrapper!.text()).toContain('unknown')
  expect(wrapper!.text()).toContain('回执状态：unknown')
  expect(wrapper!.text()).toContain('截图回执时间')
  expect(wrapper!.get('.browser-task time').text()).toBe('2026-10-21 01:08:09')
  expect(wrapper!.get('.browser-task time').attributes('datetime')).toBe('2026-10-20T17:08:09Z')
  expect(wrapper!.get('.receipt-image').attributes('data-session')).toBe('image-origin-session')
  expect(mocks.pair).not.toHaveBeenCalled()
})

it('历史start与stop各自保留原回执状态，不从绑定身份推断当前任务状态', async () => {
  const binding = {
    session_id: 'upstream-session',
    browser_instance_id: 'instance',
    agent_window_id: 7
  }
  mocks.tasks.mockResolvedValue([
    {
      receipt: {
        ...receipt('original-task'),
        invocation: 'start-invocation',
        operation: 'start',
        status: 'active',
        binding
      },
      content: []
    },
    {
      receipt: {
        ...receipt('original-task'),
        invocation: 'stop-invocation',
        operation: 'stop',
        status: 'succeeded',
        binding
      },
      content: []
    }
  ])
  await setup()
  const entries = wrapper!.findAll('.browser-task')
  expect(entries).toHaveLength(2)
  expect(entries[0]!.text()).toContain('回执状态：active')
  expect(entries[1]!.text()).toContain('回执状态：succeeded')
  expect(wrapper!.text()).not.toContain('当前任务绑定')
})

it('配对只交给共用扩展桥接，结果未知时刷新和重挂载都不重发', async () => {
  mocks.bridge.mockImplementation(async (operation: string) =>
    operation === 'probe' ? { ok: true } : { ok: false, code: 'unconfirmed' }
  )
  const opened = vi.spyOn(window, 'open').mockImplementation(() => null)
  await setup()
  await button('配对当前浏览器').trigger('click')
  await flushPromises()
  expect(mocks.pair).toHaveBeenCalledExactlyOnceWith({ session_id: 'A' })
  expect(mocks.bridge).toHaveBeenLastCalledWith(
    'pair',
    {
      pairing_url: 'ws://127.0.0.1:18321/pair#private-secret',
      identity_scope: JSON.stringify({ session_id: 'A' })
    },
    expect.any(AbortSignal)
  )
  expect(opened).not.toHaveBeenCalled()
  expect(wrapper!.text()).not.toContain('private-secret')
  expect(button('配对当前浏览器').attributes('disabled')).toBeDefined()
  await button('刷新设备与任务').trigger('click')
  await flushPromises()
  wrapper!.unmount()
  wrapper = undefined
  await setup()
  expect(button('配对当前浏览器').attributes('disabled')).toBeDefined()
  expect(mocks.pair).toHaveBeenCalledTimes(1)
})

it('切换会话中止旧读取，迟到旧任务不能污染当前会话', async () => {
  let resolveOld!: (value: unknown[]) => void
  mocks.tasks
    .mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          resolveOld = resolve
        })
    )
    .mockResolvedValueOnce([{ receipt: receipt('current-task'), content: [] }])
  await setup()
  const oldSignal = mocks.tasks.mock.calls[0]![1] as AbortSignal
  await selectSession('B')
  expect(oldSignal.aborted).toBe(true)
  expect(mocks.tasks).toHaveBeenLastCalledWith({ session_id: 'B' }, expect.any(AbortSignal))
  resolveOld([{ receipt: receipt('late-task'), content: [] }])
  await flushPromises()
  expect(wrapper!.text()).toContain('current-task')
  expect(wrapper!.text()).not.toContain('late-task')
})

it('取消准确原invocation后刷新真实回执，不把未确认清理改成完成', async () => {
  const cancelled = {
    ...receipt('original-task'),
    status: 'cleanup_failed',
    result: { cleanup_complete: false, error: 'browser_cleanup_failed' }
  }
  mocks.cancel.mockResolvedValue(cancelled)
  await setup()
  mocks.tasks.mockResolvedValue([{ receipt: cancelled, content: [] }])
  await button('请求取消').trigger('click')
  await flushPromises()
  expect(mocks.cancel).toHaveBeenCalledExactlyOnceWith(
    { session_id: 'A' },
    'invocation:original-task'
  )
  expect(mocks.tasks).toHaveBeenCalledTimes(2)
  expect(wrapper!.text()).toContain('尚未确认清理完成')
  expect(wrapper!.text()).toContain('browser_cleanup_failed')
})
