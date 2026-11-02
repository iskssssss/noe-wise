import { afterEach, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import BrowserConnectionCard from './BrowserConnectionCard.vue'
import type { Client } from '../client'
afterEach(() => {
  sessionStorage.clear()
  vi.useRealTimers()
})
it('连接卡按服务状态读取且取消等待不提交tool_input，身份切换中止旧轮询', async () => {
  vi.useFakeTimers()
  const json = vi.fn().mockResolvedValue({
    connection_request_id: 'request',
    status: 'WAITING',
    reason: 'OFFLINE',
    device_id: 'original',
    make_default: false,
    devices: { items: [], installation_url: 'https://example.com/install' }
  })
  const pending = {
    execution_id: 'run',
    run_revision: 1,
    checkpoint_revision: 1,
    interaction_type: 'tool_input',
    card: { type: 'browser_connection', connection_request_id: 'request', title: '连接浏览器' }
  }
  const client = { json } as unknown as Client
  const wrapper = mount(BrowserConnectionCard, { props: { client, clientRef: 'owner', pending } })
  await flushPromises()
  expect(wrapper.text()).toContain('等待浏览器连接')
  expect(wrapper.text()).toContain('恢复 Remote 连接')
  expect(wrapper.text()).toContain('无需再次配对')
  expect(wrapper.text()).not.toContain('OFFLINE')
  expect(wrapper.find('input[type=checkbox]').element).toHaveProperty('checked', true)
  await wrapper.setProps({ disabled: true })
  expect(
    wrapper
      .findAll('button')
      .find((b) => b.text() === '取消连接等待')!
      .attributes('disabled')
  ).toBeDefined()
  await wrapper
    .findAll('button')
    .find((b) => b.text() === '取消连接等待')!
    .trigger('click')
  expect(json.mock.calls.filter((call) => call[1] === 'POST')).toHaveLength(0)
  await wrapper.setProps({ disabled: false })
  await wrapper
    .findAll('button')
    .find((b) => b.text() === '取消连接等待')!
    .trigger('click')
  await flushPromises()
  expect(json).toHaveBeenCalledWith(
    '/chat/browser/connection-requests/cancel',
    'POST',
    { client_ref: 'owner', connection_request_id: 'request' },
    expect.any(AbortSignal)
  )
  expect(wrapper.emitted('submit')).toBeUndefined()
  const signal = json.mock.calls[0]![3] as AbortSignal
  await wrapper.setProps({ clientRef: 'other' })
  await flushPromises()
  expect(signal.aborted).toBe(true)
  wrapper.unmount()
  const reads = json.mock.calls.length
  await vi.advanceTimersByTimeAsync(4000)
  expect(json).toHaveBeenCalledTimes(reads)
})
