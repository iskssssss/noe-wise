import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { reactive } from 'vue'
import { createMemoryHistory, createRouter, RouterView, type Router } from 'vue-router'
import appRouter from '../../router'
import SessionDetailPage from './SessionDetailPage.vue'
import ObservationTree from '@skillforge/shared/observation/ObservationTree.vue'
import { node } from '@skillforge/shared/observation/observation.test-fixture'
import { clearFeedback } from '@skillforge/shared/feedback'

const mocks = vi.hoisted(() => ({
  auth: undefined as unknown,
  detail: vi.fn(),
  rounds: vi.fn(),
  messages: vi.fn(),
  usage: vi.fn(),
  trajectory: vi.fn(),
  nodes: vi.fn(),
  content: vi.fn()
}))
vi.mock('../../stores/auth', () => ({ useAuthStore: () => mocks.auth }))
vi.mock('../../api', async (original) => ({
  ...(await original<typeof import('../../api')>()),
  getSessionId: () => 'actor-session',
  foundationRequest: vi.fn(),
  api: {
    sessions: {
      detail: mocks.detail,
      rounds: mocks.rounds,
      messages: mocks.messages,
      usage: mocks.usage
    },
    trajectories: {
      detail: mocks.trajectory,
      nodes: mocks.nodes,
      content: mocks.content
    }
  }
}))
vi.mock('@skillforge/shared/admin', async (original) => ({
  ...(await original<typeof import('@skillforge/shared/admin')>()),
  AdminLayout: { template: '<section><slot /></section>' }
}))
vi.mock('@skillforge/shared/foundation/navigation', async (original) => {
  const { ref } = await import('vue')
  return {
    ...(await original<typeof import('@skillforge/shared/foundation/navigation')>()),
    useFoundationNavigation: () => ({ menus: ref([]), error: ref(''), reload: vi.fn() })
  }
})
vi.mock('@skillforge/shared/foundation/appearance', async () => {
  const { ref } = await import('vue')
  return {
    appearanceTheme: () => 'light',
    useFoundationAppearance: () => ({
      personal: ref(null),
      loading: ref(false),
      saving: ref(false),
      error: ref(''),
      reload: vi.fn(),
      savePersonal: vi.fn()
    })
  }
})

let wrapper: VueWrapper
let router: Router
let auth: {
  user: { sub: string; session_id: string; authorization_version: number; permissions: string[] }
  isAuthenticated: boolean
  identityVersion: number
}
const modelNode = {
  ...node('model'),
  title: '模型节点',
  kind: 'LLM' as const,
  input: { ref: 'request-A', availability: 'FULL' as const }
}
beforeEach(() => {
  vi.resetAllMocks()
  auth = reactive({
    user: {
      sub: 'operator',
      session_id: 'actor-session',
      authorization_version: 1,
      permissions: []
    },
    isAuthenticated: true,
    identityVersion: 1
  })
  mocks.auth = auth
  mocks.detail.mockImplementation(async (_tenant, session) => ({
    tenant_name: `机构-${session}`,
    message_count: 2
  }))
  mocks.rounds.mockResolvedValue({
    items: [
      { root_input_id: 'round-A', round_number: '1', run_count: 1 },
      { root_input_id: 'round-B', round_number: '2', run_count: 1 }
    ],
    next_cursor: null
  })
  mocks.messages.mockImplementation(async (_tenant, session) => ({
    items: [{ message_id: session, content: `消息-${session}`, role: 'user' }],
    next_cursor: null
  }))
  mocks.usage.mockResolvedValue({
    attempts: [],
    totals: null,
    coverage: { status: 'COMPLETE' },
    cache: null
  })
  mocks.trajectory.mockResolvedValue({
    snapshot: 'snapshot-A',
    summary: null,
    usage: null,
    traces: []
  })
  mocks.nodes.mockResolvedValue({
    items: [modelNode],
    ancestors: [],
    next_cursor: null,
    snapshot: 'snapshot-A',
    coverage: 'COMPLETE'
  })
  mocks.content.mockResolvedValue({
    content_ref: 'request-A',
    availability: 'FULL',
    value: '已读取原文'
  })
})
afterEach(() => {
  wrapper?.unmount()
  clearFeedback()
  document.body.innerHTML = ''
})

async function open(path = 'sessions/detail') {
  // 使用生产路由声明和平台宿主，确保测试能捕获 fullPath 导致的重建。
  router = createRouter({ history: createMemoryHistory(), routes: appRouter.options.routes })
  await router.push(
    `/platform/${path}?tenant_id=tenant-A&session_id=session-A&root_input_id=round-A`
  )
  await router.isReady()
  wrapper = mount(RouterView, { attachTo: document.body, global: { plugins: [router] } })
  await flushPromises()
}

it.each(['sessions/detail', 'trajectories/detail'])(
  '%s 中搜索、节点选择与标签往返保留状态且不重复读取会话',
  async (path) => {
    await open(path)
    const page = wrapper.getComponent(SessionDetailPage).vm.$.uid
    const tree = wrapper.getComponent(ObservationTree)
    tree.vm.$emit('search', '模型')
    await flushPromises()
    tree.vm.$emit('focus', modelNode)
    await flushPromises()
    await wrapper.get('#tab-messages').trigger('click')
    await flushPromises()
    await wrapper.get('#tab-timeline').trigger('click')
    await flushPromises()
    expect(wrapper.getComponent(SessionDetailPage).vm.$.uid).toBe(page)
    expect(wrapper.getComponent(ObservationTree).props('appliedQuery')).toBe('模型')
    expect(wrapper.getComponent(ObservationTree).props('focusNode')).toBe('model')
    expect(mocks.detail).toHaveBeenCalledTimes(1)
    expect(mocks.rounds).toHaveBeenCalledTimes(1)
    expect(mocks.messages).toHaveBeenCalledTimes(1)
    expect(mocks.trajectory).toHaveBeenCalledTimes(1)
    expect(mocks.nodes).toHaveBeenCalledTimes(2)
  }
)

it('切换业务轮次只读新范围一次，清空旧搜索和原文', async () => {
  await open()
  const tree = wrapper.getComponent(ObservationTree)
  tree.vm.$emit('search', '模型')
  tree.vm.$emit('content', 'request-A')
  await flushPromises()
  await wrapper.get('[data-round-id="round-B"]').trigger('click')
  await flushPromises()
  const current = wrapper.getComponent(ObservationTree)
  expect(current.props('scopeKey')).toContain('round-B')
  expect(current.props('appliedQuery')).toBe('')
  expect(current.props('content')).toBeUndefined()
  expect(router.currentRoute.value.query.root_input_id).toBe('round-B')
  expect(mocks.detail).toHaveBeenCalledTimes(1)
  expect(mocks.messages).toHaveBeenCalledTimes(2)
  expect(mocks.trajectory).toHaveBeenCalledTimes(2)
})

it('机构和会话切换取消旧原文，迟到返回不污染新会话且默认标签恢复', async () => {
  await open()
  let finish!: (value: object) => void
  mocks.content.mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        finish = resolve
      })
  )
  wrapper.getComponent(ObservationTree).vm.$emit('content', 'request-A')
  await flushPromises()
  const signal = mocks.content.mock.calls.at(-1)![1].signal as AbortSignal
  await wrapper.get('#tab-usage').trigger('click')
  await flushPromises()
  await router.push('/platform/sessions/detail?tenant_id=tenant-B&session_id=session-B')
  await flushPromises()
  expect(signal.aborted).toBe(true)
  finish({ content_ref: 'request-A', availability: 'FULL', value: '旧机构原文' })
  await flushPromises()
  expect(mocks.detail).toHaveBeenLastCalledWith('tenant-B', 'session-B', expect.anything())
  expect(wrapper.get('#tab-timeline').attributes('aria-selected')).toBe('true')
  expect(wrapper.text()).toContain('机构-session-B')
  expect(wrapper.text()).not.toContain('机构-session-A')
  expect(wrapper.text()).not.toContain('旧机构原文')
  expect(wrapper.getComponent(ObservationTree).props('content')).toBeUndefined()
})

it('授权版本变更仍重建详情并清除旧搜索', async () => {
  await open()
  const page = wrapper.getComponent(SessionDetailPage).vm.$.uid
  wrapper.getComponent(ObservationTree).vm.$emit('search', '模型')
  await flushPromises()
  auth.user.authorization_version++
  await flushPromises()
  expect(wrapper.getComponent(SessionDetailPage).vm.$.uid).not.toBe(page)
  expect(wrapper.getComponent(ObservationTree).props('appliedQuery')).toBe('')
  expect(mocks.detail).toHaveBeenCalledTimes(2)
})
