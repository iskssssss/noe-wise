import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import TeamManager from './TeamManager.vue'
import { ChatCheckboxGroup, ChatMessage, ChatMessageBox, ChatSelect } from './controls'
import { createClient, RequestError } from '../client'
import type { Team } from '../protocol'

const mounted: VueWrapper[] = []
afterEach(() => {
  mounted.splice(0).forEach((wrapper) => wrapper.unmount())
  ChatMessage.closeAll()
  vi.restoreAllMocks()
})
const team: Team = {
  id: 'created-team',
  revision: 1,
  name: '测试团队',
  description: '',
  status: 'draft',
  primary_agent_id: 'agent-1',
  members: [{ agent_id: 'agent-1', name: '成员' }]
}
async function panel(failure: Error = new TypeError('Failed to fetch')) {
  const client = createClient('http://localhost', { mode: 'token', token: 'test' })
  const json = vi.spyOn(client, 'json').mockRejectedValueOnce(failure).mockResolvedValue(team)
  const wrapper = mount(TeamManager, {
    props: {
      modelValue: true,
      client,
      clientRef: 'visitor',
      teams: [],
      agents: [{ id: 'agent-1', name: '成员', is_default: true }]
    }
  })
  mounted.push(wrapper)
  await wrapper.get('input[type="text"]').setValue('测试团队')
  wrapper.getComponent(ChatCheckboxGroup).vm.$emit('update:modelValue', ['agent-1'])
  await flushPromises()
  wrapper.findAllComponents(ChatSelect).at(-1)!.vm.$emit('update:modelValue', 'agent-1')
  await flushPromises()
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  return { wrapper, json }
}

describe('团队创建的未知结果恢复', () => {
  it('未知结果冻结草稿，关闭重开后也不能重复无ID新建', async () => {
    const { wrapper, json } = await panel()
    expect(wrapper.find('[aria-label="核对团队创建结果"]').exists()).toBe(true)
    expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('input[type="text"]').element).toHaveProperty('value', '测试团队')
    await wrapper.get('form').trigger('submit')
    await (wrapper.vm as unknown as { requestClose: () => Promise<boolean> }).requestClose()
    await wrapper.setProps({ modelValue: false })
    await wrapper.setProps({ modelValue: true })
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(json).toHaveBeenCalledTimes(1)
    expect(wrapper.get('input[type="text"]').element).toHaveProperty('value', '测试团队')
    expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeDefined()
  })
  it('显式核对列表并确认关联后，再保存使用已创建团队ID', async () => {
    const { wrapper, json } = await panel()
    json.mockResolvedValueOnce([team])
    await wrapper.get('[aria-label="核对团队创建结果"] button').trigger('click')
    await flushPromises()
    const choice = wrapper.get('[aria-label="核对团队创建结果"]').findComponent(ChatSelect)
    choice.vm.$emit('update:modelValue', team.id)
    await flushPromises()
    vi.spyOn(ChatMessageBox, 'confirm').mockResolvedValue()
    await wrapper
      .findAll('button')
      .find((item) => item.text() === '已核对，关联此团队')!
      .trigger('click')
    await flushPromises()
    expect(json.mock.calls.map((call) => call[1])).toEqual(['PUT', 'GET'])
    expect(wrapper.find('[aria-label="核对团队创建结果"]').exists()).toBe(false)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(json.mock.calls[2]?.[2]).toMatchObject({ id: team.id, revision: 1 })
  })
  it('核对列表失败或暂未发现团队不会解锁新建', async () => {
    const { wrapper, json } = await panel()
    json.mockRejectedValueOnce(new Error('暂时不可用')).mockResolvedValueOnce([])
    await wrapper.get('[aria-label="核对团队创建结果"] button').trigger('click')
    await flushPromises()
    await wrapper.get('[aria-label="核对团队创建结果"] button').trigger('click')
    await flushPromises()
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(json.mock.calls.map((call) => call[1])).toEqual(['PUT', 'GET', 'GET'])
    expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeDefined()
  })
  it('明确校验拒绝保留可编辑表单，允许用户修正后提交', async () => {
    const { wrapper, json } = await panel(
      new RequestError('成员不符合要求', 400, 'VALIDATION_ERROR')
    )
    expect(wrapper.find('[aria-label="核对团队创建结果"]').exists()).toBe(false)
    expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeUndefined()
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(json).toHaveBeenCalledTimes(2)
  })
  it('未明确拒绝的冲突响应仍保留未知创建，不靠状态码解锁重发', async () => {
    const { wrapper, json } = await panel(new RequestError('结果尚未确认', 409, 'OUTCOME_UNKNOWN'))
    expect(wrapper.find('[aria-label="核对团队创建结果"]').exists()).toBe(true)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(json).toHaveBeenCalledTimes(1)
  })
})
