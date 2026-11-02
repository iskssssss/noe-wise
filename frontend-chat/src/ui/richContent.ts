import DOMPurify from 'dompurify'
import { marked, type Token, type TokensList } from 'marked'

export type MarkdownBlock =
  | { kind: 'html'; html: string }
  | { kind: 'text'; text: string }
  | { kind: 'mermaid'; source: string }
  | { kind: 'chart'; source: string }

export const MAX_RICH_BLOCK_LENGTH = 20_000

function closedFence(raw: string): boolean {
  const lines = raw.trimEnd().split('\n')
  const opening = /^ {0,3}(`{3,}|~{3,})[^\n]*$/.exec(lines[0] ?? '')
  if (!opening || lines.length < 2) return false
  const fence = opening[1]!
  return new RegExp(`^ {0,3}${fence[0]}{${fence.length},}[ \\t]*$`).test(lines.at(-1)!)
}

/** 每个顶层块独立净化；保留整篇引用定义，避免拆块后丢失跨段链接。 */
function renderMarkdownToken(token: Token, links: TokensList['links']): MarkdownBlock {
  const html = marked.parser(Object.assign([token], { links }) as TokensList)
  const content = document.createElement('div')
  content.innerHTML = DOMPurify.sanitize(html, {
    FORBID_TAGS: ['style', 'iframe', 'form', 'input', 'button', 'svg', 'math'],
    FORBID_ATTR: ['style'],
    ADD_ATTR: ['target', 'rel']
  })
  // 阅读资料时保留当前会话和未发送草稿；知识依据锚点仍在本页定位。
  for (const link of content.querySelectorAll('a[href]')) {
    if (link.getAttribute('href')!.startsWith('#')) continue
    link.setAttribute('target', '_blank')
    link.setAttribute('rel', 'noopener noreferrer')
  }
  return {
    kind: 'html',
    html: content.innerHTML
  }
}

/** 顶层段落、列表、标题独立成块，累计尾部变化不替换已有 DOM；富内容只提升闭合源码。 */
export function splitMarkdown(text: string, streaming = false): MarkdownBlock[] {
  const tokens = marked.lexer(text)
  const blocks: MarkdownBlock[] = []
  for (const token of tokens) {
    // 空白没有可见正文，不占稳定块序号；新的分隔空行不能让后续图表重新挂载。
    if (token.type === 'space') continue
    const language = token.type === 'code' ? token.lang?.trim().toLowerCase() : ''
    if (streaming && token.type === 'code' && !closedFence(token.raw)) {
      blocks.push({ kind: 'text', text: token.raw })
      continue
    }
    if (
      token.type === 'code' &&
      (language === 'mermaid' || language === 'chart') &&
      token.text.length <= MAX_RICH_BLOCK_LENGTH &&
      closedFence(token.raw)
    ) {
      blocks.push({ kind: language, source: token.text })
    } else {
      blocks.push(renderMarkdownToken(token, tokens.links))
    }
  }
  return blocks
}

export type BarChart = {
  type: 'bar'
  title?: string
  unit?: string
  max?: number
  items: { label: string; value: number }[]
}

function record(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

function label(value: unknown, limit: number): value is string {
  return typeof value === 'string' && value.trim().length > 0 && value.length <= limit
}

function amount(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value) && value >= 0 && value <= 1e12
}

/** 图表只接受封闭数据结构，不接受样式、脚本、链接或根据自然语言推断数值。 */
export function parseBarChart(source: string): BarChart | undefined {
  if (source.length > MAX_RICH_BLOCK_LENGTH) return
  let value: unknown
  try {
    value = JSON.parse(source)
  } catch {
    return
  }
  if (!record(value) || value.type !== 'bar') return
  if (Object.keys(value).some((key) => !['type', 'title', 'unit', 'max', 'items'].includes(key)))
    return
  if ('title' in value && !label(value.title, 160)) return
  if ('unit' in value && !label(value.unit, 12)) return
  if ('max' in value && (!amount(value.max) || value.max === 0)) return
  if (!Array.isArray(value.items) || value.items.length < 1 || value.items.length > 12) return
  if (
    !value.items.every(
      (item: unknown) =>
        record(item) &&
        Object.keys(item).every((key) => key === 'label' || key === 'value') &&
        label(item.label, 120) &&
        amount(item.value) &&
        (value.max === undefined || item.value <= (value.max as number))
    )
  )
    return
  return value as BarChart
}

/** 本轮开放流程图；配置、CSS、HTML、外部资源和点击行为不进入 Mermaid。 */
export function safeMermaidSource(source: string): boolean {
  if (source.length > MAX_RICH_BLOCK_LENGTH) return false
  if (/%%\s*\{/.test(source)) return false
  const content = source.replace(/^\s*%%(?!\{)[^\n]*(?:\n|$)/gm, '').trim()
  if (!/^(?:flowchart|graph)\s+(?:TB|TD|BT|LR|RL)\b/.test(content)) return false
  return !/(?:<|@\s*\{|\b(?:click|style|classDef|linkStyle)\b)/i.test(content)
}
