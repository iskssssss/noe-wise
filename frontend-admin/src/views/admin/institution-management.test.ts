import { defineComponent, type Component } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { DOMWrapper, flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { ElButton, ElFormItem, ElInput, ElRadioGroup, ElSelect, ElPagination } from 'element-plus'
import SubInstitutionsPage from './SubInstitutionsPage.vue'
import InstitutionDefinitionsPage from './InstitutionDefinitionsPage.vue'
import InstitutionGrantsPage from './InstitutionGrantsPage.vue'

const mocks = vi.hoisted(() => ({
  auth: {
    user: {
      permissions: [] as string[],
      tenant_id: 'hq-1',
      sub: 'user-1',
      authorization_version: 1
    }
  },
  provision: vi.fn(),
  subList: vi.fn(),
  planOptions: vi.fn(),
  targetOptions: vi.fn(),
  grants: vi.fn(),
  grantCreate: vi.fn(),
  narrow: vi.fn(),
  revoke: vi.fn(),
  definitionList: vi.fn(),
  detail: vi.fn(),
  create: vi.fn(),
  update: vi.fn(),
  validate: vi.fn(),
  previewContent: vi.fn(),
  previewSaved: vi.fn(),
  impact: vi.fn(),
  publish: vi.fn(),
  history: vi.fn(),
  restore: vi.fn(),
  deprecate: vi.fn(),
  incoming: vi.fn(),
  confirm: vi.fn()
}))
vi.mock('../../stores/auth', () => ({ useAuthStore: () => mocks.auth }))
vi.mock('@skillforge/shared/feedback', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@skillforge/shared/feedback')>()),
  message: { success: vi.fn(), warning: vi.fn() },
  confirm: mocks.confirm
}))
vi.mock('../../api', () => ({
  api: {
    institutionManagement: {
      targetOptions: mocks.targetOptions,
      subInstitutions: {
        list: mocks.subList,
        provision: mocks.provision,
        planOptions: mocks.planOptions
      },
      grants: {
        list: mocks.grants,
        create: mocks.grantCreate,
        narrow: mocks.narrow,
        revoke: mocks.revoke
      }
    },
    agentDefinitions: {
      list: mocks.definitionList,
      detail: mocks.detail,
      create: mocks.create,
      update: mocks.update,
      validate: mocks.validate,
      previewContent: mocks.previewContent,
      previewSaved: mocks.previewSaved,
      impact: mocks.impact,
      publish: mocks.publish,
      history: mocks.history,
      restore: mocks.restore,
      deprecate: mocks.deprecate
    },
    agentDeployments: { grants: mocks.incoming },
    settings: { availableModels: async () => [] },
    capabilities: { listDefinitions: async () => [] }
  }
}))
const definition = {
  definition_id: '9223372036854775807',
  name: '机构共享客服',
  description: '说明',
  category: '',
  tags: [],
  draft_revision: 7,
  current_published_revision: '4',
  distribution_policy: 'AUTHORIZED_DEPLOY',
  status: 'published',
  owner_kind: 'TENANT',
  owner_tenant_id: 'hq-1',
  version: '4',
  definition: {
    system_prompt: '原提示词',
    role_description: '客服',
    model_config_id: null,
    execution_config: {
      max_business_model_calls: null,
      max_tool_calls: 3,
      max_knowledge_calls: null,
      max_total_tokens: null,
      max_wall_time_seconds: null
    },
    capabilities: [
      {
        capability_type: 'SKILL',
        capability_definition_id: '9223372036854775806',
        capability_version_id: '9223372036854775805',
        skill_config: { activation_mode: 'ALWAYS', token_budget: 500 },
        platform_tool_name: null,
        priority: 9
      }
    ],
    resource_slots: [
      {
        key: 'records',
        label: '机构资料',
        description: '私有',
        capability_type: 'KNOWLEDGE_SOURCE'
      }
    ],
    delegation_slots: [{ key: 'support', label: '协作', description: '人工支持' }]
  }
}
const issued = {
  grant_id: 'issued-1',
  definition_id: definition.definition_id,
  definition_name: definition.name,
  target_tenant_id: 'child-1',
  parent_grant_id: 'parent-1',
  actions: ['deploy', 'execute'],
  expires_at: null,
  allow_regrant: false,
  remaining_depth: 0,
  status: 'active',
  revision: 3
}
const wrappers: VueWrapper[] = []
afterEach(() => {
  wrappers.forEach((w) => w.unmount())
  wrappers.length = 0
  document.body.innerHTML = ''
})
beforeEach(() => {
  vi.clearAllMocks()
  mocks.auth.user.authorization_version = 1
  mocks.auth.user.permissions = [
    'tenant.sub_institutions.read',
    'tenant.sub_institutions.provision',
    ...['read', 'create', 'edit', 'validate', 'publish', 'restore', 'deprecate'].map(
      (a) => `tenant.agent_definitions.${a}`
    ),
    ...['read', 'create', 'narrow', 'revoke'].map((a) => `tenant.agent_grants.${a}`),
    'tenant.agent_deployments.read'
  ]
  mocks.targetOptions.mockResolvedValue({
    items: [{ id: 'child-2', label: '第二分店', description: 'child-2' }],
    total: 1,
    page: 1,
    page_size: 200
  })
  mocks.planOptions.mockResolvedValue({
    items: [{ id: 'basic', label: '基础套餐', description: 'basic' }],
    total: 1,
    page: 1,
    page_size: 200
  })
  mocks.subList.mockResolvedValue({
    items: [
      {
        tenant_id: 'child-2',
        parent_tenant_id: 'hq-1',
        name: '第二分店',
        status: 'active',
        revision: 1
      },
      {
        tenant_id: 'grandchild',
        parent_tenant_id: 'child-2',
        name: '非直属机构',
        status: 'active',
        revision: 1
      }
    ],
    total: 2
  })
  mocks.confirm.mockResolvedValue(true)
  mocks.definitionList.mockResolvedValue({ items: [definition], total: 1 })
  mocks.detail.mockReset().mockResolvedValue(definition)
  mocks.grants.mockResolvedValue({ items: [issued], total: 1 })
  mocks.incoming.mockResolvedValue({
    items: [
      {
        ...issued,
        grant_id: 'parent-1',
        target_tenant_id: 'hq-1',
        parent_grant_id: 'upstream-1',
        allow_regrant: true,
        remaining_depth: 2
      }
    ],
    total: 1
  })
})
async function open(component: Component) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/', component }]
  })
  await router.push('/')
  await router.isReady()
  const wrapper = mount(defineComponent({ template: '<RouterView />' }), {
    attachTo: document.body,
    global: { plugins: [router] }
  })
  wrappers.push(wrapper)
  await flushPromises()
  return wrapper
}
function button(w: VueWrapper, text: string) {
  const b = w.findAllComponents(ElButton).find((b) => b.text() === text)
  if (!b) throw Error(text)
  return b
}
function field(w: VueWrapper, label: string) {
  const f = w.findAllComponents(ElFormItem).find((f) => f.props('label') === label)
  if (!f) throw Error(label)
  return f
}
async function input(w: VueWrapper, label: string, value: string) {
  field(w, label).getComponent(ElInput).vm.$emit('update:modelValue', value)
  await flushPromises()
}

describe('机构管理实际页面交互', () => {
  it('开通套餐候选读取完整分页并按已选编码解析，命名空间保留业务输入', async () => {
    mocks.planOptions.mockImplementation(async (params) => {
      if (params.id)
        return { items: [{ id: params.id, label: '专业套餐', description: params.id }], total: 1 }
      return params.page === 1
        ? { items: [{ id: 'basic', label: '基础套餐' }], total: 2 }
        : { items: [{ id: 'premium', label: '专业套餐' }], total: 2 }
    })
    const w = await open(SubInstitutionsPage)
    await button(w, '开通直属机构').trigger('click')
    await flushPromises()
    expect(mocks.planOptions).toHaveBeenCalledWith(
      { q: '', page: 2, page_size: 200 },
      expect.any(Object)
    )
    const select = field(w, '初始套餐').getComponent(ElSelect)
    select.vm.$emit('update:modelValue', 'premium')
    await flushPromises()
    expect(mocks.planOptions).toHaveBeenCalledWith({ id: 'premium' }, { silent: true })
    expect(select.props('modelValue')).toBe('premium')
    expect(document.body.textContent).toContain(
      '填写业务系统使用的命名空间，与外部机构标识共同定位机构'
    )
  })

  it('开通结果未知时冻结表单，核对原请求使用同内容同键且不产生第二个开通', async () => {
    mocks.provision
      .mockRejectedValueOnce(Object.assign(Error('响应中断'), { status: 0 }))
      .mockResolvedValueOnce({ tenant_id: 'child-1' })
    const w = await open(SubInstitutionsPage)
    await button(w, '开通直属机构').trigger('click')
    await flushPromises()
    for (const [label, value] of [
      ['机构名称', '分店'],
      ['命名空间', 'chain'],
      ['外部机构标识', 'store-1']
    ])
      await input(w, label!, value!)
    field(w, '初始套餐').getComponent(ElSelect).vm.$emit('update:modelValue', 'basic')
    await flushPromises()
    await button(w, '开通机构').trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('开通结果尚未确认')
    expect(
      field(w, '机构名称').getComponent(ElInput).get('input').attributes('disabled')
    ).toBeDefined()
    await button(w, '核对原请求').trigger('click')
    await flushPromises()
    expect(mocks.provision.mock.calls[1]).toEqual(mocks.provision.mock.calls[0])
    expect(mocks.provision).toHaveBeenCalledTimes(2)
  })
  it('草稿CAS失败保留输入及完整复杂定义；显式重新读取才放弃编辑', async () => {
    mocks.update.mockRejectedValue(Object.assign(Error('修订冲突'), { status: 409 }))
    const w = await open(InstitutionDefinitionsPage)
    await button(w, '查看与维护').trigger('click')
    await flushPromises()
    await input(w, '定义名称', '修改后的客服')
    expect(field(w, '定义名称').getComponent(ElInput).props('modelValue')).toBe('修改后的客服')
    await button(w, '保存草稿').trigger('click')
    await flushPromises()
    expect(mocks.update.mock.calls[0]?.slice(0, 3)).toEqual([
      definition.definition_id,
      7,
      expect.objectContaining({ name: '修改后的客服', definition: definition.definition })
    ])
    expect(field(w, '定义名称').getComponent(ElInput).props('modelValue')).toBe('修改后的客服')
    mocks.confirm.mockResolvedValueOnce(false)
    await button(w, '重新读取草稿').trigger('click')
    await flushPromises()
    expect(field(w, '定义名称').getComponent(ElInput).props('modelValue')).toBe('修改后的客服')
    mocks.confirm.mockResolvedValueOnce(true)
    await button(w, '重新读取草稿').trigger('click')
    await flushPromises()
    expect(field(w, '定义名称').getComponent(ElInput).props('modelValue')).toBe(definition.name)
  })
  it('保存后读取服务端解析的能力配置，再次保存使用真实结果', async () => {
    const saved = structuredClone(definition)
    saved.draft_revision = 8
    saved.name = '已保存的名称'
    saved.definition.capabilities[0]!.capability_version_id = '300'
    saved.definition.capabilities[0]!.skill_config!.activation_mode = 'ON_DEMAND'
    mocks.detail.mockResolvedValueOnce(definition).mockResolvedValue(saved)
    mocks.update
      .mockReset()
      .mockResolvedValueOnce(saved)
      .mockRejectedValueOnce(Error('停止第二次测试写入'))
    const w = await open(InstitutionDefinitionsPage)
    await button(w, '查看与维护').trigger('click')
    await flushPromises()
    expect(document.body.textContent).not.toContain('技能激活方式')
    expect(document.body.textContent).not.toContain('冻结版本')
    await input(w, '定义名称', '已保存的名称')
    await button(w, '保存草稿').trigger('click')
    await flushPromises()
    expect(mocks.detail).toHaveBeenCalledTimes(2)
    expect(document.body.textContent).not.toContain('需重新读取保存结果')
    await input(w, '定义名称', '第二次编辑')
    await button(w, '保存草稿').trigger('click')
    await flushPromises()
    expect(mocks.update.mock.calls[1]?.[2].definition.capabilities[0]).toMatchObject({
      capability_version_id: '300',
      skill_config: { activation_mode: 'ON_DEMAND', token_budget: 500 }
    })
  })
  it('保存并校验使用实际保存修订，不自动发布', async () => {
    const saved = { ...structuredClone(definition), draft_revision: 8, name: '新的共享定义' }
    mocks.detail.mockResolvedValueOnce(definition).mockResolvedValueOnce(saved)
    mocks.update.mockReset().mockResolvedValueOnce(saved)
    mocks.previewSaved.mockReset().mockResolvedValueOnce({
      validation: { valid: true, issues: [] },
      impact: {
        definition_id: definition.definition_id,
        draft_revision: 8,
        visible_deployment_count: 0,
        changes: {
          added_resource_slots: [],
          changed_resource_slots: [],
          removed_resource_slot_keys: [],
          added_delegation_slots: [],
          removed_delegation_slot_keys: []
        },
        deployments: []
      }
    })
    const w = await open(InstitutionDefinitionsPage)
    await button(w, '查看与维护').trigger('click')
    await flushPromises()
    await input(w, '定义名称', '新的共享定义')
    await button(w, '保存并校验').trigger('click')
    await flushPromises()
    expect(mocks.update).toHaveBeenCalledTimes(1)
    expect(mocks.previewSaved).toHaveBeenCalledWith(definition.definition_id, 8, expect.any(Object))
    expect(button(w, '发布此修订').props('disabled')).toBe(false)
    expect(mocks.publish).not.toHaveBeenCalled()
  })
  it('保存后校验失败保留已保存草稿，只需重新校验', async () => {
    const saved = { ...structuredClone(definition), draft_revision: 8, name: '已保存的定义' }
    mocks.detail.mockResolvedValueOnce(definition).mockResolvedValue(saved)
    mocks.update.mockReset().mockResolvedValueOnce(saved)
    mocks.previewSaved
      .mockReset()
      .mockRejectedValueOnce(Error('读取失败'))
      .mockResolvedValueOnce({
        validation: { valid: true, issues: [] },
        impact: {
          definition_id: definition.definition_id,
          draft_revision: 8,
          visible_deployment_count: 0,
          changes: {
            added_resource_slots: [],
            changed_resource_slots: [],
            removed_resource_slot_keys: [],
            added_delegation_slots: [],
            removed_delegation_slot_keys: []
          },
          deployments: []
        }
      })
    const w = await open(InstitutionDefinitionsPage)
    await button(w, '查看与维护').trigger('click')
    await flushPromises()
    await input(w, '定义名称', '已保存的定义')
    await button(w, '保存并校验').trigger('click')
    await flushPromises()
    expect(button(w, '发布此修订').props('disabled')).toBe(true)
    expect(button(w, '校验并预览影响').props('disabled')).toBe(false)
    await button(w, '校验并预览影响').trigger('click')
    await flushPromises()
    expect(mocks.update).toHaveBeenCalledTimes(1)
    expect(button(w, '发布此修订').props('disabled')).toBe(false)
  })
  it('无校验权限仍可单独保存，不能触发组合操作', async () => {
    mocks.auth.user.permissions = ['tenant.agent_definitions.read', 'tenant.agent_definitions.edit']
    const w = await open(InstitutionDefinitionsPage)
    await button(w, '查看与维护').trigger('click')
    await flushPromises()
    await input(w, '定义名称', '仅保存')
    expect(w.findAllComponents(ElButton).some((item) => item.text() === '保存并校验')).toBe(false)
    expect(button(w, '保存草稿')).toBeDefined()
    expect(mocks.previewSaved).not.toHaveBeenCalled()
  })
  it('保存后读到其他修订时不校验错误版本', async () => {
    const saved = { ...structuredClone(definition), draft_revision: 8, name: '本次保存' }
    const newer = { ...structuredClone(definition), draft_revision: 9, name: '其他人修改' }
    mocks.detail.mockResolvedValueOnce(definition).mockResolvedValueOnce(newer)
    mocks.update.mockReset().mockResolvedValueOnce(saved)
    mocks.previewSaved.mockReset()
    const w = await open(InstitutionDefinitionsPage)
    await button(w, '查看与维护').trigger('click')
    await flushPromises()
    await input(w, '定义名称', '本次保存')
    await button(w, '保存并校验').trigger('click')
    await flushPromises()
    expect(mocks.update).toHaveBeenCalledTimes(1)
    expect(mocks.previewSaved).not.toHaveBeenCalled()
    expect(document.body.textContent).toContain('草稿已保存，需重新读取保存结果')
    expect(button(w, '发布此修订').props('disabled')).toBe(true)
  })
  it('原写入未知后重试被拒绝时保留原请求，再次核对使用同一键', async () => {
    const saved = { ...structuredClone(definition), draft_revision: 8, name: '待核对的定义' }
    mocks.detail.mockResolvedValueOnce(definition).mockResolvedValue(saved)
    mocks.update
      .mockReset()
      .mockRejectedValueOnce(Object.assign(Error('连接中断'), { status: 0 }))
      .mockRejectedValueOnce(Object.assign(Error('当前被拒绝'), { status: 403 }))
      .mockResolvedValueOnce(saved)
    mocks.previewSaved.mockReset().mockResolvedValue({
      validation: { valid: true, issues: [] },
      impact: {
        definition_id: definition.definition_id,
        draft_revision: 8,
        visible_deployment_count: 0,
        changes: {
          added_resource_slots: [],
          changed_resource_slots: [],
          removed_resource_slot_keys: [],
          added_delegation_slots: [],
          removed_delegation_slot_keys: []
        },
        deployments: []
      }
    })
    const w = await open(InstitutionDefinitionsPage)
    await button(w, '查看与维护').trigger('click')
    await flushPromises()
    await input(w, '定义名称', '待核对的定义')
    await button(w, '保存并校验').trigger('click')
    await flushPromises()
    const original = mocks.update.mock.calls[0]
    await button(w, '核对原请求').trigger('click')
    await flushPromises()
    expect(button(w, '保存草稿').props('disabled')).toBe(true)
    expect(mocks.previewSaved).not.toHaveBeenCalled()
    await button(w, '核对原请求').trigger('click')
    await flushPromises()
    expect(mocks.update.mock.calls[1]).toEqual(original)
    expect(mocks.update.mock.calls[2]).toEqual(original)
    expect(mocks.previewSaved).toHaveBeenCalledWith(definition.definition_id, 8, expect.any(Object))
  })
  it('保存成功后读取失败锁定写入，重新读取后继续维护', async () => {
    const saved = { ...structuredClone(definition), draft_revision: 8, name: '已保存的名称' }
    mocks.detail
      .mockResolvedValueOnce(definition)
      .mockRejectedValueOnce(Error('读取失败'))
      .mockResolvedValueOnce(saved)
    mocks.update.mockReset().mockResolvedValueOnce(saved)
    const w = await open(InstitutionDefinitionsPage)
    await button(w, '查看与维护').trigger('click')
    await flushPromises()
    await input(w, '定义名称', '已保存的名称')
    await button(w, '保存草稿').trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('草稿已保存，需重新读取保存结果')
    expect(button(w, '保存草稿').props('disabled')).toBe(true)
    expect(button(w, '校验并预览影响').props('disabled')).toBe(true)
    await button(w, '重新读取草稿').trigger('click')
    await flushPromises()
    expect(document.body.textContent).not.toContain('草稿已保存，需重新读取保存结果')
    expect(button(w, '保存草稿').props('disabled')).toBe(false)
    expect(mocks.update).toHaveBeenCalledTimes(1)
  })
  it('保存后读到其他修订时保留本次内容，明确重新读取才采用新草稿', async () => {
    const saved = { ...structuredClone(definition), draft_revision: 8, name: '本次保存' }
    const newer = { ...structuredClone(definition), draft_revision: 9, name: '其他人修改' }
    mocks.detail
      .mockResolvedValueOnce(definition)
      .mockResolvedValueOnce(newer)
      .mockResolvedValueOnce(newer)
    mocks.update.mockReset().mockResolvedValueOnce(saved)
    const w = await open(InstitutionDefinitionsPage)
    await button(w, '查看与维护').trigger('click')
    await flushPromises()
    await input(w, '定义名称', '本次保存')
    await button(w, '保存草稿').trigger('click')
    await flushPromises()
    expect(field(w, '定义名称').getComponent(ElInput).props('modelValue')).toBe('本次保存')
    expect(button(w, '保存草稿').props('disabled')).toBe(true)
    await button(w, '重新读取草稿').trigger('click')
    await flushPromises()
    expect(field(w, '定义名称').getComponent(ElInput).props('modelValue')).toBe('其他人修改')
    expect(mocks.update).toHaveBeenCalledTimes(1)
  })
  it('发布先校验精确草稿并读取同修订影响，确认期间失权不发写入', async () => {
    mocks.validate.mockResolvedValue({ valid: true, issues: [] })
    mocks.impact.mockResolvedValue({
      definition_id: definition.definition_id,
      draft_revision: 7,
      current_published_revision: '4',
      visible_deployment_count: 2,
      changes: {
        added_resource_slots: [],
        changed_resource_slots: [],
        removed_resource_slot_keys: [],
        added_delegation_slots: [],
        removed_delegation_slot_keys: []
      },
      deployments: []
    })
    mocks.previewSaved.mockResolvedValueOnce({
      validation: { valid: true, issues: [] },
      impact: await mocks.impact()
    })
    mocks.impact.mockClear()
    const w = await open(InstitutionDefinitionsPage)
    await button(w, '查看与维护').trigger('click')
    await flushPromises()
    expect(button(w, '发布此修订').props('disabled')).toBe(true)
    await button(w, '校验并预览影响').trigger('click')
    await flushPromises()
    expect(mocks.previewSaved).toHaveBeenCalledWith(definition.definition_id, 7, expect.any(Object))
    expect(mocks.validate).not.toHaveBeenCalled()
    expect(mocks.impact).not.toHaveBeenCalled()
    expect(document.body.textContent).toContain('可见部署 2 个')
    mocks.confirm.mockImplementationOnce(async () => {
      mocks.auth.user.authorization_version = 2
      return true
    })
    await button(w, '发布此修订').trigger('click')
    await flushPromises()
    expect(mocks.publish).not.toHaveBeenCalled()
  })
  it('未保存定义可直接预览，保存前不能将预览用于发布', async () => {
    mocks.previewContent.mockResolvedValueOnce({
      validation: { valid: true, issues: [] },
      impact: {
        definition_id: definition.definition_id,
        draft_revision: 7,
        visible_deployment_count: 0,
        changes: {
          added_resource_slots: [],
          changed_resource_slots: [],
          removed_resource_slot_keys: [],
          added_delegation_slots: [],
          removed_delegation_slot_keys: []
        },
        deployments: []
      }
    })
    const w = await open(InstitutionDefinitionsPage)
    await button(w, '查看与维护').trigger('click')
    await flushPromises()
    await input(w, '系统提示词', '未保存的提示词')
    await button(w, '校验并预览影响').trigger('click')
    await flushPromises()
    expect(mocks.previewContent).toHaveBeenCalledWith(
      definition.definition_id,
      7,
      expect.objectContaining({ system_prompt: '未保存的提示词' }),
      expect.any(Object)
    )
    expect(mocks.update).not.toHaveBeenCalled()
    expect(document.body.textContent).toContain('当前为未保存内容预览')
    expect(button(w, '发布此修订').props('disabled')).toBe(true)
    await input(w, '系统提示词', '再次修改')
    expect(document.body.textContent).not.toContain('当前为未保存内容预览')
  })
  it('自有授权候选按服务端发布与分发条件分页，末页旧定义可选并跨搜索保留身份', async () => {
    mocks.definitionList.mockImplementation(async (query) => ({
      total: query.name ? 0 : 201,
      items: query.name
        ? []
        : query.page === 11
          ? [definition]
          : Array.from({ length: 20 }, (_, index) => ({
              ...definition,
              definition_id: String(100 + index),
              name: '较新的定义' + index
            }))
    }))
    mocks.grantCreate.mockResolvedValue(issued)
    const w = await open(InstitutionGrantsPage)
    await button(w, '新增授权').trigger('click')
    await flushPromises()
    expect(mocks.definitionList).toHaveBeenCalledExactlyOnceWith(
      {
        page: 1,
        limit: 20,
        name: '',
        status: 'published',
        distribution_policy: 'AUTHORIZED_DEPLOY'
      },
      expect.any(Object)
    )
    const pagination = w.getComponent(ElPagination)
    expect(pagination.props('total')).toBe(201)
    pagination.vm.$emit('current-change', 11)
    await flushPromises()
    const picker = field(w, '自有共享定义').getComponent(ElSelect)
    picker.vm.$emit('update:modelValue', definition.definition_id)
    picker.vm.$emit('change', definition.definition_id)
    await flushPromises()
    await (picker.props('remoteMethod') as (query: string) => void)('另一个名称')
    await flushPromises()
    expect(picker.props('modelValue')).toBe(definition.definition_id)
    expect(
      picker
        .findAllComponents({ name: 'ElOption' })
        .some((option) => option.props('value') === definition.definition_id)
    ).toBe(true)
    expect(mocks.definitionList.mock.calls.map((call) => call[0].page)).toEqual([1, 11, 1])
    expect(mocks.definitionList.mock.calls.at(-1)?.[0].name).toBe('另一个名称')
    field(w, '目标直属机构').getComponent(ElSelect).vm.$emit('update:modelValue', 'child-2')
    await flushPromises()
    await button(w, '保存授权').trigger('click')
    await flushPromises()
    expect(mocks.grantCreate.mock.calls[0]?.[0]).toMatchObject({
      definition_id: definition.definition_id,
      target_tenant_id: 'child-2',
      parent_grant_id: null
    })
    expect(mocks.incoming).not.toHaveBeenCalled()
  })

  it('自有定义列表展开时可直接在真实下拉footer翻页，不关闭列表', async () => {
    mocks.definitionList.mockImplementation(async (query) => ({
      total: 21,
      items:
        query.page === 2
          ? [definition]
          : Array.from({ length: 20 }, (_, index) => ({
              ...definition,
              definition_id: String(100 + index),
              name: '本页定义' + index
            }))
    }))
    const w = await open(InstitutionGrantsPage)
    await button(w, '新增授权').trigger('click')
    await flushPromises()
    const picker = field(w, '自有共享定义').getComponent(ElSelect)
    await picker.get('.el-select__wrapper').trigger('click')
    await flushPromises()
    const footer = new DOMWrapper(document.body).get('.el-select-dropdown__footer')
    expect(footer.find('.el-pagination').exists()).toBe(true)
    await footer.get('button.btn-next').trigger('click')
    await flushPromises()
    expect(mocks.definitionList.mock.calls.at(-1)?.[0].page).toBe(2)
    expect(picker.get('input[role="combobox"]').attributes('aria-expanded')).toBe('true')
    expect(footer.text()).toContain('21')
    expect(
      picker
        .findAllComponents({ name: 'ElOption' })
        .some((option) => option.props('value') === definition.definition_id)
    ).toBe(true)
    expect(mocks.grantCreate).not.toHaveBeenCalled()
  })

  it('当前页读取失败保留已选定义与目标，重试相同筛选页后恢复授权', async () => {
    mocks.definitionList.mockResolvedValue({ items: [definition], total: 41 })
    mocks.grantCreate.mockResolvedValue(issued)
    const w = await open(InstitutionGrantsPage)
    await button(w, '新增授权').trigger('click')
    await flushPromises()
    const picker = field(w, '自有共享定义').getComponent(ElSelect)
    picker.vm.$emit('update:modelValue', definition.definition_id)
    picker.vm.$emit('change', definition.definition_id)
    field(w, '目标直属机构').getComponent(ElSelect).vm.$emit('update:modelValue', 'child-2')
    await flushPromises()
    mocks.definitionList.mockRejectedValueOnce(Error('定义目录第三页暂不可用'))
    w.getComponent(ElPagination).vm.$emit('current-change', 3)
    await flushPromises()
    expect(picker.props('modelValue')).toBe(definition.definition_id)
    expect(field(w, '目标直属机构').getComponent(ElSelect).props('modelValue')).toBe('child-2')
    await button(w, '保存授权').trigger('click')
    await flushPromises()
    expect(mocks.grantCreate).not.toHaveBeenCalled()
    await button(w, '重试来源目录').trigger('click')
    await flushPromises()
    expect(mocks.definitionList.mock.calls.slice(-2).map((call) => call[0])).toEqual([
      {
        page: 3,
        limit: 20,
        name: '',
        status: 'published',
        distribution_policy: 'AUTHORIZED_DEPLOY'
      },
      {
        page: 3,
        limit: 20,
        name: '',
        status: 'published',
        distribution_policy: 'AUTHORIZED_DEPLOY'
      }
    ])
    await button(w, '保存授权').trigger('click')
    await flushPromises()
    expect(mocks.grantCreate).toHaveBeenCalledOnce()
    expect(mocks.incoming).not.toHaveBeenCalled()
  })

  it('迟到的旧名称搜索不覆盖新筛选结果与总数', async () => {
    let resolveOld!: (value: unknown) => void
    const old = new Promise((resolve) => {
      resolveOld = resolve
    })
    mocks.definitionList.mockImplementation(async (query) =>
      query.name === '旧名称'
        ? old
        : {
            items: [{ ...definition, definition_id: '200', name: '新名称' }],
            total: 1
          }
    )
    const w = await open(InstitutionGrantsPage)
    await button(w, '新增授权').trigger('click')
    await flushPromises()
    const picker = field(w, '自有共享定义').getComponent(ElSelect)
    const search = picker.props('remoteMethod') as (query: string) => void
    search('旧名称')
    await flushPromises()
    search('新名称')
    await flushPromises()
    resolveOld({ items: [{ ...definition, definition_id: '100', name: '旧名称' }], total: 99 })
    await flushPromises()
    expect(
      picker.findAllComponents({ name: 'ElOption' }).map((option) => option.props('value'))
    ).toEqual(['200'])
    expect(w.getComponent(ElPagination).props('total')).toBe(1)
    expect(mocks.definitionList.mock.calls.at(-1)?.[0].name).toBe('新名称')
  })

  it('自有定义授权UNKNOWN保留选择并沿原命令与键核对，收窄仍使用原CAS', async () => {
    mocks.grantCreate
      .mockRejectedValueOnce(Object.assign(Error('创建超时'), { status: 408 }))
      .mockResolvedValueOnce(issued)
    const w = await open(InstitutionGrantsPage)
    await button(w, '新增授权').trigger('click')
    await flushPromises()
    const picker = field(w, '自有共享定义').getComponent(ElSelect)
    picker.vm.$emit('update:modelValue', definition.definition_id)
    picker.vm.$emit('change', definition.definition_id)
    field(w, '目标直属机构').getComponent(ElSelect).vm.$emit('update:modelValue', 'child-2')
    await flushPromises()
    await button(w, '保存授权').trigger('click')
    await flushPromises()
    expect(picker.props('modelValue')).toBe(definition.definition_id)
    expect(field(w, '目标直属机构').getComponent(ElSelect).props('modelValue')).toBe('child-2')
    await button(w, '核对原请求').trigger('click')
    await flushPromises()
    expect(mocks.grantCreate.mock.calls[1]).toEqual(mocks.grantCreate.mock.calls[0])
    mocks.narrow.mockRejectedValueOnce(Object.assign(Error('授权修订冲突'), { status: 409 }))
    await button(w, '收窄范围').trigger('click')
    await flushPromises()
    await button(w, '保存授权').trigger('click')
    await flushPromises()
    expect(mocks.narrow.mock.calls[0]?.[0]).toMatchObject({
      grant_id: issued.grant_id,
      expected_revision: issued.revision
    })
  })

  it('无机构目录读权也可搜索真实动作目标，转授父授权仍从incoming取', async () => {
    mocks.auth.user.permissions = mocks.auth.user.permissions.filter(
      (permission) => permission !== 'tenant.sub_institutions.read'
    )
    mocks.grantCreate.mockResolvedValue(issued)
    const w = await open(InstitutionGrantsPage)
    await button(w, '新增授权').trigger('click')
    await flushPromises()
    field(w, '授权来源').getComponent(ElRadioGroup).vm.$emit('update:modelValue', 'delegated')
    await flushPromises()
    const select = field(w, '显式父授权').getComponent(ElSelect)
    select.vm.$emit('update:modelValue', 'parent-1')
    await flushPromises()
    const target = field(w, '目标直属机构').getComponent(ElSelect)
    target.vm.$emit('update:modelValue', 'child-2')
    await flushPromises()
    expect(target.props('modelValue')).toBe('child-2')
    expect(mocks.targetOptions).toHaveBeenCalledWith(
      'tenant.agent_grants.create',
      expect.objectContaining({ q: '', page: 1 }),
      expect.any(Object)
    )
    expect(mocks.subList).not.toHaveBeenCalled()
    await button(w, '保存授权').trigger('click')
    await flushPromises()
    expect(mocks.grantCreate).toHaveBeenCalledWith(
      expect.objectContaining({
        definition_id: definition.definition_id,
        parent_grant_id: 'parent-1',
        target_tenant_id: 'child-2',
        actions: ['deploy', 'execute']
      }),
      expect.any(String)
    )
  })
})
