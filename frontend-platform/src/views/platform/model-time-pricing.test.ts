import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { ElButton, ElFormItem, ElInput, ElInputNumber, ElSelect, ElSwitch } from 'element-plus'
import ModelDetailPage from './ModelDetailPage.vue'
import ModelTimePricingEditor from './ModelTimePricingEditor.vue'
import type { ModelView, ModelWrite, ModelTimePricingPolicy } from '../../api'

const route = vi.hoisted(() => ({ path: '/platform/models/existing' }))
vi.mock('vue-router', () => ({
  onBeforeRouteLeave: vi.fn(),
  onBeforeRouteUpdate: vi.fn(),
  useRoute: () => ({ path: route.path, params: { id: 'existing' } }),
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() })
}))
vi.mock('../../router', () => ({ default: {} }))
vi.mock('../../stores/auth', () => ({ useAuthStore: () => ({ logout: vi.fn() }) }))
vi.mock('../../composables/actionPermissions', () => ({
  useActionPermissions: () => ({
    allowed: () => true,
    checked: (_permission: string, action: () => unknown) => action()
  })
}))

function policy(): ModelTimePricingPolicy {
  return {
    time_zone: 'Etc/UTC',
    valid_from: '2031-01-01',
    valid_through: '2031-12-31',
    peak_weekdays: ['TUESDAY', 'SATURDAY'],
    peak_periods: [{ start: '03:15:00', end: '07:45:00' }],
    peak_date_ranges: [{ from: '2031-01-05', through: '2031-01-06' }],
    off_peak_date_ranges: [{ from: '2031-05-01', through: '2031-05-03' }],
    peak_multiplier: '1.125000000001',
    off_peak_multiplier: '0.375',
    applicable_models: [],
    source_url: 'https://example.cn/pricing',
    source_version: 'editable-policy-2031',
    calendar_source_url: 'https://example.cn/calendar'
  }
}
const LABEL = '峰谷计价'
const calls: { path: string; body: string | undefined }[] = []
let wrapper: VueWrapper | undefined
let current: ModelView
let profileVersion = '2026-09-13'

function saved(values: Partial<ModelView> = {}): ModelView {
  return {
    id: 'existing',
    name: 'DeepSeek 模型',
    kind: 'LLM',
    enabled: true,
    provider_id: 'deepseek',
    service_id: 'deepseek-api',
    profile_version: profileVersion,
    protocol: 'openai-chat',
    authentication_scheme: 'bearer-api-key',
    endpoint: 'https://api.deepseek.com',
    model_id: 'deepseek-v4-flash',
    currency: 'CNY',
    timeout_seconds: 600,
    reasoning: 'DEFAULT',
    supported_max_output_tokens: 2048,
    context_window_tokens: 8192,
    max_output_tokens: 1024,
    input_price_per_million_cents: '12.123456',
    output_price_per_million_cents: '45.000001',
    cache_pricing: {
      mode: 'CACHE_READ',
      read_price_per_million_cents: '1.123456',
      write_price_per_million_cents: null
    },
    revision: 3,
    published_revision: 2,
    has_unpublished_changes: true,
    credential_configured: true,
    media_pricing: null,
    ...values
  }
}
function response(data: unknown) {
  return new Response(
    JSON.stringify({ code: 'OK', message: '成功', data, trace_id: 'time-pricing-test' }),
    { status: 200, headers: { 'Content-Type': 'application/json' } }
  )
}
function service(serviceId: string, version: string, kinds = ['LLM']) {
  return {
    service_id: serviceId,
    display_name: serviceId,
    model_kinds: kinds,
    profile_version: version,
    availability: 'SUPPORTED',
    region: 'CHINA_MAINLAND',
    endpoint: 'https://api.deepseek.com',
    protocols: [
      {
        protocol: 'openai-chat',
        authentication_schemes: ['bearer-api-key'],
        capabilities: { user_images: 'SUPPORTED', tool_images: 'SUPPORTED' }
      }
    ]
  }
}
beforeEach(() => {
  calls.length = 0
  profileVersion = '2026-09-13'
  current = saved()
  route.path = '/platform/models/existing'
  localStorage.clear()
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = new URL(String(input), 'http://127.0.0.1').pathname
      const body = init?.body === undefined ? undefined : String(init.body)
      calls.push({ path, body })
      if (path === '/api/v1/admin/models/providers')
        return response({
          providers: [
            {
              provider_id: 'deepseek',
              display_name: 'DeepSeek',
              category: 'CLOUD',
              services: [
                service('deepseek-api', profileVersion),
                service('other-api', profileVersion),
                service('fixture-rerank', profileVersion, ['RERANK'])
              ]
            },
            {
              provider_id: 'other-local',
              display_name: '本地测试服务',
              category: 'LOCAL',
              services: [service('local-api', '1')]
            }
          ]
        })
      if (path === '/api/v1/admin/models/detail') return response(current)
      if (path === '/api/v1/admin/models/reasoning-options')
        return response({ options: ['DEFAULT'], source: 'UNVERIFIED', source_url: null })
      if (path === '/api/v1/admin/models/test') return response({ ok: true, latency_ms: 1 })
      if (path === '/api/v1/admin/models/update') {
        const command = JSON.parse(body ?? '{}') as {
          id: string
          payload: ModelWrite & { expected_revision: number }
        }
        const { api_key: _apiKey, expected_revision: _revision, ...configuration } = command.payload
        current = { ...current, ...configuration, revision: current.revision + 1 }
        return response(current)
      }
      throw new Error('Unexpected request: ' + path)
    })
  )
})
afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  vi.unstubAllGlobals()
  localStorage.clear()
  document.body.innerHTML = ''
})
function field(label: string) {
  const result = wrapper
    ?.findAllComponents(ElFormItem)
    .find((item) => item.props('label') === label)
  if (!result) throw new Error('未找到字段：' + label)
  return result
}
async function open(values: Partial<ModelView> = {}, newModel = false) {
  current = saved(values)
  route.path = newModel ? '/platform/models/new' : '/platform/models/existing'
  wrapper = mount(ModelDetailPage, {
    global: { directives: { permission: () => {} }, stubs: { RouterLink: true } }
  })
  await flushPromises()
}
async function select(label: string, value: string, change = false) {
  const control = field(label).getComponent(ElSelect)
  control.vm.$emit('update:modelValue', value)
  if (change) control.vm.$emit('change', value)
  await flushPromises()
}
async function input(label: string, value: string) {
  await field(label).getComponent(ElInput).get('input').setValue(value)
  await flushPromises()
}
async function click(label: string) {
  const button = wrapper?.findAllComponents(ElButton).find((item) => item.text() === label)
  if (!button) throw new Error('未找到按钮：' + label)
  await button.trigger('click')
  await flushPromises()
}
function requests(path: string) {
  return calls.filter((call) => call.path === '/api/v1/admin/models/' + path)
}
function testCommand(): { id?: string; payload?: ModelWrite & { expected_revision?: number } } {
  const body = requests('test').at(-1)?.body
  if (!body) throw new Error('没有测试请求')
  return JSON.parse(body)
}
async function configureNew() {
  await select('提供方', 'deepseek', true)
  await select('提供方服务', 'deepseek-api', true)
  await select('模型标识', 'deepseek-v4-flash')
  await input('名称', '未保存峰谷模型')
  await input('API 凭据', 'synthetic-time-key')
  for (const [label, value] of [
    ['支持最大输出 Token', 2048],
    ['上下文窗口 Token', 8192],
    ['本次输出 Token 上限', 1024]
  ] as const)
    field(label).getComponent(ElInputNumber).vm.$emit('update:modelValue', value)
  await select('缓存输入计价', 'CACHE_READ')
}

async function configurePolicy(value = policy()) {
  if (!wrapper?.findComponent(ModelTimePricingEditor).exists()) {
    field(LABEL).getComponent(ElSwitch).vm.$emit('update:modelValue', true)
    await flushPromises()
  }
  wrapper!.getComponent(ModelTimePricingEditor).vm.$emit('update:modelValue', value)
  await flushPromises()
}

it.each(['UNIFIED', 'CACHE_READ', 'CACHE_READ_WRITE'])(
  '任意语言模型的%s输入计价可提交完整动态规则，不补默认供应商折扣',
  async (mode) => {
    await open({ model_id: 'future-model-2031', cache_pricing: null })
    await select('缓存输入计价', mode)
    await configurePolicy()
    await click('测试当前配置')
    expect(testCommand().payload?.time_pricing).toEqual(policy())
    expect(testCommand().payload).toMatchObject({
      input_price_per_million_cents: '12.123456',
      output_price_per_million_cents: '45.000001'
    })
    expect(testCommand().payload).not.toHaveProperty('api_key')
    expect(requests('update')).toHaveLength(0)
  }
)

it('新建未保存配置使用完整时段规则和当前密钥，倍率0与未知分开', async () => {
  await open({}, true)
  await configureNew()
  const value = policy()
  value.peak_multiplier = null
  value.off_peak_multiplier = '0'
  await configurePolicy(value)
  await click('测试当前配置')
  expect(testCommand()).not.toHaveProperty('id')
  expect(testCommand().payload).toMatchObject({
    api_key: 'synthetic-time-key',
    time_pricing: value
  })
  expect(requests('update')).toHaveLength(0)
  expect(localStorage.length).toBe(0)
})

it('缓存模式切换保留独立时段规则，关闭时只清除time_pricing', async () => {
  await open({ time_pricing: policy() })
  await select('缓存输入计价', 'UNIFIED')
  await select('缓存输入计价', 'CACHE_READ_WRITE')
  await click('测试当前配置')
  expect(testCommand().payload?.time_pricing).toEqual(policy())
  field(LABEL).getComponent(ElSwitch).vm.$emit('update:modelValue', false)
  await flushPromises()
  await click('测试当前配置')
  expect(testCommand().payload).toMatchObject({
    time_pricing: null,
    input_price_per_million_cents: '12.123456',
    cache_pricing: { mode: 'CACHE_READ_WRITE' }
  })
})

it('草稿回读深拷贝规则，编辑时段不会修改原对象；保存留空密钥', async () => {
  const original = policy()
  await open({ time_pricing: original })
  const editor = wrapper!.getComponent(ModelTimePricingEditor)
  await editor.get('input[aria-label="峰时段 1 开始"]').setValue('04:00:00')
  await flushPromises()
  expect(original.peak_periods[0]!.start).toBe('03:15:00')
  await click('保存草稿')
  const command = JSON.parse(requests('update').at(-1)!.body!)
  expect(command.payload.time_pricing.peak_periods[0].start).toBe('04:00:00')
  expect(command.payload).not.toHaveProperty('api_key')
  expect(current.time_pricing?.source_version).toBe('editable-policy-2031')
})

it('数据范围不匹配时定位规则范围错误，未知模型可通过修改scope采用', async () => {
  await open()
  const value = policy()
  value.applicable_models = [
    { provider_id: null, service_id: null, profile_version: null, model_id: 'different-model' }
  ]
  await configurePolicy(value)
  await click('测试当前配置')
  expect(requests('test')).toHaveLength(0)
  expect(
    wrapper!.getComponent(ModelTimePricingEditor).props('errors')?.[
      'time_pricing.applicable_models'
    ]
  ).toContain('当前模型不在规则适用范围内')
  value.applicable_models[0]!.model_id = current.model_id
  await configurePolicy({ ...value })
  await click('测试当前配置')
  expect(testCommand().payload?.time_pricing?.applicable_models).toEqual(value.applicable_models)
})

it('非法日期与时段保留输入并阻止请求，修正后按输入提交', async () => {
  await open()
  const value = policy()
  value.valid_through = '2030-12-31'
  value.peak_periods = [{ start: '23:00:00', end: '01:00:00' }]
  await configurePolicy(value)
  await click('测试当前配置')
  expect(requests('test')).toHaveLength(0)
  expect(
    wrapper!.getComponent(ModelTimePricingEditor).props('errors')?.['time_pricing.valid_through']
  ).toContain('覆盖结束日期不得早于开始日期')
  expect(
    wrapper!.getComponent(ModelTimePricingEditor).props('errors')?.['time_pricing.peak_periods.0']
  ).toContain('跨午夜请拆为两段')
  await configurePolicy()
  await click('测试当前配置')
  expect(testCommand().payload?.time_pricing).toEqual(policy())
})

it('长规则提交显示可跳转错误摘要，修改已报错的字段后即时更新对应错误', async () => {
  await open()
  const value = policy()
  value.peak_multiplier = '-1'
  value.off_peak_multiplier = '1e2'
  await configurePolicy(value)
  await click('保存草稿')
  expect(requests('update')).toHaveLength(0)
  expect(wrapper!.get('[aria-label="配置错误摘要"]').findAll('button')).toHaveLength(2)
  const editor = wrapper!.getComponent(ModelTimePricingEditor)
  const input = editor
    .findAllComponents(ElFormItem)
    .find((item) => item.props('label') === '峰时倍率')!
  await input.getComponent(ElInput).find('input').setValue('0')
  await flushPromises()
  expect(editor.props('errors')?.['time_pricing.peak_multiplier']).toBeUndefined()
  expect(editor.props('errors')?.['time_pricing.off_peak_multiplier']).toContain('倍率')
  expect(wrapper!.find('[aria-label="配置错误摘要"]').exists()).toBe(false)
})

it('保存校验失败后解除提交禁用，再将真实DOM焦点交给首个无效倍率', async () => {
  await open()
  document.body.appendChild(wrapper!.element)
  const value = policy()
  value.peak_multiplier = '-1'
  await configurePolicy(value)
  await click('保存草稿')
  const editor = wrapper!.getComponent(ModelTimePricingEditor)
  const firstInvalid = editor
    .findAllComponents(ElFormItem)
    .find((item) => item.props('label') === '峰时倍率')!
    .getComponent(ElInput)
    .get('input').element
  expect((firstInvalid as HTMLInputElement).disabled).toBe(false)
  expect(document.activeElement).toBe(firstInvalid)
  expect(requests('update')).toHaveLength(0)
})
