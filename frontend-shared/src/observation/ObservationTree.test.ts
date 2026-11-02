import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ElementPlus, { ElTree } from 'element-plus'
import ObservationTree from './ObservationTree.vue'
import type {
  ObservationAttempt,
  ObservationNode,
  ObservationPage,
  TrajectoryDetail
} from './types'
import { node } from './observation.test-fixture'

const wrappers: VueWrapper[] = []
beforeEach(() => {
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe() {}
      unobserve() {}
      disconnect() {}
    }
  )
})
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  document.body.innerHTML = ''
  vi.unstubAllGlobals()
})

function page(items: ObservationNode[]): ObservationPage {
  return {
    items,
    ancestors: [],
    next_cursor: null,
    snapshot: 'snapshot-A',
    coverage: 'COMPLETE',
    read_at: '2026-10-25T00:00:02Z'
  }
}

function attempt(id: string): ObservationAttempt {
  return {
    attempt_id: id,
    diagnostic_available: false,
    input: { ref: `request-${id}`, availability: 'FULL' },
    output: { ref: `response-${id}`, availability: 'FULL' },
    status: 'SUCCEEDED',
    provider: 'provider',
    model: 'model',
    started_at: null,
    finished_at: null,
    duration_ms: null,
    report_status: 'UNKNOWN',
    totals: null
  }
}

async function render(items: ObservationNode[], extra: Record<string, unknown> = {}) {
  const wrapper = mount(ObservationTree, {
    props: { page: page(items), scopeKey: 'actor-A:tenant-A:session-A:root-A', ...extra },
    attachTo: document.body,
    global: {
      plugins: [ElementPlus],
      stubs: {
        teleport: true,
        ElDrawer: {
          props: ['modelValue', 'title'],
          emits: ['update:modelValue'],
          template:
            '<section v-if="modelValue" role="dialog" :aria-label="title"><button aria-label="关闭原文" @click="$emit(\'update:modelValue\', false)">关闭</button><slot /></section>'
        },
        CodeEditor: {
          props: ['modelValue'],
          template: '<pre class="code-content">{{ modelValue }}</pre>'
        }
      }
    }
  })
  wrappers.push(wrapper)
  await flushPromises()
  return wrapper
}

describe('共享执行树', () => {
  it('默认定位结果未知的步骤，深链和用户选择仍优先', async () => {
    const entry = { ...node('entry'), kind: 'ENTRY' as const, status: 'OUTCOME_UNKNOWN' }
    const step = { ...node('step', 'entry'), kind: 'STEP' as const, status: 'OUTCOME_UNKNOWN' }
    const unknown = { ...node('unknown', 'step'), status: 'OUTCOME_UNKNOWN' }
    const wrapper = await render([entry, step, unknown, node('other', 'entry')])
    const tree = wrapper.findComponent(ElTree)
    expect(tree.vm.getCurrentKey()).toBe('unknown')
    await wrapper.setProps({ focusNode: 'other' })
    await flushPromises()
    expect(tree.vm.getCurrentKey()).toBe('other')
    await wrapper.setProps({
      page: page([entry, step, unknown, node('other', 'entry'), node('new', 'entry')])
    })
    await flushPromises()
    expect(tree.vm.getCurrentKey()).toBe('other')
  })
  it('在独立阅读区切换同次尝试的请求与返回，关闭清理正文且迟到响应不重新打开', async () => {
    const wrapper = await render([
      { ...node('model'), kind: 'LLM', attempts: [attempt('first'), attempt('second')] }
    ])
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '查看原始请求')!
      .trigger('click')
    expect(wrapper.get('[role="dialog"]').attributes('aria-label')).toBe('节点原始内容')
    await wrapper.setProps({
      content: {
        content_ref: 'request-first',
        availability: 'FULL',
        schema_id: 'json',
        value: '{"n":9007199254740993123}'
      }
    })
    expect(wrapper.get('.recorded-content').text()).toContain('9007199254740993123')
    await wrapper
      .get('.reader-toolbar')
      .findAll('button')
      .find((button) => button.text() === '原始返回')!
      .trigger('click')
    expect(wrapper.emitted('content')).toEqual([['request-first'], ['response-first']])
    expect(wrapper.find('.code-content').exists()).toBe(false)
    await wrapper
      .get('.reader-attempt-nav')
      .findAll('button')
      .find((button) => button.text() === '下一次尝试')!
      .trigger('click')
    expect(wrapper.emitted('content')?.at(-1)).toEqual(['response-second'])
    await wrapper.get('[aria-label="关闭原文"]').trigger('click')
    expect(wrapper.emitted('contentClose')).toHaveLength(1)
    await wrapper.setProps({
      content: {
        content_ref: 'response-first',
        availability: 'FULL',
        schema_id: 'text',
        value: '迟到的旧返回'
      }
    })
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('迟到的旧返回')
  })

  it('用量和执行结构分别标注完整性，已报告用量不掩盖未保存原文', async () => {
    const old = attempt('old-model')
    old.report_status = 'REPORTED'
    old.totals = {
      prompt_tokens: '5',
      completion_tokens: '1',
      cached_input_tokens: null,
      cache_write_input_tokens: null,
      reasoning_output_tokens: null
    }
    old.input = { ref: null, availability: 'NOT_RECORDED' }
    old.output = { ref: null, availability: 'NOT_RECORDED' }
    const detail: TrajectoryDetail = {
      termination: null,
      summary: {
        tenant_id: '7',
        tenant_name: '机构',
        session_id: 'session-A',
        root_input_id: 'root-A',
        agent_id: '101',
        agent_name: '主Agent',
        input: '用户问题',
        output: '公开结果',
        status: 'COMPLETED',
        answer_status: 'COMPLETED',
        started_at: null,
        finished_at: null,
        duration_ms: null,
        trace_ids: [],
        models: ['model'],
        tools: [],
        step_count: 1,
        model_calls: 1,
        tool_calls: 0,
        totals: old.totals,
        coverage: {
          status: 'COMPLETE',
          total_attempts: 1,
          reported_attempts: 1,
          unknown_attempts: 0,
          partial_attempts: 0,
          zero_usage_attempts: 0
        }
      },
      messages: [],
      usage: null,
      snapshot: 'snapshot-A',
      read_at: '2026-10-25T00:00:02Z',
      usage_summary: {
        calls: 1,
        tokens: '6',
        prompt_tokens: '5',
        completion_tokens: '1',
        amount_by_currency: {},
        unknown_usage_calls: 0,
        cost_coverage: {
          status: 'NO_COSTS',
          total_events: 0,
          priced_events: 0,
          unconfirmed_events: 0,
          missing_model_attempts: 1
        }
      }
    }
    const wrapper = await render([{ ...node('model'), kind: 'LLM', attempts: [old] }], { detail })
    expect(wrapper.get('.coverage-note').text()).toBe('用量：记录完整')
    expect(wrapper.get('.node-coverage').text()).toContain('执行结构：记录完整')
    expect(wrapper.get('.attempt-content').text()).toContain('原始请求 · 未记录')
    expect(wrapper.get('.attempt-content').text()).toContain('原始返回 · 未记录')
    expect(wrapper.get('.attempt-content').findAll('button')).toHaveLength(0)
  })

  it('步骤搜索交由服务端，当前页节点不被本地关键词隐藏', async () => {
    const wrapper = await render([{ ...node('entry'), kind: 'ENTRY' }, node('tool', 'entry')])
    await wrapper.get('input').setValue('未加载的工具')
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('search')?.at(-1)).toEqual(['未加载的工具'])
    expect(wrapper.find('[data-node-id="tool"]').exists()).toBe(true)
  })

  it('步骤关键词按Unicode码点计数，合法256个表情不截断，超限不会发请求', async () => {
    const wrapper = await render([node('tool')])
    const valid = '🙂'.repeat(256)
    await wrapper.get('input').setValue(valid)
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('search')?.at(-1)).toEqual([valid])
    await wrapper.get('input').setValue(valid + '🙂')
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('search')).toHaveLength(1)
    expect(document.querySelector('.el-message--error')?.textContent).toContain(
      '步骤搜索最多 256 个字符'
    )
  })

  it('成功搜索展开命中节点的完整祖先并选中首个结果，退出搜索恢复浏览折叠', async () => {
    const nodes: ObservationNode[] = [
      { ...node('entry'), kind: 'ENTRY' },
      { ...node('agent', 'entry'), kind: 'AGENT' },
      { ...node('step', 'agent'), kind: 'STEP' },
      node('tool', 'step'),
      { ...node('retrieval', 'tool'), kind: 'RETRIEVAL' }
    ]
    const wrapper = await render(nodes)
    const tree = wrapper.findComponent(ElTree)
    // 浏览状态下明确收起入口，搜索仍须显示该入口下的深层命中。
    await wrapper.get('.el-tree-node__expand-icon').trigger('click')
    expect(tree.vm.getNode('entry').expanded).toBe(false)
    await wrapper.get('input').setValue('评估')
    await wrapper.get('form').trigger('submit')
    await wrapper.setProps({
      page: { ...page([nodes[4]!]), ancestors: nodes.slice(0, 4) },
      appliedQuery: '评估'
    })
    await flushPromises()
    expect(wrapper.get('[data-node-id="retrieval"]').isVisible()).toBe(true)
    expect(tree.vm.getCurrentKey()).toBe('retrieval')
    for (const id of ['entry', 'agent', 'step', 'tool'])
      expect(tree.vm.getNode(id).expanded).toBe(true)
    await wrapper.get('input').setValue('')
    await wrapper.get('form').trigger('submit')
    await wrapper.setProps({ page: page(nodes), appliedQuery: '' })
    await flushPromises()
    expect(tree.vm.getNode('entry').expanded).toBe(false)
    expect(tree.vm.getNode('step').expanded).toBe(false)
  })

  it('搜索失败保留旧页、当前选择和已应用条件，不假装显示新搜索结果', async () => {
    const wrapper = await render([{ ...node('entry'), kind: 'ENTRY' }, node('tool', 'entry')])
    const tree = wrapper.findComponent(ElTree)
    await wrapper.get('[data-node-id="tool"]').trigger('click')
    await wrapper.get('input').setValue('未返回的搜索')
    await wrapper.get('form').trigger('submit')
    await wrapper.setProps({ error: '搜索未成功，请重新读取' })
    expect(tree.vm.getCurrentKey()).toBe('tool')
    expect(wrapper.find('[data-node-id="tool"]').isVisible()).toBe(true)
    expect(wrapper.text()).not.toContain('当前搜索：未返回的搜索')
    expect(document.querySelector('.el-message--error')?.textContent).toContain(
      '搜索未成功，请重新读取'
    )
  })

  it('失败搜索后旧条件续页不采纳新搜索或重选节点，输入继续保留', async () => {
    const original = [{ ...node('entry'), kind: 'ENTRY' }, node('tool', 'entry')]
    const wrapper = await render(original, { appliedQuery: '' })
    const tree = wrapper.findComponent(ElTree)
    await wrapper.get('[data-node-id="tool"]').trigger('click')
    await wrapper.get('form.step-search input').setValue('未返回的搜索')
    await wrapper.get('form.step-search').trigger('submit')
    await wrapper.setProps({ error: '搜索失败' })
    await wrapper.setProps({
      page: page([...original, node('next', 'entry')]),
      error: '',
      appliedQuery: ''
    })
    await flushPromises()
    expect(wrapper.text()).not.toContain('当前搜索：未返回的搜索')
    expect(tree.vm.getCurrentKey()).toBe('tool')
    expect((wrapper.get('form.step-search input').element as HTMLInputElement).value).toBe(
      '未返回的搜索'
    )
  })

  it('同词重复搜索失败后普通续页保持选择，明确重读搜索成功才重新定位', async () => {
    const original = [{ ...node('entry'), kind: 'ENTRY' }, node('tool', 'entry')]
    const wrapper = await render(original, { appliedQuery: '原条件' })
    const tree = wrapper.findComponent(ElTree)
    await wrapper.get('[data-node-id="tool"]').trigger('click')
    await wrapper.get('form.step-search input').setValue('原条件')
    await wrapper.get('form.step-search').trigger('submit')
    await wrapper.setProps({ error: '同词搜索失败' })
    await wrapper.setProps({ page: page([...original, node('next', 'entry')]), error: '' })
    await flushPromises()
    expect(tree.vm.getCurrentKey()).toBe('tool')
    expect(wrapper.text()).toContain('当前搜索：原条件')
    await wrapper.get('form.step-search').trigger('submit')
    await wrapper.setProps({ error: '再次搜索失败' })
    const retryButton = [
      ...document.querySelectorAll<HTMLButtonElement>('.el-message button')
    ].find((button) => button.textContent?.trim() === '重新读取')!
    retryButton.click()
    await wrapper.setProps({ page: page([node('found')]), error: '' })
    await flushPromises()
    expect(wrapper.emitted('retry')).toHaveLength(1)
    expect(tree.vm.getCurrentKey()).toBe('found')
  })

  it('只读取用户主动选择的可用正文，切换节点与身份立即隐藏旧内容', async () => {
    const input = { ...node('tool'), input: { ref: 'input-A', availability: 'FULL' } }
    const wrapper = await render([input, node('other')], {
      content: {
        content_ref: 'input-A',
        availability: 'FULL',
        schema_id: 'json',
        value: { secret: '已授权内容' }
      }
    })
    expect(wrapper.text()).not.toContain('已授权内容')
    const read = wrapper.findAll('button').find((button) => button.text() === '查看原始输入')!
    await read.trigger('click')
    expect(wrapper.emitted('content')).toEqual([['input-A']])
    expect(wrapper.text()).toContain('已授权内容')
    await wrapper.get('[data-node-id="other"]').trigger('click')
    expect(wrapper.text()).not.toContain('已授权内容')
    await wrapper.get('[data-node-id="tool"]').trigger('click')
    expect(wrapper.text()).not.toContain('已授权内容')
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '查看原始输入')!
      .trigger('click')
    await wrapper.setProps({ scopeKey: 'actor-B:tenant-A:session-A:root-A' })
    expect(wrapper.text()).not.toContain('已授权内容')
  })

  it('主模型原文直接按attempt读取，独立平台诊断仍由显式能力控制', async () => {
    const model: ObservationNode = {
      ...node('model'),
      kind: 'LLM',
      input: { ref: 'private-model-input', availability: 'FULL' },
      output: { ref: 'private-model-output', availability: 'FULL' },
      attempts: [
        {
          attempt_id: 'attempt-A',
          input: { ref: 'request-A', availability: 'FULL' },
          output: { ref: 'response-A', availability: 'FULL' },
          diagnostic_available: true,
          status: 'SUCCEEDED',
          provider: 'provider',
          model: 'model',
          started_at: null,
          finished_at: null,
          duration_ms: null,
          report_status: 'UNKNOWN',
          totals: null
        }
      ]
    }
    const wrapper = await render([model])
    expect(wrapper.findAll('button').some((button) => button.text() === '查看原始输入')).toBe(false)
    expect(wrapper.findAll('button').some((button) => button.text() === '查看原始输出')).toBe(false)
    expect(wrapper.emitted('content')).toBeUndefined()
    const request = wrapper.findAll('button').find((button) => button.text() === '查看原始请求')!
    expect(request.element.closest('details')).toBeNull()
    await request.trigger('click')
    expect(wrapper.emitted('content')).toEqual([['request-A']])
    expect(wrapper.findAll('button').some((button) => button.text() === '调用诊断')).toBe(false)
    await wrapper.setProps({ allowAttemptDiagnostics: true })
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '调用诊断')!
      .trigger('click')
    expect(wrapper.emitted('attempt')).toEqual([['attempt-A', 'session-A', 'root-A']])
  })

  it('没有attempt的模型节点同样按实际引用读取完整JSON，不裁剪已记录字段', async () => {
    const wrapper = await render([
      {
        ...node('model'),
        kind: 'LLM',
        input: { ref: 'model-input', availability: 'FULL' },
        output: { ref: 'model-output', availability: 'FULL' }
      }
    ])
    const full = {
      messages: [{ role: 'user', content: '原始输入' }],
      extra: '完整尾部'.repeat(1200)
    }
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '查看原始输入')!
      .trigger('click')
    expect(wrapper.emitted('content')).toEqual([['model-input']])
    await wrapper.setProps({
      content: { content_ref: 'model-input', availability: 'FULL', schema_id: 'json', value: full }
    })
    expect(wrapper.get('.code-content').text()).toBe(JSON.stringify(full, null, 2))
  })

  it('辅助模型尝试缺少typed诊断材料时保留状态与Token，不开放私有诊断入口', async () => {
    const model: ObservationNode = {
      ...node('aux-model'),
      kind: 'LLM',
      attempts: [
        {
          attempt_id: 'aux-attempt',
          input: { ref: null, availability: 'NOT_RECORDED' },
          output: { ref: null, availability: 'NOT_RECORDED' },
          diagnostic_available: false,
          status: 'SUCCEEDED',
          provider: 'provider',
          model: '语义辅助模型',
          started_at: null,
          finished_at: null,
          duration_ms: null,
          report_status: 'REPORTED',
          totals: {
            prompt_tokens: '9007199254740993',
            completion_tokens: '3',
            cached_input_tokens: null,
            cache_write_input_tokens: null,
            reasoning_output_tokens: null
          }
        }
      ]
    }
    const wrapper = await render([model], { allowAttemptDiagnostics: true })
    expect(wrapper.findAll('button').some((button) => button.text() === '调用诊断')).toBe(false)
    expect(wrapper.text()).toContain('语义辅助模型')
    expect(wrapper.text()).toContain('成功')
    expect(wrapper.text()).toContain('9,007,199,254,740,993')
    const contentActions = wrapper.get('.content-actions')
    expect(contentActions.text()).toContain('原始请求 · 未记录')
    expect(contentActions.text()).toContain('原始返回 · 未记录')
    expect(contentActions.text()).not.toContain('内容受限')
    expect(contentActions.findAll('button')).toHaveLength(0)
    expect(wrapper.emitted('content')).toBeUndefined()
    expect(wrapper.emitted('attempt')).toBeUndefined()
  })

  it('辅助模型有已保存材料就使用相同原文入口，无需独立诊断标记', async () => {
    const wrapper = await render([{ ...node('aux'), kind: 'LLM', attempts: [attempt('aux')] }])
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '查看原始返回')!
      .trigger('click')
    expect(wrapper.emitted('content')).toEqual([['response-aux']])
    expect(wrapper.emitted('attempt')).toBeUndefined()
  })

  it('多次模型尝试在折叠外按精确ref独立读取，选择新引用时旧返回立即隐藏', async () => {
    const wrapper = await render([
      { ...node('model'), kind: 'LLM', attempts: [attempt('first'), attempt('second')] }
    ])
    const first = wrapper.get('[data-attempt-id="first"]')
    const second = wrapper.get('[data-attempt-id="second"]')
    const firstRequest = first.findAll('button').find((button) => button.text() === '查看原始请求')!
    const secondOutput = second
      .findAll('button')
      .find((button) => button.text() === '查看原始返回')!
    expect(firstRequest.element.closest('details')).toBeNull()
    expect(secondOutput.element.closest('details')).toBeNull()
    await firstRequest.trigger('click')
    await wrapper.setProps({
      content: {
        content_ref: 'request-first',
        availability: 'FULL',
        schema_id: 'json',
        value: '第一次已记录请求'
      }
    })
    expect(wrapper.text()).toContain('第一次已记录请求')
    await secondOutput.trigger('click')
    expect(wrapper.emitted('content')).toEqual([['request-first'], ['response-second']])
    expect(wrapper.text()).not.toContain('第一次已记录请求')
    await wrapper.setProps({
      content: {
        content_ref: 'request-first',
        availability: 'FULL',
        schema_id: 'json',
        value: '第一次迟到请求'
      }
    })
    expect(wrapper.text()).not.toContain('第一次迟到请求')
    await wrapper.setProps({ scopeKey: 'other-identity' })
    expect(wrapper.find('.recorded-content').exists()).toBe(false)
  })

  it('多次工具尝试各自提供原文引用，不把早次请求隐藏在最后一次聚合结果中', async () => {
    const first = { ...attempt('tool-first'), model: null, provider: null }
    const second = { ...attempt('tool-second'), model: null, provider: null }
    const wrapper = await render([
      {
        ...node('tool'),
        attempts: [first, second],
        input: { ref: 'last-request', availability: 'FULL' }
      }
    ])
    const records = wrapper.findAll('.attempt-record')
    expect(wrapper.findAll('.attempt-title').map((title) => title.text())).toEqual([
      '第 1 次尝试 · 工具调用',
      '第 2 次尝试 · 工具调用'
    ])
    expect(wrapper.findAll('button').some((button) => button.text() === '查看原始输入')).toBe(false)
    const firstRequest = records[0]!
      .findAll('button')
      .find((button) => button.text() === '查看原始请求')!
    const secondOutput = records[1]!
      .findAll('button')
      .find((button) => button.text() === '查看原始返回')!
    expect(firstRequest.element.closest('details')).toBeNull()
    expect(secondOutput.element.closest('details')).toBeNull()
    await firstRequest.trigger('click')
    await secondOutput.trigger('click')
    expect(wrapper.emitted('content')).toEqual([['request-tool-first'], ['response-tool-second']])
  })

  it('无ref或未记录的尝试不发内容读取，不用存在的节点聚合ref替代该次尝试', async () => {
    const empty = attempt('empty')
    empty.input = { ref: null, availability: 'FULL' }
    empty.output = { ref: 'unrecorded', availability: 'NOT_RECORDED' }
    const wrapper = await render([
      {
        ...node('model'),
        kind: 'LLM',
        input: { ref: 'last-attempt-input', availability: 'FULL' },
        attempts: [empty]
      }
    ])
    expect(wrapper.get('.content-actions').findAll('button')).toHaveLength(0)
    expect(wrapper.emitted('content')).toBeUndefined()
  })

  it('跨页更新不覆盖手工折叠，显式深链仍可展开祖先', async () => {
    const entry = { ...node('entry'), kind: 'ENTRY' as const }
    const wrapper = await render([entry, node('tool', 'entry')])
    const tree = wrapper.findComponent(ElTree)
    expect(tree.vm.getNode('entry').expanded).toBe(true)
    await wrapper.get('.el-tree-node__expand-icon').trigger('click')
    expect(tree.vm.getNode('entry').expanded).toBe(false)
    await wrapper.setProps({
      page: page([entry, node('tool', 'entry'), { ...node('failed', 'entry'), status: 'FAILED' }])
    })
    await flushPromises()
    expect(tree.vm.getNode('entry').expanded).toBe(false)
    await wrapper.setProps({ focusNode: 'failed' })
    await flushPromises()
    expect(tree.vm.getNode('entry').expanded).toBe(true)
    expect(tree.vm.getCurrentKey()).toBe('failed')
  })

  it('深链节点随服务端定位页到达后被选中，切换数据不会显示旧正文', async () => {
    const entry = { ...node('entry'), kind: 'ENTRY' as const }
    const wrapper = await render([entry], { focusNode: 'focused' })
    await wrapper.setProps({ page: page([entry, node('focused', 'entry')]) })
    await flushPromises()
    const tree = wrapper.findComponent(ElTree)
    expect(tree.vm.getCurrentKey()).toBe('focused')
    expect(tree.vm.getNode('entry').expanded).toBe(true)
  })
})
