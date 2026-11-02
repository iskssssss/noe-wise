import { trajectoryBrowserCases } from './support/trajectory-browser-cases'

trajectoryBrowserCases('admin')

import { test, expect } from '@playwright/test'
import { mockTrajectory } from './support/trajectory-fixture'

test('轮次列表失败时仍可从深链查看指定执行节点', async ({ page }) => {
  const fixture = await mockTrajectory(page, 'admin')
  await page.route(/\/api\/v1\/chat\/trajectories\?/, async (route) => {
    await route.fulfill({ status: 503, json: { code: 'TEMPORARY', message: '轮次列表不可用' } })
  })
  await page.goto('/admin/trajectories/detail?session_id=observed-session&root_input_id=observed-input&node_id=llm')
  await expect(page.getByRole('region', { name: '当前执行节点详情' })).toContainText('observed-model')
  await expect(page.getByRole('complementary', { name: '业务轮次' })).toContainText('尚未读取')
  await expect(page.getByText('本会话尚无轨迹记录')).toHaveCount(0)
  expect(fixture.requests.filter((item) => item.method !== 'GET')).toEqual([])
  fixture.assertHandled()
})
