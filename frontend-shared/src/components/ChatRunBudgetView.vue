<script setup lang="ts">
import { ElDescriptions, ElDescriptionsItem, ElTable, ElTableColumn } from 'element-plus'
import {
  budgetCount,
  budgetDimensions,
  budgetScenarios,
  type RunBudget,
  type RunBudgetScope
} from './chatRunBudget'
defineProps<{ budget: RunBudget }>()
function rows(scope: RunBudgetScope) {
  return budgetDimensions.map(([key, label]) => ({
    resource: label,
    limit: budgetCount(scope.limits[key]),
    consumed: budgetCount(scope.consumed[key]),
    reserved: budgetCount(scope.reserved[key])
  }))
}
</script>
<template>
  <section aria-label="运行额度">
    <ElDescriptions :column="1" border>
      <ElDescriptionsItem label="当前运行">{{ budget.run_id }}</ElDescriptionsItem>
      <ElDescriptionsItem label="共享任务根">{{ budget.root_run_id }}</ElDescriptionsItem>
      <ElDescriptionsItem label="冻结来源">{{ budget.source_version }}</ElDescriptionsItem>
      <ElDescriptionsItem label="场景">{{
        budgetScenarios[budget.scenario] ?? budget.scenario
      }}</ElDescriptionsItem>
      <ElDescriptionsItem label="角色">{{
        budget.role === 'PRIMARY' ? '主 Agent' : budget.role === 'MEMBER' ? '成员' : budget.role
      }}</ElDescriptionsItem>
      <ElDescriptionsItem label="阶段">{{
        budget.phase === 'CLOSING'
          ? '收尾'
          : budget.phase === 'BUSINESS'
            ? '业务执行'
            : budget.phase
      }}</ElDescriptionsItem>
    </ElDescriptions>
    <section
      v-for="[key, title] in [
        ['root', '任务根共享额度'],
        ['run', '当前运行额度']
      ] as const"
      :key="key"
    >
      <h4>{{ title }}</h4>
      <ElTable :data="rows(budget[key])" :aria-label="title">
        <ElTableColumn prop="resource" label="资源" min-width="150" />
        <ElTableColumn prop="limit" label="有效上限" min-width="150" />
        <ElTableColumn prop="consumed" label="已消耗" min-width="150" />
        <ElTableColumn prop="reserved" label="未结算预留" min-width="150" />
      </ElTable>
      <p>
        活跃时长上限 {{ budgetCount(budget[key].active_seconds_limit) }} 秒；已确认活跃耗时
        {{ budgetCount(budget[key].confirmed_active_millis) }} 毫秒。
      </p>
    </section>
    <details>
      <summary>收尾保护额度</summary>
      <ElDescriptions :column="1" border>
        <ElDescriptionsItem
          v-for="[dimension, label] in budgetDimensions"
          :key="dimension"
          :label="label"
          >{{ budgetCount(budget.closing.resources[dimension]) }}</ElDescriptionsItem
        >
        <ElDescriptionsItem label="活跃时长保护"
          >{{ budgetCount(budget.closing.active_seconds) }} 秒</ElDescriptionsItem
        >
      </ElDescriptions>
    </details>
    <p class="admin-field-help">
      限额来自本次运行
      的冻结配置，消费与预留来自资源账本；结果未知不释放预留。此处不根据调用次数推算
      Tokens，也不计算可用余额。
    </p>
  </section>
</template>
