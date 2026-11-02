import { describe, expect, it } from 'vitest'
import { formatDate, formatDateTime, formatMonth, formatTime, toBeijingDateTime } from './datetime'

describe('统一日期展示', () => {
  it('以固定补零和24小时格式展示北京时间，不修改Date输入', () => {
    const value = new Date('2026-01-02T03:04:05.678+08:00')
    const before = value.getTime()
    expect(formatDateTime(value)).toBe('2026-01-02 03:04:05')
    expect(formatDateTime('2026-01-02T03:04:05.123456789')).toBe('2026-01-02 03:04:05')
    expect(formatDate(value)).toBe('2026-01-02')
    expect(formatMonth(value)).toBe('2026-01')
    expect(formatTime(value)).toBe('03:04:05')
    expect(value.getTime()).toBe(before)
  })

  it('将不同偏移表示的同一时刻投影为相同北京时间，跨日偏移仍生效', () => {
    expect(formatDateTime('2026-10-11T00:30:00+08:00')).toBe(formatDateTime('2026-10-10T16:30:00Z'))
    expect(formatDateTime('2026-10-10T17:02:03Z')).toBe('2026-10-11 01:02:03')
    expect(formatDateTime('2026-12-31T16:00:00Z')).toBe('2027-01-01 00:00:00')
  })

  it('日期和月份保留原精度，不产生时区偏移或补造时刻', () => {
    expect(formatDate('2024-02-29')).toBe('2024-02-29')
    expect(formatDateTime('2026-10-11')).toBe('2026-10-11')
    expect(formatMonth('2026-01')).toBe('2026-01')
    expect(formatDateTime('2026-01')).toBe('2026-01')
    expect(formatTime('2026-10-11')).toBe('—')
  })

  it('零毫秒时间戳是有效时间而非空值', () => {
    expect(formatDateTime(0)).toBe(formatDateTime('1970-01-01T00:00:00Z'))
    expect(formatDateTime(0)).toBe('1970-01-01 08:00:00')
  })

  it('日期控件的无时区选择按北京时间提交，保留实际时刻', () => {
    expect(toBeijingDateTime('2026-01-02 03:04:05')).toBe('2026-01-02T03:04:05+08:00')
    expect(toBeijingDateTime('2026-01-01T19:04:05Z')).toBeUndefined()
    expect(toBeijingDateTime('2026-01-02')).toBeUndefined()
    expect(toBeijingDateTime('2026-01')).toBeUndefined()
    expect(toBeijingDateTime(null)).toBeUndefined()
    expect(toBeijingDateTime('2026-02-30 03:04:05')).toBeUndefined()
  })

  it.each([
    null,
    undefined,
    '',
    ' ',
    'invalid',
    '2026/10/11',
    '2026-02-29',
    '2026-04-31',
    '2026-13',
    '0000-01-01',
    '2026-10-11T24:00:00Z',
    '2026-10-11T01:60:00Z',
    '2026-10-11T01:00:60Z',
    '2026-02-30T01:02:03+08:00',
    '2026-10-11T01:02:03+24:00',
    Number.NaN,
    Number.POSITIVE_INFINITY,
    new Date(Number.NaN)
  ])('空值或无效日期统一显示占位符：%s', (value) => {
    expect(formatDateTime(value)).toBe('—')
    expect(formatDate(value)).toBe('—')
    expect(formatMonth(value)).toBe('—')
    expect(formatTime(value)).toBe('—')
  })
})
