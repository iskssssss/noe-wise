import { onScopeDispose, ref, shallowRef } from 'vue'
import { HttpClientError } from '../utils/http'

function errorText(reason: unknown): string {
  return reason instanceof Error ? reason.message : '暂时无法确认操作结果，请先查看当前状态。'
}

/** 写操作只接受一次点击；卸载后不更新界面，也不自动重发请求。 */
export function useTask(options: { handlesError?: (reason: unknown) => boolean } = {}) {
  const busy = ref(false)
  const error = ref('')
  let disposed = false
  onScopeDispose(() => {
    disposed = true
  })
  async function run<T>(operation: () => Promise<T>): Promise<T | undefined> {
    if (busy.value || disposed) return undefined
    busy.value = true
    error.value = ''
    try {
      const result = await operation()
      return disposed ? undefined : result
    } catch (reason) {
      if (reason instanceof HttpClientError) reason.claimFeedback()
      if (!disposed && reason !== 'cancel' && reason !== 'close' && !options.handlesError?.(reason))
        error.value = errorText(reason)
      return undefined
    } finally {
      if (!disposed) busy.value = false
    }
  }
  return { busy, error, run }
}

/** 后发读取拥有更新权；路由或身份变化时调用 invalidate 丢弃旧返回。 */
export function useResource<T>(loader: (signal: AbortSignal) => Promise<T>, initial?: T) {
  const data = shallowRef<T | undefined>(initial)
  const loading = ref(false)
  const error = ref('')
  let generation = 0
  let controller: AbortController | undefined
  let disposed = false
  function invalidate() {
    generation++
    controller?.abort()
    loading.value = false
  }
  async function reload() {
    if (disposed) return undefined
    controller?.abort()
    controller = new AbortController()
    const current = ++generation
    loading.value = true
    error.value = ''
    try {
      const result = await loader(controller.signal)
      if (current !== generation || disposed) return undefined
      data.value = result
      return result
    } catch (reason) {
      if (reason instanceof HttpClientError) reason.claimFeedback()
      if (current === generation && !disposed) error.value = errorText(reason)
      return undefined
    } finally {
      if (current === generation && !disposed) loading.value = false
    }
  }
  onScopeDispose(() => {
    disposed = true
    invalidate()
  })
  return { data, loading, error, reload, invalidate }
}
