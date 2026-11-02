import { reactive } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import GovernancePage from '../views/admin/GovernancePage.vue'
import { HttpClientError } from '@skillforge/shared/utils/http'

const fixture = vi.hoisted(() => ({
  auth: {
    identityVersion: 1,
    user: { tenant_id: '10', sub: 'member', authorization_version: 1, permissions: [] as string[] }
  },
  list: vi.fn(),
  rotate: vi.fn(),
  rotateWithReceipt: vi.fn(),
  rotationResult: vi.fn(),
  confirm: vi.fn(),
  webhooks: vi.fn(),
  audit: vi.fn()
}))
vi.mock('../stores/auth', () => ({ useAuthStore: () => reactive(fixture.auth) }))
vi.mock('../api', () => ({
  api: {
    governance: {
      servicePrincipals: {
        list: fixture.list,
        rotate: fixture.rotate,
        rotateWithReceipt: fixture.rotateWithReceipt,
        rotationResult: fixture.rotationResult
      },
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
  sessionStorage.clear()
  fixture.rotationResult.mockResolvedValue(undefined)
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
    fixture.rotateWithReceipt.mockRejectedValue({ status: 0, message: 'connection_closed' })
    const wrapper = await open()
    await flushPromises()
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '轮换')!
      .trigger('click')
    await flushPromises()
    expect(fixture.rotateWithReceipt).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('本次凭证轮换结果未知')
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
    expect(fixture.rotateWithReceipt).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('本次凭证轮换结果未知')
  })
  it('轮换断连后重建页面只读核对原请求，匹配回执后才能再次轮换', async () => {
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
    fixture.rotateWithReceipt.mockRejectedValueOnce({ status: 503 })
    let wrapper = await open()
    await flushPromises()
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '轮换')!
      .trigger('click')
    await flushPromises()
    const request = fixture.rotateWithReceipt.mock.calls[0]![0]
    const stored = sessionStorage.getItem('skillforge.principal-rotation:["10","member"]')!
    expect(stored).toContain(request)
    wrapper.unmount()
    fixture.rotationResult.mockResolvedValue({ request_id: request, principal_id: '11' })
    wrapper = await open()
    await flushPromises()
    expect(fixture.rotationResult).toHaveBeenCalledWith(request)
    expect(fixture.rotateWithReceipt).toHaveBeenCalledTimes(1)
    expect(sessionStorage.getItem('skillforge.principal-rotation:["10","member"]')).toBeNull()
    expect(
      wrapper
        .findAll('button')
        .find((button) => button.text() === '轮换')!
        .attributes('disabled')
    ).toBeUndefined()
  })
  it('轮换成功只显示本次明文，浏览器记录不含凭据', async () => {
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
    fixture.rotateWithReceipt.mockImplementation(async (requestId: string) => ({
      request_id: requestId,
      principal_id: '11',
      api_key: 'one-time-key',
      signing_secret: 'one-time-secret'
    }))
    const wrapper = await open()
    await flushPromises()
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '轮换')!
      .trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('one-time-secret')
    expect(JSON.stringify(sessionStorage)).not.toContain('one-time')
  })
  it('未查到或回执不匹配时保留原轮换，不能重新派发', async () => {
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
    fixture.rotateWithReceipt.mockRejectedValue({ status: 409 })
    const wrapper = await open()
    await flushPromises()
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '轮换')!
      .trigger('click')
    await flushPromises()
    fixture.rotationResult.mockResolvedValue({ request_id: 'other', principal_id: '11' })
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '核对原轮换')!
      .trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('轮换结果尚未确认')
    expect(fixture.rotateWithReceipt).toHaveBeenCalledTimes(1)
    expect(
      wrapper
        .findAll('button')
        .find((button) => button.text() === '轮换')!
        .attributes('disabled')
    ).toBeDefined()
  })
  it('服务端确认轮换事务拒绝时清除原请求记录', async () => {
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
    fixture.rotateWithReceipt.mockRejectedValue(
      new HttpClientError({
        status: 409,
        code: 'PRINCIPAL_ROTATION_REJECTED',
        traceId: 'trace',
        userMessage: '轮换已拒绝'
      })
    )
    const wrapper = await open()
    await flushPromises()
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '轮换')!
      .trigger('click')
    await flushPromises()
    expect(sessionStorage.getItem('skillforge.principal-rotation:["10","member"]')).toBeNull()
    expect(
      wrapper
        .findAll('button')
        .find((button) => button.text() === '轮换')!
        .attributes('disabled')
    ).toBeUndefined()
  })
  it('损坏的轮换记录不会被当作没有待核对操作', async () => {
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
    sessionStorage.setItem('skillforge.principal-rotation:["10","member"]', 'null')
    const wrapper = await open()
    await flushPromises()
    expect(wrapper.text()).toContain('无法读取轮换操作记录')
    expect(
      wrapper
        .findAll('button')
        .find((button) => button.text() === '轮换')!
        .attributes('disabled')
    ).toBeDefined()
    expect(fixture.rotateWithReceipt).not.toHaveBeenCalled()
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
