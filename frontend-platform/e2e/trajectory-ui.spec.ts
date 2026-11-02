import { trajectoryBrowserCases } from '../../frontend-admin/e2e/support/trajectory-browser-cases'

trajectoryBrowserCases('platform')

import { test, expect } from '@playwright/test'
import { mockTrajectory } from '../../frontend-admin/e2e/support/trajectory-fixture'

test('平台轮次读取失败仍可按深链查看执行节点并恢复列表', async ({ page }) => {
  const fixture = await mockTrajectory(page, 'platform')
  let reads = 0
  await page.route(/\/api\/v1\/platform\/sessions\/rounds\?/, async (route) => {
    if (++reads === 1)
      await route.fulfill({ status: 503, json: { code: 'TEMPORARY', message: '轮次暂时不可读' } })
    else await route.fallback()
  })
  await page.goto(
    `/platform/trajectories/detail?tenant_id=${fixture.summary.tenant_id}&session_id=observed-session&root_input_id=observed-input&node_id=llm`
  )
  await expect(page.getByRole('region', { name: '当前执行节点详情' })).toContainText('observed-model')
  const rounds = page.getByRole('complementary', { name: '业务轮次' })
  await expect(rounds).toContainText('尚未读取')
  await page.getByRole('button', { name: '重新读取会话与轮次' }).click()
  await expect(rounds).toContainText('第 1 轮')
  await expect(page.getByRole('region', { name: '当前执行节点详情' })).toContainText('observed-model')
  expect(fixture.requests.filter((item) => item.method !== 'GET')).toEqual([])
  fixture.assertHandled()
})
