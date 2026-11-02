import { shallowRef } from 'vue'

/** 仅在同一页面会话中交接用户为新知识库选择的首个文件，不持久化文件内容。 */
const pending = shallowRef<{ owner: string; identity: number; kbId: string; file: File }>()

export function prepareInitialFile(owner: string, identity: number, kbId: string, file: File) {
  pending.value = { owner, identity, kbId, file }
}

export function peekInitialFile(owner: string, identity: number, kbId: string) {
  const value = pending.value
  return value?.owner === owner && value.identity === identity && value.kbId === kbId
    ? value.file
    : undefined
}

export function takeInitialFile(owner: string, identity: number, kbId: string) {
  if (pending.value && (pending.value.owner !== owner || pending.value.identity !== identity))
    pending.value = undefined
  const file = peekInitialFile(owner, identity, kbId)
  if (file) pending.value = undefined
  return file
}
