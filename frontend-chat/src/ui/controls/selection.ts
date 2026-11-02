import {
  computed,
  defineComponent,
  h,
  inject,
  nextTick,
  onBeforeUnmount,
  onMounted,
  provide,
  reactive,
  ref,
  Teleport,
  useId,
  watch,
  type InjectionKey,
  type PropType,
  type Ref
} from 'vue'
import { Check, ChevronDown, Search, X } from 'lucide-vue-next'
import { useFieldControl } from './forms'

type Value = string | number | boolean | undefined
interface Option {
  id: string
  value: Value
  label: string
  searchText?: string
  disabled?: boolean
}
interface SelectContext {
  selected: Ref<Value>
  active: Ref<string>
  search: Ref<string>
  options: Map<string, Option>
  choose(value: Value): void
}
const selectKey: InjectionKey<SelectContext> = Symbol('chat-select')

/** 自定义选择器保持值的原始类型，支持键盘导航、筛选及跨滚动容器定位。 */
export const ChatSelect = defineComponent({
  name: 'ChatSelect',
  inheritAttrs: false,
  props: {
    modelValue: { type: [String, Number, Boolean], default: undefined },
    options: Array as PropType<{ value: Value; label: string; searchText?: string; disabled?: boolean }[]>,
    disabled: Boolean,
    placeholder: { type: String, default: '请选择' },
    filterable: Boolean,
    clearable: Boolean,
    valueOnClear: Function
  },
  emits: ['update:modelValue', 'change'],
  setup(props, { attrs, slots, emit }) {
    const { form, field } = useFieldControl(),
      open = ref(false),
      active = ref(''),
      search = ref(''),
      trigger = ref<HTMLButtonElement>(),
      searchInput = ref<HTMLInputElement>(),
      popup = ref<HTMLElement>(),
      options = reactive(new Map<string, Option>()),
      id = `select-${useId()}`,
      position = ref<Record<string, string>>({})
    const disabled = computed(() => props.disabled || form?.disabled.value)
    const available = computed(() =>
      [
        ...(props.options?.map((option, index) => ({ ...option, id: `${id}-${index}` })) ??
          options.values())
      ].filter(
        (option) =>
          !option.disabled &&
          `${option.label} ${option.searchText ?? ''}`.toLocaleLowerCase().includes(search.value.toLocaleLowerCase())
      )
    )
    const selected = computed(() =>
      (props.options ?? [...options.values()]).find((option) =>
        Object.is(option.value, props.modelValue)
      )
    )
    const choose = (value: Value) => {
      emit('update:modelValue', value)
      emit('change', value)
      open.value = false
      search.value = ''
      trigger.value?.focus()
    }
    provide(selectKey, {
      selected: computed(() => props.modelValue),
      active,
      search,
      options,
      choose
    })
    const place = () => {
      const rect = trigger.value?.getBoundingClientRect()
      if (!rect) return
      const below = innerHeight - rect.bottom,
        above = rect.top,
        upward = below < 220 && above > below
      position.value = {
        left: `${Math.max(8, Math.min(rect.left, innerWidth - rect.width - 8))}px`,
        width: `${Math.min(rect.width, innerWidth - 16)}px`,
        maxHeight: `${Math.max(80, Math.min(320, (upward ? above : below) - 16))}px`,
        ...(upward
          ? { bottom: `${innerHeight - rect.top + 8}px` }
          : { top: `${rect.bottom + 8}px` })
      }
    }
    const show = (query = '') => {
      if (disabled.value) return
      open.value = true
      search.value = query
      active.value =
        available.value.find((option) => Object.is(option.value, props.modelValue))?.id ||
        available.value[0]?.id ||
        ''
      place()
      if (props.filterable)
        void nextTick(() => {
          if (!open.value) return
          searchInput.value?.focus()
          searchInput.value?.setSelectionRange(search.value.length, search.value.length)
        })
    }
    const keydown = (event: KeyboardEvent) => {
      if (event.isComposing || event.keyCode === 229) return
      const inSearch = event.target === searchInput.value
      if (
        ['ArrowDown', 'ArrowUp', 'Home', 'End', 'Enter', ' ', 'Escape', 'Tab'].includes(event.key)
      ) {
        if (event.key === 'Tab') {
          open.value = false
          // Teleport 中的输入框从触发按钮的位置继续正常 Tab 顺序。
          if (inSearch) {
            trigger.value?.focus()
            if (event.shiftKey) {
              event.preventDefault()
              event.stopPropagation()
            }
          }
          return
        }
        if (event.key === 'Escape') {
          if (open.value) {
            event.stopPropagation()
            event.preventDefault()
          }
          open.value = false
          trigger.value?.focus()
          return
        }
        if (inSearch && [' ', 'Home', 'End'].includes(event.key)) return
        event.preventDefault()
        if (!open.value) {
          show()
          return
        }
        if (event.key === 'Enter' || event.key === ' ') {
          const option = available.value.find((option) => option.id === active.value)
          if (option) choose(option.value)
          return
        }
        const index = available.value.findIndex((option) => option.id === active.value),
          length = available.value.length
        active.value =
          available.value[
            event.key === 'Home'
              ? 0
              : event.key === 'End'
                ? length - 1
                : (index + (event.key === 'ArrowUp' ? -1 : 1) + length) % length
          ]?.id || ''
        void nextTick(() =>
          document.getElementById(active.value)?.scrollIntoView?.({ block: 'nearest' })
        )
      } else if (
        !inSearch &&
        event.key.length === 1 &&
        !event.ctrlKey &&
        !event.metaKey &&
        !event.altKey
      ) {
        if (props.filterable) {
          event.preventDefault()
          show(event.key)
          return
        }
        show()
        const option = available.value.find((option) =>
          option.label.toLocaleLowerCase().startsWith(event.key.toLocaleLowerCase())
        )
        if (option) active.value = option.id
      }
    }
    const outside = (event: PointerEvent) => {
      if (
        !trigger.value?.contains(event.target as Node) &&
        !popup.value?.contains(event.target as Node)
      )
        open.value = false
    }
    onMounted(() => {
      document.addEventListener('pointerdown', outside)
      window.addEventListener('resize', place)
      window.addEventListener('scroll', place, true)
    })
    onBeforeUnmount(() => {
      document.removeEventListener('pointerdown', outside)
      window.removeEventListener('resize', place)
      window.removeEventListener('scroll', place, true)
    })
    watch(search, () => {
      active.value = available.value[0]?.id || ''
    })
    return () =>
      h('div', { class: ['chat-select', attrs.class] }, [
        h(
          'button',
          {
            ...attrs,
            ref: trigger,
            id: attrs.id || field?.id,
            type: 'button',
            role: 'combobox',
            'aria-labelledby': attrs['aria-label'] ? undefined : field?.labelId,
            'aria-expanded': open.value,
            'aria-controls': `${id}-options`,
            'aria-haspopup': 'listbox',
            'aria-activedescendant': open.value ? active.value : undefined,
            'aria-invalid': field?.error.value ? 'true' : undefined,
            'aria-describedby':
              [attrs['aria-describedby'], field?.error.value ? `${field.id}-error` : '']
                .filter(Boolean)
                .join(' ') || undefined,
            disabled: disabled.value,
            class: ['chat-select__trigger', { 'is-placeholder': !selected.value }],
            onClick: () => (open.value ? (open.value = false) : show()),
            onKeydown: keydown
          },
          [
            h('span', {}, selected.value?.label || props.placeholder),
            h(ChevronDown, { size: 16, 'aria-hidden': true })
          ]
        ),
        props.clearable &&
          selected.value &&
          h(
            'button',
            {
              type: 'button',
              class: 'select-clear',
              disabled: disabled.value,
              'aria-label': '清空选择',
              onClick: () => choose(props.valueOnClear ? props.valueOnClear() : undefined)
            },
            h(X, { size: 12 })
          ),
        h(
          Teleport,
          {
            to: trigger.value?.closest('dialog, .artifact-panel') || 'body',
            disabled: !open.value
          },
          h(
            'div',
            {
              ref: popup,
              id,
              class: 'chat-select__popup',
              style: open.value ? position.value : { display: 'none' },
              onKeydown: keydown
            },
            [
              props.filterable &&
                h('div', { class: 'select-search' }, [
                  h(Search, { size: 16, 'aria-hidden': true }),
                  h('input', {
                    ref: searchInput,
                    value: search.value,
                    placeholder: '搜索',
                    'aria-label': '搜索选项',
                    onInput: (event: Event) => {
                      search.value = (event.target as HTMLInputElement).value
                    },
                    role: 'combobox',
                    'aria-controls': `${id}-options`,
                    'aria-expanded': open.value,
                    'aria-activedescendant': active.value
                  })
                ]),
              h(
                'div',
                {
                  role: 'listbox',
                  id: `${id}-options`,
                  'aria-label': attrs['aria-label'] || '可选项'
                },
                props.options
                  ? props.options.map((option, index) =>
                      h(
                        'button',
                        {
                          type: 'button',
                          id: `${id}-${index}`,
                          role: 'option',
                          'aria-selected': Object.is(option.value, props.modelValue),
                          disabled: option.disabled,
                          tabindex: -1,
                          class: [
                            'chat-option',
                            { 'is-active': active.value === `${id}-${index}` }
                          ],
                          style: `${option.label} ${option.searchText ?? ''}`
                            .toLocaleLowerCase()
                            .includes(search.value.toLocaleLowerCase())
                            ? undefined
                            : { display: 'none' },
                          onClick: () => choose(option.value)
                        },
                        [
                          h('span', {}, option.label),
                          Object.is(option.value, props.modelValue) &&
                            h(Check, { size: 14, 'aria-hidden': true })
                        ]
                      )
                    )
                  : slots.default?.()
              ),
              !available.value.length && h('p', { class: 'select-empty' }, '没有可选项')
            ]
          )
        )
      ])
  }
})

export const ChatOption = defineComponent({
  name: 'ChatOption',
  props: { value: [String, Number, Boolean], label: String, searchText: String, disabled: Boolean },
  setup(props, { slots }) {
    const select = inject(selectKey)!,
      id = `option-${useId()}`
    watch(
      () => [props.value, props.label, props.searchText, props.disabled],
      () =>
        select.options.set(id, {
          id,
          value: props.value,
          label: props.label || String(props.value),
          searchText: props.searchText,
          disabled: props.disabled
        }),
      { immediate: true }
    )
    onBeforeUnmount(() => select.options.delete(id))
    return () =>
      h(
        'button',
        {
          id,
          type: 'button',
          role: 'option',
          class: ['chat-option', { 'is-active': select.active.value === id }],
          disabled: props.disabled,
          tabindex: -1,
          'aria-selected': Object.is(select.selected.value, props.value),
          style: `${props.label || String(props.value)} ${props.searchText ?? ''}`
            .toLocaleLowerCase()
            .includes(select.search.value.toLocaleLowerCase())
            ? undefined
            : { display: 'none' },
          onClick: () => select.choose(props.value)
        },
        [
          h('span', {}, slots.default?.() || props.label),
          Object.is(select.selected.value, props.value) &&
            h(Check, { size: 14, 'aria-hidden': true })
        ]
      )
  }
})
export const ChatOptionGroup = defineComponent({
  name: 'ChatOptionGroup',
  props: { label: String },
  setup:
    (props, { slots }) =>
    () =>
      h('div', { role: 'group', 'aria-label': props.label }, [
        h('p', { class: 'chat-option-group' }, props.label),
        slots.default?.()
      ])
})
