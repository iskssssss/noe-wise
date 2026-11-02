<script setup lang="ts">
import { computed } from 'vue'
import { parseBarChart } from './richContent'

const props = defineProps<{ source: string }>()
const chart = computed(() => parseBarChart(props.source))
const maximum = computed(
  () =>
    chart.value?.max ?? (Math.max(0, ...(chart.value?.items.map((item) => item.value) ?? [])) || 1)
)
const format = new Intl.NumberFormat('zh-CN', { maximumSignificantDigits: 15 })
</script>

<template>
  <figure v-if="chart" class="rich-chart" aria-label="条形数据图">
    <figcaption>{{ chart.title || '数据对比' }}</figcaption>
    <ul>
      <li v-for="(item, index) in chart.items" :key="index">
        <div class="chart-label">
          <span>{{ item.label }}</span>
          <strong>{{ format.format(item.value) }}{{ chart.unit || '' }}</strong>
        </div>
        <div class="chart-track" aria-hidden="true">
          <span :style="{ width: `${(item.value / maximum) * 100}%` }" />
        </div>
      </li>
    </ul>
    <small>刻度上限 {{ format.format(maximum) }}{{ chart.unit || '' }}</small>
  </figure>
  <div v-else class="rich-fallback">
    <small>图表数据格式不支持，已保留原文。</small>
    <pre><code>{{ source }}</code></pre>
  </div>
</template>

<style scoped>
.rich-chart {
  margin: 24px 0;
  padding: 0;
  min-width: 0;
}
figcaption {
  font-size: 1em;
  font-weight: 650;
  margin-bottom: 16px;
}
.rich-chart ul {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: 14px;
}
.chart-label {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 16px;
  font-size: 0.9em;
  margin-bottom: 6px;
}
.chart-label span {
  min-width: 0;
  overflow-wrap: anywhere;
}
.chart-label strong {
  min-width: 0;
  max-width: 100%;
  overflow-wrap: anywhere;
  text-align: right;
  font-variant-numeric: tabular-nums;
  font-weight: 600;
}
.chart-track {
  height: 9px;
  background: var(--soft);
  border-radius: var(--radius-sm);
  overflow: hidden;
}
.chart-track span {
  display: block;
  height: 100%;
  min-width: 0;
  background: var(--accent);
  border-radius: inherit;
}
.rich-chart small,
.rich-fallback small {
  display: block;
  margin-top: 12px;
  color: var(--muted);
  font-size: 12px;
}
@media (max-width: 480px) {
  .chart-label {
    flex-wrap: wrap;
    gap: 2px 12px;
  }
}
</style>
