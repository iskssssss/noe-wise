import { expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { defineComponent, h, reactive, ref } from 'vue'
import { ElForm, ElFormItem, ElInput, ElSelect, ElOption, type FormInstance } from 'element-plus'
import AgentConfigFields from '@skillforge/shared/admin/AgentConfigFields.vue'

it.each(['文', '😀'])('名称按码点校验，超限保留输入并允许修正：%s', async (character) => {
  const form = ref<FormInstance>()
  const value = reactive({
    name: character.repeat(128),
    system_prompt: '职责',
    model_config_id: 'model' as string | null
  })
  const wrapper = mount(
    defineComponent({
      setup: () => () =>
        h(ElForm, { ref: form, model: value }, () =>
          h(AgentConfigFields, {
            modelValue: value,
            models: [],
            'onUpdate:modelValue': (next: typeof value) => Object.assign(value, next)
          })
        )
    })
  )
  try {
    expect(await form.value!.validate()).toBe(true)
    const name = wrapper
      .findAllComponents(ElFormItem)
      .find((field) => field.props('prop') === 'name')!
    await name.getComponent(ElInput).setValue(character.repeat(129))
    expect(value.name).toBe(character.repeat(129))
    const invalid = await form.value!.validate().catch((fields) => fields)
    expect(invalid).toHaveProperty('name')
    expect(name.text()).toContain('128')
    await name.getComponent(ElInput).setValue(character.repeat(128))
    expect(await form.value!.validate()).toBe(true)
  } finally {
    wrapper.unmount()
  }
})

it.each([false, true])('说明字段按宿主模型能力展示并提交：%s', async (supported) => {
  const value = reactive({
    name: '测试',
    system_prompt: '职责',
    model_config_id: 'model' as string | null,
    ...(supported ? { description: '原说明' } : {})
  })
  const wrapper = mount(
    defineComponent({
      setup: () => () =>
        h(ElForm, { model: value }, () =>
          h(AgentConfigFields, {
            modelValue: value,
            models: [],
            'onUpdate:modelValue': (next: typeof value) => Object.assign(value, next)
          })
        )
    })
  )
  try {
    const field = wrapper
      .findAllComponents(ElFormItem)
      .find((item) => item.props('label') === '说明')
    expect(Boolean(field)).toBe(supported)
    if (field) {
      await field.getComponent(ElInput).setValue('新说明')
      expect(value.description).toBe('新说明')
    } else expect(value).not.toHaveProperty('description')
  } finally {
    wrapper.unmount()
  }
})

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
  const value = reactive({
    name: '测试',
    system_prompt: '职责',
    model_config_id: '' as string | null
  })
  const wrapper = mount(
    defineComponent({
      setup: () => () =>
        h(AgentConfigFields, {
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
