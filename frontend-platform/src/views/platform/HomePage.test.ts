import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, RouterLinkStub, type VueWrapper } from '@vue/test-utils'
import { reactive } from 'vue'
import { ElCard, ElStatistic } from 'element-plus'
import { routePermission } from '@skillforge/shared/foundation/navigation'
import HomePage from './HomePage.vue'

const mocks = vi.hoisted(() => ({
  auth: undefined as unknown as {
    identityVersion: number
    user: { sub: string; authorization_version: number; permissions: string[] }
  },
  tenants: vi.fn(),
  reviews: vi.fn(),
  models: vi.fn()
}))
vi.mock('../../stores/auth', () => ({ useAuthStore: () => mocks.auth }))
vi.mock('../../api', () => ({
  api: {
    admin: { listTenants: mocks.tenants, models: { list: mocks.models } },
    marketplace: { reviews: mocks.reviews }
  }
}))
let wrapper: VueWrapper
beforeEach(() => {
  vi.resetAllMocks()
  mocks.auth = reactive({
    identityVersion: 1,
    user: { sub: 'actor-a', authorization_version: 1, permissions: [] }
  })
  mocks.tenants.mockResolvedValue({ total: 0 })
  mocks.reviews.mockResolvedValue({ total: 0 })
  mocks.models.mockResolvedValue([{ id: 'model-a' }])
})
afterEach(() => {
  wrapper?.unmount()
  document.body.innerHTML = ''
})
async function open() {
  wrapper = mount(HomePage, {
    attachTo: document.body,
    global: { stubs: { RouterLink: RouterLinkStub } }
  })
  await flushPromises()
}

describe('平台首页当前领域授权', () => {
  it('只有jobs权限没有领域统计请求、卡片或运营入口', async () => {
    mocks.auth.user.permissions = ['foundation.jobs.read']
    await open()
    expect(mocks.tenants).not.toHaveBeenCalled()
    expect(mocks.reviews).not.toHaveBeenCalled()
    expect(mocks.models).not.toHaveBeenCalled()
    expect(wrapper.findAllComponents(ElCard)).toHaveLength(0)
    expect(wrapper.findAllComponents(ElStatistic)).toHaveLength(0)
    expect(wrapper.findAllComponents(RouterLinkStub)).toHaveLength(0)
    expect(wrapper.text()).toContain('请从左侧菜单进入已授权功能')
    expect(wrapper.text()).not.toContain('统计加载失败')
  })
  it('只读模型并仅显示符合routePermission的模型与账务入口', async () => {
    mocks.auth.user.permissions = ['foundation.models.read', 'foundation.billing.read']
    await open()
    expect(mocks.models).toHaveBeenCalledTimes(1)
    expect(mocks.tenants).not.toHaveBeenCalled()
    expect(mocks.reviews).not.toHaveBeenCalled()
    expect(wrapper.findAllComponents(ElStatistic).map((item) => item.props('value'))).toEqual([1])
    const paths = wrapper.findAllComponents(RouterLinkStub).map((item) => String(item.props('to')))
    expect(paths).toEqual(['/platform/models', '/platform/billing'])
    expect(
      paths.every((path) => mocks.auth.user.permissions.includes(routePermission(path)!))
    ).toBe(true)
    expect(wrapper.text()).not.toContain('全部机构')
    expect(wrapper.text()).not.toContain('待审核工作流')
    expect(wrapper.text()).not.toContain('系统智能体')
    expect(wrapper.text()).not.toContain('会话观察')
  })
  it('身份变化时旧统计响应不能覆盖新结果，撤权后立即清空', async () => {
    mocks.auth.user.permissions = ['foundation.models.read']
    let finishOld!: (value: Array<{ id: string }>) => void
    mocks.models
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            finishOld = resolve
          })
      )
      .mockResolvedValueOnce([{ id: 'model-b' }, { id: 'model-c' }])
    await open()
    expect(wrapper.find('.el-statistic__number').text()).toBe('—')
    mocks.auth.user = {
      sub: 'actor-b',
      authorization_version: 2,
      permissions: ['foundation.models.read']
    }
    await flushPromises()
    expect(wrapper.findAllComponents(ElStatistic).map((item) => item.props('value'))).toEqual([2])
    finishOld(Array.from({ length: 88 }, (_, id) => ({ id: String(id) })))
    await flushPromises()
    expect(wrapper.findAllComponents(ElStatistic).map((item) => item.props('value'))).toEqual([2])
    mocks.auth.user.permissions = ['foundation.jobs.read']
    await flushPromises()
    expect(mocks.models).toHaveBeenCalledTimes(2)
    expect(wrapper.findAllComponents(ElStatistic)).toHaveLength(0)
    expect(wrapper.text()).not.toContain('最近刷新')
  })
})
