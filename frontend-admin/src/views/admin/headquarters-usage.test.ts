import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { reactive } from 'vue'
import { createRouter, createMemoryHistory } from 'vue-router'
import HeadquartersUsagePage from './HeadquartersUsagePage.vue'

const fixture = vi.hoisted(() => ({
  user: {
    sub: 'headquarters-user',
    tenant_id: '10',
    authorization_version: 1,
    permissions: [] as string[]
  },
  summary: vi.fn(),
  details: vi.fn(),
  events: vi.fn()
}))
vi.mock('../../api', () => ({ api: { headquartersUsage: fixture } }))
vi.mock('../../stores/auth', () => ({ useAuthStore: () => reactive(fixture) }))
const wrappers: VueWrapper[] = []
afterEach(() => {
  wrappers.forEach((w) => w.unmount())
  wrappers.length = 0
  vi.clearAllMocks()
})

async function open(
  permissions: string[],
  path = '/admin/headquarters-usage',
  props?: { tenantId?: string; embedded?: boolean }
) {
  fixture.user.permissions = permissions
  fixture.summary.mockResolvedValue({
    request_count: 1,
    invocation_count: 1,
    prompt_tokens: null,
    completion_tokens: null,
    unknown_usage_count: 1,
    unresolved_cost_count: 1,
    costs: []
  })
  fixture.details.mockResolvedValue({ items: [], next_cursor: null, has_more: false })
  fixture.events.mockResolvedValue({ items: [], next_cursor: null, has_more: false })
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/admin/headquarters-usage', component: HeadquartersUsagePage }]
  })
  await router.push(path)
  await router.isReady()
  const wrapper = mount(HeadquartersUsagePage, { props, global: { plugins: [router] } })
  wrappers.push(wrapper)
  return wrapper
}

describe('总部用量当前权限消费', () => {
  it('工作台固定目标不受地址中的旧筛选和重置影响', async () => {
    const wrapper = await open(
      ['tenant.sub_institutions.usage_summary.read'],
      '/admin/headquarters-usage?tenant_id=other-tenant',
      { tenantId: '9223372036854775806', embedded: true }
    )
    await flushPromises()
    expect(fixture.summary.mock.lastCall?.[0].tenant_id).toBe('9223372036854775806')
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '重置')!
      .trigger('click')
    await flushPromises()
    expect(fixture.summary.mock.lastCall?.[0].tenant_id).toBe('9223372036854775806')
    expect(
      wrapper
        .findAll('input')
        .some((input) => input.element.closest('.el-form-item')?.textContent?.includes('机构标识'))
    ).toBe(false)
  })
  it('从机构目录带入精确字符串上下文，初次查询与重置保持该目标', async () => {
    const wrapper = await open(
      ['tenant.sub_institutions.usage_summary.read'],
      '/admin/headquarters-usage?tenant_id=9223372036854775806'
    )
    await flushPromises()
    expect(fixture.summary.mock.lastCall?.[0].tenant_id).toBe('9223372036854775806')
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '重置')!
      .trigger('click')
    await flushPromises()
    expect(fixture.summary.mock.lastCall?.[0].tenant_id).toBe('9223372036854775806')
    expect(fixture.details).not.toHaveBeenCalled()
  })

  it.each(['details', 'events'] as const)('仅有%s许可时只发送对应读取', async (view) => {
    await open([`tenant.sub_institutions.usage_${view}.read`])
    await flushPromises()
    expect(fixture.summary).not.toHaveBeenCalled()
    expect(fixture[view]).toHaveBeenCalledTimes(1)
    expect(fixture[view === 'details' ? 'events' : 'details']).not.toHaveBeenCalled()
  })
  it('机构目录权限不能触发任何用量读取', async () => {
    const wrapper = await open(['tenant.sub_institutions.read'])
    await flushPromises()
    expect(fixture.summary).not.toHaveBeenCalled()
    expect(fixture.details).not.toHaveBeenCalled()
    expect(fixture.events).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('当前身份没有此范围的用量读取权限')
  })
  it('汇总许可不触发明细或事件，目标机构按提交筛选', async () => {
    const wrapper = await open(['tenant.sub_institutions.usage_summary.read'])
    await flushPromises()
    expect(fixture.summary).toHaveBeenCalledTimes(1)
    expect(fixture.details).not.toHaveBeenCalled()
    expect(fixture.events).not.toHaveBeenCalled()
    const inputs = wrapper.findAll('input')
    const tenant = inputs.find((item) =>
      item.element.closest('.el-form-item')?.textContent?.includes('机构标识')
    )
    expect(tenant).toBeDefined()
    await tenant!.setValue('9223372036854775806')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(fixture.summary.mock.lastCall?.[0].tenant_id).toBe('9223372036854775806')
  })
  it('授权版本变化丢弃旧明细回包和旧游标', async () => {
    const wrapper = await open([
      'tenant.sub_institutions.usage_summary.read',
      'tenant.sub_institutions.usage_details.read'
    ])
    await flushPromises()
    let complete: (value: unknown) => void = () => {}
    fixture.details.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          complete = resolve
        })
    )
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    reactive(fixture).user.permissions = ['tenant.sub_institutions.usage_summary.read']
    reactive(fixture).user.authorization_version++
    await flushPromises()
    complete({
      items: [{ event_id: 'old-authorized-event' }],
      next_cursor: 'old-cursor',
      has_more: true
    })
    await flushPromises()
    expect(wrapper.text()).not.toContain('old-authorized-event')
    expect(wrapper.findAll('button').find((b) => b.text() === '下一页')).toBeUndefined()
    expect(wrapper.text()).not.toContain('old-cursor')
  })
})
