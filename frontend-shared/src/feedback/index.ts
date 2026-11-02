import { h, shallowReactive, type AppContext, type VNodeChild } from 'vue'
import { ElButton, ElMessage, ElMessageBox, ElLoading } from 'element-plus'

export type FeedbackType = 'success' | 'error' | 'warning' | 'info'
export type I18nParams = Record<string, unknown>
export interface FeedbackContent {
  text?: string
  key?: string
  params?: I18nParams
}
export type FeedbackInput = string | FeedbackContent
export interface FeedbackAction {
  label?: string
  labelKey?: string
  params?: I18nParams
  onClick: () => void | Promise<void>
}
export interface FeedbackOptions {
  params?: I18nParams
  details?: FeedbackInput
  action?: FeedbackAction
  duration?: number
  dedupeKey?: string
  /** 声明式消息保留所属页面的重试等操作。 */
  footer?: () => VNodeChild
  onClose?: () => void
}
export interface MessageBoxOptions {
  title: FeedbackInput
  content?: FeedbackInput
  details?: FeedbackInput
  danger?: boolean
  confirmText?: FeedbackInput
  cancelText?: FeedbackInput
}

let appContext: AppContext | undefined
const fallbackText: Record<string, string> = {
  'common.action.confirm': '确认',
  'common.action.cancel': '取消',
  'common.action.close': '关闭',
  'common.feedback.details': '排查信息',
  'common.feedback.diagnosticsTitle': '问题排查信息',
  'common.feedback.diagnosticsHelp': '需要协助时，可将以下信息提供给管理员。'
}
let translate: (key: string, params?: I18nParams) => string = (key) => fallbackText[key] ?? key
/** 业务反馈只负责翻译、去重与诊断信息；浮层和状态由 Element Plus 管理。 */
export function configureFeedback(context: AppContext, t: typeof translate) {
  appContext = context
  translate = t
}
function text(input?: FeedbackInput, params?: I18nParams): string {
  if (input === undefined) return ''
  return typeof input === 'string'
    ? input
    : input.key
      ? translate(input.key, input.params ?? params)
      : (input.text ?? '')
}
function content(input: FeedbackInput, options: FeedbackOptions, inConfirmation = false) {
  if (!options.details && !options.action && !options.footer) return text(input, options.params)
  return h('div', { class: 'admin-feedback-content' }, [
    h('div', { class: 'admin-feedback-summary' }, text(input, options.params)),
    options.details
      ? inConfirmation
        ? h('details', { class: 'admin-feedback-inline-details' }, [
            h('summary', translate('common.feedback.details')),
            h('pre', { class: 'admin-feedback-diagnostics' }, text(options.details))
          ])
        : h(ElButton, { text: true, onClick: () => showDetails(input, options) }, () =>
            translate('common.feedback.details')
          )
      : null,
    options.action
      ? h(
          ElButton,
          { text: true, onClick: options.action.onClick },
          () =>
            options.action!.label ??
            translate(options.action!.labelKey ?? '', options.action!.params)
        )
      : null,
    options.footer ? h(options.footer) : null
  ])
}
async function showDetails(input: FeedbackInput, options: FeedbackOptions) {
  try {
    await ElMessageBox.alert(
      h('div', [
        h('p', text(input, options.params)),
        h('p', { class: 'admin-field-help' }, translate('common.feedback.diagnosticsHelp')),
        h('pre', { class: 'admin-feedback-diagnostics' }, text(options.details))
      ]),
      translate('common.feedback.diagnosticsTitle'),
      {
        confirmButtonText: translate('common.action.close'),
        customClass: 'admin-confirm',
        closeOnClickModal: true,
        closeOnPressEscape: true
      },
      appContext
    )
  } catch (reason) {
    if (reason !== 'cancel' && reason !== 'close') throw reason
  }
}
const messageHandles = new Map<
  string,
  {
    close: () => void
    owners: Map<symbol, FeedbackOptions>
    persistent: boolean
    refreshDuration: () => void
  }
>()
function messageDuration(type: FeedbackType, options: FeedbackOptions) {
  return (
    options.duration ??
    (options.details || options.action || options.footer || type === 'error' ? 0 : 4000)
  )
}
export function showMessage(
  type: FeedbackType,
  input: FeedbackInput,
  options: FeedbackOptions = {},
  owner?: symbol
) {
  const key = options.dedupeKey ?? `${type}:${text(input, options.params)}`
  function release() {
    const entry = messageHandles.get(key)
    if (!entry || !owner) return
    entry.owners.delete(owner)
    if (!entry.owners.size && !entry.persistent) {
      entry.close()
      messageHandles.delete(key)
    } else entry.refreshDuration()
  }
  const existing = messageHandles.get(key)
  if (existing) {
    if (owner) existing.owners.set(owner, options)
    else existing.persistent = true
    existing.refreshDuration()
    return release
  }
  if (messageHandles.size >= 3) {
    const oldest = messageHandles.keys().next().value!
    messageHandles.get(oldest)?.close()
    messageHandles.delete(oldest)
  }
  const owners = shallowReactive(new Map(owner ? [[owner, options]] : []))
  let timer: ReturnType<typeof setTimeout> | undefined
  function clearTimer() {
    if (timer) clearTimeout(timer)
    timer = undefined
  }
  const handle = ElMessage(
    {
      type,
      message: owner
        ? h(() =>
            content(input, {
              ...options,
              footer: () =>
                [...owners].map(([source, item]) =>
                  item.footer
                    ? h('div', { key: source, class: 'admin-feedback-owner-actions' }, [
                        h(item.footer)
                      ])
                    : null
                )
            })
          )
        : content(input, options),
      // 声明式消息的时长随存活来源变化，不能让先前的计时器移除后来加入的恢复动作。
      duration: owner ? 0 : messageDuration(type, options),
      customClass: 'admin-feedback',
      placement: 'top',
      showClose: true,
      onClose: () => {
        const entry = messageHandles.get(key)
        if (entry?.close === close) {
          clearTimer()
          messageHandles.delete(key)
          entry.owners.forEach((item) => item.onClose?.())
        }
      }
    },
    appContext
  )
  const close = () => {
    clearTimer()
    handle.close()
  }
  const entry = {
    close,
    owners,
    persistent: !owner,
    refreshDuration: () => {
      if (!owner) return
      clearTimer()
      const durations = [...owners.values()].map((item) => messageDuration(type, item))
      if (entry.persistent) durations.push(messageDuration(type, options))
      if (durations.length && !durations.includes(0))
        timer = setTimeout(close, Math.max(...durations))
    }
  }
  messageHandles.set(key, entry)
  entry.refreshDuration()
  return release
}
export const message = {
  success: (input: FeedbackInput, options?: FeedbackOptions) =>
    showMessage('success', input, options),
  error: (input: FeedbackInput, options?: FeedbackOptions) => showMessage('error', input, options),
  warning: (input: FeedbackInput, options?: FeedbackOptions) =>
    showMessage('warning', input, options),
  info: (input: FeedbackInput, options?: FeedbackOptions) => showMessage('info', input, options)
}
/** 取消只返回 false，真实异常继续交给调用方，不吞掉配置或渲染错误。 */
const cancelPendingConfirmations = new Set<() => void>()
export async function messageBox(options: MessageBoxOptions): Promise<boolean> {
  // 重复确认不共享成功结果，避免一次确认触发多个写请求。
  if (cancelPendingConfirmations.size) return false
  let cancel!: () => void
  const cancelled = new Promise<boolean>((resolve) => {
    cancel = () => resolve(false)
  })
  cancelPendingConfirmations.add(cancel)
  try {
    const confirmation = ElMessageBox.confirm(
      content(options.content ?? '', { details: options.details }, true),
      text(options.title),
      {
        type: options.danger === false ? 'info' : 'warning',
        confirmButtonText: text(options.confirmText ?? { key: 'common.action.confirm' }),
        cancelButtonText: text(options.cancelText ?? { key: 'common.action.cancel' }),
        closeOnClickModal: false,
        distinguishCancelAndClose: true,
        customClass: 'admin-confirm',
        confirmButtonClass: options.danger ? 'el-button--danger' : '',
        autofocus: !options.danger
      },
      appContext
    ).then(() => true)
    return await Promise.race([confirmation, cancelled])
  } catch (reason) {
    if (reason === 'cancel' || reason === 'close') return false
    throw reason
  } finally {
    cancelPendingConfirmations.delete(cancel)
  }
}
export const confirm = messageBox
let loadingCount = 0
let mask: ReturnType<typeof ElLoading.service> | undefined
export const loading = {
  show() {
    if (loadingCount++ === 0) mask = ElLoading.service({ fullscreen: true, lock: true })
  },
  hide() {
    if (loadingCount > 0 && --loadingCount === 0) {
      mask?.close()
      mask = undefined
    }
  }
}
export function clearFeedback() {
  cancelPendingConfirmations.forEach((cancel) => cancel())
  cancelPendingConfirmations.clear()
  ElMessage.closeAll()
  ElMessageBox.close()
  messageHandles.clear()
  mask?.close()
  mask = undefined
  loadingCount = 0
}
