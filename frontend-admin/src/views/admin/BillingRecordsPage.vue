<template>
  <AdminPage title="计费记录" description="查看月度模型费用，查询计费事件与请求用量。"
    ><FeedbackNotice
      v-if="error || loadError || filterError"
      :title="error || loadError || filterError"
      :closable="false"
      type="error"
    /><ElTabs v-model="tab" :before-leave="beforeTabChange" @tab-change="queryRecords"
      ><ElTabPane label="计费事件" name="records" /><ElTabPane
        label="请求汇总"
        name="requests" /><ElTabPane label="模型月账单" name="monthly" /><ElTabPane
        label="对账配置"
        name="reconciliation" /></ElTabs
    ><template v-if="tab === 'records' || tab === 'requests'"
      ><div class="admin-toolbar admin-card">
        <div
          v-described-by="formTipId + '-group-0'"
          :aria-describedby="formTipId + '-group-0'"
          style="display: contents"
        >
          <ElDatePicker
            v-model="range"
            type="daterange"
            format="YYYY-MM-DD"
            value-format="YYYY-MM-DD"
            aria-label="计费日期范围（北京时间）"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
          />
        </div>
        <ElSelect
          v-described-by="formTipId + '-group-0'"
          :aria-describedby="formTipId + '-group-0'"
          v-if="tab === 'records'"
          v-model="model"
          clearable
          filterable
          placeholder="全部模型"
          aria-label="模型筛选"
          no-data-text="暂无可筛选的模型"
        >
          <ElOption
            v-for="item in filterOptions?.models ?? []"
            :key="item"
            :label="item"
            :value="item"
          />
        </ElSelect>
        <ElSelect
          v-described-by="formTipId + '-group-0'"
          :aria-describedby="formTipId + '-group-0'"
          v-if="tab === 'records'"
          v-model="eventType"
          clearable
          placeholder="全部事件类型"
          aria-label="事件类型筛选"
        >
          <ElOption
            v-for="item in filterOptions?.eventTypes ?? []"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </ElSelect>
        <ElInput
          v-described-by="formTipId + '-group-0'"
          :aria-describedby="formTipId + '-group-0'"
          v-else
          v-model="runId"
          placeholder="运行 ID"
          aria-label="运行标识筛选"
        /><ElButton @click="queryRecords" :disabled="busy">查询</ElButton
        ><ElButton :disabled="busy || loading" @click="resetFilters">重置</ElButton>

        <FieldTip :id="formTipId + '-group-0'"
          >按北京时间自然日期筛选，包含起止日期的整日；模型和事件类型从当前可用选项中选择。留空的条件不参与筛选，点击查询后更新结果。</FieldTip
        >
      </div>
      <p v-if="filtersChanged" class="admin-muted" role="status">
        筛选条件已修改，点击查询后应用；翻页仍使用上次查询条件。
      </p>
      <p v-if="tab === 'records'" class="admin-field-help">
        用量报告记录调用过程，不代表新增调用；调用次数和费用请查看请求汇总。
      </p>
      <ElTable
        table-layout="auto"
        v-if="tab === 'records'"
        :data="records"
        v-loading="loading"
        empty-text="当前查询没有计费事件，可调整日期、模型或事件类型后查询"
        ><ElTableColumn min-width="140" label="类型"
          ><template #default="{ row }">{{
            eventTypeLabel(row.event_type)
          }}</template></ElTableColumn
        ><ElTableColumn width="120" label="事实类型"
          ><template #default="{ row }">{{
            usageKindLabel(row.usage_event_kind)
          }}</template></ElTableColumn
        ><ElTableColumn
          min-width="200"
          show-overflow-tooltip
          prop="model"
          label="模型"
        /><ElTableColumn width="96" prop="quantity" label="数量" /><ElTableColumn
          width="96"
          prop="tokens"
          label="Token 数"
        /><ElTableColumn width="120" prop="total_amount" label="金额" /><ElTableColumn
          width="80"
          prop="currency"
          label="币种"
        /><ElTableColumn width="128" label="成本状态"
          ><template #default="{ row }">{{
            costStatusLabel(row.amount_status)
          }}</template></ElTableColumn
        ><ElTableColumn width="190" show-overflow-tooltip prop="created_at" label="时间"
          ><template #default="{ row }">{{
            formatDateTime(row.created_at)
          }}</template></ElTableColumn
        ><ElTableColumn width="100" class-name="admin-table-actions" label="追踪"
          ><template #default="{ row }"
            ><ElButton
              v-if="row.root_input_id"
              link
              @click="viewTrace(row.root_input_id)"
              :disabled="busy"
              >查看来源</ElButton
            ></template
          ></ElTableColumn
        ></ElTable
      ><ElTable
        table-layout="auto"
        v-else
        :data="requests"
        v-loading="loading"
        empty-text="当前查询没有请求汇总，可调整日期或运行 ID 后查询"
        ><ElTableColumn
          min-width="200"
          show-overflow-tooltip
          prop="request_id"
          label="请求"
        /><ElTableColumn width="120" prop="llm_calls" label="模型调用" /><ElTableColumn
          width="96"
          prop="tokens"
          label="Token 数"
        /><ElTableColumn width="120" prop="confirmed_cost" label="已确认成本" /><ElTableColumn
          width="120"
          prop="missing_cost_count"
          label="成本缺失数"
        /><ElTableColumn width="128" label="状态"
          ><template #default="{ row }">{{
            requestStatusLabel(row.usage_status)
          }}</template></ElTableColumn
        ><ElTableColumn width="80" class-name="admin-table-actions" label="操作"
          ><template #default="{ row }"
            ><ElButton link @click="viewRequest(row.request_id)" :disabled="busy"
              >详情</ElButton
            ></template
          ></ElTableColumn
        ></ElTable
      ><ElPagination
        v-described-by="formTipId + '-records-page'"
        :aria-describedby="formTipId + '-records-page'"
        :disabled="loading"
        :current-page="page"
        :page-size="pageSize"
        :total="total"
        :page-sizes="[20, 50, 100]"
        layout="total,sizes,prev,pager,next"
        @current-change="
          ($event) => {
            page = $event
            reload()
          }
        "
        @size-change="
          ($event) => {
            pageSize = $event
            page = 1
            reload()
          }
        "
      /><FieldTip :id="formTipId + '-records-page'"
        >每页可显示 20、50 或 100 条；更改条数会返回第一页并重新查询，页码按钮读取对应页。</FieldTip
      ></template
    >
    <template v-else-if="tab === 'monthly'">
      <ElForm
        class="admin-filters"
        @keydown.capture="guardFormEnter"
        @submit.prevent="queryMonthly"
      >
        <ElDatePicker
          v-described-by="formTipId + '-monthly'"
          :aria-describedby="formTipId + '-monthly'"
          v-model="month"
          type="month"
          format="YYYY-MM"
          value-format="YYYY-MM"
          aria-label="模型费用月份（北京时间）"
          placeholder="选择月份"
        />
        <ElButton native-type="submit" :loading="monthly.loading.value">查询模型费用</ElButton>
        <FieldTip :id="formTipId + '-monthly'"
          >首次进入自动查询当前北京时间月份；修改月份后点击查询。费用来自调用时保存的模型价格。</FieldTip
        >
      </ElForm>
      <p v-if="appliedMonth" class="admin-field-help" role="status">
        当前月份：{{ appliedMonth }}{{ month !== appliedMonth ? '；月份已修改，查询后生效。' : '' }}
      </p>
      <FeedbackNotice
        v-if="monthly.error.value"
        :title="monthly.error.value"
        type="error"
        :closable="false"
      />
      <ElButton
        v-if="monthly.error.value && appliedMonth"
        :disabled="monthly.loading.value"
        @click="monthly.reload"
        >重试当前查询</ElButton
      >
      <ElSkeleton v-if="monthly.loading.value && !monthly.data.value" :rows="6" animated />
      <template v-else-if="monthly.data.value">
        <ModelBillingDetails :monthly="monthly.data.value" />
        <ElPagination
          :disabled="monthly.loading.value"
          :current-page="monthly.data.value.page"
          :page-size="monthly.data.value.page_size"
          :total="monthly.data.value.total"
          :page-sizes="[20, 50, 100]"
          layout="total,sizes,prev,pager,next"
          @current-change="changeMonthlyPage"
          @size-change="changeMonthlySize"
        />
      </template>
      <ElEmpty v-else-if="!monthly.error.value" description="选择月份后查看模型费用" />
    </template>
    <ElForm
      class="admin-card billing-settings"
      v-else
      label-position="top"
      @submit.prevent="saveProvider"
      :disabled="busy || loading"
      scroll-to-error
      @keydown.capture="guardFormEnter"
      ><ElFormItem label="对账提供方"
        ><ElSelect
          v-described-by="formTipId + '-field-0'"
          :aria-describedby="formTipId + '-field-0'"
          v-model="provider.provider"
          aria-label="对账提供方"
          placeholder="选择对账提供方"
        >
          <ElOption label="使用默认配置" value="" />
          <ElOption label="通用 HTTP（http）" value="http" />
          <ElOption label="阿里云百炼（dashscope）" value="dashscope" />
        </ElSelect>
        <FieldTip :id="formTipId + '-field-0'"
          >选择受支持的对账提供方；使用默认配置时，需清空接口地址及其他覆盖配置。</FieldTip
        >
        <p v-if="!provider.provider" class="admin-field-help">
          保存默认配置会清除机构对账覆盖配置及其已保存凭据。
        </p> </ElFormItem
      ><ElFormItem label="对账接口 URL"
        ><ElInput
          v-described-by="formTipId + '-field-1'"
          :aria-describedby="formTipId + '-field-1'"
          v-model="provider.endpoint"
        />
        <FieldTip :id="formTipId + '-field-1'"
          >填写返回用量数据的对账 API 地址。</FieldTip
        > </ElFormItem
      ><ElFormItem label="API Key"
        ><ElInput
          v-described-by="formTipId + '-field-2'"
          :aria-describedby="formTipId + '-field-2'"
          v-model="provider.api_key"
          type="password"
          show-password
          placeholder="留空保持当前凭据"
        />
        <FieldTip :id="formTipId + '-field-2'"
          >填写对账访问凭据；留空保持已保存凭据，勾选清除则删除凭据。</FieldTip
        > </ElFormItem
      ><ElCheckbox
        v-described-by="formTipId + '-group-2'"
        :aria-describedby="formTipId + '-group-2'"
        v-model="provider.clear_api_key"
        >清除已有凭据</ElCheckbox
      >
      <FieldTip :id="formTipId + '-group-2'"
        >勾选并保存后清除已配置的对账凭据；未勾选时遵循上方 API Key 的编辑规则。</FieldTip
      >
      <ElCollapse
        ><ElCollapseItem title="高级配置（请求头与响应字段映射）" name="mapping"
          ><ElFormItem label="凭据请求头"
            ><ElInput
              v-described-by="formTipId + '-field-3'"
              :aria-describedby="formTipId + '-field-3'"
              v-model="provider.api_key_header"
            />
            <FieldTip :id="formTipId + '-field-3'"
              >填写发送访问凭据时使用的 HTTP 请求头名称。</FieldTip
            > </ElFormItem
          ><ElFormItem label="响应映射 Schema"
            ><CodeEditor
              :described-by="formTipId + '-field-4'"
              :readonly="busy"
              v-model="schemaText"
              language="json"
              height="240px"
            />
            <FieldTip :id="formTipId + '-field-4'"
              >填写对账响应字段映射 JSON，按接口返回结构配置。</FieldTip
            >
          </ElFormItem></ElCollapseItem
        ></ElCollapse
      ><ElButton type="primary" native-type="submit" :loading="busy">保存配置</ElButton></ElForm
    >
    <ElDrawer
      v-model="open"
      title="计费详情"
      size="850px"
      append-to-body
      direction="rtl"
      class="admin-drawer"
      ><FeedbackNotice
        v-if="error"
        :title="error"
        type="error"
        :closable="false"
        role="alert"
      /><template v-if="request"
        ><ElDescriptions :column="1" border
          ><ElDescriptionsItem label="请求">{{ request.request_id }}</ElDescriptionsItem
          ><ElDescriptionsItem label="已确认成本">{{
            request.summary.confirmed_cost
          }}</ElDescriptionsItem
          ><ElDescriptionsItem label="缺失成本">{{
            request.summary.missing_cost_count
          }}</ElDescriptionsItem></ElDescriptions
        ><ElTable table-layout="auto" :data="request.items" empty-text="此请求暂无用量明细"
          ><ElTableColumn
            min-width="140"
            show-overflow-tooltip
            prop="event_type"
            label="事件" /><ElTableColumn width="120" label="事实类型"
            ><template #default="{ row }">{{
              usageKindLabel(row.usage_event_kind)
            }}</template></ElTableColumn
          ><ElTableColumn
            min-width="200"
            show-overflow-tooltip
            prop="model"
            label="模型" /><ElTableColumn width="96" prop="tokens" label="Token" /><ElTableColumn
            width="120"
            prop="total_amount"
            label="金额" /><ElTableColumn
            width="128"
            show-overflow-tooltip
            prop="amount_status"
            label="金额状态" /></ElTable></template
      ><template v-if="trace"
        ><ElAlert
          v-if="trace.unresolved_items.length"
          :title="trace.unresolved_items.join('；')"
          type="warning"
        /><ElTimeline
          ><ElTimelineItem
            v-for="entry in trace.timeline"
            :key="entry.source_event_id"
            :timestamp="formatDateTime(entry.started_at)"
            ><h4>{{ entry.event_type }} · {{ statusLabel(entry.status) }}</h4>
            <ElDescriptions :column="1" border
              ><template v-for="(value, key) in entry.sanitized_detail" :key="key"
                ><ElDescriptionsItem :label="String(key)">{{ value }}</ElDescriptionsItem></template
              ></ElDescriptions
            ></ElTimelineItem
          ></ElTimeline
        ></template
      ></ElDrawer
    ></AdminPage
  >
</template>
<script setup lang="ts">
import FeedbackNotice from '@skillforge/shared/feedback/FeedbackNotice.vue'
import { formatDateTime, formatMonth } from '@skillforge/shared/utils/datetime'
import { vDescribedBy } from '@skillforge/shared/directives/describedBy'

import FieldTip from '@skillforge/shared/components/FieldTip.vue'

import { confirm, message } from '@skillforge/shared/feedback'
import { guardFormEnter } from '@skillforge/shared/admin'
import { statusLabel } from '@skillforge/shared/admin'
import { computed, onMounted, reactive, ref, watch, useId } from 'vue'
import { AdminPage, useResource, useTask, useUnsavedChanges } from '@skillforge/shared/admin'
import CodeEditor from '@skillforge/shared/components/CodeEditor.vue'
import ModelBillingDetails from '@skillforge/shared/components/ModelBillingDetails.vue'
import {
  api,
  type BillingRecordItem,
  type BillingRequestItem,
  type BillingRequestDetail,
  type TraceView
} from '../../api'
const usageKinds: Record<string, string> = {
  measured: '计量结果',
  usage_report: '用量报告',
  reconciliation: '对账',
  adjustment: '调整'
}
function usageKindLabel(kind?: string | null) {
  return kind ? (usageKinds[kind] ?? kind) : '未记录'
}
const eventTypeLabels: Record<string, string> = {
  request: '请求',
  'llm.chat': '对话',
  'llm.embedding': '向量化',
  'llm.rerank': '重排',
  'knowledge.source': '知识来源',
  'workflow.external': '外部工作流'
}
const costStatusLabels: Record<string, string> = {
  unpriced: '未定价',
  unresolved: '待确认',
  not_applicable: '不适用',
  priced: '已定价',
  confirmed: '已确认'
}
const requestStatusLabels: Record<string, string> = {
  complete: '完整',
  partial: '部分缺失',
  unresolved: '待确认',
  missing: '缺失'
}
const eventTypeLabel = (value?: string | null) =>
  value ? (eventTypeLabels[value] ?? value) : '未记录'
const costStatusLabel = (value?: string | null) =>
  value ? (costStatusLabels[value] ?? value) : '未记录'
const requestStatusLabel = (value?: string | null) =>
  value ? (requestStatusLabels[value] ?? value) : '未记录'
const tab = ref('records'),
  range = ref<[string, string]>(),
  model = ref(''),
  eventType = ref(''),
  runId = ref(''),
  page = ref(1),
  pageSize = ref(20),
  records = ref<BillingRecordItem[]>([]),
  requests = ref<BillingRequestItem[]>([]),
  total = ref(0)
const filterSnapshot = () => ({
  range: range.value ? ([...range.value] as [string, string]) : undefined,
  model: model.value,
  eventType: eventType.value,
  runId: runId.value
})
const appliedFilters = ref(filterSnapshot())
const filtersChanged = computed(
  () => JSON.stringify(filterSnapshot()) !== JSON.stringify(appliedFilters.value)
)
function queryRecords() {
  appliedFilters.value = filterSnapshot()
  page.value = 1
  monthly.invalidate()
  if (tab.value === 'monthly') {
    invalidateRecords()
    loadError.value = ''
    if (!appliedMonth.value) queryMonthly()
    else if (!monthly.data.value && !monthly.error.value) void monthly.reload()
  } else void reload()
}
const month = ref(formatMonth(new Date()))
const appliedMonth = ref('')
const monthlyPage = ref(1)
const monthlyPageSize = ref(20)
const monthly = useResource((signal) =>
  api.billing.monthly(
    {
      month: appliedMonth.value,
      page: monthlyPage.value,
      page_size: monthlyPageSize.value
    },
    { signal }
  )
)
function queryMonthly() {
  if (!/^\d{4}-(0[1-9]|1[0-2])$/.test(month.value)) {
    monthly.error.value = '请选择有效月份后查询'
    return
  }
  if (appliedMonth.value !== month.value) monthly.data.value = undefined
  appliedMonth.value = month.value
  monthlyPage.value = 1
  void monthly.reload()
}
function changeMonthlyPage(value: number) {
  if (!appliedMonth.value) return
  monthlyPage.value = value
  void monthly.reload()
}
function changeMonthlySize(value: number) {
  monthlyPageSize.value = value
  changeMonthlyPage(1)
}
const provider = reactive({
    provider: '',
    endpoint: '',
    api_key: '',
    api_key_header: '',
    clear_api_key: false
  }),
  schemaText = ref('{}'),
  saved = ref('')
const snapshot = () => JSON.stringify([provider, schemaText.value])
async function beforeTabChange() {
  if (busy.value) return false
  return (
    tab.value !== 'reconciliation' ||
    !saved.value ||
    snapshot() === saved.value ||
    (await confirm({
      title: '离开对账配置',
      content: '对账配置有未保存修改，切换页面会丢弃这些修改。',
      confirmText: '丢弃并切换'
    }))
  )
}
const {
  data: loaded,
  loading,
  error: loadError,
  reload,
  invalidate: invalidateRecords
} = useResource(async () => {
  // 账单接口按北京自然日查询；日历值原样提交，不转成浏览器时区或绝对时刻。
  const [from, to] = appliedFilters.value.range ?? []
  if (tab.value === 'records') {
    const value = await api.billing.records({
      from,
      to,
      model: appliedFilters.value.model,
      eventType: appliedFilters.value.eventType,
      page: page.value,
      pageSize: pageSize.value
    })
    return { kind: 'records' as const, value }
  } else if (tab.value === 'requests') {
    const value = await api.billing.requests({
      from,
      to,
      runId: appliedFilters.value.runId,
      page: page.value,
      pageSize: pageSize.value
    })
    return { kind: 'requests' as const, value }
  } else {
    const value = await api.billing.reconciliationProvider.get()
    return { kind: 'reconciliation' as const, value }
  }
})
watch(loaded, (result) => {
  if (!result || result.kind !== tab.value) return
  if (result.kind === 'records') {
    records.value = result.value.items
    total.value = result.value.total
  } else if (result.kind === 'requests') {
    requests.value = result.value.items
    total.value = result.value.total
  } else {
    Object.assign(provider, {
      provider: result.value.provider ?? '',
      endpoint: result.value.endpoint ?? '',
      api_key: '',
      api_key_header: result.value.api_key_header ?? '',
      clear_api_key: false
    })
    schemaText.value = JSON.stringify(result.value.response_schema, null, 2)
    saved.value = snapshot()
  }
})
const {
  data: filterOptions,
  error: filterError,
  reload: reloadFilterOptions
} = useResource(() => api.billing.filterOptions())
onMounted(() => {
  void reload()
  void reloadFilterOptions()
})
const { busy, error, run } = useTask()
useUnsavedChanges({
  dirty: () => tab.value === 'reconciliation' && !!saved.value && snapshot() !== saved.value,
  busy: () => busy.value
})
const open = ref(false),
  request = ref<BillingRequestDetail>(),
  trace = ref<TraceView>()
watch(open, (value) => {
  if (!value) {
    request.value = undefined
    trace.value = undefined
  }
})
async function viewRequest(id: string) {
  await run(async () => {
    request.value = await api.billing.requestDetail(id)
    trace.value = undefined
    open.value = true
  })
}
async function viewTrace(id: string) {
  await run(async () => {
    trace.value = await api.billing.trace(id)
    request.value = undefined
    open.value = true
  })
}
function resetFilters() {
  range.value = undefined
  model.value = ''
  eventType.value = ''
  runId.value = ''
  queryRecords()
}
async function saveProvider() {
  await run(async () => {
    if (!saved.value) throw Error('请先成功加载对账配置，再保存修改')
    const schema = JSON.parse(schemaText.value)
    if (!schema || Array.isArray(schema) || typeof schema !== 'object')
      throw Error('响应 Schema 必须为对象')
    await api.billing.reconciliationProvider.save({
      ...provider,
      api_key: provider.api_key || undefined,
      response_schema: !provider.provider && !Object.keys(schema).length ? undefined : schema
    })
    provider.api_key = ''
    provider.clear_api_key = false
    saved.value = snapshot()
    message.success('对账配置已保存')
  })
}

const formTipId = useId()
</script>

<style scoped>
.billing-settings {
  max-width: 920px;
}
</style>
