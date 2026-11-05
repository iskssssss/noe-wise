import { expect, test } from '@playwright/test'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'
function required(key: string) {
  const value = process.env[key]
  if (!value) throw Error(key)
  return value
}
test('跨标签发布成员后委派目录与真实执行', async ({ page, context }) => {
  test.setTimeout(180000)
  const agent = required('E2E_AGENT_ID'),
    child = required('E2E_CHILD_AGENT'),
    marker = required('E2E_DIRECTORY_MARKER'),
    evidence = required('E2E_ARTIFACT_DIR')
  const candidate = process.env.E2E_DELEGATION_DIRECTORY === 'candidate'
  const existing = candidate ? required('E2E_EXISTING_TARGET') : ''
  let directoryReads = 0
  page.on('request', (request) => {
    if (new URL(request.url()).pathname === '/api/v1/agents' && request.method() === 'GET')
      directoryReads++
  })
  await page.goto(`/login?redirect=${encodeURIComponent('/admin/agents/' + agent)}`)
  await page.locator('input[autocomplete="organization"]').fill(required('E2E_TENANT_SLUG'))
  await page.locator('input[autocomplete="username"]').fill(required('E2E_USERNAME'))
  await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.getByLabel('系统提示词', { exact: true })).toBeVisible()
  await expect.poll(() => directoryReads).toBeGreaterThan(0)
  await page.getByLabel('系统提示词', { exact: true }).fill(marker)
  const other = await context.newPage()
  await other.goto('/admin/agents/' + child)
  const published = other.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/agents/publish-with-receipt' &&
      response.request().method() === 'POST'
  )
  await other.getByRole('button', { name: '发布', exact: true }).click()
  await other
    .getByRole('dialog', { name: '发布智能体', exact: true })
    .getByRole('button', { name: '发布', exact: true })
    .click()
  expect((await published).status()).toBe(200)
  await other.close()
  const beforeReads = directoryReads
  await page.getByRole('tab', { name: '运行与协作', exact: true }).click()
  if (!candidate)
    await page
      .getByRole('tabpanel', { name: '运行与协作', exact: true })
      .locator('.el-switch__core')
      .click()
  const selector = page.getByRole('combobox', { name: '允许接收任务的智能体', exact: true })
  const selectWrapper = page
    .locator('.el-form-item')
    .filter({ hasText: '允许接收任务的智能体' })
    .locator('.el-select__wrapper')
  await selectWrapper.click()
  const targetName = 'Directory child ' + marker
  await selector.fill(targetName)
  await expect(page.getByRole('option', { name: targetName, exact: true })).toHaveCount(0)
  const stale = {
    directoryReads,
    beforeReads,
    refreshButtons: await page
      .getByRole('button', { name: '重新读取委派选项', exact: true })
      .count(),
    visible: await page.locator('body').innerText()
  }
  expect(directoryReads).toBe(beforeReads)
  expect(stale.refreshButtons).toBe(0)
  await writeFile(join(evidence, 'delegation-stale-directory.json'), JSON.stringify(stale, null, 2))
  await page.screenshot({ path: join(evidence, 'delegation-stale-directory.png'), fullPage: true })
  await page.keyboard.press('Escape')
  let refreshedDirectory: unknown
  if (candidate) {
    await expect(selectWrapper).toContainText('Directory existing ' + marker)
    const refreshed = page.waitForResponse(
      (response) =>
        new URL(response.url()).pathname === '/api/v1/agents' &&
        response.request().method() === 'GET'
    )
    await page.getByRole('button', { name: '刷新委派选项', exact: true }).click()
    const response = await refreshed
    expect(response.status()).toBe(200)
    refreshedDirectory = await response.json()
    expect(directoryReads).toBe(beforeReads + 1)
    await expect(selectWrapper).toContainText('Directory existing ' + marker)
    await expect(page.getByLabel('系统提示词', { exact: true })).toHaveValue(marker)
    await selector.fill('')
    await page.screenshot({
      path: join(evidence, 'delegation-refreshed-with-draft.png'),
      fullPage: true
    })
  } else {
    page.once('dialog', (dialog) => dialog.accept())
    await page.reload()
    await expect(page.getByLabel('系统提示词', { exact: true })).not.toHaveValue(marker)
    await page.getByLabel('系统提示词', { exact: true }).fill(marker)
    await page.getByRole('tab', { name: '运行与协作', exact: true }).click()
    await page
      .getByRole('tabpanel', { name: '运行与协作', exact: true })
      .locator('.el-switch__core')
      .click()
  }
  await selectWrapper.click()
  await page.getByRole('option', { name: targetName, exact: true }).click()
  await page.keyboard.press('Escape')
  const saved = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/agents/save-and-publish' &&
      response.request().method() === 'POST'
  )
  await page.getByRole('button', { name: '保存并发布', exact: true }).click()
  await page
    .getByRole('dialog', { name: '保存并发布智能体', exact: true })
    .getByRole('button', { name: '保存并发布', exact: true })
    .click()
  const savedResponse = await saved
  expect(savedResponse.status()).toBe(200)
  const command = savedResponse.request().postDataJSON()
  expect(command.payload.delegation_config.target_agent_ids).toEqual(
    candidate ? [existing, child] : [child]
  )
  await expect(page.getByRole('dialog', { name: '保存并发布智能体', exact: true })).toBeHidden()
  await page.getByRole('button', { name: '试用当前发布版本', exact: true }).click()
  await page.locator('.el-select').filter({ hasText: '终端用户' }).getByRole('combobox').click()
  await page.getByRole('option', { name: 'Runtime E2E User', exact: true }).click()
  await page.getByPlaceholder('输入消息', { exact: true }).fill('请委派给已配置成员并汇总')
  const accepted = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/chat' && response.request().method() === 'POST'
  )
  await page.getByRole('button', { name: '发送', exact: true }).click()
  const response = await accepted
  expect(response.status()).toBe(202)
  const run = (await response.json()).data
  await expect(
    page.locator('.debug-message').filter({ hasText: 'PARENT_ANSWER_' + marker })
  ).toHaveCount(1, { timeout: 60000 })
  await writeFile(
    join(evidence, 'delegation-directory.json'),
    JSON.stringify(
      { agent, child, existing, stale, refreshedDirectory, command, run, directoryReads },
      null,
      2
    )
  )
  await page.screenshot({
    path: join(evidence, 'delegation-runtime-completed.png'),
    fullPage: true
  })
})
