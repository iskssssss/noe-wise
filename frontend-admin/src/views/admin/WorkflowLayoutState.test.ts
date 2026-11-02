import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { ElButton, ElSelect, ElFormItem, ElInput } from 'element-plus'
import { HttpClientError } from '@skillforge/shared/utils/http'
import { createPermissionDirective } from '@skillforge/shared/directives/permission'
import { ref } from 'vue'
import { clearFeedback } from '@skillforge/shared/feedback'
import WorkflowDetailPage from './WorkflowDetailPage.vue'
vi.mock('../../stores/auth', () => ({
  useAuthStore: () => ({
    user: { tenant_id: '7', sub: '8', permissions: ['foundation.workflows.read'] }
  })
}))
import WorkflowGovernanceFields from '../../components/builder/WorkflowGovernanceFields.vue'
import WorkflowScriptResources from '../../components/builder/WorkflowScriptResources.vue'
import WorkflowCanvas from '../../components/builder/WorkflowCanvas.vue'
import WorkflowCapabilityDeclarations from '../../components/builder/WorkflowCapabilityDeclarations.vue'
const mocks = vi.hoisted(() => ({
  get: vi.fn(),
  snapshotGet: vi.fn(),
  save: vi.fn(),
  create: vi.fn(),
  replace: vi.fn(),
  snapshotSave: vi.fn(),
  validate: vi.fn(),
  validateNewContent: vi.fn(),
  validateContent: vi.fn(),
  publish: vi.fn(),
  saveAndPublish: vi.fn(),
  saveAndPublishResult: vi.fn(),
  confirm: vi.fn(),
  history: vi.fn(),
  publicationDetail: vi.fn(),
  definitions: vi.fn(),
  versions: vi.fn(),
  deployments: vi.fn(),
  install: vi.fn(),
  listRevisions: vi.fn()
}))
const routeParams = vi.hoisted(() => ({ workflowId: 'flow' }))
vi.mock('../../api', () => ({
  getSessionId: () => 'workflow-test-session',
  api: {
    workflowDrafts: {
      get: mocks.get,
      save: mocks.save,
      create: mocks.create,
      validate: mocks.validate,
      validateNewContent: mocks.validateNewContent,
      validateContent: mocks.validateContent,
      publish: mocks.publish,
      saveAndPublish: mocks.saveAndPublish,
      saveAndPublishResult: mocks.saveAndPublishResult,
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
      snapshot: { get: mocks.snapshotGet, save: mocks.snapshotSave }
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
  useRoute: () => ({ params: routeParams }),
  useRouter: () => ({ replace: mocks.replace, push: vi.fn() }),
  onBeforeRouteLeave: vi.fn(),
  onBeforeRouteUpdate: vi.fn()
}))
vi.mock('../../components/builder/NodeProperties.vue', () => ({
  default: { name: 'NodeProperties', props: ['modelValue', 'toolContracts'], template: '<div />' }
}))
vi.mock('../../components/builder/WorkflowTestPanel.vue', () => ({
  default: {
    name: 'WorkflowTestPanel',
    props: ['modelValue', 'workflowId', 'draftRevision', 'dirty', 'disabled', 'publications'],
    emits: ['update:modelValue', 'save'],
    template: '<section v-if="modelValue" aria-label="工作流测试" />'
  }
}))
vi.mock('@skillforge/shared/components/CodeEditor.vue', () => ({
  default: { template: '<div />' }
}))
vi.mock('@skillforge/shared/feedback', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@skillforge/shared/feedback')>()),
  confirm: mocks.confirm
}))
const value = {
  workflowId: 'flow',
  revision: 1,
  status: 'draft',
  problems: [],
  definition: {
    version: '1.0',
    entry: 'start',
    nodes: [
      { id: 'start', type: 'start' },
      { id: 'end', type: 'end' }
    ],
    edges: [{ from: 'start', to: 'end' }],
    meta: { name: 'flow', version: '1.0.0' }
  }
}
let wrapper: ReturnType<typeof mount>
function button(label: string) {
  return wrapper.findAllComponents(ElButton).find((item) => item.text() === label)!
}
function useConnectedGroup() {
  mocks.get.mockResolvedValue({
    ...value,
    definition: {
      ...value.definition,
      nodes: [
        { id: 'start', type: 'start' },
        { id: 'reply', type: 'output', template: '业务回答' },
        { id: 'end', type: 'end' }
      ],
      edges: [
        { from: 'start', to: 'reply', priority: 7 },
        { from: 'reply', to: 'end', condition: '${state.user_input} != ""', priority: 3 }
      ]
    }
  })
}
beforeEach(() => {
  vi.resetAllMocks()
  sessionStorage.clear()
  mocks.confirm.mockResolvedValue(true)
  window.history.replaceState(null, '')
  routeParams.workflowId = 'flow'
  mocks.get.mockResolvedValue(value)
  mocks.snapshotGet.mockResolvedValue({
    workflowId: 'flow',
    draftRevision: 1,
    layout: { start: { x: 555, y: 222 }, end: { x: 888, y: 333 } },
    viewport: {}
  })
  mocks.save.mockResolvedValue({ ...value, revision: 2 })
  mocks.snapshotSave.mockResolvedValue({})
  mocks.validate.mockResolvedValue({
    revision: 1,
    problems: [
      {
        code: 'INVALID_NODE',
        severity: 'error',
        objectRef: 'node:start',
        fieldPath: 'type',
        message: '起始节点需要配置'
      }
    ]
  })
  mocks.validateNewContent.mockResolvedValue({ revision: 0, problems: [] })
  mocks.validateContent.mockResolvedValue({ revision: 1, problems: [] })
  mocks.saveAndPublish.mockImplementation(async (command) => {
    mocks.get.mockResolvedValue({
      ...value,
      revision: command.revision + 1,
      status: 'published',
      definition: command.definition
    })
    mocks.snapshotGet.mockResolvedValue({
      workflowId: command.workflow_id,
      draftRevision: command.revision + 1,
      layout: command.layout,
      viewport: command.viewport
    })
    return {
      request_id: command.request_id,
      workflow_id: command.workflow_id,
      status: 'SUCCEEDED',
      publication: {
        workflow_id: command.workflow_id,
        draft_revision: command.revision + 1,
        definition_version_id: '11',
        definition_version: 1,
        capability_version_id: '12'
      }
    }
  })
})
afterEach(() => {
  wrapper?.unmount()
  clearFeedback()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  document.body.innerHTML = ''
})
describe('工作流布局保存与恢复', () => {
  it('在知识查询节点选择来源后自动引用新声明', async () => {
    mocks.get.mockResolvedValue({
      ...value,
      definition: {
        ...value.definition,
        nodes: [
          ...value.definition.nodes,
          {
            id: 'knowledge',
            type: 'knowledge_query',
            requirement: 'old',
            query: '查询',
            result_to: 'state.result'
          }
        ],
        edges: [
          { from: 'start', to: 'knowledge' },
          { from: 'knowledge', to: 'end' }
        ]
      }
    })
    mocks.definitions.mockResolvedValue([
      {
        id: '20',
        capabilityType: 'KNOWLEDGE_SOURCE',
        capabilityKey: 'knowledge-base:8',
        name: '订单知识',
        defaultVersionId: '21'
      }
    ])
    wrapper = mount(WorkflowDetailPage, { attachTo: document.body })
    await flushPromises()
    await wrapper.get('[data-node-id="knowledge"]').trigger('click')
    await flushPromises()
    const inline = wrapper
      .get('aside[aria-label="节点配置"]')
      .getComponent(WorkflowCapabilityDeclarations)
    expect(
      inline.findAllComponents(ElFormItem).some((item) => item.props('label') === '添加已发布工具')
    ).toBe(false)
    const select = inline
      .findAllComponents(ElFormItem)
      .find((item) => item.props('label') === '添加已发布知识来源')!
      .getComponent(ElSelect)
    select.vm.$emit('visible-change', true)
    await flushPromises()
    select.vm.$emit('change', '20')
    await flushPromises()
    expect(
      wrapper.findComponent({ name: 'NodeProperties' }).props('modelValue').knowledgeRequirement
    ).toBe('knowledge_1')
    expect(
      wrapper.findAllComponents(WorkflowCapabilityDeclarations)[1]!.props('requirements')
    ).toContain('knowledge-base:8')
  })
  it('在工具节点选择已发布工具，自动生成声明并保存同一草稿', async () => {
    mocks.get.mockResolvedValue({
      ...value,
      definition: {
        ...value.definition,
        nodes: [...value.definition.nodes, { id: 'fetch', type: 'tool', tool: 'old' }],
        edges: [
          { from: 'start', to: 'fetch' },
          { from: 'fetch', to: 'end' }
        ]
      }
    })
    mocks.definitions.mockResolvedValue([
      {
        id: '10',
        capabilityType: 'TOOL',
        capabilityKey: 'orders',
        name: '查询订单',
        defaultVersionId: '11'
      }
    ])
    mocks.versions.mockResolvedValue([])
    wrapper = mount(WorkflowDetailPage, { attachTo: document.body })
    await flushPromises()
    await wrapper.get('[data-node-id="fetch"]').trigger('click')
    await flushPromises()
    const panel = wrapper.get('aside[aria-label="节点配置"]')
    const inline = panel.getComponent(WorkflowCapabilityDeclarations)
    const select = inline
      .findAllComponents(ElFormItem)
      .find((item) => item.props('label') === '添加已发布工具')!
      .getComponent(ElSelect)
    select.vm.$emit('visible-change', true)
    await flushPromises()
    select.vm.$emit('change', '10')
    await flushPromises()
    expect(wrapper.findComponent({ name: 'NodeProperties' }).props('modelValue').tool).toBe(
      'tool_1'
    )
    const declarations = wrapper.findAllComponents(WorkflowCapabilityDeclarations)
    expect(declarations[1]!.props('requirements')).toContain('orders')
    await button('保存草稿').trigger('click')
    await flushPromises()
    expect(mocks.save).toHaveBeenCalledWith(
      'flow',
      1,
      expect.objectContaining({
        meta: expect.objectContaining({
          capability_requirements: [
            expect.objectContaining({ slot: 'tool_1', capability_id: 'orders', label: '查询订单' })
          ],
          tools: [expect.objectContaining({ name: 'tool_1', requirement: 'tool_1' })]
        })
      })
    )
  })
  it('有未保存修改时，保存并发布需要更新与发布两项权限', async () => {
    const permissions = ref(['foundation.workflows.publish'])
    wrapper = mount(WorkflowDetailPage, {
      attachTo: document.body,
      global: { directives: { permission: createPermissionDirective(() => permissions) } }
    })
    await flushPromises()
    expect(document.body.textContent).toContain('发布')
    ;(wrapper.vm as unknown as { meta: { displayName: string } }).meta.displayName = '更新流程'
    await flushPromises()
    expect(document.body.textContent).not.toContain('保存并发布')
    permissions.value.push('foundation.workflows.update', 'foundation.workflows.read')
    await flushPromises()
    expect(document.body.textContent).toContain('保存并发布')
    expect(mocks.save).not.toHaveBeenCalled()
    expect(mocks.publish).not.toHaveBeenCalled()
  })
  it('已有工作流可在一次操作中保存、校验并发布对应修订', async () => {
    useConnectedGroup()
    const current = await mocks.get()
    mocks.save.mockResolvedValue({ ...current, revision: 2 })
    mocks.validate.mockResolvedValue({ revision: 2, problems: [] })
    mocks.publish.mockResolvedValue({
      workflowId: 'flow',
      draftRevision: 2,
      definitionVersionId: '11',
      definitionVersion: 1
    })
    wrapper = mount(WorkflowDetailPage, { attachTo: document.body })
    await flushPromises()
    const state = wrapper.vm as unknown as { meta: { displayName: string } }
    state.meta.displayName = '更新后的流程'
    await flushPromises()
    await button('保存并发布').trigger('click')
    await flushPromises()
    expect(mocks.save).not.toHaveBeenCalled()
    expect(mocks.snapshotSave).not.toHaveBeenCalled()
    expect(mocks.validate).not.toHaveBeenCalled()
    expect(mocks.publish).not.toHaveBeenCalled()
    expect(mocks.saveAndPublish).toHaveBeenCalledExactlyOnceWith(
      expect.objectContaining({
        workflow_id: 'flow',
        revision: 1,
        definition: expect.any(Object),
        layout: expect.any(Object),
        viewport: expect.any(Object)
      })
    )
    expect(mocks.confirm.mock.invocationCallOrder[0]).toBeLessThan(
      mocks.saveAndPublish.mock.invocationCallOrder[0]!
    )
  })
  it('取消保存并发布后保留本地修改，不写入草稿和布局', async () => {
    useConnectedGroup()
    const current = await mocks.get()
    mocks.save.mockResolvedValue({ ...current, revision: 2 })
    mocks.validate.mockResolvedValue({ revision: 2, problems: [] })
    mocks.confirm.mockResolvedValueOnce(false)
    wrapper = mount(WorkflowDetailPage, { attachTo: document.body })
    await flushPromises()
    ;(wrapper.vm as unknown as { meta: { displayName: string } }).meta.displayName = '更新流程'
    await flushPromises()
    await button('保存并发布').trigger('click')
    await flushPromises()
    expect(mocks.save).not.toHaveBeenCalled()
    expect(mocks.snapshotSave).not.toHaveBeenCalled()
    expect(mocks.validate).not.toHaveBeenCalled()
    expect(mocks.publish).not.toHaveBeenCalled()
    expect(mocks.saveAndPublish).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('未保存')
  })

  it('组合发布结果未知时只核对原请求，不重复发送并保留输入', async () => {
    useConnectedGroup()
    mocks.saveAndPublish.mockRejectedValueOnce(new Error('网络中断'))
    wrapper = mount(WorkflowDetailPage, { attachTo: document.body })
    await flushPromises()
    ;(wrapper.vm as unknown as { meta: { displayName: string } }).meta.displayName = '待核对流程'
    await flushPromises()
    await button('保存并发布').trigger('click')
    await flushPromises()
    const command = mocks.saveAndPublish.mock.calls[0]![0]
    expect(wrapper.text()).toContain(command.request_id)
    expect(wrapper.findComponent(WorkflowCanvas).props('readonly')).toBe(true)
    mocks.saveAndPublishResult.mockResolvedValueOnce({
      request_id: command.request_id,
      workflow_id: 'flow',
      status: 'PROCESSING'
    })
    await button('核对原发布').trigger('click')
    await flushPromises()
    expect(mocks.saveAndPublishResult).toHaveBeenCalledWith('flow', command.request_id)
    expect(mocks.saveAndPublish).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain(command.request_id)
    expect((wrapper.vm as unknown as { meta: { displayName: string } }).meta.displayName).toBe(
      '待核对流程'
    )
  })

  it('明确拒绝后解锁并保留原编辑内容', async () => {
    useConnectedGroup()
    mocks.saveAndPublish.mockImplementationOnce(async (command) => ({
      request_id: command.request_id,
      workflow_id: 'flow',
      status: 'REJECTED',
      error_code: 'VALIDATION_ERROR',
      error_detail: {
        message: '配置不合法',
        problems: [
          {
            code: 'WF',
            severity: 'error',
            objectRef: 'node:start',
            fieldPath: 'type',
            message: '服务端节点错误'
          }
        ]
      }
    }))
    wrapper = mount(WorkflowDetailPage, { attachTo: document.body })
    await flushPromises()
    ;(wrapper.vm as unknown as { meta: { displayName: string } }).meta.displayName = '需要修正'
    await flushPromises()
    await button('保存并发布').trigger('click')
    await flushPromises()
    expect(wrapper.text()).not.toContain('保存并发布结果尚未确认')
    expect((wrapper.vm as unknown as { meta: { displayName: string } }).meta.displayName).toBe(
      '需要修正'
    )
    expect(wrapper.findComponent(WorkflowCanvas).props('readonly')).toBe(false)
    expect(wrapper.text()).toContain('服务端节点错误')
  })

  it('刷新页面后只保留原请求身份并能核对成功回执', async () => {
    useConnectedGroup()
    mocks.saveAndPublish.mockRejectedValueOnce(new Error('网络中断'))
    wrapper = mount(WorkflowDetailPage, { attachTo: document.body })
    await flushPromises()
    ;(wrapper.vm as unknown as { meta: { displayName: string } }).meta.displayName = '待核对'
    await flushPromises()
    await button('保存并发布').trigger('click')
    await flushPromises()
    const command = mocks.saveAndPublish.mock.calls[0]![0]
    wrapper.unmount()
    wrapper = mount(WorkflowDetailPage, { attachTo: document.body })
    await flushPromises()
    expect(wrapper.text()).toContain(command.request_id)
    mocks.get.mockResolvedValue({
      ...value,
      revision: 2,
      status: 'published',
      definition: command.definition
    })
    mocks.snapshotGet.mockResolvedValue({
      workflowId: 'flow',
      draftRevision: 2,
      layout: command.layout,
      viewport: command.viewport
    })
    mocks.saveAndPublishResult.mockResolvedValueOnce({
      request_id: command.request_id,
      workflow_id: 'flow',
      status: 'SUCCEEDED',
      publication: {
        workflow_id: 'flow',
        draft_revision: 2,
        definition_version_id: '11',
        definition_version: 1,
        capability_version_id: '12'
      }
    })
    await button('核对原发布').trigger('click')
    await flushPromises()
    expect(wrapper.text()).not.toContain(command.request_id)
    expect(mocks.saveAndPublish).toHaveBeenCalledTimes(1)
  })

  it('发布已确认但草稿与布局修订不一致时保留原请求及编辑内容', async () => {
    useConnectedGroup()
    mocks.saveAndPublish.mockImplementationOnce(async (command) => ({
      request_id: command.request_id,
      workflow_id: 'flow',
      status: 'SUCCEEDED',
      publication: {
        workflow_id: 'flow',
        draft_revision: 2,
        definition_version_id: '11',
        definition_version: 1,
        capability_version_id: '12'
      }
    }))
    wrapper = mount(WorkflowDetailPage, { attachTo: document.body })
    await flushPromises()
    ;(wrapper.vm as unknown as { meta: { displayName: string } }).meta.displayName = '原编辑内容'
    await flushPromises()
    await button('保存并发布').trigger('click')
    await flushPromises()
    const command = mocks.saveAndPublish.mock.calls[0]![0]
    expect(wrapper.text()).toContain(command.request_id)
    expect((wrapper.vm as unknown as { meta: { displayName: string } }).meta.displayName).toBe(
      '原编辑内容'
    )
    expect(wrapper.findComponent(WorkflowCanvas).props('readonly')).toBe(true)
  })

  it('发布前校验读到不同草稿修订时停止发布并保留本地内容', async () => {
    mocks.validate.mockResolvedValue({ revision: 2, problems: [] })
    wrapper = mount(WorkflowDetailPage, { attachTo: document.body })
    await flushPromises()
    await button('发布').trigger('click')
    await flushPromises()
    expect(mocks.validate).toHaveBeenCalledExactlyOnceWith('flow')
    expect(mocks.publish).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('工作流状态已变化，本地内容已保留')
    expect(button('发布').props('disabled')).toBe(true)
    expect(wrapper.text()).not.toContain('草稿校验通过，可以发布')
  })

  it('已有草稿首次读取失败时禁止编辑和测试，重新读取后恢复', async () => {
    mocks.get.mockRejectedValueOnce(new Error('草稿读取失败'))
    wrapper = mount(WorkflowDetailPage, { attachTo: document.body })
    await flushPromises()
    expect(wrapper.text()).toContain('工作流草稿尚未读取成功')
    expect(wrapper.text()).not.toContain('已提供“开始 → 结束”')
    expect(button('保存草稿').props('disabled')).toBe(true)
    expect(button('校验').props('disabled')).toBe(true)
    expect(button('测试').props('disabled')).toBe(true)
    expect(wrapper.findComponent(WorkflowCanvas).props('readonly')).toBe(true)
    await button('重新读取').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('草稿已保存')
    expect(button('保存草稿').props('disabled')).toBe(false)
    expect(button('测试').props('disabled')).toBe(false)
    expect(mocks.create).not.toHaveBeenCalled()
  })

  it('新建工作流初始明确显示尚未保存', async () => {
    routeParams.workflowId = ''
    wrapper = mount(WorkflowDetailPage, { attachTo: document.body })
    await flushPromises()
    expect(wrapper.text()).toContain('新建草稿 · 尚未保存')
    expect(wrapper.text()).toContain('已提供“开始 → 结束”')
    expect(button('撤销').props('disabled')).toBe(true)
  })

  it('新建工作流一次保存布局并进入测试配置', async () => {
    routeParams.workflowId = ''
    mocks.create.mockImplementation((workflowId: string, definition: Record<string, unknown>) =>
      Promise.resolve({ ...value, workflowId, definition })
    )
    mocks.replace.mockImplementation(async (location: { path: string }) => {
      routeParams.workflowId = location.path.split('/').at(-1)!
    })
    wrapper = mount(WorkflowDetailPage, { attachTo: document.body })
    await flushPromises()
    await button('保存并配置测试').trigger('click')
    await flushPromises()
    expect(mocks.create).toHaveBeenCalledOnce()
    expect(mocks.snapshotSave).toHaveBeenCalledOnce()
    expect(mocks.replace).toHaveBeenCalledOnce()
    expect(wrapper.find('[aria-label="工作流测试"]').exists()).toBe(true)
  })

  it('新建测试入口要求同时拥有创建和更新权限', async () => {
    routeParams.workflowId = ''
    const permissions = ref(['foundation.workflows.create'])
    wrapper = mount(WorkflowDetailPage, {
      attachTo: document.body,
      global: { directives: { permission: createPermissionDirective(() => permissions) } }
    })
    await flushPromises()
    expect(document.body.textContent).not.toContain('保存并配置测试')
    permissions.value = ['foundation.workflows.update']
    await flushPromises()
    expect(document.body.textContent).not.toContain('保存并配置测试')
    permissions.value = ['foundation.workflows.create', 'foundation.workflows.update']
    await flushPromises()
    expect(document.body.textContent).toContain('保存并配置测试')
  })

  it('新建测试布局失败后只补布局，不重复创建', async () => {
    routeParams.workflowId = ''
    mocks.create.mockImplementation((workflowId: string, definition: Record<string, unknown>) =>
      Promise.resolve({ ...value, workflowId, definition })
    )
    mocks.snapshotSave.mockRejectedValueOnce(new Error('布局保存失败')).mockResolvedValueOnce({})
    mocks.replace.mockImplementation(async (location: { path: string }) => {
      routeParams.workflowId = location.path.split('/').at(-1)!
    })
    wrapper = mount(WorkflowDetailPage, { attachTo: document.body })
    await flushPromises()
    await button('保存并配置测试').trigger('click')
    await flushPromises()
    expect(mocks.replace).not.toHaveBeenCalled()
    expect(wrapper.find('[aria-label="工作流测试"]').exists()).toBe(false)
    await button('保存并配置测试').trigger('click')
    await flushPromises()
    expect(mocks.create).toHaveBeenCalledOnce()
    expect(mocks.snapshotSave).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[aria-label="工作流测试"]').exists()).toBe(true)
  })

  it('创建结果未知时锁定原标识，刷新和只读核对不重复创建', async () => {
    routeParams.workflowId = ''
    mocks.create.mockRejectedValueOnce(new HttpClientError({ status: 0, userMessage: '连接中断' }))
    wrapper = mount(WorkflowDetailPage, { attachTo: document.body })
    await flushPromises()
    const idField = wrapper
      .findAllComponents(ElFormItem)
      .find((field) => field.props('label') === '业务标识')!
    const generated = idField.findComponent(ElInput).props('modelValue') as string
    await button('保存并配置测试').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('创建结果尚未确认')
    expect(wrapper.text()).toContain('创建结果待核对')
    expect(wrapper.text()).not.toContain('新建草稿 · 尚未保存')
    expect(button('保存草稿').props('disabled')).toBe(true)
    expect(button('保存并配置测试').props('disabled')).toBe(true)
    expect(wrapper.find('[aria-label="工作流测试"]').exists()).toBe(false)
    expect(mocks.replace).not.toHaveBeenCalled()
    expect(sessionStorage.getItem('noewise:workflow-create:workflow-test-session')).toBe(generated)
    wrapper.unmount()
    wrapper = mount(WorkflowDetailPage, { attachTo: document.body })
    await flushPromises()
    expect(wrapper.text()).toContain(generated)
    expect(button('保存草稿').props('disabled')).toBe(true)
    mocks.get.mockRejectedValueOnce(new HttpClientError({ status: 404, userMessage: '未找到' }))
    await button('核对原工作流').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('不能证明原请求未受理')
    mocks.get.mockResolvedValueOnce({ ...value, workflowId: generated })
    await button('核对原工作流').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('不能证明原创建请求已经结束')
    expect(button('保存草稿').props('disabled')).toBe(true)
    expect(mocks.create).toHaveBeenCalledOnce()
    expect(mocks.snapshotSave).not.toHaveBeenCalled()
  })
  it('明确的权限拒绝不伪造未知结果，可以在授权恢复后再次提交', async () => {
    routeParams.workflowId = ''
    mocks.create.mockRejectedValueOnce(
      new HttpClientError({ status: 403, code: 'FORBIDDEN', userMessage: '无权限' })
    )
    wrapper = mount(WorkflowDetailPage, { attachTo: document.body })
    await flushPromises()
    await button('保存草稿').trigger('click')
    await flushPromises()
    expect(wrapper.text()).not.toContain('创建结果尚未确认')
    expect(button('保存草稿').props('disabled')).toBe(false)
    expect(sessionStorage.getItem('noewise:workflow-create:workflow-test-session')).toBeNull()
  })
  it('无法保存创建记录时不派发创建请求', async () => {
    routeParams.workflowId = ''
    wrapper = mount(WorkflowDetailPage, { attachTo: document.body })
    await flushPromises()
    const storage = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw Error('不可用')
    })
    await button('保存草稿').trigger('click')
    await flushPromises()
    expect(mocks.create).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('无法保存、读取或清除创建记录')
    storage.mockRestore()
  })

  it('创建已确认但记录清除失败时保留成功身份，不误报未知或继续写布局', async () => {
    routeParams.workflowId = ''
    mocks.create.mockImplementation((workflowId: string, definition: Record<string, unknown>) =>
      Promise.resolve({ ...value, workflowId, definition })
    )
    wrapper = mount(WorkflowDetailPage, { attachTo: document.body })
    await flushPromises()
    const storage = vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => {
      throw Error('不可用')
    })
    await button('保存草稿').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('工作流已创建，但创建记录无法清除')
    expect(wrapper.text()).not.toContain('创建结果尚未确认')
    expect(wrapper.text()).toContain('流程内容已保存，节点位置尚未保存')
    expect(mocks.create).toHaveBeenCalledOnce()
    expect(mocks.snapshotSave).not.toHaveBeenCalled()
    storage.mockRestore()
  })

  it('新建草稿自动生成业务标识，布局写入失败后沿用已创建的身份', async () => {
    routeParams.workflowId = ''
    mocks.create.mockImplementation((workflowId: string, definition: Record<string, unknown>) =>
      Promise.resolve({ ...value, workflowId, definition })
    )
    mocks.snapshotSave.mockRejectedValueOnce(new Error('布局保存失败')).mockResolvedValueOnce({})
    wrapper = mount(WorkflowDetailPage, { attachTo: document.body })
    await flushPromises()
    const idField = wrapper
      .findAllComponents(ElFormItem)
      .find((field) => field.props('label') === '业务标识')!
    const generated = idField.findComponent(ElInput).props('modelValue') as string
    expect(generated).toMatch(/^workflow-[0-9a-f-]{36}$/)
    await button('保存草稿').trigger('click')
    await flushPromises()
    expect(mocks.create).toHaveBeenCalledOnce()
    expect(mocks.create.mock.calls[0]![0]).toBe(generated)
    expect(mocks.replace).not.toHaveBeenCalled()
    await button('保存草稿').trigger('click')
    await flushPromises()
    expect(mocks.create).toHaveBeenCalledOnce()
    expect(mocks.snapshotSave).toHaveBeenCalledTimes(2)
    expect(mocks.replace.mock.calls[0]![0].path).toBe('/admin/workflows/' + generated)
  })

  it.each([
    ['script', '导出定义'],
    ['script', '导出当前内容'],
    ['schema', '导出定义'],
    ['schema', '导出当前内容']
  ] as const)('导出阻止遗漏%s本地编辑：%s，处理后冲突中仍可导出', async (kind, entry) => {
    mocks.validate.mockResolvedValue({ revision: 9, problems: [] })
    wrapper = mount(WorkflowDetailPage, { attachTo: document.body })
    await flushPromises()
    await button('校验').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('工作流状态已变化，本地内容已保留')
    await wrapper.get('#tab-info').trigger('click')
    await flushPromises()
    const resources = wrapper.findComponent(WorkflowScriptResources)
    const governance = wrapper.findComponent(WorkflowGovernanceFields)
    if (kind === 'script') {
      const inputs = resources.findAllComponents(ElInput)
      inputs[0]!.vm.$emit('update:modelValue', 'scripts/export.js')
      inputs[1]!.vm.$emit('update:modelValue', 'return "当前脚本"')
    } else {
      await governance.findComponent(ElButton).trigger('click')
      await governance.find('textarea[aria-label="input_schema"]').setValue('{')
    }
    await flushPromises()
    const blob = vi.fn(function () {
      return {} as Blob
    })
    vi.stubGlobal('Blob', blob)
    vi.stubGlobal(
      'URL',
      class extends URL {
        static createObjectURL = vi.fn(() => 'blob:workflow-export')
        static revokeObjectURL = vi.fn()
      }
    )
    const download = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    await button(entry).trigger('click')
    await flushPromises()
    expect(blob).not.toHaveBeenCalled()
    expect(download).not.toHaveBeenCalled()
    expect(document.querySelectorAll('.el-message')).toHaveLength(1)
    expect(document.querySelector('.el-message')?.textContent).toContain('导出')
    if (kind === 'script') {
      vi.stubGlobal('crypto', {
        subtle: { digest: vi.fn(async () => new Uint8Array(32).fill(3).buffer) }
      })
      await resources
        .findAllComponents(ElButton)
        .find((item) => item.text() === '保存资源')!
        .trigger('click')
    } else
      await governance.find('textarea[aria-label="input_schema"]').setValue('{"type":"object"}')
    await flushPromises()
    const reads = mocks.get.mock.calls.length
    await button(entry).trigger('click')
    await flushPromises()
    expect(download).toHaveBeenCalledTimes(1)
    expect(document.querySelectorAll('.el-message')).toHaveLength(0)
    const exported = JSON.parse((blob.mock.calls[0] as unknown as [string[]])[0][0]!)
    if (kind === 'script')
      expect(exported.resources).toEqual([
        {
          path: 'scripts/export.js',
          content_base64: btoa(
            String.fromCharCode(...new TextEncoder().encode('return "当前脚本"'))
          ),
          sha256: '03'.repeat(32)
        }
      ])
    else expect(exported.meta.capability.input_schema).toEqual({ type: 'object' })
    expect(mocks.get).toHaveBeenCalledTimes(reads)
    expect(mocks.save).not.toHaveBeenCalled()
    expect(mocks.publish).not.toHaveBeenCalled()
  })
  it('校验返回其他修订时保留当前内容并进入差异核对，不提示直接覆盖重读', async () => {
    mocks.validate.mockResolvedValue({ revision: 9, problems: [] })
    wrapper = mount(WorkflowDetailPage, { attachTo: document.body })
    await flushPromises()
    const canvas = wrapper.findComponent(WorkflowCanvas)
    const original = JSON.parse(JSON.stringify(canvas.props('nodes')))
    await button('校验').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('工作流状态已变化，本地内容已保留')
    expect(wrapper.findAll('.el-alert--error')).toHaveLength(0)
    expect(canvas.props('nodes')).toEqual(original)
    expect(mocks.get).toHaveBeenCalledTimes(1)
    expect(mocks.save).not.toHaveBeenCalled()
    expect(button('发布').props('disabled')).toBe(true)
  })
  it('多选复制粘贴保留整组选择、只复制内部边，单次撤销与重做覆盖整次粘贴', async () => {
    useConnectedGroup()
    wrapper = mount(WorkflowDetailPage, { attachTo: document.body })
    await flushPromises()
    const canvas = wrapper.findComponent(WorkflowCanvas)
    const originalNodes = JSON.parse(JSON.stringify(canvas.props('nodes')))
    const originalEdges = JSON.parse(JSON.stringify(canvas.props('edges')))
    const originalViewport = { ...canvas.props('viewport') }
    canvas.vm.$emit('update:selectedIds', ['reply', 'end'])
    canvas.vm.$emit('update:selectedId', 'reply')
    await flushPromises()
    expect(canvas.props('selectedIds')).toEqual(['reply', 'end'])
    expect(wrapper.find('.workflow-properties').isVisible()).toBe(false)
    canvas.vm.$emit('copy-nodes', ['reply', 'end'])
    await flushPromises()
    expect(canvas.props('canPaste')).toBe(true)
    canvas.vm.$emit('paste-nodes')
    await flushPromises()
    expect(canvas.props('nodes')).toHaveLength(5)
    expect(canvas.props('selectedIds')).toEqual(['reply_copy', 'end_copy'])
    expect(canvas.props('selectedId')).toBe('reply_copy')
    expect(canvas.findAll('.workflow-canvas-node.selected')).toHaveLength(2)
    expect(wrapper.find('.workflow-properties').isVisible()).toBe(false)
    expect(canvas.props('edges')).toHaveLength(3)
    expect(canvas.props('edges')[2]).toMatchObject({
      source: 'reply_copy',
      target: 'end_copy',
      condition: '${state.user_input} != ""',
      priority: 3
    })
    expect(canvas.props('nodes').slice(0, 3)).toEqual(originalNodes)
    await button('撤销').trigger('click')
    await flushPromises()
    expect(canvas.props('nodes')).toEqual(originalNodes)
    expect(canvas.props('edges')).toEqual(originalEdges)
    expect(canvas.props('viewport')).toEqual(originalViewport)
    expect(canvas.props('selectedIds')).toEqual([])
    await button('重做').trigger('click')
    await flushPromises()
    expect(canvas.props('nodes')).toHaveLength(5)
    expect(canvas.props('edges')).toHaveLength(3)
  })
  it('批量删除一次移除所选节点与相邻边，恢复画布焦点，单次撤销完整恢复', async () => {
    useConnectedGroup()
    wrapper = mount(WorkflowDetailPage, { attachTo: document.body })
    await flushPromises()
    const canvas = wrapper.findComponent(WorkflowCanvas)
    const originalNodes = JSON.parse(JSON.stringify(canvas.props('nodes')))
    const originalEdges = JSON.parse(JSON.stringify(canvas.props('edges')))
    await canvas.get('[data-node-id="reply"]').trigger('click')
    canvas.vm.$emit('update:selectedIds', ['reply', 'end'])
    await flushPromises()
    canvas.vm.$emit('remove-nodes', ['reply', 'end'])
    await flushPromises()
    expect(canvas.props('nodes').map((node) => node.id)).toEqual(['start'])
    expect(canvas.props('edges')).toEqual([])
    expect(canvas.props('selectedIds')).toEqual([])
    expect(canvas.props('selectedId')).toBe('')
    expect(document.activeElement).toBe(canvas.get('.workflow-canvas-surface').element)
    await button('撤销').trigger('click')
    await flushPromises()
    expect(canvas.props('nodes')).toEqual(originalNodes)
    expect(canvas.props('edges')).toEqual(originalEdges)
    expect(button('撤销').props('disabled')).toBe(true)
  })
  it('复制开始节点给出具体解释且不启用粘贴，也不写入图或产生撤销项', async () => {
    wrapper = mount(WorkflowDetailPage, { attachTo: document.body })
    await flushPromises()
    const canvas = wrapper.findComponent(WorkflowCanvas)
    const original = JSON.parse(JSON.stringify(canvas.props('nodes')))
    canvas.vm.$emit('copy-nodes', ['start', 'end'])
    await flushPromises()
    expect(
      Array.from(document.body.querySelectorAll('.el-message__content')).at(-1)?.textContent
    ).toContain('开始节点不能复制，请取消选择开始节点')
    expect(canvas.props('canPaste')).toBe(false)
    expect(canvas.props('nodes')).toEqual(original)
    expect(button('撤销').props('disabled')).toBe(true)
  })
  it('409 保存保留本地修订、节点与视口，对比只读取远端且继续禁止保存', async () => {
    mocks.save.mockRejectedValue(
      new HttpClientError({ status: 409, userMessage: '草稿修订已变化' })
    )
    wrapper = mount(WorkflowDetailPage, { attachTo: document.body })
    await flushPromises()
    const canvas = wrapper.findComponent(WorkflowCanvas)
    canvas.vm.$emit(
      'update:nodes',
      canvas.props('nodes').map((node) => ({ ...node, x: node.x + 16 }))
    )
    canvas.vm.$emit('update:viewport', { zoom: 0.8, panX: -40, panY: 70 })
    await flushPromises()
    const localNodes = JSON.parse(JSON.stringify(canvas.props('nodes')))
    const localViewport = { ...canvas.props('viewport') }
    const governance = wrapper.findComponent(WorkflowGovernanceFields).element
    await button('保存草稿').trigger('click')
    await flushPromises()
    expect(mocks.save).toHaveBeenCalledExactlyOnceWith('flow', 1, expect.any(Object))
    expect(mocks.snapshotSave).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('工作流状态已变化，本地内容已保留')
    expect(wrapper.findAll('.el-alert--error')).toHaveLength(0)
    expect(canvas.props('nodes')).toEqual(localNodes)
    expect(canvas.props('viewport')).toEqual(localViewport)
    expect(button('保存草稿').props('disabled')).toBe(true)
    expect(button('校验').props('disabled')).toBe(true)
    expect(button('保存并发布').props('disabled')).toBe(true)
    mocks.get.mockResolvedValueOnce({
      ...value,
      revision: 9,
      definition: {
        ...value.definition,
        nodes: [...value.definition.nodes, { id: 'remote', type: 'end' }]
      }
    })
    await button('查看最新差异').trigger('click')
    await flushPromises()
    expect(mocks.get).toHaveBeenCalledTimes(2)
    expect(mocks.get).toHaveBeenLastCalledWith('flow')
    expect(mocks.snapshotGet).toHaveBeenCalledTimes(1)
    expect(mocks.snapshotSave).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('服务端最新草稿修订 9')
    expect(canvas.props('nodes')).toEqual(localNodes)
    expect(canvas.props('viewport')).toEqual(localViewport)
    expect(wrapper.findComponent(WorkflowGovernanceFields).element).toBe(governance)
    const state = wrapper.vm as unknown as { draft: { revision: number } }
    expect(state.draft.revision).toBe(1)
    expect(button('保存草稿').props('disabled')).toBe(true)
    await button('保存草稿').trigger('click')
    await flushPromises()
    expect(mocks.save).toHaveBeenCalledTimes(1)
    expect(mocks.create).not.toHaveBeenCalled()
    expect(mocks.publish).not.toHaveBeenCalled()
    expect(mocks.validate).not.toHaveBeenCalled()
    expect(mocks.history).not.toHaveBeenCalled()
  })
  it('已归档草稿禁用写入口，画布批量写事件也不改变节点和连接', async () => {
    mocks.get.mockResolvedValue({ ...value, status: 'archived' })
    wrapper = mount(WorkflowDetailPage, { attachTo: document.body })
    await flushPromises()
    const canvas = wrapper.findComponent(WorkflowCanvas)
    const originalNodes = JSON.parse(JSON.stringify(canvas.props('nodes')))
    const originalEdges = JSON.parse(JSON.stringify(canvas.props('edges')))
    expect(canvas.props('readonly')).toBe(true)
    expect(wrapper.text()).toContain('已归档 · 只读')
    for (const label of ['保存草稿', '校验', '发布', '添加节点', '自动排列', '撤销', '重做']) {
      expect(button(label).props('disabled')).toBe(true)
    }
    canvas.vm.$emit('copy-nodes', ['end'])
    await flushPromises()
    canvas.vm.$emit('paste-nodes')
    canvas.vm.$emit('remove-nodes', ['end'])
    canvas.vm.$emit('remove-node', 'end')
    await flushPromises()
    expect(canvas.props('nodes')).toEqual(originalNodes)
    expect(canvas.props('edges')).toEqual(originalEdges)
    expect(mocks.save).not.toHaveBeenCalled()
    expect(mocks.snapshotSave).not.toHaveBeenCalled()
    expect(mocks.create).not.toHaveBeenCalled()
    expect(mocks.publish).not.toHaveBeenCalled()
  })
  it('将工作流本地工具名映射到真实能力当前版本的输入与输出契约', async () => {
    mocks.get.mockResolvedValue({
      ...value,
      definition: {
        ...value.definition,
        nodes: [
          ...value.definition.nodes,
          { id: 'fetch', type: 'tool', tool: 'local_alias', result_to: 'state.order' }
        ],
        meta: {
          name: 'flow',
          version: '1.0.0',
          capability_requirements: [
            {
              slot: 'lookup_slot',
              type: 'tool',
              capability_id: 'stable_lookup',
              version_constraint: '2.x',
              label: '查询'
            }
          ],
          tools: [{ name: 'local_alias', requirement: 'lookup_slot' }]
        }
      }
    })
    mocks.definitions.mockResolvedValue([
      { id: '10', capabilityType: 'TOOL', capabilityKey: 'stable_lookup', defaultVersionId: '102' }
    ])
    mocks.versions.mockResolvedValue([
      {
        id: '101',
        definitionId: '10',
        versionKey: '1.0.0',
        descriptor: { input_schema: { type: 'object' } }
      },
      {
        id: '102',
        definitionId: '10',
        versionKey: '2.1.0',
        descriptor: {
          tool_name: 'remote.lookup',
          input_schema: { type: 'object', properties: { enabled: { type: 'boolean' } } },
          output_schema: {
            type: 'object',
            properties: { amount: { type: 'number' }, token: { type: 'string' } }
          },
          sensitive_fields: ['token']
        }
      }
    ])
    wrapper = mount(WorkflowDetailPage, { attachTo: document.body })
    await flushPromises()
    await wrapper.get('[data-node-id="fetch"]').trigger('click')
    await flushPromises()
    expect(mocks.definitions).toHaveBeenCalledWith('TOOL', 'PUBLISHED')
    expect(wrapper.findComponent({ name: 'NodeProperties' }).props('toolContracts')).toEqual({
      local_alias: {
        inputSchema: { type: 'object', properties: { enabled: { type: 'boolean' } } },
        outputSchema: {
          type: 'object',
          properties: { amount: { type: 'number' }, token: { type: 'string' } }
        },
        outputSchemaPath: 'structuredContent',
        toolName: 'remote.lookup',
        sensitiveFields: ['token'],
        versionId: '102',
        capabilityKey: 'stable_lookup',
        versionKey: '2.1.0'
      }
    })
    expect(wrapper.findComponent({ name: 'NodeProperties' }).props('modelValue').resultTo).toBe(
      'state.order'
    )
    mocks.definitions.mockRejectedValueOnce(new Error('目录读取失败'))
    await button('刷新工具参数').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('工具参数未刷新')
    expect(
      wrapper.findComponent({ name: 'NodeProperties' }).props('toolContracts').local_alias.versionId
    ).toBe('102')
    mocks.definitions.mockResolvedValueOnce([
      { id: '10', capabilityType: 'TOOL', capabilityKey: 'stable_lookup', defaultVersionId: '103' }
    ])
    mocks.versions.mockResolvedValueOnce([
      {
        id: '103',
        definitionId: '10',
        versionKey: '2.2.0',
        descriptor: {
          tool_name: 'remote.lookup',
          input_schema: { type: 'object', properties: { order: { type: 'string' } } }
        }
      }
    ])
    await button('刷新工具参数').trigger('click')
    await flushPromises()
    expect(wrapper.text()).not.toContain('工具参数未刷新')
    expect(
      wrapper.findComponent({ name: 'NodeProperties' }).props('toolContracts').local_alias.versionId
    ).toBe('103')
    expect(wrapper.findComponent({ name: 'NodeProperties' }).props('modelValue').resultTo).toBe(
      'state.order'
    )
    expect(mocks.save).not.toHaveBeenCalled()
  })
  it('通过画布插入节点保留原分支并以单次撤销恢复原图', async () => {
    mocks.get.mockResolvedValue({
      ...value,
      definition: {
        ...value.definition,
        edges: [{ from: 'start', to: 'end', condition: '${state.user_input} != ""', priority: 6 }]
      }
    })
    wrapper = mount(WorkflowDetailPage, { attachTo: document.body })
    await flushPromises()
    wrapper.findComponent(WorkflowCanvas).vm.$emit('insert-node', 'edge_0')
    await flushPromises()
    const picker = wrapper
      .findAllComponents(ElSelect)
      .find((select) => select.props('ariaLabel') === '新节点类型')!
    picker.vm.$emit('update:modelValue', 'output')
    await flushPromises()
    await wrapper
      .findAllComponents(ElButton)
      .find((button) => button.text() === '添加并连接')!
      .trigger('click')
    await flushPromises()
    const graph = wrapper.findComponent(WorkflowCanvas).props()
    expect(graph.nodes).toHaveLength(3)
    expect(graph.edges[0]).toMatchObject({
      source: 'start',
      condition: '${state.user_input} != ""',
      priority: 6
    })
    expect(graph.edges[1]).toMatchObject({
      source: graph.edges[0].target,
      target: 'end',
      condition: '',
      priority: 0
    })
    await wrapper
      .findAllComponents(ElButton)
      .find((button) => button.text() === '撤销')!
      .trigger('click')
    await flushPromises()
    expect(wrapper.findComponent(WorkflowCanvas).props('nodes')).toHaveLength(2)
    expect(wrapper.findComponent(WorkflowCanvas).props('edges')).toEqual([
      expect.objectContaining({ source: 'start', target: 'end', priority: 6 })
    ])
  })
  it('新建工作流校验当前输入，不创建草稿或保存布局', async () => {
    routeParams.workflowId = ''
    mocks.create.mockImplementation((workflowId: string, definition: Record<string, unknown>) =>
      Promise.resolve({ ...value, workflowId, definition })
    )
    let finish!: (result: { revision: number; problems: [] }) => void
    mocks.validateNewContent.mockImplementation(
      () =>
        new Promise((resolve) => {
          finish = resolve
        })
    )
    wrapper = mount(WorkflowDetailPage, { attachTo: document.body })
    await flushPromises()
    wrapper
      .findAllComponents(ElFormItem)
      .find((field) => field.props('label') === '业务标识')!
      .findComponent(ElInput)
      .vm.$emit('update:modelValue', 'flow-new')
    await flushPromises()
    await wrapper
      .findAllComponents(ElButton)
      .find((button) => button.text() === '校验')!
      .trigger('click')
    await flushPromises()
    expect(mocks.create).not.toHaveBeenCalled()
    expect(mocks.snapshotSave).not.toHaveBeenCalled()
    expect(mocks.validateNewContent).toHaveBeenCalledTimes(1)
    expect(mocks.replace).not.toHaveBeenCalled()
    finish({ revision: 0, problems: [] })
    await flushPromises()
    expect(mocks.replace).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('当前未保存内容校验通过')
  })
  it('新建校验失败保留输入，重试只校验不创建', async () => {
    routeParams.workflowId = ''
    mocks.create.mockImplementation((workflowId: string, definition: Record<string, unknown>) =>
      Promise.resolve({ ...value, workflowId, definition })
    )
    mocks.validateNewContent
      .mockRejectedValueOnce(new Error('校验结果暂不可读'))
      .mockResolvedValueOnce({ revision: 0, problems: [] })
    wrapper = mount(WorkflowDetailPage, { attachTo: document.body })
    await flushPromises()
    wrapper
      .findAllComponents(ElFormItem)
      .find((field) => field.props('label') === '业务标识')!
      .findComponent(ElInput)
      .vm.$emit('update:modelValue', 'flow-retry')
    await flushPromises()
    await wrapper
      .findAllComponents(ElButton)
      .find((button) => button.text() === '校验')!
      .trigger('click')
    await flushPromises()
    expect(
      Array.from(document.body.querySelectorAll('.el-message__content')).at(-1)?.textContent
    ).toContain('校验结果暂不可读')
    expect(wrapper.text()).toContain('尚未保存')
    expect(mocks.replace).not.toHaveBeenCalled()
    expect(
      wrapper
        .findAllComponents(ElFormItem)
        .find((field) => field.props('label') === '业务标识')!
        .findComponent(ElInput)
        .props('disabled')
    ).toBe(false)
    await wrapper
      .findAllComponents(ElButton)
      .find((button) => button.text() === '校验')!
      .trigger('click')
    await flushPromises()
    expect(mocks.create).not.toHaveBeenCalled()
    expect(mocks.save).not.toHaveBeenCalled()
    expect(mocks.validateNewContent).toHaveBeenCalledTimes(2)
    expect(mocks.replace).not.toHaveBeenCalled()
  })
  it('已保存工作流可校验未保存内容，使用精确基数且不写草稿和布局', async () => {
    wrapper = mount(WorkflowDetailPage, { attachTo: document.body })
    await flushPromises()
    ;(wrapper.vm as unknown as { meta: { displayName: string } }).meta.displayName = '修改中的流程'
    await flushPromises()
    await button('校验').trigger('click')
    await flushPromises()
    expect(mocks.validateContent).toHaveBeenCalledWith('flow', 1, expect.any(Object))
    expect(mocks.create).not.toHaveBeenCalled()
    expect(mocks.save).not.toHaveBeenCalled()
    expect(mocks.snapshotSave).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('当前未保存内容校验通过')
  })
  it('未保存内容校验遇到修订冲突时保留输入并阻止写入', async () => {
    wrapper = mount(WorkflowDetailPage, { attachTo: document.body })
    await flushPromises()
    const meta = (wrapper.vm as unknown as { meta: { displayName: string } }).meta
    meta.displayName = '保留中的流程'
    mocks.validateContent.mockRejectedValueOnce(
      new HttpClientError({ status: 409, userMessage: '修订冲突' })
    )
    await flushPromises()
    await button('校验').trigger('click')
    await flushPromises()
    expect(meta.displayName).toBe('保留中的流程')
    expect(wrapper.text()).toContain('工作流状态已变化')
    expect(button('保存草稿').props('disabled')).toBe(true)
    expect(mocks.save).not.toHaveBeenCalled()
  })
  it('目标页面只在工作流和修订均匹配本次校验回执时显示结果页', async () => {
    window.history.replaceState({ workflowValidation: { workflowId: 'flow', revision: 1 } }, '')
    wrapper = mount(WorkflowDetailPage, { attachTo: document.body })
    await flushPromises()
    expect(wrapper.get('[role="tab"][aria-selected="true"]').text()).toContain('校验与变更')
    expect(wrapper.text()).toContain('草稿校验通过')
  })
  it('重新读取会重建治理与资源缓冲并解除已丢弃输入的阻断', async () => {
    wrapper = mount(WorkflowDetailPage, { attachTo: document.body })
    await flushPromises()
    const governance = wrapper.findComponent(WorkflowGovernanceFields)
    const resources = wrapper.findComponent(WorkflowScriptResources)
    governance.vm.$emit('validity-change', false)
    resources.vm.$emit('pending-change', true)
    await flushPromises()
    expect(wrapper.text()).toContain('有未保存修改')
    await wrapper
      .findAllComponents(ElButton)
      .find((button) => button.text() === '重新读取')!
      .trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('草稿已保存')
    expect(wrapper.findComponent(WorkflowGovernanceFields).vm).not.toBe(governance.vm)
    expect(wrapper.findComponent(WorkflowScriptResources).vm).not.toBe(resources.vm)
    await wrapper
      .findAllComponents(ElButton)
      .find((button) => button.text() === '保存草稿')!
      .trigger('click')
    await flushPromises()
    expect(mocks.save).toHaveBeenCalledTimes(1)
  })
  it('安装按声明匹配真实版本和部署，可选槽跳过且提交类型化参数并保留实例回执', async () => {
    mocks.get.mockResolvedValue({ ...value, activeDefinitionVersionId: 44 })
    mocks.history.mockResolvedValue([
      { current: true, definitionVersionId: 44, definitionVersion: 1 }
    ])
    mocks.publicationDetail.mockResolvedValue({
      current: true,
      definitionVersionId: 44,
      definition: {
        ...value.definition,
        meta: {
          capability_requirements: [
            {
              slot: 'lookup',
              label: '查询',
              type: 'tool',
              capability_id: 'lookup_key',
              version_constraint: '1.x',
              required: true
            },
            {
              slot: 'optional',
              label: '可选知识',
              type: 'knowledge_source',
              capability_id: 'optional_key',
              version_constraint: '*',
              required: false
            }
          ],
          config_schema: [
            { key: 'count', type: 'integer', required: true, default: 2 },
            { key: 'enabled', type: 'boolean', default: false },
            { key: 'note', type: 'textarea' }
          ]
        }
      }
    })
    mocks.definitions.mockResolvedValue([
      {
        id: '10',
        capabilityType: 'TOOL',
        capabilityKey: 'lookup_key',
        name: '查询',
        defaultVersionId: '101'
      },
      {
        id: '11',
        capabilityType: 'TOOL',
        capabilityKey: 'other',
        name: '其他',
        defaultVersionId: '111'
      }
    ])
    mocks.versions.mockImplementation((id: string) =>
      Promise.resolve([
        {
          id: id === '10' ? '101' : '111',
          definitionId: id,
          capabilityType: 'TOOL',
          versionKey: '1.2.0',
          name: id === '10' ? '查询' : '其他',
          descriptor: {}
        }
      ])
    )
    mocks.deployments.mockResolvedValue([
      { deploymentKey: 'deploy-a', toolVersionId: '101', revision: 3, providerType: 'MCP' }
    ])
    mocks.install.mockResolvedValue({ instanceId: 'instance-9', installationRevision: '7' })
    wrapper = mount(WorkflowDetailPage, { attachTo: document.body })
    await flushPromises()
    await wrapper
      .findAllComponents(ElButton)
      .find((button) => button.text() === '安装')!
      .trigger('click')
    await flushPromises()
    const picker = wrapper
      .findAllComponents(ElSelect)
      .find((select) => select.props('placeholder') === '选择已发布能力版本')!
    expect(picker.exists()).toBe(true)
    expect(picker.props('modelValue')).toBe('101')
    expect(document.body.textContent).toContain('已选择唯一可用实例')
    expect(
      wrapper
        .findAllComponents(ElSelect)
        .find((select) => select.props('placeholder') === '选择工具部署')
    ).toBeUndefined()
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
            slot: 'lookup',
            capability: { type: 'TOOL', capabilityId: '10', versionId: '101' },
            tool_deployment_id: 'deploy-a',
            tool_deployment_revision: 3
          }
        ],
        workflows: [],
        agents: []
      },
      { count: 2, enabled: false },
      ''
    )
    expect(wrapper.text()).toContain('已安装实例 instance-9')
    expect(wrapper.text()).toContain('安装修订 7')
    expect(button('前往智能体挂载此工作流').exists()).toBe(true)
  })
  it('仅新建提供可修改的开始结束骨架及与执行缺省一致的节点参数', async () => {
    routeParams.workflowId = ''
    wrapper = mount(WorkflowDetailPage, { attachTo: document.body })
    await flushPromises()
    expect(
      wrapper.findAll('[data-node-id]').map((node) => node.attributes('data-node-id'))
    ).toEqual(['start', 'end'])
    expect(mocks.get).not.toHaveBeenCalled()
    const type = wrapper.findAllComponents(ElSelect)[0]!
    type.vm.$emit('update:modelValue', 'tool')
    await flushPromises()
    await wrapper
      .findAllComponents(ElButton)
      .find((button) => button.text() === '添加节点')!
      .trigger('click')
    expect(wrapper.findComponent({ name: 'NodeProperties' }).props('modelValue')).toMatchObject({
      type: 'tool',
      argsText: '{}',
      onError: 'fail'
    })
    type.vm.$emit('update:modelValue', 'script')
    await flushPromises()
    await wrapper
      .findAllComponents(ElButton)
      .find((button) => button.text() === '添加节点')!
      .trigger('click')
    expect(wrapper.findComponent({ name: 'NodeProperties' }).props('modelValue')).toMatchObject({
      type: 'script',
      scriptInputText: '{}'
    })
  })
  it('加载快照坐标并在保存后保留布局', async () => {
    wrapper = mount(WorkflowDetailPage, { attachTo: document.body })
    await flushPromises()
    expect(wrapper.find('.workflow-canvas-node').attributes('style')).toContain('left: 555px')
    await wrapper
      .findAllComponents(ElButton)
      .find((button) => button.text() === '保存草稿')!
      .trigger('click')
    await flushPromises()
    expect(mocks.snapshotSave).toHaveBeenCalledWith(
      'flow',
      2,
      { start: { x: 555, y: 222 }, end: { x: 888, y: 333 } },
      { zoom: 1, panX: 24, panY: 24 }
    )
    expect(wrapper.find('.workflow-canvas-node').attributes('style')).toContain('left: 555px')
  })
  it('读取已有工具节点不把原来空的参数和失败策略改成新节点默认值', async () => {
    mocks.get.mockResolvedValue({
      ...value,
      definition: {
        ...value.definition,
        nodes: [...value.definition.nodes, { id: 'old-tool', type: 'tool', tool: 'search' }]
      }
    })
    wrapper = mount(WorkflowDetailPage, { attachTo: document.body })
    await flushPromises()
    await wrapper.get('[data-node-id="old-tool"]').trigger('click')
    const current = wrapper.findComponent({ name: 'NodeProperties' }).props('modelValue')
    expect(current.onError).toBeUndefined()
    expect(current.argsText).toBeUndefined()
    expect(wrapper.findAll('[data-node-id]')).toHaveLength(3)
  })
  it('快照保存失败保留新草稿修订供重试', async () => {
    mocks.snapshotSave.mockRejectedValueOnce(new Error('布局保存失败')).mockResolvedValueOnce({})
    wrapper = mount(WorkflowDetailPage, { attachTo: document.body })
    await flushPromises()
    const save = wrapper.findAllComponents(ElButton).find((button) => button.text() === '保存草稿')!
    await save.trigger('click')
    await flushPromises()
    expect(
      Array.from(document.body.querySelectorAll('.el-message__content')).at(-1)?.textContent
    ).toContain('布局保存失败')
    await save.trigger('click')
    await flushPromises()
    expect(mocks.save).toHaveBeenCalledTimes(1)
    expect(mocks.snapshotSave).toHaveBeenLastCalledWith(
      'flow',
      2,
      { start: { x: 555, y: 222 }, end: { x: 888, y: 333 } },
      { zoom: 1, panX: 24, panY: 24 }
    )
    expect(wrapper.find('.workflow-canvas-node').attributes('style')).toContain('left: 555px')
  })
  it('校验后自动显示问题，定位时选择对应节点', async () => {
    wrapper = mount(WorkflowDetailPage, { attachTo: document.body })
    await flushPromises()
    await wrapper
      .findAllComponents(ElButton)
      .find((button) => button.text() === '校验')!
      .trigger('click')
    await flushPromises()
    expect(wrapper.get('[role="tab"][aria-selected="true"]').text()).toContain('校验与变更')
    const target = wrapper.get('[data-node-id="start"]')
    Object.defineProperty(target.element, 'scrollIntoView', { value: vi.fn() })
    await wrapper
      .findAllComponents(ElButton)
      .find((button) => button.text() === '定位问题')!
      .trigger('click')
    await flushPromises()
    expect(target.attributes('aria-pressed')).toBe('true')
    expect(wrapper.get('[role="tab"][aria-selected="true"]').text()).toBe('流程设计')
  })
  it('发布已成功但读回失败时禁用同一草稿的重复发布', async () => {
    mocks.validate.mockResolvedValue({ revision: 1, problems: [] })
    mocks.publish.mockResolvedValue({ definitionVersionId: 11 })
    wrapper = mount(WorkflowDetailPage, { attachTo: document.body })
    await flushPromises()
    mocks.get.mockRejectedValueOnce(new Error('发布结果读取失败'))
    const publish = wrapper.findAllComponents(ElButton).find((button) => button.text() === '发布')!
    await publish.trigger('click')
    await flushPromises()
    expect(
      Array.from(document.body.querySelectorAll('.el-message__content')).at(-1)?.textContent
    ).toContain('发布结果读取失败')
    expect(publish.props('disabled')).toBe(true)
    await publish.trigger('click')
    await flushPromises()
    expect(mocks.publish).toHaveBeenCalledTimes(1)
  })
})
