import assert from 'node:assert/strict'
import { mkdtemp, mkdir, rm, writeFile } from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { _electron as electron } from 'playwright'
import executablePath from 'electron'

// Real Electron main/preload and renderer, isolated controlled Host; no cloud calls.
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const temporary = await mkdtemp(path.join(os.tmpdir(), 'noewise-ui-ux-'))
const workspace = path.join(temporary, '长目录与中文项目名称', 'client-ui-accessibility-example')
const evidence = path.join(root, 'test-results', `ui-ux-${Date.now()}`)
await mkdir(workspace, { recursive: true })
await mkdir(evidence, { recursive: true })
const measurements = []
let application
let page
let stage = 'launch'

async function currentFocus(locator, label) {
  const element = await locator.elementHandle()
  try {
    await page.waitForFunction((expected) => expected === document.activeElement, element)
    assert.equal(await locator.evaluate((target) => target === document.activeElement), true, label)
  } finally {
    await element?.dispose()
  }
}
async function checkpoint(label) {
  await page.screenshot({ path: path.join(evidence, `${label}.png`) })
}
async function layout(label) {
  const measurement = await page.evaluate(() => {
    const visible = (element) =>
      element.getClientRects().length > 0 && getComputedStyle(element).visibility !== 'hidden'
    const smallTargets = [...document.querySelectorAll('button, input, select, textarea, summary')]
      .filter(visible)
      .flatMap((element) => {
        const box = element.getBoundingClientRect()
        return box.width < 24 || box.height < 24
          ? [
              {
                name: element.getAttribute('aria-label') || element.textContent.trim(),
                width: box.width,
                height: box.height
              }
            ]
          : []
      })
    const smallText = [...document.querySelectorAll('body *')]
      .filter(visible)
      .filter((element) => !element.closest('[aria-hidden="true"]'))
      .filter((element) =>
        [...element.childNodes].some(
          (node) => node.nodeType === Node.TEXT_NODE && node.textContent.trim()
        )
      )
      .flatMap((element) =>
        parseFloat(getComputedStyle(element).fontSize) < 12
          ? [
              {
                text: element.textContent.trim().slice(0, 70),
                size: getComputedStyle(element).fontSize
              }
            ]
          : []
      )
    return {
      width: innerWidth,
      height: innerHeight,
      scrollWidth: document.documentElement.scrollWidth,
      bodyWidth: document.body.scrollWidth,
      smallTargets,
      smallText
    }
  })
  measurements.push({ label, ...measurement })
  assert.ok(measurement.scrollWidth <= measurement.width + 1, `${label}: page horizontal overflow`)
  assert.ok(measurement.bodyWidth <= measurement.width + 1, `${label}: body horizontal overflow`)
  assert.deepEqual(measurement.smallTargets, [], `${label}: targets below 24 CSS px`)
  assert.deepEqual(measurement.smallText, [], `${label}: text below 12 CSS px`)
}
async function reachable(locator, label) {
  await locator.scrollIntoViewIfNeeded()
  assert.equal(await locator.isVisible(), true, `${label}: visible`)
  const box = await locator.evaluate((element) => {
    const rect = element.getBoundingClientRect()
    return { x: rect.x, y: rect.y, width: rect.width, height: rect.height }
  })
  const viewport = await page.evaluate(() => ({ width: innerWidth, height: innerHeight }))
  assert.ok(
    box &&
      box.x >= -1 &&
      box.y >= -1 &&
      box.x + box.width <= viewport.width + 1 &&
      box.y + box.height <= viewport.height + 1,
    `${label}: inside viewport ${JSON.stringify({ box, viewport })}`
  )
}
async function setWidth(width) {
  await page.setViewportSize({ width, height: 820 })
  await page.waitForFunction((expected) => innerWidth === expected, width)
}
async function openNavigation() {
  const opener = page.getByRole('button', { name: '打开会话导航', exact: true })
  await opener.click()
  await page.locator('.sidebar-dialog').waitFor({ state: 'visible' })
  assert.equal(
    await page.locator('.sidebar-dialog').evaluate((element) => element.matches(':modal')),
    true
  )
  return opener
}
async function createAnotherSession() {
  await page.getByRole('button', { name: /新建会话/ }).click()
  await page.getByText('从一个具体任务开始', { exact: true }).waitFor()
}

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
  page = await application.firstWindow()
  page.setDefaultTimeout(10000)
  await page.getByText('本地 Agent 已就绪', { exact: true }).waitFor()
  stage = 'theme persistence'
  await setWidth(1380)
  assert.equal(await page.locator('html').getAttribute('data-theme'), 'dark')
  await layout('wide-dark-welcome')
  await checkpoint('wide-dark-welcome')
  await page.getByRole('button', { name: '切换浅色主题', exact: true }).click()
  assert.equal(await page.locator('html').getAttribute('data-theme'), 'light')
  await page.reload()
  await page.getByText('本地 Agent 已就绪', { exact: true }).waitFor()
  assert.equal(await page.locator('html').getAttribute('data-theme'), 'light')
  await layout('wide-light-welcome')
  await checkpoint('wide-light-welcome')
  await page.getByRole('button', { name: '切换深色主题', exact: true }).click()
  await page.reload()
  await page.getByText('本地 Agent 已就绪', { exact: true }).waitFor()
  assert.equal(await page.locator('html').getAttribute('data-theme'), 'dark')

  stage = 'model settings and dirty close'
  const settingsTrigger = page.getByRole('button', { name: /模型设置/ })
  await settingsTrigger.click()
  const settings = page.locator('.settings-dialog')
  await settings.waitFor({ state: 'visible' })
  assert.equal(
    await settings.evaluate(
      (element) => element.matches(':modal') && element.contains(document.activeElement)
    ),
    true
  )
  for (const control of ['input[type="url"]', 'input[type="number"]', 'input[type="password"]']) {
    assert.equal(
      await settings
        .locator(control)
        .evaluate((element) =>
          (element.getAttribute('aria-describedby') || '')
            .split(/\s+/)
            .some((id) => document.getElementById(id)?.textContent.trim())
        ),
      true
    )
  }
  await page.getByLabel('API Key', { exact: true }).fill('ui-ux-fixture-key-not-real')
  const dismissed = page.waitForEvent('dialog')
  const firstEscape = page.keyboard.press('Escape')
  const dismissDialog = await dismissed
  assert.equal(dismissDialog.type(), 'confirm')
  await dismissDialog.dismiss()
  await firstEscape
  assert.equal(await settings.isVisible(), true)
  assert.equal(
    await page.getByLabel('API Key', { exact: true }).inputValue(),
    'ui-ux-fixture-key-not-real'
  )
  const accepted = page.waitForEvent('dialog')
  const secondEscape = page.keyboard.press('Escape')
  await (await accepted).accept()
  await secondEscape
  await settings.waitFor({ state: 'hidden' })
  await currentFocus(settingsTrigger, 'model close restores trigger')
  await settingsTrigger.click()
  assert.equal(await page.getByLabel('API Key', { exact: true }).inputValue(), '')
  await page.getByLabel('API Key', { exact: true }).fill('ui-ux-fixture-key-not-real')
  await page.getByRole('button', { name: '保存配置', exact: true }).click()
  await page.getByText(/配置已保存/).waitFor()
  await checkpoint('model-settings')
  await page.keyboard.press('Escape')
  await settings.waitFor({ state: 'hidden' })
  await currentFocus(settingsTrigger, 'saved settings Esc restores trigger')
  await page.getByRole('button', { name: /创建会话，开始工作/ }).click()
  const task = page.getByRole('textbox', { name: '任务内容' })
  await task.fill('UI阅读演示')
  await page.getByRole('button', { name: '发送任务', exact: true }).click()
  await page.waitForFunction(() => document.querySelectorAll('.message.assistant').length === 44)
  const sessions = await page.evaluate(() => window.desktop.listSessions())
  assert.equal(sessions.ok, true)
  const firstSessionId = sessions.value[0].id

  stage = 'history scroll preservation'
  const messages = page.locator('.messages')
  await messages.evaluate((element) => {
    element.scrollTop = 360
  })
  await page.getByRole('button', { name: /回到最新/ }).waitFor()
  const readingTop = await messages.evaluate((element) => element.scrollTop)
  const appended = await page.evaluate(
    (sessionId) =>
      window.desktop.submitTask({
        sessionId,
        inputId: crypto.randomUUID(),
        text: 'UI阅读演示追加'
      }),
    firstSessionId
  )
  assert.equal(appended.ok, true)
  await page.waitForFunction(() => document.querySelectorAll('.message.assistant').length === 46)
  assert.ok(
    Math.abs((await messages.evaluate((element) => element.scrollTop)) - readingTop) <= 2,
    'new state preserves reading position'
  )
  await page.getByRole('button', { name: /有新回复.*回到最新/ }).waitFor()
  await createAnotherSession()
  await page.locator('.session-item').nth(1).click()
  await page.waitForFunction(() => document.querySelectorAll('.message.assistant').length === 46)
  assert.ok(
    Math.abs((await messages.evaluate((element) => element.scrollTop)) - readingTop) <= 2,
    'switching sessions restores reading position: ' +
      JSON.stringify({
        expected: readingTop,
        actual: await messages.evaluate((element) => ({
          top: element.scrollTop,
          height: element.scrollHeight,
          viewport: element.clientHeight
        })),
        jumpVisible: await page.locator('.jump-to-latest').isVisible()
      })
  )
  await page.getByRole('button', { name: /回到最新/ }).click()
  await page.waitForFunction(() => {
    const element = document.querySelector('.messages')
    return element.scrollHeight - element.scrollTop - element.clientHeight <= 48
  })

  stage = 'IME does not submit'
  await task.fill('中文候选词不应发送')
  const beforeIme = await page.locator('.message.user').count()
  await task.dispatchEvent('keydown', {
    key: 'Enter',
    code: 'Enter',
    ctrlKey: true,
    isComposing: true,
    keyCode: 229,
    bubbles: true
  })
  assert.equal(await task.inputValue(), '中文候选词不应发送')
  assert.equal(await page.locator('.message.user').count(), beforeIme)

  stage = 'responsive panels and keyboard'
  for (const width of [1380, 1180, 1179, 1000, 760, 759, 640, 320]) {
    await setWidth(width)
    await layout(`conversation-${width}`)
    assert.equal(await task.inputValue(), '中文候选词不应发送', 'draft survives responsive changes')
    await reachable(task, `${width}: composer`)
    await reachable(page.getByRole('button', { name: '发送任务', exact: true }), `${width}: send`)
    if (width < 760) {
      const opener = await openNavigation()
      await reachable(
        page.getByRole('button', { name: '关闭会话导航', exact: true }),
        `${width}: close navigation`
      )
      assert.equal(
        await page
          .locator('.sidebar-dialog')
          .evaluate((element) => element.contains(document.activeElement)),
        true
      )
      await layout(`navigation-${width}`)
      await page.keyboard.press('Escape')
      await page.locator('.sidebar-dialog').waitFor({ state: 'hidden' })
      await currentFocus(opener, `${width}: navigation Esc restores focus`)
    }
    if (width < 1180) {
      const opener = page.getByRole('button', { name: '执行记录与变更', exact: true })
      await opener.click()
      const activity = page.locator('.activity-dialog')
      assert.equal(
        await activity.evaluate(
          (element) => element.matches(':modal') && element.contains(document.activeElement)
        ),
        true,
        JSON.stringify(
          await activity.evaluate((element) => ({
            width: innerWidth,
            viewport: visualViewport.width,
            dpr: devicePixelRatio,
            media: matchMedia('(width < 1180px)').matches,
            open: element.open,
            modal: element.matches(':modal'),
            focus: document.activeElement?.outerHTML.slice(0, 300)
          }))
        )
      )
      await reachable(
        page.getByRole('button', { name: '关闭执行记录', exact: true }),
        `${width}: close details`
      )
      const tabs = activity.getByRole('tab')
      await tabs.nth(0).focus()
      for (const [key, index] of [
        ['ArrowRight', 1],
        ['Home', 0],
        ['End', 1],
        ['ArrowLeft', 0]
      ]) {
        await page.keyboard.press(key)
        await currentFocus(tabs.nth(index), `${width}: ${key} moves selected tab focus`)
        assert.equal(await tabs.nth(index).getAttribute('aria-selected'), 'true')
      }
      await layout(`details-${width}`)
      if ([1000, 320].includes(width)) await checkpoint(`details-${width}`)
      await page.keyboard.press('Escape')
      await activity.waitFor({ state: 'hidden' })
      await currentFocus(opener, `${width}: details Esc restores focus`)
    }
    if ([1380, 1000, 640, 320].includes(width)) await checkpoint(`conversation-${width}`)
  }

  stage = '320 settings and full workspace'
  await page.locator('.workspace-location summary').click()
  assert.equal(await page.locator('.workspace-location code').innerText(), workspace)
  await layout('workspace-expanded-320')
  await openNavigation()
  await page.getByRole('button', { name: /模型设置/ }).click()
  await settings.waitFor({ state: 'visible' })
  await reachable(
    page.getByRole('button', { name: '关闭模型设置', exact: true }),
    '320: close settings'
  )
  await reachable(page.getByRole('button', { name: '保存配置', exact: true }), '320: save settings')
  await layout('settings-320')
  await checkpoint('settings-320')
  await page.keyboard.press('Escape')
  await settings.waitFor({ state: 'hidden' })
  await currentFocus(
    page.getByRole('button', { name: '打开会话导航', exact: true }),
    'hidden settings trigger returns to navigation'
  )
  await page.getByRole('button', { name: '切换浅色主题', exact: true }).click()
  await layout('conversation-light-320')
  await checkpoint('conversation-light-320')
  await setWidth(1380)
  await layout('conversation-light-wide')
  await checkpoint('conversation-light-wide')
  stage = '200 percent zoom at compact window height'
  await page.setViewportSize({ width: 1000, height: 650 })
  const zoomDraft = await task.inputValue()
  await application.evaluate(({ BrowserWindow }) =>
    BrowserWindow.getAllWindows()[0].webContents.setZoomFactor(2)
  )
  await page.waitForFunction(() => innerWidth < 760)
  await layout('zoom-200-percent')
  await reachable(task, '200 percent zoom: composer')
  await reachable(
    page.getByRole('button', { name: '发送任务', exact: true }),
    '200 percent zoom: send'
  )
  assert.equal(await task.inputValue(), zoomDraft)
  await checkpoint('zoom-200-percent')
  await application.evaluate(({ BrowserWindow }) =>
    BrowserWindow.getAllWindows()[0].webContents.setZoomFactor(1)
  )
  await writeFile(
    path.join(evidence, 'acceptance.json'),
    JSON.stringify(
      {
        status: 'PASS',
        source: 'Real Electron, production main/preload, controlled protocol Host; no cloud model',
        widths: [1380, 1180, 1179, 1000, 760, 759, 640, 320],
        measurements
      },
      null,
      2
    )
  )
  console.log(
    `PASS: themes/reload, responsive dialogs/focus/tabs, settings dirty protection, scroll restoration, IME; ${evidence}`
  )
} catch (error) {
  await page?.screenshot({ path: path.join(evidence, 'failure.png') }).catch(() => {})
  await writeFile(
    path.join(evidence, 'acceptance.json'),
    JSON.stringify({ status: 'FAILED', stage, error: String(error), measurements }, null, 2)
  )
  throw error
} finally {
  if (application) await application.close()
  await rm(temporary, { recursive: true, force: true })
}
