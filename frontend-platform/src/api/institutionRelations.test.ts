import { describe, expect, it, vi } from 'vitest'
import { createInstitutionRelationsApi } from './institutionRelations'

describe('平台机构关系公开客户端', () => {
  it('读取按真实页与取消信号，变更传递原ID/CAS/意图键而不使用机器认证', async () => {
    const get = vi.fn().mockResolvedValue({ items: [] }),
      post = vi.fn().mockResolvedValue({})
    const api = createInstitutionRelationsApi({ get, post })
    const signal = new AbortController().signal
    await api.list(2, { signal })
    expect(get).toHaveBeenCalledWith('/platform/institution-relations?page=2&page_size=200', {
      signal
    })
    const input = {
      child_tenant_id: '9223372036854775807',
      parent_tenant_id: '9223372036854775806',
      expected_revision: 4
    }
    await api.change('move', input, 'original-intent')
    expect(post).toHaveBeenCalledWith('/platform/institution-relations/move', input, {
      headers: { 'Idempotency-Key': 'original-intent' },
      silent: true
    })
  })
})
