<template>
  <AdminPage title="知识库" description="维护机构知识内容与检索配置。"
    ><template #extra
      ><ElButton
        v-permission="['foundation.knowledge.create']"
        type="primary"
        @click="edit()"
        :disabled="busy || !!creationUnknown || !!configurationUnknown || !!creationStorageError"
        >新建知识库</ElButton
      ></template
    ><FeedbackNotice
      v-if="error || loadError"
      :title="error || loadError"
      :closable="false"
      type="error"
    />
    <section v-if="creationUnknown" class="admin-card" role="status">
      知识库“{{ creationUnknown }}”的创建结果尚未确认，请求号：{{
        createRequestId
      }}。查不到回执不代表创建失败。
      <ElButton :disabled="busy || !canRead" @click="checkCreation()">核对创建结果</ElButton>
      <ElButton v-if="creationInputAvailable" :disabled="busy" @click="open = true"
        >查看原创建输入</ElButton
      >
    </section>
    <p v-if="creationStorageError" role="status">{{ creationStorageError }}</p>
    <section v-if="configurationUnknown" class="admin-card" role="status">
      知识库“{{ configurationUnknown.name }}”（标识：{{
        configurationUnknown.id
      }}）的配置保存结果尚未确认。
      请保留原输入并联系管理员核对原请求，当前页面不会再次写入；读取当前配置不能证明原请求已经结束。
      <ElButton :disabled="busy" @click="open = true">查看原配置输入</ElButton>
    </section>
    <section v-if="createdKnowledge" class="admin-card" role="status">
      知识库“{{ createdKnowledge.name }}”已创建，标识：{{ createdKnowledge.id }}。
      <RouterLink v-if="canRead" :to="'/admin/knowledge/' + createdKnowledge.id"
        >进入知识库维护文档</RouterLink
      >
      <span v-else>当前账号没有知识库读取权限，请联系管理员授权后维护文档。</span>
      <p v-if="createdInitialFile">
        文件“{{ createdInitialFile.name }}”尚未上传，请进入此知识库继续。
      </p>
    </section>
    <div class="admin-toolbar admin-filters">
      <ElInput v-model="query" clearable aria-label="搜索知识库" placeholder="搜索名称或说明" />
      <ElButton :loading="loading" :disabled="busy" @click="reload">刷新</ElButton>
      <span v-if="data" class="admin-muted" role="status"
        >{{ filtered.length }} 个知识库 · 点击名称维护文档</span
      >
    </div>
    <p v-if="loadError && data" class="admin-muted" role="status">
      知识库列表刷新失败，以下保留上次读取的结果。
    </p>
    <ElTable
      table-layout="auto"
      :data="filtered"
      v-loading="loading"
      row-key="id"
      stripe
      :empty-text="
        loading && !data
          ? '正在读取知识库…'
          : loadError && !data
            ? '知识库列表读取失败，请点击刷新重试'
            : query
              ? '没有匹配的知识库，请调整关键词'
              : '尚无知识库，创建后上传文档并验证检索效果'
      "
      ><ElTableColumn min-width="200" show-overflow-tooltip prop="name" label="知识库"
        ><template #default="{ row }"
          ><RouterLink :to="'/admin/knowledge/' + row.id">{{ row.name }}</RouterLink></template
        ></ElTableColumn
      ><ElTableColumn
        min-width="280"
        show-overflow-tooltip
        prop="description"
        label="说明"
      ></ElTableColumn
      ><ElTableColumn width="96" prop="document_count" label="文档"></ElTableColumn
      ><ElTableColumn width="96" prop="chunk_count" label="分块"></ElTableColumn
      ><ElTableColumn width="88" prop="status" label="状态"
        ><template #default="{ row }">{{
          row.status === 'archived' ? '已归档' : '启用'
        }}</template></ElTableColumn
      ><ElTableColumn width="184" class-name="admin-table-actions" prop="actions" label="操作"
        ><template #default="{ row }"
          ><ElButton
            v-permission="['foundation.knowledge.update']"
            link
            @click="edit(row as KnowledgeBase)"
            :disabled="busy || !!creationUnknown || !!configurationUnknown"
            >检索配置</ElButton
          ><ElButton
            v-permission="['foundation.knowledge.update']"
            link
            @click="toggleArchive(row as KnowledgeBase)"
            :disabled="busy || !!creationUnknown || !!configurationUnknown"
            >{{ row.status === 'archived' ? '启用' : '归档' }}</ElButton
          ><ElButton
            v-permission="['foundation.knowledge.delete']"
            link
            type="danger"
            @click="remove(row as KnowledgeBase)"
            :disabled="busy || !!creationUnknown || !!configurationUnknown || !row.can_delete"
            :title="!row.can_delete ? '已有文档来源的知识库不能硬删除，请使用归档' : undefined"
            >删除</ElButton
          ></template
        ></ElTableColumn
      ></ElTable
    ><ElDrawer
      v-model="open"
      :before-close="closeDrawer0"
      :title="editing ? '知识库配置' : '新建知识库'"
      size="680px"
      append-to-body
      direction="rtl"
      :close-on-click-modal="false"
      :close-on-press-escape="!busy"
      :show-close="!busy"
      class="admin-drawer"
    >
      <div v-if="creationUnknown" role="status">
        <p>创建结果待核对，请求号：{{ createRequestId }}。</p>
        <ElButton :disabled="busy || !canRead" @click="checkCreation()">核对创建结果</ElButton>
      </div>
      <ElForm
        ref="formRef"
        :model="form"
        label-position="top"
        @submit.prevent="save"
        :disabled="busy || !!creationUnknown || !!configurationUnknown || (!!editing && !canUpdate)"
        scroll-to-error
        @keydown.capture="guardFormEnter"
        ><ElAlert
          v-if="!editing"
          title="推荐使用平台当前默认设置，通常只需填写知识库名称。分块和检索参数可按需自定义。"
          type="info"
          :closable="false"
        /><ElFormItem label="名称" prop="name" :rules="[{ required: true, message: '请输入名称' }]"
          ><ElInput
            v-described-by="formTipId + '-field-0'"
            :aria-describedby="formTipId + '-field-0'"
            v-model="form.name"
          />
          <FieldTip :id="formTipId + '-field-0'">为知识库填写易识别的名称。</FieldTip> </ElFormItem
        ><ElFormItem label="说明"
          ><ElInput
            v-described-by="formTipId + '-field-1'"
            :aria-describedby="formTipId + '-field-1'"
            v-model="form.description"
            type="textarea"
          />
          <FieldTip :id="formTipId + '-field-1'">说明知识库内容和适用范围。</FieldTip>
        </ElFormItem>
        <ElFormItem v-if="!editing && canRead" label="首个文件（可选）">
          <input
            ref="initialInput"
            type="file"
            aria-label="首个文件（可选）"
            :disabled="busy || !!creationUnknown || !!configurationUnknown"
            :aria-describedby="formTipId + '-initial-file'"
            @change="selectInitialFile"
          />
          <FieldTip :id="formTipId + '-initial-file'"
            >创建成功后自动上传；创建结果未知时需先核对，刷新页面后需重新选择文件。</FieldTip
          >
        </ElFormItem>
        <ElFormItem label="索引方式">
          <ElSelect
            v-if="!editing"
            v-model="form.indexing_mode"
            clearable
            :disabled="busy || !!creationUnknown || !!configurationUnknown"
            placeholder="使用平台当前默认索引方式"
            v-described-by="formTipId + '-indexing'"
            :aria-describedby="formTipId + '-indexing'"
          >
            <ElOption value="high_quality" label="高质量索引" />
            <ElOption value="economical" label="经济索引" />
          </ElSelect>
          <span v-else>{{
            form.indexing_mode === 'economical'
              ? '经济索引'
              : form.indexing_mode === 'high_quality'
                ? '高质量索引'
                : '保留当前索引方式'
          }}</span>
          <FieldTip :id="formTipId + '-indexing'">
            {{
              economical
                ? '经济索引只使用全文检索，不需要 Embedding 模型，不建立向量，也不使用向量权重、分数阈值或重排序。'
                : editing
                  ? '此处保留建库时的索引语义，不执行索引方式切换。'
                  : '留空继承服务端当前默认设置；高质量索引需要可用的 Embedding 模型，经济索引无需 Embedding。'
            }}
          </FieldTip>
        </ElFormItem>
        <p v-if="!canUpdate" class="admin-muted" role="status">
          自定义分块、召回条数和阈值需要更新权限；当前账号可选择索引、检索和重排方式后创建知识库。
        </p>
        <ElCollapse
          ><ElCollapseItem title="自定义分块和检索（可选）" name="settings"
            ><p class="admin-muted">未填写的参数保留服务端默认或当前配置，不会写入前端推荐数字。</p>
            <ElDivider content-position="left">文档分块</ElDivider>
            <div class="admin-form-grid">
              <ElFormItem label="分块大小"
                ><ElInputNumber
                  v-numeric-disabled
                  v-described-by="formTipId + '-field-2'"
                  :aria-describedby="formTipId + '-field-2'"
                  v-model="form.chunk_size"
                  :disabled="busy || !!creationUnknown || !!configurationUnknown || !canUpdate"
                  :min="1"
                  :precision="0"
                />
                <FieldTip :id="formTipId + '-field-2'"
                  >每个分块的目标字符数，填写正整数；{{
                    editing ? '留空不修改已保存设置。' : '留空使用平台当前默认值。'
                  }}</FieldTip
                > </ElFormItem
              ><ElFormItem label="重叠长度"
                ><ElInputNumber
                  v-numeric-disabled
                  v-described-by="formTipId + '-field-3'"
                  :aria-describedby="formTipId + '-field-3'"
                  v-model="form.chunk_overlap"
                  :disabled="busy || !!creationUnknown || !!configurationUnknown || !canUpdate"
                  :min="0"
                  :max="
                    form.chunk_size === undefined ? undefined : Math.max(0, form.chunk_size - 1)
                  "
                  :precision="0"
                />
                <FieldTip :id="formTipId + '-field-3'"
                  >相邻分块重复保留的字符数，必须小于分块大小；{{
                    editing ? '留空不修改已保存设置。' : '留空使用平台当前默认值。'
                  }}</FieldTip
                >
              </ElFormItem>
            </div>
            <ElDivider content-position="left">检索效果</ElDivider>
            <div class="admin-form-grid">
              <ElFormItem label="检索方式"
                ><ElSelect
                  v-described-by="formTipId + '-field-4'"
                  :aria-describedby="formTipId + '-field-4'"
                  :model-value="economical ? 'full_text' : form.retrieval_mode"
                  @update:model-value="form.retrieval_mode = $event || undefined"
                  :disabled="
                    busy ||
                    !!creationUnknown ||
                    !!configurationUnknown ||
                    (!!editing && !canUpdate) ||
                    economical
                  "
                  clearable
                  :placeholder="editing ? '保留当前设置' : '使用平台当前默认方式'"
                  ><ElOption value="semantic" label="语义检索" /><ElOption
                    value="full_text"
                    label="全文检索" /><ElOption value="hybrid" label="混合检索"
                /></ElSelect>
                <FieldTip :id="formTipId + '-field-4'"
                  >选择语义、全文或混合检索；不同方式依赖相应索引。</FieldTip
                > </ElFormItem
              ><ElFormItem label="召回条数"
                ><ElInputNumber
                  v-numeric-disabled
                  v-described-by="formTipId + '-field-5'"
                  :aria-describedby="formTipId + '-field-5'"
                  v-model="form.top_k"
                  :disabled="busy || !!creationUnknown || !!configurationUnknown || !canUpdate"
                  :min="1"
                  :precision="0"
                />
                <FieldTip :id="formTipId + '-field-5'"
                  >检索时返回的结果条数，填写正整数；{{
                    editing ? '留空不修改已保存设置。' : '留空使用平台当前默认值。'
                  }}</FieldTip
                > </ElFormItem
              ><ElFormItem label="分数阈值"
                ><ElSwitch
                  v-described-by="formTipId + '-field-6'"
                  :aria-describedby="formTipId + '-field-6'"
                  :model-value="!economical && (form.score_threshold_enabled ?? false)"
                  :disabled="
                    busy || !!creationUnknown || !!configurationUnknown || !canUpdate || economical
                  "
                  @update:model-value="form.score_threshold_enabled = Boolean($event)"
                /><ElInputNumber
                  v-described-by="formTipId + '-field-6'"
                  :aria-describedby="formTipId + '-field-6'"
                  v-if="!economical && form.score_threshold_enabled"
                  v-model="form.score_threshold"
                  :disabled="busy || !!creationUnknown || !!configurationUnknown || !canUpdate"
                  :min="0"
                  :max="1"
                  :step="0.1"
                />
                <FieldTip :id="formTipId + '-field-6'"
                  >启用后按 0–1 的相关度阈值过滤结果；关闭时不使用该阈值。</FieldTip
                > </ElFormItem
              ><ElFormItem v-if="!economical && form.retrieval_mode === 'hybrid'" label="向量权重"
                ><ElInputNumber
                  v-described-by="formTipId + '-field-7'"
                  :aria-describedby="formTipId + '-field-7'"
                  v-model="form.vector_weight"
                  :disabled="busy || !!creationUnknown || !!configurationUnknown || !canUpdate"
                  :min="0"
                  :max="1"
                  :step="0.1"
                />
                <FieldTip :id="formTipId + '-field-7'"
                  >混合检索中的向量侧权重，范围为 0–1；其余权重归全文侧。</FieldTip
                > </ElFormItem
              ><ElFormItem label="重排序"
                ><ElSwitch
                  v-described-by="formTipId + '-field-8'"
                  :aria-describedby="formTipId + '-field-8'"
                  :model-value="!economical && (form.rerank_enabled ?? false)"
                  :disabled="
                    busy ||
                    !!creationUnknown ||
                    !!configurationUnknown ||
                    (!!editing && !canUpdate) ||
                    economical
                  "
                  @update:model-value="form.rerank_enabled = Boolean($event)"
                />
                <FieldTip :id="formTipId + '-field-8'"
                  >启用后使用重排序模型对召回结果重新排序，需有可用重排模型。</FieldTip
                >
              </ElFormItem>
            </div></ElCollapseItem
          ></ElCollapse
        ></ElForm
      ><template #footer
        ><ElButton @click="closeDrawer0" :disabled="busy">取消</ElButton
        ><ElButton
          v-permission.all="savePermissions"
          type="primary"
          :loading="busy"
          :disabled="
            !canSave || !!creationUnknown || !!configurationUnknown || !!creationStorageError
          "
          @click="save"
          >保存</ElButton
        ></template
      ></ElDrawer
    ></AdminPage
  >
</template>
<script setup lang="ts">
import FeedbackNotice from '@skillforge/shared/feedback/FeedbackNotice.vue'
import { vDescribedBy } from '@skillforge/shared/directives/describedBy'

import FieldTip from '@skillforge/shared/components/FieldTip.vue'

import { guardFormEnter } from '@skillforge/shared/admin'
import { HttpClientError } from '@skillforge/shared/utils/http'
import { useRouter } from 'vue-router'
import {
  computed,
  onMounted,
  onScopeDispose,
  reactive,
  ref,
  useId,
  watch,
  type ObjectDirective
} from 'vue'
import type { FormInstance } from 'element-plus'
import {
  AdminPage,
  useTask,
  useResource,
  useUnsavedChanges,
  useDrawerClose
} from '@skillforge/shared/admin'
import { confirm, message } from '@skillforge/shared/feedback'
import { api, type KnowledgeBase } from '../../api'
import { useAuthStore } from '../../stores/auth'
import { peekInitialFile, prepareInitialFile, takeInitialFile } from './knowledgeInitialFile'
const auth = useAuthStore()
const router = useRouter()
const canRead = computed(
  () => auth.user?.permissions.includes('foundation.knowledge.read') === true
)
const creationUnknown = ref('')
const creationInputAvailable = ref(false)
const createRequestId = ref('')
const creationStorageError = ref('')
const owner = computed(() =>
  auth.user?.tenant_id && auth.user?.sub ? JSON.stringify([auth.user.tenant_id, auth.user.sub]) : ''
)
const createStorageKey = (subject: string) => 'skillforge.knowledge-create:' + subject
let disposed = false
onScopeDispose(() => {
  disposed = true
})
watch(
  owner,
  (subject) => {
    creationUnknown.value = ''
    creationInputAvailable.value = false
    createRequestId.value = ''
    creationStorageError.value = ''
    if (!subject) return
    try {
      const raw = sessionStorage.getItem(createStorageKey(subject))
      if (!raw) return
      const record = JSON.parse(raw) as { requestId: string; name: string }
      if (
        !record ||
        typeof record.name !== 'string' ||
        !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(record.requestId)
      )
        throw Error('invalid')
      createRequestId.value = record.requestId
      creationUnknown.value = record.name
    } catch {
      creationStorageError.value = '无法读取原知识库创建记录，请恢复浏览器存储后重新进入。'
    }
  },
  { immediate: true, flush: 'sync' }
)
const configurationUnknown = ref<{ id: string; name: string }>()
const isKnowledgeWriteRejection = (cause: unknown) =>
  cause instanceof HttpClientError && cause.code === 'KNOWLEDGE_WRITE_REJECTED'
const createdKnowledge = ref<{ id: string; name: string }>()
const initialFile = ref<File>()
const initialInput = ref<HTMLInputElement>()
const createdInitialFile = computed(() =>
  createdKnowledge.value
    ? peekInitialFile(owner.value, auth.identityVersion, createdKnowledge.value.id)
    : undefined
)
watch(
  () => auth.identityVersion,
  () => {
    initialFile.value = undefined
  }
)
function selectInitialFile(event: Event) {
  initialFile.value = (event.target as HTMLInputElement).files?.[0]
}
const canCreate = computed(
  () => auth.user?.permissions.includes('foundation.knowledge.create') === true
)
const canUpdate = computed(
  () => auth.user?.permissions.includes('foundation.knowledge.update') === true
)
const { data, loading, error: loadError, reload } = useResource(() => api.knowledge.listBases())
const query = ref('')
const filtered = computed(() =>
  (data.value?.items ?? []).filter((item) =>
    (item.name + (item.description ?? '')).toLowerCase().includes(query.value.trim().toLowerCase())
  )
)
onMounted(async () => {
  await reload()
  if (!disposed && creationUnknown.value && canRead.value) await checkCreation(true)
})
const { busy, error, run } = useTask()
/** 数字输入在 busy 切换后仍按真实 native disabled 更新 ARIA，保留原控件和焦点身份。 */
function syncNumericDisabled(root: HTMLElement) {
  const input = root.querySelector<HTMLInputElement>('input[type="number"]')
  if (input) input.setAttribute('aria-disabled', String(input.disabled))
}
/** 仅补三个整数控件的状态同步；Element Plus 当前只在首次挂载写 aria-disabled。 */
const vNumericDisabled: ObjectDirective<HTMLElement> = {
  mounted: syncNumericDisabled,
  updated: syncNumericDisabled
}
const open = ref(false),
  editing = ref<string>(),
  saved = ref(''),
  formRef = ref<FormInstance>()
type KnowledgeSettingsForm = {
  name: string
  description: string
  indexing_mode?: string
  chunk_size?: number
  chunk_overlap?: number
  retrieval_mode?: string
  top_k?: number
  score_threshold_enabled?: boolean
  score_threshold?: number
  vector_weight?: number
  rerank_enabled?: boolean
}
const form = reactive<KnowledgeSettingsForm>({
  name: '',
  description: ''
})
const economical = computed(() => form.indexing_mode === 'economical')
const retrievalSettings = computed(() =>
  economical.value
    ? { retrieval_mode: 'full_text', rerank_enabled: false }
    : {
        ...(form.retrieval_mode ? { retrieval_mode: form.retrieval_mode } : {}),
        ...(form.rerank_enabled !== undefined ? { rerank_enabled: form.rerank_enabled } : {})
      }
)
const advancedSettings = computed(() =>
  Object.fromEntries(
    Object.entries(form).filter(
      ([key, value]) =>
        !['name', 'description', 'indexing_mode', 'retrieval_mode', 'rerank_enabled'].includes(
          key
        ) &&
        value !== undefined &&
        (!economical.value ||
          !['score_threshold_enabled', 'score_threshold', 'vector_weight'].includes(key))
    )
  )
)
const customSettings = computed(() => ({ ...advancedSettings.value, ...retrievalSettings.value }))
const savePermissions = computed(() =>
  editing.value
    ? ['foundation.knowledge.update']
    : Object.keys(advancedSettings.value).length
      ? ['foundation.knowledge.create', 'foundation.knowledge.update']
      : ['foundation.knowledge.create']
)
const canSave = computed(() =>
  savePermissions.value.every((permission) => auth.user?.permissions.includes(permission))
)
const dirty = computed(
  () => open.value && (JSON.stringify(form) !== saved.value || !!initialFile.value)
)
useUnsavedChanges({
  dirty: () => dirty.value || !!creationUnknown.value || !!configurationUnknown.value,
  busy: () => busy.value,
  leaveMessage: () =>
    creationUnknown.value
      ? `知识库“${creationUnknown.value}”的创建结果尚未确认，请保留请求号 ${createRequestId.value} 并核对原请求。`
      : configurationUnknown.value
        ? `知识库“${configurationUnknown.value.name}”（标识：${configurationUnknown.value.id}）的配置保存结果尚未确认。离开后页面不再保留原输入，请先记录并联系管理员核对。`
        : undefined
})
async function edit(row?: KnowledgeBase) {
  await run(async () => {
    if (configurationUnknown.value || creationUnknown.value || creationStorageError.value) return
    if (row ? !canUpdate.value : !canCreate.value)
      throw Error(row ? '当前账号没有更新知识库权限' : '当前账号没有创建知识库权限')
    if (createdInitialFile.value && createdKnowledge.value) {
      if (
        !(await confirm({
          title: '放弃待上传文件',
          content: `文件“${createdInitialFile.value.name}”尚未上传，继续将放弃当前文件选择。`,
          confirmText: '放弃并继续',
          cancelText: '返回原知识库'
        }))
      )
        return
      takeInitialFile(owner.value, auth.identityVersion, createdKnowledge.value.id)
    }
    createdKnowledge.value = undefined
    initialFile.value = undefined
    if (initialInput.value) initialInput.value.value = ''
    const detail = row ? await api.knowledge.getBase(row.id) : undefined
    editing.value = row?.id
    Object.assign(form, {
      name: detail?.name ?? '',
      description: detail?.description ?? '',
      indexing_mode: detail?.indexing_mode ?? undefined,
      chunk_size: detail?.chunk_size ?? undefined,
      chunk_overlap: detail?.chunk_overlap ?? undefined,
      retrieval_mode: detail?.retrieval_mode ?? undefined,
      top_k: detail?.top_k ?? undefined,
      score_threshold_enabled: detail?.score_threshold_enabled ?? undefined,
      score_threshold: detail?.score_threshold ?? undefined,
      vector_weight: detail?.vector_weight ?? undefined,
      rerank_enabled: detail?.rerank_enabled ?? undefined
    })
    saved.value = JSON.stringify(form)
    open.value = true
  })
}
async function save() {
  const destination = await run(async () => {
    if (creationUnknown.value || configurationUnknown.value || creationStorageError.value) return
    if (!(await formRef.value?.validate().catch(() => false))) return
    if (!canSave.value)
      throw Error(
        !canUpdate.value && (editing.value || Object.keys(advancedSettings.value).length)
          ? '当前账号没有更新知识库权限，无法保存自定义配置'
          : '当前账号没有创建知识库权限'
      )
    if (
      form.chunk_overlap !== undefined &&
      form.chunk_size !== undefined &&
      form.chunk_overlap >= form.chunk_size
    )
      throw Error('重叠长度必须小于分块大小')
    const isNew = !editing.value
    if (isNew) {
      if (!owner.value) throw Error('当前身份不可用，请重新登录')
      const subject = owner.value,
        requestId = crypto.randomUUID(),
        name = form.name,
        file = initialFile.value,
        identity = auth.identityVersion
      try {
        sessionStorage.setItem(createStorageKey(subject), JSON.stringify({ requestId, name }))
      } catch {
        creationStorageError.value = '无法保存创建记录，请恢复浏览器存储后重试。'
        return
      }
      createRequestId.value = requestId
      try {
        const created = await api.knowledge.createWithReceipt(requestId, {
          name: form.name,
          description: form.description,
          ...(form.indexing_mode ? { indexing_mode: form.indexing_mode } : {}),
          ...retrievalSettings.value,
          ...advancedSettings.value
        })
        if (disposed || owner.value !== subject) return
        if (
          created?.request_id !== requestId ||
          typeof created.kb_id !== 'string' ||
          !created.kb_id
        )
          throw Error('创建回执缺少知识库标识，请核对原请求')
        sessionStorage.removeItem(createStorageKey(subject))
        createRequestId.value = ''
        editing.value = created.kb_id
        createdKnowledge.value = { id: created.kb_id, name }
        if (file && canRead.value && canCreate.value && auth.identityVersion === identity)
          prepareInitialFile(subject, identity, created.kb_id, file)
      } catch (cause) {
        if (!disposed && owner.value === subject) {
          if (isKnowledgeWriteRejection(cause)) {
            try {
              sessionStorage.removeItem(createStorageKey(subject))
              createRequestId.value = ''
            } catch {
              creationStorageError.value =
                '创建已被拒绝，但无法清理本地请求记录，请恢复浏览器存储后重新进入。'
            }
          } else {
            creationUnknown.value = name
            creationInputAvailable.value = true
          }
        }
        throw cause
      }
    }
    if (!isNew) {
      try {
        await api.knowledge.updateBase(editing.value!, {
          name: form.name,
          description: form.description,
          ...customSettings.value
        })
      } catch (cause) {
        if (!isKnowledgeWriteRejection(cause))
          configurationUnknown.value = { id: editing.value!, name: form.name }
        throw cause
      }
    }
    saved.value = JSON.stringify(form)
    initialFile.value = undefined
    open.value = false
    if (createdKnowledge.value?.id === editing.value && canRead.value) {
      message.success('知识库已创建')
      return '/admin/knowledge/' + encodeURIComponent(editing.value!)
    } else {
      if (canRead.value) await reload()
      message.success(isNew ? '知识库已创建' : '知识库已保存')
    }
  })
  if (destination) await navigateToCreated(destination)
}
async function checkCreation(automatic = false) {
  const destination = await run(async () => {
    const subject = owner.value,
      requestId = createRequestId.value,
      name = creationUnknown.value,
      identity = auth.identityVersion
    if (!subject || !requestId || !canRead.value) return
    const result = await api.knowledge.createReceipt(requestId)
    if (
      disposed ||
      owner.value !== subject ||
      auth.identityVersion !== identity ||
      createRequestId.value !== requestId
    )
      return
    if (
      !result ||
      result.request_id !== requestId ||
      typeof result.kb_id !== 'string' ||
      !result.kb_id
    ) {
      if (!automatic) message.warning('尚未查到原创建回执，结果仍待核对')
      return
    }
    sessionStorage.removeItem(createStorageKey(subject))
    createRequestId.value = ''
    creationUnknown.value = ''
    creationInputAvailable.value = false
    createdKnowledge.value = { id: result.kb_id, name }
    if (initialFile.value && canCreate.value)
      prepareInitialFile(subject, identity, result.kb_id, initialFile.value)
    initialFile.value = undefined
    saved.value = JSON.stringify(form)
    open.value = false
    message.success('知识库创建已确认')
    return '/admin/knowledge/' + encodeURIComponent(result.kb_id)
  })
  if (destination) await navigateToCreated(destination)
}
async function navigateToCreated(destination: string) {
  try {
    const failure = await router.push(destination)
    if (failure) message.warning('知识库已创建，未能进入详情，请使用页面入口继续。')
  } catch {
    message.warning('知识库已创建，未能进入详情，请使用页面入口继续。')
  }
}
async function toggleArchive(row: KnowledgeBase) {
  const archive = row.status !== 'archived'
  if (
    archive &&
    !(await confirm({
      title: '归档知识库',
      confirmText: '归档',
      content:
        '确定归档“' + row.name + '”？归档后不再参与检索，已发布来源、文档和分块仍保留，可再次启用。'
    }))
  )
    return
  await run(async () => {
    await api.knowledge.updateBase(row.id, { status: archive ? 'archived' : 'active' })
    message.success(archive ? '知识库已归档' : '知识库已启用')
    await reload()
  })
}
async function remove(row: KnowledgeBase) {
  if (!row.can_delete) {
    message.warning('已有文档来源的知识库不能硬删除，请使用归档')
    return
  }
  if (
    await confirm({
      title: '删除知识库',
      confirmText: '删除知识库',
      danger: true,
      content: '确定删除空知识库“' + row.name + '”？'
    })
  )
    await run(async () => {
      await api.knowledge.removeBase(row.id)
      message.success('知识库已删除')
      await reload()
    })
}

const { requestClose: closeDrawer0 } = useDrawerClose({
  open: () => open.value,
  busy: () => busy.value,
  dirty: () => open.value && dirty.value,
  close: () => {
    open.value = false
  }
})

const formTipId = useId()
</script>
