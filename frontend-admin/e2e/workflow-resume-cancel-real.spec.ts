import { expect, test } from '@playwright/test'
import { existsSync } from 'node:fs'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'

function required(name: string): string {
  const value = process.env[name]
  if (!value) throw new Error(`缺少隔离浏览器夹具：${name}`)
  return value
}

test.describe.configure({ retries: 0 })

test('待办已受理后取消续跑，核对原执行是否收敛', async ({ page }) => {
  test.setTimeout(150_000)
  const workflow = required('E2E_WORKFLOW_ID')
  const evidence = required('E2E_ARTIFACT_DIR')
  let replyPosts = 0
  let closePosts = 0
  let inputId = ''
  let runId = ''
  let testSessionId = ''
  let closeStatus = ''
  page.on('request', (request) => {
    const path = new URL(request.url()).pathname
    if (request.method() === 'POST' && path === '/api/v1/chat') {
      const command = request.postDataJSON()
      if (command.hitl_response) {
        replyPosts++
        inputId = String(command.client_message_id || '')
        runId = String(command.hitl_response.execution_id || '')
      }
    }
    if (request.method() === 'POST' && path === '/api/v1/builder/workflows/test-sessions/close')
      closePosts++
  })
  try {
    await page.goto(`/login?redirect=${encodeURIComponent('/admin/workflows/' + workflow)}`)
    await page.locator('input[autocomplete="organization"]').fill(required('E2E_TENANT_SLUG'))
    await page.locator('input[autocomplete="username"]').fill(required('E2E_USERNAME'))
    await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
    await page.getByRole('button', { name: '登录', exact: true }).click()
    await expect(page).toHaveURL(new RegExp(`/admin/workflows/${workflow}$`))
    await page.getByRole('button', { name: '测试', exact: true }).click()
    const panel = page.getByRole('dialog', { name: '工作流测试' })
    await panel.getByLabel('测试消息').fill('验证人工回复后的取消')
    const created = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        new URL(response.url()).pathname === '/api/v1/builder/workflows/test-sessions'
    )
    await panel.getByRole('button', { name: '创建并测试', exact: true }).click()
    const createdResponse = await created
    expect(createdResponse.status()).toBe(200)
    testSessionId = String((await createdResponse.json()).data.test_session_id)
    await expect(panel.getByRole('button', { name: '确认继续', exact: true })).toBeVisible({
      timeout: 120_000
    })
    const submitted = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        new URL(response.url()).pathname === '/api/v1/chat' &&
        !!response.request().postDataJSON()?.hitl_response
    )
    await panel.getByRole('button', { name: '确认继续', exact: true }).click()
    expect((await submitted).status()).toBe(202)
    expect(inputId).toBeTruthy()
    expect(runId).toBeTruthy()
    expect(replyPosts).toBe(1)
    await expect
      .poll(() => existsSync(required('E2E_RESUME_CANCEL_GATE')), { timeout: 30_000 })
      .toBe(true)
    await panel.getByRole('button', { name: '取消并关闭测试', exact: true }).click()
    await expect
      .poll(
        async () => {
          closeStatus = await page.evaluate(async (id) => {
            const token = localStorage.getItem('skillforge.access_token')
            const response = await fetch(
              `/api/v1/builder/workflows/test-sessions?testSessionId=${encodeURIComponent(id)}`,
              { headers: { Authorization: `Bearer ${token}` } }
            )
            return response.ok ? String((await response.json()).data?.close_status || '') : ''
          }, testSessionId)
          return closeStatus
        },
        { timeout: 60_000 }
      )
      .toBe('closed')
    expect(replyPosts).toBe(1)
    expect(closePosts).toBe(1)
  } finally {
    await writeFile(
      join(evidence, 'resume-cancel-browser.json'),
      JSON.stringify(
        { testSessionId, runId, inputId, replyPosts, closePosts, closeStatus },
        null,
        2
      )
    )
    await page.screenshot({ path: join(evidence, 'resume-cancel.png'), fullPage: true })
  }
})
