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
  it('重建原发布及封存单次发送，原请求核对只读', async () => {
    const post = vi.fn().mockResolvedValue({ status: 'UNKNOWN', snapshot: null })
    const get = vi.fn().mockResolvedValue({ status: 'UNKNOWN', snapshot: null })
    const api = createKnowledgeRebuildApi({ get, post })
    const command = {
      request_id: 'request-1',
      kb_id: '7',
      job_id: '9',
      expected_revision: 2,
      expected_base_revision: 3,
      policy: {
        model_invocable: true,
        user_invocable: true,
        allowed_operators: ['VECTOR', 'KEYWORD'],
        freshness_policy: 'PINNED',
        citation_required: true
      }
    }
    await api.publishWithReceipt(command)
    await api.publishResolution('7', '9', 'request-1')
    await api.publishFence(command)
    expect(post).toHaveBeenNthCalledWith(
      1,
      '/knowledge/bases/rebuilds/publish-with-receipt',
      command,
      { retryOnUnauthorized: false }
    )
    expect(get).toHaveBeenCalledWith(
      '/knowledge/bases/rebuilds/publish-resolution?kb_id=7&job_id=9&request_id=request-1',
      { silent: true }
    )
    expect(post).toHaveBeenNthCalledWith(2, '/knowledge/bases/rebuilds/publish-fence', command, {
      retryOnUnauthorized: false
    })
  })
  it('大整数标识使用字符串贯穿发送与回执，不接收已损精度的数字', async () => {
    const kb = '9007199254740993'
    const jobId = '9223372036854775807'
    const exact = { ...job, kb_id: kb, job_id: jobId, target_model_id: '9007199254740995' }
    const post = vi.fn().mockResolvedValue({
      status: 'SUCCEEDED',
      snapshot: { ...exact, status: 'PUBLISHED', revision: 10, published_revision: 5 }
    })
    const get = vi.fn().mockResolvedValue(exact)
    const api = createKnowledgeRebuildApi({ get, post })
    expect((await api.detail(kb)).job_id).toBe(jobId)
    const command = {
      request_id: 'request-1',
      kb_id: kb,
      job_id: jobId,
      expected_revision: 9,
      expected_base_revision: 18,
      policy: {
        model_invocable: true,
        user_invocable: true,
        allowed_operators: ['VECTOR'],
        freshness_policy: 'PINNED',
        citation_required: true
      }
    }
    expect((await api.publishWithReceipt(command)).snapshot?.job_id).toBe(jobId)
    expect(post.mock.calls[0]![1]).toMatchObject({ kb_id: kb, job_id: jobId })
    get.mockResolvedValueOnce(JSON.parse('{"kb_id":9007199254740993,"job_id":9223372036854775807}'))
    await expect(api.detail(kb)).rejects.toThrow('重建任务标识不是精确字符串')
    post.mockResolvedValueOnce({
      status: 'SUCCEEDED',
      snapshot: JSON.parse('{"kb_id":9007199254740993,"job_id":9223372036854775807}')
    })
    await expect(api.publishWithReceipt(command)).rejects.toThrow('重建任务标识不是精确字符串')
  })
})
