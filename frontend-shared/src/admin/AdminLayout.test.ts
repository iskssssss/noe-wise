import { flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, reactive, type App } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import AdminLayout from './AdminLayout.vue'

let active: App | undefined
let mobile = true
let themeFixture: HTMLStyleElement | undefined
let previousRootStyle: string | null
beforeEach(() => {
  previousRootStyle = document.documentElement.getAttribute('style')
  mobile = true
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe() {}
      unobserve() {}
      disconnect() {}
    }
  )
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches: query.includes('max-width') && mobile,
    media: query,
    addEventListener() {},
    removeEventListener() {}
  }))
})
afterEach(() => {
  active?.unmount()
  active = undefined
  document.body.innerHTML = ''
  themeFixture?.remove()
  themeFixture = undefined
  if (previousRootStyle === null) document.documentElement.removeAttribute('style')
  else document.documentElement.setAttribute('style', previousRootStyle)
  vi.unstubAllGlobals()
})

async function render(
  options: {
    compact?: boolean
    appearanceEnabled?: boolean
    theme?: 'light' | 'dark' | 'system'
    primaryColor?: string
  } = {}
) {
  const compactChange = vi.fn()
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/first', component: { render: () => h('h1', '第一页') } },
      { path: '/second', component: { render: () => h('h1', '第二页') } }
    ]
  })
  const host = defineComponent({
    render: () =>
      h(AdminLayout, {
        title: '机构后台',
        userName: '账号甲',
        home: '/first',
        menus: [
          { title: '第一页', path: '/first' },
          { title: '第二页', path: '/second' }
        ],
        compactNavigation: options.compact ?? false,
        appearanceEnabled: options.appearanceEnabled ?? false,
        theme: options.theme,
        primaryColor: options.primaryColor,
        onCompactChange: compactChange
      })
  })
  const container = document.createElement('div')
  document.body.appendChild(container)
  active = createApp(host).use(router).use(ElementPlus)
  await router.push('/first')
  await router.isReady()
  active.mount(container)
  await flushPromises()
  return { router, compactChange }
}
async function openNavigation() {
  const toggle = document.querySelector<HTMLButtonElement>('button[aria-label="打开导航"]')!
  expect(toggle.disabled).toBe(false)
  toggle.click()
  await nextTick()
  await flushPromises()
  return toggle
}

describe('共享布局响应式导航', () => {
  it('窄屏首次不占侧栏，无偏好写权也能打开导航；Esc关闭恢复按钮焦点', async () => {
    const { compactChange } = await render()
    expect(document.querySelector('.admin-layout--narrow')).not.toBeNull()
    expect(document.querySelector('aside.admin-sidebar')).toBeNull()
    expect(
      document.querySelector<HTMLElement>('.el-drawer')?.closest<HTMLElement>('.el-overlay')?.style
        .display
    ).toBe('none')
    const toggle = await openNavigation()
    expect(toggle.getAttribute('aria-expanded')).toBe('true')
    expect(document.querySelector('.el-drawer')?.textContent).toContain('第二页')
    document.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape', bubbles: true })
    )
    await flushPromises()
    await new Promise((resolve) => setTimeout(resolve, 350))
    await flushPromises()
    expect(toggle.getAttribute('aria-expanded')).toBe('false')
    expect(document.activeElement).toBe(toggle)
    expect(compactChange).not.toHaveBeenCalled()
  })

  it('导航守卫拒绝后保持原菜单高亮及打开的抽屉', async () => {
    const { router } = await render()
    router.beforeEach((to) => to.path !== '/second')
    const toggle = await openNavigation()
    const target = [...document.querySelectorAll<HTMLElement>('.el-menu-item')].find((item) =>
      item.textContent?.includes('第二页')
    )!
    target.click()
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/first')
    expect(document.querySelector('.el-menu-item.is-active')?.textContent).toContain('第一页')
    expect(toggle.getAttribute('aria-expanded')).toBe('true')
  })

  it('桌面继续沿用折叠偏好，并通过既有偏好事件更新', async () => {
    mobile = false
    const { compactChange } = await render({ compact: true, appearanceEnabled: true })
    expect(document.querySelector('.admin-layout--narrow')).toBeNull()
    expect(document.querySelector('.admin-layout--collapsed')).not.toBeNull()
    expect(document.querySelector('aside.admin-sidebar')).not.toBeNull()
    document.querySelector<HTMLButtonElement>('button[aria-label="展开导航"]')!.click()
    await flushPromises()
    expect(compactChange).toHaveBeenCalledExactlyOnceWith(false)
  })
})

function installDefaultTheme() {
  themeFixture = document.createElement('style')
  themeFixture.textContent = ':root { --el-color-primary: #409eff; }'
  document.head.appendChild(themeFixture)
}
function themeContrast(first: string, second: string) {
  const luminance = (color: string) => {
    const values = [1, 3, 5]
      .map((offset) => parseInt(color.slice(offset, offset + 2), 16) / 255)
      .map((value) => (value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4))
    return values[0]! * 0.2126 + values[1]! * 0.7152 + values[2]! * 0.0722
  }
  const values = [luminance(first), luminance(second)].sort((a, b) => b - a)
  return (values[0]! + 0.05) / (values[1]! + 0.05)
}
describe('共享布局缺省主题装配', () => {
  it.each(['light', 'dark'] as const)('未配置品牌的%s主题也处理组件库实际默认色', async (theme) => {
    installDefaultTheme()
    expect(
      getComputedStyle(document.documentElement).getPropertyValue('--el-color-primary').trim()
    ).toBe('#409eff')
    await render({ theme })
    const styles = document.documentElement.style
    const primary = styles.getPropertyValue('--el-color-primary')
    const foreground = styles.getPropertyValue('--admin-primary-on')
    expect(primary).toMatch(/^#[0-9a-f]{6}$/)
    expect(themeContrast(primary, theme === 'dark' ? '#1d1e1f' : '#ffffff')).toBeGreaterThanOrEqual(
      4.5
    )
    expect(themeContrast(primary, foreground)).toBeGreaterThanOrEqual(4.5)
    expect(
      themeContrast(styles.getPropertyValue('--el-color-primary-dark-2'), foreground)
    ).toBeGreaterThanOrEqual(4.5)
  })

  it('明确品牌优先于默认色，清除覆盖后回到同一次挂载的组件库基准', async () => {
    installDefaultTheme()
    const options = reactive<{ theme: 'light' | 'dark'; primaryColor?: string }>({
      theme: 'light',
      primaryColor: '#123456'
    })
    await render(options)
    expect(document.documentElement.style.getPropertyValue('--el-color-primary')).toBe('#123456')
    options.primaryColor = undefined
    await nextTick()
    expect(document.documentElement.style.getPropertyValue('--el-color-primary')).not.toBe(
      '#123456'
    )
    const primary = document.documentElement.style.getPropertyValue('--el-color-primary')
    expect(themeContrast(primary, '#ffffff')).toBeGreaterThanOrEqual(4.5)
    options.theme = 'dark'
    await nextTick()
    expect(
      themeContrast(
        document.documentElement.style.getPropertyValue('--el-color-primary'),
        '#1d1e1f'
      )
    ).toBeGreaterThanOrEqual(4.5)
    expect(document.documentElement.style.getPropertyValue('--admin-primary-on')).toBe('#141414')
  })

  it('卸载恢复原内联主题变量及未改动的外部样式，不把派生配色留给后继页面', async () => {
    document.documentElement.style.setProperty('--el-color-primary', '#409eff')
    document.documentElement.style.setProperty('--el-color-primary-light-3', '#a1b2c3')
    document.documentElement.style.setProperty('--admin-primary-on', '#102030')
    document.documentElement.style.setProperty('--layout-test-external', 'preserved')
    const before = document.documentElement.getAttribute('style')
    await render({ theme: 'light' })
    expect(document.documentElement.style.getPropertyValue('--el-color-primary')).not.toBe(
      '#409eff'
    )
    active!.unmount()
    active = undefined
    expect(document.documentElement.getAttribute('style')).toBe(before)
  })
})
