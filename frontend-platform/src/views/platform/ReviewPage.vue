<script setup lang="ts">
import FeedbackNotice from '@skillforge/shared/feedback/FeedbackNotice.vue'
import { useActionPermissions } from '../../composables/actionPermissions'

import { guardFormEnter } from '@skillforge/shared/admin'
import { confirm, message } from '@skillforge/shared/feedback'
import { computed, onMounted, ref } from 'vue'

import {
  AdminPage,
  statusLabel,
  useDrawerClose,
  useTask,
  useResource
} from '@skillforge/shared/admin'
import { api, type MarketplaceReview } from '../../api'
import CodeEditor from '@skillforge/shared/components/CodeEditor.vue'
import { formatDateTime } from '@skillforge/shared/utils/datetime'
const { allowed, checked } = useActionPermissions()
const action = useTask(),
  page = ref(1),
  status = ref('pending'),
  search = ref(''),
  selected = ref<MarketplaceReview>(),
  open = ref(false),
  reason = ref('')
const selectedFile = ref('workflow.yaml')
const detail = useResource(async (signal) => {
  const target = selected.value!
  const value = await api.marketplace.detail(target.id, target.updated_at, { signal, silent: true })
  if (
    value.workflow_id !== target.id ||
    value.updated_at !== target.updated_at ||
    value.version !== target.version
  )
    throw new Error('审核内容与列表修订不一致，请刷新列表后重新查看')
  return value
})
const file = computed(() =>
  detail.data.value?.files.find((value) => value.name === selectedFile.value)
)
const contentReady = computed(
  () =>
    !!detail.data.value?.files.some(
      (value) => value.name === 'workflow.yaml' && !!value.content?.trim()
    ) &&
    !detail.loading.value &&
    !detail.error.value
)
function openReview(row: MarketplaceReview) {
  if (action.busy.value) return
  selected.value = { ...row }
  reason.value = ''
  selectedFile.value = 'workflow.yaml'
  action.error.value = ''
  detail.invalidate()
  detail.data.value = undefined
  open.value = true
  void detail.reload()
}
function closeReview() {
  open.value = false
  detail.invalidate()
  detail.data.value = undefined
}
// 输入中的筛选与当前结果的查询快照分开；翻页只使用已提交条件。
const applied = ref({ search: '', status: 'pending' })
const load = useResource(async (signal) => {
  const requestedPage = page.value
  const result = await api.marketplace.reviews(
    { page: requestedPage, limit: 20, ...applied.value },
    { signal }
  )
  return { ...result, page: requestedPage }
})

const rows = computed(() => load.data.value?.items ?? []),
  total = computed(() => load.data.value?.total ?? 0)
function reload() {
  void load.reload()
}
function approve(row: MarketplaceReview) {
  if (!allowed('foundation.marketplace.publish')) return
  if (!contentReady.value || selected.value?.id !== row.id || row.status !== 'pending') return
  void action.run(async () => {
    if (
      !(await confirm({
        title: '批准工作流',
        content: `批准工作流“${row.workflow_name}”版本 ${row.version}？系统还会检查当前所见修订并执行安全扫描，通过后才完成批准。`,
        confirmText: '批准工作流',
        cancelText: '取消',
        danger: true
      }))
    )
      return
    await checked('foundation.marketplace.publish', () =>
      api.marketplace.approve(row.id, row.updated_at)
    )
    closeReview()
    message.success('工作流已批准')
    reload()
  })
}
function reject() {
  if (!allowed('foundation.marketplace.update')) return
  if (!contentReady.value || selected.value?.status !== 'pending') return
  void action.run(async () => {
    if (!reason.value.trim()) throw new Error('请输入拒绝理由')
    await checked('foundation.marketplace.update', () =>
      api.marketplace.reject(selected.value!.id, selected.value!.updated_at, reason.value.trim())
    )
    closeReview()
    message.success('工作流本次审核已拒绝，拒绝理由已保存')
    reload()
  })
}
function submitFilters() {
  page.value = 1
  applied.value = { search: search.value, status: status.value }
  load.data.value = undefined
  reload()
}
function resetFilters() {
  search.value = ''
  status.value = 'pending'
  page.value = 1
  submitFilters()
}
onMounted(reload)

const { requestClose: openClose } = useDrawerClose({
  open: () => open.value,
  busy: () => action.busy.value,
  dirty: () => !!reason.value,
  close: () => {
    closeReview()
  }
})
async function refreshReviewList() {
  await openClose()
  if (!open.value) reload()
}
</script>

<template>
  <AdminPage title="市场审核" description="优先处理待审核工作流，批准与拒绝均针对列表中的指定版本">
    <ElForm
      @keydown.capture="guardFormEnter"
      class="admin-filters filters"
      @submit.prevent="submitFilters"
    >
      <ElInput v-model="search" aria-label="工作流名称" placeholder="工作流名称" clearable />
      <ElSelect v-model="status" aria-label="审核状态">
        <ElOption
          v-for="s in ['pending', 'approved', 'rejected']"
          :key="s"
          :value="s"
          :label="statusLabel(s)"
        />
      </ElSelect>
      <ElButton native-type="submit" :loading="load.loading.value">查询</ElButton
      ><ElButton @click="resetFilters">重置</ElButton>
    </ElForm>
    <p class="admin-muted" role="status">
      {{ statusLabel(applied.status) }} · 共 {{ total }} 条记录
    </p>
    <FeedbackNotice
      v-if="load.error.value || action.error.value"
      :title="load.error.value || action.error.value"
      :closable="false"
      type="error"
    />
    <ElTable
      table-layout="auto"
      :empty-text="
        load.loading.value
          ? '正在读取审核列表…'
          : load.error.value
            ? '审核列表尚未读取，请查看上方错误'
            : applied.status === 'pending'
              ? '当前查询范围没有待审核工作流'
              : '当前查询范围没有匹配的审核记录'
      "
      :data="rows"
      v-loading="load.loading.value"
      style="width: 100%"
    >
      <ElTableColumn prop="workflow_name" label="工作流" min-width="240" show-overflow-tooltip>
        <template #default="{ row }"
          ><ElButton
            link
            type="primary"
            :disabled="action.busy.value"
            @click="openReview(row as MarketplaceReview)"
            >{{ row.workflow_name }}</ElButton
          ></template
        >
      </ElTableColumn>
      <ElTableColumn prop="author" label="作者" min-width="160" show-overflow-tooltip>
      </ElTableColumn>
      <ElTableColumn prop="version" label="版本" width="110"> </ElTableColumn>
      <ElTableColumn prop="status" label="状态" width="110"
        ><template #default="{ row }">{{ statusLabel(row.status) }}</template></ElTableColumn
      >
      <ElTableColumn prop="reason" label="审核说明" min-width="280" show-overflow-tooltip>
      </ElTableColumn>
      <ElTableColumn label="操作" width="150" class-name="admin-table-actions">
        <template #default="{ row }"
          ><ElButton
            link
            type="primary"
            :disabled="action.busy.value"
            @click="openReview(row as MarketplaceReview)"
            >{{ row.status === 'pending' ? '查看并审核' : '查看记录' }}</ElButton
          ></template
        >
      </ElTableColumn>
    </ElTable>
    <ElPagination
      class="admin-actions"
      layout="total, prev, pager, next"
      :current-page="load.data.value?.page ?? page"
      :page-size="20"
      :total="total"
      @current-change="
        ($event: number) => {
          page = $event
          reload()
        }
      "
    />
    <ElDrawer
      class="admin-drawer"
      direction="rtl"
      append-to-body
      v-model="open"
      :title="'审核：' + (selected?.workflow_name ?? '')"
      :before-close="openClose"
      size="960px"
      :close-on-click-modal="false"
    >
      <ElDescriptions :column="2" border>
        <ElDescriptionsItem label="工作流">{{ selected?.workflow_name }}</ElDescriptionsItem>
        <ElDescriptionsItem label="审核版本">{{ selected?.version }}</ElDescriptionsItem>
        <ElDescriptionsItem label="作者">{{ selected?.author }}</ElDescriptionsItem>
        <ElDescriptionsItem label="状态">{{
          statusLabel(selected?.status ?? '')
        }}</ElDescriptionsItem>
        <ElDescriptionsItem label="所见修订">{{
          formatDateTime(selected?.updated_at)
        }}</ElDescriptionsItem>
        <ElDescriptionsItem v-if="selected?.reviewed_at" label="审核时间">{{
          formatDateTime(selected.reviewed_at)
        }}</ElDescriptionsItem>
      </ElDescriptions>
      <FeedbackNotice
        v-if="detail.error.value || action.error.value"
        :title="detail.error.value || action.error.value"
        type="error"
        :closable="false"
      />
      <div v-if="detail.error.value" class="admin-actions">
        <ElButton :loading="detail.loading.value" @click="detail.reload">重试读取本修订</ElButton>
        <ElButton @click="refreshReviewList">刷新列表并重新选择</ElButton>
      </div>
      <ElSkeleton v-if="detail.loading.value" :rows="8" />
      <template v-else-if="detail.data.value">
        <p v-if="detail.data.value.description" class="admin-muted">
          {{ detail.data.value.description }}
        </p>
        <h3>待审核包资源</h3>
        <p class="admin-field-help">
          以下内容来自所见模板修订的实际包资源，只读展示。批准时仍会执行安全扫描；当前没有已保存的扫描结论或历史内容差异可供展示。
        </p>
        <div class="admin-toolbar">
          <ElSelect v-model="selectedFile" aria-label="查看包内文件" filterable>
            <ElOption
              v-for="entry in detail.data.value.files"
              :key="entry.name"
              :value="entry.name"
              :label="entry.name + ' · ' + entry.size_bytes + ' 字节'"
            />
          </ElSelect>
          <span class="admin-muted" role="status">{{ detail.data.value.files.length }} 个资源</span>
        </div>
        <CodeEditor
          v-if="file?.content != null"
          :key="file.name"
          :model-value="file.content"
          :label="'只读包文件 ' + file.name"
          :language="
            /\.ya?ml$/.test(file.name) ? 'yaml' : /\.json$/.test(file.name) ? 'json' : 'plaintext'
          "
          readonly
          height="420px"
        />
        <ElEmpty
          v-else
          :description="
            file ? '此资源为非文本文件，仅展示名称与字节数' : '此修订缺少 workflow.yaml 内容'
          "
        />
        <ElCollapse>
          <ElCollapseItem title="安装配置与权限声明" name="declarations">
            <h4>安装配置</h4>
            <pre class="readable">{{
              JSON.stringify(detail.data.value.config_schema ?? [], null, 2)
            }}</pre>
            <h4>所需权限</h4>
            <p>{{ detail.data.value.required_permissions?.join('、') || '未声明' }}</p>
            <h4>工具声明</h4>
            <pre class="readable">{{ JSON.stringify(detail.data.value.tools ?? [], null, 2) }}</pre>
            <h4>连接器要求</h4>
            <pre class="readable">{{
              JSON.stringify(detail.data.value.connector_requirements ?? [], null, 2)
            }}</pre>
          </ElCollapseItem>
        </ElCollapse>
      </template>
      <ElAlert
        v-if="detail.data.value && !contentReady"
        title="缺少可读取的 workflow.yaml，无法确认审核内容。请联系提交者补齐后重新提交。"
        type="warning"
        :closable="false"
      />
      <ElForm
        v-if="selected?.status === 'pending'"
        @keydown.capture="guardFormEnter"
        label-position="top"
        :disabled="!allowed('foundation.marketplace.update') || action.busy.value || !contentReady"
        @submit.prevent="reject"
      >
        <ElFormItem label="拒绝理由（拒绝时必填）"
          ><ElInput
            v-model="reason"
            type="textarea"
            :rows="3"
            maxlength="1000"
            show-word-limit
            aria-label="拒绝理由"
        /></ElFormItem>
      </ElForm>
      <p v-else-if="selected?.reason" class="admin-muted">审核说明：{{ selected.reason }}</p>
      <template #footer>
        <ElButton :disabled="action.busy.value" @click="openClose()">返回列表</ElButton>
        <template v-if="selected?.status === 'pending'">
          <ElButton
            v-permission="['foundation.marketplace.update']"
            type="danger"
            :disabled="!contentReady || action.busy.value"
            :loading="action.busy.value"
            @click="reject"
            >拒绝工作流</ElButton
          >
          <ElButton
            v-permission="['foundation.marketplace.publish']"
            type="primary"
            :disabled="!contentReady || action.busy.value"
            :loading="action.busy.value"
            @click="approve(selected!)"
            >批准工作流</ElButton
          >
        </template>
      </template>
    </ElDrawer>
  </AdminPage>
</template>
