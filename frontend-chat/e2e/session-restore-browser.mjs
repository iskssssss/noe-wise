import assert from 'node:assert/strict'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'
import process from 'node:process'
import { createServer } from 'vite'

const f = JSON.parse(process.env.SESSION_RESTORE_FIXTURE)
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
let browser, page, session, first, second, originalSelection, failedSelection, refreshedSelection
let phase = 'initial',
  reloadLists = 0,
  injections = 0,
  sessionCreations = 0,
  connected = false
const requests = [],
  histories = [],
  directories = [],
  reads = [],
  errors = [],
  network = []
async function read(path) {
  const response = await fetch(f.backend + path, {
    headers: { 'X-Channel-Token': f.token, 'X-Chat-User-Token': f.identity },
    signal: AbortSignal.timeout(10000)
  })
  const value = { path, status: response.status, body: await response.json() }
  reads.push(value)
  return value
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
async function selection() {
  return page.evaluate(() => JSON.parse(sessionStorage.getItem('skillforge.chat.selection')))
}
async function openNavigation() {
  const button = page.getByRole('button', { name: '打开会话导航', exact: true })
  if (await button.isVisible()) await button.click()
}
try {
  await vite.listen()
  const origin = `http://127.0.0.1:${vite.httpServer.address().port}`
  browser = await chromium.launch({ headless: true })
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
  // 只登记真实连接身份，刷新时保留由页面自己写入的选中会话。
  await context.addInitScript(
    (input) => {
      const key = `skillforge.chat.profile:${input.origin}:${input.channel}`
      if (!localStorage.getItem(key))
        localStorage.setItem(
          key,
          JSON.stringify({ client_ref: input.clientRef, identity_token: input.identity })
        )
      if (!sessionStorage.getItem('skillforge.chat.connection'))
        sessionStorage.setItem(
          'skillforge.chat.connection',
          JSON.stringify({
            address: input.origin,
            mode: 'token',
            token: input.token,
            name: '会话恢复验收'
          })
        )
    },
    { origin, channel: f.channel, clientRef: f.clientRef, identity: f.identity, token: f.token }
  )
  page = await context.newPage()
  page.setDefaultTimeout(15000)
  page.on('pageerror', (error) => errors.push(error.message))
  page.on('request', (request) => {
    const path = new URL(request.url()).pathname
    if (path === '/api/v1/chat' && request.method() === 'POST')
      requests.push(request.postDataJSON())
    if (
      request.method() === 'POST' &&
      ['/api/v1/chat/sessions', '/api/v1/chat/sessions/create-with-receipt'].includes(path)
    )
      sessionCreations++
  })
  page.on('response', (response) => {
    const url = new URL(response.url())
    if (url.pathname.startsWith('/api/v1/chat') && !url.pathname.endsWith('/client-profile'))
      network.push({
        phase,
        path: url.pathname + url.search,
        method: response.request().method(),
        status: response.status()
      })
  })
  await page.route('**/api/v1/chat/sessions?**', async (route) => {
    if (route.request().method() !== 'GET') return route.continue()
    const routePhase = phase
    const ordinal = routePhase === 'reload' ? ++reloadLists : 0
    const response = await route.fetch({ timeout: 20000 })
    const body = await response.json()
    const injected = routePhase === 'reload' && ordinal === 2
    directories.push({
      phase: routePhase,
      ordinal,
      path: new URL(route.request().url()).pathname + new URL(route.request().url()).search,
      status: response.status(),
      body,
      injected
    })
    assert.equal(response.status(), 200)
    if (injected) {
      injections++
      await writeFile(
        join(f.evidence, 'injected-directory.json'),
        JSON.stringify(directories.at(-1), null, 2)
      )
      return route.abort('connectionreset')
    }
    return route.fulfill({ response })
  })
  await page.route('**/api/v1/chat/history?**', async (route) => {
    const routePhase = phase
    const response = await route.fetch({ timeout: 20000 })
    const body = await response.json()
    histories.push({
      phase: routePhase,
      path: new URL(route.request().url()).pathname + new URL(route.request().url()).search,
      status: response.status(),
      body
    })
    return route.fulfill({ response })
  })
  await page.goto(origin)
  const input = page.getByRole('textbox', { name: '消息', exact: true })
  await expect(input).toBeEnabled()
  connected = true
  await input.fill(f.firstMessage)
  const firstResponse = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/chat' && response.request().method() === 'POST'
  )
  await page.getByRole('button', { name: '发送消息', exact: true }).click()
  const accepted = await firstResponse
  first = {
    request: accepted.request().postDataJSON(),
    status: accepted.status(),
    body: await accepted.json()
  }
  await writeFile(join(f.evidence, 'first-accepted.json'), JSON.stringify(first, null, 2))
  assert.equal(first.status, 202)
  session = first.request.session_id
  assert.ok(session)
  await completed(first.body.data.execution_id)
  await expect(page.locator('.message.assistant .message-body')).toContainText(f.reply)
  await expect.poll(async () => (await selection())?.session).toBe(session)
  await openNavigation()
  const refresh = page.getByRole('button', { name: '刷新会话', exact: true })
  await expect(refresh).toBeEnabled()
  const originalRead = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/chat/sessions' &&
      response.request().method() === 'GET'
  )
  await refresh.click()
  assert.equal((await originalRead).status(), 200)
  await expect(refresh).toBeEnabled()
  originalSelection = await selection()
  assert.equal(originalSelection.session, session)
  await writeFile(
    join(f.evidence, 'original-selection.json'),
    JSON.stringify(originalSelection, null, 2)
  )
  await page.screenshot({ path: join(f.evidence, 'before-reload.png'), fullPage: true })
  phase = 'reload'
  await page.reload()
  await expect.poll(() => injections).toBe(1)
  await openNavigation()
  await expect(page.getByRole('navigation', { name: '历史会话', exact: true })).toContainText(
    '历史会话列表尚未读取成功。'
  )
  await expect(refresh).toBeEnabled()
  const probe = await read(`/api/v1/chat/history?session_id=${encodeURIComponent(session)}`)
  assert.equal(probe.status, 200)
  assert.equal(probe.body.data.session_id, session)
  assert.ok(
    probe.body.data.messages.some(
      (message) => message.role === 'assistant' && message.content.includes(f.reply)
    )
  )
  await writeFile(join(f.evidence, 'history-probe.json'), JSON.stringify(probe, null, 2))
  if (f.mode === 'baseline') {
    await expect.poll(async () => (await selection())?.session).toBe('')
    assert.equal(histories.filter((item) => item.phase === 'reload').length, 0)
    await expect(page.locator('.message.assistant')).toHaveCount(0)
  } else {
    await expect.poll(async () => (await selection())?.session).toBe(session)
    await expect(page.locator('.message.assistant .message-body')).toContainText(f.reply)
    assert.ok(
      histories.some(
        (item) =>
          item.phase === 'reload' && item.status === 200 && item.body.data.session_id === session
      )
    )
  }
  failedSelection = await selection()
  await writeFile(
    join(f.evidence, 'reload-state.json'),
    JSON.stringify(
      {
        selection: failedSelection,
        reloadLists,
        injections,
        histories: histories.filter((item) => item.phase === 'reload')
      },
      null,
      2
    )
  )
  await page.screenshot({ path: join(f.evidence, 'directory-failed.png'), fullPage: true })
  await writeFile(join(f.evidence, 'directory-failed.html'), await page.locator('body').innerHTML())
  phase = 'refreshed'
  const fresh = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/chat/sessions' &&
      response.request().method() === 'GET'
  )
  await refresh.click()
  assert.equal((await fresh).status(), 200)
  await expect(refresh).toBeEnabled()
  const historyNavigation = page.getByRole('navigation', { name: '历史会话', exact: true })
  const originalButton = historyNavigation.getByRole('button').filter({ hasText: f.firstMessage })
  await expect(originalButton).toHaveCount(1)
  refreshedSelection = await selection()
  assert.equal(refreshedSelection.session, f.mode === 'baseline' ? '' : session)
  if (f.mode === 'baseline') {
    assert.equal(histories.filter((item) => item.phase === 'refreshed').length, 0)
    await expect(page.locator('.message.assistant')).toHaveCount(0)
  }
  await writeFile(
    join(f.evidence, 'refreshed-state.json'),
    JSON.stringify(
      {
        selection: refreshedSelection,
        histories: histories.filter((item) => item.phase === 'refreshed')
      },
      null,
      2
    )
  )
  await page.screenshot({ path: join(f.evidence, 'directory-refreshed.png'), fullPage: true })
  if (f.mode === 'baseline') {
    phase = 'manual-restoration'
    await originalButton.click()
    await expect(page.locator('.message.assistant .message-body')).toContainText(f.reply)
    await expect.poll(async () => (await selection())?.session).toBe(session)
  }
  phase = 'second-input'
  const collapseNavigation = page.getByRole('button', { name: '收起会话导航', exact: true })
  if (await collapseNavigation.isVisible()) await collapseNavigation.click()
  await expect(input).toBeEnabled()
  await input.fill(f.secondMessage)
  const [acceptedSecond] = await Promise.all([
    page.waitForResponse(
      (response) =>
        new URL(response.url()).pathname === '/api/v1/chat' &&
        response.request().method() === 'POST'
    ),
    page.getByRole('button', { name: '发送消息', exact: true }).click()
  ])
  second = {
    request: acceptedSecond.request().postDataJSON(),
    status: acceptedSecond.status(),
    body: await acceptedSecond.json()
  }
  await writeFile(join(f.evidence, 'second-accepted.json'), JSON.stringify(second, null, 2))
  assert.equal(second.status, 202)
  assert.equal(second.request.session_id, session)
  assert.notEqual(second.request.client_message_id, first.request.client_message_id)
  await completed(second.body.data.execution_id)
  await expect(
    page.locator('.message.assistant .message-body').filter({ hasText: f.reply })
  ).toHaveCount(2)
  assert.equal(requests.length, 2)
  assert.equal(sessionCreations, 1)
  assert.equal(injections, 1)
  assert.deepEqual(errors, [])
  await writeFile(
    join(f.evidence, 'result.json'),
    JSON.stringify(
      {
        mode: f.mode,
        session,
        firstRun: first.body.data.execution_id,
        secondRun: second.body.data.execution_id,
        submissions: requests.length,
        sessionCreations,
        injections,
        originalSelection,
        failedSelection,
        refreshedSelection
      },
      null,
      2
    )
  )
} finally {
  try {
    await writeFile(
      join(f.evidence, 'browser-state.json'),
      JSON.stringify(
        {
          phase,
          requests,
          histories,
          directories,
          reads,
          errors,
          network,
          sessionCreations,
          injections
        },
        null,
        2
      )
    )
    if (page && connected) {
      await writeFile(join(f.evidence, 'page.html'), await page.locator('body').innerHTML())
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
