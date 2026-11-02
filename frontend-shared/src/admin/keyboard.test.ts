import { describe, expect, it } from 'vitest'
import { guardFormEnter } from './keyboard'

describe('表单输入法提交保护', () => {
  it('阻止合成Enter，保留普通Enter和合成文字输入', () => {
    const composing = new KeyboardEvent('keydown', {
      key: 'Enter',
      isComposing: true,
      cancelable: true
    })
    guardFormEnter(composing)
    expect(composing.defaultPrevented).toBe(true)
    const regular = new KeyboardEvent('keydown', { key: 'Enter', cancelable: true })
    guardFormEnter(regular)
    expect(regular.defaultPrevented).toBe(false)
    const character = new KeyboardEvent('keydown', {
      key: 'Process',
      code: 'KeyA',
      keyCode: 229,
      isComposing: true,
      cancelable: true
    })
    guardFormEnter(character)
    expect(character.defaultPrevented).toBe(false)
  })
})
