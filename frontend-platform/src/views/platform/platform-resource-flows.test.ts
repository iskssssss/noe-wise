import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import {
  ElButton,
  ElCheckbox,
  ElFormItem,
  ElInput,
  ElSelect,
  ElTable,
  type TableInstance
} from 'element-plus'
import SystemAgentToolsPage from './SystemAgentToolsPage.vue'
import SystemAgentsPage from './SystemAgentsPage.vue'
import ModelDetailPage from './ModelDetailPage.vue'
import AgentDefinitionImpactPanel from './AgentDefinitionImpactPanel.vue'
import SkillWorkbenchListPage from './SkillWorkbenchListPage.vue'
import type { AgentDefinitionImpact, ModelProviderService } from '../../api'
import { HttpClientError } from '@skillforge/shared/utils/http'
import { reactive, ref } from 'vue'
import { createPermissionDirective } from '@skillforge/shared/directives/permission'

const mocks = vi.hoisted(() => ({
  list: vi.fn(),
  create: vi.fn(),
  update: vi.fn(),
  allowed: vi.fn(),
  auth: undefined as unknown as { identityVersion: number; user: { sub: string } },
  publish: vi.fn(),
  receipt: vi.fn(),
  confirm: vi.fn(),
  agentList: vi.fn(),
  mountOptions: vi.fn(),
  createAgent: vi.fn(),
  createAgentReceipt: vi.fn(),
  listModels: vi.fn(),
  getDefinition: vi.fn(),
  getVersion: vi.fn(),
  listDefinitions: vi.fn(),
  listSkillCatalog: vi.fn(),
  providers: vi.fn()
}))
vi.mock('../../api', () => ({
  api: {
    systemAgentTools: {
      list: mocks.list,
      create: mocks.create,
      update: mocks.update,
      publish: mocks.publish,
      receipt: mocks.receipt
    },
    systemAgents: {
      list: mocks.agentList,
      create: mocks.createAgent,
      createWithReceipt: async (requestId: string, payload: unknown) => {
        const result = await mocks.createAgent(payload)
        return { request_id: requestId, agent_id: result?.id }
      },
      createReceipt: mocks.createAgentReceipt,
      mountOptions: mocks.mountOptions
    },
    admin: { models: { providers: mocks.providers, list: mocks.listModels } },
    capabilities: {
      listDefinitions: mocks.listDefinitions,
      listSkillCatalog: mocks.listSkillCatalog,
      getDefinition: mocks.getDefinition,
      getVersion: mocks.getVersion
    }
  }
}))
vi.mock('../../composables/actionPermissions', () => ({
  useActionPermissions: () => ({
    allowed: mocks.allowed,
    checked: (_permission: string, run: () => unknown) => run()
  })
}))
vi.mock('@skillforge/shared/feedback', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@skillforge/shared/feedback')>()),
  confirm: mocks.confirm,
  message: { success: vi.fn(), warning: vi.fn(), error: vi.fn(), info: vi.fn() }
}))
vi.mock('../../stores/auth', () => ({ useAuthStore: () => mocks.auth }))
vi.mock('vue-router', () => ({
  onBeforeRouteLeave: vi.fn(),
  onBeforeRouteUpdate: vi.fn(),
  useRoute: () => ({ path: '/platform/models/new', params: {} }),
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() })
}))

let wrapper: VueWrapper | undefined
const deployment = {
  id: 'deployment-1',
  revision: 3,
  name: '客户查询生产部署',
  tool_definition_id: 'tool-1',
  provider_type: 'PLATFORM',
  provider_ref: 'customer_search',
  environment: 'PRODUCTION',
  active: true,
  has_unpublished_changes: true,
  published_revision: 2
}
beforeEach(() => {
  vi.clearAllMocks()
  sessionStorage.clear()
  mocks.auth = reactive({ identityVersion: 1, user: { sub: 'platform-test' } })
  mocks.allowed.mockReturnValue(true)
  mocks.list.mockResolvedValue([deployment])
  mocks.create.mockImplementation(async (input) => ({ ...deployment, ...input }))
  mocks.update.mockResolvedValue(deployment)
  mocks.receipt.mockResolvedValue({ status: 'UNKNOWN', result: null })
  mocks.confirm.mockResolvedValue(true)
  mocks.publish.mockImplementation(async (id, revision) => {
    const prior = mocks.update.mock.results.at(-1)?.value ?? mocks.create.mock.results.at(-1)?.value
    const row = prior ? await prior : deployment
    return {
      ...row,
      id,
      revision,
      published_revision: (row.published_revision ?? 0) + 1,
      has_unpublished_changes: false
    }
  })
  mocks.agentList.mockResolvedValue([])
  mocks.mountOptions.mockResolvedValue({ tools: [{ id: 'tool-1', name: '查询客户' }] })
  mocks.createAgent.mockResolvedValue({ id: 'agent-1' })
  mocks.listModels.mockResolvedValue([])
  mocks.providers.mockResolvedValue({ providers: [] })
  mocks.getDefinition.mockResolvedValue({ defaultVersionId: 'version-1' })
  mocks.getVersion.mockResolvedValue({ descriptor: { tool_name: 'customer_search' } })
  mocks.listDefinitions.mockResolvedValue([])
  mocks.listSkillCatalog.mockResolvedValue([])
})

it('列表发布结果未知时仅在顶部显示一次错误，原诊断保留且不自动重发', async () => {
  const error = new HttpClientError({
    status: 503,
    userMessage: '发布结果待核实，请先读取当前部署状态',
    technicalDetails: 'supplier timeout detail'
  })
  const claimed = vi.spyOn(error, 'claimFeedback')
  mocks.publish.mockRejectedValueOnce(error)
  wrapper = mount(SystemAgentToolsPage, {
    attachTo: document.body,
    global: { directives: { permission: () => {} } }
  })
  await flushPromises()
  await wrapper
    .findAllComponents(ElButton)
    .find((button) => button.text() === '发布')!
    .trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('原工具部署操作结果未知')
  expect(document.body.querySelectorAll('.el-message--error')).toHaveLength(1)
  expect(document.body.querySelector('.el-message--error')?.textContent).toContain('发布结果待核实')
  expect(wrapper.text()).not.toContain('supplier timeout detail')
  expect(error.technicalDetails).toBe('supplier timeout detail')
  expect(claimed).toHaveBeenCalled()
  expect(mocks.publish).toHaveBeenCalledExactlyOnceWith('deployment-1', 3, expect.any(String))
})

it('切换账号后清空系统工具目录并忽略原账号迟到的读取结果', async () => {
  let complete!: (value: (typeof deployment)[]) => void
  mocks.list.mockResolvedValueOnce([deployment]).mockReturnValueOnce(
    new Promise((resolve) => {
      complete = resolve
    })
  )
  wrapper = mount(SystemAgentToolsPage, {
    attachTo: document.body,
    global: { directives: { permission: () => {} } }
  })
  await flushPromises()
  expect(wrapper.text()).toContain('客户查询生产部署')
  await wrapper
    .findAllComponents(ElButton)
    .find((item) => item.text() === '刷新')!
    .trigger('click')
  await flushPromises()
  mocks.auth.user = { sub: 'other' }
  mocks.auth.identityVersion++
  await flushPromises()
  expect((wrapper.findComponent(ElTable).props() as { data: unknown }).data).toEqual([])
  complete([deployment])
  await flushPromises()
  expect(wrapper.text()).not.toContain('客户查询生产部署')
  expect((wrapper.findComponent(ElTable).props() as { data: unknown }).data).toEqual([])
})

it('Skill 摘要目录直接显示默认版本，区分未设置和已有ID但缺版本标签，不读取完整版本', async () => {
  mocks.listSkillCatalog.mockResolvedValue([
    {
      id: 's1',
      name: '整理报告',
      capabilityKey: 'report',
      status: 'DRAFT',
      defaultVersionId: 'v1',
      defaultVersionKey: '2026.1'
    },
    {
      id: 's2',
      name: '核查报告',
      capabilityKey: 'review',
      status: 'DRAFT',
      defaultVersionId: null,
      defaultVersionKey: null
    },
    {
      id: 's3',
      name: '补充报告',
      capabilityKey: 'extra',
      status: 'DRAFT',
      defaultVersionId: 'v3',
      defaultVersionKey: null
    }
  ])
  wrapper = mount(SkillWorkbenchListPage, {
    global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } }
  })
  await flushPromises()
  expect(wrapper.text()).toContain('2026.1')
  expect(wrapper.text()).toContain('未设置')
  expect(wrapper.text()).toContain('尚未读取')
  expect(mocks.listSkillCatalog).toHaveBeenCalledTimes(1)
  expect(mocks.listDefinitions).not.toHaveBeenCalled()
  expect(mocks.getVersion).not.toHaveBeenCalled()
  mocks.listSkillCatalog.mockRejectedValueOnce(new Error('摘要刷新失败'))
  await wrapper
    .findAllComponents(ElButton)
    .find((item) => item.text() === '刷新')!
    .trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('整理报告')
  expect(wrapper.text()).toContain('2026.1')
  mocks.listSkillCatalog.mockResolvedValueOnce([
    {
      id: 's1',
      name: '整理报告',
      capabilityKey: 'report',
      status: 'DRAFT',
      defaultVersionId: 'v1',
      defaultVersionKey: '2026.2'
    }
  ])
  await wrapper
    .findAllComponents(ElButton)
    .find((item) => item.text() === '刷新')!
    .trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('2026.2')
  expect(mocks.getVersion).not.toHaveBeenCalled()
})

it('Skill 空目录提供一步新建动作，筛选及失败分别显示且可恢复', async () => {
  wrapper = mount(SkillWorkbenchListPage, {
    global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } }
  })
  await flushPromises()
  expect(wrapper.text()).toContain('导入 ZIP 包')
  expect(wrapper.find('.skill-empty').text()).toContain('新建技能')
  expect(
    wrapper.findAll('a').some((link) => link.attributes('to') === '/platform/capabilities')
  ).toBe(true)
  await wrapper.findComponent(ElInput).find('input').setValue('不存在')
  expect(wrapper.text()).toContain('没有匹配的技能')
  expect(wrapper.find('.skill-empty').text()).not.toContain('新建技能')
  await wrapper
    .findAllComponents(ElButton)
    .find((button) => button.text() === '清空筛选')!
    .trigger('click')
  mocks.listSkillCatalog.mockRejectedValueOnce(new Error('目录暂不可用'))
  await wrapper
    .findAllComponents(ElButton)
    .find((button) => button.text() === '刷新')!
    .trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('技能目录读取失败')
  expect(document.body.querySelector('.el-message--error')?.textContent).toContain('目录暂不可用')
  expect(wrapper.find('.skill-empty').text()).not.toContain('新建技能')
  expect(wrapper.text()).toContain('重新加载')
})

it('Skill 所有新建入口同时要求定义与草稿创建权限', async () => {
  wrapper = mount(SkillWorkbenchListPage, {
    global: {
      directives: {
        permission: createPermissionDirective(() => ref(['foundation.capabilities.read']))
      },
      stubs: { RouterLink: { template: '<a><slot /></a>' } }
    }
  })
  await flushPromises()
  expect(wrapper.text()).toContain('管理能力目录')
  expect(wrapper.find('.skill-empty').text()).not.toContain('新建技能')
})

it('同一工具部署重新编辑会核对当前发布配置，读取失败可重试且不误放行保存', async () => {
  wrapper = mount(SystemAgentToolsPage, {
    attachTo: document.body,
    global: { directives: { permission: () => {} } }
  })
  await flushPromises()
  const button = (text: string) =>
    wrapper!.findAllComponents(ElButton).find((value) => value.text() === text)!
  await button('编辑').trigger('click')
  await flushPromises()
  expect(mocks.getDefinition).toHaveBeenCalledTimes(1)
  await button('取消').trigger('click')
  await flushPromises()
  mocks.getDefinition.mockRejectedValueOnce(new Error('发布配置暂不可读'))
  await button('编辑').trigger('click')
  await flushPromises()
  expect(mocks.getDefinition).toHaveBeenCalledTimes(2)
  expect(button('保存草稿').props('disabled')).toBe(true)
  await button('重试读取工具').trigger('click')
  await flushPromises()
  expect(mocks.getDefinition).toHaveBeenCalledTimes(3)
  expect(button('保存草稿').props('disabled')).toBe(false)
})

it('部署编辑中可刷新工具目录及发布配置并保留手动名称', async () => {
  wrapper = mount(SystemAgentToolsPage, {
    attachTo: document.body,
    global: { directives: { permission: () => {} }, stubs: { RouterLink: true } }
  })
  await flushPromises()
  const button = (text: string) =>
    wrapper!.findAllComponents(ElButton).find((value) => value.text() === text)!
  await button('新增部署').trigger('click')
  await flushPromises()
  const field = (label: string) =>
    wrapper!.findAllComponents(ElFormItem).find((item) => item.props('label') === label)!
  field('工具（跟随当前发布）').getComponent(ElSelect).vm.$emit('update:modelValue', 'tool-1')
  await flushPromises()
  await field('部署名称').getComponent(ElInput).get('input').setValue('手动命名')
  mocks.mountOptions.mockRejectedValueOnce(new Error('工具选项读取失败'))
  await button('刷新工具选项').trigger('click')
  await flushPromises()
  expect(field('工具（跟随当前发布）').getComponent(ElSelect).props('modelValue')).toBe('tool-1')
  mocks.mountOptions.mockResolvedValueOnce({
    tools: [
      { id: 'tool-1', name: '查询客户' },
      { id: 'tool-2', name: '新工具' }
    ]
  })
  await button('刷新工具选项').trigger('click')
  await flushPromises()
  expect(field('部署名称').getComponent(ElInput).props('modelValue')).toBe('手动命名')
  expect(
    field('工具（跟随当前发布）')
      .getComponent(ElSelect)
      .findAllComponents({ name: 'ElOption' })
      .map((item) => item.props('value'))
  ).toContain('tool-2')
  mocks.getVersion.mockResolvedValueOnce({ descriptor: { tool_name: 'new_search' } })
  await button('重新读取工具发布配置').trigger('click')
  await flushPromises()
  expect(document.body.textContent).toContain('new_search')
  expect(field('部署名称').getComponent(ElInput).props('modelValue')).toBe('手动命名')
})

it('系统智能体默认生成可编辑的合法标识，未编辑关闭不确认，填写名称即可创建', async () => {
  wrapper = mount(SystemAgentsPage, {
    attachTo: document.body,
    global: { directives: { permission: () => {} } }
  })
  await flushPromises()
  const open = () =>
    wrapper!
      .findAllComponents(ElButton)
      .find((button) => button.text() === '新增智能体')!
      .trigger('click')
  await open()
  await flushPromises()
  const key = () =>
    wrapper!
      .findAllComponents(ElFormItem)
      .find((item) => item.props('label') === '唯一标识')!
      .getComponent(ElInput)
  expect(key().props('modelValue')).toMatch(/^agent-[a-z0-9-]+$/)
  expect(key().props('readonly')).not.toBe(true)
  await wrapper
    .findAllComponents(ElButton)
    .find((button) => button.text() === '取消')!
    .trigger('click')
  await flushPromises()
  expect(mocks.confirm).not.toHaveBeenCalled()
  await open()
  await flushPromises()
  const generated = key().props('modelValue')
  await wrapper
    .findAllComponents(ElFormItem)
    .find((item) => item.props('label') === '名称')!
    .getComponent(ElInput)
    .get('input')
    .setValue('报告整理助手')
  document.body
    .querySelector('form#agent-create-form')!
    .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
  await flushPromises()
  expect(mocks.createAgent).toHaveBeenCalledWith(
    expect.objectContaining({
      agent_key: generated,
      name: '报告整理助手',
      execution_mode: 'AGENT_RUN'
    })
  )
})

it('创建系统智能体时可一次填写模型和提示词', async () => {
  mocks.listModels.mockResolvedValue([
    { id: 'model-1', name: '可用模型', kind: 'LLM', enabled: true }
  ])
  wrapper = mount(SystemAgentsPage, {
    attachTo: document.body,
    global: { directives: { permission: () => {} } }
  })
  await flushPromises()
  await wrapper
    .findAllComponents(ElButton)
    .find((item) => item.text() === '新增智能体')!
    .trigger('click')
  await flushPromises()
  const field = (label: string) =>
    wrapper!.findAllComponents(ElFormItem).find((item) => item.props('label') === label)!
  await field('名称').getComponent(ElInput).get('input').setValue('报告助手')
  field('系统提示词（可选）').getComponent(ElInput).vm.$emit('update:modelValue', '整理报告')
  field('模型（可选）').getComponent(ElSelect).vm.$emit('update:modelValue', 'model-1')
  await flushPromises()
  document.body
    .querySelector('form#agent-create-form')!
    .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
  await flushPromises()
  expect(mocks.createAgent).toHaveBeenCalledWith(
    expect.objectContaining({
      name: '报告助手',
      system_prompt: '整理报告',
      model_config_id: 'model-1'
    })
  )
})

it('创建结果未知后保留请求身份，重新进入时只读核对', async () => {
  mocks.createAgent.mockRejectedValueOnce(new Error('连接中断'))
  wrapper = mount(SystemAgentsPage, {
    attachTo: document.body,
    global: { directives: { permission: () => {} } }
  })
  await flushPromises()
  await wrapper
    .findAllComponents(ElButton)
    .find((item) => item.text() === '新增智能体')!
    .trigger('click')
  await flushPromises()
  await wrapper
    .findAllComponents(ElFormItem)
    .find((item) => item.props('label') === '名称')!
    .getComponent(ElInput)
    .get('input')
    .setValue('待核对助手')
  document.body
    .querySelector('form#agent-create-form')!
    .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
  await flushPromises()
  const record = JSON.parse(
    sessionStorage.getItem('skillforge.system-agent-create:platform-test')!
  ) as { requestId: string }
  expect(wrapper.text()).toContain(record.requestId)
  wrapper.unmount()
  wrapper = mount(SystemAgentsPage, {
    attachTo: document.body,
    global: { directives: { permission: () => {} } }
  })
  await flushPromises()
  expect(wrapper.text()).toContain(record.requestId)
  mocks.createAgentReceipt
    .mockResolvedValueOnce(undefined)
    .mockResolvedValueOnce({ request_id: record.requestId, agent_id: 'agent-1' })
  await wrapper
    .findAllComponents(ElButton)
    .find((item) => item.text() === '核对创建结果')!
    .trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain(record.requestId)
  await wrapper
    .findAllComponents(ElButton)
    .find((item) => item.text() === '核对创建结果')!
    .trigger('click')
  await flushPromises()
  expect(sessionStorage.getItem('skillforge.system-agent-create:platform-test')).toBeNull()
  expect(mocks.createAgent).toHaveBeenCalledTimes(1)
})

it('工具部署名称跟随用户选择生成，手动命名后切换环境不覆盖', async () => {
  wrapper = mount(SystemAgentToolsPage, {
    attachTo: document.body,
    global: { directives: { permission: () => {} } }
  })
  await flushPromises()
  await wrapper
    .findAllComponents(ElButton)
    .find((button) => button.text() === '新增部署')!
    .trigger('click')
  await flushPromises()
  const field = (label: string) =>
    wrapper!.findAllComponents(ElFormItem).find((item) => item.props('label') === label)!
  field('工具（跟随当前发布）').getComponent(ElSelect).vm.$emit('update:modelValue', 'tool-1')
  await flushPromises()
  expect(field('部署名称').getComponent(ElInput).props('modelValue')).toBe('查询客户 · 测试')
  field('环境').getComponent(ElSelect).vm.$emit('update:modelValue', 'PRODUCTION')
  await flushPromises()
  expect(field('部署名称').getComponent(ElInput).props('modelValue')).toBe('查询客户 · 生产')
  await field('部署名称').getComponent(ElInput).get('input').setValue('机构客户查询')
  field('环境').getComponent(ElSelect).vm.$emit('update:modelValue', 'TEST')
  await flushPromises()
  expect(field('部署名称').getComponent(ElInput).props('modelValue')).toBe('机构客户查询')
})

it('选定提供方后自动采用唯一可配置服务的真实连接信息，多个服务保留显式选择', async () => {
  const service: ModelProviderService = {
    service_id: 'api',
    display_name: '标准API',
    profile_version: '1',
    availability: 'SUPPORTED',
    endpoint: 'https://model.example.com/v1',
    custom_endpoint: false,
    region: 'CHINA_MAINLAND',
    model_kinds: ['LLM'],
    protocols: [
      {
        protocol: 'openai-chat-completions',
        dialect_version: '1',
        authentication_schemes: ['bearer-api-key'],
        capabilities: {
          json_object: 'SUPPORTED',
          strict_json_schema: 'UNSUPPORTED',
          tool_calling: 'SUPPORTED',
          streaming: 'SUPPORTED',
          reasoning: 'UNSUPPORTED',
          user_images: 'UNVERIFIED',
          tool_images: 'UNVERIFIED'
        },
        image_policy_defaults: null
      }
    ]
  }
  mocks.providers.mockResolvedValue({
    providers: [
      { provider_id: 'single', display_name: '单服务', category: 'CLOUD', services: [service] },
      {
        provider_id: 'multiple',
        display_name: '多服务',
        category: 'CLOUD',
        services: [service, { ...service, service_id: 'second' }]
      }
    ]
  })
  wrapper = mount(ModelDetailPage, {
    global: { directives: { permission: () => {} }, stubs: { RouterLink: true } }
  })
  await flushPromises()
  const field = (label: string) =>
    wrapper!.findAllComponents(ElFormItem).find((item) => item.props('label') === label)!
  const provider = field('提供方').getComponent(ElSelect)
  provider.vm.$emit('update:modelValue', 'single')
  provider.vm.$emit('change', 'single')
  await flushPromises()
  expect(field('提供方服务').getComponent(ElSelect).props('modelValue')).toBe('api')
  expect(field('服务地址').getComponent(ElInput).props('modelValue')).toBe(service.endpoint)
  expect(wrapper.text()).toContain('连接协议：openai-chat-completions · 认证：bearer-api-key')
  expect(field('模型标识').getComponent(ElSelect).props('modelValue')).toBe('')
  provider.vm.$emit('update:modelValue', 'multiple')
  provider.vm.$emit('change', 'multiple')
  await flushPromises()
  expect(field('提供方服务').getComponent(ElSelect).props('modelValue')).toBe(null)
  const kind = field('类型').getComponent(ElSelect)
  kind.vm.$emit('update:modelValue', 'RERANK')
  kind.vm.$emit('change', 'RERANK')
  await flushPromises()
  expect(field('重排路径').getComponent(ElInput).props('modelValue')).toBe('/rerank')
})
afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  document.body.innerHTML = ''
})

it('生产工具部署保存草稿不重复确认，发布时确认具体环境和对象', async () => {
  wrapper = mount(SystemAgentToolsPage, {
    attachTo: document.body,
    global: { directives: { permission: () => {} } }
  })
  await flushPromises()
  await wrapper
    .findAllComponents(ElButton)
    .find((button) => button.text() === '新增部署')!
    .trigger('click')
  await flushPromises()
  const fields = wrapper.findAllComponents(ElFormItem)
  const field = (label: string) => fields.find((item) => item.props('label') === label)!
  await field('部署名称').getComponent(ElInput).get('input').setValue('客户查询生产部署')
  field('工具（跟随当前发布）').getComponent(ElSelect).vm.$emit('update:modelValue', 'tool-1')
  field('环境').getComponent(ElSelect).vm.$emit('update:modelValue', 'PRODUCTION')
  await flushPromises()
  document.body
    .querySelector('form#tool-deployment-form')!
    .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
  await flushPromises()
  expect(mocks.create).toHaveBeenCalledWith(
    expect.objectContaining({
      environment: 'PRODUCTION',
      provider_ref: 'customer_search'
    }),
    expect.any(String)
  )
  expect(mocks.confirm).not.toHaveBeenCalled()
  await wrapper
    .findAllComponents(ElButton)
    .find((button) => button.text() === '发布')!
    .trigger('click')
  await flushPromises()
  expect(mocks.confirm).toHaveBeenCalledWith(
    expect.objectContaining({
      content: expect.stringContaining('生产环境的“客户查询生产部署”'),
      danger: true
    })
  )
  expect(mocks.publish).toHaveBeenCalledWith('deployment-1', 3, expect.any(String))
})

it('同一抽屉以保存回执修订继续发布，取消后保留草稿且不重复创建', async () => {
  mocks.confirm.mockResolvedValueOnce(false).mockResolvedValueOnce(true)
  wrapper = mount(SystemAgentToolsPage, {
    attachTo: document.body,
    global: { directives: { permission: () => {} } }
  })
  await flushPromises()
  const button = (text: string) =>
    wrapper!.findAllComponents(ElButton).find((item) => item.text() === text)!
  await button('新增部署').trigger('click')
  const field = (label: string) =>
    wrapper!.findAllComponents(ElFormItem).find((item) => item.props('label') === label)!
  field('工具（跟随当前发布）').getComponent(ElSelect).vm.$emit('update:modelValue', 'tool-1')
  field('环境').getComponent(ElSelect).vm.$emit('update:modelValue', 'PRODUCTION')
  await flushPromises()
  await field('部署名称').getComponent(ElInput).get('input').setValue('客户查询生产部署')
  await button('保存并继续发布').trigger('click')
  await flushPromises()
  expect(mocks.create).toHaveBeenCalledTimes(1)
  expect(mocks.publish).not.toHaveBeenCalled()
  expect(document.body.textContent).toContain('草稿修订 3')
  expect(mocks.confirm).toHaveBeenCalledWith(
    expect.objectContaining({
      content: expect.stringContaining('生产环境的“客户查询生产部署”'),
      danger: true
    })
  )
  await button('继续发布').trigger('click')
  await flushPromises()
  expect(mocks.create).toHaveBeenCalledTimes(1)
  expect(mocks.publish).toHaveBeenCalledExactlyOnceWith('deployment-1', 3, expect.any(String))
})

it('嵌入模式复用工具部署抽屉，固定定义并只在确定发布后返回回执', async () => {
  wrapper = mount(SystemAgentToolsPage, {
    props: { toolDefinitionId: 'tool-1' },
    attachTo: document.body,
    global: { directives: { permission: () => {} } }
  })
  await flushPromises()
  const field = (label: string) =>
    wrapper!.findAllComponents(ElFormItem).find((item) => item.props('label') === label)!
  const button = (text: string) =>
    wrapper!.findAllComponents(ElButton).find((item) => item.text() === text)!
  expect(field('工具（跟随当前发布）').getComponent(ElSelect).props('modelValue')).toBe('tool-1')
  expect(field('工具（跟随当前发布）').getComponent(ElSelect).props('disabled')).toBe(true)
  expect(field('部署名称').getComponent(ElInput).props('modelValue')).toBe('查询客户 · 测试')
  await button('保存并继续发布').trigger('click')
  await flushPromises()
  expect(mocks.create).toHaveBeenCalledTimes(1)
  expect(mocks.publish).toHaveBeenCalledExactlyOnceWith('deployment-1', 3, expect.any(String))
  expect(wrapper.emitted('published')?.[0]?.[0]).toMatchObject({
    id: 'deployment-1',
    has_unpublished_changes: false
  })
  await button('关闭').trigger('click')
  await flushPromises()
  expect(wrapper.emitted('closed')).toHaveLength(1)
})

it('嵌入模式未知结果关闭确认取消时保留抽屉，明确保留后才通知父页', async () => {
  mocks.create.mockRejectedValueOnce(new HttpClientError({ status: 0, userMessage: '网络中断' }))
  mocks.confirm.mockResolvedValueOnce(false).mockResolvedValueOnce(true)
  wrapper = mount(SystemAgentToolsPage, {
    props: { toolDefinitionId: 'tool-1' },
    attachTo: document.body,
    global: { directives: { permission: () => {} } }
  })
  await flushPromises()
  const button = (text: string) =>
    wrapper!.findAllComponents(ElButton).find((item) => item.text() === text)!
  await button('保存并继续发布').trigger('click')
  await flushPromises()
  expect(document.body.textContent).toContain('结果未知')
  await button('保留未知结果并关闭').trigger('click')
  await flushPromises()
  expect(wrapper.emitted('closed')).toBeUndefined()
  expect(document.body.textContent).toContain('结果未知')
  await button('保留未知结果并关闭').trigger('click')
  await flushPromises()
  expect(wrapper.emitted('closed')).toHaveLength(1)
  expect(sessionStorage.getItem('skillforge.system-tool-unknown:platform-test')).not.toBeNull()
  expect(mocks.create).toHaveBeenCalledTimes(1)
})

it('嵌入模式预填工具和生成名称不算用户编辑，可直接关闭', async () => {
  wrapper = mount(SystemAgentToolsPage, {
    props: { toolDefinitionId: 'tool-1' },
    attachTo: document.body,
    global: { directives: { permission: () => {} } }
  })
  await flushPromises()
  await wrapper
    .findAllComponents(ElButton)
    .find((item) => item.text() === '取消')!
    .trigger('click')
  await flushPromises()
  expect(mocks.confirm).not.toHaveBeenCalled()
  expect(wrapper.emitted('closed')).toHaveLength(1)
})

it('嵌入模式迟到工具配置不覆盖用户编辑，关闭仍提示未保存内容', async () => {
  let resolve!: (value: unknown) => void
  mocks.getVersion.mockReturnValueOnce(
    new Promise((yes) => {
      resolve = yes
    })
  )
  mocks.confirm.mockResolvedValueOnce(false)
  wrapper = mount(SystemAgentToolsPage, {
    props: { toolDefinitionId: 'tool-1' },
    attachTo: document.body,
    global: { directives: { permission: () => {} } }
  })
  await flushPromises()
  const name = wrapper
    .findAllComponents(ElFormItem)
    .find((item) => item.props('label') === '部署名称')!
    .getComponent(ElInput)
  await name.get('input').setValue('用户自定义部署')
  resolve({ descriptor: { tool_name: 'customer_search' } })
  await flushPromises()
  expect(name.props('modelValue')).toBe('用户自定义部署')
  await wrapper
    .findAllComponents(ElButton)
    .find((item) => item.text() === '取消')!
    .trigger('click')
  await flushPromises()
  expect(mocks.confirm).toHaveBeenCalledWith(expect.objectContaining({ title: '关闭表单' }))
  expect(wrapper.emitted('closed')).toBeUndefined()
})

it('仅有保存权限不出现继续发布，保存后保留确定回执', async () => {
  mocks.allowed.mockImplementation(
    (permission: string) => permission !== 'foundation.system_agent_tools.publish'
  )
  wrapper = mount(SystemAgentToolsPage, {
    attachTo: document.body,
    global: { directives: { permission: () => {} } }
  })
  await flushPromises()
  const button = (text: string) =>
    wrapper!.findAllComponents(ElButton).find((item) => item.text() === text)
  await button('新增部署')!.trigger('click')
  wrapper
    .findAllComponents(ElFormItem)
    .find((item) => item.props('label') === '工具（跟随当前发布）')!
    .getComponent(ElSelect)
    .vm.$emit('update:modelValue', 'tool-1')
  await flushPromises()
  expect(button('保存并继续发布')).toBeUndefined()
  document.body
    .querySelector('form#tool-deployment-form')!
    .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
  await flushPromises()
  expect(mocks.create).toHaveBeenCalledTimes(1)
  expect(mocks.publish).not.toHaveBeenCalled()
  expect(document.body.textContent).toContain('草稿修订 3')
})

it.each(['保存', '发布'])('%s结果未知保留原身份并阻止再次提交', async (stage) => {
  if (stage === '保存')
    mocks.create.mockRejectedValueOnce(
      new HttpClientError({ status: 503, userMessage: '连接中断' })
    )
  else
    mocks.publish.mockRejectedValueOnce(
      new HttpClientError({ status: 409, userMessage: '修订冲突' })
    )
  wrapper = mount(SystemAgentToolsPage, {
    attachTo: document.body,
    global: { directives: { permission: () => {} } }
  })
  await flushPromises()
  const button = (text: string) =>
    wrapper!.findAllComponents(ElButton).find((item) => item.text() === text)!
  await button('新增部署').trigger('click')
  wrapper
    .findAllComponents(ElFormItem)
    .find((item) => item.props('label') === '工具（跟随当前发布）')!
    .getComponent(ElSelect)
    .vm.$emit('update:modelValue', 'tool-1')
  await flushPromises()
  await button('保存并继续发布').trigger('click')
  await flushPromises()
  expect(document.body.textContent).toContain('结果未知')
  expect(button(stage === '保存' ? '保存并继续发布' : '继续发布').props('disabled')).toBe(true)
  if (stage === '发布')
    expect(document.body.textContent).toContain('原部署：deployment-1，原修订：3')
  await button('只读刷新目录').trigger('click')
  await flushPromises()
  expect(document.body.textContent).toContain('结果未知')
  expect(mocks.create).toHaveBeenCalledTimes(1)
  expect(mocks.publish).toHaveBeenCalledTimes(stage === '发布' ? 1 : 0)
})

it('专用确定拒绝保留已保存草稿，不把拒绝当作未知结果', async () => {
  mocks.publish.mockRejectedValueOnce(
    new HttpClientError({
      status: 409,
      code: 'SYSTEM_AGENT_TOOL_WRITE_REJECTED',
      userMessage: '草稿修订已变化'
    })
  )
  wrapper = mount(SystemAgentToolsPage, {
    attachTo: document.body,
    global: { directives: { permission: () => {} } }
  })
  await flushPromises()
  const button = (text: string) =>
    wrapper!.findAllComponents(ElButton).find((item) => item.text() === text)!
  await button('新增部署').trigger('click')
  wrapper
    .findAllComponents(ElFormItem)
    .find((item) => item.props('label') === '工具（跟随当前发布）')!
    .getComponent(ElSelect)
    .vm.$emit('update:modelValue', 'tool-1')
  await flushPromises()
  await button('保存并继续发布').trigger('click')
  await flushPromises()
  expect(document.body.textContent).toContain('草稿修订 3')
  expect(document.body.textContent).not.toContain('结果未知')
  expect(button('关闭').props('disabled')).toBe(false)
  expect(mocks.create).toHaveBeenCalledTimes(1)
  expect(mocks.publish).toHaveBeenCalledTimes(1)
})

it('编辑保存后使用新修订发布，列表刷新失败不丢失保存回执', async () => {
  mocks.update.mockResolvedValue({ ...deployment, revision: 4 })
  wrapper = mount(SystemAgentToolsPage, {
    attachTo: document.body,
    global: { directives: { permission: () => {} } }
  })
  await flushPromises()
  const button = (text: string) =>
    wrapper!.findAllComponents(ElButton).find((item) => item.text() === text)!
  await button('编辑').trigger('click')
  await flushPromises()
  mocks.list.mockRejectedValue(new Error('目录刷新失败'))
  await button('保存并继续发布').trigger('click')
  await flushPromises()
  expect(mocks.update).toHaveBeenCalledWith(
    'deployment-1',
    3,
    expect.anything(),
    expect.any(String)
  )
  expect(mocks.publish).toHaveBeenCalledExactlyOnceWith('deployment-1', 4, expect.any(String))
  expect(document.body.textContent).toContain('列表刷新失败')
  expect(button('重新读取目录').exists()).toBe(true)
})

it('保存回执不完整保留 UNKNOWN，明确关闭后重进仍禁止重复创建', async () => {
  mocks.create.mockResolvedValueOnce(null)
  wrapper = mount(SystemAgentToolsPage, {
    attachTo: document.body,
    global: { directives: { permission: () => {} } }
  })
  await flushPromises()
  const button = (text: string) =>
    wrapper!.findAllComponents(ElButton).find((item) => item.text() === text)!
  await button('新增部署').trigger('click')
  wrapper
    .findAllComponents(ElFormItem)
    .find((item) => item.props('label') === '工具（跟随当前发布）')!
    .getComponent(ElSelect)
    .vm.$emit('update:modelValue', 'tool-1')
  await flushPromises()
  await button('保存并继续发布').trigger('click')
  await flushPromises()
  expect(document.body.textContent).toContain('结果未知')
  await button('保留未知结果并关闭').trigger('click')
  await flushPromises()
  expect(document.body.textContent).toContain('原工具部署操作结果未知')
  wrapper.unmount()
  wrapper = mount(SystemAgentToolsPage, {
    attachTo: document.body,
    global: { directives: { permission: () => {} } }
  })
  await flushPromises()
  expect(document.body.textContent).toContain('原工具部署操作结果未知')
  expect(button('新增部署').props('disabled')).toBe(true)
  expect(mocks.create).toHaveBeenCalledTimes(1)
  expect(mocks.publish).not.toHaveBeenCalled()
})

it('列表发布结果未知保留原身份，不能重复提交', async () => {
  mocks.publish.mockRejectedValueOnce(new HttpClientError({ status: 0, userMessage: '网络中断' }))
  wrapper = mount(SystemAgentToolsPage, {
    attachTo: document.body,
    global: { directives: { permission: () => {} } }
  })
  await flushPromises()
  const button = (text: string) =>
    wrapper!.findAllComponents(ElButton).find((item) => item.text() === text)!
  await button('发布').trigger('click')
  await flushPromises()
  expect(document.body.textContent).toContain('原工具部署操作结果未知')
  await button('发布').trigger('click')
  expect(mocks.publish).toHaveBeenCalledTimes(1)
  await button('查看原操作并核对').trigger('click')
  await flushPromises()
  expect(document.body.textContent).toContain('原部署：deployment-1，原修订：3')
})

it('发布结果未知时只读核对原回执，未查到保持锁定，成功后解锁', async () => {
  mocks.publish.mockRejectedValueOnce(new HttpClientError({ status: 0, userMessage: '网络中断' }))
  wrapper = mount(SystemAgentToolsPage, {
    attachTo: document.body,
    global: { directives: { permission: () => {} } }
  })
  await flushPromises()
  const button = (text: string) =>
    wrapper!.findAllComponents(ElButton).find((item) => item.text() === text)!
  await button('发布').trigger('click')
  await flushPromises()
  const pending = JSON.parse(
    sessionStorage.getItem('skillforge.system-tool-unknown:platform-test')!
  )
  await button('查看原操作并核对').trigger('click')
  await flushPromises()
  await button('核对原操作').trigger('click')
  await flushPromises()
  expect(mocks.receipt).toHaveBeenCalledWith(pending.operationId)
  expect(sessionStorage.getItem('skillforge.system-tool-unknown:platform-test')).not.toBeNull()
  mocks.receipt.mockResolvedValueOnce({
    status: 'SUCCEEDED',
    result: { ...deployment, published_revision: 3, has_unpublished_changes: false }
  })
  await button('核对原操作').trigger('click')
  await flushPromises()
  expect(sessionStorage.getItem('skillforge.system-tool-unknown:platform-test')).toBeNull()
  expect(mocks.publish).toHaveBeenCalledTimes(1)
})

it('创建回执核实后继续编辑原部署，不重复创建', async () => {
  const input = {
    name: deployment.name,
    tool_definition_id: deployment.tool_definition_id,
    provider_type: deployment.provider_type,
    provider_ref: deployment.provider_ref,
    environment: deployment.environment,
    active: deployment.active
  }
  sessionStorage.setItem(
    'skillforge.system-tool-unknown:platform-test',
    JSON.stringify({
      operationId: 'original-create',
      serverTracked: true,
      stage: '创建草稿',
      actor: 'platform-test',
      input
    })
  )
  mocks.receipt.mockResolvedValueOnce({ status: 'SUCCEEDED', result: deployment })
  mocks.update.mockResolvedValueOnce({ ...deployment, revision: 4 })
  wrapper = mount(SystemAgentToolsPage, {
    attachTo: document.body,
    global: { directives: { permission: () => {} } }
  })
  await flushPromises()
  const button = (text: string) =>
    wrapper!.findAllComponents(ElButton).find((item) => item.text() === text)!
  await button('查看原操作并核对').trigger('click')
  await flushPromises()
  await button('核对原操作').trigger('click')
  await flushPromises()
  document.body
    .querySelector('form#tool-deployment-form')!
    .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
  await flushPromises()
  expect(mocks.create).not.toHaveBeenCalled()
  expect(mocks.update).toHaveBeenCalledWith('deployment-1', 3, input, expect.any(String))
})

it('核对回执期间切换账号不会将旧账号结果带入新账号', async () => {
  mocks.publish.mockRejectedValueOnce(new HttpClientError({ status: 0, userMessage: '网络中断' }))
  let complete!: (value: unknown) => void
  mocks.receipt.mockReturnValueOnce(
    new Promise((resolve) => {
      complete = resolve
    })
  )
  wrapper = mount(SystemAgentToolsPage, {
    attachTo: document.body,
    global: { directives: { permission: () => {} } }
  })
  await flushPromises()
  const button = (text: string) =>
    wrapper!.findAllComponents(ElButton).find((item) => item.text() === text)!
  await button('发布').trigger('click')
  await flushPromises()
  await button('查看原操作并核对').trigger('click')
  await flushPromises()
  await button('核对原操作').trigger('click')
  mocks.auth.user = { sub: 'another-user' }
  mocks.auth.identityVersion++
  complete({
    status: 'SUCCEEDED',
    result: { ...deployment, published_revision: 3, has_unpublished_changes: false }
  })
  await flushPromises()
  expect(sessionStorage.getItem('skillforge.system-tool-unknown:platform-test')).not.toBeNull()
  expect(sessionStorage.getItem('skillforge.system-tool-unknown:another-user:receipt')).toBeNull()
})

it('旧版未知操作没有服务端标识时不提供原回执查询', async () => {
  sessionStorage.setItem(
    'skillforge.system-tool-unknown:platform-test',
    JSON.stringify({ operationId: 'old', stage: '发布', actor: 'platform-test' })
  )
  wrapper = mount(SystemAgentToolsPage, {
    attachTo: document.body,
    global: { directives: { permission: () => {} } }
  })
  await flushPromises()
  await wrapper
    .findAllComponents(ElButton)
    .find((item) => item.text() === '查看原操作并核对')!
    .trigger('click')
  await flushPromises()
  expect(document.body.textContent).toContain('此操作未记录服务端标识')
  expect(wrapper.findAllComponents(ElButton).some((item) => item.text() === '核对原操作')).toBe(
    false
  )
  expect(mocks.receipt).not.toHaveBeenCalled()
})

it('发布成功信封缺少完整回执时保留原请求并禁止重发', async () => {
  mocks.publish.mockResolvedValueOnce({ ...deployment, id: 'other' })
  wrapper = mount(SystemAgentToolsPage, {
    attachTo: document.body,
    global: { directives: { permission: () => {} } }
  })
  await flushPromises()
  const button = (text: string) =>
    wrapper!.findAllComponents(ElButton).find((item) => item.text() === text)!
  await button('发布').trigger('click')
  await flushPromises()
  expect(document.body.textContent).toContain('原工具部署操作结果未知')
  expect(
    JSON.parse(sessionStorage.getItem('skillforge.system-tool-unknown:platform-test')!)
  ).toMatchObject({ stage: '发布', id: 'deployment-1', revision: 3 })
  await button('发布').trigger('click')
  expect(mocks.publish).toHaveBeenCalledTimes(1)
})

it('保存成功回执迟于切换账号归档原账号，不进入新账号页面', async () => {
  let complete!: (value: typeof deployment) => void
  mocks.create.mockReturnValueOnce(
    new Promise((resolve) => {
      complete = resolve
    })
  )
  wrapper = mount(SystemAgentToolsPage, {
    attachTo: document.body,
    global: { directives: { permission: () => {} } }
  })
  await flushPromises()
  const button = (text: string) =>
    wrapper!.findAllComponents(ElButton).find((item) => item.text() === text)!
  await button('新增部署').trigger('click')
  wrapper
    .findAllComponents(ElFormItem)
    .find((item) => item.props('label') === '工具（跟随当前发布）')!
    .getComponent(ElSelect)
    .vm.$emit('update:modelValue', 'tool-1')
  await flushPromises()
  await button('保存并继续发布').trigger('click')
  await flushPromises()
  mocks.auth.user = { sub: 'other' }
  mocks.auth.identityVersion++
  complete({ ...deployment, ...mocks.create.mock.calls[0]![0] })
  await flushPromises()
  expect(document.body.textContent).not.toContain('草稿修订 3')
  expect(mocks.publish).not.toHaveBeenCalled()
  mocks.auth.user = { sub: 'platform-test' }
  mocks.auth.identityVersion++
  await flushPromises()
  expect(document.body.textContent).toContain('原账号的创建草稿已收到确定回执')
  expect(document.body.textContent).not.toContain('原工具部署操作结果未知')
})

it.each(['成功', '确定拒绝'])(
  '账号 A 切到 B 再切回 A 后，迟到%s刷新原账号结果',
  async (outcome) => {
    let resolve!: (value: typeof deployment) => void
    let reject!: (reason: unknown) => void
    mocks.create.mockReturnValueOnce(
      new Promise((yes, no) => {
        resolve = yes
        reject = no
      })
    )
    wrapper = mount(SystemAgentToolsPage, {
      attachTo: document.body,
      global: { directives: { permission: () => {} } }
    })
    await flushPromises()
    const button = (text: string) =>
      wrapper!.findAllComponents(ElButton).find((item) => item.text() === text)!
    await button('新增部署').trigger('click')
    wrapper
      .findAllComponents(ElFormItem)
      .find((item) => item.props('label') === '工具（跟随当前发布）')!
      .getComponent(ElSelect)
      .vm.$emit('update:modelValue', 'tool-1')
    await flushPromises()
    await button('保存并继续发布').trigger('click')
    await flushPromises()
    mocks.auth.user = { sub: 'other' }
    mocks.auth.identityVersion++
    await flushPromises()
    expect(document.body.textContent).not.toContain('原工具部署操作结果未知')
    expect(document.body.textContent).not.toContain('原账号的创建草稿')
    mocks.auth.user = { sub: 'platform-test' }
    mocks.auth.identityVersion++
    await flushPromises()
    expect(document.body.textContent).toContain('原工具部署操作结果未知')
    if (outcome === '成功') resolve({ ...deployment, ...mocks.create.mock.calls[0]![0] })
    else
      reject(
        new HttpClientError({
          status: 409,
          code: 'SYSTEM_AGENT_TOOL_WRITE_REJECTED',
          userMessage: '修订冲突'
        })
      )
    await flushPromises()
    expect(document.body.textContent).not.toContain('原工具部署操作结果未知')
    expect(document.body.textContent).toContain(
      outcome === '成功' ? '原账号的创建草稿已收到确定回执' : '原账号的创建草稿已确定拒绝'
    )
    mocks.auth.user = { sub: 'other' }
    mocks.auth.identityVersion++
    await flushPromises()
    expect(document.body.textContent).not.toContain('原账号的创建草稿')
    expect(mocks.create).toHaveBeenCalledTimes(1)
    expect(mocks.publish).not.toHaveBeenCalled()
  }
)

it('写请求派发前记录原身份及输入，重新进入时阻止重复创建', async () => {
  let complete!: (value: typeof deployment) => void
  mocks.create.mockReturnValueOnce(
    new Promise((resolve) => {
      complete = resolve
    })
  )
  wrapper = mount(SystemAgentToolsPage, {
    attachTo: document.body,
    global: { directives: { permission: () => {} } }
  })
  await flushPromises()
  const button = (text: string) =>
    wrapper!.findAllComponents(ElButton).find((item) => item.text() === text)!
  await button('新增部署').trigger('click')
  wrapper
    .findAllComponents(ElFormItem)
    .find((item) => item.props('label') === '工具（跟随当前发布）')!
    .getComponent(ElSelect)
    .vm.$emit('update:modelValue', 'tool-1')
  await flushPromises()
  await button('保存并继续发布').trigger('click')
  await flushPromises()
  expect(
    JSON.parse(sessionStorage.getItem('skillforge.system-tool-unknown:platform-test')!)
  ).toMatchObject({
    actor: 'platform-test',
    stage: '创建草稿',
    input: { tool_definition_id: 'tool-1' }
  })
  wrapper.unmount()
  wrapper = mount(SystemAgentToolsPage, {
    attachTo: document.body,
    global: { directives: { permission: () => {} } }
  })
  await flushPromises()
  expect(document.body.textContent).toContain('原工具部署操作结果未知')
  expect(button('新增部署').props('disabled')).toBe(true)
  complete({ ...deployment, ...mocks.create.mock.calls[0]![0] })
  await flushPromises()
})

it('原记录损坏或存储不可写时显示阻断且不派发写请求', async () => {
  sessionStorage.setItem('skillforge.system-tool-unknown:platform-test', '{invalid')
  wrapper = mount(SystemAgentToolsPage, {
    attachTo: document.body,
    global: { directives: { permission: () => {} } }
  })
  await flushPromises()
  expect(document.body.textContent).toContain('原操作记录无法读取或已损坏')
  expect(
    wrapper
      .findAllComponents(ElButton)
      .find((item) => item.text() === '新增部署')!
      .props('disabled')
  ).toBe(true)
  wrapper.unmount()
  sessionStorage.clear()
  wrapper = mount(SystemAgentToolsPage, {
    attachTo: document.body,
    global: { directives: { permission: () => {} } }
  })
  await flushPromises()
  const button = (text: string) =>
    wrapper!.findAllComponents(ElButton).find((item) => item.text() === text)!
  await button('新增部署').trigger('click')
  wrapper
    .findAllComponents(ElFormItem)
    .find((item) => item.props('label') === '工具（跟随当前发布）')!
    .getComponent(ElSelect)
    .vm.$emit('update:modelValue', 'tool-1')
  await flushPromises()
  const spy = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
    throw Error('storage denied')
  })
  await button('保存并继续发布').trigger('click')
  await flushPromises()
  expect(document.body.textContent).toContain('无法保存原操作记录')
  expect(mocks.create).not.toHaveBeenCalled()
  spy.mockRestore()
  wrapper.unmount()
  const readSpy = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
    throw Error('storage denied')
  })
  wrapper = mount(SystemAgentToolsPage, {
    attachTo: document.body,
    global: { directives: { permission: () => {} } }
  })
  await flushPromises()
  expect(document.body.textContent).toContain('原操作记录无法读取或已损坏')
  expect(
    wrapper
      .findAllComponents(ElButton)
      .find((item) => item.text() === '新增部署')!
      .props('disabled')
  ).toBe(true)
  readSpy.mockRestore()
})

it('账号切换后记录损坏或读取失败不显示原账号回执', async () => {
  sessionStorage.setItem(
    'skillforge.system-tool-unknown:platform-test:receipt',
    JSON.stringify({
      pending: { operationId: 'original', stage: '创建草稿', actor: 'platform-test' },
      status: 'succeeded',
      result: deployment
    })
  )
  sessionStorage.setItem('skillforge.system-tool-unknown:other', '{invalid')
  wrapper = mount(SystemAgentToolsPage, {
    attachTo: document.body,
    global: { directives: { permission: () => {} } }
  })
  await flushPromises()
  expect(document.body.textContent).toContain('原账号的创建草稿已收到确定回执')
  mocks.auth.user = { sub: 'other' }
  mocks.auth.identityVersion++
  await flushPromises()
  expect(document.body.textContent).toContain('原操作记录无法读取或已损坏')
  expect(document.body.textContent).not.toContain('原账号的创建草稿已收到确定回执')
  const spy = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
    throw Error('denied')
  })
  mocks.auth.user = { sub: 'third' }
  mocks.auth.identityVersion++
  await flushPromises()
  expect(document.body.textContent).toContain('原操作记录无法读取或已损坏')
  expect(document.body.textContent).not.toContain('原账号的创建草稿已收到确定回执')
  spy.mockRestore()
})

it('确定保存回执归档失败仍显示成功并阻止后续发布，恢复存储后继续', async () => {
  wrapper = mount(SystemAgentToolsPage, {
    attachTo: document.body,
    global: { directives: { permission: () => {} } }
  })
  await flushPromises()
  const button = (text: string) =>
    wrapper!.findAllComponents(ElButton).find((item) => item.text() === text)!
  await button('新增部署').trigger('click')
  wrapper
    .findAllComponents(ElFormItem)
    .find((item) => item.props('label') === '工具（跟随当前发布）')!
    .getComponent(ElSelect)
    .vm.$emit('update:modelValue', 'tool-1')
  await flushPromises()
  const original = Storage.prototype.setItem
  const spy = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(function (
    this: Storage,
    key,
    value
  ) {
    if (key.endsWith(':receipt')) throw Error('storage denied')
    return original.call(this, key, value)
  })
  await button('保存并继续发布').trigger('click')
  await flushPromises()
  expect(document.body.textContent).toContain('已收到确定结果，但无法保存或清理本地记录')
  expect(document.body.textContent).not.toContain('结果未知')
  expect(mocks.create).toHaveBeenCalledTimes(1)
  expect(mocks.publish).not.toHaveBeenCalled()
  spy.mockRestore()
  await button('重试处理原操作记录').trigger('click')
  await flushPromises()
  expect(button('继续发布').props('disabled')).toBe(false)
  await button('继续发布').trigger('click')
  await flushPromises()
  expect(mocks.create).toHaveBeenCalledTimes(1)
  expect(mocks.publish).toHaveBeenCalledTimes(1)
})

it.each(['保存', '发布'])(
  '%s确定拒绝但本地清理失败时保留拒绝事实，刷新后不变为 UNKNOWN',
  async (stage) => {
    const rejection = new HttpClientError({
      status: 409,
      code: 'SYSTEM_AGENT_TOOL_WRITE_REJECTED',
      userMessage: '修订冲突'
    })
    if (stage === '保存') mocks.create.mockRejectedValueOnce(rejection)
    else mocks.publish.mockRejectedValueOnce(rejection)
    wrapper = mount(SystemAgentToolsPage, {
      attachTo: document.body,
      global: { directives: { permission: () => {} } }
    })
    await flushPromises()
    const button = (text: string) =>
      wrapper!.findAllComponents(ElButton).find((item) => item.text() === text)!
    await button('新增部署').trigger('click')
    wrapper
      .findAllComponents(ElFormItem)
      .find((item) => item.props('label') === '工具（跟随当前发布）')!
      .getComponent(ElSelect)
      .vm.$emit('update:modelValue', 'tool-1')
    await flushPromises()
    const original = Storage.prototype.removeItem
    let removals = 0
    const spy = vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(function (
      this: Storage,
      key
    ) {
      if (++removals === (stage === '保存' ? 1 : 2)) throw Error('storage denied')
      return original.call(this, key)
    })
    await button('保存并继续发布').trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('已收到确定结果，但无法保存或清理本地记录')
    expect(document.body.textContent).not.toContain('结果未知')
    wrapper.unmount()
    spy.mockRestore()
    wrapper = mount(SystemAgentToolsPage, {
      attachTo: document.body,
      global: { directives: { permission: () => {} } }
    })
    await flushPromises()
    expect(document.body.textContent).toContain('已确定拒绝')
    expect(document.body.textContent).not.toContain('原工具部署操作结果未知')
    await button('重试处理原操作记录').trigger('click')
    await flushPromises()
    expect(sessionStorage.getItem('skillforge.system-tool-unknown:platform-test')).toBeNull()
    expect(mocks.create).toHaveBeenCalledTimes(1)
    expect(mocks.publish).toHaveBeenCalledTimes(stage === '发布' ? 1 : 0)
  }
)

it('发布影响优先定位需补齐的可见部署，切换筛选保留原始影响对象', async () => {
  const impact: AgentDefinitionImpact = {
    definition_id: 'definition-1',
    draft_revision: 2,
    current_published_revision: '1',
    visible_deployment_count: 2,
    changes: {
      added_resource_slots: [],
      changed_resource_slots: [],
      removed_resource_slot_keys: [],
      added_delegation_slots: [],
      removed_delegation_slot_keys: [],
      added_parameter_keys: [],
      removed_parameter_keys: []
    },
    deployments: [
      {
        deployment_id: 'ready',
        tenant_id: 'tenant-1',
        agent_id: 'agent-1',
        deployment_revision: 1,
        current_declarations_satisfied: true,
        draft_declarations_satisfied: true,
        current_issues: [],
        draft_issues: []
      },
      {
        deployment_id: 'missing',
        tenant_id: 'tenant-2',
        agent_id: 'agent-2',
        deployment_revision: 1,
        current_declarations_satisfied: true,
        draft_declarations_satisfied: false,
        current_issues: [],
        draft_issues: [
          { path: 'resource_slots.visit', code: 'REQUIRED', message: '请绑定就诊记录工具' }
        ]
      }
    ]
  }
  wrapper = mount(AgentDefinitionImpactPanel, { props: { impact, stale: false } })
  expect(wrapper.text()).toContain('本次预览中 1 个部署需补齐声明')
  wrapper.getComponent(ElCheckbox).vm.$emit('update:modelValue', true)
  await flushPromises()
  expect(wrapper.getComponent<TableInstance>(ElTable).props('data')).toEqual([
    impact.deployments[1]
  ])
  expect(impact.deployments).toHaveLength(2)
  expect(wrapper.text()).toContain('请绑定就诊记录工具')
  await wrapper.setProps({ stale: true })
  expect(wrapper.text()).toContain('此预览已失效')
})
