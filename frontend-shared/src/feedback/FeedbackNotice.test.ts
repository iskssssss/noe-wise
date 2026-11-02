import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { h } from 'vue'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { clearFeedback, type FeedbackType } from './index'
import FeedbackNotice from './FeedbackNotice.vue'

const wrappers: VueWrapper[] = []
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  clearFeedback()
  vi.useRealTimers()
  document.body.innerHTML = ''
})
function notice(title = '读取失败', slots = {}, type: FeedbackType = 'error') {
  const wrapper = mount(FeedbackNotice, { props: { title, type }, slots })
  wrappers.push(wrapper)
  return wrapper
}

describe('页面反馈统一使用顶部 Message', () => {
  it('页面与抽屉的相同错误只展示一次，任一来源仍存在时保留消息', async () => {
    const page = notice()
    const drawer = notice()
    await flushPromises()
    expect(document.querySelectorAll('.el-message')).toHaveLength(1)
    expect(page.find('.el-alert').exists()).toBe(false)
    drawer.unmount()
    await flushPromises()
    expect(document.querySelector('.el-message')?.textContent).toContain('读取失败')
    page.unmount()
    await flushPromises()
    expect(document.querySelector('.el-message')).toBeNull()
  })
  it('错误更新替换旧提示，清空后关闭，不留下旧流程的提示', async () => {
    const wrapper = notice()
    await wrapper.setProps({ title: '新的失败原因', description: '已有内容仍保留' })
    await flushPromises()
    expect(document.querySelectorAll('.el-message')).toHaveLength(1)
    expect(document.querySelector('.el-message')?.textContent).toContain(
      '新的失败原因。已有内容仍保留'
    )
    await wrapper.setProps({ title: '', description: '' })
    await flushPromises()
    expect(document.querySelector('.el-message')).toBeNull()
  })
  it('顶部保留可用的重试操作，显示消息本身不触发请求', async () => {
    const retry = vi.fn()
    notice('读取失败', { default: () => h('button', { onClick: retry }, '重新加载') })
    await flushPromises()
    expect(retry).not.toHaveBeenCalled()
    const button = document.querySelector<HTMLButtonElement>('.el-message button')!
    expect(button.textContent).toBe('重新加载')
    button.click()
    expect(retry).toHaveBeenCalledOnce()
  })
  it('手动关闭通知所有来源，替换内容不会清空新的错误', async () => {
    const first = notice()
    const second = notice()
    await flushPromises()
    document.querySelector<HTMLElement>('.el-message__closeBtn')!.click()
    await flushPromises()
    expect(first.emitted('close')).toHaveLength(1)
    expect(second.emitted('close')).toHaveLength(1)
    await first.setProps({ title: '新的失败原因' })
    await first.setProps({ title: '另一条失败原因' })
    await flushPromises()
    expect(first.emitted('close')).toHaveLength(1)
    expect(document.querySelector('.el-message')?.textContent).toContain('另一条失败原因')
  })
  it('同文案保留各对象的恢复动作，来源离开后只移除其自身动作', async () => {
    const firstRetry = vi.fn(),
      secondRetry = vi.fn()
    const first = notice('截图读取失败', {
      default: () => h('button', { onClick: firstRetry }, '重试第一张截图')
    })
    notice('截图读取失败', {
      default: () => h('button', { onClick: secondRetry }, '重试第二张截图')
    })
    await flushPromises()
    expect(document.querySelectorAll('.el-message')).toHaveLength(1)
    expect(document.querySelectorAll('.el-message button')).toHaveLength(2)
    first.unmount()
    await flushPromises()
    const buttons = document.querySelectorAll<HTMLButtonElement>('.el-message button')
    expect(buttons).toHaveLength(1)
    expect(buttons[0]!.textContent).toBe('重试第二张截图')
    buttons[0]!.click()
    expect(firstRetry).not.toHaveBeenCalled()
    expect(secondRetry).toHaveBeenCalledOnce()
  })
  it('warning 中的恢复动作不会在四秒后消失', async () => {
    vi.useFakeTimers()
    const retry = vi.fn()
    notice(
      '截图读取失败',
      { default: () => h('button', { onClick: retry }, '重新加载') },
      'warning'
    )
    await vi.advanceTimersByTimeAsync(4100)
    expect(document.querySelectorAll('.el-message')).toHaveLength(1)
    document.querySelector<HTMLButtonElement>('.el-message button')!.click()
    expect(retry).toHaveBeenCalledOnce()
  })
  it('后来加入的恢复动作取消原自动关闭，动作来源离开后普通消息恢复自动关闭', async () => {
    vi.useFakeTimers()
    notice('读取未完成', {}, 'info')
    await vi.advanceTimersByTimeAsync(3000)
    const action = notice('读取未完成', { default: () => h('button', {}, '重新读取') }, 'info')
    await vi.advanceTimersByTimeAsync(4100)
    expect(document.querySelectorAll('.el-message')).toHaveLength(1)
    expect(document.querySelector('.el-message button')?.textContent).toBe('重新读取')
    action.unmount()
    await vi.advanceTimersByTimeAsync(4100)
    expect(document.querySelector('.el-message')).toBeNull()
  })
})
