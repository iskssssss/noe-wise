import type { BuilderEdge, BuilderNode, WorkflowMeta } from '../../utils/workflow'

export interface WorkflowVariableOption {
  path: string
  label: string
  type: string
  source: string
  sourceNodeId?: string
  parentPath?: string
  availability?: 'available' | 'conditional'
  description?: string
  customChildren?: boolean
  blockedChildren?: string[]
}
export interface WorkflowToolContract {
  inputSchema: Record<string, unknown>
  outputSchema?: Record<string, unknown>
  outputSchemaPath: 'structuredContent'
  versionId: string
  capabilityKey: string
  versionKey: string
  toolName: string
  sensitiveFields: string[]
}
export interface NodeSchema {
  type?: string | string[]
  title?: string
  description?: string
  enum?: unknown[]
  properties?: Record<string, NodeSchema>
  required?: string[]
  additionalProperties?: boolean | NodeSchema
  items?: NodeSchema
  $ref?: string
  $defs?: Record<string, NodeSchema>
  definitions?: Record<string, NodeSchema>
  allOf?: NodeSchema[]
  anyOf?: NodeSchema[]
  oneOf?: NodeSchema[]
}
export interface RawJsonEntry {
  key: string
  value: string
}

/** 只切分最外层成员；未编辑值保留原始数字词法和开放扩展字段。 */
export function jsonParts(text: string, kind: 'object' | 'array'): string[] {
  const source = text.trim() || (kind === 'object' ? '{}' : '[]')
  const value: unknown = JSON.parse(source)
  if (
    kind === 'array'
      ? !Array.isArray(value)
      : !value || typeof value !== 'object' || Array.isArray(value)
  )
    throw new Error(kind === 'object' ? '请输入 JSON 对象' : '请输入 JSON 数组')
  const parts: string[] = []
  let start = 1,
    depth = 0,
    quoted = false,
    escaped = false
  for (let index = 1; index < source.length - 1; index++) {
    const char = source[index]
    if (quoted) {
      if (escaped) escaped = false
      else if (char === '\\') escaped = true
      else if (char === '"') quoted = false
      continue
    }
    if (char === '"') quoted = true
    else if (char === '{' || char === '[') depth++
    else if (char === '}' || char === ']') depth--
    else if (char === ',' && depth === 0) {
      parts.push(source.slice(start, index).trim())
      start = index + 1
    }
  }
  const last = source.slice(start, -1).trim()
  if (last) parts.push(last)
  return parts
}
export function objectEntries(text?: string): RawJsonEntry[] {
  return jsonParts(text ?? '', 'object').map((part) => {
    let index = 1,
      escaped = false
    for (; index < part.length; index++) {
      if (escaped) escaped = false
      else if (part[index] === '\\') escaped = true
      else if (part[index] === '"') break
    }
    return {
      key: JSON.parse(part.slice(0, index + 1)) as string,
      value: part.slice(part.indexOf(':', index) + 1).trim()
    }
  })
}
export function writeObject(text: string | undefined, key: string, value?: string): string {
  const entries = objectEntries(text)
  const filtered = entries.filter((entry) => entry.key !== key)
  if (value !== undefined) {
    JSON.parse(value)
    filtered.push({ key, value })
  }
  return (
    '{' + filtered.map((entry) => JSON.stringify(entry.key) + ': ' + entry.value).join(', ') + '}'
  )
}
export function readArray(text?: string): string[] {
  return jsonParts(text ?? '', 'array')
}
export function writeArray(items: string[]): string {
  return '[' + items.join(', ') + ']'
}
export function jsonString(text?: string): string {
  try {
    const value: unknown = JSON.parse(text ?? 'null')
    return typeof value === 'string' ? value : ''
  } catch {
    return ''
  }
}
export function schemaType(schema?: NodeSchema): string {
  return Array.isArray(schema?.type)
    ? (schema.type.find((type) => type !== 'null') ?? 'unknown')
    : (schema?.type ?? (schema?.properties ? 'object' : 'unknown'))
}
export function parseDeclaration(text?: string): Record<string, unknown>[] {
  try {
    const value: unknown = JSON.parse(text || '[]')
    return Array.isArray(value)
      ? value.filter(
          (item): item is Record<string, unknown> =>
            !!item && typeof item === 'object' && !Array.isArray(item)
        )
      : []
  } catch {
    return []
  }
}
export function declaredTools(meta?: WorkflowMeta) {
  return parseDeclaration(meta?.toolsText)
    .filter((item) => typeof item.name === 'string')
    .map((item) => ({
      name: String(item.name),
      label: typeof item.description === 'string' ? item.description : String(item.name)
    }))
}
export function declaredKnowledge(meta?: WorkflowMeta) {
  return parseDeclaration(meta?.capabilityRequirementsText)
    .filter((item) => item.type === 'knowledge_source' && typeof item.slot === 'string')
    .map((item) => ({
      slot: String(item.slot),
      label: typeof item.label === 'string' ? item.label : String(item.slot)
    }))
}
/** Runtime 逐层读取 Map；点号和括号无法表示字段名本身。 */
export function isVariableSegment(key: string): boolean {
  return !!key && !/[.[\]{}$\s]/.test(key)
}
export function customVariableError(path: string, options: WorkflowVariableOption[]): string {
  const parts = path.trim().split('.')
  if (
    parts.length < 2 ||
    !['config', 'state', 'extracted', 'user', 'runtime'].includes(parts[0] ?? '') ||
    parts.some((part) => !isVariableSegment(part))
  )
    return '请填写 config、state、extracted、user 或 runtime 开头的对象点路径，不支持括号或数组下标。'
  if (options.some((option) => option.path === path)) return ''
  const ancestors = options
    .filter((option) => path.startsWith(option.path + '.'))
    .sort((a, b) => b.path.length - a.path.length)
  if (
    ancestors.some((option) =>
      option.blockedChildren?.includes(path.slice(option.path.length + 1).split('.')[0] ?? '')
    )
  )
    return '该字段已被工具输出脱敏规则排除，请选择允许返回的字段。'
  const parent = ancestors[0]
  if (!parent?.customChildren)
    return '未找到支持此路径的可用对象，请核对上游连接和字段定义；数组只能整体引用。'
  return ''
}
export function insertAtSelection(
  text: string,
  value: string,
  selection?: { start: number; end: number }
) {
  const start = Math.max(0, Math.min(text.length, selection?.start ?? text.length))
  const end = Math.max(start, Math.min(text.length, selection?.end ?? start))
  return { text: text.slice(0, start) + value + text.slice(end), caret: start + value.length }
}
export function workflowVariables(
  nodes: BuilderNode[],
  edges: BuilderEdge[],
  currentId: string,
  meta?: WorkflowMeta,
  toolContracts: Record<string, WorkflowToolContract> = {},
  options: { includeCurrent?: boolean } = {}
): WorkflowVariableOption[] {
  const variables: WorkflowVariableOption[] = []
  const add = (option: WorkflowVariableOption) => {
    const existing = variables.find((item) => item.path === option.path)
    if (!existing) variables.push(option)
    else if (existing.sourceNodeId && existing.sourceNodeId !== option.sourceNodeId) {
      existing.source = '多个上游节点'
      existing.description = '同一路径有多个上游写入者，实际值取决于最后一次写入。'
      existing.availability = 'conditional'
      if (existing.type !== option.type) existing.type = 'unknown'
      existing.customChildren ||= option.customChildren
    }
  }
  for (const field of parseDeclaration(meta?.configSchemaText))
    if (typeof field.key === 'string' && isVariableSegment(field.key))
      add({
        path: 'config.' + field.key,
        type: typeof field.type === 'string' ? field.type : 'unknown',
        label: typeof field.label === 'string' ? field.label : field.key,
        source: '工作流配置',
        availability: 'conditional',
        description: '是否有值取决于实际工作流配置。'
      })
  for (const key of ['session_id', 'execution_id', 'workflow_instance_id'])
    add({
      path: 'runtime.' + key,
      type: 'string',
      label: key,
      source: '运行上下文',
      availability: 'conditional',
      description: '本次执行提供此标识时才有值。'
    })
  add({
    path: 'runtime.action_visits',
    type: 'object',
    label: '节点访问次数',
    source: '运行上下文',
    customChildren: true
  })
  add({ path: 'state.user_input', type: 'string', label: '用户本轮输入', source: '运行上下文' })
  add({ path: 'user.end_user_id', type: 'string', label: '终端用户标识', source: '用户信息' })
  add({
    path: 'user.display_name',
    type: 'string',
    label: '用户显示名称',
    source: '用户信息',
    availability: 'conditional',
    description: '用户资料可能没有显示名称。'
  })
  const effectiveEdges = [
    ...edges,
    ...nodes.flatMap((node) =>
      node.menuActions
        .filter((action) => action.to)
        .map((action) => ({ id: node.id + ':' + action.id, source: node.id, target: action.to }))
    ),
    ...nodes
      .filter((node) => node.onError?.startsWith('goto:'))
      .map((node) => ({
        id: node.id + ':on-error',
        source: node.id,
        target: node.onError!.slice(5)
      }))
  ]
  const upstream = new Set<string>()
  const visit = (id: string) => {
    for (const edge of effectiveEdges.filter((item) => item.target === id))
      if (edge.source !== currentId && !upstream.has(edge.source)) {
        upstream.add(edge.source)
        visit(edge.source)
      }
  }
  visit(currentId)
  if (options.includeCurrent) upstream.add(currentId)
  const entry = meta?.entryNodeId || nodes.find((node) => node.type === 'start')?.id
  const roots = entry
    ? [entry]
    : nodes
        .filter((node) => !effectiveEdges.some((edge) => edge.target === node.id))
        .map((node) => node.id)
  const reaches = (from: string[], target: string, omitted?: string): boolean => {
    const pending = [...from],
      seen = new Set<string>()
    while (pending.length) {
      const id = pending.pop()!
      if (id === omitted || seen.has(id)) continue
      if (id === target) return true
      seen.add(id)
      pending.push(
        ...effectiveEdges.filter((edge) => edge.source === id).map((edge) => edge.target)
      )
    }
    return false
  }
  const schemaAt = (
    schema: NodeSchema,
    root: NodeSchema,
    visited = new Set<NodeSchema>()
  ): NodeSchema => {
    if (!schema || typeof schema !== 'object' || Array.isArray(schema)) return {}
    if (visited.has(schema)) return schema
    const seen = new Set(visited).add(schema)
    let resolved = schema
    if (schema.$ref?.startsWith('#/')) {
      let value: unknown = root
      for (const part of schema.$ref.slice(2).split('/')) {
        if (!value || typeof value !== 'object') {
          value = undefined
          break
        }
        value = (value as Record<string, unknown>)[part.replace(/~1/g, '/').replace(/~0/g, '~')]
      }
      if (value && typeof value === 'object' && value !== schema)
        resolved = { ...schemaAt(value as NodeSchema, root, seen), ...schema }
    }
    const all = (resolved.allOf ?? []).map((part) => schemaAt(part, root, seen))
    const choices = [...(resolved.anyOf ?? []), ...(resolved.oneOf ?? [])].map((part) =>
      schemaAt(part, root, seen)
    )
    if (!all.length && !choices.length) return resolved
    const properties = { ...resolved.properties }
    for (const part of [...all, ...choices])
      for (const [key, field] of Object.entries(part.properties ?? {}))
        properties[key] = properties[key] ? { anyOf: [properties[key]!, field] } : field
    const required = new Set([
      ...(resolved.required ?? []),
      ...all.flatMap((part) => part.required ?? [])
    ])
    for (const key of choices[0]?.required ?? [])
      if (choices.every((part) => part.required?.includes(key))) required.add(key)
    const types = [
      ...new Set(
        [...all, ...choices]
          .flatMap((part) => (Array.isArray(part.type) ? part.type : [schemaType(part)]))
          .filter((type) => type !== 'unknown')
      )
    ]
    return {
      ...resolved,
      properties,
      required: [...required],
      type: resolved.type ?? (types.length ? types : undefined)
    }
  }
  const addSchema = (
    path: string,
    label: string,
    schema: NodeSchema,
    base: Omit<WorkflowVariableOption, 'path' | 'label' | 'type'>,
    root = schema,
    seen = new Set<NodeSchema>()
  ) => {
    const resolved = schemaAt(schema, root),
      type = schemaType(resolved)
    add({
      ...base,
      path,
      label,
      type: Array.isArray(resolved.type) ? resolved.type.join(' | ') : type,
      customChildren:
        type === 'unknown' || (type === 'object' && resolved.additionalProperties !== false),
      description: [
        base.description,
        resolved.description,
        Array.isArray(resolved.type) && resolved.type.includes('null') ? '允许空值。' : ''
      ]
        .filter(Boolean)
        .join(' ')
    })
    if (seen.has(schema) || seen.has(resolved) || type === 'array') return
    const nextSeen = new Set(seen).add(resolved).add(schema)
    for (const [key, field] of Object.entries(resolved.properties ?? {})) {
      if (!isVariableSegment(key) || base.blockedChildren?.includes(key)) continue
      const optional = !resolved.required?.includes(key)
      addSchema(
        path + '.' + key,
        field?.title || key,
        field,
        {
          ...base,
          parentPath: path,
          blockedChildren: undefined,
          availability: optional ? 'conditional' : base.availability,
          description: optional ? '此字段不是必填项，结果中可能不包含。' : base.description
        },
        root,
        nextSeen
      )
    }
  }
  const inputSchema = meta?.capability?.input_schema as NodeSchema | undefined
  for (const [key, field] of Object.entries(inputSchema?.properties ?? {}))
    if (isVariableSegment(key))
      addSchema(
        'state.ctx_' + key,
        field?.title || key,
        field,
        {
          source: '工作流输入',
          availability: inputSchema?.required?.includes(key) ? 'available' : 'conditional'
        },
        inputSchema
      )
  let hasHuman = false
  for (const node of nodes.filter((item) => upstream.has(item.id))) {
    const conditional =
      (node.id !== currentId &&
        (!roots.length || !reaches(roots, currentId) || reaches(roots, currentId, node.id))) ||
      !!(node.onError?.startsWith('goto:') && reaches([node.onError.slice(5)], currentId))
    const base = {
      source: (node.label || node.id) + ' · ' + node.id,
      sourceNodeId: node.id,
      availability: conditional ? ('conditional' as const) : ('available' as const),
      description: conditional ? '仅在该上游分支执行成功后产生结果。' : undefined
    }
    if (node.type === 'assign') {
      for (const pair of node.assignPairs)
        if (isVariableSegment(pair.target)) {
          const raw = pair.source.trim()
          const type = /^[-+]?\d+(?:\.\d+)?(?:[eE][-+]?\d+)?$/.test(raw)
            ? 'number'
            : ['true', 'false'].includes(raw)
              ? 'boolean'
              : raw.startsWith('"')
                ? 'string'
                : 'unknown'
          add({
            ...base,
            path: 'state.' + pair.target,
            type,
            label: pair.target,
            customChildren: type === 'unknown'
          })
        }
      continue
    }
    if (
      !['llm', 'tool', 'knowledge_query', 'script', 'human_input', 'async_task'].includes(node.type)
    )
      continue
    hasHuman ||= node.type === 'human_input'
    const target = node.resultTo || 'state'
    if (!/^(?:state|extracted)(?:\.[A-Za-z][A-Za-z0-9_]*)?$/.test(target)) continue
    const root = target.startsWith('extracted') ? 'extracted' : 'state'
    let schema: NodeSchema = {}
    if (node.type === 'script' && node.scriptOutputSchemaText?.trim()) {
      try {
        const parsed: unknown = JSON.parse(node.scriptOutputSchemaText)
        if (parsed && typeof parsed === 'object' && !Array.isArray(parsed))
          schema = parsed as NodeSchema
      } catch {
        /* 未完成的 Schema 不提供猜测字段。 */
      }
    }
    if (node.type === 'knowledge_query')
      schema = {
        type: 'object',
        additionalProperties: false,
        properties: {
          bundle_id: { type: 'string' },
          knowledge_request_id: { type: 'string' },
          plan: {
            type: 'object',
            additionalProperties: false,
            properties: {
              plan_id: { type: 'string' },
              knowledge_request_id: { type: 'string' },
              steps: { type: 'array' },
              stop_condition: { type: 'string' },
              planner_version: { type: 'string' }
            },
            required: [
              'plan_id',
              'knowledge_request_id',
              'steps',
              'stop_condition',
              'planner_version'
            ]
          },
          items: { type: 'array' },
          conflict_item_ids: { type: 'array' },
          sufficiency: { type: 'string' },
          trace: { type: 'array' },
          version: { type: 'integer' },
          created_at: { type: 'string' }
        },
        required: [
          'bundle_id',
          'knowledge_request_id',
          'plan',
          'items',
          'conflict_item_ids',
          'sufficiency',
          'trace',
          'version',
          'created_at'
        ]
      }
    if (node.type === 'llm') {
      const keys = (node.extractText ?? '')
        .split(/[,，]/)
        .map((key) => key.trim())
        .filter(Boolean)
      schema = keys.length
        ? {
            type: 'object',
            properties: Object.fromEntries(keys.map((key) => [key, {}])),
            required: keys
          }
        : { type: 'string' }
    }
    let structuredSchema: NodeSchema | undefined
    if (node.type === 'tool') {
      const contract = toolContracts[node.tool ?? '']
      schema = {
        type: 'object',
        properties: {
          content: { type: 'array' },
          isError: { type: 'boolean' },
          resultType: { type: 'string' }
        },
        required: ['content']
      }
      structuredSchema = (contract?.outputSchema as NodeSchema | undefined) ?? {}
    }
    if (['human_input', 'async_task'].includes(node.type)) schema = { type: 'object' }
    if (
      target === root &&
      !['human_input', 'async_task'].includes(node.type) &&
      node.extractText?.trim()
    ) {
      for (const key of node.extractText
        .split(/[,，]/)
        .map((value) => value.trim())
        .filter(isVariableSegment))
        addSchema(
          root + '.' + key,
          key,
          key === 'structuredContent' && structuredSchema
            ? structuredSchema
            : (schema.properties?.[key] ?? {}),
          {
            ...base,
            blockedChildren:
              key === 'structuredContent'
                ? toolContracts[node.tool ?? '']?.sensitiveFields
                : undefined
          }
        )
    } else if (target !== root || isVariableSegment(node.id)) {
      const path = target === root ? root + '.' + node.id : target
      addSchema(path, node.label || node.id, schema, base)
      if (structuredSchema)
        addSchema(path + '.structuredContent', '结构化结果', structuredSchema, {
          ...base,
          parentPath: path,
          availability: 'conditional',
          blockedChildren: toolContracts[node.tool ?? '']?.sensitiveFields
        })
    }
    if (node.onError?.startsWith('goto:') && reaches([node.onError.slice(5)], currentId))
      addSchema(
        'state.last_error',
        '上游失败信息',
        {
          type: 'object',
          properties: { node_id: { type: 'string' }, reason: { type: 'string' } },
          required: ['node_id', 'reason'],
          additionalProperties: false
        },
        {
          source: '失败处理',
          availability: 'conditional',
          description: '仅在上游失败并进入错误分支后有值。'
        }
      )
  }
  if (hasHuman)
    add({
      path: 'extracted.action',
      label: '人工响应动作',
      type: 'string',
      source: '人工响应',
      availability: 'conditional',
      description: '仅在人工等待恢复时写入。'
    })
  for (const shared of variables.filter((option) => option.source === '多个上游节点'))
    for (const child of variables.filter((option) => option.path.startsWith(shared.path + '.'))) {
      child.availability = 'conditional'
      child.description = '父路径有多个上游写入者，字段可能被后续结果覆盖。'
    }
  return variables
}
