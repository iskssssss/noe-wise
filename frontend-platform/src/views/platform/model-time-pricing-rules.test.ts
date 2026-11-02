import { expect, it } from 'vitest'
import { emptyTimePricingPolicy, timePricingErrors } from './model-time-pricing'
function validPolicy() {
  return {
    ...emptyTimePricingPolicy(),
    time_zone: 'Asia/Shanghai',
    valid_from: '2032-01-01',
    valid_through: '2032-12-31'
  }
}
it('秒与分钟写法不同的邻接时段没有重叠，原纳秒字段保持不变', () => {
  const policy = validPolicy()
  policy.peak_periods = [
    { start: '09:00:00', end: '12:00:00' },
    { start: '12:00', end: '14:00:00.000000001' }
  ]
  expect(timePricingErrors(policy)).toEqual({})
  expect(policy.peak_periods[1]!.end).toBe('14:00:00.000000001')
})
it('纳秒精度的一纳秒交叠被拒绝，同一时刻不同写法不构成有效时段', () => {
  const policy = validPolicy()
  policy.peak_periods = [
    { start: '09:00', end: '12:00:00.000000001' },
    { start: '12:00:00', end: '13:00' }
  ]
  expect(timePricingErrors(policy)).toHaveProperty('policy.peak_periods.1')
  policy.peak_periods = [{ start: '12:00', end: '12:00:00.000000000' }]
  expect(timePricingErrors(policy)).toHaveProperty('policy.peak_periods.0')
})
it('倍率最大精度、零和未知合法，超出数据库容量时拒绝', () => {
  const policy = validPolicy()
  policy.peak_multiplier = '999999999999.999999999999'
  policy.off_peak_multiplier = '0'
  expect(timePricingErrors(policy)).toEqual({})
  policy.off_peak_multiplier = null
  expect(timePricingErrors(policy)).toEqual({})
  policy.peak_multiplier = '1000000000000'
  expect(timePricingErrors(policy)).toHaveProperty('policy.peak_multiplier')
  policy.peak_multiplier = '0.0000000000001'
  expect(timePricingErrors(policy)).toHaveProperty('policy.peak_multiplier')
})
it('无效日期、覆盖范围外例外日期和峰谷相交不能发布', () => {
  const policy = validPolicy()
  policy.valid_from = '2032-02-30'
  expect(timePricingErrors(policy)).toHaveProperty('policy.valid_from')
  policy.valid_from = '2032-01-01'
  policy.peak_date_ranges = [{ from: '2032-03-01', through: '2032-03-05' }]
  policy.off_peak_date_ranges = [{ from: '2032-03-05', through: '2032-03-06' }]
  expect(timePricingErrors(policy)).toHaveProperty('policy.off_peak_date_ranges.0')
  policy.off_peak_date_ranges = [{ from: '2033-01-01', through: '2033-01-02' }]
  expect(timePricingErrors(policy)).toHaveProperty('policy.off_peak_date_ranges.0')
})
