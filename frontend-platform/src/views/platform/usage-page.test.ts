import { reactive } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import EntitySelect from '@skillforge/shared/admin/EntitySelect.vue'
import UsagePanel from '@skillforge/shared/integration/UsagePanel.vue'
import UsagePage from './UsagePage.vue'

const fixture = vi.hoisted(() => ({
  auth: undefined as unknown as {
    user: { sub: string; authorization_version: number; permissions: string[] }
  },
  options: vi.fn(),
  summary: vi.fn(),
  events: vi.fn(),
  details: vi.fn(),
  directory: vi.fn()
}))
vi.mock('../../stores/auth', () => ({ useAuthStore: () => fixture.auth }))
vi.mock('../../api', () => ({
  api: {
    usage: {
      filterOptions: fixture.options,
      summary: fixture.summary,
      events: fixture.events,
      details: fixture.details
    },
    admin: { listTenants: fixture.directory, getTenant: fixture.directory }
  }
}))
const wrappers: VueWrapper[] = []
beforeEach(() => {
  vi.clearAllMocks()
  sessionStorage.clear()
  fixture.auth = reactive({
    user: { sub: 'usage-only', authorization_version: 1, permissions: [] }
  })
  fixture.options.mockResolvedValue([
    { id: '900000000000000001', label: '平台智能体调试空间', description: '历史用量' }
  ])
  fixture.summary.mockResolvedValue({ request_count: 0, invocation_count: 2, costs: [] })
  fixture.events.mockResolvedValue({ items: [], has_more: false, next_cursor: null })
  fixture.details.mockResolvedValue({ items: [], has_more: false, next_cursor: null })
})
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  document.body.innerHTML = ''
  sessionStorage.clear()
})

describe('平台页面的历史机构候选装配', () => {
  it.each(['summary', 'details', 'events'] as const)(
    '只有%s用量读取权限时可选择历史机构并查询，不请求机构管理目录',
    async (view) => {
      fixture.auth.user.permissions = [`platform.usage.${view}.read`]
      const wrapper = mount(UsagePage)
      wrappers.push(wrapper)
      await flushPromises()
      const select = wrapper.getComponent(EntitySelect)
      const options = await select.props('load')?.('平台')
      expect(options?.[0]?.label).toBe('平台智能体调试空间')
      expect(fixture.options.mock.lastCall?.[0]).toMatchObject({
        field: 'tenant_id',
        view,
        q: '平台'
      })
      await select.setValue('900000000000000001')
      await wrapper.get('form').trigger('submit')
      await flushPromises()
      expect(fixture[view].mock.lastCall?.[0].tenant_id).toBe('900000000000000001')
      expect(wrapper.getComponent(UsagePanel).props('tenantLabel')?.('900000000000000001')).toBe(
        '平台智能体调试空间'
      )
      expect(fixture.directory).not.toHaveBeenCalled()
    }
  )

  it('账号授权变化后清除旧名称，旧候选迟到不写入新身份', async () => {
    fixture.auth.user.permissions = ['platform.usage.summary.read']
    const wrapper = mount(UsagePage)
    wrappers.push(wrapper)
    await flushPromises()
    let finish!: (value: { id: string; label: string }[]) => void
    fixture.options.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve
        })
    )
    const pending = wrapper.getComponent(EntitySelect).props('load')?.('旧机构')
    fixture.auth.user.authorization_version = 2
    fixture.auth.user.permissions = []
    await flushPromises()
    finish([{ id: 'old', label: '旧权限名称' }])
    await pending
    await flushPromises()
    expect(wrapper.getComponent(UsagePanel).props('tenantLabel')?.('old')).toBe('old')
    expect(wrapper.getComponent(EntitySelect).props('disabled')).toBe(true)
    expect(fixture.directory).not.toHaveBeenCalled()
  })
})
