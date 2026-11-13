<script setup lang="ts">
import FeedbackNotice from '../../feedback/FeedbackNotice.vue'
import { HttpClientError } from '../../utils/http'
import { computed, reactive, ref, useId, watch } from 'vue'
import {
  ElAlert,
  ElButton,
  ElCheckbox,
  ElDrawer,
  ElEmpty,
  ElForm,
  ElFormItem,
  ElInput,
  ElInputNumber,
  ElOption,
  ElRadioGroup,
  ElRadioButton,
  ElSelect,
  ElSwitch,
  ElTable,
  ElTableColumn,
  ElTabs,
  ElTabPane,
  type FormInstance
} from 'element-plus'
import AdminPage from '../../admin/AdminPage.vue'
import FoundationOptionSelect from '../FoundationOptionSelect.vue'
import {
  guardFormEnter,
  useDrawerClose,
  useResource,
  useTask,
  useUnsavedChanges
} from '../../admin'
import { confirm, message } from '../../feedback'
import { formatDateTime } from '../../utils/datetime'
import FieldTip from '../../components/FieldTip.vue'
import { vDescribedBy } from '../../directives/describedBy'
import {
  deliveryLabels,
  type AnnouncementItem,
  type DeliveryItem,
  type InboxItem,
  type MailboxView,
  type MessagingPageResult,
  type MessagingRequest,
  type TemplateView
} from './messaging'

/** 当前账号与权限变化时宿主更新props；组件清理旧读取与所有凭据草稿。 */
const props = defineProps<{
  request: MessagingRequest
  basePath: string
  permissions: string[]
  identityKey?: string
}>()
const allowed = (action: string) => props.permissions.includes(`foundation.messaging.${action}`)
const formTipId = useId()
const tabs = computed(() =>
  [
    { name: 'inbox', label: '我的消息', permission: 'inbox' },
    { name: 'announcements', label: '公告', permission: 'read' },
    { name: 'templates', label: '通知模板', permission: 'configure' },
    { name: 'mailbox', label: 'SMTP 邮箱', permission: 'configure' },
    { name: 'deliveries', label: '邮件投递', permission: 'operate' }
  ].filter((tab) => allowed(tab.permission) || (tab.name === 'announcements' && allowed('publish')))
)
const tab = ref('inbox'),
  cursor = ref(''),
  previous = ref<string[]>([]),
  unreadOnly = ref(false)
function shortId(value: string) {
  return value.length > 14 ? `${value.slice(0, 6)}…${value.slice(-4)}` : value
}
const query = () => `?limit=20${cursor.value ? `&cursor=${encodeURIComponent(cursor.value)}` : ''}`
const inbox = useResource((signal) =>
  props.request<MessagingPageResult<InboxItem>>(
    'GET',
    `${props.basePath}/inbox${query()}&unread=${unreadOnly.value}`,
    undefined,
    { signal, silent: true }
  )
)
const announcements = useResource((signal) =>
  props.request<MessagingPageResult<AnnouncementItem>>(
    'GET',
    `${props.basePath}/announcements${query()}`,
    undefined,
    { signal, silent: true }
  )
)
const deliveries = useResource((signal) =>
  props.request<MessagingPageResult<DeliveryItem>>(
    'GET',
    `${props.basePath}/deliveries${query()}`,
    undefined,
    { signal, silent: true }
  )
)
const templates = useResource((signal) =>
  props.request<TemplateView[]>('GET', `${props.basePath}/templates`, undefined, {
    signal,
    silent: true
  })
)
const mailbox = useResource((signal) =>
  props.request<MailboxView>('GET', `${props.basePath}/mailbox`, undefined, {
    signal,
    silent: true
  })
)
const unread = useResource((signal) =>
  props.request<number>('GET', `${props.basePath}/unread`, undefined, { signal, silent: true })
)
const resources = { inbox, announcements, deliveries, templates, mailbox }
const active = computed(() => resources[tab.value as keyof typeof resources])
const paged = computed(() =>
  tab.value === 'inbox'
    ? inbox.data.value
    : tab.value === 'announcements'
      ? announcements.data.value
      : tab.value === 'deliveries'
        ? deliveries.data.value
        : undefined
)
const task = useTask(),
  edit = ref<'announcement' | 'template' | ''>(''),
  form = ref<FormInstance>()
const announcement = reactive({
  title: '',
  body: '',
  audience: 'all',
  recipient_ids: [] as string[],
  email: false,
  deduplication_key: ''
})
const template = reactive({ key: '', subject: '', body: '' })
const templateVariables = computed(() => [
  ...new Set(
    Array.from(
      `${template.subject} ${template.body}`.matchAll(/\{\{([a-z][a-z0-9_]*)\}\}/g),
      (match) => match[1]!
    )
  )
])
const mailboxForm = reactive({
  enabled: false,
  host: '',
  port: 587,
  username: '',
  password: '',
  from_address: '',
  tls_mode: 'starttls'
})
const editBaseline = ref(''),
  mailboxBaseline = ref(''),
  reconcileId = ref(''),
  evidence = ref(''),
  disposition = ref('rejected')
const editValue = () => JSON.stringify(edit.value === 'announcement' ? announcement : template)
const drawer = useDrawerClose({
  open: () => !!edit.value,
  busy: () => task.busy.value,
  dirty: () => editValue() !== editBaseline.value,
  close: () => {
    edit.value = ''
  }
})
const mailboxDirty = computed(
  () => !!mailboxBaseline.value && JSON.stringify(mailboxForm) !== mailboxBaseline.value
)
useUnsavedChanges({
  dirty: () => mailboxDirty.value,
  busy: () => task.busy.value,
  discard: () => {
    mailboxForm.password = ''
  }
})
const recipientPath = computed(
  () =>
    `${props.basePath.replace(/\/messaging$/, '')}/options/${announcement.audience === 'members' ? 'members?purpose=messaging' : `recipient-groups?kind=${announcement.audience === 'departments' ? 'department' : 'role'}`}`
)
function changeAudience() {
  if (announcement.recipient_ids.length) announcement.recipient_ids = []
}
const rules = {
  title: [{ required: true, message: '请输入标题', trigger: 'blur' }],
  body: [{ required: true, message: '请输入正文', trigger: 'blur' }],
  key: [{ required: true, message: '请输入模板键', trigger: 'blur' }],
  subject: [{ required: true, message: '请输入标题模板', trigger: 'blur' }]
}
let identityGeneration = 0

/** 写请求绑定发起账号和端点；身份变化后忽略迟到成功/失败，只保留原服务端事实。 */
async function write(
  permission: 'inbox' | 'publish' | 'configure' | 'operate',
  path: string,
  body: unknown,
  method: 'POST' | 'PUT' = 'POST'
) {
  if (!allowed(permission)) return false
  const generation = identityGeneration,
    target = `${props.basePath}${path}`
  return task.run(async () => {
    try {
      await props.request(method, target, body)
      return generation === identityGeneration
    } catch (failure) {
      if (generation !== identityGeneration) {
        if (failure instanceof HttpClientError) failure.claimFeedback()
        return false
      }
      throw failure
    }
  })
}

/** 仅刷新当前业务区域；读取失败保留旧数据并提供重试。 */
function reload() {
  if (tab.value === 'announcements' && !allowed('read')) return
  void active.value?.reload()
  if (allowed('inbox')) void unread.reload()
}
/** 页签变更重新从服务端第一页读取，不复用另一作用域结果。 */
watch(tab, () => {
  cursor.value = ''
  previous.value = []
  reload()
})
/** 后端真实游标分页，页码不由本地估计。 */
function next() {
  if (!paged.value?.next_cursor || active.value.loading.value) return
  previous.value.push(cursor.value)
  cursor.value = paged.value.next_cursor
  reload()
}
/** 回到已经访问的上一页。 */
function back() {
  if (!previous.value.length || active.value.loading.value) return
  cursor.value = previous.value.pop() ?? ''
  reload()
}
/** 未读条件变更只读取，不重放写入。 */
function filterUnread() {
  cursor.value = ''
  previous.value = []
  reload()
}
/** 创建公告时生成稳定幂等键；失败保持该键与输入供安全手动重试。 */
function newAnnouncement() {
  if (!allowed('publish')) return
  Object.assign(announcement, {
    title: '',
    body: '',
    audience: 'all',
    recipient_ids: [],
    email: false,
    deduplication_key: crypto.randomUUID()
  })
  edit.value = 'announcement'
  editBaseline.value = editValue()
  form.value?.clearValidate()
}
/** 编辑当前模板定义后发布不可变新版本。 */
function newTemplate(value?: TemplateView) {
  if (!allowed('configure')) return
  Object.assign(
    template,
    value
      ? {
          key: value.key,
          subject: value.subject,
          body: value.body
        }
      : { key: '', subject: '', body: '' }
  )
  edit.value = 'template'
  editBaseline.value = editValue()
  form.value?.clearValidate()
}
/** 提交绑定点击时的内容快照，保存期间不允许二次提交或编辑。 */
async function publish() {
  const generation = identityGeneration
  if (!edit.value || !allowed(edit.value === 'announcement' ? 'publish' : 'configure')) return
  if (task.busy.value || !(await form.value?.validate().catch(() => false))) return
  if (generation !== identityGeneration) return
  const kind = edit.value
  const body =
    kind === 'announcement'
      ? { ...announcement, recipient_ids: [...announcement.recipient_ids] }
      : {
          ...template,
          variable_names: [...templateVariables.value]
        }
  if (
    kind === 'announcement' &&
    announcement.audience !== 'all' &&
    !announcement.recipient_ids.length
  ) {
    message.warning('请选择收件范围')
    return
  }
  const result = await write(
    kind === 'announcement' ? 'publish' : 'configure',
    `/${kind === 'announcement' ? 'announcements' : 'templates'}/publish`,
    body
  )
  if (result) {
    edit.value = ''
    message.success(kind === 'announcement' ? '公告已发布，通知已受理' : '模板版本已发布')
    reload()
  }
}
/** 本人已读写入完成后才更新未读与列表。 */
async function markRead(id: string) {
  const result = await write('inbox', '/read', { id })
  if (result) reload()
}
/** 撤回确认绑定实际公告身份，已发送邮件无法撤销。 */
async function withdraw(value: AnnouncementItem) {
  if (!allowed('publish')) return
  const generation = identityGeneration
  if (
    !(await confirm({
      title: '撤回公告',
      content: `撤回「${value.title}」后站内消息将隐藏，尚未派发的邮件将取消。邮件服务器已接受的邮件无法撤回，投递记录会保留。`,
      confirmText: '撤回公告'
    })) ||
    generation !== identityGeneration
  )
    return
  const result = await write('publish', '/announcements/withdraw', { id: value.id })
  if (result) {
    message.success('公告已撤回')
    reload()
  }
}
/** 仅确定未接受状态提供人工重试，服务端仍检查实际状态。 */
async function retry(value: DeliveryItem) {
  if (!allowed('operate') || !['blocked', 'rejected'].includes(value.status)) return
  const generation = identityGeneration
  if (
    !(await confirm({
      title: '重新受理邮件',
      content:
        '此邮件已确认未被邮件服务器接受。重新受理会使用当前启用的邮箱配置，受理后仍需查看投递结果。',
      confirmText: '重新受理'
    })) ||
    generation !== identityGeneration
  )
    return
  const result = await write('operate', '/deliveries/retry', { id: value.id })
  if (result) {
    message.success('邮件已重新受理')
    reload()
  }
}
/** 开始核验时绑定本次投递并清空旧证据，避免复用另一投递的核验结局。 */
function openReconciliation(value: DeliveryItem) {
  reconcileId.value = value.id
  evidence.value = ''
  disposition.value = 'rejected'
}
/** unknown必须填写实际SMTP核验证据，不在前端提供盲重发。 */
async function reconcile() {
  if (!reconcileId.value || !evidence.value.trim()) return
  const result = await write('operate', '/deliveries/reconcile', {
    id: reconcileId.value,
    status: disposition.value,
    evidence: evidence.value
  })
  if (result) {
    reconcileId.value = ''
    evidence.value = ''
    message.success('核验事实已记录')
    reload()
  }
}
/** 邮箱写成功后清空新密码；后续读取失败与写入成功分别反馈。 */
async function saveMailbox() {
  const snapshot = { ...mailboxForm, password: mailboxForm.password || null }
  const result = await write('configure', '/mailbox', snapshot, 'PUT')
  if (result) {
    mailboxForm.password = ''
    mailboxBaseline.value = JSON.stringify(mailboxForm)
    message.success('SMTP 配置已保存')
    void mailbox.reload()
  }
}
/** 服务器配置仅在没有本地草稿时建立表单基线，刷新不会覆盖密码输入。 */
watch(
  () => mailbox.data.value,
  (value) => {
    if (!value || mailboxDirty.value) return
    Object.assign(mailboxForm, {
      enabled: value.enabled,
      host: value.host ?? '',
      port: value.port ?? 587,
      username: value.username ?? '',
      password: '',
      from_address: value.from_address ?? '',
      tls_mode: value.tls_mode ?? 'starttls'
    })
    mailboxBaseline.value = JSON.stringify(mailboxForm)
  }
)
/** 身份/权限变化立即取消迟到请求并清空所有正文和密码草稿。 */
watch(
  () => [props.basePath, props.identityKey, props.permissions.join('|')],
  () => {
    identityGeneration++
    task.error.value = ''
    Object.values(resources).forEach((resource) => {
      resource.invalidate()
      resource.data.value = undefined
    })
    unread.invalidate()
    unread.data.value = undefined
    edit.value = ''
    reconcileId.value = ''
    evidence.value = ''
    mailboxBaseline.value = ''
    cursor.value = ''
    previous.value = []
    Object.assign(mailboxForm, {
      enabled: false,
      host: '',
      port: 587,
      username: '',
      password: '',
      from_address: '',
      tls_mode: 'starttls'
    })
    Object.assign(announcement, { title: '', body: '', recipient_ids: [] })
    Object.assign(template, { key: '', subject: '', body: '' })
    if (!tabs.value.some((item) => item.name === tab.value)) tab.value = tabs.value[0]?.name ?? ''
    reload()
  },
  { immediate: true }
)
</script>

<template>
  <AdminPage title="消息与公告">
    <template #extra
      ><ElButton :loading="active?.loading.value" @click="reload">刷新</ElButton></template
    >
    <ElEmpty v-if="!tabs.length" description="当前账号没有消息操作权限" />
    <template v-else>
      <FeedbackNotice
        v-if="active?.error.value"
        type="error"
        :title="active.error.value"
        :closable="false"
        ><ElButton @click="reload">重试读取</ElButton></FeedbackNotice
      >
      <FeedbackNotice
        v-if="task.error.value"
        type="error"
        :title="task.error.value"
        :closable="false"
      />
      <FeedbackNotice
        v-if="unread.error.value"
        type="error"
        :title="'未读数量读取失败：' + unread.error.value"
        :closable="false"
      />
      <ElTabs v-model="tab"
        ><ElTabPane v-for="item in tabs" :key="item.name" :name="item.name" :label="item.label"
      /></ElTabs>
      <template v-if="tab === 'inbox'">
        <div class="admin-actions">
          <span>未读：{{ unread.data.value ?? '—' }}</span
          ><ElCheckbox
            v-model="unreadOnly"
            v-described-by="formTipId + '-unread'"
            @change="filterUnread"
            >只看未读</ElCheckbox
          >
        </div>
        <FieldTip :id="formTipId + '-unread'">
          勾选后仅显示本人未读消息，取消显示全部；切换会回到第一页，不会自动标记已读。
        </FieldTip>
        <ElTable
          v-loading="inbox.loading.value"
          :data="inbox.data.value?.items ?? []"
          row-key="id"
          :empty-text="
            inbox.error.value
              ? '消息列表尚未读取成功'
              : inbox.loading.value
                ? '正在读取消息…'
                : unreadOnly
                  ? '暂无未读消息'
                  : '暂无消息'
          "
        >
          <ElTableColumn label="消息" min-width="190"
            ><template #default="scope"
              ><div>{{ scope.row.title }}</div>
              <div class="admin-field-help">
                {{ formatDateTime(scope.row.created_at) }} ·
                {{ scope.row.read_at ? '已读' : '未读' }}
              </div></template
            ></ElTableColumn
          ><ElTableColumn prop="body" label="正文" min-width="240" show-overflow-tooltip />
          <ElTableColumn label="操作" width="112" fixed="right"
            ><template #default="scope"
              ><ElButton
                v-if="!scope.row.read_at"
                :disabled="task.busy.value"
                @click="markRead(scope.row.id)"
                >标记已读</ElButton
              ></template
            ></ElTableColumn
          >
        </ElTable>
      </template>
      <template v-else-if="tab === 'announcements'">
        <div class="admin-actions">
          <ElButton
            v-if="allowed('publish')"
            type="primary"
            :disabled="task.busy.value"
            @click="newAnnouncement"
            >发布公告</ElButton
          >
        </div>
        <ElTable
          v-if="allowed('read')"
          v-loading="announcements.loading.value"
          :data="announcements.data.value?.items ?? []"
          row-key="id"
          :empty-text="
            announcements.error.value
              ? '公告列表尚未读取成功'
              : announcements.loading.value
                ? '正在读取公告…'
                : '暂无公告'
          "
        >
          <ElTableColumn label="公告" min-width="190"
            ><template #default="scope"
              ><div>{{ scope.row.title }}</div>
              <div class="admin-field-help">
                {{ scope.row.recipients }} 人 ·
                {{ scope.row.status === 'published' ? '已发布' : '已撤回' }} ·
                {{ formatDateTime(scope.row.created_at) }}
              </div></template
            ></ElTableColumn
          ><ElTableColumn
            prop="body"
            label="正文"
            min-width="220"
            show-overflow-tooltip
          /><ElTableColumn v-if="allowed('publish')" label="操作" width="96" fixed="right"
            ><template #default="scope"
              ><ElButton
                v-if="scope.row.status === 'published'"
                :disabled="task.busy.value"
                @click="withdraw(scope.row as AnnouncementItem)"
                >撤回</ElButton
              ></template
            ></ElTableColumn
          >
        </ElTable>
      </template>
      <template v-else-if="tab === 'templates'">
        <div class="admin-actions">
          <ElButton type="primary" :disabled="task.busy.value" @click="newTemplate()"
            >发布模板</ElButton
          >
        </div>
        <ElTable
          v-loading="templates.loading.value"
          :data="templates.data.value ?? []"
          row-key="id"
          :empty-text="
            templates.error.value
              ? '通知模板尚未读取成功'
              : templates.loading.value
                ? '正在读取通知模板…'
                : '暂无通知模板，可点击“发布模板”创建'
          "
        >
          <ElTableColumn prop="key" label="模板键" min-width="145" /><ElTableColumn
            prop="subject"
            label="标题模板"
            min-width="170"
            show-overflow-tooltip
          /><ElTableColumn prop="body" label="正文模板" min-width="190" show-overflow-tooltip />
          <ElTableColumn label="操作" width="100" fixed="right"
            ><template #default="scope"
              ><ElButton :disabled="task.busy.value" @click="newTemplate(scope.row as TemplateView)"
                >编辑发布</ElButton
              ></template
            ></ElTableColumn
          >
        </ElTable>
      </template>
      <template v-else-if="tab === 'mailbox'">
        <ElAlert
          v-if="mailbox.data.value"
          type="info"
          :closable="false"
          :title="
            mailbox.data.value?.credential_configured
              ? '凭据已配置；新密码留空保持原值。启用后的新通知采用当前配置版本。'
              : '凭据未配置；默认不发送邮件，邮件通知保留为暂不可投递。'
          "
        />
        <ElForm
          label-position="top"
          class="messaging-form"
          :disabled="task.busy.value"
          @keydown="guardFormEnter"
        >
          <ElFormItem label="启用 SMTP"
            ><ElSwitch v-model="mailboxForm.enabled" />
            <p class="messaging-tip">
              启用前必须完整配置；停用后所有尚未派发邮件暂停。
            </p></ElFormItem
          >
          <ElFormItem label="SMTP 主机"
            ><ElInput v-model="mailboxForm.host" maxlength="256" placeholder="smtp.example.com" />
            <p class="messaging-tip">填写可信邮件服务主机名，不包含协议或路径。</p></ElFormItem
          >
          <ElFormItem label="端口"
            ><ElInputNumber v-model="mailboxForm.port" :min="1" :max="65535" :precision="0" />
            <p class="messaging-tip">端口范围 1 至 65535，以服务商配置为准。</p></ElFormItem
          >
          <ElFormItem label="SMTP 账号"
            ><ElInput v-model="mailboxForm.username" maxlength="256" autocomplete="off" />
            <p class="messaging-tip">只用于邮件服务认证。</p></ElFormItem
          >
          <ElFormItem label="新密码"
            ><ElInput
              v-model="mailboxForm.password"
              type="password"
              show-password
              autocomplete="new-password"
            />
            <p class="messaging-tip">只写入加密凭据；留空保持已有密码，查询不回显。</p></ElFormItem
          >
          <ElFormItem label="发件邮箱"
            ><ElInput
              v-model="mailboxForm.from_address"
              maxlength="256"
              placeholder="notification@example.com"
            />
            <p class="messaging-tip">填写单个已授权发件地址。</p></ElFormItem
          >
          <ElFormItem label="TLS 模式"
            ><ElSelect v-model="mailboxForm.tls_mode"
              ><ElOption label="STARTTLS（升级加密）" value="starttls" /><ElOption
                label="TLS（连接即加密）"
                value="tls"
            /></ElSelect>
            <p class="messaging-tip">强制加密并验证证书主机名。</p></ElFormItem
          >
          <ElButton
            type="primary"
            :loading="task.busy.value"
            :disabled="!mailboxDirty"
            @click="saveMailbox"
            >保存配置</ElButton
          ><span v-if="mailboxDirty">有未保存修改</span>
        </ElForm>
      </template>
      <template v-else-if="tab === 'deliveries'">
        <ElTable
          v-loading="deliveries.loading.value"
          :data="deliveries.data.value?.items ?? []"
          row-key="id"
          :empty-text="
            deliveries.error.value
              ? '邮件投递记录尚未读取成功'
              : deliveries.loading.value
                ? '正在读取邮件投递记录…'
                : '暂无邮件投递记录'
          "
        >
          <ElTableColumn label="状态 / 投递" min-width="150"
            ><template #default="scope"
              ><div>{{ deliveryLabels[scope.row.status] ?? scope.row.status }}</div>
              <div class="admin-field-help" :title="scope.row.id">
                {{ shortId(scope.row.id) }} · 尝试 {{ scope.row.attempts }} 次
              </div></template
            ></ElTableColumn
          >
          <ElTableColumn prop="error_code" label="处理原因" min-width="180" show-overflow-tooltip />
          <ElTableColumn label="更新时间" min-width="140"
            ><template #default="scope">{{
              formatDateTime(scope.row.updated_at)
            }}</template></ElTableColumn
          >
          <ElTableColumn label="操作" width="112" fixed="right"
            ><template #default="scope"
              ><ElButton
                v-if="['blocked', 'rejected'].includes(scope.row.status)"
                :disabled="task.busy.value"
                @click="retry(scope.row as DeliveryItem)"
                >重新受理</ElButton
              ><ElButton
                v-if="scope.row.status === 'unknown'"
                :disabled="task.busy.value"
                @click="openReconciliation(scope.row as DeliveryItem)"
                >记录核验</ElButton
              ></template
            ></ElTableColumn
          >
        </ElTable>
        <ElForm
          v-if="reconcileId"
          label-position="top"
          :disabled="task.busy.value"
          @keydown="guardFormEnter"
        >
          <ElAlert
            type="warning"
            title="投递结果未知。请先核对邮件服务器记录，再记录实际结果；核验前不能重新发送。"
            :closable="false"
          />
          <ElFormItem label="核验结局"
            ><ElSelect v-model="disposition"
              ><ElOption label="SMTP 已接受" value="accepted" /><ElOption
                label="明确未接受"
                value="rejected" /></ElSelect
          ></ElFormItem>
          <ElFormItem label="核验证据"
            ><ElInput v-model="evidence" type="textarea" maxlength="1024" :rows="3" />
            <p class="messaging-tip">
              填写服务端记录或工单引用，不填写密码或一次性激活码。
            </p></ElFormItem
          >
          <ElButton
            type="primary"
            :loading="task.busy.value"
            :disabled="!evidence.trim()"
            @click="reconcile"
            >记录核验事实</ElButton
          ><ElButton :disabled="task.busy.value" @click="reconcileId = ''">取消</ElButton>
        </ElForm>
      </template>
      <div v-if="paged" class="admin-actions">
        <span>当前 {{ paged.items.length }} 条</span
        ><ElButton :disabled="!previous.length || active.loading.value" @click="back"
          >上一页</ElButton
        ><ElButton :disabled="!paged.has_more || active.loading.value" @click="next"
          >下一页</ElButton
        >
      </div>
    </template>
    <ElDrawer
      :model-value="!!edit"
      :title="edit === 'announcement' ? '发布公告' : '发布通知模板'"
      size="min(680px, calc(100vw - 24px))"
      :before-close="() => drawer.requestClose()"
      :close-on-click-modal="false"
    >
      <ElForm
        ref="form"
        :model="edit === 'announcement' ? announcement : template"
        :rules="rules"
        label-position="top"
        :disabled="task.busy.value"
        @keydown="guardFormEnter"
      >
        <template v-if="edit === 'announcement'">
          <ElFormItem label="标题" prop="title"
            ><ElInput v-model="announcement.title" maxlength="240" />
          </ElFormItem>
          <ElFormItem label="正文" prop="body"
            ><ElInput v-model="announcement.body" type="textarea" :rows="8" />
            <p class="messaging-tip">纯文本；发布后正文冻结，只允许撤回。</p></ElFormItem
          >
          <ElFormItem label="收件范围"
            ><ElRadioGroup
              v-model="announcement.audience"
              @change="changeAudience"
              class="audience-options"
              ><ElRadioButton value="all">全部有效成员</ElRadioButton
              ><ElRadioButton value="members">指定成员</ElRadioButton
              ><ElRadioButton value="departments">指定部门</ElRadioButton
              ><ElRadioButton value="roles">指定角色</ElRadioButton></ElRadioGroup
            >
            <p class="messaging-tip">
              仅包含当前账号域与租户内的有效成员，发布时冻结范围。
            </p></ElFormItem
          >
          <ElFormItem v-if="announcement.audience !== 'all'" label="收件人或范围">
            <FoundationOptionSelect
              v-model="announcement.recipient_ids"
              :request="request"
              :path="recipientPath"
              label="选择公告收件范围"
              multiple
            />
            <p class="messaging-tip" role="status">
              已选择 {{ announcement.recipient_ids.length }} 项；仅包含当前授权范围内的有效成员。
            </p>
          </ElFormItem>
          <ElFormItem label="同时邮件通知"
            ><ElSwitch v-model="announcement.email" />
            <p class="messaging-tip">
              仅向已登记邮箱的成员受理邮件；未配置 SMTP 时保留暂不可投递状态。
            </p></ElFormItem
          >
        </template>
        <template v-else>
          <ElFormItem label="模板键" prop="key"
            ><ElInput v-model="template.key" maxlength="128" />
            <p class="messaging-tip">小写字母开头，可包含数字、点、下划线和连字符。</p></ElFormItem
          >
          <ElFormItem label="标题模板" prop="subject"
            ><ElInput v-model="template.subject" maxlength="240" />
            <p class="messaging-tip">纯文本，可使用双花括号变量占位符。</p></ElFormItem
          >
          <ElFormItem label="正文模板" prop="body"
            ><ElInput v-model="template.body" type="textarea" :rows="8" />
            <p class="messaging-tip">纯文本；发布新版本只影响新通知。</p></ElFormItem
          >
          <p class="admin-field-help" role="status">
            自动识别变量：{{
              templateVariables.join('、') || '无'
            }}。使用双花括号包裹小写变量名，无需另行填写。
          </p>
        </template>
      </ElForm>
      <template #footer
        ><ElButton :disabled="task.busy.value" @click="drawer.requestClose">取消</ElButton
        ><ElButton type="primary" :loading="task.busy.value" @click="publish"
          >发布</ElButton
        ></template
      >
    </ElDrawer>
  </AdminPage>
</template>

<style scoped>
.messaging-form {
  max-width: 48rem;
}
.audience-options {
  flex-wrap: wrap;
  gap: 4px;
}
.messaging-tip {
  color: var(--el-text-color-secondary);
  margin: var(--el-form-label-font-size) 0 0;
  font-size: var(--el-font-size-small);
  width: 100%;
}
</style>
