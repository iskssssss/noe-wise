import { afterEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import type { Client } from '../client'
import ConversationTools from './ConversationTools.vue'
import { ChatMessage } from './controls'

enableAutoUnmount(afterEach)
afterEach(() => ChatMessage.closeAll())

async function open(delivery: (path: string) => Promise<unknown>) {
  const json = vi.fn((path: string, _method?: string) =>
    path.startsWith('/chat/runs/delivery') ? delivery(path) : Promise.resolve({})
  )
  const client = { json } as unknown as Client
  const wrapper = mount(ConversationTools, {
    props: { modelValue: true, session: 's', run: 'A', clientRef: 'visitor', client },
    global: { stubs: { RunBudgetPanel: true } }
  })
  await flushPromises()
  await wrapper
    .findAll('[role="tab"]')
    .find((tab) => tab.text() === '运行')!
    .trigger('click')
  await flushPromises()
  const read = () => wrapper.findAll('button').find((button) => button.text() === '查看送达状态')!
  return { wrapper, json, client, read }
}

describe('运行送达状态归属', () => {
  it('切换运行清空旧观察，新运行只读成功才展示', async () => {
    const { wrapper, json, read } = await open(async (path) => ({
      runtime_status: path.includes('run_id=A') ? 'A已完成' : 'B运行中'
    }))
    await read().trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('A已完成')
    await wrapper.setProps({ run: 'B' })
    expect(wrapper.text()).not.toContain('A已完成')
    expect(wrapper.text()).not.toContain('送达状态不表示用户已读')
    await read().trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('B运行中')
    expect(
      json.mock.calls
        .filter(([path]) => path.startsWith('/chat/runs/delivery'))
        .map(([path]) => path)
    ).toEqual([
      '/chat/runs/delivery?session_id=s&run_id=A',
      '/chat/runs/delivery?session_id=s&run_id=B'
    ])
    expect(json.mock.calls.every(([, method]) => method === 'GET')).toBe(true)
  })
  for (const outcome of ['成功', '失败']) {
    it(`旧运行迟到${outcome}不发布状态或错误，也不释放新请求的忙碌状态`, async () => {
      let resolveA!: (value: unknown) => void,
        rejectA!: (error: Error) => void,
        resolveB!: (value: unknown) => void
      const pendingA = new Promise((resolve, reject) => {
        resolveA = resolve
        rejectA = reject
      })
      const pendingB = new Promise((resolve) => {
        resolveB = resolve
      })
      const { wrapper, read } = await open((path) =>
        path.includes('run_id=A') ? pendingA : pendingB
      )
      await read().trigger('click')
      await wrapper.setProps({ run: 'B' })
      expect(read().attributes('disabled')).toBeUndefined()
      await read().trigger('click')
      if (outcome === '成功') resolveA({ runtime_status: '过期A结果' })
      else rejectA(new Error('过期A失败'))
      await flushPromises()
      expect(wrapper.text()).not.toContain('过期A结果')
      expect(document.querySelector('.chat-toast')?.textContent ?? '').not.toContain('过期A失败')
      expect(read().attributes('disabled')).toBeDefined()
      expect(wrapper.text()).toContain('正在处理')
      resolveB({ runtime_status: '当前B结果' })
      await flushPromises()
      expect(wrapper.text()).toContain('当前B结果')
      expect(read().attributes('disabled')).toBeUndefined()
    })
  }
  it('当前读取失败保留查看入口，可再次读取，切换身份清除旧失败', async () => {
    const delivery = vi
      .fn()
      .mockRejectedValueOnce(new Error('送达暂不可用'))
      .mockResolvedValue({ runtime_status: '已读取' })
    const { wrapper, read } = await open(delivery)
    await read().trigger('click')
    await flushPromises()
    expect(document.querySelector('.chat-toast')?.textContent).toContain('送达暂不可用')
    expect(read().attributes('disabled')).toBeUndefined()
    await read().trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('已读取')
    await wrapper.setProps({ session: 'other-session' })
    expect(wrapper.text()).not.toContain('已读取')
    expect(document.querySelector('.chat-toast')?.textContent ?? '').not.toContain('送达暂不可用')
    // 会话切换的既有刷新结束后，再执行用户可用的送达读取。
    await flushPromises()
    expect(read().attributes('disabled')).toBeUndefined()
    await read().trigger('click')
    await flushPromises()
    expect(delivery.mock.calls.at(-1)?.[0]).toContain('session_id=other-session')
    await wrapper.setProps({ client: { json: vi.fn().mockResolvedValue({}) } as unknown as Client })
    expect(wrapper.text()).not.toContain('已读取')
  })
})
