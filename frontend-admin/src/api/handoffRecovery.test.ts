import { afterEach, expect, it, vi } from 'vitest'
import { api } from './index'
afterEach(() => {
  vi.unstubAllGlobals()
  localStorage.clear()
})
it('工单创建和回复的 401 均不自动重发', async () => {
  const fetchMock = vi
    .fn()
    .mockResolvedValue(
      new Response(JSON.stringify({ code: 'DENIED', message: '拒绝' }), {
        status: 401,
        headers: { 'Content-Type': 'application/json' }
      })
    )
  vi.stubGlobal('fetch', fetchMock)
  localStorage.setItem('skillforge.access_token', 'old')
  localStorage.setItem('skillforge.refresh_token', 'refresh')
  await expect(api.handoff.create('session', 'reason', 'command')).rejects.toBeTruthy()
  await expect(api.handoff.reply('ticket', 7, 'reply', 'text')).rejects.toBeTruthy()
  expect(fetchMock).toHaveBeenCalledTimes(2)
  expect(JSON.parse(fetchMock.mock.calls[0]![1].body)).toEqual({
    session_id: 'session',
    reason: 'reason',
    command_id: 'command'
  })
  expect(JSON.parse(fetchMock.mock.calls[1]![1].body)).toEqual({
    ticket_id: 'ticket',
    expected_revision: 7,
    command_id: 'reply',
    content: 'text'
  })
})
