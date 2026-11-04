import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import {
  ElButton,
  ElCheckbox,
  ElDrawer,
  ElDialog,
  ElSelect,
  ElOption,
  ElInput,
  ElInputNumber,
  ElMessageBox,
  ElFormItem,
  ElForm,
  ElSwitch,
  ElTable
} from 'element-plus'
import ToolOnboarding from '../../components/ToolOnboarding.vue'
import WorkflowOnboarding from '../../components/WorkflowOnboarding.vue'
import AgentManagePage from './AgentManagePage.vue'
import ToolsPage from './ToolsPage.vue'
import AgentsPage from './AgentsPage.vue'
import { HttpClientError } from '@skillforge/shared/utils/http'
import { EntitySelect } from '@skillforge/shared/admin'
const mocks = vi.hoisted(() => ({
  create: vi.fn(),
  mountUpdate: vi.fn(),
  get: vi.fn(),
  versions: vi.fn(),
  getVersion: vi.fn(),
  models: vi.fn(),
  agents: vi.fn(),
  definitions: vi.fn(),
  listDefinitions: vi.fn(),
  requirementOptions: vi.fn(),
  deployments: vi.fn(),
  availableTools: vi.fn(),
  onboard: vi.fn(),
  onboarding: vi.fn(),
  connectors: vi.fn(),
  discover: vi.fn(),
  instances: vi.fn(),
  workflowList: vi.fn(),
  workflowOptions: vi.fn(),
  bases: vi.fn(),
  mountOption: vi.fn(),
  base: vi.fn(),
  revisions: vi.fn(),
  update: vi.fn(),
  publish: vi.fn(),
  publications: vi.fn(),
  revisionDetail: vi.fn(),
  restore: vi.fn(),
  templates: vi.fn(),
  templateDetail: vi.fn(),
  installTemplate: vi.fn(),
  workflowResult: vi.fn(),
  workflowSubmit: vi.fn(),
  permissions: [
    'foundation.connectors.read',
    'foundation.connectors.update',
    'foundation.capabilities.read',
    'foundation.capabilities.create',
    'foundation.capabilities.publish'
  ],
  push: vi.fn(),
  browserSession: 'knowledge-session'
}))
vi.mock('../../api/workflowOnboarding', () => ({
  workflowOnboardingApi: { result: mocks.workflowResult, submit: mocks.workflowSubmit }
}))
vi.mock('../../api/tools', () => ({
  toolsApi: {
    available: mocks.availableTools,
    onboard: mocks.onboard,
    onboarding: mocks.onboarding
  }
}))
vi.mock('../../api', () => ({
  getSessionId: () => mocks.browserSession,
  api: {
    connectors: { instances: mocks.connectors, mcpTools: mocks.discover },
    agents: {
      get: mocks.get,
      list: mocks.agents,
      update: mocks.update,
      publish: mocks.publish,
      revisions: mocks.publications,
      revisionDetail: mocks.revisionDetail,
      restoreRevision: mocks.restore,
      templates: mocks.templates,
      templateDetail: mocks.templateDetail,
      installTemplate: mocks.installTemplate,
      capabilityMounts: {
        create: mocks.create,
        update: mocks.mountUpdate,
        workflowOptions: mocks.workflowOptions
      }
    },
    settings: { availableModels: mocks.models },
    capabilities: {
      definitions: mocks.definitions,
      listDefinitions: mocks.listDefinitions,
      listRequirementOptions: mocks.requirementOptions,
      versions: mocks.versions,
      getVersion: mocks.getVersion,
      deployments: mocks.deployments
    },
    workflowDrafts: { instances: mocks.instances, list: mocks.workflowList },
    knowledge: {
      listBases: mocks.bases,
      mountOption: mocks.mountOption,
      getBase: mocks.base,
      listRevisions: mocks.revisions
    }
  }
}))
vi.mock('../../stores/auth', () => ({
  useAuthStore: () => ({
    identityVersion: 1,
    sessionIdentity: 'knowledge-identity',
    user: {
      tenant_id: '1',
      sub: 'actor-1',
      permissions: mocks.permissions
    }
  })
}))
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { id: 'agent-1' }, query: {} }),
  useRouter: () => ({ push: mocks.push }),
  onBeforeRouteLeave: vi.fn(),
  onBeforeRouteUpdate: vi.fn()
}))
const wrappers: VueWrapper[] = []

it('仅有 Agent 和工作流读取权限仍可核对原接入回执，不能提交写入', async () => {
  mocks.permissions.splice(
    0,
    mocks.permissions.length,
    'foundation.agents.read',
    'foundation.workflows.read'
  )
  const requestId = 'abcdefab-1234-4234-8234-123456789012'
  mocks.workflowResult.mockResolvedValue({
    request_id: requestId,
    agent_id: 'agent-1',
    workflow_id: 'flow',
    status: 'SUCCEEDED',
    instance_id: '7',
    installation_revision: 1,
    mount_id: 'm',
    mount_revision: 1
  })
  const wrapper = mount(AgentManagePage, {
    attachTo: document.body,
    global: { stubs: { RouterLink: true } }
  })
  wrappers.push(wrapper)
  await flushPromises()
  await button(wrapper, '核对工作流接入').trigger('click')
  await flushPromises()
  const panel = wrapper.getComponent(WorkflowOnboarding)
  await panel.get('input').setValue(requestId)
  await panel
    .findAllComponents(ElButton)
    .find((item) => item.text() === '核对原操作')!
    .trigger('click')
  await flushPromises()
  expect(mocks.workflowResult).toHaveBeenCalledWith(requestId)
  expect(mocks.workflowSubmit).not.toHaveBeenCalled()
  expect(panel.text()).toContain('接入已确认')
})
function button(wrapper: VueWrapper, text: string) {
  return wrapper.findAllComponents(ElButton).find((item) => item.text() === text)!
}
async function open(type: string) {
  const wrapper = mount(AgentManagePage, {
    attachTo: document.body,
    global: {
      stubs: { RouterLink: { props: ['to'], template: '<a :href="to"><slot /></a>' } }
    }
  })
  wrappers.push(wrapper)
  await flushPromises()
  await button(wrapper, '添加能力').trigger('click')
  await flushPromises()
  const drawer = wrapper.findComponent(ElDrawer)
  const typeSelect = drawer.findAllComponents(ElSelect)[0]!
  if (typeSelect.props('modelValue') !== type) {
    typeSelect.vm.$emit('update:modelValue', type)
    typeSelect.vm.$emit('change', type)
  }
  await flushPromises()
  return { wrapper, drawer }
}
beforeEach(() => {
  vi.resetAllMocks()
  mocks.browserSession = 'knowledge-session'
  mocks.requirementOptions.mockImplementation(async (type: string) =>
    type === 'SKILL' || type === 'WORKFLOW'
      ? [{ id: '10', name: '服务流程', versionId: '21', versionKey: '2' }]
      : []
  )
  sessionStorage.clear()
  mocks.permissions.splice(
    0,
    mocks.permissions.length,
    'foundation.connectors.read',
    'foundation.connectors.update',
    'foundation.capabilities.read',
    'foundation.capabilities.create',
    'foundation.capabilities.publish'
  )
  mocks.models.mockResolvedValue([{ id: 'model-1', name: '模型', kind: 'LLM' }])
  mocks.agents.mockResolvedValue({ items: [] })
  mocks.definitions.mockResolvedValue([{ id: '10', defaultVersionId: '21', name: '服务流程' }])
  mocks.listDefinitions.mockResolvedValue([
    { id: '10', defaultVersionId: '21', name: '服务流程', capabilityType: 'WORKFLOW' }
  ])
  mocks.deployments.mockResolvedValue([])
  mocks.availableTools.mockResolvedValue({ items: [] })
  mocks.connectors.mockResolvedValue({
    items: [{ instance_id: 'server', name: '服务', executor_type: 'mcp' }]
  })
  mocks.discover.mockResolvedValue({ items: [{ name: 'new_tool' }] })
  mocks.get.mockResolvedValue({
    id: 'agent-1',
    name: '客服',
    model_config_id: 'model-1',
    system_prompt: '按机构规则协助客户',
    status: 'published',
    published_revision: 2,
    has_draft_changes: true,
    mounts: [],
    execution_config: {},
    delegation_config: { enabled: false, target_agent_ids: [] }
  })
  mocks.getVersion.mockResolvedValue({ id: '21', name: '服务流程', versionKey: '2' })
  mocks.versions.mockResolvedValue([
    { id: '20', definitionId: '10', capabilityType: 'WORKFLOW', name: '服务流程', versionKey: '1' },
    { id: '21', definitionId: '10', capabilityType: 'WORKFLOW', name: '服务流程', versionKey: '2' }
  ])
  mocks.workflowList.mockResolvedValue({ items: [] })
  mocks.instances.mockResolvedValue({
    items: [
      {
        id: '9223372036854775807',
        instanceId: 'first',
        workflowId: 'workflow',
        capabilityVersionId: '20',
        installationRevision: '100'
      },
      {
        id: '9223372036854775806',
        instanceId: 'second',
        workflowId: 'workflow',
        capabilityVersionId: '21',
        installationRevision: '101'
      }
    ]
  })
  mocks.workflowOptions.mockResolvedValue([
    {
      id: '9223372036854775807',
      instanceId: 'first',
      workflowId: 'workflow',
      definitionId: '10',
      installationRevision: '100'
    },
    {
      id: '9223372036854775806',
      instanceId: 'second',
      workflowId: 'workflow',
      definitionId: '10',
      installationRevision: '101'
    }
  ])
  mocks.bases.mockResolvedValue({ items: [{ id: 'kb-1', name: '帮助中心' }] })
  mocks.mountOption.mockImplementation(async (id: string) => ({
    kb_id: id,
    active_revision_id: 'revision-2',
    definition_id: '10',
    version_id: '21',
    version_key: '2'
  }))
  mocks.base.mockResolvedValue({ id: 'kb-1', active_revision_id: 'revision-2' })
  mocks.revisions.mockResolvedValue({
    items: [
      { id: 'revision-1', revision: 1, status: 'published', capability_version_id: '20' },
      { id: 'revision-2', revision: 2, status: 'published', capability_version_id: '21' }
    ]
  })
  mocks.create.mockResolvedValue({})
  mocks.update.mockResolvedValue({ id: 'agent-1' })
  mocks.publications.mockResolvedValue({ items: [] })
  mocks.templates.mockResolvedValue({
    items: [{ id: 'template-1', name: '客服模板', version: '1' }]
  })
  mocks.templateDetail.mockResolvedValue({
    id: 'template-1',
    name: '客服模板',
    definition: {
      resource_slots: [{ key: 'process', label: '服务流程', capability_type: 'WORKFLOW' }],
      delegation_slots: []
    }
  })
  mocks.installTemplate.mockResolvedValue({ agent: { id: 'installed-agent' } })
})
afterEach(() => {
  vi.restoreAllMocks()
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  document.body.innerHTML = ''
})
describe('已挂载技能预算调整', () => {
  const skill = {
    mountId: 'mount-1',
    capabilityType: 'SKILL',
    capabilityDefinitionId: '10',
    capabilityVersionId: '21',
    priority: 3,
    revision: 7,
    status: 'ACTIVE',
    skillConfig: { activationMode: 'ON_DEMAND', tokenBudget: 64 },
    toolConfig: null,
    workflowConfig: null,
    knowledgeConfig: null
  }
  async function mountedSkill() {
    const original = await mocks.get('agent-1')
    mocks.get.mockClear()
    mocks.get.mockResolvedValue({ ...original, mounts: [skill] })
    const wrapper = mount(AgentManagePage, { attachTo: document.body })
    wrappers.push(wrapper)
    await flushPromises()
    return { wrapper, original }
  }
  async function editBudget(wrapper: VueWrapper) {
    await button(wrapper, '调整预算').trigger('click')
    await flushPromises()
    const dialog = wrapper
      .findAllComponents(ElDialog)
      .find((item) => item.props('title') === '调整技能预算')!
    expect(dialog).toBeDefined()
    const budget = dialog
      .findAllComponents(ElFormItem)
      .find((item) => item.props('label') === 'Token 预算')!
      .getComponent(ElInputNumber)
    return { dialog, budget }
  }
  it('展示技能预算并携带原挂载修订保存，成功后重读智能体', async () => {
    const { wrapper, original } = await mountedSkill()
    expect(wrapper.text()).toContain('Token 预算：64')
    const { dialog, budget } = await editBudget(wrapper)
    expect(budget.props('modelValue')).toBe(64)
    expect(budget.props('min')).toBe(1)
    expect(budget.props('precision')).toBe(0)
    budget.vm.$emit('update:modelValue', 256)
    await flushPromises()
    mocks.mountUpdate.mockResolvedValue({ generation: 2, mounts: [] })
    mocks.get.mockResolvedValue({
      ...original,
      mounts: [{ ...skill, revision: 8, skillConfig: { ...skill.skillConfig, tokenBudget: 256 } }]
    })
    await button(dialog, '保存预算').trigger('click')
    await flushPromises()
    expect(mocks.mountUpdate).toHaveBeenCalledExactlyOnceWith('agent-1', 'mount-1', 7, {
      capabilityType: 'SKILL',
      capabilityDefinitionId: '10',
      priority: 3,
      status: 'ACTIVE',
      skillConfig: { tokenBudget: 256 }
    })
    expect(mocks.get).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('Token 预算：256')
    expect(mocks.publish).not.toHaveBeenCalled()
  })
  it('写入失败保留预算输入并禁止重发，先只读核对挂载状态', async () => {
    const { wrapper } = await mountedSkill()
    const { dialog, budget } = await editBudget(wrapper)
    budget.vm.$emit('update:modelValue', 256)
    await flushPromises()
    mocks.mountUpdate.mockRejectedValueOnce(
      new HttpClientError({ status: 0, userMessage: '结果尚未确认' })
    )
    await button(dialog, '保存预算').trigger('click')
    await flushPromises()
    expect(budget.props('modelValue')).toBe(256)
    expect(button(dialog, '保存预算').props('disabled')).toBe(true)
    await button(dialog, '重新读取挂载').trigger('click')
    await flushPromises()
    expect(mocks.get).toHaveBeenCalledTimes(2)
    expect(mocks.mountUpdate).toHaveBeenCalledTimes(1)
  })
  it.each(['TOOL', 'WORKFLOW', 'KNOWLEDGE_SOURCE'])(
    '%s 挂载不展示技能预算编辑入口',
    async (capabilityType) => {
      const original = await mocks.get('agent-1')
      mocks.get.mockResolvedValue({
        ...original,
        mounts: [{ ...skill, capabilityType, skillConfig: null }]
      })
      const wrapper = mount(AgentManagePage, { attachTo: document.body })
      wrappers.push(wrapper)
      await flushPromises()
      expect(wrapper.findAllComponents(ElButton).some((item) => item.text() === '调整预算')).toBe(
        false
      )
      expect(wrapper.text()).not.toContain('Token 预算：')
    }
  )
})

describe('智能体主对象与辅助选项的读取归属', () => {
  it('从其他页签保存无效配置时切回基本配置并定位错误字段', async () => {
    const wrapper = mount(AgentManagePage, { attachTo: document.body })
    wrappers.push(wrapper)
    await flushPromises()
    const form = wrapper
      .findAllComponents(ElForm)
      .find((item) => item.props('model')?.system_prompt !== undefined)!
    const name = form.findAllComponents(ElFormItem).find((item) => item.props('prop') === 'name')!
    name.findComponent(ElInput).vm.$emit('update:modelValue', '')
    await flushPromises()
    const scroll = vi.fn(() =>
      expect(getComputedStyle(name.element.closest('[role="tabpanel"]')!).display).not.toBe('none')
    )
    ;(name.element as HTMLElement).scrollIntoView = scroll
    const tab = wrapper.findAll('[role="tab"]').find((item) => item.text().includes('运行与协作'))!
    await tab.trigger('click')
    await button(wrapper, '保存草稿').trigger('click')
    await flushPromises()
    expect(wrapper.find('[role="tab"][aria-selected="true"]').text()).toContain('基本配置')
    expect(scroll).toHaveBeenCalledOnce()
    expect(mocks.update).not.toHaveBeenCalled()
  })
  it('刷新模型选项后可选择新模型，保留智能体草稿', async () => {
    const wrapper = mount(AgentManagePage, { attachTo: document.body })
    wrappers.push(wrapper)
    await flushPromises()
    const form = wrapper
      .findAllComponents(ElForm)
      .find((item) => item.props('model')?.system_prompt !== undefined)!
    const name = wrapper
      .findAllComponents(ElInput)
      .find((input) => input.props('modelValue') === '客服')!
    name.vm.$emit('update:modelValue', '未保存的名称')
    mocks.models.mockResolvedValueOnce([
      { id: 'model-1', name: '模型', kind: 'LLM' },
      { id: 'model-2', name: '新模型', kind: 'LLM' }
    ])
    await button(wrapper, '刷新模型选项').trigger('click')
    await flushPromises()
    const select = wrapper
      .findAllComponents(ElSelect)
      .find((item) => item.props('placeholder') === '按名称或标识选择已启用模型')!
    expect(
      select.findAllComponents(ElOption).some((item) => item.props('value') === 'model-2')
    ).toBe(true)
    expect(form.props('model')).toMatchObject({ name: '未保存的名称', model_config_id: 'model-1' })
    select.vm.$emit('update:modelValue', 'model-2')
    await flushPromises()
    expect(form.props('model')).toMatchObject({ name: '未保存的名称', model_config_id: 'model-2' })
    expect(mocks.get).toHaveBeenCalledTimes(1)
    expect(mocks.update).not.toHaveBeenCalled()
    expect(mocks.publish).not.toHaveBeenCalled()
  })

  it('已选模型不在刷新后的目录时保留原值并提示核对，读取失败不冒充目录事实', async () => {
    const wrapper = mount(AgentManagePage, { attachTo: document.body })
    wrappers.push(wrapper)
    await flushPromises()
    const form = wrapper
      .findAllComponents(ElForm)
      .find((item) => item.props('model')?.name === '客服')!
    mocks.models.mockResolvedValueOnce([{ id: 'model-2', name: '其他模型', kind: 'LLM' }])
    await button(wrapper, '刷新模型选项').trigger('click')
    await flushPromises()
    expect(form.props('model')!.model_config_id).toBe('model-1')
    expect(wrapper.text()).toContain('当前指定的模型不在可选目录中')
    mocks.models.mockRejectedValueOnce(new Error('模型目录不可读'))
    await button(wrapper, '刷新模型选项').trigger('click')
    await flushPromises()
    expect(wrapper.text()).not.toContain('当前指定的模型不在可选目录中')
    expect(form.props('model')!.model_config_id).toBe('model-1')
    expect(mocks.update).not.toHaveBeenCalled()
  })

  it('辅助读取迟到或失败不阻止主对象回填，重试也不覆盖用户编辑', async () => {
    let rejectModels!: (reason: Error) => void
    mocks.models.mockReturnValueOnce(
      new Promise((_resolve, reject) => {
        rejectModels = reject
      })
    )
    const wrapper = mount(AgentManagePage, { attachTo: document.body })
    wrappers.push(wrapper)
    await flushPromises()
    const form = wrapper
      .findAllComponents(ElForm)
      .find((item) => item.props('model')?.system_prompt !== undefined)!
    expect(form.props('model')).toMatchObject({
      name: '客服',
      system_prompt: '按机构规则协助客户',
      model_config_id: 'model-1'
    })
    const name = wrapper
      .findAllComponents(ElInput)
      .find((input) => input.props('modelValue') === '客服')!
    name.vm.$emit('update:modelValue', '用户正在编辑的名称')
    rejectModels(new Error('辅助模型列表不可用'))
    await flushPromises()
    expect(form.props('model')!.name).toBe('用户正在编辑的名称')
    expect(document.body.textContent).toContain('辅助模型列表不可用')
    let finishModels!: (models: unknown[]) => void
    mocks.models.mockReturnValueOnce(
      new Promise((resolve) => {
        finishModels = resolve
      })
    )
    await button(wrapper, '重新读取模型选项').trigger('click')
    name.vm.$emit('update:modelValue', '重试等待期间的新名称')
    finishModels([{ id: 'model-1', name: '模型', kind: 'LLM' }])
    await flushPromises()
    expect(form.props('model')).toMatchObject({
      name: '重试等待期间的新名称',
      system_prompt: '按机构规则协助客户',
      model_config_id: 'model-1'
    })
    expect(mocks.get).toHaveBeenCalledTimes(1)
    expect(mocks.update).not.toHaveBeenCalled()
  })

  it('委派候选读取失败仍保留服务端原提示词与配置', async () => {
    mocks.agents.mockRejectedValueOnce(new Error('委派候选读取失败'))
    const wrapper = mount(AgentManagePage, { attachTo: document.body })
    wrappers.push(wrapper)
    await flushPromises()
    const form = wrapper
      .findAllComponents(ElForm)
      .find((item) => item.props('model')?.system_prompt !== undefined)!
    expect(form.props('model')).toMatchObject({
      name: '客服',
      system_prompt: '按机构规则协助客户',
      model_config_id: 'model-1'
    })
    expect(document.body.textContent).toContain('委派候选读取失败')
    expect(button(wrapper, '重新读取委派选项').exists()).toBe(true)
    expect(
      wrapper
        .findAllComponents(ElOption)
        .some(
          (option) =>
            option.props('label') === '模型 · 标识 model-1' && option.props('value') === 'model-1'
        )
    ).toBe(true)
    await button(wrapper, '重新读取委派选项').trigger('click')
    await flushPromises()
    expect(mocks.models).toHaveBeenCalledTimes(1)
    expect(mocks.agents).toHaveBeenCalledTimes(2)
    expect(form.props('model')!.model_config_id).toBe('model-1')
    expect(mocks.update).not.toHaveBeenCalled()
    expect(mocks.publish).not.toHaveBeenCalled()
  })

  it('模型目录拒绝不阻断委派候选，独立恢复不改变原选择或草稿', async () => {
    mocks.models.mockRejectedValueOnce(new Error('403：缺少模型读取权限'))
    mocks.agents.mockResolvedValue({
      items: [
        { id: 'agent-1', name: '当前助手', status: 'published' },
        { id: 'agent-2', name: '委派助手', status: 'published' },
        { id: 'agent-3', name: '未发布助手', status: 'draft' }
      ]
    })
    const wrapper = mount(AgentManagePage, { attachTo: document.body })
    wrappers.push(wrapper)
    await flushPromises()
    const form = wrapper
      .findAllComponents(ElForm)
      .find((item) => item.props('model')?.system_prompt !== undefined)!
    const delegationSwitch = wrapper
      .findAllComponents(ElSwitch)
      .find((item) => item.props('activeText') === '允许委派')!
    delegationSwitch.vm.$emit('update:modelValue', true)
    await flushPromises()
    const target = wrapper
      .findAllComponents(ElSelect)
      .find((item) => item.props('placeholder') === '选择允许委派的智能体')!
    expect(target.findAllComponents(ElOption).map((option) => option.props('value'))).toEqual([
      'agent-2'
    ])
    target.vm.$emit('update:modelValue', ['agent-2'])
    const name = wrapper
      .findAllComponents(ElInput)
      .find((input) => input.props('modelValue') === '客服')!
    name.vm.$emit('update:modelValue', '保留的配置草稿')
    await button(wrapper, '重新读取模型选项').trigger('click')
    await flushPromises()
    expect(mocks.models).toHaveBeenCalledTimes(2)
    expect(mocks.agents).toHaveBeenCalledTimes(1)
    expect(form.props('model')).toMatchObject({
      name: '保留的配置草稿',
      model_config_id: 'model-1',
      delegation_config: { enabled: true, target_agent_ids: ['agent-2'] }
    })
    expect(mocks.get).toHaveBeenCalledTimes(1)
    expect(mocks.update).not.toHaveBeenCalled()
    expect(mocks.publish).not.toHaveBeenCalled()
  })

  it('两个目录失败聚合为一条顶部反馈，分别保留重读入口', async () => {
    mocks.models.mockRejectedValueOnce(new Error('模型服务不可用'))
    mocks.agents.mockRejectedValueOnce(new Error('委派目录不可用'))
    const wrapper = mount(AgentManagePage, { attachTo: document.body })
    wrappers.push(wrapper)
    await flushPromises()
    const messages = document.body.querySelectorAll('.el-message')
    expect(messages).toHaveLength(1)
    expect(messages[0]!.textContent).toContain('模型选项读取失败：模型服务不可用')
    expect(messages[0]!.textContent).toContain('委派选项读取失败：委派目录不可用')
    expect(button(wrapper, '重新读取模型选项').exists()).toBe(true)
    expect(button(wrapper, '重新读取委派选项').exists()).toBe(true)
  })
})

describe('按挂载类型加载实际关联', () => {
  it('恢复工作流接入回执后刷新智能体并保留原挂载选择', async () => {
    mocks.permissions.push(
      'foundation.agents.read',
      'foundation.workflows.read',
      'foundation.agents.create',
      'foundation.agents.update',
      'foundation.workflows.create'
    )
    const id = 'abcdefab-1234-4234-8234-123456789012'
    const key = `skillforge.workflow-onboarding.${JSON.stringify(['1', 'actor-1', 'agent-1'])}`
    sessionStorage.setItem(
      key,
      JSON.stringify({ request_id: id, agent_id: 'agent-1', workflow_id: 'workflow' })
    )
    mocks.workflowResult.mockResolvedValue({
      request_id: id,
      agent_id: 'agent-1',
      workflow_id: 'workflow',
      status: 'SUCCEEDED',
      instance_id: '9223372036854775807',
      installation_revision: 1,
      mount_id: 'm',
      mount_revision: 1
    })
    const { drawer } = await open('WORKFLOW')
    const selected = drawer.findAllComponents(ElSelect)[1]!
    expect(selected.props('modelValue')).toBe('10')
    const instance = drawer.findAllComponents(ElSelect)[2]!
    instance.vm.$emit('update:modelValue', '9223372036854775807')
    mocks.definitions.mockResolvedValue([
      { id: '10', defaultVersionId: '23', name: '已有工作流' },
      { id: '11', defaultVersionId: '22', name: '新工作流' }
    ])
    mocks.requirementOptions.mockResolvedValue([
      { id: '10', name: '已有工作流', versionId: '23', versionKey: '3' },
      { id: '11', name: '新工作流', versionId: '22', versionKey: '1' }
    ])
    mocks.workflowOptions.mockResolvedValue([
      {
        id: '9223372036854775807',
        instanceId: 'first',
        workflowId: 'workflow',
        definitionId: '10',
        installationRevision: '100'
      },
      {
        id: '9223372036854775806',
        instanceId: 'second',
        workflowId: 'workflow',
        definitionId: '10',
        installationRevision: '101'
      },
      {
        id: '43',
        instanceId: 'new',
        workflowId: 'new',
        definitionId: '11',
        installationRevision: '1'
      }
    ])
    await button(drawer, '接入工作流').trigger('click')
    await flushPromises()
    expect(mocks.workflowResult).toHaveBeenCalledWith(id)
    expect(sessionStorage.getItem(key)).toBeNull()
    expect(mocks.get.mock.calls.length).toBeGreaterThan(1)
    expect(selected.props('modelValue')).toBe('10')
    expect(instance.props('modelValue')).toBe('9223372036854775807')
    expect(selected.findAllComponents(ElOption).map((option) => option.props('value'))).toContain(
      '11'
    )
    expect(mocks.workflowOptions).toHaveBeenCalledTimes(2)
    expect(mocks.workflowSubmit).not.toHaveBeenCalled()
    expect(mocks.create).not.toHaveBeenCalled()
    expect(mocks.publish).not.toHaveBeenCalled()
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue(
      'confirm' as Awaited<ReturnType<typeof ElMessageBox.confirm>>
    )
    await button(drawer, '收起工作流接入').trigger('click')
    await flushPromises()
    await button(drawer, '添加能力').trigger('click')
    await flushPromises()
    expect(mocks.create).toHaveBeenCalledWith(
      'agent-1',
      expect.objectContaining({
        capabilityDefinitionId: '10',
        workflowConfig: { workflowInstanceId: '9223372036854775807' }
      })
    )
  })
  it('工作流接入后智能体重读失败，可在抽屉内恢复且保留配置和挂载选择', async () => {
    mocks.permissions.push(
      'foundation.agents.read',
      'foundation.workflows.read',
      'foundation.agents.create',
      'foundation.workflows.create'
    )
    const { wrapper, drawer } = await open('WORKFLOW')
    const form = wrapper
      .findAllComponents(ElForm)
      .find((item) => item.props('model')?.system_prompt !== undefined)!
    const name = wrapper
      .findAllComponents(ElInput)
      .find((input) => input.props('modelValue') === '客服')!
    name.vm.$emit('update:modelValue', '未保存的智能体草稿')
    const selected = drawer.findAllComponents(ElSelect)[1]!
    const instance = drawer.findAllComponents(ElSelect)[2]!
    instance.vm.$emit('update:modelValue', '9223372036854775807')
    await button(drawer, '接入工作流').trigger('click')
    await flushPromises()
    mocks.get
      .mockRejectedValueOnce(new Error('智能体读取失败'))
      .mockRejectedValueOnce(new Error('智能体仍不可读'))
    drawer.getComponent(WorkflowOnboarding).vm.$emit('confirmed')
    await flushPromises()
    expect(document.body.textContent).toContain('工作流接入已确认，智能体或挂载选项尚未刷新')
    expect(button(drawer, '添加能力').props('disabled')).toBe(true)
    await button(drawer, '重新读取接入状态').trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('工作流接入已确认，智能体或挂载选项尚未刷新')
    expect(mocks.workflowOptions).toHaveBeenCalledTimes(1)
    expect(form.props('model')?.name).toBe('未保存的智能体草稿')
    await button(drawer, '重新读取接入状态').trigger('click')
    await flushPromises()
    expect(document.body.textContent).not.toContain('工作流接入已确认，智能体或挂载选项尚未刷新')
    expect(form.props('model')?.name).toBe('未保存的智能体草稿')
    expect(selected.props('modelValue')).toBe('10')
    expect(instance.props('modelValue')).toBe('9223372036854775807')
    expect(mocks.workflowOptions).toHaveBeenCalledTimes(2)
    expect(mocks.workflowSubmit).not.toHaveBeenCalled()
    expect(mocks.create).not.toHaveBeenCalled()
    expect(mocks.publish).not.toHaveBeenCalled()
  })
  it('工作流接入后的挂载选项读取失败时保留选择并可重试', async () => {
    mocks.permissions.push(
      'foundation.agents.read',
      'foundation.workflows.read',
      'foundation.agents.create',
      'foundation.workflows.create'
    )
    const { drawer } = await open('WORKFLOW')
    const selected = drawer.findAllComponents(ElSelect)[1]!
    const instance = drawer.findAllComponents(ElSelect)[2]!
    instance.vm.$emit('update:modelValue', '9223372036854775807')
    await button(drawer, '接入工作流').trigger('click')
    await flushPromises()
    mocks.workflowOptions.mockRejectedValueOnce(new Error('目录不可读'))
    drawer.getComponent(WorkflowOnboarding).vm.$emit('confirmed')
    await flushPromises()
    expect(document.body.textContent).toContain('工作流接入已确认，智能体或挂载选项尚未刷新')
    expect(selected.props('modelValue')).toBe('10')
    expect(instance.props('modelValue')).toBe('9223372036854775807')
    expect(button(drawer, '添加能力').props('disabled')).toBe(true)
    await button(drawer, '重新读取接入状态').trigger('click')
    await flushPromises()
    expect(document.body.textContent).not.toContain('工作流接入已确认，智能体或挂载选项尚未刷新')
    expect(selected.props('modelValue')).toBe('10')
    expect(instance.props('modelValue')).toBe('9223372036854775807')
    expect(mocks.create).not.toHaveBeenCalled()
    expect(mocks.publish).not.toHaveBeenCalled()
  })
  it('已确认接入后的刷新失败保留选择，重开抽屉成功读取后清除提示', async () => {
    mocks.availableTools
      .mockResolvedValueOnce({ items: [] })
      .mockRejectedValueOnce(new Error('目录不可读'))
      .mockResolvedValue({ items: [] })
    const { drawer } = await open('TOOL')
    await button(drawer, '接入可用工具').trigger('click')
    await flushPromises()
    drawer.getComponent(ToolsPage).vm.$emit('confirmed', {
      request_id: 'request',
      connector_id: 'server',
      status: 'SUCCEEDED',
      tool_names: ['new_tool'],
      items: [
        {
          tool_name: 'new_tool',
          definition_id: '40',
          version_id: '41',
          deployment_key: 'service-one',
          environment: 'PRODUCTION'
        }
      ]
    })
    await flushPromises()
    expect(document.body.textContent).toContain('工具接入已确认，但挂载选项尚未刷新')
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue(
      'confirm' as Awaited<ReturnType<typeof ElMessageBox.confirm>>
    )
    await button(drawer, '取消').trigger('click')
    await flushPromises()
    await button(wrappers[wrappers.length - 1]!, '添加能力').trigger('click')
    await flushPromises()
    const reopened = wrappers[wrappers.length - 1]!.getComponent(ElDrawer)
    reopened.findAllComponents(ElSelect)[0]!.vm.$emit('update:modelValue', 'TOOL')
    reopened.findAllComponents(ElSelect)[0]!.vm.$emit('change', 'TOOL')
    await flushPromises()
    expect(document.body.textContent).not.toContain('工具接入已确认，但挂载选项尚未刷新')
  })
  it('抽屉中未知结果取消离开时保留核对状态并禁止挂载', async () => {
    mocks.onboard.mockRejectedValue(new HttpClientError({ status: 408, userMessage: '超时' }))
    const { drawer } = await open('TOOL')
    await button(drawer, '接入可用工具').trigger('click')
    await flushPromises()
    const onboarding = drawer.getComponent(ToolOnboarding)
    onboarding.getComponent(ElSelect).vm.$emit('update:modelValue', 'server')
    await flushPromises()
    expect(mocks.discover).toHaveBeenCalledExactlyOnceWith('server')
    onboarding.getComponent(ElTable).vm.$emit('selection-change', [{ name: 'new_tool' }])
    await flushPromises()
    await button(drawer, '确认接入并授权调用 1 项工具').trigger('click')
    await flushPromises()
    expect(button(drawer, '添加能力').props('disabled')).toBe(true)
    let finish!: (value: { items: [] }) => void
    mocks.onboarding.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve
        })
    )
    await button(drawer, '核对接入结果').trigger('click')
    await flushPromises()
    expect(button(drawer, '取消').props('disabled')).toBe(true)
    expect(button(drawer, '收起工具接入').props('disabled')).toBe(true)
    finish({ items: [] })
    await flushPromises()
    vi.spyOn(ElMessageBox, 'confirm').mockRejectedValueOnce('cancel')
    await button(drawer, '取消').trigger('click')
    await flushPromises()
    expect(drawer.props('modelValue')).toBe(true)
    expect(document.body.textContent).toContain('接入结果尚未确认')
    expect(document.body.textContent).toContain('new_tool')
    expect(mocks.create).not.toHaveBeenCalled()
    mocks.availableTools.mockRejectedValueOnce(new Error('目录不可读'))
    mocks.onboarding.mockImplementationOnce(async (request_id: string) => ({
      request_id,
      connector_id: 'server',
      status: 'SUCCEEDED',
      tool_names: ['new_tool'],
      items: [
        {
          status: 'AVAILABLE',
          tool_name: 'new_tool',
          definition_id: '40',
          version_id: '41',
          deployment_key: 'service-one',
          name: '新工具',
          description: '',
          provider_ref: 'server',
          provider_label: '服务',
          environment: 'PRODUCTION'
        }
      ]
    }))
    await button(drawer, '核对接入结果').trigger('click')
    await flushPromises()
    expect(document.body.textContent).not.toContain('接入结果尚未确认')
    expect(document.body.textContent).toContain('工具接入已确认，但挂载选项尚未刷新')
    expect(mocks.onboard).toHaveBeenCalledTimes(1)
    expect(mocks.create).not.toHaveBeenCalled()
  })
  it('恢复工具接入回执后刷新挂载选项并保留用户已有选择', async () => {
    const existing = {
      definition_id: '30',
      default_version_id: '31',
      name: '已有',
      description: '',
      tool_name: 'existing',
      deployments: [
        { deployment_key: 'service-one', provider_label: '服务', environment: 'PRODUCTION' }
      ]
    }
    const another = { ...existing, definition_id: '32', name: '其他', tool_name: 'other' }
    const added = { ...existing, definition_id: '40', name: '新工具', tool_name: 'new_tool' }
    mocks.availableTools
      .mockResolvedValueOnce({ items: [existing, another] })
      .mockResolvedValue({ items: [existing, another, added] })
    const requestId = '12345678-1234-4234-8234-123456789abc'
    const key = `skillforge.tool-onboarding.${JSON.stringify(['1', 'actor-1'])}`
    sessionStorage.setItem(
      key,
      JSON.stringify({ requestId, connectorId: 'server', names: ['new_tool'] })
    )
    mocks.onboarding.mockResolvedValueOnce({
      request_id: requestId,
      connector_id: 'server',
      status: 'SUCCEEDED',
      tool_names: ['new_tool'],
      items: [
        {
          tool_name: 'new_tool',
          definition_id: '40',
          version_id: '31',
          deployment_key: 'service-one',
          environment: 'PRODUCTION'
        }
      ]
    })
    const { drawer } = await open('TOOL')
    const select = drawer.findAllComponents(ElSelect)[1]!
    select.vm.$emit('update:modelValue', '30')
    await flushPromises()
    await button(drawer, '接入可用工具').trigger('click')
    await flushPromises()
    expect(mocks.onboarding).toHaveBeenCalledExactlyOnceWith(requestId)
    expect(sessionStorage.getItem(key)).toBeNull()
    expect(mocks.availableTools).toHaveBeenCalledTimes(2)
    expect(select.props('modelValue')).toBe('30')
    expect(mocks.onboard).not.toHaveBeenCalled()
    expect(mocks.create).not.toHaveBeenCalled()
    expect(mocks.publish).not.toHaveBeenCalled()
  })
  it('非空目录内接入工具后刷新选项，保留原选择且不自动挂载或发布', async () => {
    const original = {
      definition_id: '30',
      default_version_id: '31',
      name: '已有',
      description: '',
      tool_name: 'existing',
      deployments: [
        { deployment_key: 'service-one', provider_label: '服务', environment: 'PRODUCTION' }
      ]
    }
    const added = { ...original, definition_id: '40', name: '新工具', tool_name: 'new_tool' }
    mocks.availableTools.mockResolvedValue({ items: [original, added] })
    mocks.onboard.mockImplementation(async (request_id: string) => ({
      request_id,
      connector_id: 'server',
      status: 'SUCCEEDED',
      tool_names: ['new_tool'],
      items: [
        {
          status: 'AVAILABLE',
          tool_name: 'new_tool',
          definition_id: '40',
          version_id: '31',
          deployment_key: 'service-one',
          name: '新工具',
          description: '',
          provider_ref: 'server',
          provider_label: '服务',
          environment: 'PRODUCTION'
        }
      ]
    }))
    const { drawer } = await open('TOOL')
    const select = drawer.findAllComponents(ElSelect)[1]!
    select.vm.$emit('update:modelValue', '30')
    await flushPromises()
    select.props('filterMethod')!('没有匹配')
    await flushPromises()
    await button(drawer, '接入可用工具').trigger('click')
    await flushPromises()
    const onboarding = drawer.getComponent(ToolOnboarding)
    onboarding.getComponent(ElSelect).vm.$emit('update:modelValue', 'server')
    await flushPromises()
    await button(drawer, '读取工具').trigger('click')
    await flushPromises()
    onboarding.getComponent(ElTable).vm.$emit('selection-change', [{ name: 'new_tool' }])
    await flushPromises()
    await button(drawer, '确认接入并授权调用 1 项工具').trigger('click')
    await flushPromises()
    expect(select.props('modelValue')).toBe('30')
    expect(mocks.availableTools).toHaveBeenCalledTimes(2)
    expect(mocks.create).not.toHaveBeenCalled()
    expect(mocks.publish).not.toHaveBeenCalled()
  })
  it('用户打开工具选择器后接入另一工具，保留当前选择', async () => {
    const original = {
      definition_id: '30',
      default_version_id: '31',
      name: '已有',
      description: '',
      tool_name: 'existing',
      deployments: [{ deployment_key: 'one', provider_label: '服务', environment: 'PRODUCTION' }]
    }
    const added = { ...original, definition_id: '40', name: '新工具' }
    mocks.availableTools
      .mockResolvedValueOnce({ items: [original] })
      .mockResolvedValue({ items: [original, added] })
    const { drawer } = await open('TOOL')
    const select = drawer.findAllComponents(ElSelect)[1]!
    expect(select.props('modelValue')).toBe('30')
    await select.get('.el-select__wrapper').trigger('click')
    await flushPromises()
    await select.get('.el-select__wrapper').trigger('click')
    await flushPromises()
    await button(drawer, '接入可用工具').trigger('click')
    await flushPromises()
    drawer.getComponent(ToolsPage).vm.$emit('confirmed', {
      request_id: 'request',
      connector_id: 'server',
      status: 'SUCCEEDED',
      tool_names: ['new'],
      items: [
        {
          tool_name: 'new',
          definition_id: '40',
          version_id: '31',
          deployment_key: 'one',
          environment: 'PRODUCTION'
        }
      ]
    })
    await flushPromises()
    expect(select.props('modelValue')).toBe('30')
    expect(mocks.create).not.toHaveBeenCalled()
  })
  it('单项接入后刷新目录并选中匹配工具，不自动挂载', async () => {
    const tool = {
      definition_id: '40',
      default_version_id: '41',
      name: '新工具',
      description: '',
      tool_name: 'new_tool',
      deployments: [
        { deployment_key: 'service-one', provider_label: '服务', environment: 'PRODUCTION' }
      ]
    }
    mocks.availableTools.mockResolvedValue({ items: [tool] })
    const { drawer } = await open('TOOL')
    const select = drawer.findAllComponents(ElSelect)[1]!
    select.props('filterMethod')!('不匹配新工具')
    await flushPromises()
    expect(select.findAllComponents(ElOption)).toHaveLength(0)
    await button(drawer, '接入可用工具').trigger('click')
    await flushPromises()
    drawer.getComponent(ToolsPage).vm.$emit('confirmed', {
      request_id: 'request',
      connector_id: 'server',
      status: 'SUCCEEDED',
      tool_names: ['new_tool'],
      items: [
        {
          tool_name: 'new_tool',
          definition_id: '40',
          version_id: '41',
          deployment_key: 'service-one',
          environment: 'PRODUCTION'
        }
      ]
    })
    await flushPromises()
    expect(select.props('modelValue')).toBe('40')
    expect(select.findAllComponents(ElOption).map((option) => option.props('label'))).toContain(
      '新工具'
    )
    expect(button(drawer, '接入可用工具').exists()).toBe(true)
    expect(mocks.create).not.toHaveBeenCalled()
  })
  it('工具选择展示说明与服务来源，支持原调用名和说明来源搜索且保持原挂载身份', async () => {
    mocks.availableTools.mockResolvedValue({
      items: [
        {
          definition_id: '30',
          default_version_id: '31',
          name: '资料工具',
          description: '查找订单',
          tool_name: 'read_order',
          deployments: [
            { deployment_key: 'service-one', provider_label: '业务服务', environment: 'PRODUCTION' }
          ]
        },
        {
          definition_id: '40',
          default_version_id: '41',
          name: '资料工具',
          description: '读取患者资料',
          tool_name: 'read_patient',
          deployments: [
            { deployment_key: 'service-two', provider_label: '患者服务', environment: 'PRODUCTION' }
          ]
        }
      ]
    })
    const { drawer } = await open('TOOL')
    const select = drawer.findAllComponents(ElSelect)[1]!
    expect(document.body.textContent).toContain('查找订单')
    expect(document.body.textContent).toContain('业务服务')
    const filter = select.props('filterMethod')!
    for (const query of ['READ_ORDER', '订单', '业务服务']) {
      filter(query)
      await flushPromises()
      expect(select.findAllComponents(ElOption).map((item) => item.props('value'))).toEqual(['30'])
    }
    select.vm.$emit('update:modelValue', '30')
    await flushPromises()
    filter('患者')
    await flushPromises()
    await button(drawer, '添加能力').trigger('click')
    await flushPromises()
    expect(mocks.create).toHaveBeenCalledWith('agent-1', {
      capabilityDefinitionId: '30',
      capabilityType: 'TOOL',
      priority: 0,
      toolConfig: { toolDeploymentId: 'service-one' }
    })
  })
  it('唯一未挂载工具和服务自动选中，可直接添加', async () => {
    mocks.availableTools.mockResolvedValue({
      items: [
        {
          definition_id: '30',
          default_version_id: '31',
          name: '资料读取',
          description: '',
          tool_name: 'read',
          deployments: [
            { deployment_key: 'service-one', provider_label: '资料服务', environment: 'PRODUCTION' }
          ]
        }
      ]
    })
    const { drawer } = await open('TOOL')
    expect(drawer.findAllComponents(ElSelect)[1]!.props('modelValue')).toBe('30')
    await button(drawer, '添加能力').trigger('click')
    await flushPromises()
    expect(mocks.create).toHaveBeenCalledWith(
      'agent-1',
      expect.objectContaining({
        capabilityDefinitionId: '30',
        toolConfig: { toolDeploymentId: 'service-one' }
      })
    )
  })
  it('唯一工具有多个服务时仍须选择服务', async () => {
    mocks.availableTools.mockResolvedValue({
      items: [
        {
          definition_id: '30',
          default_version_id: '31',
          name: '资料读取',
          description: '',
          tool_name: 'read',
          deployments: [
            { deployment_key: 'one', provider_label: '一', environment: 'PRODUCTION' },
            { deployment_key: 'two', provider_label: '二', environment: 'PRODUCTION' }
          ]
        }
      ]
    })
    const { drawer } = await open('TOOL')
    expect(drawer.findAllComponents(ElSelect)[1]!.props('modelValue')).toBe('30')
    expect(drawer.findAllComponents(ElSelect)[2]!.props('modelValue')).toBe('')
    await button(drawer, '添加能力').trigger('click')
    await flushPromises()
    expect(mocks.create).not.toHaveBeenCalled()
  })
  it('工具只有一个可用服务时直接选择并挂载，不再要求选择部署', async () => {
    mocks.availableTools.mockResolvedValue({
      items: [
        {
          definition_id: '30',
          default_version_id: '31',
          name: '资料读取',
          description: '',
          tool_name: 'read',
          deployments: [
            { deployment_key: 'service-one', provider_label: '资料服务', environment: 'PRODUCTION' }
          ]
        }
      ]
    })
    const { drawer } = await open('TOOL')
    drawer.findAllComponents(ElSelect)[1]!.vm.$emit('update:modelValue', '30')
    await flushPromises()
    expect(drawer.findAllComponents(ElFormItem).map((item) => item.props('label'))).not.toContain(
      '服务实例'
    )
    await button(drawer, '添加能力').trigger('click')
    await flushPromises()
    expect(mocks.create).toHaveBeenCalledWith('agent-1', {
      capabilityDefinitionId: '30',
      capabilityType: 'TOOL',
      priority: 0,
      toolConfig: { toolDeploymentId: 'service-one' }
    })
    expect(mocks.deployments).not.toHaveBeenCalled()
  })
  it('刷新后原服务失效时要求明确选择剩余实例，避免静默切换服务', async () => {
    const tool = {
      definition_id: '30',
      default_version_id: '31',
      name: '资料读取',
      description: '',
      tool_name: 'read',
      deployments: [
        { deployment_key: 'service-one', provider_label: '资料一', environment: 'PRODUCTION' }
      ]
    }
    mocks.availableTools
      .mockResolvedValueOnce({ items: [tool] })
      .mockResolvedValueOnce({ items: [tool] })
      .mockResolvedValueOnce({
        items: [
          {
            ...tool,
            deployments: [
              { deployment_key: 'service-two', provider_label: '资料二', environment: 'PRODUCTION' }
            ]
          }
        ]
      })
    const { drawer } = await open('TOOL')
    drawer.findAllComponents(ElSelect)[1]!.vm.$emit('update:modelValue', '30')
    await flushPromises()
    await button(drawer, '刷新可用工具').trigger('click')
    await flushPromises()
    expect(drawer.findAllComponents(ElFormItem).map((item) => item.props('label'))).not.toContain(
      '服务实例'
    )
    await button(drawer, '刷新可用工具').trigger('click')
    await flushPromises()
    expect(drawer.findAllComponents(ElFormItem).map((item) => item.props('label'))).toContain(
      '服务实例'
    )
    await button(drawer, '添加能力').trigger('click')
    await flushPromises()
    expect(mocks.create).not.toHaveBeenCalled()
    drawer.findAllComponents(ElSelect)[2]!.vm.$emit('update:modelValue', 'service-two')
    await flushPromises()
    await button(drawer, '添加能力').trigger('click')
    await flushPromises()
    expect(mocks.create).toHaveBeenCalledWith(
      'agent-1',
      expect.objectContaining({ toolConfig: { toolDeploymentId: 'service-two' } })
    )
  })
  it('同一工具有多个服务实例时明确选择后才挂载', async () => {
    mocks.availableTools.mockResolvedValue({
      items: [
        {
          definition_id: '30',
          default_version_id: '31',
          name: '资料读取',
          description: '',
          tool_name: 'read',
          deployments: [
            { deployment_key: 'service-one', provider_label: '资料一', environment: 'TEST' },
            { deployment_key: 'service-two', provider_label: '资料二', environment: 'PRODUCTION' }
          ]
        }
      ]
    })
    const { drawer } = await open('TOOL')
    drawer.findAllComponents(ElSelect)[1]!.vm.$emit('update:modelValue', '30')
    await flushPromises()
    await button(drawer, '添加能力').trigger('click')
    await flushPromises()
    expect(mocks.create).not.toHaveBeenCalled()
    drawer.findAllComponents(ElSelect)[2]!.vm.$emit('update:modelValue', 'service-two')
    await flushPromises()
    await button(drawer, '添加能力').trigger('click')
    await flushPromises()
    expect(mocks.create).toHaveBeenCalledWith(
      'agent-1',
      expect.objectContaining({ toolConfig: { toolDeploymentId: 'service-two' } })
    )
  })
  it.each([
    ['SKILL', 0, 0, 0],
    ['TOOL', 0, 0, 0],
    ['WORKFLOW', 0, 0, 0],
    ['KNOWLEDGE_SOURCE', 0, 0, 1]
  ] as const)('%s只读取其实际消费的关联列表', async (type, deployments, instances, bases) => {
    const { drawer } = await open(type)
    expect(drawer.props('modelValue')).toBe(true)
    expect(mocks.deployments).toHaveBeenCalledTimes(deployments)
    expect(mocks.instances).toHaveBeenCalledTimes(instances)
    expect(mocks.workflowOptions).toHaveBeenCalledTimes(type === 'WORKFLOW' ? 1 : 0)
    if (type === 'WORKFLOW') expect(mocks.versions).not.toHaveBeenCalled()
    expect(mocks.bases).toHaveBeenCalledTimes(bases)
    expect(mocks.definitions.mock.calls.map((call) => call[0])).toEqual(
      type === 'SKILL' || type === 'TOOL' || type === 'KNOWLEDGE_SOURCE'
        ? ['SKILL']
        : ['SKILL', type]
    )
  })

  it('唯一未挂载技能自动选中，可直接添加', async () => {
    const { drawer } = await open('SKILL')
    expect(drawer.findAllComponents(ElSelect)[1]!.props('modelValue')).toBe('10')
    await button(drawer, '添加能力').trigger('click')
    await flushPromises()
    expect(mocks.create).toHaveBeenCalledWith(
      'agent-1',
      expect.objectContaining({ capabilityType: 'SKILL', capabilityDefinitionId: '10' })
    )
  })
  it('无工作流读取权限仍能挂载Skill，不发工作流或工具关联请求', async () => {
    mocks.instances.mockRejectedValue(new Error('403 foundation.workflows.read'))
    mocks.versions.mockResolvedValue([
      { id: '21', definitionId: '10', capabilityType: 'SKILL', name: '技能', versionKey: '1' }
    ])
    const { drawer } = await open('SKILL')
    drawer.findAllComponents(ElSelect)[1]!.vm.$emit('update:modelValue', '10')
    await flushPromises()
    await button(drawer, '添加能力').trigger('click')
    await flushPromises()
    expect(mocks.create).toHaveBeenCalledWith(
      'agent-1',
      expect.objectContaining({ capabilityType: 'SKILL', capabilityDefinitionId: '10' })
    )
    expect(mocks.instances).not.toHaveBeenCalled()
    expect(mocks.deployments).not.toHaveBeenCalled()
    expect(mocks.requirementOptions).toHaveBeenCalledWith('SKILL')
    expect(mocks.versions).not.toHaveBeenCalled()
  })
  it('没有已发布技能时提供工作台入口，发布后原地刷新并选择', async () => {
    mocks.permissions.push('foundation.skills.read')
    mocks.definitions.mockResolvedValueOnce([])
    const { drawer } = await open('SKILL')
    expect(document.body.textContent).toContain('当前没有可选的已发布技能')
    expect(document.querySelector('a[href="/admin/skills"]')).not.toBeNull()
    await button(drawer, '刷新技能选项').trigger('click')
    await flushPromises()
    expect(document.body.textContent).not.toContain('当前没有可选的已发布技能')
    drawer.findAllComponents(ElSelect)[1]!.vm.$emit('update:modelValue', '10')
    await flushPromises()
    await button(drawer, '添加能力').trigger('click')
    await flushPromises()
    expect(mocks.create).toHaveBeenCalledWith(
      'agent-1',
      expect.objectContaining({ capabilityDefinitionId: '10', capabilityType: 'SKILL' })
    )
  })
  it('没有技能工作台读取权限时提示联系管理员', async () => {
    mocks.definitions.mockResolvedValueOnce([])
    await open('SKILL')
    expect(document.body.textContent).toContain('请联系管理员创建并发布技能')
    expect(document.querySelector('a[href="/admin/skills"]')).toBeNull()
  })
  it('同账号发布技能后自动刷新目录，不为其他账号刷新或自动挂载', async () => {
    const channels: FakeChannel[] = []
    class FakeChannel {
      onmessage?: (event: { data: string }) => void
      close = vi.fn()
      name: string
      constructor(name: string) {
        this.name = name
        channels.push(this)
      }
    }
    vi.stubGlobal('BroadcastChannel', FakeChannel)
    try {
      mocks.definitions.mockResolvedValueOnce([])
      const { drawer } = await open('SKILL')
      const channel = channels.find((item) => item.name === 'skillforge.skill.published')!
      expect(channel).toBeDefined()
      expect(document.body.textContent).toContain('当前没有可选的已发布技能')
      const before = mocks.definitions.mock.calls.length
      channel.onmessage!({ data: JSON.stringify(['other', 'actor-1']) })
      await flushPromises()
      expect(mocks.definitions).toHaveBeenCalledTimes(before)
      channel.onmessage!({ data: JSON.stringify(['1', 'actor-1']) })
      await flushPromises()
      expect(mocks.definitions).toHaveBeenCalledTimes(before + 1)
      expect(drawer.findAllComponents(ElOption).some((item) => item.props('value') === '10')).toBe(
        true
      )
      expect(mocks.create).not.toHaveBeenCalled()
    } finally {
      vi.unstubAllGlobals()
    }
  })
  it('技能目录读取忙碌时暂存同账号通知并在结束后刷新', async () => {
    const channels: FakeChannel[] = []
    class FakeChannel {
      onmessage?: (event: { data: string }) => void
      close = vi.fn()
      name: string
      constructor(name: string) {
        this.name = name
        channels.push(this)
      }
    }
    vi.stubGlobal('BroadcastChannel', FakeChannel)
    try {
      const { drawer } = await open('SKILL')
      let finish!: (value: unknown) => void
      mocks.definitions.mockReturnValueOnce(
        new Promise((resolve) => {
          finish = resolve
        })
      )
      await button(drawer, '刷新技能选项').trigger('click')
      await flushPromises()
      const before = mocks.definitions.mock.calls.length
      channels.find((item) => item.name === 'skillforge.skill.published')!.onmessage!({
        data: JSON.stringify(['1', 'actor-1'])
      })
      expect(mocks.definitions).toHaveBeenCalledTimes(before)
      finish([{ id: '10', defaultVersionId: '21', name: '服务流程' }])
      await flushPromises()
      expect(mocks.definitions).toHaveBeenCalledTimes(before + 1)
      expect(mocks.create).not.toHaveBeenCalled()
    } finally {
      vi.unstubAllGlobals()
    }
  })
  it('通知排队后安全会话已改变时不读取新上下文', async () => {
    const channels: FakeChannel[] = []
    class FakeChannel {
      onmessage?: (event: { data: string }) => void
      close = vi.fn()
      name: string
      constructor(name: string) {
        this.name = name
        channels.push(this)
      }
    }
    vi.stubGlobal('BroadcastChannel', FakeChannel)
    try {
      const { drawer } = await open('SKILL')
      let finish!: (value: unknown) => void
      mocks.definitions.mockReturnValueOnce(
        new Promise((resolve) => {
          finish = resolve
        })
      )
      await button(drawer, '刷新技能选项').trigger('click')
      await flushPromises()
      const before = mocks.definitions.mock.calls.length
      channels.find((item) => item.name === 'skillforge.skill.published')!.onmessage!({
        data: JSON.stringify(['1', 'actor-1'])
      })
      mocks.browserSession = 'another-session'
      finish([{ id: '10', defaultVersionId: '21', name: '服务流程' }])
      await flushPromises()
      expect(mocks.definitions).toHaveBeenCalledTimes(before)
      expect(mocks.create).not.toHaveBeenCalled()
    } finally {
      vi.unstubAllGlobals()
    }
  })
  it('技能低预算解释不改写额度或阻止显式挂载，提高预算后恢复', async () => {
    mocks.requirementOptions.mockResolvedValue([
      { id: '10', name: '技能', versionId: '21', versionKey: '2', skillEstimatedTokens: 31 }
    ])
    const { drawer } = await open('SKILL')
    drawer.findAllComponents(ElSelect)[1]!.vm.$emit('update:modelValue', '10')
    await flushPromises()
    const budget = drawer
      .findAllComponents(ElInputNumber)
      .find((item) => item.props('modelValue') === 2000)!
    expect(document.body.textContent).toContain('技能入口估算 31 Token')
    budget.vm.$emit('update:modelValue', 1)
    await flushPromises()
    expect(document.body.textContent).toContain('不会加载该技能入口，ALWAYS 技能也会跳过')
    expect(button(drawer, '添加能力').props('disabled')).toBe(false)
    expect(budget.props('modelValue')).toBe(1)
    budget.vm.$emit('update:modelValue', 31)
    await flushPromises()
    expect(document.body.textContent).not.toContain('不会加载该技能入口')
    expect(mocks.getVersion).not.toHaveBeenCalled()
    await button(drawer, '添加能力').trigger('click')
    await flushPromises()
    expect(mocks.create).toHaveBeenCalledWith(
      'agent-1',
      expect.objectContaining({ skillConfig: { tokenBudget: 31 } })
    )
  })
  it('手动刷新目录保留选择与预算，版本变化后需确认', async () => {
    mocks.versions.mockResolvedValue([
      { id: '21', definitionId: '10', capabilityType: 'SKILL', name: '技能', versionKey: '1' }
    ])
    const { drawer } = await open('SKILL')
    drawer.findAllComponents(ElSelect)[1]!.vm.$emit('update:modelValue', '10')
    await flushPromises()
    const budget = drawer
      .findAllComponents(ElInputNumber)
      .find((item) => item.props('modelValue') === 2000)!
    budget.vm.$emit('update:modelValue', 3000)
    mocks.definitions.mockResolvedValueOnce([
      { id: '10', defaultVersionId: '22', name: '技能' },
      { id: '11', defaultVersionId: '23', name: '新技能' }
    ])
    mocks.requirementOptions.mockResolvedValueOnce([
      { id: '10', name: '技能', versionId: '22', versionKey: '2' },
      { id: '11', name: '新技能', versionId: '23', versionKey: '2' }
    ])
    mocks.versions.mockImplementation(async (id: string) => [
      {
        id: id === '10' ? '22' : '23',
        definitionId: id,
        capabilityType: 'SKILL',
        name: '技能',
        versionKey: '2'
      }
    ])
    await button(drawer, '刷新技能选项').trigger('click')
    await flushPromises()
    expect(drawer.findAllComponents(ElSelect)[1]!.props('modelValue')).toBe('10')
    expect(budget.props('modelValue')).toBe(3000)
    expect(
      drawer.findAllComponents(ElOption).some((option) => option.props('value') === '11')
    ).toBe(true)
    expect(document.body.textContent).toContain('所选技能的当前发布版本已变化')
    expect(button(drawer, '添加能力').props('disabled')).toBe(true)
    await button(drawer, '确认当前发布版本').trigger('click')
    await flushPromises()
    await button(drawer, '添加能力').trigger('click')
    await flushPromises()
    expect(mocks.create).toHaveBeenCalledWith(
      'agent-1',
      expect.objectContaining({
        capabilityDefinitionId: '10',
        skillConfig: expect.objectContaining({ tokenBudget: 3000 })
      })
    )
  })
  it('通知触发刷新失败时禁止添加，手动刷新成功后恢复', async () => {
    const channels: FakeChannel[] = []
    class FakeChannel {
      onmessage?: (event: { data: string }) => void
      close = vi.fn()
      name: string
      constructor(name: string) {
        this.name = name
        channels.push(this)
      }
    }
    vi.stubGlobal('BroadcastChannel', FakeChannel)
    try {
      const { drawer } = await open('SKILL')
      mocks.definitions.mockRejectedValueOnce(new Error('目录读取失败'))
      channels.find((item) => item.name === 'skillforge.skill.published')!.onmessage!({
        data: JSON.stringify(['1', 'actor-1'])
      })
      await flushPromises()
      expect(button(drawer, '添加能力').props('disabled')).toBe(true)
      expect(document.body.textContent).toContain('技能目录需要重新核对')
      mocks.requirementOptions.mockResolvedValueOnce([
        { id: '10', name: '服务流程', versionId: '22', versionKey: '3' }
      ])
      await button(drawer, '刷新技能选项').trigger('click')
      await flushPromises()
      expect(button(drawer, '添加能力').props('disabled')).toBe(true)
      expect(document.body.textContent).toContain('所选技能的当前发布版本已变化')
      await button(drawer, '确认当前发布版本').trigger('click')
      await flushPromises()
      expect(button(drawer, '添加能力').props('disabled')).toBe(false)
    } finally {
      vi.unstubAllGlobals()
    }
  })
  it('首次读取技能目录期间浏览器会话改变时不采用迟到结果', async () => {
    let finish!: (value: unknown) => void
    mocks.definitions.mockReturnValueOnce(
      new Promise((resolve) => {
        finish = resolve
      })
    )
    const wrapper = mount(AgentManagePage, {
      attachTo: document.body,
      global: { stubs: { RouterLink: true } }
    })
    wrappers.push(wrapper)
    await flushPromises()
    await button(wrapper, '添加能力').trigger('click')
    await flushPromises()
    mocks.browserSession = 'another-session'
    finish([{ id: '10', defaultVersionId: '21', name: '服务流程' }])
    await flushPromises()
    expect(wrapper.findComponent(ElDrawer).props('modelValue')).toBe(false)
    expect(mocks.create).not.toHaveBeenCalled()
  })
  it('打开抽屉首次读取期间收到发布通知，打开后重新读取目录', async () => {
    const channels: FakeChannel[] = []
    class FakeChannel {
      onmessage?: (event: { data: string }) => void
      close = vi.fn()
      name: string
      constructor(name: string) {
        this.name = name
        channels.push(this)
      }
    }
    vi.stubGlobal('BroadcastChannel', FakeChannel)
    try {
      let finish!: (value: unknown) => void
      mocks.definitions.mockReturnValueOnce(
        new Promise((resolve) => {
          finish = resolve
        })
      )
      const wrapper = mount(AgentManagePage, {
        attachTo: document.body,
        global: { stubs: { RouterLink: true } }
      })
      wrappers.push(wrapper)
      await flushPromises()
      await button(wrapper, '添加能力').trigger('click')
      await flushPromises()
      channels.find((item) => item.name === 'skillforge.skill.published')!.onmessage!({
        data: JSON.stringify(['1', 'actor-1'])
      })
      mocks.requirementOptions.mockResolvedValueOnce([
        { id: '10', name: '服务流程', versionId: '22', versionKey: '3' }
      ])
      finish([{ id: '10', defaultVersionId: '21', name: '服务流程' }])
      await flushPromises()
      expect(mocks.definitions).toHaveBeenCalledTimes(2)
      expect(document.body.textContent).toContain('所选技能的当前发布版本已变化')
      expect(button(wrapper.findComponent(ElDrawer), '添加能力').props('disabled')).toBe(true)
      expect(mocks.create).not.toHaveBeenCalled()
    } finally {
      vi.unstubAllGlobals()
    }
  })
  it('通知刷新期间切换选择仍更新目录，并要求确认变化的版本', async () => {
    const channels: FakeChannel[] = []
    class FakeChannel {
      onmessage?: (event: { data: string }) => void
      close = vi.fn()
      name: string
      constructor(name: string) {
        this.name = name
        channels.push(this)
      }
    }
    vi.stubGlobal('BroadcastChannel', FakeChannel)
    try {
      mocks.definitions.mockResolvedValueOnce([
        { id: '10', defaultVersionId: '21', name: '技能一' },
        { id: '11', defaultVersionId: '23', name: '技能二' }
      ])
      mocks.requirementOptions.mockResolvedValueOnce([
        { id: '10', name: '技能一', versionId: '21', versionKey: '1' },
        { id: '11', name: '技能二', versionId: '23', versionKey: '1' }
      ])
      const { drawer } = await open('SKILL')
      let finish!: (value: unknown) => void
      mocks.definitions.mockReturnValueOnce(
        new Promise((resolve) => {
          finish = resolve
        })
      )
      mocks.requirementOptions.mockResolvedValueOnce([
        { id: '10', name: '技能一', versionId: '21', versionKey: '1' },
        { id: '11', name: '技能二', versionId: '24', versionKey: '2' }
      ])
      channels.find((item) => item.name === 'skillforge.skill.published')!.onmessage!({
        data: JSON.stringify(['1', 'actor-1'])
      })
      drawer.findAllComponents(ElSelect)[1]!.vm.$emit('update:modelValue', '11')
      await flushPromises()
      finish([
        { id: '10', name: '技能一' },
        { id: '11', name: '技能二' }
      ])
      await flushPromises()
      expect(drawer.findAllComponents(ElSelect)[1]!.props('modelValue')).toBe('11')
      expect(document.body.textContent).toContain('所选技能的当前发布版本已变化')
      expect(button(drawer, '添加能力').props('disabled')).toBe(true)
      await button(drawer, '确认当前发布版本').trigger('click')
      await flushPromises()
      expect(button(drawer, '添加能力').props('disabled')).toBe(false)
    } finally {
      vi.unstubAllGlobals()
    }
  })
  it('刷新技能目录失败后保留已选技能，允许重试', async () => {
    const { drawer } = await open('SKILL')
    drawer.findAllComponents(ElSelect)[1]!.vm.$emit('update:modelValue', '10')
    await flushPromises()
    mocks.definitions.mockRejectedValueOnce(new Error('目录读取失败'))
    await button(drawer, '刷新技能选项').trigger('click')
    await flushPromises()
    expect(drawer.findAllComponents(ElSelect)[1]!.props('modelValue')).toBe('10')
    expect(document.body.textContent).toContain('目录读取失败')
    await button(drawer, '刷新技能选项').trigger('click')
    await flushPromises()
    expect(drawer.findAllComponents(ElSelect)[1]!.props('modelValue')).toBe('10')
  })
  it('技能选择可按用途和标识搜索同名能力，挂载选中的身份', async () => {
    mocks.requirementOptions.mockResolvedValue([
      { id: '10', name: '服务技能', versionId: '21', versionKey: '1' },
      { id: '11', name: '服务技能', versionId: '22', versionKey: '1' }
    ])
    mocks.definitions.mockResolvedValue([
      {
        id: '10',
        defaultVersionId: '21',
        name: '服务技能',
        capabilityKey: 'orders',
        description: '查询订单'
      },
      {
        id: '11',
        defaultVersionId: '22',
        name: '服务技能',
        capabilityKey: 'patients',
        description: '查询患者'
      }
    ])
    mocks.versions.mockImplementation(async (id: string) => [
      {
        id: id === '10' ? '21' : '22',
        definitionId: id,
        capabilityType: 'SKILL',
        name: '服务技能',
        versionKey: '1'
      }
    ])
    const { drawer } = await open('SKILL')
    const select = drawer.findAllComponents(ElSelect)[1]!
    select.props('filterMethod')!('患者')
    await flushPromises()
    expect(select.findAllComponents(ElOption).map((option) => option.props('value'))).toEqual([
      '11'
    ])
    select.props('filterMethod')!('patients')
    await flushPromises()
    expect(select.findAllComponents(ElOption).map((option) => option.props('value'))).toEqual([
      '11'
    ])
    select.vm.$emit('update:modelValue', '11')
    await flushPromises()
    await button(drawer, '添加能力').trigger('click')
    await flushPromises()
    expect(mocks.create).toHaveBeenCalledWith(
      'agent-1',
      expect.objectContaining({ capabilityDefinitionId: '11' })
    )
  })

  it('首次准备挂载失败保留顶部错误，不打开抽屉或继续下一批读取清错', async () => {
    mocks.definitions.mockRejectedValueOnce(new Error('能力目录读取失败'))
    const wrapper = mount(AgentManagePage, { attachTo: document.body })
    wrappers.push(wrapper)
    await flushPromises()
    await button(wrapper, '添加能力').trigger('click')
    await flushPromises()
    expect(wrapper.findComponent(ElDrawer).props('modelValue')).toBe(false)
    expect(document.body.textContent).toContain('能力目录读取失败')
    expect(mocks.deployments).not.toHaveBeenCalled()
    expect(mocks.instances).not.toHaveBeenCalled()
    expect(mocks.create).not.toHaveBeenCalled()
  })

  it('工具目录失败原地重读成功，只读取当前目录且保留配置草稿', async () => {
    const { wrapper, drawer } = await open('SKILL')
    const name = wrapper
      .findAllComponents(ElInput)
      .find((item) => item.props('modelValue') === '客服')!
    name.vm.$emit('update:modelValue', '尚未保存的名称')
    const priority = drawer.findAllComponents(ElInputNumber)[0]!
    priority.vm.$emit('update:modelValue', 7)
    const budget = drawer.findAllComponents(ElInputNumber)[1]!
    budget.vm.$emit('update:modelValue', 3500)
    mocks.availableTools.mockRejectedValueOnce(
      new HttpClientError({ status: 503, userMessage: '可用工具读取失败' })
    )
    const type = drawer.findAllComponents(ElSelect)[0]!
    type.vm.$emit('update:modelValue', 'TOOL')
    type.vm.$emit('change', 'TOOL')
    await flushPromises()
    expect(drawer.findComponent(ElForm).find('a[href="/admin/tools"]').exists()).toBe(false)
    expect(button(drawer, '添加能力').get('button').attributes('disabled')).toBeDefined()
    expect(document.querySelectorAll('.el-message')).toHaveLength(1)
    const reads = [
      mocks.get.mock.calls.length,
      mocks.models.mock.calls.length,
      mocks.agents.mock.calls.length,
      mocks.definitions.mock.calls.length
    ]
    mocks.availableTools.mockResolvedValueOnce({
      items: [
        {
          definition_id: '30',
          default_version_id: '31',
          name: '资料读取',
          description: '',
          tool_name: 'read',
          deployments: [
            { deployment_key: 'service-one', provider_label: '资料服务', environment: 'PRODUCTION' }
          ]
        }
      ]
    })
    await button(drawer, '重试当前能力选项').trigger('click')
    await flushPromises()
    expect(mocks.availableTools).toHaveBeenCalledTimes(2)
    expect([
      mocks.get.mock.calls.length,
      mocks.models.mock.calls.length,
      mocks.agents.mock.calls.length,
      mocks.definitions.mock.calls.length
    ]).toEqual(reads)
    expect(name.props('modelValue')).toBe('尚未保存的名称')
    expect(priority.props('modelValue')).toBe(7)
    expect(button(drawer, '重试当前能力选项')).toBeUndefined()
    expect(
      drawer
        .findAllComponents(ElSelect)[1]!
        .findAllComponents(ElOption)
        .map((item) => item.props('value'))
    ).toEqual(['30'])
    type.vm.$emit('update:modelValue', 'SKILL')
    type.vm.$emit('change', 'SKILL')
    await flushPromises()
    expect(drawer.findAllComponents(ElInputNumber)[1]!.props('modelValue')).toBe(3500)
    expect(mocks.create).not.toHaveBeenCalled()
    expect(mocks.update).not.toHaveBeenCalled()
    expect(mocks.publish).not.toHaveBeenCalled()
  })

  it('工具目录可用时显示原页面接入入口', async () => {
    const { drawer } = await open('TOOL')
    expect(button(drawer, '接入可用工具')).toBeDefined()
    expect(button(drawer, '重试当前能力选项')).toBeUndefined()
  })

  it.each(['workflowOptions', 'requirementOptions'] as const)(
    '工作流%s目录失败后原地重读完整匹配组，不自动挂载',
    async (resource) => {
      const { drawer } = await open('SKILL')
      mocks[resource].mockRejectedValueOnce(new Error('当前工作流目录读取失败'))
      const type = drawer.findAllComponents(ElSelect)[0]!
      type.vm.$emit('update:modelValue', 'WORKFLOW')
      type.vm.$emit('change', 'WORKFLOW')
      await flushPromises()
      expect(button(drawer, '添加能力').get('button').attributes('disabled')).toBeDefined()
      expect(drawer.findAllComponents(ElSelect)[1]!.findAllComponents(ElOption)).toHaveLength(0)
      const mainReads = mocks.get.mock.calls.length
      await button(drawer, '重试当前能力选项').trigger('click')
      await flushPromises()
      drawer.findAllComponents(ElSelect)[1]!.vm.$emit('update:modelValue', '10')
      await flushPromises()
      expect(
        drawer
          .findAllComponents(ElSelect)[2]!
          .findAllComponents(ElOption)
          .map((item) => item.props('value'))
      ).toEqual(['9223372036854775807', '9223372036854775806'])
      expect(mocks.get).toHaveBeenCalledTimes(mainReads)
      expect(mocks.availableTools).not.toHaveBeenCalled()
      expect(mocks.create).not.toHaveBeenCalled()
    }
  )

  it('目录读取期间已有表单禁用实际类型控件，不需要跨类型迟到恢复协议', async () => {
    const { drawer } = await open('SKILL')
    let finish!: (value: { items: [] }) => void
    mocks.availableTools.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve
        })
    )
    const type = drawer.findAllComponents(ElSelect)[0]!
    type.vm.$emit('update:modelValue', 'TOOL')
    type.vm.$emit('change', 'TOOL')
    await flushPromises()
    expect(drawer.findComponent(ElForm).props('disabled')).toBe(true)
    expect(type.find('.el-select__wrapper').classes()).toContain('is-disabled')
    expect(button(drawer, '添加能力').get('button').attributes('disabled')).toBeDefined()
    expect(drawer.findComponent(ElForm).find('a[href="/admin/tools"]').exists()).toBe(false)
    finish({ items: [] })
    await flushPromises()
    expect(drawer.findComponent(ElForm).props('disabled')).toBe(false)
    expect(type.props('modelValue')).toBe('TOOL')
    expect(button(drawer, '接入可用工具')).toBeDefined()
  })

  it('切换类型关联读取失败后不保留上一类的能力或部署', async () => {
    const { drawer } = await open('SKILL')
    drawer.findAllComponents(ElSelect)[1]!.vm.$emit('update:modelValue', '10')
    await flushPromises()
    mocks.availableTools.mockRejectedValueOnce(new Error('可用工具读取失败'))
    const type = drawer.findAllComponents(ElSelect)[0]!
    type.vm.$emit('update:modelValue', 'TOOL')
    type.vm.$emit('change', 'TOOL')
    await flushPromises()
    expect(drawer.findAllComponents(ElSelect)[1]!.findAllComponents(ElOption)).toHaveLength(0)
    expect(document.body.textContent).toContain('可用工具读取失败')
    expect(mocks.create).not.toHaveBeenCalled()
    expect(mocks.instances).not.toHaveBeenCalled()
  })
})

describe('能力挂载关联一致性', () => {
  it('业务模型额度采用新 wire 字段，清空后继承而不是填入前端默认值', async () => {
    const wrapper = mount(AgentManagePage, { attachTo: document.body })
    wrappers.push(wrapper)
    await flushPromises()
    await wrapper
      .findAll('[role="tab"]')
      .find((tab) => tab.text() === '运行与协作')!
      .trigger('click')
    await flushPromises()
    await wrapper.get('.el-collapse-item__header').trigger('click')
    await flushPromises()
    const fields = wrapper.findAllComponents(ElInputNumber)
    expect(wrapper.text()).toContain('继承场景与角色默认额度，任务受理时冻结')
    fields[0]!.vm.$emit('update:modelValue', 7)
    mocks.get.mockResolvedValueOnce({
      id: 'agent-1',
      name: '客服',
      model_config_id: 'model-1',
      system_prompt: '按机构规则协助客户',
      status: 'published',
      published_revision: 2,
      has_draft_changes: true,
      mounts: [],
      execution_config: { max_business_model_calls: 7 },
      delegation_config: { enabled: false, target_agent_ids: [] }
    })
    await button(wrapper, '保存草稿').trigger('click')
    await flushPromises()
    expect(mocks.update).toHaveBeenLastCalledWith(
      'agent-1',
      expect.objectContaining({ execution_config: { max_business_model_calls: 7 } })
    )
    expect(mocks.update.mock.calls.at(-1)![1].execution_config).not.toHaveProperty('max_iterations')
    wrapper.findAllComponents(ElInputNumber)[0]!.vm.$emit('update:modelValue', undefined)
    await flushPromises()
    await button(wrapper, '保存草稿').trigger('click')
    await flushPromises()
    expect(mocks.update).toHaveBeenCalledTimes(2)
    expect(JSON.parse(JSON.stringify(mocks.update.mock.calls.at(-1)![1].execution_config))).toEqual(
      {}
    )
  })
  it('已发布智能体继续保存同一身份的工作草稿', async () => {
    const wrapper = mount(AgentManagePage, { attachTo: document.body })
    wrappers.push(wrapper)
    await flushPromises()
    expect(wrapper.text()).not.toContain('创建新修订')
    wrapper
      .findAllComponents(ElInput)
      .find((input) => input.props('modelValue') === '客服')!
      .vm.$emit('update:modelValue', '客服更新')
    await flushPromises()
    await button(wrapper, '保存草稿').trigger('click')
    await flushPromises()
    expect(mocks.update).toHaveBeenCalledWith(
      'agent-1',
      expect.objectContaining({ name: '客服更新' })
    )
  })
  it('草稿保存成功但状态读取失败时保留保存事实，重新读取后才恢复配置操作', async () => {
    const wrapper = mount(AgentManagePage, { attachTo: document.body })
    wrappers.push(wrapper)
    await flushPromises()
    wrapper
      .findAllComponents(ElInput)
      .find((input) => input.props('modelValue') === '客服')!
      .vm.$emit('update:modelValue', '客服更新')
    mocks.get.mockRejectedValueOnce(Error('读取暂时失败'))
    await flushPromises()
    await button(wrapper, '保存草稿').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('操作已完成，当前显示内容尚未更新')
    expect(button(wrapper, '保存草稿').props('disabled')).toBe(true)
    expect(button(wrapper, '发布').props('disabled')).toBe(true)
    expect(button(wrapper, '添加能力').props('disabled')).toBe(true)
    await button(wrapper, '重新读取').trigger('click')
    await flushPromises()
    expect(button(wrapper, '保存草稿').props('disabled')).toBe(false)
    expect(mocks.update).toHaveBeenCalledTimes(1)
  })
  it('发布历史恢复到同一工作草稿，并明确再次发布后生效', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue(
      'confirm' as Awaited<ReturnType<typeof ElMessageBox.confirm>>
    )
    mocks.publications.mockResolvedValue({
      items: [
        {
          revision_id: 9,
          revision: 1,
          name: '原始客服',
          published_at: '2026-10-08',
          current: false
        }
      ]
    })
    mocks.restore.mockResolvedValue({ id: 'agent-1' })
    const wrapper = mount(AgentManagePage, { attachTo: document.body })
    wrappers.push(wrapper)
    await flushPromises()
    await wrapper
      .findAll('[role="tab"]')
      .find((tab) => tab.text() === '发布历史')!
      .trigger('click')
    await flushPromises()
    await button(wrapper, '恢复为草稿').trigger('click')
    await flushPromises()
    expect(mocks.restore).toHaveBeenCalledWith('agent-1', 1)
    expect(mocks.publish).not.toHaveBeenCalled()
    expect(mocks.get).toHaveBeenLastCalledWith('agent-1')
    expect(ElMessageBox.confirm).toHaveBeenCalledWith(
      expect.anything(),
      '恢复智能体草稿',
      expect.objectContaining({ confirmButtonText: '恢复为草稿' }),
      undefined
    )
  })
  it.each(['SKILL', 'TOOL', 'KNOWLEDGE_SOURCE'] as const)(
    '%s 已挂载时避免重复添加',
    async (type) => {
      const agent = await mocks.get()
      mocks.get.mockResolvedValue({
        ...agent,
        mounts: [
          {
            mountId: 'm1',
            capabilityType: type,
            skillConfig:
              type === 'SKILL' ? { activationMode: 'ON_DEMAND', tokenBudget: 2000 } : null,
            capabilityDefinitionId: '10',
            capabilityVersionId: '21',
            priority: 0,
            revision: 1,
            status: 'DISABLED'
          }
        ]
      })
      mocks.availableTools.mockResolvedValue({
        items: [
          {
            definition_id: '10',
            default_version_id: '21',
            name: '工具',
            tool_name: 'tool',
            description: '',
            deployments: [
              { deployment_key: 'service', provider_label: '服务', environment: 'PRODUCTION' }
            ]
          }
        ]
      })
      const { drawer } = await open(type)
      if (type === 'KNOWLEDGE_SOURCE') {
        drawer.findComponent(EntitySelect).vm.$emit('update:modelValue', 'kb-1')
        await flushPromises()
        expect(document.body.textContent).toContain('该知识库已挂载')
      } else {
        const capability = drawer.findAllComponents(ElSelect)[1]!
        expect(capability.findAllComponents(ElOption)).toHaveLength(0)
        expect(document.body.textContent).toContain(
          type === 'SKILL' ? '可用技能均已挂载' : '可用工具均已挂载'
        )
      }
      expect(button(drawer, '添加能力').props('disabled')).toBe(true)
      if (type === 'TOOL') {
        const advanced = drawer.getComponent(ElCheckbox)
        advanced.vm.$emit('update:modelValue', true)
        advanced.vm.$emit('change', true)
        await flushPromises()
        expect(drawer.findAllComponents(ElSelect)[1]!.findAllComponents(ElOption)).toHaveLength(0)
        expect(button(drawer, '添加能力').props('disabled')).toBe(true)
      }
      expect(mocks.create).not.toHaveBeenCalled()
    }
  )
  it('已挂载的工作流不再预选或允许重复添加', async () => {
    const agent = await mocks.get()
    mocks.get.mockResolvedValue({
      ...agent,
      mounts: [
        {
          mountId: 'm1',
          capabilityType: 'WORKFLOW',
          capabilityDefinitionId: '10',
          capabilityVersionId: '21',
          priority: 0,
          revision: 1,
          status: 'DISABLED',
          workflowConfig: { workflowInstanceId: '42' }
        }
      ]
    })
    const { drawer } = await open('WORKFLOW')
    expect(document.body.textContent).toContain('可用工作流均已挂载，可在能力列表中查看。')
    expect(drawer.findAllComponents(ElSelect)[1]!.props('modelValue')).toBeUndefined()
    expect(button(drawer, '添加能力').props('disabled')).toBe(true)
    expect(mocks.create).not.toHaveBeenCalled()
  })
  it('已挂载和未挂载并存时预选唯一未挂载项', async () => {
    const agent = await mocks.get()
    mocks.get.mockResolvedValue({
      ...agent,
      mounts: [
        {
          mountId: 'm1',
          capabilityType: 'WORKFLOW',
          capabilityDefinitionId: '10',
          capabilityVersionId: '21',
          priority: 0,
          revision: 1,
          status: 'ACTIVE',
          workflowConfig: { workflowInstanceId: '42' }
        }
      ]
    })
    mocks.definitions.mockResolvedValue([
      { id: '10', defaultVersionId: '21', name: '已挂载' },
      { id: '11', defaultVersionId: '22', name: '未挂载' }
    ])
    mocks.requirementOptions.mockResolvedValue([
      { id: '10', name: '已挂载', versionId: '21', versionKey: '1' },
      { id: '11', name: '未挂载', versionId: '22', versionKey: '1' }
    ])
    mocks.workflowOptions.mockResolvedValueOnce([
      {
        id: '42',
        instanceId: 'one',
        workflowId: 'one',
        definitionId: '10',
        installationRevision: '1'
      },
      {
        id: '43',
        instanceId: 'two',
        workflowId: 'two',
        definitionId: '11',
        installationRevision: '1'
      }
    ])
    const { drawer } = await open('WORKFLOW')
    const capability = drawer.findAllComponents(ElSelect)[1]!
    expect(capability.props('modelValue')).toBe('11')
    expect(capability.findAllComponents(ElOption).map((item) => item.props('value'))).toEqual([
      '11'
    ])
    await button(drawer, '添加能力').trigger('click')
    await flushPromises()
    expect(mocks.create).toHaveBeenCalledWith(
      'agent-1',
      expect.objectContaining({
        capabilityDefinitionId: '11',
        workflowConfig: { workflowInstanceId: '43' }
      })
    )
  })
  it('仅有一个可挂载工作流时自动选择，过滤未安装的工作流', async () => {
    mocks.definitions.mockImplementation(async (type: string) =>
      type === 'WORKFLOW'
        ? [
            { id: '10', defaultVersionId: '21', name: '已安装' },
            { id: '11', defaultVersionId: '22', name: '未安装' }
          ]
        : [{ id: '10', defaultVersionId: '21', name: '技能' }]
    )
    mocks.requirementOptions.mockImplementation(async (type: string) =>
      type === 'WORKFLOW'
        ? [
            { id: '10', name: '已安装', versionId: '21', versionKey: '2' },
            { id: '11', name: '未安装', versionId: '22', versionKey: '1' }
          ]
        : [{ id: '10', name: '技能', versionId: '21', versionKey: '2' }]
    )
    mocks.workflowOptions.mockResolvedValueOnce([
      {
        id: '42',
        instanceId: 'first',
        workflowId: 'flow',
        definitionId: '10',
        installationRevision: '100'
      }
    ])
    const { drawer } = await open('WORKFLOW')
    const capability = drawer.findAllComponents(ElSelect)[1]!
    expect(capability.props('modelValue')).toBe('10')
    expect(capability.findAllComponents(ElOption).map((item) => item.props('value'))).toEqual([
      '10'
    ])
    expect(document.body.textContent).toContain('已选用唯一安装实例：flow / first')
    await button(drawer, '添加能力').trigger('click')
    await flushPromises()
    expect(mocks.create).toHaveBeenCalledWith(
      'agent-1',
      expect.objectContaining({
        capabilityDefinitionId: '10',
        workflowConfig: { workflowInstanceId: '42' }
      })
    )
  })
  it('多个可挂载工作流时由用户选择', async () => {
    mocks.definitions.mockResolvedValue([
      { id: '10', defaultVersionId: '21', name: '一' },
      { id: '11', defaultVersionId: '22', name: '二' }
    ])
    mocks.requirementOptions.mockResolvedValue([
      { id: '10', name: '一', versionId: '21', versionKey: '1' },
      { id: '11', name: '二', versionId: '22', versionKey: '1' }
    ])
    mocks.workflowOptions.mockResolvedValueOnce([
      {
        id: '42',
        instanceId: 'one',
        workflowId: 'one',
        definitionId: '10',
        installationRevision: '1'
      },
      {
        id: '43',
        instanceId: 'two',
        workflowId: 'two',
        definitionId: '11',
        installationRevision: '1'
      }
    ])
    const { drawer } = await open('WORKFLOW')
    expect(drawer.findAllComponents(ElSelect)[1]!.props('modelValue')).toBeUndefined()
  })
  it('工作流仅有一个已安装实例时自动选用并提交其主键', async () => {
    mocks.workflowOptions.mockResolvedValueOnce([
      {
        id: '42',
        instanceId: 'first',
        workflowId: 'workflow',
        definitionId: '10',
        installationRevision: '100'
      }
    ])
    const { drawer } = await open('WORKFLOW')
    drawer.findAllComponents(ElSelect)[1]!.vm.$emit('update:modelValue', '10')
    await flushPromises()
    expect(document.body.textContent).toContain('已选用唯一安装实例：workflow / first')
    expect(drawer.findAllComponents(ElSelect)).toHaveLength(2)
    await button(drawer, '添加能力').trigger('click')
    await flushPromises()
    expect(mocks.create).toHaveBeenCalledWith(
      'agent-1',
      expect.objectContaining({
        capabilityDefinitionId: '10',
        workflowConfig: { workflowInstanceId: '42' }
      })
    )
  })
  it('唯一实例尚未安装时不自动选用', async () => {
    mocks.workflowOptions.mockResolvedValueOnce([])
    const { drawer } = await open('WORKFLOW')
    expect(document.body.textContent).toContain('当前没有可挂载的已安装工作流')
    drawer.findAllComponents(ElSelect)[1]!.vm.$emit('update:modelValue', '10')
    await flushPromises()
    expect(drawer.findAllComponents(ElSelect)[2]!.props('modelValue')).toBe('')
    await button(drawer, '添加能力').trigger('click')
    await flushPromises()
    expect(mocks.create).not.toHaveBeenCalled()
  })
  it('工作流挂载提交数字主键字符串，业务标识仅展示且历史发布实例可继续使用', async () => {
    const { drawer } = await open('WORKFLOW')
    const selects = drawer.findAllComponents(ElSelect)
    selects[1]!.vm.$emit('update:modelValue', '10')
    await flushPromises()
    const instances = selects[2]!
    expect(instances.findAllComponents(ElOption).map((option) => option.props('value'))).toEqual([
      '9223372036854775807',
      '9223372036854775806'
    ])
    expect(instances.findAllComponents(ElOption).map((option) => option.props('label'))).toEqual([
      'workflow / first',
      'workflow / second'
    ])
    instances.vm.$emit('update:modelValue', '9223372036854775807')
    await flushPromises()
    await button(drawer, '添加能力').trigger('click')
    await flushPromises()
    expect(mocks.create).toHaveBeenCalledWith(
      'agent-1',
      expect.objectContaining({
        capabilityDefinitionId: '10',
        workflowConfig: { workflowInstanceId: '9223372036854775807' }
      })
    )
    const request = mocks.create.mock.calls[0]![1]
    expect(request).not.toHaveProperty('capabilityVersionId')
    expect(request.workflowConfig).not.toHaveProperty('workflowInstanceRevision')
  })
  it('工作流挂载不接受业务标识冒充安装实例主键', async () => {
    const { drawer } = await open('WORKFLOW')
    drawer.findAllComponents(ElSelect)[1]!.vm.$emit('update:modelValue', '10')
    await flushPromises()
    drawer.findAllComponents(ElSelect)[2]!.vm.$emit('update:modelValue', 'first')
    await flushPromises()
    await button(drawer, '添加能力').trigger('click')
    await flushPromises()
    expect(mocks.create).not.toHaveBeenCalled()
    expect(document.body.textContent).toContain('请选择已安装工作流实例')
  })
  it.each([1, 2])('有 %i 个模板时仅对唯一候选自动选择并读取', async (count) => {
    if (count === 2)
      mocks.templates.mockResolvedValue({
        items: [
          { id: 'template-1', name: '客服模板', version: '1' },
          { id: 'template-2', name: '服务模板', version: '1' }
        ]
      })
    const wrapper = mount(AgentsPage, {
      attachTo: document.body,
      global: { directives: { permission: () => {} }, stubs: { RouterLink: true } }
    })
    wrappers.push(wrapper)
    await flushPromises()
    await button(wrapper, '从模板安装').trigger('click')
    await flushPromises()
    const drawer = wrapper
      .findAllComponents(ElDrawer)
      .find((value) => value.props('title') === '从模板安装')!
    const select = drawer
      .findAllComponents(ElFormItem)
      .find((value) => value.props('label') === '模板')!
      .getComponent(ElSelect)
    expect(select.props('modelValue')).toBe(count === 1 ? 'template-1' : '')
    expect(mocks.templateDetail).toHaveBeenCalledTimes(count === 1 ? 1 : 0)
    if (count === 1) expect(document.body.textContent).toContain('服务流程')
    expect(mocks.installTemplate).not.toHaveBeenCalled()
  })
  it('唯一模板自动填入后未编辑，可直接关闭且不阻止离开', async () => {
    const wrapper = mount(AgentsPage, {
      attachTo: document.body,
      global: { directives: { permission: () => {} }, stubs: { RouterLink: true } }
    })
    wrappers.push(wrapper)
    await flushPromises()
    await button(wrapper, '从模板安装').trigger('click')
    await flushPromises()
    const drawer = wrapper
      .findAllComponents(ElDrawer)
      .find((value) => value.props('title') === '从模板安装')!
    const confirm = vi.spyOn(ElMessageBox, 'confirm')
    const leave = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(leave)
    expect(leave.defaultPrevented).toBe(false)
    await button(drawer, '取消').trigger('click')
    await flushPromises()
    expect(confirm).not.toHaveBeenCalled()
    expect(drawer.props('modelValue')).toBe(false)
  })
  it.each(['name', 'binding'])('唯一模板自动填入后修改%s仍保护输入', async (kind) => {
    const wrapper = mount(AgentsPage, {
      attachTo: document.body,
      global: { directives: { permission: () => {} }, stubs: { RouterLink: true } }
    })
    wrappers.push(wrapper)
    await flushPromises()
    await button(wrapper, '从模板安装').trigger('click')
    await flushPromises()
    const drawer = wrapper
      .findAllComponents(ElDrawer)
      .find((value) => value.props('title') === '从模板安装')!
    const field = (label: string) =>
      drawer.findAllComponents(ElFormItem).find((item) => item.props('label') === label)!
    if (kind === 'name')
      await field('智能体名称').getComponent(ElInput).get('input').setValue('用户编辑名称')
    else field('服务流程').getComponent(ElSelect).vm.$emit('update:modelValue', '10')
    await flushPromises()
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockRejectedValueOnce('cancel')
    const leave = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(leave)
    expect(leave.defaultPrevented).toBe(true)
    await button(drawer, '取消').trigger('click')
    await flushPromises()
    expect(confirm).toHaveBeenCalledOnce()
    expect(drawer.props('modelValue')).toBe(true)
  })
  it('唯一模板读取失败时保留选择并允许重读', async () => {
    mocks.templateDetail.mockRejectedValueOnce(Error('读取失败'))
    const wrapper = mount(AgentsPage, {
      attachTo: document.body,
      global: { directives: { permission: () => {} }, stubs: { RouterLink: true } }
    })
    wrappers.push(wrapper)
    await flushPromises()
    await button(wrapper, '从模板安装').trigger('click')
    await flushPromises()
    const drawer = wrapper
      .findAllComponents(ElDrawer)
      .find((value) => value.props('title') === '从模板安装')!
    expect(document.body.textContent).toContain('尚未读到模板详情')
    const name = drawer
      .findAllComponents(ElFormItem)
      .find((value) => value.props('label') === '智能体名称')!
      .getComponent(ElInput)
    await name.get('input').setValue('重试前填写的名称')
    await button(drawer, '重新读取模板').trigger('click')
    await flushPromises()
    expect(mocks.templateDetail).toHaveBeenCalledTimes(2)
    expect(document.body.textContent).toContain('服务流程')
    expect(name.props('modelValue')).toBe('重试前填写的名称')
    expect(mocks.installTemplate).not.toHaveBeenCalled()
  })
  it('手动选模板读取失败时保留名称，取消切换后可重读原模板', async () => {
    mocks.templates.mockResolvedValue({
      items: [
        { id: 'template-1', name: '客服模板', version: '1' },
        { id: 'template-2', name: '服务模板', version: '1' }
      ]
    })
    mocks.templateDetail.mockRejectedValueOnce(Error('读取失败'))
    const wrapper = mount(AgentsPage, {
      attachTo: document.body,
      global: { directives: { permission: () => {} }, stubs: { RouterLink: true } }
    })
    wrappers.push(wrapper)
    await flushPromises()
    await button(wrapper, '从模板安装').trigger('click')
    await flushPromises()
    const drawer = wrapper
      .findAllComponents(ElDrawer)
      .find((value) => value.props('title') === '从模板安装')!
    const field = (label: string) =>
      drawer.findAllComponents(ElFormItem).find((item) => item.props('label') === label)!
    const select = field('模板').getComponent(ElSelect)
    select.vm.$emit('update:modelValue', 'template-1')
    select.vm.$emit('change', 'template-1')
    await flushPromises()
    const name = field('智能体名称').getComponent(ElInput)
    await name.get('input').setValue('自定义名称')
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockRejectedValueOnce('cancel')
    select.vm.$emit('update:modelValue', 'template-2')
    select.vm.$emit('change', 'template-2')
    await flushPromises()
    expect(confirm).toHaveBeenCalledOnce()
    expect(select.props('modelValue')).toBe('template-1')
    expect(name.props('modelValue')).toBe('自定义名称')
    await button(drawer, '重新读取模板').trigger('click')
    await flushPromises()
    expect(mocks.templateDetail).toHaveBeenLastCalledWith('template-1')
    expect(name.props('modelValue')).toBe('自定义名称')
    confirm.mockResolvedValueOnce('confirm' as Awaited<ReturnType<typeof ElMessageBox.confirm>>)
    mocks.templateDetail.mockResolvedValueOnce({ name: '服务模板', definition: {} })
    select.vm.$emit('update:modelValue', 'template-2')
    select.vm.$emit('change', 'template-2')
    await flushPromises()
    expect(name.props('modelValue')).toBe('服务模板')
    expect(mocks.installTemplate).not.toHaveBeenCalled()
  })
  it('无资源槽的模板可直接安装，无需读取无关资源目录', async () => {
    mocks.templateDetail.mockResolvedValue({ name: '无槽模板', definition: {} })
    mocks.listDefinitions.mockRejectedValue(new Error('无关目录不可读'))
    mocks.deployments.mockRejectedValue(new Error('无关部署不可读'))
    mocks.instances.mockRejectedValue(new Error('无关工作流不可读'))
    const wrapper = mount(AgentsPage, {
      attachTo: document.body,
      global: { directives: { permission: () => {} }, stubs: { RouterLink: true } }
    })
    wrappers.push(wrapper)
    await flushPromises()
    await button(wrapper, '从模板安装').trigger('click')
    await flushPromises()
    const drawer = wrapper
      .findAllComponents(ElDrawer)
      .find((value) => value.props('title') === '从模板安装')!
    const template = drawer
      .findAllComponents(ElFormItem)
      .find((value) => value.props('label') === '模板')!
      .getComponent(ElSelect)
    template.vm.$emit('update:modelValue', 'template-1')
    template.vm.$emit('change', 'template-1')
    await flushPromises()
    expect(mocks.listDefinitions).not.toHaveBeenCalled()
    expect(mocks.versions).not.toHaveBeenCalled()
    expect(mocks.deployments).not.toHaveBeenCalled()
    expect(mocks.instances).not.toHaveBeenCalled()
    await button(drawer, '安装并配置').trigger('click')
    await flushPromises()
    expect(mocks.installTemplate).toHaveBeenCalledWith(
      expect.objectContaining({ resource_bindings: [] })
    )
  })
  it('模板知识槽按类型读取当前发布选项，并拒绝不一致的默认版本', async () => {
    mocks.listDefinitions.mockResolvedValue([
      { id: '10', defaultVersionId: '21', name: '知识一', capabilityType: 'KNOWLEDGE_SOURCE' },
      { id: '11', defaultVersionId: '22', name: '知识二', capabilityType: 'KNOWLEDGE_SOURCE' }
    ])
    mocks.requirementOptions.mockResolvedValue([
      { id: '10', versionId: '21' },
      { id: '11', versionId: 'old' }
    ])
    mocks.templateDetail.mockResolvedValue({
      name: '模板',
      definition: {
        resource_slots: [{ key: 'knowledge', label: '知识槽', capability_type: 'KNOWLEDGE_SOURCE' }]
      }
    })
    const wrapper = mount(AgentsPage, {
      attachTo: document.body,
      global: { directives: { permission: () => {} }, stubs: { RouterLink: true } }
    })
    wrappers.push(wrapper)
    await flushPromises()
    await button(wrapper, '从模板安装').trigger('click')
    await flushPromises()
    const drawer = wrapper
      .findAllComponents(ElDrawer)
      .find((value) => value.props('title') === '从模板安装')!
    const slot = (label: string) =>
      drawer
        .findAllComponents(ElFormItem)
        .find((item) => item.props('label') === label)!
        .getComponent(ElSelect)
    expect(mocks.requirementOptions).toHaveBeenCalledExactlyOnceWith('KNOWLEDGE_SOURCE')
    expect(mocks.versions).not.toHaveBeenCalled()
    slot('知识槽').vm.$emit('update:modelValue', '11')
    await button(drawer, '安装并配置').trigger('click')
    await flushPromises()
    expect(mocks.installTemplate).not.toHaveBeenCalled()
    slot('知识槽').vm.$emit('update:modelValue', '10')
    await button(drawer, '安装并配置').trigger('click')
    await flushPromises()
    expect(mocks.installTemplate).toHaveBeenCalledWith(
      expect.objectContaining({
        resource_bindings: [
          expect.objectContaining({
            slot_key: 'knowledge',
            mount: expect.objectContaining({ capability_definition_id: '10' })
          })
        ]
      })
    )
  })
  it('模板工具只有一个服务时自动绑定', async () => {
    mocks.listDefinitions.mockResolvedValue([
      { id: '10', defaultVersionId: '21', name: '查询订单', capabilityType: 'TOOL' },
      { id: '11', defaultVersionId: '22', name: '无关流程', capabilityType: 'WORKFLOW' }
    ])
    mocks.versions.mockResolvedValue([{ id: '21', definitionId: '10', capabilityType: 'TOOL' }])
    mocks.deployments.mockResolvedValue([{ deploymentKey: 'order-service', toolVersionId: '21' }])
    mocks.templateDetail.mockResolvedValue({
      name: '客服模板',
      definition: { resource_slots: [{ key: 'order', label: '订单查询', capability_type: 'TOOL' }] }
    })
    const wrapper = mount(AgentsPage, {
      attachTo: document.body,
      global: { directives: { permission: () => {} }, stubs: { RouterLink: true } }
    })
    wrappers.push(wrapper)
    await flushPromises()
    await button(wrapper, '从模板安装').trigger('click')
    await flushPromises()
    const drawer = wrapper
      .findAllComponents(ElDrawer)
      .find((value) => value.props('title') === '从模板安装')!
    const template = drawer
      .findAllComponents(ElFormItem)
      .find((value) => value.props('label') === '模板')!
      .getComponent(ElSelect)
    template.vm.$emit('update:modelValue', 'template-1')
    template.vm.$emit('change', 'template-1')
    await flushPromises()
    expect(mocks.versions).toHaveBeenCalledExactlyOnceWith('10', 'PUBLISHED')
    expect(mocks.instances).not.toHaveBeenCalled()
    const slot = drawer
      .findAllComponents(ElFormItem)
      .find((value) => value.props('label') === '订单查询')!
    const capability = slot.getComponent(ElSelect)
    capability.vm.$emit('update:modelValue', '10')
    capability.vm.$emit('change', '10')
    await flushPromises()
    expect(slot.findAllComponents(ElSelect)).toHaveLength(1)
    expect(slot.text()).toContain('order-service')
    await button(drawer, '安装并配置').trigger('click')
    await flushPromises()
    expect(mocks.installTemplate).toHaveBeenCalledWith(
      expect.objectContaining({
        resource_bindings: [
          {
            slot_key: 'order',
            mount: {
              capability_type: 'TOOL',
              capability_definition_id: '10',
              tool_config: { tool_deployment_id: 'order-service' }
            }
          }
        ]
      })
    )
  })
  it('模板安装期间接入工具后刷新目录，保留名称和槽位并继续安装', async () => {
    mocks.listDefinitions.mockResolvedValue([
      { id: '10', defaultVersionId: '21', name: '查询订单', capabilityType: 'TOOL' }
    ])
    mocks.versions.mockResolvedValue([{ id: '21', definitionId: '10', capabilityType: 'TOOL' }])
    mocks.deployments
      .mockResolvedValueOnce([])
      .mockResolvedValueOnce([{ deploymentKey: 'order-service', toolVersionId: '21' }])
    mocks.templateDetail.mockResolvedValue({
      name: '客服模板',
      definition: { resource_slots: [{ key: 'order', label: '订单查询', capability_type: 'TOOL' }] }
    })
    const wrapper = mount(AgentsPage, {
      attachTo: document.body,
      global: { directives: { permission: () => {} }, stubs: { RouterLink: true } }
    })
    wrappers.push(wrapper)
    await flushPromises()
    await button(wrapper, '从模板安装').trigger('click')
    await flushPromises()
    const drawer = wrapper
      .findAllComponents(ElDrawer)
      .find((value) => value.props('title') === '从模板安装')!
    const item = (label: string) =>
      drawer.findAllComponents(ElFormItem).find((value) => value.props('label') === label)!
    const template = item('模板').getComponent(ElSelect)
    template.vm.$emit('update:modelValue', 'template-1')
    template.vm.$emit('change', 'template-1')
    await flushPromises()
    await item('智能体名称').getComponent(ElInput).get('input').setValue('订单助手')
    const slot = item('订单查询').getComponent(ElSelect)
    slot.vm.$emit('update:modelValue', '10')
    slot.vm.$emit('change', '10')
    await flushPromises()
    expect(document.body.textContent).toContain('尚无可用实例')
    await button(drawer, '刷新可用资源').trigger('click')
    await flushPromises()
    expect(item('智能体名称').getComponent(ElInput).props('modelValue')).toBe('订单助手')
    expect(slot.props('modelValue')).toBe('10')
    expect(document.body.textContent).toContain('order-service')
    await button(drawer, '安装并配置').trigger('click')
    await flushPromises()
    expect(mocks.installTemplate).toHaveBeenCalledWith(
      expect.objectContaining({
        name: '订单助手',
        resource_bindings: [
          {
            slot_key: 'order',
            mount: {
              capability_type: 'TOOL',
              capability_definition_id: '10',
              tool_config: { tool_deployment_id: 'order-service' }
            }
          }
        ]
      })
    )
  })

  it('刷新失败保留原槽位，原已选服务消失时不自动改绑到剩余实例', async () => {
    mocks.listDefinitions.mockResolvedValue([
      { id: '10', defaultVersionId: '21', name: '查询订单', capabilityType: 'TOOL' }
    ])
    mocks.versions.mockResolvedValue([{ id: '21', definitionId: '10', capabilityType: 'TOOL' }])
    mocks.deployments
      .mockResolvedValueOnce([
        { deploymentKey: 'a', toolVersionId: '21' },
        { deploymentKey: 'b', toolVersionId: '21' }
      ])
      .mockRejectedValueOnce(new Error('目录不可读'))
      .mockResolvedValueOnce([{ deploymentKey: 'b', toolVersionId: '21' }])
    mocks.templateDetail.mockResolvedValue({
      name: '客服模板',
      definition: { resource_slots: [{ key: 'order', label: '订单查询', capability_type: 'TOOL' }] }
    })
    const wrapper = mount(AgentsPage, {
      attachTo: document.body,
      global: { directives: { permission: () => {} }, stubs: { RouterLink: true } }
    })
    wrappers.push(wrapper)
    await flushPromises()
    await button(wrapper, '从模板安装').trigger('click')
    await flushPromises()
    const drawer = wrapper
      .findAllComponents(ElDrawer)
      .find((value) => value.props('title') === '从模板安装')!
    const item = (label: string) =>
      drawer.findAllComponents(ElFormItem).find((value) => value.props('label') === label)!
    const template = item('模板').getComponent(ElSelect)
    template.vm.$emit('update:modelValue', 'template-1')
    template.vm.$emit('change', 'template-1')
    await flushPromises()
    const slot = item('订单查询')
    slot.findAllComponents(ElSelect)[0]!.vm.$emit('update:modelValue', '10')
    await flushPromises()
    slot.findAllComponents(ElSelect)[1]!.vm.$emit('update:modelValue', 'a')
    await button(drawer, '刷新可用资源').trigger('click')
    await flushPromises()
    expect(slot.findAllComponents(ElSelect)[1]!.props('modelValue')).toBe('a')
    expect(document.body.textContent).toContain('目录不可读')
    await button(drawer, '刷新可用资源').trigger('click')
    await flushPromises()
    expect(slot.text()).toContain('原选择已不可用')
    await button(drawer, '安装并配置').trigger('click')
    await flushPromises()
    expect(mocks.installTemplate).not.toHaveBeenCalled()
    slot.findAllComponents(ElSelect)[1]!.vm.$emit('update:modelValue', 'b')
    await button(drawer, '安装并配置').trigger('click')
    await flushPromises()
    expect(mocks.installTemplate).toHaveBeenCalledWith(
      expect.objectContaining({
        resource_bindings: [
          expect.objectContaining({
            mount: expect.objectContaining({ tool_config: { tool_deployment_id: 'b' } })
          })
        ]
      })
    )
  })

  it('模板工作流只有一个实例时自动绑定', async () => {
    mocks.deployments.mockRejectedValue(new Error('无关工具目录不可读'))
    mocks.instances.mockResolvedValueOnce({
      items: [
        {
          id: '9223372036854775807',
          instanceId: 'first',
          workflowId: 'workflow',
          capabilityVersionId: '20',
          installationRevision: '100'
        },
        {
          id: 'uninstalled',
          instanceId: 'uninstalled',
          workflowId: 'workflow',
          capabilityVersionId: '20',
          installationRevision: null
        }
      ]
    })
    const wrapper = mount(AgentsPage, {
      attachTo: document.body,
      global: { directives: { permission: () => {} }, stubs: { RouterLink: true } }
    })
    wrappers.push(wrapper)
    await flushPromises()
    await button(wrapper, '从模板安装').trigger('click')
    await flushPromises()
    const drawer = wrapper
      .findAllComponents(ElDrawer)
      .find((value) => value.props('title') === '从模板安装')!
    const template = drawer
      .findAllComponents(ElFormItem)
      .find((value) => value.props('label') === '模板')!
      .getComponent(ElSelect)
    template.vm.$emit('update:modelValue', 'template-1')
    template.vm.$emit('change', 'template-1')
    await flushPromises()
    expect(mocks.deployments).not.toHaveBeenCalled()
    expect(mocks.instances).toHaveBeenCalledOnce()
    const slot = drawer
      .findAllComponents(ElFormItem)
      .find((value) => value.props('label') === '服务流程')!
    const capability = slot.getComponent(ElSelect)
    capability.vm.$emit('update:modelValue', '10')
    capability.vm.$emit('change', '10')
    await flushPromises()
    expect(slot.findAllComponents(ElSelect)).toHaveLength(1)
    expect(slot.text()).toContain('workflow / first')
    await button(drawer, '安装并配置').trigger('click')
    await flushPromises()
    expect(mocks.installTemplate).toHaveBeenCalledWith(
      expect.objectContaining({
        resource_bindings: [
          {
            slot_key: 'process',
            mount: {
              capability_type: 'WORKFLOW',
              capability_definition_id: '10',
              workflow_config: { workflow_instance_id: '9223372036854775807' }
            }
          }
        ]
      })
    )
  })
  it('从模板安装的工作流槽提交相同数字主键，选项保留业务标识', async () => {
    const wrapper = mount(AgentsPage, {
      attachTo: document.body,
      global: { directives: { permission: () => {} }, stubs: { RouterLink: true } }
    })
    wrappers.push(wrapper)
    await flushPromises()
    await button(wrapper, '从模板安装').trigger('click')
    await flushPromises()
    const drawer = wrapper
      .findAllComponents(ElDrawer)
      .find((value) => value.props('title') === '从模板安装')!
    const template = drawer
      .findAllComponents(ElFormItem)
      .find((value) => value.props('label') === '模板')!
      .getComponent(ElSelect)
    template.vm.$emit('update:modelValue', 'template-1')
    template.vm.$emit('change', 'template-1')
    await flushPromises()
    const slot = drawer
      .findAllComponents(ElFormItem)
      .find((value) => value.props('label') === '服务流程')!
    const capability = slot.findAllComponents(ElSelect)[0]!
    capability.vm.$emit('update:modelValue', '10')
    capability.vm.$emit('change', '10')
    await flushPromises()
    const instances = slot.findAllComponents(ElSelect)[1]!
    expect(
      instances
        .findAllComponents(ElOption)
        .map((value) => [value.props('value'), value.props('label')])
    ).toEqual([
      ['9223372036854775807', 'workflow / first'],
      ['9223372036854775806', 'workflow / second']
    ])
    instances.vm.$emit('update:modelValue', '9223372036854775806')
    await flushPromises()
    await button(drawer, '安装并配置').trigger('click')
    await flushPromises()
    expect(mocks.installTemplate).toHaveBeenCalledWith(
      expect.objectContaining({
        template_id: 'template-1',
        resource_bindings: [
          {
            slot_key: 'process',
            mount: {
              capability_type: 'WORKFLOW',
              capability_definition_id: '10',
              workflow_config: { workflow_instance_id: '9223372036854775806' }
            }
          }
        ]
      })
    )
    expect(mocks.push).toHaveBeenCalledWith('/admin/agents/installed-agent')
  })
  it('唯一活动知识库预选并只读核对一次；用户清除后刷新不重选', async () => {
    mocks.bases.mockResolvedValue({
      items: [
        { id: 'kb-1', name: '帮助中心', status: 'active' },
        { id: 'kb-old', name: '归档知识', status: 'archived' }
      ]
    })
    const { drawer } = await open('KNOWLEDGE_SOURCE')
    const select = drawer.findComponent(EntitySelect)
    expect(select.props('modelValue')).toBe('kb-1')
    expect(document.body.textContent).toContain('已预选唯一活动知识库')
    expect(mocks.mountOption).toHaveBeenCalledExactlyOnceWith('kb-1')
    expect(mocks.create).not.toHaveBeenCalled()
    expect(button(drawer, '添加能力').props('disabled')).toBe(false)
    select.vm.$emit('update:modelValue', '')
    await flushPromises()
    await button(drawer, '刷新知识库选项').trigger('click')
    await flushPromises()
    expect(select.props('modelValue')).toBe('')
    expect(document.body.textContent).not.toContain('已预选唯一活动知识库')
    expect(mocks.mountOption).toHaveBeenCalledTimes(1)
    expect(button(drawer, '添加能力').props('disabled')).toBe(true)
    expect(mocks.create).not.toHaveBeenCalled()
  })
  it.each([
    ['空目录', []],
    ['仅归档', [{ id: 'kb-old', name: '旧库', status: 'archived' }]],
    [
      '多个活动库',
      [
        { id: 'kb-1', name: '一', status: 'active' },
        { id: 'kb-2', name: '二', status: 'active' }
      ]
    ],
    [
      '存在未知状态',
      [
        { id: 'kb-1', name: '一', status: 'active' },
        { id: 'kb-2', name: '二', status: 'unknown' }
      ]
    ]
  ])('%s不自动选择或查询发布', async (_name, items) => {
    mocks.bases.mockResolvedValue({ items })
    const { drawer } = await open('KNOWLEDGE_SOURCE')
    expect(drawer.findComponent(EntitySelect).props('modelValue')).toBe('')
    expect(mocks.mountOption).not.toHaveBeenCalled()
    expect(mocks.create).not.toHaveBeenCalled()
  })
  it.each([
    [
      '尚未发布',
      { active_revision_id: null, definition_id: null, version_id: null, version_key: null },
      '该知识库尚未发布'
    ],
    [
      '发布信息不一致',
      {
        active_revision_id: 'revision-2',
        definition_id: null,
        version_id: null,
        version_key: null
      },
      '当前知识发布与能力目录不一致'
    ]
  ])('唯一活动库%s时仍禁止挂载并解释原因', async (_name, option, hint) => {
    mocks.bases.mockResolvedValue({ items: [{ id: 'kb-1', name: '帮助中心', status: 'active' }] })
    mocks.mountOption.mockResolvedValueOnce({ kb_id: 'kb-1', ...option })
    const { drawer } = await open('KNOWLEDGE_SOURCE')
    expect(drawer.findComponent(EntitySelect).props('modelValue')).toBe('kb-1')
    expect(document.body.textContent).toContain(hint)
    expect(button(drawer, '添加能力').props('disabled')).toBe(true)
    expect(mocks.create).not.toHaveBeenCalled()
  })
  it('唯一活动库已经挂载时显示原因且不重复挂载', async () => {
    mocks.bases.mockResolvedValue({ items: [{ id: 'kb-1', name: '帮助中心', status: 'active' }] })
    mocks.get.mockResolvedValueOnce({
      id: 'agent-1',
      name: '客服',
      model_config_id: 'model-1',
      status: 'published',
      mounts: [{ capabilityDefinitionId: '10', capabilityVersionId: '21' }],
      execution_config: {},
      delegation_config: { enabled: false, target_agent_ids: [] }
    })
    const { drawer } = await open('KNOWLEDGE_SOURCE')
    expect(document.body.textContent).toContain('该知识库已挂载')
    expect(button(drawer, '添加能力').props('disabled')).toBe(true)
    expect(mocks.create).not.toHaveBeenCalled()
  })
  it('自动读取发布身份失败后可重新读取，不能提前挂载', async () => {
    mocks.bases.mockResolvedValue({ items: [{ id: 'kb-1', name: '帮助中心', status: 'active' }] })
    mocks.mountOption.mockRejectedValueOnce(new Error('发布状态读取失败'))
    const { drawer } = await open('KNOWLEDGE_SOURCE')
    expect(button(drawer, '添加能力').props('disabled')).toBe(true)
    expect(mocks.create).not.toHaveBeenCalled()
    await button(drawer, '重试当前能力选项').trigger('click')
    await flushPromises()
    expect(mocks.mountOption).toHaveBeenCalledTimes(2)
    expect(button(drawer, '添加能力').props('disabled')).toBe(false)
    expect(mocks.create).not.toHaveBeenCalled()
  })
  it('自动读取在途时浏览器安全会话变化，不采纳旧发布身份', async () => {
    mocks.bases.mockResolvedValue({ items: [{ id: 'kb-1', name: '帮助中心', status: 'active' }] })
    let resolveOption!: (value: unknown) => void
    mocks.mountOption.mockReturnValueOnce(
      new Promise((resolve) => {
        resolveOption = resolve
      })
    )
    const { drawer } = await open('KNOWLEDGE_SOURCE')
    expect(resolveOption).toBeTypeOf('function')
    mocks.browserSession = 'another-session'
    resolveOption({
      kb_id: 'kb-1',
      active_revision_id: 'revision-2',
      definition_id: '10',
      version_id: '21',
      version_key: '2'
    })
    await flushPromises()
    expect(button(drawer, '添加能力').props('disabled')).toBe(true)
    expect(mocks.create).not.toHaveBeenCalled()
  })
  it('手动选择时旧会话的迟到发布身份不用于新上下文', async () => {
    let resolveOption!: (value: unknown) => void
    mocks.mountOption.mockReturnValueOnce(
      new Promise((resolve) => {
        resolveOption = resolve
      })
    )
    const { drawer } = await open('KNOWLEDGE_SOURCE')
    drawer.findComponent(EntitySelect).vm.$emit('update:modelValue', 'kb-1')
    await flushPromises()
    mocks.browserSession = 'another-session'
    resolveOption({
      kb_id: 'kb-1',
      active_revision_id: 'revision-2',
      definition_id: '10',
      version_id: '21',
      version_key: '2'
    })
    await flushPromises()
    expect(button(drawer, '添加能力').props('disabled')).toBe(true)
    expect(mocks.create).not.toHaveBeenCalled()
  })
  it('刷新候选在途时切换安全会话，不采纳旧目录和发布状态', async () => {
    mocks.bases.mockResolvedValue({ items: [{ id: 'kb-1', name: '帮助中心', status: 'active' }] })
    const { drawer } = await open('KNOWLEDGE_SOURCE')
    let resolveBases!: (value: unknown) => void
    let resolveOption!: (value: unknown) => void
    mocks.bases.mockReturnValueOnce(
      new Promise((resolve) => {
        resolveBases = resolve
      })
    )
    mocks.mountOption.mockReturnValueOnce(
      new Promise((resolve) => {
        resolveOption = resolve
      })
    )
    await button(drawer, '刷新知识库选项').trigger('click')
    await flushPromises()
    mocks.browserSession = 'another-session'
    resolveBases({ items: [{ id: 'kb-old', name: '不应展示旧目录', status: 'active' }] })
    resolveOption({
      kb_id: 'kb-1',
      active_revision_id: 'revision-old',
      definition_id: '11',
      version_id: '31',
      version_key: 'old'
    })
    await flushPromises()
    expect(drawer.findComponent(EntitySelect).props('options')).toEqual([
      { id: 'kb-1', label: '帮助中心', description: '标识：kb-1' }
    ])
    expect(mocks.create).not.toHaveBeenCalled()
  })
  it('自动读取在途时用户清除选择，迟到结果不重新选中或挂载', async () => {
    mocks.bases.mockResolvedValue({ items: [{ id: 'kb-1', name: '帮助中心', status: 'active' }] })
    let resolveOption!: (value: unknown) => void
    mocks.mountOption.mockReturnValueOnce(
      new Promise((resolve) => {
        resolveOption = resolve
      })
    )
    const { drawer } = await open('KNOWLEDGE_SOURCE')
    const select = drawer.findComponent(EntitySelect)
    select.vm.$emit('update:modelValue', '')
    await flushPromises()
    resolveOption({
      kb_id: 'kb-1',
      active_revision_id: 'revision-2',
      definition_id: '10',
      version_id: '21',
      version_key: '2'
    })
    await flushPromises()
    expect(select.props('modelValue')).toBe('')
    expect(button(drawer, '添加能力').props('disabled')).toBe(true)
    expect(mocks.create).not.toHaveBeenCalled()
  })
  it('重开知识挂载抽屉时自动选择计入初始快照，不误报未保存', async () => {
    mocks.bases.mockResolvedValue({ items: [{ id: 'kb-1', name: '帮助中心', status: 'active' }] })
    const { wrapper, drawer } = await open('KNOWLEDGE_SOURCE')
    const confirm = vi
      .spyOn(ElMessageBox, 'confirm')
      .mockResolvedValue('confirm' as Awaited<ReturnType<typeof ElMessageBox.confirm>>)
    await button(drawer, '取消').trigger('click')
    await flushPromises()
    expect(drawer.props('modelValue')).toBe(false)
    expect(confirm).toHaveBeenCalledOnce()
    await button(wrapper, '添加能力').trigger('click')
    await flushPromises()
    expect(drawer.props('modelValue')).toBe(true)
    expect(drawer.findComponent(EntitySelect).props('modelValue')).toBe('kb-1')
    const leave = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(leave)
    expect(leave.defaultPrevented).toBe(false)
    await button(drawer, '取消').trigger('click')
    await flushPromises()
    expect(drawer.props('modelValue')).toBe(false)
    expect(confirm).toHaveBeenCalledOnce()
    expect(mocks.create).not.toHaveBeenCalled()
  })
  it('知识库只绑定稳定身份并跟随当前发布，不提供固定历史修订', async () => {
    const { drawer } = await open('KNOWLEDGE_SOURCE')
    drawer.findComponent(EntitySelect).vm.$emit('update:modelValue', 'kb-1')
    await flushPromises()
    expect(drawer.findAllComponents(EntitySelect)).toHaveLength(1)
    expect(drawer.text()).not.toContain('固定修订')
    await button(drawer, '添加能力').trigger('click')
    await flushPromises()
    expect(mocks.create).toHaveBeenCalledWith(
      'agent-1',
      expect.objectContaining({
        capabilityDefinitionId: '10',
        knowledgeConfig: { mode: 'FOLLOW_ACTIVE', pinnedRevisionId: null }
      })
    )
    expect(mocks.create.mock.calls[0]![1]).not.toHaveProperty('capabilityVersionId')
  })
  it('已有知识库时刷新候选保留当前选择和发布状态', async () => {
    mocks.permissions.push('foundation.knowledge.read')
    const { drawer } = await open('KNOWLEDGE_SOURCE')
    const select = drawer.findComponent(EntitySelect)
    select.vm.$emit('update:modelValue', 'kb-1')
    await flushPromises()
    mocks.bases.mockRejectedValueOnce(new Error('知识库目录暂不可读'))
    await button(drawer, '刷新知识库选项').trigger('click')
    await flushPromises()
    expect(select.props('modelValue')).toBe('kb-1')
    expect(select.props('options')).toHaveLength(1)
    expect(document.body.textContent).toContain('知识库目录暂不可读')
    mocks.bases.mockResolvedValueOnce({
      items: [
        { id: 'kb-1', name: '帮助中心' },
        { id: 'kb-2', name: '新知识库' }
      ]
    })
    await button(drawer, '刷新知识库选项').trigger('click')
    await flushPromises()
    expect(select.props('modelValue')).toBe('kb-1')
    expect(select.props('options')).toEqual([
      { id: 'kb-1', label: '帮助中心', description: '标识：kb-1' },
      { id: 'kb-2', label: '新知识库', description: '标识：kb-2' }
    ])
    expect(mocks.mountOption).toHaveBeenCalledTimes(3)
    expect(mocks.mountOption).toHaveBeenLastCalledWith('kb-1')
    expect(mocks.definitions.mock.calls.map((call) => call[0])).toEqual(['SKILL'])
    expect(mocks.versions).not.toHaveBeenCalled()
    await button(drawer, '添加能力').trigger('click')
    await flushPromises()
    expect(mocks.create).toHaveBeenCalledWith(
      'agent-1',
      expect.objectContaining({ capabilityDefinitionId: '10' })
    )
  })
  it('当前发布尚无能力身份时刷新精确选项并绑定对应能力', async () => {
    mocks.permissions.push('foundation.knowledge.read')
    mocks.mountOption.mockResolvedValueOnce({
      kb_id: 'kb-1',
      active_revision_id: 'revision-new',
      definition_id: null,
      version_id: null,
      version_key: null
    })
    const { drawer } = await open('KNOWLEDGE_SOURCE')
    drawer.findComponent(EntitySelect).vm.$emit('update:modelValue', 'kb-1')
    await flushPromises()
    expect(button(drawer, '添加能力').props('disabled')).toBe(true)
    expect(document.body.textContent).toContain('当前知识发布与能力目录不一致')
    mocks.mountOption.mockResolvedValueOnce({
      kb_id: 'kb-1',
      active_revision_id: 'revision-new',
      definition_id: '11',
      version_id: '31',
      version_key: '1'
    })
    await button(drawer, '刷新知识库选项').trigger('click')
    await flushPromises()
    expect(drawer.findComponent(EntitySelect).props('modelValue')).toBe('kb-1')
    await button(drawer, '添加能力').trigger('click')
    await flushPromises()
    expect(mocks.create).toHaveBeenCalledWith(
      'agent-1',
      expect.objectContaining({
        capabilityDefinitionId: '11',
        knowledgeConfig: { mode: 'FOLLOW_ACTIVE', pinnedRevisionId: null }
      })
    )
  })
  it('同账号发布知识后自动刷新当前选择，并保留原挂载输入', async () => {
    const channels: FakeChannel[] = []
    class FakeChannel {
      onmessage?: (event: { data: string }) => void
      close = vi.fn()
      constructor() {
        channels.push(this)
      }
    }
    vi.stubGlobal('BroadcastChannel', FakeChannel)
    mocks.permissions.push('foundation.knowledge.read')
    try {
      const { wrapper, drawer } = await open('KNOWLEDGE_SOURCE')
      const select = drawer.findComponent(EntitySelect)
      select.vm.$emit('update:modelValue', 'kb-1')
      await flushPromises()
      const before = mocks.bases.mock.calls.length
      channels[0]!.onmessage!({ data: JSON.stringify(['other', 'actor-1']) })
      await flushPromises()
      expect(mocks.bases).toHaveBeenCalledTimes(before)
      mocks.mountOption.mockResolvedValueOnce({
        kb_id: 'kb-1',
        active_revision_id: 'revision-new',
        definition_id: '11',
        version_id: '31',
        version_key: '1'
      })
      channels[0]!.onmessage!({ data: JSON.stringify(['1', 'actor-1']) })
      await flushPromises()
      expect(mocks.bases).toHaveBeenCalledTimes(before + 1)
      expect(select.props('modelValue')).toBe('kb-1')
      await button(drawer, '添加能力').trigger('click')
      await flushPromises()
      expect(mocks.create).toHaveBeenCalledWith(
        'agent-1',
        expect.objectContaining({ capabilityDefinitionId: '11' })
      )
      wrapper.unmount()
      expect(channels[0]!.close).toHaveBeenCalledOnce()
    } finally {
      vi.unstubAllGlobals()
    }
  })
  it('读取知识库时收到发布通知，读取结束后补刷新', async () => {
    const channels: FakeChannel[] = []
    class FakeChannel {
      onmessage?: (event: { data: string }) => void
      close = vi.fn()
      constructor() {
        channels.push(this)
      }
    }
    vi.stubGlobal('BroadcastChannel', FakeChannel)
    mocks.permissions.push('foundation.knowledge.read')
    try {
      const { drawer } = await open('KNOWLEDGE_SOURCE')
      let resolveBase!: (value: unknown) => void
      mocks.mountOption.mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            resolveBase = resolve
          })
      )
      drawer.findComponent(EntitySelect).vm.$emit('update:modelValue', 'kb-1')
      await flushPromises()
      const before = mocks.bases.mock.calls.length
      channels[0]!.onmessage!({ data: JSON.stringify(['1', 'actor-1']) })
      await flushPromises()
      expect(mocks.bases).toHaveBeenCalledTimes(before)
      resolveBase({
        kb_id: 'kb-1',
        active_revision_id: 'revision-new',
        definition_id: '10',
        version_id: '21',
        version_key: '2'
      })
      await flushPromises()
      expect(mocks.bases).toHaveBeenCalledTimes(before + 1)
      expect(drawer.findComponent(EntitySelect).props('modelValue')).toBe('kb-1')
    } finally {
      vi.unstubAllGlobals()
    }
  })
  it('空目录提供创建入口，返回刷新后可在原抽屉继续挂载', async () => {
    mocks.permissions.push('foundation.knowledge.read', 'foundation.knowledge.create')
    mocks.bases
      .mockResolvedValueOnce({ items: [] })
      .mockResolvedValueOnce({ items: [{ id: 'kb-1', name: '帮助中心' }] })
    const { drawer } = await open('KNOWLEDGE_SOURCE')
    expect(document.body.textContent).toContain('当前没有可选知识库')
    expect(document.body.querySelector('a[href="/admin/knowledge"]')?.getAttribute('target')).toBe(
      '_blank'
    )
    expect(button(drawer, '添加能力').props('disabled')).toBe(true)
    await button(drawer, '刷新知识库选项').trigger('click')
    await flushPromises()
    expect(document.body.textContent).not.toContain('当前没有可选知识库')
    drawer.findComponent(EntitySelect).vm.$emit('update:modelValue', 'kb-1')
    await flushPromises()
    await button(drawer, '添加能力').trigger('click')
    await flushPromises()
    expect(mocks.create).toHaveBeenCalledWith(
      'agent-1',
      expect.objectContaining({ capabilityDefinitionId: '10' })
    )
  })
  it('无知识库创建权限时给出联系管理员提示，不提供创建入口', async () => {
    mocks.bases.mockResolvedValue({ items: [] })
    await open('KNOWLEDGE_SOURCE')
    expect(document.body.textContent).toContain('请联系管理员创建并发布知识库')
    expect(document.body.querySelector('a[href="/admin/knowledge"]')).toBeNull()
  })
  it('同名知识库可按用途或标识查找并选择原始身份', async () => {
    mocks.bases.mockResolvedValue({
      items: [
        { id: 'kb-1', name: '帮助中心', description: '订单查询' },
        { id: 'kb-2', name: '帮助中心', description: '售后服务' }
      ]
    })
    const { drawer } = await open('KNOWLEDGE_SOURCE')
    const entity = drawer.findComponent(EntitySelect)
    const select = entity.getComponent(ElSelect)
    const filter = select.props('filterMethod') as (query: string) => void
    filter('售后')
    await flushPromises()
    expect(entity.props('options')).toEqual([
      { id: 'kb-2', label: '帮助中心', description: '售后服务 · 标识：kb-2' }
    ])
    filter('kb-1')
    await flushPromises()
    expect(entity.props('options')).toEqual([
      { id: 'kb-1', label: '帮助中心', description: '订单查询 · 标识：kb-1' }
    ])
    entity.vm.$emit('update:modelValue', 'kb-1')
    await flushPromises()
    expect(mocks.mountOption).toHaveBeenCalledWith('kb-1')
    await button(drawer, '添加能力').trigger('click')
    await flushPromises()
    expect(mocks.create).toHaveBeenCalledWith(
      'agent-1',
      expect.objectContaining({ capabilityDefinitionId: '10' })
    )
  })
  it('选择未发布知识库时提供发布入口并禁用添加，切换到已发布库后恢复', async () => {
    mocks.permissions.push('foundation.knowledge.read')
    mocks.mountOption.mockResolvedValueOnce({
      kb_id: 'kb-1',
      active_revision_id: null,
      definition_id: null,
      version_id: null,
      version_key: null
    })
    const { drawer } = await open('KNOWLEDGE_SOURCE')
    const select = drawer.findComponent(EntitySelect)
    select.vm.$emit('update:modelValue', 'kb-1')
    await flushPromises()
    expect(document.body.textContent).toContain('该知识库尚未发布')
    expect(
      document.body.querySelector('a[href="/admin/knowledge/kb-1"]')?.getAttribute('target')
    ).toBe('_blank')
    expect(button(drawer, '添加能力').props('disabled')).toBe(true)
    expect(mocks.create).not.toHaveBeenCalled()
    mocks.mountOption.mockRejectedValueOnce(new Error('读取失败'))
    await button(drawer, '重新读取发布状态').trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('该知识库尚未发布')
    expect(button(drawer, '添加能力').props('disabled')).toBe(true)
    await button(drawer, '重新读取发布状态').trigger('click')
    await flushPromises()
    expect(document.body.textContent).not.toContain('该知识库尚未发布')
    expect(button(drawer, '添加能力').props('disabled')).toBe(false)
  })
})

it('有待发布草稿时仍只导航试用当前发布 Agent，不提交草稿或直接执行', async () => {
  const wrapper = mount(AgentManagePage, { attachTo: document.body })
  wrappers.push(wrapper)
  await flushPromises()
  expect(wrapper.text()).toContain('试用仅使用当前发布版本，不包含草稿修改')
  const trial = button(wrapper, '试用当前发布版本')
  expect(trial.props('disabled')).toBe(false)
  await trial.trigger('click')
  expect(mocks.push).toHaveBeenCalledWith({ path: '/admin/chat', query: { agent_id: 'agent-1' } })
  expect(mocks.update).not.toHaveBeenCalled()
  expect(mocks.publish).not.toHaveBeenCalled()
})

it('尚未发布时明确说明发布后可试用并禁用入口', async () => {
  mocks.get.mockResolvedValue({ ...(await mocks.get()), status: 'draft', published_revision: null })
  const wrapper = mount(AgentManagePage, { attachTo: document.body })
  wrappers.push(wrapper)
  await flushPromises()
  expect(button(wrapper, '试用当前发布版本').props('disabled')).toBe(true)
  expect(wrapper.text()).toContain('发布后可试用')
  expect(mocks.push).not.toHaveBeenCalled()
})

it('配置页发布不读取历史，打开历史失败也不回退发布或重发POST', async () => {
  vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue(
    'confirm' as Awaited<ReturnType<typeof ElMessageBox.confirm>>
  )
  const wrapper = mount(AgentManagePage, { attachTo: document.body })
  wrappers.push(wrapper)
  await flushPromises()
  const original = await mocks.get()
  mocks.get.mockResolvedValue({ ...original, has_draft_changes: false, published_revision: 3 })
  mocks.publications.mockRejectedValue(Error('历史暂不可读'))
  await button(wrapper, '发布').trigger('click')
  await flushPromises()
  expect(mocks.publish).toHaveBeenCalledExactlyOnceWith('agent-1')
  expect(mocks.publications).not.toHaveBeenCalled()
  expect(wrapper.text()).toContain('发布修订 3')
  expect(button(wrapper, '试用当前发布版本').props('disabled')).toBe(false)
  await wrapper
    .findAll('[role="tab"]')
    .find((tab) => tab.text() === '发布历史')!
    .trigger('click')
  await flushPromises()
  expect(mocks.publications).toHaveBeenCalledExactlyOnceWith('agent-1')
  expect(mocks.publish).toHaveBeenCalledTimes(1)
  expect(wrapper.text()).toContain('发布修订 3')
  expect(button(wrapper, '试用当前发布版本').props('disabled')).toBe(false)
  await button(wrapper, '试用当前发布版本').trigger('click')
  expect(mocks.push).toHaveBeenCalledWith({ path: '/admin/chat', query: { agent_id: 'agent-1' } })
})

it('历史页签发布仍读取最新历史，精确详情和恢复保持原入口', async () => {
  vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue(
    'confirm' as Awaited<ReturnType<typeof ElMessageBox.confirm>>
  )
  const wrapper = mount(AgentManagePage, { attachTo: document.body })
  wrappers.push(wrapper)
  await flushPromises()
  await wrapper
    .findAll('[role="tab"]')
    .find((tab) => tab.text() === '发布历史')!
    .trigger('click')
  await flushPromises()
  expect(mocks.publications).toHaveBeenCalledTimes(1)
  const original = await mocks.get()
  mocks.get.mockResolvedValue({ ...original, has_draft_changes: false, published_revision: 3 })
  mocks.publications.mockResolvedValue({
    items: [
      {
        revision_id: 'revision-3',
        revision: 3,
        name: '客服',
        published_at: '2026-10-30',
        current: true
      }
    ]
  })
  await button(wrapper, '发布').trigger('click')
  await flushPromises()
  expect(mocks.publications).toHaveBeenCalledTimes(2)
  expect(wrapper.text()).toContain('当前发布')
  expect(mocks.revisionDetail).not.toHaveBeenCalled()
  expect(mocks.restore).not.toHaveBeenCalled()
})
it('发布后当前详情刷新失败仍要求重新读取，不能因跳过历史而解除保护', async () => {
  vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue(
    'confirm' as Awaited<ReturnType<typeof ElMessageBox.confirm>>
  )
  const wrapper = mount(AgentManagePage, { attachTo: document.body })
  wrappers.push(wrapper)
  await flushPromises()
  mocks.get.mockRejectedValueOnce(Error('当前配置读取失败'))
  await button(wrapper, '发布').trigger('click')
  await flushPromises()
  expect(mocks.publish).toHaveBeenCalledExactlyOnceWith('agent-1')
  expect(mocks.publications).not.toHaveBeenCalled()
  expect(wrapper.text()).toContain('操作已完成，当前显示内容尚未更新')
  expect(button(wrapper, '发布').props('disabled')).toBe(true)
  expect(button(wrapper, '试用当前发布版本').props('disabled')).toBe(true)
  expect(button(wrapper, '保存草稿').props('disabled')).toBe(true)
  await button(wrapper, '重新读取').trigger('click')
  await flushPromises()
  expect(button(wrapper, '试用当前发布版本').props('disabled')).toBe(false)
  expect(mocks.publish).toHaveBeenCalledTimes(1)
})
