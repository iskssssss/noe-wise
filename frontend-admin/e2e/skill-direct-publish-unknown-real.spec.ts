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
test('观察已保存技能直接发布的回执丢失', async ({ page }) => {
  test.setTimeout(120_000)
  const evidence = required('E2E_ARTIFACT_DIR')
  const definition = required('E2E_SKILL_DEFINITION')
  const mode = required('E2E_SKILL_DIRECT_PUBLISH')
  expect(['baseline', 'candidate']).toContain(mode)
  const requests: { method: string; path: string; status?: number }[] = []
  let publishes = 0
  let upstream = 0
  let interrupted = false
  let originalCommand: unknown
  let originalData: unknown
  page.on('request', (request) => {
    if (
      request.method() === 'POST' &&
      new URL(request.url()).pathname === '/api/v1/capabilities/skill-drafts/publish'
    )
      publishes++
  })
  page.on('response', (response) => {
    const url = new URL(response.url())
    if (url.pathname.startsWith('/api/v1/capabilities/'))
      requests.push({
        method: response.request().method(),
        path: url.pathname + url.search,
        status: response.status()
      })
  })
  await page.route(
    '**/api/v1/capabilities/skill-drafts/publish',
    async (route) => {
      const result = await route.fetch()
      upstream = result.status()
      const command = route.request().postDataJSON()
      const response = await result.json()
      originalCommand = command
      originalData = response.data
      await writeFile(
        join(evidence, 'upstream.json'),
        JSON.stringify({ status: upstream, command, response }, null, 2)
      )
      expect(upstream).toBe(200)
      expect(String(command.draft_id)).toBe(required('E2E_SKILL_DRAFT'))
      expect(String(command.revision)).toBe(required('E2E_SKILL_REVISION'))
      expect(String(command.definition_revision)).toBe(required('E2E_SKILL_DEFINITION_REVISION'))
      await route.abort('failed')
      interrupted = true
    },
    { times: 1 }
  )
  try {
    await page.goto(`/login?redirect=${encodeURIComponent('/admin/skills/' + definition)}`)
    await page.locator('input[autocomplete="organization"]').fill(required('E2E_TENANT_SLUG'))
    await page.locator('input[autocomplete="username"]').fill('runtime-e2e-admin')
    await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
    await page.getByRole('button', { name: '登录', exact: true }).click()
    const publish = page.getByRole('button', { name: '发布', exact: true })
    await expect(publish).toBeEnabled()
    await expect(page.getByRole('button', { name: '保存并发布', exact: true })).toHaveCount(0)
    await publish.click()
    await page.getByRole('button', { name: '发布技能', exact: true }).click()
    await expect.poll(() => upstream).toBe(200)
    await expect.poll(() => publishes).toBe(1)
    await expect.poll(() => interrupted).toBe(true)
    await page.waitForTimeout(300)
    await writeFile(
      join(evidence, 'button-state.json'),
      JSON.stringify(
        { publishEnabled: await publish.isEnabled(), publishCount: await publish.count() },
        null,
        2
      )
    )
    await page.screenshot({ path: join(evidence, 'after-lost-receipt.png'), fullPage: true })
    await writeFile(join(evidence, 'page.html'), await page.locator('body').innerHTML())
    await writeFile(join(evidence, 'page.txt'), await page.locator('body').innerText())
    if (mode === 'candidate') {
      await expect(page.getByText('发布结果尚未确认', { exact: false })).toBeVisible()
      await expect(page.getByRole('button', { name: '保存草稿', exact: true })).toBeDisabled()
      await expect(publish).toBeDisabled()
      await page.getByRole('tab', { name: '发布设置' }).click()
      await expect(
        page.locator('.el-form-item').filter({ hasText: '版本号' }).locator('input')
      ).toBeDisabled()
      await page.reload()
      await expect(page.getByText('发布结果尚未确认', { exact: false })).toBeVisible()
      await expect(page.getByRole('button', { name: '保存草稿', exact: true })).toBeDisabled()
      expect(publishes).toBe(1)
      const continued = page.waitForResponse(
        (response) =>
          response.request().method() === 'POST' &&
          new URL(response.url()).pathname === '/api/v1/capabilities/skill-drafts/publish'
      )
      await page.getByRole('button', { name: '继续原发布', exact: true }).click()
      const response = await continued
      const command = response.request().postDataJSON()
      const data = (await response.json()).data
      await writeFile(
        join(evidence, 'continued.json'),
        JSON.stringify({ status: response.status(), command, data }, null, 2)
      )
      expect(response.status()).toBe(200)
      expect(command).toEqual(originalCommand)
      expect(data.version_id).toBe((originalData as { version_id: string }).version_id)
      expect(data.definition_revision).toBe(
        (originalData as { definition_revision: number }).definition_revision
      )
      expect(publishes).toBe(2)
      await expect(page.getByText('发布结果尚未确认', { exact: false })).toHaveCount(0)
      await page.screenshot({ path: join(evidence, 'after-continuation.png'), fullPage: true })
    }
  } finally {
    await writeFile(
      join(evidence, 'browser-facts.json'),
      JSON.stringify({ mode, upstream, publishes, requests }, null, 2)
    )
  }
})
