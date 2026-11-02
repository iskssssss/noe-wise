import { describe, expect, it, vi } from 'vitest'
import { createOpenPlatformClient } from './v1'

describe('群治理目录客户端', () => {
  it.each(['members', 'agents'] as const)(
    '使用原精确身份读取%s分页并传递取消信号',
    async (kind) => {
      const http = {
        get: vi.fn().mockResolvedValue({ items: [], next_after: null, has_more: false }),
        post: vi.fn(),
        put: vi.fn()
      }
      const controller = new AbortController()
      const query = {
        group_binding_id: '9223372036854775807',
        after_id: '9007199254740993',
        limit: 100
      }
      await createOpenPlatformClient(http).groups[kind].list(query, { signal: controller.signal })
      const [path, options] = http.get.mock.calls[0]!
      const url = new URL(path, 'https://example.test')
      expect(url.pathname).toBe('/open-platform/groups/' + kind)
      expect(Object.fromEntries(url.searchParams)).toEqual({ ...query, limit: '100' })
      expect(options.signal).toBe(controller.signal)
      expect(http.post).not.toHaveBeenCalled()
    }
  )
})
