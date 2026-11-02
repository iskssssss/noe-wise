import type { EntityOption } from '@skillforge/shared/admin'
import type { RequestOptions } from '@skillforge/shared/utils/http'
import type { AgentGrant } from '@skillforge/shared/integration/agentDeployments'

export type InstitutionTargetOperation =
  | 'tenant.agent_grants.create'
  | 'tenant.agent_deployments.read'
  | 'tenant.agent_deployments.create'
  | 'tenant.agent_deployments.bind'
  | 'tenant.agent_deployments.switch_authorization'

export interface SubInstitution {
  tenant_id: string
  parent_tenant_id: string | null
  name: string
  status: string
  revision: number
  tree_revision: number
}
export interface ProvisionSubInstitution {
  namespace: string
  external_institution_ref: string
  name: string
  default_plan_code: string
  quota_api_calls_per_month?: number | null
  quota_max_knowledge_bases?: number | null
  quota_max_members?: number | null
  quota_max_channels?: number | null
  rate_limit_per_minute?: number | null
}
export interface GrantBoundary {
  actions: Array<'deploy' | 'execute'>
  expires_at: string | null
  allow_regrant: boolean
  remaining_depth: number
}
export interface CreateInstitutionGrant extends GrantBoundary {
  definition_id: string
  target_tenant_id: string
  parent_grant_id?: string | null
}
export function createInstitutionManagementApi(http: {
  get: <T>(path: string, options?: RequestOptions) => Promise<T>
  post: <T>(path: string, body?: unknown, options?: RequestOptions) => Promise<T>
}) {
  const command = <T>(path: string, input: unknown, key: string) =>
    http.post<T>(path, input, { headers: { 'Idempotency-Key': key }, silent: true })
  return {
    targetOptions: (
      operation: InstitutionTargetOperation,
      query: { q?: string; page?: number; page_size?: number; id?: string } = {},
      options?: RequestOptions
    ) => {
      const params = new URLSearchParams({
        operation,
        page: String(query.page ?? 1),
        page_size: String(query.page_size ?? 200)
      })
      if (query.q) params.set('q', query.q)
      if (query.id) params.set('id', query.id)
      return http.get<{ items: EntityOption[]; total: number; page: number; page_size: number }>(
        `/tenant/operation-options/targets?${params}`,
        options
      )
    },
    subInstitutions: {
      planOptions: (
        query: { q?: string; page?: number; page_size?: number; id?: string } = {},
        options?: RequestOptions
      ) => {
        const params = new URLSearchParams({
          page: String(query.page ?? 1),
          page_size: String(query.page_size ?? 200)
        })
        if (query.q) params.set('q', query.q)
        if (query.id) params.set('id', query.id)
        return http.get<{ items: EntityOption[]; total: number; page: number; page_size: number }>(
          `/tenant/sub-institutions/provision-options/plans?${params}`,
          options
        )
      },
      list: (page = 1, options?: RequestOptions) =>
        http.get<{
          items: SubInstitution[]
          total: number
          page: number
          page_size: number
          tree_revision: number
        }>(`/tenant/sub-institutions?page=${page}&page_size=20`, options),
      provision: (input: ProvisionSubInstitution, key: string) =>
        command<SubInstitution>('/tenant/sub-institutions/provision', input, key)
    },
    grants: {
      list: (options?: RequestOptions) =>
        http.get<{ items: AgentGrant[]; total: number }>('/tenant/agent-grants', options),
      create: (input: CreateInstitutionGrant, key: string) =>
        command<AgentGrant>('/tenant/agent-grants', input, key),
      narrow: (
        input: GrantBoundary & { grant_id: string; expected_revision: number },
        key: string
      ) => command<AgentGrant>('/tenant/agent-grants/narrow', input, key),
      revoke: (input: { grant_id: string; expected_revision: number }, key: string) =>
        command<AgentGrant>('/tenant/agent-grants/revoke', input, key)
    }
  }
}
