import type { ApiDocEndpoint, DocParameter } from './types'

// 来源：ChatEndUserController、TrajectoryController、BillingController、AgentRuntimeCallbackController；
// 轨迹结构来自 TrajectoryView / TraceView，回调签名来自 CallbackPayloads.signingBytes。
const session: DocParameter = {
  name: 'session_id',
  location: 'query',
  type: 'string',
  required: true,
  description: '当前机构的会话 ID。'
}
const usage = {
  calls: 0,
  tokens: null,
  prompt_tokens: null,
  completion_tokens: null,
  amount_by_currency: {},
  unknown_usage_calls: 0
}
export const runtimeEndpoints: ApiDocEndpoint[] = [
  {
    id: 'end-users-list',
    title: '查询终端用户',
    group: '终端用户',
    method: 'GET',
    path: '/api/v1/chat/end-users',
    auth: 'openapi',
    summary: '列出当前机构已有的终端用户，供创建会话时选择，不自动创建默认用户。',
    responseType: 'application/json · ChatEndUserItemResponse[]',
    responseExample: [{ id: 123, display_name: '示例用户', status: 'active' }],
    notes: ['使用 OpenAPI 渠道 API Key + HMAC。响应直接为数组。']
  },
  {
    id: 'end-users-create',
    title: '登记终端用户',
    group: '终端用户',
    method: 'POST',
    path: '/api/v1/chat/end-users',
    auth: 'openapi',
    summary: '显式登记当前机构的终端用户，使用返回的 id 创建会话。',
    body: [
      {
        name: 'display_name',
        type: 'string',
        required: true,
        description: '非空显示名称，最多 128 字符。'
      }
    ],
    requestExample: { display_name: '示例用户' },
    responseType: 'application/json · ChatEndUserItemResponse',
    responseExample: { id: 123, display_name: '示例用户', status: 'active' },
    notes: ['使用 OpenAPI 渠道 API Key + HMAC。']
  },
  {
    id: 'trajectories-list',
    title: '检索 Agent 轨迹',
    group: '执行诊断',
    method: 'GET',
    path: '/api/v1/chat/trajectories',
    auth: 'management',
    scopes: ['foundation.sessions.read'],
    summary: '当前机构管理成员按业务根输入检索轨迹；输入输出只包含当前授权的公开内容。',
    parameters: [
      {
        name: 'session_id',
        location: 'query',
        type: 'string',
        description: '精确查找会话，可省略。'
      },
      {
        name: 'trace_id',
        location: 'query',
        type: 'string',
        description: '精确匹配该轨迹真实关联的 TraceID。'
      },
      {
        name: 'agent_id',
        location: 'query',
        type: 'string',
        description: '智能体 ID；以十进制字符串传递。'
      },
      { name: 'model', location: 'query', type: 'string', description: '筛选轨迹实际调用的模型。' },
      { name: 'tool', location: 'query', type: 'string', description: '筛选轨迹实际调用的工具。' },
      {
        name: 'status',
        location: 'query',
        type: 'string',
        description: '业务执行状态，结果未知与失败分别表达。'
      },
      {
        name: 'started_from',
        location: 'query',
        type: 'ISO-8601',
        description: '开始时间下界；未提供时默认近 7 天。'
      },
      {
        name: 'started_to',
        location: 'query',
        type: 'ISO-8601',
        description: '开始时间上界；默认本次读取时间。'
      },
      {
        name: 'duration_min_ms',
        location: 'query',
        type: 'string',
        description: '非负整数毫秒下界；未知或未完成耗时不命中耗时筛选。'
      },
      {
        name: 'duration_max_ms',
        location: 'query',
        type: 'string',
        description: '非负整数毫秒上界；表示主执行含等待的真实跨度。'
      },
      {
        name: 'input_keyword',
        location: 'query',
        type: 'string',
        description: '对公开用户输入进行字面关键词匹配。'
      },
      {
        name: 'output_keyword',
        location: 'query',
        type: 'string',
        description: '对公开结果进行字面关键词匹配。'
      },
      { name: 'page', location: 'query', type: 'integer', description: '正整数页码，默认 1。' },
      {
        name: 'page_size',
        location: 'query',
        type: 'integer',
        description: '每页条数支持 20、50、100，默认 20。'
      }
    ],
    queryExample: { session_id: 'YOUR_SESSION_ID', page: 1, page_size: 20 },
    responseType: 'application/json · TrajectoryView.ListPage',
    responseExample: {
      items: [],
      page: 1,
      page_size: 20,
      total: 0,
      read_at: '2026-10-25T01:00:00Z'
    },
    notes: [
      '使用当前管理成员的 Bearer 访问令牌和 foundation.sessions.read 权限；此接口不使用 OpenAPI 渠道 API Key。机构身份从当前认证作用域确定。',
      '所有筛选条件取交集，先匹配再分页与计数。列表中的 session_id + root_input_id 是详情的稳定定位键。',
      '平台管理端使用 /api/v1/platform/trajectories，并显式携带 tenant_id；仍须平台权限及逐资源授权。'
    ]
  },
  {
    id: 'trajectory-detail',
    title: '读取业务轨迹概览',
    group: '执行诊断',
    method: 'GET',
    path: '/api/v1/chat/trajectories/detail',
    auth: 'management',
    scopes: ['foundation.sessions.read'],
    summary: '读取用户输入、公开结果、已报告用量及当前执行树快照，不重新执行任务。',
    parameters: [
      session,
      {
        name: 'root_input_id',
        location: 'query',
        type: 'string',
        required: true,
        description: '轨迹列表返回的准确根输入身份；不能用当前 Run 或显示轮次序号代替。'
      }
    ],
    queryExample: { session_id: 'YOUR_SESSION_ID', root_input_id: 'YOUR_ROOT_INPUT_ID' },
    responseType: 'application/json · TrajectoryView.Detail',
    responseExample: {
      summary: {
        session_id: 'SESSION_ID',
        root_input_id: 'ROOT_INPUT_ID',
        input: null,
        output: null,
        status: 'RUNNING'
      },
      messages: [],
      usage: null,
      usage_summary: usage,
      snapshot: 'OPAQUE_SNAPSHOT',
      read_at: '2026-10-25T01:00:00Z'
    },
    notes: [
      '使用管理成员 Bearer 令牌，沿用列表权限。snapshot 必须原样传给后续 nodes 请求。公开结果不包含私有模型上下文或供应商思考内容。'
    ]
  },
  {
    id: 'trajectory-nodes',
    title: '读取或搜索执行树节点',
    group: '执行诊断',
    method: 'GET',
    path: '/api/v1/chat/trajectories/nodes',
    auth: 'management',
    scopes: ['foundation.sessions.read'],
    summary: '按真实调用关系读取 ENTRY、AGENT、STEP 及操作节点；搜索覆盖当前轨迹未加载的节点。',
    parameters: [
      session,
      {
        name: 'root_input_id',
        location: 'query',
        type: 'string',
        required: true,
        description: '准确业务根输入身份。'
      },
      {
        name: 'snapshot',
        location: 'query',
        type: 'string',
        description: '详情或前页返回的原始快照；冻结当前查询事实来源上界。'
      },
      {
        name: 'cursor',
        location: 'query',
        type: 'string',
        description: '沿用前页 next_cursor；不能跨资源、快照或搜索条件复用。'
      },
      { name: 'page_size', location: 'query', type: 'integer', description: '默认 50，最大 200。' },
      {
        name: 'node_id',
        location: 'query',
        type: 'string',
        description: '定位真实节点，也接受该节点的来源事件 ID。'
      },
      {
        name: 'query',
        location: 'query',
        type: 'string',
        description: '搜索节点名称、可见摘要、工具与错误信息；不搜索私有 reasoning。'
      }
    ],
    queryExample: {
      session_id: 'YOUR_SESSION_ID',
      root_input_id: 'YOUR_ROOT_INPUT_ID',
      snapshot: 'OPAQUE_SNAPSHOT',
      page_size: 50
    },
    responseType: 'application/json · TrajectoryView.ObservationPage',
    responseExample: {
      items: [],
      ancestors: [],
      next_cursor: null,
      snapshot: 'OPAQUE_SNAPSHOT',
      coverage: 'PARTIAL',
      read_at: '2026-10-25T01:00:00Z'
    },
    notes: [
      '使用管理成员 Bearer 令牌。items 是本页匹配节点，ancestors 提供必要祖先骨架；按 node_id 去重合并。',
      '节点 input/output 各自返回 {ref, availability}。实际重试保留为 attempt，事件数量不等于调用次数。未记录、受限与结果未知分别表达。'
    ]
  },
  {
    id: 'trajectory-content',
    title: '读取受控节点内容',
    group: '执行诊断',
    method: 'GET',
    path: '/api/v1/chat/trajectories/content',
    auth: 'management',
    scopes: ['foundation.sessions.read'],
    summary: '按节点返回的引用读取当前授权的输入或结果；读取动作接受逐资源检查并记录审计。',
    parameters: [
      session,
      {
        name: 'root_input_id',
        location: 'query',
        type: 'string',
        required: true,
        description: '准确业务根输入身份。'
      },
      {
        name: 'content_ref',
        location: 'query',
        type: 'string',
        required: true,
        description: '节点 input.ref 或 output.ref 的原始值；不可自行构造。'
      }
    ],
    queryExample: {
      session_id: 'YOUR_SESSION_ID',
      root_input_id: 'YOUR_ROOT_INPUT_ID',
      content_ref: 'CONTENT_REF'
    },
    responseType: 'application/json · TrajectoryView.Content',
    responseExample: {
      content_ref: 'CONTENT_REF',
      availability: 'NOT_RECORDED',
      schema_id: null,
      value: null
    },
    notes: [
      '使用管理成员 Bearer 令牌。受限内容不会通过 JSON 查看、错误或子轨迹引用绕过权限；空值不表示操作没有发生。'
    ]
  },
  {
    id: 'request-trace',
    title: '请求调用与用量',
    group: '执行诊断',
    method: 'GET',
    path: '/api/v1/billing/traces/detail',
    auth: 'openapi',
    summary: '按根请求关联调用、用量与会话输出，辅助定位耗时、失败和未知用量。',
    parameters: [
      {
        name: 'root_input_id',
        location: 'query',
        type: 'string',
        required: true,
        description:
          '已受理请求的准确根输入身份；作为查询参数进行 URL 编码，不从 Run ID 或时间推算。'
      }
    ],
    queryExample: { root_input_id: 'YOUR_ROOT_INPUT_ID' },
    responseType: 'application/json · TraceView',
    responseExample: {
      root_input_id: 'ROOT_INPUT_ID',
      timeline: [],
      usage_summary: usage,
      unresolved_items: []
    },
    notes: [
      '使用 OpenAPI 渠道 API Key + HMAC。',
      'unknown_usage_calls 与 unresolved_items 表示尚未解析的信息，不能当作用量为零或成功完成。'
    ]
  },
  {
    id: 'runtime-callback-delivery',
    title: '投递 Runtime 回调',
    group: '运行时回调',
    method: 'POST',
    path: '/api/v1/runtime/callbacks/deliveries',
    auth: 'callback',
    summary:
      '向已注册的 Runtime callback 投递签名结果。受理后由 worker 处理，不在 HTTP 请求中恢复执行。',
    parameters: [
      ...['X-Tenant-Id', 'X-Callback-Id', 'X-Delivery-Id'].map((name) => ({
        name,
        location: 'header' as const,
        type: 'string',
        required: true,
        description:
          name === 'X-Delivery-Id'
            ? '稳定投递标识；同一次投递重试复用。'
            : '必须与 Runtime 已注册的回调事实一致。'
      })),
      {
        name: 'X-Timestamp',
        location: 'header',
        type: 'string',
        required: true,
        description: '正整数 Unix 秒时间戳。'
      },
      {
        name: 'X-Signature',
        location: 'header',
        type: 'string',
        required: true,
        description: '按注册凭证及 callback-v1 规则计算的 HMAC-SHA256 签名。'
      }
    ],
    requestFile: 'callback-payload.bin',
    responseType: 'application/json · AgentRuntimeCallbackDeliveryResponse',
    responseExample: { status: 'ready', delivery_id: 'DELIVERY_ID' },
    notes: [
      '请求体是注册契约要求的原始字节，必须与签名字节完全一致；模板中的 callback-payload.bin 需按该契约准备。',
      '签名输入以换行连接：callback-v1、base64url(tenantId)、base64url(callbackId)、base64url(deliveryId)、timestamp、sha256(body) 小写十六进制。标识按 UTF-8 编码，base64url 不含填充。',
      '签名使用回调注册凭证，不复用渠道或 ServicePrincipal 的 HTTP 路径签名规则。',
      '成功返回 ready 回执；稳定重放返回已有回执，内容冲突为 409，签名失败为 401，投递被拒绝为 422，不可用为 503。旧 /api/v1/callbacks/registrations 已无 Controller 实现。'
    ]
  }
]
