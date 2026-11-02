/** Actual UI lifecycle acceptance. API preparation and readback are separately labelled. */
import { readFile, writeFile, mkdir, readdir } from 'node:fs/promises'
import { createHash, randomUUID } from 'node:crypto'
import { createRequire } from 'node:module'
import { dirname, resolve, relative, join } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
const repo = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
const requireApp = createRequire(join(repo, 'frontend-platform/package.json'))
const { chromium, expect } = requireApp('@playwright/test')
const { createServer, loadConfigFromFile } = await import(
  pathToFileURL(requireApp.resolve('vite')).href
)
const options = Object.fromEntries(
  process.argv.slice(2).reduce((a, v, i, s) => {
    if (v.startsWith('--')) a.push([v.slice(2), s[i + 1]])
    return a
  }, [])
)
const root = resolve(repo, options.root)
if (relative(join(repo, '.runtime-logs'), root).startsWith('..'))
  throw Error('Private evidence required')
await mkdir(root, { recursive: false })
const env = Object.fromEntries(
  (await readFile(resolve(options.env), 'utf8')).split(/\r?\n/).flatMap((x) => {
    const m = x.match(/^\s*([A-Z_0-9]+)=(.*)$/)
    return m ? [[m[1], m[2].trim().replace(/^(['"])(.*)\1$/, '$2')]] : []
  })
)
const secrets = new Set(
  Object.entries(env)
    .filter(([k]) => /PASSWORD|TOKEN|SECRET|KEY/.test(k))
    .map(([, v]) => v)
    .filter(Boolean)
)
const redact = (v) => {
  let t = String(v)
  for (const s of secrets) t = t.replaceAll(s, '[REDACTED]')
  return t.replace(/Bearer\s+[A-Za-z0-9._-]+/g, 'Bearer [REDACTED]')
}
const save = (file, value) => writeFile(file, JSON.stringify(value, null, 2), { flag: 'wx' })
const hash = (v) => createHash('sha256').update(v).digest('hex')
const now = () => new Date().toISOString()
async function inputBindings() {
  const records = []
  async function walk(dir) {
    for (const item of await readdir(dir, { withFileTypes: true })) {
      const path = join(dir, item.name)
      if (item.isDirectory()) await walk(path)
      else if (/\.(vue|ts|json|css|scss)$/.test(item.name))
        records.push({
          path: relative(repo, path).replaceAll('\\', '/'),
          sha256: hash(await readFile(path))
        })
    }
  }
  for (const host of ['admin', 'platform', 'shared']) {
    await walk(join(repo, 'frontend-' + host, 'src'))
    for (const file of ['package.json', 'vite.config.ts']) {
      const path = join(repo, 'frontend-' + host, file)
      const bytes = await readFile(path).catch(() => null)
      if (bytes)
        records.push({ path: relative(repo, path).replaceAll('\\', '/'), sha256: hash(bytes) })
    }
  }
  return records
}
await writeFile(join(root, 'runner.source.mjs'), await readFile(fileURLToPath(import.meta.url)), {
  flag: 'wx'
})
await save(join(root, 'frontend-inputs.json'), await inputBindings())
const servers = new Set(),
  outcomes = [],
  services = [],
  nextApiAt = { admin: 0, platform: 0 }
async function paceApi(host) {
  const slot = Math.max(Date.now(), nextApiAt[host])
  nextApiAt[host] = slot + 800
  if (slot > Date.now()) await new Promise((r) => setTimeout(r, slot - Date.now()))
}
// Reused source-grounded startVite/visibleSnapshot/login helpers appended below.

const assert = (v, message) => {
  if (!v) throw Error(message)
}
const field = (scope, label) => {
  const page = typeof scope.page === 'function' ? scope.page() : scope
  return scope
    .locator('.el-form-item')
    .filter({
      has: page.locator('.el-form-item__label').filter({ hasText: new RegExp('^' + label + '$') })
    })
    .first()
}
const input = (scope, label) => field(scope, label).locator('input,textarea').first()
async function editNumber(page, scope, label, value, state) {
  const target = input(scope, label)
  assert(
    (await target.getAttribute('disabled')) === null,
    'Native disabled numeric control ' + label
  )
  const aria = await target.getAttribute('aria-disabled')
  if (aria === 'true')
    state.current.actions.push({
      kind: 'ARIA_DISABLED_MISMATCH',
      label,
      native_disabled: false,
      aria_disabled: true,
      testing_path: 'ACTUAL_TAB_KEYBOARD'
    })
  let focused = false
  for (let i = 0; i < 40; i++) {
    if (await target.evaluate((el) => el === document.activeElement)) {
      focused = true
      break
    }
    await page.keyboard.press('Tab')
  }
  assert(focused, 'Actual Tab sequence cannot reach ' + label)
  await page.keyboard.press('ControlOrMeta+A')
  await page.keyboard.type(String(value))
  await page.keyboard.press('Tab')
  await expect(target).toHaveValue(String(value))
  state.current.actions.push({ kind: 'UI_KEYBOARD_NUMBER', label, value, at: now() })
}
async function editCode(page, label, value) {
  const c = page.locator('.cm-content[aria-label="' + label + '"]')
  await c.click()
  await c.press('ControlOrMeta+A')
  await c.press('Backspace')
  await c.pressSequentially(value, { delay: 0 })
}
async function settle(page) {
  await page.waitForLoadState('networkidle')
  await expect(page.locator('.el-skeleton:visible,.el-loading-mask:visible')).toHaveCount(0, {
    timeout: 30000
  })
}
async function navigate(page, state, path) {
  await page.goto(state.base + path, { waitUntil: 'domcontentloaded', timeout: 60000 })
  await settle(page)
  assert(new URL(page.url()).pathname === path.split('?')[0], 'Unexpected route ' + page.url())
}
async function select(page, scope, label, option) {
  await field(scope, label).locator('.el-select').click()
  await page
    .locator('.el-select-dropdown:visible .el-select-dropdown__item')
    .filter({ hasText: new RegExp('^' + option + '$') })
    .click()
  await page.keyboard.press('Escape')
}
function ownedWrite(state, method, path, body) {
  if (['GET', 'HEAD'].includes(method)) return true
  if (method === 'POST' && /\/auth\/(login|refresh|logout)$/.test(path)) return true
  const p = path.replace(/^\/api\/v1/, '')
  const own = (id) => state.owned.has(String(id))
  const named = (v) =>
    typeof v === 'string' &&
    (v.startsWith(state.prefix) || v.startsWith(state.prefix.replaceAll('-', '_')))
  if (/\/capabilities\/definitions$/.test(p) && method === 'POST')
    return named(body?.capability_key) && named(body?.name)
  if (/\/capabilities\/definitions\/versions(?:\/update)?$/.test(p))
    return own(body?.definition_id) && (!body.version_id || own(body.version_id))
  if (/\/capabilities\/skill-drafts$/.test(p))
    return method === 'POST' ? own(body?.definition_id) : method === 'PUT' && own(body?.draft_id)
  if (p === '/builder/workflows') return method === 'POST' && named(body?.workflow_id)
  if (p === '/builder/workflows/draft') return method === 'PUT' && own(body?.workflow_id)
  if (p === '/builder/workflows/draft/snapshot') return method === 'PUT' && own(body?.workflow_id)
  if (options.phase === 'critical') {
    if (
      [
        '/builder/workflows/draft/validate',
        '/builder/workflows/publish',
        '/builder/workflows/install'
      ].includes(p)
    )
      return method === 'POST' && own(body?.workflow_id)
    if (p === '/agents/publish') return method === 'POST' && own(body?.agent_id)
    if (p === '/admin/models')
      return method === 'POST' && named(body?.name) && body?.enabled === false
    if (p === '/admin/models/update')
      return method === 'PUT' && own(body?.id) && body?.payload?.enabled === false
    if (p === '/admin/models/publish') return method === 'POST' && own(body?.id)
    if (p === '/admin/system-agents')
      return method === 'POST' && named(body?.agent_key) && named(body?.name)
    if (p === '/admin/system-agents/update') return method === 'PUT' && own(body?.agent_id)
    if (
      [
        '/admin/system-agents/publish',
        '/admin/system-agents/disable',
        '/admin/system-agents/enable'
      ].includes(p)
    )
      return method === 'POST' && own(body?.agent_id)
  }
  if (p === '/agents') return method === 'POST' && named(body?.agent_key) && named(body?.name)
  if (p === '/agents/update') return method === 'PATCH' && own(body?.agent_id)
  if (p === '/knowledge/bases') return method === 'POST' && named(body?.name)
  if (p === '/knowledge/bases/update') return method === 'PATCH' && own(body?.kb_id)
  if (p === '/platform/applications') return method === 'POST' && named(body?.name)
  if (p === '/platform/applications/update') return method === 'POST' && own(body?.application_id)
  if (p === '/admin/agent-templates') return method === 'POST' && named(body?.name)
  if (p === '/admin/agent-templates/update') return method === 'PUT' && own(body?.template_id)
  if (/\/foundation\/access\/department$/.test(p))
    return method === 'POST' ? named(body?.code) : method === 'PUT' && own(body?.id)
  if (/\/foundation\/access\/member$/.test(p))
    return method === 'POST' ? named(body?.username) : method === 'PUT' && own(body?.id)
  if (/\/foundation\/access\/member-roles$/.test(p))
    return method === 'POST' && own(body?.member_id)
  if (/\/foundation\/access\/role$/.test(p))
    return method === 'POST' ? named(body?.code) : method === 'PUT' && own(body?.id)
  return false
}
async function api(state, method, path, body) {
  assert(ownedWrite(state, method, '/api/v1' + path, body), 'Refuse unowned API write ' + path)
  const rec = {
    kind: ['GET', 'HEAD'].includes(method) ? 'FORMAL_API_READBACK' : 'API_FIXTURE_PREPARATION',
    method,
    path,
    at: now()
  }
  const credential = /"(?:password|api_key)"/.test(JSON.stringify(body ?? {}))
  rec.request = credential ? 'PRIVATE_CREDENTIAL_BODY' : body
  const n = String(++state.apiSeq).padStart(3, '0')
  await save(join(state.lane, n + '-api-intent.json'), rec)
  await paceApi(state.host)
  const response = await fetch(state.origin + '/api/v1' + path, {
    method,
    headers: {
      'Content-Type': 'application/json',
      [state.host === 'admin' ? 'Authorization' : 'X-Platform-Token']: 'Bearer ' + state.token
    },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    signal: AbortSignal.timeout(30000)
  })
  const b = await response.json()
  const data = b.data
  await save(join(state.lane, n + '-api-receipt.json'), {
    ...rec,
    status: response.status,
    code: b.code,
    data,
    trace_id: b.trace_id,
    finished_at: now()
  })
  assert(response.ok && b.code === 'OK', `${method} ${path} HTTP${response.status} ${b.code}`)
  if (method !== 'GET')
    for (const id of [data?.id, data?.workflow_id, data?.application_id])
      if (id) state.owned.add(String(id))
  state.current?.readbacks.push({
    kind: rec.kind,
    method,
    path,
    status: response.status,
    code: b.code,
    evidence: n + '-api-receipt.json'
  })
  return data
}
async function submit(page, state, button, method, path, status = 200) {
  state.current.actions.push({
    kind: 'UI_CLICK_SUBMIT',
    button,
    method,
    path,
    expected_http_status: status,
    at: now()
  })
  const promise = page.waitForResponse(
    (r) => new URL(r.url()).pathname === '/api/v1' + path && r.request().method() === method,
    { timeout: 30000 }
  )
  await page.getByRole('button', { name: button, exact: true }).click()
  const r = await promise,
    b = await r.json()
  state.current.actions.at(-1).actual_http_status = r.status()
  state.current.actions.at(-1).actual_code = b.code
  state.current.actions.at(-1).response = JSON.parse(redact(JSON.stringify(b)))
  assert(r.status() === status, `${button} HTTP${r.status()} ${b.code}`)
  assert(status === 409 || b.code === 'OK', `${button} ${b.code}`)
  if (status < 300)
    for (const id of [b.data?.id, b.data?.workflow_id, b.data?.application_id])
      if (id) state.owned.add(String(id))
  await settle(page)
  return b.data
}
async function capture(page, state, name) {
  await page.screenshot({
    path: join(state.current.dir, name + '.png'),
    fullPage: true,
    mask: [page.locator('input[type=password]')]
  })
  state.current.captures.push(name + '.png')
}
async function refusal(page, state, button, text) {
  const before = state.current.http.filter((x) => !['GET', 'HEAD'].includes(x.method)).length
  state.current.actions.push({
    kind: 'UI_INVALID_SUBMIT',
    button,
    expected_visible_error: text,
    at: now()
  })
  await page.getByRole('button', { name: button, exact: true }).click()
  await expect(page.getByText(text, { exact: false }).first()).toBeVisible({ timeout: 10000 })
  await settle(page)
  assert(
    state.current.http.filter((x) => !['GET', 'HEAD'].includes(x.method)).length === before,
    'Invalid UI submitted a business write'
  )
  await capture(page, state, 'invalid-' + state.current.captures.length)
}
async function flow(context, state, key, fn) {
  if (options.only && !options.only.split(',').includes(key)) return
  const dir = join(state.lane, key)
  await mkdir(dir)
  const record = {
    round: state.round,
    host: state.host,
    key,
    status: 'RUNNING',
    started_at: now(),
    dir,
    actions: [],
    readbacks: [],
    captures: [],
    http: [],
    js_errors: [],
    blocked_writes: []
  }
  state.current = record
  const page = await context.newPage()
  page.on('pageerror', (e) => record.js_errors.push(redact(e.message)))
  try {
    await fn(page)
    await capture(page, state, 'final')
    assert(!record.blocked_writes.length, 'Blocked unowned business write')
    assert(!record.js_errors.length, 'Browser JS error')
    record.status = 'PASS'
  } catch (e) {
    record.status = 'FAIL'
    record.reason = redact(e.message)
    await capture(page, state, 'failure').catch(() => {})
  } finally {
    await Promise.all([...state.processing])
    record.finished_at = now()
    record.dom = await page.evaluate(visibleSnapshot).catch(() => ({}))
    record.dir = relative(root, dir).replaceAll('\\', '/')
    await save(join(dir, 'result.json'), JSON.parse(redact(JSON.stringify(record))))
    outcomes.push(record)
    console.log(
      `round=${state.round} host=${state.host} flow=${key} ${record.status} ${record.reason?.slice(0, 180) || ''}`
    )
    await page.close()
    state.current = null
  }
}
async function capabilities(context, s) {
  const prefix = s.host === 'admin' ? '/capabilities' : '/admin/capabilities'
  await flow(context, s, 'tool-draft', async (p) => {
    const d = await api(s, 'POST', prefix + '/definitions', {
      capability_type: 'TOOL',
      capability_key: s.prefix + 'tool',
      name: s.prefix + 'tool',
      description: 'Owned unexecuted draft'
    })
    await navigate(p, s, `/${s.host}/capabilities/${d.id}`)
    await p.getByRole('button', { name: '编辑工作草稿', exact: true }).click()
    await input(p, '版本标识').fill('1.0.0')
    await input(p, '名称').fill('')
    await refusal(p, s, '保存版本草稿', '请填写名称')
    await input(p, '名称').fill(s.prefix + 'tool-saved')
    await input(p, '工具名称').fill('ui_owned_tool')
    await input(p, '工具标题').fill('Owned UI tool')
    await editCode(p, '输入 JSON Schema', '{invalid')
    await refusal(p, s, '保存版本草稿', 'JSON 格式不正确，请检查引号、逗号和括号')
    await editCode(
      p,
      '输入 JSON Schema',
      '{"type":"object","properties":{"label":{"type":"string"}}}'
    )
    await submit(p, s, '保存版本草稿', 'POST', prefix + '/definitions/versions', 201)
    const versions = await api(s, 'GET', prefix + '/definitions/versions?definitionId=' + d.id)
    const v = (versions.items || versions).find((v) => v.version_key === '1.0.0')
    assert(
      v?.name === s.prefix + 'tool-saved' &&
        v.descriptor.input_schema.properties.label.type === 'string',
      'Tool persisted descriptor mismatch'
    )
    assert(v.status === 'DRAFT', 'Tool accidentally published')
    s.owned.add(String(v.id))
    await p.getByRole('button', { name: '编辑草稿', exact: true }).click()
    await input(p, '说明').fill('Second UI save ' + s.prefix)
    await submit(p, s, '保存版本草稿', 'PUT', prefix + '/definitions/versions/update')
    const after = await api(s, 'GET', prefix + '/definitions/versions?definitionId=' + d.id)
    assert(
      (after.items || after)[0].description === 'Second UI save ' + s.prefix,
      'Tool update readback mismatch'
    )
  })
  await flow(context, s, 'skill-files', async (p) => {
    const d = await api(s, 'POST', prefix + '/definitions', {
      capability_type: 'SKILL',
      capability_key: s.prefix + 'skill',
      name: s.prefix + 'skill',
      description: 'Owned unpublished files'
    })
    const draft = await api(s, 'POST', prefix + '/skill-drafts', { definition_id: d.id })
    await navigate(p, s, `/${s.host}/skills/${d.id}`)
    await p.getByLabel('新文件路径', { exact: true }).fill('../escape.md')
    await p.getByRole('button', { name: '新增', exact: true }).click()
    await expect(
      p.getByText('请输入不重复的相对文件路径，不能包含上级目录。', { exact: true })
    ).toBeVisible()
    await capture(p, s, 'invalid-path')
    await p.getByLabel('新文件路径', { exact: true }).fill('SKILL.md')
    await p.getByRole('button', { name: '新增', exact: true }).click()
    const text =
      '---\nname: ' +
      s.prefix +
      'skill\ndescription: Owned UI draft\n---\n\nOnly draft testing. No execution.\n'
    await editCode(p, '文件 SKILL.md', text)
    const versionLabel = s.host === 'admin' ? '版本号' : '版本标识'
    await input(p, versionLabel).fill('')
    await refusal(p, s, '保存草稿', s.host === 'admin' ? '请输入版本号' : '版本标识不能为空')
    await input(p, versionLabel).fill('1.0.0')
    try {
      await submit(p, s, '保存草稿', 'PUT', prefix + '/skill-drafts')
    } catch (error) {
      const unchanged = await api(s, 'GET', prefix + '/skill-drafts/detail?draftId=' + draft.id)
      s.current.failed_save_persistence = {
        revision_before: draft.revision,
        revision_after: unchanged.revision,
        files: unchanged.files
      }
      throw error
    }
    const after = await api(s, 'GET', prefix + '/skill-drafts/detail?draftId=' + draft.id)
    assert(
      after.files['SKILL.md'] === text &&
        after.version_key === '1.0.0' &&
        after.revision > draft.revision,
      'Skill persisted content/revision mismatch'
    )
    await p.reload()
    await settle(p)
    await expect(p.locator('.cm-content[aria-label="文件 SKILL.md"]')).toContainText(
      'Only draft testing.'
    )
    assert(!after.published_version_id, 'Skill accidentally published')
  })
}
async function adminFlows(context, s, model) {
  await flow(context, s, 'agent-config', async (p) => {
    const agent = await api(s, 'POST', '/agents', {
      agent_key: s.prefix + 'agent',
      name: s.prefix + 'agent',
      system_prompt: 'Owned harmless draft',
      model_config_id: model
    })
    await navigate(p, s, '/admin/agents/' + agent.id)
    await input(p, '名称').fill('')
    await refusal(p, s, '保存草稿', '请输入名称')
    await input(p, '名称').fill(s.prefix + 'agent-saved')
    await input(p, '系统提示词').fill('Owned UI system prompt ' + s.prefix)
    await input(p, '角色描述').fill('Owned UI config')
    await submit(p, s, '保存草稿', 'PATCH', '/agents/update')
    const a = await api(s, 'GET', '/agents/detail?agentId=' + agent.id)
    assert(
      a.name === s.prefix + 'agent-saved' &&
        a.system_prompt === 'Owned UI system prompt ' + s.prefix,
      'Agent config readback mismatch'
    )
    assert(a.published_revision == null, 'Agent accidentally published')
  })
  await flow(context, s, 'workflow-conflict', async (p) => {
    const id = s.prefix + 'workflow'
    await api(s, 'POST', '/builder/workflows', {
      workflow_id: id,
      definition: {
        version: '1.0',
        entry: 'start',
        nodes: [
          { id: 'start', type: 'start' },
          { id: 'end', type: 'end' }
        ],
        edges: [{ from: 'start', to: 'end' }],
        meta: { name: id, version: '1.0.0', description: 'Owned draft' }
      }
    })
    const stale = await context.newPage()
    try {
      await navigate(p, s, '/admin/workflows/' + id)
      await navigate(stale, s, '/admin/workflows/' + id)
      for (const page of [p, stale])
        await page.getByRole('tab', { name: '工作流信息', exact: true }).click()
      await input(p, '名称').fill(id + '-winner')
      await submit(p, s, '保存', 'PUT', '/builder/workflows/draft')
      await input(stale, '名称').fill(id + '-stale')
      await submit(stale, s, '保存', 'PUT', '/builder/workflows/draft', 409)
      await capture(stale, s, 'conflict')
      const kept = await api(s, 'GET', '/builder/workflows/draft?workflowId=' + id)
      assert(
        kept.definition.meta.display_name === id + '-winner' ||
          kept.definition.meta.name === id + '-winner',
        'Stale save overwrote winner'
      )
      await stale.reload()
      await settle(stale)
      await stale.getByRole('tab', { name: '工作流信息', exact: true }).click()
      await input(stale, '名称').fill(id + '-resolved')
      await submit(stale, s, '保存', 'PUT', '/builder/workflows/draft')
      const final = await api(s, 'GET', '/builder/workflows/draft?workflowId=' + id)
      assert(
        final.definition.meta.display_name === id + '-resolved' ||
          final.definition.meta.name === id + '-resolved',
        'Workflow conflict recovery mismatch'
      )
      assert(final.revision > kept.revision, 'Workflow revision not advanced')
    } finally {
      await stale.close()
    }
  })
  await flow(context, s, 'knowledge-config', async (p) => {
    const k = await api(s, 'POST', '/knowledge/bases', {
      name: s.prefix + 'kb',
      description: 'Owned empty KB'
    })
    await navigate(p, s, '/admin/knowledge')
    const row = p.locator('.el-table__body tr').filter({ hasText: s.prefix + 'kb' })
    await expect(row).toHaveCount(1)
    await row.getByRole('button', { name: '配置', exact: true }).click()
    const drawer = p.locator('.el-drawer:visible')
    await input(drawer, '名称').fill('')
    await refusal(p, s, '保存', '请输入名称')
    await input(drawer, '名称').fill(s.prefix + 'kb-saved')
    await input(drawer, '说明').fill('Only config no indexing ' + s.prefix)
    await editNumber(p, drawer, '分块大小', 640, s)
    await editNumber(p, drawer, '重叠长度', 40, s)
    await editNumber(p, drawer, '召回条数', 7, s)
    await submit(p, s, '保存', 'PATCH', '/knowledge/bases/update')
    const a = await api(s, 'GET', '/knowledge/bases/detail?kbId=' + k.id)
    assert(
      a.name === s.prefix + 'kb-saved' &&
        a.chunk_size === 640 &&
        a.chunk_overlap === 40 &&
        a.top_k === 7,
      'KB config readback mismatch'
    )
    assert((a.document_count || 0) === 0 && (a.chunk_count || 0) === 0, 'Empty KB acquired content')
  })
}
async function platformFlows(context, s, model) {
  await flow(context, s, 'application-policy', async (p) => {
    const a = await api(s, 'POST', '/platform/applications', {
      name: s.prefix + 'app',
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
    await navigate(p, s, '/platform/applications/' + a.application_id)
    await input(p, '应用名称').fill('')
    await refusal(p, s, '保存配置', '请输入应用名称')
    await input(p, '应用名称').fill(s.prefix + 'app-saved')
    await input(p, '机构数量上限').fill('3')
    await input(p, '成员上限').fill('9')
    await submit(p, s, '保存配置', 'POST', '/platform/applications/update')
    const read = await api(
      s,
      'GET',
      '/platform/applications/detail?applicationId=' + a.application_id
    )
    assert(
      read.name === s.prefix + 'app-saved' &&
        read.policy.max_tenants === 3 &&
        read.policy.quota_max_members === 9 &&
        !read.policy.allow_provision,
      'Application policy mismatch'
    )
    assert(read.status === 'active', 'Application unexpected transition')
  })
  await flow(context, s, 'agent-template', async (p) => {
    const a = await api(s, 'POST', '/admin/agent-templates', {
      name: s.prefix + 'template',
      description: 'Owned UI draft',
      category: 'test',
      tags: [],
      price_type: 'free',
      price_cents: 0,
      definition: {
        system_prompt: 'Owned harmless draft',
        role_description: '',
        model_config_id: model,
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
    await navigate(p, s, '/platform/agent-templates/' + a.id)
    await input(p, '名称').fill('')
    await refusal(p, s, '保存模板', '请输入名称')
    await input(p, '名称').fill(s.prefix + 'template-saved')
    await input(p, '系统提示词').fill('Owned template UI prompt ' + s.prefix)
    await input(p, '分类').fill('ui-test')
    await submit(p, s, '保存模板', 'PUT', '/admin/agent-templates/update')
    const after = await api(s, 'GET', '/admin/agent-templates/detail?templateId=' + a.id)
    assert(
      after.name === s.prefix + 'template-saved' &&
        after.category === 'ui-test' &&
        after.definition.system_prompt === 'Owned template UI prompt ' + s.prefix,
      'Template draft mismatch'
    )
    assert(after.status === 'draft', 'Template unexpectedly published')
  })
}
async function accessFlow(context, s) {
  await flow(context, s, 'organization-member', async (p) => {
    const path = s.host === 'admin' ? '/tenant/foundation/access' : '/admin/foundation/access'
    const permissionCodes = s.identity.permissions
      .filter((code) => code.endsWith('.read'))
      .slice(0, 2)
    assert(permissionCodes.length === 2, 'Two actual read permissions unavailable')
    const selected = []
    for (let i = 0; i < 2; i++)
      selected.push(
        await api(s, 'POST', path + '/role', {
          code: s.prefix.replaceAll('-', '_') + 'role_' + i,
          name: s.prefix + 'role-' + i,
          data_scope: 'self',
          permissions: [permissionCodes[i]],
          department_ids: []
        })
      )
    await navigate(p, s, `/${s.host}/foundation/access`)
    await p.getByRole('tab', { name: '部门', exact: true }).click()
    await p.getByRole('button', { name: '新增部门', exact: true }).click()
    const dialog = p.locator('.el-dialog:visible')
    await input(dialog, '部门编码').fill(s.prefix.replaceAll('-', '_') + 'dept')
    await input(dialog, '部门名称').fill(s.prefix + 'dept')
    const dept = await submit(p, s, '保存', 'POST', path + '/department')
    assert(dept.id, 'Department actual identity missing')
    await p.getByRole('tab', { name: '成员', exact: true }).click()
    await p.getByRole('button', { name: '新增成员', exact: true }).click()
    await input(dialog, '用户名').fill(s.prefix + 'member')
    await input(dialog, '显示名称').fill(s.prefix + 'member')
    await dialog.getByText('设置初始密码', { exact: true }).click()
    await input(dialog, '初始密码').fill(env.SF_ADMIN_PASSWORD || env.SF_PASSWORD)
    await select(p, dialog, '部门', s.prefix + 'dept')
    for (const r of selected) await select(p, dialog, '角色', r.name)
    const member = await submit(p, s, '保存', 'POST', path + '/member')
    assert(member.id, 'Member actual identity missing')
    await p.getByPlaceholder('用户名或显示名称', { exact: true }).fill(s.prefix + 'member')
    await p.getByRole('button', { name: '查询', exact: true }).click()
    await settle(p)
    const row = p.locator('.el-table__body:visible tr')
    await expect(row).toHaveCount(1)
    await expect(row).toContainText(s.prefix + 'dept')
    const read = await api(
      s,
      'GET',
      path + '/members?keyword=' + encodeURIComponent(s.prefix + 'member') + '&page_size=100'
    )
    assert(
      read.items.length === 1 &&
        read.items[0].department_id === dept.id &&
        selected.every((r) => read.items[0].role_ids.includes(r.id)),
      'Multi-role member/department persisted mismatch'
    )
    await row.getByRole('button', { name: '编辑资料', exact: true }).click()
    await input(dialog, '显示名称').fill(s.prefix + 'member-edited')
    await submit(p, s, '保存', 'PUT', path + '/member')
    const after = await api(
      s,
      'GET',
      path + '/members?keyword=' + encodeURIComponent(s.prefix + 'member') + '&page_size=100'
    )
    assert(after.items[0].display_name === s.prefix + 'member-edited', 'Member UI edit mismatch')
  })
}
const browser = await chromium.launch({ headless: true })
try {
  const provision = JSON.parse(
    await readFile(
      resolve('.runtime-logs/full-project-test-20261012/environment/provision.json'),
      'utf8'
    )
  )
  const model = provision.models.llm.id
  for (let round = 1; round <= Number(options.rounds || 3); round++) {
    const environment = JSON.parse(await readFile(resolve(options.environment), 'utf8'))
    for (const host of (options.hosts || 'admin,platform').split(',')) {
      const origin =
        environment.services[host === 'admin' ? 'institution-service' : 'platform-service'].base_url
      assert(new URL(origin).hostname === '127.0.0.1', 'Owned loopback required')
      const vite = await startVite(host, origin)
      const lane = join(root, 'round-' + round, host)
      await mkdir(lane, { recursive: true })
      const s = {
        ...vite,
        round,
        lane,
        prefix: 'ui-life-' + host + '-' + round + '-' + randomUUID().slice(0, 8) + '-',
        owned: new Set(),
        apiSeq: 0,
        current: null,
        processing: new Set()
      }
      services.push({
        host,
        round,
        base: vite.base,
        origin,
        api_pacing_ms: 800,
        origin_adapter: host === 'admin' ? 'http://localhost:3000' : null,
        cors_acceptance: 'NOT_RUN'
      })
      const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
      await context.route('**/api/v1/**', async (route) => {
        const request = route.request(),
          path = new URL(request.url()).pathname,
          method = request.method()
        let body
        try {
          body = request.postDataJSON()
        } catch {}
        if (!ownedWrite(s, method, path, body)) {
          s.current?.blocked_writes.push({ path, method })
          await route.abort('blockedbyclient')
        } else {
          await paceApi(host)
          await route.continue()
        }
      })
      context.on('response', (response) => {
        const rec = s.current
        if (!rec || !new URL(response.url()).pathname.startsWith('/api/v1')) return
        const task = (async () => {
          const b = await response.json().catch(() => ({}))
          for (const k of ['access_token', 'refresh_token']) if (b.data?.[k]) secrets.add(b.data[k])
          rec.http.push({
            path: new URL(response.url()).pathname,
            method: response.request().method(),
            status: response.status(),
            code: b.code,
            trace_id: b.trace_id,
            if_match: response.request().headers()['if-match'],
            error_envelope: b.code !== 'OK' ? JSON.parse(redact(JSON.stringify(b))) : undefined,
            request_body: ['GET', 'HEAD'].includes(response.request().method())
              ? undefined
              : /"(?:password|api_key)"/.test(response.request().postData() || '')
                ? 'PRIVATE_CREDENTIAL_BODY'
                : JSON.parse(response.request().postData() || 'null')
          })
        })()
        s.processing.add(task)
        task.finally(() => s.processing.delete(task))
      })
      try {
        const page = await context.newPage()
        await login(
          page,
          s,
          {
            tenant: env.SF_TENANT || 'full-test-a',
            username: host === 'admin' ? env.SF_ADMIN_USER || env.SF_USER : env.SF_PLATFORM_USER,
            password:
              host === 'admin' ? env.SF_ADMIN_PASSWORD || env.SF_PASSWORD : env.SF_PLATFORM_PASSWORD
          },
          join(lane, 'login')
        )
        await page.close()
        s.current = null
        if (options.phase === 'critical') {
          const { runCritical } = await import('./critical_ui_flows.mjs')
          await writeFile(
            join(lane, 'critical-flows.source.mjs'),
            await readFile(new URL('./critical_ui_flows.mjs', import.meta.url)),
            { flag: 'wx' }
          )
          await runCritical(context, s, model, {
            api,
            flow,
            navigate,
            settle,
            input,
            field,
            select,
            editNumber,
            capture,
            refusal,
            assert,
            expect,
            redact,
            secrets,
            now,
            fixturePassword: env.SF_ADMIN_PASSWORD || env.SF_PASSWORD
          })
        } else {
          await capabilities(context, s)
          if (host === 'admin') await adminFlows(context, s, model)
          else await platformFlows(context, s, model)
          await accessFlow(context, s)
        }
        await save(join(lane, 'owned-resources.json'), {
          prefix: s.prefix,
          identity: s.identity,
          ids: [...s.owned],
          cleanup:
            'Owned unpublished fixture resources retained for evidence and may be removed/disabled after the full batch closes.',
          supplier_calls: 0,
          runtime_runs: 0
        })
      } catch (e) {
        const rec = { round, host, status: 'BLOCKED', reason: redact(e.message) }
        outcomes.push(rec)
        await save(join(lane, 'lane-error.json'), rec)
        console.log(`round=${round} host=${host} BLOCKED ${rec.reason}`)
      } finally {
        await context.close()
        await vite.server.close()
        servers.delete(vite.server)
      }
    }
  }
  await save(join(root, 'summary.json'), outcomes)
  await save(join(root, 'services.json'), services)
  process.exitCode = outcomes.some((r) => r.status !== 'PASS') ? 1 : 0
} finally {
  await browser.close()
  await Promise.all([...servers].map((x) => x.close()))
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
