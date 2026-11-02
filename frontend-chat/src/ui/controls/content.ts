import {
  computed,
  defineComponent,
  h,
  inject,
  onBeforeUnmount,
  provide,
  reactive,
  ref,
  useId,
  watch,
  type InjectionKey,
  type PropType,
  type Ref,
  type VNode
} from 'vue'
import { ChevronDown, Upload } from 'lucide-vue-next'
import { ChatDrawer } from './overlays'
import { useFieldControl } from './forms'

export const ChatUpload = defineComponent({
  name: 'ChatUpload',
  props: { disabled: Boolean, multiple: Boolean, accept: String },
  emits: ['files'],
  setup(props, { slots, emit }) {
    const input = ref<HTMLInputElement>()
    return () =>
      h(
        'div',
        {
          class: 'chat-upload',
          onClick: () => {
            if (!props.disabled) input.value?.click()
          }
        },
        [
          h('input', {
            ref: input,
            type: 'file',
            hidden: true,
            tabindex: -1,
            multiple: props.multiple,
            accept: props.accept,
            disabled: props.disabled,
            'aria-label': '选择文件',
            onClick: (event: Event) => event.stopPropagation(),
            onChange: (event: Event) => {
              const target = event.target as HTMLInputElement
              emit('files', Array.from(target.files || []))
              target.value = ''
            }
          }),
          slots.default?.() || h(Upload, { size: 18 })
        ]
      )
  }
})

const collapseKey: InjectionKey<{
  values: Ref<(string | number)[]>
  toggle(value: string | number): void
}> = Symbol('chat-collapse')
export const ChatCollapse = defineComponent({
  name: 'ChatCollapse',
  props: { modelValue: { type: Array as PropType<(string | number)[]>, default: () => [] } },
  emits: ['change'],
  setup(props, { slots, emit }) {
    provide(collapseKey, {
      values: computed(() => props.modelValue),
      toggle(value) {
        emit(
          'change',
          props.modelValue.includes(value)
            ? props.modelValue.filter((item) => item !== value)
            : [...props.modelValue, value]
        )
      }
    })
    return () => h('div', { class: 'chat-collapse' }, slots.default?.())
  }
})
export const ChatCollapseItem = defineComponent({
  name: 'ChatCollapseItem',
  props: { name: { type: [String, Number], required: true } },
  setup(props, { slots }) {
    const collapse = inject(collapseKey)!,
      id = `collapse-${useId()}`,
      open = computed(() => collapse.values.value.includes(props.name))
    return () =>
      h('section', { class: 'chat-collapse-item' }, [
        h(
          'button',
          {
            type: 'button',
            class: 'chat-collapse-item__header',
            'aria-expanded': open.value,
            'aria-controls': id,
            onClick: () => collapse.toggle(props.name)
          },
          [
            slots.title?.(),
            h(ChevronDown, {
              class: ['chat-collapse-item__arrow', { 'is-open': open.value }],
              size: 14,
              'aria-hidden': true
            })
          ]
        ),
        h(
          'div',
          { id, hidden: !open.value, class: 'chat-collapse-item__content' },
          slots.default?.()
        )
      ])
  }
})

interface Tab {
  name: string
  label: string
}
const tabsKey: InjectionKey<{ active: Ref<string>; tabs: Map<string, Tab>; id: string }> =
  Symbol('chat-tabs')
export const ChatTabs = defineComponent({
  name: 'ChatTabs',
  props: {
    modelValue: { type: String, default: '' },
    beforeLeave: Function as PropType<() => boolean | Promise<boolean>>
  },
  emits: ['update:modelValue', 'tab-change'],
  setup(props, { slots, emit }) {
    const tabs = reactive(new Map<string, Tab>()),
      id = `tabs-${useId()}`
    provide(tabsKey, { active: computed(() => props.modelValue), tabs, id })
    const choose = async (name: string) => {
      if (props.beforeLeave && !(await props.beforeLeave())) return
      emit('update:modelValue', name)
      emit('tab-change', name)
    }
    return () =>
      h('div', { class: 'chat-tabs' }, [
        h(
          'div',
          { role: 'tablist', class: 'chat-tabs__nav', 'aria-label': '会话工具' },
          [...tabs.values()].map((tab, index, all) =>
            h(
              'button',
              {
                type: 'button',
                id: `${id}-${tab.name}`,
                role: 'tab',
                'aria-selected': props.modelValue === tab.name,
                'aria-controls': `${id}-panel-${tab.name}`,
                tabindex: props.modelValue === tab.name ? 0 : -1,
                onClick: () => void choose(tab.name),
                onKeydown: (event: KeyboardEvent) => {
                  const offset = event.key === 'ArrowRight' ? 1 : event.key === 'ArrowLeft' ? -1 : 0
                  if (!offset && event.key !== 'Home' && event.key !== 'End') return
                  event.preventDefault()
                  const next =
                    all[
                      event.key === 'Home'
                        ? 0
                        : event.key === 'End'
                          ? all.length - 1
                          : (index + offset + all.length) % all.length
                    ]!
                  void choose(next.name).then(() =>
                    document.getElementById(`${id}-${next.name}`)?.focus()
                  )
                }
              },
              tab.label
            )
          )
        ),
        slots.default?.()
      ])
  }
})
export const ChatTabPane = defineComponent({
  name: 'ChatTabPane',
  props: {
    name: { type: String, required: true },
    label: { type: String, default: '' },
    lazy: Boolean
  },
  setup(props, { slots }) {
    const tabs = inject(tabsKey)!,
      loaded = ref(!props.lazy)
    watch(
      () => props.label,
      () => tabs.tabs.set(props.name, { name: props.name, label: props.label }),
      { immediate: true }
    )
    watch(
      tabs.active,
      (active) => {
        if (active === props.name) loaded.value = true
      },
      { immediate: true }
    )
    onBeforeUnmount(() => tabs.tabs.delete(props.name))
    return () =>
      h(
        'section',
        {
          id: `${tabs.id}-panel-${props.name}`,
          role: 'tabpanel',
          'aria-labelledby': `${tabs.id}-${props.name}`,
          hidden: tabs.active.value !== props.name,
          tabindex: 0,
          class: 'chat-tab-panel'
        },
        loaded.value ? slots.default?.() : []
      )
  }
})

export const ChatDescriptions = defineComponent({
  name: 'ChatDescriptions',
  props: { title: String, column: Number, border: Boolean },
  setup:
    (props, { slots }) =>
    () =>
      h('section', { class: 'chat-descriptions' }, [
        props.title && h('h3', {}, props.title),
        h('dl', {}, slots.default?.())
      ])
})
export const ChatDescriptionsItem = defineComponent({
  name: 'ChatDescriptionsItem',
  props: { label: String },
  setup:
    (props, { slots }) =>
    () =>
      h('div', {}, [h('dt', {}, props.label), h('dd', {}, slots.default?.())])
})

/** 表格列直接取自公开结果结构；单独滚动避免长结果撑宽会话。 */
export const ChatTableColumn = defineComponent({
  name: 'ChatTableColumn',
  props: { prop: String, label: String },
  setup: () => () => null
})
export const ChatTable = defineComponent({
  name: 'ChatTable',
  props: { data: { type: Array as PropType<Record<string, unknown>[]>, default: () => [] } },
  setup(props, { slots }) {
    return () => {
      const declarations = slots.default?.() || [],
        columns: VNode[] = []
      const collect = (nodes: VNode[]) => {
        for (const node of nodes) {
          if (node.type === ChatTableColumn) columns.push(node)
          else if (Array.isArray(node.children)) collect(node.children as VNode[])
        }
      }
      collect(declarations)
      return h(
        'div',
        { class: 'chat-table', tabindex: 0, 'aria-label': '过程结果表格' },
        h('table', {}, [
          h(
            'thead',
            {},
            h(
              'tr',
              {},
              columns.map((column) => h('th', { scope: 'col' }, column.props?.label))
            )
          ),
          h(
            'tbody',
            {},
            props.data.map((row) =>
              h(
                'tr',
                {},
                columns.map((column) => {
                  const value = row[String(column.props?.prop)]
                  return value === null
                    ? h('td', { 'aria-label': '无值' }, '—')
                    : h('td', {}, String(value ?? ''))
                })
              )
            )
          )
        ])
      )
    }
  }
})

export const ChatImage = defineComponent({
  name: 'ChatImage',
  props: {
    src: String,
    alt: String,
    fit: String,
    previewSrcList: Array,
    previewTeleported: Boolean
  },
  setup(props, { slots }) {
    const expanded = ref(false),
      failed = ref(false)
    const { form } = useFieldControl()
    return () =>
      h('div', { class: 'chat-image' }, [
        failed.value
          ? slots.error?.()
          : h(
              'button',
              {
                type: 'button',
                disabled: form?.disabled.value,
                'aria-label': `放大${props.alt || '图片'}`,
                onClick: () => {
                  expanded.value = true
                }
              },
              h('img', {
                src: props.src,
                alt: props.alt,
                loading: 'lazy',
                style: { objectFit: props.fit || 'contain' },
                onError: () => {
                  failed.value = true
                }
              })
            ),
        h(
          ChatDrawer,
          {
            modelValue: expanded.value,
            centered: true,
            title: props.alt || '会话图片',
            size: 'min(960px, calc(100vw - 32px))',
            'onUpdate:modelValue': (value: boolean) => {
              expanded.value = value
            }
          },
          { default: () => h('img', { src: props.src, alt: props.alt, class: 'image-preview' }) }
        )
      ])
  }
})
