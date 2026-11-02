import { defineComponent, h, reactive, type Component } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { ElButton, ElFormItem, ElInput, ElRadioGroup, ElStep } from 'element-plus'
import { HttpClientError } from '@skillforge/shared/utils/http'
import { useUnsavedChanges } from '@skillforge/shared/admin'
import InstitutionOnboardingFlow from '../views/platform/InstitutionOnboardingFlow.vue'

const mocks = vi.hoisted(() => ({
  auth: undefined as unknown as {
    identityVersion: number
    user: { sub: string; authorization_version: number; permissions: string[] }
  },
  create: vi.fn(),
  lookup: vi.fn(),
  receipt: vi.fn(),
  plans: vi.fn(),
  confirm: vi.fn(),
  list: vi.fn(),
  credentials: vi.fn(),
  feedbackError: vi.fn(),
  agentBusy: false
}))
vi.mock('../stores/auth', () => ({ useAuthStore: () => mocks.auth }))
vi.mock('../api', () => ({
  api: {
    admin: {
      createTenant: mocks.create,
      tenantCreateResult: mocks.receipt,
      findTenantBySlug: mocks.lookup,
      plans: mocks.plans
    },
    agentDeployments: { list: mocks.list },
    platformIntegration: { institutionCredentials: mocks.credentials }
  }
}))
vi.mock('@skillforge/shared/feedback', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@skillforge/shared/feedback')>()),
  confirm: mocks.confirm,
  message: { warning: vi.fn(), success: vi.fn(), error: mocks.feedbackError }
}))
vi.mock('../views/platform/InstitutionIntegrationsPage.vue', () => ({
  default: {
    name: 'MappingPanel',
    props: ['tenantId', 'embedded'],
    emits: ['provisioned', 'changed', 'integrationSelected'],
    template: '<div>真实接入面板复用点</div>'
  }
}))
vi.mock('../views/platform/InstitutionCredentialsPage.vue', () => ({
  default: {
    name: 'CredentialPanel',
    props: ['tenantId', 'integrationId', 'embedded'],
    emits: ['changed', 'integrationSelected'],
    template: '<div>凭证交付面板复用点</div>'
  }
}))
vi.mock('../views/platform/InstitutionAgentSetup.vue', () => ({
  default: {
    name: 'AgentSetupPanel',
    props: ['tenantId', 'integrationId'],
    emits: ['completed', 'changed'],
    setup() {
      useUnsavedChanges({ dirty: () => false, busy: () => mocks.agentBusy })
    },
    template: '<div>智能体连续流程复用点</div>'
  }
}))
const wrappers: VueWrapper[] = []
beforeEach(() => {
  vi.resetAllMocks()
  sessionStorage.clear()
  mocks.agentBusy = false
  mocks.auth = reactive({
    identityVersion: 1,
    user: {
      sub: 'platform-user',
      authorization_version: 1,
      permissions: [
        'foundation.tenants.create',
        'foundation.tenants.read',
        'platform.institutions.associate',
        'platform.institution_credentials.read',
        'platform.institution_credentials.issue',
        'platform.agent_deployments.create',
        'platform.agent_deployments.read'
      ]
    }
  })
  mocks.plans.mockResolvedValue([{ plan_code: 'free', display_name: '免费' }])
  mocks.create.mockImplementation(async (requestId: string) => ({
    request_id: requestId,
    status: 'SUCCEEDED',
    tenant_id: '9223372036854775807',
    tenant_name: '验收机构'
  }))
  mocks.lookup.mockResolvedValue(null)
  mocks.list.mockResolvedValue({ items: [], total: 0 })
  mocks.credentials.mockResolvedValue({ items: [], total: 0 })
  mocks.confirm.mockResolvedValue(true)
})
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  document.body.innerHTML = ''
})
async function open(props: { tenantId?: string } = {}) {
  const routeComponent: Component = defineComponent({
    setup: () => () => h(InstitutionOnboardingFlow, props)
  })
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/test', component: routeComponent }]
  })
  await router.push('/test')
  await router.isReady()
  const wrapper = mount(defineComponent({ template: '<RouterView />' }), {
    attachTo: document.body,
    global: { plugins: [router] }
  })
  wrappers.push(wrapper)
  await flushPromises()
  return wrapper
}
function button(wrapper: VueWrapper, label: string) {
  const found = wrapper.findAllComponents(ElButton).find((item) => item.text() === label)
  if (!found) throw Error(label)
  return found
}
async function fill(wrapper: VueWrapper, name: string, value: string) {
  const item = wrapper.findAllComponents(ElFormItem).find((field) => field.props('prop') === name)
  if (!item) throw Error(name)
  await item.findComponent(ElInput).get('input').setValue(value)
}
async function fillOrdinary(wrapper: VueWrapper) {
  await fill(wrapper, 'tenant_name', '验收机构')
  await fill(wrapper, 'tenant_slug', 'test-institution')
  await fill(wrapper, 'admin_username', 'first-admin')
  await fill(wrapper, 'admin_password', 'TemporaryTestPass123')
}

describe('平台连续开通流程', () => {
  it('空表校验只展示字段提示且不创建，切换接入意图后清除本地校验总错', async () => {
    const wrapper = await open()
    await button(wrapper, '创建机构').trigger('click')
    await flushPromises()
    expect(mocks.create).not.toHaveBeenCalled()
    expect(document.body.querySelector('.el-message')?.textContent).toContain(
      '请检查表单中标出的字段'
    )
    expect(wrapper.text()).not.toContain('暂时无法确认操作结果')
    expect(wrapper.text()).not.toContain('机构创建结果未知')
    wrapper.findComponent(ElRadioGroup).vm.$emit('change', 'mapping')
    await flushPromises()
    expect(wrapper.findComponent({ name: 'MappingPanel' }).exists()).toBe(true)
    expect(wrapper.text()).not.toContain('请检查表单中标出的字段')
    expect(mocks.create).not.toHaveBeenCalled()
  })

  it('普通创建保留精确机构身份，后续配置不重新创建或自动签发凭证', async () => {
    const wrapper = await open()
    await fillOrdinary(wrapper)
    await button(wrapper, '创建机构').trigger('click')
    await flushPromises()
    expect(mocks.create).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('9223372036854775807')
    expect(
      wrapper
        .findAll('input[type="password"]')
        .every((input) => !(input.element as HTMLInputElement).value)
    ).toBe(true)
    expect(wrapper.findComponent({ name: 'CredentialPanel' }).props('tenantId')).toBe(
      '9223372036854775807'
    )
    expect(wrapper.findAllComponents(ElButton).some((item) => item.text() === '继续配置')).toBe(
      false
    )
    await button(wrapper, '上一步').trigger('click')
    await flushPromises()
    expect(button(wrapper, '继续关联外部系统').exists()).toBe(true)
    expect(mocks.create).toHaveBeenCalledTimes(1)
    expect(mocks.credentials).not.toHaveBeenCalled()
  })

  it('普通创建结果未知后按原请求核对，不重复创建', async () => {
    mocks.create.mockRejectedValue(new HttpClientError({ status: 0, userMessage: '创建结果未知' }))
    const wrapper = await open()
    await fillOrdinary(wrapper)
    await button(wrapper, '创建机构').trigger('click')
    await flushPromises()
    const requestId = mocks.create.mock.calls[0]![0]
    expect(button(wrapper, '创建机构').attributes('disabled')).toBeDefined()
    wrapper.findComponent(ElRadioGroup).vm.$emit('change', 'mapping')
    await flushPromises()
    expect(wrapper.findComponent({ name: 'MappingPanel' }).exists()).toBe(false)
    expect(wrapper.text()).toContain('机构创建结果未知')
    mocks.receipt.mockResolvedValue({
      request_id: requestId,
      status: 'SUCCEEDED',
      tenant_id: '51',
      tenant_name: '验收机构'
    })
    await button(wrapper, '核对原请求').trigger('click')
    await flushPromises()
    expect(mocks.receipt).toHaveBeenCalledWith(requestId, { silent: true })
    expect(wrapper.text()).toContain('本次目标：验收机构')
    expect(wrapper.findComponent({ name: 'CredentialPanel' }).props('tenantId')).toBe('51')
    expect(mocks.credentials).not.toHaveBeenCalled()
    expect(mocks.create).toHaveBeenCalledTimes(1)
    expect(mocks.lookup).not.toHaveBeenCalled()
  })

  it('旧的标识核对记录只读查找并在确认后采用机构', async () => {
    sessionStorage.setItem('skillforge.platform-tenant-create:platform-user', 'test-institution')
    mocks.lookup.mockResolvedValue({ id: '51', name: '验收机构', slug: 'test-institution' })
    const wrapper = await open()
    await button(wrapper, '按原机构标识核对').trigger('click')
    await flushPromises()
    expect(mocks.lookup).toHaveBeenCalledWith('test-institution', { silent: true })
    await button(wrapper, '使用该机构继续').trigger('click')
    await flushPromises()
    expect(mocks.confirm).toHaveBeenCalledWith(
      expect.objectContaining({ title: '使用已找到的机构继续' })
    )
    expect(wrapper.text()).toContain('本次目标：验收机构')
    expect(mocks.create).not.toHaveBeenCalled()
  })

  it('从既有映射直接进入同机构执行凭证，不重新创建或关联机构', async () => {
    const wrapper = await open()
    wrapper.findComponent(ElRadioGroup).vm.$emit('change', 'mapping')
    await flushPromises()
    wrapper
      .findComponent({ name: 'MappingPanel' })
      .vm.$emit('integrationSelected', 'existing-integration', 'existing-tenant')
    await flushPromises()
    expect(wrapper.findComponent({ name: 'CredentialPanel' }).props()).toMatchObject({
      tenantId: 'existing-tenant',
      integrationId: 'existing-integration'
    })
    expect(mocks.create).not.toHaveBeenCalled()
    expect(mocks.credentials).not.toHaveBeenCalled()
  })

  it('拒绝既有映射携带的其他机构身份，保持当前机构与步骤', async () => {
    const wrapper = await open({ tenantId: '41' })
    wrapper
      .findComponent({ name: 'MappingPanel' })
      .vm.$emit('integrationSelected', 'other-integration', '42')
    await flushPromises()
    expect(wrapper.findComponent({ name: 'CredentialPanel' }).exists()).toBe(false)
    expect(wrapper.findComponent({ name: 'MappingPanel' }).props('tenantId')).toBe('41')
    expect(document.body.querySelector('.el-message')?.textContent).toContain(
      '所选接入不属于当前机构'
    )
  })

  it('已有目标复用接入与凭证面板，跳过凭证不显示为成功', async () => {
    const wrapper = await open({ tenantId: '41' })
    const mappings = wrapper.findComponent({ name: 'MappingPanel' })
    expect(mappings.props('tenantId')).toBe('41')
    mappings.vm.$emit('provisioned', { tenantId: '41', integrationId: 'integration-1' })
    await flushPromises()
    await flushPromises()
    expect(wrapper.findComponent({ name: 'CredentialPanel' }).props()).toMatchObject({
      tenantId: '41',
      integrationId: 'integration-1'
    })
    await button(wrapper, '继续下一步 / 稍后处理').trigger('click')
    await flushPromises()
    const credentialStep = wrapper.findAllComponents(ElStep)[1]!
    expect(credentialStep.props('status')).toBe('wait')
    expect(credentialStep.props('description')).toBe('待配置或待核对')
    expect(wrapper.findComponent({ name: 'AgentSetupPanel' }).props('tenantId')).toBe('41')
  })

  it('变更接入来源后清除旧来源的部署与凭证完成状态', async () => {
    mocks.credentials.mockResolvedValue({ items: [{ status: 'active' }], total: 1 })
    const wrapper = await open({ tenantId: '41' })
    wrapper
      .findComponent({ name: 'MappingPanel' })
      .vm.$emit('provisioned', { tenantId: '41', integrationId: 'i-1' })
    await flushPromises()
    await button(wrapper, '继续下一步 / 稍后处理').trigger('click')
    await flushPromises()
    wrapper.findComponent({ name: 'AgentSetupPanel' }).vm.$emit('completed', { agentId: 'agent-1' })
    await button(wrapper, '继续下一步 / 稍后处理').trigger('click')
    await flushPromises()
    expect(wrapper.findAllComponents(ElStep)[1]!.props('status')).toBe('success')
    expect(wrapper.findAllComponents(ElStep)[2]!.props('status')).toBe('success')
    await button(wrapper, '上一步').trigger('click')
    await button(wrapper, '上一步').trigger('click')
    await flushPromises()
    wrapper.findComponent({ name: 'CredentialPanel' }).vm.$emit('integrationSelected', 'i-2')
    await flushPromises()
    expect(wrapper.findAllComponents(ElStep)[2]!.props('status')).toBe('wait')
    await button(wrapper, '继续下一步 / 稍后处理').trigger('click')
    await flushPromises()
    expect(wrapper.findAllComponents(ElStep)[1]!.props('status')).toBe('wait')
    expect(wrapper.findComponent({ name: 'AgentSetupPanel' }).props('integrationId')).toBe('i-2')
  })

  it.each(['部署', '凭证'])('%s检查失败仍展示另一项事实，刷新后恢复', async (failure) => {
    mocks.list.mockResolvedValue({
      items: [{ definition_name: '助手', ready: true, issues: [] }],
      total: 1
    })
    mocks.credentials.mockResolvedValue({ items: [{ status: 'active' }], total: 1 })
    if (failure === '部署') mocks.list.mockRejectedValueOnce(new Error('部署读取失败'))
    else mocks.credentials.mockRejectedValueOnce(new Error('凭证读取失败'))
    const wrapper = await open({ tenantId: '41' })
    wrapper
      .findComponent({ name: 'MappingPanel' })
      .vm.$emit('provisioned', { tenantId: '41', integrationId: 'i-1' })
    await flushPromises()
    await flushPromises()
    await button(wrapper, '继续下一步 / 稍后处理').trigger('click')
    await flushPromises()
    await button(wrapper, '继续下一步 / 稍后处理').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain(failure === '部署' ? '部署状态读取失败' : '凭证状态读取失败')
    expect(wrapper.text()).toContain(
      failure === '部署' ? '已在读取页确认有效执行凭证' : '已读取 1 个稳定实例'
    )
    expect(wrapper.text()).not.toContain(
      failure === '部署' ? '部署状态：当前账号没有读取权限' : '凭证状态：当前账号没有读取权限'
    )
    expect(mocks.feedbackError).toHaveBeenCalledOnce()
    await button(wrapper, '刷新检查').trigger('click')
    await flushPromises()
    expect(wrapper.text()).not.toContain('状态读取失败')
    expect(wrapper.text()).toContain('已读取 1 个稳定实例')
    expect(wrapper.text()).toContain('已在读取页确认有效执行凭证')
  })

  it('检查未确认凭证时直接返回同机构同接入的凭证目录', async () => {
    const wrapper = await open({ tenantId: '41' })
    wrapper.findComponent({ name: 'MappingPanel' }).vm.$emit('provisioned', {
      tenantId: '41',
      integrationId: 'integration-1'
    })
    await flushPromises()
    await flushPromises()
    await button(wrapper, '继续下一步 / 稍后处理').trigger('click')
    await flushPromises()
    await button(wrapper, '继续下一步 / 稍后处理').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('尚未在读取页确认有效凭证')
    expect(mocks.credentials).toHaveBeenCalledWith(
      { page: 1, tenant_id: '41', integration_id: 'integration-1' },
      expect.objectContaining({ silent: true })
    )
    await button(wrapper, '查看凭证目录').trigger('click')
    await flushPromises()
    expect(wrapper.findComponent({ name: 'CredentialPanel' }).props()).toMatchObject({
      tenantId: '41',
      integrationId: 'integration-1'
    })
    expect(mocks.credentials).toHaveBeenCalledTimes(1)
  })

  it('检查未发现智能体时可直接回到原机构添加，已有未就绪实例显示处理方向', async () => {
    const wrapper = await open({ tenantId: '41' })
    wrapper
      .findComponent({ name: 'MappingPanel' })
      .vm.$emit('provisioned', { tenantId: '41', integrationId: 'i-1' })
    await flushPromises()
    await button(wrapper, '继续下一步 / 稍后处理').trigger('click')
    await button(wrapper, '继续下一步 / 稍后处理').trigger('click')
    await flushPromises()
    await button(wrapper, '添加智能体').trigger('click')
    await flushPromises()
    expect(wrapper.findComponent({ name: 'AgentSetupPanel' }).props()).toMatchObject({
      tenantId: '41',
      integrationId: 'i-1'
    })
    mocks.list.mockResolvedValueOnce({
      items: [{ definition_name: '助手', ready: false, issues: [] }],
      total: 1
    })
    await button(wrapper, '继续下一步 / 稍后处理').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('涉及本地资源时，请机构管理员在机构后台完成配置')
    expect(wrapper.findAllComponents(ElButton).some((item) => item.text() === '添加智能体')).toBe(
      false
    )
  })

  it('切换意图前统一保护未保存输入，取消后保留原表单', async () => {
    const wrapper = await open()
    await fill(wrapper, 'tenant_name', '未保存名称')
    mocks.confirm.mockResolvedValue(false)
    wrapper.findComponent(ElRadioGroup).vm.$emit('change', 'mapping')
    await flushPromises()
    expect(wrapper.findComponent({ name: 'MappingPanel' }).exists()).toBe(false)
    expect(
      wrapper
        .findAllComponents(ElFormItem)
        .find((field) => field.props('prop') === 'tenant_name')!
        .findComponent(ElInput)
        .props('modelValue')
    ).toBe('未保存名称')
  })

  it('子流程结果未知时，内部下一步也不能卸载原操作', async () => {
    const wrapper = await open({ tenantId: '41' })
    await button(wrapper, '继续配置').trigger('click')
    await flushPromises()
    await button(wrapper, '继续下一步 / 稍后处理').trigger('click')
    await flushPromises()
    mocks.agentBusy = true
    await button(wrapper, '继续下一步 / 稍后处理').trigger('click')
    await flushPromises()
    expect(wrapper.findComponent({ name: 'AgentSetupPanel' }).exists()).toBe(true)
    expect(wrapper.text()).not.toContain('只读接入检查')
    expect(mocks.list).not.toHaveBeenCalled()
  })

  it('账号变化清除密码和受保护流程，不采纳迟到创建回执', async () => {
    let resolve!: (value: unknown) => void
    mocks.create.mockImplementation(
      () =>
        new Promise((yes) => {
          resolve = yes
        })
    )
    const wrapper = await open()
    await fillOrdinary(wrapper)
    await button(wrapper, '创建机构').trigger('click')
    await flushPromises()
    mocks.auth.identityVersion++
    resolve({ id: 'forbidden-result', name: '旧账号结果' })
    await flushPromises()
    expect(wrapper.text()).toContain('账号或目标机构已变化')
    expect(wrapper.text()).not.toContain('forbidden-result')
    expect(wrapper.findAll('input[type="password"]')).toHaveLength(0)
  })
})
