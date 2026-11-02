import { afterEach, expect, it, vi } from 'vitest'
import { api } from './index'

afterEach(() => {
  vi.unstubAllGlobals()
  localStorage.clear()
})
it('机构月账单只发送月份和分页GET，不保留销售发票写接口', async () => {
  const fetchMock = vi
    .fn()
    .mockResolvedValue(
      new Response(
        JSON.stringify({ code: 'OK', message: '成功', data: {}, trace_id: 'monthly-test' }),
        { status: 200, headers: { 'Content-Type': 'application/json' } }
      )
    )
  vi.stubGlobal('fetch', fetchMock)
  await api.billing.monthly({ month: '2026-10', page: 2, page_size: 50 })
  expect(fetchMock).toHaveBeenCalledTimes(1)
  const [path, init] = fetchMock.mock.calls[0]!
  const url = new URL(String(path), 'https://example.test')
  expect(url.pathname).toBe('/api/v1/billing/monthly')
  expect(Object.fromEntries(url.searchParams)).toEqual({
    month: '2026-10',
    page: '2',
    page_size: '50'
  })
  expect((init as RequestInit).method).toBe('GET')
  expect(api.billing).not.toHaveProperty('generateInvoice')
  expect(api.billing).not.toHaveProperty('invoiceDetail')
})
