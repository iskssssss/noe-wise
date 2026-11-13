import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { ElButton, ElDatePicker, ElSelect } from 'element-plus'
import BillingRecordsPage from './BillingRecordsPage.vue'
const mocks = vi.hoisted(() => ({ records: vi.fn(), filterOptions: vi.fn() }))
vi.mock('../../api', () => ({
  api: { billing: { records: mocks.records, filterOptions: mocks.filterOptions } }
}))
vi.mock('vue-router', () => ({ onBeforeRouteLeave: vi.fn(), onBeforeRouteUpdate: vi.fn() }))
let wrapper: ReturnType<typeof mount>
beforeEach(() => {
  mocks.records.mockReset()
  mocks.filterOptions.mockResolvedValue({ eventTypes: [], models: [] })
})
afterEach(() => {
  wrapper?.unmount()
  document.body.innerHTML = ''
})
describe('计费查询响应归属', () => {
  it('日期范围按北京时间自然日查询，保留原日历值且清除后不带范围', async () => {
    mocks.records.mockResolvedValue({ items: [], total: 0 })
    wrapper = mount(BillingRecordsPage, { attachTo: document.body })
    await flushPromises()
    const picker = wrapper.findComponent(ElDatePicker)
    expect(picker.props('type')).toBe('daterange')
    expect(picker.props('valueFormat')).toBe('YYYY-MM-DD')
    expect(picker.props('format')).toBe('YYYY-MM-DD')
    const selected = ['2026-10-11', '2026-11-01']
    picker.vm.$emit('update:modelValue', selected)
    await flushPromises()
    expect(picker.props('modelValue')).toEqual(selected)
    const query = wrapper.findAllComponents(ElButton).find((item) => item.text() === '查询')!
    await query.trigger('click')
    await flushPromises()
    expect(mocks.records).toHaveBeenLastCalledWith(
      expect.objectContaining({ from: '2026-10-11', to: '2026-11-01' })
    )
    picker.vm.$emit('update:modelValue', null)
    await flushPromises()
    await query.trigger('click')
    await flushPromises()
    expect(mocks.records).toHaveBeenLastCalledWith(
      expect.objectContaining({ from: undefined, to: undefined })
    )
  })
  it('旧查询迟到不覆盖新查询结果及总数', async () => {
    let first!: (value: unknown) => void
    mocks.records
      .mockReturnValueOnce(
        new Promise((resolve) => {
          first = resolve
        })
      )
      .mockResolvedValueOnce({ items: [{ model: 'new', event_type: '新结果' }], total: 2 })
    wrapper = mount(BillingRecordsPage, { attachTo: document.body })
    await flushPromises()
    const field = wrapper.findAllComponents(ElSelect)[0]!
    field.vm.$emit('update:modelValue', 'new')
    await flushPromises()
    await wrapper
      .findAllComponents(ElButton)
      .find((item) => item.text() === '查询')!
      .trigger('click')
    await flushPromises()
    expect(wrapper.findComponent({ name: 'ElTable' }).props('data')).toEqual([
      { model: 'new', event_type: '新结果' }
    ])
    first({ items: [{ model: 'old' }], total: 99 })
    await flushPromises()
    expect(wrapper.findComponent({ name: 'ElTable' }).props('data')).toEqual([
      { model: 'new', event_type: '新结果' }
    ])
    expect(wrapper.findComponent({ name: 'ElPagination' }).props('total')).toBe(2)
  })
})
