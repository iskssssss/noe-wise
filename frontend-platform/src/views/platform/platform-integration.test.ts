import { defineComponent, reactive, type Component } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { ElButton, ElFormItem, ElInput, ElSelect } from 'element-plus'
import ManagementPrincipalsPage from './ManagementPrincipalsPage.vue'
import InstitutionIntegrationsPage from './InstitutionIntegrationsPage.vue'
import { EntitySelect } from '@skillforge/shared/admin'
import InstitutionCredentialsPage from './InstitutionCredentialsPage.vue'
const mocks = vi.hoisted(() => ({
  auth: undefined as unknown as {
    identityVersion: number
    user: { sub: string; authorization_version: number; permissions: string[] }
  },
  managementList: vi.fn(),
  options: vi.fn(),
  institutions: vi.fn(),
  provision: vi.fn(),
  create: vi.fn(),
  rotate: vi.fn(),
  revoke: vi.fn(),
  credentials: vi.fn(),
  issue: vi.fn(),
  rotateCredential: vi.fn(),
  revokeCredential: vi.fn(),
  confirm: vi.fn()
}))
vi.mock('../../stores/auth', () => ({ useAuthStore: () => mocks.auth }))
vi.mock('../../api', () => ({
  foundationRequest: mocks.options,
  api: {
    platformIntegration: {
      institutions: mocks.institutions,
      provision: mocks.provision,
      managementPrincipals: mocks.managementList,
      createManagementPrincipal: mocks.create,
      rotateManagementPrincipal: mocks.rotate,
      revokeManagementPrincipal: mocks.revoke,
      institutionCredentials: mocks.credentials,
      issueCredential: mocks.issue,
      rotateCredential: mocks.rotateCredential,
      revokeCredential: mocks.revokeCredential
    }
  }
}))
vi.mock('@skillforge/shared/feedback', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@skillforge/shared/feedback')>()),
  message: { success: vi.fn() },
  confirm: mocks.confirm
}))
const wrappers: VueWrapper[] = []
const principal = {
  principal_id: '9223372036854775807',
  name: '开通机器',
  status: 'active',
  revision: 7,
  credential_version: 3,
  scopes: ['platform.institutions.provision'],
  target_tenant_ids: [],
  target_definition_ids: [],
  target_namespaces: ['clinic'],
  created_at: '',
  updated_at: ''
}
beforeEach(() => {
  vi.clearAllMocks()
  mocks.auth = reactive({
    identityVersion: 1,
    user: {
      sub: 'actor-1',
      authorization_version: 1,
      permissions: [
        'platform.management_principals.read',
        'platform.management_principals.create',
        'platform.management_principals.rotate',
        'platform.institutions.provision',
        'platform.institution_credentials.read',
        'platform.institution_credentials.issue',
        'platform.institution_credentials.rotate'
      ]
    }
  })
  mocks.options.mockResolvedValue({
    items: [],
    total: 0,
    page: 1,
    page_size: 200,
    allow_create: true
  })
  mocks.institutions.mockResolvedValue({ items: [], total: 0, page: 1, page_size: 20 })
  mocks.managementList.mockResolvedValue({ items: [principal], total: 1, page: 1, page_size: 20 })
  mocks.credentials.mockResolvedValue({
    items: [
      {
        id: '9223372036854775806',
        principal_id: 'sp-stable',
        tenant_id: 't1',
        integration_id: 'i1',
        api_key_prefix: 'prefix',
        scopes: ['sessions.write'],
        status: 'active',
        credential_version: 4
      }
    ],
    total: 1,
    page: 1,
    page_size: 20
  })
  mocks.confirm.mockResolvedValue(true)
})
afterEach(() => {
  wrappers.forEach((wrapper) => wrapper.unmount())
  wrappers.length = 0
  document.body.innerHTML = ''
})
/** 经真实 RouterView 装配，复用页面离开保护和 Element Plus 表单。 */
async function open(component: Component, path: string) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: path.split('?')[0]!, component }]
  })
  await router.push(path)
  await router.isReady()
  const wrapper = mount(defineComponent({ template: '<RouterView />' }), {
    attachTo: document.body,
    global: { plugins: [router] }
  })
  wrappers.push(wrapper)
  await flushPromises()
  return wrapper
}
/** 按可见动作定位按钮，避免依赖私有组件实例。 */
function button(wrapper: VueWrapper, text: string) {
  const value = wrapper.findAllComponents(ElButton).find((item) => item.text() === text)
  if (!value) throw Error(text)
  return value
}
/** 标签定位同时校验输入字段属于其正确业务表单。 */
function field(wrapper: VueWrapper, label: string) {
  const value = wrapper.findAllComponents(ElFormItem).find((item) => item.props('label') === label)
  if (!value) throw Error(label)
  return value
}
describe('平台直管身份与秘密交付', () => {
  it('只读角色直接触发表单也不能创建，撤销权限后确认回调不能轮换', async () => {
    mocks.auth.user.permissions = ['platform.management_principals.read']
    const wrapper = await open(ManagementPrincipalsPage, '/platform/management-principals')
    expect(wrapper.findAll('button').some((item) => item.text() === '创建管理身份')).toBe(false)
    expect(mocks.create).not.toHaveBeenCalled()
    mocks.auth.user.permissions.push('platform.management_principals.rotate')
    await flushPromises()
    let complete!: (value: boolean) => void
    mocks.confirm.mockImplementation(
      () =>
        new Promise((resolve) => {
          complete = resolve
        })
    )
    await button(wrapper, '轮换').trigger('click')
    await flushPromises()
    mocks.auth.user.permissions = ['platform.management_principals.read']
    complete(true)
    await flushPromises()
    expect(mocks.rotate).not.toHaveBeenCalled()
  })
  it('创建传递精确目标和操作键，关闭交付窗口后移除一次秘密', async () => {
    mocks.create.mockResolvedValue({
      principal,
      management_key: 'secret-key',
      signing_secret: 'secret-signature',
      replayed: false
    })
    const wrapper = await open(ManagementPrincipalsPage, '/platform/management-principals')
    await button(wrapper, '创建管理身份').trigger('click')
    await flushPromises()
    await field(wrapper, '名称').getComponent(ElInput).get('input').setValue('机构开通')
    field(wrapper, '管理动作')
      .getComponent(ElSelect)
      .vm.$emit('update:modelValue', ['platform.institutions.provision'])
    await flushPromises()
    field(wrapper, '目标命名空间')
      .getComponent(ElSelect)
      .vm.$emit('update:modelValue', [' clinic ', 'clinic'])
    await button(wrapper, '签发管理身份').trigger('click')
    await flushPromises()
    expect(mocks.create).toHaveBeenCalledWith(
      {
        name: '机构开通',
        scopes: ['platform.institutions.provision'],
        target_tenant_ids: [],
        target_definition_ids: [],
        target_namespaces: ['clinic']
      },
      expect.any(String)
    )
    expect(
      wrapper.findAllComponents(ElInput).some((input) => input.props('modelValue') === 'secret-key')
    ).toBe(true)
    await button(wrapper, '已安全保存并关闭').trigger('click')
    await flushPromises()
    expect(
      wrapper.findAllComponents(ElInput).some((input) => input.props('modelValue') === 'secret-key')
    ).toBe(false)
  })
  it('仅有签发权限也从操作候选按名称多选目标，不依赖目录读取权限', async () => {
    mocks.options.mockImplementation(async (_method: string, path: string) => ({
      items: path.includes('/tenants?')
        ? [{ id: '9223372036854775807', label: '机构甲' }]
        : [{ id: '9223372036854775806', label: '定义甲' }],
      total: 1
    }))
    mocks.create.mockResolvedValue({ principal, management_key: null, signing_secret: null })
    const wrapper = await open(ManagementPrincipalsPage, '/platform/management-principals')
    await button(wrapper, '创建管理身份').trigger('click')
    await flushPromises()
    const tenants = field(wrapper, '目标机构标识').getComponent(ElSelect)
    expect(tenants.props('allowCreate')).toBe(false)
    expect(
      mocks.options.mock.calls.every((call) =>
        String(call[1]).includes('operation=platform.management_principals.create')
      )
    ).toBe(true)
    await field(wrapper, '名称').getComponent(ElInput).get('input').setValue('直管身份')
    field(wrapper, '管理动作')
      .getComponent(ElSelect)
      .vm.$emit('update:modelValue', ['platform.institutions.provision'])
    tenants.vm.$emit('update:modelValue', ['9223372036854775807'])
    field(wrapper, '目标定义标识')
      .getComponent(ElSelect)
      .vm.$emit('update:modelValue', ['9223372036854775806'])
    await button(wrapper, '签发管理身份').trigger('click')
    await flushPromises()
    expect(mocks.create).toHaveBeenCalledWith(
      expect.objectContaining({
        target_tenant_ids: ['9223372036854775807'],
        target_definition_ids: ['9223372036854775806']
      }),
      expect.any(String)
    )
  })
  it('受限签发者只能选择所选动作共同授予的命名空间，旧的失效选值阻止提交', async () => {
    mocks.auth.user.permissions.push('platform.institutions.associate')
    mocks.options.mockImplementation(async (_method: string, path: string) => ({
      items: path.includes('/namespaces?') ? [{ id: 'shared', label: '共同命名空间' }] : [],
      total: path.includes('/namespaces?') ? 1 : 0,
      allow_create: false
    }))
    mocks.create.mockResolvedValue({ principal, management_key: null, signing_secret: null })
    const wrapper = await open(ManagementPrincipalsPage, '/platform/management-principals')
    await button(wrapper, '创建管理身份').trigger('click')
    await flushPromises()
    await field(wrapper, '名称').getComponent(ElInput).get('input').setValue('受限身份')
    field(wrapper, '管理动作')
      .getComponent(ElSelect)
      .vm.$emit('update:modelValue', [
        'platform.institutions.provision',
        'platform.institutions.associate'
      ])
    await flushPromises()
    const namespaces = field(wrapper, '目标命名空间').getComponent(ElSelect)
    expect(namespaces.props('allowCreate')).toBe(false)
    namespaces.vm.$emit('update:modelValue', ['provision-only'])
    await button(wrapper, '签发管理身份').trigger('click')
    await flushPromises()
    expect(mocks.create).not.toHaveBeenCalled()
    expect(document.body.textContent).toContain('共同授予范围')
    namespaces.vm.$emit('update:modelValue', ['shared'])
    await button(wrapper, '签发管理身份').trigger('click')
    await flushPromises()
    expect(mocks.create).toHaveBeenCalledWith(
      expect.objectContaining({ target_namespaces: ['shared'] }),
      expect.any(String)
    )
  })
  it('开通机构只消费开通套餐候选，all 命名空间可显式输入新值', async () => {
    mocks.options.mockImplementation(async (_method: string, path: string) =>
      path.includes('/provision/plans?')
        ? { items: [{ id: 'pro', label: '专业套餐' }], total: 1 }
        : { items: [], total: 0, allow_create: true }
    )
    mocks.provision.mockResolvedValue({ tenant_id: '7' })
    const wrapper = await open(InstitutionIntegrationsPage, '/platform/institutions')
    await button(wrapper, '开通机构').trigger('click')
    await flushPromises()
    const namespace = wrapper
      .findAllComponents(ElFormItem)
      .findLast((item) => item.props('label') === '命名空间')!
      .getComponent(ElSelect)
    expect(namespace.props('allowCreate')).toBe(true)
    namespace.vm.$emit('update:modelValue', 'new-business')
    await field(wrapper, '机构名称').getComponent(ElInput).get('input').setValue('新机构')
    await field(wrapper, '外部机构标识').getComponent(ElInput).get('input').setValue('external-new')
    field(wrapper, '默认套餐').getComponent(EntitySelect).vm.$emit('update:modelValue', 'pro')
    await flushPromises()
    await button(wrapper, '确认开通').trigger('click')
    await flushPromises()
    expect(mocks.provision).toHaveBeenCalledWith(
      expect.objectContaining({ namespace: 'new-business', default_plan_code: 'pro' }),
      expect.any(String)
    )
    expect(
      mocks.options.mock.calls.some((call) =>
        String(call[1]).includes('/operation-options/provision/plans?')
      )
    ).toBe(true)
  })
  it('轮换执行凭证发送安全目录主键与精确版本，不混用稳定主体名', async () => {
    mocks.rotateCredential.mockResolvedValue({ principal: {}, api_key: null, signing_secret: null })
    const wrapper = await open(
      InstitutionCredentialsPage,
      '/platform/institution-credentials?tenant_id=t1&integration_id=i1'
    )
    await button(wrapper, '轮换').trigger('click')
    await flushPromises()
    expect(mocks.rotateCredential).toHaveBeenCalledWith(
      {
        tenant_id: 't1',
        integration_id: 'i1',
        principal_id: '9223372036854775806',
        expected_revision: 4
      },
      expect.any(String)
    )
    expect(wrapper.text()).toContain('没有可再次交付的秘密')
  })
  it('执行域选择变化清理权限草稿，不能把旧域的签发选择沿用到新机构', async () => {
    const wrapper = await open(
      InstitutionCredentialsPage,
      '/platform/institution-credentials?tenant_id=t1&integration_id=i1'
    )
    field(wrapper, '执行权限')
      .getComponent(ElSelect)
      .vm.$emit('update:modelValue', ['sessions.write'])
    await flushPromises()
    field(wrapper, '机构标识').getComponent(EntitySelect).vm.$emit('update:modelValue', 't2')
    await flushPromises()
    expect(field(wrapper, '接入标识').getComponent(EntitySelect).props('modelValue')).toBe('')
    expect(field(wrapper, '执行权限').getComponent(ElSelect).props('modelValue')).toEqual([])
    expect(wrapper.text()).toContain('执行域选择已变化')
    expect(mocks.issue).not.toHaveBeenCalled()
  })
})
