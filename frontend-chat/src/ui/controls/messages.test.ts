import { afterEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { defineComponent, h, ref } from 'vue'
import { ChatMessage, ChatMessageNotice } from './overlays'

afterEach(() => {
  ChatMessage.closeAll()
  vi.useRealTimers()
})

describe('会话端顶部 Message', () => {
  it('相同错误只展示一次，任一来源离开不清除另一来源的消息', () => {
    const first = mount(ChatMessageNotice, {
      props: { title: '保存失败，请稍后检查状态', type: 'error' }
    })
    const second = mount(ChatMessageNotice, {
      props: { title: '保存失败，请稍后检查状态', type: 'error' }
    })
    expect(document.querySelectorAll('.chat-toast')).toHaveLength(1)
    expect(first.find('[role="alert"]').exists()).toBe(false)
    first.unmount()
    expect(document.querySelectorAll('.chat-toast')).toHaveLength(1)
    second.unmount()
    expect(document.querySelector('.chat-toast')).toBeNull()
  })
  it('状态清空关闭旧错误，新错误只产生一条消息并保留重试按钮', async () => {
    const title = ref('读取失败')
    const retry = vi.fn()
    const wrapper = mount(
      defineComponent({
        setup: () => () =>
          h(
            ChatMessageNotice,
            { title: title.value, type: 'error' },
            { default: () => h('button', { onClick: retry }, '重新读取') }
          )
      })
    )
    ;(document.querySelector('.chat-toast button') as HTMLButtonElement).click()
    expect(retry).toHaveBeenCalledOnce()
    title.value = '连接中断'
    await wrapper.vm.$nextTick()
    expect(document.querySelectorAll('.chat-toast')).toHaveLength(1)
    expect(document.querySelector('.chat-toast')?.textContent).toContain('连接中断')
    title.value = ''
    await wrapper.vm.$nextTick()
    expect(document.querySelector('.chat-toast')).toBeNull()
    wrapper.unmount()
  })
  it('原生模态框内的 Message 保持可交互，模态关闭后交由页面继续展示', () => {
    const dialog = document.createElement('dialog')
    dialog.setAttribute('open', '')
    document.body.append(dialog)
    ChatMessage.error('操作尚未确认')
    expect(dialog.querySelector('.chat-toast')).not.toBeNull()
    dialog.removeAttribute('open')
    dialog.dispatchEvent(new Event('close'))
    expect(dialog.querySelector('.chat-toast')).toBeNull()
    expect(document.body.querySelector('.chat-toast')).not.toBeNull()
    dialog.remove()
  })
  it('dialog 切换模态重新打开后恢复原消息 popover，后续真正关闭仍交给页面', () => {
    const dialog = document.createElement('dialog')
    dialog.setAttribute('open', '')
    document.body.append(dialog)
    const retry = vi.fn()
    const wrapper = mount(ChatMessageNotice, {
      props: { title: '详情读取失败', type: 'error' },
      slots: { default: () => h('button', { onClick: retry }, '重新读取') }
    })
    const host = dialog.querySelector('.chat-toast') as HTMLElement
    const showPopover = vi.fn()
    host.showPopover = showPopover
    const hidePopover = vi.fn()
    host.hidePopover = hidePopover
    for (let index = 0; index < 2; index++) {
      dialog.removeAttribute('open')
      dialog.setAttribute('open', '')
      dialog.dispatchEvent(new Event('close'))
      expect(dialog.querySelector('.chat-toast')).toBe(host)
      expect(showPopover).toHaveBeenCalledTimes(index + 1)
    }
    dialog.dispatchEvent(new Event('toggle'))
    expect(hidePopover).toHaveBeenCalledOnce()
    expect(showPopover).toHaveBeenCalledTimes(3)
    ;(host.querySelector('button') as HTMLButtonElement).click()
    expect(retry).toHaveBeenCalledOnce()
    dialog.removeAttribute('open')
    dialog.dispatchEvent(new Event('close'))
    expect(host.parentElement).toBe(document.body)
    expect(showPopover).toHaveBeenCalledTimes(4)
    wrapper.unmount()
    dialog.dispatchEvent(new Event('close'))
    dialog.dispatchEvent(new Event('toggle'))
    expect(showPopover).toHaveBeenCalledTimes(4)
    expect(host.isConnected).toBe(false)
    dialog.remove()
  })
  it('成功消息自动关闭，错误持续至用户关闭且不会在抽屉内重复', () => {
    vi.useFakeTimers()
    ChatMessage.success('已保存')
    ChatMessage.error('写入结果尚未确认')
    vi.advanceTimersByTime(4000)
    expect(document.querySelectorAll('.chat-toast')).toHaveLength(1)
    expect(document.querySelector('.chat-toast')?.textContent).toContain('写入结果尚未确认')
    ;(
      document.querySelector('.chat-toast button[aria-label="关闭提示"]') as HTMLButtonElement
    ).click()
    expect(document.querySelector('.chat-toast')).toBeNull()
  })
})
