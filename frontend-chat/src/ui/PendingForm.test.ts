import { describe, expect, it } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import PendingForm from './PendingForm.vue'
import { ChatOption, ChatSelect } from './controls'
import type { Pending, Schema } from '../protocol'
type ToolSubmission = {
  tool_input: {
    wait: { action_id: string; generation: string }
    responses: Record<string, { result: { canonical_json: string } }>
  }
}
const pending: Pending = {
  execution_id: 'r',
  run_revision: '2',
  interaction_type: 'tool_input',
  tool_wait: { action_id: 'a', generation: '4' },
  card: {
    type: 'tool_input',
    title: '数量',
    requests: [
      {
        id: 'request',
        message: '请填写',
        requested_schema: {
          type: 'object',
          required: ['count'],
          properties: { count: { type: 'integer', title: '数量', minimum: 1 } }
        }
      }
    ]
  }
}
describe('独立工具补参表单', () => {
  it('历史未恢复时禁止提交，恢复后仍可提交原表单', async () => {
    const wrapper = mount(PendingForm, { props: { pending, busy: false, disabled: true } })
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('submit')).toBeUndefined()
    await wrapper.setProps({ disabled: false })
    await wrapper.get('input').setValue('2')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.emitted('submit')).toHaveLength(1)
    wrapper.unmount()
  })
  it('未提供描述的动态字段仍有可读取的类型、必填和范围说明', () => {
    const wrapper = mount(PendingForm, { props: { pending, busy: false } })
    const help = wrapper.get('[role="note"]')
    expect(help.text()).toContain('必填')
    expect(help.text()).toContain('整数')
    expect(help.text()).toContain('最小值 1')
    expect(wrapper.get('input').attributes('aria-describedby')).toBe(help.attributes('id'))
    wrapper.unmount()
  })
  it('保留声明的false和0默认值，密码不明文展示，长文本使用多行且未回答字段不提交', async () => {
    const schemaPending: Pending = {
      ...pending,
      card: {
        ...pending.card!,
        requests: [
          {
            id: 'request',
            message: '填写参数',
            requested_schema: {
              type: 'object',
              properties: {
                count: { type: 'integer', default: 0 },
                confirmed: { type: 'boolean', default: false },
                password: { type: 'string', format: 'password', default: 'secret-default' },
                details: { type: 'string', maxLength: 500 },
                optional: { type: 'boolean' }
              }
            }
          }
        ]
      }
    }
    const wrapper = mount(PendingForm, { props: { pending: schemaPending, busy: false } })
    expect(wrapper.get<HTMLInputElement>('input[type="password"]').element.value).toBe(
      'secret-default'
    )
    expect(wrapper.get('textarea').attributes('rows')).toBe('3')
    expect(wrapper.text()).toContain('默认值：false')
    expect(wrapper.text()).not.toContain('secret-default')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    const result = wrapper.emitted('submit')![0]![0] as ToolSubmission
    expect(JSON.parse(result.tool_input.responses.request.result.canonical_json)).toEqual({
      action: 'accept',
      content: { count: 0, confirmed: false, password: 'secret-default' }
    })
    wrapper.unmount()
  })
  it('不允许表单提交枚举之外的值', async () => {
    const wrapper = mount(PendingForm, {
      props: {
        pending: {
          ...pending,
          card: {
            ...pending.card!,
            requests: [
              {
                id: 'request',
                message: '选择范围',
                requested_schema: {
                  type: 'object',
                  properties: { scope: { type: 'string', enum: ['public'], default: 'private' } }
                }
              }
            ]
          }
        },
        busy: false
      }
    })
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.emitted('submit')).toBeUndefined()
    expect(wrapper.text()).toContain('请选择提供的选项')
    wrapper.unmount()
  })
  it('拒绝补参不发送用户草稿，保留等待身份', async () => {
    const wrapper = mount(PendingForm, { props: { pending, busy: false } })
    await wrapper.get('input').setValue('5')
    const button = wrapper.findAll('button').find((item) => item.text() === '拒绝提供')!
    await button.trigger('click')
    await flushPromises()
    const result = wrapper.emitted('submit')![0]![0] as ToolSubmission
    expect(result.tool_input.wait).toEqual({ action_id: 'a', generation: '4' })
    expect(result.tool_input.responses.request.result.canonical_json).toBe('{"action":"decline"}')
    wrapper.unmount()
  })
  it('非法数字不发送，修正后输出协议数值', async () => {
    const wrapper = mount(PendingForm, { props: { pending, busy: false } })
    await wrapper.get('input').setValue('0')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.emitted('submit')).toBeUndefined()
    await wrapper.get('input').setValue('2')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    const result = wrapper.emitted('submit')![0]![0] as ToolSubmission
    expect(JSON.parse(result.tool_input.responses.request.result.canonical_json)).toEqual({
      action: 'accept',
      content: { count: 2 }
    })
    wrapper.unmount()
  })
  it('失焦不推移提交入口，提交校验恢复输入后聚焦错误字段', async () => {
    const wrapper = mount(PendingForm, {
      attachTo: document.body,
      props: { pending, busy: false }
    })
    try {
      const input = wrapper.get<HTMLInputElement>('input')
      await input.setValue('0')
      await input.trigger('focusout')
      await flushPromises()
      expect(wrapper.find('[role="alert"]').exists()).toBe(false)
      await wrapper.get('form').trigger('submit')
      await flushPromises()
      expect(wrapper.emitted('submit')).toBeUndefined()
      expect(input.attributes('disabled')).toBeUndefined()
      expect(document.activeElement).toBe(input.element)
      expect(input.attributes('aria-invalid')).toBe('true')
    } finally {
      wrapper.unmount()
    }
  })
  it('提交期间所有输入继承表单禁用且不重复发送', async () => {
    const wrapper = mount(PendingForm, { props: { pending, busy: true } })
    expect(wrapper.get('input').attributes('disabled')).toBeDefined()
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.emitted('submit')).toBeUndefined()
    expect(
      wrapper.findAll('button').every((item) => item.attributes('disabled') !== undefined)
    ).toBe(true)
    wrapper.unmount()
  })
})

describe('紧凑补充信息', () => {
  const question: Pending = {
    execution_id: 'waiting-run',
    run_revision: '2',
    tool_wait: { action_id: 'ask-user', generation: '1' },
    interaction_type: 'user_input',
    question: '希望如何处理附件？'
  }
  it('重复问题不再显示，保留可访问标签、两行输入和图标发送', async () => {
    const wrapper = mount(PendingForm, {
      props: { pending: question, busy: false, questionDisplayed: true }
    })
    try {
      expect(wrapper.find('.pending-question').exists()).toBe(false)
      expect(wrapper.get('h3').text()).toBe('补充信息')
      expect(wrapper.find('.pending-form--simple').exists()).toBe(true)
      expect(wrapper.get('textarea').attributes('rows')).toBe('2')
      expect(wrapper.get('textarea').attributes('aria-labelledby')).toBe(
        wrapper.get('label').attributes('id')
      )
      expect(wrapper.get('[aria-label="提交信息"]').text()).toBe('')
      expect(wrapper.text()).not.toContain('Enter')
      await wrapper.get('form').trigger('submit')
      await flushPromises()
      expect(wrapper.emitted('submit')).toBeUndefined()
      await wrapper.get('textarea').setValue('请提取三条重点')
      await wrapper.get('form').trigger('submit')
      await flushPromises()
      expect(wrapper.emitted('submit')![0]![0]).toMatchObject({
        execution_id: 'waiting-run',
        human_input: {
          wait: { action_id: 'ask-user', generation: '1' },
          text: '请提取三条重点'
        }
      })
    } finally {
      wrapper.unmount()
    }
  })
  it('没有正文提问时完整显示问题，停止按钮只发出取消事件不提交草稿', async () => {
    const wrapper = mount(PendingForm, { props: { pending: question, busy: false } })
    try {
      expect(wrapper.get('.pending-question').text()).toBe(question.question)
      await wrapper.get('textarea').setValue('尚未提交')
      await wrapper.get('[aria-label="停止此等待任务"]').trigger('click')
      expect(wrapper.emitted('cancel')).toHaveLength(1)
      expect(wrapper.emitted('submit')).toBeUndefined()
    } finally {
      wrapper.unmount()
    }
  })
  it('计划审批保留计划和审批动作，说明仍可选', async () => {
    const wrapper = mount(PendingForm, {
      props: {
        pending: {
          ...pending,
          interaction_type: 'plan_review',
          checkpoint_revision: '3',
          action_id: 'a',
          invocation_id: 'i',
          pending_id: 'p',
          wake_id: 'w',
          wait_revision: '4',
          card: { title: '资料处理计划', plan: '先提取重点，再整理报告。' }
        },
        busy: false
      }
    })
    try {
      expect(wrapper.find('.pending-form--simple').exists()).toBe(false)
      expect(wrapper.text()).toContain('先提取重点，再整理报告。')
      expect(wrapper.text()).toContain('批准计划')
      expect(wrapper.text()).toContain('继续规划')
      await wrapper.get('form').trigger('submit')
      await flushPromises()
      expect(wrapper.emitted('submit')![0]![0]).toMatchObject({
        plan_review: { decision: 'approve', feedback: '' }
      })
    } finally {
      wrapper.unmount()
    }
  })
})

describe('工作流确认卡', () => {
  const confirmation: Pending = {
    execution_id: 'workflow-run',
    run_revision: '8',
    tool_wait: { action_id: 'workflow-action', generation: '2' },
    workflow_execution_id: 'workflow-execution',
    suspend_ref: 'workflow-wait',
    execution_revision: '9007199254740993',
    resume_action_key: 'frozen-workflow-resume-key',
    card: {
      type: 'confirm',
      title: '确认生成结果',
      body: '请确认继续生成经营说明。',
      actions: [{ id: 'confirm', label: '确认继续', style: 'primary' }]
    }
  }

  it('显示真实标题和正文，空说明可确认且保留准确Workflow和Run等待身份', async () => {
    const wrapper = mount(PendingForm, {
      props: { pending: confirmation, busy: false, questionDisplayed: true }
    })
    try {
      expect(wrapper.get('h3').text()).toBe('确认生成结果')
      expect(wrapper.text()).toContain('请确认继续生成经营说明。')
      expect(wrapper.find('.pending-question').exists()).toBe(false)
      expect(wrapper.find('.pending-form--simple').exists()).toBe(false)
      expect(wrapper.get('label').text()).toBe('补充说明（可选）')
      expect(wrapper.get('textarea').attributes('required')).toBeUndefined()
      expect(wrapper.get('button[type="submit"]').text()).toBe('确认继续')
      expect(wrapper.find('[aria-label="提交信息"]').exists()).toBe(false)
      expect(wrapper.text()).not.toContain('批准计划')

      await wrapper.get('form').trigger('submit')
      await flushPromises()
      expect(wrapper.emitted('submit')).toEqual([
        [
          {
            execution_id: 'workflow-run',
            run_revision: '8',
            workflow_input: {
              wait: { action_id: 'workflow-action', generation: '2' },
              execution_ref: 'workflow-execution',
              suspend_ref: 'workflow-wait',
              execution_revision: '9007199254740993',
              action_key: 'frozen-workflow-resume-key',
              response: {
                schema_id: 'application/json',
                canonical_json: '{"action":"confirm","response":""}'
              }
            }
          }
        ]
      ])
    } finally {
      wrapper.unmount()
    }
  })

  it('可选说明按原确认响应提交，连续确认不重复发送', async () => {
    const wrapper = mount(PendingForm, { props: { pending: confirmation, busy: false } })
    try {
      await wrapper.get('textarea').setValue('已核对说明')
      await Promise.all([
        wrapper.get('form').trigger('submit'),
        wrapper.get('form').trigger('submit')
      ])
      await flushPromises()
      expect(wrapper.emitted('submit')).toHaveLength(1)
      expect(wrapper.emitted('submit')![0]![0]).toMatchObject({
        workflow_input: {
          action_key: 'frozen-workflow-resume-key',
          response: {
            schema_id: 'application/json',
            canonical_json: '{"action":"confirm","response":"已核对说明"}'
          }
        }
      })
    } finally {
      wrapper.unmount()
    }
  })

  it('忙碌时不可确认，停止等待仍只发取消事件', async () => {
    const wrapper = mount(PendingForm, { props: { pending: confirmation, busy: true } })
    try {
      expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeDefined()
      await wrapper.get('form').trigger('submit')
      await flushPromises()
      expect(wrapper.emitted('submit')).toBeUndefined()
      await wrapper.setProps({ busy: false })
      await wrapper.get('[aria-label="停止此等待任务"]').trigger('click')
      expect(wrapper.emitted('cancel')).toHaveLength(1)
      expect(wrapper.emitted('submit')).toBeUndefined()
    } finally {
      wrapper.unmount()
    }
  })

  it('缺少恢复动作时不根据卡片确认按钮猜测路由', async () => {
    const wrapper = mount(PendingForm, {
      props: { pending: { ...confirmation, resume_action_key: undefined }, busy: false }
    })
    try {
      await wrapper.get('form').trigger('submit')
      await flushPromises()
      expect(wrapper.emitted('submit')).toBeUndefined()
      expect(wrapper.text()).toContain('工作流等待身份不完整')
    } finally {
      wrapper.unmount()
    }
  })

  it('任意卡片动作不转成确认权限，显式用户补充仍需填写', async () => {
    for (const value of [
      {
        ...confirmation,
        card: {
          ...confirmation.card,
          actions: [{ id: 'approve-external', type: 'confirm', label: '批准外部操作' }]
        }
      },
      { ...confirmation, interaction_type: 'user_input' }
    ]) {
      const wrapper = mount(PendingForm, { props: { pending: value, busy: false } })
      try {
        expect(wrapper.get('h3').text()).toBe('补充信息')
        expect(wrapper.find('.pending-form--simple').exists()).toBe(true)
        expect(wrapper.get('textarea').attributes('required')).toBeDefined()
        expect(wrapper.findAll('button').some((button) => button.text() === '批准外部操作')).toBe(
          false
        )
        await wrapper.get('form').trigger('submit')
        await flushPromises()
        expect(wrapper.emitted('submit')).toBeUndefined()
      } finally {
        wrapper.unmount()
      }
    }
  })

  it('计划审批优先使用原计划动作，不因同名确认卡转成工作流确认', async () => {
    const wrapper = mount(PendingForm, {
      props: {
        pending: {
          ...pending,
          ...confirmation,
          interaction_type: 'plan_review',
          action_id: 'a',
          invocation_id: 'i',
          pending_id: 'p',
          wake_id: 'w',
          wait_revision: '4',
          card: { ...confirmation.card, plan: '等待批准的任务计划' }
        },
        busy: false
      }
    })
    try {
      expect(wrapper.get('h3').text()).toBe('确认计划')
      expect(wrapper.text()).toContain('等待批准的任务计划')
      expect(wrapper.text()).not.toContain('请确认继续生成经营说明。')
      expect(wrapper.get('button[type="submit"]').text()).toBe('批准计划')
      expect(wrapper.text()).toContain('继续规划')
      await wrapper.get('form').trigger('submit')
      await flushPromises()
      const submission = wrapper.emitted('submit')![0]![0] as Record<string, unknown>
      expect(submission.plan_review).toEqual({ decision: 'approve', feedback: '' })
      expect(submission).not.toHaveProperty('action')
      expect(submission).not.toHaveProperty('response')
    } finally {
      wrapper.unmount()
    }
  })
})

function toolPending(properties: Record<string, Schema>, required: string[] = []): Pending {
  return {
    ...pending,
    card: {
      requests: [
        {
          id: 'request',
          message: '请填写',
          requested_schema: { type: 'object', properties, required }
        }
      ]
    }
  }
}
function submittedContent(wrapper: ReturnType<typeof mount>) {
  const result = wrapper.emitted('submit')!.at(-1)![0] as ToolSubmission
  return JSON.parse(result.tool_input.responses.request.result.canonical_json).content
}
describe('补参选择的值与展示语义', () => {
  it('连续提交只发出一次，字段帮助可定位', async () => {
    const wrapper = mount(PendingForm, {
      props: {
        pending: toolPending({ note: { type: 'string', description: '填写补充说明' } }),
        busy: false
      }
    })
    const input = wrapper.get('input')
    const help = input.attributes('aria-describedby')
    expect(help).toBeTruthy()
    expect(
      wrapper
        .findAll('[role="note"]')
        .find((item) => item.attributes('id') === help)
        ?.text()
    ).toContain('填写补充说明')
    await input.setValue('说明')
    await Promise.all([
      wrapper.get('form').trigger('submit'),
      wrapper.get('form').trigger('submit')
    ])
    await flushPromises()
    expect(wrapper.emitted('submit')).toHaveLength(1)
    wrapper.unmount()
  })

  it('未填写的可选布尔项省略，服务端显式 false 默认值保留', async () => {
    const wrapper = mount(PendingForm, {
      props: {
        pending: toolPending({
          optional: { type: 'boolean' },
          configured: { type: 'boolean', default: false }
        }),
        busy: false
      }
    })
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(submittedContent(wrapper)).toEqual({ configured: false })
    wrapper.unmount()
  })
  it('必填布尔项必须明确选择，选择否后提交 false', async () => {
    const wrapper = mount(PendingForm, {
      props: {
        pending: toolPending({ confirmed: { type: 'boolean', title: '确认' } }, ['confirmed']),
        busy: false
      }
    })
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.emitted('submit')).toBeUndefined()
    const select = wrapper.getComponent(ChatSelect)
    expect(select.props('clearable')).toBe(false)
    expect(wrapper.findAllComponents(ChatOption).map((item) => item.props('label'))).toEqual([
      '是',
      '否'
    ])
    select.vm.$emit('update:modelValue', false)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(submittedContent(wrapper)).toEqual({ confirmed: false })
    wrapper.unmount()
  })
  it('枚举名称用于展示，原值用于提交，清空后省略可选字段', async () => {
    const wrapper = mount(PendingForm, {
      props: {
        pending: toolPending({
          delivery: {
            type: 'string',
            enum: ['express', 'standard'],
            enumNames: ['加急配送', '标准配送']
          }
        }),
        busy: false
      }
    })
    const select = wrapper.getComponent(ChatSelect)
    expect(select.props('clearable')).toBe(true)
    expect(wrapper.findAllComponents(ChatOption).map((item) => item.props('label'))).toEqual([
      '加急配送',
      '标准配送'
    ])
    select.vm.$emit('update:modelValue', 'standard')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(submittedContent(wrapper)).toEqual({ delivery: 'standard' })
    // 清除可选字段时保留 undefined，不把空值写成 null 或空代码。
    const clearValue = select.props('valueOnClear') as () => undefined
    select.vm.$emit('update:modelValue', clearValue())
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(submittedContent(wrapper)).toEqual({})
    wrapper.unmount()
  })
})
