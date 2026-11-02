import { effectScope } from 'vue'
import { describe, expect, it } from 'vitest'
import { useResource, useTask } from './tasks'

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => {
    resolve = done
  })
  return { promise, resolve }
}

describe('后台异步交互', () => {
  it('提交中拒绝重复操作，失败保留可重试状态', async () => {
    const scope = effectScope()
    const task = scope.run(useTask)!
    const request = deferred<string>()
    const pending = task.run(() => request.promise)
    expect(task.busy.value).toBe(true)
    let duplicate = false
    expect(
      await task.run(async () => {
        duplicate = true
      })
    ).toBeUndefined()
    expect(duplicate).toBe(false)
    request.resolve('完成')
    expect(await pending).toBe('完成')
    await task.run(async () => {
      throw new Error('暂时不可用')
    })
    expect(task.error.value).toBe('暂时不可用')
    expect(task.busy.value).toBe(false)
    scope.stop()
  })

  it('页面销毁后迟到的写入结果不会交给界面', async () => {
    const scope = effectScope()
    const task = scope.run(useTask)!
    const request = deferred<number>()
    const pending = task.run(() => request.promise)
    scope.stop()
    request.resolve(1)
    expect(await pending).toBeUndefined()
  })

  it('后发读取胜出，旧请求即使忽略取消也不能覆盖', async () => {
    const scope = effectScope()
    const first = deferred<string>()
    const second = deferred<string>()
    const signals: AbortSignal[] = []
    const resource = scope.run(() =>
      useResource((signal) => {
        signals.push(signal)
        return signals.length === 1 ? first.promise : second.promise
      })
    )!
    const old = resource.reload()
    const current = resource.reload()
    expect(signals[0]!.aborted).toBe(true)
    second.resolve('新结果')
    await current
    first.resolve('旧结果')
    await old
    expect(resource.data.value).toBe('新结果')
    scope.stop()
  })

  it('身份切换清除读取更新权', async () => {
    const scope = effectScope()
    const request = deferred<string>()
    const resource = scope.run(() => useResource(() => request.promise))!
    const pending = resource.reload()
    resource.invalidate()
    request.resolve('原账号结果')
    await pending
    expect(resource.data.value).toBeUndefined()
    scope.stop()
  })
})
