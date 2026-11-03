import { expect, test } from '@playwright/test'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'

function required(name: string): string {
  const value = process.env[name]
  if (!value) throw new Error(`缺少隔离验收参数：${name}`)
  return value
}

test.describe.configure({ retries: 0 })

test('唯一已发布知识库由用户确认挂载', async ({ page }) => {
  test.setTimeout(120_000)
  const agent = required('E2E_AGENT_ID')
  const kb = required('E2E_KNOWLEDGE_ID')
  const evidence = required('E2E_ARTIFACT_DIR')
  const baseline = process.env.E2E_AGENT_KNOWLEDGE_MODE === 'baseline'
  let mountPosts = 0,
    publishPosts = 0,
    chatPosts = 0,
    manualSelectionClicks = 0
  const lookups: string[] = []
  page.on('request', (request) => {
    const url = new URL(request.url())
    if (request.method() === 'POST' && url.pathname === '/api/v1/agents/capability-mounts')
      mountPosts++
    if (request.method() === 'POST' && url.pathname === '/api/v1/agents/publish') publishPosts++
    if (request.method() === 'POST' && url.pathname === '/api/v1/chat') chatPosts++
    if (request.method() === 'GET' && url.pathname === '/api/v1/knowledge/bases/mount-option')
      lookups.push(String(url.searchParams.get('kbId')))
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
  const list = page.waitForResponse(
    (response) =>
      response.request().method() === 'GET' &&
      new URL(response.url()).pathname === '/api/v1/knowledge/bases'
  )
  const option = page.waitForResponse((response) => {
    const url = new URL(response.url())
    return (
      response.request().method() === 'GET' &&
      url.pathname === '/api/v1/knowledge/bases/mount-option' &&
      url.searchParams.get('kbId') === kb
    )
  })
  await page.getByRole('option', { name: '知识来源', exact: true }).click()
  const listResponse = await list
  expect(listResponse.status()).toBe(200)
  const bases = (await listResponse.json()).data.items
  expect(bases).toHaveLength(1)
  expect(String(bases[0].id)).toBe(kb)
  const selector = drawer.locator('.el-form-item').filter({ hasText: '知识库' })
  if (baseline) {
    expect(lookups).toEqual([])
    await selector.locator('.el-select__wrapper').click()
    manualSelectionClicks++
    await page.getByRole('option', { name: new RegExp(required('E2E_KNOWLEDGE_NAME')) }).click()
    manualSelectionClicks++
  }
  const optionResponse = await option
  expect(optionResponse.status()).toBe(200)
  const detail = (await optionResponse.json()).data
  expect(String(detail.kb_id)).toBe(kb)
  expect(String(detail.active_revision_id)).toBe(required('E2E_KNOWLEDGE_REVISION'))
  expect(String(detail.definition_id)).toBe(required('E2E_KNOWLEDGE_DEFINITION'))
  expect(String(detail.version_id)).toBe(required('E2E_KNOWLEDGE_VERSION'))
  await expect(selector).toContainText(required('E2E_KNOWLEDGE_NAME'))
  await expect(drawer.getByRole('button', { name: '添加能力', exact: true })).toBeEnabled()
  expect(mountPosts).toBe(0)
  expect(publishPosts).toBe(0)
  expect(chatPosts).toBe(0)
  await page.screenshot({ path: join(evidence, 'knowledge-before-confirm.png'), fullPage: true })
  const mounted = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      new URL(response.url()).pathname === '/api/v1/agents/capability-mounts'
  )
  await drawer.getByRole('button', { name: '添加能力', exact: true }).click()
  const response = await mounted
  expect(response.status()).toBe(201)
  const command = response.request().postDataJSON()
  const receipt = (await response.json()).data
  expect(String(command.agent_id)).toBe(agent)
  expect(command.payload.capability_type).toBe('KNOWLEDGE_SOURCE')
  expect(String(command.payload.capability_definition_id)).toBe(
    required('E2E_KNOWLEDGE_DEFINITION')
  )
  const matches = receipt.mounts.filter(
    (mount: { capability_definition_id: string }) =>
      String(mount.capability_definition_id) === required('E2E_KNOWLEDGE_DEFINITION')
  )
  expect(matches).toHaveLength(1)
  const mount = matches[0]
  expect(String(mount.capability_version_id)).toBe(required('E2E_KNOWLEDGE_VERSION'))
  expect(mount.knowledge_config.mode).toBe('FOLLOW_ACTIVE')
  expect(mountPosts).toBe(1)
  expect(publishPosts).toBe(0)
  expect(chatPosts).toBe(0)
  await page.screenshot({ path: join(evidence, 'knowledge-mounted.png'), fullPage: true })
  if (process.env.E2E_KNOWLEDGE_CASE) {
    const mode = process.env.E2E_KNOWLEDGE_CASE
    const searchQuery = required('E2E_KNOWLEDGE_QUERY')
    const sentinel = required('E2E_KNOWLEDGE_SENTINEL')
    const sourceVersion = required('E2E_KNOWLEDGE_SOURCE_VERSION')
    await page.goto(`/admin/knowledge/${kb}`)
    await page.getByRole('tab', { name: '检索验证', exact: true }).click()
    await page.getByLabel('检索问题', { exact: true }).fill(searchQuery)
    const searches: { payload: unknown; items: unknown[] }[] = []
    for (const pinned of [false, true]) {
      if (pinned) {
        await page
          .getByRole('switch', { name: '仅本次检索覆盖参数', exact: true })
          .locator('..')
          .click()
        await page.getByRole('textbox', { name: '本次来源版本', exact: true }).fill(sourceVersion)
      }
      const searched = page.waitForResponse(
        (response) =>
          response.request().method() === 'POST' &&
          new URL(response.url()).pathname === '/api/v1/knowledge/search'
      )
      await page.getByRole('button', { name: '检索', exact: true }).click()
      const response = await searched
      expect(response.status()).toBe(200)
      const envelope = await response.json()
      expect(envelope.code).toBe('OK')
      const payload = response.request().postDataJSON()
      expect(payload.query).toBe(searchQuery)
      expect(payload.kb_id).toBe(kb)
      expect(payload.source_version).toBe(pinned ? sourceVersion : undefined)
      const items = envelope.data.items
      expect(items).toHaveLength(mode === 'baseline' ? 0 : 1)
      if (mode === 'candidate') {
        expect(items[0].content).toContain(sentinel)
        await expect(page.getByText(/本次召回 1 条结果/)).toBeVisible()
      } else
        await expect(page.getByText('本次没有召回结果，请检查文档状态或调整问题')).toBeVisible()
      searches.push({ payload, items })
    }
    await page.screenshot({ path: join(evidence, 'knowledge-case-search.png'), fullPage: true })
    await writeFile(
      join(evidence, 'knowledge-case-search.json'),
      JSON.stringify({ mode, searchQuery, sentinel, sourceVersion, searches }, null, 2)
    )
  }
  await writeFile(
    join(evidence, 'agent-knowledge-single.json'),
    JSON.stringify({
      agentId: agent,
      kbId: kb,
      option: detail,
      mount,
      mountPosts,
      publishPosts,
      chatPosts,
      manualSelectionClicks,
      lookups
    })
  )
})
