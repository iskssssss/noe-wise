import { formatDateTime } from '@skillforge/shared/utils/datetime'
import type { SessionMessage, TokenTotals } from '../../api/sessionObservation'

/** 仅推导单次 attempt 的精确已报字段；缺值不补零，缓存子集不再加进合计。 */
export function attemptUsageMetrics(totals?: TokenTotals | null): {
  total: string | null
  uncachedInput: string | null
  hitRatio: string
} {
  const integer = (value?: string | null) =>
    value !== null && value !== undefined && /^(0|[1-9]\d*)$/.test(value) ? BigInt(value) : null
  const input = integer(totals?.prompt_tokens),
    output = integer(totals?.completion_tokens),
    cached = integer(totals?.cached_input_tokens)
  const paired = input !== null && cached !== null && cached <= input
  let hitRatio = '—'
  if (paired) {
    if (input === 0n) hitRatio = '不适用'
    else {
      const percentage = (cached * 20000n + input) / (input * 2n)
      hitRatio = `${percentage / 100n}.${String(percentage % 100n).padStart(2, '0')}%`
    }
  }
  return {
    total: input !== null && output !== null ? String(input + output) : null,
    uncachedInput: paired ? String(input - cached) : null,
    hitRatio
  }
}

/** 序号比较保持十进制精度，分页排序不经 JavaScript Number。 */
export function compareSequence(left: string, right: string): number {
  if (/^\d+$/.test(left) && /^\d+$/.test(right)) {
    const a = BigInt(left),
      b = BigInt(right)
    return a < b ? -1 : a > b ? 1 : 0
  }
  return left.localeCompare(right)
}

/** 续页按业务 ID 去重，摘要仍由服务端提供。 */
export function mergeById<T>(before: T[], incoming: T[], id: (value: T) => string): T[] {
  const values = new Map(before.map((value) => [id(value), value]))
  incoming.forEach((value) => values.set(id(value), value))
  return [...values.values()]
}

/** 消息更新保留服务端状态与 version，不拼接正文或覆盖更新的版本。 */
export function mergeMessages(
  before: SessionMessage[],
  incoming: SessionMessage[]
): SessionMessage[] {
  const values = new Map(before.map((value) => [value.message_id, value]))
  incoming.forEach((value) => {
    const old = values.get(value.message_id)
    if (
      !old ||
      !old.message_version ||
      !value.message_version ||
      compareSequence(value.message_version, old.message_version) >= 0
    )
      values.set(value.message_id, value)
  })
  return [...values.values()]
}

/** 时间跨度来自原记录，不能宣称为供应商 latency 或实际执行耗时。 */
export function recordedSpan(from?: string | null, through?: string | null): string {
  if (!from || !through) return '未记录'
  const duration = Date.parse(through) - Date.parse(from)
  if (!Number.isFinite(duration) || duration < 0) return '未记录'
  return duration < 1000 ? `${duration} ms` : `${(duration / 1000).toFixed(2)} s`
}

/** 按北京时间统一展示，空值或无效日期使用共享占位符。 */
export function observationTime(value?: string | null): string {
  return formatDateTime(value)
}

/** 保留未知原状态；只翻译明确公开状态，不从消息文本判成功。 */
export function messageState(value?: string | null): string {
  const labels: Record<string, string> = {
    streaming: '生成中',
    completed: '已完成',
    failed: '失败',
    cancelled: '已取消',
    outcome_unknown: '结果待核实',
    IN_PROGRESS: '进行中',
    NONE: '无最终回答',
    REPORTED: '已报告',
    UNKNOWN: '待核实',
    PARTIAL: '部分报告',
    COMPLETE: '完整报告',
    NO_CALLS: '无调用',
    ZERO: '明确零用量',
    COMPLETED: '已完成',
    FAILED: '失败',
    CANCELLED: '已取消',
    OUTCOME_UNKNOWN: '结果待核实'
  }
  return value ? (labels[value] ?? value) : '未记录'
}
