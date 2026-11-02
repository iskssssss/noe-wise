import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { ElButton, ElFormItem, ElInput, ElSelect } from 'element-plus'
import { HttpClientError } from '@skillforge/shared/utils/http'
import { clearFeedback } from '@skillforge/shared/feedback'
import WorkflowDetailPage from './WorkflowDetailPage.vue'
vi.mock('../../stores/auth', () => ({
  useAuthStore: () => ({
    user: { tenant_id: '7', sub: '8', permissions: ['foundation.workflows.read'] }
  })
}))

const mocks = vi.hoisted(() => ({
  session: 'test-session',
  get: vi.fn(),
  snapshotGet: vi.fn(),
  history: vi.fn(),
  publicationDetail: vi.fn(),
  definitions: vi.fn(),
  versions: vi.fn(),
  deployments: vi.fn(),
  listRevisions: vi.fn(),
  install: vi.fn(),
  installReceipt: vi.fn(),
  instances: vi.fn()
}))
vi.mock('../../api', () => ({
  getSessionId: () => mocks.session,
  api: {
    workflowDrafts: {
      get: mocks.get,
      history: mocks.history,
      publicationDetail: mocks.publicationDetail,
      install: mocks.install,
      installWithReceipt: async (requestId: string, id: string, ...args: unknown[]) => {
        const result = await mocks.install(id, ...args)
        return {
          request_id: requestId,
          workflow_id: id,
          instance_id: result.instanceId,
          installation_revision: result.installationRevision
        }
      },
      installReceipt: mocks.installReceipt,
      instances: mocks.instances,
      snapshot: { get: mocks.snapshotGet }
    },
    capabilities: {
      definitions: mocks.definitions,
      versions: mocks.versions,
      deployments: mocks.deployments
    },
    knowledge: { listRevisions: mocks.listRevisions }
  }
}))
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { workflowId: 'flow' } }),
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  onBeforeRouteLeave: vi.fn(),
  onBeforeRouteUpdate: vi.fn()
}))
vi.mock('../../components/builder/NodeProperties.vue', () => ({ default: { template: '<div />' } }))
vi.mock('../../components/builder/WorkflowTestPanel.vue', () => ({
  default: { template: '<div />' }
}))
vi.mock('@skillforge/shared/components/CodeEditor.vue', () => ({
  default: { template: '<div />' }
}))

const definition = {
  version: '1.0',
  entry: 'start',
  nodes: [
    { id: 'start', type: 'start' },
    { id: 'end', type: 'end' }
  ],
  edges: [{ from: 'start', to: 'end' }],
  meta: { name: 'flow', version: '1.0.0' }
}
let wrapper: ReturnType<typeof mount>
function published(meta: Record<string, unknown>) {
  mocks.history.mockResolvedValue([
    { definitionVersionId: 44, definitionVersion: 1, current: true }
  ])
  mocks.publicationDetail.mockResolvedValue({
    workflowId: 'flow',
    definitionVersionId: 44,
    definitionVersion: 1,
    current: true,
    definition: { ...definition, meta }
  })
}
async function openInstall() {
  wrapper = mount(WorkflowDetailPage, { attachTo: document.body })
  await flushPromises()
  await wrapper
    .findAllComponents(ElButton)
    .find((button) => button.text() === '安装')!
    .trigger('click')
  await flushPromises()
}
beforeEach(() => {
  vi.resetAllMocks()
  mocks.session = 'test-session'
  sessionStorage.clear()
  clearFeedback()
  window.history.replaceState(null, '')
  mocks.get.mockResolvedValue({
    workflowId: 'flow',
    revision: 1,
    status: 'published',
    activeDefinitionVersionId: 44,
    problems: [],
    definition
  })
  mocks.snapshotGet.mockResolvedValue({ draftRevision: 1, layout: {}, viewport: {} })
  mocks.install.mockResolvedValue({ instanceId: 'instance-1', installationRevision: '2' })
  published({})
})
afterEach(() => {
  wrapper?.unmount()
  clearFeedback()
  document.body.innerHTML = ''
})

describe('工作流安装只读取声明的依赖', () => {
  it('工具版本已选中后接入唯一实例，刷新即可直接安装', async () => {
    published({
      capability_requirements: [
        {
          slot: 'tool',
          label: '查询工具',
          type: 'tool',
          capability_id: 'lookup',
          version_constraint: '*'
        }
      ]
    })
    mocks.definitions.mockResolvedValue([
      { id: '10', capabilityType: 'TOOL', capabilityKey: 'lookup', defaultVersionId: '101' }
    ])
    mocks.versions.mockResolvedValue([
      {
        id: '101',
        definitionId: '10',
        capabilityType: 'TOOL',
        versionKey: '1',
        name: '查询',
        descriptor: {}
      }
    ])
    mocks.deployments
      .mockResolvedValueOnce([])
      .mockResolvedValue([
        { deploymentKey: 'only', toolVersionId: '101', providerType: 'MCP', revision: 3 }
      ])
    await openInstall()
    expect(
      wrapper
        .findAllComponents(ElFormItem)
        .find((item) => item.props('label') === '查询工具')!
        .getComponent(ElSelect)
        .props('modelValue')
    ).toBe('101')
    await wrapper
      .findAllComponents(ElButton)
      .find((item) => item.text() === '刷新依赖选项')!
      .trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('已选择唯一可用实例')
    await wrapper
      .findAllComponents(ElButton)
      .filter((item) => item.text() === '安装')
      .at(-1)!
      .trigger('click')
    await flushPromises()
    expect(mocks.install).toHaveBeenCalledWith(
      'flow',
      expect.objectContaining({
        capabilities: [expect.objectContaining({ tool_deployment_id: 'only' })]
      }),
      expect.anything(),
      expect.anything()
    )
  })
  it('安装时刷新新接入的依赖并保留填写的配置，失败后可重试', async () => {
    published({
      capability_requirements: [
        {
          slot: 'tool',
          label: '查询工具',
          type: 'tool',
          capability_id: 'lookup',
          version_constraint: '*'
        }
      ],
      config_schema: [{ key: 'note', type: 'string', label: '说明' }]
    })
    mocks.definitions
      .mockResolvedValueOnce([])
      .mockRejectedValueOnce(new Error('目录读取失败'))
      .mockResolvedValue([
        { id: '10', capabilityType: 'TOOL', capabilityKey: 'lookup', defaultVersionId: '101' }
      ])
    mocks.versions.mockResolvedValue([
      {
        id: '101',
        definitionId: '10',
        capabilityType: 'TOOL',
        versionKey: '1.0.0',
        name: '查询',
        descriptor: {}
      }
    ])
    mocks.deployments.mockResolvedValue([
      { deploymentKey: 'only', toolVersionId: '101', providerType: 'MCP', revision: 3 }
    ])
    await openInstall()
    const note = wrapper
      .findAllComponents(ElFormItem)
      .find((item) => item.props('label') === '说明（可选）')!
      .getComponent(ElInput)
    note.vm.$emit('update:modelValue', '保留内容')
    await flushPromises()
    const refresh = wrapper
      .findAllComponents(ElButton)
      .find((button) => button.text() === '刷新依赖选项')!
    await refresh.trigger('click')
    await flushPromises()
    expect(note.props('modelValue')).toBe('保留内容')
    await refresh.trigger('click')
    await flushPromises()
    expect(note.props('modelValue')).toBe('保留内容')
    expect(
      wrapper
        .findAllComponents(ElFormItem)
        .find((item) => item.props('label') === '查询工具')!
        .getComponent(ElSelect)
        .props('modelValue')
    ).toBe('101')
    await wrapper
      .findAllComponents(ElButton)
      .filter((item) => item.text() === '安装')
      .at(-1)!
      .trigger('click')
    await flushPromises()
    expect(mocks.install).toHaveBeenCalledWith(
      'flow',
      expect.objectContaining({
        capabilities: [expect.objectContaining({ tool_deployment_id: 'only' })]
      }),
      expect.objectContaining({ note: '保留内容' }),
      expect.anything()
    )
  })
  it('必填工具唯一版本和实例自动绑定，可选槽保持未选择', async () => {
    published({
      capability_requirements: [
        {
          slot: 'required',
          label: '必填工具',
          type: 'tool',
          capability_id: 'lookup',
          version_constraint: '*'
        },
        {
          slot: 'optional',
          label: '可选工具',
          type: 'tool',
          capability_id: 'lookup',
          version_constraint: '*',
          required: false
        }
      ]
    })
    mocks.definitions.mockResolvedValue([
      { id: '10', capabilityType: 'TOOL', capabilityKey: 'lookup', defaultVersionId: '101' }
    ])
    mocks.versions.mockResolvedValue([
      {
        id: '101',
        definitionId: '10',
        capabilityType: 'TOOL',
        versionKey: '1.0.0',
        name: '查询',
        descriptor: {}
      }
    ])
    mocks.deployments.mockResolvedValue([
      { deploymentKey: 'only', toolVersionId: '101', providerType: 'MCP', revision: 3 }
    ])
    await openInstall()
    const required = wrapper
      .findAllComponents(ElFormItem)
      .find((item) => item.props('label') === '必填工具')!
    const optional = wrapper
      .findAllComponents(ElFormItem)
      .find((item) => item.props('label') === '可选工具')!
    expect(required.getComponent(ElSelect).props('modelValue')).toBe('101')
    expect(required.findAllComponents(ElSelect)).toHaveLength(1)
    expect(optional.getComponent(ElSelect).props('modelValue')).toBeUndefined()
    await wrapper
      .findAllComponents(ElButton)
      .filter((item) => item.text() === '安装')
      .at(-1)!
      .trigger('click')
    await flushPromises()
    expect(mocks.install.mock.calls[0]![1]).toEqual({
      capabilities: [
        {
          slot: 'required',
          capability: { type: 'TOOL', capabilityId: '10', versionId: '101' },
          tool_deployment_id: 'only',
          tool_deployment_revision: 3
        }
      ],
      workflows: [],
      agents: []
    })
  })
  it('工具有多个实例时保留实例选择并阻止未选择的安装', async () => {
    published({
      capability_requirements: [
        { slot: 's', label: '工具', type: 'tool', capability_id: 'lookup', version_constraint: '*' }
      ]
    })
    mocks.definitions.mockResolvedValue([
      { id: '10', capabilityType: 'TOOL', capabilityKey: 'lookup', defaultVersionId: '101' }
    ])
    mocks.versions.mockResolvedValue([
      {
        id: '101',
        definitionId: '10',
        capabilityType: 'TOOL',
        versionKey: '1.0.0',
        name: '查询',
        descriptor: {}
      }
    ])
    mocks.deployments.mockResolvedValue(
      ['a', 'b'].map((key) => ({
        deploymentKey: key,
        toolVersionId: '101',
        providerType: 'MCP',
        revision: 1
      }))
    )
    await openInstall()
    const field = wrapper
      .findAllComponents(ElFormItem)
      .find((item) => item.props('label') === '工具')!
    expect(field.findAllComponents(ElSelect)).toHaveLength(2)
    await wrapper
      .findAllComponents(ElButton)
      .filter((item) => item.text() === '安装')
      .at(-1)!
      .trigger('click')
    await flushPromises()
    expect(mocks.install).not.toHaveBeenCalled()
  })
  it('安装成功仅提示一次，关闭顶部消息后仍保留精确安装回执', async () => {
    await openInstall()
    await wrapper
      .findAllComponents(ElButton)
      .filter((button) => button.text() === '安装')
      .at(-1)!
      .trigger('click')
    await flushPromises()
    expect(mocks.install).toHaveBeenCalledOnce()
    expect(document.querySelectorAll('.el-message--success')).toHaveLength(1)
    expect(document.querySelector('.el-message--success')?.textContent).toContain('工作流已安装')
    expect(wrapper.find('p.admin-field-help[role="status"]').text()).toBe(
      '已安装实例 instance-1 · 安装修订 2'
    )
    clearFeedback()
    await flushPromises()
    expect(document.querySelectorAll('.el-message')).toHaveLength(0)
    expect(wrapper.find('p.admin-field-help[role="status"]').text()).toBe(
      '已安装实例 instance-1 · 安装修订 2'
    )
    expect(
      wrapper
        .findAllComponents(ElButton)
        .some((button) => button.text() === '前往智能体挂载此工作流')
    ).toBe(true)
  })

  it.each(['string', 'textarea'])(
    '正式安装保留主动清空的可选%s，而未修改字段保留默认值',
    async (type) => {
      published({
        config_schema: [
          { key: 'cleared', type, label: '清空内容', default: '原默认内容' },
          { key: 'untouched', type, label: '保留内容', default: '保留默认内容' }
        ]
      })
      await openInstall()
      const input = wrapper
        .findAllComponents(ElFormItem)
        .find((field) => field.props('label') === '清空内容（可选）')!
        .findComponent(ElInput)
      expect(input.props('modelValue')).toBe('原默认内容')
      await input.find(type === 'textarea' ? 'textarea' : 'input').setValue('')
      await wrapper
        .findAllComponents(ElButton)
        .filter((button) => button.text() === '安装')
        .at(-1)!
        .trigger('click')
      await flushPromises()
      expect(mocks.install).toHaveBeenCalledExactlyOnceWith(
        'flow',
        { capabilities: [], workflows: [], agents: [] },
        { cleared: '', untouched: '保留默认内容' },
        ''
      )
    }
  )

  it.each(['', '   '])('正式安装的必填文本清空或空白时阻断提交：%j', async (value) => {
    published({
      config_schema: [
        {
          key: 'required',
          type: 'string',
          label: '必填内容',
          required: true,
          default: '原默认内容'
        }
      ]
    })
    await openInstall()
    const input = wrapper
      .findAllComponents(ElFormItem)
      .find((field) => field.props('label') === '必填内容（必填）')!
      .findComponent(ElInput)
    await input.find('input').setValue(value)
    await wrapper
      .findAllComponents(ElButton)
      .filter((button) => button.text() === '安装')
      .at(-1)!
      .trigger('click')
    await flushPromises()
    expect(mocks.install).not.toHaveBeenCalled()
  })

  it('安装超时后保留未决状态，只读核对不会重新安装或解锁重复提交', async () => {
    published({ config_schema: [{ key: 'note', type: 'string', label: '说明' }] })
    mocks.install.mockRejectedValueOnce(
      new HttpClientError({ status: 408, userMessage: '安装请求超时' })
    )
    mocks.installReceipt.mockResolvedValue(undefined)
    await openInstall()
    const input = wrapper
      .findAllComponents(ElFormItem)
      .find((field) => field.props('label') === '说明（可选）')!
      .findComponent(ElInput)
    input.vm.$emit('update:modelValue', '超时后保留草稿')
    await flushPromises()
    const install = wrapper
      .findAllComponents(ElButton)
      .filter((button) => button.text() === '安装')
      .at(-1)!
    await install.trigger('click')
    await flushPromises()
    expect(input.props('modelValue')).toBe('超时后保留草稿')
    expect(install.props('disabled')).toBe(true)
    expect(document.body.textContent).toContain('安装结果尚未确认，已停止重复提交')
    await wrapper
      .findAllComponents(ElButton)
      .find((button) => button.text() === '核对原安装')!
      .trigger('click')
    await flushPromises()
    expect(mocks.installReceipt).toHaveBeenCalledTimes(1)
    expect(mocks.install).toHaveBeenCalledTimes(1)
    expect(install.props('disabled')).toBe(true)
    expect(document.body.textContent).toContain('尚未查到匹配的原安装回执，结果仍待核对')
    await install.trigger('click')
    await flushPromises()
    expect(mocks.install).toHaveBeenCalledTimes(1)
  })

  it('重新进入时自动核对原安装回执，不重复安装', async () => {
    published({})
    mocks.install.mockRejectedValueOnce(new HttpClientError({ status: 0, userMessage: '连接中断' }))
    await openInstall()
    await wrapper
      .findAllComponents(ElButton)
      .filter((button) => button.text() === '安装')
      .at(-1)!
      .trigger('click')
    await flushPromises()
    const key = `noewise:workflow-install:${JSON.stringify([JSON.stringify(['7', '8']), 'flow'])}`
    const requestId = sessionStorage.getItem(key)!
    expect(requestId).toBeTruthy()
    wrapper.unmount()
    mocks.session = 'new-login-session'
    mocks.installReceipt.mockResolvedValue({
      request_id: requestId,
      workflow_id: 'flow',
      instance_id: 'flow_7',
      installation_revision: '1'
    })
    wrapper = mount(WorkflowDetailPage, { attachTo: document.body })
    await flushPromises()
    expect(mocks.installReceipt).toHaveBeenCalledExactlyOnceWith('flow', requestId)
    expect(document.body.textContent).toContain('已安装实例 flow_7')
    expect(sessionStorage.getItem(key)).toBeNull()
    expect(mocks.install).toHaveBeenCalledTimes(1)
  })

  it('无能力槽时无需能力目录或部署权限，并保留明确失败后的安装配置供重试', async () => {
    published({ config_schema: [{ key: 'note', type: 'string', label: '说明' }] })
    mocks.definitions.mockRejectedValue(new Error('无权读取能力目录'))
    mocks.deployments.mockRejectedValue(new Error('无权读取工具部署'))
    mocks.install
      .mockRejectedValueOnce(
        new HttpClientError({
          status: 422,
          code: 'WORKFLOW_INSTALL_REJECTED',
          userMessage: '配置未被接受'
        })
      )
      .mockResolvedValueOnce({ instanceId: 'instance-1', installationRevision: '2' })
    await openInstall()
    expect(mocks.publicationDetail).toHaveBeenCalledExactlyOnceWith('flow', 44)
    expect(mocks.definitions).not.toHaveBeenCalled()
    expect(mocks.versions).not.toHaveBeenCalled()
    expect(mocks.deployments).not.toHaveBeenCalled()
    const input = wrapper
      .findAllComponents(ElFormItem)
      .find((field) => field.props('label') === '说明（可选）')!
      .findComponent(ElInput)
    input.vm.$emit('update:modelValue', '保留安装草稿')
    await flushPromises()
    const install = wrapper
      .findAllComponents(ElButton)
      .filter((button) => button.text() === '安装')
      .at(-1)!
    await install.trigger('click')
    await flushPromises()
    expect(input.props('modelValue')).toBe('保留安装草稿')
    expect(install.props('disabled')).toBe(false)
    await install.trigger('click')
    await flushPromises()
    expect(mocks.install).toHaveBeenCalledTimes(2)
    expect(mocks.install).toHaveBeenLastCalledWith(
      'flow',
      { capabilities: [], workflows: [], agents: [] },
      { note: '保留安装草稿' },
      ''
    )
  })

  it('仅查询声明且有当前发布版本的能力，不被无关能力版本读取失败阻断', async () => {
    published({
      capability_requirements: [
        {
          slot: 'lookup',
          type: 'tool',
          capability_id: 'lookup_key',
          version_constraint: '1.x',
          label: '查询'
        }
      ]
    })
    mocks.definitions.mockResolvedValue([
      { id: '10', capabilityType: 'TOOL', capabilityKey: 'lookup_key', defaultVersionId: '101' },
      { id: '11', capabilityType: 'TOOL', capabilityKey: 'unrelated', defaultVersionId: '111' },
      {
        id: '12',
        capabilityType: 'KNOWLEDGE_SOURCE',
        capabilityKey: 'lookup_key',
        defaultVersionId: '121'
      },
      { id: '13', capabilityType: 'TOOL', capabilityKey: 'lookup_key', defaultVersionId: null }
    ])
    mocks.versions.mockImplementation((id: string) =>
      id === '10'
        ? Promise.resolve([
            {
              id: '101',
              definitionId: '10',
              capabilityType: 'TOOL',
              versionKey: '1.2.0',
              name: '查询',
              descriptor: {}
            }
          ])
        : Promise.reject(new Error('无关能力不可读取'))
    )
    mocks.deployments.mockResolvedValue([])
    await openInstall()
    expect(mocks.versions).toHaveBeenCalledExactlyOnceWith('10', 'PUBLISHED')
    expect(mocks.deployments).toHaveBeenCalledExactlyOnceWith('ACTIVE')
    const picker = wrapper
      .findAllComponents(ElSelect)
      .find((select) => select.props('placeholder') === '选择已发布能力版本')!
    expect(picker.exists()).toBe(true)
    picker.vm.$emit('update:modelValue', '101')
    await flushPromises()
    expect(picker.props('modelValue')).toBe('101')
  })

  it('仅知识槽读取对应发布修订，跳过工具部署目录', async () => {
    published({
      capability_requirements: [
        {
          slot: 'docs',
          type: 'knowledge_source',
          capability_id: 'docs_key',
          version_constraint: '*',
          label: '文档'
        }
      ]
    })
    mocks.definitions.mockResolvedValue([
      {
        id: '20',
        capabilityType: 'KNOWLEDGE_SOURCE',
        capabilityKey: 'docs_key',
        defaultVersionId: '201'
      }
    ])
    mocks.versions.mockResolvedValue([
      {
        id: '201',
        definitionId: '20',
        capabilityType: 'KNOWLEDGE_SOURCE',
        versionKey: '1.0.0',
        name: '文档',
        descriptor: { source_id: 'source-1' }
      }
    ])
    mocks.listRevisions.mockResolvedValue({
      items: [{ status: 'published', capability_version_id: '201', revision: 3 }]
    })
    mocks.deployments.mockRejectedValue(new Error('无权读取工具部署'))
    await openInstall()
    expect(mocks.deployments).not.toHaveBeenCalled()
    expect(mocks.listRevisions).toHaveBeenCalledExactlyOnceWith('source-1')
    const picker = wrapper
      .findAllComponents(ElSelect)
      .find((select) => select.props('placeholder') === '选择已发布能力版本')!
    picker.vm.$emit('update:modelValue', '201')
    await flushPromises()
    await wrapper
      .findAllComponents(ElButton)
      .filter((button) => button.text() === '安装')
      .at(-1)!
      .trigger('click')
    await flushPromises()
    expect(mocks.install).toHaveBeenCalledWith(
      'flow',
      {
        capabilities: [
          {
            slot: 'docs',
            capability: {
              type: 'KNOWLEDGE_SOURCE',
              capabilityId: '20',
              versionId: '201'
            },
            knowledge_revision: 3
          }
        ],
        workflows: [],
        agents: []
      },
      {},
      ''
    )
  })
})
