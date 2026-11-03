import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { ElButton, ElInput, ElSelect, ElSwitch } from 'element-plus'
import { EntitySelect } from '@skillforge/shared/admin'
import { clearFeedback } from '@skillforge/shared/feedback'
import type { MemoryFact } from '../../api'
import MemoryPage from './MemoryPage.vue'

const mocks = vi.hoisted(() => ({
  page: vi.fn(),
  detail: vi.fn(),
  list: vi.fn(),
  reviews: vi.fn(),
  agents: vi.fn(),
  users: vi.fn(),
  review: vi.fn(),
  correct: vi.fn(),
  restore: vi.fn(),
  forget: vi.fn()
}))
vi.mock('../../api', () => ({
  getSessionId: () => 'session-a',
  api: {
    memories: {
      page: (query: {
        status?: string
        scope?: string
        agent_id?: string
        end_user_id?: string
        limit?: number
        q?: string
        cursor?: string
      }) => mocks.page(query),
      list: mocks.list,
      detail: mocks.detail,
      reviews: mocks.reviews,
      review: mocks.review,
      correct: mocks.correct,
      restore: mocks.restore,
      forget: mocks.forget
    },
    agents: { list: mocks.agents },
    chat: { endUsers: mocks.users }
  }
}))
vi.mock('../../stores/auth', async () => {
  const { reactive } = await import('vue')
  const auth = reactive({ identityVersion: 0, sessionIdentity: 'session-a' })
  return { useAuthStore: () => auth }
})
vi.mock('vue-router', () => ({
  onBeforeRouteLeave: vi.fn(),
  onBeforeRouteUpdate: vi.fn()
}))

const wrappers: VueWrapper[] = []
let fact: MemoryFact

function button(wrapper: VueWrapper, label: string) {
  const found = wrapper.findAllComponents(ElButton).find((item) => item.text() === label)
  if (!found) throw Error('未找到按钮 ' + label)
  return found
}

function confirmationButton(label: string) {
  const found = [
    ...document.querySelectorAll<HTMLButtonElement>('.el-message-box__btns button')
  ].find((item) => item.textContent?.trim() === label)
  if (!found) throw Error('未找到确认框按钮 ' + label)
  return found
}

async function render() {
  const wrapper = mount(MemoryPage, {
    attachTo: document.body,
    global: { stubs: { CodeEditor: true } }
  })
  wrappers.push(wrapper)
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  vi.resetAllMocks()
  fact = {
    ref: { tenant_id: { value: 'tenant-a' }, fact_id: 'fact/user' },
    scope: 'USER',
    type: { value: 'preference' },
    subject: 'user-a',
    predicate: 'summary-style',
    object: { schema_id: 'memory.preference', canonical_json: '{"value":"先列三点结论"}' },
    source_event_ids: [{ value: 'source-event-1' }, { value: 'source-event-2' }],
    revision: 3,
    confidence: 0.9,
    importance: 0.8,
    decay_score: 1,
    review_required: true,
    status: 'PENDING_CONFIRM',
    created_at: '2026-10-16T00:00:00Z'
  }
  mocks.list.mockImplementation(async () => [fact])
  mocks.reviews.mockImplementation(async () => [fact])
  mocks.page.mockImplementation(
    async (query: {
      status?: string
      scope?: string
      agent_id?: string
      end_user_id?: string
      limit?: number
    }) => ({
      items:
        query.status === 'PENDING_CONFIRM'
          ? await mocks.reviews()
          : await mocks.list({
              scope: query.scope,
              agent_id: query.agent_id,
              end_user_id: query.end_user_id,
              limit: query.limit
            }),
      next_cursor: null
    })
  )
  mocks.agents.mockResolvedValue({ items: [{ id: 'agent-a', name: '客服助手' }] })
  mocks.users.mockResolvedValue([{ id: 'user-a', display_name: '客户甲' }])
  mocks.review.mockResolvedValue({})
})

afterEach(async () => {
  clearFeedback()
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  await flushPromises()
  document.body.innerHTML = ''
})

it.each(['DECAYED', 'FORGOTTEN'] as const)('%s事实使用原版本恢复为活动状态', async (status) => {
  fact.status = status
  fact.scope = 'USER_AGENT'
  fact.review_required = false
  mocks.restore.mockImplementation(async () => {
    fact = { ...fact, status: 'ACTIVE', revision: 4 }
    return fact
  })
  const wrapper = await render()
  expect(wrapper.findAllComponents(ElButton).some((item) => item.text() === '遗忘')).toBe(false)
  await button(wrapper, '恢复').trigger('click')
  await flushPromises()
  confirmationButton('恢复记忆').click()
  await flushPromises()
  expect(mocks.restore).toHaveBeenCalledExactlyOnceWith('fact/user', 3)
  expect(mocks.forget).not.toHaveBeenCalled()
  expect(button(wrapper, '遗忘').exists()).toBe(true)
})

it.each(['REJECTED', 'ARCHIVED'] as const)('%s事实不展示非法状态迁移入口', async (status) => {
  fact.status = status
  fact.review_required = true
  const wrapper = await render()
  const actions = wrapper.findAllComponents(ElButton).map((item) => item.text())
  expect(actions).not.toContain('遗忘')
  expect(actions).not.toContain('恢复')
  expect(actions).not.toContain('批准')
  expect(actions).not.toContain('拒绝')
})

describe('记忆筛选辅助目录独立恢复', () => {
  it('核对最新事实保留草稿并阻止提交旧版本', async () => {
    fact.scope = 'USER_AGENT'
    fact.status = 'ACTIVE'
    mocks.detail.mockResolvedValue({
      ...fact,
      revision: 4,
      status: 'FORGOTTEN',
      predicate: '最新关系'
    })
    const wrapper = await render()
    await button(wrapper, '纠正').trigger('click')
    const subject = wrapper
      .findAllComponents(ElInput)
      .find((input) => input.props('modelValue') === 'user-a')!
    subject.vm.$emit('update:modelValue', '保留的草稿')
    await button(wrapper, '核对最新状态').trigger('click')
    await flushPromises()
    expect(mocks.detail).toHaveBeenCalledWith('fact/user')
    expect(subject.props('modelValue')).toBe('保留的草稿')
    expect(document.body.textContent).toContain('最新状态：已遗忘，版本 4')
    expect(document.body.textContent).toContain('最新关系：最新关系')
    expect(button(wrapper, '保存纠正').props('disabled')).toBe(true)
    expect(mocks.correct).not.toHaveBeenCalled()
  })
  it('核对失败时保留草稿并允许重新核对', async () => {
    fact.scope = 'USER_AGENT'
    fact.status = 'ACTIVE'
    mocks.detail.mockRejectedValueOnce(new Error('读取失败'))
    const wrapper = await render()
    await button(wrapper, '纠正').trigger('click')
    const subject = wrapper
      .findAllComponents(ElInput)
      .find((input) => input.props('modelValue') === 'user-a')!
    subject.vm.$emit('update:modelValue', '未保存草稿')
    await button(wrapper, '核对最新状态').trigger('click')
    await flushPromises()
    expect(subject.props('modelValue')).toBe('未保存草稿')
    expect(document.body.textContent).toContain('读取失败')
    mocks.detail.mockResolvedValueOnce(fact)
    await button(wrapper, '核对最新状态').trigger('click')
    await flushPromises()
    expect(button(wrapper, '保存纠正').props('disabled')).toBe(false)
    expect(mocks.correct).not.toHaveBeenCalled()
  })
  it('空批次仍有游标时可继续加载，不把尚未扫描完的结果显示为无匹配', async () => {
    mocks.page
      .mockResolvedValueOnce({ items: [], next_cursor: 'next-1' })
      .mockResolvedValueOnce({ items: [fact], next_cursor: null })
    const wrapper = await render()
    expect(wrapper.text()).toContain('当前批次没有匹配记忆，可继续加载更多')
    expect(wrapper.text()).toContain('仍有更多结果')
    await button(wrapper, '加载更多').trigger('click')
    await flushPromises()
    expect(mocks.page.mock.calls[1]![0]).toMatchObject({ cursor: 'next-1', limit: 100 })
    expect(wrapper.text()).toContain('summary-style')
    expect(wrapper.text()).not.toContain('仍有更多结果')
  })
  it('关键词按查询按钮发送至服务端，切换筛选丢弃迟到的旧分页结果', async () => {
    let resolveMore!: (value: unknown) => void
    mocks.page.mockResolvedValueOnce({ items: [fact], next_cursor: 'next-1' })
    mocks.page.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          resolveMore = resolve
        })
    )
    const wrapper = await render()
    await button(wrapper, '加载更多').trigger('click')
    const search = wrapper
      .findAllComponents(ElInput)
      .find((input) => input.props('placeholder') === '搜索主体、关系或内容，点击查询应用')!
    search.vm.$emit('update:modelValue', '旧事实')
    await flushPromises()
    resolveMore({ items: [{ ...fact, predicate: '不应显示的旧分页' }], next_cursor: null })
    await flushPromises()
    expect(wrapper.text()).not.toContain('不应显示的旧分页')
    mocks.page.mockResolvedValueOnce({
      items: [{ ...fact, predicate: '旧事实' }],
      next_cursor: null
    })
    await button(wrapper, '查询').trigger('click')
    await flushPromises()
    expect(mocks.page.mock.calls.at(-1)![0]).toMatchObject({ q: '旧事实', limit: 100 })
    expect(wrapper.text()).toContain('旧事实')
  })
  it('展示已应用的记忆范围，筛选未查询或查询失败不冒充新结果', async () => {
    const wrapper = await render()
    expect(wrapper.text()).toContain('当前结果：范围 全部 · 智能体 全部 · 终端用户 全部')
    wrapper.findComponent(ElSelect).vm.$emit('update:modelValue', 'USER_AGENT')
    wrapper.findAllComponents(EntitySelect)[0]!.vm.$emit('update:modelValue', 'agent-a')
    await flushPromises()
    expect(wrapper.text()).toContain('筛选条件已更改，请点击“查询”应用')
    expect(wrapper.text()).toContain('当前结果：范围 全部 · 智能体 全部')
    mocks.list.mockRejectedValueOnce(new Error('查询失败'))
    await button(wrapper, '查询').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('当前结果：范围 全部 · 智能体 全部')
    expect(wrapper.text()).toContain('筛选条件已更改')
    await button(wrapper, '查询').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('智能体 agent-a')
    expect(wrapper.text()).not.toContain('筛选条件已更改')
  })
  it('首次读取失败不显示空队列，重试成功后显示真实结果', async () => {
    mocks.list.mockRejectedValueOnce(new Error('记忆读取失败'))
    const wrapper = await render()
    expect(wrapper.text()).toContain('记忆列表尚未读取')
    expect(wrapper.text()).not.toContain('没有找到记忆')
    expect(wrapper.text()).not.toContain('当前显示 0 条')
    await button(wrapper, '查询').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('当前显示 1 条记忆')
  })
  it('切换待审核读取失败时说明保留上次结果', async () => {
    mocks.reviews.mockRejectedValueOnce(new Error('审核队列不可用'))
    const wrapper = await render()
    const toggle = wrapper.findComponent({ name: 'ElSwitch' })
    toggle.vm.$emit('update:modelValue', true)
    toggle.vm.$emit('change', true)
    await flushPromises()
    expect(wrapper.text()).toContain('当前显示上次成功读取的结果')
    expect(wrapper.text()).toContain('当前显示 1 条记忆')
    expect(wrapper.text()).not.toContain('当前没有待审核记忆')
    await button(wrapper, '查询').trigger('click')
    await flushPromises()
    expect(wrapper.text()).not.toContain('当前显示上次成功读取的结果')
    expect(wrapper.text()).toContain('当前显示 1 条待审核记忆')
  })
  it('智能体目录失败仍可选终端用户，重读失败目录不重查记忆或覆盖纠正草稿', async () => {
    mocks.agents.mockRejectedValueOnce(new Error('智能体读取被拒绝'))
    fact.scope = 'USER_AGENT'
    const wrapper = await render()
    const selectors = wrapper.findAllComponents(EntitySelect)
    expect(selectors[1]!.props('options')).toEqual([{ id: 'user-a', label: '客户甲' }])
    selectors[1]!.vm.$emit('update:modelValue', 'user-a')
    wrapper.findComponent(ElSelect).vm.$emit('update:modelValue', 'USER_AGENT')
    const filter = wrapper
      .findAllComponents(ElInput)
      .find((input) => input.props('placeholder') === '搜索主体、关系或内容，点击查询应用')!
    filter.vm.$emit('update:modelValue', 'summary')
    await button(wrapper, '纠正').trigger('click')
    await flushPromises()
    const subject = wrapper
      .findAllComponents(ElInput)
      .find((input) => input.props('modelValue') === 'user-a')!
    subject.vm.$emit('update:modelValue', '未保存纠正主体')
    await button(wrapper, '重新读取智能体选项').trigger('click')
    await flushPromises()
    expect(mocks.agents).toHaveBeenCalledTimes(2)
    expect(mocks.users).toHaveBeenCalledTimes(1)
    expect(mocks.list).toHaveBeenCalledTimes(1)
    expect(selectors[0]!.props('options')).toEqual([{ id: 'agent-a', label: '客服助手' }])
    expect(selectors[1]!.props('modelValue')).toBe('user-a')
    expect(wrapper.findComponent(ElSelect).props('modelValue')).toBe('USER_AGENT')
    expect(filter.props('modelValue')).toBe('summary')
    expect(subject.props('modelValue')).toBe('未保存纠正主体')
    expect(mocks.correct).not.toHaveBeenCalled()
    expect(mocks.review).not.toHaveBeenCalled()
    expect(mocks.restore).not.toHaveBeenCalled()
  })
  it('终端用户目录失败不丢成功智能体目录，恢复后查询仍使用原筛选身份', async () => {
    mocks.users.mockRejectedValueOnce(new Error('终端用户目录不可用'))
    const wrapper = await render()
    const selectors = wrapper.findAllComponents(EntitySelect)
    expect(selectors[0]!.props('options')).toEqual([{ id: 'agent-a', label: '客服助手' }])
    selectors[0]!.vm.$emit('update:modelValue', 'agent-a')
    await button(wrapper, '重新读取终端用户选项').trigger('click')
    await flushPromises()
    expect(mocks.agents).toHaveBeenCalledTimes(1)
    expect(mocks.users).toHaveBeenCalledTimes(2)
    expect(mocks.list).toHaveBeenCalledTimes(1)
    expect(selectors[0]!.props('modelValue')).toBe('agent-a')
    selectors[1]!.vm.$emit('update:modelValue', 'user-a')
    await button(wrapper, '查询').trigger('click')
    await flushPromises()
    expect(mocks.list).toHaveBeenLastCalledWith({
      scope: undefined,
      agent_id: 'agent-a',
      end_user_id: 'user-a',
      limit: 100
    })
    expect(mocks.correct).not.toHaveBeenCalled()
    expect(mocks.review).not.toHaveBeenCalled()
    expect(mocks.restore).not.toHaveBeenCalled()
  })
  it('两个辅助目录错误聚合为一条顶部反馈，各自保留只读恢复入口', async () => {
    mocks.agents.mockRejectedValueOnce(new Error('缺少智能体目录权限'))
    mocks.users.mockRejectedValueOnce(new Error('缺少会话读取权限'))
    const wrapper = await render()
    expect(document.querySelectorAll('.el-message')).toHaveLength(1)
    expect(document.querySelector('.el-message')?.textContent).toContain('缺少智能体目录权限')
    expect(document.querySelector('.el-message')?.textContent).toContain('缺少会话读取权限')
    expect(button(wrapper, '重新读取智能体选项').exists()).toBe(true)
    expect(button(wrapper, '重新读取终端用户选项').exists()).toBe(true)
    expect(wrapper.text()).toContain('当前显示 1 条记忆')
  })
  it('切换待审核读取失败保留原结果类型标签与筛选草稿，成功查询后才显示待审核', async () => {
    const wrapper = await render()
    mocks.reviews.mockRejectedValueOnce(new Error('审核队列暂不可用'))
    const toggle = wrapper.findComponent(ElSwitch)
    toggle.vm.$emit('update:modelValue', true)
    toggle.vm.$emit('change', true)
    await flushPromises()
    expect(toggle.props('modelValue')).toBe(true)
    expect(wrapper.text()).toContain('当前显示 1 条记忆')
    expect(wrapper.text()).not.toContain('当前显示 1 条待审核记忆')
    await button(wrapper, '查询').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('当前显示 1 条待审核记忆')
    expect(mocks.list).toHaveBeenCalledTimes(1)
    expect(mocks.reviews).toHaveBeenCalledTimes(2)
  })
})

describe('记忆共享范围审核', () => {
  it('审核成功后列表读取失败保留旧数据并锁定写入，查询成功后使用新修订', async () => {
    fact.scope = 'USER_AGENT'
    const wrapper = await render()
    mocks.list.mockRejectedValueOnce(new Error('列表暂不可读'))
    await button(wrapper, '批准').trigger('click')
    await flushPromises()
    expect(mocks.review).toHaveBeenCalledExactlyOnceWith('fact/user', 3, true)
    expect(wrapper.text()).toContain('操作已完成，但当前列表尚未更新')
    expect(wrapper.text()).toContain('summary-style')
    expect(button(wrapper, '纠正').props('disabled')).toBe(true)
    expect(button(wrapper, '批准').props('disabled')).toBe(true)
    expect(button(wrapper, '拒绝').props('disabled')).toBe(true)
    expect(button(wrapper, '遗忘').props('disabled')).toBe(true)
    button(wrapper, '批准').vm.$emit('click')
    await flushPromises()
    expect(mocks.review).toHaveBeenCalledTimes(1)
    mocks.list.mockResolvedValueOnce([{ ...fact, revision: 4 }])
    await button(wrapper, '查询').trigger('click')
    await flushPromises()
    expect(wrapper.text()).not.toContain('操作已完成，但当前列表尚未更新')
    expect(button(wrapper, '纠正').props('disabled')).toBe(false)
    expect(button(wrapper, '批准').props('disabled')).toBe(false)
    await button(wrapper, '批准').trigger('click')
    await flushPromises()
    expect(mocks.review).toHaveBeenLastCalledWith('fact/user', 4, true)
  })

  it.each(['string', 'object'])('直接纠正%s文本并保留条件和时间等字段', async (kind) => {
    fact.scope = 'USER_AGENT'
    const original = {
      value: kind === 'string' ? '晚上联系' : { text: '晚上联系', detail: '保留' },
      conditions: ['工作日'],
      temporal: { kind: 'stable', from: null, to: null },
      extra: { source: '保留' }
    }
    fact.object.canonical_json = JSON.stringify(original)
    mocks.correct.mockResolvedValue({})
    const wrapper = await render()
    await button(wrapper, '纠正').trigger('click')
    await flushPromises()
    const input = document.querySelector<HTMLTextAreaElement>('textarea[aria-label="记忆内容"]')!
    input.value = '上午联系'
    input.dispatchEvent(new Event('input', { bubbles: true }))
    await flushPromises()
    await button(wrapper, '保存纠正').trigger('click')
    await flushPromises()
    expect(mocks.correct).toHaveBeenCalledTimes(1)
    const payload = mocks.correct.mock.calls[0]![1]
    expect(payload.expected_revision).toBe(3)
    expect(JSON.parse(payload.object_json)).toEqual({
      ...original,
      value: kind === 'string' ? '上午联系' : { text: '上午联系', detail: '保留' }
    })
  })

  it('纠正文本时不改变大整数、转义内容和其他原始 JSON 字节', async () => {
    fact.scope = 'USER_AGENT'
    const original =
      '{"value":{"text":"晚上联系","amount":9007199254740993},"conditions":[],"temporal":{"kind":"stable","from":null,"to":null},"extra":1e+30}'
    fact.object.canonical_json = original
    mocks.correct.mockResolvedValue({})
    const wrapper = await render()
    await button(wrapper, '纠正').trigger('click')
    await flushPromises()
    const input = document.querySelector<HTMLTextAreaElement>('textarea[aria-label="记忆内容"]')!
    input.value = '上午"联系'
    input.dispatchEvent(new Event('input', { bubbles: true }))
    await flushPromises()
    await button(wrapper, '保存纠正').trigger('click')
    await flushPromises()
    expect(mocks.correct.mock.calls[0]![1].object_json).toBe(
      original.replace('"晚上联系"', JSON.stringify('上午"联系'))
    )
  })

  it('重复的正文键不提供有歧义的简易编辑', async () => {
    fact.scope = 'USER_AGENT'
    fact.object.canonical_json =
      '{"value":{"text":"一","text":"二"},"conditions":[],"temporal":{"kind":"stable","from":null,"to":null}}'
    const wrapper = await render()
    await button(wrapper, '纠正').trigger('click')
    await flushPromises()
    expect(document.querySelector('textarea[aria-label="记忆内容"]')).toBeNull()
    expect(document.body.textContent).toContain('结构化事实内容')
  })

  it('非文本记忆继续使用结构化编辑', async () => {
    fact.scope = 'USER_AGENT'
    fact.object.canonical_json = JSON.stringify({
      value: 42,
      conditions: [],
      temporal: { kind: 'stable', from: null, to: null }
    })
    const wrapper = await render()
    await button(wrapper, '纠正').trigger('click')
    await flushPromises()
    expect(document.querySelector('textarea[aria-label="记忆内容"]')).toBeNull()
    expect(document.body.textContent).toContain('结构化事实内容')
  })

  it('纠正成功后读取失败仍保留结果并只允许读取恢复', async () => {
    fact.scope = 'USER_AGENT'
    mocks.correct.mockResolvedValue({})
    const wrapper = await render()
    await button(wrapper, '纠正').trigger('click')
    await flushPromises()
    mocks.list.mockRejectedValueOnce(new Error('列表暂不可读'))
    await button(wrapper, '保存纠正').trigger('click')
    await flushPromises()
    expect(mocks.correct).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('操作已完成，但当前列表尚未更新')
    expect(button(wrapper, '纠正').props('disabled')).toBe(true)
    expect(button(wrapper, '批准').props('disabled')).toBe(true)
    await button(wrapper, '查询').trigger('click')
    await flushPromises()
    expect(wrapper.text()).not.toContain('操作已完成，但当前列表尚未更新')
    expect(mocks.correct).toHaveBeenCalledTimes(1)
  })

  it('展示范围、来源和正文，实际确认点击后才提交绑定的事实修订和 USER 范围', async () => {
    const wrapper = await render()
    await button(wrapper, '批准').trigger('click')
    await flushPromises()

    const confirmation = document.querySelector('.el-message-box')!
    expect(confirmation.textContent).toContain('确认跨助手共享')
    expect(confirmation.textContent).toContain('范围：用户（USER，跨助手共享）')
    expect(confirmation.textContent).toContain('来源事件：source-event-1、source-event-2')
    expect(confirmation.textContent).toContain('内容：{"value":"先列三点结论"}')
    expect(mocks.review).not.toHaveBeenCalled()
    expect(button(wrapper, '批准').props('disabled')).toBe(true)

    // 确认期间刷新或对象变化不能把确认转用到其他事实或修订。
    fact.ref.fact_id = 'fact/changed'
    fact.revision = 4
    fact.object.canonical_json = '{"value":"变更后的内容"}'
    confirmationButton('确认跨助手共享').click()
    await flushPromises()

    expect(mocks.review).toHaveBeenCalledExactlyOnceWith('fact/user', 3, true, 'USER')
  })

  it('明确显示原事实及目标版本，确认替换后才批准修订提案', async () => {
    fact.scope = 'USER_AGENT'
    fact.object = {
      schema_id: 'skillforge.memory.preference.v2',
      canonical_json: JSON.stringify({
        value: { text: '晚上联系' },
        conditions: [],
        temporal: { kind: 'stable', from: null, to: null },
        revisionProposal: { factId: 'fact/original', expectedRevision: 7 }
      })
    }
    const wrapper = await render()
    expect(wrapper.text()).toContain('批准后替换事实 fact/original 的第 7 版')
    await button(wrapper, '批准').trigger('click')
    await flushPromises()
    expect(document.querySelector('.el-message-box')?.textContent).toContain(
      '批准后替换事实 fact/original 的第 7 版'
    )
    expect(document.querySelector('.el-message-box')?.textContent).toContain(
      '内容：{"value":{"text":"晚上联系"},"conditions":[],"temporal":{"kind":"stable","from":null,"to":null}}'
    )
    expect(document.querySelector('.el-message-box')?.textContent).not.toContain('revisionProposal')
    expect(document.querySelector('.el-message-box')?.textContent).not.toContain('expectedRevision')
    expect(fact.object.canonical_json).toContain('"revisionProposal"')
    expect(mocks.review).not.toHaveBeenCalled()
    confirmationButton('确认替换').click()
    await flushPromises()
    expect(mocks.review).toHaveBeenCalledExactlyOnceWith('fact/user', 3, true, undefined)
  })

  it('取消跨助手共享确认不发送任何审核请求', async () => {
    const wrapper = await render()
    await button(wrapper, '批准').trigger('click')
    await flushPromises()
    confirmationButton('取消').click()
    await flushPromises()

    expect(mocks.review).not.toHaveBeenCalled()
    expect(button(wrapper, '批准').props('disabled')).toBe(false)
  })

  it.each([
    { scope: 'USER_AGENT' as const, approved: true, label: '批准' },
    { scope: 'USER' as const, approved: false, label: '拒绝' }
  ])('$scope $label沿用普通审核且不补填范围确认', async ({ scope, approved, label }) => {
    fact.scope = scope
    const wrapper = await render()
    await button(wrapper, label).trigger('click')
    await flushPromises()

    expect(document.querySelector('.el-message-box')).toBeNull()
    expect(mocks.review).toHaveBeenCalledExactlyOnceWith('fact/user', 3, approved)
  })

  it('待确认 USER 记忆的纠正入口提示先审核，不提交纠正或恢复', async () => {
    const wrapper = await render()
    expect(wrapper.text()).toContain('请先审核跨助手共享')
    expect(button(wrapper, '纠正').props('disabled')).toBe(true)
    await button(wrapper, '纠正').trigger('click')

    expect(mocks.correct).not.toHaveBeenCalled()
    expect(mocks.restore).not.toHaveBeenCalled()
  })
})
