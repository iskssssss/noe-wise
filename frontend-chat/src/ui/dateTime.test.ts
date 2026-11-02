import { describe, expect, it } from 'vitest'
import { formatDateTime } from './dateTime'

describe('会话端日期展示', () => {
  it.each([null, undefined, '', '   ', 'invalid', '2026/10/11', NaN, Infinity, -Infinity])(
    '空或无效输入使用统一占位符：%s',
    (value) => {
      expect(formatDateTime(value)).toBe('—')
    }
  )

  it('无时区时间按北京时间解释，固定补零并保留秒精度', () => {
    expect(formatDateTime('2026-01-02T03:04:05')).toBe('2026-01-02 03:04:05')
    expect(formatDateTime('2026-01-02 03:04')).toBe('2026-01-02 03:04:00')
    expect(formatDateTime(' 2026-01-02T03:04:05.123456789 ')).toBe('2026-01-02 03:04:05')
    expect(formatDateTime('2026-01-02T03:04:05')).toBe(formatDateTime('2026-01-02T03:04:05+08:00'))
  })

  it.each([
    '2026-10-10T16:20:03Z',
    '2026-10-10T16:20:03.123456789Z',
    '2026-10-11T00:20:03+08:00',
    '2026-10-10T09:20:03-07:00'
  ])('同一时刻统一展示北京时间，纳秒输入保留秒精度：%s', (value) => {
    expect(formatDateTime(value)).toBe('2026-10-11 00:20:03')
  })

  it('有效 Date 和毫秒时间戳均展示北京时间，0 不是空值且不会修改 Date', () => {
    const date = new Date('2026-01-01T19:04:05.987Z')
    const original = date.getTime()
    expect(formatDateTime(date)).toBe('2026-01-02 03:04:05')
    expect(formatDateTime(original)).toBe('2026-01-02 03:04:05')
    expect(date.getTime()).toBe(original)
    expect(formatDateTime(0)).toBe('1970-01-01 08:00:00')
    expect(formatDateTime(new Date(0))).toBe('1970-01-01 08:00:00')
    expect(formatDateTime(new Date(NaN))).toBe('—')
    expect(formatDateTime(8.64e15 + 1)).toBe('—')
  })

  it('使用命名时区的历史偏移，不对绝对时刻直接加八小时', () => {
    expect(formatDateTime('1900-01-01T00:00:00Z')).toBe('1900-01-01 08:05:43')
  })

  it('纯日期和月份保留日历精度，不补造午夜或执行时区转换', () => {
    expect(formatDateTime('2026-10-11')).toBe('2026-10-11')
    expect(formatDateTime('2026-01')).toBe('2026-01')
    expect(formatDateTime(' 2024-02-29 ')).toBe('2024-02-29')
    expect(formatDateTime('2000-02-29')).toBe('2000-02-29')
    expect(formatDateTime('0001-01-01')).toBe('0001-01-01')
    expect(formatDateTime('0099-01-02')).toBe('0099-01-02')
  })

  it.each([
    '0000-01-01',
    '2026-00',
    '2026-13',
    '2026-01-00',
    '2026-02-29',
    '1900-02-29',
    '2026-04-31',
    '2026-02-30T03:04:05Z',
    '2026-13-02T03:04:05Z',
    '2026-01-02T24:00:00Z',
    '2026-01-02T03:60:00Z',
    '2026-01-02T03:04:60Z',
    '2026-01-02T03:04:05+24:00',
    '2026-01-02T03:04:05+08:60',
    '2026-01-02T03:04:05+0800',
    '2026-01-02T03:04:05.1234567890Z',
    '2026-01-02T03:04.123Z'
  ])('拒绝溢出日历、时分秒或非接口时间格式：%s', (value) => {
    expect(formatDateTime(value)).toBe('—')
  })
})
