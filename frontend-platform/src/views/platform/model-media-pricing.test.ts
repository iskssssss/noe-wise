import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { ElButton, ElFormItem, ElInput, ElSelect } from 'element-plus'
import type { ModelKind, ModelMediaPricing, ModelView, ModelWrite } from '../../api'
import ModelDetailPage from './ModelDetailPage.vue'
import ModelMediaProbe from './ModelMediaProbe.vue'
import { mediaPriceUnits, validModelPrice } from './model-media-pricing'

const mocks = vi.hoisted(() => ({
  providers: vi.fn(),
  detail: vi.fn(),
  update: vi.fn(),
  create: vi.fn(),
  showMessage: vi.fn(() => vi.fn()),
  path: '/platform/models/existing'
}))
vi.mock('../../stores/auth', () => ({
  useAuthStore: () => ({ identityVersion: 1, user: { sub: 'actor' } })
}))
vi.mock('../../api', () => ({
  api: { admin: { models: mocks } }
}))
vi.mock('../../composables/actionPermissions', () => ({
  useActionPermissions: () => ({
    allowed: () => true,
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
const imagePrice: ModelMediaPricing = {
  unit: 'OUTPUT_IMAGE',
  price_per_unit_cents: '0.000001',
  input_price_per_million_cents: null,
  output_price_per_million_cents: null,
  video_resolution: null
}
function savedModel(kind: ModelKind = 'IMAGE_GENERATION'): ModelView {
  return {
    id: 'existing',
    revision: 3,
    published_revision: 2,
    has_unpublished_changes: true,
    credential_configured: true,
    name: '国内媒体模型',
    kind,
    enabled: true,
    provider_id: 'aliyun',
    service_id: 'media-cn',
    profile_version: '1',
    protocol: 'media-native',
    authentication_scheme: 'bearer-api-key',
    endpoint: 'https://dashscope.aliyuncs.com',
    model_id: 'configured-media-model',
    timeout_seconds: 600,
    currency: 'CNY',
    media_pricing: null
  }
}
function field(label: string) {
  const result = wrapper
    ?.findAllComponents(ElFormItem)
    .find((entry) => entry.props('label') === label)
  if (!result) throw new Error('未找到字段：' + label)
  return result
}
async function chooseUnit(unit: string) {
  field('媒体计费单位').getComponent(ElSelect).vm.$emit('update:modelValue', unit)
  await flushPromises()
}
function prepare() {
  if (!wrapper) throw new Error('尚未打开模型表单')
  return wrapper.getComponent(ModelMediaProbe).props('prepare')()
}
async function open(model: ModelView) {
  mocks.detail.mockResolvedValue(model)
  wrapper = mount(ModelDetailPage, {
    global: { directives: { permission: () => {} }, stubs: { RouterLink: true } }
  })
  await flushPromises()
}
async function save() {
  const button = wrapper?.findAllComponents(ElButton).find((entry) => entry.text() === '保存草稿')
  if (!button) throw new Error('未找到保存按钮')
  await button.trigger('click')
  await flushPromises()
}
beforeEach(() => {
  sessionStorage.clear()
  vi.resetAllMocks()
  mocks.path = '/platform/models/existing'
  mocks.providers.mockResolvedValue({
    providers: [
      {
        provider_id: 'aliyun',
        display_name: '阿里云',
        category: 'CLOUD',
        services: [
          {
            service_id: 'media-cn',
            display_name: '国内媒体服务',
            profile_version: '1',
            availability: 'SUPPORTED',
            model_kinds: [
              'IMAGE_GENERATION',
              'VIDEO_GENERATION',
              'SPEECH_RECOGNITION',
              'SPEECH_SYNTHESIS'
            ],
            endpoint: 'https://dashscope.aliyuncs.com',
            protocols: [
              {
                protocol: 'media-native',
                authentication_schemes: ['bearer-api-key'],
                capabilities: {}
              }
            ]
          }
        ]
      }
    ]
  })
  mocks.update.mockImplementation((_id: string, payload: ModelWrite) =>
    Promise.resolve({
      ...savedModel(payload.kind),
      ...payload,
      revision: 4,
      credential_configured: true
    })
  )
})
afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
})

it.each([
  ['0', true],
  ['0.0', true],
  ['01', false],
  ['00.1', false],
  ['0.000001', true],
  ['9007199254740993.123456', true],
  ['9223372036854775807', true],
  ['9223372036854775807.000000', true],
  ['9223372036854775807.000001', false],
  ['9223372036854775808', false],
  ['0.0000001', false],
  ['1e2', false],
  ['-1', false],
  ['', false],
  [' 1', false],
  ['1.', false]
] as const)('价格 %s 的十进制精度校验为 %s', (value, expected) => {
  expect(validModelPrice(value)).toBe(expected)
})

it.each([
  ['IMAGE_GENERATION', ['OUTPUT_IMAGE', 'TOKENS']],
  ['VIDEO_GENERATION', ['OUTPUT_VIDEO', 'VIDEO_SECOND', 'TOKENS']],
  ['SPEECH_RECOGNITION', ['INPUT_AUDIO_SECOND', 'TOKENS']],
  ['SPEECH_SYNTHESIS', ['BILLING_CHARACTER', 'OUTPUT_AUDIO_SECOND', 'TOKENS']]
] as const)('为 %s 只展示对应计费单位', async (kind, units) => {
  await open(savedModel(kind))
  expect(
    field('媒体计费单位')
      .findAllComponents({ name: 'ElOption' })
      .map((entry) => entry.props('value'))
  ).toEqual(units)
  expect(mediaPriceUnits[kind]).toEqual(units)
})

it('加载保存的媒体价，未保存测试与保存共用当前字符串价格，不提交旧三价且不用重填凭据', async () => {
  await open({
    ...savedModel(),
    media_pricing: { ...imagePrice },
    input_price_per_million_cents: '99'
  })
  expect(field('每单位价格（分）').getComponent(ElInput).props('modelValue')).toBe('0.000001')
  field('每单位价格（分）')
    .getComponent(ElInput)
    .vm.$emit('update:modelValue', '9007199254740993.123456')
  await flushPromises()
  const expected = { ...imagePrice, price_per_unit_cents: '9007199254740993.123456' }
  const preview = prepare()
  expect(preview.payload).toMatchObject({
    media_pricing: expected,
    expected_revision: 3,
    input_price_per_million_cents: null,
    output_price_per_million_cents: null,
    price_per_quantity_cents: null
  })
  expect(preview.payload).not.toHaveProperty('api_key')
  expect(mocks.update).not.toHaveBeenCalled()
  await save()
  expect(mocks.update).toHaveBeenCalledWith(
    'existing',
    expect.objectContaining({ media_pricing: expected })
  )
  expect(field('每单位价格（分）').getComponent(ElInput).props('modelValue')).toBe(
    expected.price_per_unit_cents
  )
  expect(prepare()).toEqual({ id: 'existing' })
})

it('整体未配置允许缺价，清空单位提交 null', async () => {
  await open({ ...savedModel(), media_pricing: { ...imagePrice } })
  await chooseUnit('')
  expect(prepare().payload?.media_pricing).toBeNull()
  expect(wrapper?.text()).toContain('留空表示缺价')
  await save()
  expect(mocks.update).toHaveBeenCalledWith(
    'existing',
    expect.objectContaining({ media_pricing: null })
  )
  expect(field('媒体计费单位').getComponent(ElSelect).props('modelValue')).toBe('')
})

it('选单位后必须明确价格，零价允许且切到 Token 清除旧单价并要求双价', async () => {
  await open(savedModel())
  await chooseUnit('OUTPUT_IMAGE')
  expect(() => prepare()).toThrow('请修正')
  await flushPromises()
  expect(field('每单位价格（分）').props('error')).toContain('请填写单价')
  field('每单位价格（分）').getComponent(ElInput).vm.$emit('update:modelValue', '0')
  await flushPromises()
  expect(prepare().payload?.media_pricing?.price_per_unit_cents).toBe('0')
  await chooseUnit('TOKENS')
  expect(() => prepare()).toThrow('请修正 2 项配置')
  field('每百万输入 Token（分）').getComponent(ElInput).vm.$emit('update:modelValue', '1.123456')
  field('每百万输出 Token（分）').getComponent(ElInput).vm.$emit('update:modelValue', '0')
  await flushPromises()
  expect(prepare().payload?.media_pricing).toEqual({
    unit: 'TOKENS',
    price_per_unit_cents: null,
    input_price_per_million_cents: '1.123456',
    output_price_per_million_cents: '0',
    video_resolution: null
  })
  await chooseUnit('OUTPUT_IMAGE')
  expect(field('每单位价格（分）').getComponent(ElInput).props('modelValue')).toBeNull()
})

it('视频秒价必须绑定分辨率，切单位后分辨率与旧单价均清除', async () => {
  await open(savedModel('VIDEO_GENERATION'))
  await chooseUnit('VIDEO_SECOND')
  field('每单位价格（分）').getComponent(ElInput).vm.$emit('update:modelValue', '0.1')
  await flushPromises()
  expect(() => prepare()).toThrow('请选择视频秒单价适用的分辨率')
  field('视频单价适用分辨率').getComponent(ElSelect).vm.$emit('update:modelValue', '720P')
  await flushPromises()
  expect(prepare().payload?.media_pricing?.video_resolution).toBe('720P')
  await chooseUnit('OUTPUT_VIDEO')
  field('每单位价格（分）').getComponent(ElInput).vm.$emit('update:modelValue', '0.2')
  await flushPromises()
  expect(prepare().payload?.media_pricing).toEqual({
    unit: 'OUTPUT_VIDEO',
    price_per_unit_cents: '0.2',
    input_price_per_million_cents: null,
    output_price_per_million_cents: null,
    video_resolution: null
  })
})

it('切换新建模型类型后清空媒体定价，不把原类型单位带入新模型', async () => {
  mocks.path = '/platform/models/new'
  await open(savedModel())
  const kind = field('类型').getComponent(ElSelect)
  kind.vm.$emit('update:modelValue', 'IMAGE_GENERATION')
  kind.vm.$emit('change', 'IMAGE_GENERATION')
  await flushPromises()
  await chooseUnit('OUTPUT_IMAGE')
  kind.vm.$emit('update:modelValue', 'VIDEO_GENERATION')
  kind.vm.$emit('change', 'VIDEO_GENERATION')
  await flushPromises()
  expect(field('媒体计费单位').getComponent(ElSelect).props('modelValue')).toBe('')
})
