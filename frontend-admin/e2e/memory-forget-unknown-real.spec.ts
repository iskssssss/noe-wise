import { expect, test } from '@playwright/test'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'
function required(name: string) {
  const value = process.env[name]
  if (!value) throw Error(`缺少验收参数：${name}`)
  return value
}
test.describe.configure({ retries: 0 })
test.use({ trace: 'off', video: 'off', screenshot: 'off' })
test('遗忘回执丢失后只读核对原事实', async ({ page }) => {
  test.setTimeout(150_000)
  const mode = required('E2E_MEMORY_FORGET_UNKNOWN'),
    factId = required('E2E_MEMORY_FACT_ID')
  const text = required('E2E_MEMORY_TEXT'),
    query = required('E2E_MEMORY_QUERY')
  const revision = Number(required('E2E_MEMORY_REVISION')),
    evidence = required('E2E_ARTIFACT_DIR')
  const writes: string[] = [],
    requests: unknown[] = [],
    errors: string[] = []
  let upstreamStatus = 0,
    original: unknown
  page.on('pageerror', (error) => errors.push(error.message))
  page.on('request', (request) => {
    const path = new URL(request.url()).pathname
    if (request.method() === 'POST' && path.startsWith('/api/v1/memories/')) writes.push(path)
  })
  await page.route('**/api/v1/memories/forget', async (route) => {
    if (route.request().method() !== 'POST') return route.continue()
    const command = route.request().postDataJSON(),
      response = await route.fetch({ timeout: 30_000 }),
      body = await response.json()
    upstreamStatus = response.status()
    requests.push({ path: '/api/v1/memories/forget', command, status: upstreamStatus, body })
    await writeFile(
      join(evidence, 'upstream-forget.json'),
      JSON.stringify({ command, status: upstreamStatus, body }, null, 2)
    )
    expect(upstreamStatus).toBe(200)
    expect(body.data.ref.fact_id).toBe(factId)
    expect(body.data.status).toBe('FORGOTTEN')
    expect(body.data.revision).toBe(revision + 1)
    await route.abort('connectionreset')
  })
  try {
    await page.goto('/login?redirect=/admin/memory')
    await page.locator('input[autocomplete="organization"]').fill(required('E2E_TENANT_SLUG'))
    await page.locator('input[autocomplete="username"]').fill('runtime-e2e-admin')
    await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
    await page.getByRole('button', { name: '登录', exact: true }).click()
    await expect(page).toHaveURL(/\/admin\/memory$/)
    await page.getByRole('textbox', { name: '搜索记忆', exact: true }).fill(query)
    const found = page.waitForResponse((response) => {
      const url = new URL(response.url())
      return url.pathname === '/api/v1/memories/page' && url.searchParams.get('q') === query
    })
    await page.getByRole('button', { name: '查询', exact: true }).click()
    const foundResponse = await found,
      foundBody = await foundResponse.json()
    original = foundBody.data.items.find(
      (item: { ref: { fact_id: string } }) => item.ref.fact_id === factId
    )
    expect(original).toMatchObject({ ref: { fact_id: factId }, status: 'ACTIVE', revision })
    const row = page.getByRole('row').filter({ hasText: text })
    await row.getByRole('button', { name: '遗忘', exact: true }).click()
    await page
      .getByRole('dialog', { name: '遗忘记忆', exact: true })
      .getByRole('button', { name: '遗忘记忆', exact: true })
      .click()
    await expect.poll(() => upstreamStatus).toBe(200)
    await expect.poll(() => writes.filter((path) => path.endsWith('/forget')).length).toBe(1)
    await page.screenshot({ path: join(evidence, 'unknown.png'), fullPage: true })
    if (mode === 'baseline') {
      await expect(row.getByRole('button', { name: '遗忘', exact: true })).toBeEnabled()
    } else {
      await expect(page.getByText('遗忘结果尚未确认', { exact: false })).toBeVisible()
      await expect(row.getByRole('button', { name: '遗忘', exact: true })).toBeDisabled()
      let checks = 0
      await page.route('**/api/v1/memories/detail?**', async (route) => {
        const response = await route.fetch(),
          actual = await response.json()
        checks++
        requests.push({ path: '/api/v1/memories/detail', actual, controlledStale: checks === 1 })
        if (checks === 1) return route.fulfill({ response, json: { ...actual, data: original } })
        await route.fulfill({ response })
      })
      const check = page.getByRole('button', { name: '核对最新状态', exact: true })
      await check.click()
      await expect.poll(() => checks).toBe(1)
      await expect(page.getByText('遗忘结果尚未确认', { exact: false })).toBeVisible()
      await expect(row.getByRole('button', { name: '遗忘', exact: true })).toBeDisabled()
      await check.click()
      await expect.poll(() => checks).toBe(2)
      await expect(row).toContainText('已遗忘')
      await expect(row.getByRole('button', { name: '恢复', exact: true })).toBeEnabled()
      await page.screenshot({ path: join(evidence, 'checked.png'), fullPage: true })
    }
    expect(writes.filter((path) => path.endsWith('/forget'))).toHaveLength(1)
    expect(errors).toEqual([])
    await writeFile(
      join(evidence, 'browser-result.json'),
      JSON.stringify({ mode, factId, writes }, null, 2)
    )
  } finally {
    await writeFile(
      join(evidence, 'http.json'),
      JSON.stringify({ requests, errors, writes, upstreamStatus }, null, 2)
    )
    await writeFile(join(evidence, 'final.html'), await page.locator('body').innerHTML())
    await page.screenshot({ path: join(evidence, 'final.png'), fullPage: true })
  }
})
