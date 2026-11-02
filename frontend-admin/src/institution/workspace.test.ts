import { describe, expect, it, vi } from 'vitest'
import {
  institutionSections,
  loadInstitutionDirectory,
  type InstitutionDirectoryApi
} from './workspace'

function directory(): InstitutionDirectoryApi {
  return {
    subInstitutions: { list: vi.fn().mockResolvedValue({ items: [], total: 0, tree_revision: 1 }) },
    targetOptions: vi.fn().mockResolvedValue({ items: [], total: 0 })
  }
}
const row = (id: string, revision = 1) => ({
  tenant_id: id,
  parent_tenant_id: '10',
  name: `机构 ${id}`,
  status: 'active',
  revision,
  tree_revision: revision
})

describe('机构工作台的目录和权限边界', () => {
  it('本机构账号、接入、资源权限不会因选择下级而继承', () => {
    const permissions = [
      'foundation.access.read',
      'foundation.preferences.manage',
      'foundation.service_principals.read',
      'foundation.agents.read',
      'tenant.agent_definitions.read',
      'foundation.usage.read',
      'tenant.sub_institutions.read'
    ]
    expect(institutionSections(permissions, '10', '10').map((section) => section.key)).toEqual([
      'overview',
      'settings',
      'access',
      'agents',
      'children',
      'usage'
    ])
    expect(institutionSections(permissions, '10', '11')).toEqual([
      { key: 'overview', label: '概览' }
    ])
  })
  it('下级只呈现独立获得的授权、部署和用量管理入口', () => {
    const permissions = [
      'tenant.agent_deployments.read',
      'tenant.agent_grants.read',
      'tenant.sub_institutions.usage_details.read'
    ]
    expect(institutionSections(permissions, '10', '11').map((section) => section.key)).toEqual([
      'overview',
      'agents',
      'usage'
    ])
  })
  it('没有任何目录或目标权限时保留本机构，不探测下级', async () => {
    const api = directory()
    expect(await loadInstitutionDirectory(api, '9223372036854775807', [])).toEqual([
      {
        id: '9223372036854775807',
        label: '本机构',
        description: '当前登录机构'
      }
    ])
    expect(api.subInstitutions.list).not.toHaveBeenCalled()
    expect(api.targetOptions).not.toHaveBeenCalled()
  })
  it('完整读取超过200条关系，不把第一页当作全部机构', async () => {
    const api = directory()
    const rows = Array.from({ length: 1000 }, (_, index) => row(String(100 + index)))
    vi.mocked(api.subInstitutions.list).mockImplementation(async (page) => ({
      items: rows.slice((page - 1) * 20, page * 20),
      total: 1000,
      tree_revision: 3
    }))
    const nodes = await loadInstitutionDirectory(api, '10', ['tenant.sub_institutions.read'])
    expect(nodes).toHaveLength(1001)
    expect(nodes.at(-1)).toMatchObject({ id: '1099', parentId: '10' })
  })
  it('跨页关系版本改变时拒绝拼接两棵树', async () => {
    const api = directory()
    vi.mocked(api.subInstitutions.list)
      .mockResolvedValueOnce({ items: [row('11')], total: 2, tree_revision: 1 })
      .mockResolvedValueOnce({ items: [row('12', 2)], total: 2, tree_revision: 2 })
    await expect(
      loadInstitutionDirectory(api, '10', ['tenant.sub_institutions.read'])
    ).rejects.toThrow('机构关系在读取期间已变化')
  })
  it('仅有部署管理权可以使用原动作目标目录，不新增关系读取权', async () => {
    const api = directory()
    vi.mocked(api.targetOptions).mockResolvedValue({
      items: [{ id: '11', label: '可管理实例的机构' }],
      total: 1
    })
    const nodes = await loadInstitutionDirectory(api, '10', ['tenant.agent_deployments.read'])
    expect(nodes[1]).toMatchObject({ id: '11', label: '可管理实例的机构' })
    expect(nodes[1]?.parentId).toBeUndefined()
    expect(api.subInstitutions.list).not.toHaveBeenCalled()
    expect(api.targetOptions).toHaveBeenCalledWith(
      'tenant.agent_deployments.read',
      { page: 1, page_size: 200 },
      expect.anything()
    )
  })
  it('读取期间身份失效后不继续读取后续页', async () => {
    const api = directory()
    let valid = true
    vi.mocked(api.subInstitutions.list).mockImplementation(async () => {
      valid = false
      return { items: [row('11')], total: 2, tree_revision: 1 }
    })
    await expect(
      loadInstitutionDirectory(api, '10', ['tenant.sub_institutions.read'], undefined, () => {
        if (!valid) throw Error('identity_changed')
      })
    ).rejects.toThrow('identity_changed')
    expect(api.subInstitutions.list).toHaveBeenCalledTimes(1)
  })
})
