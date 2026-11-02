<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { query, type Client } from '../client'
import { ChatMessageNotice, ChatButton, ChatDescriptions, ChatDescriptionsItem } from './controls'
import { budgetCount, budgetDimensions, budgetScenarios, type RunBudget } from './runBudget'

const props = defineProps<{ client: Client; session: string; run: string; active: boolean }>()
const budget = ref<RunBudget>(),
  loading = ref(false),
  error = ref('')
let abort: AbortController | undefined,
  epoch = 0
function cancel() {
  epoch++
  abort?.abort()
  loading.value = false
}
async function refresh() {
  if (!props.active || !props.session || !props.run) return
  cancel()
  const current = epoch,
    session = props.session,
    run = props.run
  const controller = new AbortController()
  abort = controller
  loading.value = true
  error.value = ''
  try {
    const value = await props.client.json<RunBudget>(
      `/chat/runs/budget?${query({ session_id: session, run_id: run })}`,
      'GET',
      undefined,
      controller.signal
    )
    if (current !== epoch || controller.signal.aborted) return
    if (value.run_id !== run) throw new Error('运行额度身份不匹配，请点击“刷新运行额度”重新查询。')
    budget.value = value
  } catch (cause) {
    if (current === epoch && !controller.signal.aborted)
      error.value = cause instanceof Error ? cause.message : '运行额度读取失败。'
  } finally {
    if (current === epoch) loading.value = false
  }
}
watch(
  () => [props.session, props.run, props.client, props.active],
  () => {
    cancel()
    budget.value = undefined
    error.value = ''
    if (props.active) void refresh()
  },
  { immediate: true }
)
onBeforeUnmount(cancel)
</script>
<template>
  <section class="run-budget" aria-label="运行额度">
    <h3>运行额度</h3>
    <p v-if="!run">当前没有可定位的运行。</p>
    <template v-else>
      <ChatButton :loading="loading" :disabled="loading" @click="refresh">刷新运行额度</ChatButton>
      <p v-if="loading" role="status">正在读取已登记的运行额度。</p>
      <ChatMessageNotice v-if="error" :title="error" type="error" :closable="false" />
      <p v-if="error && budget" class="muted">刷新失败，以下保留上次读取的额度观察。</p>
      <template v-if="budget">
        <details>
          <summary>本次冻结来源</summary>
          <ChatDescriptions :column="1" title="本次冻结来源">
            <ChatDescriptionsItem label="当前运行"
              ><span class="budget-source">{{ budget.run_id }}</span></ChatDescriptionsItem
            >
            <ChatDescriptionsItem label="共享任务根"
              ><span class="budget-source">{{ budget.root_run_id }}</span></ChatDescriptionsItem
            >
            <ChatDescriptionsItem label="场景">{{
              budgetScenarios[budget.scenario] ?? budget.scenario
            }}</ChatDescriptionsItem>
            <ChatDescriptionsItem label="角色">{{
              budget.role === 'PRIMARY'
                ? '主 Agent'
                : budget.role === 'MEMBER'
                  ? '成员'
                  : budget.role
            }}</ChatDescriptionsItem>
            <ChatDescriptionsItem label="阶段">{{
              budget.phase === 'CLOSING'
                ? '收尾'
                : budget.phase === 'BUSINESS'
                  ? '业务执行'
                  : budget.phase
            }}</ChatDescriptionsItem>
            <ChatDescriptionsItem label="配置来源"
              ><span class="budget-source">{{ budget.source_version }}</span></ChatDescriptionsItem
            >
          </ChatDescriptions>
        </details>
        <section
          v-for="[key, title] in [
            ['root', '任务根共享额度'],
            ['run', '当前运行额度']
          ] as const"
          :key="key"
        >
          <h4>{{ title }}</h4>
          <div class="chat-table" tabindex="0" :aria-label="title">
            <table>
              <thead>
                <tr>
                  <th scope="col">资源</th>
                  <th scope="col">有效上限</th>
                  <th scope="col">已消耗</th>
                  <th scope="col">未结算预留</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="[dimension, label] in budgetDimensions" :key="dimension">
                  <th scope="row">{{ label }}</th>
                  <td>{{ budgetCount(budget[key].limits[dimension]) }}</td>
                  <td>{{ budgetCount(budget[key].consumed[dimension]) }}</td>
                  <td>{{ budgetCount(budget[key].reserved[dimension]) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
          <p>
            活跃时长上限 {{ budgetCount(budget[key].active_seconds_limit) }} 秒；已确认活跃耗时
            {{ budgetCount(budget[key].confirmed_active_millis) }} 毫秒。
          </p>
        </section>
        <details>
          <summary>收尾保护额度</summary>
          <dl>
            <div v-for="[dimension, label] in budgetDimensions" :key="dimension">
              <dt>{{ label }}</dt>
              <dd>{{ budgetCount(budget.closing.resources[dimension]) }}</dd>
            </div>
            <div>
              <dt>活跃时长保护</dt>
              <dd>{{ budgetCount(budget.closing.active_seconds) }} 秒</dd>
            </div>
          </dl>
        </details>
        <p class="muted">
          以上来自本次运行
          的冻结配置和资源账本。未结算或结果未知的调用继续占用预留；暂停与等待不按墙钟计入活跃耗时。此处不推算可继续使用的余额。
        </p>
      </template>
    </template>
  </section>
</template>
<style scoped>
.budget-source {
  overflow-wrap: anywhere;
}
.run-budget h4 {
  margin: var(--space-4) 0 var(--space-2);
}
.run-budget th {
  text-align: left;
}
.run-budget td {
  white-space: nowrap;
}
.run-budget details dl > div {
  display: flex;
  justify-content: space-between;
  gap: var(--space-2);
}
</style>
