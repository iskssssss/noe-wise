import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import ElementPlus, { ElPagination, ElSelect } from 'element-plus'
import TrajectoryListView from './TrajectoryListView.vue'
import type {
  TrajectoryListPage,
  TrajectoryListQuery,
  TrajectorySummary
} from '../observation/types'
import type { RequestOptions } from '../utils/http'

const wrappers: VueWrapper[] = []
beforeAll(() => {
  if (!globalThis.ResizeObserver)
    globalThis.ResizeObserver = class {
      observe() {}
      unobserve() {}
      disconnect() {}
    }
})
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  document.body.innerHTML = ''
  vi.restoreAllMocks()
})

const row: TrajectorySummary = {
  tenant_id: '9007199254740993',
  tenant_name: '测试机构',
  session_id: 'session-owned',
  root_input_id: 'root-original',
  agent_id: '9223372036854775806',
  agent_name: '知识助手',
  input: '如何做评估',
  output: '请按以下步骤设置评估',
  status: 'COMPLETED',
  answer_status: 'COMPLETED',
  started_at: '2026-10-25T00:00:00Z',
  finished_at: '2026-10-25T00:00:02Z',
  duration_ms: '2000',
  trace_ids: ['trace-original'],
  models: ['model-original'],
  tools: ['knowledge_query'],
  step_count: 2,
  model_calls: 2,
  tool_calls: 1,
  totals: {
    prompt_tokens: '9007199254740993',
    completion_tokens: '7',
    cached_input_tokens: '4',
    cache_write_input_tokens: null,
    reasoning_output_tokens: '2'
  },
  coverage: {
    status: 'COMPLETE',
    total_attempts: 2,
    reported_attempts: 2,
    unknown_attempts: 0,
    partial_attempts: 0,
    zero_usage_attempts: 0
  }
}
function result(items: TrajectorySummary[] = [row], page = 1): TrajectoryListPage {
  return { items, page, page_size: 20, total: 45, read_at: '2026-10-25T00:00:03Z' }
}
function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((yes, no) => {
    resolve = yes
    reject = no
  })
  return { promise, resolve, reject }
}
type Loader = (query: TrajectoryListQuery, options?: RequestOptions) => Promise<TrajectoryListPage>
function render(load: Loader = vi.fn<Loader>(async () => result())) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/platform/trajectories/detail', component: { template: '<div />' } }
    ]
  })
  const wrapper = mount(TrajectoryListView, {
    props: {
      load,
      identity: 'account-A',
      detailPath: '/platform/trajectories/detail',
      showTenant: true
    },
    global: { plugins: [ElementPlus, router] }
  })
  wrappers.push(wrapper)
  return wrapper
}
function button(wrapper: VueWrapper, text: string) {
  const value = wrapper.findAll('button').find((item) => item.text() === text)
  if (!value) throw new Error(`找不到按钮：${text}`)
  return value
}
function traceSelect(wrapper: VueWrapper) {
  const value = wrapper
    .findAllComponents(ElSelect)
    .find((component) => component.attributes('data-testid') === 'trajectory-trace')
  if (!value) throw new Error('找不到 TraceID 选择器')
  return value
}
async function setTrace(wrapper: VueWrapper, value: string) {
  traceSelect(wrapper).vm.$emit('update:modelValue', value)
  await flushPromises()
}

describe('轨迹列表查询与身份隔离', () => {
  it('刷新默认近七天时推进时间窗口并回到第一页，保留未提交的筛选', async () => {
    const now = vi.spyOn(Date, 'now').mockReturnValue(Date.parse('2026-10-25T00:00:00Z'))
    const load = vi.fn<Loader>(async (query) => result([row], query.page))
    const wrapper = render(load)
    await flushPromises()
    await wrapper.get('.btn-next').trigger('click')
    await setTrace(wrapper, '未提交')
    now.mockReturnValue(Date.parse('2026-10-26T00:00:00Z'))
    await button(wrapper, '刷新').trigger('click')
    await flushPromises()
    expect(load.mock.lastCall?.[0]).toMatchObject({
      page: 1,
      trace_id: '',
      started_from: '2026-10-19T08:00:00+08:00',
      started_to: '2026-10-26T08:00:00+08:00'
    })
    expect(wrapper.text()).toContain('条件已修改')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(load.mock.lastCall?.[0].trace_id).toBe('未提交')
  })

  it('用户选择固定时间后刷新不改变时间窗口', async () => {
    const now = vi.spyOn(Date, 'now').mockReturnValue(Date.parse('2026-10-25T00:00:00Z'))
    const load = vi.fn<Loader>(async () => result())
    const wrapper = render(load)
    await flushPromises()
    const picker = wrapper.getComponent({ name: 'ElDatePicker' })
    picker.vm.$emit('update:modelValue', ['2026-10-01 00:00:00', '2026-10-02 00:00:00'])
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    const fixed = load.mock.lastCall?.[0]
    now.mockReturnValue(Date.parse('2026-10-26T00:00:00Z'))
    await button(wrapper, '刷新').trigger('click')
    await flushPromises()
    expect(load.mock.lastCall?.[0]).toEqual(fixed)
  })

  it('展示输入结果、精确Token总量和绑定原机构会话根输入的深链', async () => {
    const wrapper = render()
    await flushPromises()
    expect(wrapper.text()).toContain('如何做评估')
    expect(wrapper.text()).toContain('请按以下步骤设置评估')
    expect(wrapper.text()).toContain('9,007,199,254,741,000')
    expect(wrapper.text()).toContain('2.00 s')
    expect(wrapper.get('a.trajectory-input').attributes('href')).toContain(
      'tenant_id=9007199254740993'
    )
    expect(wrapper.get('a.trajectory-input').attributes('href')).toContain(
      'session_id=session-owned'
    )
    expect(wrapper.get('a.trajectory-input').attributes('href')).toContain(
      'root_input_id=root-original'
    )
  })

  it('TraceID 空关键词复用当前结果，不重复读取整页轨迹', async () => {
    const load = vi.fn<Loader>(async () => result())
    const wrapper = render(load)
    await flushPromises()
    expect(load).toHaveBeenCalledTimes(1)
    await traceSelect(wrapper).props('remoteMethod')('')
    await flushPromises()
    expect(load).toHaveBeenCalledTimes(1)
  })

  it('翻页使用已提交条件，查询后采用新关键词并保持大ID和毫秒字符串', async () => {
    const load = vi.fn<Loader>(async (query) => result([row], query.page))
    const wrapper = render(load)
    await flushPromises()
    await setTrace(wrapper, 'trace-new')
    await wrapper.get('input[aria-label="Agent ID"]').setValue('9223372036854775806')
    await wrapper.get('input[aria-label="最短耗时（毫秒）"]').setValue('9007199254740993')
    await wrapper.get('.btn-next').trigger('click')
    await flushPromises()
    expect(load.mock.lastCall?.[0]).toMatchObject({ page: 2, trace_id: '', agent_id: '' })
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(load.mock.lastCall?.[0]).toMatchObject({
      page: 1,
      trace_id: 'trace-new',
      agent_id: '9223372036854775806',
      duration_min_ms: '9007199254740993'
    })
    expect(load.mock.lastCall?.[0].started_from).toMatch(/\+08:00$/)
  })

  it('新筛选读取失败保留旧轨迹和原条件，重读成功后才更新结果条件', async () => {
    const load = vi
      .fn<Loader>()
      .mockResolvedValueOnce(result())
      .mockRejectedValueOnce(new Error('读取失败'))
      .mockResolvedValueOnce(result([{ ...row, input: '新筛选结果' }]))
    const wrapper = render(load)
    await flushPromises()
    await setTrace(wrapper, 'trace-new')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('如何做评估')
    expect(wrapper.text()).toContain('未成功的筛选或页码尚未生效')
    expect(wrapper.text()).toContain('条件已修改，点击查询后生效')
    expect(wrapper.text()).not.toContain('TraceID：trace-new')
    expect(wrapper.getComponent(ElPagination).props('disabled')).toBe(true)
    await button(wrapper, '重新读取').trigger('click')
    await flushPromises()
    expect(load.mock.calls[2]![0]).toEqual(load.mock.calls[1]![0])
    expect(wrapper.text()).toContain('新筛选结果')
    expect(wrapper.text()).toContain('TraceID：trace-new')
    expect(wrapper.getComponent(ElPagination).props('disabled')).toBe(false)
    expect(wrapper.text()).not.toContain('条件已修改，点击查询后生效')
  })

  it('目标页读取失败后，再次点击该页可重新读取并保留原查询条件', async () => {
    const load = vi
      .fn<Loader>()
      .mockResolvedValueOnce(result())
      .mockRejectedValueOnce(new Error('读取失败'))
      .mockResolvedValueOnce(result([{ ...row, input: '第二页结果' }], 2))
    const wrapper = render(load)
    await flushPromises()
    await wrapper.get('.btn-next').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('本次刷新未完成')
    expect(wrapper.text()).toContain('如何做评估')
    await wrapper.get('.btn-next').trigger('click')
    await flushPromises()
    expect(load).toHaveBeenCalledTimes(3)
    expect(load.mock.calls[2]![0]).toEqual(load.mock.calls[1]![0])
    expect(wrapper.text()).toContain('第二页结果')
  })

  it('页大小读取失败后可以再次选择同一大小', async () => {
    const load = vi
      .fn<Loader>()
      .mockResolvedValueOnce(result())
      .mockRejectedValueOnce(new Error('读取失败'))
      .mockResolvedValueOnce({ ...result(), page_size: 50 })
    const wrapper = render(load)
    await flushPromises()
    wrapper.getComponent(ElPagination).vm.$emit('size-change', 50)
    await flushPromises()
    wrapper.getComponent(ElPagination).vm.$emit('size-change', 50)
    await flushPromises()
    expect(load).toHaveBeenCalledTimes(3)
    expect(load.mock.calls[2]![0]).toMatchObject({ page: 1, page_size: 50 })
  })

  it('刷新失败保留已有结果，账号切换立即清除并拒绝旧请求迟到回填', async () => {
    const old = deferred<TrajectoryListPage>(),
      current = deferred<TrajectoryListPage>()
    const load = vi
      .fn<Loader>()
      .mockResolvedValueOnce(result())
      .mockRejectedValueOnce(new Error('读取失败'))
      .mockReturnValueOnce(old.promise)
      .mockReturnValueOnce(current.promise)
    const wrapper = render(load)
    await flushPromises()
    await button(wrapper, '刷新').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('本次刷新未完成')
    expect(wrapper.text()).toContain('如何做评估')
    await button(wrapper, '重新读取').trigger('click')
    await wrapper.setProps({ identity: 'account-B' })
    expect(wrapper.text()).not.toContain('如何做评估')
    expect(load.mock.calls[2]![1]!.signal!.aborted).toBe(true)
    current.resolve(result([{ ...row, input: '新账号有权查看的问题' }]))
    await flushPromises()
    old.resolve(result([{ ...row, input: '旧账号迟到问题' }]))
    await flushPromises()
    expect(wrapper.text()).toContain('新账号有权查看的问题')
    expect(wrapper.text()).not.toContain('旧账号迟到问题')
  })

  it('耗时边界非法时保留当前结果并明确拒绝查询，不将字符串截断或转浮点', async () => {
    const load = vi.fn<Loader>(async () => result())
    const wrapper = render(load)
    await flushPromises()
    await wrapper.get('input[aria-label="最短耗时（毫秒）"]').setValue('1e6')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(load).toHaveBeenCalledTimes(1)
    expect(document.querySelector('.el-message--error')?.textContent).toContain(
      '不使用小数或科学计数法'
    )
    expect(wrapper.text()).toContain('如何做评估')
  })
})
