import type { ApiDocEndpoint, DocField } from './types'
import { adminZhCN } from '../../../i18n/zh-CN'

const CHAT_TEXT = adminZhCN.openApiContent.chat
const CHAT_COMMON = CHAT_TEXT.common
const CHAT_ENDPOINTS = CHAT_TEXT.endpoints
const CHAT_GROUPS = CHAT_TEXT.groups

// 来源：ChatController、ChatRequest、CreateSessionRequest、SendMessageRequest；
// 外部认证边界以 ExternalAuthPaths 为准，JSON 命名以 Jackson SNAKE_CASE 为准。
const chatBody: DocField[] = [
  {
    name: 'content',
    type: 'ChatContent[]',
    description:
      "有序内容块数组。文本使用 { type: 'text', text: '正文' }；图片使用已上传的 image_id。"
  },
  {
    name: 'session_id',
    type: 'string',
    required: true,
    description: CHAT_COMMON.sessionIdBodyDescription
  },
  {
    name: 'agent_id',
    type: 'string',
    description: `${CHAT_COMMON.agentIdDescription} 必须属于当前渠道绑定范围且已发布。`
  },
  {
    name: 'context.end_user_id',
    type: 'string',
    required: true,
    description: CHAT_COMMON.endUserContextDescription
  },
  {
    name: 'client_message_id',
    type: 'string',
    required: true,
    description: CHAT_COMMON.clientMessageIdDescription
  },
  { name: 'scope_id / scope_type', type: 'string', description: CHAT_COMMON.scopeDescription },
  { name: 'locale', type: 'string', description: CHAT_COMMON.localeDescription },
  { name: 'hitl_response', type: 'object', description: CHAT_COMMON.hitlResponseDescription }
]
const chatExample = {
  content: [{ type: 'text', text: CHAT_COMMON.helloExample }],
  session_id: 'SESSION_ID',
  agent_id: 'YOUR_PUBLISHED_AGENT',
  client_message_id: 'CLIENT_MESSAGE_ID',
  context: { end_user_id: '123' }
}
const sessionQuery = {
  name: 'session_id',
  type: 'string',
  required: true,
  location: 'query' as const,
  description: CHAT_COMMON.sessionQueryDescription
}
const lastEvent = {
  name: 'Last-Event-ID',
  type: 'string',
  location: 'header' as const,
  description: CHAT_COMMON.lastEventDescription
}

export const chatEndpoints: ApiDocEndpoint[] = [
  {
    id: 'chat-run-cancel',
    title: '取消对话运行',
    group: CHAT_GROUPS.conversations,
    method: 'POST',
    path: '/api/v1/chat/runs/cancel',
    auth: 'channel',
    summary: '请求停止指定会话中的一次执行，持久化取消意图。',
    body: [
      { name: 'session_id', type: 'string', required: true, description: '当前渠道的会话 ID。' },
      {
        name: 'run_id',
        type: 'string',
        required: true,
        description: '该会话中 run.started 事件的 run_id。'
      }
    ],
    requestExample: { session_id: 'SESSION_ID', run_id: 'RUN_ID' },
    responseType: 'ChatResult',
    responseExample: { status: 'cancelling', session_id: 'SESSION_ID', execution_id: 'RUN_ID' },
    notes: [
      '支持渠道令牌或 OpenAPI 渠道 HMAC；校验租户、渠道、智能体发布与绑定，以及 Run 的会话归属。',
      'cancelling 只表示已请求。通过运行状态查询或 SSE 核验 completed、cancelled、failed、terminated 终态；waiting_hitl 不是取消完成。关闭 SSE 不会取消执行。'
    ]
  },
  {
    id: 'chat-run-status',
    title: '查询对话运行状态',
    group: CHAT_GROUPS.conversations,
    method: 'GET',
    path: '/api/v1/chat/runs/status',
    auth: 'channel',
    summary: '只读查询指定会话与 Run 的当前状态，不恢复或重发输入。',
    parameters: [
      sessionQuery,
      {
        name: 'run_id',
        type: 'string',
        required: true,
        location: 'query',
        description: '待查询的 Run ID，必须属于指定会话。'
      }
    ],
    queryExample: { session_id: 'SESSION_ID', run_id: 'RUN_ID' },
    responseType: 'ChatResult',
    responseExample: { status: 'cancelled', session_id: 'SESSION_ID', execution_id: 'RUN_ID' },
    notes: ['认证与目标授权要求和取消接口一致；响应 execution_id 对应请求的 run_id。']
  },
  {
    id: 'session-artifacts',
    title: '会话产物列表',
    group: CHAT_GROUPS.sessions,
    method: 'GET',
    path: '/api/v1/chat/sessions/artifacts',
    auth: 'channel',
    summary: '读取当前渠道会话的产物元数据与文件清单。',
    parameters: [
      {
        name: 'sessionId',
        type: 'string',
        required: true,
        location: 'query',
        description: '当前渠道的会话 ID。'
      }
    ],
    queryExample: { sessionId: 'SESSION_ID' },
    responseType: 'ChatArtifact[]',
    responseExample: [],
    notes: ['校验会话所属渠道、渠道启用状态、智能体发布状态及绑定关系。']
  },
  {
    id: 'artifact-download-ticket',
    title: '产物下载票据',
    group: CHAT_GROUPS.sessions,
    method: 'POST',
    path: '/api/v1/chat/artifacts/download-ticket',
    auth: 'channel',
    summary: '签发单文件或 ZIP 下载票据。',
    body: [
      { name: 'id', type: 'string', required: true, description: '产物 ID。' },
      { name: 'payload', type: 'object', description: 'path 指定文件；zip=true 下载完整压缩包。' }
    ],
    requestExample: { id: 'ARTIFACT_ID', payload: { path: 'report.txt' } },
    responseType: '{ url: string }',
    notes: ['票据有效期 120 秒。下载时再次校验渠道权限，解绑或停用后拒绝访问。']
  },
  {
    id: 'artifact-preview-ticket',
    title: '产物预览票据',
    group: CHAT_GROUPS.sessions,
    method: 'POST',
    path: '/api/v1/chat/artifacts/preview-ticket',
    auth: 'channel',
    summary: '为 website 类型产物签发隔离预览地址。',
    body: [{ name: 'id', type: 'string', required: true, description: '网站产物 ID。' }],
    requestExample: { id: 'ARTIFACT_ID' },
    responseType: '{ url: string }',
    notes: [
      '票据有效期 120 秒，每次读取复核权限。预览使用独立主机名和 sandbox iframe，聊天站点须加入服务端明确来源白名单。'
    ]
  },
  {
    id: 'chat-sync',
    title: CHAT_ENDPOINTS.sync.title,
    group: CHAT_GROUPS.conversations,
    method: 'POST',
    path: '/api/v1/chat/sync',
    auth: 'channel',
    summary: CHAT_ENDPOINTS.sync.summary,
    body: chatBody,
    requestExample: chatExample,
    responseType: CHAT_ENDPOINTS.sync.responseType,
    responseExample: {
      status: 'queued',
      session_id: 'SESSION_ID',
      execution_id: 'RUN_ID',
      input_id: 'INPUT_ID',
      replayed: false
    },
    notes: [CHAT_ENDPOINTS.sync.workflowNote, CHAT_ENDPOINTS.sync.statusNote]
  },
  {
    id: 'chat-stream',
    title: CHAT_ENDPOINTS.stream.title,
    group: CHAT_GROUPS.conversations,
    method: 'POST',
    path: '/api/v1/chat',
    auth: 'channel',
    summary: CHAT_ENDPOINTS.stream.summary,
    body: chatBody,
    requestExample: chatExample,
    responseType: CHAT_ENDPOINTS.stream.responseType,
    responseExample: {
      status: 'queued',
      session_id: 'SESSION_ID',
      execution_id: 'RUN_ID',
      input_id: 'INPUT_ID',
      replayed: false
    },
    notes: [CHAT_ENDPOINTS.stream.clientNote, CHAT_ENDPOINTS.stream.eventNote]
  },
  {
    id: 'chat-events-stream',
    title: '订阅对话事件',
    group: CHAT_GROUPS.conversations,
    method: 'GET',
    path: '/api/v1/chat/events',
    auth: 'channel',
    summary: '提交已受理的对话后，以 SSE 订阅该会话的持久事件；重连只重新订阅，绝不重发 POST。',
    parameters: [sessionQuery, lastEvent],
    requestHeaders: { Accept: 'text/event-stream' },
    queryExample: { session_id: 'SESSION_ID' },
    responseType: 'text/event-stream · SSE',
    responseKind: 'sse',
    notes: [
      '按 event 类型处理 message.delta、execution.completed、hitl.required 与 error；cURL 示例使用 -N 关闭输出缓冲。',
      'Last-Event-ID 优先于 cursor；游标失效返回 409 JSON 错误信封，客户端应读取会话历史后仅重新订阅。',
      '先等待本次 input_id 的 execution.accepted 边界，再按 execution_id 接收后续事件；这样可跳过旧的挂起终态，同时保留恢复、取消等内部输入产生的合法终态。'
    ]
  },
  {
    id: 'chat-agents',
    title: '获取渠道可用智能体',
    group: CHAT_GROUPS.conversations,
    method: 'GET',
    path: '/api/v1/chat/agents',
    auth: 'channel',
    summary:
      '验证渠道凭证并获取当前渠道绑定、已发布且可聊天的智能体。用于独立聊天前端首次配置和智能体选择。',
    responseType: 'application/json',
    responseExample: {
      channel_id: 'CHANNEL_ID',
      items: [{ id: 'AGENT_ID', name: '客户服务助手', is_default: true }]
    },
    notes: [
      '支持 X-Channel-Token 或 OpenAPI 渠道 API Key + HMAC；两种方式返回相同的渠道授权范围。',
      '不会返回提示词、模型凭据或管理配置。没有可用智能体时 items 为空。'
    ]
  },
  {
    id: 'chat-client-profile',
    title: '登记聊天身份',
    group: CHAT_GROUPS.conversations,
    method: 'POST',
    path: '/api/v1/chat/client-profile',
    auth: 'channel',
    summary:
      '凭证验证后，按机构、渠道及本机 client_ref 幂等登记聊天身份，用于恢复同一渠道的会话历史。',
    body: [
      {
        name: 'client_ref',
        type: 'string',
        required: true,
        description: '本机随机生成并保存的稳定标识；不同渠道分别保存，不是登录凭证。'
      },
      { name: 'display_name', type: 'string', required: true, description: '用户输入的聊天昵称。' }
    ],
    requestExample: { client_ref: '550e8400-e29b-41d4-a716-446655440000', display_name: '访客' },
    responseType: 'application/json',
    responseExample: { end_user_id: '123', display_name: '访客', channel_id: 'CHANNEL_ID' },
    notes: [
      '重复提交同一 client_ref 返回同一 end_user_id；昵称和本机标识不构成独立用户认证，权限仍来自渠道凭证。',
      '创建会话、发送消息、读取历史与事件回放都校验智能体仍属于当前渠道且可用；解绑或下线后拒绝继续访问。'
    ]
  },
  {
    id: 'send-message',
    title: CHAT_ENDPOINTS.sendMessage.title,
    group: CHAT_GROUPS.conversations,
    method: 'POST',
    path: '/api/v1/messages',
    auth: 'channel',
    summary: CHAT_ENDPOINTS.sendMessage.summary,
    body: [
      {
        name: 'session_id',
        type: 'string',
        required: true,
        description: CHAT_ENDPOINTS.sendMessage.sessionDescription
      },
      {
        name: 'content',
        type: 'string',
        required: true,
        description: CHAT_ENDPOINTS.sendMessage.contentDescription
      },
      {
        name: 'card / render',
        type: 'object',
        description: CHAT_ENDPOINTS.sendMessage.cardDescription
      },
      {
        name: 'merge_message_id',
        type: 'string',
        description: CHAT_ENDPOINTS.sendMessage.mergeDescription
      }
    ],
    requestExample: { session_id: 'SESSION_ID', content: CHAT_COMMON.completedMessageExample },
    responseType: CHAT_ENDPOINTS.sendMessage.responseType,
    responseExample: { status: 'queued', message_id: 'MESSAGE_ID', delivered: false },
    notes: [CHAT_ENDPOINTS.sendMessage.queuedNote]
  },
  {
    id: 'chat-history',
    title: CHAT_ENDPOINTS.history.title,
    group: CHAT_GROUPS.sessions,
    method: 'GET',
    path: '/api/v1/chat/history',
    auth: 'channel',
    summary: CHAT_ENDPOINTS.history.summary,
    parameters: [
      sessionQuery,
      {
        name: 'limit',
        type: 'integer',
        location: 'query',
        description: '每页条数，默认 30，最大 100。'
      },
      {
        name: 'before_message_id',
        type: 'string',
        location: 'query',
        description: '上一页 next_before_message_id；首次省略，返回最新消息。'
      }
    ],
    queryExample: { session_id: 'SESSION_ID', limit: 30 },
    responseType: CHAT_ENDPOINTS.history.responseType,
    responseExample: {
      session_id: 'SESSION_ID',
      messages: [],
      pending_interactions: [],
      activities: [],
      has_more: false,
      next_before_message_id: null
    },
    notes: [
      '回答正文仅由 messages[].content 承载；activities 按 message_id 关联卡片和运行状态，不重复返回正文。面向用户的回答默认采用 Markdown，但不能据此信任原始 HTML。代码应按代码块展示，流式或未规范包裹的源码需由客户端转义。'
    ]
  },
  {
    id: 'sessions-list',
    title: CHAT_ENDPOINTS.sessionList.title,
    group: CHAT_GROUPS.sessions,
    method: 'GET',
    path: '/api/v1/chat/sessions',
    auth: 'channel',
    summary: CHAT_ENDPOINTS.sessionList.summary,
    parameters: [
      {
        name: 'end_user_id',
        type: 'string',
        required: true,
        location: 'query',
        description: CHAT_ENDPOINTS.sessionList.endUserDescription
      },
      {
        name: 'agentId',
        type: 'integer',
        location: 'query',
        description: CHAT_ENDPOINTS.sessionList.agentDescription
      }
    ],
    queryExample: { end_user_id: '123' },
    responseType: CHAT_ENDPOINTS.sessionList.responseType,
    responseExample: [
      {
        session_id: 'SESSION_ID',
        summary: CHAT_COMMON.sessionSummaryExample,
        message_count: 2,
        updated_at: '2026-09-13T00:00:00Z',
        agent_id: 1
      }
    ],
    notes: [CHAT_ENDPOINTS.sessionList.arrayNote]
  },
  {
    id: 'sessions-create',
    title: CHAT_ENDPOINTS.sessionCreate.title,
    group: CHAT_GROUPS.sessions,
    method: 'POST',
    path: '/api/v1/chat/sessions',
    auth: 'channel',
    summary: CHAT_ENDPOINTS.sessionCreate.summary,
    body: [
      {
        name: 'agent_id',
        type: 'string',
        required: true,
        description: CHAT_ENDPOINTS.sessionCreate.agentDescription
      },
      {
        name: 'end_user_id',
        type: 'string',
        required: true,
        description: CHAT_ENDPOINTS.sessionCreate.endUserDescription
      },
      {
        name: 'scope_id / scope_type',
        type: 'string',
        description: CHAT_ENDPOINTS.sessionCreate.scopeDescription
      }
    ],
    requestExample: { agent_id: 'YOUR_PUBLISHED_AGENT', end_user_id: '123' },
    responseType: CHAT_ENDPOINTS.sessionCreate.responseType,
    responseExample: { session_id: 'SESSION_ID' }
  },
  {
    id: 'chat-replay',
    title: CHAT_ENDPOINTS.replay.title,
    group: CHAT_GROUPS.sessions,
    method: 'GET',
    path: '/api/v1/chat/replay',
    auth: 'channel',
    summary: CHAT_ENDPOINTS.replay.summary,
    parameters: [sessionQuery, lastEvent],
    queryExample: { session_id: 'SESSION_ID' },
    responseType: CHAT_ENDPOINTS.replay.responseType,
    responseKind: 'sse',
    requestHeaders: { Accept: 'text/event-stream' },
    notes: [CHAT_ENDPOINTS.replay.limitNote]
  },
  {
    id: 'chat-events',
    title: CHAT_ENDPOINTS.events.title,
    group: CHAT_GROUPS.sessionManagement,
    method: 'GET',
    path: '/api/v1/chat/events',
    auth: 'openapi',
    summary: CHAT_ENDPOINTS.events.summary,
    parameters: [
      sessionQuery,
      {
        name: 'cursor',
        type: 'string',
        location: 'query',
        description: CHAT_ENDPOINTS.events.cursorDescription
      },
      {
        name: 'limit',
        type: 'integer',
        location: 'query',
        description: CHAT_ENDPOINTS.events.limitDescription
      }
    ],
    queryExample: { session_id: 'SESSION_ID', limit: 100 },
    responseType: CHAT_ENDPOINTS.events.responseType,
    responseExample: { session_id: 'SESSION_ID', next_cursor: null, events: [] }
  },
  {
    id: 'session-detail',
    title: CHAT_ENDPOINTS.sessionDetail.title,
    group: CHAT_GROUPS.sessionManagement,
    method: 'GET',
    path: '/api/v1/chat/sessions/detail',
    auth: 'openapi',
    summary: CHAT_ENDPOINTS.sessionDetail.summary,
    parameters: [
      {
        name: 'sessionId',
        type: 'string',
        required: true,
        location: 'query',
        description: CHAT_ENDPOINTS.sessionDetail.sessionDescription
      }
    ],
    queryExample: { sessionId: 'YOUR_SESSION_ID' },
    responseType: CHAT_ENDPOINTS.sessionDetail.responseType,
    responseExample: {
      session: { session_id: 'SESSION_ID', agent_id: 101, team_id: null },
      end_user_id: '9223372036854775806',
      target_agent_id: '101',
      closed: false,
      agent: null,
      executions: [],
      tasks: [],
      callbacks: []
    },
    notes: [
      'end_user_id、target_agent_id 使用十进制字符串，恢复会话绑定时保持完整 ID 精度；closed 从会话权威关闭记录读取。Agent 不可见或已删除时仍保留原绑定，不改选其他对象。',
      '当前 executions、tasks、callbacks 返回空数组。执行过程请使用会话执行轨迹接口，调用用量请使用根请求诊断接口。'
    ]
  },
  {
    id: 'session-close',
    title: CHAT_ENDPOINTS.sessionClose.title,
    group: CHAT_GROUPS.sessionManagement,
    method: 'POST',
    path: '/api/v1/chat/sessions/close',
    auth: 'openapi',
    summary: CHAT_ENDPOINTS.sessionClose.summary,
    body: [
      {
        name: 'session_id',
        type: 'string',
        required: true,
        description: CHAT_ENDPOINTS.sessionClose.sessionDescription
      },
      { name: 'payload', type: 'object', description: '关闭会话的可选业务参数。' },
      {
        name: 'payload.reason',
        type: 'string',
        description: CHAT_ENDPOINTS.sessionClose.reasonDescription
      }
    ],
    requestExample: {
      session_id: 'YOUR_SESSION_ID',
      payload: { reason: CHAT_COMMON.closeReasonExample }
    },
    responseType: CHAT_ENDPOINTS.sessionClose.responseType,
    responseExample: { session_id: 'SESSION_ID', status: 'closed' },
    notes: [CHAT_ENDPOINTS.sessionClose.roleNote]
  }
]
