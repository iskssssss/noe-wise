<script lang="ts">
import { defineComponent, onScopeDispose, watch, type PropType } from 'vue'
import { showMessage, type FeedbackType } from './index'

/** 将所属区域的反馈状态交给顶部 Message，不在内容区重复渲染。 */
export default defineComponent({
  inheritAttrs: false,
  props: {
    title: { type: String, default: '' },
    description: { type: String, default: '' },
    type: { type: String as PropType<FeedbackType>, default: 'info' }
  },
  emits: ['close'],
  setup(props, { slots, emit }) {
    const owner = Symbol('feedback')
    let release: (() => void) | undefined
    watch(
      () => [props.title, props.description, props.type] as const,
      () => {
        release?.()
        release = undefined
        const summary = [props.title, props.description].filter(Boolean).join('。')
        if (summary)
          release = showMessage(
            props.type,
            summary,
            { footer: slots.default, onClose: () => emit('close') },
            owner
          )
      },
      { immediate: true, flush: 'post' }
    )
    onScopeDispose(() => release?.())
    return () => null
  }
})
</script>
