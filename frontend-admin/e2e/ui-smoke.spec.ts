import { test, expect } from '@playwright/test'
import { captureUi, mockUiSmoke } from './support/ui-smoke'

test('机构 UI：列表、表单、工作台、共享设置与会话待办在多视口主题下可读可操作', async ({
  page,
  colorScheme
}, info) => {
  const fixture = await mockUiSmoke(page, 'admin', colorScheme === 'dark' ? 'dark' : 'light')
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  await page.goto('/admin/home')
  await expect(page.getByRole('heading', { name: '机构工作台', exact: true })).toBeVisible()
  await expect(page.locator('.admin-home-metrics .el-card')).toHaveCount(4)
  await captureUi(page, info, '01-home')
  await page.goto('/admin/agents')
  await expect(page.locator('.el-table__body tbody tr')).toHaveCount(47)
  await captureUi(page, info, '02-agents')
  await page.getByRole('button', { name: '新建智能体', exact: true }).click()
  const drawer = page.getByRole('dialog')
  await expect(drawer).toBeVisible()
  await drawer.getByRole('button', { name: '创建并配置', exact: true }).click()
  await expect(drawer.getByText('请输入智能体名称', { exact: true })).toBeVisible()
  await captureUi(page, info, '03-agent-validation')
  await drawer.getByRole('button', { name: '取消', exact: true }).click()
  await expect(drawer).toBeHidden()
  await page.goto('/admin/foundation/catalog')
  await expect(page.getByRole('heading', { name: '基础目录与设置', exact: true })).toBeVisible()
  await page.getByRole('tab', { name: '个人偏好', exact: true }).click()
  await captureUi(page, info, '04-shared-preferences')
  await page.goto('/admin/sessions')
  await expect(page.getByRole('button', { name: fixture.title, exact: true })).toBeVisible()
  await captureUi(page, info, '05-sessions')
  await page.getByRole('button', { name: fixture.title, exact: true }).click()
  await expect(page.getByRole('dialog')).toContainText('方案复核已完成')
  await captureUi(page, info, '06-session-detail')
  await page.getByRole('dialog').getByRole('button', { name: '关闭此对话框', exact: true }).click()
  await page.goto('/admin/chat')
  await page
    .locator('.el-select')
    .filter({ hasText: '历史会话' })
    .locator('.el-select__wrapper')
    .click()
  await page.getByRole('option', { name: fixture.title, exact: true }).click()
  await expect(page.getByRole('heading', { name: '请确认后续联系人', exact: true })).toBeVisible()
  await expect(page.locator('.debug-message').filter({ hasText: '方案复核已完成' })).toHaveCount(1)
  await captureUi(page, info, '07-chat-pending', page.locator('.pending-card'))
  await captureUi(
    page,
    info,
    '07-chat-answer',
    page.locator('.debug-message').filter({ hasText: '方案复核已完成' })
  )
  await page.goto(`/admin/chat/sessions/${fixture.session}/trace`)
  await page.getByRole('button', { name: /第 1 轮/ }).click()
  await expect(page.getByText('模型调用 · 已记录', { exact: true })).toBeVisible()
  await captureUi(page, info, '08-session-trace')
  expect(fixture.requests.filter((request) => request.method !== 'GET')).toEqual([])
  expect(errors).toEqual([])
  fixture.assertHandled()
})
