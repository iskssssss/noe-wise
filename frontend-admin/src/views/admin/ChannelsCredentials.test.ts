import { ref } from 'vue'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { ElButton, ElDrawer, ElInput, ElSelect } from 'element-plus'
import { createPermissionDirective } from '@skillforge/shared/directives/permission'
import { HttpClientError } from '@skillforge/shared/utils/http'
import ChannelsPage from './ChannelsPage.vue'
const mocks = vi.hoisted(() => ({
  list: vi.fn(),
  create: vi.fn(),
  onboard: vi.fn(),
  onboardingResult: vi.fn(),
  update: vi.fn(),
  get: vi.fn(),
  agents: vi.fn(),
  rotateToken: vi.fn(),
  bindAgents: vi.fn(),
  copy: vi.fn(),
  error: vi.fn(),
  issueApiKey: vi.fn(),
  rotateApiKey: vi.fn(),
  issueApiKeyWithReceipt: vi.fn(),
  apiKeyIssueResult: vi.fn(),
  confirm: vi.fn()
}))
vi.mock('../../api', () => ({ api: { channels: mocks, agents: { list: mocks.agents } } }))
vi.mock('vue-router', () => ({ onBeforeRouteLeave: vi.fn(), onBeforeRouteUpdate: vi.fn() }))
vi.mock('@skillforge/shared/feedback', async (load) => {
  const actual = await load<typeof import('@skillforge/shared/feedback')>()
  return { ...actual, confirm: mocks.confirm, message: { ...actual.message, error: mocks.error } }
})
const channel = {
  id: 'owned-new',
  name: '首次接入',
  type: 'web_widget',
  status: 'active',
  config: {},
  ui_config: {}
}
const permissions = ref(['foundation.channels.read', 'foundation.channels.create'])
const subject = ref('8')
vi.mock('../../stores/auth', () => ({
  useAuthStore: () => ({
    user: {
      tenant_id: '7',
      get sub() {
        return subject.value
      },
      get permissions() {
        return permissions.value
      }
    }
  })
}))
let wrapper: VueWrapper
const drawer = (prefix: string) =>
  wrapper.findAllComponents(ElDrawer).find((d) => String(d.props('title')).startsWith(prefix))!
async function click(text: string, scope = wrapper) {
  const button = scope.findAllComponents(ElButton).find((b) => b.text() === text)!
  expect(button, text).toBeDefined()
  await button.trigger('click')
  await flushPromises()
}
async function render() {
  wrapper = mount(ChannelsPage, {
    attachTo: document.body,
    global: { directives: { permission: createPermissionDirective(() => permissions) } }
  })
  await flushPromises()
}
async function create() {
  await click('新增渠道')
  drawer('新增渠道').findComponent(ElInput).vm.$emit('update:modelValue', channel.name)
  await flushPromises()
  await click('保存', drawer('新增渠道'))
}
beforeEach(() => {
  vi.resetAllMocks()
  sessionStorage.clear()
  permissions.value = ['foundation.channels.read', 'foundation.channels.create']
  subject.value = '8'
  mocks.list.mockResolvedValue({ items: [channel], total: 1 })
  mocks.create.mockResolvedValue({ ...channel, channel_token: 'one-time-only' })
  mocks.onboard.mockImplementation(async (requestId: string) => ({
    request_id: requestId,
    channel_id: channel.id,
    channel: { ...channel, channel_token: 'one-time-only' }
  }))
  mocks.confirm.mockResolvedValue(true)
  mocks.issueApiKeyWithReceipt.mockImplementation(async (requestId: string, channelId: string) => ({
    request_id: requestId,
    channel_id: channelId,
    api_key: 'new-key',
    api_secret: 'new-secret'
  }))
  mocks.issueApiKey.mockResolvedValue({
    channel_id: channel.id,
    api_key: 'new-key',
    api_secret: 'new-secret'
  })
  mocks.rotateApiKey.mockResolvedValue({
    channel_id: channel.id,
    api_key: 'recovered-key',
    api_secret: 'recovered-secret'
  })
  mocks.get.mockResolvedValue({ ...channel, agent_bindings: [] })
  mocks.agents.mockResolvedValue({ items: [], total: 0 })
  Object.defineProperty(navigator, 'clipboard', {
    configurable: true,
    value: { writeText: mocks.copy }
  })
})
afterEach(() => {
  wrapper?.unmount()
  document.body.innerHTML = ''
})
it('create-only权限可复制首次令牌，关闭清空且不隐式轮换绑定', async () => {
  await render()
  await create()
  expect(drawer('渠道访问凭据').props('modelValue')).toBe(true)
  expect(document.body.textContent).toContain('one-time-only')
  expect(document.body.textContent).not.toContain('已保存令牌，绑定智能体')
  expect(document.body.textContent).not.toContain('轮换渠道令牌')
  await click('复制 channel_token', drawer('渠道访问凭据'))
  expect(mocks.copy).toHaveBeenCalledExactlyOnceWith('one-time-only')
  await click('关闭', drawer('渠道访问凭据'))
  await click('访问凭据')
  expect(document.body.textContent).not.toContain('one-time-only')
  expect(mocks.rotateToken).not.toHaveBeenCalled()
  expect(mocks.bindAgents).not.toHaveBeenCalled()
})
it('创建成功先显示秘密，后续GET失败与刷新不重发POST', async () => {
  await render()
  let reject!: (error: Error) => void
  mocks.list.mockReturnValueOnce(
    new Promise((_, fail) => {
      reject = fail
    })
  )
  await create()
  expect(document.body.textContent).toContain('one-time-only')
  reject(Error('列表尚未刷新'))
  await flushPromises()
  expect(document.body.textContent).toContain('one-time-only')
  await click('刷新')
  expect(mocks.onboard).toHaveBeenCalledTimes(1)
  expect(mocks.list).toHaveBeenCalledTimes(3)
})
it('显式接续原绑定入口，读取失败仍保留秘密，成功后清空且不隐式保存绑定', async () => {
  permissions.value.push('foundation.channels.update')
  await render()
  await create()
  mocks.get.mockRejectedValueOnce(Error('读取失败'))
  await click('已保存令牌，绑定智能体')
  expect(drawer('渠道访问凭据').props('modelValue')).toBe(true)
  expect(document.body.textContent).toContain('one-time-only')
  await click('已保存令牌，绑定智能体')
  expect(drawer('绑定智能体').props('modelValue')).toBe(true)
  expect(drawer('渠道访问凭据').props('modelValue')).toBe(false)
  expect(mocks.get).toHaveBeenLastCalledWith(channel.id)
  expect(mocks.bindAgents).not.toHaveBeenCalled()
})
it('更新不展示秘密，复制失败保留秘密', async () => {
  permissions.value.push('foundation.channels.update')
  await render()
  await click('编辑')
  await click('保存', drawer('编辑渠道'))
  expect(mocks.update).toHaveBeenCalledTimes(1)
  expect(mocks.create).not.toHaveBeenCalled()
  expect(drawer('渠道访问凭据').props('modelValue')).toBe(false)
  await create()
  mocks.copy.mockRejectedValueOnce(Error('clipboard blocked'))
  await click('复制 channel_token', drawer('渠道访问凭据'))
  expect(mocks.error).toHaveBeenCalledWith('复制失败，请手动选择并保存凭据')
  expect(document.body.textContent).toContain('one-time-only')
})
it('创建失败保留表单，不自动重发创建或轮换', async () => {
  await render()
  mocks.onboard.mockRejectedValueOnce(Error('请求结果尚未确认'))
  await create()
  expect(drawer('新增渠道').props('modelValue')).toBe(true)
  expect(drawer('渠道访问凭据').props('modelValue')).toBe(false)
  expect(mocks.onboard).toHaveBeenCalledTimes(1)
  expect(mocks.rotateToken).not.toHaveBeenCalled()
})
it.each([
  { status: 409, code: 'CONFLICT' },
  { status: 403, code: 'FORBIDDEN' },
  { status: 429, code: 'RATE_LIMITED' }
])('非明确创建拒绝保留原请求：$code', async ({ status, code }) => {
  await render()
  mocks.onboard.mockRejectedValueOnce(
    new HttpClientError({ status, code, traceId: 'trace', userMessage: '请求未确认' })
  )
  await create()
  const requestId = mocks.onboard.mock.calls[0]![0] as string
  expect(sessionStorage.getItem('skillforge.channel-create:["7","8"]')).toBe(requestId)
  expect(document.body.textContent).toContain(requestId)
  expect(
    drawer('新增渠道')
      .findAllComponents(ElButton)
      .find((item) => item.text() === '保存')!
      .props('disabled')
  ).toBe(true)
  expect(mocks.onboard).toHaveBeenCalledTimes(1)
})
it('明确回滚的渠道创建可保留输入并修正后重新提交', async () => {
  await render()
  mocks.onboard.mockRejectedValueOnce(
    new HttpClientError({
      status: 422,
      code: 'CHANNEL_ONBOARDING_REJECTED',
      userMessage: '绑定目标无效'
    })
  )
  await create()
  expect(sessionStorage.getItem('skillforge.channel-create:["7","8"]')).toBeNull()
  expect(drawer('新增渠道').findComponent(ElInput).props('modelValue')).toBe(channel.name)
  expect(
    drawer('新增渠道')
      .findAllComponents(ElButton)
      .find((item) => item.text() === '保存')!
      .props('disabled')
  ).toBe(false)
  const first = mocks.onboard.mock.calls[0]![0]
  await click('保存', drawer('新增渠道'))
  expect(mocks.onboard).toHaveBeenCalledTimes(2)
  expect(mocks.onboard.mock.calls[1]![0]).not.toBe(first)
  expect(document.body.textContent).toContain('one-time-only')
})
it('创建时选择智能体，一次请求完成默认绑定', async () => {
  permissions.value.push('foundation.channels.update', 'foundation.agents.read')
  mocks.agents.mockResolvedValue({
    items: [{ id: '81', name: '接诊助手', status: 'published' }],
    total: 1
  })
  await render()
  await click('新增渠道')
  const panel = drawer('新增渠道')
  panel.findComponent(ElInput).vm.$emit('update:modelValue', channel.name)
  panel.findAllComponents(ElSelect).at(-1)!.vm.$emit('update:modelValue', ['81'])
  await flushPromises()
  await click('保存', panel)
  expect(mocks.onboard).toHaveBeenCalledWith(
    expect.any(String),
    expect.objectContaining({
      agent_bindings: [{ agent_id: '81', priority: 0, is_default: true }]
    })
  )
  expect(mocks.bindAgents).not.toHaveBeenCalled()
  expect(document.body.textContent).toContain('所选智能体已绑定')
})
it('创建时可从多个智能体中选择默认项，不必事后重新绑定', async () => {
  permissions.value.push('foundation.channels.update', 'foundation.agents.read')
  mocks.agents.mockResolvedValue({
    items: [
      { id: '81', name: '接诊助手', status: 'published' },
      { id: '82', name: '导诊助手', status: 'published' }
    ],
    total: 2
  })
  await render()
  await click('新增渠道')
  const panel = drawer('新增渠道')
  panel.findComponent(ElInput).vm.$emit('update:modelValue', channel.name)
  panel.findAllComponents(ElSelect).at(-1)!.vm.$emit('update:modelValue', ['81', '82'])
  await flushPromises()
  expect(panel.findAllComponents(ElSelect).at(-1)!.props('modelValue')).toBe('81')
  panel.findAllComponents(ElSelect).at(-1)!.vm.$emit('update:modelValue', '82')
  await flushPromises()
  await click('保存', panel)
  expect(mocks.onboard).toHaveBeenCalledWith(
    expect.any(String),
    expect.objectContaining({
      agent_bindings: [
        { agent_id: '81', priority: 0, is_default: false },
        { agent_id: '82', priority: 1, is_default: true }
      ]
    })
  )
  expect(mocks.bindAgents).not.toHaveBeenCalled()
})
it('创建时移除默认智能体，自动选择剩余智能体', async () => {
  permissions.value.push('foundation.channels.update', 'foundation.agents.read')
  mocks.agents.mockResolvedValue({
    items: [
      { id: '81', name: '接诊助手', status: 'published' },
      { id: '82', name: '导诊助手', status: 'published' }
    ],
    total: 2
  })
  await render()
  await click('新增渠道')
  const panel = drawer('新增渠道')
  panel.findComponent(ElInput).vm.$emit('update:modelValue', channel.name)
  panel.findAllComponents(ElSelect).at(-1)!.vm.$emit('update:modelValue', ['81', '82'])
  await flushPromises()
  panel.findAllComponents(ElSelect).at(-2)!.vm.$emit('update:modelValue', ['82'])
  await flushPromises()
  await click('保存', panel)
  expect(mocks.onboard).toHaveBeenCalledWith(
    expect.any(String),
    expect.objectContaining({
      agent_bindings: [{ agent_id: '82', priority: 0, is_default: true }]
    })
  )
})
it('结果未知跨页面重建保留原请求，只读核对后提示轮换令牌', async () => {
  await render()
  mocks.onboard.mockRejectedValueOnce(Error('连接中断'))
  await create()
  const requestId = mocks.onboard.mock.calls[0]![0] as string
  expect(document.body.textContent).toContain(requestId)
  wrapper.unmount()
  await render()
  expect(document.body.textContent).toContain(requestId)
  mocks.onboardingResult
    .mockResolvedValueOnce(undefined)
    .mockResolvedValueOnce({ request_id: requestId, channel_id: channel.id })
  await click('核对创建结果')
  expect(document.body.textContent).toContain(requestId)
  await click('核对创建结果')
  expect(document.body.textContent).not.toContain(requestId)
  expect(drawer('渠道访问凭据').props('modelValue')).toBe(true)
  expect(document.body.textContent).toContain('已确认渠道创建成功，但首次令牌无法恢复')
  expect(mocks.get).toHaveBeenCalledWith(channel.id)
  expect(mocks.onboard).toHaveBeenCalledTimes(1)
  expect(mocks.rotateToken).not.toHaveBeenCalled()
})
it('重新进入时自动读取原回执，不重复创建或轮换令牌', async () => {
  await render()
  mocks.onboard.mockRejectedValueOnce(Error('连接中断'))
  await create()
  const requestId = mocks.onboard.mock.calls[0]![0] as string
  wrapper.unmount()
  mocks.onboardingResult.mockResolvedValue({ request_id: requestId, channel_id: channel.id })
  await render()
  expect(mocks.onboardingResult).toHaveBeenCalledExactlyOnceWith(requestId)
  expect(drawer('渠道访问凭据').props('modelValue')).toBe(true)
  expect(document.body.textContent).toContain('已确认渠道创建成功，但首次令牌无法恢复')
  expect(sessionStorage.getItem('skillforge.channel-create:["7","8"]')).toBeNull()
  expect(mocks.onboard).toHaveBeenCalledTimes(1)
  expect(mocks.rotateToken).not.toHaveBeenCalled()
})
it('没有渠道读取权限时保留原请求，不自动核对', async () => {
  sessionStorage.setItem(
    'skillforge.channel-create:["7","8"]',
    'a386f414-2ff6-44b3-9925-823997767d6c'
  )
  permissions.value = ['foundation.channels.create']
  await render()
  expect(mocks.onboardingResult).not.toHaveBeenCalled()
  expect(document.body.textContent).toContain('当前账号没有渠道读取权限')
  expect(document.body.textContent).toContain('a386f414-2ff6-44b3-9925-823997767d6c')
})
it('回执确认后详情读取失败保留原请求，可重新核对且不重复创建', async () => {
  permissions.value.push('foundation.channels.update')
  await render()
  mocks.onboard.mockRejectedValueOnce(Error('连接中断'))
  await create()
  const requestId = mocks.onboard.mock.calls[0]![0] as string
  mocks.onboardingResult.mockResolvedValue({ request_id: requestId, channel_id: channel.id })
  mocks.get.mockRejectedValueOnce(Error('详情读取失败'))
  await click('核对创建结果')
  expect(document.body.textContent).toContain(requestId)
  await click('核对创建结果')
  expect(drawer('渠道访问凭据').props('modelValue')).toBe(true)
  expect(mocks.onboard).toHaveBeenCalledTimes(1)
  expect(mocks.rotateToken).not.toHaveBeenCalled()
  mocks.rotateToken.mockResolvedValueOnce({ channel_token: 'replacement-token' })
  await click('轮换渠道令牌', drawer('渠道访问凭据'))
  expect(document.body.textContent).toContain('replacement-token')
  expect(document.body.textContent).not.toContain('已确认渠道创建成功，但首次令牌无法恢复')
})
it('首次响应令牌缺失时保留原请求并通过回执恢复', async () => {
  await render()
  mocks.onboard.mockImplementationOnce(async (requestId: string) => ({
    request_id: requestId,
    channel_id: channel.id,
    channel: { ...channel, channel_token: '' }
  }))
  await create()
  const requestId = mocks.onboard.mock.calls[0]![0] as string
  expect(document.body.textContent).toContain(requestId)
  expect(drawer('渠道访问凭据').props('modelValue')).toBe(false)
  mocks.onboardingResult.mockResolvedValue({ request_id: requestId, channel_id: channel.id })
  await click('核对创建结果')
  expect(drawer('渠道访问凭据').props('modelValue')).toBe(true)
  expect(document.body.textContent).toContain('首次令牌无法恢复')
  expect(mocks.onboard).toHaveBeenCalledTimes(1)
})

async function openApiCredentials() {
  permissions.value.push('foundation.channels.update', 'foundation.channels.delete')
  mocks.list.mockResolvedValue({
    items: [{ ...channel, type: 'openapi', api_key_issued: false }],
    total: 1
  })
  mocks.get.mockResolvedValue({ ...channel, type: 'openapi', api_key_issued: true })
  await render()
  await click('访问凭据')
}
async function unknownFirstIssue() {
  await openApiCredentials()
  mocks.issueApiKeyWithReceipt.mockRejectedValueOnce(Error('连接中断'))
  await click('签发 API Key', drawer('渠道访问凭据'))
  return mocks.issueApiKeyWithReceipt.mock.calls[0]![0] as string
}
const stored = () => sessionStorage.getItem('skillforge.channel-key:["7","8"]')

it('首次签发只显示一次明文，存储不含凭据', async () => {
  await openApiCredentials()
  await click('签发 API Key', drawer('渠道访问凭据'))
  expect(document.body.textContent).toContain('new-key')
  expect(document.body.textContent).toContain('new-secret')
  expect(stored()).not.toContain('new-key')
  expect(stored()).not.toContain('new-secret')
  expect(mocks.issueApiKeyWithReceipt).toHaveBeenCalledTimes(1)
  await click('关闭', drawer('渠道访问凭据'))
  await click('访问凭据')
  expect(document.body.textContent).not.toContain('new-secret')
})

it('结果未知跨页面保留原请求，未匹配回执不能重新签发或吊销', async () => {
  const requestId = await unknownFirstIssue()
  expect(stored()).toContain(requestId)
  expect(stored()).not.toContain('secret')
  wrapper.unmount()
  await render()
  mocks.apiKeyIssueResult.mockResolvedValueOnce(undefined)
  await click('访问凭据')
  expect(mocks.apiKeyIssueResult).toHaveBeenCalledWith(requestId)
  expect(document.body.textContent).toContain(requestId)
  expect(document.body.textContent).not.toContain('签发 API Key')
  mocks.apiKeyIssueResult.mockResolvedValueOnce({ request_id: requestId, channel_id: 'other' })
  await click('核对原签发', drawer('渠道访问凭据'))
  expect(document.body.textContent).toContain(requestId)
  mocks.apiKeyIssueResult.mockRejectedValueOnce(Error('读取失败'))
  await click('核对原签发', drawer('渠道访问凭据'))
  expect(document.body.textContent).toContain(requestId)
  expect(mocks.issueApiKeyWithReceipt).toHaveBeenCalledTimes(1)
})

it('回执确认并读取渠道后，只有显式操作才发出新请求', async () => {
  const requestId = await unknownFirstIssue()
  mocks.apiKeyIssueResult.mockResolvedValue({ request_id: requestId, channel_id: channel.id })
  await click('核对原签发', drawer('渠道访问凭据'))
  expect(mocks.get).toHaveBeenCalledWith(channel.id)
  expect(document.body.textContent).toContain('原签发已确认，但明文无法恢复')
  expect(stored()).not.toContain(requestId)
  expect(mocks.issueApiKeyWithReceipt).toHaveBeenCalledTimes(1)
  mocks.confirm.mockResolvedValueOnce(false)
  await click('轮换 API Key', drawer('渠道访问凭据'))
  expect(mocks.issueApiKeyWithReceipt).toHaveBeenCalledTimes(1)
  await click('轮换 API Key', drawer('渠道访问凭据'))
  expect(mocks.issueApiKeyWithReceipt).toHaveBeenCalledTimes(2)
  expect(mocks.issueApiKeyWithReceipt.mock.calls[1]![0]).not.toBe(requestId)
})

it.each([400, 403, 409, 429, 408, 503])('HTTP %s 不能证明原操作失败', async (status) => {
  await openApiCredentials()
  mocks.issueApiKeyWithReceipt.mockRejectedValueOnce(
    new HttpClientError({ status, code: 'ERROR', traceId: 'trace', userMessage: '未确认' })
  )
  await click('签发 API Key', drawer('渠道访问凭据'))
  expect(document.body.textContent).toContain('API 凭据签发结果尚未确认')
  expect(stored()).toContain(mocks.issueApiKeyWithReceipt.mock.calls[0]![0])
})

it.each([
  { api_key: 'key' },
  { api_key: '', api_secret: '' },
  { request_id: 'other', channel_id: channel.id, api_key: 'key', api_secret: 'secret' },
  { channel_id: 'other', api_key: 'key', api_secret: 'secret' }
])('损坏响应保留请求：%s', async (partial) => {
  await openApiCredentials()
  mocks.issueApiKeyWithReceipt.mockImplementationOnce(async (requestId: string) => ({
    request_id: requestId,
    channel_id: channel.id,
    ...partial
  }))
  await click('签发 API Key', drawer('渠道访问凭据'))
  expect(document.body.textContent).toContain('API 凭据签发结果尚未确认')
  expect(drawer('渠道访问凭据').find('.channel-secrets').exists()).toBe(false)
})

it('没有明文的重放回执读取当前渠道，提示无法恢复', async () => {
  await openApiCredentials()
  mocks.issueApiKeyWithReceipt.mockImplementationOnce(async (requestId: string) => ({
    request_id: requestId,
    channel_id: channel.id
  }))
  await click('签发 API Key', drawer('渠道访问凭据'))
  expect(mocks.get).toHaveBeenCalledWith(channel.id)
  expect(document.body.textContent).toContain('原签发已确认，但明文无法恢复')
  expect(document.body.textContent).not.toContain('API 凭据签发结果尚未确认')
})

it.each([false, true])('轮换失败时保留旧明文仅限明确拒绝：%s', async (rejected) => {
  await openApiCredentials()
  await click('签发 API Key', drawer('渠道访问凭据'))
  mocks.issueApiKeyWithReceipt.mockRejectedValueOnce(
    new HttpClientError(
      rejected
        ? { status: 422, code: 'CHANNEL_KEY_REJECTED', userMessage: '明确拒绝' }
        : { status: 0, userMessage: '轮换结果尚未确认' }
    )
  )
  await click('轮换 API Key', drawer('渠道访问凭据'))
  expect(document.body.textContent!.includes('new-secret')).toBe(rejected)
  expect(document.body.textContent!.includes('API 凭据签发结果尚未确认')).toBe(!rejected)
})

it('存储写入失败时不发送签发', async () => {
  await openApiCredentials()
  const spy = vi.spyOn(Storage.prototype, 'setItem').mockImplementationOnce(() => {
    throw Error('storage blocked')
  })
  await click('签发 API Key', drawer('渠道访问凭据'))
  expect(mocks.issueApiKeyWithReceipt).not.toHaveBeenCalled()
  expect(document.body.textContent).toContain('无法保存凭据操作记录')
  spy.mockRestore()
})

it('成功后清除记录失败仍展示明文，恢复存储后核对原请求', async () => {
  await openApiCredentials()
  const original = Storage.prototype.setItem
  let calls = 0
  const spy = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(function (
    this: Storage,
    key,
    value
  ) {
    calls++
    if (calls === 2) throw Error('storage blocked')
    return original.call(this, key, value)
  })
  await click('签发 API Key', drawer('渠道访问凭据'))
  const requestId = mocks.issueApiKeyWithReceipt.mock.calls[0]![0]
  expect(document.body.textContent).toContain('new-secret')
  expect(stored()).toContain(requestId)
  spy.mockRestore()
  mocks.apiKeyIssueResult.mockResolvedValue({ request_id: requestId, channel_id: channel.id })
  await click('核对原签发', drawer('渠道访问凭据'))
  expect(stored()).not.toContain(requestId)
  expect(mocks.issueApiKeyWithReceipt).toHaveBeenCalledTimes(1)
})

it('无读取权限时保留原请求，不自动查询', async () => {
  const requestId = await unknownFirstIssue()
  await click('关闭', drawer('渠道访问凭据'))
  permissions.value = ['foundation.channels.update']
  await click('访问凭据')
  expect(mocks.apiKeyIssueResult).not.toHaveBeenCalled()
  expect(document.body.textContent).toContain(requestId)
  expect(document.body.textContent).toContain('当前账号没有渠道读取权限')
})

it('成员切换后不显示或读取其他成员的请求记录', async () => {
  const requestId = await unknownFirstIssue()
  subject.value = '9'
  await flushPromises()
  await click('访问凭据')
  expect(document.body.textContent).not.toContain(requestId)
  expect(mocks.apiKeyIssueResult).not.toHaveBeenCalled()
  expect(stored()).toContain(requestId)
})
