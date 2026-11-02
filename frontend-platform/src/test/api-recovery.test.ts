import { afterEach, describe, expect, it, vi } from 'vitest'
import { createHttpClient, HttpClientError } from '@skillforge/shared/utils/http'
import { api } from '../api'

afterEach(() => vi.unstubAllGlobals())
describe('平台恢复响应契约', () => {
  it('健康503保留依赖状态而不泄露诊断凭据', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            status: 'degraded',
            deps: {
              postgres: { ok: false, error: 'postgres://user:secret@host' },
              redis: { ok: true }
            }
          }),
          { status: 503 }
        )
      )
    )
    const result = await api.health()
    expect(result.status).toBe('degraded')
    expect(result.deps.postgres.ok).toBe(false)
    expect(result.deps.redis.ok).toBe(true)
    expect(JSON.stringify(result)).not.toContain('secret')
  })
  it('损坏健康响应不能伪装为依赖已检查', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ status: 'degraded', deps: { postgres: {} } }), {
          status: 503
        })
      )
    )
    await expect(api.health()).rejects.toThrow()
  })
  it('字段错误只保留安全键和值，不把结构或原始字段混入表单', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            code: 'VALIDATION_FAILED',
            message: '参数校验失败',
            data: null,
            trace_id: 'test-trace',
            field_errors: {
              tenant_name: '请输入机构名称',
              'constructor.prototype': 'unsafe',
              admin_password: { rejectedValue: 'secret' }
            }
          }),
          { status: 400 }
        )
      )
    )
    const http = createHttpClient({
      base: '/api/v1',
      accessTokenKey: 'test',
      refreshTokenKey: 'test-refresh',
      authHeader: () => ({}),
      refreshPath: '/auth',
      isAuthPath: () => true
    })
    const error = await http.post('/tenants', {}).catch((error) => error)
    expect(error).toBeInstanceOf(HttpClientError)
    expect((error as HttpClientError).fieldErrors).toEqual({ tenant_name: '请输入机构名称' })
  })
})
