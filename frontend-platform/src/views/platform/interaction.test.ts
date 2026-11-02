import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { computed, type Component } from 'vue'
import { createPermissionDirective } from '@skillforge/shared/directives/permission'
import { HttpClientError } from '@skillforge/shared/utils/http'
import {
  ElFormItem,
  ElPagination,
  ElInput,
  ElInputNumber,
  ElSelect,
  ElDrawer,
  ElMessageBox
} from 'element-plus'
import { AgentConfigFields } from '@skillforge/shared/admin'
import AgentTemplateDetailPage from './AgentTemplateDetailPage.vue'
import ModelDetailPage from './ModelDetailPage.vue'
import InstitutionOnboardingFlow from './InstitutionOnboardingFlow.vue'
import InstitutionIntegrationsPage from './InstitutionIntegrationsPage.vue'
import InstitutionCredentialsPage from './InstitutionCredentialsPage.vue'
import AgentTemplatesPage from './AgentTemplatesPage.vue'
const currentAuth = vi.hoisted(() => ({
  identityVersion: 1,
  user: {
    sub: 'platform-actor',
    permissions: [
      'foundation.models.create',
      'foundation.models.update',
      'foundation.models.publish',
      'foundation.models.run',
      'platform.agent_definitions.create',
      'platform.agent_definitions.edit',
      'foundation.tenants.create',
      'platform.institutions.read',
      'platform.institutions.associate'
    ]
  }
}))
vi.mock('../../stores/auth', () => ({ useAuthStore: () => currentAuth }))
const mocks = vi.hoisted(() => ({
  providers: vi.fn(),
  detail: vi.fn(),
  create: vi.fn(),
  update: vi.fn(),
  listTenants: vi.fn(),
  listTemplates: vi.fn(),
  listMappings: vi.fn(),
  credentials: vi.fn(),
  issueCredential: vi.fn(),
  createTenant: vi.fn(),
  tenantCreateResult: vi.fn(),
  findTenantBySlug: vi.fn(),
  createTemplate: vi.fn(),
  updateTemplate: vi.fn(),
  modelList: vi.fn(),
  capabilityReferences: vi.fn(),
  capabilityVersion: vi.fn()
}))
vi.mock('../../api', () => ({
  foundationRequest: async () => ({ items: [], total: 0, allow_create: true }),
  api: {
    capabilities: {
      listDefinitions: mocks.capabilityReferences,
      getVersion: mocks.capabilityVersion
    },
    agentTemplates: {
      list: mocks.listTemplates,
      create: mocks.createTemplate,
      update: mocks.updateTemplate,
      detail: async () => {
        throw new Error('读取失败')
      }
    },
    platformIntegration: {
      institutions: mocks.listMappings,
      institutionCredentials: mocks.credentials,
      issueCredential: mocks.issueCredential
    },
    admin: {
      models: {
        list: mocks.modelList,
        providers: mocks.providers,
        detail: mocks.detail,
        createWithReceipt: mocks.create,
        update: mocks.update
      },
      listTenants: mocks.listTenants,
      createTenant: mocks.createTenant,
      tenantCreateResult: mocks.tenantCreateResult,
      findTenantBySlug: mocks.findTenantBySlug,
      plans: async () => []
    }
  }
}))
const mounted: VueWrapper[] = []
afterEach(() => {
  mounted.forEach((w) => w.unmount())
  mounted.length = 0
  document.body.innerHTML = ''
})
beforeEach(() => {
  vi.clearAllMocks()
  sessionStorage.clear()
  mocks.modelList.mockResolvedValue([{ id: 'm1', name: '模型', kind: 'LLM', enabled: true }])
  mocks.capabilityReferences.mockResolvedValue([])
  mocks.capabilityVersion.mockResolvedValue({ descriptor: { activation_mode: 'ON_DEMAND' } })
  mocks.providers.mockResolvedValue({ providers: [] })
  mocks.listTenants.mockResolvedValue({ items: [], total: 0 })
  mocks.listTemplates.mockResolvedValue({ items: [], total: 0 })
  mocks.listMappings.mockResolvedValue({ items: [], total: 0 })
  mocks.credentials.mockResolvedValue({ items: [], total: 0 })
})
async function open(
  component: Component,
  path = '/platform/models/new',
  props?: Record<string, unknown>
) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/platform/models/new', component },
      { path: '/platform/models/:id', component },
      { path: '/platform/tenants', component },
      { path: '/platform/agent-templates/new', component },
      { path: '/platform/agent-templates', component }
    ]
  })
  await router.push(path)
  await router.isReady()
  const wrapper = mount(component, {
    props,
    attachTo: document.body,
    global: {
      plugins: [router],
      directives: {
        permission: createPermissionDirective(() => computed(() => currentAuth.user.permissions))
      }
    }
  })
  mounted.push(wrapper)
  await flushPromises()
  return wrapper
}
function field(wrapper: VueWrapper, label: string) {
  const item = wrapper.findAllComponents(ElFormItem).find((x) => x.props('label') === label)
  if (!item) throw new Error('Missing field ' + label)
  return item
}
async function setInput(wrapper: VueWrapper, label: string, value: string) {
  await field(wrapper, label).getComponent(ElInput).get('input').setValue(value)
}
async function embedding(wrapper: VueWrapper) {
  const select = field(wrapper, '类型').getComponent(ElSelect)
  select.vm.$emit('update:modelValue', 'EMBEDDING')
  select.vm.$emit('change', 'EMBEDDING')
  await flushPromises()
  await setInput(wrapper, '名称', '向量模型')
  const provider = field(wrapper, '提供方').getComponent(ElSelect)
  provider.vm.$emit('update:modelValue', 'provider')
  provider.vm.$emit('change', 'provider')
  await flushPromises()
  await setInput(wrapper, 'API 凭据', 'test-key')
  field(wrapper, '向量维度').getComponent(ElInputNumber).vm.$emit('update:modelValue', 1024)
  field(wrapper, '模型标识').getComponent(ElSelect).vm.$emit('update:modelValue', 'embedding-v1')
  await flushPromises()
}
function prepareEmbeddingCatalog() {
  mocks.providers.mockResolvedValueOnce({
    providers: [
      {
        provider_id: 'provider',
        display_name: '测试服务',
        category: 'CLOUD',
        services: [
          {
            service_id: 'embedding',
            display_name: '向量服务',
            profile_version: '1',
            availability: 'SUPPORTED',
            region: 'CHINA_MAINLAND',
            model_kinds: ['EMBEDDING'],
            endpoint: 'https://models.example.test',
            custom_endpoint: false,
            protocols: [
              {
                protocol: 'openai-compatible',
                dialect_version: '1',
                authentication_schemes: ['bearer-api-key'],
                capabilities: {}
              }
            ]
          }
        ]
      }
    ]
  })
}
describe('新平台直接 Element Plus 交互', () => {
  it('模板槽位生成不重复标识，缺名与重复标识就近反馈且不提交', async () => {
    const wrapper = await open(AgentTemplateDetailPage, '/platform/agent-templates/new')
    wrapper.getComponent(AgentConfigFields).vm.$emit('update:modelValue', {
      name: '客户服务模板',
      description: '',
      system_prompt: '遵守事实',
      model_config_id: 'm1'
    })
    wrapper
      .findAllComponents(ElSelect)
      .find((value) => value.props('placeholder') === '请选择分发方式')!
      .vm.$emit('update:modelValue', 'OPEN_INSTALL')
    const add = () =>
      wrapper
        .findAll('button')
        .find((value) => value.text() === '添加资源槽位')!
        .trigger('click')
    await add()
    await add()
    await flushPromises()
    const keys = () =>
      wrapper.findAllComponents(ElFormItem).filter((value) => value.props('label') === '槽位标识')
    expect(keys().map((value) => value.getComponent(ElInput).props('modelValue'))).toEqual([
      'resource_1',
      'resource_2'
    ])
    const stableHelp = keys()[1]!.getComponent(ElInput).get('input').attributes('aria-describedby')
    await keys()[1]!.getComponent(ElInput).get('input').setValue('resource_1')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mocks.createTemplate).not.toHaveBeenCalled()
    expect(keys()[1]!.props('error')).toContain('唯一')
    expect(
      wrapper
        .findAllComponents(ElFormItem)
        .filter((value) => value.props('label') === '显示名称')
        .map((value) => value.props('error'))
    ).toEqual(['请填写显示名称', '请填写显示名称'])
    await wrapper
      .findAll('button')
      .find((value) => value.text() === '移除')!
      .trigger('click')
    await flushPromises()
    expect(keys()[0]!.getComponent(ElInput).get('input').attributes('aria-describedby')).toBe(
      stableHelp
    )
  })
  it('模板切换能力时丢弃旧发布配置，保留新版本激活方式', async () => {
    mocks.capabilityReferences.mockResolvedValue([
      { id: 'one', name: '能力一', capabilityType: 'SKILL', defaultVersionId: 'v-one' },
      { id: 'two', name: '能力二', capabilityType: 'SKILL', defaultVersionId: 'v-two' }
    ])
    let resolve!: (value: unknown) => void
    mocks.capabilityVersion.mockImplementation((id: string) =>
      id === 'v-one'
        ? new Promise((value) => {
            resolve = value
          })
        : Promise.resolve({ descriptor: { activation_mode: 'ALWAYS' } })
    )
    const wrapper = await open(AgentTemplateDetailPage, '/platform/agent-templates/new')
    await wrapper
      .findAll('button')
      .find((value) => value.text() === '添加能力')!
      .trigger('click')
    await flushPromises()
    const select = field(wrapper, '能力').getComponent(ElSelect)
    const choose = async (id: string) => {
      select.vm.$emit('update:modelValue', id)
      select.vm.$emit('change', id)
      await flushPromises()
    }
    await choose('one')
    expect(wrapper.text()).toContain('正在读取能力发布配置')
    await choose('two')
    resolve({ descriptor: { activation_mode: 'ON_DEMAND' } })
    await flushPromises()
    expect(
      field(wrapper, '激活方式（跟随所选发布）').getComponent(ElSelect).props('modelValue')
    ).toBe('ALWAYS')
  })
  it('能力发布版本变化后无需手动更新配置即可保存草稿', async () => {
    mocks.capabilityReferences.mockResolvedValueOnce([
      { id: 'tool-1', name: '查询工具', capabilityType: 'TOOL', defaultVersionId: 'v1' }
    ])
    mocks.capabilityVersion.mockImplementation(async (id: string) => ({
      descriptor: { tool_name: id === 'v1' ? 'old_tool' : 'new_tool' }
    }))
    mocks.createTemplate.mockImplementation(async (payload) => ({
      ...payload,
      definition_id: 'new-definition',
      draft_revision: 1,
      status: 'draft',
      version: '1'
    }))
    const wrapper = await open(AgentTemplateDetailPage, '/platform/agent-templates/new')
    const replace = vi.spyOn(wrapper.vm.$router, 'replace').mockResolvedValue(undefined)
    const config = wrapper.getComponent(AgentConfigFields)
    config.vm.$emit('update:modelValue', {
      name: '保留草稿',
      description: '',
      system_prompt: '根据事实回答',
      model_config_id: 'm1'
    })
    wrapper
      .findAllComponents(ElSelect)
      .find((select) => select.props('placeholder') === '请选择分发方式')!
      .vm.$emit('update:modelValue', 'OPEN_INSTALL')
    await wrapper
      .findAll('button')
      .find((item) => item.text() === '添加能力')!
      .trigger('click')
    await flushPromises()
    const type = field(wrapper, '类型').getComponent(ElSelect)
    type.vm.$emit('update:modelValue', 'TOOL')
    type.vm.$emit('change', 'TOOL')
    await flushPromises()
    const capability = field(wrapper, '能力').getComponent(ElSelect)
    capability.vm.$emit('update:modelValue', 'tool-1')
    capability.vm.$emit('change', 'tool-1')
    await flushPromises()
    expect(field(wrapper, '平台工具名（自动读取）').getComponent(ElInput).props('modelValue')).toBe(
      'old_tool'
    )
    mocks.modelList.mockResolvedValueOnce([
      { id: 'm1', name: '模型', kind: 'LLM', enabled: true },
      { id: 'm2', name: '新模型', kind: 'LLM', enabled: true }
    ])
    mocks.capabilityReferences.mockResolvedValueOnce([
      { id: 'tool-1', name: '查询工具', capabilityType: 'TOOL', defaultVersionId: 'v2' }
    ])
    await wrapper
      .findAll('button')
      .find((item) => item.text() === '刷新编辑选项')!
      .trigger('click')
    await flushPromises()
    const configProps = config.props() as unknown as {
      modelValue: { name: string }
      models: unknown[]
    }
    expect(configProps.modelValue.name).toBe('保留草稿')
    expect(configProps.models).toHaveLength(2)
    expect(field(wrapper, '平台工具名（自动读取）').getComponent(ElInput).props('modelValue')).toBe(
      'old_tool'
    )
    expect(wrapper.text()).toContain('能力已发布新版本')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mocks.createTemplate).toHaveBeenCalledWith(
      expect.objectContaining({
        definition: expect.objectContaining({
          capabilities: [
            expect.objectContaining({
              capability_definition_id: 'tool-1',
              capability_version_id: 'v1',
              platform_tool_name: 'old_tool'
            })
          ]
        })
      }),
      expect.any(String)
    )
    expect(replace).toHaveBeenCalled()
  })

  it('基础能力使用精确目录字符串身份，大ID保存不转为数值', async () => {
    mocks.capabilityReferences.mockResolvedValue([
      {
        id: '9223372036854775807',
        name: '精确技能',
        capabilityType: 'SKILL',
        defaultVersionId: '9223372036854775806'
      }
    ])
    mocks.createTemplate.mockImplementation(async (payload) => ({
      ...payload,
      definition_id: 'definition-1',
      draft_revision: 1,
      status: 'draft',
      version: '1'
    }))
    const wrapper = await open(
      AgentTemplateDetailPage,
      '/platform/agent-templates/new?list_name=客户&list_status=published&list_page=3'
    )
    const back = wrapper.findAll('a').find((link) => link.text() === '返回列表')!
    const returnUrl = new URL(back.attributes('href')!, 'http://test.local')
    expect(Object.fromEntries(returnUrl.searchParams)).toEqual({
      list_name: '客户',
      list_status: 'published',
      list_page: '3'
    })
    const replace = vi.spyOn(wrapper.vm.$router, 'replace').mockResolvedValue(undefined)
    expect(wrapper.text()).toContain('留空继承场景与角色默认额度')
    field(wrapper, '业务模型调用次数').getComponent(ElInputNumber).vm.$emit('update:modelValue', 9)
    wrapper.getComponent(AgentConfigFields).vm.$emit('update:modelValue', {
      name: '基础能力定义',
      description: '',
      system_prompt: '根据事实回答',
      model_config_id: 'm1'
    })
    wrapper
      .findAllComponents(ElSelect)
      .find((select) => select.props('placeholder') === '请选择分发方式')!
      .vm.$emit('update:modelValue', 'OPEN_INSTALL')
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '添加能力')!
      .trigger('click')
    await flushPromises()
    const select = field(wrapper, '能力').getComponent(ElSelect)
    select.vm.$emit('update:modelValue', '9223372036854775807')
    select.vm.$emit('change', '9223372036854775807')
    await flushPromises()
    field(wrapper, 'Token 预算').getComponent(ElInputNumber).vm.$emit('update:modelValue', 2000)
    await flushPromises()
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mocks.createTemplate).toHaveBeenCalledWith(
      expect.objectContaining({
        definition: expect.objectContaining({
          execution_config: expect.objectContaining({ max_business_model_calls: 9 }),
          capabilities: [
            expect.objectContaining({
              capability_definition_id: '9223372036854775807',
              capability_version_id: '9223372036854775806'
            })
          ]
        })
      }),
      expect.any(String)
    )
    expect(replace).toHaveBeenCalledWith({
      path: '/platform/agent-templates/definition-1',
      query: { list_name: '客户', list_status: 'published', list_page: '3' }
    })
    expect(
      mocks.createTemplate.mock.calls.at(-1)![0].definition.execution_config
    ).not.toHaveProperty('max_iterations')
  })
  it('新建模型深链直接显示表单，无路由过渡和抽屉初始化依赖', async () => {
    const wrapper = await open(ModelDetailPage)
    expect(wrapper.text()).toContain('新建模型')
    expect(wrapper.find('form').exists()).toBe(true)
    expect(wrapper.text()).toContain('模型标识')
    expect(mocks.detail).not.toHaveBeenCalled()
  })
  it('新建模型超时跟随类型默认值，手动输入后切换类型保留其值', async () => {
    const wrapper = await open(ModelDetailPage)
    const timeout = () => field(wrapper, '超时（秒）').getComponent(ElInputNumber)
    const kind = field(wrapper, '类型').getComponent(ElSelect)
    const selectKind = async (value: string) => {
      kind.vm.$emit('update:modelValue', value)
      kind.vm.$emit('change', value)
      await flushPromises()
    }
    expect(timeout().props('modelValue')).toBe(600)
    await selectKind('EMBEDDING')
    expect(timeout().props('modelValue')).toBe(120)
    await selectKind('RERANK')
    expect(timeout().props('modelValue')).toBe(60)
    await selectKind('LLM')
    expect(timeout().props('modelValue')).toBe(600)
    timeout().vm.$emit('update:modelValue', 240)
    await selectKind('EMBEDDING')
    expect(timeout().props('modelValue')).toBe(240)
  })
  it('已有模型保留保存的超时且类型只读', async () => {
    mocks.detail.mockResolvedValue({
      id: 'existing-model',
      revision: 1,
      name: '已有模型',
      kind: 'LLM',
      timeout_seconds: 75,
      credential_configured: true
    })
    const wrapper = await open(ModelDetailPage, '/platform/models/existing-model')
    expect(field(wrapper, '超时（秒）').getComponent(ElInputNumber).props('modelValue')).toBe(75)
    expect(field(wrapper, '类型').getComponent(ElSelect).props('disabled')).toBe(true)
  })
  it('字段缺失时阻止保存并保持编辑界面', async () => {
    const wrapper = await open(ModelDetailPage)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mocks.create).not.toHaveBeenCalled()
    expect(document.body.querySelector('.el-message')?.textContent).toContain(
      '请填写名称、提供方和模型标识'
    )
  })
  it('价格保持精确字符串，提交期间重复点击只创建一次', async () => {
    prepareEmbeddingCatalog()
    const wrapper = await open(ModelDetailPage)
    await embedding(wrapper)
    await setInput(wrapper, '每百万输入 Token（分）', '9223372036854775807')
    let complete!: (value: unknown) => void
    mocks.create.mockImplementation(
      () =>
        new Promise((resolve) => {
          complete = resolve
        })
    )
    await wrapper.get('form').trigger('submit')
    await wrapper.get('form').trigger('submit')
    expect(mocks.create).toHaveBeenCalledTimes(1)
    expect(mocks.create.mock.calls[0]?.[1].timeout_seconds).toBe(120)
    expect(mocks.create.mock.calls[0]?.[1].input_price_per_million_cents).toBe(
      '9223372036854775807'
    )
    mocks.detail.mockResolvedValue({
      ...mocks.create.mock.calls[0]?.[1],
      id: 'model-1',
      revision: 1,
      credential_configured: false
    })
    complete({ request_id: mocks.create.mock.calls[0]?.[0], model_id: 'model-1' })
    await flushPromises()
    expect(wrapper.text()).not.toContain('未保存')
  })
  it('价格超出后端 Long 上限时不发送创建请求', async () => {
    prepareEmbeddingCatalog()
    const wrapper = await open(ModelDetailPage)
    await embedding(wrapper)
    await setInput(wrapper, '每百万输入 Token（分）', '9223372036854775808')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mocks.create).not.toHaveBeenCalled()
    expect(document.body.querySelector('.el-message')?.textContent).toContain(
      '价格须为不超过 9223372036854775807'
    )
  })
  it('普通机构开通流程直接显示完整表单，空提交不会请求', async () => {
    await open(InstitutionOnboardingFlow, '/platform/tenants')
    expect(document.body.textContent).toContain('管理员初始密码')
    const create = Array.from(document.body.querySelectorAll('button')).find((x) =>
      x.textContent?.includes('创建机构')
    )!
    create.click()
    await flushPromises()
    expect(mocks.createTenant).not.toHaveBeenCalled()
    expect(document.body.textContent).toContain('至少 12 个字符')
  })
  it('普通机构创建成功后同步向工作台报告维护目标', async () => {
    mocks.createTenant.mockImplementation(async (id: string) => ({
      request_id: id,
      status: 'SUCCEEDED',
      tenant_id: '9001',
      tenant_name: '示例机构'
    }))
    const wrapper = await open(InstitutionOnboardingFlow, '/platform/tenants')
    await setInput(wrapper, '机构名称', '示例机构')
    await setInput(wrapper, '机构标识', 'example')
    await setInput(wrapper, '管理员账号', 'admin')
    await setInput(wrapper, '管理员初始密码', 'Example123456')
    await wrapper.get('form.ordinary-form').trigger('submit')
    await flushPromises()
    expect(mocks.createTenant).toHaveBeenCalledOnce()
    expect(wrapper.emitted('targetSelected')).toEqual([[{ tenantId: '9001' }]])
    expect(wrapper.emitted('changed')).toHaveLength(1)
    expect(wrapper.text()).toContain('本次目标：示例机构')
  })
  it('外部机构接入成功后携带原目标直接进入凭证步骤，不自动签发', async () => {
    currentAuth.user.permissions.push(
      'platform.institution_credentials.read',
      'platform.institution_credentials.issue'
    )
    try {
      const wrapper = await open(InstitutionOnboardingFlow, '/platform/tenants', {
        tenantId: 'tenant-1'
      })
      wrapper
        .getComponent(InstitutionIntegrationsPage)
        .vm.$emit('provisioned', { tenantId: 'tenant-1', integrationId: 'integration-1' })
      await flushPromises()
      const panel = wrapper.getComponent(InstitutionCredentialsPage)
      expect(panel.props('tenantId')).toBe('tenant-1')
      expect(panel.props('integrationId')).toBe('integration-1')
      expect(wrapper.emitted('targetSelected')).toEqual([
        [{ tenantId: 'tenant-1', integrationId: 'integration-1' }]
      ])
      expect(mocks.credentials).toHaveBeenCalledWith(
        expect.objectContaining({ tenant_id: 'tenant-1', integration_id: 'integration-1' }),
        expect.anything()
      )
      expect(mocks.issueCredential).not.toHaveBeenCalled()
    } finally {
      currentAuth.user.permissions.splice(-2)
    }
  })
  it('旧记录按机构标识核对且保留未知状态', async () => {
    currentAuth.user.permissions.push('foundation.tenants.read')
    try {
      sessionStorage.setItem('skillforge.platform-tenant-create:platform-actor', 'example')
      mocks.findTenantBySlug.mockResolvedValue({ id: '9001', name: '示例机构', slug: 'example' })
      vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue(
        'confirm' as Awaited<ReturnType<typeof ElMessageBox.confirm>>
      )
      const wrapper = await open(InstitutionOnboardingFlow, '/platform/tenants')
      await wrapper
        .findAll('button')
        .find((button) => button.text().includes('按原机构标识核对'))!
        .trigger('click')
      await flushPromises()
      await wrapper
        .findAll('button')
        .find((button) => button.text().includes('使用该机构继续'))!
        .trigger('click')
      await flushPromises()
      expect(mocks.tenantCreateResult).not.toHaveBeenCalled()
      expect(mocks.createTenant).not.toHaveBeenCalled()
      expect(wrapper.emitted('targetSelected')).toEqual([[{ tenantId: '9001' }]])
      expect(sessionStorage.getItem('skillforge.platform-tenant-create:platform-actor')).toBe(
        'example'
      )
    } finally {
      currentAuth.user.permissions.pop()
      vi.restoreAllMocks()
    }
  })
  it('响应丢失后刷新并查询原回执，成功后直接继续且不保存密码', async () => {
    currentAuth.user.permissions.push('foundation.tenants.read')
    try {
      mocks.createTenant.mockRejectedValue(new Error('网络中断'))
      mocks.tenantCreateResult.mockImplementation(async (id: string) => ({
        request_id: id,
        status: 'SUCCEEDED',
        tenant_id: '9001',
        tenant_name: '示例机构'
      }))
      const wrapper = await open(InstitutionOnboardingFlow, '/platform/tenants')
      await setInput(wrapper, '机构名称', '示例机构')
      await setInput(wrapper, '机构标识', 'original-slug')
      await setInput(wrapper, '管理员账号', 'admin')
      await setInput(wrapper, '管理员初始密码', 'Example123456')
      await wrapper.get('form.ordinary-form').trigger('submit')
      await flushPromises()
      const stored = sessionStorage.getItem('skillforge.platform-tenant-create:platform-actor')!
      expect(stored).not.toContain('Example123456')
      const record = JSON.parse(stored)
      expect(record.slug).toBe('original-slug')
      wrapper.unmount()
      const restored = await open(InstitutionOnboardingFlow, '/platform/tenants')
      expect(field(restored, '管理员初始密码').getComponent(ElInput).props('modelValue')).toBe('')
      await restored
        .findAll('button')
        .find((button) => button.text().includes('核对原请求'))!
        .trigger('click')
      await flushPromises()
      expect(mocks.tenantCreateResult).toHaveBeenCalledWith(record.requestId, expect.anything())
      expect(mocks.findTenantBySlug).not.toHaveBeenCalled()
      expect(mocks.createTenant).toHaveBeenCalledTimes(1)
      expect(restored.emitted('targetSelected')).toEqual([[{ tenantId: '9001' }]])
      expect(sessionStorage.getItem('skillforge.platform-tenant-create:platform-actor')).toBeNull()
    } finally {
      currentAuth.user.permissions.pop()
    }
  })
  it('原请求仍处理中或回执身份不符时保留记录且不重复创建', async () => {
    currentAuth.user.permissions.push('foundation.tenants.read')
    try {
      sessionStorage.setItem(
        'skillforge.platform-tenant-create:platform-actor',
        JSON.stringify({ slug: 'example', requestId: 'request-1' })
      )
      mocks.tenantCreateResult
        .mockResolvedValueOnce({ request_id: 'request-1', status: 'PROCESSING' })
        .mockResolvedValueOnce({
          request_id: 'other',
          status: 'SUCCEEDED',
          tenant_id: '9001',
          tenant_name: '示例机构'
        })
      const wrapper = await open(InstitutionOnboardingFlow, '/platform/tenants')
      const verify = () =>
        wrapper.findAll('button').find((button) => button.text().includes('核对原请求'))!
      await verify().trigger('click')
      await flushPromises()
      await verify().trigger('click')
      await flushPromises()
      expect(wrapper.emitted('targetSelected')).toBeUndefined()
      expect(mocks.createTenant).not.toHaveBeenCalled()
      expect(
        sessionStorage.getItem('skillforge.platform-tenant-create:platform-actor')
      ).not.toBeNull()
    } finally {
      currentAuth.user.permissions.pop()
    }
  })
  it('原请求确定拒绝后解除未知状态', async () => {
    currentAuth.user.permissions.push('foundation.tenants.read')
    try {
      sessionStorage.setItem(
        'skillforge.platform-tenant-create:platform-actor',
        JSON.stringify({ slug: 'example', requestId: 'request-1' })
      )
      mocks.tenantCreateResult.mockResolvedValue({
        request_id: 'request-1',
        status: 'REJECTED',
        error_message: '机构标识已存在'
      })
      const wrapper = await open(InstitutionOnboardingFlow, '/platform/tenants')
      await wrapper
        .findAll('button')
        .find((button) => button.text().includes('核对原请求'))!
        .trigger('click')
      await flushPromises()
      expect(sessionStorage.getItem('skillforge.platform-tenant-create:platform-actor')).toBeNull()
      expect(wrapper.text()).not.toContain('机构创建结果未知')
      expect(mocks.createTenant).not.toHaveBeenCalled()
    } finally {
      currentAuth.user.permissions.pop()
    }
  })
  it('回执确认事务回滚后允许重新创建', async () => {
    currentAuth.user.permissions.push('foundation.tenants.read')
    try {
      sessionStorage.setItem(
        'skillforge.platform-tenant-create:platform-actor',
        JSON.stringify({ slug: 'example', requestId: 'request-1' })
      )
      mocks.tenantCreateResult.mockResolvedValue({
        request_id: 'request-1',
        status: 'FAILED',
        error_message: '机构创建失败，事务已回滚；请检查后重新创建。'
      })
      const wrapper = await open(InstitutionOnboardingFlow, '/platform/tenants')
      await wrapper
        .findAll('button')
        .find((button) => button.text().includes('核对原请求'))!
        .trigger('click')
      await flushPromises()
      expect(sessionStorage.getItem('skillforge.platform-tenant-create:platform-actor')).toBeNull()
      expect(wrapper.text()).not.toContain('机构创建结果未知')
      expect(mocks.createTenant).not.toHaveBeenCalled()
    } finally {
      currentAuth.user.permissions.pop()
    }
  })
  it('服务端确认派发前输入拒绝后允许修正', async () => {
    mocks.createTenant.mockRejectedValue(
      new HttpClientError({
        status: 400,
        code: 'TENANT_CREATE_PRE_DISPATCH_REJECTED',
        userMessage: '创建请求无效'
      })
    )
    const wrapper = await open(InstitutionOnboardingFlow, '/platform/tenants')
    await setInput(wrapper, '机构名称', '示例机构')
    await setInput(wrapper, '机构标识', 'example')
    await setInput(wrapper, '管理员账号', 'admin')
    await setInput(wrapper, '管理员初始密码', 'Example123456')
    await wrapper.get('form.ordinary-form').trigger('submit')
    await flushPromises()
    expect(sessionStorage.getItem('skillforge.platform-tenant-create:platform-actor')).toBeNull()
    expect(wrapper.text()).not.toContain('机构创建结果未知')
  })
  it('机构创建返回 408 时保留原标识，不能按确定失败重新提交', async () => {
    mocks.createTenant.mockRejectedValue(
      new HttpClientError({ status: 408, userMessage: '请求超时' })
    )
    const wrapper = await open(InstitutionOnboardingFlow, '/platform/tenants')
    await setInput(wrapper, '机构名称', '示例机构')
    await setInput(wrapper, '机构标识', 'timeout-slug')
    await setInput(wrapper, '管理员账号', 'admin')
    await setInput(wrapper, '管理员初始密码', 'Example123456')
    await wrapper.get('form.ordinary-form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('机构创建结果未知')
    expect(
      JSON.parse(sessionStorage.getItem('skillforge.platform-tenant-create:platform-actor')!).slug
    ).toBe('timeout-slug')
    await wrapper.get('form.ordinary-form').trigger('submit')
    await flushPromises()
    expect(mocks.createTenant).toHaveBeenCalledTimes(1)
  })
  it('机构创建响应缺少身份时保留原标识并等待核对', async () => {
    mocks.createTenant.mockResolvedValue({ name: '示例机构' })
    const wrapper = await open(InstitutionOnboardingFlow, '/platform/tenants')
    await setInput(wrapper, '机构名称', '示例机构')
    await setInput(wrapper, '机构标识', 'incomplete-response')
    await setInput(wrapper, '管理员账号', 'admin')
    await setInput(wrapper, '管理员初始密码', 'Example123456')
    await wrapper.get('form.ordinary-form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('机构创建结果未知')
    expect(wrapper.emitted('targetSelected')).toBeUndefined()
    expect(
      JSON.parse(sessionStorage.getItem('skillforge.platform-tenant-create:platform-actor')!).slug
    ).toBe('incomplete-response')
  })
  it('无法保存原标识时不发送机构创建请求', async () => {
    const wrapper = await open(InstitutionOnboardingFlow, '/platform/tenants')
    await setInput(wrapper, '机构名称', '示例机构')
    await setInput(wrapper, '机构标识', 'storage-failure')
    await setInput(wrapper, '管理员账号', 'admin')
    await setInput(wrapper, '管理员初始密码', 'Example123456')
    const storage = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('storage unavailable')
    })
    try {
      await wrapper.get('form.ordinary-form').trigger('submit')
      await flushPromises()
      expect(mocks.createTenant).not.toHaveBeenCalled()
      expect(document.body.textContent).toContain('未发送请求')
    } finally {
      storage.mockRestore()
    }
  })
  it('模板创建成功后导航失败保留回执并禁止重复提交', async () => {
    mocks.createTemplate.mockImplementation(async (payload) => ({
      ...payload,
      definition_id: 'template-1',
      draft_revision: 1,
      status: 'draft',
      version: '1',
      template_key: 'template-1'
    }))
    mocks.updateTemplate.mockImplementation(async (_id, _revision, payload) => ({
      ...payload,
      definition_id: 'template-1',
      draft_revision: 1,
      status: 'draft',
      version: '1',
      template_key: 'template-1'
    }))
    const wrapper = await open(AgentTemplateDetailPage, '/platform/agent-templates/new')
    const distribution = wrapper
      .findAllComponents(ElSelect)
      .find((select) => select.props('placeholder') === '请选择分发方式')!
    distribution.vm.$emit('update:modelValue', 'OPEN_INSTALL')
    vi.spyOn(wrapper.vm.$router, 'replace').mockRejectedValue(new Error('导航失败'))
    wrapper.getComponent(AgentConfigFields).vm.$emit('update:modelValue', {
      name: '模板一',
      description: '',
      system_prompt: '根据事实回答',
      model_config_id: 'm1'
    })
    await flushPromises()
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mocks.createTemplate).toHaveBeenCalledTimes(1)
    expect(mocks.createTemplate).toHaveBeenCalledWith(
      expect.objectContaining({ distribution_policy: 'OPEN_INSTALL' }),
      expect.any(String)
    )
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mocks.createTemplate).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('模板已保存，请从模板列表进入详情后继续')
    expect(mocks.updateTemplate).not.toHaveBeenCalled()
  })
  it('机构接入编辑抽屉保持右侧顶层且禁止遮罩关闭', async () => {
    const wrapper = await open(InstitutionOnboardingFlow, '/platform/tenants', {
      tenantId: 'tenant-1'
    })
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '关联已有机构')!
      .trigger('click')
    await flushPromises()
    const drawer = wrapper.getComponent(ElDrawer)
    expect(drawer.props('direction')).toBe('rtl')
    expect(drawer.props('appendToBody')).toBe(true)
    expect(drawer.props('closeOnClickModal')).toBe(false)
  })
  it('普通机构创建表单的IME确认不提交创建请求', async () => {
    const wrapper = await open(InstitutionOnboardingFlow, '/platform/tenants')
    const input = field(wrapper, '机构名称').getComponent(ElInput).get('input').element
    const event = new KeyboardEvent('keydown', {
      key: 'Enter',
      isComposing: true,
      bubbles: true,
      cancelable: true
    })
    input.dispatchEvent(event)
    expect(event.defaultPrevented).toBe(true)
    expect(mocks.createTenant).not.toHaveBeenCalled()
  })
  it('连续翻页发送最新请求并丢弃迟到的旧页结果', async () => {
    mocks.listTemplates.mockResolvedValueOnce({ items: [], total: 100 })
    const wrapper = await open(AgentTemplatesPage, '/platform/agent-templates')
    let finishOld!: (value: unknown) => void
    mocks.listTemplates
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            finishOld = resolve
          })
      )
      .mockResolvedValueOnce({ items: [{ id: 'new', name: '第三页模板' }], total: 100 })
    const pager = wrapper.getComponent(ElPagination)
    pager.vm.$emit('current-change', 2)
    await flushPromises()
    pager.vm.$emit('current-change', 3)
    await flushPromises()
    expect(mocks.listTemplates.mock.calls.map((call) => call[0].page)).toEqual([1, 2, 3])
    expect(mocks.listTemplates.mock.calls[1]?.[1].signal.aborted).toBe(true)
    finishOld({ items: [{ id: 'old', name: '第二页模板' }], total: 100 })
    await flushPromises()
    expect(wrapper.text()).toContain('第三页模板')
    expect(wrapper.text()).not.toContain('第二页模板')
  })
})
