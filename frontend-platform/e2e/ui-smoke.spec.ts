import { test, expect } from '@playwright/test'
import { captureUi, mockUiSmoke } from '../../frontend-admin/e2e/support/ui-smoke'

test('平台 UI：列表、表单、工作台、共享设置与受控会话在多视口主题下可读可操作', async ({
  page,
  colorScheme
}, info) => {
  const fixture = await mockUiSmoke(page, 'platform', colorScheme === 'dark' ? 'dark' : 'light')
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  await page.goto('/platform/home')
  await expect(page.getByRole('heading', { name: '工作台', exact: true })).toBeVisible()
  await captureUi(page, info, '01-home')
  await page.goto('/platform/tenants')
  await expect(page.locator('.el-table__body tbody tr')).toHaveCount(20)
  await captureUi(page, info, '02-tenants')
  await page.goto('/platform/models')
  await expect(page.locator('.el-table__body tbody tr')).toHaveCount(37)
  await captureUi(page, info, '02-models')
  await page.getByRole('button', { name: '新增模型', exact: true }).click()
  await expect(page.getByRole('heading', { name: '新建模型', exact: true })).toBeVisible()
  await page.locator('form').getByRole('button', { name: '保存草稿', exact: true }).click()
  await expect(page.getByText('请填写名称、提供方和模型标识', { exact: true })).toBeVisible()
  await captureUi(page, info, '03-model-validation')
  await page.goto('/platform/models/model-0')
  await expect(
    page.getByRole('heading', { name: fixture.models[0]!.name, exact: true })
  ).toBeVisible()
  await captureUi(page, info, '03-model-detail')
  await page.goto('/platform/foundation/catalog')
  await expect(page.getByRole('heading', { name: '基础目录与设置', exact: true })).toBeVisible()
  await expect(page.getByRole('tab', { name: '个人偏好', exact: true })).toBeVisible()
  await captureUi(page, info, '04-shared-preferences')
  await page.goto('/platform/sessions')
  const filter = page.getByRole('button', { name: '精确筛选', exact: true })
  await expect(filter).toHaveAttribute('aria-expanded', 'false')
  await expect(page.getByRole('link', { name: fixture.title, exact: true })).toBeVisible()
  await captureUi(page, info, '05-sessions')
  await filter.click()
  await expect(page.getByPlaceholder('会话 ID', { exact: true })).toBeVisible()
  await captureUi(page, info, '06-session-filters')
  await page.getByRole('link', { name: fixture.title, exact: true }).click()
  await expect(page.getByRole('heading', { name: '会话详情', exact: true })).toBeVisible()
  await expect(page.locator('[data-message-id="answer"]')).toContainText('方案复核已完成')
  await expect(page.locator('.public-commentary')).not.toHaveAttribute('open')
  await expect(page.locator('[data-attempt-id="attempt-ui-1"]')).toBeVisible()
  await captureUi(page, info, '07-session-answer', page.locator('[data-message-id="answer"]'))
  await page.getByRole('tab', { name: 'Token 用量', exact: true }).click()
  await expect(
    page
      .getByRole('tabpanel', { name: 'Token 用量', exact: true })
      .getByText('9,007,199,254,740,993', { exact: true })
      .first()
  ).toBeVisible()
  await captureUi(
    page,
    info,
    '08-session-usage',
    page.getByRole('tabpanel', { name: 'Token 用量', exact: true })
  )
  expect(fixture.requests.filter((request) => request.method !== 'GET')).toEqual([])
  expect(
    fixture.requests.some(
      (request) =>
        request.path.includes('model-attempt/detail') || request.path.includes('trace/content')
    )
  ).toBe(false)
  expect(errors).toEqual([])
  fixture.assertHandled()
})
