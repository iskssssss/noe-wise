import { reactive } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import GovernancePage from '../views/admin/GovernancePage.vue'

const fixture = vi.hoisted(() => ({
  auth: {
    identityVersion: 1,
    user: { tenant_id: '10', sub: 'member', authorization_version: 1, permissions: [] as string[] }
  },
  list: vi.fn(),
  rotate: vi.fn(),
  confirm: vi.fn(),
  webhooks: vi.fn(),
  audit: vi.fn()
}))
vi.mock('../stores/auth', () => ({ useAuthStore: () => reactive(fixture.auth) }))
vi.mock('../api', () => ({
  api: {
    governance: {
      servicePrincipals: { list: fixture.list, rotate: fixture.rotate },
      webhooks: { list: fixture.webhooks },
      auditLogs: fixture.audit
    }
  }
}))
vi.mock('@skillforge/shared/feedback', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@skillforge/shared/feedback')>()),
  message: { success: vi.fn(), warning: vi.fn() },
  confirm: fixture.confirm
}))
const wrappers: VueWrapper[] = []
afterEach(() => wrappers.splice(0).forEach((wrapper) => wrapper.unmount()))
beforeEach(() => {
  vi.clearAllMocks()
  fixture.auth.identityVersion++
  fixture.auth.user.permissions = ['foundation.service_principals.read']
  fixture.list.mockResolvedValue([])
  fixture.confirm.mockResolvedValue(true)
})
async function open(tenantId = '10') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/admin/institutions/:tenantId', component: GovernancePage }]
  })
  await router.push('/admin/institutions/' + tenantId)
  await router.isReady()
  const wrapper = mount(GovernancePage, {
    props: { tenantId, embedded: true, initialTab: 'audit' },
    global: { plugins: [router] }
  })
  wrappers.push(wrapper)
  return wrapper
}
describe('工作台内嵌凭证维护', () => {
  it('轮换结果未知后只允许查询事实，不能再次派发轮换', async () => {
    fixture.auth.user.permissions = [
      'foundation.service_principals.read',
      'foundation.service_principals.update'
    ]
    fixture.list.mockResolvedValue([
      {
        id: 11,
        principal_id: 'service-a',
        integration_id: 'integration-a',
        scopes: [],
        status: 'active',
        credential_version: 1
      }
    ])
    fixture.rotate.mockRejectedValue({ status: 0, message: 'connection_closed' })
    const wrapper = await open()
    await flushPromises()
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '轮换')!
      .trigger('click')
    await flushPromises()
    expect(fixture.rotate).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('本次凭证操作结果未知')
    expect(
      wrapper
        .findAll('button')
        .find((button) => button.text() === '轮换')!
        .attributes('disabled')
    ).toBeDefined()
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '刷新')!
      .trigger('click')
    await flushPromises()
    expect(fixture.rotate).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('本次凭证操作结果未知')
  })
  it('只读本机构服务身份，不把工作台权限扩展到审计和Webhook', async () => {
    const wrapper = await open()
    await flushPromises()
    expect(fixture.list).toHaveBeenCalledTimes(1)
    expect(fixture.audit).not.toHaveBeenCalled()
    expect(fixture.webhooks).not.toHaveBeenCalled()
    expect(wrapper.findAll('button').some((button) => button.text() === '创建服务身份')).toBe(false)
    expect(wrapper.text()).toContain('接入与执行凭证')
  })
  it('传入下级机构时在读取之前明确拒绝，不使用本机构身份冒充目标', async () => {
    const wrapper = await open('11')
    await flushPromises()
    expect(wrapper.find('.el-alert--error').exists()).toBe(false)
    expect(fixture.list).not.toHaveBeenCalled()
    expect(
      Array.from(document.body.querySelectorAll('.el-message__content')).at(-1)?.textContent
    ).toContain('执行凭证只能由所属机构维护')
  })
  it('授权变化后迟到凭证目录不能显示旧身份数据', async () => {
    let complete!: (value: unknown[]) => void
    fixture.list.mockReturnValue(
      new Promise((resolve) => {
        complete = resolve
      })
    )
    const wrapper = await open()
    await flushPromises()
    reactive(fixture.auth).user.authorization_version++
    complete([
      {
        id: 1,
        principal_id: 'old-principal',
        integration_id: 'private-old-integration',
        scopes: [],
        status: 'active'
      }
    ])
    await flushPromises()
    expect(wrapper.text()).not.toContain('private-old-integration')
    expect(
      Array.from(document.body.querySelectorAll('.el-message__content')).at(-1)?.textContent
    ).toContain('账号或授权已变化')
  })
})
