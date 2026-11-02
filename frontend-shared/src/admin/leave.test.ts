import { effectScope } from 'vue'
import { confirm } from '../feedback'
import { describe, expect, it, vi } from 'vitest'
import { confirmPageLeave, useUnsavedChanges } from './leave'
vi.mock('vue-router', () => ({ onBeforeRouteLeave: vi.fn(), onBeforeRouteUpdate: vi.fn() }))
vi.mock('../feedback', () => ({ message: { warning: vi.fn() }, confirm: vi.fn() }))

describe('单页离开保护', () => {
  it('取消离开不丢弃草稿，确认后才丢弃', async () => {
    const scope = effectScope()
    const discard = vi.fn()
    scope.run(() => useUnsavedChanges({ dirty: () => true, discard }))
    vi.mocked(confirm).mockResolvedValueOnce(false)
    expect(await confirmPageLeave()).toBe(false)
    expect(discard).not.toHaveBeenCalled()
    vi.mocked(confirm).mockResolvedValueOnce(true)
    expect(await confirmPageLeave()).toBe(true)
    expect(discard).toHaveBeenCalledTimes(1)
    scope.stop()
    expect(await confirmPageLeave()).toBe(true)
  })
  it('提交中不能通过离开确认绕过保护', async () => {
    const scope = effectScope()
    scope.run(() => useUnsavedChanges({ dirty: () => false, busy: () => true }))
    expect(await confirmPageLeave()).toBe(false)
    scope.stop()
  })
  it('未知结果使用具体离开说明，确认不意味着原操作已失败或撤销', async () => {
    const scope = effectScope()
    let unresolved = true
    const detail = '原请求 request-7 结果未知；离开不会撤销，也不能作为失败重新提交。'
    scope.run(() =>
      useUnsavedChanges({
        dirty: () => unresolved,
        leaveMessage: () => (unresolved ? detail : undefined)
      })
    )
    vi.mocked(confirm).mockResolvedValueOnce(false)
    expect(await confirmPageLeave()).toBe(false)
    vi.mocked(confirm).mockResolvedValueOnce(true)
    expect(await confirmPageLeave()).toBe(true)
    expect(confirm).toHaveBeenLastCalledWith(
      expect.objectContaining({ content: detail, confirmText: '确认离开' })
    )
    expect(unresolved).toBe(true)
    unresolved = false
    expect(await confirmPageLeave()).toBe(true)
    scope.stop()
  })
  it('确认未结束时拒绝后续导航，不将同一次确认用于另一个目的地', async () => {
    const scope = effectScope()
    scope.run(() => useUnsavedChanges({ dirty: () => true }))
    let resolve!: (value: boolean) => void
    vi.mocked(confirm).mockImplementationOnce(
      () =>
        new Promise((done) => {
          resolve = done
        })
    )
    const first = confirmPageLeave()
    expect(await confirmPageLeave()).toBe(false)
    resolve(true)
    expect(await first).toBe(true)
    scope.stop()
  })
})
