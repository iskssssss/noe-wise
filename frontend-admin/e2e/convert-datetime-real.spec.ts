import { expect, test } from '@playwright/test'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import type { ChatHistoryResponse } from '../src/api'

function required(name: string): string {
  const value = process.env[name]
  if (!value) throw Error(`缺少时间转换验收参数：${name}`)
  return value
}

test.describe.configure({ retries: 0 })
test.use({ trace: 'off', video: 'off', screenshot: 'off' })
test('时间转换的确定错误可回传模型纠正并展示实际结果', async ({ page }) => {
  test.setTimeout(170_000)
  const mode = required('E2E_CONVERT_DATETIME')
  expect(['baseline', 'candidate']).toContain(mode)
  const evidence = required('E2E_ARTIFACT_DIR')
  const errors: string[] = []
  const network: { path: string; method: string; status: number }[] = []
  let submits = 0
  let trialOpened = false
  page.on('pageerror', (error) => errors.push(error.message))
  page.on('request', (request) => {
    if (request.method() === 'POST' && new URL(request.url()).pathname === '/api/v1/chat') submits++
  })
  page.on('response', (response) => {
    const url = new URL(response.url())
    if (url.pathname.startsWith('/api/v1/chat'))
      network.push({
        path: url.pathname + url.search,
        method: response.request().method(),
        status: response.status()
      })
  })
  try {
    await page.goto(
      `/login?redirect=${encodeURIComponent('/admin/agents/' + required('E2E_AGENT_ID'))}`
    )
    await page.locator('input[autocomplete="organization"]').fill(required('E2E_TENANT_SLUG'))
    await page.locator('input[autocomplete="username"]').fill('runtime-e2e-admin')
    await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
    await page.getByRole('button', { name: '登录', exact: true }).click()
    const users = page.waitForResponse(
      (response) => new URL(response.url()).pathname === '/api/v1/chat/end-users'
    )
    await page.getByRole('button', { name: '试用当前发布版本', exact: true }).click()
    expect((await users).status()).toBe(200)
    trialOpened = true
    await page.locator('.el-select').filter({ hasText: '终端用户' }).getByRole('combobox').click()
    await page.getByRole('option', { name: 'Runtime E2E User', exact: true }).click()
    await page
      .getByPlaceholder('输入消息', { exact: true })
      .fill(
        '将 2026-01-01T00:00:00Z 转为 Asia/Beijing；如收到确定的参数错误，再改用 Asia/Shanghai，并展示实际工具结果。'
      )
    const observedHistory = page.waitForResponse(
      async (response) => {
        if (new URL(response.url()).pathname !== '/api/v1/chat/history' || !response.ok())
          return false
        const history: ChatHistoryResponse = (await response.json()).data
        if (mode === 'baseline')
          return !!history.processes?.some(
            (process) => process.type === 'tool' && process.status === 'outcome_unknown'
          )
        return !!history.activities?.some(
          (activity) =>
            activity.node_type === 'agent_runtime' &&
            ['completed', 'failed', 'cancelled', 'outcome_unknown', 'terminated'].includes(
              activity.status
            )
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
    await writeFile(
      join(evidence, 'accepted.json'),
      JSON.stringify(
        { status: response.status(), request: response.request().postDataJSON(), response: body },
        null,
        2
      )
    )
    expect(response.status()).toBe(202)
    const receipt = body.data
    await writeFile(join(evidence, 'receipt.json'), JSON.stringify(receipt, null, 2))
    const observedResponse = await observedHistory
    const observed: ChatHistoryResponse = (await observedResponse.json()).data
    await writeFile(join(evidence, 'observed-history.json'), await observedResponse.text())
    const run = observed.activities?.find((activity) => activity.node_id === receipt.execution_id)
    expect(run).toBeTruthy()
    const toolProcesses =
      observed.processes?.filter(
        (process) => process.run_id === receipt.execution_id && process.type === 'tool'
      ) ?? []
    const expectedStatuses = mode === 'baseline' ? ['outcome_unknown'] : ['failed', 'succeeded']
    expect(toolProcesses.map((process) => process.status)).toEqual(expectedStatuses)
    const reply = page.locator('.debug-message').filter({ hasText: 'CONVERT_DATETIME_OBSERVED' })
    if (mode === 'baseline') {
      expect(['completed', 'failed', 'cancelled', 'terminated']).not.toContain(run?.status)
      await expect(reply).toHaveCount(0)
    } else {
      expect(run?.status).toBe('completed')
      await expect(reply).toHaveCount(1)
      const text = await reply.innerText()
      await writeFile(join(evidence, 'actual-reply.txt'), text)
      expect(
        [...text.matchAll(/"success"\s*:\s*(true|false)/g)].map((match) => match[1] === 'true')
      ).toEqual([false, true])
      expect(text).toContain('2026-01-01T08:00:00+08:00')
      expect(text).toContain('Asia/Shanghai')
    }
    await page.getByRole('tab', { name: '运行过程', exact: true }).click()
    const processPanel = page.getByRole('tabpanel', { name: '运行过程', exact: true })
    const toolRows = processPanel.getByRole('row').filter({ hasText: '工具调用' })
    await expect(toolRows).toHaveCount(expectedStatuses.length)
    for (let index = 0; index < expectedStatuses.length; index++)
      await expect(
        toolRows.nth(index).getByRole('cell', { name: expectedStatuses[index], exact: true })
      ).toBeVisible()
    if (mode === 'baseline') await expect(toolRows).toContainText('工具结果未知')
    expect(submits).toBe(1)
    expect(errors).toEqual([])
    await writeFile(
      join(evidence, 'browser-result.json'),
      JSON.stringify({ mode, submits, run, toolProcesses, errors }, null, 2)
    )
  } finally {
    await writeFile(
      join(evidence, 'browser-state.json'),
      JSON.stringify({ mode, submits, errors, network }, null, 2)
    )
    if (trialOpened) {
      await writeFile(join(evidence, 'final.html'), await page.locator('body').innerHTML())
      await page.screenshot({
        path: join(evidence, `convert-datetime-${mode}.png`),
        fullPage: true
      })
    }
  }
})
