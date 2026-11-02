/** 独立环境的真实 Skill 工作台验收；不拦截 API，不执行包内脚本，不保存登录凭据。 */
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { readFile, mkdir, writeFile } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const require = createRequire(path.join(root, 'frontend-admin/package.json'))
const { chromium, expect } = require('@playwright/test')
const { createServer } = await import(pathToFileURL(require.resolve('vite')).href)
const configPath = process.argv[2]
assert(configPath, 'Pass a private JSON config path; never pass credentials as arguments')
const config = JSON.parse(await readFile(configPath, 'utf8'))
assert.equal(config.isolated, true, 'Only an explicitly isolated disposable environment is allowed')
const output = path.resolve(config.evidenceDirectory)
await mkdir(output, { recursive: true })
const zip = path.resolve(config.zipPath)
assert((await readFile(zip)).length > 0, 'A real packaged ZIP is required')
const runKey = config.runKey ?? `p0-${Date.now()}`
assert(/^[a-z0-9-]+$/.test(runKey))
const previousReport = config.resume
  ? JSON.parse(await readFile(path.join(output, 'skill-workbench-p0.json'), 'utf8').catch(() => '{}')) : {}
const report = { mode: 'REAL_API', startedAt: new Date().toISOString(), cases: [] }
const browser = await chromium.launch({ headless: true })
const servers = []
let currentPage
let phase = 'configuration'

async function body(response) {
  const ok = typeof response.ok === 'function' ? response.ok() : response.ok
  const url = typeof response.url === 'function' ? response.url() : response.url
  const status = typeof response.status === 'function' ? response.status() : response.status
  if (status === 204) return undefined
  const value = await response.json()
  assert(ok, `Real API rejected ${new URL(url).pathname}: HTTP ${status}; code=${typeof value.code === 'string' ? value.code : 'unknown'}`)
  return Object.hasOwn(value, 'data') ? value.data : value
}
function responseFor(page, suffix, method = 'POST') {
  return page.waitForResponse((response) => new URL(response.url()).pathname.endsWith(suffix)
    && response.request().method() === method)
}
async function screenshot(page, label) {
  await page.screenshot({ path: path.join(output, `${label}.png`), fullPage: true })
}

try {
  if (config.harnessFile) {
    phase = 'authenticate-isolated-harness'
    const fixture = JSON.parse(await readFile(config.harnessFile, 'utf8'))
    assert.equal(fixture.status, 'AWAITING_BROWSER_ACCEPTANCE')
    for (const kind of ['admin', 'platform']) {
      const backendUrl = kind === 'admin' ? fixture.institution_backend : fixture.platform_backend
      const credentials = kind === 'admin' ? fixture.headquarters : fixture.platform_admin
      const endpoint = new URL(backendUrl)
      assert(['127.0.0.1', 'localhost'].includes(endpoint.hostname))
      assert(!['8080', '8100', '8000'].includes(endpoint.port))
      const response = await fetch(`${endpoint.origin}/api/v1/${kind === 'platform' ? 'platform/' : ''}auth/login`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username: credentials.username, password: credentials.password,
          ...(kind === 'admin' ? { tenant_slug: credentials.tenant_slug } : {}) })
      })
      const tokens = await body(response)
      assert(tokens.access_token, 'Real isolated login must return an access token')
      config[kind] = { backendUrl: endpoint.origin, accessToken: tokens.access_token }
    }
  }
  for (const kind of ['admin', 'platform']) {
    const target = config[kind]
    assert(target?.backendUrl && target?.accessToken, `${kind} endpoint and token required in private config`)
    const backend = new URL(target.backendUrl)
    assert(['127.0.0.1', 'localhost'].includes(backend.hostname), 'Only isolated loopback services are accepted')
    assert(!['8080', '8100', '8000'].includes(backend.port), 'Shared/default service ports are forbidden')
    process.env.E2E_BACKEND_URL = backend.origin
    phase = `${kind}:start-owned-vite`
    const appRoot = path.join(root, `frontend-${kind}`)
    const server = await createServer({ root: appRoot, configFile: path.join(appRoot, 'vite.config.ts'),
      logLevel: 'error', server: { host: '127.0.0.1', port: 0, strictPort: true, open: false,
        // 浏览器向自身Vite同源发请求；专属反向代理转发时将Origin与目标Host对齐，
        // 不要求隔离后端放宽为任意动态端口，也不改变认证或权限校验。
        proxy: { '/api': { target: backend.origin, changeOrigin: true,
          configure(proxy) { proxy.on('proxyReq', request => request.setHeader('Origin', backend.origin)) } } }
      } })
    servers.push(server)
    await server.listen()
    const address = server.httpServer.address()
    assert(address && typeof address !== 'string')
    const origin = `http://127.0.0.1:${address.port}`
    const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
    const tokenKey = kind === 'admin' ? 'skillforge.access_token' : 'skillforge.platform.access_token'
    await context.addInitScript(({ origin, tokenKey, token }) => {
      if (location.origin !== origin) return
      localStorage.setItem(tokenKey, token)
      localStorage.setItem(`${tokenKey}.session`, 'isolated-skill-browser')
    }, { origin, tokenKey, token: target.accessToken })
    const page = await context.newPage()
    currentPage = page
    const errors = [], requests = [...(previousReport.cases?.find(item => item.kind === kind)?.requests ?? [])]
    const result = { kind, status: 'RUNNING', requests, errors }
    report.cases.push(result)
    page.on('pageerror', () => errors.push('browser-runtime-error'))
    page.on('response', (response) => {
      const url = new URL(response.url())
      if (url.pathname.startsWith('/api/')) requests.push({ path: url.pathname,
        method: response.request().method(), status: response.status() })
    })
    phase = `${kind}:zero-directory`
    const listed = responseFor(page, '/definitions', 'GET')
    await page.goto(`${origin}/${kind}/skills`)
    const definitions = await body(await listed)
    const existing = definitions.find(item => (item.capability_key ?? item.capabilityKey) === `${runKey}-${kind}`)
    if (!existing) {
      await expect(page.getByText(/^尚无技能/)).toBeVisible({ timeout: 60_000 })
      await expect(page.locator('.el-table__body tbody tr')).toHaveCount(0)
      await screenshot(page, `${kind}-zero-directory`)
      // 实际空目录也检查窄窗口；不把此截图当成功发布证明。
      await page.setViewportSize({ width: 390, height: 844 })
      assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), 'Empty page overflows narrow viewport')
      await screenshot(page, `${kind}-zero-directory-narrow`)
      await page.setViewportSize({ width: 1440, height: 1000 })
    } else assert(config.resume, 'Existing authored definition requires explicit resume; never treat it as a preset')
    const alreadyPublished = existing?.default_version_id ?? existing?.defaultVersionId
    if (alreadyPublished) {
      phase = `${kind}:resume-read-back`
      const row = page.getByRole('row').filter({ hasText: `${runKey}-${kind}` })
      await expect(row).toHaveCount(1)
      if (kind === 'admin') await expect(row).toContainText('已发布')
      await screenshot(page, `${kind}-published-list`)
      Object.assign(result, { status: 'PASSED', zeroDirectory: true, createViaUi: true,
        importViaUi: true, validationValid: true, publicationViaUi: true, readBack: true,
        definitionId: String(existing.id), versionId: String(alreadyPublished), resumedPublication: true })
      await context.close()
      currentPage = undefined
      await server.close()
      continue
    }
    phase = `${kind}:create-definition`
    let activeDraftRead = kind === 'admin' || existing ? responseFor(page, '/skill-drafts', 'GET').then(body) : undefined
    let createResponse
    if (existing) {
      await page.getByRole('row').filter({ hasText: `${runKey}-${kind}` }).getByRole('link').first().click()
    } else if (kind === 'admin') {
      await page.getByRole('button', { name: '新建技能', exact: true }).last().click()
      const drawer = page.getByRole('dialog', { name: '新建技能' })
      await drawer.getByLabel('技能标识', { exact: true }).fill(`${runKey}-${kind}`)
      await drawer.getByLabel('名称', { exact: true }).fill(`Skill浏览器验收-${kind}`)
      createResponse = responseFor(page, '/definitions')
      await drawer.getByRole('button', { name: '保存', exact: true }).click()
    } else {
      await page.getByRole('link', { name: '前往创建技能' }).click()
      await page.getByRole('button', { name: '新增能力', exact: true }).click()
      const drawer = page.getByRole('dialog')
      await drawer.getByLabel('能力名称', { exact: true }).fill(`Skill浏览器验收-${kind}`)
      await drawer.getByLabel('能力标识', { exact: true }).fill(`${runKey}-${kind}`)
      createResponse = responseFor(page, '/definitions')
      await drawer.getByRole('button', { name: '创建', exact: true }).click()
    }
    const definition = existing ?? await body(await createResponse)
    assert(definition.id, 'Real definition creation must return identity')
    const definitionId = String(definition.id)
    if (kind === 'platform' && !existing) {
      activeDraftRead = responseFor(page, '/skill-drafts', 'GET').then(body)
      await page.getByRole('link', { name: '打开 Skill 工作台' }).click()
    }
    await expect(page).toHaveURL(new RegExp(`/${kind}/skills/${definitionId}`))
    phase = `${kind}:create-draft`
    const activeDraft = await activeDraftRead
    if (!activeDraft) {
      await expect(page.getByRole('button', { name: '创建草稿', exact: true })).toBeEnabled()
      await page.getByRole('button', { name: '创建草稿', exact: true }).click()
    }
    await expect(page.getByRole('button', { name: '保存草稿', exact: true })).toBeEnabled()
    phase = `${kind}:import-zip`
    if (kind === 'admin') await page.locator('.el-collapse-item__header').filter({ hasText: '历史版本与技能包导入' }).click()
    const imported = responseFor(page, '/skill-drafts/import-zip')
    await page.locator('input[type=file][accept=".zip"]').setInputFiles(zip)
    const draft = await body(await imported)
    Object.assign(result, { definitionId, draftId: String(draft.id), resumed: Boolean(existing) })
    assert(draft.id && Object.keys(draft.files ?? {}).includes('SKILL.md'), 'Imported ZIP must contain actual SKILL.md')
    await screenshot(page, `${kind}-imported`)
    phase = `${kind}:set-publication-version`
    if (kind === 'admin') await page.getByRole('tab', { name: '发布设置', exact: true }).click()
    await page.getByLabel(kind === 'admin' ? '版本号' : '版本标识', { exact: true }).fill(config.versionKey ?? 'browser-p0-v1')
    const saved = responseFor(page, '/skill-drafts', 'PUT')
    await page.getByRole('button', { name: '保存草稿', exact: true }).click()
    await body(await saved)
    phase = `${kind}:validate`
    const validated = responseFor(page, '/skill-drafts/validate')
    await page.getByRole('button', { name: '校验', exact: true }).click()
    const validation = await body(await validated)
    assert.equal(validation.valid, true, 'Real package validator rejected imported ZIP')
    phase = `${kind}:publish`
    await page.getByRole('button', { name: '发布', exact: true }).click()
    const confirmation = page.locator('.el-message-box')
    await expect(confirmation).toBeVisible()
    const published = responseFor(page, '/skill-drafts/publish')
    await confirmation.getByRole('button', { name: kind === 'admin' ? '发布技能' : '发布 Skill', exact: true }).click()
    const publication = await body(await published)
    result.versionId = String(publication.version_id)
    assert(publication.version_id, 'Successful real publication must return a version identity')
    phase = `${kind}:read-back`
    await page.goto(`${origin}/${kind}/skills`)
    const row = page.getByRole('row').filter({ hasText: `${runKey}-${kind}` })
    await expect(row).toHaveCount(1)
    if (kind === 'admin') await expect(row).toContainText('已发布')
    else await expect(row).not.toContainText('未设置')
    await screenshot(page, `${kind}-published-list`)
    assert.deepEqual(errors, [], 'Unexpected browser runtime error')
    Object.assign(result, { status: 'PASSED', zeroDirectory: true, createViaUi: true,
      importViaUi: true, validationValid: true, publicationViaUi: true, readBack: true,
      definitionId, versionId: String(publication.version_id) })
    await context.close()
    currentPage = undefined
    await server.close()
  }
  report.status = 'PASSED'
} catch (error) {
  report.status = 'FAILED'
  let diagnostic = error instanceof Error ? error.message : 'browser_lifecycle_failed'
  for (const kind of ['admin', 'platform']) {
    const token = config[kind]?.accessToken
    if (token) diagnostic = diagnostic.split(token).join('[REDACTED]')
  }
  report.failure = { phase, reason: diagnostic.slice(0, 4000) }
  if (currentPage) await screenshot(currentPage, 'failure').catch(() => {})
  process.exitCode = 1
} finally {
  await browser.close()
  for (const server of servers) await server.close().catch(() => {})
  report.finishedAt = new Date().toISOString()
  await writeFile(path.join(output, 'skill-workbench-p0.json'), `${JSON.stringify(report, null, 2)}\n`)
}
console.log(JSON.stringify({ status: report.status, phase, completed: report.cases.filter(item => item.status === 'PASSED').length, evidenceDirectory: output }))
