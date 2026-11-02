import { describe, it, expect, vi } from 'vitest'
import { createKnowledgeRebuildApi, type KnowledgeRebuild } from './knowledgeRebuild'
const job: KnowledgeRebuild = {
  job_id: '9007199254740993',
  kb_id: '7',
  status: 'READY',
  revision: 9,
  base_revision: 18,
  target_model_id: '81',
  target_model_revision: 3,
  target_dimensions: 768,
  document_count: 2,
  ready_documents: 2,
  unknown_batches: 0,
  error_code: null,
  published_revision: null,
  write_locked: true
}
describe('Knowledge全库重建请求', () => {
  it('创建发送KB控制修订，发布同时绑定KB和任务两个修订', async () => {
    const post = vi.fn().mockResolvedValue(job)
    const get = vi.fn().mockResolvedValue(job)
    const api = createKnowledgeRebuildApi({ get, post })
    await api.create('7', '81', 3, 18, 'idempotency-key')
    expect(post).toHaveBeenNthCalledWith(1, '/knowledge/bases/rebuilds/create', {
      kb_id: '7',
      target_model_id: '81',
      target_model_revision: 3,
      expected_base_revision: 18,
      idempotency_key: 'idempotency-key'
    })
    const policy = {
      model_invocable: true,
      user_invocable: false,
      allowed_operators: ['VECTOR'],
      freshness_policy: 'PINNED',
      citation_required: true
    }
    await api.publish(job, policy)
    expect(post).toHaveBeenNthCalledWith(2, '/knowledge/bases/rebuilds/publish', {
      kb_id: '7',
      job_id: '9007199254740993',
      expected_revision: 9,
      expected_base_revision: 18,
      policy
    })
  })
  it('费用同意仅通过明确retry参数发送，detail只读且可取消', async () => {
    const post = vi.fn().mockResolvedValue(job)
    const get = vi.fn().mockResolvedValue(job)
    const api = createKnowledgeRebuildApi({ get, post })
    const controller = new AbortController()
    await api.detail('kb id', { signal: controller.signal })
    expect(get).toHaveBeenCalledWith('/knowledge/bases/rebuilds/detail?kbId=kb%20id', {
      signal: controller.signal
    })
    await api.retry({ ...job, status: 'UNKNOWN', unknown_batches: 1 }, true)
    expect(post).toHaveBeenCalledWith('/knowledge/bases/rebuilds/retry', {
      kb_id: '7',
      job_id: '9007199254740993',
      expected_revision: 9,
      confirm_unknown_cost: true
    })
    expect(get).toHaveBeenCalledTimes(1)
  })
})
