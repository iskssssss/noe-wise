import { effectScope, ref } from 'vue'
import { afterEach, expect, it, vi } from 'vitest'
import { useSkillCreation } from './skillCreation'
import { HttpClientError } from '../utils/http'

afterEach(() => {
  sessionStorage.clear()
  vi.restoreAllMocks()
})

it('原请求按主体保存，切换主体不暴露回执且切回后继续核对', async () => {
  const subject = ref('tenant:actor-a'),
    identity = ref(1)
  const api = {
    createWithReceipt: vi.fn().mockRejectedValue(new Error('断连')),
    creationReceipt: vi.fn().mockResolvedValue(undefined)
  }
  const scope = effectScope()
  const creation = scope.run(() =>
    useSkillCreation(
      'institution',
      () => subject.value,
      () => identity.value,
      api
    )
  )!
  await expect(
    creation.create({ capabilityKey: 'skill-a', name: '技能', description: '' })
  ).rejects.toThrow('断连')
  const requestId = creation.pending.value!.requestId
  subject.value = 'tenant:actor-b'
  identity.value++
  expect(creation.pending.value).toBeUndefined()
  subject.value = 'tenant:actor-a'
  identity.value++
  expect(creation.pending.value?.requestId).toBe(requestId)
  expect(await creation.check()).toBeUndefined()
  expect(creation.pending.value?.requestId).toBe(requestId)
  expect(api.createWithReceipt).toHaveBeenCalledOnce()
  scope.stop()
})

it('浏览器存储不可用时不发送创建请求', async () => {
  const api = { createWithReceipt: vi.fn(), creationReceipt: vi.fn() }
  const scope = effectScope()
  const creation = scope.run(() =>
    useSkillCreation(
      'platform',
      () => 'actor',
      () => 1,
      api
    )
  )!
  vi.spyOn(Storage.prototype, 'setItem').mockImplementationOnce(() => {
    throw Error('unavailable')
  })
  await expect(
    creation.create({ capabilityKey: 'skill-a', name: '技能', description: '' })
  ).rejects.toThrow('尚未发送请求')
  expect(api.createWithReceipt).not.toHaveBeenCalled()
  scope.stop()
})

it.each([
  ['SKILL_CREATE_REJECTED', false],
  ['SKILL_CREATE_IDENTITY_CONFLICT', true]
])('根据明确拒绝或请求标识冲突决定是否保留原请求：%s', async (code, remainsPending) => {
  const error = new HttpClientError({ status: 409, code, userMessage: '冲突' })
  const api = { createWithReceipt: vi.fn().mockRejectedValue(error), creationReceipt: vi.fn() }
  const scope = effectScope()
  const creation = scope.run(() =>
    useSkillCreation(
      'platform',
      () => 'actor',
      () => 1,
      api
    )
  )!
  await expect(
    creation.create({ capabilityKey: 'skill-a', name: '技能', description: '' })
  ).rejects.toBe(error)
  expect(!!creation.pending.value).toBe(remainsPending)
  scope.stop()
})
