/* global process */
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { mkdir, writeFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'

const require = createRequire(new URL('../../frontend-admin/package.json', import.meta.url))
const { chromium, expect } = require('@playwright/test')
const roleSearchOnly = process.env.CHAT_ROLE_SEARCH_ONLY === '1'
const evidence = new URL(roleSearchOnly ? '../test-results/role-search-browser/' : '../test-results/capability-browser/', import.meta.url)
const commandScrollOnly = process.env.CHAT_COMMAND_SCROLL_ONLY === '1'
const root = fileURLToPath(new URL('../', import.meta.url))
const vite = await createServer({
  root,
  server: { host: '127.0.0.1', port: roleSearchOnly ? 0 : 23444, strictPort: true }
})
const errors = [],
  requests = [],
  writes = [],
  snapshots = []
let browser, currentPage
const ok = (data) => ({ code: 'OK', message: 'OK', trace_id: 'mock-browser', data })
const team = {
  id: 'team-1',
  name: '报告团队',
  status: 'active',
  revision: 3,
  description: '分工完成报告',
  primary_agent_id: 'fixture-agent',
  members: [{ agent_id: 'fixture-agent' }]
}
const pending = {
  execution_id: 'fixture-run',
  run_revision: '9007199254740993',
  interaction_type: 'tool_input',
  tool_wait: { action_id: 'fixture-action', generation: '7' },
  card: {
    type: 'tool_input',
    title: '核对报告参数',
    requests: [
      {
        id: 'request',
        message: '请核对参数后确认',
        requested_schema: {
          type: 'object',
          required: ['count', 'confirmed', 'scope'],
          properties: {
            count: { type: 'integer', title: '数量', default: 0, minimum: 0 },
            confirmed: { type: 'boolean', title: '已确认', default: false },
            scope: {
              type: 'string',
              title: '范围',
              enum: ['public', 'internal'],
              enumNames: ['公开', '内部'],
              default: 'internal'
            },
            password: { type: 'string', title: '访问密码', format: 'password' },
            details: { type: 'string', title: '详细说明', maxLength: 500 },
            optional: { type: 'boolean', title: '可选项' }
          }
        }
      }
    ]
  }
}
const deviceFixture = () => ({
  selected_browser_id: 'browser-1',
  installation_url: '',
  items: [
    {
      device_id: 'device-1',
      browser_id: 'browser-1',
      label: '办公浏览器',
      connected: true,
      expires_at: '2027-01-01T00:00:00Z'
    }
  ]
})
try {
  await mkdir(evidence, { recursive: true })
  await vite.listen()
  const origin = `http://127.0.0.1:${vite.httpServer.address().port}`
  console.warn(`SERVER pid=${process.pid} port=${vite.httpServer.address().port}`)
  browser = await chromium.launch({ headless: true })
  for (const scenario of [
    { name: 'desktop-light', theme: 'light', viewport: { width: 1440, height: 900 } },
    { name: 'desktop-dark', theme: 'dark', viewport: { width: 1280, height: 800 } },
    { name: 'narrow-light', theme: 'light', viewport: { width: 390, height: 844 } },
    { name: 'narrow-dark', theme: 'dark', viewport: { width: 390, height: 844 } }
  ]) {
    let goal = null,
      devices = deviceFixture()
    const context = await browser.newContext({ viewport: scenario.viewport })
    await context.addInitScript(
      (theme) => localStorage.setItem('skillforge-chat:theme', theme),
      scenario.theme
    )
    const page = await context.newPage()
    currentPage = page
    page.setDefaultTimeout(10000)
    page.on('pageerror', (error) => errors.push(`${scenario.name}: ${error.message}`))
    await page.route('**/api/v1/**', async (route) => {
      try {
        const request = route.request(),
          path = new URL(request.url()).pathname
        requests.push({ scenario: scenario.name, path, method: request.method() })
        if (request.method() !== 'GET')
          writes.push({ scenario: scenario.name, path, body: request.postDataJSON() })
        let data
        switch (path) {
          case '/api/v1/chat/agents':
            data = {
              channel_id: 'fixture-channel',
              items: [
                {
                  id: 'fixture-agent',
                  name: '报告助手',
                  role_description: '负责报告整理',
                  is_default: true,
                  delegation_config: { enabled: false, target_agent_ids: [] }
                },
                ...(roleSearchOnly ? [{ id: 'order-agent', name: '订单助手', role_description: '负责订单查询', is_default: false }] : [])
              ]
            }
            break
          case '/api/v1/chat/teams':
            data = [team]
            break
          case '/api/v1/chat/client-profile':
            data = {
              identity_token: 'mock-identity',
              end_user_id: 'fixture-user',
              display_name: '浏览器验收',
              channel_id: 'fixture-channel'
            }
            break
          case '/api/v1/chat/sessions':
            data = [
              {
                session_id: 'fixture-session',
                summary: '控件浏览器验收',
                message_count: 1,
                agent_id: 'fixture-agent'
              }
            ]
            break
          case '/api/v1/chat/history':
            data = {
              session_id: 'fixture-session',
              event_cursor: '1',
              pending_interactions: [pending],
              collaboration: { active: false, pending: false, selected_active: false },
              messages: [
                {
                  message_id: 'fixture-input',
                  input_id: 'fixture-input',
                  message_version: 1,
                  role: 'user',
                  content: '请核对本月报告参数',
                  created_at: '2026-10-24T01:00:00Z'
                }
              ],
              processes: [
                {
                  process_id: 'public-note',
                  type: 'commentary',
                  presentation: { title: '工作摘要', summary: '已完成报告数据核对。' }
                },
                {
                  process_id: 'public-model',
                  type: 'reasoning',
                  presentation: { title: '公开正文', summary: '报告保留三项待确认事项。' }
                },
                {
                  process_id: 'empty-model',
                  type: 'reasoning',
                  presentation: { title: '模型调用', summary: '' }
                }
              ].map((item, sequence) => ({
                ...item,
                run_id: 'fixture-run',
                root_run_id: 'fixture-run',
                root_input_id: 'fixture-input',
                sequence: sequence + 1,
                ordinal: sequence,
                updated_sequence: sequence + 1,
                status: 'succeeded',
                stage: { id: `stage-${sequence}`, index: sequence + 1 }
              })),
              activities: [],
              work_plans: []
            }
            break
          case '/api/v1/chat/runs/status':
            data = { status: 'waiting_hitl' }
            break
          case '/api/v1/chat/events':
            await route.fulfill({ contentType: 'text/event-stream', body: ': mock idle\n\n' })
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
            data = {
              items: commandScrollOnly
                ? Array.from({ length: 8 }, (_, i) => ({
                    command: `/command${i}`,
                    title: `命令 ${i}`,
                    description: '带有足够长度说明的命令'
                  }))
                : []
            }
            break
          case '/api/v1/chat/runs/collaboration':
            data = { active: false, pending: false, selected_active: false }
            break
          case '/api/v1/chat/runs/steering':
            data = { accepting: false, items: [] }
            break
          case '/api/v1/chat/goals':
            data = { goal, continuation_enabled: false }
            break
          case '/api/v1/chat/goals/create': {
            const body = request.postDataJSON()
            assert.equal(body.session_id, 'fixture-session')
            assert.equal(body.max_rounds, 256)
            goal = {
              id: 'goal-1',
              objective: body.objective,
              max_rounds: body.max_rounds,
              rounds_started: 0,
              state: 'ACTIVE',
              revision: 1
            }
            data = { goal }
            break
          }
          case '/api/v1/chat/goals/pause':
          case '/api/v1/chat/goals/resume': {
            const body = request.postDataJSON()
            assert.equal(body.expected_revision, goal.revision)
            goal = {
              ...goal,
              state: path.endsWith('pause') ? 'PAUSED' : 'ACTIVE',
              revision: goal.revision + 1
            }
            data = { goal }
            break
          }
          case '/api/v1/chat/browser/devices':
            data = devices
            break
          case '/api/v1/chat/browser/tasks':
            data = []
            break
          case '/api/v1/chat/browser/rename': {
            const body = request.postDataJSON()
            assert.equal(body.device_id, 'device-1')
            assert.ok(body.client_ref)
            devices = { ...devices, items: [{ ...devices.items[0], label: body.label }] }
            data = devices
            break
          }
          case '/api/v1/chat/browser/pair':
            data = { pairing_url: 'wss://browser.example.invalid/pair#mock-only' }
            break
          case '/api/v1/chat': {
            const body = request.postDataJSON()
            const hitl = body.hitl_response
            assert.equal(hitl.execution_id, pending.execution_id)
            assert.equal(hitl.run_revision, pending.run_revision)
            assert.deepEqual(hitl.tool_input.wait, pending.tool_wait)
            const response = JSON.parse(hitl.tool_input.responses.request.result.canonical_json)
            assert.deepEqual(response, {
              action: 'accept',
              content: {
                count: 0,
                confirmed: false,
                scope: 'public',
                password: 'mock-password',
                details: '先核对再生成'
              }
            })
            await route.fulfill({
              json: ok({ execution_id: 'fixture-run', status: 'waiting_hitl' })
            })
            return
          }
          default:
            errors.push(`Unhandled API ${request.method()} ${path}`)
            await route.abort()
            return
        }
        await route.fulfill({ json: ok(data) })
      } catch (cause) {
        errors.push(String(cause))
        await route.fulfill({
          status: 500,
          json: { code: 'MOCK_ASSERTION_FAILED', message: String(cause) }
        })
      }
    })
    await page.goto(origin)
    await expect(page.getByRole('heading', { name: '灵知' })).toBeVisible()
    await page.getByRole('textbox', { name: '你的称呼' }).fill('浏览器验收')
    await page.getByRole('combobox', { name: '认证方式' }).click()
    await page.getByRole('option', { name: '渠道令牌', exact: true }).click()
    await page.getByLabel(/^渠道令牌/).fill('mock-channel-token')
    await page.getByRole('button', { name: '进入会话', exact: true }).click()
    if (roleSearchOnly) {
      await page.getByRole('button', { name: '打开会话导航', exact: true }).click()
      await page.locator('.target-picker button[role="combobox"]').click()
      const search = page.getByRole('combobox', { name: '搜索选项' })
      await search.fill('订单查询')
      await expect(page.getByRole('option', { name: /订单助手/ })).toBeVisible()
      await expect(page.getByRole('option', { name: /报告助手/ })).toBeHidden()
      await search.press('Enter')
      await expect(page.getByRole('heading', { name: '订单助手' })).toBeVisible()
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false)
      snapshots.push(scenario)
      await context.close()
      continue
    }
    if (commandScrollOnly) {
      const input = page.getByRole('textbox', { name: '消息', exact: true })
      await input.fill('/')
      const menu = page.locator('.command-menu')
      await expect(menu).toBeVisible()
      for (let i = 0; i < 7; i++) await input.press('ArrowDown')
      await expect(input).toHaveAttribute('aria-activedescendant', 'command-option-7')
      const visible = await page.locator('#command-option-7').evaluate((option) => {
        const item = option.getBoundingClientRect(),
          list = option.parentElement.getBoundingClientRect()
        return item.top >= list.top && item.bottom <= list.bottom
      })
      assert.equal(visible, true, `${scenario.name}: 选中命令应位于滚动区域内`)
      await expect(input).toBeFocused()
      await context.close()
      snapshots.push(scenario)
      continue
    }
    await page.getByRole('button', { name: '打开会话导航', exact: true }).click()
    await page.getByText('控件浏览器验收', { exact: false }).click()
    await expect(page.locator('.pending-card')).toContainText('请核对参数后确认')
    await expect(page.locator('html')).toHaveAttribute('data-theme', scenario.theme)
    const processToggle = page.locator('.process-toggle').first()
    if ((await processToggle.getAttribute('aria-expanded')) !== 'true') await processToggle.click()
    await expect(page.locator('[data-process-id="public-note"]')).toContainText(
      '已完成报告数据核对。'
    )
    await expect(page.locator('[data-process-id="public-model"]')).toContainText(
      '报告保留三项待确认事项。'
    )
    await expect(page.locator('[data-process-id="empty-model"]')).toHaveCount(0)
    await expect(page.locator('.stage-toggle')).toHaveCount(0)
    await page.screenshot({
      path: fileURLToPath(new URL(`${scenario.name}-public-process.png`, evidence)),
      fullPage: true
    })
    await page.locator('[data-process-trace-toggle]').click()
    await expect(page.locator('[data-process-id="empty-model"]')).toBeVisible()
    await page.locator('[data-process-trace-toggle]').click()
    await expect(page.locator('[data-process-id="empty-model"]')).toHaveCount(0)
    await processToggle.click()
    const pendingCard = page.locator('.pending-card')
    await expect(pendingCard.getByRole('spinbutton', { name: '数量', exact: true })).toHaveValue(
      '0'
    )
    await expect(pendingCard.getByRole('combobox', { name: '已确认', exact: true })).toContainText(
      '否'
    )
    await expect(pendingCard.getByRole('combobox', { name: '范围', exact: true })).toContainText(
      '内部'
    )
    await pendingCard.getByRole('combobox', { name: '范围', exact: true }).click()
    await page.getByRole('option', { name: '公开', exact: true }).click()
    await expect(pendingCard.getByLabel('访问密码', { exact: true })).toHaveAttribute(
      'type',
      'password'
    )
    await pendingCard.getByLabel('访问密码', { exact: true }).fill('mock-password')
    await pendingCard.getByLabel('详细说明', { exact: true }).fill('先核对再生成')
    await expect(pendingCard.getByLabel('详细说明', { exact: true })).toHaveAttribute('rows', '3')
    await pendingCard.getByRole('spinbutton', { name: '数量', exact: true }).fill('-1')
    await pendingCard.getByRole('button', { name: '提交信息', exact: true }).click()
    await expect(pendingCard).toContainText('数量超出允许范围')
    await expect(pendingCard.getByRole('spinbutton', { name: '数量', exact: true })).toBeFocused()
    const invalidBox = await pendingCard
      .getByRole('spinbutton', { name: '数量', exact: true })
      .boundingBox()
    assert.ok(
      invalidBox.y > 64 && invalidBox.y + invalidBox.height < scenario.viewport.height - 80,
      '错误字段焦点完整可见'
    )
    await pendingCard.getByRole('spinbutton', { name: '数量', exact: true }).fill('0')
    await page.screenshot({
      path: fileURLToPath(new URL(`${scenario.name}-pending.png`, evidence)),
      fullPage: true
    })
    await pendingCard.getByRole('button', { name: '提交信息', exact: true }).click()
    await expect
      .poll(
        () =>
          writes.filter((item) => item.scenario === scenario.name && item.path === '/api/v1/chat')
            .length
      )
      .toBe(1)
    await page.getByRole('button', { name: '更多会话操作', exact: true }).click()
    await page.getByRole('button', { name: '会话工具', exact: true }).click()
    let dialog = page.getByRole('dialog', { name: '会话工具', exact: true })
    await expect(dialog.locator('.new-goal')).toHaveAttribute('open', '')
    await dialog.getByRole('textbox', { name: '持续目标', exact: true }).fill('完成月度报告')
    await dialog.getByRole('button', { name: '创建目标', exact: true }).click()
    await expect(dialog).toContainText('完成月度报告')
    await expect(dialog.locator('.new-goal')).not.toHaveAttribute('open', '')
    await expect(dialog.getByRole('textbox', { name: '持续目标', exact: true })).toBeHidden()
    await dialog.getByRole('button', { name: '暂停目标', exact: true }).click()
    await expect(dialog.getByRole('button', { name: '继续目标', exact: true })).toBeVisible()
    await dialog.getByRole('button', { name: '继续目标', exact: true }).click()
    await expect(dialog.getByRole('button', { name: '暂停目标', exact: true })).toBeVisible()
    await dialog.locator('.new-goal summary').focus()
    await page.keyboard.press('Enter')
    await expect(dialog.getByRole('textbox', { name: '持续目标', exact: true })).toBeVisible()
    await dialog.locator('.new-goal summary').click()
    await page.screenshot({
      path: fileURLToPath(new URL(`${scenario.name}-goal.png`, evidence)),
      fullPage: true
    })
    await page.keyboard.press('Escape')
    await expect(dialog).toHaveCount(0)
    await page.getByRole('button', { name: '打开会话导航', exact: true }).click()
    await page.getByRole('button', { name: '浏览器设备', exact: false }).click()
    dialog = page.getByRole('dialog', { name: '浏览器设备', exact: true })
    await expect(dialog).toContainText('在线')
    await expect(dialog.getByRole('button', { name: '手动官方扩展配对', exact: true })).toBeHidden()
    await dialog.locator('.manual-pair summary').click()
    await dialog.getByRole('button', { name: '手动官方扩展配对', exact: true }).click()
    await expect(dialog.getByRole('textbox', { name: '浏览器配对地址', exact: true })).toHaveValue(
      'wss://browser.example.invalid/pair#mock-only'
    )
    await expect(dialog.getByRole('textbox', { name: '设备名称', exact: true })).toBeHidden()
    await dialog.locator('.device-management summary').click()
    await dialog.getByRole('textbox', { name: '设备名称', exact: true }).fill('验收办公浏览器')
    await dialog.getByRole('button', { name: '保存名称', exact: true }).click()
    await expect(dialog).toContainText('设备名称已保存')
    await expect(dialog.locator('.device-management summary')).toContainText('验收办公浏览器')
    await page.screenshot({
      path: fileURLToPath(new URL(`${scenario.name}-devices.png`, evidence)),
      fullPage: true
    })
    await page.keyboard.press('Escape')
    await page.getByRole('button', { name: '打开会话导航', exact: true }).click()
    await page.getByRole('button', { name: '团队管理', exact: true }).click()
    dialog = page.getByRole('dialog', { name: '团队管理', exact: true })
    await dialog.getByRole('combobox', { name: '选择团队编辑', exact: true }).click()
    await page.getByRole('option', { name: '报告团队', exact: true }).click()
    await expect(dialog.getByRole('textbox', { name: '名称', exact: true })).toHaveValue('报告团队')
    await page.screenshot({
      path: fileURLToPath(new URL(`${scenario.name}-team.png`, evidence)),
      fullPage: true
    })
    assert.equal(
      await page.evaluate(() => document.documentElement.scrollWidth > innerWidth),
      false,
      `${scenario.name}页面横向溢出`
    )
    snapshots.push(scenario)
    await context.close()
  }
  assert.deepEqual(errors, [])
  if (roleSearchOnly) assert.equal(writes.filter((item) => item.path === '/api/v1/chat').length, 0)
  if (!commandScrollOnly && !roleSearchOnly) {
    assert.equal(writes.filter((item) => item.path === '/api/v1/chat').length, 4)
    assert.equal(writes.filter((item) => item.path === '/api/v1/chat/goals/create').length, 4)
  }
  await writeFile(
    new URL('result.json', evidence),
    JSON.stringify(
      {
        status: 'PASS',
        pid: process.pid,
        port: vite.httpServer.address().port,
        boundary:
          '真实Chromium、实际Vue应用与API mock；无后端、数据库、供应商或真实浏览器扩展，所有业务写均被route.fulfill拦截',
        snapshots,
        writes,
        requests,
        errors
      },
      null,
      2
    )
  )
  console.warn(`PASS ${snapshots.length} scenarios; evidence=${fileURLToPath(evidence)}`)
} catch (error) {
  if (currentPage)
    await currentPage
      .screenshot({ path: fileURLToPath(new URL('failure.png', evidence)), fullPage: true })
      .catch(() => {})
  await writeFile(
    new URL('failure.json', evidence),
    JSON.stringify({ error: String(error), errors, requests, writes }, null, 2)
  )
  throw error
} finally {
  await browser?.close()
  await vite.close()
  console.warn(`CLEANUP pid=${process.pid}`)
}
