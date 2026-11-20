import { afterEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { RequestError, type Client } from '../client'
import ConversationTools from './ConversationTools.vue'
import { ChatMessage } from './controls'

enableAutoUnmount(afterEach)
afterEach(() => ChatMessage.closeAll())
const status = (revision = 1, state = 'ACTIVE') => ({
  goal: { goal_id: 'g', objective: '核对结果', revision, state, rounds_started: 0, max_rounds: 1 },
  support: { available: true, reason: null },
  continuation_enabled: state === 'ACTIVE',
  completion_pending: false
})
async function open(json: ReturnType<typeof vi.fn>) {
  const wrapper = mount(ConversationTools, {
    attachTo: document.body,
    props: {
      modelValue: true,
      session: 's',
      run: 'r',
      clientRef: 'c',
      client: { json } as unknown as Client
    }
  })
  await flushPromises()
  const click = async (name: string) => {
    await wrapper
      .findAll('button')
      .find((button) => button.text() === name)!
      .trigger('click')
    await flushPromises()
  }
  return { wrapper, click }
}
describe('持续目标能力与冲突恢复', () => {
  it('缺少能力时旧目标仍可查看，恢复禁用且不出现创建表单', async () => {
    const json = vi.fn().mockResolvedValue({
      ...status(22, 'PAUSED'),
      support: {
        available: false,
        reason: '请添加“查看任务目标”和“更新任务目标”工具并发布；也可以使用普通对话。'
      }
    })
    const { wrapper, click } = await open(json)
    expect(wrapper.text()).toContain('查看任务目标')
    expect(wrapper.text()).toContain('已暂停')
    expect(
      wrapper
        .findAll('button')
        .find((button) => button.text() === '继续目标')!
        .attributes('disabled')
    ).toBeDefined()
    expect(wrapper.find('form').exists()).toBe(false)
    await click('继续目标')
    expect(json.mock.calls.filter(([, method]) => method === 'POST')).toHaveLength(0)
  })
  it('明确版本拒绝只回读，用户再次暂停使用新版本和新身份', async () => {
    let readCount = 0
    let writes = 0
    const json = vi.fn(async (_path: string, method?: string) => {
      if (method !== 'POST') return status(++readCount === 1 ? 7 : 9)
      if (++writes === 1)
        throw new RequestError('目标状态已更新，请核对后重新操作。', 409, 'goal_revision_conflict')
      return status(10, 'PAUSED')
    })
    const { wrapper, click } = await open(json)
    await click('暂停目标')
    expect(writes).toBe(1)
    expect(readCount).toBe(2)
    await click('暂停目标')
    const posts = json.mock.calls.filter(([, method]) => method === 'POST') as unknown as [
      string,
      string,
      { command_id: string; expected_revision: number }
    ][]
    expect(posts.map(([, , body]) => body.expected_revision)).toEqual([7, 9])
    expect(posts[0][2].command_id).not.toBe(posts[1][2].command_id)
    expect(wrapper.text()).toContain('已暂停')
  })
  it('未知结果后刷新不会更换原暂停身份或预期版本', async () => {
    let reads = 0
    let writes = 0
    const json = vi.fn(async (_path: string, method?: string) => {
      if (method !== 'POST') return status(++reads === 1 ? 7 : 9)
      if (++writes === 1) throw new RequestError('网络断开，结果未确认', 0)
      return status(10, 'COMPLETE')
    })
    const { click } = await open(json)
    await click('暂停目标')
    await click('刷新')
    await click('暂停目标')
    const posts = json.mock.calls.filter(([, method]) => method === 'POST')
    expect(posts[0]).toEqual(posts[1])
    expect(document.body.textContent).toContain('持续目标：已完成')
    expect(document.body.textContent).not.toContain('持续目标已暂停')
  })
  it('受阻目标按当前领域契约允许新建，不显示无法执行的恢复按钮', async () => {
    const { wrapper } = await open(vi.fn().mockResolvedValue(status(3, 'BLOCKED')))
    expect(wrapper.text()).not.toContain('继续目标')
    expect(wrapper.find('form').exists()).toBe(true)
  })
})
