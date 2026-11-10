import assert from 'node:assert/strict'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'
import { randomUUID } from 'node:crypto'
import process from 'node:process'
import { createServer } from 'vite'

const f = JSON.parse(process.env.TEMPLATE_BUDGET_FIXTURE)
assert.ok(['baseline', 'candidate'].includes(f.mode))
const { chromium, expect } = createRequire(
  new URL('../../frontend-admin/package.json', import.meta.url)
)('@playwright/test')
const vite = await createServer({
  root: fileURLToPath(new URL('../', import.meta.url)),
  server: {
    host: '127.0.0.1',
    port: 0,
    proxy: {
      '/api': {
        target: f.platform,
        changeOrigin: true,
        configure(server) {
          server.on('proxyReq', (request) => request.removeHeader('Origin'))
        }
      }
    }
  }
})
let browser,
  page,
  opened = false
const calls = [],
  errors = []
async function api(platform, method, path, body, key) {
  const response = await fetch((platform ? f.platform : f.institution) + path, {
    method,
    headers: {
      'Content-Type': 'application/json',
      [platform ? 'X-Platform-Token' : 'Authorization']:
        'Bearer ' + (platform ? f.platformToken : f.institutionToken),
      ...(key ? { 'Idempotency-Key': key } : {})
    },
    ...(body ? { body: JSON.stringify(body) } : {}),
    signal: AbortSignal.timeout(20000)
  })
  const text = await response.text()
  const result = {
    method,
    path,
    request: body,
    status: response.status,
    body: text ? JSON.parse(text) : null
  }
  calls.push(result)
  return result
}
async function saveVerify(value) {
  const input = page.locator('.el-form-item').filter({ hasText: '累计总 Tokens' }).locator('input')
  await input.fill(String(value))
  await input.press('Tab')
  const saved = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/platform/agent-definitions/update' &&
      response.request().method() === 'PUT'
  )
  const preview = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/platform/agent-definitions/preview' &&
      response.request().method() === 'POST'
  )
  await page.getByRole('button', { name: '保存并验证', exact: true }).click()
  const write = await saved,
    checked = await preview
  const record = {
    saved: {
      status: write.status(),
      request: write.request().postDataJSON(),
      body: await write.json()
    },
    preview: {
      status: checked.status(),
      request: checked.request().postDataJSON(),
      body: await checked.json()
    }
  }
  await writeFile(join(f.evidence, `budget-${value}.json`), JSON.stringify(record, null, 2))
  assert.equal(write.status(), 200)
  assert.equal(checked.status(), 200)
  assert.equal(record.saved.request.payload.definition.execution_config.max_total_tokens, value)
  return record
}
async function publish() {
  const response = page.waitForResponse(
    (item) =>
      new URL(item.url()).pathname === '/api/v1/platform/agent-definitions/publish' &&
      item.request().method() === 'POST'
  )
  await page.getByRole('button', { name: '发布', exact: true }).click()
  await page.getByRole('dialog').getByRole('button', { name: '发布定义', exact: true }).click()
  const actual = await response
  const result = {
    status: actual.status(),
    request: actual.request().postDataJSON(),
    body: await actual.json()
  }
  calls.push({ method: 'POST', path: '/api/v1/platform/agent-definitions/publish', ...result })
  assert.equal(actual.status(), 200)
  await expect(page.getByRole('button', { name: '下架', exact: true })).toBeVisible()
  return result
}
try {
  await vite.listen()
  browser = await chromium.launch({ headless: true })
  const context = await browser.newContext({ viewport: { width: 1440, height: 1100 } })
  page = await context.newPage()
  page.setDefaultTimeout(20000)
  page.on('pageerror', (error) => errors.push(error.message))
  await page.goto(
    `http://127.0.0.1:${vite.httpServer.address().port}/login?redirect=${encodeURIComponent('/platform/agent-templates/' + f.definition)}`
  )
  await page.getByLabel('账号', { exact: true }).fill(f.username)
  await page.getByLabel('密码', { exact: true }).fill(f.password)
  await page.getByRole('button', { name: '登录运营台', exact: true }).click()
  await expect(page).toHaveURL(new RegExp(`/platform/agent-templates/${f.definition}$`))
  await page.getByRole('button', { name: /^执行预算/ }).click()
  opened = true
  const invalid = await saveVerify(2048)
  const revision = invalid.saved.body.data.draft_revision
  const before = await api(
    true,
    'GET',
    `/api/v1/platform/agent-definitions/detail?definition_id=${f.definition}`
  )
  assert.equal(before.status, 200)
  assert.equal(before.body.data.current_published_revision, null)
  if (f.mode === 'baseline') {
    assert.equal(invalid.preview.body.data.validation.valid, true)
    await expect(page.getByRole('button', { name: '发布', exact: true })).toBeEnabled()
    const published = await publish()
    const rejectedId = randomUUID()
    const refused = await api(false, 'POST', '/api/v1/agents/install-template', {
      template_id: f.definition,
      request_id: rejectedId,
      resource_bindings: [],
      delegation_bindings: []
    })
    await writeFile(
      join(f.evidence, 'invalid-published-install.json'),
      JSON.stringify({ published, refused }, null, 2)
    )
    assert.equal(refused.status, 422)
    assert.ok(JSON.stringify(refused.body).includes('agent_budget_cannot_fit_text_closing'))
    const receipt = await api(
      false,
      'GET',
      '/api/v1/agents/install-template/result?request_id=' + rejectedId
    )
    assert.equal(receipt.status, 204)
  } else {
    assert.equal(invalid.preview.body.data.validation.valid, false)
    assert.ok(
      invalid.preview.body.data.validation.issues.some(
        (issue) => issue.path === 'definition.execution_config' && issue.code === 'budget_invalid'
      )
    )
    await expect(
      page.getByRole('alert').filter({ hasText: 'definition.execution_config' })
    ).toBeVisible()
    await expect(page.getByRole('button', { name: '发布', exact: true })).toBeDisabled()
    const refused = await api(
      true,
      'POST',
      '/api/v1/platform/agent-definitions/publish',
      { definition_id: f.definition, expected_revision: revision },
      randomUUID()
    )
    assert.equal(refused.status, 422)
    assert.ok(JSON.stringify(refused.body).includes('agent_budget_cannot_fit_text_closing'))
    const after = await api(
      true,
      'GET',
      `/api/v1/platform/agent-definitions/detail?definition_id=${f.definition}`
    )
    const history = await api(
      true,
      'GET',
      `/api/v1/platform/agent-definitions/history?definition_id=${f.definition}`
    )
    assert.equal(after.status, 200)
    assert.equal(after.body.data.current_published_revision, null)
    assert.equal(after.body.data.draft_revision, revision)
    assert.equal(history.status, 200)
    assert.deepEqual(history.body.data, [])
    await writeFile(
      join(f.evidence, 'invalid-publish-rejected.json'),
      JSON.stringify({ before, refused, after, history }, null, 2)
    )
  }
  await page.screenshot({ path: join(f.evidence, 'invalid-budget.png'), fullPage: true })
  await writeFile(join(f.evidence, 'invalid-budget.html'), await page.locator('main').innerHTML())
  const valid = await saveVerify(20000)
  assert.equal(valid.preview.body.data.validation.valid, true)
  await expect(page.getByRole('button', { name: '发布', exact: true })).toBeEnabled()
  const published = await publish()
  const installation = await api(false, 'POST', '/api/v1/agents/install-template', {
    template_id: f.definition,
    request_id: randomUUID(),
    resource_bindings: [],
    delegation_bindings: []
  })
  assert.equal(installation.status, 200)
  const agent = String(installation.body.data.agent.id)
  assert.match(agent, /^[1-9]\d*$/)
  const detail = await api(
    true,
    'GET',
    `/api/v1/platform/agent-definitions/detail?definition_id=${f.definition}`
  )
  assert.equal(detail.body.data.definition.execution_config.max_total_tokens, 20000)
  assert.deepEqual(errors, [])
  await writeFile(
    join(f.evidence, 'result.json'),
    JSON.stringify(
      { mode: f.mode, agent, definition: f.definition, published, installation, detail },
      null,
      2
    )
  )
} finally {
  try {
    await writeFile(
      join(f.evidence, 'browser-calls.json'),
      JSON.stringify({ calls, errors }, null, 2)
    )
    if (page && opened) {
      await writeFile(join(f.evidence, 'page.html'), await page.locator('main').innerHTML())
      await page.screenshot({ path: join(f.evidence, 'final.png'), fullPage: true })
    }
  } finally {
    try {
      if (browser) await browser.close()
    } finally {
      await vite.close()
    }
  }
}
