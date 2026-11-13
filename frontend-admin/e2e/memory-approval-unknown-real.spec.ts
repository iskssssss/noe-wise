import { expect, test } from '@playwright/test'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'

function required(name: string) {
  const value = process.env[name]
  if (!value) throw Error(`缺少记忆验收参数：${name}`)
  return value
}
test.describe.configure({ retries: 0 })
test.use({ trace: 'off', video: 'off', screenshot: 'off' })
test('观察记忆批准成功后回执丢失', async ({ page }) => {
  test.setTimeout(120_000)
  const evidence = required('E2E_ARTIFACT_DIR')
  const mode = required('E2E_MEMORY_APPROVAL_UNKNOWN')
  expect(['baseline', 'candidate']).toContain(mode)
  const factId = required('E2E_MEMORY_FACT_ID')
  const revision = Number(required('E2E_MEMORY_REVISION'))
  const query = required('E2E_MEMORY_QUERY')
  const text = required('E2E_MEMORY_TEXT')
  const network: object[] = []
  const errors: string[] = []
  let writes = 0,
    upstream = 0,
    interrupted = false,
    opened = false
  let checks = 0
  page.on('pageerror', (error) => errors.push(error.message))
  page.on('request', (request) => {
    if (
      request.method() === 'POST' &&
      new URL(request.url()).pathname === '/api/v1/memories/review'
    )
      writes++
  })
  page.on('response', (response) => {
    const url = new URL(response.url())
    if (url.pathname.startsWith('/api/v1/memories/'))
      network.push({
        path: url.pathname + url.search,
        method: response.request().method(),
        status: response.status()
      })
  })
  await page.route(
    '**/api/v1/memories/review',
    async (route) => {
      const response = await route.fetch()
      upstream = response.status()
      const command = route.request().postDataJSON(),
        body = await response.json()
      await writeFile(
        join(evidence, 'upstream.json'),
        JSON.stringify({ status: upstream, command, body }, null, 2)
      )
      expect(command).toEqual({
        fact_id: factId,
        payload: { expected_revision: revision, approved: true, confirmed_scope: 'USER' }
      })
      expect(upstream).toBe(200)
      expect(body.data).toMatchObject({ status: 'ACTIVE', scope: 'USER', revision: revision + 1 })
      await route.abort('failed')
      interrupted = true
    },
    { times: 1 }
  )
  try {
    await page.goto('/login?redirect=/admin/memory')
    await page.locator('input[autocomplete="organization"]').fill(required('E2E_TENANT_SLUG'))
    await page.locator('input[autocomplete="username"]').fill('runtime-e2e-admin')
    await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
    await page.getByRole('button', { name: '登录', exact: true }).click()
    await expect(page).toHaveURL(/\/admin\/memory$/)
    opened = true
    await page.getByRole('textbox', { name: '搜索记忆', exact: true }).fill(query)
    const searched = page.waitForResponse((response) => {
      const url = new URL(response.url())
      return url.pathname === '/api/v1/memories/page' && url.searchParams.get('q') === query
    })
    await page.getByRole('button', { name: '查询', exact: true }).click()
    const search = await searched
    const initial = await search.json()
    await writeFile(
      join(evidence, 'before.json'),
      JSON.stringify({ status: search.status(), body: initial }, null, 2)
    )
    expect(search.status()).toBe(200)
    const original = initial.data.items.find(
      (item: { ref: { fact_id: string } }) => item.ref.fact_id === factId
    )
    expect(original).toMatchObject({ status: 'PENDING_CONFIRM', scope: 'USER', revision })
    const row = page.getByRole('row').filter({ hasText: text })
    await expect(row).toBeVisible()
    await row.getByRole('button', { name: '批准', exact: true }).click()
    const dialog = page.getByRole('dialog', { name: '确认跨助手共享', exact: true })
    await expect(dialog).toContainText(text)
    await dialog.getByRole('button', { name: '确认跨助手共享', exact: true }).click()
    await expect.poll(() => interrupted).toBe(true)
    expect(writes).toBe(1)
    await page.waitForTimeout(300)
    const approve = row.getByRole('button', { name: '批准', exact: true })
    const approveCount = await approve.count()
    await writeFile(
      join(evidence, 'observation.json'),
      JSON.stringify(
        {
          writes,
          approveCount,
          approveEnabled: approveCount ? await approve.isEnabled() : null,
          row: await row.innerText(),
          page: await page.locator('body').innerText()
        },
        null,
        2
      )
    )
    await page.screenshot({
      path: join(evidence, 'after-approval-response-loss.png'),
      fullPage: true
    })
    if (mode === 'candidate') {
      await expect(page.getByText('批准结果尚未确认', { exact: false })).toBeVisible()
      await expect(approve).toBeDisabled()
      await page.reload()
      await expect(page.getByText('批准结果尚未确认', { exact: false })).toBeVisible()
      expect(writes).toBe(1)
      await page.getByRole('textbox', { name: '搜索记忆', exact: true }).fill(query)
      const searchedAgain = page.waitForResponse((response) => {
        const url = new URL(response.url())
        return url.pathname === '/api/v1/memories/page' && url.searchParams.get('q') === query
      })
      await page.getByRole('button', { name: '查询', exact: true }).click()
      expect((await searchedAgain).status()).toBe(200)
      await expect(row).toBeVisible()
      await expect(row.getByRole('button', { name: '纠正', exact: true })).toBeDisabled()
      await expect(row.getByRole('button', { name: '遗忘', exact: true })).toBeDisabled()
      await page.route('**/api/v1/memories/detail?**', async (route) => {
        const response = await route.fetch(),
          actual = await response.json()
        checks++
        await writeFile(
          join(evidence, `detail-${checks}.json`),
          JSON.stringify(
            { status: response.status(), actual, controlledStale: checks === 1 },
            null,
            2
          )
        )
        expect(response.status()).toBe(200)
        expect(actual.data).toMatchObject({ status: 'ACTIVE', revision: revision + 1 })
        if (checks === 1) return route.fulfill({ response, json: { ...actual, data: original } })
        await route.fulfill({ response })
      })
      const check = page.getByRole('button', { name: '核对最新状态', exact: true })
      await check.click()
      await expect.poll(() => checks).toBe(1)
      await expect(page.getByText('批准结果尚未确认', { exact: false })).toBeVisible()
      await expect(row.getByRole('button', { name: '纠正', exact: true })).toBeDisabled()
      await expect(check).toBeEnabled()
      await check.click()
      await expect.poll(() => checks).toBe(2)
      await expect(page.getByText('批准结果尚未确认', { exact: false })).toHaveCount(0)
      await expect(row.getByRole('button', { name: '纠正', exact: true })).toBeEnabled()
      await page.screenshot({ path: join(evidence, 'checked.png'), fullPage: true })
      expect(writes).toBe(1)
    }
    expect(errors).toEqual([])
  } finally {
    await writeFile(
      join(evidence, 'browser-state.json'),
      JSON.stringify(
        { mode, factId, revision, writes, upstream, interrupted, checks, errors, network },
        null,
        2
      )
    )
    if (opened) {
      await writeFile(join(evidence, 'page.html'), await page.locator('body').innerHTML())
      await page.screenshot({ path: join(evidence, 'final-page.png'), fullPage: true })
    }
  }
})
