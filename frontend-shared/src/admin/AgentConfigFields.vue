<script
  setup
  lang="ts"
  generic="
    Config extends {
      name: string
      description?: string
      system_prompt: string
      model_config_id: string | null
    }
  "
>
import { vDescribedBy } from '@skillforge/shared/directives/describedBy'

import FieldTip from '@skillforge/shared/components/FieldTip.vue'

import { useId } from 'vue'
import { ElFormItem, ElInput, ElSelect, ElOption } from 'element-plus'
const helpId = useId()
const props = defineProps<{
  modelValue: Config
  models: { id: string; name: string; model_id?: string; provider_id?: string }[]
  disabled?: boolean
  allowDefaultModel?: boolean
}>()
const emit = defineEmits<{ 'update:modelValue': [value: Config] }>()
const defaultModel = '__institution_default__'
function update(key: keyof Config, value: unknown) {
  emit('update:modelValue', { ...props.modelValue, [key]: value })
}

const formTipId = useId()
</script>
<template>
  <ElFormItem
    label="名称"
    prop="name"
    :rules="[{ required: true, whitespace: true, message: '请输入名称', trigger: 'blur' }]"
    ><ElInput
      :model-value="modelValue.name"
      :disabled="disabled"
      maxlength="200"
      show-word-limit
      @update:model-value="update('name', $event)"
    />
  </ElFormItem>
  <ElFormItem label="说明"
    ><ElInput
      v-described-by="formTipId + '-field-1'"
      :aria-describedby="formTipId + '-field-1'"
      :model-value="modelValue.description ?? ''"
      :disabled="disabled"
      type="textarea"
      :rows="2"
      @update:model-value="update('description', $event)"
    />
    <FieldTip :id="formTipId + '-field-1'">说明智能体的用途和适用场景，方便管理员识别。</FieldTip>
  </ElFormItem>
  <ElFormItem
    label="使用模型"
    prop="model_config_id"
    :rules="
      allowDefaultModel ? [] : [{ required: true, message: '请选择已启用模型', trigger: 'change' }]
    "
    ><ElSelect
      v-described-by="`${helpId}-model`"
      :model-value="
        allowDefaultModel && !modelValue.model_config_id ? defaultModel : modelValue.model_config_id
      "
      :disabled="disabled"
      filterable
      placeholder="按名称或标识选择已启用模型"
      :aria-describedby="`${helpId}-model`"
      @update:model-value="update('model_config_id', $event === defaultModel ? '' : $event)"
      ><ElOption v-if="allowDefaultModel" label="继承机构默认模型" :value="defaultModel" />
      <ElOption
        v-for="model in models"
        :key="model.id"
        :label="[model.name, model.model_id, model.provider_id, '标识 ' + model.id].filter(Boolean).join(' · ')"
        :value="model.id"
    /></ElSelect>
    <p :id="`${helpId}-model`" class="admin-field-help">
      {{
        allowDefaultModel
          ? '未单独指定时使用机构默认模型；发布前需确保默认模型已启用。'
          : '未找到所需模型时，请联系管理员确认模型是否已启用。'
      }}
    </p></ElFormItem
  >
  <ElFormItem
    label="系统提示词"
    prop="system_prompt"
    :rules="[{ required: true, whitespace: true, message: '请输入系统提示词', trigger: 'blur' }]"
    ><ElInput
      v-described-by="`${helpId}-prompt`"
      :model-value="modelValue.system_prompt"
      :disabled="disabled"
      type="textarea"
      :rows="10"
      :aria-describedby="`${helpId}-prompt`"
      @update:model-value="update('system_prompt', $event)"
    />
    <p :id="`${helpId}-prompt`" class="admin-field-help">
      明确智能体职责、处理范围、回复要求和限制。完成后保存配置。
    </p></ElFormItem
  >
</template>
