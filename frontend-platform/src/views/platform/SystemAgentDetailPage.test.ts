import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { ElButton, ElForm, ElFormItem, ElInputNumber, ElMessageBox, ElSelect } from 'element-plus'
import { reactive } from 'vue'
import SystemAgentDetailPage from './SystemAgentDetailPage.vue'
import { clearFeedback } from '@skillforge/shared/feedback'
import { HttpClientError } from '@skillforge/shared/utils/http'

const mocks = vi.hoisted(() => ({
  detail: vi.fn(),
  debug: vi.fn(),
  debugResult: vi.fn(),
  models: vi.fn(),
  options: vi.fn(),
  versions: vi.fn(),
  update: vi.fn(),
  publish: vi.fn(),
  runs: vi.fn(),
  permissions: null as string[] | null,
  auth: undefined as unknown as { identityVersion: number; user: { sub: string } }
}))
vi.mock('../../stores/auth', () => ({ useAuthStore: () => mocks.auth }))
vi.mock('./SystemAgentToolsPage.vue', async () => {
  const { defineComponent } = await import('vue')
  return {
    default: defineComponent({
      name: 'SystemAgentToolsPage',
      props: ['toolDefinitionId'],
      emits: ['published', 'closed'],
      template: '<div />'
    })
  }
})
vi.mock('../../api', () => ({
  api: {
    systemAgents: {
      detail: mocks.detail,
      debug: mocks.debug,
      debugResult: mocks.debugResult,
      mountOptions: mocks.options,
      versions: mocks.versions,
      update: mocks.update,
      publish: mocks.publish,
      runs: mocks.runs
    },
    admin: { models: { list: mocks.models } }
  }
}))
vi.mock('../../composables/actionPermissions', () => ({
  useActionPermissions: () => ({
    allowed: (code: string) => mocks.permissions === null || mocks.permissions.includes(code),
    checked: (_code: string, operation: () => unknown) => operation()
  })
}))
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { id: '901' } }),
  useRouter: () => ({ replace: vi.fn() }),
  onBeforeRouteLeave: vi.fn(),
  onBeforeRouteUpdate: vi.fn(),
  RouterLink: { name: 'RouterLink', template: '<a><slot /></a>' }
}))
let wrapper: VueWrapper | undefined
beforeEach(() => {
  vi.resetAllMocks()
  sessionStorage.clear()
  mocks.permissions = null
  mocks.auth = reactive({ identityVersion: 1, user: { sub: 'actor' } })
  mocks.models.mockResolvedValue([])
  mocks.options.mockResolvedValue({ skills: [], tools: [], deployments: [] })
  mocks.versions.mockResolvedValue([])
  mocks.runs.mockResolvedValue([])
  const config = {
    name: '记忆提取',
    description: '',
    system_prompt: '提取明确来源的候选',
    model_config_id: null,
    timeout_seconds: 30,
    max_business_model_calls: 1,
    mounts: []
  }
  mocks.detail.mockResolvedValue({
    id: '901',
    agent_key: 'memory-extraction',
    purpose: 'memory-extraction',
    execution_mode: 'MODEL_TASK',
    status: 'draft',
    revision: 7,
    published_version: null,
    draft: config,
    published: null,
    updated_at: '2026-10-23T00:00:00Z'
  })
})

it('查看完整调试结果不重新执行，切换身份后清除详情', async () => {
  const output = '多行调试结果\n' + '完整内容'.repeat(200)
  mocks.runs.mockResolvedValueOnce([
    {
      id: 'run-1',
      status: 'COMPLETED',
      output,
      error_code: null,
      created_at: '2026-10-23T00:00:00Z'
    }
  ])
  const page = await openPage()
  await page
    .findAllComponents(ElButton)
    .find((item) => item.text() === '查看结果')!
    .trigger('click')
  await flushPromises()
  expect(document.body.textContent).toContain(output)
  expect(mocks.runs).toHaveBeenCalledTimes(1)
  expect(mocks.debug).not.toHaveBeenCalled()
  mocks.auth.identityVersion++
  await flushPromises()
  expect(document.body.textContent).not.toContain(output)
})

function prepareToolMount() {
  const config = {
    name: '编辑中的智能体',
    description: '',
    system_prompt: '原始提示词',
    model_config_id: null,
    timeout_seconds: 30,
    max_business_model_calls: 2,
    mounts: [
      {
        capability_type: 'TOOL',
        capability_definition_id: '21',
        priority: 0,
        token_budget: null,
        activation_mode: null,
        tool_deployment_id: null
      }
    ]
  }
  mocks.detail.mockResolvedValue({
    id: '901',
    agent_key: 'agent',
    execution_mode: 'AGENT_RUN',
    status: 'draft',
    revision: 7,
    draft: config,
    published: null
  })
  mocks.options.mockResolvedValue({
    skills: [],
    tools: [{ id: '21', name: '查询工具' }],
    deployments: []
  })
}
const publishedTool = {
  id: '101',
  revision: 1,
  published_revision: 1,
  has_unpublished_changes: false,
  name: '查询工具 · 测试',
  tool_definition_id: '21',
  tool_version_id: '31',
  provider_type: 'PLATFORM',
  provider_ref: 'query',
  environment: 'TEST',
  active: true
}
it('选择工具时仅在存在唯一可用部署时自动填入，多个或没有部署时保持待选', async () => {
  prepareToolMount()
  const detail = await mocks.detail()
  detail.draft.mounts[0].capability_definition_id = ''
  mocks.options.mockResolvedValue({
    skills: [],
    tools: [
      { id: '21', name: '唯一部署' },
      { id: '22', name: '多个部署' },
      { id: '23', name: '无部署' }
    ],
    deployments: [
      {
        id: '101',
        name: '唯一生产',
        tool_definition_id: '21',
        tool_version_id: '31',
        environment: 'PRODUCTION'
      },
      {
        id: '102',
        name: '测试',
        tool_definition_id: '22',
        tool_version_id: '32',
        environment: 'TEST'
      },
      {
        id: '103',
        name: '生产',
        tool_definition_id: '22',
        tool_version_id: '32',
        environment: 'PRODUCTION'
      }
    ]
  })
  const page = await openPage()
  const config = page
    .findAllComponents(ElForm)
    .find((item) => item.props('model'))!
    .props('model') as {
    mounts: Array<{ tool_deployment_id: string | null }>
  }
  const select = page
    .findAllComponents(ElFormItem)
    .find((item) => item.props('label') === '能力（跟随当前发布）')!
    .getComponent(ElSelect)
  for (const [tool, expected] of [
    ['21', '101'],
    ['22', null],
    ['23', null]
  ] as const) {
    select.vm.$emit('update:modelValue', tool)
    select.vm.$emit('change', tool)
    await flushPromises()
    expect(config.mounts[0]!.tool_deployment_id).toBe(expected)
  }
  expect(page.text()).toContain('测试部署仅供调试，生产运行需使用生产部署')
})
it('在原草稿中配置部署，目录重读成功后自动使用真实候选', async () => {
  prepareToolMount()
  const page = await openPage()
  const config = page
    .findAllComponents(ElForm)
    .find((item) => item.props('model'))!
    .props('model') as {
    system_prompt: string
    mounts: Array<{ tool_deployment_id: string | null }>
  }
  config.system_prompt = '未保存的修改'
  await button('配置此工具部署').trigger('click')
  const child = page.findComponent({ name: 'SystemAgentToolsPage' })
  expect(child.props('toolDefinitionId')).toBe('21')
  mocks.options.mockRejectedValueOnce(new Error('候选暂不可读'))
  child.vm.$emit('published', publishedTool)
  await flushPromises()
  expect(config.mounts[0]!.tool_deployment_id).toBeNull()
  expect(config.system_prompt).toBe('未保存的修改')
  expect(page.text()).toContain('可挂载目录未更新')
  mocks.options.mockResolvedValueOnce({
    skills: [],
    tools: [{ id: '21', name: '查询工具' }],
    deployments: [
      {
        id: '101',
        name: '查询工具 · 测试',
        tool_definition_id: '21',
        tool_version_id: '31',
        environment: 'TEST'
      }
    ]
  })
  await button('重新读取可挂载部署').trigger('click')
  await flushPromises()
  expect(config.mounts[0]!.tool_deployment_id).toBe('101')
  expect(page.text()).toContain('已选择，请保存智能体草稿')
  expect(page.findAllComponents(ElButton).some((item) => item.text() === '使用此部署')).toBe(false)
  expect(config.system_prompt).toBe('未保存的修改')
  expect(mocks.detail).toHaveBeenCalledTimes(1)
})
it('发布期间用户改选部署时保留其选择，并允许明确采用新部署', async () => {
  prepareToolMount()
  const page = await openPage()
  const config = page
    .findAllComponents(ElForm)
    .find((item) => item.props('model'))!
    .props('model') as {
    mounts: Array<{ tool_deployment_id: string | null }>
  }
  await button('配置此工具部署').trigger('click')
  let resolve!: (value: unknown) => void
  mocks.options.mockReturnValueOnce(
    new Promise((done) => {
      resolve = done
    })
  )
  page.findComponent({ name: 'SystemAgentToolsPage' }).vm.$emit('published', publishedTool)
  await flushPromises()
  config.mounts[0]!.tool_deployment_id = '102'
  resolve({
    skills: [],
    tools: [{ id: '21', name: '查询工具' }],
    deployments: [
      { id: '101', tool_definition_id: '21', environment: 'TEST', name: '新部署' },
      { id: '102', tool_definition_id: '21', environment: 'PRODUCTION', name: '原部署' }
    ]
  })
  await flushPromises()
  expect(config.mounts[0]!.tool_deployment_id).toBe('102')
  await button('使用此部署').trigger('click')
  expect(config.mounts[0]!.tool_deployment_id).toBe('101')
})
it('目标挂载或身份变化后的迟到目录不能回填；无发布权限不能启动部署', async () => {
  prepareToolMount()
  const page = await openPage()
  const config = page
    .findAllComponents(ElForm)
    .find((item) => item.props('model'))!
    .props('model') as {
    mounts: Array<{ capability_definition_id: string; tool_deployment_id: string | null }>
  }
  await button('配置此工具部署').trigger('click')
  let resolve!: (value: unknown) => void
  mocks.options.mockReturnValueOnce(
    new Promise((yes) => {
      resolve = yes
    })
  )
  page.findComponent({ name: 'SystemAgentToolsPage' }).vm.$emit('published', publishedTool)
  await flushPromises()
  config.mounts[0]!.capability_definition_id = '22'
  mocks.auth.identityVersion++
  resolve({
    skills: [],
    tools: [],
    deployments: [{ id: '101', tool_definition_id: '21', environment: 'TEST' }]
  })
  await flushPromises()
  expect(config.mounts[0]!.tool_deployment_id).toBeNull()
  expect(page.findAllComponents(ElButton).some((item) => item.text() === '使用此部署')).toBe(false)
  expect(page.text()).not.toContain('可挂载目录未更新')
  page.unmount()
  wrapper = undefined
  mocks.permissions = [
    'foundation.system_agents.read',
    'foundation.system_agents.update',
    'foundation.system_agent_tools.read',
    'foundation.system_agent_tools.create',
    'foundation.capabilities.read'
  ]
  prepareToolMount()
  await openPage()
  expect(button('配置此工具部署').props('disabled')).toBe(true)
})

it.each(['缺少更新权限', '调试结果未知', '智能体已归档'])(
  '%s时配置工具按钮和启动方法均受父编辑锁约束',
  async (scenario) => {
    prepareToolMount()
    if (scenario === '缺少更新权限')
      mocks.permissions = [
        'foundation.system_agents.read',
        'foundation.system_agents.run',
        'foundation.system_agent_tools.read',
        'foundation.system_agent_tools.create',
        'foundation.system_agent_tools.publish',
        'foundation.capabilities.read'
      ]
    if (scenario === '智能体已归档') {
      const detail = await mocks.detail()
      mocks.detail.mockResolvedValue({ ...detail, status: 'archived' })
    }
    if (scenario === '调试结果未知') mocks.debug.mockRejectedValueOnce(new Error('调试结果未知'))
    const page = await openPage()
    if (scenario === '调试结果未知') {
      await page
        .findAll('[role=tab]')
        .find((tab) => tab.text() === '调试与运行')!
        .trigger('click')
      await page.get('textarea[aria-describedby$="-field-9"]').setValue('需要核对的输入')
      await button('运行调试').trigger('click')
      await flushPromises()
      await page
        .findAll('[role=tab]')
        .find((tab) => tab.text() === '配置草稿')!
        .trigger('click')
    }
    const control = button('配置此工具部署')
    expect(control.props('disabled')).toBe(true)
    control.vm.$emit('click')
    await flushPromises()
    expect(page.findComponent({ name: 'SystemAgentToolsPage' }).exists()).toBe(false)
  }
)
afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  vi.restoreAllMocks()
  clearFeedback()
  document.body.innerHTML = ''
})
function button(label: string) {
  const found = wrapper?.findAllComponents(ElButton).find((value) => value.text() === label)
  if (!found) throw new Error('未找到按钮 ' + label)
  return found
}
it('未知结果冻结原输入，显式核对保留同一操作键，完成后允许新调试', async () => {
  mocks.debug.mockRejectedValueOnce(new Error('结果待确认')).mockResolvedValueOnce({
    id: 'run-1',
    agent_id: '901',
    status: 'COMPLETED',
    output: '',
    input_tokens: '1',
    output_tokens: '1',
    duration_ms: 1
  })
  wrapper = mount(SystemAgentDetailPage, {
    attachTo: document.body,
    global: { stubs: { AgentConfigFields: true, RouterLink: true } }
  })
  await flushPromises()
  const tab = wrapper.findAll('[role=tab]').find((value) => value.text() === '调试与运行')
  if (!tab) throw new Error('缺少调试页签')
  await tab.trigger('click')
  await button('填入记忆输入示例').trigger('click')
  const input = wrapper.get('textarea[aria-describedby$="-field-9"]')
  const element = input.element
  if (!(element instanceof HTMLTextAreaElement)) throw new Error('调试输入必须可编辑')
  const example = element.value
  const decoded: unknown = JSON.parse(example)
  expect(decoded).toMatchObject({
    schemaVersion: 2,
    userId: 7,
    sources: [{ id: 's0', occurredAt: '2026-01-01T00:00:00Z' }],
    existingFacts: []
  })
  const edited = example.replace('电子邮件', '企业微信')
  await input.setValue(edited)
  await button('运行调试').trigger('click')
  await flushPromises()
  expect(element.value).toBe(edited)
  expect(document.body.querySelector('.el-message--error')?.textContent).toContain('结果待确认')
  expect(element.disabled).toBe(true)
  expect(button('运行调试').props('disabled')).toBe(true)
  await button('重试原请求').trigger('click')
  await flushPromises()
  expect(element.disabled).toBe(false)
  expect(mocks.debug).toHaveBeenCalledTimes(2)
  expect(mocks.debug.mock.calls[1]?.[0]).toEqual(mocks.debug.mock.calls[0]?.[0])
  expect(mocks.debug.mock.calls[0]?.[0]).toMatchObject({
    input: edited,
    agent_id: '901',
    expected_revision: 7
  })
})

async function openPage() {
  wrapper = mount(SystemAgentDetailPage, {
    attachTo: document.body,
    global: { stubs: { AgentConfigFields: true, RouterLink: true } }
  })
  await flushPromises()
  return wrapper
}

it.each(['confirm', 'cancel', 'failure'])(
  '未保存的系统智能体草稿可%s恢复历史版本',
  async (outcome) => {
    const detail = await mocks.detail()
    const historical = { ...detail.draft, name: '历史名称', system_prompt: '历史提示词' }
    mocks.versions.mockResolvedValue([
      { version: 1, config: historical, created_at: '2026-10-23T00:00:00Z' }
    ])
    mocks.update.mockResolvedValue({ ...detail, revision: 8, draft: historical })
    if (outcome === 'failure') mocks.update.mockRejectedValueOnce(Error('修订冲突'))
    const confirm = vi.spyOn(ElMessageBox, 'confirm')
    if (outcome === 'cancel') confirm.mockRejectedValueOnce('cancel')
    else
      confirm.mockResolvedValueOnce('confirm' as Awaited<ReturnType<typeof ElMessageBox.confirm>>)
    const page = await openPage()
    const config = page
      .findAllComponents(ElForm)
      .find((item) => item.props('model'))!
      .props('model') as { system_prompt: string }
    config.system_prompt = '尚未保存的提示词'
    await flushPromises()
    await page
      .findAll('[role=tab]')
      .find((tab) => tab.text() === '发布历史')!
      .trigger('click')
    expect(button('恢复为草稿').props('disabled')).toBe(false)
    await button('恢复为草稿').trigger('click')
    await flushPromises()
    expect(String(confirm.mock.calls[0]?.[0])).toContain('未保存的修改也将丢弃')
    expect(mocks.update).toHaveBeenCalledTimes(outcome === 'cancel' ? 0 : 1)
    if (outcome !== 'cancel')
      expect(mocks.update).toHaveBeenCalledWith({
        agent_id: '901',
        expected_revision: 7,
        config: historical
      })
    const current = page
      .findAllComponents(ElForm)
      .find((item) => item.props('model'))!
      .props('model') as { system_prompt: string }
    expect(current.system_prompt).toBe(outcome === 'confirm' ? '历史提示词' : '尚未保存的提示词')
  }
)

it('未保存草稿时仍可调试已发布版本，并明确不包含本地修改', async () => {
  const detail = await mocks.detail()
  mocks.detail.mockResolvedValue({
    ...detail,
    published_version: 3,
    published: { ...detail.draft }
  })
  mocks.debug.mockResolvedValue({
    id: 'run-1',
    agent_id: '901',
    status: 'COMPLETED',
    output: '完成'
  })
  const page = await openPage()
  const config = page.findComponent({ name: 'AgentConfigFields' })
  config.vm.$emit('update:modelValue', { ...config.props('modelValue'), name: '未保存名称' })
  await flushPromises()
  await page
    .findAll('[role=tab]')
    .find((tab) => tab.text() === '调试与运行')!
    .trigger('click')
  await page.get('textarea[aria-describedby$="-field-9"]').setValue('测试输入')
  expect(button('运行调试').props('disabled')).toBe(true)
  const published = page
    .findAll<HTMLInputElement>('input[type=radio]')
    .find((item) => item.element.value === 'PUBLISHED')!
  await published.setValue()
  await flushPromises()
  expect(page.text()).toContain('本次调试使用已发布版本，不包含当前未保存的草稿修改')
  expect(button('运行调试').props('disabled')).toBe(false)
  await button('运行调试').trigger('click')
  await flushPromises()
  expect(mocks.debug).toHaveBeenCalledWith(
    expect.objectContaining({
      agent_id: '901',
      expected_revision: 7,
      source: 'PUBLISHED',
      input: '测试输入'
    })
  )
  expect(config.props('modelValue').name).toBe('未保存名称')
})

it('新增技能挂载使用发布配置的激活方式，刷新目录保留草稿并提交空激活覆盖', async () => {
  const detail = await mocks.detail()
  detail.execution_mode = 'AGENT_RUN'
  mocks.options.mockResolvedValue({
    skills: [{ id: 'skill-1', name: '已发布技能' }],
    tools: [],
    deployments: []
  })
  mocks.update.mockImplementationOnce(async ({ config }) => ({
    ...detail,
    revision: 8,
    draft: {
      ...config,
      mounts: config.mounts.map((item: object) => ({ ...item, activation_mode: 'ALWAYS' }))
    }
  }))
  const page = await openPage()
  await button('添加挂载').trigger('click')
  await flushPromises()
  const field = (label: string) =>
    page.findAllComponents(ElFormItem).find((item) => item.props('label') === label)!
  const activation = field('激活方式')
  expect(activation.findComponent(ElSelect).exists()).toBe(false)
  expect(activation.text()).toContain('由技能当前发布配置决定')
  field('能力（跟随当前发布）').getComponent(ElSelect).vm.$emit('update:modelValue', 'skill-1')
  field('Token 预算').getComponent(ElInputNumber).vm.$emit('update:modelValue', 384)
  field('优先级').getComponent(ElInputNumber).vm.$emit('update:modelValue', 5)
  const form = page.findAllComponents(ElForm).find((item) => item.props('model')?.mounts)!
  const config = form.props('model') as {
    system_prompt: string
    mounts: Array<{
      capability_definition_id: string
      token_budget: number
      priority: number
      activation_mode: string | null
    }>
  }
  config.system_prompt = '尚未保存的技能提示词'
  mocks.options.mockResolvedValueOnce({
    skills: [
      { id: 'skill-1', name: '已发布技能' },
      { id: 'skill-2', name: '新发布技能' }
    ],
    tools: [],
    deployments: []
  })
  const detailReads = mocks.detail.mock.calls.length
  await button('刷新能力选项').trigger('click')
  await flushPromises()
  expect(mocks.detail).toHaveBeenCalledTimes(detailReads)
  expect(config.system_prompt).toBe('尚未保存的技能提示词')
  expect(config.mounts).toEqual([
    expect.objectContaining({
      capability_definition_id: 'skill-1',
      token_budget: 384,
      priority: 5,
      activation_mode: null
    })
  ])
  await form.trigger('submit')
  await flushPromises()
  expect(mocks.update).toHaveBeenCalledExactlyOnceWith({
    agent_id: '901',
    expected_revision: 7,
    config: expect.objectContaining({
      system_prompt: '尚未保存的技能提示词',
      mounts: [
        expect.objectContaining({
          capability_type: 'SKILL',
          capability_definition_id: 'skill-1',
          token_budget: 384,
          priority: 5,
          activation_mode: null
        })
      ]
    })
  })
  expect(form.props('model')!.mounts[0]!.activation_mode).toBe('ALWAYS')
  expect(field('激活方式').findComponent(ElSelect).exists()).toBe(false)
})

it('已有技能挂载切换类型时清除原激活值，切回技能后仍由发布配置决定', async () => {
  prepareToolMount()
  const detail = await mocks.detail()
  Object.assign(detail.draft.mounts[0], {
    capability_type: 'SKILL',
    capability_definition_id: 'skill-1',
    activation_mode: 'ALWAYS',
    token_budget: 384
  })
  mocks.options.mockResolvedValue({
    skills: [{ id: 'skill-1', name: '已发布技能' }],
    tools: [],
    deployments: []
  })
  const page = await openPage()
  const field = (label: string) =>
    page.findAllComponents(ElFormItem).find((item) => item.props('label') === label)!
  const config = page
    .findAllComponents(ElForm)
    .find((item) => item.props('model')?.mounts)!
    .props('model') as { mounts: Array<{ activation_mode: string | null }> }
  expect(config.mounts[0]!.activation_mode).toBe('ALWAYS')
  expect(field('激活方式').findComponent(ElSelect).exists()).toBe(false)
  const type = field('类型').getComponent(ElSelect)
  for (const value of ['TOOL', 'SKILL']) {
    type.vm.$emit('update:modelValue', value)
    type.vm.$emit('change', value)
    await flushPromises()
    expect(config.mounts[0]!.activation_mode).toBeNull()
  }
  expect(field('激活方式').text()).toContain('由技能当前发布配置决定')
  expect(field('激活方式').findComponent(ElSelect).exists()).toBe(false)
  expect(mocks.update).not.toHaveBeenCalled()
})

it('草稿中独立刷新能力目录，保留已有挂载并提示失效实例', async () => {
  prepareToolMount()
  const detail = await mocks.detail()
  detail.draft.mounts[0].tool_deployment_id = '101'
  mocks.detail.mockResolvedValue(detail)
  mocks.options.mockResolvedValueOnce({
    skills: [],
    tools: [{ id: '21', name: '查询工具' }],
    deployments: [{ id: '101', name: '服务一', tool_definition_id: '21', environment: 'TEST' }]
  })
  const page = await openPage()
  const config = page
    .findAllComponents(ElForm)
    .find((item) => item.props('model')?.mounts)!
    .props('model') as {
    name: string
    mounts: Array<{ capability_definition_id: string; tool_deployment_id: string | null }>
  }
  config.name = '未保存名称'
  mocks.options.mockRejectedValueOnce(new Error('能力目录暂不可读'))
  await button('刷新能力选项').trigger('click')
  await flushPromises()
  expect(config.name).toBe('未保存名称')
  expect(config.mounts[0]).toMatchObject({
    capability_definition_id: '21',
    tool_deployment_id: '101'
  })
  expect(document.body.textContent).toContain('能力目录暂不可读')
  mocks.options.mockResolvedValueOnce({
    skills: [{ id: 'skill-new', name: '新技能' }],
    tools: [{ id: '21', name: '查询工具' }],
    deployments: []
  })
  await button('刷新能力选项').trigger('click')
  await flushPromises()
  expect(config.mounts[0]!.tool_deployment_id).toBe('101')
  expect(page.text()).toContain('当前服务实例不在可挂载目录中')
  expect(config.name).toBe('未保存名称')
  expect(mocks.detail).toHaveBeenCalledTimes(2)
  expect(document.body.textContent).not.toContain('能力目录暂不可读')
})

it('首次能力目录失败时可原地重读且不重置当前草稿', async () => {
  prepareToolMount()
  mocks.options.mockRejectedValueOnce(new Error('能力目录读取失败'))
  const page = await openPage()
  const config = page
    .findAllComponents(ElForm)
    .find((item) => item.props('model')?.mounts)!
    .props('model') as { name: string }
  config.name = '正在编辑'
  await flushPromises()
  expect(document.body.textContent).toContain('能力目录读取失败')
  mocks.options.mockResolvedValueOnce({
    skills: [],
    tools: [{ id: '21', name: '已发布工具' }],
    deployments: []
  })
  await button('刷新能力选项').trigger('click')
  await flushPromises()
  const selector = page
    .findAllComponents(ElFormItem)
    .find((item) => item.props('label') === '能力（跟随当前发布）')!
    .getComponent(ElSelect)
  expect(
    selector.findAllComponents({ name: 'ElOption' }).map((item) => item.props('label'))
  ).toEqual(['已发布工具'])
  expect(config.name).toBe('正在编辑')
  expect(mocks.detail).toHaveBeenCalledTimes(1)
  expect(document.body.textContent).not.toContain('能力目录读取失败')
})

it('刷新能力目录时身份变化，旧账号结果不能进入新账号', async () => {
  prepareToolMount()
  const page = await openPage()
  let complete!: (value: unknown) => void
  mocks.options.mockReturnValueOnce(
    new Promise((resolve) => {
      complete = resolve
    })
  )
  await button('刷新能力选项').trigger('click')
  mocks.options.mockResolvedValueOnce({
    skills: [],
    tools: [{ id: '22', name: '新账号工具' }],
    deployments: []
  })
  mocks.auth.user = { sub: 'other' }
  mocks.auth.identityVersion++
  complete({ skills: [], tools: [{ id: '21', name: '旧账号工具' }], deployments: [] })
  await flushPromises()
  const selector = page
    .findAllComponents(ElFormItem)
    .find((item) => item.props('label') === '能力（跟随当前发布）')!
    .getComponent(ElSelect)
  expect(
    selector.findAllComponents({ name: 'ElOption' }).map((item) => item.props('label'))
  ).toEqual(['新账号工具'])
})

it('草稿编辑时可独立刷新模型选项，失败保留选择，成功不重读或覆盖草稿', async () => {
  const oldModel = { id: 'old-model', name: '旧模型', kind: 'LLM', enabled: true }
  mocks.models.mockResolvedValueOnce([oldModel])
  const page = await openPage()
  const config = page.getComponent({ name: 'AgentConfigFields' })
  config.vm.$emit('update:modelValue', {
    ...config.props('modelValue'),
    name: '未保存名称',
    model_config_id: 'old-model'
  })
  await flushPromises()
  mocks.models.mockRejectedValueOnce(new Error('模型目录暂不可读'))
  await button('刷新模型选项').trigger('click')
  await flushPromises()
  expect(config.props('modelValue')).toMatchObject({
    name: '未保存名称',
    model_config_id: 'old-model'
  })
  expect(config.props('models')).toEqual([oldModel])
  expect(document.body.textContent).toContain('模型目录暂不可读')
  const newModel = { id: 'new-model', name: '新模型', kind: 'LLM', enabled: true }
  mocks.models.mockResolvedValueOnce([oldModel, newModel])
  await button('刷新模型选项').trigger('click')
  await flushPromises()
  expect(config.props('models')).toEqual([oldModel, newModel])
  expect(config.props('modelValue')).toMatchObject({
    name: '未保存名称',
    model_config_id: 'old-model'
  })
  expect(mocks.detail).toHaveBeenCalledTimes(1)
  mocks.models.mockResolvedValueOnce([newModel])
  await button('刷新模型选项').trigger('click')
  await flushPromises()
  expect(config.props('modelValue').model_config_id).toBe('old-model')
  expect(page.text()).toContain('当前选择不在已启用的对话模型目录中')
})

it('首次模型目录读取失败可单独重试，不重读智能体草稿', async () => {
  mocks.models.mockRejectedValueOnce(new Error('模型目录读取失败'))
  const page = await openPage()
  const config = page.getComponent({ name: 'AgentConfigFields' })
  config.vm.$emit('update:modelValue', { ...config.props('modelValue'), name: '正在编辑' })
  await flushPromises()
  expect(document.body.textContent).toContain('模型目录读取失败')
  const model = { id: 'new-model', name: '已启用模型', kind: 'LLM', enabled: true }
  mocks.models.mockResolvedValueOnce([model])
  await button('刷新模型选项').trigger('click')
  await flushPromises()
  expect(config.props('models')).toEqual([model])
  expect(config.props('modelValue').name).toBe('正在编辑')
  expect(mocks.detail).toHaveBeenCalledTimes(1)
  expect(document.body.textContent).not.toContain('模型目录读取失败')
})

it('模型选项读取期间切换身份，迟到旧目录不能覆盖当前账号', async () => {
  const oldModel = { id: 'old-model', name: '旧账号模型', kind: 'LLM', enabled: true }
  mocks.models.mockResolvedValueOnce([oldModel])
  const page = await openPage()
  let complete!: (value: unknown) => void
  mocks.models.mockReturnValueOnce(
    new Promise((resolve) => {
      complete = resolve
    })
  )
  await button('刷新模型选项').trigger('click')
  const newModel = { id: 'new-model', name: '新账号模型', kind: 'LLM', enabled: true }
  mocks.models.mockResolvedValueOnce([newModel])
  mocks.auth.user = { sub: 'other' }
  mocks.auth.identityVersion++
  complete([oldModel])
  await flushPromises()
  expect(page.getComponent({ name: 'AgentConfigFields' }).props('models')).toEqual([newModel])
  expect(page.text()).not.toContain('旧账号模型')
})

it('仅有系统智能体读取权限仍能读取详情，不请求无权模型目录', async () => {
  mocks.permissions = ['foundation.system_agents.read']
  const page = await openPage()
  expect(mocks.models).not.toHaveBeenCalled()
  expect(page.findAllComponents(ElButton).some((item) => item.text() === '刷新模型选项')).toBe(
    false
  )
  expect(mocks.detail).toHaveBeenCalledWith('901', { silent: true })
  expect(page.find('[role=tab]').exists()).toBe(true)
  expect(page.text()).toContain('记忆提取')
})

it('辅助目录失败保留已成功读取的详情，所有批次错误只展示一条Message', async () => {
  const failures = [
    new HttpClientError({ status: 503, userMessage: '资料暂不可用' }),
    new HttpClientError({ status: 503, userMessage: '资料暂不可用' })
  ]
  mocks.models.mockRejectedValueOnce(failures[0])
  mocks.options.mockRejectedValueOnce(failures[1])
  const page = await openPage()
  expect(page.find('[role=tab]').exists()).toBe(true)
  expect(failures.every((reason) => reason.hasFeedbackOwner)).toBe(true)
  expect(document.querySelectorAll('.el-message--error')).toHaveLength(1)
  expect(mocks.models).toHaveBeenCalledWith({ signal: expect.any(AbortSignal), silent: true })
  expect(mocks.options).toHaveBeenCalledWith({ signal: expect.any(AbortSignal), silent: true })
})

it('首次详情失败提供重读入口，读取成功后恢复详情', async () => {
  mocks.detail.mockRejectedValueOnce(new Error('详情暂不可读'))
  const page = await openPage()
  expect(page.find('[role=tab]').exists()).toBe(false)
  await button('重新读取资料').trigger('click')
  await flushPromises()
  expect(page.find('[role=tab]').exists()).toBe(true)
  expect(mocks.detail).toHaveBeenCalledTimes(2)
})

it('核对得到409或非终态时仍保留原命令，不解锁新的执行', async () => {
  mocks.debug
    .mockRejectedValueOnce(new HttpClientError({ status: 0, userMessage: '结果未知' }))
    .mockRejectedValueOnce(new HttpClientError({ status: 409, userMessage: '原执行处理中' }))
    .mockResolvedValueOnce({ id: 'run-1', status: 'RUNNING' })
    .mockResolvedValueOnce({ id: 'run-1', status: 'COMPLETED' })
  const page = await openPage()
  await page
    .findAll('[role=tab]')
    .find((tab) => tab.text() === '调试与运行')!
    .trigger('click')
  await page.get('textarea[aria-describedby$="-field-9"]').setValue('原始调试输入')
  await button('运行调试').trigger('click')
  await flushPromises()
  const original = { ...mocks.debug.mock.calls[0]![0] }
  for (let step = 0; step < 3; step++) {
    await button('重试原请求').trigger('click')
    await flushPromises()
    expect(mocks.debug.mock.calls[step + 1]![0]).toEqual(original)
    expect(page.find('[aria-label="原调试结果待核对"]').exists()).toBe(step < 2)
  }
})

it('首次派发前的明确校验拒绝允许修改输入，核对阶段校验错误仍保留未知事实', async () => {
  mocks.debug
    .mockRejectedValueOnce(
      new HttpClientError({ status: 400, code: 'VALIDATION_ERROR', userMessage: '输入格式无效' })
    )
    .mockRejectedValueOnce(new HttpClientError({ status: 0, userMessage: '结果未知' }))
    .mockRejectedValueOnce(
      new HttpClientError({ status: 400, code: 'VALIDATION_ERROR', userMessage: '核对未完成' })
    )
  const page = await openPage()
  await page
    .findAll('[role=tab]')
    .find((tab) => tab.text() === '调试与运行')!
    .trigger('click')
  const input = page.get('textarea[aria-describedby$="-field-9"]')
  await input.setValue('无效输入')
  await button('运行调试').trigger('click')
  await flushPromises()
  expect((input.element as HTMLTextAreaElement).disabled).toBe(false)
  await input.setValue('有效输入')
  await button('运行调试').trigger('click')
  await flushPromises()
  await button('重试原请求').trigger('click')
  await flushPromises()
  expect(page.find('[aria-label="原调试结果待核对"]').exists()).toBe(true)
  expect((input.element as HTMLTextAreaElement).disabled).toBe(true)
})

it('离开后重新进入自动保留原标识并只读找回结果，不重新派发调试', async () => {
  mocks.debug.mockRejectedValueOnce(new Error('结果待确认'))
  let page = await openPage()
  await page
    .findAll('[role=tab]')
    .find((tab) => tab.text() === '调试与运行')!
    .trigger('click')
  await page.get('textarea[aria-describedby$="-field-9"]').setValue('原始输入')
  await button('运行调试').trigger('click')
  await flushPromises()
  const key = mocks.debug.mock.calls[0]![0].idempotency_key
  expect(page.get('[aria-label="原调试结果待核对"]').text()).toContain(key)
  page.unmount()
  wrapper = undefined
  clearFeedback()
  mocks.debugResult.mockResolvedValueOnce({
    id: 'original-run',
    status: 'COMPLETED',
    output: '原执行的持久结果'
  })
  page = await openPage()
  await page
    .findAll('[role=tab]')
    .find((tab) => tab.text() === '调试与运行')!
    .trigger('click')
  expect(page.get('[aria-label="原调试结果待核对"]').text()).toContain(key)
  expect(page.findAllComponents(ElButton).some((item) => item.text() === '重试原请求')).toBe(false)
  expect(
    sessionStorage.getItem(`skillforge.system-agent-debug:${JSON.stringify(['actor', '901'])}`)
  ).toBe(key)
  expect(JSON.stringify(sessionStorage)).not.toContain('原始输入')
  await button('只读查询原调试').trigger('click')
  await flushPromises()
  expect(mocks.debugResult).toHaveBeenCalledWith('901', key, { silent: true })
  expect(page.text()).toContain('原执行的持久结果')
  expect(mocks.debug).toHaveBeenCalledTimes(1)
})

it('确定结果但本地记录清理失败时保持锁定，只读核对后恢复', async () => {
  mocks.debug.mockResolvedValueOnce({ id: 'run-1', status: 'COMPLETED', output: '结果' })
  mocks.debugResult.mockResolvedValueOnce({ id: 'run-1', status: 'COMPLETED', output: '结果' })
  const page = await openPage()
  await page
    .findAll('[role=tab]')
    .find((tab) => tab.text() === '调试与运行')!
    .trigger('click')
  await page.get('textarea[aria-describedby$="-field-9"]').setValue('调试输入')
  const storage = vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => {
    throw new Error('存储不可用')
  })
  await button('运行调试').trigger('click')
  await flushPromises()
  expect(page.text()).toContain('无法清理本地记录')
  expect(button('运行调试').props('disabled')).toBe(true)
  expect(page.findAllComponents(ElButton).some((item) => item.text() === '重试原请求')).toBe(false)
  storage.mockRestore()
  await button('只读查询原调试').trigger('click')
  await flushPromises()
  expect(page.find('[aria-label="原调试结果待核对"]').exists()).toBe(false)
  expect(button('运行调试').props('disabled')).toBe(false)
  expect(mocks.debug).toHaveBeenCalledTimes(1)
})

it('账号切换时旧目录迟到后重新读取新账号目录', async () => {
  let complete!: (value: unknown) => void
  const current = await mocks.detail()
  mocks.detail.mockReturnValueOnce(
    new Promise((resolve) => {
      complete = resolve
    })
  )
  const page = await openPage()
  mocks.auth.user = { sub: 'another' }
  mocks.auth.identityVersion++
  complete({ ...current, agent_key: 'old-actor-agent' })
  await flushPromises()
  expect(mocks.detail).toHaveBeenCalledTimes(3)
  expect(page.text()).not.toContain('old-actor-agent')
  expect(page.text()).toContain('memory-extraction')
})

it('存储不可写时不派发调试', async () => {
  const page = await openPage()
  await page
    .findAll('[role=tab]')
    .find((tab) => tab.text() === '调试与运行')!
    .trigger('click')
  await page.get('textarea[aria-describedby$="-field-9"]').setValue('不应发出的输入')
  const storage = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
    throw new Error('存储不可用')
  })
  await button('运行调试').trigger('click')
  await flushPromises()
  expect(mocks.debug).not.toHaveBeenCalled()
  expect(page.text()).toContain('尚未发起调试')
  storage.mockRestore()
})

it('查询途中切换账号时不展示或清理原账号结果', async () => {
  mocks.debug.mockRejectedValueOnce(new Error('结果待确认'))
  let complete!: (value: unknown) => void
  mocks.debugResult.mockReturnValueOnce(
    new Promise((resolve) => {
      complete = resolve
    })
  )
  const page = await openPage()
  await page
    .findAll('[role=tab]')
    .find((tab) => tab.text() === '调试与运行')!
    .trigger('click')
  await page.get('textarea[aria-describedby$="-field-9"]').setValue('原账号输入')
  await button('运行调试').trigger('click')
  await flushPromises()
  const key = mocks.debug.mock.calls[0]![0].idempotency_key
  await button('只读查询原调试').trigger('click')
  mocks.auth.user = { sub: 'another' }
  mocks.auth.identityVersion++
  complete({ id: 'old-run', status: 'COMPLETED', output: '原账号结果' })
  await flushPromises()
  expect(page.text()).not.toContain('原账号结果')
  expect(page.text()).not.toContain(key)
  expect(
    sessionStorage.getItem(`skillforge.system-agent-debug:${JSON.stringify(['actor', '901'])}`)
  ).toBe(key)
  expect(
    sessionStorage.getItem(`skillforge.system-agent-debug:${JSON.stringify(['another', '901'])}`)
  ).toBeNull()
})

it('按标识查询404保留原未知状态且不重新POST调试', async () => {
  mocks.debug.mockRejectedValueOnce(new Error('结果待确认'))
  const missing = new HttpClientError({ status: 404, userMessage: '记录不存在' })
  mocks.debugResult.mockRejectedValueOnce(missing)
  const page = await openPage()
  await page
    .findAll('[role=tab]')
    .find((tab) => tab.text() === '调试与运行')!
    .trigger('click')
  const input = page.get('textarea[aria-describedby$="-field-9"]')
  await input.setValue('不能重新派发的输入')
  await button('运行调试').trigger('click')
  await flushPromises()
  const key = mocks.debug.mock.calls[0]![0].idempotency_key
  clearFeedback()
  await button('只读查询原调试').trigger('click')
  await flushPromises()
  expect(mocks.debugResult).toHaveBeenCalledWith('901', key, { silent: true })
  expect(missing.hasFeedbackOwner).toBe(true)
  expect(document.body.querySelector('.el-message--warning')?.textContent).toContain(
    '不能据此认定原请求未执行'
  )
  expect(page.get('[aria-label="原调试结果待核对"]').text()).toContain(key)
  expect((input.element as HTMLTextAreaElement).disabled).toBe(true)
  expect(button('运行调试').props('disabled')).toBe(true)
  expect(mocks.debug).toHaveBeenCalledTimes(1)
})

it('只读找回RUNNING及结果未知仍冻结原命令，同一标识的明确终态才解除pending', async () => {
  mocks.debug.mockRejectedValueOnce(new Error('结果待确认'))
  mocks.debugResult
    .mockResolvedValueOnce({ id: 'original-run', status: 'RUNNING', output: '处理中' })
    .mockResolvedValueOnce({
      id: 'original-run',
      status: 'FAILED',
      error_code: 'system_agent_outcome_unknown',
      output: '待核对'
    })
    .mockResolvedValueOnce({ id: 'other-run', status: 'COMPLETED', output: '另一执行' })
    .mockResolvedValueOnce({
      id: 'original-run',
      status: 'FAILED',
      error_code: 'model_rejected',
      output: '原执行明确失败'
    })
  const page = await openPage()
  await page
    .findAll('[role=tab]')
    .find((tab) => tab.text() === '调试与运行')!
    .trigger('click')
  const input = page.get('textarea[aria-describedby$="-field-9"]')
  await input.setValue('原始输入')
  await button('运行调试').trigger('click')
  await flushPromises()
  const key = mocks.debug.mock.calls[0]![0].idempotency_key
  const lookup = page.get('input[maxlength="256"]')
  for (const query of [key, key, 'another-key']) {
    await lookup.setValue(query)
    await button('查询原调试').trigger('click')
    await flushPromises()
    expect(page.find('[aria-label="原调试结果待核对"]').exists()).toBe(true)
    expect((input.element as HTMLTextAreaElement).disabled).toBe(true)
    expect(button('运行调试').props('disabled')).toBe(true)
    if (mocks.debugResult.mock.calls.length === 2) {
      expect(page.text()).toContain('结果未知')
      expect(document.body.querySelector('.el-message--info')?.textContent).toContain('结果仍未知')
      expect(page.findAll('.el-table__body-wrapper tbody tr')).toHaveLength(1)
    }
    clearFeedback()
  }
  await lookup.setValue(key)
  await button('查询原调试').trigger('click')
  await flushPromises()
  expect(page.find('[aria-label="原调试结果待核对"]').exists()).toBe(false)
  expect((input.element as HTMLTextAreaElement).disabled).toBe(false)
  expect(page.text()).toContain('原执行明确失败')
  expect(mocks.debug).toHaveBeenCalledTimes(1)
})

it('原调试直接返回结果未知错误码时保持冻结，不能当作FAILED解除原命令', async () => {
  mocks.debug.mockResolvedValueOnce({
    id: 'original-run',
    status: 'FAILED',
    error_code: 'system_agent_outcome_unknown',
    output: '待核对'
  })
  const page = await openPage()
  await page
    .findAll('[role=tab]')
    .find((tab) => tab.text() === '调试与运行')!
    .trigger('click')
  const input = page.get('textarea[aria-describedby$="-field-9"]')
  await input.setValue('原始输入')
  await button('运行调试').trigger('click')
  await flushPromises()
  expect(page.find('[aria-label="原调试结果待核对"]').exists()).toBe(true)
  expect((input.element as HTMLTextAreaElement).disabled).toBe(true)
  expect(page.text()).toContain('结果未知')
  expect(mocks.debug).toHaveBeenCalledTimes(1)
})

it.each(['publish', 'cancel', 'save-failure', 'publish-failure', 'permission-change'])(
  '保存后继续发布使用精确修订并处理 %s',
  async (outcome) => {
    const original = await mocks.detail()
    const next = {
      ...original,
      revision: 8,
      draft: { ...original.draft, system_prompt: '新提示词' }
    }
    mocks.update.mockImplementationOnce(async () => {
      if (outcome === 'save-failure') throw Error('保存失败')
      if (outcome === 'permission-change') mocks.permissions = ['foundation.system_agents.update']
      return next
    })
    mocks.publish.mockResolvedValue({ ...next, published_version: 1, published: next.draft })
    if (outcome === 'publish-failure') mocks.publish.mockRejectedValueOnce(Error('发布失败'))
    const dialog = vi.spyOn(ElMessageBox, 'confirm')
    if (outcome === 'cancel') dialog.mockRejectedValueOnce('cancel')
    else dialog.mockResolvedValueOnce('confirm' as Awaited<ReturnType<typeof ElMessageBox.confirm>>)
    const page = await openPage()
    const config = page
      .findAllComponents(ElForm)
      .find((item) => item.props('model'))!
      .props('model') as { system_prompt: string }
    config.system_prompt = '新提示词'
    await flushPromises()
    await button('保存并继续发布').trigger('click')
    await flushPromises()
    expect(mocks.update).toHaveBeenCalledExactlyOnceWith(
      expect.objectContaining({ agent_id: '901', expected_revision: 7 })
    )
    expect(mocks.publish).toHaveBeenCalledTimes(
      outcome === 'publish' || outcome === 'publish-failure' ? 1 : 0
    )
    if (outcome === 'publish' || outcome === 'publish-failure')
      expect(mocks.publish).toHaveBeenCalledWith({ agent_id: '901', expected_revision: 8 })
    expect(dialog).toHaveBeenCalledTimes(
      outcome === 'save-failure' || outcome === 'permission-change' ? 0 : 1
    )
    expect(
      (
        page
          .findAllComponents(ElForm)
          .find((item) => item.props('model'))!
          .props('model') as { system_prompt: string }
      ).system_prompt
    ).toBe('新提示词')
  }
)
