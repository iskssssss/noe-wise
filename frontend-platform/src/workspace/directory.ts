import type { InstitutionNode } from '@skillforge/shared/institution/workspace'
import { api } from '../api'
import { createOperationOptions, type PlatformOptionOperation } from '../api/entityOptions'

const targetOperations: PlatformOptionOperation[] = [
  'platform.institutions.read',
  'platform.agent_grants.read',
  'platform.agent_deployments.read',
  'platform.institution_credentials.read',
  'platform.agent_grants.create',
  'platform.agent_deployments.create',
  'platform.institution_credentials.issue'
]

export interface InstitutionDirectory {
  nodes: InstitutionNode[]
  complete: boolean
  hierarchy: boolean
}

/** 只组合当前有权读取的轻量目录；关系不足时保留可操作机构，不补造父节点。 */
export async function loadInstitutionDirectory(
  allowed: (action: string) => boolean,
  signal: AbortSignal,
  progress: (loaded: number, total: number) => void
): Promise<InstitutionDirectory> {
  const nodes = new Map<string, InstitutionNode>()
  const hierarchy = allowed('platform.institution_relations.read')
  if (allowed('foundation.tenants.read')) {
    for (let page = 1; ; page++) {
      const result = await api.admin.listTenants({ page, limit: 200 }, { signal, silent: true })
      if (signal.aborted || !allowed('foundation.tenants.read'))
        throw Error('机构目录读取身份已变化')
      let added = 0
      for (const row of result.items) {
        const id = String(row.id)
        if (!nodes.has(id)) added++
        nodes.set(id, { id, label: row.name, status: row.status, description: row.slug })
      }
      progress(nodes.size, result.total)
      if (nodes.size >= result.total) break
      if (!added) throw Error('机构目录在分页期间已变化，请重新读取')
    }
  }
  if (hierarchy) {
    let revision: number | undefined
    const read = new Set<string>()
    const relations = new Map<string, string | null>()
    for (let page = 1; ; page++) {
      const result = await api.institutionRelations.list(page, { signal, silent: true })
      if (signal.aborted || !allowed('platform.institution_relations.read'))
        throw Error('机构关系读取身份已变化')
      if (revision !== undefined && revision !== result.tree_revision)
        throw Error('机构树在分页期间已变化，请重新读取')
      revision = result.tree_revision
      let added = 0
      for (const row of result.items) {
        if (!read.has(row.tenant_id)) added++
        read.add(row.tenant_id)
        relations.set(row.tenant_id, row.parent_tenant_id)
        const previous = nodes.get(row.tenant_id)
        nodes.set(row.tenant_id, {
          ...previous,
          id: row.tenant_id,
          label: row.name,
          status: row.status
        })
      }
      progress(read.size, result.total)
      if (read.size >= result.total) break
      if (!added) throw Error('机构树目录已变化，请重新读取')
    }
    for (const [id, parent] of relations) {
      const node = nodes.get(id)!
      if (parent && nodes.has(parent)) node.parentId = parent
    }
  }
  for (const operation of targetOperations.filter(allowed)) {
    const options = await createOperationOptions('tenants', operation).load('', signal)
    if (signal.aborted || !allowed(operation)) throw Error('可操作机构范围已变化')
    for (const item of options) {
      if (!nodes.has(item.id))
        nodes.set(item.id, { id: item.id, label: item.label, description: item.description })
    }
    progress(nodes.size, nodes.size)
  }
  return { nodes: [...nodes.values()], complete: true, hierarchy }
}
