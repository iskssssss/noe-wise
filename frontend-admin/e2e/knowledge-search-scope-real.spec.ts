import { expect, test } from '@playwright/test'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'

function required(name: string) {
  const value = process.env[name]
  if (!value) throw Error(`缺少隔离参数：${name}`)
  return value
}
test.use({ trace: 'off', video: 'off', screenshot: 'off' })
test('管理检索准确提示未发布预览与发布范围', async ({ page }) => {
  const stage = required('E2E_KNOWLEDGE_SCOPE_STAGE')
  const mode = required('E2E_KNOWLEDGE_SCOPE_MODE')
  const kb = required('E2E_KB_ID')
  const sentinel = required('E2E_SENTINEL')
  const source = required('E2E_SOURCE_VERSION')
  const evidence = required('E2E_ARTIFACT_DIR')
  let facts: Record<string, unknown> = { stage, mode }
  try {
    await page.goto(`/login?redirect=${encodeURIComponent('/admin/knowledge/' + kb)}`)
    await page.locator('input[autocomplete="organization"]').fill(required('E2E_TENANT_SLUG'))
    await page.locator('input[autocomplete="username"]').fill('runtime-e2e-admin')
    await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
    const detail = page.waitForResponse(
      (response) =>
        new URL(response.url()).pathname === '/api/v1/knowledge/bases/detail' && response.ok()
    )
    await page.getByRole('button', { name: '登录', exact: true }).click()
    const base = (await (await detail).json()).data
    expect(Boolean(base.active_revision_id)).toBe(stage === 'published')
    await expect(
      page.getByText(stage === 'unpublished' ? '尚未发布' : /当前发布/).first()
    ).toBeVisible()
    await page.getByRole('tab', { name: '检索验证', exact: true }).click()
    await page
      .getByRole('switch', { name: '仅本次检索覆盖参数', exact: true })
      .locator('..')
      .click()
    await page.getByLabel('检索问题', { exact: true }).fill(sentinel)
    const searched = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        new URL(response.url()).pathname === '/api/v1/knowledge/search'
    )
    await page.getByRole('button', { name: '检索', exact: true }).click()
    const response = await searched
    expect(response.status()).toBe(200)
    const payload = response.request().postDataJSON()
    const body = await response.json()
    expect(payload.source_version).toBeUndefined()
    expect(
      body.data.items.some(
        (item: { source_version: string; content: string }) =>
          item.source_version === source && item.content.includes(sentinel)
      )
    ).toBe(true)
    const text = await page.locator('body').innerText()
    facts = {
      stage,
      mode,
      baseActiveRevision: base.active_revision_id,
      request: payload,
      body,
      text
    }
    await page.screenshot({ path: join(evidence, `${stage}.png`), fullPage: true })
    if (mode === 'candidate') {
      const scope = page.getByTestId('knowledge-search-scope')
      await expect(scope).toContainText(stage === 'unpublished' ? '未发布' : '已发布')
      if (stage === 'unpublished') await expect(scope).toContainText('预览')
    }
  } finally {
    await writeFile(join(evidence, `${stage}.json`), JSON.stringify(facts, null, 2))
  }
})
