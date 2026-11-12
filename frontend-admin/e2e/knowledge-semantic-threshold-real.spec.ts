import { test, expect } from '@playwright/test'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'

const required = (key: string) => {
  const value = process.env[key]
  if (!value) throw new Error(`Missing ${key}`)
  return value
}

test('健康语义检索的阈值排空不得变成关键词命中', async ({ page }) => {
  const candidate = process.env.E2E_SEMANTIC_THRESHOLD === 'candidate'
  const evidence = required('E2E_ARTIFACT_DIR')
  const kb = required('E2E_KB_ID')
  await page.goto(`/login?redirect=${encodeURIComponent('/admin/knowledge/' + kb)}`)
  await page.locator('input[autocomplete="organization"]').fill(required('E2E_TENANT_SLUG'))
  await page.locator('input[autocomplete="username"]').fill(required('E2E_USERNAME'))
  await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL(new RegExp(`/admin/knowledge/${kb}$`))
  await page.getByRole('tab', { name: '检索验证', exact: true }).click()
  await page.getByLabel('检索问题', { exact: true }).fill(required('E2E_QUERY'))
  await page.getByRole('switch', { name: '仅本次检索覆盖参数', exact: true }).locator('..').click()
  await page
    .locator('.el-form-item')
    .filter({ hasText: '本次检索方式' })
    .locator('.el-select__wrapper')
    .click()
  await page.getByRole('option', { name: '语义检索', exact: true }).click()
  await page
    .locator('.el-form-item')
    .filter({ hasText: '本次相关度阈值' })
    .locator('.el-select__wrapper')
    .click()
  await page.getByRole('option', { name: '开启', exact: true }).click()
  const samples = []
  for (const pinned of [false, true]) {
    if (pinned)
      await page
        .getByRole('textbox', { name: '本次来源版本', exact: true })
        .fill(required('E2E_SOURCE_VERSION'))
    for (const threshold of [0.1, 0.9]) {
      await page
        .getByRole('spinbutton', { name: '本次阈值数值', exact: true })
        .fill(String(threshold))
      const pending = page.waitForResponse(
        (response) =>
          new URL(response.url()).pathname === '/api/v1/knowledge/search' &&
          response.request().method() === 'POST'
      )
      await page.getByRole('button', { name: '检索', exact: true }).click()
      const response = await pending
      expect(response.status()).toBe(200)
      const request = response.request().postDataJSON()
      expect(request.retrieval_mode).toBe('semantic')
      expect(request.score_threshold_enabled).toBe(true)
      expect(request.score_threshold).toBe(threshold)
      expect(request.source_version).toBe(pinned ? required('E2E_SOURCE_VERSION') : undefined)
      const body = await response.json()
      const empty = candidate && threshold === 0.9
      expect(body.data.items).toHaveLength(empty ? 0 : 1)
      if (empty)
        await expect(page.getByText('本次没有召回结果，请检查文档状态或调整问题')).toBeVisible()
      else {
        expect(body.data.items[0].source_version).toBe(required('E2E_SOURCE_VERSION'))
        expect(body.data.items[0].content).toContain(required('E2E_QUERY'))
        if (threshold === 0.1) expect(body.data.items[0].score).toBeCloseTo(0.2, 3)
        else expect(body.data.items[0].score).toBe(1)
        await expect(page.getByText('本次召回 1 条结果，请核对内容和来源。')).toBeVisible()
      }
      samples.push({
        pinned,
        threshold,
        request,
        response: body,
        visible: await page.locator('body').innerText()
      })
      await page.screenshot({
        path: join(evidence, `semantic-${pinned ? 'pinned' : 'current'}-${threshold}.png`),
        fullPage: true
      })
      await writeFile(
        join(evidence, 'semantic-searches.json'),
        JSON.stringify({ candidate, samples }, null, 2)
      )
    }
  }
})
