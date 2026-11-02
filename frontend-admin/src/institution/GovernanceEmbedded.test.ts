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
  issueWithReceipt: vi.fn(),
  issueResult: vi.fn(),
  rotationResult: vi.fn(),
  changeStatusWithReceipt: vi.fn(),
  statusResult: vi.fn(),
  confirm: vi.fn(),
  webhooks: vi.fn(),
  webhookCreate: vi.fn(),
  webhookResult: vi.fn(),
  webhookPause: vi.fn(),
  webhookPauseResult: vi.fn(),
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
        rotationResult: fixture.rotationResult,
        issueWithReceipt: fixture.issueWithReceipt,
        issueResult: fixture.issueResult,
        changeStatusWithReceipt: fixture.changeStatusWithReceipt,
        statusResult: fixture.statusResult
      },
      webhooks: {
        list: fixture.webhooks,
        createWithReceipt: fixture.webhookCreate,
        createResult: fixture.webhookResult,
        pauseWithReceipt: fixture.webhookPause,
        pauseResult: fixture.webhookPauseResult
      },
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
  fixture.issueResult.mockResolvedValue(undefined)
  fixture.statusResult.mockResolvedValue(undefined)
  fixture.webhookResult.mockResolvedValue(undefined)
  fixture.webhookPauseResult.mockResolvedValue(undefined)
  fixture.webhooks.mockResolvedValue([])
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
  it('原签发回执匹配后自动恢复，且不会再次签发', async () => {
    fixture.auth.user.permissions = [
      'foundation.service_principals.read',
      'foundation.service_principals.create'
    ]
    const request = 'd13c4df2-81ec-40f2-ae3b-f277bc177a3e'
    sessionStorage.setItem('skillforge.principal-issue:["10","member"]', request)
    fixture.issueResult.mockResolvedValue({ request_id: request, principal_id: '11' })
    const wrapper = await open()
    await flushPromises()
    expect(fixture.issueResult).toHaveBeenCalledWith(request)
    expect(fixture.issueWithReceipt).not.toHaveBeenCalled()
    expect(sessionStorage.getItem('skillforge.principal-issue:["10","member"]')).toBeNull()
    expect(wrapper.text()).toContain('原签发已确认（服务身份 11）')
  })
  it('签发恢复后可从提示直接轮换对应身份', async () => {
    fixture.auth.user.permissions = [
      'foundation.service_principals.read',
      'foundation.service_principals.update'
    ]
    const request = 'd13c4df2-81ec-40f2-ae3b-f277bc177a3e'
    sessionStorage.setItem('skillforge.principal-issue:["10","member"]', request)
    fixture.issueResult.mockResolvedValue({ request_id: request, principal_id: '11' })
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
      api_key: 'replacement-key',
      signing_secret: 'replacement-secret'
    }))
    const wrapper = await open()
    await flushPromises()
    expect(wrapper.text()).toContain('集成“integration-a”')
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '轮换该身份凭据')!
      .trigger('click')
    await flushPromises()
    expect(fixture.rotateWithReceipt).toHaveBeenCalledTimes(1)
    expect(document.body.textContent).toContain('replacement-secret')
    expect(wrapper.text()).not.toContain('首次凭据无法恢复')
  })
  it('未查到或不匹配的签发回执保持未知并禁止再次创建', async () => {
    fixture.auth.user.permissions = [
      'foundation.service_principals.read',
      'foundation.service_principals.create'
    ]
    const request = 'd13c4df2-81ec-40f2-ae3b-f277bc177a3e'
    sessionStorage.setItem('skillforge.principal-issue:["10","member"]', request)
    fixture.issueResult.mockResolvedValue({ request_id: 'other', principal_id: '11' })
    const wrapper = await open()
    await flushPromises()
    expect(wrapper.text()).toContain('服务身份签发结果尚未确认')
    expect(sessionStorage.getItem('skillforge.principal-issue:["10","member"]')).toBe(request)
    expect(
      wrapper
        .findAll('button')
        .find((button) => button.text() === '创建服务身份')!
        .attributes('disabled')
    ).toBeDefined()
    expect(fixture.issueWithReceipt).not.toHaveBeenCalled()
  })
  it('缺少读取权限时保留签发请求，不自动核对或再次创建', async () => {
    fixture.auth.user.permissions = ['foundation.service_principals.create']
    const request = 'd13c4df2-81ec-40f2-ae3b-f277bc177a3e'
    sessionStorage.setItem('skillforge.principal-issue:["10","member"]', request)
    const wrapper = await open()
    await flushPromises()
    expect(wrapper.text()).toContain('当前账号没有服务身份读取权限')
    expect(fixture.issueResult).not.toHaveBeenCalled()
    expect(fixture.issueWithReceipt).not.toHaveBeenCalled()
    expect(sessionStorage.getItem('skillforge.principal-issue:["10","member"]')).toBe(request)
  })
  it('签发记录损坏时不允许创建新身份', async () => {
    fixture.auth.user.permissions = [
      'foundation.service_principals.read',
      'foundation.service_principals.create'
    ]
    sessionStorage.setItem('skillforge.principal-issue:["10","member"]', 'invalid')
    const wrapper = await open()
    await flushPromises()
    expect(wrapper.text()).toContain('无法读取签发操作记录')
    expect(
      wrapper
        .findAll('button')
        .find((button) => button.text() === '创建服务身份')!
        .attributes('disabled')
    ).toBeDefined()
    expect(fixture.issueWithReceipt).not.toHaveBeenCalled()
  })
  it.each(['suspend', 'revoke'] as const)('%s 断连后重建页面只读核对原操作', async (action) => {
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
    fixture.changeStatusWithReceipt.mockRejectedValueOnce({ status: 503 })
    let wrapper = await open()
    await flushPromises()
    const label = action === 'suspend' ? '暂停' : '吊销'
    await wrapper
      .findAll('button')
      .find((button) => button.text() === label)!
      .trigger('click')
    await flushPromises()
    const request = fixture.changeStatusWithReceipt.mock.calls[0]![0]
    expect(sessionStorage.getItem('skillforge.principal-status:["10","member"]')).toContain(request)
    wrapper.unmount()
    fixture.statusResult.mockResolvedValue({
      request_id: request,
      principal_id: '11',
      action,
      changed: true
    })
    wrapper = await open()
    await flushPromises()
    expect(fixture.statusResult).toHaveBeenCalledWith(request)
    expect(fixture.changeStatusWithReceipt).toHaveBeenCalledTimes(1)
    expect(sessionStorage.getItem('skillforge.principal-status:["10","member"]')).toBeNull()
    expect(wrapper.text()).toContain(`原${label}操作已确认`)
    expect(wrapper.text()).toContain('集成“integration-a”')
  })
  it.each([400, 401, 403, 409, 429, 503])(
    '状态操作返回 %s 不能凭状态码解除未知',
    async (status) => {
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
      fixture.changeStatusWithReceipt.mockRejectedValueOnce(
        new HttpClientError({
          status,
          code: 'UNCONFIRMED',
          traceId: 'trace',
          userMessage: '结果未知'
        })
      )
      const wrapper = await open()
      await flushPromises()
      await wrapper
        .findAll('button')
        .find((button) => button.text() === '暂停')!
        .trigger('click')
      await flushPromises()
      expect(wrapper.text()).toContain('暂停结果尚未确认')
      expect(sessionStorage.getItem('skillforge.principal-status:["10","member"]')).toBeTruthy()
      expect(fixture.changeStatusWithReceipt).toHaveBeenCalledTimes(1)
    }
  )
  it('状态请求记录无法写入时不派发操作', async () => {
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
    const wrapper = await open()
    await flushPromises()
    const storage = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw Error('storage unavailable')
    })
    try {
      await wrapper
        .findAll('button')
        .find((button) => button.text() === '暂停')!
        .trigger('click')
      await flushPromises()
      expect(wrapper.text()).toContain('无法保存状态操作记录')
      expect(fixture.changeStatusWithReceipt).not.toHaveBeenCalled()
    } finally {
      storage.mockRestore()
    }
  })
  it('不匹配的状态回执不解除原请求', async () => {
    fixture.auth.user.permissions = [
      'foundation.service_principals.read',
      'foundation.service_principals.update'
    ]
    const request = 'd13c4df2-81ec-40f2-ae3b-f277bc177a3e'
    sessionStorage.setItem(
      'skillforge.principal-status:["10","member"]',
      JSON.stringify({ requestId: request, principalId: '11', action: 'suspend' })
    )
    fixture.statusResult.mockResolvedValue({
      request_id: request,
      principal_id: '11',
      action: 'revoke',
      changed: true
    })
    const wrapper = await open()
    await flushPromises()
    expect(wrapper.text()).toContain('暂停结果尚未确认')
    expect(sessionStorage.getItem('skillforge.principal-status:["10","member"]')).toContain(request)
    expect(fixture.changeStatusWithReceipt).not.toHaveBeenCalled()
  })
  it('状态未发生变更时明确提示，不报告成功', async () => {
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
    fixture.changeStatusWithReceipt.mockImplementation(
      async (requestId: string, id: number, action: string) => ({
        request_id: requestId,
        principal_id: String(id),
        action,
        changed: false
      })
    )
    const wrapper = await open()
    await flushPromises()
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '吊销')!
      .trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('原吊销操作未改变服务身份')
    expect(wrapper.text()).not.toContain('原吊销操作已确认')
    expect(sessionStorage.getItem('skillforge.principal-status:["10","member"]')).toBeNull()
  })
  it('缺少读取权限时保留原状态请求且不自动核对', async () => {
    fixture.auth.user.permissions = ['foundation.service_principals.update']
    const request = 'd13c4df2-81ec-40f2-ae3b-f277bc177a3e'
    sessionStorage.setItem(
      'skillforge.principal-status:["10","member"]',
      JSON.stringify({ requestId: request, principalId: '11', action: 'revoke' })
    )
    const wrapper = await open()
    await flushPromises()
    expect(wrapper.text()).toContain('当前账号没有服务身份读取权限')
    expect(fixture.statusResult).not.toHaveBeenCalled()
    expect(sessionStorage.getItem('skillforge.principal-status:["10","member"]')).toContain(request)
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

describe('Webhook 原订阅核对', () => {
  const key = 'skillforge.webhook-create:["10","member"]'
  const request = '00000000-0000-4000-8000-000000000001'
  async function openWebhooks() {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/admin/governance', component: GovernancePage }]
    })
    await router.push('/admin/governance')
    await router.isReady()
    const wrapper = mount(GovernancePage, { global: { plugins: [router] } })
    wrappers.push(wrapper)
    await flushPromises()
    return wrapper
  }
  it('仅有 Webhook 读取权限时默认展示 Webhook，不读取其他区域', async () => {
    fixture.auth.user.permissions = ['foundation.webhooks.read', 'foundation.webhooks.create']
    const wrapper = await openWebhooks()
    expect(fixture.webhooks).toHaveBeenCalled()
    expect(fixture.list).not.toHaveBeenCalled()
    expect(fixture.audit).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('订阅回调')
    expect(wrapper.text()).not.toContain('创建服务身份')
  })
  it('页面重建只读核对原订阅，匹配后展示订阅编号', async () => {
    fixture.auth.user.permissions = ['foundation.webhooks.read', 'foundation.webhooks.create']
    sessionStorage.setItem(key, request)
    fixture.webhookResult.mockResolvedValue({ request_id: request, subscription_id: '42' })
    const wrapper = await openWebhooks()
    expect(fixture.webhookResult).toHaveBeenCalledWith(request)
    expect(fixture.webhookCreate).not.toHaveBeenCalled()
    expect(sessionStorage.getItem(key)).toBeNull()
    expect(wrapper.text()).toContain('订阅 ID：42')
  })
  it.each([undefined, { request_id: 'other', subscription_id: '42' }])(
    '未查到或不匹配回执时保留原请求',
    async (receipt) => {
      fixture.auth.user.permissions = ['foundation.webhooks.read', 'foundation.webhooks.create']
      sessionStorage.setItem(key, request)
      fixture.webhookResult.mockResolvedValue(receipt)
      const wrapper = await openWebhooks()
      expect(sessionStorage.getItem(key)).toBe(request)
      expect(wrapper.text()).toContain('创建结果尚未确认')
      expect(fixture.webhookCreate).not.toHaveBeenCalled()
      expect(
        wrapper
          .findAll('button')
          .find((button) => button.text() === '订阅回调')
          ?.attributes('disabled')
      ).toBeDefined()
    }
  )
  it('没有读取权限时保留原请求而不自动核对', async () => {
    fixture.auth.user.permissions = [
      'foundation.service_principals.read',
      'foundation.webhooks.create'
    ]
    sessionStorage.setItem(key, request)
    const wrapper = await openWebhooks()
    expect(fixture.webhookResult).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('没有 Webhook 读取权限')
    expect(sessionStorage.getItem(key)).toBe(request)
  })
  it('提交断连保留原请求且不保存密钥，重建后只查询回执', async () => {
    fixture.auth.user.permissions = ['foundation.webhooks.read', 'foundation.webhooks.create']
    fixture.webhookCreate.mockRejectedValueOnce(Error('connection closed'))
    const wrapper = await openWebhooks()
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '订阅回调')!
      .trigger('click')
    await flushPromises()
    const endpoint = document.querySelector('input[placeholder="https://"]') as HTMLInputElement
    const secret = document.querySelector('input[type="password"]') as HTMLInputElement
    endpoint.value = 'https://127.0.0.1:1/callback'
    endpoint.dispatchEvent(new Event('input', { bubbles: true }))
    secret.value = 'fixture-secret'
    secret.dispatchEvent(new Event('input', { bubbles: true }))
    const createButton = [...document.querySelectorAll('button')].find(
      (button) => button.textContent?.trim() === '创建'
    )!
    createButton.click()
    await flushPromises()
    expect(fixture.webhookCreate).toHaveBeenCalledTimes(1)
    const original = fixture.webhookCreate.mock.calls[0]![0]
    expect(sessionStorage.getItem(key)).toBe(original)
    expect(JSON.stringify(sessionStorage)).not.toContain('fixture-secret')
    wrapper.unmount()
    fixture.webhookResult.mockResolvedValue({ request_id: original, subscription_id: '42' })
    const restored = await openWebhooks()
    expect(fixture.webhookResult).toHaveBeenCalledWith(original)
    expect(fixture.webhookCreate).toHaveBeenCalledTimes(1)
    expect(restored.text()).toContain('订阅 ID：42')
  })
  it.each(['storage', 'rejected'] as const)('订阅创建 %s 时不丢失结果边界', async (mode) => {
    fixture.auth.user.permissions = ['foundation.webhooks.read', 'foundation.webhooks.create']
    const wrapper = await openWebhooks()
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '订阅回调')!
      .trigger('click')
    await flushPromises()
    const endpoint = document.querySelector('input[placeholder="https://"]') as HTMLInputElement
    const secret = document.querySelector('input[type="password"]') as HTMLInputElement
    endpoint.value = 'https://127.0.0.1:1/callback'
    endpoint.dispatchEvent(new Event('input', { bubbles: true }))
    secret.value = 'fixture-secret'
    secret.dispatchEvent(new Event('input', { bubbles: true }))
    if (mode === 'storage')
      vi.spyOn(Storage.prototype, 'setItem').mockImplementationOnce(() => {
        throw Error('storage unavailable')
      })
    else
      fixture.webhookCreate.mockRejectedValueOnce(
        new HttpClientError({
          status: 400,
          code: 'WEBHOOK_CREATE_REJECTED',
          traceId: 'trace',
          userMessage: '已拒绝'
        })
      )
    ;[...document.querySelectorAll('button')]
      .find((button) => button.textContent?.trim() === '创建')!
      .click()
    await flushPromises()
    if (mode === 'storage') {
      expect(fixture.webhookCreate).not.toHaveBeenCalled()
      expect(document.body.textContent).toContain('无法保存订阅操作记录')
      vi.restoreAllMocks()
      const reloadButton = [...document.querySelectorAll('.el-drawer button')].find(
        (button) => button.textContent?.trim() === '重新读取订阅记录'
      ) as HTMLButtonElement
      expect(reloadButton).toBeTruthy()
      reloadButton.click()
      await flushPromises()
      expect(document.body.textContent).not.toContain('无法保存订阅操作记录')
      expect(fixture.webhookCreate).not.toHaveBeenCalled()
    } else {
      expect(fixture.webhookCreate).toHaveBeenCalledTimes(1)
      expect(sessionStorage.getItem(key)).toBeNull()
    }
  })
  it.each(['endpoint', 'secret'] as const)('超长 %s 派发前阻止提交', async (field) => {
    fixture.auth.user.permissions = ['foundation.webhooks.read', 'foundation.webhooks.create']
    const wrapper = await openWebhooks()
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '订阅回调')!
      .trigger('click')
    await flushPromises()
    const endpoint = document.querySelector('input[placeholder="https://"]') as HTMLInputElement
    const secret = document.querySelector('input[type="password"]') as HTMLInputElement
    endpoint.value =
      field === 'endpoint' ? 'https://' + 'a'.repeat(2042) : 'https://127.0.0.1:1/callback'
    endpoint.dispatchEvent(new Event('input', { bubbles: true }))
    secret.value = field === 'secret' ? 'x'.repeat(4097) : 'fixture-secret'
    secret.dispatchEvent(new Event('input', { bubbles: true }))
    ;[...document.querySelectorAll('button')]
      .find((button) => button.textContent?.trim() === '创建')!
      .click()
    await flushPromises()
    expect(fixture.webhookCreate).not.toHaveBeenCalled()
    expect(sessionStorage.getItem(key)).toBeNull()
  })
  it('损坏的本地记录禁止创建', async () => {
    fixture.auth.user.permissions = ['foundation.webhooks.read', 'foundation.webhooks.create']
    sessionStorage.setItem(key, 'broken')
    const wrapper = await openWebhooks()
    expect(wrapper.text()).toContain('无法读取订阅操作记录')
    expect(
      wrapper
        .findAll('button')
        .find((button) => button.text() === '订阅回调')
        ?.attributes('disabled')
    ).toBeDefined()
    expect(fixture.webhookCreate).not.toHaveBeenCalled()
  })
  it('暂停断连保留原请求，重建页面只读核对并禁用已暂停记录', async () => {
    fixture.auth.user.permissions = ['foundation.webhooks.read', 'foundation.webhooks.update']
    fixture.webhooks.mockResolvedValue([
      { id: 42, endpoint_url: 'https://example.test', status: 'active' }
    ])
    fixture.webhookPause.mockRejectedValueOnce(Error('connection closed'))
    let wrapper = await openWebhooks()
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '暂停')!
      .trigger('click')
    await flushPromises()
    expect(fixture.webhookPause).toHaveBeenCalledTimes(1)
    const original = fixture.webhookPause.mock.calls[0]![0]
    const pauseKey = 'skillforge.webhook-pause:["10","member"]'
    expect(sessionStorage.getItem(pauseKey)).toContain(original)
    expect(wrapper.text()).toContain('暂停结果尚未确认')
    expect(
      wrapper
        .findAll('button')
        .find((button) => button.text() === '暂停')!
        .attributes('disabled')
    ).toBeDefined()
    wrapper.unmount()
    fixture.webhooks.mockResolvedValue([
      { id: 42, endpoint_url: 'https://example.test', status: 'paused' }
    ])
    fixture.webhookPauseResult.mockResolvedValue({
      request_id: original,
      subscription_id: '42',
      changed: true
    })
    wrapper = await openWebhooks()
    expect(fixture.webhookPauseResult).toHaveBeenCalledWith(original)
    expect(fixture.webhookPause).toHaveBeenCalledTimes(1)
    expect(sessionStorage.getItem(pauseKey)).toBeNull()
    expect(wrapper.text()).toContain('原暂停操作已确认')
    expect(wrapper.text()).toContain('已暂停')
    expect(
      wrapper
        .findAll('button')
        .find((button) => button.text() === '暂停')!
        .attributes('disabled')
    ).toBeDefined()
  })
  it.each([undefined, { request_id: request, subscription_id: '99', changed: true }])(
    '缺失或不匹配的暂停回执不解除未知结果',
    async (receipt) => {
      fixture.auth.user.permissions = ['foundation.webhooks.read', 'foundation.webhooks.update']
      const pauseKey = 'skillforge.webhook-pause:["10","member"]'
      sessionStorage.setItem(pauseKey, JSON.stringify({ requestId: request, subscriptionId: '42' }))
      fixture.webhookPauseResult.mockResolvedValue(receipt)
      const wrapper = await openWebhooks()
      expect(wrapper.text()).toContain('暂停结果尚未确认')
      expect(sessionStorage.getItem(pauseKey)).toContain(request)
      expect(fixture.webhookPause).not.toHaveBeenCalled()
    }
  )
  it('暂停返回未改变时如实显示，并允许其他领域操作', async () => {
    fixture.auth.user.permissions = [
      'foundation.webhooks.read',
      'foundation.webhooks.update',
      'foundation.webhooks.create'
    ]
    fixture.webhooks.mockResolvedValue([
      { id: 42, endpoint_url: 'https://example.test', status: 'active' }
    ])
    fixture.webhookPause.mockImplementation(async (id: string) => ({
      request_id: id,
      subscription_id: '42',
      changed: false
    }))
    const wrapper = await openWebhooks()
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '暂停')!
      .trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('原暂停操作未改变回调订阅')
    expect(wrapper.text()).not.toContain('原暂停操作已确认')
    expect(sessionStorage.getItem('skillforge.webhook-pause:["10","member"]')).toBeNull()
    expect(
      wrapper
        .findAll('button')
        .find((button) => button.text() === '订阅回调')!
        .attributes('disabled')
    ).toBeUndefined()
  })
  it('暂停记录无法写入时不派发，可重新读取存储', async () => {
    fixture.auth.user.permissions = ['foundation.webhooks.read', 'foundation.webhooks.update']
    fixture.webhooks.mockResolvedValue([
      { id: 42, endpoint_url: 'https://example.test', status: 'active' }
    ])
    const wrapper = await openWebhooks()
    vi.spyOn(Storage.prototype, 'setItem').mockImplementationOnce(() => {
      throw Error('unavailable')
    })
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '暂停')!
      .trigger('click')
    await flushPromises()
    expect(fixture.webhookPause).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('无法保存订阅暂停记录')
    vi.restoreAllMocks()
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '重新读取暂停记录')!
      .trigger('click')
    await flushPromises()
    expect(wrapper.text()).not.toContain('无法保存订阅暂停记录')
  })
  it('缺少读取权限时不自动核对暂停且保留原请求', async () => {
    fixture.auth.user.permissions = [
      'foundation.service_principals.read',
      'foundation.webhooks.update'
    ]
    const pauseKey = 'skillforge.webhook-pause:["10","member"]'
    sessionStorage.setItem(pauseKey, JSON.stringify({ requestId: request, subscriptionId: '42' }))
    const wrapper = await openWebhooks()
    expect(fixture.webhookPauseResult).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('没有 Webhook 读取权限')
    expect(sessionStorage.getItem(pauseKey)).toContain(request)
  })
  it('暂停回执迟到时授权已变化，不清除原请求', async () => {
    fixture.auth.user.permissions = ['foundation.webhooks.read', 'foundation.webhooks.update']
    const pauseKey = 'skillforge.webhook-pause:["10","member"]'
    sessionStorage.setItem(pauseKey, JSON.stringify({ requestId: request, subscriptionId: '42' }))
    let finish!: (receipt: unknown) => void
    fixture.webhookPauseResult.mockReturnValueOnce(
      new Promise((resolve) => {
        finish = resolve
      })
    )
    const wrapper = await openWebhooks()
    reactive(fixture.auth).user.authorization_version++
    finish({ request_id: request, subscription_id: '42', changed: true })
    await flushPromises()
    expect(sessionStorage.getItem(pauseKey)).toContain(request)
    expect(wrapper.text()).not.toContain('原暂停操作已确认')
  })
  it('损坏的暂停记录禁止派发，仍可使用其他功能', async () => {
    fixture.auth.user.permissions = [
      'foundation.webhooks.read',
      'foundation.webhooks.update',
      'foundation.webhooks.create'
    ]
    sessionStorage.setItem('skillforge.webhook-pause:["10","member"]', 'broken')
    fixture.webhooks.mockResolvedValue([
      { id: 42, endpoint_url: 'https://example.test', status: 'active' }
    ])
    const wrapper = await openWebhooks()
    expect(wrapper.text()).toContain('无法读取订阅暂停记录')
    expect(
      wrapper
        .findAll('button')
        .find((button) => button.text() === '暂停')!
        .attributes('disabled')
    ).toBeDefined()
    expect(
      wrapper
        .findAll('button')
        .find((button) => button.text() === '订阅回调')!
        .attributes('disabled')
    ).toBeUndefined()
    expect(fixture.webhookPause).not.toHaveBeenCalled()
  })
  it.each([
    ['WEBHOOK_PAUSE_REJECTED', true],
    ['OTHER_FORBIDDEN', false]
  ] as const)('暂停返回 %s 时按明确拒绝边界保留请求', async (code, cleared) => {
    fixture.auth.user.permissions = ['foundation.webhooks.read', 'foundation.webhooks.update']
    fixture.webhooks.mockResolvedValue([
      { id: 42, endpoint_url: 'https://example.test', status: 'active' }
    ])
    fixture.webhookPause.mockRejectedValueOnce(
      new HttpClientError({
        status: 403,
        code,
        traceId: 'trace',
        userMessage: '操作未获授权'
      })
    )
    const wrapper = await openWebhooks()
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '暂停')!
      .trigger('click')
    await flushPromises()
    const raw = sessionStorage.getItem('skillforge.webhook-pause:["10","member"]')
    if (cleared) expect(raw).toBeNull()
    else expect(raw).toContain(fixture.webhookPause.mock.calls[0]![0])
    expect(fixture.webhookPause).toHaveBeenCalledTimes(1)
  })
  it('权限拒绝返回前身份变化时保留原暂停请求', async () => {
    fixture.auth.user.permissions = ['foundation.webhooks.read', 'foundation.webhooks.update']
    fixture.webhooks.mockResolvedValue([
      { id: 42, endpoint_url: 'https://example.test', status: 'active' }
    ])
    let reject!: (reason: unknown) => void
    fixture.webhookPause.mockReturnValueOnce(
      new Promise((_, fail) => {
        reject = fail
      })
    )
    const wrapper = await openWebhooks()
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '暂停')!
      .trigger('click')
    await flushPromises()
    reactive(fixture.auth).user.authorization_version++
    reject(
      new HttpClientError({
        status: 403,
        code: 'WEBHOOK_PAUSE_REJECTED',
        traceId: 'trace',
        userMessage: '操作未获授权'
      })
    )
    await flushPromises()
    expect(sessionStorage.getItem('skillforge.webhook-pause:["10","member"]')).toContain(
      fixture.webhookPause.mock.calls[0]![0]
    )
  })
})
