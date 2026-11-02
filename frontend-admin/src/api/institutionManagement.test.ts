import { describe, expect, it, vi } from 'vitest'
import { createInstitutionManagementApi } from './institutionManagement'
describe('机构管理公开客户端', () => {
  it('机构动作候选在服务端名称查询、分页和精确id解析时保留真实动作', async () => {
    const http = { get: vi.fn().mockResolvedValue({ items: [], total: 0 }), post: vi.fn() }
    const api = createInstitutionManagementApi(http)
    await api.targetOptions(
      'tenant.agent_grants.create',
      { q: '第二分店', page: 2, page_size: 20, id: '9223372036854775806' },
      { silent: true }
    )
    const path = http.get.mock.calls[0]![0] as string
    expect(path.split('?')[0]).toBe('/tenant/operation-options/targets')
    expect(Object.fromEntries(new URLSearchParams(path.split('?')[1]))).toEqual({
      operation: 'tenant.agent_grants.create',
      q: '第二分店',
      page: '2',
      page_size: '20',
      id: '9223372036854775806'
    })
  })

  it('开通套餐候选独立使用真实查询、分页与已选套餐标识解析', async () => {
    const http = { get: vi.fn().mockResolvedValue({ items: [], total: 0 }), post: vi.fn() }
    const api = createInstitutionManagementApi(http)
    const signal = new AbortController().signal
    await api.subInstitutions.planOptions(
      { q: '基础', page: 2, page_size: 200 },
      { signal, silent: true }
    )
    const path = http.get.mock.calls[0]![0] as string
    expect(path.split('?')[0]).toBe('/tenant/sub-institutions/provision-options/plans')
    expect(Object.fromEntries(new URLSearchParams(path.split('?')[1]))).toEqual({
      q: '基础',
      page: '2',
      page_size: '200'
    })
    expect(http.get.mock.calls[0]![1]).toEqual({ signal, silent: true })
    await api.subInstitutions.planOptions({ id: 'plan / exact' }, { silent: true })
    expect(
      new URLSearchParams((http.get.mock.calls[1]![0] as string).split('?')[1]).get('id')
    ).toBe('plan / exact')
  })

  it('机构授权列表不误用平台target查询；开通和收窄保持十进制字符串及显式操作键', async () => {
    const http = {
      get: vi.fn().mockResolvedValue({ items: [], total: 0 }),
      post: vi.fn().mockResolvedValue({})
    }
    const api = createInstitutionManagementApi(http)
    await api.grants.list({ silent: true })
    expect(http.get).toHaveBeenCalledWith('/tenant/agent-grants', { silent: true })
    const input = {
      grant_id: '9223372036854775807',
      expected_revision: 3,
      actions: ['execute'] as const,
      expires_at: null,
      allow_regrant: false,
      remaining_depth: 0
    }
    await api.grants.narrow({ ...input, actions: [...input.actions] }, 'original-command')
    expect(http.post).toHaveBeenCalledWith('/tenant/agent-grants/narrow', input, {
      headers: { 'Idempotency-Key': 'original-command' },
      silent: true
    })
    const provision = {
      namespace: 'chain',
      external_institution_ref: 'store-1',
      name: '分店',
      default_plan_code: 'basic'
    }
    await api.subInstitutions.provision(provision, 'provision-1')
    expect(http.post).toHaveBeenCalledWith('/tenant/sub-institutions/provision', provision, {
      headers: { 'Idempotency-Key': 'provision-1' },
      silent: true
    })
  })
})
