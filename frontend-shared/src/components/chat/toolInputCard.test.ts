import { describe, expect, it } from 'vitest'
import { buildToolInputResponse, toolInputCardOf } from './toolInputCard'

const wait = { action_id: 'action-1', generation: '9007199254740993' }
const card = {
  type: 'tool_input',
  title: '请补充数量',
  tool_wait: wait,
  requestState: 'must-stay-on-server',
  requests: [
    {
      id: 'quantity',
      message: '请输入数量',
      requested_schema: { type: 'object', properties: { quantity: { type: 'integer' } } }
    }
  ]
}

describe('当前执行等待的公开补参协议', () => {
  it('高位代次按原文本续接且拒绝响应不会带入表单草稿或opaque状态', () => {
    const parsed = toolInputCardOf(card)!
    expect(parsed.toolWait).toEqual(wait)
    const response = buildToolInputResponse(
      { execution_id: 'run-1', run_revision: '9007199254740997', tool_wait: wait },
      parsed,
      'decline',
      { quantity: { quantity: 99 } }
    )
    expect(response).toEqual({
      execution_id: 'run-1',
      run_revision: '9007199254740997',
      tool_input: {
        wait,
        responses: {
          quantity: {
            method: 'elicitation/create',
            result: { schema_id: 'application/json', canonical_json: '{"action":"decline"}' }
          }
        }
      }
    })
    expect(JSON.stringify(response)).not.toContain('must-stay-on-server')
    expect(response.tool_input.responses.quantity.result.canonical_json).not.toContain('99')
  })

  it('相同Action的新等待代次和不规范版本都不能复用旧卡片', () => {
    const parsed = toolInputCardOf(card)!
    for (const pending of [
      { execution_id: 'run-1', run_revision: '3', tool_wait: { ...wait, generation: '2' } },
      { execution_id: 'run-1', run_revision: 3, tool_wait: wait },
      { execution_id: 'run-1', run_revision: '03', tool_wait: wait },
      { execution_id: 'run-1', run_revision: '3' }
    ])
      expect(() => buildToolInputResponse(pending, parsed, 'accept')).toThrow(
        '当前补充输入已失效，请重新读取会话，并按当前待办填写。'
      )
    for (const generation of ['0', '01', '+1', '1e3', '1.0', ' 1'])
      expect(toolInputCardOf({ ...card, tool_wait: { ...wait, generation } })).toBeUndefined()
  })

  it('接受响应保存标准方法、原始请求键和规范数值', () => {
    const response = buildToolInputResponse(
      { execution_id: 'run-1', run_revision: '7', tool_wait: wait },
      toolInputCardOf(card)!,
      'accept',
      { quantity: { quantity: 27, urgent: false } }
    )
    expect(response.tool_input.responses.quantity).toEqual({
      method: 'elicitation/create',
      result: {
        schema_id: 'application/json',
        canonical_json: '{"action":"accept","content":{"quantity":27,"urgent":false}}'
      }
    })
  })
})
