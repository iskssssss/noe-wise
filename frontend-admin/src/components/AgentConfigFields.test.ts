import { expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { defineComponent, h, reactive, ref } from 'vue'
import { ElForm, ElSelect, ElOption, type FormInstance } from 'element-plus'
import AgentConfigFields from '@skillforge/shared/admin/AgentConfigFields.vue'

it('平台侧未选模型时仍阻止保存', async () => {
  const form = ref<FormInstance>()
  const value = reactive({ name: '测试', system_prompt: '职责', model_config_id: '' })
  const wrapper = mount(
    defineComponent({
      setup: () => () =>
        h(ElForm, { ref: form, model: value }, () =>
          h(AgentConfigFields, { modelValue: value, models: [] })
        )
    })
  )
  try {
    const invalid = await form.value!.validate().catch((fields) => fields)
    expect(invalid).toHaveProperty('model_config_id')
  } finally {
    wrapper.unmount()
  }
})


it('同名模型显示模型、提供方和配置标识，选择仍提交原配置 ID', async () => {
  const value = reactive({ name: '测试', system_prompt: '职责', model_config_id: '' as string | null })
  const wrapper = mount(
    defineComponent({
      setup: () => () => h(AgentConfigFields, {
        modelValue: value,
        models: [
          { id: '101', name: '对话模型', model_id: 'deepseek-flash', provider_id: 'east' },
          { id: '102', name: '对话模型', model_id: 'deepseek-flash', provider_id: 'west' }
        ],
        'onUpdate:modelValue': (next: typeof value) => Object.assign(value, next)
      })
    })
  )
  try {
    const options = wrapper.findAllComponents(ElOption)
    expect(options.map((option) => option.props('label'))).toEqual([
      '对话模型 · deepseek-flash · east · 标识 101',
      '对话模型 · deepseek-flash · west · 标识 102'
    ])
    expect(wrapper.getComponent(ElSelect).props('filterable')).toBe(true)
    wrapper.getComponent(ElSelect).vm.$emit('update:modelValue', '102')
    expect(value.model_config_id).toBe('102')
  } finally {
    wrapper.unmount()
  }
})
