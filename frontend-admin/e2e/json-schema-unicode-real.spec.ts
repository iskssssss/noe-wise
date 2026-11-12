import { expect, test } from '@playwright/test'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'

function required(name: string): string {
  const value = process.env[name]
  if (!value) throw new Error(`缺少格式校验验收参数：${name}`)
  return value
}

test.describe.configure({ retries: 0 })
// 业务证据显式保存，登录凭据不进入 trace。
test.use({ trace: 'off', video: 'off' })
test('字符串长度约束的真实工具结果回传模型并展示', async ({ page }) => {
  test.setTimeout(170_000)
  const agent = required('E2E_AGENT_ID'),
    mode = required('E2E_JSON_SCHEMA_UNICODE')
  const evidence = required('E2E_ARTIFACT_DIR'),
    errors: string[] = []
  expect(['baseline', 'candidate']).toContain(mode)
  let submits = 0
  page.on('pageerror', (error) => errors.push(error.message))
  page.on('request', (request) => {
    if (request.method() === 'POST' && new URL(request.url()).pathname === '/api/v1/chat') submits++
  })
  try {
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
      .fill('按顺序验证单个表情与两个 ASCII 字符，返回实际长度校验结果。')
    const accepted = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        new URL(response.url()).pathname === '/api/v1/chat'
    )
    await page.getByRole('button', { name: '发送', exact: true }).click()
    const response = await accepted
    expect(response.status()).toBe(202)
    const receipt = (await response.json()).data
    await writeFile(join(evidence, 'receipt.json'), JSON.stringify(receipt, null, 2))
    await writeFile(
      join(evidence, 'chat-input.json'),
      JSON.stringify(response.request().postDataJSON(), null, 2)
    )
    const reply = page.locator('.debug-message').filter({ hasText: 'JSON_SCHEMA_UNICODE_OBSERVED' })
    await expect(reply).toHaveCount(1, { timeout: 120_000 })
    const text = await reply.innerText()
    await writeFile(join(evidence, 'actual-reply.txt'), text)
    const results = [...text.matchAll(/"valid"\s*:\s*(true|false)/g)].map(
      (match) => match[1] === 'true'
    )
    expect(results).toEqual(mode === 'baseline' ? [false, false] : [true, false])
    expect([...text.matchAll(/"success"\s*:\s*true/g)]).toHaveLength(2)
    expect(submits).toBe(1)
    expect(errors).toEqual([])
    await writeFile(
      join(evidence, 'browser-result.json'),
      JSON.stringify({ mode, submits, results, errors }, null, 2)
    )
  } finally {
    await writeFile(
      join(evidence, 'browser-state.json'),
      JSON.stringify({ mode, submits, errors }, null, 2)
    )
    await writeFile(join(evidence, 'final.html'), await page.locator('body').innerHTML())
    await page.screenshot({ path: join(evidence, 'final.png'), fullPage: true })
  }
})
