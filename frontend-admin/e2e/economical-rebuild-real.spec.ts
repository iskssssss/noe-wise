import { expect, test } from '@playwright/test'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'

function required(name: string): string {
  const value = process.env[name]
  if (!value) throw new Error(`缺少隔离验收参数：${name}`)
  return value
}

test.describe.configure({ retries: 0 })

test('经济索引不允许向量重建', async ({ page }) => {
  const kb = required('E2E_KNOWLEDGE_ID')
  let createPosts = 0
  page.on('request', (request) => {
    if (
      request.method() === 'POST' &&
      new URL(request.url()).pathname === '/api/v1/knowledge/bases/rebuilds/create'
    )
      createPosts++
  })
  await page.goto(`/login?redirect=${encodeURIComponent('/admin/knowledge/' + kb)}`)
  await page.locator('input[autocomplete="organization"]').fill(required('E2E_TENANT_SLUG'))
  await page.locator('input[autocomplete="username"]').fill(required('E2E_USERNAME'))
  await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL(new RegExp(`/admin/knowledge/${kb}$`))
  await page.getByRole('tab', { name: '索引与模型' }).click()
  const panel = page.getByRole('tabpanel', { name: '索引与模型' })
  await expect(
    panel.getByText('当前为经济索引，仅支持全文检索，不支持更换向量模型或发起向量重建。')
  ).toBeVisible()
  await panel
    .locator('.el-form-item')
    .filter({ hasText: '目标向量模型' })
    .locator('.el-select__wrapper')
    .click()
  await page.getByRole('option', { name: required('E2E_MODEL_NAME'), exact: true }).click()
  await expect(panel.locator('.el-form-item').filter({ hasText: '目标向量模型' })).toContainText(
    required('E2E_MODEL_NAME')
  )
  const start = panel.getByRole('button', { name: '开始重建', exact: true })
  if (await start.count()) await expect(start).toBeDisabled()
  expect(createPosts).toBe(0)
  await page.screenshot({
    path: join(required('E2E_ARTIFACT_DIR'), 'economical-rebuild-blocked.png'),
    fullPage: true
  })
  await writeFile(
    join(required('E2E_ARTIFACT_DIR'), 'economical-rebuild.json'),
    JSON.stringify({ kbId: kb, createPosts })
  )
})
