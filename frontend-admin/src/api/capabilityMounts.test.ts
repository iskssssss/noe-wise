import { afterEach, describe, expect, it, vi } from 'vitest'
import { createHttpClient } from '@skillforge/shared/utils/http'
import {
  createCapabilityCatalogApi,
  createCapabilityMountApi,
  type CapabilityMountSetWire
} from './capabilityMounts'
import { api } from './index'

function client() {
  return createHttpClient({
    base: '/api/v1',
    accessTokenKey: 'mounts.access',
    refreshTokenKey: 'mounts.refresh',
    authHeader: (token) => ({ Authorization: `Bearer ${token}` }),
    refreshPath: '/auth/refresh',
    isAuthPath: (path) => path.startsWith('/auth/')
  })
}

function response(data: unknown, status = 200): Response {
  return new Response(
    JSON.stringify({ code: 'OK', message: '成功', data, trace_id: 'mount-test' }),
    { status }
  )
}

const skillSet: CapabilityMountSetWire = {
  generation: 4,
  mounts: [
    {
      mount_id: 'mount-skill-1',
      capability_type: 'SKILL',
      capability_definition_id: '100',
      capability_version_id: '101',
      skill_config: { activation_mode: 'ALWAYS', token_budget: 1200 },
      tool_config: null,
      workflow_config: null,
      knowledge_config: null,
      priority: 9,
      revision: 3,
      status: 'ACTIVE'
    }
  ]
}

afterEach(() => {
  vi.unstubAllGlobals()
  localStorage.clear()
})

it('部署原请求遇 401 不刷新认证或重发，回执只读使用原请求号', async () => {
  localStorage.setItem('mounts.access', 'token')
  localStorage.setItem('mounts.refresh', 'refresh-token')
  const fetchMock = vi
    .fn()
    .mockResolvedValueOnce(response(null, 401))
    .mockResolvedValueOnce(
      response({
        request_id: 'req-1',
        status: 'SUCCEEDED',
        deployment_id: '123',
        deployment_key: 'clock'
      })
    )
  vi.stubGlobal('fetch', fetchMock)
  const catalog = createCapabilityCatalogApi(client())
  await expect(
    catalog.createDeploymentWithReceipt('req-1', {
      toolVersionId: '202',
      providerType: 'PLATFORM',
      providerRef: 'get_current_time',
      environment: 'DEVELOPMENT'
    })
  ).rejects.toThrow()
  expect(fetchMock).toHaveBeenCalledTimes(1)
  expect(fetchMock.mock.calls[0][0]).toBe('/api/v1/capabilities/deployments/create-with-receipt')
  const request = JSON.parse(String((fetchMock.mock.calls[0][1] as RequestInit).body))
  expect(request).toMatchObject({
    request_id: 'req-1',
    payload: { tool_version_id: '202' }
  })
  expect(request.payload).not.toHaveProperty('deployment_key')
  expect(await catalog.deploymentCreateReceipt('req-1')).toMatchObject({
    request_id: 'req-1',
    deployment_id: '123'
  })
  expect(fetchMock.mock.calls[1][0]).toBe(
    '/api/v1/capabilities/deployments/create-with-receipt/result?request_id=req-1'
  )
})

describe('CapabilityMount API wire contract', () => {
  it('maps the snake_case mount-set list into typed camelCase fields', async () => {
    localStorage.setItem('mounts.access', 'token')
    const fetchMock = vi.fn().mockResolvedValue(response(skillSet))
    vi.stubGlobal('fetch', fetchMock)

    const result = await createCapabilityMountApi(client()).list('agent/7')

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/agents/capability-mounts?agentId=agent%2F7',
      expect.objectContaining({ method: 'GET' })
    )
    expect(result).toMatchObject({
      generation: 4,
      mounts: [
        {
          mountId: 'mount-skill-1',
          capabilityType: 'SKILL',
          capabilityDefinitionId: '100',
          capabilityVersionId: '101',
          skillConfig: { activationMode: 'ALWAYS', tokenBudget: 1200 },
          priority: 9,
          revision: 3,
          status: 'ACTIVE'
        }
      ]
    })
  })

  it('serializes a typed create request with the backend snake_case contract', async () => {
    localStorage.setItem('mounts.access', 'token')
    const fetchMock = vi.fn().mockResolvedValue(response(skillSet))
    vi.stubGlobal('fetch', fetchMock)

    await createCapabilityMountApi(client()).create('agent-7', {
      capabilityType: 'TOOL',
      capabilityDefinitionId: '200',
      priority: 2,
      toolConfig: { toolDeploymentId: 'calendar-prod' }
    })

    const [path, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(path).toBe('/api/v1/agents/capability-mounts')
    expect(JSON.parse(String(init.body))).toEqual({
      agent_id: 'agent-7',
      payload: {
        capability_type: 'TOOL',
        capability_definition_id: '200',
        priority: 2,
        tool_config: { tool_deployment_id: 'calendar-prod' }
      }
    })
  })

  it('uses If-Match for CAS update and maps typed knowledge config', async () => {
    localStorage.setItem('mounts.access', 'token')
    const fetchMock = vi.fn().mockResolvedValue(
      response({
        generation: 5,
        mounts: [
          {
            mount_id: 'mount-knowledge-1',
            capability_type: 'KNOWLEDGE_SOURCE',
            capability_definition_id: '300',
            capability_version_id: '301',
            skill_config: null,
            tool_config: null,
            workflow_config: null,
            knowledge_config: { mode: 'FOLLOW_ACTIVE', pinned_revision_id: null },
            priority: 0,
            revision: 4,
            status: 'ACTIVE'
          }
        ]
      })
    )
    vi.stubGlobal('fetch', fetchMock)

    const result = await createCapabilityMountApi(client()).update('agent-7', 'mount/1', 3, {
      capabilityType: 'KNOWLEDGE_SOURCE',
      capabilityDefinitionId: '300',
      capabilityVersionId: '301',
      knowledgeConfig: { mode: 'FOLLOW_ACTIVE', pinnedRevisionId: null }
    })

    const [path, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(path).toBe('/api/v1/agents/capability-mounts/update')
    expect((init.headers as Headers).get('If-Match')).toBe('3')
    expect(JSON.parse(String(init.body))).toMatchObject({
      agent_id: 'agent-7',
      mount_id: 'mount/1',
      payload: {
        capability_type: 'KNOWLEDGE_SOURCE',
        knowledge_config: { mode: 'FOLLOW_ACTIVE', pinned_revision_id: null }
      }
    })
    expect(result.mounts[0].knowledgeConfig).toEqual({
      mode: 'FOLLOW_ACTIVE',
      pinnedRevisionId: null
    })
  })

  it('uses If-Match for CAS delete', async () => {
    localStorage.setItem('mounts.access', 'token')
    const fetchMock = vi.fn().mockResolvedValue(response({ generation: 6, mounts: [] }))
    vi.stubGlobal('fetch', fetchMock)

    await createCapabilityMountApi(client()).delete('agent-7', 'mount-1', 4)

    const [path, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(path).toBe('/api/v1/agents/capability-mounts/delete')
    expect(init.method).toBe('DELETE')
    expect(JSON.parse(String(init.body))).toEqual({ agent_id: 'agent-7', mount_id: 'mount-1' })
    expect((init.headers as Headers).get('If-Match')).toBe('4')
  })

  it('fails closed for an unknown type or incomplete typed config', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      response({
        generation: 1,
        mounts: [
          {
            ...skillSet.mounts[0],
            capability_type: 'MEMORY'
          }
        ]
      })
    )
    vi.stubGlobal('fetch', fetchMock)
    await expect(createCapabilityMountApi(client()).list('agent-7')).rejects.toThrow(
      /capability_type is invalid/
    )

    const secondFetch = vi.fn().mockResolvedValue(
      response({
        generation: 1,
        mounts: [
          {
            ...skillSet.mounts[0],
            skill_config: { activation_mode: 'ALWAYS', token_budget: 0 }
          }
        ]
      })
    )
    vi.stubGlobal('fetch', secondFetch)
    await expect(createCapabilityMountApi(client()).list('agent-7')).rejects.toThrow(
      /skill_config.token_budget must be a positive integer/
    )
  })

  it('maps the Agent detail mount_set_generation and mount config fields from backend JSON', async () => {
    localStorage.setItem('access_token', 'token')
    const fetchMock = vi.fn().mockResolvedValue(
      response(
        {
          id: '7',
          agent_key: 'agent.support',
          name: 'Support Agent',
          template_id: 11,
          template_name: 'Support template',
          status: 'published',
          created_at: '2026-08-30T00:00:00Z',
          system_prompt: '你可以提供帮助。',
          role_description: null,
          planning_rules: null,
          config: null,
          published_at: '2026-08-30T00:00:00Z',
          delegation_config: { enabled: true, target_agent_ids: [8, '9'] },
          mount_set_generation: 12,
          mounts: skillSet.mounts
        },
        200
      )
    )
    vi.stubGlobal('fetch', fetchMock)

    const result = await api.agents.get('7')

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/agents/detail?agentId=7',
      expect.objectContaining({ method: 'GET' })
    )
    expect(result).toMatchObject({
      id: '7',
      agent_key: 'agent.support',
      delegation_config: { enabled: true, target_agent_ids: ['8', '9'] },
      mountSetGeneration: 12,
      mounts: [
        { mountId: 'mount-skill-1', skillConfig: { activationMode: 'ALWAYS', tokenBudget: 1200 } }
      ]
    })
    expect('bindings' in result).toBe(false)
  })

  it('maps published catalog and active ToolDeployment JSON identities without display-name inference', async () => {
    localStorage.setItem('mounts.access', 'token')
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        response(
          [
            {
              id: '200',
              tenant_id: '9',
              capability_type: 'TOOL',
              capability_key: 'tool.calendar',
              name: 'Calendar',
              description: '',
              trust_level: 'TRUSTED',
              status: 'PUBLISHED',
              revision: 2
            }
          ],
          200
        )
      )
      .mockResolvedValueOnce(
        response(
          [
            {
              id: '201',
              definition_id: '200',
              tenant_id: '9',
              capability_type: 'TOOL',
              version_key: '1.0.0',
              name: 'Calendar v1',
              description: '',
              trust_level: 'TRUSTED',
              descriptor: { provider: 'mcp' },
              content_hash: 'hash',
              status: 'PUBLISHED',
              created_at: null,
              published_at: '2026-08-30T00:00:00Z'
            }
          ],
          200
        )
      )
      .mockResolvedValueOnce(
        response(
          [
            {
              id: '301',
              tenant_id: '9',
              deployment_key: 'calendar-prod',
              tool_version_id: '201',
              definition_id: '200',
              tool_name: 'Calendar v1',
              tool_version_key: '1.0.0',
              provider_type: 'MCP',
              provider_ref: 'calendar',
              environment: 'PRODUCTION',
              status: 'ACTIVE',
              revision: 5
            }
          ],
          200
        )
      )
    vi.stubGlobal('fetch', fetchMock)

    const catalog = createCapabilityCatalogApi(client())
    const [definitions, versions, deployments] = await Promise.all([
      catalog.definitions('TOOL', 'PUBLISHED'),
      catalog.versions('200', 'PUBLISHED'),
      catalog.deployments('ACTIVE')
    ])

    expect(definitions[0]).toMatchObject({
      id: '200',
      tenantId: '9',
      capabilityType: 'TOOL',
      capabilityKey: 'tool.calendar'
    })
    expect(versions[0]).toMatchObject({
      id: '201',
      definitionId: '200',
      versionKey: '1.0.0',
      contentHash: 'hash'
    })
    expect(deployments[0]).toMatchObject({
      id: '301',
      deploymentKey: 'calendar-prod',
      toolVersionId: '201',
      toolDefinitionId: '200',
      toolName: 'Calendar v1',
      toolVersionKey: '1.0.0',
      revision: 5
    })
    expect(fetchMock.mock.calls.map((call) => call[0])).toEqual([
      '/api/v1/capabilities/definitions?capability_type=TOOL&status=PUBLISHED',
      '/api/v1/capabilities/definitions/versions?definitionId=200&status=PUBLISHED',
      '/api/v1/capabilities/deployments?status=ACTIVE'
    ])
  })

  it('部署投影按精确字符串读取能力定义，空值不猜测关联', async () => {
    const wire = {
      id: '301',
      tenant_id: '9',
      deployment_key: 'd',
      tool_version_id: '201',
      definition_id: null,
      tool_name: null,
      tool_version_key: null,
      provider_type: 'MCP',
      provider_ref: 'c',
      environment: 'DEVELOPMENT',
      status: 'ACTIVE',
      revision: 1
    }
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(response([wire]))
      .mockResolvedValueOnce(response([{ ...wire, definition_id: '9007199254740993' }]))
      .mockResolvedValueOnce(response([{ ...wire, definition_id: 'invalid' }]))
    vi.stubGlobal('fetch', fetchMock)
    const catalog = createCapabilityCatalogApi(client())
    expect((await catalog.deployments('ACTIVE'))[0]!.toolDefinitionId).toBeNull()
    expect((await catalog.deployments('ACTIVE'))[0]!.toolDefinitionId).toBe('9007199254740993')
    await expect(catalog.deployments('ACTIVE')).rejects.toThrow()
  })

  it('covers authoring, validation, publish CAS, lifecycle CAS and canonical platform providers', async () => {
    localStorage.setItem('mounts.access', 'token')
    const versionWire = {
      id: '202',
      definition_id: '200',
      tenant_id: '9',
      capability_type: 'TOOL',
      version_key: '1.0.0',
      name: 'Calendar v1',
      description: '',
      trust_level: 'TENANT',
      descriptor: { model_invocable: true },
      content_hash: 'sha256:v1',
      status: 'DRAFT'
    }
    const deploymentWire = {
      id: '301',
      tenant_id: '9',
      deployment_key: 'calendar-prod',
      tool_version_id: '202',
      definition_id: '200',
      tool_name: 'Calendar v1',
      tool_version_key: '1.0.0',
      provider_type: 'PLATFORM',
      provider_ref: 'get_current_time',
      environment: 'PRODUCTION',
      status: 'ACTIVE',
      revision: 1
    }
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        response(
          {
            id: '200',
            tenant_id: '9',
            capability_type: 'TOOL',
            capability_key: 'tool.calendar',
            name: 'Calendar',
            description: '',
            trust_level: 'TENANT',
            status: 'DRAFT',
            revision: 4
          },
          200
        )
      )
      .mockResolvedValueOnce(response(versionWire, 200))
      .mockResolvedValueOnce(
        response({ ...versionWire, name: 'Calendar updated', content_hash: 'sha256:v2' }, 200)
      )
      .mockResolvedValueOnce(
        response(
          {
            definition_id: '200',
            version_id: '202',
            definition_revision: 4,
            valid: true,
            errors: []
          },
          200
        )
      )
      .mockResolvedValueOnce(response({ ...versionWire, status: 'PUBLISHED' }, 200))
      .mockResolvedValueOnce(response(deploymentWire, 200))
      .mockResolvedValueOnce(response({ ...deploymentWire, status: 'DISABLED', revision: 2 }, 200))
      .mockResolvedValueOnce(response({ ...deploymentWire, status: 'ACTIVE', revision: 3 }, 200))
      .mockResolvedValueOnce(
        response(
          [
            {
              name: 'get_current_time',
              description: 'Returns time',
              content_hash: 'sha256:time',
              descriptor: { input_schema: { type: 'object' } }
            }
          ],
          200
        )
      )
    vi.stubGlobal('fetch', fetchMock)

    const catalog = createCapabilityCatalogApi(client())
    await catalog.getDefinition('200')
    await catalog.createDraftVersion('200', {
      versionKey: '1.0.0',
      name: 'Calendar v1',
      contentHash: 'sha256:v1',
      descriptor: { model_invocable: true }
    })
    await catalog.updateDraftVersion('200', '202', 4, {
      name: 'Calendar updated',
      contentHash: 'sha256:v2',
      descriptor: { model_invocable: true }
    })
    const validation = await catalog.validateVersion('200', '202')
    await catalog.publishVersion('200', '202', validation.definitionRevision)
    await catalog.createDeployment({
      deploymentKey: 'calendar-prod',
      toolVersionId: '202',
      providerType: 'PLATFORM',
      providerRef: 'get_current_time',
      environment: 'PRODUCTION'
    })
    await catalog.stopDeployment('calendar-prod', 1)
    await catalog.startDeployment('calendar-prod', 2)
    const platformTools = await catalog.listPlatformTools()

    const updateCall = fetchMock.mock.calls[2] as [string, RequestInit]
    expect(validation).toMatchObject({ definitionId: '200', versionId: '202', valid: true })
    expect(fetchMock.mock.calls[0][0]).toBe(
      '/api/v1/capabilities/definitions/detail?definitionId=200'
    )
    expect(fetchMock.mock.calls[1][0]).toBe('/api/v1/capabilities/definitions/versions')
    expect(JSON.parse(String((fetchMock.mock.calls[1][1] as RequestInit).body)).payload).toEqual(
      expect.objectContaining({
        version_key: '1.0.0',
        content_hash: 'sha256:v1',
        descriptor: { model_invocable: true }
      })
    )
    expect((updateCall[1].headers as Headers).get('If-Match')).toBe('4')
    expect(JSON.parse(String(updateCall[1].body)).payload).toEqual(
      expect.objectContaining({
        name: 'Calendar updated',
        content_hash: 'sha256:v2'
      })
    )
    const publishCall = fetchMock.mock.calls[4] as [string, RequestInit]
    expect((publishCall[1].headers as Headers).get('If-Match')).toBe('4')
    const stopCall = fetchMock.mock.calls[6] as [string, RequestInit]
    expect((stopCall[1].headers as Headers).get('If-Match')).toBe('1')
    const startCall = fetchMock.mock.calls[7] as [string, RequestInit]
    expect((startCall[1].headers as Headers).get('If-Match')).toBe('2')
    expect(platformTools[0]).toEqual({
      name: 'get_current_time',
      description: 'Returns time',
      contentHash: 'sha256:time',
      descriptor: { input_schema: { type: 'object' } }
    })
  })

  it('covers catalog list/get and create wire mappings', async () => {
    localStorage.setItem('mounts.access', 'token')
    const definitionWire = {
      id: '210',
      tenant_id: '9',
      capability_type: 'SKILL',
      capability_key: 'skill.calendar',
      name: 'Calendar Skill',
      description: 'desc',
      trust_level: 'TENANT',
      status: 'DRAFT',
      revision: 1
    }
    const versionWire = {
      id: '211',
      definition_id: '210',
      tenant_id: '9',
      capability_type: 'SKILL',
      version_key: '1.0.0',
      name: 'Calendar Skill v1',
      description: 'desc',
      trust_level: 'TENANT',
      descriptor: { model_invocable: true },
      content_hash: 'sha256:calendar',
      status: 'DRAFT'
    }
    const deploymentWire = {
      id: '312',
      tenant_id: '9',
      deployment_key: 'calendar-dev',
      tool_version_id: '211',
      definition_id: null,
      tool_name: null,
      tool_version_key: null,
      provider_type: 'CONNECTOR',
      provider_ref: 'http-calendar-1',
      environment: 'DEVELOPMENT',
      status: 'ACTIVE',
      revision: 1
    }
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(response([definitionWire], 200))
      .mockResolvedValueOnce(response(definitionWire, 200))
      .mockResolvedValueOnce(response(definitionWire, 201))
      .mockResolvedValueOnce(response([versionWire], 200))
      .mockResolvedValueOnce(response(versionWire, 200))
      .mockResolvedValueOnce(response([deploymentWire], 200))
      .mockResolvedValueOnce(response(deploymentWire, 200))
    vi.stubGlobal('fetch', fetchMock)

    const catalog = createCapabilityCatalogApi(client())
    const listedDefinitions = await catalog.listDefinitions({
      capabilityType: 'SKILL',
      status: 'DRAFT'
    })
    const fetchedDefinition = await catalog.getDefinition('210')
    const createdDefinition = await catalog.createDefinition({
      capabilityType: 'SKILL',
      capabilityKey: 'skill.calendar',
      name: 'Calendar Skill',
      description: 'desc'
    })
    const listedVersions = await catalog.listVersions('210', 'DRAFT')
    const fetchedVersion = await catalog.getVersion('211')
    const listedDeployments = await catalog.listDeployments('ACTIVE')
    const fetchedDeployment = await catalog.getDeployment('calendar-dev')

    expect(listedDefinitions[0].capabilityKey).toBe('skill.calendar')
    expect(fetchedDefinition.id).toBe('210')
    expect(createdDefinition.capabilityType).toBe('SKILL')
    expect(listedVersions[0].versionKey).toBe('1.0.0')
    expect(fetchedVersion.contentHash).toBe('sha256:calendar')
    expect(listedDeployments[0].providerRef).toBe('http-calendar-1')
    expect(fetchedDeployment.environment).toBe('DEVELOPMENT')
    expect(listedDeployments[0].toolName).toBeNull()
    expect(fetchedDeployment.toolVersionKey).toBeNull()
    expect(fetchMock.mock.calls.map((call) => call[0])).toEqual([
      '/api/v1/capabilities/definitions?capability_type=SKILL&status=DRAFT',
      '/api/v1/capabilities/definitions/detail?definitionId=210',
      '/api/v1/capabilities/definitions',
      '/api/v1/capabilities/definitions/versions?definitionId=210&status=DRAFT',
      '/api/v1/capabilities/versions/detail?versionId=211',
      '/api/v1/capabilities/deployments?status=ACTIVE',
      '/api/v1/capabilities/deployments/detail?deploymentKey=calendar-dev'
    ])
    expect(JSON.parse(String((fetchMock.mock.calls[2][1] as RequestInit).body))).toEqual({
      capability_type: 'SKILL',
      capability_key: 'skill.calendar',
      name: 'Calendar Skill',
      description: 'desc'
    })
  })

  it('rejects an unknown catalog status and unknown deployment provider', async () => {
    const fetchMock = vi.fn().mockResolvedValue(response([], 200))
    vi.stubGlobal('fetch', fetchMock)
    const catalog = createCapabilityCatalogApi(client())
    expect(() => catalog.listDefinitions({ status: 'BROKEN' as never })).toThrow(
      /status is invalid/
    )
    expect(() => catalog.listDeployments('BROKEN' as never)).toThrow(/status is invalid/)
    expect(() =>
      catalog.createDeployment({
        deploymentKey: 'bad',
        toolVersionId: '1',
        providerType: 'UNKNOWN' as never,
        providerRef: 'provider',
        environment: 'TEST'
      })
    ).toThrow(/providerType is invalid/)
    expect(fetchMock).not.toHaveBeenCalled()
  })
})

it('requirement options use a scoped type GET and preserve exact published identities', async () => {
  const http = client()
  const fetchMock = vi.fn<typeof fetch>().mockResolvedValue(
    response([
      {
        definition_id: '9223372036854775807',
        name: '当前工具',
        capability_key: 'tool:current',
        description: '工具说明',
        version_id: '9223372036854775806',
        version_key: 'current',
        version_name: '版本名称',
        skill_estimated_tokens: null
      }
    ])
  )
  vi.stubGlobal('fetch', fetchMock)
  const options = await createCapabilityCatalogApi(http).listRequirementOptions('TOOL')
  expect(fetchMock).toHaveBeenCalledExactlyOnceWith(
    '/api/v1/capabilities/requirements/options?capability_type=TOOL',
    expect.objectContaining({ method: 'GET' })
  )
  expect(options).toEqual([
    {
      id: '9223372036854775807',
      name: '当前工具',
      capabilityKey: 'tool:current',
      description: '工具说明',
      versionId: '9223372036854775806',
      versionKey: 'current',
      versionName: '版本名称',
      skillEstimatedTokens: null
    }
  ])
})

it.each([
  { capability_key: '', description: '说明' },
  { capability_key: 'skill:current', description: null }
])('requirement options reject invalid definition text at the API boundary', async (text) => {
  const fetchMock = vi.fn<typeof fetch>().mockResolvedValue(
    response([
      {
        definition_id: '1',
        name: '技能',
        ...text,
        version_id: '2',
        version_key: 'current',
        version_name: '版本名称',
        skill_estimated_tokens: 31
      }
    ])
  )
  vi.stubGlobal('fetch', fetchMock)
  await expect(
    createCapabilityCatalogApi(client()).listRequirementOptions('SKILL')
  ).rejects.toThrow()
})

it('Skill history summaries use exact definition identity and map only three scalar fields', async () => {
  const http = client()
  const fetchMock = vi
    .fn<typeof fetch>()
    .mockResolvedValue(
      response([{ id: '9223372036854775806', version_key: 'historical', status: 'ARCHIVED' }])
    )
  vi.stubGlobal('fetch', fetchMock)
  const versions =
    await createCapabilityCatalogApi(http).listSkillVersionSummaries('9223372036854775807')
  expect(fetchMock).toHaveBeenCalledExactlyOnceWith(
    '/api/v1/capabilities/skills/versions/summaries?definition_id=9223372036854775807',
    expect.objectContaining({ method: 'GET' })
  )
  expect(versions).toEqual([
    { id: '9223372036854775806', versionKey: 'historical', status: 'ARCHIVED' }
  ])
  expect(versions[0]).not.toHaveProperty('descriptor')
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
  const http = client()
  const fetchMock = vi.fn<typeof fetch>().mockResolvedValue(response(wire))
  vi.stubGlobal('fetch', fetchMock)
  const catalog = createCapabilityCatalogApi(http)
  expect(await catalog.listVersionSummaries('9223372036854775807')).toEqual([
    {
      id: '9223372036854775806',
      versionKey: 'history',
      name: '历史版本',
      description: '说明',
      status: 'ARCHIVED'
    }
  ])
  expect(fetchMock).toHaveBeenCalledExactlyOnceWith(
    '/api/v1/capabilities/versions/summaries?definition_id=9223372036854775807',
    expect.objectContaining({ method: 'GET' })
  )
})

describe('工作流挂载选项', () => {
  it('保留大整数身份并拒绝不合法身份', async () => {
    const get = vi
      .fn()
      .mockResolvedValueOnce([
        {
          id: '9223372036854775807',
          instance_id: 'one',
          workflow_id: 'flow',
          installation_revision: '9',
          definition_id: '9223372036854775806'
        }
      ])
      .mockResolvedValueOnce([
        {
          id: '0',
          instance_id: 'one',
          workflow_id: 'flow',
          installation_revision: '9',
          definition_id: '10'
        }
      ])
    const mounts = createCapabilityMountApi({ get, post: vi.fn(), put: vi.fn(), del: vi.fn() })
    await expect(mounts.workflowOptions()).resolves.toEqual([
      {
        id: '9223372036854775807',
        instanceId: 'one',
        workflowId: 'flow',
        installationRevision: '9',
        definitionId: '9223372036854775806'
      }
    ])
    await expect(mounts.workflowOptions()).rejects.toThrow()
  })
})
