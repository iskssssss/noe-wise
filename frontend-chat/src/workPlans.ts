import type { Work, WorkTask } from './protocol'

const terminalRunStatuses = new Set([
  'completed',
  'failed',
  'cancelled',
  'outcome_unknown',
  'terminated'
])

export function runIsTerminal(status: string): boolean {
  return terminalRunStatuses.has(status)
}

/** 终态与当前计划中已受阻的步骤仅供回看，不再提供执行输入和取消入口。 */
export function taskIsTerminal(task: WorkTask): boolean {
  return ['completed', 'failed', 'cancelled', 'outcome_unknown', 'terminated', 'blocked'].includes(
    task.status
  )
}

/** 计划版本与历史 pending 不会随终态消失；控制入口只消费当前可推进的工作。 */
export function workIsTerminal(work: Work): boolean {
  return runIsTerminal(work.status) || runIsTerminal(work.run_status ?? '')
}

/** Work 与主 Run 独立推进；旧响应不能回退任何一个事实域。 */
export function mergeWorkPlans(current: Work[], incoming: Work[]): Work[] {
  const plans = new Map(current.map((work) => [work.work_id, work]))
  for (const next of incoming) {
    const previous = plans.get(next.work_id)
    if (!previous) {
      plans.set(next.work_id, next)
      continue
    }
    const work = next.revision >= previous.revision ? next : previous
    const run = (next.run_revision ?? -1) >= (previous.run_revision ?? -1) ? next : previous
    plans.set(next.work_id, { ...work, run_status: run.run_status, run_revision: run.run_revision })
  }
  return [...plans.values()]
}
export function taskStatus(status: WorkTask['status']): string {
  return (
    (
      {
        pending: '待开始',
        blocked: '依赖受阻',
        running: '进行中',
        waiting: '等待',
        completed: '已完成',
        failed: '失败',
        cancelling: '取消中',
        cancelled: '已取消',
        outcome_unknown: '结果待核实'
      } as Record<string, string>
    )[status] ?? status
  )
}
export function workPhase(work: Work): string {
  if (work.run_status === 'completed') return '主 Agent 已回复'
  if (work.run_status === 'failed') return '主 Agent 执行失败'
  if (work.run_status === 'outcome_unknown') return '结果待核实'
  if (work.run_status === 'cancelled' || work.status === 'cancelled') return '已取消'
  if (work.run_status === 'terminated') return '执行已结束'
  if (work.approval_required && (work.approved_revision ?? 0) < work.plan_revision)
    return '等待批准'
  if (work.stage === 'summarizing' || (work.run_status && work.status === 'completed'))
    return '正在整理结果'
  return (
    (
      { active: '执行中', queued: '待开始', completed: '已完成', cancelled: '已取消' } as Record<
        string,
        string
      >
    )[work.status] ?? work.status
  )
}
