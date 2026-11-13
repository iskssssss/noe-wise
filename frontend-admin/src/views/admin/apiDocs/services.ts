import type { ApiDocEndpoint, DocParameter } from './types'
import { adminZhCN } from '../../../i18n/zh-CN'

const SERVICE_TEXT = adminZhCN.openApiContent.services
const SERVICE_COMMON = SERVICE_TEXT.common
const SERVICE_ENDPOINTS = SERVICE_TEXT.endpoints
const SERVICE_GROUPS = SERVICE_TEXT.groups

/**
 * 常用非 Chat 接口事实来源（仓库相对路径）：
 * backend-java/skillforge-institution-service/src/main/java/com/skillforge/institution/
 *   config/ExternalAuthPaths.java:32、ExternalAuthInterceptor.java:88、RoleCheckInterceptor.java:37
 *   controller/OpenPlatformSessionController.java:51、OpenPlatformSessionEventController.java:34
 *   controller/SessionWebhookSubscriptionController.java:34、../runtime/AgentRuntimeCallbackController.java
 *   controller/KnowledgeController.java:77、KnowledgeDocumentController.java:138
 *   runtime/AgentRuntimeInboundGateway.java:205、:555
 * backend-java/skillforge-institution-service/src/main/resources/application.yml:10
 * DTO：skillforge-core-shared/.../dto/dto/{request,response}/OpenPlatform*.java、SessionOutboxCursorResponse.java；
 * skillforge-core-knowledge/.../dto/{request,response,query} 下同名 DTO。
 * 服务层补充：SessionService.java:97、SessionMailboxService.java:317、
 * SessionWebhookSubscriptionService.java:31。
 * JSON DTO 使用 snake_case；查询参数按 Controller / MVC query record 的显式绑定名称逐项核对。
 * 这里只收录常用接口，不能用此列表推断 ExternalAuthPaths 的全部开放范围。
 */

const exampleNote = SERVICE_COMMON.exampleNote
const channelNotes = [exampleNote, SERVICE_COMMON.channelAuthNote]
const serviceNotes = [exampleNote, SERVICE_COMMON.serviceAuthNote]
const sessionParameter: DocParameter = {
  name: 'sessionId',
  location: 'query',
  type: 'string',
  required: true,
  description: SERVICE_COMMON.sessionDescription
}
const inputParameters: DocParameter[] = [
  {
    name: 'Idempotency-Key',
    location: 'header',
    type: 'string',
    required: true,
    description: SERVICE_COMMON.idempotencyDescription
  }
]
const kbParameter: DocParameter = {
  name: 'kbId',
  location: 'query',
  type: 'integer',
  required: true,
  description: SERVICE_COMMON.knowledgeBaseDescription
}
const receiptExample = {
  session_id: 'session-example',
  input_id: 'open-platform:integration-example:session-example:request-001',
  root_input_id: 'open-platform:integration-example:session-example:request-001',
  event_id: 'open-platform:integration-example:session-example:request-001',
  status: 'queued'
}
export const serviceEndpoints: ApiDocEndpoint[] = [
  {
    id: 'service-session-create',
    title: SERVICE_ENDPOINTS.sessionCreate.title,
    group: SERVICE_GROUPS.openPlatformSessions,
    method: 'POST',
    path: '/api/v1/open-platform/sessions',
    auth: 'service',
    summary: SERVICE_ENDPOINTS.sessionCreate.summary,
    scopes: ['sessions.write'],
    body: [
      {
        name: 'external_user_ref',
        type: 'string',
        required: true,
        description: SERVICE_ENDPOINTS.sessionCreate.externalUserDescription
      },
      {
        name: 'external_conversation_ref',
        type: 'string',
        required: true,
        description: SERVICE_ENDPOINTS.sessionCreate.externalConversationDescription
      },
      {
        name: 'end_user_id',
        type: 'string',
        required: true,
        description: SERVICE_ENDPOINTS.sessionCreate.endUserDescription
      },
      {
        name: 'agent_id',
        type: 'string',
        required: true,
        description: SERVICE_ENDPOINTS.sessionCreate.agentDescription
      },
      {
        name: 'session_id',
        type: 'string',
        description: SERVICE_ENDPOINTS.sessionCreate.sessionDescription
      }
    ],
    requestExample: {
      external_user_ref: 'customer-001',
      external_conversation_ref: 'conversation-001',
      end_user_id: '1001',
      agent_id: '2001'
    },
    responseType: SERVICE_ENDPOINTS.sessionCreate.responseType,
    responseExample: {
      session_id: 'session-example',
      integration_id: 'integration-example',
      external_conversation_ref: 'conversation-001',
      end_user_id: '1001',
      agent_id: '2001',
      subject: { kind: 'user', end_user_id: '1001' }
    },
    notes: [...serviceNotes, SERVICE_ENDPOINTS.sessionCreate.identityNote]
  },
  {
    id: 'service-session-message',
    title: SERVICE_ENDPOINTS.sessionMessage.title,
    group: SERVICE_GROUPS.openPlatformSessions,
    method: 'POST',
    path: '/api/v1/open-platform/sessions/messages',
    auth: 'service',
    summary: SERVICE_ENDPOINTS.sessionMessage.summary,
    scopes: ['sessions.write'],
    parameters: inputParameters,
    body: [
      {
        name: 'session_id',
        type: 'string',
        required: true,
        description: SERVICE_COMMON.sessionDescription
      },
      { name: 'payload', type: 'object', required: true, description: '本次输入的业务参数。' },
      {
        name: 'payload.text',
        type: 'string',
        required: true,
        description: SERVICE_ENDPOINTS.sessionMessage.textDescription
      }
    ],
    requestExample: {
      session_id: 'YOUR_SESSION_ID',
      payload: { text: SERVICE_COMMON.messageExample }
    },
    responseType: SERVICE_ENDPOINTS.sessionMessage.responseType,
    responseExample: receiptExample,
    notes: [...serviceNotes, SERVICE_ENDPOINTS.sessionMessage.lifecycleNote]
  },
  {
    id: 'service-session-action',
    title: SERVICE_ENDPOINTS.sessionAction.title,
    group: SERVICE_GROUPS.openPlatformSessions,
    method: 'POST',
    path: '/api/v1/open-platform/sessions/actions',
    auth: 'service',
    summary: SERVICE_ENDPOINTS.sessionAction.summary,
    scopes: ['sessions.write'],
    parameters: inputParameters,
    body: [
      {
        name: 'session_id',
        type: 'string',
        required: true,
        description: SERVICE_COMMON.sessionDescription
      },
      { name: 'payload', type: 'object', required: true, description: '本次输入的业务参数。' },
      {
        name: 'payload.execution_id',
        type: 'string',
        required: true,
        description: SERVICE_ENDPOINTS.sessionAction.executionDescription
      },
      {
        name: 'payload.action_key',
        type: 'string',
        required: true,
        description: SERVICE_ENDPOINTS.sessionAction.actionDescription
      },
      {
        name: 'payload.context',
        type: 'object',
        description: SERVICE_ENDPOINTS.sessionAction.contextDescription
      }
    ],
    requestExample: {
      session_id: 'YOUR_SESSION_ID',
      payload: { execution_id: 'execution-example', action_key: 'confirm' }
    },
    responseType: SERVICE_ENDPOINTS.sessionAction.responseType,
    responseExample: {
      session_id: 'session-example',
      input_id: 'open-platform:integration-example:session-example:action:request-001',
      root_input_id: 'open-platform:integration-example:session-example:action:request-001',
      event_id: 'open-platform:integration-example:session-example:action:request-001',
      status: 'queued'
    },
    notes: [
      ...serviceNotes,
      SERVICE_ENDPOINTS.sessionAction.exampleNote,
      SERVICE_ENDPOINTS.sessionAction.reservedFieldsNote
    ]
  },
  {
    id: 'service-session-callback',
    title: SERVICE_ENDPOINTS.sessionCallback.title,
    group: SERVICE_GROUPS.openPlatformSessions,
    method: 'POST',
    path: '/api/v1/open-platform/sessions/callbacks',
    auth: 'service',
    summary: SERVICE_ENDPOINTS.sessionCallback.summary,
    scopes: ['sessions.write'],
    parameters: inputParameters,
    body: [
      {
        name: 'session_id',
        type: 'string',
        required: true,
        description: SERVICE_COMMON.sessionDescription
      },
      { name: 'payload', type: 'object', required: true, description: '本次输入的业务参数。' },
      {
        name: 'payload.callback_id',
        type: 'string',
        required: true,
        description: SERVICE_ENDPOINTS.sessionCallback.callbackDescription
      },
      {
        name: 'payload.event_type',
        type: 'string',
        required: true,
        description: SERVICE_ENDPOINTS.sessionCallback.eventDescription
      },
      {
        name: 'payload.execution_id',
        type: 'string',
        description: SERVICE_ENDPOINTS.sessionCallback.executionDescription
      },
      {
        name: 'payload.workflow_id',
        type: 'string',
        description: SERVICE_ENDPOINTS.sessionCallback.workflowDescription
      },
      {
        name: 'payload.payload',
        type: 'object',
        description: SERVICE_ENDPOINTS.sessionCallback.payloadDescription
      }
    ],
    requestExample: {
      session_id: 'YOUR_SESSION_ID',
      payload: {
        callback_id: 'callback-example',
        event_type: 'confirmed',
        execution_id: 'execution-example'
      }
    },
    responseType: SERVICE_ENDPOINTS.sessionCallback.responseType,
    responseExample: {
      session_id: 'session-example',
      input_id: 'open-platform:integration-example:session-example:callback:request-001',
      root_input_id: 'open-platform:integration-example:session-example:callback:request-001',
      event_id: 'open-platform:integration-example:session-example:callback:request-001',
      status: 'queued'
    },
    notes: [
      ...serviceNotes,
      SERVICE_ENDPOINTS.sessionCallback.ownershipNote,
      SERVICE_ENDPOINTS.sessionCallback.reservedFieldsNote
    ]
  },
  {
    id: 'service-session-command',
    title: SERVICE_ENDPOINTS.sessionCommand.title,
    group: SERVICE_GROUPS.openPlatformSessions,
    method: 'POST',
    path: '/api/v1/open-platform/sessions/commands',
    auth: 'service',
    summary: SERVICE_ENDPOINTS.sessionCommand.summary,
    scopes: ['sessions.write'],
    parameters: inputParameters,
    body: [
      {
        name: 'session_id',
        type: 'string',
        required: true,
        description: SERVICE_COMMON.sessionDescription
      },
      { name: 'payload', type: 'object', required: true, description: '本次输入的业务参数。' },
      {
        name: 'payload.command',
        type: 'string',
        required: true,
        description: SERVICE_ENDPOINTS.sessionCommand.commandDescription
      },
      {
        name: 'payload.execution_id',
        type: 'string',
        description: SERVICE_ENDPOINTS.sessionCommand.executionDescription
      },
      {
        name: 'payload.reason',
        type: 'string',
        description: SERVICE_ENDPOINTS.sessionCommand.reasonDescription
      }
    ],
    requestExample: { session_id: 'YOUR_SESSION_ID', payload: { command: 'close_session' } },
    responseType: SERVICE_ENDPOINTS.sessionCommand.responseType,
    responseExample: {
      session_id: 'session-example',
      input_id: 'open-platform:integration-example:session-example:command:request-001',
      root_input_id: 'open-platform:integration-example:session-example:command:request-001',
      event_id: 'open-platform:integration-example:session-example:command:request-001',
      status: 'closed'
    },
    notes: [...serviceNotes, SERVICE_ENDPOINTS.sessionCommand.ownershipNote]
  },
  {
    id: 'service-session-events',
    title: SERVICE_ENDPOINTS.sessionEvents.title,
    group: SERVICE_GROUPS.openPlatformEvents,
    method: 'GET',
    path: '/api/v1/open-platform/sessions/events',
    auth: 'service',
    summary: SERVICE_ENDPOINTS.sessionEvents.summary,
    scopes: ['sessions.events.read'],
    parameters: [
      sessionParameter,
      {
        name: 'cursor',
        location: 'query',
        type: 'string',
        description: SERVICE_ENDPOINTS.sessionEvents.cursorDescription
      },
      {
        name: 'limit',
        location: 'query',
        type: 'integer',
        description: SERVICE_ENDPOINTS.sessionEvents.limitDescription
      }
    ],
    queryExample: { sessionId: 'YOUR_SESSION_ID', limit: 100 },
    responseType: SERVICE_ENDPOINTS.sessionEvents.responseType,
    responseExample: { session_id: 'session-example', next_cursor: null, events: [] },
    notes: [
      ...serviceNotes,
      SERVICE_ENDPOINTS.sessionEvents.scopeNote,
      SERVICE_ENDPOINTS.sessionEvents.fieldsNote,
      SERVICE_ENDPOINTS.sessionEvents.cursorNote
    ]
  },
  {
    id: 'service-webhook-create',
    title: SERVICE_ENDPOINTS.webhookCreate.title,
    group: SERVICE_GROUPS.webhookManagement,
    method: 'POST',
    path: '/api/v1/webhook-subscriptions',
    auth: 'openapi',
    summary: SERVICE_ENDPOINTS.webhookCreate.summary,
    body: [
      {
        name: 'session_id',
        type: 'string',
        description: SERVICE_ENDPOINTS.webhookCreate.sessionDescription
      },
      {
        name: 'endpoint_url',
        type: 'string',
        required: true,
        description: SERVICE_ENDPOINTS.webhookCreate.endpointDescription
      },
      {
        name: 'signing_secret',
        type: 'string',
        required: true,
        description: SERVICE_ENDPOINTS.webhookCreate.secretDescription
      }
    ],
    requestExample: {
      session_id: 'session-example',
      endpoint_url: 'https://example.com/skillforge/events',
      signing_secret: 'replace-with-your-signing-secret'
    },
    responseType: SERVICE_ENDPOINTS.webhookCreate.responseType,
    responseExample: {
      id: 3001,
      session_id: 'session-example',
      endpoint_url: 'https://example.com/skillforge/events',
      status: 'active',
      created_at: '2026-09-13T00:00:00Z'
    },
    notes: [
      exampleNote,
      SERVICE_ENDPOINTS.webhookCreate.roleNote,
      SERVICE_ENDPOINTS.webhookCreate.separationNote,
      SERVICE_ENDPOINTS.webhookCreate.headersNote
    ]
  },
  {
    id: 'service-webhook-list',
    title: SERVICE_ENDPOINTS.webhookList.title,
    group: SERVICE_GROUPS.webhookManagement,
    method: 'GET',
    path: '/api/v1/webhook-subscriptions',
    auth: 'openapi',
    summary: SERVICE_ENDPOINTS.webhookList.summary,
    responseType: SERVICE_ENDPOINTS.webhookList.responseType,
    responseExample: [],
    notes: [exampleNote, SERVICE_ENDPOINTS.webhookList.authNote]
  },
  {
    id: 'service-webhook-pause',
    title: SERVICE_ENDPOINTS.webhookPause.title,
    group: SERVICE_GROUPS.webhookManagement,
    method: 'PATCH',
    path: '/api/v1/webhook-subscriptions/pause',
    auth: 'openapi',
    summary: SERVICE_ENDPOINTS.webhookPause.summary,
    body: [
      {
        name: 'subscription_id',
        type: 'integer',
        required: true,
        description: SERVICE_ENDPOINTS.webhookPause.subscriptionDescription
      }
    ],
    requestExample: { subscription_id: 3001 },
    responseType: SERVICE_ENDPOINTS.webhookPause.responseType,
    responseExample: { id: 3001, paused: true },
    notes: [exampleNote, SERVICE_ENDPOINTS.webhookPause.roleNote]
  },
  {
    id: 'service-knowledge-search',
    title: SERVICE_ENDPOINTS.knowledgeSearch.title,
    group: SERVICE_GROUPS.knowledgeSearch,
    method: 'POST',
    path: '/api/v1/knowledge/search',
    auth: 'channel',
    summary: SERVICE_ENDPOINTS.knowledgeSearch.summary,
    body: [
      {
        name: 'kb_id',
        type: 'string',
        required: true,
        description: SERVICE_ENDPOINTS.knowledgeSearch.knowledgeBaseDescription
      },
      {
        name: 'query',
        type: 'string',
        required: true,
        description: SERVICE_ENDPOINTS.knowledgeSearch.queryDescription
      },
      {
        name: 'top_k',
        type: 'integer',
        description: SERVICE_ENDPOINTS.knowledgeSearch.topKDescription
      },
      {
        name: 'retrieval_mode',
        type: 'string',
        description: SERVICE_ENDPOINTS.knowledgeSearch.modeDescription
      },
      {
        name: 'score_threshold_enabled',
        type: 'boolean',
        description: SERVICE_ENDPOINTS.knowledgeSearch.thresholdEnabledDescription
      },
      {
        name: 'score_threshold',
        type: 'number',
        description: SERVICE_ENDPOINTS.knowledgeSearch.thresholdDescription
      },
      {
        name: 'vector_weight',
        type: 'number',
        description: SERVICE_ENDPOINTS.knowledgeSearch.vectorWeightDescription
      },
      {
        name: 'rerank_enabled',
        type: 'boolean',
        description: SERVICE_ENDPOINTS.knowledgeSearch.rerankDescription
      },
      {
        name: 'source_version',
        type: 'string',
        description: SERVICE_ENDPOINTS.knowledgeSearch.sourceVersionDescription
      }
    ],
    requestExample: { kb_id: '4001', query: SERVICE_COMMON.knowledgeQueryExample, top_k: 5 },
    responseType: SERVICE_ENDPOINTS.knowledgeSearch.responseType,
    responseExample: { items: [] },
    notes: [...channelNotes, SERVICE_ENDPOINTS.knowledgeSearch.archiveNote]
  },
  {
    id: 'service-knowledge-list',
    title: SERVICE_ENDPOINTS.knowledgeList.title,
    group: SERVICE_GROUPS.knowledgeSearch,
    method: 'GET',
    path: '/api/v1/knowledge/bases',
    auth: 'channel',
    summary: SERVICE_ENDPOINTS.knowledgeList.summary,
    responseType: SERVICE_ENDPOINTS.knowledgeList.responseType,
    responseExample: {
      total: 1,
      items: [
        {
          id: 4001,
          name: SERVICE_COMMON.knowledgeNameExample,
          description: SERVICE_COMMON.knowledgeDescriptionExample,
          status: 'active',
          document_count: 0,
          chunk_count: 0,
          can_delete: true
        }
      ]
    },
    notes: channelNotes
  },
  {
    id: 'service-knowledge-detail',
    title: SERVICE_ENDPOINTS.knowledgeDetail.title,
    group: SERVICE_GROUPS.knowledgeSearch,
    method: 'GET',
    path: '/api/v1/knowledge/bases/detail',
    auth: 'channel',
    summary: SERVICE_ENDPOINTS.knowledgeDetail.summary,
    parameters: [kbParameter],
    queryExample: { kbId: 'YOUR_KB_ID' },
    responseType: SERVICE_ENDPOINTS.knowledgeDetail.responseType,
    responseExample: {
      id: 4001,
      name: SERVICE_COMMON.knowledgeNameExample,
      description: SERVICE_COMMON.knowledgeDescriptionExample,
      status: 'active',
      active_revision_id: null,
      chunk_size: null,
      chunk_overlap: null,
      indexing_mode: 'high_quality',
      retrieval_mode: 'semantic',
      top_k: 5,
      score_threshold_enabled: false,
      score_threshold: null,
      vector_weight: null,
      rerank_enabled: false,
      document_count: 0,
      chunk_count: 0,
      can_delete: true,
      created_at: '2026-09-13T00:00:00Z'
    },
    notes: channelNotes
  },
  {
    id: 'service-knowledge-documents',
    title: SERVICE_ENDPOINTS.knowledgeDocuments.title,
    group: SERVICE_GROUPS.knowledgeSearch,
    method: 'GET',
    path: '/api/v1/knowledge/bases/documents',
    auth: 'channel',
    summary: '按当前文档标题和状态筛选后分页读取摘要；不读取正文，不调用模型。',
    parameters: [
      kbParameter,
      {
        name: 'page',
        location: 'query',
        type: 'integer',
        description: SERVICE_ENDPOINTS.knowledgeDocuments.pageDescription
      },
      {
        name: 'page_size',
        location: 'query',
        type: 'integer',
        description: '每页条数，默认 20，最大 200；参数名为 page_size。'
      },
      {
        name: 'status',
        location: 'query',
        type: 'string',
        description: SERVICE_ENDPOINTS.knowledgeDocuments.statusDescription
      },
      {
        name: 'titleQuery',
        location: 'query',
        type: 'string',
        description:
          '可选当前文档标题关键词，最多 128 个 Unicode 码点。忽略首尾空白，空白不筛选；大小写不敏感的字面包含，%、_ 和反斜杠不作为通配符。与状态组合筛选后计算 total 并分页。'
      }
    ],
    queryExample: {
      kbId: 'YOUR_KB_ID',
      page: 1,
      page_size: 20,
      status: 'ready',
      titleQuery: '报告'
    },
    responseType: SERVICE_ENDPOINTS.knowledgeDocuments.responseType,
    responseExample: { total: 0, items: [] },
    notes: channelNotes
  }
]
