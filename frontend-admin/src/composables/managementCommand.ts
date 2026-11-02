import { ref, shallowRef } from 'vue'
import { useUnsavedChanges } from '@skillforge/shared/admin'

/** 管理写入结果未知时保留原操作与键；核对只重新提交同一冻结命令。 */
export function useManagementCommand() {
  const pending = shallowRef<{ key: string; reconcile: () => Promise<void> }>()
  const running = ref(false)
  useUnsavedChanges({ dirty: () => false, busy: () => running.value || !!pending.value })
  async function execute<T>(
    write: (key: string) => Promise<T>,
    accept: (value: T) => void | Promise<void>,
    guard: () => void
  ) {
    if (pending.value) throw Error('原请求结果尚未确认，请先核对原请求')
    const key = crypto.randomUUID()
    const invoke = async () => {
      guard()
      const wasPending = pending.value?.key === key
      running.value = true
      let result: T
      try {
        result = await write(key)
      } catch (reason) {
        const status =
          typeof reason === 'object' && reason !== null && 'status' in reason
            ? Number(reason.status)
            : 0
        if (!wasPending && status >= 400 && status < 500 && status !== 408)
          pending.value = undefined
        else pending.value = { key, reconcile: invoke }
        throw reason
      } finally {
        running.value = false
      }
      pending.value = undefined
      guard()
      await accept(result)
    }
    await invoke()
  }
  async function reconcile() {
    await pending.value?.reconcile()
  }
  return { pending, execute, reconcile }
}
