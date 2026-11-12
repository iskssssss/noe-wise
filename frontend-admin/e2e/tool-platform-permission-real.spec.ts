import { expect, test } from '@playwright/test'
import { mkdir, writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { randomUUID } from 'node:crypto'

function required(name: string) {
  const value = process.env[name]
  if (!value) throw Error(`缺少隔离验收参数：${name}`)
  return value
}

test.describe.configure({ retries: 0 })
test.use({ trace: 'off', video: 'off', screenshot: 'off' })
test('平台工具部署不依赖连接器读取权限', async ({ page }) => {
  test.setTimeout(150_000)
  const mode = process.env.E2E_TOOL_PLATFORM_PERMISSION ?? 'candidate'
  expect(['baseline', 'candidate']).toContain(mode)
  const evidence = required('E2E_ARTIFACT_DIR')
  await mkdir(evidence, { recursive: true })
  const network: { path: string; status: number; method: string }[] = []
  let browserPosts = 0
  let apiPosts = 0
  let key = ''
  let loggedIn = false
  page.on('response', (response) => {
    const url = new URL(response.url())
    if (/^\/api\/v1\/(capabilities|connectors)(\/|$)/.test(url.pathname))
      network.push({
        path: url.pathname + url.search,
        status: response.status(),
        method: response.request().method()
      })
  })
  page.on('request', (request) => {
    if (
      request.method() === 'POST' &&
      new URL(request.url()).pathname === '/api/v1/capabilities/deployments/create-with-receipt'
    )
      browserPosts++
  })
  try {
    await page.goto('/login?redirect=%2Fadmin%2Ftool-deployments')
    await page.locator('input[autocomplete="organization"]').fill(required('E2E_TENANT_SLUG'))
    await page.locator('input[autocomplete="username"]').fill('tool-limited')
    await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
    await page.getByRole('button', { name: '登录', exact: true }).click()
    await expect(page).toHaveURL(/\/admin\/tool-deployments$/)
    loggedIn = true
    const drawer = page.getByRole('dialog', { name: '新增工具部署' })
    await page.getByRole('button', { name: '新增部署', exact: true }).click()
    if (mode === 'baseline') {
      await expect
        .poll(() =>
          network.some(
            (item) => item.path === '/api/v1/connectors/instances' && item.status === 403
          )
        )
        .toBe(true)
      await expect(drawer).toHaveCount(0)
      await page.screenshot({ path: join(evidence, 'blocked.png'), fullPage: true })
      const payload = {
        tool_version_id: required('E2E_TOOL_VERSION'),
        provider_type: 'PLATFORM',
        provider_ref: 'get_current_time',
        environment: 'DEVELOPMENT'
      }
      const request = { request_id: randomUUID(), payload }
      apiPosts++
      const response = await page.request.post(
        '/api/v1/capabilities/deployments/create-with-receipt',
        {
          data: request,
          headers: { Authorization: `Bearer ${required('E2E_LIMITED_TOKEN')}` },
          timeout: 20_000
        }
      )
      const body = await response.json()
      await writeFile(
        join(evidence, 'installation.json'),
        JSON.stringify(
          {
            source: 'limited-user-api-control',
            status: response.status(),
            request,
            response: body
          },
          null,
          2
        )
      )
      expect(response.status()).toBe(200)
      expect(body.data.status).toBe('SUCCEEDED')
      key = String(body.data.deployment_key)
    } else {
      await expect(drawer).toBeVisible()
      await drawer.getByLabel('工具（跟随当前发布）', { exact: true }).click()
      await page.getByRole('option', { name: 'get_current_time', exact: true }).click()
      await drawer
        .getByLabel('提供方', { exact: true })
        .locator(
          'xpath=ancestor::div[contains(concat(" ",normalize-space(@class)," ")," el-select__wrapper ")][1]'
        )
        .click()
      await page.getByRole('option', { name: '平台内置工具', exact: true }).click()
      await expect(drawer.getByLabel('提供方引用', { exact: true })).toContainText(
        'get_current_time'
      )
      const created = page.waitForResponse(
        (response) =>
          response.request().method() === 'POST' &&
          new URL(response.url()).pathname ===
            '/api/v1/capabilities/deployments/create-with-receipt'
      )
      await drawer.getByRole('button', { name: '创建', exact: true }).click()
      const response = await created
      const body = await response.json()
      const request = response.request().postDataJSON()
      await writeFile(
        join(evidence, 'installation.json'),
        JSON.stringify(
          { source: 'limited-user-browser', status: response.status(), request, response: body },
          null,
          2
        )
      )
      expect(response.status()).toBe(200)
      expect(body.data.status).toBe('SUCCEEDED')
      expect(request.payload.provider_type).toBe('PLATFORM')
      expect(request.payload.provider_ref).toBe('get_current_time')
      key = String(body.data.deployment_key)
      await expect(page.getByRole('row').filter({ hasText: key })).toBeVisible()
      await page.screenshot({ path: join(evidence, 'created.png'), fullPage: true })
    }
    expect(key).toMatch(/^tool-/)
    expect(browserPosts).toBe(mode === 'candidate' ? 1 : 0)
    expect(apiPosts).toBe(mode === 'baseline' ? 1 : 0)
    await writeFile(
      join(evidence, 'receipt.json'),
      JSON.stringify({ mode, key, browserPosts, apiPosts, network }, null, 2)
    )
  } finally {
    await writeFile(
      join(evidence, 'state.json'),
      JSON.stringify({ mode, key, browserPosts, apiPosts, network }, null, 2)
    )
    if (loggedIn) await page.screenshot({ path: join(evidence, 'final.png'), fullPage: true })
  }
})
