<template>
  <section aria-label="能力与工具声明">
    <h3>{{ quickType ? '选择资源' : '能力槽与工具' }}</h3>
    <ElAlert v-if="error" :title="error" type="error" :closable="false" />
    <template v-if="!error">
      <ElFormItem v-if="!quickType || quickType === 'TOOL'" label="添加已发布工具">
        <ElSelect
          :model-value="undefined"
          :disabled="readonly"
          :loading="catalogLoading.TOOL"
          filterable
          placeholder="选择工具后自动添加声明"
          aria-label="添加已发布工具"
          @visible-change="onCatalogVisible('TOOL', $event)"
          @change="addPublishedTool"
        >
          <ElOption
            v-for="item in catalog.TOOL"
            :key="item.id"
            :value="item.id"
            :label="item.name + ' · ' + item.capabilityKey"
          />
        </ElSelect>
        <p class="admin-field-help">选择后自动创建工具和能力槽，安装时再选择服务实例。</p>
      </ElFormItem>
      <ElFormItem v-if="!quickType || quickType === 'KNOWLEDGE_SOURCE'" label="添加已发布知识来源">
        <ElSelect
          :model-value="undefined"
          :disabled="readonly"
          :loading="catalogLoading.KNOWLEDGE_SOURCE"
          filterable
          placeholder="选择知识来源后自动添加声明"
          aria-label="添加已发布知识来源"
          @visible-change="onCatalogVisible('KNOWLEDGE_SOURCE', $event)"
          @change="addPublishedKnowledge"
        >
          <ElOption
            v-for="item in catalog.KNOWLEDGE_SOURCE"
            :key="item.id"
            :value="item.id"
            :label="item.name + ' · ' + item.capabilityKey"
          />
        </ElSelect>
        <p class="admin-field-help">选择后自动创建知识能力槽，安装时绑定对应发布版本。</p>
      </ElFormItem>
      <div v-if="!quickType || advancedOpen">
        <div v-for="(slot, index) in slots" :key="index" class="slot-row">
          <div class="admin-form-grid">
            <ElFormItem label="槽标识"
              ><ElInput
                :model-value="slot.slot"
                :disabled="readonly"
                @update:model-value="patchSlot(index, 'slot', $event)"
            /></ElFormItem>
            <ElFormItem label="名称"
              ><ElInput
                :model-value="slot.label"
                :disabled="readonly"
                @update:model-value="patchSlot(index, 'label', $event)"
            /></ElFormItem>
            <ElFormItem label="能力类型"
              ><ElSelect
                :model-value="slot.type"
                :disabled="readonly"
                @update:model-value="patchSlot(index, 'type', $event)"
                ><ElOption label="工具" value="tool" /><ElOption
                  label="知识来源"
                  value="knowledge_source" /></ElSelect
            ></ElFormItem>
            <ElFormItem label="能力稳定键"
              ><ElInput
                :model-value="slot.capability_id"
                :disabled="readonly"
                @update:model-value="patchSlot(index, 'capability_id', $event)"
            /></ElFormItem>
            <ElFormItem label="版本约束"
              ><ElInput
                :model-value="slot.version_constraint"
                :disabled="readonly"
                placeholder="*、1.0.0 或 1.x"
                @update:model-value="patchSlot(index, 'version_constraint', $event)"
            /></ElFormItem>
            <ElFormItem label="必须绑定"
              ><ElSwitch
                :model-value="slot.required !== false"
                :disabled="readonly"
                @update:model-value="patchSlot(index, 'required', $event)"
            /></ElFormItem>
            <ElFormItem label="说明"
              ><ElInput
                :model-value="slot.description"
                :disabled="readonly"
                @update:model-value="patchSlot(index, 'description', $event)"
            /></ElFormItem>
          </div>
          <ElButton
            link
            type="danger"
            :disabled="readonly"
            @click="writeSlots(slots.filter((_, i) => i !== index))"
            >删除能力槽</ElButton
          >
        </div>
        <ElButton
          :disabled="readonly"
          @click="
            writeSlots([
              ...slots,
              {
                slot: '',
                type: 'tool',
                capability_id: '',
                version_constraint: '*',
                label: '',
                required: true
              }
            ])
          "
          >添加能力槽</ElButton
        >
        <div v-for="(tool, index) in tools" :key="index" class="admin-form-grid">
          <ElFormItem label="节点工具名"
            ><ElInput
              :model-value="tool.name"
              :disabled="readonly"
              @update:model-value="patchTool(index, 'name', $event)"
          /></ElFormItem>
          <ElFormItem label="对应工具槽"
            ><ElSelect
              :model-value="tool.requirement"
              :disabled="readonly"
              @update:model-value="patchTool(index, 'requirement', $event)"
              ><ElOption
                v-for="slot in slots.filter((s) => s.type === 'tool')"
                :key="String(slot.slot)"
                :label="String(slot.label || slot.slot)"
                :value="slot.slot" /></ElSelect
          ></ElFormItem>
          <ElFormItem label="工具说明"
            ><ElInput
              :model-value="tool.description"
              :disabled="readonly"
              @update:model-value="patchTool(index, 'description', $event)"
          /></ElFormItem>
          <ElButton
            link
            type="danger"
            :disabled="readonly"
            @click="writeTools(tools.filter((_, i) => i !== index))"
            >删除工具声明</ElButton
          >
        </div>
        <ElButton
          :disabled="readonly"
          @click="writeTools([...tools, { name: '', requirement: '' }])"
          >添加工具声明</ElButton
        >
      </div>
    </template>
    <ElButton
      v-if="quickType"
      link
      :aria-expanded="advancedOpen"
      @click="advancedOpen = !advancedOpen"
    >
      {{ advancedOpen ? '收起高级声明配置' : '高级声明配置' }}
    </ElButton>
    <p v-if="!quickType || advancedOpen" class="admin-field-help">
      能力标识与版本约束用于筛选安装时可绑定的能力。删除槽或工具声明后，请同步修改引用它的节点，再校验工作流。
    </p>
    <details v-if="!quickType || advancedOpen">
      <summary>高级 JSON 编辑</summary>
      <ElFormItem label="能力槽 JSON"
        ><ElInput
          :model-value="requirements"
          type="textarea"
          :rows="7"
          :disabled="readonly"
          @update:model-value="emit('update:requirements', $event)" /></ElFormItem
      ><ElFormItem label="工具声明 JSON"
        ><ElInput
          :model-value="toolDeclarations"
          type="textarea"
          :rows="6"
          :disabled="readonly"
          @update:model-value="emit('update:toolDeclarations', $event)"
      /></ElFormItem>
    </details>
  </section>
</template>
<script setup lang="ts">
import { computed, ref } from 'vue'
import { message } from '@skillforge/shared/feedback'
import { HttpClientError } from '@skillforge/shared/utils/http'
import { api } from '../../api'
import type { CapabilityDefinition } from '../../api/capabilityMounts'
interface SlotDeclaration extends Record<string, unknown> {
  slot: string
  label?: string
  type: string
  capability_id: string
  version_constraint: string
  required?: boolean
  description?: string
}
interface ToolDeclaration extends Record<string, unknown> {
  name: string
  requirement: string
  description?: string
}
const props = defineProps<{
  requirements?: string
  toolDeclarations?: string
  readonly?: boolean
  quickType?: 'TOOL' | 'KNOWLEDGE_SOURCE'
}>()
const emit = defineEmits<{
  'update:requirements': [value: string]
  'update:toolDeclarations': [value: string]
  'tool-added': [name: string]
  'knowledge-added': [name: string]
}>()
const advancedOpen = ref(false)
const parsed = computed(() => {
  try {
    const slots: unknown = JSON.parse(props.requirements || '[]'),
      tools: unknown = JSON.parse(props.toolDeclarations || '[]')
    if (
      !Array.isArray(slots) ||
      !Array.isArray(tools) ||
      [...slots, ...tools].some((v) => !v || typeof v !== 'object' || Array.isArray(v))
    )
      throw Error()
    return {
      slots: slots as SlotDeclaration[],
      tools: tools as ToolDeclaration[],
      error: ''
    }
  } catch {
    return {
      slots: [],
      tools: [],
      error:
        '能力槽或工具声明格式不正确。请展开“高级 JSON 编辑”，检查每项是否为 JSON 对象，并放在数组中。'
    }
  }
})
const slots = computed(() => parsed.value.slots),
  tools = computed(() => parsed.value.tools),
  error = computed(() => parsed.value.error)
type QuickCapabilityType = 'TOOL' | 'KNOWLEDGE_SOURCE'
const catalog = ref<Record<QuickCapabilityType, CapabilityDefinition[]>>({
  TOOL: [],
  KNOWLEDGE_SOURCE: []
})
const catalogLoading = ref<Record<QuickCapabilityType, boolean>>({
  TOOL: false,
  KNOWLEDGE_SOURCE: false
})
async function onCatalogVisible(type: QuickCapabilityType, visible: boolean) {
  if (!visible || catalogLoading.value[type]) return
  catalogLoading.value[type] = true
  catalog.value[type] = []
  try {
    catalog.value[type] = (await api.capabilities.definitions(type, 'PUBLISHED')).filter(
      (item) => item.capabilityType === type && item.defaultVersionId
    )
  } catch (cause) {
    if (cause instanceof HttpClientError) cause.claimFeedback()
    message.error(`${type === 'TOOL' ? '工具' : '知识来源'}目录读取失败，请重新打开选择框重试`)
  } finally {
    catalogLoading.value[type] = false
  }
}
function addPublishedTool(id: string) {
  const item = catalog.value.TOOL.find((entry) => entry.id === id)
  if (!item || props.readonly) return
  const existingSlots = slots.value.filter(
    (slot) => slot.type === 'tool' && slot.capability_id === item.capabilityKey
  )
  if (existingSlots.length > 1 || (existingSlots.length === 1 && !existingSlots[0]!.slot)) {
    message.info('该工具已有多个或不完整的能力槽，请在已有声明中选择或修正。')
    return
  }
  const existingSlot = existingSlots[0]
  const existingTools = existingSlot
    ? tools.value.filter((tool) => tool.requirement === existingSlot.slot)
    : []
  if (existingTools.length > 1 || (existingTools.length === 1 && !existingTools[0]!.name)) {
    message.info('该工具已有多个或不完整的工具声明，请在已有声明中选择或修正。')
    return
  }
  if (existingTools.length === 1) {
    emit('tool-added', existingTools[0]!.name)
    message.info('已复用已有工具声明，版本约束保持不变。')
    return
  }
  let index = 1
  while (
    slots.value.some((slot) => slot.slot === `tool_${index}`) ||
    tools.value.some((tool) => tool.name === `tool_${index}`)
  )
    index++
  const name = `tool_${index}`
  if (!existingSlot)
    writeSlots([
      ...slots.value,
      {
        slot: name,
        type: 'tool',
        capability_id: item.capabilityKey,
        version_constraint: '*',
        label: item.name,
        required: true
      }
    ])
  writeTools([
    ...tools.value,
    { name, requirement: existingSlot?.slot ?? name, description: item.name }
  ])
  emit('tool-added', name)
}
function addPublishedKnowledge(id: string) {
  const item = catalog.value.KNOWLEDGE_SOURCE.find((entry) => entry.id === id)
  if (!item || props.readonly) return
  const existing = slots.value.filter(
    (slot) => slot.type === 'knowledge_source' && slot.capability_id === item.capabilityKey
  )
  if (existing.length > 1 || (existing.length === 1 && !existing[0]!.slot)) {
    message.info('该知识来源已有多个或不完整的能力槽，请在已有声明中选择或修正。')
    return
  }
  if (existing.length === 1) {
    emit('knowledge-added', existing[0]!.slot)
    message.info('已复用已有知识能力槽，版本约束保持不变。')
    return
  }
  let index = 1
  while (slots.value.some((slot) => slot.slot === `knowledge_${index}`)) index++
  const name = `knowledge_${index}`
  writeSlots([
    ...slots.value,
    {
      slot: name,
      type: 'knowledge_source',
      capability_id: item.capabilityKey,
      version_constraint: '*',
      label: item.name,
      required: true
    }
  ])
  emit('knowledge-added', name)
}
function writeSlots(value: Record<string, unknown>[]) {
  emit('update:requirements', JSON.stringify(value, null, 2))
}
function writeTools(value: Record<string, unknown>[]) {
  emit('update:toolDeclarations', JSON.stringify(value, null, 2))
}
function patchSlot(index: number, key: string, value: unknown) {
  writeSlots(slots.value.map((s, i) => (i === index ? { ...s, [key]: value } : s)))
}
function patchTool(index: number, key: string, value: unknown) {
  writeTools(tools.value.map((s, i) => (i === index ? { ...s, [key]: value } : s)))
}
</script>
<style scoped>
.slot-row {
  padding-block: 12px;
  border-bottom: 1px solid var(--el-border-color);
}
details {
  margin-top: 16px;
}
summary {
  cursor: pointer;
}
</style>
