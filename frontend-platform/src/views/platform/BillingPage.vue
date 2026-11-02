<script setup lang="ts">
import { computed, ref, useId, watch } from 'vue'
import { AdminPage, EntitySelect, guardFormEnter, useResource } from '@skillforge/shared/admin'
import FieldTip from '@skillforge/shared/components/FieldTip.vue'
import FeedbackNotice from '@skillforge/shared/feedback/FeedbackNotice.vue'
import ModelBillingDetails from '@skillforge/shared/components/ModelBillingDetails.vue'
import { vDescribedBy } from '@skillforge/shared/directives/describedBy'
import { formatMonth } from '@skillforge/shared/utils/datetime'
import { api } from '../../api'
import { loadTenantOptions, resolveTenantOption } from '../../api/entityOptions'
import { useActionPermissions } from '../../composables/actionPermissions'

const { allowed, checked } = useActionPermissions()
const tenantId = ref('')
const month = ref(formatMonth(new Date()))
const applied = ref<{ tenantId: string; month: string }>()
const page = ref(1)
const pageSize = ref(20)
const helpId = useId()
const monthly = useResource(async (signal) => {
  const target = applied.value
  if (!target) throw new Error('请选择机构和月份后查询')
  return {
    ...target,
    value: await checked('foundation.billing.read', () =>
      api.billing.monthly(
        target.tenantId,
        {
          month: target.month,
          page: page.value,
          page_size: pageSize.value
        },
        { signal }
      )
    )
  }
})
const changed = computed(
  () =>
    !!applied.value &&
    (tenantId.value !== applied.value.tenantId || month.value !== applied.value.month)
)
watch(
  () => allowed('foundation.billing.read'),
  (permitted) => {
    if (!permitted) {
      monthly.invalidate()
      monthly.data.value = undefined
    }
  }
)
function query() {
  if (!allowed('foundation.billing.read')) return
  if (!tenantId.value.trim() || !/^\d{4}-(0[1-9]|1[0-2])$/.test(month.value)) {
    monthly.error.value = '请选择机构和有效月份后查询'
    return
  }
  const target = { tenantId: tenantId.value.trim(), month: month.value }
  if (
    !applied.value ||
    applied.value.tenantId !== target.tenantId ||
    applied.value.month !== target.month
  )
    monthly.data.value = undefined
  applied.value = target
  page.value = 1
  void monthly.reload()
}
function changePage(value: number) {
  if (!applied.value || !allowed('foundation.billing.read')) return
  page.value = value
  void monthly.reload()
}
function changeSize(value: number) {
  pageSize.value = value
  changePage(1)
}
watch(tenantId, (value) => {
  if (!applied.value && value.trim() && /^\d{4}-(0[1-9]|1[0-2])$/.test(month.value)) query()
})
</script>

<template>
  <AdminPage title="模型账单" description="按月查看模型调用费用、用量和价格依据。">
    <ElForm class="admin-filters" @keydown.capture="guardFormEnter" @submit.prevent="query">
      <EntitySelect
        v-described-by="helpId"
        :aria-describedby="helpId"
        v-model="tenantId"
        :load="loadTenantOptions"
        :resolve="resolveTenantOption"
        placeholder="选择机构"
        :disabled="!allowed('foundation.billing.read')"
      />
      <ElDatePicker
        v-described-by="helpId"
        :aria-describedby="helpId"
        v-model="month"
        type="month"
        format="YYYY-MM"
        value-format="YYYY-MM"
        placeholder="选择月份"
        aria-label="模型费用月份（北京时间）"
      />
      <ElButton
        native-type="submit"
        :loading="monthly.loading.value"
        :disabled="!allowed('foundation.billing.read')"
        >查询模型费用</ElButton
      >
      <FieldTip :id="helpId"
        >首次选择机构后自动查询所选北京时间月份；修改查询范围后点击查询。费用按调用时的模型价格计算。</FieldTip
      >
    </ElForm>
    <p v-if="applied" class="admin-field-help" role="status">
      当前机构：{{ applied.tenantId }} · 月份：{{ applied.month
      }}{{ changed ? '；选择已修改，查询后生效，翻页仍查看当前范围。' : '' }}
    </p>
    <FeedbackNotice
      v-if="monthly.error.value"
      :title="monthly.error.value"
      type="error"
      :closable="false"
    />
    <ElButton
      v-if="monthly.error.value && applied"
      :disabled="monthly.loading.value"
      @click="monthly.reload"
      >重试当前查询</ElButton
    >
    <ElSkeleton v-if="monthly.loading.value && !monthly.data.value" :rows="6" animated />
    <template v-else-if="monthly.data.value">
      <ModelBillingDetails :monthly="monthly.data.value.value" />
      <ElPagination
        :disabled="monthly.loading.value"
        :current-page="monthly.data.value.value.page"
        :page-size="monthly.data.value.value.page_size"
        :total="monthly.data.value.value.total"
        :page-sizes="[20, 50, 100]"
        layout="total,sizes,prev,pager,next"
        @current-change="changePage"
        @size-change="changeSize"
      />
    </template>
    <ElEmpty v-else-if="!monthly.error.value" description="选择机构和月份后查看模型费用" />
  </AdminPage>
</template>
