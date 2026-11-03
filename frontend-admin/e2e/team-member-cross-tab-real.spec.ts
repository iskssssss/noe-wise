import { expect, test } from '@playwright/test'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'

function required(name: string) {
  const value = process.env[name]
  if (!value) throw Error(`Missing isolated fixture: ${name}`)
  return value
}

test('发布新成员后读取团队成员并发布团队', async ({ page, context }) => {
  const member = required('E2E_DRAFT_AGENT_ID')
  const primary = required('E2E_PRIMARY_AGENT_ID')
  const mode = process.env.E2E_TEAM_MEMBER_MODE ?? 'candidate'
  const name = '跨标签成员验收团队'
  let reads = 0
  let memberPublishes = 0
  page.on('request', (request) => {
    if (request.method() === 'GET' && new URL(request.url()).pathname === '/api/v1/agents') reads++
  })
  await page.goto(`/login?redirect=${encodeURIComponent('/admin/teams')}`)
  await page.locator('input[autocomplete="organization"]').fill(required('E2E_TENANT_SLUG'))
  await page.locator('input[autocomplete="username"]').fill(required('E2E_USERNAME'))
  await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL(/\/admin\/teams$/)
  await page.getByRole('button', { name: '新增团队', exact: true }).click()
  const drawer = page.getByRole('dialog', { name: '新增团队' })
  await expect(drawer.getByText('已预选唯一可用成员并设为主智能体，可按需修改。')).toBeVisible()
  await drawer.getByLabel('名称', { exact: true }).fill(name)
  const primaryControl = drawer
    .getByRole('combobox', { name: '*主智能体', exact: true })
    .locator('xpath=ancestor::div[contains(@class,"el-form-item")][1]')
  await expect(primaryControl).toContainText(`Runtime E2E Agent · ${primary}`)
  const readsBefore = reads
  // 关闭浏览器自动化对焦点状态的模拟，保留浏览器实际的标签焦点事件。
  await (
    await context.newCDPSession(page)
  ).send('Emulation.setFocusEmulationEnabled', {
    enabled: false
  })
  await page.evaluate(() => {
    const state = window as typeof window & {
      teamFocusProbe?: {
        initialSession: string | null
        events: { event: string; visibility: string; focused: boolean }[]
      }
    }
    const probe = {
      initialSession: localStorage.getItem('skillforge.access_token.session'),
      events: [] as { event: string; visibility: string; focused: boolean }[]
    }
    state.teamFocusProbe = probe
    const record = (event: string) =>
      probe.events.push({
        event,
        visibility: document.visibilityState,
        focused: document.hasFocus()
      })
    record('initial')
    window.addEventListener('focus', () => record('focus'))
    window.addEventListener('blur', () => record('blur'))
    document.addEventListener('visibilitychange', () => record('visibilitychange'))
  })
  const agentPage = await context.newPage()
  await (
    await context.newCDPSession(agentPage)
  ).send('Emulation.setFocusEmulationEnabled', {
    enabled: false
  })
  agentPage.on('request', (request) => {
    if (
      request.method() === 'POST' &&
      new URL(request.url()).pathname === '/api/v1/agents/save-and-publish'
    )
      memberPublishes++
  })
  await agentPage.goto(`/admin/agents/${member}`)
  await agentPage.bringToFront()
  await expect.poll(() => agentPage.evaluate(() => document.hasFocus())).toBe(true)
  await expect.poll(() => page.evaluate(() => document.hasFocus())).toBe(false)
  await agentPage.getByLabel('系统提示词', { exact: true }).fill('团队成员验收助手')
  const published = agentPage.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      new URL(response.url()).pathname === '/api/v1/agents/save-and-publish'
  )
  await agentPage.getByRole('button', { name: '保存并发布', exact: true }).click()
  await agentPage
    .getByRole('dialog', { name: '保存并发布智能体' })
    .getByRole('button', { name: '保存并发布', exact: true })
    .click()
  const receipt = (await (await published).json()).data
  expect(String(receipt.agent_id)).toBe(member)
  await expect(agentPage.getByText('保存并发布已确认', { exact: false })).toBeVisible()
  await agentPage.goto(`/admin/agents/${primary}`)
  await agentPage.getByRole('tab', { name: '运行与协作', exact: true }).click()
  const delegation = agentPage
    .locator('section.admin-card')
    .filter({ has: agentPage.getByRole('heading', { name: '任务委派' }) })
  await delegation.locator('.el-switch__core').click()
  await expect(delegation.getByRole('switch')).toBeChecked()
  await delegation.locator('.el-select__wrapper').click()
  await agentPage.getByRole('option').filter({ hasText: '团队目录新成员' }).click()
  await agentPage.keyboard.press('Escape')
  const primaryPublish = agentPage.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      new URL(response.url()).pathname === '/api/v1/agents/save-and-publish'
  )
  await agentPage.getByRole('button', { name: '保存并发布', exact: true }).click()
  await agentPage
    .getByRole('dialog', { name: '保存并发布智能体' })
    .getByRole('button', { name: '保存并发布', exact: true })
    .click()
  expect((await primaryPublish).ok()).toBeTruthy()
  await expect(agentPage.getByText('保存并发布已确认', { exact: false })).toBeVisible()
  await page.bringToFront()
  await expect.poll(() => page.evaluate(() => document.hasFocus())).toBe(true)
  const focusEvidence = await page.evaluate(() => {
    const probe = (
      window as typeof window & {
        teamFocusProbe: {
          initialSession: string | null
          events: { event: string; visibility: string; focused: boolean }[]
        }
      }
    ).teamFocusProbe
    return {
      events: probe.events,
      sessionUnchanged:
        !!probe.initialSession &&
        probe.initialSession === localStorage.getItem('skillforge.access_token.session'),
      visibility: document.visibilityState,
      focused: document.hasFocus()
    }
  })
  expect(focusEvidence.sessionUnchanged).toBe(true)
  expect(focusEvidence.events.some((event) => event.event === 'blur')).toBe(true)
  expect(focusEvidence.events.some((event) => event.event === 'focus')).toBe(true)
  const members = drawer
    .locator('.el-form-item')
    .filter({ has: page.getByText('团队成员', { exact: true }) })
  let manualRefreshes = 0
  if (mode === 'baseline') {
    await members.locator('.el-select__wrapper').click()
    await expect(page.getByRole('option').filter({ hasText: '团队目录新成员' })).toHaveCount(0)
    expect(reads).toBe(readsBefore)
    await page.keyboard.press('Escape')
    await drawer.getByRole('button', { name: '刷新成员选项', exact: true }).click()
    manualRefreshes++
    await expect.poll(() => reads).toBe(readsBefore + 1)
  } else {
    await expect.poll(() => reads).toBeGreaterThan(readsBefore)
  }
  await expect(drawer.getByLabel('名称', { exact: true })).toHaveValue(name)
  await expect(primaryControl).toContainText(`Runtime E2E Agent · ${primary}`)
  await members.locator('.el-select__wrapper').click()
  await expect(page.getByRole('option').filter({ hasText: '团队目录新成员' })).toBeVisible()
  await page.getByRole('option').filter({ hasText: '团队目录新成员' }).click()
  await page.keyboard.press('Escape')
  await expect(primaryControl).toContainText(`Runtime E2E Agent · ${primary}`)
  const teamPublish = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      new URL(response.url()).pathname === '/api/v1/teams/save-and-publish'
  )
  await drawer.getByRole('button', { name: '保存并发布', exact: true }).click()
  await page
    .getByRole('dialog', { name: '保存并发布团队' })
    .getByRole('button', { name: '保存并发布', exact: true })
    .click()
  const teamResponse = await teamPublish
  expect(teamResponse.ok()).toBeTruthy()
  const teamReceipt = (await teamResponse.json()).data
  expect(teamReceipt.status).toBe('SUCCEEDED')
  await page.screenshot({ path: join(required('E2E_ARTIFACT_DIR'), `team-member-${mode}.png`) })
  await writeFile(
    join(required('E2E_ARTIFACT_DIR'), 'team-member-baseline.json'),
    JSON.stringify(
      {
        agentId: member,
        primaryId: primary,
        mode,
        focusEvidence,
        directoryReadsBefore: readsBefore,
        directoryReadsAfter: reads,
        manualRefreshes,
        publications: memberPublishes,
        name,
        teamId: String(teamReceipt.team.id),
        teamRevision: teamReceipt.team.revision
      },
      null,
      2
    )
  )
  expect(memberPublishes).toBe(2)
})
