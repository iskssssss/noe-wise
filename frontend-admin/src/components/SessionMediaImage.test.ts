import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import SessionMediaImage from './SessionMediaImage.vue'
import { clearFeedback } from '@skillforge/shared/feedback'
import { ref } from 'vue'

const mocks = vi.hoisted(() => ({ mediaContent: vi.fn() }))
vi.mock('../api', () => ({ api: { chat: { mediaContent: mocks.mediaContent } } }))
let wrapper: ReturnType<typeof mount> | undefined
beforeEach(() => {
  vi.resetAllMocks()
  vi.stubGlobal(
    'URL',
    class extends URL {
      static createObjectURL = vi
        .fn()
        .mockReturnValueOnce('blob:first')
        .mockReturnValueOnce('blob:second')
      static revokeObjectURL = vi.fn()
    }
  )
})
afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  clearFeedback()
  vi.unstubAllGlobals()
})

it('按回执媒体所属会话读图，图片更换与卸载均回收Blob地址', async () => {
  mocks.mediaContent.mockResolvedValue(new Blob(['image']))
  wrapper = mount(SessionMediaImage, { props: { session: 'image-session', media: 'first' } })
  await flushPromises()
  const firstSignal = mocks.mediaContent.mock.calls[0]![2] as AbortSignal
  expect(mocks.mediaContent).toHaveBeenCalledWith('image-session', 'first', firstSignal)
  expect(wrapper.get('img').attributes('src')).toBe('blob:first')
  await wrapper.setProps({ session: 'another-image-session', media: 'second' })
  await flushPromises()
  expect(firstSignal.aborted).toBe(true)
  expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:first')
  expect(mocks.mediaContent).toHaveBeenLastCalledWith(
    'another-image-session',
    'second',
    expect.any(AbortSignal)
  )
  expect(wrapper.get('img').attributes('src')).toBe('blob:second')
  wrapper.unmount()
  wrapper = undefined
  expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:second')
  expect((mocks.mediaContent.mock.calls[1]![2] as AbortSignal).aborted).toBe(true)
})

it('已中止的迟到图片不能创建对象地址或覆盖当前截图', async () => {
  let resolveOld!: (blob: Blob) => void
  mocks.mediaContent
    .mockImplementationOnce(
      () =>
        new Promise<Blob>((resolve) => {
          resolveOld = resolve
        })
    )
    .mockResolvedValueOnce(new Blob(['new']))
  wrapper = mount(SessionMediaImage, { props: { session: 'old', media: 'old-image' } })
  await wrapper.setProps({ session: 'current', media: 'current-image' })
  await flushPromises()
  resolveOld(new Blob(['old']))
  await flushPromises()
  expect(URL.createObjectURL).toHaveBeenCalledTimes(1)
  expect(wrapper.get('img').attributes('src')).toBe('blob:first')
})

it('多张截图失败只显示一条Message，每张图保留自己的恢复动作', async () => {
  mocks.mediaContent
    .mockRejectedValueOnce(new Error('unavailable'))
    .mockRejectedValueOnce(new Error('unavailable'))
    .mockResolvedValue(new Blob(['image']))
  const firstVisible = ref(true)
  wrapper = mount({
    components: { SessionMediaImage },
    setup: () => ({ firstVisible }),
    template:
      '<div><SessionMediaImage v-if="firstVisible" session="first-session" media="first" /><SessionMediaImage session="second-session" media="second" /></div>'
  })
  await flushPromises()
  expect(wrapper.findAll('.session-media-placeholder')).toHaveLength(2)
  expect(document.querySelectorAll('.el-message')).toHaveLength(1)
  expect(document.querySelectorAll('.el-message button')).toHaveLength(2)
  firstVisible.value = false
  await flushPromises()
  const buttons = document.querySelectorAll<HTMLButtonElement>('.el-message button')
  expect(buttons).toHaveLength(1)
  buttons[0]!.click()
  await flushPromises()
  expect(mocks.mediaContent).toHaveBeenLastCalledWith(
    'second-session',
    'second',
    expect.any(AbortSignal)
  )
  expect(wrapper.findAll('.session-media-placeholder')).toHaveLength(0)
  expect(wrapper.get('img').attributes('src')).toBe('blob:first')
})

it('关闭顶部提示后可主动重新显示恢复操作，重新打开本身不请求图片', async () => {
  mocks.mediaContent
    .mockRejectedValueOnce(new Error('unavailable'))
    .mockResolvedValue(new Blob(['image']))
  wrapper = mount(SessionMediaImage, { props: { session: 'session', media: 'image' } })
  await flushPromises()
  document.querySelector<HTMLElement>('.el-message__closeBtn')!.click()
  await flushPromises()
  expect(document.querySelector('.el-message')).toBeNull()
  expect(wrapper.get('.session-media-placeholder').text()).toBe('图片未加载')
  expect(mocks.mediaContent).toHaveBeenCalledTimes(1)
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '查看恢复操作')!
    .trigger('click')
  await flushPromises()
  expect(mocks.mediaContent).toHaveBeenCalledTimes(1)
  expect(document.querySelectorAll('.el-message')).toHaveLength(1)
  document.querySelector<HTMLButtonElement>('.el-message button')!.click()
  await flushPromises()
  expect(mocks.mediaContent).toHaveBeenCalledTimes(2)
  expect(wrapper.find('.session-media-placeholder').exists()).toBe(false)
  expect(wrapper.get('img').attributes('src')).toBe('blob:first')
})

it('浏览器回执复用组件时保留截图专用alt与占位语义', async () => {
  mocks.mediaContent.mockResolvedValue(new Blob(['image']))
  wrapper = mount(SessionMediaImage, {
    props: {
      session: 'browser-session',
      media: 'screenshot',
      label: '浏览器截图',
      alt: '浏览器任务截图'
    }
  })
  expect(wrapper.text()).toContain('正在加载浏览器截图')
  await flushPromises()
  expect(wrapper.get('img').attributes('alt')).toBe('浏览器任务截图')
  expect(
    wrapper
      .findAll('button')
      .find((button) => button.text() === '查看大图')!
      .attributes('aria-label')
  ).toBe('查看浏览器任务截图大图')
})

it('每张失败图片可在原位置重试，恢复后撤销自己的顶部反馈', async () => {
  mocks.mediaContent
    .mockRejectedValueOnce(new Error('unavailable'))
    .mockResolvedValueOnce(new Blob(['image']))
  wrapper = mount(SessionMediaImage, { props: { session: 'session', media: 'image' } })
  await flushPromises()
  expect(document.querySelectorAll('.el-message')).toHaveLength(1)
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '重新加载图片')!
    .trigger('click')
  await flushPromises()
  expect(mocks.mediaContent).toHaveBeenCalledTimes(2)
  expect(wrapper.get('img').attributes('src')).toBe('blob:first')
  expect(document.querySelector('.el-message')).toBeNull()
})

it('可聚焦查看动作调用EP原生预览，Escape关闭并恢复入口焦点', async () => {
  mocks.mediaContent.mockResolvedValue(new Blob(['image']))
  wrapper = mount(SessionMediaImage, {
    props: { session: 'session', media: 'image' },
    attachTo: document.body
  })
  await flushPromises()
  const trigger = wrapper.findAll('button').find((button) => button.text() === '查看大图')!
  ;(trigger.element as HTMLButtonElement).focus()
  await trigger.trigger('click')
  await flushPromises()
  expect(document.querySelector('.el-image-viewer__wrapper')).not.toBeNull()
  document.dispatchEvent(
    new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape', bubbles: true })
  )
  await flushPromises()
  expect(document.querySelector('.el-image-viewer__wrapper')).toBeNull()
  expect(document.activeElement).toBe(trigger.element)
})
