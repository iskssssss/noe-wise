import { expect, test } from '@playwright/test'
import { mockConsole } from './support/mock-console'

test('已有候选时刷新团队成员，保留草稿和主智能体', async ({ page }) => {
  const fixture = await mockConsole(page, 'admin')
  let reads = 0
  fixture.setHook(async (_request, url) => {
    if (url.pathname === '/api/v1/teams') return { data: [] }
    if (url.pathname === '/api/v1/agents') {
      reads++
      const items = [{ ...fixture.agents[1], name: '订单助手', role_description: '查询订单' }]
      if (reads > 1)
        items.push({ ...fixture.agents[2], name: '售后助手', role_description: '处理售后' })
      return { data: { total: items.length, items } }
    }
    return undefined
  })
  await page.goto('/admin/teams')
  await page.getByRole('button', { name: '新增团队' }).click()
  const drawer = page.getByRole('dialog', { name: '新增团队' })
  await drawer.getByLabel('名称', { exact: true }).fill('客户服务团队')
  const members = drawer
    .locator('.el-form-item')
    .filter({ has: page.getByText('团队成员', { exact: true }) })
  await members.locator('.el-select__wrapper').click()
  await page.getByRole('option').filter({ hasText: '订单助手' }).click()
  const primary = drawer
    .locator('.el-form-item')
    .filter({ has: page.locator('label').getByText('主智能体', { exact: true }) })
  await expect(primary).toContainText(fixture.agents[1]!.id)
  await drawer.getByRole('button', { name: '刷新成员选项' }).click()
  await expect.poll(() => reads).toBe(2)
  await expect(drawer.getByLabel('名称', { exact: true })).toHaveValue('客户服务团队')
  await expect(primary).toContainText(fixture.agents[1]!.id)
  await members.locator('.el-select__wrapper').click()
  await expect(page.getByRole('option').filter({ hasText: '售后助手' })).toBeVisible()
  expect(fixture.requests.filter((request) => request.method !== 'GET')).toEqual([])
  fixture.assertHandled()
})
