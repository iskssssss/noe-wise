import { test, expect } from '@playwright/test'

for (const viewport of [
  { width: 1280, height: 900 },
  { width: 390, height: 844 }
]) {
  test(`登录及恢复使用单条顶部 Message ${viewport.width}`, async ({ page }, testInfo) => {
    await page.setViewportSize(viewport)
    let requests = 0
    await page.route('**/api/v1/**', async (route) => {
      requests++
      await route.fulfill({
        status: 503,
        json: {
          code: 'SERVICE_UNAVAILABLE',
          message: 'internal diagnostic',
          data: null,
          trace_id: 'feedback-browser'
        }
      })
    })
    await page.goto('/login')
    await page.getByLabel('机构标识', { exact: true }).fill('test-institution')
    await page.getByLabel('用户名', { exact: true }).fill('test-admin')
    await page.getByLabel('密码', { exact: true }).fill('synthetic-test-password')
    await page.getByRole('button', { name: '登录', exact: true }).click()
    const messages = page.locator('.el-message')
    await expect(messages).toHaveCount(1)
    await expect(messages).toContainText('暂时无法登录，请稍后重试')
    await expect(page.locator('.login-card .el-alert')).toHaveCount(0)
    expect((await messages.boundingBox())!.y).toBeLessThan(100)
    expect(requests).toBe(1)
    await page.getByRole('button', { name: '忘记密码或接受邀请' }).click()
    await page.getByRole('button', { name: '发送密码恢复邮件', exact: true }).click()
    await expect(messages.filter({ hasText: '先查看登记邮箱' })).toHaveCount(1)
    await expect(messages).toHaveCount(1)
    await expect(messages).toContainText('先查看登记邮箱')
    await expect(messages).not.toContainText('暂时无法登录')
    await expect(page.locator('.login-card .el-alert')).toHaveCount(0)
    expect(requests).toBe(2)
    const box = (await messages.boundingBox())!
    expect(box.x).toBeGreaterThanOrEqual(0)
    expect(box.x + box.width).toBeLessThanOrEqual(viewport.width)
    await page.screenshot({
      path: testInfo.outputPath('login-recovery-message.png'),
      fullPage: true
    })
    await messages.locator('.el-message__closeBtn').click()
    await expect(messages).toHaveCount(0)
    await page.emulateMedia({ colorScheme: 'dark' })
    await page.reload()
    await page.getByLabel('机构标识', { exact: true }).fill('test-institution')
    await page.getByLabel('用户名', { exact: true }).fill('test-admin')
    await page.getByLabel('密码', { exact: true }).fill('synthetic-test-password')
    await page.getByRole('button', { name: '登录', exact: true }).click()
    await expect(messages).toHaveCount(1)
    await expect(messages).not.toHaveClass(/el-message-fade-enter-active/)
    await page.screenshot({ path: testInfo.outputPath('login-message-dark.png'), fullPage: true })
  })
}
