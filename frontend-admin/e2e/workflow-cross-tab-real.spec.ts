import { expect, test } from '@playwright/test'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'

function required(name: string): string {
  const value = process.env[name]
  if (!value) throw new Error(`缺少隔离验收参数：${name}`)
  return value
}

test.describe.configure({ retries: 0 })

test('跨页发布工作流后刷新接入智能体', async ({ page }) => {
  test.setTimeout(120_000)
  const agent = required('E2E_AGENT_ID')
  const evidence = required('E2E_ARTIFACT_DIR')
  const mode = process.env.E2E_WORKFLOW_CROSS_TAB_MODE ?? 'candidate'
  expect(['baseline', 'candidate']).toContain(mode)
  let onboardingPosts = 0
  let catalogReads = 0
  let publishPosts = 0
  let manualRefreshClicks = 0
  const details: string[] = []
  page.on('request', (request) => {
    const url = new URL(request.url())
    if (request.method() === 'GET' && url.pathname === '/api/v1/builder/workflows') catalogReads++
    if (request.method() === 'POST' && url.pathname === '/api/v1/agents/workflows/onboard')
      onboardingPosts++
    if (request.method() === 'GET' && url.pathname === '/api/v1/builder/workflows/history/detail')
      details.push(String(url.searchParams.get('definitionVersionId')))
  })
  await page.goto(`/login?redirect=${encodeURIComponent('/admin/agents/' + agent)}`)
  await page.locator('input[autocomplete="organization"]').fill(required('E2E_TENANT_SLUG'))
  await page.locator('input[autocomplete="username"]').fill(required('E2E_USERNAME'))
  await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL(new RegExp(`/admin/agents/${agent}$`))
  await page.getByRole('tab', { name: /能力挂载/ }).click()
  await page.getByRole('button', { name: '添加能力', exact: true }).first().click()
  const drawer = page.getByRole('dialog', { name: '添加能力挂载' })
  await drawer
    .locator('.el-form-item')
    .filter({ hasText: '能力类型' })
    .locator('.el-select__wrapper')
    .click()
  await page.getByRole('option', { name: '工作流', exact: true }).click()
  await drawer.getByRole('button', { name: '接入工作流', exact: true }).click()
  const panel = drawer.locator('section[aria-label="工作流接入"]')
  await expect(panel.getByText('当前没有可接入的已发布工作流。', { exact: false })).toBeVisible()
  const catalogBefore = catalogReads
  expect(catalogBefore).toBeGreaterThan(0)
  const [workflow] = await Promise.all([
    page.context().waitForEvent('page'),
    panel.getByRole('link', { name: '前往工作流（新标签页）' }).click()
  ])
  await workflow.waitForLoadState('domcontentloaded')
  await expect(workflow).toHaveURL(/\/admin\/workflows$/)
  workflow.on('request', (request) => {
    if (
      request.method() === 'POST' &&
      new URL(request.url()).pathname === '/api/v1/builder/workflows/publish'
    )
      publishPosts++
  })
  await workflow.getByRole('button', { name: '新建工作流', exact: true }).click()
  const info = workflow.getByRole('tabpanel', { name: '工作流信息', exact: true })
  const id = await info
    .locator('.el-form-item')
    .filter({ hasText: '业务标识' })
    .locator('input')
    .inputValue()
  await info
    .locator('.el-form-item')
    .filter({ hasText: '名称' })
    .locator('input')
    .fill('跨页接入验收工作流')
  const created = workflow.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      new URL(response.url()).pathname === '/api/v1/builder/workflows'
  )
  await workflow.getByRole('button', { name: '保存草稿', exact: true }).click()
  expect((await created).status()).toBe(200)
  await expect(workflow).toHaveURL(new RegExp(`/admin/workflows/${id}$`))
  const publication = workflow.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      new URL(response.url()).pathname === '/api/v1/builder/workflows/publish'
  )
  const listResponse = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/builder/workflows' &&
      response.request().method() === 'GET'
  )
  const detailResponse = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/builder/workflows/history/detail' &&
      response.request().method() === 'GET'
  )
  await workflow.getByRole('button', { name: '发布', exact: true }).click()
  await workflow
    .getByRole('dialog', { name: '发布工作流' })
    .getByRole('button', { name: '发布工作流', exact: true })
    .click()
  const publishedResponse = await publication
  expect(publishedResponse.status()).toBe(200)
  const definitionVersion = String((await publishedResponse.json()).data.definition_version_id)
  expect(definitionVersion).toMatch(/^[1-9]\d*$/)
  expect(publishPosts).toBe(1)
  expect(onboardingPosts).toBe(0)
  await workflow.screenshot({ path: join(evidence, 'workflow-published.png'), fullPage: true })
  await page.bringToFront()
  if (mode === 'baseline') {
    expect(catalogReads).toBe(catalogBefore)
    await expect(panel.getByText('当前没有可接入的已发布工作流。', { exact: false })).toBeVisible()
    await page.screenshot({ path: join(evidence, 'workflow-stale.png'), fullPage: true })
    await panel.getByRole('button', { name: '刷新工作流选项', exact: true }).click()
    manualRefreshClicks++
  }
  const list = await listResponse
  expect(list.status()).toBe(200)
  const directory = (await list.json()).data.items
  const published = directory.filter(
    (item: { active_definition_version_id?: string | null }) => item.active_definition_version_id
  )
  expect(published).toHaveLength(1)
  expect(published[0].workflow_id).toBe(id)
  expect(String(published[0].active_definition_version_id)).toBe(definitionVersion)
  expect(catalogReads).toBeGreaterThan(catalogBefore)
  await expect(panel.locator('.el-form-item').filter({ hasText: '已发布工作流' })).toContainText(
    '跨页接入验收工作流'
  )
  const detail = await detailResponse
  expect(detail.status()).toBe(200)
  expect(new URL(detail.url()).searchParams.get('definitionVersionId')).toBe(
    String(published[0].active_definition_version_id)
  )
  await page.screenshot({ path: join(evidence, 'onboard-selection.png'), fullPage: true })
  const posted = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/agents/workflows/onboard' &&
      response.request().method() === 'POST'
  )
  await panel.getByRole('button', { name: '接入智能体', exact: true }).click()
  await page.getByRole('button', { name: '确认接入', exact: true }).click()
  const response = await posted
  expect(response.status()).toBe(200)
  const command = response.request().postDataJSON()
  const receipt = (await response.json()).data
  expect(receipt.status).toBe('SUCCEEDED')
  expect(receipt.agent_id).toBe(agent)
  expect(receipt.workflow_id).toBe(id)
  expect(command.expected_definition_version_id).toBe(
    String(published[0].active_definition_version_id)
  )
  expect(onboardingPosts).toBe(1)
  await expect(panel.getByText(/接入已确认，安装修订/)).toBeVisible()
  await page.screenshot({ path: join(evidence, 'onboard-confirmed.png'), fullPage: true })
  await writeFile(
    join(evidence, 'workflow-cross-tab.json'),
    JSON.stringify({
      command,
      receipt,
      mode,
      catalogBefore,
      catalogReads,
      manualRefreshClicks,
      publishPosts,
      definitionVersionId: String(published[0].active_definition_version_id),
      onboardingPosts,
      details
    })
  )
})
