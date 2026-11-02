import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { ElSelect, ElInput, ElInputNumber, ElFormItem } from 'element-plus'
import ChatPage from './ChatPage.vue'
vi.mock('../stores/auth', () => ({
  useAuthStore: () => ({
    identityVersion: 1,
    user: {
      tenant_id: '1',
      sub: 'member-1',
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
import ToolInputForm from '../components/ToolInputForm.vue'
import { HttpClientError } from '@skillforge/shared/utils/http'
const mocks = vi.hoisted(() => ({
  detail: vi.fn(),
  close: vi.fn(),
  sessions: vi.fn(),
  history: vi.fn(),
  observe: vi.fn(),
  confirm: vi.fn(),
  submit: vi.fn(),
  budget: vi.fn(),
  createGoal: vi.fn()
}))
vi.mock('../api', () => ({
  api: {
    chat: {
      endUsers: async () => [],
      sessions: mocks.sessions,
      sessionDetail: mocks.detail,
      closeSession: mocks.close,
      history: mocks.history,
      observeSession: mocks.observe,
      submit: mocks.submit,
      runBudget: mocks.budget,
      createGoal: mocks.createGoal,
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
const history = (id: string) => ({
  session_id: id,
  messages: [{ message_id: id, role: 'assistant', content: id + '消息' }],
  pending_interactions: [],
  has_more: false
})
beforeEach(() => {
  sessionStorage.clear()
  vi.resetAllMocks()
  mocks.sessions.mockResolvedValue([{ session_id: 'A' }, { session_id: 'B' }])
  mocks.detail.mockImplementation(async (id: string) => ({
    session: { session_id: id },
    end_user_id: '42',
    target_agent_id: '101',
    closed: false
  }))
  mocks.close.mockResolvedValue({ session_id: 'A', status: 'closed' })
  mocks.history.mockImplementation(async (id: string) => history(id))
  mocks.observe.mockResolvedValue(undefined)
  mocks.confirm.mockResolvedValue(true)
})
afterEach(() => {
  wrapper?.unmount()
  document.body.innerHTML = ''
})
async function setup() {
  wrapper = mount(ChatPage, { attachTo: document.body })
  await flushPromises()
  const sessions = wrapper
    .findAllComponents(ElSelect)
    .find((field) => field.props('placeholder') === '历史会话')!
  sessions.vm.$emit('change', 'A')
  await flushPromises()
  const input = wrapper
    .findAllComponents(ElInput)
    .find((field) => field.props('placeholder') === '输入消息')!
  input.vm.$emit('update:modelValue', '未发送草稿')
  await flushPromises()
  return { sessions, input }
}
describe('聊天会话原子切换', () => {
  it.each([
    ['tool_output_invalid', '工具已执行，但返回内容未通过格式校验'],
    ['tool_outcome_unknown', '工具执行结果尚未确认'],
    ['execution_output_closing_budget_insufficient', '无法为任务收尾预留足够额度']
  ])('运行过程解释正式原因 %s', async (reason, explanation) => {
    mocks.history.mockResolvedValue({
      ...history('A'),
      activities: [
        { node_id: 'run:A', node_type: 'agent_runtime', status: 'failed', render: { reason } }
      ]
    })
    await setup()
    await wrapper
      .findAll('[role="tab"]')
      .find((tab) => tab.text() === '运行过程')!
      .trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain(explanation)
  })
  it.each([
    ['running', false],
    ['terminated', true]
  ])('恢复 %s 运行时正确控制停止入口', async (status, disabled) => {
    mocks.history.mockResolvedValue({
      ...history('A'),
      activities: [{ node_id: 'run:A', node_type: 'agent_runtime', status }]
    })
    await setup()
    const stop = wrapper.findAll('button').find((button) => button.text() === '停止运行')!
    expect(stop.attributes('disabled') !== undefined).toBe(disabled)
  })
  it.each([
    ['connected', '已连接'],
    ['reconnecting', '正在重连'],
    ['unavailable', '连接不可用'],
    ['future-state', 'future-state']
  ])('只读连接状态 %s 使用明确展示 %s，未知状态不猜测', async (state, label) => {
    mocks.observe.mockImplementation(
      async (_request, handlers: { onConnection?: (value: string) => void }) => {
        handlers.onConnection?.(state)
      }
    )
    await setup()
    expect(wrapper.get('.session-setup [role="status"]').text()).toBe(label)
    expect(mocks.submit).not.toHaveBeenCalled()
  })

  it('目标表单创建默认 256 轮并保留显式轮数', async () => {
    mocks.createGoal.mockResolvedValue({ goal: null })
    await setup()
    await wrapper
      .findAll('[role="tab"]')
      .find((tab) => tab.text() === '计划与目标')!
      .trigger('click')
    await flushPromises()
    const goalField = wrapper
      .findAllComponents(ElFormItem)
      .find((item) => item.props('label') === '目标')!
    const rounds = wrapper
      .findAllComponents(ElInputNumber)
      .find((input) => input.props('max') === 1024)!
    expect(rounds.props('modelValue')).toBe(256)
    await goalField.getComponent(ElInput).get('textarea').setValue('完成业务交付')
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '创建目标')!
      .trigger('click')
    await flushPromises()
    expect(mocks.createGoal).toHaveBeenCalledWith('A', expect.any(String), '完成业务交付', 256)
    const currentGoalField = wrapper
      .findAllComponents(ElFormItem)
      .find((item) => item.props('label') === '目标')!
    await currentGoalField.getComponent(ElInput).get('textarea').setValue('继续业务交付')
    wrapper
      .findAllComponents(ElInputNumber)
      .find((input) => input.props('max') === 1024)!
      .vm.$emit('update:modelValue', 1024)
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '创建目标')!
      .trigger('click')
    await flushPromises()
    expect(mocks.createGoal).toHaveBeenLastCalledWith('A', expect.any(String), '继续业务交付', 1024)
  })
  it('运行额度按明确选择读取，迟到的其他运行回执不能覆盖当前观察', async () => {
    mocks.history.mockResolvedValue({
      ...history('A'),
      activities: [
        {
          node_id: 'run:A',
          node_type: 'agent_runtime',
          status: 'failed',
          render: { reason: 'execution_budget_root_tool_calls_limit' }
        },
        {
          node_id: 'run:B',
          node_type: 'agent_runtime',
          status: 'completed',
          render: { reason: 'completed' }
        }
      ]
    })
    await setup()
    expect(mocks.budget).not.toHaveBeenCalled()
    await wrapper
      .findAll('[role="tab"]')
      .find((tab) => tab.text() === '运行过程')!
      .trigger('click')
    await flushPromises()
    const resources = {
      business_model_calls: '10',
      auxiliary_model_calls: '5',
      tool_calls: '20',
      knowledge_calls: '2',
      input_tokens: '1000',
      output_tokens: '1000',
      total_tokens: '1000'
    }
    const scope = {
      limits: resources,
      consumed: resources,
      reserved: resources,
      active_seconds_limit: '600',
      confirmed_active_millis: '1234'
    }
    const budget = (id: string) => ({
      run_id: id,
      root_run_id: 'root',
      scenario: 'STANDARD',
      role: 'PRIMARY',
      phase: 'BUSINESS',
      source_version: 'frozen-source:' + id,
      root: scope,
      run: scope,
      closing: { resources, active_seconds: '30' }
    })
    let finish!: (value: ReturnType<typeof budget>) => void
    mocks.budget
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            finish = resolve
          })
      )
      .mockResolvedValueOnce(budget('run:B'))
    const read = () => wrapper.findAll('button').find((button) => button.text() === '读取运行额度')!
    expect(wrapper.text()).toContain('任务根的工具调用额度已耗尽。')
    await read().trigger('click')
    await flushPromises()
    expect(mocks.budget).toHaveBeenCalledWith('A', 'run:A')
    wrapper
      .findAllComponents(ElSelect)
      .find((select) => select.props('placeholder') === '选择运行')!
      .vm.$emit('update:modelValue', 'run:B')
    await flushPromises()
    await read().trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('frozen-source:run:B')
    finish(budget('run:A'))
    await flushPromises()
    expect(wrapper.text()).not.toContain('frozen-source:run:A')
    expect(wrapper.text()).toContain('frozen-source:run:B')
  })
  it.each([
    ['', '1', '请先填写补充信息。'],
    ['补充内容', '01', '当前补充待办已失效，请重新读取会话，并按当前待办填写。']
  ])('补充回复校验区分空内容与失效待办：%s', async (text, revision, message) => {
    mocks.history.mockResolvedValueOnce({
      ...history('A'),
      pending_interactions: [
        {
          execution_id: 'input-run',
          run_revision: revision,
          interaction_type: 'user_input',
          tool_wait: { action_id: 'input-action', generation: '1' },
          question: '请提供补充信息'
        }
      ]
    })
    await setup()
    await wrapper.get('.pending-card textarea').setValue(text)
    await wrapper.get('.pending-card form').trigger('submit')
    await flushPromises()
    expect(
      Array.from(document.body.querySelectorAll('.el-message__content')).at(-1)?.textContent
    ).toContain(message)
    expect(mocks.submit).not.toHaveBeenCalled()
    expect(wrapper.find('.pending-card').exists()).toBe(true)
  })

  it('Workflow受理回执只清原准确表单，迟到回执保留同Run新等待代次', async () => {
    const pending = (generation: string) => ({
      execution_id: 'workflow-run',
      run_revision: '9007199254740993',
      interaction_type: 'workflow_input',
      tool_wait: { action_id: 'workflow-action', generation },
      workflow_execution_id: 'workflow-execution',
      suspend_ref: 'workflow-suspend',
      execution_revision: '9007199254740995',
      resume_action_key: 'workflow-confirm',
      card: { type: 'confirm', title: `工作流等待 ${generation}`, body: '请确认当前工作流结果' }
    })
    const workflowHistory = (generation: string) => ({
      ...history('A'),
      pending_interactions: [pending(generation)]
    })
    const receipt = {
      status: 'queued',
      session_id: 'A',
      execution_id: 'workflow-run',
      input_id: 'reply-1'
    }
    mocks.history.mockResolvedValueOnce(workflowHistory('1'))
    await setup()
    let refresh!: (value: ReturnType<typeof workflowHistory>) => void
    mocks.history.mockImplementationOnce(
      () =>
        new Promise<ReturnType<typeof workflowHistory>>((resolve) => {
          refresh = resolve
        })
    )
    mocks.submit.mockResolvedValueOnce(receipt)

    await wrapper.get('.pending-card form').trigger('submit')
    await flushPromises()
    expect(mocks.submit).toHaveBeenLastCalledWith({
      session_id: 'A',
      agent_id: '101',
      client_message_id: expect.any(String),
      context: { end_user_id: '42' },
      hitl_response: {
        execution_id: 'workflow-run',
        run_revision: '9007199254740993',
        workflow_input: {
          wait: { action_id: 'workflow-action', generation: '1' },
          execution_ref: 'workflow-execution',
          suspend_ref: 'workflow-suspend',
          execution_revision: '9007199254740995',
          action_key: 'workflow-confirm',
          response: {
            schema_id: 'application/json',
            canonical_json: '{"action":"confirm","response":""}'
          }
        }
      }
    })
    // 权威刷新仍挂起时，准确受理回执已经清除原表单，不能靠刷新掩盖本地匹配缺失。
    expect(wrapper.find('.pending-card').exists()).toBe(false)
    refresh(workflowHistory('2'))
    await flushPromises()
    expect(wrapper.get('.pending-card h3').text()).toBe('工作流等待 2')

    let accept!: (value: typeof receipt) => void
    mocks.submit.mockImplementationOnce(
      () =>
        new Promise<typeof receipt>((resolve) => {
          accept = resolve
        })
    )
    mocks.history.mockImplementationOnce(
      () =>
        new Promise<ReturnType<typeof workflowHistory>>((resolve) => {
          refresh = resolve
        })
    )
    await wrapper.get('.pending-card form').trigger('submit')
    await flushPromises()
    expect(mocks.submit).toHaveBeenLastCalledWith(
      expect.objectContaining({
        hitl_response: expect.objectContaining({
          workflow_input: expect.objectContaining({
            wait: { action_id: 'workflow-action', generation: '2' }
          })
        })
      })
    )
    const observation = mocks.observe.mock.calls[0]![1] as {
      onHistory: (session: string, value: ReturnType<typeof workflowHistory>) => void
    }
    observation.onHistory('A', workflowHistory('3'))
    await flushPromises()
    expect(wrapper.get('.pending-card h3').text()).toBe('工作流等待 3')

    accept({ ...receipt, input_id: 'reply-2' })
    await flushPromises()
    // 新等待先于旧回执到达；同Run、同revision和Action也必须按准确generation保留新表单。
    expect(wrapper.get('.pending-card h3').text()).toBe('工作流等待 3')
    expect(mocks.history).toHaveBeenCalledTimes(3)
    refresh(workflowHistory('3'))
    await flushPromises()
    expect(wrapper.get('.pending-card h3').text()).toBe('工作流等待 3')
  })

  it('运行过程表按正式 presentation 显示步骤和业务摘要', async () => {
    mocks.history.mockResolvedValue({
      ...history('A'),
      processes: [
        {
          process_id: 'process:A:tool',
          run_id: 'run:A',
          input_id: 'A',
          root_run_id: 'run:A',
          root_input_id: 'A',
          type: 'tool',
          sequence: 1,
          ordinal: 0,
          updated_sequence: 2,
          status: 'succeeded',
          presentation: {
            title: '查找已有成果',
            summary: '本次查询已完成，未找到已发布成果',
            results: [],
            references: []
          }
        }
      ]
    })
    await setup()
    const tab = wrapper.findAll('[role="tab"]').find((item) => item.text() === '运行过程')!
    await tab.trigger('click')
    await flushPromises()
    const cells = wrapper.findAll('td').map((cell) => cell.text())
    expect(cells).toContain('查找已有成果')
    expect(cells).toContain('本次查询已完成，未找到已发布成果')
  })

  it('切换等待任务保留各工具表单草稿，隐藏草稿仍触发离开确认', async () => {
    const pending = (id: string) => ({
      execution_id: id,
      run_revision: '1',
      interaction_type: 'tool_input',
      tool_wait: { action_id: id, generation: '1' },
      card: {
        type: 'tool_input',
        title: id,
        tool_wait: {
          action_id: id,
          generation: '1'
        },
        requests: [
          {
            id,
            message: '请填写',
            requested_schema: {
              type: 'object',
              properties: { text: { type: 'string' } }
            }
          }
        ]
      }
    })
    mocks.history.mockImplementation(async (id: string) => ({
      ...history(id),
      pending_interactions: [pending('first'), pending('second')]
    }))
    const { sessions, input } = await setup()
    input.vm.$emit('update:modelValue', '')
    const forms = wrapper.findAllComponents(ToolInputForm)
    expect(forms).toHaveLength(2)
    await forms[0]!.get('input').setValue('第一个任务的草稿')
    const selector = wrapper
      .findAllComponents(ElSelect)
      .find((field) => field.props('placeholder') === '选择需要回复的任务')!
    selector.vm.$emit('update:modelValue', 'second')
    await flushPromises()
    expect(forms[0]!.isVisible()).toBe(false)
    await forms[1]!.get('input').setValue('第二个任务的草稿')
    selector.vm.$emit('update:modelValue', 'first')
    await flushPromises()
    expect((forms[0]!.get('input').element as HTMLInputElement).value).toBe('第一个任务的草稿')
    expect((forms[1]!.get('input').element as HTMLInputElement).value).toBe('第二个任务的草稿')
    mocks.confirm.mockResolvedValue(false)
    sessions.vm.$emit('change', 'B')
    await flushPromises()
    expect(mocks.confirm).toHaveBeenCalled()
    expect(sessions.props('modelValue')).toBe('A')
    expect(forms[0]!.exists()).toBe(true)
  })
  it('取消切换保留当前身份和草稿', async () => {
    const { sessions, input } = await setup()
    mocks.confirm.mockResolvedValue(false)
    sessions.vm.$emit('change', 'B')
    await flushPromises()
    expect(sessions.props('modelValue')).toBe('A')
    expect(input.props('modelValue')).toBe('未发送草稿')
    expect(mocks.history).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('A消息')
  })
  it('历史读取失败保留原会话，成功后才清除草稿并订阅新会话', async () => {
    const { sessions, input } = await setup()
    mocks.history.mockRejectedValueOnce(new Error('历史读取失败'))
    sessions.vm.$emit('change', 'B')
    await flushPromises()
    expect(sessions.props('modelValue')).toBe('A')
    expect(input.props('modelValue')).toBe('未发送草稿')
    expect(wrapper.text()).toContain('A消息')
    expect(
      Array.from(document.body.querySelectorAll('.el-message__content')).at(-1)?.textContent
    ).toContain('历史读取失败')
    expect(mocks.observe).toHaveBeenCalledTimes(1)
    sessions.vm.$emit('change', 'B')
    await flushPromises()
    expect(sessions.props('modelValue')).toBe('B')
    expect(input.props('modelValue')).toBe('')
    expect(wrapper.text()).toContain('B消息')
    expect(wrapper.text()).not.toContain('A消息')
    expect(mocks.observe).toHaveBeenCalledTimes(2)
  })
})

describe('历史会话绑定与关闭', () => {
  const button = (label: string) => wrapper.findAll('button').find((item) => item.text() === label)!
  it('历史切换恢复服务端字符串用户和Agent绑定，再发送使用原绑定', async () => {
    mocks.detail.mockResolvedValue({
      session: { session_id: 'A' },
      end_user_id: '9223372036854775806',
      target_agent_id: '9223372036854775807',
      closed: false
    })
    await setup()
    expect(
      wrapper
        .findAllComponents(ElSelect)
        .find((item) => item.props('placeholder') === '终端用户')!
        .props('modelValue')
    ).toBe('9223372036854775806')
    expect(
      wrapper
        .findAllComponents(ElSelect)
        .find((item) => item.props('placeholder') === '选择智能体或团队')!
        .props('modelValue')
    ).toBe('agent:9223372036854775807')
    await button('发送').trigger('click')
    await flushPromises()
    expect(mocks.submit.mock.calls[0]![0]).toMatchObject({
      session_id: 'A',
      agent_id: '9223372036854775807',
      context: { end_user_id: '9223372036854775806' }
    })
  })
  it('团队历史恢复目标并用已有用户与团队参数筛选，不切换活动会话', async () => {
    mocks.detail.mockResolvedValue({
      session: { session_id: 'A', team_id: 'team-B' },
      end_user_id: '43',
      target_agent_id: null,
      closed: false
    })
    await setup()
    const scope = wrapper
      .findAllComponents(ElSelect)
      .find((item) => item.props('placeholder') === '历史范围')!
    scope.vm.$emit('update:modelValue', 'selected')
    scope.vm.$emit('change', 'selected')
    await flushPromises()
    expect(mocks.sessions).toHaveBeenLastCalledWith({
      endUserId: '43',
      teamId: 'team-B',
      agentId: undefined
    })
    expect(
      wrapper
        .findAllComponents(ElSelect)
        .find((item) => item.props('placeholder') === '历史会话')!
        .props('modelValue')
    ).toBe('A')
  })
  it('已关闭会话保留历史并禁止发送和目标修改，新会话可继续', async () => {
    mocks.detail.mockResolvedValue({
      session: { session_id: 'A' },
      end_user_id: '42',
      target_agent_id: '101',
      closed: true
    })
    await setup()
    expect(wrapper.text()).toContain('此会话已关闭')
    expect(button('发送').attributes('disabled')).toBeDefined()
    expect(button('关闭会话').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('A消息')
    await button('新会话').trigger('click')
    await flushPromises()
    expect(wrapper.text()).not.toContain('此会话已关闭')
    expect(mocks.submit).not.toHaveBeenCalled()
  })
  it('关闭成功后读取权威状态，并保留输入和历史，不自动再次提交', async () => {
    await setup()
    mocks.detail.mockResolvedValue({
      session: { session_id: 'A' },
      end_user_id: '42',
      target_agent_id: '101',
      closed: true
    })
    await button('关闭会话').trigger('click')
    await flushPromises()
    expect(mocks.close).toHaveBeenCalledExactlyOnceWith('A')
    expect(wrapper.text()).toContain('此会话已关闭')
    expect(wrapper.text()).toContain('A消息')
    expect(
      wrapper
        .findAllComponents(ElInput)
        .find((item) => item.props('placeholder') === '输入消息')!
        .props('modelValue')
    ).toBe('未发送草稿')
    expect(mocks.submit).not.toHaveBeenCalled()
  })

  it('关闭被权限契约明确拒绝后恢复可编辑状态，不把拒绝标记为未知', async () => {
    await setup()
    mocks.close.mockRejectedValue(
      new HttpClientError({ status: 403, code: 'FORBIDDEN', userMessage: '无权关闭会话' })
    )
    await button('关闭会话').trigger('click')
    await flushPromises()
    expect(wrapper.text()).not.toContain('关闭结果尚待核对')
    expect(button('发送').attributes('disabled')).toBeUndefined()
    expect(mocks.close).toHaveBeenCalledTimes(1)
    expect(mocks.submit).not.toHaveBeenCalled()
  })

  it('关闭响应丢失后只读核对，不重复写；确认权威关闭状态后展示已关闭', async () => {
    await setup()
    mocks.close.mockRejectedValue(new Error('连接中断'))
    await button('关闭会话').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('关闭结果尚待核对')
    expect(button('发送').attributes('disabled')).toBeDefined()
    expect(button('关闭会话').attributes('disabled')).toBeDefined()
    await button('读取会话状态').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('关闭结果尚待核对')
    expect(button('关闭会话').attributes('disabled')).toBeDefined()
    mocks.detail.mockResolvedValue({
      session: { session_id: 'A' },
      end_user_id: '42',
      target_agent_id: '101',
      closed: true
    })
    await button('读取会话状态').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('此会话已关闭')
    expect(mocks.close).toHaveBeenCalledTimes(1)
    expect(mocks.submit).not.toHaveBeenCalled()
  })
})

describe('待办回复沿已确认会话目标派发', () => {
  const cases = [
    ['Agent', 'user_input'],
    ['Team', 'user_input'],
    ['Agent', 'tool_input'],
    ['Team', 'tool_input']
  ] as const
  function waiting(type: string) {
    return {
      execution_id: 'wait-run',
      interaction_type: type,
      run_revision: '9007199254740993',
      tool_wait: { action_id: 'wait-action', generation: '7' },
      question: '请补充说明',
      ...(type === 'tool_input'
        ? {
            card: {
              type: 'tool_input',
              title: '工具补参',
              tool_wait: { action_id: 'wait-action', generation: '7' },
              requests: [
                {
                  id: 'request-1',
                  message: '填写工具文本',
                  requested_schema: { type: 'object', properties: { text: { type: 'string' } } }
                }
              ]
            }
          }
        : {})
    }
  }
  async function reply(target: string, type: string) {
    mocks.detail.mockResolvedValue({
      session: { session_id: 'A', ...(target === 'Team' ? { team_id: 'team-202' } : {}) },
      end_user_id: '42',
      target_agent_id: target === 'Team' ? null : '101',
      closed: false
    })
    mocks.history.mockResolvedValueOnce({ ...history('A'), pending_interactions: [waiting(type)] })
    await setup()
    if (type === 'user_input') {
      await wrapper.get('.pending-card textarea').setValue('原补充文本')
      await wrapper.get('.pending-card form').trigger('submit')
    } else {
      const form = wrapper.getComponent(ToolInputForm)
      await form.get('input').setValue('原工具文本')
      await form.trigger('submit')
    }
    await flushPromises()
  }

  it.each(cases)('%s的%s回复携带显式目标与新client身份，保原wait/CAS', async (target, type) => {
    mocks.submit.mockResolvedValue({ status: 'accepted', session_id: 'A' })
    await reply(target, type)
    expect(mocks.submit).toHaveBeenCalledOnce()
    const body = mocks.submit.mock.calls[0]![0]
    expect(body).toMatchObject({
      session_id: 'A',
      ...(target === 'Team' ? { team_id: 'team-202' } : { agent_id: '101' }),
      client_message_id: expect.any(String),
      context: { end_user_id: '42' },
      hitl_response: {
        execution_id: 'wait-run',
        run_revision: '9007199254740993',
        [type === 'user_input' ? 'human_input' : 'tool_input']: {
          wait: { action_id: 'wait-action', generation: '7' }
        }
      }
    })
    expect(body).not.toHaveProperty(target === 'Team' ? 'agent_id' : 'team_id')
    expect(
      wrapper
        .findAllComponents(ElInput)
        .find((field) => field.props('placeholder') === '输入消息')!
        .props('modelValue')
    ).toBe('未发送草稿')
  })

  it.each(cases)(
    '%s的%s UNKNOWN按原body重试，GET新等待/Run终态不证明原回复受理',
    async (target, type) => {
      mocks.submit
        .mockRejectedValueOnce(new TypeError('受理响应丢失'))
        .mockResolvedValue({ status: 'accepted', session_id: 'A' })
      await reply(target, type)
      const original = mocks.submit.mock.calls[0]![0]
      const newer = waiting(type)
      newer.tool_wait.generation = '8'
      if (newer.card) newer.card.tool_wait.generation = '8'
      const nextHistory = {
        ...history('A'),
        session_id: 'A',
        pending_interactions: [newer],
        activities: [{ node_id: 'wait-run', node_type: 'agent_runtime', status: 'completed' }]
      }
      mocks.history.mockResolvedValueOnce(nextHistory)
      await wrapper
        .findAll('button')
        .find((button) => button.text() === '核对原回复受理')!
        .trigger('click')
      await flushPromises()
      expect(wrapper.text()).toContain('本次回复受理结果尚未确认')
      let finish!: (value: typeof nextHistory) => void
      mocks.history.mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            finish = resolve
          })
      )
      await wrapper
        .findAll('button')
        .find((button) => button.text() === '重试原回复')!
        .trigger('click')
      await flushPromises()
      expect(mocks.submit.mock.calls[1]![0]).toBe(original)
      expect(wrapper.text()).not.toContain('本次回复受理结果尚未确认')
      if (type === 'tool_input')
        expect(wrapper.getComponent(ToolInputForm).props('pending').tool_wait?.generation).toBe('8')
      else expect(wrapper.find('.pending-card').exists()).toBe(true)
      expect(
        wrapper
          .findAllComponents(ElInput)
          .find((field) => field.props('placeholder') === '输入消息')!
          .props('modelValue')
      ).toBe('未发送草稿')
      finish(nextHistory)
      await flushPromises()
      expect(
        type === 'tool_input'
          ? wrapper.getComponent(ToolInputForm).props('pending').tool_wait?.generation
          : wrapper.find('.pending-card').exists()
      ).toBe(type === 'tool_input' ? '8' : true)
    }
  )

  it('human回复的精确user历史可只读确认，普通composer草稿保持', async () => {
    mocks.submit.mockRejectedValueOnce(new TypeError('受理响应丢失'))
    await reply('Agent', 'user_input')
    const original = mocks.submit.mock.calls[0]![0]
    mocks.history.mockResolvedValueOnce({
      ...history('A'),
      session_id: 'A',
      messages: [
        {
          role: 'user',
          message_id: original.client_message_id,
          content: '原补充文本',
          metadata: {}
        }
      ]
    })
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '核对原回复受理')!
      .trigger('click')
    await flushPromises()
    expect(mocks.submit).toHaveBeenCalledOnce()
    expect(wrapper.text()).not.toContain('本次回复受理结果尚未确认')
    expect(wrapper.find('.pending-card').exists()).toBe(false)
    expect(
      wrapper
        .findAllComponents(ElInput)
        .find((field) => field.props('placeholder') === '输入消息')!
        .props('modelValue')
    ).toBe('未发送草稿')
  })

  it.each(cases)('%s的%s明确拒绝保留回复草稿和原等待', async (target, type) => {
    mocks.submit.mockRejectedValueOnce(
      new HttpClientError({ status: 422, code: 'VALIDATION_ERROR', userMessage: '明确拒绝' })
    )
    await reply(target, type)
    expect(mocks.submit.mock.calls[0]![0]).toMatchObject({
      ...(target === 'Team' ? { team_id: 'team-202' } : { agent_id: '101' }),
      client_message_id: expect.any(String)
    })
    if (type === 'user_input')
      expect((wrapper.get('.pending-card textarea').element as HTMLTextAreaElement).value).toBe(
        '原补充文本'
      )
    else
      expect(
        (wrapper.getComponent(ToolInputForm).get('input').element as HTMLInputElement).value
      ).toBe('原工具文本')
    expect(wrapper.text()).toContain(type === 'user_input' ? '请补充说明' : '工具补参')
  })
})
