<script setup lang="ts">
import { computed, ref } from 'vue'
import type { AgentDefinitionImpact } from '../../api'
const props = defineProps<{ impact: AgentDefinitionImpact; stale: boolean; candidate?: boolean }>()
const onlyIssues = ref(false)
const affected = computed(() =>
  props.impact.deployments.filter((item) => !item.draft_declarations_satisfied)
)
const deployments = computed(() => (onlyIssues.value ? affected.value : props.impact.deployments))
const changes = computed(() => {
  const value = props.impact.changes
  const slots = (items: Array<{ label: string; key: string }>) =>
    items.map((slot) => `${slot.label}（${slot.key}）`).join('、')
  return [
    { label: '新增资源槽', text: slots(value.added_resource_slots) },
    { label: '变更资源槽', text: slots(value.changed_resource_slots) },
    { label: '移除资源槽', text: value.removed_resource_slot_keys.join('、') },
    { label: '新增委派槽', text: slots(value.added_delegation_slots) },
    { label: '移除委派槽', text: value.removed_delegation_slot_keys.join('、') },
    { label: '新增参数', text: value.added_parameter_keys.join('、') },
    { label: '移除参数', text: value.removed_parameter_keys.join('、') }
  ].filter((item) => item.text)
})
</script>
<template>
  <ElCard class="admin-card">
    <h3 v-if="candidate">未保存内容的发布影响（基于草稿修订 {{ impact.draft_revision }}）</h3>
    <h3 v-else>草稿修订 {{ impact.draft_revision }} 的发布影响</h3>
    <ElAlert
      v-if="stale"
      title="此预览已失效，仅保留供对照；请保存、验证并重新预览当前草稿。"
      type="warning"
      :closable="false"
    />
    <p>
      相对当前发布 {{ impact.current_published_revision ?? '尚未发布' }}，当前权限可见
      {{ impact.visible_deployment_count }} 个部署。
    </p>
    <p class="admin-field-help">
      以下只验证声明结构，不能证明模型、连接、资源可用性或当前执行权限全部就绪。运行中任务保留原冻结配置。
    </p>
    <ElDescriptions v-if="changes.length" :column="1" border>
      <ElDescriptionsItem v-for="change in changes" :key="change.label" :label="change.label">{{
        change.text
      }}</ElDescriptionsItem>
    </ElDescriptions>
    <p v-else class="admin-muted">资源槽、委派槽与参数声明没有增删。</p>
    <div class="admin-toolbar">
      <span role="status">本次预览中 {{ affected.length }} 个部署需补齐声明</span>
      <ElCheckbox v-model="onlyIssues">只看需补齐声明的部署</ElCheckbox>
    </div>
    <ElTable
      :data="deployments"
      row-key="deployment_id"
      table-layout="auto"
      :empty-text="onlyIssues ? '当前预览中没有需补齐声明的部署' : '当前权限范围没有可见部署'"
    >
      <ElTableColumn prop="tenant_id" label="机构标识" min-width="170" show-overflow-tooltip />
      <ElTableColumn prop="deployment_id" label="部署标识" min-width="170" show-overflow-tooltip />
      <ElTableColumn prop="agent_id" label="稳定 Agent" min-width="170" show-overflow-tooltip />
      <ElTableColumn prop="deployment_revision" label="绑定修订" width="110" />
      <ElTableColumn label="当前发布声明" min-width="180"
        ><template #default="{ row }"
          >{{
            row.current_declarations_satisfied === null
              ? '尚无发布'
              : row.current_declarations_satisfied
                ? '声明满足'
                : '待完善'
          }}
          <ul v-if="row.current_issues.length">
            <li v-for="issue in row.current_issues" :key="issue.path + issue.code">
              {{ issue.path }}：{{ issue.message }}
            </li>
          </ul></template
        ></ElTableColumn
      >
      <ElTableColumn label="发布本草稿后" min-width="230"
        ><template #default="{ row }"
          ><ElTag :type="row.draft_declarations_satisfied ? 'success' : 'warning'">{{
            row.draft_declarations_satisfied ? '声明满足' : '需补齐声明'
          }}</ElTag>
          <ul v-if="row.draft_issues.length">
            <li v-for="issue in row.draft_issues" :key="issue.path + issue.code">
              {{ issue.path }}：{{ issue.message }}
            </li>
          </ul></template
        ></ElTableColumn
      >
    </ElTable>
  </ElCard>
</template>
