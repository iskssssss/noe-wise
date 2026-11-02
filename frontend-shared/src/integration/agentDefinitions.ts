import type { RequestOptions } from '../utils/http'

/** 平台与机构自有共享定义的同一公开响应；所有实体身份保持十进制字符串。 */
export interface AgentTemplateItem {
  distribution_policy: 'OPEN_INSTALL' | 'AUTHORIZED_DEPLOY' | null
  definition_id: string
  draft_revision: number
  owner_kind: string
  owner_tenant_id: string | null
  current_published_revision: string | null
  template_key: string
  name: string
  version: string
  has_unpublished_changes: boolean
  category?: string | null
  tags?: string[]
  description?: string | null
  icon_url?: string | null
  author?: string | null
  author_type?: string
  status: string
  price_type?: string
  price_cents?: number
  install_count?: number
  published_at?: string | null
  created_at?: string
  updated_at?: string
}

/** 工作草稿或指定发布历史的完整定义快照。 */
export interface AgentTemplateDetail extends AgentTemplateItem {
  definition: AgentTemplateDefinition
}

export interface AgentTemplateExecutionConfig {
  max_business_model_calls: number | null
  max_tool_calls: number | null
  max_knowledge_calls: number | null
  max_total_tokens: number | null
  max_wall_time_seconds: number | null
}

export interface AgentTemplateCapability {
  capability_type: 'SKILL' | 'TOOL'
  capability_definition_id: string
  capability_version_id: string | null
  skill_config: { activation_mode: 'ALWAYS' | 'ON_DEMAND'; token_budget: number | null } | null
  platform_tool_name: string | null
  priority: number
}

export interface AgentTemplateResourceSlot {
  key: string
  label: string
  description: string
  capability_type: 'TOOL' | 'WORKFLOW' | 'KNOWLEDGE_SOURCE'
}

export interface AgentTemplateDelegationSlot {
  key: string
  label: string
  description: string
}

export interface AgentTemplateDefinition {
  system_prompt: string
  role_description: string
  model_config_id: string | null
  execution_config: AgentTemplateExecutionConfig
  capabilities: AgentTemplateCapability[]
  resource_slots: AgentTemplateResourceSlot[]
  delegation_slots: AgentTemplateDelegationSlot[]
}

export interface AgentTemplateValidation {
  valid: boolean
  issues: Array<{ path: string; code: string; message: string }>
}

/** 精确草稿的声明影响，不表示模型、连接或执行权限全部就绪。 */
export interface AgentDefinitionImpact {
  definition_id: string
  draft_revision: number
  current_published_revision: string | null
  changes: {
    added_resource_slots: AgentTemplateResourceSlot[]
    changed_resource_slots: AgentTemplateResourceSlot[]
    removed_resource_slot_keys: string[]
    added_delegation_slots: AgentTemplateDelegationSlot[]
    removed_delegation_slot_keys: string[]
    added_parameter_keys: string[]
    removed_parameter_keys: string[]
  }
  visible_deployment_count: number
  deployments: Array<{
    tenant_id: string
    deployment_id: string
    agent_id: string
    deployment_revision: number
    current_declarations_satisfied: boolean | null
    draft_declarations_satisfied: boolean
    current_issues: Array<{ path: string; code: string; message: string }>
    draft_issues: Array<{ path: string; code: string; message: string }>
  }>
}

/** 对应共享 CreateAgentTemplateRequest；所有权与授权不属于可写内容。 */
export interface AgentDefinitionCreateInput {
  name: string
  distribution_policy: 'OPEN_INSTALL' | 'AUTHORIZED_DEPLOY'
  category?: string | null
  tags?: string[] | null
  description?: string | null
  icon_url?: string | null
  price_type?: string | null
  price_cents?: number | null
  definition?: AgentTemplateDefinition | null
}

/** 对应 UpdateAgentTemplateRequest；省略策略保留现值，策略不可显式清空。 */
export type AgentDefinitionUpdateInput = Partial<AgentDefinitionCreateInput>

export interface AgentDefinitionsQuery {
  page?: number
  limit?: number
  status?: string
  distribution_policy?: 'OPEN_INSTALL' | 'AUTHORIZED_DEPLOY'
  category?: string
  name?: string
}

export type AgentDefinitionsBase = '/platform/agent-definitions' | '/tenant/agent-definitions'

export interface AgentDefinitionsHttp {
  get: <T>(path: string, options?: RequestOptions) => Promise<T>
  post: <T>(path: string, body?: unknown, options?: RequestOptions) => Promise<T>
  put: <T>(path: string, body?: unknown, options?: RequestOptions) => Promise<T>
}

/** 两个宿主同构的定义生命周期客户端；身份、目标范围和CAS均由正式服务端校验。 */
export function createAgentDefinitionsApi(
  http: AgentDefinitionsHttp,
  base: AgentDefinitionsBase = '/platform/agent-definitions'
) {
  const query = (values: Record<string, string | number | undefined>) => {
    const params = new URLSearchParams()
    for (const [name, value] of Object.entries(values)) {
      if (value !== undefined && value !== '') params.set(name, String(value))
    }
    return params.size ? `?${params}` : ''
  }
  // 调用方保存原内容与key后可核验未知结果；工厂只派发一次，不自行重试或换key。
  const writeOptions = (key: string) => ({ headers: { 'Idempotency-Key': key } })
  const command = (id: string, revision: number) => ({
    definition_id: id,
    expected_revision: revision
  })
  return {
    list: (params: AgentDefinitionsQuery = {}, options?: RequestOptions) =>
      http.get<{ total: number; items: AgentTemplateItem[] }>(
        `${base}${query({ ...params })}`,
        options
      ),
    detail: (id: string, options?: RequestOptions) =>
      http.get<AgentTemplateDetail>(`${base}/detail${query({ definition_id: id })}`, options),
    history: (id: string, options?: RequestOptions) =>
      http.get<AgentTemplateDetail[]>(`${base}/history${query({ definition_id: id })}`, options),
    impact: (id: string, revision: number, options?: RequestOptions) =>
      http.get<AgentDefinitionImpact>(
        `${base}/impact${query({ definition_id: id, draft_revision: revision })}`,
        options
      ),
    previewSaved: (id: string, revision: number, options?: RequestOptions) =>
      http.post<{ validation: AgentTemplateValidation; impact: AgentDefinitionImpact | null }>(
        `${base}/preview`,
        command(id, revision),
        options
      ),
    create: (data: AgentDefinitionCreateInput, key: string = crypto.randomUUID()) =>
      http.post<AgentTemplateItem>(base, data, writeOptions(key)),
    update: (
      id: string,
      revision: number,
      data: AgentDefinitionUpdateInput,
      key: string = crypto.randomUUID()
    ) =>
      http.put<AgentTemplateItem>(
        `${base}/update`,
        { ...command(id, revision), payload: data },
        writeOptions(key)
      ),
    validate: (id: string, revision: number, key: string = crypto.randomUUID()) =>
      http.post<AgentTemplateValidation>(
        `${base}/validate`,
        command(id, revision),
        writeOptions(key)
      ),
    publish: (id: string, revision: number, key: string = crypto.randomUUID()) =>
      http.post<AgentTemplateItem>(`${base}/publish`, command(id, revision), writeOptions(key)),
    deprecate: (id: string, revision: number, key: string = crypto.randomUUID()) =>
      http.post<AgentTemplateItem>(`${base}/deprecate`, command(id, revision), writeOptions(key)),
    restore: (
      id: string,
      revision: number,
      publishedRevision: string,
      key: string = crypto.randomUUID()
    ) =>
      http.post<AgentTemplateItem>(
        `${base}/restore`,
        { ...command(id, revision), published_revision: publishedRevision },
        writeOptions(key)
      )
  }
}

export type AgentDefinitionsApi = ReturnType<typeof createAgentDefinitionsApi>
