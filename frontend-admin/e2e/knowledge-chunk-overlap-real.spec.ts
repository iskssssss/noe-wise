import { expect, test } from '@playwright/test'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'

function required(name: string): string {
  const value = process.env[name]
  if (!value) throw new Error(`缺少知识验收参数：${name}`)
  return value
}

test.describe.configure({ retries: 0 })
test('跨分块短语与同段对照通过真实页面检索', async ({ page }) => {
  test.setTimeout(120_000)
  const evidence = required('E2E_ARTIFACT_DIR')
  const kb = required('E2E_KNOWLEDGE_ID')
  const baseline = required('E2E_KNOWLEDGE_OVERLAP') === 'baseline'
  await page.goto(`/login?redirect=${encodeURIComponent('/admin/knowledge/' + kb)}`)
  await page.locator('input[autocomplete="organization"]').fill(required('E2E_TENANT_SLUG'))
  await page.locator('input[autocomplete="username"]').fill('runtime-e2e-admin')
  await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await page.getByRole('tab', { name: '检索验证', exact: true }).click()
  for (const [label, query] of [
    ['control', '甲甲甲'],
    ['boundary', '跨段检索凭证']
  ]) {
    await page.getByLabel('检索问题', { exact: true }).fill(query)
    const responsePending = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        new URL(response.url()).pathname === '/api/v1/knowledge/search'
    )
    await page.getByRole('button', { name: '检索', exact: true }).click()
    const response = await responsePending
    const body = await response.json()
    await writeFile(
      join(evidence, `${label}-search.json`),
      JSON.stringify(
        { status: response.status(), request: response.request().postDataJSON(), response: body },
        null,
        2
      )
    )
    expect(response.status()).toBe(200)
    expect(response.request().postDataJSON()).toEqual({ kb_id: kb, query })
    await expect(page.getByText(`上次成功检索的问题：${query}`, { exact: true })).toBeVisible()
    if (label === 'boundary' && baseline) {
      expect(body.data.items).toHaveLength(0)
      await expect(
        page.getByText('本次没有召回结果，请检查文档状态或调整问题', { exact: true })
      ).toBeVisible()
    } else {
      expect(body.data.items.length).toBeGreaterThan(0)
      await expect(page.locator('.readable').filter({ hasText: query }).first()).toBeVisible()
      for (const item of body.data.items) {
        expect(item.content).toContain(query)
        expect(item.source_version).toBe(required('E2E_KNOWLEDGE_SOURCE_VERSION'))
      }
    }
    await page.screenshot({ path: join(evidence, `${label}-search.png`), fullPage: true })
  }
})
