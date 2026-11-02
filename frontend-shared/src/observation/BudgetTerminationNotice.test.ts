import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import BudgetTerminationNotice from './BudgetTerminationNotice.vue'
import type { TrajectoryTermination } from './types'

function termination(): TrajectoryTermination {
  return {
    run_id: 'run-budget',
    reason_code: 'execution_output_closing_budget_insufficient',
    business_model_attempts: '0',
    tool_attempts: '0',
    auxiliary_model_attempts: '1',
    budget_failure: {
      schema_id: 'skillforge.runtime.execution.closing-budget-failure.v1',
      code: 'execution_output_closing_budget_insufficient',
      phase: 'BUSINESS',
      shortfalls: [
        {
          scope: 'RUN',
          dimension: 'ACTIVE_MILLIS',
          unit: 'ms',
          required: '240000',
          available: '200000',
          deficit: '40000',
          consumed: '0',
          reserved: '0',
          limit: '200000'
        }
      ],
      time_calculation: {
        model_calls: '2',
        model_timeout_ms: '120000',
        model_window_ms: '120000',
        local_processing_ms: '120000',
        required_ms: '240000'
      }
    }
  }
}

describe('持久化预算终止说明', () => {
  it('逐项显示总需求、总额度、不足与未来收尾时间，不把两次调用误乘为两个窗口', () => {
    const wrapper = mount(BudgetTerminationNotice, { props: { termination: termination() } })
    expect(wrapper.text()).toContain('模型准备时无法预留收尾预算')
    expect(wrapper.text()).toContain('预算检查阶段：业务阶段')
    expect(wrapper.get('.budget-shortfall').text()).toContain('本次运行预算 · 执行时间')
    expect(wrapper.findAll('.budget-values dd').map((item) => item.text())).toEqual([
      '240 秒',
      '200 秒',
      '40 秒'
    ])
    expect(wrapper.findAll('.budget-values dt').map((item) => item.text())).toEqual([
      '总需求',
      '总额度',
      '不足'
    ])
    expect(wrapper.text()).toContain('总需求包含已消耗和其他预留；总额度为该范围的原始额度')
    const calculation = wrapper.get('.budget-calculation').text().replace(/\s+/g, '')
    expect(calculation).toContain('共享模型时间窗120秒')
    expect(calculation).toContain('模型调用预留2次')
    expect(calculation).toContain('未来收尾时间240秒')
    expect(wrapper.findAll('.attempt-counts dd').map((item) => item.text())).toEqual([
      '0 次',
      '0 次',
      '1 次'
    ])
    expect(wrapper.text()).not.toContain('未执行')
    expect(wrapper.text()).not.toContain('360')
    wrapper.unmount()
  })

  it('旧终止记录无结构化预算时明确缺数值，不从原因码或消息拼出原预算', () => {
    const value = termination()
    value.budget_failure = null
    const wrapper = mount(BudgetTerminationNotice, { props: { termination: value } })
    expect(wrapper.text()).toContain('未记录具体预算数值')
    expect(wrapper.find('.budget-values').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('300')
    expect(wrapper.text()).not.toContain('360')
    wrapper.unmount()
  })

  it('Token与次数维度保留大十进制，清除旧资源后不残留不足项', async () => {
    const value = termination()
    value.budget_failure!.shortfalls = [
      {
        scope: 'ROOT',
        dimension: 'TOTAL_TOKENS',
        unit: 'tokens',
        required: '9007199254740993123456789',
        available: '0',
        deficit: '9007199254740993123456789',
        consumed: '0',
        reserved: '0',
        limit: '0'
      }
    ]
    value.budget_failure!.time_calculation = null
    const wrapper = mount(BudgetTerminationNotice, { props: { termination: value } })
    expect(wrapper.get('.budget-shortfall').text()).toContain('根任务预算 · 合计 Token')
    expect(wrapper.get('.budget-values dd').text()).toBe('9,007,199,254,740,993,123,456,789 Token')
    await wrapper.setProps({ termination: null })
    expect(wrapper.find('[aria-label="执行终止原因"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('合计 Token')
    wrapper.unmount()
  })
})
