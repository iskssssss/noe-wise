import { describe, expect, it } from 'vitest'
import { mergeWorkPlans, taskIsTerminal, taskStatus, workPhase } from './workPlans'
import type { Work } from './protocol'
const plan = (fields: Partial<Work> = {}): Work => ({
  work_id: 'w',
  input_id: 'i',
  session_id: 's',
  run_id: 'r',
  revision: 1,
  run_revision: 1,
  run_status: 'running',
  status: 'active',
  plan_revision: 1,
  approval_required: false,
  tasks: [],
  results: [],
  ...fields
})
describe('统一任务计划事实合并', () => {
  it('未准入受阻步骤不冒充执行失败，也不再提供步骤操作', () => {
    expect(taskStatus('blocked')).toBe('依赖受阻')
    expect(taskStatus('outcome_unknown')).toBe('结果待核实')
    expect(taskIsTerminal({ status: 'blocked' } as Work['tasks'][number])).toBe(true)
    expect(taskIsTerminal({ status: 'pending' } as Work['tasks'][number])).toBe(false)
  })
  it('Work较旧但主Run较新的快照只推进主Run事实', () => {
    const [merged] = mergeWorkPlans(
      [plan({ revision: 5, title: '新计划', plan_revision: 3 })],
      [plan({ revision: 2, title: '旧计划', run_revision: 7, run_status: 'completed' })]
    )
    expect(merged).toMatchObject({
      revision: 5,
      title: '新计划',
      plan_revision: 3,
      run_revision: 7,
      run_status: 'completed'
    })
  })
  it('Work较新但主Run较旧不会覆盖主Run终态；历史计划独立保留', () => {
    const [merged, historical] = mergeWorkPlans(
      [plan({ run_revision: 7, run_status: 'completed' }), plan({ work_id: 'history' })],
      [plan({ revision: 3, run_revision: 2, run_status: 'running' })]
    )
    expect(merged).toMatchObject({ revision: 3, run_revision: 7, run_status: 'completed' })
    expect(historical?.work_id).toBe('history')
  })
  it('已批准精确版本不再显示等待审批，修订后重新等待', () => {
    expect(
      workPhase(plan({ approval_required: true, approved_revision: 2, plan_revision: 2 }))
    ).toBe('执行中')
    expect(
      workPhase(plan({ approval_required: true, approved_revision: 2, plan_revision: 3 }))
    ).toBe('等待批准')
  })
  it('成员完成时主Agent未结束仍显示正在汇总', () => {
    expect(workPhase(plan({ status: 'completed' }))).toBe('正在整理结果')
  })
  it('工作仍active但公开阶段为整理时，父Run未结束显示整理结果', () => {
    expect(workPhase(plan({ stage: 'summarizing' }))).toBe('正在整理结果')
    expect(
      workPhase(
        plan({
          stage: 'summarizing',
          tasks: [
            {
              task_id: 't',
              task_kind: 'plan',
              title: '完成步骤',
              agent_name: '主助手',
              status: 'completed',
              details_access: 'available'
            }
          ]
        })
      )
    ).toBe('正在整理结果')
    expect(workPhase(plan({ tasks: [] }))).toBe('执行中')
  })
  it.each([
    ['failed', '主 Agent 执行失败'],
    ['cancelled', '已取消'],
    ['outcome_unknown', '结果待核实'],
    ['completed', '主 Agent 已回复']
  ])('父执行%s优先于整理阶段', (run_status, expected) => {
    expect(workPhase(plan({ stage: 'summarizing', status: 'completed', run_status }))).toBe(
      expected
    )
  })
})
