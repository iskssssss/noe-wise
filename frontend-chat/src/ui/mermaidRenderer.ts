import DOMPurify from 'dompurify'
import { safeMermaidSource } from './richContent'

let mermaidModule: Promise<(typeof import('mermaid'))['default']> | undefined
let nextDiagram = 0

/** Mermaid 按需本地加载；生成 SVG 再次净化，正文不能提供 CSS 或可执行行为。 */
export async function renderMermaid(source: string): Promise<string> {
  if (!safeMermaidSource(source)) throw new Error('不支持的流程图语法')
  mermaidModule ??= import('mermaid').then(({ default: mermaid }) => {
    mermaid.initialize({
      startOnLoad: false,
      securityLevel: 'strict',
      suppressErrorRendering: true,
      theme: 'neutral',
      fontFamily: 'system-ui, sans-serif',
      maxTextSize: 20_000,
      maxEdges: 200,
      flowchart: { htmlLabels: false, useMaxWidth: true },
      htmlLabels: false
    })
    return mermaid
  })
  const mermaid = await mermaidModule
  const container = document.createElement('div')
  container.setAttribute('aria-hidden', 'true')
  container.style.cssText =
    'position:absolute;left:-10000px;top:0;visibility:hidden;pointer-events:none'
  document.body.append(container)
  try {
    const { svg } = await mermaid.render(`chat-diagram-${++nextDiagram}`, source, container)
    const fragment = DOMPurify.sanitize(svg, {
      RETURN_DOM_FRAGMENT: true,
      USE_PROFILES: { svg: true, svgFilters: true },
      FORBID_TAGS: [
        'style',
        'foreignObject',
        'a',
        'image',
        'use',
        'script',
        'iframe',
        'animate',
        'animateMotion',
        'animateTransform',
        'set'
      ],
      FORBID_ATTR: ['style', 'href', 'xlink:href']
    })
    const diagram = fragment.querySelector('svg')
    if (!diagram) throw new Error('流程图未生成有效 SVG')
    const bounds = diagram
      .getAttribute('viewBox')
      ?.trim()
      .split(/[\s,]+/)
      .map(Number)
    // 使用受控数值恢复自然尺寸，避免净化 max-width 后将小图放大至整栏宽度。
    if (bounds?.length === 4 && bounds.every(Number.isFinite) && bounds[2]! > 0 && bounds[3]! > 0) {
      diagram.setAttribute('width', String(bounds[2]))
      diagram.setAttribute('height', String(bounds[3]))
    }
    return diagram.outerHTML
  } finally {
    container.remove()
  }
}
