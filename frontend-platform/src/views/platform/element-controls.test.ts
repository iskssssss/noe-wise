import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { computed, type Component } from 'vue'
import { createPermissionDirective } from '@skillforge/shared/directives/permission'
import { HttpClientError } from '@skillforge/shared/utils/http'
import {
  ElCollapseItem,
  ElFormItem,
  ElInput,
  ElInputNumber,
  ElMenu,
  ElMessageBox,
  ElStatistic,
  ElSelect,
  ElUpload
} from 'element-plus'
import HomePage from './HomePage.vue'
import SkillWorkbenchPage from './SkillWorkbenchPage.vue'
import SessionDetailPage from './SessionDetailPage.vue'
import SystemAgentDetailPage from './SystemAgentDetailPage.vue'
const currentAuth = vi.hoisted(() => ({
  identityVersion: 1,
  user: {
    sub: 'platform-actor',
    permissions: [
      'foundation.tenants.read',
      'foundation.marketplace.read',
      'foundation.models.read',
      'foundation.skills.create',
      'foundation.skills.read',
      'foundation.skills.update',
      'foundation.skills.publish',
      'foundation.system_agents.update',
      'foundation.system_agents.publish',
      'foundation.system_agents.run',
      'foundation.system_agents.delete'
    ]
  }
}))
vi.mock('../../stores/auth', () => ({ useAuthStore: () => currentAuth }))
const mocked = vi.hoisted(() => ({
  tenants: vi.fn(),
  reviews: vi.fn(),
  models: vi.fn(),
  definition: vi.fn(),
  draft: vi.fn(),
  getVersion: vi.fn(),
  createDraft: vi.fn(),
  validateDraft: vi.fn(),
  validateDraftContent: vi.fn(),
  publishDraft: vi.fn(),
  saveAndPublish: vi.fn(),
  publishResult: vi.fn(),
  importZip: vi.fn(),
  session: vi.fn(),
  rounds: vi.fn(),
  messages: vi.fn(),
  usage: vi.fn(),
  trajectoryDetail: vi.fn(),
  trajectoryNodes: vi.fn(),
  agent: vi.fn(),
  options: vi.fn(),
  versions: vi.fn(),
  runs: vi.fn(),
  updateAgent: vi.fn(),
  publishAgent: vi.fn(),
  revealFile: vi.fn()
}))
vi.mock('../../api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../api')>()),
  getSessionId: () => 'account-A',
  api: {
    admin: { listTenants: mocked.tenants, models: { list: mocked.models } },
    marketplace: { reviews: mocked.reviews },
    capabilities: { getDefinition: mocked.definition },
    skillDrafts: {
      getActive: mocked.draft,
      getVersion: mocked.getVersion,
      create: mocked.createDraft,
      validate: mocked.validateDraft,
      validateContent: mocked.validateDraftContent,
      publish: mocked.publishDraft,
      saveAndPublish: mocked.saveAndPublish,
      saveAndPublishResult: mocked.publishResult,
      importZip: mocked.importZip
    },
    sessions: {
      detail: mocked.session,
      rounds: mocked.rounds,
      messages: mocked.messages,
      usage: mocked.usage
    },
    trajectories: {
      detail: mocked.trajectoryDetail,
      nodes: mocked.trajectoryNodes
    },
    systemAgents: {
      detail: mocked.agent,
      mountOptions: mocked.options,
      versions: mocked.versions,
      runs: mocked.runs,
      update: mocked.updateAgent,
      publish: mocked.publishAgent
    }
  }
}))
vi.mock('@skillforge/shared/components/PackageFileWorkbench.vue', () => ({
  default: { template: '<div />', methods: { reveal: mocked.revealFile } }
}))
const wrappers: VueWrapper[] = []
const config = {
  name: '摘要助手',
  description: '',
  system_prompt: '仅总结提供的事实',
  model_config_id: null,
  timeout_seconds: 30,
  max_business_model_calls: 3,
  mounts: []
}
const draft = {
  id: '7',
  definitionId: '3',
  revision: 2,
  versionKey: 'v1',
  files: { 'SKILL.md': '原始内容' },
  modelInvocable: true,
  userInvocable: false,
  activationMode: 'ON_DEMAND',
  conflictGroup: null,
  requiredCapabilities: [],
  requiresPlan: null,
  baseVersionId: null,
  publishedVersionId: null,
  updatedAt: ''
}
beforeEach(() => {
  vi.clearAllMocks()
  sessionStorage.clear()
  mocked.tenants.mockResolvedValue({ items: [], total: 0 })
  mocked.reviews.mockResolvedValue({ items: [], total: 0 })
  mocked.models.mockResolvedValue([])
  mocked.definition.mockResolvedValue({ id: '3', name: '测试 Skill', revision: 1 })
  mocked.draft.mockImplementation(async () => structuredClone(draft))
  mocked.importZip.mockImplementation(async () => ({
    ...structuredClone(draft),
    revision: 3,
    files: { 'SKILL.md': '导入内容' }
  }))
  mocked.session.mockResolvedValue({
    tenant_id: 'tenant-A',
    session_id: 'session-A',
    message_count: 0
  })
  mocked.rounds.mockResolvedValue({
    items: [
      {
        root_input_id: 'input-7',
        round_number: '1',
        first_sequence: '1',
        last_sequence: '1',
        answer_status: 'COMPLETED',
        execution_status: 'COMPLETED',
        run_count: 1,
        started_at: '2026-10-07',
        updated_at: '2026-10-07',
        runs: [{ run_id: 'run-7', loops: [] }]
      }
    ],
    next_cursor: null
  })
  mocked.messages.mockResolvedValue({ items: [], next_cursor: null })
  mocked.usage.mockResolvedValue({
    coverage: { status: 'NO_CALLS', reported_attempts: 0, total_attempts: 0 },
    attempts: [],
    totals: null,
    next_cursor: null
  })
  mocked.trajectoryDetail.mockResolvedValue({
    termination: null,
    summary: {
      tenant_id: 'tenant-A',
      tenant_name: '测试机构',
      session_id: 'session-A',
      root_input_id: 'input-7',
      agent_id: 'agent-A',
      agent_name: '测试 Agent',
      input: '用户输入',
      output: '公开结果',
      status: 'COMPLETED',
      answer_status: 'COMPLETED',
      started_at: null,
      finished_at: null,
      duration_ms: null,
      trace_ids: [],
      models: [],
      tools: [],
      step_count: 0,
      model_calls: 0,
      tool_calls: 0,
      totals: null,
      coverage: null
    },
    messages: [],
    usage: null,
    snapshot: 'snapshot:input-7',
    read_at: '2026-10-07',
    usage_summary: {
      calls: 0,
      tokens: null,
      prompt_tokens: null,
      completion_tokens: null,
      amount_by_currency: {},
      unknown_usage_calls: 0,
      cost_coverage: {
        status: 'NO_COSTS',
        total_events: 0,
        priced_events: 0,
        unconfirmed_events: 0,
        missing_model_attempts: 0
      }
    }
  })
  mocked.trajectoryNodes.mockResolvedValue({
    items: [],
    ancestors: [],
    next_cursor: null,
    snapshot: 'snapshot:input-7',
    coverage: 'NOT_RECORDED',
    read_at: '2026-10-07'
  })
  mocked.agent.mockResolvedValue({ id: '1', draft: config, revision: 1, status: 'draft' })
  mocked.options.mockResolvedValue({ skills: [], tools: [], deployments: [] })
  mocked.versions.mockResolvedValue([{ version: 2, config, created_at: '2026-10-07' }])
  mocked.runs.mockResolvedValue([])
})
afterEach(() => {
  wrappers.splice(0).forEach((w) => w.unmount())
  vi.restoreAllMocks()
  document.body.innerHTML = ''
})
async function open(component: Component, path: string, routePath = path) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: routePath, component },
      { path: '/platform/trajectories', component: { template: '<div />' } },
      { path: '/platform/sessions', component: { template: '<div />' } }
    ]
  })
  await router.push(path)
  await router.isReady()
  const wrapper = mount(component, {
    attachTo: document.body,
    global: {
      plugins: [router],
      directives: {
        permission: createPermissionDirective(() => computed(() => currentAuth.user.permissions))
      }
    }
  })
  wrappers.push(wrapper)
  return wrapper
}
async function chooseZip(wrapper: VueWrapper, file: File) {
  const input = wrapper.getComponent(ElUpload).get('input[type="file"]')
  Object.defineProperty(input.element, 'files', { configurable: true, value: [file] })
  await input.trigger('change')
  await flushPromises()
}
describe('平台 Element Plus 控件收口', () => {
  it('统计组件区分尚未加载与真实零值', async () => {
    let finish!: (value: unknown) => void
    mocked.tenants.mockImplementation(
      () =>
        new Promise((resolve) => {
          finish = resolve
        })
    )
    const wrapper = await open(HomePage, '/platform/home')
    await flushPromises()
    expect(wrapper.findAllComponents(ElStatistic)).toHaveLength(3)
    expect(wrapper.findAll('.el-statistic__number').map((w) => w.text())).toEqual(['—', '—', '—'])
    finish({ items: [], total: 0 })
    await flushPromises()
    expect(wrapper.findAll('.el-statistic__number').map((w) => w.text())).toEqual(['0', '0', '0'])
  })
  it('ZIP选择只经草稿导入接口，保留修订并允许再次选择相同文件', async () => {
    const wrapper = await open(
      SkillWorkbenchPage,
      '/platform/skills/3',
      '/platform/skills/:definitionId'
    )
    await flushPromises()
    const file = new File(['package'], 'skill.zip', { type: 'application/zip' })
    await chooseZip(wrapper, file)
    expect(mocked.importZip).toHaveBeenNthCalledWith(1, '7', 2, file)
    await chooseZip(wrapper, file)
    expect(mocked.importZip).toHaveBeenNthCalledWith(2, '7', 3, file)
  })
  it('未保存技能通过一次请求保存发布并展示校验警告', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue(undefined as never)
    mocked.saveAndPublish.mockImplementation(async (requestId: string) => ({
      request_id: requestId,
      status: 'SUCCEEDED',
      draft_id: '7',
      definition_id: '3',
      revision: 3,
      publication: { version_id: '9', definition_revision: 2 },
      validation: {
        valid: true,
        issues: [{ severity: 'WARNING', code: 'WARNING', message: '依赖尚未核实' }]
      }
    }))
    const wrapper = await open(
      SkillWorkbenchPage,
      '/platform/skills/3',
      '/platform/skills/:definitionId'
    )
    await flushPromises()
    const version = wrapper
      .findAllComponents(ElFormItem)
      .find((item) => item.props('label') === '版本标识')!
    await version.getComponent(ElInput).get('input').setValue('v2')
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '保存并发布')!
      .trigger('click')
    await flushPromises()
    expect(mocked.saveAndPublish).toHaveBeenCalledTimes(1)
    expect(mocked.publishDraft).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('依赖尚未核实')
  })
  it('发布结果未知时保留原请求，核对只读取回执', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue(undefined as never)
    mocked.saveAndPublish.mockRejectedValueOnce(new Error('连接中断'))
    const wrapper = await open(
      SkillWorkbenchPage,
      '/platform/skills/3',
      '/platform/skills/:definitionId'
    )
    await flushPromises()
    const version = wrapper
      .findAllComponents(ElFormItem)
      .find((item) => item.props('label') === '版本标识')!
    await version.getComponent(ElInput).get('input').setValue('v2')
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '保存并发布')!
      .trigger('click')
    await flushPromises()
    const requestId = mocked.saveAndPublish.mock.calls[0]![0]
    expect(wrapper.text()).toContain('发布结果未知')
    mocked.publishResult.mockResolvedValueOnce({
      request_id: requestId,
      status: 'REJECTED',
      error_message: '版本无效'
    })
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '核对原操作')!
      .trigger('click')
    await flushPromises()
    expect(mocked.publishResult).toHaveBeenCalledWith(requestId)
    expect(mocked.saveAndPublish).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).not.toContain('发布结果未知')
    expect((version.get('input').element as HTMLInputElement).value).toBe('v2')
  })
  it('可使用原请求 ID 查询历史回执而不覆盖未保存内容', async () => {
    const requestId = 'd4999064-006f-4af4-af0f-04344f345ca5'
    mocked.publishResult.mockResolvedValueOnce({
      request_id: requestId,
      status: 'SUCCEEDED',
      publication: { version_id: '9', definition_revision: 2 }
    })
    const wrapper = await open(
      SkillWorkbenchPage,
      '/platform/skills/3',
      '/platform/skills/:definitionId'
    )
    await flushPromises()
    const version = wrapper
      .findAllComponents(ElFormItem)
      .find((item) => item.props('label') === '版本标识')!
    await version.getComponent(ElInput).get('input').setValue('v2')
    await wrapper.get('input[aria-label="原请求 ID"]').setValue(requestId)
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '查询发布回执')!
      .trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('发布版本 9')
    expect((version.get('input').element as HTMLInputElement).value).toBe('v2')
    expect(mocked.saveAndPublish).not.toHaveBeenCalled()
  })
  it('技能校验分别展示未支持扩展和环境未核实提示', async () => {
    mocked.validateDraft.mockResolvedValue({
      valid: true,
      issues: [
        {
          path: 'SKILL.md',
          line: null,
          severity: 'WARNING',
          code: 'UNSUPPORTED',
          message: 'hooks 不会执行'
        },
        {
          path: 'SKILL.md',
          line: null,
          severity: 'INFO',
          code: 'UNVERIFIED',
          message: '运行依赖尚未核实'
        }
      ]
    })
    const wrapper = await open(
      SkillWorkbenchPage,
      '/platform/skills/3',
      '/platform/skills/:definitionId'
    )
    await flushPromises()
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '校验')!
      .trigger('click')
    await flushPromises()
    expect(wrapper.find('.el-alert--warning').text()).toContain('hooks 不会执行')
    expect(wrapper.find('.el-alert--info').text()).toContain('运行依赖尚未核实')
    expect(wrapper.text()).toContain('导入与校验不会执行包内命令')
    const version = wrapper
      .findAllComponents(ElFormItem)
      .find((item) => item.props('label') === '版本标识')!
    await version.getComponent(ElInput).get('input').setValue('platform-v2')
    expect(wrapper.text()).not.toContain('hooks 不会执行')
  })
  it('校验当前编辑内容无需先保存', async () => {
    mocked.validateDraftContent.mockResolvedValueOnce({
      valid: true,
      issues: [{ severity: 'WARNING', code: 'WARN', message: '需要核实环境' }]
    })
    const wrapper = await open(
      SkillWorkbenchPage,
      '/platform/skills/3',
      '/platform/skills/:definitionId'
    )
    await flushPromises()
    const version = wrapper
      .findAllComponents(ElFormItem)
      .find((item) => item.props('label') === '版本标识')!
    await version.getComponent(ElInput).get('input').setValue('v2')
    await wrapper
      .findAll('button')
      .find((item) => item.text() === '校验')!
      .trigger('click')
    await flushPromises()
    expect(mocked.validateDraftContent).toHaveBeenCalledWith(
      expect.objectContaining({ draftId: '7', revision: 2, versionKey: 'v2' })
    )
    expect(mocked.validateDraft).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('需要核实环境')
    expect((version.get('input').element as HTMLInputElement).value).toBe('v2')
  })
  it('取消ZIP覆盖确认保留本地草稿，不发送导入请求', async () => {
    const wrapper = await open(
      SkillWorkbenchPage,
      '/platform/skills/3',
      '/platform/skills/:definitionId'
    )
    await flushPromises()
    const version = wrapper
      .findAllComponents(ElFormItem)
      .find((item) => item.props('label') === '版本标识')!
    await version.getComponent(ElInput).get('input').setValue('local-v2')
    vi.spyOn(ElMessageBox, 'confirm').mockRejectedValue('cancel')
    await chooseZip(wrapper, new File(['package'], 'skill.zip'))
    expect(mocked.importZip).not.toHaveBeenCalled()
    expect((version.get('input').element as HTMLInputElement).value).toBe('local-v2')
  })
  it('轮次菜单保留深链选中并可切换回全部消息', async () => {
    const wrapper = await open(
      SessionDetailPage,
      '/platform/sessions/detail?tenant_id=tenant-A&session_id=session-A&root_input_id=input-7&node_id=node-9&tab=trace',
      '/platform/sessions/detail'
    )
    await flushPromises()
    expect(wrapper.getComponent(ElMenu).props('defaultActive')).toBe('round:input-7')
    expect(mocked.trajectoryDetail).toHaveBeenCalledWith(
      { tenant_id: 'tenant-A', session_id: 'session-A', root_input_id: 'input-7' },
      { signal: expect.any(AbortSignal), silent: true }
    )
    expect(mocked.trajectoryNodes).toHaveBeenCalledWith(
      {
        tenant_id: 'tenant-A',
        session_id: 'session-A',
        root_input_id: 'input-7',
        snapshot: 'snapshot:input-7',
        node_id: 'node-9',
        query: undefined,
        page_size: 100
      },
      { signal: expect.any(AbortSignal), silent: true }
    )
    const all = wrapper
      .findAll('[role="menuitem"]')
      .find((item) => item.text().includes('全部消息与用量'))!
    await all.trigger('click')
    await flushPromises()
    expect(wrapper.getComponent(ElMenu).props('defaultActive')).toBe('all')
    expect(mocked.messages).toHaveBeenLastCalledWith(
      'tenant-A',
      'session-A',
      undefined,
      undefined,
      50,
      { signal: expect.any(AbortSignal), silent: true }
    )
  })
  it('发布历史通过折叠面板展示提示词，恢复操作仍独立可用', async () => {
    const wrapper = await open(
      SystemAgentDetailPage,
      '/platform/system-agents/1',
      '/platform/system-agents/:id'
    )
    await flushPromises()
    const businessCalls = wrapper
      .findAllComponents(ElFormItem)
      .find((field) => field.props('label') === '最大业务模型调用次数')!
      .getComponent(ElInputNumber)
    expect(businessCalls.props('min')).toBe(1)
    expect(businessCalls.props('max')).toBe(100)
    expect(businessCalls.props('modelValue')).toBe(3)
    expect(wrapper.text()).not.toContain('限制单次执行步骤数')
    const tab = wrapper.findAll('[role="tab"]').find((t) => t.text() === '发布历史')!
    await tab.trigger('click')
    const collapse = wrapper
      .findAllComponents(ElCollapseItem)
      .find((item) => item.props('title') === '完整发布配置')!
    const trigger = collapse.get('[role="button"]')
    expect(trigger.attributes('aria-expanded')).toBe('false')
    await trigger.trigger('click')
    await flushPromises()
    expect(trigger.attributes('aria-expanded')).toBe('true')
    expect(collapse.text()).toContain('仅总结提供的事实')
    expect(wrapper.text()).toContain('恢复为草稿')
  })
  it('取消系统智能体发布确认不发送写请求', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockRejectedValue('cancel')
    const wrapper = await open(
      SystemAgentDetailPage,
      '/platform/system-agents/1',
      '/platform/system-agents/:id'
    )
    await flushPromises()
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '发布')!
      .trigger('click')
    await flushPromises()
    expect(mocked.publishAgent).not.toHaveBeenCalled()
    expect(ElMessageBox.confirm).toHaveBeenCalledWith(
      expect.anything(),
      '系统智能体',
      expect.objectContaining({ confirmButtonText: '发布', cancelButtonText: '取消' }),
      undefined
    )
  })
  it('Skill 直接发布未知后显式使用原身份继续，拒绝不解锁', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue(
      'confirm' as Awaited<ReturnType<typeof ElMessageBox.confirm>>
    )
    mocked.publishDraft.mockRejectedValueOnce(new Error('回执丢失'))
    const first = await open(
      SkillWorkbenchPage,
      '/platform/skills/3',
      '/platform/skills/:definitionId'
    )
    await flushPromises()
    await first
      .findAll('button')
      .find((button) => button.text() === '发布')!
      .trigger('click')
    await flushPromises()
    expect(first.text()).toContain('技能发布结果尚未确认')
    const original = mocked.publishDraft.mock.calls[0]
    first.unmount()
    const restored = await open(
      SkillWorkbenchPage,
      '/platform/skills/3',
      '/platform/skills/:definitionId'
    )
    await flushPromises()
    expect(restored.text()).toContain('技能发布结果尚未确认')
    mocked.publishDraft.mockRejectedValueOnce(
      new HttpClientError({ status: 409, code: 'CONFLICT', userMessage: '冲突' })
    )
    await restored
      .findAll('button')
      .find((button) => button.text() === '继续原发布')!
      .trigger('click')
    await flushPromises()
    expect(restored.text()).toContain('技能发布结果尚未确认')
    expect(mocked.publishDraft.mock.calls[1]).toEqual(original)
    mocked.publishDraft.mockResolvedValueOnce({ versionId: '44', definitionRevision: 2 })
    await restored
      .findAll('button')
      .find((button) => button.text() === '继续原发布')!
      .trigger('click')
    await flushPromises()
    expect(restored.text()).not.toContain('技能发布结果尚未确认')
    expect(mocked.publishDraft.mock.calls[2]).toEqual(original)
  })
  it('Skill 发布确认后存储恢复清除错误并解锁', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue(
      'confirm' as Awaited<ReturnType<typeof ElMessageBox.confirm>>
    )
    mocked.publishDraft.mockResolvedValue({ versionId: '44', definitionRevision: 2 })
    const wrapper = await open(
      SkillWorkbenchPage,
      '/platform/skills/3',
      '/platform/skills/:definitionId'
    )
    await flushPromises()
    const remove = vi.spyOn(Storage.prototype, 'removeItem').mockImplementationOnce(() => {
      throw Error('storage')
    })
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '发布')!
      .trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('无法清除原发布记录')
    expect(wrapper.text()).toContain('技能发布结果尚未确认')
    remove.mockRestore()
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '继续原发布')!
      .trigger('click')
    await flushPromises()
    expect(wrapper.text()).not.toContain('无法清除原发布记录')
    expect(wrapper.text()).not.toContain('技能发布结果尚未确认')
    expect(
      wrapper
        .findAll('button')
        .find((button) => button.text() === '保存草稿')!
        .attributes('disabled')
    ).toBeUndefined()
  })
  it('Skill发布已成功但读取失败时保留成功身份并阻止重复发布', async () => {
    mocked.validateDraft.mockResolvedValue({ valid: true, issues: [] })
    mocked.publishDraft.mockResolvedValue({
      versionId: '44',
      definitionRevision: 2,
      validation: {
        valid: true,
        issues: [
          {
            path: 'SKILL.md',
            line: 1,
            message: '请核对兼容性',
            code: 'notice',
            severity: 'WARNING'
          }
        ]
      }
    })
    mocked.draft
      .mockImplementationOnce(async () => structuredClone(draft))
      .mockRejectedValueOnce(new Error('读取失败'))
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue(
      'confirm' as Awaited<ReturnType<typeof ElMessageBox.confirm>>
    )
    const wrapper = await open(
      SkillWorkbenchPage,
      '/platform/skills/3',
      '/platform/skills/:definitionId'
    )
    await flushPromises()
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '发布')!
      .trigger('click')
    await flushPromises()
    expect(mocked.publishDraft).toHaveBeenCalledTimes(1)
    expect(mocked.validateDraft).not.toHaveBeenCalled()
    expect(ElMessageBox.confirm).toHaveBeenCalledWith(
      expect.stringContaining('已固定版本的 Agent 需显式更新挂载'),
      '发布 Skill',
      expect.any(Object),
      undefined
    )
    const publicationStatus = wrapper
      .findAll('.el-alert')
      .filter((alert) => alert.text().includes('Skill 已发布'))
    expect(publicationStatus).toHaveLength(1)
    expect(publicationStatus[0]!.text()).toContain('当前草稿尚未读取')
    expect(publicationStatus[0]!.text()).toContain('重新读取已保存内容')
    expect(publicationStatus[0]!.text()).toContain('不要重复发布')
    const publish = wrapper.findAll('button').find((button) => button.text() === '发布')!
    expect(publish.attributes('disabled')).toBeDefined()
    await publish.trigger('click')
    await flushPromises()
    expect(mocked.publishDraft).toHaveBeenCalledTimes(1)
    mocked.draft.mockRejectedValueOnce(new Error('仍不可用'))
    const reread = () =>
      wrapper.findAll('button').find((button) => button.text() === '重新读取已保存内容')!
    await reread().trigger('click')
    await flushPromises()
    expect(document.body.querySelector('.el-message--error')?.textContent).toContain('仍不可用')
    expect(publish.attributes('disabled')).toBeDefined()
    expect(
      wrapper
        .findAll('button')
        .find((button) => button.text() === '保存草稿')!
        .attributes('disabled')
    ).toBeDefined()
    mocked.draft.mockResolvedValueOnce({ ...structuredClone(draft), revision: 3 })
    await reread().trigger('click')
    await flushPromises()
    expect(publish.attributes('disabled')).toBeUndefined()
  })
  it('Skill 发布被服务端校验拒绝后读取问题，不重复发布', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue(
      'confirm' as Awaited<ReturnType<typeof ElMessageBox.confirm>>
    )
    mocked.publishDraft.mockRejectedValueOnce(
      new HttpClientError({
        status: 422,
        code: 'CAPABILITY_DESCRIPTOR_INVALID',
        userMessage: '技能包无效'
      })
    )
    mocked.validateDraft.mockResolvedValueOnce({
      valid: false,
      issues: [
        { path: 'SKILL.md', line: 4, message: '缺少说明', code: 'invalid', severity: 'ERROR' }
      ]
    })
    const wrapper = await open(
      SkillWorkbenchPage,
      '/platform/skills/3',
      '/platform/skills/:definitionId'
    )
    await flushPromises()
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '发布')!
      .trigger('click')
    await flushPromises()
    expect(mocked.publishDraft).toHaveBeenCalledTimes(1)
    expect(mocked.validateDraft).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('缺少说明')
  })
  it('Skill 校验问题只为真实包内路径提供文件行定位', async () => {
    mocked.validateDraft.mockResolvedValue({
      valid: false,
      issues: [
        { path: 'SKILL.md', line: 4, message: '缺少说明', code: 'content_required' },
        { path: 'missing.txt', line: 2, message: '不存在文件', code: 'missing_file' }
      ]
    })
    mocked.revealFile.mockResolvedValue(true)
    const wrapper = await open(
      SkillWorkbenchPage,
      '/platform/skills/3',
      '/platform/skills/:definitionId'
    )
    await flushPromises()
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '校验')!
      .trigger('click')
    await flushPromises()
    const links = wrapper.findAll('button').filter((button) => button.text().includes('定位文件'))
    expect(links).toHaveLength(1)
    await links[0]!.trigger('click')
    expect(mocked.revealFile).toHaveBeenCalledExactlyOnceWith('SKILL.md', 4)
  })
  it('工作台一项统计失败仍保留其他成功结果', async () => {
    mocked.tenants.mockRejectedValue(new Error('不可用'))
    mocked.reviews.mockResolvedValue({ total: 4 })
    mocked.models.mockResolvedValue([{ id: 'm1' }])
    const wrapper = await open(HomePage, '/platform/home')
    await flushPromises()
    expect(wrapper.findAll('.el-statistic__number').map((item) => item.text())).toEqual([
      '—',
      '1',
      '4'
    ])
    expect(document.body.querySelector('.el-message--warning')?.textContent).toContain(
      '机构统计加载失败'
    )
  })
  it('切换工具版本清除原部署并拒绝提交不关联部署', async () => {
    mocked.models.mockResolvedValue([{ id: 'm1', name: '模型', kind: 'LLM', enabled: true }])
    mocked.agent.mockResolvedValue({
      id: '1',
      revision: 1,
      status: 'draft',
      draft: {
        ...config,
        model_config_id: 'm1',
        mounts: [
          {
            capability_type: 'TOOL',
            capability_definition_id: 'tool1',
            tool_deployment_id: 'd1',
            priority: 0,
            token_budget: null,
            activation_mode: null
          }
        ]
      }
    })
    mocked.options.mockResolvedValue({
      skills: [],
      tools: [
        { id: 'tool1', name: '工具一' },
        { id: 'tool2', name: '工具二' }
      ],
      deployments: [
        {
          id: 'd1',
          name: '部署一',
          tool_definition_id: 'tool1',
          tool_version_id: 'v1',
          environment: 'TEST'
        }
      ]
    })
    const wrapper = await open(
      SystemAgentDetailPage,
      '/platform/system-agents/1',
      '/platform/system-agents/:id'
    )
    await flushPromises()
    const version = wrapper
      .findAllComponents(ElFormItem)
      .find((item) => item.props('label') === '能力（跟随当前发布）')!
      .getComponent(ElSelect)
    version.vm.$emit('update:modelValue', 'tool2')
    version.vm.$emit('change', 'tool2')
    await flushPromises()
    const deployment = wrapper
      .findAllComponents(ElFormItem)
      .find((item) => item.props('label') === '工具部署')!
      .getComponent(ElSelect)
    expect(deployment.props('modelValue')).toBeNull()
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(mocked.updateAgent).not.toHaveBeenCalled()
    expect(document.body.querySelector('.el-message')?.textContent).toContain(
      '工具部署必须属于所选工具'
    )
  })
  it('发布后继续编辑同一工作草稿，不新建业务身份', async () => {
    mocked.definition.mockResolvedValue({
      id: '3',
      name: '测试 Skill',
      revision: 1,
      defaultVersionId: '12'
    })
    mocked.validateDraft.mockResolvedValue({ valid: true, issues: [] })
    mocked.publishDraft.mockResolvedValue({
      versionId: '44',
      definitionRevision: 2,
      validation: {
        valid: true,
        issues: [
          {
            path: 'SKILL.md',
            line: 1,
            message: '请核对兼容性',
            code: 'notice',
            severity: 'WARNING'
          }
        ]
      }
    })
    mocked.getVersion.mockResolvedValue({ ...structuredClone(draft), id: '44' })
    mocked.createDraft.mockResolvedValue(structuredClone(draft))
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue(
      'confirm' as Awaited<ReturnType<typeof ElMessageBox.confirm>>
    )
    const wrapper = await open(
      SkillWorkbenchPage,
      '/platform/skills/3',
      '/platform/skills/:definitionId'
    )
    await flushPromises()
    await wrapper
      .findAll('button')
      .find((b) => b.text() === '发布')!
      .trigger('click')
    await flushPromises()
    expect(mocked.draft).toHaveBeenCalledTimes(2)
    expect(mocked.createDraft).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('请核对兼容性')
    expect(wrapper.findAll('button').some((button) => button.text() === '保存草稿')).toBe(true)
    expect(
      wrapper
        .findAllComponents(ElInput)
        .some((input) => input.props('modelValue') === draft.versionKey && !input.props('disabled'))
    ).toBe(true)
  })
})
