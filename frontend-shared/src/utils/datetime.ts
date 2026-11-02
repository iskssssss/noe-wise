/** 后台日期展示输入；数值遵循 JavaScript 毫秒时间戳，不影响接口原值。 */
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

/** 先验证日历边界，避免 Date 将 2 月 30 日等无效值滚动到下个月。 */
function validCalendar(year: number, month: number, day = 1): boolean {
  if (year < 1 || month < 1 || month > 12 || day < 1) return false
  const leap = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0)
  const days = [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31]
  return day <= days[month - 1]!
}

/** 仅解析明确的接口时间格式；无偏移的业务日期时间按北京时间解释。 */
function parseTimestamp(value: DateTimeInput): Date | undefined {
  if (value === null || value === undefined || value === '') return undefined
  if (value instanceof Date) return Number.isFinite(value.getTime()) ? value : undefined
  if (typeof value === 'number') {
    const date = new Date(value)
    return Number.isFinite(date.getTime()) ? date : undefined
  }
  const match = timestampPattern.exec(value.trim())
  if (!match) return undefined
  const [, year, month, day, hour, minute, second = '00', fraction = '', offset = '+08:00'] = match
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
  // UI 精度到秒，先标准化小数位供浏览器解析，保留原始值由调用方负责。
  const milliseconds = fraction ? `.${fraction.slice(0, 3).padEnd(3, '0')}` : ''
  const date = new Date(
    `${year}-${month}-${day}T${hour}:${minute}:${second}${milliseconds}${offset}`
  )
  return Number.isFinite(date.getTime()) ? date : undefined
}

/** 统一纯展示投影；纯日期/月份保留日历精度，不补造时刻或参与时区转换。 */
function displayDate(
  value: DateTimeInput,
  precision: 'datetime' | 'date' | 'month' | 'time'
): string {
  if (typeof value === 'string') {
    const text = value.trim()
    const calendar = calendarPattern.exec(text)
    if (calendar) {
      if (!validCalendar(Number(calendar[1]), Number(calendar[2]), Number(calendar[3] ?? 1))) {
        return EMPTY_DATE
      }
      return precision === 'time' ? EMPTY_DATE : precision === 'month' ? text.slice(0, 7) : text
    }
  }
  const date = parseTimestamp(value)
  if (!date) return EMPTY_DATE
  // 显式指定业务时区，避免浏览器或测试进程的 TZ 改变同一条记录的日期。
  const parts = Object.fromEntries(
    beijingFormatter.formatToParts(date).map(({ type, value }) => [type, value])
  )
  const month = `${parts.year!.padStart(4, '0')}-${parts.month}`
  if (precision === 'month') return month
  const day = `${month}-${parts.day}`
  if (precision === 'date') return day
  const time = `${parts.hour}:${parts.minute}:${parts.second}`
  return precision === 'time' ? time : `${day} ${time}`
}

/** 展示北京时间 YYYY-MM-DD HH:mm:ss；未知或无效值为统一占位符。 */
export function formatDateTime(value: DateTimeInput): string {
  return displayDate(value, 'datetime')
}

/** 展示日历日期 YYYY-MM-DD；不把纯日期当作 UTC 午夜转换。 */
export function formatDate(value: DateTimeInput): string {
  return displayDate(value, 'date')
}

/** 展示月份 YYYY-MM，供账期及月份控件标签使用。 */
export function formatMonth(value: DateTimeInput): string {
  return displayDate(value, 'month')
}

/** 展示已有时间戳的北京时刻 HH:mm:ss；纯日期没有时刻，保持未知。 */
export function formatTime(value: DateTimeInput): string {
  return displayDate(value, 'time')
}

/** 仅将日期控件的无偏移墙上时间编码为 +08:00，避免对已有绝对时刻二次换算。 */
export function toBeijingDateTime(value: string | null | undefined): string | undefined {
  const text = value?.trim()
  if (!text || !/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/.test(text) || !parseTimestamp(text)) {
    return undefined
  }
  return `${text.replace(' ', 'T')}+08:00`
}
