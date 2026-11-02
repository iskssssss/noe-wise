import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { ElButton, ElFormItem, ElInput, ElRadioGroup, ElSelect } from 'element-plus'
import { createHttpClient, HttpClientError } from '@skillforge/shared/utils/http'
import { useAuthStore } from '../stores/auth'
import WorkflowOnboarding from './WorkflowOnboarding.vue'

const mocks = vi.hoisted(() => ({
  list: vi.fn(),
  instances: vi.fn(),
  history: vi.fn(),
  detail: vi.fn(),
  definitions: vi.fn(),
  versions: vi.fn(),
  deployments: vi.fn(),
  revisions: vi.fn(),
  submit: vi.fn(),
  result: vi.fn(),
  confirm: vi.fn(),
  warning: vi.fn(),
  error: vi.fn(),
  success: vi.fn()
}))
vi.mock('../api', () => ({
  getSessionId: () => 'test-session',
  api: {
    workflowDrafts: {
      list: mocks.list,
      instances: mocks.instances,
      history: mocks.history,
      publicationDetail: mocks.detail
    },
    capabilities: {
      definitions: mocks.definitions,
      versions: mocks.versions,
      deployments: mocks.deployments
    },
    knowledge: { listRevisions: mocks.revisions }
  }
}))
vi.mock('../api/workflowOnboarding', () => ({
  workflowOnboardingApi: { submit: mocks.submit, result: mocks.result }
}))
vi.mock('../stores/auth', async () => {
  const { reactive } = await import('vue')
  const auth = reactive({
    user: { tenant_id: '1', sub: 'member-1', permissions: ['foundation.workflows.read'] },
    identityVersion: 1
  })
  return { useAuthStore: () => auth }
})
vi.mock('@skillforge/shared/feedback', () => ({
  confirm: mocks.confirm,
  message: { success: mocks.success, warning: mocks.warning, error: mocks.error }
}))
vi.mock('vue-router', () => ({ onBeforeRouteLeave: vi.fn(), onBeforeRouteUpdate: vi.fn() }))
const wrappers: VueWrapper[] = []
beforeEach(() => {
  sessionStorage.clear()
  vi.resetAllMocks()
  useAuthStore().identityVersion = 1
  mocks.list.mockResolvedValue({
    items: [
      {
        workflowId: 'flow',
        name: '服务流程',
        status: 'published',
        activeDefinitionVersionId: '9007199254740999'
      }
    ]
  })
  mocks.instances.mockResolvedValue({ items: [] })
  mocks.history.mockResolvedValue([{ current: true, definitionVersionId: '9007199254740999' }])
  mocks.detail.mockResolvedValue({
    workflowId: 'flow',
    current: true,
    definitionVersionId: '9007199254740999',
    definition: { nodes: [], meta: {} }
  })
  mocks.confirm.mockResolvedValue(true)
})
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  document.body.innerHTML = ''
  vi.unstubAllGlobals()
})
async function open(
  mounts: unknown[] = [],
  canCreate = true,
  canUpdate = true,
  refreshAgent = vi.fn().mockResolvedValue(true)
) {
  const wrapper = mount(WorkflowOnboarding, {
    attachTo: document.body,
    props: {
      agentId: 'agent-1',
      mounts: mounts as never,
      canCreate,
      canUpdate,
      canInstall: true,
      refreshAgent
    }
  })
  wrappers.push(wrapper)
  await flushPromises()
  const select = wrapper
    .findAllComponents(ElFormItem)
    .find((item) => item.props('label') === '已发布工作流')!
    .getComponent(ElSelect)
  if (!select.props('modelValue')) {
    select.vm.$emit('update:modelValue', 'flow')
    select.vm.$emit('change', 'flow')
  }
  await flushPromises()
  return wrapper
}
function button(wrapper: VueWrapper, name: string) {
  return wrapper.findAllComponents(ElButton).find((item) => item.text() === name)!
}
function mountPanel() {
  const wrapper = mount(WorkflowOnboarding, {
    attachTo: document.body,
    props: {
      agentId: 'agent-1',
      mounts: [],
      canCreate: true,
      canUpdate: true,
      canInstall: true,
      refreshAgent: vi.fn().mockResolvedValue(true)
    },
    global: { stubs: { RouterLink: { props: ['to'], template: '<a :href="to"><slot /></a>' } } }
  })
  wrappers.push(wrapper)
  return wrapper
}
function workflowSelect(wrapper: VueWrapper) {
  return wrapper
    .findAllComponents(ElFormItem)
    .find((item) => item.props('label') === '已发布工作流')!
    .getComponent(ElSelect)
}
describe('智能体内工作流接入', () => {
  it('原工作流版本变化后切换到另一工作流，不带入原配置', async () => {
    mocks.list.mockResolvedValue({
      items: [
        { workflowId: 'flow', name: '流程 A', status: 'published', activeDefinitionVersionId: '1' },
        { workflowId: 'other', name: '流程 B', status: 'published', activeDefinitionVersionId: '2' }
      ]
    })
    mocks.detail.mockImplementation(async (id: string, version: string) => ({
      workflowId: id,
      current: true,
      definitionVersionId: version,
      definition: {
        nodes: [],
        meta: {
          config_schema: [
            {
              key: 'note',
              type: 'string',
              label: '说明',
              default: id === 'other' ? 'B 默认值' : 'A 默认值'
            }
          ]
        }
      }
    }))
    const wrapper = await open()
    wrapper
      .findAllComponents(ElFormItem)
      .find((item) => item.props('label') === '说明（可选）')!
      .getComponent(ElInput)
      .vm.$emit('update:modelValue', 'A 私有配置')
    mocks.list.mockResolvedValue({
      items: [
        { workflowId: 'flow', name: '流程 A', status: 'published', activeDefinitionVersionId: '3' },
        { workflowId: 'other', name: '流程 B', status: 'published', activeDefinitionVersionId: '2' }
      ]
    })
    await button(wrapper, '刷新工作流选项').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('所选工作流的发布版本或共享安装已变化')
    const select = workflowSelect(wrapper)
    select.vm.$emit('update:modelValue', 'other')
    select.vm.$emit('change', 'other')
    await flushPromises()
    expect(
      wrapper
        .findAllComponents(ElFormItem)
        .find((item) => item.props('label') === '说明（可选）')!
        .getComponent(ElInput)
        .props('modelValue')
    ).toBe('B 默认值')
  })
  it('确认接入弹窗期间收到发布通知，确认后不提交旧目录', async () => {
    const channels: FakeChannel[] = []
    class FakeChannel {
      name: string
      onmessage?: (event: { data: string }) => void
      close = vi.fn()
      constructor(name: string) {
        this.name = name
        channels.push(this)
      }
    }
    vi.stubGlobal('BroadcastChannel', FakeChannel)
    const wrapper = await open()
    let finish!: (value: boolean) => void
    mocks.confirm.mockReturnValueOnce(
      new Promise((resolve) => {
        finish = resolve
      })
    )
    await button(wrapper, '接入智能体').trigger('click')
    await flushPromises()
    channels.find((item) => item.name === 'skillforge.workflow.published')!.onmessage!({
      data: JSON.stringify(['1', 'member-1'])
    })
    finish(true)
    await flushPromises()
    expect(mocks.submit).not.toHaveBeenCalled()
    expect(sessionStorage.length).toBe(0)
    expect(mocks.list).toHaveBeenCalledTimes(2)
  })
  it('首读期间收到同主体发布通知，完成后重读且不自动提交', async () => {
    const channels: FakeChannel[] = []
    class FakeChannel {
      name: string
      onmessage?: (event: { data: string }) => void
      close = vi.fn()
      constructor(name: string) {
        this.name = name
        channels.push(this)
      }
    }
    vi.stubGlobal('BroadcastChannel', FakeChannel)
    let finish!: (value: unknown) => void
    mocks.list.mockReturnValueOnce(
      new Promise((resolve) => {
        finish = resolve
      })
    )
    const wrapper = mountPanel()
    await flushPromises()
    channels.find((item) => item.name === 'skillforge.workflow.published')!.onmessage!({
      data: JSON.stringify(['1', 'member-1'])
    })
    finish({ items: [] })
    await flushPromises()
    expect(mocks.list).toHaveBeenCalledTimes(2)
    expect(workflowSelect(wrapper).props('modelValue')).toBe('flow')
    expect(mocks.submit).not.toHaveBeenCalled()
  })
  it('发布通知读取失败阻止提交，重新读取版本变化后要求确认', async () => {
    const channels: FakeChannel[] = []
    class FakeChannel {
      name: string
      onmessage?: (event: { data: string }) => void
      close = vi.fn()
      constructor(name: string) {
        this.name = name
        channels.push(this)
      }
    }
    vi.stubGlobal('BroadcastChannel', FakeChannel)
    mocks.detail.mockImplementation(async (_id: string, version: string) => ({
      workflowId: 'flow',
      current: true,
      definitionVersionId: version,
      definition: {
        nodes: [],
        meta: { config_schema: [{ key: 'note', type: 'string', label: '说明' }] }
      }
    }))
    const wrapper = await open()
    wrapper
      .findAllComponents(ElFormItem)
      .find((item) => item.props('label') === '说明（可选）')!
      .getComponent(ElInput)
      .vm.$emit('update:modelValue', '保留配置')
    await flushPromises()
    mocks.list.mockRejectedValueOnce(Error('读取失败'))
    channels.find((item) => item.name === 'skillforge.workflow.published')!.onmessage!({
      data: JSON.stringify(['1', 'member-1'])
    })
    await flushPromises()
    expect(button(wrapper, '接入智能体').props('disabled')).toBe(true)
    mocks.list.mockResolvedValue({
      items: [
        {
          workflowId: 'flow',
          name: '服务流程',
          status: 'published',
          activeDefinitionVersionId: '77'
        }
      ]
    })
    await button(wrapper, '重新读取工作流目录').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('所选工作流的发布版本或共享安装已变化')
    await button(wrapper, '重新读取当前工作流').trigger('click')
    await flushPromises()
    expect(
      wrapper
        .findAllComponents(ElFormItem)
        .find((item) => item.props('label') === '说明（可选）')!
        .getComponent(ElInput)
        .props('modelValue')
    ).toBe('保留配置')
    expect(mocks.submit).not.toHaveBeenCalled()
  })
  it('唯一可用发布版本自动选择并只读加载，不自动接入', async () => {
    mocks.list.mockResolvedValueOnce({
      items: [
        {
          workflowId: 'flow',
          name: '服务流程',
          status: 'draft',
          activeDefinitionVersionId: '9007199254740999'
        },
        { workflowId: 'archived', status: 'archived', activeDefinitionVersionId: '4' },
        { workflowId: 'unpublished', status: 'draft', activeDefinitionVersionId: null }
      ]
    })
    const wrapper = mountPanel()
    await flushPromises()
    expect(workflowSelect(wrapper).props('modelValue')).toBe('flow')
    expect(wrapper.text()).toContain('已预选唯一可用的已发布工作流')
    expect(mocks.detail).toHaveBeenCalledExactlyOnceWith('flow', '9007199254740999')
    expect(mocks.submit).not.toHaveBeenCalled()
    expect(button(wrapper, '接入智能体').props('disabled')).toBe(false)
  })
  it('多个可用版本时保留用户选择，不自动读取发布版本', async () => {
    mocks.list.mockResolvedValueOnce({
      items: [
        { workflowId: 'flow', status: 'published', activeDefinitionVersionId: '9007199254740999' },
        { workflowId: 'other', status: 'published', activeDefinitionVersionId: '42' }
      ]
    })
    const wrapper = mountPanel()
    await flushPromises()
    expect(workflowSelect(wrapper).props('modelValue')).toBe('')
    expect(mocks.detail).not.toHaveBeenCalled()
    workflowSelect(wrapper).vm.$emit('update:modelValue', 'flow')
    workflowSelect(wrapper).vm.$emit('change', 'flow')
    await flushPromises()
    expect(mocks.detail).toHaveBeenCalledExactlyOnceWith('flow', '9007199254740999')
    expect(wrapper.text()).not.toContain('已预选唯一可用的已发布工作流')
    expect(mocks.submit).not.toHaveBeenCalled()
  })
  it('存在待核对原请求时不预选或读取新工作流', async () => {
    const request = 'abcdefab-1234-4234-8234-123456789012'
    const key = `skillforge.workflow-onboarding.${JSON.stringify(['1', 'member-1', 'agent-1'])}`
    sessionStorage.setItem(
      key,
      JSON.stringify({ request_id: request, agent_id: 'agent-1', workflow_id: 'flow' })
    )
    mocks.result.mockResolvedValue(undefined)
    const wrapper = mountPanel()
    await flushPromises()
    expect(mocks.result).toHaveBeenCalledWith(request)
    expect(workflowSelect(wrapper).props('modelValue')).toBe('')
    expect(mocks.detail).not.toHaveBeenCalled()
    expect(mocks.submit).not.toHaveBeenCalled()
  })
  it('身份变化后丢弃迟到的唯一候选目录', async () => {
    let resolveList!: (value: unknown) => void
    mocks.list.mockReturnValueOnce(
      new Promise((resolve) => {
        resolveList = resolve
      })
    )
    const wrapper = mountPanel()
    useAuthStore().identityVersion++
    resolveList({
      items: [
        { workflowId: 'flow', status: 'published', activeDefinitionVersionId: '9007199254740999' }
      ]
    })
    await flushPromises()
    expect(workflowSelect(wrapper).props('modelValue')).toBe('')
    expect(mocks.detail).not.toHaveBeenCalled()
    expect(button(wrapper, '重新读取工作流目录').exists()).toBe(true)
  })
  it('目录失败后只在成功重读时预选，不派发写操作', async () => {
    mocks.list.mockRejectedValueOnce(new Error('读取失败'))
    const wrapper = mountPanel()
    await flushPromises()
    expect(workflowSelect(wrapper).props('modelValue')).toBe('')
    expect(mocks.detail).not.toHaveBeenCalled()
    await button(wrapper, '重新读取工作流目录').trigger('click')
    await flushPromises()
    expect(workflowSelect(wrapper).props('modelValue')).toBe('flow')
    expect(mocks.detail).toHaveBeenCalledOnce()
    expect(mocks.submit).not.toHaveBeenCalled()
  })
  it('发布详情在身份或智能体切换后到达时不展示旧配置', async () => {
    let resolveDetail!: (value: unknown) => void
    mocks.detail.mockReturnValueOnce(
      new Promise((resolve) => {
        resolveDetail = resolve
      })
    )
    const wrapper = mountPanel()
    await flushPromises()
    expect(workflowSelect(wrapper).props('modelValue')).toBe('flow')
    await wrapper.setProps({ agentId: 'agent-2' })
    resolveDetail({
      workflowId: 'flow',
      current: true,
      definitionVersionId: '9007199254740999',
      definition: { nodes: [], meta: {} }
    })
    await flushPromises()
    expect(workflowSelect(wrapper).props('modelValue')).toBe('')
    expect(wrapper.findAllComponents(ElButton).some((item) => item.text() === '接入智能体')).toBe(
      false
    )
    expect(mocks.submit).not.toHaveBeenCalled()
  })
  it('同一成员重新认证后冻结已读结果，需按新身份重新读取', async () => {
    const wrapper = mountPanel()
    await flushPromises()
    expect(button(wrapper, '接入智能体').props('disabled')).toBe(false)
    useAuthStore().identityVersion++
    await flushPromises()
    expect(wrapper.findAllComponents(ElButton).some((item) => item.text() === '接入智能体')).toBe(
      false
    )
    expect(button(wrapper, '重新读取工作流目录').exists()).toBe(true)
    expect(mocks.submit).not.toHaveBeenCalled()
  })
  it('身份变化后的旧目录与发布详情失败不污染当前页面反馈', async () => {
    let failList!: (reason: Error) => void
    mocks.list.mockReturnValueOnce(
      new Promise((_resolve, reject) => {
        failList = reject
      })
    )
    const first = mountPanel()
    useAuthStore().identityVersion++
    await flushPromises()
    failList(Error('旧目录失败'))
    await flushPromises()
    expect(mocks.error).not.toHaveBeenCalledWith('旧目录失败')
    first.unmount()
    let failDetail!: (reason: Error) => void
    mocks.detail.mockReturnValueOnce(
      new Promise((_resolve, reject) => {
        failDetail = reject
      })
    )
    mountPanel()
    await flushPromises()
    useAuthStore().identityVersion++
    await flushPromises()
    failDetail(Error('旧详情失败'))
    await flushPromises()
    expect(mocks.error).not.toHaveBeenCalledWith('旧详情失败')
    expect(mocks.submit).not.toHaveBeenCalled()
  })
  it.each(['目录', '发布详情', '依赖'] as const)(
    '智能体切换后旧%s的真实 HTTP 错误不触发全局兜底',
    async (stage) => {
      let respond!: (response: Response) => void
      let failure: HttpClientError | undefined
      const onError = vi.fn()
      vi.stubGlobal(
        'fetch',
        vi.fn(
          () =>
            new Promise<Response>((resolve) => {
              respond = resolve
            })
        )
      )
      const http = createHttpClient({
        base: '/api/v1',
        accessTokenKey: 'workflow-test.access',
        refreshTokenKey: 'workflow-test.refresh',
        authHeader: (token) => ({ Authorization: `Bearer ${token}` }),
        refreshPath: '/auth/refresh',
        isAuthPath: () => false,
        onError
      })
      const failRead = () =>
        http.get('/old-workflow').catch((reason: HttpClientError) => {
          failure = reason
          throw reason
        })
      if (stage === '目录') mocks.list.mockImplementationOnce(failRead)
      if (stage === '发布详情') mocks.detail.mockImplementationOnce(failRead)
      if (stage === '依赖') {
        mocks.detail.mockResolvedValueOnce({
          workflowId: 'flow',
          current: true,
          definitionVersionId: '9007199254740999',
          definition: {
            nodes: [],
            meta: {
              capability_requirements: [
                { slot: 'tool', type: 'tool', capability_id: 'lookup', version_constraint: '*' }
              ]
            }
          }
        })
        mocks.definitions.mockImplementationOnce(failRead)
      }
      const wrapper = mountPanel()
      await flushPromises()
      expect(respond).toBeTypeOf('function')
      await wrapper.setProps({ agentId: 'agent-2' })
      respond(
        new Response(
          JSON.stringify({ code: 'FAIL', message: 'old', data: null, trace_id: 'old-trace' }),
          { status: 503 }
        )
      )
      await flushPromises()
      await new Promise((resolve) => setTimeout(resolve, 0))
      expect(failure).toBeInstanceOf(HttpClientError)
      expect(failure?.hasFeedbackOwner).toBe(true)
      expect(onError).not.toHaveBeenCalled()
      expect(mocks.submit).not.toHaveBeenCalled()
    }
  )
  it('旧目录和旧安装列表先后失败时都接管各自的 HTTP 反馈', async () => {
    const responders: ((response: Response) => void)[] = []
    const failures: HttpClientError[] = []
    const onError = vi.fn()
    vi.stubGlobal(
      'fetch',
      vi.fn(() => new Promise<Response>((resolve) => responders.push(resolve)))
    )
    const http = createHttpClient({
      base: '/api/v1',
      accessTokenKey: 'workflow-test.access',
      refreshTokenKey: 'workflow-test.refresh',
      authHeader: (token) => ({ Authorization: `Bearer ${token}` }),
      refreshPath: '/auth/refresh',
      isAuthPath: () => false,
      onError
    })
    const failRead = (path: string) =>
      http.get(path).catch((reason: HttpClientError) => {
        failures.push(reason)
        throw reason
      })
    mocks.list.mockImplementationOnce(() => failRead('/old-list'))
    mocks.instances.mockImplementationOnce(() => failRead('/old-instances'))
    const wrapper = mountPanel()
    await flushPromises()
    expect(responders).toHaveLength(2)
    await wrapper.setProps({ agentId: 'agent-2' })
    responders[0]!(
      new Response(JSON.stringify({ code: 'FAIL', message: 'old', data: null }), { status: 503 })
    )
    await flushPromises()
    responders[1]!(
      new Response(JSON.stringify({ code: 'FAIL', message: 'old', data: null }), { status: 503 })
    )
    await flushPromises()
    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(failures).toHaveLength(2)
    expect(failures.every((failure) => failure.hasFeedbackOwner)).toBe(true)
    expect(onError).not.toHaveBeenCalled()
    expect(mocks.submit).not.toHaveBeenCalled()
  })
  it('在组合接入中刷新依赖，失败保留配置并可重试', async () => {
    mocks.detail.mockResolvedValue({
      workflowId: 'flow',
      current: true,
      definitionVersionId: '9007199254740999',
      definition: {
        nodes: [],
        meta: {
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
        }
      }
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
        name: '查询',
        versionKey: '1',
        descriptor: {}
      }
    ])
    mocks.deployments.mockResolvedValue([
      { deploymentKey: 'only', toolVersionId: '101', providerType: 'MCP', revision: 3 }
    ])
    const wrapper = await open()
    const note = wrapper
      .findAllComponents(ElFormItem)
      .find((item) => item.props('label') === '说明（可选）')!
      .getComponent(ElInput)
    note.vm.$emit('update:modelValue', '保留配置')
    await flushPromises()
    await button(wrapper, '刷新依赖选项').trigger('click')
    await flushPromises()
    expect(mocks.error).toHaveBeenCalledWith('目录读取失败')
    expect(note.props('modelValue')).toBe('保留配置')
    await button(wrapper, '刷新依赖选项').trigger('click')
    await flushPromises()
    expect(note.props('modelValue')).toBe('保留配置')
    expect(
      wrapper
        .findAllComponents(ElFormItem)
        .find((item) => item.props('label') === '查询工具')!
        .getComponent(ElSelect)
        .props('modelValue')
    ).toBe('101')
    mocks.submit.mockImplementation(async (command) => ({
      request_id: command.request_id,
      agent_id: command.agent_id,
      workflow_id: command.workflow_id,
      status: 'SUCCEEDED',
      instance_id: '7',
      installation_revision: 1,
      mount_id: 'm',
      mount_revision: 1
    }))
    await button(wrapper, '接入智能体').trigger('click')
    await flushPromises()
    expect(mocks.submit).toHaveBeenCalledWith(
      expect.objectContaining({
        config: { note: '保留配置' },
        bindings: expect.objectContaining({
          capabilities: [expect.objectContaining({ tool_deployment_id: 'only' })]
        })
      })
    )
  })
  it('已有工作流但目标刚发布时可原地刷新目录，再选择新工作流', async () => {
    const wrapper = mount(WorkflowOnboarding, {
      attachTo: document.body,
      props: {
        agentId: 'agent-1',
        mounts: [],
        canCreate: true,
        canUpdate: true,
        canInstall: true,
        refreshAgent: vi.fn()
      },
      global: { stubs: { RouterLink: { props: ['to'], template: '<a :href="to"><slot /></a>' } } }
    })
    wrappers.push(wrapper)
    await flushPromises()
    expect(wrapper.find('a[href="/admin/workflows"]').attributes('target')).toBe('_blank')
    mocks.list.mockResolvedValueOnce({
      items: [
        {
          workflowId: 'flow',
          name: '服务流程',
          status: 'published',
          activeDefinitionVersionId: '9007199254740999'
        },
        {
          workflowId: 'new-flow',
          name: '新流程',
          status: 'published',
          activeDefinitionVersionId: '42'
        }
      ]
    })
    await button(wrapper, '刷新工作流选项').trigger('click')
    await flushPromises()
    const select = wrapper
      .findAllComponents(ElFormItem)
      .find((item) => item.props('label') === '已发布工作流')!
      .getComponent(ElSelect)
    expect(
      select.findAllComponents({ name: 'ElOption' }).map((item) => item.props('value'))
    ).toEqual(['flow', 'new-flow'])
    expect(mocks.list).toHaveBeenCalledTimes(2)
    expect(mocks.instances).toHaveBeenCalledTimes(2)
    expect(mocks.submit).not.toHaveBeenCalled()
    mocks.detail.mockResolvedValueOnce({
      workflowId: 'new-flow',
      current: true,
      definitionVersionId: '42',
      definition: { nodes: [], meta: {} }
    })
    select.vm.$emit('update:modelValue', 'new-flow')
    select.vm.$emit('change', 'new-flow')
    await flushPromises()
    expect(mocks.detail).toHaveBeenCalledWith('new-flow', '42')
    expect(mocks.history).not.toHaveBeenCalled()
    expect(
      wrapper.findAllComponents(ElButton).some((item) => item.text() === '刷新工作流选项')
    ).toBe(true)
  })
  it('选择时目录版本已过期，刷新后才可接入', async () => {
    mocks.detail.mockResolvedValueOnce({
      workflowId: 'flow',
      current: false,
      definitionVersionId: '9007199254740999',
      definition: { nodes: [], meta: {} }
    })
    const wrapper = await open()
    expect(wrapper.text()).toContain('所选工作流的发布版本或共享安装已变化')
    expect(mocks.submit).not.toHaveBeenCalled()
    await button(wrapper, '重新读取当前工作流').trigger('click')
    await flushPromises()
    expect(mocks.list).toHaveBeenCalledTimes(2)
    expect(button(wrapper, '接入智能体').props('disabled')).toBe(false)
  })
  it('选中工作流后仍可刷新目录，版本变化时暂停提交并要求重新读取', async () => {
    const wrapper = await open()
    await button(wrapper, '刷新工作流选项').trigger('click')
    await flushPromises()
    expect(button(wrapper, '接入智能体').props('disabled')).toBe(false)
    expect(mocks.detail).toHaveBeenCalledOnce()
    mocks.list.mockResolvedValueOnce({
      items: [
        {
          workflowId: 'flow',
          name: '服务流程',
          status: 'published',
          activeDefinitionVersionId: '42'
        }
      ]
    })
    await button(wrapper, '刷新工作流选项').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('所选工作流的发布版本或共享安装已变化')
    expect(wrapper.findAllComponents(ElButton).some((item) => item.text() === '接入智能体')).toBe(
      false
    )
    expect(button(wrapper, '重新读取当前工作流').exists()).toBe(true)
    expect(mocks.submit).not.toHaveBeenCalled()
  })
  it('选中后刷新目录失败保留选择并暂停提交，重试成功后继续', async () => {
    const wrapper = await open()
    mocks.list.mockRejectedValueOnce(new Error('目录读取失败'))
    await button(wrapper, '刷新工作流选项').trigger('click')
    await flushPromises()
    expect(button(wrapper, '接入智能体').props('disabled')).toBe(true)
    expect(mocks.submit).not.toHaveBeenCalled()
    await button(wrapper, '重新读取工作流目录').trigger('click')
    await flushPromises()
    expect(button(wrapper, '接入智能体').props('disabled')).toBe(false)
    expect(mocks.detail).toHaveBeenCalledOnce()
  })
  it('没有已发布工作流时提供工作流入口，发布后可在原地刷新并接入', async () => {
    mocks.list.mockResolvedValueOnce({ items: [] })
    const wrapper = mount(WorkflowOnboarding, {
      attachTo: document.body,
      props: {
        agentId: 'agent-1',
        mounts: [],
        canCreate: true,
        canUpdate: true,
        canInstall: true,
        refreshAgent: vi.fn()
      },
      global: { stubs: { RouterLink: { props: ['to'], template: '<a :href="to"><slot /></a>' } } }
    })
    wrappers.push(wrapper)
    await flushPromises()
    expect(wrapper.text()).toContain('当前没有可接入的已发布工作流')
    expect(wrapper.find('a[href="/admin/workflows"]').exists()).toBe(true)
    await button(wrapper, '刷新工作流选项').trigger('click')
    await flushPromises()
    expect(wrapper.text()).not.toContain('当前没有可接入的已发布工作流')
    const select = wrapper
      .findAllComponents(ElFormItem)
      .find((item) => item.props('label') === '已发布工作流')!
      .getComponent(ElSelect)
    expect(select.props('modelValue')).toBe('flow')
    expect(mocks.detail).toHaveBeenCalledOnce()
    expect(button(wrapper, '接入智能体').props('disabled')).toBe(false)
  })

  it('目录读取失败不误报没有已发布工作流', async () => {
    mocks.list.mockRejectedValueOnce(new Error('工作流读取失败'))
    const wrapper = mount(WorkflowOnboarding, {
      attachTo: document.body,
      props: {
        agentId: 'agent-1',
        mounts: [],
        canCreate: true,
        canUpdate: true,
        canInstall: true,
        refreshAgent: vi.fn()
      }
    })
    wrappers.push(wrapper)
    await flushPromises()
    expect(wrapper.text()).not.toContain('当前没有可接入的已发布工作流')
    expect(button(wrapper, '重新读取工作流目录').exists()).toBe(true)
  })

  it('只有其他实例时按后端默认安装缺失处理', async () => {
    mocks.instances.mockResolvedValue({
      items: [
        {
          id: '8',
          workflowId: 'flow',
          instanceId: 'other',
          installationRevision: '9',
          config: {},
          customPrompt: ''
        }
      ]
    })
    const wrapper = await open()
    mocks.submit.mockResolvedValue({ status: 'PROCESSING', request_id: 'not-matching' })
    await button(wrapper, '接入智能体').trigger('click')
    await flushPromises()
    expect(mocks.submit.mock.calls[0]![0]).toMatchObject({
      mode: 'INSTALL',
      expected_installation_revision: 0
    })
  })
  it('同一工作流存在其他实例时使用后端指定的机构默认安装', async () => {
    mocks.instances.mockResolvedValue({
      items: [
        {
          id: '8',
          workflowId: 'flow',
          instanceId: 'other',
          installationRevision: '9',
          config: {},
          customPrompt: ''
        },
        {
          id: '7',
          workflowId: 'flow',
          instanceId: 'flow_1',
          installationRevision: '4',
          config: {},
          customPrompt: ''
        }
      ]
    })
    const wrapper = await open()
    expect(wrapper.text()).toContain('flow_1 · 修订 4')
    wrapper.getComponent(ElRadioGroup).vm.$emit('update:modelValue', 'REUSE')
    await flushPromises()
    mocks.submit.mockResolvedValue({ status: 'PROCESSING', request_id: 'not-matching' })
    await button(wrapper, '接入智能体').trigger('click')
    await flushPromises()
    expect(mocks.submit.mock.calls[0]![0]).toMatchObject({
      mode: 'REUSE',
      expected_installation_revision: 4
    })
  })
  it('一次提交安装及挂载，保留精确发布身份并以回执刷新', async () => {
    mocks.submit.mockImplementation(async (command) => ({
      request_id: command.request_id,
      agent_id: command.agent_id,
      workflow_id: command.workflow_id,
      status: 'SUCCEEDED',
      instance_id: '9007199254740997',
      installation_revision: 1,
      mount_id: 'mount-1',
      mount_revision: 1
    }))
    const wrapper = await open()
    await button(wrapper, '接入智能体').trigger('click')
    await flushPromises()
    expect(mocks.submit).toHaveBeenCalledOnce()
    expect(mocks.submit.mock.calls[0]![0]).toMatchObject({
      agent_id: 'agent-1',
      workflow_id: 'flow',
      expected_definition_version_id: '9007199254740999',
      mode: 'INSTALL',
      expected_installation_revision: 0,
      bindings: { capabilities: [], workflows: [], agents: [] }
    })
    expect(wrapper.emitted('confirmed')).toHaveLength(1)
  })
  it('共享安装明确区分沿用与更新，并以当前挂载修订更新', async () => {
    mocks.instances.mockResolvedValue({
      items: [
        {
          id: '9007199254740997',
          workflowId: 'flow',
          instanceId: 'flow_1',
          installationRevision: '4',
          status: 'published',
          config: {},
          customPrompt: ''
        }
      ]
    })
    mocks.submit.mockImplementation(async (command) => ({
      request_id: command.request_id,
      agent_id: command.agent_id,
      workflow_id: command.workflow_id,
      status: 'SUCCEEDED',
      instance_id: '9007199254740997',
      installation_revision: 4,
      mount_id: 'mount-1',
      mount_revision: 6
    }))
    const wrapper = await open([
      {
        capabilityType: 'WORKFLOW',
        workflowConfig: { workflowInstanceId: '9007199254740997' },
        mountId: 'mount-1',
        revision: 5
      }
    ])
    expect(button(wrapper, '更新接入').props('disabled')).toBe(true)
    wrapper.getComponent(ElRadioGroup).vm.$emit('update:modelValue', 'REUSE')
    await flushPromises()
    await button(wrapper, '更新接入').trigger('click')
    await flushPromises()
    expect(mocks.submit.mock.calls[0]![0]).toMatchObject({
      mode: 'REUSE',
      expected_installation_revision: 4,
      mount_id: 'mount-1',
      expected_mount_revision: 5
    })
  })
  it('沿用已有安装不读取知识槽候选或知识修订', async () => {
    mocks.instances.mockResolvedValue({
      items: [
        {
          id: '7',
          workflowId: 'flow',
          instanceId: 'flow_1',
          installationRevision: '4',
          status: 'published',
          config: {},
          customPrompt: ''
        }
      ]
    })
    mocks.detail.mockResolvedValue({
      workflowId: 'flow',
      current: true,
      definitionVersionId: '9007199254740999',
      definition: {
        nodes: [],
        meta: {
          capability_requirements: [
            {
              slot: 's',
              label: '知识',
              type: 'knowledge_source',
              capability_id: 'kb',
              version_constraint: '*'
            }
          ]
        }
      }
    })
    const wrapper = await open()
    wrapper.getComponent(ElRadioGroup).vm.$emit('update:modelValue', 'REUSE')
    await flushPromises()
    expect(mocks.definitions).not.toHaveBeenCalled()
    expect(mocks.revisions).not.toHaveBeenCalled()
  })
  it('安装只读取用户选中知识槽的修订', async () => {
    mocks.detail.mockResolvedValue({
      workflowId: 'flow',
      current: true,
      definitionVersionId: '9007199254740999',
      definition: {
        nodes: [],
        meta: {
          capability_requirements: [
            {
              slot: 'selected',
              label: '已选知识',
              type: 'knowledge_source',
              capability_id: 'kb-a',
              version_constraint: '*'
            },
            {
              slot: 'optional',
              label: '可选知识',
              type: 'knowledge_source',
              capability_id: 'kb-b',
              version_constraint: '*',
              required: false
            }
          ]
        }
      }
    })
    mocks.definitions.mockResolvedValue([
      {
        id: '10',
        capabilityType: 'KNOWLEDGE_SOURCE',
        capabilityKey: 'kb-a',
        defaultVersionId: '11'
      },
      {
        id: '20',
        capabilityType: 'KNOWLEDGE_SOURCE',
        capabilityKey: 'kb-b',
        defaultVersionId: '21'
      }
    ])
    mocks.versions.mockImplementation(async (id) =>
      id === '10'
        ? [
            {
              id: '11',
              definitionId: '10',
              capabilityType: 'KNOWLEDGE_SOURCE',
              versionKey: '1',
              descriptor: { source_id: '100' }
            }
          ]
        : [
            {
              id: '21',
              definitionId: '20',
              capabilityType: 'KNOWLEDGE_SOURCE',
              versionKey: '1',
              descriptor: { source_id: '200' }
            }
          ]
    )
    mocks.revisions.mockResolvedValue({
      items: [{ status: 'published', capability_version_id: '11', revision: 3 }]
    })
    mocks.submit.mockImplementation(async (command) => ({
      request_id: command.request_id,
      agent_id: command.agent_id,
      workflow_id: command.workflow_id,
      status: 'SUCCEEDED',
      instance_id: '7',
      installation_revision: 1,
      mount_id: 'm',
      mount_revision: 1
    }))
    const wrapper = await open()
    expect(mocks.revisions).not.toHaveBeenCalled()
    const selected = wrapper
      .findAllComponents(ElFormItem)
      .find((item) => item.props('label') === '已选知识')!
      .getComponent(ElSelect)
    expect(selected.props('modelValue')).toBe('11')
    const optional = wrapper
      .findAllComponents(ElFormItem)
      .find((item) => item.props('label') === '可选知识')!
      .getComponent(ElSelect)
    expect(optional.props('modelValue')).toBeUndefined()
    await button(wrapper, '接入智能体').trigger('click')
    await flushPromises()
    expect(mocks.revisions).toHaveBeenCalledExactlyOnceWith('100')
    expect(mocks.submit.mock.calls[0]![0].bindings.capabilities).toHaveLength(1)
  })
  it('成功后安装列表刷新失败保留回执，禁止再次提交并提供只读重试', async () => {
    const failure = new HttpClientError({ status: 500, userMessage: '读取失败' })
    mocks.instances.mockResolvedValueOnce({ items: [] }).mockRejectedValueOnce(failure)
    mocks.submit.mockImplementation(async (command) => ({
      request_id: command.request_id,
      agent_id: command.agent_id,
      workflow_id: command.workflow_id,
      status: 'SUCCEEDED',
      instance_id: '7',
      installation_revision: 1,
      mount_id: 'm',
      mount_revision: 1
    }))
    const wrapper = await open()
    await button(wrapper, '接入智能体').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('接入已确认')
    expect(failure.hasFeedbackOwner).toBe(true)
    expect(mocks.warning).toHaveBeenCalledTimes(1)
    expect(button(wrapper, '重新读取安装列表').exists()).toBe(true)
    expect(mocks.submit).toHaveBeenCalledOnce()
    mocks.instances.mockResolvedValueOnce({
      items: [
        {
          id: '7',
          workflowId: 'flow',
          instanceId: 'flow_1',
          installationRevision: '1',
          config: {},
          customPrompt: ''
        }
      ]
    })
    await button(wrapper, '重新读取安装列表').trigger('click')
    await flushPromises()
    expect(mocks.submit).toHaveBeenCalledOnce()
    expect(button(wrapper, '重新选择当前工作流').exists()).toBe(true)
  })
  it('确定拒绝后可重新读取实例并再次明确选择，不自动重发', async () => {
    mocks.submit.mockImplementation(async (command) => ({
      request_id: command.request_id,
      agent_id: command.agent_id,
      workflow_id: command.workflow_id,
      status: 'REJECTED',
      error_message: '安装修订已变化'
    }))
    const wrapper = await open()
    await button(wrapper, '接入智能体').trigger('click')
    await flushPromises()
    mocks.instances.mockResolvedValueOnce({
      items: [
        {
          id: '7',
          workflowId: 'flow',
          instanceId: 'flow_1',
          installationRevision: '2',
          status: 'published',
          config: {},
          customPrompt: ''
        }
      ]
    })
    await button(wrapper, '重新读取并选择工作流').trigger('click')
    await flushPromises()
    expect(mocks.submit).toHaveBeenCalledOnce()
    expect(wrapper.text()).toContain('已有共享安装')
    expect(button(wrapper, '接入智能体').props('disabled')).toBe(true)
  })
  it('挂载修订过期后必须重新读取 Agent，读取失败时不能提交，成功后使用新修订', async () => {
    const instance = {
      id: '7',
      workflowId: 'flow',
      instanceId: 'flow_1',
      installationRevision: '4',
      status: 'published',
      config: {},
      customPrompt: ''
    }
    const mount = {
      capabilityType: 'WORKFLOW' as const,
      capabilityDefinitionId: '8',
      capabilityVersionId: '9',
      priority: 0,
      status: 'ACTIVE' as const,
      skillConfig: null,
      toolConfig: null,
      knowledgeConfig: null,
      workflowConfig: { workflowInstanceId: '7' },
      mountId: 'm',
      revision: 5
    }
    mocks.instances.mockResolvedValue({ items: [instance] })
    mocks.submit.mockImplementation(async (command) => ({
      request_id: command.request_id,
      agent_id: command.agent_id,
      workflow_id: command.workflow_id,
      status: 'REJECTED',
      error_message: '挂载修订已过期'
    }))
    const refreshAgent = vi
      .fn()
      .mockResolvedValueOnce(false)
      .mockImplementation(async () => {
        await wrapper.setProps({ mounts: [{ ...mount, revision: 6 }] })
        return true
      })
    const wrapper = await open([mount], true, true, refreshAgent)
    wrapper.getComponent(ElRadioGroup).vm.$emit('update:modelValue', 'REUSE')
    await flushPromises()
    await button(wrapper, '更新接入').trigger('click')
    await flushPromises()
    expect(mocks.submit.mock.calls[0]![0].expected_mount_revision).toBe(5)
    expect(button(wrapper, '更新接入').props('disabled')).toBe(true)
    await button(wrapper, '重新读取并选择工作流').trigger('click')
    await flushPromises()
    expect(button(wrapper, '更新接入').props('disabled')).toBe(true)
    expect(mocks.submit).toHaveBeenCalledOnce()
    mocks.instances.mockRejectedValueOnce(new Error('安装目录读取失败'))
    await button(wrapper, '重新读取并选择工作流').trigger('click')
    await flushPromises()
    expect(button(wrapper, '更新接入').props('disabled')).toBe(true)
    expect(mocks.submit).toHaveBeenCalledOnce()
    await button(wrapper, '重新读取并选择工作流').trigger('click')
    await flushPromises()
    wrapper.getComponent(ElRadioGroup).vm.$emit('update:modelValue', 'REUSE')
    await flushPromises()
    await button(wrapper, '更新接入').trigger('click')
    await flushPromises()
    expect(mocks.submit).toHaveBeenCalledTimes(2)
    expect(mocks.submit.mock.calls[1]![0].expected_mount_revision).toBe(6)
  })
  it('写入未知时冻结原命令，只通过原请求 ID 核对，不再派发写入', async () => {
    mocks.submit.mockRejectedValueOnce(new Error('连接中断'))
    const wrapper = await open()
    await button(wrapper, '接入智能体').trigger('click')
    await flushPromises()
    const command = mocks.submit.mock.calls[0]![0]
    expect(wrapper.text()).toContain(command.request_id)
    expect(button(wrapper, '接入智能体').props('disabled')).toBe(true)
    mocks.result.mockResolvedValueOnce(undefined)
    await button(wrapper, '核对原操作').trigger('click')
    await flushPromises()
    expect(mocks.result).toHaveBeenCalledWith(command.request_id)
    expect(mocks.submit).toHaveBeenCalledOnce()
    expect(button(wrapper, '接入智能体').props('disabled')).toBe(true)
  })
  it('刷新后自动核对原请求，仅保存身份且不重复发送', async () => {
    mocks.submit.mockRejectedValueOnce(new Error('连接中断'))
    const first = await open()
    await button(first, '接入智能体').trigger('click')
    await flushPromises()
    const command = mocks.submit.mock.calls[0]![0]
    const key = `skillforge.workflow-onboarding.${JSON.stringify(['1', 'member-1', 'agent-1'])}`
    expect(JSON.parse(sessionStorage.getItem(key)!)).toEqual({
      request_id: command.request_id,
      agent_id: 'agent-1',
      workflow_id: 'flow'
    })
    first.unmount()
    wrappers.pop()
    mocks.instances.mockResolvedValue({ items: [{ id: '7', workflowId: 'flow' }] })
    mocks.result.mockResolvedValue({
      request_id: command.request_id,
      agent_id: 'agent-1',
      workflow_id: 'flow',
      status: 'SUCCEEDED',
      instance_id: '7',
      installation_revision: 1,
      mount_id: 'm',
      mount_revision: 1
    })
    const restored = mount(WorkflowOnboarding, {
      attachTo: document.body,
      props: {
        agentId: 'agent-1',
        mounts: [],
        canCreate: true,
        canUpdate: true,
        canInstall: true,
        refreshAgent: vi.fn()
      }
    })
    wrappers.push(restored)
    await flushPromises()
    expect(mocks.result).toHaveBeenCalledOnce()
    expect(mocks.result).toHaveBeenCalledWith(command.request_id)
    expect(mocks.submit).toHaveBeenCalledOnce()
    expect(restored.emitted('confirmed')).toHaveLength(1)
    expect(sessionStorage.getItem(key)).toBeNull()
    expect(restored.text()).toContain('接入已确认')
  })
  it('自动核对未查到或回执不匹配时保留原身份和锁定', async () => {
    const id = 'abcdefab-1234-4234-8234-123456789012'
    const key = `skillforge.workflow-onboarding.${JSON.stringify(['1', 'member-1', 'agent-1'])}`
    sessionStorage.setItem(
      key,
      JSON.stringify({ request_id: id, agent_id: 'agent-1', workflow_id: 'flow' })
    )
    mocks.result
      .mockResolvedValueOnce(undefined)
      .mockResolvedValueOnce({
        request_id: id,
        agent_id: 'agent-1',
        workflow_id: 'flow',
        status: 'PROCESSING'
      })
      .mockResolvedValueOnce({
        request_id: id,
        agent_id: 'agent-1',
        workflow_id: 'other',
        status: 'REJECTED',
        error_message: '拒绝'
      })
    const wrapper = await open()
    expect(mocks.result).toHaveBeenCalledWith(id)
    expect(wrapper.text()).toContain(id)
    expect(button(wrapper, '接入智能体').props('disabled')).toBe(true)
    await button(wrapper, '核对原操作').trigger('click')
    await flushPromises()
    expect(sessionStorage.getItem(key)).not.toBeNull()
    await button(wrapper, '核对原操作').trigger('click')
    await flushPromises()
    expect(sessionStorage.getItem(key)).not.toBeNull()
    mocks.result.mockRejectedValueOnce(new Error('查询失败'))
    await button(wrapper, '核对原操作').trigger('click')
    await flushPromises()
    expect(sessionStorage.getItem(key)).not.toBeNull()
    expect(mocks.submit).not.toHaveBeenCalled()
    expect(wrapper.emitted('confirmed')).toBeUndefined()
  })
  it('保存请求身份失败时不发送接入请求', async () => {
    const wrapper = await open()
    const spy = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw Error('storage')
    })
    await button(wrapper, '接入智能体').trigger('click')
    await flushPromises()
    expect(mocks.submit).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('本次未发送')
    spy.mockRestore()
  })
  it('终态回执无法清除原身份时保持锁定并允许再次核对', async () => {
    const id = 'abcdefab-1234-4234-8234-123456789012'
    const key = `skillforge.workflow-onboarding.${JSON.stringify(['1', 'member-1', 'agent-1'])}`
    sessionStorage.setItem(
      key,
      JSON.stringify({ request_id: id, agent_id: 'agent-1', workflow_id: 'flow' })
    )
    mocks.result.mockResolvedValue({
      request_id: id,
      agent_id: 'agent-1',
      workflow_id: 'flow',
      status: 'REJECTED',
      error_message: '拒绝'
    })
    const spy = vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => {
      throw Error('storage')
    })
    const wrapper = await open()
    expect(wrapper.text()).toContain('无法清除原工作流接入记录')
    expect(sessionStorage.getItem(key)).not.toBeNull()
    expect(button(wrapper, '接入智能体').props('disabled')).toBe(true)
    spy.mockRestore()
    await button(wrapper, '核对原操作').trigger('click')
    await flushPromises()
    expect(sessionStorage.getItem(key)).toBeNull()
    expect(mocks.result).toHaveBeenCalledTimes(2)
    expect(mocks.submit).not.toHaveBeenCalled()
  })
  it('不同智能体不会读取原请求', async () => {
    const key = `skillforge.workflow-onboarding.${JSON.stringify(['1', 'member-1', 'other-agent'])}`
    sessionStorage.setItem(
      key,
      JSON.stringify({
        request_id: 'abcdefab-1234-4234-8234-123456789012',
        agent_id: 'other-agent',
        workflow_id: 'flow'
      })
    )
    await open()
    expect(mocks.result).not.toHaveBeenCalled()
    expect(sessionStorage.getItem(key)).not.toBeNull()
  })
  it('不支持的工作流节点明确阻止接入，不提交空绑定', async () => {
    mocks.detail.mockResolvedValue({
      workflowId: 'flow',
      current: true,
      definitionVersionId: '9007199254740999',
      definition: { nodes: [{ id: 'child', type: 'subgraph' }], meta: {} }
    })
    const wrapper = await open()
    expect(wrapper.text()).toContain('当前版本暂不支持执行 subgraph')
    expect(mocks.submit).not.toHaveBeenCalled()
    expect(wrapper.findAllComponents(ElButton).some((item) => item.text() === '接入智能体')).toBe(
      false
    )
  })
  it('只读回执入口不读取目录、不授予写入能力', async () => {
    const id = 'abcdefab-1234-4234-8234-123456789012'
    mocks.result.mockResolvedValue({
      request_id: id,
      agent_id: 'agent-1',
      workflow_id: 'flow',
      status: 'SUCCEEDED',
      instance_id: '7',
      installation_revision: 1,
      mount_id: 'm',
      mount_revision: 1
    })
    const wrapper = mount(WorkflowOnboarding, {
      attachTo: document.body,
      props: {
        agentId: 'agent-1',
        mounts: [],
        canCreate: false,
        canUpdate: false,
        canInstall: false,
        refreshAgent: vi.fn(),
        receiptOnly: true
      }
    })
    wrappers.push(wrapper)
    await wrapper.get('input').setValue(id.toUpperCase())
    await button(wrapper, '核对原操作').trigger('click')
    await flushPromises()
    expect(mocks.result).toHaveBeenCalledWith(id)
    expect(mocks.list).not.toHaveBeenCalled()
    expect(mocks.submit).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('接入已确认')
  })
})
