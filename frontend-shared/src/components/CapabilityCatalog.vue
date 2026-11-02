<script setup lang="ts">
import FeedbackNotice from '../feedback/FeedbackNotice.vue'
import { vDescribedBy } from '@skillforge/shared/directives/describedBy'

import FieldTip from '@skillforge/shared/components/FieldTip.vue'

import { computed, onMounted, reactive, ref, useId } from 'vue'
import { useRouter } from 'vue-router'
import { AdminPage, useTask, useDrawerClose, guardFormEnter } from '@skillforge/shared/admin'
import type { CapabilityDefinition, CapabilityCatalogApi } from '../capabilities/catalog'
import type { CapabilityType } from '../capabilities/types'
import { statusLabel } from '../admin/presentation'
import { message } from '../feedback'
import type { FormInstance } from 'element-plus'
const formRef = ref<FormInstance>()
const props = defineProps<{
  api: CapabilityCatalogApi
  basePath: string
  skillBasePath: string
  permissions: readonly string[]
}>()
const canCreate = computed(() => props.permissions.includes('foundation.capabilities.create'))
const router = useRouter(),
  load = useTask(),
  action = useTask(),
  rows = ref<CapabilityDefinition[]>([]),
  search = ref(''),
  kind = ref(''),
  state = ref(''),
  open = ref(false)
const form = reactive({
  name: '',
  capabilityKey: '',
  capabilityType: 'SKILL' as CapabilityType,
  description: ''
})
const filtered = computed(() =>
  rows.value.filter(
    (r) =>
      (!kind.value || r.capabilityType === kind.value) &&
      (!state.value || r.status === state.value) &&
      `${r.name} ${r.capabilityKey} ${r.description}`
        .toLowerCase()
        .includes(search.value.trim().toLowerCase())
  )
)
const hasFilters = computed(() => !!search.value.trim() || !!kind.value || !!state.value)
function clearFilters() {
  search.value = ''
  kind.value = ''
  state.value = ''
}
function reload() {
  void load.run(async () => {
    rows.value = await props.api.listDefinitions()
  })
}
function create() {
  if (!canCreate.value) return
  Object.assign(form, { name: '', capabilityKey: '', capabilityType: 'SKILL', description: '' })
  action.error.value = ''
  formRef.value?.clearValidate()
  open.value = true
}
function save() {
  if (!canCreate.value) return
  void action.run(async () => {
    if (!(await formRef.value?.validate().catch(() => false))) return
    if (!canCreate.value) return
    const item = await props.api.createDefinition({ ...form })
    rows.value = [...rows.value.filter((row) => row.id !== item.id), item]
    open.value = false
    message.success('能力已创建')
    try {
      const failure = await router.push(props.basePath + '/' + item.id)
      if (failure) message.warning('能力已创建，请从列表进入详情。')
    } catch {
      message.warning('能力已创建，但未能打开详情。请从列表点击该能力继续。')
    }
  })
}
const { requestClose } = useDrawerClose({
  open: () => open.value,
  busy: () => action.busy.value,
  dirty: () =>
    !!form.name || !!form.capabilityKey || !!form.description || form.capabilityType !== 'SKILL',
  close: () => {
    open.value = false
  }
})
onMounted(reload)

const formTipId = useId()
</script>
<template>
  <AdminPage title="能力目录" description="查找可复用能力，进入详情维护草稿与发布版本"
    ><template #extra
      ><ElButton v-if="canCreate" type="primary" :disabled="action.busy.value" @click="create"
        >新增能力</ElButton
      ></template
    >
    <div class="admin-toolbar admin-filters" role="search" aria-label="筛选能力">
      <ElInput
        v-described-by="formTipId + '-group-0'"
        :aria-describedby="formTipId + '-group-0'"
        v-model="search"
        placeholder="搜索名称、标识或用途"
        aria-label="能力名称、标识或用途"
        class="catalog-search"
        clearable
      /><ElSelect
        v-described-by="formTipId + '-group-0'"
        :aria-describedby="formTipId + '-group-0'"
        v-model="kind"
        clearable
        placeholder="全部类型"
        aria-label="能力类型"
        ><ElOption
          v-for="t in ['SKILL', 'TOOL', 'WORKFLOW', 'KNOWLEDGE_SOURCE']"
          :key="t"
          :value="t"
          :label="statusLabel(t)" /></ElSelect
      ><ElSelect v-model="state" clearable placeholder="全部状态" aria-label="能力状态">
        <ElOption
          v-for="s in ['DRAFT', 'PUBLISHED', 'ARCHIVED', 'DEPRECATED']"
          :key="s"
          :value="s"
          :label="statusLabel(s)"
        />
      </ElSelect>
      <ElButton v-if="hasFilters" @click="clearFilters">清空筛选</ElButton>
      <ElButton :loading="load.busy.value" @click="reload">刷新</ElButton>
      <span
        :id="formTipId + '-group-0'"
        class="admin-field-help catalog-count"
        role="status"
        aria-live="polite"
        >{{
          load.busy.value ? '正在读取目录…' : `显示 ${filtered.length} / ${rows.length} 项能力`
        }}</span
      >
    </div>
    <FeedbackNotice
      v-if="load.error.value"
      :title="load.error.value"
      type="error"
      :closable="false"
    /><FeedbackNotice
      v-if="!open && action.error.value"
      :title="action.error.value"
      type="error"
      :closable="false"
    /><ElTable
      table-layout="auto"
      :data="filtered"
      v-loading="load.busy.value"
      row-key="id"
      :empty-text="
        load.busy.value
          ? '正在加载能力…'
          : load.error.value
            ? '能力加载失败，请点击刷新'
            : '暂无符合条件的能力'
      "
      stripe
      ><ElTableColumn prop="name" label="名称" min-width="180" show-overflow-tooltip
        ><template #default="{ row }"
          ><RouterLink class="capability-name" :to="basePath + '/' + row.id">{{
            row.name
          }}</RouterLink>
          <div class="capability-key">{{ row.capabilityKey }}</div></template
        ></ElTableColumn
      ><ElTableColumn
        prop="description"
        label="说明"
        min-width="240"
        show-overflow-tooltip
      /><ElTableColumn prop="capabilityType" label="类型" width="120"
        ><template #default="{ row }">{{
          statusLabel(row.capabilityType)
        }}</template></ElTableColumn
      ><ElTableColumn prop="status" label="状态" width="100"
        ><template #default="{ row }"
          ><ElTag :type="row.status === 'PUBLISHED' ? 'success' : 'info'" effect="plain">{{
            statusLabel(row.status)
          }}</ElTag></template
        ></ElTableColumn
      ><template #empty
        ><ElEmpty
          :description="
            load.busy.value
              ? '正在读取能力目录…'
              : load.error.value
                ? '目录读取失败'
                : hasFilters
                  ? '没有匹配的能力'
                  : '尚无能力'
          "
          :image-size="64"
          ><ElButton v-if="!load.busy.value && load.error.value" @click="reload">重新加载</ElButton
          ><ElButton v-else-if="!load.busy.value && hasFilters" @click="clearFilters"
            >清空筛选</ElButton
          ><ElButton v-else-if="!load.busy.value && canCreate" type="primary" @click="create"
            >新增能力</ElButton
          ></ElEmpty
        ></template
      ></ElTable
    ><ElDrawer
      v-model="open"
      title="新增能力"
      size="680px"
      class="admin-drawer"
      direction="rtl"
      append-to-body
      :show-close="!action.busy.value"
      :close-on-press-escape="!action.busy.value"
      :before-close="requestClose"
      :close-on-click-modal="false"
      ><FeedbackNotice
        v-if="action.error.value"
        :title="action.error.value"
        type="error"
        :closable="false"
      /><ElForm
        ref="formRef"
        :model="form"
        @submit.prevent="save"
        @keydown.capture="guardFormEnter"
        scroll-to-error
        label-position="top"
        :disabled="!canCreate || action.busy.value"
        ><ElFormItem
          label="能力名称"
          prop="name"
          :rules="[
            { required: true, whitespace: true, message: '请输入能力名称', trigger: 'blur' }
          ]"
          ><ElInput v-model="form.name" /> </ElFormItem
        ><ElFormItem
          label="能力标识"
          prop="capabilityKey"
          :rules="[
            { required: true, whitespace: true, message: '请输入能力标识', trigger: 'blur' }
          ]"
          ><ElInput
            v-described-by="'capability-key-help'"
            v-model="form.capabilityKey"
            aria-describedby="capability-key-help"
          />
          <p id="capability-key-help" class="admin-field-help">
            供接口和其他能力引用的稳定标识，请使用能够明确区分该能力的名称。
          </p></ElFormItem
        ><ElFormItem label="能力类型"
          ><ElSelect
            v-described-by="formTipId + '-field-2'"
            :aria-describedby="formTipId + '-field-2'"
            v-model="form.capabilityType"
            ><ElOption
              v-for="t in ['SKILL', 'TOOL', 'WORKFLOW', 'KNOWLEDGE_SOURCE']"
              :key="t"
              :value="t"
              :label="statusLabel(t)"
          /></ElSelect>
          <FieldTip :id="formTipId + '-field-2'"
            >按用途选择技能、工具、工作流或知识来源，创建后在详情中维护对应内容。</FieldTip
          > </ElFormItem
        ><ElFormItem label="说明"
          ><ElInput
            v-described-by="formTipId + '-field-3'"
            :aria-describedby="formTipId + '-field-3'"
            v-model="form.description"
            type="textarea"
          />
          <FieldTip :id="formTipId + '-field-3'">补充能力用途和适用场景，方便使用者选择。</FieldTip>
        </ElFormItem></ElForm
      ><template #footer
        ><ElButton :disabled="action.busy.value" @click="requestClose">取消</ElButton
        ><ElButton v-if="canCreate" type="primary" :loading="action.busy.value" @click="save"
          >创建</ElButton
        ></template
      ></ElDrawer
    ></AdminPage
  >
</template>

<style scoped>
.catalog-search {
  flex: 1 1 280px;
}
.catalog-count {
  margin: 0;
  white-space: nowrap;
}
.capability-name {
  font-weight: 600;
}
.capability-key {
  color: var(--admin-muted);
  font-size: 12px;
  overflow-wrap: anywhere;
  margin-top: 4px;
}
</style>
