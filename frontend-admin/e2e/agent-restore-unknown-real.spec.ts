import { expect, test } from '@playwright/test'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'
function required(name: string) {
  const value = process.env[name]
  if (!value) throw Error(name)
  return value
}
test.use({ trace: 'off', video: 'off', screenshot: 'off' })
test('历史恢复回执丢失后采集草稿和当前发布运行', async ({ page }) => {
  test.setTimeout(180_000)
  const agent = required('E2E_AGENT_ID'),
    evidence = required('E2E_ARTIFACT_DIR')
  const mode = process.env.E2E_AGENT_RESTORE_UNKNOWN ?? 'baseline'
  expect(['baseline', 'candidate']).toContain(mode)
  let upstreamStatus = 0,
    posts = 0
  let originalRequest: { agent_id: string; revision: number; request_id?: string } | undefined
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  await page.route('**/api/v1/agents/restore-revision', async (route) => {
    if (route.request().method() !== 'POST') return route.continue()
    posts++
    const request = route.request().postDataJSON(),
      response = await route.fetch(),
      body = await response.json()
    originalRequest = request
    upstreamStatus = response.status()
    await writeFile(
      join(evidence, 'restore-upstream.json'),
      JSON.stringify({ request, status: upstreamStatus, body }, null, 2)
    )
    expect(upstreamStatus).toBe(200)
    await route.abort('connectionreset')
  })
  try {
    await page.goto(`/login?redirect=${encodeURIComponent('/admin/agents/' + agent)}`)
    await page.locator('input[autocomplete="organization"]').fill(required('E2E_TENANT_SLUG'))
    await page.locator('input[autocomplete="username"]').fill('runtime-e2e-admin')
    await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
    await page.getByRole('button', { name: '登录', exact: true }).click()
    await expect(page.getByLabel('系统提示词', { exact: true })).toHaveValue(
      required('E2E_RESTORE_B')
    )
    await page.getByRole('tab', { name: '发布历史', exact: true }).click()
    const row = page.getByRole('row').filter({
      has: page.getByRole('cell', { name: required('E2E_RESTORE_REVISION'), exact: true })
    })
    await expect(row).toHaveCount(1)
    await row.getByRole('button', { name: '恢复为草稿', exact: true }).click()
    await page
      .getByRole('dialog', { name: '恢复智能体草稿', exact: true })
      .getByRole('button', { name: '恢复为草稿', exact: true })
      .click()
    await expect.poll(() => upstreamStatus).toBe(200)
    await expect.poll(() => posts).toBe(1)
    if (mode === 'candidate') {
      const firstMountSet = await page.request.get(
        `/api/v1/agents/capability-mounts?agentId=${encodeURIComponent(agent)}`,
        {
          headers: { Authorization: `Bearer ${required('E2E_ADMIN_TOKEN')}` }
        }
      )
      expect(firstMountSet.status()).toBe(200)
      await writeFile(
        join(evidence, 'first-restored-mount-set.json'),
        JSON.stringify(await firstMountSet.json(), null, 2)
      )
    }
    await page.waitForTimeout(300)
    const buttonDisabled = await row
      .getByRole('button', { name: '恢复为草稿', exact: true })
      .isDisabled()
    await page.getByRole('tab', { name: '基本配置', exact: true }).click()
    const shownPrompt = await page.getByLabel('系统提示词', { exact: true }).inputValue()
    await writeFile(
      join(evidence, 'after-loss.json'),
      JSON.stringify(
        { posts, shownPrompt, buttonDisabled, text: await page.locator('body').innerText() },
        null,
        2
      )
    )
    await page.screenshot({ path: join(evidence, 'after-loss.png'), fullPage: true })
    if (mode === 'candidate') {
      expect(originalRequest?.agent_id).toBe(agent)
      expect(originalRequest?.revision).toBe(Number(required('E2E_RESTORE_REVISION')))
      expect(originalRequest?.request_id).toBeTruthy()
      await expect(page.getByText('历史恢复结果尚未确认', { exact: false })).toBeVisible()
      await page.reload()
      const card = page.getByLabel('恢复结果核对', { exact: true })
      await expect(card).toBeVisible()
      await expect(card).toContainText(originalRequest!.request_id!)
      const receiptResponse = page.waitForResponse((response) => {
        const url = new URL(response.url())
        return (
          url.pathname === '/api/v1/agents/restore-revision/result' &&
          url.searchParams.get('agentId') === agent &&
          url.searchParams.get('requestId') === originalRequest!.request_id &&
          response.request().method() === 'GET'
        )
      })
      await card.getByRole('button', { name: '核对原恢复', exact: true }).click()
      const receipt = await receiptResponse
      expect(receipt.status()).toBe(200)
      const receiptBody = await receipt.json()
      expect(receiptBody.data.request_id).toBe(originalRequest!.request_id)
      expect(String(receiptBody.data.agent_id)).toBe(agent)
      expect(receiptBody.data.revision).toBe(Number(required('E2E_RESTORE_REVISION')))
      await expect(card).toHaveCount(0)
      await expect(page.getByLabel('系统提示词', { exact: true })).toHaveValue(
        required('E2E_RESTORE_A')
      )
      expect(posts).toBe(1)
      const mountSet = await page.request.get(
        `/api/v1/agents/capability-mounts?agentId=${encodeURIComponent(agent)}`,
        {
          headers: { Authorization: `Bearer ${required('E2E_ADMIN_TOKEN')}` }
        }
      )
      expect(mountSet.status()).toBe(200)
      await writeFile(
        join(evidence, 'recovered-mount-set.json'),
        JSON.stringify(await mountSet.json(), null, 2)
      )
      await writeFile(
        join(evidence, 'restore-receipt.json'),
        JSON.stringify({ request: originalRequest, receipt: receiptBody }, null, 2)
      )
      const publish = page.getByRole('button', { name: '发布', exact: true })
      await expect(publish).toBeEnabled()
      await publish.click()
      const published = page.waitForResponse(
        (response) =>
          new URL(response.url()).pathname === '/api/v1/agents/publish-with-receipt' &&
          response.request().method() === 'POST'
      )
      await page
        .getByRole('dialog', { name: '发布智能体', exact: true })
        .getByRole('button', { name: '发布', exact: true })
        .click()
      expect((await published).status()).toBe(200)
      await expect(
        page.getByRole('button', { name: '试用当前发布版本', exact: true })
      ).toBeEnabled()
    }
    await page.getByRole('button', { name: '试用当前发布版本', exact: true }).click()
    await expect(page).toHaveURL(/\/admin\/chat\?agent_id=/)
    await page.locator('.el-select').filter({ hasText: '终端用户' }).getByRole('combobox').click()
    await page.getByRole('option', { name: 'Runtime E2E User', exact: true }).click()
    await page.getByPlaceholder('输入消息', { exact: true }).fill('确认当前发布配置。')
    const accepted = page.waitForResponse(
      (response) =>
        new URL(response.url()).pathname === '/api/v1/chat' &&
        response.request().method() === 'POST'
    )
    await page.getByRole('button', { name: '发送', exact: true }).click()
    const response = await accepted
    expect(response.status()).toBe(202)
    const run = (await response.json()).data
    await expect(
      page.locator('.debug-message').filter({ hasText: required('E2E_EXPECTED_REPLY') })
    ).toHaveCount(1, { timeout: 60000 })
    expect(posts).toBe(1)
    expect(errors).toEqual([])
    await writeFile(
      join(evidence, 'browser-result.json'),
      JSON.stringify(
        {
          agent,
          run,
          posts,
          shownPrompt,
          buttonDisabled,
          mode,
          requestId: originalRequest?.request_id
        },
        null,
        2
      )
    )
    await page.screenshot({ path: join(evidence, 'runtime.png'), fullPage: true })
  } finally {
    await writeFile(join(evidence, 'browser-final.html'), await page.locator('body').innerHTML())
  }
})
