import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { computed } from 'vue'
import { ElDatePicker, ElPagination } from 'element-plus'
import { createMemoryHistory, createRouter } from 'vue-router'
import { EntitySelect } from '@skillforge/shared/admin'
import ModelBillingDetails from '@skillforge/shared/components/ModelBillingDetails.vue'
import { createPermissionDirective } from '@skillforge/shared/directives/permission'
import {
  loadTenantOptions,
  resolveTenantOption,
  createOperationOptions,
  createProvisionPlanOptions,
  loadNamespaceOptions
} from '../../api/entityOptions'
import type { ModelBillingMonthly, TenantItem } from '../../api'
import BillingPage from './BillingPage.vue'

const http = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
  patch: vi.fn(),
  del: vi.fn(),
  request: vi.fn()
}))
const auth = vi.hoisted(() => ({
  identityVersion: 1,
  user: { sub: 'platform-actor', permissions: ['foundation.billing.read'] }
}))
vi.mock('@skillforge/shared/utils/http', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@skillforge/shared/utils/http')>()),
  createHttpClient: () => http
}))
vi.mock('../../stores/auth', () => ({ useAuthStore: () => auth }))
vi.mock('../../router', () => ({ default: {} }))
const tenant = {
  id: 858547788300294,
  name: '数字 ID 机构',
  slug: 'numeric-tenant',
  status: 'suspended'
} satisfies TenantItem
const monthly: ModelBillingMonthly = {
  month: '2026-10',
  currency: 'CNY',
  model_calls: 3,
  known_amount: '0.00000001',
  total_amount: null,
  missing_cost_count: 1,
  prompt_tokens: '9007199254740993',
  completion_tokens: '13',
  total: 41,
  page: 1,
  page_size: 20,
  items: []
}
let wrapper: VueWrapper | undefined
beforeEach(() => {
  vi.clearAllMocks()
  auth.user.permissions = ['foundation.billing.read']
  http.get.mockImplementation(async (path: string) => {
    const url = new URL(path, 'https://example.test')
    if (url.pathname === '/admin/tenants') return { items: [tenant], total: 1 }
    if (url.pathname === '/admin/tenants/detail') return { ...tenant, member_count: 0 }
    if (url.pathname === '/platform/billing/monthly')
      return {
        ...monthly,
        month: url.searchParams.get('month'),
        page: Number(url.searchParams.get('page')),
        page_size: Number(url.searchParams.get('page_size'))
      }
    throw new Error('Unexpected GET: ' + path)
  })
})
afterEach(() => {
  vi.restoreAllMocks()
  wrapper?.unmount()
  wrapper = undefined
  document.body.innerHTML = ''
})
async function open() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/platform/billing', component: BillingPage }]
  })
  await router.push('/platform/billing')
  await router.isReady()
  wrapper = mount(BillingPage, {
    attachTo: document.body,
    global: {
      plugins: [router],
      directives: {
        permission: createPermissionDirective(() => computed(() => auth.user.permissions))
      }
    }
  })
  await flushPromises()
  return wrapper
}
async function query(tenantId = String(tenant.id), month = '2026-10') {
  if (!wrapper) throw new Error('页面未打开')
  wrapper.getComponent(EntitySelect).vm.$emit('update:modelValue', tenantId)
  wrapper.getComponent(ElDatePicker).vm.$emit('update:modelValue', month)
  await flushPromises()
  await wrapper.get('form.admin-filters').trigger('submit')
  await flushPromises()
}
describe('只读模型月账单', () => {
  it('首次选择机构后自动查询当前所选月份，无需再次点击查询', async () => {
    const page = await open()
    page.getComponent(ElDatePicker).vm.$emit('update:modelValue', '2026-10')
    page.getComponent(EntitySelect).vm.$emit('update:modelValue', String(tenant.id))
    await flushPromises()
    const requests = http.get.mock.calls.filter(([path]) =>
      String(path).startsWith('/platform/billing/monthly')
    )
    expect(requests).toHaveLength(1)
    expect(requests[0]?.[0]).toBe(
      `/platform/billing/monthly?tenant_id=${tenant.id}&month=2026-10&page=1&page_size=20`
    )
    expect(page.getComponent(ModelBillingDetails).props('monthly').month).toBe('2026-10')
  })
  it('数字机构ID在候选与已选项中保持相同字符串身份', async () => {
    const options = await loadTenantOptions('数字')
    expect(options).toEqual([
      { id: String(tenant.id), label: tenant.name, description: tenant.slug }
    ])
    expect(await resolveTenantOption(options[0]!.id)).toEqual(options[0])
  })
  it('实际机构选择与月份发出只读GET，展示原样金额和未知合计且无销售写入口', async () => {
    const page = await open()
    const select = page.getComponent(EntitySelect)
    await select.get('input[role="combobox"]').trigger('click')
    await flushPromises()
    const option = Array.from(
      document.body.querySelectorAll<HTMLElement>('.el-select-dropdown__item')
    ).find((item) => item.textContent?.includes(tenant.name))!
    option.click()
    page.getComponent(ElDatePicker).vm.$emit('update:modelValue', '2026-10')
    await flushPromises()
    await page.get('form.admin-filters').trigger('submit')
    await flushPromises()
    expect(http.get).toHaveBeenCalledWith(
      `/platform/billing/monthly?tenant_id=${tenant.id}&month=2026-10&page=1&page_size=20`,
      { signal: expect.any(AbortSignal) }
    )
    expect(page.text()).toContain('0.00000001')
    expect(page.text()).toContain('9007199254740993')
    expect(page.text()).toContain('不代表完整费用')
    for (const text of ['发布销售价', '封账', '补单', '调账', '生成账单'])
      expect(page.text()).not.toContain(text)
    expect(http.post).not.toHaveBeenCalled()
  })
  it('未查询的机构与月份不改变分页范围，改每页条数回第一页', async () => {
    const page = await open()
    await query()
    page.getComponent(EntitySelect).vm.$emit('update:modelValue', 'other')
    page.getComponent(ElDatePicker).vm.$emit('update:modelValue', '2026-11')
    await flushPromises()
    page.getComponent(ElPagination).vm.$emit('current-change', 2)
    await flushPromises()
    expect(http.get.mock.lastCall?.[0]).toBe(
      `/platform/billing/monthly?tenant_id=${tenant.id}&month=2026-10&page=2&page_size=20`
    )
    page.getComponent(ElPagination).vm.$emit('size-change', 50)
    await flushPromises()
    expect(http.get.mock.lastCall?.[0]).toBe(
      `/platform/billing/monthly?tenant_id=${tenant.id}&month=2026-10&page=1&page_size=50`
    )
  })
  it('切换机构清除旧结果，迟到响应不能覆盖当前机构', async () => {
    const page = await open()
    let oldResolve!: (value: ModelBillingMonthly) => void
    const fallback = http.get.getMockImplementation()!
    http.get.mockImplementation((path: string, ...args: unknown[]) =>
      path.includes('tenant_id=A&')
        ? new Promise((resolve) => {
            oldResolve = resolve
          })
        : fallback(path, ...args)
    )
    await query('A')
    const firstCall = http.get.mock.calls.find(([path]) => String(path).includes('tenant_id=A&'))!
    await query('B', '2026-11')
    expect(page.getComponent(ModelBillingDetails).props('monthly').month).toBe('2026-11')
    oldResolve({ ...monthly, known_amount: '999' })
    await flushPromises()
    expect(page.getComponent(ModelBillingDetails).props('monthly').known_amount).toBe('0.00000001')
    expect(firstCall[1].signal.aborted).toBe(true)
  })
  it('缺少读取权限不会请求模型月费用', async () => {
    auth.user.permissions = ['foundation.billing.create']
    await open()
    await query()
    expect(
      http.get.mock.calls.some(([path]) => String(path).startsWith('/platform/billing/monthly'))
    ).toBe(false)
  })
  it('清空月份不发请求，分页失败保留上次成功结果的页码与精确金额', async () => {
    const page = await open()
    await query(String(tenant.id), '')
    expect(
      http.get.mock.calls.some(([path]) => String(path).startsWith('/platform/billing/monthly'))
    ).toBe(false)
    await query()
    http.get.mockRejectedValueOnce(new Error('模型费用暂不可读取'))
    page.getComponent(ElPagination).vm.$emit('current-change', 2)
    await flushPromises()
    expect(page.getComponent(ElPagination).props('currentPage')).toBe(1)
    expect(page.getComponent(ModelBillingDetails).props('monthly').known_amount).toBe('0.00000001')
  })
})

describe('平台操作候选目录', () => {
  it('按操作上下文读取全部分页并保留大整数的字符串身份', async () => {
    http.request.mockImplementation(async (_method: string, path: string) => {
      const url = new URL(path, 'https://example.test')
      expect(url.searchParams.get('operation')).toBe('platform.agent_grants.read')
      const page = Number(url.searchParams.get('page'))
      return {
        total: 2,
        items: [
          {
            id: page === 1 ? '9223372036854775806' : '9223372036854775807',
            label: `机构${page}`,
            description: `org-${page}`
          }
        ]
      }
    })
    const options = createOperationOptions('tenants', 'platform.agent_grants.read')
    expect((await options.load('机构')).map((option) => option.id)).toEqual([
      '9223372036854775806',
      '9223372036854775807'
    ])
    expect(http.request).toHaveBeenCalledTimes(2)
    expect(http.get).not.toHaveBeenCalled()
  })
  it('定义候选读取全部页，目录重复时失败而非呈现为完整结果', async () => {
    http.request.mockResolvedValue({
      total: 2,
      items: [{ id: 'definition-1', label: '问诊', description: 'v2' }]
    })
    const options = createOperationOptions('definitions', 'platform.agent_grants.create')
    await expect(options.load('问诊')).rejects.toThrow('候选目录已变化')
    expect(http.request).toHaveBeenCalledTimes(2)
  })
  it('接入候选只绑定所选机构，空机构不请求其他范围', async () => {
    http.request.mockImplementation(async (_method: string, path: string) => {
      const url = new URL(path, 'https://example.test')
      expect(url.searchParams.get('tenant_id')).toBe('tenant-7')
      expect(url.searchParams.get('q')).toBe('目标')
      expect(url.searchParams.get('operation')).toBe('platform.institution_credentials.read')
      return { total: 1, items: [{ id: 'i2', label: '目标域', description: 'i2' }] }
    })
    expect(
      await createOperationOptions(
        'integrations',
        'platform.institution_credentials.read',
        'tenant-7'
      ).load('目标')
    ).toEqual([{ id: 'i2', label: '目标域', description: 'i2' }])
    http.request.mockClear()
    expect(
      await createOperationOptions(
        'integrations',
        'platform.institution_credentials.read',
        ''
      ).load('')
    ).toEqual([])
    expect(http.request).not.toHaveBeenCalled()
  })
  it('已选身份重读仍使用原操作与精确 id，不调用宽泛详情接口', async () => {
    http.request.mockResolvedValue({
      total: 1,
      items: [{ id: '9223372036854775807', label: '管理目标', description: '' }]
    })
    const options = createOperationOptions('tenants', 'platform.management_principals.create')
    expect((await options.resolve('9223372036854775807'))?.label).toBe('管理目标')
    const path = String(http.request.mock.lastCall?.[1])
    expect(path).toContain('id=9223372036854775807')
    expect(path).toContain('operation=platform.management_principals.create')
    expect(http.get).not.toHaveBeenCalled()
  })
})

describe('平台开通候选契约', () => {
  it('套餐搜索与精确回读使用独立开通上下文，完整读取跨页结果', async () => {
    http.request.mockImplementation(async (_method: string, path: string) => {
      const url = new URL(path, 'https://example.test')
      expect(url.pathname).toBe('/platform/operation-options/provision/plans')
      const page = Number(url.searchParams.get('page'))
      return {
        items: [{ id: `plan-${page}`, label: `套餐${page}`, description: `plan-${page}` }],
        total: 2
      }
    })
    const options = createProvisionPlanOptions()
    expect((await options.load('套餐')).map((option) => option.id)).toEqual(['plan-1', 'plan-2'])
    expect(http.get).not.toHaveBeenCalled()
  })
  it('命名空间新值能力取服务端明确标志，并提交完整所选scope集合', async () => {
    http.request.mockResolvedValue({ items: [], total: 0, allow_create: false })
    const scopes = ['platform.institutions.associate', 'platform.institutions.provision']
    const result = await loadNamespaceOptions('platform.management_principals.create', scopes)
    expect(result).toEqual({ items: [], allow_create: false })
    const url = new URL(String(http.request.mock.lastCall?.[1]), 'https://example.test')
    expect(url.searchParams.getAll('scopes')).toEqual([...scopes].sort())
    http.request.mockResolvedValue({ items: [], total: 0, allow_create: true })
    expect((await loadNamespaceOptions('platform.institutions.provision', [])).allow_create).toBe(
      true
    )
  })
})
