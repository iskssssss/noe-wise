import { expect, test } from '@playwright/test'
import { createServer } from 'vite'
import { writeFile } from 'node:fs/promises'
import { join, resolve } from 'node:path'

function required(name: string): string {
  const value = process.env[name]
  if (!value) throw Error(`缺少渠道路由验收参数：${name}`)
  return value
}

test.describe.configure({ retries: 0 })
// 凭据只用于真实连接，证据不包含 trace、视频或自动失败截图。
test.use({ trace: 'off', video: 'off', screenshot: 'off' })
test('清除渠道默认值后保留路由参数并由原优先级决定实际运行', async ({ page, browser }) => {
  test.setTimeout(170_000)
  const mode = required('E2E_CHANNEL_BINDING_ROUTING'),
    evidence = required('E2E_ARTIFACT_DIR')
  expect(['baseline', 'candidate']).toContain(mode)
  const channel = required('E2E_CHANNEL_ID'),
    agentA = required('E2E_AGENT_A'),
    agentB = required('E2E_AGENT_B')
  const expectedAgent = mode === 'baseline' ? agentB : agentA
  let bindingWrites = 0,
    submissions = 0
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  page.on('request', (request) => {
    if (request.method() === 'PUT' && new URL(request.url()).pathname === '/api/v1/channels/agents')
      bindingWrites++
  })
  await page.goto(`/login?redirect=${encodeURIComponent('/admin/channels')}`)
  await page.locator('input[autocomplete="organization"]').fill(required('E2E_TENANT_SLUG'))
  await page.locator('input[autocomplete="username"]').fill('runtime-e2e-admin')
  await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
  await page.getByRole('button', { name: '登录', exact: true }).click()
  const row = page.getByRole('row').filter({ hasText: required('E2E_CHANNEL_NAME') })
  const beforeRead = page.waitForResponse(
    (response) => new URL(response.url()).pathname === '/api/v1/channels/detail'
  )
  await row.getByRole('button', { name: '绑定智能体', exact: true }).click()
  const beforeResponse = await beforeRead,
    before = (await beforeResponse.json()).data
  await writeFile(
    join(evidence, 'before-detail.json'),
    JSON.stringify(
      { id: before.id, name: before.name, agent_bindings: before.agent_bindings },
      null,
      2
    )
  )
  expect(before.agent_bindings.map((item: { id: string }) => item.id)).toEqual([agentA, agentB])
  expect(before.agent_bindings).toEqual(
    expect.arrayContaining([
      expect.objectContaining({
        id: agentA,
        priority: 20,
        traffic_percentage: 25,
        is_default: true
      }),
      expect.objectContaining({
        id: agentB,
        priority: 10,
        traffic_percentage: 75,
        is_default: false
      })
    ])
  )
  const drawer = page.getByRole('dialog', {
    name: `绑定智能体 · ${required('E2E_CHANNEL_NAME')}`,
    exact: true
  })
  const defaultSelect = drawer
    .locator('.el-form-item')
    .filter({ hasText: '默认智能体' })
    .locator('.el-select')
  await defaultSelect.hover()
  await defaultSelect.locator('.el-select__clear').click()
  const saved = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/channels/agents' &&
      response.request().method() === 'PUT'
  )
  await drawer.getByRole('button', { name: '保存', exact: true }).click()
  const savedResponse = await saved,
    command = savedResponse.request().postDataJSON(),
    responseBody = await savedResponse.json()
  await writeFile(
    join(evidence, 'binding-write.json'),
    JSON.stringify({ command, status: savedResponse.status(), body: responseBody }, null, 2)
  )
  expect(savedResponse.status()).toBe(200)
  expect(command.channel_id).toBe(channel)
  expect(command.payload.map((item: { is_default: boolean }) => item.is_default)).toEqual([
    false,
    false
  ])
  await expect(drawer).toBeHidden()
  const afterRead = page.waitForResponse(
    (response) => new URL(response.url()).pathname === '/api/v1/channels/detail'
  )
  await row.getByRole('button', { name: '绑定智能体', exact: true }).click()
  const afterResponse = await afterRead,
    after = (await afterResponse.json()).data
  await writeFile(
    join(evidence, 'after-detail.json'),
    JSON.stringify(
      { id: after.id, name: after.name, agent_bindings: after.agent_bindings },
      null,
      2
    )
  )
  expect(after.agent_bindings).toEqual(
    expect.arrayContaining([
      expect.objectContaining({
        id: agentA,
        priority: mode === 'baseline' ? 0 : 20,
        traffic_percentage: mode === 'baseline' ? 100 : 25,
        is_default: false
      }),
      expect.objectContaining({
        id: agentB,
        priority: mode === 'baseline' ? 1 : 10,
        traffic_percentage: mode === 'baseline' ? 100 : 75,
        is_default: false
      })
    ])
  )
  expect(bindingWrites).toBe(1)
  await page.screenshot({ path: join(evidence, 'bindings.png'), fullPage: true })
  await writeFile(join(evidence, 'bindings.html'), await page.locator('main').innerHTML())
  await drawer.getByRole('button', { name: '取消', exact: true }).click()
  const vite = await createServer({
    root: resolve('../frontend-chat'),
    server: {
      host: '127.0.0.1',
      port: 0,
      proxy: {
        '/api': {
          target: required('E2E_BACKEND_URL'),
          changeOrigin: true,
          configure(server) {
            server.on('proxyReq', (request) => request.setHeader('Origin', 'http://localhost:3200'))
          }
        }
      }
    }
  })
  const context = await browser.newContext()
  let chat: Awaited<ReturnType<typeof context.newPage>> | undefined
  let connected = false
  try {
    await vite.listen()
    const address = vite.httpServer!.address()
    if (!address || typeof address === 'string') throw Error('会话端未监听')
    chat = await context.newPage()
    chat.on('pageerror', (error) => errors.push(error.message))
    chat.on('request', (request) => {
      if (request.method() === 'POST' && new URL(request.url()).pathname === '/api/v1/chat')
        submissions++
    })
    await chat.goto(`http://127.0.0.1:${address.port}`)
    await chat.getByRole('textbox', { name: '你的称呼', exact: true }).fill('渠道路由验收用户')
    await chat.locator('input[type="password"]').fill(required('E2E_CHANNEL_TOKEN'))
    const catalogRead = chat.waitForResponse(
      (response) => new URL(response.url()).pathname === '/api/v1/chat/agents'
    )
    await chat.getByRole('button', { name: '进入会话', exact: true }).click()
    const catalogResponse = await catalogRead,
      catalog = (await catalogResponse.json()).data
    await writeFile(join(evidence, 'runtime-catalog.json'), JSON.stringify(catalog, null, 2))
    expect(catalog.items[0].id).toBe(expectedAgent)
    expect(catalog.items.every((item: { is_default: boolean }) => !item.is_default)).toBe(true)
    const input = chat.getByRole('textbox', { name: '消息', exact: true })
    await expect(input).toBeEnabled()
    connected = true
    await input.fill('请使用渠道当前自动选择的助手完成这次对话。')
    const accepted = chat.waitForResponse(
      (response) =>
        new URL(response.url()).pathname === '/api/v1/chat' &&
        response.request().method() === 'POST'
    )
    await chat.getByRole('button', { name: '发送消息', exact: true }).click()
    const acceptedResponse = await accepted,
      inputCommand = acceptedResponse.request().postDataJSON(),
      acceptedBody = await acceptedResponse.json()
    await writeFile(
      join(evidence, 'runtime-accepted.json'),
      JSON.stringify(
        { command: inputCommand, status: acceptedResponse.status(), body: acceptedBody },
        null,
        2
      )
    )
    expect(acceptedResponse.status()).toBe(202)
    expect(inputCommand.agent_id).toBe(expectedAgent)
    await expect(chat.locator('.message.assistant .message-body')).toContainText(
      required('E2E_EXPECTED_REPLY'),
      { timeout: 100_000 }
    )
    expect(submissions).toBe(1)
    expect(errors).toEqual([])
    await writeFile(
      join(evidence, 'result.json'),
      JSON.stringify(
        {
          mode,
          channel,
          agentA,
          agentB,
          expectedAgent,
          runId: acceptedBody.data.execution_id,
          sessionId: inputCommand.session_id,
          bindingWrites,
          submissions,
          errors
        },
        null,
        2
      )
    )
  } finally {
    try {
      await writeFile(
        join(evidence, 'browser-state.json'),
        JSON.stringify({ mode, bindingWrites, submissions, errors }, null, 2)
      )
      if (chat && connected) {
        await writeFile(join(evidence, 'runtime-page.html'), await chat.locator('body').innerHTML())
        await chat.screenshot({ path: join(evidence, 'runtime.png'), fullPage: true })
      }
    } finally {
      try {
        await context.close()
      } finally {
        await vite.close()
      }
    }
  }
})
