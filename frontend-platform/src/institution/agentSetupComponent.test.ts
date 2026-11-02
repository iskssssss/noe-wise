import { defineComponent, h, reactive } from 'vue'
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { ElButton, ElFormItem, ElInput, ElRadioGroup, ElSelect } from 'element-plus'
import { HttpClientError } from '@skillforge/shared/utils/http'
import InstitutionAgentSetup from '../views/platform/InstitutionAgentSetup.vue'

const mocks = vi.hoisted(() => ({
  auth: undefined as unknown as {
    identityVersion: number
    user: { sub: string; authorization_version: number; permissions: string[] }
  },
  grants: vi.fn(),
  definitions: vi.fn(),
  detail: vi.fn(),
  resolveOption: vi.fn(),
  authorize: vi.fn(),
  deploy: vi.fn(),
  grantReceipt: vi.fn(),
  deploymentReceipt: vi.fn(),
  confirm: vi.fn(),
  autoLoad: false,
  optionFailure: vi.fn()
}))
vi.mock('../stores/auth', () => ({ useAuthStore: () => mocks.auth }))
vi.mock('../api', () => ({
  api: {
    agentGrants: { create: mocks.authorize, receipt: mocks.grantReceipt },
    agentTemplates: { detail: mocks.detail },
    agentDeployments: {
      definitions: mocks.definitions,
      grants: mocks.grants,
      create: mocks.deploy,
      receipt: mocks.deploymentReceipt
    }
  }
}))
vi.mock('../api/entityOptions', () => ({
  createOperationOptions: (kind: string, operation: string) => ({
    load: vi.fn().mockResolvedValue([]),
    resolve: (id: string) => mocks.resolveOption(kind, operation, id)
  })
}))
vi.mock('@skillforge/shared/feedback', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@skillforge/shared/feedback')>()),
  confirm: mocks.confirm,
  message: { warning: vi.fn() }
}))
vi.mock('@skillforge/shared/admin', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@skillforge/shared/admin')>()
  return {
    ...actual,
    EntitySelect: {
      name: 'EntitySelect',
      props: ['modelValue', 'load', 'resolve'],
      emits: ['update:modelValue'],
      mounted() {
        if (mocks.autoLoad) {
          const load = (this as unknown as { load: (query: string) => Promise<unknown> }).load
          void load('').catch(mocks.optionFailure)
        }
      },
      template:
        '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />'
    }
  }
})
const grant = {
  grant_id: '21',
  target_tenant_id: '41',
  definition_id: '31',
  definition_name: '公共助手',
  actions: ['deploy', 'execute'],
  status: 'active',
  expires_at: null
}
const result = {
  tenant_id: '41',
  integration_id: 'integration-1',
  definition_id: '31',
  grant_id: '21',
  external_agent_ref: 'assistant',
  agent_id: '61',
  deployment_id: '51',
  definition_version: '1.0.0',
  deployment_revision: 1,
  ready: false,
  issues: [{ path: 'slot', code: 'missing', message: '请绑定机构资源' }]
}
const wrappers: VueWrapper[] = []
beforeEach(() => {
  vi.clearAllMocks()
  sessionStorage.clear()
  mocks.autoLoad = false
  mocks.auth = reactive({
    identityVersion: 1,
    user: {
      sub: 'user',
      authorization_version: 1,
      permissions: [
        'platform.agent_deployments.read',
        'platform.agent_deployments.create',
        'platform.agent_grants.read',
        'platform.agent_grants.create'
      ]
    }
  })
  mocks.grants.mockResolvedValue({ items: [grant, { ...grant, grant_id: '22' }] })
  mocks.definitions.mockResolvedValue({ items: [{ id: '31', name: '公共助手', version: '1.0.0' }] })
  mocks.detail.mockResolvedValue({
    definition_id: '31',
    owner_kind: 'platform',
    status: 'published',
    current_published_revision: 'v1',
    distribution_policy: 'AUTHORIZED_DEPLOY'
  })
  mocks.resolveOption.mockImplementation((_kind, _operation, id) =>
    Promise.resolve({ id, label: id })
  )
  mocks.authorize.mockResolvedValue(grant)
  mocks.grantReceipt.mockResolvedValue({ status: 'FOUND', result: grant })
  mocks.deploy.mockResolvedValue(result)
  mocks.confirm.mockResolvedValue(true)
})
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  document.body.innerHTML = ''
})
async function open(definitionId?: string, native = false) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      {
        path: '/test',
        component: defineComponent({
          setup: () => () =>
            h(InstitutionAgentSetup, {
              tenantId: '41',
              integrationId: native ? undefined : 'integration-1',
              definitionId
            })
        })
      }
    ]
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
function button(wrapper: VueWrapper, text: string) {
  const found = wrapper.findAllComponents(ElButton).find((item) => item.text() === text)
  if (!found) throw Error(text)
  return found
}
function field(wrapper: VueWrapper, label: string) {
  const found = wrapper.findAllComponents(ElFormItem).find((item) => item.props('label') === label)
  if (!found) throw Error(label)
  return found
}
async function fill(wrapper: VueWrapper, mode: 'existing' | 'new') {
  if (mode === 'new') {
    wrapper.findComponent(ElRadioGroup).vm.$emit('update:modelValue', 'new')
    await flushPromises()
  }
  field(wrapper, '公共定义')
    .findComponent({ name: 'EntitySelect' })
    .vm.$emit('update:modelValue', '31')
  await field(wrapper, '外部智能体标识').findComponent(ElInput).get('input').setValue('assistant')
  await flushPromises()
}

describe('添加智能体真实页面装配', () => {
  it('没有可用授权时默认选择新增授权，仍须用户确认才写入', async () => {
    mocks.grants.mockResolvedValueOnce({ items: [] })
    const wrapper = await open()
    expect(wrapper.findComponent(ElRadioGroup).props('modelValue')).toBe('new')
    expect(button(wrapper, '授权并部署').exists()).toBe(true)
    expect(window.dispatchEvent(new Event('beforeunload', { cancelable: true }))).toBe(true)
    expect(mocks.authorize).not.toHaveBeenCalled()
    expect(mocks.deploy).not.toHaveBeenCalled()
  })

  it('有可用授权时保留已有授权，目录读取失败时不推断授权为空', async () => {
    const existing = await open()
    expect(existing.findComponent(ElRadioGroup).props('modelValue')).toBe('existing')
    mocks.grants.mockRejectedValueOnce(new Error('读取失败'))
    const failed = await open()
    expect(failed.findComponent(ElRadioGroup).props('modelValue')).toBe('existing')
    expect(mocks.grants).toHaveBeenCalledTimes(2)
    expect(mocks.authorize).not.toHaveBeenCalled()
  })

  it('没有新增授权权限时不自动选择新增授权', async () => {
    mocks.auth.user.permissions = [
      'platform.agent_deployments.read',
      'platform.agent_deployments.create'
    ]
    mocks.grants.mockResolvedValueOnce({ items: [] })
    const wrapper = await open()
    expect(wrapper.findComponent(ElRadioGroup).props('modelValue')).toBe('existing')
    expect(mocks.authorize).not.toHaveBeenCalled()
  })

  it('目录迟到时不覆盖用户已选的授权方式', async () => {
    let finish!: (value: unknown) => void
    mocks.grants.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve
        })
    )
    const wrapper = await open()
    const radio = wrapper.findComponent(ElRadioGroup)
    radio.vm.$emit('update:modelValue', 'new')
    radio.vm.$emit('change', 'new')
    await flushPromises()
    radio.vm.$emit('update:modelValue', 'existing')
    radio.vm.$emit('change', 'existing')
    finish({ items: [] })
    await flushPromises()
    expect(radio.props('modelValue')).toBe('existing')
    expect(mocks.authorize).not.toHaveBeenCalled()
  })

  it('其他模板已有授权时，当前模板无授权仍直接进入新增授权', async () => {
    mocks.grants.mockResolvedValueOnce({ items: [{ ...grant, definition_id: '32' }] })
    const wrapper = await open('31')
    expect(wrapper.findComponent(ElRadioGroup).props('modelValue')).toBe('new')
    expect(
      field(wrapper, '公共定义').findComponent({ name: 'EntitySelect' }).props('modelValue')
    ).toBe('31')
    expect(mocks.authorize).not.toHaveBeenCalled()
    expect(mocks.deploy).not.toHaveBeenCalled()
  })

  it('当前模板已有可用授权时继续使用该授权', async () => {
    mocks.grants.mockResolvedValue({
      items: [{ ...grant, grant_id: '22', definition_id: '32' }, grant]
    })
    const wrapper = await open('31')
    expect(wrapper.findComponent(ElRadioGroup).props('modelValue')).toBe('existing')
    expect(field(wrapper, '部署与执行授权').findComponent(ElSelect).props('modelValue')).toBe('21')
    expect(mocks.authorize).not.toHaveBeenCalled()
  })

  it('模板入口没有授权时直接选择新增并核验原模板', async () => {
    mocks.grants.mockResolvedValueOnce({ items: [] })
    const wrapper = await open('31')
    expect(wrapper.findComponent(ElRadioGroup).props('modelValue')).toBe('new')
    expect(
      field(wrapper, '公共定义').findComponent({ name: 'EntitySelect' }).props('modelValue')
    ).toBe('31')
    expect(mocks.authorize).not.toHaveBeenCalled()
  })

  it('机构接入流程重进后核对原未知授权，不重复发起授权', async () => {
    mocks.authorize.mockRejectedValueOnce(
      new HttpClientError({ status: 0, userMessage: '结果未知' })
    )
    mocks.grantReceipt.mockResolvedValueOnce({ status: 'NOT_FOUND', result: null })
    const first = await open()
    await fill(first, 'new')
    await button(first, '授权并部署').trigger('click')
    await flushPromises()
    first.unmount()
    wrappers.splice(wrappers.indexOf(first), 1)
    const restored = await open()
    expect(restored.text()).toContain('原请求结果未知')
    await button(restored, '核对原请求').trigger('click')
    await flushPromises()
    expect(mocks.grantReceipt.mock.calls[0]).toEqual(mocks.authorize.mock.calls[0])
    expect(mocks.authorize).toHaveBeenCalledOnce()
    expect(restored.text()).toContain('原请求结果未知')
  })

  it('机构接入流程重进后保留已完成授权并继续部署', async () => {
    mocks.deploy.mockRejectedValueOnce(
      new HttpClientError({ status: 422, userMessage: '部署未通过' })
    )
    const first = await open()
    await fill(first, 'new')
    await button(first, '授权并部署').trigger('click')
    await flushPromises()
    first.unmount()
    wrappers.splice(wrappers.indexOf(first), 1)
    const restored = await open()
    expect(restored.text()).toContain('授权已成功')
    await button(restored, '继续部署').trigger('click')
    await flushPromises()
    expect(mocks.authorize).toHaveBeenCalledOnce()
    expect(mocks.deploy).toHaveBeenCalledTimes(2)
  })

  it('原生部署留空自动生成稳定标识，提交并读取同一回执', async () => {
    mocks.grants.mockResolvedValue({ items: [grant] })
    mocks.deploy.mockImplementation(async (input) => ({ ...result, ...input }))
    const wrapper = await open('31', true)
    expect(wrapper.text()).toContain('系统会自动生成实例标识')
    await button(wrapper, '部署智能体').trigger('click')
    await flushPromises()
    expect(mocks.deploy).toHaveBeenCalledOnce()
    const input = mocks.deploy.mock.calls[0]![0]
    expect(input.integration_id).toBeNull()
    expect(input.external_agent_ref).toMatch(/^agent-[0-9a-f-]{36}$/)
    expect(wrapper.text()).toContain('智能体已部署')
  })
  it('外部接入未填写真实标识时不创建授权或部署', async () => {
    const wrapper = await open('31')
    wrapper.findComponent(ElRadioGroup).vm.$emit('update:modelValue', 'new')
    await flushPromises()
    await button(wrapper, '授权并部署').trigger('click')
    await flushPromises()
    expect(mocks.authorize).not.toHaveBeenCalled()
    expect(mocks.deploy).not.toHaveBeenCalled()
  })
  it('原生部署明确拒绝后重试保留原自动标识与请求身份', async () => {
    mocks.grants.mockResolvedValue({ items: [grant] })
    mocks.deploy.mockRejectedValueOnce(
      new HttpClientError({ status: 422, userMessage: '部署未通过' })
    )
    mocks.deploy.mockImplementationOnce(async (input) => ({ ...result, ...input }))
    const wrapper = await open('31', true)
    await button(wrapper, '部署智能体').trigger('click')
    await flushPromises()
    await button(wrapper, '部署智能体').trigger('click')
    await flushPromises()
    expect(mocks.deploy).toHaveBeenCalledTimes(2)
    expect(mocks.deploy.mock.calls[1]).toEqual(mocks.deploy.mock.calls[0])
    expect(wrapper.text()).toContain('智能体已部署')
  })
  it('原生部署结果未知时保留生成的原标识，核对不重复部署', async () => {
    mocks.grants.mockResolvedValue({ items: [grant] })
    mocks.deploy.mockRejectedValueOnce(new HttpClientError({ status: 0, userMessage: '结果未知' }))
    const first = await open('31', true)
    await button(first, '部署智能体').trigger('click')
    await flushPromises()
    const input = mocks.deploy.mock.calls[0]![0]
    const key = mocks.deploy.mock.calls[0]![1]
    expect(input.external_agent_ref).toMatch(/^agent-[0-9a-f-]{36}$/)
    first.unmount()
    wrappers.splice(wrappers.indexOf(first), 1)
    mocks.deploymentReceipt.mockResolvedValue({ status: 'FOUND', result: { ...result, ...input } })
    const restored = await open('31', true)
    await button(restored, '核对原请求').trigger('click')
    await flushPromises()
    expect(mocks.deploymentReceipt.mock.calls[0]).toEqual([input, key])
    expect(mocks.deploy).toHaveBeenCalledOnce()
    expect(restored.text()).toContain('智能体已部署')
  })

  it('初始目录核验未完成时切换授权方式，完成后核验并预填新方式', async () => {
    let finish!: (value: unknown) => void
    mocks.detail.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve
        })
    )
    const wrapper = await open('31')
    wrapper.findComponent(ElRadioGroup).vm.$emit('update:modelValue', 'new')
    await flushPromises()
    finish({
      definition_id: '31',
      owner_kind: 'platform',
      status: 'published',
      current_published_revision: 'v1',
      distribution_policy: 'AUTHORIZED_DEPLOY'
    })
    await flushPromises()
    expect(
      field(wrapper, '公共定义').findComponent({ name: 'EntitySelect' }).props('modelValue')
    ).toBe('31')
  })
  it('模板上下文核验后预填定义，并在提交前重新核验已发布状态', async () => {
    const wrapper = await open('31')
    expect(
      field(wrapper, '公共定义').findComponent({ name: 'EntitySelect' }).props('modelValue')
    ).toBe('31')
    field(wrapper, '部署与执行授权').findComponent(ElSelect).vm.$emit('update:modelValue', '21')
    await field(wrapper, '外部智能体标识').findComponent(ElInput).get('input').setValue('assistant')
    mocks.detail.mockResolvedValueOnce({
      definition_id: '31',
      owner_kind: 'platform',
      status: 'deprecated',
      current_published_revision: 'v1',
      distribution_policy: 'AUTHORIZED_DEPLOY'
    })
    await button(wrapper, '部署智能体').trigger('click')
    await flushPromises()
    expect(mocks.deploy).not.toHaveBeenCalled()
    expect(mocks.authorize).not.toHaveBeenCalled()
  })

  it('授权确认后只读取一次最新模板与授权范围，然后执行授权和部署', async () => {
    const wrapper = await open('31')
    await fill(wrapper, 'new')
    mocks.detail.mockClear()
    mocks.resolveOption.mockClear()
    await button(wrapper, '授权并部署').trigger('click')
    await flushPromises()
    expect(mocks.confirm).toHaveBeenCalledOnce()
    expect(mocks.detail).toHaveBeenCalledOnce()
    expect(mocks.resolveOption).toHaveBeenCalledTimes(3)
    expect(mocks.confirm.mock.invocationCallOrder[0]).toBeLessThan(
      mocks.detail.mock.invocationCallOrder[0]!
    )
    expect(mocks.detail.mock.invocationCallOrder[0]).toBeLessThan(
      mocks.authorize.mock.invocationCallOrder[0]!
    )
    expect(mocks.authorize).toHaveBeenCalledOnce()
    expect(mocks.deploy).toHaveBeenCalledOnce()
  })

  it('确认期间模板下线时按确认后的状态拒绝授权', async () => {
    const wrapper = await open('31')
    await fill(wrapper, 'new')
    mocks.confirm.mockImplementationOnce(async () => {
      mocks.detail.mockResolvedValueOnce({
        definition_id: '31',
        owner_kind: 'platform',
        status: 'deprecated',
        current_published_revision: 'v1',
        distribution_policy: 'AUTHORIZED_DEPLOY'
      })
      return true
    })
    await button(wrapper, '授权并部署').trigger('click')
    await flushPromises()
    expect(mocks.authorize).not.toHaveBeenCalled()
    expect(mocks.deploy).not.toHaveBeenCalled()
  })

  it('模板不在机构可部署目录中时不预填或写入', async () => {
    mocks.definitions.mockResolvedValue({ items: [] })
    const wrapper = await open('31')
    expect(
      field(wrapper, '公共定义').findComponent({ name: 'EntitySelect' }).props('modelValue')
    ).toBe('')
    expect(wrapper.text()).toContain('重新核对模板与机构')
    expect(mocks.deploy).not.toHaveBeenCalled()
  })

  it('模板流程跨页面恢复未知授权，未找到回执不解除未知或重发写入', async () => {
    mocks.authorize.mockRejectedValueOnce(
      new HttpClientError({ status: 0, userMessage: '结果未知' })
    )
    mocks.grantReceipt.mockResolvedValueOnce({ status: 'NOT_FOUND', result: null })
    const first = await open('31')
    await fill(first, 'new')
    await button(first, '授权并部署').trigger('click')
    await flushPromises()
    first.unmount()
    wrappers.splice(wrappers.indexOf(first), 1)
    mocks.auth.user.permissions = ['platform.agent_grants.read']
    const restored = await open('31')
    expect(restored.text()).toContain('原请求结果未知')
    await button(restored, '核对原请求').trigger('click')
    await flushPromises()
    expect(restored.text()).toContain('原请求结果未知')
    expect(mocks.authorize).toHaveBeenCalledTimes(1)
    expect(mocks.grantReceipt.mock.calls[0]).toEqual(mocks.authorize.mock.calls[0])
    expect(mocks.deploy).not.toHaveBeenCalled()
  })

  it('授权成功后部署明确失败，跨页面恢复只继续原部署', async () => {
    mocks.deploy.mockRejectedValueOnce(
      new HttpClientError({ status: 422, userMessage: '部署未通过' })
    )
    const first = await open('31')
    await fill(first, 'new')
    await button(first, '授权并部署').trigger('click')
    await flushPromises()
    first.unmount()
    wrappers.splice(wrappers.indexOf(first), 1)
    const restored = await open('31')
    expect(restored.text()).toContain('授权已成功')
    expect(restored.text()).not.toContain('原请求结果未知')
    await button(restored, '继续部署').trigger('click')
    await flushPromises()
    expect(mocks.authorize).toHaveBeenCalledTimes(1)
    expect(mocks.deploy).toHaveBeenCalledTimes(2)
  })

  it('只读回执查询被拒绝时仍保留 UNKNOWN', async () => {
    mocks.authorize.mockRejectedValueOnce(
      new HttpClientError({ status: 0, userMessage: '结果未知' })
    )
    mocks.grantReceipt.mockRejectedValueOnce(
      new HttpClientError({ status: 403, userMessage: '无权读取' })
    )
    const wrapper = await open('31')
    await fill(wrapper, 'new')
    await button(wrapper, '授权并部署').trigger('click')
    await flushPromises()
    await button(wrapper, '核对原请求').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('原请求结果未知')
    expect(mocks.authorize).toHaveBeenCalledTimes(1)
    expect(mocks.deploy).not.toHaveBeenCalled()
  })

  it('新增授权前重新核对机构和定义的授权范围', async () => {
    const wrapper = await open('31')
    wrapper.findComponent(ElRadioGroup).vm.$emit('update:modelValue', 'new')
    await flushPromises()
    await field(wrapper, '外部智能体标识').findComponent(ElInput).get('input').setValue('assistant')
    mocks.resolveOption.mockImplementation((kind, operation, id) =>
      Promise.resolve(
        kind === 'definitions' && operation === 'platform.agent_grants.create'
          ? undefined
          : { id, label: id }
      )
    )
    await button(wrapper, '授权并部署').trigger('click')
    await flushPromises()
    expect(mocks.authorize).not.toHaveBeenCalled()
    expect(mocks.deploy).not.toHaveBeenCalled()
  })

  it('原请求无法保存时不派发写入，也不伪造未知结果', async () => {
    const wrapper = await open('31')
    await fill(wrapper, 'new')
    const set = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw Error('storage unavailable')
    })
    try {
      await button(wrapper, '授权并部署').trigger('click')
      await flushPromises()
      expect(mocks.authorize).not.toHaveBeenCalled()
      expect(wrapper.text()).not.toContain('原请求结果未知')
    } finally {
      set.mockRestore()
    }
  })
  it('选项首次读取与面板挂载复用同一目录请求，不在加载期间误报失败', async () => {
    let resolve!: (value: unknown) => void
    mocks.autoLoad = true
    mocks.definitions.mockImplementation(
      () =>
        new Promise((yes) => {
          resolve = yes
        })
    )
    await open()
    expect(mocks.definitions).toHaveBeenCalledTimes(1)
    expect(mocks.grants).toHaveBeenCalledTimes(1)
    resolve({ items: [{ id: '31', name: '公共助手', version: '1.0.0' }] })
    await flushPromises()
    expect(mocks.optionFailure).not.toHaveBeenCalled()
  })

  it('多条授权不自动选取，用户明确选择后按当前机构部署', async () => {
    const wrapper = await open()
    await fill(wrapper, 'existing')
    expect(field(wrapper, '部署与执行授权').findComponent(ElSelect).props('modelValue')).toBe('')
    await button(wrapper, '部署智能体').trigger('click')
    await flushPromises()
    expect(mocks.deploy).not.toHaveBeenCalled()
    field(wrapper, '部署与执行授权').findComponent(ElSelect).vm.$emit('update:modelValue', '21')
    await button(wrapper, '部署智能体').trigger('click')
    await flushPromises()
    expect(mocks.deploy).toHaveBeenCalledWith(
      expect.objectContaining({ tenant_id: '41', integration_id: 'integration-1', grant_id: '21' }),
      expect.any(String),
      { retryOnUnauthorized: false }
    )
    expect(mocks.authorize).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('等待机构完成本地配置')
    expect(wrapper.text()).toContain('请绑定机构资源')
  })

  it('所选授权失效后刷新不会静默切换到另一条授权链', async () => {
    const wrapper = await open()
    await fill(wrapper, 'existing')
    const select = field(wrapper, '部署与执行授权').findComponent(ElSelect)
    select.vm.$emit('update:modelValue', '21')
    await flushPromises()
    mocks.grants.mockResolvedValue({ items: [{ ...grant, grant_id: '22' }] })
    await button(wrapper, '刷新授权与定义').trigger('click')
    await flushPromises()
    expect(select.props('modelValue')).toBe('')
    expect(wrapper.text()).toContain('原授权不可用，请确认并选择当前有效授权')
    await button(wrapper, '刷新授权与定义').trigger('click')
    await flushPromises()
    expect(select.props('modelValue')).toBe('')
    await button(wrapper, '部署智能体').trigger('click')
    await flushPromises()
    expect(mocks.deploy).not.toHaveBeenCalled()
    select.vm.$emit('update:modelValue', '22')
    select.vm.$emit('change', '22')
    await flushPromises()
    mocks.deploy.mockResolvedValueOnce({ ...result, grant_id: '22' })
    await button(wrapper, '部署智能体').trigger('click')
    await flushPromises()
    expect(mocks.deploy).toHaveBeenCalledWith(
      expect.objectContaining({ grant_id: '22' }),
      expect.any(String),
      { retryOnUnauthorized: false }
    )
  })

  it('授权已成功、部署明确失败后，只继续部署且不再弹授权确认', async () => {
    mocks.deploy
      .mockRejectedValueOnce(new HttpClientError({ status: 422, userMessage: '部署未通过' }))
      .mockResolvedValue(result)
    const wrapper = await open()
    await fill(wrapper, 'new')
    await button(wrapper, '授权并部署').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('不会再次创建授权')
    await button(wrapper, '继续部署').trigger('click')
    await flushPromises()
    expect(mocks.authorize).toHaveBeenCalledTimes(1)
    expect(mocks.deploy).toHaveBeenCalledTimes(2)
    expect(mocks.deploy.mock.calls[1]).toEqual(mocks.deploy.mock.calls[0])
    expect(mocks.confirm).toHaveBeenCalledTimes(1)
  })

  it('未知授权只查询原键及正文，核对成功不会自动继续部署', async () => {
    mocks.authorize.mockRejectedValueOnce(
      new HttpClientError({ status: 0, userMessage: '结果未知' })
    )
    const wrapper = await open()
    await fill(wrapper, 'new')
    await button(wrapper, '授权并部署').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('原请求结果未知')
    expect(mocks.deploy).not.toHaveBeenCalled()
    await button(wrapper, '核对原请求').trigger('click')
    await flushPromises()
    expect(mocks.grantReceipt.mock.calls[0]).toEqual(mocks.authorize.mock.calls[0])
    expect(mocks.authorize).toHaveBeenCalledTimes(1)
    expect(mocks.deploy).not.toHaveBeenCalled()
    await button(wrapper, '继续部署').trigger('click')
    await flushPromises()
    expect(mocks.deploy).toHaveBeenCalledTimes(1)
  })
})
