<script setup lang="ts" generic="T extends string | string[]">
import FeedbackNotice from '../feedback/FeedbackNotice.vue'
import { computed, onScopeDispose, ref, watch } from 'vue'
import { HttpClientError } from '../utils/http'
import type { FoundationOption, FoundationOptionPage, FoundationOptionRequest } from './optionTypes'

const props = defineProps<{
  request: FoundationOptionRequest
  path: string
  modelValue: T
  multiple?: boolean
  disabled?: boolean
  clearable?: boolean
  placeholder?: string
  label: string
}>()
const emit = defineEmits<{
  'update:modelValue': [value: T]
  selected: [option: FoundationOption | undefined]
}>()
const rows = ref<FoundationOption[]>([])
const selectedRows = ref<FoundationOption[]>([])
const loading = ref(false)
const searchError = ref('')
const resolveError = ref('')
const error = computed(() => resolveError.value || searchError.value)
const keyword = ref('')
const page = ref(1)
const more = ref(false)
let generation = 0
let selectionGeneration = 0
const selectedIds = computed<string[]>(() => {
  const value: string | string[] = props.modelValue
  return Array.isArray(value) ? value : value ? [value] : []
})
const options = computed(() => [
  ...new Map([...selectedRows.value, ...rows.value].map((row) => [row.id, row])).values()
])
function url(params: URLSearchParams) {
  return `${props.path}${props.path.includes('?') ? '&' : '?'}${params}`
}
async function load(query = keyword.value, append = false) {
  const current = ++generation
  const requestedPage = append ? page.value + 1 : 1
  keyword.value = query
  loading.value = true
  searchError.value = ''
  try {
    const value = await props.request<FoundationOptionPage>(
      'GET',
      url(new URLSearchParams({ keyword: query, page: String(requestedPage) }))
    )
    if (current !== generation) return
    rows.value = append ? [...rows.value, ...value.items] : value.items
    more.value = value.has_more
    page.value = requestedPage
  } catch (cause) {
    if (cause instanceof HttpClientError) cause.claimFeedback()
    if (current === generation) {
      searchError.value = cause instanceof Error ? cause.message : '选项读取失败'
    }
  } finally {
    if (current === generation) loading.value = false
  }
}
/** 搜索不能抹掉已选名称；缺失身份通过同一接口、同一权限重新解析。 */
async function resolveSelected(force = false) {
  const current = ++selectionGeneration
  resolveError.value = ''
  selectedRows.value = options.value.filter((row) => selectedIds.value.includes(row.id))
  const missing = force
    ? selectedIds.value
    : selectedIds.value.filter((id) => !selectedRows.value.some((row) => row.id === id))
  if (!missing.length) return
  try {
    const resolved: FoundationOption[] = []
    for (let start = 0; start < missing.length; start += 100) {
      const params = new URLSearchParams()
      missing.slice(start, start + 100).forEach((id) => params.append('ids', id))
      const value = await props.request<FoundationOptionPage>('GET', url(params))
      if (current !== selectionGeneration) return
      resolved.push(...value.items)
    }
    if (current === selectionGeneration)
      selectedRows.value = [
        ...selectedRows.value.filter((row) => !missing.includes(row.id)),
        ...resolved
      ]
    if (
      current === selectionGeneration &&
      missing.some((id) => !resolved.some((row) => row.id === id))
    )
      resolveError.value = '部分已选项目已不可用或不在当前授权范围，请重新选择'
  } catch (cause) {
    if (cause instanceof HttpClientError) cause.claimFeedback()
    if (current === selectionGeneration) {
      resolveError.value = cause instanceof Error ? cause.message : '已选项目读取失败'
    }
  }
}
async function retry() {
  await Promise.all([load(keyword.value), resolveSelected(true)])
}
function select(input: string | string[] | undefined) {
  const value = input ?? (props.multiple ? [] : '')
  emit('update:modelValue', value as T)
  emit(
    'selected',
    typeof value === 'string' ? options.value.find((row) => row.id === value) : undefined
  )
}
watch(
  () => [props.path, props.request],
  () => {
    generation++
    selectionGeneration++
    rows.value = []
    selectedRows.value = []
    keyword.value = ''
    void load('')
    void resolveSelected()
  },
  { immediate: true }
)
watch(selectedIds, () => void resolveSelected(), { deep: true })
onScopeDispose(() => {
  generation++
  selectionGeneration++
})
</script>

<template>
  <div class="foundation-option-select">
    <ElSelect
      :model-value="modelValue"
      :multiple="multiple"
      :disabled="disabled"
      :aria-label="label"
      :placeholder="placeholder || '按名称或编码搜索'"
      filterable
      remote
      :clearable="clearable !== false"
      :remote-method="(query: string) => load(query)"
      :loading="loading"
      collapse-tags
      collapse-tags-tooltip
      @update:model-value="select"
    >
      <ElOption
        v-for="option in options"
        :key="option.id"
        :value="option.id"
        :label="option.name === option.code ? option.name : `${option.name} · ${option.code}`"
      >
        <span>{{ option.name }}</span
        ><span class="admin-field-help"> · {{ option.code }} · {{ option.id }}</span>
      </ElOption>
      <template #footer
        ><ElButton v-if="more" text :loading="loading" @click="load(keyword, true)"
          >加载更多</ElButton
        ><span v-else class="admin-field-help">{{
          loading
            ? '正在读取选项…'
            : searchError
              ? '搜索结果尚未读取成功'
              : rows.length
                ? '已显示全部搜索结果'
                : keyword
                  ? '当前关键词下没有可选项目，可换个关键词搜索'
                  : '当前没有可选项目'
        }}</span></template
      >
    </ElSelect>
    <FeedbackNotice v-if="error" :title="error" type="error">
      <ElButton link :loading="loading" @click="retry">重新读取选项</ElButton>
    </FeedbackNotice>
  </div>
</template>

<style scoped>
.foundation-option-select {
  min-width: 0;
  width: 100%;
}
</style>
