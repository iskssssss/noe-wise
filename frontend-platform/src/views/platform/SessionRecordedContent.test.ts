import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import SessionRecordedContent from './SessionRecordedContent.vue'
import type { RecordedContent } from '../../api/sessionObservation'

const viewer = {
  name: 'JsonViewer',
  props: ['value', 'label', 'height'],
  template: '<div class="json-viewer-stub" />'
}

function render(value: unknown, availability: RecordedContent['availability'] = 'FULL') {
  const content: RecordedContent = {
    availability,
    source: 'RUNTIME_RECORD',
    schema_id: 'test.v1',
    value
  }
  return mount(SessionRecordedContent, {
    props: { content, label: '原始记录' },
    global: { stubs: { JsonViewer: viewer } }
  })
}

describe('平台已记录内容的JSON展示接线', () => {
  it('原始JSON字符串未经parse/stringify直接传递，保留超大整数及小数词法', () => {
    const raw = '{"id":9007199254740993123456789,"decimal":1.2300e+2,"nested":{"items":[1,2]}}'
    const wrapper = render(raw)
    expect(wrapper.getComponent({ name: 'JsonViewer' }).props('value')).toBe(raw)
    expect(wrapper.getComponent({ name: 'JsonViewer' }).props('label')).toBe('原始记录')
    expect(wrapper.text()).toContain('RUNTIME_RECORD')
    expect(wrapper.text()).toContain('test.v1')
    wrapper.unmount()
  })

  it('对象和普通文本均直接传递，部分记录提示保持可见', () => {
    const original = { nested: { id: '9007199254740993123456789' }, extra: ['保留原字段'] }
    const partial = render(original, 'PARTIAL')
    expect(partial.getComponent({ name: 'JsonViewer' }).props('value')).toEqual(original)
    expect(partial.text()).toContain('以下仅展示已记录部分')
    partial.unmount()
    const plain = render('模型返回的普通文本')
    expect(plain.getComponent({ name: 'JsonViewer' }).props('value')).toBe('模型返回的普通文本')
    plain.unmount()
  })

  it('已记录JSON null、空正文和未记录互不混淆，隐藏状态不渲染意外附带的值', () => {
    const literalNull = render(null)
    expect(literalNull.getComponent({ name: 'JsonViewer' }).props('value')).toBeNull()
    literalNull.unmount()
    const empty = render('')
    expect(empty.text()).toContain('已记录空正文')
    expect(empty.findComponent({ name: 'JsonViewer' }).exists()).toBe(false)
    empty.unmount()
    const missing = render('不可展示的意外值', 'NOT_RECORDED')
    expect(missing.text()).toContain('未记录')
    expect(missing.text()).not.toContain('不可展示的意外值')
    expect(missing.findComponent({ name: 'JsonViewer' }).exists()).toBe(false)
    missing.unmount()
  })
})
