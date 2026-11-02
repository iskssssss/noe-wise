<script setup lang="ts">
import type { RecordedContent, RecordedContentAvailability } from '../../api/sessionObservation'
import { computed } from 'vue'
import JsonViewer from '@skillforge/shared/components/JsonViewer.vue'
const props = defineProps<{ content: RecordedContent; label: string }>()
const labels: Record<RecordedContentAvailability, string> = {
  FULL: '已记录',
  PARTIAL: '部分记录',
  NOT_RECORDED: '未记录',
  NOT_RETURNED: '供应商未返回',
  UNKNOWN: '记录情况未知'
}
const available = computed(() => ['FULL', 'PARTIAL'].includes(props.content.availability))
</script>
<template>
  <section class="recorded-content" :aria-label="label">
    <div class="recorded-title">
      <strong>{{ label }}</strong
      ><ElTag size="small" :type="content.availability === 'FULL' ? 'success' : 'info'">{{
        labels[content.availability] ?? content.availability
      }}</ElTag>
    </div>
    <details class="content-source">
      <summary>记录来源</summary>
      <p class="muted">
        来源 {{ content.source ?? '未记录' }} · Schema {{ content.schema_id ?? '未记录' }}
      </p>
    </details>
    <p v-if="content.availability === 'PARTIAL'" class="muted">
      以下仅展示已记录部分，不代表完整正文。
    </p>
    <p v-if="available && content.value === ''" class="muted">已记录空正文</p>
    <JsonViewer
      v-else-if="available && content.value !== undefined"
      :value="content.value"
      :label="label"
    />
    <p v-else class="muted">
      {{ available ? '正文未记录' : (labels[content.availability] ?? content.availability) }}
    </p>
  </section>
</template>
<style scoped>
.content-source {
  margin-top: 8px;
  font-size: 13px;
  color: var(--admin-muted);
}
.content-source summary {
  cursor: pointer;
  padding: 4px 0;
}
.recorded-content {
  min-width: 0;
}

.recorded-title {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}
.muted {
  color: var(--admin-muted);
  overflow-wrap: anywhere;
}
</style>
