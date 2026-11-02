/** 本地粘贴原文与当时选区；只有发送普通消息时才转为服务端附件。 */
export interface PastedTextAttachment {
  id: string
  name: string
  text: string
  size: number
  selectionStart?: number
  selectionEnd?: number
  selectedText?: string
  sourceDraft?: string
}
