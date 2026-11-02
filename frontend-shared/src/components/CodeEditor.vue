<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { EditorState, Compartment, type Extension } from '@codemirror/state'
import { EditorView } from '@codemirror/view'
import { basicSetup } from 'codemirror'
import { javascript } from '@codemirror/lang-javascript'
import { json } from '@codemirror/lang-json'
import {
  foldAll as foldAllCode,
  forceParsing,
  HighlightStyle,
  syntaxHighlighting,
  unfoldAll as unfoldAllCode
} from '@codemirror/language'
import { tags } from '@lezer/highlight'
import { markdown } from '@codemirror/lang-markdown'
import { python } from '@codemirror/lang-python'
import { yaml } from '@codemirror/lang-yaml'
const props = withDefaults(
  defineProps<{
    modelValue?: string | null
    language?: string
    readonly?: boolean
    height?: string
    label?: string
    describedBy?: string
  }>(),
  {
    modelValue: '',
    language: 'text',
    readonly: false,
    height: '360px',
    label: '代码编辑器',
    describedBy: ''
  }
)
const emit = defineEmits<{ 'update:modelValue': [text: string] }>()
const host = ref<HTMLElement>()
const syntax = new Compartment()
const editable = new Compartment()
const accessibility = new Compartment()
const highlightStyle = HighlightStyle.define([
  { tag: tags.keyword, color: 'var(--code-syntax-keyword)' },
  {
    tag: [tags.atom, tags.bool, tags.number, tags.typeName, tags.className, tags.namespace],
    color: 'var(--code-syntax-constant)'
  },
  {
    tag: [tags.string, tags.regexp, tags.escape, tags.special(tags.string)],
    color: 'var(--code-syntax-string)'
  },
  {
    tag: [tags.definition(tags.variableName), tags.function(tags.variableName)],
    color: 'var(--code-syntax-keyword)'
  },
  { tag: [tags.comment, tags.meta], color: 'var(--code-syntax-comment)' },
  { tag: tags.invalid, color: 'var(--code-syntax-invalid)' },
  { tag: tags.link, color: 'var(--code-syntax-constant)', textDecoration: 'underline' },
  { tag: tags.heading, fontWeight: 'bold' },
  { tag: tags.emphasis, fontStyle: 'italic' },
  { tag: tags.strong, fontWeight: 'bold' },
  { tag: tags.strikethrough, textDecoration: 'line-through' }
])
function contentAttributes() {
  return EditorView.contentAttributes.of({
    'aria-label': props.label,
    'aria-multiline': 'true',
    'aria-readonly': String(props.readonly),
    tabindex: '0',
    role: 'textbox',
    'aria-describedby': props.describedBy
  })
}
let editor: EditorView | undefined
let externalUpdate = false
function languageExtension(): Extension {
  const name = props.language.toLowerCase()
  if (['json', 'jsonc'].includes(name)) return json()
  if (['js', 'javascript', 'ts', 'typescript'].includes(name))
    return javascript({ typescript: ['ts', 'typescript'].includes(name) })
  if (['md', 'markdown'].includes(name)) return markdown()
  if (['py', 'python'].includes(name)) return python()
  if (['yaml', 'yml'].includes(name)) return yaml()
  return []
}
onMounted(() => {
  editor = new EditorView({
    parent: host.value,
    state: EditorState.create({
      doc: props.modelValue ?? '',
      extensions: [
        basicSetup,
        syntax.of(languageExtension()),
        syntaxHighlighting(highlightStyle),
        editable.of([
          EditorState.readOnly.of(props.readonly),
          EditorView.editable.of(!props.readonly)
        ]),
        EditorView.lineWrapping,
        accessibility.of(contentAttributes()),
        EditorView.updateListener.of((update) => {
          if (update.docChanged && !externalUpdate)
            emit('update:modelValue', update.state.doc.toString())
        }),
        EditorView.theme({
          '&': {
            height: '100%',
            backgroundColor: 'var(--admin-surface, #fff)',
            color: 'var(--admin-text, #202532)'
          },
          '&.cm-focused': {
            outline: '2px solid var(--admin-accent, #4f46e5)',
            outlineOffset: '-2px'
          },
          '.cm-scroller': {
            overflow: 'auto',
            fontFamily: 'Consolas, monospace',
            lineHeight: '1.6',
            fontSize: '13px'
          },
          '.cm-content': { padding: '12px 0', caretColor: 'var(--admin-text, #202532)' },
          '.cm-line': { padding: '0 12px' },
          '.cm-cursor': { borderLeftColor: 'var(--admin-text, #202532)' },
          '.cm-activeLine, .cm-activeLineGutter': {
            backgroundColor: 'var(--admin-hover, #f1f3f8)'
          },
          '&.cm-focused .cm-selectionBackground, .cm-selectionBackground': {
            backgroundColor: 'var(--el-color-primary-light-8, #dedaf9)'
          },
          '.cm-panels': {
            backgroundColor: 'var(--admin-surface, #fff)',
            color: 'var(--admin-text, #202532)',
            borderColor: 'var(--admin-border, #e5e8ee)'
          },
          '.cm-search': {
            display: 'flex',
            flexWrap: 'wrap',
            alignItems: 'center',
            gap: '6px',
            padding: '8px'
          },
          '.cm-textfield': {
            backgroundColor: 'var(--admin-bg, #f5f6f8)',
            color: 'var(--admin-text, #202532)',
            borderColor: 'var(--admin-border, #e5e8ee)'
          },
          '.cm-gutters': {
            backgroundColor: 'var(--admin-bg, #f5f6f8)',
            color: 'var(--admin-muted, #687181)',
            borderColor: 'var(--admin-border, #e5e8ee)'
          },
          '.cm-foldPlaceholder': {
            backgroundColor: 'var(--admin-hover, #f1f3f8)',
            color: 'var(--admin-text, #202532)',
            borderColor: 'var(--admin-border, #e5e8ee)'
          },
          '.cm-foldGutter .cm-gutterElement': {
            cursor: 'pointer'
          }
        })
      ]
    })
  })
})
watch(
  () => props.modelValue,
  (value) => {
    if (!editor || editor.state.doc.toString() === (value ?? '')) return
    externalUpdate = true
    try {
      editor.dispatch({ changes: { from: 0, to: editor.state.doc.length, insert: value ?? '' } })
    } finally {
      externalUpdate = false
    }
  }
)
watch(
  () => props.language,
  () => editor?.dispatch({ effects: syntax.reconfigure(languageExtension()) })
)
watch(
  () => props.readonly,
  (value) =>
    editor?.dispatch({
      effects: [
        editable.reconfigure([EditorState.readOnly.of(value), EditorView.editable.of(!value)]),
        accessibility.reconfigure(contentAttributes())
      ]
    })
)
watch(
  () => [props.label, props.describedBy],
  () => editor?.dispatch({ effects: accessibility.reconfigure(contentAttributes()) })
)
function focusLine(line = 1) {
  if (!editor) return
  const number = Number.isFinite(line)
    ? Math.max(1, Math.min(editor.state.doc.lines, Math.trunc(line)))
    : 1
  const target = editor.state.doc.line(number)
  editor.dispatch({
    selection: { anchor: target.from },
    effects: EditorView.scrollIntoView(target.from, { y: 'center' })
  })
  editor.focus()
}
/** 使用 CodeMirror 保留的选区替换文本，弹层取得焦点后仍可插入。 */
function insertText(text: string): boolean {
  if (!editor || props.readonly) return false
  editor.dispatch(editor.state.replaceSelection(text))
  editor.focus()
  return true
}
function foldAll(): boolean {
  if (!editor) return false
  // 主动折叠覆盖完整文档，不受后台解析仅处理可见区域的进度限制。
  forceParsing(editor, editor.state.doc.length, Number.POSITIVE_INFINITY)
  return foldAllCode(editor)
}
function unfoldAll(): boolean {
  return editor ? unfoldAllCode(editor) : false
}
defineExpose({ focusLine, insertText, foldAll, unfoldAll })
onBeforeUnmount(() => editor?.destroy())
</script>
<template><div ref="host" class="code-editor" :style="{ height }" /></template>
<style scoped>
.code-editor {
  --code-syntax-keyword: #8250df;
  --code-syntax-constant: #0550ae;
  --code-syntax-string: #0a3069;
  --code-syntax-comment: #57606a;
  --code-syntax-invalid: #cf222e;
  border: 1px solid var(--admin-border, #e5e8ee);
  border-radius: 6px;
  overflow: hidden;
  min-height: 100px;
  width: 100%;
  min-width: 0;
}
:global(.dark .code-editor) {
  --code-syntax-keyword: #d2a8ff;
  --code-syntax-constant: #79c0ff;
  --code-syntax-string: #a5d6ff;
  --code-syntax-comment: #8b949e;
  --code-syntax-invalid: #ff7b72;
}
</style>
