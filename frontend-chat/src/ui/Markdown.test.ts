import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import Markdown from './Markdown.vue'
import { renderMermaid } from './mermaidRenderer'

vi.mock('./mermaidRenderer', () => ({
  renderMermaid: vi.fn().mockResolvedValue('<svg viewBox="0 0 100 80"><text>流程</text></svg>')
}))

beforeEach(() => {
  vi.mocked(renderMermaid).mockClear()
  // DOM 稳定性与安全回归直接检查真实快照；帧调度行为由 streamingText 独立控制验收。
  vi.stubGlobal(
    'matchMedia',
    vi.fn(() => ({ matches: true, addEventListener: vi.fn(), removeEventListener: vi.fn() }))
  )
})
afterEach(() => vi.unstubAllGlobals())

describe('聊天正文富内容呈现', () => {
  it('帧级增量渲染真实尾部，同时保留旧段落DOM；终态立即补齐完整快照', async () => {
    const frames = new Map<number, FrameRequestCallback>()
    let nextId = 0
    vi.stubGlobal(
      'matchMedia',
      vi.fn(() => ({ matches: false, addEventListener: vi.fn(), removeEventListener: vi.fn() }))
    )
    vi.stubGlobal(
      'requestAnimationFrame',
      vi.fn((callback: FrameRequestCallback) => {
        const id = ++nextId
        frames.set(id, callback)
        return id
      })
    )
    vi.stubGlobal(
      'cancelAnimationFrame',
      vi.fn((id: number) => frames.delete(id))
    )
    const clock = vi.spyOn(performance, 'now').mockReturnValue(0),
      prefix = '# 标题\n\n历史完整段落。\n\n',
      target = `${prefix}正在${'甲'.repeat(120)}`,
      wrapper = mount(Markdown, { props: { text: `${prefix}正在`, streaming: true } })
    try {
      const heading = wrapper.get('h1').element,
        paragraph = wrapper.get('p').element
      await wrapper.setProps({ text: target })
      expect(wrapper.findAll('p').at(-1)?.text()).toBe('正在')
      for (let index = 1; index <= 3; index++) {
        const callbacks = [...frames.values()]
        frames.clear()
        callbacks.forEach((callback) => callback(index * 16))
        await flushPromises()
        expect(wrapper.get('h1').element).toBe(heading)
        expect(wrapper.get('p').element).toBe(paragraph)
        const tail = wrapper.findAll('p').at(-1)!.text()
        expect(tail.length).toBeGreaterThan(2)
        expect(tail.length).toBeLessThan(122)
        expect(`正在${'甲'.repeat(120)}`.startsWith(tail)).toBe(true)
      }
      await wrapper.setProps({ streaming: false })
      expect(wrapper.findAll('p').at(-1)?.text()).toBe(`正在${'甲'.repeat(120)}`)
      expect(frames.size).toBe(0)
      expect(wrapper.get('p').element).toBe(paragraph)
    } finally {
      wrapper.unmount()
      clock.mockRestore()
    }
  })

  it('真实尾部增长和新段落追加保留已完成标题、段落、列表的DOM身份', async () => {
    const prefix = '# 标题\n\n首段 **说明**。\n\n- 一项\n- 两项\n\n列表之后。\n\n'
    const wrapper = mount(Markdown, { props: { text: `${prefix}正在`, streaming: true } })
    const heading = wrapper.get('h1').element
    const paragraph = wrapper.get('p').element
    const emphasis = wrapper.get('strong').element
    const list = wrapper.get('ul').element
    const items = wrapper.findAll('li').map((item) => item.element)
    for (const tail of [
      '正在增长',
      '正在增长的真实正文',
      '正在增长的真实正文\n\n下一段已立即出现'
    ]) {
      await wrapper.setProps({ text: `${prefix}${tail}` })
      expect(wrapper.get('h1').element).toBe(heading)
      expect(wrapper.get('p').element).toBe(paragraph)
      expect(wrapper.get('strong').element).toBe(emphasis)
      expect(wrapper.get('ul').element).toBe(list)
      wrapper.findAll('li').forEach((item, index) => expect(item.element).toBe(items[index]))
      for (const fragment of tail.split('\n\n'))
        expect(wrapper.findAll('p').map((item) => item.text())).toContain(fragment)
    }
    expect(wrapper.get('.markdown').attributes('aria-busy')).toBe('true')
    wrapper.unmount()
  })

  it.each(['completed', 'cancelled', 'failed'])(
    '%s收口立即展示最后累计快照，不播放或等待未收到的文本',
    async () => {
      const prefix = '已有的完整段落。\n\n'
      const wrapper = mount(Markdown, { props: { text: `${prefix}最后`, streaming: true } })
      const paragraph = wrapper.get('p').element
      await wrapper.setProps({ text: `${prefix}最后累计快照完整到达。`, streaming: false })
      expect(wrapper.findAll('p').map((item) => item.text())).toEqual([
        '已有的完整段落。',
        '最后累计快照完整到达。'
      ])
      expect(wrapper.get('p').element).toBe(paragraph)
      expect(wrapper.get('.markdown').attributes('aria-busy')).toBeUndefined()
      wrapper.unmount()
    }
  )

  it('流式拆块仍净化链接和HTML，闭合或停止后的代码源码保持转义', async () => {
    const prefix = '[安全来源](https://example.com)\n\n[危险来源](javascript:alert(1))\n\n'
    const code = '```html\n</pre><img src=x onerror=alert(1)><script>alert(2)</script>'
    const wrapper = mount(Markdown, { props: { text: `${prefix}${code}`, streaming: true } })
    const safeLink = wrapper.get('a[href="https://example.com"]').element
    expect(wrapper.find('a[href^="javascript:"]').exists()).toBe(false)
    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.find('script').exists()).toBe(false)
    expect(wrapper.get('.markdown-pending').text()).toBe(code)
    await wrapper.setProps({
      text: `${prefix}${code}\n\`\`\`\n\n<p onclick="alert(3)" style="position:fixed">后续正文</p>`
    })
    expect(wrapper.get('a[href="https://example.com"]').element).toBe(safeLink)
    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.find('script').exists()).toBe(false)
    expect(wrapper.get('code').text()).toContain('<img src=x onerror=alert(1)>')
    expect(wrapper.html()).not.toMatch(/onclick=|style="position:fixed"|href="javascript:/)
    await wrapper.setProps({ streaming: false })
    expect(wrapper.get('a[href="https://example.com"]').element).toBe(safeLink)
    expect(wrapper.text()).toContain('后续正文')
    wrapper.unmount()
  })

  it('流式未闭合富内容按转义文本显示，闭合之后才渲染且后续快照不重绘', async () => {
    const source = '```mermaid\ngraph TD\nA-->B'
    const wrapper = mount(Markdown, { props: { text: source, streaming: true } })
    expect(wrapper.get('.markdown-pending').text()).toBe(source)
    expect(renderMermaid).not.toHaveBeenCalled()
    await wrapper.setProps({ text: `${source}\n\`\`\`` })
    await flushPromises()
    expect(wrapper.find('.markdown-pending').exists()).toBe(false)
    expect(renderMermaid).toHaveBeenCalledTimes(1)
    const svg = wrapper.get('svg').element
    await wrapper.setProps({ text: `${source}\n\`\`\`\n\n后续安全正文` })
    await flushPromises()
    expect(wrapper.get('svg').element).toBe(svg)
    expect(renderMermaid).toHaveBeenCalledTimes(1)
    await wrapper.setProps({ text: '```chart\n{"label":"<img src=x onerror=alert(1)>"}' })
    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.get('.markdown-pending').text()).toContain('<img src=x onerror=alert(1)>')
    wrapper.unmount()
  })
  it('累计追加正文不重新绘制已完成流程图，未闭合时不绘制', async () => {
    const source = '```mermaid\ngraph TD\nA-->B'
    const wrapper = mount(Markdown, { props: { text: source } })
    expect(renderMermaid).not.toHaveBeenCalled()
    await wrapper.setProps({ text: `${source}\n\`\`\`` })
    await flushPromises()
    const svg = wrapper.get('svg').element
    await wrapper.setProps({ text: `${source}\n\`\`\`\n\n继续说明` })
    await flushPromises()
    expect(renderMermaid).toHaveBeenCalledTimes(1)
    expect(wrapper.get('svg').element).toBe(svg)
    wrapper.unmount()
  })

  it('图表之前的多个普通块不因尾部追加重挂载富内容或历史正文', async () => {
    const source = '# 说明\n\n第一段。\n\n第二段。\n\n```mermaid\ngraph TD\nA-->B\n```\n\n'
    const wrapper = mount(Markdown, { props: { text: `${source}尾部`, streaming: true } })
    await flushPromises()
    const svg = wrapper.get('svg').element
    const paragraphs = wrapper
      .findAll('p')
      .slice(0, 2)
      .map((item) => item.element)
    for (const tail of ['尾部增加', '尾部增加\n\n再一段。']) {
      await wrapper.setProps({ text: `${source}${tail}` })
      await flushPromises()
      expect(wrapper.get('svg').element).toBe(svg)
      wrapper
        .findAll('p')
        .slice(0, 2)
        .forEach((item, index) => expect(item.element).toBe(paragraphs[index]))
      expect(renderMermaid).toHaveBeenCalledTimes(1)
    }
    wrapper.unmount()
  })

  it('数据标签作为文本渲染，图表使用明确刻度', () => {
    const source = JSON.stringify({
      type: 'bar',
      max: 100,
      unit: '%',
      items: [{ label: '<img src=x onerror=alert(1)>', value: 60 }]
    })
    const wrapper = mount(Markdown, { props: { text: `\`\`\`chart\n${source}\n\`\`\`` } })
    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.text()).toContain('<img src=x onerror=alert(1)>')
    expect(wrapper.get('.chart-track span').attributes('style')).toContain('width: 60%')
    expect(wrapper.text()).toContain('刻度上限 100%')
    wrapper.unmount()
  })

  it('非法图表保留原始数据，不阻止后续正文', () => {
    const wrapper = mount(Markdown, {
      props: { text: '```chart\n{"type":"unknown"}\n```\n\n后续正文' }
    })
    expect(wrapper.find('.rich-chart').exists()).toBe(false)
    expect(wrapper.get('code').text()).toContain('unknown')
    expect(wrapper.text()).toContain('后续正文')
    wrapper.unmount()
  })
})
