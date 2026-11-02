import { ref, watch, onScopeDispose, type Ref } from 'vue'

/** 镜像宿主公开输入契约；已有会话通过授权 limits 查询更新。 */
export interface ChatLimits {
  attachments: {
    max_file_bytes: number
    max_files: number
    max_total_bytes: number
    extensions: string[]
  }
  references: { max_items: number; max_file_selectors: number }
  selections: { max_selectable_files: number }
}
const MIB = 1024 * 1024
export const DEFAULT_CHAT_LIMITS: ChatLimits = {
  attachments: {
    max_file_bytes: 50 * MIB,
    max_files: 16,
    max_total_bytes: 200 * MIB,
    extensions: [
      'csv',
      'tsv',
      'txt',
      'md',
      'json',
      'pdf',
      'docx',
      'pptx',
      'xlsx',
      'png',
      'jpg',
      'jpeg'
    ]
  },
  references: { max_items: 64, max_file_selectors: 2048 },
  selections: { max_selectable_files: 2048 }
}
export function useChatLimits(
  session: Ref<string | undefined>,
  load: (sessionId: string, signal: AbortSignal) => Promise<ChatLimits>
) {
  const limits = ref<ChatLimits>(DEFAULT_CHAT_LIMITS)
  let controller: AbortController | undefined
  let epoch = 0
  watch(
    session,
    async (id) => {
      const version = ++epoch
      controller?.abort()
      controller = undefined
      limits.value = DEFAULT_CHAT_LIMITS
      if (!id) return
      const next = new AbortController()
      controller = next
      try {
        const value = await load(id, next.signal)
        if (version === epoch && !next.signal.aborted && session.value === id) limits.value = value
      } catch {
        /* 后端仍执行校验；观察重连或下次会话打开时可重新查询容量。 */
      }
    },
    { immediate: true }
  )
  onScopeDispose(() => {
    ++epoch
    controller?.abort()
  })
  return limits
}
export function attachmentSelectionError(
  files: ReadonlyArray<{ name: string; size: number }>,
  limits: ChatLimits
): string | undefined {
  const policy = limits.attachments
  if (files.length > policy.max_files) return `每条消息最多选择 ${policy.max_files} 个文件。`
  if (
    files.some(
      (file) => !policy.extensions.includes(file.name.split('.').pop()?.toLowerCase() ?? '')
    )
  )
    return `请选择 ${policy.extensions.join('、')} 格式的文件。`
  if (files.some((file) => file.size > policy.max_file_bytes))
    return `每个文件不能超过 ${policy.max_file_bytes / MIB} MiB。`
  if (files.reduce((total, file) => total + file.size, 0) > policy.max_total_bytes)
    return `每条消息附件合计不能超过 ${policy.max_total_bytes / MIB} MiB。`
  return undefined
}
