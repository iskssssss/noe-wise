import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createInstitutionTargetOptions } from './institutionTargetOptions'
const mocks = vi.hoisted(() => ({ targetOptions: vi.fn() }))
vi.mock('./index', () => ({ api: { institutionManagement: mocks } }))
beforeEach(() => vi.resetAllMocks())
describe('机构真实动作候选', () => {
  it('跨服务端分页搜索，已选机构独立解析且保持完整字符串标识', async () => {
    mocks.targetOptions.mockImplementation(async (_operation, query) => ({
      items: query.id
        ? [{ id: query.id, label: '已选机构' }]
        : query.page === 1
          ? [{ id: '7', label: '第一分店' }]
          : [{ id: '9223372036854775806', label: '第二分店' }],
      total: query.id ? 1 : 2,
      page: query.page,
      page_size: 200
    }))
    const current = vi.fn(),
      signal = new AbortController().signal
    const options = createInstitutionTargetOptions('tenant.agent_grants.create', current)
    expect(await options.load('分店', signal)).toEqual([
      { id: '7', label: '第一分店' },
      { id: '9223372036854775806', label: '第二分店' }
    ])
    expect(mocks.targetOptions).toHaveBeenNthCalledWith(
      2,
      'tenant.agent_grants.create',
      { q: '分店', page: 2, page_size: 200 },
      { signal, silent: true }
    )
    expect(await options.resolve('9223372036854775806')).toEqual({
      id: '9223372036854775806',
      label: '已选机构'
    })
    expect(mocks.targetOptions).toHaveBeenLastCalledWith(
      'tenant.agent_grants.create',
      { q: '', page: 1, page_size: 200, id: '9223372036854775806' },
      { signal: undefined, silent: true }
    )
    expect(current).toHaveBeenCalledTimes(6)
  })
  it('候选读取期间身份变化拒绝迟到选项，首请求拒绝时不读取目录', async () => {
    const current = vi
      .fn()
      .mockImplementationOnce(() => {})
      .mockImplementation(() => {
        throw Error('身份已变化')
      })
    mocks.targetOptions.mockResolvedValue({ items: [{ id: '8', label: '旧机构' }], total: 1 })
    const options = createInstitutionTargetOptions('tenant.agent_deployments.read', current)
    await expect(options.load('')).rejects.toThrow('身份已变化')
    await expect(options.load('')).rejects.toThrow('身份已变化')
    expect(mocks.targetOptions).toHaveBeenCalledTimes(1)
  })
})
