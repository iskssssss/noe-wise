/** Free, isolated, API-only fixtures for actual dual-console route reading. */
import { readFile, writeFile, mkdir, readdir, access } from 'node:fs/promises'
import { createHash } from 'node:crypto'
import { resolve, join, relative } from 'node:path'

const repo = process.cwd()
const options = Object.fromEntries(
  process.argv.slice(2).reduce((pairs, value, index, values) => {
    if (value.startsWith('--')) pairs.push([value.slice(2), values[index + 1]])
    return pairs
  }, [])
)
if (!options.root || !options.env) throw new Error('--root and --env are required')
const root = resolve(options.root)
if (relative(resolve('.runtime-logs'), root).startsWith('..'))
  throw new Error('Private evidence required')
await mkdir(root, { recursive: false })
const env = Object.fromEntries(
  (await readFile(options.env, 'utf8')).split(/\r?\n/).flatMap((line) => {
    const match = line.match(/^\s*([A-Za-z_][A-Za-z0-9_]*)=(.*)$/)
    return match ? [[match[1], match[2].trim().replace(/^(['"])(.*)\1$/, '$2')]] : []
  })
)
const environmentRoot = resolve(options.env, '..')
const state = JSON.parse(await readFile(join(environmentRoot, 'environment.json'), 'utf8'))
const provision = JSON.parse(await readFile(join(environmentRoot, 'provision.json'), 'utf8'))
const source = JSON.parse(
  await readFile(
    resolve('.agent-work-state/full-test-20261012/execution-source/fixture.private.json'),
    'utf8'
  )
)
const save = (name, data) =>
  writeFile(join(root, name), JSON.stringify(data, null, 2), { flag: 'wx' })
const sha = (data) =>
  createHash('sha256')
    .update(JSON.stringify(data ?? null))
    .digest('hex')
let sequence = 0
const previous = options.previous ? resolve(options.previous) : null
if (previous) {
  if (relative(resolve('.runtime-logs'), previous).startsWith('..'))
    throw new Error('Private previous evidence required')
  for (const name of await readdir(previous)) {
    if (name.endsWith('-intent.json')) {
      await access(join(previous, name.replace('-intent.json', '-receipt.json'))).catch(() => {
        throw new Error('Unconfirmed prior dispatch must be queried, never replayed')
      })
    }
  }
}
const results = previous
  ? await readFile(join(previous, 'manifest.json'), 'utf8')
      .then(JSON.parse)
      .catch(async (error) => {
        if (error.code !== 'ENOENT') throw error
        return JSON.parse(await readFile(join(previous, 'failure.json'), 'utf8')).partial
      })
  : {
      admin: {},
      platform: {},
      supplier_calls: 0,
      runtime_runs: 0,
      created_at: new Date().toISOString()
    }
if (previous) results.previous_evidence = relative(repo, previous)

async function api(host, token, method, path, body) {
  const base =
    state.services[host === 'admin' ? 'institution-service' : 'platform-service'].base_url
  if (new URL(base).hostname !== '127.0.0.1') throw new Error('Owned loopback service required')
  const identity = String(++sequence).padStart(3, '0')
  const auth = path.includes('/auth/')
  const credentialBody =
    auth ||
    /"(?:password|api_key|signing_secret|access_token|refresh_token)"/.test(
      JSON.stringify(body ?? {})
    )
  await save(`${identity}-intent.json`, {
    host,
    method,
    path,
    body: credentialBody ? 'PRIVATE_CREDENTIAL_BODY' : body,
    body_sha256: credentialBody ? 'PRIVATE_CREDENTIAL_BODY' : sha(body),
    started_at: new Date().toISOString()
  })
  // No automatic replay: every write has one dispatch and one independent receipt.
  const response = await fetch(`${base}/api/v1${path}`, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(token
        ? {
            [host === 'platform' ? 'X-Platform-Token' : 'Authorization']: `Bearer ${token}`
          }
        : {})
    },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    signal: AbortSignal.timeout(30000)
  })
  const envelope = await response.json()
  await save(`${identity}-receipt.json`, {
    host,
    method,
    path,
    status: response.status,
    code: envelope.code,
    trace_id: envelope.trace_id,
    response_sha256: auth ? 'PRIVATE_LOGIN' : sha(envelope.data),
    data: auth
      ? { roles: envelope.data?.roles, mfa_state: envelope.data?.mfa_state }
      : envelope.data,
    finished_at: new Date().toISOString()
  })
  if (!response.ok || envelope.code !== 'OK')
    throw new Error(`${host} ${method} ${path} HTTP ${response.status} code ${envelope.code}`)
  await new Promise((done) => setTimeout(done, 800))
  return envelope.data
}

try {
  for (const host of ['admin', 'platform']) {
    const login = await api(
      host,
      null,
      'POST',
      host === 'admin' ? '/auth/login' : '/platform/auth/login',
      {
        ...(host === 'admin' ? { tenant_slug: env.SF_TENANT || 'full-test-a' } : {}),
        username: host === 'admin' ? env.SF_ADMIN_USER || env.SF_USER : env.SF_PLATFORM_USER,
        password:
          host === 'admin' ? env.SF_ADMIN_PASSWORD || env.SF_PASSWORD : env.SF_PLATFORM_PASSWORD
      }
    )
    if (login.mfa_state !== 'full') throw new Error('Full formal auth required')
    const token = login.access_token
    const prefix = host === 'admin' ? '/capabilities' : '/admin/capabilities'
    for (const type of ['TOOL', 'SKILL']) {
      const kind = type === 'TOOL' ? 'capability' : 'skill'
      const item = results[host][kind]
        ? await api(
            host,
            token,
            'GET',
            `${prefix}/definitions/detail?definitionId=${results[host][kind]}`
          )
        : await api(host, token, 'POST', `${prefix}/definitions`, {
            capability_type: type,
            capability_key: `full-ui-smoke-${host}-${type.toLowerCase()}`,
            name: `本批页面验收 ${host} ${type}`,
            description: '仅供本批隔离页面读取，未发布，不执行供应商或外部工具'
          })
      if (item.capability_key !== `full-ui-smoke-${host}-${type.toLowerCase()}`)
        throw new Error('Prior owned capability identity changed')
      results[host][kind] = String(item.id)
      if (type === 'SKILL') {
        const draft = results[host].skill_draft
          ? await api(host, token, 'GET', `${prefix}/skill-drafts?definitionId=${item.id}`)
          : await api(host, token, 'POST', `${prefix}/skill-drafts`, { definition_id: item.id })
        if (results[host].skill_draft && String(draft.id) !== results[host].skill_draft)
          throw new Error('Prior owned draft identity changed')
        results[host].skill_draft = String(draft.id)
      }
    }
    if (host === 'admin') {
      const workflow = results.admin.workflow
        ? await api(
            host,
            token,
            'GET',
            `/builder/workflows/draft?workflowId=${results.admin.workflow}`
          )
        : await api(host, token, 'POST', '/builder/workflows', {
            workflow_id: 'full-ui-smoke-workflow',
            definition: {
              version: '1.0',
              entry: 'start',
              nodes: [
                { id: 'start', type: 'start' },
                { id: 'end', type: 'end' }
              ],
              edges: [{ from: 'start', to: 'end' }],
              meta: {
                name: '本批页面验收工作流',
                description: '仅创建草稿，不发布或执行',
                version: '1.0.0'
              }
            }
          })
      results.admin.workflow = workflow.workflow_id
      const knowledge = results.admin.knowledge
        ? await api(host, token, 'GET', `/knowledge/bases/detail?kbId=${results.admin.knowledge}`)
        : await api(host, token, 'POST', '/knowledge/bases', {
            name: '本批页面验收知识库',
            description: '仅创建空知识库，不上传/嵌入/重建/执行'
          })
      results.admin.knowledge = String(knowledge.id)
      const agents = await api(host, token, 'GET', '/agents')
      const sessions = await api(host, token, 'GET', '/chat/sessions')
      const sourceAgent = (agents.items || agents).find(
        (row) => String(row.id) === String(source.source_agent_id)
      )
      const sourceSession = (sessions.items || sessions).find(
        (row) => String(row.session_id) === String(source.browser_session_id)
      )
      if (!sourceAgent || !sourceSession)
        throw new Error('Source fixture Agent and Session must be in real tenant catalogue')
      results.admin.agent = String(sourceAgent.id)
      results.admin.session = String(sourceSession.session_id)
    } else {
      const application = results.platform.application
        ? await api(
            host,
            token,
            'GET',
            `/platform/applications/detail?applicationId=${results.platform.application}`
          )
        : await api(host, token, 'POST', '/platform/applications', {
            name: '本批页面验收应用',
            policy: {
              default_plan_code: 'free',
              max_tenants: 1,
              allow_provision: false,
              quota_api_calls_per_month: null,
              quota_max_knowledge_bases: null,
              quota_max_members: null,
              quota_max_channels: null,
              rate_limit_per_minute: null
            }
          })
      results.platform.application = application.application_id
      const template = results.platform.template
        ? await api(
            host,
            token,
            'GET',
            `/admin/agent-templates/detail?templateId=${results.platform.template}`
          )
        : await api(host, token, 'POST', '/admin/agent-templates', {
            name: '本批页面验收模板',
            description: '仅草稿读取，不发布/安装/执行',
            category: 'test',
            tags: ['isolated-ui-smoke'],
            price_type: 'free',
            price_cents: 0,
            definition: {
              system_prompt: '仅供本批隔离页面验收。',
              role_description: '',
              model_config_id: provision.models.llm.id,
              execution_config: {
                max_business_model_calls: null,
                max_tool_calls: null,
                max_knowledge_calls: null,
                max_total_tokens: null,
                max_wall_time_seconds: null
              },
              capabilities: [],
              resource_slots: [],
              delegation_slots: []
            }
          })
      results.platform.template = String(template.id)
      const tenant = provision.tenants.find((row) => row.slug === (env.SF_TENANT || 'full-test-a'))
      if (!tenant) throw new Error('Batch tenant receipt required')
      results.platform.tenant = String(tenant.id)
      results.platform.model = String(provision.models.llm.id)
      if (options['dedicated-admin']) {
        const username = options['dedicated-admin']
        if (!/^[a-z0-9-]{4,50}$/.test(username)) throw new Error('Invalid owned UI account name')
        const members = await api(
          host,
          token,
          'GET',
          `/admin/tenants/members?tenantId=${tenant.id}&page=1&limit=200`
        )
        let member = members.items.find((row) => row.username === username)
        if (
          member &&
          (!results.admin.identity || String(member.id) !== results.admin.identity.member_id)
        ) {
          throw new Error(
            'Account exists without an owned prior receipt; do not replace credentials'
          )
        }
        if (!member)
          member = await api(host, token, 'POST', '/admin/tenants/members', {
            tenant_id: String(tenant.id),
            payload: {
              username,
              password: env.SF_ADMIN_PASSWORD || env.SF_PASSWORD,
              roles: ['tenant_admin'],
              email: 'ui-smoke-admin@example.test'
            }
          })
        if (!member.roles?.includes('tenant_admin') || member.username !== username)
          throw new Error('Owned UI role receipt mismatch')
        results.admin.identity = {
          member_id: String(member.id),
          username,
          roles: member.roles,
          owner: 'full-project-test-20261012/ui-route-smoke',
          cleanup:
            'Delete only with owned isolated environment; retain until final evidence collection'
        }
        const envText = await readFile(options.env, 'utf8')
        await writeFile(join(root, 'isolated-ui.env'), `${envText}\nSF_ADMIN_USER=${username}\n`, {
          flag: 'wx'
        })
        results.private_ui_env = relative(repo, join(root, 'isolated-ui.env'))
      }
    }
  }
  await save('manifest.json', results)
  console.log(JSON.stringify({ status: 'PREPARED', references: results, dispatches: sequence }))
} catch (error) {
  await save('failure.json', {
    status: 'FAIL',
    reason: error.message,
    partial: results,
    dispatches: sequence
  })
  console.error(error.message)
  process.exitCode = 1
}
