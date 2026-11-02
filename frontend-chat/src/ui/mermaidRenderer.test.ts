import { describe, expect, it, vi } from 'vitest'
import mermaid from 'mermaid'
import { renderMermaid } from './mermaidRenderer'

vi.mock('mermaid', () => ({
  default: {
    initialize: vi.fn(),
    render: vi.fn().mockResolvedValue({
      svg: '<svg viewBox="0 0 100 80"><style>body{display:none}</style><foreignObject><div>HTML</div></foreignObject><image href="https://example.com/image.png"/><path style="fill:red" d="M0 0L1 1" onload="alert(1)"/><text>节点</text></svg>'
    })
  }
}))

describe('流程图执行边界', () => {
  it('渲染器强制strict并净化SVG，结束后清理临时DOM', async () => {
    const count = document.body.childElementCount
    const svg = await renderMermaid('graph TD\nA-->B')
    expect(mermaid.initialize).toHaveBeenCalledWith(
      expect.objectContaining({ securityLevel: 'strict', startOnLoad: false, htmlLabels: false })
    )
    expect(svg).toContain('<text>节点</text>')
    expect(svg).not.toMatch(/<style|<foreignObject|<image|href=|style=|onload=/i)
    expect(document.body.childElementCount).toBe(count)
    await expect(renderMermaid('graph TD\nclick A "https://example.com"')).rejects.toThrow()
    expect(mermaid.render).toHaveBeenCalledTimes(1)
  })
})
