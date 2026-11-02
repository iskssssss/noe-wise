import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { reactive } from 'vue'
import SessionDetailPage from './SessionDetailPage.vue'
import SessionAttemptDiagnostics from './SessionAttemptDiagnostics.vue'
import JsonViewer from '@skillforge/shared/components/JsonViewer.vue'
import ObservationTree from '@skillforge/shared/observation/ObservationTree.vue'
import type {
  ObservationNode,
  ObservationPage,
  TrajectoryDetail
} from '@skillforge/shared/observation/types'
import {
  attemptUsageMetrics,
  compareSequence,
  mergeMessages,
  messageState
} from './sessionTimeline'
import type {
  SessionMessage,
  SessionRound,
  SessionUsage,
  ModelAttemptDetail
} from '../../api/sessionObservation'

const calls = vi.hoisted(() => ({
  detail: vi.fn(),
  rounds: vi.fn(),
  messages: vi.fn(),
  trace: vi.fn(),
  usage: vi.fn(),
  traceContent: vi.fn(),
  modelAttemptDetail: vi.fn(),
  trajectoryDetail: vi.fn(),
  trajectoryNodes: vi.fn(),
  trajectoryContent: vi.fn()
}))
const authState = reactive({
  identityVersion: 1,
  user: {
    sub: 'platform-actor',
    authorization_version: 1,
    mfa_state: 'full',
    permissions: ['foundation.sessions.read', 'foundation.sessions.diagnostic_read']
  }
})
vi.mock('../../stores/auth', () => ({ useAuthStore: () => authState }))
vi.mock('../../api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../api')>()),
  getSessionId: () => 'account-A',
  api: {
    sessions: calls,
    trajectories: {
      detail: calls.trajectoryDetail,
      nodes: calls.trajectoryNodes,
      content: calls.trajectoryContent
    }
  }
}))
const mounted: VueWrapper[] = []
const huge = '9007199254740993123456789'
const totals = {
  prompt_tokens: huge,
  completion_tokens: '7',
  cached_input_tokens: '0',
  cache_write_input_tokens: null,
  reasoning_output_tokens: null
}
const coverage = {
  status: 'COMPLETE' as const,
  total_attempts: 2,
  reported_attempts: 2,
  unknown_attempts: 0,
  partial_attempts: 0,
  zero_usage_attempts: 0
}

it('用量报告未知和明确零用量分别提示，不把UNKNOWN断言为未发生或零', () => {
  expect(messageState('UNKNOWN')).toBe('待核实')
  expect(messageState('ZERO')).toBe('明确零用量')
  expect(messageState('OUTCOME_UNKNOWN')).toBe('结果待核实')
})
/** 明确提供服务端真实身份，测试不依靠时间相邻关系制造 Step。 */
function round(root = 'root-A', run = 'run-A', number = '1', firstSequence = '1'): SessionRound {
  return {
    root_input_id: root,
    round_number: number,
    first_sequence: firstSequence,
    last_sequence: huge,
    answer_status: 'COMPLETED',
    execution_status: 'COMPLETED',
    started_at: '2026-10-11T01:00:00Z',
    updated_at: '2026-10-11T01:00:01Z',
    run_count: 1,
    runs: [{ run_id: run, loops: [] }]
  }
}
function message(text = '公开过程说明', root = 'root-A', run = 'run-A'): SessionMessage {
  return {
    message_id: root + ':message',
    role: 'assistant',
    content: text,
    root_input_id: root,
    run_id: run,
    phase: 'commentary',
    state: 'completed',
    message_version: '2',
    created_at: '2026-10-11T01:00:01Z'
  }
}
function usage(): SessionUsage {
  return {
    tenant_id: '7',
    session_id: 'session',
    root_input_id: 'root-A',
    read_at: '',
    next_cursor: null,
    totals,
    coverage,
    cache: null,
    breakdowns: [],
    attempts: ['attempt-1', 'attempt-retry'].map((attempt_id) => ({
      attempt_id,
      diagnostic_available: true,
      source_session_id: 'session',
      source_root_input_id: 'root-A',
      invocation_id: 'invocation-A',
      run_id: 'run-A',
      trace_node_id: 'node-A',
      model: '真实模型',
      provider: '测试提供方',
      call_point: 'agent-runtime.model',
      report_status: 'REPORTED',
      usage_source: 'PROVIDER_REPORTED',
      totals,
      ingested_at: null
    }))
  }
}
function attemptDetail(attemptId = 'attempt-1'): ModelAttemptDetail {
  const missing = {
    availability: 'NOT_RECORDED' as const,
    source: null,
    schema_id: null,
    value: null
  }
  return {
    tenant_id: '7',
    session_id: 'session',
    root_input_id: 'root-A',
    attempt_id: attemptId,
    invocation_id: 'invocation-A',
    run_id: 'run-A',
    action_id: 'model-action',
    loop_id: 'loop-A',
    iteration: 1,
    provider: '测试提供方',
    model: '真实模型',
    call_point: 'agent-runtime.model',
    status: 'SUCCEEDED',
    finish_reason: 'tool_calls',
    started_at: '2026-10-11T01:00:00Z',
    finished_at: '2026-10-11T01:00:01Z',
    read_at: '2026-10-11T01:00:02Z',
    usage: { ...usage().attempts[0], attempt_id: attemptId },
    input: {
      availability: 'FULL',
      source: 'FROZEN_MODEL_REQUEST',
      schema_id: 'frozen.v1',
      value: {
        messages: [
          {
            message_id: 'sys-1',
            role: 'system',
            content: '精确冻结系统提示词 <script>不要执行</script>'
          },
          {
            message_id: 'user-1',
            role: 'user',
            content: '用户原始输入',
            contents: [{ type: 'image', media_id: 'recorded-image-1', mime_type: 'image/png' }]
          }
        ],
        tools: [{ name: 'available-only' }],
        generation: { temperature: 0.3 },
        response: {},
        reasoning: {},
        tool_choice: 'auto'
      }
    },
    reasoning_history_retention: null,
    reasoning: {
      availability: 'NOT_RETURNED',
      source: 'PROVIDER_RESPONSE',
      schema_id: null,
      value: null
    },
    output: {
      availability: 'FULL',
      source: 'PROVIDER_RESPONSE',
      schema_id: 'output.v1',
      value: '此 attempt 的真实模型输出'
    },
    decision: {
      availability: 'FULL',
      source: 'RUNTIME_MODEL_OUTCOME',
      schema_id: 'decision.v1',
      value: '{"kind":"TOOL_CALLS","actions":[{"name":"模型提出的查询动作"}]}'
    },
    capability_calls_availability: 'FULL',
    capability_calls: [
      {
        call_id: 'call-1',
        action_id: 'tool-action',
        attempt_id: attemptId,
        kind: 'TOOL',
        title: '实际查询工具',
        status: 'SUCCEEDED',
        input: { ...missing, availability: 'FULL', value: { query: '真实工具参数' } },
        output: { ...missing, availability: 'PARTIAL', value: { text: '真实工具结果' } }
      }
    ]
  }
}
function trajectoryFixture(root = 'root-A'): TrajectoryDetail {
  return {
    termination: null,
    summary: {
      tenant_id: '7',
      tenant_name: '测试机构',
      session_id: 'session',
      root_input_id: root,
      agent_id: 'agent',
      agent_name: '主 Agent',
      input: '本轮用户问题',
      output: '本轮最终回答',
      status: 'COMPLETED',
      answer_status: 'COMPLETED',
      started_at: '2026-10-11T01:00:00Z',
      finished_at: '2026-10-11T01:00:01Z',
      duration_ms: '1000',
      trace_ids: ['trace-A'],
      models: ['真实模型'],
      tools: ['实际查询工具'],
      step_count: 1,
      model_calls: 2,
      tool_calls: 1,
      totals,
      coverage
    },
    messages: [{ ...message(), message_version: '2' }],
    usage: {
      ...usage(),
      root_input_id: root,
      attempts: usage().attempts.map((attempt) => ({
        ...attempt,
        source_root_input_id: root,
        trace_node_id: attempt.trace_node_id ?? null
      }))
    },
    snapshot: 'snapshot:' + root,
    read_at: '2026-10-11T01:00:02Z',
    usage_summary: {
      calls: 2,
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
        missing_model_attempts: 2
      }
    }
  }
}
function observationNode(
  id: string,
  kind: ObservationNode['kind'],
  parent: string | null,
  root = 'root-A'
): ObservationNode {
  return {
    node_id: id,
    parent_node_id: parent,
    kind,
    name: kind === 'LLM' ? '真实模型' : kind,
    status: 'COMPLETED',
    source_session_id: 'session',
    root_input_id: root,
    input_id: root,
    run_id: 'run-A',
    action_id: kind === 'LLM' ? 'model-action' : null,
    step_id: kind === 'LLM' || kind === 'STEP' ? 'step-A' : null,
    iteration: '1',
    trace_id: 'trace-A',
    span_id: 'span-A',
    started_at: '2026-10-11T01:00:00Z',
    finished_at: '2026-10-11T01:00:01Z',
    duration_ms: '1000',
    sequence: '1',
    summary: '已提交真实执行事实',
    input: { ref: null, availability: 'NOT_RECORDED' },
    output: { ref: null, availability: 'NOT_RECORDED' },
    source_event_ids: [id + '-event'],
    attempts:
      kind === 'LLM'
        ? usage().attempts.map((attempt) => ({
            attempt_id: attempt.attempt_id,
            input: { ref: `${attempt.attempt_id}:input`, availability: 'FULL' },
            output: { ref: `${attempt.attempt_id}:output`, availability: 'FULL' },
            diagnostic_available: true,
            invocation_id: attempt.invocation_id,
            provider: attempt.provider,
            model: attempt.model,
            status: 'SUCCEEDED',
            started_at: null,
            finished_at: null,
            duration_ms: null,
            report_status: attempt.report_status,
            totals: attempt.totals
          }))
        : []
  }
}
function observationFixture(root = 'root-A'): ObservationPage {
  return {
    items: [
      observationNode('entry-A', 'ENTRY', null, root),
      observationNode('agent-A', 'AGENT', 'entry-A', root),
      observationNode('step-A', 'STEP', 'agent-A', root),
      observationNode('llm-A', 'LLM', 'step-A', root),
      observationNode('tool-A', 'TOOL', 'step-A', root)
    ],
    ancestors: [],
    next_cursor: null,
    snapshot: 'snapshot:' + root,
    coverage: 'COMPLETE',
    read_at: '2026-10-11T01:00:02Z'
  }
}

beforeEach(() => {
  vi.clearAllMocks()
  authState.identityVersion = 1
  authState.user.authorization_version = 1
  authState.user.permissions = ['foundation.sessions.read', 'foundation.sessions.diagnostic_read']
  localStorage.clear()
  calls.detail.mockResolvedValue({
    tenant_name: '测试机构',
    message_count: 1,
    execution_status: 'COMPLETED',
    answer_status: 'COMPLETED'
  })
  calls.rounds.mockResolvedValue({
    items: [round('root-A', 'run-A', '2', '10'), round('root-B', 'run-B', '1', '1')],
    next_cursor: null
  })
  calls.messages.mockImplementation(async (_tenant, _session, root) => ({
    items: [message(root === 'root-B' ? 'B 的公开消息' : '公开过程说明', root || 'root-A')],
    next_cursor: null
  }))
  calls.usage.mockResolvedValue(usage())
  calls.trajectoryDetail.mockImplementation(async (input) => trajectoryFixture(input.root_input_id))
  calls.trajectoryNodes.mockImplementation(async (input) => observationFixture(input.root_input_id))
  calls.modelAttemptDetail.mockImplementation(async (_tenant, _session, attempt) =>
    attemptDetail(attempt)
  )
})
afterEach(() => {
  mounted.splice(0).forEach((wrapper) => wrapper.unmount())
})
async function open(query = 'tenant_id=7&session_id=session') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/platform/sessions/detail', component: SessionDetailPage },
      { path: '/platform/sessions', component: { template: '<div />' } },
      { path: '/platform/trajectories', component: { template: '<div />' } }
    ]
  })
  await router.push('/platform/sessions/detail?' + query)
  await router.isReady()
  const wrapper = mount(SessionDetailPage, { global: { plugins: [router] } })
  mounted.push(wrapper)
  await flushPromises()
  return { wrapper, router }
}
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => {
    resolve = done
  })
  return { promise, resolve }
}

describe('业务轮次的会话内固定编号', () => {
  it('重复选择当前轮次保留轨迹筛选，切换轮次与显式刷新仍重新读取', async () => {
    const { wrapper } = await open()
    const tree = wrapper.getComponent(ObservationTree)
    tree.vm.$emit('search', '模型节点')
    await flushPromises()
    const messages = calls.messages.mock.calls.length
    const detail = calls.trajectoryDetail.mock.calls.length
    const nodes = calls.trajectoryNodes.mock.calls.length
    await wrapper.get('[data-round-id="root-A"]').trigger('click')
    await flushPromises()
    expect(calls.messages).toHaveBeenCalledTimes(messages)
    expect(calls.trajectoryDetail).toHaveBeenCalledTimes(detail)
    expect(calls.trajectoryNodes).toHaveBeenCalledTimes(nodes)
    expect(tree.props('appliedQuery')).toBe('模型节点')
    await wrapper.get('[data-round-id="root-B"]').trigger('click')
    await flushPromises()
    expect(calls.trajectoryDetail).toHaveBeenCalledTimes(detail + 1)
    expect(tree.props('appliedQuery')).toBe('')
    await wrapper
      .findAll('button')
      .find((item) => item.text() === '刷新')!
      .trigger('click')
    await flushPromises()
    expect(calls.trajectoryDetail).toHaveBeenCalledTimes(detail + 2)
  })
  it('切换轮次和视图后链接保留当前定位，重新打开仍指向同一轮次', async () => {
    const { wrapper, router } = await open('tenant_id=7&session_id=session&root_input_id=root-A')
    await wrapper.get('[data-round-id="root-B"]').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.query).toMatchObject({
      tenant_id: '7',
      session_id: 'session',
      root_input_id: 'root-B'
    })
    await wrapper
      .findAll('[role=tab]')
      .find((tab) => tab.text() === '消息')!
      .trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.query).toMatchObject({
      root_input_id: 'root-B',
      tab: 'messages'
    })
    const again = await open(
      new URLSearchParams(router.currentRoute.value.query as Record<string, string>).toString()
    )
    expect(again.wrapper.text()).toContain('B 的公开消息')
    expect(again.router.currentRoute.value.query.root_input_id).toBe('root-B')
  })

  it('选择全部消息后刷新仍保持全部范围，不回到最新轮次', async () => {
    const { wrapper, router } = await open()
    await wrapper
      .findAll('.el-menu-item')
      .find((item) => item.text().includes('全部消息与用量'))!
      .trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.query.scope).toBe('all')
    expect(router.currentRoute.value.query.root_input_id).toBeUndefined()
    const again = await open(
      new URLSearchParams(router.currentRoute.value.query as Record<string, string>).toString()
    )
    expect(again.wrapper.text()).toContain('请选择业务轮次查看执行树')
    expect(calls.messages.mock.calls.at(-1)?.[2]).toBeUndefined()
  })

  it('按服务器倒序展示第2轮和第1轮，不用当前列表下标重新编号', async () => {
    const { wrapper } = await open()
    expect(wrapper.findAll('.round-summary > span').map((item) => item.text())).toEqual([
      '第 2 轮',
      '第 1 轮'
    ])
    expect(wrapper.get('.round-panel').text()).toContain('最新轮次在前')
    expect(wrapper.get('[data-round-id="root-A"]').text()).toContain('1 次运行')
    expect(wrapper.get('[data-round-id="root-A"] time').attributes('datetime')).toBe(
      '2026-10-11T01:00:00Z'
    )
  })

  it('加载旧轮次后保留已有编号，后续页不从第1轮重新计数', async () => {
    calls.rounds.mockImplementation(async (_tenant, _session, cursor) =>
      cursor
        ? { items: [round('old-root', 'old-run', '1', '1')], next_cursor: null }
        : {
            items: [round('root-A', 'run-A', '3', '30'), round('root-B', 'run-B', '2', '20')],
            next_cursor: 'older-rounds'
          }
    )
    const { wrapper } = await open()
    expect(wrapper.findAll('.round-summary > span').map((item) => item.text())).toEqual([
      '第 3 轮',
      '第 2 轮'
    ])
    await wrapper
      .findAll('button')
      .find((item) => item.text() === '更多轮次')!
      .trigger('click')
    await flushPromises()
    expect(calls.rounds.mock.calls.at(-1)?.[2]).toBe('older-rounds')
    expect(wrapper.findAll('.round-summary > span').map((item) => item.text())).toEqual([
      '第 3 轮',
      '第 2 轮',
      '第 1 轮'
    ])
  })

  it('大十进制编号原样显示，不经Number转换', async () => {
    calls.rounds.mockResolvedValue({
      items: [round('root-A', 'run-A', huge, huge)],
      next_cursor: null
    })
    const { wrapper } = await open()
    expect(wrapper.get('.round-summary > span').text()).toBe(`第 ${huge} 轮`)
  })
})

describe('会话用量与消息十进制边界', () => {
  it('单 attempt 精确推导未命中、命中率与合计，超大十进制不经 Number', () => {
    expect(
      attemptUsageMetrics({
        ...totals,
        prompt_tokens: '9262',
        completion_tokens: '2118',
        cached_input_tokens: '3072'
      })
    ).toEqual({ total: '11380', uncachedInput: '6190', hitRatio: '33.17%' })
    expect(attemptUsageMetrics({ ...totals, cached_input_tokens: '1' })).toEqual({
      total: '9007199254740993123456796',
      uncachedInput: '9007199254740993123456788',
      hitRatio: '0.00%'
    })
  })
  it('派生字段缺报保持未知，零输入比例不适用，缓存大于输入不显示负数', () => {
    expect(attemptUsageMetrics(null)).toEqual({ total: null, uncachedInput: null, hitRatio: '—' })
    expect(
      attemptUsageMetrics({
        ...totals,
        prompt_tokens: '100',
        completion_tokens: null,
        cached_input_tokens: null
      })
    ).toEqual({ total: null, uncachedInput: null, hitRatio: '—' })
    expect(
      attemptUsageMetrics({ ...totals, prompt_tokens: '0', cached_input_tokens: '0' })
    ).toEqual({ total: '7', uncachedInput: '0', hitRatio: '不适用' })
    expect(
      attemptUsageMetrics({ ...totals, prompt_tokens: '1', cached_input_tokens: '2' })
    ).toEqual({ total: '8', uncachedInput: null, hitRatio: '—' })
  })
  it('消息旧版本不能覆盖新正文，超出安全整数的相邻版本仍能正确比较', () => {
    expect(
      mergeMessages(
        [message()],
        [{ ...message('旧前缀'), state: 'streaming', message_version: '1' }]
      )[0].content
    ).toBe('公开过程说明')
    expect(compareSequence('9007199254740993', '9007199254740992')).toBe(1)
    expect(
      mergeMessages(
        [{ ...message(), message_version: '9007199254740993' }],
        [{ ...message('过期正文'), message_version: '9007199254740992' }]
      )[0].content
    ).toBe('公开过程说明')
  })
})

describe('会话详情实际接线', () => {
  it('默认主视图使用统一执行树与服务端快照，公开用量保持大整数精度', async () => {
    const { wrapper } = await open()
    const tree = wrapper.getComponent(ObservationTree)
    expect(calls.trajectoryDetail.mock.calls[0][0]).toEqual({
      tenant_id: '7',
      session_id: 'session',
      root_input_id: 'root-A'
    })
    expect(calls.trajectoryNodes.mock.calls[0][0]).toMatchObject({
      snapshot: 'snapshot:root-A',
      page_size: 100
    })
    expect(tree.props('page')?.items.map((node: ObservationNode) => node.kind)).toEqual([
      'ENTRY',
      'AGENT',
      'STEP',
      'LLM',
      'TOOL'
    ])
    expect(tree.text()).toContain('9,007,199,254,740,993,123,456,789')
    expect(
      Object.fromEntries(
        tree
          .findAll('.overview-metrics > div')
          .map((metric) => [metric.get('dt').text(), metric.get('dd').text()])
      )
    ).toMatchObject({ 模型尝试: '2 次' })
    expect(calls.trace).not.toHaveBeenCalled()
    expect(calls.trajectoryContent).not.toHaveBeenCalled()
    expect(calls.modelAttemptDetail).not.toHaveBeenCalled()
  })
  it('公开目标与结果位于执行树之前，展开树不自动读取私有模型诊断', async () => {
    const { wrapper } = await open()
    const overview = wrapper.get('.trajectory-overview'),
      tree = wrapper.get('.execution-tree')
    expect(overview.text()).toContain('本轮用户问题')
    expect(overview.text()).toContain('本轮最终回答')
    expect(
      overview.element.compareDocumentPosition(tree.element) & Node.DOCUMENT_POSITION_FOLLOWING
    ).toBeTruthy()
    expect(calls.modelAttemptDetail).not.toHaveBeenCalled()
    expect(calls.trajectoryContent).not.toHaveBeenCalled()
  })
  it('页面从当前轨迹的持久终止记录显示预算原因，旧记录不编造不足数值', async () => {
    const value = trajectoryFixture()
    value.summary.status = 'TERMINATED'
    value.summary.model_calls = 0
    value.summary.tool_calls = 0
    value.termination = {
      run_id: 'run-A',
      reason_code: 'execution_output_closing_budget_insufficient',
      business_model_attempts: '0',
      tool_attempts: '0',
      auxiliary_model_attempts: '1',
      budget_failure: null
    }
    calls.trajectoryDetail.mockResolvedValue(value)
    const { wrapper } = await open('tenant_id=7&session_id=session&root_input_id=root-A')
    const notice = wrapper.get('[aria-label="执行终止原因"]')
    expect(notice.text()).toContain('模型准备时无法预留收尾预算')
    expect(notice.text()).toContain('未记录具体预算数值')
    expect(notice.findAll('.attempt-counts dd').map((item) => item.text())).toEqual([
      '0 次',
      '0 次',
      '1 次'
    ])
    expect(notice.text()).not.toContain('300')
    expect(notice.text()).not.toContain('未执行')
  })
  it('执行树的精确attempt诊断入口读取冻结内容，关闭销毁详情并取消观察', async () => {
    const { wrapper } = await open()
    expect(calls.modelAttemptDetail).not.toHaveBeenCalled()
    wrapper.getComponent(ObservationTree).vm.$emit('attempt', 'attempt-1', 'session', 'root-A')
    await flushPromises()
    expect(calls.modelAttemptDetail.mock.calls[0].slice(0, 4)).toEqual([
      '7',
      'session',
      'attempt-1',
      'root-A'
    ])
    const diagnostics = wrapper.getComponent(SessionAttemptDiagnostics)
    expect(diagnostics.text()).toContain('此 attempt 的真实模型输出')
    expect(diagnostics.text()).toContain('真实工具参数')
    expect(diagnostics.text()).toContain('真实工具结果')
    expect(diagnostics.find('script').exists()).toBe(false)
    const signal = calls.modelAttemptDetail.mock.calls[0][4].signal as AbortSignal
    wrapper
      .findAllComponents({ name: 'ElDrawer' })
      .find((drawer) => drawer.props('title') === '模型调用详情')!
      .vm.$emit('update:modelValue', false)
    await flushPromises()
    expect(signal.aborted).toBe(true)
    expect(wrapper.findComponent(SessionAttemptDiagnostics).exists()).toBe(false)
    expect(
      Array.from({ length: localStorage.length }, (_, index) =>
        localStorage.getItem(localStorage.key(index)!)
      ).join('')
    ).not.toContain('精确冻结系统提示词')
  })
  it('Token 用量表提供相同调用详情，关闭期间取消请求并丢弃迟到正文', async () => {
    const pending = deferred<ModelAttemptDetail>()
    calls.modelAttemptDetail.mockReturnValue(pending.promise)
    const { wrapper } = await open('tenant_id=7&session_id=session&tab=usage')
    await wrapper.find('[data-attempt-detail-id="attempt-1"]').trigger('click')
    await flushPromises()
    expect(wrapper.findComponent(SessionAttemptDiagnostics).exists()).toBe(true)
    const signal = calls.modelAttemptDetail.mock.calls[0][4].signal as AbortSignal
    const drawers = wrapper.findAllComponents({ name: 'ElDrawer' })
    drawers
      .find((drawer) => drawer.props('title') === '模型调用详情')!
      .vm.$emit('update:modelValue', false)
    await flushPromises()
    expect(signal.aborted).toBe(true)
    pending.resolve(attemptDetail())
    await flushPromises()
    expect(wrapper.findComponent(SessionAttemptDiagnostics).exists()).toBe(false)
    expect(document.body.textContent).not.toContain('此 attempt 的真实模型输出')
  })
  it('选中问答用量表与执行树使用同一完整轨迹用量，辅助模型可见但无私有诊断', async () => {
    const complete = trajectoryFixture()
    const core = complete.usage!.attempts[0]
    const fullTotals = {
      ...totals,
      prompt_tokens: '9007199254740993123456796',
      completion_tokens: '10'
    }
    complete.summary.totals = fullTotals
    complete.usage = {
      ...complete.usage!,
      totals: fullTotals,
      attempts: [
        core,
        {
          ...core,
          attempt_id: 'aux-attempt',
          diagnostic_available: false,
          trace_node_id: null,
          call_point: 'memory.retrieval',
          totals: { ...totals, prompt_tokens: '7', completion_tokens: '3' }
        }
      ]
    }
    calls.trajectoryDetail.mockResolvedValue(complete)
    const { wrapper } = await open('tenant_id=7&session_id=session&root_input_id=root-A&tab=usage')
    expect(calls.usage).not.toHaveBeenCalled()
    expect(wrapper.getComponent(ObservationTree).props('detail')?.usage?.totals).toEqual(fullTotals)
    const input = wrapper
      .findAll('.el-descriptions__label')
      .find((item) => item.text() === '输入 Token')!
    expect(input.element.nextElementSibling?.textContent).toContain(
      '9,007,199,254,740,993,123,456,796'
    )
    const auxiliary = wrapper.findAll('tr').find((row) => row.text().includes('aux-attempt'))
    expect(auxiliary?.text()).toContain('memory.retrieval')
    expect(auxiliary?.text()).toContain('7')
    expect(wrapper.find('[data-attempt-detail-id="aux-attempt"]').exists()).toBe(false)
    await wrapper.get('[data-attempt-detail-id="attempt-1"]').trigger('click')
    await flushPromises()
    expect(calls.modelAttemptDetail.mock.calls[0].slice(0, 4)).toEqual([
      '7',
      'session',
      'attempt-1',
      'root-A'
    ])
    expect(wrapper.findComponent(SessionAttemptDiagnostics).exists()).toBe(true)
  })
  it('已有会话读权可直接读取该次模型原始材料，不以独立诊断权限作为门槛', async () => {
    authState.user.permissions = ['foundation.sessions.read']
    calls.trajectoryContent.mockResolvedValueOnce({
      content_ref: 'attempt-1:input',
      availability: 'FULL',
      schema_id: 'json',
      value: { messages: [{ role: 'user', content: '完整原始输入' }], extra: '保留已记录额外字段' }
    })
    const { wrapper } = await open(
      'tenant_id=7&session_id=session&root_input_id=root-A&node_id=llm-A&tab=timeline'
    )
    await wrapper
      .get('[data-attempt-id="attempt-1"]')
      .findAll('button')
      .find((button) => button.text() === '查看原始请求')!
      .trigger('click')
    await flushPromises()
    expect(calls.trajectoryContent.mock.calls[0][0]).toEqual({
      tenant_id: '7',
      session_id: 'session',
      root_input_id: 'root-A',
      content_ref: 'attempt-1:input'
    })
    expect(wrapper.getComponent(ObservationTree).props('content')?.value).toMatchObject({
      extra: '保留已记录额外字段'
    })
    expect(calls.modelAttemptDetail).not.toHaveBeenCalled()
  })
  it('扩展诊断仍要求独立管理员动作权限，无诊断权限时树和用量表无扩展入口', async () => {
    authState.user.permissions = ['foundation.sessions.read']
    const { wrapper } = await open()
    const tree = wrapper.getComponent(ObservationTree)
    expect(tree.props('allowAttemptDiagnostics')).toBe(false)
    expect(wrapper.find('[data-attempt-detail-id="attempt-1"]').exists()).toBe(false)
    tree.vm.$emit('attempt', 'attempt-1', 'session', 'root-A')
    await flushPromises()
    expect(calls.modelAttemptDetail).not.toHaveBeenCalled()
    expect(wrapper.findComponent(SessionAttemptDiagnostics).exists()).toBe(false)
  })
  it('成员调用按用量记录真实源读取诊断，并携带当前父轨迹对，不依赖节点分页', async () => {
    const detail = trajectoryFixture()
    const child = {
      ...detail.usage!.attempts[0],
      attempt_id: 'child-attempt',
      source_session_id: 'delegation:child',
      source_root_input_id: 'child-root',
      run_id: 'child-run',
      trace_node_id: 'unloaded-child-node'
    }
    detail.usage = { ...detail.usage!, attempts: [child] }
    calls.trajectoryDetail.mockResolvedValue(detail)
    calls.modelAttemptDetail.mockResolvedValue({
      ...attemptDetail('child-attempt'),
      session_id: 'delegation:child',
      root_input_id: 'child-root'
    })
    const { wrapper } = await open('tenant_id=7&session_id=session&root_input_id=root-A&tab=usage')
    expect(
      wrapper
        .getComponent(ObservationTree)
        .props('page')
        ?.items.some((node: ObservationNode) => node.node_id === 'unloaded-child-node')
    ).toBe(false)
    await wrapper.get('[data-attempt-detail-id="child-attempt"]').trigger('click')
    await flushPromises()
    expect(calls.modelAttemptDetail.mock.calls[0].slice(0, 4)).toEqual([
      '7',
      'delegation:child',
      'child-attempt',
      'child-root'
    ])
    expect(calls.modelAttemptDetail.mock.calls[0][5]).toEqual({
      session_id: 'session',
      root_input_id: 'root-A'
    })
    expect(wrapper.getComponent(SessionAttemptDiagnostics).props('sessionId')).toBe(
      'delegation:child'
    )
  })
  it('全Session源root缺失时不从轮次列表假补，也不制造父轨迹身份', async () => {
    calls.usage.mockResolvedValue({
      ...usage(),
      root_input_id: null,
      attempts: [{ ...usage().attempts[0], source_root_input_id: null }]
    })
    const { wrapper } = await open('tenant_id=7&session_id=session&tab=usage')
    await wrapper.get('[data-attempt-detail-id="attempt-1"]').trigger('click')
    await flushPromises()
    expect(calls.modelAttemptDetail.mock.calls[0].slice(0, 4)).toEqual([
      '7',
      'session',
      'attempt-1',
      undefined
    ])
    expect(calls.modelAttemptDetail.mock.calls[0][5]).toBeUndefined()
  })
  it('诊断权限撤销立即关闭详情并取消请求，迟到结果不能重新显示', async () => {
    const pending = deferred<ModelAttemptDetail>()
    calls.modelAttemptDetail.mockReturnValue(pending.promise)
    const { wrapper } = await open('tenant_id=7&session_id=session&tab=usage')
    await wrapper.get('[data-attempt-detail-id="attempt-1"]').trigger('click')
    await flushPromises()
    const signal = calls.modelAttemptDetail.mock.calls[0][4].signal as AbortSignal
    expect(wrapper.findComponent(SessionAttemptDiagnostics).exists()).toBe(true)
    authState.user.permissions = ['foundation.sessions.read']
    await flushPromises()
    expect(wrapper.findComponent(SessionAttemptDiagnostics).exists()).toBe(false)
    expect(wrapper.find('[data-attempt-detail-id="attempt-1"]').exists()).toBe(false)
    expect(signal.aborted).toBe(true)
    pending.resolve(attemptDetail())
    await flushPromises()
    expect(document.body.textContent).not.toContain('此 attempt 的真实模型输出')
  })
  it('native TOOL_CALLS 的原 assistant 文字为空时，模型动作仍可读且不伪造实际调用', async () => {
    const value = attemptDetail()
    value.decision.value =
      '{"kind":"TOOL_CALLS","action":"模型提出的查询动作","exact_id":9007199254740993123456789}'
    calls.modelAttemptDetail.mockResolvedValue({
      ...value,
      output: { ...value.output, source: 'PROVIDER_ASSISTANT_TURN', value: '' },
      capability_calls: [],
      capability_calls_availability: 'NOT_RECORDED'
    })
    const wrapper = mount(SessionAttemptDiagnostics, {
      props: {
        tenantId: '7',
        sessionId: 'session',
        rootInputId: 'root-A',
        attemptId: 'attempt-1',
        scopeKey: 'account-A'
      }
    })
    mounted.push(wrapper)
    await flushPromises()
    expect(wrapper.text()).toContain('模型提出的查询动作')
    expect(wrapper.text()).toContain('PROVIDER_ASSISTANT_TURN')
    expect(wrapper.find('[aria-label="模型输出"]').text()).toContain('已记录空正文')
    expect(wrapper.find('[aria-label="模型输出"]').findComponent(JsonViewer).exists()).toBe(false)
    expect(wrapper.find('[aria-label="模型动作"]').findComponent(JsonViewer).props('value')).toBe(
      value.decision.value
    )
    expect(wrapper.text()).toContain('实际能力调用未记录')
    expect(wrapper.findAll('[data-capability-call-id]')).toHaveLength(0)
    expect(wrapper.text()).not.toContain('此 attempt 的真实模型输出')
  })
  it('诊断消息、完整请求和结果直接交给JSON查看器，保留原文中的大整数及数字词法', async () => {
    const raw = '{"id":9007199254740993123456789,"nested":{"amount":1.2300e+2,"negativeZero":-0}}'
    const original = {
      messages: [
        {
          message_id: 'raw-1',
          role: 'user',
          content: raw,
          tool_calls: [{ function: { arguments: raw } }]
        }
      ],
      tools: [{ name: 'query', parameters: { type: 'object' } }],
      generation: { temperature: 0.3 },
      extra_original_field: raw
    }
    const value = attemptDetail()
    value.input = { ...value.input, value: original }
    value.output = { ...value.output, value: raw }
    calls.modelAttemptDetail.mockResolvedValueOnce(value)
    const wrapper = mount(SessionAttemptDiagnostics, {
      props: {
        tenantId: '7',
        sessionId: 'session',
        rootInputId: 'root-A',
        attemptId: 'attempt-1',
        scopeKey: 'account-A'
      }
    })
    mounted.push(wrapper)
    await flushPromises()
    const viewers = wrapper.findAllComponents(JsonViewer)
    const view = (label: string) => viewers.find((viewer) => viewer.props('label') === label)!
    expect(view('模型输出').props('value')).toBe(raw)
    expect(view('消息 1 正文').props('value')).toBe(raw)
    expect(view('消息 1 tool_calls').props('value')).toEqual(original.messages[0]!.tool_calls)
    expect(view('可调用能力定义').props('value')).toEqual(original.tools)
    expect(view('已记录请求配置').props('value')).toEqual({ generation: original.generation })
    expect(view('完整冻结请求投影').props('value')).toEqual(original)
    expect(wrapper.find('[aria-label="输入提示词 / 消息"]').text()).toContain(
      'FROZEN_MODEL_REQUEST'
    )
  })
  it('管理员详情在同 attempt 的机构/root scope 切换时取消旧请求，不接受迟到内容', async () => {
    const pending = deferred<ModelAttemptDetail>()
    calls.modelAttemptDetail.mockImplementation(async (tenant, _session, attempt) =>
      tenant === '7'
        ? pending.promise
        : {
            ...attemptDetail(attempt),
            tenant_id: '8',
            root_input_id: 'root-B',
            output: { ...attemptDetail().output, value: 'B 的供应商输出' }
          }
    )
    const wrapper = mount(SessionAttemptDiagnostics, {
      props: {
        tenantId: '7',
        sessionId: 'session',
        rootInputId: 'root-A',
        attemptId: 'attempt-1',
        scopeKey: 'account-A/7/root-A'
      }
    })
    mounted.push(wrapper)
    await flushPromises()
    const signal = calls.modelAttemptDetail.mock.calls[0][4].signal as AbortSignal
    await wrapper.setProps({ tenantId: '8', rootInputId: 'root-B', scopeKey: 'account-A/8/root-B' })
    await flushPromises()
    expect(signal.aborted).toBe(true)
    expect(wrapper.text()).toContain('B 的供应商输出')
    pending.resolve(attemptDetail())
    await flushPromises()
    expect(wrapper.text()).not.toContain('此 attempt 的真实模型输出')
    expect(calls.modelAttemptDetail.mock.calls.at(-1)?.slice(0, 4)).toEqual([
      '8',
      'session',
      'attempt-1',
      'root-B'
    ])
  })
  it('同一子attempt切换父轨迹也取消旧诊断，不能接受原父作用域的迟到私有正文', async () => {
    const pending = deferred<ModelAttemptDetail>()
    const child = {
      ...attemptDetail(),
      session_id: 'delegation:child',
      root_input_id: 'child-root'
    }
    calls.modelAttemptDetail.mockImplementation(
      async (_tenant, _session, _attempt, _root, _opts, parent) =>
        parent?.root_input_id === 'parent-root-A'
          ? pending.promise
          : { ...child, output: { ...child.output, value: '新父作用域的私有输出' } }
    )
    const wrapper = mount(SessionAttemptDiagnostics, {
      props: {
        tenantId: '7',
        sessionId: 'delegation:child',
        rootInputId: 'child-root',
        attemptId: 'attempt-1',
        scopeKey: 'account-A',
        parentSessionId: 'parent-session',
        parentRootInputId: 'parent-root-A'
      }
    })
    mounted.push(wrapper)
    await flushPromises()
    const signal = calls.modelAttemptDetail.mock.calls[0][4].signal as AbortSignal
    expect(calls.modelAttemptDetail.mock.calls[0][5]).toEqual({
      session_id: 'parent-session',
      root_input_id: 'parent-root-A'
    })
    await wrapper.setProps({ parentRootInputId: 'parent-root-B' })
    await flushPromises()
    expect(signal.aborted).toBe(true)
    expect(wrapper.text()).toContain('新父作用域的私有输出')
    pending.resolve({ ...child, output: { ...child.output, value: '旧父作用域的迟到私有输出' } })
    await flushPromises()
    expect(wrapper.text()).not.toContain('旧父作用域的迟到私有输出')
    expect(calls.modelAttemptDetail.mock.calls.at(-1)![5]).toEqual({
      session_id: 'parent-session',
      root_input_id: 'parent-root-B'
    })
  })
  it('详情读取失败可重试，缺失输入与供应商已记录思考明确分开，错误身份不渲染', async () => {
    calls.modelAttemptDetail
      .mockRejectedValueOnce(new Error('诊断读取失败'))
      .mockResolvedValueOnce({ ...attemptDetail(), tenant_id: 'wrong-tenant' })
      .mockResolvedValueOnce({
        ...attemptDetail(),
        input: {
          availability: 'NOT_RECORDED',
          source: 'FROZEN_MODEL_REQUEST',
          schema_id: null,
          value: null
        },
        reasoning: {
          availability: 'FULL',
          source: 'PROVIDER_RESPONSE',
          schema_id: null,
          value: '供应商记录的思考内容'
        },
        capability_calls_availability: 'NOT_RECORDED',
        capability_calls: []
      })
    const wrapper = mount(SessionAttemptDiagnostics, {
      props: {
        tenantId: '7',
        sessionId: 'session',
        rootInputId: 'root-A',
        attemptId: 'attempt-1',
        scopeKey: 'account-A'
      }
    })
    mounted.push(wrapper)
    await flushPromises()
    expect(document.body.querySelector('.el-message--error')?.textContent).toContain('诊断读取失败')
    await wrapper.find('button').trigger('click')
    await flushPromises()
    expect(document.body.querySelector('.el-message')?.textContent).toContain('调用详情身份不匹配')
    expect(wrapper.text()).not.toContain('此 attempt 的真实模型输出')
    await wrapper.find('button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('供应商记录的思考内容')
    expect(wrapper.text()).toContain('实际能力调用未记录')
    expect(wrapper.findAll('[data-input-message-index]')).toHaveLength(0)
  })
  it('Session推理历史退役只展示owner范围说明，无留存内容保持未知且不声称该attempt丢失', async () => {
    calls.modelAttemptDetail.mockResolvedValueOnce({
      ...attemptDetail(),
      reasoning: {
        availability: 'UNKNOWN',
        source: 'PROVIDER_REASONING',
        schema_id: null,
        value: null
      },
      reasoning_history_retention: {
        scope: 'SESSION_OWNER',
        retired_reasoning_event_count: '5',
        description:
          '所属Session推理历史部分退役，不能仅由该日志完整还原；该范围事实不能证明本次attempt曾有或丢失推理'
      }
    })
    const wrapper = mount(SessionAttemptDiagnostics, {
      props: {
        tenantId: '7',
        sessionId: 'session',
        rootInputId: 'root-A',
        attemptId: 'attempt-1',
        scopeKey: 'account-A'
      }
    })
    mounted.push(wrapper)
    await flushPromises()
    expect(wrapper.text()).toContain('所属Session推理历史部分退役，不能仅由该日志完整还原')
    expect(wrapper.text()).toContain('不能证明本次attempt曾有或丢失推理')
    expect(wrapper.text()).toContain('记录情况未知')
    expect(wrapper.text().replace(/\s+/g, '')).toContain('所属Session累计退役推理事件：5')
  })
  it('同一调用的百条来源事件不会增加调用节点或重算服务器调用数', async () => {
    const value = observationFixture()
    value.items.find((node) => node.kind === 'LLM')!.source_event_ids = Array.from(
      { length: 100 },
      (_, index) => 'model-event-' + index
    )
    calls.trajectoryNodes.mockResolvedValue(value)
    const { wrapper } = await open()
    const tree = wrapper.getComponent(ObservationTree)
    expect(
      tree.props('page')?.items.filter((node: ObservationNode) => node.kind === 'LLM')
    ).toHaveLength(1)
    expect(
      tree.props('page')?.items.filter((node: ObservationNode) => node.kind === 'TOOL')
    ).toHaveLength(1)
    expect(
      Object.fromEntries(
        tree
          .findAll('.overview-metrics > div')
          .map((metric) => [metric.get('dt').text(), metric.get('dd').text()])
      )
    ).toMatchObject({ 模型尝试: '2 次', 工具调用: '1 次' })
    expect(calls.trajectoryContent).not.toHaveBeenCalled()
  })
  it('来源事件深链归一化到实际操作节点并保留服务器snapshot', async () => {
    const { wrapper } = await open(
      'tenant_id=7&session_id=session&root_input_id=root-A&node_id=llm-A-event&tab=timeline'
    )
    const tree = wrapper.getComponent(ObservationTree)
    expect(calls.trajectoryNodes.mock.calls[0][0]).toMatchObject({
      node_id: 'llm-A-event',
      snapshot: 'snapshot:root-A'
    })
    expect(tree.props('focusNode')).toBe('llm-A')
    expect(wrapper.get('.node-detail').text()).toContain('真实模型')
  })
  it('快速切换 Turn 取消旧请求，迟到正文不能覆盖新轮次', async () => {
    const first = deferred<{ items: SessionMessage[]; next_cursor: null }>()
    calls.messages.mockImplementation(async (_tenant, _session, root) =>
      root === 'root-A'
        ? first.promise
        : { items: [message('B 的公开消息', 'root-B', 'run-B')], next_cursor: null }
    )
    const { wrapper } = await open()
    const firstSignal = calls.messages.mock.calls[0][5].signal as AbortSignal
    const button = wrapper.find('[data-round-id="root-B"]')
    await button.trigger('click')
    await flushPromises()
    expect(firstSignal.aborted).toBe(true)
    expect(wrapper.text()).toContain('B 的公开消息')
    first.resolve({ items: [message('A 的迟到正文')], next_cursor: null })
    await flushPromises()
    expect(wrapper.text()).not.toContain('A 的迟到正文')
  })
  it('来源 A 到 B 的迟到详情丢弃，请求和响应均绑定新机构', async () => {
    const first = deferred<unknown>()
    calls.detail.mockImplementation(async (tenant) =>
      tenant === '7' ? first.promise : { tenant_name: 'B机构', message_count: 1 }
    )
    const { wrapper, router } = await open()
    const signal = calls.detail.mock.calls[0][2].signal as AbortSignal
    await router.push('/platform/sessions/detail?tenant_id=8&session_id=session-B')
    await flushPromises()
    expect(signal.aborted).toBe(true)
    first.resolve({ tenant_name: 'A的迟到详情', message_count: 99 })
    await flushPromises()
    expect(wrapper.text()).toContain('B机构')
    expect(wrapper.text()).not.toContain('A的迟到详情')
    expect(calls.messages.mock.calls.at(-1)?.slice(0, 2)).toEqual(['8', 'session-B'])
  })
  it('指定深链root不在首页时精确定位，用量来自相同轨迹快照而非旧Core查询', async () => {
    calls.rounds.mockImplementation(async (_tenant, _session, _cursor, _size, root) => ({
      items: [root ? round(root, 'deep-run', '37', '370') : round('root-A', 'run-A', '42', '420')],
      next_cursor: 'round-cursor'
    }))
    const { wrapper } = await open(
      'tenant_id=7&session_id=session&root_input_id=root-deep&tab=usage'
    )
    expect(calls.rounds.mock.calls.some((args) => args[4] === 'root-deep')).toBe(true)
    expect(calls.trajectoryDetail.mock.calls[0][0].root_input_id).toBe('root-deep')
    expect(calls.usage).not.toHaveBeenCalled()
    expect(wrapper.findAll('button').some((item) => item.text() === '加载更多调用')).toBe(false)
    expect(wrapper.getComponent(ObservationTree).props('detail')?.usage?.root_input_id).toBe(
      'root-deep'
    )
    expect(wrapper.get('[data-round-id="root-A"] .round-summary > span').text()).toBe('第 42 轮')
    expect(wrapper.get('[data-round-id="root-deep"] .round-summary > span').text()).toBe('第 37 轮')
    await wrapper
      .findAll('button')
      .find((item) => item.text() === '刷新')!
      .trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-round-id="root-deep"] .round-summary > span').text()).toBe('第 37 轮')
    expect(calls.trajectoryDetail.mock.calls.at(-1)?.[0].root_input_id).toBe('root-deep')
  })
  it('全Session主Agent用量续页只追加attempt，不替换冻结汇总并明确该范围', async () => {
    const first = usage()
    first.root_input_id = null
    first.next_cursor = 'usage-cursor'
    calls.usage.mockImplementation(async (_tenant, _session, _root, cursor) =>
      cursor
        ? {
            ...first,
            totals: { ...totals, prompt_tokens: '1' },
            next_cursor: null,
            attempts: [{ ...first.attempts[0], attempt_id: 'next-attempt' }]
          }
        : first
    )
    const { wrapper } = await open('tenant_id=7&session_id=session&tab=usage')
    expect(calls.trajectoryDetail).not.toHaveBeenCalled()
    expect(calls.usage.mock.calls[0][2]).toBeUndefined()
    expect(wrapper.text()).toContain('主 Agent 模型调用（选择问答可查看含辅助和成员的完整用量）')
    const button = wrapper.findAll('button').find((item) => item.text() === '加载更多调用')!
    await button.trigger('click')
    await flushPromises()
    const label = wrapper
      .findAll('.el-descriptions__label')
      .find((item) => item.text() === '输入 Token')!
    expect(label.element.nextElementSibling?.textContent).toContain(
      '9,007,199,254,740,993,123,456,789'
    )
    expect(calls.usage.mock.calls.at(-1)?.slice(2, 4)).toEqual([undefined, 'usage-cursor'])
  })
  it('冻结请求投影区分供应商HTTP请求，正文只在主视图展示且内容块可展开', async () => {
    const value = attemptDetail()
    const original = {
      messages: [
        {
          message_id: 'mixed-1',
          role: 'user',
          content: '唯一正文',
          contents: [
            { type: 'text', text: '唯一正文' },
            { type: 'image', media_id: 'image-1' }
          ],
          tool_calls: [],
          tool_call_id: null,
          reasoning_content: null
        }
      ],
      tools: [{ wire_name: 'available-only', input_schema: '{"type":"object"}' }]
    }
    value.input = { ...value.input, source: 'ACTUAL_MODEL_REQUEST', value: original }
    calls.modelAttemptDetail.mockResolvedValueOnce(value)
    const wrapper = mount(SessionAttemptDiagnostics, {
      props: {
        tenantId: '7',
        sessionId: 'session',
        rootInputId: 'root-A',
        attemptId: 'attempt-1',
        scopeKey: 'account-A'
      }
    })
    mounted.push(wrapper)
    await flushPromises()
    expect(wrapper.text()).toContain('输入是冻结模型请求的可读投影')
    expect(wrapper.text()).toContain('供应商 HTTP 请求由适配器转换生成')
    expect(wrapper.text()).toContain('JSON 长度不代表供应商计费 Token')
    const blocks = wrapper.get('.message-blocks')
    expect(blocks.attributes('open')).toBeUndefined()
    expect(blocks.get('summary').text()).toBe('查看内容块与媒体信息')
    expect(blocks.text()).toContain('两种视图展示同一份消息')
    const viewers = wrapper.findAllComponents(JsonViewer)
    expect(viewers.filter((viewer) => viewer.props('label') === '消息 1 正文')).toHaveLength(1)
    expect(
      viewers.find((viewer) => viewer.props('label') === '消息 1 内容块')?.props('value')
    ).toEqual(original.messages[0].contents)
    expect(
      viewers.find((viewer) => viewer.props('label') === '完整冻结请求投影')?.props('value')
    ).toEqual(original)
    expect(wrapper.text()).not.toContain('完整已记录请求')
  })
})

describe('步骤读取失败只重试所属节点查询', () => {
  it.each(['未成功的新条件', '', '已应用条件'])(
    '搜索%s失败后续页绑定原成功关键词，真实树保旧条件与选中节点',
    async (failedQuery) => {
      const { wrapper } = await open()
      const tree = wrapper.getComponent(ObservationTree)
      calls.trajectoryNodes.mockResolvedValueOnce({
        ...observationFixture(),
        next_cursor: 'cursor-old'
      })
      await tree.get('form.step-search input').setValue('已应用条件')
      await tree.get('form.step-search').trigger('submit')
      await flushPromises()
      await tree.get('[data-node-id="tool-A"]').trigger('click')
      calls.trajectoryNodes.mockRejectedValueOnce(new Error('新条件搜索失败'))
      await tree.get('form.step-search input').setValue(failedQuery)
      await tree.get('form.step-search').trigger('submit')
      await flushPromises()
      calls.trajectoryNodes.mockResolvedValueOnce({
        ...observationFixture(),
        items: [observationNode('tool-B', 'TOOL', 'step-A')],
        next_cursor: 'cursor-old-next'
      })
      tree.vm.$emit('more')
      await flushPromises()
      expect(calls.trajectoryNodes).toHaveBeenLastCalledWith(
        expect.objectContaining({
          query: '已应用条件',
          cursor: 'cursor-old',
          snapshot: 'snapshot:root-A'
        }),
        expect.anything()
      )
      expect(tree.text()).toContain('当前搜索：已应用条件')
      expect((tree.get('form.step-search input').element as HTMLInputElement).value).toBe(
        failedQuery
      )
      expect(
        tree
          .get('[data-node-id="tool-A"]')
          .element.closest('.el-tree-node')
          ?.classList.contains('is-current')
      ).toBe(true)
      expect(wrapper.get('[aria-label="轨迹记录筛选"]').text()).toContain('已应用条件')
      calls.trajectoryNodes.mockResolvedValueOnce({
        ...observationFixture(),
        next_cursor: 'cursor-new'
      })
      await tree.get('form.step-search').trigger('submit')
      await flushPromises()
      tree.vm.$emit('more')
      await flushPromises()
      expect(calls.trajectoryNodes).toHaveBeenLastCalledWith(
        expect.objectContaining({ query: failedQuery || undefined, cursor: 'cursor-new' }),
        expect.anything()
      )
      expect(calls.messages).toHaveBeenCalledTimes(1)
      expect(calls.trajectoryDetail).toHaveBeenCalledTimes(1)
      expect(calls.trajectoryContent).not.toHaveBeenCalled()
    }
  )
  it('搜索失败后重试保留关键词与快照，不重读消息和轨迹摘要', async () => {
    const { wrapper } = await open()
    const tree = wrapper.findComponent(ObservationTree)
    const initialMessages = calls.messages.mock.calls.length
    const initialDetails = calls.trajectoryDetail.mock.calls.length
    calls.trajectoryNodes.mockRejectedValueOnce(new Error('节点搜索暂不可用'))
    tree.vm.$emit('search', '真实模型')
    await flushPromises()
    expect(tree.props('error')).toBe('节点搜索暂不可用')
    tree.vm.$emit('retry')
    await flushPromises()
    expect(calls.trajectoryNodes).toHaveBeenLastCalledWith(
      expect.objectContaining({
        root_input_id: 'root-A',
        query: '真实模型',
        snapshot: 'snapshot:root-A'
      }),
      expect.anything()
    )
    expect(calls.messages).toHaveBeenCalledTimes(initialMessages)
    expect(calls.trajectoryDetail).toHaveBeenCalledTimes(initialDetails)
    expect(tree.props('error')).toBe('')
  })
  it('续页失败后重试原游标并合并已有节点，不改成首页重读', async () => {
    calls.trajectoryNodes.mockResolvedValueOnce({
      ...observationFixture(),
      next_cursor: 'cursor-2'
    })
    const { wrapper } = await open()
    const tree = wrapper.findComponent(ObservationTree)
    const initialMessages = calls.messages.mock.calls.length
    const initialDetails = calls.trajectoryDetail.mock.calls.length
    calls.trajectoryNodes.mockRejectedValueOnce(new Error('续页暂不可用'))
    tree.vm.$emit('more')
    await flushPromises()
    calls.trajectoryNodes.mockResolvedValueOnce({
      ...observationFixture(),
      items: [observationNode('tool-B', 'TOOL', 'step-A')]
    })
    tree.vm.$emit('retry')
    await flushPromises()
    expect(calls.trajectoryNodes).toHaveBeenLastCalledWith(
      expect.objectContaining({ cursor: 'cursor-2', snapshot: 'snapshot:root-A' }),
      expect.anything()
    )
    expect(tree.props('page')?.items.map((item: ObservationNode) => item.node_id)).toEqual([
      'entry-A',
      'agent-A',
      'step-A',
      'llm-A',
      'tool-A',
      'tool-B'
    ])
    expect(calls.messages).toHaveBeenCalledTimes(initialMessages)
    expect(calls.trajectoryDetail).toHaveBeenCalledTimes(initialDetails)
  })
})

describe('轨迹记录表明确保留步骤搜索的结果身份', () => {
  it('在轨迹记录中直接清除筛选，失败时保留旧条件并可重试', async () => {
    const { wrapper } = await open()
    const tree = wrapper.getComponent(ObservationTree)
    await tree.get('form.step-search input').setValue('模型调用')
    await tree.get('form.step-search').trigger('submit')
    await flushPromises()
    await wrapper
      .findAll('[role="tab"]')
      .find((tab) => tab.text() === '轨迹记录')!
      .trigger('click')
    calls.trajectoryNodes.mockRejectedValueOnce(new Error('清除读取失败'))
    const clear = () =>
      wrapper
        .get('[aria-label="轨迹记录筛选"]')
        .findAll('button')
        .find((item) => item.text() === '清除步骤搜索')!
    await clear().trigger('click')
    await flushPromises()
    expect(wrapper.get('[aria-label="轨迹记录筛选"]').text()).toContain('模型调用')
    expect(wrapper.text()).toContain('轨迹读取失败，以下保留上次读取的记录')
    await clear().trigger('click')
    await flushPromises()
    expect(wrapper.find('[aria-label="轨迹记录筛选"]').exists()).toBe(false)
    expect(calls.trajectoryNodes).toHaveBeenLastCalledWith(
      expect.objectContaining({ query: undefined, snapshot: 'snapshot:root-A' }),
      expect.anything()
    )
    expect(calls.messages).toHaveBeenCalledTimes(1)
    expect(calls.trajectoryDetail).toHaveBeenCalledTimes(1)
    expect(
      wrapper
        .findAll('[role="tab"]')
        .find((tab) => tab.text() === '轨迹记录')!
        .attributes('aria-selected')
    ).toBe('true')
  })
  it('搜索后切换表格显示实际关键词，返回原搜索控件不增加读取', async () => {
    const { wrapper } = await open()
    const tree = wrapper.getComponent(ObservationTree)
    await tree.get('form.step-search input').setValue('真实模型')
    await tree.get('form.step-search').trigger('submit')
    await flushPromises()
    await wrapper
      .findAll('[role="tab"]')
      .find((tab) => tab.text() === '轨迹记录')!
      .trigger('click')
    const notice = wrapper.get('[aria-label="轨迹记录筛选"]')
    expect(notice.text()).toContain('步骤搜索“真实模型”')
    const reads = calls.trajectoryNodes.mock.calls.length
    await notice.get('button').trigger('click')
    expect(
      wrapper
        .findAll('[role="tab"]')
        .find((tab) => tab.text() === '问答与调用')!
        .attributes('aria-selected')
    ).toBe('true')
    expect((tree.get('form.step-search input').element as HTMLInputElement).value).toBe('真实模型')
    expect(calls.trajectoryNodes).toHaveBeenCalledTimes(reads)
  })
  it('失败的新搜索和清除都保留旧结果标签，成功清除后才移除', async () => {
    const { wrapper } = await open()
    const tree = wrapper.getComponent(ObservationTree)
    await tree.get('form.step-search input').setValue('已应用条件')
    await tree.get('form.step-search').trigger('submit')
    await flushPromises()
    calls.trajectoryNodes.mockRejectedValueOnce(new Error('新搜索失败'))
    await tree.get('form.step-search input').setValue('未成功条件')
    await tree.get('form.step-search').trigger('submit')
    await flushPromises()
    expect(wrapper.get('[aria-label="轨迹记录筛选"]').text()).toContain('已应用条件')
    expect(wrapper.text()).toContain('轨迹读取失败，以下保留上次读取的记录')
    expect(wrapper.get('[aria-label="轨迹记录筛选"]').text()).not.toContain('未成功条件')
    calls.trajectoryNodes.mockRejectedValueOnce(new Error('清除读取失败'))
    await tree.get('form.step-search input').setValue('')
    await tree.get('form.step-search').trigger('submit')
    await flushPromises()
    expect(wrapper.get('[aria-label="轨迹记录筛选"]').text()).toContain('已应用条件')
    await tree.get('form.step-search').trigger('submit')
    await flushPromises()
    expect(wrapper.find('[aria-label="轨迹记录筛选"]').exists()).toBe(false)
    expect(calls.trajectoryNodes).toHaveBeenLastCalledWith(
      expect.objectContaining({ query: undefined, snapshot: 'snapshot:root-A' }),
      expect.anything()
    )
    expect(calls.messages).toHaveBeenCalledTimes(1)
    expect(calls.trajectoryDetail).toHaveBeenCalledTimes(1)
  })
})

describe('轮次各视图读取失败独立恢复', () => {
  it('节点读取失败保留成功的公开消息、轨迹摘要与用量', async () => {
    calls.trajectoryNodes.mockRejectedValueOnce(new Error('节点暂不可用'))
    const { wrapper } = await open()
    expect(wrapper.getComponent(ObservationTree).props('detail')?.snapshot).toBe('snapshot:root-A')
    expect(wrapper.text()).toContain('公开过程说明')
    expect(wrapper.text()).not.toContain('没有消息记录')
    await wrapper
      .findAll('[role="tab"]')
      .find((tab) => tab.text() === '轨迹记录')!
      .trigger('click')
    expect(wrapper.text()).toContain('轨迹节点读取失败，请重新读取轨迹节点')
    expect(wrapper.text()).not.toContain('当前快照覆盖：未记录')
    await wrapper
      .findAll('button')
      .find((item) => item.text() === '重新读取轨迹节点')!
      .trigger('click')
    await flushPromises()
    expect(calls.trajectoryNodes).toHaveBeenCalledTimes(2)
    expect(calls.trajectoryNodes.mock.calls[1]?.[0]).toMatchObject({ snapshot: 'snapshot:root-A' })
    expect(calls.messages).toHaveBeenCalledTimes(1)
    expect(calls.trajectoryDetail).toHaveBeenCalledTimes(1)
    expect(calls.usage).not.toHaveBeenCalled()
    expect(wrapper.text()).not.toContain('轨迹节点读取失败，请重新读取轨迹节点')
  })

  it('消息读取失败保留成功轨迹与节点，消息区域不伪装为空记录', async () => {
    calls.messages.mockRejectedValueOnce(new Error('消息暂不可用'))
    const { wrapper } = await open()
    expect(wrapper.getComponent(ObservationTree).props('page')?.items).toHaveLength(5)
    expect(wrapper.getComponent(ObservationTree).props('detail')?.snapshot).toBe('snapshot:root-A')
    expect(wrapper.text()).not.toContain('没有消息记录')
    await wrapper
      .findAll('button')
      .find((item) => item.text() === '重新读取消息')!
      .trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('公开过程说明')
    expect(calls.messages).toHaveBeenCalledTimes(2)
    expect(calls.trajectoryDetail).toHaveBeenCalledTimes(1)
    expect(calls.trajectoryNodes).toHaveBeenCalledTimes(1)
  })

  it('全会话用量失败不丢弃已成功消息', async () => {
    calls.usage.mockRejectedValueOnce(new Error('用量暂不可用'))
    const { wrapper } = await open('tenant_id=7&session_id=session&tab=messages')
    expect(wrapper.text()).toContain('公开过程说明')
    expect(wrapper.text()).not.toContain('没有消息记录')
    await wrapper
      .findAll('button')
      .find((item) => item.text() === '重新读取用量')!
      .trigger('click')
    await flushPromises()
    expect(calls.usage).toHaveBeenCalledTimes(2)
    expect(calls.messages).toHaveBeenCalledTimes(1)
    expect(calls.trajectoryDetail).not.toHaveBeenCalled()
  })

  it('轨迹摘要失败不阻断消息，摘要重试成功后才读取其捕获快照的节点', async () => {
    calls.trajectoryDetail.mockRejectedValueOnce(new Error('摘要暂不可用'))
    const { wrapper } = await open()
    expect(wrapper.text()).toContain('公开过程说明')
    expect(calls.trajectoryNodes).not.toHaveBeenCalled()
    await wrapper
      .findAll('[role="tab"]')
      .find((tab) => tab.text() === '轨迹记录')!
      .trigger('click')
    expect(wrapper.text()).toContain('轨迹摘要读取失败，请重新读取轨迹摘要')
    await wrapper
      .findAll('button')
      .find((item) => item.text() === '重新读取轨迹摘要')!
      .trigger('click')
    await flushPromises()
    expect(calls.trajectoryDetail).toHaveBeenCalledTimes(2)
    expect(calls.trajectoryNodes).toHaveBeenCalledOnce()
    expect(calls.trajectoryNodes.mock.calls[0]?.[0]).toMatchObject({ snapshot: 'snapshot:root-A' })
    expect(calls.messages).toHaveBeenCalledTimes(1)
  })

  it('刷新摘要失败保留原轨迹并标明未刷新', async () => {
    const { wrapper } = await open()
    calls.trajectoryDetail.mockRejectedValueOnce(new Error('摘要暂不可用'))
    await wrapper
      .findAll('button')
      .find((item) => item.text() === '刷新')!
      .trigger('click')
    await flushPromises()
    await wrapper
      .findAll('[role="tab"]')
      .find((tab) => tab.text() === '轨迹记录')!
      .trigger('click')
    expect(wrapper.text()).toContain('轨迹读取失败，以下保留上次读取的记录')
    expect(wrapper.getComponent(ObservationTree).props('page')?.items).toHaveLength(5)
  })

  it('同轮次刷新使用新摘要快照，节点失败重试不沿用旧节点页快照', async () => {
    const { wrapper } = await open()
    calls.trajectoryDetail.mockResolvedValueOnce({
      ...trajectoryFixture(),
      snapshot: 'new-snapshot'
    })
    calls.trajectoryNodes.mockRejectedValueOnce(new Error('新节点页暂不可用'))
    await wrapper
      .findAll('button')
      .find((item) => item.text() === '刷新')!
      .trigger('click')
    await flushPromises()
    expect(wrapper.getComponent(ObservationTree).props('detail')?.snapshot).toBe('new-snapshot')
    expect(calls.trajectoryNodes.mock.calls.at(-1)?.[0].snapshot).toBe('new-snapshot')
    expect(wrapper.getComponent(ObservationTree).props('page')).toBeUndefined()
    calls.trajectoryNodes.mockResolvedValueOnce({
      ...observationFixture(),
      snapshot: 'new-snapshot'
    })
    await wrapper
      .findAll('button')
      .find((item) => item.text() === '重新读取轨迹节点')!
      .trigger('click')
    await flushPromises()
    expect(calls.trajectoryNodes.mock.calls.at(-1)?.[0].snapshot).toBe('new-snapshot')
    expect(calls.trajectoryDetail).toHaveBeenCalledTimes(2)
    expect(wrapper.getComponent(ObservationTree).props('page')?.snapshot).toBe('new-snapshot')
  })

  it('切换轮次后旧摘要和节点迟到都不能覆盖新轮次的消息与用量', async () => {
    const oldDetail = deferred<TrajectoryDetail>()
    calls.trajectoryDetail.mockReturnValueOnce(oldDetail.promise)
    const { wrapper } = await open()
    await wrapper.find('[data-round-id="root-B"]').trigger('click')
    await flushPromises()
    oldDetail.resolve(trajectoryFixture('root-A'))
    await flushPromises()
    expect(wrapper.getComponent(ObservationTree).props('detail')?.summary.root_input_id).toBe(
      'root-B'
    )
    expect(wrapper.text()).toContain('B 的公开消息')
    expect(calls.trajectoryNodes).toHaveBeenCalledOnce()
    expect(calls.trajectoryNodes.mock.calls[0]?.[0].root_input_id).toBe('root-B')
  })

  it('旧轮次节点迟到不能覆盖新轮次节点页', async () => {
    const oldNodes = deferred<ObservationPage>()
    calls.trajectoryNodes.mockReturnValueOnce(oldNodes.promise)
    const { wrapper } = await open()
    expect(wrapper.text()).toContain('公开过程说明')
    await wrapper.find('[data-round-id="root-B"]').trigger('click')
    await flushPromises()
    oldNodes.resolve(observationFixture('root-A'))
    await flushPromises()
    expect(wrapper.getComponent(ObservationTree).props('page')?.snapshot).toBe('snapshot:root-B')
    expect(wrapper.getComponent(ObservationTree).props('detail')?.snapshot).toBe('snapshot:root-B')
  })

  it('切换机构会话后旧Session用量迟到不覆盖新作用域', async () => {
    const oldUsage = deferred<SessionUsage>()
    calls.usage.mockReturnValueOnce(oldUsage.promise)
    const { wrapper, router } = await open('tenant_id=7&session_id=session&tab=messages')
    await router.push('/platform/sessions/detail?tenant_id=8&session_id=session-B&tab=messages')
    await flushPromises()
    oldUsage.resolve({
      ...usage(),
      attempts: usage().attempts.map((attempt) => ({ ...attempt, model: '旧机构迟到用量' }))
    })
    await flushPromises()
    expect(wrapper.text()).not.toContain('旧机构迟到用量')
    expect(calls.usage.mock.calls.at(-1)?.slice(0, 2)).toEqual(['8', 'session-B'])
  })

  it('消息成功且确实为空才显示空态', async () => {
    calls.messages.mockResolvedValueOnce({ items: [], next_cursor: null })
    const { wrapper } = await open()
    expect(wrapper.text()).toContain('没有消息记录')
    expect(calls.trajectoryNodes).toHaveBeenCalledOnce()
  })
})
