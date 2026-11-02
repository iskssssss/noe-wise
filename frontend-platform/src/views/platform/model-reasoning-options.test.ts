import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { ElFormItem, ElSelect, ElInput, ElInputNumber, ElButton, ElSwitch } from 'element-plus'
import ModelDetailPage from './ModelDetailPage.vue'
import type { ModelReasoningOptions } from '../../api'
import { HttpClientError } from '@skillforge/shared/utils/http'

const mocks = vi.hoisted(() => ({
  providers: vi.fn(),
  detail: vi.fn(),
  reasoningOptions: vi.fn(),
  discover: vi.fn(),
  test: vi.fn(),
  showMessage: vi.fn(() => vi.fn()),
  allowed: vi.fn((_permission: string) => true),
  path: '/platform/models/new'
}))
vi.mock('../../stores/auth', () => ({
  useAuthStore: () => ({ identityVersion: 1, user: { sub: 'actor' } })
}))
vi.mock('../../api', () => ({
  api: {
    admin: {
      models: {
        providers: mocks.providers,
        detail: mocks.detail,
        reasoningOptions: mocks.reasoningOptions,
        discover: mocks.discover,
        test: mocks.test
      }
    }
  }
}))
vi.mock('../../composables/actionPermissions', () => ({
  useActionPermissions: () => ({
    allowed: mocks.allowed,
    checked: (_permission: string, run: () => unknown) => run()
  })
}))
vi.mock('@skillforge/shared/feedback', () => ({
  confirm: vi.fn(),
  showMessage: mocks.showMessage,
  message: { success: vi.fn(), warning: vi.fn(), error: vi.fn() }
}))
vi.mock('vue-router', () => ({
  onBeforeRouteLeave: vi.fn(),
  onBeforeRouteUpdate: vi.fn(),
  useRoute: () => ({ path: mocks.path, params: { id: 'existing' } }),
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() })
}))
let wrapper: VueWrapper | undefined
const protocol = {
  protocol: 'anthropic-messages',
  dialect_version: '1',
  authentication_schemes: ['bearer-api-key'],
  capabilities: {
    json_object: 'UNSUPPORTED',
    strict_json_schema: 'UNSUPPORTED',
    streaming: 'SUPPORTED',
    tool_calling: 'SUPPORTED',
    reasoning: 'SUPPORTED',
    user_images: 'SUPPORTED',
    tool_images: 'SUPPORTED'
  },
  image_policy_defaults: {
    enabled: true,
    tool_images: true,
    max_images: 4,
    max_image_bytes: 10485760,
    max_request_bytes: 20971520,
    max_dimension: 2048,
    tile_size: 512,
    base_tokens: 1024,
    tile_tokens: 0,
    version: 'deepseek-vision.v1'
  }
}
const connection = {
  provider_id: 'deepseek',
  service_id: 'api',
  profile_version: '1',
  protocol: protocol.protocol,
  authentication_scheme: 'bearer-api-key',
  endpoint: 'https://api.deepseek.com/anthropic',
  model_id: 'deepseek-v4-flash'
}
const options: ModelReasoningOptions = {
  options: ['DISABLED', 'HIGH', 'MAX'],
  source: 'OFFICIAL_DOCUMENTATION',
  source_url: 'https://api-docs.deepseek.com/reasoning'
}
function field(label: string) {
  return wrapper!.findAllComponents(ElFormItem).find((item) => item.props('label') === label)!
}
async function open(existing = false) {
  mocks.path = existing ? '/platform/models/existing' : '/platform/models/new'
  wrapper = mount(ModelDetailPage, {
    global: { directives: { permission: () => {} }, stubs: { RouterLink: true } }
  })
  await flushPromises()
}
async function chooseConnection() {
  const provider = field('提供方').getComponent(ElSelect)
  provider.vm.$emit('update:modelValue', 'deepseek')
  provider.vm.$emit('change', 'deepseek')
  await flushPromises()
  field('模型标识').getComponent(ElSelect).vm.$emit('update:modelValue', connection.model_id)
  await flushPromises()
}
beforeEach(() => {
  sessionStorage.clear()
  vi.resetAllMocks()
  mocks.allowed.mockReturnValue(true)
  mocks.providers.mockResolvedValue({
    providers: [
      {
        provider_id: 'deepseek',
        display_name: 'DeepSeek',
        category: 'CLOUD',
        services: [
          {
            service_id: 'api',
            display_name: 'API',
            profile_version: '1',
            availability: 'SUPPORTED',
            region: 'CHINA_MAINLAND',
            model_kinds: ['LLM'],
            endpoint: connection.endpoint,
            model_discovery: {
              path: '/models',
              dialect_version: '1',
              authentication_schemes: ['bearer-api-key'],
              verification_status: 'UNVERIFIED'
            },
            protocols: [protocol]
          }
        ]
      }
    ]
  })
  mocks.reasoningOptions.mockResolvedValue(options)
  mocks.detail.mockResolvedValue({
    ...connection,
    id: 'existing',
    name: '已有模型',
    revision: 1,
    kind: 'LLM',
    enabled: true,
    reasoning: 'LOW',
    currency: 'CNY',
    supported_max_output_tokens: 2048,
    context_window_tokens: 4096,
    max_output_tokens: 512,
    credential_configured: true
  })
})
afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
})

it('新建时选择发现的模型填入空名称，保留用户修改', async () => {
  mocks.discover.mockResolvedValue({
    models: [
      { model_id: 'model-a', metadata: { name: '模型 A' } },
      { model_id: 'model-b', metadata: {} }
    ]
  })
  await open()
  await chooseConnection()
  field('API 凭据').getComponent(ElInput).vm.$emit('update:modelValue', 'synthetic-key')
  await wrapper!
    .findAllComponents(ElButton)
    .find((button) => button.text() === '发现模型')!
    .trigger('click')
  await flushPromises()
  const select = field('模型标识').getComponent(ElSelect)
  select.vm.$emit('update:modelValue', 'model-a')
  select.vm.$emit('change', 'model-a')
  await flushPromises()
  const name = field('名称').getComponent(ElInput)
  expect(name.props('modelValue')).toBe('模型 A')
  select.vm.$emit('update:modelValue', 'model-b')
  select.vm.$emit('change', 'model-b')
  await flushPromises()
  expect(name.props('modelValue')).toBe('model-b')
  select.vm.$emit('update:modelValue', 'custom-model')
  select.vm.$emit('change', 'custom-model')
  await flushPromises()
  expect(name.props('modelValue')).toBe('custom-model')
  name.vm.$emit('update:modelValue', '自定义名称')
  name.vm.$emit('input', '自定义名称')
  select.vm.$emit('update:modelValue', 'model-a')
  select.vm.$emit('change', 'model-a')
  await flushPromises()
  expect(name.props('modelValue')).toBe('自定义名称')
})

it('编辑已有模型时更换手动标识不改名称', async () => {
  await open(true)
  const select = field('模型标识').getComponent(ElSelect)
  select.vm.$emit('update:modelValue', 'custom-model')
  select.vm.$emit('change', 'custom-model')
  await flushPromises()
  expect(field('名称').getComponent(ElInput).props('modelValue')).toBe('已有模型')
})

it('不要求JSON对象能力即可选择Messages协议，推理档位来自当前返回并展示来源', async () => {
  await open()
  await chooseConnection()
  expect(mocks.reasoningOptions).toHaveBeenLastCalledWith(connection)
  expect(field('推理程度').getComponent(ElSelect).props('modelValue')).toBe('DISABLED')
  expect(wrapper!.text()).toContain('供应商官方文档')
  expect(wrapper!.find('a[href="https://api-docs.deepseek.com/reasoning"]').exists()).toBe(true)
  expect(
    field('推理程度')
      .findAllComponents({ name: 'ElOption' })
      .map((option) => option.props('value'))
  ).toEqual(options.options)
})
it('已有配置不被选项读取静默改写，非法值保留并提示重新选择', async () => {
  await open(true)
  expect(field('推理程度').getComponent(ElSelect).props('modelValue')).toBe('LOW')
  expect(wrapper!.text()).toContain('原值已保留')
})
it('切换模型后忽略旧请求的迟到结果和finally，失败保留原输入并可重试', async () => {
  let oldResolve!: (value: ModelReasoningOptions) => void
  mocks.reasoningOptions.mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        oldResolve = resolve
      })
  )
  await open()
  await chooseConnection()
  let newReject!: (error: Error) => void
  mocks.reasoningOptions.mockImplementationOnce(
    () =>
      new Promise((_resolve, reject) => {
        newReject = reject
      })
  )
  field('模型标识').getComponent(ElSelect).vm.$emit('update:modelValue', 'deepseek-v4-pro')
  await flushPromises()
  oldResolve(options)
  await flushPromises()
  expect(field('推理程度').getComponent(ElSelect).props('loading')).toBe(true)
  newReject(new Error('offline'))
  await flushPromises()
  expect(field('模型标识').getComponent(ElSelect).props('modelValue')).toBe('deepseek-v4-pro')
  expect(wrapper!.text()).toContain('未能读取当前模型的推理选项')
  mocks.reasoningOptions.mockResolvedValue({
    options: ['DEFAULT'],
    source: 'UNVERIFIED',
    source_url: ''
  })
  const retry = wrapper!.findAll('button').find((button) => button.text().includes('重试读取'))!
  await retry.trigger('click')
  await flushPromises()
  expect(wrapper!.text()).toContain('未验证，能力尚未确认')
})

it('协议定义只显示待测试候选来源，不冒充模型官方能力', async () => {
  mocks.reasoningOptions.mockResolvedValue({
    options: ['DEFAULT', 'HIGH'],
    source: 'PROTOCOL_DEFINITION',
    source_url: ''
  })
  await open()
  await chooseConnection()
  expect(wrapper!.text()).toContain('协议候选，待兼容性测试')
  expect(wrapper!.text()).not.toContain('供应商官方文档')
})
it('发现元数据只供显式采用，允许任意模型ID且不覆盖单次输出或未报告的能力', async () => {
  mocks.discover.mockResolvedValue({
    models: [
      {
        model_id: 'custom-deployment-2026',
        metadata: {
          name: '机构部署',
          context: { context_window: 64000, max_output_tokens: 8192 },
          reasoning: { supported_levels: ['low', 'max'], default_level: 'low' },
          messages: { system_prompt_update: 'in-history', tool_update: 'add-remove' }
        }
      }
    ]
  })
  await open(true)
  field('API 凭据').getComponent(ElInput).vm.$emit('update:modelValue', 'synthetic-new-key')
  await flushPromises()
  await wrapper!
    .findAllComponents(ElButton)
    .find((button) => button.text() === '发现模型')!
    .trigger('click')
  await flushPromises()
  field('模型标识').getComponent(ElSelect).vm.$emit('update:modelValue', 'custom-deployment-2026')
  await flushPromises()
  expect(field('支持最大输出 Token').getComponent(ElInputNumber).props('modelValue')).toBe(2048)
  expect(field('上下文窗口 Token').getComponent(ElInputNumber).props('modelValue')).toBe(4096)
  expect(wrapper!.text()).toContain('供应商实时发现（候选，待兼容性测试）')
  expect(field('推理程度').getComponent(ElSelect).props('modelValue')).toBe('LOW')
  await wrapper!
    .findAllComponents(ElButton)
    .find((button) => button.text() === '采用候选容量')!
    .trigger('click')
  await flushPromises()
  expect(field('支持最大输出 Token').getComponent(ElInputNumber).props('modelValue')).toBe(8192)
  expect(field('上下文窗口 Token').getComponent(ElInputNumber).props('modelValue')).toBe(64000)
  expect(field('本次输出 Token 上限').getComponent(ElInputNumber).props('modelValue')).toBe(512)
  await wrapper!
    .findAllComponents(ElButton)
    .find((button) => button.text() === '采用系统指令更新声明')!
    .trigger('click')
  await flushPromises()
  expect(wrapper!.text()).toContain('历史内系统指令更新 支持')
  expect(wrapper!.text()).toContain('禁用工具选择时保留目录 未验证')
})
it('逐项呈现保存配置的真实兼容报告，编辑后旧结果立即标记失效', async () => {
  const check = (status: string, reason_code: string | null = null) => ({
    status,
    duration_ms: 12,
    reason_code
  })
  const report = {
    draft_revision: 1,
    tested_at: '2026-10-25T11:00:00Z',
    status: 'FAILED',
    configuration_acceptance: check('PASSED'),
    streaming: check('UNKNOWN', 'stream_disconnected'),
    tool_continuation: check('NOT_RUN', 'previous_probe_incomplete'),
    duration_ms: 36,
    reason_code: 'stream_disconnected'
  }
  mocks.test.mockResolvedValue({ ok: false, latency_ms: 36, compatibility_report: report })
  await open(true)
  await wrapper!
    .findAllComponents(ElButton)
    .find((button) => button.text() === '测试当前配置')!
    .trigger('click')
  await flushPromises()
  expect(mocks.test).toHaveBeenCalledWith({ id: 'existing' })
  const result = wrapper!.get('section[aria-label="模型兼容性测试结果"]')
  expect(result.text()).toContain('配置参数接受')
  expect(result.text()).toContain('结果未知')
  expect(result.text()).toContain('未执行')
  expect(result.text()).toContain('stream_disconnected')
  expect(result.text()).toContain('不代表推理效果或缓存命中已验证')
  field('名称').getComponent(ElInput).vm.$emit('update:modelValue', '修改配置')
  await flushPromises()
  expect(result.text()).toContain('以下测试结果已失效')
})

it('重复测试失败时将之前结果标为历史，成功后恢复最新状态', async () => {
  const check = { status: 'PASSED', duration_ms: 12, reason_code: null }
  const report = {
    draft_revision: 1,
    tested_at: '2026-10-25T11:00:00Z',
    status: 'PASSED',
    configuration_acceptance: check,
    streaming: check,
    tool_continuation: check,
    duration_ms: 36,
    reason_code: null
  }
  mocks.test
    .mockResolvedValueOnce({ ok: true, latency_ms: 36, compatibility_report: report })
    .mockRejectedValueOnce(new Error('连接失败'))
    .mockResolvedValueOnce({ ok: true, latency_ms: 20, compatibility_report: report })
  await open(true)
  const test = () =>
    wrapper!
      .findAllComponents(ElButton)
      .find((button) => button.text() === '测试当前配置')!
      .trigger('click')
  await test()
  await flushPromises()
  expect(wrapper!.text()).toContain('兼容性测试 · 通过')
  expect(wrapper!.text()).not.toContain('本次测试尚未完成')
  await test()
  await flushPromises()
  expect(wrapper!.text()).toContain('本次测试尚未完成；以下为之前的测试结果')
  expect(wrapper!.text()).toContain('兼容性测试 · 通过')
  await test()
  await flushPromises()
  expect(wrapper!.text()).not.toContain('本次测试尚未完成')
  expect(mocks.test).toHaveBeenCalledTimes(3)
})

it('没有兼容报告的旧通过结果在重测失败后标注为上次结果', async () => {
  mocks.test
    .mockResolvedValueOnce({ ok: true, latency_ms: 12 })
    .mockRejectedValueOnce(new Error('连接失败'))
  await open(true)
  const test = () =>
    wrapper!
      .findAllComponents(ElButton)
      .find((button) => button.text() === '测试当前配置')!
      .trigger('click')
  await test()
  await flushPromises()
  expect(wrapper!.text()).toContain('当前配置的连接测试通过')
  await test()
  await flushPromises()
  expect(wrapper!.text()).toContain('本次测试尚未完成；上次结果：当前配置的连接测试通过')
})

it('未保存时仅凭连接信息发现模型，无需名称、模型标识和容量', async () => {
  const catalog = await mocks.providers()
  catalog.providers[0].services[0].protocols[0] = {
    ...protocol,
    authentication_schemes: ['anthropic-api-key']
  }
  mocks.providers.mockResolvedValue(catalog)
  mocks.discover.mockResolvedValue({ models: [{ model_id: 'available-model' }] })
  await open()
  const provider = field('提供方').getComponent(ElSelect)
  provider.vm.$emit('update:modelValue', 'deepseek')
  provider.vm.$emit('change', 'deepseek')
  field('API 凭据').getComponent(ElInput).vm.$emit('update:modelValue', 'synthetic-key')
  await flushPromises()
  await wrapper!
    .findAllComponents(ElButton)
    .find((button) => button.text() === '发现模型')!
    .trigger('click')
  await flushPromises()
  expect(mocks.discover).toHaveBeenCalledWith(
    expect.objectContaining({
      authentication_scheme: 'anthropic-api-key',
      protocol: 'anthropic-messages',
      api_key: 'synthetic-key'
    })
  )
  expect(
    field('模型标识')
      .findAllComponents({ name: 'ElOption' })
      .map((option) => option.props('value'))
  ).toContain('available-model')
  expect(field('名称').getComponent(ElInput).props('modelValue')).toBe('')
  expect(field('上下文窗口 Token').getComponent(ElInputNumber).props('modelValue')).toBeUndefined()
})

it('没有凭据时展开模型标识可手动输入，填写凭据后自动发现', async () => {
  mocks.discover.mockResolvedValue({ models: [{ model_id: 'available-model' }] })
  await open()
  const provider = field('提供方').getComponent(ElSelect)
  provider.vm.$emit('update:modelValue', 'deepseek')
  provider.vm.$emit('change', 'deepseek')
  await flushPromises()
  const model = field('模型标识').getComponent(ElSelect)
  model.vm.$emit('visible-change', true)
  await flushPromises()
  expect(mocks.discover).not.toHaveBeenCalled()
  expect(field('API 凭据').props('error')).toBeFalsy()
  expect(field('模型标识').text()).toContain('填写 API 凭据后展开下拉可获取候选')
  model.vm.$emit('update:modelValue', 'manual-model')
  field('API 凭据').getComponent(ElInput).vm.$emit('update:modelValue', 'synthetic-key')
  await flushPromises()
  model.vm.$emit('visible-change', true)
  await flushPromises()
  expect(mocks.discover).toHaveBeenCalledExactlyOnceWith(
    expect.objectContaining({ api_key: 'synthetic-key' })
  )
  expect(model.props('modelValue')).toBe('manual-model')
})

it('已保存配置获取模型列表时无需重输凭据，修改连接范围后要求新凭据', async () => {
  mocks.discover.mockResolvedValue({ models: [{ model_id: 'available-model' }] })
  await open(true)
  const button = wrapper!
    .findAllComponents(ElButton)
    .find((button) => button.text() === '发现模型')!
  await button.trigger('click')
  await flushPromises()
  expect(mocks.discover).toHaveBeenCalledWith(
    expect.objectContaining({ id: 'existing', expected_revision: 1, api_key: undefined })
  )
  expect(field('API 凭据').getComponent(ElInput).props('modelValue')).toBe('')
  mocks.discover.mockClear()
  field('服务地址').getComponent(ElInput).vm.$emit('update:modelValue', 'https://other.example/v1')
  await flushPromises()
  await button.trigger('click')
  await flushPromises()
  expect(mocks.discover).not.toHaveBeenCalled()
  expect(field('API 凭据').props('error')).toContain('请填写当前连接的 API 凭据')
})

it('新建配置无需保存即可测试，临时结果绑定当前输入且编辑后标记失效', async () => {
  const check = { status: 'PASSED', duration_ms: 1, reason_code: null }
  mocks.test.mockResolvedValue({
    ok: true,
    latency_ms: 3,
    compatibility_report: {
      draft_revision: 0,
      tested_at: '2026-10-25T11:00:00Z',
      status: 'PASSED',
      configuration_acceptance: check,
      streaming: check,
      tool_continuation: check,
      duration_ms: 3,
      reason_code: null
    }
  })
  await open()
  await chooseConnection()
  field('名称').getComponent(ElInput).vm.$emit('update:modelValue', '未保存模型')
  field('API 凭据').getComponent(ElInput).vm.$emit('update:modelValue', 'synthetic-key')
  for (const [label, value] of [
    ['支持最大输出 Token', 2048],
    ['上下文窗口 Token', 4096],
    ['本次输出 Token 上限', 512]
  ] as const)
    field(label).getComponent(ElInputNumber).vm.$emit('update:modelValue', value)
  await flushPromises()
  const button = wrapper!
    .findAllComponents(ElButton)
    .find((button) => button.text() === '测试当前配置')!
  expect(button.props('disabled')).toBe(false)
  await button.trigger('click')
  await flushPromises()
  expect(mocks.test).toHaveBeenCalledWith({
    id: undefined,
    payload: expect.objectContaining({
      name: '未保存模型',
      api_key: 'synthetic-key',
      max_output_tokens: 512,
      model_id: connection.model_id
    })
  })
  const result = wrapper!.get('section[aria-label="模型兼容性测试结果"]')
  expect(result.text()).toContain('未保存配置（仅本次测试）')
  expect(result.text()).not.toContain('以下测试结果已失效')
  field('本次输出 Token 上限').getComponent(ElInputNumber).vm.$emit('update:modelValue', 1024)
  await flushPromises()
  expect(result.text()).toContain('以下测试结果已失效')
})

it('修改已有配置时提交当前值与原修订，留空凭据供后端按连接范围复用', async () => {
  mocks.test.mockResolvedValue({ ok: true, latency_ms: 1 })
  await open(true)
  field('推理程度').getComponent(ElSelect).vm.$emit('update:modelValue', 'HIGH')
  await flushPromises()
  await wrapper!
    .findAllComponents(ElButton)
    .find((button) => button.text() === '测试当前配置')!
    .trigger('click')
  await flushPromises()
  expect(mocks.test).toHaveBeenCalledWith({
    id: 'existing',
    payload: expect.objectContaining({
      expected_revision: 1,
      reasoning: 'HIGH'
    })
  })
  expect(mocks.test.mock.calls[0]![0].payload).not.toHaveProperty('api_key')
})

it('只有模型测试权限时仍可测试已保存配置，编辑字段保持只读', async () => {
  mocks.allowed.mockImplementation((permission: string) => permission === 'foundation.models.run')
  mocks.test.mockResolvedValue({ ok: true, latency_ms: 1 })
  await open(true)
  expect(field('名称').getComponent(ElInput).get('input').element.disabled).toBe(true)
  const button = wrapper!
    .findAllComponents(ElButton)
    .find((item) => item.text() === '测试当前配置')!
  expect(button.get('button').element.disabled).toBe(false)
  await button.trigger('click')
  await flushPromises()
  expect(mocks.test).toHaveBeenCalledExactlyOnceWith({ id: 'existing' })
  expect(wrapper!.text()).toContain('当前配置的连接测试通过')
})

it('模型发现失败显示具体操作原因并保留输入', async () => {
  mocks.discover.mockRejectedValue(
    new HttpClientError({
      status: 400,
      code: 'model_discovery_failed',
      userMessage: '提交内容不符合要求'
    })
  )
  await open()
  await chooseConnection()
  field('API 凭据').getComponent(ElInput).vm.$emit('update:modelValue', 'synthetic-key')
  await flushPromises()
  await wrapper!
    .findAllComponents(ElButton)
    .find((button) => button.text() === '发现模型')!
    .trigger('click')
  await flushPromises()
  expect(mocks.showMessage).toHaveBeenCalledWith(
    'error',
    expect.stringContaining('获取模型列表失败，请核对服务地址、API 凭据权限及网络后重试'),
    expect.anything(),
    expect.anything()
  )
  expect(field('API 凭据').getComponent(ElInput).props('modelValue')).toBe('synthetic-key')
  expect(wrapper!.text()).not.toContain('提交内容不符合要求')
})

it('模型下拉由显式展开获取候选，重复展开复用结果，刷新不改当前模型', async () => {
  mocks.discover.mockResolvedValue({
    models: [{ model_id: 'candidate-with-a-long-supplier-model-identifier-2032' }]
  })
  await open(true)
  expect(mocks.discover).not.toHaveBeenCalled()
  const select = field('模型标识').getComponent(ElSelect)
  const original = select.props('modelValue')
  select.vm.$emit('visible-change', true)
  await flushPromises()
  expect(mocks.discover).toHaveBeenCalledTimes(1)
  expect(mocks.discover).toHaveBeenLastCalledWith(
    expect.objectContaining({ id: 'existing', expected_revision: 1, api_key: undefined })
  )
  expect(select.props('modelValue')).toBe(original)
  select.vm.$emit('visible-change', false)
  select.vm.$emit('visible-change', true)
  await flushPromises()
  expect(mocks.discover).toHaveBeenCalledTimes(1)
  await wrapper!
    .findAllComponents(ElButton)
    .find((button) => button.text() === '刷新模型')!
    .trigger('click')
  await flushPromises()
  expect(mocks.discover).toHaveBeenCalledTimes(2)
  expect(select.props('modelValue')).toBe(original)
  expect(field('API 凭据').getComponent(ElInput).props('modelValue')).toBe('')
})

it('候选获取中重复展开不会重发，连接切换后的旧失败不提示或覆盖新输入', async () => {
  let reject!: (reason: Error) => void
  mocks.discover.mockImplementationOnce(
    () =>
      new Promise((_resolve, failure) => {
        reject = failure
      })
  )
  await open(true)
  const select = field('模型标识').getComponent(ElSelect)
  select.vm.$emit('visible-change', true)
  select.vm.$emit('visible-change', true)
  await flushPromises()
  expect(mocks.discover).toHaveBeenCalledTimes(1)
  expect(select.props('loading')).toBe(true)
  field('服务地址').getComponent(ElInput).vm.$emit('update:modelValue', 'https://new.example.cn/v1')
  await flushPromises()
  reject(new Error('old directory connection failed'))
  await flushPromises()
  expect(mocks.showMessage).not.toHaveBeenCalled()
  expect(select.props('loading')).toBe(false)
  expect(field('服务地址').getComponent(ElInput).props('modelValue')).toBe(
    'https://new.example.cn/v1'
  )
  expect(field('API 凭据').getComponent(ElInput).props('modelValue')).toBe('')
})

it('发现effort只收窄可编码强度，保留协议思考开关，区分空列表和未知', async () => {
  mocks.reasoningOptions.mockResolvedValue({
    options: ['DEFAULT', 'DISABLED', 'ENABLED', 'HIGH', 'MAX'],
    source: 'PROTOCOL_DEFINITION',
    source_url: ''
  })
  mocks.discover.mockResolvedValue({
    models: [
      {
        model_id: 'discovered',
        metadata: {
          reasoning: { supported_levels: ['low', 'high', 'max', 'xhigh', 'future-level'] }
        }
      },
      { model_id: 'empty', metadata: { reasoning: { supported_levels: [] } } },
      { model_id: 'unknown', metadata: { reasoning: { supported_levels: null } } }
    ]
  })
  await open(true)
  field('API 凭据').getComponent(ElInput).vm.$emit('update:modelValue', 'synthetic-key')
  await flushPromises()
  await wrapper!
    .findAllComponents(ElButton)
    .find((button) => button.text() === '发现模型')!
    .trigger('click')
  await flushPromises()
  const selectable = () =>
    field('推理程度')
      .findAllComponents({ name: 'ElOption' })
      .filter((option) => !option.props('disabled'))
      .map((option) => option.props('value'))
  for (const [model, expected] of [
    ['discovered', ['DEFAULT', 'DISABLED', 'ENABLED', 'HIGH', 'MAX']],
    ['empty', ['DEFAULT', 'DISABLED', 'ENABLED']],
    ['unknown', ['DEFAULT', 'DISABLED', 'ENABLED', 'HIGH', 'MAX']]
  ] as const) {
    field('模型标识').getComponent(ElSelect).vm.$emit('update:modelValue', model)
    await flushPromises()
    expect(selectable()).toEqual(expected)
    if (model === 'discovered') expect(wrapper!.text()).toContain('future-level')
  }
})

it('Messages图片能力取自目录，显式采用固定单图上界策略后可测试未保存配置', async () => {
  mocks.test.mockResolvedValue({ ok: true, latency_ms: 1 })
  await open(true)
  field('推理程度').getComponent(ElSelect).vm.$emit('update:modelValue', 'HIGH')
  await wrapper!
    .findAllComponents(ElButton)
    .find((button) => button.text() === '采用目录图片策略')!
    .trigger('click')
  await flushPromises()
  expect(field('基础 Token').getComponent(ElInputNumber).props('modelValue')).toBe(1024)
  expect(field('每切片 Token').getComponent(ElInputNumber).props('modelValue')).toBe(0)
  await wrapper!
    .findAllComponents(ElButton)
    .find((button) => button.text() === '测试当前配置')!
    .trigger('click')
  await flushPromises()
  expect(mocks.test).toHaveBeenCalledWith({
    id: 'existing',
    payload: expect.objectContaining({
      protocol: 'anthropic-messages',
      image_policy: expect.objectContaining({
        enabled: true,
        tool_images: true,
        base_tokens: 1024,
        tile_tokens: 0
      })
    })
  })
  expect(wrapper!.text()).not.toContain('当前协议不支持图片输入')
})

it('图片拒绝按目录声明显示字段原因，不根据Messages协议名推断支持', async () => {
  const catalog = await mocks.providers()
  catalog.providers[0].services[0].protocols[0] = {
    ...protocol,
    capabilities: { ...protocol.capabilities, user_images: 'UNSUPPORTED' },
    image_policy_defaults: null
  }
  mocks.providers.mockResolvedValue(catalog)
  await open(true)
  field('推理程度').getComponent(ElSelect).vm.$emit('update:modelValue', 'HIGH')
  field('允许图片输入').getComponent(ElSwitch).vm.$emit('update:modelValue', true)
  await flushPromises()
  await wrapper!
    .findAllComponents(ElButton)
    .find((button) => button.text() === '测试当前配置')!
    .trigger('click')
  await flushPromises()
  expect(mocks.test).not.toHaveBeenCalled()
  expect(field('允许图片输入').props('error')).toContain('当前服务的协议适配明确不支持图片输入')
})

it('目录未验证的图片能力保留管理员显式配置并说明待测试，不伪造已验证状态', async () => {
  const catalog = await mocks.providers()
  catalog.providers[0].services[0].protocols[0] = {
    ...protocol,
    capabilities: { ...protocol.capabilities, user_images: 'UNVERIFIED' },
    image_policy_defaults: null
  }
  mocks.providers.mockResolvedValue(catalog)
  mocks.test.mockResolvedValue({ ok: true, latency_ms: 1 })
  await open(true)
  field('推理程度').getComponent(ElSelect).vm.$emit('update:modelValue', 'HIGH')
  field('允许图片输入').getComponent(ElSwitch).vm.$emit('update:modelValue', true)
  await flushPromises()
  await wrapper!
    .findAllComponents(ElButton)
    .find((button) => button.text() === '测试当前配置')!
    .trigger('click')
  await flushPromises()
  expect(mocks.test).toHaveBeenCalledWith(
    expect.objectContaining({
      payload: expect.objectContaining({ image_policy: expect.objectContaining({ enabled: true }) })
    })
  )
  expect(wrapper!.text()).toContain('未验证的能力需依据供应商说明显式配置')
})

it('具体模型返回的图片能力覆盖协议目录上限，不把同协议其他模型的视觉能力借用过来', async () => {
  mocks.reasoningOptions.mockResolvedValue({
    ...options,
    image_support: {
      user_images: 'UNSUPPORTED',
      tool_images: 'UNSUPPORTED',
      defaults: null
    }
  })
  await open(true)
  field('推理程度').getComponent(ElSelect).vm.$emit('update:modelValue', 'HIGH')
  field('允许图片输入').getComponent(ElSwitch).vm.$emit('update:modelValue', true)
  await flushPromises()
  expect(
    wrapper!.findAllComponents(ElButton).some((button) => button.text() === '采用目录图片策略')
  ).toBe(false)
  await wrapper!
    .findAllComponents(ElButton)
    .find((button) => button.text() === '测试当前配置')!
    .trigger('click')
  await flushPromises()
  expect(mocks.test).not.toHaveBeenCalled()
  expect(field('允许图片输入').props('error')).toContain('明确不支持图片输入')
})

it('媒体模型类型只显示支持该种类的国内服务，媒体测试使用独立入口', async () => {
  const catalog = await mocks.providers()
  catalog.providers.push({
    provider_id: 'aliyun-bailian',
    display_name: '百炼北京',
    category: 'CLOUD',
    services: [
      {
        service_id: 'bailian-media-cn-beijing',
        display_name: '百炼北京图像服务',
        profile_version: 'media-v1',
        availability: 'SUPPORTED',
        endpoint: 'https://dashscope.aliyuncs.com',
        region: 'CHINA_MAINLAND',
        model_kinds: ['IMAGE_GENERATION'],
        custom_endpoint: false,
        model_discovery: null,
        protocols: [{ ...protocol, protocol: 'bailian-media', image_policy_defaults: null }]
      }
    ]
  })
  mocks.providers.mockResolvedValue(catalog)
  await open()
  const kind = field('类型').getComponent(ElSelect)
  kind.vm.$emit('update:modelValue', 'IMAGE_GENERATION')
  kind.vm.$emit('change', 'IMAGE_GENERATION')
  await flushPromises()
  expect(
    field('提供方')
      .findAllComponents({ name: 'ElOption' })
      .map((option) => option.props('value'))
  ).toEqual(['aliyun-bailian'])
  const provider = field('提供方').getComponent(ElSelect)
  provider.vm.$emit('update:modelValue', 'aliyun-bailian')
  provider.vm.$emit('change', 'aliyun-bailian')
  await flushPromises()
  expect(field('提供方服务').getComponent(ElSelect).props('modelValue')).toBe(
    'bailian-media-cn-beijing'
  )
  expect(wrapper!.text()).toContain('中国内地')
  expect(
    wrapper!.findAllComponents(ElButton).some((button) => button.text() === '测试当前配置')
  ).toBe(false)
  expect(
    wrapper!.findAllComponents(ElButton).some((button) => button.text() === '开始媒体测试')
  ).toBe(true)
})
