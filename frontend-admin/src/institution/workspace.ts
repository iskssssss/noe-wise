import type { InstitutionNode, InstitutionSection } from '@skillforge/shared/institution/workspace'
import type { RequestOptions } from '@skillforge/shared/utils/http'
import type { SubInstitution, InstitutionTargetOperation } from '../api/institutionManagement'
import type { EntityOption } from '@skillforge/shared/admin'

export const headquartersUsagePermissions = ['summary', 'details', 'events'].map(
  (kind) => `tenant.sub_institutions.usage_${kind}.read`
)

/** 维护目标仅收窄原动作范围，目录和工作台均不改变当前登录机构。 */
export function institutionSections(
  permissions: readonly string[],
  actorTenant: string,
  targetTenant: string
): InstitutionSection[] {
  const owns = actorTenant === targetTenant
  const has = (...items: string[]) => items.some((item) => permissions.includes(item))
  const sections: InstitutionSection[] = [{ key: 'overview', label: '概览' }]
  if (
    owns &&
    has(
      'foundation.access.read',
      'foundation.preferences.manage',
      'foundation.entitlements.read',
      'foundation.settings.read'
    )
  )
    sections.push({ key: 'settings', label: '机构设置' })
  if (owns && has('foundation.service_principals.read'))
    sections.push({ key: 'access', label: '接入与凭证' })
  if (
    has('tenant.agent_deployments.read', 'tenant.agent_grants.read') ||
    (owns && has('foundation.agents.read', 'tenant.agent_definitions.read'))
  )
    sections.push({ key: 'agents', label: '智能体' })
  if (owns && has('tenant.sub_institutions.read', 'tenant.sub_institutions.provision'))
    sections.push({ key: 'children', label: '下级机构' })
  if ((owns && has('foundation.usage.read')) || (!owns && has(...headquartersUsagePermissions)))
    sections.push({ key: 'usage', label: '用量' })
  return sections
}

export interface InstitutionDirectoryApi {
  subInstitutions: {
    list(
      page: number,
      options?: RequestOptions
    ): Promise<{
      items: SubInstitution[]
      total: number
      tree_revision: number
    }>
  }
  targetOptions(
    operation: InstitutionTargetOperation,
    query: {
      page: number
      page_size: number
    },
    options?: RequestOptions
  ): Promise<{ items: EntityOption[]; total: number }>
}

/** 轻量关系完整分页；动作目标候选仅补充可操作对象，不推断未知上级关系。 */
export async function loadInstitutionDirectory(
  api: InstitutionDirectoryApi,
  actorTenant: string,
  permissions: readonly string[],
  signal?: AbortSignal,
  requireCurrent: () => void = () => {}
): Promise<InstitutionNode[]> {
  const ensureCurrent = () => {
    signal?.throwIfAborted()
    requireCurrent()
  }
  ensureCurrent()
  const nodes = new Map<string, InstitutionNode>([
    [
      actorTenant,
      {
        id: actorTenant,
        label: '本机构',
        description: '当前登录机构'
      }
    ]
  ])
  if (permissions.includes('tenant.sub_institutions.read')) {
    let revision: number | undefined
    let total = 0
    const loaded = new Set<string>()
    for (let page = 1; ; page++) {
      ensureCurrent()
      const response = await api.subInstitutions.list(page, { signal, silent: true })
      ensureCurrent()
      if (revision !== undefined && response.tree_revision !== revision)
        throw Error('机构关系在读取期间已变化，请重新刷新机构目录')
      revision = response.tree_revision
      if (page > 1 && response.total !== total) throw Error('机构目录在读取期间已变化，请重新刷新')
      total = response.total
      const fresh = response.items.filter((row) => !loaded.has(row.tenant_id))
      if (!fresh.length && loaded.size < total) throw Error('机构目录尚未完整读取，请重试')
      for (const row of fresh) {
        loaded.add(row.tenant_id)
        nodes.set(row.tenant_id, {
          id: row.tenant_id,
          label: row.name,
          parentId: row.parent_tenant_id,
          status: row.status,
          description: '直属下级机构'
        })
      }
      if (loaded.size >= total) break
    }
  }
  const operations: InstitutionTargetOperation[] = []
  if (permissions.includes('tenant.agent_deployments.read'))
    operations.push('tenant.agent_deployments.read')
  if (permissions.includes('tenant.agent_grants.create'))
    operations.push('tenant.agent_grants.create')
  for (const operation of operations) {
    const loaded = new Set<string>()
    let total: number | undefined
    for (let page = 1; ; page++) {
      ensureCurrent()
      const response = await api.targetOptions(
        operation,
        { page, page_size: 200 },
        { signal, silent: true }
      )
      ensureCurrent()
      if (total !== undefined && response.total !== total)
        throw Error('操作目标目录已变化，请重新读取')
      total = response.total
      const fresh = response.items.filter((row) => !loaded.has(row.id))
      if (!fresh.length && loaded.size < response.total)
        throw Error('操作目标目录已变化，请重新读取')
      for (const row of fresh) {
        loaded.add(row.id)
        if (!nodes.has(row.id))
          nodes.set(row.id, {
            id: row.id,
            label: row.label,
            description: row.description || `获准操作目标 · ${row.id}`
          })
      }
      if (loaded.size >= response.total) break
    }
  }
  return [...nodes.values()]
}
