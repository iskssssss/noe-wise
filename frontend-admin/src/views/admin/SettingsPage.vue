<template>
  <AdminPage title="机构设置" description="选择机构默认模型并维护账户安全。"
    ><FeedbackNotice
      v-if="error || loadError"
      :title="error || loadError"
      :closable="false"
      type="error" /><ElButton v-if="loadError" :loading="loading" @click="loadModels"
      >重新读取模型配置</ElButton
    ><ElTabs
      ><ElTabPane label="默认模型"
        ><ElForm
          class="admin-card settings-form"
          v-loading="loading"
          label-position="top"
          @submit.prevent="saveModels"
          :disabled="busy || loading || !saved"
          scroll-to-error
          @keydown.capture="guardFormEnter"
          ><p class="admin-muted">
            按使用场景选择默认模型；留空表示该用途尚未配置。保存后供新任务使用，正在执行的任务保留原模型版本。
          </p>
          <div class="admin-form-grid">
            <ElFormItem v-for="field in modelFields" :key="field.key" :label="field.label"
              ><ElSelect
                v-described-by="
                  formTipId + '-field-0' + '-' + encodeURIComponent(String(field.key))
                "
                :aria-describedby="
                  formTipId + '-field-0' + '-' + encodeURIComponent(String(field.key))
                "
                v-model="selections[field.key]"
                clearable
                filterable
                placeholder="暂不配置"
                ><ElOption
                  v-for="model in data?.models.filter((m) => m.kind === field.kind) ?? []"
                  :key="model.id"
                  :label="model.name + ' / ' + model.model_id"
                  :value="model.id"
              /></ElSelect>
              <FieldTip :id="formTipId + '-field-0' + '-' + encodeURIComponent(String(field.key))"
                >选择该用途的机构默认模型；清除后不指定机构覆盖值。</FieldTip
              >
            </ElFormItem>
          </div>
          <ElButton
            type="primary"
            native-type="submit"
            :loading="busy"
            :disabled="!saved || JSON.stringify(selections) === saved"
            >保存默认模型</ElButton
          ></ElForm
        ></ElTabPane
      ><ElTabPane label="修改密码"
        ><ElForm
          class="admin-card settings-form"
          ref="passwordRef"
          :model="password"
          label-position="top"
          @submit.prevent="changePassword"
          :disabled="busy"
          scroll-to-error
          @keydown.capture="guardFormEnter"
          ><ElFormItem
            label="当前密码"
            prop="old"
            :rules="[{ required: true, message: '请输入当前密码' }]"
            ><ElInput
              v-described-by="formTipId + '-field-1'"
              :aria-describedby="formTipId + '-field-1'"
              v-model="password.old"
              type="password"
              show-password
              autocomplete="current-password"
            />
            <FieldTip :id="formTipId + '-field-1'"
              >填写当前登录账号的密码，用于验证本次修改。</FieldTip
            > </ElFormItem
          ><ElFormItem
            label="新密码"
            prop="next"
            :rules="[{ required: true, message: '请输入新密码' }, accountPasswordRule]"
            ><ElInput
              v-described-by="formTipId + '-field-2'"
              :aria-describedby="formTipId + '-field-2'"
              v-model="password.next"
              type="password"
              show-password
              autocomplete="new-password"
            />
            <FieldTip :id="formTipId + '-field-2'"
              >新密码至少 12 位，包含字母和数字；保存后所有设备退出，需要重新登录。</FieldTip
            > </ElFormItem
          ><ElFormItem
            label="再次输入新密码"
            prop="confirm"
            :rules="[
              { required: true, message: '请再次输入新密码' },
              {
                validator: (_rule, value, callback) =>
                  callback(
                    value === password.next ? undefined : new Error('两次输入的新密码不一致')
                  ),
                trigger: 'blur'
              }
            ]"
            ><ElInput
              v-described-by="formTipId + '-field-3'"
              :aria-describedby="formTipId + '-field-3'"
              v-model="password.confirm"
              type="password"
              show-password
              autocomplete="new-password"
            />
            <FieldTip :id="formTipId + '-field-3'"
              >再次填写新密码，两次输入必须一致。</FieldTip
            > </ElFormItem
          ><ElButton type="primary" native-type="submit" :loading="busy">修改密码</ElButton></ElForm
        ></ElTabPane
      ></ElTabs
    ><FeedbackNotice role="status" v-if="success" :title="success" type="success" :closable="false"
  /></AdminPage>
</template>
<script setup lang="ts">
import FeedbackNotice from '@skillforge/shared/feedback/FeedbackNotice.vue'
import { vDescribedBy } from '@skillforge/shared/directives/describedBy'

import FieldTip from '@skillforge/shared/components/FieldTip.vue'

import { message } from '@skillforge/shared/feedback'
import { guardFormEnter } from '@skillforge/shared/admin'
import { computed, onMounted, reactive, ref, useId } from 'vue'
import type { FormInstance } from 'element-plus'
import { AdminPage, useResource, useTask, useUnsavedChanges } from '@skillforge/shared/admin'
import { accountPasswordRule } from '@skillforge/shared/utils/accountPassword'
import { api, type ModelSelections } from '../../api'
import { useAuthStore } from '../../stores/auth'
import { useRouter } from 'vue-router'
const auth = useAuthStore(),
  router = useRouter()
const {
  data,
  loading,
  error: loadError,
  reload
} = useResource(async () => ({
  models: await api.settings.availableModels(),
  selections: await api.settings.getModelSelections()
}))
const { busy, error, run } = useTask()
const selections = reactive<ModelSelections>({
    llm_model_config_id: null,
    embedding_model_config_id: null,
    rerank_model_config_id: null
  }),
  saved = ref(''),
  success = ref('')
async function loadModels() {
  const value = await reload()
  if (value) {
    Object.assign(selections, value.selections)
    saved.value = JSON.stringify(selections)
  }
}
onMounted(loadModels)
const modelFields: Array<{ key: keyof ModelSelections; label: string; kind: string }> = [
  { key: 'llm_model_config_id', label: '对话模型', kind: 'LLM' },
  { key: 'embedding_model_config_id', label: '向量模型', kind: 'EMBEDDING' },
  { key: 'rerank_model_config_id', label: '重排序模型', kind: 'RERANK' }
]
const password = reactive({ old: '', next: '', confirm: '' }),
  passwordRef = ref<FormInstance>()
const dirty = computed(
  () =>
    (!!saved.value && JSON.stringify(selections) !== saved.value) ||
    !!password.old ||
    !!password.next ||
    !!password.confirm
)
useUnsavedChanges({ dirty: () => dirty.value, busy: () => busy.value })
async function saveModels() {
  await run(async () => {
    const result = await api.settings.updateModelSelections(
      Object.fromEntries(
        Object.entries(selections).map(([k, v]) => [k, v || null])
      ) as unknown as ModelSelections
    )
    Object.assign(selections, result)
    saved.value = JSON.stringify(selections)
    success.value = '默认模型已保存'
    message.success('默认模型已保存')
  })
}
async function changePassword() {
  await run(async () => {
    if (!(await passwordRef.value?.validate().catch(() => false))) return
    await api.auth.changePassword({ old_password: password.old, new_password: password.next })
    Object.assign(password, { old: '', next: '', confirm: '' })
    success.value = '密码已修改，请重新登录'
    message.success('密码已修改，请重新登录')
    auth.logout(false)
    await router.replace('/login')
  })
}

const formTipId = useId()
</script>

<style scoped>
.settings-form {
  max-width: 840px;
}
</style>
