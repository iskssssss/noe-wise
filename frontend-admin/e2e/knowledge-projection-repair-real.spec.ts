import { expect, test } from '@playwright/test'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'

function required(name: string): string {
  const value = process.env[name]
  if (!value) throw Error(`缺少知识投影验收参数：${name}`)
  return value
}

test.describe.configure({ retries: 0 })
test.use({ trace: 'off', video: 'off', screenshot: 'off' })
test('已发布文档可按原来源修复检索投影', async ({ page }) => {
  test.setTimeout(150_000)
  const mode = required('E2E_KNOWLEDGE_PROJECTION_REPAIR')
  expect(['baseline', 'candidate']).toContain(mode)
  const evidence = required('E2E_ARTIFACT_DIR')
  const kb = required('E2E_KB_ID')
  const documentId = required('E2E_DOCUMENT_ID')
  const source = required('E2E_SOURCE_VERSION')
  const title = required('E2E_DOCUMENT_TITLE')
  const network: { path: string; status: number; method: string }[] = []
  const errors: string[] = []
  let posts = 0
  let opened = false
  page.on('pageerror', (error) => errors.push(error.message))
  page.on('request', (request) => {
    if (
      request.method() === 'POST' &&
      new URL(request.url()).pathname === '/api/v1/knowledge/bases/projections/repair'
    )
      posts++
  })
  page.on('response', (response) => {
    const url = new URL(response.url())
    if (url.pathname.startsWith('/api/v1/knowledge/'))
      network.push({
        path: url.pathname + url.search,
        status: response.status(),
        method: response.request().method()
      })
  })
  try {
    await page.goto(`/login?redirect=${encodeURIComponent('/admin/knowledge/' + kb)}`)
    await page.locator('input[autocomplete="organization"]').fill(required('E2E_TENANT_SLUG'))
    await page.locator('input[autocomplete="username"]').fill('runtime-e2e-admin')
    await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
    await page.getByRole('button', { name: '登录', exact: true }).click()
    const row = page.getByRole('row').filter({ hasText: title })
    await expect(row).toBeVisible()
    opened = true
    const button = row.getByRole('button', { name: '修复检索投影', exact: true })
    if (mode === 'baseline') {
      await expect(button).toHaveCount(0)
      expect(posts).toBe(0)
    } else {
      await expect(button).toBeVisible()
      const repaired = page.waitForResponse(
        (response) =>
          response.request().method() === 'POST' &&
          new URL(response.url()).pathname === '/api/v1/knowledge/bases/projections/repair'
      )
      await button.click()
      const confirmation = page.getByRole('dialog', { name: '修复检索投影', exact: true })
      if (await confirmation.isVisible())
        await confirmation.getByRole('button', { name: '修复检索投影', exact: true }).click()
      const response = await repaired
      const request = response.request().postDataJSON()
      const body = await response.json()
      await writeFile(
        join(evidence, 'browser-repair.json'),
        JSON.stringify({ status: response.status(), request, body }, null, 2)
      )
      expect(response.status()).toBe(200)
      expect(String(request.kb_id)).toBe(kb)
      expect(request.source_version).toBe(source)
      expect(String(body.data.kb_id)).toBe(kb)
      expect(body.data.source_version).toBe(source)
      expect(body.data.chunk_count).toBeGreaterThan(0)
      expect(posts).toBe(1)
      await expect(page.getByText(/检索投影.*(完成|成功)/).first()).toBeVisible()
    }
    expect(errors).toEqual([])
    await writeFile(
      join(evidence, 'browser-result.json'),
      JSON.stringify({ mode, kb, documentId, source, posts, network }, null, 2)
    )
  } finally {
    await writeFile(
      join(evidence, 'browser-state.json'),
      JSON.stringify({ mode, kb, documentId, source, posts, errors, network }, null, 2)
    )
    if (opened) {
      await writeFile(join(evidence, 'document.html'), await page.locator('body').innerHTML())
      await page.screenshot({ path: join(evidence, `projection-${mode}.png`), fullPage: true })
    }
  }
})
