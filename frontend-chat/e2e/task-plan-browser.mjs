import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { mkdir, writeFile } from 'node:fs/promises'
import { createServer } from 'vite'
const require = createRequire(new URL('../../frontend-admin/package.json', import.meta.url))
const { chromium, expect } = require('@playwright/test')
const evidence = new URL('../../.runtime-logs/task-plan-browser/', import.meta.url)
await mkdir(evidence, { recursive: true })
const vite = await createServer({
  root: new URL('../', import.meta.url).pathname.replace(/^\/(\w:)/, '$1'),
  server: { host: '127.0.0.1', port: 0, strictPort: false }
})
let browser, diagnosticPage
const errors = [],
  requests = [],
  controls = []
let work = {
  work_id: 'fixture-work',
  input_id: 'fixture-input',
  session_id: 'fixture-session',
  run_id: 'fixture-run',
  revision: 1,
  run_revision: 1,
  run_status: 'waiting_hitl',
  status: 'active',
  title: '月度报告',
  plan_revision: 2,
  approval_required: true,
  tasks: [
    {
      task_id: 'fixture-task',
      task_kind: 'plan',
      executor_type: 'PRIMARY',
      title: '核查数据',
      agent_name: '报告助手',
      status: 'waiting',
      wait_reason: '等待批准导出',
      details_access: 'available',
      goal: '核对本月订单',
      expected_output: '汇总报告',
      pending: {
        kind: 'operation',
        pending_id: 'fixture-pending',
        parameters_version: 3,
        prompt: '确认导出本月报告'
      }
    }
  ],
  results: []
}
const ok = (data) => ({ code: 'OK', message: 'OK', trace_id: 'fixture-trace', data })
try {
  await vite.listen()
  const origin = `http://127.0.0.1:${vite.httpServer.address().port}`
  browser = await chromium.launch({ headless: true })
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
  const page = await context.newPage()
  diagnosticPage = page
  page.setDefaultTimeout(12000)
  page.on('pageerror', (e) => errors.push(e.message))
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request(),
      path = new URL(request.url()).pathname
    requests.push({ path, method: request.method() })
    let data
    switch (path) {
      case '/api/v1/chat/agents':
        data = {
          channel_id: 'fixture-channel',
          items: [
            {
              id: 'fixture-agent',
              name: '报告助手',
              is_default: true,
              delegation_config: { enabled: false, target_agent_ids: [] }
            }
          ]
        }
        break
      case '/api/v1/chat/teams':
        data = []
        break
      case '/api/v1/chat/client-profile':
        data = {
          identity_token: 'fixture-identity',
          end_user_id: 'fixture-user',
          display_name: '验收用户',
          channel_id: 'fixture-channel'
        }
        break
      case '/api/v1/chat/sessions':
        data = [
          {
            session_id: 'fixture-session',
            summary: '计划浏览器验收',
            message_count: 1,
            agent_id: 'fixture-agent'
          }
        ]
        break
      case '/api/v1/chat/history':
        data = {
          pending_interactions: [],
          collaboration: { active: false, pending: false, selected_active: false },
          event_cursor: '1',
          messages: [
            {
              message_id: 'fixture-input',
              input_id: 'fixture-input',
              message_version: 1,
              role: 'user',
              content: '请生成月度报告'
            }
          ],
          processes: [],
          activities: [],
          work_plans: [work]
        }
        break
      case '/api/v1/chat/work':
        data = work
        break
      case '/api/v1/chat/work/history':
        data = { items: [work] }
        break
      case '/api/v1/chat/work/tasks/detail':
        data = { processes: [], results: [], pending: work.tasks[0].pending }
        break
      case '/api/v1/chat/work/controls': {
        const body = request.postDataJSON()
        controls.push(body)
        assert.equal(body.session_id, 'fixture-session')
        assert.equal(body.work_id, 'fixture-work')
        assert.ok(body.input_id)
        work = { ...work, revision: work.revision + 1 }
        if (body.operation === 'approve_plan') {
          assert.equal(body.plan_revision, 2)
          work.approved_revision = 2
          work.approval_required = false
        }
        if (body.operation === 'approve_operation') {
          assert.equal(body.pending_id, 'fixture-pending')
          assert.equal(body.parameters_version, 3)
          work.tasks = [{ ...work.tasks[0], pending: undefined, status: 'running' }]
        }
        data = work
        break
      }
      case '/api/v1/chat/events':
        await route.fulfill({ contentType: 'text/event-stream', body: ': fixture idle\n\n' })
        return
      case '/api/v1/chat/sessions/workspace':
        data = { workspaces: [] }
        break
      case '/api/v1/chat/limits':
        data = {
          attachments: {
            max_file_bytes: 52428800,
            max_files: 16,
            max_total_bytes: 209715200,
            extensions: ['txt']
          },
          references: { max_items: 64, max_file_selectors: 2048 },
          selections: { max_selectable_files: 2048 }
        }
        break
      case '/api/v1/chat/sessions/deliverables':
      case '/api/v1/chat/sessions/attachments':
        data = []
        break
      case '/api/v1/chat/commands':
        data = { items: [] }
        break
      case '/api/v1/chat/runs/collaboration':
        data = { active: false, pending: false, selected_active: false }
        break
      case '/api/v1/chat/runs/steering':
        data = { accepting: false, items: [] }
        break
      case '/api/v1/chat/goals':
        data = { goal: null, continuation_enabled: false }
        break
      default:
        errors.push(`Unhandled API ${path}`)
        await route.abort()
        return
    }
    await route.fulfill({ json: ok(data) })
  })
  await page.goto(origin)
  await page.getByRole('textbox', { name: '你的称呼' }).fill('验收用户')
  await page.getByRole('combobox', { name: '认证方式' }).click()
  await page.getByRole('option', { name: '渠道令牌', exact: true }).click()
  await page.getByLabel(/^渠道令牌/).fill('isolated-fixture-token')
  await page.getByRole('button', { name: '进入会话', exact: true }).click()
  await page.getByRole('button', { name: '打开会话导航', exact: true }).click()
  await page.getByText('计划浏览器验收', { exact: false }).click()
  const card = page.locator('.task-plan-card')
  await expect(card).toContainText('月度报告')
  await expect(card).toContainText('等待批准导出')
  await card.getByRole('button', { name: '批准第 2 版计划', exact: true }).click()
  await expect(card.getByRole('button', { name: '批准第 2 版计划', exact: true })).toHaveCount(0)
  await card.getByRole('button', { name: '补充计划要求', exact: true }).click()
  await page.getByRole('textbox', { name: '补充计划要求', exact: true }).fill('报告增加同比趋势')
  await page.getByRole('button', { name: '提交补充', exact: true }).click()
  await expect(page.getByRole('textbox', { name: '补充计划要求', exact: true })).toHaveValue('')
  await page.keyboard.press('Escape')
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await card.getByRole('button', { name: '核查数据', exact: true }).click()
  await expect(page.getByRole('dialog')).toContainText('确认导出本月报告')
  await page.getByRole('button', { name: '批准操作', exact: true }).click()
  await expect.poll(() => controls.length).toBe(3)
  await expect(page.getByRole('button', { name: '批准操作', exact: true })).toHaveCount(0)
  await page.keyboard.press('Escape')
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await page.screenshot({
    path: new URL('desktop.png', evidence).pathname.replace(/^\/(\w:)/, '$1'),
    fullPage: true
  })
  await page.reload()
  await expect(page.locator('.task-plan-card')).toContainText('月度报告')
  await expect(
    page.locator('.task-plan-card').getByRole('button', { name: '批准第 2 版计划', exact: true })
  ).toHaveCount(0)
  assert.equal(controls.length, 3)
  assert.equal(controls[1].content, '报告增加同比趋势')
  await page.setViewportSize({ width: 390, height: 844 })
  assert.ok((await card.boundingBox()).width <= 390)
  await page.screenshot({
    path: new URL('mobile-restored.png', evidence).pathname.replace(/^\/(\w:)/, '$1'),
    fullPage: true
  })
  assert.deepEqual(errors, [])
  await writeFile(
    new URL('result.json', evidence),
    JSON.stringify(
      {
        status: 'PASS',
        boundary:
          'Real Chromium + actual Vue App + context API fixture; no backend/database/provider',
        checks: [
          'history plan restored',
          'version-bound plan approval',
          'plan supplement',
          'pending-bound operation approval',
          'reload restores without repost',
          '390px card fits'
        ],
        controls,
        requests,
        errors
      },
      null,
      2
    )
  )
  console.warn('PASS: task plan browser fixture; evidence ' + evidence.pathname)
} catch (error) {
  console.warn(await diagnosticPage?.locator('body').innerText())
  await writeFile(
    new URL('failure.json', evidence),
    JSON.stringify({ error: String(error), errors, requests, controls }, null, 2)
  )
  throw error
} finally {
  if (browser) await browser.close()
  await vite.close()
}
