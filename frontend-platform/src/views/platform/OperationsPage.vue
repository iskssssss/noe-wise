<script setup lang="ts">
import FeedbackNotice from '@skillforge/shared/feedback/FeedbackNotice.vue'
import { useActionPermissions } from '../../composables/actionPermissions'
import { vDescribedBy } from '@skillforge/shared/directives/describedBy'

import FieldTip from '@skillforge/shared/components/FieldTip.vue'

import { guardFormEnter } from '@skillforge/shared/admin'
import { confirm, message } from '@skillforge/shared/feedback'
import { computed, onMounted, reactive, ref, useId } from 'vue'

import { AdminPage, EntitySelect, useTask, useResource } from '@skillforge/shared/admin'
import { formatDateTime, toBeijingDateTime } from '@skillforge/shared/utils/datetime'
import { loadTenantOptions, resolveTenantOption } from '../../api/entityOptions'
import { api, type AuditLogItem, type BillingStateProcessResult } from '../../api'
const { allowed, checked } = useActionPermissions()
const action = useTask(),
  page = ref(1),
  selected = ref<AuditLogItem>(),
  open = ref(false),
  range = ref<[string, string]>(),
  receipt = ref<BillingStateProcessResult>(),
  operationId = ref('')
// 日期控件只处理北京墙上时间，业务筛选仍保留带偏移的绝对时刻。
const dateRange = computed<[string, string] | undefined>({
  get: (): [string, string] | undefined =>
    range.value && [formatDateTime(range.value[0]), formatDateTime(range.value[1])],
  set: (value: [string, string] | null | undefined) => {
    const from = toBeijingDateTime(value?.[0]),
      to = toBeijingDateTime(value?.[1])
    range.value = from && to ? [from, to] : undefined
  }
})
const filter = reactive({ action: '', tenant_id: '', actor_id: '', resource_type: '' })
const manualTenant = ref(!allowed('foundation.tenants.read'))
// 输入中的筛选与当前结果的查询快照分开；翻页只使用已提交条件。
const applied = ref({
  ...filter,
  from: undefined as string | undefined,
  to: undefined as string | undefined
})
const filtersChanged = computed(
  () =>
    Object.keys(filter).some(
      (key) => filter[key as keyof typeof filter] !== applied.value[key as keyof typeof filter]
    ) ||
    range.value?.[0] !== applied.value.from ||
    range.value?.[1] !== applied.value.to
)
const appliedSummary = computed(
  () =>
    [
      applied.value.action && `操作 ${applied.value.action}`,
      applied.value.tenant_id && `机构 ${applied.value.tenant_id}`,
      applied.value.actor_id && `操作者 ${applied.value.actor_id}`,
      applied.value.resource_type && `资源 ${applied.value.resource_type}`,
      applied.value.from &&
        `${formatDateTime(applied.value.from)} 至 ${formatDateTime(applied.value.to)}`
    ]
      .filter(Boolean)
      .join(' · ') || '全部审计记录'
)
const load = useResource(async (signal) => {
  const requestedPage = page.value
  const result = await api.operations.auditLogs(
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
function process() {
  if (!allowed('foundation.billing.run')) return
  void action.run(async () => {
    if (
      !(await confirm({
        title: '账单状态扫描',
        content: '扫描全平台机构账单并更新欠费状态。确认执行？',
        confirmText: '执行扫描',
        cancelText: '取消',
        danger: true
      }))
    )
      return
    operationId.value ||= crypto.randomUUID()
    receipt.value = await checked('foundation.billing.run', () =>
      api.operations.processBillingStates(operationId.value)
    )
    message.success('账单状态扫描已完成')
  })
}
function recover() {
  void action.run(async () => {
    const r = await api.operations.billingStateResult(operationId.value)
    if (!r)
      throw new Error(
        '暂未查到该操作的回执，执行结果仍未确认。可使用同一操作键重试，或稍后查询回执'
      )
    receipt.value = r
    message.success('已查到操作回执')
  })
}
function submitFilters() {
  page.value = 1
  applied.value = { ...filter, from: range.value?.[0], to: range.value?.[1] }
  load.data.value = undefined
  reload()
}
function resetFilters() {
  Object.assign(filter, { action: '', tenant_id: '', actor_id: '', resource_type: '' })
  range.value = undefined
  page.value = 1
  submitFilters()
}
onMounted(reload)

const formTipId = useId()
</script>

<template>
  <AdminPage title="运维审计" description="查看操作审计与执行平台账单状态扫描">
    <FeedbackNotice
      v-if="action.error.value"
      :title="action.error.value"
      :closable="false"
      type="error"
    />
    <ElCollapse v-if="allowed('foundation.billing.run')" class="admin-card">
      <ElCollapseItem title="账单状态扫描 · 平台运维操作" name="billing-scan">
        <h2>账单状态扫描</h2>
        <p>扫描全部机构的账单状态。请求结果不明确时，用同一操作键查询或重试。</p>
        <div class="admin-actions">
          <ElButton
            v-permission="['foundation.billing.run']"
            :loading="action.busy.value"
            :disabled="!!receipt"
            @click="process"
            >{{ operationId ? '使用同一操作键重试' : '执行扫描' }}</ElButton
          >
          <ElButton v-if="operationId" :disabled="action.busy.value" @click="recover"
            >查询回执</ElButton
          >
          <ElButton
            v-if="receipt"
            :disabled="action.busy.value"
            @click="
              () => {
                operationId = ''
                receipt = undefined
              }
            "
            >新建扫描</ElButton
          >
          <code>{{ operationId }}</code>
          <p v-if="receipt" class="admin-field-help" role="status">
            本次扫描已完成，开始下一次扫描请点击“新建扫描”。
          </p>
        </div>
        <ElDescriptions v-if="receipt" :column="4" border>
          <ElDescriptionsItem label="扫描机构">{{ receipt.scanned }}</ElDescriptionsItem>
          <ElDescriptionsItem label="预警">{{ receipt.warning }}</ElDescriptionsItem>
          <ElDescriptionsItem label="正常">{{ receipt.active }}</ElDescriptionsItem>
          <ElDescriptionsItem label="完成时间">{{
            formatDateTime(receipt.completed_at)
          }}</ElDescriptionsItem>
        </ElDescriptions>
      </ElCollapseItem>
    </ElCollapse>
    <ElCard class="admin-card">
      <h2>审计日志</h2>
      <ElForm
        @keydown.capture="guardFormEnter"
        class="admin-filters"
        @submit.prevent="submitFilters"
      >
        <ElInput
          v-described-by="formTipId + '-group-0'"
          :aria-describedby="formTipId + '-group-0'"
          v-model="filter.action"
          placeholder="操作类型"
        />
        <EntitySelect
          v-if="!manualTenant"
          v-described-by="formTipId + '-group-0'"
          :aria-describedby="formTipId + '-group-0'"
          v-model="filter.tenant_id"
          :load="loadTenantOptions"
          :resolve="resolveTenantOption"
          placeholder="选择机构"
        />
        <ElInput
          v-else
          v-model="filter.tenant_id"
          clearable
          placeholder="历史机构标识"
          aria-label="历史机构标识"
        />
        <ElButton link @click="manualTenant = !manualTenant">{{
          manualTenant ? '按机构名称选择' : '输入历史机构标识'
        }}</ElButton>
        <ElInput
          v-described-by="formTipId + '-group-0'"
          :aria-describedby="formTipId + '-group-0'"
          v-model="filter.actor_id"
          placeholder="操作者 ID"
        />
        <ElInput
          v-described-by="formTipId + '-group-0'"
          :aria-describedby="formTipId + '-group-0'"
          v-model="filter.resource_type"
          placeholder="资源类型"
        />
        <div v-described-by="formTipId + '-time-range'" class="time-range-filter">
          <ElDatePicker
            :aria-describedby="formTipId + '-time-range'"
            v-model="dateRange"
            type="datetimerange"
            format="YYYY-MM-DD HH:mm:ss"
            date-format="YYYY-MM-DD"
            time-format="HH:mm:ss"
            value-format="YYYY-MM-DD HH:mm:ss"
            start-placeholder="开始时间"
            end-placeholder="结束时间"
          />
          <FieldTip :id="formTipId + '-time-range'">
            按北京时间选择审计发生时间范围；清空后不限制时间，点击查询后生效。
          </FieldTip>
        </div>
        <ElButton native-type="submit" :loading="load.loading.value">查询</ElButton
        ><ElButton @click="resetFilters">重置</ElButton>

        <FieldTip :id="formTipId + '-group-0'"
          >按操作类型、机构、操作者
          ID、资源类型和发生时间组合查询审计记录；历史机构可输入原标识。时间范围按北京时间选择，留空的条件不限制结果。</FieldTip
        >
      </ElForm>
      <p class="admin-field-help" role="status">
        当前范围：{{ appliedSummary }}{{ filtersChanged ? '；筛选已修改，查询后生效。' : '' }}
      </p>
      <FeedbackNotice
        v-if="load.error.value"
        :title="load.error.value"
        :closable="false"
        type="error"
      />
      <ElButton v-if="load.error.value" :loading="load.loading.value" @click="reload"
        >重试当前审计查询</ElButton
      >
      <ElTable
        table-layout="auto"
        :empty-text="
          load.loading.value
            ? '正在读取审计记录…'
            : load.error.value
              ? '审计记录尚未读取，请查看上方错误'
              : '当前已查询范围没有审计记录，可调整条件后查询'
        "
        v-loading="load.loading.value"
        :data="rows"
        style="width: 100%"
      >
        <ElTableColumn prop="created_at" label="时间" min-width="190">
          <template #default="{ row }">{{ formatDateTime(row.created_at) }}</template>
        </ElTableColumn>
        <ElTableColumn prop="action" label="操作" min-width="180" show-overflow-tooltip>
        </ElTableColumn>
        <ElTableColumn prop="actor_id" label="操作者" min-width="180" show-overflow-tooltip>
        </ElTableColumn>
        <ElTableColumn prop="resource_type" label="资源类型" min-width="160" show-overflow-tooltip>
        </ElTableColumn>
        <ElTableColumn prop="resource_id" label="资源 ID" min-width="240" show-overflow-tooltip>
        </ElTableColumn>
        <ElTableColumn prop="message" label="说明" min-width="280" show-overflow-tooltip>
        </ElTableColumn>
        <ElTableColumn prop="actions" label="详情" width="100" class-name="admin-table-actions">
          <template #default="{ row }">
            <ElButton
              link
              @click="
                () => {
                  selected = row as AuditLogItem
                  open = true
                }
              "
              >查看</ElButton
            >
          </template>
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
    </ElCard>
    <ElDrawer
      class="admin-drawer"
      direction="rtl"
      append-to-body
      v-model="open"
      title="审计详情"
      size="680px"
    >
      <ElDescriptions v-if="selected" :column="1" border>
        <ElDescriptionsItem label="操作">{{ selected.action }}</ElDescriptionsItem>
        <ElDescriptionsItem label="Trace ID">{{ selected.trace_id }}</ElDescriptionsItem>
        <ElDescriptionsItem label="说明">{{ selected.message }}</ElDescriptionsItem>
      </ElDescriptions>
      <pre class="prewrap">{{ JSON.stringify(selected?.payload, null, 2) }}</pre>
    </ElDrawer>
  </AdminPage>
</template>
