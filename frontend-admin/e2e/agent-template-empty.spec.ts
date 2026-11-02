import { expect, test } from '@playwright/test'
import { fullPermissions, mockConsole } from './support/mock-console'

test('模板为空时直接进入新建，抽屉焦点与关闭行为正常', async ({ page }) => {
  const fixture = await mockConsole(page, 'admin')
  fixture.setHook(async (_request, url) =>
    url.pathname === '/api/v1/agents/templates' ? { data: { total: 0, items: [] } } : undefined
  )
  await page.goto('/admin/agents')
  await page.getByRole('button', { name: '从模板安装' }).click()
  const template = page.getByRole('dialog', { name: '从模板安装' })
  await expect(template.getByText('暂无可安装模板')).toBeVisible()
  await template.getByRole('button', { name: '新建智能体' }).click()
  const create = page.getByRole('dialog', { name: '新建智能体' })
  await expect(create).toBeVisible()
  await expect(template).toBeHidden()
  await expect
    .poll(() => create.evaluate((element) => element.contains(document.activeElement)))
    .toBe(true)
  await create.getByLabel('名称').fill('订单助手')
  await expect(create.getByLabel('名称')).toHaveValue('订单助手')
  await create.getByLabel('名称').fill('')
  await page.keyboard.press('Escape')
  await expect(create).toBeHidden()
  expect(fixture.requests.filter((request) => request.method !== 'GET')).toEqual([])
  fixture.assertHandled()
})

test('没有创建权限时模板空态不提供新建入口', async ({ page }) => {
  const fixture = await mockConsole(
    page,
    'admin',
    fullPermissions.filter((value) => value !== 'foundation.agents.create')
  )
  fixture.setHook(async (_request, url) =>
    url.pathname === '/api/v1/agents/templates' ? { data: { total: 0, items: [] } } : undefined
  )
  await page.goto('/admin/agents')
  await page.getByRole('button', { name: '从模板安装' }).click()
  const template = page.getByRole('dialog', { name: '从模板安装' })
  await expect(template.getByText('暂无可安装模板')).toBeVisible()
  await expect(template.getByRole('button', { name: '新建智能体' })).toHaveCount(0)
  expect(fixture.requests.filter((request) => request.method !== 'GET')).toEqual([])
  fixture.assertHandled()
})
