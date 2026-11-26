import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import { ElInput, ElPagination, ElSelect } from 'element-plus'
import { clearFeedback } from '@skillforge/shared/feedback'
import MarketplacePage from './MarketplacePage.vue'
import Layout from './Layout.vue'
import applicationRouter from '../../router'

const mocks = vi.hoisted(() => ({ search: vi.fn() }))
vi.mock('../../api', () => ({ api: { marketplace: mocks }, foundationRequest: vi.fn() }))
vi.mock('../../stores/auth', () => ({
  useAuthStore: () => ({
    isAuthenticated: true,
    user: { tenant_id: 'tenant', sub: 'member', permissions: [] }
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
  mocks.search.mockImplementation(async (query, _type, category, page) => ({
    items: [
      {
        workflow_id: `flow-${page}`,
        name: `${query}/${category ?? ''}/第${page}页`,
        category: '办公'
      }
    ],
    total: 61
  }))
})
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  clearFeedback()
  document.body.innerHTML = ''
})
async function render(path = '/admin/marketplace') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      {
        path: '/admin',
        component: Layout,
        children: [
          {
            path: 'marketplace',
            component: MarketplacePage,
            meta: applicationRouter
              .getRoutes()
              .find((route) => route.path === '/admin/marketplace')!.meta
          },
          { path: 'home', component: { template: '<p>工作台</p>' } },
          { path: 'workflows', component: { template: '<p>机构工作流列表</p>' } },
          { path: 'workflows/:id', component: { template: '<p>工作流详情</p>' } }
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
async function click(wrapper: VueWrapper, text: string) {
  await wrapper
    .findAll('button')
    .find((button) => button.text() === text)!
    .trigger('click')
  await flushPromises()
}

it('搜索后保留条件和页码，详情返回及刷新恢复；翻页不应用输入中的新关键词', async () => {
  const { wrapper, router } = await render('/admin/marketplace?source=audit')
  wrapper.getComponent(MarketplacePage).getComponent(ElInput).vm.$emit('update:modelValue', '报销')
  wrapper.getComponent(ElSelect).vm.$emit('update:modelValue', '办公')
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  wrapper
    .getComponent(MarketplacePage)
    .getComponent(ElInput)
    .vm.$emit('update:modelValue', '未搜索内容')
  wrapper.getComponent(ElPagination).vm.$emit('current-change', 2)
  await flushPromises()
  expect(wrapper.text()).toContain('报销/办公/第2页')
  expect(wrapper.getComponent(MarketplacePage).getComponent(ElInput).props('modelValue')).toBe(
    '未搜索内容'
  )
  expect(router.currentRoute.value.query).toEqual({
    source: 'audit',
    q: '报销',
    category: '办公',
    page: '2'
  })
  const location = router.currentRoute.value.fullPath
  await click(wrapper, '查看与安装')
  expect(router.currentRoute.value.path).toBe('/admin/workflows/flow-2')
  router.back()
  await flushPromises()
  expect(wrapper.getComponent(MarketplacePage).getComponent(ElInput).props('modelValue')).toBe(
    '报销'
  )
  expect(wrapper.getComponent(ElPagination).props('currentPage')).toBe(2)
  const refreshed = await render(location)
  expect(refreshed.wrapper.text()).toContain('报销/办公/第2页')
})

it('首次慢读和失败没有零条分页；明确重试后成功空结果提供机构工作流入口', async () => {
  let fail!: (error: Error) => void
  mocks.search.mockReturnValueOnce(
    new Promise((_, reject) => {
      fail = reject
    })
  )
  const { wrapper, router } = await render()
  expect(wrapper.text()).toContain('正在读取工作流市场')
  expect(wrapper.findComponent(ElPagination).exists()).toBe(false)
  fail(new Error('连接中断'))
  await flushPromises()
  expect(wrapper.text()).toContain('尚未取得市场内容')
  expect(wrapper.findComponent(ElPagination).exists()).toBe(false)
  expect(wrapper.text()).not.toContain('市场尚无')
  mocks.search.mockResolvedValueOnce({ items: [], total: 0 })
  await click(wrapper, '重新读取市场')
  expect(wrapper.text()).toContain('市场尚无已发布的平台工作流')
  expect(wrapper.getComponent(ElPagination).props('total')).toBe(0)
  await click(wrapper, '管理本机构工作流')
  expect(router.currentRoute.value.path).toBe('/admin/workflows')
})

it('新搜索失败不替换地址和旧结果，保留输入；清空筛选成功后更新地址', async () => {
  const { wrapper, router } = await render('/admin/marketplace?q=报销&category=办公&page=2')
  wrapper
    .getComponent(MarketplacePage)
    .getComponent(ElInput)
    .vm.$emit('update:modelValue', '新条件')
  mocks.search.mockRejectedValueOnce(new Error('查询失败'))
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  expect(router.currentRoute.value.query).toEqual({ q: '报销', category: '办公', page: '2' })
  expect(wrapper.getComponent(ElPagination).props('currentPage')).toBe(2)
  expect(wrapper.getComponent(MarketplacePage).getComponent(ElInput).props('modelValue')).toBe(
    '新条件'
  )
  expect(wrapper.text()).toContain('保留上次成功读取的结果')
  mocks.search.mockResolvedValueOnce({ items: [], total: 0 })
  await click(wrapper, '重置')
  expect(router.currentRoute.value.query).toEqual({})
  expect(wrapper.text()).toContain('市场尚无已发布的平台工作流')
})

it('同路径新查询使旧请求失效，迟到结果不能把用户带回旧条件', async () => {
  const { wrapper, router } = await render('/admin/marketplace?q=旧条件&page=bad')
  let finish!: (value: { items: { workflow_id: string; name: string }[]; total: number }) => void
  mocks.search.mockReturnValueOnce(
    new Promise((resolve) => {
      finish = resolve
    })
  )
  wrapper.getComponent(ElPagination).vm.$emit('current-change', 2)
  await flushPromises()
  const signal = mocks.search.mock.lastCall?.[4].signal as AbortSignal
  await router.push('/admin/marketplace?q=新地址&page=3')
  await flushPromises()
  expect(signal.aborted).toBe(true)
  finish({ items: [{ workflow_id: 'old', name: '迟到旧结果' }], total: 61 })
  await flushPromises()
  expect(router.currentRoute.value.query).toEqual({ q: '新地址', page: '3' })
  expect(wrapper.text()).toContain('新地址//第3页')
  expect(wrapper.text()).not.toContain('迟到旧结果')
})
