<script setup lang="ts">
import {
  formatToken,
  formatCacheHitRatio,
  type TokenTotals,
  type CacheStats,
  type UsageCoverage
} from '../../api/sessionObservation'
import { messageState } from './sessionTimeline'
defineProps<{
  totals?: TokenTotals | null
  cache?: CacheStats | null
  coverage?: UsageCoverage | null
}>()
</script>
<template>
  <div class="usage-summary" aria-label="已记录 Token 与缓存用量">
    <span
      >输入 <b>{{ formatToken(totals?.prompt_tokens) }}</b></span
    >
    <span
      >输出 <b>{{ formatToken(totals?.completion_tokens) }}</b></span
    >
    <span
      >缓存读取 <b>{{ formatToken(totals?.cached_input_tokens) }}</b></span
    >
    <span
      >命中率 <b>{{ formatCacheHitRatio(cache?.hit_ratio) }}</b></span
    >
    <span v-if="coverage"
      >报告 {{ coverage.reported_attempts }}/{{ coverage.total_attempts }} ·
      {{ messageState(coverage.status) }}</span
    >
    <span v-else class="muted">用量未单独记录</span>
  </div>
</template>
<style scoped>
.usage-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 16px;
  color: var(--admin-muted);
  font-size: var(--font-size-xs, 12px);
}
.usage-summary b {
  color: var(--admin-text);
  font-weight: 500;
  font-variant-numeric: tabular-nums;
}
</style>
