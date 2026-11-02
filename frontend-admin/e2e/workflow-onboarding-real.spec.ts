import { expect, test } from '@playwright/test'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'
function required(name: string) {
  const value = process.env[name]
  if (!value) throw Error(`Missing isolated fixture: ${name}`)
  return value
}

test('机构共享定义可一次保存并校验精确修订', async ({ page }) => {
  await page.goto(`/login?redirect=${encodeURIComponent('/admin/agent-definitions')}`)
  await page.locator('input[autocomplete="organization"]').fill(required('E2E_PUBLISH_SLUG'))
  await page.locator('input[autocomplete="username"]').fill('recovery-admin')
  await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL(/\/admin\/agent-definitions$/)
  const row = page.getByRole('row').filter({ hasText: '机构保存校验验收' })
  await row.getByRole('button', { name: '查看与维护' }).click()
  const drawer = page.getByRole('dialog', { name: '维护机构共享定义' })
  await drawer.getByLabel('用途说明').fill('机构保存校验浏览器验收')
  let publishes = 0
  page.on('request', (request) => {
    if (
      request.method() === 'POST' &&
      new URL(request.url()).pathname === '/api/v1/tenant/agent-definitions/publish'
    )
      publishes++
  })
  const update = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/tenant/agent-definitions/update' &&
      response.request().method() === 'PUT'
  )
  const preview = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/tenant/agent-definitions/preview' &&
      response.request().method() === 'POST'
  )
  await drawer.getByRole('button', { name: '保存并校验' }).click()
  const saved = await update
  expect(saved.status(), await saved.text()).toBe(200)
  const revision = (await saved.json()).data.draft_revision
  const validated = await preview
  expect(validated.status(), await validated.text()).toBe(200)
  expect(validated.request().postDataJSON().expected_revision).toBe(revision)
  expect((await validated.json()).data.validation.valid).toBe(true)
  await expect(drawer.getByRole('button', { name: '发布此修订' })).toBeEnabled()
  expect(publishes).toBe(0)
})

test('新建智能体响应丢失后刷新并自动核对原回执', async ({ page }) => {
  await page.goto(`/login?redirect=${encodeURIComponent('/admin/agents')}`)
  await page.locator('input[autocomplete="organization"]').fill(required('E2E_PUBLISH_SLUG'))
  await page.locator('input[autocomplete="username"]').fill('recovery-admin')
  await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL(/\/admin\/agents$/)
  await page.getByRole('button', { name: '新建智能体', exact: true }).click()
  const drawer = page.getByRole('dialog', { name: '新建智能体' })
  await drawer.getByLabel('名称', { exact: true }).fill('创建回执验收')
  let upstream = 0,
    posts = 0
  page.on('request', (request) => {
    if (
      request.method() === 'POST' &&
      new URL(request.url()).pathname === '/api/v1/agents/create-with-receipt'
    )
      posts++
  })
  await page.route(
    '**/api/v1/agents/create-with-receipt',
    async (route) => {
      upstream = (await route.fetch()).status()
      await route.abort('failed')
    },
    { times: 1 }
  )
  const sent = page.waitForRequest(
    (request) =>
      new URL(request.url()).pathname === '/api/v1/agents/create-with-receipt' &&
      request.method() === 'POST'
  )
  await drawer.getByRole('button', { name: '创建并配置' }).click()
  const command = (await sent).postDataJSON()
  await expect.poll(() => upstream).toBe(200)
  await expect(page.getByRole('button', { name: '核对创建结果' })).toBeEnabled()
  page.on('dialog', (dialog) => dialog.accept())
  const lookup = page.waitForResponse(
    (response) => new URL(response.url()).pathname === '/api/v1/agents/create-with-receipt/result'
  )
  await page.reload()
  const response = await lookup
  expect(response.status()).toBe(200)
  const receipt = (await response.json()).data
  expect(receipt.request_id).toBe(command.request_id)
  await writeFile(
    join(required('E2E_ARTIFACT_DIR'), 'agent-create-recovery.json'),
    JSON.stringify({ command, receipt })
  )
  await expect(page).toHaveURL(new RegExp(`/admin/agents/${receipt.agent_id}$`))
  expect(posts).toBe(1)
})

test('知识槽按类型读取当前发布版本并安装模板', async ({ page }) => {
  await page.goto(`/login?redirect=${encodeURIComponent('/admin/agents')}`)
  await page.locator('input[autocomplete="organization"]').fill(required('E2E_PUBLISH_SLUG'))
  await page.locator('input[autocomplete="username"]').fill('recovery-admin')
  await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL(/\/admin\/agents$/)
  let optionReads = 0,
    versionReads = 0
  page.on('request', (request) => {
    const path = new URL(request.url()).pathname
    if (request.method() === 'GET' && path === '/api/v1/capabilities/requirements/options')
      optionReads++
    if (request.method() === 'GET' && path === '/api/v1/capabilities/definitions/versions')
      versionReads++
  })
  await page.getByRole('button', { name: '从模板安装' }).click()
  const drawer = page.getByRole('dialog', { name: '从模板安装' })
  await expect(drawer.getByLabel('智能体名称')).toHaveValue('知识槽验收模板')
  await drawer.getByRole('button', { name: '取消', exact: true }).click()
  await expect(drawer).not.toBeVisible()
  await expect(page.getByRole('dialog', { name: '关闭表单' })).not.toBeVisible()
  optionReads = 0
  await page.getByRole('button', { name: '从模板安装' }).click()
  await expect(drawer.getByLabel('智能体名称')).toHaveValue('知识槽验收模板')
  await drawer.getByLabel('智能体名称').fill('用户编辑的名称')
  await drawer.getByRole('button', { name: '取消', exact: true }).click()
  const discard = page.getByRole('dialog', { name: '关闭表单' })
  await expect(discard).toBeVisible()
  await discard.getByRole('button', { name: '继续编辑' }).click()
  await expect(drawer.getByLabel('智能体名称')).toHaveValue('用户编辑的名称')
  await drawer.getByLabel('智能体名称').fill('知识槽验收模板')
  await drawer
    .locator('.el-form-item')
    .filter({ hasText: '验收知识' })
    .locator('.el-select')
    .click()
  await page.getByRole('option', { name: 'execution-knowledge-template-a', exact: true }).click()
  await expect(drawer.getByRole('button', { name: '安装并配置' })).toBeEnabled()
  expect(optionReads).toBe(1)
  expect(versionReads).toBe(0)
  let upstream = 0,
    upstreamBody = '',
    posts = 0
  page.on('request', (request) => {
    if (
      request.method() === 'POST' &&
      new URL(request.url()).pathname === '/api/v1/agents/install-template'
    )
      posts++
  })
  await page.route(
    '**/api/v1/agents/install-template',
    async (route) => {
      const result = await route.fetch()
      upstream = result.status()
      upstreamBody = await result.text()
      await route.abort('failed')
    },
    { times: 1 }
  )
  const installed = page.waitForRequest(
    (request) =>
      new URL(request.url()).pathname === '/api/v1/agents/install-template' &&
      request.method() === 'POST'
  )
  await drawer.getByRole('button', { name: '安装并配置' }).click()
  const command = (await installed).postDataJSON()
  expect(command.resource_bindings).toHaveLength(1)
  expect(command.resource_bindings[0].mount.capability_type).toBe('KNOWLEDGE_SOURCE')
  await expect.poll(() => upstream).not.toBe(0)
  expect(upstream, upstreamBody).toBe(200)
  await expect(page.getByRole('button', { name: '核对原安装结果' })).toBeEnabled()
  page.on('dialog', (dialog) => dialog.accept())
  await page.reload()
  await expect(page.getByText(command.request_id, { exact: false }).first()).toBeVisible()
  const lookup = page.waitForResponse(
    (response) => new URL(response.url()).pathname === '/api/v1/agents/install-template/result'
  )
  await page.getByRole('button', { name: '核对原安装结果' }).click()
  const response = await lookup
  expect(response.status()).toBe(200)
  const receipt = (await response.json()).data
  expect(receipt.request_id).toBe(command.request_id)
  await writeFile(
    join(required('E2E_ARTIFACT_DIR'), 'agent-template-recovery.json'),
    JSON.stringify(receipt)
  )
  await expect(page).toHaveURL(new RegExp(`/admin/agents/${receipt.agent_id}$`))
  expect(posts).toBe(1)
})

test('智能体保存发布响应丢失后刷新并核对原回执', async ({ page }) => {
  const agent = required('E2E_PUBLISH_AGENT_ID')
  await page.goto(`/login?redirect=${encodeURIComponent('/admin/agents/' + agent)}`)
  await page.locator('input[autocomplete="organization"]').fill(required('E2E_PUBLISH_SLUG'))
  await page.locator('input[autocomplete="username"]').fill('recovery-admin')
  await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL(new RegExp(`/admin/agents/${agent}$`))
  await page
    .locator('.el-form-item')
    .filter({ hasText: '名称' })
    .first()
    .locator('input')
    .fill('刷新恢复的智能体')
  let upstream = 0
  let posts = 0
  page.on('request', (request) => {
    if (
      request.method() === 'POST' &&
      new URL(request.url()).pathname === '/api/v1/agents/save-and-publish'
    )
      posts++
  })
  await page.route(
    '**/api/v1/agents/save-and-publish',
    async (route) => {
      upstream = (await route.fetch()).status()
      await route.abort('failed')
    },
    { times: 1 }
  )
  const sent = page.waitForRequest(
    (request) =>
      new URL(request.url()).pathname === '/api/v1/agents/save-and-publish' &&
      request.method() === 'POST'
  )
  await page.getByRole('button', { name: '保存并发布', exact: true }).click()
  await page.getByRole('button', { name: '保存并发布', exact: true }).last().click()
  const command = (await sent).postDataJSON()
  await expect.poll(() => upstream).toBe(200)
  await expect(page.getByRole('button', { name: '核对原操作' })).toBeEnabled()
  page.on('dialog', (dialog) => dialog.accept())
  const lookup = page.waitForResponse(
    (response) => new URL(response.url()).pathname === '/api/v1/agents/save-and-publish/result'
  )
  await page.reload()
  const response = await lookup
  expect(response.status()).toBe(200)
  const receipt = (await response.json()).data
  expect(receipt.request_id).toBe(command.request_id)
  await writeFile(
    join(required('E2E_ARTIFACT_DIR'), 'agent-publish-recovery.json'),
    JSON.stringify(receipt)
  )
  await expect(page.getByText('保存并发布已确认', { exact: false })).toBeVisible()
  expect(posts).toBe(1)
})

test('新工作流从测试面板保存后继续配置测试', async ({ page }) => {
  await page.goto(`/login?redirect=${encodeURIComponent('/admin/workflows/create')}`)
  await page.locator('input[autocomplete="organization"]').fill(required('E2E_TENANT_SLUG'))
  await page.locator('input[autocomplete="username"]').fill(required('E2E_USERNAME'))
  await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
  const login = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/auth/login' &&
      response.request().method() === 'POST'
  )
  await page.getByRole('button', { name: '登录', exact: true }).click()
  expect((await login).status()).toBe(200)
  await expect(page).toHaveURL(/\/admin\/workflows\/create$/, { timeout: 30_000 })
  await page
    .locator('.el-form-item')
    .filter({ hasText: '业务标识' })
    .locator('input')
    .fill('test-from-editor')
  await page
    .locator('.el-form-item')
    .filter({ hasText: '名称' })
    .first()
    .locator('input')
    .fill('测试入口工作流')
  const writes: string[] = []
  page.on('request', (request) => {
    const path = new URL(request.url()).pathname
    if (request.method() !== 'GET' && path.startsWith('/api/v1/builder/workflows'))
      writes.push(path)
  })
  const validation = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/builder/workflows/validate-content' &&
      response.request().method() === 'POST'
  )
  await page.getByRole('button', { name: '校验', exact: true }).first().click()
  expect((await validation).status()).toBe(200)
  await expect(page).toHaveURL(/\/admin\/workflows\/create$/)
  expect(writes).toEqual(['/api/v1/builder/workflows/validate-content'])
  await page.getByRole('button', { name: '测试', exact: true }).click()
  const panel = page.getByRole('dialog', { name: '工作流测试' })
  await expect(panel.getByText('请先保存工作流草稿，再配置测试。')).toBeVisible()
  const created = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/builder/workflows' &&
      response.request().method() === 'POST'
  )
  await panel.getByRole('button', { name: '先保存草稿' }).click()
  expect((await created).status()).toBe(200)
  await expect(page).toHaveURL(/\/admin\/workflows\/test-from-editor$/)
  await expect(panel.getByLabel('测试消息')).toBeVisible()
})

test('Agent 内安装并挂载工作流', async ({ page }) => {
  const agent = required('E2E_AGENT_ID')
  const writes: string[] = []
  const historyReads: string[] = []
  page.on('request', (request) => {
    const url = new URL(request.url())
    if (request.method() !== 'GET') writes.push(url.pathname)
    if (url.pathname === '/api/v1/builder/workflows/history') historyReads.push(url.pathname)
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
  const workflowList = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/builder/workflows' &&
      response.request().method() === 'GET'
  )
  await drawer.getByRole('button', { name: '接入工作流', exact: true }).click()
  const listed = await workflowList
  expect(listed.status()).toBe(200)
  const directory = await listed.json()
  await writeFile(
    join(required('E2E_ARTIFACT_DIR'), 'workflow-list.json'),
    JSON.stringify(directory)
  )
  expect(directory.data.items).toEqual(
    expect.arrayContaining([
      expect.objectContaining({ workflow_id: 'browser-flow', name: '浏览器工作流' })
    ])
  )
  const panel = drawer.locator('section[aria-label="工作流接入"]')
  await panel
    .locator('.el-form-item')
    .filter({ hasText: '已发布工作流' })
    .locator('.el-select__wrapper')
    .click()
  const detail = page.waitForResponse(
    (response) => new URL(response.url()).pathname === '/api/v1/builder/workflows/history/detail'
  )
  await page.getByRole('option', { name: '浏览器工作流', exact: true }).click()
  const detailResponse = await detail
  expect(detailResponse.status()).toBe(200)
  const selectedWorkflow = directory.data.items.find(
    (item: { workflow_id: string }) => item.workflow_id === 'browser-flow'
  )
  expect(new URL(detailResponse.url()).searchParams.get('definitionVersionId')).toBe(
    String(selectedWorkflow.active_definition_version_id)
  )
  expect(historyReads).toEqual([])
  await panel
    .locator('.el-form-item')
    .filter({ hasText: '知识资料' })
    .locator('.el-select__wrapper')
    .click()
  await page.getByRole('option', { name: /浏览器知识/ }).click()
  await panel.getByLabel('区域（必填）').fill('华东')
  const submitted = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/agents/workflows/onboard' &&
      response.request().method() === 'POST'
  )
  await panel.getByRole('button', { name: '接入智能体', exact: true }).click()
  await page.getByRole('button', { name: '确认接入', exact: true }).click()
  const response = await submitted
  expect(response.status()).toBe(200)
  const command = response.request().postDataJSON()
  expect(command.bindings.capabilities).toHaveLength(1)
  expect(command.bindings.capabilities[0].capability).toMatchObject({
    type: 'KNOWLEDGE_SOURCE',
    capability_id: expect.any(String),
    version_id: expect.any(String)
  })
  const envelope = await response.json()
  expect(envelope.data.status).toBe('SUCCEEDED')
  await writeFile(
    join(required('E2E_ARTIFACT_DIR'), 'onboarding-receipt.json'),
    JSON.stringify(envelope)
  )
  await expect(panel.getByText(/接入已确认，安装修订/)).toBeVisible()
  const result = await page.evaluate(async (requestId) => {
    const token = localStorage.getItem('skillforge.access_token')
    const response = await fetch(
      `/api/v1/agents/workflows/onboard/result?requestId=${encodeURIComponent(requestId)}`,
      { headers: { Authorization: `Bearer ${token}` } }
    )
    return { status: response.status, body: await response.json() }
  }, envelope.data.request_id)
  expect(result.status).toBe(200)
  expect(result.body.data).toEqual(envelope.data)
  const mountOptions = await page.evaluate(async () => {
    const token = localStorage.getItem('skillforge.access_token')
    const response = await fetch('/api/v1/agents/capability-mounts/workflow-options', {
      headers: { Authorization: `Bearer ${token}` }
    })
    return { status: response.status, body: await response.json() }
  })
  expect(mountOptions.status).toBe(200)
  expect(mountOptions.body.data).toEqual(
    expect.arrayContaining([
      expect.objectContaining({
        workflow_id: 'browser-flow',
        definition_id: expect.any(String),
        installation_revision: expect.any(String)
      })
    ])
  )
  expect(writes.filter((path) => path === '/api/v1/agents/workflows/onboard')).toHaveLength(1)
  await page.reload()
  await page.getByRole('tab', { name: /能力挂载/ }).click()
  await page.getByRole('button', { name: '添加能力', exact: true }).first().click()
  const secondDrawer = page.getByRole('dialog', { name: '添加能力挂载' })
  await secondDrawer
    .locator('.el-form-item')
    .filter({ hasText: '能力类型' })
    .locator('.el-select__wrapper')
    .click()
  await page.getByRole('option', { name: '工作流', exact: true }).click()
  await expect(secondDrawer.getByText('可用工作流均已挂载，可在能力列表中查看。')).toBeVisible()
  await expect(secondDrawer.getByRole('button', { name: '添加能力', exact: true })).toBeDisabled()
  expect(writes.some((path) => path.includes('/agents/publish'))).toBe(false)
})

test('按用途选择同名技能并挂载正确能力', async ({ page }) => {
  const agent = required('E2E_AGENT_ID')
  await page.goto(`/login?redirect=${encodeURIComponent('/admin/agents/' + agent)}`)
  await page.locator('input[autocomplete="organization"]').fill(required('E2E_TENANT_SLUG'))
  await page.locator('input[autocomplete="username"]').fill(required('E2E_USERNAME'))
  await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL(new RegExp(`/admin/agents/${agent}$`))
  await page.getByRole('tab', { name: /能力挂载/ }).click()
  await page.getByRole('button', { name: '添加能力', exact: true }).first().click()
  const drawer = page.getByRole('dialog', { name: '添加能力挂载' })
  const skill = drawer.locator('.el-form-item').filter({ hasText: '按名称、用途或标识查找' })
  await skill.locator('.el-select__wrapper').click()
  await skill.locator('input').fill('患者资料')
  const selected = page.getByRole('option').filter({ hasText: '查询患者资料' })
  await expect(selected).toBeVisible()
  await expect(page.getByRole('option').filter({ hasText: '查询订单资料' })).toHaveCount(0)
  await selected.click()
  const created = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/agents/capability-mounts' &&
      response.request().method() === 'POST'
  )
  await drawer.getByRole('button', { name: '添加能力', exact: true }).click()
  const response = await created
  expect(response.status()).toBe(201)
  expect(response.request().postDataJSON().payload.capability_definition_id).toBe(
    required('E2E_SKILL_DEFINITION_ID')
  )
  await expect(drawer).toBeHidden()
  await page.getByRole('button', { name: '添加能力', exact: true }).first().click()
  const reopened = page.getByRole('dialog', { name: '添加能力挂载' })
  const choices = reopened.locator('.el-form-item').filter({ hasText: '按名称、用途或标识查找' })
  await expect(choices).toContainText('service-skill')
  await choices.locator('.el-select__wrapper').click()
  await expect(page.getByRole('option').filter({ hasText: '查询患者资料' })).toHaveCount(0)
  await expect(page.getByRole('option').filter({ hasText: '查询订单资料' })).toBeVisible()
})

test('机构智能体使用默认模型时可修改并保存配置', async ({ page }) => {
  const agent = required('E2E_AGENT_ID')
  await page.goto(`/login?redirect=${encodeURIComponent('/admin/agents/' + agent)}`)
  await page.locator('input[autocomplete="organization"]').fill(required('E2E_TENANT_SLUG'))
  await page.locator('input[autocomplete="username"]').fill(required('E2E_USERNAME'))
  await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL(new RegExp(`/admin/agents/${agent}$`))
  const model = page.locator('.el-form-item').filter({ hasText: '使用模型' })
  await expect(model).toContainText('继承机构默认模型')
  await page
    .locator('.el-form-item')
    .filter({ hasText: '名称' })
    .first()
    .locator('input')
    .fill('默认模型配置验收')
  const saved = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/agents/update' &&
      response.request().method() === 'PATCH'
  )
  await page.getByRole('button', { name: '保存草稿', exact: true }).click()
  const response = await saved
  expect(response.status()).toBe(200)
  expect(response.request().postDataJSON().payload.model_config_id).toBe('')
  await expect(page.getByText('继承机构默认模型').first()).toBeVisible()
  await expect(page.getByRole('button', { name: '发布', exact: true })).toHaveCount(1)
  await expect(page.getByRole('button', { name: '保存并发布', exact: true })).toHaveCount(0)
  const current = await page.evaluate(async (id) => {
    const token = localStorage.getItem('skillforge.access_token')
    const response = await fetch(`/api/v1/agents/detail?agentId=${encodeURIComponent(id)}`, {
      headers: { Authorization: `Bearer ${token}` }
    })
    return { status: response.status, body: await response.json() }
  }, agent)
  expect(current.status).toBe(200)
  expect(current.body.data.model_config_id).toBeNull()
  await page
    .locator('.el-form-item')
    .filter({ hasText: '名称' })
    .first()
    .locator('input')
    .fill('未发布的修改')
  await expect(page.getByRole('button', { name: '发布', exact: true })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '保存并发布', exact: true })).toHaveCount(1)
  const rejected = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/agents/save-and-publish' &&
      response.request().method() === 'POST'
  )
  await page.getByRole('button', { name: '保存并发布', exact: true }).click()
  await page.getByRole('button', { name: '保存并发布', exact: true }).last().click()
  const result = await rejected
  expect(result.status()).toBe(422)
  expect((await result.json()).code).toBe('INSTITUTION_MODEL_SELECTION_REQUIRED')
  await expect(page.getByText('请先在机构设置中配置默认对话模型，再发布智能体')).toBeVisible()
  const [settings] = await Promise.all([
    page.waitForEvent('popup'),
    page.getByRole('link', { name: '查看机构默认模型设置（新标签页）' }).click()
  ])
  await expect(settings.getByText('留空表示该用途尚未配置', { exact: false })).toBeVisible()
  await expect(
    page.locator('.el-form-item').filter({ hasText: '名称' }).first().locator('input')
  ).toHaveValue('未发布的修改')
  const after = await page.evaluate(async (id) => {
    const token = localStorage.getItem('skillforge.access_token')
    const response = await fetch(`/api/v1/agents/detail?agentId=${encodeURIComponent(id)}`, {
      headers: { Authorization: `Bearer ${token}` }
    })
    return (await response.json()).data
  }, agent)
  expect(after.name).toBe('默认模型配置验收')
})

test('工作流节点内编辑知识声明并保存草稿', async ({ page }) => {
  await page.goto(`/login?redirect=${encodeURIComponent('/admin/workflows/browser-flow')}`)
  await page.locator('input[autocomplete="organization"]').fill(required('E2E_TENANT_SLUG'))
  await page.locator('input[autocomplete="username"]').fill(required('E2E_USERNAME'))
  await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL(/\/admin\/workflows\/browser-flow$/)
  await page.locator('[data-node-id="knowledge"]').click()
  const panel = page.locator('aside[aria-label="节点配置"]')
  await panel.getByLabel('结果字段名').fill('evidence')
  await panel.getByRole('button', { name: '高级声明配置' }).click()
  const name = panel.getByLabel('名称', { exact: true }).last()
  await name.fill('节点内编辑的知识资料')
  const saved = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/builder/workflows/draft' &&
      response.request().method() === 'PUT'
  )
  await page.getByRole('button', { name: '保存草稿', exact: true }).click()
  const response = await saved
  expect(response.status()).toBe(200)
  const definition = (await response.json()).data.definition
  expect(definition.meta.capability_requirements[0].label).toBe('节点内编辑的知识资料')
})

test('工作流节点选择工具后自动创建声明并保存', async ({ page }) => {
  await page.goto(`/login?redirect=${encodeURIComponent('/admin/workflows/browser-tool-flow')}`)
  await page.locator('input[autocomplete="organization"]').fill(required('E2E_TENANT_SLUG'))
  await page.locator('input[autocomplete="username"]').fill(required('E2E_USERNAME'))
  await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL(/\/admin\/workflows\/browser-tool-flow$/)
  await page.locator('[data-node-id="tool"]').click()
  const panel = page.locator('aside[aria-label="节点配置"]')
  await panel.getByLabel('添加已发布工具', { exact: true }).click()
  await page.getByRole('option', { name: /浏览器时间工具/ }).click()
  await expect(
    panel.locator('.el-form-item').filter({ hasText: '调用工具' }).first()
  ).toContainText('浏览器时间工具 · tool_1')
  const saved = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/builder/workflows/draft' &&
      response.request().method() === 'PUT'
  )
  await page.getByRole('button', { name: '保存草稿', exact: true }).click()
  const response = await saved
  expect(response.status()).toBe(200)
  const definition = (await response.json()).data.definition
  expect(definition.meta.capability_requirements).toEqual(
    expect.arrayContaining([
      expect.objectContaining({ slot: 'tool_1', capability_id: 'tool:browser-selection' })
    ])
  )
  expect(definition.meta.tools).toEqual(
    expect.arrayContaining([expect.objectContaining({ name: 'tool_1', requirement: 'tool_1' })])
  )
  expect(definition.nodes.find((node: { id: string }) => node.id === 'tool').tool).toBe('tool_1')
})

test('知识查询节点重复选择来源时复用已有声明', async ({ page }) => {
  await page.goto(`/login?redirect=${encodeURIComponent('/admin/workflows/browser-flow')}`)
  await page.locator('input[autocomplete="organization"]').fill(required('E2E_TENANT_SLUG'))
  await page.locator('input[autocomplete="username"]').fill(required('E2E_USERNAME'))
  await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL(/\/admin\/workflows\/browser-flow$/)
  await page.locator('[data-node-id="knowledge"]').click()
  const panel = page.locator('aside[aria-label="节点配置"]')
  await panel.getByLabel('添加已发布知识来源', { exact: true }).click()
  await page.getByRole('option', { name: /浏览器知识/ }).click()
  await expect(
    panel.locator('.el-form-item').filter({ hasText: '知识能力槽' }).first()
  ).toContainText('节点内编辑的知识资料 · knowledge')
  await panel.getByLabel('知识查询内容').fill('检查复用后的查询')
  const saved = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/builder/workflows/draft' &&
      response.request().method() === 'PUT'
  )
  await page.getByRole('button', { name: '保存草稿', exact: true }).click()
  const response = await saved
  expect(response.status()).toBe(200)
  const definition = (await response.json()).data.definition
  expect(definition.meta.capability_requirements).toHaveLength(1)
  expect(definition.meta.capability_requirements[0]).toMatchObject({
    slot: 'knowledge',
    type: 'knowledge_source'
  })
  expect(definition.nodes.find((node: { id: string }) => node.id === 'knowledge').requirement).toBe(
    'knowledge'
  )
})

test('调试页仅有一个可用对象时预选但不创建会话', async ({ page }) => {
  let writes = 0
  page.on('request', (request) => {
    if (
      request.method() === 'POST' &&
      ['/api/v1/chat/sessions', '/api/v1/chat/sessions/create-with-receipt'].includes(
        new URL(request.url()).pathname
      )
    )
      writes++
  })
  await page.goto(`/login?redirect=${encodeURIComponent('/admin/chat')}`)
  await page.locator('input[autocomplete="organization"]').fill(required('E2E_SINGLE_SLUG'))
  await page.locator('input[autocomplete="username"]').fill('single-admin')
  await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL(/\/admin\/chat$/)
  await expect(page.getByText('已选择唯一可用的对话对象，请确认终端用户后发送。')).toBeVisible()
  await expect(page.locator('.session-setup')).toContainText('唯一可用智能体')
  await expect(page.getByRole('combobox', { name: '终端用户', exact: true })).toHaveValue('')
  expect(writes).toBe(0)
})

test('会话创建响应丢失后刷新并只读核对原会话', async ({ page }) => {
  await page.goto(`/login?redirect=${encodeURIComponent('/admin/chat')}`)
  await page.locator('input[autocomplete="organization"]').fill(required('E2E_SINGLE_SLUG'))
  await page.locator('input[autocomplete="username"]').fill('single-admin')
  await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.getByText('已选择唯一可用的对话对象，请确认终端用户后发送。')).toBeVisible()
  await page.getByRole('combobox', { name: '终端用户', exact: true }).click()
  await page.getByRole('option', { name: '回执测试用户', exact: true }).click()
  let upstream = 0,
    posts = 0,
    submissions = 0
  page.on('request', (request) => {
    const path = new URL(request.url()).pathname
    if (request.method() === 'POST' && path === '/api/v1/chat/sessions/create-with-receipt') posts++
    if (request.method() === 'POST' && path === '/api/v1/chat') submissions++
  })
  await page.route(
    '**/api/v1/chat/sessions/create-with-receipt',
    async (route) => {
      upstream = (await route.fetch()).status()
      await route.abort('failed')
    },
    { times: 1 }
  )
  const sent = page.waitForRequest(
    (request) =>
      new URL(request.url()).pathname === '/api/v1/chat/sessions/create-with-receipt' &&
      request.method() === 'POST'
  )
  await page.getByPlaceholder('输入消息').fill('连接中断后保留的草稿')
  await page.getByRole('button', { name: '发送', exact: true }).click()
  const command = (await sent).postDataJSON()
  await expect.poll(() => upstream).toBe(200)
  await expect(page.getByRole('button', { name: '核对创建结果' })).toBeEnabled()
  page.on('dialog', (dialog) => dialog.accept())
  const lookup = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/chat/sessions/create-with-receipt/result'
  )
  await page.reload()
  const response = await lookup
  expect(response.status()).toBe(200)
  const receipt = (await response.json()).data
  expect(receipt.request_id).toBe(command.request_id)
  await expect(page.getByRole('button', { name: '核对创建结果' })).toHaveCount(0)
  expect(posts).toBe(1)
  expect(submissions).toBe(0)
  await writeFile(
    join(required('E2E_ARTIFACT_DIR'), 'session-create-recovery.json'),
    JSON.stringify({ command, receipt, posts, submissions })
  )
})

test('新团队只有一个已发布成员时预选并可直接保存发布', async ({ page }) => {
  let writes = 0
  page.on('request', (request) => {
    if (request.method() === 'POST' && new URL(request.url()).pathname.startsWith('/api/v1/teams'))
      writes++
  })
  await page.goto(`/login?redirect=${encodeURIComponent('/admin/teams')}`)
  await page.locator('input[autocomplete="organization"]').fill(required('E2E_SINGLE_SLUG'))
  await page.locator('input[autocomplete="username"]').fill('single-admin')
  await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await page.getByRole('button', { name: '新增团队' }).click()
  const drawer = page.getByRole('dialog', { name: '新增团队' })
  await expect(drawer.getByText('已预选唯一可用成员并设为主智能体，可按需修改。')).toBeVisible()
  await expect(drawer.locator('.el-select').nth(0)).toContainText('唯一可用智能体')
  await expect(drawer.locator('.el-select').nth(1)).toContainText('唯一可用智能体')
  expect(writes).toBe(0)
  await drawer.getByLabel('名称', { exact: true }).fill('唯一成员团队')
  const published = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/teams/save-and-publish' &&
      response.request().method() === 'POST'
  )
  await drawer.getByRole('button', { name: '保存并发布' }).click()
  await page
    .getByRole('dialog', { name: '保存并发布团队' })
    .getByRole('button', { name: '保存并发布' })
    .click()
  const response = await published
  expect(response.status()).toBe(200)
  const result = (await response.json()).data
  expect(result.status).toBe('SUCCEEDED')
  expect(result.team.members).toHaveLength(1)
  expect(result.team.primary_agent_id).toBe(result.team.members[0].agent_id)
  expect(writes).toBe(1)
})
