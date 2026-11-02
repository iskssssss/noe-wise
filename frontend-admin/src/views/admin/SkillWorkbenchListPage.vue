<template>
  <AdminPage title="技能工作台" description="维护技能包文件、版本和发布策略。"
    ><template #extra
      ><ElButton
        v-permission.all="['foundation.capabilities.create', 'foundation.skills.create']"
        type="primary"
        @click="openCreate"
        :disabled="busy || creationUnknown || !!createdSkill"
        >新建技能</ElButton
      ></template
    ><FeedbackNotice
      v-if="error || loadError"
      :title="error || loadError"
      :closable="false"
      type="error"
    />
    <section v-if="creation.pending.value" class="admin-card" role="status">
      技能“{{ creation.pending.value?.name }}”创建结果尚未确认，原标识“{{
        creation.pending.value?.capabilityKey
      }}”，请求号：{{
        creation.pending.value?.requestId
      }}。列表缺失不代表创建失败，未查到回执仍需等待。
      <ElButton v-permission="['foundation.skills.read']" :disabled="busy" @click="checkCreation()"
        >核对创建结果</ElButton
      >
      <ElButton :disabled="busy || loading" @click="reload">查看当前技能列表</ElButton>
      <ElButton :disabled="busy" @click="open = true">查看原创建输入</ElButton>
    </section>
    <p v-if="creation.storageError.value" role="status">{{ creation.storageError.value }}</p>
    <section v-if="createdSkill" class="admin-card" role="status">
      技能及工作草稿已创建。草稿 {{ createdSkill.id }} · 修订 {{ createdSkill.revision }}。
      <RouterLink :to="'/admin/skills/' + createdSkill.definitionId"
        >进入已创建的技能工作台</RouterLink
      >
    </section>
    <div class="admin-toolbar admin-filters">
      <ElInput
        v-model="query"
        clearable
        aria-label="搜索技能"
        placeholder="搜索技能名称、标识或用途"
      />
      <ElSelect
        v-model="publicationFilter"
        clearable
        aria-label="发布状态"
        placeholder="全部发布状态"
      >
        <ElOption label="已有当前发布" value="published" />
        <ElOption label="尚未发布" value="unpublished" />
      </ElSelect>
      <ElButton :loading="loading" :disabled="busy" @click="reload">刷新</ElButton>
      <span class="admin-muted" role="status">{{
        loading ? '正在读取技能…' : data ? filtered.length + ' 项技能' : '技能列表尚未读取'
      }}</span>
    </div>
    <ElTable
      table-layout="auto"
      :data="filtered"
      v-loading="loading"
      row-key="id"
      stripe
      :empty-text="
        query || publicationFilter
          ? '没有匹配的技能，请调整筛选条件'
          : '尚无技能，创建后编辑 SKILL.md 和包文件'
      "
      ><ElTableColumn min-width="200" show-overflow-tooltip prop="name" label="技能"
        ><template #default="{ row }"
          ><RouterLink :to="'/admin/skills/' + row.id">{{ row.name }}</RouterLink></template
        ></ElTableColumn
      ><ElTableColumn label="当前发布" width="128"
        ><template #default="{ row }">
          <ElTag :type="row.defaultVersionId ? 'success' : 'info'">{{
            row.defaultVersionId ? '已发布' : '尚未发布'
          }}</ElTag>
        </template></ElTableColumn
      ><ElTableColumn
        min-width="200"
        show-overflow-tooltip
        prop="capabilityKey"
        label="标识"
      ></ElTableColumn
      ><ElTableColumn width="128" show-overflow-tooltip label="状态"
        ><template #default="{ row }">{{ statusLabel(row.status) }}</template></ElTableColumn
      ><ElTableColumn
        min-width="280"
        show-overflow-tooltip
        prop="description"
        label="说明"
      ></ElTableColumn>
      <template #empty>
        <ElEmpty class="skill-empty" :description="emptyDescription" :image-size="64">
          <ElButton v-if="!loading && loadError" @click="reload">重新加载</ElButton>
          <ElButton v-else-if="!loading && data && hasFilters" @click="clearFilters"
            >清空筛选</ElButton
          >
          <div v-else-if="!loading && data" class="skill-empty-actions">
            <RouterLink v-permission="['foundation.capabilities.read']" to="/admin/capabilities">
              <ElButton>查看已发布技能</ElButton>
            </RouterLink>
            <ElButton
              v-permission.all="['foundation.capabilities.create', 'foundation.skills.create']"
              type="primary"
              :disabled="busy || creationUnknown || !!createdSkill"
              @click="openCreate"
              >新建技能</ElButton
            >
          </div>
        </ElEmpty>
      </template> </ElTable
    ><ElDrawer
      v-model="open"
      :before-close="closeDrawer0"
      title="新建技能"
      size="680px"
      append-to-body
      direction="rtl"
      :close-on-click-modal="false"
      :close-on-press-escape="!busy"
      :show-close="!busy"
      class="admin-drawer"
      ><p v-if="creationUnknown" role="status">
        创建结果尚未确认，以下为原请求的技能标识和名称，仅供核对。
      </p>
      <ElForm
        ref="formRef"
        :model="{ key, name }"
        label-position="top"
        scroll-to-error
        @submit.prevent="create"
        :disabled="busy || creationUnknown || !!createdSkill"
        @keydown.capture="guardFormEnter"
        ><ElFormItem
          label="技能标识"
          prop="key"
          :rules="[
            { required: true, whitespace: true, message: '请输入技能标识', trigger: 'blur' }
          ]"
          ><ElInput
            v-described-by="formTipId + '-field-0'"
            :aria-describedby="formTipId + '-field-0'"
            v-model="key"
            placeholder="例如 customer-service"
          />
          <FieldTip :id="formTipId + '-field-0'"
            >已生成唯一标识；如有集成约定，可在创建前修改。</FieldTip
          > </ElFormItem
        ><ElFormItem
          label="名称"
          prop="name"
          :rules="[{ required: true, whitespace: true, message: '请输入名称', trigger: 'blur' }]"
          ><ElInput
            v-described-by="formTipId + '-field-1'"
            :aria-describedby="formTipId + '-field-1'"
            v-model="name"
          />
          <FieldTip :id="formTipId + '-field-1'">填写技能的展示名称。</FieldTip> </ElFormItem
        ><ElFormItem v-if="!creationUnknown" label="说明"
          ><ElInput
            v-described-by="formTipId + '-field-2'"
            :aria-describedby="formTipId + '-field-2'"
            v-model="description"
            type="textarea"
          />
          <FieldTip :id="formTipId + '-field-2'">描述技能用途和适用场景。</FieldTip>
        </ElFormItem></ElForm
      ><template #footer
        ><ElButton @click="closeDrawer0" :disabled="busy">取消</ElButton
        ><ElButton
          v-permission.all="['foundation.capabilities.create', 'foundation.skills.create']"
          type="primary"
          :loading="busy"
          :disabled="creationUnknown || !!createdSkill"
          @click="create"
          >创建并进入工作草稿</ElButton
        ></template
      ></ElDrawer
    ></AdminPage
  >
</template>
<script setup lang="ts">
import type { SkillDraftContent } from '@skillforge/shared/capabilities/skillDrafts'
import FeedbackNotice from '@skillforge/shared/feedback/FeedbackNotice.vue'
import { vDescribedBy } from '@skillforge/shared/directives/describedBy'

import FieldTip from '@skillforge/shared/components/FieldTip.vue'

import { message } from '@skillforge/shared/feedback'
import { guardFormEnter } from '@skillforge/shared/admin'
import { computed, onMounted, ref, useId, watch } from 'vue'
import type { FormInstance } from 'element-plus'
import { useRouter } from 'vue-router'
import {
  AdminPage,
  statusLabel,
  useTask,
  useResource,
  useDrawerClose
} from '@skillforge/shared/admin'
import { api } from '../../api'
import { useAuthStore } from '../../stores/auth'
import { useSkillCreation } from '@skillforge/shared/capabilities/skillCreation'
const formRef = ref<FormInstance>()
const router = useRouter(),
  {
    data,
    loading,
    error: loadError,
    reload
  } = useResource(() => api.capabilities.definitions('SKILL'))
onMounted(reload)
const query = ref(''),
  publicationFilter = ref('')
const hasFilters = computed(() => !!query.value.trim() || !!publicationFilter.value)
const emptyDescription = computed(() =>
  loading.value
    ? '正在读取技能…'
    : loadError.value
      ? '技能目录读取失败，请重新加载'
      : !data.value
        ? '技能列表尚未读取'
        : hasFilters.value
          ? '没有匹配的技能，请调整筛选条件'
          : '尚无技能，可在能力目录查看已发布技能，或新建技能后在工作台导入 ZIP 包'
)
function clearFilters() {
  query.value = ''
  publicationFilter.value = ''
}
const filtered = computed(() =>
  (data.value ?? []).filter(
    (item) =>
      [item.name, item.capabilityKey, item.description ?? ''].some((value) =>
        value.toLowerCase().includes(query.value.trim().toLowerCase())
      ) &&
      (!publicationFilter.value ||
        (publicationFilter.value === 'published'
          ? !!item.defaultVersionId
          : !item.defaultVersionId))
  )
)
const { busy, error, run } = useTask(),
  open = ref(false),
  name = ref(''),
  key = ref(''),
  description = ref('')
const auth = useAuthStore()
const creation = useSkillCreation(
  'institution',
  () =>
    auth.user?.sub && auth.user?.tenant_id
      ? JSON.stringify([auth.user.tenant_id, auth.user.sub])
      : '',
  () => auth.identityVersion,
  api.skillDrafts
)
const creationUnknown = computed(() => !!creation.pending.value || !!creation.storageError.value)
const createdSkill = ref<Pick<SkillDraftContent, 'id' | 'definitionId' | 'revision'>>()
watch(
  creation.pending,
  (pending) => {
    if (pending && !open.value) {
      key.value = pending.capabilityKey
      name.value = pending.name
    }
  },
  { immediate: true }
)
watch(
  () => auth.identityVersion,
  () => {
    open.value = false
    key.value = ''
    name.value = ''
    description.value = ''
    createdSkill.value = undefined
  },
  { flush: 'sync' }
)
const generatedKey = ref('')
function openCreate() {
  if (creationUnknown.value || createdSkill.value || busy.value) return
  error.value = ''
  name.value = ''
  generatedKey.value = `skill-${crypto.randomUUID()}`
  key.value = generatedKey.value
  description.value = ''
  formRef.value?.clearValidate()
  open.value = true
}
async function create() {
  if (creationUnknown.value || createdSkill.value || busy.value) return
  if (!(await formRef.value?.validate().catch(() => false))) return
  const created = await run(async () => {
    if (!name.value.trim() || !key.value.trim()) throw Error('请输入技能名称和标识')
    const draft = await creation.create({
      capabilityKey: key.value.trim(),
      name: name.value.trim(),
      description: description.value
    })
    if (!draft) return
    createdSkill.value = {
      id: draft.draft_id,
      definitionId: draft.definition_id,
      revision: draft.revision
    }
    open.value = false
    message.success('技能及工作草稿已创建')
    return draft
  })
  if (!created) return
  await navigateCreated(created.definition_id)
}
async function navigateCreated(definitionId: string) {
  try {
    const failure = await router.push('/admin/skills/' + definitionId)
    if (failure) message.warning('技能已创建，未能进入工作台。请使用原技能入口继续，不要重复创建。')
  } catch {
    message.warning('技能已创建，未能进入工作台。请使用原技能入口继续，不要重复创建。')
  }
}
async function checkCreation(automatic = false) {
  const result = await run(async () => {
    const receipt = await creation.check()
    if (!receipt) {
      if (!automatic) message.warning('尚未查到原创建回执，结果仍待核对')
      return
    }
    createdSkill.value = {
      id: receipt.draft_id,
      definitionId: receipt.definition_id,
      revision: receipt.revision
    }
    open.value = false
    message.success('技能创建已确认')
    return receipt
  })
  if (result) await navigateCreated(result.definition_id)
}
onMounted(() => {
  if (creation.pending.value && auth.user?.permissions.includes('foundation.skills.read'))
    void checkCreation(true)
})

const { requestClose: closeDrawer0 } = useDrawerClose({
  open: () => open.value,
  busy: () => busy.value,
  dirty: () =>
    open.value &&
    !creationUnknown.value &&
    !createdSkill.value &&
    (!!name.value || key.value !== generatedKey.value || !!description.value),
  close: () => {
    open.value = false
  }
})

const formTipId = useId()
</script>
<style scoped>
.skill-empty {
  padding-inline: var(--el-card-padding, 20px);
  line-height: 1.6;
}
.skill-empty :deep(.el-empty__description) {
  max-width: 34rem;
}
.skill-empty :deep(.el-empty__description p) {
  line-height: 1.6;
}
.skill-empty-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 12px;
}
</style>
