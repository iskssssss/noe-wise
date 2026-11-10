import assert from 'node:assert/strict'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'
import process from 'node:process'
import { randomUUID } from 'node:crypto'
import { createServer } from 'vite'

const f = JSON.parse(process.env.TEAM_DISABLE_FIXTURE)
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
        target: f.backend,
        changeOrigin: true,
        configure(server) {
          server.on('proxyReq', (request) => request.setHeader('Origin', 'http://localhost:3200'))
        }
      }
    }
  }
})
let browser, page
const requests = [],
  responses = [],
  errors = [],
  reads = [],
  network = []
let first, second, disabled, restored, rejected, session, disabledWrite
const draft = '团队恢复发布后，请继续处理这条保留的草稿。'
async function read(path) {
  const response = await fetch(f.backend + path, {
    headers: { 'X-Channel-Token': f.token, 'X-Chat-User-Token': f.identity },
    signal: AbortSignal.timeout(10000)
  })
  const body = await response.json()
  reads.push({ path, status: response.status, body })
  return { status: response.status, body }
}
async function completed(run) {
  let state
  await expect
    .poll(
      async () => {
        state = await read(
          `/api/v1/chat/runs/status?session_id=${encodeURIComponent(session)}&run_id=${encodeURIComponent(run)}`
        )
        assert.equal(state.status, 200)
        return state.body.data.status
      },
      { timeout: 90000, intervals: [500] }
    )
    .toMatch(/^(completed|failed|cancelled)$/)
  assert.equal(state.body.data.status, 'completed')
}
async function openTeams() {
  const navigation = page.getByRole('button', { name: '打开会话导航', exact: true })
  if (await navigation.isVisible()) await navigation.click()
  await page.getByRole('button', { name: '团队管理', exact: true }).click()
  const panel = page.getByRole('dialog', { name: '团队管理', exact: true })
  await panel.getByRole('combobox', { name: '选择团队编辑', exact: true }).click()
  await panel.getByRole('option', { name: new RegExp(`^${f.teamName}`) }).click()
  return panel
}
try {
  await vite.listen()
  const origin = `http://127.0.0.1:${vite.httpServer.address().port}`
  browser = await chromium.launch({ headless: true })
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
  // 仅注入真实登记得到的连接身份；不记录 trace 或凭据。
  await context.addInitScript(
    (input) => {
      localStorage.setItem(
        `skillforge.chat.profile:${input.origin}:${input.channel}`,
        JSON.stringify({ client_ref: input.clientRef, identity_token: input.identity })
      )
      sessionStorage.setItem(
        'skillforge.chat.connection',
        JSON.stringify({
          address: input.origin,
          mode: 'token',
          token: input.token,
          name: '团队停用验收'
        })
      )
    },
    { origin, channel: f.channel, clientRef: f.clientRef, identity: f.identity, token: f.token }
  )
  page = await context.newPage()
  page.on('pageerror', (error) => errors.push(error.message))
  page.on('response', (response) => {
    const path = new URL(response.url()).pathname
    if (path.startsWith('/api/'))
      network.push({ path, method: response.request().method(), status: response.status() })
  })
  page.on('request', (request) => {
    const path = new URL(request.url()).pathname
    if (path === '/api/v1/chat' && request.method() === 'POST')
      requests.push(request.postDataJSON())
  })
  await page.goto(origin)
  const navigation = page.getByRole('button', { name: '打开会话导航', exact: true })
  await expect(navigation).toBeVisible()
  await navigation.click()
  await page.getByRole('combobox', { name: '对话对象', exact: true }).click()
  await page.getByRole('option', { name: f.teamName, exact: true }).click()
  await expect(page.getByLabel('选择对话对象', { exact: true })).toContainText(f.teamName)
  await expect(page.getByRole('button', { name: '收起会话导航', exact: true })).toBeHidden()
  const input = page.getByRole('textbox', { name: '消息', exact: true })
  await expect(input).toBeEnabled()
  await input.fill('请确认当前个人团队可以正常执行。')
  const [acceptedResponse] = await Promise.all([
    page.waitForResponse(
      (response) =>
        new URL(response.url()).pathname === '/api/v1/chat' &&
        response.request().method() === 'POST'
    ),
    page.getByRole('button', { name: '发送消息', exact: true }).click()
  ])
  first = {
    request: acceptedResponse.request().postDataJSON(),
    status: acceptedResponse.status(),
    body: await acceptedResponse.json()
  }
  responses.push({ path: '/api/v1/chat', ...first })
  assert.equal(first.status, 202)
  session = first.request.session_id
  assert.ok(session)
  await completed(first.body.data.execution_id)
  await expect(page.getByText(f.reply, { exact: true })).toHaveCount(1)
  await input.fill(draft)
  let panel = await openTeams()
  await panel.getByRole('button', { name: '停用团队', exact: true }).click()
  const disabling = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/chat/teams/disable' &&
      response.request().method() === 'POST'
  )
  await page
    .getByRole('dialog', { name: '停用团队', exact: true })
    .getByRole('button', { name: '停用团队', exact: true })
    .click()
  const disableResponse = await disabling
  disabled = {
    request: disableResponse.request().postDataJSON(),
    status: disableResponse.status(),
    body: await disableResponse.json()
  }
  responses.push({ path: '/api/v1/chat/teams/disable', ...disabled })
  assert.equal(disabled.status, 200)
  assert.equal(disabled.body.data.id, f.team)
  assert.equal(disabled.body.data.status, 'disabled')
  await expect(panel.getByText('团队已停用。', { exact: true })).toBeVisible()
  await panel.getByRole('button', { name: '关闭', exact: true }).click()
  await expect(panel).toBeHidden()
  await expect(input).toHaveValue(draft)
  const disabledHistory = await read(
    `/api/v1/chat/history?session_id=${encodeURIComponent(session)}`
  )
  const disabledStatus = await read(
    `/api/v1/chat/runs/status?session_id=${encodeURIComponent(session)}&run_id=${encodeURIComponent(first.body.data.execution_id)}`
  )
  const disabledSessions = await read(
    `/api/v1/chat/sessions?team_id=${encodeURIComponent(f.team)}&end_user_id=${first.request.context.end_user_id}`
  )
  const disabledLimits = await read(`/api/v1/chat/limits?session_id=${encodeURIComponent(session)}`)
  await writeFile(
    join(f.evidence, 'disabled-reads.json'),
    JSON.stringify({ disabledHistory, disabledStatus, disabledSessions, disabledLimits }, null, 2)
  )
  const send = page.getByRole('button', { name: '发送消息', exact: true })
  if (f.mode === 'baseline') {
    await expect(send).toBeEnabled()
    const denied = page.waitForResponse(
      (response) =>
        new URL(response.url()).pathname === '/api/v1/chat' &&
        response.request().method() === 'POST'
    )
    await send.click()
    const deniedResponse = await denied
    rejected = {
      request: deniedResponse.request().postDataJSON(),
      status: deniedResponse.status(),
      body: await deniedResponse.json()
    }
    responses.push({ path: '/api/v1/chat', ...rejected })
    assert.ok(rejected.status >= 400 && rejected.status < 500, JSON.stringify(rejected))
    assert.equal(rejected.request.session_id, session)
    await expect(input).toBeEnabled()
  } else {
    assert.equal(disabledHistory.status, 200)
    assert.ok(disabledHistory.body.data.messages.some((message) => message.content === f.reply))
    assert.equal(disabledStatus.status, 200)
    assert.equal(disabledStatus.body.data.status, 'completed')
    assert.equal(disabledSessions.status, 200)
    assert.ok(disabledSessions.body.data.some((item) => item.session_id === session))
    assert.equal(disabledLimits.status, 200)
    const request = {
      ...first.request,
      client_message_id: randomUUID(),
      content: [{ type: 'text', text: draft }]
    }
    const deniedResponse = await fetch(f.backend + '/api/v1/chat', {
      method: 'POST',
      headers: {
        'X-Channel-Token': f.token,
        'X-Chat-User-Token': f.identity,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify(request),
      signal: AbortSignal.timeout(10000)
    })
    disabledWrite = { request, status: deniedResponse.status, body: await deniedResponse.json() }
    assert.equal(disabledWrite.status, 404)
    assert.equal(disabledWrite.body.code, 'NOT_FOUND')
    await expect(send).toBeDisabled()
    await expect(page.getByText(/当前团队已停用/).first()).toBeVisible()
    await expect(page.getByText(/当前团队已停用，仅可查看历史/).first()).toBeVisible()
    await page.locator('.composer-form').dispatchEvent('submit')
    assert.equal(requests.length, 1)
    const eventConnections = network.filter((item) => item.path === '/api/v1/chat/events').length
    await page.getByRole('button', { name: '更多会话操作', exact: true }).click()
    await page.getByRole('button', { name: '重新连接', exact: true }).click()
    await expect(page.getByText(f.reply, { exact: true })).toHaveCount(1)
    await expect
      .poll(
        () =>
          network.filter((item) => item.path === '/api/v1/chat/events' && item.status === 200)
            .length,
        // 无新事件时服务端每 15 秒发心跳，响应头随首次写入到达。
        { timeout: 25000 }
      )
      .toBeGreaterThan(eventConnections)
    await expect(input).toHaveValue(draft)
    await expect(send).toBeDisabled()
  }
  await expect(input).toHaveValue(draft)
  await page.screenshot({ path: join(f.evidence, 'disabled.png'), fullPage: true })
  await writeFile(join(f.evidence, 'disabled.html'), await page.content())
  if (f.mode === 'candidate') {
    await page.reload()
    await expect(page.getByLabel('选择对话对象', { exact: true })).toContainText(f.teamName)
    await expect(page.getByText(f.reply, { exact: true })).toHaveCount(1)
    await expect(send).toBeDisabled()
    await page.screenshot({ path: join(f.evidence, 'disabled-reloaded.png'), fullPage: true })
    await writeFile(join(f.evidence, 'disabled-reloaded.html'), await page.content())
  }
  panel = await openTeams()
  const saving = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/chat/teams' &&
      response.request().method() === 'PUT'
  )
  await panel.getByRole('button', { name: '保存团队', exact: true }).click()
  const saved = await saving
  restored = {
    request: saved.request().postDataJSON(),
    status: saved.status(),
    body: await saved.json()
  }
  responses.push({ path: '/api/v1/chat/teams', ...restored })
  assert.equal(restored.status, 200)
  assert.equal(restored.body.data.id, f.team)
  assert.equal(restored.body.data.status, 'published')
  await expect(panel.getByText('团队已保存，可在团队列表中查看。', { exact: true })).toBeVisible()
  await panel.getByRole('button', { name: '关闭', exact: true }).click()
  await expect(panel).toBeHidden()
  if (f.mode === 'candidate') await input.fill(draft)
  await expect(input).toHaveValue(draft)
  await expect(send).toBeEnabled()
  const resumed = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/chat' && response.request().method() === 'POST'
  )
  await send.click()
  const resumedResponse = await resumed
  second = {
    request: resumedResponse.request().postDataJSON(),
    status: resumedResponse.status(),
    body: await resumedResponse.json()
  }
  responses.push({ path: '/api/v1/chat', ...second })
  assert.equal(second.status, 202)
  assert.equal(second.request.session_id, session)
  assert.notEqual(second.body.data.execution_id, first.body.data.execution_id)
  await completed(second.body.data.execution_id)
  await expect(page.getByText(f.reply, { exact: true })).toHaveCount(2)
  assert.equal(requests.length, f.mode === 'baseline' ? 3 : 2)
  assert.deepEqual(errors, [])
  const history = await read(`/api/v1/chat/history?session_id=${encodeURIComponent(session)}`)
  assert.equal(history.status, 200)
  assert.equal(history.body.data.messages.filter((message) => message.role === 'user').length, 2)
  await writeFile(
    join(f.evidence, 'receipt.json'),
    JSON.stringify(
      {
        mode: f.mode,
        session,
        team: f.team,
        first,
        second,
        rejected,
        disabledWrite,
        disabled,
        restored,
        submissions: requests.length,
        history
      },
      null,
      2
    )
  )
  process.stdout.write('PASS: 团队停用与重新发布的原会话链路完成\n')
} finally {
  try {
    await writeFile(
      join(f.evidence, 'browser-http.json'),
      JSON.stringify({ requests, responses, reads, errors, network }, null, 2)
    )
    if (page) {
      await writeFile(join(f.evidence, 'final.html'), await page.content())
      await page.screenshot({ path: join(f.evidence, 'final.png'), fullPage: true })
    }
  } finally {
    try {
      await browser?.close()
    } finally {
      await vite.close()
    }
  }
}
