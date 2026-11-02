import { expect, test, type Page } from '@playwright/test'
import { existsSync } from 'node:fs'
import { rename, writeFile } from 'node:fs/promises'
import { join } from 'node:path'

function required(name: string): string {
  const value = process.env[name]
  if (!value) throw new Error(`缺少隔离验收参数：${name}`)
  return value
}

test.describe.configure({ retries: 0 })

test('共享定义预算控件的可访问性状态随保存同步', async ({ page }) => {
  const evidence = required('E2E_ARTIFACT_DIR')
  const definitionId = required('E2E_DEFINITION_ID')
  const prompt = required('E2E_DEFINITION_PROMPT')
  const mode = required('E2E_DEFINITION_BUDGET_A11Y_MODE')
  expect(['baseline', 'candidate']).toContain(mode)
  let writes = 0
  page.on('request', (request) => {
    if (
      request.method() === 'PUT' &&
      new URL(request.url()).pathname === '/api/v1/tenant/agent-definitions/update'
    )
      writes++
  })
  await page.goto(`/login?redirect=${encodeURIComponent('/admin/agent-definitions')}`)
  await page.locator('input[autocomplete="organization"]').fill(required('E2E_TENANT_SLUG'))
  await page.locator('input[autocomplete="username"]').fill(required('E2E_USERNAME'))
  await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await page
    .getByRole('row')
    .filter({ hasText: definitionId })
    .getByRole('button', { name: '查看与维护', exact: true })
    .click()
  const drawer = page.getByRole('dialog', { name: '维护机构共享定义' })
  await drawer.getByLabel('系统提示词').fill(prompt)
  await drawer.getByText('执行预算（可选）', { exact: true }).click()
  const input = drawer.getByLabel('活跃执行时长（秒）')
  const state = () =>
    input.evaluate((element: HTMLInputElement) => ({
      disabled: element.disabled,
      readOnly: element.readOnly,
      ariaDisabled: element.getAttribute('aria-disabled')
    }))
  await expect.poll(async () => (await state()).disabled).toBe(false)
  const initial = await state()
  expect(initial).toEqual({
    disabled: false,
    readOnly: false,
    ariaDisabled: mode === 'baseline' ? 'true' : 'false'
  })
  // 基线 ARIA 属性错误，先核实原生输入未禁用，再以真实键盘输入。
  await input.click({ force: mode === 'baseline' })
  await page.keyboard.press('Control+A')
  await page.keyboard.type('300')
  await input.blur()
  await expect(input).toHaveValue('300')
  await page.route(
    '**/api/v1/tenant/agent-definitions/update',
    async (route) => {
      const key = (await route.request().allHeaders())['idempotency-key']
      expect(key).toBeTruthy()
      const command = route.request().postDataJSON()
      expect(command.payload.definition.execution_config.max_wall_time_seconds).toBe(300)
      const upstream = await route.fetch({ maxRetries: 0 })
      expect(upstream.status()).toBe(200)
      const receipt = (await upstream.json()).data
      await writeFile(join(evidence, 'upstream.tmp'), JSON.stringify({ key, command, receipt }))
      await rename(join(evidence, 'upstream.tmp'), join(evidence, 'upstream.json'))
      await expect
        .poll(() => existsSync(join(evidence, 'original-confirmed')), { timeout: 45_000 })
        .toBe(true)
      await route.fulfill({ response: upstream })
    },
    { times: 1 }
  )
  const saved = page.waitForResponse(
    (response) =>
      response.request().method() === 'PUT' &&
      new URL(response.url()).pathname === '/api/v1/tenant/agent-definitions/update'
  )
  await drawer.getByRole('button', { name: '保存草稿', exact: true }).click()
  await expect
    .poll(() => existsSync(join(evidence, 'database-confirmed')), { timeout: 45_000 })
    .toBe(true)
  await expect.poll(async () => (await state()).disabled).toBe(true)
  const busy = await state()
  expect(busy.disabled).toBe(true)
  expect(busy.ariaDisabled).toBe('true')
  await writeFile(join(evidence, 'busy-observed'), 'observed')
  expect((await saved).status()).toBe(200)
  await expect.poll(async () => (await state()).disabled).toBe(false)
  const after = await state()
  expect(after).toEqual({
    disabled: false,
    readOnly: false,
    ariaDisabled: mode === 'baseline' ? 'true' : 'false'
  })
  await expect(input).toHaveValue('300')
  expect(writes).toBe(1)
  await page.screenshot({ path: join(evidence, 'budget-a11y.png'), fullPage: true })
  await writeFile(
    join(evidence, 'budget-a11y.json'),
    JSON.stringify({ mode, definitionId, initial, busy, after, writes })
  )
})

test('原保存已提交但响应中断，刷新后核对原操作', async ({ page }) => {
  await verifyRecovery(page, 'refresh')
})

test('原保存已提交但响应中断，抽屉内核对原保存', async ({ page }) => {
  test.skip(process.env.E2E_DEFINITION_RECOVERY_MODE === 'baseline', '基线只验证刷新后的状态')
  await verifyRecovery(page, 'in-page')
})

test('原保存被服务端拒绝后封存原键并继续编辑', async ({ page }) => {
  test.skip(process.env.E2E_DEFINITION_RECOVERY_MODE === 'baseline', '基线不支持封存原键')
  const evidence = required('E2E_ARTIFACT_DIR')
  const definitionId = required('E2E_DEFINITION_ID')
  const prompt = required('E2E_DEFINITION_PROMPT')
  let updates = 0
  let fences = 0
  let originalKey = ''
  page.on('request', (request) => {
    const path = new URL(request.url()).pathname
    if (request.method() === 'PUT' && path === '/api/v1/tenant/agent-definitions/update') updates++
    if (request.method() === 'POST' && path === '/api/v1/tenant/agent-definitions/save-fence')
      fences++
  })
  await page.goto(`/login?redirect=${encodeURIComponent('/admin/agent-definitions')}`)
  await page.locator('input[autocomplete="organization"]').fill(required('E2E_TENANT_SLUG'))
  await page.locator('input[autocomplete="username"]').fill(required('E2E_USERNAME'))
  await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await page
    .getByRole('row')
    .filter({ hasText: definitionId })
    .getByRole('button', { name: '查看与维护', exact: true })
    .click()
  const drawer = page.getByRole('dialog', { name: '维护机构共享定义' })
  await drawer.getByLabel('系统提示词').fill(prompt)
  await drawer.getByText('执行预算（可选）', { exact: true }).click()
  const wallTime = drawer.getByLabel('活跃执行时长（秒）')
  expect(
    await wallTime.evaluate((input: HTMLInputElement) => ({
      disabled: input.disabled,
      readOnly: input.readOnly
    }))
  ).toEqual({ disabled: false, readOnly: false })
  await wallTime.click()
  await page.keyboard.press('Control+A')
  await page.keyboard.type('604801')
  await wallTime.blur()
  await page.route(
    '**/api/v1/tenant/agent-definitions/update',
    async (route) => {
      const headers = await route.request().allHeaders()
      originalKey = headers['idempotency-key'] ?? ''
      expect(originalKey).toBeTruthy()
      const command = route.request().postDataJSON()
      expect(String(command.definition_id)).toBe(definitionId)
      expect(command.payload.definition.execution_config.max_wall_time_seconds).toBe(604801)
      const upstream = await route.fetch({ maxRetries: 0 })
      expect(upstream.status()).toBe(422)
      await writeFile(
        join(evidence, 'upstream.tmp'),
        JSON.stringify({ key: originalKey, command, upstreamStatus: upstream.status() })
      )
      await rename(join(evidence, 'upstream.tmp'), join(evidence, 'upstream.json'))
      await expect
        .poll(() => existsSync(join(evidence, 'original-confirmed')), { timeout: 45_000 })
        .toBe(true)
      await route.fulfill({ response: upstream })
    },
    { times: 1 }
  )
  await drawer.getByRole('button', { name: '保存草稿', exact: true }).click()
  await expect(drawer.getByRole('button', { name: '结束未确认的保存', exact: true })).toBeVisible()
  expect(updates).toBe(1)
  const fenceResponse = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      new URL(response.url()).pathname === '/api/v1/tenant/agent-definitions/save-fence'
  )
  await drawer.getByRole('button', { name: '结束未确认的保存', exact: true }).click()
  await page
    .getByRole('dialog', { name: '结束未确认的保存' })
    .getByRole('button', { name: '结束原保存', exact: true })
    .click()
  const response = await fenceResponse
  expect(response.status()).toBe(200)
  expect(response.request().postDataJSON()).toMatchObject({
    operation: 'update',
    request_key: originalKey,
    definition_id: definitionId
  })
  const resolution = (await response.json()).data
  expect(resolution).toEqual({ state: 'FENCED', receipt: null })
  await expect(drawer.getByRole('button', { name: '结束未确认的保存', exact: true })).toHaveCount(0)
  await expect(drawer.getByLabel('系统提示词')).toHaveValue(prompt)
  await expect(wallTime).toHaveValue('604801')
  await expect(drawer.getByRole('button', { name: '保存草稿', exact: true })).toBeEnabled()
  expect(updates).toBe(1)
  expect(fences).toBe(1)
  await page.screenshot({ path: join(evidence, 'fenced.png'), fullPage: true })
  await writeFile(
    join(evidence, 'fence.json'),
    JSON.stringify({ definitionId, originalKey, upstreamStatus: 422, updates, fences, resolution })
  )
})

async function verifyRecovery(page: Page, scenario: 'refresh' | 'in-page') {
  const evidence = required('E2E_ARTIFACT_DIR')
  const definitionId = required('E2E_DEFINITION_ID')
  const prompt = required('E2E_DEFINITION_PROMPT')
  const mode = process.env.E2E_DEFINITION_RECOVERY_MODE ?? 'candidate'
  expect(['baseline', 'candidate']).toContain(mode)
  const baseline = mode === 'baseline'
  let updates = 0
  let previews = 0
  let lookups = 0
  let manualChecks = 0
  let originalKey = ''
  let originalRevision = 0
  let originalReceipt: unknown
  page.on('request', (request) => {
    const path = new URL(request.url()).pathname
    if (request.method() === 'PUT' && path === '/api/v1/tenant/agent-definitions/update') updates++
    if (request.method() === 'POST' && path === '/api/v1/tenant/agent-definitions/preview')
      previews++
    if (request.method() === 'GET' && path === '/api/v1/tenant/agent-definitions/save-resolution') {
      const url = new URL(request.url())
      expect(url.searchParams.get('operation')).toBe('update')
      expect(url.searchParams.get('request_key')).toBe(originalKey)
      expect(url.searchParams.get('definition_id')).toBe(definitionId)
      lookups++
    }
  })
  await page.goto(`/login?redirect=${encodeURIComponent('/admin/agent-definitions')}`)
  await page.locator('input[autocomplete="organization"]').fill(required('E2E_TENANT_SLUG'))
  await page.locator('input[autocomplete="username"]').fill(required('E2E_USERNAME'))
  await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
  await page.getByRole('button', { name: '登录', exact: true }).click()
  const row = page.getByRole('row').filter({ hasText: definitionId })
  await row.getByRole('button', { name: '查看与维护', exact: true }).click()
  const drawer = page.getByRole('dialog', { name: '维护机构共享定义' })
  await drawer.getByLabel('系统提示词').fill(prompt)

  await page.route(
    '**/api/v1/tenant/agent-definitions/update',
    async (route) => {
      const headers = await route.request().allHeaders()
      const key = headers['idempotency-key']
      expect(key).toBeTruthy()
      originalKey = key!
      const command = route.request().postDataJSON()
      expect(String(command.definition_id)).toBe(definitionId)
      const upstream = await route.fetch({ maxRetries: 0 })
      expect(upstream.status()).toBe(200)
      const receipt = (await upstream.json()).data
      originalReceipt = receipt
      expect(String(receipt.definition_id)).toBe(definitionId)
      originalRevision = Number(receipt.draft_revision)
      expect(originalRevision).toBeGreaterThan(0)
      await writeFile(join(evidence, 'upstream.tmp'), JSON.stringify({ key, command, receipt }))
      await rename(join(evidence, 'upstream.tmp'), join(evidence, 'upstream.json'))
      await expect
        .poll(() => existsSync(join(evidence, 'original-confirmed')), { timeout: 45_000 })
        .toBe(true)
      await route.abort('failed')
    },
    { times: 1 }
  )
  await drawer.getByRole('button', { name: '保存并校验', exact: true }).click()
  await expect(page.getByRole('button', { name: /核对原(?:请求|保存)/ })).toBeVisible()
  expect(originalKey).toBeTruthy()
  const persistedBefore = await page.evaluate(
    (key) => Object.values(sessionStorage).some((value) => value.includes(key)),
    originalKey
  )
  expect(persistedBefore).toBe(!baseline)
  expect(updates).toBe(1)
  expect(previews).toBe(0)
  const originalDocument = await page.evaluate(() => performance.timeOrigin)
  await page.screenshot({ path: join(evidence, 'before-refresh.png'), fullPage: true })
  const lookup = baseline
    ? undefined
    : page.waitForResponse((response) => {
        const url = new URL(response.url())
        return (
          response.request().method() === 'GET' &&
          url.pathname === '/api/v1/tenant/agent-definitions/save-resolution' &&
          url.searchParams.get('request_key') === originalKey
        )
      })
  if (scenario === 'refresh') {
    page.on('dialog', (dialog) => void dialog.accept())
    await page.reload()
    if (baseline)
      await expect(page.getByRole('row').filter({ hasText: definitionId })).toBeVisible()
  } else {
    await expect(drawer.getByLabel('系统提示词')).toHaveValue(prompt)
    await drawer.getByRole('button', { name: '核对原保存', exact: true }).click()
    manualChecks++
  }
  let recoveredReceipt: unknown = null
  if (lookup) {
    const response = await lookup
    expect(response.status()).toBe(200)
    const resolution = (await response.json()).data
    expect(resolution.state).toBe('COMMITTED')
    const receipt = resolution.receipt
    expect(String(receipt.definition_id)).toBe(definitionId)
    expect(Number(receipt.draft_revision)).toBe(originalRevision)
    expect(receipt).toEqual(originalReceipt)
    recoveredReceipt = receipt
  }
  await expect(page.getByRole('button', { name: /核对原(?:请求|保存)/ })).toHaveCount(0)
  if (baseline) {
    await page
      .getByRole('row')
      .filter({ hasText: definitionId })
      .getByRole('button', { name: '查看与维护', exact: true })
      .click()
  } else await expect(drawer).toBeVisible()
  await expect(drawer.getByRole('button', { name: /核对原(?:请求|保存)/ })).toHaveCount(0)
  expect(
    await page.evaluate(
      (key) => Object.values(sessionStorage).some((value) => value.includes(key)),
      originalKey
    )
  ).toBe(false)
  await expect(drawer.getByLabel('系统提示词')).toHaveValue(prompt)
  const documentPreserved = (await page.evaluate(() => performance.timeOrigin)) === originalDocument
  if (scenario === 'in-page') expect(documentPreserved).toBe(true)
  expect(updates).toBe(1)
  expect(previews).toBe(0)
  expect(lookups).toBe(baseline ? 0 : 1)
  await page.screenshot({ path: join(evidence, 'after-refresh.png'), fullPage: true })
  await writeFile(
    join(evidence, 'recovery.json'),
    JSON.stringify({
      mode,
      scenario,
      definitionId,
      originalKey,
      originalRevision,
      updates,
      previews,
      lookups,
      manualChecks,
      documentPreserved,
      persistedBefore,
      pendingBefore: true,
      pendingAfter: false,
      recoveredReceipt
    })
  )
}
