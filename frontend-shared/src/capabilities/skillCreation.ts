import { computed, onScopeDispose, ref, watch } from 'vue'
import { HttpClientError } from '../utils/http'
import type { SkillCreationReceipt } from './skillDrafts'

type Metadata = { capabilityKey: string; name: string; description: string }
type Pending = { requestId: string; capabilityKey: string; name: string }
type Api = {
  createWithReceipt: (requestId: string, payload: Metadata) => Promise<SkillCreationReceipt>
  creationReceipt: (requestId: string) => Promise<SkillCreationReceipt | undefined>
}

/** 原请求在发送前按主体保存；刷新后只能读取同一请求的确定回执。 */
export function useSkillCreation(
  scope: string,
  subject: () => string,
  identity: () => number,
  api: Api
) {
  const owner = computed(subject)
  const pending = ref<Pending>()
  const storageError = ref('')
  const key = (value: string) => `skillforge.skill-create:${scope}:${encodeURIComponent(value)}`
  let disposed = false
  onScopeDispose(() => {
    disposed = true
  })
  watch(
    [owner, identity],
    () => {
      pending.value = undefined
      storageError.value = ''
      if (!owner.value) return
      try {
        const raw = sessionStorage.getItem(key(owner.value))
        if (!raw) return
        const value = JSON.parse(raw) as Pending
        if (
          !value ||
          typeof value.capabilityKey !== 'string' ||
          !value.capabilityKey ||
          typeof value.name !== 'string' ||
          !value.name ||
          !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value.requestId)
        )
          throw Error('invalid')
        pending.value = value
      } catch {
        storageError.value = '无法读取原技能创建记录，请恢复浏览器存储后重新进入。'
      }
    },
    { immediate: true, flush: 'sync' }
  )

  function accept(
    value: SkillCreationReceipt | undefined,
    operation: Pending,
    currentOwner: string,
    version: number
  ) {
    if (
      disposed ||
      owner.value !== currentOwner ||
      identity() !== version ||
      pending.value?.requestId !== operation.requestId
    )
      return undefined
    if (
      !value ||
      value.request_id !== operation.requestId ||
      !/^[1-9][0-9]*$/.test(value.definition_id) ||
      !/^[1-9][0-9]*$/.test(value.draft_id) ||
      !Number.isSafeInteger(value.revision) ||
      value.revision < 1
    )
      return undefined
    try {
      sessionStorage.removeItem(key(currentOwner))
    } catch {
      storageError.value = '已取得创建回执，但无法清除本地记录；请恢复浏览器存储后重新核对。'
      return undefined
    }
    pending.value = undefined
    storageError.value = ''
    return value
  }

  async function create(payload: Metadata) {
    const currentOwner = owner.value,
      version = identity()
    if (!currentOwner || pending.value || storageError.value)
      throw Error('当前身份不可用或原创建请求待核对')
    const operation = {
      requestId: crypto.randomUUID(),
      capabilityKey: payload.capabilityKey,
      name: payload.name
    }
    try {
      sessionStorage.setItem(key(currentOwner), JSON.stringify(operation))
    } catch {
      storageError.value = '无法保存创建记录，尚未发送请求；请恢复浏览器存储。'
      throw Error(storageError.value)
    }
    pending.value = operation
    try {
      const receipt = await api.createWithReceipt(operation.requestId, payload)
      const accepted = accept(receipt, operation, currentOwner, version)
      if (!accepted && !disposed && owner.value === currentOwner && identity() === version)
        throw Error('未取得完整创建回执，请核对原请求')
      return accepted
    } catch (reason) {
      if (
        !disposed &&
        owner.value === currentOwner &&
        identity() === version &&
        reason instanceof HttpClientError &&
        reason.code === 'SKILL_CREATE_REJECTED'
      ) {
        try {
          sessionStorage.removeItem(key(currentOwner))
          pending.value = undefined
        } catch {
          storageError.value = '创建已被拒绝，但无法清除本地记录；请恢复浏览器存储。'
        }
      }
      throw reason
    }
  }
  async function check() {
    const operation = pending.value,
      currentOwner = owner.value,
      version = identity()
    if (!operation || !currentOwner) return undefined
    return accept(await api.creationReceipt(operation.requestId), operation, currentOwner, version)
  }
  return { pending, storageError, create, check }
}
