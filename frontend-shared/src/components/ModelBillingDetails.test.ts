import { mount, flushPromises } from '@vue/test-utils'
import { expect, it } from 'vitest'
import ElementPlus from 'element-plus'
import ModelBillingDetails from './ModelBillingDetails.vue'
import type { ModelBillingLine, ModelBillingMonthly } from '../types/billing'

const line = (values: Partial<ModelBillingLine> = {}): ModelBillingLine => ({
  id: 'usage-1',
  event_type: 'llm.chat',
  provider: '国内提供方',
  model: '模型1',
  occurred_at: '2026-10-01T00:00:00+08:00',
  invocation_id: 'call-1',
  attempt_id: 'attempt-1',
  session_id: 'session-1',
  run_id: 'run-1',
  quantity: '1',
  prompt_tokens: null,
  completion_tokens: null,
  image_count: null,
  video_count: null,
  characters: null,
  duration_seconds: null,
  pricing_snapshot_version: 'model-prices.v1',
  pricing_model_id: '9223372036854775807',
  pricing_model_revision: 7,
  original_amount: '0.00000001',
  original_currency: 'CNY',
  amount: '0.00000001',
  cost_status: 'priced',
  ...values
})
const monthly = (
  items: ModelBillingLine[],
  values: Partial<ModelBillingMonthly> = {}
): ModelBillingMonthly => ({
  month: '2026-10',
  currency: 'CNY',
  model_calls: items.length,
  known_amount: '0.00000001',
  total_amount: '0.00000001',
  missing_cost_count: 0,
  prompt_tokens: '9007199254740993',
  completion_tokens: null,
  total: items.length,
  page: 1,
  page_size: 20,
  items,
  ...values
})

it('模型费用只展示服务端精确金额、用量与调用时价格依据，不出现销售或应收', async () => {
  const wrapper = mount(ModelBillingDetails, {
    props: {
      monthly: monthly([line({ prompt_tokens: '9007199254740993', completion_tokens: '0' })])
    },
    global: { plugins: [ElementPlus] }
  })
  await flushPromises()
  expect(wrapper.text()).toContain('0.00000001')
  expect(wrapper.text()).toContain('9007199254740993')
  expect(wrapper.text()).toContain('输出 0 Token')
  expect(wrapper.text()).toContain('9223372036854775807')
  expect(wrapper.text()).toContain('修订 7')
  expect(wrapper.text()).toContain('model-prices.v1')
  for (const text of ['销售', '机构应收', '封账', '调账'])
    expect(wrapper.text()).not.toContain(text)
  wrapper.unmount()
})

it('合计有缺口时显示待核实，已计算费用仅为已知部分；原币与缺少汇率保持原值', async () => {
  const wrapper = mount(ModelBillingDetails, {
    props: {
      monthly: monthly(
        [
          line({
            amount: null,
            original_amount: '0.123456789012',
            original_currency: 'USD',
            cost_status: 'missing_fx'
          })
        ],
        {
          total_amount: null,
          missing_cost_count: 1,
          known_amount: '0'
        }
      )
    },
    global: { plugins: [ElementPlus] }
  })
  await flushPromises()
  expect(wrapper.text()).toContain('缺少汇率，待核实')
  expect(wrapper.text()).toContain('0.123456789012 USD')
  expect(wrapper.text()).toContain('不代表完整费用')
  expect(wrapper.text()).toContain('已计算费用')
  expect(wrapper.text()).toContain('未核实不视为零费用')
  expect(wrapper.text()).toMatch(/模型费用合计（元）\s*待核实/)
  wrapper.unmount()
})

it('四媒体用量与调用次数分别呈现，零值不丢失，未定价不自动补价', async () => {
  const wrapper = mount(ModelBillingDetails, {
    props: {
      monthly: monthly(
        [
          line({
            id: 'image',
            event_type: 'media.image',
            image_count: '0',
            amount: null,
            cost_status: 'unpriced'
          }),
          line({
            id: 'video',
            event_type: 'media.video',
            video_count: '2',
            duration_seconds: '6.5'
          }),
          line({
            id: 'asr',
            event_type: 'media.transcription',
            duration_seconds: '1',
            prompt_tokens: '51',
            completion_tokens: '13'
          }),
          line({ id: 'tts', event_type: 'media.speech', characters: '18' })
        ],
        { total_amount: null, missing_cost_count: 1 }
      )
    },
    global: { plugins: [ElementPlus] }
  })
  await flushPromises()
  for (const text of [
    '图像生成与编辑',
    '视频生成',
    '语音识别',
    '语音合成',
    '图片 0 张',
    '视频 2 个',
    '时长 6.5 秒',
    '计费字符 18',
    '调用 1 次',
    '缺少模型价格，待核实'
  ])
    expect(wrapper.text()).toContain(text)
  wrapper.unmount()
})

it('NULL用量保留待核实，不回退为零或根据调用次数猜媒体用量', async () => {
  const wrapper = mount(ModelBillingDetails, {
    props: {
      monthly: monthly(
        [
          line({
            event_type: 'media.image',
            quantity: null,
            original_amount: null,
            original_currency: null,
            amount: null,
            cost_status: 'unknown'
          })
        ],
        { total_amount: null, missing_cost_count: 1 }
      )
    },
    global: { plugins: [ElementPlus] }
  })
  await flushPromises()
  expect(wrapper.text()).toContain('用量待核实')
  expect(wrapper.text()).not.toContain('图片 0 张')
  expect(wrapper.text()).not.toContain('调用 0 次')
  wrapper.unmount()
})
