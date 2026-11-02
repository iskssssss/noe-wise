import { expect, test } from '@playwright/test'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'
function required(name: string) {
  const value = process.env[name]
  if (!value) throw Error(`Missing fixture: ${name}`)
  return value
}
test('创建时保存模型提示词，断连后核对原请求', async ({ page }) => {
  const writes: string[] = []
  page.on('request', (request) => {
    if (request.method() !== 'GET') writes.push(new URL(request.url()).pathname)
  })
  await page.goto(`/login?redirect=${encodeURIComponent('/platform/system-agents')}`)
  await page.getByLabel('账号', { exact: true }).fill(required('E2E_USERNAME'))
  await page.getByLabel('密码', { exact: true }).fill(required('E2E_PASSWORD'))
  await page.getByRole('button', { name: '登录运营台', exact: true }).click()
  await expect(page).toHaveURL(/\/platform\/system-agents$/)
  await page.getByRole('button', { name: '新增智能体' }).click()
  const drawer = page.getByRole('dialog', { name: '新增系统智能体' })
  await drawer.getByLabel('名称', { exact: true }).fill('浏览器创建助手')
  await drawer.getByLabel('系统提示词（可选）').fill('整理真实页面输入')
  await drawer
    .locator('.el-form-item')
    .filter({ has: page.locator('.el-form-item__label').filter({ hasText: /^模型（可选）$/ }) })
    .locator('.el-select__wrapper')
    .click()
  await page.getByRole('option', { name: '创建测试模型', exact: true }).click()
  const response = page.waitForResponse(
    (r) =>
      new URL(r.url()).pathname === '/api/v1/admin/system-agents/create-with-receipt' &&
      r.request().method() === 'POST'
  )
  await drawer.getByRole('button', { name: '创建并配置' }).click()
  const created = (await (await response).json()).data
  await expect(page).toHaveURL(new RegExp(`/platform/system-agents/${created.agent_id}$`))
  expect(
    writes.filter((path) => path === '/api/v1/admin/system-agents/create-with-receipt')
  ).toHaveLength(1)
  expect(writes).not.toContain('/api/v1/admin/system-agents/update')
  await page.getByRole('button', { name: '发布', exact: true }).click()
  const publishDialog = page.getByRole('dialog', { name: '系统智能体' })
  const publication = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/admin/system-agents/publish' &&
      response.request().method() === 'POST'
  )
  await publishDialog.getByRole('button', { name: '发布', exact: true }).click()
  expect((await publication).status()).toBe(200)
  const prompt = page.getByLabel('系统提示词', { exact: true })
  await prompt.fill('未保存的提示词')
  await page.getByRole('tab', { name: '发布历史' }).click()
  await page.getByRole('button', { name: '恢复为草稿' }).click()
  const restoreDialog = page.getByRole('dialog', { name: '恢复草稿' })
  await expect(restoreDialog.getByText(/未保存的修改也将丢弃/)).toBeVisible()
  const restored = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/admin/system-agents/update' &&
      response.request().method() === 'PUT'
  )
  await restoreDialog.getByRole('button', { name: '恢复为草稿' }).click()
  const restoreResponse = await restored
  expect(restoreResponse.status(), await restoreResponse.text()).toBe(200)
  expect(restoreResponse.request().postDataJSON().config.system_prompt).toBe('整理真实页面输入')
  await page.getByRole('tab', { name: '配置草稿' }).click()
  await expect(prompt).toHaveValue('整理真实页面输入')
  await prompt.fill('直接保存并发布的提示词')
  const savedAgent = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/admin/system-agents/update' &&
      response.request().method() === 'PUT'
  )
  await page.getByRole('button', { name: '保存并继续发布', exact: true }).click()
  const saveResponse = await savedAgent
  expect(saveResponse.status()).toBe(200)
  const savedRevision = (await saveResponse.json()).data.revision
  const republishedAgent = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/admin/system-agents/publish' &&
      response.request().method() === 'POST'
  )
  await page
    .getByRole('dialog', { name: '系统智能体' })
    .getByRole('button', { name: '发布', exact: true })
    .click()
  const republishedResponse = await republishedAgent
  expect(republishedResponse.status()).toBe(200)
  expect(republishedResponse.request().postDataJSON().expected_revision).toBe(savedRevision)
  await page.goto('/platform/system-agents')
  await page.route(
    '**/api/v1/admin/system-agents/create-with-receipt',
    async (route) => {
      await route.fetch()
      await route.abort('failed')
    },
    { times: 1 }
  )
  await page.getByRole('button', { name: '新增智能体' }).click()
  const recovery = page.getByRole('dialog', { name: '新增系统智能体' })
  await recovery.getByLabel('名称', { exact: true }).fill('恢复系统助手')
  await recovery.getByRole('button', { name: '创建并配置' }).click()
  await expect(recovery.getByRole('button', { name: '核对创建结果' })).toBeVisible()
  const result = page.waitForResponse(
    (r) => new URL(r.url()).pathname === '/api/v1/admin/system-agents/create-with-receipt/result'
  )
  await recovery.getByRole('button', { name: '核对创建结果' }).click()
  const recovered = (await (await result).json()).data
  await expect(page).toHaveURL(new RegExp(`/platform/system-agents/${recovered.agent_id}$`))
  expect(
    writes.filter((path) => path === '/api/v1/admin/system-agents/create-with-receipt')
  ).toHaveLength(2)
  await writeFile(
    join(required('E2E_ARTIFACT_DIR'), 'system-agent-create.json'),
    JSON.stringify({ created, recovered })
  )
})

test('已有模型保存后直接确认发布', async ({ page }) => {
  const modelId = required('E2E_MODEL_ID')
  await page.goto(`/login?redirect=${encodeURIComponent('/platform/models/' + modelId)}`)
  await page.getByLabel('账号', { exact: true }).fill(required('E2E_USERNAME'))
  await page.getByLabel('密码', { exact: true }).fill(required('E2E_PASSWORD'))
  await page.getByRole('button', { name: '登录运营台', exact: true }).click()
  await expect(page).toHaveURL(new RegExp(`/platform/models/${modelId}$`))
  await page.getByLabel('名称', { exact: true }).fill('浏览器修改模型')
  const saved = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/admin/models/update' &&
      response.request().method() === 'PUT'
  )
  await page.getByRole('button', { name: '保存并继续发布', exact: true }).click()
  const saveResponse = await saved
  expect(saveResponse.status()).toBe(200)
  const revision = (await saveResponse.json()).data.revision
  const published = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/admin/models/publish' &&
      response.request().method() === 'POST'
  )
  await page
    .getByRole('dialog', { name: '发布模型配置' })
    .getByRole('button', { name: '发布', exact: true })
    .click()
  const publishResponse = await published
  expect(publishResponse.status()).toBe(200)
  expect(publishResponse.request().postDataJSON().expected_revision).toBe(revision)
  expect((await publishResponse.json()).data.has_unpublished_changes).toBe(false)
})

test('新建模型保存后直接确认发布', async ({ page }) => {
  await page.goto(`/login?redirect=${encodeURIComponent('/platform/models/new')}`)
  await page.getByLabel('账号', { exact: true }).fill(required('E2E_USERNAME'))
  await page.getByLabel('密码', { exact: true }).fill(required('E2E_PASSWORD'))
  await page.getByRole('button', { name: '登录运营台', exact: true }).click()
  await expect(page).toHaveURL(/\/platform\/models\/new$/)
  await page.getByLabel('名称', { exact: true }).fill('浏览器新建模型')
  await page
    .locator('.el-form-item')
    .filter({ has: page.locator('.el-form-item__label').filter({ hasText: /^提供方$/ }) })
    .locator('.el-select__wrapper')
    .click()
  await page
    .getByRole('option', { name: /deepseek/i })
    .first()
    .click()
  const model = page.getByRole('combobox', { name: /模型标识/ })
  await model.fill('deepseek-flash')
  await page.getByRole('option', { name: 'deepseek-flash', exact: true }).click()
  await page.getByLabel('API 凭据', { exact: true }).fill('test-key')
  await page.getByLabel('支持最大输出 Token', { exact: true }).fill('4096')
  await page.getByLabel('上下文窗口 Token', { exact: true }).fill('32000')
  await page.getByLabel('本次输出 Token 上限', { exact: true }).fill('2048')
  const saved = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/admin/models/create-with-receipt' &&
      response.request().method() === 'POST'
  )
  await page.getByRole('button', { name: '保存并继续发布', exact: true }).click()
  const response = await saved
  expect(response.status()).toBe(200)
  const receipt = (await response.json()).data
  const published = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/admin/models/publish' &&
      response.request().method() === 'POST'
  )
  await page
    .getByRole('dialog', { name: '发布模型配置' })
    .getByRole('button', { name: '发布', exact: true })
    .click()
  const publishedResponse = await published
  expect(publishedResponse.status()).toBe(200)
  expect(publishedResponse.request().postDataJSON()).toMatchObject({
    id: receipt.model_id,
    expected_revision: 1
  })
  await expect(page).toHaveURL(new RegExp(`/platform/models/${receipt.model_id}$`))
})

test('新建模型响应中断后刷新并核对原回执', async ({ page }) => {
  await page.goto(`/login?redirect=${encodeURIComponent('/platform/models/new')}`)
  await page.getByLabel('账号', { exact: true }).fill(required('E2E_USERNAME'))
  await page.getByLabel('密码', { exact: true }).fill(required('E2E_PASSWORD'))
  await page.getByRole('button', { name: '登录运营台', exact: true }).click()
  await expect(page).toHaveURL(/\/platform\/models\/new$/)
  await page.getByLabel('名称', { exact: true }).fill('恢复创建模型')
  await page
    .locator('.el-form-item')
    .filter({ has: page.locator('.el-form-item__label').filter({ hasText: /^提供方$/ }) })
    .locator('.el-select__wrapper')
    .click()
  await page
    .getByRole('option', { name: /deepseek/i })
    .first()
    .click()
  await page.getByRole('combobox', { name: /模型标识/ }).fill('deepseek-flash')
  await page.getByRole('option', { name: 'deepseek-flash', exact: true }).click()
  await page.getByLabel('API 凭据', { exact: true }).fill('test-recovery-key')
  await page.getByLabel('支持最大输出 Token', { exact: true }).fill('4096')
  await page.getByLabel('上下文窗口 Token', { exact: true }).fill('32000')
  await page.getByLabel('本次输出 Token 上限', { exact: true }).fill('2048')
  let posts = 0
  page.on('request', (request) => {
    if (
      request.method() === 'POST' &&
      new URL(request.url()).pathname === '/api/v1/admin/models/create-with-receipt'
    )
      posts++
  })
  await page.route(
    '**/api/v1/admin/models/create-with-receipt',
    async (route) => {
      const upstream = await route.fetch()
      expect(upstream.status()).toBe(200)
      await route.abort('failed')
    },
    { times: 1 }
  )
  await page.getByRole('button', { name: '保存并继续发布', exact: true }).click()
  await expect(page.getByText(/创建结果尚未确认/)).toBeVisible()
  const stored = await page.evaluate(() =>
    Object.entries(sessionStorage).filter(([key]) => key.startsWith('skillforge.model-create:'))
  )
  expect(JSON.stringify(stored)).not.toContain('test-recovery-key')
  await page.reload()
  await expect(page.getByText(/创建结果尚未确认/)).toBeVisible()
  const receipt = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/admin/models/create-with-receipt/result'
  )
  await page.getByRole('button', { name: '核对创建结果' }).click()
  const result = (await (await receipt).json()).data
  await expect(page).toHaveURL(new RegExp(`/platform/models/${result.model_id}$`))
  expect(posts).toBe(1)
})
