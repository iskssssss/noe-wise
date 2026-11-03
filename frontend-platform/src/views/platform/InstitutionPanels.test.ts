import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { defineComponent, reactive } from 'vue'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { ElButton, ElDialog, ElForm, ElFormItem, ElInput, ElSelect } from 'element-plus'
import { confirmPageLeave } from '@skillforge/shared/admin'
import AgentGrantsPage from './AgentGrantsPage.vue'
import AgentDeploymentsPage from './AgentDeploymentsPage.vue'
import InstitutionCredentialsPage from './InstitutionCredentialsPage.vue'
import InstitutionIntegrationsPage from './InstitutionIntegrationsPage.vue'
import TenantSettingsPanel from './TenantSettingsPanel.vue'

const mocks = vi.hoisted(() => ({
  grants: vi.fn(),
  createGrant: vi.fn(),
  revokeGrant: vi.fn(),
  deployments: vi.fn(),
  definitions: vi.fn(),
  deploymentGrants: vi.fn(),
  createDeployment: vi.fn(),
  deploymentReceipt: vi.fn(),
  credentials: vi.fn(),
  issue: vi.fn(),
  rotateCredential: vi.fn(),
  revokeCredential: vi.fn(),
  mappings: vi.fn(),
  associate: vi.fn(),
  tenant: vi.fn(),
  members: vi.fn(),
  allowed: vi.fn(),
  confirm: vi.fn()
}))
vi.mock('../../api', () => ({
  foundationRequest: async () => ({ items: [], total: 0, allow_create: true }),
  api: {
    agentGrants: { list: mocks.grants, create: mocks.createGrant, revoke: mocks.revokeGrant },
    agentDeployments: {
      list: mocks.deployments,
      definitions: mocks.definitions,
      grants: mocks.deploymentGrants,
      create: mocks.createDeployment,
      receipt: mocks.deploymentReceipt
    },
    platformIntegration: {
      institutionCredentials: mocks.credentials,
      issueCredential: mocks.issue,
      rotateCredential: mocks.rotateCredential,
      revokeCredential: mocks.revokeCredential,
      institutions: mocks.mappings,
      associate: mocks.associate
    },
    admin: { getTenant: mocks.tenant, listMembers: mocks.members, plans: async () => [] }
  }
}))
vi.mock('../../stores/auth', () => ({
  useAuthStore: () => ({
    identityVersion: 1,
    user: { sub: 'operator-1', tenant_id: '0', permissions: [] }
  })
}))
vi.mock('../../composables/actionPermissions', () => ({
  useActionPermissions: () => ({
    allowed: mocks.allowed,
    checked: (_: string, work: () => unknown) => work()
  })
}))
vi.mock('@skillforge/shared/feedback', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@skillforge/shared/feedback')>()),
  confirm: mocks.confirm,
  message: { success: vi.fn(), warning: vi.fn() }
}))
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: {}, query: {} }),
  onBeforeRouteLeave: vi.fn(),
  onBeforeRouteUpdate: vi.fn(),
  RouterLink: { template: '<a><slot /></a>' }
}))

const EntitySelect = defineComponent({
  name: 'EntitySelect',
  inheritAttrs: false,
  props: ['modelValue', 'placeholder'],
  emits: ['update:modelValue'],
  template:
    '<input :placeholder="placeholder" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />'
})
let wrapper: VueWrapper | undefined
const global = { stubs: { EntitySelect, RouterLink: true } }
function button(label: string) {
  const value = wrapper!.findAllComponents(ElButton).find((item) => item.text() === label)
  if (!value) throw new Error('找不到操作：' + label)
  return value
}
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((yes) => {
    resolve = yes
  })
  return { promise, resolve }
}
beforeEach(() => {
  sessionStorage.clear()
  vi.clearAllMocks()
  mocks.allowed.mockReturnValue(true)
  mocks.confirm.mockResolvedValue(true)
  for (const mock of [
    mocks.grants,
    mocks.deployments,
    mocks.definitions,
    mocks.deploymentGrants,
    mocks.credentials,
    mocks.mappings,
    mocks.members
  ])
    mock.mockResolvedValue({ items: [], total: 0 })
  mocks.tenant.mockImplementation(async (id: string) => ({
    id,
    name: '机构 ' + id,
    slug: 'tenant-' + id,
    status: 'active'
  }))
})
afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  document.body.innerHTML = ''
})

it('固定机构的授权面板自动读取，切换后旧响应不能覆盖新机构事实', async () => {
  const old = deferred<{ items: unknown[]; total: number }>()
  mocks.grants.mockReturnValueOnce(old.promise).mockResolvedValue({ items: [], total: 0 })
  wrapper = mount(AgentGrantsPage, { props: { tenantId: '77', embedded: true }, global })
  await flushPromises()
  expect(mocks.grants).toHaveBeenCalledWith(
    '77',
    expect.objectContaining({ signal: expect.any(AbortSignal) })
  )
  expect(wrapper.find('input[placeholder="搜索机构名称"]').exists()).toBe(false)
  await wrapper.setProps({ tenantId: '88' })
  await flushPromises()
  old.resolve({ items: [{ grant_id: 'old', definition_name: '旧机构定义' }], total: 1 })
  await flushPromises()
  expect(mocks.grants).toHaveBeenLastCalledWith('88', expect.anything())
  expect(wrapper.text()).not.toContain('旧机构定义')
})

it('授权结果未知时目录刷新不解锁，显式核对复用原键和原正文', async () => {
  mocks.createGrant
    .mockRejectedValueOnce(Object.assign(new Error('网络断开'), { status: 0 }))
    .mockResolvedValueOnce({ grant_id: '101' })
  wrapper = mount(AgentGrantsPage, { props: { tenantId: '77', embedded: true }, global })
  await flushPromises()
  await wrapper.get('input[placeholder="搜索公共定义名称"]').setValue('91')
  await wrapper
    .findAllComponents(ElForm)
    .find((item) => item.props('model') !== undefined)!
    .trigger('submit')
  await flushPromises()
  expect(mocks.createGrant).toHaveBeenCalledTimes(1)
  expect(wrapper.text()).toContain('核对原请求')
  expect(await confirmPageLeave()).toBe(false)
  await button('刷新授权事实').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('核对原请求')
  expect(mocks.createGrant).toHaveBeenCalledTimes(1)
  await button('核对原请求').trigger('click')
  await flushPromises()
  expect(mocks.createGrant.mock.calls[1]).toEqual(mocks.createGrant.mock.calls[0])
  expect(wrapper.emitted('changed')).toBeDefined()
})

it.each(['创建', '撤销'])('授权%s成功但刷新失败时阻止重复操作，刷新后恢复', async (operation) => {
  const row = {
    grant_id: 'g1',
    definition_id: '91',
    definition_name: '助手',
    status: 'active',
    revision: 1
  }
  mocks.grants
    .mockResolvedValueOnce({ items: [row], total: 1 })
    .mockRejectedValueOnce(new Error('读取失败'))
    .mockResolvedValue({ items: [row], total: 1 })
  mocks.createGrant.mockResolvedValueOnce({ grant_id: 'g2' })
  mocks.revokeGrant.mockResolvedValueOnce({ grant_id: 'g1' })
  wrapper = mount(AgentGrantsPage, { props: { tenantId: '77', embedded: true }, global })
  await flushPromises()
  const form = wrapper.findAllComponents(ElForm).find((item) => item.props('model') !== undefined)!
  if (operation === '创建') {
    await wrapper.get('input[placeholder="搜索公共定义名称"]').setValue('91')
    await form.trigger('submit')
  } else {
    await button('撤销授权').trigger('click')
  }
  await flushPromises()
  expect(wrapper.text()).toContain('授权操作已完成，但列表尚未更新')
  expect(wrapper.text()).toContain('助手')
  expect(form.props('disabled')).toBe(true)
  expect(button('撤销授权').props('disabled')).toBe(true)
  await form.trigger('submit')
  await button('撤销授权').trigger('click')
  await flushPromises()
  expect(mocks.createGrant).toHaveBeenCalledTimes(operation === '创建' ? 1 : 0)
  expect(mocks.revokeGrant).toHaveBeenCalledTimes(operation === '撤销' ? 1 : 0)
  await button('刷新授权事实').trigger('click')
  await flushPromises()
  expect(wrapper.text()).not.toContain('授权操作已完成，但列表尚未更新')
  expect(form.props('disabled')).toBe(false)
  expect(button('撤销授权').props('disabled')).toBe(false)
})

it('固定机构部署直接读取部署、定义和授权，不重复要求选择机构', async () => {
  wrapper = mount(AgentDeploymentsPage, { props: { tenantId: '77', embedded: true }, global })
  await flushPromises()
  expect(mocks.deployments).toHaveBeenCalledWith(expect.anything(), '77')
  expect(mocks.definitions).toHaveBeenCalledWith(expect.anything(), '77')
  expect(mocks.deploymentGrants).toHaveBeenCalledWith(expect.anything(), '77')
  expect(wrapper.find('input[placeholder="搜索机构名称"]').exists()).toBe(false)
})

it('部署目录和配置同时读取失败时可一次恢复，且不会提交写入', async () => {
  mocks.deployments.mockRejectedValueOnce(new Error('部署读取失败'))
  mocks.definitions.mockRejectedValueOnce(new Error('定义读取失败'))
  mocks.deploymentGrants.mockRejectedValueOnce(new Error('授权读取失败'))
  wrapper = mount(AgentDeploymentsPage, { props: { tenantId: '77', embedded: true }, global })
  await flushPromises()
  const form = wrapper.findAllComponents(ElForm).find((item) => item.props('model') !== undefined)!
  expect(form.props('disabled')).toBe(true)
  expect(button('重新读取部署及配置').props('disabled')).toBe(false)
  await button('重新读取部署及配置').trigger('click')
  await flushPromises()
  expect(mocks.deployments).toHaveBeenCalledTimes(2)
  expect(mocks.definitions).toHaveBeenCalledTimes(2)
  expect(mocks.deploymentGrants).toHaveBeenCalledTimes(2)
  expect(mocks.createDeployment).not.toHaveBeenCalled()
  expect(form.props('disabled')).toBe(false)
  expect(wrapper.text()).not.toContain('重新读取部署及配置')
})

it('仅一个可部署定义时预选定义和授权，自动值不构成未保存输入', async () => {
  mocks.definitions.mockResolvedValue({
    items: [
      { id: '91', name: '助手', version: '1' },
      { id: '92', name: '未授权', version: '1' }
    ]
  })
  mocks.deploymentGrants.mockResolvedValue({
    items: [
      {
        grant_id: 'valid',
        definition_id: '91',
        definition_name: '助手',
        target_tenant_id: '77',
        status: 'active',
        actions: ['deploy', 'execute'],
        expires_at: null
      },
      {
        grant_id: 'other',
        definition_id: '92',
        definition_name: '未授权',
        target_tenant_id: '88',
        status: 'active',
        actions: ['deploy', 'execute'],
        expires_at: null
      }
    ]
  })
  wrapper = mount(AgentDeploymentsPage, { props: { tenantId: '77', embedded: true }, global })
  await flushPromises()
  const field = (label: string) =>
    wrapper!.findAllComponents(ElFormItem).find((item) => item.props('label') === label)!
  expect(field('公共定义').getComponent(ElSelect).props('modelValue')).toBe('91')
  expect(field('直接授权').getComponent(ElSelect).props('modelValue')).toBe('valid')
  expect(wrapper.text()).toContain('已预选唯一有有效授权的公共定义')
  expect(await confirmPageLeave()).toBe(true)
  expect(mocks.createDeployment).not.toHaveBeenCalled()
})

it.each(['新增可部署定义', '原授权失效'])(
  '目录刷新时%s，清除自动定义并保留其他输入',
  async (change) => {
    const grant = (id: string) => ({
      grant_id: 'g' + id,
      definition_id: id,
      definition_name: '助手',
      target_tenant_id: '77',
      status: 'active',
      actions: ['deploy', 'execute'],
      expires_at: null
    })
    mocks.definitions.mockResolvedValue({
      items: [
        { id: '91', name: '一', version: '1' },
        { id: '92', name: '二', version: '1' }
      ]
    })
    mocks.deploymentGrants.mockReset().mockResolvedValue({ items: [grant('91')] })
    wrapper = mount(AgentDeploymentsPage, { global })
    await wrapper.get('input[placeholder="搜索机构名称"]').setValue('77')
    await flushPromises()
    const field = (label: string) =>
      wrapper!.findAllComponents(ElFormItem).find((item) => item.props('label') === label)!
    expect(field('公共定义').getComponent(ElSelect).props('modelValue')).toBe('91')
    await wrapper.get('input[placeholder="原生部署，或选择目标机构接入"]').setValue('integration-1')
    await field('外部智能体标识').get('input').setValue('external-1')
    mocks.deploymentGrants.mockResolvedValue({
      items: change === '新增可部署定义' ? [grant('91'), grant('92')] : []
    })
    await wrapper
      .findAllComponents(ElForm)
      .find((item) => !item.props('model'))!
      .trigger('submit')
    await flushPromises()
    expect(field('公共定义').getComponent(ElSelect).props('modelValue')).toBe('')
    expect(field('直接授权').getComponent(ElSelect).props('modelValue')).toBe('')
    expect((field('外部智能体标识').get('input').element as HTMLInputElement).value).toBe(
      'external-1'
    )
    expect(wrapper.text()).not.toContain('已预选唯一有有效授权的公共定义')
    expect(mocks.createDeployment).not.toHaveBeenCalled()
  }
)

it('刷新目录时保留用户明确选择的可用定义', async () => {
  const grant = (id: string) => ({
    grant_id: 'g' + id,
    definition_id: id,
    definition_name: '助手',
    target_tenant_id: '77',
    status: 'active',
    actions: ['deploy', 'execute'],
    expires_at: null
  })
  mocks.definitions.mockResolvedValue({
    items: [
      { id: '91', name: '一', version: '1' },
      { id: '92', name: '二', version: '1' }
    ]
  })
  mocks.deploymentGrants.mockReset().mockResolvedValue({ items: [grant('91')] })
  wrapper = mount(AgentDeploymentsPage, { global })
  await wrapper.get('input[placeholder="搜索机构名称"]').setValue('77')
  await flushPromises()
  const definition = () =>
    wrapper!
      .findAllComponents(ElFormItem)
      .find((item) => item.props('label') === '公共定义')!
      .getComponent(ElSelect)
  definition().vm.$emit('change', '91')
  mocks.deploymentGrants.mockResolvedValue({ items: [grant('91'), grant('92')] })
  await wrapper
    .findAllComponents(ElForm)
    .find((item) => !item.props('model'))!
    .trigger('submit')
  await flushPromises()
  expect(definition().props('modelValue')).toBe('91')
  expect(wrapper.text()).not.toContain('已预选唯一有有效授权的公共定义')
})

it('仅有自动预选时切换机构不触发草稿确认，并按新机构重新预选', async () => {
  mocks.definitions.mockResolvedValue({
    items: [
      { id: '91', name: '一', version: '1' },
      { id: '92', name: '二', version: '1' }
    ]
  })
  mocks.deploymentGrants.mockImplementation(async (_options: unknown, tenant: string) => ({
    items: [
      {
        grant_id: tenant === '77' ? 'g1' : 'g2',
        definition_id: tenant === '77' ? '91' : '92',
        definition_name: '助手',
        target_tenant_id: tenant,
        status: 'active',
        actions: ['deploy', 'execute'],
        expires_at: null
      }
    ]
  }))
  wrapper = mount(AgentDeploymentsPage, { global })
  await wrapper.get('input[placeholder="搜索机构名称"]').setValue('77')
  await flushPromises()
  const definition = () =>
    wrapper!
      .findAllComponents(ElFormItem)
      .find((item) => item.props('label') === '公共定义')!
      .getComponent(ElSelect)
  expect(definition().props('modelValue')).toBe('91')
  await wrapper.get('input[placeholder="搜索机构名称"]').setValue('88')
  await flushPromises()
  expect(mocks.confirm).not.toHaveBeenCalled()
  expect(definition().props('modelValue')).toBe('92')
  expect(mocks.createDeployment).not.toHaveBeenCalled()
})

it('多个可部署定义不预选，迟到的授权读取不覆盖用户输入', async () => {
  const pending = deferred<{ items: unknown[] }>()
  mocks.definitions.mockResolvedValue({
    items: [
      { id: '91', name: '一', version: '1' },
      { id: '92', name: '二', version: '1' }
    ]
  })
  mocks.deploymentGrants.mockReturnValueOnce(pending.promise)
  wrapper = mount(AgentDeploymentsPage, { props: { tenantId: '77', embedded: true }, global })
  await flushPromises()
  const field = (label: string) =>
    wrapper!.findAllComponents(ElFormItem).find((item) => item.props('label') === label)!
  field('公共定义').getComponent(ElSelect).vm.$emit('update:modelValue', '92')
  pending.resolve({
    items: [
      {
        grant_id: 'g1',
        definition_id: '91',
        target_tenant_id: '77',
        status: 'active',
        actions: ['deploy', 'execute'],
        expires_at: null
      },
      {
        grant_id: 'g2',
        definition_id: '92',
        target_tenant_id: '77',
        status: 'active',
        actions: ['deploy', 'execute'],
        expires_at: null
      }
    ]
  })
  await flushPromises()
  expect(field('公共定义').getComponent(ElSelect).props('modelValue')).toBe('92')
  expect(mocks.createDeployment).not.toHaveBeenCalled()
  wrapper.unmount()
  mocks.deploymentGrants.mockResolvedValue({
    items: [
      {
        grant_id: 'g1',
        definition_id: '91',
        target_tenant_id: '77',
        status: 'active',
        actions: ['deploy', 'execute'],
        expires_at: null
      },
      {
        grant_id: 'g2',
        definition_id: '92',
        target_tenant_id: '77',
        status: 'active',
        actions: ['deploy', 'execute'],
        expires_at: null
      }
    ]
  })
  wrapper = mount(AgentDeploymentsPage, { props: { tenantId: '77', embedded: true }, global })
  await flushPromises()
  expect(
    wrapper
      .findAllComponents(ElFormItem)
      .find((item) => item.props('label') === '公共定义')!
      .getComponent(ElSelect)
      .props('modelValue')
  ).toBe('')
})

it('部署仅预选当前机构与定义的唯一完整有效授权，刷新、切换和重试重新核对', async () => {
  const grant = (id: string, extra = {}) => ({
    grant_id: id,
    definition_id: '91',
    definition_name: '助手',
    target_tenant_id: '77',
    status: 'active',
    actions: ['deploy', 'execute'],
    expires_at: null,
    ...extra
  })
  mocks.deploymentGrants.mockResolvedValue({
    items: [
      grant('valid'),
      grant('other', { target_tenant_id: '88' }),
      grant('expired', { expires_at: '2000-01-01T00:00:00Z' }),
      grant('incomplete', { actions: ['deploy'] })
    ]
  })
  wrapper = mount(AgentDeploymentsPage, { props: { tenantId: '77', embedded: true }, global })
  await flushPromises()
  const field = (label: string) =>
    wrapper!.findAllComponents(ElFormItem).find((item) => item.props('label') === label)!
  const definition = () => field('公共定义').getComponent(ElSelect)
  const selected = () => field('直接授权').getComponent(ElSelect).props('modelValue')
  definition().vm.$emit('update:modelValue', '91')
  await flushPromises()
  expect(selected()).toBe('valid')
  expect(wrapper.text()).toContain('已预选唯一有效授权')

  mocks.deploymentGrants.mockRejectedValueOnce(new Error('授权读取失败'))
  await wrapper.setProps({ tenantId: '88' })
  await flushPromises()
  expect(selected()).toBe('')
  expect(button('保存机构部署').props('disabled')).toBe(true)
  mocks.deploymentGrants.mockResolvedValueOnce({
    items: [grant('first', { target_tenant_id: '88' }), grant('second', { target_tenant_id: '88' })]
  })
  await button('重新读取部署及配置').trigger('click')
  await flushPromises()
  definition().vm.$emit('update:modelValue', '91')
  await flushPromises()
  expect(selected()).toBe('')
  field('直接授权').getComponent(ElSelect).vm.$emit('update:modelValue', 'second')
  definition().vm.$emit('update:modelValue', '92')
  await flushPromises()
  expect(selected()).toBe('')
})

it('自动预选不扩大创建权限，未知写入核对保留原参数与请求键', async () => {
  mocks.deploymentGrants.mockResolvedValue({
    items: [
      {
        grant_id: 'g1',
        definition_id: '91',
        definition_name: '助手',
        target_tenant_id: '77',
        status: 'active',
        actions: ['deploy', 'execute'],
        expires_at: null
      }
    ]
  })
  mocks.allowed.mockImplementation(
    (permission: string) => permission !== 'platform.agent_deployments.create'
  )
  wrapper = mount(AgentDeploymentsPage, { props: { tenantId: '77' }, global })
  await flushPromises()
  expect(mocks.deploymentGrants).not.toHaveBeenCalled()
  expect(wrapper.text()).not.toContain('保存机构部署')
  wrapper.unmount()
  mocks.allowed.mockReturnValue(true)
  mocks.createDeployment.mockRejectedValueOnce(new Error('网络断开'))
  mocks.deploymentReceipt
    .mockResolvedValueOnce({ status: 'NOT_FOUND', result: null })
    .mockResolvedValueOnce({
      status: 'FOUND',
      result: {
        deployment_id: 'd1',
        agent_id: 'a1',
        tenant_id: '77',
        definition_id: '91',
        grant_id: 'g1',
        integration_id: 'integration-1',
        external_agent_ref: 'assistant'
      }
    })
  wrapper = mount(AgentDeploymentsPage, { props: { tenantId: '77' }, global })
  await flushPromises()
  const field = (label: string) =>
    wrapper!.findAllComponents(ElFormItem).find((item) => item.props('label') === label)!
  field('公共定义').getComponent(ElSelect).vm.$emit('update:modelValue', '91')
  await wrapper.get('input[placeholder="原生部署，或选择目标机构接入"]').setValue('integration-1')
  await field('外部智能体标识').getComponent(ElInput).get('input').setValue('assistant')
  await flushPromises()
  await wrapper
    .findAllComponents(ElForm)
    .find((item) => item.props('model') !== undefined)!
    .trigger('submit')
  await flushPromises()
  expect(mocks.createDeployment).toHaveBeenCalledTimes(1)
  expect(mocks.createDeployment.mock.calls[0]![0]).toMatchObject({
    tenant_id: '77',
    definition_id: '91',
    grant_id: 'g1'
  })
  expect(wrapper.text()).toContain('核对原请求')
  await button('刷新稳定部署').trigger('click')
  await flushPromises()
  expect(mocks.createDeployment).toHaveBeenCalledTimes(1)
  await button('核对原请求').trigger('click')
  await flushPromises()
  expect(mocks.deploymentReceipt.mock.calls[0]).toEqual(
    mocks.createDeployment.mock.calls[0]!.slice(0, 2)
  )
  expect(mocks.createDeployment).toHaveBeenCalledTimes(1)
  expect(wrapper.text()).toContain('核对原请求')
  await button('核对原请求').trigger('click')
  await flushPromises()
  expect(mocks.deploymentReceipt).toHaveBeenCalledTimes(2)
  expect(mocks.createDeployment).toHaveBeenCalledTimes(1)
  expect(wrapper.text()).not.toContain('核对原请求')
})

it('部署请求未知后重新进入原机构，只读核对原请求并恢复', async () => {
  mocks.deploymentGrants.mockResolvedValue({
    items: [
      {
        grant_id: 'g1',
        definition_id: '91',
        target_tenant_id: '77',
        status: 'active',
        actions: ['deploy', 'execute'],
        expires_at: null
      }
    ]
  })
  mocks.createDeployment.mockRejectedValueOnce(new Error('网络中断'))
  wrapper = mount(AgentDeploymentsPage, { props: { tenantId: '77' }, global })
  await flushPromises()
  const definition = wrapper
    .findAllComponents(ElFormItem)
    .find((item) => item.props('label') === '公共定义')!
  definition.getComponent(ElSelect).vm.$emit('update:modelValue', '91')
  await flushPromises()
  await wrapper
    .findAllComponents(ElForm)
    .find((item) => item.props('model') !== undefined)!
    .trigger('submit')
  await flushPromises()
  const [input, key] = mocks.createDeployment.mock.calls[0]!
  expect(input.external_agent_ref).toBeNull()
  expect(mocks.createDeployment.mock.calls[0]![2]).toEqual({ retryOnUnauthorized: false })
  wrapper.unmount()
  wrapper = mount(AgentDeploymentsPage, { props: { tenantId: '77' }, global })
  await flushPromises()
  expect(wrapper.text()).toContain('机构 77 的部署结果待核对')
  mocks.deploymentReceipt.mockResolvedValueOnce({ status: 'NOT_FOUND', result: null })
  await button('核对原请求').trigger('click')
  await flushPromises()
  expect(mocks.deploymentReceipt).toHaveBeenCalledWith(input, key)
  expect(mocks.createDeployment).toHaveBeenCalledOnce()
  expect(wrapper.text()).toContain('核对原请求')
  mocks.deploymentReceipt.mockResolvedValueOnce({
    status: 'FOUND',
    result: { ...input, deployment_id: 'd1', agent_id: 'a1', external_agent_ref: 'agent-server' }
  })
  await button('核对原请求').trigger('click')
  await flushPromises()
  expect(wrapper.text()).not.toContain('核对原请求')
  expect(sessionStorage.length).toBe(0)
  expect(mocks.createDeployment).toHaveBeenCalledOnce()
})

it('创建成功但记录清除失败时仍阻止重复创建，并可再次核对', async () => {
  mocks.deploymentGrants.mockResolvedValue({
    items: [
      {
        grant_id: 'g1',
        definition_id: '91',
        target_tenant_id: '77',
        status: 'active',
        actions: ['deploy', 'execute'],
        expires_at: null
      }
    ]
  })
  mocks.createDeployment.mockImplementationOnce(async (input) => ({
    ...input,
    deployment_id: 'd1',
    agent_id: 'a1',
    external_agent_ref: 'agent-server'
  }))
  wrapper = mount(AgentDeploymentsPage, { props: { tenantId: '77' }, global })
  await flushPromises()
  wrapper
    .findAllComponents(ElFormItem)
    .find((item) => item.props('label') === '公共定义')!
    .getComponent(ElSelect)
    .vm.$emit('update:modelValue', '91')
  await flushPromises()
  const remove = vi.spyOn(Storage.prototype, 'removeItem').mockImplementationOnce(() => {
    throw Error('存储不可用')
  })
  await wrapper
    .findAllComponents(ElForm)
    .find((item) => item.props('model') !== undefined)!
    .trigger('submit')
  await flushPromises()
  expect(wrapper.text()).toContain('核对原请求')
  const [input] = mocks.createDeployment.mock.calls[0]!
  remove.mockRestore()
  mocks.deploymentReceipt.mockResolvedValueOnce({
    status: 'FOUND',
    result: { ...input, deployment_id: 'd1', agent_id: 'a1', external_agent_ref: 'agent-server' }
  })
  await button('核对原请求').trigger('click')
  await flushPromises()
  expect(wrapper.text()).not.toContain('核对原请求')
  expect(wrapper.text()).not.toContain('无法读取或保存原请求')
  expect(mocks.createDeployment).toHaveBeenCalledOnce()
})

it('恢复记录属于其他机构时不核对或创建新部署', async () => {
  const key = `noewise:platform-deployment-create:${encodeURIComponent(JSON.stringify(['0', 'operator-1']))}`
  sessionStorage.setItem(
    key,
    JSON.stringify({
      key: 'original-request',
      input: {
        tenant_id: '77',
        definition_id: '91',
        grant_id: 'g1',
        integration_id: null,
        external_agent_ref: null,
        resource_bindings: [],
        delegation_bindings: [],
        parameters: {}
      }
    })
  )
  wrapper = mount(AgentDeploymentsPage, { props: { tenantId: '88' }, global })
  await flushPromises()
  expect(wrapper.text()).toContain('机构 77 的部署结果待核对')
  await button('核对原请求').trigger('click')
  await flushPromises()
  expect(mocks.deploymentReceipt).not.toHaveBeenCalled()
  expect(mocks.createDeployment).not.toHaveBeenCalled()
  expect(sessionStorage.getItem(key)).not.toBeNull()
})

it('无法保存原请求时不发送部署', async () => {
  mocks.deploymentGrants.mockResolvedValue({
    items: [
      {
        grant_id: 'g1',
        definition_id: '91',
        target_tenant_id: '77',
        status: 'active',
        actions: ['deploy', 'execute'],
        expires_at: null
      }
    ]
  })
  wrapper = mount(AgentDeploymentsPage, { props: { tenantId: '77' }, global })
  await flushPromises()
  wrapper
    .findAllComponents(ElFormItem)
    .find((item) => item.props('label') === '公共定义')!
    .getComponent(ElSelect)
    .vm.$emit('update:modelValue', '91')
  await flushPromises()
  const storage = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
    throw Error('存储不可用')
  })
  await wrapper
    .findAllComponents(ElForm)
    .find((item) => item.props('model') !== undefined)!
    .trigger('submit')
  await flushPromises()
  expect(mocks.createDeployment).not.toHaveBeenCalled()
  expect(wrapper.text()).toContain('无法读取或保存原请求')
  storage.mockRestore()
})

it('原生部署无需输入标识并将生成交给服务端', async () => {
  mocks.deploymentGrants.mockResolvedValue({
    items: [
      {
        grant_id: 'g1',
        definition_id: '91',
        target_tenant_id: '77',
        status: 'active',
        actions: ['deploy', 'execute'],
        expires_at: null
      }
    ]
  })
  mocks.createDeployment.mockImplementationOnce(async (input) => ({
    ...input,
    deployment_id: 'd1',
    agent_id: 'a1',
    external_agent_ref: input.external_agent_ref ?? 'agent-server'
  }))
  wrapper = mount(AgentDeploymentsPage, { props: { tenantId: '77' }, global })
  await flushPromises()
  const field = (label: string) =>
    wrapper!.findAllComponents(ElFormItem).find((item) => item.props('label') === label)!
  field('公共定义').getComponent(ElSelect).vm.$emit('update:modelValue', '91')
  await flushPromises()
  expect(
    wrapper.findAllComponents(ElFormItem).some((item) => item.props('label') === '外部智能体标识')
  ).toBe(false)
  await wrapper
    .findAllComponents(ElForm)
    .find((item) => item.props('model') !== undefined)!
    .trigger('submit')
  await flushPromises()
  expect(mocks.createDeployment).toHaveBeenCalledOnce()
  expect(mocks.createDeployment.mock.calls[0]![0]).toMatchObject({
    integration_id: null,
    external_agent_ref: null
  })
})

it('选择外部接入后必须填写真实智能体标识', async () => {
  mocks.deploymentGrants.mockResolvedValue({
    items: [
      {
        grant_id: 'g1',
        definition_id: '91',
        target_tenant_id: '77',
        status: 'active',
        actions: ['deploy', 'execute'],
        expires_at: null
      }
    ]
  })
  wrapper = mount(AgentDeploymentsPage, { props: { tenantId: '77' }, global })
  await flushPromises()
  const definition = wrapper
    .findAllComponents(ElFormItem)
    .find((item) => item.props('label') === '公共定义')!
  definition.getComponent(ElSelect).vm.$emit('update:modelValue', '91')
  await wrapper.get('input[placeholder="原生部署，或选择目标机构接入"]').setValue('integration-1')
  await flushPromises()
  await wrapper
    .findAllComponents(ElForm)
    .find((item) => item.props('model') !== undefined)!
    .trigger('submit')
  await flushPromises()
  expect(mocks.createDeployment).not.toHaveBeenCalled()
})

it('部署已保存但列表刷新失败时阻止再次创建，刷新成功后恢复', async () => {
  mocks.deploymentGrants.mockResolvedValue({
    items: [
      {
        grant_id: 'g1',
        definition_id: '91',
        definition_name: '助手',
        target_tenant_id: '77',
        status: 'active',
        actions: ['deploy', 'execute'],
        expires_at: null
      }
    ]
  })
  mocks.createDeployment.mockImplementationOnce(async (input) => ({
    ...input,
    deployment_id: 'd1',
    agent_id: 'a1',
    external_agent_ref: input.external_agent_ref ?? 'agent-server'
  }))
  wrapper = mount(AgentDeploymentsPage, { props: { tenantId: '77' }, global })
  await flushPromises()
  const field = (label: string) =>
    wrapper!.findAllComponents(ElFormItem).find((item) => item.props('label') === label)!
  field('公共定义').getComponent(ElSelect).vm.$emit('update:modelValue', '91')
  await wrapper.get('input[placeholder="原生部署，或选择目标机构接入"]').setValue('integration-1')
  await field('外部智能体标识').getComponent(ElInput).get('input').setValue('assistant')
  await flushPromises()
  mocks.deployments.mockRejectedValueOnce(new Error('目录暂时不可用'))
  const form = wrapper.findAllComponents(ElForm).find((item) => item.props('model') !== undefined)!
  await form.trigger('submit')
  await flushPromises()
  expect(mocks.createDeployment).toHaveBeenCalledTimes(1)
  expect(wrapper.text()).toContain('部署已保存，但列表尚未更新')
  expect(form.props('disabled')).toBe(true)
  await form.trigger('submit')
  await flushPromises()
  expect(mocks.createDeployment).toHaveBeenCalledTimes(1)
  await button('刷新稳定部署').trigger('click')
  await flushPromises()
  expect(wrapper.text()).not.toContain('部署已保存，但列表尚未更新')
  expect(form.props('disabled')).toBe(false)
})
it('重新查询授权时清除失效预选，出现多条授权时要求明确选择', async () => {
  const grant = (id: string) => ({
    grant_id: id,
    definition_id: '91',
    definition_name: '助手',
    target_tenant_id: '77',
    status: 'active',
    actions: ['deploy', 'execute'],
    expires_at: null
  })
  mocks.deploymentGrants.mockResolvedValueOnce({ items: [grant('g1')] })
  wrapper = mount(AgentDeploymentsPage, { global })
  await wrapper.get('input[placeholder="搜索机构名称"]').setValue('77')
  const query = () =>
    wrapper!
      .findAllComponents(ElForm)
      .find((item) => !item.props('model'))!
      .trigger('submit')
  await flushPromises()
  const field = (label: string) =>
    wrapper!.findAllComponents(ElFormItem).find((item) => item.props('label') === label)!
  const selected = () => field('直接授权').getComponent(ElSelect).props('modelValue')
  field('公共定义').getComponent(ElSelect).vm.$emit('update:modelValue', '91')
  await flushPromises()
  expect(selected()).toBe('g1')
  mocks.deploymentGrants.mockResolvedValueOnce({ items: [grant('g1'), grant('g2')] })
  await query()
  await flushPromises()
  expect(selected()).toBe('')
  field('直接授权').getComponent(ElSelect).vm.$emit('update:modelValue', 'g2')
  mocks.deploymentGrants.mockResolvedValueOnce({ items: [] })
  await query()
  await flushPromises()
  expect(selected()).toBe('')
})

it('固定机构和接入的凭证面板自动查询，切换机构清除旧执行域', async () => {
  wrapper = mount(InstitutionCredentialsPage, {
    props: { tenantId: '77', integrationId: 'i-1', embedded: true },
    global
  })
  await flushPromises()
  expect(mocks.credentials).toHaveBeenLastCalledWith(
    { page: 1, tenant_id: '77', integration_id: 'i-1' },
    expect.anything()
  )
  expect(wrapper.find('input[placeholder="搜索机构名称"]').exists()).toBe(false)
  expect(wrapper.find('input[placeholder="选择机构接入"]').exists()).toBe(false)
  await wrapper.setProps({ tenantId: '88', integrationId: 'i-2' })
  await flushPromises()
  expect(mocks.credentials).toHaveBeenLastCalledWith(
    { page: 1, tenant_id: '88', integration_id: 'i-2' },
    expect.anything()
  )
  await wrapper.setProps({ tenantId: '99', integrationId: undefined })
  await flushPromises()
  expect(wrapper.text()).not.toContain('88 / i-2')
})

it('签发确认列出目标与所选原始权限，取消保留输入，切换目标不发送旧请求', async () => {
  wrapper = mount(InstitutionCredentialsPage, {
    props: { tenantId: '77', integrationId: 'i-1', embedded: true },
    global
  })
  await flushPromises()
  const selected = () =>
    wrapper!
      .findAllComponents(ElFormItem)
      .find((item) => item.props('label') === '执行权限')!
      .getComponent(ElSelect)
  const submit = () =>
    wrapper!
      .findAllComponents(ElForm)
      .find((item) => item.props('model') !== undefined)!
      .trigger('submit')
  selected().vm.$emit('update:modelValue', ['sessions.read', 'groups.messages.write'])
  await flushPromises()
  mocks.confirm.mockResolvedValueOnce(false)
  await submit()
  await flushPromises()
  expect(mocks.confirm).toHaveBeenCalledWith(
    expect.objectContaining({ content: expect.stringContaining('机构 77 的接入 i-1') })
  )
  const content = mocks.confirm.mock.calls[0]![0].content as string
  expect(content).toContain('读取会话（sessions.read）')
  expect(content).toContain('记录群消息（groups.messages.write）')
  expect(selected().props('modelValue')).toEqual(['sessions.read', 'groups.messages.write'])
  expect(mocks.issue).not.toHaveBeenCalled()
  const pending = deferred<boolean>()
  mocks.confirm.mockReturnValueOnce(pending.promise)
  await submit()
  await flushPromises()
  await wrapper.setProps({ tenantId: '88', integrationId: 'i-2' })
  pending.resolve(true)
  await flushPromises()
  expect(mocks.issue).not.toHaveBeenCalled()
})

it.each(['签发', '轮换', '撤销'])('凭证%s成功但目录刷新失败时阻止重复写入', async (operation) => {
  const row = {
    id: 'p1',
    tenant_id: '77',
    integration_id: 'i-1',
    principal_id: 'p1',
    api_key_prefix: 'prefix',
    status: 'active',
    credential_version: 1,
    scopes: ['sessions.read']
  }
  mocks.credentials
    .mockResolvedValueOnce({ items: [row], total: 1 })
    .mockRejectedValueOnce(new Error('目录读取失败'))
    .mockResolvedValue({ items: [row], total: 1 })
  const receipt = { principal: row, api_key: 'once-key', signing_secret: 'once-secret' }
  mocks.issue.mockResolvedValue(receipt)
  mocks.rotateCredential.mockResolvedValue(receipt)
  mocks.revokeCredential.mockResolvedValue({})
  wrapper = mount(InstitutionCredentialsPage, {
    props: { tenantId: '77', integrationId: 'i-1', embedded: true },
    global
  })
  await flushPromises()
  const form = wrapper.findAllComponents(ElForm).find((item) => item.props('model') !== undefined)!
  if (operation === '签发') {
    wrapper
      .findAllComponents(ElFormItem)
      .find((item) => item.props('label') === '执行权限')!
      .getComponent(ElSelect)
      .vm.$emit('update:modelValue', ['sessions.read'])
    await flushPromises()
    await form.trigger('submit')
  } else await button(operation).trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('凭证操作已完成，但安全目录尚未更新')
  expect(button('签发执行凭证').props('disabled')).toBe(true)
  expect(button('轮换').props('disabled')).toBe(true)
  expect(button('撤销').props('disabled')).toBe(true)
  if (operation !== '撤销') expect(wrapper.findComponent(ElDialog).props('modelValue')).toBe(true)
  await form.trigger('submit')
  await button('轮换').trigger('click')
  await button('撤销').trigger('click')
  await flushPromises()
  expect(mocks.issue).toHaveBeenCalledTimes(operation === '签发' ? 1 : 0)
  expect(mocks.rotateCredential).toHaveBeenCalledTimes(operation === '轮换' ? 1 : 0)
  expect(mocks.revokeCredential).toHaveBeenCalledTimes(operation === '撤销' ? 1 : 0)
  await button('刷新安全目录').trigger('click')
  await flushPromises()
  expect(wrapper.text()).not.toContain('凭证操作已完成，但安全目录尚未更新')
  expect(button('轮换').props('disabled')).toBe(false)
  if (operation !== '撤销') expect(wrapper.findComponent(ElDialog).props('modelValue')).toBe(true)
})

it('凭证权限不足不签发，未知结果使用原权限、目标和命令键核对', async () => {
  mocks.allowed.mockImplementation(
    (permission: string) => permission !== 'platform.institution_credentials.issue'
  )
  wrapper = mount(InstitutionCredentialsPage, {
    props: { tenantId: '77', integrationId: 'i-1' },
    global
  })
  await flushPromises()
  expect(wrapper.text()).not.toContain('签发执行凭证')
  expect(mocks.issue).not.toHaveBeenCalled()
  wrapper.unmount()
  mocks.allowed.mockReturnValue(true)
  mocks.issue
    .mockRejectedValueOnce(Object.assign(new Error('网络断开'), { status: 0 }))
    .mockResolvedValueOnce({ principal: {} })
  wrapper = mount(InstitutionCredentialsPage, {
    props: { tenantId: '77', integrationId: 'i-1' },
    global
  })
  await flushPromises()
  wrapper
    .findAllComponents(ElFormItem)
    .find((item) => item.props('label') === '执行权限')!
    .getComponent(ElSelect)
    .vm.$emit('update:modelValue', ['sessions.read', 'usage.read'])
  await flushPromises()
  await wrapper
    .findAllComponents(ElForm)
    .find((item) => item.props('model') !== undefined)!
    .trigger('submit')
  await flushPromises()
  expect(mocks.issue.mock.calls[0]![0]).toEqual({
    tenant_id: '77',
    integration_id: 'i-1',
    scopes: ['sessions.read', 'usage.read']
  })
  expect(wrapper.text()).toContain('核对原请求')
  await button('核对原请求').trigger('click')
  await flushPromises()
  expect(mocks.issue.mock.calls[1]).toEqual(mocks.issue.mock.calls[0])
  expect(wrapper.text()).toContain('没有可再次交付的秘密')
})

it('凭证签发回包晚于目标变化时不把原秘密交给新机构面板', async () => {
  const old = deferred<{ principal: object; api_key: string; signing_secret: string }>()
  mocks.issue.mockReturnValueOnce(old.promise)
  wrapper = mount(InstitutionCredentialsPage, {
    props: { tenantId: '77', integrationId: 'i-1', embedded: true },
    global
  })
  await flushPromises()
  const field = wrapper
    .findAllComponents(ElFormItem)
    .find((item) => item.props('label') === '执行权限')!
  field.getComponent(ElSelect).vm.$emit('update:modelValue', ['sessions.read'])
  await flushPromises()
  await wrapper
    .findAllComponents(ElForm)
    .find((item) => item.props('model') !== undefined)!
    .trigger('submit')
  await flushPromises()
  expect(mocks.issue).toHaveBeenCalledTimes(1)
  await wrapper.setProps({ tenantId: '88', integrationId: 'i-2' })
  await flushPromises()
  old.resolve({
    principal: {},
    api_key: 'fake-original-key',
    signing_secret: 'fake-original-secret'
  })
  await flushPromises()
  expect(wrapper.emitted('changed')).toBeUndefined()
  expect(
    wrapper.findAll('input').map((item) => (item.element as HTMLInputElement).value)
  ).not.toContain('fake-original-key')
})

it.each(['撤销签发权限', '切换身份'] as const)(
  '同身份快照刷新保留凭证交付与离开保护，%s后清除秘密',
  async (change) => {
    const auth = reactive({
      identityVersion: 1,
      user: {
        permissions: [
          'platform.institution_credentials.issue',
          'platform.institution_credentials.rotate'
        ]
      }
    })
    mocks.allowed.mockImplementation(
      (code: string) => auth.identityVersion === 1 && auth.user.permissions.includes(code)
    )
    mocks.issue.mockResolvedValueOnce({
      principal: {},
      api_key: 'fake-handoff-key',
      signing_secret: 'fake-handoff-secret'
    })
    wrapper = mount(InstitutionCredentialsPage, {
      props: { tenantId: '77', integrationId: 'i-1', embedded: true },
      global
    })
    await flushPromises()
    wrapper
      .findAllComponents(ElFormItem)
      .find((item) => item.props('label') === '执行权限')!
      .getComponent(ElSelect)
      .vm.$emit('update:modelValue', ['usage.read'])
    await flushPromises()
    await wrapper
      .findAllComponents(ElForm)
      .find((item) => item.props('model') !== undefined)!
      .trigger('submit')
    await flushPromises()
    expect(wrapper.getComponent(ElDialog).props('modelValue')).toBe(true)
    expect(wrapper.text()).not.toContain('请至少选择一项执行权限')

    auth.user = { permissions: [...auth.user.permissions] }
    await flushPromises()
    expect(wrapper.getComponent(ElDialog).props('modelValue')).toBe(true)
    mocks.confirm.mockResolvedValueOnce(false)
    expect(await confirmPageLeave()).toBe(false)
    expect(mocks.issue).toHaveBeenCalledTimes(1)

    if (change === '撤销签发权限')
      auth.user = { permissions: ['platform.institution_credentials.rotate'] }
    else auth.identityVersion++
    await flushPromises()
    expect(wrapper.getComponent(ElDialog).props('modelValue')).toBe(false)
    expect(
      wrapper.findAll('input').map((item) => (item.element as HTMLInputElement).value)
    ).not.toContain('fake-handoff-key')
  }
)

it('关联已有机构直接携带工作台目标，未要求再次选择已有机构', async () => {
  wrapper = mount(InstitutionIntegrationsPage, {
    props: { tenantId: '77', embedded: true },
    global
  })
  await flushPromises()
  expect(mocks.mappings).toHaveBeenCalledWith(
    { page: 1, tenant_id: '77', namespace: '' },
    expect.anything()
  )
  ;(wrapper.vm as unknown as { beginAssociate: () => void }).beginAssociate()
  await flushPromises()
  expect(
    wrapper.findAllComponents(ElFormItem).some((item) => item.props('label') === '已有机构')
  ).toBe(false)
  expect(wrapper.find('input[placeholder="搜索机构名称"]').exists()).toBe(false)
})

it('机构设置使用传入目标，目标切换后重新读取成员与资料', async () => {
  wrapper = mount(TenantSettingsPanel, { props: { tenantId: '77', embedded: true }, global })
  await flushPromises()
  expect(mocks.tenant).toHaveBeenCalledWith('77', expect.anything())
  expect(mocks.members).toHaveBeenCalledWith('77', expect.anything(), expect.anything())
  await wrapper.setProps({ tenantId: '88' })
  await flushPromises()
  expect(mocks.tenant).toHaveBeenLastCalledWith('88', expect.anything())
  expect(mocks.members).toHaveBeenLastCalledWith('88', expect.anything(), expect.anything())
  expect(
    wrapper
      .findAllComponents(ElFormItem)
      .find((item) => item.props('label') === '机构名称')!
      .get('input').element
  ).toHaveProperty('value', '机构 88')
})
