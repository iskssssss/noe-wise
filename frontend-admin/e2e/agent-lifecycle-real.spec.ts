import { expect, test, type Page } from '@playwright/test'
import { existsSync } from 'node:fs'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'

function required(name: string): string {
  const value = process.env[name]
  if (!value) throw new Error(`缺少生命周期验收参数：${name}`)
  return value
}

async function trial(page: Page, expectedReply: string) {
  const users = page.waitForResponse(
    (response) => new URL(response.url()).pathname === '/api/v1/chat/end-users'
  )
  await page.getByRole('button', { name: '试用当前发布版本', exact: true }).click()
  expect((await users).status()).toBe(200)
  await page.locator('.el-select').filter({ hasText: '终端用户' }).getByRole('combobox').click()
  await page.getByRole('option', { name: 'Runtime E2E User', exact: true }).click()
  await page.getByPlaceholder('输入消息', { exact: true }).fill('验证智能体当前状态与发布配置。')
  const created = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      new URL(response.url()).pathname === '/api/v1/chat/sessions/create-with-receipt'
  )
  const accepted = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' && new URL(response.url()).pathname === '/api/v1/chat'
  )
  await page.getByRole('button', { name: '发送', exact: true }).click()
  const session = await created,
    chat = await accepted
  expect(session.status()).toBe(200)
  expect(chat.status()).toBe(202)
  const sessionId = String((await session.json()).data.session_id)
  const runId = String((await chat.json()).data.execution_id)
  await expect(page.locator('.debug-message').filter({ hasText: expectedReply })).toHaveCount(1, {
    timeout: 120_000
  })
  return { sessionId, runId, command: chat.request().postDataJSON() as Record<string, unknown> }
}

test.describe.configure({ retries: 0 })

test('已发布智能体停用后拒绝新执行，重新发布恢复运行并保留历史', async ({ page }) => {
  test.setTimeout(210_000)
  const agent = required('E2E_AGENT_ID'),
    evidence = required('E2E_ARTIFACT_DIR')
  const expectedReply = required('E2E_EXPECTED_REPLY')
  const baseline = process.env.E2E_AGENT_LIFECYCLE === 'baseline'
  const facts: Record<string, unknown> = {
    agentId: agent,
    mode: baseline ? 'baseline' : 'candidate',
    runs: []
  }
  try {
    await page.goto(`/login?redirect=${encodeURIComponent('/admin/agents/' + agent)}`)
    await page.locator('input[autocomplete="organization"]').fill(required('E2E_TENANT_SLUG'))
    await page.locator('input[autocomplete="username"]').fill(required('E2E_USERNAME'))
    await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
    await page.getByRole('button', { name: '登录', exact: true }).click()
    await expect(page).toHaveURL(new RegExp(`/admin/agents/${agent}$`))
    const first = await trial(page, expectedReply)
    facts.runs = [first.runId]
    facts.firstSession = first.sessionId
    await page.screenshot({ path: join(evidence, 'published-runtime.png'), fullPage: true })
    await page.goto('/admin/agents')
    const row = page
      .getByRole('row')
      .filter({ has: page.locator(`a[href="/admin/agents/${agent}"]`) })
    await expect(row).toBeVisible()
    if (baseline) {
      await expect(row.getByRole('button', { name: '停用', exact: true })).toHaveCount(0)
      const deleted = page.waitForResponse(
        (response) => new URL(response.url()).pathname === '/api/v1/agents/delete'
      )
      await row.getByRole('button', { name: '删除', exact: true }).click()
      await page
        .getByRole('dialog', { name: '删除智能体', exact: true })
        .getByRole('button', { name: '删除智能体', exact: true })
        .click()
      const deletion = await deleted
      expect(deletion.status()).toBe(409)
      const deletionBody = await deletion.json()
      expect(deletionBody.message).toBe('已发布版本请停用或归档，不能删除')
      facts.deletion = { status: deletion.status(), body: deletionBody }
      await expect(
        page.getByText('当前状态不允许此操作，请核对最新状态后再决定是否继续', { exact: false })
      ).toBeVisible()
      await page.screenshot({
        path: join(evidence, 'published-delete-rejected.png'),
        fullPage: true
      })
      const disabled = await page.evaluate(async (id) => {
        const response = await fetch('/api/v1/agents/update', {
          method: 'PATCH',
          headers: {
            'Content-Type': 'application/json',
            Authorization: `Bearer ${localStorage.getItem('skillforge.access_token')}`
          },
          body: JSON.stringify({ agent_id: id, payload: { status: 'disabled' } })
        })
        return { status: response.status, body: await response.json() }
      }, agent)
      expect(disabled.status).toBe(200)
      facts.disable = disabled
    } else {
      await expect(row.getByRole('button', { name: '删除', exact: true })).toHaveCount(0)
      const disabled = page.waitForResponse(
        (response) =>
          response.request().method() === 'PATCH' &&
          new URL(response.url()).pathname === '/api/v1/agents/update'
      )
      await row.getByRole('button', { name: '停用', exact: true }).click()
      await page
        .getByRole('dialog', { name: '停用智能体', exact: true })
        .getByRole('button', { name: '停用', exact: true })
        .click()
      const result = await disabled
      expect(result.status()).toBe(200)
      expect(result.request().postDataJSON()).toEqual({
        agent_id: agent,
        payload: { status: 'disabled' }
      })
      facts.disable = { status: result.status(), body: await result.json() }
      await expect(row).toContainText('已停用')
    }
    const rejected = await page.evaluate(async (command) => {
      const response = await fetch('/api/v1/chat', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${localStorage.getItem('skillforge.access_token')}`
        },
        body: JSON.stringify({
          ...command,
          client_message_id: crypto.randomUUID(),
          content: [{ type: 'text', text: '停用后此输入必须拒绝' }]
        })
      })
      return { status: response.status, body: await response.json() }
    }, first.command)
    facts.rejected = rejected
    expect(rejected.status).toBe(403)
    expect(rejected.body.code).toBe('FORBIDDEN')
    expect(rejected.body.message).toBe('当前执行身份或Agent授权已失效')
    await writeFile(join(evidence, 'disabled-rejection.json'), JSON.stringify(rejected, null, 2))
    await expect
      .poll(() => existsSync(join(evidence, 'disabled-verified')), { timeout: 30_000 })
      .toBe(true)
    await page.goto(`/admin/agents/${agent}`)
    await expect(page.getByText('发布修订', { exact: false }).first()).toBeVisible()
    const detail = await page.evaluate(async (id) => {
      const response = await fetch(`/api/v1/agents/detail?agentId=${encodeURIComponent(id)}`, {
        headers: { Authorization: `Bearer ${localStorage.getItem('skillforge.access_token')}` }
      })
      return { status: response.status, body: await response.json() }
    }, agent)
    expect(detail.status).toBe(200)
    expect(detail.body.data.status).toBe('disabled')
    expect(detail.body.data.has_draft_changes).toBe(false)
    facts.disabledDetail = detail.body.data
    if (baseline) {
      await expect(page.getByRole('button', { name: '发布', exact: true })).toBeDisabled()
      await expect(page.getByRole('button', { name: '重新发布', exact: true })).toHaveCount(0)
      await page.screenshot({
        path: join(evidence, 'disabled-publication-blocked.png'),
        fullPage: true
      })
    } else {
      await expect(page.getByRole('button', { name: '重新发布', exact: true })).toBeEnabled()
      const published = page.waitForResponse(
        (response) => new URL(response.url()).pathname === '/api/v1/agents/publish-with-receipt'
      )
      await page.getByRole('button', { name: '重新发布', exact: true }).click()
      await page
        .getByRole('dialog', { name: '发布智能体', exact: true })
        .getByRole('button', { name: '发布', exact: true })
        .click()
      const result = await published
      expect(result.status()).toBe(200)
      const receipt = (await result.json()).data
      expect(receipt.agent_id).toBe(agent)
      expect(receipt.request_id).toBe(result.request().postDataJSON().request_id)
      expect(receipt.revision).toBe(detail.body.data.published_revision + 1)
      facts.publication = receipt
      await expect(page.getByText('发布已确认：修订', { exact: false })).toBeVisible()
      const second = await trial(page, expectedReply)
      expect(second.runId).not.toBe(first.runId)
      facts.runs = [first.runId, second.runId]
      facts.secondSession = second.sessionId
      const history = await page.evaluate(async (id) => {
        const response = await fetch(
          `/api/v1/chat/history?session_id=${encodeURIComponent(id)}&limit=30`,
          {
            headers: { Authorization: `Bearer ${localStorage.getItem('skillforge.access_token')}` }
          }
        )
        return { status: response.status, body: await response.json() }
      }, first.sessionId)
      expect(history.status).toBe(200)
      expect(JSON.stringify(history.body)).toContain(expectedReply)
      facts.originalHistory = history
      await page.screenshot({ path: join(evidence, 'republished-runtime.png'), fullPage: true })
    }
  } finally {
    await writeFile(join(evidence, 'agent-lifecycle.json'), JSON.stringify(facts, null, 2))
  }
})
