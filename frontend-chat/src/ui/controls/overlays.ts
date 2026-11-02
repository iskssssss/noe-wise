import {
  createVNode,
  defineComponent,
  h,
  nextTick,
  onBeforeUnmount,
  onMounted,
  ref,
  render,
  Teleport,
  useId,
  watch,
  type Slot,
  type PropType
} from 'vue'
import { CircleAlert, CircleCheck, Info, X } from 'lucide-vue-next'
import { ChatButton } from './forms'

/** 原生 dialog 承担焦点约束与背景隔离，关闭前调用业务面板的放弃保护。 */
export const ChatDrawer = defineComponent({
  name: 'ChatDrawer',
  inheritAttrs: false,
  props: {
    modelValue: Boolean,
    title: String,
    size: { type: [String, Number], default: 'min(480px, 100vw)' },
    direction: { type: String, default: 'rtl' },
    withHeader: { type: Boolean, default: true },
    showClose: { type: Boolean, default: true },
    closeOnClickModal: { type: Boolean, default: true },
    closeOnPressEscape: { type: Boolean, default: true },
    beforeClose: Function as PropType<(done: () => void) => unknown>,
    centered: Boolean
  },
  emits: ['update:modelValue'],
  setup(props, { slots, emit, attrs }) {
    const dialog = ref<HTMLDialogElement>(),
      titleId = `dialog-${useId()}`
    let previousFocus: HTMLElement | undefined,
      closing = false
    const done = () => emit('update:modelValue', false)
    const close = async () => {
      if (closing) return
      closing = true
      try {
        if (props.beforeClose) await props.beforeClose(done)
        else done()
      } finally {
        closing = false
      }
    }
    watch(
      () => props.modelValue,
      async (value) => {
        if (value) {
          previousFocus =
            document.activeElement instanceof HTMLElement ? document.activeElement : undefined
          await nextTick()
          if (dialog.value && !dialog.value.open) {
            if (dialog.value.showModal) dialog.value.showModal()
            else dialog.value.setAttribute('open', '')
          }
        } else {
          dialog.value?.close?.()
          if (previousFocus?.isConnected) previousFocus.focus()
        }
      },
      { immediate: true, flush: 'post' }
    )
    onBeforeUnmount(() => {
      dialog.value?.close?.()
      if (previousFocus?.isConnected) previousFocus.focus()
    })
    return () =>
      props.modelValue
        ? h(
            'div',
            { class: 'chat-dialog-host', style: { display: 'contents' } },
            h(
              'dialog',
              {
                ...attrs,
                ref: dialog,
                class: [
                  'chat-drawer',
                  `chat-drawer--${props.direction}`,
                  { 'chat-dialog': props.centered },
                  attrs.class
                ],
                style: {
                  '--drawer-size': typeof props.size === 'number' ? `${props.size}px` : props.size
                },
                'aria-labelledby': props.withHeader && props.title ? titleId : undefined,
                'aria-label':
                  attrs['aria-label'] || (props.withHeader ? undefined : props.title || '会话导航'),
                onCancel: (event: Event) => {
                  event.preventDefault()
                  if (props.closeOnPressEscape) void close()
                },
                onClick: (event: MouseEvent) => {
                  if (event.target !== dialog.value || !props.closeOnClickModal) return
                  const rect = dialog.value.getBoundingClientRect()
                  if (
                    event.clientX < rect.left ||
                    event.clientX > rect.right ||
                    event.clientY < rect.top ||
                    event.clientY > rect.bottom
                  )
                    void close()
                }
              },
              [
                props.withHeader &&
                  h('header', { class: 'chat-drawer__header' }, [
                    h('h2', { id: titleId }, props.title),
                    props.showClose &&
                      h(
                        ChatButton,
                        {
                          text: true,
                          class: 'icon-button',
                          'aria-label': '关闭面板',
                          title: '关闭面板',
                          onClick: close
                        },
                        { default: () => h(X, { size: 18, 'aria-hidden': true }) }
                      )
                  ]),
                h('div', { class: 'chat-drawer__body' }, slots.default?.()),
                slots.footer && h('footer', { class: 'chat-drawer__footer' }, slots.footer())
              ]
            )
          )
        : null
  }
})

/** 弹出内容根据参照元素定位，随窗口及滚动更新且不挤压主布局。 */
export const ChatPopover = defineComponent({
  name: 'ChatPopover',
  props: {
    visible: Boolean,
    trigger: { type: String, default: 'click' },
    placement: { type: String, default: 'bottom-end' },
    width: [String, Number],
    popperClass: String,
    teleported: { type: Boolean, default: true },
    showArrow: Boolean,
    persistent: Boolean
  },
  emits: ['update:visible'],
  setup(props, { slots, emit }) {
    const anchor = ref<HTMLElement>(),
      popup = ref<HTMLElement>(),
      position = ref<Record<string, string>>({})
    const place = () => {
      const rect = anchor.value?.getBoundingClientRect()
      if (!rect) return
      const width = Math.min(
          popup.value?.getBoundingClientRect().width || rect.width,
          innerWidth - 24
        ),
        left = props.placement.endsWith('start') ? rect.left : rect.right - width
      position.value = {
        left: `${Math.max(12, Math.min(left, innerWidth - width - 12))}px`,
        ...(props.placement.startsWith('top')
          ? {
              bottom: `${innerHeight - rect.top + 8}px`,
              maxHeight: `${Math.max(80, rect.top - 16)}px`
            }
          : {
              top: `${rect.bottom + 8}px`,
              maxHeight: `${Math.max(80, innerHeight - rect.bottom - 16)}px`
            })
      }
    }
    const outside = (event: PointerEvent) => {
      if (
        props.visible &&
        !anchor.value?.contains(event.target as Node) &&
        !popup.value?.contains(event.target as Node)
      )
        emit('update:visible', false)
    }
    const escape = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && props.visible) {
        event.stopPropagation()
        event.preventDefault()
        emit('update:visible', false)
        anchor.value?.querySelector<HTMLElement>('button, input, textarea')?.focus()
      }
    }
    onMounted(() => {
      document.addEventListener('pointerdown', outside)
      document.addEventListener('keydown', escape)
      window.addEventListener('resize', place)
      window.addEventListener('scroll', place, true)
    })
    onBeforeUnmount(() => {
      document.removeEventListener('pointerdown', outside)
      document.removeEventListener('keydown', escape)
      window.removeEventListener('resize', place)
      window.removeEventListener('scroll', place, true)
    })
    watch(
      () => props.visible,
      () => void nextTick(place),
      { immediate: true }
    )
    return () =>
      h(
        'div',
        {
          class: 'chat-popover-anchor',
          ref: anchor,
          onClick: (event: MouseEvent) => {
            if (props.trigger !== 'click') return
            emit('update:visible', !props.visible)
            if (!props.visible && event.detail === 0)
              void nextTick(() =>
                popup.value?.querySelector<HTMLElement>('button:not(:disabled), input')?.focus()
              )
          }
        },
        [
          slots.reference?.(),
          props.visible &&
            h(
              Teleport,
              { to: 'body', disabled: !props.teleported },
              h(
                'div',
                {
                  ref: popup,
                  class: ['chat-popover', props.popperClass],
                  style: {
                    width:
                      typeof props.width === 'number'
                        ? `${props.width}px`
                        : props.width || `${anchor.value?.getBoundingClientRect().width || 240}px`,
                    maxWidth: 'calc(100vw - 24px)',
                    ...position.value
                  },
                  onClick: (event: Event) => event.stopPropagation()
                },
                slots.default?.()
              )
            )
        ]
      )
  }
})

export const ChatAlert = defineComponent({
  name: 'ChatAlert',
  props: {
    title: String,
    type: { type: String, default: 'info' },
    closable: { type: Boolean, default: true },
    showIcon: Boolean
  },
  emits: ['close'],
  setup(props, { slots, emit }) {
    return () =>
      h(
        'div',
        {
          class: ['chat-alert', `chat-alert--${props.type}`],
          role: props.type === 'error' ? 'alert' : 'status'
        },
        [
          h(
            props.type === 'error' || props.type === 'warning'
              ? CircleAlert
              : props.type === 'success'
                ? CircleCheck
                : Info,
            { size: 16, 'aria-hidden': true }
          ),
          h('div', { class: 'chat-alert__content' }, [
            props.title && h('p', {}, props.title),
            slots.default?.()
          ]),
          props.closable &&
            h(
              ChatButton,
              {
                text: true,
                class: 'icon-button',
                'aria-label': '关闭提示',
                onClick: () => emit('close')
              },
              { default: () => h(X, { size: 14 }) }
            )
        ]
      )
  }
})

/** 确认框挂载为最上层 dialog，取消保持 Promise 拒绝以阻止待执行业务动作。 */
export const ChatMessageBox = {
  confirm(
    message: string,
    title: string,
    options: {
      confirmButtonText?: string
      cancelButtonText?: string
      type?: string
      closeOnClickModal?: boolean
    } = {}
  ) {
    return new Promise<void>((resolve, reject) => {
      const host = document.createElement('div')
      document.body.append(host)
      const finish = (accepted: boolean) => {
        render(null, host)
        host.remove()
        if (accepted) resolve()
        else reject(new Error('cancel'))
      }
      render(
        createVNode(
          ChatDrawer,
          {
            modelValue: true,
            centered: true,
            title,
            size: 'min(420px, calc(100vw - 32px))',
            closeOnClickModal: options.closeOnClickModal ?? false,
            'onUpdate:modelValue': () => finish(false)
          },
          {
            default: () => h('p', { class: 'confirmation-message' }, message),
            footer: () => [
              h(
                ChatButton,
                { onClick: () => finish(false), autofocus: true },
                { default: () => options.cancelButtonText || '取消' }
              ),
              h(
                ChatButton,
                { type: 'primary', onClick: () => finish(true) },
                { default: () => options.confirmButtonText || '确认' }
              )
            ]
          }
        ),
        host
      )
    })
  }
}
type MessageType = 'info' | 'success' | 'warning' | 'error'
interface MessageEntry {
  close: () => void
  owners: number
  host: HTMLElement
}
const messages = new Map<string, MessageEntry>()
function positionMessages() {
  let top = 24
  for (const entry of messages.values()) {
    entry.host.style.top = `${top}px`
    top += entry.host.getBoundingClientRect().height + 12
  }
}

/** 操作反馈统一置于页面顶部；重复内容共享一条消息，错误保留至关闭或状态清除。 */
function showMessage(type: MessageType, message: string, details?: Slot, onDismiss?: () => void) {
  const key = `${type}:${message}`
  const existing = messages.get(key)
  if (existing?.host.isConnected && existing.owners > 0) {
    existing.owners++
    return () => {
      if (--existing.owners === 0) existing.close()
    }
  }
  existing?.close()
  if (messages.size >= 3) messages.values().next().value?.close()
  const host = document.createElement('div')
  host.className = 'chat-toast'
  host.setAttribute('popover', 'manual')
  // 原生模态框外的节点会处于 inert 状态；消息跟随当前模态框挂载，确保关闭和重试可点击。
  const dialog = [...document.querySelectorAll<HTMLDialogElement>('dialog[open]')].at(-1)
  ;(dialog ?? document.body).append(host)
  const dialogClosed = () => {
    if (!messages.has(key)) return
    // 同一 dialog 切换模态后可能已重新打开；原生 close 会隐藏其子 popover。
    if (!dialog?.open) document.body.append(host)
    host.showPopover?.()
    positionMessages()
  }
  const dialogToggled = () => {
    if (!dialog?.open || host.parentElement !== dialog) return
    // showModal 会把 dialog 放到现有 popover 之后，重新显示消息以恢复最上层交互。
    host.hidePopover?.()
    host.showPopover?.()
    positionMessages()
  }
  dialog?.addEventListener('close', dialogClosed)
  dialog?.addEventListener('toggle', dialogToggled)
  let timer: ReturnType<typeof setTimeout> | undefined
  const entry: MessageEntry = {
    owners: 1,
    host,
    close: () => {
      if (timer) clearTimeout(timer)
      dialog?.removeEventListener('close', dialogClosed)
      dialog?.removeEventListener('toggle', dialogToggled)
      render(null, host)
      host.remove()
      if (messages.get(key) === entry) messages.delete(key)
      positionMessages()
    }
  }
  messages.set(key, entry)
  render(
    h(
      ChatAlert,
      {
        title: message,
        type,
        closable: true,
        onClose: () => {
          entry.close()
          onDismiss?.()
        }
      },
      details ? { default: details } : undefined
    ),
    host
  )
  host.showPopover?.()
  positionMessages()
  if (type !== 'error' && type !== 'warning') timer = setTimeout(entry.close, 4000)
  return () => {
    if (--entry.owners === 0) entry.close()
  }
}
export const ChatMessage = {
  info: (message: string) => showMessage('info', message),
  success: (message: string) => showMessage('success', message),
  warning: (message: string) => showMessage('warning', message),
  error: (message: string) => showMessage('error', message),
  closeAll: () => [...messages.values()].forEach((entry) => entry.close())
}

/** 将页面已有反馈状态接入 Message，业务状态及表单草稿仍归原组件。 */
export const ChatMessageNotice = defineComponent({
  name: 'ChatMessageNotice',
  inheritAttrs: false,
  props: { title: String, type: { type: String as PropType<MessageType>, default: 'info' } },
  emits: ['close'],
  setup(props, { slots, emit }) {
    let release: (() => void) | undefined
    watch(
      () => [props.title, props.type] as const,
      ([title, type]) => {
        release?.()
        release = title ? showMessage(type, title, slots.default, () => emit('close')) : undefined
      },
      { immediate: true }
    )
    onBeforeUnmount(() => release?.())
    return () => null
  }
})
