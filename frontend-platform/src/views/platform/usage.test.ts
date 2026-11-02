import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { ElDatePicker, ElFormItem, ElInput } from 'element-plus'
import UsagePanel from '@skillforge/shared/integration/UsagePanel.vue'
import { HttpClientError } from '@skillforge/shared/utils/http'
const wrappers: VueWrapper[] = []
afterEach(() => {
  wrappers.forEach((value) => value.unmount())
  wrappers.length = 0
  document.body.innerHTML = ''
})
const summary = {
  request_count: '9223372036854775807',
  invocation_count: 8,
  prompt_tokens: null,
  completion_tokens: 3,
  unknown_usage_count: 1,
  unresolved_cost_count: 1,
  costs: [{ amount_cents: '9223372036854775807.125', currency: 'CNY' }]
}
const event = {
  event_id: 'event-1',
  tenant_id: 'tenant-1',
  agent_id: 'agent-1',
  kind: 'confirmation',
  event_type: 'MODEL',
  tokens: null,
  quantity: 1,
  amount_cents: '9223372036854775807.125',
  currency: 'CNY',
  amount_status: 'confirmed',
  occurred_at: '2026-10-11T01:00:00Z'
}
function button(wrapper: VueWrapper, text: string) {
  const value = wrapper.findAll('button').find((item) => item.text() === text)
  if (!value) throw Error(text)
  return value
}
describe('平台用量查询与游标', () => {
  it('读取权限分项控制请求，未授权明细不发送；权限收窄隔离迟到结果', async () => {
    let complete!: (value: unknown) => void
    const api = {
      summary: vi.fn().mockResolvedValue(summary),
      events: vi.fn(),
      details: vi.fn().mockImplementation(
        () =>
          new Promise((resolve) => {
            complete = resolve
          })
      )
    }
    const wrapper = mount(UsagePanel, {
      props: { api, canSummary: true, canDetails: false, canEvents: false }
    })
    wrappers.push(wrapper)
    await flushPromises()
    expect(api.summary).toHaveBeenCalledTimes(1)
    expect(api.details).not.toHaveBeenCalled()
    expect(api.events).not.toHaveBeenCalled()
    await wrapper.setProps({ canDetails: true })
    await flushPromises()
    expect(api.details).toHaveBeenCalledTimes(1)
    await wrapper.setProps({ canDetails: false })
    await flushPromises()
    complete({ items: [event], next_cursor: 'opaque', has_more: true })
    await flushPromises()
    expect(wrapper.text()).not.toContain('event-1')
  })
  it('游标授权冲突后必须显式清空，不允许沿用旧游标重试', async () => {
    const api = {
      summary: vi.fn().mockResolvedValue(summary),
      events: vi.fn(),
      details: vi
        .fn()
        .mockResolvedValueOnce({ items: [event], next_cursor: 'opaque:signature', has_more: true })
        .mockRejectedValueOnce(new HttpClientError({ status: 409, userMessage: '游标授权已变化' }))
        .mockResolvedValue({ items: [], next_cursor: null, has_more: false })
    }
    const wrapper = mount(UsagePanel, { props: { api } })
    wrappers.push(wrapper)
    await flushPromises()
    await button(wrapper, '下一页').trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('游标授权已变化')
    expect(wrapper.findAll('button').some((item) => item.text() === '重试明细')).toBe(false)
    await button(wrapper, '清空游标并重新查询').trigger('click')
    await flushPromises()
    expect(api.details.mock.lastCall?.[0]).toMatchObject({ cursor: '' })
  })
  it('清空时间范围后查询省略时间限制，不附带任何应用身份', async () => {
    const api = {
      events: vi.fn(),
      summary: vi.fn().mockResolvedValue(summary),
      details: vi.fn().mockResolvedValue({ items: [], next_cursor: '', has_more: false })
    }
    const wrapper = mount(UsagePanel, { props: { api, identity: 'actor-1' } })
    wrappers.push(wrapper)
    await flushPromises()
    for (const input of wrapper.getComponent(ElDatePicker).findAll('input'))
      expect(input.attributes('aria-describedby')).toBeTruthy()
    wrapper.getComponent(ElDatePicker).vm.$emit('update:modelValue', null)
    await flushPromises()
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(api.summary.mock.lastCall?.[0]).toMatchObject({
      from: undefined,
      to: undefined
    })
    expect(api.summary).toHaveBeenCalledTimes(2)
  })
  it('金额和大计量原样展示；翻页沿用已提交查询，不采用未查询输入', async () => {
    const api = {
      events: vi.fn(),
      summary: vi.fn().mockResolvedValue(summary),
      details: vi.fn().mockResolvedValue({ items: [event], next_cursor: '42', has_more: true })
    }
    const wrapper = mount(UsagePanel, {
      props: { api, identity: 'actor-1', showTenant: true }
    })
    wrappers.push(wrapper)
    await flushPromises()
    expect(wrapper.text()).toContain('9223372036854775807.125')
    expect(wrapper.text()).toContain('9223372036854775807')
    const field = wrapper
      .findAllComponents(ElFormItem)
      .find((item) => item.props('label') === '机构标识')!
    await field.getComponent(ElInput).get('input').setValue('unsubmitted-tenant')
    await button(wrapper, '下一页').trigger('click')
    await flushPromises()
    expect(api.details.mock.lastCall?.[0]).toMatchObject({
      cursor: '42',
      tenant_id: ''
    })
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(api.details.mock.lastCall?.[0]).toMatchObject({
      cursor: '',
      tenant_id: 'unsubmitted-tenant'
    })
  })
  it('当前身份变化隔离迟到回包，失败区域可以独立重读', async () => {
    let complete!: (value: unknown) => void
    const api = {
      events: vi.fn(),
      summary: vi
        .fn()
        .mockImplementationOnce(
          () =>
            new Promise((resolve) => {
              complete = resolve
            })
        )
        .mockResolvedValue(summary),
      details: vi
        .fn()
        .mockRejectedValueOnce(new Error('明细不可用'))
        .mockResolvedValue({ items: [], next_cursor: '', has_more: false })
    }
    const wrapper = mount(UsagePanel, { props: { api, identity: 'actor-A' } })
    wrappers.push(wrapper)
    await flushPromises()
    expect(document.body.textContent).toContain('明细不可用')
    await button(wrapper, '重试明细').trigger('click')
    await flushPromises()
    await wrapper.setProps({ identity: 'actor-B' })
    await flushPromises()
    complete({ ...summary, request_count: 'OLD' })
    await flushPromises()
    expect(wrapper.text()).not.toContain('OLD')
    expect(api.details.mock.lastCall?.[0]).toMatchObject({ cursor: '' })
    expect(api.summary).toHaveBeenCalledTimes(2)
  })
})
