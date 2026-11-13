<template>
  <AdminPage title="工作流" description="设计、验证、发布并安装工作流。"
    ><template #extra
      ><ElButton
        v-permission="['foundation.workflows.create']"
        type="primary"
        @click="router.push('/admin/workflows/create')"
        >新建工作流</ElButton
      ></template
    ><FeedbackNotice v-if="error" :title="error" :closable="false" type="error" />
    <div class="admin-toolbar admin-filters">
      <ElInput
        v-model="query"
        clearable
        aria-label="搜索工作流"
        placeholder="搜索名称、标识或说明"
      />
      <ElSelect v-model="status" clearable aria-label="工作流状态" placeholder="全部状态">
        <ElOption label="草稿待发布" value="draft" /><ElOption
          label="已发布"
          value="published"
        /><ElOption label="已归档" value="archived" />
      </ElSelect>
      <ElButton :loading="loading" @click="reload">刷新</ElButton>
      <span class="admin-muted" role="status">{{ filtered.length }} 项工作流</span>
    </div>
    <ElTable
      table-layout="auto"
      :data="filtered"
      v-loading="loading"
      stripe
      :empty-text="
        query || status
          ? '没有匹配的工作流，请调整筛选条件'
          : '尚无工作流，新建后添加节点并连接处理步骤'
      "
      ><ElTableColumn min-width="190" prop="name" label="工作流"
        ><template #default="{ row }"
          ><RouterLink :to="'/admin/workflows/' + encodeURIComponent(row.workflowId)">{{
            row.name || row.workflowId
          }}</RouterLink>
          <div class="admin-field-help">
            {{ statusLabel(row.status) }} ·
            {{ row.activeDefinitionVersionId ? '已发布' : '尚未发布' }} ·
            {{ row.status === 'draft' ? '草稿待发布' : '与发布一致' }}
          </div></template
        ></ElTableColumn
      ><ElTableColumn
        min-width="150"
        show-overflow-tooltip
        prop="workflowId"
        label="标识"
      /><ElTableColumn
        min-width="180"
        show-overflow-tooltip
        prop="description"
        label="说明"
      /><ElTableColumn label="操作" width="104" fixed="right"
        ><template #default="{ row }">
          <ElButton
            link
            @click="router.push('/admin/workflows/' + encodeURIComponent(row.workflowId))"
            >{{ row.status === 'archived' ? '查看流程' : '设计流程' }}</ElButton
          >
        </template></ElTableColumn
      ></ElTable
    ></AdminPage
  >
</template>
<script setup lang="ts">
import FeedbackNotice from '@skillforge/shared/feedback/FeedbackNotice.vue'
import { statusLabel } from '@skillforge/shared/admin'
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { AdminPage, useResource } from '@skillforge/shared/admin'
import { api } from '../../api'
const router = useRouter(),
  { data, loading, error, reload } = useResource(() => api.workflowDrafts.list())
onMounted(reload)
const query = ref(''),
  status = ref('')
const filtered = computed(() =>
  (data.value?.items ?? []).filter(
    (item) =>
      (item.name + item.workflowId + (item.description ?? ''))
        .toLowerCase()
        .includes(query.value.trim().toLowerCase()) &&
      (!status.value || item.status === status.value)
  )
)
</script>
