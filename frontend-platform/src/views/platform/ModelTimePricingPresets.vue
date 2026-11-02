<script setup lang="ts">
import { computed, ref, nextTick, watch, useId } from 'vue'
import { api, type ModelTimePricingPolicy, type ModelTimePricingPreset } from '../../api'
import { useActionPermissions } from '../../composables/actionPermissions'
import { useDrawerClose, useResource, useTask, guardFormEnter } from '@skillforge/shared/admin'
import { message, confirm } from '@skillforge/shared/feedback'
import FeedbackNotice from '@skillforge/shared/feedback/FeedbackNotice.vue'
import ModelTimePricingEditor from './ModelTimePricingEditor.vue'
import { vDescribedBy } from '@skillforge/shared/directives/describedBy'
import {
  cloneTimePricingPolicy,
  emptyTimePricingPolicy,
  timePricingErrors,
  pricingWeekdays
} from './model-time-pricing'
const props = defineProps<{ disabled: boolean; canApply: boolean }>()
const emit = defineEmits<{ apply: [policy: ModelTimePricingPolicy] }>()
const { allowed, checked } = useActionPermissions()
const open = ref(false)
const selectedId = ref<string>()
const current = ref<ModelTimePricingPreset>()
const name = ref('')
const nameErrorId = useId() + '-preset-name-error'
const policy = ref(emptyTimePricingPolicy())
const baseline = ref('')
const editing = ref(false)
const errors = ref<Record<string, string>>({})
const contentRoot = ref<HTMLElement>()
const pendingErrorField = ref<string>()
const validationEntries = computed(() => Object.entries(errors.value))
const serialized = () => JSON.stringify({ name: name.value, policy: policy.value })
const dirty = computed(() => editing.value && serialized() !== baseline.value)
const action = useTask()
watch(
  [action.busy, pendingErrorField],
  ([inProgress, field]) => {
    if (inProgress || !field || !open.value) return
    pendingErrorField.value = undefined
    focusError(field)
  },
  { flush: 'post' }
)
const list = useResource((signal) => api.admin.models.timePricingPresets.list({ signal }))
const detail = useResource(async (signal) => {
  const id = selectedId.value
  if (!id) throw new Error('请选择预设')
  return api.admin.models.timePricingPresets.detail(id, { signal })
})
const history = useResource((signal) => {
  const id = selectedId.value
  if (!id) throw new Error('请选择预设')
  return api.admin.models.timePricingPresets.history(id, { signal })
})
const readBusy = computed(() => list.loading.value || detail.loading.value)
const busy = computed(() => action.busy.value || readBusy.value)
const canEdit = computed(() =>
  allowed(current.value ? 'foundation.models.update' : 'foundation.models.create')
)
function accept(value: ModelTimePricingPreset) {
  current.value = value
  name.value = value.name
  policy.value = cloneTimePricingPolicy(value.policy)
  baseline.value = serialized()
  errors.value = {}
  editing.value = true
}
function close() {
  pendingErrorField.value = undefined
  open.value = false
  list.invalidate()
  detail.invalidate()
  history.invalidate()
  detail.error.value = ''
  editing.value = false
  current.value = undefined
  selectedId.value = undefined
  history.data.value = undefined
  history.error.value = ''
}
const { requestClose } = useDrawerClose({
  open: () => open.value,
  busy: () => action.busy.value,
  dirty: () => dirty.value,
  close
})
async function show() {
  if (props.disabled || !allowed('foundation.models.read')) return
  open.value = true
  await list.reload()
}
async function mayDiscard() {
  if (busy.value) return false
  return (
    !dirty.value ||
    (await confirm({
      title: '切换预设',
      content: '切换后将丢弃当前未保存的预设修改。',
      confirmText: '丢弃并切换',
      cancelText: '继续编辑'
    }))
  )
}
async function select(id: string) {
  if (!(await mayDiscard())) return
  selectedId.value = id
  current.value = undefined
  editing.value = false
  history.invalidate()
  history.data.value = undefined
  history.error.value = ''
  errors.value = {}
  void history.reload()
  const result = await detail.reload()
  if (result && open.value) accept(result)
}
async function retryDetail() {
  if (!selectedId.value) return
  const result = await detail.reload()
  if (result && open.value) accept(result)
}
async function createNew() {
  if (!allowed('foundation.models.create') || !(await mayDiscard())) return
  detail.invalidate()
  history.invalidate()
  detail.error.value = ''
  current.value = undefined
  selectedId.value = undefined
  history.data.value = undefined
  history.error.value = ''
  name.value = ''
  policy.value = emptyTimePricingPolicy()
  baseline.value = serialized()
  errors.value = {}
  editing.value = true
}
function save() {
  const permission = current.value ? 'foundation.models.update' : 'foundation.models.create'
  if (!allowed(permission)) return
  void action.run(async () => {
    errors.value = timePricingErrors(policy.value)
    if (!name.value.trim()) errors.value.name = '请填写预设名称'
    if (Array.from(name.value).length > 128) errors.value.name = '预设名称最多 128 个字符'
    if (Object.keys(errors.value).length) {
      pendingErrorField.value = Object.keys(errors.value)[0]!
      throw new Error('请修正预设配置后保存')
    }
    const value = { name: name.value, policy: cloneTimePricingPolicy(policy.value) }
    const target = current.value
    const saved = await checked(permission, () =>
      target
        ? api.admin.models.timePricingPresets.update(target.id, {
            ...value,
            expected_revision: target.revision
          })
        : api.admin.models.timePricingPresets.create(value)
    )
    accept(saved)
    selectedId.value = saved.id
    if (!target) void history.reload()
    message.success('预设草稿已保存，发布后可供模型采用')
    await list.reload()
  })
}
function focusError(field: string) {
  void nextTick(() => {
    const owner = contentRoot.value?.querySelector<HTMLElement>(
      `[data-time-pricing-field="${field}"]`
    )
    const target =
      owner?.querySelector<HTMLElement>(
        'input:not([disabled]), textarea:not([disabled]), select:not([disabled]), button:not([disabled])'
      ) ??
      owner?.querySelector<HTMLElement>('[tabindex]:not([tabindex="-1"]):not([disabled])') ??
      owner
    target?.scrollIntoView?.({ block: 'center' })
    target?.focus()
  })
}
function refreshErrors() {
  if (!Object.keys(errors.value).some((key) => key.startsWith('policy.'))) return
  const nameError = errors.value.name
  errors.value = timePricingErrors(policy.value)
  if (nameError) errors.value.name = nameError
}
function refreshNameError() {
  if (!errors.value.name) return
  if (!name.value.trim()) errors.value.name = '请填写预设名称'
  else if (Array.from(name.value).length > 128) errors.value.name = '预设名称最多 128 个字符'
  else delete errors.value.name
}
function submitTextField(event: KeyboardEvent) {
  const target = event.target
  if (
    event.defaultPrevented ||
    event.isComposing ||
    event.keyCode === 229 ||
    !(target instanceof HTMLInputElement) ||
    target.type !== 'text' ||
    target.closest('[role="combobox"]')
  )
    return
  event.preventDefault()
  save()
}
function ruleRows(value: ModelTimePricingPolicy) {
  return [
    { label: '计价时区', values: [value.time_zone] },
    { label: '覆盖日期', values: [`${value.valid_from} 至 ${value.valid_through}`] },
    { label: '峰时倍率', values: [value.peak_multiplier ?? '未知'] },
    { label: '谷时倍率', values: [value.off_peak_multiplier ?? '未知'] },
    {
      label: '常规峰日',
      values: [
        value.peak_weekdays
          .map((day) => pricingWeekdays.find((item) => item.value === day)?.label ?? day)
          .join('、') || '无常规峰日'
      ]
    },
    {
      label: '峰时段',
      values: value.peak_periods.length
        ? value.peak_periods.map((period) => `${period.start} 至 ${period.end}`)
        : ['无峰时段']
    },
    {
      label: '额外峰日',
      values: value.peak_date_ranges.length
        ? value.peak_date_ranges.map((date) => `${date.from} 至 ${date.through}`)
        : ['未配置']
    },
    {
      label: '整日谷日',
      values: value.off_peak_date_ranges.length
        ? value.off_peak_date_ranges.map((date) => `${date.from} 至 ${date.through}`)
        : ['未配置']
    },
    {
      label: '适用模型范围',
      values: value.applicable_models.length
        ? value.applicable_models.map(
            (scope) =>
              `提供方：${scope.provider_id ?? '不限'}；服务：${scope.service_id ?? '不限'}；目录版本：${scope.profile_version ?? '不限'}；模型：${scope.model_id ?? '不限'}`
          )
        : ['全部语言模型']
    },
    { label: '价格规则来源', values: [value.source_url ?? '未记录'] },
    { label: '来源版本', values: [value.source_version ?? '未记录'] },
    { label: '日历来源', values: [value.calendar_source_url ?? '未记录'] }
  ]
}
function publish() {
  if (!allowed('foundation.models.publish') || !current.value || dirty.value) return
  void action.run(async () => {
    const target = current.value!
    if (
      !(await confirm({
        title: '发布峰谷预设',
        content: `发布“${target.name}”的当前草稿？现有模型和调用继续使用各自原配置。`,
        confirmText: '发布'
      }))
    )
      return
    const saved = await checked('foundation.models.publish', () =>
      api.admin.models.timePricingPresets.publish(target.id, target.revision)
    )
    accept(saved)
    message.success('预设已发布；需在模型中采用并发布模型后生效')
    await history.reload()
    await list.reload()
  })
}
function apply(version: ModelTimePricingPreset) {
  if (!props.canApply || busy.value || dirty.value) return
  emit('apply', cloneTimePricingPolicy(version.policy))
  message.success(`已复制预设“${version.name}”修订 ${version.revision}，保存并发布模型后生效`)
  close()
}
</script>

<template>
  <div class="time-pricing-presets">
    <ElButton :disabled="disabled || !allowed('foundation.models.read')" @click="show"
      >管理或采用峰谷预设</ElButton
    >
    <ElDrawer
      class="admin-drawer time-pricing-presets-drawer"
      direction="rtl"
      append-to-body
      v-model="open"
      :title="current ? `峰谷计价预设 · ${current.name}` : '峰谷计价预设'"
      :before-close="requestClose"
      :close-on-click-modal="false"
      size="min(960px, 100vw)"
    >
      <div ref="contentRoot" class="admin-drawer-content">
        <FeedbackNotice
          v-if="list.error.value"
          :title="list.error.value"
          :closable="false"
          type="error"
        />
        <FeedbackNotice
          v-if="detail.error.value"
          :title="detail.error.value"
          :closable="false"
          type="error"
        />
        <FeedbackNotice
          v-if="history.error.value"
          :title="history.error.value"
          :closable="false"
          type="error"
        />
        <FeedbackNotice
          v-if="action.error.value"
          :title="action.error.value"
          :closable="false"
          type="error"
        />
        <div class="preset-actions">
          <ElButton :loading="list.loading.value" :disabled="action.busy.value" @click="list.reload"
            >刷新预设</ElButton
          >
          <ElButton v-if="allowed('foundation.models.create')" :disabled="busy" @click="createNew"
            >新建预设</ElButton
          >
        </div>
        <ElSkeleton v-if="list.loading.value && !list.data.value" :rows="3" />
        <ElEmpty v-else-if="list.data.value?.length === 0" description="暂无峰谷预设" />
        <ul v-if="list.data.value?.length" class="preset-list" aria-label="预设列表">
          <li v-for="preset in list.data.value" :key="preset.id">
            <ElButton text :disabled="busy" @click="select(preset.id)">{{ preset.name }}</ElButton>
            <span class="admin-field-help">{{
              preset.published_revision ? `已发布修订 ${preset.published_revision}` : '尚未发布'
            }}</span>
          </li>
        </ul>
        <ElSkeleton v-if="detail.loading.value" :rows="5" />
        <ElButton v-if="detail.error.value" :disabled="busy" @click="retryDetail"
          >重试读取所选预设</ElButton
        >
        <section
          v-if="validationEntries.length > 1"
          class="preset-error-summary"
          aria-label="预设配置错误摘要"
        >
          <p>请修正以下 {{ validationEntries.length }} 项配置：</p>
          <ul>
            <li v-for="[field, text] in validationEntries" :key="field">
              <ElButton text @click="focusError(field)">{{ text }}</ElButton>
            </li>
          </ul>
        </section>
        <ElForm
          v-if="editing && canEdit"
          label-position="top"
          :disabled="action.busy.value || !canEdit"
          @keydown.capture="guardFormEnter"
          @keydown.enter="submitTextField"
          @submit.prevent="save"
        >
          <h3>{{ current ? '预设工作草稿' : '新建预设' }}</h3>
          <ElFormItem
            label="预设名称"
            :error="errors.name"
            required
            data-time-pricing-field="name"
            v-described-by="errors.name ? nameErrorId : undefined"
          >
            <ElInput v-model="name" :aria-invalid="!!errors.name" @input="refreshNameError" />
            <template #error>
              <div :id="nameErrorId" class="el-form-item__error">{{ errors.name }}</div>
            </template>
          </ElFormItem>
          <ModelTimePricingEditor v-model="policy" :errors="errors" @field-change="refreshErrors" />
        </ElForm>
        <section v-else-if="editing" aria-label="预设工作草稿只读信息">
          <h3>{{ name }}</h3>
          <p class="admin-field-help">当前账号只可查看，规则正文可选择并复制。</p>
          <dl class="preset-rule-details">
            <div v-for="row in ruleRows(policy)" :key="row.label">
              <dt>{{ row.label }}</dt>
              <dd>
                <p v-for="(value, index) in row.values" :key="index">{{ value }}</p>
              </dd>
            </div>
          </dl>
        </section>
        <section v-if="editing" aria-label="已发布预设版本">
          <h3>已发布版本</h3>
          <p class="admin-field-help">
            采用操作复制所选发布版本到模型当前表单，仍需另行保存并发布模型。编辑预设或发布新预设不会自动改变模型。
          </p>
          <p v-if="dirty" class="admin-field-help">
            请先保存预设草稿，或切换预设放弃修改，再采用已发布版本。
          </p>
          <ElButton
            v-if="history.error.value"
            :loading="history.loading.value"
            @click="history.reload"
            >重试读取发布历史</ElButton
          >
          <ElSkeleton v-if="history.loading.value && !history.data.value" :rows="3" />
          <ElEmpty v-else-if="history.data.value?.length === 0" description="尚无已发布版本" />
          <ElCard
            v-for="version in history.data.value ?? []"
            :key="version.revision"
            class="admin-card"
          >
            <h4>{{ version.name }} · 修订 {{ version.revision }}</h4>
            <p>
              {{ version.policy.time_zone }} · {{ version.policy.valid_from }} 至
              {{ version.policy.valid_through }}
            </p>
            <p>
              峰时倍率 {{ version.policy.peak_multiplier ?? '未知' }} · 谷时倍率
              {{ version.policy.off_peak_multiplier ?? '未知' }}
            </p>
            <ElCollapse
              ><ElCollapseItem :title="`查看修订 ${version.revision} 规则`"
                ><dl class="preset-rule-details">
                  <div v-for="row in ruleRows(version.policy)" :key="row.label">
                    <dt>{{ row.label }}</dt>
                    <dd>
                      <p v-for="(value, index) in row.values" :key="index">{{ value }}</p>
                    </dd>
                  </div>
                </dl></ElCollapseItem
              ></ElCollapse
            >
            <ElButton
              :disabled="!canApply || busy || dirty || history.loading.value"
              @click="apply(version)"
              >采用修订 {{ version.revision }}</ElButton
            >
          </ElCard>
        </section>
      </div>
      <template #footer>
        <p v-if="editing && dirty" class="admin-field-help">
          有未保存的预设修改；保存后才能发布或采用已发布版本。
        </p>
        <p
          v-else-if="editing && current && !current.has_unpublished_changes"
          class="admin-field-help"
        >
          当前草稿已发布，无需再次保存或发布。
        </p>
        <p v-if="!canApply" class="admin-field-help">当前没有模型编辑权限，不能采用预设到模型。</p>
        <div class="admin-actions preset-footer-actions">
          <ElButton :disabled="action.busy.value" @click="requestClose">关闭</ElButton>
          <ElButton
            v-if="editing && canEdit"
            type="primary"
            :loading="action.busy.value"
            :disabled="busy || (!!current && !dirty)"
            @click="save"
            >保存预设草稿</ElButton
          >
          <ElButton
            v-if="current && allowed('foundation.models.publish')"
            :disabled="busy || dirty || !current.has_unpublished_changes"
            @click="publish"
            >发布预设</ElButton
          >
        </div>
      </template>
    </ElDrawer>
  </div>
</template>
<style scoped>
/* 抽屉传送至 body，只限定本抽屉的错误排版。 */
:global(.time-pricing-presets-drawer .el-form-item__error) {
  position: static;
  flex-basis: 100%;
}
.time-pricing-presets {
  grid-column: 1 / -1;
}
.preset-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.preset-list {
  list-style: none;
  padding: 0;
}
.preset-list li {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
}
.preset-footer-actions {
  justify-content: flex-end;
  margin-top: 8px;
}
.preset-rule-details {
  margin: 12px 0;
}
.preset-rule-details > div {
  display: grid;
  grid-template-columns: minmax(96px, 1fr) minmax(0, 3fr);
  gap: 12px;
  padding-block: 8px;
  border-bottom: 1px solid var(--admin-border);
}
.preset-rule-details dt {
  color: var(--admin-muted);
}
.preset-rule-details dd {
  min-width: 0;
  margin: 0;
  overflow-wrap: anywhere;
  user-select: text;
}
.preset-rule-details dd p {
  margin: 0;
}
.preset-error-summary {
  border-inline-start: 3px solid var(--el-color-danger);
  padding-inline: 12px;
  margin-block: 16px;
}
.preset-error-summary .el-button {
  white-space: normal;
  height: auto;
  min-height: 32px;
  text-align: start;
}
[data-time-pricing-field] {
  scroll-margin-block: 24px;
}
</style>
