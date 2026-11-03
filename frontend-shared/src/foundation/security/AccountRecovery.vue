<script setup lang="ts">
import FeedbackNotice from '../../feedback/FeedbackNotice.vue'
import { authErrorText } from './feedback'
import { HttpClientError } from '../../utils/http'
import { onScopeDispose, reactive, ref, watch } from 'vue'
import type { SecurityAuthApi } from './api'
const props = defineProps<{
  api: SecurityAuthApi
  tenantSlug?: string
  username?: string
  disabled?: boolean
}>()
const emit = defineEmits<{ start: [] }>()
const hasCredential = ref(false)
const form = reactive({ token: '', new_password: '', purpose: 'recovery' }),
  busy = ref(false),
  error = ref(''),
  result = ref('')
let generation = 0
function clear() {
  generation++
  form.token = ''
  form.new_password = ''
  form.purpose = 'recovery'
  hasCredential.value = false
  error.value = ''
  result.value = ''
  busy.value = false
}
watch(() => [props.api, props.tenantSlug, props.username], clear)
watch(
  () => form.purpose,
  () => {
    form.token = ''
    form.new_password = ''
    error.value = ''
    result.value = ''
  }
)
watch(
  () => props.disabled,
  (disabled) => {
    if (disabled) {
      error.value = ''
      result.value = ''
    }
  }
)
onScopeDispose(clear)
/** 请求只发往已登记邮箱；相同响应不透露账号是否存在。 */
async function request() {
  if (
    busy.value ||
    props.disabled ||
    !props.username ||
    (props.tenantSlug !== undefined && !props.tenantSlug)
  )
    return
  const current = generation
  emit('start')
  busy.value = true
  error.value = ''
  result.value = ''
  try {
    await props.api.requestRecovery({
      tenant_slug: props.tenantSlug,
      username: props.username || ''
    })
    if (current !== generation) return
    hasCredential.value = true
    result.value = '如账号和邮箱有效，恢复邮件将进入投递队列，请查看登记邮箱。'
  } catch (e) {
    if (e instanceof HttpClientError) e.claimFeedback()
    if (current === generation) error.value = authErrorText(e, 'requestRecovery')
  } finally {
    if (current === generation) busy.value = false
  }
}
/** 密码恢复和邀请采用隔离的凭据用途，成功后保留账号已有的MFA规则。 */
async function complete() {
  if (busy.value || props.disabled || !form.token || !form.new_password) return
  const current = generation
  emit('start')
  busy.value = true
  error.value = ''
  result.value = ''
  try {
    if (
      [...form.new_password].length < 12 ||
      !/[A-Za-z]/.test(form.new_password) ||
      !/[0-9]/.test(form.new_password) ||
      new TextEncoder().encode(form.new_password).length > 72
    )
      throw Error('密码至少十二位，包含字母和数字，UTF-8不超过72字节')
    const command = { token: form.token, new_password: form.new_password }
    if (form.purpose === 'invitation') await props.api.activate(command)
    else await props.api.completeRecovery(command)
    if (current !== generation) return
    form.token = ''
    form.new_password = ''
    hasCredential.value = false
    result.value = '密码已设置，请使用新密码登录。已有设备已撤销。'
  } catch (e) {
    if (e instanceof HttpClientError) e.claimFeedback()
    if (current === generation)
      error.value = authErrorText(
        e,
        form.purpose === 'invitation' ? 'activate' : 'completeRecovery'
      )
  } finally {
    if (current === generation) busy.value = false
  }
}
</script>
<template>
  <ElCollapse
    ><ElCollapseItem title="忘记密码或接受邀请" name="recovery"
      ><FeedbackNotice v-if="error" :title="error" type="error" :closable="false" /><FeedbackNotice
        v-if="result"
        :title="result"
        type="info"
        :closable="false"
      />
      <ElRadioGroup v-model="form.purpose" aria-label="邮件用途" :disabled="busy || disabled">
        <ElRadioButton value="recovery">找回密码</ElRadioButton>
        <ElRadioButton value="invitation">接受邀请</ElRadioButton>
      </ElRadioGroup>
      <template v-if="form.purpose === 'recovery' && !hasCredential">
        <p>
          {{
            tenantSlug === undefined ? '填写上方用户名' : '填写上方机构标识和用户名'
          }}，发送恢复邮件至登记邮箱。
        </p>
        <ElButton
          :loading="busy"
          :disabled="busy || disabled || !username || (tenantSlug !== undefined && !tenantSlug)"
          @click="request"
          >发送密码恢复邮件</ElButton
        >
      </template>
      <ElButton
        v-if="form.purpose === 'recovery'"
        link
        :disabled="busy || disabled"
        @click="hasCredential = !hasCredential"
      >
        {{ hasCredential ? '返回发送恢复邮件' : '已有恢复邮件，填写凭据' }}
      </ElButton>
      <ElForm
        v-if="hasCredential || form.purpose === 'invitation'"
        label-position="top"
        :disabled="busy || disabled"
        @submit.prevent="complete"
        ><ElFormItem label="邮件中的一次性凭据"
          ><ElInput
            v-model.trim="form.token"
            autocomplete="off"
            type="password"
            show-password /></ElFormItem
        ><ElFormItem label="新密码"
          ><ElInput
            v-model="form.new_password"
            autocomplete="new-password"
            type="password"
            show-password
        /></ElFormItem>
        <p>至少十二位，包含字母和数字，UTF-8不超过72字节。</p>
        <ElButton
          native-type="submit"
          type="primary"
          :loading="busy"
          :disabled="disabled || !form.token || !form.new_password"
          >设置密码</ElButton
        ></ElForm
      ></ElCollapseItem
    ></ElCollapse
  >
</template>
