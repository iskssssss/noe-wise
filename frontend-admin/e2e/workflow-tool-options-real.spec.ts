import { expect, test } from '@playwright/test'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'

function required(name: string) {
  const value = process.env[name]
  if (!value) throw Error(`缺少验收参数：${name}`)
  return value
}
test.describe.configure({ retries: 0 })
test.use({ trace: 'off', video: 'off', screenshot: 'off' })
test('工作流工具依赖目录读取的实际影响', async ({ page }) => {
  test.setTimeout(120_000)
  const evidence = required('E2E_ARTIFACT_DIR')
  const definition = required('E2E_RENDER_DEFINITION')
  const mode = required('E2E_WORKFLOW_TOOL_OPTIONS')
  expect(['baseline', 'candidate']).toContain(mode)
  const requests: { path: string; status: number }[] = []
  let writes = 0
  let upstream = 0
  let phase: 'initialization' | 'install' = 'initialization'
  let versionRequests = 0
  let initialVersions = 0
  page.on('request', (request) => {
    if (new URL(request.url()).pathname === '/api/v1/capabilities/definitions/versions') {
      if (phase === 'install') versionRequests++
      else initialVersions++
    }
    if (
      request.method() === 'POST' &&
      new URL(request.url()).pathname.endsWith('/install-with-receipt')
    )
      writes++
  })
  page.on('response', (response) => {
    const url = new URL(response.url())
    if (url.pathname.startsWith('/api/v1/capabilities/'))
      requests.push({ path: url.pathname + url.search, status: response.status() })
  })
  await page.route('**/api/v1/capabilities/definitions/versions?*', async (route) => {
    if (
      phase !== 'install' ||
      new URL(route.request().url()).searchParams.get('definitionId') !== definition
    )
      return route.continue()
    const response = await route.fetch()
    upstream = response.status()
    await writeFile(
      join(evidence, 'upstream.json'),
      JSON.stringify(
        {
          path: new URL(route.request().url()).pathname + new URL(route.request().url()).search,
          status: upstream,
          body: await response.json()
        },
        null,
        2
      )
    )
    await route.abort('failed')
  })
  try {
    await page.goto(
      `/login?redirect=${encodeURIComponent('/admin/workflows/workflow-tool-options')}`
    )
    await page.locator('input[autocomplete="organization"]').fill(required('E2E_TENANT_SLUG'))
    await page.locator('input[autocomplete="username"]').fill('runtime-e2e-admin')
    await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
    await page.getByRole('button', { name: '登录', exact: true }).click()
    await expect
      .poll(() =>
        requests.some(
          (item) =>
            item.path.includes('/capabilities/definitions/versions?') &&
            item.path.includes('definitionId=' + definition) &&
            item.status === 200
        )
      )
      .toBe(true)
    await expect(page.getByRole('button', { name: '刷新工具参数', exact: true })).toBeEnabled()
    phase = 'install'
    await page.getByRole('button', { name: '安装', exact: true }).first().click()
    if (mode === 'baseline') {
      await expect.poll(() => upstream).toBe(200)
      expect(versionRequests).toBeGreaterThan(0)
      await expect(page.getByRole('dialog', { name: '安装工作流' })).toHaveCount(0)
      expect(writes).toBe(0)
    } else {
      const drawer = page.getByRole('dialog', { name: '安装工作流' })
      await expect(drawer).toBeVisible()
      await expect(drawer.locator('.el-form-item').filter({ hasText: '渲染工具' })).toContainText(
        required('E2E_RENDER_LABEL')
      )
      await expect(drawer.locator('.el-form-item').filter({ hasText: '时间工具' })).toContainText(
        required('E2E_CLOCK_LABEL')
      )
      await expect(drawer.getByText('已选择唯一可用实例')).toHaveCount(2)
      expect(versionRequests).toBe(0)
      expect(upstream).toBe(0)
      expect(
        requests.some(
          (item) =>
            item.path.startsWith(
              '/api/v1/capabilities/requirements/options?capability_type=TOOL'
            ) && item.status === 200
        )
      ).toBe(true)
      const [response] = await Promise.all([
        page.waitForResponse(
          (item) =>
            item.request().method() === 'POST' &&
            new URL(item.url()).pathname.endsWith('/install-with-receipt')
        ),
        drawer.getByRole('button', { name: '安装', exact: true }).click()
      ])
      const command = response.request().postDataJSON()
      const receipt = (await response.json()).data
      await writeFile(
        join(evidence, 'install-receipt.json'),
        JSON.stringify({ status: response.status(), command, receipt }, null, 2)
      )
      expect(response.status()).toBe(200)
      expect(writes).toBe(1)
      expect(receipt.workflow_id).toBe('workflow-tool-options')
      expect(receipt.request_id).toBe(command.request_id)
      expect(receipt.instance_id).toBeTruthy()
      const selected = command.payload.bindings.capabilities
      expect(selected).toHaveLength(2)
      for (const [slot, prefix] of [
        ['render_slot', 'E2E_RENDER_'],
        ['clock_slot', 'E2E_CLOCK_']
      ]) {
        const value = selected.find((item: { slot: string }) => item.slot === slot)
        expect(value.capability.version_id).toBe(required(prefix + 'VERSION'))
        expect(value.tool_deployment_id).toBe(required(prefix + 'DEPLOYMENT'))
      }
    }
  } finally {
    await writeFile(
      join(evidence, 'browser.json'),
      JSON.stringify(
        { mode, upstream, writes, initialVersions, versionRequests, requests },
        null,
        2
      )
    )
    await writeFile(join(evidence, 'page.html'), await page.locator('body').innerHTML())
    await page.screenshot({ path: join(evidence, 'page.png'), fullPage: true })
  }
})
