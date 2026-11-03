import { test, expect } from '@playwright/test'
import { createServer } from 'vite'
import { writeFile } from 'node:fs/promises'
import { resolve, join } from 'node:path'

function required(name: string) {
  const value = process.env[name]
  if (!value) throw Error(`缺少隔离夹具：${name}`)
  return value
}
test('新建渠道后使用首次令牌运行智能体', async ({ page, browser }) => {
  await page.goto(`/login?redirect=${encodeURIComponent('/admin/channels')}`)
  await page.locator('input[autocomplete="organization"]').fill(required('E2E_TENANT_SLUG'))
  await page.locator('input[autocomplete="username"]').fill(required('E2E_USERNAME'))
  await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await page.getByRole('button', { name: '新增渠道', exact: true }).click()
  const drawer = page.getByRole('dialog', { name: '新增渠道' })
  await drawer
    .locator('.el-form-item')
    .filter({ hasText: '渠道名称' })
    .locator('input')
    .fill('端到端测试渠道')
  await drawer
    .locator('.el-form-item')
    .filter({ hasText: '接入智能体' })
    .locator('.el-select__wrapper')
    .click()
  await page.getByRole('option', { name: 'Runtime E2E Agent', exact: true }).click()
  await page.keyboard.press('Escape')
  const created = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/channels/onboarding' &&
      response.request().method() === 'POST'
  )
  await drawer.getByRole('button', { name: '保存', exact: true }).click()
  const response = await created
  expect(response.status()).toBe(200)
  const token = (await response.json()).data.channel.channel_token
  expect(token).toBeTruthy()
  const vite = await createServer({
    root: resolve('../frontend-chat'),
    server: {
      host: '127.0.0.1',
      port: 0,
      strictPort: false,
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
  try {
    await vite.listen()
    const address = vite.httpServer!.address()
    if (!address || typeof address === 'string') throw Error('会话端未监听')
    const chat = await context.newPage()
    let submissions = 0
    chat.on('request', (request) => {
      if (request.method() === 'POST' && new URL(request.url()).pathname === '/api/v1/chat')
        submissions++
    })
    await chat.goto(`http://127.0.0.1:${address.port}/`)
    await chat.getByRole('textbox', { name: '你的称呼' }).fill('隔离渠道用户')
    await chat.locator('input[type="password"]').fill(token)
    await chat.getByRole('button', { name: '进入会话' }).click()
    const input = chat.getByRole('textbox', { name: '消息', exact: true })
    await expect(input).toBeEnabled()
    await input.fill('你好，请回复测试结果')
    const accepted = chat.waitForResponse(
      (r) => new URL(r.url()).pathname === '/api/v1/chat' && r.request().method() === 'POST'
    )
    await chat.getByRole('button', { name: '发送消息', exact: true }).click()
    const reply = await accepted
    expect(reply.status()).toBe(202)
    const runId = String((await reply.json()).data.execution_id)
    await expect(chat.locator('.message.assistant .message-body')).toContainText(
      required('E2E_EXPECTED_REPLY'),
      { timeout: 120_000 }
    )
    expect(submissions).toBe(1)
    await chat.screenshot({
      path: join(required('E2E_ARTIFACT_DIR'), 'channel-runtime-result.png')
    })
    await writeFile(
      join(required('E2E_ARTIFACT_DIR'), 'channel-runtime-result.json'),
      JSON.stringify({ runId, submissions })
    )
  } finally {
    await context.close()
    await vite.close()
  }
})
