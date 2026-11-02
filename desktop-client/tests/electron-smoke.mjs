import assert from 'node:assert/strict'
import { mkdtemp, mkdir, rm } from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { _electron as electron } from 'playwright'
import executablePath from 'electron'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const temporary = await mkdtemp(path.join(os.tmpdir(), 'noewise-electron-'))
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
  await page.getByText('本地 Agent 已就绪', { exact: true }).waitFor()
  const security = await application.evaluate(({ BrowserWindow }) => {
    const web = BrowserWindow.getAllWindows()[0].webContents.getLastWebPreferences()
    return {
      nodeIntegration: web.nodeIntegration,
      contextIsolation: web.contextIsolation,
      sandbox: web.sandbox
    }
  })
  assert.deepEqual(security, { nodeIntegration: false, contextIsolation: true, sandbox: true })
  assert.equal(await page.evaluate(() => typeof window.require), 'undefined')
  await page.screenshot({ path: path.join(root, 'test-results', 'welcome.png') })
  await page.getByRole('button', { name: /模型设置/ }).click()
  await page.getByLabel('API Key', { exact: true }).fill('fixture-key-not-real')
  await page.getByRole('button', { name: '保存配置', exact: true }).click()
  await page.getByText(/配置已保存/).waitFor()
  await page.getByRole('button', { name: '测试当前配置' }).click()
  await page.getByText('连接成功，模型返回了有效响应。').waitFor()
  await page.getByLabel('API Key', { exact: true }).fill('bad-key')
  await page.getByRole('button', { name: '保存配置', exact: true }).click()
  await page.getByRole('button', { name: '测试当前配置' }).click()
  await page.getByRole('alert').filter({ hasText: 'AUTH_REJECTED: 模型鉴权失败' }).waitFor()
  assert.equal(await page.getByText('连接成功，模型返回了有效响应。').count(), 0)
  await page.getByLabel('API Key', { exact: true }).fill('unknown-key')
  await page.getByRole('button', { name: '保存配置', exact: true }).click()
  await page.getByRole('button', { name: '测试当前配置' }).click()
  await page.getByRole('alert').filter({ hasText: 'UNKNOWN: 模型结果未知' }).waitFor()
  assert.equal(await page.getByText('连接成功，模型返回了有效响应。').count(), 0)
  await page.getByLabel('API Key', { exact: true }).fill('fixture-key-not-real')
  await page.getByRole('button', { name: '保存配置', exact: true }).click()
  await page.getByText(/配置已保存/).waitFor()
  await page.getByRole('button', { name: '关闭模型设置' }).click()
  await page.getByRole('button', { name: /选择目录/ }).click()
  await page.getByRole('button', { name: /创建会话，开始工作/ }).click()
  await page.getByRole('textbox', { name: '任务内容' }).fill('修复示例问题并运行测试')
  await page.getByRole('button', { name: '发送任务' }).click()
  await page.getByText('确认执行命令', { exact: true }).waitFor()
  await page.screenshot({ path: path.join(root, 'test-results', 'confirmation.png') })
  await page.getByRole('button', { name: '允许这次执行' }).click()
  await page.getByText('已修复边界条件，测试通过。', { exact: true }).waitFor()
  await page
    .locator('.tool-card')
    .filter({ hasText: 'command_execute' })
    .locator('summary')
    .first()
    .click()
  await page.getByText('标准输出', { exact: true }).waitFor()
  assert.equal(await page.getByText('退出码 0', { exact: true }).isVisible(), true)
  await page.getByRole('tab', { name: /文件变更/ }).click()
  await page.getByText('文件差异', { exact: true }).waitFor()
  await page.screenshot({ path: path.join(root, 'test-results', 'changes.png') })
  await page.setViewportSize({ width: 1000, height: 700 })
  assert.equal(
    await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth),
    true
  )
  await page.screenshot({ path: path.join(root, 'test-results', 'compact.png') })
  await page.getByRole('textbox', { name: '任务内容' }).fill('保留在原会话的草稿')
  await page.getByRole('button', { name: /新建会话/ }).click()
  await page.getByRole('textbox', { name: '任务内容' }).fill('取消这个任务')
  await page.getByRole('button', { name: '发送任务' }).click()
  await page.getByText('确认执行命令', { exact: true }).waitFor()
  await page.getByRole('button', { name: /^停止任务 / }).click()
  await page.locator('.session-heading .run-badge').filter({ hasText: '已取消' }).waitFor()
  assert.equal(await page.getByText('确认执行命令', { exact: true }).count(), 0)
  await page.getByRole('textbox', { name: '任务内容' }).fill('拒绝执行命令')
  await page.getByRole('button', { name: '发送任务' }).click()
  await page.getByRole('button', { name: '拒绝', exact: true }).click()
  await page.getByText('已拒绝执行命令。', { exact: true }).waitFor()
  await page.getByRole('textbox', { name: '任务内容' }).fill('命令失败演示')
  await page.getByRole('button', { name: '发送任务' }).click()
  await page.getByRole('button', { name: '允许这次执行' }).click()
  await page.getByText('命令退出码为 7，已保留失败输出。', { exact: true }).waitFor()
  await page.getByRole('button', { name: '执行记录与变更', exact: true }).click()
  await page.getByRole('tab', { name: /执行记录/ }).click()
  const failedTool = page
    .locator('.tool-card')
    .filter({ hasText: 'command_execute' })
    .filter({ has: page.locator('.tool-status.failed') })
  await failedTool.locator('summary').first().click()
  await failedTool.getByText('退出码 7', { exact: true }).waitFor()
  await page
    .getByText('部分历史与输出已省略展示，完整记录仍保存在本机。', { exact: true })
    .waitFor()
  await failedTool
    .getByText('此工具的部分展示内容已省略，完整持久记录未被修改。', { exact: true })
    .waitFor()
  await page.getByRole('button', { name: '关闭执行记录', exact: true }).click()
  await page.getByRole('textbox', { name: '任务内容' }).fill('受控拒绝')
  await page.getByRole('button', { name: '发送任务' }).click()
  await page
    .getByRole('alert')
    .filter({ hasText: 'TASK_REJECTED: 受控请求拒绝，输入应保留' })
    .waitFor()
  assert.equal(await page.getByRole('textbox', { name: '任务内容' }).inputValue(), '受控拒绝')
  await page.locator('.session-item').nth(1).click()
  assert.equal(
    await page.getByRole('textbox', { name: '任务内容' }).inputValue(),
    '保留在原会话的草稿'
  )
  await page.getByRole('textbox', { name: '任务内容' }).fill('这个输入在断连后应该保留')
  await application.evaluate(({ BrowserWindow }) => {
    const win = BrowserWindow.getAllWindows()[0]
    win.webContents.send('desktop:event', {
      event: 'host-status',
      data: { status: 'failed', message: '受控断连' }
    })
  })
  await page.getByText('Agent 连接中断', { exact: true }).waitFor()
  assert.equal(
    await page.getByRole('textbox', { name: '任务内容' }).inputValue(),
    '这个输入在断连后应该保留'
  )
  assert.equal(await page.getByRole('button', { name: '发送任务' }).isDisabled(), true)
  console.log(
    'PASS: Electron 沙箱、配置、目录选择、任务、确认/拒绝/取消、diff、长输出、窄窗口、会话草稿与失败/断连保留输入（受控 Host）'
  )
} catch (error) {
  if (application) {
    const page = await application.firstWindow().catch(() => null)
    if (page)
      await page
        .screenshot({ path: path.join(root, 'test-results', 'failure.png') })
        .catch(() => {})
  }
  throw error
} finally {
  if (application) await application.close()
  await rm(temporary, { recursive: true, force: true })
}
