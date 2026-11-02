import { describe, expect, it } from 'vitest'
import { runtimeEndpoints } from './runtime'
import { buildCurl } from './examples'

describe('当前管理轨迹接口说明', () => {
  it('只展示当前轨迹资源，管理Bearer与外部渠道密钥分别生成', () => {
    expect(
      runtimeEndpoints.some((endpoint) => endpoint.path.startsWith('/api/v1/chat/sessions/trace'))
    ).toBe(false)
    const trajectories = runtimeEndpoints.filter((endpoint) =>
      endpoint.path.startsWith('/api/v1/chat/trajectories')
    )
    expect(trajectories.map((endpoint) => endpoint.path)).toEqual([
      '/api/v1/chat/trajectories',
      '/api/v1/chat/trajectories/detail',
      '/api/v1/chat/trajectories/nodes',
      '/api/v1/chat/trajectories/content'
    ])
    for (const endpoint of trajectories) {
      expect(endpoint.auth).toBe('management')
      expect(endpoint.scopes).toContain('foundation.sessions.read')
      const curl = buildCurl(endpoint, 'https://example.invalid')
      expect(curl).toContain('Authorization: Bearer YOUR_MANAGEMENT_ACCESS_TOKEN')
      expect(curl).not.toContain('X-Api-Key')
      expect(curl).not.toContain('X-Channel-Token')
    }
    const external = runtimeEndpoints.find((endpoint) => endpoint.id === 'end-users-list')!
    expect(buildCurl(external, 'https://example.invalid')).toContain(
      'X-Api-Key: YOUR_OPENAPI_API_KEY'
    )
  })
  it('深层示例携带同一业务根和快照，受控内容使用真实引用字段', () => {
    const nodes = runtimeEndpoints.find((endpoint) => endpoint.id === 'trajectory-nodes')!
    const content = runtimeEndpoints.find((endpoint) => endpoint.id === 'trajectory-content')!
    expect(nodes.queryExample).toMatchObject({
      session_id: 'YOUR_SESSION_ID',
      root_input_id: 'YOUR_ROOT_INPUT_ID',
      snapshot: 'OPAQUE_SNAPSHOT'
    })
    expect(
      content.parameters?.filter((field) => field.required).map((field) => field.name)
    ).toEqual(['session_id', 'root_input_id', 'content_ref'])
    expect(content.parameters?.some((field) => field.name === 'ref')).toBe(false)
  })
})
