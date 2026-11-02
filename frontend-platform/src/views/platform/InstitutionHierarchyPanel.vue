<script setup lang="ts">
import FeedbackNotice from '@skillforge/shared/feedback/FeedbackNotice.vue'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import {
  guardFormEnter,
  statusLabel,
  useDrawerClose,
  useResource,
  useTask,
  useUnsavedChanges
} from '@skillforge/shared/admin'
import { HttpClientError } from '@skillforge/shared/utils/http'
import { confirm, message } from '@skillforge/shared/feedback'
import { api } from '../../api'
import { useAuthStore } from '../../stores/auth'
import { useActionPermissions } from '../../composables/actionPermissions'
import type {
  InstitutionRelation,
  InstitutionRelationChange,
  RelationOperation
} from '../../api/institutionRelations'

import { useInstitutionPanelScope } from './institutionPanelScope'
const props = defineProps<{ tenantId?: string; embedded?: boolean }>()
const emit = defineEmits<{ changed: [] }>()
const scope = useInstitutionPanelScope(() => props.tenantId ?? '')
const auth = useAuthStore()
const { allowed, checked } = useActionPermissions()
const action = useTask()
const open = ref(false)
const target = ref<InstitutionRelation>()
const operation = ref<RelationOperation>('attach')
const form = reactive({ parent_tenant_id: '' })
const baseline = ref('')
const conflict = ref(false)
const pending = ref<{
  operation: RelationOperation
  input: InstitutionRelationChange
  key: string
  requireTarget: () => void
}>()
const names = { attach: '挂接上级', move: '迁移上级', detach: '解除上级' } as const
const directory = useResource(async (signal) => {
  if (!allowed('platform.institution_relations.read')) throw Error('当前账号没有机构关系读取权限')
  const first = await api.institutionRelations.list(1, { signal, silent: true })
  const rows = [...first.items]
  for (let page = 2; rows.length < first.total; page++) {
    const next = await api.institutionRelations.list(page, { signal, silent: true })
    if (next.tree_revision !== first.tree_revision || !next.items.length)
      throw Error('机构树在读取期间已变化，请重新刷新机构树')
    rows.push(...next.items)
  }
  return { items: rows, tree_revision: first.tree_revision }
})
const rows = computed(() => directory.data.value?.items ?? [])
const scopedRows = computed(() =>
  props.tenantId
    ? rows.value.filter(
        (row) => row.tenant_id === props.tenantId || row.parent_tenant_id === props.tenantId
      )
    : rows.value
)
const tree = computed(() => {
  const nodes = new Map(
    scopedRows.value.map((row) => [
      row.tenant_id,
      { ...row, children: [] as InstitutionRelation[] }
    ])
  )
  const roots = [] as InstitutionRelation[]
  for (const row of nodes.values()) {
    const parent = row.parent_tenant_id && nodes.get(row.parent_tenant_id)
    if (parent) parent.children.push(row)
    else roots.push(row)
  }
  return roots
})
const latest = computed(() => rows.value.find((row) => row.tenant_id === target.value?.tenant_id))
const search = ref('')
const visibleTree = computed(() => {
  const text = search.value.trim().toLocaleLowerCase()
  if (!text) return tree.value
  return scopedRows.value.filter((row) =>
    `${row.name} ${row.tenant_id}`.toLocaleLowerCase().includes(text)
  )
})
function institutionName(id: string | null) {
  return id ? (rows.value.find((row) => row.tenant_id === id)?.name ?? id) : '独立根'
}
const parentChoices = computed(() => {
  const excluded = new Set<string>(target.value ? [target.value.tenant_id] : [])
  let changed = true
  while (changed) {
    changed = false
    for (const row of rows.value) {
      if (
        row.parent_tenant_id &&
        excluded.has(row.parent_tenant_id) &&
        !excluded.has(row.tenant_id)
      ) {
        excluded.add(row.tenant_id)
        changed = true
      }
    }
  }
  return rows.value.filter(
    (row) => !excluded.has(row.tenant_id) && row.tenant_id !== target.value?.parent_tenant_id
  )
})
const canChange = computed(() => allowed(`platform.institution_relations.${operation.value}`))
const close = useDrawerClose({
  open: () => open.value,
  busy: () => action.busy.value,
  dirty: () => !!pending.value || form.parent_tenant_id !== baseline.value,
  close: () => {
    open.value = false
  }
})
/** 刷新只更新权威目录，不覆盖抽屉中的目标修订与未提交输入。 */
async function refresh() {
  const result = await directory.reload()
  if (target.value && latest.value && target.value.revision !== latest.value.revision)
    conflict.value = true
  return result
}
/** 关系动作绑定明确机构，读取范围不冒充child/原父/新父的写入授权。 */
function begin(tenantId: string, value: RelationOperation) {
  const row = rows.value.find((item) => item.tenant_id === tenantId)
  if (!row) return
  if (!allowed(`platform.institution_relations.${value}`) || action.busy.value || pending.value)
    return
  target.value = { ...row }
  operation.value = value
  form.parent_tenant_id = ''
  baseline.value = ''
  conflict.value = false
  action.error.value = ''
  open.value = true
}
/** CAS后仅显式采用最新关系基数，保留所选新上级，并要求重新确认。 */
function useCurrent() {
  if (!latest.value || pending.value || action.busy.value) return
  if (operation.value === 'detach' && !latest.value.parent_tenant_id) {
    action.error.value = '该机构现已是独立根，无需再次解除，请关闭后核对机构树'
    return
  }
  target.value = { ...latest.value }
  if (operation.value !== 'detach')
    operation.value = latest.value.parent_tenant_id ? 'move' : 'attach'
  conflict.value = false
  action.error.value = ''
}
async function dispatch(command: NonNullable<typeof pending.value>) {
  command.requireTarget()
  try {
    await checked(`platform.institution_relations.${command.operation}`, () =>
      api.institutionRelations.change(command.operation, command.input, command.key)
    )
  } catch (reason) {
    if (reason instanceof HttpClientError && reason.status === 409) {
      pending.value = undefined
      conflict.value = true
      await refresh()
    } else if (
      !(reason instanceof HttpClientError) ||
      (reason.status >= 400 && reason.status < 500 && reason.status !== 408)
    ) {
      pending.value = undefined
    }
    throw reason
  }
  command.requireTarget()
  pending.value = undefined
  baseline.value = form.parent_tenant_id
  open.value = false
  message.success('机构关系操作已确认，当前关系以刷新后的机构树为准')
  await refresh()
  emit('changed')
}
/** 确认后重新检查权限、目标及修订；未知操作固定原键和快照，不能被编辑成新意图。 */
function save() {
  if (!canChange.value || conflict.value || pending.value || action.busy.value || !target.value)
    return
  const requireTarget = scope.capture()
  void action.run(async () => {
    const row = { ...target.value! },
      selected = operation.value
    const parent = selected === 'detach' ? null : form.parent_tenant_id
    if (selected !== 'detach' && (!parent || parent === row.tenant_id))
      throw Error('请选择其他机构作为新上级')
    const input = {
      child_tenant_id: row.tenant_id,
      parent_tenant_id: parent,
      expected_revision: row.revision
    }
    if (
      !(await confirm({
        title: names[selected],
        content: `${row.name}（${row.tenant_id}），关系修订 ${row.revision}：${row.parent_tenant_id ?? '独立根'} → ${parent ?? '独立根'}。依赖旧关系的授权会重新判定，迁回不会恢复旧授权；机构成员、业务和费用归属保持原机构。`,
        confirmText: names[selected],
        cancelText: '返回编辑',
        danger: selected !== 'attach'
      }))
    )
      return
    if (
      target.value?.tenant_id !== row.tenant_id ||
      target.value.revision !== row.revision ||
      operation.value !== selected ||
      form.parent_tenant_id !== (parent ?? '') ||
      conflict.value
    )
      throw Error('关系输入已变化，请重新确认')
    requireTarget()
    if (!canChange.value) throw Error('当前账号没有该关系操作权限')
    const command = { operation: selected, input, key: crypto.randomUUID(), requireTarget }
    pending.value = command
    await dispatch(command)
  })
}
/** 显式查询原命令回执，重复请求保留原键；不以新revision重放未知变更。 */
function reconcile() {
  const command = pending.value
  if (!command || action.busy.value) return
  void action.run(() => dispatch(command))
}
watch(
  () => [auth.identityVersion, auth.user?.permissions.join('|')],
  () => {
    directory.invalidate()
    directory.data.value = undefined
    target.value = undefined
    pending.value = undefined
    open.value = false
    form.parent_tenant_id = ''
    if (allowed('platform.institution_relations.read')) void refresh()
  }
)
useUnsavedChanges({
  dirty: () => open.value && form.parent_tenant_id !== baseline.value,
  busy: () => action.busy.value || !!pending.value
})
onMounted(() => {
  if (allowed('platform.institution_relations.read')) void refresh()
})
</script>

<template>
  <ElCard v-if="allowed('platform.institution_relations.read')" class="hierarchy-panel admin-card">
    <template #header
      ><div class="admin-actions">
        <h2>{{ props.tenantId ? '当前机构与直属关系' : '机构树与关系' }}</h2>
        <ElButton :loading="directory.loading.value" :disabled="action.busy.value" @click="refresh"
          >刷新机构树</ElButton
        >
      </div></template
    >
    <p class="admin-field-help">
      仅展示当前管理范围可见的机构。机构层级不自动传递成员、业务数据、执行或费用权限；不可见的上级仍以原上级标识保留。
    </p>
    <p v-if="directory.data.value">机构树修订：{{ directory.data.value.tree_revision }}</p>
    <FeedbackNotice
      v-if="directory.error.value"
      :title="directory.error.value"
      type="error"
      :closable="false"
    />
    <ElAlert
      v-if="pending"
      title="关系操作结果待核对。保留原请求，先核对原操作回执；不要创建另一笔关系变更。"
      type="warning"
      :closable="false"
    />
    <ElButton v-if="pending" @click="open = true">查看待核对操作</ElButton>
    <ElInput
      v-model="search"
      class="hierarchy-search"
      placeholder="搜索机构名称或标识"
      aria-label="搜索可见机构"
      clearable
    />
    <p v-if="search" class="admin-muted" role="status">
      找到 {{ visibleTree.length }} 家机构，清空搜索可查看完整层级。
    </p>
    <ElTable
      :data="visibleTree"
      row-key="tenant_id"
      default-expand-all
      table-layout="auto"
      v-loading="directory.loading.value"
      :empty-text="
        directory.loading.value
          ? '正在读取机构层级…'
          : directory.error.value
            ? '机构层级尚未读取，请查看上方错误'
            : search
              ? '没有匹配的可见机构，可清空搜索查看完整层级'
              : '当前管理范围内没有可见机构'
      "
    >
      <ElTableColumn prop="name" label="机构" min-width="200" show-overflow-tooltip />
      <ElTableColumn prop="tenant_id" label="机构标识" min-width="180" show-overflow-tooltip />
      <ElTableColumn label="当前上级" min-width="180"
        ><template #default="{ row }">{{
          institutionName(row.parent_tenant_id)
        }}</template></ElTableColumn
      >
      <ElTableColumn label="状态" width="100"
        ><template #default="{ row }">{{ statusLabel(row.status) }}</template></ElTableColumn
      >
      <ElTableColumn prop="revision" label="关系修订" width="100" />
      <ElTableColumn label="关系操作" min-width="230"
        ><template #default="{ row }">
          <ElButton
            v-if="!row.parent_tenant_id && allowed('platform.institution_relations.attach')"
            link
            :disabled="action.busy.value || !!pending"
            @click="begin(row.tenant_id, 'attach')"
            >挂接上级</ElButton
          >
          <ElButton
            v-if="row.parent_tenant_id && allowed('platform.institution_relations.move')"
            link
            :disabled="action.busy.value || !!pending"
            @click="begin(row.tenant_id, 'move')"
            >迁移上级</ElButton
          >
          <ElButton
            v-if="row.parent_tenant_id && allowed('platform.institution_relations.detach')"
            link
            type="danger"
            :disabled="action.busy.value || !!pending"
            @click="begin(row.tenant_id, 'detach')"
            >解除上级</ElButton
          >
        </template></ElTableColumn
      >
    </ElTable>
    <ElDrawer
      v-model="open"
      :title="names[operation]"
      size="680px"
      append-to-body
      :close-on-click-modal="false"
      :before-close="close.requestClose"
    >
      <template v-if="target">
        <p>{{ target.name }} · {{ target.tenant_id }} · 所见关系修订 {{ target.revision }}</p>
        <p>当前上级：{{ institutionName(target.parent_tenant_id) }}</p>
        <FeedbackNotice
          v-if="action.error.value"
          :title="action.error.value"
          type="error"
          :closable="false"
        />
        <ElAlert
          v-if="conflict"
          :title="`原修订已失效，输入已保留。最新关系修订：${latest?.revision ?? '未取得'}；上级：${latest?.parent_tenant_id ?? '独立根'}。请重新核对后采用最新关系。`"
          type="warning"
          :closable="false"
        />
        <ElAlert
          v-if="pending"
          title="结果待核对，只能沿原请求键读取操作回执。"
          type="warning"
          :closable="false"
        />
        <ElForm
          label-position="top"
          :disabled="action.busy.value || !!pending || !canChange"
          @submit.prevent="save"
          @keydown.capture="guardFormEnter"
        >
          <ElFormItem v-if="operation !== 'detach'" label="新上级机构" required
            ><ElSelect v-model="form.parent_tenant_id" filterable
              ><ElOption
                v-for="row in parentChoices"
                :key="row.tenant_id"
                :value="row.tenant_id"
                :label="`${row.name} · ${row.tenant_id}`" /></ElSelect
          ></ElFormItem>
          <p class="admin-field-help">
            需同时具有目标机构、当前上级和新上级的明确管理范围；服务端最终核验当前权限、关系修订及成环限制。
          </p>
        </ElForm>
      </template>
      <template #footer
        ><div class="admin-actions">
          <ElButton :disabled="action.busy.value" @click="close.requestClose()">取消</ElButton>
          <ElButton
            :loading="directory.loading.value"
            :disabled="action.busy.value"
            @click="refresh"
            >重新读取当前关系</ElButton
          >
          <ElButton
            v-if="conflict && latest && !pending"
            :disabled="action.busy.value || !canChange"
            @click="useCurrent"
            >采用最新关系修订</ElButton
          >
          <ElButton
            v-if="pending"
            :loading="action.busy.value"
            :disabled="!canChange"
            @click="reconcile"
            >核对原操作回执</ElButton
          >
          <ElButton
            v-else
            type="primary"
            :loading="action.busy.value"
            :disabled="!canChange || conflict"
            @click="save"
            >确认{{ names[operation] }}</ElButton
          >
        </div></template
      >
    </ElDrawer>
  </ElCard>
</template>

<style scoped>
.hierarchy-panel {
  margin-bottom: var(--el-card-padding, 20px);
}
.hierarchy-search {
  max-width: 360px;
  margin-bottom: 16px;
}
h2 {
  margin: 0;
  font-size: var(--el-font-size-large);
}
</style>
