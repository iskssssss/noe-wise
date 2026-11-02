import {
  computed,
  defineComponent,
  h,
  inject,
  mergeProps,
  nextTick,
  onBeforeUnmount,
  provide,
  ref,
  useId,
  watch,
  type InjectionKey,
  type PropType,
  type Ref
} from 'vue'
import { Check, Eye, EyeOff, LoaderCircle, Minus, Plus } from 'lucide-vue-next'
import { useTooltip } from './tooltip'

export interface FormItemRule {
  required?: boolean
  whitespace?: boolean
  type?: string
  min?: number
  max?: number
  message?: string
  trigger?: string | string[]
  validator?: (rule: FormItemRule, value: unknown, callback: (error?: Error) => void) => void
}
export type FormRules = Record<string, FormItemRule | FormItemRule[]>
export interface FormInstance {
  $el: HTMLElement
  validate(): Promise<boolean>
  clearValidate(fields?: string | string[]): void
}
interface Field {
  validate(): Promise<boolean>
  clear(): void
}
interface FormContext {
  disabled: Ref<boolean>
  model: Ref<Record<string, unknown>>
  rules: Ref<FormRules>
  fields: Set<Field>
}
const formKey: InjectionKey<FormContext> = Symbol('chat-form')
const fieldKey: InjectionKey<{ id: string; labelId: string; error: Ref<string> }> =
  Symbol('chat-field')

/** 会话表单集中校验已登记字段；禁用状态向所有控件传递。 */
export const ChatForm = defineComponent({
  name: 'ChatForm',
  props: {
    model: { type: Object as PropType<Record<string, unknown>>, default: () => ({}) },
    rules: { type: Object as PropType<FormRules>, default: () => ({}) },
    disabled: Boolean,
    labelPosition: String
  },
  setup(props, { slots, expose }) {
    const fields = new Set<Field>()
    const element = ref<HTMLFormElement>()
    provide(formKey, {
      disabled: computed(() => props.disabled),
      model: computed(() => props.model),
      rules: computed(() => props.rules),
      fields
    })
    expose({
      async validate() {
        const results = await Promise.all([...fields].map((field) => field.validate()))
        if (results.some((result) => !result)) {
          await nextTick()
          const invalid = element.value?.querySelector<HTMLElement>('[aria-invalid="true"]')
          invalid?.focus()
          invalid?.scrollIntoView?.({ block: 'nearest' })
          throw new Error('请检查输入')
        }
        return true
      },
      clearValidate() {
        fields.forEach((field) => field.clear())
      }
    })
    return () =>
      h('form', { ref: element, class: 'chat-form', novalidate: true }, slots.default?.())
  }
})

/** 字段负责标签、校验错误和帮助文本关联，保留动态 JSON Schema 校验。 */
export const ChatFormItem = defineComponent({
  name: 'ChatFormItem',
  props: {
    label: String,
    prop: [String, Array] as PropType<string | string[]>,
    required: Boolean,
    validateOnBlur: { type: Boolean, default: true },
    rules: [Object, Array] as PropType<FormItemRule | FormItemRule[]>
  },
  setup(props, { slots }) {
    const form = inject(formKey, undefined),
      error = ref(''),
      id = `field-${useId()}`,
      labelId = `${id}-label`
    provide(fieldKey, { id, labelId, error })
    const rules = computed(() => {
      const value = props.rules ?? form?.rules.value[String(props.prop)] ?? []
      return Array.isArray(value) ? value : [value]
    })
    const field: Field = {
      clear() {
        error.value = ''
      },
      async validate() {
        error.value = ''
        if (!props.prop || !form) return true
        const path = Array.isArray(props.prop) ? props.prop : props.prop.split('.')
        const value = path.reduce<unknown>(
          (current, key) =>
            current && typeof current === 'object'
              ? (current as Record<string, unknown>)[key]
              : undefined,
          form.model.value
        )
        try {
          for (const rule of rules.value) {
            if (rule.validator)
              await new Promise<void>((resolve, reject) =>
                rule.validator!(rule, value, (cause) => (cause ? reject(cause) : resolve()))
              )
            else {
              const empty =
                value === undefined ||
                value === null ||
                value === '' ||
                (Array.isArray(value) && !value.length) ||
                (rule.whitespace && !String(value).trim())
              const size =
                rule.type === 'number'
                  ? Number(value)
                  : Array.isArray(value)
                    ? value.length
                    : String(value ?? '').length
              if (
                (rule.required && empty) ||
                (!empty &&
                  ((rule.type === 'number' && !Number.isFinite(Number(value))) ||
                    (rule.min !== undefined && size < rule.min) ||
                    (rule.max !== undefined && size > rule.max)))
              )
                throw new Error(rule.message || '请检查输入')
            }
          }
          return true
        } catch (cause) {
          error.value = cause instanceof Error ? cause.message : '请检查输入'
          return false
        }
      }
    }
    form?.fields.add(field)
    onBeforeUnmount(() => form?.fields.delete(field))
    return () =>
      h('div', { class: ['chat-field', { 'is-error': error.value }] }, [
        props.label &&
          h('label', { id: labelId, for: id, class: 'chat-field__label' }, [
            props.label,
            (props.required || rules.value.some((rule) => rule.required)) &&
              h('span', { 'aria-hidden': 'true', class: 'required-mark' }, ' *')
          ]),
        h(
          'div',
          {
            class: 'chat-field__content',
            onFocusout: () => {
              if (props.validateOnBlur) void field.validate()
            }
          },
          [
            slots.default?.(),
            error.value &&
              h('p', { id: `${id}-error`, role: 'alert', class: 'chat-field__error' }, error.value)
          ]
        )
      ])
  }
})

/** 文本输入把所有输入属性和键盘事件直接交给真实输入元素。 */
export const ChatInput = defineComponent({
  name: 'ChatInput',
  inheritAttrs: false,
  props: {
    modelValue: [String, Number],
    type: { type: String, default: 'text' },
    disabled: Boolean,
    rows: { type: Number, default: 3 },
    autosize: [Boolean, Object],
    clearable: Boolean
  },
  emits: ['update:modelValue', 'input', 'change'],
  setup(props, { attrs, slots, emit, expose }) {
    const form = inject(formKey, undefined),
      field = inject(fieldKey, undefined),
      input = ref<HTMLInputElement | HTMLTextAreaElement>(),
      revealed = ref(false)
    const disabled = computed(() => props.disabled || form?.disabled.value)
    const resize = () => {
      if (props.type !== 'textarea' || !props.autosize || !input.value) return
      input.value.style.height = 'auto'
      input.value.style.height = `${Math.max(32, Math.min(160, input.value.scrollHeight))}px`
    }
    watch(
      () => props.modelValue,
      () => void nextTick(resize),
      { immediate: true }
    )
    expose({ focus: () => input.value?.focus(), input })
    return () =>
      h(
        'div',
        {
          class: [
            props.type === 'textarea' ? 'chat-textarea' : 'chat-input',
            attrs.class,
            { 'is-disabled': disabled.value }
          ]
        },
        [
          slots.prefix?.(),
          h(props.type === 'textarea' ? 'textarea' : 'input', {
            ...attrs,
            class: props.type === 'textarea' ? 'chat-textarea__inner' : 'chat-input__inner',
            ref: input,
            id: attrs.id || field?.id,
            'aria-labelledby': attrs['aria-label'] ? undefined : field?.labelId,
            'aria-invalid': field?.error.value ? 'true' : undefined,
            'aria-describedby':
              [attrs['aria-describedby'], field?.error.value ? `${field.id}-error` : '']
                .filter(Boolean)
                .join(' ') || undefined,
            type: props.type === 'textarea' ? undefined : revealed.value ? 'text' : props.type,
            rows:
              props.autosize && typeof props.autosize === 'object' && 'minRows' in props.autosize
                ? Number(props.autosize.minRows)
                : props.rows,
            disabled: disabled.value,
            value: props.modelValue ?? '',
            onInput: (event: Event) => {
              const value = (event.target as HTMLInputElement).value
              emit('update:modelValue', value)
              emit('input', value)
              resize()
            },
            onChange: (event: Event) => emit('change', (event.target as HTMLInputElement).value)
          }),
          props.type === 'password' &&
            h(
              'button',
              {
                type: 'button',
                class: 'input-action',
                disabled: disabled.value,
                'aria-label': revealed.value ? '隐藏凭据' : '显示凭据',
                onClick: () => {
                  revealed.value = !revealed.value
                }
              },
              h(revealed.value ? EyeOff : Eye, { size: 16, 'aria-hidden': true })
            ),
          props.clearable &&
            props.modelValue &&
            h(
              'button',
              {
                type: 'button',
                class: 'input-action',
                disabled: disabled.value,
                'aria-label': '清空输入',
                onClick: () => emit('update:modelValue', '')
              },
              '×'
            )
        ]
      )
  }
})

export const ChatButton = defineComponent({
  name: 'ChatButton',
  inheritAttrs: false,
  props: {
    type: String,
    nativeType: { type: String, default: 'button' },
    disabled: Boolean,
    loading: Boolean,
    text: Boolean,
    size: String,
    round: Boolean
  },
  setup(props, { slots, attrs }) {
    const form = inject(formKey, undefined)
    const tooltip = useTooltip(
      () => (typeof attrs.title === 'string' ? attrs.title : undefined),
      () => props.disabled || props.loading || form?.disabled.value
    )
    return () =>
      h(
        'button',
        mergeProps(attrs, tooltip.events, {
          title: undefined,
          'data-tooltip': attrs.title,
          'aria-describedby':
            [attrs['aria-describedby'], tooltip.describedBy.value].filter(Boolean).join(' ') ||
            undefined,
          type: props.nativeType,
          disabled: props.disabled || props.loading || form?.disabled.value,
          'aria-busy': props.loading || undefined,
          class: [
            'chat-button',
            props.type && `chat-button--${props.type}`,
            { 'is-text': props.text, 'is-loading': props.loading }
          ]
        }),
        [
          h('span', {}, [
            props.loading && h(LoaderCircle, { class: 'spin', size: 16, 'aria-hidden': true }),
            slots.default?.()
          ]),
          tooltip.render()
        ]
      )
  }
})

const checkboxKey: InjectionKey<{
  value: Ref<unknown[]>
  disabled: Ref<boolean>
  change(value: unknown): void
}> = Symbol('checkbox-group')
export const ChatCheckboxGroup = defineComponent({
  name: 'ChatCheckboxGroup',
  props: {
    modelValue: { type: Array as PropType<unknown[]>, default: () => [] },
    disabled: Boolean
  },
  emits: ['update:modelValue'],
  setup(props, { slots, emit }) {
    const form = inject(formKey, undefined),
      field = inject(fieldKey, undefined)
    provide(checkboxKey, {
      value: computed(() => props.modelValue),
      disabled: computed(() => props.disabled || Boolean(form?.disabled.value)),
      change(value) {
        emit(
          'update:modelValue',
          props.modelValue.includes(value)
            ? props.modelValue.filter((item) => item !== value)
            : [...props.modelValue, value]
        )
      }
    })
    return () =>
      h(
        'div',
        {
          class: 'chat-checkbox-group',
          role: 'group',
          'aria-labelledby': field?.labelId,
          id: field?.id
        },
        slots.default?.()
      )
  }
})
export const ChatCheckbox = defineComponent({
  name: 'ChatCheckbox',
  props: { value: [String, Number, Boolean], label: String, disabled: Boolean },
  setup(props, { slots }) {
    const group = inject(checkboxKey, undefined)
    return () =>
      h(
        'label',
        { class: ['chat-checkbox', { 'is-checked': group?.value.value.includes(props.value) }] },
        [
          h('input', {
            type: 'checkbox',
            checked: group?.value.value.includes(props.value),
            disabled: props.disabled || group?.disabled.value,
            onChange: () => group?.change(props.value)
          }),
          h('span', { class: 'chat-checkbox__box', 'aria-hidden': true }, h(Check, { size: 12 })),
          h('span', { class: 'chat-checkbox__label' }, slots.default?.() || props.label)
        ]
      )
  }
})

/** 数值输入保留空值以便校验，按钮只在合法区间步进。 */
export const ChatInputNumber = defineComponent({
  name: 'ChatInputNumber',
  props: {
    modelValue: Number,
    min: Number,
    max: Number,
    step: { type: Number, default: 1 },
    disabled: Boolean
  },
  emits: ['update:modelValue'],
  setup(props, { emit }) {
    const form = inject(formKey, undefined),
      field = inject(fieldKey, undefined)
    const disabled = computed(() => props.disabled || form?.disabled.value)
    const step = (direction: number) =>
      emit(
        'update:modelValue',
        Math.max(
          props.min ?? -Infinity,
          Math.min(
            props.max ?? Infinity,
            (props.modelValue ?? props.min ?? 0) + direction * props.step
          )
        )
      )
    return () =>
      h('div', { class: 'chat-number' }, [
        h(
          'button',
          {
            type: 'button',
            disabled:
              disabled.value || (props.min !== undefined && Number(props.modelValue) <= props.min),
            'aria-label': '减少',
            onClick: () => step(-1)
          },
          h(Minus, { size: 16 })
        ),
        h('input', {
          id: field?.id,
          'aria-labelledby': field?.labelId,
          'aria-invalid': field?.error.value ? true : undefined,
          'aria-describedby': field?.error.value ? `${field.id}-error` : undefined,
          type: 'number',
          value: props.modelValue ?? '',
          min: props.min,
          max: props.max,
          step: props.step,
          disabled: disabled.value,
          onInput: (event: Event) => {
            const value = (event.target as HTMLInputElement).value
            emit('update:modelValue', value === '' ? undefined : Number(value))
          }
        }),
        h(
          'button',
          {
            type: 'button',
            disabled:
              disabled.value || (props.max !== undefined && Number(props.modelValue) >= props.max),
            'aria-label': '增加',
            onClick: () => step(1)
          },
          h(Plus, { size: 16 })
        )
      ])
  }
})

export function useFieldControl() {
  return { form: inject(formKey, undefined), field: inject(fieldKey, undefined) }
}
