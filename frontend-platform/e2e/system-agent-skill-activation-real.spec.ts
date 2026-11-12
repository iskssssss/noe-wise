import { expect, test } from '@playwright/test'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'

function required(name: string): string {
  const value = process.env[name]
  if (!value) throw Error(`缺少系统技能验收参数：${name}`)
  return value
}

test('系统智能体挂载技能后按发布配置激活并完成真实运行', async ({ page }) => {
  const mode = process.env.E2E_SYSTEM_AGENT_SKILL_ACTIVATION ?? 'candidate'
  expect(['baseline', 'candidate']).toContain(mode)
  const evidence = required('E2E_ARTIFACT_DIR')
  const agentId = required('E2E_AGENT_ID')
  const skillId = required('E2E_SKILL_ID')
  const network: { path: string; method: string; status: number }[] = []
  const errors: string[] = []
  let loggedIn = false
  let debugPosts = 0
  page.on('pageerror', (error) => errors.push(error.message))
  page.on('request', (request) => {
    if (
      request.method() === 'POST' &&
      new URL(request.url()).pathname === '/api/v1/admin/system-agents/debug'
    )
      debugPosts++
  })
  page.on('response', (response) => {
    const url = new URL(response.url())
    if (url.pathname.startsWith('/api/v1/admin/system-agents'))
      network.push({
        path: url.pathname + url.search,
        method: response.request().method(),
        status: response.status()
      })
  })
  try {
    await page.goto(`/login?redirect=${encodeURIComponent('/platform/system-agents/' + agentId)}`)
    await page.getByLabel('账号', { exact: true }).fill(required('E2E_USERNAME'))
    await page.getByLabel('密码', { exact: true }).fill(required('E2E_PASSWORD'))
    await page.getByRole('button', { name: '登录运营台', exact: true }).click()
    await expect(page).toHaveURL(new RegExp(`/platform/system-agents/${agentId}$`))
    loggedIn = true
    await page.getByRole('button', { name: '添加挂载', exact: true }).click()
    await page.getByLabel('能力（跟随当前发布）', { exact: true }).click()
    await page.getByRole('option', { name: 'always-browser-skill', exact: true }).click()
    await page.getByRole('button', { name: /挂载额度与顺序/ }).click()
    await page.getByLabel('Token 预算', { exact: true }).fill('2000')
    await page.getByLabel('Token 预算', { exact: true }).press('Tab')
    const activation = page
      .locator('.el-form-item')
      .filter({ has: page.locator('.el-form-item__label').filter({ hasText: /^激活方式$/ }) })
    if (mode === 'baseline')
      await expect(activation.locator('.el-select')).toContainText('按需激活')
    else {
      await expect(activation.getByRole('combobox')).toHaveCount(0)
      await expect(page.getByText('由技能当前发布配置决定', { exact: true })).toBeVisible()
    }
    const saved = page.waitForResponse(
      (response) =>
        response.request().method() === 'PUT' &&
        new URL(response.url()).pathname === '/api/v1/admin/system-agents/update'
    )
    await page.getByRole('button', { name: '保存草稿', exact: true }).click()
    const savedResponse = await saved
    const savedBody = await savedResponse.json()
    const savedRequest = savedResponse.request().postDataJSON()
    await writeFile(
      join(evidence, 'saved-agent.json'),
      JSON.stringify(
        { status: savedResponse.status(), request: savedRequest, response: savedBody },
        null,
        2
      )
    )
    expect(savedResponse.status()).toBe(200)
    expect(savedRequest.config.mounts).toHaveLength(1)
    expect(String(savedRequest.config.mounts[0].capability_definition_id)).toBe(skillId)
    if (mode === 'baseline') expect(savedRequest.config.mounts[0].activation_mode).toBe('ON_DEMAND')
    else {
      expect(savedRequest.config.mounts[0].activation_mode).toBeNull()
      expect(savedBody.data.draft.mounts[0].activation_mode).toBe('ALWAYS')
    }
    await page.getByRole('button', { name: '发布', exact: true }).click()
    const confirm = page.getByRole('dialog', { name: '系统智能体', exact: true })
    const publication = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        new URL(response.url()).pathname === '/api/v1/admin/system-agents/publish'
    )
    await confirm.getByRole('button', { name: '发布', exact: true }).click()
    const published = await publication
    const publishedBody = await published.json()
    await writeFile(
      join(evidence, 'published-agent.json'),
      JSON.stringify(
        {
          status: published.status(),
          request: published.request().postDataJSON(),
          response: publishedBody
        },
        null,
        2
      )
    )
    expect(published.status()).toBe(200)
    expect(publishedBody.data.published_version).toBe(1)
    if (mode === 'candidate') {
      expect(publishedBody.data.published.mounts[0].activation_mode).toBe('ALWAYS')
      expect(publishedBody.data.draft.mounts[0].activation_mode).toBe('ALWAYS')
    }
    await expect(confirm).not.toBeVisible()
    await page.getByRole('tab', { name: '调试与运行', exact: true }).click()
    await page.getByText('已发布版本', { exact: true }).click()
    await page.getByLabel('调试输入', { exact: true }).fill('请依据已激活技能给出一次回复。')
    const debugResponse = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        new URL(response.url()).pathname === '/api/v1/admin/system-agents/debug',
      { timeout: 60_000 }
    )
    await page.getByRole('button', { name: '运行调试', exact: true }).click()
    const first = await debugResponse
    const firstBody = await first.json()
    const command = first.request().postDataJSON()
    await writeFile(
      join(evidence, 'debug-first.json'),
      JSON.stringify({ status: first.status(), request: command, response: firstBody }, null, 2)
    )
    expect(first.status()).toBe(200)
    expect(command.source).toBe('PUBLISHED')
    let debug = firstBody.data
    const reads: unknown[] = []
    if (mode === 'candidate') {
      await expect
        .poll(
          async () => {
            if (
              debug.status === 'COMPLETED' ||
              (debug.status === 'FAILED' && debug.error_code !== 'system_agent_outcome_unknown')
            )
              return debug.status
            const response = page.waitForResponse(
              (value) =>
                new URL(value.url()).pathname === '/api/v1/admin/system-agents/debug-result'
            )
            await page.getByRole('button', { name: '只读查询原调试', exact: true }).click()
            const result = await response
            const body = await result.json()
            reads.push({ status: result.status(), response: body })
            expect(result.status()).toBe(200)
            debug = body.data
            await writeFile(join(evidence, 'debug-reads.json'), JSON.stringify(reads, null, 2))
            return debug.status
          },
          { timeout: 60_000, intervals: [500, 1000] }
        )
        .toBe('COMPLETED')
      expect(debug.output).toBe(required('E2E_EXPECTED_REPLY'))
      await expect(page.getByRole('status', { name: '原调试结果待核对' })).toHaveCount(0)
      await expect(page.getByText(required('E2E_EXPECTED_REPLY'), { exact: true })).toBeVisible()
    } else {
      expect(debug.status).not.toBe('COMPLETED')
      if (debug.status === 'RUNNING' || debug.error_code === 'system_agent_outcome_unknown') {
        await expect(page.getByRole('status', { name: '原调试结果待核对' })).toBeVisible()
        await expect(page.getByRole('button', { name: '运行调试', exact: true })).toBeDisabled()
      }
    }
    expect(debugPosts).toBe(1)
    expect(errors).toEqual([])
    await writeFile(
      join(evidence, 'browser-result.json'),
      JSON.stringify({ mode, agentId, skillId, debugPosts, debug, reads }, null, 2)
    )
  } finally {
    await writeFile(
      join(evidence, 'browser-state.json'),
      JSON.stringify({ mode, agentId, skillId, debugPosts, errors, network }, null, 2)
    )
    if (loggedIn) {
      await writeFile(join(evidence, 'final.html'), await page.locator('body').innerHTML())
      await page.screenshot({
        path: join(evidence, `system-agent-skill-${mode}.png`),
        fullPage: true
      })
    }
  }
})
