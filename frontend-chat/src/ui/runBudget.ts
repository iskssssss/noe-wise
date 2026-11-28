/** 只消费已登记 Run 的冻结限额与账本观察，不派生额度或估算未知用量。 */
export interface RunBudgetResources {
  business_model_calls: string
  auxiliary_model_calls: string
  tool_calls: string
  knowledge_calls: string
  input_tokens: string
  output_tokens: string
  total_tokens: string
}
export interface RunBudgetScope {
  limits: RunBudgetResources
  consumed: RunBudgetResources
  reserved: RunBudgetResources
  active_seconds_limit: string
  confirmed_active_millis: string
}
export interface RunBudget {
  run_id: string
  root_run_id: string
  scenario: string
  role: string
  phase: string
  source_version: string
  root: RunBudgetScope
  run: RunBudgetScope
  closing: { resources: RunBudgetResources; active_seconds: string }
}
export const budgetDimensions: Array<[keyof RunBudgetResources, string]> = [
  ['business_model_calls', '业务模型调用'],
  ['auxiliary_model_calls', '辅助模型调用'],
  ['tool_calls', '工具调用'],
  ['knowledge_calls', '知识调用'],
  ['input_tokens', '累计输入 Tokens'],
  ['output_tokens', '累计输出 Tokens'],
  ['total_tokens', '累计总 Tokens']
]
export const budgetScenarios: Record<string, string> = {
  STANDARD: '普通任务',
  DELEGATION: '协作任务',
  TEAM: '团队任务',
  MAINTENANCE: '维护任务'
}
/** 十进制字符串用 BigInt 格式化，缺失或未知不能变成零。 */
export function budgetCount(value: string) {
  return typeof value === 'string' && /^\d+$/.test(value)
    ? BigInt(value).toLocaleString('zh-CN')
    : '未知'
}
/** 精确消费已公开的资源原因，未知原因原样保留供用户核对。 */
export function executionStopReason(reason: string) {
  const match =
    /^execution_budget_(root|run)_(business_model_calls|auxiliary_model_calls|tool_calls|knowledge_calls|input_tokens|output_tokens|total_tokens)_limit$/.exec(
      reason
    )
  if (match) {
    const resource = budgetDimensions.find(([key]) => key === match[2])?.[1]
    return `${match[1] === 'root' ? '任务根' : '当前运行'}的${resource}额度已耗尽。`
  }
  const known: Record<string, string> = {
    model_rate_limited:
      '模型服务限流，本次任务未完成。请联系管理员核查模型额度或限流状态，恢复后再尝试；重新发送前请核对已执行的操作。',
    user_cancelled: '已按用户请求停止本次执行。',
    tool_output_invalid: '工具已执行，但返回内容未通过格式校验；请核对结果后再决定是否重试。',
    workflow_output_schema_rejected:
      '工作流输出与声明的格式不一致，本次任务未完成。请联系工作流维护者检查输出 Schema、导出字段及节点输出；修正后，先核对已执行的操作，再决定是否重新运行。',
    tool_outcome_unknown: '工具执行结果尚未确认，请先核对原操作，避免重复执行。',
    tool_progress_stopped:
      '工具调用和结果反复相同，本次执行已停止。请查看执行轨迹并调整请求后再试。',
    execution_budget_active_time_limit: '任务活跃执行时长额度已耗尽。',
    execution_output_closing_budget_insufficient:
      '无法为任务收尾预留足够额度，请联系管理员检查运行额度。',
    execution_budget_closing_required: '当前资源仅允许收尾，不能启动新的业务操作。',
    execution_budget_request_conflict: '本次资源请求与已登记请求冲突。',
    BUSINESS_MODEL_CALL_BUDGET_EXCEEDED: '业务模型调用额度已耗尽。',
    WORKFLOW_MAX_STEPS_REACHED: '工作流已达到最大步骤限制。',
    WORKFLOW_NODE_REVISIT: '工作流触发节点重复访问保护。'
  }
  return known[reason] ?? reason
}
