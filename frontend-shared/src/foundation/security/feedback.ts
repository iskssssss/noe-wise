import { HttpClientError } from '../../utils/http'

type AuthAction =
  'login' | 'enroll' | 'verify' | 'requestRecovery' | 'completeRecovery' | 'activate'

/** 认证入口按当前动作给出下一步，不套用业务写入的核对提示。 */
export function authErrorText(reason: unknown, action: AuthAction): string {
  if (!(reason instanceof HttpClientError))
    return reason instanceof Error ? reason.message : '操作未完成，请稍后重试'
  if (reason.status === 401) {
    if (action === 'login') return '账号或密码不正确，请检查后重新登录'
    if (action === 'verify') return '验证码或恢复码无效，请检查后重新输入'
    if (action === 'completeRecovery' || action === 'activate')
      return '邮件凭据无效或已过期，请检查凭据或获取新的邮件'
  }
  if (reason.status === 0 || reason.status >= 500) {
    const connection = reason.status === 0 ? '连接中断，请检查网络。' : '服务暂时不可用。'
    const next: Record<AuthAction, string> = {
      login: '暂时无法登录，请稍后重试',
      enroll: '暂时无法创建验证器密钥，请稍后重试',
      verify: '暂时无法完成验证，请稍后重新验证',
      requestRecovery: '暂时无法确认恢复邮件是否已发送，请先查看登记邮箱，未收到时稍后再试',
      completeRecovery: '暂时无法确认密码是否已设置，请先尝试使用新密码登录',
      activate: '暂时无法确认邀请是否已接受，请先尝试登录'
    }
    return connection + next[action]
  }
  return reason.userMessage
}
