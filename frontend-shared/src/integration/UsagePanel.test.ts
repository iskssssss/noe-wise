import { HttpClientError } from '../utils/http'
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus, { ElFormItem, ElInput } from 'element-plus'
import UsagePanel from './UsagePanel.vue'
import type { UsageApi, UsageEvent, UsagePage, UsageSummary } from './usage'

const active: VueWrapper[] = []
beforeAll(() => {
  if (!globalThis.ResizeObserver)
    globalThis.ResizeObserver = class {
      observe() {}
      unobserve() {}
      disconnect() {}
    }
})
afterEach(() => {
  active.forEach((wrapper) => wrapper.unmount())
  active.length = 0
  document.body.innerHTML = ''
})
const summary: UsageSummary = {
  request_count: '9223372036854775807',
  invocation_count: 1,
  prompt_tokens: null,
  completion_tokens: null,
  unknown_usage_count: 1,
  unresolved_cost_count: 1,
  costs: [{ amount_cents: '9223372036854775807.125', currency: 'CNY' }]
}
const background: UsageEvent = {
  cursor: 'cursor-original',
  event_id: 'group-background-event',
  source_event_id: 'usage-source',
  tenant_id: '9007199254740991',
  integration_id: 'original-integration',
  origin_kind: 'system',
  actor_ref: 'system:system-agent:900000000000000003',
  responsibility_ref: 'agent:9007199254740993',
  deployment_id: null,
  kind: 'unknown',
  revision: 0,
  adjustment_of_event_id: null,
  event_type: 'llm.chat',
  quantity: null,
  tokens: null,
  prompt_tokens: null,
  completion_tokens: null,
  usage_source: 'UNKNOWN',
  measurement_unit: 'token',
  image_count: null,
  video_count: null,
  characters: null,
  duration_seconds: null,
  pricing_model_id: null,
  pricing_model_revision: null,
  amount_status: 'unresolved',
  amount_cents: null,
  currency: null,
  invocation_id: 'original-invocation',
  attempt_id: 'original-attempt',
  request_id: 'original-request',
  root_input_id: 'original-input',
  source_owner_kind: 'platform',
  source_owner_tenant_id: null,
  agent_definition_id: null,
  agent_revision_id: null,
  agent_revision: null,
  deployment_revision: null,
  grant_id: null,
  grant_revision: null,
  grant_chain: [],
  agent_id: '900000000000000003',
  agent_definition_version: 'system-agent-config:900000000000000003:7',
  end_user_id: '9007199254740997',
  channel_id: null,
  business_scope_id: 'original-store',
  session_id: null,
  run_id: null,
  occurred_at: '2026-10-23T00:00:00Z',
  published_at: '2026-10-23T00:00:01Z',
  group_binding_id: '9007199254740995',
  requester_kind: 'SYSTEM',
  requester_ref: 'system:system-agent:900000000000000003',
  origin_session_id: 'original-group-session',
  origin_event_id: 'original-group-message',
  responsibility_agent_id: '9007199254740993',
  system_agent_run_id: '9007199254740999',
  system_agent_configuration_ref: 'system-agent-config:900000000000000003:7',
  system_agent_invocation_ref: 'system-agent-invocation:9007199254740999'
}
function page(items: UsageEvent[] = [background], hasMore = false): UsagePage {
  return { items, next_cursor: hasMore ? 'opaque-next' : '', has_more: hasMore }
}
function api(details: UsageApi['details'] = async () => page()) {
  return {
    summary: vi.fn<UsageApi['summary']>(async () => summary),
    details: vi.fn(details),
    events: vi.fn<UsageApi['events']>(async () => page())
  }
}
function render(value: ReturnType<typeof api>, identity = 'tenant:current') {
  const wrapper = mount(UsagePanel, {
    props: { api: value, identity },
    global: { plugins: [ElementPlus] }
  })
  active.push(wrapper)
  return wrapper
}
function button(wrapper: VueWrapper, text: string) {
  const found = wrapper.findAll('button').find((item) => item.text() === text)
  if (!found) throw new Error(`按钮不存在：${text}`)
  return found
}
function groupInput(wrapper: VueWrapper) {
  const field = wrapper
    .findAllComponents(ElFormItem)
    .find((item) => item.props('label') === '群标识')
  if (!field) throw new Error('群标识筛选不存在')
  return field.getComponent(ElInput).get('input')
}

describe('共享用量的群与后台来源', () => {
  it('游标失效后重读当前范围首页，不应用未提交的筛选', async () => {
    const value = api(async (query) => {
      if (query.cursor === 'opaque-next')
        throw new HttpClientError({ status: 409, userMessage: '游标已失效' })
      return page([background], true)
    })
    const wrapper = render(value)
    await flushPromises()
    await groupInput(wrapper).setValue('9007199254740995')
    await button(wrapper, '下一页').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('条件已修改，点击查询后生效')
    expect(wrapper.findAll('button').some((item) => item.text() === '重试明细')).toBe(false)
    await button(wrapper, '清空游标并重读当前范围首页').trigger('click')
    await flushPromises()
    expect(value.details.mock.lastCall?.[0]).toMatchObject({ group_binding_id: '', cursor: '' })
    expect(value.summary).toHaveBeenCalledTimes(1)
    expect((groupInput(wrapper).element as HTMLInputElement).value).toBe('9007199254740995')
    expect(wrapper.text()).toContain('条件已修改，点击查询后生效')
    expect(wrapper.get('[role="status"]').text()).toContain('当前第 1 页')
  })

  it('翻页失败时不把上一页记录显示为目标页，允许重试和返回', async () => {
    let failNext = true
    const value = api(async (query) => {
      if (query.cursor === 'opaque-next' && failNext) throw new Error('读取失败')
      return query.cursor === 'opaque-next'
        ? page([{ ...background, event_id: 'second-page' }])
        : page([background], true)
    })
    const wrapper = render(value)
    await flushPromises()
    expect(wrapper.text()).toContain('智能体 …000003')
    expect(wrapper.find('span[title="900000000000000003"]').exists()).toBe(true)
    await button(wrapper, '下一页').trigger('click')
    await flushPromises()
    expect(wrapper.text()).not.toContain('智能体 …000003')
    expect(wrapper.get('[role="status"]').text()).toContain('当前第 2 页，读取失败')
    expect(button(wrapper, '下一页').attributes('disabled')).toBeDefined()
    failNext = false
    await button(wrapper, '重试明细').trigger('click')
    await flushPromises()
    expect(value.details.mock.lastCall?.[0].cursor).toBe('opaque-next')
    expect(wrapper.get('[role="status"]').text()).toContain('显示 1 条记录')
    await button(wrapper, '上一页').trigger('click')
    await flushPromises()
    expect(value.details.mock.lastCall?.[0].cursor).toBe('')
    expect(wrapper.get('[role="status"]').text()).toContain('当前第 1 页')
  })

  it('展开后分别呈现系统请求者、来源作者和责任智能体，精确ID与金额不转换', async () => {
    const wrapper = render(api())
    await flushPromises()
    expect(wrapper.text()).toContain('模型对话 · 待确认用量')
    expect(wrapper.text()).toContain('用量待确认')
    expect(wrapper.text()).toContain('— · 待确认')
    expect(wrapper.text()).toContain('9,223,372,036,854,775,807')
    expect(wrapper.text()).not.toContain('llm.chat')
    await wrapper.get('.el-table__expand-icon').trigger('click')
    await flushPromises()
    const detail = wrapper.get('.usage-source-detail')
    expect(detail.text()).toContain('来源类型系统后台')
    expect(detail.text()).toContain('用量来源待确认')
    expect(detail.text()).toContain('计量单位Token')
    expect(detail.text()).not.toContain('native')
    expect(detail.text()).toContain('实际请求者类型系统主体')
    expect(detail.text()).toContain('来源作者（终端用户）9007199254740997')
    expect(detail.text()).toContain('执行智能体900000000000000003')
    expect(detail.text()).toContain('后台责任智能体9007199254740993')
    expect(detail.text()).toContain('original-group-session')
    expect(detail.text()).toContain('original-group-message')
    expect(detail.text()).toContain('9007199254740999')
    expect(detail.text()).toContain(background.system_agent_configuration_ref)
    expect(detail.text()).toContain(background.system_agent_invocation_ref)
    expect(wrapper.text()).toContain('9223372036854775807.125 CNY')
    expect(wrapper.text()).toContain('待确认用量')
    expect(wrapper.get('[role="status"]').text()).toContain('显示 1 条记录')
  })

  it('群筛选保留字符串，未提交输入不改变翻页，提交后清除旧游标', async () => {
    const value = api(async () => page([background], true))
    const wrapper = render(value)
    await flushPromises()
    await groupInput(wrapper).setValue('9007199254740995')
    await button(wrapper, '下一页').trigger('click')
    await flushPromises()
    expect(value.details.mock.lastCall?.[0]).toMatchObject({
      group_binding_id: '',
      cursor: 'opaque-next'
    })
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(value.details.mock.lastCall?.[0]).toMatchObject({
      group_binding_id: '9007199254740995',
      cursor: ''
    })
    expect(value.summary.mock.lastCall?.[0]).toMatchObject({ group_binding_id: '9007199254740995' })
    expect(wrapper.get('[role="status"]').text()).toContain('当前第 1 页')
    await groupInput(wrapper).setValue('9.007199254740995e15')
    const count = value.details.mock.calls.length
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(document.querySelector('.el-message--error')?.textContent).toContain(
      '群标识必须为完整的正整数'
    )
    expect(value.details).toHaveBeenCalledTimes(count)
  })

  it('目标与读取角色改变取消旧请求，即使旧接口忽略取消也不显示原群结果', async () => {
    let finish!: (value: UsagePage) => void
    let oldSignal: AbortSignal | undefined
    const value = api(async (query, options) => {
      if (query.group_binding_id === background.group_binding_id) {
        oldSignal = options?.signal
        return new Promise<UsagePage>((resolve) => {
          finish = resolve
        })
      }
      return page([])
    })
    const wrapper = render(value)
    await flushPromises()
    value.summary.mockRejectedValueOnce(new Error('旧目标汇总失败'))
    await groupInput(wrapper).setValue(background.group_binding_id!)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    await wrapper.setProps({
      identity: 'headquarters:another-current-target',
      canSummary: false,
      canDetails: false,
      canEvents: true
    })
    await flushPromises()
    expect(oldSignal?.aborted).toBe(true)
    expect(wrapper.text()).not.toContain('旧目标汇总失败')
    expect(value.events).toHaveBeenCalledTimes(1)
    finish(page([{ ...background, event_id: 'late-private-event' }]))
    await flushPromises()
    expect(wrapper.text()).not.toContain('late-private-event')
    expect(value.events.mock.lastCall?.[0]).toMatchObject({ group_binding_id: '', cursor: '' })
  })
})

describe('用量查询任务与历史范围', () => {
  it('工作台固定机构在查询、翻页、记录切换与重置后始终保留，不提供可编辑机构筛选', async () => {
    const value = api(async () => page([background], true))
    const wrapper = mount(UsagePanel, {
      props: {
        api: value,
        identity: 'platform:operator',
        showTenant: true,
        initialTenantId: 'another-initial-tenant',
        fixedTenantId: '9007199254740999'
      },
      global: { plugins: [ElementPlus] }
    })
    active.push(wrapper)
    await flushPromises()
    expect(value.summary.mock.lastCall?.[0]).toMatchObject({ tenant_id: '9007199254740999' })
    expect(value.details.mock.lastCall?.[0]).toMatchObject({ tenant_id: '9007199254740999' })
    expect(wrapper.get('[aria-label="已应用查询范围"]').text()).toBe('机构：9007199254740999')
    expect(wrapper.text()).toContain('查询限定于机构 9007199254740999')
    expect(wrapper.text()).not.toContain('当前身份的全部授权范围')
    expect(wrapper.text()).not.toContain('条件已修改，点击查询后生效')
    expect(
      wrapper.findAllComponents(ElFormItem).some((item) => item.props('label') === '机构标识')
    ).toBe(false)

    await groupInput(wrapper).setValue('9007199254740995')
    expect(wrapper.text()).toContain('条件已修改，点击查询后生效')
    await button(wrapper, '下一页').trigger('click')
    await flushPromises()
    expect(value.details.mock.lastCall?.[0]).toMatchObject({
      tenant_id: '9007199254740999',
      group_binding_id: '',
      cursor: 'opaque-next'
    })
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(value.details.mock.lastCall?.[0]).toMatchObject({
      tenant_id: '9007199254740999',
      group_binding_id: '9007199254740995',
      cursor: ''
    })
    expect(wrapper.text()).not.toContain('条件已修改，点击查询后生效')
    await wrapper
      .findAll('.el-radio-button')
      .find((item) => item.text() === '稳定事件')!
      .get('input')
      .setValue(true)
    await flushPromises()
    expect(value.events.mock.lastCall?.[0]).toMatchObject({
      tenant_id: '9007199254740999',
      group_binding_id: '9007199254740995'
    })
    await button(wrapper, '重置').trigger('click')
    await flushPromises()
    expect(value.summary.mock.lastCall?.[0]).toMatchObject({ tenant_id: '9007199254740999' })
    expect(value.events.mock.lastCall?.[0]).toMatchObject({
      tenant_id: '9007199254740999',
      group_binding_id: '',
      cursor: ''
    })
    expect(wrapper.get('[aria-label="已应用查询范围"]').text()).toBe('机构：9007199254740999')
  })

  it('切换固定机构取消旧读取，隐藏机构筛选时仍显示并查询新机构范围', async () => {
    let finish!: (value: UsagePage) => void
    let oldSignal: AbortSignal | undefined
    const value = api(async (query, options) => {
      if (query.tenant_id === 'original-tenant') {
        oldSignal = options?.signal
        return new Promise<UsagePage>((resolve) => {
          finish = resolve
        })
      }
      return page([])
    })
    const wrapper = mount(UsagePanel, {
      props: { api: value, identity: 'headquarters:operator', fixedTenantId: 'original-tenant' },
      global: { plugins: [ElementPlus] }
    })
    active.push(wrapper)
    await flushPromises()
    await wrapper.setProps({ fixedTenantId: 'next-tenant' })
    await flushPromises()
    expect(oldSignal?.aborted).toBe(true)
    expect(value.summary.mock.lastCall?.[0]).toMatchObject({ tenant_id: 'next-tenant' })
    expect(value.details.mock.lastCall?.[0]).toMatchObject({ tenant_id: 'next-tenant' })
    expect(wrapper.get('[aria-label="已应用查询范围"]').text()).toBe('机构：next-tenant')
    expect(wrapper.text()).not.toContain('条件已修改，点击查询后生效')
    finish(page([{ ...background, event_id: 'late-original-tenant-event' }]))
    await flushPromises()
    expect(wrapper.text()).not.toContain('late-original-tenant-event')
    expect(wrapper.get('[role="status"]').text()).toContain('显示 0 条记录')
  })

  it('只有汇总权限时不渲染记录切换、明细空表或分页', async () => {
    const value = api()
    const wrapper = mount(UsagePanel, {
      props: {
        api: value,
        fixedTenantId: 'summary-only-tenant',
        canSummary: true,
        canDetails: false,
        canEvents: false
      },
      global: { plugins: [ElementPlus] }
    })
    active.push(wrapper)
    await flushPromises()
    expect(value.summary.mock.lastCall?.[0]).toMatchObject({ tenant_id: 'summary-only-tenant' })
    expect(value.details).not.toHaveBeenCalled()
    expect(value.events).not.toHaveBeenCalled()
    expect(wrapper.find('.usage-summary').exists()).toBe(true)
    expect(wrapper.find('[aria-label="用量记录视图"]').exists()).toBe(false)
    expect(wrapper.find('.usage-table').exists()).toBe(false)
    expect(wrapper.find('.usage-record-help').exists()).toBe(false)
    expect(wrapper.find('.actions').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('显示 0 条记录')
  })

  it('从机构入口带入精确历史机构标识，重置不将范围替换为资源目录', async () => {
    const value = api()
    const wrapper = mount(UsagePanel, {
      props: {
        api: value,
        identity: 'headquarters',
        showTenant: true,
        initialTenantId: '9007199254740999'
      },
      global: { plugins: [ElementPlus] }
    })
    active.push(wrapper)
    await flushPromises()
    expect(value.details.mock.lastCall?.[0]).toMatchObject({
      tenant_id: '9007199254740999',
      cursor: ''
    })
    expect(wrapper.get('[aria-label="已应用查询范围"]').text()).toContain('机构：9007199254740999')
    expect(wrapper.text()).toContain('范围不受当前资源目录限制')
    await button(wrapper, '重置').trigger('click')
    await flushPromises()
    expect(value.details.mock.lastCall?.[0]).toMatchObject({ tenant_id: '9007199254740999' })
    await wrapper.setProps({
      identity: 'another-target',
      initialTenantId: 'another-historical-tenant'
    })
    await flushPromises()
    expect(value.details.mock.lastCall?.[0]).toMatchObject({
      tenant_id: 'another-historical-tenant'
    })
  })
  it('切换事件视图沿用已应用条件，填写未查询的标识不会被隐式提交', async () => {
    const value = api()
    const wrapper = render(value)
    await flushPromises()
    await groupInput(wrapper).setValue('9007199254740995')
    expect(wrapper.text()).toContain('条件已修改，点击查询后生效')
    await wrapper
      .findAll('.el-radio-button')
      .find((item) => item.text() === '稳定事件')!
      .get('input')
      .setValue(true)
    await flushPromises()
    expect(value.events.mock.lastCall?.[0]).toMatchObject({ group_binding_id: '', cursor: '' })
    expect(wrapper.get('[aria-label="已应用查询范围"]').text()).toContain('全部授权范围')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(value.events.mock.lastCall?.[0]).toMatchObject({
      group_binding_id: '9007199254740995',
      cursor: ''
    })
    expect(wrapper.get('[aria-label="已应用查询范围"]').text()).toContain('群：9007199254740995')
  })
})
