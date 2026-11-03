import { expect, test } from '@playwright/test'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'

function required(name: string) {
  const value = process.env[name]
  if (!value) throw Error(`Missing isolated fixture: ${name}`)
  return value
}

test('对账配置保存响应中断后的真实状态', async ({ page }) => {
  const secret = required('E2E_SYNTHETIC_SECRET')
  const endpoint = 'https://billing.example.invalid/usage'
  const mode = process.env.E2E_BILLING_RECONCILIATION_MODE ?? 'candidate'
  expect(['baseline', 'candidate', 'disconnect']).toContain(mode)
  let writes = 0
  let refreshes = 0
  let upstreamStatus = 0
  page.on('request', (request) => {
    const path = new URL(request.url()).pathname
    if (path === '/api/v1/billing/reconciliation/provider' && request.method() === 'PUT') writes++
    if (path === '/api/v1/auth/refresh' && request.method() === 'POST') refreshes++
  })
  await page.goto('/login?redirect=/admin/billing-records')
  await page.locator('input[autocomplete="organization"]').fill(required('E2E_TENANT_SLUG'))
  await page.locator('input[autocomplete="username"]').fill('runtime-e2e-admin')
  await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
  const login = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/auth/login' &&
      response.request().method() === 'POST'
  )
  await page.getByRole('button', { name: '登录', exact: true }).click()
  const loginResponse = await login
  expect(loginResponse.ok()).toBeTruthy()
  const token = (await loginResponse.json()).data.access_token as string
  await expect(page).toHaveURL(/\/admin\/billing-records$/)
  const initialRead = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/billing/reconciliation/provider' &&
      response.request().method() === 'GET'
  )
  await page.getByRole('tab', { name: '对账配置', exact: true }).click()
  const initialResponse = await initialRead
  expect(initialResponse.ok()).toBeTruthy()
  const initialConfig = (await initialResponse.json()).data
  let unsupportedStatus: number
  let unsupportedCode = ''
  let unchangedAfterUnsupported: boolean
  if (mode === 'baseline') {
    await page.getByLabel('对账提供方', { exact: true }).fill('isolated_provider')
    await page.getByLabel('对账接口 URL', { exact: true }).fill(endpoint)
    const failed = page.waitForResponse(
      (response) =>
        new URL(response.url()).pathname === '/api/v1/billing/reconciliation/provider' &&
        response.request().method() === 'PUT'
    )
    await page.getByRole('button', { name: '保存配置', exact: true }).click()
    const response = await failed
    unsupportedStatus = response.status()
    expect(unsupportedStatus).toBe(500)
    const current = await page.request.get('/api/v1/billing/reconciliation/provider', {
      headers: { Authorization: `Bearer ${token}` }
    })
    expect(current.ok()).toBeTruthy()
    unchangedAfterUnsupported =
      JSON.stringify((await current.json()).data) === JSON.stringify(initialConfig)
    expect(unchangedAfterUnsupported).toBe(true)
    upstreamStatus = unsupportedStatus
  } else {
    const invalid = await page.request.put('/api/v1/billing/reconciliation/provider', {
      headers: { Authorization: `Bearer ${token}` },
      data: { provider: 'isolated_provider', endpoint, response_schema: {} }
    })
    unsupportedStatus = invalid.status()
    expect(unsupportedStatus).toBe(422)
    const invalidBody = await invalid.json()
    unsupportedCode = invalidBody.code
    expect(unsupportedCode).toBe('RECONCILIATION_PROVIDER_UNSUPPORTED')
    const afterInvalid = await page.request.get('/api/v1/billing/reconciliation/provider', {
      headers: { Authorization: `Bearer ${token}` }
    })
    expect(afterInvalid.ok()).toBeTruthy()
    unchangedAfterUnsupported =
      JSON.stringify((await afterInvalid.json()).data) === JSON.stringify(initialConfig)
    expect(unchangedAfterUnsupported).toBe(true)
    const provider = page.getByLabel('对账提供方', { exact: true })
    await provider
      .locator(
        'xpath=ancestor::div[contains(concat(" ",normalize-space(@class)," ")," el-select ")][1]'
      )
      .locator('.el-select__wrapper')
      .click()
    await page.getByRole('option', { name: '通用 HTTP（http）', exact: true }).click()
    await page.getByLabel('对账接口 URL', { exact: true }).fill(endpoint)
    const key = page.getByLabel('API Key', { exact: true })
    await key.fill(secret)
    if (mode === 'disconnect') {
      await page.route(
        '**/api/v1/billing/reconciliation/provider',
        async (route) => {
          const response = await route.fetch()
          upstreamStatus = response.status()
          await route.abort('failed')
        },
        { times: 1 }
      )
    }
    const save =
      mode === 'candidate'
        ? page.waitForResponse(
            (response) =>
              new URL(response.url()).pathname === '/api/v1/billing/reconciliation/provider' &&
              response.request().method() === 'PUT'
          )
        : undefined
    await page.getByRole('button', { name: '保存配置', exact: true }).click()
    if (save) upstreamStatus = (await save).status()
    await expect.poll(() => upstreamStatus).toBe(200)
    await expect.poll(() => writes).toBe(1)
    await page.waitForTimeout(500)
  }
  const key = page.getByLabel('API Key', { exact: true })
  const retainedSecret = await key.evaluate(
    (element, expected) => (element as HTMLInputElement).value === expected,
    secret
  )
  expect(retainedSecret).toBe(mode === 'disconnect')
  const saveEnabled = await page.getByRole('button', { name: '保存配置', exact: true }).isEnabled()
  const visibleError = await page.locator('[role="alert"]').count()
  const read = await page.request.get('/api/v1/billing/reconciliation/provider', {
    headers: { Authorization: `Bearer ${token}` }
  })
  expect(read.ok()).toBeTruthy()
  const body = await read.json()
  const data = body.data
  const responseContainsSecret = JSON.stringify(body).includes(secret)
  expect(responseContainsSecret).toBe(false)
  if (mode !== 'baseline') {
    expect(data.provider).toBe('http')
    expect(data.endpoint).toBe(endpoint)
    expect(data.api_key_configured).toBe(true)
  }
  expect(writes).toBe(1)
  expect(refreshes).toBe(0)
  await writeFile(
    join(required('E2E_ARTIFACT_DIR'), 'browser-receipt.json'),
    JSON.stringify(
      {
        mode,
        unsupportedStatus,
        unsupportedCode,
        unchangedAfterUnsupported,
        upstreamStatus,
        writes,
        refreshes,
        retainedSecret,
        saveEnabled,
        visibleErrorCount: visibleError,
        configured: data.api_key_configured,
        responseContainsSecret
      },
      null,
      2
    )
  )
})
