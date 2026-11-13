import { afterEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { ElButton, ElTable } from 'element-plus'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import { clearFeedback, confirm } from '@skillforge/shared/feedback'
import { HttpClientError } from '@skillforge/shared/utils/http'
import ToolOnboarding from '../../components/ToolOnboarding.vue'
import ToolsPage from './ToolsPage.vue'

const calls = vi.hoisted(() => ({ onboard: vi.fn(), onboarding: vi.fn() }))
vi.mock('../../api', () => ({
  api: {
    connectors: {
      instances: async () => ({
        items: [{ instance_id: 'server', name: '服务', executor_type: 'mcp' }]
      }),
      mcpTools: async () => ({ items: [{ name: 'read_alpha' }] })
    },
    capabilities: {
      platformTools: async () => [],
      definitions: async () => [],
      getVersion: vi.fn(),
      createDeploymentWithReceipt: vi.fn(),
      deploymentCreateReceipt: vi.fn()
    }
  }
}))
vi.mock('../../api/tools', () => ({
  toolsApi: {
    available: async () => ({ items: [] }),
    onboard: calls.onboard,
    onboarding: calls.onboarding
  }
}))
vi.mock('../../stores/auth', () => ({
  useAuthStore: () => ({
    user: {
      tenant_id: 'tenant-1',
      sub: 'member-1',
      permissions: [
        'foundation.connectors.read',
        'foundation.connectors.update',
        'foundation.capabilities.read',
        'foundation.capabilities.create',
        'foundation.capabilities.publish'
      ]
    }
  })
}))
vi.mock('@skillforge/shared/feedback', async (original) => ({
  ...(await original<typeof import('@skillforge/shared/feedback')>()),
  confirm: vi.fn()
}))
let wrapper: VueWrapper | undefined
afterEach(() => {
  wrapper?.unmount()
  clearFeedback()
  sessionStorage.clear()
  vi.resetAllMocks()
  document.body.innerHTML = ''
})

it('真实路由取消离开保留未知选择，当前可用也不能确认原请求', async () => {
  sessionStorage.clear()
  calls.onboard.mockRejectedValue(new HttpClientError({ status: 408, userMessage: '超时' }))
  calls.onboarding.mockResolvedValue({ items: [{ tool_name: 'read_alpha', status: 'AVAILABLE' }] })
  vi.mocked(confirm).mockResolvedValue(false)
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/tools', component: ToolsPage },
      { path: '/other', component: { template: '<p>其他页面</p>' } }
    ]
  })
  await router.push('/tools?connectorId=server')
  await router.isReady()
  wrapper = mount(RouterView, { attachTo: document.body, global: { plugins: [router] } })
  await flushPromises()
  const button = (label: string) =>
    wrapper!.findAllComponents(ElButton).find((item) => item.text() === label)!
  await button('读取工具').trigger('click')
  await flushPromises()
  wrapper
    .getComponent(ToolOnboarding)
    .getComponent(ElTable)
    .vm.$emit('selection-change', [{ name: 'read_alpha' }])
  await flushPromises()
  await button('确认接入并授权调用 1 项工具').trigger('click')
  await flushPromises()
  await button('核对接入结果').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('接入结果尚未确认')
  const unload = new Event('beforeunload', { cancelable: true })
  window.dispatchEvent(unload)
  expect(unload.defaultPrevented).toBe(true)
  await router.push('/other')
  expect(router.currentRoute.value.path).toBe('/tools')
  expect(confirm).toHaveBeenCalledWith(
    expect.objectContaining({ content: expect.stringContaining('read_alpha') })
  )
  expect(wrapper.text()).toContain('接入结果尚未确认')
  vi.mocked(confirm).mockResolvedValue(true)
  await router.push('/other')
  expect(router.currentRoute.value.path).toBe('/other')
  expect(calls.onboard).toHaveBeenCalledTimes(1)
})
