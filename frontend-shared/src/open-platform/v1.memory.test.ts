import { afterEach, describe, expect, it, vi } from 'vitest'
import { createOpenPlatformClient } from './v1'
import type { CreateGroupMemoryInput, GroupMemoryQuery } from './v1'
import { createHttpClient } from '../utils/http'

afterEach(() => vi.unstubAllGlobals())

function transport() {
  return { get: vi.fn(), post: vi.fn(), put: vi.fn() }
}

describe('group memory public client', () => {
  it('reads explicit extraction policy and sends the original revision when changing it', async () => {
    const http = transport()
    const client = createOpenPlatformClient(http)
    const query = {
      group_binding_id: '9223372036854775806',
      operator_end_user_id: '9223372036854775807'
    }
    const disabled = {
      group_binding_id: query.group_binding_id,
      enabled: false,
      extractor_agent_id: null,
      revision: 0
    }
    http.get.mockResolvedValueOnce(disabled)
    expect(await client.groups.memories.extractionPolicy.get(query)).toBe(disabled)
    const url = new URL(http.get.mock.calls[0]![0], 'https://example.test')
    expect(url.pathname).toBe('/open-platform/groups/memories/extraction-policy')
    expect(Object.fromEntries(url.searchParams)).toEqual(query)
    const request = {
      ...query,
      enabled: true,
      extractor_agent_id: '9223372036854775805',
      expected_revision: 0
    }
    http.put.mockResolvedValueOnce({ ...disabled, ...request, revision: 1 })
    await client.groups.memories.extractionPolicy.update(request)
    expect(http.put).toHaveBeenCalledExactlyOnceWith(
      '/open-platform/groups/memories/extraction-policy',
      request,
      undefined
    )
  })

  it.each(['disconnect', 'invalid-success', 'server-error'] as const)(
    'keeps the original serialized CAS instruction and key through %s and caller recovery',
    async (failure) => {
      const fetcher = vi.fn()
      if (failure === 'disconnect') fetcher.mockRejectedValueOnce(new Error('connection lost'))
      else if (failure === 'invalid-success')
        fetcher.mockResolvedValueOnce(new Response('{', { status: 200 }))
      else fetcher.mockResolvedValueOnce(new Response('{}', { status: 503 }))
      vi.stubGlobal('fetch', fetcher)
      const client = createOpenPlatformClient(
        createHttpClient({
          base: '/api/v1',
          accessTokenKey: 'memory-test.access',
          refreshTokenKey: 'memory-test.refresh',
          refreshPath: '/refresh',
          authHeader: (token) => ({ Authorization: `Bearer ${token}` }),
          isAuthPath: () => false
        })
      )
      const body = {
        group_binding_id: 'group/1',
        operator_end_user_id: '9223372036854775807',
        agent_id: 'agent/1',
        fact_id: 'fact/original',
        expected_revision: 7
      }
      const options = { anonymous: true, silent: true }
      await expect(client.groups.memories.forget(body, 'original/key', options)).rejects.toThrow()
      expect(fetcher).toHaveBeenCalledTimes(1)
      const recovered = { fact_id: 'fact/original', revision: 8, status: 'FORGOTTEN' }
      fetcher.mockResolvedValueOnce(
        new Response(
          JSON.stringify({ code: 'OK', message: '', trace_id: 'recover', data: recovered })
        )
      )
      expect(await client.groups.memories.forget(body, 'original/key', options)).toEqual(recovered)
      expect(fetcher).toHaveBeenCalledTimes(2)
      const first = fetcher.mock.calls[0]![1]
      const second = fetcher.mock.calls[1]![1]
      expect(first.body).toBe(JSON.stringify(body))
      expect(second.body).toBe(first.body)
      expect(new Headers(first.headers).get('Idempotency-Key')).toBe('original/key')
      expect(new Headers(second.headers).get('Idempotency-Key')).toBe('original/key')
    }
  )

  it('preserves separate reader, Agent and described member identities in encoded filters', async () => {
    const http = transport()
    const client = createOpenPlatformClient(http)
    const query: GroupMemoryQuery = {
      group_binding_id: 'group/one & two',
      reader_end_user_id: '9223372036854775806',
      agent_id: 'agent/1',
      subject_end_user_id: '9223372036854775807',
      scope: 'GROUP_MEMBER',
      status: 'FORGOTTEN',
      limit: 100
    }
    const facts = [{ fact_id: 'fact/1', status: 'FORGOTTEN', revision: 3 }]
    http.get.mockResolvedValueOnce(facts)
    const options = { headers: { 'X-Request-ID': 'read/1' } }
    expect(await client.groups.memories.list(query, options)).toBe(facts)
    const [path, sentOptions] = http.get.mock.calls[0]!
    const url = new URL(path, 'https://example.test')
    expect(url.pathname).toBe('/open-platform/groups/memories')
    expect(Object.fromEntries(url.searchParams)).toEqual({ ...query, limit: '100' })
    expect(sentOptions).toBe(options)
  })

  it('uses real message IDs for candidates and retains the original key and body after an unknown write', async () => {
    const http = transport()
    const client = createOpenPlatformClient(http)
    const request: CreateGroupMemoryInput = {
      group_binding_id: 'group/1',
      operator_end_user_id: '9223372036854775806',
      agent_id: 'agent/1',
      scope: 'GROUP_MEMBER',
      subject_end_user_id: '9223372036854775807',
      source_message_ids: ['message:original/1'],
      type: 'preference',
      subject: '客户偏好',
      predicate: 'prefers',
      object_json: '{"text":"下午联系"}',
      review_required: true
    }
    const original = JSON.stringify(request)
    const unknown = new Error('connection ended after send')
    http.post.mockRejectedValueOnce(unknown)
    await expect(client.groups.memories.create(request, 'memory/original')).rejects.toBe(unknown)
    expect(http.post).toHaveBeenCalledTimes(1)
    expect(http.post.mock.calls[0]![1]).toBe(request)
    expect(JSON.stringify(request)).toBe(original)
    expect(new Headers(http.post.mock.calls[0]![2].headers).get('Idempotency-Key')).toBe(
      'memory/original'
    )
  })

  it('keeps review, correction, forgetting and restore as explicit CAS commands', async () => {
    const http = transport()
    const client = createOpenPlatformClient(http)
    const revision = {
      group_binding_id: 'group/1',
      operator_end_user_id: '9223372036854775807',
      agent_id: 'agent/1',
      fact_id: 'fact/stable',
      expected_revision: 7
    }
    const options = { headers: { 'X-Request-ID': 'governance/1', 'Idempotency-Key': 'wrong' } }
    const commands = [
      ['review', { ...revision, approved: true }],
      ['correct', { ...revision, object_json: '{"text":"上午联系"}' }],
      ['forget', revision],
      ['restore', revision]
    ] as const
    for (const [action, body] of commands) {
      const result = { fact_id: 'fact/stable', revision: 8, status: 'ACTIVE' }
      http.post.mockResolvedValueOnce(result)
      if (action === 'review')
        expect(await client.groups.memories.review(body, `${action}/key`, options)).toBe(result)
      else if (action === 'correct')
        expect(await client.groups.memories.correct(body, `${action}/key`, options)).toBe(result)
      else expect(await client.groups.memories[action](body, `${action}/key`, options)).toBe(result)
      const [path, sent, sentOptions] = http.post.mock.calls.at(-1)!
      expect(path).toBe(`/open-platform/groups/memories/${action}`)
      expect(sent).toBe(body)
      expect(sent.expected_revision).toBe(7)
      expect(new Headers(sentOptions.headers).get('Idempotency-Key')).toBe(`${action}/key`)
      expect(new Headers(sentOptions.headers).get('X-Request-ID')).toBe('governance/1')
    }
    expect(options.headers['Idempotency-Key']).toBe('wrong')
  })
})
