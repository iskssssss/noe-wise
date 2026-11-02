<script setup lang="ts">
import { computed } from 'vue'
import { ElAlert } from 'element-plus'
import type { BudgetFailure, TrajectoryTermination } from './types'
import { formatObservationTokens } from './presentation'

const props = defineProps<{ termination: TrajectoryTermination | null }>()
const title = computed(() =>
  props.termination?.reason_code === 'execution_output_closing_budget_insufficient'
    ? '模型准备时无法预留收尾预算'
    : '执行已终止'
)
const failure = computed(() => props.termination?.budget_failure)
const phases = { BUSINESS: '业务阶段', CLOSING: '收尾阶段' }
const dimensions: Record<BudgetFailure['shortfalls'][number]['dimension'], string> = {
  ACTIVE_MILLIS: '执行时间',
  BUSINESS_MODELS: '主模型调用次数',
  AUXILIARY_MODELS: '辅助模型调用次数',
  TOOLS: '工具调用次数',
  KNOWLEDGE: '知识调用次数',
  INPUT_TOKENS: '输入 Token',
  OUTPUT_TOKENS: '输出 Token',
  TOTAL_TOKENS: '合计 Token'
}

/** 仅格式化持久化数值，不从错误文案取数，也不在页面重算预算。 */
function amount(value: string | null | undefined, unit: 'ms' | 'count' | 'tokens'): string {
  if (value === null || value === undefined || !/^\d+$/.test(value)) return '未记录'
  if (unit !== 'ms')
    return `${formatObservationTokens(value)} ${unit === 'tokens' ? 'Token' : '次'}`
  const milliseconds = BigInt(value)
  const seconds = milliseconds / 1000n
  const fraction = (milliseconds % 1000n).toString().padStart(3, '0').replace(/0+$/, '')
  return `${formatObservationTokens(seconds.toString())}${fraction ? '.' + fraction : ''} 秒`
}
</script>

<template>
  <section v-if="termination" class="termination-notice" aria-label="执行终止原因">
    <ElAlert :title="title" type="warning" :closable="false" show-icon />
    <dl class="attempt-counts">
      <div>
        <dt>已登记主模型尝试</dt>
        <dd>{{ amount(termination.business_model_attempts, 'count') }}</dd>
      </div>
      <div>
        <dt>已登记工具尝试</dt>
        <dd>{{ amount(termination.tool_attempts, 'count') }}</dd>
      </div>
      <div>
        <dt>已登记辅助模型尝试</dt>
        <dd>{{ amount(termination.auxiliary_model_attempts, 'count') }}</dd>
      </div>
    </dl>
    <template v-if="failure">
      <p class="budget-phase">预算检查阶段：{{ phases[failure.phase] ?? failure.phase }}</p>
      <p class="budget-ledger">总需求包含已消耗和其他预留；总额度为该范围的原始额度。</p>
      <section
        v-for="item in failure.shortfalls"
        :key="item.scope + ':' + item.dimension"
        class="budget-shortfall"
      >
        <h5>
          {{ item.scope === 'ROOT' ? '根任务预算' : '本次运行预算' }} ·
          {{ dimensions[item.dimension] ?? item.dimension }}
        </h5>
        <dl class="budget-values">
          <div>
            <dt>总需求</dt>
            <dd>{{ amount(item.required, item.unit) }}</dd>
          </div>
          <div>
            <dt>总额度</dt>
            <dd>{{ amount(item.available, item.unit) }}</dd>
          </div>
          <div>
            <dt>不足</dt>
            <dd>{{ amount(item.deficit, item.unit) }}</dd>
          </div>
        </dl>
        <p class="budget-ledger">
          已消耗 {{ amount(item.consumed, item.unit) }} · 已预留
          {{ amount(item.reserved, item.unit) }} · 上限 {{ amount(item.limit, item.unit) }}
        </p>
      </section>
      <p v-if="!failure.shortfalls.length" class="budget-missing">未记录具体预算数值</p>
      <details v-if="failure.time_calculation" class="budget-calculation">
        <summary>查看时间预算计算项</summary>
        <dl>
          <div>
            <dt>模型调用预留</dt>
            <dd>{{ amount(failure.time_calculation.model_calls, 'count') }}</dd>
          </div>
          <div>
            <dt>单次模型超时</dt>
            <dd>{{ amount(failure.time_calculation.model_timeout_ms, 'ms') }}</dd>
          </div>
          <div>
            <dt>共享模型时间窗</dt>
            <dd>{{ amount(failure.time_calculation.model_window_ms, 'ms') }}</dd>
          </div>
          <div>
            <dt>本地处理时间</dt>
            <dd>{{ amount(failure.time_calculation.local_processing_ms, 'ms') }}</dd>
          </div>
          <div>
            <dt>未来收尾时间</dt>
            <dd>{{ amount(failure.time_calculation.required_ms, 'ms') }}</dd>
          </div>
        </dl>
      </details>
    </template>
    <p
      v-else-if="termination.reason_code === 'execution_output_closing_budget_insufficient'"
      class="budget-missing"
    >
      未记录具体预算数值
    </p>
    <details class="termination-source">
      <summary>终止记录与来源</summary>
      <p>
        原因码：<code>{{ termination.reason_code }}</code>
      </p>
      <p>
        Run：<code>{{ termination.run_id }}</code>
      </p>
    </details>
  </section>
</template>

<style scoped>
.termination-notice {
  margin: 16px 0;
  min-width: 0;
}
.attempt-counts,
.budget-values {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  margin: 12px 0;
}
dt,
.budget-phase,
.budget-ledger,
.budget-missing,
.termination-source {
  color: var(--admin-muted);
}
dt {
  font-size: var(--el-font-size-small);
  line-height: 1.5;
}
dd {
  margin: 4px 0 0;
  color: var(--admin-text);
  font-variant-numeric: tabular-nums;
  overflow-wrap: anywhere;
}
.budget-shortfall {
  border-top: 1px solid var(--admin-border);
  padding-top: 12px;
  margin-top: 12px;
}
h5 {
  margin: 0;
  font-size: var(--el-font-size-base);
}
p {
  line-height: 1.6;
  overflow-wrap: anywhere;
}
.budget-ledger {
  font-size: var(--el-font-size-small);
}
summary {
  cursor: pointer;
  padding: 8px 0;
}
.budget-calculation dl > div {
  display: flex;
  gap: 16px;
  justify-content: space-between;
  padding: 6px 0;
}
.budget-calculation dd {
  margin: 0;
}
.termination-source {
  margin-top: 8px;
  font-size: var(--el-font-size-small);
}
code {
  overflow-wrap: anywhere;
}
@media (max-width: 720px) {
  .attempt-counts,
  .budget-values {
    grid-template-columns: minmax(0, 1fr);
    gap: 8px;
  }
}
</style>
