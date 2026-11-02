import { describe, expect, it, vi } from 'vitest'
import { acceptUnhighlightedCreate } from '@skillforge/shared/admin/creatableOption'

function eventFor(value: string, options: { active?: string; composing?: boolean } = {}) {
  const input = document.createElement('input')
  input.value = value
  input.setAttribute('role', 'combobox')
  input.setAttribute('aria-expanded', 'true')
  input.setAttribute('aria-activedescendant', options.active ?? '')
  const event = new KeyboardEvent('keydown', {
    key: 'Enter',
    cancelable: true,
    isComposing: options.composing
  })
  input.addEventListener('keydown', (current) =>
    acceptUnhighlightedCreate(current, true, ['已有命名空间'], accept)
  )
  const accept = vi.fn()
  input.dispatchEvent(event)
  return { event, accept }
}
describe('未高亮的新命名空间', () => {
  it('只接纳精确新值并阻止表单提交', () => {
    const { event, accept } = eventFor('new-space')
    expect(accept).toHaveBeenCalledExactlyOnceWith('new-space')
    expect(event.defaultPrevented).toBe(true)
  })
  it('已有标签、已高亮候选、输入法组合和空值交回原控件处理', () => {
    for (const result of [
      eventFor('已有命名空间'),
      eventFor('new-space', { active: 'option-1' }),
      eventFor('new-space', { composing: true }),
      eventFor('  ')
    ]) {
      expect(result.accept).not.toHaveBeenCalled()
      expect(result.event.defaultPrevented).toBe(false)
    }
  })
  it('没有创建授权时不接纳新值', () => {
    const input = document.createElement('input')
    input.value = 'not-authorized'
    input.setAttribute('role', 'combobox')
    input.setAttribute('aria-expanded', 'true')
    const event = new KeyboardEvent('keydown', { key: 'Enter', cancelable: true })
    const accept = vi.fn()
    input.addEventListener('keydown', (current) =>
      acceptUnhighlightedCreate(current, false, [], accept)
    )
    input.dispatchEvent(event)
    expect(accept).not.toHaveBeenCalled()
    expect(event.defaultPrevented).toBe(false)
  })
})
