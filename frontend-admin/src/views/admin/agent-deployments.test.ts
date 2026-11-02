import { defineComponent } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { DOMWrapper, flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { ElButton, ElFormItem, ElInput, ElSelect } from 'element-plus'
import AgentDeploymentsPage from './AgentDeploymentsPage.vue'

const mocks = vi.hoisted(() => ({
  list: vi.fn(),
  targetOptions: vi.fn(),
  definitions: vi.fn(),
  grants: vi.fn(),
  detail: vi.fn(),
  create: vi.fn(),
  bindings: vi.fn(),
  authorization: vi.fn(),
  confirm: vi.fn(),
  resourceDefinitions: vi.fn(),
  resourceVersions: vi.fn(),
  toolDeployments: vi.fn(),
  workflowInstances: vi.fn(),
  agents: vi.fn(),
  auth: {
    user: {
      tenant_id: '7',
      permissions: [
        'tenant.agent_deployments.read',
        'tenant.agent_deployments.create',
        'tenant.agent_deployments.bind',
        'tenant.agent_deployments.switch_authorization'
      ]
    }
  }
}))
vi.mock('../../stores/auth', () => ({ useAuthStore: () => mocks.auth }))
vi.mock('@skillforge/shared/feedback', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@skillforge/shared/feedback')>()),
  message: { success: vi.fn(), warning: vi.fn() },
  confirm: mocks.confirm
}))
vi.mock('../../api', () => ({
  api: {
    institutionManagement: { targetOptions: mocks.targetOptions },
    agentDeployments: {
      ...mocks,
      grants: mocks.grants
    },
    agents: { list: mocks.agents },
    capabilities: {
      listDefinitions: mocks.resourceDefinitions,
      deployments: mocks.toolDeployments,
      versions: mocks.resourceVersions
    },
    workflowDrafts: { instances: mocks.workflowInstances }
  }
}))
const template = {
  id: 't1',
  name: '公共客服',
  version: '2',
  definition: {
    resource_slots: [
      {
        key: 'knowledge',
        label: '机构知识',
        description: '只访问本机构资源',
        capability_type: 'KNOWLEDGE_SOURCE'
      }
    ],
    delegation_slots: []
  }
}
const deployment = {
  deployment_id: 'deployment-1',
  integration_id: null,
  grant_id: 'grant-1',
  grant_revision: 1,
  tenant_id: 'tenant-1',
  agent_id: 'agent-1',
  external_agent_ref: 'external-agent-1',
  definition_id: 't1',
  definition_name: '公共客服',
  definition_version: '2',
  mode: 'FOLLOW_PUBLISHED',
  deployment_revision: 7,
  ready: false,
  issues: [{ path: 'resource.knowledge', code: 'missing', message: '请绑定机构知识' }],
  resource_slots: template.definition.resource_slots,
  delegation_slots: [],
  resource_bindings: [],
  delegation_bindings: [],
  parameters: {}
}
const wrappers: VueWrapper[] = []
afterEach(() => {
  wrappers.forEach((value) => value.unmount())
  wrappers.length = 0
  document.body.innerHTML = ''
})
beforeEach(() => {
  vi.clearAllMocks()
  mocks.auth.user.permissions = [
    'tenant.agent_deployments.read',
    'tenant.agent_deployments.create',
    'tenant.agent_deployments.bind',
    'tenant.agent_deployments.switch_authorization'
  ]
  mocks.targetOptions.mockImplementation(async (_operation, query) => ({
    items: [
      {
        id: query?.id ?? '7',
        label: query?.id ? '目标机构' : '本机构',
        description: query?.id ?? '7'
      }
    ],
    total: 1,
    page: 1,
    page_size: 200
  }))
  mocks.confirm.mockResolvedValue(true)
  mocks.list.mockResolvedValue({ items: [deployment], total: 1 })
  mocks.definitions.mockResolvedValue({ items: [template], total: 1 })
  mocks.grants.mockResolvedValue({
    total: 1,
    items: [
      {
        grant_id: 'grant-1',
        definition_id: 't1',
        target_tenant_id: 'tenant-1',
        definition_name: '公共客服',
        actions: ['deploy', 'execute'],
        status: 'active',
        revision: 1
      }
    ]
  })
  mocks.detail.mockResolvedValue(deployment)
  mocks.resourceDefinitions.mockResolvedValue([
    { id: '101', name: '机构知识', capabilityType: 'KNOWLEDGE_SOURCE', defaultVersionId: '201' },
    { id: '102', name: '机构工具', capabilityType: 'TOOL', defaultVersionId: '202' },
    { id: '103', name: '机构工作流', capabilityType: 'WORKFLOW', defaultVersionId: '203' }
  ])
  mocks.resourceVersions.mockImplementation(async (id: string) => [
    { id: String(Number(id) + 100), definitionId: id }
  ])
  mocks.toolDeployments.mockResolvedValue([
    { deploymentKey: 'tool-a', toolVersionId: '202', revision: 5 }
  ])
  mocks.workflowInstances.mockResolvedValue({
    items: [{ instanceId: 'workflow-a', capabilityVersionId: '203', status: 'published' }]
  })
  mocks.agents.mockResolvedValue({ items: [] })
})
async function open(
  path = '/admin/agent-deployments',
  props?: { tenantId?: string; embedded?: boolean }
) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/admin/agent-deployments', component: AgentDeploymentsPage, props }]
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
function button(wrapper: VueWrapper, text: string) {
  const value = wrapper.findAllComponents(ElButton).find((item) => item.text() === text)
  if (!value) throw Error(text)
  return value
}
function field(wrapper: VueWrapper, label: string) {
  const value = wrapper.findAllComponents(ElFormItem).find((item) => item.props('label') === label)
  if (!value) throw Error(label)
  return value
}
async function choose(wrapper: VueWrapper, label: string, value: string | number) {
  field(wrapper, label).getComponent(ElSelect).vm.$emit('update:modelValue', value)
  await flushPromises()
}
async function begin(wrapper: VueWrapper) {
  await button(wrapper, '部署公共智能体').trigger('click')
  await flushPromises()
  await choose(wrapper, '公共智能体模板', 't1')
  if (field(wrapper, '显式授权依据').findComponent(ElSelect).exists())
    await choose(wrapper, '显式授权依据', 'grant-1')
  await field(wrapper, '外部智能体标识')
    .getComponent(ElInput)
    .get('input')
    .setValue('external-agent-2')
}

describe('机构公共Agent部署交互', () => {
  it('唯一有效授权自动选择并随原模板提交', async () => {
    mocks.create.mockResolvedValue(deployment)
    const wrapper = await open()
    await begin(wrapper)
    expect(field(wrapper, '显式授权依据').text()).toContain('已自动选择唯一可用授权')
    await button(wrapper, '保存机构实例').trigger('click')
    await flushPromises()
    expect(mocks.create.mock.calls[0]?.[0]).toEqual(
      expect.objectContaining({ definition_id: 't1', grant_id: 'grant-1' })
    )
  })
  it('多个有效授权时保留用户选择，不能凭空生成授权依据', async () => {
    mocks.grants.mockResolvedValue({
      items: [
        {
          grant_id: 'grant-1',
          definition_id: 't1',
          definition_name: '公共客服',
          actions: ['deploy', 'execute'],
          status: 'active'
        },
        {
          grant_id: 'grant-2',
          definition_id: 't1',
          definition_name: '公共客服',
          actions: ['deploy', 'execute'],
          status: 'active'
        }
      ]
    })
    const wrapper = await open()
    await button(wrapper, '部署公共智能体').trigger('click')
    await flushPromises()
    await choose(wrapper, '公共智能体模板', 't1')
    expect(field(wrapper, '显式授权依据').getComponent(ElSelect).props('modelValue')).toBe('')
    await field(wrapper, '外部智能体标识').getComponent(ElInput).get('input').setValue('external')
    await button(wrapper, '保存机构实例').trigger('click')
    await flushPromises()
    expect(mocks.create).not.toHaveBeenCalled()
    await choose(wrapper, '显式授权依据', 'grant-2')
    mocks.create.mockResolvedValue(deployment)
    await button(wrapper, '保存机构实例').trigger('click')
    await flushPromises()
    expect(mocks.create.mock.calls[0]?.[0].grant_id).toBe('grant-2')
  })
  it('选择工具和工作流后自动绑定唯一实例并保存', async () => {
    mocks.detail.mockResolvedValue({
      ...deployment,
      resource_slots: [
        { key: 'tool', label: '工具', capability_type: 'TOOL' },
        { key: 'workflow', label: '工作流', capability_type: 'WORKFLOW' }
      ]
    })
    mocks.bindings.mockResolvedValue(deployment)
    const wrapper = await open()
    await button(wrapper, '配置机构绑定').trigger('click')
    await flushPromises()
    for (const [label, id] of [
      ['工具', '102'],
      ['工作流', '103']
    ]) {
      const select = field(wrapper, label!).getComponent(ElSelect)
      select.vm.$emit('update:modelValue', id)
      select.vm.$emit('change', id)
      await flushPromises()
      expect(field(wrapper, label!).text()).toContain('已选择唯一可用实例')
      expect(field(wrapper, label!).findAllComponents(ElSelect)).toHaveLength(1)
    }
    await button(wrapper, '保存机构实例').trigger('click')
    await flushPromises()
    expect(mocks.bindings.mock.calls[0]?.[0].resource_bindings).toEqual([
      {
        slot_key: 'tool',
        mount: {
          capability_type: 'TOOL',
          capability_definition_id: '102',
          tool_config: { tool_deployment_id: 'tool-a' }
        }
      },
      {
        slot_key: 'workflow',
        mount: {
          capability_type: 'WORKFLOW',
          capability_definition_id: '103',
          workflow_config: { workflow_instance_id: 'workflow-a' }
        }
      }
    ])
  })
  it('有多个匹配工具实例时要求用户选择', async () => {
    mocks.detail.mockResolvedValue({
      ...deployment,
      resource_slots: [{ key: 'tool', label: '工具', capability_type: 'TOOL' }]
    })
    mocks.toolDeployments.mockResolvedValue([
      { deploymentKey: 'tool-a', toolVersionId: '202' },
      { deploymentKey: 'tool-b', toolVersionId: '202' }
    ])
    const wrapper = await open()
    await button(wrapper, '配置机构绑定').trigger('click')
    await flushPromises()
    const select = field(wrapper, '工具').getComponent(ElSelect)
    select.vm.$emit('update:modelValue', '102')
    select.vm.$emit('change', '102')
    await flushPromises()
    expect(field(wrapper, '工具').findAllComponents(ElSelect)).toHaveLength(2)
    await button(wrapper, '保存机构实例').trigger('click')
    await flushPromises()
    expect(mocks.bindings).not.toHaveBeenCalled()
  })
  it('无资源或委派槽的参数编辑不读取任何辅助目录，保留CAS并提交参数', async () => {
    mocks.detail.mockResolvedValue({
      ...deployment,
      resource_slots: [],
      parameters: { note: '原内容' }
    })
    for (const read of [
      mocks.resourceDefinitions,
      mocks.resourceVersions,
      mocks.toolDeployments,
      mocks.workflowInstances,
      mocks.agents
    ])
      read.mockRejectedValue(new Error('没有无关目录权限'))
    mocks.bindings.mockResolvedValue(deployment)
    const wrapper = await open()
    await button(wrapper, '配置机构绑定').trigger('click')
    await flushPromises()
    await new DOMWrapper(document.body).get('input[aria-label="参数值"]').setValue('修改内容')
    await button(wrapper, '保存机构实例').trigger('click')
    await flushPromises()
    expect(mocks.bindings.mock.calls[0]?.[0]).toEqual({
      deployment_id: 'deployment-1',
      expected_revision: 7,
      resource_bindings: [],
      delegation_bindings: [],
      parameters: { note: '修改内容' }
    })
    for (const read of [
      mocks.resourceDefinitions,
      mocks.resourceVersions,
      mocks.toolDeployments,
      mocks.workflowInstances,
      mocks.agents
    ])
      expect(read).not.toHaveBeenCalled()
  })

  it('只有知识槽时只读取能力定义，不读取工具工作流Agent目录或能力版本', async () => {
    for (const read of [mocks.toolDeployments, mocks.workflowInstances, mocks.agents])
      read.mockRejectedValue(new Error('无关目录读取被拒绝'))
    mocks.resourceVersions.mockImplementation(async (id: string) => {
      if (id !== '101') throw Error('无关能力版本不可读')
      return [{ id: '201', definitionId: '101' }]
    })
    mocks.bindings.mockResolvedValue(deployment)
    const wrapper = await open()
    await button(wrapper, '配置机构绑定').trigger('click')
    await flushPromises()
    await choose(wrapper, '机构知识', '101')
    await button(wrapper, '保存机构实例').trigger('click')
    await flushPromises()
    expect(mocks.bindings).toHaveBeenCalledOnce()
    expect(mocks.resourceVersions).not.toHaveBeenCalled()
    for (const read of [mocks.toolDeployments, mocks.workflowInstances, mocks.agents])
      expect(read).not.toHaveBeenCalled()
  })

  it.each(['TOOL', 'WORKFLOW', 'DELEGATION'])(
    '仅%s槽加载实际消费的目录及对应类型版本',
    async (type) => {
      mocks.detail.mockResolvedValue({
        ...deployment,
        resource_slots:
          type === 'DELEGATION'
            ? []
            : [{ key: 'resource', label: '机构资源', capability_type: type }],
        delegation_slots:
          type === 'DELEGATION' ? [{ key: 'delegate', label: '委派', description: '' }] : []
      })
      const wrapper = await open()
      await button(wrapper, '配置机构绑定').trigger('click')
      await flushPromises()
      expect(mocks.resourceDefinitions).toHaveBeenCalledTimes(type === 'DELEGATION' ? 0 : 1)
      expect(mocks.toolDeployments).toHaveBeenCalledTimes(type === 'TOOL' ? 1 : 0)
      expect(mocks.workflowInstances).toHaveBeenCalledTimes(type === 'WORKFLOW' ? 1 : 0)
      expect(mocks.agents).toHaveBeenCalledTimes(type === 'DELEGATION' ? 1 : 0)
      if (type === 'DELEGATION') expect(mocks.resourceVersions).not.toHaveBeenCalled()
      else
        expect(mocks.resourceVersions).toHaveBeenCalledExactlyOnceWith(
          type === 'TOOL' ? '102' : '103',
          'PUBLISHED'
        )
    }
  )

  it('所需目录失败阻断保存但保留参数草稿，重试只读取当前槽需要的目录', async () => {
    mocks.detail.mockResolvedValue({ ...deployment, parameters: { note: '原内容' } })
    mocks.resourceDefinitions.mockRejectedValueOnce(new Error('知识能力目录暂不可用'))
    mocks.bindings.mockResolvedValue(deployment)
    const wrapper = await open()
    await button(wrapper, '配置机构绑定').trigger('click')
    await flushPromises()
    const input = new DOMWrapper(document.body).get('input[aria-label="参数值"]')
    await input.setValue('保留参数草稿')
    await button(wrapper, '保存机构实例').trigger('click')
    await flushPromises()
    expect(mocks.bindings).not.toHaveBeenCalled()
    expect(document.body.textContent).toContain('知识能力目录暂不可用')
    await button(wrapper, '重试机构资源目录').trigger('click')
    await flushPromises()
    expect((input.element as HTMLInputElement).value).toBe('保留参数草稿')
    await choose(wrapper, '机构知识', '101')
    await button(wrapper, '保存机构实例').trigger('click')
    await flushPromises()
    expect(mocks.bindings.mock.calls[0]?.[0]).toMatchObject({
      expected_revision: 7,
      parameters: { note: '保留参数草稿' }
    })
    expect(mocks.resourceDefinitions).toHaveBeenCalledTimes(2)
    for (const read of [
      mocks.resourceVersions,
      mocks.toolDeployments,
      mocks.workflowInstances,
      mocks.agents
    ])
      expect(read).not.toHaveBeenCalled()
  })

  it('新建先读取模板槽再加载目录，切换模板后旧目录迟到不覆盖新槽', async () => {
    let resolveOld!: (value: unknown[]) => void
    const old = new Promise<unknown[]>((resolve) => {
      resolveOld = resolve
    })
    mocks.resourceDefinitions.mockReturnValueOnce(old)
    const toolTemplate = {
      ...template,
      id: 'tool-template',
      definition: {
        resource_slots: [
          { key: 'tool', label: '机构工具', description: '', capability_type: 'TOOL' }
        ],
        delegation_slots: []
      }
    }
    mocks.definitions.mockResolvedValue({ items: [template, toolTemplate], total: 2 })
    const wrapper = await open()
    await button(wrapper, '部署公共智能体').trigger('click')
    await flushPromises()
    for (const read of [
      mocks.resourceDefinitions,
      mocks.resourceVersions,
      mocks.toolDeployments,
      mocks.workflowInstances,
      mocks.agents
    ])
      expect(read).not.toHaveBeenCalled()
    await choose(wrapper, '公共智能体模板', 'tool-template')
    expect(mocks.resourceDefinitions).toHaveBeenCalledOnce()
    expect(mocks.toolDeployments).toHaveBeenCalledOnce()
    await choose(wrapper, '公共智能体模板', 't1')
    expect(field(wrapper, '机构知识').exists()).toBe(true)
    expect(mocks.resourceDefinitions).toHaveBeenCalledTimes(2)
    resolveOld([{ id: '102', name: '旧工具', capabilityType: 'TOOL', defaultVersionId: '202' }])
    await flushPromises()
    const picker = field(wrapper, '机构知识').getComponent(ElSelect)
    expect(
      picker.findAllComponents({ name: 'ElOption' }).map((option) => option.props('value'))
    ).toEqual(['101'])
    expect(mocks.resourceVersions).not.toHaveBeenCalled()
    expect(document.body.textContent).not.toContain('机构资源目录读取失败')
    expect(mocks.create).not.toHaveBeenCalled()
  })

  it('工作台固定本机构隐藏重复选择且不启用跨机构管理路径', async () => {
    const wrapper = await open('/admin/agent-deployments?tenant_id=other', {
      tenantId: '7',
      embedded: true
    })
    expect(mocks.list.mock.calls[0]?.[1]).toBeUndefined()
    expect(wrapper.find('[aria-label="管理机构"]').exists()).toBe(false)
    expect(mocks.targetOptions).not.toHaveBeenCalled()
  })
  it('工作台下级目标覆盖地址中的旧选择并使用原目标管理API', async () => {
    const wrapper = await open('/admin/agent-deployments?tenant_id=other', {
      tenantId: '11',
      embedded: true
    })
    expect(mocks.list.mock.calls[0]?.[1]).toBe('11')
    expect(wrapper.text()).toContain('资源绑定由目标机构维护')
    expect(wrapper.find('[aria-label="管理机构"]').exists()).toBe(false)
  })
  it('实例依据切换需要显式选择且使用CAS；未知后保留选择并核对同一命令键', async () => {
    mocks.authorization.mockRejectedValueOnce(Error('网络中断')).mockResolvedValueOnce(deployment)
    const wrapper = await open()
    await button(wrapper, '授权链与切换').trigger('click')
    await flushPromises()
    expect(field(wrapper, '新的同源授权依据').getComponent(ElSelect).props('modelValue')).toBe('')
    await choose(wrapper, '新的同源授权依据', 'grant-1')
    await button(wrapper, '切换授权依据').trigger('click')
    await flushPromises()
    expect(
      Array.from(document.body.querySelectorAll('.el-message__content')).at(-1)?.textContent
    ).toContain('网络中断')
    expect(field(wrapper, '新的同源授权依据').getComponent(ElSelect).props('modelValue')).toBe(
      'grant-1'
    )
    await button(wrapper, '核对原请求').trigger('click')
    await flushPromises()
    expect(mocks.authorization.mock.calls[0]?.[0]).toEqual({
      deployment_id: 'deployment-1',
      expected_revision: 7,
      grant_id: 'grant-1'
    })
    expect(mocks.authorization.mock.calls[1]?.[1]).toBe(mocks.authorization.mock.calls[0]?.[1])
  })
  it('代理下级目录与切换写入均携带明确目标且不提供总部资源绑定', async () => {
    mocks.authorization.mockResolvedValue(deployment)
    const wrapper = await open('/admin/agent-deployments?tenant_id=child-9')
    expect(mocks.list).toHaveBeenCalledWith(expect.any(Object), 'child-9')
    expect(wrapper.text()).not.toContain('配置机构绑定')
    await button(wrapper, '授权链与切换').trigger('click')
    await flushPromises()
    expect(mocks.detail).toHaveBeenCalledWith('deployment-1', expect.any(Object), 'child-9')
    await choose(wrapper, '新的同源授权依据', 'grant-1')
    await button(wrapper, '切换授权依据').trigger('click')
    await flushPromises()
    expect(mocks.authorization).toHaveBeenCalledWith(
      expect.objectContaining({ tenant_id: 'child-9' }),
      expect.any(String)
    )
  })
  it('确认期间失去切换权限后不发出旧页面写入', async () => {
    const wrapper = await open()
    await button(wrapper, '授权链与切换').trigger('click')
    await flushPromises()
    await choose(wrapper, '新的同源授权依据', 'grant-1')
    mocks.confirm.mockImplementationOnce(async () => {
      mocks.auth.user.permissions = []
      return true
    })
    await button(wrapper, '切换授权依据').trigger('click')
    await flushPromises()
    expect(mocks.authorization).not.toHaveBeenCalled()
  })
  it('读回大ID能力绑定后保存仍保留两项精确字符串身份', async () => {
    const bindings = [
      {
        slot_key: 'knowledge',
        mount: {
          capability_type: 'KNOWLEDGE_SOURCE',
          capability_definition_id: '9223372036854775807',
          capability_version_id: '9223372036854775806',
          knowledge_config: { mode: 'FOLLOW_ACTIVE', pinned_revision_id: null },
          priority: 7,
          status: 'ACTIVE'
        }
      }
    ]
    mocks.detail.mockResolvedValue({ ...deployment, resource_bindings: bindings })
    mocks.bindings.mockResolvedValue(deployment)
    const wrapper = await open()
    await button(wrapper, '配置机构绑定').trigger('click')
    await flushPromises()
    await button(wrapper, '保存机构实例').trigger('click')
    await flushPromises()
    expect(mocks.bindings.mock.calls[0]?.[0]).toEqual(
      expect.objectContaining({ resource_bindings: bindings })
    )
  })
  it('编辑其他参数不丢失工具和工作流完整挂载版本、顺序和停用状态', async () => {
    const bindings = [
      {
        slot_key: 'tool',
        mount: {
          capability_type: 'TOOL',
          capability_definition_id: '102',
          capability_version_id: '202',
          tool_config: { tool_deployment_id: 'tool-a', tool_deployment_revision: 4 },
          priority: 8,
          status: 'DISABLED'
        }
      },
      {
        slot_key: 'workflow',
        mount: {
          capability_type: 'WORKFLOW',
          capability_definition_id: '103',
          capability_version_id: '203',
          workflow_config: { workflow_instance_id: 'workflow-a', workflow_instance_revision: 7 },
          priority: 9,
          status: 'ACTIVE'
        }
      }
    ]
    mocks.detail.mockResolvedValue({
      ...deployment,
      resource_slots: [
        { key: 'tool', label: '工具', capability_type: 'TOOL' },
        { key: 'workflow', label: '工作流', capability_type: 'WORKFLOW' }
      ],
      resource_bindings: bindings
    })
    mocks.bindings.mockResolvedValue(deployment)
    const wrapper = await open()
    await button(wrapper, '配置机构绑定').trigger('click')
    await flushPromises()
    await button(wrapper, '保存机构实例').trigger('click')
    await flushPromises()
    expect(mocks.bindings.mock.calls[0]?.[0]).toEqual(
      expect.objectContaining({
        deployment_id: 'deployment-1',
        expected_revision: 7,
        resource_bindings: bindings
      })
    )
  })
  it('只读角色不显示写操作，仍展示跟随版本和就绪原因', async () => {
    mocks.auth.user.permissions = ['tenant.agent_deployments.read']
    const wrapper = await open()
    expect(document.body.textContent ?? '').toContain('请绑定机构知识')
    expect(document.body.textContent ?? '').toContain('跟随最新发布')
    expect(wrapper.findAll('button').some((item) => item.text() === '部署公共智能体')).toBe(false)
    expect(wrapper.findAll('button').some((item) => item.text() === '配置机构绑定')).toBe(false)
  })
  it('创建提交直接授权/外部身份/真实资源引用，防重并保留ready=false语义', async () => {
    const wrapper = await open()
    await begin(wrapper)
    await choose(wrapper, '机构知识', '101')
    let complete!: (value: unknown) => void
    mocks.create.mockImplementation(
      () =>
        new Promise((resolve) => {
          complete = resolve
        })
    )
    await button(wrapper, '保存机构实例').trigger('click')
    await button(wrapper, '保存机构实例').trigger('click')
    await flushPromises()
    expect(mocks.create).toHaveBeenCalledTimes(1)
    expect(mocks.create.mock.calls[0]?.[0]).toMatchObject({
      integration_id: null,
      grant_id: 'grant-1',
      external_agent_ref: 'external-agent-2',
      definition_id: 't1',
      resource_bindings: [
        {
          slot_key: 'knowledge',
          mount: {
            capability_type: 'KNOWLEDGE_SOURCE',
            capability_definition_id: '101',
            knowledge_config: { mode: 'FOLLOW_ACTIVE', pinned_revision_id: null }
          }
        }
      ]
    })
    complete({ ...deployment, ready: false })
    await flushPromises()
    expect(mocks.list).toHaveBeenCalledTimes(2)
    expect(document.body.textContent ?? '').toContain('待完善')
  })
  it('编辑绑定使用加载到的CAS修订，冲突时保留本地选择', async () => {
    const wrapper = await open()
    await button(wrapper, '配置机构绑定').trigger('click')
    await flushPromises()
    await choose(wrapper, '机构知识', '101')
    mocks.bindings.mockRejectedValue(
      Object.assign(new Error('绑定已被其他管理员修改，请重新读取'), { status: 409 })
    )
    await button(wrapper, '保存机构实例').trigger('click')
    await flushPromises()
    expect(mocks.bindings.mock.calls[0]?.[0]).toEqual(
      expect.objectContaining({ deployment_id: 'deployment-1', expected_revision: 7 })
    )
    expect(document.body.textContent ?? '').toContain('绑定已被其他管理员修改')
    expect(field(wrapper, '机构知识').getComponent(ElSelect).props('modelValue')).toBe('101')
    mocks.confirm.mockResolvedValueOnce(false)
    await button(wrapper, '重新读取机构绑定').trigger('click')
    await flushPromises()
    expect(mocks.detail).toHaveBeenCalledTimes(1)
    expect(field(wrapper, '机构知识').getComponent(ElSelect).props('modelValue')).toBe('101')
    mocks.detail.mockResolvedValue({ ...deployment, deployment_revision: 8 })
    await button(wrapper, '重新读取机构绑定').trigger('click')
    await flushPromises()
    expect(field(wrapper, '机构知识').getComponent(ElSelect).props('modelValue')).toBeUndefined()
    mocks.bindings.mockResolvedValue({ ...deployment, deployment_revision: 9 })
    await button(wrapper, '保存机构实例').trigger('click')
    await flushPromises()
    expect(mocks.bindings.mock.lastCall?.[0]).toMatchObject({ expected_revision: 8 })
  })
  it('模板目录读取失败可重试并恢复槽位；未读取到模板不能提交', async () => {
    mocks.definitions
      .mockRejectedValueOnce(new Error('模板目录暂不可用'))
      .mockResolvedValue({ items: [template], total: 1 })
    const wrapper = await open()
    await button(wrapper, '部署公共智能体').trigger('click')
    await flushPromises()
    expect(document.body.textContent ?? '').toContain('模板目录暂不可用')
    await button(wrapper, '保存机构实例').trigger('click')
    await flushPromises()
    expect(mocks.create).not.toHaveBeenCalled()
    await button(wrapper, '重试公共模板目录').trigger('click')
    await flushPromises()
    expect(field(wrapper, '公共智能体模板').getComponent(ElSelect).props('modelValue')).toBe('t1')
    expect(field(wrapper, '机构知识').exists()).toBe(true)
  })
  it('唯一授权定义自动选中并复用目录，无编辑时直接关闭', async () => {
    const wrapper = await open()
    await button(wrapper, '部署公共智能体').trigger('click')
    await flushPromises()
    expect(field(wrapper, '公共智能体模板').getComponent(ElSelect).props('modelValue')).toBe('t1')
    expect(field(wrapper, '机构知识').exists()).toBe(true)
    expect(mocks.definitions).toHaveBeenCalledTimes(1)
    expect(mocks.create).not.toHaveBeenCalled()
    const leave = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(leave)
    expect(leave.defaultPrevented).toBe(false)
    await button(wrapper, '取消').trigger('click')
    await flushPromises()
    expect(mocks.confirm).not.toHaveBeenCalled()
  })
  it.each(['name', 'binding'])('唯一授权定义自动选中后修改%s仍保护输入', async (kind) => {
    const wrapper = await open()
    await button(wrapper, '部署公共智能体').trigger('click')
    await flushPromises()
    if (kind === 'name')
      await field(wrapper, '实例名称').getComponent(ElInput).get('input').setValue('用户编辑的名称')
    else await choose(wrapper, '机构知识', '101')
    await flushPromises()
    const leave = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(leave)
    expect(leave.defaultPrevented).toBe(true)
    mocks.confirm.mockResolvedValueOnce(false)
    await button(wrapper, '取消').trigger('click')
    await flushPromises()
    expect(mocks.confirm).toHaveBeenCalledOnce()
    expect(field(wrapper, kind === 'name' ? '实例名称' : '机构知识').exists()).toBe(true)
  })
  it('目录加载期间填写的名称不会被自动选择覆盖或当作默认值', async () => {
    let resolve!: (value: unknown) => void
    mocks.definitions.mockReturnValueOnce(
      new Promise((done) => {
        resolve = done
      })
    )
    const wrapper = await open()
    await button(wrapper, '部署公共智能体').trigger('click')
    await flushPromises()
    await field(wrapper, '实例名称').getComponent(ElInput).get('input').setValue('提前填写的名称')
    resolve({ items: [template], total: 1 })
    await flushPromises()
    expect(field(wrapper, '实例名称').getComponent(ElInput).props('modelValue')).toBe(
      '提前填写的名称'
    )
    expect(field(wrapper, '公共智能体模板').getComponent(ElSelect).props('modelValue')).toBe('t1')
    const leave = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(leave)
    expect(leave.defaultPrevented).toBe(true)
  })
  it('存在多个授权定义时由用户选择，并复用已读取的目录', async () => {
    mocks.definitions.mockResolvedValue({
      items: [template, { ...template, id: 't2', name: '另一模板' }],
      total: 2
    })
    const wrapper = await open()
    await button(wrapper, '部署公共智能体').trigger('click')
    await flushPromises()
    expect(field(wrapper, '公共智能体模板').getComponent(ElSelect).props('modelValue')).toBe('')
    await choose(wrapper, '公共智能体模板', 't2')
    expect(field(wrapper, '机构知识').exists()).toBe(true)
    expect(mocks.definitions).toHaveBeenCalledTimes(1)
    expect(mocks.create).not.toHaveBeenCalled()
  })
})
