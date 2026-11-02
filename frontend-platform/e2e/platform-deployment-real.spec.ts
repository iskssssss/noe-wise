import { expect, test } from '@playwright/test'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'
function required(name: string) {
  const value = process.env[name]
  if (!value) throw Error(`缺少隔离夹具：${name}`)
  return value
}
test('关联机构成功后直接进入原接入的凭证页面', async ({ page }) => {
  const target = `/platform/tenants/${required('E2E_TENANT_ID')}?section=onboarding`
  await page.goto(`/login?redirect=${encodeURIComponent(target)}`)
  await page.getByLabel('账号', { exact: true }).fill(required('E2E_USERNAME'))
  await page.getByLabel('密码', { exact: true }).fill(required('E2E_PASSWORD'))
  await page.getByRole('button', { name: '登录运营台', exact: true }).click()
  await page.getByRole('button', { name: '关联已有机构', exact: true }).click()
  const drawer = page.getByRole('dialog', { name: '关联已有机构' })
  const namespace = drawer.getByRole('combobox', { name: '命名空间' })
  await namespace.fill('browser-onboarding')
  await expect(page.getByRole('option', { name: 'browser-onboarding', exact: true })).toBeVisible()
  await namespace.press('Enter')
  await expect(
    drawer.locator('.el-select__selected-item').getByText('browser-onboarding')
  ).toBeVisible()
  await drawer.getByLabel('外部机构标识').fill('browser-onboarding-institution')
  let issues = 0
  page.on('request', (request) => {
    if (
      request.method() === 'POST' &&
      new URL(request.url()).pathname === '/api/v1/platform/institution-credentials/issue'
    )
      issues++
  })
  const saved = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/platform/institutions/associate' &&
      response.request().method() === 'POST'
  )
  const credentials = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/platform/institution-credentials' &&
      response.request().method() === 'GET'
  )
  await drawer.getByRole('button', { name: '确认关联' }).click()
  const response = await saved
  expect(response.status(), await response.text()).toBe(200)
  const receipt = (await response.json()).data
  const read = await credentials
  expect(read.status()).toBe(200)
  expect(new URL(read.url()).searchParams.get('tenant_id')).toBe(required('E2E_TENANT_ID'))
  expect(new URL(read.url()).searchParams.get('integration_id')).toBe(
    String(receipt.integration_id)
  )
  await expect(page.getByText('执行凭证独立签发', { exact: false })).toBeVisible()
  expect(issues).toBe(0)
  await writeFile(
    join(required('E2E_ARTIFACT_DIR'), 'onboarding-associate.json'),
    JSON.stringify(receipt)
  )
})
test('平台新建技能使用自动生成的标识', async ({ page }) => {
  await page.goto(`/login?redirect=${encodeURIComponent('/platform/skills')}`)
  await page.getByLabel('账号', { exact: true }).fill(required('E2E_USERNAME'))
  await page.getByLabel('密码', { exact: true }).fill(required('E2E_PASSWORD'))
  await page.getByRole('button', { name: '登录运营台', exact: true }).click()
  await page.getByRole('button', { name: '新建技能', exact: true }).first().click()
  const drawer = page.getByRole('dialog', { name: '新建技能' })
  const generated = await drawer.getByLabel('技能标识').inputValue()
  expect(generated).toMatch(/^skill-[0-9a-f-]{36}$/)
  await drawer.getByLabel('名称', { exact: true }).fill('平台自动标识技能')
  let upstreamStatus = 0
  await page.route(
    '**/api/v1/admin/capabilities/skill-drafts/create-with-receipt',
    async (route) => {
      upstreamStatus = (await route.fetch()).status()
      await route.abort('failed')
    },
    { times: 1 }
  )
  const request = page.waitForRequest(
    (r) =>
      new URL(r.url()).pathname === '/api/v1/admin/capabilities/skill-drafts/create-with-receipt' &&
      r.method() === 'POST'
  )
  await drawer.getByRole('button', { name: '创建并进入工作草稿' }).click()
  const sent = await request
  expect(sent.postDataJSON().payload.capability_key).toBe(generated)
  await expect(page.getByRole('button', { name: '核对创建结果' })).toBeVisible()
  await expect.poll(() => upstreamStatus).toBe(200)
  await expect(page.getByRole('button', { name: '核对创建结果' })).toBeEnabled()
  const lookup = page.waitForResponse(
    (r) =>
      new URL(r.url()).pathname ===
      '/api/v1/admin/capabilities/skill-drafts/create-with-receipt/result'
  )
  await page.reload()
  const data = (await (await lookup).json()).data
  expect(data.request_id).toBe(sent.postDataJSON().request_id)
  await writeFile(
    join(required('E2E_ARTIFACT_DIR'), 'platform-skill-create-receipt.json'),
    JSON.stringify({ command: sent.postDataJSON(), receipt: data })
  )
  await expect(page).toHaveURL(new RegExp(`/platform/skills/${data.definition_id}$`))
})
test('选择机构后自动读取其部署、定义和授权', async ({ page }) => {
  // 测试夹具将该路径转发到实际机构服务，其余请求进入实际平台服务。
  await page.route('**/api/v1/platform/agent-deployments**', async (route) => {
    const requestUrl = new URL(route.request().url())
    const response = await route.fetch({
      url: required('E2E_INSTITUTION_URL') + requestUrl.pathname + requestUrl.search
    })
    await route.fulfill({ response })
  })
  await page.goto(`/login?redirect=${encodeURIComponent('/platform/agent-deployments')}`)
  await page.getByLabel('账号', { exact: true }).fill(required('E2E_USERNAME'))
  await page.getByLabel('密码', { exact: true }).fill(required('E2E_PASSWORD'))
  await page.getByRole('button', { name: '登录运营台', exact: true }).click()
  await expect(page).toHaveURL(/\/platform\/agent-deployments$/)
  const paths = [
    '/api/v1/platform/agent-deployments',
    '/api/v1/platform/agent-deployments/definitions',
    '/api/v1/platform/agent-deployments/grants'
  ]
  const reads = paths.map((path) =>
    page.waitForResponse(
      (response) =>
        new URL(response.url()).pathname === path && response.request().method() === 'GET'
    )
  )
  await page.getByRole('combobox', { name: '目标机构' }).click()
  await page.getByRole('option').filter({ hasText: '浏览器部署机构' }).click()
  for (const response of await Promise.all(reads)) {
    expect(response.status(), response.url()).toBe(200)
    expect(new URL(response.url()).searchParams.get('tenant_id')).toBe(required('E2E_TENANT_ID'))
  }
  await expect(page.getByText(`当前查询与操作目标机构：${required('E2E_TENANT_ID')}`)).toBeVisible()
  await expect(page.getByText('已预选唯一有有效授权的公共定义。')).toBeVisible()
  await expect(page.locator('.el-form-item').filter({ hasText: '公共定义' })).toContainText(
    '已有授权的其他助手'
  )
  await expect(page.getByText('已预选唯一有效授权，提交时将使用此授权。')).toBeVisible()
})

test('选择机构后自动读取直接授权', async ({ page }) => {
  await page.goto(`/login?redirect=${encodeURIComponent('/platform/agent-grants')}`)
  await page.getByLabel('账号', { exact: true }).fill(required('E2E_USERNAME'))
  await page.getByLabel('密码', { exact: true }).fill(required('E2E_PASSWORD'))
  await page.getByRole('button', { name: '登录运营台', exact: true }).click()
  await expect(page).toHaveURL(/\/platform\/agent-grants$/)
  const read = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/platform/agent-grants' &&
      response.request().method() === 'GET'
  )
  await page.getByRole('combobox', { name: '目标机构' }).click()
  await page.getByRole('option').filter({ hasText: '浏览器部署机构' }).click()
  const response = await read
  expect(response.status()).toBe(200)
  expect(new URL(response.url()).searchParams.get('target_tenant_id')).toBe(
    required('E2E_TENANT_ID')
  )
  await expect(page.getByText(`当前查询与操作目标机构：${required('E2E_TENANT_ID')}`)).toBeVisible()
})

test('模板选定机构后直接授权并部署', async ({ page }) => {
  await page.route('**/api/v1/platform/agent-deployments**', async (route) => {
    const requestUrl = new URL(route.request().url())
    const headers = await route.request().allHeaders()
    delete headers.origin
    delete headers.host
    const response = await route.fetch({
      url: required('E2E_INSTITUTION_URL') + requestUrl.pathname + requestUrl.search,
      headers
    })
    await route.fulfill({ response })
  })
  const target = '/platform/agent-templates/' + required('E2E_DEFINITION_ID')
  await page.goto(`/login?redirect=${encodeURIComponent(target)}`)
  await page.getByLabel('账号', { exact: true }).fill(required('E2E_USERNAME'))
  await page.getByLabel('密码', { exact: true }).fill(required('E2E_PASSWORD'))
  await page.getByRole('button', { name: '登录运营台', exact: true }).click()
  await expect(page).toHaveURL(new RegExp(target + '$'))
  await page.getByRole('button', { name: '向机构授权并部署' }).click()
  const section = page.getByRole('region', { name: '向机构授权并部署' })
  await section.getByRole('combobox', { name: '目标机构' }).click()
  await page.getByRole('option').filter({ hasText: '浏览器部署机构' }).click()
  await expect(section.getByRole('radio', { name: '新增授权并部署' })).toBeChecked()
  await expect(section.getByRole('button', { name: '授权并部署', exact: true })).toBeEnabled()
  const grant = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/platform/agent-grants' &&
      response.request().method() === 'POST'
  )
  const deployment = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/platform/agent-deployments' &&
      response.request().method() === 'POST'
  )
  await section.getByRole('button', { name: '授权并部署', exact: true }).click()
  await page.getByRole('dialog').getByRole('button', { name: '授权并部署', exact: true }).click()
  expect((await grant).status()).toBe(200)
  const deployed = await deployment
  expect(deployed.status(), await deployed.text()).toBe(200)
  await expect(section.getByText(/智能体已部署/)).toBeVisible()
})

test('模板编辑可一次保存并验证精确修订', async ({ page }) => {
  const target = '/platform/agent-templates/' + required('E2E_DEFINITION_ID')
  await page.goto(`/login?redirect=${encodeURIComponent(target)}`)
  await page.getByLabel('账号', { exact: true }).fill(required('E2E_USERNAME'))
  await page.getByLabel('密码', { exact: true }).fill(required('E2E_PASSWORD'))
  await page.getByRole('button', { name: '登录运营台', exact: true }).click()
  await expect(page).toHaveURL(new RegExp(target + '$'))
  await page.getByLabel('说明', { exact: true }).fill('保存并验证浏览器验收')
  let publishes = 0
  page.on('request', (request) => {
    if (
      request.method() === 'POST' &&
      new URL(request.url()).pathname === '/api/v1/platform/agent-definitions/publish'
    )
      publishes++
  })
  const update = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/platform/agent-definitions/update' &&
      response.request().method() === 'PUT'
  )
  const preview = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/platform/agent-definitions/preview' &&
      response.request().method() === 'POST'
  )
  await page.getByRole('button', { name: '保存并验证', exact: true }).click()
  const saved = await update
  expect(saved.status()).toBe(200)
  const revision = (await saved.json()).data.draft_revision
  const validated = await preview
  expect(validated.status()).toBe(200)
  expect(validated.request().postDataJSON().expected_revision).toBe(revision)
  expect((await validated.json()).data.validation.valid).toBe(true)
  await expect(page.getByRole('button', { name: '发布', exact: true })).toBeEnabled()
  expect(publishes).toBe(0)
})
