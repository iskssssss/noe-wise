import { expect, test } from '@playwright/test'
import { readFile, writeFile } from 'node:fs/promises'
import { join } from 'node:path'
function required(key: string) {
  const value = process.env[key]
  if (!value) throw Error(key)
  return value
}
test('停用响应丢失后页面状态与真实工具派发', async ({ page, context }) => {
  test.setTimeout(180000)
  const agent = required('E2E_AGENT_ID'),
    key = required('E2E_DEPLOYMENT_KEY'),
    evidence = required('E2E_ARTIFACT_DIR')
  const mode = process.env.E2E_TOOL_STOP_MODE ?? 'candidate'
  if (!['candidate', 'not-arrived'].includes(mode)) throw Error(`E2E_TOOL_STOP_MODE: ${mode}`)
  const notArrived = mode === 'not-arrived'
  await page.goto(`/login?redirect=${encodeURIComponent('/admin/agents/' + agent)}`)
  await page.locator('input[autocomplete="organization"]').fill(required('E2E_TENANT_SLUG'))
  await page.locator('input[autocomplete="username"]').fill(required('E2E_USERNAME'))
  await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await page.getByRole('button', { name: '试用当前发布版本', exact: true }).click()
  await page.locator('.el-select').filter({ hasText: '终端用户' }).getByRole('combobox').click()
  await page.getByRole('option', { name: 'Runtime E2E User', exact: true }).click()
  await page.getByPlaceholder('输入消息', { exact: true }).fill('调用已挂载的当前时间工具')
  const accepted = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/chat' && response.request().method() === 'POST'
  )
  await page.getByRole('button', { name: '发送', exact: true }).click()
  const response = await accepted
  expect(response.status()).toBe(202)
  const run = (await response.json()).data
  await expect
    .poll(
      async () => {
        try {
          return await readFile(join(evidence, 'model-entered'), 'utf8')
        } catch {
          return ''
        }
      },
      { timeout: 60000 }
    )
    .toBe('entered')
  const deployments = await context.newPage()
  await deployments.goto('/admin/tool-deployments')
  const row = deployments.getByRole('row').filter({ hasText: key })
  await expect(row.getByRole('button', { name: '停用', exact: true })).toBeEnabled()
  let stops = 0,
    sourceResponse: unknown,
    sourceCommand: unknown,
    sourceRevision: string | undefined
  let requestId = '',
    originalReceipt: unknown,
    recoveredReceipt: unknown
  let upstreamStops = 0,
    missingReceipt: unknown
  const stopCommands: unknown[] = []
  let complete!: () => void
  const dropped = new Promise<void>((resolve) => {
    complete = resolve
  })
  await deployments.route(
    '**/api/v1/capabilities/deployments/state-with-receipt',
    async (route) => {
      stops++
      sourceCommand = route.request().postDataJSON()
      stopCommands.push(sourceCommand)
      if (stops > 1) expect(sourceCommand).toEqual(stopCommands[0])
      sourceRevision = route.request().headers()['if-match']
      const command = route.request().postDataJSON()
      requestId = command.request_id
      expect(command.deployment_key).toBe(key)
      expect(command.active).toBe(false)
      expect(command.expected_revision).toBe(1)
      expect(sourceRevision).toBeUndefined()
      sourceRevision = String(command.expected_revision)
      if (notArrived && stops === 1) {
        await route.abort('failed')
        complete()
        return
      }
      upstreamStops++
      const response = await route.fetch({ maxRetries: 0 })
      expect(response.status()).toBe(200)
      sourceResponse = await response.json()
      originalReceipt = (sourceResponse as { data: unknown }).data
      expect(originalReceipt).toMatchObject({
        request_id: requestId,
        status: 'SUCCEEDED',
        deployment_key: key,
        deployment_status: 'DISABLED',
        revision: 2
      })
      if (notArrived) await route.fulfill({ response })
      else {
        await route.abort('failed')
        complete()
      }
    }
  )
  await row.getByRole('button', { name: '停用', exact: true }).click()
  await dropped
  await expect(deployments.getByText(requestId, { exact: false }).first()).toBeVisible()
  await expect(
    deployments
      .getByText('当前部署状态不能代替原请求回执，请勿重复提交。', { exact: false })
      .first()
  ).toBeVisible()
  await writeFile(
    join(evidence, 'tool-stop-unknown.txt'),
    await deployments.locator('body').innerText()
  )
  await expect(row.getByRole('button', { name: '停用', exact: true })).toBeDisabled()
  const lookup = deployments.waitForResponse((response) => {
    const url = new URL(response.url())
    return (
      url.pathname === '/api/v1/capabilities/deployments/state-with-receipt/result' &&
      url.searchParams.get('request_id') === requestId
    )
  })
  if (notArrived) {
    await deployments.getByRole('button', { name: '核对原启停结果', exact: true }).click()
    const response = await lookup
    expect(response.status()).toBe(204)
    missingReceipt = { status: response.status() }
    expect(stops).toBe(1)
    expect(upstreamStops).toBe(0)
    await expect(row.getByRole('button', { name: '停用', exact: true })).toBeDisabled()
    const continued = deployments.waitForResponse(
      (response) =>
        new URL(response.url()).pathname ===
          '/api/v1/capabilities/deployments/state-with-receipt' &&
        response.request().method() === 'POST'
    )
    await deployments.getByRole('button', { name: '继续原启停操作', exact: true }).click()
    expect((await continued).status()).toBe(200)
    const read = await deployments.evaluate(async (requestId) => {
      const response = await fetch(
        `/api/v1/capabilities/deployments/state-with-receipt/result?request_id=${requestId}`,
        {
          headers: { Authorization: `Bearer ${localStorage.getItem('skillforge.access_token')}` }
        }
      )
      return { status: response.status, body: await response.json() }
    }, requestId)
    expect(read.status).toBe(200)
    recoveredReceipt = read.body.data
  } else {
    deployments.once('dialog', (dialog) => dialog.accept())
    await deployments.reload()
    const response = await lookup
    expect(response.status()).toBe(200)
    recoveredReceipt = (await response.json()).data
  }
  expect(recoveredReceipt).toEqual(originalReceipt)
  await expect(deployments.getByText('原启停回执：请求', { exact: false }).first()).toBeVisible()
  await expect(
    deployments.getByText('此回执不代表当前状态，当前部署以列表为准。', { exact: false }).first()
  ).toBeVisible()
  await expect(row.getByRole('button', { name: '启用', exact: true })).toBeEnabled()
  await expect(
    deployments.getByText('原停用请求已确认（修订 2）；当前状态以列表为准', { exact: true }).first()
  ).toBeVisible()
  const current = await deployments.evaluate(async (key) => {
    const response = await fetch(`/api/v1/capabilities/deployments/detail?deploymentKey=${key}`, {
      headers: { Authorization: `Bearer ${localStorage.getItem('skillforge.access_token')}` }
    })
    return { status: response.status, body: await response.json() }
  }, key)
  expect(current.status).toBe(200)
  expect(current.body.data.status).toBe('DISABLED')
  const staleVisible = await row.innerText()
  await deployments.screenshot({
    path: join(evidence, 'tool-stop-response-lost.png'),
    fullPage: true
  })
  await writeFile(
    join(evidence, 'tool-stop-original.json'),
    JSON.stringify(
      {
        sourceResponse,
        sourceCommand,
        sourceRevision,
        originalReceipt,
        recoveredReceipt,
        currentState: current,
        staleVisible,
        stops
      },
      null,
      2
    )
  )
  await writeFile(join(evidence, 'release-tool-call'), 'release')
  const statusReads: unknown[] = []
  let actualStatus: unknown
  await expect
    .poll(
      async () => {
        const value = await page.evaluate(async (run) => {
          const response = await fetch(
            `/api/v1/chat/runs/status?session_id=${encodeURIComponent(run.session_id)}&run_id=${encodeURIComponent(run.execution_id)}`,
            {
              headers: {
                Authorization: `Bearer ${localStorage.getItem('skillforge.access_token')}`
              }
            }
          )
          return { status: response.status, body: await response.json() }
        }, run)
        expect(value.status).toBe(200)
        actualStatus = value
        statusReads.push(value)
        await writeFile(
          join(evidence, 'tool-stop-run-status.json'),
          JSON.stringify({ actualStatus, statusReads }, null, 2)
        )
        return ['queued', 'running'].includes(value.body.data.status)
      },
      { timeout: 20000 }
    )
    .toBe(false)
  const runtimeVisible = await page.locator('body').innerText()
  const refreshed = deployments.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/capabilities/deployments' &&
      response.request().method() === 'GET'
  )
  await deployments.getByRole('button', { name: '刷新', exact: true }).click()
  expect((await refreshed).status()).toBe(200)
  await expect(row.getByRole('button', { name: '启用', exact: true })).toBeEnabled()
  expect(stops).toBe(notArrived ? 2 : 1)
  expect(upstreamStops).toBe(1)
  await writeFile(
    join(evidence, 'tool-stop.json'),
    JSON.stringify(
      {
        run,
        key,
        sourceResponse,
        sourceCommand,
        sourceRevision,
        currentState: current,
        staleVisible,
        refreshedVisible: await row.innerText(),
        stops,
        originalReceipt,
        recoveredReceipt,
        requestId,
        upstreamStops,
        missingReceipt,
        stopCommands,
        notArrived,
        mode,
        actualStatus,
        statusReads,
        runtimeVisible
      },
      null,
      2
    )
  )
  await deployments.screenshot({
    path: join(evidence, 'tool-stop-read-refreshed.png'),
    fullPage: true
  })
})
