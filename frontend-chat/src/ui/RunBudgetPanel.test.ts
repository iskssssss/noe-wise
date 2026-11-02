import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { Client } from '../client'
import RunBudgetPanel from './RunBudgetPanel.vue'
import { budgetCount, executionStopReason, type RunBudget } from './runBudget'

const wrappers: ReturnType<typeof mount>[] = []
afterEach(() => wrappers.splice(0).forEach((wrapper) => wrapper.unmount()))
function snapshot(run = 'r'): RunBudget {
  const resources = {
    business_model_calls: '10',
    auxiliary_model_calls: '5',
    tool_calls: '20',
    knowledge_calls: '3',
    input_tokens: '9007199254740993',
    output_tokens: '100',
    total_tokens: '9007199254740993'
  }
  const scope = {
    limits: resources,
    consumed: { ...resources, business_model_calls: '2' },
    reserved: { ...resources, business_model_calls: '1' },
    active_seconds_limit: '600',
    confirmed_active_millis: '1234'
  }
  return {
    run_id: run,
    root_run_id: 'root',
    scenario: 'DELEGATION',
    role: 'MEMBER',
    phase: 'CLOSING',
    source_version: 'budget-policy.v3:task_budget:42',
    root: scope,
    run: scope,
    closing: { resources, active_seconds: '30' }
  }
}
function open(json: ReturnType<typeof vi.fn>, active = true) {
  const wrapper = mount(RunBudgetPanel, {
    props: { active, session: 's', run: 'r', client: { json } as unknown as Client }
  })
  wrappers.push(wrapper)
  return wrapper
}
describe('已登记运行额度', () => {
  it('按需读取冻结来源并精确展示已消耗与预留，不推算余额', async () => {
    const json = vi.fn().mockResolvedValue(snapshot())
    const wrapper = open(json, false)
    await flushPromises()
    expect(json).not.toHaveBeenCalled()
    await wrapper.setProps({ active: true })
    await flushPromises()
    expect(json).toHaveBeenCalledTimes(1)
    expect(json).toHaveBeenCalledWith(
      '/chat/runs/budget?session_id=s&run_id=r',
      'GET',
      undefined,
      expect.any(AbortSignal)
    )
    expect(wrapper.text()).toContain('budget-policy.v3:task_budget:42')
    expect(wrapper.text()).toContain('协作任务')
    expect(wrapper.text()).toContain('收尾')
    expect(wrapper.findAll('table')).toHaveLength(2)
    expect(wrapper.findAll('table')[0]!.findAll('tbody tr')[0]!.text()).toBe('业务模型调用1021')
    expect(wrapper.text()).toContain('9,007,199,254,740,993')
    expect(wrapper.text()).toContain('已确认活跃耗时 1,234 毫秒')
    expect(wrapper.text()).not.toContain('剩余额度')
  })
  it('刷新失败保留原观察，用户可再次刷新', async () => {
    const json = vi
      .fn()
      .mockResolvedValueOnce(snapshot())
      .mockRejectedValueOnce(new Error('暂时不可用'))
    const wrapper = open(json)
    await flushPromises()
    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(document.querySelector('.chat-toast')?.textContent).toContain('暂时不可用')
    expect(wrapper.text()).toContain('保留上次读取的额度观察')
    expect(wrapper.findAll('table')).toHaveLength(2)
    expect(json).toHaveBeenCalledTimes(2)
  })
  it('切换运行时取消旧请求并忽略迟到回执，关闭后也不采用在途结果', async () => {
    let finishOld!: (value: RunBudget) => void
    let finishNew!: (value: RunBudget) => void
    const json = vi
      .fn()
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            finishOld = resolve
          })
      )
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            finishNew = resolve
          })
      )
    const wrapper = open(json)
    const signal = json.mock.calls[0]![3] as AbortSignal
    await wrapper.setProps({ run: 'new' })
    expect(signal.aborted).toBe(true)
    finishOld(snapshot('r'))
    await flushPromises()
    expect(wrapper.findAll('table')).toHaveLength(0)
    await wrapper.setProps({ active: false })
    finishNew(snapshot('new'))
    await flushPromises()
    expect(wrapper.findAll('table')).toHaveLength(0)
  })
  it('拒绝另一运行的额度回执', async () => {
    const wrapper = open(vi.fn().mockResolvedValue(snapshot('wrong-run')))
    await flushPromises()
    expect(document.querySelector('.chat-toast')?.textContent).toContain('运行额度身份不匹配')
    expect(wrapper.findAll('table')).toHaveLength(0)
  })
})
describe('运行预算文案', () => {
  it('用量未知和缺失不归零，超出安全整数仍精确展示', () => {
    expect(budgetCount('UNKNOWN')).toBe('未知')
    expect(budgetCount(undefined as unknown as string)).toBe('未知')
    expect(budgetCount('0')).toBe('0')
    expect(budgetCount('9007199254740993')).toBe('9,007,199,254,740,993')
  })
  it('明确区分根与当前运行的业务、辅助和非模型资源，保留未知原因', () => {
    expect(executionStopReason('execution_budget_root_business_model_calls_limit')).toContain(
      '任务根的业务模型调用'
    )
    expect(executionStopReason('execution_budget_run_auxiliary_model_calls_limit')).toContain(
      '当前运行的辅助模型调用'
    )
    expect(executionStopReason('execution_budget_run_tool_calls_limit')).toContain('工具调用')
    expect(executionStopReason('execution_budget_root_knowledge_calls_limit')).toContain('知识调用')
    expect(executionStopReason('BUSINESS_MODEL_CALL_BUDGET_EXCEEDED')).toBe(
      '业务模型调用额度已耗尽。'
    )
    expect(executionStopReason('WORKFLOW_MAX_STEPS_REACHED')).toContain('工作流')
    expect(executionStopReason('tool_output_invalid')).toContain('工具已执行')
    expect(executionStopReason('tool_outcome_unknown')).toContain('先核对原操作')
    expect(executionStopReason('unrecognized_reason')).toBe('unrecognized_reason')
  })
})
