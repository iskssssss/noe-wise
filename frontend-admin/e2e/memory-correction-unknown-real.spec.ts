import { expect, test } from '@playwright/test'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'

function required(name: string): string {
  const value = process.env[name]
  if (!value) throw new Error(`缺少记忆纠正验收参数：${name}`)
  return value
}

test.describe.configure({ retries: 0 })
test.use({ trace: 'off', video: 'off', screenshot: 'off' })
test('纠正回执丢失后保留草稿并只读核对原事实', async ({ page }) => {
  test.setTimeout(150_000)
  const mode = required('E2E_MEMORY_CORRECTION_UNKNOWN')
  expect(['baseline', 'candidate']).toContain(mode)
  const evidence = required('E2E_ARTIFACT_DIR')
  const factId = required('E2E_MEMORY_FACT_ID')
  const query = required('E2E_MEMORY_QUERY')
  const oldText = required('E2E_MEMORY_OLD_TEXT')
  const newText = required('E2E_MEMORY_NEW_TEXT')
  const originalRevision = Number(required('E2E_MEMORY_REVISION'))
  const requests: unknown[] = []
  const errors: string[] = []
  const writes: string[] = []
  let replacement: Record<string, unknown> | undefined
  let upstreamStatus = 0
  let opened = false
  page.on('pageerror', (error) => errors.push(error.message))
  page.on('request', (request) => {
    const path = new URL(request.url()).pathname
    if (request.method() === 'POST' && path.startsWith('/api/v1/memories/')) writes.push(path)
  })
  await page.route('**/api/v1/memories/correct', async (route) => {
    if (route.request().method() !== 'POST') return route.continue()
    const command = route.request().postDataJSON()
    const upstream = await route.fetch({ timeout: 30_000 })
    upstreamStatus = upstream.status()
    const body = await upstream.json()
    requests.push({ path: '/api/v1/memories/correct', command, status: upstreamStatus, body })
    await writeFile(
      join(evidence, 'original-response.json'),
      JSON.stringify({ command, status: upstreamStatus, body }, null, 2)
    )
    expect(upstreamStatus).toBe(200)
    expect(body.data.status).toBe('ACTIVE')
    expect(body.data.derived_from.fact_id).toBe(factId)
    replacement = body.data
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
    const searched = page.waitForResponse((response) => {
      const url = new URL(response.url())
      return url.pathname === '/api/v1/memories/page' && url.searchParams.get('q') === query
    })
    await page.getByRole('button', { name: '查询', exact: true }).click()
    const search = await searched
    expect(search.status()).toBe(200)
    const initial = await search.json()
    requests.push({ path: '/api/v1/memories/page', status: search.status(), body: initial })
    const initialFact = initial.data.items.find(
      (item: { ref: { fact_id: string } }) => item.ref.fact_id === factId
    )
    expect(initialFact.status).toBe('ACTIVE')
    expect(initialFact.revision).toBe(originalRevision)
    const row = page.getByRole('row').filter({ hasText: oldText })
    await row.getByRole('button', { name: '纠正', exact: true }).click()
    const drawer = page.getByRole('dialog', { name: '纠正记忆', exact: true })
    opened = true
    const input = drawer.getByRole('textbox', { name: '记忆内容', exact: true })
    await expect(input).toHaveValue(oldText)
    await input.fill(newText)
    await drawer.getByRole('button', { name: '保存纠正', exact: true }).click()
    await expect.poll(() => upstreamStatus).toBe(200)
    await expect.poll(() => writes.filter((path) => path.endsWith('/correct')).length).toBe(1)
    await expect(input).toHaveValue(newText)
    const save = drawer.getByRole('button', { name: '保存纠正', exact: true })
    if (mode === 'baseline') {
      await expect(save).toBeEnabled()
      await page.screenshot({ path: join(evidence, 'unknown-baseline.png'), fullPage: true })
    } else {
      await expect(
        drawer.getByText('纠正结果尚未确认，请核对最新状态', { exact: false })
      ).toBeVisible()
      await expect(save).toBeDisabled()
      await page.screenshot({ path: join(evidence, 'unknown-candidate.png'), fullPage: true })
      const checked = page.waitForResponse((response) => {
        const url = new URL(response.url())
        return (
          url.pathname === '/api/v1/memories/detail' && url.searchParams.get('fact_id') === factId
        )
      })
      await drawer.getByRole('button', { name: '核对最新状态', exact: true }).click()
      const detail = await checked
      const body = await detail.json()
      requests.push({ path: '/api/v1/memories/detail', status: detail.status(), body })
      expect(detail.status()).toBe(200)
      expect(body.data.ref.fact_id).toBe(factId)
      expect(body.data.status).toBe('ARCHIVED')
      expect(body.data.revision).toBe(originalRevision + 1)
      await expect(drawer.getByText(/最新状态：/)).toBeVisible()
      await expect(input).toHaveValue(newText)
      await expect(save).toBeDisabled()
      await page.screenshot({ path: join(evidence, 'checked-candidate.png'), fullPage: true })
      page.once('dialog', (dialog) => dialog.accept())
      await page.reload()
      await page.getByRole('textbox', { name: '搜索记忆', exact: true }).fill(query)
      const restored = page.waitForResponse((response) => {
        const url = new URL(response.url())
        return url.pathname === '/api/v1/memories/page' && url.searchParams.get('q') === query
      })
      await page.getByRole('button', { name: '查询', exact: true }).click()
      const restoredResponse = await restored
      const restoredBody = await restoredResponse.json()
      requests.push({
        path: '/api/v1/memories/page',
        status: restoredResponse.status(),
        body: restoredBody
      })
      expect(restoredResponse.status()).toBe(200)
      const restoredOriginal = restoredBody.data.items.find(
        (item: { ref: { fact_id: string } }) => item.ref.fact_id === factId
      )
      const restoredReplacement = restoredBody.data.items.find(
        (item: { ref: { fact_id: string } }) =>
          item.ref.fact_id === (replacement as { ref: { fact_id: string } }).ref.fact_id
      )
      expect(restoredOriginal.status).toBe('ARCHIVED')
      expect(restoredOriginal.revision).toBe(originalRevision + 1)
      expect(restoredReplacement.status).toBe('ACTIVE')
      const restoredRow = page.getByRole('row').filter({ hasText: oldText })
      await expect(restoredRow).toContainText('已归档')
      await expect(restoredRow.getByRole('button', { name: '纠正', exact: true })).toBeDisabled()
      await expect(page.getByRole('row').filter({ hasText: newText })).toBeVisible()
      await page.screenshot({ path: join(evidence, 'reloaded-candidate.png'), fullPage: true })
    }
    expect(writes.filter((path) => path.endsWith('/correct'))).toHaveLength(1)
    expect(errors).toEqual([])
    await writeFile(
      join(evidence, 'browser-result.json'),
      JSON.stringify({ mode, factId, replacement, writes }, null, 2)
    )
  } finally {
    await writeFile(
      join(evidence, 'http.json'),
      JSON.stringify({ requests, errors, writes, upstreamStatus }, null, 2)
    )
    if (opened) {
      await writeFile(join(evidence, 'final.html'), await page.locator('body').innerHTML())
      await page.screenshot({ path: join(evidence, 'final.png'), fullPage: true })
    }
  }
})
