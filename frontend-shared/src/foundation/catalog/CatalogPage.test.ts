import { flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, type App } from 'vue'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import ElementPlus, { ElMessage, ElMessageBox } from 'element-plus'
import CatalogPage from './CatalogPage.vue'
import type { CatalogRequest } from './types'
import { confirm } from '../../feedback'

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
  allowed = ['foundation.dictionary.read', 'foundation.dictionary.manage']
) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      {
        path: '/',
        component: CatalogPage,
        props: {
          request,
          platform: false,
          permissions: allowed
        }
      }
    ]
  })
  const container = document.createElement('div')
  document.body.append(container)
  app = createApp(defineComponent({ render: () => h(RouterView) }))
    .use(router)
    .use(ElementPlus)
  app.mount(container)
  await router.push('/')
  await router.isReady()
  await flushPromises()
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
    expect(success).toHaveBeenCalledWith('已删除')
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
    expect(document.querySelector('.el-message--error')?.textContent).toContain('请选择业务页面')
    expect(vi.mocked(request).mock.calls.every(([method]) => method === 'GET')).toBe(true)
  })
})
