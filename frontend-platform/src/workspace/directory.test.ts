import { beforeEach, describe, expect, it, vi } from 'vitest'
import { loadInstitutionDirectory } from './directory'

const mocks = vi.hoisted(() => ({ tenants: vi.fn(), relations: vi.fn(), options: vi.fn() }))
vi.mock('../api', () => ({
  api: { admin: { listTenants: mocks.tenants }, institutionRelations: { list: mocks.relations } }
}))
vi.mock('../api/entityOptions', () => ({ createOperationOptions: () => ({ load: mocks.options }) }))
const row = (id: string, parent: string | null = null) => ({
  tenant_id: id,
  parent_tenant_id: parent,
  name: `机构${id}`,
  status: 'active',
  revision: 1
})
beforeEach(() => vi.resetAllMocks())

describe('机构工作台目录', () => {
  it('完整翻页且同树修订后才发布关系，不调用逐机构详情', async () => {
    mocks.relations
      .mockResolvedValueOnce({ items: [row('1')], total: 2, tree_revision: 8 })
      .mockResolvedValueOnce({ items: [row('2', '1')], total: 2, tree_revision: 8 })
    const result = await loadInstitutionDirectory(
      (action) => action === 'platform.institution_relations.read',
      new AbortController().signal,
      vi.fn()
    )
    expect(result.complete).toBe(true)
    expect(result.nodes.find((node) => node.id === '2')?.parentId).toBe('1')
    expect(mocks.tenants).not.toHaveBeenCalled()
    expect(mocks.relations).toHaveBeenCalledTimes(2)
  })
  it('分页期间树修订变化不拼接发布', async () => {
    mocks.relations
      .mockResolvedValueOnce({ items: [row('1')], total: 2, tree_revision: 8 })
      .mockResolvedValueOnce({ items: [row('2')], total: 2, tree_revision: 9 })
    await expect(
      loadInstitutionDirectory(
        (action) => action === 'platform.institution_relations.read',
        new AbortController().signal,
        vi.fn()
      )
    ).rejects.toThrow('分页期间已变化')
  })
  it('无机构总目录权限时使用原动作目标目录，不提升读取权', async () => {
    mocks.options.mockResolvedValue([{ id: '9223372036854775807', label: '受限机构' }])
    const result = await loadInstitutionDirectory(
      (action) => action === 'platform.agent_deployments.read',
      new AbortController().signal,
      vi.fn()
    )
    expect(result.nodes[0]!.id).toBe('9223372036854775807')
    expect(result.hierarchy).toBe(false)
    expect(mocks.tenants).not.toHaveBeenCalled()
    expect(mocks.relations).not.toHaveBeenCalled()
  })
  it('未获目录中的父机构不补造可见节点', async () => {
    mocks.relations.mockResolvedValue({ items: [row('2', 'hidden')], total: 1, tree_revision: 8 })
    const result = await loadInstitutionDirectory(
      (action) => action === 'platform.institution_relations.read',
      new AbortController().signal,
      vi.fn()
    )
    expect(result.nodes).toHaveLength(1)
    expect(result.nodes[0]!.parentId).toBeUndefined()
  })
  it('总目录较窄时仍合并独立动作目标，不截断其他授权范围', async () => {
    mocks.tenants.mockResolvedValue({
      items: [{ id: 'A', name: '机构A', status: 'active', slug: 'a' }],
      total: 1
    })
    mocks.options.mockResolvedValue([{ id: 'B', label: '机构B' }])
    const result = await loadInstitutionDirectory(
      (action) => ['foundation.tenants.read', 'platform.agent_deployments.read'].includes(action),
      new AbortController().signal,
      vi.fn()
    )
    expect(result.nodes.map((node) => node.id)).toEqual(['A', 'B'])
  })
})
