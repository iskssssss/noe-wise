import { ChatMessage, ChatMessageBox, type FormInstance } from './controls'
import { nextTick } from 'vue'

/** 会话内临时面板的关闭协议，不依赖后台布局或状态。 */
export async function mayDiscard(busy: boolean, dirty: boolean): Promise<boolean> {
  if (busy) {
    ChatMessage.info('当前操作尚未结束，请完成后再关闭或切换。')
    return false
  }
  if (!dirty) return true
  return confirmAction('放弃尚未提交的修改？', '确认离开', '放弃修改', '继续编辑')
}

let confirmation: Promise<boolean> | undefined
/** 确认未结束时忽略后续动作，防止多个入口同时执行。 */
export async function confirmAction(
  message: string,
  title: string,
  confirmText: string,
  cancelText = '取消'
) {
  if (confirmation) return false
  confirmation = (async () => {
    try {
      await ChatMessageBox.confirm(message, title, {
        type: 'warning',
        confirmButtonText: confirmText,
        cancelButtonText: cancelText,
        closeOnClickModal: false
      })
      return true
    } catch {
      return false
    }
  })()
  try {
    return await confirmation
  } finally {
    confirmation = undefined
  }
}

/** 校验失败时定位第一个错误字段，允许键盘用户直接修正。 */
export async function validateForm(form?: FormInstance): Promise<boolean> {
  if (!form) return false
  try {
    return await form.validate()
  } catch {
    await nextTick()
    const item = (form.$el as HTMLElement).querySelector<HTMLElement>('.chat-field.is-error')
    item?.scrollIntoView?.({ block: 'nearest', behavior: 'smooth' })
    item
      ?.querySelector<HTMLElement>(
        'input:not(:disabled), textarea:not(:disabled), button:not(:disabled), [tabindex="0"]'
      )
      ?.focus()
    return false
  }
}

export interface ClosablePanel {
  requestClose(): Promise<boolean>
}
