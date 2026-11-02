import assert from 'node:assert/strict'
import { mkdir } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')

/** 真实 renderer + 受控 IPC，验证停止操作的对象归属，不派发真实本地任务。 */
export async function verifyRendererInteractions(page, url, evidence) {
  await mkdir(evidence, { recursive: true })
  await page.addInitScript(() => {
    const ok = (value) => ({ ok: true, value })
    const session = (id) => ({ id, title: `会话 ${id}`, workspace: `D:/workspace/${id}` })
    const states = {
      A: {
        session: session('A'),
        messages: [
          { role: 'user', text: '修复登录表单', runId: 'run-active' },
          { role: 'user', text: '整理文档说明', runId: 'run-queued' }
        ],
        runs: [
          { id: 'run-active', status: 'RUNNING', phase: 'READY', active: true },
          { id: 'run-queued', status: 'QUEUED', phase: 'READY' }
        ],
        events: [],
        pendingConfirmations: []
      },
      B: { session: session('B'), messages: [], runs: [], events: [], pendingConfirmations: [] }
    }
    let listener
    let finishCancel
    window.rendererFixture = {
      completeCleanup() {
        states.A.runs.find((run) => run.id === 'run-active').active = false
        listener({ event: 'state', sessionId: 'A' })
      },
      reads: [],
      cancels: [],
      finishCancel(failed) {
        if (!failed) {
          const run = states.A.runs.find((item) => item.id === this.cancels.at(-1).runId)
          run.status = 'CANCELLED'
          run.phase = 'FINISHED'
          run.active = run.id === 'run-active'
        }
        finishCancel(failed ? { ok: false, error: { message: '会话 A 停止失败' } } : ok({}))
      }
    }
    window.desktop = {
      getStatus: async () => ok({ status: 'ready', message: '已就绪' }),
      getModel: async () => ok({ providerId: 'deepseek', model: 'test-model', hasApiKey: true }),
      listSessions: async () => ok([states.A.session, states.B.session]),
      getSession: async (id) => {
        window.rendererFixture.reads.push(id)
        return ok(structuredClone(states[id]))
      },
      cancelTask: async (command) => {
        window.rendererFixture.cancels.push(command)
        return await new Promise((resolve) => {
          finishCancel = resolve
        })
      },
      onEvent: (callback) => { listener = callback; return () => {} }
    }
  })
  await page.goto(url)
  await page.locator('.session-item').filter({ hasText: '会话 A' }).click()
  await page.getByRole('button', { name: '停止任务 1：修复登录表单', exact: true }).waitFor()
  assert.equal(await page.locator('.session-heading .run-badge').textContent(), '执行中')
  assert.deepEqual(await page.locator('.stop-button').allTextContents(), [
    '■停止任务 1',
    '■取消排队任务 2'
  ])
  assert.deepEqual(await page.locator('.message-task-name').allTextContents(), ['任务 1', '任务 2'])
  await page.screenshot({ path: path.join(evidence, 'multiple-tasks-desktop.png') })

  await page.setViewportSize({ width: 640, height: 740 })
  assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth))
  for (const action of await page.locator('.stop-button').all()) {
    const box = await action.boundingBox()
    assert.ok(box && box.x >= 0 && box.x + box.width <= 640)
  }
  await page.screenshot({ path: path.join(evidence, 'multiple-tasks-narrow.png') })
  await page.setViewportSize({ width: 1380, height: 820 })

  await page.getByRole('button', { name: '取消排队任务 2：整理文档说明', exact: true }).click()
  await page.waitForFunction(() => window.rendererFixture.cancels.length === 1)
  assert.equal(await page.evaluate(() => window.rendererFixture.cancels[0].runId), 'run-queued')
  await page.evaluate(() => window.rendererFixture.finishCancel(false))
  await page.waitForFunction(() => document.querySelectorAll('.stop-button').length === 1)
  assert.equal(await page.locator('.session-heading .run-badge').textContent(), '执行中')

  await page.getByRole('button', { name: '停止任务 1：修复登录表单', exact: true }).click()
  await page.locator('.session-item').filter({ hasText: '会话 B' }).click()
  await page.getByText('从一个具体任务开始', { exact: true }).waitFor()
  await page.evaluate(async () => {
    window.rendererFixture.finishCancel(true)
    await new Promise(requestAnimationFrame)
    await new Promise(requestAnimationFrame)
  })
  assert.equal(await page.getByText('会话 A 停止失败', { exact: true }).count(), 0)
  assert.equal(await page.locator('.breadcrumb strong').textContent(), '会话 B')
  await page.screenshot({ path: path.join(evidence, 'late-cancel-failure-isolated.png') })

  await page.locator('.session-item').filter({ hasText: '会话 A' }).click()
  await page.getByRole('button', { name: '停止任务 1：修复登录表单', exact: true }).click()
  await page.locator('.session-item').filter({ hasText: '会话 B' }).click()
  await page.getByText('从一个具体任务开始', { exact: true }).waitFor()
  const readCount = await page.evaluate(() => window.rendererFixture.reads.length)
  await page.evaluate(() => window.rendererFixture.finishCancel(false))
  await page.waitForFunction((count) => window.rendererFixture.reads.length > count, readCount)
  assert.equal(await page.evaluate(() => window.rendererFixture.reads.at(-1)), 'A')
  assert.equal(await page.locator('.breadcrumb strong').textContent(), '会话 B')
  assert.equal(await page.locator('.stop-button').count(), 0)
  await page.locator('.session-item').filter({ hasText: '会话 A' }).click()
  await page.getByText('已取消 · 正在清理本地任务', { exact: true }).waitFor()
  assert.equal(await page.locator('.stop-button').count(), 0)
  await page.locator('.working-indicator').filter({ hasText: '正在清理本地任务' }).waitFor()
  const cancelCount = await page.evaluate(() => window.rendererFixture.cancels.length)
  await page.evaluate(() => window.rendererFixture.completeCleanup())
  await page.waitForFunction(() => !document.querySelector('.working-indicator'))
  assert.equal(await page.locator('.session-heading .run-badge').textContent(), '已取消')
  assert.equal(await page.evaluate(() => window.rendererFixture.cancels.length), cancelCount)
}

if (process.argv[1] && pathToFileURL(path.resolve(process.argv[1])).href === import.meta.url) {
  const [{ createServer }, { chromium }] = await Promise.all([import('vite'), import('playwright')])
  const server = await createServer({ root, server: { host: '127.0.0.1', port: 0 } })
  let browser
  try {
    await server.listen()
    browser = await chromium.launch()
    const page = await browser.newPage({ viewport: { width: 1380, height: 820 } })
    await verifyRendererInteractions(
      page,
      server.resolvedUrls.local[0],
      path.join(root, 'test-results', 'renderer-interactions')
    )
    process.stdout.write('renderer interactions passed\n')
  } finally {
    await browser?.close()
    await server.close()
  }
}
