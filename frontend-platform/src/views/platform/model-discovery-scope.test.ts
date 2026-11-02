import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { ElButton, ElFormItem, ElSelect, ElInput, ElInputNumber } from 'element-plus'
import ModelDetailPage from './ModelDetailPage.vue'
import { confirm } from '@skillforge/shared/feedback'

const calls = vi.hoisted(() => ({
  providers: vi.fn(),
  detail: vi.fn(),
  reasoningOptions: vi.fn(),
  discover: vi.fn(),
  create: vi.fn(),
  createWithReceipt: vi.fn(),
  createReceipt: vi.fn(),
  replace: vi.fn(),
  update: vi.fn(),
  publish: vi.fn(),
  allowed: vi.fn()
}))
vi.mock('../../stores/auth', () => ({
  useAuthStore: () => ({ identityVersion: 1, user: { sub: 'actor' } })
}))
vi.mock('../../api', () => ({ api: { admin: { models: calls } } }))
vi.mock('../../composables/actionPermissions', () => ({
  useActionPermissions: () => ({
    allowed: calls.allowed,
    checked: (_permission: string, operation: () => unknown) => operation()
  })
}))
vi.mock('@skillforge/shared/feedback', () => ({
  confirm: vi.fn(),
  showMessage: vi.fn(() => vi.fn()),
  message: { success: vi.fn(), warning: vi.fn(), error: vi.fn(), info: vi.fn() }
}))
const route = vi.hoisted(() => ({ path: '/platform/models/model-1', params: { id: 'model-1' } }))
vi.mock('vue-router', () => ({
  onBeforeRouteLeave: vi.fn(),
  onBeforeRouteUpdate: vi.fn(),
  useRoute: () => route,
  useRouter: () => ({ push: vi.fn(), replace: calls.replace })
}))
let wrapper: VueWrapper | undefined
const connection = {
  provider_id: 'custom',
  service_id: 'chat',
  profile_version: 'v1',
  protocol: 'openai-chat-completions',
  authentication_scheme: 'bearer-api-key',
  endpoint: 'https://models.example/v1'
}
function field(label: string) {
  return wrapper!.findAllComponents(ElFormItem).find((item) => item.props('label') === label)!
}
async function open() {
  wrapper = mount(ModelDetailPage, {
    global: { stubs: { RouterLink: true }, directives: { permission: () => {} } }
  })
  await flushPromises()
}
beforeEach(() => {
  vi.resetAllMocks()
  route.path = '/platform/models/model-1'
  sessionStorage.clear()
  calls.allowed.mockReturnValue(true)
  calls.providers.mockResolvedValue({
    providers: [
      {
        provider_id: 'custom',
        display_name: '兼容服务',
        category: 'CLOUD',
        services: [
          {
            service_id: 'chat',
            profile_version: 'v1',
            display_name: '对话',
            endpoint: connection.endpoint,
            region: 'LOCAL',
            availability: 'SUPPORTED',
            model_kinds: ['LLM'],
            model_discovery: { supported: true },
            protocols: [
              {
                protocol: connection.protocol,
                dialect_version: 'v1',
                authentication_schemes: ['bearer-api-key'],
                capabilities: {
                  streaming: 'SUPPORTED',
                  tool_calling: 'SUPPORTED',
                  reasoning: 'SUPPORTED',
                  user_images: 'UNSUPPORTED',
                  tool_images: 'UNSUPPORTED',
                  json_object: 'SUPPORTED',
                  strict_json_schema: 'SUPPORTED'
                }
              }
            ]
          }
        ]
      }
    ]
  })
  calls.detail.mockResolvedValue({
    ...connection,
    id: 'model-1',
    revision: 1,
    name: '已有模型',
    model_id: 'original',
    kind: 'LLM',
    enabled: true,
    reasoning: 'DEFAULT',
    currency: 'CNY',
    credential_configured: true,
    supported_max_output_tokens: 2048,
    context_window_tokens: 4096,
    max_output_tokens: 512
  })
  calls.reasoningOptions.mockResolvedValue({
    options: ['DEFAULT'],
    source: 'OFFICIAL_DOCUMENTATION'
  })
})
afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
})

it('同一发现连接中编辑模型ID、名称和容量不会丢弃返回候选或覆盖草稿', async () => {
  let complete!: (result: { models: { model_id: string }[] }) => void
  calls.discover.mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        complete = resolve
      })
  )
  await open()
  const models = field('模型标识').getComponent(ElSelect)
  models.vm.$emit('visible-change', true)
  await flushPromises()
  expect(calls.discover).toHaveBeenCalledWith({
    ...connection,
    id: 'model-1',
    expected_revision: 1,
    api_key: undefined
  })
  expect(models.props('loading')).toBe(true)
  models.vm.$emit('update:modelValue', 'typed-during-discovery')
  field('名称').getComponent(ElInput).vm.$emit('update:modelValue', '保留当前草稿')
  field('上下文窗口 Token').getComponent(ElInputNumber).vm.$emit('update:modelValue', 8192)
  await flushPromises()
  complete({ models: [{ model_id: 'candidate-model' }] })
  await flushPromises()
  expect(
    models.findAllComponents({ name: 'ElOption' }).map((option) => option.props('value'))
  ).toContain('candidate-model')
  expect(models.props('modelValue')).toBe('typed-during-discovery')
  expect(field('名称').getComponent(ElInput).props('modelValue')).toBe('保留当前草稿')
  expect(field('上下文窗口 Token').getComponent(ElInputNumber).props('modelValue')).toBe(8192)
  models.vm.$emit('visible-change', false)
  models.vm.$emit('visible-change', true)
  await flushPromises()
  expect(calls.discover).toHaveBeenCalledTimes(1)
  expect(models.props('loading')).toBe(false)
})

it('服务地址或API凭据变化后仍丢弃旧成功候选，并从新连接重新读取', async () => {
  for (const label of ['服务地址', 'API 凭据']) {
    let complete!: (result: { models: { model_id: string }[] }) => void
    calls.discover.mockClear()
    calls.discover
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            complete = resolve
          })
      )
      .mockResolvedValueOnce({ models: [{ model_id: 'new-connection-model' }] })
    await open()
    const models = field('模型标识').getComponent(ElSelect)
    models.vm.$emit('visible-change', true)
    await flushPromises()
    field(label)
      .getComponent(ElInput)
      .vm.$emit('update:modelValue', label === '服务地址' ? 'https://new.example/v1' : 'new-key')
    if (label === '服务地址')
      field('API 凭据').getComponent(ElInput).vm.$emit('update:modelValue', 'new-key')
    await flushPromises()
    complete({ models: [{ model_id: 'stale-connection-model' }] })
    await flushPromises()
    expect(
      models.findAllComponents({ name: 'ElOption' }).map((option) => option.props('value'))
    ).not.toContain('stale-connection-model')
    expect(models.props('loading')).toBe(false)
    models.vm.$emit('visible-change', false)
    models.vm.$emit('visible-change', true)
    await flushPromises()
    expect(calls.discover).toHaveBeenCalledTimes(2)
    expect(calls.discover).toHaveBeenLastCalledWith(
      expect.objectContaining({
        api_key: 'new-key',
        endpoint: label === '服务地址' ? 'https://new.example/v1' : connection.endpoint
      })
    )
    expect(
      models.findAllComponents({ name: 'ElOption' }).map((option) => option.props('value'))
    ).toContain('new-connection-model')
    expect(models.props('modelValue')).toBe('original')
    wrapper!.unmount()
    wrapper = undefined
  }
})

it('刷新发现失败保留候选并标明是上次结果，可重试读取', async () => {
  calls.discover
    .mockResolvedValueOnce({ models: [{ model_id: 'original' }] })
    .mockRejectedValueOnce(new Error('连接暂不可用'))
    .mockResolvedValueOnce({ models: [{ model_id: 'updated' }] })
  await open()
  const models = field('模型标识').getComponent(ElSelect)
  models.vm.$emit('visible-change', true)
  await flushPromises()
  const refresh = () =>
    wrapper!.findAllComponents(ElButton).find((item) => item.text() === '刷新模型')!
  await refresh().trigger('click')
  await flushPromises()
  expect(wrapper!.text()).toContain('模型候选刷新失败，当前显示上次获取的结果')
  expect(
    models.findAllComponents({ name: 'ElOption' }).map((item) => item.props('value'))
  ).toContain('original')
  await wrapper!
    .findAllComponents(ElButton)
    .find((item) => item.text() === '重试获取')!
    .trigger('click')
  await flushPromises()
  expect(wrapper!.text()).not.toContain('模型候选刷新失败')
  expect(
    models.findAllComponents({ name: 'ElOption' }).map((item) => item.props('value'))
  ).toContain('updated')
})

it('模型保存后直接确认发布，使用保存回执修订', async () => {
  const current = await calls.detail()
  calls.update.mockResolvedValue({
    ...current,
    name: '新名称',
    revision: 2,
    has_unpublished_changes: true
  })
  calls.publish.mockResolvedValue({
    ...current,
    name: '新名称',
    revision: 2,
    published_revision: 1,
    has_unpublished_changes: false
  })
  vi.mocked(confirm).mockResolvedValue(true)
  await open()
  field('名称').getComponent(ElInput).vm.$emit('update:modelValue', '新名称')
  await flushPromises()
  await wrapper!
    .findAllComponents(ElButton)
    .find((button) => button.text() === '保存并继续发布')!
    .trigger('click')
  await flushPromises()
  expect(calls.update).toHaveBeenCalledWith(
    'model-1',
    expect.objectContaining({ name: '新名称', expected_revision: 1 })
  )
  expect(confirm).toHaveBeenCalledWith(expect.objectContaining({ title: '发布模型配置' }))
  expect(calls.publish).toHaveBeenCalledExactlyOnceWith('model-1', 2)
})

it('取消发布后保留已保存草稿，单独发布不会重复保存', async () => {
  const current = await calls.detail()
  calls.update.mockResolvedValue({
    ...current,
    name: '新名称',
    revision: 2,
    has_unpublished_changes: true
  })
  calls.publish.mockResolvedValue({
    ...current,
    name: '新名称',
    revision: 2,
    published_revision: 1,
    has_unpublished_changes: false
  })
  vi.mocked(confirm).mockResolvedValueOnce(false).mockResolvedValueOnce(true)
  await open()
  field('名称').getComponent(ElInput).vm.$emit('update:modelValue', '新名称')
  await flushPromises()
  await wrapper!
    .findAllComponents(ElButton)
    .find((button) => button.text() === '保存并继续发布')!
    .trigger('click')
  await flushPromises()
  expect(calls.update).toHaveBeenCalledTimes(1)
  expect(calls.publish).not.toHaveBeenCalled()
  await wrapper!
    .findAllComponents(ElButton)
    .find((button) => button.text() === '发布')!
    .trigger('click')
  await flushPromises()
  expect(calls.update).toHaveBeenCalledTimes(1)
  expect(calls.publish).toHaveBeenCalledExactlyOnceWith('model-1', 2)
})

it('保存失败时不弹发布确认或派发发布', async () => {
  calls.update.mockRejectedValueOnce(new Error('保存失败'))
  await open()
  field('名称').getComponent(ElInput).vm.$emit('update:modelValue', '新名称')
  await flushPromises()
  await wrapper!
    .findAllComponents(ElButton)
    .find((button) => button.text() === '保存并继续发布')!
    .trigger('click')
  await flushPromises()
  expect(confirm).not.toHaveBeenCalled()
  expect(calls.publish).not.toHaveBeenCalled()
  expect(field('名称').getComponent(ElInput).props('modelValue')).toBe('新名称')
})

it('发布失败保留已保存草稿，不重复保存或自动重试', async () => {
  const current = await calls.detail()
  calls.update.mockResolvedValue({
    ...current,
    name: '新名称',
    revision: 2,
    has_unpublished_changes: true
  })
  calls.publish.mockRejectedValueOnce(new Error('发布失败'))
  vi.mocked(confirm).mockResolvedValue(true)
  await open()
  field('名称').getComponent(ElInput).vm.$emit('update:modelValue', '新名称')
  await flushPromises()
  await wrapper!
    .findAllComponents(ElButton)
    .find((button) => button.text() === '保存并继续发布')!
    .trigger('click')
  await flushPromises()
  expect(calls.update).toHaveBeenCalledTimes(1)
  expect(calls.publish).toHaveBeenCalledExactlyOnceWith('model-1', 2)
  expect(field('名称').getComponent(ElInput).props('modelValue')).toBe('新名称')
  expect(
    wrapper!.findAllComponents(ElButton).some((button) => button.text() === '保存并继续发布')
  ).toBe(false)
})

it('保存后发布权限被撤销时不再发起发布', async () => {
  const current = await calls.detail()
  calls.update.mockImplementationOnce(async () => {
    calls.allowed.mockImplementation(
      (permission: string) => permission !== 'foundation.models.publish'
    )
    return { ...current, name: '新名称', revision: 2, has_unpublished_changes: true }
  })
  await open()
  field('名称').getComponent(ElInput).vm.$emit('update:modelValue', '新名称')
  await flushPromises()
  await wrapper!
    .findAllComponents(ElButton)
    .find((button) => button.text() === '保存并继续发布')!
    .trigger('click')
  await flushPromises()
  expect(calls.update).toHaveBeenCalledTimes(1)
  expect(confirm).not.toHaveBeenCalled()
  expect(calls.publish).not.toHaveBeenCalled()
  expect(field('名称').getComponent(ElInput).props('modelValue')).toBe('新名称')
})

it.each(['publish', 'cancel', 'publish-failure', 'create-failure', 'unknown-recover'])(
  '新模型创建后%s仍进入返回的详情，且不重复创建',
  async (outcome) => {
    const original = await calls.detail()
    const created = {
      ...original,
      id: 'new-model',
      name: '新模型',
      model_id: 'deepseek-flash',
      revision: 1,
      has_unpublished_changes: true
    }
    route.path = '/platform/models/new'
    calls.createWithReceipt.mockImplementationOnce(async (requestId: string) => ({
      request_id: requestId,
      model_id: 'new-model'
    }))
    calls.detail.mockResolvedValueOnce(created)
    if (outcome === 'create-failure' || outcome === 'unknown-recover')
      calls.createWithReceipt.mockReset().mockRejectedValueOnce(new Error('创建失败'))
    calls.publish.mockResolvedValueOnce({
      ...created,
      published_revision: 1,
      has_unpublished_changes: false
    })
    if (outcome === 'publish-failure') calls.publish.mockRejectedValueOnce(new Error('连接中断'))
    vi.mocked(confirm).mockResolvedValueOnce(outcome !== 'cancel')
    await open()
    field('名称').getComponent(ElInput).vm.$emit('update:modelValue', '新模型')
    const provider = field('提供方').getComponent(ElSelect)
    provider.vm.$emit('update:modelValue', 'custom')
    provider.vm.$emit('change', 'custom')
    await flushPromises()
    field('API 凭据').getComponent(ElInput).vm.$emit('update:modelValue', 'test-key')
    field('模型标识').getComponent(ElSelect).vm.$emit('update:modelValue', 'deepseek-flash')
    field('支持最大输出 Token').getComponent(ElInputNumber).vm.$emit('update:modelValue', 2048)
    field('上下文窗口 Token').getComponent(ElInputNumber).vm.$emit('update:modelValue', 4096)
    field('本次输出 Token 上限').getComponent(ElInputNumber).vm.$emit('update:modelValue', 512)
    await flushPromises()
    await wrapper!
      .findAllComponents(ElButton)
      .find((button) => button.text() === '保存并继续发布')!
      .trigger('click')
    await flushPromises()
    expect(calls.createWithReceipt).toHaveBeenCalledTimes(1)
    if (outcome === 'unknown-recover') {
      const raw = sessionStorage.getItem('skillforge.model-create:actor')!
      expect(raw).not.toContain('test-key')
      const operation = JSON.parse(raw) as { requestId: string }
      wrapper!.unmount()
      await open()
      calls.createReceipt
        .mockResolvedValueOnce(undefined)
        .mockResolvedValueOnce({ request_id: operation.requestId, model_id: 'new-model' })
      const check = () =>
        wrapper!.findAllComponents(ElButton).find((button) => button.text() === '核对创建结果')!
      await check().trigger('click')
      await flushPromises()
      expect(sessionStorage.getItem('skillforge.model-create:actor')).toBe(raw)
      await check().trigger('click')
      await flushPromises()
      expect(calls.createReceipt).toHaveBeenCalledTimes(2)
      expect(calls.replace).toHaveBeenCalledExactlyOnceWith('/platform/models/new-model')
      expect(sessionStorage.getItem('skillforge.model-create:actor')).toBeNull()
      expect(calls.publish).not.toHaveBeenCalled()
      return
    }
    expect(calls.publish).toHaveBeenCalledTimes(
      outcome === 'cancel' || outcome === 'create-failure' ? 0 : 1
    )
    if (outcome === 'publish' || outcome === 'publish-failure')
      expect(calls.publish).toHaveBeenCalledWith('new-model', 1)
    if (outcome === 'create-failure') expect(calls.replace).not.toHaveBeenCalled()
    else expect(calls.replace).toHaveBeenCalledExactlyOnceWith('/platform/models/new-model')
    expect(calls.update).not.toHaveBeenCalled()
  }
)
