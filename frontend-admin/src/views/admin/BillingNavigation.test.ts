import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import { ElDatePicker, ElPagination, ElSelect } from 'element-plus'
import { clearFeedback, confirm } from '@skillforge/shared/feedback'
import BillingRecordsPage from './BillingRecordsPage.vue'
import Layout from './Layout.vue'
import applicationRouter from '../../router'

const mocks = vi.hoisted(() => ({
  records: vi.fn(),
  requests: vi.fn(),
  filterOptions: vi.fn(),
  monthly: vi.fn(),
  requestDetail: vi.fn(),
  reconciliationProvider: { get: vi.fn(), save: vi.fn() }
}))
vi.mock('../../api', () => ({ api: { billing: mocks }, foundationRequest: vi.fn() }))
vi.mock('@skillforge/shared/feedback', async (load) => ({
  ...(await load<typeof import('@skillforge/shared/feedback')>()),
  confirm: vi.fn()
}))
vi.mock('../../stores/auth', () => ({
  useAuthStore: () => ({
    isAuthenticated: true,
    user: { tenant_id: 'tenant', sub: 'member', permissions: ['billing.read', 'billing.update'] }
  })
}))
vi.mock('@skillforge/shared/foundation/navigation', async (load) => {
  const actual = await load<typeof import('@skillforge/shared/foundation/navigation')>()
  const { ref } = await import('vue')
  return {
    ...actual,
    useFoundationNavigation: () => ({ menus: ref([]), error: ref(''), reload: vi.fn() })
  }
})
vi.mock('@skillforge/shared/foundation/appearance', async (load) => {
  const actual = await load<typeof import('@skillforge/shared/foundation/appearance')>()
  const { ref } = await import('vue')
  return {
    ...actual,
    useFoundationAppearance: () => ({
      personal: ref(null),
      branding: ref(null),
      loading: ref(false),
      saving: ref(false),
      error: ref(''),
      reload: vi.fn(),
      savePersonal: vi.fn()
    })
  }
})
const wrappers: VueWrapper[] = []
beforeEach(() => {
  vi.resetAllMocks()
  mocks.records.mockImplementation(async (query) => ({
    items: [{ event_type: 'request', model: `page-${query.page}`, root_input_id: 'root' }],
    total: 61
  }))
  mocks.requests.mockResolvedValue({ items: [], total: 0 })
  mocks.filterOptions.mockResolvedValue({
    models: ['alpha'],
    eventTypes: [{ value: 'request', label: '请求' }]
  })
  mocks.monthly.mockImplementation(async (query) => ({
    ...query,
    currency: 'CNY',
    model_calls: 0,
    known_amount: '0',
    total_amount: '0',
    missing_cost_count: 0,
    prompt_tokens: '0',
    completion_tokens: '0',
    total: 61,
    items: []
  }))
  mocks.reconciliationProvider.get.mockResolvedValue({
    provider: null,
    endpoint: null,
    api_key_configured: false,
    api_key_header: null,
    response_schema: {}
  })
})
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  clearFeedback()
  document.body.innerHTML = ''
})
async function render(path = '/admin/billing-records') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      {
        path: '/admin',
        component: Layout,
        children: [
          {
            path: 'billing-records',
            component: BillingRecordsPage,
            meta: applicationRouter
              .getRoutes()
              .find((route) => route.path === '/admin/billing-records')!.meta
          },
          { path: 'home', component: { template: '<p>工作台</p>' } }
        ]
      }
    ]
  })
  await router.push(path)
  await router.isReady()
  const wrapper = mount(RouterView, { attachTo: document.body, global: { plugins: [router] } })
  wrappers.push(wrapper)
  await flushPromises()
  return { router, wrapper }
}
async function click(wrapper: VueWrapper, label: string) {
  await wrapper
    .findAll('button')
    .find((button) => button.text() === label)!
    .trigger('click')
  await flushPromises()
}
async function tab(wrapper: VueWrapper, label: string) {
  await wrapper
    .findAll('[role="tab"]')
    .find((item) => item.text() === label)!
    .trigger('click')
  await flushPromises()
}
it('已查询条件和页码支持离开返回及刷新，未查询的输入在翻页时保留而不应用', async () => {
  const { router, wrapper } = await render('/admin/billing-records?source=audit')
  wrapper.getComponent(ElDatePicker).vm.$emit('update:modelValue', ['2026-11-01', '2026-11-26'])
  wrapper.findAllComponents(ElSelect)[1]!.vm.$emit('update:modelValue', 'request')
  await click(wrapper, '查询')
  wrapper.findAllComponents(ElSelect)[0]!.vm.$emit('update:modelValue', 'alpha')
  wrapper.getComponent(ElPagination).vm.$emit('current-change', 2)
  await flushPromises()
  expect(mocks.records.mock.lastCall?.[0]).toEqual(
    expect.objectContaining({
      from: '2026-11-01',
      to: '2026-11-26',
      eventType: 'request',
      model: '',
      page: 2
    })
  )
  expect(wrapper.findAllComponents(ElSelect)[0]!.props('modelValue')).toBe('alpha')
  expect(router.currentRoute.value.query).toEqual(
    expect.objectContaining({
      source: 'audit',
      event_type: 'request',
      from: '2026-11-01',
      to: '2026-11-26',
      page: '2'
    })
  )
  expect(router.currentRoute.value.query.model).toBeUndefined()
  const location = router.currentRoute.value.fullPath
  await router.push('/admin/home')
  router.back()
  await flushPromises()
  expect(wrapper.getComponent(ElPagination).props('currentPage')).toBe(2)
  expect(wrapper.findAllComponents(ElSelect)[1]!.props('modelValue')).toBe('request')
  const refreshed = await render(location)
  expect(refreshed.wrapper.text()).toContain('page-2')
  expect(refreshed.wrapper.getComponent(ElDatePicker).props('modelValue')).toEqual([
    '2026-11-01',
    '2026-11-26'
  ])
  expect(document.title).toContain('计费记录')
})
it('首次慢读和失败不显示零条，已有结果刷新失败保留正确页码，再次翻页可恢复', async () => {
  let reject!: (reason: Error) => void
  mocks.records.mockReturnValueOnce(
    new Promise((_, fail) => {
      reject = fail
    })
  )
  const { wrapper } = await render()
  expect(wrapper.text()).toContain('正在读取计费记录')
  expect(wrapper.findComponent(ElPagination).exists()).toBe(false)
  reject(new Error('读取被阻断'))
  await flushPromises()
  expect(wrapper.text()).toContain('尚未取得计费记录')
  expect(wrapper.text()).not.toContain('当前查询没有计费事件')
  await click(wrapper, '查询')
  mocks.records.mockRejectedValueOnce(new Error('翻页失败'))
  wrapper.getComponent(ElPagination).vm.$emit('current-change', 2)
  await flushPromises()
  expect(wrapper.text()).toContain('保留上次成功读取的结果')
  expect(wrapper.text()).toContain('page-1')
  expect(wrapper.getComponent(ElPagination).props('currentPage')).toBe(1)
  wrapper.getComponent(ElPagination).vm.$emit('current-change', 2)
  await flushPromises()
  expect(wrapper.text()).toContain('page-2')
  expect(wrapper.getComponent(ElPagination).props('currentPage')).toBe(2)
})
it('真实空结果与首次失败分开，请求汇总标签刷新后直接读取请求', async () => {
  const { wrapper } = await render('/admin/billing-records?tab=requests&run_id=run-1&page_size=50')
  expect(mocks.records).not.toHaveBeenCalled()
  expect(mocks.requests.mock.lastCall?.[0]).toEqual(
    expect.objectContaining({ runId: 'run-1', page: 1, pageSize: 50 })
  )
  expect(wrapper.text()).toContain('当前查询没有请求汇总')
  expect(wrapper.getComponent(ElPagination).props('total')).toBe(0)
})
it('月账单恢复月份和分页，切换标签不会应用未查询月份', async () => {
  const { router, wrapper } = await render(
    '/admin/billing-records?tab=monthly&month=2026-10&monthly_page=2&monthly_page_size=50'
  )
  expect(mocks.records).not.toHaveBeenCalled()
  expect(mocks.monthly.mock.lastCall?.[0]).toEqual({ month: '2026-10', page: 2, page_size: 50 })
  wrapper.getComponent(ElDatePicker).vm.$emit('update:modelValue', '2026-09')
  await tab(wrapper, '计费事件')
  await tab(wrapper, '模型月账单')
  expect(wrapper.getComponent(ElDatePicker).props('modelValue')).toBe('2026-09')
  expect(router.currentRoute.value.query.month).toBe('2026-10')
  expect(wrapper.text()).toContain('月份已修改，查询后生效')
  await wrapper.get('form.admin-filters').trigger('submit')
  await flushPromises()
  expect(router.currentRoute.value.query.month).toBe('2026-09')
  expect(router.currentRoute.value.query.monthly_page).toBeUndefined()
})
it('同路径外部查询变化重新读取正确标签，非法页码使用默认值', async () => {
  const { router, wrapper } = await render('/admin/billing-records?tab=bad&page=-2&page_size=9')
  expect(mocks.records.mock.lastCall?.[0]).toEqual(
    expect.objectContaining({ page: 1, pageSize: 20 })
  )
  await router.push('/admin/billing-records?tab=requests&page=2')
  await flushPromises()
  expect(mocks.requests.mock.lastCall?.[0]).toEqual(expect.objectContaining({ page: 2 }))
  expect(wrapper.find('[aria-label="运行标识筛选"]').exists()).toBe(true)
})
it('对账首次读取失败不能展示可编辑空配置，重试恢复；草稿取消切换保留且确认仅出现一次', async () => {
  mocks.reconciliationProvider.get.mockRejectedValueOnce(new Error('配置读取失败'))
  const { router, wrapper } = await render('/admin/billing-records?tab=reconciliation')
  expect(wrapper.text()).toContain('尚未取得对账配置')
  expect(wrapper.find('form.billing-settings').exists()).toBe(false)
  await click(wrapper, '重新读取对账配置')
  await wrapper
    .get('input[name="billing-reconciliation-endpoint"]')
    .setValue('http://localhost:11740/draft')
  vi.mocked(confirm).mockResolvedValueOnce(false)
  await tab(wrapper, '计费事件')
  expect(wrapper.find('form.billing-settings').exists()).toBe(true)
  expect(router.currentRoute.value.query.tab).toBe('reconciliation')
  vi.mocked(confirm).mockResolvedValueOnce(true)
  await tab(wrapper, '计费事件')
  expect(vi.mocked(confirm)).toHaveBeenCalledTimes(2)
  expect(router.currentRoute.value.query.tab).toBe('records')
  expect(router.currentRoute.value.fullPath).not.toContain('localhost')
  expect(mocks.reconciliationProvider.save).not.toHaveBeenCalled()
})
