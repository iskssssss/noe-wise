import type { RequestOptions } from '../utils/http'

/** 部署响应保留服务端实例身份、当前跟随版本与绑定修订。 */
export interface AgentDeployment {
  deployment_id: string
  integration_id: string | null
  grant_id: string
  grant_revision: number
  /** 当前实例采用的完整授权依据；历史Run继续保留原冻结链。 */
  grant_chain: GrantChain
  tenant_id: string
  agent_id: string
  external_agent_ref: string
  definition_id: string
  definition_name: string
  definition_version: string
  mode: 'FOLLOW_PUBLISHED'
  deployment_revision: number
  ready: boolean
  issues: Array<{ path: string; code: string; message: string }>
  resource_slots: Array<{
    key: string
    label: string
    description: string
    capability_type: 'TOOL' | 'WORKFLOW' | 'KNOWLEDGE_SOURCE'
  }>
  delegation_slots: Array<{ key: string; label: string; description: string }>
  resource_bindings: ResourceSlotBinding[]
  delegation_bindings: Array<{ slot_key: string; target_agent_id: string }>
  parameters: Record<string, string | number | boolean>
}
export interface ResourceSlotBinding {
  slot_key: string
  mount: {
    capability_type: 'TOOL' | 'WORKFLOW' | 'KNOWLEDGE_SOURCE'
    capability_definition_id: string
    /** 服务端已解析的能力发布版本；读取后原样保留，缺省由正式装配解析。 */
    capability_version_id?: string | null
    /** 工具稳定部署及精确修订；变更部署引用时重新由服务端解析。 */
    tool_config?: { tool_deployment_id: string; tool_deployment_revision?: number | null }
    /** 工作流安装实例及精确修订，不能在编辑其他字段时丢失。 */
    workflow_config?: { workflow_instance_id: string; workflow_instance_revision?: number | null }
    knowledge_config?: { mode: 'FOLLOW_ACTIVE'; pinned_revision_id: null }
    /** 原挂载优先级，未提供时沿用正式装配默认规则。 */
    priority?: number | null
    /** 读取后的挂载状态，不因编辑其他绑定擅自激活 DISABLED。 */
    status?: 'ACTIVE' | 'DISABLED' | null
  }
}
export interface DeploymentBindings {
  resource_bindings: ResourceSlotBinding[]
  delegation_bindings: AgentDeployment['delegation_bindings']
  parameters: AgentDeployment['parameters']
}
/** 人员入口可明确授权目标，切换只改变同源实例采用的授权依据。 */
export interface DeploymentAuthorization {
  deployment_id: string
  expected_revision: number
  grant_id: string
  tenant_id?: string | null
}
/** 当前采用的授权链，hops按原授到末端排列，只包含治理元数据。 */
export interface GrantChain {
  definition_id: string
  owner_kind: string
  owner_tenant_id: string | null
  hops: GrantHop[]
}
/** 授权链上的固定身份与修订；平台原授没有父授权或机构关系。 */
export interface GrantHop {
  grant_id: string
  revision: number
  grantor_kind: string
  grantor_tenant_id: string | null
  target_tenant_id: string
  parent_grant_id: string | null
  parent_grant_revision: number | null
  relation_ref: string | null
  relation_revision: number | null
}
/** 目标机构获得的精确授权事实，保留源所有者、转授来源和当前修订。 */
export interface AgentGrant {
  grant_id: string
  definition_id: string
  target_tenant_id: string
  actions: string[]
  expires_at: string | null
  status: string
  revision: number
  definition_name: string
  definition_version: string
  owner_kind: string
  owner_tenant_id: string | null
  grantor_kind: string
  grantor_tenant_id: string | null
  parent_grant_id: string | null
  parent_grant_revision: number | null
  relation_ref: string | null
  relation_revision: number | null
  allow_regrant: boolean
  remaining_depth: number
}
export interface DeploymentTemplate {
  id: string
  name: string
  version: string
  definition: {
    resource_slots?: AgentDeployment['resource_slots']
    delegation_slots?: AgentDeployment['delegation_slots']
    system_prompt?: string
  }
}
type DeploymentHttp = {
  get: <T>(path: string, options?: RequestOptions) => Promise<T>
  post: <T>(path: string, body?: unknown, options?: RequestOptions) => Promise<T>
  put: <T>(path: string, body?: unknown, options?: RequestOptions) => Promise<T>
}
/** 人员 JWT 管理客户端；目标机构和授权仍由服务端逐项校验。 */
export function createAgentDeploymentsApi(
  http: DeploymentHttp,
  base = '/tenant/agent-deployments'
) {
  const platform = base.startsWith('/platform/')
  const writeOptions = (key: string) => ({ headers: { 'Idempotency-Key': key } })
  const query = (values: Record<string, string | undefined> = {}) => {
    const params = new URLSearchParams()
    for (const [key, value] of Object.entries(values)) if (value) params.set(key, value)
    return params.size ? `?${params}` : ''
  }
  return {
    list: (options?: RequestOptions, tenantId?: string) =>
      http.get<{ total: number; items: AgentDeployment[] }>(
        `${base}${query({ tenant_id: tenantId })}`,
        options
      ),
    detail: (deploymentId: string, options?: RequestOptions, tenantId?: string) =>
      http.get<AgentDeployment>(
        `${base}/detail${query({ deployment_id: deploymentId, tenant_id: tenantId })}`,
        options
      ),
    definitions: (options?: RequestOptions, tenantId?: string) =>
      http.get<{ total: number; items: DeploymentTemplate[] }>(
        `${base}/definitions${query({ tenant_id: tenantId })}`,
        options
      ),
    grants: (options?: RequestOptions, tenantId?: string) =>
      http.get<{ total: number; items: AgentGrant[] }>(
        `${base}/grants${query({ tenant_id: tenantId })}`,
        options
      ),
    create: (
      input: DeploymentBindings & {
        tenant_id?: string | null
        integration_id: string | null
        definition_id: string
        grant_id: string
        external_agent_ref: string
        name?: string
      },
      key: string = crypto.randomUUID(),
      options?: RequestOptions
    ) => {
      const { tenant_id, ...payload } = input
      return http.post<AgentDeployment>(base, platform ? { tenant_id, payload } : input, {
        ...writeOptions(key),
        ...options
      })
    },
    bindings: (
      input: DeploymentBindings & {
        deployment_id: string
        expected_revision: number
        tenant_id?: string | null
      },
      key: string = crypto.randomUUID()
    ) => {
      const { tenant_id, ...payload } = input
      return http.put<AgentDeployment>(
        `${base}/bindings`,
        platform ? { tenant_id, payload } : input,
        writeOptions(key)
      )
    },
    /** 显式稳定命令键用于重读原切换回执；旧命令不会覆盖后续部署修订。 */
    authorization: (input: DeploymentAuthorization, idempotencyKey: string) => {
      const { tenant_id, ...payload } = input
      return http.post<AgentDeployment>(
        `${base}/authorization`,
        platform ? { tenant_id, payload } : input,
        { headers: { 'Idempotency-Key': idempotencyKey } }
      )
    }
  }
}
