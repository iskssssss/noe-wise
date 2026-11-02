import { describe, expect, it, vi } from 'vitest'
import { createTrajectoryApi } from './trajectories'

describe('机构轨迹资源请求', () => {
  it('精确保留业务身份和关键词，并把取消信号传入受控内容读取', async () => {
    const get = vi.fn().mockResolvedValue({})
    const api = createTrajectoryApi(get)
    const controller = new AbortController()
    const opts = { signal: controller.signal, silent: true }
    await api.nodes(
      {
        session_id: 'session/1',
        root_input_id: 'input+中文',
        query: '工具%_🙂',
        snapshot: 'snapshot+/=',
        node_id: 'node:1'
      },
      opts
    )
    const url = new URL(get.mock.calls[0]![0], 'https://local.test')
    expect(url.pathname).toBe('/chat/trajectories/nodes')
    expect(url.searchParams.get('session_id')).toBe('session/1')
    expect(url.searchParams.get('root_input_id')).toBe('input+中文')
    expect(url.searchParams.get('query')).toBe('工具%_🙂')
    expect(url.searchParams.get('snapshot')).toBe('snapshot+/=')
    expect(url.searchParams.has('tenant_id')).toBe(false)
    expect(get.mock.calls[0]![1]).toBe(opts)
    await api.content(
      { session_id: 'session/1', root_input_id: 'input+中文', content_ref: 'result:/+==' },
      opts
    )
    const content = new URL(get.mock.calls[1]![0], 'https://local.test')
    expect(content.searchParams.get('content_ref')).toBe('result:/+==')
    expect(get.mock.calls[1]![1].signal).toBe(controller.signal)
  })
})
