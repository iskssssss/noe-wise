import { flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, reactive, type App } from 'vue'
import { createMemoryHistory, createRouter, RouterView, type Router } from 'vue-router'
import { confirm } from '../../feedback'
import ElementPlus, { ElMessageBox } from 'element-plus'
import AccessPage from './AccessPage.vue'
import type { AccessRequest, Member, MemberPage } from './types'

vi.mock('../../feedback', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../feedback')>()),
  confirm: vi.fn().mockResolvedValue(true),
  message: { warning: vi.fn() }
}))

const permissions = [
  'foundation.access.read',
  'foundation.access.organization.manage',
  'foundation.access.role.manage'
]
const member = (name = '成员甲'): Member => ({
  id: '9223372036854775806',
  username: 'member-a',
  display_name: name,
  email: 'a@example.com',
  status: 'active',
  department_id: null,
  position_id: null,
  role_ids: ['9223372036854775805'],
  roles: ['developer'],
  lock_version: 7,
  authorization_version: 8,
  created_at: '2026-10-11T00:00:00Z'
})
const page = (name = '成员甲'): MemberPage => ({
  items: [member(name)],
  total: 1,
  page: 1,
  page_size: 20
})
function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((complete, fail) => {
    resolve = complete
    reject = fail
  })
  return { promise, resolve, reject }
}
let active: App | undefined
let activeRouter: Router
beforeEach(() => {
  vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm')
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe() {}
      unobserve() {}
      disconnect() {}
    }
  )
  vi.stubGlobal('matchMedia', (media: string) => ({
    media,
    matches: false,
    addEventListener() {},
    removeEventListener() {},
    addListener() {},
    removeListener() {},
    dispatchEvent: () => false
  }))
})
afterEach(() => {
  active?.unmount()
  active = undefined
  document.body.innerHTML = ''
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

/** 使用真实 Vue app、RouterView 与 ElementPlus；DOM 事件不经过测试工具的组件或 Teleport 重写。 */
async function render(
  request: AccessRequest,
  start = '/',
  platform = false,
  granted = permissions
) {
  const props = reactive({ request, permissions: [...granted], platform })
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: AccessPage, props: () => ({ ...props }) },
      { path: '/other', component: { template: '<p>其他页面</p>' } }
    ]
  })
  const host = defineComponent({ render: () => h(RouterView) })
  const container = document.createElement('div')
  document.body.appendChild(container)
  active = createApp(host).use(router).use(ElementPlus)
  activeRouter = router
  await router.push(start)
  await router.isReady()
  active.mount(container)
  await flushPromises()
  return props
}
function button(label: string) {
  const result = [...document.querySelectorAll<HTMLButtonElement>('button')].find(
    (item) => item.textContent?.trim() === label
  )
  expect(result, `应显示操作 ${label}`).toBeDefined()
  return result!
}
async function click(label: string) {
  button(label).click()
  await nextTick()
  await flushPromises()
}
async function input(element: HTMLInputElement, value: string) {
  element.value = value
  element.dispatchEvent(new Event('input', { bubbles: true }))
  await nextTick()
}
function dialog() {
  const result = document.querySelector<HTMLElement>('.el-dialog')
  expect(result).not.toBeNull()
  return result!
}
function reads(method: string, path: string) {
  if (method === 'GET') return path.includes('members?') ? page() : []
  return undefined
}

describe('组织权限真实交互', () => {
  it('宿主导航刷新相同权限时不会用旧地址重查成员', async () => {
    const request = vi.fn((method: string, path: string) => Promise.resolve(reads(method, path)))
    const props = await render(request as AccessRequest)
    activeRouter.beforeEach(() => {
      props.permissions = [...props.permissions]
    })
    request.mockClear()
    await input(
      document.querySelector<HTMLInputElement>('input[placeholder="用户名或显示名称"]')!,
      'member-a'
    )
    await click('查询')
    expect(activeRouter.currentRoute.value.query.keyword).toBe('member-a')
    expect(request.mock.calls.filter(([, path]) => path.includes('members?'))).toEqual([
      ['GET', 'foundation/access/members?page=1&page_size=20&keyword=member-a', undefined]
    ])
  })

  it('恢复成员已查条件与分页，输入草稿和失败查询不覆盖已成功地址', async () => {
    let fail = false
    const request = vi.fn((method: string, path: string) => {
      if (path.includes('members?'))
        return fail
          ? Promise.reject(new Error('成员读取失败'))
          : Promise.resolve({ ...page(), total: 120 })
      return Promise.resolve(reads(method, path))
    })
    await render(
      request as AccessRequest,
      '/?tab=members&keyword=member-a&page=2&page_size=10&keep=yes'
    )
    expect(request).toHaveBeenCalledWith(
      'GET',
      'foundation/access/members?page=2&page_size=10&keyword=member-a',
      undefined
    )
    const search = () =>
      document.querySelector<HTMLInputElement>('input[placeholder="用户名或显示名称"]')!
    expect(search().value).toBe('member-a')
    await input(search(), '未查询草稿')
    document.querySelector<HTMLElement>('#tab-departments')!.click()
    await flushPromises()
    expect(activeRouter.currentRoute.value.query).toMatchObject({
      tab: 'departments',
      keyword: 'member-a',
      page: '2',
      page_size: '10',
      keep: 'yes'
    })
    await activeRouter.push('/other')
    activeRouter.back()
    await vi.waitFor(() =>
      expect(document.querySelector('#tab-departments')?.getAttribute('aria-selected')).toBe('true')
    )
    document.querySelector<HTMLElement>('#tab-members')!.click()
    await flushPromises()
    expect(search().value).toBe('member-a')
    fail = true
    await input(search(), '失败条件')
    await click('查询')
    expect(activeRouter.currentRoute.value.query.keyword).toBe('member-a')
    expect(search().value).toBe('失败条件')
    expect(document.body.textContent).toContain('成员甲')
  })

  it('真实无匹配条件可清除且只用已查询条件更新地址', async () => {
    const request = vi.fn((method: string, path: string) =>
      Promise.resolve(
        path.includes('members?') ? { ...page(), items: [], total: 0 } : reads(method, path)
      )
    )
    await render(request as AccessRequest, '/?keyword=missing')
    expect(document.body.textContent).toContain('没有匹配当前查询的成员')
    await click('清除查询条件')
    expect(activeRouter.currentRoute.value.query.keyword).toBeUndefined()
    expect(request).toHaveBeenCalledWith(
      'GET',
      'foundation/access/members?page=1&page_size=20&keyword=',
      undefined
    )
    expect(request.mock.calls.every(([method]) => method === 'GET')).toBe(true)
  })

  it('从地址恢复资源时重读名称与授权；取消离开不丢授权草稿，撤权后不再读取', async () => {
    const request = vi.fn((method: string, path: string) => {
      if (path.startsWith('foundation/options/'))
        return Promise.resolve({
          items: [{ id: '123', name: '资源甲', code: 'agent_a', status: 'active' }],
          has_more: false
        })
      if (path.startsWith('foundation/access/resource?'))
        return Promise.resolve({
          kind: 'agent',
          resource_id: '123',
          owner_member_id: '9223372036854775806',
          department_id: null,
          grants: [],
          lock_version: 2
        })
      return Promise.resolve(reads(method, path))
    })
    const props = await render(
      request as AccessRequest,
      '/?tab=resources&resource_kind=agent&resource_id=123',
      false,
      [...permissions, 'foundation.access.resource.manage']
    )
    expect(document.body.textContent).toContain('资源甲 · 授权版本 2')
    await click('增加共享目标')
    vi.mocked(confirm).mockResolvedValueOnce(false)
    document.querySelector<HTMLElement>('#tab-departments')!.click()
    await flushPromises()
    expect(activeRouter.currentRoute.value.query.tab).toBe('resources')
    expect(document.querySelector('#tab-resources')?.getAttribute('aria-selected')).toBe('true')
    expect(button('保存授权').disabled).toBe(false)
    expect(request.mock.calls.every(([method]) => method === 'GET')).toBe(true)
    const count = request.mock.calls.filter(([, path]) =>
      path.startsWith('foundation/access/resource?')
    ).length
    props.permissions = [...permissions]
    await flushPromises()
    expect(document.querySelector('#tab-resources')).toBeNull()
    expect(
      request.mock.calls.filter(([, path]) => path.startsWith('foundation/access/resource?'))
    ).toHaveLength(count)
  })

  it('平台不能通过地址进入机构资源授权', async () => {
    const request = vi.fn((method: string, path: string) => Promise.resolve(reads(method, path)))
    await render(request as AccessRequest, '/?tab=resources&resource_id=123', true)
    expect(document.querySelector('#tab-members')?.getAttribute('aria-selected')).toBe('true')
    expect(document.querySelector('#tab-resources')).toBeNull()
    expect(
      request.mock.calls.some(([, path]) => path.startsWith('foundation/access/resource?'))
    ).toBe(false)
  })

  it.each([
    ['部门', 'department', 'departments'],
    ['岗位', 'position', 'positions']
  ])('%s 必填和编码错误定位到字段，修正后才提交且新表单不继承错误', async (label, kind, tab) => {
    const request = vi.fn((method: string, path: string) => Promise.resolve(reads(method, path)))
    await render(request as AccessRequest)
    document.querySelector<HTMLElement>(`#tab-${tab}`)!.click()
    await nextTick()
    await click(`新增${label}`)
    const inputs = [...dialog().querySelectorAll<HTMLInputElement>('input')]
    expect(dialog().querySelector('.el-form-item__error')).toBeNull()
    await input(inputs[0]!, '   ')
    await input(inputs[1]!, '   ')
    await click('保存')
    await vi.waitFor(() => {
      expect(dialog().textContent).toContain(`请填写${label}编码`)
      expect(dialog().textContent).toContain(`请填写${label}名称`)
    })
    expect(request.mock.calls.filter(([method]) => method !== 'GET')).toHaveLength(0)
    await input(inputs[0]!, '1invalid')
    await input(inputs[1]!, `${label}巡检`)
    await click('保存')
    await vi.waitFor(() => {
      expect(dialog().textContent).toContain('请按上方规则填写编码')
      expect(dialog().textContent).not.toContain(`请填写${label}名称`)
    })
    expect(request.mock.calls.filter(([method]) => method !== 'GET')).toHaveLength(0)
    await input(inputs[0]!, 'ui_audit_1')
    await click('保存')
    expect(request).toHaveBeenCalledWith(
      'POST',
      `foundation/access/${kind}`,
      expect.objectContaining({ code: 'ui_audit_1', name: `${label}巡检` })
    )
    await click(`新增${label}`)
    expect(dialog().querySelector('.el-form-item__error')).toBeNull()
    expect(
      [...dialog().querySelectorAll<HTMLInputElement>('input')]
        .slice(0, 2)
        .map((item) => item.value)
    ).toEqual(['', ''])
  })

  it('成员关键词和真实每页条数控件关联唯一可见的非空说明', async () => {
    const request: AccessRequest = async <T>(method: string, path: string) =>
      reads(method, path) as T
    await render(request)
    const controls = [
      document.querySelector<HTMLInputElement>('input[placeholder="用户名或显示名称"]'),
      document.querySelector<HTMLElement>('.el-pagination [role="combobox"]')
    ]
    for (const control of controls) {
      expect(control).not.toBeNull()
      const ids = control!.getAttribute('aria-describedby')?.split(/\s+/).filter(Boolean) ?? []
      expect(ids.length).toBeGreaterThan(0)
      for (const id of ids) {
        const targets = document.querySelectorAll<HTMLElement>(`[id="${id}"]`)
        expect(targets).toHaveLength(1)
        expect(targets[0]!.textContent?.trim()).not.toBe('')
        expect(targets[0]!.closest('[hidden], [aria-hidden="true"]')).toBeNull()
        expect(getComputedStyle(targets[0]!).display).not.toBe('none')
      }
    }
  })

  it('初读及读取失败不显示成功空结果，重试成功后才显示真实空列表', async () => {
    const first = deferred<MemberPage>()
    let memberReads = 0
    const request = vi.fn((method: string, path: string) =>
      path.includes('members?')
        ? ++memberReads === 1
          ? first.promise
          : Promise.resolve({ ...page(), items: [], total: 0 })
        : Promise.resolve([])
    )
    await render(request as AccessRequest)
    expect(document.body.textContent).toContain('正在读取组织与权限')
    expect(document.querySelector('.access-page')?.getAttribute('aria-busy')).toBe('true')
    expect(document.querySelector('.el-table')).toBeNull()
    expect(document.querySelector('.el-pagination')).toBeNull()
    first.reject(new Error('读取失败'))
    await flushPromises()
    expect(document.body.textContent).toContain('读取失败')
    expect(document.body.textContent).toContain('请点击刷新重试')
    expect(document.querySelector('.el-table')).toBeNull()
    await click('刷新')
    expect(document.querySelector('.el-table')).not.toBeNull()
    expect(document.querySelector('.el-pagination')).not.toBeNull()
    expect(document.querySelector('.access-page')?.getAttribute('aria-busy')).toBe('false')
    expect(document.querySelector('.el-alert--error')).toBeNull()
  })

  it('刷新读取中和失败后保留上次成功列表', async () => {
    const refresh = deferred<MemberPage>()
    let memberReads = 0
    const request = vi.fn((method: string, path: string) =>
      path.includes('members?')
        ? ++memberReads === 1
          ? Promise.resolve(page('已读取成员'))
          : refresh.promise
        : Promise.resolve([])
    )
    await render(request as AccessRequest)
    await click('刷新')
    expect(document.body.textContent).toContain('仍显示上次成功读取的数据')
    expect(document.body.textContent).toContain('已读取成员')
    refresh.reject(new Error('刷新读取失败'))
    await flushPromises()
    expect(document.body.textContent).toContain('刷新读取失败')
    expect(document.body.textContent).toContain('已读取成员')
    expect(document.querySelector('.access-page')?.getAttribute('aria-busy')).toBe('false')
  })

  it('迟到成员读取不能覆盖后来的查询结果', async () => {
    const first = deferred<MemberPage>()
    const second = deferred<MemberPage>()
    let readsStarted = 0
    const request = vi.fn((method: string, path: string) =>
      path.includes('members?')
        ? ++readsStarted === 1
          ? first.promise
          : second.promise
        : Promise.resolve([])
    )
    await render(request as AccessRequest)
    await input(document.querySelector<HTMLInputElement>('input')!, 'new')
    await click('查询')
    second.resolve(page('新结果'))
    await flushPromises()
    expect(document.body.textContent).toContain('新结果')
    first.resolve(page('旧结果'))
    await flushPromises()
    expect(document.body.textContent).toContain('新结果')
    expect(document.body.textContent).not.toContain('旧结果')
  })

  it('失败保留表单、字符串身份和版本；撤权后立即只读且不再提交', async () => {
    const request = vi.fn((method: string, path: string) =>
      method === 'PUT'
        ? Promise.reject(new Error('并发版本冲突'))
        : Promise.resolve(reads(method, path))
    )
    const props = await render(request as AccessRequest)
    await click('编辑资料')
    const inputs = [...dialog().querySelectorAll<HTMLInputElement>('input')]
    await input(inputs[1]!, '修改后的姓名')
    await click('保存')
    expect(document.querySelector('.el-message--error')?.textContent).toContain('并发版本冲突')
    expect(inputs[1]!.value).toBe('修改后的姓名')
    expect(request).toHaveBeenCalledWith(
      'PUT',
      'foundation/access/member',
      expect.objectContaining({
        id: '9223372036854775806',
        display_name: '修改后的姓名',
        lock_version: 7
      })
    )
    const writes = request.mock.calls.filter((call) => call[0] !== 'GET').length
    props.permissions = ['foundation.access.read']
    await nextTick()
    await flushPromises()
    expect(button('保存').disabled).toBe(true)
    expect(inputs[1]!.disabled).toBe(true)
    await click('保存')
    expect(request.mock.calls.filter((call) => call[0] !== 'GET')).toHaveLength(writes)
  })

  it('凭据仅随本次创建发送，成功关闭后清空且不缓存', async () => {
    const cache = vi.spyOn(Storage.prototype, 'setItem')
    const request = vi.fn((method: string, path: string) => Promise.resolve(reads(method, path)))
    await render(request as AccessRequest)
    await click('新增成员')
    dialog().querySelector<HTMLInputElement>('input[type="radio"][value="password"]')!.click()
    await nextTick()
    await flushPromises()
    const inputs = [...dialog().querySelectorAll<HTMLInputElement>('input')].filter(
      (item) => !['radio', 'checkbox'].includes(item.type)
    )
    await input(inputs[0]!, 'new-member')
    await input(inputs[1]!, '新成员')
    await input(inputs[2]!, 'new@example.com')
    await input(inputs[3]!, 'Secret12345678')
    await click('保存')
    expect(request).toHaveBeenCalledWith(
      'POST',
      'foundation/access/member',
      expect.objectContaining({ password: 'Secret12345678' })
    )
    expect(cache).not.toHaveBeenCalled()
    await click('新增成员')
    dialog().querySelector<HTMLInputElement>('input[type="radio"][value="password"]')!.click()
    await nextTick()
    await flushPromises()
    expect(dialog().querySelector<HTMLInputElement>('input[type="password"]')!.value).toBe('')
  })
  it('角色权限按业务分组，筛选和只看已选保持授权草稿', async () => {
    const request = vi.fn(async (_method: string, path: string) => {
      if (path.endsWith('/permissions'))
        return [
          { code: 'foundation.access.read', name: '读取组织', audience: 'tenant' },
          { code: 'foundation.security.session.manage', name: '撤销设备', audience: 'tenant' }
        ]
      return reads('GET', path)
    }) as unknown as AccessRequest
    await render(request)
    ;[...document.querySelectorAll<HTMLElement>('[role="tab"]')]
      .find((item) => item.textContent?.trim() === '角色与数据范围')!
      .click()
    await flushPromises()
    await click('新增角色')
    expect(dialog().querySelectorAll('fieldset')).toHaveLength(2)
    const checkboxes = dialog().querySelectorAll<HTMLInputElement>('input[type="checkbox"]')
    checkboxes[1]!.click()
    await flushPromises()
    checkboxes[0]!.click()
    await flushPromises()
    expect(dialog().querySelectorAll('fieldset')).toHaveLength(1)
    expect(dialog().textContent).toContain('读取组织')
    expect(dialog().textContent).not.toContain('foundation.access.read')
    expect(dialog().textContent).not.toContain('撤销设备')
    await input(
      dialog().querySelector<HTMLInputElement>('input[placeholder="搜索权限动作"]')!,
      '不存在'
    )
    await flushPromises()
    expect(dialog().textContent).toContain('没有符合条件的权限')
    expect(dialog().textContent).toContain('已选 1 项权限')
  })
})
