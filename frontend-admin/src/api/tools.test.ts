import { beforeEach, expect, it, vi } from 'vitest'
import { toolsApi } from './tools'

const calls = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))
vi.mock('./index', () => ({ get: calls.get, post: calls.post }))
beforeEach(() => vi.resetAllMocks())

it('submits one backend onboarding command with original tool names', async () => {
  calls.post.mockResolvedValue({ items: [] })
  await toolsApi.onboard('request-id', 'server', ['read_alpha', 'read.beta'], true)
  expect(calls.post).toHaveBeenCalledExactlyOnceWith('/tools/onboard', {
    request_id: 'request-id',
    connector_id: 'server',
    tool_names: ['read_alpha', 'read.beta'],
    allow_agent_calls: true
  })
})

it('queries the original request without another onboarding command', async () => {
  calls.get.mockResolvedValue(undefined)
  await toolsApi.onboarding('request-id')
  const url = new URL(calls.get.mock.calls[0]![0], 'https://example.test')
  expect(url.pathname).toBe('/tools/onboard/result')
  expect(url.searchParams.get('requestId')).toBe('request-id')
  expect(calls.post).not.toHaveBeenCalled()
})
