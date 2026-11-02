<script setup lang="ts">
import FeedbackNotice from '@skillforge/shared/feedback/FeedbackNotice.vue'
import { computed, nextTick, onScopeDispose, reactive, ref, watch } from 'vue'
import type { FormInstance } from 'element-plus'
import { useRoute } from 'vue-router'
import {
  EntitySelect,
  statusLabel,
  AdminPage,
  guardFormEnter,
  useResource,
  useTask,
  useUnsavedChanges
} from '@skillforge/shared/admin'
import { confirm, message } from '@skillforge/shared/feedback'
import { api } from '../../api'
import { createOperationOptions } from '../../api/entityOptions'
import { useActionPermissions } from '../../composables/actionPermissions'
import type {
  InstitutionCredential,
  IssuedInstitutionCredential
} from '../../api/platformIntegration'
import { useInstitutionPanelScope, useInstitutionPanelCommand } from './institutionPanelScope'
const props = defineProps<{ tenantId?: string; integrationId?: string; embedded?: boolean }>()
const emit = defineEmits<{ changed: []; integrationSelected: [integrationId: string] }>()
const scope = useInstitutionPanelScope(() => `${props.tenantId ?? ''}:${props.integrationId ?? ''}`)
const route = useRoute()
const { allowed, checked } = useActionPermissions()
const tenantOptions = createOperationOptions('tenants', 'platform.institution_credentials.read')
const filters = reactive({
  tenant_id: props.tenantId ?? String(route.query.tenant_id ?? ''),
  integration_id: props.integrationId ?? String(route.query.integration_id ?? '')
})
const integrationOptions = computed(() =>
  createOperationOptions('integrations', 'platform.institution_credentials.read', filters.tenant_id)
)
const applied = ref({ ...filters })
const targetChanged = computed(
  () =>
    filters.tenant_id.trim() !== applied.value.tenant_id ||
    filters.integration_id.trim() !== applied.value.integration_id
)
const page = ref(1)
const refreshRequired = ref(false)
const list = useResource((signal) =>
  api.platformIntegration.institutionCredentials(
    { page: page.value, ...applied.value },
    { signal, silent: true }
  )
)
const action = useTask()
const issueFormRef = ref<FormInstance>()
const scopes = ref<string[]>([])
// 说明对应开放接口的 x-required-scope；代码仍是签发请求的原值。
const executionScopes: Record<string, string> = {
  'sessions.write': '写入和控制会话',
  'sessions.read': '读取会话',
  'sessions.events.read': '读取会话事件和事件流',
  'end_users.write': '登记终端用户',
  'resources.read': '读取资源和智能体部署',
  'resources.write': '维护资源和智能体部署',
  'artifacts.read': '读取会话已发布成果',
  'control.operations.read': '读取控制操作',
  'control.operations.write': '创建控制操作',
  'usage.read': '读取用量',
  'business.scopes.write': '登记和维护业务范围',
  'groups.read': '读取群、成员和服务智能体',
  'groups.write': '创建或关闭群',
  'groups.members.write': '维护群成员',
  'groups.agents.write': '维护群服务智能体',
  'groups.messages.write': '记录群消息',
  'groups.triggers.write': '触发群消息处理',
  'groups.triggers.read': '读取群触发结果',
  'groups.runs.respond': '响应群运行中的等待',
  'groups.runs.read': '读取群运行状态',
  'groups.runs.control': '控制群运行',
  'groups.history.read': '读取群历史和事件',
  'groups.controls.write': '创建群控制命令',
  'groups.controls.read': '读取群控制状态并核验权限',
  'groups.controls.reconcile': '确认群控制命令终态',
  'groups.controls.release': '释放群控制',
  'groups.controls.ack': '确认发送端及交付资格',
  'groups.senders.manage': '登记或退役群发送端'
}
const issued = ref<IssuedInstitutionCredential>()
const command = useInstitutionPanelCommand()
const uncertain = computed(() => !!command.pending.value)
/** 目标切换先清除旧凭证投影和秘密；只有显式查询后才采用新目标。 */
function query() {
  if (action.busy.value) return
  if (uncertain.value && targetChanged.value) {
    action.error.value = '请先核对原执行域的请求结果后再切换执行域'
    return
  }
  clearSecret()
  if (!filters.tenant_id.trim() || !filters.integration_id.trim()) {
    action.error.value = '请选择目标机构和接入'
    return
  }
  if (targetChanged.value) refreshRequired.value = false
  applied.value = {
    tenant_id: filters.tenant_id.trim(),
    integration_id: filters.integration_id.trim()
  }
  page.value = 1
  list.data.value = undefined
  emit('integrationSelected', applied.value.integration_id)
  void list.reload().then((value) => {
    if (value) refreshRequired.value = false
  })
}
/** 安全目录只反映当前状态，不解除原未知请求；首次秘密不能重新读取。 */
async function verify() {
  if (action.busy.value) return
  if (await list.reload()) {
    refreshRequired.value = false
    action.error.value = ''
    emit('changed')
  }
}
/** 已确认写入后，只有重新读取安全目录才能继续基于目录操作。 */
function refreshAfterWrite() {
  refreshRequired.value = true
  void list.reload().then((value) => {
    if (value) refreshRequired.value = false
  })
}
/** 显式签发当前已查询执行域，输入中的目标变化不会静默改变签发对象。 */
function issue() {
  if (!allowed('platform.institution_credentials.issue') || uncertain.value || refreshRequired.value) return
  const requireTarget = scope.capture()
  void action.run(async () => {
    if (
      filters.tenant_id.trim() !== applied.value.tenant_id ||
      filters.integration_id.trim() !== applied.value.integration_id
    )
      throw Error('目标输入已变化，请先查询执行域')
    if (!applied.value.tenant_id || !applied.value.integration_id || !list.data.value)
      throw Error('请先查询目标执行域')
    await issueFormRef.value?.validate()
    const selected = [...scopes.value]
    if (!selected.length) throw Error('请至少选择一项执行权限')
    const input = { ...applied.value, scopes: selected }
    if (
      !(await confirm({
        title: '签发机构执行凭证',
        content: `为机构 ${input.tenant_id} 的接入 ${input.integration_id} 签发以下权限？\n${selected.map((item) => `${executionScopes[item] ?? item}（${item}）`).join('\n')}`,
        confirmText: '签发',
        cancelText: '取消'
      }))
    )
      return
    clearSecret()
    requireTarget()
    await command.execute(
      (key) =>
        checked('platform.institution_credentials.issue', () =>
          api.platformIntegration.issueCredential(input, key)
        ),
      (result) => {
        issued.value = result
        scopes.value = []
        // 成功后清空签发草稿，不把重置后的空权限显示为一次提交失败。
        void nextTick(() => issueFormRef.value?.clearValidate())
        emit('changed')
        message.success('机构执行凭证已签发')
        refreshAfterWrite()
      },
      requireTarget
    )
  })
}
/** 命令 principal_id 使用安全目录 id 主键，版本条件来自 credential_version。 */
function change(id: InstitutionCredential['id'], operation: 'rotate' | 'revoke') {
  if (targetChanged.value || list.loading.value || refreshRequired.value) return
  const row = list.data.value?.items.find((item) => item.id === id)
  if (!row) return
  const permission = `platform.institution_credentials.${operation}`
  if (!allowed(permission) || uncertain.value) return
  const requireTarget = scope.capture()
  void action.run(async () => {
    if (
      !(await confirm({
        title: operation === 'rotate' ? '轮换执行凭证' : '撤销执行凭证',
        content:
          operation === 'rotate'
            ? '轮换后旧凭证立即失效，新凭证仅在本次成功响应中交付。请安排接入方更新凭证，确认轮换？'
            : '撤销后旧凭证立即失效，接入方不能再用它发起请求。确认撤销？',
        confirmText: operation === 'rotate' ? '轮换' : '撤销',
        cancelText: '取消',
        danger: true
      }))
    )
      return
    clearSecret()
    const input = {
      tenant_id: row.tenant_id,
      integration_id: row.integration_id,
      principal_id: row.id,
      expected_revision: row.credential_version
    }
    requireTarget()
    if (operation === 'rotate') {
      await command.execute(
        (key) => checked(permission, () => api.platformIntegration.rotateCredential(input, key)),
        (result) => {
          issued.value = result
          emit('changed')
          message.success('执行凭证已轮换')
          refreshAfterWrite()
        },
        requireTarget
      )
    } else {
      await command.execute(
        (key) => checked(permission, () => api.platformIntegration.revokeCredential(input, key)),
        () => {
          emit('changed')
          message.success('执行凭证已撤销')
          refreshAfterWrite()
        },
        requireTarget
      )
    }
  })
}
/** 交付窗口关闭、身份变化或页面卸载立即丢弃秘密。 */
function clearSecret() {
  issued.value = undefined
}
watch(
  [
    () => allowed('platform.institution_credentials.issue'),
    () => allowed('platform.institution_credentials.rotate')
  ],
  clearSecret
)
watch(
  () => filters.tenant_id,
  () => {
    filters.integration_id = props.tenantId === filters.tenant_id ? (props.integrationId ?? '') : ''
  }
)
watch(
  () => [filters.tenant_id, filters.integration_id],
  () => {
    clearSecret()
    scopes.value = []
    list.invalidate()
    list.data.value = undefined
  }
)
function reconcile() {
  void action.run(() => command.reconcile())
}
onScopeDispose(clearSecret)
useUnsavedChanges({
  dirty: () => scopes.value.length > 0 || !!issued.value,
  busy: () => action.busy.value || uncertain.value
})
watch(
  () => [props.tenantId, props.integrationId],
  () => {
    clearSecret()
    list.invalidate()
    list.data.value = undefined
    refreshRequired.value = false
    if (props.tenantId) {
      filters.tenant_id = props.tenantId
      filters.integration_id = props.integrationId ?? ''
    } else if (props.integrationId) filters.integration_id = props.integrationId
    applied.value = { ...filters }
    page.value = 1
    if (filters.tenant_id && filters.integration_id) query()
  },
  { immediate: true, flush: 'post' }
)
</script>
<template>
  <AdminPage
    class="credential-issue-page"
    :embedded="props.embedded"
    title="机构执行凭证"
    description="在固定机构与接入域内签发执行身份，凭证独立于机构开通与定义授权。"
  >
    <ElForm
      class="admin-filters"
      inline
      :disabled="action.busy.value"
      @submit.prevent="query"
      @keydown.capture="guardFormEnter"
      ><ElFormItem v-if="!props.tenantId" label="机构标识" required
        ><EntitySelect
          v-model="filters.tenant_id"
          :load="tenantOptions.load"
          :resolve="tenantOptions.resolve"
          placeholder="搜索机构名称"
        /> </ElFormItem
      ><ElFormItem v-if="!props.integrationId" label="接入标识" required
        ><EntitySelect
          v-model="filters.integration_id"
          :load="integrationOptions.load"
          :resolve="integrationOptions.resolve"
          :disabled="!filters.tenant_id"
          placeholder="选择机构接入" /></ElFormItem
      ><ElButton native-type="submit" :loading="list.loading.value">查询执行域</ElButton></ElForm
    >
    <p v-if="applied.tenant_id && applied.integration_id" class="admin-field-help">
      当前已查询执行域：{{ applied.tenant_id }} / {{ applied.integration_id }}
    </p>
    <ElAlert
      v-if="targetChanged"
      title="执行域选择已变化，请查询后再签发；刷新安全目录仍核对原执行域。"
      type="info"
      :closable="false"
    />
    <FeedbackNotice
      v-if="action.error.value || list.error.value"
      :title="action.error.value || list.error.value"
      type="error"
      :closable="false"
    /><ElAlert
      v-if="uncertain"
      title="写入结果待核对。请核对原请求；目录刷新不会解除阻止，一次性秘密不能重新交付。"
      type="warning"
      :closable="false"
    /><ElButton v-if="uncertain" :loading="action.busy.value" @click="reconcile"
      >核对原请求</ElButton
    >
    <ElButton
      v-if="applied.tenant_id && applied.integration_id"
      @click="verify"
      :loading="list.loading.value"
      >刷新安全目录</ElButton
    >
    <p v-if="refreshRequired" class="admin-field-help" role="status">
      凭证操作已完成，但安全目录尚未更新；请刷新安全目录后继续操作。
    </p>
    <ElForm
      v-if="allowed('platform.institution_credentials.issue')"
      ref="issueFormRef"
      :model="{ scopes }"
      label-position="top"
      @submit.prevent="issue"
      @keydown.capture="guardFormEnter"
      ><ElFormItem
        label="执行权限"
        prop="scopes"
        :rules="[{ type: 'array', required: true, min: 1, message: '请至少选择一项执行权限' }]"
        ><ElSelect
          v-model="scopes"
          multiple
          filterable
          aria-describedby="execution-scopes-help"
          :disabled="action.busy.value || uncertain || refreshRequired"
          ><ElOption
            v-for="(label, scope) in executionScopes"
            :key="scope"
            :value="scope"
            :label="`${label} · ${scope}`" /></ElSelect
      ></ElFormItem>
      <p id="execution-scopes-help">
        显式选择机构执行权限。签发目标以已查询的机构和接入标识为准，权限由服务端执行白名单校验。
      </p>
      <ElButton
        native-type="submit"
        type="primary"
        :disabled="!list.data.value || uncertain || targetChanged || refreshRequired"
        :loading="action.busy.value"
        >签发执行凭证</ElButton
      ></ElForm
    >
    <ElTable
      :data="list.data.value?.items ?? []"
      v-loading="list.loading.value"
      row-key="id"
      :empty-text="
        targetChanged
          ? '机构或接入选择已变化，请查询新的执行域'
          : !applied.tenant_id || !applied.integration_id
            ? '选择机构和接入并查询后，查看执行凭证'
            : list.loading.value
              ? '正在读取执行凭证…'
              : list.error.value
                ? '安全目录尚未读取，请查看上方错误'
                : '当前执行域没有执行凭证'
      "
      ><ElTableColumn
        prop="principal_id"
        label="执行主体标识"
        min-width="180"
        show-overflow-tooltip
      /><ElTableColumn prop="api_key_prefix" label="凭证前缀" min-width="160" /><ElTableColumn
        prop="status"
        label="状态"
        width="110"
        ><template #default="{ row }">{{ statusLabel(row.status) }}</template></ElTableColumn
      ><ElTableColumn prop="credential_version" label="凭证版本" width="110" /><ElTableColumn
        label="执行权限"
        min-width="220"
        ><template #default="{ row }">{{ row.scopes.join('、') }}</template></ElTableColumn
      ><ElTableColumn label="操作" width="150" fixed="right"
        ><template #default="{ row }"
          ><ElButton
            v-if="allowed('platform.institution_credentials.rotate')"
            link
            :disabled="
              action.busy.value ||
              list.loading.value ||
              targetChanged ||
              refreshRequired ||
              uncertain ||
              row.status !== 'active'
            "
            @click="change(row.id, 'rotate')"
            >轮换</ElButton
          ><ElButton
            v-if="allowed('platform.institution_credentials.revoke')"
            link
            type="danger"
            :disabled="
              action.busy.value ||
              list.loading.value ||
              targetChanged ||
              refreshRequired ||
              uncertain ||
              row.status !== 'active'
            "
            @click="change(row.id, 'revoke')"
            >撤销</ElButton
          ></template
        ></ElTableColumn
      ></ElTable
    >
    <ElPagination
      v-if="list.data.value"
      layout="total, prev, pager, next"
      :total="list.data.value.total"
      :current-page="page"
      :page-size="20"
      :disabled="refreshRequired"
      @current-change="
        (value: number) => {
          page = value
          refreshAfterWrite()
        }
      "
    />
    <ElDialog
      :model-value="!!issued"
      title="一次性交付执行凭证"
      :close-on-click-modal="false"
      @update:model-value="clearSecret"
      @closed="clearSecret"
      ><ElAlert
        title="秘密只在本次成功写响应提供；关闭后立即从页面清除。"
        type="warning"
        :closable="false"
      /><ElForm v-if="issued?.api_key && issued.signing_secret" label-position="top"
        ><ElFormItem label="API Key"
          ><ElInput
            :model-value="issued.api_key"
            type="password"
            show-password
            readonly
            autocomplete="off" /></ElFormItem
        ><ElFormItem label="签名秘密"
          ><ElInput
            :model-value="issued.signing_secret"
            type="password"
            show-password
            readonly
            autocomplete="off" /></ElFormItem
      ></ElForm>
      <p v-else>这是安全重读回执，没有可再次交付的秘密；需要时请显式轮换。</p>
      <template #footer
        ><ElButton @click="clearSecret">已安全保存并关闭</ElButton></template
      ></ElDialog
    >
  </AdminPage>
</template>

<style scoped>
:global(body:has(.credential-issue-page) .admin-confirm) {
  display: inline-flex;
  flex-direction: column;
  max-height: calc(100dvh - 32px);
}
:global(body:has(.credential-issue-page) .admin-confirm .el-message-box__content) {
  min-height: 0;
  overflow-y: auto;
}
</style>
