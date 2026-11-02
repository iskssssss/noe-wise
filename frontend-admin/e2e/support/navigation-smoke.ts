import { test, expect } from '@playwright/test'
import type { NavigationItem } from '@skillforge/shared/foundation/navigation'
import { captureUi, mockUiSmoke } from './ui-smoke'

export function navigationSmoke(kind: 'admin' | 'platform') {
  test(`${kind} 导航：真实多层目录的搜索、折叠、紧凑弹出和窄窗焦点`, async ({ page }, info) => {
    const targetName = kind === 'admin' ? '智能体' : '模型管理'
    const secondName = kind === 'admin' ? '记忆管理' : '机构管理'
    const sessionName = kind === 'admin' ? '会话记录' : '会话观察'
    const target = `/${kind}/${kind === 'admin' ? 'agents' : 'models'}`
    const second = `/${kind}/${kind === 'admin' ? 'memory' : 'tenants'}`
    const leaf = (title: string, path: string): NavigationItem => ({
      title,
      path,
      permission: null,
      children: []
    })
    const group = (title: string, children: NavigationItem[]): NavigationItem => ({
      title,
      path: null,
      permission: null,
      children
    })
    const navigation = [
      group('业务资源', [group('能力目录', [leaf(targetName, target), leaf(secondName, second)])]),
      group('运营协作', [
        group('会话与审查', [leaf(sessionName, `/${kind}/sessions`)]),
        leaf('展示设置', `/${kind}/foundation/catalog`)
      ]),
      group('个人工作', [leaf('工作台', `/${kind}/home`)])
    ]
    const fixture = await mockUiSmoke(page, kind, 'dark', {
      navigation,
      allowPreferenceWrites: true
    })
    await page.goto(target)
    await expect(page.getByRole('heading', { name: targetName, exact: true })).toBeVisible()
    const narrow = (page.viewportSize()?.width ?? 1440) < 900
    const toggle = page.getByRole('button', { name: narrow ? '打开导航' : '收起导航', exact: true })
    if (narrow) await toggle.click()
    const region = narrow
      ? page.getByRole('dialog', { name: '主导航', exact: true })
      : page.locator('.admin-sidebar')
    await expect(region).toBeVisible()
    const nav = region.getByRole('navigation', { name: '主导航', exact: true })
    const search = region.getByRole('textbox', { name: '查找功能', exact: true })
    await expect(nav.locator('.el-sub-menu.is-opened')).toHaveCount(2)
    await expect(nav.getByRole('link', { name: targetName, exact: true })).toHaveAttribute(
      'aria-current',
      'page'
    )
    await expect(nav.locator('.admin-nav-group-title')).toHaveText([
      '业务资源',
      '能力目录',
      '运营协作',
      '会话与审查',
      '个人工作'
    ])
    if (!narrow) await captureUi(page, info, '01-navigation-expanded')

    await search.fill('不存在的功能别名')
    await expect(region.getByText('没有匹配的功能，请更换关键词', { exact: true })).toBeVisible()
    await expect(nav.locator('a.admin-menu-link')).toHaveCount(0)
    await search.fill(targetName)
    await expect(nav.locator('a.admin-menu-link')).toHaveCount(1)
    await expect(nav.getByRole('link', { name: targetName, exact: true })).toBeVisible()
    await search.fill(second)
    await expect(nav.locator('a.admin-menu-link')).toHaveCount(1)
    await expect(nav.getByRole('link', { name: secondName, exact: true })).toBeVisible()
    await search.press('Escape')
    await expect(search).toHaveValue('')
    await expect(region).toBeVisible()
    await expect(nav.locator('a.admin-menu-link')).toHaveCount(5)
    await search.fill('业务资源')
    await expect(nav.locator('a.admin-menu-link')).toHaveCount(2)
    await expect(nav.getByRole('link', { name: targetName, exact: true })).toBeVisible()
    await expect(nav.getByRole('link', { name: secondName, exact: true })).toBeVisible()
    if (narrow) await captureUi(page, info, '02-navigation-mobile-search')
    await region.locator('.el-input__clear').click()
    await expect(search).toHaveValue('')
    await expect(nav.locator('.admin-nav-group-title')).toHaveText([
      '业务资源',
      '能力目录',
      '运营协作',
      '会话与审查',
      '个人工作'
    ])
    const business = nav.locator('.el-sub-menu__title').filter({ hasText: '业务资源' })
    await business.click()
    await expect(nav.getByRole('link', { name: targetName, exact: true })).toBeHidden()
    await business.click()
    await expect(nav.getByRole('link', { name: targetName, exact: true })).toBeVisible()
    await nav.getByRole('link', { name: secondName, exact: true }).click()
    await expect(page).toHaveURL(new RegExp(second + '$'))

    if (narrow) {
      await expect(region).toBeHidden()
      await expect(toggle).toBeFocused()
      await toggle.click()
      await expect(page.getByRole('dialog', { name: '主导航', exact: true })).toBeVisible()
      await page
        .getByRole('dialog', { name: '主导航', exact: true })
        .getByRole('button', { name: '关闭此对话框', exact: true })
        .click()
      await expect(page.getByRole('dialog', { name: '主导航', exact: true })).toBeHidden()
      await expect(toggle).toBeFocused()
    } else {
      await toggle.click()
      await expect(page.locator('.admin-layout')).toHaveClass(/admin-layout--collapsed/)
      await expect(search).toHaveCount(0)
      const title = nav.locator('.el-sub-menu__title').filter({ hasText: '运营协作' })
      await title.hover()
      const popup = page.locator('.admin-nav-popup:visible')
      await expect(
        popup.locator('.admin-nav-group-title').filter({ hasText: '会话与审查' })
      ).toBeVisible()
      await popup.locator('.el-sub-menu__title').filter({ hasText: '会话与审查' }).hover()
      const sessionLink = page
        .locator('.admin-nav-popup:visible')
        .getByRole('link', { name: sessionName, exact: true })
      await expect(sessionLink).toBeVisible()
      await captureUi(page, info, '03-navigation-compact-popup')
      await sessionLink.click()
      await expect(page).toHaveURL(new RegExp(`/${kind}/sessions$`))
    }
    const writes = fixture.requests.filter((request) => request.method !== 'GET')
    expect(writes).toHaveLength(narrow ? 0 : 1)
    if (!narrow)
      expect(writes[0]).toMatchObject({
        method: 'PUT',
        body: { compact_navigation: true, expected_revision: 7 }
      })
    fixture.assertHandled()
  })
}
