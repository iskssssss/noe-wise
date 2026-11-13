<script setup lang="ts">
import FeedbackNotice from '../../feedback/FeedbackNotice.vue'
import { HttpClientError } from '../../utils/http'
import { computed, onScopeDispose, reactive, ref, shallowRef, useId, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import { useResource, useTask } from '../../admin/tasks'
import { useUnsavedChanges } from '../../admin/leave'
import { formatDateTime } from '../../utils/datetime'
import { saveFoundationBlob } from './contracts'
import { useFoundationPageSize } from '../appearance'
import FieldTip from '../../components/FieldTip.vue'
import { vDescribedBy } from '../../directives/describedBy'
import type {
  FoundationFile,
  FoundationJob,
  FoundationPage,
  FoundationRequest,
  TransferHandler,
  TransferPreflight,
  TransferRowError,
  TransferSubmission
} from './contracts'

const props = defineProps<{ request: FoundationRequest; apiBase: string; permissions: string[] }>()
const page = ref(1)
const formTipId = useId()
const pageSize = useFoundationPageSize()
const handlerCode = ref('')
const operation = ref<'import' | 'export'>('import')
const optionValues = reactive<Record<string, string>>({})
const inputFile = ref<FoundationFile>()
const checked = ref<TransferPreflight>()
const idempotencyKey = ref(crypto.randomUUID())
const selected = ref<FoundationJob>()
const selectedId = ref('')
const message = ref('')
const action = useTask()
const handlers = useResource((signal) =>
  props.request<TransferHandler[]>('GET', `${props.apiBase}/jobs/handlers`, undefined, false, {
    signal,
    silent: true
  })
)
const jobs = useResource((signal) =>
  props.request<FoundationPage<FoundationJob>>(
    'GET',
    `${props.apiBase}/jobs?page=${page.value}&page_size=${pageSize.value}`,
    undefined,
    false,
    { signal, silent: true }
  )
)
const errors = useResource((signal) =>
  props.request<TransferRowError[]>(
    'GET',
    `${props.apiBase}/jobs/errors?job_id=${encodeURIComponent(selectedId.value)}&page_size=200`,
    undefined,
    false,
    { signal, silent: true }
  )
)
const detail = useResource((signal) =>
  props.request<FoundationJob>(
    'GET',
    `${props.apiBase}/jobs/detail?job_id=${encodeURIComponent(selectedId.value)}`,
    undefined,
    false,
    { signal, silent: true }
  )
)
watch(
  selectedId,
  (id) => {
    errors.invalidate()
    errors.data.value = undefined
    errors.error.value = ''
    detail.invalidate()
    detail.data.value = undefined
    detail.error.value = ''
    if (id) void errors.reload()
  },
  { flush: 'sync' }
)
const currentHandler = computed(() =>
  handlers.data.value?.find((h) => h.code === handlerCode.value)
)
const dictionaries = useResource((signal) =>
  props.request<{ code: string; name: string; enabled: boolean }[]>(
    'GET',
    `${props.apiBase}/catalog/dictionaries`,
    undefined,
    false,
    { signal, silent: true }
  )
)
const needsDictionary = computed(
  () =>
    currentHandler.value?.option_fields.some((field) => field.code === 'dictionary_code') &&
    props.permissions.includes('foundation.dictionary.read')
)
watch(needsDictionary, (needed) => {
  if (needed) void dictionaries.reload()
})
const canCreate = computed(() => props.permissions.includes('foundation.jobs.create'))
const canCancel = computed(() => props.permissions.includes('foundation.jobs.cancel'))
const pendingSubmission = shallowRef<TransferSubmission>()
const submittedJob = ref<FoundationJob>()
const submitted = computed(() => Boolean(submittedJob.value))
const stateLabels: Record<string, string> = {
  queued: '排队中',
  running: '处理中',
  cancel_requested: '正在取消',
  succeeded: '已完成',
  failed: '失败',
  cancelled: '已取消',
  expired: '结果已过期'
}
function handlerTitle(code: string) {
  return handlers.data.value?.find((handler) => handler.code === code)?.title || code
}
function shortId(value: string) {
  return value.length > 14 ? `${value.slice(0, 6)}…${value.slice(-4)}` : value
}
let timer: ReturnType<typeof setTimeout> | undefined
let disposed = false
let generation = 0

/** 身份变化后丢弃旧写投影与错误，确认框之后不向新身份继续发送旧命令。 */
function currentAction<T>(
  operation: (active: () => boolean, request: FoundationRequest, base: string) => Promise<T>
) {
  const current = generation
  const request = props.request
  const base = props.apiBase
  return action.run(async () => {
    try {
      const result = await operation(() => !disposed && current === generation, request, base)
      return current === generation ? result : undefined
    } catch (reason) {
      if (disposed || current !== generation) {
        if (reason instanceof HttpClientError) reason.claimFeedback()
        throw 'cancel'
      }
      throw reason
    }
  })
}

/** 仅提交当前处理器声明的领域字段，必填字段先给出明确错误。 */
function options(): Record<string, string> {
  const value: Record<string, string> = {}
  for (const field of currentHandler.value?.option_fields ?? []) {
    const input = optionValues[field.code] ?? ''
    if (field.required && !input.trim()) throw new Error(`请填写${field.label}`)
    if (input || field.required) value[field.code] = input
  }
  return value
}
/** 材料改变取消预检基线，但不重发已提交任务。 */
function invalidatePreflight() {
  if (pendingSubmission.value) return
  checked.value = undefined
  idempotencyKey.value = crypto.randomUUID()
  message.value = ''
  submittedJob.value = undefined
}
/** 文件上传与预检分开，上传失败不能伪造已保存材料。 */
async function upload(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  const result = await currentAction(async (_active, request, base) => {
    const body = new FormData()
    body.append('file', file)
    return request<FoundationFile>('POST', `${base}/files/upload`, body)
  })
  if (result) {
    inputFile.value = result
    invalidatePreflight()
  }
}
/** 预检失败展示全部逐行错误，业务写入尚未开始。 */
async function preflight() {
  const result = await currentAction((_active, request, base) =>
    request<TransferPreflight>('POST', `${base}/jobs/preflight`, {
      handler: handlerCode.value,
      file_id: inputFile.value?.id,
      options: options()
    })
  )
  if (result) checked.value = result
}
/** 保留受理幂等键供未知响应核验；服务器同键同内容读取原任务。 */
async function submit() {
  if (submittedJob.value) {
    inspect(submittedJob.value)
    return
  }
  await currentAction(async (active, request, base) => {
    const previous = pendingSubmission.value
    const command: TransferSubmission = previous ?? {
      handler: handlerCode.value,
      operation: operation.value,
      options: options(),
      idempotency_key: idempotencyKey.value,
      ...(operation.value === 'import' ? { file_id: inputFile.value?.id } : {})
    }
    pendingSubmission.value = command
    let result: FoundationJob
    try {
      result = await request<FoundationJob>('POST', `${base}/jobs/submit`, command)
      if (!result || typeof result.id !== 'string' || !result.id.trim())
        throw Error('任务受理响应不完整，请重试原提交以核对原任务')
    } catch (reason) {
      const rejected =
        reason instanceof HttpClientError &&
        reason.status >= 400 &&
        reason.status < 500 &&
        reason.status !== 408 &&
        !!reason.code &&
        !!reason.traceId
      if (active() && !previous && rejected) {
        pendingSubmission.value = undefined
        idempotencyKey.value = crypto.randomUUID()
      }
      throw reason
    }
    if (!active()) return
    pendingSubmission.value = undefined
    submittedJob.value = result
    selected.value = result
    selectedId.value = result.id
    message.value = `任务已受理：${result.id}，处理结果请查看任务详情`
    void poll(generation)
  })
}
/** 取消只确认停止后续行，已提交行保留；最终结果由工作者确认。 */
async function cancel(job: FoundationJob) {
  const result = await currentAction(async (active, request, base) => {
    await ElMessageBox.confirm('取消后续处理？已提交的业务行会保留。', '取消管理任务', {
      autofocus: false,
      confirmButtonText: '取消后续处理',
      cancelButtonText: '继续等待'
    })
    if (!active()) throw 'cancel'
    return request<FoundationJob>('POST', `${base}/jobs/cancel`, { job_id: job.id })
  })
  if (result) {
    selected.value = result
    selectedId.value = result.id
    message.value = `当前任务状态：${stateLabels[result.state] ?? result.state}。已提交的数据会保留，请在任务详情核对最终结果。`
    void poll(generation)
  }
}
/** 详情选定稳定身份，后发查询拥有更新权。 */
function inspect(job: FoundationJob) {
  selected.value = job
  if (selectedId.value === job.id) void errors.reload()
  selectedId.value = job.id
  void poll(generation)
}
/** 结果继续由文件服务重新鉴权下载。 */
async function download(job: FoundationJob) {
  if (!job.result_file_id) return
  await currentAction(async (active, request, base) => {
    const blob = await request<Blob>(
      'GET',
      `${base}/files/download?file_id=${encodeURIComponent(job.result_file_id!)}`,
      undefined,
      false,
      { responseType: 'blob' }
    )
    if (active()) saveFoundationBlob(blob, `${job.handler}-${job.id}.csv`)
  })
}
/** 轮询只读取状态；页面卸载与作用域改变释放定时器和旧结果写权。 */
async function poll(current: number) {
  if (disposed || current !== generation) return
  if (timer) clearTimeout(timer)
  const value = await jobs.reload()
  if (disposed || current !== generation) return
  const inspecting = selectedId.value
  if (inspecting) {
    const row = value?.items.find((item) => item.id === inspecting) ?? (await detail.reload())
    if (disposed || current !== generation) return
    if (row && selectedId.value === inspecting) selected.value = row
  }
  if (
    value?.items.some((item) => ['queued', 'running', 'cancel_requested'].includes(item.state)) ||
    (selected.value && ['queued', 'running', 'cancel_requested'].includes(selected.value.state))
  )
    timer = setTimeout(() => void poll(current), 3000)
}
/** 本页页大小仅控制列表，不修改保存的个人默认。 */
function resize(value: number) {
  pageSize.value = value
  page.value = 1
  void poll(generation)
}
/** 使用当前处理器声明的列生成空白材料，用户无需抄写表头。 */
function downloadTemplate() {
  if (!currentHandler.value) return
  const columns = currentHandler.value.columns.map((column) => `"${column.replaceAll('"', '""')}"`)
  saveFoundationBlob(
    new Blob([columns.join(',') + '\r\n'], { type: 'text/csv;charset=utf-8' }),
    `${currentHandler.value.code}-template.csv`
  )
}
watch(handlerCode, () => {
  for (const key of Object.keys(optionValues)) delete optionValues[key]
  if (!currentHandler.value?.can_import && currentHandler.value?.can_export)
    operation.value = 'export'
  invalidatePreflight()
})
watch(operation, invalidatePreflight)
watch(optionValues, invalidatePreflight, { deep: true })
watch(
  () => [props.apiBase, props.request, props.permissions.join(',')],
  () => {
    generation++
    if (timer) clearTimeout(timer)
    jobs.invalidate()
    handlers.invalidate()
    errors.invalidate()
    detail.invalidate()
    dictionaries.invalidate()
    dictionaries.data.value = undefined
    jobs.data.value = undefined
    handlers.data.value = undefined
    detail.data.value = undefined
    errors.data.value = undefined
    inputFile.value = undefined
    selected.value = undefined
    selectedId.value = ''
    checked.value = undefined
    submittedJob.value = undefined
    pendingSubmission.value = undefined
    handlerCode.value = ''
    message.value = ''
    action.error.value = ''
    void handlers.reload()
    void poll(generation)
  },
  { immediate: true }
)
useUnsavedChanges({
  dirty: () =>
    Boolean(
      pendingSubmission.value ||
      (handlerCode.value &&
        !submitted.value &&
        (inputFile.value || Object.values(optionValues).some(Boolean)))
    ),
  busy: () => action.busy.value,
  leaveMessage: () =>
    pendingSubmission.value
      ? '原提交结果尚未确认，离开后将丢失本页保留的原提交恢复信息。请先重试原提交，或确认离开。'
      : undefined
})
onScopeDispose(() => {
  disposed = true
  generation++
  if (timer) clearTimeout(timer)
})
</script>

<template>
  <section class="foundation-jobs" aria-label="管理任务中心">
    <p class="muted">导入须先预检。取消只停止后续行；已提交行保留。结果保留七天。</p>
    <FeedbackNotice
      v-if="action.error.value"
      :title="action.error.value"
      type="error"
      :closable="false"
    />
    <FeedbackNotice
      v-if="handlers.error.value"
      :title="'可用业务类型读取失败：' + handlers.error.value"
      type="error"
      :closable="false"
    />
    <FeedbackNotice
      v-if="jobs.error.value"
      :title="'任务读取失败：' + jobs.error.value"
      type="error"
      :closable="false"
    />
    <FeedbackNotice
      v-if="detail.error.value"
      :title="'任务详情读取失败：' + detail.error.value"
      type="error"
      :closable="false"
    />
    <FeedbackNotice v-if="message" :title="message" type="success" :closable="false" />
    <p v-if="pendingSubmission" class="muted" role="status">
      原提交结果尚未确认，当前材料和选项已锁定。重试会继续提交原内容；已受理时返回原任务，不会重复创建。
    </p>
    <ElForm v-if="canCreate" label-position="top" @submit.prevent="submit">
      <ElFormItem label="业务类型"
        ><ElSelect
          v-model="handlerCode"
          v-described-by="formTipId + '-handler'"
          placeholder="选择导入导出领域"
          :disabled="action.busy.value || submitted || !!pendingSubmission"
          ><ElOption
            v-for="handler in handlers.data.value ?? []"
            :key="handler.code"
            :label="handler.title"
            :value="handler.code"
        /></ElSelect>
        <FieldTip :id="formTipId + '-handler'"> 切换业务类型会清空选项和预检结果。 </FieldTip>
      </ElFormItem>
      <ElFormItem v-if="currentHandler" label="操作"
        ><ElRadioGroup
          v-model="operation"
          v-described-by="formTipId + '-operation'"
          :disabled="action.busy.value || submitted || !!pendingSubmission"
          ><ElRadioButton value="import" :disabled="!currentHandler?.can_import">导入</ElRadioButton
          ><ElRadioButton value="export" :disabled="!currentHandler?.can_export"
            >导出</ElRadioButton
          ></ElRadioGroup
        >
        <FieldTip :id="formTipId + '-operation'">
          导入使用 UTF-8 CSV；导出生成 CSV 文件。
        </FieldTip>
      </ElFormItem>
      <ElFormItem
        v-for="field in currentHandler?.option_fields ?? []"
        :key="field.code"
        :label="field.label"
        :required="field.required"
        ><ElSelect
          v-if="field.code === 'dictionary_code' && needsDictionary"
          v-model="optionValues[field.code]"
          filterable
          :disabled="action.busy.value || submitted || !!pendingSubmission"
          placeholder="选择字典"
        >
          <ElOption
            v-for="dictionary in dictionaries.data.value ?? []"
            :key="dictionary.code"
            :label="`${dictionary.name} · ${dictionary.code}`"
            :value="dictionary.code"
          />
        </ElSelect>
        <ElInput
          v-else
          v-model="optionValues[field.code]"
          :disabled="action.busy.value || submitted || !!pendingSubmission"
          :placeholder="field.hint"
      /></ElFormItem>
      <FeedbackNotice
        v-if="needsDictionary && dictionaries.error.value"
        :title="dictionaries.error.value"
        type="error"
        :closable="false"
      />
      <ElButton v-if="needsDictionary && dictionaries.error.value" @click="dictionaries.reload()"
        >重试读取字典</ElButton
      >
      <div v-if="currentHandler && operation === 'import'" class="actions">
        <ElButton @click="downloadTemplate">下载空白模板</ElButton
        ><span class="muted">{{ currentHandler.columns.length }} 列，按模板填写后上传</span>
      </div>
      <ElFormItem
        v-if="currentHandler && operation === 'import' && !submitted"
        label="CSV 文件（UTF-8）"
        ><input
          type="file"
          accept=".csv"
          :aria-describedby="formTipId + '-upload'"
          :disabled="action.busy.value || !!pendingSubmission"
          @change="upload"
        /><span v-if="inputFile">{{ inputFile.name }}</span>
        <FieldTip :id="formTipId + '-upload'">
          选中文件即上传材料；预检通过并提交后才写入业务数据。
        </FieldTip>
      </ElFormItem>
      <div v-if="currentHandler" class="actions">
        <ElButton
          v-if="operation === 'import' && !submitted"
          :loading="action.busy.value"
          :disabled="action.busy.value || !!pendingSubmission || !handlerCode || !inputFile"
          @click="preflight"
          >导入预检</ElButton
        ><ElButton
          native-type="submit"
          type="primary"
          :loading="action.busy.value"
          :disabled="
            !currentHandler ||
            (operation === 'import'
              ? !currentHandler.can_import || !checked?.valid
              : !currentHandler.can_export)
          "
          >{{
            submitted ? '查看已受理任务' : pendingSubmission ? '重试原提交' : '提交任务'
          }}</ElButton
        ><ElButton v-if="submitted" :disabled="action.busy.value" @click="invalidatePreflight"
          >新建任务</ElButton
        >
      </div>
      <FeedbackNotice
        v-if="checked"
        :title="
          checked.valid
            ? `预检通过，共 ${checked.row_count} 行；尚未导入，提交任务后开始处理`
            : `预检发现 ${checked.errors.length} 项错误，业务数据未写入`
        "
        :type="checked.valid ? 'success' : 'error'"
        :closable="false"
      />
      <ElTable v-if="checked && !checked.valid" :data="checked.errors" empty-text="暂无错误"
        ><ElTableColumn prop="row_number" label="数据行" width="100" /><ElTableColumn
          prop="field"
          label="字段"
          width="160" /><ElTableColumn prop="message" label="纠正说明" min-width="240"
      /></ElTable>
    </ElForm>
    <div class="actions">
      <ElButton :loading="jobs.loading.value" @click="poll(generation)">刷新任务</ElButton>
    </div>
    <ElTable
      :data="jobs.data.value?.items ?? []"
      v-loading="jobs.loading.value"
      row-key="id"
      :empty-text="
        jobs.error.value
          ? '任务列表尚未读取成功'
          : jobs.loading.value
            ? '正在读取任务…'
            : '暂无管理任务'
      "
    >
      <ElTableColumn label="任务" min-width="180"
        ><template #default="{ row }"
          ><ElButton link @click="inspect(row as FoundationJob)">{{
            handlerTitle(row.handler)
          }}</ElButton>
          <div class="muted" :title="row.id">
            {{ shortId(row.id) }} · {{ formatDateTime(row.created_at) }}
          </div></template
        ></ElTableColumn
      >
      <ElTableColumn label="方式" width="64"
        ><template #default="{ row }">{{
          row.operation === 'import' ? '导入' : '导出'
        }}</template></ElTableColumn
      >
      <ElTableColumn label="状态与进度" min-width="160"
        ><template #default="{ row }"
          ><div>{{ stateLabels[row.state] ?? row.state }}</div>
          <div class="muted">
            {{ row.completed_rows }} / {{ row.total_rows }}，错误 {{ row.error_rows }}
          </div></template
        ></ElTableColumn
      >
      <ElTableColumn label="操作" width="180" fixed="right"
        ><template #default="{ row }"
          ><ElButton link @click="inspect(row as FoundationJob)">详情</ElButton
          ><ElButton
            v-if="row.result_file_id"
            link
            :disabled="action.busy.value"
            @click="download(row as FoundationJob)"
            >下载结果</ElButton
          ><ElButton
            v-if="canCancel && ['queued', 'running'].includes(row.state)"
            link
            type="danger"
            :disabled="action.busy.value"
            @click="cancel(row as FoundationJob)"
            >取消</ElButton
          ></template
        ></ElTableColumn
      >
    </ElTable>
    <ElPagination
      v-described-by="formTipId + '-pagination'"
      :current-page="page"
      :page-size="pageSize"
      :total="jobs.data.value?.total ?? 0"
      :page-sizes="[10, 20, 50, 100]"
      layout="total, sizes, prev, pager, next"
      @current-change="
        (value: number) => {
          page = value
          poll(generation)
        }
      "
      @size-change="resize"
    />
    <FieldTip :id="formTipId + '-pagination'"> 条数仅对当前列表生效。 </FieldTip>
    <ElDescriptions v-if="selected" title="任务详情" :column="2" border
      ><ElDescriptionsItem label="任务身份">{{ selected.id }}</ElDescriptionsItem
      ><ElDescriptionsItem label="状态">{{
        stateLabels[selected.state] ?? selected.state
      }}</ElDescriptionsItem
      ><ElDescriptionsItem label="失败说明">{{ selected.error_message ?? '—' }}</ElDescriptionsItem
      ><ElDescriptionsItem label="结果保留截止">{{
        formatDateTime(selected.expires_at)
      }}</ElDescriptionsItem></ElDescriptions
    >
    <FeedbackNotice
      v-if="errors.error.value"
      :title="'错误读取失败：' + errors.error.value"
      type="error"
      :closable="false"
    />
    <ElButton
      v-if="selected && errors.error.value"
      :loading="errors.loading.value"
      @click="errors.reload()"
    >
      重试读取逐行错误
    </ElButton>
    <ElTable
      v-if="selected"
      :data="errors.data.value ?? []"
      v-loading="errors.loading.value"
      :empty-text="
        errors.error.value
          ? '逐行错误尚未读取成功'
          : errors.loading.value
            ? '正在读取逐行错误…'
            : '当前任务没有可显示的逐行错误'
      "
      ><ElTableColumn prop="row_number" label="数据行" width="100" /><ElTableColumn
        prop="field"
        label="字段"
        width="160" /><ElTableColumn prop="message" label="纠正说明" min-width="240"
    /></ElTable>
    <p v-if="selected && selected.error_rows > 200" class="muted">
      详情先展示 200 个错误行；下载错误结果文件查看完整材料。
    </p>
  </section>
</template>

<style scoped>
.foundation-jobs {
  display: flex;
  flex-direction: column;
  min-width: 0;
  gap: var(--space-md, 16px);
}
.actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-sm, 8px);
}
.actions :deep(.el-button + .el-button) {
  margin-left: 0;
}
.foundation-jobs input[type='file'] {
  min-width: 0;
  max-width: 100%;
}
.muted {
  overflow-wrap: anywhere;
}
:deep(.el-form-item__content) {
  min-width: 0;
  gap: var(--space-sm, 8px);
}
:deep(.el-pagination) {
  flex-wrap: wrap;
  row-gap: var(--space-sm, 8px);
  max-width: 100%;
}
:deep(.el-descriptions__content) {
  overflow-wrap: anywhere;
}
</style>
