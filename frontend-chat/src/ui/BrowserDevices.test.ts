import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import BrowserDevices from './BrowserDevices.vue'
import type { Client } from '../client'
const bridge = vi.hoisted(() => vi.fn())
vi.mock('./browserConnection', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./browserConnection')>()),
  browserBridge: bridge
}))
beforeEach(() => {
  sessionStorage.clear()
  bridge.mockReset()
})
afterEach(() => sessionStorage.clear())
function fixture() {
  const json = vi.fn(async (path: string) =>
    path.startsWith('/chat/browser/devices')
      ? { items: [], installation_url: '' }
      : path.startsWith('/chat/browser/tasks')
        ? []
        : { pairing_url: 'wss://example.com/pair#synthetic' }
  )
  const wrapper = mount(BrowserDevices, {
    props: {
      modelValue: true,
      client: { json, base: 'https://example.com' } as unknown as Client,
      clientRef: 'owner'
    }
  })
  return { wrapper, json }
}
it('未信任时提示插件信任，不创建独立配对地址', async () => {
  bridge.mockResolvedValue({ ok: false, code: 'site_not_trusted' })
  const { wrapper, json } = fixture()
  await flushPromises()
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '配对当前浏览器')!
    .trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('信任当前站点')
  expect(json.mock.calls.some((call) => call[0] === '/chat/browser/pair')).toBe(false)
  wrapper.unmount()
})
it('设置页自动交给适配扩展，无连接请求身份且不显示或复制配对地址', async () => {
  bridge.mockResolvedValue({ ok: true })
  const { wrapper, json } = fixture()
  await flushPromises()
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '配对当前浏览器')!
    .trigger('click')
  await flushPromises()
  expect(json).toHaveBeenCalledWith(
    '/chat/browser/pair',
    'POST',
    { client_ref: 'owner' },
    expect.any(AbortSignal)
  )
  expect(bridge).toHaveBeenLastCalledWith(
    'pair',
    { pairing_url: 'wss://example.com/pair#synthetic', identity_scope: 'owner' },
    expect.any(AbortSignal)
  )
  expect(wrapper.find('textarea').exists()).toBe(false)
  expect(document.querySelector('.chat-toast')?.textContent).toContain('插件已确认配对')
  expect(wrapper.findAll('button').some((button) => button.text() === '手动官方扩展配对')).toBe(
    true
  )
  wrapper.unmount()
})
it('未知结果关闭后保留，不重发；切换身份中止扩展观察', async () => {
  bridge
    .mockResolvedValueOnce({ ok: true })
    .mockImplementationOnce(
      (_operation, _payload, signal: AbortSignal) =>
        new Promise((_resolve, reject) =>
          signal.addEventListener('abort', () =>
            reject(new DOMException('已停止观察', 'AbortError'))
          )
        )
    )
  const { wrapper, json } = fixture()
  await flushPromises()
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '配对当前浏览器')!
    .trigger('click')
  await flushPromises()
  const signal = bridge.mock.calls[1]![2] as AbortSignal
  await wrapper.setProps({ modelValue: false })
  await flushPromises()
  expect(signal.aborted).toBe(true)
  await wrapper.setProps({ modelValue: true })
  await flushPromises()
  expect(
    wrapper
      .findAll('button')
      .find((button) => button.text() === '配对当前浏览器')!
      .attributes('disabled')
  ).toBeDefined()
  expect(json.mock.calls.filter((call) => call[0] === '/chat/browser/pair')).toHaveLength(1)
  await wrapper.setProps({ clientRef: 'other' })
  await flushPromises()
  expect(
    wrapper
      .findAll('button')
      .find((button) => button.text() === '配对当前浏览器')!
      .attributes('disabled')
  ).toBeUndefined()
  wrapper.unmount()
})
