import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import { clearFeedback } from '@skillforge/shared/feedback'
import ConnectorsPage from './ConnectorsPage.vue'
import Layout from './Layout.vue'
import applicationRouter from '../../router'

const mocks = vi.hoisted(() => ({ list: vi.fn() }))
vi.mock('../../api', () => ({
  api: { connectors: { instances: mocks.list } },
  foundationRequest: vi.fn()
}))
vi.mock('../../stores/auth', () => ({
  useAuthStore: () => ({
    isAuthenticated: true,
    user: { tenant_id: 'tenant', sub: 'member', permissions: ['foundation.connectors.read'] }
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
  sessionStorage.clear()
  mocks.list.mockReset().mockResolvedValue({
    items: [
      {
        id: '1',
        instance_id: 'one',
        name: '本地验收',
        base_url: 'http://localhost:11740/mcp',
        status: 'active'
      },
      {
        id: '2',
        instance_id: 'two',
        name: '资料查询',
        base_url: 'https://example.org/mcp',
        status: 'disabled'
      }
    ],
    total: 2
  })
})
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  clearFeedback()
  document.body.innerHTML = ''
})

async function render(path = '/admin/connectors') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      {
        path: '/admin',
        component: Layout,
        children: [
          {
            path: 'connectors',
            component: ConnectorsPage,
            meta: applicationRouter.getRoutes().find((route) => route.path === '/admin/connectors')!
              .meta
          },
          { path: 'tools', component: { template: '<p>工具接入</p>' } }
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

it('按名称或地址搜索后进入工具页，返回和重新打开地址均恢复条件与结果', async () => {
  const { router, wrapper } = await render()
  await wrapper.get('input[aria-label="查找连接器"]').setValue(' LOCALHOST ')
  await flushPromises()
  const location = router.currentRoute.value.fullPath
  expect(wrapper.findAll('tbody tr').map((row) => row.text())).toEqual([
    expect.stringContaining('本地验收')
  ])
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '接入工具')!
    .trigger('click')
  await flushPromises()
  expect(router.currentRoute.value.query.connectorId).toBe('one')
  router.back()
  await flushPromises()
  expect((wrapper.get('input[aria-label="查找连接器"]').element as HTMLInputElement).value).toBe(
    ' LOCALHOST '
  )
  const refreshed = await render(location)
  expect(refreshed.wrapper.findAll('tbody tr')).toHaveLength(1)
  expect(refreshed.wrapper.text()).toContain('本地验收')
  expect(refreshed.wrapper.text()).not.toContain('资料查询')
})

it('连续输入保留焦点，无匹配可清空且不堆积历史或重新读取列表', async () => {
  const { router, wrapper } = await render('/admin/connectors?source=tools')
  const search = wrapper.get('input[aria-label="查找连接器"]')
  ;(search.element as HTMLInputElement).focus()
  await search.setValue('未')
  await flushPromises()
  await search.setValue('未匹配')
  await flushPromises()
  expect(router.currentRoute.value.query.q).toBe('未匹配')
  expect(document.activeElement).toBe(search.element)
  expect(wrapper.text()).toContain('没有匹配的连接器，清空关键词后查看全部')
  await search.setValue('')
  await flushPromises()
  expect(router.currentRoute.value.query).toEqual({ source: 'tools' })
  expect(wrapper.findAll('tbody tr')).toHaveLength(2)
  expect(mocks.list).toHaveBeenCalledTimes(1)
  await router.push('/admin/tools')
  router.back()
  await flushPromises()
  expect(router.currentRoute.value.fullPath).toBe('/admin/connectors?source=tools')
})

it('首次读取失败不伪装为空列表，重试和已有内容刷新失败保持真实结果', async () => {
  let rejectRead!: (reason: Error) => void
  mocks.list.mockReturnValueOnce(
    new Promise((_, reject) => {
      rejectRead = reject
    })
  )
  const { wrapper } = await render('/admin/connectors?q=本地')
  expect(wrapper.text()).toContain('正在读取连接器…')
  expect(wrapper.text()).not.toContain('0 个连接器')
  expect(wrapper.text()).not.toContain('尚未接入 MCP 服务')
  rejectRead(new Error('连接器暂时无法读取'))
  await flushPromises()
  expect(wrapper.text()).toContain('尚未取得连接器列表，请点击刷新连接器重新读取')
  expect(wrapper.text()).not.toContain('0 个连接器')
  const refresh = wrapper.findAll('button').find((button) => button.text() === '刷新连接器')!
  await refresh.trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('1 个连接器')
  expect(wrapper.findAll('tbody tr')).toHaveLength(1)
  mocks.list.mockRejectedValueOnce(new Error('刷新失败'))
  await refresh.trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('本地验收')
  expect(wrapper.text()).toContain('1 个连接器')
  expect((wrapper.get('input[aria-label="查找连接器"]').element as HTMLInputElement).value).toBe(
    '本地'
  )
})

it('真实空列表显示新增入口和下一步说明', async () => {
  mocks.list.mockResolvedValueOnce({ items: [], total: 0 })
  const { wrapper } = await render()
  expect(wrapper.text()).toContain('0 个连接器')
  expect(wrapper.text()).toContain('尚未接入 MCP 服务，新增连接器后发现工具')
  expect(wrapper.findAll('button').some((button) => button.text() === '新增连接器')).toBe(true)
  expect(wrapper.text()).not.toContain('尚未取得连接器列表')
})
