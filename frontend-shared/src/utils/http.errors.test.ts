import { afterEach, expect, it, vi } from 'vitest'
import { createHttpClient } from './http'

afterEach(() => vi.unstubAllGlobals())

it.each([
  [410, 'job_result_expired', '任务结果已到期，请查看已保存的文件；导出可新建任务获取当前数据'],
  [403, 'FORBIDDEN', '当前账号没有执行此操作的权限']
])('下载错误 %s 区分结果到期与权限拒绝', async (status, code, userMessage) => {
  vi.stubGlobal(
    'fetch',
    vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ code, message: '内部诊断内容', trace_id: 'download-trace' }), {
        status
      })
    )
  )
  const client = createHttpClient({
    base: '/api',
    accessTokenKey: 'download.access',
    refreshTokenKey: 'download.refresh',
    refreshPath: '/refresh',
    authHeader: (token) => ({ Authorization: `Bearer ${token}` }),
    isAuthPath: () => false
  })
  await expect(
    client.get('/foundation/files/download?file_id=result', { responseType: 'blob', silent: true })
  ).rejects.toMatchObject({ status, code, userMessage, traceId: 'download-trace' })
})
