import { describe, expect, it, vi } from 'vitest'
import { createTrajectoryApi } from './trajectories'

describe('平台轨迹资源请求', () => {
  it('每次深层读取都显式携带目标机构和轨迹身份，保留精确字符串', async () => {
    const get = vi.fn().mockResolvedValue({})
    const api = createTrajectoryApi(get)
    const resource = {
      tenant_id: '9007199254740993123',
      session_id: 'session/1',
      root_input_id: 'input+中文'
    }
    const opts = { signal: new AbortController().signal, silent: true }
    await api.detail(resource, opts)
    await api.nodes(
      { ...resource, cursor: 'cursor+/=', snapshot: 'snapshot', query: 'tool🙂' },
      opts
    )
    await api.content({ ...resource, content_ref: 'ref+/=' }, opts)
    for (const [path, passed] of get.mock.calls) {
      const url = new URL(path, 'https://local.test')
      expect(url.pathname).toMatch(/^\/platform\/trajectories\/(detail|nodes|content)$/)
      expect(url.searchParams.get('tenant_id')).toBe(resource.tenant_id)
      expect(url.searchParams.get('session_id')).toBe(resource.session_id)
      expect(url.searchParams.get('root_input_id')).toBe(resource.root_input_id)
      expect(passed).toBe(opts)
    }
    const nodes = new URL(get.mock.calls[1]![0], 'https://local.test')
    expect(nodes.searchParams.get('cursor')).toBe('cursor+/=')
    expect(nodes.searchParams.get('query')).toBe('tool🙂')
  })
})
