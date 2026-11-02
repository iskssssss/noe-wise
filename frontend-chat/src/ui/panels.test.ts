import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { ChatMessageBox, ChatCheckbox, ChatOption, ChatInputNumber, ChatSelect } from './controls'
import TeamManager from './TeamManager.vue'
import TaskDetailsPanel from './TaskDetailsPanel.vue'
import TaskPlanCard from './TaskPlanCard.vue'
import ConversationTools from './ConversationTools.vue'
import type { Client } from '../client'
import type { Work } from '../protocol'
import { readFileSync } from 'node:fs'

const stylesheet = document.createElement('style')
beforeAll(() => {
  stylesheet.textContent = readFileSync('src/style.css', 'utf8')
  document.head.append(stylesheet)
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe() {}
      unobserve() {}
      disconnect() {}
    }
  )
})
afterAll(() => stylesheet.remove())
const work = (id: string): Work => ({
  input_id: 'input-plan',
  work_id: id,
  session_id: 's',
  revision: 1,
  status: 'RUNNING',
  run_id: 'r',
  plan_revision: 1,
  approval_required: true,
  tasks: [],
  results: []
})
type PanelState = {
  work: Work
  workText: string
  task: string
  selectWork: (id: string) => Promise<void>
}
const options = { attachTo: document.body }
async function tools(records: Work[] = [work('w1'), work('w2')]) {
  let resolve!: (value: Work) => void
  const control = new Promise<Work>((done) => {
    resolve = done
  })
  const json = vi.fn(
    async (path: string, _method?: string, _body?: unknown, _signal?: AbortSignal) =>
      path.startsWith('/chat/work/history')
        ? { items: records }
        : path === '/chat/work/controls'
          ? control
          : []
  )
  const wrapper = mount(TaskDetailsPanel, {
    ...options,
    props: {
      modelValue: true,
      client: { json, base: 'http://localhost' } as unknown as Client,
      session: 's',
      selectedId: records[0]?.work_id
    }
  })
  await flushPromises()
  return { wrapper, json, resolve }
}
describe('会话抽屉与请求隔离', () => {
  it('目标表单提交默认 256 轮，允许 1024 轮并在提交后恢复默认值', async () => {
    const json = vi.fn(async (_path: string, method?: string, body?: unknown) =>
      method === 'POST'
        ? { goal: { ...(body as object), state: 'ACTIVE', rounds_started: 0 } }
        : { goal: null }
    )
    const wrapper = mount(ConversationTools, {
      ...options,
      props: {
        modelValue: true,
        session: 's',
        run: 'r',
        clientRef: 'c',
        client: { json } as unknown as Client
      }
    })
    try {
      await flushPromises()
      expect(wrapper.getComponent(ChatInputNumber).props('modelValue')).toBe(256)
      expect(wrapper.getComponent(ChatInputNumber).props('max')).toBe(1024)
      await wrapper.get('textarea').setValue('完成文件交付')
      await wrapper.get('form').trigger('submit')
      await flushPromises()
      expect(json).toHaveBeenCalledWith(
        '/chat/goals/create',
        'POST',
        expect.objectContaining({ objective: '完成文件交付', max_rounds: 256 }),
        expect.any(AbortSignal)
      )
      await wrapper.get('textarea').setValue('继续文件交付')
      wrapper.getComponent(ChatInputNumber).vm.$emit('update:modelValue', 1024)
      await wrapper.get('form').trigger('submit')
      await flushPromises()
      expect(json).toHaveBeenLastCalledWith(
        '/chat/goals/create',
        'POST',
        expect.objectContaining({ objective: '继续文件交付', max_rounds: 1024 }),
        expect.any(AbortSignal)
      )
      expect(wrapper.getComponent(ChatInputNumber).props('modelValue')).toBe(256)
    } finally {
      wrapper.unmount()
    }
  })
  it('任务详情将真实控制回执上送会话，同时同步本地计划摘要', async () => {
    const { wrapper, resolve } = await tools([work('w1')])
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '批准第 1 版计划')!
      .trigger('click')
    const approved = { ...work('w1'), revision: 2, approved_revision: 1 }
    resolve(approved)
    await flushPromises()
    expect(wrapper.emitted('updated')?.[0]).toEqual([approved])
    expect(wrapper.text()).not.toContain('待批准第 1 版计划')
    wrapper.unmount()
  })
  it.each([
    ['PAUSED', 'run_cancelled', '本次执行已取消。'],
    ['BLOCKED', 'run_failed', '本次执行失败。'],
    ['BLOCKED', 'run_completed', '本次执行未形成可发布的成果。'],
    ['BLOCKED', 'publication_validation_failed', '成果文件校验未通过，本次交付未发布。'],
    ['BLOCKED', 'publication_reservation_lost', '成果版本已发生变化，本次交付未发布。'],
    ['BLOCKED', 'completed_result_reference_missing', '成果交付确认缺失，本次交付未发布。'],
    ['BLOCKED', 'publication_customer_review_required', 'publication_customer_review_required'],
    ['PAUSED', 'driver_error', '目标暂时无法继续，请检查当前执行情况。'],
    ['BLOCKED', '请补充尚未提供的流水文件', '请补充尚未提供的流水文件']
  ])('目标 %s 的原因 %s 显示可读说明并保留业务要求', async (state, reason, expected) => {
    const wrapper = mount(ConversationTools, {
      ...options,
      props: {
        modelValue: true,
        session: 's',
        run: 'r',
        clientRef: 'c',
        client: {
          json: vi.fn(async () => ({
            goal: { objective: '完成文件交付', state, reason, rounds_started: 0, max_rounds: 1 }
          }))
        } as unknown as Client
      }
    })
    try {
      await flushPromises()
      expect(wrapper.get('.panel-card').text()).toContain(expected)
      if (reason !== expected) expect(wrapper.get('.panel-card').text()).not.toContain(reason)
    } finally {
      wrapper.unmount()
    }
  })

  it.each([
    ['ACTIVE', '进行中'],
    ['PAUSED', '已暂停'],
    ['COMPLETE', '已完成'],
    ['BLOCKED', '受阻'],
    ['UNRECOGNIZED', '状态待核实']
  ])('目标状态 %s 使用用户可读文案', async (state, label) => {
    const wrapper = mount(ConversationTools, {
      ...options,
      props: {
        modelValue: true,
        session: 's',
        run: 'r',
        clientRef: 'c',
        client: {
          json: vi.fn(async () => ({
            goal: { objective: '完成文件交付', state, rounds_started: 0, max_rounds: 2 }
          }))
        } as unknown as Client
      }
    })
    try {
      await flushPromises()
      expect(wrapper.get('.panel-card').text()).toContain(label)
      expect(wrapper.get('.panel-card').text()).not.toContain(state)
    } finally {
      wrapper.unmount()
    }
  })

  it('团队仅一个成员时自动选为负责人，添加成员保留选择并在移除后更新', async () => {
    const agents = [
      { id: 'agent-1', name: '助手一', is_default: true },
      { id: 'agent-2', name: '助手二', is_default: false }
    ]
    const wrapper = mount(TeamManager, {
      ...options,
      props: { modelValue: true, client: {} as Client, clientRef: 'c', agents, teams: [] }
    })
    await flushPromises()
    const boxes = wrapper.findAll('input[type="checkbox"]')
    const leader = wrapper.getComponent(ChatSelect)
    await boxes[0]!.setValue(true)
    expect(leader.props('modelValue')).toBe('agent-1')
    await boxes[1]!.setValue(true)
    expect(leader.props('modelValue')).toBe('agent-1')
    await boxes[0]!.setValue(false)
    expect(leader.props('modelValue')).toBe('agent-2')
    await boxes[1]!.setValue(false)
    expect(leader.props('modelValue')).toBe('')
    wrapper.unmount()
  })
  it('同名团队成员展示角色说明与标识，负责人仍提交对应智能体 ID', async () => {
    const agents = [
      { id: 'agent-1', name: '助手', is_default: true, role_description: '负责申请' },
      { id: 'agent-2', name: '助手', is_default: false, role_description: '负责资料检索' }
    ]
    const wrapper = mount(TeamManager, {
      ...options,
      props: { modelValue: true, client: {} as Client, clientRef: 'c', agents, teams: [] }
    })
    await flushPromises()
    const choices = wrapper.findAllComponents(ChatCheckbox)
    expect(choices.map((item) => item.text())).toEqual([
      '助手（agent-1）负责申请',
      '助手（agent-2）负责资料检索'
    ])
    for (const checkbox of wrapper.findAll('input[type="checkbox"]')) await checkbox.setValue(true)
    const leaders = wrapper.findAllComponents(ChatOption)
    expect(leaders.map((item) => item.props('label'))).toEqual([
      '助手（agent-1）',
      '助手（agent-2）'
    ])
    expect(leaders.map((item) => item.props('value'))).toEqual(['agent-1', 'agent-2'])
    expect(leaders.map((item) => item.props('searchText'))).toEqual(['负责申请', '负责资料检索'])
    expect(leaders.map((item) => item.text())).toEqual([
      '助手（agent-1）负责申请',
      '助手（agent-2）负责资料检索'
    ])
    wrapper.unmount()
  })

  it('团队编辑的父级关闭入口同样要求确认，取消保留草稿', async () => {
    const confirm = vi.spyOn(ChatMessageBox, 'confirm').mockRejectedValue('cancel')
    const wrapper = mount(TeamManager, {
      ...options,
      props: { modelValue: true, client: {} as Client, clientRef: 'c', agents: [], teams: [] }
    })
    await flushPromises()
    await wrapper.get('input').setValue('未保存团队')
    expect(await wrapper.vm.requestClose()).toBe(false)
    expect(confirm).toHaveBeenCalledOnce()
    expect(wrapper.get('input').element.value).toBe('未保存团队')
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
    confirm.mockResolvedValue('confirm' as never)
    expect(await wrapper.vm.requestClose()).toBe(true)
    expect(wrapper.emitted('update:modelValue')).toEqual([[false]])
    wrapper.unmount()
  })
  it('协作提交期间阻止抽屉关闭和记录切换', async () => {
    const { wrapper, resolve } = await tools()
    await wrapper
      .findAll('button')
      .find((item) => item.text().includes('批准第 1 版计划'))!
      .trigger('click')
    await flushPromises()
    expect(await wrapper.vm.requestClose()).toBe(false)
    expect(wrapper.get('.chat-select__trigger').attributes('disabled')).toBeDefined()
    const state = (wrapper.vm.$ as unknown as { setupState: PanelState }).setupState
    await state.selectWork('w2')
    expect(state.work.work_id).toBe('w1')
    resolve({ ...work('w1'), approval_required: false })
    await flushPromises()
    expect(wrapper.findComponent(TaskPlanCard).text()).not.toContain('批准第 1 版计划')
    wrapper.unmount()
  })
  it('整个计划取消和步骤取消各自携带准确目标', async () => {
    const record: Work = {
      ...work('w1'),
      tasks: [
        {
          task_id: 'task-1',
          task_kind: 'plan',
          title: '主步骤',
          executor_type: 'PRIMARY',
          agent_name: '主助手',
          status: 'running',
          details_access: 'available'
        }
      ]
    }
    const { wrapper, json, resolve } = await tools([record])
    await wrapper
      .findAll('button')
      .find((item) => item.text() === '取消计划')!
      .trigger('click')
    await flushPromises()
    expect(json).toHaveBeenCalledWith(
      '/chat/work/controls',
      'POST',
      expect.objectContaining({ operation: 'cancel', work_id: 'w1', task_id: undefined }),
      expect.any(AbortSignal)
    )
    resolve(record)
    await flushPromises()
    const card = wrapper.findComponent(TaskPlanCard)
    const state = (
      card.vm.$ as unknown as {
        setupState: { control: (op: string, task: Work['tasks'][number]) => Promise<void> }
      }
    ).setupState
    await state.control('cancel', record.tasks[0]!)
    expect(json).toHaveBeenLastCalledWith(
      '/chat/work/controls',
      'POST',
      expect.objectContaining({ operation: 'cancel', work_id: 'w1', task_id: 'task-1' }),
      expect.any(AbortSignal)
    )
    wrapper.unmount()
  })
  it('切换会话取消在途控制，迟到响应不覆盖新会话计划', async () => {
    const { wrapper, json, resolve } = await tools()
    await wrapper
      .findAll('button')
      .find((item) => item.text().includes('批准第 1 版计划'))!
      .trigger('click')
    await flushPromises()
    const call = json.mock.calls.find((args) => args[0] === '/chat/work/controls')!
    await wrapper.setProps({ session: 'new-session' })
    expect(call[3]?.aborted).toBe(true)
    resolve({ ...work('old-plan'), status: 'completed' })
    await flushPromises()
    expect(wrapper.text()).not.toContain('old-plan')
    wrapper.unmount()
  })
})
