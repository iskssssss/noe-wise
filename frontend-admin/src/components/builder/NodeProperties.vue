<script setup lang="ts">
import { computed, nextTick, ref, useId, watch } from 'vue'
import { guardFormEnter } from '@skillforge/shared/admin'
import { acceptUnhighlightedCreate } from '@skillforge/shared/admin/creatableOption'
import CodeEditor from '@skillforge/shared/components/CodeEditor.vue'
import { knowledgeLimitHelp } from '@skillforge/shared/utils/formHelp'
import type { BuilderEdge, BuilderNode, WorkflowMeta } from '../../utils/workflow'
import { isExecutableWorkflowNodeType } from '../../utils/workflow'
import NodeArguments from './NodeArguments.vue'
import CardRowsEditor from './CardRowsEditor.vue'
import WorkflowVariableSelect from './WorkflowVariableSelect.vue'
import AssignValueEditor from './AssignValueEditor.vue'
import {
  declaredTools,
  declaredKnowledge,
  workflowVariables,
  type NodeSchema,
  type WorkflowToolContract,
  insertAtSelection
} from './nodeEditing'
const model = defineModel<BuilderNode>()
const props = withDefaults(
  defineProps<{
    nodes: BuilderNode[]
    readonly: boolean
    meta?: WorkflowMeta
    edges?: BuilderEdge[]
    toolContracts?: Record<string, WorkflowToolContract>
    scriptResources?: string[]
  }>(),
  { edges: () => [], toolContracts: () => ({}), scriptResources: () => [] }
)
const emit = defineEmits<{ 'edit-connections': [nodeId: string] }>()
const host = ref<HTMLElement>()
const help = useId()
const supported = computed(() => !!model.value && isExecutableWorkflowNodeType(model.value.type))
const locked = computed(() => props.readonly || !supported.value)
const renderSupported = computed(() =>
  ['card', 'human_input', 'menu'].includes(model.value?.type ?? '')
)
const tools = computed(() => declaredTools(props.meta))
const knowledge = computed(() => declaredKnowledge(props.meta))
const variables = computed(() =>
  workflowVariables(
    props.nodes,
    props.edges,
    model.value?.id ?? '',
    props.meta,
    props.toolContracts
  )
)
const ownOutputs = computed(() =>
  workflowVariables(
    model.value ? [model.value] : [],
    [],
    model.value?.id ?? '',
    props.meta,
    props.toolContracts,
    { includeCurrent: true }
  ).filter((variable) => variable.sourceNodeId === model.value?.id)
)
const cardVariables = variables
const inputSchema = computed(
  () => props.toolContracts[model.value?.tool ?? '']?.inputSchema as NodeSchema | undefined
)
const destinations = computed(() => props.nodes.filter((node) => node.id !== model.value?.id))
const producesResult = computed(() =>
  ['llm', 'tool', 'knowledge_query', 'human_input', 'async_task', 'script'].includes(
    model.value?.type ?? ''
  )
)
const resultRoot = computed({
  get: () => (model.value?.resultTo?.startsWith('extracted') ? 'extracted' : 'state'),
  set: (root: string) => {
    if (model.value) model.value.resultTo = root + (resultKey.value ? '.' + resultKey.value : '')
  }
})
const resultKey = computed({
  get: () =>
    model.value?.resultTo?.includes('.')
      ? model.value.resultTo.slice(model.value.resultTo.indexOf('.') + 1)
      : '',
  set: (key: string) => {
    if (model.value) model.value.resultTo = resultRoot.value + (key ? '.' + key : '')
  }
})
const extractKeys = computed({
  get: () =>
    (model.value?.extractText ?? '')
      .split(/[,，]/)
      .map((value) => value.trim())
      .filter(Boolean),
  set: (values: string[]) => {
    if (model.value) model.value.extractText = values.join(',')
  }
})
function onExtractKeyEnter(event: KeyboardEvent) {
  acceptUnhighlightedCreate(event, !locked.value && model.value?.type === 'llm', [], (value) => {
    if (!extractKeys.value.includes(value)) extractKeys.value = [...extractKeys.value, value]
  })
}
const errorStrategy = computed({
  get: () => (model.value?.onError?.startsWith('goto:') ? 'goto' : model.value?.onError || 'fail'),
  set: (value: string) => {
    if (model.value) model.value.onError = value === 'goto' ? 'goto:' + errorTarget.value : value
  }
})
const errorTarget = computed({
  get: () => (model.value?.onError?.startsWith('goto:') ? model.value.onError.slice(5) : ''),
  set: (value: string) => {
    if (model.value) model.value.onError = 'goto:' + value
  }
})
const branchIds = computed(() =>
  (model.value?.branchesText ?? '')
    .split(/[,\n]/)
    .map((value) => value.trim())
    .filter(Boolean)
)
const knowledgeFields = [
  { key: 'knowledgeMaxItems', label: '最大结果数' },
  { key: 'knowledgeMaxTokens', label: '最大 Token' },
  { key: 'knowledgeMaxCharacters', label: '最大字符数' },
  { key: 'knowledgeTimeoutMs', label: '超时（毫秒）' },
  { key: 'knowledgeMaxSources', label: '最大来源数' }
] as const
type TextField =
  'prompt' | 'message' | 'knowledgeQuery' | 'cardTitle' | 'cardBody' | 'mergeMessageId'
const promptEditor = ref<{ insertText(text: string): boolean }>()
const selections = new Map<
  TextField,
  { start: number; end: number; input: HTMLInputElement | HTMLTextAreaElement }
>()
watch(
  () => model.value?.id,
  () => selections.clear()
)
function rememberSelection(field: TextField, event: Event) {
  const input = event.target
  if (input instanceof HTMLInputElement || input instanceof HTMLTextAreaElement)
    selections.set(field, {
      start: input.selectionStart ?? input.value.length,
      end: input.selectionEnd ?? input.value.length,
      input
    })
}
function insertVariable(field: TextField, value: string) {
  if (!model.value || !value || locked.value) return
  if (field === 'prompt') {
    promptEditor.value?.insertText(value)
    return
  }
  const selection = selections.get(field)
  const result = insertAtSelection(model.value[field] ?? '', value, selection)
  model.value[field] = result.text
  void nextTick(() => {
    if (!selection?.input.isConnected) return
    selection.input.focus()
    selection.input.setSelectionRange(result.caret, result.caret)
    selections.set(field, { ...selection, start: result.caret, end: result.caret })
  })
}
function focusField(fieldPath: string): boolean {
  const requested = fieldPath.replace(/^nodes(?:\[[^\]]+\])?\./, '')
  const aliases: Record<string, string> = {
    result_to: 'resultTo',
    on_error: 'onError',
    requirement: 'knowledgeRequirement',
    query: 'knowledgeQuery',
    budget: 'knowledgeBudget',
    extract: 'extract',
    args: 'args',
    timeout_seconds: 'timeoutSeconds',
    card: 'card',
    render: 'render',
    merge_message_id: 'mergeMessageId'
  }
  const detailAliases: Record<string, string> = {
    'card.title': 'cardTitle',
    'card.body': 'cardBody',
    'card.fields': 'cardFieldsText',
    'card.actions': 'cardActionsText',
    'card.style': 'cardStyleText',
    'args.input': 'scriptInputText',
    'args.output_schema': 'scriptOutputSchemaText',
    'args.script': 'args'
  }
  const key =
    (/^assignPairs\[\d+\]\.source$/.test(requested) ? requested : undefined) ??
    detailAliases[requested] ??
    aliases[requested.split('.')[0] ?? ''] ??
    requested.split('.')[0] ??
    requested
  const target = [...(host.value?.querySelectorAll<HTMLElement>('[data-node-field]') ?? [])].find(
    (element) => element.dataset.nodeField === key
  )
  if (!target) return false
  let parent: HTMLElement | null = target
  while (parent) {
    if (parent instanceof HTMLDetailsElement) parent.open = true
    parent = parent.parentElement
  }
  void nextTick(() => {
    const input = target.querySelector<HTMLElement>('input, textarea, button, [tabindex="0"]')
    input?.focus()
    target.scrollIntoView?.({ block: 'nearest' })
  })
  return true
}
defineExpose({ focusField })
</script>
<template>
  <div ref="host" class="node-properties">
    <ElForm
      v-if="model"
      :key="model.id"
      label-position="top"
      :disabled="locked"
      @submit.prevent
      scroll-to-error
      @keydown.capture="guardFormEnter"
    >
      <div class="node-context">
        <ElTag effect="plain" size="small">{{ model.type }}</ElTag
        ><span>{{ model.id }}</span>
      </div>
      <ElAlert
        v-if="!supported"
        title="此节点类型暂不支持执行，配置仅供查看。请换用可执行节点后再发布。"
        type="warning"
        :closable="false"
      />
      <ElFormItem label="节点名称" data-node-field="label"
        ><ElInput v-model="model.label" aria-label="节点名称"
      /></ElFormItem>
      <section v-if="producesResult" class="node-section" data-node-field="resultTo">
        <h4>保存结果</h4>
        <p
          v-if="
            model.resultTo &&
            !/^(?:state|extracted)(?:\.[A-Za-z][A-Za-z0-9_]*)?$/.test(model.resultTo)
          "
          class="node-warning"
        >
          结果位置
          {{ model.resultTo }}
          无法使用。请选择变量集合；自定义字段名须为字母开头的单层名称。原值尚未更改。
        </p>
        <div class="node-two-columns">
          <ElFormItem label="变量集合"
            ><ElSelect v-model="resultRoot" aria-label="结果变量集合"
              ><ElOption value="state" label="状态变量（state）" /><ElOption
                value="extracted"
                label="提取变量（extracted）" /></ElSelect></ElFormItem
          ><ElFormItem label="字段名（可选）"
            ><ElInput v-model="resultKey" aria-label="结果字段名" placeholder="留空按默认规则保存"
          /></ElFormItem>
        </div>
        <p class="admin-field-help">
          {{
            resultKey
              ? '完整结果将保存到 ' + resultRoot + '.' + resultKey + '。同名字段会被覆盖。'
              : !['human_input', 'async_task'].includes(model.type) && extractKeys.length
                ? '提取字段将分别写入 ' + resultRoot + ' 集合。同名字段会被覆盖。'
                : '结果将保存到 ' + resultRoot + '.' + model.id + '。同名字段会被覆盖。'
          }}
          后续节点可从变量选择器引用。
        </p>
      </section>
      <section
        v-if="ownOutputs.length"
        class="node-section node-output-preview"
        aria-label="此节点可用输出"
      >
        <h4>此节点可用输出</h4>
        <p class="admin-field-help">
          节点成功执行后，后续节点可从变量选择器引用以下结果。未声明结构的结果和数组可整体引用。
        </p>
        <ul>
          <li v-for="output in ownOutputs" :key="output.path">
            <code>{{ output.path }}</code
            ><span
              >{{ output.type
              }}{{ output.availability === 'conditional' ? ' · 可能无值' : '' }}</span
            >
          </li>
        </ul>
      </section>
      <ElFormItem
        v-if="
          ['llm', 'human_input', 'menu'].includes(model.type) ||
          (model.type === 'async_task' && model.prompt)
        "
        label="提示词"
        data-node-field="prompt"
        ><CodeEditor
          ref="promptEditor"
          v-model="model.prompt"
          label="节点提示词"
          language="markdown"
          height="200px"
          :readonly="locked || model.type === 'async_task'"
        /><WorkflowVariableSelect
          v-if="model.type !== 'async_task'"
          :options="variables"
          :disabled="locked"
          label="向提示词插入变量"
          template
          @update:model-value="insertVariable('prompt', $event)"
        />
        <p class="admin-field-help">
          {{
            model.type === 'async_task'
              ? '此处提示词不参与执行，仅保留原配置。异步节点等待已有任务的回调，请确认任务已启动。'
              : '说明处理目标或需要用户补充的信息。可插入上游变量；未执行的分支可能没有输出。'
          }}
        </p></ElFormItem
      >
      <ElFormItem
        v-if="model.type === 'llm' || (model.type === 'human_input' && model.extractText)"
        label="提取字段"
        data-node-field="extract"
        ><ElSelect
          v-model="extractKeys"
          @keydown.enter.capture="onExtractKeyEnter"
          :disabled="locked || model.type === 'human_input'"
          multiple
          filterable
          allow-create
          default-first-option
          aria-label="提取字段"
          placeholder="输入字段名后按 Enter 添加"
        />
        <p class="admin-field-help">
          {{
            model.type === 'human_input'
              ? '人工节点保存完整响应，不执行模型字段提取；此处旧配置仅供查看。'
              : '每个标签对应一个字段，输入字段名后按 Enter 添加。'
          }}
        </p></ElFormItem
      >
      <template v-if="model.type === 'tool'">
        <ElFormItem label="调用工具" data-node-field="tool"
          ><ElSelect
            v-model="model.tool"
            filterable
            aria-label="调用工具"
            placeholder="选择工作流已声明的工具"
            ><ElOption
              v-if="model.tool && !tools.some((tool) => tool.name === model?.tool)"
              :value="model.tool"
              :label="model.tool + '（原引用，尚未声明）'" /><ElOption
              v-for="tool in tools"
              :key="tool.name"
              :value="tool.name"
              :label="tool.label === tool.name ? tool.name : tool.label + ' · ' + tool.name"
          /></ElSelect>
          <p v-if="!tools.length" class="admin-field-help">
            在下方选择已发布工具后即可自动添加并选中。
          </p>
          <p
            v-else-if="model.tool && !tools.some((tool) => tool.name === model?.tool)"
            class="node-warning"
          >
            此工具未在工作流中声明。请重新选择，或在下方补充声明。
          </p></ElFormItem
        >
        <ElFormItem label="工具参数" data-node-field="args"
          ><NodeArguments
            :key="model.id + ':' + model.tool"
            v-model="model.argsText"
            :schema="inputSchema"
            :variables="variables"
            :readonly="locked"
            label="工具参数"
        /></ElFormItem>
      </template>
      <details
        v-if="['tool', 'llm', 'knowledge_query', 'script', 'async_task'].includes(model.type)"
        class="node-section"
        data-node-field="onError"
      >
        <summary>{{ model.type === 'async_task' ? '回调失败或超时时' : '执行失败时' }}</summary>
        <ElFormItem label="失败策略"
          ><ElSelect
            v-model="errorStrategy"
            :aria-label="model.type === 'tool' ? '工具失败策略' : '节点失败策略'"
            ><ElOption value="fail" label="终止流程" /><ElOption
              value="goto"
              label="跳转到恢复节点" /><ElOption
              value="message"
              label="返回错误消息并终止" /><ElOption
              v-if="!['fail', 'goto', 'message'].includes(errorStrategy)"
              :value="errorStrategy"
              :label="errorStrategy + '（原配置）'" /></ElSelect></ElFormItem
        ><ElFormItem v-if="errorStrategy === 'goto'" label="恢复节点"
          ><ElSelect v-model="errorTarget" filterable aria-label="失败后跳转节点"
            ><ElOption
              v-if="errorTarget && !destinations.some((node) => node.id === errorTarget)"
              :value="errorTarget"
              :label="errorTarget + '（节点不存在）'" /><ElOption
              v-for="node in destinations"
              :key="node.id"
              :value="node.id"
              :label="node.label || node.id" /></ElSelect
        ></ElFormItem>
      </details>
      <template v-if="model.type === 'knowledge_query'">
        <ElFormItem label="知识能力槽" data-node-field="knowledgeRequirement"
          ><ElSelect
            v-model="model.knowledgeRequirement"
            filterable
            aria-label="知识能力槽"
            placeholder="选择已声明的知识来源"
            ><ElOption
              v-if="
                model.knowledgeRequirement &&
                !knowledge.some((slot) => slot.slot === model?.knowledgeRequirement)
              "
              :value="model.knowledgeRequirement"
              :label="model.knowledgeRequirement + '（原引用，尚未声明）'" /><ElOption
              v-for="slot in knowledge"
              :key="slot.slot"
              :value="slot.slot"
              :label="slot.label === slot.slot ? slot.slot : slot.label + ' · ' + slot.slot"
          /></ElSelect>
          <p v-if="!knowledge.length" class="admin-field-help">
            在下方选择已发布知识来源后即可自动添加并选中。
          </p></ElFormItem
        >
        <ElFormItem label="查询内容" data-node-field="knowledgeQuery"
          ><ElInput
            v-model="model.knowledgeQuery"
            @blur="rememberSelection('knowledgeQuery', $event)"
            @keyup="rememberSelection('knowledgeQuery', $event)"
            @select="rememberSelection('knowledgeQuery', $event)"
            @click="rememberSelection('knowledgeQuery', $event)"
            type="textarea"
            :rows="3"
            aria-label="知识查询内容" /><WorkflowVariableSelect
            :options="variables"
            :disabled="locked"
            label="向查询插入变量"
            template
            @update:model-value="insertVariable('knowledgeQuery', $event)"
        /></ElFormItem>
        <details class="node-section" data-node-field="knowledgeBudget" open>
          <summary>检索预算</summary>
          <div class="node-two-columns">
            <ElFormItem v-for="field in knowledgeFields" :key="field.key" :label="field.label"
              ><ElInput
                v-model="model[field.key]"
                inputmode="numeric"
                :aria-label="field.label"
                :aria-describedby="help + field.key"
              />
              <p :id="help + field.key" class="admin-field-help">
                {{ knowledgeLimitHelp(field.key) }}
              </p></ElFormItem
            >
          </div>
        </details>
      </template>
      <ElFormItem
        v-if="['output', 'card'].includes(model.type) || (model.type === 'end' && model.message)"
        label="输出消息"
        data-node-field="message"
        ><ElInput
          v-model="model.message"
          @blur="rememberSelection('message', $event)"
          @keyup="rememberSelection('message', $event)"
          @select="rememberSelection('message', $event)"
          @click="rememberSelection('message', $event)"
          :disabled="locked || model.type === 'end'"
          type="textarea"
          :rows="4"
          aria-label="输出消息" /><WorkflowVariableSelect
          v-if="model.type !== 'end'"
          :options="variables"
          :disabled="locked"
          label="向输出消息插入变量"
          template
          @update:model-value="insertVariable('message', $event)"
      /></ElFormItem>
      <p v-if="model.type === 'end'" class="admin-field-help">
        结束节点只终止流程。如需回复用户，请在前面添加“输出”节点；此处旧消息仅供查看。
      </p>
      <p v-if="model.type === 'async_task'" class="admin-field-help">
        本节点只等待已有任务的回调。请确认任务已启动；完成后保存结果，失败或超时按上方策略处理。
      </p>
      <template v-if="['card', 'human_input'].includes(model.type)">
        <section class="node-section" data-node-field="card">
          <h4>卡片内容</h4>
          <ElFormItem label="卡片标题" data-node-field="cardTitle"
            ><ElInput
              v-model="model.cardTitle"
              @blur="rememberSelection('cardTitle', $event)"
              @keyup="rememberSelection('cardTitle', $event)"
              @select="rememberSelection('cardTitle', $event)"
              @click="rememberSelection('cardTitle', $event)"
              aria-label="卡片标题" /><WorkflowVariableSelect
              :options="cardVariables"
              :disabled="locked"
              label="向卡片标题插入变量"
              template
              @update:model-value="insertVariable('cardTitle', $event)" /></ElFormItem
          ><ElFormItem label="卡片正文" data-node-field="cardBody"
            ><ElInput
              v-model="model.cardBody"
              @blur="rememberSelection('cardBody', $event)"
              @keyup="rememberSelection('cardBody', $event)"
              @select="rememberSelection('cardBody', $event)"
              @click="rememberSelection('cardBody', $event)"
              type="textarea"
              :rows="3"
              aria-label="卡片正文" /><WorkflowVariableSelect
              :options="cardVariables"
              :disabled="locked"
              label="向卡片正文插入变量"
              template
              @update:model-value="insertVariable('cardBody', $event)" /></ElFormItem
          ><ElFormItem label="卡片字段" data-node-field="cardFieldsText"
            ><CardRowsEditor
              v-model="model.cardFieldsText"
              kind="fields"
              :variables="cardVariables"
              :readonly="locked" /></ElFormItem
          ><ElFormItem label="卡片操作" data-node-field="cardActionsText"
            ><CardRowsEditor
              v-model="model.cardActionsText"
              kind="actions"
              :variables="cardVariables"
              :readonly="locked"
          /></ElFormItem>
        </section>
        <details class="node-section">
          <summary>卡片样式与消息合并</summary>
          <ElFormItem label="合并目标消息" data-node-field="mergeMessageId"
            ><ElInput
              v-model="model.mergeMessageId"
              @blur="rememberSelection('mergeMessageId', $event)"
              @keyup="rememberSelection('mergeMessageId', $event)"
              @select="rememberSelection('mergeMessageId', $event)"
              @click="rememberSelection('mergeMessageId', $event)"
              aria-label="合并目标消息"
            /><WorkflowVariableSelect
              :options="variables"
              :disabled="locked"
              label="插入目标消息变量"
              template
              @update:model-value="insertVariable('mergeMessageId', $event)"
            />
            <p class="admin-field-help">
              留空发送新卡片；填写真实目标消息标识或其变量引用。
            </p></ElFormItem
          ><ElFormItem label="卡片样式" data-node-field="cardStyleText"
            ><CodeEditor
              v-model="model.cardStyleText"
              label="卡片样式 JSON"
              language="json"
              height="140px"
              :readonly="locked"
            />
            <p class="admin-field-help">样式效果取决于卡片展示端支持的属性。</p></ElFormItem
          >
        </details>
      </template>
      <section v-if="model.type === 'menu'" class="node-section" data-node-field="actions">
        <div class="node-section-heading">
          <h4>菜单选项</h4>
          <ElButton
            :disabled="locked"
            @click="model.menuActions.push({ id: '', label: '', to: '' })"
            >添加选项</ElButton
          >
        </div>
        <p v-if="!model.menuActions.length" class="admin-field-help">
          为用户添加可选操作，并指定选中后的节点。
        </p>
        <div v-for="(item, index) in model.menuActions" :key="index" class="node-row">
          <div class="node-row-heading">
            <strong>选项 {{ index + 1 }}</strong
            ><ElButton
              type="danger"
              text
              :disabled="locked"
              @click="model.menuActions.splice(index, 1)"
              >删除选项</ElButton
            >
          </div>
          <ElFormItem label="展示标题"
            ><ElInput v-model="item.label" :aria-label="'选项' + (index + 1) + '标题'"
          /></ElFormItem>
          <div class="node-two-columns">
            <ElFormItem label="选项标识"
              ><ElInput v-model="item.id" :aria-label="'选项' + (index + 1) + '标识'" /></ElFormItem
            ><ElFormItem label="选中后跳转"
              ><ElSelect
                v-model="item.to"
                filterable
                :aria-label="'选项' + (index + 1) + '目标节点'"
                ><ElOption
                  v-if="item.to && !destinations.some((node) => node.id === item.to)"
                  :value="item.to"
                  :label="item.to + '（节点不存在）'" /><ElOption
                  v-for="node in destinations"
                  :key="node.id"
                  :value="node.id"
                  :label="node.label || node.id" /></ElSelect
            ></ElFormItem>
          </div>
        </div>
      </section>
      <section v-if="model.type === 'assign'" class="node-section" data-node-field="args">
        <div class="node-section-heading">
          <h4>变量赋值</h4>
          <ElButton :disabled="locked" @click="model.assignPairs.push({ target: '', source: '' })"
            >添加赋值</ElButton
          >
        </div>
        <p class="admin-field-help">
          目标是 state 集合内的字段名，例如 order_id，后续通过 state.order_id 引用。
        </p>
        <div v-for="(pair, index) in model.assignPairs" :key="index" class="node-row">
          <div class="node-row-heading">
            <strong>赋值 {{ index + 1 }}</strong
            ><ElButton
              type="danger"
              text
              :disabled="locked"
              @click="model.assignPairs.splice(index, 1)"
              >删除赋值</ElButton
            >
          </div>
          <ElFormItem label="目标字段名"
            ><ElInput
              v-model="pair.target"
              :aria-label="'赋值' + (index + 1) + '目标字段名'" /></ElFormItem
          ><ElFormItem label="来源" :data-node-field="'assignPairs[' + index + '].source'"
            ><AssignValueEditor
              v-model="pair.source"
              :variables="variables"
              :readonly="locked"
              :label="'赋值' + (index + 1)"
          /></ElFormItem>
        </div>
      </section>
      <template v-if="model.type === 'script'"
        ><ElFormItem label="已冻结脚本资源" data-node-field="args"
          ><ElSelect
            v-model="model.script"
            filterable
            aria-label="脚本资源路径"
            placeholder="选择脚本资源"
            ><ElOption
              v-if="model.script && !scriptResources.includes(model.script)"
              :value="model.script"
              :label="model.script + '（原引用，资源未找到）'" /><ElOption
              v-for="path in scriptResources"
              :key="path"
              :value="path"
              :label="path"
          /></ElSelect>
          <p class="admin-field-help">从“脚本资源”中选择 .py 或 .js 文件，脚本内容在资源区编辑。</p>
          <ElAlert
            v-if="!scriptResources.length"
            title="尚无脚本文件，请先在“脚本资源”中添加并保存文件。"
            type="info"
            :closable="false" /></ElFormItem
        ><ElFormItem label="脚本输入" data-node-field="scriptInputText"
          ><NodeArguments
            v-model="model.scriptInputText"
            :variables="variables"
            :readonly="locked"
            label="脚本输入"
            data-node-field="scriptInputText"
        /></ElFormItem>
        <details class="node-section" data-node-field="scriptOutputSchemaText">
          <summary>脚本输出约束</summary>
          <CodeEditor
            v-model="model.scriptOutputSchemaText"
            label="脚本输出 JSON Schema"
            language="json"
            height="180px"
            :readonly="locked"
          />
          <p class="admin-field-help">
            填写 JSON Schema 以校验脚本返回值；留空时返回值必须为对象。
          </p>
        </details></template
      >
      <template v-if="model.type === 'agentic'"
        ><ElFormItem
          v-for="field in [
            { key: 'agentSlot', label: '智能体槽' },
            { key: 'agentObjective', label: '任务目标' },
            { key: 'agentContext', label: '任务上下文' },
            { key: 'agentConstraints', label: '约束' },
            { key: 'agentExpectedOutput', label: '预期输出' }
          ] as const"
          :key="field.key"
          :label="field.label"
          ><ElInput
            v-model="model[field.key]"
            type="textarea"
            :rows="2"
            :aria-label="field.label" /></ElFormItem
      ></template>
      <ElFormItem v-if="model.type === 'parallel'" label="原并行分支"
        ><ElSelect :model-value="branchIds" multiple aria-label="原并行分支" disabled
          ><ElOption
            v-for="id in branchIds"
            :key="id"
            :value="id"
            :label="nodes.find((node) => node.id === id)?.label || id" /></ElSelect
      ></ElFormItem>
      <ElFormItem v-if="model.type === 'subgraph'" label="原子工作流标识"
        ><ElInput v-model="model.workflowId" aria-label="原子工作流标识"
      /></ElFormItem>
      <section v-if="model.type === 'condition'" class="node-section">
        <h4>分支判断</h4>
        <p class="admin-field-help">
          条件在节点执行后判断，可引用本节点和可达上游已产生的输出。按优先级从高到低选择首个满足条件的连接。
        </p>
        <ElButton :disabled="readonly" @click="emit('edit-connections', model.id)"
          >编辑此节点的连接条件</ElButton
        >
      </section>
      <ElFormItem
        v-if="['human_input', 'menu', 'async_task'].includes(model.type)"
        label="等待超时（秒）"
        data-node-field="timeoutSeconds"
        ><ElInput v-model="model.timeoutSeconds" inputmode="numeric" aria-label="等待超时秒数" />
        <p class="admin-field-help">
          {{
            model.type === 'async_task'
              ? '超时按回调失败处理，使用上面的失败策略终止或跳转。'
              : '超时后以 action=timeout 恢复，后续连接可按该响应判断。'
          }}
        </p></ElFormItem
      >
      <details
        v-if="renderSupported || model.renderText"
        class="node-section"
        data-node-field="render"
      >
        <summary>专业渲染扩展</summary>
        <CodeEditor
          v-model="model.renderText"
          label="节点渲染扩展 JSON"
          language="json"
          height="160px"
          :readonly="locked || !renderSupported"
        />
        <p class="admin-field-help">
          {{
            renderSupported
              ? '填写卡片展示数据，可引用工作流变量；显示效果取决于消息展示端支持的格式。'
              : '此节点不使用渲染扩展，旧配置仅供查看。'
          }}
        </p>
      </details> </ElForm
    ><ElEmpty v-else description="请在画布中选择要配置的节点" :image-size="56" />
  </div>
</template>
<style scoped>
.node-properties {
  min-width: 0;
}
.node-context {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  color: var(--admin-muted);
  font-size: 12px;
  overflow-wrap: anywhere;
  margin-bottom: 16px;
}
.node-section {
  border-top: 1px solid var(--admin-border);
  padding-top: 16px;
  margin-block: 16px;
}
.node-section h4 {
  font-size: 14px;
  margin: 0 0 12px;
}
.node-section-heading,
.node-row-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.node-section-heading h4 {
  margin: 0;
}
.node-two-columns {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}
.node-two-columns :deep(.el-form-item) {
  min-width: 0;
  margin-bottom: 0;
}
.node-row {
  border: 1px solid var(--admin-border);
  border-radius: 8px;
  padding: 12px;
  margin-top: 12px;
}
.node-row-heading {
  margin-bottom: 12px;
}
.node-section summary {
  cursor: pointer;
  min-height: 32px;
  font-size: 14px;
  font-weight: 600;
  padding-bottom: 12px;
}
.node-section summary:focus-visible {
  outline: 2px solid var(--admin-accent);
  outline-offset: 3px;
}
.node-properties :deep(.el-form-item__content) {
  gap: 8px;
}
.node-properties :deep(.el-select) {
  width: 100%;
}
.node-warning {
  color: var(--el-color-warning);
  font-size: 13px;
  overflow-wrap: anywhere;
}
@media (max-width: 600px) {
  .node-two-columns {
    grid-template-columns: 1fr;
  }
}
.node-output-preview ul {
  list-style: none;
  padding: 0;
  margin: 8px 0 0;
  max-height: 220px;
  overflow: auto;
}
.node-output-preview li {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: 4px 12px;
  padding-block: 4px;
}
.node-output-preview code {
  overflow-wrap: anywhere;
  font-size: 12px;
}
.node-output-preview li span {
  color: var(--admin-muted);
  font-size: 12px;
}
</style>
