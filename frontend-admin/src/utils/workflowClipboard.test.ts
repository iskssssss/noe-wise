import { describe, expect, it } from 'vitest'
import { reactive } from 'vue'
import { captureWorkflowSelection, pasteWorkflowSelection } from './workflowClipboard'
import { workflowDefinition, type BuilderEdge, type BuilderNode } from './workflow'

const node = (id: string, fields: Partial<BuilderNode> = {}): BuilderNode => ({
  id,
  type: 'tool',
  label: id,
  x: 10,
  y: 20,
  resultTo: 'state',
  assignPairs: [],
  menuActions: [],
  ...fields
})
const edge = (
  id: string,
  source: string,
  target: string,
  fields: Partial<BuilderEdge> = {}
): BuilderEdge => ({ id, source, target, ...fields })
const clones = (result: ReturnType<typeof pasteWorkflowSelection>) =>
  result.nodes.filter((node) => result.selectedIds.includes(node.id))

describe('工作流本页复制粘贴', () => {
  it('捕获响应式子图深克隆，普通边只保留两个端点都选中的连接', () => {
    const nodes = reactive([
      node('fetch', { original: { extra: { flags: [false, null, { keep: '原值' }] } } }),
      node('send', { type: 'output' }),
      node('outside')
    ])
    const edges = [
      edge('inside', 'fetch', 'send', { original: { unknown: { enabled: false, empty: null } } }),
      edge('external', 'send', 'outside')
    ]
    const clipboard = captureWorkflowSelection(nodes, edges, ['fetch', 'send'])
    expect(clipboard.nodes.map((node) => node.id)).toEqual(['fetch', 'send'])
    expect(clipboard.edges.map((edge) => edge.id)).toEqual(['inside'])
    expect(clipboard.nodes[0]!.original).toEqual(nodes[0]!.original)
    const extra = clipboard.nodes[0]!.original!.extra as { flags: unknown[] }
    extra.flags.push('副本')
    expect((nodes[0]!.original!.extra as { flags: unknown[] }).flags).toHaveLength(3)
    ;(clipboard.edges[0]!.original!.unknown as { enabled: boolean }).enabled = true
    expect((edges[0]!.original!.unknown as { enabled: boolean }).enabled).toBe(false)
  })
  it('开始节点和任何不可执行类型拒绝整次复制，粘贴入口也执行相同边界', () => {
    expect(() =>
      captureWorkflowSelection([node('start', { type: 'start' }), node('a')], [], ['start', 'a'])
    ).toThrow('开始节点不能复制')
    for (const type of ['parallel', 'subgraph', 'agentic', 'http', 'unknown'])
      expect(() =>
        captureWorkflowSelection([node('a'), node('blocked', { type })], [], ['a', 'blocked'])
      ).toThrow(type)
    expect(() =>
      pasteWorkflowSelection([], [], { nodes: [node('start', { type: 'start' })], edges: [] })
    ).toThrow('开始节点')
    expect(() => captureWorkflowSelection([node('a')], [], ['missing'])).toThrow('已经变化')
  })
  it('隐式state/extracted输出使用新节点ID，重映射已知引用且保持原图和剪贴快照', () => {
    const nodes = [
      node('orders'),
      node('facts', { resultTo: 'extracted' }),
      node('send', {
        argsText:
          '{"order":"${state.orders.structuredContent.order.id}","fact":"${extracted.facts.name}","external":"${state.orders_backup}"}'
      })
    ]
    const edges = [edge('flow', 'orders', 'send')]
    const clipboard = captureWorkflowSelection(
      nodes,
      edges,
      nodes.map((node) => node.id)
    )
    const before = JSON.stringify({ nodes, edges, clipboard })
    const result = pasteWorkflowSelection(nodes, edges, clipboard)
    expect(result.selectedIds).toEqual(['orders_copy', 'facts_copy', 'send_copy'])
    expect(clones(result)[0]).toMatchObject({ id: 'orders_copy', resultTo: 'state', x: 50, y: 60 })
    expect(clones(result)[1]!.resultTo).toBe('extracted')
    expect(clones(result)[2]!.argsText).toContain('${state.orders_copy.structuredContent.order.id}')
    expect(clones(result)[2]!.argsText).toContain('${extracted.facts_copy.name}')
    expect(clones(result)[2]!.argsText).toContain('${state.orders_backup}')
    expect(result.edges.at(-1)).toMatchObject({
      id: 'flow_copy',
      source: 'orders_copy',
      target: 'send_copy'
    })
    expect(JSON.stringify({ nodes, edges, clipboard })).toBe(before)
    clones(result)[0]!.menuActions.push({ id: 'new', label: '', to: '' })
    expect(clipboard.nodes[0]!.menuActions).toEqual([])
  })
  it('自定义结果沿原字段后缀避开结果和全局assign写入，重复粘贴不撞ID或输出', () => {
    const nodes = [
      node('fetch', { resultTo: 'state.order' }),
      node('occupied', { resultTo: 'state.order_copy' }),
      node('assign', { type: 'assign', assignPairs: [{ target: 'order_copy_2', source: 'false' }] })
    ]
    const clipboard = captureWorkflowSelection(nodes, [], ['fetch'])
    const first = pasteWorkflowSelection(nodes, [], clipboard)
    expect(clones(first)[0]!.resultTo).toBe('state.order_copy_3')
    const second = pasteWorkflowSelection(first.nodes, first.edges, clipboard)
    expect(clones(second)[0]).toMatchObject({ id: 'fetch_copy_2', resultTo: 'state.order_copy_4' })
    const implicit = captureWorkflowSelection([node('orders')], [], ['orders'])
    const occupied = [
      node('assign', { type: 'assign', assignPairs: [{ target: 'orders_copy', source: 'true' }] })
    ]
    expect(clones(pasteWorkflowSelection(occupied, [], implicit))[0]!.id).toBe('orders_copy_2')
  })
  it('根extract改为独立完整结果对象，不改提取字段并将原根字段引用映射到新对象', () => {
    const nodes = [
      node('model', { type: 'llm', resultTo: 'extracted', extractText: 'amount,customer' }),
      node('send', {
        type: 'output',
        message: '${extracted.amount} / ${extracted.customer.email} / extracted.amount'
      })
    ]
    const result = pasteWorkflowSelection(
      nodes,
      [],
      captureWorkflowSelection(nodes, [], ['model', 'send'])
    )
    expect(clones(result)[0]).toMatchObject({
      resultTo: 'extracted.model_copy',
      extractText: 'amount,customer'
    })
    expect(clones(result)[1]!.message).toBe(
      '${extracted.model_copy.amount} / ${extracted.model_copy.customer.email} / extracted.amount'
    )
  })
  it('人工与异步节点不消费extract，仍按完整响应或完成结果映射节点根输出', () => {
    const nodes = [
      node('human', { type: 'human_input', extractText: 'legacy' }),
      node('job', { type: 'async_task', resultTo: 'extracted' }),
      node('send', {
        type: 'output',
        message: '${state.human.id} ${extracted.job.data} ${state.legacy} ${extracted.action}'
      })
    ]
    const result = pasteWorkflowSelection(
      nodes,
      [],
      captureWorkflowSelection(
        nodes,
        [],
        nodes.map((node) => node.id)
      )
    )
    expect(clones(result)[0]!.resultTo).toBe('state')
    expect(clones(result)[2]!.message).toBe(
      '${state.human_copy.id} ${extracted.job_copy.data} ${state.legacy} ${extracted.action}'
    )
  })
  it('选中输出写入者歧义时拒绝迁移，多个纯全局赋值保持业务语义', () => {
    const a = node('a', { resultTo: 'state.shared' }),
      b = node('b', { resultTo: 'state.shared' })
    expect(() =>
      pasteWorkflowSelection([a, b], [], captureWorkflowSelection([a, b], [], ['a', 'b']))
    ).toThrow('共同写入 state.shared')
    const assign = node('assign', {
      type: 'assign',
      assignPairs: [{ target: 'shared', source: 'false' }]
    })
    expect(() =>
      pasteWorkflowSelection(
        [a, assign],
        [],
        captureWorkflowSelection([a, assign], [], ['a', 'assign'])
      )
    ).toThrow('无法无歧义')
    const two = [assign, { ...assign, id: 'second' }]
    expect(
      clones(
        pasteWorkflowSelection(two, [], captureWorkflowSelection(two, [], ['assign', 'second']))
      ).map((node) => node.assignPairs[0]!.target)
    ).toEqual(['shared', 'shared'])
  })
  it('assign保持全局target、类型化false/null与字面量，仅重映射模板及裸来源', () => {
    const nodes = [
      node('fetch'),
      node('assign', {
        type: 'assign',
        assignPairs: [
          { target: 'global', source: 'state.fetch.total' },
          { target: 'bare', source: 'fetch.total' },
          { target: 'fixed', source: '"state.fetch"' },
          { target: 'boolean', source: 'false' },
          { target: 'nullValue', source: 'null' },
          { target: 'text', source: '金额=${state.fetch.total}' }
        ],
        original: { args: { boolean: false, nullValue: null }, unknown: '${state.fetch}' }
      })
    ]
    const result = pasteWorkflowSelection(
      nodes,
      [],
      captureWorkflowSelection(nodes, [], ['fetch', 'assign'])
    )
    const assign = clones(result)[1]!
    expect(assign.assignPairs).toEqual([
      { target: 'global', source: 'state.fetch_copy.total' },
      { target: 'bare', source: 'fetch_copy.total' },
      { target: 'fixed', source: '"state.fetch"' },
      { target: 'boolean', source: 'false' },
      { target: 'nullValue', source: 'null' },
      { target: 'text', source: '金额=${state.fetch_copy.total}' }
    ])
    const serialized = workflowDefinition([assign], []).nodes as { args: Record<string, unknown> }[]
    expect(serialized[0]!.args.boolean).toBe(false)
    expect(serialized[0]!.args.nullValue).toBeNull()
    expect(assign.original!.unknown).toBe('${state.fetch}')
  })
  it('内部菜单与错误跳转映射新节点，外部引用及动作id保持不变，普通边保留扩展与优先级', () => {
    const nodes = [
      node('menu', {
        type: 'menu',
        menuActions: [
          { id: 'continue', label: '继续 ${state.fetch.id}', to: 'fetch' },
          { id: 'external', label: '外部', to: 'outside' }
        ]
      }),
      node('fetch', { onError: 'goto:menu' }),
      node('externalError', { type: 'output', onError: 'goto:outside' }),
      node('outside')
    ]
    const edges = [
      edge('normal', 'menu', 'fetch', {
        condition: '${state.fetch.ok} == true',
        priority: 5,
        original: { extension: { flag: false, placeholder: '${state.fetch}' } }
      }),
      edge('outside-edge', 'fetch', 'outside')
    ]
    const result = pasteWorkflowSelection(
      nodes,
      edges,
      captureWorkflowSelection(nodes, edges, ['menu', 'fetch', 'externalError'])
    )
    expect(clones(result)[0]!.menuActions).toEqual([
      { id: 'continue', label: '继续 ${state.fetch_copy.id}', to: 'fetch_copy' },
      { id: 'external', label: '外部', to: 'outside' }
    ])
    expect(clones(result)[1]!.onError).toBe('goto:menu_copy')
    expect(clones(result)[2]!.onError).toBe('goto:outside')
    expect(result.edges.at(-1)).toMatchObject({
      id: 'normal_copy',
      priority: 5,
      condition: '${state.fetch_copy.ok} == true',
      original: edges[0]!.original
    })
    expect(result.edges).toHaveLength(3)
  })
  it('JSON只重映射字符串值，保留精确数字、空值、键名、普通文案、Schema/样式与未知扩展', () => {
    const raw =
      '{"recipient":"${state.fetch.email}","large":9223372036854775807,"decimal":0.123456789123456789,"enabled":false,"empty":null,"nested":["${state.fetch.id}",false,null],"${state.fetch}":"键名保持"}'
    const nodes = [
      node('fetch'),
      node('script', {
        type: 'script',
        script: 'scripts/fetch.py',
        label: '${state.fetch}',
        scriptInputText: raw,
        scriptOutputSchemaText: '{"description":"${state.fetch}"}',
        original: {
          extension: { expression: '${state.fetch}', number: '9223372036854775807', enabled: false }
        }
      }),
      node('card', {
        type: 'card',
        message: '普通 state.fetch 与 ${state.fetch.id}',
        cardTitle: '${state.fetch.id}',
        cardBody: '${state.fetch}',
        cardFieldsText:
          '[{"value":"${state.fetch.total}","number":0.123456789123456789,"null":null}]',
        cardActionsText: '[{"url":"https://x/${state.fetch.id}","enabled":false}]',
        cardStyleText: '{"color":"${state.fetch}"}',
        renderText: '{"data":"${state.fetch}","long":9223372036854775807}',
        mergeMessageId: '${state.fetch.message_id}'
      })
    ]
    const result = pasteWorkflowSelection(
      nodes,
      [],
      captureWorkflowSelection(
        nodes,
        [],
        nodes.map((node) => node.id)
      )
    )
    const script = clones(result)[1]!,
      card = clones(result)[2]!
    expect(script.scriptInputText).toBe(
      raw
        .replace('${state.fetch.email}', '${state.fetch_copy.email}')
        .replace('${state.fetch.id}', '${state.fetch_copy.id}')
    )
    expect(script.scriptOutputSchemaText).toBe(nodes[1]!.scriptOutputSchemaText)
    expect(script.label).toBe('${state.fetch}')
    expect(script.original).toEqual(nodes[1]!.original)
    expect(card.message).toBe('普通 state.fetch 与 ${state.fetch_copy.id}')
    expect(card.cardFieldsText).toContain('0.123456789123456789')
    expect(card.cardFieldsText).toContain('${state.fetch_copy.total}')
    expect(card.cardActionsText).toContain('https://x/${state.fetch_copy.id}')
    expect(card.cardStyleText).toBe(nodes[2]!.cardStyleText)
    expect(card.renderText).toBe('{"data":"${state.fetch_copy}","long":9223372036854775807}')
    expect(card.mergeMessageId).toBe('${state.fetch_copy.message_id}')
  })
  it('条件仅重映射真正的比较左变量与布尔变量，右侧字面量和裸比较左文案不变', () => {
    const nodes = [node('fetch'), node('send', { type: 'output' })]
    const cases = [
      ['${state.fetch.id} == "${state.fetch.id}"', '${state.fetch_copy.id} == "${state.fetch.id}"'],
      ['state.fetch == "state.fetch"', 'state.fetch == "state.fetch"'],
      ['state.fetch.ok', 'state.fetch_copy.ok'],
      [
        '!  fetch.ok || ${state.fetch.total} >= 12.5000 && "${state.fetch}" == "keep"',
        '!  fetch_copy.ok || ${state.fetch_copy.total} >= 12.5000 && "${state.fetch}" == "keep"'
      ],
      [
        '${state.fetch.id} in ["${state.fetch}", "x||y"]',
        '${state.fetch_copy.id} in ["${state.fetch}", "x||y"]'
      ]
    ]
    for (const [input, expected] of cases) {
      const edges = [edge('a', 'fetch', 'send', { condition: input })]
      expect(
        pasteWorkflowSelection(
          nodes,
          edges,
          captureWorkflowSelection(nodes, edges, ['fetch', 'send'])
        ).edges.at(-1)!.condition
      ).toBe(expected)
    }
  })
  it('不完整JSON和非法结果落点明确拒绝安全粘贴，空剪贴板不会改变图', () => {
    const invalid = [node('fetch'), node('send', { argsText: '{"value":"${state.fetch}"' })]
    const clipboard = captureWorkflowSelection(invalid, [], ['fetch', 'send'])
    const before = JSON.stringify(clipboard)
    expect(() => pasteWorkflowSelection(invalid, [], clipboard)).toThrow('不是完整 JSON')
    expect(JSON.stringify(clipboard)).toBe(before)
    expect(() =>
      pasteWorkflowSelection([], [], {
        nodes: [node('old', { resultTo: 'state.deep.result' })],
        edges: []
      })
    ).toThrow('结果位置')
    const current = [node('existing')]
    expect(pasteWorkflowSelection(current, [], { nodes: [], edges: [] })).toEqual({
      nodes: current,
      edges: [],
      selectedIds: []
    })
  })
})
