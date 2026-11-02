import { onScopeDispose, ref, watch } from 'vue'
import type { HttpClient } from '../utils/http'
import type { AdminMenu } from '../admin/types'
import { institutionWorkspaceActions } from './institutionNavigation'

export interface NavigationItem {
  title: string
  path: string | null
  permission: string | null
  children: NavigationItem[]
}

/** 导航只消费服务端受控路由与当前身份，账号切换后的旧响应不能更新菜单。 */
export function useFoundationNavigation(
  request: HttpClient['request'],
  prefix: string,
  identity: () => string
) {
  const menus = ref<AdminMenu[]>([])
  const error = ref('')
  let generation = 0
  let controller: AbortController | undefined
  function project(items: NavigationItem[]): AdminMenu[] {
    return items.flatMap<AdminMenu>((item) => {
      const children = project(item.children ?? [])
      if (children.length) return [{ title: item.title, children }]
      return item.path?.startsWith(prefix === '/admin' ? '/platform/' : '/admin/')
        ? [{ title: item.title, path: item.path }]
        : []
    })
  }
  async function reload() {
    const version = ++generation
    controller?.abort()
    controller = new AbortController()
    menus.value = []
    error.value = ''
    if (!identity()) return
    try {
      const current = await request<NavigationItem[]>(
        'GET',
        `${prefix}/foundation/catalog/navigation/current`,
        undefined,
        false,
        { signal: controller.signal, silent: true }
      )
      if (version === generation) menus.value = project(current)
    } catch (failure) {
      if (version === generation && !controller.signal.aborted)
        error.value = failure instanceof Error ? failure.message : '菜单加载失败'
    }
  }
  watch(identity, reload, { immediate: true })
  onScopeDispose(() => {
    generation++
    controller?.abort()
  })
  return { menus, error, reload }
}

const messagingPagePermissions = ['inbox', 'read', 'publish', 'configure', 'operate'].map(
  (action) => `foundation.messaging.${action}`
)

/** 与宿主编译路由一致的入口权限；聚合页面返回当前已拥有的实际动作，不授予业务权限。 */
export function routePermission(path: string, permissions?: readonly string[]): string | undefined {
  const parts = path.split('/').filter(Boolean)
  const platform = parts[0] === 'platform'
  if (!['platform', 'admin'].includes(parts[0] ?? '')) return undefined
  const segment = parts[1]
  if (!segment || segment === 'home') return undefined
  if (!platform && segment === 'tools') {
    return ['foundation.capabilities.read', 'foundation.connectors.read']
      .find((permission) => permissions?.includes(permission)) ?? 'foundation.capabilities.read'
  }
  // 工作台是已有动作的聚合入口，不要求额外的机构总目录权限。
  if ((platform && segment === 'tenants') || (!platform && segment === 'institutions')) {
    const actions = institutionWorkspaceActions(platform)
    return actions.find((action) => permissions?.includes(action)) ?? actions[0]
  }
  if (segment === 'foundation') {
    if (parts[2] === 'messaging' && permissions) {
      return (
        messagingPagePermissions.find((permission) => permissions.includes(permission)) ??
        'foundation.messaging.inbox'
      )
    }
    const mapping: Record<string, string> = {
      access: 'access.read',
      catalog: 'preferences.manage',
      security: 'security.self',
      messaging: 'messaging.inbox',
      assets: 'files.read',
      jobs: 'jobs.read',
      operations: 'operations.audit_read'
    }
    return mapping[parts[2] ?? ''] ? `foundation.${mapping[parts[2]!]}` : undefined
  }
  const integrationActions: Record<string, string> = platform
    ? {
        'management-principals': 'platform.management_principals.read',
        institutions: 'platform.institutions.read',
        'institution-relations': 'platform.institution_relations.read',
        'institution-credentials': 'platform.institution_credentials.read',
        'agent-grants': 'platform.agent_grants.read',
        'agent-templates': 'platform.agent_definitions.read',
        'agent-deployments': 'platform.agent_deployments.read',
        usage: 'platform.usage.summary.read'
      }
    : {
        'agent-deployments': 'tenant.agent_deployments.read',
        'sub-institutions': 'tenant.sub_institutions.read',
        'agent-definitions': 'tenant.agent_definitions.read',
        'agent-grants': 'tenant.agent_grants.read',
        'headquarters-usage': 'tenant.sub_institutions.usage_summary.read',
        usage: 'foundation.usage.read'
      }
  if (integrationActions[segment ?? '']) return integrationActions[segment!]!
  const mapping: Record<string, string> = {
    agents: 'agents',
    'agent-deployments': 'agents',
    teams: 'teams',
    workflows: 'workflows',
    skills: 'skills',
    capabilities: 'capabilities',
    'tool-deployments': 'capabilities',
    connectors: 'connectors',
    knowledge: 'knowledge',
    memory: 'memory',
    chat: 'sessions',
    sessions: 'sessions',
    trajectories: 'sessions',
    'conversation-tasks': 'sessions',
    handoff: 'handoff',
    channels: 'channels',
    'api-docs': 'service_principals',
    dashboard: platform ? 'audit' : 'usage',
    'billing-records': 'billing',
    governance: 'audit',
    marketplace: 'agents',
    settings: 'models',
    tenants: 'tenants',
    models: 'models',
    'system-settings': 'runtime_settings',
    'system-agents': 'system_agents',
    'system-agent-tools': 'system_agent_tools',
    'agent-templates': 'agent_templates',
    billing: 'billing',
    operations: 'audit',
    review: 'marketplace'
  }
  const area = mapping[segment]
  if (!area) return undefined
  return `foundation.${area}.${['new', 'create'].includes(parts[2] ?? '') ? 'create' : 'read'}`
}
