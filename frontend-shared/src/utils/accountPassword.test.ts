import { describe, expect, it } from 'vitest'
import { accountPasswordError } from './accountPassword'

describe('账号密码Unicode与BCrypt字节边界', () => {
  it('按Unicode字符计数，要求英文字母和数字', () => {
    expect(accountPasswordError('Aa1' + '🙂'.repeat(8))).toBeDefined()
    expect(accountPasswordError('Aa1' + '🙂'.repeat(9))).toBeUndefined()
    expect(accountPasswordError('letterswithoutnumber')).toBeDefined()
    expect(accountPasswordError('123456789012')).toBeDefined()
  })
  it('中文及emoji恰好72 UTF-8字节可用，增加一个字符后拒绝', () => {
    const chinese = 'Aa1' + '界'.repeat(23),
      emoji = 'Aa1' + '🙂'.repeat(17) + 'z'
    expect(accountPasswordError(chinese)).toBeUndefined()
    expect(accountPasswordError(emoji)).toBeUndefined()
    expect(accountPasswordError(chinese + '界')).toBeDefined()
    expect(accountPasswordError(emoji + '🙂')).toBeDefined()
  })
})
