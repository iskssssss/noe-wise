import { expect, test } from '@playwright/test'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'

function required(name: string): string {
  const value = process.env[name]
  if (!value) throw new Error(`缺少部署目录验收参数：${name}`)
  return value
}

test.describe.configure({ retries: 0 })

test('部署列表完整显示可搜索的名称且同一部署可以实际执行', async ({ page }) => {
  test.setTimeout(180_000)
  const mode = required('E2E_DEPLOYMENT_DIRECTORY')
  expect(['baseline', 'candidate']).toContain(mode)
  const evidence = required('E2E_ARTIFACT_DIR')
  const clockKey = required('E2E_CLOCK_DEPLOYMENT')
  const queryKey = required('E2E_QUERY_DEPLOYMENT')
  const label = `${required('E2E_CLOCK_NAME')} / ${required('E2E_CLOCK_VERSION_KEY')}`
  const errors: string[] = []
  const network: { path: string; method: string; status: number }[] = []
  let detailReads = 0
  let faultApplied = 0
  let upstreamStatus = 0
  let chatPosts = 0
  let directoryPhase = true
  page.on('pageerror', (error) => errors.push(error.message))
  page.on('request', (request) => {
    const path = new URL(request.url()).pathname
    if (directoryPhase && path === '/api/v1/capabilities/versions/detail') detailReads++
    if (path === '/api/v1/chat' && request.method() === 'POST') chatPosts++
  })
  page.on('response', (response) => {
    const path = new URL(response.url()).pathname
    if (path.startsWith('/api/v1/capabilities') || path.startsWith('/api/v1/chat'))
      network.push({ path, method: response.request().method(), status: response.status() })
  })
  await page.route('**/api/v1/capabilities/versions/detail?*', async (route) => {
    if (
      new URL(route.request().url()).searchParams.get('versionId') !== required('E2E_QUERY_VERSION')
    )
      return route.continue()
    const upstream = await route.fetch()
    upstreamStatus = upstream.status()
    expect(upstreamStatus).toBe(200)
    await writeFile(join(evidence, 'interrupted-version-response.json'), await upstream.text())
    faultApplied++
    await route.abort('connectionreset')
  })
  await page.goto('/login?redirect=%2Fadmin%2Ftool-deployments')
  await page.locator('input[autocomplete="organization"]').fill(required('E2E_TENANT_SLUG'))
  await page.locator('input[autocomplete="username"]').fill('runtime-e2e-admin')
  await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
  const directoryReady = page.waitForResponse(
    (response) => new URL(response.url()).pathname === '/api/v1/capabilities/deployments'
  )
  await page.getByRole('button', { name: '登录', exact: true }).click()
  const directoryResponse = await directoryReady
  expect(directoryResponse.status()).toBe(200)
  const directory = await directoryResponse.json()
  expect(directory.code).toBe('OK')
  await writeFile(join(evidence, 'directory-response.json'), JSON.stringify(directory, null, 2))
  const row = page.getByRole('row').filter({ hasText: clockKey })
  await expect(row).toBeVisible()
  await expect(page.getByRole('row').filter({ hasText: queryKey })).toBeVisible()
  if (mode === 'baseline') {
    await expect.poll(() => faultApplied).toBe(1)
    await expect(page.getByRole('button', { name: '刷新', exact: true })).toBeEnabled()
    await expect(row).toContainText('版本信息不可用')
    await expect(row).not.toContainText(label)
    expect(detailReads).toBe(2)
  } else {
    const item = directory.data.find(
      (value: { deployment_key: string }) => value.deployment_key === clockKey
    )
    expect(item.tool_name).toBe(required('E2E_CLOCK_NAME'))
    expect(item.tool_version_key).toBe(required('E2E_CLOCK_VERSION_KEY'))
    await expect(row).toContainText(label)
    expect(detailReads).toBe(0)
    expect(faultApplied).toBe(0)
  }
  await writeFile(join(evidence, 'directory.html'), await page.locator('main').innerHTML())
  await page.screenshot({ path: join(evidence, `directory-${mode}.png`), fullPage: true })
  await page.getByRole('textbox', { name: '搜索工具部署', exact: true }).fill(label)
  if (mode === 'baseline') {
    await expect(row).toHaveCount(0)
    await expect(page.getByText('没有匹配的部署，请调整筛选条件', { exact: true })).toBeVisible()
  } else {
    await expect(row).toBeVisible()
    await expect(page.getByRole('row').filter({ hasText: queryKey })).toHaveCount(0)
  }
  await page.screenshot({ path: join(evidence, `directory-search-${mode}.png`), fullPage: true })
  await writeFile(
    join(evidence, 'directory-result.json'),
    JSON.stringify(
      {
        mode,
        detailReads,
        faultApplied,
        upstreamStatus,
        label,
        clockKey,
        queryKey,
        network,
        errors
      },
      null,
      2
    )
  )
  directoryPhase = false
  await page.unroute('**/api/v1/capabilities/versions/detail?*')

  await page.goto(`/admin/agents/${required('E2E_AGENT_ID')}`)
  const users = page.waitForResponse(
    (response) => new URL(response.url()).pathname === '/api/v1/chat/end-users'
  )
  await page.getByRole('button', { name: '试用当前发布版本', exact: true }).click()
  expect((await users).status()).toBe(200)
  await page.locator('.el-select').filter({ hasText: '终端用户' }).getByRole('combobox').click()
  await page.getByRole('option', { name: 'Runtime E2E User', exact: true }).click()
  await page.getByPlaceholder('输入消息', { exact: true }).fill('查询上海当前时间并确认工具结果。')
  const accepted = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' && new URL(response.url()).pathname === '/api/v1/chat'
  )
  await page.getByRole('button', { name: '发送', exact: true }).click()
  const response = await accepted
  expect(response.status()).toBe(202)
  const receipt = (await response.json()).data
  await writeFile(join(evidence, 'receipt.json'), JSON.stringify(receipt, null, 2))
  await expect(
    page.locator('.debug-message').filter({ hasText: required('E2E_EXPECTED_REPLY') })
  ).toHaveCount(1, { timeout: 120_000 })
  expect(chatPosts).toBe(1)
  expect(errors).toEqual([])
  await page.screenshot({ path: join(evidence, 'clock-result.png'), fullPage: true })
  await writeFile(
    join(evidence, 'result.json'),
    JSON.stringify(
      { mode, detailReads, faultApplied, upstreamStatus, chatPosts, receipt, network, errors },
      null,
      2
    )
  )
})
