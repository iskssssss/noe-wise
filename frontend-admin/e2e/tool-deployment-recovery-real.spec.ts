import { expect, test } from '@playwright/test'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'

function required(name: string) {
  const value = process.env[name]
  if (!value) throw Error(`Missing isolated fixture: ${name}`)
  return value
}

test('部署创建响应丢失后核对原请求并运行工具', async ({ page, context }) => {
  test.skip(Boolean(process.env.E2E_TOOL_PROVIDER_BINDING), '本轮验证工具契约与处理器绑定')
  let key = ''
  const mode = process.env.E2E_TOOL_DEPLOYMENT_MODE ?? 'candidate'
  const responseMode = process.env.E2E_TOOL_DEPLOYMENT_RESPONSE ?? 'disconnect'
  let posts = 0,
    upstreamStatus = 0,
    reads = 0,
    authRefreshes = 0,
    receiptReads = 0,
    requestId = '',
    receiptAfterReopen = false
  let upstreamReceipt:
    { request_id: string; deployment_id: string; deployment_key: string } | undefined
  let reopenStarted = false
  let resultPage = page
  await context.route(
    '**/api/v1/capabilities/deployments/create-with-receipt/result?*',
    async (route) => {
      expect(new URL(route.request().url()).searchParams.get('request_id')).toBe(requestId)
      receiptReads++
      if (!reopenStarted) return route.fulfill({ status: 204, body: '' })
      return route.continue()
    }
  )
  await context.route(
    mode === 'baseline'
      ? '**/api/v1/capabilities/deployments'
      : '**/api/v1/capabilities/deployments/create-with-receipt',
    async (route) => {
      if (route.request().method() !== 'POST') return route.continue()
      posts++
      if (mode === 'candidate') {
        const request = route.request().postDataJSON()
        requestId = String(request.request_id ?? '')
        expect(requestId).toMatch(/^[0-9a-f-]{36}$/i)
        expect(request.payload.deployment_key).toBeUndefined()
      }
      const upstream = await route.fetch()
      upstreamStatus = upstream.status()
      expect([200, 201]).toContain(upstreamStatus)
      if (mode === 'candidate') {
        upstreamReceipt = (await upstream.json()).data
        expect(upstreamReceipt?.request_id).toBe(requestId)
        key = String(upstreamReceipt?.deployment_key ?? '')
        expect(key).toMatch(/^tool-/)
      } else key = String((await upstream.json()).data.deployment_key)
      if (responseMode === '401')
        await route.fulfill({
          status: 401,
          contentType: 'application/json',
          body: '{"code":"UNAUTHORIZED"}'
        })
      else await route.abort('connectionreset')
    }
  )
  context.on('request', (request) => {
    const url = new URL(request.url())
    if (request.method() === 'POST' && url.pathname === '/api/v1/auth/refresh') authRefreshes++
    if (
      request.method() === 'GET' &&
      url.pathname === '/api/v1/capabilities/deployments/detail' &&
      url.searchParams.get('deploymentKey') === key
    )
      reads++
  })
  await page.goto('/login?redirect=%2Fadmin%2Ftool-deployments')
  await page.locator('input[autocomplete="organization"]').fill(required('E2E_TENANT_SLUG'))
  await page.locator('input[autocomplete="username"]').fill('runtime-e2e-admin')
  await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await page.getByRole('button', { name: '新增部署', exact: true }).click()
  const drawer = page.getByRole('dialog', { name: '新增工具部署' })
  await drawer.getByLabel('工具（跟随当前发布）', { exact: true }).click()
  await page.getByRole('option', { name: 'get_current_time', exact: true }).click()
  await drawer
    .getByLabel('提供方', { exact: true })
    .locator(
      'xpath=ancestor::div[contains(concat(" ",normalize-space(@class)," ")," el-select__wrapper ")][1]'
    )
    .click()
  await page.getByRole('option', { name: '平台内置工具', exact: true }).click()
  await expect(drawer.getByLabel('提供方引用', { exact: true })).toContainText('get_current_time')
  await expect(drawer.getByLabel('提供方引用', { exact: true }).getByRole('combobox')).toHaveCount(
    0
  )
  await drawer.getByRole('button', { name: '创建', exact: true }).click()
  if (mode === 'baseline') {
    await expect(page.getByText('创建结果尚未确认，原输入已保留。', { exact: false })).toBeVisible()
    await expect(drawer.getByRole('button', { name: '创建', exact: true })).toBeDisabled()
    await drawer.getByRole('button', { name: '取消', exact: true }).click()
    await expect(drawer).toBeHidden()
    await page.getByRole('button', { name: '查看当前部署', exact: true }).click()
    await expect(page.getByText(`当前部署：${key}`, { exact: false })).toBeVisible()
    expect(reads).toBe(1)
  } else {
    await expect.poll(() => requestId).not.toBe('')
    await expect(page.getByText(/结果尚未确认/).first()).toBeVisible()
    await expect.poll(() => receiptReads).toBeGreaterThan(0)
    await page.screenshot({ path: join(required('E2E_ARTIFACT_DIR'), 'before-reopen.png') })
    await page.close()
    reopenStarted = true
    resultPage = await context.newPage()
    const originalReceipt = resultPage.waitForResponse((response) => {
      const url = new URL(response.url())
      return (
        url.pathname === '/api/v1/capabilities/deployments/create-with-receipt/result' &&
        url.searchParams.get('request_id') === requestId &&
        response.status() === 200
      )
    })
    await resultPage.goto('/admin/tool-deployments')
    const envelope = await (await originalReceipt).json()
    const receipt = envelope.data
    expect(receipt.request_id).toBe(requestId)
    expect(receipt.deployment_key).toBe(key)
    expect(String(receipt.deployment_id)).toMatch(/^[1-9]\d*$/)
    expect(receipt).toEqual(upstreamReceipt)
    receiptAfterReopen = true
    await expect(resultPage.getByText(`工具部署已创建：${key}`, { exact: false })).toBeVisible()
    await expect(resultPage.getByRole('row').filter({ hasText: key })).toBeVisible()
    expect(authRefreshes).toBe(0)
    expect(receiptReads).toBeGreaterThanOrEqual(1)
  }
  expect(posts).toBe(1)
  await resultPage.screenshot({ path: join(required('E2E_ARTIFACT_DIR'), 'after-recovery.png') })
  await writeFile(
    join(required('E2E_ARTIFACT_DIR'), 'browser-receipt.json'),
    JSON.stringify(
      {
        key,
        mode,
        responseMode,
        posts,
        reads,
        upstreamStatus,
        authRefreshes,
        receiptReads,
        requestId,
        receiptAfterReopen,
        upstreamReceipt
      },
      null,
      2
    )
  )
})

test('工具契约与平台处理器绑定后由同一部署执行', async ({ page }) => {
  const mode = process.env.E2E_TOOL_PROVIDER_BINDING
  test.skip(!mode, '仅在工具绑定专项运行')
  let posts = 0
  page.on('request', (request) => {
    if (
      request.method() === 'POST' &&
      new URL(request.url()).pathname === '/api/v1/capabilities/deployments/create-with-receipt'
    )
      posts++
  })
  await page.goto('/login?redirect=%2Fadmin%2Ftool-deployments')
  await page.locator('input[autocomplete="organization"]').fill(required('E2E_TENANT_SLUG'))
  await page.locator('input[autocomplete="username"]').fill('runtime-e2e-admin')
  await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await page.getByRole('button', { name: '新增部署', exact: true }).click()
  const drawer = page.getByRole('dialog', { name: '新增工具部署' })
  await drawer.getByLabel('工具（跟随当前发布）', { exact: true }).click()
  await page.getByRole('option', { name: 'get_current_time', exact: true }).click()
  await drawer
    .getByLabel('提供方', { exact: true })
    .locator(
      'xpath=ancestor::div[contains(concat(" ",normalize-space(@class)," ")," el-select__wrapper ")][1]'
    )
    .click()
  await page.getByRole('option', { name: '平台内置工具', exact: true }).click()
  let manualReferenceSelections = 0
  if (mode === 'baseline') {
    await drawer
      .getByLabel('提供方引用', { exact: true })
      .locator(
        'xpath=ancestor::div[contains(concat(" ",normalize-space(@class)," ")," el-select__wrapper ")][1]'
      )
      .click()
    await page.getByRole('option', { name: /^template_render(?:\s|$)/ }).click()
    manualReferenceSelections++
  } else {
    await expect(drawer).toContainText('get_current_time')
    await expect(
      drawer.getByLabel('提供方引用', { exact: true }).getByRole('combobox')
    ).toHaveCount(0)
  }
  const created = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/capabilities/deployments/create-with-receipt' &&
      response.request().method() === 'POST'
  )
  await drawer.getByRole('button', { name: '创建', exact: true }).click()
  const response = await created
  expect(response.ok()).toBe(true)
  const envelope = await response.json()
  expect(envelope.code).toBe('OK')
  expect(envelope.data.status).toBe('SUCCEEDED')
  expect(response.request().postDataJSON().payload.provider_ref).toBe(
    mode === 'baseline' ? 'template_render' : 'get_current_time'
  )
  const key = String(envelope.data.deployment_key)
  expect(key).toMatch(/^tool-/)
  await expect(page.getByRole('row').filter({ hasText: key })).toBeVisible()
  expect(posts).toBe(1)
  await page.screenshot({
    path: join(required('E2E_ARTIFACT_DIR'), 'provider-binding-created.png'),
    fullPage: true
  })
  await writeFile(
    join(required('E2E_ARTIFACT_DIR'), 'browser-receipt.json'),
    JSON.stringify(
      {
        key,
        bindingMode: mode,
        posts,
        upstreamStatus: response.status(),
        upstreamReceipt: envelope.data,
        payload: response.request().postDataJSON().payload,
        manualReferenceSelections
      },
      null,
      2
    )
  )
})
