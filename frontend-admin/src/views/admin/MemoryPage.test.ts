import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { reactive } from 'vue'
import { ElButton, ElDrawer, ElInput, ElSelect, ElSwitch } from 'element-plus'
import { EntitySelect } from '@skillforge/shared/admin'
import { clearFeedback } from '@skillforge/shared/feedback'
import { HttpClientError } from '@skillforge/shared/utils/http'
import type { MemoryFact } from '../../api'
import MemoryPage from './MemoryPage.vue'
import { useAuthStore } from '../../stores/auth'

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
  const auth = reactive({
    identityVersion: 0,
    sessionIdentity: 'session-a',
    user: { tenant_id: 'tenant-a', sub: 'operator-a' }
  })
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
  ].findLast((item) => item.textContent?.trim() === label)
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

async function forgetFrom(wrapper: VueWrapper, index = 0) {
  const actions = wrapper.findAllComponents(ElButton).filter((item) => item.text() === '遗忘')
  await actions[index]!.trigger('click')
  await flushPromises()
  confirmationButton('遗忘记忆').click()
  await flushPromises()
}

beforeEach(() => {
  vi.resetAllMocks()
  sessionStorage.clear()
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
  mocks.review.mockImplementation(async (_id: string, revision: number, approved: boolean) => ({
    ...fact,
    revision: revision + 1,
    status: approved ? 'ACTIVE' : 'REJECTED'
  }))
})

afterEach(async () => {
  clearFeedback()
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  await flushPromises()
  document.body.innerHTML = ''
  vi.restoreAllMocks()
})

it('旧页面遗忘回执迟到不能清除新页面另一事实的未知记录', async () => {
  fact.status = 'ACTIVE'
  const second = {
    ...fact,
    ref: { ...fact.ref, fact_id: 'fact/second' },
    revision: 7,
    predicate: 'second-fact'
  }
  mocks.list.mockResolvedValue([fact, second])
  let finish!: (value: unknown) => void
  mocks.forget.mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        finish = resolve
      })
  )
  const old = await render()
  await forgetFrom(old, 0)
  expect(mocks.forget).toHaveBeenCalledExactlyOnceWith('fact/user', 3)
  old.unmount()
  wrappers.splice(wrappers.indexOf(old), 1)
  mocks.forget.mockRejectedValueOnce(new Error('第二事实回执丢失'))
  const current = await render()
  await forgetFrom(current, 1)
  expect(mocks.forget).toHaveBeenNthCalledWith(2, 'fact/second', 7)
  finish({ ...fact, revision: 4, status: 'FORGOTTEN' })
  await flushPromises()
  current.unmount()
  wrappers.splice(wrappers.indexOf(current), 1)
  const reloaded = await render()
  const cards = reloaded.findAll('[aria-label="记忆状态结果核对"]')
  expect(
    cards.some((card) => card.text().includes('fact/second') && card.text().includes('原修订 7'))
  ).toBe(true)
  expect(mocks.forget).toHaveBeenCalledTimes(2)
})

it('多个事实的遗忘未知互不覆盖，重载保留各自原修订', async () => {
  fact.status = 'ACTIVE'
  const second = {
    ...fact,
    ref: { ...fact.ref, fact_id: 'fact/second' },
    revision: 7,
    predicate: 'second-fact'
  }
  mocks.list.mockResolvedValue([fact, second])
  mocks.forget.mockRejectedValue(new Error('响应丢失'))
  const wrapper = await render()
  await forgetFrom(wrapper, 0)
  const actions = wrapper.findAllComponents(ElButton).filter((item) => item.text() === '遗忘')
  expect(actions[0]!.props('disabled')).toBe(true)
  expect(actions[1]!.props('disabled')).toBe(false)
  await forgetFrom(wrapper, 1)
  expect(mocks.forget).toHaveBeenCalledTimes(2)
  expect(mocks.forget).toHaveBeenNthCalledWith(1, 'fact/user', 3)
  expect(mocks.forget).toHaveBeenNthCalledWith(2, 'fact/second', 7)
  wrapper.unmount()
  wrappers.splice(wrappers.indexOf(wrapper), 1)
  const reloaded = await render()
  const cards = reloaded.findAll('[aria-label="记忆状态结果核对"]')
  expect(cards).toHaveLength(2)
  expect(cards[0]!.text()).toContain('fact/user')
  expect(cards[0]!.text()).toContain('原修订 3')
  expect(cards[1]!.text()).toContain('fact/second')
  expect(cards[1]!.text()).toContain('原修订 7')
  expect(
    reloaded
      .findAllComponents(ElButton)
      .filter((item) => item.text() === '遗忘')
      .every((item) => item.props('disabled'))
  ).toBe(true)
  expect(mocks.forget).toHaveBeenCalledTimes(2)
})

it('遗忘未知时同修订核对不解锁，更高修订只读核对后解锁', async () => {
  fact.status = 'ACTIVE'
  mocks.forget.mockRejectedValue(new Error('响应丢失'))
  mocks.detail
    .mockResolvedValueOnce(fact)
    .mockResolvedValueOnce({ ...fact, revision: 4, status: 'FORGOTTEN' })
  const wrapper = await render()
  await forgetFrom(wrapper)
  await button(wrapper, '核对最新状态').trigger('click')
  await flushPromises()
  expect(button(wrapper, '遗忘').props('disabled')).toBe(true)
  expect(wrapper.text()).toContain('遗忘结果尚未确认')
  mocks.list.mockResolvedValueOnce([{ ...fact, revision: 4, status: 'FORGOTTEN' }])
  await button(wrapper, '核对最新状态').trigger('click')
  await flushPromises()
  expect(wrapper.text()).not.toContain('遗忘结果尚未确认')
  expect(button(wrapper, '恢复').props('disabled')).toBe(false)
  expect(mocks.detail).toHaveBeenCalledTimes(2)
  expect(mocks.forget).toHaveBeenCalledExactlyOnceWith('fact/user', 3)
  expect(mocks.restore).not.toHaveBeenCalled()
})

it('遗忘核对记录写入失败时不发送请求', async () => {
  fact.status = 'ACTIVE'
  const wrapper = await render()
  vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
    throw new Error('存储不可写')
  })
  await forgetFrom(wrapper)
  expect(mocks.forget).not.toHaveBeenCalled()
  expect(document.body.textContent).toContain('尚未发送')
})

it('遗忘明确业务拒绝后允许用户再次操作', async () => {
  fact.status = 'ACTIVE'
  mocks.forget.mockRejectedValueOnce(
    new HttpClientError({ status: 422, code: 'VALIDATION_ERROR', userMessage: '遗忘被拒绝' })
  )
  const wrapper = await render()
  await forgetFrom(wrapper)
  expect(mocks.forget).toHaveBeenCalledExactlyOnceWith('fact/user', 3)
  expect(button(wrapper, '遗忘').props('disabled')).toBe(false)
  expect(document.body.textContent).toContain('遗忘被拒绝')
})

it('遗忘成功后列表读取失败只重读，不重复遗忘', async () => {
  fact.status = 'ACTIVE'
  mocks.forget.mockResolvedValue({ ...fact, status: 'FORGOTTEN', revision: 4 })
  const wrapper = await render()
  mocks.list.mockRejectedValueOnce(new Error('列表暂不可读'))
  await forgetFrom(wrapper)
  expect(mocks.forget).toHaveBeenCalledExactlyOnceWith('fact/user', 3)
  expect(wrapper.text()).toContain('操作已完成，但当前列表尚未更新')
  expect(button(wrapper, '遗忘').props('disabled')).toBe(true)
  mocks.list.mockResolvedValueOnce([{ ...fact, status: 'FORGOTTEN', revision: 4 }])
  await button(wrapper, '查询').trigger('click')
  await flushPromises()
  expect(mocks.forget).toHaveBeenCalledTimes(1)
  expect(button(wrapper, '恢复').props('disabled')).toBe(false)
})

it('多个事实的未知纠正互不覆盖，关闭重开仍禁止再次提交', async () => {
  fact.status = 'ACTIVE'
  const second = { ...fact, ref: { ...fact.ref, fact_id: 'fact/second' } }
  mocks.list.mockResolvedValue([fact, second])
  mocks.correct.mockRejectedValue(new Error('响应丢失'))
  const wrapper = await render()
  const editButtons = () =>
    wrapper.findAllComponents(ElButton).filter((item) => item.text() === '纠正')
  await editButtons()[0]!.trigger('click')
  await flushPromises()
  await button(wrapper, '保存纠正').trigger('click')
  await flushPromises()
  await button(wrapper, '取消').trigger('click')
  await flushPromises()
  await editButtons()[1]!.trigger('click')
  await flushPromises()
  await button(wrapper, '保存纠正').trigger('click')
  await flushPromises()
  expect(mocks.correct).toHaveBeenCalledTimes(2)
  await button(wrapper, '取消').trigger('click')
  await flushPromises()
  await editButtons()[0]!.trigger('click')
  await flushPromises()
  expect(button(wrapper, '保存纠正').props('disabled')).toBe(true)
  button(wrapper, '保存纠正').vm.$emit('click')
  await flushPromises()
  expect(mocks.correct).toHaveBeenCalledTimes(2)
  for (const id of ['fact/user', 'fact/second'])
    expect(
      sessionStorage.getItem(
        `memory-correction-unknown:${JSON.stringify(['tenant-a', 'operator-a', id])}`
      )
    ).toBe('1')
})

it('未知纠正核对仍为原修订时保持锁定，页面重载后仍保留', async () => {
  fact.status = 'ACTIVE'
  mocks.correct.mockRejectedValue(new Error('响应丢失'))
  mocks.detail.mockResolvedValue(fact)
  const wrapper = await render()
  await button(wrapper, '纠正').trigger('click')
  await flushPromises()
  await button(wrapper, '保存纠正').trigger('click')
  await flushPromises()
  await button(wrapper, '核对最新状态').trigger('click')
  await flushPromises()
  expect(mocks.detail).toHaveBeenCalledExactlyOnceWith('fact/user')
  expect(button(wrapper, '保存纠正').props('disabled')).toBe(true)
  expect(document.body.textContent).toContain('状态未变化也不能证明原请求未受理')
  wrapper.unmount()
  wrappers.splice(wrappers.indexOf(wrapper), 1)
  const reloaded = await render()
  await button(reloaded, '纠正').trigger('click')
  await flushPromises()
  expect(button(reloaded, '保存纠正').props('disabled')).toBe(true)
  expect(mocks.correct).toHaveBeenCalledTimes(1)
})

it('纠正核对标记写入失败时不发送业务请求', async () => {
  fact.status = 'ACTIVE'
  const wrapper = await render()
  await button(wrapper, '纠正').trigger('click')
  await flushPromises()
  vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
    throw new Error('存储不可写')
  })
  await button(wrapper, '保存纠正').trigger('click')
  await flushPromises()
  expect(mocks.correct).not.toHaveBeenCalled()
  expect(document.body.textContent).toContain('无法保存纠正核对状态，尚未发送纠正请求')
  expect(button(wrapper, '保存纠正').props('disabled')).toBe(false)
})

it('明确业务拒绝清除临时标记，允许修改后继续纠正', async () => {
  fact.status = 'ACTIVE'
  mocks.correct
    .mockRejectedValueOnce(
      new HttpClientError({
        status: 422,
        code: 'VALIDATION_ERROR',
        userMessage: '纠正内容未通过校验'
      })
    )
    .mockResolvedValueOnce({})
  const wrapper = await render()
  await button(wrapper, '纠正').trigger('click')
  await flushPromises()
  await button(wrapper, '保存纠正').trigger('click')
  await flushPromises()
  expect(button(wrapper, '保存纠正').props('disabled')).toBe(false)
  expect(
    sessionStorage.getItem(
      `memory-correction-unknown:${JSON.stringify(['tenant-a', 'operator-a', 'fact/user'])}`
    )
  ).toBeNull()
  const subject = wrapper
    .findAllComponents(ElInput)
    .find((item) => item.props('modelValue') === fact.subject)!
  subject.vm.$emit('update:modelValue', '修正后的主体')
  await flushPromises()
  await button(wrapper, '保存纠正').trigger('click')
  await flushPromises()
  expect(mocks.correct).toHaveBeenCalledTimes(2)
  expect(mocks.correct).toHaveBeenLastCalledWith(
    'fact/user',
    expect.objectContaining({ subject: '修正后的主体', expected_revision: 3 })
  )
})

it.each(['DECAYED', 'FORGOTTEN'] as const)('%s事实显式恢复后才可按新版本纠正', async (status) => {
  fact.status = status
  fact.scope = 'USER_AGENT'
  fact.review_required = false
  mocks.restore.mockImplementation(async () => {
    fact = { ...fact, status: 'ACTIVE', revision: 4 }
    return fact
  })
  mocks.correct.mockResolvedValue({})
  const wrapper = await render()
  expect(wrapper.findAllComponents(ElButton).some((item) => item.text() === '遗忘')).toBe(false)
  expect(wrapper.text()).toContain('恢复后保留原审核状态')
  expect(button(wrapper, '纠正').props('disabled')).toBe(true)
  button(wrapper, '纠正').vm.$emit('click')
  await flushPromises()
  expect(wrapper.findComponent(ElDrawer).props('modelValue')).toBe(false)
  expect(mocks.correct).not.toHaveBeenCalled()
  expect(mocks.restore).not.toHaveBeenCalled()
  await button(wrapper, '恢复').trigger('click')
  await flushPromises()
  confirmationButton('恢复记忆').click()
  await flushPromises()
  expect(mocks.restore).toHaveBeenCalledExactlyOnceWith('fact/user', 3)
  expect(mocks.forget).not.toHaveBeenCalled()
  expect(button(wrapper, '遗忘').exists()).toBe(true)
  expect(button(wrapper, '纠正').props('disabled')).toBe(false)
  expect(wrapper.text()).not.toContain('恢复后保留原审核状态')
  await button(wrapper, '纠正').trigger('click')
  await flushPromises()
  await button(wrapper, '保存纠正').trigger('click')
  await flushPromises()
  expect(mocks.correct).toHaveBeenCalledExactlyOnceWith(
    'fact/user',
    expect.objectContaining({ expected_revision: 4 })
  )
  expect(fact.scope).toBe('USER_AGENT')
})

it.each([
  {
    status: 'PENDING_CONFIRM' as const,
    feedback: '记忆已恢复为待审核，审核通过后才能使用'
  },
  { status: 'ACTIVE' as const, feedback: '记忆已恢复，后续任务可按原范围使用' }
])('需审核的 USER 记忆按恢复回执 $status 展示提示和操作', async ({ status, feedback }) => {
  fact.status = 'FORGOTTEN'
  mocks.restore.mockImplementation(async () => {
    fact = { ...fact, status, revision: 4 }
    return fact
  })
  const wrapper = await render()
  await button(wrapper, '恢复').trigger('click')
  await flushPromises()
  expect(document.querySelector('.el-message-box')?.textContent).toContain(
    '还原遗忘或衰减前的审核状态'
  )
  expect(document.querySelector('.el-message-box')?.textContent).toContain(
    '待确认记忆恢复后仍需审核'
  )
  expect(mocks.restore).not.toHaveBeenCalled()
  confirmationButton('恢复记忆').click()
  await flushPromises()

  expect(mocks.restore).toHaveBeenCalledExactlyOnceWith('fact/user', 3)
  expect(mocks.detail).not.toHaveBeenCalled()
  expect(mocks.review).not.toHaveBeenCalled()
  expect(fact.review_required).toBe(true)
  expect(document.querySelector('.el-message')?.textContent).toContain(feedback)
  expect(button(wrapper, '纠正').props('disabled')).toBe(status === 'PENDING_CONFIRM')
  expect(button(wrapper, '遗忘').props('disabled')).toBe(false)
  const actions = wrapper.findAllComponents(ElButton).map((item) => item.text())
  expect(actions.includes('批准')).toBe(status === 'PENDING_CONFIRM')
  expect(actions.includes('拒绝')).toBe(status === 'PENDING_CONFIRM')
  expect(actions).not.toContain('恢复')
  if (status === 'PENDING_CONFIRM') {
    expect(wrapper.text()).toContain('请先审核跨助手共享')
    expect(button(wrapper, '批准').props('disabled')).toBe(false)
  }
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
  expect(button(wrapper, '纠正').props('disabled')).toBe(true)
  expect(wrapper.text()).toContain('当前状态不可纠正')
  button(wrapper, '纠正').vm.$emit('click')
  await flushPromises()
  expect(wrapper.findComponent(ElDrawer).props('modelValue')).toBe(false)
  expect(mocks.correct).not.toHaveBeenCalled()
  expect(mocks.restore).not.toHaveBeenCalled()
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
    fact.status = 'ACTIVE'
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

describe('记忆审核结果未知', () => {
  it.each([
    { label: '批准', operation: '批准', approved: true },
    { label: '拒绝', operation: '拒绝', approved: false }
  ])(
    '$label 丢失回执后同事实锁定，重载与核对只按原修订收敛',
    async ({ label, operation, approved }) => {
      fact.scope = 'USER_AGENT'
      mocks.review.mockRejectedValueOnce(new Error('连接中断'))
      const first = await render()
      await button(first, label).trigger('click')
      await flushPromises()
      expect(mocks.review).toHaveBeenCalledExactlyOnceWith('fact/user', 3, approved)
      expect(first.text()).toContain(`${operation}结果尚未确认`)
      expect(button(first, '批准').props('disabled')).toBe(true)
      expect(button(first, '拒绝').props('disabled')).toBe(true)
      first.unmount()
      const restored = await render()
      expect(restored.text()).toContain(`${operation}结果尚未确认`)
      mocks.detail
        .mockRejectedValueOnce(new Error('读取失败'))
        .mockResolvedValueOnce({ ...fact })
        .mockResolvedValueOnce({ ...fact, revision: 4, status: approved ? 'ACTIVE' : 'REJECTED' })
      await button(restored, '核对最新状态').trigger('click')
      await flushPromises()
      expect(restored.text()).toContain(`${operation}结果尚未确认`)
      await button(restored, '核对最新状态').trigger('click')
      await flushPromises()
      expect(restored.text()).toContain(`${operation}结果尚未确认`)
      await button(restored, '核对最新状态').trigger('click')
      await flushPromises()
      expect(restored.text()).not.toContain(`${operation}结果尚未确认`)
      expect(document.body.textContent).toContain('原操作回执未单独确认')
      expect(mocks.review).toHaveBeenCalledTimes(1)
    }
  )
  it('修订提案按提案事实而非被替换事实核对', async () => {
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
    mocks.review.mockRejectedValueOnce(new Error('连接中断'))
    const wrapper = await render()
    await button(wrapper, '批准').trigger('click')
    await flushPromises()
    confirmationButton('确认替换').click()
    await flushPromises()
    expect(wrapper.text()).toContain('批准结果尚未确认')
    mocks.detail.mockResolvedValueOnce({ ...fact, revision: 4, status: 'ACTIVE' })
    await button(wrapper, '核对最新状态').trigger('click')
    await flushPromises()
    expect(mocks.detail).toHaveBeenCalledExactlyOnceWith('fact/user')
    expect(mocks.review).toHaveBeenCalledExactlyOnceWith('fact/user', 3, true, undefined)
  })
  it.each([
    { label: '缺失修订', response: { revision: undefined } },
    { label: '原修订', response: { revision: 3 } },
    { label: '错误状态', response: { revision: 4, status: 'REJECTED' } },
    {
      label: '错误事实',
      response: {
        revision: 4,
        status: 'ACTIVE',
        ref: { tenant_id: { value: 'tenant-a' }, fact_id: 'other' }
      }
    },
    {
      label: '错误租户',
      response: {
        revision: 4,
        status: 'ACTIVE',
        ref: { tenant_id: { value: 'other' }, fact_id: 'fact/user' }
      }
    }
  ])('审核响应$label时保持未知与写锁', async ({ response }) => {
    fact.scope = 'USER_AGENT'
    mocks.review.mockResolvedValueOnce({ ...fact, ...response })
    const wrapper = await render()
    await button(wrapper, '批准').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('批准结果尚未确认')
    expect(button(wrapper, '拒绝').props('disabled')).toBe(true)
    expect(mocks.review).toHaveBeenCalledOnce()
  })
  it('核对响应缺失修订或事实身份不匹配时保持锁', async () => {
    fact.scope = 'USER_AGENT'
    mocks.review.mockRejectedValueOnce(new Error('连接中断'))
    const wrapper = await render()
    await button(wrapper, '批准').trigger('click')
    await flushPromises()
    mocks.detail
      .mockResolvedValueOnce({ ...fact, revision: undefined })
      .mockResolvedValueOnce({ ...fact, revision: 4, ref: { ...fact.ref, fact_id: 'other' } })
    await button(wrapper, '核对最新状态').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('批准结果尚未确认')
    await button(wrapper, '核对最新状态').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('批准结果尚未确认')
    expect(button(wrapper, '批准').props('disabled')).toBe(true)
  })
  it('共享确认期间身份变化不会发送旧事实审核', async () => {
    const auth = useAuthStore()
    const wrapper = await render()
    await button(wrapper, '批准').trigger('click')
    await flushPromises()
    auth.identityVersion++
    confirmationButton('确认跨助手共享').click()
    await flushPromises()
    expect(mocks.review).not.toHaveBeenCalled()
  })
  it('审核在途时切换成员，迟到成功不清除原成员的未知记录', async () => {
    const auth = useAuthStore()
    fact.scope = 'USER_AGENT'
    let finish!: (value: unknown) => void
    mocks.review.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve
        })
    )
    const wrapper = await render()
    await button(wrapper, '批准').trigger('click')
    await flushPromises()
    const originalKey = `memory-state-unknown:${JSON.stringify(['tenant-a', 'operator-a'])}`
    expect(sessionStorage.getItem(originalKey)).toContain('APPROVE')
    auth.user!.sub = 'operator-b'
    auth.identityVersion++
    finish({ ...fact, revision: 4, status: 'ACTIVE' })
    await flushPromises()
    expect(sessionStorage.getItem(originalKey)).toContain('APPROVE')
    expect(wrapper.text()).not.toContain('批准结果尚未确认')
    auth.user!.sub = 'operator-a'
    auth.identityVersion++
  })
  it('存储写入失败不审核，明确业务拒绝释放锁', async () => {
    fact.scope = 'USER_AGENT'
    const wrapper = await render()
    const set = vi.spyOn(Storage.prototype, 'setItem').mockImplementationOnce(() => {
      throw Error('storage')
    })
    await button(wrapper, '批准').trigger('click')
    await flushPromises()
    expect(mocks.review).not.toHaveBeenCalled()
    expect(document.body.textContent).toContain('尚未发送请求')
    set.mockRestore()
    mocks.review.mockRejectedValueOnce(
      new HttpClientError({ status: 409, code: 'CONFLICT', userMessage: '审核冲突' })
    )
    await button(wrapper, '批准').trigger('click')
    await flushPromises()
    expect(wrapper.text()).not.toContain('批准结果尚未确认')
    expect(button(wrapper, '批准').props('disabled')).toBe(false)
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
    expect(button(wrapper, '纠正').props('disabled')).toBe(true)
    expect(wrapper.text()).toContain('批准后可纠正')
    expect(button(wrapper, '批准').props('disabled')).toBe(false)
    await button(wrapper, '批准').trigger('click')
    await flushPromises()
    expect(mocks.review).toHaveBeenLastCalledWith('fact/user', 4, true)
  })

  it.each(['string', 'object'])('直接纠正%s文本并保留条件和时间等字段', async (kind) => {
    fact.scope = 'USER_AGENT'
    fact.status = 'ACTIVE'
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
    fact.status = 'ACTIVE'
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
    fact.status = 'ACTIVE'
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
    fact.status = 'ACTIVE'
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
    fact.status = 'ACTIVE'
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
    expect(button(wrapper, '遗忘').props('disabled')).toBe(true)
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

  it.each([
    { scope: 'USER' as const, notice: '请先审核跨助手共享' },
    { scope: 'USER_AGENT' as const, notice: '批准后可纠正' }
  ])('待确认 $scope 记忆先审核，不提交纠正或恢复', async ({ scope, notice }) => {
    fact.scope = scope
    const wrapper = await render()
    expect(wrapper.text()).toContain(notice)
    expect(button(wrapper, '纠正').props('disabled')).toBe(true)
    expect(button(wrapper, '批准').props('disabled')).toBe(false)
    button(wrapper, '纠正').vm.$emit('click')
    await flushPromises()

    expect(wrapper.findComponent(ElDrawer).props('modelValue')).toBe(false)
    expect(mocks.correct).not.toHaveBeenCalled()
    expect(mocks.restore).not.toHaveBeenCalled()
  })

  it('选中事实不再活动时保存事件不提交，保留纠正草稿', async () => {
    fact = reactive<MemoryFact>({ ...fact, scope: 'USER_AGENT', status: 'ACTIVE' })
    const wrapper = await render()
    await button(wrapper, '纠正').trigger('click')
    await flushPromises()
    const subject = wrapper
      .findAllComponents(ElInput)
      .find((input) => input.props('modelValue') === 'user-a')!
    subject.vm.$emit('update:modelValue', '保留的纠正草稿')
    fact.status = 'FORGOTTEN'
    await flushPromises()

    expect(button(wrapper, '保存纠正').props('disabled')).toBe(true)
    button(wrapper, '保存纠正').vm.$emit('click')
    await flushPromises()
    expect(subject.props('modelValue')).toBe('保留的纠正草稿')
    expect(wrapper.findComponent(ElDrawer).props('modelValue')).toBe(true)
    expect(mocks.correct).not.toHaveBeenCalled()
    expect(mocks.restore).not.toHaveBeenCalled()
  })
})
