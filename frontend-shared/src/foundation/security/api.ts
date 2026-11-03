import type { RequestOptions } from '../../utils/http'

/** 两个账号体系共用传输类型；令牌读取和刷新仍归宿主现有客户端。 */
export interface SecurityAuthTokens {
  access_token: string
  refresh_token?: string
  token_type: string
  expires_in: number
  tenant_id?: string
  username: string
  roles: string[]
  permissions: string[]
  authorization_version: number
  mfa_state: 'enrollment' | 'challenge' | 'full'
  session_id: string
}
/** 当前授权与MFA状态；ID保持十进制字符串。 */
export interface SecurityAuthUser {
  sub: string
  tenant_id?: string
  username: string
  roles: string[]
  permissions: string[]
  authorization_version: number
  mfa_state: 'enrollment' | 'challenge' | 'full'
  session_id: string
}
/** 当前安全状态。 */
export interface SecurityStatus {
  enabled: boolean
  required: boolean
  mfa_state: string
  recovery_codes_remaining: number
  verified_at?: string
}
/** 已验证器绑定和恢复码一次性交付。 */
export interface SecurityEnabled {
  recovery_codes: string[]
  authentication: SecurityAuthTokens
}
/** 设备列表不包含凭据。 */
export interface SecurityDevice {
  id: string
  device_label: string
  created_at: string
  expires_at: string
  verified_at?: string
  current: boolean
}
/** 宿主提供的typed请求函数，不另取token。 */
export type SecurityGet = <T>(path: string) => Promise<T>
/** 宿主提供的typed写入函数。 */
export type SecurityPost = <T>(path: string, body?: unknown, options?: RequestOptions) => Promise<T>
/** 扩展已有auth namespace；prefix由宿主固定，不从用户输入取得。 */
export function createSecurityAuthApi(get: SecurityGet, post: SecurityPost, prefix: string) {
  return {
    logout: () => post<{ status: string }>(prefix + '/logout'),
    status: () => get<SecurityStatus>(prefix + '/mfa/status'),
    enroll: () => post<{ secret: string; otpauth_uri: string }>(prefix + '/mfa/enroll'),
    enable: (code: string) => post<SecurityEnabled>(prefix + '/mfa/enable', { code }),
    verify: (code: string) => post<SecurityAuthTokens>(prefix + '/mfa/verify', { code }),
    requestRecovery: (body: { tenant_slug?: string; username: string }) =>
      post<{ status: string }>(prefix + '/recovery/request', body, { anonymous: true }),
    completeRecovery: (body: { token: string; new_password: string }) =>
      post<{ status: string }>(prefix + '/recovery/complete', body, { anonymous: true }),
    disable: () => post<{ status: string }>(prefix + '/mfa/disable'),
    activate: (body: { token: string; new_password: string }) =>
      post<{ status: string }>(prefix + '/invite/activate', body, { anonymous: true })
  }
}
/** 当前安全管理客户端，直接使用宿主请求函数。 */
export function createSecurityApi(get: SecurityGet, post: SecurityPost, prefix: string) {
  return {
    status: () => get<SecurityStatus>(prefix + '/status'),
    sessions: () => get<SecurityDevice[]>(prefix + '/sessions'),
    revoke: (session_id: string) =>
      post<{ status: string }>(prefix + '/sessions/revoke', { session_id }),
    resetMemberMfa: (member_id: string) =>
      post<{ status: string }>(prefix + '/members/reset-mfa', { member_id }),
    revokeMember: (member_id: string) =>
      post<{ status: string }>(prefix + '/members/revoke', { member_id }),
    regenerateCodes: () => post<string[]>(prefix + '/recovery-codes'),
    policy: () => get<{ require_all_mfa: boolean }>(prefix + '/policy'),
    setPolicy: (require_all_mfa: boolean) =>
      post<{ require_all_mfa: boolean }>(prefix + '/policy', { require_all_mfa }),
    invite: (member_id: string) => post<{ status: string }>(prefix + '/invitations', { member_id })
  }
}
/** 组件仅依赖现有客户端函数集合。 */
export type SecurityAuthApi = ReturnType<typeof createSecurityAuthApi>
/** 安全管理页typed契约。 */
export type SecurityApi = ReturnType<typeof createSecurityApi>

/** 旧认证请求已失去当前会话的更新权，页面应静默丢弃其结果。 */
export class AuthSessionChangedError extends Error {
  constructor() {
    super('登录身份已变化，请重新加载')
    this.name = 'AuthSessionChangedError'
  }
}
