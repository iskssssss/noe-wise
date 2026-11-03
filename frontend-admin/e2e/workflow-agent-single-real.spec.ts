import { expect, test } from '@playwright/test'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'

function required(name: string): string {
  const value = process.env[name]
  if (!value) throw new Error(`缺少隔离验收参数：${name}`)
  return value
}

test.describe.configure({ retries: 0 })

test('唯一已发布工作流接入智能体', async ({ page }) => {
  test.setTimeout(120_000)
  const agent = required('E2E_AGENT_ID')
  const evidence = required('E2E_ARTIFACT_DIR')
  let onboardingPosts = 0
  let manualSelectionClicks = 0
  const details: string[] = []
  page.on('request', (request) => {
    const url = new URL(request.url())
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
  await drawer.getByRole('button', { name: '接入工作流', exact: true }).click()
  const list = await listResponse
  expect(list.status()).toBe(200)
  const directory = (await list.json()).data.items
  const published = directory.filter(
    (item: { active_definition_version_id?: string | null }) => item.active_definition_version_id
  )
  expect(published).toHaveLength(1)
  expect(published[0].workflow_id).toBe('onboard-single')
  const panel = drawer.locator('section[aria-label="工作流接入"]')
  if (process.env.E2E_WORKFLOW_ONBOARD_MODE === 'baseline') {
    expect(details).toEqual([])
    await panel
      .locator('.el-form-item')
      .filter({ hasText: '已发布工作流' })
      .locator('.el-select__wrapper')
      .click()
    manualSelectionClicks++
    await page.getByRole('option', { name: '唯一接入工作流', exact: true }).click()
    manualSelectionClicks++
  }
  await expect(panel.locator('.el-form-item').filter({ hasText: '已发布工作流' })).toContainText(
    '唯一接入工作流'
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
  expect(receipt.workflow_id).toBe('onboard-single')
  expect(command.expected_definition_version_id).toBe(
    String(published[0].active_definition_version_id)
  )
  expect(onboardingPosts).toBe(1)
  await expect(panel.getByText(/接入已确认，安装修订/)).toBeVisible()
  await expect(drawer.getByText('工作流接入已确认，智能体或挂载选项尚未刷新。')).toHaveCount(0, {
    timeout: 30_000
  })
  await drawer.getByRole('button', { name: '取消', exact: true }).click()
  await expect(drawer).toBeHidden()
  await expect(page.getByRole('row').filter({ hasText: '工作流' })).toContainText('唯一接入工作流')
  await page.screenshot({ path: join(evidence, 'onboard-confirmed.png'), fullPage: true })
  await writeFile(
    join(evidence, 'onboard-single.json'),
    JSON.stringify({
      command,
      receipt,
      definitionVersionId: String(published[0].active_definition_version_id),
      onboardingPosts,
      manualSelectionClicks,
      details
    })
  )
})
