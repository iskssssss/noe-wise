import { api, foundationRequest } from './index'
import type { EntityOption } from '@skillforge/shared/admin'

// 机构 API 返回数字 ID；选择控件与页面请求状态按字符串身份保存。
export async function loadTenantOptions(
  query: string,
  signal?: AbortSignal
): Promise<EntityOption[]> {
  const result = await api.admin.listTenants({ q: query, limit: 200 }, { signal })
  return result.items.map((tenant) => ({
    id: String(tenant.id),
    label: tenant.name,
    description: tenant.slug
  }))
}
export async function resolveTenantOption(id: string): Promise<EntityOption> {
  const tenant = await api.admin.getTenant(id)
  return { id: String(tenant.id), label: tenant.name, description: tenant.slug }
}

export type PlatformOptionKind = 'tenants' | 'definitions' | 'integrations'
export type PlatformOptionOperation =
  | 'platform.institutions.read'
  | 'platform.institutions.associate'
  | 'platform.agent_grants.read'
  | 'platform.agent_grants.create'
  | 'platform.agent_deployments.read'
  | 'platform.agent_deployments.create'
  | 'platform.institution_credentials.read'
  | 'platform.institution_credentials.issue'
  | 'platform.management_principals.create'

interface OperationOptionPage {
  items: EntityOption[]
  total: number
  page: number
  page_size: number
}

/** 候选只依赖真实操作及其目标闭包，不额外请求整个机构或定义目录。 */
export function createOperationOptions(
  kind: PlatformOptionKind,
  operation: PlatformOptionOperation,
  tenantId?: string
) {
  async function request(query: string, page: number, signal?: AbortSignal, id?: string) {
    const params = new URLSearchParams({
      operation,
      q: query,
      page: String(page),
      page_size: '200'
    })
    if (tenantId) params.set('tenant_id', tenantId)
    if (id) params.set('id', id)
    return foundationRequest<OperationOptionPage>(
      'GET',
      `/platform/operation-options/${kind}?${params}`,
      undefined,
      false,
      { signal, silent: true }
    )
  }
  return {
    async load(query: string, signal?: AbortSignal): Promise<EntityOption[]> {
      if (kind === 'integrations' && !tenantId) return []
      const options: EntityOption[] = []
      for (let page = 1; ; page++) {
        const result = await request(query, page, signal)
        const next = result.items.filter((row) => !options.some((option) => option.id === row.id))
        if (!next.length && options.length < result.total) throw Error('候选目录已变化，请重新搜索')
        options.push(...next)
        if (options.length >= result.total) return options
      }
    },
    async resolve(id: string): Promise<EntityOption | undefined> {
      if (kind === 'integrations' && !tenantId) return undefined
      return (await request('', 1, undefined, id)).items.find((option) => option.id === id)
    }
  }
}

/** 套餐是既有目录值，开通表单只消费真实启用项。 */
export function createProvisionPlanOptions() {
  async function request(query: string, page: number, signal?: AbortSignal, id?: string) {
    const params = new URLSearchParams({ q: query, page: String(page), page_size: '200' })
    if (id) params.set('id', id)
    return foundationRequest<OperationOptionPage>(
      'GET',
      `/platform/operation-options/provision/plans?${params}`,
      undefined,
      false,
      { signal, silent: true }
    )
  }
  return {
    async load(query: string, signal?: AbortSignal): Promise<EntityOption[]> {
      const options: EntityOption[] = []
      for (let page = 1; ; page++) {
        const result = await request(query, page, signal)
        const next = result.items.filter((row) => !options.some((option) => option.id === row.id))
        if (!next.length && options.length < result.total) throw Error('套餐目录已变化，请重新搜索')
        options.push(...next)
        if (options.length >= result.total) return options
      }
    },
    async resolve(id: string) {
      return (await request('', 1, undefined, id)).items.find((option) => option.id === id)
    }
  }
}

export interface NamespaceOptions {
  items: EntityOption[]
  allow_create: boolean
}
interface NamespaceOptionPage extends OperationOptionPage {
  allow_create: boolean
}
export type NamespaceOperation =
  | 'platform.institutions.provision'
  | 'platform.institutions.associate'
  | 'platform.management_principals.create'

/** 完整读取真实授予集合；all 的开放新值能力由服务端单独声明，不以空列表猜测。 */
export async function loadNamespaceOptions(
  operation: NamespaceOperation,
  scopes: string[],
  signal?: AbortSignal
): Promise<NamespaceOptions> {
  const options: EntityOption[] = []
  let allowCreate: boolean | undefined
  for (let page = 1; ; page++) {
    const params = new URLSearchParams({ operation, page: String(page), page_size: '200' })
    for (const scope of [...scopes].sort()) params.append('scopes', scope)
    const result = await foundationRequest<NamespaceOptionPage>(
      'GET',
      `/platform/operation-options/namespaces?${params}`,
      undefined,
      false,
      { signal, silent: true }
    )
    if (typeof result.allow_create !== 'boolean') throw Error('命名空间授权选项无效，请重新读取')
    if (allowCreate !== undefined && allowCreate !== result.allow_create)
      throw Error('命名空间授权已变化，请重新读取')
    allowCreate = result.allow_create
    const next = result.items.filter((row) => !options.some((option) => option.id === row.id))
    if (!next.length && options.length < result.total) throw Error('命名空间目录已变化，请重新读取')
    options.push(...next)
    if (options.length >= result.total) return { items: options, allow_create: allowCreate }
  }
}
