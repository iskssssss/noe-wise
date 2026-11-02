import type { RequestOptions } from '@skillforge/shared/utils/http'

/** 平台管理机器的安全投影；范围由平台人员签发，读取不返回秘密。 */
export interface ManagementPrincipal {
  principal_id: string
  name: string
  status: string
  revision: number
  credential_version: number
  scopes: string[]
  target_tenant_ids: string[]
  target_definition_ids: string[]
  target_namespaces: string[]
  created_at: string
  updated_at: string
}
/** 创建管理机器时显式声明的动作及目标闭包。 */
export type ManagementPrincipalInput = Pick<
  ManagementPrincipal,
  'name' | 'scopes' | 'target_tenant_ids' | 'target_definition_ids' | 'target_namespaces'
>
/** 管理秘密仅首次写响应提供；幂等重读的两个秘密均为空。 */
export interface IssuedManagementPrincipal {
  principal: ManagementPrincipal
  management_key: string | null
  signing_secret: string | null
  replayed: boolean
}
/** 机构映射独立于机器凭证和 Agent 授权。 */
export interface InstitutionMapping {
  mapping_id: string
  tenant_id: string
  namespace: string
  external_institution_ref: string
  integration_id: string
  tenant_name: string
  revision: number
  created_at: string
}
/** 机构开通策略随本次显式开通提供；空配额继承套餐。 */
export interface ProvisionInstitution {
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
/** 机构执行凭证安全目录；principal_id 是轮换和撤销所用稳定主键。 */
export interface InstitutionCredential {
  id: string
  principal_id: string
  tenant_id: string
  integration_id: string
  api_key_prefix: string
  scopes: string[]
  status: string
  credential_version: number
  created_at: string
}
/** 执行秘密只保存在当前交付窗口，查询或幂等重读不能再次取得。 */
export interface IssuedInstitutionCredential {
  principal: InstitutionCredential
  api_key: string | null
  signing_secret: string | null
}
/** 人员管理列表按服务端页号读取，标识全程使用十进制字符串。 */
export interface ManagementPage<T> {
  items: T[]
  total: number
  page: number
  page_size: number
}
/** 平台人员 JWT 客户端的最小 HTTP 依赖。 */
type IntegrationHttp = {
  get: <T>(path: string, options?: RequestOptions) => Promise<T>
  post: <T>(path: string, body?: unknown, options?: RequestOptions) => Promise<T>
}
/** 所有写操作显式携带一次操作键；浏览器从不使用管理机器认证头。 */
export function createPlatformIntegrationApi(http: IntegrationHttp) {
  const query = (input: Record<string, string | number | undefined>) => {
    const values = new URLSearchParams()
    for (const [key, value] of Object.entries(input))
      if (value !== undefined && value !== '') values.set(key, String(value))
    return values.size ? `?${values}` : ''
  }
  const write = <T>(path: string, body: unknown, key: string) =>
    http.post<T>(path, body, { headers: { 'Idempotency-Key': key } })
  return {
    managementPrincipals: (page: number, options?: RequestOptions) =>
      http.get<ManagementPage<ManagementPrincipal>>(
        `/platform/management-principals${query({ page, page_size: 20 })}`,
        options
      ),
    createManagementPrincipal: (input: ManagementPrincipalInput, key: string) =>
      write<IssuedManagementPrincipal>('/platform/management-principals', input, key),
    rotateManagementPrincipal: (
      input: { principal_id: string; expected_revision: number },
      key: string
    ) => write<IssuedManagementPrincipal>('/platform/management-principals/rotate', input, key),
    revokeManagementPrincipal: (
      input: { principal_id: string; expected_revision: number },
      key: string
    ) => write<IssuedManagementPrincipal>('/platform/management-principals/revoke', input, key),
    institutions: (
      filters: { page: number; tenant_id?: string; namespace?: string },
      options?: RequestOptions
    ) =>
      http.get<ManagementPage<InstitutionMapping>>(
        `/platform/institutions${query({ ...filters, page_size: 20 })}`,
        options
      ),
    provision: (input: ProvisionInstitution, key: string) =>
      write<InstitutionMapping>('/platform/institutions/provision', input, key),
    associate: (
      input: { tenant_id: string; namespace: string; external_institution_ref: string },
      key: string
    ) => write<InstitutionMapping>('/platform/institutions/associate', input, key),
    institutionCredentials: (
      filters: { page: number; tenant_id: string; integration_id: string },
      options?: RequestOptions
    ) =>
      http.get<ManagementPage<InstitutionCredential>>(
        `/platform/institution-credentials${query({ ...filters, page_size: 20 })}`,
        options
      ),
    issueCredential: (
      input: { tenant_id: string; integration_id: string; scopes: string[] },
      key: string
    ) => write<IssuedInstitutionCredential>('/platform/institution-credentials/issue', input, key),
    rotateCredential: (
      input: {
        tenant_id: string
        integration_id: string
        principal_id: string
        expected_revision: number
      },
      key: string
    ) => write<IssuedInstitutionCredential>('/platform/institution-credentials/rotate', input, key),
    revokeCredential: (
      input: {
        tenant_id: string
        integration_id: string
        principal_id: string
        expected_revision: number
      },
      key: string
    ) => write<InstitutionCredential>('/platform/institution-credentials/revoke', input, key)
  }
}
