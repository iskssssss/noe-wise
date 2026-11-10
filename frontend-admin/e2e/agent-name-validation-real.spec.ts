import { expect, test, type Page } from '@playwright/test'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'

function required(name: string): string {
  const value = process.env[name]
  if (!value) throw new Error(`缺少 Agent 名称验收参数：${name}`)
  return value
}

test.describe.configure({ retries: 0 })
// 登录凭据不进入 trace；业务证据只保存显式选择的请求正文、响应和页面。
test.use({ trace: 'off', video: 'off' })

test('名称输入边界保护保存发布并使已发布配置实际运行', async ({ page, context }) => {
  test.setTimeout(170_000)
  const mode = required('E2E_AGENT_NAME_VALIDATION')
  expect(['baseline', 'candidate']).toContain(mode)
  const evidence = required('E2E_ARTIFACT_DIR')
  const agent = required('E2E_AGENT_ID')
  const marker = required('E2E_NAME_PROMPT_MARKER')
  const errors: string[] = []
  let submissions = 0
  let publications = 0
  let publication: Record<string, unknown> | undefined
  let command: Record<string, unknown> | undefined
  const receiptReads: { status: number }[] = []
  const recordPage = (target: Page) => {
    target.on('pageerror', (error) => errors.push(error.message))
    target.on('request', (request) => {
      if (request.method() !== 'POST') return
      const path = new URL(request.url()).pathname
      if (path === '/api/v1/chat') submissions++
      if (path === '/api/v1/agents/save-and-publish') publications++
    })
  }
  recordPage(page)
  let trialPage = page
  try {
    await page.goto(`/login?redirect=${encodeURIComponent('/admin/agents/' + agent)}`)
    await page.locator('input[autocomplete="organization"]').fill(required('E2E_TENANT_SLUG'))
    await page.locator('input[autocomplete="username"]').fill(required('E2E_USERNAME'))
    await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
    await page.getByRole('button', { name: '登录', exact: true }).click()
    await expect(page).toHaveURL(new RegExp(`/admin/agents/${agent}$`))
    const name = page.getByLabel('名称', { exact: true })
    await expect(name).toBeEnabled()
    await name.fill('A'.repeat(129))
    await expect(name).toHaveValue('A'.repeat(129))
    await page.getByLabel('系统提示词', { exact: true }).fill(`你是测试智能体。${marker}`)
    if (mode === 'candidate') {
      await page.getByRole('button', { name: '保存并发布', exact: true }).click()
      await expect(page.locator('.el-form-item__error').filter({ hasText: '128' })).toBeVisible()
      expect(publications).toBe(0)
      await expect(page.getByRole('dialog', { name: '保存并发布智能体', exact: true })).toHaveCount(
        0
      )
      await page.screenshot({ path: join(evidence, 'name-validation.png'), fullPage: true })
      await name.fill('😀'.repeat(128))
      await expect(name).toHaveValue('😀'.repeat(128))
    }
    await page.screenshot({ path: join(evidence, 'before-publish.png'), fullPage: true })
    const publish = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        new URL(response.url()).pathname === '/api/v1/agents/save-and-publish'
    )
    await page.getByRole('button', { name: '保存并发布', exact: true }).click()
    await page
      .getByRole('dialog', { name: '保存并发布智能体', exact: true })
      .getByRole('button', { name: '保存并发布', exact: true })
      .click()
    const result = await publish
    command = result.request().postDataJSON()
    const body = await result.json()
    await writeFile(
      join(evidence, 'publication-response.json'),
      JSON.stringify({ command, status: result.status(), body }, null, 2)
    )
    if (mode === 'baseline') {
      expect(result.status()).toBe(500)
      expect(body.code).toBe('INTERNAL_ERROR')
      await expect(page.getByText('保存并发布结果尚未确认', { exact: false }).first()).toBeVisible()
      await expect(name).toBeDisabled()
      const checked = page.waitForResponse(
        (response) => new URL(response.url()).pathname === '/api/v1/agents/save-and-publish/result'
      )
      await page.getByRole('button', { name: '核对原操作', exact: true }).click()
      const check = await checked
      receiptReads.push({ status: check.status() })
      expect(check.status()).toBe(204)
      await expect(name).toBeDisabled()
      const restored = page.waitForResponse(
        (response) => new URL(response.url()).pathname === '/api/v1/agents/save-and-publish/result'
      )
      await page.reload()
      const read = await restored
      receiptReads.push({ status: read.status() })
      expect(read.status()).toBe(204)
      await expect(page.getByText('保存并发布结果尚未确认', { exact: false }).first()).toBeVisible()
      await expect(name).toBeDisabled()
      await writeFile(
        join(evidence, 'locked-after-reload.html'),
        await page.locator('main').innerHTML()
      )
      await page.screenshot({ path: join(evidence, 'locked-after-reload.png'), fullPage: true })
      trialPage = await context.newPage()
      recordPage(trialPage)
      await trialPage.goto(`/admin/chat?agent_id=${agent}`)
    } else {
      expect(result.status()).toBe(200)
      publication = body.data
      expect(publication?.request_id).toBe(command?.request_id)
      expect(String(publication?.agent_id)).toBe(agent)
      await expect(page.getByText('保存并发布已确认', { exact: false })).toBeVisible()
      await expect(name).toBeEnabled()
      await page.getByRole('button', { name: '试用当前发布版本', exact: true }).click()
      await expect(page).toHaveURL(/\/admin\/chat\?agent_id=/)
    }
    await trialPage
      .locator('.el-select')
      .filter({ hasText: '终端用户' })
      .getByRole('combobox')
      .click()
    await trialPage.getByRole('option', { name: 'Runtime E2E User', exact: true }).click()
    await trialPage
      .getByPlaceholder('输入消息', { exact: true })
      .fill('请确认当前已发布配置能够执行。')
    const accepted = trialPage.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        new URL(response.url()).pathname === '/api/v1/chat'
    )
    await trialPage.getByRole('button', { name: '发送', exact: true }).click()
    const response = await accepted
    expect(response.status()).toBe(202)
    const receipt = (await response.json()).data
    await writeFile(
      join(evidence, 'chat-accepted.json'),
      JSON.stringify({ request: response.request().postDataJSON(), receipt }, null, 2)
    )
    await expect(
      trialPage.locator('.debug-message').filter({ hasText: required('E2E_EXPECTED_REPLY') })
    ).toHaveCount(1, { timeout: 100_000 })
    expect(publications).toBe(1)
    expect(submissions).toBe(1)
    expect(errors).toEqual([])
    await trialPage.screenshot({ path: join(evidence, 'runtime-reply.png'), fullPage: true })
    await writeFile(
      join(evidence, 'receipt.json'),
      JSON.stringify(
        {
          mode,
          agentId: agent,
          runId: receipt.execution_id,
          publication,
          command,
          receiptReads,
          publications,
          submissions,
          errors
        },
        null,
        2
      )
    )
  } finally {
    await writeFile(
      join(evidence, 'browser-state.json'),
      JSON.stringify(
        { mode, command, publication, receiptReads, publications, submissions, errors },
        null,
        2
      )
    )
    await writeFile(join(evidence, 'final.html'), await trialPage.locator('body').innerHTML())
    await trialPage.screenshot({ path: join(evidence, 'final.png'), fullPage: true })
  }
})
