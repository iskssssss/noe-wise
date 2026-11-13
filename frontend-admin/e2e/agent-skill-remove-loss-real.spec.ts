import { expect, test } from '@playwright/test'
import { writeFile, access } from 'node:fs/promises'
import { join } from 'node:path'

function required(name: string): string {
  const value = process.env[name]
  if (!value) throw new Error(`缺少隔离验收参数：${name}`)
  return value
}

test.describe.configure({ retries: 0 })
test.use({ trace: 'off', video: 'off', screenshot: 'off' })

test('技能跨标签发布后原挂载抽屉更新', async ({ page }) => {
  test.setTimeout(240_000)
  const agent = required('E2E_AGENT_ID')
  const evidence = required('E2E_ARTIFACT_DIR')
  const marker = required('E2E_SKILL_MARKER')
  const removalMode = process.env.E2E_SKILL_REMOVE_LOSS ?? 'baseline'
  expect(['baseline', 'candidate', 'not-arrived']).toContain(removalMode)
  const candidate = removalMode !== 'baseline'
  const notArrived = removalMode === 'not-arrived'
  const mode = process.env.E2E_AGENT_SKILL_REFRESH_MODE ?? 'candidate'
  expect(['baseline', 'candidate']).toContain(mode)
  const baseline = mode === 'baseline'
  const budgetMode = process.env.E2E_SKILL_BUDGET
  const budgetExperiment = budgetMode === 'baseline' || budgetMode === 'candidate'
  let requirementOptions: Array<{ definition_id: string; skill_estimated_tokens?: number }> = []
  page.on('response', async (response) => {
    const url = new URL(response.url())
    if (
      url.pathname === '/api/v1/capabilities/requirements/options' &&
      url.searchParams.get('capability_type') === 'SKILL' &&
      response.ok()
    )
      requirementOptions = (await response.json()).data
  })
  let catalogReads = 0,
    mountPosts = 0,
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
  if (process.env.E2E_SKILL_RESOURCE_BUDGET) {
    await skill.getByRole('button', { name: '新建文件', exact: true }).first().click()
    await skill.getByPlaceholder('scripts/main.py').fill('references/guide.md')
    await skill.getByRole('button', { name: '创建文件', exact: true }).click()
    await skill
      .getByRole('textbox', { name: '文件 references/guide.md' })
      .fill('RESOURCE_' + marker + '\n' + '详细资料内容 '.repeat(400))
  }
  await skill.getByRole('tab', { name: '发布设置' }).click()
  await skill.locator('.el-form-item').filter({ hasText: '版本号' }).locator('input').fill('v1')
  await skill
    .locator('.el-form-item')
    .filter({ hasText: '激活方式' })
    .locator('.el-select__wrapper')
    .click()
  await skill
    .getByRole('option', {
      name: process.env.E2E_SKILL_RESOURCE_BUDGET ? '按需激活' : '始终激活',
      exact: true
    })
    .click()
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
  }
  await expect.poll(() => catalogReads, { timeout: 15_000 }).toBeGreaterThan(catalogBefore)
  const selection = drawer.locator('.el-form-item').filter({ hasText: '按名称、用途或标识查找' })
  await selection.locator('.el-select__wrapper').click()
  await page.getByRole('option').filter({ hasText: 'cross-tab-skill' }).click()
  await expect(drawer.getByText('激活方式由技能的当前发布版本决定。')).toBeVisible()
  await expect(drawer.getByRole('combobox', { name: '激活方式' })).toHaveCount(0)
  expect(mountPosts).toBe(0)
  await page.screenshot({ path: join(evidence, 'skill-before-mount.png'), fullPage: true })
  if (process.env.E2E_SKILL_RESOURCE_BUDGET)
    await drawer.getByLabel('Token 预算', { exact: true }).fill('64')
  if (budgetExperiment) {
    await drawer.getByLabel('Token 预算', { exact: true }).fill('1')
    if (budgetMode === 'candidate') {
      const skillEstimate = Number(
        requirementOptions.find((option) => String(option.definition_id) === definition)
          ?.skill_estimated_tokens
      )
      expect(skillEstimate).toBe(31)
      await expect(drawer.getByText(/技能入口估算/)).toContainText(String(skillEstimate))
      await expect(drawer.getByText(/不会加载/)).toBeVisible()
      await expect(drawer.getByText(/不会加载/)).toContainText('ALWAYS')
      await page.screenshot({
        path: join(evidence, 'skill-budget-low-warning.png'),
        fullPage: true
      })
      await drawer.getByLabel('Token 预算', { exact: true }).fill(String(skillEstimate))
      await expect(drawer.getByText(/不会加载/)).toHaveCount(0)
    }
  }
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
    join(evidence, 'mounted.json'),
    JSON.stringify({ agent, definition, version, mount }, null, 2)
  )
  await expect
    .poll(
      async () => {
        try {
          await access(join(evidence, 'initial-published'))
          return true
        } catch {
          return false
        }
      },
      { timeout: 60_000 }
    )
    .toBe(true)
  await page.reload()
  const unsavedPrompt = `UNSAVED_REMOVE_${marker}`
  if (candidate) {
    await page.getByRole('tab', { name: '基本配置', exact: true }).click()
    await page.getByLabel('系统提示词', { exact: true }).fill(unsavedPrompt)
  }
  await page.getByRole('tab', { name: /能力挂载/ }).click()
  const row = page
    .getByRole('row')
    .filter({ has: page.getByRole('button', { name: '移除', exact: true }) })
    .filter({ hasText: 'cross-tab-skill' })
  await expect(row).toHaveCount(1)
  let deletes = 0
  let serverDeletes = 0
  const deleteCommands: Array<{ body: unknown; ifMatch: string | undefined }> = []
  let complete!: () => void
  const deleted = new Promise<void>((resolve) => {
    complete = resolve
  })
  await page.route('**/api/v1/agents/capability-mounts/delete', async (route) => {
    deletes++
    deleteCommands.push({
      body: route.request().postDataJSON(),
      ifMatch: route.request().headers()['if-match']
    })
    if (notArrived && deletes === 1) {
      await writeFile(
        join(evidence, 'delete-not-dispatched.json'),
        JSON.stringify({ controlledPreDispatchAbort: true, ...deleteCommands[0] }, null, 2)
      )
      await route.abort('failed')
      complete()
      return
    }
    serverDeletes++
    const response = await route.fetch({ maxRetries: 0 })
    const body = await response.json()
    await writeFile(
      join(evidence, 'delete-http.json'),
      JSON.stringify(
        {
          method: route.request().method(),
          body: route.request().postDataJSON(),
          ifMatch: route.request().headers()['if-match'],
          status: response.status(),
          response: body
        },
        null,
        2
      )
    )
    expect(response.status()).toBe(200)
    if (notArrived) await route.fulfill({ response })
    else await route.abort('failed')
    complete()
  })
  await row.getByRole('button', { name: '移除', exact: true }).click()
  await page
    .getByRole('dialog', { name: '移除能力', exact: true })
    .getByRole('button', { name: '移除能力', exact: true })
    .click()
  await deleted
  await expect(page.getByRole('dialog', { name: '移除能力', exact: true })).toBeHidden()
  if (!candidate) await expect(row.getByRole('button', { name: '移除', exact: true })).toBeEnabled()
  else await expect(page.getByRole('button', { name: '核对挂载状态', exact: true })).toBeVisible()
  await expect.poll(() => page.locator('.el-loading-mask:visible').count()).toBe(0)
  await page.screenshot({ path: join(evidence, 'lost-response.png'), fullPage: true })
  await writeFile(join(evidence, 'lost-response-dom.txt'), await page.locator('body').innerText())
  const reread = page.getByRole('button', { name: '重新读取', exact: true })
  const state = {
    deletes,
    staleRowCount: await row.count(),
    explicitRereadVisible: await reread.isVisible(),
    explicitRereadCount: await reread.count()
  }
  const checkReads: Array<{ path: string; status: number; response: unknown }> = []
  if (candidate) {
    await expect(page.getByText(/原移除结果尚未确认/)).toBeVisible()
    const pendingReads: Promise<void>[] = []
    const capture = (response: import('@playwright/test').Response) => {
      const url = new URL(response.url())
      if (response.request().method() === 'GET' && url.pathname.startsWith('/api/v1/agents/'))
        pendingReads.push(
          response.json().then((body) => {
            checkReads.push({
              path: url.pathname + url.search,
              status: response.status(),
              response: body
            })
          })
        )
    }
    page.on('response', capture)
    await page.getByRole('button', { name: '核对挂载状态', exact: true }).click()
    if (notArrived) {
      await expect(row).toHaveCount(1)
      await expect(page.getByText(/原移除结果尚未确认/)).toBeVisible()
      await expect(row.getByRole('button', { name: '移除', exact: true })).toBeDisabled()
      await expect(page.getByRole('button', { name: '保存并发布', exact: true })).toBeDisabled()
    } else await expect(row).toHaveCount(0)
    await expect(page.getByText('有未保存修改', { exact: true })).toBeVisible()
    await page.getByRole('tab', { name: '基本配置', exact: true }).click()
    await expect(page.getByLabel('系统提示词', { exact: true })).toHaveValue(unsavedPrompt)
    await page.getByRole('tab', { name: /能力挂载/ }).click()
    if (notArrived) {
      await Promise.all(pendingReads)
      expect(
        checkReads.some((read) => {
          const body = read.response as {
            data?: { mounts?: Array<{ mount_id: string; revision: number }> }
          }
          return body.data?.mounts?.some(
            (current) =>
              String(current.mount_id) === String(mount.mount_id) &&
              current.revision === mount.revision
          )
        })
      ).toBe(true)
      await writeFile(
        join(evidence, 'same-revision-check-http.json'),
        JSON.stringify(checkReads, null, 2)
      )
      await writeFile(
        join(evidence, 'not-arrived-check.json'),
        JSON.stringify({ deletes, serverDeletes, mount }, null, 2)
      )
      await expect
        .poll(
          async () => {
            try {
              await access(join(evidence, 'not-arrived-database-checked'))
              return true
            } catch {
              return false
            }
          },
          { timeout: 60_000 }
        )
        .toBe(true)
      await page.reload()
      await page.getByRole('tab', { name: /能力挂载/ }).click()
      await expect(page.getByText(/原移除结果尚未确认/)).toBeVisible()
      await expect(row).toHaveCount(1)
      await expect(row.getByRole('button', { name: '移除', exact: true })).toBeDisabled()
      await expect(page.getByRole('button', { name: '发布', exact: true })).toBeDisabled()
      await page.screenshot({ path: join(evidence, 'unknown-after-reload.png'), fullPage: true })
      await page.getByRole('button', { name: '继续移除原挂载', exact: true }).click()
      const continueDialog = page.getByRole('dialog', { name: '继续移除原挂载', exact: true })
      await expect(continueDialog).toContainText('如果原请求尚未执行，此操作可能移除该挂载。')
      expect(deletes).toBe(1)
      expect(serverDeletes).toBe(0)
      await continueDialog.getByRole('button', { name: '继续移除原挂载', exact: true }).click()
      await expect(continueDialog).toBeHidden()
      await expect(row).toHaveCount(0)
      expect(deleteCommands).toHaveLength(2)
      expect(deleteCommands[1]).toEqual(deleteCommands[0])
      await writeFile(
        join(evidence, 'same-cas-commands.json'),
        JSON.stringify(deleteCommands, null, 2)
      )
    }
    await Promise.all(pendingReads)
    page.off('response', capture)
    expect(checkReads.length).toBeGreaterThan(0)
    await writeFile(
      join(evidence, 'check-current-state-http.json'),
      JSON.stringify(checkReads, null, 2)
    )
    await writeFile(join(evidence, 'checked-dom.txt'), await page.locator('body').innerText())
  } else {
    await page.reload()
    await page.getByRole('tab', { name: /能力挂载/ }).click()
  }
  await expect(page.getByRole('row').filter({ hasText: 'cross-tab-skill' })).toHaveCount(0)
  await page.screenshot({ path: join(evidence, 'after-reload.png'), fullPage: true })
  await writeFile(
    join(evidence, 'result.json'),
    JSON.stringify(
      {
        ...state,
        deletes,
        serverDeletes,
        removalMode,
        unsavedPromptPreserved: candidate,
        checkReads: checkReads.length,
        agent,
        definition,
        version,
        mount,
        afterReloadRowCount: 0
      },
      null,
      2
    )
  )
  expect(deletes).toBe(notArrived ? 2 : 1)
  expect(serverDeletes).toBe(1)
})
