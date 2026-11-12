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
test('可选资源目录失败后可保留模板详情并独立安装执行', async ({ page }) => {
  test.setTimeout(170_000)
  const mode = process.env.E2E_AGENT_TEMPLATE_RESOURCES ?? 'candidate'
  expect(['baseline', 'candidate']).toContain(mode)
  const summaryMode = process.env.E2E_TEMPLATE_SUMMARY
  if (summaryMode) expect(['baseline', 'candidate']).toContain(summaryMode)
  const evidence = required('E2E_ARTIFACT_DIR')
  await mkdir(evidence, { recursive: true })
  const templateId = required('E2E_TEMPLATE_ID')
  const templateName = required('E2E_TEMPLATE_NAME')
  const reply = required('E2E_EXPECTED_REPLY')
  const errors: string[] = []
  const network: { path: string; method: string; status: number }[] = []
  let loggedIn = false
  let templateReads = 0
  let resourceReads = 0
  let resourceDrops = 0
  let injectResourceFailure = true
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
  try {
    await page.goto('/login?redirect=/admin/agents')
    await page.locator('input[autocomplete="organization"]').fill(required('E2E_TENANT_SLUG'))
    await page.locator('input[autocomplete="username"]').fill('runtime-e2e-admin')
    await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
    await page.getByRole('button', { name: '登录', exact: true }).click()
    await expect(page).toHaveURL(/\/admin\/agents$/)
    loggedIn = true
    await page.route(
      (url) =>
        url.pathname === '/api/v1/capabilities/definitions' &&
        url.searchParams.get('status') === 'PUBLISHED',
      async (route) => {
        if (!injectResourceFailure) return route.continue()
        const ordinal = ++resourceReads
        const upstream = await route.fetch({ timeout: 15_000 })
        const response = await upstream.json()
        await writeFile(
          join(evidence, `resource-upstream-${ordinal}.json`),
          JSON.stringify(
            {
              fault: '真实资源目录 HTTP 响应已取得，受控丢弃浏览器回包',
              path: new URL(route.request().url()).pathname + new URL(route.request().url()).search,
              status: upstream.status(),
              response
            },
            null,
            2
          )
        )
        expect(upstream.status()).toBe(200)
        expect(response.code).toBe('OK')
        await route.abort('failed')
        resourceDrops++
      }
    )
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
    if (summaryMode) {
      const marker = required('E2E_TEMPLATE_PROMPT_MARKER')
      const selected = items.find((item: { id: string | number }) => String(item.id) === templateId)
      expect(selected).toBeTruthy()
      for (const body of [selected, detailBody.data]) {
        if (summaryMode === 'baseline') {
          expect(body.definition.system_prompt).toContain(marker)
        } else {
          expect(JSON.stringify(body)).not.toContain('"system_prompt"')
          expect(JSON.stringify(body)).not.toContain(marker)
        }
      }
      if (summaryMode === 'candidate') {
        expect(JSON.stringify(listBody)).not.toContain('"system_prompt"')
        expect(JSON.stringify(listBody)).not.toContain(marker)
        expect(JSON.stringify(detailBody)).not.toContain('"system_prompt"')
        expect(JSON.stringify(detailBody)).not.toContain(marker)
      }
    }
    expect(detailBody.data.name).toBe(templateName)
    expect(detailBody.data.definition.resource_slots).toEqual([
      {
        key: 'knowledge',
        label: '可选验收知识',
        description: '可不绑定',
        capability_type: 'KNOWLEDGE_SOURCE'
      }
    ])
    await expect.poll(() => resourceDrops).toBe(1)
    const missing = drawer.getByText(
      '尚未读到模板详情，请点击“重新读取模板”，加载完成后再配置安装资源。',
      { exact: true }
    )
    const install = drawer.getByRole('button', { name: '安装并配置', exact: true })
    if (mode === 'baseline') {
      await expect(missing).toBeVisible()
      await expect(install).toBeDisabled()
      await expect(drawer.getByLabel('可选验收知识', { exact: true })).toHaveCount(0)
    } else {
      await expect(missing).toHaveCount(0)
      await expect(drawer.getByLabel('智能体名称', { exact: true })).toHaveValue(templateName)
      await expect(drawer.getByLabel('可选验收知识', { exact: true })).toBeVisible()
      await expect(drawer.getByLabel('可选验收知识', { exact: true })).toBeDisabled()
      await expect(
        drawer.getByText('可选资源尚未读取，可刷新可用资源，或跳过绑定直接安装。', { exact: true })
      ).toBeVisible()
      await expect(install).toBeEnabled()
      const readsBeforeRefresh = templateReads
      const refresh = drawer.getByRole('button', { name: '刷新可用资源', exact: true })
      await expect(refresh).toBeEnabled()
      await refresh.click()
      await expect.poll(() => resourceDrops).toBe(2)
      await expect(refresh).toBeEnabled()
      expect(templateReads).toBe(readsBeforeRefresh)
      await expect(install).toBeEnabled()
      await expect(drawer.getByLabel('智能体名称', { exact: true })).toHaveValue(templateName)
    }
    await expect
      .poll(() =>
        drawer.evaluate((element) => {
          const rect = element.getBoundingClientRect()
          return rect.left >= -1 && rect.right <= window.innerWidth + 1
        })
      )
      .toBe(true)
    await writeFile(join(evidence, 'resource-failure.html'), await drawer.innerHTML())
    await page.screenshot({ path: join(evidence, `resource-failure-${mode}.png`), fullPage: true })
    injectResourceFailure = false
    if (mode === 'baseline') {
      const request = {
        template_id: templateId,
        name: templateName,
        request_id: randomUUID(),
        resource_bindings: [],
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
      await drawer.getByRole('button', { name: '取消', exact: true }).click()
      await expect(drawer).not.toBeVisible()
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
      expect(request.resource_bindings).toEqual([])
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
    await page
      .getByPlaceholder('输入消息', { exact: true })
      .fill('请确认已安装模板可在未绑定知识资源时完成对话。')
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
          resourceReads,
          resourceDrops,
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
          resourceReads,
          resourceDrops,
          browserInstalls,
          apiInstalls,
          submits,
          agentId,
          sessionId,
          runId,
          errors,
          network
        },
        null,
        2
      )
    )
    if (loggedIn) {
      await writeFile(join(evidence, 'final.html'), await page.locator('body').innerHTML())
      await page.screenshot({
        path: join(evidence, `template-resources-${mode}.png`),
        fullPage: true
      })
    }
  }
})
