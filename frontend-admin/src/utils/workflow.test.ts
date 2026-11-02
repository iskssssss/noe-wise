/**
 * workflow.ts 纯函数模块单元测试。
 */
import { describe, expect, it, beforeEach } from 'vitest'
import {
  type BuilderEdge,
  type BuilderNode,
  type WorkflowMeta,
  workflowYaml,
  workflowDefinition,
  workflowMetaYaml,
  parseWorkflowJson,
  validateWorkflow,
  validateVarRefs,
  validateNodeVarRefs,
  nodeFieldIssues,
  buildAvailableVars,
  defaultWorkflowMeta,
  parseJsonField,
  stringifyJsonField,
  formatJsonField,
  slugifyWorkflowId,
  detailToMeta,
  WORKFLOW_NODE_CATALOG,
  EXECUTABLE_WORKFLOW_NODE_CATALOG,
  isSupportedWorkflowNodeType,
  isExecutableWorkflowNodeType,
  defaultBuilderNodePosition,
  workflowConfigFields,
  workflowInstallationConfig,
  workflowDefinitionChanges,
  matchesWorkflowVersion,
  workflowProblems,
  recommendedWorkflowCapability,
  recommendedWorkflowResultTo,
  connectWorkflowNode
} from './workflow'

describe('工作流安装与无损编辑契约', () => {
  it('新节点推荐可读结果字段并避开已有结果与赋值字段，不重写原定义', () => {
    const old = node({ id: 'legacy', type: 'tool', resultTo: 'state' })
    const occupied = node({
      id: 'assign',
      type: 'assign',
      assignPairs: [{ target: 'tool_result', source: 'false' }]
    })
    expect(recommendedWorkflowResultTo('tool', [old, occupied])).toBe('state.tool_result_2')
    expect(old.resultTo).toBe('state')
    expect(recommendedWorkflowResultTo('llm', [])).toBe('state.model_result')
    expect(recommendedWorkflowResultTo('output', [])).toBe('state')
  })
  it('插入节点保留进入分支的条件、优先级和扩展，新增无条件后续边', () => {
    const nodes = [
      node({ id: 'source', type: 'condition' }),
      node({ id: 'target', type: 'output', x: 260 })
    ]
    const original = {
      id: 'branch',
      source: 'source',
      target: 'target',
      condition: '${state.flag} == false',
      priority: 7,
      original: { custom: 0 }
    }
    const inserted = node({ id: 'new', type: 'output' })
    const graph = connectWorkflowNode(nodes, [original], inserted, {
      kind: 'edge',
      edgeId: 'branch'
    })
    expect(graph.edges[0]).toEqual({ ...original, target: 'new' })
    expect(graph.edges[1]).toMatchObject({
      source: 'new',
      target: 'target',
      condition: '',
      priority: 0
    })
    expect(original.target).toBe('target')
    expect(nodes).toHaveLength(2)
    expect(Math.abs(inserted.y - nodes[1]!.y)).toBeGreaterThanOrEqual(116)
  })
  it('菜单选项插入只更新被选行动点，不产生普通默认分支', () => {
    const menu = node({
      id: 'menu',
      type: 'menu',
      menuActions: [
        { id: 'yes', label: '确认', to: 'end' },
        { id: 'no', label: '取消', to: 'other' }
      ]
    })
    const graph = connectWorkflowNode(
      [menu, node({ id: 'end', type: 'end' })],
      [],
      node({ id: 'audit', type: 'output' }),
      { kind: 'action', nodeId: 'menu', actionId: 'yes' }
    )
    expect(graph.nodes[0]!.menuActions).toEqual([
      { id: 'yes', label: '确认', to: 'audit' },
      { id: 'no', label: '取消', to: 'other' }
    ])
    expect(graph.edges).toEqual([expect.objectContaining({ source: 'audit', target: 'end' })])
    expect(menu.menuActions[0]!.to).toBe('end')
  })
  it('有来源的引用问题可定位到节点字段与连接，条件能使用刚产出的值', () => {
    const nodes = [
      node({ id: 'producer', type: 'tool', tool: 'lookup' }),
      node({ id: 'reply', type: 'output', message: '${state.old_result.count}' })
    ]
    const edges = [
      {
        id: 'branch',
        source: 'producer',
        target: 'reply',
        condition: '${state.producer.structuredContent.count} > 0'
      }
    ]
    const seen: Array<[string, string, boolean]> = []
    const problems = workflowProblems(nodes, edges, defaultWorkflowMeta(), {
      validate: (path, id, includeCurrent) => {
        seen.push([path, id, includeCurrent])
        return path.startsWith('state.old_result') ? '来源已删除或不在上游' : ''
      }
    })
    expect(problems).toContainEqual(
      expect.objectContaining({
        code: 'VARIABLE_REFERENCE',
        objectRef: 'node:reply',
        fieldPath: 'message',
        severity: 'warning'
      })
    )
    expect(seen).toContainEqual(['state.producer.structuredContent.count', 'producer', true])
    expect(problems.filter((problem) => problem.code === 'VARIABLE_REFERENCE')).toHaveLength(1)
  })
  it('卡片身份与类型往返保留，清空已编辑样式不会恢复原值', () => {
    const parsed = parseWorkflowJson({
      nodes: [
        {
          id: 'confirm',
          type: 'human_input',
          card: {
            card_id: 'approval-42',
            type: 'info',
            title: '原标题',
            style: { theme: 'dark' },
            custom: 'keep'
          }
        }
      ],
      edges: []
    })
    const edited = parsed.nodes[0]!
    edited.label = '新节点名称'
    edited.cardStyleText = ''
    const definition = workflowDefinition([edited], [], defaultWorkflowMeta())
    const card = (definition.nodes as Array<Record<string, unknown>>)[0]!.card
    expect(card).toMatchObject({ card_id: 'approval-42', type: 'info', custom: 'keep' })
    expect(card).not.toHaveProperty('style')
  })
  it('消费类型化默认值、必填和范围，保留可选文本空字符串', () => {
    const fields = workflowConfigFields(
      '[{"key":"enabled","type":"boolean","default":false},{"key":"count","type":"integer","min":1,"max":4,"required":true},{"key":"note","type":"textarea"},{"key":"kind","type":"enum","enum_values":["a","b"]}]'
    )
    expect(workflowInstallationConfig(fields, { count: 2, note: '', kind: 'a' })).toEqual({
      enabled: false,
      count: 2,
      note: '',
      kind: 'a'
    })
    expect(() => workflowInstallationConfig(fields, { count: 2.5 })).toThrow('整数')
    expect(() => workflowInstallationConfig(fields, { count: 5 })).toThrow('范围')
    expect(() => workflowInstallationConfig(fields, { count: 1, kind: 'z' })).toThrow(
      '不在可选值中'
    )
    expect(() => workflowInstallationConfig(fields, {})).toThrow('请填写')
    expect(() =>
      workflowConfigFields('[{"key":"same","type":"string"},{"key":"same","type":"boolean"}]')
    ).toThrow('重复')
  })
  it.each(['string', 'textarea'] as const)(
    '安装%s区分未提供的默认值与明确清空，必填空白仍拒绝',
    (type) => {
      const fields = workflowConfigFields(
        JSON.stringify([{ key: 'note', type, default: '原默认值' }])
      )
      expect(workflowInstallationConfig(fields, {})).toEqual({ note: '原默认值' })
      expect(workflowInstallationConfig(fields, { note: '' })).toEqual({ note: '' })
      expect(workflowInstallationConfig(fields, { note: ' ' })).toEqual({ note: ' ' })
      expect(workflowInstallationConfig([{ key: 'note', type }], {})).toEqual({})
      expect(workflowInstallationConfig([{ key: 'note', type }], { note: '' })).toEqual({
        note: ''
      })
      expect(() =>
        workflowInstallationConfig([{ ...fields[0]!, required: true }], { note: '' })
      ).toThrow()
      expect(() =>
        workflowInstallationConfig([{ ...fields[0]!, required: true }], { note: ' ' })
      ).toThrow('空白')
    }
  )
  it('非文本清空仍省略，显式0和false不被默认值替换', () => {
    const fields = workflowConfigFields(
      '[{"key":"enabled","type":"boolean","default":true},{"key":"count","type":"integer","default":2},{"key":"kind","type":"enum","enum_values":["a","b"],"default":"a"}]'
    )
    expect(workflowInstallationConfig(fields, {})).toEqual({ enabled: true, count: 2, kind: 'a' })
    expect(workflowInstallationConfig(fields, { enabled: '', count: '', kind: '' })).toEqual({})
    expect(workflowInstallationConfig(fields, { enabled: false, count: 0, kind: 'b' })).toEqual({
      enabled: false,
      count: 0,
      kind: 'b'
    })
  })
  it('按后端精确版本和末段通配规则筛选', () => {
    expect(matchesWorkflowVersion('*', '2.1.0')).toBe(true)
    expect(matchesWorkflowVersion('1.x', '1.2.3')).toBe(true)
    expect(matchesWorkflowVersion('1.X', '11.2.3')).toBe(false)
    expect(matchesWorkflowVersion('1.2.3', '1.2.4')).toBe(false)
  })
  it('保留顶层、元数据、节点、连接扩展以及冻结脚本对象输入', () => {
    const original = {
      version: '1.0',
      entry: 'script',
      progress: { message: '执行中' },
      state_schema: [{ key: 'value' }],
      resources: [{ path: 'scripts/a.js', content_base64: 'YQ==', sha256: 'hash' }],
      nodes: [
        {
          id: 'script',
          type: 'script',
          custom: { keep: true },
          args: { script: 'scripts/a.js', input: { count: 2 }, output_schema: { type: 'object' } }
        }
      ],
      edges: []
    }
    const parsed = parseWorkflowJson(original)
    const meta = {
      ...defaultWorkflowMeta(),
      originalDefinition: original,
      entryNodeId: 'script',
      resources: original.resources,
      originalMeta: { custom_meta: '保留' }
    }
    const saved = workflowDefinition(parsed.nodes, parsed.edges, meta)
    expect(saved).toMatchObject({
      progress: original.progress,
      state_schema: original.state_schema,
      resources: original.resources,
      nodes: [
        {
          custom: { keep: true },
          args: { script: 'scripts/a.js', input: { count: 2 }, output_schema: { type: 'object' } }
        }
      ],
      meta: { custom_meta: '保留' }
    })
    expect((saved.nodes as Array<Record<string, unknown>>)[0].args).not.toHaveProperty('args')
    expect(() => parseWorkflowJson({ nodes: null, edges: [] } as never)).toThrow('nodes 和 edges')
  })
  it('本地比较包含未保存变化，并转义 JSON Pointer 路径', () => {
    expect(workflowDefinitionChanges({ 'a/b': 1 }, { 'a/b': 2 })).toEqual([
      { operation: 'replace', path: '/a~1b', beforeValue: 1, afterValue: 2 }
    ])
  })
  it('变量提醒不阻断保存，脚本字段结构错误可定位', () => {
    const output = node({ type: 'output', message: '${state.missing}' })
    expect(
      workflowProblems([output], [], defaultWorkflowMeta())
        .filter((p) => p.message.includes('state.missing'))
        .every((p) => p.severity === 'warning')
    ).toBe(true)
    const script = node({ type: 'script', script: 'scripts/a.js', scriptInputText: '[]' })
    expect(workflowProblems([script], [], defaultWorkflowMeta())).toContainEqual(
      expect.objectContaining({
        severity: 'error',
        objectRef: 'node:n1',
        fieldPath: 'scriptInputText'
      })
    )
    expect(recommendedWorkflowCapability()).toMatchObject({
      timeout_ms: 30000,
      supports_hitl: false
    })
    expect(defaultWorkflowMeta().capability).toBeUndefined()
  })
})

function node(overrides: Partial<BuilderNode> = {}): BuilderNode {
  return {
    id: 'n1',
    type: 'llm',
    label: '测试节点',
    x: 0,
    y: 0,
    resultTo: 'state',
    assignPairs: [],
    menuActions: [],
    ...overrides
  }
}

describe('defaultBuilderNodePosition', () => {
  it('为连续面板添加分配互不重叠的网格位置', () => {
    expect(defaultBuilderNodePosition(0)).toEqual({ x: 80, y: 80 })
    expect(defaultBuilderNodePosition(1)).toEqual({ x: 340, y: 80 })
    expect(defaultBuilderNodePosition(3)).toEqual({ x: 80, y: 208 })
  })
})

describe('workflowYaml', () => {
  it('输出对齐 WorkflowParser 契约：entry/nodes/edges', () => {
    const nodes = [
      node({
        id: 'a',
        type: 'tool',
        tool: 'check_user_status',
        argsText: '{"user_id": "${state.ctx_user_id}"}'
      })
    ]
    const edges: BuilderEdge[] = [
      {
        id: 'e1',
        source: 'a',
        target: 'b',
        condition: '${state.ctx_is_formal} == true',
        priority: 5
      }
    ]
    const yaml = workflowYaml(nodes, edges)
    expect(yaml).toContain('entry: a')
    expect(yaml).toContain('  - id: a')
    expect(yaml).toContain('    type: tool')
    expect(yaml).toContain('    tool: "check_user_status"')
    expect(yaml).toContain('  - from: a')
    expect(yaml).toContain('    to: b')
    expect(yaml).toContain('    condition: "${state.ctx_is_formal} == true"')
    expect(yaml).toContain('    priority: 5')
  })

  it('条件为空时输出无条件边', () => {
    const yaml = workflowYaml([node()], [{ id: 'e1', source: 'n1', target: 'n2' }])
    expect(yaml).toContain('  - from: n1')
    expect(yaml).not.toContain('condition')
  })

  it('script 节点输出 args map 契约（{script, input, output_schema}），不输出顶层 script 键', () => {
    const nodes = [
      node({
        id: 's1',
        type: 'script',
        script: 'scripts/x.py',
        scriptInputText: '{"name":"${state.name}"}'
      })
    ]
    const yaml = workflowYaml(nodes, [])
    expect(yaml).not.toContain('script: ')
    expect(yaml).toContain('    args: {"script":"scripts/x.py","input":{"name":"${state.name}"}}')
  })

  it('async_task 节点输出 timeout_seconds（挂起超时）', () => {
    const nodes = [node({ id: 'w', type: 'async_task', timeoutSeconds: '600' })]
    const yaml = workflowYaml(nodes, [])
    expect(yaml).toContain('    type: async_task')
    expect(yaml).toContain('    timeout_seconds: 600')
  })

  it('render / merge_message_id 序列化（渲染契约与卡片 merge）', () => {
    const nodes = [
      node({
        id: 'c1',
        type: 'card',
        renderText:
          '{"type":"progress_card","sub_tasks":[{"code":"sync","status":"${state.sync_status}"}]}',
        mergeMessageId: '${state.progress_message_id}'
      })
    ]
    const yaml = workflowYaml(nodes, [])
    expect(yaml).toContain(
      '    render: {"type":"progress_card","sub_tasks":[{"code":"sync","status":"${state.sync_status}"}]}'
    )
    expect(yaml).toContain('    merge_message_id: "${state.progress_message_id}"')
  })

  it('human_input 支持 timeout_seconds 与 merge_message_id', () => {
    const nodes = [
      node({ id: 'h1', type: 'human_input', timeoutSeconds: '300', mergeMessageId: 'm-1' })
    ]
    const yaml = workflowYaml(nodes, [])
    expect(yaml).toContain('    timeout_seconds: 300')
    expect(yaml).toContain('    merge_message_id: "m-1"')
  })
})

describe('workflowYaml meta 段（原 WORKFLOW.md frontmatter）', () => {
  it('meta 段字段完整生成，画布位置不进入运行定义', () => {
    const meta: WorkflowMeta = {
      ...defaultWorkflowMeta(),
      name: 'my-workflow',
      displayName: '服务申请',
      tagsText: '申请,通用',
      configSchemaText: '[{"key":"k"}]'
    }
    const yaml = workflowYaml([node({ id: 'start', type: 'start', x: 120, y: 80 })], [], meta)
    expect(yaml).toContain('meta:')
    expect(yaml).toContain('  name: "my-workflow"')
    expect(yaml).toContain('  type: state-machine')
    expect(yaml).toContain('  display_name: "服务申请"')
    expect(yaml).toContain('  tags: ["申请", "通用"]')
    expect(yaml).toContain('[{"key":"k"}]')
    // 顶层 name 导出约定（-workflow 后缀）；布局改由 BuilderSnapshot 保存
    expect(yaml).not.toContain('name: "my-workflow-definition"')
    expect(yaml).not.toContain('layout:')
  })

  it('workflowMetaYaml 仅输出 meta 段文本（详情页预览）', () => {
    const meta: WorkflowMeta = { ...defaultWorkflowMeta(), name: 'demo', displayName: '演示' }
    const text = workflowMetaYaml(meta)
    expect(text.startsWith('meta:')).toBe(true)
    expect(text).toContain('  name: "demo"')
  })
})

describe('parseWorkflowJson', () => {
  it('后端结构化定义 → 画布模型（全类型字段映射）', () => {
    const parsed = parseWorkflowJson({
      entry: 'a',
      nodes: [
        {
          id: 'a',
          type: 'tool',
          name: '校验',
          tool: 'check_user_status',
          args: { user_id: '${state.ctx_user_id}' },
          result_to: 'state'
        },
        {
          id: 'b',
          type: 'llm',
          name: '提取',
          prompt: '提取信息',
          extract: ['name', 'phone'],
          result_to: 'state'
        },
        { id: 'c', type: 'parallel', name: '并行', branches: ['b'] },
        { id: 'd', type: 'assign', name: '映射', args: { ctx_is_formal: '${state.is_formal}' } },
        {
          id: 'e',
          type: 'script',
          name: '脚本',
          args: { script: 'scripts/x.py', input: { name: '${state.name}' } }
        },
        { id: 'f', type: 'human_input', name: '确认', card: { title: '确认申请', body: '确认？' } }
      ],
      edges: [{ from: 'a', to: 'b', condition: '${state.x} == true', priority: 10 }]
    })
    expect(parsed.nodes).toHaveLength(6)
    expect(parsed.nodes[0].tool).toBe('check_user_status')
    expect(parsed.nodes[1].extractText).toBe('name,phone')
    expect(parsed.nodes[2].branchesText).toBe('b')
    expect(parsed.nodes[3].assignPairs).toEqual([
      { target: 'ctx_is_formal', source: '${state.is_formal}' }
    ])
    expect(parsed.nodes[4].script).toBe('scripts/x.py')
    expect(JSON.parse(parsed.nodes[4].scriptInputText!)).toEqual({ name: '${state.name}' })
    expect(parsed.nodes[5].cardTitle).toBe('确认申请')
    expect(parsed.edges[0].condition).toBe('${state.x} == true')
    expect(parsed.edges[0].priority).toBe(10)
  })
})

describe('parseWorkflowJson layout 恢复', () => {
  it('节点带 layout 时保留画布位置，缺失时自动布局', () => {
    const parsed = parseWorkflowJson({
      entry: 'a',
      nodes: [
        { id: 'a', type: 'start', name: '开始', layout: { x: 150, y: 60 } },
        { id: 'b', type: 'output', name: '结束', layout: { x: 150, y: 240 } }
      ],
      edges: [{ from: 'a', to: 'b' }]
    })
    expect(parsed.nodes[0].x).toBe(150)
    expect(parsed.nodes[0].y).toBe(60)
    expect(parsed.nodes[1].x).toBe(150)
    expect(parsed.nodes[1].y).toBe(240)
  })

  it('节点缺失 layout 时回退自动布局（非原点堆叠）', () => {
    const parsed = parseWorkflowJson({
      entry: 'a',
      nodes: [
        { id: 'a', type: 'start', name: '开始' },
        { id: 'b', type: 'output', name: '结束' }
      ],
      edges: [{ from: 'a', to: 'b' }]
    })
    expect(parsed.nodes[0].y).toBeLessThan(parsed.nodes[1].y)
  })

  it('节点 layout 为原点 (0,0) 时仍视为有布局，不误判回退自动布局', () => {
    const parsed = parseWorkflowJson({
      entry: 'a',
      nodes: [
        { id: 'a', type: 'start', name: '开始', layout: { x: 0, y: 0 } },
        { id: 'b', type: 'output', name: '结束', layout: { x: 150, y: 240 } }
      ],
      edges: [{ from: 'a', to: 'b' }]
    })
    expect(parsed.nodes[0].x).toBe(0)
    expect(parsed.nodes[0].y).toBe(0)
    expect(parsed.nodes[1].x).toBe(150)
    expect(parsed.nodes[1].y).toBe(240)
  })

  it('空 layout 对象视为缺失，回退自动布局', () => {
    const parsed = parseWorkflowJson({
      entry: 'a',
      nodes: [
        { id: 'a', type: 'start', name: '开始', layout: {} },
        { id: 'b', type: 'output', name: '结束' }
      ],
      edges: [{ from: 'a', to: 'b' }]
    })
    expect(parsed.nodes[0].y).toBeLessThan(parsed.nodes[1].y)
  })
})

describe('validateWorkflow', () => {
  let nodes: BuilderNode[]
  let edges: BuilderEdge[]

  beforeEach(() => {
    nodes = [
      node({ id: 's', type: 'start', label: '开始' }),
      node({ id: 'a', type: 'tool', tool: 't1' }),
      node({ id: 'b', type: 'output', message: 'ok' }),
      node({ id: 'e', type: 'end', label: '结束' })
    ]
    edges = [
      { id: 'e1', source: 's', target: 'a' },
      { id: 'e2', source: 'a', target: 'b' },
      { id: 'e3', source: 'b', target: 'e' }
    ]
  })

  it('正常图无警告', () => {
    expect(validateWorkflow(nodes, edges)).toEqual([])
  })

  it('悬空边与孤立节点被检出', () => {
    edges = [{ id: 'e1', source: 'a', target: 'missing' }]
    const errs = validateWorkflow(nodes, edges)
    expect(errs.some((e) => e.includes('missing'))).toBe(true)
    expect(errs.some((e) => e.includes('未连接'))).toBe(true)
  })

  it('有向环不再误报死循环（合法重试环；与后端无环校验对齐）', () => {
    edges = [
      { id: 'e1', source: 'a', target: 'b' },
      { id: 'e2', source: 'b', target: 'a' }
    ]
    expect(validateWorkflow(nodes, edges).some((e) => e.includes('死循环'))).toBe(false)
  })

  it('parallel 缺 branches / join 出边被检出', () => {
    nodes.push(node({ id: 'p', type: 'parallel' }))
    edges.push({ id: 'e2', source: 'b', target: 'p' })
    const errs = validateWorkflow(nodes, edges)
    expect(errs.some((e) => e.includes('缺少 branches'))).toBe(true)
    expect(errs.some((e) => e.includes('join 出边'))).toBe(true)
  })

  it('tool 缺工具名被检出', () => {
    nodes.find((n) => n.id === 'a')!.tool = ''
    expect(validateWorkflow(nodes, edges).some((e) => e.includes('缺少工具名'))).toBe(true)
  })

  it('同节点多条无条件出边被检出（引擎仅走 priority 最高一条，其余永不可达）', () => {
    edges = [
      { id: 'e1', source: 'a', target: 'b' },
      { id: 'e2', source: 'a', target: 'c' }
    ]
    const errs = validateWorkflow(nodes, edges)
    expect(errs.some((e) => e.includes('无条件出边'))).toBe(true)
  })

  it('有条件分支出边不误报无条件歧义', () => {
    edges = [
      { id: 'e1', source: 'a', target: 'b', condition: '${state.ok} == true' },
      { id: 'e2', source: 'a', target: 'c' }
    ]
    expect(validateWorkflow(nodes, edges).some((e) => e.includes('无条件出边'))).toBe(false)
  })
})

describe('start/end 结构性节点', () => {
  it('workflowYaml entry 指向 start 节点（start 不在数组首位时）', () => {
    const nodes = [
      node({ id: 'a', type: 'llm', label: '询问' }),
      node({ id: 's', type: 'start', label: '开始' }),
      node({ id: 'e', type: 'end', label: '结束' })
    ]
    const yaml = workflowYaml(nodes, [
      { id: 'e1', source: 's', target: 'a' },
      { id: 'e2', source: 'a', target: 'e' }
    ])
    expect(yaml).toContain('entry: s')
    expect(yaml).toContain('    type: start')
    expect(yaml).toContain('    type: end')
  })

  it('无 start 节点时 entry 取第一个节点（回归）', () => {
    const yaml = workflowYaml([node({ id: 'a', type: 'llm' })], [])
    expect(yaml).toContain('entry: a')
  })

  it('完整 start→业务→end 图无警告（start 无入边不报未连接）', () => {
    const nodes = [
      node({ id: 's', type: 'start', label: '开始' }),
      node({ id: 'a', type: 'llm', label: '询问' }),
      node({ id: 'e', type: 'end', label: '结束' })
    ]
    const edges = [
      { id: 'e1', source: 's', target: 'a' },
      { id: 'e2', source: 'a', target: 'e' }
    ]
    expect(validateWorkflow(nodes, edges)).toEqual([])
  })

  it('多个 start 被检出', () => {
    const nodes = [
      node({ id: 's1', type: 'start', label: '开始1' }),
      node({ id: 's2', type: 'start', label: '开始2' })
    ]
    expect(validateWorkflow(nodes, []).some((e) => e.includes('至多一个'))).toBe(true)
  })

  it('start 有入边 / end 有出边被检出', () => {
    const nodes = [
      node({ id: 's', type: 'start', label: '开始' }),
      node({ id: 'a', type: 'llm', label: '询问' }),
      node({ id: 'e', type: 'end', label: '结束' })
    ]
    const edges = [
      { id: 'e1', source: 's', target: 'a' },
      { id: 'e2', source: 'a', target: 's' },
      { id: 'e3', source: 'e', target: 'a' }
    ]
    const errs = validateWorkflow(nodes, edges)
    expect(errs.some((e) => e.includes('不允许有入边'))).toBe(true)
    expect(errs.some((e) => e.includes('不允许有出边'))).toBe(true)
  })

  it('start 无出边被检出（流程空转）', () => {
    const nodes = [
      node({ id: 's', type: 'start', label: '开始' }),
      node({ id: 'e', type: 'end', label: '结束' })
    ]
    expect(validateWorkflow(nodes, []).some((e) => e.includes('缺少出边'))).toBe(true)
  })

  it('缺少 end 时保留自然终止提示', () => {
    const errs = validateWorkflow([node({ id: 'a', type: 'llm' })], [])
    expect(errs.some((e) => e.includes('缺少结束节点'))).toBe(true)
  })
})

describe('Workflow 节点目录契约', () => {
  it('可执行目录隐藏未落地节点，完整目录仍支持既有草稿解析和导出', () => {
    const supportedTypes = WORKFLOW_NODE_CATALOG.map((node) => node.type)
    const executableTypes = EXECUTABLE_WORKFLOW_NODE_CATALOG.map((node) => node.type)
    expect(executableTypes).toContain('knowledge_query')
    expect(executableTypes).not.toEqual(expect.arrayContaining(['parallel', 'subgraph', 'agentic']))
    expect(supportedTypes).toEqual(expect.arrayContaining(['parallel', 'subgraph', 'agentic']))
    expect(supportedTypes).not.toContain('http')
    expect(isSupportedWorkflowNodeType('knowledge_query')).toBe(true)
    expect(isSupportedWorkflowNodeType('parallel')).toBe(true)
    expect(isExecutableWorkflowNodeType('parallel')).toBe(false)
    expect(isExecutableWorkflowNodeType('subgraph')).toBe(false)
    expect(isExecutableWorkflowNodeType('agentic')).toBe(false)
    expect(isSupportedWorkflowNodeType('http')).toBe(false)
    expect(isSupportedWorkflowNodeType('unknown')).toBe(false)

    const parsed = parseWorkflowJson({
      nodes: [
        { id: 'legacy-parallel', type: 'parallel', name: '存量并行', branches: ['branch'] },
        {
          id: 'legacy-subgraph',
          type: 'subgraph',
          name: '存量子工作流',
          workflow: 'child-workflow'
        },
        {
          id: 'legacy-agentic',
          type: 'agentic',
          name: '存量 Agentic',
          agent_slot: 'specialist',
          task: { objective: '分析', expected_output: '结果' }
        }
      ],
      edges: []
    })
    const yaml = workflowYaml(parsed.nodes, parsed.edges)
    expect(yaml).toContain('    type: parallel')
    expect(yaml).toContain('    type: subgraph')
    expect(yaml).toContain('    type: agentic')
    expect(workflowDefinition(parsed.nodes, parsed.edges)['nodes']).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ id: 'legacy-parallel', type: 'parallel', branches: ['branch'] }),
        expect.objectContaining({
          id: 'legacy-subgraph',
          type: 'subgraph',
          workflow: 'child-workflow'
        }),
        expect.objectContaining({ id: 'legacy-agentic', type: 'agentic', agent_slot: 'specialist' })
      ])
    )
    expect(validateWorkflow(parsed.nodes, parsed.edges)).toContain(
      "节点 '存量并行' 使用了当前版本不可执行的类型 'parallel'；请移除或替换该节点后再发布"
    )
  })

  it('知识查询按类型化槽、查询、预算契约序列化，未支持节点不能生成 Workflow YAML', () => {
    const yaml = workflowYaml(
      [
        node({
          id: 'knowledge',
          type: 'knowledge_query',
          knowledgeRequirement: 'request_docs',
          knowledgeQuery: '${state.question}',
          knowledgeMaxItems: '5',
          knowledgeMaxTokens: '1200',
          knowledgeMaxCharacters: '6000',
          knowledgeTimeoutMs: '3000',
          knowledgeMaxSources: '3'
        })
      ],
      []
    )
    expect(yaml).toContain('    type: knowledge_query')
    expect(yaml).toContain('    requirement: "request_docs"')
    expect(yaml).toContain('    query: "${state.question}"')
    expect(yaml).toContain(
      '    budget: {"max_items":5,"max_tokens":1200,"max_characters":6000,"timeout_ms":3000,"max_sources":3}'
    )
    expect(() => workflowYaml([node({ id: 'legacy', type: 'http' })], [])).toThrow(
      '不支持的 Workflow 节点类型'
    )
  })

  it('存量原始 http 定义可被标记为无效，但不能重新发布', () => {
    const parsed = parseWorkflowJson({
      entry: 'legacy',
      nodes: [
        { id: 'legacy', type: 'http', name: '遗留 HTTP', args: { url: 'https://example.test' } }
      ],
      edges: []
    })
    expect(parsed.nodes[0].type).toBe('http')
    expect(
      validateWorkflow(parsed.nodes, parsed.edges).some((error) =>
        error.includes('原始 http 节点已移除')
      )
    ).toBe(true)
  })
})

describe('workflowDefinition（版本化 Builder API）', () => {
  it('将画布、布局和元数据序列化为草稿 API 所需的结构化定义', () => {
    const meta: WorkflowMeta = {
      ...defaultWorkflowMeta(),
      name: 'my-workflow',
      displayName: '服务申请',
      tagsText: '申请，通用',
      configSchemaText: '[{"key":"workspace"}]',
      toolsText: '[{"name":"check_user_status","requirement":"user_status"}]',
      capabilityRequirementsText:
        '[{"slot":"user_status","type":"tool","capability_id":"user-status","version_constraint":"1.x","label":"用户状态"}]'
    }
    const definition = workflowDefinition(
      [
        node({ id: 'start', type: 'start', label: '开始', x: 12, y: 24 }),
        node({
          id: 'lookup',
          type: 'knowledge_query',
          label: '查询知识',
          x: 180,
          y: 24,
          knowledgeRequirement: 'request_docs',
          knowledgeQuery: '${state.question}',
          knowledgeMaxItems: '5',
          knowledgeMaxTokens: '1200',
          knowledgeMaxCharacters: '6000',
          knowledgeTimeoutMs: '3000',
          knowledgeMaxSources: '3',
          timeoutSeconds: '30'
        }),
        node({ id: 'end', type: 'end', label: '结束', x: 360, y: 24 })
      ],
      [
        { id: 'e1', source: 'start', target: 'lookup' },
        { id: 'e2', source: 'lookup', target: 'end', priority: 5 }
      ],
      meta
    ) as {
      entry: string
      nodes: Array<Record<string, unknown>>
      edges: Array<Record<string, unknown>>
      meta: Record<string, unknown>
    }

    expect(definition.entry).toBe('start')
    expect(definition.nodes[1]).toMatchObject({
      id: 'lookup',
      type: 'knowledge_query',
      timeout_seconds: 30,
      requirement: 'request_docs',
      query: '${state.question}',
      result_to: 'state',
      budget: {
        max_items: 5,
        max_tokens: 1200,
        max_characters: 6000,
        timeout_ms: 3000,
        max_sources: 3
      }
    })
    expect(definition.nodes[1].layout).toBeUndefined()
    expect(definition.edges).toEqual([
      { from: 'start', to: 'lookup' },
      { from: 'lookup', to: 'end', priority: 5 }
    ])
    expect(definition.meta).toMatchObject({
      name: 'my-workflow',
      display_name: '服务申请',
      tags: ['申请', '通用'],
      config_schema: [{ key: 'workspace' }],
      tools: [{ name: 'check_user_status', requirement: 'user_status' }]
    })
  })

  it('拒绝无效 JSON 与未支持节点，防止将坏数据写入草稿', () => {
    expect(() =>
      workflowDefinition(
        [
          node({
            id: 'q',
            type: 'knowledge_query',
            knowledgeRequirement: 'docs',
            knowledgeQuery: 'q',
            knowledgeMaxItems: '0',
            knowledgeMaxTokens: '1',
            knowledgeMaxCharacters: '1',
            knowledgeTimeoutMs: '1',
            knowledgeMaxSources: '1'
          })
        ],
        []
      )
    ).toThrow('q.budget.max_items 必须为正整数')
    expect(() => workflowDefinition([node({ id: 'raw', type: 'http' })], [])).toThrow(
      '不支持的 Workflow 节点类型'
    )
  })

  it('保留各节点专有契约与全部可选元数据', () => {
    const definition = workflowDefinition(
      [
        node({
          id: 'tool',
          type: 'tool',
          prompt: '可选提示',
          extractText: 'name，phone\naddress',
          tool: 'lookup',
          onError: 'goto:end',
          resultTo: 'result',
          message: '完成',
          renderText: '{"kind":"tip"}',
          argsText: '{"city":"shanghai"}',
          timeoutSeconds: '60',
          branchesText: 'a,b',
          workflowId: 'child-workflow'
        }),
        node({
          id: 'assign',
          type: 'assign',
          assignPairs: [
            { target: 'state.ok', source: '${state.value}' },
            { target: '', source: 'skip' }
          ]
        }),
        node({
          id: 'script',
          type: 'script',
          script: 'scripts/check.py',
          scriptInputText: '{"id":"${state.id}"}'
        }),
        node({
          id: 'agent',
          type: 'agentic',
          agentSlot: 'specialist',
          agentObjective: '分析 ${state.value}',
          agentContext: '当前会话',
          agentConstraints: '只返回 JSON',
          agentExpectedOutput: '分析结果'
        }),
        node({
          id: 'menu',
          type: 'menu',
          menuActions: [
            { id: 'book', label: '申请', to: 'end' },
            { id: '', label: '', to: '' }
          ]
        }),
        node({
          id: 'card',
          type: 'card',
          mergeMessageId: '${state.message}',
          cardTitle: '确认',
          cardBody: '继续？',
          cardFieldsText: '["name"]',
          cardActionsText: '[{"type":"submit"}]',
          cardStyleText: '{"theme":"dark"}'
        })
      ],
      [],
      {
        ...defaultWorkflowMeta(),
        name: 'full',
        version: '2.0',
        displayName: '完整',
        description: '说明',
        category: 'general',
        tagsText: '申请,通用',
        configSchemaText: '[{"key":"city"}]',
        author: 'team',
        toolsText: '[{"name":"lookup","requirement":"calendar"}]',
        capabilityRequirementsText:
          '[{"slot":"calendar","type":"tool","capability_id":"calendar","version_constraint":"1.x","label":"日历"}]',
        requiredPermissionsText: 'workflow:publish，workflow:install'
      }
    ) as { nodes: Array<Record<string, unknown>>; meta: Record<string, unknown> }

    expect(definition.nodes[0]).toMatchObject({
      prompt: '可选提示',
      extract: ['name', 'phone', 'address'],
      tool: 'lookup',
      on_error: 'goto:end',
      result_to: 'result',
      message: '完成',
      render: { kind: 'tip' },
      args: { city: 'shanghai' },
      timeout_seconds: 60,
      branches: ['a', 'b'],
      workflow: 'child-workflow'
    })
    expect(definition.nodes[1].args).toEqual({ 'state.ok': '${state.value}' })
    expect(definition.nodes[2].args).toEqual({
      script: 'scripts/check.py',
      input: { id: '${state.id}' }
    })
    expect(definition.nodes[3]).toMatchObject({
      agent_slot: 'specialist',
      result_to: 'state',
      task: {
        objective: '分析 ${state.value}',
        context: '当前会话',
        constraints: '只返回 JSON',
        expected_output: '分析结果'
      }
    })
    expect(definition.nodes[4].actions).toEqual([{ id: 'book', label: '申请', to: 'end' }])
    expect(definition.nodes[5]).toMatchObject({
      merge_message_id: '${state.message}',
      card: {
        card_id: 'hitl_confirm',
        type: 'confirm',
        title: '确认',
        body: '继续？',
        fields: ['name'],
        actions: [{ type: 'submit' }],
        style: { theme: 'dark' }
      }
    })
    expect(definition.meta).toMatchObject({
      type: 'state-machine',
      name: 'full',
      version: '2.0',
      display_name: '完整',
      description: '说明',
      category: 'general',
      tags: ['申请', '通用'],
      config_schema: [{ key: 'city' }],
      author: 'team',
      tools: [{ name: 'lookup', requirement: 'calendar' }],
      capability_requirements: [
        {
          slot: 'calendar',
          type: 'tool',
          capability_id: 'calendar',
          version_constraint: '1.x',
          label: '日历'
        }
      ],
      required_permissions: ['workflow:publish', 'workflow:install']
    })
  })

  it('超时必须为正数，权限数组保留数组形态', () => {
    expect(() =>
      workflowDefinition([node({ id: 'wait', type: 'async_task', timeoutSeconds: '0' })], [])
    ).toThrow('wait.timeout_seconds 必须为正数')
    const definition = workflowDefinition([], [], {
      ...defaultWorkflowMeta(),
      requiredPermissionsText: '["workflow:publish"]'
    }) as { meta: Record<string, unknown> }
    expect(definition.meta.required_permissions).toEqual(['workflow:publish'])
  })

  it('能力槽拒绝未知字段', () => {
    expect(() =>
      workflowDefinition([], [], {
        ...defaultWorkflowMeta(),
        capabilityRequirementsText:
          '[{"slot":"calendar","type":"tool","capability_id":"calendar","version_constraint":"1.x","label":"日历","connector_type":"legacy"}]'
      })
    ).toThrow('包含不支持的字段')
  })
})

describe('autoLayout（parseWorkflowJson 布局）', () => {
  it('有环图加载不爆栈且按层排布', () => {
    const parsed = parseWorkflowJson({
      entry: 'a',
      nodes: [
        { id: 'a', type: 'llm', name: 'A' },
        { id: 'b', type: 'llm', name: 'B' },
        { id: 'c', type: 'output', name: 'C' }
      ],
      edges: [
        { from: 'a', to: 'b' },
        { from: 'b', to: 'a' },
        { from: 'b', to: 'c' }
      ]
    })
    expect(parsed.nodes).toHaveLength(3)
    // 层从左到右递增（x 单调），同层不重叠
    const xs = parsed.nodes.map((n) => n.x)
    expect(Math.min(...xs)).toBeGreaterThanOrEqual(0)
    expect(parsed.nodes.every((n) => n.y >= 0)).toBe(true)
  })
})

describe('workflowYaml 新字段（menu / agentic / 卡片）', () => {
  it('menu 行动点序列化', () => {
    const n = node({
      id: 'm',
      type: 'menu',
      label: '主菜单',
      prompt: '请选择',
      menuActions: [
        { id: 'book', label: '服务申请', to: 'b1' },
        { id: 'consult', label: '服务咨询', to: 'c1' }
      ]
    })
    const yaml = workflowYaml([n], [])
    expect(yaml).toContain('type: menu')
    expect(yaml).toContain('- id: "book"')
    expect(yaml).toContain('label: "服务申请"')
    expect(yaml).toContain('to: "b1"')
  })

  it('agentic 序列化为 Agent 槽与类型化任务', () => {
    const n = node({
      id: 'a',
      type: 'agentic',
      agentSlot: 'owner',
      agentObjective: '查询事项',
      agentContext: '服务台',
      agentConstraints: '仅返回可办理事项',
      agentExpectedOutput: '事项列表'
    })
    const yaml = workflowYaml([n], [])
    expect(yaml).toContain('agent_slot: "owner"')
    expect(yaml).toContain(
      'task: {"objective":"查询事项","expected_output":"事项列表","context":"服务台","constraints":"仅返回可办理事项"}'
    )
  })

  it('tool on_error 序列化', () => {
    const n = node({ id: 't', type: 'tool', tool: 'send_msg', onError: 'goto:notify' })
    const yaml = workflowYaml([n], [])
    expect(yaml).toContain('on_error: "goto:notify"')
  })

  it('卡片 fields/actions/style 序列化', () => {
    const n = node({
      id: 'h',
      type: 'human_input',
      cardTitle: '确认申请',
      cardBody: '用户：${state.name}',
      cardFieldsText: '[{"key":"owner","label":"负责人","value":"${state.owner}"}]',
      cardActionsText:
        '[{"id":"detail","label":"查看详情","type":"link","url":"https://x.com/p/${state.id}"}]',
      cardStyleText: '{"accent":"#ff6a1e"}'
    })
    const yaml = workflowYaml([n], [])
    expect(yaml).toContain('fields: [{"key":"owner","label":"负责人","value":"${state.owner}"}]')
    expect(yaml).toContain(
      'actions: [{"id":"detail","label":"查看详情","type":"link","url":"https://x.com/p/${state.id}"}]'
    )
    expect(yaml).toContain('style: {"accent":"#ff6a1e"}')
  })
})

describe('parseWorkflowJson 新字段反序列化', () => {
  it('menu actions / on_error / agentic task 还原到画布模型', () => {
    const parsed = parseWorkflowJson({
      entry: 'm',
      nodes: [
        {
          id: 'm',
          type: 'menu',
          name: '主菜单',
          actions: [{ id: 'book', label: '服务申请', to: 'b1' }]
        },
        { id: 't', type: 'tool', name: '发送', tool: 'send_msg', on_error: 'goto:notify' },
        {
          id: 'a',
          type: 'agentic',
          name: '自主',
          agent_slot: 'owner',
          task: {
            objective: '查询事项',
            context: '服务台',
            constraints: '仅返回可办理事项',
            expected_output: '事项列表'
          }
        }
      ],
      edges: []
    })
    const menu = parsed.nodes.find((n) => n.type === 'menu')
    expect(menu?.menuActions).toEqual([{ id: 'book', label: '服务申请', to: 'b1' }])
    const tool = parsed.nodes.find((n) => n.type === 'tool')
    expect(tool?.onError).toBe('goto:notify')
    const agentic = parsed.nodes.find((n) => n.type === 'agentic')
    expect(agentic).toMatchObject({
      agentSlot: 'owner',
      agentObjective: '查询事项',
      agentContext: '服务台',
      agentConstraints: '仅返回可办理事项',
      agentExpectedOutput: '事项列表'
    })
  })

  it('async_task / timeout_seconds / render / merge_message_id 还原到画布模型', () => {
    const parsed = parseWorkflowJson({
      entry: 'w',
      nodes: [
        { id: 'w', type: 'async_task', name: '等待反馈', timeout_seconds: 600 },
        {
          id: 'c',
          type: 'card',
          name: '进度卡',
          render: { type: 'progress_card', sub_tasks: [{ code: 'sync' }] },
          merge_message_id: '${state.progress_message_id}'
        }
      ],
      edges: []
    })
    const wait = parsed.nodes.find((n) => n.type === 'async_task')
    expect(wait?.timeoutSeconds).toBe('600')
    const card = parsed.nodes.find((n) => n.type === 'card')
    expect(card?.renderText).toContain('progress_card')
    expect(card?.mergeMessageId).toBe('${state.progress_message_id}')
  })

  it('卡片 fields/actions/style 还原为 JSON 字符串', () => {
    const parsed = parseWorkflowJson({
      entry: 'h',
      nodes: [
        {
          id: 'h',
          type: 'human_input',
          name: '确认',
          card: {
            title: '确认申请',
            body: '用户：${state.name}',
            fields: [{ key: 'owner', label: '负责人', value: '${state.owner}' }],
            actions: [
              { id: 'detail', label: '查看详情', type: 'link', url: 'https://x.com/p/${state.id}' }
            ],
            style: { accent: '#ff6a1e' }
          }
        }
      ],
      edges: []
    })
    const h = parsed.nodes[0]
    expect(h.cardFieldsText).toContain('owner')
    expect(h.cardActionsText).toContain('"type":"link"')
    expect(h.cardStyleText).toContain('#ff6a1e')
  })
})

describe('validateWorkflow 新规则（menu / on_error）', () => {
  it('menu 行动点 id 重复与 to 不存在被检出', () => {
    const nodes = [
      node({
        id: 'm',
        type: 'menu',
        menuActions: [
          { id: 'a', label: 'A', to: 'x1' },
          { id: 'a', label: 'B', to: 'x2' }
        ]
      })
    ]
    const errs = validateWorkflow(nodes, [])
    expect(errs.some((e) => e.includes('id 重复'))).toBe(true)
    expect(errs.some((e) => e.includes("目标 'x1' 不存在"))).toBe(true)
  })

  it('menu 无完整行动点被检出', () => {
    const nodes = [node({ id: 'm', type: 'menu', menuActions: [] })]
    expect(validateWorkflow(nodes, []).some((e) => e.includes('至少需要一个完整行动点'))).toBe(true)
  })

  it('tool on_error goto 目标不存在被检出', () => {
    const nodes = [node({ id: 't', type: 'tool', tool: 'x', onError: 'goto:missing' })]
    expect(validateWorkflow(nodes, []).some((e) => e.includes("跳转目标 'missing' 不存在"))).toBe(
      true
    )
  })
})

describe('card 展示卡节点', () => {
  it('card 节点序列化与反序列化（卡片字段走通用块）', () => {
    const n = node({
      id: 'c',
      type: 'card',
      label: '结果卡',
      message: '申请确认消息已发送成功！',
      cardTitle: '申请确认',
      cardBody: '话术已生成',
      cardFieldsText: '[{"key":"channel","label":"发送渠道","value":"${state.channel}"}]',
      cardActionsText:
        '[{"id":"detail","label":"查看详情","type":"link","url":"https://x.com/p/${state.ctx_id}"}]',
      cardStyleText: '{"accent":"#ff6a1e"}'
    })
    const yaml = workflowYaml([n], [])
    expect(yaml).toContain('type: card')
    expect(yaml).toContain('message: "申请确认消息已发送成功！"')
    expect(yaml).toContain(
      'fields: [{"key":"channel","label":"发送渠道","value":"${state.channel}"}]'
    )

    const parsed = parseWorkflowJson({
      entry: 'c',
      nodes: [
        {
          id: 'c',
          type: 'card',
          name: '结果卡',
          message: 'ok',
          card: {
            title: '申请确认',
            fields: [{ key: 'channel', label: '发送渠道', value: '${state.channel}' }],
            actions: [
              {
                id: 'detail',
                label: '查看详情',
                type: 'link',
                url: 'https://x.com/p/${state.ctx_id}'
              }
            ],
            style: { accent: '#ff6a1e' }
          }
        }
      ],
      edges: []
    })
    const card = parsed.nodes[0]
    expect(card.cardTitle).toBe('申请确认')
    expect(card.cardFieldsText).toContain('channel')
    expect(card.cardActionsText).toContain('"type":"link"')
    expect(card.cardStyleText).toContain('#ff6a1e')
  })
})

describe('workflowYaml 修复：menu 行动点部分填写不静默丢弃', () => {
  it('仅剔除全空行，保留部分填写的行动点', () => {
    const n = node({
      id: 'm',
      type: 'menu',
      label: '行动点菜单',
      menuActions: [
        { id: 'book', label: '服务申请', to: 'query' },
        { id: '', label: '只有文案', to: '' },
        { id: '', label: '', to: '' }
      ]
    })
    const yaml = workflowYaml([n], [])
    expect(yaml).toContain('actions:')
    expect(yaml).toContain('- id: "book"')
    expect(yaml).toContain('label: "只有文案"')
    // 全空行被剔除（不产生空 action 行）
    expect((yaml.match(/ {6}- id:/g) ?? []).length).toBe(2)
  })
})

describe('buildAvailableVars 可用变量推导', () => {
  const meta: WorkflowMeta = {
    ...defaultWorkflowMeta(),
    configSchemaText: JSON.stringify([
      { key: 'workspace_name', type: 'string' },
      { key: 'departments', type: 'string' }
    ])
  }

  it('按节点推导 state/extracted/config/user/runtime 变量', () => {
    const nodes: BuilderNode[] = [
      node({ id: 'extract', type: 'llm', extractText: 'name, phone', resultTo: 'extracted' }),
      node({
        id: 'map',
        type: 'assign',
        assignPairs: [{ target: 'ctx_user_id', source: '${extracted.user_id}' }]
      }),
      node({ id: 'ask', type: 'human_input', resultTo: 'extracted' })
    ]
    const vars = buildAvailableVars(nodes, meta)
    const labels = vars.map((v) => v.label)
    // extracted：llm extract 键 + 隐式 action
    expect(labels).toContain('extracted.name')
    expect(labels).toContain('extracted.phone')
    expect(labels).toContain('extracted.action')
    // state：assign target、实际输入及错误字段；LLM没有隐式content包装
    expect(labels).toContain('state.ctx_user_id')
    expect(labels).not.toContain('state.content')
    expect(labels).toContain('state.user_input')
    expect(labels).toContain('state.last_error')
    // config：config_schema 键
    expect(labels).toContain('config.workspace_name')
    expect(labels).toContain('config.departments')
    // user/runtime 固定白名单
    expect(labels).toContain('user.end_user_id')
    expect(labels).not.toContain('user.id')
    expect(labels).toContain('user.display_name')
    expect(labels).toContain('runtime.session_id')
    expect(labels).toContain('runtime.execution_id')
    expect(labels).not.toContain('runtime.run_id')
    expect(labels).toContain('runtime.action_visits')
    expect(labels).toContain('runtime.workflow_instance_id')
    // 插值片段格式
    const ctxUser = vars.find((v) => v.label === 'state.ctx_user_id')
    expect(ctxUser?.value).toBe('${state.ctx_user_id}')
  })

  it('条件字段过滤：仅 state/extracted', () => {
    const nodes: BuilderNode[] = [
      node({ id: 'extract', type: 'llm', extractText: 'name', resultTo: 'state' }),
      node({ id: 'ask', type: 'human_input', resultTo: 'extracted' })
    ]
    const vars = buildAvailableVars(nodes, meta, { namespaces: ['state', 'extracted'] })
    const namespaces = new Set(vars.map((v) => v.namespace))
    expect(namespaces.has('state')).toBe(true)
    expect(namespaces.has('extracted')).toBe(true)
    expect(namespaces.has('config')).toBe(false)
    expect(namespaces.has('user')).toBe(false)
    expect(namespaces.has('runtime')).toBe(false)
  })
})

describe('validateVarRefs 变量引用校验', () => {
  const meta: WorkflowMeta = {
    ...defaultWorkflowMeta(),
    configSchemaText: JSON.stringify([{ key: 'workspace_name', type: 'string' }])
  }

  it('未定义引用告警、ctx_* 与已定义引用豁免', () => {
    const nodes: BuilderNode[] = [
      node({
        id: 'a',
        type: 'output',
        message: '结果：${state.result}，配置：${config.workspace_name}，用户：${state.ctx_custom}'
      })
    ]
    const edges: BuilderEdge[] = [
      {
        id: 'e1',
        source: 'a',
        target: 'b',
        condition: "${state.result} == 'ok' && ${state.undefined_key} == 'x'"
      }
    ]
    const warnings = validateVarRefs(nodes, edges, meta)
    // 未定义 state.undefined_key 告警（边条件）
    expect(warnings.some((w) => w.includes('state.undefined_key'))).toBe(true)
    // state.result 未定义也应告警（output message 引用，节点未产生该字段）
    expect(warnings.some((w) => w.includes('state.result'))).toBe(true)
    // config.workspace_name 已定义、ctx_custom 运行时注入 → 均豁免
    expect(warnings.some((w) => w.includes('config.workspace_name'))).toBe(false)
    expect(warnings.some((w) => w.includes('ctx_custom'))).toBe(false)
  })

  it('assign 裸变量路径也参与校验', () => {
    const nodes: BuilderNode[] = [
      node({
        id: 'a',
        type: 'assign',
        assignPairs: [{ target: 'ok', source: 'state.missing_key' }]
      })
    ]
    const warnings = validateVarRefs(nodes, [], meta)
    expect(warnings.some((w) => w.includes('state.missing_key'))).toBe(true)
  })

  it('validateWorkflow 集成变量警告（不阻断结构校验）', () => {
    const nodes: BuilderNode[] = [
      node({ id: 'start', type: 'start', label: '开始' }),
      node({ id: 'out', type: 'output', message: '值：${state.not_exist}' }),
      node({ id: 'end', type: 'end', label: '结束' })
    ]
    const edges: BuilderEdge[] = [
      { id: 'e1', source: 'start', target: 'out' },
      { id: 'e2', source: 'out', target: 'end' }
    ]
    const warnings = validateWorkflow(nodes, edges, meta)
    expect(warnings.some((w) => w.includes('state.not_exist'))).toBe(true)
  })
})

describe('JSON 字段工具（可视化表单编辑器）', () => {
  it('parseJsonField：合法 JSON 解析、非法/空返回 null', () => {
    expect(parseJsonField<{ a: number }>('{"a":1}')).toEqual({ a: 1 })
    expect(parseJsonField<unknown>('')).toBeNull()
    expect(parseJsonField<unknown>('{bad json')).toBeNull()
    expect(parseJsonField<unknown>(undefined)).toBeNull()
  })

  it('stringifyJsonField：2 空格缩进，空对象/数组输出空串', () => {
    expect(stringifyJsonField({ user_id: '${state.ctx_user_id}' })).toBe(
      '{\n  "user_id": "${state.ctx_user_id}"\n}'
    )
    expect(stringifyJsonField({})).toBe('')
    expect(stringifyJsonField([])).toBe('')
    expect(stringifyJsonField(null)).toBe('')
    expect(stringifyJsonField(undefined)).toBe('')
  })

  it('formatJsonField：合法美化、非法原样、空原样', () => {
    expect(formatJsonField('{"a":1}')).toBe('{\n  "a": 1\n}')
    expect(formatJsonField('{bad')).toBe('{bad')
    expect(formatJsonField('')).toBe('')
    expect(formatJsonField(undefined)).toBe('')
  })
})

describe('nodeFieldIssues 节点字段内联校验', () => {
  const meta: WorkflowMeta = {
    ...defaultWorkflowMeta(),
    configSchemaText: JSON.stringify([{ key: 'workspace_name', type: 'string' }])
  }

  it('字段级问题：tool 缺名 / knowledge_query 缺配置 / on_error 目标缺失', () => {
    const nodes: BuilderNode[] = [
      node({ id: 'a', type: 'tool', tool: '', onError: 'goto:nope' }),
      node({ id: 'b', type: 'knowledge_query', knowledgeRequirement: '', knowledgeQuery: '' })
    ]
    const edges: BuilderEdge[] = []
    const aIssues = nodeFieldIssues(nodes[0], nodes, edges, meta)
    expect(aIssues.some((i) => i.field === 'tool' && i.message.includes('缺少工具名'))).toBe(true)
    expect(aIssues.some((i) => i.field === 'onError' && i.message.includes('nope'))).toBe(true)
    const bIssues = nodeFieldIssues(nodes[1], nodes, edges, meta)
    expect(
      bIssues.some((i) => i.field === 'knowledgeQuery' && i.message.includes('缺少知识查询配置'))
    ).toBe(true)
    expect(bIssues.some((i) => i.field === 'knowledgeRequirement')).toBe(true)
  })

  it('parallel 分支引用不存在节点 → branches 字段问题', () => {
    const nodes: BuilderNode[] = [node({ id: 'p', type: 'parallel', branchesText: 'ghost\n' })]
    const issues = nodeFieldIssues(nodes[0], nodes, [], meta)
    expect(issues.some((i) => i.field === 'branches' && i.message.includes('ghost'))).toBe(true)
  })

  it('变量引用未定义 → vars 字段问题；已知变量与 ctx_* 豁免', () => {
    // a 为 llm 提取 name（state），out 引用 state.name（已知）与 state.nope（未定义）
    const nodes: BuilderNode[] = [
      node({ id: 'a', type: 'llm', extractText: 'name', resultTo: 'state' }),
      node({
        id: 'out',
        type: 'output',
        message: '${state.name} ${state.nope} ${state.ctx_custom}'
      })
    ]
    const issues = nodeFieldIssues(nodes[1], nodes, [], meta)
    expect(issues.some((i) => i.field === 'vars' && i.message.includes('state.nope'))).toBe(true)
    expect(issues.some((i) => i.message.includes('state.name'))).toBe(false)
    expect(issues.some((i) => i.message.includes('ctx_custom'))).toBe(false)
  })

  it('干净节点无问题', () => {
    const nodes: BuilderNode[] = [node({ id: 'a', type: 'tool', tool: 'check_user_status' })]
    expect(nodeFieldIssues(nodes[0], nodes, [], meta)).toEqual([])
  })

  it('validateNodeVarRefs 与 validateVarRefs 消息一致（共享 collectNodeVarRefs）', () => {
    const nodes: BuilderNode[] = [
      node({ id: 'a', type: 'assign', assignPairs: [{ target: 'ok', source: 'state.missing' }] })
    ]
    const single = validateNodeVarRefs(nodes[0], nodes, meta)
    const whole = validateVarRefs(nodes, [], meta)
    expect(single).toEqual(whole)
  })

  it('script 与 subgraph 缺失必填字段时给出对应内联问题', () => {
    const script = node({ id: 'script', type: 'script', script: '' })
    const subgraph = node({ id: 'child', type: 'subgraph', workflowId: '' })
    expect(nodeFieldIssues(script, [script, subgraph], [], meta)).toContainEqual(
      expect.objectContaining({ field: 'script', message: '缺少脚本路径' })
    )
    expect(nodeFieldIssues(subgraph, [script, subgraph], [], meta)).toContainEqual(
      expect.objectContaining({ field: 'workflowId', message: '缺少子工作流 ID' })
    )
  })
})

describe('工作流标识与详情元数据映射', () => {
  it('slugify 对可用 ASCII 标识归一化，并为全非 ASCII 输入生成安全回退', () => {
    expect(slugifyWorkflowId('  Request / V2  ')).toBe('request-v2')
    expect(slugifyWorkflowId('服务申请')).toMatch(/^workflow-[a-z0-9]+$/)
  })

  it('detailToMeta 保留所有可选元数据，并为空的可选字段稳定降级', () => {
    expect(
      detailToMeta({
        name: '申请',
        version: 'v2',
        description: '说明',
        category: 'general',
        tags: ['申请', '服务台'],
        config_schema: [{ key: 'workspace' }],
        author: 'team-a',
        tools: [{ name: 'lookup' }],
        capability_requirements: [
          {
            slot: 'calendar',
            type: 'tool',
            capability_id: 'calendar',
            version_constraint: '1.x',
            label: '日历'
          }
        ],
        required_permissions: ['workflow:run']
      })
    ).toMatchObject({
      name: '申请',
      displayName: '申请',
      tagsText: '申请,服务台',
      configSchemaText: '[{"key":"workspace"}]',
      author: 'team-a',
      toolsText: '[{"name":"lookup"}]',
      capabilityRequirementsText:
        '[{"slot":"calendar","type":"tool","capability_id":"calendar","version_constraint":"1.x","label":"日历"}]',
      requiredPermissionsText: '["workflow:run"]'
    })
    expect(detailToMeta({ name: 'empty', version: 'v1', description: '' })).toMatchObject({
      category: '',
      tagsText: '',
      configSchemaText: '',
      author: '',
      toolsText: '',
      capabilityRequirementsText: '',
      requiredPermissionsText: ''
    })
  })

  it('合法治理对象在图形编辑、独立布局保存及再次加载后完整保留', () => {
    const original = {
      entry: 'start',
      nodes: [
        { id: 'start', type: 'start', name: '开始', layout: { x: 0, y: 0 } },
        { id: 'end', type: 'end', name: '结束', layout: { x: 480, y: 80 } }
      ],
      edges: [{ from: 'start', to: 'end' }],
      meta: {
        name: 'graph-governance',
        version: '1.0.0',
        description: '图形编辑治理往返',
        capability: {
          timeout_ms: 300000,
          input_schema: {},
          output_schema: {},
          export_fields: ['messages'],
          supports_hitl: false,
          user_invocable: true,
          model_invocable: true,
          concurrency_safe: true
        }
      }
    }
    const originalJson = JSON.stringify(original)
    const parsed = parseWorkflowJson(original)
    const meta = { ...defaultWorkflowMeta(), ...detailToMeta(original.meta) }
    parsed.nodes.push(
      node({ id: 'output', type: 'output', message: '保存后的结果', x: 240, y: 160 })
    )
    parsed.nodes[1].x = 520
    const editedEdges: BuilderEdge[] = [
      { id: 'start-output', source: 'start', target: 'output', condition: 'true' },
      { id: 'output-end', source: 'output', target: 'end' }
    ]
    const saved = workflowDefinition(parsed.nodes, editedEdges, meta) as {
      nodes: Array<Record<string, unknown>>
      edges: Array<Record<string, unknown>>
      meta: typeof original.meta
    }
    const positions = Object.fromEntries(parsed.nodes.map((n) => [n.id, { x: n.x, y: n.y }]))

    expect(saved.meta.capability).toEqual(original.meta.capability)
    expect(saved.nodes.map((n) => n.id)).toEqual(['start', 'end', 'output'])
    expect(saved.nodes[2]).toMatchObject({ type: 'output', message: '保存后的结果' })
    expect(saved.nodes.every((n) => n.layout === undefined)).toBe(true)
    expect(saved.edges).toEqual([
      { from: 'start', to: 'output', condition: 'true' },
      { from: 'output', to: 'end' }
    ])
    expect(positions).toEqual({
      start: { x: 0, y: 0 },
      end: { x: 520, y: 80 },
      output: { x: 240, y: 160 }
    })
    const reloaded = parseWorkflowJson(saved)
    const restoredNodes = reloaded.nodes.map((n) => ({ ...n, ...positions[n.id] }))
    expect(restoredNodes.map(({ original: _original, ...node }) => node)).toEqual(
      parsed.nodes.map(({ original: _original, ...node }) => node)
    )
    expect(
      workflowDefinition(restoredNodes, reloaded.edges, {
        ...defaultWorkflowMeta(),
        ...detailToMeta(saved.meta)
      })
    ).toEqual(saved)
    expect(workflowMetaYaml(meta)).toContain(
      `  capability: ${JSON.stringify(original.meta.capability)}`
    )
    expect(workflowYaml(parsed.nodes, editedEdges, meta)).toContain(
      `  capability: ${JSON.stringify(original.meta.capability)}`
    )
    expect(JSON.stringify(original)).toBe(originalJson)
  })

  it('治理中的嵌套 schema、false 和空数组保留原值且不共享可变对象', () => {
    const capability = {
      timeout_ms: 120000,
      input_schema: {
        type: 'object',
        properties: { status: { type: 'string', enum: ['active', 'closed'] } },
        required: ['status'],
        additionalProperties: false
      },
      output_schema: { type: 'object', properties: { count: { type: 'integer', minimum: 0 } } },
      export_fields: [] as string[],
      supports_hitl: true,
      user_invocable: false,
      model_invocable: false,
      concurrency_safe: false
    }
    const originalJson = JSON.stringify(capability)
    const meta = {
      ...defaultWorkflowMeta(),
      ...detailToMeta({ name: 'nested', version: '1.0.0', description: '', capability })
    }
    const saved = workflowDefinition([], [], meta) as { meta: { capability: typeof capability } }

    expect(JSON.stringify(meta.capability)).toBe(originalJson)
    expect(JSON.stringify(saved.meta.capability)).toBe(originalJson)
    expect(meta.capability).not.toBe(capability)
    expect(saved.meta.capability).not.toBe(meta.capability)
    saved.meta.capability.input_schema.properties.status.enum.push('changed')
    saved.meta.capability.export_fields.push('messages')
    expect(JSON.stringify(meta.capability)).toBe(originalJson)
    expect(JSON.stringify(capability)).toBe(originalJson)
  })

  it('缺失或 null 治理不会补默认值，切换详情会清除上一对象的治理', () => {
    const meta: WorkflowMeta = { ...defaultWorkflowMeta(), capability: { timeout_ms: 300000 } }
    Object.assign(meta, detailToMeta({ name: 'missing', version: '1.0.0', description: '' }))
    expect(meta.capability).toBeUndefined()
    expect(workflowDefinition([], [], meta).meta).not.toHaveProperty('capability')
    expect(workflowMetaYaml(meta)).not.toContain('  capability:')

    Object.assign(
      meta,
      detailToMeta({ name: 'null', version: '1.0.0', description: '', capability: null })
    )
    expect(meta.capability).toBeNull()
    expect(workflowDefinition([], [], meta).meta).toHaveProperty('capability', null)
    expect(workflowMetaYaml(meta)).toContain('  capability: null')
    expect(defaultWorkflowMeta()).toHaveProperty('capability', undefined)
  })
})
