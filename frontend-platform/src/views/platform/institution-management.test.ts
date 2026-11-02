import { defineComponent, reactive, computed, type Component } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { ElButton, ElSelect, ElOption } from 'element-plus'
import { AgentConfigFields, EntitySelect } from '@skillforge/shared/admin'
import { HttpClientError } from '@skillforge/shared/utils/http'
import { createPermissionDirective } from '@skillforge/shared/directives/permission'
import InstitutionHierarchyPanel from './InstitutionHierarchyPanel.vue'
import InstitutionRelationsPage from './InstitutionRelationsPage.vue'
import AgentTemplateDetailPage from './AgentTemplateDetailPage.vue'
import type { AgentTemplateDetail, AgentDefinitionImpact } from '../../api'

const mocks = vi.hoisted(() => ({
  auth: undefined as unknown as {
    identityVersion: number
    user: { sub: string; permissions: string[] }
  },
  relations: vi.fn(),
  change: vi.fn(),
  detail: vi.fn(),
  history: vi.fn(),
  impact: vi.fn(),
  previewContent: vi.fn(),
  previewSaved: vi.fn(),
  update: vi.fn(),
  publish: vi.fn(),
  deprecate: vi.fn(),
  restore: vi.fn(),
  validate: vi.fn(),
  models: vi.fn(),
  confirm: vi.fn(),
  resolveTenant: vi.fn()
}))
vi.mock('../../api/entityOptions', () => ({
  createOperationOptions: () => ({
    load: vi.fn().mockResolvedValue([]),
    resolve: mocks.resolveTenant
  })
}))
vi.mock('../../stores/auth', () => ({ useAuthStore: () => mocks.auth }))
vi.mock('../../api', () => ({
  api: {
    institutionRelations: { list: mocks.relations, change: mocks.change },
    admin: { models: { list: mocks.models } },
    capabilities: { listDefinitions: async () => [] },
    agentTemplates: {
      detail: mocks.detail,
      history: mocks.history,
      impact: mocks.impact,
      previewContent: mocks.previewContent,
      previewSaved: mocks.previewSaved,
      update: mocks.update,
      publish: mocks.publish,
      deprecate: mocks.deprecate,
      restore: mocks.restore,
      validate: mocks.validate
    }
  }
}))
vi.mock('@skillforge/shared/feedback', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@skillforge/shared/feedback')>()),
  confirm: mocks.confirm,
  message: { success: vi.fn(), warning: vi.fn() }
}))
const wrappers: VueWrapper[] = []
const child = '9223372036854775807'
const definitionId = '9223372036854775806'
const definitionPath = '/platform/agent-templates/' + definitionId
const permissions = [
  'platform.institution_relations.read',
  'platform.institution_relations.attach',
  'platform.institution_relations.move',
  'platform.institution_relations.detach',
  'platform.agent_definitions.read',
  'platform.agent_definitions.edit',
  'platform.agent_definitions.validate',
  'platform.agent_definitions.publish',
  'platform.agent_definitions.deprecate',
  'platform.agent_definitions.restore'
]
function relations(revision = 2) {
  return {
    items: [
      {
        tenant_id: '7',
        parent_tenant_id: null,
        name: '原总部',
        status: 'active',
        revision: 1,
        tree_revision: 8
      },
      {
        tenant_id: '8',
        parent_tenant_id: null,
        name: '目标总部',
        status: 'active',
        revision: 1,
        tree_revision: 8
      },
      {
        tenant_id: child,
        parent_tenant_id: '7',
        name: '直属门店',
        status: 'active',
        revision,
        tree_revision: 8
      }
    ],
    total: 3,
    page: 1,
    page_size: 200,
    tree_revision: 8
  }
}
function detail(revision = 2): AgentTemplateDetail {
  return {
    definition_id: definitionId,
    draft_revision: revision,
    owner_kind: 'platform',
    owner_tenant_id: null,
    distribution_policy: 'AUTHORIZED_DEPLOY',
    current_published_revision: 'v1',
    template_key: 'public',
    name: '公共接诊助手',
    version: 'v1',
    status: 'published',
    has_unpublished_changes: true,
    definition: {
      system_prompt: '服务端提示词',
      role_description: '',
      model_config_id: 'model-1',
      execution_config: {
        max_business_model_calls: null,
        max_tool_calls: null,
        max_knowledge_calls: null,
        max_total_tokens: null,
        max_wall_time_seconds: null
      },
      capabilities: [],
      resource_slots: [],
      delegation_slots: []
    }
  }
}
function impact(revision = 2): AgentDefinitionImpact {
  return {
    definition_id: definitionId,
    draft_revision: revision,
    current_published_revision: 'v1',
    visible_deployment_count: 1,
    changes: {
      added_resource_slots: [
        { key: 'records', label: '就诊记录', description: '', capability_type: 'TOOL' }
      ],
      changed_resource_slots: [],
      removed_resource_slot_keys: [],
      added_delegation_slots: [],
      removed_delegation_slot_keys: [],
      added_parameter_keys: [],
      removed_parameter_keys: []
    },
    deployments: [
      {
        tenant_id: child,
        deployment_id: '9223372036854775805',
        agent_id: '9223372036854775804',
        deployment_revision: 3,
        current_declarations_satisfied: true,
        draft_declarations_satisfied: false,
        current_issues: [],
        draft_issues: [
          { path: 'resources.records', code: 'REQUIRED', message: '请绑定就诊记录工具' }
        ]
      }
    ]
  }
}
function failure(status: number) {
  return new HttpClientError({
    status,
    userMessage: status === 409 ? '修订冲突' : '请求结果待核对'
  })
}
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => {
    resolve = done
  })
  return { promise, resolve }
}
async function open(component: Component, path = '/platform/tenants', stubSetup = false) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      {
        path: path.startsWith('/platform/agent-templates/')
          ? '/platform/agent-templates/:id'
          : path,
        component
      },
      { path: '/platform/agent-templates', component: { template: '<div />' } }
    ]
  })
  await router.push(path)
  await router.isReady()
  const wrapper = mount(defineComponent({ template: '<RouterView />' }), {
    attachTo: document.body,
    global: {
      plugins: [router],
      stubs: stubSetup ? { InstitutionAgentSetup: true } : {},
      directives: {
        permission: createPermissionDirective(() => computed(() => mocks.auth.user.permissions))
      }
    }
  })
  wrappers.push(wrapper)
  await flushPromises()
  return wrapper
}
function button(wrapper: VueWrapper, label: string) {
  const result = wrapper.findAllComponents(ElButton).find((item) => item.text() === label)
  if (!result) throw Error(label)
  if (!result.element.isConnected) throw Error('当前动作没有出现在真实DOM：' + label)
  return result
}
function editPrompt(wrapper: VueWrapper, prompt: string) {
  const fields = wrapper.getComponent(AgentConfigFields)
  fields.vm.$emit('update:modelValue', { ...formModel(wrapper), system_prompt: prompt })
}
/** 泛型配置组件按真实公开modelValue契约读取，避免依赖页面私有ref。 */
function formModel(wrapper: VueWrapper) {
  return wrapper.getComponent({ name: 'AgentConfigFields' }).props('modelValue') as {
    name: string
    description: string
    system_prompt: string
    model_config_id: string | null
  }
}
beforeEach(() => {
  vi.clearAllMocks()
  mocks.resolveTenant.mockReset()
  sessionStorage.clear()
  mocks.auth = reactive({
    identityVersion: 1,
    user: { sub: 'current-platform-actor', permissions: [...permissions] }
  })
  mocks.relations.mockResolvedValue(relations())
  mocks.change.mockResolvedValue(relations(3).items[2])
  mocks.detail.mockResolvedValue(detail())
  mocks.history.mockResolvedValue([detail(1)])
  mocks.impact.mockResolvedValue(impact())
  mocks.validate.mockResolvedValue({ valid: true, issues: [] })
  mocks.previewSaved.mockResolvedValue({
    validation: { valid: true, issues: [] },
    impact: impact()
  })
  mocks.models.mockResolvedValue([{ id: 'model-1', name: '当前模型', kind: 'LLM', enabled: true }])
  mocks.update.mockResolvedValue(detail(3))
  mocks.publish.mockResolvedValue(detail(3))
  mocks.restore.mockResolvedValue(detail(3))
  mocks.confirm.mockResolvedValue(true)
})
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  document.body.innerHTML = ''
})

describe('平台机构关系实际消费者', () => {
  it('独立机构层级入口只需关系读取权限，不依赖原机构管理目录', async () => {
    mocks.auth.user.permissions = ['platform.institution_relations.read']
    const wrapper = await open(InstitutionRelationsPage, '/platform/institution-relations')
    expect(wrapper.get('h1').text()).toBe('机构层级')
    expect(wrapper.text()).toContain('直属门店')
    expect(wrapper.findAll('button').some((item) => item.text() === '迁移上级')).toBe(false)
    expect(mocks.relations).toHaveBeenCalled()
    expect(mocks.change).not.toHaveBeenCalled()
  })
  it('按名称快速定位机构，并从上级候选排除自己及整个子树', async () => {
    const wrapper = await open(InstitutionHierarchyPanel)
    await wrapper.get('input[aria-label="搜索可见机构"]').setValue('直属门店')
    expect(wrapper.text()).toContain('找到 1 家机构')
    await wrapper.get('input[aria-label="搜索可见机构"]').setValue('')
    await button(wrapper, '挂接上级').trigger('click')
    await flushPromises()
    expect(
      wrapper
        .getComponent(ElSelect)
        .findAllComponents(ElOption)
        .map((option) => option.props('value'))
    ).toEqual(['8'])
  })
  it('只读目录可见树，确认期间撤销关系权限不发送变更', async () => {
    const wrapper = await open(InstitutionHierarchyPanel)
    expect(wrapper.text()).toContain(child)
    const confirmation = deferred<boolean>()
    mocks.confirm.mockReturnValueOnce(confirmation.promise)
    await button(wrapper, '迁移上级').trigger('click')
    wrapper.getComponent(ElSelect).vm.$emit('update:modelValue', '8')
    await button(wrapper, '确认迁移上级').trigger('click')
    await flushPromises()
    mocks.auth.user.permissions = ['platform.institution_relations.read']
    confirmation.resolve(true)
    await flushPromises()
    expect(mocks.change).not.toHaveBeenCalled()
    expect(wrapper.findAllComponents(ElButton).some((item) => item.text() === '迁移上级')).toBe(
      false
    )
  })
  it('CAS冲突保留新上级，主动重读后显式采用最新修订才可重新确认', async () => {
    const wrapper = await open(InstitutionHierarchyPanel)
    await button(wrapper, '迁移上级').trigger('click')
    wrapper.getComponent(ElSelect).vm.$emit('update:modelValue', '8')
    mocks.relations.mockResolvedValue(relations(4))
    mocks.change.mockRejectedValueOnce(failure(409))
    await button(wrapper, '确认迁移上级').trigger('click')
    await flushPromises()
    expect(mocks.change).toHaveBeenCalledWith(
      'move',
      { child_tenant_id: child, parent_tenant_id: '8', expected_revision: 2 },
      expect.any(String)
    )
    expect(wrapper.getComponent(ElSelect).props('modelValue')).toBe('8')
    expect(button(wrapper, '确认迁移上级').props('disabled')).toBe(true)
    expect(document.body.textContent).toContain('原修订已失效')
    await button(wrapper, '采用最新关系修订').trigger('click')
    await button(wrapper, '确认迁移上级').trigger('click')
    await flushPromises()
    expect(mocks.change).toHaveBeenLastCalledWith(
      'move',
      { child_tenant_id: child, parent_tenant_id: '8', expected_revision: 4 },
      expect.any(String)
    )
  })
  it.each([200, 503])(
    'HTTP %s回执不可确认只沿原key与原快照核对，不创建新的迁移意图',
    async (status) => {
      const wrapper = await open(InstitutionHierarchyPanel)
      await button(wrapper, '迁移上级').trigger('click')
      wrapper.getComponent(ElSelect).vm.$emit('update:modelValue', '8')
      mocks.change.mockRejectedValueOnce(failure(status))
      await button(wrapper, '确认迁移上级').trigger('click')
      await flushPromises()
      const original = mocks.change.mock.calls[0]
      expect(wrapper.text()).toContain('关系操作结果待核对')
      await button(wrapper, '核对原操作回执').trigger('click')
      await flushPromises()
      expect(mocks.change.mock.calls[1]).toEqual(original)
    }
  )
  it('较早目录迟到不能覆盖较新树，读取失败保留已有树并显示失败', async () => {
    const old = deferred<ReturnType<typeof relations>>()
    mocks.relations.mockReturnValueOnce(old.promise)
    const wrapper = await open(InstitutionHierarchyPanel)
    mocks.relations.mockResolvedValue(relations(4))
    button(wrapper, '刷新机构树').vm.$emit('click')
    await flushPromises()
    old.resolve(relations())
    await flushPromises()
    await button(wrapper, '迁移上级').trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('所见关系修订 4')
    mocks.relations.mockRejectedValue(new Error('机构树刷新失败'))
    await button(wrapper, '重新读取当前关系').trigger('click')
    await flushPromises()
    expect(document.body.querySelector('.el-message--error')?.textContent).toContain(
      '机构树刷新失败'
    )
    expect(wrapper.text()).toContain('直属门店')
  })
})

describe('平台公共定义精确修订管理', () => {
  it('部署成功后可在模板页继续为另一家机构部署，未完成时不能切换', async () => {
    mocks.auth.user.permissions.push(
      'platform.agent_grants.read',
      'platform.agent_deployments.create'
    )
    const key = `skillforge.agent-setup:${encodeURIComponent(JSON.stringify([null, 'current-platform-actor', '41', definitionId]))}`
    sessionStorage.setItem(key, '{}')
    const wrapper = await open(AgentTemplateDetailPage, definitionPath, true)
    await button(wrapper, '恢复或开始机构部署').trigger('click')
    await button(wrapper, '恢复机构 41 的原流程').trigger('click')
    const setup = wrapper.findComponent({ name: 'InstitutionAgentSetup' })
    expect(setup.exists()).toBe(true)
    expect(wrapper.text()).not.toContain('为其他机构部署')
    setup.vm.$emit('completed', { tenantId: '41', agentId: 'a', deploymentId: 'd' })
    await flushPromises()
    await button(wrapper, '为其他机构部署').trigger('click')
    await flushPromises()
    expect(wrapper.findComponent({ name: 'InstitutionAgentSetup' }).exists()).toBe(false)
    expect(
      wrapper.findAllComponents(ElButton).some((item) => item.text() === '重新核对并继续')
    ).toBe(false)
  })
  it('选择机构即进入部署配置，读取失败后可原地重试', async () => {
    mocks.auth.user.permissions.push(
      'platform.agent_grants.read',
      'platform.agent_deployments.create'
    )
    mocks.resolveTenant
      .mockRejectedValueOnce(new Error('机构目录不可用'))
      .mockResolvedValue({ id: '41', label: '机构' })
    const wrapper = await open(AgentTemplateDetailPage, definitionPath, true)
    await button(wrapper, '向机构授权并部署').trigger('click')
    wrapper.getComponent(EntitySelect).vm.$emit('update:modelValue', '41')
    await flushPromises()
    expect(wrapper.findComponent({ name: 'InstitutionAgentSetup' }).exists()).toBe(false)
    await button(wrapper, '重新核对并继续').trigger('click')
    await flushPromises()
    expect(wrapper.findComponent({ name: 'InstitutionAgentSetup' }).props('tenantId')).toBe('41')
  })
  it('模板下架且失去创建权限后，原主体仍能看到回执恢复入口', async () => {
    mocks.auth.user.permissions = ['platform.agent_grants.read']
    mocks.detail.mockRejectedValue(failure(403))
    const key = `skillforge.agent-setup:${encodeURIComponent(JSON.stringify([null, 'current-platform-actor', '41', definitionId]))}`
    sessionStorage.setItem(key, '{}')
    const wrapper = await open(AgentTemplateDetailPage, definitionPath)
    expect(button(wrapper, '恢复或开始机构部署').exists()).toBe(true)
  })

  it('不会向其他主体显示已保存的流程', async () => {
    mocks.auth.user.permissions = ['platform.agent_definitions.read', 'platform.agent_grants.read']
    mocks.detail.mockResolvedValue({ ...detail(), status: 'deprecated' })
    const key = `skillforge.agent-setup:${encodeURIComponent(JSON.stringify([null, 'another-actor', '41', definitionId]))}`
    sessionStorage.setItem(key, '{}')
    const wrapper = await open(AgentTemplateDetailPage, definitionPath)
    expect(wrapper.text()).not.toContain('向机构授权并部署')
  })
  it('只读定义角色不依赖编辑目录权限，仍可读取详情、历史和精确影响', async () => {
    mocks.auth.user.permissions = ['platform.agent_definitions.read']
    mocks.models.mockRejectedValue(failure(403))
    const wrapper = await open(AgentTemplateDetailPage, definitionPath)
    expect(wrapper.text()).toContain('公共接诊助手')
    expect(mocks.models).not.toHaveBeenCalled()
    expect(wrapper.findAll('button').some((item) => item.text() === '发布')).toBe(false)
    await button(wrapper, '发布历史').trigger('click')
    await flushPromises()
    expect(mocks.history).toHaveBeenCalledWith(definitionId, expect.anything())
    await button(wrapper, '预览发布影响').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('请绑定就诊记录工具')
    expect(mocks.publish).not.toHaveBeenCalled()
  })
  it('保存并验证使用保存后的精确修订，一次操作准备发布但不自动发布', async () => {
    const wrapper = await open(AgentTemplateDetailPage, definitionPath)
    editPrompt(wrapper, '保存并验证的配置')
    mocks.detail.mockResolvedValueOnce(detail(3))
    mocks.previewSaved.mockResolvedValueOnce({
      validation: { valid: true, issues: [] },
      impact: impact(3)
    })
    await flushPromises()
    await button(wrapper, '保存并验证').trigger('click')
    await flushPromises()
    expect(mocks.update).toHaveBeenCalledTimes(1)
    expect(mocks.previewSaved).toHaveBeenCalledWith(
      definitionId,
      3,
      expect.objectContaining({ silent: true })
    )
    expect(button(wrapper, '发布').props('disabled')).toBe(false)
    expect(mocks.publish).not.toHaveBeenCalled()
  })
  it('保存成功后验证失败保留已保存结果，可只重试验证', async () => {
    const wrapper = await open(AgentTemplateDetailPage, definitionPath)
    editPrompt(wrapper, '已保存的配置')
    mocks.detail.mockResolvedValue(detail(3))
    mocks.previewSaved.mockRejectedValueOnce(new Error('验证暂不可用'))
    await flushPromises()
    await button(wrapper, '保存并验证').trigger('click')
    await flushPromises()
    expect(document.body.querySelector('.el-message--error')?.textContent).toContain(
      '模板已保存，但验证未完成'
    )
    expect(button(wrapper, '发布').props('disabled')).toBe(true)
    mocks.previewSaved.mockResolvedValueOnce({
      validation: { valid: true, issues: [] },
      impact: impact(3)
    })
    await button(wrapper, '验证').trigger('click')
    await flushPromises()
    expect(mocks.update).toHaveBeenCalledTimes(1)
    expect(button(wrapper, '发布').props('disabled')).toBe(false)
  })
  it('保存成功但校验未通过时展示问题并禁止发布', async () => {
    const wrapper = await open(AgentTemplateDetailPage, definitionPath)
    editPrompt(wrapper, '需要修正的配置')
    mocks.detail.mockResolvedValueOnce(detail(3))
    mocks.previewSaved.mockResolvedValueOnce({
      validation: {
        valid: false,
        issues: [{ path: 'definition', code: 'invalid', message: '请检查配置' }]
      },
      impact: null
    })
    await flushPromises()
    await button(wrapper, '保存并验证').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('请检查配置')
    expect(button(wrapper, '发布').props('disabled')).toBe(true)
    expect(mocks.update).toHaveBeenCalledTimes(1)
    expect(mocks.publish).not.toHaveBeenCalled()
  })
  it('保存后读到其他修订时不验证错误的草稿', async () => {
    const wrapper = await open(AgentTemplateDetailPage, definitionPath)
    editPrompt(wrapper, '本次输入')
    mocks.detail.mockResolvedValueOnce(detail(4))
    await flushPromises()
    await button(wrapper, '保存并验证').trigger('click')
    await flushPromises()
    expect(mocks.update).toHaveBeenCalledTimes(1)
    expect(mocks.previewSaved).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('服务端草稿修订 4')
    expect(button(wrapper, '发布').props('disabled')).toBe(true)
  })
  it('组合操作结果未知后继续原请求，成功时验证同一已保存修订', async () => {
    const wrapper = await open(AgentTemplateDetailPage, definitionPath)
    editPrompt(wrapper, '待核对的配置')
    mocks.update.mockRejectedValueOnce(failure(0))
    await flushPromises()
    await button(wrapper, '保存并验证').trigger('click')
    await flushPromises()
    const original = mocks.update.mock.calls[0]
    expect(wrapper.text()).toContain('操作结果待核对')
    mocks.detail.mockResolvedValueOnce(detail(3))
    mocks.previewSaved.mockResolvedValueOnce({
      validation: { valid: true, issues: [] },
      impact: impact(3)
    })
    await button(wrapper, '核对原操作回执').trigger('click')
    await flushPromises()
    expect(mocks.update.mock.calls[1]).toEqual(original)
    expect(mocks.previewSaved).toHaveBeenCalledWith(
      definitionId,
      3,
      expect.objectContaining({ silent: true })
    )
    expect(mocks.publish).not.toHaveBeenCalled()
  })
  it('没有验证权限时仍可独立保存，不展示组合操作', async () => {
    mocks.auth.user.permissions = [
      'platform.agent_definitions.read',
      'platform.agent_definitions.edit'
    ]
    const wrapper = await open(AgentTemplateDetailPage, definitionPath)
    editPrompt(wrapper, '仅保存配置')
    await flushPromises()
    expect(wrapper.findAllComponents(ElButton).some((item) => item.text() === '保存并验证')).toBe(
      false
    )
    expect(button(wrapper, '保存模板')).toBeDefined()
    expect(mocks.previewSaved).not.toHaveBeenCalled()
  })
  it('验证后自动更新同修订影响即可发布，展示具体绑定缺口及权限可见范围', async () => {
    const wrapper = await open(AgentTemplateDetailPage, definitionPath)
    expect(button(wrapper, '发布').props('disabled')).toBe(true)
    await button(wrapper, '验证').trigger('click')
    await flushPromises()
    expect(mocks.previewSaved).toHaveBeenCalledWith(
      definitionId,
      2,
      expect.objectContaining({ silent: true })
    )
    expect(mocks.validate).not.toHaveBeenCalled()
    expect(mocks.impact).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('请绑定就诊记录工具')
    expect(wrapper.text()).toContain('当前权限可见 1 个部署')
    expect(button(wrapper, '发布').props('disabled')).toBe(false)
    await button(wrapper, '发布').trigger('click')
    await flushPromises()
    expect(mocks.publish).toHaveBeenCalledWith(definitionId, 2, expect.any(String))
    expect(mocks.confirm).toHaveBeenCalledWith(
      expect.objectContaining({ content: expect.stringContaining('草稿修订 2') })
    )
  })
  it('未保存编辑可预览影响，但不能用该预览直接发布', async () => {
    mocks.previewContent.mockResolvedValueOnce({
      validation: { valid: true, issues: [] },
      impact: impact()
    })
    const wrapper = await open(AgentTemplateDetailPage, definitionPath)
    editPrompt(wrapper, '未保存的提示词')
    await flushPromises()
    await button(wrapper, '验证并预览').trigger('click')
    await flushPromises()
    expect(mocks.previewContent).toHaveBeenCalledWith(
      definitionId,
      2,
      expect.objectContaining({ system_prompt: '未保存的提示词' }),
      expect.any(Object)
    )
    expect(mocks.update).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('当前为未保存内容预览')
    expect(button(wrapper, '发布').props('disabled')).toBe(true)
    editPrompt(wrapper, '再次修改')
    await flushPromises()
    expect(wrapper.text()).not.toContain('当前为未保存内容预览')
  })
  it('平台身份变化时清除未保存影响预览', async () => {
    mocks.previewContent.mockResolvedValueOnce({
      validation: { valid: true, issues: [] },
      impact: impact()
    })
    const wrapper = await open(AgentTemplateDetailPage, definitionPath)
    editPrompt(wrapper, '未保存内容')
    await flushPromises()
    await button(wrapper, '验证并预览').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('请绑定就诊记录工具')
    mocks.auth.identityVersion++
    await flushPromises()
    expect(wrapper.text()).not.toContain('请绑定就诊记录工具')
  })
  it('编辑后迟到预览不能启用发布，确认期间再次编辑也不发送发布', async () => {
    const wrapper = await open(AgentTemplateDetailPage, definitionPath)
    await button(wrapper, '验证').trigger('click')
    await flushPromises()
    const response = deferred<AgentDefinitionImpact>()
    mocks.impact.mockReturnValueOnce(response.promise)
    await button(wrapper, '预览发布影响').trigger('click')
    await flushPromises()
    editPrompt(wrapper, '正在输入的新草稿')
    await flushPromises()
    response.resolve(impact())
    await flushPromises()
    expect(button(wrapper, '发布').props('disabled')).toBe(true)
    expect(mocks.publish).not.toHaveBeenCalled()
    expect(formModel(wrapper).system_prompt).toBe('正在输入的新草稿')
    await button(wrapper, '采用最新草稿（覆盖当前输入）').trigger('click')
    await flushPromises()
    await button(wrapper, '验证').trigger('click')
    await flushPromises()
    await button(wrapper, '预览发布影响').trigger('click')
    await flushPromises()
    const confirmation = deferred<boolean>()
    mocks.confirm.mockReturnValueOnce(confirmation.promise)
    await button(wrapper, '发布').trigger('click')
    await flushPromises()
    editPrompt(wrapper, '确认期间发生变更')
    await flushPromises()
    confirmation.resolve(true)
    await flushPromises()
    expect(mocks.publish).not.toHaveBeenCalled()
    expect(document.body.querySelector('.el-message--error')?.textContent).toContain(
      '确认期间草稿已变化'
    )
  })
  it('保存CAS保留输入和失效预览，主动重读不覆盖，明确覆盖后采用服务端最新值', async () => {
    const wrapper = await open(AgentTemplateDetailPage, definitionPath)
    await button(wrapper, '预览发布影响').trigger('click')
    await flushPromises()
    editPrompt(wrapper, '本地尚未保存的内容')
    await flushPromises()
    mocks.update.mockRejectedValueOnce(failure(409))
    const newer = detail(5)
    newer.definition.system_prompt = '其他管理者的新草稿'
    mocks.detail.mockResolvedValue(newer)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mocks.update).toHaveBeenCalledWith(
      definitionId,
      2,
      expect.objectContaining({
        definition: expect.objectContaining({ system_prompt: '本地尚未保存的内容' })
      }),
      expect.any(String)
    )
    expect(formModel(wrapper).system_prompt).toBe('本地尚未保存的内容')
    expect(wrapper.text()).toContain('此预览已失效')
    await button(wrapper, '重读服务端草稿').trigger('click')
    await flushPromises()
    expect(formModel(wrapper).system_prompt).toBe('本地尚未保存的内容')
    await button(wrapper, '采用最新草稿（覆盖当前输入）').trigger('click')
    await flushPromises()
    expect(formModel(wrapper).system_prompt).toBe('其他管理者的新草稿')
    expect(wrapper.text()).toContain('草稿修订 5')
  })
  it('历史恢复创建新草稿，不将恢复显示为已发布或移动当前发布', async () => {
    const wrapper = await open(AgentTemplateDetailPage, definitionPath)
    const historic = detail(1)
    historic.version = 'v0'
    historic.definition.system_prompt = '历史配置'
    mocks.history.mockResolvedValue([historic])
    const restored = detail(3)
    restored.definition.system_prompt = '历史配置'
    mocks.detail.mockResolvedValue(restored)
    await button(wrapper, '发布历史').trigger('click')
    await flushPromises()
    await button(wrapper, '恢复此历史为草稿').trigger('click')
    await flushPromises()
    expect(mocks.restore).toHaveBeenCalledWith(definitionId, 2, 'v0', expect.any(String))
    expect(wrapper.text()).toContain('当前发布 v1')
    expect(wrapper.text()).toContain('草稿修订 3')
    expect(button(wrapper, '发布').props('disabled')).toBe(true)
  })
  it.each([0, 200])(
    'HTTP %s定义写入结果未知保留原内容和key，只能显式核对同一请求',
    async (status) => {
      const wrapper = await open(AgentTemplateDetailPage, definitionPath)
      editPrompt(wrapper, '需核对的输入')
      await flushPromises()
      mocks.update.mockRejectedValueOnce(failure(status))
      await wrapper.get('form').trigger('submit')
      await flushPromises()
      const original = mocks.update.mock.calls[0]
      expect(wrapper.text()).toContain('操作结果待核对')
      await button(wrapper, '核对原操作回执').trigger('click')
      await flushPromises()
      expect(mocks.update.mock.calls[1]).toEqual(original)
      expect(mocks.update.mock.calls[1]![2].definition.system_prompt).toBe('需核对的输入')
    }
  )
  it('写入成功后重读失败单独提示，保留已确认结果并阻止重复提交', async () => {
    const wrapper = await open(AgentTemplateDetailPage, definitionPath)
    editPrompt(wrapper, '已经提交的草稿')
    await flushPromises()
    mocks.detail.mockRejectedValueOnce(new Error('最新草稿读取失败'))
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('操作回执已确认，但当前服务端草稿尚未刷新')
    expect(button(wrapper, '保存模板').props('disabled')).toBe(true)
    expect(formModel(wrapper).system_prompt).toBe('已经提交的草稿')
    expect(mocks.update).toHaveBeenCalledTimes(1)
    mocks.detail.mockResolvedValue(detail(3))
    await button(wrapper, '重读服务端草稿').trigger('click')
    await flushPromises()
    expect(button(wrapper, '保存模板').props('disabled')).toBe(false)
    expect(mocks.update).toHaveBeenCalledTimes(1)
  })
  it('保存后读取实际配置；若已出现其他修订则保留本次内容并明确选择覆盖', async () => {
    const wrapper = await open(AgentTemplateDetailPage, definitionPath)
    editPrompt(wrapper, '本次保存的提示词')
    await flushPromises()
    const saved = detail(3)
    saved.definition.system_prompt = '服务端解析后的提示词'
    mocks.detail.mockResolvedValueOnce(saved)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(formModel(wrapper).system_prompt).toBe('服务端解析后的提示词')
    editPrompt(wrapper, '再次保存的提示词')
    await flushPromises()
    mocks.update.mockResolvedValueOnce(detail(4))
    const newer = detail(5)
    newer.definition.system_prompt = '其他管理者的提示词'
    mocks.detail.mockResolvedValueOnce(newer)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(formModel(wrapper).system_prompt).toBe('再次保存的提示词')
    expect(button(wrapper, '保存模板').props('disabled')).toBe(true)
    expect(wrapper.text()).toContain('服务端草稿修订 5')
    await button(wrapper, '采用最新草稿（覆盖当前输入）').trigger('click')
    await flushPromises()
    expect(formModel(wrapper).system_prompt).toBe('其他管理者的提示词')
    expect(button(wrapper, '保存模板').props('disabled')).toBe(false)
  })
})
