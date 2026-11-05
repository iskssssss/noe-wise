<script setup lang="ts">
import {
  Quote as IconQuote,
  Copy as IconCopy,
  Sparkles as IconSparkles,
  Info as IconInfo,
  FileText as IconFileText
} from 'lucide-vue-next'
import { computed, ref, watch } from 'vue'
import { ChatButton, ChatMessage } from './controls'
import type { Client } from '../client'
import type {
  Deliverable,
  History,
  Message,
  MessagePreview,
  MessageState,
  Reference
} from '../protocol'
import Markdown from './Markdown.vue'
import MessageBody from './MessageBody.vue'
import MediaImage from './MediaImage.vue'
import ArtifactCards from './ArtifactCards.vue'
import ConversationProcess from './ConversationProcess.vue'
import TaskPlanCard from './TaskPlanCard.vue'
import { executionStopReason } from './runBudget'
import {
  conversationEntries,
  conversationTurns,
  type ConversationEntry,
  type ConversationTurn
} from './conversationTimeline'

const props = defineProps<{
  history: History
  client: Client
  session: string
  targetName?: string
  locked: boolean
  runStates: Record<string, string>
  connection?: string
  artifacts: Deliverable[]
  previews?: Record<string, MessagePreview>
}>()
const emit = defineEmits<{
  reference: [value: Reference]
  artifact: [id: string]
  task: [id: string]
}>()
const turns = computed(() => {
  const turns = conversationTurns(props.history)
  const candidates = Object.values(props.previews ?? {}).filter(
    (preview) => preview.state === 'streaming' && preview.content
  )
  for (const preview of candidates) {
    if (
      !turns.some(
        (turn) =>
          turn.runIds.includes(preview.run_id) ||
          turn.messages.some(
            (message) =>
              message.message_id === preview.root_input_id ||
              (message.root_input_id || message.metadata?.root_input_id) === preview.root_input_id
          )
      )
    )
      turns.push({
        id: `preview:${preview.run_id}`,
        runIds: [preview.run_id],
        messages: [],
        processes: []
      })
  }
  return turns.map((turn) => ({
    turn,
    entries: conversationEntries(turn),
    previews: candidates.filter(
      (preview) =>
        turn.runIds.includes(preview.run_id) ||
        turn.messages.some(
          (message) =>
            message.message_id === preview.root_input_id ||
            (message.root_input_id || message.metadata?.root_input_id) === preview.root_input_id
        )
    )
  }))
})
const traceModes = ref<Record<string, boolean>>({})
watch(
  () => props.session,
  () => {
    traceModes.value = {}
  }
)
const outputStatus: Partial<Record<MessageState, string>> = {
  streaming: '正在生成回复',
  failed: '本次执行未完成，以上为已确认的信息。',
  cancelled: '已停止，已保留已生成的内容。',
  outcome_unknown: '结果待核实，以下仅为已接收的内容。'
}
/** 复制公开回答原文，不从渲染 DOM 拼接过程、引用或附件材料。 */
async function copyAnswer(message: Message) {
  try {
    await navigator.clipboard.writeText(message.content)
    ChatMessage.success('回答已复制。')
  } catch {
    ChatMessage.error('复制失败，请选中回答正文手动复制。')
  }
}
function canCopy(message: Message) {
  return (
    message.role === 'assistant' &&
    !isCommentary(message) &&
    message.state !== 'streaming' &&
    Boolean(message.content.trim())
  )
}
function parts(message: Message) {
  return Array.isArray(message.metadata?.content_parts) ? message.metadata.content_parts : undefined
}
/** 依据标记只展示安全名称与本消息锚点，不拼接私有材料链接。 */
function citations(message: Message) {
  return message.citations ?? message.metadata?.citations ?? []
}
/** 只接受服务端声明的短标识，正文链接仅定位本 Run 的已公开依据。 */
function citationAnchor(id: string) {
  return /^k[0-9a-f]{16}$/.test(id) ? `citation-${id}` : undefined
}
function stopReason(turn: ConversationTurn) {
  const activity = props.history.activities?.find(
    (item) =>
      !!item.node_id &&
      turn.runIds.includes(item.node_id) &&
      ['failed', 'cancelled', 'terminated'].includes(item.status ?? '') &&
      item.render?.reason
  )
  return activity?.render?.reason ? executionStopReason(activity.render.reason) : ''
}
/** 纯附件也是用户输入，不用占位正文伪造消息；只展示已确认的选择器数量。 */
function attachmentCount(message: Message) {
  const ids = message.metadata?.attachment_ids
  return Array.isArray(ids) ? ids.filter((id) => typeof id === 'string' && id.length).length : 0
}
/** 展示服务端已公开的冻结名称，不用附件 ID 构造读取链接。 */
function attachmentNames(message: Message) {
  const files = message.metadata?.attachments
  return Array.isArray(files)
    ? files.map((file) => (typeof file?.name === 'string' ? file.name : '')).filter(Boolean)
    : []
}
/** 只将已确认的公开 commentary 标为执行说明，不猜测 pending 正文的用途。 */
function isCommentary(message: Message) {
  return message.role === 'assistant' && (message.phase ?? message.metadata?.phase) === 'commentary'
}
function isExecution(entry?: ConversationEntry) {
  return entry?.kind === 'process' || (entry?.kind === 'message' && isCommentary(entry.message))
}
/** 公开说明仍是原消息节点；相邻执行片段只在视觉上连接，不折叠或重排正文。 */
function executionClasses(entries: ConversationEntry[], index: number) {
  return {
    'is-execution-start': !isExecution(entries[index - 1]),
    'is-execution-end': !isExecution(entries[index + 1]),
    'is-execution-continuation': isExecution(entries[index - 1])
  }
}
/** 原生段尚未判定为最终答复时只提示正在输出，不暗示工具或运行已完成。 */
function messageStatus(message: Message) {
  if (message.state === 'streaming' && (message.phase ?? message.metadata?.phase) !== 'final')
    return '正在输出'
  return message.state ? outputStatus[message.state] : undefined
}
/** Pending 可属于被委派的子 Run，不能只匹配本轮根运行。 */
function pending(turn: ConversationTurn) {
  return props.history.pending_interactions.some(
    (item) =>
      turn.runIds.includes(item.execution_id) ||
      turn.processes.some((process) => process.run_id === item.execution_id)
  )
}
</script>

<template>
  <p
    v-if="history.history_retention?.retired_event_types.includes('agent.model-reasoning')"
    class="history-retention"
    role="status"
  >
    部分较早的推理过程已按保留策略回收。已保留的回复与执行记录仍可查看。
  </p>
  <section
    v-for="{ turn, entries, previews: turnPreviews } in turns"
    :key="turn.id"
    class="conversation-turn"
    :data-turn="turn.id"
  >
    <template v-for="(entry, entryIndex) in entries" :key="entry.key">
      <ConversationProcess
        v-if="entry.kind === 'process'"
        :key="`${session}:${turn.id}:${entry.key}`"
        :turn="entry.turn"
        :overview="entry.turn"
        :class="executionClasses(entries, entryIndex)"
        :trace-expanded="traceModes[entry.key] ?? false"
        :run-states="runStates"
        :pending="pending(entry.turn)"
        :connection="connection"
        :locked="locked"
        compact
        :target-name="targetName"
        @reference="emit('reference', $event)"
        @trace="traceModes[entry.key] = $event"
      />
      <template v-else>
        <template v-for="message in [entry.message]" :key="message.message_id">
          <article
            v-if="message.role === 'user'"
            class="message user"
            :data-message-id="message.message_id"
          >
            <MessageBody :created-at="message.created_at">
              <small
                v-if="message.metadata?.interaction_reply_kind"
                class="interaction-reply-label"
                >{{
                  message.metadata.interaction_reply_kind === 'operation_authorization'
                    ? '操作确认记录'
                    : '补充回复'
                }}</small
              >
              <div v-if="attachmentCount(message)" class="message-attachment-summary">
                <IconFileText :size="16" aria-hidden="true" />
                <span v-if="attachmentNames(message).length">{{
                  attachmentNames(message).join('、')
                }}</span>
                <span v-else>{{ attachmentCount(message) }} 个附件</span>
              </div>
              <template v-if="parts(message)">
                <template v-for="(part, index) in parts(message)" :key="index">
                  <Markdown v-if="part.type === 'text'" :text="part.text" />
                  <MediaImage v-else :client="client" :session="session" :media="part.media_id" />
                </template>
              </template>
              <Markdown v-else :text="message.content" />
            </MessageBody>
          </article>
          <template v-if="message.role === 'user'">
            <TaskPlanCard
              v-for="plan in (history.work_plans ?? []).filter(
                (plan) => plan.input_id === (message.input_id || message.message_id)
              )"
              :key="plan.work_id"
              :work="plan"
              :client="client"
              :session="session"
              :locked="locked"
              @open="emit('task', $event)"
            />
          </template>
          <article
            v-if="message.role !== 'user'"
            :class="[
              'message',
              message.role,
              { 'is-commentary': isCommentary(message) },
              isCommentary(message) ? executionClasses(entries, entryIndex) : {}
            ]"
            :data-message-id="message.message_id"
            :data-message-phase="message.phase ?? message.metadata?.phase"
            :aria-label="isCommentary(message) ? '执行说明' : undefined"
          >
            <div class="message-label">
              <span class="message-mark" aria-hidden="true">
                <IconSparkles v-if="message.role === 'assistant' && !isCommentary(message)" />
                <IconInfo v-else />
              </span>
              {{
                isCommentary(message)
                  ? '执行说明'
                  : message.role === 'assistant'
                    ? targetName || '灵知'
                    : '通知'
              }}
            </div>
            <MessageBody :created-at="message.created_at">
              <Markdown :text="message.content" :streaming="message.state === 'streaming'" />
            </MessageBody>
            <section v-if="message.card" class="message-card">
              <strong>{{ message.card.title }}</strong
              ><Markdown :text="message.card.body" />
            </section>
            <p
              v-for="(reference, index) in message.references"
              :key="index"
              class="reference-label"
            >
              {{ reference.label }}
            </p>
            <ol v-if="citations(message).length" class="message-citations" aria-label="知识依据">
              <li
                v-for="citation in citations(message)"
                :id="citationAnchor(citation.citation_id)"
                :key="citation.item_id"
                tabindex="-1"
              >
                {{ citation.label }}
              </li>
            </ol>
            <div
              v-if="messageStatus(message) || message.message_version || canCopy(message)"
              class="message-footer"
            >
              <p
                v-if="messageStatus(message)"
                :class="['message-output-status', message.state]"
                role="status"
                :data-output-state="message.state"
              >
                <span
                  v-if="message.state === 'streaming'"
                  class="stream-indicator"
                  aria-hidden="true"
                >
                  <i /><i /><i />
                </span>
                {{ messageStatus(message) }}
              </p>
              <ChatButton
                v-if="canCopy(message)"
                text
                size="small"
                class="message-copy"
                @click="copyAnswer(message)"
                ><IconCopy class="reference-icon" aria-hidden="true" />复制回答</ChatButton
              >
              <ChatButton
                v-if="message.message_version && message.state !== 'streaming'"
                text
                size="small"
                class="message-reference"
                :disabled="locked"
                @click="
                  emit('reference', {
                    label: message.content.slice(0, 60) || '会话消息',
                    source: {
                      kind: 'message',
                      source: { kind: 'session' },
                      message_id: message.message_id,
                      message_version: message.message_version
                    }
                  })
                "
                ><IconQuote class="reference-icon" aria-hidden="true" />引用</ChatButton
              >
            </div>
          </article>
        </template>
      </template>
    </template>
    <article
      v-for="preview in turnPreviews"
      :key="preview.run_id"
      class="preview-message"
      :data-preview-run="preview.run_id"
      data-authoritative="false"
    >
      <p class="message-output-status" role="status">生成中预览 · 非最终答复</p>
      <p class="preview-content">{{ preview.content }}</p>
    </article>
    <p v-if="stopReason(turn)" class="message-output-status" role="status">
      停止原因：{{ stopReason(turn) }}
    </p>
    <ArtifactCards :artifacts="artifacts" :run-ids="turn.runIds" @open="emit('artifact', $event)" />
  </section>
</template>

<style scoped>
.preview-message {
  padding: var(--space-3);
  border: 1px dashed var(--border);
  border-radius: var(--radius);
  margin-block: var(--space-3);
}
.preview-content {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.history-retention {
  margin: 0 0 var(--space-4);
  color: var(--muted);
  font-size: 13px;
}
.interaction-reply-label {
  display: block;
  margin-bottom: 6px;
  color: var(--muted);
  font-size: 11px;
}
.message-attachment-summary {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 8px;
  color: var(--muted);
  font-size: 13px;
}
.message-attachment-summary:last-child {
  margin-bottom: 0;
}
.conversation-turn {
  margin-bottom: var(--space-4);
}
.message {
  margin: 0 0 var(--space-2);
}
.conversation-turn > :last-child {
  margin-bottom: 0;
}
.message.user {
  padding: 12px var(--space-2);
  border-radius: var(--radius);
  background: var(--soft);
}
.message.user :deep(.markdown) {
  font-size: 15px;
  line-height: 1.8;
}
.message.assistant > .message-body :deep(.markdown) {
  font-size: 16px;
  line-height: 1.8;
}
.message-body:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: 4px;
  border-radius: var(--radius-sm);
}
.message.assistant :deep(.markdown h2) {
  margin: 18px 0 16px;
  font-size: 21px;
  font-weight: 500;
  letter-spacing: 0;
  line-height: 1.6;
}
.message.assistant :deep(.markdown h3) {
  margin: 22px 0 8px;
  font-size: 18px;
  font-weight: 500;
}
.message-label {
  gap: 8px;
  margin-bottom: var(--space-1);
  font-size: 12px;
  font-weight: 500;
}
.message-label::before {
  content: none;
}
.message-mark {
  display: grid;
  place-items: center;
  width: 23px;
  height: 23px;
  border-radius: var(--radius);
  color: var(--accent);
  background: var(--notice);
}
.message-mark svg,
.reference-icon {
  width: 14px;
  height: 14px;
  flex: none;
  stroke: currentColor;
  stroke-width: 1.4;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.message-reference {
  height: auto;
  min-height: 26px;
  padding: 4px 7px;
  border-radius: var(--radius-sm);
  color: var(--muted);
  font-size: 11px;
  font-weight: 400;
  opacity: 1;
}
/* 执行说明保留原文和交互，用较轻的层级区分最终答复。 */
.message.assistant.is-commentary {
  padding-left: 12px;
  border-left: 1px solid var(--border);
  color: var(--muted);
}
.conversation-turn > .is-execution-start {
  margin-top: 8px;
}
.conversation-turn > .is-execution-continuation {
  padding-top: 4px;
}
.conversation-turn > :is(.process-group, .is-commentary):not(.is-execution-end) {
  margin-bottom: 0;
  padding-bottom: 4px;
}
.conversation-turn > .is-execution-end {
  margin-bottom: 16px;
}
.is-commentary .message-label {
  gap: 6px;
  margin-bottom: 4px;
  font-size: 11px;
  font-weight: 400;
}
.is-commentary .message-mark {
  width: 14px;
  height: 14px;
  border-radius: 0;
  color: var(--muted);
  background: transparent;
}
.message.assistant.is-commentary > .message-body :deep(.markdown) {
  font-size: 12px;
  line-height: 1.6;
}
.message.assistant.is-commentary :deep(.markdown :is(h1, h2, h3, h4)) {
  margin: 8px 0 4px;
  font-size: 12px;
  font-weight: 500;
  line-height: 1.65;
}
.is-commentary :deep(.markdown :is(p, ul, ol)) {
  margin-bottom: 6px;
}
.is-commentary :deep(.markdown li) {
  margin-bottom: 3px;
}
.is-commentary :deep(.markdown :is(blockquote, pre, table)) {
  margin: 6px 0;
}
.is-commentary :deep(.markdown-section:last-child > :last-child) {
  margin-bottom: 0;
}
.is-commentary .message-footer {
  margin-top: 0;
}
.message-footer {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--space-1);
  min-height: 26px;
  margin-top: var(--space-1);
}
.message-footer .message-reference {
  margin: 0;
}
.message-reference:not(:disabled):hover {
  color: var(--accent);
  background: var(--notice);
}
.reference-icon {
  margin-right: 5px;
}
.message-output-status {
  min-height: 26px;
  margin: 0;
  gap: 7px;
  font-size: 11px;
}
.stream-indicator {
  display: inline-flex;
  align-items: center;
  flex: none;
  gap: 3px;
  width: 18px;
  height: 12px;
  color: var(--accent);
}
.stream-indicator i {
  width: 4px;
  height: 4px;
  border-radius: 50%;
  background: currentColor;
  animation: stream-pulse 1200ms var(--ease) infinite;
}
.stream-indicator i:nth-child(2) {
  animation-delay: 160ms;
}
.stream-indicator i:nth-child(3) {
  animation-delay: 320ms;
}
@keyframes stream-pulse {
  0%,
  70%,
  100% {
    opacity: 0.35;
  }
  35% {
    opacity: 1;
  }
}
@media (max-width: 640px) {
  .conversation-turn {
    margin-bottom: var(--space-3);
  }
  .message.user {
    padding: 12px var(--space-2);
  }
  .message.user :deep(.markdown) {
    font-size: 14px;
  }
  .message.assistant > .message-body :deep(.markdown) {
    font-size: 15px;
  }
  .message.assistant.is-commentary > .message-body :deep(.markdown) {
    font-size: 12px;
  }
  .message.assistant :deep(.markdown h2) {
    font-size: 18px;
  }
}
@media (prefers-reduced-motion: reduce) {
  .conversation-turn,
  .stream-indicator i {
    animation: none;
  }
}
</style>
