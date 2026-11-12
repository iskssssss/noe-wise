import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { ElButton, ElDrawer, ElSelect, ElInput, ElFormItem } from 'element-plus'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import { HttpClientError } from '@skillforge/shared/utils/http'
import { clearFeedback, confirm } from '@skillforge/shared/feedback'
import AgentsPage from './AgentsPage.vue'

const mocks = vi.hoisted(() => ({
  install: vi.fn(),
  receipt: vi.fn(),
  detail: vi.fn(),
  options: vi.fn(),
  deployments: vi.fn(),
  versions: vi.fn(),
  definitions: vi.fn()
}))
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
      templateDetail: mocks.detail,
      installTemplate: mocks.install,
      installationResult: mocks.receipt
    },
    capabilities: {
      listDefinitions: mocks.definitions,
      deployments: mocks.deployments,
      listRequirementOptions: mocks.options,
      versions: mocks.versions
    },
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
  mocks.detail.mockResolvedValue({ name: '客服模板', definition: {} })
  mocks.definitions.mockResolvedValue([])
  mocks.options.mockResolvedValue([])
  mocks.deployments.mockResolvedValue([])
  mocks.versions.mockResolvedValue([])
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

it('模板工具槽使用发布摘要和部署定义投影，不逐定义读取版本', async () => {
  mocks.detail.mockResolvedValue({
    name: '客服模板',
    definition: { resource_slots: [{ key: 'tool', label: '工具槽', capability_type: 'TOOL' }] }
  })
  mocks.options.mockResolvedValue([
    { id: '10', name: '工具甲', versionId: '12' },
    { id: '20', name: '工具乙', versionId: '22' }
  ])
  mocks.deployments.mockResolvedValue([
    { deploymentKey: 'old-published', toolVersionId: '11', toolDefinitionId: '10' },
    { deploymentKey: 'archived', toolVersionId: '9', toolDefinitionId: null },
    { deploymentKey: 'other', toolVersionId: '22', toolDefinitionId: '20' }
  ])
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/agents', component: AgentsPage }]
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
  const slot = drawer
    .findAllComponents(ElFormItem)
    .find((item) => item.props('label') === '工具槽')!
    .getComponent(ElSelect)
  slot.vm.$emit('update:modelValue', '10')
  slot.vm.$emit('change', '10')
  await flushPromises()
  expect(document.body.textContent).toContain('old-published')
  expect(document.body.textContent).not.toContain('archived')
  expect(mocks.options).toHaveBeenCalledExactlyOnceWith('TOOL')
  expect(mocks.definitions).not.toHaveBeenCalled()
  expect(mocks.versions).not.toHaveBeenCalled()
  await button('安装并配置').trigger('click')
  await flushPromises()
  expect(mocks.install.mock.calls[0]![0].resource_bindings).toEqual([
    {
      slot_key: 'tool',
      mount: {
        capability_type: 'TOOL',
        capability_definition_id: '10',
        tool_config: { tool_deployment_id: 'old-published' }
      }
    }
  ])
})

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

it.each(['安装成功', '回执确认'])('%s后导航被阻止时保留原智能体入口', async (mode) => {
  const requestId = 'request-1'
  if (mode === '回执确认') {
    const key = `skillforge.agent-template-install:${encodeURIComponent(JSON.stringify(['1', 'actor-1']))}`
    sessionStorage.setItem(key, requestId)
    mocks.receipt.mockResolvedValue({ request_id: requestId, agent_id: '123' })
  } else mocks.install.mockResolvedValue({ agent: { id: '123' } })
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/agents', component: AgentsPage },
      { path: '/admin/agents/:id', component: { template: '<p>智能体配置</p>' } }
    ]
  })
  router.beforeEach((to) => (to.path.startsWith('/admin/agents/') ? false : true))
  await router.push('/agents')
  await router.isReady()
  wrapper = mount(RouterView, { attachTo: document.body, global: { plugins: [router] } })
  await flushPromises()
  if (mode === '回执确认') {
    await button('核对原安装结果').trigger('click')
  } else {
    await button('从模板安装').trigger('click')
    await flushPromises()
    await button('安装并配置').trigger('click')
  }
  await flushPromises()
  expect(router.currentRoute.value.path).toBe('/agents')
  expect(wrapper.get('a[href="/admin/agents/123"]').text()).toContain('进入原智能体配置')
  expect(button('从模板安装').props('disabled')).toBe(true)
  expect(button('新建智能体').props('disabled')).toBe(true)
  expect(mocks.install).toHaveBeenCalledTimes(mode === '安装成功' ? 1 : 0)
})
