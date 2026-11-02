import { afterEach, describe, expect, it, vi } from 'vitest'
import { createHttpClient } from '@skillforge/shared/utils/http'
import { createCapabilityCatalogApi, type CapabilityCatalogHttp } from './capabilities'

function transport(): {
  get: ReturnType<typeof vi.fn>
  post: ReturnType<typeof vi.fn>
  put: ReturnType<typeof vi.fn>
} {
  return {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn()
  }
}

const definitionWire = {
  id: '101',
  tenant_id: null,
  capability_type: 'TOOL',
  capability_key: 'calendar.lookup',
  name: 'Calendar lookup',
  description: 'Lookup requests',
  trust_level: 'PLATFORM',
  status: 'DRAFT',
  revision: 4,
  created_at: '2026-08-30T01:02:03Z',
  updated_at: '2026-08-30T02:03:04Z'
}

const versionWire = {
  id: '202',
  definition_id: '101',
  tenant_id: null,
  capability_type: 'TOOL',
  version_key: '1.0.0',
  name: 'Calendar lookup v1',
  description: 'Published descriptor',
  trust_level: 'PLATFORM',
  descriptor: { input_schema: { type: 'object' } },
  content_hash: 'sha256:abc',
  status: 'DRAFT',
  created_at: '2026-08-30T01:02:03Z',
  published_at: null
}

describe('platform Capability Catalog typed client', () => {
  it('maps definition wire fields and encodes type/status filters', async () => {
    const http = transport()
    http.get.mockResolvedValue([definitionWire])
    const client = createCapabilityCatalogApi(http as unknown as CapabilityCatalogHttp)

    const definitions = await client.listDefinitions({ capabilityType: 'TOOL', status: 'DRAFT' })

    expect(http.get).toHaveBeenCalledWith(
      '/admin/capabilities/definitions?capability_type=TOOL&status=DRAFT',
      undefined
    )
    expect(definitions[0]).toMatchObject({
      id: '101',
      tenantId: null,
      capabilityType: 'TOOL',
      capabilityKey: 'calendar.lookup',
      trustLevel: 'PLATFORM',
      revision: 4,
      updatedAt: '2026-08-30T02:03:04Z'
    })
  })

  it('covers definition/version reads and draft creation with snake_case request bodies', async () => {
    const http = transport()
    http.get
      .mockResolvedValueOnce(definitionWire)
      .mockResolvedValueOnce([versionWire])
      .mockResolvedValueOnce(versionWire)
    http.post.mockResolvedValueOnce(versionWire)
    const client = createCapabilityCatalogApi(http as unknown as CapabilityCatalogHttp)

    const opts = { silent: true }
    await client.getDefinition('101', opts)
    const versions = await client.listVersions('101', 'DRAFT', opts)
    await client.getVersion('202', opts)
    await client.createDraftVersion('101', {
      versionKey: '1.0.0',
      name: 'Calendar lookup v1',
      description: 'Published descriptor',
      contentHash: 'sha256:abc',
      descriptor: { input_schema: { type: 'object' } }
    })

    expect(http.get).toHaveBeenNthCalledWith(
      1,
      '/admin/capabilities/definitions/detail?definitionId=101',
      opts
    )
    expect(http.get).toHaveBeenNthCalledWith(
      2,
      '/admin/capabilities/definitions/versions?definitionId=101&status=DRAFT',
      opts
    )
    expect(http.get).toHaveBeenNthCalledWith(
      3,
      '/admin/capabilities/versions/detail?versionId=202',
      opts
    )
    expect(versions[0].definitionId).toBe('101')
    expect(versions[0].tenantId).toBeNull()
    expect(http.post).toHaveBeenCalledWith('/admin/capabilities/definitions/versions', {
      definition_id: '101',
      payload: {
        version_key: '1.0.0',
        name: 'Calendar lookup v1',
        description: 'Published descriptor',
        content_hash: 'sha256:abc',
        descriptor: { input_schema: { type: 'object' } }
      }
    })
  })

  it('maps validate and sends Definition revision as If-Match for update and publish', async () => {
    const http = transport()
    http.post
      .mockResolvedValueOnce({
        definition_id: '101',
        version_id: '202',
        definition_revision: 4,
        valid: false,
        errors: [{ code: 'descriptor.invalid', message: 'descriptor invalid' }]
      })
      .mockResolvedValueOnce({ ...versionWire, status: 'PUBLISHED' })
    http.put.mockResolvedValueOnce(versionWire)
    const client = createCapabilityCatalogApi(http as unknown as CapabilityCatalogHttp)

    const validation = await client.validateVersion('101', '202')
    await client.updateDraftVersion('101', '202', 4, {
      name: 'Updated',
      description: 'Updated description',
      contentHash: 'sha256:def',
      descriptor: { output_schema: { type: 'string' } }
    })
    await client.publishVersion('101', '202', 5)

    expect(validation).toEqual({
      definitionId: '101',
      versionId: '202',
      definitionRevision: 4,
      valid: false,
      errors: [{ code: 'descriptor.invalid', message: 'descriptor invalid' }]
    })
    expect(http.put).toHaveBeenCalledWith(
      '/admin/capabilities/definitions/versions/update',
      {
        definition_id: '101',
        version_id: '202',
        payload: {
          name: 'Updated',
          description: 'Updated description',
          content_hash: 'sha256:def',
          descriptor: { output_schema: { type: 'string' } }
        }
      },
      { headers: { 'If-Match': '4' } }
    )
    expect(http.post).toHaveBeenNthCalledWith(
      2,
      '/admin/capabilities/definitions/versions/publish',
      { definition_id: '101', version_id: '202' },
      { headers: { 'If-Match': '5' } }
    )
  })

  it('fails closed when the server sends a Capability type outside the four-value contract', async () => {
    const http = transport()
    http.get.mockResolvedValue([{ ...definitionWire, capability_type: 'MEMORY' }])
    const client = createCapabilityCatalogApi(http as unknown as CapabilityCatalogHttp)

    await expect(client.listDefinitions()).rejects.toThrow('不支持的 Capability 类型')
  })
})

afterEach(() => {
  vi.unstubAllGlobals()
  localStorage.clear()
})
it('skill summary maps description and exact decimal IDs with null defaults', async () => {
  const fetchMock = vi.fn<typeof fetch>().mockResolvedValue(
    new Response(
      JSON.stringify({
        code: 'OK',
        message: '',
        data: [
          {
            id: '9223372036854775807',
            capability_key: 'summary',
            name: '技能摘要',
            description: '处理订单',
            status: 'DRAFT',
            default_version_id: '9223372036854775806',
            default_version_key: '2026.10'
          },
          {
            id: '9223372036854775805',
            capability_key: 'empty',
            name: '无默认发布',
            description: null,
            status: 'DRAFT',
            default_version_id: null,
            default_version_key: null
          },
          {
            id: '9223372036854775804',
            capability_key: 'missing-label',
            name: '标签未读取',
            description: null,
            status: 'DRAFT',
            default_version_id: '9223372036854775803',
            default_version_key: null
          }
        ],
        trace_id: 'test'
      }),
      { status: 200 }
    )
  )
  vi.stubGlobal('fetch', fetchMock)
  const http = createHttpClient({
    base: '/api/v1',
    accessTokenKey: 'skill.catalog.test',
    refreshTokenKey: 'skill.catalog.refresh',
    authHeader: (token) => ({ 'X-Platform-Token': 'Bearer ' + token }),
    refreshPath: '/platform/auth/refresh',
    isAuthPath: (path) => path.startsWith('/platform/auth/')
  })
  const client = createCapabilityCatalogApi(http)
  const catalog = await client.listSkillCatalog()
  expect(fetchMock).toHaveBeenCalledExactlyOnceWith(
    '/api/v1/admin/capabilities/skills/catalog',
    expect.objectContaining({ method: 'GET' })
  )
  expect(catalog).toEqual([
    {
      id: '9223372036854775807',
      capabilityKey: 'summary',
      name: '技能摘要',
      description: '处理订单',
      status: 'DRAFT',
      defaultVersionId: '9223372036854775806',
      defaultVersionKey: '2026.10'
    },
    {
      id: '9223372036854775805',
      capabilityKey: 'empty',
      name: '无默认发布',
      description: null,
      status: 'DRAFT',
      defaultVersionId: null,
      defaultVersionKey: null
    },
    {
      id: '9223372036854775804',
      capabilityKey: 'missing-label',
      name: '标签未读取',
      description: null,
      status: 'DRAFT',
      defaultVersionId: '9223372036854775803',
      defaultVersionKey: null
    }
  ])
  expect(catalog.every((item) => !('descriptor' in item))).toBe(true)
})
it('skill summary rejects JSON number identities at the existing catalog boundary', async () => {
  const http = transport()
  http.get.mockResolvedValue([
    {
      id: '101',
      capability_key: 'summary',
      name: '技能摘要',
      status: 'DRAFT',
      default_version_id: 9007199254740992,
      default_version_key: '1.0'
    }
  ])
  const client = createCapabilityCatalogApi(http as unknown as CapabilityCatalogHttp)
  await expect(client.listSkillCatalog()).rejects.toThrow('能力标识无效')
})

it('platform requirement options use the fixed platform type scope and exact published IDs', async () => {
  const http = transport()
  http.get.mockResolvedValue([
    {
      definition_id: '9223372036854775807',
      name: '当前技能',
      version_id: '9223372036854775806',
      version_key: 'current'
    }
  ])
  const client = createCapabilityCatalogApi(http as unknown as CapabilityCatalogHttp)
  expect(await client.listRequirementOptions('SKILL')).toEqual([
    {
      id: '9223372036854775807',
      name: '当前技能',
      versionId: '9223372036854775806',
      versionKey: 'current'
    }
  ])
  expect(http.get).toHaveBeenCalledExactlyOnceWith(
    '/admin/capabilities/requirements/options?capability_type=SKILL',
    undefined
  )
})

it('capability version summary maps five scalar fields and preserves exact identities', async () => {
  const wire = [
    {
      id: '9223372036854775806',
      version_key: 'history',
      name: '历史版本',
      description: '说明',
      status: 'ARCHIVED'
    }
  ]
  const http = transport()
  http.get.mockResolvedValue(wire)
  const catalog = createCapabilityCatalogApi(http as unknown as CapabilityCatalogHttp)
  expect(await catalog.listVersionSummaries('9223372036854775807')).toEqual([
    {
      id: '9223372036854775806',
      versionKey: 'history',
      name: '历史版本',
      description: '说明',
      status: 'ARCHIVED'
    }
  ])
  expect(http.get).toHaveBeenCalledExactlyOnceWith(
    '/admin/capabilities/versions/summaries?definition_id=9223372036854775807',
    undefined
  )
})
