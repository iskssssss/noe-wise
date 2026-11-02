<template>
  <main class="login-screen">
    <ElCard class="login-card"
      ><h1>灵知</h1>
      <p>登录机构管理后台</p>
      <FeedbackNotice
        v-if="error"
        :title="error"
        type="error"
        :closable="false"
        role="alert"
      /><ElForm
        v-if="!pendingMfa"
        ref="formRef"
        :model="form"
        label-position="top"
        @submit.prevent="login"
        :disabled="busy"
        scroll-to-error
        @keydown.capture="guardFormEnter"
        ><ElFormItem label="机构标识" prop="tenant_slug" :rules="required.tenant_slug"
          ><ElInput
            v-described-by="formTipId + '-field-0'"
            :aria-describedby="formTipId + '-field-0'"
            v-model.trim="form.tenant_slug"
            autocomplete="organization"
          />
          <FieldTip :id="formTipId + '-field-0'"
            >填写所属机构的登录标识，由机构管理员提供。</FieldTip
          > </ElFormItem
        ><ElFormItem label="用户名" prop="username" :rules="required.username"
          ><ElInput v-model.trim="form.username" autocomplete="username" /> </ElFormItem
        ><ElFormItem label="密码" prop="password" :rules="required.password"
          ><ElInput
            v-model="form.password"
            type="password"
            show-password
            autocomplete="current-password"
          /> </ElFormItem
        ><ElButton type="primary" native-type="submit" :loading="busy" class="full"
          >登录</ElButton
        ></ElForm
      >
      <SecurityChallenge
        v-if="pendingMfa"
        :api="api.auth"
        :state="pendingMfa"
        @complete="completeMfa"
      />
      <AccountRecovery
        :api="api.auth"
        :tenant-slug="form.tenant_slug"
        :username="form.username"
        :disabled="busy || Boolean(pendingMfa)"
        @start="clearLoginFeedback"
      />
    </ElCard>
  </main>
</template>
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
import { vDescribedBy } from '@skillforge/shared/directives/describedBy'

import FieldTip from '@skillforge/shared/components/FieldTip.vue'

import { guardFormEnter } from '@skillforge/shared/admin'
import { computed, reactive, ref, useId } from 'vue'
import type { FormInstance } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
const auth = useAuthStore(),
  route = useRoute(),
  router = useRouter(),
  formRef = ref<FormInstance>()
const form = reactive({ tenant_slug: '', username: '', password: '' }),
  busy = ref(false),
  error = ref('')
// 登录更新身份会重建页面，验证阶段始终来自当前认证会话。
const pendingMfa = computed(() => {
  const state = auth.user?.mfa_state
  return state === 'enrollment' || state === 'challenge' ? state : null
})
const required = {
  tenant_slug: [{ required: true, message: '请输入机构管理员提供的机构标识', trigger: 'blur' }],
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}
function clearLoginFeedback() {
  error.value = ''
  clearFeedback()
}
async function login() {
  if (busy.value || !(await formRef.value?.validate().catch(() => false))) return
  if (busy.value) return
  busy.value = true
  clearLoginFeedback()
  try {
    await auth.login({ ...form })
    form.password = ''
    if (pendingMfa.value) return
    const target = route.query.redirect
    await router.replace(
      typeof target === 'string' && target.startsWith('/admin/') ? target : '/admin/home'
    )
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
    const target = route.query.redirect
    await router.replace(
      typeof target === 'string' && target.startsWith('/admin/') ? target : '/admin/home'
    )
  } catch (e) {
    if (e instanceof AuthSessionChangedError) return
    if (e instanceof HttpClientError) e.claimFeedback()
    message.error('二次验证已通过，登录资料暂时无法读取，请稍后重新登录')
  }
}

const formTipId = useId()
</script>
