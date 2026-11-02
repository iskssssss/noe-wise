import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { ElInput, ElMessageBox, ElSelect } from 'element-plus'
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
  saveAndPublish: vi.fn(),
  result: vi.fn(),
  push: vi.fn(),
  publications: vi.fn()
}))
vi.mock('../../stores/auth', async () => {
  const { reactive } = await import('vue')
  const state = reactive({
    identityVersion: 1,
    user: { tenant_id: 'tenant-1', sub: 'actor-1', permissions: [] as string[] }
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
  api: {
    agents: {
      get: mocks.get,
      list: vi.fn(async () => ({ items: [] })),
      update: mocks.update,
      publish: mocks.publish,
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
it('能力读取失败时保留原错误，不自动核对覆盖提示', async () => {
  await startUnknown()
  wrapper.unmount()
  mocks.get.mockResolvedValue({ ...detail(), mounts: [{ capabilityVersionId: 'version-1' }] })
  vi.mocked(api.capabilities.getVersion).mockRejectedValueOnce(new Error('能力版本读取失败'))
  await render()
  expect(wrapper.findComponent(FeedbackNotice).props('title')).toContain('能力版本读取失败')
  expect(mocks.result).not.toHaveBeenCalled()
  expect(button('保存草稿').attributes('disabled')).toBeDefined()
})
async function render() {
  wrapper = mount(AgentManagePage, { attachTo: document.body })
  await flushPromises()
}
function button(label: string) {
  return wrapper.findAll('button').find((item) => item.text() === label)!
}
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
