import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { ElButton, ElFormItem, ElInput, ElInputNumber, ElSelect } from 'element-plus'
import ModelDetailPage from './ModelDetailPage.vue'
import type { ModelCachePricing, ModelView, ModelWrite } from '../../api'

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

const READ_LABEL = '缓存命中价格（分 / 百万 Token）'
const WRITE_LABEL = '缓存写入价格（分 / 百万 Token）'
const calls: { path: string; method: string; body: string | undefined }[] = []
let wrapper: VueWrapper | undefined
let current: ModelView
let rejectUpdate = false

function saved(cache: ModelCachePricing | null = null): ModelView {
  return {
    id: 'existing',
    name: '缓存计价模型',
    kind: 'LLM',
    enabled: true,
    provider_id: 'local-fixture',
    service_id: 'llm',
    profile_version: '1',
    protocol: 'openai-chat',
    authentication_scheme: 'bearer-api-key',
    endpoint: 'http://127.0.0.1:9999',
    model_id: 'fixture-model',
    currency: 'CNY',
    timeout_seconds: 600,
    reasoning: 'DEFAULT',
    supported_max_output_tokens: 2048,
    context_window_tokens: 8192,
    max_output_tokens: 1024,
    input_price_per_million_cents: '123.456789',
    output_price_per_million_cents: '456.000001',
    revision: 3,
    published_revision: 2,
    has_unpublished_changes: true,
    credential_configured: true,
    media_pricing: null,
    cache_pricing: cache
  }
}
function response(data: unknown, status = 200) {
  return new Response(
    JSON.stringify({
      code: status === 200 ? 'OK' : 'MODEL_CONFIG_INVALID',
      message: status === 200 ? '成功' : '缓存价格配置未保存，请核对后重试',
      data,
      trace_id: 'cache-price-test'
    }),
    { status, headers: { 'Content-Type': 'application/json' } }
  )
}
beforeEach(() => {
  calls.length = 0
  current = saved()
  rejectUpdate = false
  route.path = '/platform/models/existing'
  localStorage.clear()
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = new URL(String(input), 'http://127.0.0.1').pathname
      const method = init?.method ?? 'GET'
      const body = init?.body === undefined ? undefined : String(init.body)
      calls.push({ path, method, body })
      if (path === '/api/v1/admin/models/providers')
        return response({
          providers: [
            {
              provider_id: 'local-fixture',
              display_name: '本地测试提供方',
              category: 'LOCAL',
              services: [
                { service_id: 'llm', model_kinds: ['LLM'], protocol: 'openai-chat' },
                { service_id: 'rerank', model_kinds: ['RERANK'], protocol: 'rerank-native' }
              ].map((service) => ({
                service_id: service.service_id,
                display_name: service.service_id,
                model_kinds: service.model_kinds,
                profile_version: '1',
                availability: 'SUPPORTED',
                region: 'LOCAL',
                endpoint: 'http://127.0.0.1:9999',
                protocols: [
                  {
                    protocol: service.protocol,
                    authentication_schemes: ['bearer-api-key'],
                    capabilities: { user_images: 'SUPPORTED', tool_images: 'SUPPORTED' }
                  }
                ]
              }))
            }
          ]
        })
      if (path === '/api/v1/admin/models/detail') return response(current)
      if (path === '/api/v1/admin/models/reasoning-options')
        return response({ options: ['DEFAULT'], source: 'UNVERIFIED', source_url: null })
      if (path === '/api/v1/admin/models/test') return response({ ok: true, latency_ms: 1 })
      if (path === '/api/v1/admin/models/update') {
        if (rejectUpdate) return response(null, 422)
        const command = JSON.parse(body ?? '{}') as {
          id: string
          payload: ModelWrite & { expected_revision: number }
        }
        const {
          api_key: _apiKey,
          expected_revision: _expectedRevision,
          ...configuration
        } = command.payload
        current = { ...current, ...configuration, revision: current.revision + 1 }
        return response(current)
      }
      if (path === '/api/v1/admin/models' && method === 'POST') {
        const { api_key: _apiKey, ...configuration } = JSON.parse(body ?? '{}') as ModelWrite
        current = { ...saved(), ...configuration, revision: 1, published_revision: null }
        return response(current)
      }
      throw new Error(`Unexpected request: ${method} ${path}`)
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
async function open(cache: ModelCachePricing | null = null, newModel = false) {
  current = saved(cache)
  route.path = newModel ? '/platform/models/new' : '/platform/models/existing'
  wrapper = mount(ModelDetailPage, {
    global: { directives: { permission: () => {} }, stubs: { RouterLink: true } }
  })
  await flushPromises()
}
async function mode(value: ModelCachePricing['mode'] | 'UNIFIED') {
  field('缓存输入计价').getComponent(ElSelect).vm.$emit('update:modelValue', value)
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
  const call = requests('test').at(-1)
  if (!call?.body) throw new Error('没有测试请求')
  return JSON.parse(call.body)
}
async function configureNew() {
  const provider = field('提供方').getComponent(ElSelect)
  provider.vm.$emit('update:modelValue', 'local-fixture')
  provider.vm.$emit('change', 'local-fixture')
  await flushPromises()
  field('模型标识').getComponent(ElSelect).vm.$emit('update:modelValue', 'fixture-model')
  await input('名称', '未保存缓存模型')
  await input('API 凭据', 'synthetic-cache-key')
  for (const [label, value] of [
    ['支持最大输出 Token', 2048],
    ['上下文窗口 Token', 8192],
    ['本次输出 Token 上限', 1024]
  ] as const)
    field(label).getComponent(ElInputNumber).vm.$emit('update:modelValue', value)
  await input('每百万输入 Token（分）', '123.456789')
  await input('每百万输出 Token（分）', '456.000001')
}

it('新建LLM无需保存即可按真实JSON请求发送缓存价，空价和零价分别保留NULL与字符串0', async () => {
  await open(null, true)
  await configureNew()
  expect(field('缓存输入计价').getComponent(ElSelect).props('modelValue')).toBe('UNIFIED')
  await mode('CACHE_READ_WRITE')
  await click('测试当前配置')
  expect(testCommand()).toMatchObject({
    payload: {
      cache_pricing: {
        mode: 'CACHE_READ_WRITE',
        read_price_per_million_cents: null,
        write_price_per_million_cents: null
      },
      input_price_per_million_cents: '123.456789',
      output_price_per_million_cents: '456.000001'
    }
  })
  expect(testCommand()).not.toHaveProperty('id')
  await input(READ_LABEL, '0')
  await input(WRITE_LABEL, '0.000001')
  await click('测试当前配置')
  expect(testCommand().payload?.cache_pricing).toEqual({
    mode: 'CACHE_READ_WRITE',
    read_price_per_million_cents: '0',
    write_price_per_million_cents: '0.000001'
  })
  expect(requests('test').at(-1)?.body).toContain('"read_price_per_million_cents":"0"')
  expect(calls.some((call) => call.path === '/api/v1/admin/models' && call.method === 'POST')).toBe(
    false
  )
})

it('已保存模型加载缓存价，未修改测试仅传id；改价preview和保存保留大十进制且无需重填凭据', async () => {
  await open({
    mode: 'CACHE_READ_WRITE',
    read_price_per_million_cents: '0',
    write_price_per_million_cents: '9007199254740993.123456'
  })
  expect(field(READ_LABEL).getComponent(ElInput).props('modelValue')).toBe('0')
  expect(field(WRITE_LABEL).getComponent(ElInput).props('modelValue')).toBe(
    '9007199254740993.123456'
  )
  await click('测试当前配置')
  expect(testCommand()).toEqual({ id: 'existing' })
  await input(READ_LABEL, '9223372036854775807')
  await click('测试当前配置')
  const expected = {
    mode: 'CACHE_READ_WRITE',
    read_price_per_million_cents: '9223372036854775807',
    write_price_per_million_cents: '9007199254740993.123456'
  }
  expect(testCommand()).toMatchObject({
    id: 'existing',
    payload: { expected_revision: 3, cache_pricing: expected }
  })
  expect(testCommand().payload).not.toHaveProperty('api_key')
  expect(requests('update')).toHaveLength(0)
  await click('保存草稿')
  expect(requests('update')).toHaveLength(1)
  const update = JSON.parse(requests('update')[0]!.body ?? '{}') as {
    id: string
    payload: ModelWrite
  }
  expect(update.id).toBe('existing')
  expect(update.payload.cache_pricing).toEqual(expected)
  expect(update.payload).not.toHaveProperty('api_key')
  await click('测试当前配置')
  expect(testCommand()).toEqual({ id: 'existing' })
})

it('切为命中分价清除写价并禁止携带，再切写入模式不恢复旧写价，统一输入模式发送null', async () => {
  await open({
    mode: 'CACHE_READ_WRITE',
    read_price_per_million_cents: '1.123456',
    write_price_per_million_cents: '2.654321'
  })
  await mode('CACHE_READ')
  expect(wrapper?.text()).not.toContain(WRITE_LABEL)
  await click('测试当前配置')
  expect(testCommand().payload?.cache_pricing).toEqual({
    mode: 'CACHE_READ',
    read_price_per_million_cents: '1.123456',
    write_price_per_million_cents: null
  })
  await mode('CACHE_READ_WRITE')
  expect(field(WRITE_LABEL).getComponent(ElInput).props('modelValue')).toBeNull()
  await mode('UNIFIED')
  await click('测试当前配置')
  expect(testCommand().payload).toMatchObject({
    cache_pricing: null,
    input_price_per_million_cents: '123.456789',
    output_price_per_million_cents: '456.000001'
  })
  await click('保存草稿')
  expect(current.cache_pricing).toBeNull()
  expect(field('缓存输入计价').getComponent(ElSelect).props('modelValue')).toBe('UNIFIED')
})

it('清空单项价格表示未知，明确零写价不被转成null', async () => {
  await open({
    mode: 'CACHE_READ_WRITE',
    read_price_per_million_cents: '1',
    write_price_per_million_cents: '0'
  })
  await input(READ_LABEL, '')
  await click('测试当前配置')
  expect(testCommand().payload?.cache_pricing).toEqual({
    mode: 'CACHE_READ_WRITE',
    read_price_per_million_cents: null,
    write_price_per_million_cents: '0'
  })
  expect(wrapper?.text()).toContain('完整单价，不是额外加价')
})

it.each(['01', '-1', '1e2', '0.0000001', '9223372036854775807.000001'])(
  '缓存价格%s在字段旁拒绝，保留原输入且不发送测试请求',
  async (value) => {
    await open()
    await mode('CACHE_READ')
    await input(READ_LABEL, value)
    await click('测试当前配置')
    expect(requests('test')).toHaveLength(0)
    expect(field(READ_LABEL).props('error')).toContain('最多 6 位小数')
    expect(field(READ_LABEL).getComponent(ElInput).props('modelValue')).toBe(value)
  }
)

it('写入价非法时不发送请求，切为仅命中模式移除无效写价后可以测试', async () => {
  await open()
  await mode('CACHE_READ_WRITE')
  await input(WRITE_LABEL, '0.0000001')
  await click('测试当前配置')
  expect(requests('test')).toHaveLength(0)
  expect(field(WRITE_LABEL).props('error')).toContain('最多 6 位小数')
  await mode('CACHE_READ')
  await click('测试当前配置')
  expect(testCommand().payload?.cache_pricing).toEqual({
    mode: 'CACHE_READ',
    read_price_per_million_cents: null,
    write_price_per_million_cents: null
  })
})

it('新建表单切类型清缓存配置，非LLM不显示缓存价且preview发送null', async () => {
  await open(null, true)
  await configureNew()
  await mode('CACHE_READ_WRITE')
  await input(READ_LABEL, '0')
  const kind = field('类型').getComponent(ElSelect)
  kind.vm.$emit('update:modelValue', 'RERANK')
  kind.vm.$emit('change', 'RERANK')
  await flushPromises()
  expect(wrapper?.text()).not.toContain('缓存输入计价')
  await input('API 凭据', 'synthetic-rerank-key')
  await click('测试当前配置')
  expect(testCommand().payload).toMatchObject({ kind: 'RERANK', cache_pricing: null })
  kind.vm.$emit('update:modelValue', 'LLM')
  kind.vm.$emit('change', 'LLM')
  await flushPromises()
  expect(field('缓存输入计价').getComponent(ElSelect).props('modelValue')).toBe('UNIFIED')
})

it('保存失败保留当前缓存模式、价格和原模型，不清除输入或补默认折扣', async () => {
  await open()
  await mode('CACHE_READ_WRITE')
  await input(READ_LABEL, '0')
  await input(WRITE_LABEL, '1.234567')
  rejectUpdate = true
  await click('保存草稿')
  expect(requests('update')).toHaveLength(1)
  expect(field('缓存输入计价').getComponent(ElSelect).props('modelValue')).toBe('CACHE_READ_WRITE')
  expect(field(READ_LABEL).getComponent(ElInput).props('modelValue')).toBe('0')
  expect(field(WRITE_LABEL).getComponent(ElInput).props('modelValue')).toBe('1.234567')
  expect(field('每百万输入 Token（分）').getComponent(ElInput).props('modelValue')).toBe(
    '123.456789'
  )
  expect(current.cache_pricing).toBeNull()
})
