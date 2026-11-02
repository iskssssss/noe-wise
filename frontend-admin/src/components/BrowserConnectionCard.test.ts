import { afterEach, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import BrowserConnectionCard from './BrowserConnectionCard.vue'
const mocks = vi.hoisted(() => ({
  detail: vi.fn(),
  pair: vi.fn(),
  select: vi.fn(),
  cancel: vi.fn()
}))
vi.mock('../api', () => ({
  api: {
    chat: {
      browserConnectionDetail: mocks.detail,
      browserConnectionPair: mocks.pair,
      browserConnectionSelect: mocks.select,
      browserConnectionCancel: mocks.cancel
    }
  }
}))
afterEach(() => {
  sessionStorage.clear()
  vi.useRealTimers()
})
it('机构连接卡只以session读取和取消，服务端CANCELLING时隐藏操作', async () => {
  vi.useFakeTimers()
  const value = {
    connection_request_id: 'request',
    status: 'WAITING',
    reason: 'OFFLINE',
    device_id: 'original',
    make_default: false,
    devices: {
      items: [],
      selected_browser_id: 'old',
      installation_url: 'https://example.com/install'
    }
  }
  mocks.detail.mockResolvedValue(value)
  mocks.cancel.mockResolvedValue({})
  const wrapper = mount(BrowserConnectionCard, {
    props: { session: 'session', request: 'request' },
    global: {
      stubs: {
        ElCard: { template: '<section><slot/></section>' },
        ElButton: {
          props: ['disabled'],
          template: '<button :disabled="disabled"><slot/></button>'
        },
        ElCheckbox: {
          props: ['modelValue'],
          template: '<input type="checkbox" :checked="modelValue" />'
        },
        ElAlert: true
      }
    }
  })
  await flushPromises()
  expect(wrapper.text()).toContain('恢复 Remote 连接')
  expect(wrapper.text()).toContain('无需再次配对')
  expect(wrapper.text()).not.toContain('OFFLINE')
  expect(mocks.detail).toHaveBeenCalledWith(
    'request',
    { session_id: 'session' },
    expect.any(AbortSignal)
  )
  expect(wrapper.get('input').element).toHaveProperty('checked', false)
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '取消连接等待')!
    .trigger('click')
  await flushPromises()
  expect(mocks.cancel).toHaveBeenCalledWith(
    { session_id: 'session', connection_request_id: 'request' },
    expect.any(AbortSignal)
  )
  expect(wrapper.emitted('submit')).toBeUndefined()
  mocks.detail.mockResolvedValue({ ...value, status: 'CANCELLING' })
  await vi.advanceTimersByTimeAsync(2000)
  await flushPromises()
  expect(wrapper.text()).toContain('正在取消连接等待')
  expect(wrapper.find('button').exists()).toBe(false)
  wrapper.unmount()
})
