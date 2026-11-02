import type { AgentGrant } from '@skillforge/shared/integration/agentDeployments'
import type { RequestOptions } from '@skillforge/shared/utils/http'
/** 显式声明原授或父授权，以及动作、期限和转授边界；服务端逐项核验。 */
export interface CreateAgentGrant {
  definition_id: string
  target_tenant_id: string
  actions: Array<'deploy' | 'execute'>
  expires_at: string | null
  allow_regrant: boolean
  remaining_depth: number
  parent_grant_id?: string | null
}
/** 平台授权真实客户端，写入携带独立操作键并使用精确修订撤销。 */
export function createAgentGrantsApi(http: {
  get: <T>(path: string, options?: RequestOptions) => Promise<T>
  post: <T>(path: string, body?: unknown, options?: RequestOptions) => Promise<T>
}) {
  return {
    list: (tenantId: string, options?: RequestOptions) =>
      http.get<{ total: number; items: AgentGrant[] }>(
        `/platform/agent-grants?target_tenant_id=${encodeURIComponent(tenantId)}`,
        options
      ),
    create: (input: CreateAgentGrant, key: string) =>
      http.post<AgentGrant>('/platform/agent-grants', input, {
        headers: { 'Idempotency-Key': key },
        retryOnUnauthorized: false
      }),
    receipt: (input: CreateAgentGrant, key: string) =>
      http.post<{ status: 'FOUND' | 'NOT_FOUND'; result: AgentGrant | null }>(
        '/platform/agent-grants/receipt/query',
        input,
        { headers: { 'Idempotency-Key': key }, silent: true }
      ),
    revoke: (input: { grant_id: string; expected_revision: number }, key: string) =>
      http.post<AgentGrant>('/platform/agent-grants/revoke', input, {
        headers: { 'Idempotency-Key': key }
      })
  }
}
