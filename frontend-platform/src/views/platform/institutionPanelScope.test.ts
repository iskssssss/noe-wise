import { effectScope, ref } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import { useInstitutionPanelCommand, useInstitutionPanelScope } from './institutionPanelScope'

describe('institution panel operation identity', () => {
  it('rejects old confirmations after target changes and after disposal', () => {
    const scope = effectScope()
    const target = ref('77')
    const context = scope.run(() => useInstitutionPanelScope(() => target.value))!
    const original = context.capture()
    target.value = '88'
    expect(original).toThrow('机构目标已变化')
    const next = context.capture()
    expect(next).not.toThrow()
    scope.stop()
    expect(next).toThrow('机构目标已变化')
  })

  it('reconciles only the same key and frozen write instead of creating a new operation', async () => {
    const command = useInstitutionPanelCommand()
    const body = { tenant_id: '77', definition_id: '91' }
    const calls: Array<{ key: string; body: typeof body }> = []
    const write = vi.fn(async (key: string) => {
      calls.push({ key, body: { ...body } })
      if (calls.length === 1) throw Object.assign(new Error('断连'), { status: 0 })
      return { grant_id: '101' }
    })
    const accept = vi.fn()
    await expect(command.execute(write, accept, () => undefined)).rejects.toThrow('断连')
    expect(command.pending.value).toBeDefined()
    await expect(command.execute(write, accept, () => undefined)).rejects.toThrow(
      '原请求结果待核对'
    )
    expect(calls).toHaveLength(1)
    await command.reconcile()
    expect(calls[1]).toEqual(calls[0])
    expect(command.pending.value).toBeUndefined()
    expect(accept).toHaveBeenCalledExactlyOnceWith({ grant_id: '101' })
  })

  it('keeps deterministic rejection separate from an uncertain dispatched write', async () => {
    const command = useInstitutionPanelCommand()
    await expect(
      command.execute(
        async () => {
          throw Object.assign(new Error('无权'), { status: 403 })
        },
        () => undefined,
        () => undefined
      )
    ).rejects.toThrow('无权')
    expect(command.pending.value).toBeUndefined()
  })
})
