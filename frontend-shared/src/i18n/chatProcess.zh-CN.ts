export const chatProcessZhCN = {
  processView: '工作摘要与执行过程',
  processCompact: '紧凑',
  processStandard: '标准',
  processCollapse: '收起本轮工作摘要与执行过程',
  processExpand: '展开本轮工作摘要与执行过程',
  processSummary: '工作摘要与执行过程 · {count} 项操作',
  processWaitingSeconds: '已等待 {seconds} 秒',
  processDuration: '总耗时 {time}',
  processDurationHint: '按本轮可见执行记录的起止时间计算',
  processDetailExpand: '展开详情',
  processDetailCollapse: '收起详情',
  processArguments: '入参',
  processResult: '结果',
  processCancelledOutcomeUnknown: '本轮已停止，部分工具结果仍未知；已执行的操作不会因取消而撤销。',
  processFailedNotice: '本轮执行失败，可展开查看失败位置和已完成记录。',
  processOutcomeUnknownNotice: '本轮结果尚未确认，可展开查看已执行的操作。',
  processTeamDetail: '本次协作 · {status} · {count} 个任务',
  processType: {
    commentary: '助手说明',
    reasoning: '模型调用',
    skill: 'Skill',
    tool: '工具',
    workflow: 'Workflow',
    memory: '上下文记忆',
    knowledge: '知识检索',
    delegation: 'Agent 委派'
  },
  processStatus: {
    running: '运行中',
    waiting: '等待中',
    succeeded: '已完成',
    failed: '失败',
    cancelled: '已取消',
    outcome_unknown: '结果未知'
  }
}
