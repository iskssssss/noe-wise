import { effectScope } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createHttpClient, HttpClientError } from '../utils/http'
import { useResource, useTask } from './tasks'

function client(status = 409) {
  const onError = vi.fn()
  const fetch = vi.fn().mockImplementation(
    async () =>
      new Response(
        JSON.stringify({
          code: 'CONFLICT',
          message: 'internal diagnostic',
          data: null,
          trace_id: 'trace-owned'
        }),
        { status }
      )
  )
  vi.stubGlobal('fetch', fetch)
  const http = createHttpClient({
    base: '/api',
    accessTokenKey: 'feedback.access',
    refreshTokenKey: 'feedback.refresh',
    authHeader: (token) => ({ Authorization: token }),
    refreshPath: '/auth/refresh',
    isAuthPath: () => false,
    onError
  })
  return { http, fetch, onError }
}
beforeEach(() => vi.useFakeTimers())
afterEach(() => {
  vi.clearAllTimers()
  vi.useRealTimers()
  vi.unstubAllGlobals()
  localStorage.clear()
})

describe('请求反馈由页面优先承接', () => {
  it('页内写入错误只保留内联原因，不叠加全局提示或重发请求', async () => {
    const { http, fetch, onError } = client()
    const scope = effectScope(),
      task = scope.run(() => useTask())!
    let failure: HttpClientError | undefined
    await task.run(async () => {
      try {
        await http.put('/draft', {})
      } catch (error) {
        failure = error as HttpClientError
        throw error
      }
    })
    expect(task.error.value).toContain('当前状态不允许此操作')
    expect(failure).toMatchObject({
      technicalDetails: 'internal diagnostic',
      traceId: 'trace-owned'
    })
    await vi.runAllTimersAsync()
    expect(onError).not.toHaveBeenCalled()
    expect(fetch).toHaveBeenCalledTimes(1)
    scope.stop()
  })
  it('专用冲突面板接管后不再重复填充通用错误区', async () => {
    const { http, onError } = client()
    const scope = effectScope(),
      task = scope.run(() =>
        useTask({
          handlesError: (reason) => reason instanceof HttpClientError && reason.status === 409
        })
      )!
    await task.run(() => http.put('/draft', {}))
    expect(task.error.value).toBe('')
    await vi.runAllTimersAsync()
    expect(onError).not.toHaveBeenCalled()
    scope.stop()
  })
  it('刷新读取失败保留旧数据并在原区域报告，迟到错误不弹全局提示', async () => {
    const { http, onError } = client(503)
    const scope = effectScope(),
      resource = scope.run(() => useResource(() => http.get('/list'), ['已有内容']))!
    await resource.reload()
    expect(resource.data.value).toEqual(['已有内容'])
    expect(resource.error.value).toContain('重新加载')
    const late = resource.reload()
    resource.invalidate()
    await late
    await vi.runAllTimersAsync()
    expect(onError).not.toHaveBeenCalled()
    scope.stop()
  })
  it('没有页面承接时仍提供一次全局兜底，手动接管与silent不漏接异常', async () => {
    const { http, onError } = client()
    await expect(http.post('/one', {})).rejects.toBeInstanceOf(HttpClientError)
    await vi.runAllTimersAsync()
    expect(onError).toHaveBeenCalledTimes(1)
    await http.post('/two', {}).catch((reason: HttpClientError) => reason.claimFeedback())
    await expect(http.post('/probe', {}, { silent: true })).rejects.toBeInstanceOf(HttpClientError)
    await vi.runAllTimersAsync()
    expect(onError).toHaveBeenCalledTimes(1)
  })
  it('换号或退出后不出现旧请求的全局提示', async () => {
    const { http, onError } = client()
    await expect(http.post('/old', {})).rejects.toBeInstanceOf(HttpClientError)
    http.clearTokens()
    await vi.runAllTimersAsync()
    expect(onError).not.toHaveBeenCalled()
  })
  it('写操作网络中断只引导核对结果，不把重试当成确定安全', async () => {
    const { http, fetch } = client()
    fetch.mockRejectedValue(new TypeError('Failed to fetch'))
    await expect(http.post('/publish', {})).rejects.toMatchObject({
      status: 0,
      userMessage: expect.stringContaining('核对操作状态')
    })
    expect(fetch).toHaveBeenCalledTimes(1)
  })
})
