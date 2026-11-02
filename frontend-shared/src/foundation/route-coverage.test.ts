import { existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import { routePermission } from './navigation'
import { institutionWorkspaceActions } from './institutionNavigation'

const foundation = {
  access: 'access.read',
  catalog: 'preferences.manage',
  security: 'security.self',
  messaging: 'messaging.inbox',
  assets: 'files.read',
  jobs: 'jobs.read',
  operations: 'operations.audit_read'
}
const domains = {
  admin: {
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
    dashboard: 'usage',
    'billing-records': 'billing',
    usage: 'usage',
    governance: 'audit',
    marketplace: 'agents',
    settings: 'models'
  },
  platform: {
    tenants: 'tenants',
    models: 'models',
    'system-settings': 'runtime_settings',
    'system-agents': 'system_agents',
    'system-agent-tools': 'system_agent_tools',
    'agent-templates': 'agent_templates',
    capabilities: 'capabilities',
    skills: 'skills',
    sessions: 'sessions',
    trajectories: 'sessions',
    billing: 'billing',
    operations: 'audit',
    review: 'marketplace',
    dashboard: 'audit'
  }
}

/** Source routes form the denominator; a new unbound page must fail this contract check. */
describe.each(['admin', 'platform'] as const)('真实%s路由全量权限绑定', (host) => {
  const repository = existsSync(resolve(process.cwd(), 'frontend-admin/package.json'))
    ? process.cwd()
    : resolve(process.cwd(), '..')
  const source = readFileSync(resolve(repository, `frontend-${host}/src/router/index.ts`), 'utf8')
  const children = [...source.matchAll(/path:\s*'([^']+)'/g)]
    .map((match) => match[1]!)
    .filter((path) => path && !path.startsWith('/'))
  it('每个编译业务路由和详情都有明确read/create权限，不能遗漏新增模块', () => {
    expect(children.length).toBeGreaterThan(25)
    for (const path of children) {
      if (path === 'home') {
        expect(routePermission(`/${host}/${path}`)).toBeUndefined()
        continue
      }
      const integration: Record<string, string> =
        host === 'platform'
          ? {
              'management-principals': 'platform.management_principals.read',
              institutions: 'platform.institutions.read',
              'institution-relations': 'platform.institution_relations.read',
              'institution-credentials': 'platform.institution_credentials.read',
              'agent-grants': 'platform.agent_grants.read',
              'agent-deployments': 'platform.agent_deployments.read',
              'agent-templates': 'platform.agent_definitions.read',
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
      const [area, detail] = path.split('/')
      if (
        (host === 'admin' && area === 'institutions') ||
        (host === 'platform' && area === 'tenants')
      ) {
        const actions = institutionWorkspaceActions(host === 'platform')
        expect(routePermission(`/${host}/${path}`)).toBe(actions[0])
        for (const permission of actions)
          expect(routePermission(`/${host}/${path}`, [permission])).toBe(permission)
        continue
      }
      if (host === 'admin' && area === 'tools') {
        expect(routePermission(`/${host}/${path}`)).toBe('foundation.capabilities.read')
        expect(routePermission(`/${host}/${path}`, ['foundation.connectors.read'])).toBe(
          'foundation.connectors.read'
        )
        continue
      }
      if (integration[area!]) {
        expect(routePermission(`/${host}/${path}`), path).toBe(integration[area!])
        continue
      }
      const action =
        area === 'foundation'
          ? foundation[detail as keyof typeof foundation]
          : `${domains[host][area as keyof (typeof domains)[typeof host]]}.${['new', 'create'].includes(detail ?? '') ? 'create' : 'read'}`
      expect(action, `missing fixture contract for ${host}/${path}`).not.toContain('undefined')
      expect(routePermission(`/${host}/${path}`), path).toBe(`foundation.${action}`)
    }
  })
  it('十进制大ID、深链Run/Turn和复合query不改变所属领域权限', () => {
    for (const path of children.filter((path) => path.includes(':'))) {
      const concrete = path.replace(/:[A-Za-z]+/g, '9223372036854775806')
      expect(routePermission(`/${host}/${concrete}?root_run_id=root-B&turn=7&tenant_id=B`)).toBe(
        routePermission(`/${host}/${path}`)
      )
    }
  })
  it('七个foundation域逐项权限不同，URL与假管理员角色不能扩大写权限', () => {
    for (const [area, permission] of Object.entries(foundation))
      expect(routePermission(`/${host}/foundation/${area}`)).toBe(`foundation.${permission}`)
    expect(new Set(Object.values(foundation)).size).toBe(7)
    expect(routePermission('/login')).toBeUndefined()
    expect(routePermission('/403')).toBeUndefined()
  })
})
