import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import MessageBody from './MessageBody.vue'

const createdAt = '2026-10-11T00:20:03+08:00'
let wrapper: ReturnType<typeof mount> | undefined
afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  vi.useRealTimers()
})

/** 使用受控时钟验证停留阈值，不依赖执行机器的计时速度。 */
function body() {
  vi.useFakeTimers()
  wrapper = mount(MessageBody, { props: { createdAt }, slots: { default: '正文' } })
  return wrapper
}

describe('消息时间的弱提示', () => {
  it('快速划过不会出现时间，重新进入需要重新完整停留', async () => {
    const view = body()
    await view.trigger('pointerenter', { pointerType: 'mouse' })
    await vi.advanceTimersByTimeAsync(400)
    await view.trigger('pointerleave')
    await vi.advanceTimersByTimeAsync(1000)
    expect(view.get('time').classes()).not.toContain('is-visible')
    await view.trigger('pointerenter', { pointerType: 'mouse' })
    await vi.advanceTimersByTimeAsync(699)
    expect(view.get('time').classes()).not.toContain('is-visible')
    await vi.advanceTimersByTimeAsync(1)
    expect(view.get('time').classes()).toContain('is-visible')
  })

  it('按下鼠标选字会撤销提示，也不因随后的鼠标聚焦立即打开', async () => {
    const view = body()
    await view.trigger('pointerenter', { pointerType: 'mouse' })
    await view.trigger('pointerdown')
    await view.trigger('focus')
    await vi.advanceTimersByTimeAsync(1000)
    expect(view.get('time').classes()).not.toContain('is-visible')
    expect(view.attributes('aria-describedby')).toBeUndefined()
  })

  it('键盘聚焦立即可读，Escape 隐藏后鼠标移动不会重新打开', async () => {
    const view = body()
    await view.trigger('focus')
    expect(view.get('time').classes()).toContain('is-visible')
    expect(view.get('time').attributes('aria-hidden')).toBe('false')
    expect(view.attributes('aria-describedby')).toBe(view.get('time').attributes('id'))
    await view.trigger('keydown', { key: 'Escape' })
    await view.trigger('pointermove', { pointerType: 'mouse' })
    await vi.advanceTimersByTimeAsync(1000)
    expect(view.get('time').classes()).not.toContain('is-visible')
    await view.trigger('focus')
    await view.trigger('blur')
    expect(view.get('time').classes()).not.toContain('is-visible')
  })

  it('触屏轻触不产生悬停提示', async () => {
    const view = body()
    await view.trigger('pointerenter', { pointerType: 'touch' })
    await view.trigger('pointerdown', { pointerType: 'touch' })
    await view.trigger('focus')
    await vi.advanceTimersByTimeAsync(1000)
    expect(view.get('time').classes()).not.toContain('is-visible')
  })

  it.each(['scroll', 'resize'])('%s 撤销等待中的提示并关闭已显示的提示', async (event) => {
    const view = body()
    await view.trigger('pointerenter', { pointerType: 'mouse' })
    window.dispatchEvent(new Event(event))
    await vi.advanceTimersByTimeAsync(1000)
    expect(view.get('time').classes()).not.toContain('is-visible')
    await view.trigger('focus')
    expect(view.get('time').classes()).toContain('is-visible')
    window.dispatchEvent(new Event(event))
    await view.vm.$nextTick()
    expect(view.get('time').classes()).not.toContain('is-visible')
  })

  it('时间变化撤销旧的等待任务，卸载不会留下计时器', async () => {
    const view = body()
    await view.trigger('pointerenter', { pointerType: 'mouse' })
    await view.setProps({ createdAt: undefined })
    await vi.advanceTimersByTimeAsync(1000)
    expect(view.find('time').exists()).toBe(false)
    expect(view.attributes('tabindex')).toBeUndefined()
    await view.setProps({ createdAt })
    await view.trigger('pointerenter', { pointerType: 'mouse' })
    view.unmount()
    wrapper = undefined
    expect(vi.getTimerCount()).toBe(0)
  })
})
