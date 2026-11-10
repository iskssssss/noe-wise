import { expect, test } from '@playwright/test'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'

function required(name: string): string {
  const value = process.env[name]
  if (!value) throw new Error(`缺少工具验收参数：${name}`)
  return value
}

test.describe.configure({ retries: 0 })

test('真实试用返回结构化数据查询结果', async ({ page }) => {
  test.setTimeout(180_000)
  const agent = required('E2E_AGENT_ID')
  await page.goto(`/login?redirect=${encodeURIComponent('/admin/agents/' + agent)}`)
  await page.locator('input[autocomplete="organization"]').fill(required('E2E_TENANT_SLUG'))
  await page.locator('input[autocomplete="username"]').fill('runtime-e2e-admin')
  await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
  await page.getByRole('button', { name: '登录', exact: true }).click()
  const users = page.waitForResponse(
    (response) => new URL(response.url()).pathname === '/api/v1/chat/end-users'
  )
  await page.getByRole('button', { name: '试用当前发布版本', exact: true }).click()
  expect((await users).status()).toBe(200)
  await page.locator('.el-select').filter({ hasText: '终端用户' }).getByRole('combobox').click()
  await page.getByRole('option', { name: 'Runtime E2E User', exact: true }).click()
  await page
    .getByPlaceholder('输入消息', { exact: true })
    .fill('查询联系人电话空值、缺失字段和姓名，并验证无效指针的结果。')
  const accepted = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' && new URL(response.url()).pathname === '/api/v1/chat'
  )
  await page.getByRole('button', { name: '发送', exact: true }).click()
  const response = await accepted
  expect(response.status()).toBe(202)
  const receipt = (await response.json()).data
  await expect(
    page.locator('.debug-message').filter({ hasText: 'JSON_QUERY_OBSERVED' })
  ).toHaveCount(1, { timeout: 120_000 })
  const reply = page.locator('.debug-message').filter({ hasText: 'JSON_QUERY_OBSERVED' })
  await expect(reply).toContainText('"found":true,"success":true,"value":null')
  await expect(reply).toContainText('"found":false,"success":true,"value":null')
  await expect(reply).toContainText('"value":"Ada"')
  await expect(reply).toContainText('pointer 必须是有效的 RFC 6901 JSON Pointer')
  await writeFile(
    join(required('E2E_ARTIFACT_DIR'), 'receipt.json'),
    JSON.stringify(receipt, null, 2)
  )
  await page.screenshot({
    path: join(required('E2E_ARTIFACT_DIR'), 'query-result.png'),
    fullPage: true
  })
})
