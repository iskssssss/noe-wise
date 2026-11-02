import { afterEach, expect, it, vi } from 'vitest'
import { api } from './index'
afterEach(() => {
  vi.unstubAllGlobals()
  localStorage.clear()
})
it('知识组合发布保留来源与治理策略，401 不自动重发', async () => {
  const fetchMock = vi
    .fn()
    .mockResolvedValue(
      new Response(JSON.stringify({ code: 'DENIED', message: '拒绝', trace_id: 'trace' }), {
        status: 401,
        headers: { 'Content-Type': 'application/json' }
      })
    )
  vi.stubGlobal('fetch', fetchMock)
  localStorage.setItem('skillforge.access_token', 'old')
  localStorage.setItem('skillforge.refresh_token', 'refresh')
  const policy = {
    model_invocable: true,
    user_invocable: true,
    allowed_operators: ['KEYWORD'],
    freshness_policy: 'PINNED',
    citation_required: true
  }
  await expect(
    api.knowledge.prepareAndPublish('request', '123', ['v1', 'v2'], policy)
  ).rejects.toBeTruthy()
  expect(fetchMock).toHaveBeenCalledTimes(1)
  expect(fetchMock.mock.calls[0]![0]).toBe('/api/v1/knowledge/bases/revisions/prepare-and-publish')
  expect(JSON.parse(fetchMock.mock.calls[0]![1].body)).toEqual({
    request_id: 'request',
    kb_id: '123',
    payload: { source_versions: ['v1', 'v2'], policy }
  })
})
