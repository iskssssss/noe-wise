import { expect, test, type Page, type Response } from '@playwright/test'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'

function fixture(name: string): string {
  const value = process.env[name]
  if (!value) throw new Error(`Missing isolated browser fixture: ${name}`)
  return value
}

function responseAt(page: Page, path: string, method: string) {
  return page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === path && response.request().method() === method
  )
}

async function dataOf(response: Response) {
  expect(response.ok(), `HTTP ${response.status()} at ${new URL(response.url()).pathname}`).toBe(
    true
  )
  const envelope = await response.json()
  expect(envelope.code).toBe('OK')
  return envelope.data
}

async function openTicket(page: Page, ticketId: string) {
  const detailResponse = responseAt(page, '/api/v1/handoff/tickets/detail', 'GET')
  await page.getByRole('button', { name: '查看工单', exact: true }).click()
  const detail = await dataOf(await detailResponse)
  expect(detail.ticket.id).toBe(ticketId)
  await expect(page.locator('.el-drawer')).toBeVisible()
  return detail
}

test('real browser creates, replies, restores both clients and resolves a handoff ticket', async ({
  page,
  context
}) => {
  const runId = fixture('E2E_RUN_ID')
  const text = `坐席已人工核实申请 ${runId}`
  const note = `人工核实完成并已通知用户 ${runId}`
  const evidence = fixture('E2E_ARTIFACT_DIR')
  const mode = process.env.E2E_HANDOFF_PROGRESS_MODE ?? 'candidate'
  const chat = await context.newPage()
  const errors: string[] = []
  let chatSubmissions = 0
  let replyPosts = 0
  let detailReads = 0
  for (const client of [page, chat]) {
    client.on('pageerror', (error) => errors.push(error.message))
    client.on('request', (request) => {
      const path = new URL(request.url()).pathname
      if (request.method() === 'POST' && path === '/api/v1/chat') chatSubmissions++
      if (request.method() === 'POST' && path === '/api/v1/handoff/tickets/replies') replyPosts++
      if (request.method() === 'GET' && path === '/api/v1/handoff/tickets/detail') detailReads++
    })
  }

  // 通过真实渠道登记终端用户；地址明确指向本次随机端口的隔离后端。
  await chat.goto(`http://127.0.0.1:${fixture('E2E_CHAT_PORT')}/`)
  await chat
    .getByRole('textbox', { name: '服务地址', exact: true })
    .fill(fixture('E2E_BACKEND_URL'))
  await chat
    .getByRole('textbox', { name: '你的称呼', exact: true })
    .fill(`人工协助验收用户 ${runId}`)
  await chat.locator('input[type="password"]').fill(fixture('E2E_CHANNEL_TOKEN'))
  const catalogResponse = responseAt(chat, '/api/v1/chat/agents', 'GET')
  const profileResponse = responseAt(chat, '/api/v1/chat/client-profile', 'POST')
  await chat.getByRole('button', { name: '进入会话', exact: true }).click()
  const catalog = await dataOf(await catalogResponse)
  expect(catalog.items.map((agent: { id: string }) => String(agent.id))).toContain(
    fixture('E2E_AGENT_ID')
  )
  const profile = await dataOf(await profileResponse)
  const user = String(profile.end_user_id)
  const messageInput = chat.getByRole('textbox', { name: '消息', exact: true })
  await expect(messageInput).toBeEnabled()
  await expect(chat.locator('.header-target')).toContainText('Runtime E2E Agent')

  // /plan off 是本地控制命令，创建真实产品 Session，不提交模型输入。
  await messageInput.fill('/plan off')
  const send = chat.getByRole('button', { name: '发送消息', exact: true })
  await expect(send).toBeEnabled()
  const [sessionResponse, planResponse] = await Promise.all([
    responseAt(chat, '/api/v1/chat/sessions', 'POST'),
    responseAt(chat, '/api/v1/chat/runs/plan-mode', 'POST'),
    send.click()
  ])
  const { session_id: sessionId } = await dataOf(sessionResponse)
  await dataOf(planResponse)
  await expect(chat.getByText('已选择普通模式。', { exact: true })).toBeVisible()
  expect(typeof sessionId).toBe('string')

  // 在同一渠道会话中执行真实 Runtime；模型由本次隔离服务提供。
  await messageInput.fill(`请简短说明处理流程 ${runId}`)
  const runtimeResponse = responseAt(chat, '/api/v1/chat', 'POST')
  await send.click()
  const runtime = await dataOf(await runtimeResponse)
  expect(String(runtime.session_id)).toBe(sessionId)
  const runtimeRunId = String(runtime.execution_id)
  expect(runtimeRunId).not.toBe('undefined')
  await expect(
    chat.locator('.message.assistant').filter({ hasText: fixture('E2E_EXPECTED_REPLY') })
  ).toHaveCount(1, { timeout: 120_000 })

  // 登录走正式密码与安全 Session 入口；没有注入令牌或绕过 MFA。
  await page.goto('/login?redirect=/admin/chat')
  await page.locator('input[autocomplete="organization"]').fill(fixture('E2E_TENANT_SLUG'))
  await page.locator('input[autocomplete="username"]').fill(fixture('E2E_USERNAME'))
  await page.locator('input[autocomplete="current-password"]').fill(fixture('E2E_PASSWORD'))
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL(/\/admin\/chat$/)
  await page.locator('.el-select').filter({ hasText: '历史会话' }).getByRole('combobox').click()
  await page.getByRole('option', { name: sessionId, exact: true }).click()
  await page.getByRole('tab', { name: '任务', exact: true }).click()
  const ticketResponse = responseAt(page, '/api/v1/handoff/tickets', 'POST')
  await page.getByRole('button', { name: '创建人工接管工单', exact: true }).click()
  await page
    .getByRole('dialog', { name: '创建人工接管工单' })
    .getByRole('button', { name: '创建工单', exact: true })
    .click()
  const ticket = await dataOf(await ticketResponse)
  const ticketId = String(ticket.id)
  expect(ticket.status).toBe('pending')

  await page.goto('/admin/handoff')
  const pending = await openTicket(page, ticketId)
  expect(String(pending.ticket.user)).toBe(user)
  expect(pending.ticket.session_id).toBe(sessionId)
  const drawer = page.locator('.el-drawer')
  const acceptResponse = responseAt(page, '/api/v1/handoff/tickets/accept', 'POST')
  await drawer.getByRole('button', { name: '领取工单', exact: true }).click()
  expect((await dataOf(await acceptResponse)).status).toBe('assigned')
  await expect(drawer.getByRole('button', { name: '领取工单', exact: true })).toBeDisabled()

  const replyInput = drawer
    .locator('.el-form-item')
    .filter({ hasText: 'Enter 用于换行' })
    .locator('textarea')
  await replyInput.fill(text)
  const replyResponse = responseAt(page, '/api/v1/handoff/tickets/replies', 'POST')
  const detailAfterReply = responseAt(page, '/api/v1/handoff/tickets/detail', 'GET')
  await drawer.getByRole('button', { name: '发送回复', exact: true }).click()
  const accepted = await dataOf(await replyResponse)
  expect(accepted.status).toBe('accepted')
  const commandId = String(accepted.command_id)
  expect(commandId).not.toBe('undefined')
  const firstDetail = await dataOf(await detailAfterReply)
  expect(
    firstDetail.replies.find((reply: { command_id: string }) => reply.command_id === commandId)
      ?.status
  ).toBe('accepted')
  const readsBeforeRelease = detailReads
  await writeFile(join(evidence, 'release-reply.txt'), commandId, 'utf8')

  // 等待原命令由服务端投影；同一人工消息在会话端只出现一次。
  const publicMessage = chat.locator('.message.assistant').filter({ hasText: text })
  await expect(publicMessage).toHaveCount(1)
  const sourceMessageId = await publicMessage.getAttribute('data-message-id')
  expect(sourceMessageId).toBeTruthy()
  const readsAfterCommit = detailReads
  let manualRefreshes = 0
  if (mode === 'baseline') {
    await expect(drawer.locator('.el-card').filter({ hasText: text })).toHaveCount(0)
    await page.waitForTimeout(6500)
    expect(detailReads).toBe(readsAfterCommit)
    await page.screenshot({ path: join(evidence, 'admin-before-refresh.png') })
    await drawer.getByRole('button', { name: '刷新状态', exact: true }).click()
    manualRefreshes++
  } else {
    expect(mode).toBe('candidate')
    await expect(drawer.locator('.el-card').filter({ hasText: text })).toHaveCount(1, {
      timeout: 30_000
    })
    expect(detailReads).toBeGreaterThan(readsBeforeRelease)
  }
  expect(replyPosts).toBe(1)
  await expect(drawer.locator('.el-card').filter({ hasText: text })).toHaveCount(1)
  await page.screenshot({ path: join(evidence, 'admin-reply.png') })
  await chat.screenshot({ path: join(evidence, 'chat-reply.png') })

  // 两端刷新后都从数据库重新读取原事实与身份，而非重发回复或模型请求。
  await page.reload()
  await page.locator('.admin-toolbar .el-select .el-select__wrapper').first().click()
  await page.getByRole('option', { name: '已接管', exact: true }).click()
  const restored = await openTicket(page, ticketId)
  expect(
    restored.replies.filter(
      (reply: { command_id: string; status: string }) =>
        reply.command_id === commandId && reply.status === 'committed'
    )
  ).toHaveLength(1)
  await expect(drawer.locator('.el-card').filter({ hasText: text })).toHaveCount(1)
  const restoredProfileResponse = responseAt(chat, '/api/v1/chat/client-profile', 'POST')
  await chat.reload()
  expect(String((await dataOf(await restoredProfileResponse)).end_user_id)).toBe(user)
  await expect(publicMessage).toHaveCount(1)
  await expect(publicMessage).toHaveAttribute('data-message-id', sourceMessageId!)
  await expect(chat.locator('.connection-status')).toContainText('已连接')

  await drawer.getByText('结束处理并标记已解决', { exact: true }).click()
  await drawer
    .locator('.el-form-item')
    .filter({ hasText: '说明问题如何解决' })
    .locator('textarea')
    .fill(note)
  const resolveResponse = responseAt(page, '/api/v1/handoff/tickets/resolve', 'POST')
  await drawer.getByRole('button', { name: '标记已解决', exact: true }).click()
  expect((await dataOf(await resolveResponse)).status).toBe('resolved')
  await expect(drawer.getByRole('button', { name: '刷新状态', exact: true })).toBeEnabled()
  await expect(drawer.getByRole('button', { name: '标记已解决', exact: true })).toBeDisabled()
  await expect(replyInput).toBeDisabled()
  await page.screenshot({ path: join(evidence, 'admin-resolved.png') })
  await chat.screenshot({ path: join(evidence, 'chat-restored.png') })
  expect(chatSubmissions).toBe(1)
  expect(errors).toEqual([])
  await writeFile(
    join(evidence, 'browser-receipt.json'),
    JSON.stringify(
      {
        sessionId,
        ticketId,
        commandId,
        user,
        text,
        note,
        chatSubmissions,
        runtimeRunId,
        replyPosts,
        readsBeforeRelease,
        readsAfterCommit,
        detailReads,
        mode,
        manualRefreshes,
        sourceMessageId,
        restoredBothClients: true,
        errors
      },
      null,
      2
    ),
    'utf8'
  )
})
