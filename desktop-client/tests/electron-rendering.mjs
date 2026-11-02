import assert from 'node:assert/strict'
import { mkdtemp, mkdir, rm } from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { _electron as electron } from 'playwright'
import executablePath from 'electron'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const temporary = await mkdtemp(path.join(os.tmpdir(), 'noewise-rendering-'))
const workspace = path.join(temporary, 'sample-project')
await mkdir(workspace)
await mkdir(path.join(root, 'test-results'), { recursive: true })
let application
try {
  application = await electron.launch({
    executablePath,
    args: [path.join(root, 'tests', 'fixtures', 'electron-entry.cjs')],
    env: {
      ...process.env,
      NOEWISE_TEST_DATA: path.join(temporary, 'app-data'),
      NOEWISE_TEST_NODE: process.execPath,
      NOEWISE_TEST_WORKSPACE: workspace
    },
    timeout: 30000
  })
  const page = await application.firstWindow()
  const externalRequests = []
  page.on('request', (request) => {
    if (request.url().startsWith('https://attacker.invalid')) externalRequests.push(request.url())
  })
  await page.getByText('本地 Agent 已就绪', { exact: true }).waitFor()
  await page.getByRole('button', { name: /模型设置/ }).click()
  await page.getByLabel('API Key', { exact: true }).fill('rendering-fixture-key-not-real')
  await page.getByRole('button', { name: '保存配置', exact: true }).click()
  await page.getByText(/配置已保存/).waitFor()
  await page.getByRole('button', { name: '关闭模型设置' }).click()
  await page.getByRole('button', { name: /创建会话，开始工作/ }).click()
  await page.getByRole('textbox', { name: '任务内容' }).fill('Markdown展示 **用户原文**')
  await page.getByRole('button', { name: '发送任务' }).click()
  const rendered = page.locator('.markdown-message')
  await rendered.getByRole('heading', { name: '项目说明', level: 1 }).waitFor()
  assert.equal(await rendered.getByRole('heading', { name: '验证步骤', level: 2 }).count(), 1)
  assert.equal(await rendered.locator('ol li').count(), 2)
  assert.equal(await rendered.locator('ul li').count(), 2)
  assert.match(await rendered.locator('pre code').innerText(), /<script>/)
  assert.equal(await rendered.locator('img,script,style,iframe,form,input,svg,math').count(), 0)
  assert.equal(await rendered.locator('a').count(), 1)
  assert.equal(await page.evaluate(() => window.__markdownXss), undefined)
  assert.equal(
    await page.locator('body').evaluate((element) => getComputedStyle(element).display === 'none'),
    false
  )
  assert.deepEqual(externalRequests, [])
  assert.match(await page.locator('.message.user .message-text').innerText(), /\*\*用户原文\*\*/)
  const originalPage = page.url()
  await rendered.getByRole('link', { name: '外部文档' }).click()
  const opened = await application.evaluate(() => globalThis.__openedExternalUrls)
  assert.deepEqual(opened, ['https://example.com/docs?q=1#intro'])
  assert.equal(page.url(), originalPage)
  await rendered.getByRole('link', { name: '外部文档' }).evaluate((element) => element.click())
  assert.deepEqual(await application.evaluate(() => globalThis.__openedExternalUrls), opened)
  const direct = await page.evaluate(() =>
    window.desktop.openExternal('https://example.com/without-click')
  )
  assert.equal(direct.ok, false)
  await page.getByRole('button', { name: '关闭错误提示' }).click()
  await rendered.screenshot({ path: path.join(root, 'test-results', 'markdown-0.1.5.png') })
  await page.setViewportSize({ width: 1000, height: 700 })
  assert.equal(
    await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth),
    true
  )
  assert.equal(
    await rendered.locator('pre').evaluate((element) => element.scrollWidth > element.clientWidth),
    true
  )
  await page.screenshot({ path: path.join(root, 'test-results', 'markdown-0.1.5-compact.png') })

  async function newTask(text) {
    await page.getByRole('button', { name: /新建会话/ }).click()
    await page.getByText('从一个具体任务开始', { exact: true }).waitFor()
    await page.getByRole('textbox', { name: '任务内容' }).fill(text)
    await page.getByRole('button', { name: '发送任务' }).click()
    await page
      .locator('.outcome-notice')
      .getByText('模型输出达到长度上限', { exact: true })
      .waitFor()
  }
  await newTask('输出截断无工具')
  await page.getByText('此任务未记录本地工具调用。', { exact: true }).waitFor()
  await page
    .getByText(
      '可以按文件或功能拆成小任务，或在模型设置中调整单次最大输出 token，再明确提交下一步。应用不会自动重试。',
      {
        exact: true
      }
    )
    .waitFor()
  await newTask('输出截断有工具')
  await page
    .getByText('该任务此前已有工具调用，已发生的操作仍保留，请先核对执行记录和文件。', {
      exact: true
    })
    .waitFor()
  assert.equal(await page.getByText('此任务未记录本地工具调用。', { exact: true }).count(), 0)
  await page.getByRole('button', { name: '执行记录与变更', exact: true }).click()
  await page.getByRole('tab', { name: /文件变更/ }).click()
  await page.getByText('文件差异', { exact: true }).waitFor()
  await page.screenshot({ path: path.join(root, 'test-results', 'output-length-0.1.5.png') })
  await page.getByRole('button', { name: '关闭执行记录', exact: true }).click()
  await page.locator('.session-item').nth(1).click()
  await page.getByText('此任务未记录本地工具调用。', { exact: true }).waitFor()
  assert.equal(await page.locator('.outcome-notice').count(), 1)

  await page.getByRole('button', { name: /新建会话/ }).click()
  await page.getByRole('textbox', { name: '任务内容' }).fill('旧版历史演示')
  await page.getByRole('button', { name: '发送任务' }).click()
  await page.getByText('历史会话仅可查看', { exact: true }).waitFor()
  await page.getByText('原有回答和执行记录仍保留。', { exact: true }).waitFor()
  await page
    .getByText('此会话使用旧版执行协议，历史记录和文件变更仍保留。请新建会话开始任务。', {
      exact: true
    })
    .waitFor()
  await page.getByRole('textbox', { name: '任务内容' }).fill('这份草稿应保留')
  assert.equal(await page.getByRole('button', { name: '发送任务' }).isDisabled(), true)
  assert.equal(await page.getByRole('button', { name: '停止', exact: true }).count(), 0)
  assert.equal(await page.locator('.working-indicator').count(), 0)
  await page.screenshot({ path: path.join(root, 'test-results', 'old-run-readonly-0.1.5.png') })
  console.log(
    'PASS: Markdown/安全链接/窄窗口、截断失败反馈和工具事实、旧版会话只读及恢复阻断（受控Host，无真实模型调用）'
  )
} catch (error) {
  if (application) {
    const page = await application.firstWindow().catch(() => null)
    if (page)
      await page
        .screenshot({ path: path.join(root, 'test-results', 'rendering-failure.png') })
        .catch(() => {})
  }
  throw error
} finally {
  if (application) await application.close()
  await rm(temporary, { recursive: true, force: true })
}
