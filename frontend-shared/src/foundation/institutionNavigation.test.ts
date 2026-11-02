import { describe, expect, it } from 'vitest'
import { institutionNavigation } from './institutionNavigation'
import { routePermission } from './navigation'
import { navigationLeaves } from '../admin/navigationTree'

describe('institution navigation', () => {
  it('收拢已授权机构入口并保留全局与跨机构正式路径', () => {
    const original = [
      { title: '工作台', path: '/platform/home' },
      { title: '机构管理', path: '/platform/tenants' },
      { title: '授权', path: '/platform/agent-grants' },
      { title: '模型', path: '/platform/models' }
    ]
    const projected = institutionNavigation(original, true)
    expect(projected.map((item) => item.title)).toEqual([
      '工作台',
      '机构工作台',
      '全局中心',
      '跨机构运营'
    ])
    expect(navigationLeaves(projected).map((item) => item.path)).toEqual([
      '/platform/home',
      '/platform/tenants',
      '/platform/models',
      '/platform/agent-grants'
    ])
    expect(original[1]!.title).toBe('机构管理')
  })
  it('没有机构入口时不凭空创建机构导航', () => {
    const projected = institutionNavigation([{ title: '模型', path: '/platform/models' }], true)
    expect(projected.some((item) => item.title === '机构工作台')).toBe(false)
  })
  it('只有审计页面的机构账号不获得工作台入口', () => {
    const projected = institutionNavigation([{ title: '治理', path: '/admin/governance' }], false, [
      'foundation.audit.read'
    ])
    expect(projected.some((item) => item.path === '/admin/institutions')).toBe(false)
    expect(navigationLeaves(projected).map((item) => item.path)).toEqual(['/admin/governance'])
  })
  it('只有目标部署权限也能进入工作台，但不获得机构设置权限', () => {
    const permissions = ['platform.agent_deployments.read']
    expect(routePermission('/platform/tenants/42', permissions)).toBe(permissions[0])
    expect(routePermission('/platform/institution-credentials', permissions)).toBe(
      'platform.institution_credentials.read'
    )
    expect(routePermission('/admin/institutions/42', ['tenant.agent_deployments.read'])).toBe(
      'tenant.agent_deployments.read'
    )
  })
})
