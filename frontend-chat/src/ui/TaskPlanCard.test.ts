import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { mayDiscard } from './panel'
import TaskPlanCard from './TaskPlanCard.vue'
import { ChatButton } from './controls'
import type { Client } from '../client'
import type { Work } from '../protocol'
vi.mock('./panel', () => ({
  mayDiscard: vi.fn(async (busy: boolean, dirty: boolean) => !busy && !dirty)
}))
const work: Work = {
  work_id: 'w',
  input_id: 'i',
  session_id: 's',
  run_id: 'r',
  revision: 1,
  run_revision: 1,
  run_status: 'running',
  status: 'active',
  plan_revision: 2,
  approval_required: true,
  tasks: [
    {
      task_id: 't',
      run_id: 'r',
      task_kind: 'plan',
      executor_type: 'PRIMARY',
      title: '核查',
      agent_name: '主助手',
      status: 'waiting',
      wait_reason: '请补充资料',
      details_access: 'available'
    }
  ],
  results: []
}
describe('轮次任务计划卡', () => {
  for (const status of ['completed', 'failed', 'cancelled', 'outcome_unknown', 'terminated']) {
    it(`工作仍活动时 ${status} 步骤仅保留历史结果`, async () => {
      const json = vi.fn().mockResolvedValue({
        processes: [],
        results: [{ result_id: 'result', title: '历史结果', summary: '已保留的内容' }],
        pending: {
          kind: 'operation',
          pending_id: 'old',
          parameters_version: '1',
          prompt: '旧审批'
        }
      })
      const wrapper = mount(TaskPlanCard, {
        props: {
          work: { ...work, tasks: [{ ...work.tasks[0]!, status }] },
          mode: 'detail',
          view: 'steps',
          session: 's',
          client: { json } as unknown as Client
        }
      })
      await wrapper.get('[data-task-id="t"]').trigger('click')
      await flushPromises()
      expect(wrapper.text()).toContain('历史结果')
      expect(wrapper.text()).not.toContain('旧审批')
      expect(wrapper.find('textarea').exists()).toBe(false)
      expect(
        wrapper
          .findAll('button')
          .some((button) => /提交补充|批准操作|拒绝操作|回答问题|取消步骤/.test(button.text()))
      ).toBe(false)
      expect(json.mock.calls.filter(([path]) => path === '/chat/work/controls')).toHaveLength(0)
      wrapper.unmount()
    })
  }
  it('未准入依赖受阻显示原因且不提供执行或取消，不伪造失败结果', async () => {
    const json = vi.fn()
    const value = {
      ...work,
      tasks: [
        {
          ...work.tasks[0]!,
          run_id: null,
          status: 'blocked',
          details_access: 'not_started',
          wait_reason: '前置任务结果待核实，不能重复执行'
        }
      ]
    }
    const wrapper = mount(TaskPlanCard, {
      props: {
        work: value,
        mode: 'detail',
        view: 'steps',
        session: 's',
        client: { json } as unknown as Client
      }
    })
    expect(wrapper.text()).toContain('依赖受阻')
    expect(wrapper.text()).toContain('前置任务结果待核实，不能重复执行')
    await wrapper.get('[data-task-id="t"]').trigger('click')
    expect(wrapper.find('textarea').exists()).toBe(false)
    expect(
      wrapper.findAll('button').some((button) => /取消步骤|提交补充|重新执行/.test(button.text()))
    ).toBe(false)
    expect(json).not.toHaveBeenCalled()
    expect(value.tasks[0]!.status).toBe('blocked')
    wrapper.unmount()
  })
  it('未开始步骤可取消，执行输入不可用，计划级补充仍独立可用', async () => {
    const value = {
      ...work,
      tasks: [{ ...work.tasks[0]!, run_id: null, status: 'pending', details_access: 'not_started' }]
    }
    const json = vi.fn().mockResolvedValue(value)
    const wrapper = mount(TaskPlanCard, {
      props: {
        work: value,
        mode: 'detail',
        view: 'steps',
        session: 's',
        client: { json } as unknown as Client
      }
    })
    await wrapper.get('[data-task-id="t"]').trigger('click')
    expect(wrapper.find('textarea').exists()).toBe(false)
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '取消步骤')!
      .trigger('click')
    await flushPromises()
    expect(json).toHaveBeenCalledWith(
      '/chat/work/controls',
      'POST',
      expect.objectContaining({ operation: 'cancel', task_id: 't' }),
      expect.any(AbortSignal)
    )
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '返回任务')!
      .trigger('click')
    await wrapper.setProps({ view: 'overview' })
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '补充计划要求')!
      .trigger('click')
    expect(wrapper.find('textarea').exists()).toBe(true)
    wrapper.unmount()
  })
  it('步骤收到终态后，旧提交事件也不能发出执行输入', async () => {
    const json = vi.fn().mockResolvedValue({ processes: [], results: [] })
    const wrapper = mount(TaskPlanCard, {
      props: {
        work,
        mode: 'detail',
        view: 'steps',
        session: 's',
        client: { json } as unknown as Client
      }
    })
    await wrapper.get('[data-task-id="t"]').trigger('click')
    await flushPromises()
    await wrapper.get('textarea').setValue('旧草稿')
    const submit = wrapper
      .findAllComponents(ChatButton)
      .find((button) => button.text() === '提交补充')!
    await wrapper.setProps({
      work: { ...work, revision: 2, tasks: [{ ...work.tasks[0]!, status: 'cancelled' }] }
    })
    submit.vm.$emit('click')
    await flushPromises()
    expect(json.mock.calls.filter(([path]) => path === '/chat/work/controls')).toHaveLength(0)
    expect(wrapper.find('textarea').exists()).toBe(false)
    wrapper.unmount()
  })
  for (const terminal of ['completed', 'cancelled', 'failed', 'outcome_unknown', 'terminated']) {
    for (const domain of ['status', 'run_status'] as const) {
      it(`${domain} 为 ${terminal} 时保留版本但关闭当前审批与补充入口`, async () => {
        const json = vi.fn()
        const value = { ...work, [domain]: terminal }
        const wrapper = mount(TaskPlanCard, {
          props: { work: value, session: 's', client: { json } as unknown as Client }
        })
        expect(wrapper.text()).not.toContain('待批准第')
        await wrapper.setProps({ mode: 'detail' })
        expect(wrapper.text()).toContain('当前计划版本：第 2 版')
        expect(
          wrapper
            .findAll('button')
            .some((button) => /批准|补充计划要求|取消计划/.test(button.text()))
        ).toBe(false)
        expect(wrapper.find('textarea').exists()).toBe(false)
        expect(json).not.toHaveBeenCalled()
        wrapper.unmount()
      })
    }
  }

  it('已打开的步骤在工作取消后仅保留历史详情，不继续显示旧 pending 控件', async () => {
    const json = vi.fn().mockResolvedValue({
      processes: [],
      results: [],
      pending: {
        kind: 'operation',
        pending_id: 'old',
        parameters_version: 1,
        prompt: '旧审批问题'
      }
    })
    const wrapper = mount(TaskPlanCard, {
      props: {
        work,
        mode: 'detail',
        view: 'steps',
        session: 's',
        client: { json } as unknown as Client
      }
    })
    await wrapper.get('[data-task-id="t"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('批准操作')
    await wrapper.setProps({
      work: { ...work, revision: 2, run_revision: 2, status: 'cancelled', run_status: 'cancelled' }
    })
    expect(wrapper.text()).toContain('核查')
    expect(wrapper.text()).not.toContain('旧审批问题')
    expect(wrapper.find('textarea').exists()).toBe(false)
    expect(
      wrapper
        .findAll('button')
        .some((button) => /批准操作|拒绝操作|提交补充|取消步骤/.test(button.text()))
    ).toBe(false)
    expect(json).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('工作未结束时显示整理结果，变更原因只在对应版本概览内展示', async () => {
    const value = {
      ...work,
      approval_required: false,
      stage: 'summarizing',
      change_reason: '按已确认口径调整预算上限',
      plan_revision: 3
    }
    const wrapper = mount(TaskPlanCard, {
      props: { work: value, session: 's', client: {} as Client }
    })
    expect(wrapper.text()).toContain('正在整理结果')
    expect(wrapper.text()).not.toContain('按已确认口径调整预算上限')
    await wrapper.setProps({ mode: 'detail' })
    expect(wrapper.text()).toContain('当前计划版本：第 3 版')
    expect(wrapper.get('.plan-change-reason').text()).toBe('本版调整说明：按已确认口径调整预算上限')
    await wrapper.setProps({
      work: { ...value, revision: value.revision + 1, plan_revision: 4, change_reason: '' }
    })
    expect(wrapper.find('.plan-change-reason').exists()).toBe(false)
    wrapper.unmount()
  })
  it('默认仅展示摘要，查看任务只发出打开意图', async () => {
    const json = vi.fn()
    const wrapper = mount(TaskPlanCard, {
      props: { work, session: 's', client: { json } as unknown as Client }
    })
    expect(wrapper.text()).toContain('待批准第 2 版计划')
    expect(wrapper.find('ol').exists()).toBe(false)
    expect(wrapper.find('textarea').exists()).toBe(false)
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    expect(wrapper.emitted('open')).toBeUndefined()
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '查看任务')!
      .trigger('click')
    expect(wrapper.emitted('open')).toEqual([['w']])
    expect(json).not.toHaveBeenCalled()
    wrapper.unmount()
  })
  it('步骤详情在同层打开并可返回步骤列表', async () => {
    const json = vi.fn().mockResolvedValue({ processes: [], results: [] })
    const wrapper = mount(TaskPlanCard, {
      props: {
        mode: 'detail',
        view: 'steps',
        work,
        session: 's',
        client: { json } as unknown as Client
      }
    })
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '核查')!
      .trigger('click')
    await flushPromises()
    expect(wrapper.find('ol').exists()).toBe(false)
    expect(wrapper.find('textarea').exists()).toBe(true)
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '返回任务')!
      .trigger('click')
    await flushPromises()
    expect(wrapper.find('textarea').exists()).toBe(false)
    expect(wrapper.find('ol').exists()).toBe(true)
    wrapper.unmount()
  })
  it('返回与外层关闭保留未提交草稿，并保护在途读取', async () => {
    let finish!: (value: unknown) => void
    const json = vi.fn().mockImplementation(
      () =>
        new Promise((resolve) => {
          finish = resolve
        })
    )
    const wrapper = mount(TaskPlanCard, {
      props: {
        mode: 'detail',
        view: 'steps',
        work,
        session: 's',
        client: { json } as unknown as Client
      }
    })
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '核查')!
      .trigger('click')
    const exposed = wrapper.vm as unknown as { requestClose(): Promise<boolean> }
    expect(await exposed.requestClose()).toBe(false)
    expect(mayDiscard).toHaveBeenLastCalledWith(true, false)
    finish({ processes: [], results: [] })
    await flushPromises()
    await wrapper.get('textarea').setValue('未提交的资料')
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '返回任务')!
      .trigger('click')
    await flushPromises()
    expect(mayDiscard).toHaveBeenLastCalledWith(false, true)
    expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toBe('未提交的资料')
    expect(await exposed.requestClose()).toBe(false)
    wrapper.unmount()
  })
  it('依赖视图只展示公开依赖，并允许打开依赖步骤', async () => {
    const json = vi.fn().mockResolvedValue({ processes: [], results: [] })
    const wrapper = mount(TaskPlanCard, {
      props: {
        mode: 'detail',
        view: 'dependencies',
        work: {
          ...work,
          tasks: [
            ...work.tasks,
            { ...work.tasks[0]!, task_id: 'next', title: '汇总', depends_on: ['t', 'missing'] }
          ]
        },
        session: 's',
        client: { json } as unknown as Client
      }
    })
    expect(wrapper.get('[aria-label="步骤依赖"]').text()).toContain('汇总依赖：核查missing')
    await wrapper.get('svg [data-task-id="next"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('.task-detail-screen').exists()).toBe(true)
    expect(wrapper.find('dialog').exists()).toBe(false)
    expect(wrapper.text()).toContain('依赖：核查、missing')
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '返回任务')!
      .trigger('click')
    await flushPromises()
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '核查')!
      .trigger('click')
    await flushPromises()
    expect(json.mock.calls.at(-1)![0]).toContain('task_id=t')
    wrapper.unmount()
  })
  it('步骤列表不铺开依赖，选中详情仍保留真实依赖', async () => {
    const json = vi.fn().mockResolvedValue({ processes: [], results: [] })
    const wrapper = mount(TaskPlanCard, {
      props: {
        mode: 'detail',
        view: 'steps',
        session: 's',
        client: { json } as unknown as Client,
        work: {
          ...work,
          tasks: [
            ...work.tasks,
            { ...work.tasks[0]!, task_id: 'next', title: '汇总', depends_on: ['t', 'missing'] }
          ]
        }
      }
    })
    expect(wrapper.text()).not.toContain('依赖：')
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '汇总')!
      .trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('依赖：核查、missing')
    wrapper.unmount()
  })
  it.each([
    { status: 'cancelled', run_status: 'failed' },
    { status: 'completed', run_status: 'running' },
    { status: 'active', run_status: 'failed' },
    { status: 'active', run_status: 'outcome_unknown' }
  ])('已结束工作或父执行不显示计划取消：$status/$run_status', (state) => {
    const wrapper = mount(TaskPlanCard, {
      props: {
        mode: 'detail',
        work: { ...work, ...state },
        session: 's',
        client: {} as Client
      }
    })
    expect(wrapper.findAll('button').some((button) => button.text() === '取消计划')).toBe(false)
    wrapper.unmount()
  })
  it.each(['plan', 'task'])('显示本次 %s 补充拒绝并保留草稿及重试身份', async (scope) => {
    const json = vi.fn().mockImplementation(async (path, _method, body) =>
      path.includes('/detail?')
        ? { processes: [], results: [] }
        : {
            ...work,
            control_inputs: [
              {
                input_id: 'another',
                task_id: '',
                operation: 'approve_plan',
                plan_revision: 2,
                state: 'APPLIED',
                detail: '操作已受理'
              },
              {
                input_id: body.input_id,
                task_id: body.task_id ?? '',
                operation: body.operation,
                plan_revision: body.plan_revision ?? null,
                state: 'REJECTED',
                detail: '操作未被受理'
              }
            ]
          }
    )
    const wrapper = mount(TaskPlanCard, {
      props: { mode: 'detail', work, session: 's', client: { json } as unknown as Client }
    })
    const click = (label: string) =>
      wrapper
        .findAll('button')
        .find((button) => button.text() === label)!
        .trigger('click')
    if (scope === 'task') await wrapper.setProps({ view: 'steps' })
    await click(scope === 'plan' ? '补充计划要求' : '核查')
    await flushPromises()
    await wrapper.get('textarea').setValue('保留这份补充要求')
    await click('提交补充')
    await flushPromises()
    expect(document.querySelectorAll('.chat-toast [role="alert"]')).toHaveLength(1)
    expect(document.querySelector('.chat-toast [role="alert"]')?.textContent).toContain(
      '操作未被受理'
    )
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toBe('保留这份补充要求')
    expect(wrapper.emitted('dirty')?.at(-1)).toEqual([true])
    await click('提交补充')
    await flushPromises()
    const writes = json.mock.calls.filter(([path]) => path === '/chat/work/controls')
    expect(writes).toHaveLength(2)
    expect(writes[0]![2].input_id).toBe(writes[1]![2].input_id)
    wrapper.unmount()
  })
  it.each(['PENDING', 'ACCEPTED', 'APPLIED'])(
    '只检查本次回执，%s 不显示其他输入的拒绝',
    async (state) => {
      const json = vi.fn().mockImplementation(async (_path, _method, body) => ({
        ...work,
        control_inputs: [
          {
            input_id: 'another',
            task_id: '',
            operation: 'approve_plan',
            plan_revision: 2,
            state: 'REJECTED',
            detail: '其他操作拒绝'
          },
          {
            input_id: body.input_id,
            task_id: '',
            operation: body.operation,
            plan_revision: body.plan_revision ?? null,
            state,
            detail: '正在处理操作'
          }
        ]
      }))
      const wrapper = mount(TaskPlanCard, {
        props: { mode: 'detail', work, session: 's', client: { json } as unknown as Client }
      })
      const click = (label: string) =>
        wrapper
          .findAll('button')
          .find((button) => button.text() === label)!
          .trigger('click')
      await click('补充计划要求')
      await wrapper.get('textarea').setValue('补充要求')
      await click('提交补充')
      await flushPromises()
      expect(wrapper.find('[role="alert"]').exists()).toBe(false)
      expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toBe('')
      if (state !== 'APPLIED') {
        await wrapper.get('textarea').setValue('补充要求')
        await click('提交补充')
        await flushPromises()
        expect(json.mock.calls[0]![2].input_id).toBe(json.mock.calls[1]![2].input_id)
      }
      wrapper.unmount()
    }
  )
  it('概览按精确版本批准并保持真实状态', async () => {
    const json = vi.fn().mockResolvedValue({ ...work, approved_revision: 2, revision: 2 })
    const wrapper = mount(TaskPlanCard, {
      props: { mode: 'detail', work, session: 's', client: { json } as unknown as Client }
    })
    expect(wrapper.text()).toContain('待批准第 2 版计划')
    await wrapper
      .findAll('button')
      .find((button) => button.text().includes('批准第 2 版计划'))!
      .trigger('click')
    await flushPromises()
    expect(wrapper.emitted('updated')?.[0]).toEqual([
      { ...work, approved_revision: 2, revision: 2 }
    ])
    expect(json).toHaveBeenCalledWith(
      '/chat/work/controls',
      'POST',
      expect.objectContaining({ work_id: 'w', plan_revision: 2, operation: 'approve_plan' }),
      expect.any(AbortSignal)
    )
    await wrapper.setProps({
      work: { ...work, revision: 3, run_revision: 3, run_status: 'completed' }
    })
    expect(wrapper.text()).toContain('已完成')
    expect(wrapper.find('ol').exists()).toBe(false)
    wrapper.unmount()
  })
  it('未确认写请求的重试保留请求身份', async () => {
    const json = vi.fn().mockRejectedValueOnce(null).mockResolvedValueOnce(work)
    const wrapper = mount(TaskPlanCard, {
      props: { mode: 'detail', work, session: 's', client: { json } as unknown as Client }
    })
    const approve = () =>
      wrapper
        .findAll('button')
        .find((button) => button.text().includes('批准第 2 版计划'))!
        .trigger('click')
    await approve()
    await flushPromises()
    expect(document.querySelector('.chat-toast')?.textContent).toContain(
      '操作结果尚未确认，请先查看最新任务状态'
    )
    expect(document.querySelector('.chat-toast')?.textContent).toContain(
      '保留原内容并再次执行同一操作'
    )
    await approve()
    await flushPromises()
    expect(json.mock.calls[0]![2].input_id).toBe(json.mock.calls[1]![2].input_id)
    wrapper.unmount()
  })
  it('详情首次读取失败可原地重读，保留步骤与补充草稿', async () => {
    const json = vi
      .fn()
      .mockRejectedValueOnce(new Error('暂时无法读取'))
      .mockResolvedValueOnce({ processes: [], results: [] })
    const wrapper = mount(TaskPlanCard, {
      props: {
        work,
        mode: 'detail',
        view: 'steps',
        session: 's',
        client: { json } as unknown as Client
      }
    })
    await wrapper.get('[data-task-id="t"]').trigger('click')
    await flushPromises()
    await wrapper.get('textarea').setValue('保留补充草稿')
    const retry = [...document.querySelectorAll<HTMLButtonElement>('.chat-toast button')].find(
      (button) => button.textContent === '重新读取'
    )!
    expect(retry).toBeDefined()
    retry.click()
    await flushPromises()
    expect(json.mock.calls[1]).toEqual(json.mock.calls[0])
    expect(json.mock.calls.every(([, method]) => method === 'GET')).toBe(true)
    expect(wrapper.get('h3').text()).toBe('核查')
    expect(wrapper.get('textarea').element.value).toBe('保留补充草稿')
    wrapper.unmount()
  })
  it('当前事件页没有公开过程时仍可继续读取后续页', async () => {
    const json = vi
      .fn()
      .mockResolvedValueOnce({ processes: [], results: [], has_more: true, next_cursor: 'page-2' })
      .mockResolvedValueOnce({
        processes: [{ process_id: 'p2', presentation: { title: '后续过程', summary: '已取得' } }],
        results: [],
        has_more: false
      })
    const wrapper = mount(TaskPlanCard, {
      props: {
        work,
        mode: 'detail',
        view: 'steps',
        session: 's',
        client: { json } as unknown as Client
      }
    })
    await wrapper.get('[data-task-id="t"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('当前页没有此步骤的公开过程')
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '加载更多过程')!
      .trigger('click')
    await flushPromises()
    expect(json.mock.calls[1]![0]).toContain('cursor=page-2')
    expect(wrapper.text()).toContain('后续过程')
    expect(wrapper.findAll('button').some((button) => button.text() === '加载更多过程')).toBe(false)
    wrapper.unmount()
  })
  it('跨页的同一过程按版本合并，保留已展开内容和补充草稿', async () => {
    const start = {
      process_id: 'p1',
      run_id: 'r',
      root_run_id: 'r',
      sequence: 1,
      ordinal: 1,
      updated_sequence: 1,
      status: 'running',
      type: 'tool',
      presentation: { title: '查询订单', summary: '执行中' }
    }
    const json = vi
      .fn()
      .mockResolvedValueOnce({
        processes: [start],
        results: [],
        has_more: true,
        next_cursor: 'page-2'
      })
      .mockResolvedValueOnce({
        processes: [
          {
            ...start,
            sequence: 2,
            updated_sequence: 2,
            status: 'succeeded',
            presentation: { title: '查询订单', summary: '查询完成' }
          }
        ],
        results: [],
        has_more: false
      })
    const wrapper = mount(TaskPlanCard, {
      props: {
        work,
        mode: 'detail',
        view: 'steps',
        session: 's',
        client: { json } as unknown as Client
      }
    })
    await wrapper.get('[data-task-id="t"]').trigger('click')
    await flushPromises()
    const details = wrapper.get('details.task-process-details')
    ;(details.element as HTMLDetailsElement).open = true
    await wrapper.get('textarea').setValue('继续保留草稿')
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '加载更多过程')!
      .trigger('click')
    await flushPromises()
    expect(wrapper.findAll('details.task-process-details article')).toHaveLength(1)
    expect(wrapper.text()).toContain('查询完成')
    expect(wrapper.text()).not.toContain('执行中')
    expect((details.element as HTMLDetailsElement).open).toBe(true)
    expect(wrapper.get('textarea').element.value).toBe('继续保留草稿')
    wrapper.unmount()
  })
  it('分页读取失败重读原 cursor，保留已有过程与草稿', async () => {
    const process = { process_id: 'p1', presentation: { title: '已有过程', summary: '已取得' } }
    const json = vi
      .fn()
      .mockResolvedValueOnce({
        processes: [process],
        results: [],
        has_more: true,
        next_cursor: 'page-2'
      })
      .mockRejectedValueOnce(new Error('分页读取失败'))
      .mockResolvedValueOnce({
        processes: [
          { ...process, process_id: 'p2', presentation: { title: '后续过程', summary: '新取得' } }
        ],
        results: [],
        has_more: false
      })
    const wrapper = mount(TaskPlanCard, {
      props: {
        work,
        mode: 'detail',
        view: 'steps',
        session: 's',
        client: { json } as unknown as Client
      }
    })
    await wrapper.get('[data-task-id="t"]').trigger('click')
    await flushPromises()
    await wrapper.get('textarea').setValue('分页时的草稿')
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '加载更多过程')!
      .trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('已有过程')
    const retry = [...document.querySelectorAll<HTMLButtonElement>('.chat-toast button')].find(
      (button) => button.textContent === '重新读取'
    )!
    retry.click()
    await flushPromises()
    expect(json.mock.calls[2]).toEqual(json.mock.calls[1])
    expect(json.mock.calls[2]![0]).toContain('cursor=page-2')
    expect(wrapper.text()).toContain('已有过程')
    expect(wrapper.text()).toContain('后续过程')
    expect(wrapper.get('textarea').element.value).toBe('分页时的草稿')
    wrapper.unmount()
  })
  it('详情读取失败后控制操作失败不展示读取重试', async () => {
    const json = vi
      .fn()
      .mockRejectedValueOnce(new Error('详情读取失败'))
      .mockRejectedValueOnce(new Error('操作被拒绝'))
    const wrapper = mount(TaskPlanCard, {
      props: {
        work,
        mode: 'detail',
        view: 'steps',
        session: 's',
        client: { json } as unknown as Client
      }
    })
    await wrapper.get('[data-task-id="t"]').trigger('click')
    await flushPromises()
    await wrapper.get('textarea').setValue('补充要求')
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '提交补充')!
      .trigger('click')
    await flushPromises()
    expect(document.querySelector('.chat-toast')?.textContent).toContain('操作被拒绝')
    expect(document.querySelector('.chat-toast')?.textContent).not.toContain('重新读取')
    expect(json.mock.calls[1]![0]).toBe('/chat/work/controls')
    expect(wrapper.get('textarea').element.value).toBe('补充要求')
    wrapper.unmount()
  })
})
