import { describe, expect, it } from 'vitest'
import { catalogDecimalIdentity } from './decimalIdentity'
describe('目录能力身份精度边界', () => {
  it('保留超过2^53的十进制字符串，不参与数值计算', () => {
    expect(catalogDecimalIdentity('9223372036854775807', '定义')).toBe('9223372036854775807')
    expect(catalogDecimalIdentity('9223372036854775806', '版本')).toBe('9223372036854775806')
  })
  it('任何number身份或未选空值均不能成为有效目录身份', () => {
    expect(() => catalogDecimalIdentity(101, '定义')).toThrow('能力标识无效')
    expect(() => catalogDecimalIdentity(Number.MAX_SAFE_INTEGER + 2, '定义')).toThrow(
      '能力标识无效'
    )
    expect(() => catalogDecimalIdentity('', '定义')).toThrow('能力标识无效')
  })
})
