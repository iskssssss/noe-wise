import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import TeamManager from './TeamManager.vue'
import { ChatCheckboxGroup, ChatMessage, ChatSelect } from './controls'
import { createClient } from '../client'
import type { Agent, Team } from '../protocol'

const mounted: VueWrapper[] = []
afterEach(() => {
  mounted.splice(0).forEach((wrapper) => wrapper.unmount())
  ChatMessage.closeAll()
})
const team: Team = {
  id: 'existing-team',
  revision: 4,
  name: '双成员团队',
  description: '保留原说明',
  status: 'published',
  primary_agent_id: 'primary',
  members: [{ agent_id: 'primary' }, { agent_id: 'reviewer' }]
}
const agents: Agent[] = [
  {
    id: 'primary',
    name: '负责人',
    is_default: true,
    delegation_config: { enabled: false, target_agent_ids: [] }
  },
  {
    id: 'reviewer',
    name: '复核员',
    is_default: false,
    delegation_config: { enabled: false, target_agent_ids: [] }
  }
]
async function panel(catalog = agents) {
  const client = createClient('http://localhost', { mode: 'token', token: 'test' })
  const json = vi.spyOn(client, 'json').mockResolvedValue(team)
  const wrapper = mount(TeamManager, {
    props: { modelValue: true, client, clientRef: 'visitor', teams: [team], agents: catalog }
  })
  mounted.push(wrapper)
  wrapper.getComponent(ChatSelect).vm.$emit('change', team.id)
  await flushPromises()
  return { wrapper, json }
}

describe('团队成员的公开协作范围', () => {
  it.each([
    { enabled: false, target_agent_ids: ['reviewer'] },
    { enabled: true, target_agent_ids: ['another-agent'] }
  ])('负责人未允许该成员时保留配置并阻止保存，移除后可保存原修订', async (config) => {
    const { wrapper, json } = await panel([
      { ...agents[0]!, delegation_config: config },
      agents[1]!
    ])
    expect(wrapper.get('[aria-label="团队协作范围"]').text()).toContain('复核员')
    expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeDefined()
    await wrapper.get('form').trigger('submit')
    expect(json).not.toHaveBeenCalled()
    expect(wrapper.getComponent(ChatCheckboxGroup).props('modelValue')).toEqual([
      'primary',
      'reviewer'
    ])
    wrapper.getComponent(ChatCheckboxGroup).vm.$emit('update:modelValue', ['primary'])
    await flushPromises()
    expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeUndefined()
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(json).toHaveBeenCalledWith(
      '/chat/teams',
      'PUT',
      expect.objectContaining({
        id: team.id,
        revision: 4,
        members: [{ agent_id: 'primary' }]
      }),
      expect.any(AbortSignal)
    )
  })

  it('目录刷新失败保留原选项和草稿，成功后可按原团队修订保存', async () => {
    const { wrapper, json } = await panel()
    await wrapper.get('input[type="text"]').setValue('尚未保存的团队名')
    json.mockRejectedValueOnce(new Error('目录暂不可用'))
    const refresh = wrapper.findAll('button').find((button) => button.text() === '刷新成员目录')!
    await refresh.trigger('click')
    await flushPromises()
    expect(wrapper.emitted('agents-updated')).toBeUndefined()
    expect(wrapper.get('input[type="text"]').element).toHaveProperty('value', '尚未保存的团队名')
    expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeDefined()
    const allowed = [
      { ...agents[0]!, delegation_config: { enabled: true, target_agent_ids: ['reviewer'] } },
      agents[1]!
    ]
    json.mockResolvedValueOnce({ items: allowed })
    await refresh.trigger('click')
    await flushPromises()
    expect(wrapper.emitted('agents-updated')?.[0]).toEqual([allowed])
    await wrapper.setProps({ agents: allowed })
    expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeUndefined()
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(json.mock.calls.map((call) => call[1])).toEqual(['GET', 'GET', 'PUT'])
    expect(json.mock.calls[2]?.[2]).toMatchObject({
      id: team.id,
      revision: 4,
      name: '尚未保存的团队名',
      description: team.description,
      primary_agent_id: 'primary',
      members: team.members
    })
  })

  it('切换负责人重新计算可用分工，不自动移除已选成员', async () => {
    const { wrapper, json } = await panel([
      agents[0]!,
      { ...agents[1]!, delegation_config: { enabled: true, target_agent_ids: ['primary'] } }
    ])
    wrapper.findAllComponents(ChatSelect).at(-1)!.vm.$emit('update:modelValue', 'reviewer')
    await flushPromises()
    expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeUndefined()
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(json).toHaveBeenCalledWith(
      '/chat/teams',
      'PUT',
      expect.objectContaining({
        id: team.id,
        revision: 4,
        primary_agent_id: 'reviewer',
        members: team.members
      }),
      expect.any(AbortSignal)
    )
  })
})
