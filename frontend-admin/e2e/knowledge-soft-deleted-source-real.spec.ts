import { expect, test } from '@playwright/test'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'

function required(name: string): string {
  const value = process.env[name]
  if (!value) throw new Error(`缺少隔离验收参数：${name}`)
  return value
}

test.describe.configure({ retries: 0 })
test('删除来源后核对同库检索', async ({ page }) => {
  test.setTimeout(120_000)
  const evidence = required('E2E_ARTIFACT_DIR')
  await page.goto(
    `/login?redirect=${encodeURIComponent('/admin/knowledge/' + required('E2E_KNOWLEDGE_ID'))}`
  )
  await page.locator('input[autocomplete="organization"]').fill(required('E2E_TENANT_SLUG'))
  await page.locator('input[autocomplete="username"]').fill('runtime-e2e-admin')
  await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
  await page.getByRole('button', { name: '登录', exact: true }).click()
  const row = page.getByRole('row').filter({ hasText: required('E2E_TARGET_TITLE') })
  await expect(row).toBeVisible()
  const deletion = page.waitForResponse(
    (response) =>
      response.request().method() === 'DELETE' &&
      new URL(response.url()).pathname === '/api/v1/knowledge/documents/delete'
  )
  await row.getByRole('button', { name: '删除', exact: true }).click()
  await page
    .getByRole('dialog', { name: '删除文档', exact: true })
    .getByRole('button', { name: '删除文档', exact: true })
    .click()
  const deleted = await deletion
  const request = deleted.request().postDataJSON()
  await writeFile(
    join(evidence, 'delete-http.json'),
    JSON.stringify({ request, status: deleted.status(), body: await deleted.json() }, null, 2)
  )
  expect(deleted.status()).toBe(200)
  expect(String(request.doc_id)).toBe(required('E2E_TARGET_ID'))
  expect(request.expected_source_version).toBe(required('E2E_TARGET_SOURCE'))
  await expect(row).toHaveCount(0)
  await page.getByRole('tab', { name: '检索验证', exact: true }).click()
  await page.getByLabel('检索问题', { exact: true }).fill(required('E2E_QUERY'))
  const searched = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      new URL(response.url()).pathname === '/api/v1/knowledge/search'
  )
  await page.getByRole('button', { name: '检索', exact: true }).click()
  const response = await searched
  const result = await response.json()
  await writeFile(
    join(evidence, 'search-http.json'),
    JSON.stringify(
      { request: response.request().postDataJSON(), status: response.status(), body: result },
      null,
      2
    )
  )
  expect(response.status()).toBe(200)
  const versions = result.data.items.map((item: { source_version: string }) => item.source_version)
  expect(versions).toContain(required('E2E_SURVIVOR_SOURCE'))
  expect(versions).not.toContain(required('E2E_TARGET_SOURCE'))
  const survivor = page.getByText(`来源版本 ${required('E2E_SURVIVOR_SOURCE')}`, {
    exact: false
  })
  await expect(survivor).toBeVisible()
  await survivor.scrollIntoViewIfNeeded()
  await page.screenshot({ path: join(evidence, 'search-after-delete.png'), fullPage: true })
})
