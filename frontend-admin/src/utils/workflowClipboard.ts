import { isExecutableWorkflowNodeType, type BuilderEdge, type BuilderNode } from './workflow'

export interface WorkflowClipboard {
  nodes: BuilderNode[]
  edges: BuilderEdge[]
}
export interface WorkflowPasteOffset {
  x: number
  y: number
}
export interface WorkflowPasteResult {
  nodes: BuilderNode[]
  edges: BuilderEdge[]
  selectedIds: string[]
}
type Namespace = 'state' | 'extracted'
interface ResultDestination {
  namespace: Namespace
  key?: string
  extracts: string[]
}
const RESULT_NODE_TYPES = new Set([
  'llm',
  'tool',
  'knowledge_query',
  'script',
  'human_input',
  'async_task'
])

/** 画布包含 Vue 响应式对象，不能直接 structuredClone；这里只复制 JSON 业务数据。 */
function deepClone<T>(value: T): T {
  if (Array.isArray(value)) return value.map((item: unknown) => deepClone(item)) as T
  if (value !== null && typeof value === 'object')
    return Object.fromEntries(
      Object.entries(value).map(([key, item]) => [key, deepClone(item)])
    ) as T
  return value
}
function requireCopyable(nodes: readonly BuilderNode[]) {
  for (const node of nodes) {
    if (node.type === 'start') throw Error('开始节点不能复制，请取消选择开始节点')
    if (!isExecutableWorkflowNodeType(node.type))
      throw Error(`当前不可执行的节点类型不能复制：${node.type}（${node.id}）`)
  }
  if (new Set(nodes.map((node) => node.id)).size !== nodes.length)
    throw Error('所选节点标识重复，无法复制')
}

/** 只捕获真实选中子图的普通边；菜单跳转及错误跳转仍保存在节点配置中。 */
export function captureWorkflowSelection(
  nodes: readonly BuilderNode[],
  edges: readonly BuilderEdge[],
  ids: readonly string[]
): WorkflowClipboard {
  const selected = new Set(ids)
  const captured = nodes.filter((node) => selected.has(node.id))
  if (captured.length !== selected.size) throw Error('所选节点已经变化，请重新选择后复制')
  requireCopyable(captured)
  return deepClone({
    nodes: captured,
    edges: edges.filter((edge) => selected.has(edge.source) && selected.has(edge.target))
  })
}
function destination(node: BuilderNode): ResultDestination | undefined {
  if (!RESULT_NODE_TYPES.has(node.type)) return undefined
  const to = node.resultTo ?? 'state'
  if (!/^(?:state|extracted)(?:\.[A-Za-z][A-Za-z0-9_]*)?$/.test(to))
    throw Error(`节点 ${node.id} 的结果位置不符合当前 Runtime 契约，无法安全粘贴`)
  const namespace: Namespace = to.startsWith('extracted') ? 'extracted' : 'state'
  const key = to.includes('.') ? to.slice(to.indexOf('.') + 1) : undefined
  const extracts =
    key === undefined && !['human_input', 'async_task'].includes(node.type)
      ? [
          ...new Set(
            (node.extractText ?? '')
              .split(/[,，\n]/)
              .map((key) => key.trim())
              .filter(Boolean)
          )
        ]
      : []
  return { namespace, key, extracts }
}
/** Map 的写入键按完整字段名比较；assign target 不是嵌套 setter。 */
function writtenPaths(node: BuilderNode): string[] {
  if (node.type === 'assign')
    return [
      ...new Set(
        node.assignPairs
          .filter((pair) => pair.target && pair.source)
          .map((pair) => 'state.' + pair.target)
      )
    ]
  const output = destination(node)
  if (!output) return []
  return output.extracts.length
    ? output.extracts.map((key) => output.namespace + '.' + key)
    : [output.namespace + '.' + (output.key ?? node.id)]
}
function copyName(
  base: string,
  occupied: Set<string>,
  alsoOccupied: (value: string) => boolean = () => false
): string {
  let value = base + '_copy',
    index = 2
  while (occupied.has(value) || alsoOccupied(value)) value = base + '_copy_' + index++
  occupied.add(value)
  return value
}
function fieldBase(value: string): string {
  const key = value.replace(/[^A-Za-z0-9_]/g, '_')
  return /^[A-Za-z]/.test(key) ? key : 'result_' + key
}
function addressable(key: string): boolean {
  return !!key && !/[.[\]{}$\s]/.test(key)
}

function referenceMapper(mapping: Map<string, string>) {
  const paths = [...mapping.keys()].sort((left, right) => right.length - left.length)
  return (reference: string): string => {
    const path = reference.trim()
    const bare = !/^(?:state|extracted|config|user|runtime)\./.test(path)
    const explicit = bare ? 'state.' + path : path
    const match = paths.find((path) => explicit === path || explicit.startsWith(path + '.'))
    if (!match) return reference
    const changed = mapping.get(match)! + explicit.slice(match.length)
    const result = bare ? changed.slice('state.'.length) : changed
    return (
      reference.slice(0, reference.indexOf(path)) +
      result +
      reference.slice(reference.indexOf(path) + path.length)
    )
  }
}
type RewriteReference = ReturnType<typeof referenceMapper>
function templates(text: string, rewrite: RewriteReference): string {
  return text.replace(/\$\{([^{}]+)}/g, (original, path: string) => {
    const result = rewrite(path)
    return result === path ? original : '${' + result + '}'
  })
}
/** 只替换 JSON 字符串值中的表达式，JSON 键、数字词法与其他字节均保持原样。 */
function jsonTemplates(
  text: string | undefined,
  rewrite: RewriteReference,
  field: string
): string | undefined {
  if (!text?.trim()) return text
  try {
    JSON.parse(text)
  } catch {
    throw Error(`${field} 不是完整 JSON，无法安全重映射复制节点的引用`)
  }
  return text.replace(/"(?:\\.|[^"\\])*"/g, (token: string, offset: number) => {
    if (
      text
        .slice(offset + token.length)
        .trimStart()
        .startsWith(':')
    )
      return token
    const value = JSON.parse(token) as string
    const result = templates(value, rewrite)
    return result === value ? token : JSON.stringify(result)
  })
}
/** 与 Runtime 一样跳过引号及占位符内部，只在表达式边界识别运算符。 */
function operatorIndex(text: string, operator: string): number {
  let quote = '',
    depth = 0
  for (let index = 0; index < text.length; index++) {
    const char = text[index]!
    if (quote) {
      if (char === '\\') index++
      else if (char === quote) quote = ''
    } else if (char === '"' || char === "'") quote = char
    else if (char === '$' && text[index + 1] === '{') depth++
    else if (char === '}' && depth > 0) depth--
    else if (!depth && text.startsWith(operator, index)) return index
  }
  return -1
}
function conditionUnit(text: string, rewrite: RewriteReference, first: boolean): string {
  const leading = first ? (text.match(/^\s*(?:!\s*)*/)?.[0] ?? '') : ''
  const unit = text.slice(leading.length)
  for (const operator of ['contains', 'matches', '>=', '<=', '==', '!=', 'in', '>', '<']) {
    const index = operatorIndex(unit, operator)
    if (index < 0) continue
    const left = unit.slice(0, index)
    // 比较右侧只解析字面量；裸左值也是字面量，不能当变量改写。
    return (
      leading +
      (/^\s*\$\{[^{}]+}\s*$/.test(left) ? templates(left, rewrite) : left) +
      unit.slice(index)
    )
  }
  if (/^\s*\$\{[^{}]+}\s*$/.test(unit)) return leading + templates(unit, rewrite)
  return leading + (/^\s*[^\s"'{}]+\s*$/.test(unit) ? rewrite(unit) : unit)
}
function condition(text: string | undefined, rewrite: RewriteReference): string | undefined {
  if (!text) return text
  const or = operatorIndex(text, '||'),
    and = operatorIndex(text, '&&')
  const split = or < 0 ? and : and < 0 ? or : Math.min(or, and)
  if (split < 0) return conditionUnit(text, rewrite, true)
  let result = '',
    rest = text,
    first = true
  while (rest) {
    const or = operatorIndex(rest, '||'),
      and = operatorIndex(rest, '&&')
    const index = or < 0 ? and : and < 0 ? or : Math.min(or, and)
    if (index < 0) return result + conditionUnit(rest, rewrite, first)
    result += conditionUnit(rest.slice(0, index), rewrite, first) + rest.slice(index, index + 2)
    rest = rest.slice(index + 2)
    first = false
  }
  return result
}
function rewriteNode(node: BuilderNode, rewrite: RewriteReference, ids: Map<string, string>) {
  if (['llm', 'human_input', 'menu'].includes(node.type) && node.prompt !== undefined)
    node.prompt = templates(node.prompt, rewrite)
  if (['output', 'card'].includes(node.type) && node.message !== undefined)
    node.message = templates(node.message, rewrite)
  if (node.type === 'knowledge_query' && node.knowledgeQuery !== undefined)
    node.knowledgeQuery = templates(node.knowledgeQuery, rewrite)
  if (node.type === 'tool' && node.argsText !== undefined)
    node.argsText = jsonTemplates(node.argsText, rewrite, node.id + '.args')
  if (node.type === 'script' && node.scriptInputText !== undefined)
    node.scriptInputText = jsonTemplates(node.scriptInputText, rewrite, node.id + '.args.input')
  if (['human_input', 'card'].includes(node.type)) {
    for (const key of ['cardTitle', 'cardBody', 'mergeMessageId'] as const)
      if (node[key] !== undefined) node[key] = templates(node[key], rewrite)
    if (node.cardFieldsText !== undefined)
      node.cardFieldsText = jsonTemplates(node.cardFieldsText, rewrite, node.id + '.card.fields')
    if (node.cardActionsText !== undefined)
      node.cardActionsText = jsonTemplates(node.cardActionsText, rewrite, node.id + '.card.actions')
  }
  if (['human_input', 'card', 'menu'].includes(node.type) && node.renderText !== undefined)
    node.renderText = jsonTemplates(node.renderText, rewrite, node.id + '.render')
  if (node.type === 'assign')
    for (const pair of node.assignPairs) {
      const source = pair.source
      pair.source = source.includes('${')
        ? templates(source, rewrite)
        : /^\s*[^\s"'{}]+\s*$/.test(source)
          ? rewrite(source)
          : source
    }
  if (node.type === 'menu')
    for (const action of node.menuActions) {
      action.to = ids.get(action.to) ?? action.to
      action.label = templates(action.label, rewrite)
    }
  if (node.onError?.startsWith('goto:'))
    node.onError = 'goto:' + (ids.get(node.onError.slice(5)) ?? node.onError.slice(5))
}

/** 同页粘贴只新增副本，保留原图；全局 assign target 不属于可自动改名的局部输出。 */
export function pasteWorkflowSelection(
  currentNodes: readonly BuilderNode[],
  currentEdges: readonly BuilderEdge[],
  clipboard: WorkflowClipboard,
  offset: WorkflowPasteOffset = { x: 40, y: 40 }
): WorkflowPasteResult {
  requireCopyable(clipboard.nodes)
  if (!Number.isFinite(offset.x) || !Number.isFinite(offset.y))
    throw Error('粘贴位置偏移必须是有限数值')
  const copied = deepClone(clipboard)
  const selected = new Set(copied.nodes.map((node) => node.id))
  const owners = new Map<string, string[]>()
  for (const node of copied.nodes)
    for (const path of writtenPaths(node)) owners.set(path, [...(owners.get(path) ?? []), node.id])
  for (const node of copied.nodes.filter((node) => RESULT_NODE_TYPES.has(node.type)))
    for (const path of writtenPaths(node))
      if ((owners.get(path)?.length ?? 0) > 1)
        throw Error(`所选节点共同写入 ${path}，无法无歧义重映射输出，请分开复制`)
  const occupiedPaths = new Set(['state.user_input', 'state.last_error'])
  for (const node of currentNodes) {
    // 原图可能仍在编辑，不把旧节点的非法落点当成一次实际写入。
    try {
      for (const path of writtenPaths(node)) occupiedPaths.add(path)
    } catch {
      continue
    }
  }
  for (const node of copied.nodes) for (const path of writtenPaths(node)) occupiedPaths.add(path)
  const nodeIds = new Set(currentNodes.map((node) => node.id)),
    ids = new Map<string, string>()
  for (const node of copied.nodes) {
    const output = destination(node)
    const implicit =
      output && output.key === undefined && !output.extracts.length ? output.namespace : undefined
    const id = copyName(
      node.id,
      nodeIds,
      (id) => !!implicit && occupiedPaths.has(implicit + '.' + id)
    )
    ids.set(node.id, id)
    if (implicit) occupiedPaths.add(implicit + '.' + id)
  }
  const mapping = new Map<string, string>()
  for (const node of copied.nodes) {
    const output = destination(node)
    if (!output) continue
    if (output.key !== undefined || output.extracts.length) {
      const field = copyName(fieldBase(output.key ?? node.id), new Set(), (field) =>
        occupiedPaths.has(output.namespace + '.' + field)
      )
      node.resultTo = output.namespace + '.' + field
      occupiedPaths.add(node.resultTo)
      if (output.extracts.length)
        for (const key of output.extracts) {
          if (addressable(key)) mapping.set(output.namespace + '.' + key, node.resultTo + '.' + key)
        }
      else mapping.set(output.namespace + '.' + output.key, node.resultTo)
    } else if (addressable(node.id))
      mapping.set(output.namespace + '.' + node.id, output.namespace + '.' + ids.get(node.id)!)
  }
  const rewrite = referenceMapper(mapping)
  for (const node of copied.nodes) {
    const oldId = node.id
    rewriteNode(node, rewrite, ids)
    node.id = ids.get(oldId)!
    node.x += offset.x
    node.y += offset.y
  }
  const edgeIds = new Set(currentEdges.map((edge) => edge.id))
  const edges = copied.edges
    .filter((edge) => selected.has(edge.source) && selected.has(edge.target))
    .map((edge) => ({
      ...edge,
      id: copyName(edge.id, edgeIds),
      source: ids.get(edge.source)!,
      target: ids.get(edge.target)!,
      ...(edge.condition !== undefined ? { condition: condition(edge.condition, rewrite) } : {})
    }))
  return {
    nodes: [...currentNodes, ...copied.nodes],
    edges: [...currentEdges, ...edges],
    selectedIds: copied.nodes.map((node) => node.id)
  }
}
