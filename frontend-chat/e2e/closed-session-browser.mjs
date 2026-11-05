/* global process */
import assert from 'node:assert/strict'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'
const f = JSON.parse(process.env.CLOSED_FIXTURE)
const { chromium, expect } = createRequire(
  new URL('../../frontend-admin/package.json', import.meta.url)
)('@playwright/test')
const vite = await createServer({
  root: fileURLToPath(new URL('../', import.meta.url)),
  server: {
    host: '127.0.0.1',
    port: 0,
    proxy: {
      '/api': {
        target: f.backend,
        changeOrigin: true,
        configure(server) {
          server.on('proxyReq', (request) => request.setHeader('Origin', 'http://localhost:3200'))
        }
      }
    }
  }
})
let browser, context, page
const errors = [],
  chats = [],
  snapshots = [],
  detailReads = []
try {
  await vite.listen()
  const origin = `http://127.0.0.1:${vite.httpServer.address().port}`
  browser = await chromium.launch({ headless: true })
  context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
  await context.addInitScript(
    (input) => {
      localStorage.setItem(
        `skillforge.chat.profile:${input.origin}:${input.channel}`,
        JSON.stringify({ client_ref: input.clientRef, identity_token: input.identity })
      )
      sessionStorage.setItem(
        'skillforge.chat.connection',
        JSON.stringify({
          address: input.origin,
          mode: 'token',
          token: input.token,
          name: '上传验收'
        })
      )
      sessionStorage.setItem(
        'skillforge.chat.selection',
        JSON.stringify({
          clientRef: input.clientRef,
          target: `agent:${input.agent}`,
          session: input.session
        })
      )
    },
    { ...f, origin }
  )
  page = await context.newPage()
  page.on('pageerror', (error) => errors.push(error.message))
  page.on('request', (request) => {
    const path = new URL(request.url()).pathname
    if (path === '/api/v1/chat' && request.method() === 'POST') chats.push(path)
    if (path === '/api/v1/chat/sessions/detail') detailReads.push(path)
  })
  page.on('response', async (response) => {
    if (new URL(response.url()).pathname === '/api/v1/chat/history' && response.ok())
      snapshots.push(await response.json())
  })
  await context.tracing.start({ screenshots: true, snapshots: true, sources: true })
  const initial = page.waitForResponse(
    (response) => new URL(response.url()).pathname === '/api/v1/chat/history'
  )
  await page.goto(origin)
  await initial

  const input = page.getByRole('textbox', { name: '消息', exact: true })
  await expect(input).toBeEnabled()
  if (f.cursorRecovery) {
    await input.fill('真实运行游标恢复失败后自动观察验收')
    const accepted = page.waitForResponse(
      (response) =>
        new URL(response.url()).pathname === '/api/v1/chat' &&
        response.request().method() === 'POST'
    )
    await page.getByRole('button', { name: '发送消息', exact: true }).click()
    const receipt = (await (await accepted).json()).data
    await expect(page.getByRole('button', { name: '停止运行', exact: true })).toBeVisible()
    let expired = false,
      failedHistory = false,
      eventRequests = 0,
      recoveryHistoryRequests = 0
    await page.route('**/api/v1/chat/events?**', async (route) => {
      eventRequests++
      if (!expired) {
        expired = true
        return route.fulfill({
          status: 409,
          contentType: 'application/json',
          body: JSON.stringify({
            code: 'EVENT_CURSOR_EXPIRED',
            message: '隔离游标已过期',
            data: null,
            trace_id: 'cursor-recovery-control'
          })
        })
      }
      return route.continue()
    })
    await page.route('**/api/v1/chat/history?**', async (route) => {
      if (expired) {
        recoveryHistoryRequests++
        if (!failedHistory) {
          failedHistory = true
          return route.fulfill({
            status: 503,
            contentType: 'application/json',
            body: JSON.stringify({
              code: 'SERVICE_UNAVAILABLE',
              message: '隔离历史读取暂不可用',
              data: null,
              trace_id: 'cursor-recovery-control'
            })
          })
        }
      }
      return route.continue()
    })
    const reconnect = async () => {
      await page.getByRole('button', { name: '更多会话操作', exact: true }).click()
      await page.getByRole('button', { name: '重新连接', exact: true }).click()
    }
    await reconnect()
    await expect.poll(() => failedHistory).toBe(true)
    await writeFile(join(f.evidence, 'release-model'), 'release')
    let terminalRun
    const runStatusReads = []
    await expect
      .poll(
        async () => {
          const response = await page.request.get(
            origin +
              `/api/v1/chat/runs/status?session_id=${encodeURIComponent(f.session)}&run_id=${encodeURIComponent(receipt.execution_id)}`,
            { headers: { 'X-Channel-Token': f.token, 'X-Chat-User-Token': f.identity } }
          )
          assert.equal(response.status(), 200)
          terminalRun = await response.json()
          runStatusReads.push({ at: Date.now(), body: terminalRun })
          return String(terminalRun.data.status).toLowerCase()
        },
        { timeout: 20000 }
      )
      .toBe('completed')
    await writeFile(
      join(f.evidence, 'cursor-recovery-terminal-run.json'),
      JSON.stringify({ terminalRun, runStatusReads }, null, 2)
    )
    const final = page.getByText(/STREAM_FINISHED/).first()
    let automaticallyRecovered = true
    try {
      await expect(final).toBeVisible({ timeout: 8000 })
    } catch {
      automaticallyRecovered = false
    }
    const beforeManual = {
      eventRequests,
      recoveryHistoryRequests,
      automaticallyRecovered,
      visible: await page.locator('body').innerText()
    }
    await page.screenshot({
      path: join(f.evidence, 'cursor-recovery-before-manual.png'),
      fullPage: true
    })
    if (f.cursorRecovery === 'control') {
      assert.equal(automaticallyRecovered, false)
      assert.equal(eventRequests, 1)
      await reconnect()
      await expect(final).toBeVisible({ timeout: 60000 })
    } else {
      assert.equal(automaticallyRecovered, true)
      assert.ok(eventRequests >= 2)
      assert.ok(recoveryHistoryRequests >= 2)
    }
    assert.equal(chats.length, 1)
    await writeFile(
      join(f.evidence, 'receipt.json'),
      JSON.stringify(
        {
          runId: receipt.execution_id,
          sessionId: f.session,
          beforeManual,
          eventRequests,
          recoveryHistoryRequests,
          terminalRun,
          runStatusReads,
          submissions: chats.length,
          errors
        },
        null,
        2
      )
    )
    process.stdout.write('PASS: real runtime cursor recovery and observation outcome recorded\n')
  } else if (f.unknownSteering) {
    await input.fill('运行中补充未知回执导航核验')
    const accepted = page.waitForResponse(
      (response) =>
        new URL(response.url()).pathname === '/api/v1/chat' &&
        response.request().method() === 'POST'
    )
    await page.getByRole('button', { name: '发送消息', exact: true }).click()
    const receipt = (await (await accepted).json()).data
    await expect(page.getByRole('button', { name: '补充本轮', exact: true })).toBeVisible()
    await page.getByRole('button', { name: '补充本轮', exact: true }).click()
    await page
      .getByRole('textbox', { name: '消息', exact: true })
      .fill('SUPPLEMENT_UNKNOWN_ORIGINAL')
    let originalCommand, acceptedCommand, rejectedRetry
    let steeringPosts = 0
    let blockedSteeringReads = 0
    let heldRead = true
    let complete
    const acceptedSteer = new Promise((resolve) => {
      complete = resolve
    })
    await page.route('**/api/v1/chat/runs/steer**', async (route) => {
      if (route.request().method() === 'GET') {
        if (heldRead) {
          blockedSteeringReads++
          return route.abort()
        }
        return route.continue()
      }
      steeringPosts++
      const command = route.request().postDataJSON()
      if (originalCommand) assert.deepEqual(command, originalCommand)
      originalCommand = command
      if (f.steeringRejected && steeringPosts === 1) {
        complete()
        await route.abort()
        return
      }
      const response = await route.fetch()
      if (f.steeringRejected) {
        rejectedRetry = { status: response.status(), body: await response.json() }
        await route.fulfill({ response })
        return
      }
      assert.equal(response.status(), 200)
      acceptedCommand = await response.json()
      complete()
      await route.abort()
    })
    await page.getByRole('button', { name: '发送本轮补充', exact: true }).click()
    await acceptedSteer
    await expect(page.getByRole('button', { name: '重试原补充', exact: true })).toBeVisible()
    await expect.poll(() => blockedSteeringReads).toBeGreaterThan(0)
    const unknownVisible = await page.locator('body').innerText()
    await writeFile(
      join(f.evidence, 'steering-accepted.json'),
      JSON.stringify(
        { runId: receipt.execution_id, originalCommand, acceptedCommand, unknownVisible },
        null,
        2
      )
    )
    const newConversation = page.getByRole('button', { name: '新建会话', exact: true })
    const navigationEnabled = await newConversation.isEnabled()
    await page.screenshot({
      path: join(f.evidence, 'unknown-steering-before-navigation.png'),
      fullPage: true
    })
    let discardPrompt, afterNavigation, afterReturn
    let recoveredSteering
    if (f.steeringRejected) {
      await expect(newConversation).toBeDisabled()
      heldRead = false
      const observed = page.waitForResponse(
        (response) =>
          new URL(response.url()).pathname === '/api/v1/chat/runs/steering' &&
          response.status() === 200
      )
      await page.getByRole('button', { name: '查询状态', exact: true }).click()
      recoveredSteering = await (await observed).json()
      assert.equal(recoveredSteering.data.items.length, 0)
      await expect(page.getByRole('button', { name: '重试原补充', exact: true })).toBeVisible()
      if (f.rejectedCandidate) heldRead = true
      await writeFile(join(f.evidence, 'release-model'), 'release')
      await expect(page.getByText(/STREAM_FINISHED/).first()).toBeVisible({ timeout: 60000 })
      if (f.rejectedCandidate) {
        heldRead = false
        const terminalRead = page.waitForResponse(
          (response) =>
            new URL(response.url()).pathname === '/api/v1/chat/runs/steering' &&
            response.status() === 200
        )
        await page.getByRole('button', { name: '查询状态', exact: true }).click()
        const terminal = (await (await terminalRead).json()).data
        assert.equal(terminal.accepting, false)
        assert.equal(terminal.items.length, 0)
        await expect(page.getByRole('button', { name: '重试原补充', exact: true })).toHaveCount(0)
        await expect(page.getByRole('textbox', { name: '消息', exact: true })).toHaveValue(
          originalCommand.text
        )
        await expect(page.getByText(/未受理/).first()).toBeVisible()
        await expect(newConversation).toBeEnabled()
        await page.getByRole('button', { name: '打开会话导航', exact: true }).click()
        for (const button of await page
          .getByRole('navigation', { name: '历史会话', exact: true })
          .getByRole('button')
          .all())
          await expect(button).toBeEnabled()
        await page.getByRole('button', { name: '收起会话导航', exact: true }).click()
      } else {
        const retried = page.waitForResponse(
          (response) =>
            new URL(response.url()).pathname === '/api/v1/chat/runs/steer' &&
            response.request().method() === 'POST'
        )
        await page.getByRole('button', { name: '重试原补充', exact: true }).click()
        assert.ok((await retried).status() >= 400)
        await expect(page.getByRole('button', { name: '重试原补充', exact: true })).toBeVisible()
        await expect(newConversation).toBeDisabled()
      }
      afterReturn = await page.locator('body').innerText()
      await page.screenshot({
        path: join(f.evidence, 'unaccepted-steering-terminal-lock.png'),
        fullPage: true
      })
    } else if (f.steeringCandidate) {
      await expect(newConversation).toBeDisabled()
      await page.getByRole('button', { name: '打开会话导航', exact: true }).click()
      const history = page.getByRole('navigation', { name: '历史会话', exact: true })
      await expect(history.getByRole('button')).not.toHaveCount(0)
      for (const button of await history.getByRole('button').all())
        await expect(button).toBeDisabled()
      await page.getByRole('button', { name: '收起会话导航', exact: true }).click()
      const observed = page.waitForResponse(
        (response) =>
          new URL(response.url()).pathname === '/api/v1/chat/runs/steering' &&
          response.status() === 200
      )
      heldRead = false
      if (!f.automaticSteeringRead)
        await page.getByRole('button', { name: '查询状态', exact: true }).click()
      recoveredSteering = await (await observed).json()
      assert.equal(recoveredSteering.data.items.length, 1)
      assert.equal(recoveredSteering.data.items[0].command_id, originalCommand.command_id)
      await expect(page.getByRole('button', { name: '重试原补充', exact: true })).toHaveCount(0)
      await expect(newConversation).toBeEnabled()
      await page.getByRole('button', { name: '打开会话导航', exact: true }).click()
      for (const button of await history.getByRole('button').all())
        await expect(button).toBeEnabled()
      await page.screenshot({
        path: join(f.evidence, 'steering-read-recovered-navigation.png'),
        fullPage: true
      })
      await page.getByRole('button', { name: '收起会话导航', exact: true }).click()
    } else if (navigationEnabled) {
      await newConversation.click()
      const confirmation = page.getByRole('dialog', { name: '确认离开', exact: true })
      await expect(confirmation).toBeVisible()
      discardPrompt = await confirmation.innerText()
      await confirmation.getByRole('button', { name: '放弃修改', exact: true }).click()
      afterNavigation = await page.locator('body').innerText()
      await page.getByRole('button', { name: '打开会话导航', exact: true }).click()
      const restored = page.waitForResponse(
        (response) => new URL(response.url()).pathname === '/api/v1/chat/history'
      )
      await page
        .getByRole('navigation', { name: '历史会话', exact: true })
        .getByRole('button')
        .first()
        .click()
      await restored
      afterReturn = await page.locator('body').innerText()
      await expect(page.getByRole('button', { name: '重试原补充', exact: true })).toHaveCount(0)
    }
    heldRead = false
    await writeFile(join(f.evidence, 'release-model'), 'release')
    await expect(page.getByText(/STREAM_FINISHED/).first()).toBeVisible({ timeout: 60000 })
    const status = await page.request.get(
      origin +
        `/api/v1/chat/runs/steering?session_id=${encodeURIComponent(f.session)}&run_id=${encodeURIComponent(receipt.execution_id)}`,
      { headers: { 'X-Channel-Token': f.token, 'X-Chat-User-Token': f.identity } }
    )
    assert.equal(status.status(), 200)
    const finalSteering = await status.json()
    assert.equal(finalSteering.data.items.length, f.steeringRejected ? 0 : 1)
    if (!f.steeringRejected)
      assert.equal(finalSteering.data.items[0].command_id, originalCommand.command_id)
    assert.equal(chats.length, 1)
    assert.equal(steeringPosts, f.steeringRejected && !f.rejectedCandidate ? 2 : 1)
    await writeFile(
      join(f.evidence, 'receipt.json'),
      JSON.stringify(
        {
          runId: receipt.execution_id,
          sessionId: f.session,
          originalCommand,
          acceptedCommand,
          rejectedRetry,
          recoveredSteering,
          finalSteering,
          unknownVisible,
          navigationEnabled,
          discardPrompt,
          afterNavigation,
          afterReturn,
          submissions: chats.length,
          steeringPosts,
          blockedSteeringReads,
          candidate: f.steeringCandidate,
          automaticSteeringRead: f.automaticSteeringRead
        },
        null,
        2
      )
    )
    process.stdout.write(
      'PASS: real accepted supplement with lost response inspected through navigation\n'
    )
  } else {
    await input.fill('关闭前的真实运行验收')
    const accepted = page.waitForResponse(
      (response) =>
        new URL(response.url()).pathname === '/api/v1/chat' &&
        response.request().method() === 'POST'
    )
    await page.getByRole('button', { name: '发送消息', exact: true }).click()
    const result = await accepted
    assert.equal(result.status(), 202)
    const receipt = (await result.json()).data
    await expect(page.getByText(f.reply, { exact: true }).first()).toBeVisible()
    await expect(page.getByRole('button', { name: '停止运行', exact: true })).toHaveCount(0)
    const closeResponse = await page.request.post(origin + '/api/v1/chat/sessions/close', {
      headers: { Authorization: `Bearer ${f.adminToken}` },
      data: { session_id: f.session, payload: { reason: '真实关闭会话验收' } }
    })
    assert.equal(closeResponse.status(), 200)
    const closed = await closeResponse.json()
    assert.equal(closed.data.status, 'closed')
    const historyReady = page.waitForResponse(
      (response) => new URL(response.url()).pathname === '/api/v1/chat/history'
    )
    await page.reload()
    assert.equal((await historyReady).status(), 200)
    await expect(page.getByText(f.reply, { exact: true }).first()).toBeVisible()
    const beforeAttempt = chats.length
    let rejected = null
    if (f.baseline) {
      await expect(input).toBeEnabled()
      await input.fill('已关闭会话不应允许编辑发送')
      const denied = page.waitForResponse(
        (response) =>
          new URL(response.url()).pathname === '/api/v1/chat' &&
          response.request().method() === 'POST'
      )
      await page.getByRole('button', { name: '发送消息', exact: true }).click()
      const response = await denied
      assert.ok(response.status() >= 400)
      rejected = { status: response.status(), body: await response.json() }
      assert.equal(chats.length - beforeAttempt, 1)
    } else {
      assert.equal(snapshots.at(-1).data.closed, true)
      assert.deepEqual(detailReads, [])
      await expect(input).toBeDisabled()
      await expect(page.getByLabel('选择文件')).toBeDisabled()
      await expect(page.getByRole('button', { name: '添加文件或图片', exact: true })).toBeDisabled()
      await expect(page.getByText(/会话已关闭/).first()).toBeVisible()
      assert.equal(chats.length - beforeAttempt, 0)
    }
    await page.screenshot({ path: join(f.evidence, 'closed-restored.png'), fullPage: true })
    if (f.baseline) await input.fill('')
    await page.getByRole('button', { name: '新建会话', exact: true }).click()
    await expect(input).toBeEnabled()
    await expect(page.getByText(f.reply, { exact: true })).toHaveCount(0)
    assert.deepEqual(errors, [])
    await writeFile(
      join(f.evidence, 'receipt.json'),
      JSON.stringify(
        {
          runId: receipt.execution_id,
          sessionId: f.session,
          snapshots,
          rejected,
          submissions: chats.length,
          detailReads,
          baseline: f.baseline
        },
        null,
        2
      )
    )
    process.stdout.write(
      'PASS: completed runtime, real close, restored closed session, and fresh composer\n'
    )
  }
} finally {
  if (page) await page.screenshot({ path: join(f.evidence, 'final-state.png'), fullPage: true })
  if (context) await context.tracing.stop({ path: join(f.evidence, 'browser-trace.zip') })
  if (browser) await browser.close()
  await vite.close()
}
