<script setup lang="ts">
import FeedbackNotice from '@skillforge/shared/feedback/FeedbackNotice.vue'
import { computed, onScopeDispose, watch } from 'vue'
import { statusLabel, useResource } from '@skillforge/shared/admin'
import { api, formatToken } from '../../api'
import { attemptUsageMetrics, observationTime, recordedSpan } from './sessionTimeline'
import SessionRecordedContent from './SessionRecordedContent.vue'
import JsonViewer from '@skillforge/shared/components/JsonViewer.vue'
const props = defineProps<{
  tenantId: string
  sessionId: string
  attemptId: string
  rootInputId?: string
  parentSessionId?: string
  parentRootInputId?: string
  scopeKey: string
}>()
const task = useResource(async (signal) => {
  const identity = {
    tenant: props.tenantId,
    session: props.sessionId,
    attempt: props.attemptId,
    root: props.rootInputId,
    parentSession: props.parentSessionId,
    parentRoot: props.parentRootInputId
  }
  const value = await api.sessions.modelAttemptDetail(
    identity.tenant,
    identity.session,
    identity.attempt,
    identity.root,
    { signal, silent: true },
    identity.parentSession && identity.parentRoot
      ? { session_id: identity.parentSession, root_input_id: identity.parentRoot }
      : undefined
  )
  if (
    value.tenant_id !== identity.tenant ||
    value.session_id !== identity.session ||
    value.attempt_id !== identity.attempt ||
    (identity.root && value.root_input_id !== identity.root)
  )
    throw new Error('调用详情身份不匹配，请重新读取。')
  return value
})
watch(
  () =>
    JSON.stringify([
      props.scopeKey,
      props.tenantId,
      props.sessionId,
      props.rootInputId ?? null,
      props.parentSessionId ?? null,
      props.parentRootInputId ?? null,
      props.attemptId
    ]),
  () => {
    task.invalidate()
    task.data.value = undefined
    task.error.value = ''
    void task.reload()
  },
  { immediate: true }
)
const detail = task.data
onScopeDispose(() => {
  task.data.value = undefined
  task.error.value = ''
})
function object(value: unknown): Record<string, unknown> | undefined {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : undefined
}
const input = computed(() => object(detail.value?.input.value))
const messages = computed(() =>
  Array.isArray(input.value?.messages)
    ? input.value.messages.map((message: unknown) => object(message) ?? { content: message })
    : []
)
const tools = computed(() => (Array.isArray(input.value?.tools) ? input.value.tools : []))
const metrics = computed(() => attemptUsageMetrics(detail.value?.usage?.totals))
const availableInput = computed(
  () => detail.value && ['FULL', 'PARTIAL'].includes(detail.value.input.availability)
)
function field(value: unknown) {
  return value === null || value === undefined || value === '' ? '未记录' : String(value)
}
const inputSetting = computed(() => {
  const keys = ['generation', 'response', 'reasoning', 'tool_choice'].filter(
    (key) => input.value && key in input.value
  )
  return keys.length ? Object.fromEntries(keys.map((key) => [key, input.value![key]])) : undefined
})
</script>
<template>
  <div class="attempt-diagnostics" :data-diagnostic-attempt-id="attemptId">
    <p class="muted">
      每次模型调用独立读取。输入是冻结模型请求的可读投影，供应商 HTTP 请求由适配器转换生成。
    </p>
    <FeedbackNotice
      v-if="task.error.value"
      :title="task.error.value"
      type="error"
      :closable="false"
      show-icon
    />
    <ElButton v-if="task.error.value" :loading="task.loading.value" @click="task.reload"
      >重试调用详情</ElButton
    >
    <ElSkeleton v-if="task.loading.value && !detail" :rows="6" />
    <template v-if="detail">
      <div class="diagnostic-section">
        <SessionRecordedContent :content="detail.output" label="模型输出" />
      </div>
      <details class="attempt-metadata">
        <summary>调用状态、标识与用量</summary>
        <ElDescriptions :column="2" size="small" border>
          <ElDescriptionsItem label="模型 / 提供方"
            >{{ detail.model ?? '未记录' }} / {{ detail.provider ?? '未记录' }}</ElDescriptionsItem
          >
          <ElDescriptionsItem label="调用点">{{
            detail.call_point ?? '未记录'
          }}</ElDescriptionsItem>
          <ElDescriptionsItem label="Attempt ID"
            ><code>{{ detail.attempt_id }}</code></ElDescriptionsItem
          >
          <ElDescriptionsItem label="Invocation ID"
            ><code>{{ detail.invocation_id ?? '未记录' }}</code></ElDescriptionsItem
          >
          <ElDescriptionsItem label="Action ID"
            ><code>{{ detail.action_id ?? '未记录' }}</code></ElDescriptionsItem
          >
          <ElDescriptionsItem label="调用状态">{{
            detail.status ? statusLabel(detail.status) : '未记录'
          }}</ElDescriptionsItem>
          <ElDescriptionsItem label="调用登记开始">{{
            observationTime(detail.started_at)
          }}</ElDescriptionsItem>
          <ElDescriptionsItem label="调用登记完成">{{
            observationTime(detail.finished_at)
          }}</ElDescriptionsItem>
          <ElDescriptionsItem label="调用登记边界耗时">{{
            recordedSpan(detail.started_at, detail.finished_at)
          }}</ElDescriptionsItem>
          <ElDescriptionsItem label="读取时间">{{
            observationTime(detail.read_at)
          }}</ElDescriptionsItem>
          <ElDescriptionsItem label="Request ID / 供应商响应 ID"
            >未记录 / 未记录</ElDescriptionsItem
          >
          <ElDescriptionsItem label="系统指纹 / TTFT">未记录 / 未记录</ElDescriptionsItem>
          <ElDescriptionsItem label="Runtime 记录的结束码">{{
            detail.finish_reason ?? '未记录'
          }}</ElDescriptionsItem>
          <ElDescriptionsItem label="Root / Run"
            ><code
              >{{ detail.root_input_id ?? '未关联根输入' }} / {{ detail.run_id ?? '未记录' }}</code
            ></ElDescriptionsItem
          >
          <ElDescriptionsItem label="Loop / iteration"
            ><code
              >{{ detail.loop_id ?? '未记录' }} / {{ detail.iteration ?? '未记录' }}</code
            ></ElDescriptionsItem
          >
          <ElDescriptionsItem label="输入 / 输出 Token"
            >{{ formatToken(detail.usage?.totals?.prompt_tokens) }} /
            {{ formatToken(detail.usage?.totals?.completion_tokens) }}</ElDescriptionsItem
          >
          <ElDescriptionsItem label="缓存读取 / 写入 Token"
            >{{ formatToken(detail.usage?.totals?.cached_input_tokens) }} /
            {{ formatToken(detail.usage?.totals?.cache_write_input_tokens) }}</ElDescriptionsItem
          >
          <ElDescriptionsItem label="缓存未命中 / 命中率"
            >{{ formatToken(metrics.uncachedInput) }} / {{ metrics.hitRatio }}</ElDescriptionsItem
          >
          <ElDescriptionsItem label="合计 Token（输入 + 输出）">{{
            formatToken(metrics.total)
          }}</ElDescriptionsItem>
          <ElDescriptionsItem label="推理输出 Token">{{
            formatToken(detail.usage?.totals?.reasoning_output_tokens)
          }}</ElDescriptionsItem>
        </ElDescriptions>
      </details>
      <section class="diagnostic-section" aria-label="输入提示词 / 消息">
        <h4>输入提示词 / 消息</h4>
        <p class="muted">
          来源 {{ detail.input.source ?? '未记录' }} · {{ detail.input.availability }} ·
          保持冻结消息顺序；投影的 JSON 长度不代表供应商计费 Token
        </p>
        <template v-if="availableInput && input">
          <ElCollapse class="input-messages">
            <ElCollapseItem
              v-for="(message, index) in messages"
              :key="index"
              :name="index"
              :data-input-message-index="index"
            >
              <template #title
                ><span
                  >消息 {{ index + 1 }} · {{ field(message.role) }} ·
                  {{ field(message.message_id) }}</span
                ></template
              >
              <JsonViewer :value="message.content" :label="`消息 ${index + 1} 正文`" />
              <details
                v-if="message.contents !== null && message.contents !== undefined"
                class="message-blocks"
              >
                <summary>查看内容块与媒体信息</summary>
                <p class="muted">上方正文由内容块中的文本拼接而成，两种视图展示同一份消息。</p>
                <JsonViewer :value="message.contents" :label="`消息 ${index + 1} 内容块`" />
              </details>
              <template
                v-for="key in ['tool_calls', 'tool_call_id', 'reasoning_content']"
                :key="key"
              >
                <template v-if="message[key] !== null && message[key] !== undefined"
                  ><strong>{{ key }}</strong>
                  <JsonViewer :value="message[key]" :label="`消息 ${index + 1} ${key}`" />
                </template>
              </template>
            </ElCollapseItem>
          </ElCollapse>
          <p v-if="!messages.length" class="muted">输入消息未记录</p>
          <ElCollapse>
            <ElCollapseItem name="tools" :title="`可调用能力定义（${tools.length}）`"
              ><p class="muted">这是输入中的能力定义；实际能力调用单独列在下方。</p>
              <JsonViewer :value="input.tools" label="可调用能力定义" />
            </ElCollapseItem>
            <ElCollapseItem name="settings" title="已记录请求配置">
              <JsonViewer :value="inputSetting" label="已记录请求配置" />
            </ElCollapseItem>
            <ElCollapseItem name="complete-input" title="完整冻结请求投影">
              <JsonViewer :value="detail.input.value" label="完整冻结请求投影" />
            </ElCollapseItem>
          </ElCollapse>
        </template>
        <SessionRecordedContent v-else :content="detail.input" label="冻结输入材料" />
      </section>
      <div class="diagnostic-section">
        <p v-if="detail.reasoning_history_retention" class="muted" role="note">
          {{ detail.reasoning_history_retention.description }}。所属Session累计退役推理事件：
          {{ formatToken(detail.reasoning_history_retention.retired_reasoning_event_count) }}
        </p>
        <SessionRecordedContent :content="detail.reasoning" label="供应商思考内容" />
      </div>
      <div class="diagnostic-section">
        <p class="muted">
          模型动作是 Runtime 归一化的已记录结果；提出的动作与下方实际能力调用分别展示。
        </p>
        <SessionRecordedContent :content="detail.decision" label="模型动作" />
      </div>

      <section class="diagnostic-section" aria-label="实际能力调用">
        <h4>实际能力调用</h4>
        <p class="muted">
          关联可用性 {{ detail.capability_calls_availability }}；不按时间或相邻事件推测关联。
        </p>
        <ElCollapse v-if="detail.capability_calls.length">
          <ElCollapseItem
            v-for="(call, index) in detail.capability_calls"
            :key="call.call_id ?? call.action_id ?? index"
            :name="index"
            :data-capability-call-id="call.call_id ?? call.action_id ?? index"
          >
            <template #title
              ><span
                >能力调用 {{ index + 1 }} · {{ call.title ?? '名称未记录' }} ·
                {{ call.kind ?? '类型未记录' }} ·
                {{ call.status ? statusLabel(call.status) : '状态未记录' }}</span
              ></template
            >
            <ElDescriptions :column="2" size="small" border>
              <ElDescriptionsItem label="调用 ID"
                ><code>{{ call.call_id ?? '未记录' }}</code></ElDescriptionsItem
              >
              <ElDescriptionsItem label="Action ID"
                ><code>{{ call.action_id ?? '未记录' }}</code></ElDescriptionsItem
              >
              <ElDescriptionsItem label="Attempt ID"
                ><code>{{ call.attempt_id ?? '未记录' }}</code></ElDescriptionsItem
              >
              <ElDescriptionsItem label="执行状态">{{
                call.status ? statusLabel(call.status) : '未记录'
              }}</ElDescriptionsItem>
            </ElDescriptions>
            <SessionRecordedContent :content="call.input" label="能力调用参数" />
            <SessionRecordedContent :content="call.output" label="能力调用结果" />
          </ElCollapseItem>
        </ElCollapse>
        <p v-else class="muted">
          {{
            detail.capability_calls_availability === 'FULL'
              ? '已确认无能力调用'
              : '实际能力调用未记录'
          }}
        </p>
      </section>
    </template>
  </div>
</template>
<style scoped>
.attempt-metadata {
  margin: 16px 0;
}
.attempt-metadata summary {
  cursor: pointer;
  padding: 8px 0;
  color: var(--admin-muted);
}

.attempt-diagnostics {
  min-width: 0;
}
.diagnostic-section {
  margin-top: 20px;
  padding-top: 12px;
  border-top: 1px solid var(--admin-border);
}
.muted {
  color: var(--admin-muted);
  line-height: 1.6;
  overflow-wrap: anywhere;
}
code {
  overflow-wrap: anywhere;
  font-variant-numeric: tabular-nums;
}
:deep(.el-collapse-item__header) {
  white-space: normal;
  line-height: 1.6;
  min-height: 48px;
  height: auto;
  overflow-wrap: anywhere;
}
</style>
