import type { ModelTimePricingPolicy, ModelPricingWeekday } from '../../api'

export const pricingWeekdays: { value: ModelPricingWeekday; label: string }[] = [
  { value: 'MONDAY', label: '周一' },
  { value: 'TUESDAY', label: '周二' },
  { value: 'WEDNESDAY', label: '周三' },
  { value: 'THURSDAY', label: '周四' },
  { value: 'FRIDAY', label: '周五' },
  { value: 'SATURDAY', label: '周六' },
  { value: 'SUNDAY', label: '周日' }
]
export function emptyTimePricingPolicy(): ModelTimePricingPolicy {
  return {
    time_zone: '',
    valid_from: '',
    valid_through: '',
    peak_weekdays: [],
    peak_periods: [],
    peak_date_ranges: [],
    off_peak_date_ranges: [],
    peak_multiplier: '1',
    off_peak_multiplier: null,
    applicable_models: [],
    source_url: null,
    source_version: null,
    calendar_source_url: null
  }
}
export function cloneTimePricingPolicy(value: ModelTimePricingPolicy): ModelTimePricingPolicy {
  return {
    ...value,
    peak_weekdays: [...value.peak_weekdays],
    peak_periods: value.peak_periods.map((v) => ({ ...v })),
    peak_date_ranges: value.peak_date_ranges.map((v) => ({ ...v })),
    off_peak_date_ranges: value.off_peak_date_ranges.map((v) => ({ ...v })),
    applicable_models: value.applicable_models.map((v) => ({ ...v }))
  }
}
function validDate(value: string) {
  return (
    /^\d{4}-\d{2}-\d{2}$/.test(value) &&
    Number.isFinite(Date.parse(value)) &&
    new Date(value).toISOString().slice(0, 10) === value
  )
}
export function timePricingErrors(
  policy: ModelTimePricingPolicy,
  prefix = 'policy'
): Record<string, string> {
  const errors: Record<string, string> = {}
  const fail = (field: string, text: string) => {
    errors[prefix + '.' + field] = text
  }
  try {
    new Intl.DateTimeFormat('zh-CN', { timeZone: policy.time_zone })
  } catch {
    fail('time_zone', '请填写有效的 IANA 时区，例如 Asia/Shanghai')
  }
  if (!policy.time_zone.trim()) fail('time_zone', '请填写计价时区')
  if (!validDate(policy.valid_from)) fail('valid_from', '请选择有效的覆盖开始日期')
  if (!validDate(policy.valid_through) || policy.valid_through < policy.valid_from)
    fail('valid_through', '覆盖结束日期不得早于开始日期')
  for (const key of ['peak_multiplier', 'off_peak_multiplier'] as const) {
    const value = policy[key]
    if (value !== null && !/^(?:0|[1-9]\d{0,11})(?:\.\d{1,12})?$/.test(value))
      fail(key, '倍率须为非负十进制数，整数、小数部分分别最多 12 位；留空表示未知')
  }
  const validTime = (value: string) =>
    /^(?:[01]\d|2[0-3]):[0-5]\d(?::[0-5]\d(?:\.\d{1,9})?)?$/.test(value)
  const timeKey = (value: string) => {
    const [hour = '', minute = '', rest = '00'] = value.split(':')
    const [second = '00', fraction = ''] = rest.split('.')
    return hour + minute + second + fraction.padEnd(9, '0')
  }
  policy.peak_periods.forEach((period, index) => {
    if (
      !validTime(period.start) ||
      !validTime(period.end) ||
      timeKey(period.start) >= timeKey(period.end)
    )
      fail('peak_periods.' + index, '请填写同日内开始早于结束的时段；跨午夜请拆为两段')
    else if (
      policy.peak_periods.some(
        (other, otherIndex) =>
          otherIndex < index &&
          timeKey(period.start) < timeKey(other.end) &&
          timeKey(other.start) < timeKey(period.end)
      )
    )
      fail('peak_periods.' + index, '峰时段不能重叠')
  })
  for (const key of ['peak_date_ranges', 'off_peak_date_ranges'] as const) {
    policy[key].forEach((range, index) => {
      if (
        !validDate(range.from) ||
        !validDate(range.through) ||
        range.from > range.through ||
        range.from < policy.valid_from ||
        range.through > policy.valid_through
      )
        fail(key + '.' + index, '日期范围须有效且位于规则覆盖日期内')
      else if (
        policy[key].some(
          (other, otherIndex) =>
            otherIndex < index && range.from <= other.through && other.from <= range.through
        )
      )
        fail(key + '.' + index, '同类日期范围不能重叠')
      else if (
        key === 'off_peak_date_ranges' &&
        policy.peak_date_ranges.some(
          (other) => range.from <= other.through && other.from <= range.through
        )
      )
        fail(key + '.' + index, '同一日期不能同时指定为峰日和谷日')
    })
  }
  if (policy.source_version !== null && Array.from(policy.source_version).length > 256)
    fail('source_version', '来源版本最多 256 个字符')
  policy.applicable_models.forEach((scope, index) => {
    for (const key of ['provider_id', 'service_id', 'profile_version', 'model_id'] as const) {
      const value = scope[key]
      if (value !== null && !value.trim())
        fail(`applicable_models.${index}.${key}`, '请填写有效标识，或清空表示不限')
      else if (key !== 'model_id' && value !== null && Array.from(value).length > 128)
        fail(`applicable_models.${index}.${key}`, '提供方、服务及目录版本标识最多 128 个字符')
    }
  })
  return errors
}
