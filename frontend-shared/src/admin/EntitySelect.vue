<script setup lang="ts">
import FeedbackNotice from '../feedback/FeedbackNotice.vue'
import { computed, onBeforeUnmount, ref, watch, useAttrs } from 'vue'
import { ElOption, ElSelect, ElButton } from 'element-plus'
import type { EntityOption } from './presentation'
import { HttpClientError } from '../utils/http'
defineOptions({ inheritAttrs: false })
const attrs = useAttrs()
const props = withDefaults(
  defineProps<{
    modelValue?: string | null
    options?: EntityOption[]
    load?: (query: string, signal?: AbortSignal) => Promise<EntityOption[]>
    resolve?: (id: string) => Promise<EntityOption | undefined>
    placeholder?: string
    disabled?: boolean
    clearable?: boolean
  }>(),
  { options: () => [], placeholder: '搜索名称并选择', clearable: true }
)
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()
const remoteOptions = ref<EntityOption[]>([]),
  selected = ref<EntityOption>(),
  select = ref<InstanceType<typeof ElSelect>>(),
  expanded = ref(false),
  loading = ref(false),
  searchError = ref(''),
  resolveError = ref('')
const error = computed(() => resolveError.value || searchError.value)
let generation = 0,
  resolveGeneration = 0,
  searchQuery = '',
  controller: AbortController | undefined,
  disposed = false
const choices = computed(() => {
  const values = props.load ? remoteOptions.value : props.options
  if (
    selected.value &&
    selected.value.id === props.modelValue &&
    !values.some((item) => item.id === selected.value!.id)
  )
    return [selected.value, ...values]
  return values
})
async function search(query = '') {
  if (!props.load) return
  searchQuery = query
  selected.value = choices.value.find((item) => item.id === props.modelValue) ?? selected.value
  remoteOptions.value = []
  controller?.abort()
  controller = new AbortController()
  const version = ++generation
  loading.value = true
  searchError.value = ''
  try {
    const values = await props.load(query, controller.signal)
    if (version === generation && !disposed) remoteOptions.value = values
  } catch (reason) {
    if (reason instanceof HttpClientError) reason.claimFeedback()
    if (version === generation && !disposed)
      searchError.value =
        reason instanceof Error &&
        !(reason instanceof TypeError) &&
        !(reason instanceof SyntaxError)
          ? reason.message
          : '未能加载选项，请点击“重新加载选项”重试'
  } finally {
    if (version === generation && !disposed) loading.value = false
  }
}
watch(
  () => props.load,
  () => {
    remoteOptions.value = []
    void search(searchQuery)
  },
  { immediate: true }
)
async function resolveSelected() {
  const id = props.modelValue,
    resolve = props.resolve
  const current = ++resolveGeneration
  resolveError.value = ''
  selected.value = choices.value.find((item) => item.id === id)
  if (!id || !resolve) return
  try {
    const option = await resolve(id)
    if (!disposed && current === resolveGeneration && props.modelValue === id) {
      selected.value = option
      if (!option)
        resolveError.value = '无法找到已选项，请重新选择或联系管理员确认权限。原值尚未更改。'
    }
  } catch (reason) {
    if (reason instanceof HttpClientError) reason.claimFeedback()
    if (!disposed && current === resolveGeneration && props.modelValue === id)
      resolveError.value =
        reason instanceof Error &&
        !(reason instanceof TypeError) &&
        !(reason instanceof SyntaxError)
          ? reason.message
          : '未能读取已选项名称，请点击“重新加载选项”重试。原值尚未更改。'
  }
}
watch(() => [props.modelValue, props.resolve] as const, resolveSelected, { immediate: true })
async function retry() {
  await Promise.all([search(searchQuery), resolveSelected()])
}
onBeforeUnmount(() => {
  disposed = true
  generation++
  controller?.abort()
})
function change(value: string) {
  selected.value = choices.value.find((item) => item.id === value)
  emit('update:modelValue', value || '')
}
</script>
<template>
  <div class="entity-select">
    <ElSelect
      ref="select"
      v-bind="attrs"
      :model-value="modelValue || undefined"
      filterable
      :remote="!!load"
      :remote-method="search"
      :loading="loading"
      :disabled="disabled"
      :automatic-dropdown="!!searchError"
      :clearable="clearable"
      fit-input-width
      popper-class="entity-select-options"
      :placeholder="placeholder"
      :no-data-text="error ? '选项未加载，请重新加载选项' : '暂无可选项'"
      no-match-text="未找到匹配选项，请更换关键词"
      @visible-change="expanded = $event"
      @update:model-value="change"
    >
      <template v-if="searchError" #header>
        <ElButton link :loading="loading" @mousedown.prevent @click="retry">重新加载选项</ElButton>
      </template>
      <ElOption v-for="option in choices" :key="option.id" :value="option.id" :label="option.label"
        ><span class="entity-label">{{ option.label }}</span
        ><small v-if="option.description" class="entity-description">{{
          option.description
        }}</small></ElOption
      > </ElSelect
    ><FeedbackNotice
      v-if="resolveError"
      :title="resolveError"
      type="error"
      :closable="false"
    /><FeedbackNotice
      v-if="searchError && searchError !== resolveError"
      :title="searchError"
      type="error"
      :closable="false"
    /><ElButton
      v-if="resolveError && resolve"
      link
      :disabled="disabled"
      :loading="loading"
      @click="retry"
      >重新加载选项</ElButton
    ><ElButton v-if="searchError && !expanded" link :disabled="disabled" @click="select?.focus()"
      >重新搜索选项</ElButton
    >
  </div>
</template>
<style scoped>
.entity-select {
  width: 100%;
  min-width: 160px;
}
.entity-description {
  display: block;
  color: var(--el-text-color-secondary);
}
:global(.entity-select-options) {
  max-width: calc(100vw - 32px);
}
:global(.entity-select-options .el-select-dropdown__item) {
  height: auto;
  min-height: var(--el-component-size);
  padding-block: 8px;
  line-height: 1.6;
  white-space: normal;
  overflow-wrap: anywhere;
}
.entity-label {
  display: block;
}
</style>
