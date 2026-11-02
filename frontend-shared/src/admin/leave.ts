import { onScopeDispose } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, type RouteLocationNormalized } from 'vue-router'
import { message, confirm } from '../feedback'

interface LeaveProtection {
  dirty: () => boolean
  busy?: () => boolean
  discard?: () => void
  leaveMessage?: () => string | undefined
}
const protections = new Set<LeaveProtection>()
let pending: Promise<boolean> | undefined

export async function confirmPageLeave(): Promise<boolean> {
  // 首次确认绑定首次离开动作，后续导航不能借用确认结果改变目的地。
  if (pending) return false
  if ([...protections].some((item) => item.busy?.())) {
    message.warning('操作正在进行，请等待完成后离开。')
    return false
  }
  const changed = [...protections].filter((item) => item.dirty())
  if (!changed.length) return true
  pending = (async () => {
    try {
      const messages = changed.flatMap((item) => {
        const text = item.leaveMessage?.()
        return text ? [text] : []
      })
      if (
        !(await confirm({
          title: '离开页面',
          content: messages.length ? messages.join('\n') : '当前页面有未保存内容，离开后将丢弃。',
          confirmText: messages.length ? '确认离开' : '丢弃并离开',
          cancelText: '继续编辑'
        }))
      )
        return false
      if ([...protections].some((item) => item.busy?.())) return false
      changed.forEach((item) => {
        if (protections.has(item)) item.discard?.()
      })
      return true
    } catch (reason) {
      if (reason === 'cancel' || reason === 'close') return false
      throw reason
    } finally {
      pending = undefined
    }
  })()
  return pending
}

function confirmRouteUpdate(to: RouteLocationNormalized, from: RouteLocationNormalized) {
  return to.fullPath === from.fullPath ? true : confirmPageLeave()
}

/** 单页导航的保护集中在活动页面；密码、草稿不持久化到浏览器存储。 */
export function useUnsavedChanges(protection: LeaveProtection) {
  protections.add(protection)
  const beforeUnload = (event: BeforeUnloadEvent) => {
    if (!protection.dirty() && !protection.busy?.()) return
    event.preventDefault()
    event.returnValue = ''
  }
  window.addEventListener('beforeunload', beforeUnload)
  onBeforeRouteLeave(confirmPageLeave)
  onBeforeRouteUpdate(confirmRouteUpdate)
  onScopeDispose(() => {
    protections.delete(protection)
    window.removeEventListener('beforeunload', beforeUnload)
  })
  return { confirmLeave: confirmPageLeave }
}
