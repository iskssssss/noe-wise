<template>
  <AdminPage title="工具部署" description="绑定工具身份与机构服务实例，新执行跟随工具当前发布版本。"
    ><template #extra
      ><ElButton type="primary" @click="openCreate" :disabled="busy || creationUnknown"
        >新增部署</ElButton
      ></template
    >
    <section v-if="creationUnknown" class="admin-card" role="status">
      创建结果尚未确认，原输入已保留。请只读核对部署标识“{{ form.deploymentKey }}”，不要重复创建；
      查到或查不到当前部署均不能证明原请求结果。
      <ElButton :disabled="busy" @click="checkDeployment">查看当前部署</ElButton>
      <ElButton :disabled="busy" @click="open = true">查看原创建输入</ElButton>
      <p v-if="checkedDeployment">
        当前部署：{{ checkedDeployment.deploymentKey }} · 工具版本
        {{ checkedDeployment.toolVersionId }} · {{ checkedDeployment.providerType }} /
        {{ checkedDeployment.providerRef }} · {{ checkedDeployment.environment }} · 修订
        {{ checkedDeployment.revision }}
      </p>
    </section>
    <div class="admin-toolbar admin-filters">
      <ElInput
        v-model="query"
        clearable
        aria-label="搜索工具部署"
        placeholder="搜索部署、工具或提供方引用"
      />
      <ElSelect v-model="environment" clearable aria-label="运行环境" placeholder="全部环境">
        <ElOption label="开发" value="DEVELOPMENT" /><ElOption label="测试" value="TEST" /><ElOption
          label="生产"
          value="PRODUCTION"
        />
      </ElSelect>
      <ElSelect v-model="deploymentStatus" clearable aria-label="部署状态" placeholder="全部状态">
        <ElOption label="启用" value="ACTIVE" /><ElOption label="停用" value="INACTIVE" />
      </ElSelect>
      <ElButton :loading="loading || busy" :disabled="busy" @click="refresh">刷新</ElButton>
      <span class="admin-muted" role="status">{{
        loading
          ? '正在读取部署…'
          : !directoryLoaded
            ? '部署目录尚未完整读取'
            : `${filtered.length} 项部署`
      }}</span>
    </div>
    <ElTable
      table-layout="auto"
      :data="filtered"
      v-loading="loading"
      :empty-text="
        !directoryLoaded && loadError
          ? '部署列表读取失败，请点击刷新重试'
          : !directoryLoaded
            ? '部署目录尚未完整读取'
            : query || environment || deploymentStatus
              ? '没有匹配的部署，请调整筛选条件'
              : '尚无部署，先为已发布工具绑定提供方'
      "
      ><ElTableColumn
        min-width="200"
        show-overflow-tooltip
        prop="deploymentKey"
        label="部署标识"
      /><ElTableColumn min-width="200" show-overflow-tooltip label="工具（跟随当前发布）"
        ><template #default="{ row }"
          ><div>{{ versionNames[row.toolVersionId] || '版本信息不可用' }}</div>
          <small class="muted">{{ row.toolVersionId }}</small></template
        ></ElTableColumn
      ><ElTableColumn min-width="140" show-overflow-tooltip label="提供方"
        ><template #default="{ row }">{{ statusLabel(row.providerType) }}</template></ElTableColumn
      ><ElTableColumn
        min-width="200"
        show-overflow-tooltip
        prop="providerRef"
        label="引用"
      /><ElTableColumn min-width="140" show-overflow-tooltip label="环境"
        ><template #default="{ row }">{{ statusLabel(row.environment) }}</template></ElTableColumn
      ><ElTableColumn width="128" show-overflow-tooltip label="状态"
        ><template #default="{ row }">{{ statusLabel(row.status) }}</template></ElTableColumn
      ><ElTableColumn width="80" class-name="admin-table-actions" label="操作"
        ><template #default="{ row }"
          ><ElButton
            v-permission="[
              row.status === 'ACTIVE'
                ? 'foundation.capabilities.cancel'
                : 'foundation.capabilities.run'
            ]"
            link
            :disabled="busy"
            @click="toggle(row as CapabilityDeployment)"
            >{{ row.status === 'ACTIVE' ? '停用' : '启用' }}</ElButton
          ></template
        ></ElTableColumn
      ></ElTable
    ><ElDrawer
      v-model="open"
      title="新增工具部署"
      :before-close="requestClose"
      append-to-body
      direction="rtl"
      :close-on-click-modal="false"
      :close-on-press-escape="!busy"
      :show-close="!busy"
      class="admin-drawer"
      size="680px"
      ><p v-if="creationUnknown" role="status">创建结果尚未确认，以下原输入仅供核对。</p>
      <ElForm
        ref="formRef"
        :disabled="busy || creationUnknown"
        :model="form"
        :rules="rules"
        label-position="top"
        scroll-to-error
        @submit.prevent="create"
        @keydown.capture="guardFormEnter"
        ><ElFormItem label="部署标识" prop="deploymentKey"
          ><ElInput
            v-described-by="formTipId + '-field-0'"
            :aria-describedby="formTipId + '-field-0'"
            v-model="form.deploymentKey"
          />
          <FieldTip :id="formTipId + '-field-0'"
            >填写工具部署的稳定标识，用于能力挂载引用。</FieldTip
          > </ElFormItem
        ><ElFormItem label="工具（跟随当前发布）" prop="toolVersionId"
          ><ElSelect
            v-described-by="formTipId + '-field-1'"
            :aria-describedby="formTipId + '-field-1'"
            v-model="form.toolVersionId"
            filterable
            ><ElOption v-for="v in versions" :key="v.id" :value="v.id" :label="v.name"
          /></ElSelect>
          <FieldTip :id="formTipId + '-field-1'"
            >选择已发布工具；新执行跟随其当前发布版本。</FieldTip
          > </ElFormItem
        ><ElDivider content-position="left">连接与运行环境</ElDivider
        ><ElFormItem label="提供方"
          ><ElSelect
            v-described-by="formTipId + '-field-2'"
            :aria-describedby="formTipId + '-field-2'"
            v-model="form.providerType"
            ><ElOption value="PLATFORM" label="平台内置工具" /><ElOption
              value="MCP"
              label="MCP" /><ElOption value="CONNECTOR" label="连接器"
          /></ElSelect>
          <FieldTip :id="formTipId + '-field-2'"
            >选择实际提供工具的平台能力或 MCP 连接器。</FieldTip
          > </ElFormItem
        ><ElFormItem label="提供方引用" prop="providerRef"
          ><EntitySelect
            v-described-by="'deployment-provider-help'"
            aria-describedby="deployment-provider-help"
            v-model="form.providerRef"
            :disabled="busy || creationUnknown"
            :options="providerOptions"
            :placeholder="form.providerType === 'PLATFORM' ? '选择平台工具' : '选择连接器实例'"
          />
          <p id="deployment-provider-help" class="admin-field-help">
            平台工具从内置目录选择；MCP 和连接器从当前机构的实例目录选择。切换提供方会清空原引用。
          </p></ElFormItem
        ><ElAlert
          v-if="!providerOptions.length"
          title="当前提供方没有可用引用，请先配置对应连接器或选择其他提供方。"
          type="warning"
          :closable="false"
        />
        <ElFormItem label="运行环境"
          ><ElSelect
            v-described-by="formTipId + '-field-4'"
            :aria-describedby="formTipId + '-field-4'"
            v-model="form.environment"
            ><ElOption value="DEVELOPMENT" label="开发" /><ElOption
              value="TEST"
              label="测试" /><ElOption value="PRODUCTION" label="生产"
          /></ElSelect>
          <FieldTip :id="formTipId + '-field-4'">选择该部署使用的运行环境。</FieldTip>
        </ElFormItem></ElForm
      ><template #footer
        ><ElButton @click="requestClose" :disabled="busy">取消</ElButton
        ><ElButton
          v-permission="['foundation.capabilities.create']"
          type="primary"
          :loading="busy"
          :disabled="creationUnknown"
          @click="create"
          >创建</ElButton
        ></template
      ></ElDrawer
    ></AdminPage
  >
</template>
<script setup lang="ts">
import { vDescribedBy } from '@skillforge/shared/directives/describedBy'
import { HttpClientError } from '@skillforge/shared/utils/http'

import FieldTip from '@skillforge/shared/components/FieldTip.vue'

import { message } from '@skillforge/shared/feedback'
import { guardFormEnter } from '@skillforge/shared/admin'
import { computed, onMounted, onScopeDispose, reactive, ref, watch, useId } from 'vue'
import {
  AdminPage,
  EntitySelect,
  statusLabel,
  useResource,
  useTask,
  useDrawerClose,
  useUnsavedChanges
} from '@skillforge/shared/admin'
import type { FormInstance, FormRules } from 'element-plus'
import {
  api,
  type CapabilityDeployment,
  type CreateCapabilityDeploymentInput,
  type CapabilityVersion
} from '../../api'
const {
  data,
  loading,
  error: loadError,
  reload,
  invalidate
} = useResource(() => api.capabilities.deployments())
let disposed = false
onScopeDispose(() => {
  disposed = true
})
const formRef = ref<FormInstance>()
const rules: FormRules = {
  deploymentKey: [
    { required: true, whitespace: true, message: '请输入部署标识', trigger: 'blur' },
    { max: 128, message: '部署标识不能超过 128 个字符', trigger: 'blur' }
  ],
  toolVersionId: [
    {
      type: 'string',
      pattern: /^[1-9]\d*$/,
      required: true,
      message: '请选择已发布工具版本',
      trigger: 'change'
    }
  ],
  providerRef: [{ required: true, message: '请选择提供方引用', trigger: 'change' }]
}
const versionNames = ref<Record<string, string>>({})
const query = ref(''),
  environment = ref(''),
  deploymentStatus = ref('')
const filtered = computed(() =>
  (data.value ?? []).filter(
    (item) =>
      (item.deploymentKey + (versionNames.value[item.toolVersionId] ?? '') + item.providerRef)
        .toLowerCase()
        .includes(query.value.trim().toLowerCase()) &&
      (!environment.value || item.environment === environment.value) &&
      (!deploymentStatus.value ||
        (deploymentStatus.value === 'ACTIVE' ? item.status === 'ACTIVE' : item.status !== 'ACTIVE'))
  )
)
const platformTools = ref<Awaited<ReturnType<typeof api.capabilities.platformTools>>>([])
const connectors = ref<Awaited<ReturnType<typeof api.connectors.instances>>['items']>([])
const directoryLoaded = ref(false)
onMounted(refresh)
async function refresh() {
  confirmedChange.value = ''
  const deployments = await reload()
  if (deployments === undefined) return
  directoryLoaded.value = true
  await run(async () => {
    const ids = [...new Set(deployments.map((d) => d.toolVersionId))]
    const result = await Promise.all(ids.map((id) => api.capabilities.getVersion(id)))
    versionNames.value = Object.fromEntries(
      result.map((v) => [v.id, `${v.name} / ${v.versionKey}`])
    )
  })
}
const creationUnknown = ref(false)
const checkedDeployment = ref<CapabilityDeployment>()
const { busy, error, run } = useTask(),
  open = ref(false),
  saved = ref(''),
  confirmedChange = ref(''),
  versions = ref<CapabilityVersion[]>([]),
  form = reactive<CreateCapabilityDeploymentInput>({
    deploymentKey: '',
    toolVersionId: '',
    providerType: 'MCP',
    providerRef: '',
    environment: 'DEVELOPMENT'
  })
useUnsavedChanges({
  dirty: () => creationUnknown.value,
  leaveMessage: () =>
    '创建结果尚未确认，离开后将失去本页保留的原输入。请先记录部署标识并核对，勿重复创建。'
})
watch(error, (value) => {
  if (value) message.error(value)
})
watch(loadError, (value) => {
  if (!value) return
  if (confirmedChange.value)
    message.warning(`${confirmedChange.value}；列表刷新失败。请点击“刷新”，不必重复操作。`)
  else message.error(`工具部署列表读取失败：${value}。请点击“刷新”重试。`)
})
const providerOptions = computed(() =>
  form.providerType === 'PLATFORM'
    ? platformTools.value.map((tool) => ({
        id: tool.name,
        label: tool.name,
        description: tool.description
      }))
    : connectors.value
        .filter(
          (connector) =>
            form.providerType !== 'MCP' || connector.executor_type?.toLowerCase() === 'mcp'
        )
        .map((connector) => ({ id: connector.instance_id, label: connector.name }))
)
watch(
  () => form.providerType,
  () => {
    form.providerRef = ''
  }
)
const { requestClose } = useDrawerClose({
  open: () => open.value,
  busy: () => busy.value,
  dirty: () => open.value && !creationUnknown.value && JSON.stringify(form) !== saved.value,
  close: () => {
    open.value = false
  }
})
async function openCreate() {
  if (creationUnknown.value) return
  await run(async () => {
    const [defs, tools, instances] = await Promise.all([
      api.capabilities.definitions('TOOL', 'PUBLISHED'),
      api.capabilities.platformTools(),
      api.connectors.instances()
    ])
    platformTools.value = tools
    connectors.value = instances.items
    versions.value = await Promise.all(
      defs
        .filter((d) => d.defaultVersionId)
        .map((d) => api.capabilities.getVersion(d.defaultVersionId!))
    )
    Object.assign(form, {
      deploymentKey: '',
      toolVersionId: '',
      providerType: 'MCP',
      providerRef: '',
      environment: 'DEVELOPMENT'
    })
    saved.value = JSON.stringify(form)
    open.value = true
  })
}
async function create() {
  if (creationUnknown.value) return
  if (!(await formRef.value?.validate().catch(() => false))) return
  await run(async () => {
    if (!form.deploymentKey.trim() || !form.providerRef.trim() || !form.toolVersionId)
      throw Error('请填写部署标识，选择已发布工具和对应的连接器或平台工具')
    if (!providerOptions.value.some((option) => option.id === form.providerRef))
      throw Error('所选连接器或平台工具已不可用，请从当前列表重新选择')
    let created: CapabilityDeployment
    try {
      created = await api.capabilities.createDeployment({ ...form })
    } catch (cause) {
      // 这些业务码在创建链校验或事务回滚时产生，不能用状态码替代写入结果。
      if (
        cause instanceof HttpClientError &&
        (cause.code === 'CAPABILITY_VERSION_NOT_PUBLISHED' ||
          cause.code === 'CAPABILITY_DEPLOYMENT_INVALID')
      )
        throw cause
      if (cause instanceof HttpClientError && cause.code === 'CAPABILITY_DUPLICATE') {
        cause.claimFeedback()
        throw Error('部署标识已存在，请更换标识后创建。')
      }
      if (cause instanceof HttpClientError) cause.claimFeedback()
      if (!disposed) {
        creationUnknown.value = true
        message.warning('工具部署创建结果尚未确认，请只读核对原标识，不要重复创建。')
      }
      return
    }
    if (disposed) return
    invalidate()
    data.value = [
      ...(data.value ?? []).filter((item) => item.deploymentKey !== created.deploymentKey),
      created
    ]
    const version = versions.value.find((v) => v.id === form.toolVersionId)
    if (version) versionNames.value[version.id] = `${version.name} / ${version.versionKey}`
    open.value = false
    confirmedChange.value = '工具部署已创建'
    if ((await reload()) !== undefined && !disposed) {
      directoryLoaded.value = true
      message.success(confirmedChange.value)
    }
  })
}
async function checkDeployment() {
  await run(async () => {
    checkedDeployment.value = undefined
    const current = await api.capabilities.getDeployment(form.deploymentKey.trim(), {
      silent: true
    })
    if (!disposed) checkedDeployment.value = current
  })
}
async function toggle(row: CapabilityDeployment) {
  await run(async () => {
    confirmedChange.value = ''
    const wasActive = row.status === 'ACTIVE'
    const updated = await (
      wasActive ? api.capabilities.stopDeployment : api.capabilities.startDeployment
    )(row.deploymentKey, row.revision)
    data.value = data.value?.map((item) =>
      item.deploymentKey === updated.deploymentKey ? updated : item
    )
    confirmedChange.value = wasActive ? '工具部署已停止，新调用不可使用' : '工具部署已启动'
    if ((await reload()) !== undefined) {
      directoryLoaded.value = true
      message.success(confirmedChange.value)
    }
  })
}

const formTipId = useId()
</script>
