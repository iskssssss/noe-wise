import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { ElButton, ElInput, ElSelect } from 'element-plus'
import type {
  ChatSteeringCommandStatus,
  ChatSteeringState
} from '@skillforge/shared/components/chatSteering'
import { clearFeedback } from '@skillforge/shared/feedback'
import { HttpClientError } from '@skillforge/shared/utils/http'
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
  history: vi.fn(),
  detail: vi.fn(),
  observe: vi.fn(),
  steer: vi.fn(),
  steering: vi.fn(),
  submit: vi.fn(),
  confirm: vi.fn()
}))
vi.mock('../api', () => ({
  api: {
    chat: {
      endUsers: async () => [],
      sessions: async () => [{ session_id: 'A' }, { session_id: 'B' }],
      sessionDetail: mocks.detail,
      history: mocks.history,
      observeSession: mocks.observe,
      steer: mocks.steer,
      steering: mocks.steering,
      submit: mocks.submit,
      goals: async () => ({ goal: null }),
      artifacts: async () => [],
      collaboration: async () => ({})
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
  useRoute: () => ({ query: {} }),
  onBeforeRouteLeave: vi.fn(),
  onBeforeRouteUpdate: vi.fn()
}))
vi.mock('../components/TeamDebugPanel.vue', () => ({ default: { template: '<div />' } }))
vi.mock('@skillforge/shared/components/MarkdownContent.vue', () => ({
  default: { props: ['content'], template: '<div>{{ content }}</div>' }
}))

let wrapper: ReturnType<typeof mount>
let events: { onEvent?: (event: { event: string; data: Record<string, unknown> }) => void }
const snapshot = (
  id: string,
  text: string,
  status: ChatSteeringCommandStatus = 'PENDING',
  run = 'run:A'
): ChatSteeringState => ({
  run_id: run,
  accepting: true,
  items: [{ command_id: id, text, status }]
})
const failure = (status: number, code: string) =>
  new HttpClientError({ status, code, userMessage: '补充响应未完成' })
function button(text: string) {
  const found = wrapper.findAllComponents(ElButton).find((item) => item.text() === text)
  if (!found) throw Error('按钮不存在：' + text)
  return found
}
function sessionSelect() {
  return wrapper
    .findAllComponents(ElSelect)
    .find((item) => item.props('placeholder') === '历史会话')!
}
function steeringInput() {
  return wrapper
    .findAll('details')
    .find((item) => item.get('summary').text() === '运行中补充指令')!
    .findComponent(ElInput)
}
async function setup(text = '原运行补充') {
  wrapper = mount(ChatPage, { attachTo: document.body })
  await flushPromises()
  sessionSelect().vm.$emit('change', 'A')
  await flushPromises()
  await wrapper
    .findAll('[role="tab"]')
    .find((item) => item.text() === '运行过程')!
    .trigger('click')
  await flushPromises()
  steeringInput().vm.$emit('update:modelValue', text)
  await flushPromises()
}
beforeEach(() => {
  vi.resetAllMocks()
  events = {}
  mocks.history.mockImplementation(async (id: string) => ({
    session_id: id,
    messages: [],
    pending_interactions: [],
    has_more: false,
    activities: [{ node_id: 'run:' + id, node_type: 'agent_runtime', status: 'running' }]
  }))
  mocks.detail.mockImplementation(async (id: string) => ({
    session: { session_id: id },
    end_user_id: '42',
    target_agent_id: '101',
    closed: false
  }))
  mocks.observe.mockImplementation(async (_request, handlers) => {
    events = handlers
  })
  mocks.steer.mockImplementation(async (_session: string, run: string, id: string, text: string) =>
    snapshot(id, text, 'PENDING', run)
  )
  mocks.steering.mockResolvedValue({ run_id: 'run:A', accepting: true, items: [] })
  mocks.confirm.mockResolvedValue(false)
})
afterEach(() => {
  wrapper?.unmount()
  clearFeedback()
  document.body.innerHTML = ''
})

describe('机构运行补充的原命令身份', () => {
  it('UNKNOWN后再次点击不能生成第二补充，只能核对或按原四参数显式重试', async () => {
    mocks.steer.mockRejectedValueOnce(failure(408, 'TIMEOUT'))
    await setup()
    await button('提交补充').trigger('click')
    await flushPromises()
    const original = [...mocks.steer.mock.calls[0]!]
    await button('提交补充').trigger('click')
    await flushPromises()
    expect(mocks.steer).toHaveBeenCalledTimes(1)
    expect(steeringInput().props('modelValue')).toBe('原运行补充')
    await button('重试原补充').trigger('click')
    await flushPromises()
    expect(mocks.steer.mock.calls[1]).toEqual(original)
    expect(steeringInput().props('modelValue')).toBe('')
  })

  it.each([403, 404, 409])(
    '首次晚阶段%s不证明未写，原UNKNOWN后400也不能解除原身份',
    async (status) => {
      mocks.steer
        .mockRejectedValueOnce(failure(status, 'REJECTED_AFTER_OFFER'))
        .mockRejectedValueOnce(failure(400, 'VALIDATION_ERROR'))
      await setup()
      await button('提交补充').trigger('click')
      await flushPromises()
      const original = [...mocks.steer.mock.calls[0]!]
      await button('重试原补充').trigger('click')
      await flushPromises()
      expect(mocks.steer.mock.calls[1]).toEqual(original)
      expect(button('提交补充').props('disabled')).toBe(true)
      expect(steeringInput().props('modelValue')).toBe('原运行补充')
      expect(button('核对补充状态').exists()).toBe(true)
    }
  )

  it('首次400绑定校验拒绝可释放命令，修正后才生成新身份', async () => {
    mocks.steer.mockRejectedValueOnce(failure(400, 'VALIDATION_ERROR'))
    await setup()
    await button('提交补充').trigger('click')
    await flushPromises()
    const original = mocks.steer.mock.calls[0]![2]
    steeringInput().vm.$emit('update:modelValue', '已修正补充')
    await flushPromises()
    await button('提交补充').trigger('click')
    await flushPromises()
    expect(mocks.steer.mock.calls[1]![2]).not.toBe(original)
    expect(mocks.steer.mock.calls[1]![3]).toBe('已修正补充')
  })

  it('MISSING和同ID错正文均不解除未决，当前Run改变仍查询原session/run', async () => {
    mocks.steer.mockRejectedValueOnce(failure(408, 'TIMEOUT'))
    await setup()
    await button('提交补充').trigger('click')
    await flushPromises()
    const id = mocks.steer.mock.calls[0]![2] as string
    events.onEvent?.({ event: 'run.started', data: { run_id: 'run:new' } })
    await flushPromises()
    mocks.steering.mockRejectedValueOnce(failure(503, 'READ_FAILED'))
    await button('核对补充状态').trigger('click')
    await flushPromises()
    expect(mocks.steering).toHaveBeenLastCalledWith('A', 'run:A')
    expect(button('提交补充').props('disabled')).toBe(true)
    for (const result of [
      { run_id: 'run:A', accepting: false, items: [] },
      snapshot(id, '另一份正文')
    ]) {
      mocks.steering.mockResolvedValueOnce(result)
      await button('核对补充状态').trigger('click')
      await flushPromises()
      expect(mocks.steering).toHaveBeenLastCalledWith('A', 'run:A')
      expect(button('提交补充').props('disabled')).toBe(true)
      expect(steeringInput().props('modelValue')).toBe('原运行补充')
    }
    expect(mocks.steer).toHaveBeenCalledTimes(1)
  })

  it.each([
    ['PENDING', '待纳入'],
    ['INCLUDED', '已纳入'],
    ['CONSUMED', '已消费']
  ] as const)('GET精确确认%s只清原稿并展示实际消费状态', async (status, label) => {
    mocks.steer.mockRejectedValueOnce(failure(408, 'TIMEOUT'))
    await setup()
    await button('提交补充').trigger('click')
    await flushPromises()
    const id = mocks.steer.mock.calls[0]![2] as string
    mocks.steering.mockResolvedValueOnce(snapshot(id, '原运行补充', status))
    await button('核对补充状态').trigger('click')
    await flushPromises()
    expect(steeringInput().props('modelValue')).toBe('')
    expect(wrapper.text()).toContain(label)
    expect(mocks.steer).toHaveBeenCalledTimes(1)
  })

  it('已确认回执不清除提交期间另写的新补充', async () => {
    let finish!: (value: ChatSteeringState) => void
    mocks.steer.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve
        })
    )
    await setup()
    await button('提交补充').trigger('click')
    await flushPromises()
    const id = mocks.steer.mock.calls[0]![2] as string
    steeringInput().vm.$emit('update:modelValue', '下一份补充')
    await flushPromises()
    finish(snapshot(id, '原运行补充'))
    await flushPromises()
    expect(steeringInput().props('modelValue')).toBe('下一份补充')
  })

  it('同一已受理补充可GET推进消费状态，当前Run改变仍查原且不清同文新稿，切换清所属回执', async () => {
    await setup()
    await button('提交补充').trigger('click')
    await flushPromises()
    const id = mocks.steer.mock.calls[0]![2] as string
    expect(wrapper.text()).toContain('待纳入')
    expect(steeringInput().props('modelValue')).toBe('')
    steeringInput().vm.$emit('update:modelValue', '原运行补充')
    events.onEvent?.({ event: 'run.started', data: { run_id: 'run:new' } })
    await flushPromises()
    for (const [status, label] of [
      ['INCLUDED', '已纳入'],
      ['CONSUMED', '已消费']
    ] as const) {
      mocks.steering.mockResolvedValueOnce({
        ...snapshot(id, '原运行补充', status),
        accepting: status !== 'CONSUMED'
      })
      await button('刷新补充状态').trigger('click')
      await flushPromises()
      expect(mocks.steering).toHaveBeenLastCalledWith('A', 'run:A')
      expect(wrapper.text()).toContain(label)
      expect(steeringInput().props('modelValue')).toBe('原运行补充')
      expect(mocks.steer).toHaveBeenCalledTimes(1)
    }
    expect(mocks.steering).toHaveBeenCalledTimes(2)
    expect(
      wrapper.findAllComponents(ElButton).some((item) => item.text() === '追加到下一条消息')
    ).toBe(false)
    mocks.confirm.mockResolvedValueOnce(true)
    sessionSelect().vm.$emit('change', 'B')
    await flushPromises()
    expect(sessionSelect().props('modelValue')).toBe('B')
    expect(wrapper.findAllComponents(ElButton).some((item) => item.text() === '刷新补充状态')).toBe(
      false
    )
    expect(wrapper.text()).not.toContain('补充状态：')
    expect(mocks.steering).toHaveBeenCalledTimes(2)
    expect(mocks.submit).not.toHaveBeenCalled()
  })

  it.each(['PENDING', 'INCLUDED'] as const)(
    '原Run不再接受且补充%s未消费时，可看原文并显式追加已有下一条草稿，不自动发送',
    async (status) => {
      await setup()
      await button('提交补充').trigger('click')
      await flushPromises()
      const id = mocks.steer.mock.calls[0]![2] as string
      const input = wrapper
        .findAllComponents(ElInput)
        .find((field) => field.props('placeholder') === '输入消息')!
      input.vm.$emit('update:modelValue', '已有下一条消息')
      mocks.steering.mockResolvedValueOnce({
        ...snapshot(id, '原运行补充', status),
        accepting: false
      })
      await button('刷新补充状态').trigger('click')
      await flushPromises()
      const original = wrapper
        .findAll('details')
        .find((item) => item.get('summary').text() === '查看原补充')!
      expect(original.text()).toContain('原运行补充')
      expect(input.props('modelValue')).toBe('已有下一条消息')
      expect(mocks.submit).not.toHaveBeenCalled()
      await button('追加到下一条消息').trigger('click')
      await flushPromises()
      expect(input.props('modelValue')).toBe('已有下一条消息\n\n原运行补充')
      expect(mocks.steering).toHaveBeenLastCalledWith('A', 'run:A')
      expect(mocks.steer).toHaveBeenCalledTimes(1)
      expect(mocks.submit).not.toHaveBeenCalled()
    }
  )

  it('未决补充阻止切换会话，普通未发送补充沿原确认保护', async () => {
    await setup()
    sessionSelect().vm.$emit('change', 'B')
    await flushPromises()
    expect(mocks.confirm).toHaveBeenCalled()
    expect(sessionSelect().props('modelValue')).toBe('A')
    mocks.confirm.mockClear()
    mocks.steer.mockRejectedValueOnce(failure(408, 'TIMEOUT'))
    await button('提交补充').trigger('click')
    await flushPromises()
    sessionSelect().vm.$emit('change', 'B')
    await flushPromises()
    expect(sessionSelect().props('modelValue')).toBe('A')
    expect(mocks.detail.mock.calls.map(([id]) => id)).toEqual(['A'])
    expect(mocks.steer).toHaveBeenCalledTimes(1)
  })
})
