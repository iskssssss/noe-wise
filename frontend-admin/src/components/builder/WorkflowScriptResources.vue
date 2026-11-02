<template>
  <section aria-label="冻结脚本资源">
    <h3>脚本资源</h3>
    <p class="admin-field-help">
      添加 scripts/ 目录下的 .js 或 .py
      文件，供脚本节点选择。点击“保存资源”加入当前编辑内容后，还需保存工作流草稿。
    </p>
    <FeedbackNotice v-if="error" :title="error" type="error" :closable="false" />
    <ElTable :data="modelValue || []" empty-text="尚未添加脚本资源">
      <ElTableColumn prop="path" label="资源路径" min-width="180" /><ElTableColumn
        prop="sha256"
        label="SHA-256"
        min-width="200"
        show-overflow-tooltip
      />
      <ElTableColumn label="操作" width="140"
        ><template #default="{ row }"
          ><ElButton link :disabled="pending" @click="edit(row as WorkflowScriptResource)"
            >查看/编辑</ElButton
          ><ElButton link type="danger" :disabled="readonly || pending" @click="remove(row.path)"
            >删除</ElButton
          ></template
        ></ElTableColumn
      >
    </ElTable>
    <ElFormItem label="资源路径"
      ><ElInput v-model="path" :disabled="readonly || pending" placeholder="scripts/process.js"
    /></ElFormItem>
    <ElFormItem label="脚本内容"
      ><ElInput
        v-model="content"
        type="textarea"
        :rows="10"
        :disabled="readonly || pending"
        aria-label="冻结脚本内容"
    /></ElFormItem>
    <div class="admin-actions">
      <ElButton :disabled="readonly || pending" :loading="pending" @click="save">保存资源</ElButton
      ><ElButton :disabled="pending" @click="clear">清空编辑器</ElButton
      ><label
        >从本地读取脚本<input
          type="file"
          accept=".js,.py"
          :disabled="readonly || pending"
          @change="upload"
      /></label>
    </div>
  </section>
</template>
<script setup lang="ts">
import FeedbackNotice from '@skillforge/shared/feedback/FeedbackNotice.vue'
import { computed, ref, watch } from 'vue'
import type { WorkflowScriptResource } from '../../utils/workflow'
const props = defineProps<{ modelValue?: WorkflowScriptResource[]; readonly?: boolean }>()
const emit = defineEmits<{
  'update:modelValue': [value: WorkflowScriptResource[]]
  'pending-change': [value: boolean]
}>()
const path = ref(''),
  content = ref(''),
  error = ref(''),
  pending = ref(false),
  baseline = ref(JSON.stringify(['', '']))
const dirty = computed(() => JSON.stringify([path.value, content.value]) !== baseline.value)
watch(dirty, (value) => emit('pending-change', value))
function clear() {
  if (pending.value) return
  error.value = ''
  path.value = ''
  content.value = ''
  baseline.value = JSON.stringify(['', ''])
  emit('pending-change', false)
}
function edit(resource: WorkflowScriptResource) {
  if (pending.value) return
  if (dirty.value) {
    error.value = '当前脚本有未保存修改，请先保存资源或清空编辑器，再切换脚本或读取本地文件。'
    return
  }
  try {
    const bytes = Uint8Array.from(atob(resource.content_base64), (c) => c.charCodeAt(0))
    path.value = resource.path
    content.value = new TextDecoder('utf-8', { fatal: true }).decode(bytes)
    error.value = ''
    baseline.value = JSON.stringify([path.value, content.value])
    emit('pending-change', false)
  } catch {
    error.value = '无法读取此脚本。请将文件转换为 UTF-8 文本后重新上传；已保存资源未改动。'
  }
}
async function upload(event: Event) {
  if (pending.value || props.readonly) return
  const input = event.target as HTMLInputElement,
    file = input.files?.[0]
  input.value = ''
  if (!file) return
  if (dirty.value) {
    error.value = '当前脚本有未保存修改，请先保存资源或清空编辑器，再切换脚本或读取本地文件。'
    return
  }
  pending.value = true
  emit('pending-change', true)
  try {
    const text = new TextDecoder('utf-8', { fatal: true }).decode(await file.arrayBuffer())
    path.value = 'scripts/' + file.name
    content.value = text
    error.value = ''
  } catch {
    error.value = '文件读取失败，请上传 UTF-8 编码的脚本文本。原编辑内容已保留。'
  } finally {
    pending.value = false
    emit('pending-change', dirty.value)
  }
}
async function save() {
  if (props.readonly || pending.value) return
  pending.value = true
  error.value = ''
  const savedPath = path.value,
    savedContent = content.value
  try {
    const parts = savedPath.split('/')
    if (
      savedPath.length > 240 ||
      !savedPath.startsWith('scripts/') ||
      !/\.(js|py)$/.test(savedPath) ||
      /[\\\0]/.test(savedPath) ||
      parts.some((p) => !p || p === '.' || p === '..')
    )
      throw Error('请填写 scripts/ 开头、.js 或 .py 结尾的相对路径，并去掉空目录、反斜杠和 ..')
    const bytes = new TextEncoder().encode(savedContent),
      digest = await crypto.subtle.digest('SHA-256', bytes)
    let binary = ''
    for (const byte of bytes) binary += String.fromCharCode(byte)
    const resource = {
      path: savedPath,
      content_base64: btoa(binary),
      sha256: Array.from(new Uint8Array(digest), (b) => b.toString(16).padStart(2, '0')).join('')
    }
    emit('update:modelValue', [
      ...(props.modelValue || []).filter((r) => r.path !== savedPath),
      resource
    ])
    baseline.value = JSON.stringify([savedPath, savedContent])
    emit('pending-change', false)
  } catch (caught) {
    error.value =
      caught instanceof Error && !(caught instanceof TypeError) && !(caught instanceof DOMException)
        ? caught.message
        : '资源未保存，请检查路径和脚本内容后重试'
  } finally {
    pending.value = false
  }
}
function remove(value: string) {
  if (pending.value || props.readonly) return
  emit(
    'update:modelValue',
    (props.modelValue || []).filter((r) => r.path !== value)
  )
  if (path.value === value) clear()
}
</script>
