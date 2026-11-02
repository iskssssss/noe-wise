/**
 * 可视化构建器纯函数模块。
 *
 * <p>画布数据模型（BuilderNode/BuilderEdge）、workflow.yaml 序列化、
 * 后端结构化定义反序列化、WORKFLOW.md 生成与解析、校验。
 * 全部为纯函数/无副作用逻辑，可独立单元测试。
 */

import { adminZhCN, formatAdminMessage } from '../i18n/zh-CN'

const BUILDER_TEXT = adminZhCN.components.builder
const WORKFLOW_VALIDATION_TEXT = BUILDER_TEXT.validation

export interface BuilderNode {
  id: string
  type: string
  label: string
  x: number
  y: number
  resultTo: string
  prompt?: string
  extractText?: string
  tool?: string
  argsText?: string
  message?: string
  cardTitle?: string
  cardBody?: string
  assignPairs: Array<{ target: string; source: string }>
  branchesText?: string
  workflowId?: string
  script?: string
  scriptInputText?: string
  scriptOutputSchemaText?: string
  /** 原始节点用于保留构建器未编辑的扩展字段。 */
  original?: Record<string, unknown>
  /** menu 行动点列表（id/label/to 目标节点）。 */
  menuActions: Array<{ id: string; label: string; to: string }>
  /** tool 节点失败策略：fail / goto:<id> / message。 */
  onError?: string
  /** knowledge_query 引用的 knowledge_source 能力槽。 */
  knowledgeRequirement?: string
  knowledgeQuery?: string
  knowledgeMaxItems?: string
  knowledgeMaxTokens?: string
  knowledgeMaxCharacters?: string
  knowledgeTimeoutMs?: string
  knowledgeMaxSources?: string
  /** agentic 节点绑定槽与类型化任务。 */
  agentSlot?: string
  agentObjective?: string
  agentContext?: string
  agentConstraints?: string
  agentExpectedOutput?: string
  /** 卡片自定义样式（JSON 对象）。 */
  cardStyleText?: string
  /** 卡片只读字段列表（JSON 数组）。 */
  cardFieldsText?: string
  /** 卡片动作列表（JSON 数组，含 type=link 跳转）。 */
  cardActionsText?: string
  /** 挂起节点超时秒数（human_input / menu / async_task；超时以 action=timeout 恢复）。 */
  timeoutSeconds?: string
  /** 节点自定义渲染数据（JSON 对象；渲染契约 render，插值后原样透传）。 */
  renderText?: string
  /** 卡片同 id merge 目标消息 ID（human_input / card；支持 ${state.x} 插值）。 */
  mergeMessageId?: string
}

/**
 * Builder 可识别的 Workflow 节点目录。
 *
 * <p>用于已有草稿的结构解析、导入和导出。外部 HTTP 必须经 ToolVersion /
 * ToolDeployment，因此这里不能出现 {@code http} 节点。
 */
export const WORKFLOW_NODE_CATALOG = [
  { type: 'start', label: BUILDER_TEXT.nodeCatalog.start },
  { type: 'tool', label: BUILDER_TEXT.nodeCatalog.tool },
  { type: 'knowledge_query', label: BUILDER_TEXT.nodeCatalog.knowledgeQuery },
  { type: 'human_input', label: BUILDER_TEXT.nodeCatalog.humanInput },
  { type: 'menu', label: BUILDER_TEXT.nodeCatalog.menu },
  { type: 'async_task', label: BUILDER_TEXT.nodeCatalog.asyncTask },
  { type: 'llm', label: BUILDER_TEXT.nodeCatalog.llm },
  { type: 'output', label: BUILDER_TEXT.nodeCatalog.output },
  { type: 'assign', label: BUILDER_TEXT.nodeCatalog.assign },
  { type: 'parallel', label: BUILDER_TEXT.nodeCatalog.parallel },
  { type: 'subgraph', label: BUILDER_TEXT.nodeCatalog.subgraph },
  { type: 'script', label: BUILDER_TEXT.nodeCatalog.script },
  { type: 'agentic', label: BUILDER_TEXT.nodeCatalog.agentic },
  { type: 'card', label: BUILDER_TEXT.nodeCatalog.card },
  { type: 'condition', label: BUILDER_TEXT.nodeCatalog.condition },
  { type: 'end', label: BUILDER_TEXT.nodeCatalog.end }
] as const

/**
 * 当前版本可由 Builder 新增、发布并交给 Runtime 执行的节点目录。
 *
 * <p>parallel、subgraph 和 agentic 仍保留在完整结构目录中，以确保已有草稿可读、
 * 可导入和可导出；它们尚未具备生产执行链路，不能新增到画布。
 */
export const EXECUTABLE_WORKFLOW_NODE_CATALOG = WORKFLOW_NODE_CATALOG.filter(
  (node) => !['parallel', 'subgraph', 'agentic'].includes(node.type)
)

/**
 * 点击节点面板时的初始位置。每个节点占用一条网格槽位，避免圆形开始/结束节点
 * 在连续添加时相互遮挡，使用户能直接从连线手柄发起连接。
 */
export function defaultBuilderNodePosition(index: number): { x: number; y: number } {
  const safeIndex = Math.max(0, Math.floor(index))
  const columnCount = 3
  return {
    x: 80 + (safeIndex % columnCount) * 260,
    y: 80 + Math.floor(safeIndex / columnCount) * 128
  }
}

/** 只用于新节点；已读定义保留原来的结果落点。 */
export function recommendedWorkflowResultTo(type: string, nodes: BuilderNode[]): string {
  const names: Record<string, string> = {
    tool: 'tool_result',
    llm: 'model_result',
    knowledge_query: 'knowledge_result',
    human_input: 'user_response',
    async_task: 'async_result',
    script: 'script_result'
  }
  const preferred = names[type]
  if (!preferred) return 'state'
  const used = new Set(
    nodes.flatMap((node) => [
      node.resultTo === 'state' ? node.id : node.resultTo.replace(/^state\./, ''),
      ...node.assignPairs.map((pair) => pair.target)
    ])
  )
  let key = preferred,
    index = 2
  while (used.has(key)) key = preferred + '_' + index++
  return 'state.' + key
}

export type WorkflowNodePlacement =
  | { kind: 'after'; nodeId: string }
  | { kind: 'edge'; edgeId: string }
  | { kind: 'action'; nodeId: string; actionId: string }

/** 添加节点与改写关联在同一个图变更中完成，条件只在原分支入口求值一次。 */
export function connectWorkflowNode(
  nodes: BuilderNode[],
  edges: BuilderEdge[],
  node: BuilderNode,
  placement: WorkflowNodePlacement
): { nodes: BuilderNode[]; edges: BuilderEdge[] } {
  if (nodes.some((item) => item.id === node.id)) throw Error('新节点标识已存在')
  if (['start', 'end'].includes(node.type) && placement.kind !== 'after')
    throw Error('开始或结束节点不能插入已有连接')
  let nextNodes = [...nodes, node],
    nextEdges = [...edges]
  const edgeId = () => crypto.randomUUID()
  if (placement.kind === 'edge') {
    const edge = edges.find((item) => item.id === placement.edgeId)
    if (!edge) throw Error('原连接已经变化，请重新选择插入位置')
    node.x = (nodes.find((item) => item.id === edge.source)?.x ?? 0) + 260
    node.y = (nodes.find((item) => item.id === edge.source)?.y ?? 0) + 40
    nextEdges = edges.map((item) => (item.id === edge.id ? { ...item, target: node.id } : item))
    nextEdges.push({
      id: edgeId(),
      source: node.id,
      target: edge.target,
      condition: '',
      priority: 0
    })
  } else {
    const source = nodes.find((item) => item.id === placement.nodeId)
    if (!source || source.type === 'end') throw Error('所选节点不能添加后续节点')
    node.x = source.x + 260
    node.y = source.y + 40
    if (placement.kind === 'action') {
      const action = source.menuActions.find((item) => item.id === placement.actionId)
      if (source.type !== 'menu' || !action) throw Error('菜单选项已经变化，请重新选择')
      nextNodes = nextNodes.map((item) =>
        item.id === source.id
          ? {
              ...item,
              menuActions: item.menuActions.map((item) =>
                item.id === action.id ? { ...item, to: node.id } : item
              )
            }
          : item
      )
      if (action.to)
        nextEdges.push({
          id: edgeId(),
          source: node.id,
          target: action.to,
          condition: '',
          priority: 0
        })
    } else
      nextEdges.push({
        id: edgeId(),
        source: source.id,
        target: node.id,
        condition: '',
        priority: 0
      })
  }
  while (nodes.some((item) => Math.abs(item.x - node.x) < 240 && Math.abs(item.y - node.y) < 116))
    node.y += 128
  return { nodes: nextNodes, edges: nextEdges }
}

const WORKFLOW_NODE_TYPES = new Set<string>(WORKFLOW_NODE_CATALOG.map((node) => node.type))
const EXECUTABLE_WORKFLOW_NODE_TYPES = new Set<string>(
  EXECUTABLE_WORKFLOW_NODE_CATALOG.map((node) => node.type)
)

/** 返回节点类型是否可由 Builder 解析、导入和导出。 */
export function isSupportedWorkflowNodeType(type: string): boolean {
  return WORKFLOW_NODE_TYPES.has(type)
}

/** 返回节点类型是否可在当前版本新增并进入 Runtime 执行。 */
export function isExecutableWorkflowNodeType(type: string): boolean {
  return EXECUTABLE_WORKFLOW_NODE_TYPES.has(type)
}

export interface BuilderEdge {
  id: string
  source: string
  target: string
  condition?: string
  priority?: number
  original?: Record<string, unknown>
}

export interface WorkflowScriptResource {
  path: string
  content_base64: string
  sha256: string
}

export function workflowScriptResources(value: unknown): WorkflowScriptResource[] {
  if (value === undefined) return []
  if (!Array.isArray(value)) throw Error('resources 必须为脚本资源数组')
  const paths = new Set<string>()
  return value.map((item) => {
    if (!item || typeof item !== 'object' || Array.isArray(item)) throw Error('脚本资源必须为对象')
    const resource = item as WorkflowScriptResource
    if (
      Object.keys(item).some((key) => !['path', 'content_base64', 'sha256'].includes(key)) ||
      typeof resource.path !== 'string' ||
      typeof resource.content_base64 !== 'string' ||
      typeof resource.sha256 !== 'string'
    )
      throw Error('脚本资源只允许 path、content_base64 和 sha256 字符串')
    const parts = resource.path.split('/')
    if (
      resource.path.length > 240 ||
      !resource.path.startsWith('scripts/') ||
      !/\.(js|py)$/.test(resource.path) ||
      /[\\\0]/.test(resource.path) ||
      parts.some((p) => !p || p === '.' || p === '..')
    )
      throw Error(`脚本资源路径不合法：${resource.path}`)
    if (paths.has(resource.path)) throw Error(`脚本资源路径重复：${resource.path}`)
    paths.add(resource.path)
    if (!/^[0-9a-f]{64}$/.test(resource.sha256))
      throw Error(`脚本摘要必须为 SHA-256 小写十六进制：${resource.path}`)
    try {
      if (btoa(atob(resource.content_base64)) !== resource.content_base64) throw Error()
    } catch {
      throw Error(`脚本资源必须使用规范 Base64：${resource.path}`)
    }
    return JSON.parse(JSON.stringify(resource)) as WorkflowScriptResource
  })
}

export interface WorkflowMeta {
  name: string
  version: string
  displayName: string
  description: string
  category: string
  tagsText: string
  configSchemaText: string
  /** 作者（市场展示；默认 tenant）。 */
  author?: string
  /** 工具声明（JSON 数组文本：{name, description, requirement}）。 */
  toolsText?: string
  /** 能力槽声明（JSON 数组文本：{slot,type,capability_id,version_constraint,label,required?}）。 */
  capabilityRequirementsText?: string
  /** 必需权限（JSON 数组文本或逗号分隔字符串）。 */
  requiredPermissionsText?: string
  /** 原定义的能力治理对象；图形编辑完整保留，缺失或 null 不补默认值。 */
  capability?: Record<string, unknown> | null
  resources?: WorkflowScriptResource[]
  entryNodeId?: string
  originalDefinition?: Record<string, unknown>
  originalMeta?: Record<string, unknown>
}

const yamlString = (value: string) => JSON.stringify(value)

/**
 * 画布 → workflow.yaml（对齐 WorkflowParser 契约：entry/nodes/edges + condition/priority，
 * 含 menu/agentic 节点、卡片扩展；meta 段承载工作流元数据。
 * 画布 layout/viewport 属于 BuilderSnapshot，不能导出到运行定义。
 *
 * @param nodes 画布节点
 * @param edges 画布连线
 * @param meta  工作流元数据（原 frontmatter 字段），可空
 */
export function workflowYaml(
  nodes: BuilderNode[],
  edges: BuilderEdge[],
  meta?: WorkflowMeta
): string {
  const unsupported = nodes.find((node) => !isSupportedWorkflowNodeType(node.type))
  if (unsupported) {
    throw new Error(
      formatAdminMessage(WORKFLOW_VALIDATION_TEXT.unsupportedNodeType, {
        type: unsupported.type,
        id: unsupported.id
      })
    )
  }
  // 入口：存在 type=start 节点时指向它（对齐后端 resolveEntry），否则取第一个节点
  const entry = nodes.find((n) => n.type === 'start')?.id ?? nodes[0]?.id ?? 'start'
  const lines: string[] = ["version: '1.0'", `entry: ${entry}`]
  lines.push('nodes:')
  for (const n of nodes) {
    lines.push(`  - id: ${n.id}`, `    type: ${n.type}`, `    name: ${yamlString(n.label)}`)
    if (n.prompt) lines.push(`    prompt: ${yamlString(n.prompt)}`)
    if (n.extractText?.trim()) {
      const keys = n.extractText
        .split(/[,，]/)
        .map((s) => s.trim())
        .filter(Boolean)
      // 元素统一 yamlString 转义：键含 #/: 等 YAML 特殊字符时防注释截断/解析错误
      lines.push(`    extract: [${keys.map((k) => yamlString(k)).join(', ')}]`)
    }
    if (n.tool) lines.push(`    tool: ${yamlString(n.tool)}`)
    if (n.onError) lines.push(`    on_error: ${yamlString(n.onError)}`)
    if (
      n.type !== 'script' &&
      n.type !== 'agentic' &&
      n.type !== 'knowledge_query' &&
      n.argsText?.trim()
    ) {
      lines.push(`    args: ${n.argsText.trim()}`)
    }
    if (n.resultTo !== 'state' || ['knowledge_query', 'agentic', 'parallel'].includes(n.type)) {
      lines.push(`    result_to: ${yamlString(n.resultTo)}`)
    }
    if (n.message) lines.push(`    message: ${yamlString(n.message)}`)
    // 渲染契约：节点自定义渲染数据（任意键值，插值后原样透传）
    if (n.renderText?.trim()) lines.push(`    render: ${n.renderText.trim()}`)
    // 挂起超时（human_input / menu / async_task）：超时未恢复由平台扫描以 action=timeout 恢复
    if (n.timeoutSeconds?.trim()) lines.push(`    timeout_seconds: ${n.timeoutSeconds.trim()}`)
    // 卡片同 id merge（human_input / card）：进度卡/状态卡原位更新（message.card.update）
    if ((n.type === 'human_input' || n.type === 'card') && n.mergeMessageId?.trim()) {
      lines.push(`    merge_message_id: ${yamlString(n.mergeMessageId.trim())}`)
    }
    // card 配置仅 human_input（HITL 交互挂起卡）与 card 节点（展示卡）合法；
    // output / menu 不允许 card
    if (
      (n.type === 'human_input' || n.type === 'card') &&
      (n.cardTitle ||
        n.cardBody ||
        n.cardFieldsText?.trim() ||
        n.cardActionsText?.trim() ||
        n.cardStyleText?.trim())
    ) {
      lines.push(
        '    card:',
        '      card_id: hitl_confirm',
        '      type: confirm',
        `      title: ${yamlString(n.cardTitle ?? '')}`,
        `      body: ${yamlString(n.cardBody ?? '')}`
      )
      if (n.cardFieldsText?.trim()) lines.push(`      fields: ${n.cardFieldsText.trim()}`)
      if (n.cardActionsText?.trim()) lines.push(`      actions: ${n.cardActionsText.trim()}`)
      if (n.cardStyleText?.trim()) lines.push(`      style: ${n.cardStyleText.trim()}`)
    }
    if (n.type === 'assign') {
      const pairs = n.assignPairs.filter((p) => p.target && p.source)
      if (pairs.length) {
        lines.push('    args:')
        pairs.forEach((p) => lines.push(`      ${p.target}: ${yamlString(p.source)}`))
      }
    }
    if (n.branchesText?.trim()) {
      const branches = n.branchesText
        .split(/\n/)
        .map((s) => s.trim())
        .filter(Boolean)
      // 分支入口为节点 id，仍统一 yamlString 转义防特殊字符解析错位
      lines.push(`    branches: [${branches.map((b) => yamlString(b)).join(', ')}]`)
    }
    if (n.workflowId) lines.push(`    workflow: ${yamlString(n.workflowId)}`)
    if (n.type === 'script') {
      lines.push(`    args: ${JSON.stringify(definitionNode(n).args)}`)
    }
    if (n.type === 'knowledge_query') {
      lines.push(
        `    requirement: ${yamlString(n.knowledgeRequirement?.trim() ?? '')}`,
        `    query: ${yamlString(n.knowledgeQuery?.trim() ?? '')}`,
        `    budget: ${JSON.stringify(knowledgeBudget(n))}`
      )
    }
    if (n.type === 'agentic') {
      lines.push(
        `    agent_slot: ${yamlString(n.agentSlot?.trim() ?? '')}`,
        `    task: ${JSON.stringify(agentTask(n))}`
      )
    }
    if (n.type === 'menu') {
      // 保留已填写的行动点（仅剔除全空行），避免部分配置被静默丢弃；
      // 完整性由 validateWorkflow 警告提示
      const actions = n.menuActions.filter((a) => a.id?.trim() || a.label?.trim() || a.to?.trim())
      if (actions.length) {
        lines.push('    actions:')
        actions.forEach((a) =>
          lines.push(
            `      - id: ${yamlString(a.id ?? '')}`,
            `        label: ${yamlString(a.label ?? '')}`,
            `        to: ${yamlString(a.to ?? '')}`
          )
        )
      }
    }
  }
  lines.push('edges:')
  for (const e of edges) {
    // priority 归一化：输入框清空后 v-model.number 得 ""，统一转数字（空串/NaN → 0）
    const priority = Number(e.priority) || 0
    if (e.condition?.trim()) {
      lines.push(
        `  - from: ${e.source}`,
        `    to: ${e.target}`,
        `    condition: ${yamlString(e.condition.trim())}`,
        `    priority: ${priority}`
      )
    } else {
      lines.push(`  - from: ${e.source}`, `    to: ${e.target}`)
      // 无条件边也保留 priority（非 0 时输出）：用户设置的优先级在保存/往返中不丢失（D15）
      if (priority !== 0) {
        lines.push(`    priority: ${priority}`)
      }
    }
  }
  if (meta) {
    lines.push('', ...metaYamlSection(meta))
  }
  return lines.join('\n') + '\n'
}

/**
 * 画布 → 版本化 Builder API 的结构化 Workflow Definition。
 *
 * <p>与 {@link workflowYaml} 使用同一节点契约，但不经 ZIP/YAML 上传路径；草稿、
 * 校验和发布都以该对象为输入。JSON 编辑字段在此严格解析，避免把无效文本静默写入草稿。
 */
export function workflowDefinition(
  nodes: BuilderNode[],
  edges: BuilderEdge[],
  meta?: WorkflowMeta
): Record<string, unknown> {
  const unsupported = nodes.find((node) => !isSupportedWorkflowNodeType(node.type))
  if (unsupported) {
    throw new Error(
      formatAdminMessage(WORKFLOW_VALIDATION_TEXT.unsupportedNodeType, {
        type: unsupported.type,
        id: unsupported.id
      })
    )
  }
  const definition: Record<string, unknown> = {
    ...meta?.originalDefinition,
    version: '1.0',
    entry:
      meta?.entryNodeId ||
      nodes.find((node) => node.type === 'start')?.id ||
      nodes[0]?.id ||
      'start',
    nodes: nodes.map((node) => definitionNode(node)),
    edges: edges.map((edge) => {
      const result: Record<string, unknown> = {
        ...edge.original,
        from: edge.source,
        to: edge.target
      }
      delete result.condition
      delete result.priority
      if (edge.condition?.trim()) result.condition = edge.condition.trim()
      const priority = Number(edge.priority) || 0
      if (priority !== 0) result.priority = priority
      return result
    })
  }
  if (meta) definition.meta = definitionMeta(meta)
  if (meta?.resources !== undefined) definition.resources = meta.resources
  return definition
}

function definitionNode(node: BuilderNode): Record<string, unknown> {
  const result: Record<string, unknown> = {
    ...node.original,
    id: node.id,
    type: node.type,
    name: node.label
  }
  for (const field of [
    'layout',
    'prompt',
    'extract',
    'tool',
    'on_error',
    'result_to',
    'message',
    'render',
    'timeout_seconds',
    'merge_message_id',
    'args',
    'branches',
    'workflow',
    'requirement',
    'query',
    'budget',
    'agent_slot',
    'task',
    'actions',
    'card'
  ])
    delete result[field]
  if (node.prompt) result.prompt = node.prompt
  if (node.extractText?.trim()) result.extract = splitKeys(node.extractText)
  if (node.tool) result.tool = node.tool
  if (node.onError) result.on_error = node.onError
  if (node.resultTo !== 'state' || ['knowledge_query', 'agentic', 'parallel'].includes(node.type)) {
    result.result_to = node.resultTo
  }
  if (node.message) result.message = node.message
  if (node.renderText?.trim()) result.render = requiredJson(node.renderText, `${node.id}.render`)
  if (node.timeoutSeconds?.trim())
    result.timeout_seconds = requiredNumber(node.timeoutSeconds, `${node.id}.timeout_seconds`)
  if ((node.type === 'human_input' || node.type === 'card') && node.mergeMessageId?.trim()) {
    result.merge_message_id = node.mergeMessageId.trim()
  }
  if (
    node.type !== 'script' &&
    node.type !== 'agentic' &&
    node.type !== 'knowledge_query' &&
    node.type !== 'assign' &&
    node.argsText?.trim()
  ) {
    result.args = requiredJson(node.argsText, `${node.id}.args`)
  }
  if (node.type === 'assign') {
    const entries = node.assignPairs.filter((pair) => pair.target && pair.source)
    if (entries.length)
      result.args = Object.fromEntries(
        entries.map((pair) => {
          const original = (node.original?.args as Record<string, unknown> | undefined)?.[
            pair.target
          ]
          return [
            pair.target,
            original !== undefined && String(original) === pair.source ? original : pair.source
          ]
        })
      )
  }
  if (
    ['parallel', 'knowledge_query', 'agentic'].includes(node.type) &&
    node.original?.args !== undefined
  )
    result.args = node.original.args
  if (node.branchesText?.trim()) result.branches = splitKeys(node.branchesText)
  if (node.workflowId) result.workflow = node.workflowId
  if (node.type === 'script') {
    const originalArgs = { ...(node.original?.args as Record<string, unknown> | undefined) }
    delete originalArgs.args
    delete originalArgs.input
    delete originalArgs.output_schema
    result.args = {
      ...originalArgs,
      script: node.script ?? '',
      ...(node.scriptInputText?.trim()
        ? { input: requiredJson(node.scriptInputText, `${node.id}.args.input`) }
        : {}),
      ...(node.scriptOutputSchemaText?.trim()
        ? {
            output_schema: requiredJson(
              node.scriptOutputSchemaText,
              `${node.id}.args.output_schema`
            )
          }
        : {})
    }
  }
  if (node.type === 'knowledge_query') {
    result.requirement = node.knowledgeRequirement?.trim() ?? ''
    result.query = node.knowledgeQuery?.trim() ?? ''
    result.budget = knowledgeBudget(node)
  }
  if (node.type === 'agentic') {
    result.agent_slot = node.agentSlot?.trim() ?? ''
    result.task = agentTask(node)
  }
  if (node.type === 'menu') {
    const actions = node.menuActions.filter(
      (action) => action.id?.trim() || action.label?.trim() || action.to?.trim()
    )
    if (actions.length)
      result.actions = actions.map((action) => ({
        ...(node.original?.actions as Array<Record<string, unknown>> | undefined)?.find(
          (item) => item.id === action.id
        ),
        id: action.id ?? '',
        label: action.label ?? '',
        to: action.to ?? ''
      }))
  }
  if (
    (node.type === 'human_input' || node.type === 'card') &&
    (node.original?.card ||
      node.cardTitle ||
      node.cardBody ||
      node.cardFieldsText?.trim() ||
      node.cardActionsText?.trim() ||
      node.cardStyleText?.trim())
  ) {
    const card: Record<string, unknown> = {
      ...(node.original?.card as Record<string, unknown> | undefined),
      card_id:
        (node.original?.card as Record<string, unknown> | undefined)?.card_id ?? 'hitl_confirm',
      type: (node.original?.card as Record<string, unknown> | undefined)?.type ?? 'confirm',
      title: node.cardTitle ?? '',
      body: node.cardBody ?? ''
    }
    delete card.fields
    delete card.actions
    delete card.style
    if (node.cardFieldsText?.trim())
      card.fields = requiredJson(node.cardFieldsText, `${node.id}.card.fields`)
    if (node.cardActionsText?.trim())
      card.actions = requiredJson(node.cardActionsText, `${node.id}.card.actions`)
    if (node.cardStyleText?.trim())
      card.style = requiredJson(node.cardStyleText, `${node.id}.card.style`)
    result.card = card
  }
  return result
}

function knowledgeBudget(node: BuilderNode): Record<string, number> {
  return {
    max_items: requiredPositiveInteger(node.knowledgeMaxItems ?? '', `${node.id}.budget.max_items`),
    max_tokens: requiredPositiveInteger(
      node.knowledgeMaxTokens ?? '',
      `${node.id}.budget.max_tokens`
    ),
    max_characters: requiredPositiveInteger(
      node.knowledgeMaxCharacters ?? '',
      `${node.id}.budget.max_characters`
    ),
    timeout_ms: requiredPositiveInteger(
      node.knowledgeTimeoutMs ?? '',
      `${node.id}.budget.timeout_ms`
    ),
    max_sources: requiredPositiveInteger(
      node.knowledgeMaxSources ?? '',
      `${node.id}.budget.max_sources`
    )
  }
}

function agentTask(node: BuilderNode): Record<string, string> {
  const task: Record<string, string> = {
    objective: node.agentObjective?.trim() ?? '',
    expected_output: node.agentExpectedOutput?.trim() ?? ''
  }
  if (node.agentContext?.trim()) task.context = node.agentContext.trim()
  if (node.agentConstraints?.trim()) task.constraints = node.agentConstraints.trim()
  return task
}

function definitionMeta(meta: WorkflowMeta): Record<string, unknown> {
  const result: Record<string, unknown> = { ...meta.originalMeta, type: 'state-machine' }
  for (const field of [
    'name',
    'version',
    'display_name',
    'description',
    'category',
    'tags',
    'config_schema',
    'author',
    'capability',
    'tools',
    'capability_requirements',
    'required_permissions'
  ])
    delete result[field]
  if (meta.name.trim()) result.name = meta.name.trim()
  if (meta.version.trim()) result.version = meta.version.trim()
  if (meta.displayName.trim()) result.display_name = meta.displayName.trim()
  if (meta.description.trim()) result.description = meta.description.trim()
  if (meta.category.trim()) result.category = meta.category.trim()
  const tags = meta.tagsText
    .split(/[,，]/)
    .map((tag) => tag.trim())
    .filter(Boolean)
  if (tags.length) result.tags = tags
  if (meta.configSchemaText.trim())
    result.config_schema = requiredJson(meta.configSchemaText, 'meta.config_schema')
  if (meta.author?.trim()) result.author = meta.author.trim()
  if (meta.capability !== undefined) {
    result.capability = JSON.parse(JSON.stringify(meta.capability))
  }
  const requirements = meta.capabilityRequirementsText?.trim()
    ? capabilityRequirements(meta.capabilityRequirementsText)
    : []
  if (meta.toolsText?.trim()) result.tools = toolDeclarations(meta.toolsText, requirements)
  if (meta.capabilityRequirementsText?.trim()) {
    result.capability_requirements = requirements
  }
  if (meta.requiredPermissionsText?.trim()) {
    const permissions = parseJsonField<unknown>(meta.requiredPermissionsText)
    result.required_permissions = Array.isArray(permissions)
      ? permissions
      : meta.requiredPermissionsText
          .split(/[,，]/)
          .map((value) => value.trim())
          .filter(Boolean)
  }
  return result
}

function requiredJson(text: string, field: string): unknown {
  const value = parseJsonField(text)
  if (value === null)
    throw new Error(formatAdminMessage(WORKFLOW_VALIDATION_TEXT.invalidJson, { field }))
  return value
}

const CAPABILITY_REQUIREMENT_FIELDS = new Set([
  'slot',
  'type',
  'capability_id',
  'version_constraint',
  'label',
  'required',
  'description'
])

/** 解析发布定义的能力槽；安装阶段只能为这些槽选择精确资源。 */
function capabilityRequirements(text: string): Array<Record<string, unknown>> {
  const value = requiredJson(text, 'meta.capability_requirements')
  if (!Array.isArray(value)) throw new Error('能力槽声明格式不正确，请填写 JSON 数组')
  const slots = new Set<string>()
  return value.map((item, index) => {
    if (typeof item !== 'object' || item === null || Array.isArray(item)) {
      throw new Error(`第 ${index + 1} 个能力槽必须为 JSON 对象`)
    }
    const requirement = item as Record<string, unknown>
    if (Object.keys(requirement).some((key) => !CAPABILITY_REQUIREMENT_FIELDS.has(key))) {
      throw new Error('能力槽声明包含不支持的字段，请对照高级 JSON 的能力槽字段检查')
    }
    const requiredText = ['slot', 'type', 'capability_id', 'version_constraint', 'label']
    if (
      requiredText.some(
        (field) => typeof requirement[field] !== 'string' || !(requirement[field] as string).trim()
      )
    ) {
      throw new Error(
        '请完整填写能力槽标识、类型、能力标识、版本约束和名称；高级 JSON 中对应 slot、type、capability_id、version_constraint、label'
      )
    }
    const slot = String(requirement.slot)
    if (!/^[a-z][a-z0-9_]{0,63}$/.test(slot))
      throw new Error(`能力槽标识 ${slot} 无效：须为小写字母开头的 1–64 位小写字母、数字或下划线`)
    if (slots.has(slot)) throw new Error(`能力槽 ${slot} 重复，请为每个槽填写不同标识`)
    slots.add(slot)
    if (!new Set(['tool', 'knowledge_source']).has(String(requirement.type)))
      throw new Error(`能力类型 ${requirement.type} 不支持，请选择工具或知识来源`)
    const versionConstraint = String(requirement.version_constraint)
    if (
      versionConstraint !== '*' &&
      !/^[A-Za-z0-9:_-]+(?:\.[A-Za-z0-9_-]+)*(?:\.[xX*])?$/.test(versionConstraint)
    ) {
      throw new Error(`能力槽 ${slot} 的版本约束无效，请填写精确版本、* 或 1.x 形式`)
    }
    if (
      requirement.required !== null &&
      requirement.required !== undefined &&
      typeof requirement.required !== 'boolean'
    ) {
      throw new Error('能力槽的“必须绑定”仅支持是或否；高级 JSON 中 required 应为 true 或 false')
    }
    if (
      requirement.description !== null &&
      requirement.description !== undefined &&
      typeof requirement.description !== 'string'
    ) {
      throw new Error('能力槽说明必须为文本；请检查高级 JSON 中的 description')
    }
    return requirement
  })
}

function toolDeclarations(
  text: string,
  requirements: Array<Record<string, unknown>>
): Array<Record<string, unknown>> {
  const value = requiredJson(text, 'meta.tools')
  if (!Array.isArray(value)) throw new Error('工具声明格式不正确，请填写 JSON 数组')
  const toolSlots = new Set(
    requirements
      .filter((requirement) => requirement.type === 'tool')
      .map((requirement) => String(requirement.slot))
  )
  const names = new Set<string>()
  return value.map((item, index) => {
    if (typeof item !== 'object' || item === null || Array.isArray(item))
      throw new Error(`第 ${index + 1} 个工具声明必须为 JSON 对象`)
    const tool = item as Record<string, unknown>
    if (
      typeof tool.name !== 'string' ||
      !tool.name.trim() ||
      typeof tool.requirement !== 'string' ||
      !tool.requirement.trim()
    ) {
      throw new Error(
        '请为工具填写节点工具名并选择对应工具槽；高级 JSON 中 name 和 requirement 不能为空'
      )
    }
    if (names.has(tool.name)) throw new Error(`工具名 ${tool.name} 重复，请使用不同的节点工具名`)
    names.add(tool.name)
    if (!toolSlots.has(tool.requirement))
      throw new Error(`工具 ${tool.name} 尚未关联工具槽，请选择已声明的工具类型槽`)
    return tool
  })
}

function requiredNumber(text: string, field: string): number {
  const value = Number(text)
  if (!Number.isFinite(value) || value <= 0) {
    throw new Error(formatAdminMessage(WORKFLOW_VALIDATION_TEXT.positiveNumber, { field }))
  }
  return value
}

function requiredPositiveInteger(text: string, field: string): number {
  const value = Number(text)
  if (!Number.isSafeInteger(value) || value <= 0) throw new Error(`${field} 必须为正整数`)
  return value
}

/**
 * 生成 workflow.yaml 的 meta 段文本（原 WORKFLOW.md frontmatter + body 正文）。
 *
 * <p>与 workflowYaml 内 meta 段同一实现（详情页预览复用）。body 以块标量
 * `|` 输出（内容行缩进 4 空格，空行保持），空 body 省略。
 *
 * @param meta 工作流元数据
 * @returns meta 段 YAML 文本（含 `meta:` 起始行）
 */
export function workflowMetaYaml(meta: WorkflowMeta): string {
  return metaYamlSection(meta).join('\n') + '\n'
}

/** 组装 meta 段行（meta: 起始行 + 2 空格缩进的字段行）。 */
function metaYamlSection(meta: WorkflowMeta): string[] {
  const tags = meta.tagsText
    .split(/[,，]/)
    .map((t) => t.trim())
    .filter(Boolean)
  const lines: string[] = ['meta:']
  if (meta.name.trim()) lines.push(`  name: ${yamlString(meta.name.trim())}`)
  lines.push('  type: state-machine')
  if (meta.version.trim()) lines.push(`  version: ${yamlString(meta.version.trim())}`)
  if (meta.displayName.trim()) lines.push(`  display_name: ${yamlString(meta.displayName.trim())}`)
  if (meta.description.trim()) lines.push(`  description: ${yamlString(meta.description.trim())}`)
  if (meta.category.trim()) lines.push(`  category: ${yamlString(meta.category.trim())}`)
  // flow 形式数组（SnakeYAML 兼容；与手写包 tags: [a, b] 同语义）
  if (tags.length) lines.push(`  tags: [${tags.map((t) => yamlString(t)).join(', ')}]`)
  const schema = meta.configSchemaText.trim()
  if (schema) lines.push(`  config_schema: ${schema}`)
  // 平台扩展声明（构建器可选编辑；存在即输出，保持上传/编辑往返不丢字段）
  if (meta.author?.trim()) lines.push(`  author: ${yamlString(meta.author.trim())}`)
  if (meta.capability !== undefined) {
    lines.push(`  capability: ${JSON.stringify(meta.capability)}`)
  }
  const requirements = meta.capabilityRequirementsText?.trim()
    ? capabilityRequirements(meta.capabilityRequirementsText)
    : []
  if (meta.toolsText?.trim())
    lines.push(`  tools: ${JSON.stringify(toolDeclarations(meta.toolsText, requirements))}`)
  if (meta.capabilityRequirementsText?.trim()) {
    lines.push(`  capability_requirements: ${JSON.stringify(requirements)}`)
  }
  if (meta.requiredPermissionsText?.trim()) {
    lines.push(`  required_permissions: ${meta.requiredPermissionsText.trim()}`)
  }
  return lines
}

/** 后端结构化定义（GET /workflows/{id}/workflow）→ 画布数据模型。 */
export function parseWorkflowJson(definition: {
  entry?: string
  nodes: Array<Record<string, unknown>>
  edges: Array<Record<string, unknown>>
  progress?: Record<string, unknown>
  state_schema?: Array<Record<string, unknown>>
}): {
  nodes: BuilderNode[]
  edges: BuilderEdge[]
} {
  if (
    !definition ||
    typeof definition !== 'object' ||
    !Array.isArray(definition.nodes) ||
    !Array.isArray(definition.edges)
  )
    throw new Error('工作流定义必须包含 nodes 和 edges 数组')
  if (
    definition.nodes.some(
      (node) =>
        !node ||
        typeof node !== 'object' ||
        Array.isArray(node) ||
        typeof node.id !== 'string' ||
        typeof node.type !== 'string'
    )
  )
    throw new Error('每个节点必须包含字符串 id 和 type')
  if (
    definition.edges.some(
      (edge) =>
        !edge ||
        typeof edge !== 'object' ||
        Array.isArray(edge) ||
        typeof edge.from !== 'string' ||
        typeof edge.to !== 'string'
    )
  )
    throw new Error('每条连接必须包含字符串 from 和 to')
  const nodes: BuilderNode[] = definition.nodes.map((raw, index) => {
    const id = String(raw.id ?? `node_${index}`)
    // 画布位置恢复：layout 子对象（{x, y}）→ 节点坐标；缺失走自动布局
    const layout = raw.layout as Record<string, unknown> | undefined
    const node: BuilderNode = {
      id,
      type: String(raw.type ?? 'llm'),
      label: String(raw.name ?? raw.id ?? id),
      x: typeof layout?.x === 'number' ? layout.x : 0,
      y: typeof layout?.y === 'number' ? layout.y : 0,
      resultTo: String(raw.result_to ?? 'state'),
      assignPairs: [],
      menuActions: [],
      original: JSON.parse(JSON.stringify(raw))
    }
    const args = raw.args as Record<string, unknown> | undefined
    if (raw.prompt) node.prompt = String(raw.prompt)
    if (Array.isArray(raw.extract)) node.extractText = (raw.extract as string[]).join(',')
    if (raw.tool) node.tool = String(raw.tool)
    if (raw.on_error) node.onError = String(raw.on_error)
    if (raw.message) node.message = String(raw.message)
    if (raw.workflow) node.workflowId = String(raw.workflow)
    // 脚本只接受冻结资源路径、对象输入与对象输出 Schema。
    if (node.type === 'script') {
      if (args && typeof args.script === 'string') node.script = args.script
      if (args && args.input !== undefined)
        node.scriptInputText = JSON.stringify(args.input, null, 2)
      if (args && args.output_schema !== undefined)
        node.scriptOutputSchemaText = JSON.stringify(args.output_schema, null, 2)
    } else if (node.type === 'knowledge_query') {
      node.knowledgeRequirement = String(raw.requirement ?? '')
      node.knowledgeQuery = String(raw.query ?? '')
      const budget = raw.budget as Record<string, unknown> | undefined
      node.knowledgeMaxItems = String(budget?.max_items ?? '')
      node.knowledgeMaxTokens = String(budget?.max_tokens ?? '')
      node.knowledgeMaxCharacters = String(budget?.max_characters ?? '')
      node.knowledgeTimeoutMs = String(budget?.timeout_ms ?? '')
      node.knowledgeMaxSources = String(budget?.max_sources ?? '')
    } else if (node.type === 'agentic') {
      node.agentSlot = String(raw.agent_slot ?? '')
      const task = raw.task as Record<string, unknown> | undefined
      node.agentObjective = String(task?.objective ?? '')
      node.agentContext = String(task?.context ?? '')
      node.agentConstraints = String(task?.constraints ?? '')
      node.agentExpectedOutput = String(task?.expected_output ?? '')
    } else if (node.type === 'assign' && args) {
      node.assignPairs = Object.entries(args).map(([target, source]) => ({
        target,
        source: String(source)
      }))
    } else if (args && node.type !== 'parallel') {
      node.argsText = JSON.stringify(args, null, 2)
    }
    if (Array.isArray(raw.branches)) node.branchesText = (raw.branches as string[]).join('\n')
    if (Array.isArray(raw.actions)) {
      node.menuActions = (raw.actions as Array<Record<string, unknown>>).map((a) => ({
        id: String(a.id ?? ''),
        label: String(a.label ?? ''),
        to: String(a.to ?? '')
      }))
    }
    const card = raw.card as Record<string, unknown> | undefined
    if (card) {
      if (card.title) node.cardTitle = String(card.title)
      if (card.body) node.cardBody = String(card.body)
      if (Array.isArray(card.fields)) node.cardFieldsText = JSON.stringify(card.fields)
      if (Array.isArray(card.actions)) node.cardActionsText = JSON.stringify(card.actions)
      if (card.style) node.cardStyleText = JSON.stringify(card.style)
    }
    // 渲染契约 / 挂起超时 / 卡片 merge（阶段 2/3 新增字段）
    if (raw.timeout_seconds !== null && raw.timeout_seconds !== undefined)
      node.timeoutSeconds = String(raw.timeout_seconds)
    if (raw.render) node.renderText = JSON.stringify(raw.render, null, 2)
    if (raw.merge_message_id) node.mergeMessageId = String(raw.merge_message_id)
    return node
  })
  const edges: BuilderEdge[] = definition.edges.map((raw, index) => ({
    original: JSON.parse(JSON.stringify(raw)),
    id: `edge_${index}`,
    source: String(raw.from),
    target: String(raw.to),
    condition: raw.condition === null || raw.condition === undefined ? '' : String(raw.condition),
    priority: typeof raw.priority === 'number' ? raw.priority : 0
  }))
  // 布局恢复：全部节点带非空 layout 时保留画布位置；任一缺失回退自动布局。
  // 按原始 layout 键存在性判定（而非坐标非零），避免节点恰在原点 (0,0) 时误判回退
  const hasLayout =
    definition.nodes.length > 0 &&
    definition.nodes.every((raw) => {
      const layout = raw.layout
      return layout instanceof Object && !Array.isArray(layout) && Object.keys(layout).length > 0
    })
  if (!hasLayout) {
    autoLayout(nodes, edges)
  }
  return { nodes, edges }
}

/**
 * 按依赖分层自动布局（与 Mermaid graph TD 自上而下流向一致）。
 *
 * <p>使用 Kahn 拓扑序计算层数：层 = 1 + max(已处理父节点的层)；
 * 回边指向的父节点尚未处理时不参与计算，因此含回边的图（条件重试、
 * 确认回退等）不会导致层级无限膨胀，布局稳定。
 * 环内节点按拓扑序兜底收尾。
 *
 * @param nodes 画布节点（原地修改 x/y）
 * @param edges 画布连线
 */
function autoLayout(nodes: BuilderNode[], edges: BuilderEdge[]): void {
  const count = nodes.length
  const index = new Map(nodes.map((n, i) => [n.id, i]))
  const out: number[][] = nodes.map(() => [])
  const indegree = new Array<number>(count).fill(0)
  edges.forEach((e) => {
    const source = index.get(e.source)
    const target = index.get(e.target)
    if (source === undefined || target === undefined) return
    out[source].push(target)
    indegree[target]++
  })

  // Kahn 拓扑序（环内节点最终由兜底补入）
  const order: number[] = []
  const indeg = [...indegree]
  const queue: number[] = []
  indeg.forEach((value, i) => {
    if (value === 0) queue.push(i)
  })
  while (queue.length > 0) {
    const nodeIndex = queue.shift()!
    order.push(nodeIndex)
    out[nodeIndex].forEach((child) => {
      indeg[child]--
      if (indeg[child] === 0) queue.push(child)
    })
  }
  nodes.forEach((_, i) => {
    if (!order.includes(i)) order.push(i)
  })

  // 层 = 1 + max(已处理父节点层)；回边父节点未处理不计入
  const layer = new Array<number>(count).fill(0)
  const processed = new Array<boolean>(count).fill(false)
  order.forEach((nodeIndex) => {
    let maxParent = -1
    out.forEach((children, parent) => {
      if (processed[parent] && children.includes(nodeIndex)) {
        if (layer[parent] > maxParent) maxParent = layer[parent]
      }
    })
    layer[nodeIndex] = maxParent + 1
    processed[nodeIndex] = true
  })

  // 与 Mermaid graph TD 一致：自上而下（层决定 y，同层横向排布）
  const perLayer = new Map<number, number>()
  nodes.forEach((n, i) => {
    const layerIndex = layer[i]
    const offset = perLayer.get(layerIndex) ?? 0
    perLayer.set(layerIndex, offset + 1)
    n.x = 80 + offset * 300
    n.y = 80 + layerIndex * 130
  })
}

// ---- 可用变量推导（变量选择器数据源） ----

/** 变量命名空间。 */
export type VarNamespace = 'state' | 'extracted' | 'config' | 'user' | 'runtime'

/** 可用变量条目（变量选择器选项）。 */
export interface AvailableVar {
  namespace: VarNamespace
  /** 变量路径（含命名空间，如 state.ctx_user_id）。 */
  label: string
  /** 插值片段（如 ${state.ctx_user_id}）。 */
  value: string
  /** 来源说明（节点/隐式/配置项/固定）。 */
  origin: string
}

/** 命名空间中文标签（选择器分组标题）。 */
export const VAR_NAMESPACE_LABELS: Record<VarNamespace, string> = {
  ...BUILDER_TEXT.variableNamespaceLabels
}

/** 用户画像固定白名单（对齐引擎 buildExtraVars）。 */
const USER_VARS: Array<[string, string]> = [
  ['end_user_id', BUILDER_TEXT.variableOrigins.userId],
  ['display_name', BUILDER_TEXT.variableOrigins.userDisplayName]
]

/** 运行标识固定白名单（对齐引擎 buildExtraVars）。 */
const RUNTIME_VARS: Array<[string, string]> = [
  ['session_id', BUILDER_TEXT.variableOrigins.sessionId],
  ['execution_id', BUILDER_TEXT.variableOrigins.executionId],
  ['workflow_instance_id', BUILDER_TEXT.variableOrigins.workflowInstanceId],
  ['action_visits', '节点访问次数']
]

/** 提取文本（extractText）拆分为键列表（逗号/换行分隔）。 */
function splitKeys(text: string | undefined): string[] {
  return (text ?? '')
    .split(/[,，\n]/)
    .map((s) => s.trim())
    .filter(Boolean)
}

// ---- JSON 字段工具（可视化表单编辑器用；*Text 字符串 ⇄ 结构化往返） ----

/**
 * 安全解析 JSON 文本字段。
 *
 * <p>表单组件（KVEditor/ListEditor 等）解析 *Text 字段用；解析失败返回
 * null（调用方降级到原始 JSON textarea），不再抛异常。
 *
 * @param text JSON 文本（可为空）
 * @returns 解析结果，失败或空返回 null
 */
export function parseJsonField<T = unknown>(text: string | undefined): T | null {
  if (!text?.trim()) return null
  try {
    return JSON.parse(text) as T
  } catch {
    return null
  }
}

/**
 * 结构化对象序列化为 JSON 文本（2 空格缩进）。
 *
 * <p>与 workflowYaml 期望的原始 JSON 内联块一致；空对象/数组序列化为 ""，
 * 保证 YAML 不输出空 `args:`/`render:` 块。
 */
export function stringifyJsonField(value: unknown): string {
  if (value === null || value === undefined) return ''
  if (Array.isArray(value) && value.length === 0) return ''
  if (typeof value === 'object' && Object.keys(value as object).length === 0) return ''
  return JSON.stringify(value, null, 2)
}

/**
 * 美化 JSON 文本（2 空格缩进）；非合法 JSON 原样返回。
 *
 * <p>供「格式化 JSON」按钮：合法则排版规整便于阅读，非法保持原文以便纠错。
 */
export function formatJsonField(text: string | undefined): string {
  if (!text?.trim()) return text ?? ''
  try {
    return JSON.stringify(JSON.parse(text), null, 2)
  } catch {
    return text
  }
}

/** 从 config_schema JSON 文本解析配置键列表。 */
function configKeys(configSchemaText: string): string[] {
  try {
    const parsed = JSON.parse(configSchemaText)
    if (!Array.isArray(parsed)) return []
    return parsed
      .map((item) =>
        item && typeof item === 'object' ? String((item as Record<string, unknown>).key ?? '') : ''
      )
      .filter((key) => key)
  } catch {
    return []
  }
}

/**
 * 推导画布可用变量（变量选择器数据源）。
 *
 * <p>可用变量完全由画布数据推导：state.*（llm extract / assign target / 隐式
 * content、last_error）、extracted.*（llm extract / 隐式 action）、config.*
 * （meta.config_schema 的 key）、user.* / runtime.*（固定白名单）。
 * ctx_* 由调用方运行时注入不可枚举，不列入（选择器提供自定义手输入口）。
 *
 * @param nodes            画布节点
 * @param meta             工作流元数据（config. 键来源），可空
 * @param options          可选：namespaces 白名单（条件表达式字段仅 state/extracted）
 * @returns 可用变量列表（按命名空间分组排序）
 */
export function buildAvailableVars(
  nodes: BuilderNode[],
  meta?: WorkflowMeta,
  options?: { namespaces?: VarNamespace[] }
): AvailableVar[] {
  const allowed = new Set(
    options?.namespaces ?? (['state', 'extracted', 'config', 'user', 'runtime'] as VarNamespace[])
  )
  const vars: AvailableVar[] = []
  const seen = new Set<string>()
  const push = (namespace: VarNamespace, key: string, origin: string) => {
    if (!allowed.has(namespace) || !key) return
    // 同名键去重：多节点提取/赋值同一变量时仅保留首个来源（D14 缺陷）
    const label = `${namespace}.${key}`
    if (seen.has(label)) return
    seen.add(label)
    vars.push({ namespace, label, value: `\${${label}}`, origin })
  }

  // state.* / extracted.*：按节点推导
  for (const n of nodes) {
    if (n.type === 'assign') {
      n.assignPairs
        .filter((p) => p.target?.trim())
        .forEach((p) =>
          push(
            'state',
            p.target.trim(),
            formatAdminMessage(BUILDER_TEXT.variableOrigins.assignValue, { name: n.label })
          )
        )
    } else if (
      ['llm', 'tool', 'knowledge_query', 'script', 'human_input', 'async_task'].includes(n.type)
    ) {
      const namespace = n.resultTo.startsWith('extracted') ? 'extracted' : 'state'
      const custom = n.resultTo.includes('.') ? n.resultTo.slice(n.resultTo.indexOf('.') + 1) : ''
      const keys =
        !custom && !['human_input', 'async_task'].includes(n.type) ? splitKeys(n.extractText) : []
      if (keys.length) keys.forEach((key) => push(namespace, key, n.label + '的提取结果'))
      else push(namespace, custom || n.id, n.label + '的完整结果')
    }
    if (['human_input', 'menu'].includes(n.type)) push('extracted', 'action', '恢复时的行动标识')
  }
  // 隐式字段
  push('state', 'last_error', BUILDER_TEXT.variableOrigins.lastError)
  push('state', 'user_input', '当前输入文本')

  // config.*：meta.config_schema 的 key
  if (meta) {
    configKeys(meta.configSchemaText).forEach((k) =>
      push('config', k, BUILDER_TEXT.variableOrigins.configItem)
    )
    const schema = meta.capability?.input_schema
    if (schema && typeof schema === 'object' && !Array.isArray(schema)) {
      const properties = (schema as Record<string, unknown>).properties
      if (properties && typeof properties === 'object' && !Array.isArray(properties))
        for (const key of Object.keys(properties)) push('state', 'ctx_' + key, '工作流输入')
    }
  }
  // user.* / runtime.*：固定白名单
  USER_VARS.forEach(([k, origin]) => push('user', k, origin))
  RUNTIME_VARS.forEach(([k, origin]) => push('runtime', k, origin))

  const order: Record<VarNamespace, number> = {
    state: 0,
    extracted: 1,
    config: 2,
    user: 3,
    runtime: 4
  }
  return vars.sort(
    (a, b) => order[a.namespace] - order[b.namespace] || a.label.localeCompare(b.label)
  )
}

// ---- 变量引用收集与校验 ----

/** 变量引用占位符（对齐后端 WorkflowExpressions.PLACEHOLDER）。 */
const VAR_PLACEHOLDER = /\$\{([^}]+)\}/g

/** 会引用变量的画布文本字段（value → 字段描述）。 */
const VARIABLE_FIELDS: Array<(n: BuilderNode) => string | undefined> = [
  (n) => n.prompt,
  (n) => n.argsText,
  (n) => n.message,
  (n) => n.cardTitle,
  (n) => n.cardBody,
  (n) => n.cardFieldsText,
  (n) => n.cardActionsText,
  (n) => n.mergeMessageId,
  (n) => n.scriptInputText,
  (n) => n.scriptOutputSchemaText,
  (n) => n.renderText,
  (n) => n.knowledgeQuery,
  (n) => n.agentObjective,
  (n) => n.agentContext,
  (n) => n.agentConstraints,
  (n) => n.agentExpectedOutput
]

/**
 * 收集文本中的变量引用（${...} 去重，保留路径原文）。
 *
 * @param text 文本
 * @returns 引用路径列表（如 state.ctx_user_id）
 */
export function collectVarRefs(text: string | undefined): string[] {
  if (!text) return []
  const refs = new Set<string>()
  const matches = text.matchAll(VAR_PLACEHOLDER)
  for (const m of matches) {
    const path = m[1]?.trim()
    if (path) refs.add(path)
  }
  return [...refs]
}

/**
 * 收集单节点全部字段的变量引用告警（已知变量集由调用方构建）。
 *
 * <p>ctx_* 由调用方运行时注入不可枚举，视为合法跳过。与 validateVarRefs 共用，
 * 保证节点级（抽屉内联）与整图级校验消息一致。
 */
function collectNodeVarRefs(node: BuilderNode, known: Set<string>, warnings: string[]): void {
  const nodeWhere = formatAdminMessage(WORKFLOW_VALIDATION_TEXT.nodeWhere, { name: node.label })
  const check = (path: string, where: string) => {
    if (known.has(path) || path.startsWith('ctx_') || path.includes('.ctx_')) return
    warnings.push(formatAdminMessage(WORKFLOW_VALIDATION_TEXT.variableUndefined, { path, where }))
  }
  VARIABLE_FIELDS.forEach((field) => {
    const text = field(node)
    if (text) collectVarRefs(text).forEach((p) => check(p, nodeWhere))
  })
  node.assignPairs.forEach((p) => {
    if (p.source) {
      collectVarRefs(p.source).forEach((ref) =>
        check(
          ref,
          formatAdminMessage(WORKFLOW_VALIDATION_TEXT.variableMappingWhere, { where: nodeWhere })
        )
      )
    }
  })
  // assign source 也可能是裸变量路径（无 ${} 包裹，如 state.xxx 或字面量）
  if (node.type === 'assign') {
    node.assignPairs.forEach((p) => {
      const src = p.source?.trim() ?? ''
      if (
        src &&
        !src.includes('${') &&
        /^(state|extracted|config|user|runtime)\.[\w.]+$/.test(src)
      ) {
        check(
          src,
          formatAdminMessage(WORKFLOW_VALIDATION_TEXT.bareVariableMappingWhere, {
            where: nodeWhere
          })
        )
      }
    })
  }
}

/**
 * 校验变量引用合法性：未定义的 ${...} 引用返回警告（软警告，不阻断导出）。
 *
 * <p>ctx_* 由调用方运行时注入不可枚举，视为合法跳过；无 meta 时跳过
 * config. 引用判定（config. 键未知）。
 *
 * @param nodes 画布节点
 * @param edges 画布连线
 * @param meta  工作流元数据（config. 键来源），可空
 * @returns 变量引用警告列表
 */
export function validateVarRefs(
  nodes: BuilderNode[],
  edges: BuilderEdge[],
  meta?: WorkflowMeta
): string[] {
  const known = new Set(buildAvailableVars(nodes, meta).map((v) => v.label))
  const edgeKnown = known
  const warnings: string[] = []
  for (const n of nodes) collectNodeVarRefs(n, known, warnings)
  edges.forEach((e) => {
    if (!e.condition) return
    const where = formatAdminMessage(WORKFLOW_VALIDATION_TEXT.edgeConditionWhere, {
      source: e.source,
      target: e.target
    })
    collectVarRefs(e.condition).forEach((p) => {
      if (edgeKnown.has(p) || p.startsWith('ctx_') || p.includes('.ctx_')) return
      warnings.push(
        formatAdminMessage(WORKFLOW_VALIDATION_TEXT.variableUndefined, { path: p, where })
      )
    })
  })
  return warnings
}

/** 单节点字段校验问题（抽屉内联校验提示；field 为逻辑字段键）。 */
export interface NodeFieldIssue {
  field: string
  message: string
}

/**
 * 单节点变量引用校验（已知变量用全图推导，只检查本节点字段）。
 *
 * <p>与 validateVarRefs 共用 collectNodeVarRefs，供抽屉内联提示使用。
 */
export function validateNodeVarRefs(
  node: BuilderNode,
  allNodes: BuilderNode[],
  meta?: WorkflowMeta
): string[] {
  const known = new Set(buildAvailableVars(allNodes, meta).map((v) => v.label))
  const warnings: string[] = []
  collectNodeVarRefs(node, known, warnings)
  return warnings
}

/**
 * 单节点字段级校验问题（抽屉内联）。
 *
 * <p>字段相关结构性校验（工具名/请求地址/脚本路径/分支/行动点/on_error 目标）+
 * 该节点变量引用告警（已知变量用全图推导）。供 NodeDrawer 内联提示。
 */
export function nodeFieldIssues(
  node: BuilderNode,
  allNodes: BuilderNode[],
  edges: BuilderEdge[],
  meta?: WorkflowMeta
): NodeFieldIssue[] {
  const issues: NodeFieldIssue[] = []
  const ids = new Set(allNodes.map((n) => n.id))
  const push = (field: string, message: string) => issues.push({ field, message })
  if (
    ['llm', 'tool', 'knowledge_query', 'script', 'human_input', 'async_task'].includes(node.type) &&
    !/^(?:state|extracted)(?:\.[A-Za-z][A-Za-z0-9_]*)?$/.test(node.resultTo)
  )
    push('resultTo', '结果位置无效，请选择 state 或 extracted；自定义字段名须为字母开头的单层名称')

  switch (node.type) {
    case 'tool':
      if (!node.tool?.trim()) push('tool', WORKFLOW_VALIDATION_TEXT.missingToolName)
      else if (meta?.toolsText?.trim()) {
        const declarations = parseJsonField<unknown>(meta.toolsText)
        if (Array.isArray(declarations) && !declarations.some((tool) => tool?.name === node.tool))
          push('tool', '请从工作流已声明的工具中选择，或先补充工具声明')
      }
      break
    case 'knowledge_query':
      if (!node.knowledgeRequirement?.trim())
        push('knowledgeRequirement', '请选择已声明的知识来源槽')
      if (!node.knowledgeQuery?.trim())
        push('knowledgeQuery', WORKFLOW_VALIDATION_TEXT.missingKnowledgeQuery)
      if (meta?.capabilityRequirementsText?.trim()) {
        const slots = parseJsonField<unknown>(meta.capabilityRequirementsText)
        if (
          Array.isArray(slots) &&
          !slots.some(
            (slot) => slot?.slot === node.knowledgeRequirement && slot?.type === 'knowledge_source'
          )
        )
          push('knowledgeRequirement', '知识来源槽未声明，请重新选择或补充声明')
      }
      if (
        [
          node.knowledgeMaxItems,
          node.knowledgeMaxTokens,
          node.knowledgeMaxCharacters,
          node.knowledgeTimeoutMs,
          node.knowledgeMaxSources
        ].some((value) => !Number.isInteger(Number(value)) || Number(value) <= 0)
      ) {
        push('knowledgeBudget', '请将检索数量、Token、字符、超时和来源数量都填写为大于 0 的整数')
      }
      break
    case 'agentic':
      if (!node.agentSlot?.trim()) push('agentSlot', '缺少 Agent 槽')
      if (!node.agentObjective?.trim()) push('agentObjective', '缺少任务目标')
      if (!node.agentExpectedOutput?.trim()) push('agentExpectedOutput', '缺少期望输出')
      break
    case 'script':
      if (
        (node.original?.args as Record<string, unknown> | undefined)?.args !== undefined &&
        !node.scriptInputText?.trim()
      )
        push('scriptInputText', '脚本输入不支持旧参数数组，请在“脚本输入”中填写 JSON 对象')
      if (!node.script?.trim()) push('script', WORKFLOW_VALIDATION_TEXT.missingScriptPath)
      else if (meta?.resources && !meta.resources.some((resource) => resource.path === node.script))
        push('script', '脚本文件未在资源区保存，请先添加资源，再选择脚本文件')
      for (const [field, text] of [
        ['scriptInputText', node.scriptInputText],
        ['scriptOutputSchemaText', node.scriptOutputSchemaText]
      ] as const) {
        if (!text?.trim()) continue
        try {
          const value: unknown = JSON.parse(text)
          if (!value || typeof value !== 'object' || Array.isArray(value)) throw Error()
        } catch {
          push(field, '必须为有效的 JSON 对象')
        }
      }
      break
    case 'subgraph':
      if (!node.workflowId?.trim())
        push('workflowId', WORKFLOW_VALIDATION_TEXT.missingSubworkflowId)
      break
    case 'parallel': {
      const branches = (node.branchesText ?? '')
        .split(/\n/)
        .map((s) => s.trim())
        .filter(Boolean)
      if (branches.length === 0) push('branches', WORKFLOW_VALIDATION_TEXT.missingBranches)
      branches.forEach((branch) => {
        if (!ids.has(branch)) {
          push('branches', formatAdminMessage(WORKFLOW_VALIDATION_TEXT.branchNotFound, { branch }))
        }
      })
      if (!edges.some((e) => e.source === node.id))
        push('branches', WORKFLOW_VALIDATION_TEXT.missingJoinEdge)
      break
    }
    case 'menu': {
      const actions = node.menuActions.filter((a) => a.id && a.label && a.to)
      if (actions.length === 0) push('actions', WORKFLOW_VALIDATION_TEXT.missingCompleteAction)
      const seen = new Set<string>()
      for (const a of actions) {
        if (seen.has(a.id)) {
          push(
            'actions',
            formatAdminMessage(WORKFLOW_VALIDATION_TEXT.duplicateActionId, { id: a.id })
          )
        }
        seen.add(a.id)
        if (!ids.has(a.to)) {
          push(
            'actions',
            formatAdminMessage(WORKFLOW_VALIDATION_TEXT.actionTargetNotFound, {
              id: a.id,
              target: a.to
            })
          )
        }
      }
      break
    }
  }

  if (node.onError?.startsWith('goto:')) {
    const target = node.onError.slice('goto:'.length).trim()
    if (target && !ids.has(target)) {
      push(
        'onError',
        formatAdminMessage(WORKFLOW_VALIDATION_TEXT.onErrorTargetNotFound, { target })
      )
    }
  }

  validateNodeVarRefs(node, allNodes, meta).forEach((m) => push('vars', m))
  return issues
}

/** 画布校验，返回警告列表。 */
export function validateWorkflow(
  nodes: BuilderNode[],
  edges: BuilderEdge[],
  meta?: WorkflowMeta
): string[] {
  const errs: string[] = []
  const ids = new Set(nodes.map((n) => n.id))
  // 重复节点 id 检测：yaml 含重复 id 时画布渲染 key 冲突、引擎路由错乱（F20）
  const seenIds = new Set<string>()
  nodes.forEach((n) => {
    if (seenIds.has(n.id)) {
      errs.push(formatAdminMessage(WORKFLOW_VALIDATION_TEXT.duplicateNodeId, { id: n.id }))
    }
    seenIds.add(n.id)
  })
  edges.forEach((e, i) => {
    if (!ids.has(e.source)) {
      errs.push(
        formatAdminMessage(WORKFLOW_VALIDATION_TEXT.edgeSourceNotFound, {
          index: i + 1,
          source: e.source
        })
      )
    }
    if (!ids.has(e.target)) {
      errs.push(
        formatAdminMessage(WORKFLOW_VALIDATION_TEXT.edgeTargetNotFound, {
          index: i + 1,
          target: e.target
        })
      )
    }
  })
  // 出边歧义检测：同节点多条无条件边时引擎仅按 priority 降序取第一条，
  // 其余无条件边永不可达（其余为有条件分支时正常，仅无条件重复是缺陷）
  const plainOut = new Map<string, number>()
  edges.forEach((e) => {
    if (!e.condition?.trim()) plainOut.set(e.source, (plainOut.get(e.source) ?? 0) + 1)
  })
  plainOut.forEach((count, id) => {
    if (count > 1) {
      const node = nodes.find((n) => n.id === id)
      errs.push(
        formatAdminMessage(WORKFLOW_VALIDATION_TEXT.ambiguousEdges, {
          name: node?.label ?? id,
          count
        })
      )
    }
  })
  const connected = new Set<string>()
  edges.forEach((e) => {
    connected.add(e.source)
    connected.add(e.target)
  })
  // 注：不做有向环检测——条件重试/确认回退等合法回边会被误报为死循环，
  // 且后端 validateWorkflowRules 无环校验（两端对齐，避免同一边界前后端矛盾，D7 缺陷）
  // start/end 结构性节点豁免「未连接」检查：start 允许无入边、end 允许无出边
  nodes.forEach((n) => {
    if (n.type === 'start' || n.type === 'end') return
    if (!connected.has(n.id)) {
      errs.push(formatAdminMessage(WORKFLOW_VALIDATION_TEXT.nodeUnconnected, { name: n.label }))
    }
  })
  for (const n of nodes) {
    if (!isSupportedWorkflowNodeType(n.type)) {
      errs.push(
        formatAdminMessage(WORKFLOW_VALIDATION_TEXT.unsupportedType, {
          name: n.label,
          type: n.type
        })
      )
    } else if (!isExecutableWorkflowNodeType(n.type)) {
      errs.push(
        formatAdminMessage(WORKFLOW_VALIDATION_TEXT.nonExecutableType, {
          name: n.label,
          type: n.type
        })
      )
    }
    // 节点字段级规则收敛到 nodeFieldIssues（单点维护；vars 告警由下方全局变量校验覆盖，避免重复）
    nodeFieldIssues(n, nodes, edges, meta)
      .filter((issue) => issue.field !== 'vars')
      .forEach((issue) =>
        errs.push(
          formatAdminMessage(WORKFLOW_VALIDATION_TEXT.nodeIssue, {
            type: n.type,
            name: n.label,
            message: issue.message
          })
        )
      )
  }
  // JSON 字段预检：内联 JSON 非法会产出非法 YAML（后端 422），保存前给出节点级提示
  for (const n of nodes) {
    const jsonFields: Array<[string, string | undefined]> = [
      ['args', n.type === 'script' ? undefined : n.argsText],
      ['script input', n.type === 'script' ? n.scriptInputText : undefined],
      ['script output_schema', n.type === 'script' ? n.scriptOutputSchemaText : undefined],
      ['render', n.renderText],
      ['card fields', n.cardFieldsText],
      ['card actions', n.cardActionsText],
      ['card style', n.cardStyleText]
    ]
    for (const [field, text] of jsonFields) {
      if (!text?.trim()) continue
      try {
        JSON.parse(text)
      } catch {
        errs.push(
          formatAdminMessage(WORKFLOW_VALIDATION_TEXT.invalidNodeJson, {
            name: n.label,
            field
          })
        )
      }
    }
  }
  // SF-WF-008：start/end 结构性节点约束（存在时校验，警告不阻断）
  const starts = nodes.filter((n) => n.type === 'start')
  const ends = nodes.filter((n) => n.type === 'end')
  if (starts.length > 1) {
    errs.push(formatAdminMessage(WORKFLOW_VALIDATION_TEXT.startLimit, { count: starts.length }))
  }
  if (starts.length > 0) {
    const entry =
      meta?.entryNodeId || nodes.find((n) => n.type === 'start')?.id || nodes[0]?.id || ''
    if (entry !== starts[0].id) {
      errs.push(
        formatAdminMessage(WORKFLOW_VALIDATION_TEXT.entryStartMismatch, {
          entry,
          start: starts[0].id
        })
      )
    }
    if (!edges.some((e) => e.source === starts[0].id)) {
      errs.push(
        formatAdminMessage(WORKFLOW_VALIDATION_TEXT.startMissingOutgoing, { name: starts[0].label })
      )
    }
  }
  for (const e of edges) {
    if (starts.some((s) => s.id === e.target)) {
      errs.push(
        formatAdminMessage(WORKFLOW_VALIDATION_TEXT.startIncomingForbidden, { name: e.target })
      )
    }
    if (ends.some((ed) => ed.id === e.source)) {
      errs.push(
        formatAdminMessage(WORKFLOW_VALIDATION_TEXT.endOutgoingForbidden, { name: e.source })
      )
    }
  }
  // SF-WF-009：无 end 时允许自然终止，但保留明确提示。
  if (ends.length === 0) errs.push(WORKFLOW_VALIDATION_TEXT.missingEnd)
  // 变量引用软警告（未定义 ${...} 引用；ctx_* 运行时注入跳过）
  errs.push(...validateVarRefs(nodes, edges, meta))
  return errs
}

/** 默认元数据。 */
export function defaultWorkflowMeta(): WorkflowMeta {
  return {
    name: 'builder-workflow',
    version: '1.0.0',
    displayName: BUILDER_TEXT.defaults.displayName,
    description: BUILDER_TEXT.defaults.description,
    category: 'general',
    tagsText: 'builder',
    configSchemaText: '[]',
    capability: undefined,
    resources: [],
    entryNodeId: '',
    originalDefinition: undefined,
    originalMeta: undefined
  }
}

/** 新建时使用；读取已保存治理时不能自动补齐缺项。 */
export function recommendedWorkflowCapability(): Record<string, unknown> {
  return {
    model_invocable: true,
    user_invocable: true,
    input_schema: { type: 'object', properties: {} },
    output_schema: { type: 'object', properties: {} },
    supports_hitl: false,
    export_fields: [],
    timeout_ms: 30000,
    concurrency_safe: false
  }
}

/** 带对象与字段定位的本地检查；变量引用与自然终止保留为提醒。 */
export function workflowProblems(
  nodes: BuilderNode[],
  edges: BuilderEdge[],
  meta: WorkflowMeta,
  references?: { validate: (path: string, nodeId: string, includeCurrent: boolean) => string }
): Array<{
  code: string
  severity: 'error' | 'warning'
  objectRef: string
  fieldPath: string
  message: string
}> {
  const legacyWarnings = validateVarRefs(nodes, edges, meta)
  const warnings = new Set([...legacyWarnings, WORKFLOW_VALIDATION_TEXT.missingEnd])
  const problems = validateWorkflow(nodes, edges, meta)
    .filter((message) => !references || !legacyWarnings.includes(message))
    .map((message) => {
      const node = nodes.find((n) =>
        nodeFieldIssues(n, nodes, edges, meta).some(
          (issue) =>
            message ===
            formatAdminMessage(WORKFLOW_VALIDATION_TEXT.nodeIssue, {
              type: n.type,
              name: n.label,
              message: issue.message
            })
        )
      )
      const issue =
        node &&
        nodeFieldIssues(node, nodes, edges, meta).find((issue) => message.endsWith(issue.message))
      return {
        code: 'LOCAL_VALIDATION',
        severity: (warnings.has(message) ? 'warning' : 'error') as 'error' | 'warning',
        objectRef: node ? `node:${node.id}` : 'workflow',
        fieldPath: issue?.field || '',
        message
      }
    })
  if (references) {
    const check = (
      text: string | undefined,
      nodeId: string,
      includeCurrent: boolean,
      objectRef: string,
      fieldPath: string
    ) => {
      for (const path of collectVarRefs(text)) {
        const reason = references.validate(path, nodeId, includeCurrent)
        if (reason)
          problems.push({
            code: 'VARIABLE_REFERENCE',
            severity: 'warning',
            objectRef,
            fieldPath,
            message: `变量 ${path}：${reason}`
          })
      }
    }
    for (const node of nodes) {
      const fields: Array<[string, string | undefined]> = []
      if (['llm', 'human_input', 'menu'].includes(node.type)) fields.push(['prompt', node.prompt])
      if (['output', 'card'].includes(node.type)) fields.push(['message', node.message])
      if (node.type === 'tool') fields.push(['args', node.argsText])
      if (node.type === 'script') fields.push(['args.input', node.scriptInputText])
      if (node.type === 'knowledge_query') fields.push(['query', node.knowledgeQuery])
      if (['card', 'human_input'].includes(node.type))
        fields.push(
          ['card.title', node.cardTitle],
          ['card.body', node.cardBody],
          ['card.fields', node.cardFieldsText],
          ['card.actions', node.cardActionsText],
          ['merge_message_id', node.mergeMessageId]
        )
      if (['card', 'human_input', 'menu'].includes(node.type))
        fields.push(['render', node.renderText])
      for (const [field, text] of fields) check(text, node.id, false, 'node:' + node.id, field)
      if (node.type === 'assign')
        for (const [index, pair] of node.assignPairs.entries()) {
          const bare = /^(?:state|extracted|config|user|runtime)\.[^\s]+$/.test(pair.source.trim())
            ? '${' + pair.source.trim() + '}'
            : pair.source
          check(bare, node.id, false, 'node:' + node.id, `assignPairs[${index}].source`)
        }
    }
    for (const edge of edges)
      check(edge.condition, edge.source, true, 'edge:' + edge.id, 'condition')
  }
  const signatures = new Set<string>()
  for (const edge of edges) {
    const signature = JSON.stringify([
      edge.source,
      edge.target,
      edge.condition?.trim() || '',
      edge.priority || 0
    ])
    if (signatures.has(signature))
      problems.push({
        code: 'DUPLICATE_EDGE',
        severity: 'error',
        objectRef: `edge:${edge.id}`,
        fieldPath: 'condition',
        message: '此连接的起点、终点、条件和优先级均重复，请删除重复连接或调整配置'
      })
    signatures.add(signature)
    if (!Number.isSafeInteger(edge.priority ?? 0))
      problems.push({
        code: 'EDGE_PRIORITY',
        severity: 'error',
        objectRef: `edge:${edge.id}`,
        fieldPath: 'priority',
        message: '连接优先级无效，请填写 0、1、2 等整数'
      })
    if (
      !nodes.some((node) => node.id === edge.source) ||
      !nodes.some((node) => node.id === edge.target)
    )
      problems.push({
        code: 'EDGE_TARGET',
        severity: 'error',
        objectRef: `edge:${edge.id}`,
        fieldPath: 'target',
        message: '连接的起点或终点已不存在，请重新选择工作流中的节点'
      })
  }
  if (meta.entryNodeId && !nodes.some((n) => n.id === meta.entryNodeId))
    problems.push({
      code: 'ENTRY',
      severity: 'error',
      objectRef: 'workflow',
      fieldPath: 'entry',
      message: '入口节点已不存在，请重新选择入口节点'
    })
  try {
    workflowConfigFields(meta.configSchemaText)
  } catch (error) {
    problems.push({
      code: 'CONFIG_SCHEMA',
      severity: 'error',
      objectRef: 'meta',
      fieldPath: 'config_schema',
      message:
        error instanceof SyntaxError
          ? '安装参数 JSON 格式不正确，请在“高级 JSON 编辑”中检查引号、逗号和括号'
          : error instanceof Error
            ? error.message
            : '安装参数声明无效，请检查参数配置'
    })
  }
  try {
    definitionMeta(meta)
  } catch (error) {
    problems.push({
      code: 'DECLARATION',
      severity: 'error',
      objectRef: 'meta',
      fieldPath: 'capability_requirements',
      message: error instanceof Error ? error.message : '声明无效'
    })
  }
  return problems
}

export interface WorkflowConfigField {
  key: string
  type: 'string' | 'textarea' | 'boolean' | 'integer' | 'number' | 'enum'
  label?: string
  description?: string
  required?: boolean
  default?: string | number | boolean
  enum_values?: string[]
  min?: number
  max?: number
  [key: string]: unknown
}

/** 安装参数声明是字段数组，字段值在提交安装前按同一契约验证。 */
export function workflowConfigFields(text: string): WorkflowConfigField[] {
  const fields: unknown = text.trim() ? JSON.parse(text) : []
  if (!Array.isArray(fields)) throw new Error('安装参数声明必须为字段数组')
  const keys = new Set<string>()
  return fields.map((value, index) => {
    if (!value || typeof value !== 'object' || Array.isArray(value))
      throw new Error(`安装参数第 ${index + 1} 项必须为对象`)
    const field = value as WorkflowConfigField
    if (typeof field.key !== 'string' || !/^[a-z][a-z0-9_]{0,63}$/.test(field.key))
      throw new Error(
        `第 ${index + 1} 项的参数标识无效：须为小写字母开头的 1–64 位小写字母、数字或下划线`
      )
    if (keys.has(field.key)) throw new Error(`安装参数重复：${field.key}`)
    keys.add(field.key)
    if (!['string', 'textarea', 'boolean', 'integer', 'number', 'enum'].includes(field.type))
      throw new Error(`安装参数 ${field.key} 的类型不支持，请在参数声明中重新选择类型`)
    if (field.required !== undefined && typeof field.required !== 'boolean')
      throw new Error(`安装参数 ${field.key} 的 required 必须为布尔值`)
    if (
      field.type === 'enum' &&
      (!Array.isArray(field.enum_values) ||
        !field.enum_values.length ||
        field.enum_values.some((item) => typeof item !== 'string' || !item.trim()) ||
        new Set(field.enum_values).size !== field.enum_values.length)
    )
      throw new Error(`安装参数 ${field.key} 的枚举值必须为非空且不重复的字符串数组`)
    for (const bound of ['min', 'max'] as const)
      if (
        field[bound] !== undefined &&
        (typeof field[bound] !== 'number' || !Number.isFinite(field[bound]))
      )
        throw new Error(`安装参数 ${field.key} 的 ${bound} 必须为数值`)
    if (field.min !== undefined && field.max !== undefined && field.min > field.max)
      throw new Error(`安装参数 ${field.key} 的最小值不能大于最大值`)
    if (field.default !== undefined)
      workflowInstallationConfig([field], { [field.key]: field.default })
    return field
  })
}

export function workflowInstallationConfig(
  fields: WorkflowConfigField[],
  values: Record<string, unknown>
): Record<string, unknown> {
  const config: Record<string, unknown> = {}
  for (const field of fields) {
    const value = values[field.key] ?? field.default
    if (
      value === undefined ||
      value === null ||
      (value === '' && !['string', 'textarea'].includes(field.type))
    ) {
      if (field.required) throw new Error(`请填写安装参数：${field.label || field.key}`)
      continue
    }
    if (['string', 'textarea', 'enum'].includes(field.type) && typeof value !== 'string')
      throw new Error(`安装参数 ${field.key} 必须为字符串`)
    if (field.required && typeof value === 'string' && !value.trim())
      throw new Error(`安装参数 ${field.key} 不能为空白`)
    if (field.type === 'boolean' && typeof value !== 'boolean')
      throw new Error(`安装参数 ${field.key} 必须为布尔值`)
    if (['integer', 'number'].includes(field.type)) {
      if (
        typeof value !== 'number' ||
        !Number.isFinite(value) ||
        (field.type === 'integer' && !Number.isSafeInteger(value))
      )
        throw new Error(`安装参数 ${field.key} 必须为${field.type === 'integer' ? '整数' : '数值'}`)
      if (
        (field.min !== undefined && value < field.min) ||
        (field.max !== undefined && value > field.max)
      )
        throw new Error(`安装参数 ${field.key} 超出允许范围，请按声明的最小值和最大值调整`)
    }
    if (field.type === 'enum' && !field.enum_values?.includes(String(value)))
      throw new Error(`安装参数 ${field.key} 不在可选值中，请从声明的选项中选择`)
    config[field.key] = value
  }
  return config
}

export function matchesWorkflowVersion(constraint: string, version: string): boolean {
  if (constraint === '*') return true
  const segments = constraint.split('.')
  if (['x', 'X', '*'].includes(segments[segments.length - 1]) && segments.length > 1)
    return version.startsWith(segments.slice(0, -1).join('.') + '.')
  return constraint === version
}

export function workflowDefinitionChanges(
  before: unknown,
  after: unknown,
  path = ''
): Array<{
  operation: 'add' | 'remove' | 'replace'
  path: string
  beforeValue: unknown
  afterValue: unknown
}> {
  if (JSON.stringify(before) === JSON.stringify(after)) return []
  if (
    before &&
    after &&
    typeof before === 'object' &&
    typeof after === 'object' &&
    !Array.isArray(before) &&
    !Array.isArray(after)
  ) {
    const left = before as Record<string, unknown>,
      right = after as Record<string, unknown>
    return [...new Set([...Object.keys(left), ...Object.keys(right)])].flatMap((key) =>
      workflowDefinitionChanges(
        left[key],
        right[key],
        `${path}/${key.replace(/~/g, '~0').replace(/\//g, '~1')}`
      )
    )
  }
  return [
    {
      operation: before === undefined ? 'add' : after === undefined ? 'remove' : 'replace',
      path: path || '/',
      beforeValue: before,
      afterValue: after
    }
  ]
}

/** 从名称推导业务 ID（与后端 WorkflowController.slugifyWorkflowId 同规则；
 *  全非 ASCII（如中文）时按时间戳兜底，保证 URL 合法且唯一）。 */
export function slugifyWorkflowId(name: string): string {
  const slug = name
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9_-]+/g, '-')
    .replace(/^-+|-+$/g, '')
  if (!slug || !/[a-z0-9]/.test(slug)) {
    return `workflow-${Date.now().toString(36)}`
  }
  return slug
}

/** 后端详情响应 → WorkflowMeta（元数据导入映射；模板 name 列即展示名，扩展字段保留往返）。 */
export function detailToMeta(detail: {
  name: string
  version: string
  description: string
  category?: string
  tags?: string[]
  config_schema?: Array<Record<string, unknown>>
  author?: string
  tools?: Array<Record<string, unknown>>
  capability_requirements?: Array<Record<string, unknown>>
  required_permissions?: string[]
  capability?: Record<string, unknown> | null
}): Partial<WorkflowMeta> {
  return {
    name: detail.name,
    displayName: detail.name,
    version: detail.version,
    description: detail.description ?? '',
    category: detail.category ?? '',
    tagsText: (detail.tags ?? []).join(','),
    configSchemaText: detail.config_schema ? JSON.stringify(detail.config_schema) : '',
    author: detail.author ?? '',
    toolsText: detail.tools?.length ? JSON.stringify(detail.tools) : '',
    capabilityRequirementsText: detail.capability_requirements?.length
      ? JSON.stringify(detail.capability_requirements)
      : '',
    requiredPermissionsText: detail.required_permissions?.length
      ? JSON.stringify(detail.required_permissions)
      : '',
    capability:
      detail.capability === undefined ? undefined : JSON.parse(JSON.stringify(detail.capability)),
    originalMeta: JSON.parse(JSON.stringify(detail))
  }
}
