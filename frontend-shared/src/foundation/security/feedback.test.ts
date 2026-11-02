import { describe, expect, it } from 'vitest'
import { HttpClientError } from '../../utils/http'
import { authErrorText } from './feedback'

describe('认证失败提供当前动作的下一步', () => {
  it('未登录时的401提示检查凭据，而非登录状态过期', () => {
    const reason = new HttpClientError({ status: 401, userMessage: '登录状态已失效，请重新登录' })
    expect(authErrorText(reason, 'login')).toBe('账号或密码不正确，请检查后重新登录')
    expect(authErrorText(reason, 'verify')).toContain('验证码或恢复码无效')
  })
  it('登录服务不可用可重新登录，恢复邮件结果未知则先查邮箱', () => {
    const reason = new HttpClientError({ status: 503, userMessage: '已提交的操作请先核对结果' })
    expect(authErrorText(reason, 'login')).toBe('服务暂时不可用。暂时无法登录，请稍后重试')
    expect(authErrorText(reason, 'requestRecovery')).toContain('先查看登记邮箱')
    expect(authErrorText(reason, 'completeRecovery')).toContain('先尝试使用新密码登录')
  })
})
