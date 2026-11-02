import { onScopeDispose } from 'vue'
import { useAuthStore } from '../stores/auth'

/** 异步读取与确认均绑定进入页面时的主体及授权版本。 */
export function useManagementIdentity() {
  const auth = useAuthStore()
  let active = true
  const snapshot = () =>
    JSON.stringify([
      auth.identityVersion,
      auth.user?.sub,
      auth.user?.tenant_id,
      auth.user?.authorization_version
    ])
  const initial = snapshot()
  onScopeDispose(() => {
    active = false
  })
  const current = () => active && snapshot() === initial
  const allowed = (permission: string) => current() && !!auth.user?.permissions.includes(permission)
  function requirePermission(permission: string) {
    if (!allowed(permission)) throw Error('当前账号或授权已变化，请重新进入页面')
  }
  return { current, allowed, requirePermission }
}
