<script setup lang="ts">
import FeedbackNotice from '../../feedback/FeedbackNotice.vue'
import { HttpClientError } from '../../utils/http'
import { computed, nextTick, onMounted, onScopeDispose, ref, useId } from 'vue'
import AdminPage from '../../admin/AdminPage.vue'
import FoundationOptionSelect from '../FoundationOptionSelect.vue'
import type { FoundationOption, FoundationOptionRequest } from '../optionTypes'
import { confirm } from '../../feedback'
import { useUnsavedChanges } from '../../admin/leave'
import { formatDateTime } from '../../utils/datetime'
import FieldTip from '../../components/FieldTip.vue'
import { vDescribedBy } from '../../directives/describedBy'
import SecurityChallenge from './SecurityChallenge.vue'
import type {
  SecurityApi,
  SecurityAuthApi,
  SecurityAuthTokens,
  SecurityDevice,
  SecurityStatus
} from './api'
const props = defineProps<{
  request: FoundationOptionRequest
  api: SecurityApi
  authApi: SecurityAuthApi
  permissions: string[]
  onAuthentication: (tokens: SecurityAuthTokens) => void | Promise<void>
  onLogout: () => void
}>()
const status = ref<SecurityStatus>(),
  devices = ref<SecurityDevice[]>(),
  loading = ref(false),
  saving = ref(false),
  confirming = ref(false),
  error = ref(''),
  statusError = ref(''),
  devicesError = ref(''),
  policyError = ref(''),
  proof = ref(''),
  codes = ref<string[]>([]),
  all = ref(false),
  policyBaseline = ref<boolean>(),
  memberId = ref(''),
  notice = ref('')
const busy = computed(() => loading.value || saving.value || confirming.value)
const selectedMember = ref<FoundationOption>()
const canManagePolicy = computed(() =>
  props.permissions.includes('foundation.security.policy.manage')
)
const policyDirty = computed(
  () => policyBaseline.value !== undefined && all.value !== policyBaseline.value
)
const formTipId = useId()
let generation = 0
let disposed = false
const publicError = (cause: unknown, fallback: string) => {
  return cause instanceof Error ? cause.message : fallback
}

/** 库中保留原始 UA；界面只展示可读的设备与客户端名称。 */
function deviceLabel(value: string) {
  if (!value) return '未知设备'
  if (!/Mozilla|Chrome|Safari|Firefox|PowerShell/i.test(value)) return value
  const os = /Windows/i.test(value)
    ? 'Windows'
    : /Macintosh|Mac OS X/i.test(value)
      ? 'macOS'
      : /Android/i.test(value)
        ? 'Android'
        : /iPhone|iPad/i.test(value)
          ? 'iOS'
          : /Linux/i.test(value)
            ? 'Linux'
            : ''
  const client = /PowerShell/i.test(value)
    ? '命令行客户端'
    : /HeadlessChrome/i.test(value)
      ? '自动化浏览器'
      : /Edg\//i.test(value)
        ? 'Edge'
        : /Chrome\//i.test(value)
          ? 'Chrome'
          : /Firefox\//i.test(value)
            ? 'Firefox'
            : /Safari\//i.test(value)
              ? 'Safari'
              : '浏览器'
  return [os, client].filter(Boolean).join(' · ')
}

/** 状态、设备和策略保留各自的读取失败；刷新不覆盖尚未保存的策略草稿。 */
async function load() {
  const request = ++generation
  loading.value = true
  statusError.value = ''
  devicesError.value = ''
  policyError.value = ''
  try {
    const current = await props.authApi.status()
    if (disposed || request !== generation) return
    status.value = current
    if (current.mfa_state !== 'full') {
      devices.value = undefined
      policyBaseline.value = undefined
      return
    }
    const [deviceResult, policyResult] = await Promise.allSettled([
      props.api.sessions(),
      canManagePolicy.value ? props.api.policy() : Promise.resolve(undefined)
    ])
    for (const result of [deviceResult, policyResult]) {
      if (result.status === 'rejected' && result.reason instanceof HttpClientError)
        result.reason.claimFeedback()
    }
    if (disposed || request !== generation) return
    if (deviceResult.status === 'fulfilled') devices.value = deviceResult.value
    else devicesError.value = publicError(deviceResult.reason, '登录设备读取失败')
    if (policyResult.status === 'fulfilled') {
      if (policyResult.value) {
        const dirty = policyDirty.value
        policyBaseline.value = policyResult.value.require_all_mfa
        if (!dirty) all.value = policyBaseline.value
      } else policyBaseline.value = undefined
    } else policyError.value = publicError(policyResult.reason, '安全策略读取失败')
  } catch (cause) {
    if (cause instanceof HttpClientError) cause.claimFeedback()
    if (!disposed && request === generation)
      statusError.value = publicError(cause, '安全状态读取失败')
  } finally {
    if (!disposed && request === generation) loading.value = false
  }
}

/** 写入成功和后续读取失败分别展示；当前设备退出后不再发出状态查询。 */
async function act(work: () => Promise<unknown>, logout = false, successText = '操作已完成') {
  if (busy.value || disposed) return
  saving.value = true
  error.value = ''
  notice.value = ''
  try {
    await work()
    if (disposed) return
    if (logout) {
      await props.onLogout()
      return
    }
    notice.value = successText
    await load()
  } catch (cause) {
    if (cause instanceof HttpClientError) cause.claimFeedback()
    if (!disposed) error.value = publicError(cause, '操作失败')
  } finally {
    if (!disposed) saving.value = false
  }
}

/** 确认期间固定目标；取消、关闭或页面销毁不派发写请求。 */
async function ask(title: string, content: string, confirmText: string) {
  if (busy.value || disposed) return false
  const trigger = document.activeElement instanceof HTMLElement ? document.activeElement : undefined
  confirming.value = true
  try {
    const accepted = await confirm({
      title,
      content,
      confirmText,
      cancelText: '取消',
      danger: true
    })
    return accepted && !disposed
  } catch (cause) {
    if (cause instanceof HttpClientError) cause.claimFeedback()
    if (!disposed) error.value = publicError(cause, '确认操作失败')
    return false
  } finally {
    if (!disposed) {
      confirming.value = false
      await nextTick()
      if (!disposed && trigger?.isConnected && !trigger.matches(':disabled')) trigger.focus()
    }
  }
}

async function stepUp() {
  if (!proof.value) return
  await act(
    async () => {
      const tokens = await props.authApi.verify(proof.value)
      if (disposed) return
      await props.onAuthentication(tokens)
      if (!disposed) proof.value = ''
    },
    false,
    '敏感操作验证已通过'
  )
}
async function bound(tokens: SecurityAuthTokens) {
  if (disposed) return
  await props.onAuthentication(tokens)
  if (!disposed) await load()
}

/** 只撤销确认的设备；当前设备与其他设备的退出影响必须明确。 */
async function revoke(device: SecurityDevice) {
  const target = {
    id: device.id,
    current: device.current,
    label: deviceLabel(device.device_label)
  }
  if (
    !(await ask(
      '撤销登录设备',
      `撤销“${target.label}”？${target.current ? '这是当前设备，确认后将退出登录。' : '该设备将不能继续访问，当前设备保持登录。'}`,
      '撤销设备'
    ))
  )
    return
  await act(
    () => props.api.revoke(target.id),
    target.current,
    `设备“${target.label}”的登录授权已撤销`
  )
}

/** 草稿只是本地预览；发布前确认影响，返回的服务器值才成为已保存状态。 */
async function savePolicy() {
  if (!policyDirty.value || !canManagePolicy.value || policyBaseline.value === undefined) return
  const target = all.value
  if (
    !(await ask(
      '保存机构安全策略',
      target
        ? '开启后，当前机构所有未绑定成员需完成验证器绑定。确认发布该策略？'
        : '关闭全员强制绑定要求；已经自愿绑定的账号仍需二次验证。确认发布该策略？',
      '保存策略'
    ))
  )
    return
  if (!canManagePolicy.value) return
  await act(
    async () => {
      const saved = await props.api.setPolicy(target)
      if (!disposed) {
        policyBaseline.value = saved.require_all_mfa
        all.value = saved.require_all_mfa
      }
    },
    false,
    '机构安全策略已保存'
  )
}
function cancelPolicy() {
  if (busy.value || policyBaseline.value === undefined) return
  all.value = policyBaseline.value
}
async function disable() {
  if (await ask('关闭自愿绑定', '关闭后将撤销已有设备并退出登录，需要重新登录。', '关闭并退出'))
    await act(() => props.authApi.disable(), true)
}
async function regenerate() {
  if (
    !(await ask('重新生成恢复码', '旧恢复码将立即失效，新恢复码只在本次操作中展示。', '重新生成'))
  )
    return
  await act(
    async () => {
      const result = await props.api.regenerateCodes()
      if (!disposed) codes.value = result
    },
    false,
    '恢复码已更新，请在离开前保存'
  )
}
async function manageMember(action: 'invite' | 'revoke' | 'reset') {
  const target = memberId.value
  const permission = `foundation.security.${action === 'invite' ? 'invitation' : action === 'revoke' ? 'session' : 'mfa'}.manage`
  if (!target || !props.permissions.includes(permission)) return
  const title =
    action === 'invite'
      ? '发送登记邮箱邀请'
      : action === 'revoke'
        ? '撤销成员全部设备'
        : '重置遗失验证器'
  const impact =
    action === 'invite'
      ? '仅发送到成员登记邮箱，受理不表示已送达。'
      : action === 'revoke'
        ? '该成员所有设备会失效，需要重新登录。'
        : '该成员验证器与恢复码将重置，已有设备会失效；全员策略保持不变。'
  if (
    !(await ask(
      title,
      `成员 ${selectedMember.value?.name || target}（${target}）：${impact}`,
      title
    ))
  )
    return
  if (!props.permissions.includes(permission)) return
  await act(
    () =>
      action === 'invite'
        ? props.api.invite(target)
        : action === 'revoke'
          ? props.api.revokeMember(target)
          : props.api.resetMemberMfa(target),
    false,
    action === 'invite'
      ? '激活邀请已受理，尚不代表邮件已送达'
      : action === 'revoke'
        ? '该成员全部设备的登录授权已撤销'
        : '该成员的验证器和恢复码已重置，已有设备的登录授权已撤销'
  )
}
useUnsavedChanges({
  dirty: () => policyDirty.value || !!proof.value || !!codes.value.length,
  busy: () => saving.value || confirming.value,
  discard: () => {
    if (policyBaseline.value !== undefined) all.value = policyBaseline.value
    proof.value = ''
    codes.value = []
  }
})
onScopeDispose(() => {
  disposed = true
  generation++
  proof.value = ''
  codes.value = []
})
onMounted(load)
</script>
<template>
  <AdminPage class="security-settings" title="账号与安全" :aria-busy="busy">
    <template #extra><ElButton :disabled="busy" @click="load">刷新安全状态</ElButton></template>
    <FeedbackNotice v-if="error" :title="error" type="error" :closable="false" role="alert" />
    <FeedbackNotice
      v-if="statusError"
      :title="statusError"
      type="error"
      :closable="false"
      role="alert"
    />
    <FeedbackNotice v-if="notice" :title="notice" type="success" :closable="false" role="status" />
    <p v-if="notice && (statusError || devicesError || policyError)" class="security-hint">
      操作已经完成，最新状态读取失败。请刷新查询，不要重复提交该操作。
    </p>
    <ElCard shadow="never" v-if="status">
      <h2>二次验证</h2>
      <p>
        验证器：{{ status.enabled ? '已绑定' : '未绑定' }}；{{
          status.required ? '此账号必须启用' : '可自愿启用'
        }}；剩余恢复码 {{ status.recovery_codes_remaining }} 个
      </p>
      <SecurityChallenge
        v-if="!status.enabled"
        v-described-by="formTipId + '-enrollment'"
        :api="authApi"
        state="enrollment"
        @complete="bound"
      />
      <FieldTip v-if="!status.enabled" :id="formTipId + '-enrollment'">
        先创建密钥并添加到验证器，再输入六位验证码完成绑定；新恢复码仅在本次生成时展示，请在离开前保存。
      </FieldTip>
      <template v-else>
        <ElForm label-position="top" @submit.prevent="stepUp">
          <ElFormItem label="敏感操作二次验证">
            <ElInput
              v-model.trim="proof"
              v-described-by="formTipId + '-proof'"
              :aria-describedby="formTipId + '-proof'"
              autocomplete="one-time-code"
              placeholder="六位验证码或未使用的恢复码"
              :disabled="busy"
            />
            <FieldTip :id="formTipId + '-proof'"
              >服务端确认后，敏感操作授权有效五分钟；输入只保留在当前页面。</FieldTip
            >
          </ElFormItem>
          <ElButton type="primary" native-type="submit" :loading="saving" :disabled="busy || !proof"
            >验证并授权 5 分钟</ElButton
          >
        </ElForm>
        <div class="security-actions">
          <ElButton v-if="!status.required" type="danger" :disabled="busy" @click="disable"
            >关闭自愿绑定</ElButton
          >
          <ElButton :disabled="busy" @click="regenerate">重新生成恢复码</ElButton>
        </div>
        <template v-if="codes.length">
          <ElAlert
            title="请立即保存。旧恢复码已失效，离开后无法再次查看。"
            type="warning"
            :closable="false"
          />
          <pre>{{ codes.join('\n') }}</pre>
          <ElButton @click="codes = []">已保存，隐藏</ElButton>
        </template>
      </template>
    </ElCard>
    <ElCard shadow="never" v-else-if="loading"><ElSkeleton :rows="3" animated /></ElCard>
    <ElCard shadow="never">
      <h2>登录设备</h2>
      <FeedbackNotice
        v-if="devicesError"
        :title="devicesError"
        type="error"
        :closable="false"
        role="alert"
      />
      <ElTable
        v-if="devices !== undefined"
        :data="devices"
        v-loading="loading"
        empty-text="暂无登录设备"
      >
        <ElTableColumn label="设备" min-width="180"
          ><template #default="{ row }"
            ><span :title="row.device_label">{{ deviceLabel(row.device_label) }}</span></template
          ></ElTableColumn
        >
        <ElTableColumn label="登录时间" min-width="180" show-overflow-tooltip
          ><template #default="{ row }"
            ><span :title="row.created_at">{{ formatDateTime(row.created_at) }}</span></template
          ></ElTableColumn
        >
        <ElTableColumn label="会话" min-width="110"
          ><template #default="{ row }">{{
            row.current ? '当前设备' : '其他设备'
          }}</template></ElTableColumn
        >
        <ElTableColumn label="操作" width="100" fixed="right"
          ><template #default="{ row }"
            ><ElButton type="danger" link :disabled="busy" @click="revoke(row as SecurityDevice)"
              >撤销</ElButton
            ></template
          ></ElTableColumn
        >
      </ElTable>
      <ElSkeleton v-else-if="loading" :rows="3" animated />
      <p v-else-if="status && status.mfa_state !== 'full'">请先完成账号认证，再读取登录设备。</p>
      <p v-else>登录设备尚未读取成功。可使用上方“刷新安全状态”重新查询。</p>
    </ElCard>
    <ElCard shadow="never" v-if="canManagePolicy">
      <h2>机构安全策略</h2>
      <FeedbackNotice
        v-if="policyError"
        :title="policyError"
        type="error"
        :closable="false"
        role="alert"
      />
      <template v-if="policyBaseline !== undefined">
        <p class="policy-status" role="status">
          已保存策略：{{ policyBaseline ? '已开启全员验证器要求' : '已关闭全员验证器要求'
          }}<span v-if="policyDirty">；有未保存修改</span>
        </p>
        <ElCheckbox
          v-model="all"
          v-described-by="formTipId + '-policy'"
          :disabled="busy"
          :aria-describedby="formTipId + '-policy'"
          >所有成员必须绑定验证器</ElCheckbox
        >
        <FieldTip :id="formTipId + '-policy'"
          >保存后要求所有成员绑定；关闭后，已绑定的账号仍保留二次验证。</FieldTip
        >
        <div class="security-actions">
          <ElButton
            type="primary"
            :loading="saving"
            :disabled="busy || !policyDirty"
            @click="savePolicy"
            >保存策略</ElButton
          >
          <ElButton :disabled="busy || !policyDirty" @click="cancelPolicy">取消修改</ElButton>
        </div>
      </template>
      <p v-else>机构安全策略尚未读取成功，当前无法编辑。可使用上方“刷新安全状态”重新查询。</p>
    </ElCard>
    <ElCard
      shadow="never"
      v-if="
        permissions.includes('foundation.security.invitation.manage') ||
        permissions.includes('foundation.security.session.manage') ||
        permissions.includes('foundation.security.mfa.manage')
      "
    >
      <h2>成员安全管理</h2>
      <ElForm label-position="top" @submit.prevent>
        <ElFormItem label="成员">
          <FoundationOptionSelect
            v-model="memberId"
            :request="request"
            path="foundation/options/members?purpose=security"
            label="选择管理成员"
            v-described-by="formTipId + '-member'"
            :aria-describedby="formTipId + '-member'"
            placeholder="按姓名或账号搜索成员"
            :disabled="busy"
            @selected="selectedMember = $event"
          />
          <FieldTip :id="formTipId + '-member'"
            >邀请发至登记邮箱；撤销和重置只影响选中的成员。</FieldTip
          >
        </ElFormItem>
      </ElForm>
      <div class="security-actions">
        <ElButton
          v-if="permissions.includes('foundation.security.invitation.manage')"
          :disabled="
            !memberId ||
            busy ||
            !selectedMember ||
            !['pending', 'invited'].includes(selectedMember.status)
          "
          @click="manageMember('invite')"
          >发送登记邮箱邀请</ElButton
        >
        <ElButton
          v-if="permissions.includes('foundation.security.session.manage')"
          type="danger"
          :disabled="!memberId || busy"
          @click="manageMember('revoke')"
          >撤销成员全部设备</ElButton
        >
        <ElButton
          v-if="permissions.includes('foundation.security.mfa.manage')"
          type="danger"
          :disabled="!memberId || busy"
          @click="manageMember('reset')"
          >重置遗失验证器</ElButton
        >
      </div>
      <p
        v-if="
          selectedMember &&
          !['pending', 'invited'].includes(selectedMember.status) &&
          permissions.includes('foundation.security.invitation.manage')
        "
        class="admin-field-help"
      >
        该成员已激活或停用，无需发送激活邀请。
      </p>
    </ElCard>
  </AdminPage>
</template>
<style scoped>
.security-settings {
  display: grid;
  gap: 16px;
  min-width: 0;
}
.security-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
}
.security-actions {
  margin-top: 12px;
  gap: 8px;
}
.security-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}
.security-settings :deep(.el-card__body) {
  min-width: 0;
}
.security-settings :deep(.el-form-item__content) {
  min-width: 0;
}
.security-hint {
  margin: 0;
  color: var(--el-text-color-secondary);
}
pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font-size: 12px;
}
.security-settings :deep(h2) {
  margin-top: 0;
  font-size: var(--font-size-lg, 18px);
}
</style>
