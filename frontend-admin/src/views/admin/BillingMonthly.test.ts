import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { ElDatePicker, ElPagination } from 'element-plus'
import { formatMonth } from '@skillforge/shared/utils/datetime'
import ModelBillingDetails from '@skillforge/shared/components/ModelBillingDetails.vue'
import type { ModelBillingMonthly } from '../../api'
import BillingRecordsPage from './BillingRecordsPage.vue'

const mocks = vi.hoisted(() => ({ records: vi.fn(), monthly: vi.fn() }))
vi.mock('../../api', () => ({ api: { billing: mocks } }))
vi.mock('vue-router', () => ({ onBeforeRouteLeave: vi.fn(), onBeforeRouteUpdate: vi.fn() }))
const result: ModelBillingMonthly = {
  month: '2026-10',
  currency: 'CNY',
  model_calls: 2,
  known_amount: '0.001440000000',
  total_amount: null,
  missing_cost_count: 1,
  prompt_tokens: '51',
  completion_tokens: '13',
  total: 41,
  page: 1,
  page_size: 20,
  items: []
}
let wrapper: VueWrapper | undefined
beforeEach(() => {
  vi.resetAllMocks()
  mocks.records.mockResolvedValue({ items: [], total: 0 })
  mocks.monthly.mockImplementation(
    async (params: { month: string; page: number; page_size: number }) => ({ ...result, ...params })
  )
})
afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  document.body.innerHTML = ''
})
async function open() {
  wrapper = mount(BillingRecordsPage, { attachTo: document.body })
  await flushPromises()
  await wrapper
    .findAll('[role="tab"]')
    .find((tab) => tab.text() === '模型月账单')!
    .trigger('click')
  await flushPromises()
  return wrapper
}
async function query(month: string) {
  if (!wrapper) throw new Error('页面未打开')
  wrapper.getComponent(ElDatePicker).vm.$emit('update:modelValue', month)
  await flushPromises()
  await wrapper.get('form.admin-filters').trigger('submit')
  await flushPromises()
}
it('保留审计记录入口，月费用只查询不生成发票，精确金额和NULL合计保持原义', async () => {
  const page = await open()
  expect(mocks.records).toHaveBeenCalledTimes(1)
  expect(mocks.monthly).toHaveBeenCalledTimes(1)
  expect(mocks.monthly.mock.calls[0]?.[0]).toEqual({
    month: formatMonth(new Date()),
    page: 1,
    page_size: 20
  })
  await query('2026-10')
  expect(mocks.monthly).toHaveBeenCalledWith(
    { month: '2026-10', page: 1, page_size: 20 },
    { signal: expect.any(AbortSignal) }
  )
  expect(page.text()).toContain('0.001440000000')
  expect(page.text()).toContain('不代表完整费用')
  expect(page.text()).toContain('计费事件')
  expect(page.text()).toContain('请求汇总')
  expect(page.text()).toContain('对账配置')
  expect(page.text()).not.toContain('生成账单')
  expect(page.text()).not.toContain('机构应收')
})
it('月份编辑未查询时分页使用原范围，更改条数回第一页', async () => {
  const page = await open()
  await query('2026-10')
  page.getComponent(ElDatePicker).vm.$emit('update:modelValue', '2026-11')
  page.getComponent(ElPagination).vm.$emit('current-change', 2)
  await flushPromises()
  expect(mocks.monthly.mock.lastCall?.[0]).toEqual({ month: '2026-10', page: 2, page_size: 20 })
  page.getComponent(ElPagination).vm.$emit('size-change', 50)
  await flushPromises()
  expect(mocks.monthly.mock.lastCall?.[0]).toEqual({ month: '2026-10', page: 1, page_size: 50 })
})
it('切换标签取消首次读取后，返回时恢复已确认月份而非未查询输入', async () => {
  mocks.monthly.mockImplementationOnce(() => new Promise(() => {}))
  const page = await open()
  const original = mocks.monthly.mock.calls[0]!
  page
    .getComponent(ElDatePicker)
    .vm.$emit('update:modelValue', original[0].month === '2026-11' ? '2026-10' : '2026-11')
  await page
    .findAll('[role="tab"]')
    .find((tab) => tab.text() === '计费事件')!
    .trigger('click')
  await page
    .findAll('[role="tab"]')
    .find((tab) => tab.text() === '模型月账单')!
    .trigger('click')
  await flushPromises()
  expect(original[1].signal.aborted).toBe(true)
  expect(mocks.monthly).toHaveBeenCalledTimes(2)
  expect(mocks.monthly.mock.lastCall?.[0]).toEqual(original[0])
  expect(page.text()).toContain('月份已修改，查询后生效')
})
it('新月份后到的旧响应被丢弃，旧AbortSignal取消', async () => {
  const page = await open()
  let finish!: (value: ModelBillingMonthly) => void
  mocks.monthly.mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        finish = resolve
      })
  )
  await query('2026-09')
  await query('2026-10')
  finish({ ...result, month: '2026-09', known_amount: '999' })
  await flushPromises()
  expect(page.getComponent(ModelBillingDetails).props('monthly').month).toBe('2026-10')
  expect(page.getComponent(ModelBillingDetails).props('monthly').known_amount).toBe(
    '0.001440000000'
  )
  expect(mocks.monthly.mock.calls.at(-2)?.[1].signal.aborted).toBe(true)
})
it('清空月份不会请求，也不将未配置数据展示为零费用', async () => {
  await open()
  mocks.monthly.mockClear()
  await query('')
  expect(mocks.monthly).not.toHaveBeenCalled()
})
