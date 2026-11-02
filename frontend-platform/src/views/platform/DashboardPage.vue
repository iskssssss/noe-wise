<script setup lang="ts">
import FeedbackNotice from '@skillforge/shared/feedback/FeedbackNotice.vue'
import { computed, onMounted, ref } from 'vue'
import { AdminPage } from '@skillforge/shared/admin'
import { api } from '../../api'
import { useTask } from '@skillforge/shared/admin'
import { formatDateTime } from '@skillforge/shared/utils/datetime'
const { busy, error, run } = useTask(),
  updatedAt = ref(''),
  status = ref(''),
  rows = ref<Array<{ name: string; ok: boolean; error?: string }>>([])
const failedCount = computed(() => rows.value.filter((row) => !row.ok).length)
const healthy = computed(() => status.value === 'ok' && failedCount.value === 0)
const statusText = computed(() =>
  status.value === 'ok' ? '服务正常' : status.value === 'degraded' ? '服务降级' : status.value
)
function load() {
  void run(async () => {
    const result = await api.health()
    status.value = result.status
    updatedAt.value = formatDateTime(new Date())
    rows.value = Object.entries(result.deps).map(([name, value]) => ({ name, ...value }))
  })
}
onMounted(load)
</script>

<template>
  <AdminPage title="系统状态" description="查看服务健康与依赖连通情况，异常说明保留在对应服务行。">
    <template #extra>
      <ElButton :loading="busy" @click="load">刷新</ElButton>
    </template>
    <FeedbackNotice
      v-if="error"
      :title="error"
      :description="
        updatedAt
          ? '以下保留上次成功读取的结果，当前状态尚未确认，请重新刷新。'
          : '尚未读取到系统状态，请重新刷新。'
      "
      :closable="false"
      type="error"
    />
    <div class="admin-toolbar" role="status">
      <div class="admin-actions">
        <ElTag v-if="status" :type="healthy ? 'success' : 'danger'">{{
          healthy ? '依赖服务正常' : failedCount ? `${failedCount} 项依赖异常` : statusText
        }}</ElTag
        ><span class="admin-muted"
          >{{ rows.length }} 项依赖 · {{ statusText || '等待读取状态' }}</span
        >
      </div>
      <span v-if="updatedAt" class="admin-muted">最近成功读取：{{ updatedAt }}</span>
    </div>
    <ElTable
      table-layout="auto"
      :empty-text="
        busy
          ? '正在读取依赖状态…'
          : error
            ? '依赖状态尚未读取，请查看上方错误'
            : '此次响应未包含依赖明细'
      "
      v-loading="busy"
      row-key="name"
      :data="rows"
      style="width: 100%"
    >
      <ElTableColumn prop="name" label="依赖服务" min-width="220" show-overflow-tooltip>
      </ElTableColumn>
      <ElTableColumn prop="ok" label="状态" width="100">
        <template #default="{ row }">
          <ElTag :type="row.ok ? 'success' : 'danger'">{{ row.ok ? '正常' : '异常' }}</ElTag>
        </template>
      </ElTableColumn>
      <ElTableColumn prop="error" label="说明" min-width="280" show-overflow-tooltip>
      </ElTableColumn>
    </ElTable>
  </AdminPage>
</template>
