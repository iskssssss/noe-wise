/** Real dual-console route smoke: owned loopback Vite, password login, read-only business HTTP. */
import { readFile, writeFile, mkdir } from 'node:fs/promises'
import { createHash } from 'node:crypto'
import { createRequire } from 'node:module'
import { dirname, resolve, relative, join } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const repo = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
const requireApp = createRequire(join(repo, 'frontend-platform/package.json'))
const { chromium } = requireApp('@playwright/test')
const { createServer, loadConfigFromFile } = await import(
  pathToFileURL(requireApp.resolve('vite')).href
)
const options = Object.fromEntries(
  process.argv.slice(2).reduce((pairs, value, index, values) => {
    if (value.startsWith('--')) pairs.push([value.slice(2), values[index + 1]])
    return pairs
  }, [])
)
if (!options.root || !options.env) throw new Error('--root and --env are required')
const root = resolve(repo, options.root)
if (relative(join(repo, '.runtime-logs'), root).startsWith('..'))
  throw new Error('Evidence must remain in .runtime-logs')
await mkdir(root, { recursive: false })
const env = {}
for (const line of (await readFile(resolve(repo, options.env), 'utf8')).split(/\r?\n/)) {
  const match = line.match(/^\s*([A-Za-z_][A-Za-z0-9_]*)=(.*)$/)
  if (match) env[match[1]] = match[2].trim().replace(/^(['"])(.*)\1$/, '$2')
}
const secrets = new Set(
  Object.entries(env)
    .filter(([name]) => /PASSWORD|TOKEN|KEY|SECRET/.test(name))
    .map(([, value]) => value)
    .filter(Boolean)
)
const redact = (value) => {
  let text = String(value)
  for (const secret of secrets) text = text.replaceAll(secret, '[REDACTED]')
  return text.replace(/Bearer\s+[A-Za-z0-9._-]+/g, 'Bearer [REDACTED]')
}
const save = (file, data) =>
  writeFile(file, JSON.stringify(data, null, 2), { encoding: 'utf8', flag: 'wx' })
const now = () => new Date().toISOString()
const hash = (value) => createHash('sha256').update(value).digest('hex')
const browser = await chromium.launch({ headless: true })
const servers = new Set()
const outcomes = []
const services = []
const fixtureManifest = options.fixtures
  ? JSON.parse(await readFile(resolve(repo, options.fixtures), 'utf8'))
  : null
const nextApiAt = { admin: 0, platform: 0 }
// Real host default is 120 requests per authenticated actor per UTC minute.
// Leave room for concurrent acceptance reads; do not retry or clear real counters.
async function paceApi(host) {
  const slot = Math.max(Date.now(), nextApiAt[host])
  nextApiAt[host] = slot + 750
  const delay = slot - Date.now()
  if (delay > 0) await new Promise((done) => setTimeout(done, delay))
}

async function startVite(host, origin) {
  const app = join(repo, `frontend-${host}`)
  const oldCwd = process.cwd()
  const oldBackend = process.env.E2E_BACKEND_URL
  try {
    process.chdir(app)
    process.env.E2E_BACKEND_URL = origin
    const loaded = await loadConfigFromFile(
      { command: 'serve', mode: 'development' },
      join(app, 'vite.config.ts'),
      app
    )
    // This plugin only emits development .d.ts; excluding its writer avoids touching an existing checkout/server.
    const plugins = loaded.config.plugins
      .flat()
      .filter((plugin) => plugin?.name !== 'skillforge:component-declarations')
    const proxy = { ...loaded.config.server.proxy }
    if (host === 'admin') {
      // Institution explicitly admits localhost:3000 in WebConfig. This owned,
      // same-origin reverse proxy represents that admitted application origin;
      // these route smokes do not claim random-port browser CORS acceptance.
      for (const path of ['/api', '/health']) {
        proxy[path] = {
          ...proxy[path],
          configure(instance) {
            instance.on('proxyReq', (request, incoming) => {
              if (incoming.headers.origin) request.setHeader('Origin', 'http://localhost:3000')
            })
          }
        }
      }
    }
    const server = await createServer({
      ...loaded.config,
      plugins,
      configFile: false,
      root: app,
      cacheDir: join(root, 'vite-cache', host),
      server: {
        ...loaded.config.server,
        proxy,
        host: '127.0.0.1',
        port: 0,
        strictPort: true,
        open: false
      },
      clearScreen: false
    })
    await server.listen()
    servers.add(server)
    const address = server.httpServer.address()
    const base = `http://127.0.0.1:${address.port}`
    const source = await readFile(join(app, 'src/router/index.ts'), 'utf8')
    const routes = [
      ...source.matchAll(/path:\s*'([^']+)'[\s\S]*?component:\s*\(\)\s*=>\s*import\('([^']+)'\)/g)
    ]
      .filter((match) => !match[1].startsWith('/'))
      .map((match) => ({ template: `/${host}/${match[1]}`, component: match[2] }))
    const declared = [...source.matchAll(/path:\s*'([^']+)'/g)]
      .map((match) => match[1])
      .filter((path) => !path.startsWith('/'))
    if (
      declared.some((path) => !routes.some((route) => route.template === `/${host}/${path}`)) ||
      new Set(routes.map((route) => route.template)).size !== routes.length
    )
      throw new Error('The source route roster is incomplete or duplicated')
    return { host, base, origin, routes, router_sha256: hash(source), server }
  } finally {
    process.chdir(oldCwd)
    if (oldBackend === undefined) delete process.env.E2E_BACKEND_URL
    else process.env.E2E_BACKEND_URL = oldBackend
  }
}

function visibleSnapshot() {
  const visible = (element) =>
    Boolean(element.getClientRects().length) && getComputedStyle(element).visibility !== 'hidden'
  const content =
    document.querySelector('#main-content') || document.querySelector('main') || document.body
  const controls = [
    ...content.querySelectorAll('input,textarea,[role=combobox],[role=switch],.cm-content')
  ].filter((element) => visible(element) && element.getAttribute('type') !== 'hidden')
  const help = controls.map((element, index) => {
    const described = (element.getAttribute('aria-describedby') || '').split(/\s+/).filter(Boolean)
    const targets = described.map((id) => {
      const matches = document.querySelectorAll(`[id="${CSS.escape(id)}"]`)
      const target = matches[0]
      return {
        id,
        unique: matches.length === 1,
        visible: Boolean(target && visible(target)),
        text: target?.textContent?.trim() || '',
        nearby: Boolean(
          target &&
          (
            element.closest('.el-form-item,.admin-filter-field,.field-group') ||
            element.parentElement?.parentElement
          )?.contains(target)
        )
      }
    })
    return {
      index,
      tag: element.tagName,
      role: element.getAttribute('role'),
      type: element.getAttribute('type'),
      label:
        element.getAttribute('aria-label') ||
        element.getAttribute('placeholder') ||
        element.closest('.el-form-item')?.querySelector('label')?.textContent?.trim() ||
        '',
      described,
      targets,
      description_ok:
        targets.length > 0 &&
        targets.every((target) => target.unique && target.visible && target.text)
    }
  })
  return {
    title: document.title,
    headings: [...content.querySelectorAll('h1,h2,h3')]
      .filter(visible)
      .map((element) => element.textContent?.trim()),
    content_text_length: content.innerText.trim().length,
    rows: content.querySelectorAll('.el-table__body tr').length,
    empty_states: [...content.querySelectorAll('.el-empty__description')]
      .filter(visible)
      .map((element) => element.textContent?.trim()),
    alerts: [...content.querySelectorAll('[role=alert],.el-alert--error')]
      .filter(visible)
      .map((element) => element.textContent?.trim()),
    error_alerts: [...content.querySelectorAll('.el-alert--error')]
      .filter(visible)
      .map((element) => element.textContent?.trim()),
    loading: [
      ...document.querySelectorAll('.el-loading-mask,.el-skeleton,.is-loading,[aria-busy="true"]')
    ].filter(visible).length,
    help
  }
}

async function inspect(page, state, route, destination) {
  await mkdir(destination, { recursive: true })
  const record = {
    ...route,
    started_at: now(),
    business_acceptance: 'NOT_RUN',
    http: [],
    js_errors: [],
    console_errors: [],
    request_failures: [],
    blocked_writes: []
  }
  state.current = record
  try {
    const response = await page.goto(`${state.base}${route.path}`, {
      waitUntil: 'domcontentloaded',
      timeout: 45000
    })
    record.document_status = response?.status()
    await page.waitForLoadState('networkidle', { timeout: 30000 }).catch(() => {
      record.network_idle_timeout = true
    })
    await page
      .waitForFunction(
        () => {
          const visible = (element) =>
            element.getClientRects().length && getComputedStyle(element).visibility !== 'hidden'
          return ![
            ...document.querySelectorAll(
              '.el-loading-mask,.el-skeleton,.is-loading,[aria-busy="true"]'
            )
          ].some(visible)
        },
        undefined,
        { timeout: 15000 }
      )
      .catch(() => {
        record.loading_timeout = true
      })
    await Promise.allSettled([...state.processing])
    record.actual_url = redact(page.url())
    record.dom = JSON.parse(redact(JSON.stringify(await page.evaluate(visibleSnapshot))))
    record.non_2xx = record.http.filter((row) => row.status < 200 || row.status >= 300)
    const failedHttp = record.non_2xx.filter((row) => row.status !== 304)
    record.protocol_errors = record.http.filter((row) => row.code && row.code !== 'OK')
    const redirected = new URL(page.url()).pathname !== route.expected_path
    const failedRequests = record.request_failures.filter(
      (request) => !request.error.includes('ERR_ABORTED')
    )
    record.status =
      record.js_errors.length ||
      record.console_errors.length ||
      failedHttp.length ||
      record.protocol_errors.length ||
      failedRequests.length ||
      record.blocked_writes.length ||
      record.loading_timeout ||
      record.dom.loading ||
      record.dom.error_alerts.length ||
      record.dom.content_text_length < 5 ||
      redirected ||
      record.document_status >= 400
        ? 'FAIL'
        : 'SMOKE_PASS'
    record.help_status = record.dom.help.some((control) => !control.description_ok)
      ? 'GAP'
      : record.dom.help.length
        ? 'PASS'
        : 'NO_VISIBLE_FORM_CONTROLS'
    await page.screenshot({
      path: join(destination, 'capture.png'),
      fullPage: true,
      mask: [page.locator('input[type=password],input[autocomplete=current-password]')]
    })
    record.capture = relative(root, join(destination, 'capture.png')).replaceAll('\\', '/')
  } catch (error) {
    record.status = 'FAIL'
    record.exception = redact(error.message)
    await page
      .screenshot({
        path: join(destination, 'failure.png'),
        fullPage: true,
        mask: [page.locator('input[type=password],input[autocomplete=current-password]')]
      })
      .catch(() => undefined)
  }
  record.finished_at = now()
  await save(join(destination, 'result.json'), record)
  return record
}

async function login(page, state, credentials, destination) {
  const record = {
    path: '/login',
    started_at: now(),
    http: [],
    js_errors: [],
    console_errors: [],
    request_failures: [],
    blocked_writes: []
  }
  state.current = record
  await page.goto(`${state.base}/login`, { waitUntil: 'networkidle', timeout: 90000 })
  record.dom = await page.evaluate(visibleSnapshot)
  if (state.host === 'admin')
    await page.locator('input[autocomplete=organization]').fill(credentials.tenant)
  await page.locator('input[autocomplete=username]').fill(credentials.username)
  await page.locator('input[autocomplete=current-password]').fill(credentials.password)
  const mePath = state.host === 'admin' ? '/api/v1/auth/me' : '/api/v1/platform/auth/me'
  const mePromise = page.waitForResponse(
    (response) => new URL(response.url()).pathname === mePath,
    { timeout: 30000 }
  )
  await page
    .getByRole('button', { name: state.host === 'admin' ? '登录' : '登录运营台', exact: true })
    .click()
  const response = await mePromise
  const envelope = await response.json()
  const user = envelope.data
  if (
    !response.ok() ||
    envelope.code !== 'OK' ||
    user?.mfa_state !== 'full' ||
    !Array.isArray(user.roles) ||
    !Array.isArray(user.permissions)
  )
    throw new Error('Real login/me did not establish the full server identity')
  state.identity = {
    sub: user.sub,
    tenant_id: user.tenant_id,
    roles: user.roles,
    permissions: user.permissions,
    authorization_version: user.authorization_version,
    mfa_state: user.mfa_state,
    session_id: user.session_id
  }
  await page.waitForURL(`**/${state.host}/home`, { timeout: 30000 })
  state.token = await page.evaluate(
    (key) => localStorage.getItem(key),
    state.host === 'admin' ? 'skillforge.access_token' : 'skillforge.platform.access_token'
  )
  if (!state.token) throw new Error('Real login did not issue an access token')
  secrets.add(state.token)
  record.identity = state.identity
  record.status = 'PASS'
  record.finished_at = now()
  await mkdir(destination, { recursive: true })
  await save(join(destination, 'result.json'), JSON.parse(redact(JSON.stringify(record))))
}

async function discover(state, context) {
  const definitions =
    state.host === 'admin' ? '/capabilities/definitions' : '/admin/capabilities/definitions'
  const paths =
    state.host === 'admin'
      ? {
          agent: '/agents',
          workflow: '/builder/workflows',
          knowledge: '/knowledge/bases',
          capability: definitions,
          session: '/chat/sessions'
        }
      : {
          tenant: '/admin/tenants',
          application: '/platform/applications?limit=200&offset=0',
          model: '/admin/models',
          system_agent: '/admin/system-agents',
          template: '/admin/agent-templates',
          capability: definitions,
          session: '/platform/sessions?page=1&page_size=20'
        }
  const refs = {},
    evidence = []
  for (const [kind, path] of Object.entries(paths)) {
    await paceApi(state.host)
    const response = await context.request.get(`${state.base}/api/v1${path}`, {
      headers:
        state.host === 'admin'
          ? { Authorization: `Bearer ${state.token}` }
          : { 'X-Platform-Token': `Bearer ${state.token}` },
      timeout: 30000
    })
    const envelope = await response.json().catch(() => ({}))
    const data = envelope.data
    const items = Array.isArray(data) ? data : data?.items || []
    const first = items[0]
    evidence.push({
      kind,
      path,
      status: response.status(),
      code: envelope.code,
      items: items.length,
      total: data?.total
    })
    if (response.ok() && envelope.code === 'OK' && first) refs[kind] = first
    if (kind === 'capability') refs.skill = items.find((item) => item.capability_type === 'SKILL')
    const desired = fixtureManifest?.[state.host]?.[kind]
    if (desired) {
      const selected = items.find(
        (item) =>
          String(item.id || item.workflow_id || item.application_id || item.session_id) ===
          String(desired)
      )
      if (!selected)
        throw new Error(`Owned ${kind} fixture is absent from the actual ${state.host} catalogue`)
      refs[kind] = selected
    }
    if (kind === 'capability' && fixtureManifest?.[state.host]?.skill) {
      const selected = items.find(
        (item) =>
          String(item.id) === String(fixtureManifest[state.host].skill) &&
          item.capability_type === 'SKILL'
      )
      if (!selected)
        throw new Error(`Owned Skill fixture is absent from the actual ${state.host} catalogue`)
      refs.skill = selected
    }
    if (kind === 'session' && state.host === 'platform' && fixtureManifest?.admin?.session) {
      const owned = items.filter(
        (item) => String(item.tenant_id) === String(fixtureManifest.platform.tenant)
      )
      // The institution browser fixture can have no Run and therefore need not
      // appear in the execution-observation list. Select an actual Run-bearing
      // Session from this same owned batch tenant; never invent an empty trace.
      refs.session =
        owned.find((item) => String(item.session_id) === String(fixtureManifest.admin.session)) ||
        owned[0]
      if (!refs.session)
        throw new Error('A real execution-observation Session from the owned tenant is required')
    }
  }
  return { refs, evidence }
}

function resolveRoute(host, route, refs) {
  const template = route.template
  let entity
  if (template.includes('/agents/:')) entity = refs.agent?.id
  if (template.includes('/workflows/:')) entity = refs.workflow?.workflow_id
  if (template.includes('/knowledge/:')) entity = refs.knowledge?.id
  if (template.includes('/capabilities/:')) entity = refs.capability?.id
  if (template.includes('/skills/:')) entity = refs.skill?.id
  if (template.includes('/tenants/:')) entity = refs.tenant?.id
  if (template.includes('/applications/:')) entity = refs.application?.application_id
  if (template.includes('/models/:')) entity = refs.model?.id
  if (template.includes('/system-agents/:')) entity = refs.system_agent?.id
  if (template.includes('/agent-templates/:')) entity = refs.template?.id
  if (template.includes('/chat/sessions/:')) entity = refs.session?.session_id
  if (template.includes(':') && !entity)
    return {
      ...route,
      status: 'MISSING_FIXTURE',
      reason: 'No real entity in the authenticated isolated catalogue',
      business_acceptance: 'NOT_RUN'
    }
  let path = entity ? template.replace(/:[A-Za-z]+/g, encodeURIComponent(String(entity))) : template
  if (host === 'platform' && template.endsWith('/sessions/detail')) {
    if (!refs.session?.session_id || !refs.session?.tenant_id)
      return {
        ...route,
        status: 'MISSING_FIXTURE',
        reason: 'A real tenant and Session are required',
        business_acceptance: 'NOT_RUN'
      }
    path += `?tenant_id=${encodeURIComponent(refs.session.tenant_id)}&session_id=${encodeURIComponent(refs.session.session_id)}`
  }
  return {
    ...route,
    path,
    expected_path: path.split('?')[0],
    fixture_id: entity ? String(entity) : undefined
  }
}

try {
  for (let round = 1; round <= Number(options.rounds || 3); round++) {
    const currentEnvironment = JSON.parse(
      await readFile(
        resolve(repo, options.environment || join(dirname(options.env), 'environment.json')),
        'utf8'
      )
    )
    const selectedHosts = options.hosts ? options.hosts.split(',') : ['admin', 'platform']
    if (
      !selectedHosts.length ||
      selectedHosts.some((host) => !['admin', 'platform'].includes(host)) ||
      new Set(selectedHosts).size !== selectedHosts.length
    )
      throw new Error('Invalid host roster')
    for (const hostName of selectedHosts) {
      const origin =
        currentEnvironment.services[
          hostName === 'admin' ? 'institution-service' : 'platform-service'
        ].base_url
      if (!['127.0.0.1', 'localhost', '[::1]'].includes(new URL(origin).hostname))
        throw new Error('Real smoke requires a loopback isolated backend')
      const host = await startVite(hostName, origin)
      const { base, router_sha256, routes: sourceRoutes } = host
      services.push({
        round,
        host: hostName,
        base,
        origin,
        router_sha256,
        routes: sourceRoutes,
        api_pacing_ms: 750,
        origin_adapter: hostName === 'admin' ? 'http://localhost:3000' : null,
        cors_acceptance: 'NOT_RUN'
      })
      const lane = join(root, `round-${round}`, hostName)
      await mkdir(lane, { recursive: true })
      const state = { ...host, current: null, processing: new Set(), requestOwners: new WeakMap() }
      const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
      await context.route('**/api/v1/**', async (route) => {
        const request = route.request(),
          method = request.method(),
          path = new URL(request.url()).pathname
        const allowed =
          ['GET', 'HEAD'].includes(method) ||
          (method === 'POST' && /\/auth\/(login|refresh|logout)$/.test(path))
        if (!allowed) {
          state.current?.blocked_writes.push({ method, path })
          await route.abort('blockedbyclient')
        } else {
          await paceApi(state.host)
          await route.continue()
        }
      })
      const page = await context.newPage()
      page.on('request', (request) => state.requestOwners.set(request, state.current))
      page.on('pageerror', (error) => state.current?.js_errors.push(redact(error.message)))
      page.on('console', (entry) => {
        if (entry.type() === 'error') state.current?.console_errors.push(redact(entry.text()))
      })
      page.on('requestfailed', (request) =>
        state.requestOwners.get(request)?.request_failures.push({
          path: redact(new URL(request.url()).pathname),
          method: request.method(),
          error: redact(request.failure()?.errorText)
        })
      )
      page.on('response', (response) => {
        const target = state.requestOwners.get(response.request())
        if (!target || !/^https?:/.test(response.url())) return
        const task = (async () => {
          const path = new URL(response.url()).pathname
          const row = {
            path,
            query: redact(new URL(response.url()).search),
            method: response.request().method(),
            status: response.status(),
            resource_type: response.request().resourceType()
          }
          const retryAfter = response.headers()['retry-after']
          if (retryAfter) row.retry_after = retryAfter
          if (path.startsWith('/api/v1') && path.includes('/auth/')) {
            const auth = await response.json().catch(() => undefined)
            row.code = auth?.code
            for (const key of ['access_token', 'refresh_token']) {
              if (auth?.data?.[key]) secrets.add(auth.data[key])
            }
          } else if (path.startsWith('/api/v1')) {
            const body = await response.json().catch(() => undefined)
            row.code = body?.code
            row.trace_id = body?.trace_id
            const items = Array.isArray(body?.data) ? body.data : body?.data?.items
            if (Array.isArray(items)) row.item_count = items.length
          }
          target.http.push(row)
        })()
        state.processing.add(task)
        task.finally(() => state.processing.delete(task))
      })
      try {
        await login(
          page,
          state,
          {
            tenant: env.SF_TENANT || 'full-test-a',
            username:
              host.host === 'admin' ? env.SF_ADMIN_USER || env.SF_USER : env.SF_PLATFORM_USER,
            password:
              host.host === 'admin'
                ? env.SF_ADMIN_PASSWORD || env.SF_PASSWORD
                : env.SF_PLATFORM_PASSWORD
          },
          join(lane, 'login')
        )
        const discovered = await discover(state, context)
        await save(join(lane, 'fixture-discovery.json'), {
          identity: state.identity,
          evidence: discovered.evidence,
          refs: Object.fromEntries(
            Object.entries(discovered.refs)
              .filter(([, value]) => value)
              .map(([kind, value]) => [
                kind,
                value.id ||
                  value.definition_id ||
                  value.workflow_id ||
                  value.application_id ||
                  value.session_id
              ])
          )
        })
        const routes = host.routes.map((route) => resolveRoute(host.host, route, discovered.refs))
        routes.push(
          { template: '/', path: '/', expected_path: `/${host.host}/home`, component: 'redirect' },
          { template: '/403', path: '/403', expected_path: '/403', component: 'ForbiddenPage' },
          {
            template: '/__route_smoke_unknown__',
            path: '/__route_smoke_unknown__',
            expected_path: host.host === 'admin' ? '/404' : '/__route_smoke_unknown__',
            component: 'NotFoundPage'
          }
        )
        const selected = options.only
          ? routes.filter((route) => options.only.split(',').includes(route.template))
          : routes
        for (let index = 0; index < selected.length; index++) {
          const route = selected[index],
            destination = join(lane, String(index + 1).padStart(2, '0'))
          let result
          if (route.status === 'MISSING_FIXTURE') {
            await mkdir(destination)
            result = route
            await save(join(destination, 'result.json'), result)
          } else result = await inspect(page, state, route, destination)
          outcomes.push({
            round,
            host: host.host,
            evidence: relative(root, destination).replaceAll('\\', '/'),
            ...result
          })
          console.log(
            `round=${round} host=${host.host} ${route.template} ${result.status} help=${result.help_status || '-'} http=${result.http?.length || 0}`
          )
        }
      } catch (error) {
        const result = { round, host: host.host, status: 'BLOCKED', reason: redact(error.message) }
        outcomes.push(result)
        await save(join(lane, 'lane-error.json'), result)
        if (state.current) {
          state.current.dom = JSON.parse(
            redact(JSON.stringify(await page.evaluate(visibleSnapshot).catch(() => ({}))))
          )
          await save(join(lane, 'interrupted-page.json'), state.current)
        }
        console.log(`round=${round} host=${host.host} BLOCKED ${redact(error.message)}`)
      } finally {
        await context.close()
        await host.server.close()
        servers.delete(host.server)
      }
    }
  }
  await save(join(root, 'services.json'), services)
  await save(join(root, 'summary.json'), outcomes)
  await save(join(root, 'counts.json'), {
    records: outcomes.length,
    smoke_pass: outcomes.filter((row) => row.status === 'SMOKE_PASS').length,
    fail: outcomes.filter((row) => row.status === 'FAIL').length,
    missing_fixture: outcomes.filter((row) => row.status === 'MISSING_FIXTURE').length,
    blocked: outcomes.filter((row) => row.status === 'BLOCKED').length,
    help_gaps: outcomes.filter((row) => row.help_status === 'GAP').length,
    business_acceptance: 'NOT_RUN',
    completed_at: now()
  })
  process.exitCode = outcomes.some((row) => ['FAIL', 'BLOCKED'].includes(row.status)) ? 1 : 0
} finally {
  await browser.close()
  await Promise.allSettled([...servers].map((server) => server.close()))
}
