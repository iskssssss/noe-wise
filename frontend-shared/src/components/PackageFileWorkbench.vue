<script setup lang="ts">
import { computed, nextTick, ref, watch, useId } from 'vue'
import { ElButton, ElInput, ElAlert, ElEmpty, ElTree } from 'element-plus'
import { confirm } from '../feedback'
import { guardFormEnter } from '../admin/keyboard'
import CodeEditor from './CodeEditor.vue'
const props = withDefaults(defineProps<{ files: Record<string, string>; readonly?: boolean }>(), {
  readonly: false
})
const emit = defineEmits<{ 'update:files': [files: Record<string, string>] }>()
interface FileNode {
  key: string
  label: string
  path: string
  isFile: boolean
  children?: FileNode[]
}
const selected = ref('')
const filePath = ref('')
const search = ref('')
const creating = ref(false)
const removing = ref(false)
const error = ref('')
const inputRef = ref<InstanceType<typeof ElInput>>()
const treeRef = ref<InstanceType<typeof ElTree>>()
const editorRef = ref<InstanceType<typeof CodeEditor>>()
const addButton = ref<InstanceType<typeof ElButton>>()
const helpId = useId()
const names = computed(() => Object.keys(props.files).sort())
const matchingNames = computed(() =>
  names.value.filter((name) => name.toLowerCase().includes(search.value.trim().toLowerCase()))
)
const tree = computed(() => {
  const roots: FileNode[] = []
  for (const path of matchingNames.value) {
    let nodes = roots
    const parts = path.split('/')
    parts.forEach((label, index) => {
      const isFile = index === parts.length - 1
      const entryPath = parts.slice(0, index + 1).join('/')
      const key = `${isFile ? 'file' : 'directory'}:${entryPath}`
      let node = nodes.find((item) => item.key === key)
      if (!node) {
        node = { key, label, path: entryPath, isFile, ...(isFile ? {} : { children: [] }) }
        nodes.push(node)
      }
      if (!isFile) nodes = node.children!
    })
  }
  return roots
})
watch(
  names,
  (next) => {
    if (!next.includes(selected.value))
      selected.value = next.includes('SKILL.md') ? 'SKILL.md' : (next[0] ?? '')
  },
  { immediate: true }
)
watch(
  () => props.readonly,
  (value) => {
    if (value) {
      creating.value = false
      error.value = ''
    }
  }
)
watch(selected, async (value) => {
  await nextTick()
  treeRef.value?.setCurrentKey(`file:${value}`)
})
const language = computed(() => selected.value.split('.').pop() ?? 'text')
function selectFile(path: string): boolean {
  if (!Object.hasOwn(props.files, path)) return false
  search.value = ''
  selected.value = path
  return true
}
async function reveal(path: string, line = 1): Promise<boolean> {
  if (!selectFile(path)) return false
  await nextTick()
  editorRef.value?.focusLine(line)
  return true
}
defineExpose({ selectFile, reveal })
function update(content: string) {
  if (!props.readonly && selected.value)
    emit('update:files', { ...props.files, [selected.value]: content })
}
async function startCreate() {
  if (props.readonly) return
  creating.value = true
  error.value = ''
  await nextTick()
  inputRef.value?.focus()
}
function add(event?: Event) {
  if (
    props.readonly ||
    (event instanceof KeyboardEvent && (event.isComposing || event.keyCode === 229))
  )
    return
  const name = filePath.value.trim().replaceAll('\\', '/')
  if (
    !name ||
    name.startsWith('/') ||
    name.includes(':') ||
    name.split('/').some((part) => !part || part === '.' || part === '..') ||
    names.value.some(
      (existing) =>
        existing === name || existing.startsWith(name + '/') || name.startsWith(existing + '/')
    )
  ) {
    error.value =
      '文件未创建。请填写不重复的相对路径，去掉上级目录，并检查是否与已有文件或目录冲突。'
    return
  }
  error.value = ''
  search.value = ''
  emit('update:files', { ...props.files, [name]: '' })
  selected.value = name
  filePath.value = ''
  creating.value = false
  void nextTick(() => addButton.value?.$el.focus())
}
function cancelCreate() {
  creating.value = false
  error.value = ''
  void nextTick(() => addButton.value?.$el.focus())
}
async function remove() {
  if (props.readonly || !selected.value || removing.value) return
  const name = selected.value
  removing.value = true
  try {
    if (
      !(await confirm({
        title: '删除文件',
        content: `从当前编辑内容中删除“${name}”？删除后需保存草稿。`,
        confirmText: '删除',
        cancelText: '取消',
        danger: true
      }))
    )
      return
    if (props.readonly || !Object.hasOwn(props.files, name)) return
    const index = names.value.indexOf(name)
    const next = { ...props.files }
    delete next[name]
    if (selected.value === name)
      selected.value = names.value[index + 1] ?? names.value[index - 1] ?? ''
    emit('update:files', next)
    await nextTick()
    const current = treeRef.value?.$el.querySelector('[tabindex="0"]') as HTMLElement | undefined
    if (current) current.focus()
    else addButton.value?.$el.focus()
  } finally {
    removing.value = false
  }
}
</script>
<template>
  <section class="package-workbench" aria-label="包文件工作台">
    <aside class="file-sidebar" aria-label="文件列表">
      <div class="file-sidebar-heading">
        <strong>文件</strong><span class="file-count">{{ names.length }} 项</span
        ><ElButton v-if="!readonly" ref="addButton" text @click="startCreate">新建文件</ElButton>
      </div>
      <ElInput
        v-if="names.length"
        v-model="search"
        clearable
        aria-label="搜索文件路径"
        placeholder="查找文件…"
        class="file-search"
      />
      <div
        v-if="creating && !readonly"
        class="file-create"
        @keydown.capture="guardFormEnter"
        @keydown.esc.stop.prevent="cancelCreate"
      >
        <label :for="helpId + '-path'">包内相对路径</label>
        <ElInput
          :id="helpId + '-path'"
          ref="inputRef"
          v-model="filePath"
          placeholder="scripts/main.py"
          :aria-describedby="helpId + '-path-help'"
          @keydown.enter.prevent="add"
        />
        <p :id="helpId + '-path-help'" class="admin-field-help">
          使用 / 分隔目录，例如 scripts/main.py。路径不能与已有文件或目录冲突；创建后需保存草稿。
        </p>
        <ElAlert v-if="error" :title="error" type="error" :closable="false" />
        <div class="file-create-actions">
          <ElButton type="primary" @click="add">创建文件</ElButton
          ><ElButton @click="cancelCreate">取消</ElButton>
        </div>
      </div>
      <ElTree
        v-if="tree.length"
        ref="treeRef"
        class="file-tree"
        :data="tree"
        node-key="key"
        :current-node-key="'file:' + selected"
        :expand-on-click-node="true"
        default-expand-all
        highlight-current
        aria-label="技能文件树"
        @node-click="
          (node: FileNode) => {
            if (node.isFile) selected = node.path
          }
        "
      >
        <template #default="{ data }"
          ><span class="file-node" :title="data.path"
            ><span class="file-kind" aria-hidden="true">{{ data.isFile ? '·' : '/' }}</span
            >{{ data.label }}</span
          ></template
        >
      </ElTree>
      <ElEmpty
        v-else
        :description="names.length ? '未找到匹配文件，请调整或清空搜索' : '尚无文件'"
        :image-size="48"
        ><ElButton v-if="names.length" text @click="search = ''">清空搜索</ElButton></ElEmpty
      >
    </aside>
    <div class="file-content">
      <header>
        <div class="file-current">
          <strong>{{ selected || '文件编辑' }}</strong
          ><span class="file-mode">{{ readonly ? '只读版本' : '草稿 · 修改后需保存' }}</span>
        </div>
        <ElButton
          v-if="selected && !readonly"
          type="danger"
          text
          :loading="removing"
          @click="remove"
          >删除文件</ElButton
        >
      </header>
      <CodeEditor
        v-if="selected"
        :key="selected"
        ref="editorRef"
        :model-value="files[selected] ?? ''"
        :language="language"
        :label="`文件 ${selected}`"
        :readonly="readonly"
        height="500px"
        @update:model-value="update"
      />
      <ElEmpty
        v-else
        :description="readonly ? '当前版本没有文件' : '新建文件，开始编写能力内容'"
        :image-size="72"
        ><ElButton v-if="!readonly" type="primary" @click="startCreate">新建文件</ElButton></ElEmpty
      >
      <p v-if="selected" class="admin-field-help file-footer" role="status">
        {{ language.toUpperCase() }} · {{ (files[selected] ?? '').split('\n').length }} 行<span
          >使用方向键浏览文件树，Enter 选择文件</span
        >
      </p>
    </div>
  </section>
</template>
<style scoped>
.package-workbench {
  display: grid;
  grid-template-columns: minmax(210px, 270px) minmax(0, 1fr);
  border: 1px solid var(--admin-border);
  border-radius: 10px;
  background: var(--admin-surface);
  overflow: hidden;
}
.file-sidebar {
  min-width: 0;
  padding: 12px;
  border-right: 1px solid var(--admin-border);
  overflow: auto;
}
.file-sidebar-heading {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 40px;
}
.file-sidebar-heading .el-button {
  margin-left: auto;
}
.file-count,
.file-mode {
  font-size: 12px;
  color: var(--admin-muted);
}
.file-search {
  margin-bottom: 12px;
}
.file-create {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 12px 0;
  margin-bottom: 12px;
  border-block: 1px solid var(--admin-border);
}
.file-create label {
  font-size: 13px;
}
.file-create .admin-field-help {
  margin: 0;
}
.file-create-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.file-create-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}
.file-tree :deep(.el-tree-node__content) {
  height: auto;
  min-height: 36px;
  align-items: baseline;
  padding-block: 8px;
}
.file-tree :deep(.el-tree-node__expand-icon) {
  align-self: center;
}
.file-node {
  white-space: normal;
  overflow-wrap: anywhere;
  min-width: 0;
  padding-right: 8px;
}
.file-kind {
  color: var(--admin-muted);
  margin-right: 6px;
}
.file-content {
  min-width: 0;
  padding: 12px;
}
.file-content header {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  min-height: 48px;
  padding-bottom: 12px;
  font-size: 14px;
}
.file-current {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
  overflow-wrap: anywhere;
}
.file-footer {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 0;
}
@media (max-width: 900px) {
  .package-workbench {
    grid-template-columns: 1fr;
  }
  .file-sidebar {
    max-height: 280px;
    border-right: 0;
    border-bottom: 1px solid var(--admin-border);
  }
}
</style>
