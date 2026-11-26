import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import { ElSelect } from 'element-plus'
import { clearFeedback } from '@skillforge/shared/feedback'
import ChannelsPage from './ChannelsPage.vue'
import Layout from './Layout.vue'
import applicationRouter from '../../router'

const mocks = vi.hoisted(() => ({ list: vi.fn() }))
vi.mock('../../api', () => ({
  api: { channels: { list: mocks.list } },
  foundationRequest: vi.fn()
}))
vi.mock('../../stores/auth', () => ({
  useAuthStore: () => ({
    isAuthenticated: true,
    user: { tenant_id: 'tenant', sub: 'member', permissions: ['foundation.channels.read'] }
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
      { id: '1', name: '测试渠道', type: 'openapi', status: 'active' },
      { id: '2', name: '测试组件', type: 'web_widget', status: 'active' },
      { id: '3', name: '其他接入', type: 'openapi', status: 'active' }
    ],
    total: 3
  })
})
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  clearFeedback()
  document.body.innerHTML = ''
})

async function render(path = '/admin/channels') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      {
        path: '/admin',
        component: Layout,
        children: [
          {
            path: 'channels',
            component: ChannelsPage,
            meta: applicationRouter.getRoutes().find((route) => route.path === '/admin/channels')!
              .meta
          },
          { path: 'api-docs', component: { template: '<p>开放 API</p>' } }
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

it('组合筛选后离开再返回及重新打开地址，恢复名称、类型和对应渠道', async () => {
  const { router, wrapper } = await render()
  await wrapper.get('input[aria-label="搜索渠道名称"]').setValue('测试')
  await flushPromises()
  wrapper.findComponent(ElSelect).vm.$emit('update:modelValue', 'openapi')
  await flushPromises()
  const location = router.currentRoute.value.fullPath
  expect(wrapper.findAll('tbody tr').map((row) => row.text())).toEqual([
    expect.stringContaining('测试渠道')
  ])
  await router.push('/admin/api-docs')
  router.back()
  await flushPromises()
  expect((wrapper.get('input[aria-label="搜索渠道名称"]').element as HTMLInputElement).value).toBe(
    '测试'
  )
  expect(wrapper.findComponent(ElSelect).props('modelValue')).toBe('openapi')
  expect(wrapper.findAll('tbody tr')).toHaveLength(1)
  const refreshed = await render(location)
  expect(refreshed.wrapper.findAll('tbody tr').map((row) => row.text())).toEqual([
    expect.stringContaining('测试渠道')
  ])
})

it('无结果可清空两项条件，保留其它查询参数且不增加返回步骤', async () => {
  const { router, wrapper } = await render('/admin/channels?source=dashboard&type=openapi')
  const search = wrapper.get('input[aria-label="搜索渠道名称"]')
  ;(search.element as HTMLInputElement).focus()
  await search.setValue('未')
  await search.setValue('未匹配')
  await flushPromises()
  expect(wrapper.text()).toContain('没有匹配渠道，清空名称或类型后查看全部')
  expect(router.currentRoute.value.query.q).toBe('未匹配')
  expect(document.activeElement).toBe(search.element)
  await search.setValue('')
  await flushPromises()
  wrapper.findComponent(ElSelect).vm.$emit('update:modelValue', '')
  await flushPromises()
  expect(router.currentRoute.value.query).toEqual({ source: 'dashboard' })
  expect(wrapper.findAll('tbody tr')).toHaveLength(3)
  expect(mocks.list).toHaveBeenCalledTimes(1)
  await router.push('/admin/api-docs')
  router.back()
  await flushPromises()
  expect(router.currentRoute.value.fullPath).toBe('/admin/channels?source=dashboard')
})

it('首次读取和失败不显示空结果，重试成功后刷新失败保留渠道与筛选', async () => {
  let rejectRead!: (reason: Error) => void
  mocks.list.mockReturnValueOnce(
    new Promise((_, reject) => {
      rejectRead = reject
    })
  )
  const { wrapper } = await render('/admin/channels?q=测试&type=openapi')
  expect(wrapper.text()).toContain('正在读取渠道…')
  expect(wrapper.text()).not.toContain('0 个渠道')
  expect(wrapper.text()).not.toContain('没有匹配渠道')
  rejectRead(new Error('渠道暂时无法读取'))
  await flushPromises()
  expect(wrapper.text()).toContain('尚未取得渠道列表，请点击刷新重新读取')
  expect(wrapper.text()).not.toContain('0 个渠道')
  const refresh = wrapper.findAll('button').find((button) => button.text() === '刷新')!
  await refresh.trigger('click')
  await flushPromises()
  expect(wrapper.findAll('tbody tr').map((row) => row.text())).toEqual([
    expect.stringContaining('测试渠道')
  ])
  mocks.list.mockRejectedValueOnce(new Error('刷新暂时不可用'))
  await refresh.trigger('click')
  await flushPromises()
  expect(wrapper.findAll('tbody tr').map((row) => row.text())).toEqual([
    expect.stringContaining('测试渠道')
  ])
  expect(wrapper.text()).toContain('1 个渠道')
  expect((wrapper.get('input[aria-label="搜索渠道名称"]').element as HTMLInputElement).value).toBe(
    '测试'
  )
  expect(wrapper.findComponent(ElSelect).props('modelValue')).toBe('openapi')
})
