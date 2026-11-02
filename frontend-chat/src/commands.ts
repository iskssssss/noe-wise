export const GOAL_DEFAULT_MAX_ROUNDS = 256
export const GOAL_MAX_ROUNDS = 1024

export type Command =
  | { kind: 'goal-create'; objective: string; rounds: number }
  | { kind: 'goal-status' | 'goal-pause' | 'goal-resume' | 'delivery' }
  | { kind: 'plan'; active: boolean }
/** 本地控制命令不进入模型；无效参数在创建会话前拒绝。 */
export function command(text: string): Command | undefined {
  const value = text.trim()
  if (value === '/plan' || value === '/plan off') return { kind: 'plan', active: value === '/plan' }
  if (value === '/delivery') return { kind: 'delivery' }
  if (!/^\/goal(?:\s|$)/.test(value)) return undefined
  const arg = value.slice(5).trim()
  if (['status', 'pause', 'resume'].includes(arg))
    return { kind: `goal-${arg}` as 'goal-status' | 'goal-pause' | 'goal-resume' }
  const parsed = arg.match(/^(?:--rounds\s+(\d+)\s+)?(.+)$/s)
  if (!parsed || parsed[2]!.startsWith('--'))
    throw new Error(`格式：/goal [--rounds 1至${GOAL_MAX_ROUNDS}] 目标正文`)
  const rounds = Number(parsed[1] ?? GOAL_DEFAULT_MAX_ROUNDS),
    objective = parsed[2]!.trim()
  if (rounds < 1 || rounds > GOAL_MAX_ROUNDS || !objective || objective.length > 8000)
    throw new Error(`目标不能为空且最多 8000 字，轮数必须为 1 至 ${GOAL_MAX_ROUNDS}。`)
  return { kind: 'goal-create', objective, rounds }
}
