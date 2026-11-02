import { describe, expect, it } from 'vitest'
import {
  canReadObservationContent,
  formatObservationCosts,
  formatObservationDuration,
  formatObservationMoney,
  formatObservationTokens,
  observationCostCoverage,
  observationTokenTotal,
  observationTree
} from './presentation'
import type { TokenTotals } from './types'
import { node } from './observation.test-fixture'

describe('只读观测展示', () => {
  it('按真实父节点连接跨页祖先，续页覆盖骨架且保持未关联节点可见', () => {
    const entry = { ...node('entry'), kind: 'ENTRY' as const }
    const step = { ...node('step', 'entry'), kind: 'STEP' as const }
    const actual = { ...node('tool', 'step'), name: '实际工具调用' }
    const roots = observationTree(
      [step, actual, node('unassociated', 'missing')],
      [entry, { ...actual, name: '骨架' }]
    )
    expect(roots.map((value) => value.node_id)).toEqual(['entry', 'unassociated'])
    expect(roots[0]!.children[0]!.children.map((value) => value.name)).toEqual(['实际工具调用'])
    expect(actual).not.toHaveProperty('children')
  })

  it('因果环不会造成递归树或按时间重新挂接', () => {
    const roots = observationTree([node('a', 'b'), node('b', 'a'), node('child', 'a')])
    expect(roots.map((value) => value.node_id)).toEqual(['a', 'b', 'child'])
    expect(roots.every((value) => value.children.length === 0)).toBe(true)
  })

  it('Token保留精度，合计不重复加入缓存与推理子集，缺报保持未知', () => {
    const totals: TokenTotals = {
      prompt_tokens: '9007199254740993',
      completion_tokens: '7',
      cached_input_tokens: '11',
      cache_write_input_tokens: '13',
      reasoning_output_tokens: '3'
    }
    expect(formatObservationTokens(totals.prompt_tokens)).toBe('9,007,199,254,740,993')
    expect(observationTokenTotal(totals)).toBe('9007199254741000')
    expect(observationTokenTotal({ ...totals, completion_tokens: null })).toBeNull()
    expect(observationTokenTotal({ ...totals, prompt_tokens: '0', completion_tokens: '0' })).toBe(
      '0'
    )
    expect(formatObservationTokens(null)).toBe('未记录')
    expect(formatObservationTokens('1e4')).toBe('未记录')
  })

  it('只格式化已记录耗时，明确零不丢失，未知不补造', () => {
    expect(formatObservationDuration('0')).toBe('0 ms')
    expect(formatObservationDuration('999')).toBe('999 ms')
    expect(formatObservationDuration('12345')).toBe('12.35 s')
    expect(formatObservationDuration('9007199254740993')).toBe('9007199254740.99 s')
    expect(formatObservationDuration(null)).toBe('未记录')
    expect(formatObservationDuration('-1')).toBe('未记录')
  })

  it('内容引用和可用性同时满足才允许读取，受限引用不能绕过展示入口', () => {
    expect(canReadObservationContent({ ref: 'tool-output', availability: 'FULL' })).toBe(true)
    expect(canReadObservationContent({ ref: 'model-output', availability: 'FULL' })).toBe(true)
    expect(
      canReadObservationContent({ ref: 'old-model-output', availability: 'NOT_RECORDED' })
    ).toBe(false)
    expect(canReadObservationContent({ ref: 'tool-output', availability: 'REDACTED' })).toBe(false)
    expect(canReadObservationContent({ ref: null, availability: 'FULL' })).toBe(false)
  })

  it('十进制分精确转为币种主单位，负调整、亚分和大整数不会被四舍五入成零', () => {
    expect(formatObservationMoney('0')).toBe('0.00')
    expect(formatObservationMoney('100.000')).toBe('1.00')
    expect(formatObservationMoney('0.125')).toBe('0.00125')
    expect(formatObservationMoney('-2.5')).toBe('-0.025')
    expect(formatObservationMoney('0.000001')).toBe('0.00000001')
    expect(formatObservationMoney('-9007199254740993123456789.125')).toBe(
      '-90,071,992,547,409,931,234,567.89125'
    )
    expect(formatObservationMoney(null)).toBe('未提供')
    expect(formatObservationMoney('NaN')).toBe('未提供')
    expect(formatObservationCosts({})).toBe('金额未提供')
    expect(formatObservationCosts({ CNY: '0' })).toBe('CNY 0.00')
  })

  it('成本记录缺失、未确认、部分确认及明确零金额分别表达', () => {
    const none = {
      total_events: 0,
      priced_events: 0,
      unconfirmed_events: 0,
      missing_model_attempts: 0
    }
    expect(observationCostCoverage({ ...none, status: 'NO_COSTS' })).toBe('暂无费用记录')
    expect(observationCostCoverage({ ...none, status: 'UNKNOWN', missing_model_attempts: 1 })).toBe(
      '费用尚未确认（1 次模型调用缺少费用记录）'
    )
    expect(
      observationCostCoverage({
        ...none,
        status: 'PARTIAL',
        total_events: 2,
        priced_events: 1,
        unconfirmed_events: 1
      })
    ).toBe('部分费用未确认（1 条费用待确认）')
    expect(
      observationCostCoverage({ ...none, status: 'COMPLETE', total_events: 1, priced_events: 1 })
    ).toBe('全部费用已确认')
  })
})
