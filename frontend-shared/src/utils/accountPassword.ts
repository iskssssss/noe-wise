/** 账号密码与Java BCrypt受理契约一致，Unicode字符按code point计数，UTF-8不超过72字节。 */
export function accountPasswordError(value: string): string | undefined {
  if (
    Array.from(value).length < 12 ||
    new TextEncoder().encode(value).length > 72 ||
    !/[A-Za-z]/.test(value) ||
    !/[0-9]/.test(value)
  ) {
    return '密码至少 12 个字符且包含英文字母和数字，UTF-8 编码最多 72 字节'
  }
  return undefined
}

/** 表单与真正提交入口使用同一校验，不使用UTF-16字符串长度作为Unicode字符数。 */
export const accountPasswordRule = {
  trigger: 'blur',
  validator: (_rule: unknown, value: string, callback: (error?: Error) => void) => {
    const error = accountPasswordError(value ?? '')
    callback(error ? new Error(error) : undefined)
  }
}
