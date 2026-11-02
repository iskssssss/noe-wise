<script setup lang="ts">
import FeedbackNotice from '../../feedback/FeedbackNotice.vue'
import { authErrorText } from './feedback'
import { HttpClientError } from '../../utils/http'
import { onScopeDispose, ref, watch } from 'vue'
import type { SecurityAuthApi, SecurityAuthTokens } from './api'
const props = defineProps<{ api: SecurityAuthApi; state: string }>()
const emit = defineEmits<{ complete: [tokens: SecurityAuthTokens] }>()
const secret = ref(''),
  uri = ref(''),
  code = ref(''),
  busy = ref(false),
  error = ref(''),
  codes = ref<string[]>([]),
  authentication = ref<SecurityAuthTokens>()
let generation = 0
function clear() {
  generation++
  secret.value = ''
  uri.value = ''
  code.value = ''
  codes.value = []
  authentication.value = undefined
  error.value = ''
  busy.value = false
}
watch(() => [props.api, props.state], clear)
onScopeDispose(clear)
/** 一次性种子只驻留当前组件，后端不提供后续查询。 */
async function enroll() {
  if (busy.value || secret.value || codes.value.length) return
  const current = generation
  busy.value = true
  error.value = ''
  try {
    const result = await props.api.enroll()
    if (current !== generation) return
    secret.value = result.secret
    uri.value = result.otpauth_uri
  } catch (e) {
    if (e instanceof HttpClientError) e.claimFeedback()
    if (current === generation) error.value = authErrorText(e, 'enroll')
  } finally {
    if (current === generation) busy.value = false
  }
}
/** 强化结果先交付恢复码，用户确认保存后才离开绑定页。 */
async function verify() {
  if (busy.value || !code.value || (props.state === 'enrollment' && !secret.value)) return
  const current = generation
  const state = props.state
  busy.value = true
  error.value = ''
  try {
    if (state === 'enrollment') {
      const result = await props.api.enable(code.value)
      if (current !== generation) return
      codes.value = result.recovery_codes
      authentication.value = result.authentication
      secret.value = ''
      uri.value = ''
    } else {
      const result = await props.api.verify(code.value)
      if (current !== generation) return
      emit('complete', result)
    }
    code.value = ''
  } catch (e) {
    if (e instanceof HttpClientError) e.claimFeedback()
    if (current === generation) error.value = authErrorText(e, 'verify')
  } finally {
    if (current === generation) busy.value = false
  }
}
/** 恢复码无法再次查看；确认交付后进入完整登录会话。 */
function finish() {
  if (authentication.value) {
    emit('complete', authentication.value)
    codes.value = []
    authentication.value = undefined
  }
}
</script>
<template>
  <section aria-label="二次验证">
    <h2>{{ state === 'enrollment' ? '绑定验证器' : '二次验证' }}</h2>
    <FeedbackNotice v-if="error" :title="error" type="error" :closable="false" />
    <template v-if="codes.length"
      ><ElAlert
        title="请立即保存恢复码，每个恢复码只能使用一次，离开后无法再次查看。"
        type="warning"
        :closable="false"
      />
      <pre class="recovery-codes">{{ codes.join('\n') }}</pre>
      <ElButton type="primary" @click="finish">已保存，继续登录</ElButton></template
    >
    <template v-else>
      <p>
        {{
          state === 'enrollment'
            ? secret
              ? '第二步：在验证器应用添加密钥，再输入六位验证码。'
              : '第一步：创建密钥并添加到验证器应用；完成验证码验证后才会绑定账号。'
            : '输入验证器六位验证码，或使用一个未使用的恢复码。'
        }}
      </p>
      <template v-if="state === 'enrollment'"
        ><ElButton v-if="!secret" :loading="busy" @click="enroll">创建验证器密钥</ElButton
        ><template v-else
          ><ElFormItem label="一次性绑定密钥"><ElInput :model-value="secret" readonly /></ElFormItem
          ><a :href="uri">在验证器应用中打开</a></template
        ></template
      >
      <ElForm v-if="state !== 'enrollment' || secret" label-position="top" @submit.prevent="verify"
        ><ElFormItem :label="state === 'enrollment' ? '六位验证码' : '验证码或恢复码'"
          ><ElInput
            v-model.trim="code"
            autocomplete="one-time-code"
            :maxlength="128"
            :disabled="busy" /></ElFormItem
        ><ElButton native-type="submit" type="primary" :loading="busy" :disabled="!code"
          >验证</ElButton
        ></ElForm
      >
    </template>
  </section>
</template>
<style scoped>
.recovery-codes {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font-size: 12px;
  padding: 12px;
  background: var(--el-fill-color-light);
}
</style>
