import { onScopeDispose, ref, shallowRef, watch } from 'vue'

/** 目标变化或卸载后，原确认与异步写回包不能继续修改新的机构面板。 */
export function useInstitutionPanelScope(target: () => string) {
  let generation = 0
  let active = true
  watch(target, () => generation++, { flush: 'sync' })
  onScopeDispose(() => {
    active = false
    generation++
  })
  return {
    capture() {
      const expected = generation
      return () => {
        if (!active || generation !== expected)
          throw new Error('机构目标已变化，原操作结果请在原机构核对')
      }
    }
  }
}

/** 管理意图的结果未知时，仅允许显式核对同一冻结请求，不因目录刷新创建新意图。 */
export function useInstitutionPanelCommand() {
  const pending = shallowRef<{ key: string; reconcile: () => Promise<void> }>()
  const running = ref(false)
  async function execute<T>(
    write: (key: string) => Promise<T>,
    accept: (value: T) => void | Promise<void>,
    guard: () => void
  ) {
    if (running.value) throw new Error('原请求正在处理，请等待完成')
    if (pending.value) throw new Error('原请求结果待核对，请先核对原请求')
    const key = crypto.randomUUID()
    const invoke = async () => {
      guard()
      running.value = true
      let result: T
      try {
        result = await write(key)
      } catch (reason) {
        const status =
          typeof reason === 'object' && reason !== null && 'status' in reason
            ? Number(reason.status)
            : 0
        if (status >= 400 && status < 500 && status !== 408) pending.value = undefined
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
  return { pending, running, execute, reconcile }
}
