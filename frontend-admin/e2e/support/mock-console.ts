import { expect, type Locator, type Page, type Request } from '@playwright/test'

export interface MockReply {
  data?: unknown
  status?: number
  code?: string
}
export interface RecordedRequest {
  method: string
  path: string
  query: string
  body: unknown
}
const areas = [
  'agents',
  'teams',
  'workflows',
  'skills',
  'capabilities',
  'connectors',
  'knowledge',
  'memory',
  'sessions',
  'handoff',
  'channels',
  'service_principals',
  'usage',
  'billing',
  'audit',
  'models',
  'tenants',
  'applications',
  'runtime_settings',
  'system_agents',
  'system_agent_tools',
  'agent_templates',
  'marketplace'
]
export const fullPermissions = areas.flatMap((area) =>
  ['read', 'create', 'update', 'delete', 'publish', 'run'].map(
    (action) => `foundation.${area}.${action}`
  )
)

/** Per-context complex data; unknown API calls fail instead of fake success. */
export async function mockConsole(
  page: Page,
  kind: 'admin' | 'platform',
  permissions = fullPermissions
) {
  const requests: RecordedRequest[] = [],
    unhandled: string[] = []
  const user = {
    sub: `${kind}-operator`,
    tenant_id: kind === 'admin' ? '9007199254740993' : undefined,
    username: '复杂夹具管理员',
    roles: ['admin', 'custom-auditor'],
    permissions,
    authorization_version: 7,
    mfa_state: 'full',
    session_id: `${kind}-session-v7`
  }
  const agents = Array.from({ length: 47 }, (_, i) => ({
    id: String(9100000000000000n + BigInt(i)),
    agent_key: `agent-${i}`,
    name: `智能体-${String(i).padStart(2, '0')}-版本${(i % 4) + 1}`,
    revision: 4 + (i % 3),
    template_id: i % 2 ? '301' : null,
    template_name: i % 2 ? '公共模板-301' : null,
    status: i % 5 ? 'published' : 'draft',
    published_revision_id: i % 5 ? 700 + i : null,
    published_revision: i % 5 ? 3 : null,
    has_draft_changes: i % 3 === 0,
    delegation_config: { enabled: i % 2 === 0, target_agent_ids: ['9100000000000001'] }
  }))
  const endUsers = Array.from({ length: 73 }, (_, i) => ({
    id: `user-${i}`,
    display_name: `用户-${String(i).padStart(2, '0')}`
  }))
  const facts = Array.from({ length: 137 }, (_, i) => ({
    ref: { tenant_id: { value: '9007199254740993' }, fact_id: `fact-${i}` },
    scope: ['USER_AGENT', 'SESSION', 'USER'][i % 3],
    type: { value: 'PREFERENCE' },
    subject: `主体-${i}`,
    predicate: `关系-${String(i).padStart(3, '0')}`,
    object: {
      schema_id: 'profile-v2',
      canonical_json: JSON.stringify({
        preferences: {
          language: '中文',
          projects: [
            { code: `P-${i}`, tags: ['审计', '嵌套'] },
            { code: 'shared', tags: [] }
          ]
        },
        version: (i % 4) + 1
      })
    },
    source_event_ids: [{ value: `event-${i}` }],
    revision: 4 + (i % 4),
    confidence: 0.8,
    importance: 0.6,
    decay_score: 1,
    review_required: i % 5 === 0,
    status: 'ACTIVE',
    created_at: '2026-10-12T09:10:11+08:00',
    agent_id: agents[i % 4]!.id,
    end_user_id: endUsers[i % 7]!.id
  }))
  const tenants = Array.from({ length: 47 }, (_, i) => ({
    id: String(9200000000000000n + BigInt(i)),
    name: `复杂机构-${String(i).padStart(2, '0')}`,
    slug: `complex-${i}`,
    status: i % 3 ? 'active' : 'suspended',
    plan_code: ['free', 'pro', 'enterprise'][i % 3],
    created_at: '2026-10-12T09:10:11+08:00'
  }))
  const models = Array.from({ length: 37 }, (_, i) => ({
    id: `model-${i}`,
    name: `复杂模型-${String(i).padStart(2, '0')}`,
    model_id: `catalog-${i}`,
    provider_id: i % 2 ? 'provider-b' : 'provider-a',
    kind: ['LLM', 'EMBEDDING', 'RERANK'][i % 3],
    enabled: i % 5 !== 0,
    revision: 5,
    published_revision: 4,
    has_unpublished_changes: i % 2 === 0
  }))
  let hook: ((request: Request, url: URL) => Promise<MockReply | undefined>) | undefined
  await page.addInitScript(
    ({ kind, user }) => {
      localStorage.setItem(
        kind === 'admin' ? 'skillforge.access_token' : 'skillforge.platform.access_token',
        'context-owned-mock-token'
      )
      localStorage.setItem(
        kind === 'admin' ? 'skillforge.access_token.session' : 'skillforge.platform.access_token.session',
        `${kind}-fixture-session`
      )
      localStorage.setItem(
        kind === 'admin' ? 'skillforge.user' : 'skillforge.platform.user',
        JSON.stringify(user)
      )
    },
    { kind, user }
  )
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request(),
      url = new URL(request.url()),
      path = url.pathname.slice('/api/v1'.length)
    const body = request.postDataJSON()
    requests.push({ method: request.method(), path, query: url.search, body })
    let result = await hook?.(request, url)
    if (!result) {
      if (path === (kind === 'admin' ? '/auth/me' : '/platform/auth/me')) result = { data: user }
      else if (path.endsWith('/foundation/catalog/navigation/current'))
        result = {
          data: [
            {
              title: '业务',
              path: null,
              permission: null,
              children: (kind === 'admin'
                ? [
                    ['智能体', 'agents'],
                    ['记忆管理', 'memory']
                  ]
                : [
                    ['模型管理', 'models'],
                    ['机构管理', 'tenants']
                  ]
              ).map(([title, target]) => ({
                title,
                path: `/${kind}/${target}`,
                permission: null,
                children: []
              }))
            }
          ]
        }
      else if (path === '/agents') result = { data: { total: agents.length, items: agents } }
      else if (path === '/chat/end-users') result = { data: endUsers }
      else if (path === '/memories' || path === '/memories/reviews') {
        const matches = facts.filter(
          (f) =>
            (path !== '/memories/reviews' || f.review_required) &&
            (!url.searchParams.get('scope') || f.scope === url.searchParams.get('scope')) &&
            (!url.searchParams.get('agent_id') ||
              f.agent_id === url.searchParams.get('agent_id')) &&
            (!url.searchParams.get('end_user_id') ||
              f.end_user_id === url.searchParams.get('end_user_id'))
        )
        result = { data: matches.slice(0, Number(url.searchParams.get('limit') || 100)) }
      } else if (path === '/admin/tenants') {
        const matches = tenants.filter(
          (t) =>
            (!url.searchParams.get('q') ||
              `${t.name} ${t.slug}`.includes(url.searchParams.get('q')!)) &&
            (!url.searchParams.get('status') || t.status === url.searchParams.get('status'))
        )
        const size = Number(url.searchParams.get('limit') || 20),
          current = Number(url.searchParams.get('page') || 1)
        result = {
          data: {
            total: matches.length,
            items: matches.slice((current - 1) * size, current * size)
          }
        }
      } else if (path === '/admin/tenants/plans')
        result = {
          data: ['free', 'pro', 'enterprise'].map((plan_code) => ({
            plan_code,
            display_name: `套餐-${plan_code}`
          }))
        }
      else if (path === '/admin/models/summaries')
        result = { data: models.map(({ revision: _revision, ...summary }) => summary) }
      else if (path === '/admin/models') result = { data: models }
      else if (path === '/admin/models/providers') result = { data: { providers: [] } }
    }
    if (!result) {
      unhandled.push(`${request.method()} ${path}${url.search}`)
      result = { status: 500, code: 'UNHANDLED_MOCK_ENDPOINT' }
    }
    await route.fulfill({
      status: result.status ?? 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: result.code ?? 'OK',
        message: result.status ? '受控夹具拒绝' : 'OK',
        trace_id: `mock-${requests.length}`,
        data: result.data ?? null
      })
    })
  })
  return {
    agents,
    facts,
    endUsers,
    tenants,
    models,
    requests,
    unhandled,
    user,
    setHook(value: typeof hook) {
      hook = value
    },
    assertHandled() {
      expect(unhandled, 'every API must have a named fixture').toEqual([])
    }
  }
}

/** Inspect focusable Element Plus/CodeMirror nodes, not wrapper attributes. */
export async function assertVisibleDescriptions(container: Locator) {
  const controls = container.locator(
    'input:not([type=hidden]), textarea, [role=combobox], [role=switch], .cm-content'
  )
  const count = await controls.count()
  expect(count).toBeGreaterThan(0)
  for (let i = 0; i < count; i++) {
    const control = controls.nth(i)
    if (!(await control.isVisible())) continue
    const ids = ((await control.getAttribute('aria-describedby')) ?? '')
      .split(/\s+/)
      .filter(Boolean)
    // Self-explanatory filters use a visible label or explicit accessible name.
    // Declared contextual help still has to be unique, visible, and nonempty.
    if (!ids.length)
      await expect(control, `visible control ${i} needs an accessible name`).toHaveAccessibleName(
        /.+/
      )
    for (const id of ids) {
      const description = container.page().locator(`[id="${id}"]`)
      await expect(description).toHaveCount(1)
      await expect(description).toBeVisible()
      expect((await description.textContent())?.trim()).toBeTruthy()
    }
  }
}
