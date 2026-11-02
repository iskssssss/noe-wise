import { onScopeDispose, ref } from 'vue'
import { useAuthStore } from '../stores/auth'

/** 平台页面仅依据me返回的当前动作权限，确认及验证完成后再次在写请求前检查。 */
export function useActionPermissions() {
  const auth = useAuthStore()
  const active = ref(true)
  const version = auth.identityVersion
  const identity = () =>
    JSON.stringify([
      auth.user?.sub,
      auth.user?.tenant_id,
      auth.user?.authorization_version,
      auth.user?.mfa_state
    ])
  const initialIdentity = identity()
  onScopeDispose(() => {
    active.value = false
  })
  const allowed = (code: string) =>
    active.value &&
    version === auth.identityVersion &&
    initialIdentity === identity() &&
    (auth.user?.permissions.includes(code) ?? false)
  function requireAction(code: string) {
    if (!allowed(code)) throw new Error('当前账号没有该操作权限，请刷新授权资料')
  }
  async function checked<T>(code: string, operation: () => T): Promise<Awaited<T>> {
    requireAction(code)
    const result = await operation()
    // 换号、权限撤销或页面卸载后的旧写响应不能更新界面或显示一次性秘密。
    requireAction(code)
    return result
  }
  return { allowed, requireAction, checked }
}
