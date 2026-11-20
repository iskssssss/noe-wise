import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { computed, defineComponent, type Component } from 'vue'
import { createPermissionDirective } from '@skillforge/shared/directives/permission'
import { EntitySelect } from '@skillforge/shared/admin'
import { ElDatePicker, ElInput, ElOption, ElPagination, ElSelect } from 'element-plus'
import AgentTemplatesPage from './AgentTemplatesPage.vue'
import ReviewPage from './ReviewPage.vue'
import OperationsPage from './OperationsPage.vue'
import SessionsPage from './SessionsPage.vue'
const currentAuth = vi.hoisted(() => ({
  identityVersion: 1,
  user: {
    sub: 'platform-actor',
    permissions: [
      'foundation.tenants.create',
      'platform.agent_definitions.create',
      'foundation.marketplace.publish',
      'foundation.marketplace.update',
      'foundation.billing.create',
      'foundation.billing.update',
      'foundation.billing.run'
    ]
  }
}))
vi.mock('../../stores/auth', () => ({ useAuthStore: () => currentAuth }))
const mocks = vi.hoisted(() => ({
  tenants: vi.fn(),
  templates: vi.fn(),
  definitionOptions: vi.fn(),
  reviews: vi.fn(),
  audit: vi.fn(),
  sessions: vi.fn()
}))
vi.mock('../../api', () => ({
  api: {
    admin: { listTenants: mocks.tenants },
    agentTemplates: { list: mocks.templates, options: mocks.definitionOptions },
    marketplace: { reviews: mocks.reviews },
    operations: {
      auditLogs: mocks.audit,
      auditOptions: async () => ({ actions: [], actors: [], resourceTypes: [] })
    },
    sessions: { list: mocks.sessions }
  }
}))
vi.mock('../../api/entityOptions', () => ({
  loadTenantOptions: async () => [
    { id: 'A', label: '机构A' },
    { id: 'B', label: '机构B' }
  ],
  resolveTenantOption: async (id: string) => ({ id, label: id })
}))
const wrappers: VueWrapper[] = []
async function open(component: Component, path = '/') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/', component }]
  })
  await router.push(path)
  await router.isReady()
  const wrapper = mount(
    component === AgentTemplatesPage
      ? defineComponent({ template: '<RouterView :key="$route.path" />' })
      : component,
    {
      attachTo: document.body,
      global: {
        plugins: [router],
        directives: {
          permission: createPermissionDirective(() => computed(() => currentAuth.user.permissions))
        }
      }
    }
  )
  wrappers.push(wrapper)
  await flushPromises()
  return wrapper
}
beforeEach(() => {
  vi.clearAllMocks()
  mocks.definitionOptions.mockResolvedValue({
    statuses: [
      { value: 'draft', label: '服务端草稿' },
      { value: 'published', label: '已发布' }
    ],
    distribution_policies: [],
    price_types: []
  })
  for (const loader of [mocks.tenants, mocks.templates, mocks.reviews, mocks.audit, mocks.sessions])
    loader.mockResolvedValue({ items: [], total: 100 })
})
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  vi.restoreAllMocks()
  vi.useRealTimers()
  document.body.innerHTML = ''
})
describe('平台读取请求快照', () => {
  it('模板详情返回后恢复已查询范围，未提交的输入不改变返回上下文', async () => {
    mocks.templates.mockResolvedValue({
      total: 100,
      items: [{ definition_id: 'template-1', name: '客户助手', status: 'published' }]
    })
    const wrapper = await open(
      AgentTemplatesPage,
      '/?list_name=客户&list_status=published&list_page=3'
    )
    expect(mocks.templates.mock.lastCall?.[0]).toMatchObject({
      name: '客户',
      status: 'published',
      page: 3
    })
    const input = wrapper.get('input[aria-label="公共定义名称"]')
    expect((input.element as HTMLInputElement).value).toBe('客户')
    await input.setValue('尚未查询')
    const detail = wrapper.findAll('a').find((link) => link.text() === '客户助手')!
    const target = new URL(detail.attributes('href')!, 'http://test.local')
    expect(target.pathname).toBe('/platform/agent-templates/template-1')
    expect(Object.fromEntries(target.searchParams)).toEqual({
      list_name: '客户',
      list_status: 'published',
      list_page: '3'
    })
    const create = wrapper.findAll('a').find((link) => link.text() === '新增公共定义')!
    expect(
      new URL(create.attributes('href')!, 'http://test.local').searchParams.get('list_page')
    ).toBe('3')
    const restored = await open(AgentTemplatesPage, '/?' + target.searchParams.toString())
    expect(mocks.templates.mock.lastCall?.[0]).toMatchObject({
      name: '客户',
      status: 'published',
      page: 3
    })
    expect(
      (restored.get('input[aria-label="公共定义名称"]').element as HTMLInputElement).value
    ).toBe('客户')
  })

  it('定义状态交给服务端校验，非法页码仍归一为第一页', async () => {
    await open(AgentTemplatesPage, '/?list_status=invalid&list_page=-2')
    expect(mocks.templates.mock.lastCall?.[0]).toMatchObject({ status: 'invalid', page: 1 })
  })

  it('读取服务端状态选项，失败后独立重试而不重查列表', async () => {
    mocks.definitionOptions.mockRejectedValueOnce(new Error('选项读取失败'))
    const wrapper = await open(AgentTemplatesPage)
    expect(wrapper.getComponent(ElSelect).props('disabled')).toBe(true)
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '重新读取筛选选项')!
      .trigger('click')
    await flushPromises()
    expect(mocks.definitionOptions).toHaveBeenCalledTimes(2)
    expect(mocks.templates).toHaveBeenCalledTimes(1)
    expect(wrapper.getComponent(ElSelect).props('disabled')).toBe(false)
    expect(wrapper.findAllComponents(ElOption).map((option) => option.props('label'))).toContain(
      '服务端草稿'
    )
  })

  it('仅显示真实发布修订，未发布草稿不显示预设版本', async () => {
    mocks.templates.mockResolvedValue({
      total: 2,
      items: [
        {
          definition_id: '1',
          name: '未发布样本',
          version: '1.0.0',
          current_published_revision: null
        },
        {
          definition_id: '2',
          name: '已发布样本',
          version: '2.0.0',
          current_published_revision: '1.5.0'
        }
      ]
    })
    const wrapper = await open(AgentTemplatesPage)
    expect(wrapper.text()).toContain('尚未发布')
    expect(wrapper.text()).toContain('1.5.0')
    expect(wrapper.text()).not.toContain('1.0.0')
    expect(wrapper.text()).not.toContain('2.0.0')
  })

  it('查询和重置同步地址，真实路由同步后只读取已提交条件', async () => {
    const wrapper = await open(
      AgentTemplatesPage,
      '/?list_name=旧条件&list_status=draft&list_page=3'
    )
    await wrapper.get('input[aria-label="公共定义名称"]').setValue('新条件')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.vm.$router.currentRoute.value.query).toEqual({
      list_name: '新条件',
      list_status: 'draft',
      list_page: '1'
    })
    expect(mocks.templates.mock.lastCall?.[0]).toMatchObject({
      name: '新条件',
      status: 'draft',
      page: 1
    })
    expect(mocks.templates).toHaveBeenCalledTimes(2)
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '重置')!
      .trigger('click')
    await flushPromises()
    expect(wrapper.vm.$router.currentRoute.value.query).toEqual({ list_page: '1' })
    expect(mocks.templates.mock.lastCall?.[0]).toMatchObject({ name: '', status: '', page: 1 })
    expect(mocks.templates).toHaveBeenCalledTimes(3)
    const refreshed = await open(AgentTemplatesPage, wrapper.vm.$router.currentRoute.value.fullPath)
    expect(
      (refreshed.get('input[aria-label="公共定义名称"]').element as HTMLInputElement).value
    ).toBe('')
  })

  it('无结果时不提示点击不存在的名称，仍提供查询重置入口', async () => {
    mocks.templates.mockResolvedValue({ total: 0, items: [] })
    const wrapper = await open(AgentTemplatesPage, '/?list_name=无匹配')
    expect(wrapper.text()).not.toContain('点击名称维护')
    expect(wrapper.text()).toContain('可调整名称或状态后查询')
    expect(wrapper.findAll('button').some((button) => button.text() === '重置')).toBe(true)
  })

  it('会话翻页失败标明保留的页码，重试原页后展示新结果', async () => {
    mocks.sessions.mockResolvedValueOnce({
      total: 30,
      items: [
        {
          session_id: 'first',
          tenant_id: 't',
          answer_status: 'NONE',
          execution_status: 'COMPLETED'
        }
      ]
    })
    const wrapper = await open(SessionsPage)
    mocks.sessions.mockRejectedValueOnce(new Error('读取失败'))
    wrapper.getComponent(ElPagination).vm.$emit('current-change', 2)
    await flushPromises()
    expect(wrapper.text()).toContain('第 2 页读取失败，当前保留第 1 页的结果')
    expect(wrapper.text()).toContain('first')
    expect(wrapper.getComponent(ElPagination).props('currentPage')).toBe(1)
    mocks.sessions.mockResolvedValueOnce({
      total: 30,
      items: [
        {
          session_id: 'second',
          tenant_id: 't',
          answer_status: 'NONE',
          execution_status: 'COMPLETED'
        }
      ]
    })
    await wrapper
      .findAll('button')
      .find((item) => item.text() === '重新读取会话')!
      .trigger('click')
    await flushPromises()
    expect(mocks.sessions.mock.lastCall?.[0]).toMatchObject({ page: 2 })
    expect(wrapper.text()).toContain('second')
    expect(wrapper.text()).not.toContain('当前保留第 1 页')
    expect(wrapper.getComponent(ElPagination).props('currentPage')).toBe(2)
  })
  it('会话筛选说明回答或执行任一状态，支持查找无回答', async () => {
    mocks.sessions.mockResolvedValue({
      total: 1,
      items: [
        { session_id: 's', tenant_id: 't', answer_status: 'NONE', execution_status: 'COMPLETED' }
      ]
    })
    const wrapper = await open(SessionsPage)
    expect(wrapper.text()).toContain('状态匹配公开回复或执行任一状态')
    expect(wrapper.text()).toContain('暂无回复')
    const select = wrapper
      .findAllComponents(ElSelect)
      .find((item) => item.props('ariaLabel') === '回复或执行状态')!
    expect(select.findAllComponents(ElOption).map((item) => item.props('value'))).toContain('NONE')
    select.vm.$emit('update:modelValue', 'NONE')
    await wrapper.get('form.filters').trigger('submit')
    await flushPromises()
    expect(mocks.sessions.mock.lastCall?.[0]).toMatchObject({ status: 'NONE', page: 1 })
  })
  it.each([
    ['审计', OperationsPage],
    ['会话', SessionsPage]
  ] as const)('%s起止时间的真实输入关联唯一、可见且非空的就近说明', async (_label, component) => {
    const wrapper = await open(component)
    if (component === SessionsPage) {
      const toggle = wrapper.findAll('button').find((button) => button.text() === '精确筛选')!
      expect(toggle.attributes('aria-expanded')).toBe('false')
      await toggle.trigger('click')
      expect(toggle.attributes('aria-expanded')).toBe('true')
    }
    const help = wrapper.get('.time-range-filter .field-tip')
    const helpId = help.attributes('id')
    expect(helpId).toBeTruthy()
    expect(help.isVisible()).toBe(true)
    expect(help.text()).toContain('北京时间')
    expect(help.text()).toContain('清空')
    expect(help.text()).toContain('查询')
    const inputs = wrapper.findAll('.time-range-filter input.el-range-input')
    expect(inputs.map((input) => input.attributes('placeholder'))).toEqual(['开始时间', '结束时间'])
    for (const input of inputs) {
      expect(input.attributes('aria-describedby')?.split(/\s+/).filter(Boolean)).toEqual([helpId])
    }
    expect(
      [...document.querySelectorAll('[id]')].filter((element) => element.id === helpId)
    ).toHaveLength(1)
    wrapper.getComponent(ElDatePicker).vm.$emit('update:modelValue', null)
    await flushPromises()
    for (const input of wrapper.findAll('.time-range-filter input.el-range-input')) {
      expect(input.attributes('aria-describedby')?.split(/\s+/).filter(Boolean)).toEqual([helpId])
    }
    expect(help.isVisible()).toBe(true)
    expect(help.text().trim()).not.toBe('')
  })

  it.each([
    ['审计', OperationsPage, mocks.audit, 'from', 'to'],
    ['会话', SessionsPage, mocks.sessions, 'started_from', 'started_to']
  ] as const)(
    '%s时间范围按北京时间提交，未提交编辑保持已应用快照',
    async (_label, component, loader, fromKey, toKey) => {
      const wrapper = await open(component)
      if (component === SessionsPage)
        await wrapper
          .findAll('button')
          .find((button) => button.text() === '精确筛选')!
          .trigger('click')
      const picker = wrapper.getComponent(ElDatePicker)
      expect(picker.props('valueFormat')).toBe('YYYY-MM-DD HH:mm:ss')
      picker.vm.$emit('update:modelValue', ['2026-12-31 23:59:59', '2027-01-01 00:00:00'])
      await flushPromises()
      expect(picker.props('modelValue')).toEqual(['2026-12-31 23:59:59', '2027-01-01 00:00:00'])
      await wrapper.find('form.admin-filters, form.filters').trigger('submit')
      await flushPromises()
      const expected = {
        [fromKey]: '2026-12-31T23:59:59+08:00',
        [toKey]: '2027-01-01T00:00:00+08:00'
      }
      expect(loader.mock.lastCall?.[0]).toMatchObject(expected)
      picker.vm.$emit('update:modelValue', ['2027-01-02 00:00:00', '2027-01-03 00:00:00'])
      await flushPromises()
      wrapper.getComponent(ElPagination).vm.$emit('current-change', 2)
      await flushPromises()
      expect(loader.mock.lastCall?.[0]).toMatchObject({ page: 2, ...expected })
      picker.vm.$emit('update:modelValue', null)
      await flushPromises()
      // Element Plus 将缺失的 modelValue 规范为空字符串；请求边界仍必须取消筛选。
      expect(picker.props('modelValue')).toBe('')
      await wrapper.find('form.admin-filters, form.filters').trigger('submit')
      await flushPromises()
      expect(loader.mock.lastCall?.[0]).toMatchObject({ [fromKey]: undefined, [toKey]: undefined })
    }
  )
  it.each([
    ['模板', AgentTemplatesPage, mocks.templates, 'name'],
    ['审核', ReviewPage, mocks.reviews, 'search'],
    ['审计', OperationsPage, mocks.audit, 'action'],
    ['会话', SessionsPage, mocks.sessions, 'session_id']
  ] as const)(
    '%s筛选编辑不改变结果页，翻页仅用已应用条件',
    async (_label, component, loader, key) => {
      const wrapper = await open(component)
      const pager = wrapper.getComponent(ElPagination)
      pager.vm.$emit('current-change', 3)
      await flushPromises()
      expect(pager.props('currentPage')).toBe(3)
      const count = loader.mock.calls.length
      const inputs = wrapper.find('form.admin-filters, form.filters').findAllComponents(ElInput)
      if (component === SessionsPage)
        await wrapper
          .findAll('button')
          .find((button) => button.text() === '精确筛选')!
          .trigger('click')
      const input =
        component === SessionsPage
          ? inputs.find(
              (field: VueWrapper) => field.get('input').attributes('placeholder') === '会话 ID'
            )!
          : inputs[0]!
      const filterValue = component === OperationsPage ? 'auth.login' : 'new-filter'
      if (component === OperationsPage)
        wrapper.findAllComponents(ElSelect)[0]!.vm.$emit('update:modelValue', filterValue)
      else if (component === SessionsPage)
        wrapper
          .findAllComponents(EntitySelect)
          .find((field) => field.props('placeholder') === '选择会话')!
          .vm.$emit('update:modelValue', 'session-1')
      else await input.get('input').setValue(filterValue)
      await flushPromises()
      expect(loader).toHaveBeenCalledTimes(count)
      expect(pager.props('currentPage')).toBe(3)
      pager.vm.$emit('current-change', 4)
      await flushPromises()
      expect(loader.mock.lastCall?.[0]).toMatchObject({ page: 4, [key]: '' })
      await wrapper.find('form.admin-filters, form.filters').trigger('submit')
      await flushPromises()
      expect(loader.mock.lastCall?.[0]).toMatchObject({
        page: 1,
        [key]: component === SessionsPage ? 'session-1' : filterValue
      })
      loader.mockRejectedValueOnce(new Error('加载失败'))
      pager.vm.$emit('current-change', 2)
      await flushPromises()
      expect(pager.props('currentPage')).toBe(1)
    }
  )
})
