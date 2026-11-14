<template>
  <AdminPage
    class="skill-workbench"
    :title="definition?.name || '技能工作台'"
    description="技能内容通过草稿保存；发布版本不可原地修改。"
    ><template #extra
      ><ElButton
        v-if="publicationRefreshRequired || loadError"
        :loading="busy"
        @click="refreshSkill"
        >{{ publicationRefreshRequired ? '读取发布结果' : '重新读取' }}</ElButton
      ><ElButton :disabled="busy || !draft || readonly" @click="validate">校验</ElButton
      ><ElButton
        v-permission="['foundation.skills.publish']"
        :disabled="busy || !draft || readonly || (dirty && !canCombine)"
        @click="dirty ? saveAndPublish() : publish()"
        >{{ dirty && canCombine ? '保存并发布' : '发布' }}</ElButton
      ><ElButton
        v-permission="['foundation.skills.update']"
        type="primary"
        :loading="busy"
        :disabled="!draft || readonly"
        @click="save"
        >保存草稿</ElButton
      ></template
    >
    <section v-if="directPending" class="admin-card" aria-label="直接发布结果核对">
      <p role="status">
        技能发布结果尚未确认，写入已锁定。草稿 {{ directPending.draftId }} · 修订
        {{ directPending.revision }}
      </p>
      <p>继续原发布会使用原草稿和修订重新提交，需要当前发布权限。</p>
      <ElButton
        v-if="can('foundation.skills.publish')"
        :loading="busy"
        @click="continueDirectPublish"
        >继续原发布</ElButton
      >
    </section>
    <section v-if="pending" class="admin-card" aria-label="技能发布结果核对">
      <p role="status">
        保存并发布结果未知，写入已锁定。请求 ID：<code>{{ pending.requestId }}</code>
      </p>
      <p>请记录请求 ID；未查到回执不代表失败，请勿重复提交。</p>
      <ElButton v-if="can('foundation.skills.read')" :loading="busy" @click="checkPublication"
        >核对原操作</ElButton
      >
    </section>
    <ElCollapse v-if="!pending && can('foundation.skills.read')" class="admin-card">
      <ElCollapseItem title="查询历史发布回执" name="receipt">
        <p class="admin-field-help">
          仅在系统提示结果未知且原页面已关闭时使用。正常保存与发布不需要请求 ID。
        </p>
        <div class="admin-actions">
          <ElInput v-model="receiptRequestId" aria-label="原请求 ID" placeholder="输入原请求 ID" />
          <ElButton :loading="busy" @click="checkPublication">查询发布回执</ElButton>
        </div>
      </ElCollapseItem>
    </ElCollapse>
    <p v-if="publicationReceipt?.publication" class="admin-field-help" role="status">
      技能发布已确认：版本 {{ publicationReceipt.publication.version_id }}，定义修订
      {{ publicationReceipt.publication.definition_revision }}。
    </p>
    <div v-if="definition" class="admin-toolbar" role="status">
      <ElTag :type="readonly ? 'info' : dirty ? 'warning' : 'success'">{{
        publicationRefreshRequired
          ? '已发布 · 待更新显示'
          : readonly
            ? '历史发布 · 只读'
            : dirty
              ? '有未保存修改'
              : '工作草稿'
      }}</ElTag>
      <span>{{ definition.capabilityKey }}</span>
      <span v-if="draft"
        >{{ Object.keys(draft.files).length }} 个文件 · 版本 {{ draft.versionKey }}</span
      >
      <span>{{ definition.defaultVersionId ? '已有当前发布' : '尚未发布' }}</span>
    </div>
    <p v-if="dirty" class="admin-field-help">
      校验当前编辑内容不会保存草稿；发布前请处理校验问题。
    </p>
    <p v-else-if="publicationRefreshRequired" class="admin-field-help">
      技能已发布。请读取发布结果后继续编辑，避免重复发布同一草稿。
    </p>
    <p v-else-if="readonly" class="admin-field-help">
      当前查看历史发布配置。恢复到工作草稿后修改，再发布才会生效。
    </p>
    <p class="admin-field-help">
      格式校验检查包结构与平台配置；兼容性、脚本依赖和工具声明仍需在实际 Agent
      环境核验。导入与校验不会执行包内命令。
    </p>
    <div class="skill-validation" v-if="validation" role="status">
      <ElAlert
        v-if="validation"
        :title="validation.valid ? '包格式与平台配置校验通过' : '包格式或平台配置有误'"
        :closable="false"
        :type="validation.valid ? 'success' : 'error'"
      />
      <div v-for="(issue, index) in validation?.issues ?? []" :key="index" class="skill-issue">
        <ElAlert
          :title="[issue.path, issue.line, issue.message].filter(Boolean).join(' · ')"
          :closable="false"
          :type="
            issue.severity === 'ERROR' ? 'error' : issue.severity === 'WARNING' ? 'warning' : 'info'
          "
        /><ElButton
          v-if="issue.path && draft?.files[issue.path] !== undefined"
          link
          @click="locateIssue(issue.path)"
          >定位文件</ElButton
        >
      </div>
    </div>
    <FeedbackNotice
      v-if="storageError || error || loadError"
      :title="storageError || error || loadError"
      :closable="false"
      type="error" /><ElSkeleton v-if="loading" :rows="8" animated /><template
      v-else-if="definition"
      ><ElCollapse
        ><ElCollapseItem title="历史版本与技能包导入" name="versions"
          ><div class="admin-toolbar">
            <ElSelect
              v-described-by="formTipId + '-group-0'"
              :aria-describedby="formTipId + '-group-0'"
              :model-value="selectedVersion"
              :disabled="busy || !!pending || !!storageError"
              style="width: 260px"
              placeholder="工作版本"
              @change="switchVersion"
              ><ElOption label="当前草稿" :value="''" /><ElOption
                v-for="version in versions"
                :key="version.id"
                :value="version.id"
                :label="version.versionKey + ' / ' + statusLabel(version.status)" /></ElSelect
            ><ElButton
              v-permission="['foundation.skills.create']"
              v-if="readonly"
              :disabled="busy"
              @click="fork"
              >恢复为工作草稿</ElButton
            ><ElButton v-if="selectedVersion" :disabled="busy" @click="download"
              >下载技能包</ElButton
            ><ElButton
              v-if="selectedVersion"
              :disabled="busy || !!pending || !!storageError"
              @click="setDefault"
              >设为当前发布</ElButton
            ><ElUpload
              v-described-by="formTipId + '-upload'"
              :aria-describedby="formTipId + '-upload'"
              v-if="!readonly"
              ref="zipUpload"
              :auto-upload="false"
              :show-file-list="false"
              :disabled="busy || !draft || readonly"
              :on-change="importZip"
              accept=".zip"
              ><ElButton :disabled="busy || !draft || readonly">导入 ZIP</ElButton></ElUpload
            >
            <FieldTip v-if="!readonly" :id="formTipId + '-upload'"
              >选择 ZIP 技能包后导入并替换当前草稿文件；有未保存修改时先核对替换提示。</FieldTip
            >

            <FieldTip :id="formTipId + '-group-0'"
              >选择要查看或编辑的技能版本；切换前请保存当前修改。</FieldTip
            >
          </div></ElCollapseItem
        ></ElCollapse
      >
      <p v-if="selectedVersion" class="admin-field-help">计划要求：{{ planRequirement }}</p>
      <ElEmpty v-if="!draft" description="尚无草稿"
        ><ElButton
          v-permission="['foundation.skills.create']"
          type="primary"
          :loading="busy"
          @click="fork"
          >创建草稿</ElButton
        ></ElEmpty
      ><template v-else
        ><ElTabs v-model="activeTab"
          ><ElTabPane label="技能文件" name="files"
            ><PackageFileWorkbench
              ref="packageWorkbench"
              v-model:files="draft.files"
              :readonly="readonly || busy" /></ElTabPane
          ><ElTabPane label="发布设置" name="settings"
            ><ElForm
              :disabled="readonly || busy"
              label-position="top"
              @submit.prevent
              scroll-to-error
              @keydown.capture="guardFormEnter"
              ><div class="admin-form-grid">
                <ElFormItem label="版本号" required
                  ><ElInput
                    v-described-by="formTipId + '-field-0'"
                    :aria-describedby="formTipId + '-field-0'"
                    v-model="draft.versionKey"
                    placeholder="例如 1.0.0"
                  />
                  <FieldTip :id="formTipId + '-field-0'"
                    >平台发布版本标识，必填；独立于原包
                    metadata.version；不需要修改上游文件中的版本号。</FieldTip
                  > </ElFormItem
                ><ElFormItem label="激活方式"
                  ><ElSelect
                    v-described-by="formTipId + '-field-1'"
                    :aria-describedby="formTipId + '-field-1'"
                    v-model="draft.activationMode"
                    ><ElOption value="ON_DEMAND" label="按需激活" /><ElOption
                      value="ALWAYS"
                      label="始终激活"
                  /></ElSelect>
                  <FieldTip :id="formTipId + '-field-1'"
                    >选择技能进入上下文的方式，与技能使用场景一致。</FieldTip
                  > </ElFormItem
                ><ElFormItem label="冲突组"
                  ><ElInput
                    v-described-by="'skill-conflict-help'"
                    aria-describedby="skill-conflict-help"
                    v-model="draft.conflictGroup"
                  />
                  <p id="skill-conflict-help" class="admin-field-help">
                    填写技能冲突组标识，留空表示不指定冲突组。
                  </p></ElFormItem
                >
              </div>
              <div class="admin-toolbar">
                <ElCheckbox
                  v-described-by="formTipId + '-group-1'"
                  :aria-describedby="formTipId + '-group-1'"
                  v-model="draft.modelInvocable"
                  >允许模型调用</ElCheckbox
                ><ElCheckbox
                  v-described-by="formTipId + '-group-1'"
                  :aria-describedby="formTipId + '-group-1'"
                  v-model="draft.userInvocable"
                  >允许用户调用</ElCheckbox
                >

                <FieldTip :id="formTipId + '-group-1'"
                  >分别控制模型是否可主动调用技能、用户是否可主动调用技能；修改随技能版本发布。</FieldTip
                >
              </div></ElForm
            ></ElTabPane
          ><ElTabPane label="能力依赖" name="requirements"
            ><CapabilityRequirementFields
              ref="requirementsRef"
              v-model="draft.requiredCapabilities"
              :disabled="readonly || busy"
              :load-options="loadRequirementOptions" /></ElTabPane></ElTabs></template></template
  ></AdminPage>
</template>
<script setup lang="ts">
import FeedbackNotice from '@skillforge/shared/feedback/FeedbackNotice.vue'
import { vDescribedBy } from '@skillforge/shared/directives/describedBy'

import FieldTip from '@skillforge/shared/components/FieldTip.vue'

import { guardFormEnter } from '@skillforge/shared/admin'
import type { UploadFile, UploadInstance } from 'element-plus'
import { computed, nextTick, onScopeDispose, ref, watch, useId } from 'vue'
import { useAuthStore } from '../../stores/auth'
import { HttpClientError } from '@skillforge/shared/utils/http'
import { skillDraftSavePayload } from '@skillforge/shared/capabilities/skillDrafts'
import { useRoute } from 'vue-router'
import {
  AdminPage,
  CapabilityRequirementFields,
  statusLabel,
  useTask,
  useResource,
  useUnsavedChanges
} from '@skillforge/shared/admin'
import PackageFileWorkbench from '@skillforge/shared/components/PackageFileWorkbench.vue'
import { confirm, message } from '@skillforge/shared/feedback'
import { notifySkillPublished } from './skillPublicationEvents'
import {
  api,
  type SkillDraftContent,
  type SkillDraftValidation,
  type CapabilityType,
  type SkillVersionSummary,
  type SkillSaveAndPublishReceipt
} from '../../api'
const route = useRoute(),
  id = computed(() => String(route.params.definitionId ?? ''))
const {
  data: definition,
  loading,
  error: loadError,
  reload
} = useResource(() => api.capabilities.getDefinition(id.value))
const { busy, error, run } = useTask()
const auth = useAuthStore()
const can = (permission: string) => auth.user?.permissions.includes(permission) === true
const canCombine = computed(() =>
  ['foundation.skills.read', 'foundation.skills.update', 'foundation.skills.publish'].every(can)
)
const owner = computed(() =>
  auth.user?.tenant_id && auth.user?.sub
    ? JSON.stringify([auth.user.tenant_id, auth.user.sub, id.value])
    : ''
)
const storageKey = (value: string) => `skillforge.skill-publication.${value}`
const directStorageKey = (value: string) => `skillforge.skill-direct-publication.${value}`
function noticeIdentity() {
  return {
    actor:
      auth.user?.tenant_id && auth.user?.sub
        ? JSON.stringify([auth.user.tenant_id, auth.user.sub])
        : '',
    owner: owner.value,
    identity: auth.identityVersion,
    session: auth.sessionIdentity
  }
}
function notifyConfirmedSkill(original: ReturnType<typeof noticeIdentity>) {
  const now = noticeIdentity()
  if (
    !disposed &&
    original.actor &&
    original.actor === now.actor &&
    original.owner === now.owner &&
    original.identity === now.identity &&
    original.session === now.session
  )
    notifySkillPublished(original.actor)
}
type Pending = { requestId: string; draftId: string; definitionId: string; revision: number }
const pending = ref<Pending>()
type DirectPending = {
  draftId: string
  definitionId: string
  revision: number
  definitionRevision: number
}
const directPending = ref<DirectPending>()
const storageError = ref('')
const receiptRequestId = ref('')
const publicationReceipt = ref<SkillSaveAndPublishReceipt>()
let disposed = false
onScopeDispose(() => {
  disposed = true
})
watch(
  owner,
  (value) => {
    pending.value = undefined
    directPending.value = undefined
    storageError.value = ''
    publicationReceipt.value = undefined
    receiptRequestId.value = ''
    if (!value) return
    try {
      const directRaw = sessionStorage.getItem(directStorageKey(value))
      if (directRaw) {
        const record = JSON.parse(directRaw) as DirectPending
        if (
          !record ||
          typeof record.draftId !== 'string' ||
          !record.draftId ||
          record.definitionId !== id.value ||
          !Number.isSafeInteger(record.revision) ||
          record.revision < 1 ||
          !Number.isSafeInteger(record.definitionRevision) ||
          record.definitionRevision < 1
        )
          throw Error('invalid')
        directPending.value = record
      }
      const raw = sessionStorage.getItem(storageKey(value))
      if (!raw) return
      const record = JSON.parse(raw) as Pending
      if (
        !record ||
        !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(record.requestId) ||
        typeof record.draftId !== 'string' ||
        record.definitionId !== id.value ||
        !Number.isSafeInteger(record.revision)
      )
        throw Error('invalid')
      pending.value = record
    } catch {
      storageError.value = '无法读取原技能发布记录，请恢复浏览器存储后重新进入页面。'
    }
  },
  { immediate: true, flush: 'sync' }
)
const loadRequirementOptions = (type: CapabilityType) =>
  api.capabilities.listRequirementOptions(type)
const requirementsRef = ref<InstanceType<typeof CapabilityRequirementFields>>()
const activeTab = ref('files'),
  packageWorkbench = ref<{ selectFile?: (path: string) => boolean }>()
async function locateIssue(path: string) {
  activeTab.value = 'files'
  await nextTick()
  if (!packageWorkbench.value?.selectFile?.(path))
    message.info('请在文件列表中打开 ' + path + ' 查看问题位置')
}
const draft = ref<SkillDraftContent>(),
  versions = ref<SkillVersionSummary[]>([]),
  selectedVersion = ref(''),
  saved = ref(''),
  validation = ref<SkillDraftValidation>(),
  publicationRefreshRequired = ref(false)
const readonly = computed(
    () =>
      selectedVersion.value !== '' ||
      publicationRefreshRequired.value ||
      !!pending.value ||
      !!directPending.value ||
      !!storageError.value
  ),
  dirty = computed(() => !!draft.value && JSON.stringify(draft.value) !== saved.value)
watch(
  draft,
  () => {
    validation.value = undefined
  },
  { deep: true }
)
const planRequirement = computed(() =>
  draft.value?.requiresPlan === true
    ? '必须先计划'
    : draft.value?.requiresPlan === false
      ? '按需计划'
      : '尚未读取'
)
useUnsavedChanges({
  dirty: () => dirty.value || !!pending.value || !!directPending.value,
  busy: () => busy.value,
  leaveMessage: () =>
    directPending.value
      ? '技能发布结果尚未确认；离开后请继续原发布。'
      : pending.value
        ? `技能保存并发布结果未知，请记录原请求 ID：${pending.value.requestId}。离开后未保存的正文将丢失。`
        : undefined
})
function accept(value: SkillDraftContent | undefined) {
  publicationRefreshRequired.value = false
  draft.value = value
  saved.value = JSON.stringify(value)
  validation.value = undefined
}
async function load() {
  await run(async () => {
    if (!(await reload())) return
    versions.value = await api.capabilities.listSkillVersionSummaries(id.value)
    selectedVersion.value = ''
    accept(await api.skillDrafts.getActive(id.value))
  })
}
async function refreshSkill() {
  const allowed = await run(
    async () =>
      !dirty.value ||
      (await confirm({
        title: '重新读取技能',
        content: '重新读取会放弃当前未保存的修改。',
        confirmText: '放弃并读取'
      }))
  )
  if (allowed) await load()
}
watch(id, load, { immediate: true })
async function switchVersion(version: string) {
  if (pending.value || directPending.value || storageError.value) return
  if (version === selectedVersion.value) return
  await run(async () => {
    if (
      dirty.value &&
      !(await confirm({
        title: '切换版本',
        confirmText: '放弃并切换',
        content: '放弃尚未保存的技能修改？'
      }))
    )
      return
    const value = version
      ? await api.skillDrafts.getVersion(version)
      : await api.skillDrafts.getActive(id.value)
    if (value && value.definitionId !== id.value) throw Error('版本或草稿不属于当前技能')
    accept(value)
    selectedVersion.value = version
  })
}
async function fork() {
  if (pending.value || directPending.value || storageError.value) return
  await run(async () => {
    if (
      selectedVersion.value &&
      !(await confirm({
        title: '恢复技能草稿',
        confirmText: '恢复为草稿',
        danger: true,
        content: '将此发布版本恢复到当前工作草稿？草稿内容将被覆盖，发布后才会生效。'
      }))
    )
      return
    accept(await api.skillDrafts.create(id.value, selectedVersion.value || undefined))
    selectedVersion.value = ''
    message.success('技能草稿已创建')
  })
}
async function save() {
  return run(async () => {
    const current = draft.value
    if (!current || readonly.value) return
    try {
      await requirementsRef.value?.validate()
    } catch (failure) {
      activeTab.value = 'requirements'
      throw failure
    }
    if (!current.versionKey.trim()) {
      activeTab.value = 'settings'
      throw Error('请输入版本号')
    }
    accept(await api.skillDrafts.save({ ...current, draftId: current.id }))
    message.success('技能草稿已保存')
    return true
  })
}
async function validate() {
  await run(async () => {
    if (!draft.value || readonly.value) return
    if (dirty.value) {
      try {
        await requirementsRef.value?.validate()
      } catch (failure) {
        activeTab.value = 'requirements'
        throw failure
      }
      validation.value = await api.skillDrafts.validateContent({
        ...draft.value,
        draftId: draft.value.id
      })
    } else {
      validation.value = await api.skillDrafts.validate(draft.value.id, draft.value.revision)
    }
  })
}
async function publish() {
  const notice = noticeIdentity(),
    subject = owner.value
  await run(async () => {
    const current = draft.value,
      target = definition.value
    if (
      !subject ||
      !current ||
      !target ||
      dirty.value ||
      readonly.value ||
      !can('foundation.skills.publish')
    )
      return
    if (
      !(await confirm({
        title: '发布技能',
        confirmText: '发布技能',
        content: `发布“${target.name}”的 ${current.versionKey} 版本并设为默认版本？已固定版本的 Agent 需显式更新挂载，正在执行的任务继续原版本。`
      }))
    )
      return
    if (
      disposed ||
      owner.value !== subject ||
      auth.identityVersion !== notice.identity ||
      auth.sessionIdentity !== notice.session ||
      readonly.value ||
      !can('foundation.skills.publish')
    )
      return
    const operation: DirectPending = {
      draftId: current.id,
      definitionId: id.value,
      revision: current.revision,
      definitionRevision: target.revision
    }
    try {
      sessionStorage.setItem(directStorageKey(subject), JSON.stringify(operation))
    } catch {
      storageError.value = '无法保存原发布记录，未发送发布请求；请恢复浏览器存储。'
      return
    }
    directPending.value = operation
    await submitDirectPublish(directPending.value!, subject, notice, false)
  })
}
async function continueDirectPublish() {
  const operation = directPending.value,
    subject = owner.value,
    notice = noticeIdentity()
  if (!operation || !subject || !can('foundation.skills.publish')) return
  await run(() => submitDirectPublish(operation, subject, notice, true))
}
async function submitDirectPublish(
  operation: DirectPending,
  subject: string,
  notice: ReturnType<typeof noticeIdentity>,
  retry: boolean
) {
  const currentScope = () =>
    !disposed &&
    owner.value === subject &&
    auth.identityVersion === notice.identity &&
    auth.sessionIdentity === notice.session
  const current = () => currentScope() && directPending.value === operation
  let result
  try {
    result = await api.skillDrafts.publish(
      operation.draftId,
      operation.revision,
      operation.definitionRevision,
      { silent: true }
    )
  } catch (reason) {
    if (!current()) {
      if (reason instanceof HttpClientError) reason.claimFeedback()
      throw 'cancel'
    }
    if (
      !retry &&
      reason instanceof HttpClientError &&
      reason.status >= 400 &&
      reason.status < 500 &&
      reason.status !== 408 &&
      reason.status !== 429
    ) {
      try {
        sessionStorage.removeItem(directStorageKey(subject))
        directPending.value = undefined
      } catch {
        storageError.value = '无法清除原发布记录，请恢复浏览器存储后继续核对。'
      }
    }
    if (
      !retry &&
      reason instanceof HttpClientError &&
      reason.code === 'CAPABILITY_DESCRIPTOR_INVALID' &&
      can('foundation.skills.update') &&
      !directPending.value
    ) {
      const checked = await api.skillDrafts.validate(operation.draftId, operation.revision)
      if (currentScope()) validation.value = checked
    }
    throw reason
  }
  if (!current()) return
  if (!result.versionId || !Number.isSafeInteger(result.definitionRevision))
    throw Error('原发布回执不完整，结果仍未确认')
  try {
    sessionStorage.removeItem(directStorageKey(subject))
  } catch {
    storageError.value = '无法清除原发布记录，请恢复浏览器存储后继续核对。'
    return
  }
  directPending.value = undefined
  storageError.value = ''
  notifyConfirmedSkill(notice)
  publicationRefreshRequired.value = true
  message.success('技能版本已发布')
  const refreshedDefinition = await reload()
  if (!currentScope() || !refreshedDefinition) return
  const nextVersions = await api.capabilities.listSkillVersionSummaries(operation.definitionId)
  if (!currentScope()) return
  const nextDraft = await api.skillDrafts.getActive(operation.definitionId)
  if (!currentScope()) return
  if (!nextDraft || nextDraft.definitionId !== operation.definitionId)
    throw Error('技能已发布，当前草稿尚未读取；请重新读取已保存内容')
  versions.value = nextVersions
  selectedVersion.value = ''
  accept(nextDraft)
  validation.value = result.validation ?? undefined
}

async function acceptPublication(
  value: SkillSaveAndPublishReceipt | undefined,
  requestId: string,
  subject: string,
  operation?: Pending,
  notice?: ReturnType<typeof noticeIdentity>
) {
  if (
    !value ||
    value.request_id !== requestId ||
    !['PROCESSING', 'SUCCEEDED', 'REJECTED'].includes(value.status) ||
    (value.status === 'REJECTED' && !value.error_message) ||
    (value.status === 'SUCCEEDED' &&
      (value.definition_id !== id.value ||
        (operation && value.draft_id !== operation.draftId) ||
        !value.publication?.version_id ||
        !Number.isSafeInteger(value.publication.definition_revision) ||
        !Number.isSafeInteger(value.revision) ||
        (operation && value.revision !== operation.revision + 1)))
  ) {
    if (!disposed && owner.value === subject)
      message.warning('尚未查到完整匹配的原请求回执，结果仍未确认')
    return
  }
  if (value.status === 'PROCESSING') {
    if (!disposed && owner.value === subject) message.warning('原请求仍在处理，结果尚未确认')
    return
  }
  if (disposed || owner.value !== subject) return
  if (value.status === 'SUCCEEDED' && notice) notifyConfirmedSkill(notice)
  if (operation?.requestId === requestId) {
    try {
      sessionStorage.removeItem(storageKey(subject))
    } catch {
      storageError.value = '无法清除原发布记录，请恢复浏览器存储后重新核对。'
      return
    }
    pending.value = undefined
    storageError.value = ''
  }
  receiptRequestId.value = ''
  if (value.status === 'REJECTED') {
    if (operation && value.validation) validation.value = value.validation
    message.error(`保存并发布未完成：${value.error_message}`)
    return
  }
  publicationReceipt.value = value
  if (!operation && dirty.value) {
    message.success('原技能发布已确认；当前未保存内容仍保留')
    return
  }
  publicationRefreshRequired.value = true
  message.success('技能已保存并发布')
  await reload()
  versions.value = await api.capabilities.listSkillVersionSummaries(id.value)
  selectedVersion.value = ''
  accept(await api.skillDrafts.getActive(id.value))
}
async function saveAndPublish() {
  if (
    !canCombine.value ||
    !owner.value ||
    !draft.value ||
    !definition.value ||
    readonly.value ||
    !dirty.value
  )
    return
  const subject = owner.value
  const notice = noticeIdentity()
  await run(async () => {
    const current = draft.value!
    try {
      await requirementsRef.value?.validate()
    } catch (failure) {
      activeTab.value = 'requirements'
      throw failure
    }
    if (!current.versionKey.trim()) {
      activeTab.value = 'settings'
      throw Error('请输入版本号')
    }
    if (
      !(await confirm({
        title: '保存并发布技能',
        confirmText: '保存并发布',
        content: `保存并发布“${definition.value!.name}”的 ${current.versionKey} 版本并设为默认版本？已固定版本的 Agent 需显式更新挂载，正在执行的任务继续原版本。`
      }))
    )
      return
    if (
      disposed ||
      owner.value !== subject ||
      !canCombine.value ||
      pending.value ||
      storageError.value
    )
      return
    const operation = {
      requestId: crypto.randomUUID(),
      draftId: current.id,
      definitionId: id.value,
      revision: current.revision
    }
    const input = {
      ...(JSON.parse(JSON.stringify(current)) as SkillDraftContent),
      draftId: current.id
    }
    const payload = skillDraftSavePayload(input)
    try {
      sessionStorage.setItem(storageKey(subject), JSON.stringify(operation))
    } catch {
      storageError.value = '无法保存原请求记录，未发送发布请求；请恢复浏览器存储。'
      return
    }
    pending.value = operation
    publicationReceipt.value = undefined
    try {
      const result = await api.skillDrafts.saveAndPublish(
        operation.requestId,
        definition.value!.revision,
        current.revision,
        payload
      )
      await acceptPublication(result, operation.requestId, subject, operation, notice)
    } catch (cause) {
      if (disposed || owner.value !== subject) {
        if (cause instanceof HttpClientError) cause.claimFeedback()
        throw 'cancel'
      }
      if (
        cause instanceof HttpClientError &&
        cause.code === 'SKILL_PUBLISH_PRE_DISPATCH_REJECTED'
      ) {
        try {
          sessionStorage.removeItem(storageKey(subject))
          pending.value = undefined
        } catch {
          storageError.value = '无法清除原发布记录，请恢复浏览器存储后重新核对。'
        }
      }
      throw cause
    }
  })
}
async function checkPublication() {
  const subject = owner.value
  const notice = noticeIdentity()
  const operation = pending.value
  const requestId = operation?.requestId ?? receiptRequestId.value.trim()
  if (
    !subject ||
    !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(requestId)
  ) {
    message.warning('请输入原请求的 UUID')
    return
  }
  await run(async () =>
    acceptPublication(
      await api.skillDrafts.saveAndPublishResult(requestId),
      requestId,
      subject,
      operation,
      notice
    )
  )
}

const zipUpload = ref<UploadInstance>()
async function importZip(selection: UploadFile) {
  const file = selection.raw
  zipUpload.value?.clearFiles()
  if (!file || !draft.value || readonly.value) return
  await run(async () => {
    const current = draft.value!
    if (
      dirty.value &&
      !(await confirm({
        title: '导入技能包',
        confirmText: '替换并导入',
        content: '导入会替换当前文件，请先保存或确认放弃修改。'
      }))
    )
      return
    accept(await api.skillDrafts.importZip(current.id, current.revision, file))
    message.success('技能包已导入')
  })
}
async function download() {
  await run(async () => {
    const blob = await api.capabilities.downloadSkill(selectedVersion.value),
      url = URL.createObjectURL(blob),
      anchor = document.createElement('a')
    anchor.href = url
    anchor.download = (definition.value?.capabilityKey ?? 'skill') + '.zip'
    anchor.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  })
}
async function setDefault() {
  if (pending.value || directPending.value || storageError.value) return
  const notice = noticeIdentity()
  await run(async () => {
    if (!definition.value) return
    if (
      !(await confirm({
        title: '切换当前发布技能',
        confirmText: '设为当前发布',
        content: '设为默认版本；已固定版本的 Agent 需显式更新挂载，正在执行的任务继续原版本。'
      }))
    )
      return
    const result = await api.capabilities.setSkillDefault(
      id.value,
      selectedVersion.value,
      definition.value.revision
    )
    if (result.id === id.value && result.defaultVersionId === selectedVersion.value)
      notifyConfirmedSkill(notice)
    await reload()
    message.success('默认技能版本已更新，已固定版本的 Agent 需显式更新挂载')
  })
}

const formTipId = useId()
</script>
<style scoped>
.skill-workbench :deep(.el-collapse) {
  overflow: hidden;
  border: 1px solid var(--admin-border);
  border-radius: var(--el-card-border-radius, 10px);
  background: var(--admin-surface);
}
.skill-workbench :deep(.el-collapse-item__header) {
  padding-inline: var(--el-card-padding, 20px);
}
.skill-workbench :deep(.el-collapse-item__content) {
  padding: 0 var(--el-card-padding, 20px) var(--el-card-padding, 20px);
}
.skill-workbench :deep(.el-tab-pane) {
  padding: var(--el-card-padding, 20px);
  border: 1px solid var(--admin-border);
  border-radius: var(--el-card-border-radius, 10px);
  background: var(--admin-surface);
}
.skill-validation {
  display: grid;
  gap: 10px;
  margin-block: 16px;
}
.skill-issue {
  display: flex;
  gap: 12px;
  align-items: flex-start;
}
.skill-issue :deep(.el-alert) {
  flex: 1;
  min-width: 0;
}
.skill-issue :deep(.el-alert__title) {
  overflow-wrap: anywhere;
}
</style>
