import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import { defineComponent, h, nextTick, type Ref } from 'vue'
import { useStreamingText } from './streamingText'

let now: number, nextId: number, displayed: Ref<string>, wrapper: VueWrapper | undefined
const frames = new Map<number, FrameRequestCallback>(),
  listeners = new Set<(event: MediaQueryListEvent) => void>()
const motionQuery = {
  matches: false,
  addEventListener: vi.fn((_name: string, listener: (event: MediaQueryListEvent) => void) =>
    listeners.add(listener)
  ),
  removeEventListener: vi.fn((_name: string, listener: (event: MediaQueryListEvent) => void) =>
    listeners.delete(listener)
  )
}
const cancel = vi.fn((id: number) => frames.delete(id)),
  request = vi.fn((callback: FrameRequestCallback) => {
    const id = ++nextId
    frames.set(id, callback)
    return id
  })

beforeEach(() => {
  now = 0
  nextId = 0
  frames.clear()
  listeners.clear()
  request.mockClear()
  cancel.mockClear()
  motionQuery.matches = false
  motionQuery.addEventListener.mockClear()
  motionQuery.removeEventListener.mockClear()
  vi.stubGlobal('requestAnimationFrame', request)
  vi.stubGlobal('cancelAnimationFrame', cancel)
  vi.stubGlobal(
    'matchMedia',
    vi.fn(() => motionQuery)
  )
  vi.spyOn(performance, 'now').mockImplementation(() => now)
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

/** 挂载真实组合式逻辑，正文身份与终态只从父组件属性输入。 */
function mountText(text = '', streaming = true) {
  wrapper = mount(
    defineComponent({
      props: { text: String, streaming: Boolean },
      setup(props) {
        displayed = useStreamingText(props)
        return () => h('p', displayed.value)
      }
    }),
    { props: { text, streaming } }
  )
  return wrapper
}

/** 每次只推进当前帧，帧内排出的下一回调留到下一次，避免用定时器伪造播放时间。 */
async function advanceFrame(milliseconds = 16) {
  now += milliseconds
  const callbacks = [...frames.values()]
  frames.clear()
  callbacks.forEach((callback) => callback(now))
  await nextTick()
}

describe('真实累计正文的帧级渐进呈现', () => {
  it('已收到的新字符按常规到达节奏分散至多帧，显示始终是单调真实前缀', async () => {
    const view = mountText('已有'),
      target = `已有${'甲'.repeat(120)}`
    await view.setProps({ text: target })
    expect(view.props()).toHaveProperty('text', target)
    expect(displayed.value).toBe('已有')
    const lengths: number[] = []
    for (let index = 0; index < 18; index++) {
      const previous = displayed.value
      await advanceFrame()
      expect(displayed.value.startsWith(previous)).toBe(true)
      expect(target.startsWith(displayed.value)).toBe(true)
      expect(displayed.value.length).toBeLessThanOrEqual(target.length)
      lengths.push(displayed.value.length)
    }
    expect(lengths[0]).toBeGreaterThan(3)
    expect(lengths[0]).toBeLessThan(target.length)
    expect(new Set(lengths).size).toBeGreaterThan(3)
    expect(lengths[15]).toBeLessThan(target.length)
    expect(lengths[16]).toBe(target.length)
    expect(displayed.value).toBe(target)
    expect(frames.size).toBe(0)
  })

  it('连续快照从当前前缀重规划最新窗口，不为旧快照逐次排队', async () => {
    const view = mountText('已有')
    let target = `已有${'甲'.repeat(90)}`
    await view.setProps({ text: target })
    await advanceFrame()
    target += '乙'.repeat(60)
    await view.setProps({ text: target })
    expect(frames.size).toBe(1)
    await advanceFrame()
    target += '丙'.repeat(60)
    await view.setProps({ text: target })
    for (let index = 0; index < 16; index++) {
      await advanceFrame()
      expect(target.startsWith(displayed.value)).toBe(true)
      expect(frames.size).toBeLessThanOrEqual(1)
    }
    expect(now).toBe(288)
    expect(displayed.value).toBe(target)
    expect(frames.size).toBe(0)
  })

  it('250–280ms交替到达时保持小步连续增长，不在每批之后留下长静止间歇', async () => {
    const view = mountText('已有')
    let target = `已有${'甲'.repeat(120)}`,
      lastGrowth = 0,
      longestGap = 0,
      growthCount = 0
    await view.setProps({ text: target })
    const observeFrame = async (milliseconds: number) => {
      const previous = displayed.value
      await advanceFrame(milliseconds)
      expect(displayed.value.startsWith(previous)).toBe(true)
      expect(target.startsWith(displayed.value)).toBe(true)
      if (displayed.value.length > previous.length) {
        longestGap = Math.max(longestGap, now - lastGrowth)
        lastGrowth = now
        growthCount++
      }
    }
    for (const arrivedAt of [250, 530, 780, 1060]) {
      while (now < arrivedAt) await observeFrame(Math.min(16, arrivedAt - now))
      target += '乙'.repeat(120)
      await view.setProps({ text: target })
      expect(frames.size).toBe(1)
    }
    const lastArrival = now
    while (frames.size && now - lastArrival < 300) await observeFrame(16)
    expect(growthCount).toBeGreaterThan(60)
    expect(longestGap).toBeLessThanOrEqual(48)
    expect(now - lastArrival).toBeLessThanOrEqual(300)
    expect(displayed.value).toBe(target)
    expect(frames.size).toBe(0)
  })

  it('多批频繁追加仅保留单一目标，停止到达后300ms内追齐最后快照', async () => {
    const view = mountText('已有')
    let target = '已有'
    for (let index = 0; index < 40; index++) {
      target += '甲'.repeat(80)
      await view.setProps({ text: target })
      expect(frames.size).toBe(1)
      const previous = displayed.value
      await advanceFrame(20)
      expect(displayed.value.startsWith(previous)).toBe(true)
      expect(target.startsWith(displayed.value)).toBe(true)
      expect(frames.size).toBeLessThanOrEqual(1)
    }
    const stoppedAt = now
    while (frames.size && now - stoppedAt < 300) await advanceFrame()
    expect(now - stoppedAt).toBeLessThanOrEqual(300)
    expect(displayed.value).toBe(target)
    expect(frames.size).toBe(0)
  })

  it.each(['completed', 'cancelled', 'failed'])(
    '%s立即显示最新完整快照，撤销帧且不在收口后播放',
    async () => {
      const view = mountText('已有'),
        target = `已有${'真实文字'.repeat(40)}`
      await view.setProps({ text: target })
      await advanceFrame()
      expect(displayed.value.length).toBeLessThan(target.length)
      await view.setProps({ text: `${target}最后已收到`, streaming: false })
      expect(displayed.value).toBe(`${target}最后已收到`)
      expect(frames.size).toBe(0)
      expect(cancel).toHaveBeenCalled()
      await advanceFrame(300)
      expect(displayed.value).toBe(`${target}最后已收到`)
      await view.setProps({ text: `${target}迟到持久快照` })
      expect(displayed.value).toBe(`${target}迟到持久快照`)
      expect(frames.size).toBe(0)
    }
  )

  it('首屏已有正文和重连历史直接显示，不重新播放已接收的消息', () => {
    const text = '已持久的历史正文'.repeat(100)
    mountText(text)
    expect(displayed.value).toBe(text)
    expect(request).not.toHaveBeenCalled()
    expect(frames.size).toBe(0)
  })

  it('文本被替换或缩短时立即同步，不把两个不同正文拼成一次动画', async () => {
    const view = mountText('已有')
    await view.setProps({ text: `已有${'甲'.repeat(100)}` })
    await advanceFrame()
    await view.setProps({ text: '另一条真实正文' })
    expect(displayed.value).toBe('另一条真实正文')
    expect(frames.size).toBe(0)
    await view.setProps({ text: '另一条' })
    expect(displayed.value).toBe('另一条')
    expect(frames.size).toBe(0)
  })

  it('减弱动态直接显示，流中切换偏好也会撤销帧立即同步', async () => {
    motionQuery.matches = true
    const view = mountText('已有')
    await view.setProps({ text: '已有直接追加' })
    expect(displayed.value).toBe('已有直接追加')
    expect(request).not.toHaveBeenCalled()
    listeners.forEach((listener) => listener({ matches: false } as MediaQueryListEvent))
    const target = `已有直接追加${'甲'.repeat(100)}`
    await view.setProps({ text: target })
    await advanceFrame()
    expect(displayed.value).not.toBe(target)
    listeners.forEach((listener) => listener({ matches: true } as MediaQueryListEvent))
    expect(displayed.value).toBe(target)
    expect(frames.size).toBe(0)
  })

  it('帧切片保持组合emoji、旗帜和组合重音完整，不展示半个字素', async () => {
    const view = mountText('已有'),
      target = `已有${'👨‍👩‍👧‍👦🇨🇳e\u0301👍🏽'.repeat(8)}`,
      boundaries = new Set(
        Array.from(
          new Intl.Segmenter('zh', { granularity: 'grapheme' }).segment(target),
          (item) => item.index + item.segment.length
        )
      )
    await view.setProps({ text: target })
    for (let index = 0; index < 18; index++) {
      await advanceFrame()
      expect(boundaries.has(displayed.value.length)).toBe(true)
      expect(target.startsWith(displayed.value)).toBe(true)
    }
    expect(displayed.value).toBe(target)
  })

  it('卸载撤销未执行帧及动态偏好监听，迟到回调失去写入权', async () => {
    const view = mountText('已有')
    await view.setProps({ text: `已有${'甲'.repeat(100)}` })
    const callback = [...frames.values()][0]!,
      before = displayed.value
    view.unmount()
    wrapper = undefined
    expect(frames.size).toBe(0)
    expect(listeners.size).toBe(0)
    expect(motionQuery.removeEventListener).toHaveBeenCalledOnce()
    callback(1000)
    expect(displayed.value).toBe(before)
  })
})
