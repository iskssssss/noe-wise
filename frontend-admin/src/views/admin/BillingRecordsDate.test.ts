import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { ElDatePicker, ElPagination, ElInput } from 'element-plus'
import BillingRecordsPage from './BillingRecordsPage.vue'

const mocks = vi.hoisted(() => ({ records: vi.fn(), requests: vi.fn() }))
vi.mock('../../api', () => ({ api: { billing: mocks } }))
vi.mock('@skillforge/shared/feedback', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@skillforge/shared/feedback')>()),
  confirm: vi.fn(),
  message: { success: vi.fn() }
}))
vi.mock('vue-router', () => ({ onBeforeRouteLeave: vi.fn(), onBeforeRouteUpdate: vi.fn() }))
const wrappers: VueWrapper[] = []
beforeEach(() => {
  vi.resetAllMocks()
  mocks.records.mockResolvedValue({ items: [], total: 41 })
  mocks.requests.mockResolvedValue({ items: [], total: 41 })
})
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  document.body.innerHTML = ''
})

async function page(kind: 'records' | 'requests') {
  const wrapper = mount(BillingRecordsPage, { attachTo: document.body })
  wrappers.push(wrapper)
  await flushPromises()
  if (kind === 'requests') {
    await wrapper
      .findAll('[role="tab"]')
      .find((tab) => tab.text() === '请求汇总')!
      .trigger('click')
    await flushPromises()
  }
  return wrapper
}

describe('账单自然日期查询契约', () => {
  it('修改模型或日期但未查询时，翻页保留上次查询条件', async () => {
    const wrapper = await page('records')
    wrapper.getComponent(ElDatePicker).vm.$emit('update:modelValue', ['2026-10-01', '2026-10-12'])
    wrapper.getComponent(ElInput).vm.$emit('update:modelValue', 'new-model')
    wrapper.getComponent(ElPagination).vm.$emit('current-change', 2)
    await flushPromises()
    expect(mocks.records).toHaveBeenLastCalledWith(
      expect.objectContaining({ from: undefined, to: undefined, model: '', page: 2 })
    )
    await wrapper
      .findAll('button')
      .find((item) => item.text() === '查询')!
      .trigger('click')
    await flushPromises()
    expect(mocks.records).toHaveBeenLastCalledWith(
      expect.objectContaining({ from: '2026-10-01', to: '2026-10-12', model: 'new-model', page: 1 })
    )
  })
  it.each(['records', 'requests'] as const)(
    '%s 真实日期输入提交同一天范围，清除与分页仍保持查询和帮助',
    async (kind) => {
      const wrapper = await page(kind)
      const picker = wrapper.findComponent(ElDatePicker)
      expect(picker.props('type')).toBe('daterange')
      expect(picker.props('valueFormat')).toBe('YYYY-MM-DD')
      const query = mocks[kind]
      const calls = query.mock.calls.length
      const dates = wrapper.findAll<HTMLInputElement>('.el-range-input')
      // 与真实操作一致，先打开懒加载日期面板，再让原控件解析输入；不直接补造 modelValue。
      dates[0]!.element.focus()
      await dates[0]!.trigger('click')
      await flushPromises()
      await dates[0]!.setValue('2026-10-12')
      await dates[1]!.setValue('2026-10-12')
      await dates[1]!.trigger('keydown', { key: 'Enter', code: 'Enter' })
      await flushPromises()
      expect(picker.props('modelValue')).toEqual(['2026-10-12', '2026-10-12'])
      expect(query).toHaveBeenCalledTimes(calls)
      await wrapper
        .findAll('button')
        .find((button) => button.text() === '查询')!
        .trigger('click')
      await flushPromises()
      expect(query).toHaveBeenLastCalledWith(
        expect.objectContaining({ from: '2026-10-12', to: '2026-10-12', page: 1 })
      )
      const pagination = wrapper.findComponent(ElPagination)
      pagination.vm.$emit('current-change', 2)
      await flushPromises()
      expect(query).toHaveBeenLastCalledWith(
        expect.objectContaining({ from: '2026-10-12', to: '2026-10-12', page: 2 })
      )
      pagination.vm.$emit('size-change', 50)
      await flushPromises()
      expect(query).toHaveBeenLastCalledWith(
        expect.objectContaining({ from: '2026-10-12', to: '2026-10-12', page: 1, pageSize: 50 })
      )
      await wrapper.get('.el-date-editor').trigger('mouseenter')
      await wrapper.get('.el-range__close-icon').trigger('click')
      await flushPromises()
      await wrapper
        .findAll('button')
        .find((button) => button.text() === '查询')!
        .trigger('click')
      await flushPromises()
      expect(query).toHaveBeenLastCalledWith(
        expect.objectContaining({ from: undefined, to: undefined, page: 1, pageSize: 50 })
      )
      for (const control of wrapper.findAll('.el-range-input')) {
        const ids = (control.attributes('aria-describedby') ?? '').split(/\s+/).filter(Boolean)
        expect(ids).toHaveLength(1)
        const help = document.getElementById(ids[0]!)
        expect(help?.textContent).toContain('北京时间自然日期')
        expect(help?.textContent).toContain('起止日期的整日')
      }
    }
  )

  it.each([
    ['2024-02-29', '2024-03-01'],
    ['2026-12-31', '2027-01-01']
  ])('闰日或跨年日期 %s 至 %s 保留原日历精度', async (from, to) => {
    const wrapper = await page('records')
    const dates = wrapper.findAll<HTMLInputElement>('.el-range-input')
    dates[0]!.element.focus()
    await dates[0]!.trigger('click')
    await flushPromises()
    await dates[0]!.setValue(from)
    await dates[1]!.setValue(to)
    await dates[1]!.trigger('keydown', { key: 'Enter', code: 'Enter' })
    await flushPromises()
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '查询')!
      .trigger('click')
    await flushPromises()
    expect(mocks.records).toHaveBeenLastCalledWith(expect.objectContaining({ from, to }))
  })
})
