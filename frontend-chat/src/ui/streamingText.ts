import { onBeforeUnmount, ref, watch } from 'vue'

const DEFAULT_REVEAL_WINDOW_MS = 260
const MIN_REVEAL_WINDOW_MS = 240
const MAX_REVEAL_WINDOW_MS = 280
const FRAME_INTERVAL_MS = 16

/** 二分定位下一字素边界，代理对、组合重音、ZWJ emoji 和旗帜不在帧间拆开。 */
function prefixBoundary(boundaries: number[], length: number): number {
  let left = 0,
    right = boundaries.length
  while (left < right) {
    const middle = Math.floor((left + right) / 2)
    if (boundaries[middle]! < length) left = middle + 1
    else right = middle
  }
  return boundaries[left] ?? length
}

/** 仅流式期间渐进展示已收到的累计正文；首次挂载、替换及所有收口立即同步原文。 */
export function useStreamingText(props: { text?: string; streaming?: boolean }) {
  const displayedText = ref(props.text ?? '')
  let target = displayedText.value,
    frame: number | undefined,
    revealFrom = displayedText.value.length,
    startedAt = 0,
    deadline = 0,
    lastArrival: number | undefined,
    cadence: number | undefined,
    disposed = false
  const segmenter =
    typeof Intl.Segmenter === 'function'
      ? new Intl.Segmenter('zh', { granularity: 'grapheme' })
      : undefined
  let boundaries: number[] = []
  const motionQuery =
    typeof window.matchMedia === 'function'
      ? window.matchMedia('(prefers-reduced-motion: reduce)')
      : undefined
  let reducedMotion = motionQuery?.matches ?? false

  /** 撤销当前待展示帧；收口、替换和卸载不留下迟到写入。 */
  function cancelFrame() {
    if (frame !== undefined) cancelAnimationFrame(frame)
    frame = undefined
    startedAt = 0
    deadline = 0
  }

  /** 完整快照始终可立即采用，不在模型终态后补播尚未展示的字符。 */
  function showLatest() {
    cancelFrame()
    displayedText.value = target
  }

  /** 每帧线性推进最新目标；后续快照重规划而不排队，停止到达后正常前台帧在 300ms 内追齐。 */
  function advance(timestamp: number) {
    frame = undefined
    if (disposed) return
    if (!props.streaming || reducedMotion || timestamp >= deadline) {
      showLatest()
      return
    }
    const progress = Math.max(0, (timestamp - startedAt) / (deadline - startedAt)),
      length = prefixBoundary(
        boundaries,
        Math.min(target.length, revealFrom + Math.ceil((target.length - revealFrom) * progress))
      )
    displayedText.value = target.slice(0, length)
    if (length < target.length) frame = requestAnimationFrame(advance)
    else {
      startedAt = 0
      deadline = 0
    }
  }

  /** 仅接受真实追加作为动画目标；减弱动态、非前缀替换或无 rAF 环境直接显示。 */
  watch(
    [() => props.text ?? '', () => props.streaming === true],
    ([text, streaming]) => {
      const previous = target
      target = text
      if (
        !streaming ||
        reducedMotion ||
        !segmenter ||
        typeof requestAnimationFrame !== 'function' ||
        !text.startsWith(previous) ||
        !text.startsWith(displayedText.value)
      ) {
        lastArrival = undefined
        cadence = undefined
        showLatest()
        return
      }
      if (text === displayedText.value) {
        cancelFrame()
        return
      }
      // 一个新快照只分段一次；帧回调复用边界，且切片永不超过真实累计正文。
      if (text !== previous) {
        boundaries = Array.from(segmenter.segment(text), (item) => item.index + item.segment.length)
        const arrivedAt = performance.now()
        if (lastArrival !== undefined) {
          const interval = arrivedAt - lastArrival
          cadence = cadence === undefined ? interval : cadence * 0.6 + interval * 0.4
        }
        lastArrival = arrivedAt
        // 靠近真实快照节奏，消除长静止间歇；频繁追加只改最新目标，不承诺旧水位的独立硬时限。
        const duration =
          cadence === undefined
            ? DEFAULT_REVEAL_WINDOW_MS
            : Math.min(
                MAX_REVEAL_WINDOW_MS,
                Math.max(MIN_REVEAL_WINDOW_MS, cadence + FRAME_INTERVAL_MS)
              )
        revealFrom = displayedText.value.length
        startedAt = arrivedAt
        deadline = arrivedAt + duration
      }
      if (frame === undefined) frame = requestAnimationFrame(advance)
    },
    { flush: 'sync' }
  )

  /** 系统减弱动态偏好切换后立即采用完整快照，不继续当前帧序列。 */
  function motionChanged(event: MediaQueryListEvent) {
    if (disposed) return
    reducedMotion = event.matches
    if (reducedMotion) showLatest()
  }
  motionQuery?.addEventListener('change', motionChanged)

  onBeforeUnmount(() => {
    disposed = true
    cancelFrame()
    motionQuery?.removeEventListener('change', motionChanged)
  })
  return displayedText
}
