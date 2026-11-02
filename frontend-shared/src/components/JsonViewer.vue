<script setup lang="ts">
import FeedbackNotice from '../feedback/FeedbackNotice.vue'
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ElAlert, ElButton } from 'element-plus'
import CodeEditor from './CodeEditor.vue'
import { formatJsonText } from './jsonFormatting'

const props = withDefaults(defineProps<{ value?: unknown; label?: string; height?: string }>(), {
  label: 'JSON 内容',
  height: '420px'
})
const editor = ref<InstanceType<typeof CodeEditor>>()
const originalMode = ref(false)
const copying = ref(false)
const copyMessage = ref('')
const copyError = ref('')
let copyGeneration = 0

const presentation = computed(() => {
  if (props.value === undefined) return { original: '', formatted: null, error: '', missing: true }
  let original: string
  try {
    const source = typeof props.value === 'string' ? props.value : JSON.stringify(props.value)
    if (source === undefined) throw new TypeError('Unsupported JSON value')
    original = source
  } catch {
    return {
      original: '',
      formatted: null,
      missing: false,
      error: '无法将此内容表示为 JSON，请提供原始文本。'
    }
  }
  return { original, formatted: formatJsonText(original), error: '', missing: false }
})
const isJson = computed(() => presentation.value.formatted !== null)
const displayedText = computed(() =>
  originalMode.value
    ? presentation.value.original
    : (presentation.value.formatted ?? presentation.value.original)
)
const canFold = computed(
  () =>
    isJson.value &&
    !originalMode.value &&
    (displayedText.value.startsWith('{') || displayedText.value.startsWith('['))
)

watch(presentation, () => {
  originalMode.value = false
  copyGeneration++
  copying.value = false
  copyMessage.value = ''
  copyError.value = ''
})
onBeforeUnmount(() => copyGeneration++)

async function copyOriginal() {
  if (copying.value || presentation.value.missing || presentation.value.error) return
  const generation = ++copyGeneration
  const original = presentation.value.original
  copying.value = true
  copyMessage.value = ''
  copyError.value = ''
  try {
    if (!navigator.clipboard?.writeText) throw new Error('Clipboard unavailable')
    await navigator.clipboard.writeText(original)
    if (generation === copyGeneration) copyMessage.value = '已复制原文'
  } catch {
    if (generation === copyGeneration) copyError.value = '复制失败，请在原文中选择内容后手动复制。'
  } finally {
    if (generation === copyGeneration) copying.value = false
  }
}
</script>

<template>
  <section class="json-viewer" :aria-label="label">
    <ElAlert v-if="presentation.error" :title="presentation.error" type="error" :closable="false" />
    <p v-else-if="presentation.missing" class="viewer-note">未记录</p>
    <template v-else>
      <div class="viewer-toolbar" role="group" :aria-label="label + '查看操作'">
        <span class="viewer-note">{{
          isJson ? (originalMode ? '完整原文' : '已格式化 JSON') : '文本'
        }}</span>
        <div class="viewer-actions">
          <template v-if="canFold">
            <ElButton size="small" @click="editor?.foldAll()">全部折叠</ElButton>
            <ElButton size="small" @click="editor?.unfoldAll()">全部展开</ElButton>
          </template>
          <ElButton
            v-if="isJson"
            size="small"
            :aria-pressed="originalMode"
            @click="originalMode = !originalMode"
          >
            {{ originalMode ? '格式化显示' : '查看原文' }}
          </ElButton>
          <ElButton size="small" :loading="copying" @click="copyOriginal">复制原文</ElButton>
        </div>
      </div>
      <p v-if="canFold" class="viewer-note fold-help">使用行号旁的箭头展开或折叠对象与数组。</p>
      <FeedbackNotice v-if="copyMessage" :title="copyMessage" type="success" />
      <FeedbackNotice
        v-if="copyError"
        :title="copyError"
        type="error"
        :closable="false"
        class="copy-error"
      />
      <CodeEditor
        ref="editor"
        :model-value="displayedText"
        :language="isJson && !originalMode ? 'json' : 'text'"
        :label="label"
        :height="height"
        readonly
      />
    </template>
  </section>
</template>

<style scoped>
.json-viewer {
  min-width: 0;
}
.viewer-toolbar,
.viewer-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}
.viewer-toolbar {
  justify-content: space-between;
  margin-bottom: 8px;
}
.viewer-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}
.viewer-note {
  margin: 0;
  color: var(--admin-muted);
  font-size: 13px;
  line-height: 1.6;
}
.fold-help,
.copy-status,
.copy-error {
  margin-bottom: 8px;
}
</style>
