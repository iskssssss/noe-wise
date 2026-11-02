import { describe, expect, it } from 'vitest'
import { parseBarChart, safeMermaidSource, splitMarkdown } from './richContent'

describe('公开 Markdown 富内容块', () => {
  it('完整块转为图表，未闭合或嵌套源码保留为代码', () => {
    const pending = splitMarkdown('说明\n\n```mermaid\ngraph TD\nA-->B')
    expect(pending.map((block) => block.kind)).toEqual(['html', 'html'])
    const complete = splitMarkdown('说明\n\n```mermaid\ngraph TD\nA-->B\n```\n\n结束')
    expect(complete.map((block) => block.kind)).toEqual(['html', 'mermaid', 'html'])
    expect(complete[1]).toEqual({ kind: 'mermaid', source: 'graph TD\nA-->B' })
    expect(
      splitMarkdown('````text\n```mermaid\ngraph TD\nA-->B\n```\n````').map((block) => block.kind)
    ).toEqual(['html'])
    expect(splitMarkdown('````mermaid\ngraph TD\nA-->B\n```').map((block) => block.kind)).toEqual([
      'html'
    ])
  })

  it('顶层正文独立拆分，空白不占序号，尾部增长不改变已完成块HTML', () => {
    const source = '# 标题\n\n首段 **说明**。\n\n- 一项\n- 两项\n\n尾部'
    const before = splitMarkdown(source, true)
    const after = splitMarkdown(`${source}仍在增加\n\n新段落`, true)
    expect(before.map((block) => block.kind)).toEqual(['html', 'html', 'html', 'html'])
    expect(after.slice(0, 3)).toEqual(before.slice(0, 3))
    expect(after).toHaveLength(5)
    expect(splitMarkdown(`${source}\n\n\n\n`, true)).toEqual(before)
  })

  it('普通 HTML 继续净化且跨图表的引用定义仍然可用', () => {
    const blocks = splitMarkdown(
      '[来源][ref]\n\n```chart\n{}\n```\n\n[ref]: https://example.com\n\n<style>body{display:none}</style><iframe src="https://example.com"></iframe><img src=x onerror="alert(1)"><script>alert(1)</script><p style="position:fixed">文本</p>'
    )
    const html = blocks
      .filter((block) => block.kind === 'html')
      .map((block) => block.html)
      .join('')
    expect(html).toContain('href="https://example.com"')
    expect(html).not.toMatch(/<style|<iframe|<script|onerror|style=/)
    expect(html).toContain('文本')
  })
})

describe('条形图封闭数据结构', () => {
  const data = {
    type: 'bar',
    title: '建议投入比例',
    unit: '%',
    max: 100,
    items: [
      { label: '复购', value: 60 },
      { label: '新客', value: 40 }
    ]
  }
  it('保留明确数值和刻度，不从正文推测比例', () => {
    expect(parseBarChart(JSON.stringify(data))).toEqual(data)
    expect(parseBarChart('复购 60%，新客 40%')).toBeUndefined()
    expect(
      parseBarChart(JSON.stringify({ type: 'bar', items: [{ label: '增长', value: 0.2 }] }))
    ).toBeDefined()
  })
  it('拒绝未知字段、越界和非数值数据', () => {
    for (const invalid of [
      { ...data, script: 'alert(1)' },
      { ...data, max: 0 },
      { ...data, items: [{ label: '超额', value: 101 }] },
      { ...data, items: [{ label: '负值', value: -1 }] },
      { ...data, items: [{ label: '文本值', value: '60' }] },
      { ...data, items: [{ label: '样式', value: 60, color: 'red' }] },
      { ...data, items: [] },
      { ...data, items: Array.from({ length: 13 }, () => ({ label: '项目', value: 1 })) }
    ])
      expect(parseBarChart(JSON.stringify(invalid))).toBeUndefined()
    expect(parseBarChart('{"type":"bar","items":[{"label":"无限","value":1e400}]}')).toBeUndefined()
  })
})

describe('Mermaid 受控流程图', () => {
  it('允许流程和分支，拒绝配置、交互、CSS、HTML和外部资源', () => {
    expect(
      safeMermaidSource('flowchart TD\nA[检查指标] --> B{是否达标}\nB -->|是| C[继续执行]')
    ).toBe(true)
    expect(safeMermaidSource('%% 注释\ngraph LR\nA-->B')).toBe(true)
    for (const source of [
      'sequenceDiagram\nA->>B: hello',
      '%%{init: {"securityLevel":"loose"}}%%\ngraph TD\nA-->B',
      '%% {init: {"securityLevel":"loose"}}%%\ngraph TD\nA-->B',
      '---\nconfig:\n securityLevel: loose\n---\ngraph TD\nA-->B',
      'graph TD\nA-->B\nclick A "https://example.com"',
      'graph TD\nA-->B\nclassDef custom fill:red',
      'graph TD\nA[<img src=x>]',
      'graph TD\nA@{ img: "https://example.com/a.png" }'
    ])
      expect(safeMermaidSource(source)).toBe(false)
  })
})
