<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { query, type Client } from '../client'
import { ChatButton, ChatMessageNotice } from './controls'
import { formatDateTime } from './dateTime'

/** 工作区状态仅用于展示，归属和备份事实始终由宿主接口决定。 */
type WorkspaceSummary = {
  sessionId: string
  isChild: boolean
  status: {
    workspaceId: string
    revision: number
    backupRevision: number | null
    localAvailable: boolean
    phase: string
    backupError?: string | null
    lastBackupAt?: string | null
  }
}

const props = defineProps<{ client: Client; session: string; refreshKey?: string }>()
const workspaces = ref<WorkspaceSummary[]>([]),
  busy = ref(false),
  unavailable = ref(false)
const summaries = computed(() => {
  let child = 0
  return workspaces.value.map((workspace) => ({
    ...workspace,
    label: workspace.isChild ? `协作工作区 ${++child}` : '当前会话',
    backupTime: workspace.status.lastBackupAt
      ? formatDateTime(workspace.status.lastBackupAt)
      : undefined
  }))
})
let request: AbortController | undefined,
  epoch = 0,
  disposed = false

/** 刷新只读取既有工作区；会话切换或卸载后，迟到响应不能覆盖当前展示。 */
async function refresh() {
  request?.abort()
  const token = ++epoch,
    session = props.session,
    client = props.client
  unavailable.value = false
  if (!session || disposed) {
    busy.value = false
    return
  }
  request = new AbortController()
  busy.value = true
  const current = () =>
    !disposed && token === epoch && session === props.session && client === props.client
  try {
    const data = await client.json<{ workspaces: WorkspaceSummary[] }>(
      `/chat/sessions/workspace?${query({ sessionId: session })}`,
      'GET',
      undefined,
      request.signal
    )
    if (current()) workspaces.value = data.workspaces ?? []
  } catch {
    // 状态读取失败不改变对话和备份事实，且不暴露服务错误中的路径、凭据或标识。
    if (current()) unavailable.value = true
  } finally {
    if (current()) busy.value = false
  }
}

/** 只投影保存和备份的明确关系，不把请求成功或本地保存解释成云端完成。 */
function backupLabel(status: WorkspaceSummary['status']) {
  if (status.backupError || status.phase === 'backup_failed') return '备份失败，等待重试'
  if (status.phase === 'backing_up') return '备份同步中'
  if (status.backupRevision !== null && status.backupRevision >= status.revision)
    return status.lastBackupAt ? '已备份' : '尚无备份'
  return '等待备份'
}

/** 本地可用性不能推断归档、恢复或清理阶段，阶段标签取自服务的明确事实。 */
function localLabel(status: WorkspaceSummary['status']) {
  const phases: Record<string, string> = {
    archived: '已归档',
    restoring: '正在恢复',
    recovery_required: '需要恢复',
    deleting: '正在清理',
    deleted: '已清理'
  }
  return phases[status.phase] ?? (status.localAvailable ? '本地可用' : '本地暂不可用')
}

watch(
  () => [props.client, props.session, props.refreshKey] as const,
  (current, previous) => {
    if (!previous || current[0] !== previous[0] || current[1] !== previous[1]) workspaces.value = []
    // 合并同一轮会话切换与完结标记更新，只读取一次，避免重复状态请求。
    void refresh()
  },
  { immediate: true }
)
onBeforeUnmount(() => {
  disposed = true
  epoch++
  request?.abort()
})
</script>

<template>
  <section
    v-if="summaries.length || unavailable || (session && busy)"
    class="workspace-status"
    aria-label="工作区状态"
  >
    <div class="workspace-status-heading">
      <strong>工作区</strong>
      <ChatButton text :loading="busy" :disabled="busy" @click="refresh">刷新状态</ChatButton>
    </div>
    <ul>
      <li v-for="workspace in summaries" :key="workspace.sessionId">
        <span class="workspace-status-label">{{ workspace.label }}</span>
        <span>{{ localLabel(workspace.status) }}</span>
        <span>已保存修订 {{ workspace.status.revision }}</span>
        <span v-if="workspace.status.backupRevision !== null && workspace.status.lastBackupAt">
          已备份到修订 {{ workspace.status.backupRevision }}
        </span>
        <span
          class="workspace-backup-state"
          :class="{
            'has-error':
              Boolean(workspace.status.backupError) || workspace.status.phase === 'backup_failed'
          }"
          >{{ backupLabel(workspace.status) }}</span
        >
        <time
          v-if="workspace.backupTime"
          :datetime="workspace.backupTime === '—' ? undefined : workspace.status.lastBackupAt!"
          >备份时间 {{ workspace.backupTime }}</time
        >
      </li>
    </ul>
    <ChatMessageNotice v-if="unavailable" title="工作区状态暂时无法读取，请重新读取。" type="error"
      ><ChatButton text :loading="busy" :disabled="busy" @click="refresh"
        >重新读取工作区</ChatButton
      ></ChatMessageNotice
    >
    <p v-if="busy && !summaries.length" role="status">正在读取工作区状态…</p>
  </section>
</template>

<style scoped>
.workspace-status {
  flex: none;
  padding: var(--space-1) var(--space-3);
  border-bottom: 1px solid var(--border);
  color: var(--muted);
  font-size: var(--font-small);
  line-height: 1.8;
}
.workspace-status-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-1);
}
.workspace-status-heading strong,
.workspace-status-label {
  color: var(--text);
  font-weight: 500;
}
.workspace-status ul {
  list-style: none;
  padding: 0;
}
.workspace-status li {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 0 var(--space-2);
}
.workspace-status time {
  font-size: 11px;
}
.workspace-backup-state,
.workspace-status p {
  color: var(--warning);
}
.workspace-backup-state.has-error {
  color: var(--error);
}
</style>
