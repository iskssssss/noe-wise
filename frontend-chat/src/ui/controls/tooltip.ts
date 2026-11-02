import {
  computed,
  defineComponent,
  h,
  mergeProps,
  nextTick,
  onBeforeUnmount,
  ref,
  shallowRef,
  Teleport,
  useId,
  watch
} from 'vue'

/** 视口提示共用定位与清理，正文时间和图标按钮均不参与所在滚动区的尺寸计算。 */
export function useTooltip(
  text: () => string | undefined,
  disabled: () => boolean | undefined = () => false,
  followPointer = false
) {
  const id = `tooltip-${useId()}`
  const element = ref<HTMLElement>()
  const target = shallowRef<HTMLElement | string>('body')
  const position = shallowRef<{ left: number; top: number; above: boolean; ready: boolean }>()
  let dismissed = false
  function hide() {
    position.value = undefined
    window.removeEventListener('scroll', hide, true)
    window.removeEventListener('resize', hide)
  }
  /** 按触发区域定位并限制左右边界，失去焦点或滚动后不留下过期位置。 */
  async function show(event: MouseEvent | FocusEvent) {
    if (!text() || disabled()) return
    const trigger = event.currentTarget as HTMLElement
    target.value = trigger.closest('dialog[open]') ?? 'body'
    const rect = trigger.getBoundingClientRect()
    const pointer = followPointer && event instanceof MouseEvent
    const anchor = pointer
      ? { left: event.clientX, top: event.clientY, bottom: event.clientY }
      : {
          left: rect.left + rect.width / 2,
          top: Math.max(16, rect.top),
          bottom: Math.min(window.innerHeight - 16, rect.bottom)
        }
    // 首次测量不露出未定位的提示，移动时沿用上一帧的稳定位置。
    const next = position.value
      ? { ...position.value }
      : { left: 16, top: 16, above: false, ready: false }
    position.value = next
    window.addEventListener('scroll', hide, true)
    window.addEventListener('resize', hide)
    await nextTick()
    if (position.value !== next || !element.value) return
    element.value.showPopover?.()
    const { width, height } = element.value.getBoundingClientRect()
    const above = anchor.top >= height + 24
    const top = above ? anchor.top - height - 8 : anchor.bottom + 8
    const clampedTop = Math.max(16, Math.min(window.innerHeight - height - 16, top))
    position.value = {
      left: Math.max(16, Math.min(window.innerWidth - width - 16, anchor.left - width / 2)),
      top: clampedTop + (above ? height : 0),
      above,
      ready: true
    }
  }
  watch([text, disabled], hide)
  onBeforeUnmount(hide)
  return {
    describedBy: computed(() => (position.value?.ready ? id : undefined)),
    events: {
      onMouseenter: (event: MouseEvent) => {
        dismissed = false
        void show(event)
      },
      onMousemove: followPointer
        ? (event: MouseEvent) => {
            if (!dismissed) void show(event)
          }
        : undefined,
      onMouseleave: hide,
      onFocus: (event: FocusEvent) => {
        dismissed = false
        void show(event)
      },
      onBlur: hide,
      onClick: hide,
      onKeydown: (event: KeyboardEvent) => {
        if (event.key === 'Escape') {
          dismissed = true
          hide()
        }
      }
    },
    render: () =>
      position.value &&
      h(
        Teleport,
        { to: target.value },
        h(
          'span',
          {
            ref: element,
            id,
            role: 'tooltip',
            popover: 'manual',
            class: ['chat-tooltip', { 'is-above': position.value.above }],
            style: {
              left: `${position.value.left}px`,
              top: `${position.value.top}px`,
              visibility: position.value.ready ? undefined : 'hidden'
            }
          },
          text()
        )
      )
  }
}

/** 可聚焦正文容器，不替换其中的链接、文本选择或 Markdown 交互。 */
export const ChatTooltip = defineComponent({
  name: 'ChatTooltip',
  inheritAttrs: false,
  props: { text: String },
  setup(props, { slots, attrs }) {
    const tooltip = useTooltip(
      () => props.text,
      () => false,
      true
    )
    return () =>
      h(
        'div',
        mergeProps(attrs, tooltip.events, {
          tabindex: props.text ? 0 : undefined,
          'aria-describedby':
            [attrs['aria-describedby'], tooltip.describedBy.value].filter(Boolean).join(' ') ||
            undefined
        }),
        [slots.default?.(), tooltip.render()]
      )
  }
})
