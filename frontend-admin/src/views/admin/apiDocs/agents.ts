import type { ApiDocEndpoint, DocField } from './types'

// 事实源：AgentController、CreateAgentRequest、UpdateAgentRequest 与 Agent*Response。
const group = '智能体管理'
const agentId: DocField = {
  name: 'agent_id',
  type: 'string',
  required: true,
  description: '智能体 ID，可从智能体列表或创建结果获取。'
}
const item = {
  id: '2001',
  agent_key: 'support-agent',
  name: '客服助手',
  template_id: null,
  template_name: null,
  status: 'draft',
  created_at: '2026-09-21T12:00:00Z'
}
const writeNotes = [
  '使用 OpenAPI 渠道 API Key + HMAC，租户由签名凭证确定。',
  '示例 ID 需替换为当前机构的实际值；写操作参数放在 JSON 请求体中。'
]
const fields: DocField[] = [
  { name: 'name', type: 'string', description: '智能体名称。' },
  { name: 'system_prompt', type: 'string', description: '系统提示词；发布前必须非空。' },
  {
    name: 'model_config_id',
    type: 'string',
    description: '已启用的平台 LLM 模型 ID；未指定时按模板选择或继承机构默认。'
  },
  { name: 'config', type: 'object', description: '智能体通用配置。' }
]

export const agentEndpoints: ApiDocEndpoint[] = [
  {
    id: 'agent-list',
    title: '查询智能体列表',
    group,
    method: 'GET',
    path: '/api/v1/agents',
    auth: 'openapi',
    summary: '查询当前机构的全部智能体，按创建时间倒序返回。',
    responseType: 'AgentListResponse：total 与 items。',
    responseExample: { total: 1, items: [item] },
    notes: ['当前接口不分页。智能体 ID 可用于详情、发布及对话接口的 agent_id。']
  },
  {
    id: 'agent-templates',
    title: '查询智能体模板',
    group,
    method: 'GET',
    path: '/api/v1/agents/templates',
    auth: 'openapi',
    summary: '查询已发布的智能体模板，用于创建智能体时选择初始配置。',
    responseType: 'AgentTemplateListResponse：total 与 items。',
    responseExample: {
      total: 1,
      items: [
        {
          id: '1001',
          template_key: 'support',
          name: '客服模板',
          version: '1.0',
          description: '客服助手初始配置',
          definition: { system_prompt: '根据用户问题提供清晰准确的回答。' },
          config_schema: []
        }
      ]
    },
    notes: ['模板仅提供创建时的初始值，后续修改模板不会自动同步到已创建的智能体。']
  },
  {
    id: 'agent-create',
    title: '创建智能体',
    group,
    method: 'POST',
    path: '/api/v1/agents',
    auth: 'openapi',
    summary: '直接创建智能体或基于已发布模板创建，初始状态为 draft。',
    body: [
      { name: 'template_id', type: 'integer', description: '可选模板 ID；省略时直接创建。' },
      {
        name: 'agent_key',
        type: 'string',
        description: '唯一业务标识；省略时自动生成，重复返回 409。'
      },
      ...fields.map((field) => ({ ...field, required: field.name === 'name' }))
    ],
    requestExample: {
      name: '客服助手',
      agent_key: 'support-agent',
      system_prompt: '根据用户问题提供清晰准确的回答。'
    },
    responseType: 'AgentItemResponse：智能体基本信息。',
    responseExample: item,
    notes: writeNotes
  },
  {
    id: 'agent-detail',
    title: '查询智能体详情',
    group,
    method: 'GET',
    path: '/api/v1/agents/detail',
    auth: 'openapi',
    summary: '读取智能体本体配置及当前能力挂载集合。',
    parameters: [{ ...agentId, name: 'agentId', location: 'query' }],
    queryExample: { agentId: '2001' },
    responseType: 'AgentDetailResponse：基本信息、提示词、模型配置与能力挂载。',
    responseExample: {
      ...item,
      system_prompt: '根据用户问题提供清晰准确的回答。',
      role_description: null,
      planning_rules: null,
      model_config_id: null,
      config: {},
      published_at: null,
      mount_set_generation: 0,
      mounts: []
    },
    notes: ['只能读取当前机构的智能体，不存在或不属于当前机构时返回 404。']
  },
  {
    id: 'agent-update',
    title: '更新智能体',
    group,
    method: 'PATCH',
    path: '/api/v1/agents/update',
    auth: 'openapi',
    summary: '更新名称、提示词、模型选择、规划规则或状态；未提供的字段不修改。',
    body: [
      agentId,
      { name: 'payload', type: 'object', required: true, description: '需要更新的业务字段。' },
      ...fields.map((field) => ({
        ...field,
        name: `payload.${field.name}`,
        description:
          field.name === 'model_config_id'
            ? '模型 ID；省略或 null 不修改，空串清除为继承机构默认。'
            : field.description
      })),
      {
        name: 'payload.status',
        type: 'string',
        description: 'draft / published / disabled / archived；设为 published 时要求提示词非空。'
      },
      { name: 'payload.role_description', type: 'string', description: '角色描述，空串清空。' },
      {
        name: 'payload.planning_rules',
        type: 'object',
        description: '规划与协作规则；null 不修改。'
      }
    ],
    requestExample: {
      agent_id: '2001',
      payload: { name: '客服助手', system_prompt: '根据用户问题提供清晰准确的回答。' }
    },
    responseType: 'AgentItemResponse：更新后的基本信息。',
    responseExample: item,
    notes: [...writeNotes, 'system_prompt 空串表示清空；config 为整体配置对象更新。']
  },
  {
    id: 'agent-publish',
    title: '发布智能体',
    group,
    method: 'POST',
    path: '/api/v1/agents/publish',
    auth: 'openapi',
    summary: '发布智能体供会话直聊调用；未配置系统提示词时返回 422。',
    body: [agentId],
    requestExample: { agent_id: '2001' },
    responseType: 'AgentPublishResponse：发布状态与智能体 ID。',
    responseExample: { status: 'published', agent_id: '2001' },
    notes: writeNotes
  },
  {
    id: 'agent-delete',
    title: '删除智能体',
    group,
    method: 'DELETE',
    path: '/api/v1/agents/delete',
    auth: 'openapi',
    summary: '删除当前机构的智能体。',
    body: [agentId],
    requestExample: { agent_id: '2001' },
    responseType: 'DeleteResponse：删除状态。',
    responseExample: { status: 'deleted' },
    notes: [...writeNotes, '删除受数据库引用完整性约束；如只需停止使用，可更新状态为 disabled。']
  }
]
