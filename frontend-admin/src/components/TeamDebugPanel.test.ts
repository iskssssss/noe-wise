import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { HttpClientError } from '@skillforge/shared/utils/http'
import TeamDebugPanel from './TeamDebugPanel.vue'
import { ElButton, ElInput } from 'element-plus'
import { clearFeedback } from '@skillforge/shared/feedback'

const mocks = vi.hoisted(() => ({
  history: vi.fn(),
  current: vi.fn(),
  control: vi.fn(),
  details: vi.fn(),
  confirm: vi.fn()
}))
vi.mock('../api', () => ({
  api: {
    chat: {
      teamWorkHistory: mocks.history,
      teamWork: mocks.current,
      controlTeamWork: mocks.control,
      memberTaskDetails: mocks.details
    }
  }
}))

vi.mock('@skillforge/shared/feedback', async (original) => ({
  ...(await original<typeof import('@skillforge/shared/feedback')>()),
  confirm: mocks.confirm
}))

let wrapper: VueWrapper | undefined
beforeEach(() => {
  vi.resetAllMocks()
  mocks.confirm.mockResolvedValue(true)
  mocks.control.mockResolvedValue({})
  mocks.history.mockResolvedValue({ items: [] })
  mocks.current.mockResolvedValue(null)
})
afterEach(() => {
  wrapper?.unmount()
  clearFeedback()
  document.body.innerHTML = ''
})

describe('团队计划历史反馈归属', () => {
  it.each(['session', 'update'] as const)(
    '%s 读取失败由页面顶部承接，避免重复提示',
    async (source) => {
      const failure = new HttpClientError({ status: 503, userMessage: '任务计划暂时无法读取' })
      if (source === 'session') mocks.history.mockRejectedValueOnce(failure)
      wrapper = mount(TeamDebugPanel, { props: { sessionId: 'A' } })
      await flushPromises()
      if (source === 'update') {
        mocks.history.mockRejectedValueOnce(failure)
        await wrapper.setProps({ update: { workId: 'work', sequence: 1 } })
        await flushPromises()
      }
      expect(failure.hasFeedbackOwner).toBe(true)
      expect(document.body.querySelectorAll('.el-message')).toHaveLength(1)
      expect(document.body.querySelector('.el-message')?.textContent).toContain(
        '任务计划暂时无法读取'
      )
      expect(wrapper.find('.el-alert').exists()).toBe(false)
    }
  )

  it.each(['session', 'update'] as const)(
    '%s 旧会话读取迟到失败也归属原面板，不覆盖新会话或触发全局反馈',
    async (source) => {
      let reject!: (reason: unknown) => void
      const pending = () =>
        new Promise((_resolve, rejectRequest) => {
          reject = rejectRequest
        })
      if (source === 'session') mocks.history.mockImplementationOnce(pending)
      wrapper = mount(TeamDebugPanel, { props: { sessionId: 'A' } })
      await flushPromises()
      if (source === 'update') {
        mocks.history.mockImplementationOnce(pending)
        await wrapper.setProps({ update: { workId: 'work', sequence: 1 } })
      }
      await wrapper.setProps({ sessionId: 'B' })
      await flushPromises()
      const failure = new HttpClientError({ status: 503, userMessage: '旧会话读取失败' })
      reject(failure)
      await flushPromises()
      expect(failure.hasFeedbackOwner).toBe(true)
      expect(wrapper.text()).not.toContain('旧会话读取失败')
      expect(wrapper.find('.el-alert').exists()).toBe(false)
    }
  )
})

it('会话只读禁止计划批准、任务调整与待办推进，保留详情读取和取消任务', async () => {
  const task = {
    task_id: 'task',
    task_kind: 'plan',
    details_access: 'available',
    title: '业务任务',
    agent_id: '101',
    agent_name: '助手',
    depends_on: [],
    status: 'waiting',
    result_ids: []
  }
  const work = {
    work_id: 'work',
    session_id: 'A',
    input_id: 'input',
    run_id: 'run',
    run_revision: 1,
    run_status: 'suspended',
    revision: 1,
    status: 'active',
    plan_revision: 1,
    approved_revision: 0,
    approval_required: true,
    coordinator: { agent_id: '101', name: '助手' },
    tasks: [task],
    results: []
  }
  mocks.current.mockResolvedValue(work)
  mocks.details.mockResolvedValue({
    task: { ...task, constraints: [] },
    run: { status: 'suspended' },
    processes: [],
    has_more: false,
    pending: {
      kind: 'operation',
      pending_id: 'pending',
      prompt: '确认业务操作',
      parameters_version: '1'
    }
  })
  wrapper = mount(TeamDebugPanel, {
    props: { sessionId: 'A', readOnly: true },
    attachTo: document.body
  })
  await flushPromises()
  const button = (label: string) =>
    wrapper!.findAllComponents(ElButton).find((item) => item.text() === label)!
  expect(button('批准当前计划').props('disabled')).toBe(true)
  expect(button('调整').props('disabled')).toBe(true)
  button('批准当前计划').vm.$emit('click')
  await flushPromises()
  expect(mocks.control).not.toHaveBeenCalled()
  await button('详情').trigger('click')
  await flushPromises()
  expect(mocks.details).toHaveBeenCalled()
  expect(button('批准操作').props('disabled')).toBe(true)
  button('批准操作').vm.$emit('click')
  await flushPromises()
  expect(mocks.control).not.toHaveBeenCalled()
  await button('取消计划').trigger('click')
  await flushPromises()
  expect(mocks.control).toHaveBeenCalledWith(
    'A',
    expect.objectContaining({ work_id: 'work', operation: 'cancel' }),
    expect.any(AbortSignal)
  )
})

function controlWork() {
  return {
    work_id: 'work',
    session_id: 'A',
    input_id: 'input',
    run_id: 'run',
    run_revision: 1,
    run_status: 'suspended',
    revision: 1,
    status: 'active',
    plan_revision: 1,
    approved_revision: 0,
    approval_required: true,
    coordinator: { agent_id: '101', name: '助手' },
    tasks: [
      {
        task_id: 'task',
        task_kind: 'plan',
        details_access: 'available',
        title: '业务任务',
        agent_id: '101',
        agent_name: '助手',
        depends_on: [],
        status: 'waiting',
        result_ids: []
      }
    ],
    results: []
  }
}
function panelButton(label: string) {
  return wrapper!.findAllComponents(ElButton).find((button) => button.text() === label)!
}
async function feedbackButton(label: string) {
  const button = Array.from(document.body.querySelectorAll('button')).find(
    (item) => item.textContent?.trim() === label
  )!
  expect(button).toBeDefined()
  button.click()
  await flushPromises()
}
async function lostControl() {
  const work = controlWork()
  mocks.current.mockResolvedValue(work)
  mocks.control.mockRejectedValueOnce(
    new HttpClientError({ status: 0, userMessage: '控制回执丢失' })
  )
  wrapper = mount(TeamDebugPanel, { props: { sessionId: 'A' }, attachTo: document.body })
  await flushPromises()
  await panelButton('调整').trigger('click')
  await flushPromises()
  wrapper.getComponent(ElInput).vm.$emit('update:modelValue', '原始调整内容')
  await flushPromises()
  await panelButton('提交').trigger('click')
  await flushPromises()
  expect(mocks.control).toHaveBeenCalledTimes(1)
  return { work, body: structuredClone(mocks.control.mock.calls[0]![1]) }
}

it('控制响应丢失后改字、关闭重开与计划刷新均不产生替代请求，只按原input_id读取回执', async () => {
  const { work, body } = await lostControl()
  wrapper!.getComponent(ElInput).vm.$emit('update:modelValue', '修改后的内容')
  await flushPromises()
  panelButton('提交').vm.$emit('click')
  await flushPromises()
  expect(mocks.control).toHaveBeenCalledTimes(1)
  await panelButton('取消').trigger('click')
  await flushPromises()
  await panelButton('调整').trigger('click')
  await flushPromises()
  panelButton('提交').vm.$emit('click')
  await flushPromises()
  expect(mocks.control).toHaveBeenCalledTimes(1)

  const leaving = new Event('beforeunload', { cancelable: true })
  window.dispatchEvent(leaving)
  expect(leaving.defaultPrevented).toBe(true)
  mocks.current.mockResolvedValue({
    ...work,
    revision: 2,
    plan_revision: 2,
    control_inputs: [
      { input_id: 'another-control', task_id: 'task', state: 'APPLIED', detail: '其它控制' }
    ]
  })
  await panelButton('刷新计划').trigger('click')
  await flushPromises()
  await feedbackButton('核对原请求')
  expect(mocks.current).toHaveBeenLastCalledWith('A', expect.any(AbortSignal), 'work')
  expect(document.body.textContent).toContain('结果仍待核对')
  panelButton('提交').vm.$emit('click')
  await flushPromises()
  expect(mocks.control).toHaveBeenCalledTimes(1)

  mocks.current.mockResolvedValue({
    ...work,
    control_inputs: [
      { input_id: body.input_id, task_id: 'task', state: 'APPLIED', detail: '已应用' }
    ]
  })
  await feedbackButton('核对原请求')
  expect(document.body.textContent).toContain('原控制已执行')
  expect(panelButton('提交').props('disabled')).toBe(false)
  expect(mocks.control).toHaveBeenCalledTimes(1)
})

it('显式重发仅采用冻结原input_id和原payload，不采用已修改的说明', async () => {
  const { work, body } = await lostControl()
  wrapper!.getComponent(ElInput).vm.$emit('update:modelValue', '不应重发的新正文')
  await flushPromises()
  mocks.control.mockResolvedValueOnce(work)
  await feedbackButton('重发原请求')
  expect(mocks.confirm).toHaveBeenCalledWith(expect.objectContaining({ title: '重发原控制请求' }))
  expect(mocks.control).toHaveBeenCalledTimes(2)
  expect(mocks.control.mock.calls[1]![0]).toBe('A')
  expect(mocks.control.mock.calls[1]![1]).toEqual(body)
  expect(mocks.control.mock.calls[1]![1].content).toBe('原始调整内容')
})

it('步骤回复丢失后修改输入仍保留原pending标识和控制请求，缺失回执不解除待核对', async () => {
  const work = controlWork()
  mocks.current.mockResolvedValue(work)
  mocks.details.mockResolvedValue({
    task: { ...work.tasks[0], constraints: [] },
    run: { status: 'suspended' },
    processes: [],
    has_more: false,
    pending: {
      kind: 'input',
      pending_id: 'pending-original',
      prompt: '请补充信息',
      parameters_version: '3'
    }
  })
  mocks.control.mockRejectedValueOnce(
    new HttpClientError({ status: 0, userMessage: '回复回执丢失' })
  )
  wrapper = mount(TeamDebugPanel, { props: { sessionId: 'A' }, attachTo: document.body })
  await flushPromises()
  await panelButton('详情').trigger('click')
  await flushPromises()
  wrapper.getComponent(ElInput).vm.$emit('update:modelValue', '原始回复')
  await flushPromises()
  await panelButton('提交回答').trigger('click')
  await flushPromises()
  expect(mocks.control).toHaveBeenCalledTimes(1)
  const body = structuredClone(mocks.control.mock.calls[0]![1])
  expect(body).toMatchObject({
    pending_id: 'pending-original',
    parameters_version: '3',
    content: '原始回复'
  })
  wrapper.getComponent(ElInput).vm.$emit('update:modelValue', '修改后的回复')
  panelButton('提交回答').vm.$emit('click')
  await flushPromises()
  expect(mocks.control).toHaveBeenCalledTimes(1)
  await feedbackButton('核对原请求')
  expect(document.body.textContent).toContain('结果仍待核对')
  expect(panelButton('提交回答').props('disabled')).toBe(true)
})

it('重发确认期间会话变为只读时保留原控制，不派发旧写请求', async () => {
  await lostControl()
  let completeConfirmation!: (accepted: boolean) => void
  mocks.confirm.mockImplementationOnce(
    () =>
      new Promise<boolean>((resolve) => {
        completeConfirmation = resolve
      })
  )
  await feedbackButton('重发原请求')
  await wrapper!.setProps({ readOnly: true })
  completeConfirmation(true)
  await flushPromises()
  expect(mocks.control).toHaveBeenCalledTimes(1)
  expect(document.body.textContent).toContain('原控制操作尚待核对')
})
