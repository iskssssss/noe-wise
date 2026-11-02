import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { ElButton, ElDrawer, ElSelect, ElInput, ElFormItem } from 'element-plus'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import { HttpClientError } from '@skillforge/shared/utils/http'
import { clearFeedback, confirm } from '@skillforge/shared/feedback'
import AgentsPage from './AgentsPage.vue'

const mocks = vi.hoisted(() => ({ install: vi.fn() }))
vi.mock('../../stores/auth', () => ({
  useAuthStore: () => ({
    identityVersion: 1,
    user: { tenant_id: '1', sub: 'actor-1', permissions: [] }
  })
}))
vi.mock('../../api', () => ({
  api: {
    agents: {
      list: async () => ({ items: [] }),
      templates: async () => ({ items: [{ id: 'template-1', name: '客服模板', version: '1' }] }),
      templateDetail: async () => ({ name: '客服模板', definition: {} }),
      installTemplate: mocks.install
    },
    capabilities: { listDefinitions: async () => [], deployments: async () => [] },
    workflowDrafts: { instances: async () => ({ items: [] }) }
  }
}))
vi.mock('@skillforge/shared/feedback', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@skillforge/shared/feedback')>()),
  confirm: vi.fn()
}))
let wrapper: VueWrapper | undefined
beforeEach(() => {
  vi.resetAllMocks()
  sessionStorage.clear()
  mocks.install.mockRejectedValue(new HttpClientError({ status: 0, userMessage: '结果尚未确认' }))
  vi.mocked(confirm).mockResolvedValue(false)
})
afterEach(() => {
  wrapper?.unmount()
  clearFeedback()
  document.body.innerHTML = ''
})
function button(label: string) {
  return wrapper!.findAllComponents(ElButton).find((item) => item.text() === label)!
}

it('真实 RouterView 导航对未决安装只确认一次，取消保留原核对，确认后允许离开', async () => {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/agents', component: AgentsPage },
      { path: '/other', component: { template: '<p>目标页面</p>' } }
    ]
  })
  await router.push('/agents')
  await router.isReady()
  wrapper = mount(RouterView, { attachTo: document.body, global: { plugins: [router] } })
  await flushPromises()
  await button('从模板安装').trigger('click')
  await flushPromises()
  const drawer = wrapper
    .findAllComponents(ElDrawer)
    .find((item) => item.props('title') === '从模板安装')!
  const select = drawer.getComponent(ElSelect)
  select.vm.$emit('update:modelValue', 'template-1')
  select.vm.$emit('change', 'template-1')
  await flushPromises()
  await button('安装并配置').trigger('click')
  await flushPromises()
  const original = mocks.install.mock.calls[0]![0]
  await router.push('/other')
  await flushPromises()
  expect(router.currentRoute.value.path).toBe('/agents')
  expect(confirm).toHaveBeenCalledTimes(1)
  expect(document.body.textContent).toContain(original.request_id)
  vi.mocked(confirm).mockResolvedValue(true)
  await router.push('/other')
  await flushPromises()
  expect(router.currentRoute.value.path).toBe('/other')
  expect(wrapper.text()).toContain('目标页面')
  expect(confirm).toHaveBeenCalledTimes(2)
  expect(mocks.install).toHaveBeenCalledTimes(1)
})

it('模板安装草稿阻止意外离开，取消后保留输入并仅确认一次', async () => {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/agents', component: AgentsPage },
      { path: '/other', component: { template: '<p>目标页面</p>' } }
    ]
  })
  await router.push('/agents')
  await router.isReady()
  wrapper = mount(RouterView, { attachTo: document.body, global: { plugins: [router] } })
  await flushPromises()
  await button('从模板安装').trigger('click')
  await flushPromises()
  const drawer = wrapper
    .findAllComponents(ElDrawer)
    .find((item) => item.props('title') === '从模板安装')!
  const select = drawer.getComponent(ElSelect)
  select.vm.$emit('update:modelValue', 'template-1')
  select.vm.$emit('change', 'template-1')
  await flushPromises()
  const name = drawer
    .findAllComponents(ElFormItem)
    .find((item) => item.props('label') === '智能体名称')!
    .getComponent(ElInput)
  await name.get('input').setValue('自定义客服')
  await router.push('/other')
  await flushPromises()
  expect(router.currentRoute.value.path).toBe('/agents')
  expect(confirm).toHaveBeenCalledTimes(1)
  expect(name.props('modelValue')).toBe('自定义客服')
  expect(mocks.install).not.toHaveBeenCalled()
  vi.mocked(confirm).mockResolvedValueOnce(true)
  await router.push('/other')
  await flushPromises()
  expect(router.currentRoute.value.path).toBe('/other')
  expect(confirm).toHaveBeenCalledTimes(2)
})

it('模板安装成功后直接进入新智能体，不触发草稿离开确认', async () => {
  mocks.install.mockResolvedValueOnce({ agent: { id: 'installed-agent' } })
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/agents', component: AgentsPage },
      { path: '/admin/agents/:id', component: { template: '<p>智能体配置</p>' } }
    ]
  })
  await router.push('/agents')
  await router.isReady()
  wrapper = mount(RouterView, { attachTo: document.body, global: { plugins: [router] } })
  await flushPromises()
  await button('从模板安装').trigger('click')
  await flushPromises()
  const drawer = wrapper
    .findAllComponents(ElDrawer)
    .find((item) => item.props('title') === '从模板安装')!
  const select = drawer.getComponent(ElSelect)
  select.vm.$emit('update:modelValue', 'template-1')
  select.vm.$emit('change', 'template-1')
  await flushPromises()
  await button('安装并配置').trigger('click')
  await flushPromises()
  expect(router.currentRoute.value.path).toBe('/admin/agents/installed-agent')
  expect(confirm).not.toHaveBeenCalled()
  expect(mocks.install).toHaveBeenCalledTimes(1)
})
