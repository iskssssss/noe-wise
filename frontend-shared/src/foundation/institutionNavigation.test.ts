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
      '跨机构运营',
      '模型'
    ])
    expect(navigationLeaves(projected).map((item) => item.path)).toEqual([
      '/platform/home',
      '/platform/tenants',
      '/platform/agent-grants',
      '/platform/models'
    ])
    expect(original[1]!.title).toBe('机构管理')
  })
  it('没有机构入口时不凭空创建机构导航', () => {
    const projected = institutionNavigation([{ title: '模型', path: '/platform/models' }], true)
    expect(projected.some((item) => item.title === '机构工作台')).toBe(false)
  })
  it('保留服务端业务域分组，只把机构维护入口单独收拢', () => {
    const projected = institutionNavigation(
      [
        { title: '工作台', path: '/admin/home' },
        {
          title: '智能体与协作',
          children: [
            { title: '智能体', path: '/admin/agents' },
            { title: '下级机构', path: '/admin/sub-institutions' }
          ]
        },
        {
          title: '能力与数据',
          children: [{ title: '知识库', path: '/admin/knowledge' }]
        }
      ],
      false,
      ['foundation.agents.read']
    )
    expect(projected.map((item) => item.title)).toEqual([
      '工作台',
      '机构工作台',
      '智能体与协作',
      '能力与数据',
      '机构维护视图'
    ])
    expect(projected[2]!.children!.map((item) => item.title)).toEqual(['智能体'])
    expect(projected[4]!.children!.map((item) => item.title)).toEqual(['下级机构'])
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
