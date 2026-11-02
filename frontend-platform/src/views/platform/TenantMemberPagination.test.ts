import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { computed, reactive } from 'vue'
import { ElPagination } from 'element-plus'
import { createPermissionDirective } from '@skillforge/shared/directives/permission'
import TenantSettingsPanel from './TenantSettingsPanel.vue'

const mocks = vi.hoisted(() => ({
  auth: undefined as unknown as {
    identityVersion: number
    user: { sub: string; permissions: string[] }
  },
  members: vi.fn(),
  tenant: vi.fn(),
  updateTenant: vi.fn(),
  updateMember: vi.fn()
}))
vi.mock('../../stores/auth', () => ({ useAuthStore: () => mocks.auth }))
vi.mock('../../api', () => ({
  api: {
    admin: {
      getTenant: mocks.tenant,
      listMembers: mocks.members,
      plans: async () => [{ plan_code: 'free', display_name: '免费套餐' }],
      updateTenant: mocks.updateTenant,
      updateMember: mocks.updateMember
    }
  }
}))
const result = (name: string) => ({
  items: [
    {
      id: name,
      username: name,
      roles: ['developer'],
      email: 'member@example.test',
      status: 'active'
    }
  ],
  total: 61
})
let wrapper: VueWrapper
beforeEach(() => {
  vi.resetAllMocks()
  mocks.auth = reactive({
    identityVersion: 1,
    user: { sub: 'platform-actor', permissions: ['foundation.tenants.update'] }
  })
  mocks.tenant.mockImplementation(async (id: string) => ({
    id,
    name: id,
    slug: id,
    plan_code: 'free',
    status: 'active',
    deployment_mode: 'cloud'
  }))
  mocks.members.mockImplementation(async (id: string, query: { page: number }) =>
    result(`${id}-page-${query.page}`)
  )
})
afterEach(() => {
  wrapper?.unmount()
  document.body.innerHTML = ''
})
async function open() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/platform/tenants/:id', component: TenantSettingsPanel }]
  })
  await router.push('/platform/tenants/tenant-1')
  await router.isReady()
  wrapper = mount(TenantSettingsPanel, {
    props: { tenantId: 'tenant-1', embedded: true },
    attachTo: document.body,
    global: {
      plugins: [router],
      directives: {
        permission: createPermissionDirective(() => computed(() => mocks.auth.user.permissions))
      }
    }
  })
  await flushPromises()
}
function pagination() {
  return wrapper.getComponent(ElPagination)
}
async function page(value: number) {
  pagination().vm.$emit('current-change', value)
  await flushPromises()
}

describe('机构成员分页读取失败保留真实页面', () => {
  it('下一页GET失败时保留原页码和成员，仍可显式重试同一目标页', async () => {
    await open()
    mocks.members.mockRejectedValueOnce(new Error('成员读取失败'))
    await page(2)
    expect(pagination().props('currentPage')).toBe(1)
    expect(wrapper.text()).toContain('tenant-1-page-1')
    await page(2)
    expect(pagination().props('currentPage')).toBe(2)
    expect(wrapper.text()).toContain('tenant-1-page-2')
    expect(mocks.members).toHaveBeenCalledTimes(3)
    expect(mocks.tenant).toHaveBeenCalledTimes(1)
    expect(mocks.updateTenant).not.toHaveBeenCalled()
    expect(mocks.updateMember).not.toHaveBeenCalled()
  })
  it('已有第二页时第三页失败保留第二页，不回到首页', async () => {
    await open()
    await page(2)
    mocks.members.mockRejectedValueOnce(new Error('第三页读取失败'))
    await page(3)
    expect(pagination().props('currentPage')).toBe(2)
    expect(wrapper.text()).toContain('tenant-1-page-2')
    expect(mocks.members).toHaveBeenLastCalledWith(
      'tenant-1',
      { page: 3, limit: 20 },
      expect.anything()
    )
  })
  it('成员读取期间禁用分页，重复翻页事件不会再发送查询', async () => {
    await open()
    let complete!: (value: ReturnType<typeof result>) => void
    mocks.members.mockReturnValueOnce(
      new Promise((resolve) => {
        complete = resolve
      })
    )
    await page(2)
    expect(pagination().props('disabled')).toBe(true)
    await page(3)
    expect(mocks.members).toHaveBeenCalledTimes(2)
    complete(result('tenant-1-page-2'))
    await flushPromises()
    expect(pagination().props('currentPage')).toBe(2)
    expect(pagination().props('disabled')).toBe(false)
  })
  it('切换机构后原翻页失败不会恢复旧机构页码或覆盖新机构成员', async () => {
    await open()
    await page(2)
    let fail!: (reason: Error) => void
    mocks.members.mockReturnValueOnce(
      new Promise((_, reject) => {
        fail = reject
      })
    )
    await page(3)
    await wrapper.setProps({ tenantId: 'tenant-2' })
    await flushPromises()
    expect(pagination().props('currentPage')).toBe(1)
    expect(wrapper.text()).toContain('tenant-2-page-1')
    fail(new Error('原机构第三页失败'))
    await flushPromises()
    expect(pagination().props('currentPage')).toBe(1)
    expect(wrapper.text()).toContain('tenant-2-page-1')
    expect(wrapper.text()).not.toContain('tenant-1-page-2')
  })
})
