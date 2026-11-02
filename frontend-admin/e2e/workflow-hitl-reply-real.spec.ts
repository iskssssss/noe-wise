import { expect, test, type Response } from '@playwright/test'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'

function required(name: string): string {
  const value = process.env[name]
  if (!value) throw Error(`缺少隔离浏览器夹具：${name}`)
  return value
}

test.describe.configure({ retries: 0 })

test('工作流待办回复受理后断连，刷新核对原输入', async ({ page }) => {
  test.setTimeout(180_000)
  const workflowId = required('E2E_WORKFLOW_ID')
  const mode = process.env.E2E_HITL_RESTORE_MODE || 'candidate'
  expect(['baseline', 'candidate']).toContain(mode)
  let createPosts = 0
  page.on('request', (request) => {
    if (
      request.method() === 'POST' &&
      new URL(request.url()).pathname === '/api/v1/builder/workflows/test-sessions'
    )
      createPosts++
  })
  await page.goto(`/login?redirect=${encodeURIComponent('/admin/workflows/' + workflowId)}`)
  await page.locator('input[autocomplete="organization"]').fill(required('E2E_TENANT_SLUG'))
  await page.locator('input[autocomplete="username"]').fill(required('E2E_USERNAME'))
  await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL(new RegExp(`/admin/workflows/${workflowId}$`))
  await page.getByRole('button', { name: '测试', exact: true }).click()
  const panel = page.getByRole('dialog', { name: '工作流测试' })
  await expect(panel.getByLabel('测试消息')).toBeVisible()
  await panel.getByLabel('测试消息').fill('请执行隔离待办确认工作流')
  const created = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      new URL(response.url()).pathname === '/api/v1/builder/workflows/test-sessions'
  )
  await panel.getByRole('button', { name: '创建并测试', exact: true }).click()
  const createResponse = await created
  expect(createResponse.status()).toBe(200)
  const testReceipt = (await createResponse.json()).data
  await expect(panel.getByRole('button', { name: '确认继续', exact: true })).toBeVisible({
    timeout: 120_000
  })

  let replyPosts = 0
  let replyInputId = ''
  let runId = ''
  let originalAction = ''
  let originalGeneration = ''
  let upstreamStatus = 0
  let finishInterception!: () => void
  const interrupted = new Promise<void>((resolve) => {
    finishInterception = resolve
  })
  await page.route('**/api/v1/builder/workflows/test-sessions/reply-receipt?*', (route) =>
    route.fulfill({ status: 204 })
  )
  await page.route('**/api/v1/chat', async (route) => {
    const command = route.request().postDataJSON()
    if (!command.hitl_response) return route.continue()
    replyPosts++
    if (replyPosts > 1) return route.abort('failed')
    replyInputId = String(command.client_message_id || '')
    runId = String(command.hitl_response.execution_id || '')
    expect(command.hitl_response.workflow_input).toBeTruthy()
    originalAction = String(command.hitl_response.workflow_input.wait.action_id)
    originalGeneration = String(command.hitl_response.workflow_input.wait.generation)
    const upstream = await route.fetch()
    upstreamStatus = upstream.status()
    expect(upstreamStatus).toBe(202)
    await route.abort('failed')
    finishInterception()
  })
  await panel.getByRole('button', { name: '确认继续', exact: true }).click()
  await interrupted
  expect(replyInputId).toBeTruthy()
  expect(runId).toBeTruthy()
  expect(replyPosts).toBe(1)
  const evidence = required('E2E_ARTIFACT_DIR')
  const before = await page.evaluate((id) => {
    const entries = Object.entries(sessionStorage).filter(([key]) =>
      key.startsWith('noewise:workflow-test:')
    )
    return {
      retainsWait: entries.some(
        ([, value]) => (JSON.parse(value).replyOperations || []).length > 0
      ),
      retainsInputId: entries.some(([, value]) => value.includes(id))
    }
  }, replyInputId)
  expect(before.retainsWait).toBe(true)
  expect(before.retainsInputId).toBe(true)
  await page.screenshot({
    path: join(evidence, 'workflow-hitl-reply-before-reload.png'),
    fullPage: true
  })
  page.on('dialog', (dialog) => void dialog.accept())
  await page.unroute('**/api/v1/builder/workflows/test-sessions/reply-receipt?*')
  const automaticReads: { inputId: string | null; status: number }[] = []
  const observeReceipt = (response: Response) => {
    const url = new URL(response.url())
    if (url.pathname !== '/api/v1/builder/workflows/test-sessions/reply-receipt') return
    automaticReads.push({ inputId: url.searchParams.get('inputId'), status: response.status() })
  }
  page.on('response', observeReceipt)
  const automaticLookup = page.waitForResponse(
    (response) => {
      const url = new URL(response.url())
      return (
        url.pathname === '/api/v1/builder/workflows/test-sessions/reply-receipt' &&
        url.searchParams.get('inputId') === replyInputId &&
        response.status() === 200
      )
    },
    { timeout: 30_000 }
  )
  await page.reload()
  await expect(page.getByRole('button', { name: '测试', exact: true })).toBeVisible()
  let manualOpenClicks = 0
  if (mode === 'baseline') {
    // 基线只观察页面，不触发测试面板，再手动打开以读取原回执。
    await page.waitForTimeout(5000)
    expect(await panel.isVisible()).toBe(false)
    expect(automaticReads).toEqual([])
    await page.screenshot({
      path: join(evidence, 'workflow-hitl-reply-after-reload-before-opening.png'),
      fullPage: true
    })
    await page.getByRole('button', { name: '测试', exact: true }).click()
    manualOpenClicks++
  }
  await expect(panel).toBeVisible({ timeout: 30_000 })
  const response = await automaticLookup
  page.off('response', observeReceipt)
  const afterReload = {
    mode,
    automaticReads,
    panelVisible: await panel.isVisible(),
    retainedInput: await page.evaluate(
      (id) =>
        Object.entries(sessionStorage).some(
          ([key, value]) => key.startsWith('noewise:workflow-test:') && value.includes(id)
        ),
      replyInputId
    ),
    manualOpenClicks,
    replyPosts,
    createPosts
  }
  expect(manualOpenClicks).toBe(mode === 'baseline' ? 1 : 0)
  expect(createPosts).toBe(1)
  expect(replyPosts).toBe(1)
  expect(response.status()).toBe(200)
  const receipt = (await response.json()).data
  expect(receipt.test_session_id).toBe(String(testReceipt.test_session_id))
  expect(receipt.run_id).toBe(runId)
  expect(receipt.input_id).toBe(replyInputId)
  expect(receipt.action_id).toBe(originalAction)
  expect(String(receipt.generation)).toBe(originalGeneration)
  expect(receipt.kind).toBe('workflow')
  expect(String(receipt.accepted_revision)).toMatch(/^[1-9][0-9]*$/)
  await expect(
    panel.getByText('原待办回复已受理，后续运行状态请以测试结果为准。', { exact: true })
  ).toBeVisible()
  expect(replyPosts).toBe(1)
  expect(createPosts).toBe(1)
  await page.screenshot({
    path: join(evidence, 'workflow-hitl-reply-after-reload.png'),
    fullPage: true
  })
  await writeFile(
    join(evidence, 'workflow-hitl-reply-candidate.json'),
    JSON.stringify(
      {
        workflowId,
        testSessionId: String(testReceipt.test_session_id),
        runId,
        replyInputId,
        upstreamStatus,
        replyPosts,
        createPosts,
        manualOpenClicks,
        mode,
        before,
        afterReload,
        receipt
      },
      null,
      2
    )
  )
})
