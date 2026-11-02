<script setup lang="ts">
import { useId } from 'vue'
import { guardFormEnter } from '@skillforge/shared/admin'
import FieldTip from '@skillforge/shared/components/FieldTip.vue'
import { vDescribedBy } from '@skillforge/shared/directives/describedBy'
import type { CapabilityDeployment, CapabilityVersion } from '../../api'
import type { WorkflowConfigField } from '../../utils/workflow'
import { uniqueToolDeployment } from './workflowInstallationDefaults'

interface InstallationSlot {
  slot: string
  label: string
  type: string
  capability_id: string
  version_constraint: string
  required?: boolean
  versionId?: string
  deploymentKey?: string
}
type FieldValue = string | number | boolean | undefined
const props = defineProps<{
  slots: InstallationSlot[]
  fields: WorkflowConfigField[]
  config: Record<string, FieldValue>
  customPrompt: string
  deployments: CapabilityDeployment[]
  candidates: (slot: InstallationSlot) => CapabilityVersion[]
  disabled?: boolean
}>()
const emit = defineEmits<{
  'update:slots': [value: InstallationSlot[]]
  'update:config': [value: Record<string, FieldValue>]
  'update:customPrompt': [value: string]
}>()
const tip = useId()
function patchSlot(index: number, patch: Partial<InstallationSlot>) {
  emit(
    'update:slots',
    props.slots.map((slot, i) => (i === index ? { ...slot, ...patch } : slot))
  )
}
function patchConfig(key: string, value: FieldValue) {
  emit('update:config', { ...props.config, [key]: value })
}
</script>

<template>
  <ElForm
    label-position="top"
    @submit.prevent
    :disabled="disabled"
    scroll-to-error
    @keydown.capture="guardFormEnter"
  >
    <ElFormItem v-for="(slot, index) in slots" :key="slot.slot" :label="slot.label || slot.slot">
      <ElSelect
        :model-value="slot.versionId"
        :disabled="disabled"
        filterable
        clearable
        :placeholder="slot.required === false ? '可选，留空跳过此槽' : '选择已发布能力版本'"
        v-described-by="tip + '-' + encodeURIComponent(slot.slot)"
        :aria-describedby="tip + '-' + encodeURIComponent(slot.slot)"
        @update:model-value="
          patchSlot(index, {
            versionId: $event || undefined,
            deploymentKey:
              slot.type === 'tool'
                ? uniqueToolDeployment($event || undefined, deployments)
                : undefined
          })
        "
      >
        <ElOption
          v-for="v in candidates(slot)"
          :key="v.id"
          :value="v.id"
          :label="v.name + ' · ' + v.versionKey"
        />
      </ElSelect>
      <ElSelect
        v-if="
          slot.type === 'tool' &&
          slot.versionId &&
          !(
            slot.deploymentKey &&
            slot.deploymentKey === uniqueToolDeployment(slot.versionId, deployments)
          )
        "
        :model-value="slot.deploymentKey"
        :disabled="disabled"
        placeholder="选择工具部署"
        @update:model-value="patchSlot(index, { deploymentKey: $event })"
      >
        <ElOption
          v-for="d in deployments.filter(
            (d) =>
              d.toolVersionId === slot.versionId && ['MCP', 'PLATFORM'].includes(d.providerType)
          )"
          :key="d.deploymentKey"
          :label="d.deploymentKey"
          :value="d.deploymentKey"
        />
      </ElSelect>
      <span
        v-if="
          slot.type === 'tool' &&
          slot.deploymentKey &&
          slot.deploymentKey === uniqueToolDeployment(slot.versionId, deployments)
        "
        class="admin-field-help"
        >已选择唯一可用实例</span
      >
      <FieldTip :id="tip + '-' + encodeURIComponent(slot.slot)"
        >必填槽的唯一候选自动选择；工具有多个可用实例时请选择对应实例。</FieldTip
      >
      <p v-if="!candidates(slot).length" class="admin-field-help" role="status">
        没有符合 {{ slot.capability_id }} /
        {{ slot.version_constraint }} 的已发布版本，请先发布对应能力。
      </p>
      <p
        v-else-if="slot.versionId && !candidates(slot).some((v) => v.id === slot.versionId)"
        class="admin-field-help"
        role="status"
      >
        已选版本不再可用，原选择已保留，请重新选择。
      </p>
      <p
        v-if="
          slot.type === 'tool' &&
          slot.deploymentKey &&
          !deployments.some(
            (d) =>
              d.deploymentKey === slot.deploymentKey &&
              d.toolVersionId === slot.versionId &&
              ['MCP', 'PLATFORM'].includes(d.providerType)
          )
        "
        class="admin-field-help"
        role="status"
      >
        已选服务实例不再可用，请重新选择。
      </p>
    </ElFormItem>
    <ElFormItem
      v-for="field in fields"
      :key="field.key"
      :label="(field.label || field.key) + (field.required ? '（必填）' : '（可选）')"
    >
      <ElSelect
        v-if="field.type === 'boolean'"
        :model-value="config[field.key]"
        :disabled="disabled"
        clearable
        @update:model-value="patchConfig(field.key, $event)"
      >
        <ElOption label="是" :value="true" /><ElOption label="否" :value="false" />
      </ElSelect>
      <ElSelect
        v-else-if="field.type === 'enum'"
        :model-value="config[field.key]"
        :disabled="disabled"
        clearable
        @update:model-value="patchConfig(field.key, $event)"
      >
        <ElOption v-for="value in field.enum_values" :key="value" :label="value" :value="value" />
      </ElSelect>
      <ElInputNumber
        v-else-if="field.type === 'integer' || field.type === 'number'"
        :model-value="typeof config[field.key] === 'number' ? Number(config[field.key]) : undefined"
        :disabled="disabled"
        :precision="field.type === 'integer' ? 0 : undefined"
        :min="field.min"
        :max="field.max"
        @update:model-value="patchConfig(field.key, $event ?? undefined)"
      />
      <ElInput
        v-else
        :model-value="typeof config[field.key] === 'string' ? String(config[field.key]) : ''"
        :disabled="disabled"
        :type="field.type === 'textarea' ? 'textarea' : 'text'"
        @update:model-value="patchConfig(field.key, $event)"
      />
      <p v-if="field.description" class="admin-field-help">{{ field.description }}</p>
    </ElFormItem>
    <ElFormItem label="自定义提示">
      <ElInput
        :model-value="customPrompt"
        :disabled="disabled"
        type="textarea"
        :aria-describedby="tip + '-prompt'"
        v-described-by="tip + '-prompt'"
        @update:model-value="emit('update:customPrompt', $event)"
      />
      <p :id="tip + '-prompt'" class="admin-field-help">
        此提示随工作流安装配置保存，不修改已发布的工作流定义。
      </p>
    </ElFormItem>
  </ElForm>
</template>
