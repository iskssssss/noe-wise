<script setup lang="ts">
import FeedbackNotice from '../../feedback/FeedbackNotice.vue'
import { HttpClientError } from '../../utils/http'
import { computed, onScopeDispose, ref, useId, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import { useResource, useTask } from '../../admin/tasks'
import { formatDateTime } from '../../utils/datetime'
import { saveFoundationBlob } from './contracts'
import { useFoundationPageSize } from '../appearance'
import FieldTip from '../../components/FieldTip.vue'
import { vDescribedBy } from '../../directives/describedBy'
import type { FoundationFile, FoundationPage, FoundationRequest } from './contracts'

const props = defineProps<{ request: FoundationRequest; apiBase: string; permissions: string[] }>()
const page = ref(1)
const formTipId = useId()
const size = useFoundationPageSize()
const action = useTask()
const message = ref('')
const canUpload = computed(() => props.permissions.includes('foundation.files.upload'))
const canDelete = computed(() => props.permissions.includes('foundation.files.delete'))
const files = useResource((signal) =>
  props.request<FoundationPage<FoundationFile>>(
    'GET',
    `${props.apiBase}/files?page=${page.value}&page_size=${size.value}`,
    undefined,
    false,
    { signal, silent: true }
  )
)
const feedback = computed(() => {
  if (action.error.value) return { title: action.error.value, type: 'error' as const }
  if (files.error.value) {
    if (message.value)
      return {
        title: `${message.value}，但列表刷新失败：${files.error.value}。请刷新列表${message.value === '文件已删除' ? '，不必重复删除' : ''}。`,
        type: 'warning' as const
      }
    return { title: `文件读取失败：${files.error.value}`, type: 'error' as const }
  }
  return { title: message.value, type: 'success' as const }
})
let generation = 0

/** 身份改变后不发布旧操作结果或错误；确认框返回后必须再次确认作用域。 */
function currentAction<T>(
  operation: (active: () => boolean, request: FoundationRequest, base: string) => Promise<T>
) {
  const current = generation
  const request = props.request
  const base = props.apiBase
  return action.run(async () => {
    try {
      const result = await operation(() => current === generation, request, base)
      return current === generation ? result : undefined
    } catch (reason) {
      if (current !== generation) {
        if (reason instanceof HttpClientError) reason.claimFeedback()
        throw 'cancel'
      }
      throw reason
    }
  })
}

/** 上传只发一次，失败保留明确错误；服务器负责识别 MIME 与配额。 */
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
    message.value = `已上传 ${result.name}`
    page.value = 1
    void files.reload()
  }
}
/** 下载仍通过注入客户端认证，不构造公开存储 URL。 */
async function download(file: FoundationFile) {
  await currentAction(async (active, request, base) => {
    const blob = await request<Blob>(
      'GET',
      `${base}/files/download?file_id=${encodeURIComponent(file.id)}`,
      undefined,
      false,
      { responseType: 'blob' }
    )
    if (active()) saveFoundationBlob(blob, file.name)
  })
}
/** 确认具体文件后删除；服务器重新检查引用，冲突时刷新投影。 */
async function remove(file: FoundationFile) {
  await currentAction(async (active, request, base) => {
    await ElMessageBox.confirm(
      `删除“${file.name}”（ID：${file.id}）？仍被业务引用的文件无法删除。`,
      '删除公共文件',
      {
        type: 'warning',
        autofocus: false,
        confirmButtonText: '删除',
        cancelButtonText: '取消'
      }
    )
    if (!active()) throw 'cancel'
    await request('POST', `${base}/files/delete`, { file_id: file.id })
    if (!active()) return
    files.invalidate()
    const current = files.data.value
    if (current?.items.some((item) => item.id === file.id)) {
      const total = current.total - 1
      page.value = Math.min(page.value, Math.max(1, Math.ceil(total / size.value)))
      files.data.value = {
        ...current,
        items: current.items.filter((item) => item.id !== file.id),
        total,
        page: page.value
      }
    }
    message.value = '文件已删除'
    void files.reload()
  })
}
/** 页大小改变时只触发一次查询。 */
function resize(value: number) {
  size.value = value
  page.value = 1
  void files.reload()
}
/** 同一页面宿主身份变化时旧响应失去写权。 */
watch(
  () => [props.apiBase, props.request, props.permissions.join(',')],
  () => {
    generation++
    page.value = 1
    message.value = ''
    action.error.value = ''
    files.invalidate()
    files.data.value = undefined
    void files.reload()
  },
  { immediate: true }
)
onScopeDispose(() => {
  generation++
})
</script>

<template>
  <section class="foundation-files" aria-label="公共文件管理">
    <div class="actions">
      <label v-if="canUpload" class="upload-label"
        ><span>上传公共文件</span
        ><input
          type="file"
          :aria-describedby="formTipId + '-upload'"
          :disabled="action.busy.value"
          accept=".csv,.txt,.json,.pdf,.png,.jpg,.jpeg,.webp"
          @change="upload"
      /></label>
      <ElButton :loading="files.loading.value" @click="files.reload">刷新</ElButton>
    </div>
    <FieldTip v-if="canUpload" :id="formTipId + '-upload'">
      支持 CSV、TXT、JSON、PDF、PNG、JPEG、WebP，选中即上传。
    </FieldTip>
    <FeedbackNotice
      v-if="feedback.title"
      :title="feedback.title"
      :type="feedback.type"
      :closable="false"
    />
    <ElTable
      :data="files.data.value?.items ?? []"
      v-loading="files.loading.value"
      row-key="id"
      :empty-text="
        files.error.value
          ? '文件列表尚未读取成功'
          : files.loading.value
            ? '正在读取文件…'
            : '暂无公共文件'
      "
    >
      <ElTableColumn prop="name" label="文件名" min-width="200" show-overflow-tooltip />
      <ElTableColumn prop="mime_type" label="类型" min-width="140" />
      <ElTableColumn label="容量（字节）" width="140" align="right"
        ><template #default="{ row }">{{
          row.size_bytes.toLocaleString()
        }}</template></ElTableColumn
      >
      <ElTableColumn prop="reference_count" label="引用数" width="100" align="right" />
      <ElTableColumn label="上传时间" min-width="180"
        ><template #default="{ row }">{{ formatDateTime(row.created_at) }}</template></ElTableColumn
      >
      <ElTableColumn label="操作" width="170" fixed="right"
        ><template #default="{ row }"
          ><ElButton link :disabled="action.busy.value" @click="download(row as FoundationFile)"
            >下载</ElButton
          ><ElButton
            v-if="canDelete && row.reference_count === 0"
            link
            type="danger"
            :disabled="action.busy.value || row.reference_count > 0"
            @click="remove(row as FoundationFile)"
            >删除</ElButton
          ><span v-if="canDelete && row.reference_count > 0" class="admin-field-help"
            >使用中</span
          ></template
        ></ElTableColumn
      >
    </ElTable>
    <ElPagination
      v-described-by="formTipId + '-pagination'"
      :current-page="page"
      :page-size="size"
      :total="files.data.value?.total ?? 0"
      :page-sizes="[10, 20, 50, 100]"
      layout="total, sizes, prev, pager, next"
      @current-change="
        (value: number) => {
          page = value
          files.reload()
        }
      "
      @size-change="resize"
    />
    <FieldTip :id="formTipId + '-pagination'">
      条数仅对当前列表生效。使用中的文件不能删除；孤立文件至少保留 24 小时。
    </FieldTip>
  </section>
</template>

<style scoped>
.foundation-files {
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
.upload-label {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  min-width: 0;
  max-width: 100%;
  gap: var(--space-sm, 8px);
}
.upload-label > span {
  white-space: nowrap;
}
.upload-label input {
  min-width: 0;
  max-width: 100%;
}
:deep(.el-pagination) {
  flex-wrap: wrap;
  row-gap: var(--space-sm, 8px);
  max-width: 100%;
}
</style>
