import { expect, test } from '@playwright/test'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'

function required(name: string): string {
  const value = process.env[name]
  if (!value) throw new Error(`缺少隔离验收参数：${name}`)
  return value
}

test.describe.configure({ retries: 0 })

test('技能跨标签发布后原挂载抽屉更新', async ({ page }) => {
  test.setTimeout(180_000)
  const agent = required('E2E_AGENT_ID')
  const evidence = required('E2E_ARTIFACT_DIR')
  const marker = required('E2E_SKILL_MARKER')
  const mode = process.env.E2E_AGENT_SKILL_REFRESH_MODE ?? 'candidate'
  expect(['baseline', 'candidate']).toContain(mode)
  const baseline = mode === 'baseline'
  let catalogReads = 0,
    mountPosts = 0,
    manualRefreshClicks = 0,
    skillPublishes = 0
  page.on('request', (request) => {
    const url = new URL(request.url())
    if (
      request.method() === 'GET' &&
      url.pathname === '/api/v1/capabilities/requirements/options' &&
      url.searchParams.get('capability_type') === 'SKILL'
    )
      catalogReads++
    if (request.method() === 'POST' && url.pathname === '/api/v1/agents/capability-mounts')
      mountPosts++
  })
  await page.goto(`/login?redirect=${encodeURIComponent('/admin/agents/' + agent)}`)
  await page.locator('input[autocomplete="organization"]').fill(required('E2E_TENANT_SLUG'))
  await page.locator('input[autocomplete="username"]').fill(required('E2E_USERNAME'))
  await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL(new RegExp(`/admin/agents/${agent}$`))
  await page.getByRole('tab', { name: /能力挂载/ }).click()
  await page.getByRole('button', { name: '添加能力', exact: true }).first().click()
  const drawer = page.getByRole('dialog', { name: '添加能力挂载' })
  await expect(drawer.getByRole('button', { name: '刷新技能选项', exact: true })).toBeVisible()
  const catalogBefore = catalogReads
  expect(catalogBefore).toBeGreaterThan(0)
  const link = drawer.getByRole('link', { name: /(?:前往技能工作台|管理技能)（新标签页）/ })
  const [skill] = await Promise.all([page.context().waitForEvent('page'), link.click()])
  await skill.waitForLoadState('domcontentloaded')
  await expect(skill).toHaveURL(/\/admin\/skills$/)
  skill.on('request', (request) => {
    if (
      request.method() === 'POST' &&
      new URL(request.url()).pathname === '/api/v1/capabilities/skill-drafts/save-and-publish'
    )
      skillPublishes++
  })
  await skill.getByRole('button', { name: '新建技能', exact: true }).first().click()
  const create = skill.getByRole('dialog', { name: '新建技能' })
  await create.getByLabel('名称', { exact: true }).fill('跨页发布验收技能')
  await create.getByLabel('说明', { exact: true }).fill('验证机构内跨页发布和挂载')
  const created = skill.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      new URL(response.url()).pathname === '/api/v1/capabilities/skill-drafts/create-with-receipt'
  )
  await create.getByRole('button', { name: '创建并进入工作草稿', exact: true }).click()
  const createResponse = await created
  expect(createResponse.status()).toBe(200)
  const creation = (await createResponse.json()).data
  const definition = String(creation.definition_id)
  expect(definition).toMatch(/^[1-9]\d*$/)
  await expect(skill).toHaveURL(new RegExp(`/admin/skills/${definition}$`))
  await skill.getByRole('button', { name: '新建文件', exact: true }).first().click()
  await skill.getByPlaceholder('scripts/main.py').fill('SKILL.md')
  await skill.getByRole('button', { name: '创建文件', exact: true }).click()
  await skill
    .getByRole('textbox', { name: '文件 SKILL.md' })
    .fill(
      `---\nname: cross-tab-skill\ndescription: Isolated skill for actual runtime verification\n---\n${marker}\n`
    )
  await skill.getByRole('tab', { name: '发布设置' }).click()
  await skill.locator('.el-form-item').filter({ hasText: '版本号' }).locator('input').fill('v1')
  await skill
    .locator('.el-form-item')
    .filter({ hasText: '激活方式' })
    .locator('.el-select__wrapper')
    .click()
  await skill.getByRole('option', { name: '始终激活', exact: true }).click()
  const validated = skill.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      new URL(response.url()).pathname === '/api/v1/capabilities/skill-drafts/validate-content'
  )
  await skill.getByRole('button', { name: '校验', exact: true }).click()
  expect((await (await validated).json()).data.valid).toBe(true)
  const submitted = skill.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      new URL(response.url()).pathname === '/api/v1/capabilities/skill-drafts/save-and-publish'
  )
  await skill.getByRole('button', { name: '保存并发布', exact: true }).click()
  await skill.getByRole('button', { name: '保存并发布', exact: true }).last().click()
  const publishedResponse = await submitted
  expect(publishedResponse.status()).toBe(200)
  const receipt = (await publishedResponse.json()).data
  expect(receipt.status).toBe('SUCCEEDED')
  expect(String(receipt.definition_id)).toBe(definition)
  const version = String(receipt.publication.version_id)
  expect(version).toMatch(/^[1-9]\d*$/)
  await expect(skill.getByText(/技能发布已确认：版本/)).toBeVisible()
  expect(skillPublishes).toBe(1)
  expect(mountPosts).toBe(0)
  await skill.screenshot({ path: join(evidence, 'skill-published-tab.png'), fullPage: true })
  await page.bringToFront()
  if (baseline) {
    expect(catalogReads).toBe(catalogBefore)
    await drawer.getByRole('button', { name: '刷新技能选项', exact: true }).click()
    manualRefreshClicks++
  }
  await expect.poll(() => catalogReads, { timeout: 15_000 }).toBeGreaterThan(catalogBefore)
  const selection = drawer.locator('.el-form-item').filter({ hasText: '按名称、用途或标识查找' })
  await selection.locator('.el-select__wrapper').click()
  await page.getByRole('option').filter({ hasText: 'cross-tab-skill' }).click()
  await expect(drawer.getByText('激活方式由技能的当前发布版本决定。')).toBeVisible()
  await expect(drawer.getByRole('combobox', { name: '激活方式' })).toHaveCount(0)
  expect(mountPosts).toBe(0)
  await page.screenshot({ path: join(evidence, 'skill-before-mount.png'), fullPage: true })
  const mounted = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      new URL(response.url()).pathname === '/api/v1/agents/capability-mounts'
  )
  await drawer.getByRole('button', { name: '添加能力', exact: true }).click()
  const response = await mounted
  expect(response.status()).toBe(201)
  const command = response.request().postDataJSON()
  const mount = (await response.json()).data.mounts.find(
    (value: { capability_definition_id: string }) =>
      String(value.capability_definition_id) === definition
  )
  expect(String(command.agent_id)).toBe(agent)
  expect(command.payload.capability_type).toBe('SKILL')
  expect(String(command.payload.capability_definition_id)).toBe(definition)
  expect(command.payload.skill_config).not.toHaveProperty('activation_mode')
  expect(mount).toBeTruthy()
  expect(String(mount.capability_version_id)).toBe(version)
  expect(mountPosts).toBe(1)
  await expect(drawer).toBeHidden()
  await page.screenshot({ path: join(evidence, 'skill-mounted.png'), fullPage: true })
  await writeFile(
    join(evidence, 'skill-cross-tab.json'),
    JSON.stringify({
      agentId: agent,
      creation,
      receipt,
      definition,
      version,
      mount,
      mode,
      catalogBefore,
      catalogReads,
      manualRefreshClicks,
      mountPosts,
      skillPublishes
    })
  )
})
