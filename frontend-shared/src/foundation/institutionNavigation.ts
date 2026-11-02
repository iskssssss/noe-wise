import type { AdminMenu } from '../admin/types'
import { navigationLeaves } from '../admin/navigationTree'

const platformInstitutionPaths = new Set([
  '/platform/tenants',
  '/platform/institutions',
  '/platform/institution-relations',
  '/platform/institution-credentials',
  '/platform/agent-grants',
  '/platform/agent-deployments'
])
const tenantInstitutionPaths = new Set([
  '/admin/sub-institutions',
  '/admin/agent-definitions',
  '/admin/agent-grants',
  '/admin/agent-deployments',
  '/admin/headquarters-usage',
  '/admin/agents',
  '/admin/usage',
  '/admin/governance',
  '/admin/api-docs'
])

/** 聚合入口接受已有动作之一，实际分区仍检查其独立动作与目标。 */
export function institutionWorkspaceActions(platform: boolean): string[] {
  return platform
    ? [
        'foundation.tenants.read',
        'platform.institution_relations.read',
        'platform.institutions.read',
        'platform.institution_credentials.read',
        'platform.agent_grants.read',
        'platform.agent_deployments.read',
        'foundation.tenants.create',
        'platform.institutions.provision',
        'platform.institutions.associate',
        'platform.agent_deployments.create',
        'platform.usage.summary.read',
        'platform.usage.details.read',
        'platform.usage.events.read'
      ]
    : [
        'foundation.agents.read',
        'tenant.sub_institutions.read',
        'tenant.agent_deployments.read',
        'tenant.agent_definitions.read',
        'tenant.agent_grants.read',
        'foundation.usage.read',
        'foundation.service_principals.read',
        'foundation.access.read',
        'foundation.preferences.manage',
        'foundation.entitlements.read',
        'foundation.settings.read',
        'tenant.sub_institutions.usage_summary.read',
        'tenant.sub_institutions.usage_details.read',
        'tenant.sub_institutions.usage_events.read'
      ]
}

/** 仅重排服务端已经提供的入口；领域权限仍由路由和每次真实请求核验。 */
export function institutionNavigation(
  items: AdminMenu[],
  platform: boolean,
  permissions?: readonly string[]
): AdminMenu[] {
  const leaves = navigationLeaves(items).filter((item) => item.path)
  const seen = new Set<string>()
  const unique = leaves.filter((item) => {
    if (seen.has(item.path!)) return false
    seen.add(item.path!)
    return true
  })
  const homePath = platform ? '/platform/home' : '/admin/home'
  const home = unique.filter((item) => item.path === homePath)
  const institutional = unique.filter((item) =>
    (platform ? platformInstitutionPaths : tenantInstitutionPaths).has(item.path!)
  )
  const remaining = unique.filter((item) => item.path !== homePath && !institutional.includes(item))
  const result: AdminMenu[] = [...home]
  const workspaceSource =
    institutional.length ||
    unique.some((item) =>
      (platform
        ? ['/platform/usage']
        : ['/admin/foundation/access', '/admin/foundation/catalog']
      ).includes(item.path!)
    )
  if (
    workspaceSource &&
    (!permissions ||
      institutionWorkspaceActions(platform).some((action) => permissions.includes(action)))
  )
    result.push({
      title: '机构工作台',
      path: platform ? '/platform/tenants' : '/admin/institutions'
    })
  if (platform) {
    if (remaining.length) result.push({ title: '全局中心', children: remaining })
    const operations = institutional.filter((item) => item.path !== '/platform/tenants')
    if (operations.length) result.push({ title: '跨机构运营', children: operations })
  } else {
    // 自有智能体等仍是正式维护视图；机构工作台增加上下文，不删除已有直接入口。
    if (remaining.length) result.push({ title: '资源与运营', children: remaining })
    if (institutional.length) result.push({ title: '机构维护视图', children: institutional })
  }
  return result
}
