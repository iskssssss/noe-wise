import type { Agent } from '../protocol'

/** 同名智能体以完整标识消歧；角色说明单独展示，名称仍是选择主信息。 */
export function agentChoiceName(agent: Agent, agents: Agent[]): string {
  return agents.some((item) => item.id !== agent.id && item.name === agent.name)
    ? `${agent.name}（${agent.id}）`
    : agent.name
}
