import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { clearFeedback } from '@skillforge/shared/feedback'
import { HttpClientError } from '@skillforge/shared/utils/http'
import { routePermission } from '@skillforge/shared/foundation/navigation'
import SystemAgentToolsPage from './SystemAgentToolsPage.vue'
import HomePage from './HomePage.vue'

const mocks = vi.hoisted(() => ({
  list: vi.fn(),
  options: vi.fn(),
  tenants: vi.fn(),
  reviews: vi.fn(),
  models: vi.fn(),
  permissions: [] as string[]
}))
vi.mock('../../api', () => ({
  api: {
    systemAgentTools: { list: mocks.list },
    systemAgents: { mountOptions: mocks.options },
    admin: { listTenants: mocks.tenants, models: { list: mocks.models } },
    marketplace: { reviews: mocks.reviews }
  }
}))
vi.mock('../../stores/auth', () => ({
  useAuthStore: () => ({ identityVersion: 1, user: { permissions: mocks.permissions } })
}))
vi.mock('../../composables/actionPermissions', () => ({
  useActionPermissions: () => ({
    allowed: (code: string) => mocks.permissions.includes(code),
    checked: (_code: string, work: () => unknown) => work()
  })
}))
vi.mock('vue-router', () => ({
  onBeforeRouteLeave: vi.fn(),
  onBeforeRouteUpdate: vi.fn(),
  RouterLink: { template: '<a><slot /></a>' }
}))
let wrapper: VueWrapper | undefined
beforeEach(() => {
  vi.resetAllMocks()
  mocks.permissions = ['foundation.system_agent_tools.read']
  mocks.list.mockResolvedValue([
    {
      id: '1',
      name: '已有工具部署',
      tool_definition_id: 'tool-1',
      provider_ref: 'order_lookup',
      environment: 'TEST',
      active: true
    }
  ])
  mocks.options.mockResolvedValue({ skills: [], tools: [], deployments: [] })
})
afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  clearFeedback()
  document.body.innerHTML = ''
})

it('只读工具角色可查看所属列表，不请求系统智能体目录', async () => {
  wrapper = mount(SystemAgentToolsPage, {
    attachTo: document.body,
    global: { directives: { permission: () => {} } }
  })
  await flushPromises()
  expect(wrapper.text()).toContain('已有工具部署')
  await wrapper.get('input[aria-label="搜索工具部署"]').setValue('ORDER_LOOKUP')
  expect(wrapper.text()).toContain('已有工具部署')
  expect(mocks.list).toHaveBeenCalledWith({ silent: true })
  expect(mocks.options).not.toHaveBeenCalled()
})

it('按工具名或提供方引用查找部署，保留现有目录权限', async () => {
  mocks.permissions.push('foundation.system_agents.read')
  mocks.options.mockResolvedValue({
    skills: [],
    tools: [{ id: 'tool-1', name: '查询订单' }],
    deployments: []
  })
  wrapper = mount(SystemAgentToolsPage, {
    attachTo: document.body,
    global: { directives: { permission: () => {} } }
  })
  await flushPromises()
  const search = wrapper.get('input[aria-label="搜索工具部署"]')
  await search.setValue(' 查询订单 ')
  expect(wrapper.text()).toContain('已有工具部署')
  await search.setValue('ORDER_LOOKUP')
  expect(wrapper.text()).toContain('已有工具部署')
  await search.setValue('不存在')
  expect(wrapper.text()).not.toContain('已有工具部署')
  expect(mocks.options).toHaveBeenCalledTimes(1)
})

it('列表与辅助目录同时失败时认领全部错误，仅保留一条批次反馈', async () => {
  mocks.permissions.push('foundation.system_agents.read')
  const errors = [0, 1].map(() => new HttpClientError({ status: 503, userMessage: '服务暂不可用' }))
  mocks.list.mockRejectedValueOnce(errors[0])
  mocks.options.mockRejectedValueOnce(errors[1])
  wrapper = mount(SystemAgentToolsPage, {
    attachTo: document.body,
    global: { directives: { permission: () => {} } }
  })
  await flushPromises()
  expect(errors.every((reason) => reason.hasFeedbackOwner)).toBe(true)
  expect(document.querySelectorAll('.el-message--error')).toHaveLength(1)
  expect(mocks.options).toHaveBeenCalledWith({ silent: true })
})

it('首页并行统计失败由页面聚合成一条Message，保留成功读取的统计', async () => {
  mocks.permissions = ['/platform/tenants', '/platform/review', '/platform/models'].map((path) =>
    routePermission(path)!
  )
  const errors = [0, 1].map(() => new HttpClientError({ status: 503, userMessage: '服务暂不可用' }))
  mocks.tenants.mockRejectedValueOnce(errors[0])
  mocks.reviews.mockRejectedValueOnce(errors[1])
  mocks.models.mockResolvedValueOnce([{ id: 'model-1' }])
  wrapper = mount(HomePage, { attachTo: document.body })
  await flushPromises()
  expect(errors.every((reason) => reason.hasFeedbackOwner)).toBe(true)
  expect(document.querySelectorAll('.el-message--warning')).toHaveLength(1)
  expect(document.querySelector('.el-message--warning')?.textContent).toContain('机构统计加载失败')
  expect(document.querySelector('.el-message--warning')?.textContent).toContain('审核统计加载失败')
  expect(mocks.tenants).toHaveBeenCalledWith({ limit: 1 }, { silent: true })
  expect(mocks.reviews).toHaveBeenCalledWith({ limit: 1, status: 'pending' }, { silent: true })
  expect(mocks.models).toHaveBeenCalledWith({ silent: true })
  expect(wrapper.find('.el-statistic__number')?.text()).not.toBe('')
})
