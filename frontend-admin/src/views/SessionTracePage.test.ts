import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { ElTree } from 'element-plus'
import ObservationTree from '@skillforge/shared/observation/ObservationTree.vue'
import SessionTracePage from './SessionTracePage.vue'
import type {
  ObservationNode,
  ObservationPage,
  TrajectoryDetail,
  TrajectorySummary
} from '@skillforge/shared/observation/types'

const calls = vi.hoisted(() => ({
  list: vi.fn(),
  detail: vi.fn(),
  nodes: vi.fn(),
  content: vi.fn()
}))
vi.mock('../api', () => ({ api: { trajectories: calls }, getSessionId: () => 'account-A' }))
const mounted: VueWrapper[] = []
const readAt = '2026-10-25T01:00:00Z'
function summary(session = 'session-A'): TrajectorySummary {
  return {
    tenant_id: '7',
    tenant_name: '机构',
    session_id: session,
    root_input_id: session + '-root',
    agent_id: 'agent-A',
    agent_name: '主 Agent',
    input: session + '的用户输入',
    output: '公开结果',
    status: 'COMPLETED',
    answer_status: 'COMPLETED',
    started_at: readAt,
    finished_at: readAt,
    duration_ms: '0',
    trace_ids: ['trace'],
    models: [],
    tools: [],
    step_count: 1,
    model_calls: 0,
    tool_calls: 0,
    totals: null,
    coverage: null
  }
}
function detail(session = 'session-A'): TrajectoryDetail {
  return {
    termination: null,
    summary: summary(session),
    messages: [],
    usage: null,
    snapshot: 'snapshot:' + session,
    read_at: readAt,
    usage_summary: {
      calls: 0,
      tokens: null,
      prompt_tokens: null,
      completion_tokens: null,
      amount_by_currency: {},
      unknown_usage_calls: 0,
      cost_coverage: {
        status: 'NO_COSTS',
        total_events: 0,
        priced_events: 0,
        unconfirmed_events: 0,
        missing_model_attempts: 0
      }
    }
  }
}
function node(id = 'node-A'): ObservationNode {
  return {
    node_id: id,
    parent_node_id: null,
    kind: 'AGENT',
    name: '主 Agent',
    status: 'COMPLETED',
    source_session_id: 'session-A',
    root_input_id: 'session-A-root',
    input_id: 'session-A-root',
    run_id: 'run-A',
    action_id: null,
    step_id: null,
    iteration: null,
    trace_id: 'trace-A',
    span_id: 'span-A',
    started_at: readAt,
    finished_at: readAt,
    duration_ms: '0',
    sequence: '1',
    summary: '公开过程',
    input: { ref: 'input-A', availability: 'FULL' },
    output: { ref: 'output-A', availability: 'FULL' },
    source_event_ids: ['source-event-A'],
    attempts: []
  }
}
function page(session = 'session-A'): ObservationPage {
  return {
    items: [node()],
    ancestors: [],
    next_cursor: null,
    snapshot: 'snapshot:' + session,
    coverage: 'COMPLETE',
    read_at: readAt
  }
}
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => {
    resolve = done
  })
  return { promise, resolve }
}
async function open(query = 'session_id=session-A') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/admin/trajectories/detail', component: SessionTracePage },
      { path: '/admin/trajectories', component: { template: '<div />' } },
      { path: '/admin/sessions', component: { template: '<div />' } }
    ]
  })
  await router.push('/admin/trajectories/detail?' + query)
  await router.isReady()
  const wrapper = mount(SessionTracePage, { global: { plugins: [router] } })
  mounted.push(wrapper)
  await flushPromises()
  return { wrapper, router }
}
beforeEach(() => {
  vi.clearAllMocks()
  calls.list.mockImplementation(async (input) => ({
    items: [summary(input.session_id)],
    page: 1,
    page_size: 20,
    total: 1,
    read_at: readAt
  }))
  calls.detail.mockImplementation(async (input) => detail(input.session_id))
  calls.nodes.mockImplementation(async (input) => page(input.session_id))
})
afterEach(() => {
  mounted.splice(0).forEach((wrapper) => wrapper.unmount())
})

describe('机构执行树页面接线', () => {
  it('加载多页后刷新失败，继续加载从原分页位置开始', async () => {
    calls.list.mockImplementation(async (input) => ({
      items: [{ ...summary(), root_input_id: `root-${input.page}`, input: `第 ${input.page} 页` }],
      page: input.page,
      page_size: 20,
      total: 3,
      read_at: readAt
    }))
    const { wrapper } = await open()
    await wrapper.get('button.round-more').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('第 2 页')
    calls.list.mockRejectedValueOnce(new Error('刷新失败'))
    await wrapper.findAll('button').find((button) => button.text() === '刷新')!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('第 2 页')
    await wrapper.get('button.round-more').trigger('click')
    await flushPromises()
    expect(calls.list.mock.calls.map(([input]) => input.page)).toEqual([1, 2, 1, 3])
    expect(wrapper.text()).toContain('第 3 页')
  })
  it('深链指定的业务根优先于列表首轮，直接读取对应轨迹', async () => {
    await open('session_id=session-A&root_input_id=workflow-test-input')
    expect(calls.list).toHaveBeenCalledOnce()
    expect(calls.detail.mock.calls[0]![0]).toEqual({
      session_id: 'session-A',
      root_input_id: 'workflow-test-input'
    })
  })
  it('轮次列表读取失败时仍按深链读取轨迹与定位节点', async () => {
    calls.list.mockRejectedValueOnce(new Error('轮次列表暂时不可用'))
    const { wrapper } = await open(
      'session_id=session-A&root_input_id=session-A-root&node_id=source-event-A'
    )
    expect(calls.detail.mock.calls[0]![0]).toEqual({
      session_id: 'session-A',
      root_input_id: 'session-A-root'
    })
    expect(calls.nodes.mock.calls[0]![0]).toMatchObject({ node_id: 'source-event-A' })
    expect(wrapper.getComponent(ObservationTree).props('focusNode')).toBe('node-A')
    expect(wrapper.get('aside[aria-label="业务轮次"]').text()).toContain('尚未读取')
    expect(wrapper.text()).not.toContain('本会话尚无轨迹记录')
  })
  it('先定位业务根输入，再用同一快照读取执行树；来源事件深链对应真实节点', async () => {
    const { wrapper } = await open(
      'session_id=session-A&root_input_id=session-A-root&node_id=source-event-A'
    )
    expect(calls.detail.mock.calls[0]![0]).toEqual({
      session_id: 'session-A',
      root_input_id: 'session-A-root'
    })
    expect(calls.nodes.mock.calls[0]![0]).toMatchObject({
      snapshot: 'snapshot:session-A',
      node_id: 'source-event-A'
    })
    expect(wrapper.getComponent({ name: 'ObservationTree' }).props('focusNode')).toBe('node-A')
    expect(calls.content).not.toHaveBeenCalled()
  })
  it('切换会话取消正文请求，迟到内容不能进入新会话', async () => {
    const pending = deferred<{
      content_ref: string
      availability: string
      schema_id: string
      value: string
    }>()
    calls.content.mockReturnValue(pending.promise)
    const { wrapper, router } = await open()
    wrapper.getComponent({ name: 'ObservationTree' }).vm.$emit('content', 'output-A')
    await flushPromises()
    const signal = calls.content.mock.calls[0]![1].signal as AbortSignal
    await router.push('/admin/trajectories/detail?session_id=session-B')
    await flushPromises()
    expect(signal.aborted).toBe(true)
    pending.resolve({
      content_ref: 'output-A',
      availability: 'FULL',
      schema_id: 'safe-output.v1',
      value: 'A的迟到正文'
    })
    await flushPromises()
    expect(wrapper.text()).toContain('session-B的用户输入')
    expect(wrapper.text()).not.toContain('A的迟到正文')
    expect(wrapper.getComponent({ name: 'ObservationTree' }).props('content')).toBeUndefined()
  })
  it('刷新失败保留已读节点与摘要，并在当前区域给出错误', async () => {
    const { wrapper } = await open()
    const tree = wrapper.getComponent({ name: 'ObservationTree' })
    calls.detail.mockRejectedValueOnce(new Error('轨迹暂时无法读取'))
    tree.vm.$emit('retry')
    await flushPromises()
    expect(tree.props('page').items).toHaveLength(1)
    expect(tree.props('detail').summary.input).toBe('session-A的用户输入')
    expect(tree.props('error')).toContain('轨迹暂时无法读取')
  })
  it.each(['未成功的新条件', '', '已应用条件'])(
    '搜索%s失败后真实树续页沿用原成功条件及选择，重查成功才采纳新条件',
    async (failedQuery) => {
      const { wrapper } = await open()
      const tree = wrapper.getComponent(ObservationTree)
      calls.nodes.mockResolvedValueOnce({
        ...page(),
        items: [node('old-first'), node('old-selected')],
        next_cursor: 'cursor-old'
      })
      await tree.get('form.step-search input').setValue('已应用条件')
      await tree.get('form.step-search').trigger('submit')
      await flushPromises()
      await tree.get('[data-node-id="old-selected"]').trigger('click')
      calls.nodes.mockRejectedValueOnce(new Error('搜索暂时不可用'))
      await tree.get('form.step-search input').setValue(failedQuery)
      await tree.get('form.step-search').trigger('submit')
      await flushPromises()
      calls.nodes.mockResolvedValueOnce({
        ...page(),
        items: [node('old-next')],
        next_cursor: 'cursor-old-next'
      })
      tree.vm.$emit('more')
      await flushPromises()
      expect(calls.nodes.mock.calls.at(-1)![0]).toMatchObject({
        query: '已应用条件',
        cursor: 'cursor-old',
        snapshot: 'snapshot:session-A'
      })
      expect(tree.text()).toContain('当前搜索：已应用条件')
      expect((tree.get('form.step-search input').element as HTMLInputElement).value).toBe(
        failedQuery
      )
      expect(tree.findComponent(ElTree).vm.getCurrentKey()).toBe('old-selected')
      expect(tree.props('page')?.items.map((item: ObservationNode) => item.node_id)).toEqual([
        'old-first',
        'old-selected',
        'old-next'
      ])
      calls.nodes.mockResolvedValueOnce({
        ...page(),
        items: [node('new-result')],
        next_cursor: 'cursor-new'
      })
      await tree.get('form.step-search').trigger('submit')
      await flushPromises()
      expect(calls.nodes.mock.calls.at(-1)![0]).toMatchObject({
        query: failedQuery || undefined,
        cursor: undefined
      })
      calls.nodes.mockResolvedValueOnce({ ...page(), items: [node('new-next')] })
      tree.vm.$emit('more')
      await flushPromises()
      expect(calls.nodes.mock.calls.at(-1)![0]).toMatchObject({
        query: failedQuery || undefined,
        cursor: 'cursor-new'
      })
      expect(calls.detail).toHaveBeenCalledTimes(1)
      expect(calls.content).not.toHaveBeenCalled()
    }
  )
  it('步骤搜索向服务端提交整个轨迹查询，续页按稳定节点身份合并', async () => {
    const { wrapper } = await open()
    const tree = wrapper.getComponent({ name: 'ObservationTree' })
    calls.nodes.mockResolvedValueOnce({
      ...page(),
      items: [node('node-found')],
      next_cursor: 'next-found'
    })
    tree.vm.$emit('search', '未加载的工具')
    await flushPromises()
    expect(calls.nodes.mock.calls.at(-1)![0]).toMatchObject({
      query: '未加载的工具',
      snapshot: 'snapshot:session-A'
    })
    expect(tree.props('page').items.map((value: ObservationNode) => value.node_id)).toEqual([
      'node-found'
    ])
    calls.nodes.mockResolvedValueOnce({ ...page(), items: [node('node-found'), node('node-next')] })
    tree.vm.$emit('more')
    await flushPromises()
    expect(calls.nodes.mock.calls.at(-1)![0]).toMatchObject({
      cursor: 'next-found',
      query: '未加载的工具'
    })
    expect(tree.props('page').items.map((value: ObservationNode) => value.node_id)).toEqual([
      'node-found',
      'node-next'
    ])
  })
  it('失败搜索明确重读只请求节点，全量刷新成功后采纳新快照与条件再续页', async () => {
    const { wrapper } = await open()
    const tree = wrapper.getComponent(ObservationTree)
    calls.nodes.mockRejectedValueOnce(new Error('搜索读取失败'))
    await tree.get('form.step-search input').setValue('重读条件')
    await tree.get('form.step-search').trigger('submit')
    await flushPromises()
    calls.nodes.mockResolvedValueOnce({ ...page(), items: [node('retried')] })
    const retry = [...document.querySelectorAll<HTMLButtonElement>('.el-message button')].find(
      (button) => button.textContent?.trim() === '重新读取'
    )!
    retry.click()
    await flushPromises()
    expect(calls.detail).toHaveBeenCalledTimes(1)
    expect(calls.nodes.mock.calls.at(-1)![0]).toMatchObject({
      query: '重读条件',
      snapshot: 'snapshot:session-A',
      cursor: undefined
    })
    expect(tree.findComponent(ElTree).vm.getCurrentKey()).toBe('retried')
    calls.nodes.mockRejectedValueOnce(new Error('待刷新条件失败'))
    await tree.get('form.step-search input').setValue('待刷新条件')
    await tree.get('form.step-search').trigger('submit')
    await flushPromises()
    calls.detail.mockResolvedValueOnce({ ...detail(), snapshot: 'snapshot:fresh' })
    calls.nodes.mockResolvedValueOnce({
      ...page(),
      snapshot: 'snapshot:fresh',
      next_cursor: 'cursor:fresh'
    })
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '刷新')!
      .trigger('click')
    await flushPromises()
    expect(tree.props('error')).toBe('')
    expect(tree.text()).toContain('当前搜索：待刷新条件')
    tree.vm.$emit('more')
    await flushPromises()
    expect(calls.nodes.mock.calls.at(-1)![0]).toMatchObject({
      query: '待刷新条件',
      snapshot: 'snapshot:fresh',
      cursor: 'cursor:fresh'
    })
    expect(calls.content).not.toHaveBeenCalled()
  })
})
