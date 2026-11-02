import { canonicalJson } from '../../utils/capabilityContent'
import type { ChatToolInputResponse } from '../../types/chatCollaboration'
export const TOOL_INPUT_ACTIONS = { ACCEPT: 'accept', DECLINE: 'decline' } as const
export type ToolInputAction = (typeof TOOL_INPUT_ACTIONS)[keyof typeof TOOL_INPUT_ACTIONS]
export const TOOL_INPUT_PROTOCOL = {
  CARD_TYPE: 'tool_input',
  METHOD: 'elicitation/create',
  JSON_SCHEMA: 'application/json'
} as const
/** MCP elicitation/create 卡对浏览器公开的最小恢复身份。 */
export interface ToolInputWait {
  /** 原逻辑工具Action身份，同一次续跑不会新建Action。 */
  action_id: string
  /** 该Action当前等待代次的规范十进制文本。 */
  generation: string
}

/** 用户可见的单个 MCP elicitation/create 请求；不携带服务端 requestState。 */
export interface ToolInputRequest {
  id: string
  message: string
  requestedSchema: Record<string, unknown>
}

export interface ToolInputCard {
  type: typeof TOOL_INPUT_PROTOCOL.CARD_TYPE
  title: string
  toolWait: ToolInputWait
  requests: ToolInputRequest[]
}

function record(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value)
}

function text(value: unknown): value is string {
  return typeof value === 'string' && value.length > 0
}

/**
 * 只读取可呈现和续接所需的字段，避免把服务端内部 requestState 带进浏览器状态或回传。
 */
export function toolInputCardOf(value: unknown): ToolInputCard | undefined {
  if (
    !record(value) ||
    value.type !== TOOL_INPUT_PROTOCOL.CARD_TYPE ||
    !text(value.title) ||
    !record(value.tool_wait) ||
    !Array.isArray(value.requests)
  )
    return undefined
  const wait = value.tool_wait
  if (!text(wait.action_id) || !revision(wait.generation)) return undefined
  const requests: ToolInputRequest[] = []
  for (const item of value.requests) {
    if (!record(item) || !text(item.id) || !text(item.message) || !record(item.requested_schema))
      return undefined
    requests.push({ id: item.id, message: item.message, requestedSchema: item.requested_schema })
  }
  if (!requests.length || new Set(requests.map((item) => item.id)).size !== requests.length)
    return undefined
  return {
    type: TOOL_INPUT_PROTOCOL.CARD_TYPE,
    title: value.title,
    toolWait: {
      action_id: wait.action_id,
      generation: wait.generation
    },
    requests
  }
}

export function sameToolInputWait(left: ToolInputWait, right: ToolInputWait): boolean {
  return left.action_id === right.action_id && left.generation === right.generation
}

/** 版本和等待代次必须保持服务端规范十进制文本，避免浮点数损失高位身份。 */
function revision(value: unknown): value is string {
  return typeof value === 'string' && /^[1-9][0-9]*$/.test(value)
}

/** 精确恢复当前工具 wait；响应进入结构化 hitl_response，不成为用户消息。 */
export function buildToolInputResponse(
  pending: {
    execution_id: string
    interaction_type?: string
    run_revision?: number | string
    tool_wait?: ToolInputWait
  },
  card: ToolInputCard,
  action: ToolInputAction,
  values: Record<string, Record<string, unknown>> = {}
): ChatToolInputResponse {
  if (
    !text(pending.execution_id) ||
    !revision(pending.run_revision) ||
    !pending.tool_wait ||
    !sameToolInputWait(pending.tool_wait, card.toolWait)
  )
    throw new Error('当前补充输入已失效，请重新读取会话，并按当前待办填写。')
  const responses = Object.fromEntries(
    card.requests.map((request) => [
      request.id,
      {
        method: TOOL_INPUT_PROTOCOL.METHOD,
        result: {
          schemaId: TOOL_INPUT_PROTOCOL.JSON_SCHEMA,
          canonicalJson: canonicalJson(
            action === TOOL_INPUT_ACTIONS.ACCEPT
              ? { action, content: values[request.id] ?? {} }
              : { action }
          )
        }
      }
    ])
  )
  return {
    execution_id: pending.execution_id,
    run_revision: pending.run_revision,
    tool_input: {
      wait: { ...card.toolWait },
      responses: Object.fromEntries(
        Object.entries(responses).map(([id, response]) => [
          id,
          {
            method: response.method,
            result: {
              schema_id: response.result.schemaId,
              canonical_json: response.result.canonicalJson
            }
          }
        ])
      )
    }
  }
}
