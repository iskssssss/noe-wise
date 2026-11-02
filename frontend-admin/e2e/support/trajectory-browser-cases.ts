import { test, expect } from '@playwright/test'
import { mockTrajectory } from './trajectory-fixture'

export function trajectoryBrowserCases(kind: 'admin' | 'platform') {
  test(`${kind} 轨迹：默认定位结果未知的工具并尊重显式深链`, async ({ page }) => {
    const fixture = await mockTrajectory(page, kind, { unknown: true })
    const base = `/${kind}/trajectories/detail?tenant_id=${fixture.summary.tenant_id}&session_id=observed-session&root_input_id=observed-input`
    await page.goto(base)
    const detail = page.getByRole('region', { name: '当前执行节点详情', exact: true })
    await expect(detail).toContainText('knowledge_query')
    await page.goto(base + '&node_id=llm')
    await expect(detail).toContainText('observed-model')
    expect(fixture.requests.filter((item) => item.method !== 'GET')).toEqual([])
    fixture.assertHandled()
  })
  test(`${kind} 轨迹：筛选、执行树搜索、受控工具交换与JSON使用同一资源`, async ({ page }) => {
    const fixture = await mockTrajectory(page, kind)
    await page.goto(`/${kind}/trajectories`)
    await expect(page.getByRole('heading', { name: 'Agent 轨迹', exact: true })).toBeVisible()
    await expect(page.getByText('请给出评估操作步骤', { exact: true })).toBeVisible()
    await page.getByRole('textbox', { name: 'TraceID', exact: true }).fill('trace-observed')
    await page.getByRole('textbox', { name: '输入关键词', exact: true }).fill('评估%_')
    await page.getByRole('button', { name: '查询', exact: true }).click()
    await expect
      .poll(
        () =>
          fixture.requests.filter((request) => request.path.endsWith('/trajectories')).at(-1)?.query
      )
      .toContain('input_keyword=')
    const request = fixture.requests.filter((item) => item.path.endsWith('/trajectories')).at(-1)!
    expect(new URLSearchParams(request.query).get('trace_id')).toBe('trace-observed')
    expect(new URLSearchParams(request.query).get('input_keyword')).toBe('评估%_')
    await page.getByRole('link', { name: '请给出评估操作步骤', exact: true }).click()
    await expect(page.getByRole('region', { name: '执行调用树', exact: true })).toBeVisible()
    const search = page.getByPlaceholder('工具名、可见摘要或错误信息')
    await search.fill('评估')
    await page.getByRole('button', { name: '搜索', exact: true }).click()
    await expect(page.locator('[data-node-id="retrieval"]')).toBeVisible()
    expect(
      new URLSearchParams(
        fixture.requests.filter((item) => item.path.endsWith('/nodes')).at(-1)!.query
      ).get('query')
    ).toBe('评估')
    await search.fill('')
    await page.getByRole('button', { name: '搜索', exact: true }).click()
    await page.locator('[data-node-id="tool"]').click()
    await page.getByRole('button', { name: '查看原始输入', exact: true }).click()
    await expect(page.getByText('已记录的工具结果', { exact: false })).toBeVisible()
    const content = fixture.requests.filter((item) => item.path.endsWith('/content')).at(-1)!
    expect(new URLSearchParams(content.query).get('root_input_id')).toBe('observed-input')
    await page.screenshot({ path: test.info().outputPath('execution-tree.png'), fullPage: true })
    await page
      .getByRole('dialog', { name: '节点原始内容', exact: true })
      .getByRole('button', { name: /关闭/ })
      .click()
    await page.getByRole('button', { name: '查看轨迹 JSON', exact: true }).click()
    await expect(page.getByRole('dialog').locator('.cm-content')).toContainText('observed-input')
    expect(fixture.requests.filter((item) => item.method !== 'GET')).toEqual([])
    fixture.assertHandled()
  })

  test(`${kind} 轨迹：分区与长名称成员层级保持清晰`, async ({ page }) => {
    const fixture = await mockTrajectory(page, kind, { dense: true })
    await page.setViewportSize({ width: 1440, height: 1080 })
    await page.goto(
      `/${kind}/trajectories/detail?tenant_id=${fixture.summary.tenant_id}&session_id=observed-session&root_input_id=observed-input&node_id=deep-tool`
    )
    const tree = page.getByRole('region', { name: '执行调用树', exact: true })
    const detail = page.getByRole('region', { name: '当前执行节点详情', exact: true })
    const row = page.locator('[data-node-id="deep-tool"]')
    await expect(tree).toBeVisible()
    await expect(row).toBeVisible()
    await expect(detail).toContainText(
      'institution_evidence_reconciliation_with_extended_diagnostic_name'
    )
    const bounds = await row.boundingBox()
    expect(bounds?.height).toBeLessThan(90)
    await page.screenshot({
      path: test.info().outputPath('partitioned-workspace.png'),
      fullPage: true
    })
    await tree.scrollIntoViewIfNeeded()
    await page.screenshot({
      path: test.info().outputPath('tree-hierarchy-wide.png'),
      fullPage: true
    })
    for (const width of [1280, 720]) {
      await page.setViewportSize({ width, height: 1080 })
      expect(
        await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)
      ).toBe(true)
      await expect(detail).toContainText(
        'institution_evidence_reconciliation_with_extended_diagnostic_name'
      )
    }
    await page.emulateMedia({ colorScheme: 'dark' })
    await tree.scrollIntoViewIfNeeded()
    await page.screenshot({
      path: test.info().outputPath('tree-hierarchy-dark-narrow.png'),
      fullPage: true
    })
    expect(fixture.requests.filter((item) => item.method !== 'GET')).toEqual([])
    fixture.assertHandled()
  })

  test(`${kind} 轨迹：窄窗口、暗色与刷新失败保留已读事实`, async ({ page }) => {
    const fixture = await mockTrajectory(page, kind)
    await page.setViewportSize({ width: 720, height: 960 })
    await page.emulateMedia({ colorScheme: 'dark' })
    await page.goto(
      `/${kind}/trajectories/detail?tenant_id=${fixture.summary.tenant_id}&session_id=observed-session&root_input_id=observed-input&node_id=tool`
    )
    await expect(page.locator('[data-node-id="tool"]')).toBeVisible()
    await expect(page.locator('html')).toHaveClass(/dark/)
    await expect(page.getByRole('button', { name: '查看原始输出', exact: true })).toBeVisible()
    await page.screenshot({ path: test.info().outputPath('dark-narrow.png'), fullPage: true })
    fixture.setNodesFailure(true)
    await page.getByRole('button', { name: '刷新', exact: true }).first().click()
    await expect(
      page
        .locator('.el-alert')
        .filter({ hasText: /OBSERVATION|读取|失败/ })
        .first()
    ).toBeVisible()
    await expect(page.locator('[data-node-id="tool"]')).toBeVisible()
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1)
    ).toBe(true)
    fixture.assertHandled()
  })
}
