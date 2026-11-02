import type {
  ObservationContentRef,
  ObservationNode,
  TokenTotals,
  TrajectoryCostCoverage
} from './types'

export interface ObservationTreeItem extends ObservationNode {
  children: ObservationTreeItem[]
}

/** 祖先骨架和续页按稳定身份合并；完整节点覆盖同身份骨架，不根据时间猜父级。 */
export function observationTree(
  items: ObservationNode[],
  ancestors: ObservationNode[] = []
): ObservationTreeItem[] {
  const byId = new Map<string, ObservationTreeItem>()
  for (const node of [...ancestors, ...items]) byId.set(node.node_id, { ...node, children: [] })
  const roots: ObservationTreeItem[] = []
  for (const node of byId.values()) {
    const parent = node.parent_node_id ? byId.get(node.parent_node_id) : undefined
    const visited = new Set([node.node_id])
    let cursor = parent
    let cyclic = false
    while (cursor) {
      if (visited.has(cursor.node_id)) {
        cyclic = true
        break
      }
      visited.add(cursor.node_id)
      cursor = cursor.parent_node_id ? byId.get(cursor.parent_node_id) : undefined
    }
    if (parent && !cyclic) parent.children.push(node)
    else roots.push(node)
  }
  return roots
}

export function formatObservationTokens(value?: string | null): string {
  return value !== undefined && value !== null && /^(0|[1-9]\d*)$/.test(value)
    ? value.replace(/\B(?=(\d{3})+(?!\d))/g, ',')
    : '未记录'
}

/** 耗时来自服务端实测字段，使用整数格式化，缺值不以事件时间或当前时钟补齐。 */
export function formatObservationDuration(value?: string | null): string {
  if (value === undefined || value === null || !/^(0|[1-9]\d*)$/.test(value)) return '未记录'
  const milliseconds = BigInt(value)
  if (milliseconds < 1000n) return `${milliseconds} ms`
  const hundredths = (milliseconds + 5n) / 10n
  return `${hundredths / 100n}.${String(hundredths % 100n).padStart(2, '0')} s`
}

/** 输入、输出主字段都由供应商报告时才给出合计，缓存与推理子集不重复相加。 */
export function observationTokenTotal(totals?: TokenTotals | null): string | null {
  if (
    !totals?.prompt_tokens ||
    !totals.completion_tokens ||
    !/^(0|[1-9]\d*)$/.test(totals.prompt_tokens) ||
    !/^(0|[1-9]\d*)$/.test(totals.completion_tokens)
  )
    return null
  return String(BigInt(totals.prompt_tokens) + BigInt(totals.completion_tokens))
}

/** 成本契约使用十进制分；展示只移动小数点，不定价、不转浮点或舍去亚分。 */
export function formatObservationMoney(minorUnits?: string | null): string {
  if (typeof minorUnits !== 'string') return '未提供'
  const match = /^(-?)(0|[1-9]\d*)(?:\.(\d+))?$/.exec(minorUnits)
  if (!match) return '未提供'
  const whole = match[2]!.padStart(3, '0')
  const major = whole.slice(0, -2).replace(/\B(?=(\d{3})+(?!\d))/g, ',')
  const fraction = (whole.slice(-2) + (match[3] ?? '')).replace(/0+$/, '').padEnd(2, '0')
  const sign = match[1] && /[1-9]/.test(match[2]! + (match[3] ?? '')) ? '-' : ''
  return `${sign}${major}.${fraction}`
}

export function formatObservationCosts(amounts?: Record<string, string> | null): string {
  const values = Object.entries(amounts ?? {})
  return values.length
    ? values.map(([currency, amount]) => `${currency} ${formatObservationMoney(amount)}`).join('；')
    : '金额未提供'
}

/** 金额是否确认与用量是否报告分别表达，缺失费用记录不能补成零。 */
export function observationCostCoverage(coverage?: TrajectoryCostCoverage | null): string {
  if (!coverage) return '费用状态未提供'
  const labels: Record<TrajectoryCostCoverage['status'], string> = {
    NO_COSTS: '暂无费用记录',
    COMPLETE: '全部费用已确认',
    PARTIAL: '部分费用未确认',
    UNKNOWN: '费用尚未确认'
  }
  const details = [
    ...(coverage.unconfirmed_events > 0 ? [`${coverage.unconfirmed_events} 条费用待确认`] : []),
    ...(coverage.missing_model_attempts > 0
      ? [`${coverage.missing_model_attempts} 次模型调用缺少费用记录`]
      : [])
  ]
  return labels[coverage.status] + (details.length ? `（${details.join('；')}）` : '')
}

const availabilityLabels: Record<string, string> = {
  FULL: '已记录',
  AVAILABLE: '已记录',
  PARTIAL: '部分记录',
  REDACTED: '内容受限',
  RESTRICTED: '内容受限',
  NOT_RECORDED: '未记录',
  NOT_RETURNED: '未返回',
  UNKNOWN: '可用性未记录'
}

export function observationAvailability(value?: string | null): string {
  return value ? (availabilityLabels[value] ?? value) : '未记录'
}

export function canReadObservationContent(value: ObservationContentRef): boolean {
  return Boolean(value.ref) && ['FULL', 'AVAILABLE', 'PARTIAL'].includes(value.availability)
}

export function observationCoverage(value?: string | null): string {
  const labels: Record<string, string> = {
    COMPLETE: '记录完整',
    PARTIAL: '记录不完整',
    NOT_RECORDED: '尚无观测记录',
    RESTRICTED: '部分记录受限',
    NO_CALLS: '无模型调用',
    UNKNOWN: '用量待核实',
    REPORTED: '用量已报告',
    ZERO: '明确零用量'
  }
  return value ? (labels[value] ?? value) : '未记录'
}
