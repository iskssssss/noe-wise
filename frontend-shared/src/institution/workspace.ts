import type { InjectionKey } from 'vue'

/** 只承载已获准展示的机构目录，不作为登录身份或业务授权。 */
export interface InstitutionNode {
  id: string
  label: string
  parentId?: string | null
  status?: string
  description?: string
}

export interface InstitutionSection {
  key: string
  label: string
  disabled?: boolean
  reason?: string
}

export interface InstitutionTreeNode extends InstitutionNode {
  children: InstitutionTreeNode[]
}

/** 仅影响标题层级和内边距，机构、用户和权限不经共享展示上下文传播。 */
export const institutionWorkspaceEmbeddedKey: InjectionKey<boolean> = Symbol(
  'institution-workspace-embedded'
)

/** 上级不在可见目录时按可见根呈现；无效重复和循环不能伪装成正常机构树。 */
export function buildInstitutionTree(nodes: readonly InstitutionNode[]): InstitutionTreeNode[] {
  const map = new Map<string, InstitutionTreeNode>()
  for (const node of nodes) {
    if (!node.id || map.has(node.id)) throw Error('机构目录标识重复或缺失，请刷新后重试')
    map.set(node.id, { ...node, children: [] })
  }
  const complete = new Set<string>()
  for (const node of map.values()) {
    const path = new Set<string>()
    let current: InstitutionTreeNode | undefined = node
    while (current && !complete.has(current.id)) {
      if (path.has(current.id)) throw Error('机构目录关系存在循环，请刷新后重试')
      path.add(current.id)
      current = current.parentId ? map.get(current.parentId) : undefined
    }
    path.forEach((id) => complete.add(id))
  }
  const roots: InstitutionTreeNode[] = []
  for (const node of map.values()) {
    const parent = node.parentId ? map.get(node.parentId) : undefined
    if (parent) parent.children.push(node)
    else roots.push(node)
  }
  return roots
}

export function matchesInstitution(node: InstitutionNode, query: string): boolean {
  const term = query.trim().toLocaleLowerCase()
  return (
    !term || `${node.label} ${node.id} ${node.description ?? ''}`.toLocaleLowerCase().includes(term)
  )
}

/** 搜索保留匹配项的可见祖先，不自行补取或命名无权机构。 */
export function filterInstitutionTree(
  nodes: readonly InstitutionTreeNode[],
  query: string
): InstitutionTreeNode[] {
  if (!query.trim()) return [...nodes]
  return nodes.flatMap((node) => {
    const children = filterInstitutionTree(node.children, query)
    return matchesInstitution(node, query) || children.length ? [{ ...node, children }] : []
  })
}

/** 只沿调用方提供的可见关系展开当前节点。 */
export function institutionAncestors(nodes: readonly InstitutionNode[], id: string): string[] {
  const map = new Map(nodes.map((node) => [node.id, node]))
  const result: string[] = []
  const seen = new Set([id])
  let parent = map.get(id)?.parentId
  while (parent && map.has(parent) && !seen.has(parent)) {
    result.unshift(parent)
    seen.add(parent)
    parent = map.get(parent)?.parentId
  }
  return result
}
