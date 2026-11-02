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

/** Element Plus 的分组索引只属于显示树，不充当路由或权限标识。 */
export function expandedNavigation(
  items: AdminMenu[],
  activePath?: string,
  search = false,
  prefix = 'group'
): string[] {
  return items.flatMap((item, index) => {
    if (!item.children?.length) return []
    const key = `${prefix}.${index}`
    const containsActive = navigationLeaves(item.children).some(
      (child) => child.path === activePath
    )
    return search || containsActive
      ? [key, ...expandedNavigation(item.children, activePath, search, key)]
      : []
  })
}
