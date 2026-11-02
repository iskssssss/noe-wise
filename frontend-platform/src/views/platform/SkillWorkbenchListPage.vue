<script setup lang="ts">
import FeedbackNotice from '@skillforge/shared/feedback/FeedbackNotice.vue'
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import type { FormInstance } from 'element-plus'
import type { SkillDraftContent } from '@skillforge/shared/capabilities/skillDrafts'
import { message } from '@skillforge/shared/feedback'
import {
  AdminPage,
  statusLabel,
  useTask,
  useDrawerClose,
  guardFormEnter
} from '@skillforge/shared/admin'
import { api, type SkillCatalogItem } from '../../api'
import { useAuthStore } from '../../stores/auth'
import { useSkillCreation } from '@skillforge/shared/capabilities/skillCreation'
const loaded = ref(false)
const load = useTask(),
  rows = ref<SkillCatalogItem[]>([])
const search = ref('')
const filtered = computed(() =>
  rows.value.filter((row) =>
    [row.name, row.capabilityKey, row.description ?? ''].some((value) =>
      value.toLowerCase().includes(search.value.trim().toLowerCase())
    )
  )
)
const hasFilters = computed(() => !!search.value.trim())
const emptyDescription = computed(() =>
  load.busy.value
    ? '正在读取技能…'
    : load.error.value
      ? '技能目录读取失败，请重新加载'
      : !loaded.value
        ? '技能列表尚未读取'
        : hasFilters.value
          ? '没有匹配的技能，请调整筛选条件'
          : '尚无技能，新建后直接编辑工作草稿，也可随后导入 ZIP 包'
)
function reload() {
  void load.run(async () => {
    rows.value = await api.capabilities.listSkillCatalog()
    loaded.value = true
  })
}
const router = useRouter()
const createTask = useTask(),
  createOpen = ref(false),
  formRef = ref<FormInstance>()
const key = ref(''),
  name = ref(''),
  description = ref('')
const auth = useAuthStore()
const creation = useSkillCreation(
  'platform',
  () => auth.user?.sub ?? '',
  () => auth.identityVersion,
  api.skillDrafts
)
const creationUnknown = computed(() => !!creation.pending.value || !!creation.storageError.value)
const createdSkill = ref<Pick<SkillDraftContent, 'id' | 'definitionId' | 'revision'>>()
watch(
  creation.pending,
  (pending) => {
    if (pending && !createOpen.value) {
      key.value = pending.capabilityKey
      name.value = pending.name
    }
  },
  { immediate: true }
)
watch(
  () => auth.identityVersion,
  () => {
    createOpen.value = false
    key.value = ''
    name.value = ''
    description.value = ''
    createdSkill.value = undefined
  },
  { flush: 'sync' }
)
const generatedKey = ref('')
const pageError = computed(() =>
  [load.error.value, createTask.error.value].filter(Boolean).join('；')
)
function openCreate() {
  if (createTask.busy.value || creationUnknown.value || createdSkill.value) return
  createTask.error.value = ''
  generatedKey.value = `skill-${crypto.randomUUID()}`
  key.value = generatedKey.value
  name.value = ''
  description.value = ''
  formRef.value?.clearValidate()
  createOpen.value = true
}
async function create() {
  if (createTask.busy.value || creationUnknown.value || createdSkill.value) return
  if (!(await formRef.value?.validate().catch(() => false))) return
  const created = await createTask.run(async () => {
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
    createOpen.value = false
    message.success('技能及工作草稿已创建')
    return draft
  })
  if (!created) return
  await navigateCreated(created.definition_id)
}
async function navigateCreated(definitionId: string) {
  try {
    const failure = await router.push('/platform/skills/' + definitionId)
    if (failure) message.warning('技能已创建，未能进入工作台。请使用原技能入口继续，不要重复创建。')
  } catch {
    message.warning('技能已创建，未能进入工作台。请使用原技能入口继续，不要重复创建。')
  }
}
async function checkCreation(automatic = false) {
  const result = await createTask.run(async () => {
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
    createOpen.value = false
    message.success('技能创建已确认')
    return receipt
  })
  if (result) await navigateCreated(result.definition_id)
}
onMounted(() => {
  if (creation.pending.value && auth.user?.permissions.includes('foundation.skills.read'))
    void checkCreation(true)
})
const { requestClose: closeCreate } = useDrawerClose({
  open: () => createOpen.value,
  busy: () => createTask.busy.value,
  dirty: () =>
    createOpen.value &&
    !creationUnknown.value &&
    !createdSkill.value &&
    !!(key.value !== generatedKey.value || name.value || description.value),
  close: () => {
    createOpen.value = false
  }
})
onMounted(reload)
</script>

<template>
  <AdminPage title="Skill 工作台" description="以文件工作台编辑、校验和发布 Skill 包">
    <template #extra>
      <ElButton
        v-permission.all="['foundation.capabilities.create', 'foundation.skills.create']"
        type="primary"
        :disabled="createTask.busy.value || creationUnknown || !!createdSkill"
        @click="openCreate"
        >新建技能</ElButton
      >
      <RouterLink v-permission="['foundation.capabilities.read']" to="/platform/capabilities">
        <ElButton>管理能力目录</ElButton>
      </RouterLink>
      <ElButton :loading="load.busy.value" @click="reload">刷新</ElButton>
    </template>
    <section v-if="creation.pending.value" class="admin-card" role="status">
      技能“{{ creation.pending.value?.name }}”创建结果尚未确认，原标识“{{
        creation.pending.value?.capabilityKey
      }}”，请求号：{{
        creation.pending.value?.requestId
      }}。列表缺失不代表创建失败，未查到回执仍需等待。
      <ElButton
        v-permission="['foundation.skills.read']"
        :disabled="createTask.busy.value"
        @click="checkCreation()"
        >核对创建结果</ElButton
      >
      <ElButton :disabled="createTask.busy.value || load.busy.value" @click="reload"
        >查看当前技能列表</ElButton
      >
      <ElButton :disabled="createTask.busy.value" @click="createOpen = true"
        >查看原创建输入</ElButton
      >
    </section>
    <p v-if="creation.storageError.value" role="status">{{ creation.storageError.value }}</p>
    <section v-if="createdSkill" class="admin-card" role="status">
      技能及工作草稿已创建。草稿 {{ createdSkill.id }} · 修订 {{ createdSkill.revision }}。
      <RouterLink :to="'/platform/skills/' + createdSkill.definitionId"
        >进入已创建的技能工作台</RouterLink
      >
    </section>
    <div class="admin-toolbar">
      <div class="admin-filters">
        <ElInput
          v-model="search"
          aria-label="搜索技能"
          placeholder="搜索技能名称、标识或用途"
          clearable
        />
      </div>
      <span class="admin-muted" role="status">{{
        loaded
          ? filtered.length + ' 个技能'
          : load.busy.value
            ? '正在读取技能…'
            : '技能列表尚未读取'
      }}</span>
    </div>
    <FeedbackNotice v-if="pageError" :title="pageError" :closable="false" type="error" />
    <ElTable
      table-layout="auto"
      :empty-text="
        load.busy.value
          ? '正在读取技能…'
          : load.error.value
            ? '技能目录尚未读取，请查看上方错误；可点击“刷新”重新读取'
            : '暂无符合条件的技能'
      "
      :data="filtered"
      v-loading="load.busy.value"
      style="width: 100%"
    >
      <ElTableColumn prop="name" label="Skill 名称" min-width="220" show-overflow-tooltip>
        <template #default="{ row }">
          <RouterLink :to="'/platform/skills/' + row.id">{{ row.name }}</RouterLink>
        </template>
      </ElTableColumn>
      <ElTableColumn prop="capabilityKey" label="标识" min-width="240" show-overflow-tooltip>
      </ElTableColumn>
      <ElTableColumn prop="description" label="说明" min-width="220" show-overflow-tooltip />
      <ElTableColumn prop="status" label="状态" width="110"
        ><template #default="{ row }">{{ statusLabel(row.status) }}</template></ElTableColumn
      >
      <ElTableColumn label="默认版本" min-width="150" show-overflow-tooltip
        ><template #default="{ row }">
          <span v-if="row.defaultVersionId">{{ row.defaultVersionKey ?? '尚未读取' }}</span>
          <span v-else>未设置</span>
        </template></ElTableColumn
      >
      <ElTableColumn prop="actions" label="操作" width="130" class-name="admin-table-actions">
        <template #default="{ row }">
          <RouterLink :to="'/platform/skills/' + row.id">编辑文件</RouterLink>
          <RouterLink :to="'/platform/capabilities/' + row.id">版本管理</RouterLink>
        </template>
      </ElTableColumn>
      <template #empty>
        <ElEmpty class="skill-empty" :description="emptyDescription" :image-size="64">
          <ElButton v-if="!load.busy.value && load.error.value" @click="reload">重新加载</ElButton>
          <ElButton v-else-if="!load.busy.value && loaded && hasFilters" @click="search = ''"
            >清空筛选</ElButton
          >
          <template v-else-if="!load.busy.value && loaded">
            <ElButton
              v-permission.all="['foundation.capabilities.create', 'foundation.skills.create']"
              type="primary"
              :disabled="createTask.busy.value || creationUnknown || !!createdSkill"
              @click="openCreate"
              >新建技能</ElButton
            >
          </template>
        </ElEmpty>
      </template>
    </ElTable>
    <ElDrawer
      v-model="createOpen"
      title="新建技能"
      size="680px"
      append-to-body
      class="admin-drawer"
      :before-close="closeCreate"
      :close-on-click-modal="false"
      :close-on-press-escape="!createTask.busy.value"
      :show-close="!createTask.busy.value"
    >
      <p class="admin-field-help">新建后直接编辑工作草稿；ZIP 可随后导入，发布另行确认。</p>
      <p v-if="creationUnknown" role="status">
        创建结果尚未确认，以下为原请求的技能标识和名称，仅供核对。
      </p>
      <ElForm
        ref="formRef"
        :model="{ key, name }"
        label-position="top"
        :disabled="createTask.busy.value || creationUnknown || !!createdSkill"
        scroll-to-error
        @submit.prevent="create"
        @keydown.capture="guardFormEnter"
      >
        <ElFormItem
          label="技能标识"
          prop="key"
          :rules="[
            { required: true, whitespace: true, message: '请输入技能标识', trigger: 'blur' }
          ]"
          ><ElInput v-model="key" placeholder="例如 customer-service" />
          <p class="admin-field-help">已生成唯一标识；如有集成约定，可在创建前修改。</p></ElFormItem
        >
        <ElFormItem
          label="名称"
          prop="name"
          :rules="[{ required: true, whitespace: true, message: '请输入名称', trigger: 'blur' }]"
          ><ElInput v-model="name"
        /></ElFormItem>
        <ElFormItem v-if="!creationUnknown" label="说明"
          ><ElInput v-model="description" type="textarea"
        /></ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton :disabled="createTask.busy.value" @click="closeCreate">取消</ElButton>
        <ElButton
          v-permission.all="['foundation.capabilities.create', 'foundation.skills.create']"
          type="primary"
          :loading="createTask.busy.value"
          :disabled="creationUnknown || !!createdSkill"
          @click="create"
          >创建并进入工作草稿</ElButton
        >
      </template>
    </ElDrawer>
  </AdminPage>
</template>
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
</style>
