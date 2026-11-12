import { expect, test } from '@playwright/test'
import { mkdir, writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { randomUUID } from 'node:crypto'
import type { ChatHistoryResponse } from '../src/api'

function required(name: string): string {
  const value = process.env[name]
  if (!value) throw Error(`缺少模板资源验收参数：${name}`)
  return value
}

test.describe.configure({ retries: 0 })
test.use({ trace: 'off', video: 'off', screenshot: 'off' })
test('工具槽目录读取与实际工具调用', async ({ page }) => {
  test.setTimeout(170_000)
  const mode = process.env.E2E_TEMPLATE_TOOL_MODE ?? 'candidate'
  expect(['baseline', 'candidate']).toContain(mode)
  const evidence = required('E2E_ARTIFACT_DIR')
  await mkdir(evidence, { recursive: true })
  const templateId = required('E2E_TEMPLATE_ID')
  const templateName = required('E2E_TEMPLATE_NAME')
  const reply = required('E2E_EXPECTED_REPLY')
  const errors: string[] = []
  const network: { path: string; method: string; status: number }[] = []
  let loggedIn = false
  let templateReads = 0
  let browserInstalls = 0
  let apiInstalls = 0
  let submits = 0
  let agentId = ''
  let runId = ''
  let sessionId = ''
  page.on('pageerror', (error) => errors.push(error.message))
  page.on('request', (request) => {
    const url = new URL(request.url())
    if (
      url.pathname === '/api/v1/agents/templates/detail' &&
      url.searchParams.get('templateId') === templateId
    )
      templateReads++
    if (request.method() === 'POST' && url.pathname === '/api/v1/agents/install-template')
      browserInstalls++
    if (request.method() === 'POST' && url.pathname === '/api/v1/chat') submits++
  })
  page.on('response', (response) => {
    const url = new URL(response.url())
    if (/^\/api\/v1\/(agents|capabilities|chat)(\/|$)/.test(url.pathname))
      network.push({
        path: url.pathname + url.search,
        method: response.request().method(),
        status: response.status()
      })
  })
  const versions: string[] = []
  let droppedStatus = 0
  await page.route('**/api/v1/capabilities/definitions/versions?**', async (route) => {
    const url = new URL(route.request().url())
    versions.push(url.searchParams.get('definitionId') ?? '')
    if (
      mode === 'baseline' &&
      url.searchParams.get('definitionId') === required('E2E_DROP_DEFINITION')
    ) {
      const response = await route.fetch()
      droppedStatus = response.status()
      await writeFile(
        join(evidence, 'dropped-version.json'),
        JSON.stringify(
          {
            path: url.pathname + url.search,
            status: response.status(),
            body: await response.json()
          },
          null,
          2
        )
      )
      await route.abort('connectionreset')
      return
    }
    await route.continue()
  })
  try {
    await page.goto('/login?redirect=/admin/agents')
    await page.locator('input[autocomplete="organization"]').fill(required('E2E_TENANT_SLUG'))
    await page.locator('input[autocomplete="username"]').fill('runtime-e2e-admin')
    await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
    await page.getByRole('button', { name: '登录', exact: true }).click()
    await expect(page).toHaveURL(/\/admin\/agents$/)
    loggedIn = true
    const listReady = page.waitForResponse(
      (response) => new URL(response.url()).pathname === '/api/v1/agents/templates'
    )
    const detailReady = page.waitForResponse((response) => {
      const url = new URL(response.url())
      return (
        url.pathname === '/api/v1/agents/templates/detail' &&
        url.searchParams.get('templateId') === templateId
      )
    })
    await page.getByRole('button', { name: '从模板安装', exact: true }).click()
    const drawer = page.getByRole('dialog', { name: '从模板安装', exact: true })
    await expect(drawer).toBeVisible()
    const list = await listReady
    expect(list.status()).toBe(200)
    const listBody = await list.json()
    await writeFile(
      join(evidence, 'template-list.json'),
      JSON.stringify(
        {
          method: list.request().method(),
          path: new URL(list.url()).pathname,
          status: list.status(),
          response: listBody
        },
        null,
        2
      )
    )
    const items = listBody.data.items
    expect(items.some((item: { id: string | number }) => String(item.id) === templateId)).toBe(true)
    if (items.length > 1) {
      await drawer.getByLabel('模板', { exact: true }).click()
      await page.getByRole('option', { name: `${templateName} 1.0.0`, exact: true }).click()
    }
    const detail = await detailReady
    const detailBody = await detail.json()
    await writeFile(
      join(evidence, 'template-detail.json'),
      JSON.stringify(
        {
          method: detail.request().method(),
          path: new URL(detail.url()).pathname + new URL(detail.url()).search,
          status: detail.status(),
          response: detailBody
        },
        null,
        2
      )
    )
    expect(detail.status()).toBe(200)
    expect(detailBody.data.name).toBe(templateName)
    expect(detailBody.data.definition.resource_slots[0].capability_type).toBe('TOOL')
    const slot = drawer.getByLabel('可选验收工具', { exact: true })
    const editedName = `${templateName}-已编辑`
    await drawer.getByLabel('智能体名称', { exact: true }).fill(editedName)
    if (mode === 'baseline') {
      await expect.poll(() => droppedStatus).toBe(200)
      await expect.poll(() => versions.length).toBeGreaterThanOrEqual(2)
      await expect(slot).toBeDisabled()
      await expect(drawer).toContainText('可选资源尚未读取')
    } else {
      await slot.click()
      await page.getByRole('option', { name: required('E2E_TOOL_NAME'), exact: true }).click()
      const deploymentSelect = drawer.getByPlaceholder('选择工具部署', { exact: true })
      if (await deploymentSelect.isVisible()) {
        await deploymentSelect.click()
        await page
          .getByRole('option', { name: required('E2E_TOOL_DEPLOYMENT'), exact: true })
          .click()
      }
      await expect(drawer).toContainText(required('E2E_TOOL_DEPLOYMENT'))
      expect(versions).toHaveLength(0)
    }
    await writeFile(join(evidence, 'slot.html'), await drawer.innerHTML())
    await page.screenshot({ path: join(evidence, `slot-${mode}.png`), fullPage: true })
    const install = drawer.getByRole('button', { name: '安装并配置', exact: true })
    if (mode === 'baseline') {
      const request = {
        template_id: templateId,
        name: editedName,
        request_id: randomUUID(),
        resource_bindings: [
          {
            slot_key: 'tool',
            mount: {
              capability_type: 'TOOL',
              capability_definition_id: required('E2E_TOOL_DEFINITION'),
              tool_config: { tool_deployment_id: required('E2E_TOOL_DEPLOYMENT') }
            }
          }
        ],
        delegation_bindings: []
      }
      apiInstalls++
      const response = await page.request.post('/api/v1/agents/install-template', {
        data: request,
        headers: { Authorization: `Bearer ${required('E2E_ADMIN_TOKEN')}` },
        timeout: 20_000
      })
      const body = await response.json()
      await writeFile(
        join(evidence, 'installation.json'),
        JSON.stringify(
          { source: 'independent-api-control', status: response.status(), request, response: body },
          null,
          2
        )
      )
      expect(response.status()).toBe(200)
      agentId = String(body.data.agent.id)
      await page.goto(`/admin/agents/${agentId}`)
    } else {
      const installed = page.waitForResponse(
        (response) =>
          response.request().method() === 'POST' &&
          new URL(response.url()).pathname === '/api/v1/agents/install-template'
      )
      await install.click()
      const response = await installed
      const body = await response.json()
      const request = response.request().postDataJSON()
      await writeFile(
        join(evidence, 'installation.json'),
        JSON.stringify(
          { source: 'browser', status: response.status(), request, response: body },
          null,
          2
        )
      )
      expect(response.status()).toBe(200)
      expect(String(request.template_id)).toBe(templateId)
      expect(request.resource_bindings).toHaveLength(1)
      expect(String(request.resource_bindings[0].mount.capability_definition_id)).toBe(
        required('E2E_TOOL_DEFINITION')
      )
      expect(request.resource_bindings[0].mount.tool_config.tool_deployment_id).toBe(
        required('E2E_TOOL_DEPLOYMENT')
      )
      expect(request.delegation_bindings).toEqual([])
      agentId = String(body.data.agent.id)
    }
    expect(agentId).toMatch(/^[1-9]\d*$/)
    await expect(page).toHaveURL(new RegExp(`/admin/agents/${agentId}$`))
    const users = page.waitForResponse(
      (response) => new URL(response.url()).pathname === '/api/v1/chat/end-users'
    )
    await page.getByRole('button', { name: '试用当前发布版本', exact: true }).click()
    expect((await users).status()).toBe(200)
    await page.locator('.el-select').filter({ hasText: '终端用户' }).getByRole('combobox').click()
    await page.getByRole('option', { name: 'Runtime E2E User', exact: true }).click()
    await page.getByPlaceholder('输入消息', { exact: true }).fill('请调用已安装工具获取当前时间。')
    const historyReady = page.waitForResponse(
      async (response) => {
        if (new URL(response.url()).pathname !== '/api/v1/chat/history' || !response.ok())
          return false
        const history: ChatHistoryResponse = (await response.json()).data
        return !!history.activities?.some(
          (activity) =>
            activity.node_type === 'agent_runtime' &&
            ['completed', 'failed', 'cancelled', 'terminated'].includes(activity.status)
        )
      },
      { timeout: 120_000 }
    )
    const accepted = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        new URL(response.url()).pathname === '/api/v1/chat'
    )
    await page.getByRole('button', { name: '发送', exact: true }).click()
    const response = await accepted
    const body = await response.json()
    const request = response.request().postDataJSON()
    await writeFile(
      join(evidence, 'accepted.json'),
      JSON.stringify({ status: response.status(), request, response: body }, null, 2)
    )
    expect(response.status()).toBe(202)
    sessionId = String(body.data.session_id)
    runId = String(body.data.execution_id)
    const historyResponse = await historyReady
    const history: ChatHistoryResponse = (await historyResponse.json()).data
    await writeFile(join(evidence, 'completed-history.json'), await historyResponse.text())
    expect(history.session_id).toBe(sessionId)
    expect(history.activities?.find((activity) => activity.node_id === runId)?.status).toBe(
      'completed'
    )
    await expect(page.locator('.debug-message').filter({ hasText: reply })).toHaveCount(1)
    expect(templateReads).toBe(1)
    expect(browserInstalls).toBe(mode === 'candidate' ? 1 : 0)
    expect(apiInstalls).toBe(mode === 'baseline' ? 1 : 0)
    expect(submits).toBe(1)
    expect(errors).toEqual([])
    await writeFile(
      join(evidence, 'browser-result.json'),
      JSON.stringify(
        {
          mode,
          templateId,
          templateReads,
          browserInstalls,
          apiInstalls,
          submits,
          agentId,
          sessionId,
          runId
        },
        null,
        2
      )
    )
  } finally {
    await writeFile(
      join(evidence, 'browser-state.json'),
      JSON.stringify(
        {
          mode,
          templateId,
          templateReads,
          browserInstalls,
          apiInstalls,
          submits,
          agentId,
          sessionId,
          runId,
          errors,
          network,
          versions,
          droppedStatus
        },
        null,
        2
      )
    )
    if (loggedIn) {
      await writeFile(join(evidence, 'final.html'), await page.locator('body').innerHTML())
      await page.screenshot({
        path: join(evidence, `template-tool-slot-${mode}.png`),
        fullPage: true
      })
    }
  }
})
