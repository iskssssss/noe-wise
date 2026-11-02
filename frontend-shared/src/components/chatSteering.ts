/** 运行中纠正命令的服务端状态。 */
export type ChatSteeringCommandStatus = 'PENDING' | 'INCLUDED' | 'CONSUMED'

/** 同一 commandId 的失败重试表示同一条用户意图。 */
export interface ChatSteeringCommand {
  command_id: string
  text: string
  status: ChatSteeringCommandStatus
}

/** 查询与提交共用的权威响应。 */
export interface ChatSteeringState {
  run_id: string
  accepting: boolean
  items: ChatSteeringCommand[]
}

export type ChatSteeringQuery = (sessionId: string, runId: string) => Promise<ChatSteeringState>

export type ChatSteeringSubmit = (
  sessionId: string,
  runId: string,
  commandId: string,
  text: string
) => Promise<ChatSteeringState>

export const CHAT_STEERING_MAX_TEXT_LENGTH = 8192
