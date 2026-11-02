<script setup lang="ts">
import { ElDescriptions, ElDescriptionsItem, ElTable, ElTableColumn, ElTag } from 'element-plus'
import type { ModelBillingMonthly } from '../types/billing'
import { statusLabel } from '../admin/presentation'
import { formatDateTime } from '../utils/datetime'

defineProps<{ monthly: ModelBillingMonthly }>()
const costStatuses: Record<string, string> = {
  priced: '已计费',
  unpriced: '缺少模型价格，待核实',
  unknown: '用量待核实',
  unresolved: '费用待核实',
  missing_fx: '缺少汇率，待核实',
  fx_missing: '缺少汇率，待核实',
  missing_price: '缺少模型价格，待核实',
  missing_usage: '用量待核实'
}
</script>

<template>
  <section class="model-billing" aria-label="月度模型费用">
    <ElDescriptions :column="2" border>
      <ElDescriptionsItem label="月份">{{ monthly.month }}（北京时间）</ElDescriptionsItem>
      <ElDescriptionsItem label="模型调用次数">{{ monthly.model_calls }}</ElDescriptionsItem>
      <ElDescriptionsItem label="模型费用合计（元）">{{
        monthly.total_amount ?? '待核实'
      }}</ElDescriptionsItem>
      <ElDescriptionsItem label="已计算费用（元）">{{ monthly.known_amount }}</ElDescriptionsItem>
      <ElDescriptionsItem label="输入 Token">{{
        monthly.prompt_tokens ?? '待核实'
      }}</ElDescriptionsItem>
      <ElDescriptionsItem label="输出 Token">{{
        monthly.completion_tokens ?? '待核实'
      }}</ElDescriptionsItem>
    </ElDescriptions>
    <p
      v-if="monthly.missing_cost_count || monthly.total_amount === null"
      class="admin-field-help"
      role="status"
    >
      {{
        monthly.missing_cost_count
      }}
      条模型费用待核实。已计算费用仅为已知部分，不代表完整费用；未核实不视为零费用。
    </p>
    <p class="admin-field-help">费用按调用时保存的模型价格计算，以下金额和用量直接来自计费记录。</p>
    <ElTable
      :data="monthly.items"
      row-key="id"
      table-layout="auto"
      stripe
      empty-text="本月暂无模型调用费用明细"
    >
      <ElTableColumn label="模型服务" min-width="180">
        <template #default="{ row }">
          <div>{{ statusLabel(row.event_type) }}</div>
          <small>{{ row.provider ?? '—' }} · {{ row.model ?? '—' }}</small>
        </template>
      </ElTableColumn>
      <ElTableColumn label="发生时间" min-width="180">
        <template #default="{ row }">{{ formatDateTime(row.occurred_at) }}</template>
      </ElTableColumn>
      <ElTableColumn label="用量" min-width="190">
        <template #default="{ row }">
          <div v-if="row.quantity !== null">调用 {{ row.quantity }} 次</div>
          <div v-if="row.prompt_tokens !== null">输入 {{ row.prompt_tokens }} Token</div>
          <div v-if="row.completion_tokens !== null">输出 {{ row.completion_tokens }} Token</div>
          <div v-if="row.image_count !== null">图片 {{ row.image_count }} 张</div>
          <div v-if="row.video_count !== null">视频 {{ row.video_count }} 个</div>
          <div v-if="row.characters !== null">计费字符 {{ row.characters }}</div>
          <div v-if="row.duration_seconds !== null">时长 {{ row.duration_seconds }} 秒</div>
          <span
            v-if="
              [
                row.quantity,
                row.prompt_tokens,
                row.completion_tokens,
                row.image_count,
                row.video_count,
                row.characters,
                row.duration_seconds
              ].every((value) => value === null)
            "
            >用量待核实</span
          >
        </template>
      </ElTableColumn>
      <ElTableColumn label="模型费用（元）" min-width="150" align="right">
        <template #default="{ row }">{{ row.amount ?? '待核实' }}</template>
      </ElTableColumn>
      <ElTableColumn label="费用状态" min-width="190">
        <template #default="{ row }"
          ><ElTag :type="row.cost_status === 'priced' ? 'success' : 'warning'">{{
            costStatuses[row.cost_status] ?? `待核实（${row.cost_status}）`
          }}</ElTag></template
        >
      </ElTableColumn>
      <ElTableColumn label="费用依据" min-width="190">
        <template #default="{ row }">
          <details>
            <summary>查看记录与模型价格版本</summary>
            <div>
              原币金额：{{
                row.original_amount === null
                  ? '待核实'
                  : `${row.original_amount} ${row.original_currency ?? '币种待核实'}`
              }}
            </div>
            <div>记录：{{ row.id }}</div>
            <div>价格快照：{{ row.pricing_snapshot_version ?? '待核实' }}</div>
            <div>
              模型配置：{{ row.pricing_model_id ?? '待核实' }} · 修订
              {{ row.pricing_model_revision ?? '待核实' }}
            </div>
            <div>调用：{{ row.invocation_id ?? '—' }}</div>
            <div>尝试：{{ row.attempt_id ?? '—' }}</div>
            <div>会话：{{ row.session_id ?? '—' }}</div>
            <div>运行：{{ row.run_id ?? '—' }}</div>
          </details>
        </template>
      </ElTableColumn>
    </ElTable>
  </section>
</template>

<style scoped>
.model-billing {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
small {
  color: var(--el-text-color-secondary);
}
details {
  overflow-wrap: anywhere;
}
summary {
  cursor: pointer;
  min-height: 24px;
}
summary:focus-visible {
  outline: 2px solid var(--admin-accent);
  outline-offset: 3px;
}
</style>
