<script setup lang="ts">
import FeedbackNotice from '@skillforge/shared/feedback/FeedbackNotice.vue'
import { useActionPermissions } from '../../composables/actionPermissions'
import { vDescribedBy } from '@skillforge/shared/directives/describedBy'

import FieldTip from '@skillforge/shared/components/FieldTip.vue'

import { guardFormEnter } from '@skillforge/shared/admin'
import { message, confirm } from '@skillforge/shared/feedback'
import { HttpClientError } from '@skillforge/shared/utils/http'
import { skillDraftSavePayload } from '@skillforge/shared/capabilities/skillDrafts'
import { useAuthStore } from '../../stores/auth'
import { computed, onMounted, onScopeDispose, ref, useId, watch } from 'vue'
import { useRoute } from 'vue-router'
import { type UploadFile, type UploadInstance } from 'element-plus'
import {
  AdminPage,
  useTask,
  useUnsavedChanges,
  CapabilityRequirementFields
} from '@skillforge/shared/admin'
import PackageFileWorkbench from '@skillforge/shared/components/PackageFileWorkbench.vue'
import {
  api,
  type CapabilityDefinition,
  type CapabilityType,
  type SkillDraftContent,
  type SkillSaveAndPublishReceipt,
  type SkillValidationIssue
} from '../../api'
const { allowed, checked } = useActionPermissions()
const auth = useAuthStore()
const loadRequirementOptions = (type: CapabilityType) =>
  api.capabilities.listRequirementOptions(type)
const requirementsRef = ref<InstanceType<typeof CapabilityRequirementFields>>()
const upload = ref<UploadInstance>()
const fileWorkbench = ref<InstanceType<typeof PackageFileWorkbench>>()
const skillSections = ref<string[]>([])
let publicationReadFailure: Error | undefined
const route = useRoute(),
  id = String(route.params.definitionId ?? ''),
  load = useTask(),
  action = useTask({
    handlesError: (reason) =>
      publicationReadFailure !== undefined && reason === publicationReadFailure
  }),
  definition = ref<CapabilityDefinition>(),
  draft = ref<SkillDraftContent>(),
  baseline = ref(''),
  issues = ref<SkillValidationIssue[]>([]),
  versionView = ref(false),
  displayedVersionId = ref<string>(),
  publicationReadPending = ref(false)
type Pending = { requestId: string; draftId: string; definitionId: string; revision: number }
const pending = ref<Pending>()
const receiptRequestId = ref('')
const manualReceipt = ref<SkillSaveAndPublishReceipt>()
const storageError = ref('')
const showDirtyIssues = ref(false)
const owner = computed(() => (auth.user?.sub ? JSON.stringify([auth.user.sub, id]) : ''))
const storageKey = (subject: string) => `skillforge.platform.skill-publication.${subject}`
const writeLocked = computed(
  () => publicationReadPending.value || !!pending.value || !!storageError.value
)
let disposed = false
onScopeDispose(() => {
  disposed = true
})
watch(
  owner,
  (subject) => {
    pending.value = undefined
    receiptRequestId.value = ''
    manualReceipt.value = undefined
    storageError.value = ''
    if (!subject) return
    try {
      const raw = sessionStorage.getItem(storageKey(subject))
      if (!raw) return
      const record = JSON.parse(raw) as Pending
      if (
        !record ||
        !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(record.requestId) ||
        typeof record.draftId !== 'string' ||
        record.definitionId !== id ||
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
const dirty = computed(
  () => !!draft.value && !versionView.value && JSON.stringify(draft.value) !== baseline.value
)
watch(
  draft,
  () => {
    showDirtyIssues.value = false
  },
  { deep: true }
)
useUnsavedChanges({
  dirty: () => dirty.value || !!pending.value,
  busy: () => action.busy.value,
  leaveMessage: () =>
    pending.value
      ? `发布结果未知，请记录原请求 ID：${pending.value.requestId}。离开后未保存内容将丢失。`
      : undefined
})
function accept(value: SkillDraftContent) {
  draft.value = value
  baseline.value = JSON.stringify(value)
  issues.value = []
  showDirtyIssues.value = false
}
function reload() {
  void load.run(async () => {
    if (
      dirty.value &&
      !(await confirm({
        title: '重新读取草稿',
        content: '将重新读取已保存内容并放弃当前未保存修改，确认继续？',
        confirmText: '放弃修改并读取',
        cancelText: '继续编辑'
      }))
    )
      return
    definition.value = await api.capabilities.getDefinition(id)
    const versionId = String(route.query.versionId ?? '')
    if (versionId) {
      const value = await api.skillDrafts.getVersion(versionId)
      if (value.definitionId !== id) throw new Error('版本不属于当前能力')
      accept(value)
      displayedVersionId.value = versionId
      versionView.value = true
      publicationReadPending.value = false
      return
    }
    const draftId = String(route.query.draftId ?? '')
    const value = draftId ? await api.skillDrafts.get(draftId) : await api.skillDrafts.getActive(id)
    if (value) {
      if (value.definitionId !== id) throw new Error('草稿不属于当前能力')
      accept(value)
      publicationReadPending.value = false
    } else if (publicationReadPending.value)
      throw new Error('Skill 已发布，但当前草稿尚未读取；请点击“重新读取已保存内容”核对')
  })
}
function create() {
  if (!allowed('foundation.skills.create') || writeLocked.value) return
  void action.run(async () => {
    const v = versionView.value ? displayedVersionId.value : definition.value?.defaultVersionId
    if (
      versionView.value &&
      !(await confirm({
        title: '恢复技能草稿',
        confirmText: '恢复为草稿',
        danger: true,
        content: '将历史发布恢复到当前工作草稿？草稿内容将被覆盖，发布后才会生效。'
      }))
    )
      return
    accept(await checked('foundation.skills.create', () => api.skillDrafts.create(id, v)))
    versionView.value = false
  })
}
async function saveCurrent() {
  if (!allowed('foundation.skills.update') || writeLocked.value) return
  try {
    await requirementsRef.value?.validate()
  } catch (error) {
    if (!skillSections.value.includes('dependencies')) skillSections.value.push('dependencies')
    throw error
  }
  const d = draft.value!
  if (!d.versionKey.trim()) throw new Error('版本标识不能为空')
  accept(
    await checked('foundation.skills.update', () =>
      api.skillDrafts.save({
        draftId: d.id,
        revision: d.revision,
        versionKey: d.versionKey,
        files: d.files,
        modelInvocable: d.modelInvocable,
        userInvocable: d.userInvocable,
        activationMode: d.activationMode,
        conflictGroup: d.conflictGroup,
        requiredCapabilities: d.requiredCapabilities
      })
    )
  )
}
function save() {
  if (!allowed('foundation.skills.update') || writeLocked.value) return
  void action.run(async () => {
    await saveCurrent()
    message.success('草稿已保存')
  })
}
function validate() {
  if (!allowed('foundation.skills.update') || writeLocked.value) return
  void action.run(async () => {
    const d = draft.value!
    if (dirty.value) {
      try {
        await requirementsRef.value?.validate()
      } catch (failure) {
        if (!skillSections.value.includes('dependencies')) skillSections.value.push('dependencies')
        throw failure
      }
    }
    const result = await checked('foundation.skills.update', () =>
      dirty.value
        ? api.skillDrafts.validateContent({ ...d, draftId: d.id })
        : api.skillDrafts.validate(d.id, d.revision)
    )
    issues.value = result.issues
    showDirtyIssues.value = dirty.value
    if (result.valid) message.success('包格式与平台配置校验通过')
  })
}
async function acceptPublication(
  value: SkillSaveAndPublishReceipt | undefined,
  operation: Pending,
  subject: string
) {
  if (disposed || owner.value !== subject || !allowed('foundation.skills.read')) return
  if (
    !value ||
    value.request_id !== operation.requestId ||
    !['PROCESSING', 'SUCCEEDED', 'REJECTED'].includes(value.status) ||
    (value.status === 'REJECTED' && !value.error_message) ||
    (value.status === 'SUCCEEDED' &&
      (value.definition_id !== id ||
        value.draft_id !== operation.draftId ||
        value.revision !== operation.revision + 1 ||
        !value.publication?.version_id ||
        !Number.isSafeInteger(value.publication.definition_revision)))
  ) {
    message.warning('尚未查到完整匹配的原请求回执，结果仍未确认')
    return
  }
  if (value.status === 'PROCESSING') {
    message.warning('原请求仍在处理，结果尚未确认')
    return
  }
  try {
    sessionStorage.removeItem(storageKey(subject))
  } catch {
    storageError.value = '无法清除原发布记录，请恢复浏览器存储后重新核对。'
    return
  }
  pending.value = undefined
  storageError.value = ''
  if (value.status === 'REJECTED') {
    issues.value = value.validation?.issues ?? []
    showDirtyIssues.value = true
    message.error(`保存并发布未完成：${value.error_message}`)
    return
  }
  publicationReadPending.value = true
  definition.value = {
    ...definition.value!,
    revision: value.publication!.definition_revision,
    defaultVersionId: value.publication!.version_id
  }
  baseline.value = JSON.stringify(draft.value)
  message.success('Skill 已保存并发布')
  const fresh = await api.skillDrafts.getActive(id)
  if (!fresh || fresh.definitionId !== id)
    throw new Error('Skill 已发布，当前草稿尚未读取；请重新读取已保存内容')
  accept(fresh)
  issues.value = value.validation?.issues ?? []
  publicationReadPending.value = false
}
function saveAndPublish() {
  if (
    !owner.value ||
    writeLocked.value ||
    !dirty.value ||
    !draft.value ||
    !definition.value ||
    !allowed('foundation.skills.read') ||
    !allowed('foundation.skills.update') ||
    !allowed('foundation.skills.publish')
  )
    return
  const subject = owner.value
  void action.run(async () => {
    const current = draft.value!
    try {
      await requirementsRef.value?.validate()
    } catch (failure) {
      if (!skillSections.value.includes('dependencies')) skillSections.value.push('dependencies')
      throw failure
    }
    if (!current.versionKey.trim()) throw new Error('版本标识不能为空')
    if (
      !(await confirm({
        title: '保存并发布 Skill',
        confirmText: '保存并发布',
        cancelText: '取消',
        content: `保存并发布 Skill ${current.versionKey} 并设为默认版本？已固定版本的 Agent 需显式更新挂载，正在执行的任务继续原版本；发布版本不可修改。`
      }))
    )
      return
    if (
      disposed ||
      owner.value !== subject ||
      writeLocked.value ||
      !allowed('foundation.skills.update') ||
      !allowed('foundation.skills.publish') ||
      !allowed('foundation.skills.read')
    )
      return
    const operation = {
      requestId: crypto.randomUUID(),
      draftId: current.id,
      definitionId: id,
      revision: current.revision
    }
    const payload = skillDraftSavePayload({
      ...JSON.parse(JSON.stringify(current)),
      draftId: current.id
    })
    try {
      sessionStorage.setItem(storageKey(subject), JSON.stringify(operation))
    } catch {
      storageError.value = '无法保存原请求记录，未发送发布请求；请恢复浏览器存储。'
      return
    }
    pending.value = operation
    try {
      const result = await api.skillDrafts.saveAndPublish(
        operation.requestId,
        definition.value!.revision,
        current.revision,
        payload
      )
      await acceptPublication(result, operation, subject)
    } catch (reason) {
      if (disposed || owner.value !== subject) {
        if (reason instanceof HttpClientError) reason.claimFeedback()
        throw 'cancel'
      }
      if (
        reason instanceof HttpClientError &&
        reason.code === 'SKILL_PUBLISH_PRE_DISPATCH_REJECTED'
      ) {
        try {
          sessionStorage.removeItem(storageKey(subject))
          pending.value = undefined
        } catch {
          storageError.value = '无法清除原发布记录，请恢复浏览器存储后重新核对。'
        }
      }
      throw reason
    }
  })
}
function checkPublication() {
  const operation = pending.value,
    subject = owner.value
  if (!operation || !subject || !allowed('foundation.skills.read')) return
  void action.run(async () =>
    acceptPublication(
      await api.skillDrafts.saveAndPublishResult(operation.requestId),
      operation,
      subject
    )
  )
}
function lookupPublication() {
  const subject = owner.value,
    requestId = receiptRequestId.value.trim()
  if (
    !subject ||
    !allowed('foundation.skills.read') ||
    !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(requestId)
  ) {
    message.warning('请输入原请求的 UUID')
    return
  }
  void action.run(async () => {
    const receipt = await api.skillDrafts.saveAndPublishResult(requestId)
    if (
      disposed ||
      owner.value !== subject ||
      !allowed('foundation.skills.read') ||
      receiptRequestId.value.trim() !== requestId
    )
      return
    if (!receipt || receipt.request_id.toLowerCase() !== requestId.toLowerCase()) {
      message.warning('尚未查到原请求回执，结果仍未确认')
      return
    }
    manualReceipt.value = receipt
  })
}
function publish() {
  if (
    !allowed('foundation.skills.update') ||
    !allowed('foundation.skills.publish') ||
    writeLocked.value
  )
    return
  void action.run(async () => {
    if (versionView.value) return
    if (dirty.value) throw new Error('请先保存草稿')
    if (
      !(await confirm({
        title: '发布 Skill',
        content: `发布 Skill ${draft.value!.versionKey} 并设为默认版本？已固定版本的 Agent 需显式更新挂载，正在执行的任务继续原版本；发布版本不可修改。`,
        confirmText: '发布 Skill',
        cancelText: '取消',
        danger: false
      }))
    )
      return
    let result
    try {
      result = await checked('foundation.skills.publish', () =>
        api.skillDrafts.publish(
          draft.value!.id,
          draft.value!.revision,
          definition.value!.revision,
          { silent: true }
        )
      )
    } catch (reason) {
      if (reason instanceof HttpClientError && reason.code === 'CAPABILITY_DESCRIPTOR_INVALID') {
        const validation = await checked('foundation.skills.update', () =>
          api.skillDrafts.validate(draft.value!.id, draft.value!.revision)
        )
        issues.value = validation.issues
      }
      throw reason
    }
    issues.value = result.validation?.issues ?? []
    publicationReadPending.value = true
    definition.value = {
      ...definition.value!,
      revision: result.definitionRevision,
      defaultVersionId: result.versionId
    }
    versionView.value = false
    baseline.value = JSON.stringify(draft.value)
    message.success('Skill 已发布')
    const value = await api.skillDrafts.getActive(id).catch((reason) => {
      if (reason instanceof HttpClientError) reason.claimFeedback()
      publicationReadFailure = new Error(
        'Skill 已发布，但当前草稿读取失败；请点击“重新读取已保存内容”，不要重复发布',
        { cause: reason }
      )
      throw publicationReadFailure
    })
    if (!value) return
    if (value.definitionId !== id)
      throw new Error('读取的草稿与当前能力不匹配，已停止采用该内容；请通过版本管理核对能力身份')
    accept(value)
    issues.value = result.validation?.issues ?? []
    displayedVersionId.value = undefined
    versionView.value = false
    publicationReadPending.value = false
  })
}
async function importZip(selected: UploadFile) {
  if (!allowed('foundation.skills.create') || writeLocked.value) return
  const file = selected.raw
  if (!file) return
  try {
    await action.run(async () => {
      if (dirty.value)
        if (
          !(await confirm({
            title: '导入 Skill',
            content: '导入 ZIP 将替换当前草稿文件，确认继续？',
            confirmText: '替换草稿文件',
            cancelText: '取消',
            danger: true
          }))
        )
          return
      accept(
        await checked('foundation.skills.create', () =>
          api.skillDrafts.importZip(draft.value!.id, draft.value!.revision, file)
        )
      )
    })
  } finally {
    upload.value?.clearFiles()
  }
}
function issueFile(issue: SkillValidationIssue) {
  return issue.path &&
    draft.value &&
    Object.prototype.hasOwnProperty.call(draft.value.files, issue.path)
    ? issue.path
    : undefined
}
async function locateIssue(issue: SkillValidationIssue) {
  const path = issueFile(issue)
  if (path) await fileWorkbench.value?.reveal(path, issue.line ?? 1)
}
onMounted(reload)

const formTipId = useId()
</script>

<template>
  <AdminPage
    class="skill-workbench"
    :title="definition?.name ?? 'Skill 编辑'"
    :description="versionView ? '已发布版本 · 只读' : '编辑技能，可直接校验当前内容或保存并发布'"
  >
    <template #extra>
      <RouterLink :to="'/platform/capabilities/' + id">
        <ElButton>版本管理</ElButton>
      </RouterLink>
      <ElTag v-if="dirty" type="warning">未保存</ElTag>
      <ElButton
        v-permission="['foundation.skills.create']"
        v-if="!draft || versionView"
        type="primary"
        :loading="action.busy.value"
        :disabled="writeLocked"
        @click="create"
        >{{ versionView ? '恢复为工作草稿' : '创建草稿' }}</ElButton
      >
      <template v-else>
        <ElButton
          v-permission="['foundation.skills.update']"
          :loading="action.busy.value"
          :disabled="writeLocked"
          @click="save"
          >保存草稿</ElButton
        >
        <ElButton
          v-permission="['foundation.skills.update']"
          :disabled="action.busy.value || writeLocked"
          @click="validate"
          >校验</ElButton
        >
        <ElButton
          v-permission.all="['foundation.skills.update', 'foundation.skills.publish']"
          type="primary"
          :disabled="
            action.busy.value || writeLocked || (dirty && !allowed('foundation.skills.read'))
          "
          @click="dirty ? saveAndPublish() : publish()"
          >{{ dirty ? '保存并发布' : '发布' }}</ElButton
        >
      </template>
    </template>
    <section v-if="pending" class="admin-card" aria-label="技能发布结果核对">
      <p role="status">
        保存并发布结果未知，写入已锁定。请求 ID：<code>{{ pending.requestId }}</code>
      </p>
      <p>未查到回执不代表失败，请勿重复提交。</p>
      <ElButton
        v-if="allowed('foundation.skills.read')"
        :loading="action.busy.value"
        @click="checkPublication"
        >核对原操作</ElButton
      >
    </section>
    <section
      v-else-if="allowed('foundation.skills.read')"
      class="admin-toolbar"
      aria-label="查询技能发布回执"
    >
      <ElInput
        v-model="receiptRequestId"
        aria-label="原请求 ID"
        placeholder="输入原请求 ID"
        @input="manualReceipt = undefined"
      />
      <ElButton :loading="action.busy.value" @click="lookupPublication">查询发布回执</ElButton>
    </section>
    <p v-if="manualReceipt" role="status" class="admin-field-help">
      原请求状态：{{
        manualReceipt.status === 'SUCCEEDED'
          ? '已完成'
          : manualReceipt.status === 'REJECTED'
            ? '已拒绝'
            : '处理中'
      }}{{ manualReceipt.error_message ? ' · ' + manualReceipt.error_message : '' }}
      {{ manualReceipt.publication ? ' · 发布版本 ' + manualReceipt.publication.version_id : '' }}
    </p>
    <p v-if="storageError" role="status">{{ storageError }}</p>
    <FeedbackNotice
      v-if="load.error.value || action.error.value"
      :title="load.error.value || action.error.value"
      :closable="false"
      type="error"
    />
    <ElAlert
      v-if="publicationReadPending"
      title="Skill 已发布，当前草稿尚未读取；请点击“重新读取已保存内容”核对，不要重复发布。"
      type="warning"
      :closable="false"
    />
    <ElButton
      v-if="(load.error.value || publicationReadPending) && !pending"
      :disabled="action.busy.value"
      :loading="load.busy.value"
      @click="reload"
      >重新读取已保存内容</ElButton
    >
    <ElSkeleton v-if="load.busy.value && !draft" :rows="8" />
    <ElEmpty
      v-else-if="!draft"
      :description="
        load.error.value
          ? 'Skill 内容尚未读取，请先处理上方错误'
          : '尚无编辑草稿，请创建草稿开始编写'
      "
    />
    <template v-else>
      <p class="admin-field-help">
        格式校验检查包结构与平台配置；兼容性、脚本依赖和工具声明仍需在实际 Agent
        环境核验。导入与校验不会执行包内命令。
      </p>
      <ElCard class="admin-card">
        <template #header
          ><strong>发布设置</strong
          ><span class="admin-muted"> · {{ draft.versionKey || '尚未填写版本' }}</span></template
        >
        <ElForm
          @keydown.capture="guardFormEnter"
          label-position="top"
          :disabled="
            !allowed('foundation.skills.update') || versionView || action.busy.value || writeLocked
          "
        >
          <div class="admin-form-grid">
            <ElFormItem label="版本标识">
              <ElInput
                v-described-by="'skill-version-help'"
                aria-describedby="skill-version-help"
                v-model="draft.versionKey"
              />
              <p id="skill-version-help" class="admin-field-help">
                平台发布版本标识，独立于原包
                metadata.version；发布新版本不需要修改上游文件中的版本号。
              </p>
            </ElFormItem>
            <ElFormItem label="激活方式">
              <ElSelect
                v-described-by="formTipId + '-field-1'"
                :aria-describedby="formTipId + '-field-1'"
                v-model="draft.activationMode"
              >
                <ElOption value="ALWAYS" label="始终激活" />
                <ElOption value="ON_DEMAND" label="按需激活" />
              </ElSelect>

              <FieldTip :id="formTipId + '-field-1'">选择技能何时进入执行上下文。</FieldTip>
            </ElFormItem>
          </div>
          <ElCollapse v-model="skillSections">
            <ElCollapseItem
              name="invocation"
              :title="`调用权限与互斥 · ${draft.modelInvocable ? '模型可调用' : '模型不可调用'} · ${draft.userInvocable ? '用户可调用' : '用户不可调用'}${draft.conflictGroup ? ' · 互斥组 ' + draft.conflictGroup : ''}`"
              ><div class="admin-form-grid">
                <ElFormItem label="允许模型调用">
                  <ElSwitch
                    v-described-by="formTipId + '-field-2'"
                    :aria-describedby="formTipId + '-field-2'"
                    v-model="draft.modelInvocable"
                  />

                  <FieldTip :id="formTipId + '-field-2'"
                    >允许智能体通过模型决策调用该技能。</FieldTip
                  >
                </ElFormItem>
                <ElFormItem label="允许用户调用">
                  <ElSwitch
                    v-described-by="formTipId + '-field-3'"
                    :aria-describedby="formTipId + '-field-3'"
                    v-model="draft.userInvocable"
                  />

                  <FieldTip :id="formTipId + '-field-3'">允许用户主动调用该技能。</FieldTip>
                </ElFormItem>
                <ElFormItem label="互斥组">
                  <ElInput
                    v-described-by="formTipId + '-field-4'"
                    :aria-describedby="formTipId + '-field-4'"
                    v-model="draft.conflictGroup"
                  />

                  <FieldTip :id="formTipId + '-field-4'">同组技能互斥；留空不指定互斥组。</FieldTip>
                </ElFormItem>
              </div></ElCollapseItem
            >
            <ElCollapseItem
              name="dependencies"
              :title="`依赖能力 · ${draft.requiredCapabilities.length} 项`"
            >
              <CapabilityRequirementFields
                ref="requirementsRef"
                v-model="draft.requiredCapabilities"
                :disabled="
                  !allowed('foundation.skills.update') ||
                  versionView ||
                  action.busy.value ||
                  writeLocked
                "
                :load-options="loadRequirementOptions"
              />
            </ElCollapseItem>
          </ElCollapse>
        </ElForm>
        <ElUpload
          v-described-by="formTipId + '-upload'"
          :aria-describedby="formTipId + '-upload'"
          v-if="!versionView"
          ref="upload"
          class="admin-actions"
          accept=".zip"
          :auto-upload="false"
          :show-file-list="false"
          :disabled="!allowed('foundation.skills.create') || action.busy.value || writeLocked"
          :on-change="importZip"
          ><ElButton :loading="action.busy.value">导入 ZIP 替换草稿</ElButton></ElUpload
        >
        <FieldTip v-if="!versionView" :id="formTipId + '-upload'"
          >选择 ZIP 技能包后导入并替换当前草稿文件；有未保存修改时先核对替换提示。</FieldTip
        >
      </ElCard>
      <ElAlert
        v-for="(issue, index) in !dirty || showDirtyIssues ? issues : []"
        :key="index"
        :type="
          issue.severity === 'ERROR' ? 'error' : issue.severity === 'WARNING' ? 'warning' : 'info'
        "
        :closable="false"
        :title="[issue.path, issue.line, issue.message].filter(Boolean).join(' · ')"
        ><ElButton v-if="issueFile(issue)" link @click="locateIssue(issue)"
          >定位文件{{ issue.line ? '第 ' + issue.line + ' 行' : '' }}</ElButton
        ></ElAlert
      >
      <PackageFileWorkbench
        ref="fileWorkbench"
        v-model:files="draft.files"
        :readonly="
          !allowed('foundation.skills.update') || versionView || action.busy.value || writeLocked
        "
      />
    </template>
  </AdminPage>
</template>
<style scoped>
.skill-workbench :deep(.el-collapse-item__header) {
  padding-inline: 12px;
}
.skill-workbench :deep(.el-collapse-item__content) {
  padding: 12px;
}
</style>
