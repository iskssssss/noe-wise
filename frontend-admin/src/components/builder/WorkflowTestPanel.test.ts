import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { DOMWrapper, flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { ref } from 'vue'
import { ElButton, ElSelect } from 'element-plus'
import { HttpClientError } from '@skillforge/shared/utils/http'
import { clearFeedback } from '@skillforge/shared/feedback'
import { confirmPageLeave } from '@skillforge/shared/admin'
import { createPermissionDirective } from '@skillforge/shared/directives/permission'
import WorkflowTestPanel from './WorkflowTestPanel.vue'
import type {
  WorkflowTestOptions,
  WorkflowTestReceipt,
  WorkflowTestSource
} from '../../api/workflowTests'

const mocks = vi.hoisted(() => ({
  options: vi.fn(),
  create: vi.fn(),
  byRequest: vi.fn(),
  get: vi.fn(),
  close: vi.fn(),
  submit: vi.fn(),
  history: vi.fn(),
  trace: vi.fn()
}))
vi.mock('../../api', () => ({
  authSessionKey: 'test.auth.session',
  api: {
    workflowTests: {
      options: mocks.options,
      create: mocks.create,
      byRequest: mocks.byRequest,
      get: mocks.get,
      close: mocks.close
    },
    chat: { submit: mocks.submit, history: mocks.history },
    trajectories: { detail: mocks.trace }
  }
}))
vi.mock('@skillforge/shared/components/CodeEditor.vue', () => ({
  default: {
    props: ['modelValue', 'label', 'readonly'],
    emits: ['update:modelValue'],
    template:
      '<textarea :aria-label="label" :value="modelValue" :readonly="readonly" @input="$emit(\'update:modelValue\', $event.target.value)" />'
  }
}))
vi.mock('vue-router', async (original) => ({
  ...(await original<typeof import('vue-router')>()),
  onBeforeRouteLeave: vi.fn(),
  onBeforeRouteUpdate: vi.fn()
}))

const recommended = {
  maxBusinessModelCalls: 4,
  maxToolCalls: 8,
  maxKnowledgeCalls: 8,
  maxTotalTokens: 16000,
  maxWallTimeSeconds: 120
}
function options(
  source: WorkflowTestSource = { kind: 'draft', draftRevision: 7 }
): WorkflowTestOptions {
  return {
    source: {
      kind: source.kind,
      workflowId: 'flow',
      draftRevision: source.kind === 'draft' ? source.draftRevision : null,
      definitionVersionId: source.kind === 'publication' ? source.definitionVersionId : null,
      hash: source.kind === 'draft' ? 'draft-' + source.draftRevision : 'published-hash'
    },
    inputSchema: {
      type: 'object',
      properties: {
        user_input: { type: 'string', title: '消息来源', default: '默认测试消息' },
        customer: { type: 'string', title: '客户名称', default: '测试客户' },
        count: { type: 'integer', title: '处理数量', default: 0, minimum: 0, maximum: 100 },
        urgent: { type: 'boolean', title: '紧急处理', default: false }
      },
      required: ['user_input', 'count']
    },
    configSchema: [{ key: 'enabled', type: 'boolean', label: '启用', default: false }],
    capabilitySlots: [],
    budget: { recommended, maximum: { ...recommended, maxTotalTokens: 64000 } },
    restrictions: ['仅允许 TEST 环境的 READ_ONLY MCP 工具'],
    costNotice: '模型和知识调用按实际用量计费。工具声明只读不构成物理沙箱。'
  }
}
function receipt(fields: Partial<WorkflowTestReceipt> = {}): WorkflowTestReceipt {
  return {
    testSessionId: 'test:1',
    requestId: 'request-fixed',
    status: 'ready',
    source: options().source,
    sessionId: 'session:1',
    agentId: 'agent:1',
    endUserId: 'user:1',
    initialInputId: 'initial:1',
    initialMessage: '后端冻结原文🙂',
    expiresAt: '2026-10-24T12:00:00Z',
    runId: null,
    runStatus: null,
    closeStatus: 'open',
    blockingReason: null,
    ...fields
  }
}
const wrappers: VueWrapper[] = []
const permissions = ref(['foundation.workflows.create', 'foundation.workflows.update'])
function render(props: Record<string, unknown> = {}) {
  const wrapper = mount(WorkflowTestPanel, {
    props: {
      modelValue: true,
      workflowId: 'flow',
      draftRevision: 7,
      dirty: false,
      publications: [{ id: 'pub:1', label: '发布版本 1' }],
      ...props
    },
    attachTo: document.body,
    global: {
      directives: { permission: createPermissionDirective(() => permissions) },
      stubs: {
        RouterLink: {
          props: ['to'],
          template: '<a :href="to.path" :data-root="to.query.root_input_id"><slot /></a>'
        }
      }
    }
  })
  wrappers.push(wrapper)
  return wrapper
}
function dom() {
  return new DOMWrapper(document.body)
}
function button(wrapper: VueWrapper, text: string) {
  const found = wrapper.findAllComponents(ElButton).find((item) => item.text() === text)
  if (!found) throw Error('按钮不存在：' + text)
  return found
}
async function expectCleanLeave() {
  const leaving = confirmPageLeave()
  await flushPromises()
  const modal = document.querySelector('.el-message-box')
  try {
    expect(modal).toBeNull()
  } finally {
    // 负控失败时也完成真实确认，避免 pending 污染其他用例。
    if (modal)
      await dom()
        .findAll('.el-message-box__btns button')
        .find((item) => item.text() === '继续编辑')!
        .trigger('click')
    await leaving
  }
  expect(await leaving).toBe(true)
  const unload = new Event('beforeunload', { cancelable: true })
  window.dispatchEvent(unload)
  expect(unload.defaultPrevented).toBe(false)
}
function select(wrapper: VueWrapper, name: string) {
  const found = wrapper
    .findAllComponents(ElSelect)
    .find((item) => item.find(`input[aria-label="${name}"]`).exists())
  if (!found) throw Error('选择器不存在：' + name)
  return found
}
beforeEach(() => {
  vi.resetAllMocks()
  permissions.value = ['foundation.workflows.create', 'foundation.workflows.update']
  sessionStorage.clear()
  localStorage.setItem('test.auth.session', 'actor')
  vi.spyOn(crypto, 'randomUUID').mockReturnValue(
    'request-fixed' as ReturnType<typeof crypto.randomUUID>
  )
  mocks.options.mockImplementation(async (_workflow, source) => options(source))
  mocks.create.mockResolvedValue(receipt())
  mocks.get.mockResolvedValue(receipt({ status: 'running', runId: 'run:1', runStatus: 'running' }))
  mocks.byRequest.mockResolvedValue(receipt())
  mocks.close.mockResolvedValue(receipt({ closeStatus: 'cancelling', status: 'cancelling' }))
  mocks.submit.mockResolvedValue({ session_id: 'session:1', status: 'accepted' })
  mocks.history.mockResolvedValue({
    session_id: 'session:1',
    event_cursor: null,
    messages: [],
    pending_interactions: [],
    collaboration: {}
  })
  mocks.trace.mockResolvedValue({
    termination: null,
    summary: { session_id: 'session:1' },
    usage_summary: {
      calls: 1,
      tokens: null,
      prompt_tokens: null,
      completion_tokens: null,
      amount_by_currency: {},
      unknown_usage_calls: 1,
      cost_coverage: {
        status: 'UNKNOWN',
        total_events: 0,
        priced_events: 0,
        unconfirmed_events: 0,
        missing_model_attempts: 1
      }
    },
    diagnostics: {}
  })
})
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  clearFeedback()
  document.body.innerHTML = ''
  vi.restoreAllMocks()
  vi.useRealTimers()
})

describe('原测试消息历史按需续页', () => {
  function restoreTest(status = 'completed') {
    sessionStorage.setItem(
      'noewise:workflow-test:' + JSON.stringify(['actor', 'flow']),
      JSON.stringify({
        requestId: 'request-fixed',
        testSessionId: 'test:1',
        initial: 'submitted',
        closing: false
      })
    )
    mocks.get.mockResolvedValue(receipt({ status, runId: 'run:1', runStatus: status }))
  }
  function page(
    first: number,
    last: number,
    hasMore: boolean,
    cursor?: string,
    pendingTitle?: string
  ) {
    return {
      session_id: 'session:1',
      event_cursor: null,
      has_more: hasMore,
      next_before_message_id: cursor,
      messages: Array.from({ length: last - first + 1 }, (_, index) => ({
        message_id: `message:${first + index}`,
        role: 'user',
        content: `历史消息${first + index}`,
        created_at: '2026-10-29T00:00:00Z'
      })),
      pending_interactions: pendingTitle
        ? [
            {
              interaction_type: 'workflow_input',
              execution_id: 'run:1',
              card: { card_id: pendingTitle, type: 'form', title: pendingTitle }
            }
          ]
        : [],
      collaboration: {}
    }
  }
  it.each([false, true])(
    '关闭后新窗口与旧消息不相交时采用可续页的新窗口，旧空=%s',
    async (empty) => {
      restoreTest()
      mocks.history.mockResolvedValueOnce(empty ? page(1, 0, false) : page(1, 10, false))
      const wrapper = render()
      await flushPromises()
      expect(document.querySelectorAll('.el-message')).toHaveLength(0)
      await wrapper.setProps({ modelValue: false })
      mocks.history.mockResolvedValueOnce(page(21, 50, true, 'message:21'))
      await wrapper.setProps({ modelValue: true })
      await flushPromises()
      const latest = dom().findAll('[aria-label="测试消息记录"] article')
      expect(latest).toHaveLength(30)
      expect(latest[0]!.text()).toContain('历史消息21')
      expect(document.querySelectorAll('.el-message')).toHaveLength(empty ? 0 : 1)
      if (!empty)
        expect(document.querySelector('.el-message')?.textContent).toContain('已更新为最新消息')
      mocks.history.mockResolvedValueOnce(page(1, 20, false))
      await button(wrapper, '加载较早测试消息').trigger('click')
      await flushPromises()
      expect(mocks.history).toHaveBeenLastCalledWith('session:1', 'message:21')
      const all = dom().findAll('[aria-label="测试消息记录"] article')
      expect(all).toHaveLength(50)
      expect(all.map((item) => item.find('div.markdown-content').text())).toEqual(
        Array.from({ length: 50 }, (_, index) => `历史消息${index + 1}`)
      )
      expect(dom().findAll('[aria-label="测试消息记录"] button')).toHaveLength(0)
      expect(mocks.create).not.toHaveBeenCalled()
      expect(mocks.submit).not.toHaveBeenCalled()
      expect(mocks.close).not.toHaveBeenCalled()
    }
  )
  it('较早页失败保留原游标，刷新合并消息并保留最早页游标与最新待办', async () => {
    restoreTest()
    mocks.history.mockResolvedValueOnce(page(61, 90, true, 'message:61', '当前待办'))
    const wrapper = render()
    await flushPromises()
    expect(dom().findAll('[aria-label="测试消息记录"] article')).toHaveLength(30)
    mocks.history.mockRejectedValueOnce(new Error('较早消息读取失败'))
    await button(wrapper, '加载较早测试消息').trigger('click')
    await flushPromises()
    expect(dom().text()).toContain('较早消息读取失败')
    expect(dom().findAll('[aria-label="测试消息记录"] article')).toHaveLength(30)
    expect(mocks.history).toHaveBeenLastCalledWith('session:1', 'message:61')
    mocks.history.mockResolvedValueOnce(page(31, 60, true, 'message:31', '旧页过期待办'))
    await button(wrapper, '加载较早测试消息').trigger('click')
    await flushPromises()
    expect(mocks.history).toHaveBeenLastCalledWith('session:1', 'message:61')
    expect(dom().findAll('[aria-label="测试消息记录"] article')).toHaveLength(60)
    expect(dom().text()).toContain('当前待办')
    expect(dom().text()).not.toContain('旧页过期待办')
    mocks.history.mockResolvedValueOnce(page(62, 91, true, 'message:62', '刷新后的待办'))
    await button(wrapper, '查询测试状态').trigger('click')
    await flushPromises()
    const messages = dom().findAll('[aria-label="测试消息记录"] article')
    expect(messages).toHaveLength(61)
    expect(messages[0]!.text()).toContain('历史消息31')
    expect(messages.at(-1)!.text()).toContain('历史消息91')
    expect(dom().text()).toContain('刷新后的待办')
    expect(dom().text()).not.toContain('当前待办')
    mocks.history.mockResolvedValueOnce(page(1, 30, false))
    await button(wrapper, '加载较早测试消息').trigger('click')
    await flushPromises()
    expect(mocks.history).toHaveBeenLastCalledWith('session:1', 'message:31')
    expect(dom().findAll('[aria-label="测试消息记录"] article')).toHaveLength(91)
    expect(dom().findAll('[aria-label="测试消息记录"] button')).toHaveLength(0)
    mocks.history.mockResolvedValueOnce(page(63, 92, true, 'message:63', '最终待办'))
    await button(wrapper, '查询测试状态').trigger('click')
    await flushPromises()
    expect(dom().findAll('[aria-label="测试消息记录"] article')).toHaveLength(92)
    expect(dom().findAll('[aria-label="测试消息记录"] button')).toHaveLength(0)
    expect(mocks.create).not.toHaveBeenCalled()
    expect(mocks.submit).not.toHaveBeenCalled()
    expect(mocks.close).not.toHaveBeenCalled()
  })
  it.each(['close', 'source'] as const)(
    '较早页在%s后迟到不能覆盖重新读取的当前历史或待办',
    async (change) => {
      restoreTest()
      mocks.history.mockResolvedValueOnce(page(2, 31, true, 'message:2', '原待办'))
      const wrapper = render()
      await flushPromises()
      let finish!: (value: ReturnType<typeof page>) => void
      mocks.history.mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            finish = resolve
          })
      )
      await button(wrapper, '加载较早测试消息').trigger('click')
      await flushPromises()
      mocks.history.mockResolvedValueOnce(page(3, 32, true, 'message:3', '重新读取的待办'))
      if (change === 'close') {
        await wrapper.setProps({ modelValue: false })
        await wrapper.setProps({ modelValue: true })
      } else await wrapper.setProps({ draftRevision: 8 })
      await flushPromises()
      finish(page(1, 1, false, undefined, '迟到页的过期待办'))
      await flushPromises()
      const messages = dom().findAll('[aria-label="测试消息记录"] article')
      expect(messages).toHaveLength(31)
      expect(messages[0]!.text()).toContain('历史消息2')
      expect(messages.at(-1)!.text()).toContain('历史消息32')
      expect(dom().text()).toContain('重新读取的待办')
      expect(dom().text()).not.toContain('迟到页的过期待办')
      expect(button(wrapper, '加载较早测试消息').props('disabled')).toBe(false)
      expect(mocks.create).not.toHaveBeenCalled()
      expect(mocks.submit).not.toHaveBeenCalled()
      expect(mocks.close).not.toHaveBeenCalled()
    }
  )
  it('运行中较早页读取越过原轮询时点，结束后继续安排原测试状态轮询', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    restoreTest('running')
    mocks.history.mockResolvedValueOnce(page(2, 31, true, 'message:2'))
    const wrapper = render()
    await flushPromises()
    let finish!: (value: ReturnType<typeof page>) => void
    mocks.history.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve
        })
    )
    await button(wrapper, '加载较早测试消息').trigger('click')
    await vi.advanceTimersByTimeAsync(3000)
    expect(mocks.get).toHaveBeenCalledTimes(1)
    finish(page(1, 1, false))
    await flushPromises()
    mocks.history.mockResolvedValueOnce(page(3, 32, true, 'message:3'))
    await vi.advanceTimersByTimeAsync(2999)
    expect(mocks.get).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(1)
    await flushPromises()
    expect(mocks.get).toHaveBeenCalledTimes(2)
    expect(mocks.get).toHaveBeenLastCalledWith('test:1', expect.objectContaining({ silent: true }))
    expect(dom().findAll('[aria-label="测试消息记录"] article')).toHaveLength(32)
    expect(mocks.create).not.toHaveBeenCalled()
    expect(mocks.submit).not.toHaveBeenCalled()
    expect(mocks.close).not.toHaveBeenCalled()
  })
})

describe('真实Workflow测试面板边界', () => {
  it('测试参数显示数值范围且保留默认值', async () => {
    const wrapper = render()
    await flushPromises()
    expect(dom().text()).toContain('最小值 0 · 最大值 100')
    const input = dom().get<HTMLInputElement>('input[aria-label="处理数量"]')
    expect(input.element.value).toBe('0')
    expect(document.getElementById(input.attributes('aria-describedby')!)?.textContent).toContain(
      '最大值 100'
    )
    await button(wrapper, 'JSON 输入').trigger('click')
    await flushPromises()
    expect(
      dom().get<HTMLTextAreaElement>('textarea[aria-label="测试参数 JSON"]').element.value
    ).toContain('"count": 0')
  })
  it('未修改默认表单或仅切换JSON模式不拦截真实离页，改回原消息也恢复干净', async () => {
    const wrapper = render()
    await flushPromises()
    await expectCleanLeave()
    await button(wrapper, 'JSON 输入').trigger('click')
    await flushPromises()
    await expectCleanLeave()
    await dom().get('textarea[aria-label="测试消息"]').setValue('修改消息')
    await dom().get('textarea[aria-label="测试消息"]').setValue('默认测试消息')
    await expectCleanLeave()
  })

  it.each(['参数', '安装配置', '预算', 'JSON'])(
    '空消息下修改%s受到真实离页与刷新保护，取消离开保留内容',
    async (field) => {
      mocks.options.mockImplementation(async (_workflow, source) => {
        const value = options(source)
        value.inputSchema = {
          type: 'object',
          properties: { customer: { type: 'string', title: '客户名称', default: '原客户' } }
        }
        value.configSchema = [{ key: 'note', type: 'string', label: '说明', default: '原说明' }]
        return value
      })
      const wrapper = render()
      await flushPromises()
      if (field === 'JSON') {
        await button(wrapper, 'JSON 输入').trigger('click')
        await dom().get('textarea[aria-label="测试参数 JSON"]').setValue('{"customer":"新客户"}')
      } else
        await dom()
          .get(
            field === '参数'
              ? 'input[aria-label="客户名称"]'
              : field === '安装配置'
                ? 'input[aria-label="安装参数 说明"]'
                : 'input[aria-label="Token 总量上限"]'
          )
          .setValue(field === '预算' ? 17000 : '新内容')
      const unload = new Event('beforeunload', { cancelable: true })
      window.dispatchEvent(unload)
      expect(unload.defaultPrevented).toBe(true)
      const leaving = confirmPageLeave()
      await flushPromises()
      expect(document.querySelector('.el-message-box')?.textContent).toContain(
        '当前页面有未保存内容'
      )
      await dom()
        .findAll('.el-message-box__btns button')
        .find((item) => item.text() === '继续编辑')!
        .trigger('click')
      expect(await leaving).toBe(false)
      expect(mocks.create).not.toHaveBeenCalled()
      expect(mocks.submit).not.toHaveBeenCalled()
      await wrapper.setProps({ modelValue: false })
      await wrapper.setProps({ modelValue: true })
      await flushPromises()
      const retained = new Event('beforeunload', { cancelable: true })
      window.dispatchEvent(retained)
      expect(retained.defaultPrevented).toBe(true)
    }
  )

  it('唯一必选能力自动填充不算修改，改选能力受保护而恢复默认后干净', async () => {
    const candidates = ['one', 'two'].map((id) => ({
      capability: { type: 'TOOL' as const, capabilityId: 'tool', versionId: id },
      toolDeploymentId: id,
      toolDeploymentRevision: 1,
      knowledgeRevision: null,
      label: id
    }))
    mocks.options.mockImplementation(async (_workflow, source) => ({
      ...options(source),
      capabilitySlots: [
        { slot: 'tool', type: 'tool', required: true, candidates: candidates.slice(0, 1) }
      ]
    }))
    const wrapper = render()
    await flushPromises()
    const original = select(wrapper, '测试能力 tool').props('modelValue')
    await expectCleanLeave()
    mocks.options.mockImplementation(async (_workflow, source) => ({
      ...options(source),
      capabilitySlots: [{ slot: 'tool', type: 'tool', required: true, candidates }]
    }))
    await wrapper.setProps({ modelValue: false })
    await wrapper.setProps({ modelValue: true })
    await flushPromises()
    const picker = select(wrapper, '测试能力 tool')
    const second = picker
      .findAllComponents({ name: 'ElOption' })
      .find((option) => option.props('label') === 'two')!
    picker.vm.$emit('update:modelValue', second.props('value'))
    await flushPromises()
    const unload = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(unload)
    expect(unload.defaultPrevented).toBe(true)
    picker.vm.$emit('update:modelValue', original)
    await flushPromises()
    await expectCleanLeave()
  })

  it('其他来源缓存草稿也保护离页，来源切回与重读不覆盖编辑基线', async () => {
    mocks.options.mockImplementation(async (_workflow, source) => {
      const value = options(source)
      value.inputSchema = { type: 'object', properties: {} }
      return value
    })
    const wrapper = render()
    await flushPromises()
    await dom().get('textarea[aria-label="测试消息"]').setValue('草稿A')
    select(wrapper, '测试目标').vm.$emit('update:modelValue', 'pub:1')
    await flushPromises()
    const unload = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(unload)
    expect(unload.defaultPrevented).toBe(true)
    select(wrapper, '测试目标').vm.$emit('update:modelValue', 'draft')
    await flushPromises()
    expect(dom().get<HTMLTextAreaElement>('textarea[aria-label="测试消息"]').element.value).toBe(
      '草稿A'
    )
    await wrapper.setProps({ draftRevision: 8 })
    await flushPromises()
    await dom().get('textarea[aria-label="测试消息"]').setValue('')
    await expectCleanLeave()
  })

  it('JSON仅格式与键顺序变化不算修改，无效JSON受保护，改回实际默认值即干净', async () => {
    const wrapper = render()
    await flushPromises()
    await button(wrapper, 'JSON 输入').trigger('click')
    const json = dom().get('textarea[aria-label="测试参数 JSON"]')
    await json.setValue('{ "urgent": false, "count": 0, "customer": "测试客户" }')
    await expectCleanLeave()
    await json.setValue('{')
    const unload = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(unload)
    expect(unload.defaultPrevented).toBe(true)
    await json.setValue('{"customer":"测试客户","count":0,"urgent":false}')
    await expectCleanLeave()
  })

  it.each(['字段', 'JSON'])(
    '关闭重开后选项读取失败仍保护%s参数草稿，而不使用加载暂态切换格式',
    async (mode) => {
      mocks.options.mockImplementation(async (_workflow, source) => {
        const value = options(source)
        value.inputSchema = {
          type: 'object',
          properties: { customer: { type: 'string', title: '客户名称', default: '原客户' } }
        }
        return value
      })
      const wrapper = render()
      await flushPromises()
      if (mode === '字段') await dom().get('input[aria-label="客户名称"]').setValue('修改客户')
      else {
        await button(wrapper, 'JSON 输入').trigger('click')
        await dom().get('textarea[aria-label="测试参数 JSON"]').setValue('{"customer":"修改客户"}')
      }
      await wrapper.setProps({ modelValue: false })
      mocks.options.mockRejectedValueOnce(new Error('选项暂时无法读取'))
      await wrapper.setProps({ modelValue: true })
      await flushPromises()
      const unload = new Event('beforeunload', { cancelable: true })
      window.dispatchEvent(unload)
      expect(unload.defaultPrevented).toBe(true)
      const leaving = confirmPageLeave()
      await flushPromises()
      const cancel = dom()
        .findAll('.el-message-box__btns button')
        .find((item) => item.text() === '继续编辑')
      expect(cancel?.exists()).toBe(true)
      await cancel!.trigger('click')
      expect(await leaving).toBe(false)
      await button(wrapper, '重新读取测试选项').trigger('click')
      await flushPromises()
      if (mode === '字段')
        expect(dom().get<HTMLInputElement>('input[aria-label="客户名称"]').element.value).toBe(
          '修改客户'
        )
      else
        expect(
          dom().get<HTMLTextAreaElement>('textarea[aria-label="测试参数 JSON"]').element.value
        ).toBe('{"customer":"修改客户"}')
    }
  )

  it('选项重读失败后重试及修订更新只同步程序默认值，不吞掉已有编辑', async () => {
    let defaultMessage = '初始默认消息'
    mocks.options.mockImplementation(async (_workflow, source) => {
      const value = options(source)
      value.inputSchema = {
        type: 'object',
        properties: { user_input: { type: 'string', default: defaultMessage } }
      }
      value.configSchema = [{ key: 'note', type: 'string', label: '说明', default: '原说明' }]
      return value
    })
    const wrapper = render()
    await flushPromises()
    await dom().get('input[aria-label="安装参数 说明"]').setValue('编辑内容')
    await wrapper.setProps({ modelValue: false })
    mocks.options.mockRejectedValueOnce(new Error('暂时无法读取选项'))
    await wrapper.setProps({ modelValue: true })
    await flushPromises()
    await button(wrapper, '重新读取测试选项').trigger('click')
    await flushPromises()
    expect(dom().get<HTMLInputElement>('input[aria-label="安装参数 说明"]').element.value).toBe(
      '编辑内容'
    )
    const unload = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(unload)
    expect(unload.defaultPrevented).toBe(true)
    defaultMessage = '新修订默认消息'
    await wrapper.setProps({ draftRevision: 8 })
    await flushPromises()
    expect(dom().get<HTMLTextAreaElement>('textarea[aria-label="测试消息"]').element.value).toBe(
      '新修订默认消息'
    )
    await dom().get('input[aria-label="安装参数 说明"]').setValue('原说明')
    await expectCleanLeave()
  })

  it.each([false, true])(
    '明确创建回执仅清理已提交来源，其他来源草稿保留=%s',
    async (otherDraft) => {
      const wrapper = render()
      await flushPromises()
      if (otherDraft) await dom().get('textarea[aria-label="测试消息"]').setValue('其他来源草稿')
      select(wrapper, '测试目标').vm.$emit('update:modelValue', 'pub:1')
      await flushPromises()
      await dom().get('textarea[aria-label="测试消息"]').setValue('已提交消息')
      mocks.create.mockResolvedValue(
        receipt({ source: options({ kind: 'publication', definitionVersionId: 'pub:1' }).source })
      )
      await dom().get('form').trigger('submit')
      await flushPromises()
      expect(mocks.create).toHaveBeenCalledOnce()
      const unload = new Event('beforeunload', { cancelable: true })
      window.dispatchEvent(unload)
      expect(unload.defaultPrevented).toBe(otherDraft)
      if (!otherDraft) await expectCleanLeave()
    }
  )

  it('创建UNKNOWN及查询未找到不清草稿，原请求回执确认后才清理', async () => {
    render()
    await flushPromises()
    await dom().get('textarea[aria-label="测试消息"]').setValue('原请求消息')
    mocks.create.mockRejectedValueOnce(new HttpClientError({ status: 408, userMessage: '超时' }))
    mocks.byRequest.mockRejectedValueOnce(
      new HttpClientError({ status: 404, userMessage: '未找到' })
    )
    await dom().get('form').trigger('submit')
    await flushPromises()
    const unknown = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(unknown)
    expect(unknown.defaultPrevented).toBe(true)
    await dom()
      .findAll('button')
      .find((item) => item.text() === '查询测试状态')!
      .trigger('click')
    await flushPromises()
    const notFound = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(notFound)
    expect(notFound.defaultPrevented).toBe(true)
    await dom()
      .findAll('button')
      .find((item) => item.text() === '查询测试状态')!
      .trigger('click')
    await flushPromises()
    await expectCleanLeave()
    expect(mocks.create).toHaveBeenCalledOnce()
    expect(mocks.submit).not.toHaveBeenCalled()
  })

  it.each(['string', 'textarea'])(
    '测试安装保留主动清空的可选%s，而未修改字段保留默认值',
    async (type) => {
      mocks.options.mockResolvedValue({
        ...options(),
        configSchema: [
          { key: 'cleared', type, label: '清空内容', default: '原默认内容' },
          { key: 'untouched', type, label: '保留内容', default: '保留默认内容' }
        ]
      })
      render()
      await flushPromises()
      const input = dom().get('[aria-label="安装参数 清空内容"]')
      expect((input.element as HTMLInputElement).value).toBe('原默认内容')
      await input.setValue('')
      await dom().get('form').trigger('submit')
      await flushPromises()
      expect(mocks.create).toHaveBeenCalledOnce()
      expect(mocks.create.mock.calls[0]![0].config).toEqual({
        cleared: '',
        untouched: '保留默认内容'
      })
    }
  )

  it.each(['', '   '])('测试安装的必填文本清空或空白时阻断创建：%j', async (value) => {
    mocks.options.mockResolvedValue({
      ...options(),
      configSchema: [
        {
          key: 'required',
          type: 'string',
          label: '必填内容',
          required: true,
          default: '原默认内容'
        }
      ]
    })
    render()
    await flushPromises()
    await dom().get('[aria-label="安装参数 必填内容"]').setValue(value)
    await dom().get('form').trigger('submit')
    await flushPromises()
    expect(mocks.create).not.toHaveBeenCalled()
    expect(mocks.submit).not.toHaveBeenCalled()
  })

  it('源Schema的测试消息只出现一次，默认0/false和冻结参数进入创建，正式chat只发送回执身份与正文', async () => {
    render()
    await flushPromises()
    expect(dom().findAll('textarea[aria-label="测试消息"]')).toHaveLength(1)
    expect(dom().find('input[aria-label="消息来源"]').exists()).toBe(false)
    expect(dom().get<HTMLTextAreaElement>('textarea[aria-label="测试消息"]').element.value).toBe(
      '默认测试消息'
    )
    await dom().get('form').trigger('submit')
    await flushPromises()
    expect(mocks.create).toHaveBeenCalledOnce()
    expect(mocks.create.mock.calls[0]![0]).toMatchObject({
      requestId: 'request-fixed',
      source: { kind: 'draft', draftRevision: 7 },
      userInput: '默认测试消息',
      parameters: { customer: '测试客户', count: 0, urgent: false },
      config: { enabled: false },
      budget: recommended
    })
    expect(mocks.create.mock.calls[0]![0].parameters).not.toHaveProperty('user_input')
    expect(mocks.submit).toHaveBeenCalledExactlyOnceWith({
      session_id: 'session:1',
      agent_id: 'agent:1',
      content: [{ type: 'text', text: '后端冻结原文🙂' }],
      client_message_id: 'initial:1',
      context: { end_user_id: 'user:1' },
      references: []
    })
    expect(mocks.trace.mock.calls.at(-1)![0]).toEqual({
      session_id: 'session:1',
      root_input_id: 'initial:1'
    })
    expect(dom().text()).toContain('未知')
    expect(dom().text()).toContain('金额未提供')
    expect(dom().text()).toContain('费用尚未确认')
    const link = dom().get('.test-trace-link')
    expect(link.text()).toBe('查看完整执行追踪与用量（新标签页）')
    expect(link.attributes('href')).toBe('/admin/chat/sessions/session%3A1/trace')
    expect(link.attributes('data-root')).toBe('initial:1')
    expect(link.attributes('target')).toBe('_blank')
    expect(link.attributes('rel')).toBe('noopener noreferrer')
    expect(
      dom()
        .findAll('.el-descriptions__cell')
        .some((cell) => cell.text() === '用量未知调用')
    ).toBe(true)
  })
  it('真实费用精确显示分转元的负调整及亚分，大整数与部分未确认不被截断', async () => {
    mocks.trace.mockResolvedValue({
      summary: { session_id: 'session:1' },
      usage_summary: {
        calls: 2,
        tokens: null,
        prompt_tokens: null,
        completion_tokens: null,
        amount_by_currency: { CNY: '-9007199254740993123456789.125' },
        unknown_usage_calls: 1,
        cost_coverage: {
          status: 'PARTIAL',
          total_events: 2,
          priced_events: 0,
          unconfirmed_events: 2,
          missing_model_attempts: 1
        }
      },
      diagnostics: {}
    })
    render()
    await flushPromises()
    await dom().get('form').trigger('submit')
    await flushPromises()
    expect(dom().text()).toContain('CNY -90,071,992,547,409,931,234,567.89125')
    expect(dom().text()).toContain('部分费用未确认')
    expect(dom().text()).toContain('2 条费用待确认')
    expect(dom().text()).toContain('1 次模型调用缺少费用记录')
    expect(dom().text()).toContain('用量未知调用')
    expect(dom().text()).toContain('未知')
  })

  it.each([
    {
      amount: { CNY: '0' },
      status: 'COMPLETE',
      events: 1,
      priced: 1,
      missing: 0,
      text: 'CNY 0.00',
      notice: '全部费用已确认'
    },
    {
      amount: {},
      status: 'NO_COSTS',
      events: 0,
      priced: 0,
      missing: 0,
      text: '金额未提供',
      notice: '暂无费用记录'
    },
    {
      amount: {},
      status: 'UNKNOWN',
      events: 0,
      priced: 0,
      missing: 1,
      text: '金额未提供',
      notice: '费用尚未确认'
    }
  ])(
    '费用状态$status保留已知零与没有金额的区别',
    async ({ amount, status, events, priced, missing, text, notice }) => {
      mocks.trace.mockResolvedValue({
        summary: { session_id: 'session:1' },
        usage_summary: {
          calls: 1,
          tokens: null,
          prompt_tokens: null,
          completion_tokens: null,
          amount_by_currency: amount,
          unknown_usage_calls: 0,
          cost_coverage: {
            status,
            total_events: events,
            priced_events: priced,
            unconfirmed_events: 0,
            missing_model_attempts: missing
          }
        }
      })
      render()
      await flushPromises()
      await dom().get('form').trigger('submit')
      await flushPromises()
      expect(dom().text()).toContain(text)
      expect(dom().text()).toContain(notice)
      if (!Object.keys(amount).length) expect(dom().text()).not.toContain('CNY 0.00')
    }
  )
  it('新建工作流可在测试面板内保存草稿并加载该修订的测试选项', async () => {
    const wrapper = render({ draftRevision: undefined, dirty: true })
    await flushPromises()
    expect(dom().text()).toContain('请先保存工作流草稿，再配置测试')
    expect(mocks.options).not.toHaveBeenCalled()
    permissions.value = ['foundation.workflows.update']
    await flushPromises()
    expect(
      dom()
        .findAll('button')
        .some((value) => value.text() === '先保存草稿')
    ).toBe(false)
    permissions.value = ['foundation.workflows.create', 'foundation.workflows.update']
    await flushPromises()
    await button(wrapper, '先保存草稿').trigger('click')
    expect(wrapper.emitted('save')).toHaveLength(1)
    await wrapper.setProps({ draftRevision: 7, dirty: false })
    await flushPromises()
    expect(mocks.options.mock.calls.at(-1)![1]).toEqual({ kind: 'draft', draftRevision: 7 })
    expect(button(wrapper, '创建并测试').props('disabled')).toBe(false)
  })
  it('草稿dirty先保存但不丢消息和参数；显式发布版本不被本地dirty锁住', async () => {
    const wrapper = render({ dirty: true })
    await flushPromises()
    await dom().get('textarea[aria-label="测试消息"]').setValue('未保存测试输入')
    expect(button(wrapper, '创建并测试').props('disabled')).toBe(true)
    await button(wrapper, '先保存草稿').trigger('click')
    expect(wrapper.emitted('save')).toHaveLength(1)
    await wrapper.setProps({ draftRevision: 8, dirty: false })
    await flushPromises()
    expect(dom().get<HTMLTextAreaElement>('textarea[aria-label="测试消息"]').element.value).toBe(
      '未保存测试输入'
    )
    expect(mocks.options.mock.calls.at(-1)![1]).toEqual({ kind: 'draft', draftRevision: 8 })
    await wrapper.setProps({ dirty: true })
    select(wrapper, '测试目标').vm.$emit('update:modelValue', 'pub:1')
    await flushPromises()
    expect(button(wrapper, '创建并测试').props('disabled')).toBe(false)
    expect(dom().text()).toContain('不包含未保存的草稿修改')
  })
  it('一次保存并测试使用新修订，保留消息并只提交一次', async () => {
    const saveDraft = vi.fn(async () => {
      await wrapper.setProps({ draftRevision: 8, dirty: false })
      return 8
    })
    mocks.create.mockImplementation(async (input) =>
      receipt({ source: options(input.source).source })
    )
    const wrapper = render({ dirty: true, saveDraft, identityVersion: 1 })
    await flushPromises()
    await dom().get('textarea[aria-label="测试消息"]').setValue('确定的测试消息')
    await button(wrapper, '保存并测试').trigger('click')
    await flushPromises()
    expect(saveDraft).toHaveBeenCalledOnce()
    expect(mocks.create).toHaveBeenCalledOnce()
    expect(mocks.create.mock.calls[0]![0]).toMatchObject({
      source: { kind: 'draft', draftRevision: 8 },
      userInput: '确定的测试消息'
    })
    expect(mocks.submit).toHaveBeenCalledOnce()
  })
  it('保存后测试选项变化时保留消息并要求重新核对', async () => {
    const saveDraft = vi.fn(async () => {
      await wrapper.setProps({ draftRevision: 8, dirty: false })
      return 8
    })
    mocks.options.mockImplementation(async (_workflow, source) => ({
      ...options(source),
      costNotice: source.draftRevision === 8 ? '费用已变化' : '原费用'
    }))
    const wrapper = render({ dirty: true, saveDraft, identityVersion: 1 })
    await flushPromises()
    await dom().get('textarea[aria-label="测试消息"]').setValue('保留原消息')
    await button(wrapper, '保存并测试').trigger('click')
    await flushPromises()
    expect(mocks.create).not.toHaveBeenCalled()
    expect(dom().text()).toContain('测试选项已变化')
    expect(dom().get<HTMLTextAreaElement>('textarea[aria-label="测试消息"]').element.value).toBe(
      '保留原消息'
    )
  })
  it('保存后选项读取失败时不创建测试，保留测试消息', async () => {
    let failRead = true
    const saveDraft = vi.fn(async () => {
      await wrapper.setProps({ draftRevision: 8, dirty: false })
      return 8
    })
    mocks.options.mockImplementation(async (_workflow, source) => {
      if (failRead && source.kind === 'draft' && source.draftRevision === 8)
        throw Error('选项读取失败')
      return options(source)
    })
    const wrapper = render({ dirty: true, saveDraft, identityVersion: 1 })
    await flushPromises()
    await dom().get('textarea[aria-label="测试消息"]').setValue('保留原消息')
    await button(wrapper, '保存并测试').trigger('click')
    await flushPromises()
    expect(mocks.create).not.toHaveBeenCalled()
    expect(dom().text()).toContain('选项读取失败')
    expect(mocks.submit).not.toHaveBeenCalled()
    failRead = false
    await button(wrapper, '重新读取测试选项').trigger('click')
    await flushPromises()
    expect(dom().get<HTMLTextAreaElement>('textarea[aria-label="测试消息"]').element.value).toBe(
      '保留原消息'
    )
  })
  it('保存期间身份变化不继续原测试意图', async () => {
    let complete!: (revision: number) => void
    const saveDraft = vi.fn(
      () =>
        new Promise<number>((resolve) => {
          complete = resolve
        })
    )
    const wrapper = render({ dirty: true, saveDraft, identityVersion: 1 })
    await flushPromises()
    await button(wrapper, '保存并测试').trigger('click')
    localStorage.setItem('test.auth.session', 'other')
    await wrapper.setProps({ identityVersion: 2, draftRevision: 8, dirty: false })
    complete(8)
    await flushPromises()
    expect(mocks.create).not.toHaveBeenCalled()
    expect(mocks.submit).not.toHaveBeenCalled()
  })
  it('保存未确认时不创建测试并说明后续处理', async () => {
    const wrapper = render({
      dirty: true,
      saveDraft: vi.fn().mockResolvedValue(undefined),
      identityVersion: 1
    })
    await flushPromises()
    await button(wrapper, '保存并测试').trigger('click')
    await flushPromises()
    expect(mocks.create).not.toHaveBeenCalled()
    expect(dom().text()).toContain('草稿保存尚未确认')
  })
  it('保存过程中关闭并重新打开面板，不继续先前测试意图', async () => {
    let complete!: (revision: number) => void
    const saveDraft = vi.fn(
      () =>
        new Promise<number>((resolve) => {
          complete = resolve
        })
    )
    const wrapper = render({ dirty: true, saveDraft, identityVersion: 1 })
    await flushPromises()
    await button(wrapper, '保存并测试').trigger('click')
    await wrapper.setProps({ modelValue: false })
    await wrapper.setProps({ modelValue: true, draftRevision: 8, dirty: false })
    complete(8)
    await flushPromises()
    expect(mocks.create).not.toHaveBeenCalled()
  })
  it('测试创建返回前切换身份时保留原请求且不派发消息', async () => {
    let complete!: (value: WorkflowTestReceipt) => void
    mocks.create.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          complete = resolve
        })
    )
    const wrapper = render({ identityVersion: 1 })
    await flushPromises()
    await dom().get('form').trigger('submit')
    await flushPromises()
    localStorage.setItem('test.auth.session', 'other')
    await wrapper.setProps({ identityVersion: 2 })
    complete(receipt())
    await flushPromises()
    expect(mocks.submit).not.toHaveBeenCalled()
    expect(
      sessionStorage.getItem('noewise:workflow-test:' + JSON.stringify(['actor', 'flow']))
    ).toContain('request-fixed')
    expect(
      sessionStorage.getItem('noewise:workflow-test:' + JSON.stringify(['other', 'flow']))
    ).toBeNull()
  })
  it.each([400, 401, 403])('创建收到普通 %i 时保留原请求并禁止重复创建', async (status) => {
    mocks.create.mockRejectedValueOnce(new HttpClientError({ status, userMessage: '未确认' }))
    render()
    await flushPromises()
    await dom().get('form').trigger('submit')
    await flushPromises()
    expect(
      sessionStorage.getItem('noewise:workflow-test:' + JSON.stringify(['actor', 'flow']))
    ).toContain('request-fixed')
    expect(dom().text()).toContain('查询原测试')
    expect(mocks.create).toHaveBeenCalledOnce()
    expect(mocks.submit).not.toHaveBeenCalled()
  })
  it('创建收到明确业务拒绝码时允许修正后重新提交', async () => {
    mocks.create.mockRejectedValueOnce(
      new HttpClientError({
        status: 400,
        code: 'workflow_test_budget_invalid',
        userMessage: '预算无效'
      })
    )
    render()
    await flushPromises()
    await dom().get('form').trigger('submit')
    await flushPromises()
    expect(
      sessionStorage.getItem('noewise:workflow-test:' + JSON.stringify(['actor', 'flow']))
    ).toBeNull()
    expect(dom().text()).toContain('预算无效')
  })
  it('明确拒绝但无法清除原请求时保留记录并阻止重发', async () => {
    mocks.create.mockRejectedValueOnce(
      new HttpClientError({
        status: 400,
        code: 'workflow_test_budget_invalid',
        userMessage: '预算无效'
      })
    )
    render()
    await flushPromises()
    vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => {
      throw Error('storage unavailable')
    })
    await dom().get('form').trigger('submit')
    await flushPromises()
    expect(
      sessionStorage.getItem('noewise:workflow-test:' + JSON.stringify(['actor', 'flow']))
    ).toContain('request-fixed')
    expect(dom().text()).toContain('测试创建已明确拒绝，但无法清除本地记录')
    expect(mocks.create).toHaveBeenCalledOnce()
    expect(dom().find('form').exists()).toBe(false)
  })
  it('初始消息发送期间切换工作流不将回执写入新工作流', async () => {
    let complete!: (value: unknown) => void
    mocks.submit.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          complete = resolve
        })
    )
    const wrapper = render({ identityVersion: 1 })
    await flushPromises()
    await dom().get('form').trigger('submit')
    await flushPromises()
    expect(mocks.submit).toHaveBeenCalledOnce()
    await wrapper.setProps({ workflowId: 'other' })
    const reads = mocks.get.mock.calls.length
    complete({ session_id: 'session:1', status: 'accepted' })
    await flushPromises()
    expect(
      sessionStorage.getItem('noewise:workflow-test:' + JSON.stringify(['actor', 'flow']))
    ).toContain('sending')
    expect(
      sessionStorage.getItem('noewise:workflow-test:' + JSON.stringify(['actor', 'other']))
    ).toBeNull()
    expect(mocks.get).toHaveBeenCalledTimes(reads)
  })
  it('登录状态存储无法读取时不派发测试创建', async () => {
    render()
    await flushPromises()
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation((key) => {
      if (key === 'test.auth.session') throw Error('storage unavailable')
      return null
    })
    await dom().get('form').trigger('submit')
    await flushPromises()
    expect(mocks.create).not.toHaveBeenCalled()
    expect(dom().text()).toContain('无法读取登录状态')
  })
  it('无法保存原测试请求时不创建资源也不发送消息', async () => {
    render()
    await flushPromises()
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw Error('storage unavailable')
    })
    await dom().get('form').trigger('submit')
    await flushPromises()
    expect(mocks.create).not.toHaveBeenCalled()
    expect(mocks.submit).not.toHaveBeenCalled()
    expect(dom().text()).toContain('无法保存原测试请求，未发送')
  })
  it('无法保存初始发送记录时不派发消息且保留原测试', async () => {
    const key = 'noewise:workflow-test:' + JSON.stringify(['actor', 'flow'])
    sessionStorage.setItem(
      key,
      JSON.stringify({
        requestId: 'request-fixed',
        testSessionId: 'test:1',
        initial: 'not_sent',
        closing: false
      })
    )
    mocks.get.mockResolvedValue(receipt())
    const wrapper = render({ identityVersion: 1 })
    await flushPromises()
    const original = sessionStorage.getItem(key)
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw Error('storage unavailable')
    })
    await button(wrapper, '开始已创建的测试').trigger('click')
    await flushPromises()
    expect(mocks.submit).not.toHaveBeenCalled()
    expect(sessionStorage.getItem(key)).toBe(original)
    expect(button(wrapper, '开始已创建的测试').props('loading')).toBe(false)
    expect(dom().text()).toContain('无法保存初始输入记录')
  })
  it('仅在 JSON 中填写测试消息也可保存并测试', async () => {
    const saveDraft = vi.fn(async () => {
      await wrapper.setProps({ draftRevision: 8, dirty: false })
      return 8
    })
    mocks.create.mockImplementation(async (input) =>
      receipt({ source: options(input.source).source })
    )
    const wrapper = render({ dirty: true, saveDraft, identityVersion: 1 })
    await flushPromises()
    await button(wrapper, 'JSON 输入').trigger('click')
    await dom()
      .get('textarea[aria-label="测试参数 JSON"]')
      .setValue('{"user_input":"JSON 消息","count":0,"urgent":false}')
    await button(wrapper, '保存并测试').trigger('click')
    await flushPromises()
    expect(mocks.create).toHaveBeenCalledOnce()
    expect(mocks.create.mock.calls[0]![0].userInput).toBe('JSON 消息')
  })
  it('JSON消息与上方冲突时阻止创建，统一后只提交一个测试消息', async () => {
    const wrapper = render()
    await flushPromises()
    await dom().get('textarea[aria-label="测试消息"]').setValue('上方消息')
    await button(wrapper, 'JSON 输入').trigger('click')
    await dom()
      .get('textarea[aria-label="测试参数 JSON"]')
      .setValue(JSON.stringify({ user_input: '不同消息', count: 0, urgent: false }))
    await dom().get('form').trigger('submit')
    await flushPromises()
    expect(mocks.create).not.toHaveBeenCalled()
    expect(dom().text()).toContain('JSON 中的测试消息与上方内容不同')
    await dom()
      .get('textarea[aria-label="测试参数 JSON"]')
      .setValue(JSON.stringify({ user_input: '上方消息', count: 0, urgent: false }))
    await dom().get('form').trigger('submit')
    await flushPromises()
    expect(mocks.create.mock.calls[0]![0]).toMatchObject({
      userInput: '上方消息',
      parameters: { count: 0, urgent: false }
    })
    expect(mocks.create.mock.calls[0]![0].parameters).not.toHaveProperty('user_input')
  })
  it('JSON 中必填参数为 null 时不创建测试，修正为零后可继续', async () => {
    const wrapper = render()
    await flushPromises()
    await button(wrapper, 'JSON 输入').trigger('click')
    await dom()
      .get('textarea[aria-label="测试参数 JSON"]')
      .setValue('{"count":null,"urgent":false}')
    await dom().get('form').trigger('submit')
    await flushPromises()
    expect(mocks.create).not.toHaveBeenCalled()
    expect(dom().text()).toContain('请填写测试参数：处理数量')
    await dom().get('textarea[aria-label="测试参数 JSON"]').setValue('{"count":0,"urgent":false}')
    await dom().get('form').trigger('submit')
    await flushPromises()
    expect(mocks.create.mock.calls[0]![0].parameters).toEqual({ count: 0, urgent: false })
  })
  it.each([
    ['额外字段', '{"count":0,"extra":"保留"}', 'extra'],
    ['类型不匹配', '{"count":"0"}', '处理数量'],
    ['整数小数', '{"count":1.5}', '处理数量']
  ])('JSON 包含%s时保留原输入，不切换到无法完整显示的表单', async (_case, input, field) => {
    const wrapper = render()
    await flushPromises()
    await button(wrapper, 'JSON 输入').trigger('click')
    await dom().get('textarea[aria-label="测试参数 JSON"]').setValue(input)
    await button(wrapper, '按字段填写').trigger('click')
    await flushPromises()
    expect(
      dom().get<HTMLTextAreaElement>('textarea[aria-label="测试参数 JSON"]').element.value
    ).toBe(input)
    expect(dom().text()).toContain(`参数“${field}”无法在表单中表示`)
    expect(mocks.create).not.toHaveBeenCalled()
    await dom().get('textarea[aria-label="测试参数 JSON"]').setValue('{"count":0,"urgent":false}')
    await button(wrapper, '按字段填写').trigger('click')
    await flushPromises()
    expect(dom().find('textarea[aria-label="测试参数 JSON"]').exists()).toBe(false)
    expect(dom().get<HTMLInputElement>('input[aria-label="处理数量"]').element.value).toBe('0')
    expect(dom().text()).not.toContain('无法在表单中表示')
  })

  it('数值超出范围时指出字段并保留输入，修正后才创建测试', async () => {
    const wrapper = render()
    await flushPromises()
    await button(wrapper, 'JSON 输入').trigger('click')
    const input = dom().get<HTMLTextAreaElement>('textarea[aria-label="测试参数 JSON"]')
    for (const [value, warning] of [
      [-1, '处理数量不能小于 0'],
      [101, '处理数量不能大于 100']
    ] as const) {
      await input.setValue(JSON.stringify({ count: value, urgent: false }))
      await dom().get('form').trigger('submit')
      await flushPromises()
      expect(mocks.create).not.toHaveBeenCalled()
      expect(dom().text()).toContain(warning)
      expect(input.element.value).toContain(String(value))
    }
    await input.setValue('{"count":100,"urgent":false}')
    await dom().get('form').trigger('submit')
    await flushPromises()
    expect(mocks.create.mock.calls[0]![0].parameters).toEqual({ count: 100, urgent: false })
  })
  it('选项目标身份与请求不一致时不开放创建', async () => {
    mocks.options.mockResolvedValue(options({ kind: 'draft', draftRevision: 8 }))
    render()
    await flushPromises()
    expect(dom().text()).toContain('测试选项与所选版本不一致')
    expect(dom().find('textarea[aria-label="测试消息"]').exists()).toBe(false)
    expect(mocks.create).not.toHaveBeenCalled()
  })
  it('创建UNKNOWN关闭重开只查询原请求，回执确认前不重新POST或发chat', async () => {
    const failure = new HttpClientError({ status: 503, userMessage: '服务暂不可用，请查询状态' })
    mocks.create.mockRejectedValueOnce(failure)
    const wrapper = render()
    await flushPromises()
    await dom().get('form').trigger('submit')
    await flushPromises()
    expect(failure.hasFeedbackOwner).toBe(true)
    expect(dom().findAll('.el-alert--warning')).toHaveLength(1)
    expect(dom().findAll('.el-alert--error')).toHaveLength(0)
    expect(mocks.submit).not.toHaveBeenCalled()
    await wrapper.setProps({ modelValue: false })
    await wrapper.setProps({ modelValue: true })
    await flushPromises()
    expect(mocks.byRequest.mock.calls.at(-1)![0]).toBe('request-fixed')
    expect(mocks.create).toHaveBeenCalledOnce()
    await button(wrapper, '开始已创建的测试').trigger('click')
    await flushPromises()
    expect(mocks.submit).toHaveBeenCalledOnce()
    await wrapper.setProps({ modelValue: false })
    await wrapper.setProps({ modelValue: true })
    await flushPromises()
    expect(mocks.submit).toHaveBeenCalledOnce()
  })
  it('初始chat UNKNOWN仅按原资源查询，仍无run回执时不重发，关闭状态必须读回确认', async () => {
    mocks.submit.mockRejectedValueOnce(new TypeError('network disconnected'))
    mocks.get.mockResolvedValue(receipt())
    const wrapper = render()
    await flushPromises()
    await dom().get('form').trigger('submit')
    await flushPromises()
    expect(dom().text()).toContain('结果尚未确认')
    expect(
      wrapper.findAllComponents(ElButton).some((item) => item.text() === '开始已创建的测试')
    ).toBe(false)
    await button(wrapper, '查询测试状态').trigger('click')
    await flushPromises()
    expect(mocks.submit).toHaveBeenCalledOnce()
    mocks.get.mockResolvedValue(receipt({ status: 'cancelling', closeStatus: 'cancelling' }))
    await button(wrapper, '取消并关闭测试').trigger('click')
    await flushPromises()
    expect(mocks.close.mock.calls[0]![0]).toBe('test:1')
    expect(dom().text()).toContain('正在确认运行是否结束')
    expect(wrapper.findAllComponents(ElButton).some((item) => item.text() === '开始新测试')).toBe(
      false
    )
    mocks.get.mockResolvedValue(receipt({ status: 'closed', closeStatus: 'closed' }))
    await button(wrapper, '查询测试状态').trigger('click')
    await flushPromises()
    expect(button(wrapper, '开始新测试').exists()).toBe(true)
  })
  it('候选身份变化不沿用旧索引，切换来源保留各自输入草稿', async () => {
    mocks.options.mockImplementation(async (_workflow, source) => ({
      ...options(source),
      capabilitySlots: [
        {
          slot: 'tool',
          type: 'tool',
          required: true,
          candidates: [
            {
              capability: {
                type: 'TOOL',
                capabilityId: 'cap',
                versionId: source.kind === 'draft' && source.draftRevision === 7 ? 'old' : 'new'
              },
              toolDeploymentId: 'deployment',
              toolDeploymentRevision: 1,
              knowledgeRevision: null,
              label: '测试工具'
            }
          ]
        }
      ]
    }))
    const wrapper = render()
    await flushPromises()
    expect(select(wrapper, '测试能力 tool').props('modelValue')).toContain('old')
    await dom().get('textarea[aria-label="测试消息"]').setValue('草稿消息')
    await wrapper.setProps({ draftRevision: 8 })
    await flushPromises()
    expect(select(wrapper, '测试能力 tool').props('modelValue')).toBeUndefined()
    expect(select(wrapper, '测试能力 tool').props('filterable')).toBe(true)
    expect(dom().text()).toContain('之前选择的测试候选已不可用，请重新选择。')
    await wrapper.setProps({ modelValue: false })
    await wrapper.setProps({ modelValue: true })
    await flushPromises()
    expect(select(wrapper, '测试能力 tool').props('modelValue')).toBeUndefined()
    expect(dom().text()).toContain('之前选择的测试候选已不可用，请重新选择。')
    const replacement = select(wrapper, '测试能力 tool')
      .findComponent({ name: 'ElOption' })
      .props('value')
    select(wrapper, '测试能力 tool').vm.$emit('update:modelValue', replacement)
    select(wrapper, '测试能力 tool').vm.$emit('change', replacement)
    await flushPromises()
    expect(dom().text()).not.toContain('之前选择的测试候选已不可用，请重新选择。')
    select(wrapper, '测试目标').vm.$emit('update:modelValue', 'pub:1')
    await flushPromises()
    await dom().get('textarea[aria-label="测试消息"]').setValue('发布版本消息')
    select(wrapper, '测试目标').vm.$emit('update:modelValue', 'draft')
    await flushPromises()
    expect(dom().get<HTMLTextAreaElement>('textarea[aria-label="测试消息"]').element.value).toBe(
      '草稿消息'
    )
  })
  it.each([400, 503])(
    '真实Pending收到 %i 时保留原wait，迟到history错误不覆盖新来源',
    async (status) => {
      const item = {
        interaction_type: 'workflow_input',
        execution_id: 'run:1',
        run_revision: '9007199254740993',
        tool_wait: { action_id: 'action:1', generation: '2' },
        workflow_execution_id: 'workflow:1',
        suspend_ref: 'suspend:1',
        execution_revision: '9007199254740995',
        resume_action_key: 'menu-node',
        card: {
          card_id: 'menu',
          type: 'select',
          title: '请选择测试路径',
          actions: [{ id: 'continue', label: '继续此分支' }]
        }
      }
      mocks.get.mockResolvedValue(receipt({ runId: 'run:1', status: 'waiting' }))
      mocks.history.mockResolvedValue({
        session_id: 'session:1',
        event_cursor: null,
        messages: [],
        pending_interactions: [item],
        collaboration: {}
      })
      const wrapper = render()
      await flushPromises()
      await dom().get('form').trigger('submit')
      await flushPromises()
      const pendingFailure = new HttpClientError({ status, userMessage: '受理暂未确认' })
      mocks.submit.mockRejectedValueOnce(pendingFailure)
      await button(wrapper, '继续此分支').trigger('click')
      await flushPromises()
      expect(pendingFailure.hasFeedbackOwner).toBe(true)
      expect(dom().findAll('.el-alert--error')).toHaveLength(0)
      expect(dom().text()).toContain('此回复已提交，请查询当前待办确认处理结果')
      expect(mocks.submit.mock.calls.at(-1)![0]).toMatchObject({
        session_id: 'session:1',
        hitl_response: {
          execution_id: 'run:1',
          run_revision: '9007199254740993',
          workflow_input: {
            wait: item.tool_wait,
            execution_ref: 'workflow:1',
            suspend_ref: 'suspend:1',
            execution_revision: '9007199254740995',
            action_key: 'menu-node',
            response: {
              schema_id: 'application/json',
              canonical_json: '{"action":"continue","response":""}'
            }
          }
        }
      })
      let reject!: (reason: unknown) => void
      mocks.history.mockImplementationOnce(
        () =>
          new Promise((_resolve, no) => {
            reject = no
          })
      )
      await button(wrapper, '查询测试状态').trigger('click')
      await flushPromises()
      await wrapper.setProps({ modelValue: false })
      const failure = new HttpClientError({ status: 500, userMessage: '旧查询失败' })
      reject(failure)
      await flushPromises()
      expect(failure.hasFeedbackOwner).toBe(true)
      expect(dom().text()).not.toContain('旧查询失败')
    }
  )
})
