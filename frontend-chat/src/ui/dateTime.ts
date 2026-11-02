/** 会话端独立持有日期展示投影；数值采用 JavaScript 毫秒时间戳。 */
export type DateTimeInput = string | number | Date | null | undefined

const EMPTY_DATE = '—'
const calendarPattern = /^(\d{4})-(\d{2})(?:-(\d{2}))?$/
const timestampPattern =
  /^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,9}))?)?(Z|[+-]\d{2}:\d{2})?$/
const beijingFormatter = new Intl.DateTimeFormat('en-GB', {
  timeZone: 'Asia/Shanghai',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hourCycle: 'h23'
})

/** 先验证日历边界，避免 Date 将无效日期滚动到下个月。 */
function validCalendar(year: number, month: number, day = 1): boolean {
  if (year < 1 || month < 1 || month > 12 || day < 1) return false
  const leap = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0)
  const days = [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31]
  return day <= days[month - 1]!
}

/** 绝对时刻按北京时间展示，无偏移日期时间按 +08:00 解释，不改变原值。 */
function parseTimestamp(value: DateTimeInput): Date | undefined {
  if (value === null || value === undefined || value === '') return undefined
  if (value instanceof Date) return Number.isFinite(value.getTime()) ? value : undefined
  if (typeof value === 'number') {
    const date = new Date(value)
    return Number.isFinite(date.getTime()) ? date : undefined
  }
  const match = timestampPattern.exec(value.trim())
  if (!match) return undefined
  const [, year, month, day, hour, minute, second = '00', fraction = '', offset = ''] = match
  if (
    !validCalendar(Number(year), Number(month), Number(day)) ||
    Number(hour) > 23 ||
    Number(minute) > 59 ||
    Number(second) > 59
  )
    return undefined
  if (offset && offset !== 'Z') {
    const [offsetHour, offsetMinute] = offset.slice(1).split(':').map(Number)
    if (offsetHour! > 23 || offsetMinute! > 59) return undefined
  }
  // 展示精度到秒，标准化小数位只用于解析，接口和存储仍保留原值。
  const milliseconds = fraction ? `.${fraction.slice(0, 3).padEnd(3, '0')}` : ''
  const date = new Date(
    `${year}-${month}-${day}T${hour}:${minute}:${second}${milliseconds}${offset || '+08:00'}`
  )
  return Number.isFinite(date.getTime()) ? date : undefined
}

/** 北京时间固定展示 YYYY-MM-DD HH:mm:ss；纯日期或月份保留精度，无效值展示占位符。 */
export function formatDateTime(value: DateTimeInput): string {
  if (typeof value === 'string') {
    const text = value.trim()
    const calendar = calendarPattern.exec(text)
    if (calendar) {
      return validCalendar(Number(calendar[1]), Number(calendar[2]), Number(calendar[3] ?? 1))
        ? text
        : EMPTY_DATE
    }
  }
  const date = parseTimestamp(value)
  if (!date) return EMPTY_DATE
  const parts = beijingFormatter.formatToParts(date)
  const field = (type: Intl.DateTimeFormatPartTypes) =>
    parts.find((part) => part.type === type)!.value
  const day = `${field('year').padStart(4, '0')}-${field('month')}-${field('day')}`
  const time = `${field('hour')}:${field('minute')}:${field('second')}`
  return `${day} ${time}`
}
