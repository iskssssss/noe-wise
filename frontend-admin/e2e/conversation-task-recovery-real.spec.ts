import { expect, test } from '@playwright/test'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'

function required(name: string) {
  const value = process.env[name]
  if (!value) throw Error(`Missing isolated fixture: ${name}`)
  return value
}

test('任务操作已提交但响应中断后核对原回执', async ({ page }) => {
  const sessionId = required('E2E_SESSION_ID')
  const mode = process.env.E2E_TASK_RECOVERY_MODE ?? 'candidate'
  expect(['baseline', 'candidate']).toContain(mode)
  const responseMode = process.env.E2E_TASK_RESPONSE_MODE ?? 'disconnect'
  expect(['disconnect', '401']).toContain(responseMode)
  let updatePosts = 0
  let receiptReads = 0
  let authRefreshes = 0
  let upstreamStatus = 0
  let operationId = ''
  page.on('request', (request) => {
    const path = new URL(request.url()).pathname
    if (path === '/api/v1/conversation-tasks/update' && request.method() === 'POST') {
      updatePosts++
      operationId = request.postDataJSON().operation_id
    }
    if (path === '/api/v1/conversation-tasks/receipt' && request.method() === 'GET') receiptReads++
    if (path === '/api/v1/auth/refresh' && request.method() === 'POST') authRefreshes++
  })
  await page.goto(
    `/login?redirect=${encodeURIComponent(`/admin/conversation-tasks?sessionId=${sessionId}`)}`
  )
  await page.locator('input[autocomplete="organization"]').fill(required('E2E_TENANT_SLUG'))
  await page.locator('input[autocomplete="username"]').fill(required('E2E_USERNAME'))
  await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
  await page.getByRole('button', { name: '登录', exact: true }).click()
  const row = page.getByRole('row').filter({ hasText: '核对资料' })
  await expect(row).toBeVisible()
  await page.route(
    '**/api/v1/conversation-tasks/update',
    async (route) => {
      const response = await route.fetch()
      upstreamStatus = response.status()
      if (responseMode === '401') {
        await route.fulfill({
          status: 401,
          contentType: 'application/json',
          body: JSON.stringify({ code: 'UNAUTHORIZED', message: '隔离测试：响应中断' })
        })
      } else await route.abort('failed')
    },
    { times: 1 }
  )
  const readOriginal = () =>
    page.waitForResponse((response) => {
      const url = new URL(response.url())
      return (
        url.pathname === '/api/v1/conversation-tasks/receipt' &&
        url.searchParams.get('operation_id') === operationId
      )
    })
  // 候选监听必须先于动作开始，避免错过紧邻请求失败的只读回执。
  const automaticRead = mode === 'candidate' ? readOriginal() : undefined
  await row.getByRole('button', { name: '完成', exact: true }).click()
  await expect.poll(() => upstreamStatus).toBe(200)
  const notice = page.getByText('任务操作结果尚未确认', { exact: false })
  let manualChecks = 0
  let before:
    | { receiptReads: number; updatePosts: number; operationId: string; noticeVisible: boolean }
    | undefined
  let response
  if (mode === 'baseline') {
    await expect(notice).toBeVisible()
    await expect(row.getByRole('button', { name: '完成', exact: true })).toBeDisabled()
    await page.waitForTimeout(5000)
    expect(updatePosts).toBe(1)
    expect(receiptReads).toBe(0)
    before = { receiptReads, updatePosts, operationId, noticeVisible: await notice.isVisible() }
    await page.screenshot({ path: join(required('E2E_ARTIFACT_DIR'), 'before-manual-check.png') })
    const read = readOriginal()
    await page.getByRole('button', { name: '核对原操作', exact: true }).click()
    manualChecks++
    response = await read
  } else {
    response = await automaticRead!
  }
  expect(response.status()).toBe(200)
  const receipt = (await response.json()).data
  expect(receipt.status).toBe('SUCCEEDED')
  expect(receipt.task.task_id).toBe('task-browser')
  await expect(notice).toHaveCount(0)
  await expect(row).toHaveCount(0)
  await expect(page.getByText('当前没有进行中的任务，可勾选“包含已结束任务”查看历史')).toBeVisible()
  await page.getByText('包含已结束任务', { exact: true }).click()
  await expect(row).toBeVisible()
  await expect(row).toContainText('已完成')
  await page.waitForTimeout(500)
  expect(updatePosts).toBe(1)
  expect(receiptReads).toBe(1)
  expect(authRefreshes).toBe(0)
  expect(manualChecks).toBe(mode === 'candidate' ? 0 : 1)
  await page.screenshot({ path: join(required('E2E_ARTIFACT_DIR'), 'after-receipt.png') })
  await writeFile(
    join(required('E2E_ARTIFACT_DIR'), 'browser-receipt.json'),
    JSON.stringify(
      {
        sessionId,
        mode,
        responseMode,
        authRefreshes,
        upstreamStatus,
        updatePosts,
        receiptReads,
        manualChecks,
        operationId,
        before,
        receipt
      },
      null,
      2
    )
  )
})
