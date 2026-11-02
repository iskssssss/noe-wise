<script setup lang="ts">
import FeedbackNotice from '@skillforge/shared/feedback/FeedbackNotice.vue'
import { clearFeedback, message } from '@skillforge/shared/feedback'
import { authErrorText } from '@skillforge/shared/foundation/security/feedback'
import SecurityChallenge from '@skillforge/shared/foundation/security/SecurityChallenge.vue'
import AccountRecovery from '@skillforge/shared/foundation/security/AccountRecovery.vue'
import {
  AuthSessionChangedError,
  type SecurityAuthTokens
} from '@skillforge/shared/foundation/security/api'
import { api } from '../api'
import { HttpClientError } from '@skillforge/shared/utils/http'

import { guardFormEnter } from '@skillforge/shared/admin'
import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { loginRedirectTarget } from '../router/login-redirect'
const auth = useAuthStore(),
  router = useRouter(),
  route = useRoute()
const form = reactive({ username: '', password: '' }),
  busy = ref(false),
  error = ref('')
// 登录更新身份会重建页面，验证阶段始终来自当前认证会话。
const pendingMfa = computed(() => {
  const state = auth.user?.mfa_state
  return state === 'enrollment' || state === 'challenge' ? state : null
})
function clearLoginFeedback() {
  error.value = ''
  clearFeedback()
}
async function login() {
  if (busy.value) return
  clearLoginFeedback()
  if (!form.username.trim() || !form.password) {
    error.value = '请输入账号和密码'
    return
  }
  busy.value = true
  try {
    await auth.login({ ...form })
    form.password = ''
    if (pendingMfa.value) return
    await router.replace(loginRedirectTarget(router, route.query.redirect))
  } catch (e) {
    if (e instanceof AuthSessionChangedError) return
    if (e instanceof HttpClientError) e.claimFeedback()
    message.error(authErrorText(e, 'login'))
  } finally {
    busy.value = false
  }
}

/** 接收二次验证完成的轮换凭据，重新读取数据库授权后进入后台。 */
async function completeMfa(tokens: SecurityAuthTokens) {
  try {
    auth.applyTokens(tokens)
    await auth.fetchMe()
    await router.replace(loginRedirectTarget(router, route.query.redirect))
  } catch (e) {
    if (e instanceof AuthSessionChangedError) return
    if (e instanceof HttpClientError) e.claimFeedback()
    message.error('二次验证已通过，登录资料暂时无法读取，请稍后重新登录')
  }
}
</script>

<template>
  <main class="login-screen">
    <ElCard class="login-card">
      <h1>平台运营登录</h1>
      <p>灵知 管理工作台</p>
      <FeedbackNotice v-if="error" :title="error" type="error" :closable="false" />
      <ElForm
        v-if="!pendingMfa"
        @keydown.capture="guardFormEnter"
        label-position="top"
        @submit.prevent="login"
      >
        <ElFormItem label="账号">
          <ElInput v-model="form.username" autocomplete="username" :disabled="busy" />
        </ElFormItem>
        <ElFormItem label="密码">
          <ElInput
            v-model="form.password"
            type="password"
            show-password
            autocomplete="current-password"
            :disabled="busy"
          />
        </ElFormItem>
        <ElButton native-type="submit" type="primary" :loading="busy" style="width: 100%"
          >登录运营台</ElButton
        >
      </ElForm>
      <SecurityChallenge
        v-if="pendingMfa"
        :api="api.auth"
        :state="pendingMfa"
        @complete="completeMfa"
      />
      <AccountRecovery
        :api="api.auth"
        :username="form.username"
        :disabled="busy || Boolean(pendingMfa)"
        @start="clearLoginFeedback"
      />
    </ElCard>
  </main>
</template>
