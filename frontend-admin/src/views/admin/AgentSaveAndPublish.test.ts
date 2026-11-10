import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { ElInput, ElMessageBox, ElSelect, ElTabs } from 'element-plus'
import { useRoute } from 'vue-router'
import { HttpClientError } from '@skillforge/shared/utils/http'
import { message } from '@skillforge/shared/feedback'
import FeedbackNotice from '@skillforge/shared/feedback/FeedbackNotice.vue'
import { useAuthStore } from '../../stores/auth'
import { api } from '../../api'
import type { AgentSaveAndPublishCommand } from '../../api'
import AgentManagePage from './AgentManagePage.vue'

const mocks = vi.hoisted(() => ({
  get: vi.fn(),
  update: vi.fn(),
  publish: vi.fn(),
  publishWithReceipt: vi.fn(),
  saveAndPublish: vi.fn(),
  result: vi.fn(),
  push: vi.fn(),
  publications: vi.fn(),
  browserSession: 'browser-session'
}))
vi.mock('../../stores/auth', async () => {
  const { reactive } = await import('vue')
  const state = reactive({
    identityVersion: 1,
    sessionIdentity: 'tenant-1:actor-1:server-session:browser-session:full',
    user: {
      tenant_id: 'tenant-1',
      sub: 'actor-1',
      session_id: 'server-session',
      permissions: [] as string[]
    }
  })
  return { useAuthStore: () => state }
})
vi.mock('vue-router', async () => {
  const { reactive } = await import('vue')
  const route = reactive({ params: { id: 'agent-1' } })
  return {
    useRoute: () => route,
    useRouter: () => ({ push: mocks.push }),
    onBeforeRouteLeave: vi.fn(),
    onBeforeRouteUpdate: vi.fn()
  }
})
vi.mock('../../api', () => ({
  getSessionId: () => mocks.browserSession,
  api: {
    agents: {
      get: mocks.get,
      list: vi.fn(async () => ({ items: [] })),
      update: mocks.update,
      publish: mocks.publish,
      publishWithReceipt: mocks.publishWithReceipt,
      saveAndPublish: mocks.saveAndPublish,
      saveAndPublishResult: mocks.result,
      revisions: mocks.publications,
      capabilityMounts: {}
    },
    settings: { availableModels: vi.fn(async () => [{ id: 'model', name: '模型', kind: 'LLM' }]) },
    capabilities: { getVersion: vi.fn() }
  }
}))
vi.mock('../../api/tools', () => ({ toolsApi: { available: vi.fn(async () => ({ items: [] })) } }))
const detail = (id = 'agent-1') => ({
  id,
  definition_managed: false,
  agent_key: id,
  name: '原智能体',
  role_description: '',
  model_config_id: 'model',
  system_prompt: '原提示词',
  status: 'published',
  published_revision: 2,
  has_draft_changes: false,
  mounts: [],
  execution_config: {},
  delegation_config: { enabled: false, target_agent_ids: [] },
  config: { extension: '保留', memory: { extension: true } }
})
const receipt = (command: AgentSaveAndPublishCommand) => ({
  request_id: command.request_id,
  agent_id: command.agent_id,
  publication_id: '9007199254740995',
  revision: 3,
  published_at: '2026-10-30T10:00:00Z'
})
let wrapper: VueWrapper
beforeEach(() => {
  vi.resetAllMocks()
  sessionStorage.clear()
  useAuthStore().identityVersion = 1
  useAuthStore().user!.session_id = 'server-session'
  mocks.browserSession = 'browser-session'
  useAuthStore().user!.sub = 'actor-1'
  useAuthStore().user!.permissions = [
    'foundation.agents.read',
    'foundation.agents.update',
    'foundation.agents.publish'
  ]
  useRoute().params.id = 'agent-1'
  mocks.get.mockImplementation(async (id: string) => detail(id))
  mocks.saveAndPublish.mockImplementation(async (command: AgentSaveAndPublishCommand) => {
    mocks.get.mockResolvedValue({
      ...detail(command.agent_id),
      ...command.payload,
      published_revision: 3
    })
    return receipt(command)
  })
  mocks.publications.mockResolvedValue({ items: [] })
  vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue(
    'confirm' as Awaited<ReturnType<typeof ElMessageBox.confirm>>
  )
})
afterEach(() => {
  wrapper?.unmount()
  document.body.innerHTML = ''
  vi.restoreAllMocks()
})
it('仅发布权限可按原请求恢复发布，刷新只读核对不重发', async () => {
  useAuthStore().user!.permissions = ['foundation.agents.read', 'foundation.agents.publish']
  mocks.get.mockResolvedValue({ ...detail(), has_draft_changes: true })
  mocks.publishWithReceipt.mockRejectedValueOnce(
    new HttpClientError({ status: 0, userMessage: '结果尚未确认' })
  )
  await render()
  await button('发布').trigger('click')
  await flushPromises()
  expect(mocks.publishWithReceipt).toHaveBeenCalledTimes(1)
  const command = mocks.publishWithReceipt.mock.calls[0]![0]
  expect(command).toEqual({ agent_id: 'agent-1', request_id: expect.any(String) })
  expect(mocks.saveAndPublish).not.toHaveBeenCalled()
  expect(mocks.publish).not.toHaveBeenCalled()
  expect(wrapper.text()).toContain('发布结果尚未确认')
  expect(button('继续原操作')).toBeDefined()
  wrapper.unmount()
  mocks.result.mockResolvedValueOnce({
    request_id: command.request_id,
    agent_id: command.agent_id,
    publication_id: '123',
    revision: 3,
    published_at: '2026-10-30T10:00:00Z'
  })
  await render()
  expect(mocks.result).toHaveBeenCalledExactlyOnceWith('agent-1', command.request_id)
  expect(mocks.publishWithReceipt).toHaveBeenCalledTimes(1)
  expect(button('继续原操作')).toBeUndefined()
  expect(wrapper.text()).toContain('发布已确认')
  expect(sessionStorage.length).toBe(0)
})
it('仅发布未知后继续原操作沿用原请求，无更新权限也可核对', async () => {
  useAuthStore().user!.permissions = ['foundation.agents.read', 'foundation.agents.publish']
  mocks.get.mockResolvedValue({ ...detail(), has_draft_changes: true })
  mocks.publishWithReceipt.mockRejectedValueOnce(
    new HttpClientError({ status: 0, userMessage: '结果尚未确认' })
  )
  await render()
  await button('发布').trigger('click')
  await flushPromises()
  const command = mocks.publishWithReceipt.mock.calls[0]![0]
  mocks.publishWithReceipt.mockResolvedValueOnce({
    ...command,
    publication_id: '123',
    revision: 3,
    published_at: '2026-10-30T10:00:00Z'
  })
  await button('继续原操作').trigger('click')
  await flushPromises()
  expect(mocks.publishWithReceipt.mock.calls.map((call) => call[0])).toEqual([command, command])
  expect(mocks.update).not.toHaveBeenCalled()
  expect(mocks.saveAndPublish).not.toHaveBeenCalled()
})
it.each(['no-read', 'other-actor', 'other-session'])(
  '仅发布刷新在 %s 边界不查询或重发原操作',
  async (boundary) => {
    mocks.get.mockResolvedValue({ ...detail(), has_draft_changes: true })
    mocks.publishWithReceipt.mockRejectedValueOnce(
      new HttpClientError({ status: 0, userMessage: '结果尚未确认' })
    )
    await render()
    await button('发布').trigger('click')
    await flushPromises()
    const command = mocks.publishWithReceipt.mock.calls[0]![0]
    wrapper.unmount()
    if (boundary === 'no-read') useAuthStore().user!.permissions = ['foundation.agents.publish']
    if (boundary === 'other-actor') useAuthStore().user!.sub = 'actor-2'
    const sessionState = useAuthStore() as unknown as { sessionIdentity: string }
    const previousIdentity = sessionState.sessionIdentity
    if (boundary === 'other-session') sessionState.sessionIdentity = 'another-session'
    try {
      await render()
      expect(mocks.result).not.toHaveBeenCalled()
      expect(mocks.publishWithReceipt).toHaveBeenCalledTimes(1)
      if (boundary !== 'no-read') expect(wrapper.text()).not.toContain(command.request_id)
    } finally {
      sessionState.sessionIdentity = previousIdentity
    }
  }
)
it.each([false, true])(
  '仅发布确认后刷新当前历史列表，读取失败=%s 不改变发布回执',
  async (failed) => {
    mocks.get.mockResolvedValue({ ...detail(), has_draft_changes: true })
    mocks.publishWithReceipt.mockImplementation(async (command) => ({
      ...command,
      publication_id: '123',
      revision: 3,
      published_at: '2026-10-30T10:00:00Z'
    }))
    await render()
    const tabs = wrapper.findComponent(ElTabs)
    tabs.vm.$emit('update:modelValue', 'history')
    tabs.vm.$emit('tab-change', 'history')
    await flushPromises()
    expect(mocks.publications).toHaveBeenCalledTimes(1)
    if (failed) mocks.publications.mockRejectedValueOnce(new Error('历史暂不可读'))
    else
      mocks.publications.mockResolvedValueOnce({
        items: [
          {
            revision_id: '123',
            revision: 3,
            name: '新增历史修订',
            published_at: '2026-10-30T10:00:00Z',
            current: true
          }
        ]
      })
    await button('发布').trigger('click')
    await flushPromises()
    expect(mocks.publications).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('发布已确认')
    expect(mocks.publishWithReceipt).toHaveBeenCalledTimes(1)
    expect(sessionStorage.length).toBe(0)
    if (failed) {
      expect(wrapper.findComponent(FeedbackNotice).props('title')).toContain(
        '发布历史读取失败：历史暂不可读'
      )
      await button('刷新历史').trigger('click')
      await flushPromises()
      expect(mocks.publications).toHaveBeenCalledTimes(3)
      expect(mocks.publishWithReceipt).toHaveBeenCalledTimes(1)
    } else expect(wrapper.text()).toContain('新增历史修订')
  }
)
it('刷新后自动只读核对原发布并恢复编辑', async () => {
  await startUnknown()
  const command = mocks.saveAndPublish.mock.calls[0]![0] as AgentSaveAndPublishCommand
  const stored = sessionStorage.getItem(sessionStorage.key(0)!)!
  expect(stored).toContain(command.request_id)
  expect(stored).not.toContain('本次配置')
  expect(stored).not.toContain('原提示词')
  wrapper.unmount()
  mocks.result.mockResolvedValueOnce(receipt(command))
  await render()
  expect(button('继续原操作')).toBeUndefined()
  expect(mocks.result).toHaveBeenCalledWith(command.agent_id, command.request_id)
  expect(mocks.saveAndPublish).toHaveBeenCalledTimes(1)
  expect(wrapper.text()).toContain('保存并发布已确认')
  expect(sessionStorage.length).toBe(0)
})
it('本地记录不可写时不发送发布请求', async () => {
  await render()
  await edit()
  vi.spyOn(Storage.prototype, 'setItem').mockImplementationOnce(() => {
    throw Error('unavailable')
  })
  await button('保存并发布').trigger('click')
  await flushPromises()
  expect(mocks.saveAndPublish).not.toHaveBeenCalled()
  expect(wrapper.text()).toContain('尚未发送发布请求')
})
it('取得回执但本地记录无法清除时保留原请求，可再次核对', async () => {
  await startUnknown()
  const command = mocks.saveAndPublish.mock.calls[0]![0] as AgentSaveAndPublishCommand
  mocks.result.mockResolvedValue(receipt(command))
  vi.spyOn(Storage.prototype, 'removeItem').mockImplementationOnce(() => {
    throw Error('unavailable')
  })
  await button('核对原操作').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('无法清除原发布请求记录')
  expect(button('保存草稿').attributes('disabled')).toBeDefined()
  await button('核对原操作').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('保存并发布已确认')
  expect(mocks.saveAndPublish).toHaveBeenCalledTimes(1)
})
it('刷新后未查到回执继续锁定，切换主体不展示原请求', async () => {
  await startUnknown()
  const command = mocks.saveAndPublish.mock.calls[0]![0] as AgentSaveAndPublishCommand
  wrapper.unmount()
  mocks.result.mockResolvedValueOnce(undefined)
  await render()
  expect(wrapper.text()).toContain('结果仍未确认')
  expect(button('保存草稿').attributes('disabled')).toBeDefined()
  expect(mocks.saveAndPublish).toHaveBeenCalledTimes(1)
  useAuthStore().user!.sub = 'actor-2'
  useAuthStore().identityVersion++
  await flushPromises()
  expect(wrapper.text()).not.toContain(command.request_id)
  useAuthStore().user!.sub = 'actor-1'
  useAuthStore().identityVersion++
  await flushPromises()
  expect(wrapper.text()).toContain(command.request_id)
})
it('刷新后无读取权限时不自动核对原发布', async () => {
  await startUnknown()
  wrapper.unmount()
  useAuthStore().user!.permissions = ['foundation.agents.update', 'foundation.agents.publish']
  await render()
  expect(mocks.result).not.toHaveBeenCalled()
  expect(button('保存草稿').attributes('disabled')).toBeDefined()
})
it('自动核对失败后仍保留请求并允许手动核对', async () => {
  await startUnknown()
  const command = mocks.saveAndPublish.mock.calls[0]![0] as AgentSaveAndPublishCommand
  wrapper.unmount()
  mocks.result
    .mockRejectedValueOnce(new Error('回执读取失败'))
    .mockResolvedValueOnce(receipt(command))
  await render()
  expect(wrapper.findComponent(FeedbackNotice).props('title')).toContain('回执读取失败')
  expect(button('保存草稿').attributes('disabled')).toBeDefined()
  await button('核对原操作').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('保存并发布已确认')
  expect(mocks.saveAndPublish).toHaveBeenCalledTimes(1)
})
it('挂载名称读取失败仍自动核对原发布且保留独立反馈，不重复提交', async () => {
  await startUnknown()
  const command = mocks.saveAndPublish.mock.calls[0]![0] as AgentSaveAndPublishCommand
  wrapper.unmount()
  useAuthStore().user!.permissions.push('foundation.capabilities.read')
  mocks.get.mockResolvedValue({ ...detail(), mounts: [{ capabilityVersionId: 'version-1' }] })
  vi.mocked(api.capabilities.getVersion).mockRejectedValueOnce(new Error('能力版本读取失败'))
  mocks.result.mockResolvedValueOnce(receipt(command))
  await render()
  expect(wrapper.findComponent(FeedbackNotice).props('title')).toContain(
    '能力版本名称读取失败：能力版本读取失败'
  )
  expect(wrapper.text()).toContain('保存并发布已确认')
  expect(mocks.result).toHaveBeenCalledExactlyOnceWith(command.agent_id, command.request_id)
  expect(mocks.saveAndPublish).toHaveBeenCalledTimes(1)
  expect(sessionStorage.length).toBe(0)
  vi.mocked(api.capabilities.getVersion).mockResolvedValueOnce({
    id: 'version-1',
    name: '服务流程',
    versionKey: '2'
  } as Awaited<ReturnType<typeof api.capabilities.getVersion>>)
  const detailReads = mocks.get.mock.calls.length
  await button('重新读取能力名称').trigger('click')
  await flushPromises()
  expect(api.capabilities.getVersion).toHaveBeenCalledTimes(2)
  expect(api.capabilities.getVersion).toHaveBeenLastCalledWith('version-1')
  expect(mocks.get).toHaveBeenCalledTimes(detailReads)
  expect(mocks.result).toHaveBeenCalledTimes(1)
  expect(mocks.saveAndPublish).toHaveBeenCalledTimes(1)
  expect(wrapper.text()).toContain('服务流程 / 2')
  expect(wrapper.findComponent(FeedbackNotice).exists()).toBe(false)
})
it('切换智能体后不采纳旧挂载名称读取结果', async () => {
  useAuthStore().user!.permissions.push('foundation.capabilities.read')
  mocks.get.mockImplementation(async (id: string) => ({
    ...detail(id),
    mounts: [{ capabilityVersionId: id === 'agent-1' ? 'version-1' : 'version-2' }]
  }))
  let resolveOld!: (value: Awaited<ReturnType<typeof api.capabilities.getVersion>>) => void
  vi.mocked(api.capabilities.getVersion)
    .mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          resolveOld = resolve
        })
    )
    .mockResolvedValueOnce({
      id: 'version-2',
      name: '当前能力',
      versionKey: '2'
    } as Awaited<ReturnType<typeof api.capabilities.getVersion>>)
  await render()
  useRoute().params.id = 'agent-2'
  await flushPromises()
  expect(wrapper.text()).toContain('当前能力 / 2')
  resolveOld({ id: 'version-1', name: '旧能力', versionKey: '1' } as Awaited<
    ReturnType<typeof api.capabilities.getVersion>
  >)
  await flushPromises()
  expect(wrapper.text()).toContain('当前能力 / 2')
  expect(wrapper.text()).not.toContain('旧能力 / 1')
})
it('没有能力读取权限时不读取挂载名称，仍自动核对原发布', async () => {
  await startUnknown()
  const command = mocks.saveAndPublish.mock.calls[0]![0] as AgentSaveAndPublishCommand
  wrapper.unmount()
  mocks.get.mockResolvedValue({ ...detail(), mounts: [{ capabilityVersionId: 'version-1' }] })
  mocks.result.mockResolvedValueOnce(receipt(command))
  await render()
  expect(api.capabilities.getVersion).not.toHaveBeenCalled()
  expect(mocks.result).toHaveBeenCalledExactlyOnceWith(command.agent_id, command.request_id)
  expect(mocks.saveAndPublish).toHaveBeenCalledTimes(1)
  expect(wrapper.text()).toContain('保存并发布已确认')
})
async function render() {
  wrapper = mount(AgentManagePage, {
    attachTo: document.body,
    global: { stubs: { RouterLink: { props: ['to'], template: '<a :href="to"><slot /></a>' } } }
  })
  await flushPromises()
}
function button(label: string) {
  return wrapper.findAll('button').find((item) => item.text() === label)!
}

it('已停用且无草稿修改可直接重新发布，仅需发布权限并沿用原请求回执', async () => {
  useAuthStore().user!.permissions = ['foundation.agents.read', 'foundation.agents.publish']
  mocks.get.mockResolvedValue({ ...detail(), status: 'disabled' })
  mocks.publishWithReceipt.mockImplementationOnce(async (command) => {
    mocks.get.mockResolvedValue({ ...detail(), published_revision: 3 })
    return { ...command, publication_id: '123', revision: 3, published_at: '2026-10-30T10:00:00Z' }
  })
  await render()
  expect(button('重新发布').attributes('disabled')).toBeUndefined()
  await button('重新发布').trigger('click')
  await flushPromises()
  expect(mocks.publishWithReceipt).toHaveBeenCalledExactlyOnceWith({
    agent_id: 'agent-1',
    request_id: expect.any(String)
  })
  expect(mocks.update).not.toHaveBeenCalled()
  expect(mocks.saveAndPublish).not.toHaveBeenCalled()
  expect(wrapper.text()).toContain('发布已确认')
  expect(button('重新发布')).toBeUndefined()
})

it('只有更新权限不能重新发布停用的智能体', async () => {
  useAuthStore().user!.permissions = ['foundation.agents.read', 'foundation.agents.update']
  mocks.get.mockResolvedValue({ ...detail(), status: 'disabled' })
  await render()
  await button('重新发布').trigger('click')
  await flushPromises()
  expect(mocks.publishWithReceipt).not.toHaveBeenCalled()
  expect(mocks.update).not.toHaveBeenCalled()
})

it.each([false, true])(
  '公共部署实例不提供普通编辑发布入口，部署读权限=%s',
  async (canReadDeployment) => {
    if (canReadDeployment) useAuthStore().user!.permissions.push('tenant.agent_deployments.read')
    mocks.get.mockResolvedValue({
      ...detail(),
      status: 'disabled',
      definition_managed: true,
      has_draft_changes: true
    })
    await render()
    expect(wrapper.text()).toContain('由公共部署管理')
    expect(button('保存草稿')).toBeUndefined()
    expect(button('发布')).toBeUndefined()
    expect(button('重新发布')).toBeUndefined()
    expect(button('保存发布并试用')).toBeUndefined()
    expect(nameInput().get('input').attributes('disabled')).toBeDefined()
    expect(wrapper.find('a[href="/admin/agent-deployments"]').exists()).toBe(canReadDeployment)
    expect(mocks.publishWithReceipt).not.toHaveBeenCalled()
    expect(mocks.update).not.toHaveBeenCalled()
  }
)
function nameInput() {
  return wrapper
    .findAllComponents(ElInput)
    .find(
      (item) => item.props('modelValue') === '原智能体' || item.props('modelValue') === '本次配置'
    )!
}
async function edit() {
  await nameInput().get('input').setValue('本次配置')
  await flushPromises()
}
function allowTrial() {
  useAuthStore().user!.permissions.push(
    'foundation.sessions.read',
    'foundation.sessions.create',
    'foundation.sessions.run'
  )
}
it('明确保存发布并试用后，释放忙碌状态并进入同一智能体，不创建会话', async () => {
  allowTrial()
  await render()
  await edit()
  mocks.push.mockImplementation(() => {
    expect(button('保存草稿').attributes('disabled')).toBeUndefined()
  })
  await button('保存发布并试用').trigger('click')
  await flushPromises()
  expect(mocks.saveAndPublish).toHaveBeenCalledOnce()
  expect(mocks.push).toHaveBeenCalledExactlyOnceWith({
    path: '/admin/chat',
    query: { agent_id: 'agent-1' }
  })
  expect(sessionStorage.length).toBe(0)
})
it('没有完整试用权限时不提供组合入口，普通发布保留', async () => {
  await render()
  await edit()
  expect(button('保存发布并试用')).toBeUndefined()
  expect(button('保存并发布').attributes('disabled')).toBeUndefined()
})
it('取消确认不发布也不进入试用', async () => {
  allowTrial()
  await render()
  await edit()
  vi.mocked(ElMessageBox.confirm).mockRejectedValueOnce('cancel')
  await button('保存发布并试用').trigger('click')
  await flushPromises()
  expect(mocks.saveAndPublish).not.toHaveBeenCalled()
  expect(mocks.push).not.toHaveBeenCalled()
})
it('发布结果未知时保留原请求，后续普通核对不自动进入试用', async () => {
  allowTrial()
  await render()
  await edit()
  mocks.saveAndPublish.mockRejectedValueOnce(
    new HttpClientError({ status: 0, userMessage: '结果未知' })
  )
  await button('保存发布并试用').trigger('click')
  await flushPromises()
  const command = mocks.saveAndPublish.mock.calls[0]![0] as AgentSaveAndPublishCommand
  expect(wrapper.text()).toContain(command.request_id)
  expect(mocks.push).not.toHaveBeenCalled()
  mocks.result.mockResolvedValueOnce(receipt(command))
  await button('核对原操作').trigger('click')
  await flushPromises()
  expect(mocks.saveAndPublish).toHaveBeenCalledOnce()
  expect(mocks.push).not.toHaveBeenCalled()
})
it('发布收到 401 时保留原请求且不进入试用', async () => {
  allowTrial()
  await render()
  await edit()
  mocks.saveAndPublish.mockRejectedValueOnce(
    new HttpClientError({
      status: 401,
      code: 'UNAUTHORIZED',
      traceId: 'trace-401',
      userMessage: '身份失效'
    })
  )
  await button('保存发布并试用').trigger('click')
  await flushPromises()
  const command = mocks.saveAndPublish.mock.calls[0]![0] as AgentSaveAndPublishCommand
  expect(wrapper.text()).toContain(command.request_id)
  expect(sessionStorage.getItem(sessionStorage.key(0)!)!).toContain(command.request_id)
  expect(mocks.saveAndPublish).toHaveBeenCalledOnce()
  expect(mocks.push).not.toHaveBeenCalled()
})
it.each([
  ['发布版本变化', { published_revision: 4 }],
  ['对象身份变化', { id: 'agent-other' }],
  ['已停用', { status: 'disabled' }]
])('%s时保留发布结果且不自动试用', async (_reason, altered) => {
  allowTrial()
  await render()
  await edit()
  mocks.saveAndPublish.mockImplementationOnce(async (command: AgentSaveAndPublishCommand) => {
    mocks.get.mockResolvedValue({
      ...detail(),
      ...command.payload,
      published_revision: 3,
      ...altered
    })
    return receipt(command)
  })
  await button('保存发布并试用').trigger('click')
  await flushPromises()
  expect(mocks.saveAndPublish).toHaveBeenCalledOnce()
  expect(mocks.push).not.toHaveBeenCalled()
  expect(wrapper.text()).toContain('保存并发布已确认')
})
it('发布已确认但刷新失败时不导航或重复发布', async () => {
  allowTrial()
  await render()
  await edit()
  mocks.saveAndPublish.mockImplementationOnce(async (command: AgentSaveAndPublishCommand) => {
    mocks.get.mockRejectedValueOnce(new Error('读取失败'))
    return receipt(command)
  })
  await button('保存发布并试用').trigger('click')
  await flushPromises()
  expect(mocks.saveAndPublish).toHaveBeenCalledOnce()
  expect(mocks.push).not.toHaveBeenCalled()
  expect(wrapper.text()).toContain('当前显示内容尚未更新')
})
it('刷新在途时浏览器安全会话切换，不回填旧对象或自动跳转', async () => {
  allowTrial()
  await render()
  await edit()
  let resolveDetail!: (value: unknown) => void
  mocks.saveAndPublish.mockImplementationOnce(async (command: AgentSaveAndPublishCommand) => {
    mocks.get.mockReturnValueOnce(
      new Promise((resolve) => {
        resolveDetail = resolve
      })
    )
    return receipt(command)
  })
  await button('保存发布并试用').trigger('click')
  await flushPromises()
  expect(resolveDetail).toBeTypeOf('function')
  mocks.browserSession = 'new-browser-session'
  resolveDetail({
    ...detail(),
    name: '不应回填旧内容',
    mounts: [{ capabilityVersionId: 'old-version' }],
    published_revision: 3
  })
  await flushPromises()
  expect(nameInput().props('modelValue')).toBe('本次配置')
  expect(wrapper.text()).not.toContain('不应回填旧内容')
  expect(wrapper.text()).toContain('能力挂载（0）')
  expect(mocks.push).not.toHaveBeenCalled()
})
it('发布在途时失去试用权限不自动跳转', async () => {
  allowTrial()
  await render()
  await edit()
  let resolvePublish!: (value: unknown) => void
  mocks.saveAndPublish.mockReturnValueOnce(
    new Promise((resolve) => {
      resolvePublish = resolve
    })
  )
  await button('保存发布并试用').trigger('click')
  await flushPromises()
  const command = mocks.saveAndPublish.mock.calls[0]![0] as AgentSaveAndPublishCommand
  useAuthStore().user!.permissions = useAuthStore().user!.permissions.filter(
    (value) => value !== 'foundation.sessions.run'
  )
  mocks.get.mockResolvedValue({ ...detail(), ...command.payload, published_revision: 3 })
  resolvePublish(receipt(command))
  await flushPromises()
  expect(mocks.push).not.toHaveBeenCalled()
  expect(wrapper.text()).toContain('保存并发布已确认')
})
async function startUnknown() {
  await render()
  await edit()
  mocks.saveAndPublish.mockRejectedValueOnce(
    new HttpClientError({ status: 0, userMessage: '结果尚未确认' })
  )
  await button('保存并发布').trigger('click')
  await flushPromises()
}
it('机构智能体可从指定模型切回机构默认并保存', async () => {
  await render()
  const select = wrapper
    .findAllComponents(ElSelect)
    .find((item) => item.props('modelValue') === 'model')!
  select.vm.$emit('update:modelValue', '__institution_default__')
  await flushPromises()
  expect(wrapper.text()).toContain('继承机构默认模型')
  await button('保存草稿').trigger('click')
  await flushPromises()
  expect(mocks.update).toHaveBeenCalledWith(
    'agent-1',
    expect.objectContaining({ model_config_id: '' })
  )
})
it('缺少机构默认模型时提示配置入口并保留草稿', async () => {
  await render()
  const select = wrapper
    .findAllComponents(ElSelect)
    .find((item) => item.props('modelValue') === 'model')!
  select.vm.$emit('update:modelValue', '__institution_default__')
  await flushPromises()
  mocks.saveAndPublish.mockRejectedValueOnce(
    new HttpClientError({
      status: 422,
      code: 'INSTITUTION_MODEL_SELECTION_REQUIRED',
      traceId: 'trace',
      userMessage: '请求无效'
    })
  )
  const feedback = vi.spyOn(message, 'error')
  await button('保存并发布').trigger('click')
  await flushPromises()
  expect(feedback).toHaveBeenCalledWith('请先在机构设置中配置默认对话模型，再发布智能体')
  expect(wrapper.text()).not.toContain('保存并发布结果尚未确认')
  expect(button('保存草稿').attributes('disabled')).toBeUndefined()
})
it('本次配置经一次确认和单POST保存并发布，不先PATCH或读取，不包含status', async () => {
  await render()
  expect(button('发布').attributes('disabled')).toBeDefined()
  expect(button('保存并发布')).toBeUndefined()
  await edit()
  expect(button('发布')).toBeUndefined()
  mocks.get.mockClear()
  await button('保存并发布').trigger('click')
  await flushPromises()
  expect(ElMessageBox.confirm).toHaveBeenCalledOnce()
  expect(mocks.saveAndPublish).toHaveBeenCalledOnce()
  const command = mocks.saveAndPublish.mock.calls[0]![0] as AgentSaveAndPublishCommand
  expect(command).toMatchObject({
    agent_id: 'agent-1',
    payload: { name: '本次配置', config: { extension: '保留' } }
  })
  expect(command.request_id).toMatch(/^[0-9a-f-]{36}$/)
  expect(command.payload).not.toHaveProperty('status')
  expect(mocks.update).not.toHaveBeenCalled()
  expect(mocks.publish).not.toHaveBeenCalled()
  expect(mocks.saveAndPublish.mock.invocationCallOrder[0]).toBeLessThan(
    mocks.get.mock.invocationCallOrder[0]!
  )
  expect(wrapper.text()).toContain('保存并发布已确认：修订 3')
})
it.each([
  { permissions: [] },
  { permissions: ['foundation.agents.update'] },
  { permissions: ['foundation.agents.publish'] }
])('双权限缺失不展示组合操作：%j', async ({ permissions }) => {
  useAuthStore().user!.permissions = permissions
  await render()
  await edit()
  expect(button('保存并发布')).toBeUndefined()
  expect(mocks.saveAndPublish).not.toHaveBeenCalled()
})
it.each([
  { status: 422, code: 'VALIDATION_FAILED' },
  { status: 409, code: 'AGENT_DEFINITION_MANAGED' }
])('事务明确失败保留表单，新动作经重新确认使用新请求号：$code', async ({ status, code }) => {
  await render()
  await edit()
  mocks.saveAndPublish.mockRejectedValueOnce(
    new HttpClientError({
      status,
      code,
      traceId: 'trace',
      userMessage: '发布校验失败，未保存配置'
    })
  )
  await button('保存并发布').trigger('click')
  await flushPromises()
  expect(wrapper.text()).not.toContain('结果核对')
  expect(nameInput().props('modelValue')).toBe('本次配置')
  expect(button('保存并发布').attributes('disabled')).toBeUndefined()
  expect(button('保存草稿').attributes('disabled')).toBeUndefined()
  await button('保存并发布').trigger('click')
  await flushPromises()
  expect(mocks.saveAndPublish.mock.calls[1]![0].request_id).not.toBe(
    mocks.saveAndPublish.mock.calls[0]![0].request_id
  )
  expect(ElMessageBox.confirm).toHaveBeenCalledTimes(2)
})
it('掉响应后冻结原命令，204核对仍未知且继续发送同key/body，不再确认换内容', async () => {
  await startUnknown()
  const command = structuredClone(mocks.saveAndPublish.mock.calls[0]![0])
  expect(nameInput().get('input').attributes('disabled')).toBeDefined()
  expect(button('保存草稿').attributes('disabled')).toBeDefined()
  mocks.result.mockResolvedValueOnce(undefined)
  await button('核对原操作').trigger('click')
  await flushPromises()
  expect(mocks.result).toHaveBeenCalledExactlyOnceWith('agent-1', command.request_id)
  expect(wrapper.text()).toContain('结果仍未确认')
  expect(button('继续原操作').attributes('disabled')).toBeUndefined()
  await button('继续原操作').trigger('click')
  await flushPromises()
  expect(mocks.saveAndPublish.mock.calls[1]![0]).toEqual(command)
  expect(ElMessageBox.confirm).toHaveBeenCalledOnce()
  expect(wrapper.text()).toContain('保存并发布已确认')
})
it('成功原receipt先存，后置GET失败仅刷新恢复不重复提交', async () => {
  await render()
  await edit()
  mocks.saveAndPublish.mockImplementationOnce(async (command: AgentSaveAndPublishCommand) => {
    mocks.get.mockRejectedValueOnce(Error('详情读取失败'))
    return receipt(command)
  })
  await button('保存并发布').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('发布记录 9007199254740995')
  expect(document.body.textContent).toContain('保存并发布已确认，但配置未刷新')
  expect(button('继续原操作')).toBeUndefined()
  await button('重新读取').trigger('click')
  await flushPromises()
  expect(mocks.saveAndPublish).toHaveBeenCalledOnce()
  expect(button('保存草稿').attributes('disabled')).toBeUndefined()
})
it.each(['403', '409', '503', 'broken'])(
  '原UNKNOWN后核对/继续的%s不清原请求或换key',
  async (kind) => {
    await startUnknown()
    const command = mocks.saveAndPublish.mock.calls[0]![0]
    if (kind === 'broken')
      mocks.saveAndPublish.mockResolvedValueOnce({ request_id: command.request_id })
    else
      mocks.saveAndPublish.mockRejectedValueOnce(
        new HttpClientError({
          status: Number(kind),
          code: kind === '409' ? 'AGENT_SAVE_PUBLISH_REQUEST_CONFLICT' : 'FAILURE',
          traceId: 'trace',
          userMessage: '仍需核对'
        })
      )
    await button('继续原操作').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain(command.request_id)
    expect(button('继续原操作')).toBeDefined()
    expect(mocks.saveAndPublish.mock.calls[1]![0]).toEqual(command)
  }
)
it('核对成功采纳原操作回执，关闭页面保护包含未知请求', async () => {
  await startUnknown()
  const command = mocks.saveAndPublish.mock.calls[0]![0]
  const event = new Event('beforeunload', { cancelable: true })
  window.dispatchEvent(event)
  expect(event.defaultPrevented).toBe(true)
  mocks.result.mockResolvedValueOnce(receipt(command))
  await button('核对原操作').trigger('click')
  await flushPromises()
  expect(mocks.saveAndPublish).toHaveBeenCalledOnce()
  expect(wrapper.text()).toContain('保存并发布已确认')
})
it.each(['route', 'identity'])('旧对象迟到成功不发布到新%s', async (kind) => {
  await render()
  await edit()
  let finish!: (value: unknown) => void
  mocks.saveAndPublish.mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        finish = resolve
      })
  )
  await button('保存并发布').trigger('click')
  await flushPromises()
  const command = mocks.saveAndPublish.mock.calls[0]![0]
  if (kind === 'route') useRoute().params.id = 'agent-2'
  else useAuthStore().identityVersion++
  await flushPromises()
  finish(receipt(command))
  await flushPromises()
  expect(wrapper.text()).not.toContain('保存并发布已确认')
  expect(button('继续原操作')).toBeUndefined()
  expect(mocks.get.mock.calls.filter(([id]) => id === 'agent-1')).toHaveLength(1)
})

it('本地校验与影响确认取消均不派发组合请求', async () => {
  await render()
  await nameInput().get('input').setValue('')
  await flushPromises()
  await button('保存并发布').trigger('click')
  await flushPromises()
  expect(mocks.saveAndPublish).not.toHaveBeenCalled()
  expect(ElMessageBox.confirm).not.toHaveBeenCalled()
  wrapper
    .findAllComponents(ElInput)
    .find((item) => item.props('modelValue') === '')!
    .vm.$emit('update:modelValue', '本次配置')
  await flushPromises()
  vi.mocked(ElMessageBox.confirm).mockRejectedValueOnce('cancel')
  await button('保存并发布').trigger('click')
  await flushPromises()
  expect(mocks.saveAndPublish).not.toHaveBeenCalled()
})
it('首次请求号冲突保留原操作，不能默默换号重新派发', async () => {
  await render()
  await edit()
  mocks.saveAndPublish.mockRejectedValueOnce(
    new HttpClientError({
      status: 409,
      code: 'AGENT_SAVE_PUBLISH_REQUEST_CONFLICT',
      traceId: 'trace',
      userMessage: '原请求号内容冲突'
    })
  )
  await button('保存并发布').trigger('click')
  await flushPromises()
  const command = mocks.saveAndPublish.mock.calls[0]![0]
  expect(button('继续原操作')).toBeDefined()
  expect(wrapper.text()).toContain(command.request_id)
  expect(mocks.saveAndPublish).toHaveBeenCalledOnce()
})
it('核对被拒绝或返回其他操作身份均不解除原UNKNOWN', async () => {
  await startUnknown()
  const command = mocks.saveAndPublish.mock.calls[0]![0]
  mocks.result.mockRejectedValueOnce(
    new HttpClientError({
      status: 403,
      code: 'FORBIDDEN',
      traceId: 'trace',
      userMessage: '没有读取权限'
    })
  )
  await button('核对原操作').trigger('click')
  await flushPromises()
  expect(button('继续原操作')).toBeDefined()
  mocks.result.mockResolvedValueOnce({ ...receipt(command), agent_id: 'other-agent' })
  await button('核对原操作').trigger('click')
  await flushPromises()
  expect(wrapper.text()).not.toContain('保存并发布已确认')
  expect(wrapper.text()).toContain(command.request_id)
  expect(mocks.saveAndPublish).toHaveBeenCalledOnce()
})
it('旧对象核对迟到错误不污染新对象或复活原操作', async () => {
  await startUnknown()
  let reject!: (reason: Error) => void
  mocks.result.mockImplementationOnce(
    () =>
      new Promise((_resolve, fail) => {
        reject = fail
      })
  )
  await button('核对原操作').trigger('click')
  await flushPromises()
  useRoute().params.id = 'agent-2'
  await flushPromises()
  reject(new HttpClientError({ status: 503, userMessage: '旧对象核对失败' }))
  await flushPromises()
  expect(button('继续原操作')).toBeUndefined()
  expect(document.body.textContent).not.toContain('旧对象核对失败')
})
