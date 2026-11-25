import { flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, type App } from 'vue'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import ElementPlus, { ElMessage, ElMessageBox } from 'element-plus'
import CatalogPage from './CatalogPage.vue'
import type { CatalogRequest, FeatureGrant, Limits, TenantEntitlements } from './types'
import { confirm } from '../../feedback'
import { useFoundationNavigation } from '../navigation'
import type { HttpClient } from '../../utils/http'

vi.mock('../../feedback', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../feedback')>()),
  confirm: vi.fn().mockResolvedValue(true),
  message: { warning: vi.fn() }
}))
const dictionary = { code: 'test_catalog', name: '验收字典', enabled: true, revision: 1 }
let app: App | undefined
beforeEach(() => {
  vi.mocked(confirm).mockReset().mockResolvedValue(true)
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe() {}
      unobserve() {}
      disconnect() {}
    }
  )
})
afterEach(() => {
  app?.unmount()
  app = undefined
  document.body.innerHTML = ''
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})
async function render(
  request: CatalogRequest,
  allowed = ['foundation.dictionary.read', 'foundation.dictionary.manage'],
  platform = false,
  entry = '/',
  navigationRequest?: HttpClient['request']
) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      {
        path: '/',
        component: CatalogPage,
        props: {
          request,
          platform,
          permissions: allowed
        }
      },
      { path: '/away', component: defineComponent({ render: () => h('p', '其他页面') }) }
    ]
  })
  const container = document.createElement('div')
  document.body.append(container)
  app = createApp(
    defineComponent({
      setup() {
        const navigation = navigationRequest
          ? useFoundationNavigation(
              navigationRequest,
              platform ? '/admin' : '/tenant',
              () => 'audit'
            )
          : undefined
        return () =>
          h('div', [
            navigation
              ? h('nav', { 'aria-label': '宿主菜单' }, [
                  ...navigation.menus.value.map((menu) => h('span', menu.title)),
                  navigation.error.value
                    ? h('button', { onClick: navigation.reload }, '重新加载菜单')
                    : null,
                  navigation.error.value
                ])
              : null,
            h(RouterView)
          ])
      }
    })
  )
    .use(router)
    .use(ElementPlus)
  app.mount(container)
  await router.push(entry)
  await router.isReady()
  await flushPromises()
  return router
}
function button(text: string, root: ParentNode = document) {
  const node = [...root.querySelectorAll<HTMLButtonElement>('button')].find(
    (node) => node.textContent?.trim() === text
  )
  if (!node) throw new Error(`Button missing: ${text}`)
  return node
}
async function editName(value: string) {
  button('编辑').click()
  await flushPromises()
  const dialog = document.querySelector<HTMLElement>('.el-dialog')!
  const input = dialog.querySelectorAll<HTMLInputElement>('input')[1]
  input.value = value
  input.dispatchEvent(new Event('input', { bubbles: true }))
  await flushPromises()
  return dialog
}
describe('目录编辑实际交互保护', () => {
  it('品牌字段错误就地提示，按码点校验名称且修正后保存完整品牌与原修订', async () => {
    const current = {
      display_name: '巡检机构',
      logo_url: null,
      primary_color: '#2563eb',
      contact_email: null,
      support_url: null,
      revision: 4
    }
    const request = vi.fn(async (method: string, _path: string, data?: object) =>
      method === 'GET' ? current : { ...data, revision: 5 }
    ) as unknown as CatalogRequest
    await render(
      request,
      ['foundation.settings.read', 'foundation.settings.manage'],
      false,
      '/?tab=branding'
    )
    function field(label: string) {
      const item = [...document.querySelectorAll('.el-form-item')].find(
        (item) => item.querySelector('label')?.textContent === label
      )!
      return item.querySelector<HTMLInputElement>('input')!
    }
    function fill(label: string, value: string) {
      const input = field(label)
      input.value = value
      input.dispatchEvent(new Event('input', { bubbles: true }))
    }
    fill('品牌名称', '   ')
    fill('公开 Logo HTTPS 链接', 'http://example.invalid/logo.png')
    fill('联系邮箱', 'invalid-email')
    fill('支持页面 HTTPS 链接', 'https://account@example.invalid/support')
    await flushPromises()
    button('保存品牌').click()
    await vi.waitFor(() => expect(document.body.textContent).toContain('请输入品牌名称'))
    expect(document.querySelectorAll('.el-form-item__error')).toHaveLength(4)
    expect(document.body.textContent).toContain('请输入有效的联系邮箱')
    expect(document.body.textContent).toContain('请输入不含账号密码的完整 HTTPS 链接')
    expect(vi.mocked(request).mock.calls.filter(([method]) => method === 'PUT')).toHaveLength(0)
    fill('品牌名称', '灵'.repeat(129))
    fill('公开 Logo HTTPS 链接', 'https://example.invalid/logo.png')
    fill('联系邮箱', 'audit@example.invalid')
    fill('支持页面 HTTPS 链接', 'https://example.invalid/support')
    await flushPromises()
    button('保存品牌').click()
    await vi.waitFor(() => expect(document.body.textContent).toContain('品牌名称最多 128 个字符'))
    expect(vi.mocked(request).mock.calls.filter(([method]) => method === 'PUT')).toHaveLength(0)
    fill('品牌名称', '🌿'.repeat(128))
    await flushPromises()
    button('保存品牌').click()
    await flushPromises()
    const writes = vi.mocked(request).mock.calls.filter(([method]) => method === 'PUT')
    expect(writes).toHaveLength(1)
    expect(writes[0]?.[2]).toEqual({
      display_name: '🌿'.repeat(128),
      logo_url: 'https://example.invalid/logo.png',
      primary_color: '#2563eb',
      contact_email: 'audit@example.invalid',
      support_url: 'https://example.invalid/support',
      expected_revision: 4
    })
    expect(button('保存品牌').disabled).toBe(true)
    for (const label of ['公开 Logo HTTPS 链接', '联系邮箱', '支持页面 HTTPS 链接']) fill(label, '')
    await flushPromises()
    button('保存品牌').click()
    await flushPromises()
    expect(
      vi.mocked(request).mock.calls.filter(([method]) => method === 'PUT')[1]?.[2]
    ).toMatchObject({
      logo_url: null,
      contact_email: null,
      support_url: null,
      expected_revision: 5
    })
  })

  it('品牌写入失败只提示一次并保留编辑输入，不显示读取设置恢复动作', async () => {
    const request = vi.fn(async (method: string) => {
      if (method === 'PUT') throw new Error('品牌保存暂时不可用')
      return {
        display_name: '原机构',
        primary_color: '#2563eb',
        logo_url: null,
        contact_email: null,
        support_url: null,
        revision: 2
      }
    }) as unknown as CatalogRequest
    await render(
      request,
      ['foundation.settings.read', 'foundation.settings.manage'],
      false,
      '/?tab=branding'
    )
    const input = document.querySelector<HTMLInputElement>('.settings-form input')!
    input.value = '保留品牌草稿'
    input.dispatchEvent(new Event('input', { bubbles: true }))
    await flushPromises()
    button('保存品牌').click()
    await flushPromises()
    expect(input.value).toBe('保留品牌草稿')
    expect(document.querySelectorAll('.el-message--error')).toHaveLength(1)
    expect(document.body.textContent).toContain('品牌保存暂时不可用')
    expect(document.body.textContent).not.toContain('重新读取展示设置')
    expect(button('保存品牌').disabled).toBe(false)
  })

  it.each(['dictionary', 'value'])('%s 必填和编码错误就地提示，修正后才提交', async (kind) => {
    const request = vi.fn(async (_method: string, path: string) =>
      path.endsWith('/dictionary-values') ? [] : [dictionary]
    ) as unknown as CatalogRequest
    await render(request)
    if (kind === 'value') {
      button('查看值').click()
      await flushPromises()
    }
    button(kind === 'dictionary' ? '新建字典' : '添加值').click()
    await flushPromises()
    const dialog = document.querySelector<HTMLElement>('.el-dialog')!
    button('保存', dialog).click()
    await flushPromises()
    await vi.waitFor(() => expect(dialog.textContent).toContain('请输入稳定编码'))
    expect(dialog.textContent).toContain(kind === 'dictionary' ? '请输入名称' : '请输入显示文本')
    expect(document.querySelector('.el-message--error')).toBeNull()
    expect(vi.mocked(request).mock.calls.every(([method]) => method === 'GET')).toBe(true)
    const inputs = dialog.querySelectorAll<HTMLInputElement>('input')
    inputs[0].value = 'Invalid code'
    inputs[1].value = '   '
    for (const input of [inputs[0], inputs[1]])
      input.dispatchEvent(new Event('input', { bubbles: true }))
    button('保存', dialog).click()
    await flushPromises()
    await vi.waitFor(() => expect(dialog.textContent).toContain('以小写字母开头'))
    expect(dialog.textContent).toContain(kind === 'dictionary' ? '请输入名称' : '请输入显示文本')
    expect(vi.mocked(request).mock.calls.every(([method]) => method === 'GET')).toBe(true)
    inputs[0].value = 'valid_code'
    inputs[1].value = '有效文本'
    for (const input of [inputs[0], inputs[1]])
      input.dispatchEvent(new Event('input', { bubbles: true }))
    button('保存', dialog).click()
    await flushPromises()
    const writes = vi.mocked(request).mock.calls.filter(([method]) => method === 'PUT')
    expect(writes).toHaveLength(1)
    expect(writes[0][2]).toMatchObject({
      code: 'valid_code',
      [kind === 'dictionary' ? 'name' : 'label']: '有效文本'
    })
    expect(dialog.querySelector('.el-dialog__title')?.textContent).toBe(
      kind === 'dictionary' ? '字典' : '字典值'
    )
    expect(dialog.querySelectorAll<HTMLInputElement>('input')[1].value).toBe('有效文本')
  })
  it('真实写入拒绝后顶部提示一次且弹窗保留草稿', async () => {
    const request = vi.fn(async (method: string) => {
      if (method === 'PUT') throw new Error('目录修订冲突')
      return [dictionary]
    }) as unknown as CatalogRequest
    await render(request)
    const dialog = await editName('仍需保留的草稿')
    button('保存', dialog).click()
    await flushPromises()
    expect(document.querySelector('.el-message--error')?.textContent).toContain('目录修订冲突')
    expect(dialog.querySelector('.el-alert')).toBeNull()
    expect(dialog.querySelectorAll<HTMLInputElement>('input')[1].value).toBe('仍需保留的草稿')
    expect(dialog.closest('.el-overlay')?.getAttribute('style')).not.toContain('display: none')
  })
  it('取消关闭确认保持草稿，确认丢弃不发写请求', async () => {
    const request = vi.fn(async () => [dictionary]) as unknown as CatalogRequest
    await render(request)
    const dialog = await editName('未保存的草稿')
    vi.mocked(confirm).mockResolvedValueOnce(false)
    button('取消', dialog).click()
    await flushPromises()
    expect(dialog.querySelectorAll<HTMLInputElement>('input')[1].value).toBe('未保存的草稿')
    expect(confirm).toHaveBeenCalledWith(expect.objectContaining({ cancelText: '继续编辑' }))
    vi.mocked(confirm).mockResolvedValueOnce(true)
    button('取消', dialog).click()
    await flushPromises()
    expect(vi.mocked(request).mock.calls.every(([method]) => method === 'GET')).toBe(true)
  })
  it('写入成功后刷新失败明确已保存且只写一次', async () => {
    let reads = 0
    const request = vi.fn(async (method: string) => {
      if (method === 'PUT') return { ...dictionary, revision: 2 }
      if (++reads > 1) throw new Error('读取暂时不可用')
      return [dictionary]
    }) as unknown as CatalogRequest
    await render(request)
    const dialog = await editName('已写入名称')
    button('保存', dialog).click()
    await flushPromises()
    expect([...document.querySelectorAll('.el-message--error')].at(-1)?.textContent).toContain(
      '已保存，但最新数据读取失败'
    )
    expect(document.body.textContent).toContain('可重新读取，无需再次提交')
    expect(vi.mocked(request).mock.calls.filter(([method]) => method === 'PUT')).toHaveLength(1)
  })
  it('删除确认标明真实对象，删除成功而读取失败时不误报保存或重复删除', async () => {
    const confirmation = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm')
    const success = vi.spyOn(ElMessage, 'success')
    let reads = 0
    const request = vi.fn(async (method: string) => {
      if (method === 'DELETE') return {}
      if (++reads > 1) throw new Error('目录读取暂不可用')
      return [dictionary]
    }) as unknown as CatalogRequest
    await render(request)
    button('删除').click()
    await flushPromises()
    expect(confirmation).toHaveBeenCalledWith(
      expect.stringContaining('验收字典”（test_catalog）'),
      '删除确认',
      expect.any(Object)
    )
    expect(success).not.toHaveBeenCalled()
    expect(document.body.textContent).toContain('已删除，但最新数据读取失败')
    expect(document.body.textContent).toContain('无需再次提交')
    expect(vi.mocked(request).mock.calls.filter(([method]) => method === 'DELETE')).toHaveLength(1)
  })
  it('提交未完成时取消和表单不可操作', async () => {
    let resolve!: (value: unknown) => void
    const pending = new Promise((complete) => {
      resolve = complete
    })
    const request = vi.fn(async (method: string) =>
      method === 'PUT' ? pending : [dictionary]
    ) as unknown as CatalogRequest
    await render(request)
    const dialog = await editName('正在保存')
    button('保存', dialog).click()
    await flushPromises()
    expect(button('取消', dialog).disabled).toBe(true)
    expect(dialog.querySelectorAll<HTMLInputElement>('input')[1].disabled).toBe(true)
    resolve(dictionary)
    await flushPromises()
  })
  it('菜单父节点排除自身及所有后代，页面类型必须明确选择受控路由', async () => {
    const nodes = [
      {
        code: 'root',
        title: '根分组',
        parent_code: null,
        route_key: null,
        sort_order: 0,
        enabled: true,
        revision: 1
      },
      {
        code: 'child',
        title: '子分组',
        parent_code: 'root',
        route_key: null,
        sort_order: 0,
        enabled: true,
        revision: 1
      },
      {
        code: 'grandchild',
        title: '孙页面',
        parent_code: 'child',
        route_key: 'home',
        sort_order: 0,
        enabled: true,
        revision: 1
      },
      {
        code: 'other',
        title: '其它分组',
        parent_code: null,
        route_key: null,
        sort_order: 0,
        enabled: true,
        revision: 1
      }
    ]
    const request = vi.fn(async (_method: string, path: string) =>
      path.endsWith('/routes')
        ? [{ key: 'home', title: '工作台', path: '/home', permission: '', platform: false }]
        : nodes
    ) as unknown as CatalogRequest
    await render(request, ['foundation.navigation.read', 'foundation.navigation.manage'])
    expect(document.body.textContent).toContain('根分组 / 子分组 / 孙页面')
    button('编辑').click()
    await flushPromises()
    const dialog = document.querySelector<HTMLElement>('.el-dialog')!
    ;(dialog.querySelector('.el-select__wrapper') as HTMLElement).click()
    await flushPromises()
    const options = [...document.querySelectorAll('.el-select-dropdown__item')].map((item) =>
      item.textContent?.trim()
    )
    expect(options).toEqual(['其它分组'])
    dialog.querySelector<HTMLInputElement>('input[type="radio"][value="page"]')!.click()
    await flushPromises()
    button('保存', dialog).click()
    await flushPromises()
    await vi.waitFor(() =>
      expect(dialog.querySelector('.el-form-item__error')?.textContent).toContain('请选择业务页面')
    )
    expect(document.querySelector('.el-message--error')).toBeNull()
    expect(vi.mocked(request).mock.calls.every(([method]) => method === 'GET')).toBe(true)
  })
  it('菜单新建校验编码、标题和排序，修正后以原始字段保存分组', async () => {
    const request = vi.fn(async (_method: string, path: string) =>
      path.endsWith('/routes')
        ? [{ key: 'home', title: '工作台', path: '/home', permission: '', platform: false }]
        : []
    ) as unknown as CatalogRequest
    await render(request, ['foundation.navigation.read', 'foundation.navigation.manage'])
    button('新增节点').click()
    await flushPromises()
    const dialog = document.querySelector<HTMLElement>('.el-dialog')!
    button('保存', dialog).click()
    await vi.waitFor(() => expect(dialog.textContent).toContain('请输入稳定编码'))
    expect(dialog.textContent).toContain('请输入标题')
    expect(document.querySelector('.el-message--error')).toBeNull()
    const inputs = dialog.querySelectorAll<HTMLInputElement>('input')
    inputs[0].value = 'Invalid code'
    inputs[1].value = '   '
    for (const input of [inputs[0], inputs[1]])
      input.dispatchEvent(new Event('input', { bubbles: true }))
    button('保存', dialog).click()
    await vi.waitFor(() => expect(dialog.textContent).toContain('以小写字母开头'))
    expect(dialog.textContent).toContain('请输入标题')
    inputs[0].value = 'audit_menu'
    inputs[1].value = '菜单验收分组'
    for (const input of [inputs[0], inputs[1]])
      input.dispatchEvent(new Event('input', { bubbles: true }))
    const sort = dialog.querySelector<HTMLInputElement>('[role="spinbutton"]')!
    sort.value = ''
    sort.dispatchEvent(new Event('input', { bubbles: true }))
    sort.dispatchEvent(new Event('change', { bubbles: true }))
    button('保存', dialog).click()
    await vi.waitFor(() =>
      expect(dialog.textContent).toContain('请输入 0 至 2147483647 的整数排序')
    )
    expect(vi.mocked(request).mock.calls.every(([method]) => method === 'GET')).toBe(true)
    sort.value = '0'
    sort.dispatchEvent(new Event('input', { bubbles: true }))
    sort.dispatchEvent(new Event('change', { bubbles: true }))
    button('保存', dialog).click()
    await flushPromises()
    expect(vi.mocked(request).mock.calls.filter(([method]) => method === 'PUT')).toEqual([
      [
        'PUT',
        'foundation/catalog/navigation',
        {
          code: 'audit_menu',
          title: '菜单验收分组',
          parent_code: null,
          route_key: null,
          sort_order: 0,
          enabled: true,
          expected_revision: 0
        }
      ]
    ])
  })
})

it.each([true, false])(
  '菜单写入后刷新所属宿主，读回失败只重试读取（平台=%s）',
  async (platform) => {
    let node = {
      code: 'audit_menu',
      title: '原菜单',
      parent_code: null,
      route_key: 'home',
      sort_order: 0,
      enabled: true,
      revision: 1
    }
    let writeFails = true
    let readFails = false
    let deleted = false
    const request = vi.fn(
      async (method: string, path: string, command?: Record<string, unknown>) => {
        if (method === 'PUT') {
          if (writeFails) throw new Error('菜单保存失败')
          node = { ...node, title: String(command!.title), revision: node.revision + 1 }
          return node
        }
        if (method === 'DELETE') {
          deleted = true
          return
        }
        if (path.endsWith('/routes'))
          return [{ key: 'home', title: '工作台', path: '/home', permission: '', platform }]
        return deleted ? [] : [node]
      }
    ) as unknown as CatalogRequest
    const navigationRequest = vi.fn(async () => {
      if (readFails) throw new Error('当前菜单读取失败')
      return deleted
        ? []
        : [{ title: node.title, path: platform ? '/platform/home' : '/admin/home', children: [] }]
    }) as unknown as HttpClient['request']
    await render(
      request,
      ['foundation.navigation.read', 'foundation.navigation.manage'],
      platform,
      '/',
      navigationRequest
    )
    const shell = document.querySelector('nav')!
    expect(shell.textContent).toContain('原菜单')
    await editName('已保存的新菜单')
    button('保存').click()
    await flushPromises()
    expect(navigationRequest).toHaveBeenCalledTimes(1)
    expect(shell.textContent).toContain('原菜单')
    expect(document.body.textContent).toContain('菜单保存失败')
    writeFails = false
    readFails = true
    button('保存').click()
    await flushPromises()
    expect(navigationRequest).toHaveBeenCalledTimes(2)
    expect(shell.textContent).toContain('当前菜单读取失败')
    expect(shell.textContent).toContain('菜单变更已保存，但侧栏更新失败')
    expect(shell.textContent).toContain('无需再次保存')
    expect(document.querySelector('.el-message--success')).toBeNull()
    expect(document.querySelector('.el-table__body')?.textContent).toContain('已保存的新菜单')
    readFails = false
    button('重新加载菜单').click()
    await flushPromises()
    expect(shell.textContent).toBe('已保存的新菜单')
    expect(vi.mocked(request).mock.calls.filter(([method]) => method === 'PUT')).toHaveLength(2)
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm')
    button('删除').click()
    await flushPromises()
    expect(shell.textContent).toBe('')
    expect(navigationRequest).toHaveBeenCalledTimes(4)
    expect(
      vi
        .mocked(navigationRequest)
        .mock.calls.every(
          ([method, path]) =>
            method === 'GET' &&
            path === `${platform ? '/admin' : '/tenant'}/foundation/catalog/navigation/current`
        )
    ).toBe(true)
  }
)

describe('目录浏览位置与字典值恢复', () => {
  const value = {
    dictionary_code: dictionary.code,
    code: 'item',
    label: '保留的字典值',
    sort_order: 0,
    enabled: true,
    revision: 1
  }
  it('选择字典后离开返回与页面重建均按地址恢复对象并重读数据', async () => {
    const request = vi.fn(async (_method: string, path: string) =>
      path.includes('/dictionary-values?') ? [value] : [dictionary]
    ) as unknown as CatalogRequest
    const router = await render(request)
    button('查看值').click()
    await flushPromises()
    expect(router.currentRoute.value.query).toEqual({
      tab: 'dictionaries',
      dictionary_code: dictionary.code
    })
    const location = router.currentRoute.value.fullPath
    await router.push('/away')
    router.back()
    await vi.waitFor(() => expect(document.body.textContent).toContain(value.label))
    expect(router.currentRoute.value.fullPath).toBe(location)
    app!.unmount()
    document.body.innerHTML = ''
    await render(request, undefined, false, location)
    expect(document.body.textContent).toContain('验收字典 · 字典值')
    expect(document.body.textContent).toContain(value.label)
    expect(
      vi.mocked(request).mock.calls.filter(([, path]) => path.includes('/dictionary-values?'))
    ).toHaveLength(3)
    expect(vi.mocked(request).mock.calls.every(([method]) => method === 'GET')).toBe(true)
  })
  it('字典值首次加载和失败不显示空态，刷新失败保留原值并可重试', async () => {
    let reject!: (error: Error) => void
    let read = new Promise<unknown>((_resolve, fail) => {
      reject = fail
    })
    const request = vi.fn(async (_method: string, path: string) =>
      path.includes('/dictionary-values?') ? read : [dictionary]
    ) as unknown as CatalogRequest
    await render(request, undefined, false, '/?tab=dictionaries&dictionary_code=test_catalog')
    expect(document.body.textContent).toContain('正在读取字典值')
    expect(document.body.textContent).not.toContain('此字典还没有值')
    reject(new Error('字典值读取失败'))
    await flushPromises()
    expect(document.body.textContent).toContain('可点击“刷新字典值”重试')
    read = Promise.resolve([value])
    button('刷新字典值').click()
    await flushPromises()
    expect(document.body.textContent).toContain(value.label)
    read = Promise.reject(new Error('字典值刷新失败'))
    button('刷新字典值').click()
    await flushPromises()
    expect(document.body.textContent).toContain(value.label)
    expect(document.body.textContent).toContain('字典值刷新失败')
    read = Promise.resolve([])
    button('刷新字典值').click()
    await flushPromises()
    expect(document.body.textContent).toContain('此字典还没有值，可点击“添加值”创建')
  })
  it('恢复位置不能打开无权页签，已失效字典提示重新选择且不请求旧值', async () => {
    const request = vi.fn(async () => []) as unknown as CatalogRequest
    const router = await render(
      request,
      ['foundation.dictionary.read'],
      true,
      '/?tab=entitlements&dictionary_code=missing&plan_id=1&tenant_id=100'
    )
    expect(document.querySelector('[role="tab"][aria-selected="true"]')?.textContent).toBe('字典')
    expect(document.body.textContent).toContain('之前选择的字典已不可用，请重新选择')
    expect(vi.mocked(request).mock.calls).toEqual([
      ['GET', 'foundation/catalog/dictionaries', undefined]
    ])
    await router.replace('/?tab=navigation&dictionary_code[]=test_catalog')
    await flushPromises()
    expect(vi.mocked(request).mock.calls.every(([, path]) => path.endsWith('/dictionaries'))).toBe(
      true
    )
  })
  it('页签切换取消离开时保持页签、地址与偏好草稿，确认后才切换', async () => {
    const request = vi.fn(async (_method: string, path: string) =>
      path.endsWith('/preferences')
        ? {
            theme: 'system',
            locale: 'zh-CN',
            page_size: 20,
            compact_navigation: false,
            revision: 1
          }
        : []
    ) as unknown as CatalogRequest
    const router = await render(
      request,
      ['foundation.preferences.manage', 'foundation.navigation.read'],
      false,
      '/?tab=preferences'
    )
    document.querySelector<HTMLInputElement>('input[role="switch"]')!.click()
    await flushPromises()
    vi.mocked(confirm).mockResolvedValueOnce(false)
    ;(document.querySelector('#tab-navigation') as HTMLElement).click()
    await flushPromises()
    expect(router.currentRoute.value.query.tab).toBe('preferences')
    expect(document.querySelector('[role="tab"][aria-selected="true"]')?.textContent).toBe(
      '个人偏好'
    )
    expect(document.querySelector<HTMLInputElement>('input[role="switch"]')!.checked).toBe(true)
    vi.mocked(confirm).mockResolvedValueOnce(true)
    ;(document.querySelector('#tab-navigation') as HTMLElement).click()
    await flushPromises()
    expect(router.currentRoute.value.query.tab).toBe('navigation')
    expect(document.querySelector('[role="tab"][aria-selected="true"]')?.textContent).toBe(
      '菜单结构'
    )
    expect(vi.mocked(request).mock.calls.every(([method]) => method === 'GET')).toBe(true)
  })
})

const limits: Limits = {
  agents: null,
  workflows: null,
  knowledge_bases: null,
  members: null,
  channels: null,
  file_bytes: null,
  admin_tasks: null,
  sub_institutions: null
}
const features = [{ code: 'agents', name: '智能体', description: '', enabled: true, revision: 1 }]
const grants = [{ feature_code: 'agents', enabled: true, revision: 2 }]
const plans = [
  { id: '1', code: 'first', name: '套餐甲', enabled: true, revision: 1 },
  { id: '2', code: 'second', name: '套餐乙', enabled: true, revision: 1 }
]
async function selectPlan(label: string) {
  ;(document.querySelector('[aria-label="选择授权套餐"]') as HTMLElement).click()
  await flushPromises()
  const option = [...document.querySelectorAll<HTMLElement>('[role="option"]')].find(
    (item) => item.textContent?.trim() === label
  )!
  option.click()
  await flushPromises()
}

describe('授权读取状态与资源用量', () => {
  it('地址恢复套餐和机构时从现有接口重读授权及机构名称', async () => {
    const request = vi.fn(async (_method: string, path: string) => {
      if (path.includes('/options/tenants'))
        return { items: [{ id: '100', name: '恢复机构' }], has_more: false }
      if (path.endsWith('/features')) return features
      if (path.endsWith('/plans')) return plans
      if (path.includes('/plan-quota?')) return { plan_id: '1', limits, revision: 4 }
      if (path.includes('/plan-features?')) return grants
      if (path.includes('/tenant-entitlements?'))
        return { tenant_id: '100', limits, overrides: limits, revision: 1, features: [], usage: [] }
      if (path.includes('/tenant-features?')) return []
      throw new Error(`Unexpected request: ${path}`)
    }) as unknown as CatalogRequest
    await render(
      request,
      ['foundation.entitlements.read'],
      true,
      '/?tab=entitlements&plan_id=1&tenant_id=100'
    )
    expect(document.body.textContent).toContain('套餐甲 (first)')
    expect(document.body.textContent).toContain('恢复机构')
    expect(
      document.querySelector('[aria-label="智能体的套餐授权"]')?.getAttribute('aria-checked')
    ).toBe('true')
    expect(
      vi
        .mocked(request)
        .mock.calls.some(([, path]) => path.endsWith('/tenant-entitlements?tenant_id=100'))
    ).toBe(true)
    expect(vi.mocked(request).mock.calls.every(([method]) => method === 'GET')).toBe(true)
  })
  it('首次失败不显示关闭开关，重读成功后显示真实授权；刷新失败保留原结果', async () => {
    let rejectRead!: (reason: Error) => void
    let read: Promise<FeatureGrant[]> = new Promise((_resolve, reject) => {
      rejectRead = reject
    })
    const request = vi.fn(async (_method: string, path: string) => {
      if (path.endsWith('/features')) return features
      if (path.endsWith('/plans')) return plans
      if (path.includes('/plan-quota?')) return { plan_id: '1', limits, revision: 4 }
      if (path.includes('/plan-features?')) return read
      throw new Error(`Unexpected request: ${path}`)
    }) as unknown as CatalogRequest
    await render(request, ['foundation.entitlements.read', 'foundation.entitlements.manage'], true)
    await selectPlan('套餐甲 (first)')
    expect(document.body.textContent).toContain('正在读取套餐授权与限额')
    expect(document.querySelector('[aria-label="智能体的套餐授权"]')).toBeNull()
    rejectRead(new Error('授权读取暂不可用'))
    await flushPromises()
    expect(document.body.textContent).toContain('授权读取暂不可用')
    expect(document.querySelector('[aria-label="智能体的套餐授权"]')).toBeNull()
    read = Promise.resolve(grants)
    button('重新读取套餐授权').click()
    await flushPromises()
    expect(
      document.querySelector('[aria-label="智能体的套餐授权"]')?.getAttribute('aria-checked')
    ).toBe('true')
    read = new Promise((_resolve, reject) => {
      rejectRead = reject
    })
    button('刷新套餐授权').click()
    await flushPromises()
    const control = document.querySelector('[aria-label="智能体的套餐授权"]')!
    expect(control.getAttribute('aria-checked')).toBe('true')
    expect(control.getAttribute('aria-disabled')).toBe('true')
    expect(document.body.textContent).toContain('正在刷新套餐授权')
    rejectRead(new Error('刷新暂不可用'))
    await flushPromises()
    expect(control.getAttribute('aria-checked')).toBe('true')
    expect(document.body.textContent).not.toContain('正在刷新套餐授权')
    expect(vi.mocked(request).mock.calls.every(([method]) => method === 'GET')).toBe(true)
  })

  it('切换套餐后旧响应不能恢复旧开关或结束新套餐加载', async () => {
    let first!: (value: FeatureGrant[]) => void
    let second!: (value: FeatureGrant[]) => void
    const firstRead = new Promise<FeatureGrant[]>((resolve) => {
      first = resolve
    })
    const secondRead = new Promise<FeatureGrant[]>((resolve) => {
      second = resolve
    })
    const request = vi.fn(async (_method: string, path: string) => {
      if (path.endsWith('/features')) return features
      if (path.endsWith('/plans')) return plans
      if (path.includes('/plan-quota?'))
        return { plan_id: path.endsWith('=1') ? '1' : '2', limits, revision: 1 }
      if (path.includes('/plan-features?')) return path.endsWith('=1') ? firstRead : secondRead
      throw new Error(`Unexpected request: ${path}`)
    }) as unknown as CatalogRequest
    await render(request, ['foundation.entitlements.read', 'foundation.entitlements.manage'], true)
    await selectPlan('套餐甲 (first)')
    await selectPlan('套餐乙 (second)')
    first(grants)
    await flushPromises()
    expect(document.body.textContent).toContain('正在读取套餐授权与限额')
    expect(document.querySelector('[aria-label="智能体的套餐授权"]')).toBeNull()
    second([{ ...grants[0], enabled: false }])
    await flushPromises()
    expect(
      document.querySelector('[aria-label="智能体的套餐授权"]')?.getAttribute('aria-checked')
    ).toBe('false')
    expect(document.body.textContent).not.toContain('正在读取套餐授权与限额')
  })

  it('机构首次读取与刷新可辨识，失败保留资源名称和精确用量并可重读', async () => {
    const tenant: TenantEntitlements = {
      tenant_id: '100',
      limits,
      overrides: limits,
      revision: 1,
      features: ['agents'],
      usage: [
        { kind: 'AGENT', used: '0', limit: '0' },
        { kind: 'FILE_BYTES', used: '9007199254740993', limit: null },
        { kind: 'ADMIN_TASK', used: '2', limit: '4' },
        { kind: 'SUB_INSTITUTION', used: '1', limit: '3' }
      ]
    }
    let finish!: (value: TenantEntitlements) => void
    let rejectRead!: (reason: Error) => void
    let read = new Promise<TenantEntitlements>((resolve) => {
      finish = resolve
    })
    const request = vi.fn(async (_method: string, path: string) => {
      if (path.endsWith('/features')) return features
      if (path.endsWith('/tenant-features')) return []
      if (path.endsWith('/tenant-entitlements')) return read
      throw new Error(`Unexpected request: ${path}`)
    }) as unknown as CatalogRequest
    await render(request, ['foundation.entitlements.read'])
    expect(document.body.textContent).toContain('正在读取机构授权与资源用量')
    finish(tenant)
    await flushPromises()
    for (const label of [
      '公共文件（字节）',
      '并行管理任务',
      '直属子机构',
      '9007199254740993',
      '不限'
    ]) {
      expect(document.body.textContent).toContain(label)
    }
    expect(document.body.textContent).not.toContain('FILE_BYTES')
    expect(document.querySelectorAll('.el-table__body tr')[0]?.textContent).toBe('智能体00')
    read = new Promise((_resolve, reject) => {
      rejectRead = reject
    })
    button('刷新授权').click()
    await flushPromises()
    expect(document.body.textContent).toContain('正在刷新机构授权')
    expect(document.body.textContent).toContain('9007199254740993')
    rejectRead(new Error('机构授权读取暂不可用'))
    await flushPromises()
    expect(document.body.textContent).toContain('9007199254740993')
    expect(document.body.textContent).toContain('机构授权读取暂不可用')
    read = Promise.resolve(tenant)
    button('刷新授权').click()
    await flushPromises()
    expect(document.body.textContent).not.toContain('正在刷新机构授权')
    expect(vi.mocked(request).mock.calls.every(([method]) => method === 'GET')).toBe(true)
  })
})
