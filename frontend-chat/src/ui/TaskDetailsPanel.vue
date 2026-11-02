<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { query, type Client } from '../client'
import type { Work } from '../protocol'
import { mergeWorkPlans, workPhase } from '../workPlans'
import { ChatButton, ChatSelect, ChatMessageNotice } from './controls'
import TaskPlanCard from './TaskPlanCard.vue'
import type { ClosablePanel } from './panel'

const props = defineProps<{
  modelValue: boolean
  client: Client
  session: string
  selectedId?: string
  workPlans?: Work[]
  locked?: boolean
}>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  selected: [id: string]
  updated: [work: Work]
}>()
const works = ref<Work[]>([]),
  selected = ref(''),
  view = ref('overview')
const card = ref<ClosablePanel>(),
  busy = ref(false),
  loading = ref(false),
  error = ref('')
const work = computed(() => works.value.find((item) => item.work_id === selected.value))
const workOptions = computed(() =>
  works.value.map((item) => ({
    value: item.work_id,
    label: `${item.title || '任务计划'} · ${workPhase(item)}`
  }))
)
const media = window.matchMedia?.('(max-width: 800px)')
const mobile = ref(media?.matches ?? false)
const mediaChanged = (event: MediaQueryListEvent) => {
  mobile.value = event.matches
}
media?.addEventListener('change', mediaChanged)
// 宿主与子树保持挂载，缩放仅改变同一 dialog 的模态方式。
const dialog = ref<HTMLDialogElement>()
let modal = false,
  previousFocus: HTMLElement | undefined
watch(
  [() => props.modelValue, mobile],
  async ([open, narrow], previous) => {
    if (open && !previous?.[0])
      previousFocus =
        document.activeElement instanceof HTMLElement ? document.activeElement : undefined
    await nextTick()
    const element = dialog.value
    if (!open) {
      if (modal && previousFocus?.isConnected) previousFocus.focus()
      modal = false
      return
    }
    if (!element) return
    const focus = document.activeElement instanceof HTMLElement ? document.activeElement : undefined
    if (narrow && !modal) {
      element.removeAttribute('open')
      if (element.showModal) element.showModal()
      else element.setAttribute('open', '')
      modal = true
    } else if (!narrow) {
      if (modal) element.close?.()
      element.setAttribute('open', '')
      modal = false
    }
    if (focus && element.contains(focus)) focus.focus()
  },
  { immediate: true }
)

let abort = new AbortController(),
  generation = 0
async function requestClose() {
  if (busy.value || (card.value && !(await card.value.requestClose()))) return false
  emit('update:modelValue', false)
  return true
}
async function selectWork(id: string) {
  if (id === selected.value) return true
  if (busy.value || (card.value && !(await card.value.requestClose()))) return false
  selected.value = id
  view.value = 'overview'
  emit('selected', id)
  return true
}
async function selectView(value: string) {
  if (value === view.value) return
  if (busy.value || (card.value && !(await card.value.requestClose()))) return
  view.value = value
}
async function refresh() {
  const token = ++generation
  loading.value = true
  error.value = ''
  try {
    const value = await props.client.json<{ items: Work[] }>(
      `/chat/work/history?${query({ session_id: props.session })}`,
      'GET',
      undefined,
      abort.signal
    )
    if (token !== generation || abort.signal.aborted) return
    works.value = mergeWorkPlans(works.value, value.items)
    // 只有尚未选过任务时才采用默认项；新工作和进度更新不能夺走当前选择。
    if (!selected.value && works.value[0]) {
      selected.value = works.value[0].work_id
      emit('selected', selected.value)
    }
  } catch (cause) {
    if (token === generation && !abort.signal.aborted)
      error.value = cause instanceof Error ? cause.message : '未能读取任务计划，可点击重新读取。'
  } finally {
    if (token === generation) loading.value = false
  }
}
watch(
  () => props.workPlans,
  (value) => {
    works.value = mergeWorkPlans(works.value, value ?? [])
  },
  { immediate: true, deep: true }
)
watch(
  () => props.selectedId,
  (id) => {
    if (id) void selectWork(id)
  },
  { immediate: true }
)
watch(
  () => props.modelValue,
  (open) => {
    if (open) {
      if (!selected.value && works.value[0]) {
        selected.value = works.value[0].work_id
        emit('selected', selected.value)
      }
      void refresh()
    }
  },
  { immediate: true }
)
watch(
  () => props.session,
  () => {
    abort.abort()
    abort = new AbortController()
    generation++
    selected.value = ''
    works.value = props.workPlans ?? []
    view.value = 'overview'
    busy.value = false
    error.value = ''
    if (props.modelValue) void refresh()
  }
)
onBeforeUnmount(() => {
  generation++
  abort.abort()
  media?.removeEventListener('change', mediaChanged)
  dialog.value?.close?.()
  if (modal && previousFocus?.isConnected) previousFocus.focus()
})
defineExpose({ requestClose })
</script>

<template>
  <aside v-if="modelValue" class="task-details-panel" aria-label="任务详情">
    <dialog
      ref="dialog"
      class="task-panel-dialog"
      :class="{ 'chat-drawer': mobile }"
      :style="mobile ? { '--drawer-size': '100vw' } : undefined"
      :role="mobile ? undefined : 'complementary'"
      aria-labelledby="task-panel-title"
      @cancel.prevent="requestClose"
    >
      <header class="task-panel-header">
        <h2 id="task-panel-title">任务详情</h2>
        <ChatButton text aria-label="关闭任务详情" @click="requestClose">关闭</ChatButton>
      </header>
      <div class="task-panel-body">
        <label for="task-work-selector">查看任务</label>
        <ChatSelect
          id="task-work-selector"
          :model-value="selected"
          :options="workOptions"
          :disabled="busy"
          @change="selectWork"
        />
        <p v-if="loading" role="status">正在读取任务…</p>
        <ChatMessageNotice v-if="error" :title="error" type="error">
          <ChatButton text @click="refresh">重新读取</ChatButton>
        </ChatMessageNotice>
        <nav v-if="work" class="task-panel-views" aria-label="任务视图">
          <ChatButton
            v-for="item in [
              { id: 'overview', label: '概览' },
              { id: 'steps', label: '步骤' },
              { id: 'dependencies', label: '依赖关系' }
            ]"
            :key="item.id"
            text
            :aria-pressed="view === item.id"
            :disabled="busy"
            @click="selectView(item.id)"
            >{{ item.label }}</ChatButton
          >
        </nav>
        <TaskPlanCard
          v-if="work"
          ref="card"
          :key="`${session}:${work.work_id}`"
          mode="detail"
          :view="view as 'overview' | 'steps' | 'dependencies'"
          :work="work"
          :client="client"
          :session="session"
          :locked="locked"
          @busy="busy = $event"
          @updated="
            (value: Work) => {
              works = mergeWorkPlans(works, [value])
              emit('updated', value)
            }
          "
        />
        <div v-else-if="!loading && !error">
          <p>
            {{
              selected
                ? '当前任务记录暂未返回，请重新读取。'
                : '当前会话尚未生成任务计划，生成后会显示在这里。'
            }}
          </p>
          <ChatButton text @click="refresh">重新读取任务</ChatButton>
        </div>
      </div>
    </dialog>
  </aside>
</template>

<style scoped>
.task-details-panel {
  grid-column: span 5;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  border-left: 1px solid var(--border);
  background: var(--surface);
}
.task-panel-dialog:not(.chat-drawer) {
  display: contents;
  color: inherit;
}
.task-panel-header {
  min-height: 72px;
  padding: 16px 24px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid var(--border);
}
.task-panel-header h2 {
  margin: 0;
  font-size: 14px;
}
.task-panel-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 18px 24px;
}
.task-panel-body > label {
  display: block;
  margin-bottom: 8px;
  font-size: 12px;
  color: var(--muted);
}
.task-panel-views {
  display: flex;
  gap: 8px;
  margin-top: 16px;
}
.task-panel-views [aria-pressed='true'] {
  color: var(--accent);
  font-weight: 600;
}
@media (min-width: 1440px) {
  .task-details-panel {
    grid-column: span 4;
  }
}
@media (max-width: 800px) {
  .task-details-panel {
    display: contents;
    border: 0;
  }
  .task-panel-body {
    padding: 8px 16px;
  }
}
</style>
