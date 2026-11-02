import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'
import process from 'node:process'
import { createServer } from 'vite'

const require = createRequire(new URL('../../frontend-admin/package.json', import.meta.url))
const { chromium, expect } = require('@playwright/test')
const vite = await createServer({
  root: fileURLToPath(new URL('../', import.meta.url)),
  server: { host: '127.0.0.1', port: 0 }
})
let browser
try {
  await vite.listen()
  const origin = `http://127.0.0.1:${vite.httpServer.address().port}`
  browser = await chromium.launch({ headless: true })
  const page = await browser.newPage()
  const requests = []
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request(),
      url = new URL(request.url())
    requests.push({ path: url.pathname, method: request.method(), query: url.search })
    const data =
      url.pathname === '/api/v1/chat/agents'
        ? { channel_id: 'fixture', items: [] }
        : url.pathname === '/api/v1/chat/client-profile'
          ? { end_user_id: '42', identity_token: 'fixture-identity' }
          : url.pathname === '/api/v1/chat/teams'
            ? [
                {
                  id: 'team-1',
                  name: '服务团队',
                  status: 'published',
                  revision: 1,
                  members: [],
                  primary_agent_id: 'agent-1'
                }
              ]
            : url.pathname === '/api/v1/chat/commands'
              ? { items: [] }
              : url.pathname === '/api/v1/chat/sessions' && request.method() === 'GET'
                ? []
                : undefined
    if (data === undefined)
      throw new Error(`unexpected request ${request.method()} ${url.pathname}`)
    await route.fulfill({ json: { code: 'OK', message: 'OK', trace_id: 'fixture', data } })
  })
  await page.goto(origin)
  await page.getByLabel('服务地址').fill(origin)
  await page.getByLabel('渠道令牌').fill('fixture-token')
  await page.getByRole('button', { name: '进入会话' }).click()
  await expect(page.getByText('服务团队').first()).toBeVisible()
  await expect(page.getByRole('button', { name: '新建会话' })).toBeEnabled()
  const sessions = requests.filter((request) => request.path === '/api/v1/chat/sessions')
  assert.equal(sessions.length, 1)
  assert.equal(new URLSearchParams(sessions[0].query).get('team_id'), 'team-1')
  assert.equal(sessions[0].method, 'GET')
  process.stdout.write('single-team-browser: passed\n')
} finally {
  await browser?.close()
  await vite.close()
}
