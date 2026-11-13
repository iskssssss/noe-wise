import type { AdminMenu } from './types'

/** 只筛选服务端已授权树；命中组名保留组内入口，叶节点支持标题和路由关键词。 */
export function filterNavigation(items: AdminMenu[], query: string): AdminMenu[] {
  const text = query.trim().toLocaleLowerCase()
  if (!text) return items
  return items.flatMap((item) => {
    if (`${item.title} ${item.path ?? ''}`.toLocaleLowerCase().includes(text)) return [item]
    const children = item.children ? filterNavigation(item.children, text) : []
    return children.length ? [{ ...item, children }] : []
  })
}

export function navigationLeaves(items: AdminMenu[]): AdminMenu[] {
  return items.flatMap((item) => (item.children?.length ? navigationLeaves(item.children) : [item]))
}

/**
 * 分组键按标题生成，筛选后仍指向同一个分组；同名兄弟节点才追加序号。
 * 叶节点使用路由路径，避免过滤重排后把展开状态套到另一个目录。
 */
export function navigationKeys(items: AdminMenu[], parent = 'section'): string[] {
  const seen = new Map<string, number>()
  return items.map((item, index) => {
    const name = item.children?.length
      ? item.title || `group-${index}`
      : item.path || item.title || `item-${index}`
    const base = `${parent}/${name}`
    const count = (seen.get(base) ?? 0) + 1
    seen.set(base, count)
    return count === 1 ? base : `${base}#${count}`
  })
}

/** 只展开当前页所在分组；搜索时展开全部命中分组。 */
export function expandedNavigation(
  items: AdminMenu[],
  activePath?: string,
  search = false,
  prefix = 'section'
): string[] {
  const keys = navigationKeys(items, prefix)
  return items.flatMap((item, index) => {
    if (!item.children?.length) return []
    const key = keys[index]!
    const containsActive = navigationLeaves(item.children).some(
      (child) => child.path === activePath
    )
    return search || containsActive
      ? [key, ...expandedNavigation(item.children, activePath, search, key)]
      : []
  })
}
