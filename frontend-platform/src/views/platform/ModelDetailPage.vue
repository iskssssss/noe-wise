<script setup lang="ts">
import FeedbackNotice from '@skillforge/shared/feedback/FeedbackNotice.vue'
import { useActionPermissions } from '../../composables/actionPermissions'
import { vDescribedBy } from '@skillforge/shared/directives/describedBy'

import {
  modelCapacityHelp,
  imagePolicyHelp,
  modelKindLabels,
  modelReasoningLabels
} from './model-field-help'
import FieldTip from '@skillforge/shared/components/FieldTip.vue'
import ModelMediaProbe from './ModelMediaProbe.vue'
import ModelTimePricingEditor from './ModelTimePricingEditor.vue'
import ModelTimePricingPresets from './ModelTimePricingPresets.vue'
import {
  emptyTimePricingPolicy,
  timePricingErrors,
  cloneTimePricingPolicy
} from './model-time-pricing'
import { mediaPriceUnits, mediaPriceUnitLabels, validModelPrice } from './model-media-pricing'

import { guardFormEnter } from '@skillforge/shared/admin'
import { acceptUnhighlightedCreate } from '@skillforge/shared/admin/creatableOption'
import { message, confirm } from '@skillforge/shared/feedback'
import { HttpClientError } from '@skillforge/shared/utils/http'
import { formatDateTime } from '@skillforge/shared/utils/datetime'
import { computed, nextTick, onMounted, onScopeDispose, reactive, ref, useId, watch } from 'vue'
import type { FormInstance } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../../stores/auth'

import { AdminPage, useDrawerClose, useUnsavedChanges } from '@skillforge/shared/admin'
import {
  api,
  type ModelKind,
  type ModelMediaPriceUnit,
  type ModelCachePricing,
  type ModelWrite,
  type ModelView,
  type ModelProvider,
  type ModelReasoningOptions,
  type ModelDiscoveredModel,
  type ModelCompatibilityReport,
  type ModelProbeStatus,
  type ModelReasoningSelection
} from '../../api'
import { useTask } from '@skillforge/shared/admin'
const { allowed, checked } = useActionPermissions()
const route = useRoute(),
  router = useRouter(),
  isNew = route.path.endsWith('/new')
const auth = useAuthStore()
const actor = computed(() => auth.user?.sub ?? '')
const createStorageKey = (subject: string) => `skillforge.model-create:${subject}`
const pendingCreate = ref<{ requestId: string; name: string }>()
const createStorageError = ref('')
let disposed = false
onScopeDispose(() => {
  disposed = true
})
watch(
  () => auth.identityVersion,
  () => {
    pendingCreate.value = undefined
    createStorageError.value = ''
    if (!isNew || !actor.value) return
    try {
      const raw = sessionStorage.getItem(createStorageKey(actor.value))
      if (!raw) return
      const operation = JSON.parse(raw) as { requestId: string; name: string }
      if (
        typeof operation?.name !== 'string' ||
        !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(operation.requestId)
      )
        throw Error('invalid')
      pendingCreate.value = operation
    } catch {
      createStorageError.value = '无法读取原创建记录，请恢复浏览器存储后重新进入。'
    }
  },
  { immediate: true, flush: 'sync' }
)
const loadTask = useTask(),
  action = useTask(),
  discoveryTask = useTask()
const discoveryLoaded = ref(false)
const item = ref<ModelView>(),
  providers = ref<ModelProvider[]>([]),
  ready = ref(false),
  baseline = ref(''),
  history = ref<ModelView[]>([]),
  historyOpen = ref(false),
  discovered = ref<ModelDiscoveredModel[]>([]),
  testResult = ref(''),
  testIncomplete = ref(false),
  testedInput = ref(''),
  compatibilityReport = ref<ModelCompatibilityReport>()
const timeoutDefaults: Record<ModelKind, number> = {
  LLM: 600,
  EMBEDDING: 120,
  RERANK: 60,
  IMAGE_GENERATION: 600,
  VIDEO_GENERATION: 600,
  SPEECH_RECOGNITION: 600,
  SPEECH_SYNTHESIS: 600
}
const timeoutEdited = ref(false)
const mediaBusy = ref(false)
const mediaPendingOperation = ref<string>()
const formBusy = computed(() => action.busy.value || mediaBusy.value)
const isMediaKind = computed(() =>
  ['IMAGE_GENERATION', 'VIDEO_GENERATION', 'SPEECH_RECOGNITION', 'SPEECH_SYNTHESIS'].includes(
    form.kind
  )
)
const modelSections = ref<string[]>([])
const form = reactive<ModelWrite>({
  name: '',
  kind: 'LLM',
  enabled: true,
  provider_id: '',
  service_id: null,
  profile_version: null,
  protocol: null,
  authentication_scheme: null,
  endpoint: '',
  model_id: '',
  api_key: '',
  currency: 'CNY',
  media_pricing: null,
  cache_pricing: null,
  time_pricing: null,
  timeout_seconds: timeoutDefaults.LLM,
  reasoning: 'DEFAULT',
  prefix_capabilities: {
    system_updates_in_history: 'UNVERIFIED',
    tool_updates: 'NONE',
    retain_tools_with_tool_choice_none: 'UNVERIFIED'
  },
  image_policy: {
    enabled: false,
    tool_images: false,
    max_images: 4,
    max_image_bytes: 10485760,
    max_request_bytes: 20971520,
    max_dimension: 2048,
    tile_size: 512,
    base_tokens: 85,
    tile_tokens: 170,
    version: '1'
  }
})
const mediaPricingUnit = computed<ModelMediaPriceUnit | ''>({
  get: () => form.media_pricing?.unit ?? '',
  set: (unit) => {
    form.media_pricing = unit
      ? {
          unit,
          price_per_unit_cents: null,
          input_price_per_million_cents: null,
          output_price_per_million_cents: null,
          video_resolution: null
        }
      : null
    for (const key of Object.keys(fieldErrors.value))
      if (key.startsWith('media_pricing.')) delete fieldErrors.value[key]
  }
})
const cachePricingMode = computed<ModelCachePricing['mode'] | 'UNIFIED'>({
  get: () => form.cache_pricing?.mode ?? 'UNIFIED',
  set: (mode) => {
    const pricing = form.cache_pricing
    form.cache_pricing =
      mode === 'UNIFIED'
        ? null
        : {
            mode,
            read_price_per_million_cents: pricing?.read_price_per_million_cents ?? null,
            write_price_per_million_cents:
              mode === 'CACHE_READ_WRITE' ? (pricing?.write_price_per_million_cents ?? null) : null
          }
    for (const key of Object.keys(fieldErrors.value))
      if (key.startsWith('cache_pricing.')) delete fieldErrors.value[key]
  }
})
const timePricingEnabled = computed({
  get: () => !!form.time_pricing,
  set: (enabled: boolean) => {
    form.time_pricing = enabled ? emptyTimePricingPolicy() : null
    for (const key of Object.keys(fieldErrors.value))
      if (key.startsWith('time_pricing')) delete fieldErrors.value[key]
  }
})
const committedNavigation = ref(false)
const dirty = computed(() => ready.value && JSON.stringify(form) !== baseline.value)
useUnsavedChanges({
  dirty: () => dirty.value || !!mediaPendingOperation.value || !!pendingCreate.value,
  busy: () => formBusy.value && !committedNavigation.value,
  leaveMessage: () =>
    pendingCreate.value
      ? `模型创建结果尚未确认，请保留原请求 ID ${pendingCreate.value.requestId}，只核对原回执，不要重复创建。`
      : mediaPendingOperation.value
        ? `媒体操作 ${mediaPendingOperation.value} 尚未确认结束。离开不会撤销供应商任务，请保留操作及任务编号并核对原任务，不要重新创建。`
        : undefined
})
const services = computed(
  () => providers.value.find((p) => p.provider_id === form.provider_id)?.services ?? []
)
const kindProviders = computed(() =>
  providers.value.filter((provider) =>
    provider.services.some((entry) => entry.model_kinds.includes(form.kind))
  )
)
const service = computed(() => services.value.find((s) => s.service_id === form.service_id))
const discoveryProviderLabel = computed(
  () =>
    kindProviders.value.find((entry) => entry.provider_id === form.provider_id)?.display_name ||
    form.provider_id ||
    '模型候选'
)
const discoveryActionLabel = computed(() =>
  discoveryTask.error.value ? '重试获取' : discoveryLoaded.value ? '刷新模型' : '发现模型'
)
const discoveryEmptyLabel = computed(() => {
  if (discoveryTask.busy.value) return '正在获取模型候选…'
  if (discoveryLoaded.value) return '服务未返回候选，可直接输入模型标识'
  if (!service.value?.model_discovery) return '当前服务不提供模型目录，请手动输入模型标识'
  if (!allowed('foundation.models.create')) return '当前账号无模型发现权限，可手动输入模型标识'
  if (!form.api_key?.trim() && !savedCredentialAvailable.value)
    return '可直接输入模型标识；填写 API 凭据后可发现模型'
  return '可直接输入模型标识，或从当前连接获取候选'
})
const discoveryHelp = computed(() => {
  if (!service.value?.model_discovery)
    return '当前服务不提供模型目录，请手动填写供应商实际模型标识。'
  if (!allowed('foundation.models.create'))
    return '当前账号无模型发现权限，可手动填写供应商实际模型标识。'
  if (!form.api_key?.trim() && !savedCredentialAvailable.value)
    return '可直接填写模型标识；填写 API 凭据后展开下拉可获取候选。'
  return '填写供应商实际模型标识；展开下拉可从当前连接获取候选，也可搜索或手动填写。已保存且连接范围不变时无需重填凭据。'
})
const configurableServices = computed(() =>
  services.value.filter(
    (value) =>
      ['SUPPORTED', 'CONFIGURABLE_UNVERIFIED'].includes(value.availability) &&
      value.model_kinds.includes(form.kind) &&
      value.protocols.length > 0
  )
)
const kindServices = computed(() =>
  services.value.filter((entry) => entry.model_kinds.includes(form.kind))
)
const protocols = computed(() => service.value?.protocols ?? [])
const authSchemes = computed(
  () => protocols.value.find((p) => p.protocol === form.protocol)?.authentication_schemes ?? []
)
const selectedProtocol = computed(() => protocols.value.find((p) => p.protocol === form.protocol))
const imageSupport = computed(
  () =>
    reasoningOptions.value?.image_support?.user_images ??
    selectedProtocol.value?.capabilities.user_images ??
    'UNVERIFIED'
)
const toolImageSupport = computed(
  () =>
    reasoningOptions.value?.image_support?.tool_images ??
    selectedProtocol.value?.capabilities.tool_images ??
    'UNVERIFIED'
)
const imageDefaults = computed(() =>
  reasoningOptions.value?.image_support
    ? reasoningOptions.value.image_support.defaults
    : selectedProtocol.value?.image_policy_defaults
)
function adoptImagePolicy() {
  const defaults = imageDefaults.value
  if (defaults) form.image_policy = { ...defaults }
}
const reasoningOptions = ref<ModelReasoningOptions>()
const reasoningLoading = ref(false)
const reasoningError = ref('')
const reasoningEdited = ref(false)
const reasoningRefresh = ref(0)
function reasoningChanged() {
  reasoningEdited.value = true
  delete fieldErrors.value.reasoning
}
const reasoningSelection = computed<ModelReasoningSelection | undefined>(() => {
  if (
    form.kind !== 'LLM' ||
    !form.provider_id ||
    !form.service_id ||
    !form.profile_version ||
    !form.protocol ||
    !form.authentication_scheme ||
    !form.endpoint.trim() ||
    !form.model_id.trim()
  )
    return
  return {
    provider_id: form.provider_id,
    service_id: form.service_id,
    profile_version: form.profile_version,
    protocol: form.protocol,
    authentication_scheme: form.authentication_scheme,
    endpoint: form.endpoint.trim(),
    model_id: form.model_id.trim()
  }
})
const discoveredModel = computed(() =>
  discovered.value.find((model) => model.model_id === form.model_id)
)
const discoveredLevels = computed(
  () => discoveredModel.value?.metadata?.reasoning?.supported_levels ?? null
)
const effectiveReasoningOptions = computed(() => {
  const protocol = reasoningOptions.value
  const reported = discoveredLevels.value
  if (!protocol || reported === null) return protocol
  const efforts = new Set(reported.map((level) => level.toUpperCase()))
  // Thinking switches belong to the protocol; discovered effort levels can only narrow encodable efforts.
  return {
    options: protocol.options.filter(
      (level) => ['DEFAULT', 'DISABLED', 'ENABLED'].includes(level) || efforts.has(level)
    ),
    source: 'DISCOVERY_METADATA' as const,
    source_url: null
  }
})
const discoveredCapacity = computed(() => {
  const context = discoveredModel.value?.metadata?.context
  const positive = (value: number | null | undefined) =>
    Number.isSafeInteger(value) && value! > 0 ? value : undefined
  return {
    context: positive(context?.context_window),
    output: positive(context?.max_output_tokens)
  }
})
const discoveredSystemUpdates = computed(() => {
  const mode = discoveredModel.value?.metadata?.messages?.system_prompt_update
  return mode === 'in-history'
    ? 'SUPPORTED'
    : mode === 'leading-only'
      ? 'UNSUPPORTED'
      : 'UNVERIFIED'
})
const capabilityLabels = { SUPPORTED: '支持', UNSUPPORTED: '不支持', UNVERIFIED: '未验证' }
const toolUpdateLabels = {
  NONE: '固定',
  ADDITION_ONLY: '仅新增',
  ADD_REMOVE: '新增或移除',
  UNVERIFIED: '未验证'
}
function adoptDiscoveredPrefix() {
  form.prefix_capabilities = {
    system_updates_in_history: discoveredSystemUpdates.value,
    tool_updates: 'NONE',
    retain_tools_with_tool_choice_none:
      form.prefix_capabilities?.retain_tools_with_tool_choice_none ?? 'UNVERIFIED'
  }
}
function adoptDiscoveredCapacity() {
  if (discoveredCapacity.value.context)
    form.context_window_tokens = discoveredCapacity.value.context
  if (discoveredCapacity.value.output)
    form.supported_max_output_tokens = discoveredCapacity.value.output
}
// 目录归属仅由发现接口的真实输入决定，模型名称、容量和价格草稿不改变连接。
const discoverySelection = computed(() => ({
  id: item.value?.id,
  expected_revision: item.value?.revision,
  provider_id: form.provider_id,
  service_id: form.service_id ?? '',
  profile_version: form.profile_version ?? '',
  protocol: form.protocol ?? '',
  authentication_scheme: form.authentication_scheme ?? '',
  endpoint: form.endpoint,
  api_key: form.api_key?.trim() || undefined
}))
watch(discoverySelection, () => {
  discovered.value = []
  discoveryLoaded.value = false
  discoveryTask.error.value = ''
})
const reasoningInvalid = computed(() =>
  effectiveReasoningOptions.value &&
  !effectiveReasoningOptions.value.options.includes(form.reasoning ?? 'DEFAULT')
    ? '当前配置的推理程度不在此模型返回的候选选项内，请重新选择；原值已保留。'
    : ''
)
watch([reasoningSelection, reasoningRefresh], async ([selection], _previous, onCleanup) => {
  let current = true
  onCleanup(() => {
    current = false
  })
  reasoningOptions.value = undefined
  reasoningError.value = ''
  reasoningLoading.value = false
  if (!selection) return
  reasoningLoading.value = true
  try {
    const result = await api.admin.models.reasoningOptions(selection)
    if (!current) return
    reasoningOptions.value = result
    delete fieldErrors.value.reasoning
    if (
      isNew &&
      !reasoningEdited.value &&
      !result.options.includes(form.reasoning ?? 'DEFAULT') &&
      result.options[0]
    )
      form.reasoning = result.options[0]
  } catch {
    if (current) reasoningError.value = '未能读取当前模型的推理选项，请重试。当前输入已保留。'
  } finally {
    if (current) reasoningLoading.value = false
  }
})
function accept(value: ModelView) {
  item.value = value
  Object.assign(
    form,
    JSON.parse(
      JSON.stringify(
        Object.fromEntries(
          Object.entries(value).filter(
            ([key]) =>
              ![
                'id',
                'revision',
                'credential_configured',
                'published_revision',
                'has_unpublished_changes',
                'compatibility_report'
              ].includes(key)
          )
        )
      )
    ),
    {
      api_key: '',
      media_pricing: value.media_pricing ? { ...value.media_pricing } : null,
      cache_pricing: value.cache_pricing ? { ...value.cache_pricing } : null,
      time_pricing: value.time_pricing ? cloneTimePricingPolicy(value.time_pricing) : null
    }
  )
  baseline.value = JSON.stringify(form)
  ready.value = true
  testResult.value = ''
  testIncomplete.value = false
  compatibilityReport.value = value.compatibility_report ?? undefined
  testedInput.value = baseline.value
  fieldErrors.value = {}
}
function providerChanged() {
  form.service_id = null
  form.profile_version = null
  form.protocol = null
  form.authentication_scheme = null
  form.endpoint = ''
  form.api_key = ''
  discovered.value = []
  if (configurableServices.value.length === 1) {
    form.service_id = configurableServices.value[0]!.service_id
    serviceChanged()
  }
}
function kindChanged() {
  if (!item.value && !timeoutEdited.value) form.timeout_seconds = timeoutDefaults[form.kind]
  providerChanged()
  form.supported_max_output_tokens = null
  form.context_window_tokens = null
  form.max_output_tokens = null
  form.temperature = null
  form.dimensions = null
  form.path = form.kind === 'RERANK' ? '/rerank' : null
  form.media_pricing = null
  form.cache_pricing = null
  form.time_pricing = null
  form.input_price_per_million_cents = null
  form.output_price_per_million_cents = null
  form.price_per_quantity_cents = null
  if (form.image_policy) form.image_policy.enabled = false
}
function serviceChanged() {
  form.profile_version = service.value?.profile_version ?? null
  form.endpoint = service.value?.endpoint ?? ''
  form.protocol = protocols.value[0]?.protocol ?? null
  protocolChanged()
}
function protocolChanged() {
  form.authentication_scheme = authSchemes.value[0] ?? null
  form.api_key = ''
  discovered.value = []
}
const savedCredentialAvailable = computed(
  () =>
    !!item.value?.credential_configured &&
    ['endpoint', 'provider_id', 'service_id', 'authentication_scheme'].every(
      (key) => item.value?.[key as keyof ModelView] === form[key as keyof ModelWrite]
    )
)
const fieldErrors = ref<Record<string, string>>({})
const modelForm = ref<FormInstance>()
const pendingErrorField = ref<string>()
watch(
  [formBusy, pendingErrorField],
  ([busy, field]) => {
    if (busy || !field) return
    pendingErrorField.value = undefined
    void focusFieldError(field)
  },
  { flush: 'post' }
)
async function focusFieldError(field: string) {
  if (formBusy.value) {
    pendingErrorField.value = field
    return
  }
  await nextTick()
  const root = modelForm.value?.$el as HTMLElement | undefined
  if (!root) return
  const target =
    Array.from(root.querySelectorAll<HTMLElement>('[data-time-pricing-field]')).find(
      (element) => element.dataset.timePricingField === field
    ) ??
    Array.from(root.querySelectorAll<HTMLElement>('.el-form-item.is-error')).find(
      (element) =>
        element.querySelector('.el-form-item__error')?.textContent?.trim() ===
        fieldErrors.value[field]
    )
  if (!target) return
  target.scrollIntoView?.({ block: 'center' })
  const control =
    target.querySelector<HTMLElement>(
      'input:not([type="hidden"]):not([disabled]), textarea:not([disabled]), [role="combobox"], [tabindex="0"]'
    ) ?? target.querySelector<HTMLElement>('button:not([disabled])')
  control?.focus({ preventScroll: true })
}
function currentTimePricingErrors() {
  const errors: Record<string, string> = {}
  if (!form.time_pricing) return errors
  Object.assign(errors, timePricingErrors(form.time_pricing, 'time_pricing'))
  if (form.kind !== 'LLM') errors.time_pricing = '峰谷计价只适用于语言模型'
  const scopes = form.time_pricing.applicable_models
  if (
    scopes.length &&
    !scopes.some(
      (scope) =>
        (!scope.provider_id || scope.provider_id === form.provider_id) &&
        (!scope.service_id || scope.service_id === form.service_id) &&
        (!scope.profile_version || scope.profile_version === form.profile_version) &&
        (!scope.model_id || scope.model_id === form.model_id)
    )
  )
    errors['time_pricing.applicable_models'] = '当前模型不在规则适用范围内，请修正规则或模型标识'
  return errors
}
function refreshTimePricingErrors() {
  if (!Object.keys(fieldErrors.value).some((key) => key.startsWith('time_pricing'))) return
  const errors = { ...fieldErrors.value }
  for (const key of Object.keys(errors)) if (key.startsWith('time_pricing')) delete errors[key]
  Object.assign(errors, currentTimePricingErrors())
  fieldErrors.value = errors
}
function collectValidationErrors() {
  const errors: Record<string, string> = {}
  if (!form.name.trim()) errors.name = '请填写名称'
  if (!form.provider_id) errors.provider_id = '请选择或填写提供方'
  if (!form.model_id.trim()) errors.model_id = '请填写供应商模型标识'
  try {
    const url = new URL(form.endpoint)
    if (
      !['http:', 'https:'].includes(url.protocol) ||
      url.username ||
      url.password ||
      url.search ||
      url.hash
    )
      errors.endpoint = '服务地址仅支持无凭据、查询和片段的 HTTP(S) URL'
  } catch {
    errors.endpoint = '请输入有效的服务地址'
  }
  const priceError = '价格须为不超过 9223372036854775807 的非负十进制数，最多 6 位小数（分）'
  if (isMediaKind.value) {
    const pricing = form.media_pricing
    if (pricing) {
      if (!mediaPriceUnits[form.kind]?.includes(pricing.unit))
        errors['media_pricing.unit'] = '请选择适用于当前模型类型的计费单位'
      const fields =
        pricing.unit === 'TOKENS'
          ? (['input_price_per_million_cents', 'output_price_per_million_cents'] as const)
          : (['price_per_unit_cents'] as const)
      for (const key of fields) {
        const value = pricing[key]
        if (!value)
          errors['media_pricing.' + key] = '请填写单价；如尚未确认价格，请清空媒体计费单位'
        else if (!validModelPrice(value)) errors['media_pricing.' + key] = priceError
      }
      if (
        pricing.unit === 'VIDEO_SECOND' &&
        !['480P', '720P', '1080P'].includes(pricing.video_resolution ?? '')
      )
        errors['media_pricing.video_resolution'] = '请选择视频秒单价适用的分辨率'
    }
  } else {
    for (const key of [
      'input_price_per_million_cents',
      'output_price_per_million_cents',
      'price_per_quantity_cents'
    ] as const) {
      const value = form[key]
      if (value && !validModelPrice(value)) errors[key] = priceError
    }
  }
  if (form.kind === 'LLM' && form.cache_pricing) {
    const pricing = form.cache_pricing
    for (const key of ['read_price_per_million_cents', 'write_price_per_million_cents'] as const) {
      if (key === 'write_price_per_million_cents' && pricing.mode !== 'CACHE_READ_WRITE') continue
      const value = pricing[key]
      if (value && !validModelPrice(value)) errors['cache_pricing.' + key] = priceError
    }
  }
  Object.assign(errors, currentTimePricingErrors())
  if (
    !Number.isInteger(form.timeout_seconds) ||
    !form.timeout_seconds ||
    form.timeout_seconds < 1 ||
    form.timeout_seconds > 600
  )
    errors.timeout_seconds = '超时须为 1–600 秒的整数'
  if (
    !form.service_id ||
    !configurableServices.value.some((entry) => entry.service_id === form.service_id)
  )
    errors.service_id = '请选择支持当前模型类型的国内或本地服务'
  if (!selectedProtocol.value) errors.protocol = '请选择该服务支持的协议'
  if (!form.authentication_scheme || !authSchemes.value.includes(form.authentication_scheme))
    errors.authentication_scheme = '请选择该服务支持的认证方式'
  if (form.kind === 'LLM') {
    if (reasoningInvalid.value) errors.reasoning = reasoningInvalid.value
    else if (reasoningSelection.value && !effectiveReasoningOptions.value)
      errors.reasoning = reasoningLoading.value
        ? '正在读取模型推理选项，请稍候再操作'
        : reasoningError.value || '请先读取当前模型推理选项'
    if (errors.reasoning && !modelSections.value.includes('generation'))
      modelSections.value.push('generation')
    for (const key of [
      'supported_max_output_tokens',
      'context_window_tokens',
      'max_output_tokens'
    ] as const) {
      const value = form[key]
      if (!value || !Number.isInteger(value) || value < 1 || value > 2147483647)
        errors[key] = '请填写模型输出上限与上下文容量，须为正整数'
    }
    if (
      form.max_output_tokens &&
      form.supported_max_output_tokens &&
      form.max_output_tokens > form.supported_max_output_tokens
    )
      errors.max_output_tokens = '输出上限不得超过模型支持上限'
    if (
      form.max_output_tokens &&
      form.context_window_tokens &&
      form.max_output_tokens >= form.context_window_tokens
    )
      errors.max_output_tokens = '输出上限必须小于上下文窗口'
  }
  if (
    form.kind === 'EMBEDDING' &&
    (!form.dimensions ||
      !Number.isInteger(form.dimensions) ||
      form.dimensions < 1 ||
      form.dimensions > 65536)
  )
    errors.dimensions = 'Embedding 维度必须在 1–65536 之间'
  if (
    form.kind === 'RERANK' &&
    (!form.path?.startsWith('/') ||
      form.path.startsWith('//') ||
      /[?#]/.test(form.path) ||
      form.path.includes('..'))
  )
    errors.path = '请输入有效的重排路径'
  if (!form.api_key?.trim() && !savedCredentialAvailable.value)
    errors.api_key = '新建模型或更换连接范围时必须填写 API 凭据'
  if (form.image_policy?.enabled && imageSupport.value === 'UNSUPPORTED')
    errors['image_policy.enabled'] =
      '当前服务的协议适配明确不支持图片输入，请关闭图片能力或选择支持的服务'
  if (form.image_policy?.tool_images && toolImageSupport.value === 'UNSUPPORTED')
    errors['image_policy.tool_images'] =
      '当前服务尚未声明工具图片支持，请关闭工具图片或选择支持的服务'
  return errors
}
watch(
  form,
  () => {
    const previous = fieldErrors.value
    if (Object.keys(previous).length)
      fieldErrors.value = Object.fromEntries(
        Object.entries(collectValidationErrors()).filter(([field]) =>
          Object.hasOwn(previous, field)
        )
      )
  },
  { deep: true }
)
function validate() {
  const errors = collectValidationErrors()
  fieldErrors.value = errors
  if (
    Object.keys(errors).some(
      (key) =>
        key.includes('price_') || key.startsWith('media_pricing.') || key.startsWith('time_pricing')
    ) &&
    !modelSections.value.includes('pricing')
  )
    modelSections.value.push('pricing')
  if (
    (errors['image_policy.enabled'] || errors['image_policy.tool_images']) &&
    !modelSections.value.includes('images')
  )
    modelSections.value.push('images')
  if (Object.keys(errors).length) {
    void focusFieldError(Object.keys(errors)[0]!)
    if (errors.name || errors.provider_id || errors.model_id)
      throw new Error('请填写名称、提供方和模型标识')
    throw new Error(`请修正 ${Object.keys(errors).length} 项配置：${Object.values(errors)[0]}`)
  }
}
function save(continueToPublish = false) {
  if (
    pendingCreate.value ||
    createStorageError.value ||
    !allowed(item.value ? 'foundation.models.update' : 'foundation.models.create')
  )
    return
  void action.run(async () => {
    validate()
    const payload = currentPayload()
    const target = item.value
    let value: ModelView
    if (target) {
      value = await checked('foundation.models.update', () =>
        api.admin.models.update(target.id, { ...payload, expected_revision: target.revision })
      )
    } else {
      const subject = actor.value,
        identity = auth.identityVersion
      if (!subject) throw Error('当前身份不可用，请重新登录')
      const operation = { requestId: crypto.randomUUID(), name: payload.name }
      try {
        sessionStorage.setItem(createStorageKey(subject), JSON.stringify(operation))
      } catch {
        createStorageError.value = '无法保存创建记录，请恢复浏览器存储后重试。'
        return
      }
      pendingCreate.value = operation
      let result
      try {
        result = await checked('foundation.models.create', () =>
          api.admin.models.createWithReceipt(operation.requestId, payload)
        )
        if (disposed || actor.value !== subject || auth.identityVersion !== identity) return
        if (!result || result.request_id !== operation.requestId || !result.model_id)
          throw Error('创建回执不完整，请核对原请求')
        sessionStorage.removeItem(createStorageKey(subject))
        pendingCreate.value = undefined
      } catch (cause) {
        if (
          !disposed &&
          actor.value === subject &&
          auth.identityVersion === identity &&
          cause instanceof HttpClientError &&
          cause.code === 'MODEL_CREATE_REJECTED'
        ) {
          try {
            sessionStorage.removeItem(createStorageKey(subject))
            pendingCreate.value = undefined
          } catch {
            createStorageError.value = '创建已被拒绝，但无法清理原请求记录；请恢复浏览器存储。'
          }
        }
        throw cause
      }
      try {
        value = await checked('foundation.models.read', () =>
          api.admin.models.detail(result.model_id)
        )
      } catch (cause) {
        if (cause instanceof HttpClientError) cause.claimFeedback()
        if (disposed || actor.value !== subject || auth.identityVersion !== identity) return
        baseline.value = JSON.stringify(form)
        message.warning('模型已创建，但详情读取失败；请从模型列表进入并核对。')
        await navigateCreated(result.model_id)
        return
      }
    }
    accept(value)
    message.success('模型草稿已保存，发布后生效')
    try {
      if (continueToPublish && allowed('foundation.models.publish')) await publishSaved(value)
    } catch (cause) {
      if (target) throw cause
      if (cause instanceof HttpClientError) cause.claimFeedback()
      message.error('模型已创建，发布未完成或结果未知；请在模型详情核对状态后再操作。')
    } finally {
      if (!target) await navigateCreated(value.id)
    }
  })
}
async function navigateCreated(id: string) {
  committedNavigation.value = true
  try {
    const failure = await router.replace('/platform/models/' + encodeURIComponent(id))
    if (failure) message.warning('模型已保存，详情导航失败，可从模型列表重新进入。')
  } catch {
    message.warning('模型已保存，详情导航失败，可从模型列表重新进入。')
  } finally {
    committedNavigation.value = false
  }
}
function checkCreation() {
  if (!pendingCreate.value || !allowed('foundation.models.read')) return
  void action.run(async () => {
    const operation = pendingCreate.value!,
      subject = actor.value,
      identity = auth.identityVersion
    const result = await api.admin.models.createReceipt(operation.requestId)
    if (
      disposed ||
      actor.value !== subject ||
      auth.identityVersion !== identity ||
      pendingCreate.value !== operation
    )
      return
    if (!result || result.request_id !== operation.requestId || !result.model_id) {
      message.warning('尚未查到原创建回执，结果仍待核对')
      return
    }
    try {
      sessionStorage.removeItem(createStorageKey(subject))
    } catch {
      createStorageError.value = '创建已确认，但无法清除原请求记录；请恢复浏览器存储。'
      return
    }
    pendingCreate.value = undefined
    baseline.value = JSON.stringify(form)
    message.success('模型创建已确认')
    await navigateCreated(result.model_id)
  })
}
function currentPayload(): ModelWrite {
  const payload = { ...form }
  if (!payload.api_key?.trim()) delete payload.api_key
  for (const key of [
    'input_price_per_million_cents',
    'output_price_per_million_cents',
    'price_per_quantity_cents'
  ] as const)
    payload[key] = isMediaKind.value ? null : payload[key] || null
  const pricing = form.media_pricing
  payload.media_pricing =
    isMediaKind.value && pricing
      ? {
          unit: pricing.unit,
          price_per_unit_cents: pricing.unit === 'TOKENS' ? null : pricing.price_per_unit_cents,
          input_price_per_million_cents:
            pricing.unit === 'TOKENS' ? pricing.input_price_per_million_cents : null,
          output_price_per_million_cents:
            pricing.unit === 'TOKENS' ? pricing.output_price_per_million_cents : null,
          video_resolution: pricing.unit === 'VIDEO_SECOND' ? pricing.video_resolution : null
        }
      : null
  const cachePricing = form.cache_pricing
  payload.cache_pricing =
    form.kind === 'LLM' && cachePricing
      ? {
          mode: cachePricing.mode,
          read_price_per_million_cents: cachePricing.read_price_per_million_cents || null,
          write_price_per_million_cents:
            cachePricing.mode === 'CACHE_READ_WRITE'
              ? cachePricing.write_price_per_million_cents || null
              : null
        }
      : null
  payload.time_pricing = form.time_pricing ?? null
  return payload
}
function publish() {
  if (!allowed('foundation.models.publish')) return
  void action.run(async () => {
    const target = item.value
    if (!target || dirty.value) throw new Error('请先保存当前修改')
    await publishSaved(target)
  })
}
async function publishSaved(target: ModelView) {
  if (
    !(await confirm({
      title: '发布模型配置',
      confirmText: '发布',
      content: `发布“${target.name}”的当前草稿？新执行采用新配置，当前执行继续原版本。`
    }))
  )
    return
  accept(
    await checked('foundation.models.publish', () =>
      api.admin.models.publish(target.id, target.revision)
    )
  )
  message.success('模型配置已发布')
}

function discover() {
  if (!allowed('foundation.models.create')) return
  void discoveryTask.run(async () => {
    const apiKey = form.api_key?.trim()
    if (!apiKey && !savedCredentialAvailable.value) {
      fieldErrors.value.api_key = '请填写当前连接的 API 凭据；连接范围不变时可直接使用已保存凭据'
      void focusFieldError('api_key')
      throw new Error(fieldErrors.value.api_key)
    }
    if (!service.value?.model_discovery) throw new Error('请先选择支持模型发现的提供方和服务')
    delete fieldErrors.value.api_key
    const selection = discoverySelection.value
    const requestInput = JSON.stringify(selection)
    let r
    try {
      r = await checked('foundation.models.create', () => api.admin.models.discover(selection))
    } catch (failure) {
      if (requestInput !== JSON.stringify(discoverySelection.value)) return
      if (failure instanceof HttpClientError) {
        const messages: Record<string, string> = {
          model_discovery_configuration_invalid:
            '获取模型列表的连接配置无效，请核对提供方、服务和服务地址。无需先保存或填写模型标识、容量。',
          model_discovery_credential_required: '请填写当前连接的 API 凭据后获取模型列表。',
          model_discovery_credential_scope_changed:
            '连接范围已变更，请填写当前连接的 API 凭据后获取模型列表。',
          model_discovery_failed:
            '获取模型列表失败，请核对服务地址、API 凭据权限及网络后重试。当前输入已保留。'
        }
        const text = failure.code && messages[failure.code]
        if (text) {
          failure.claimFeedback()
          throw new Error(text, { cause: failure })
        }
      }
      throw failure
    }
    if (requestInput !== JSON.stringify(discoverySelection.value)) return
    discovered.value = r.models
    discoveryLoaded.value = true
    if (!discovered.value.length)
      message.info('服务未返回模型标识，请核对连接和凭据权限，或手动填写供应商模型标识')
  })
}
function modelOptionsVisible(visible: boolean) {
  if (
    visible &&
    !discoveryLoaded.value &&
    !discoveryTask.busy.value &&
    !discoveryTask.error.value &&
    (Boolean(form.api_key?.trim()) || savedCredentialAvailable.value) &&
    service.value?.model_discovery &&
    allowed('foundation.models.create')
  )
    discover()
}
const suggestedName = ref('')
function modelSelected(modelId: string) {
  if (!isNew || (form.name.trim() && form.name !== suggestedName.value)) return
  const model = discovered.value.find((candidate) => candidate.model_id === modelId)
  suggestedName.value = (model?.metadata?.name?.trim() || modelId.trim()).slice(0, 200)
  form.name = suggestedName.value
}
function onModelIdEnter(event: KeyboardEvent) {
  const labels = discovered.value.map((item) =>
    item.metadata?.name ? `${item.metadata.name} · ${item.model_id}` : item.model_id
  )
  acceptUnhighlightedCreate(event, !formBusy.value, labels, (value) => {
    form.model_id = value
    modelSelected(value)
  })
}
const compatibilityStale = computed(
  () =>
    !!compatibilityReport.value &&
    (testedInput.value !== JSON.stringify(form) ||
      (compatibilityReport.value.draft_revision !== 0 &&
        compatibilityReport.value.draft_revision !== item.value?.revision))
)
const probeLabels: Record<ModelProbeStatus, string> = {
  PASSED: '通过',
  FAILED: '未通过',
  UNKNOWN: '结果未知',
  NOT_RUN: '未执行'
}
const probeReasonLabels: Record<string, string> = {
  configuration_request_accepted: '供应商已接受配置请求',
  stream_completed_with_deltas: '流式响应完整返回',
  native_tool_continuation_completed: '原生工具调用与续答完成'
}
function probeReasonLabel(value?: string | null) {
  if (!value) return '—'
  return probeReasonLabels[value] ?? '未识别诊断'
}
const compatibilityChecks = computed(() => {
  const report = compatibilityReport.value
  return report
    ? [
        { label: '配置参数接受', ...report.configuration_acceptance },
        { label: '流式响应', ...report.streaming },
        { label: '工具调用与续答', ...report.tool_continuation }
      ]
    : []
})
function test() {
  if (!allowed('foundation.models.run')) return
  void action.run(async () => {
    testIncomplete.value = true
    const target = item.value
    const preview = !target || dirty.value
    if (preview) validate()
    const testedForm = JSON.stringify(form)
    const testedRevision = target?.revision
    const command = preview
      ? { id: target?.id, payload: { ...currentPayload(), expected_revision: testedRevision } }
      : { id: target.id }
    const r = await checked('foundation.models.run', () => api.admin.models.test(command))
    if (
      item.value?.id !== target?.id ||
      item.value?.revision !== testedRevision ||
      JSON.stringify(form) !== testedForm
    )
      return
    compatibilityReport.value = r.compatibility_report ?? undefined
    testIncomplete.value = false
    testedInput.value = testedForm
    testResult.value = r.compatibility_report
      ? ''
      : r.ok
        ? `当前配置的连接测试通过，耗时 ${r.latency_ms} ms${preview ? '；本次输入尚未保存' : ''}`
        : (r.error ?? '连接测试未通过，请核对当前服务地址、模型标识和凭据')
  })
}
function mediaConfiguration() {
  const target = item.value
  if (target && !dirty.value) return { id: target.id }
  validate()
  return { id: target?.id, payload: { ...currentPayload(), expected_revision: target?.revision } }
}
const { requestClose: historyClose } = useDrawerClose({
  open: () => historyOpen.value,
  busy: () => action.busy.value,
  dirty: () => false,
  close: () => {
    historyOpen.value = false
  }
})
function loadHistory() {
  historyOpen.value = true
  void action.run(async () => {
    history.value = []
    history.value = await api.admin.models.history(item.value!.id)
  })
}
function rollback(version: ModelView) {
  if (!allowed('foundation.models.update')) return
  void action.run(async () => {
    if (dirty.value) throw new Error('请先保存或放弃当前修改')
    if (
      !(await confirm({
        title: '恢复模型草稿',
        content: `将发布修订 ${version.revision} 恢复到当前工作草稿？发布后新执行生效。`,
        confirmText: '恢复为草稿',
        cancelText: '取消',
        danger: true
      }))
    )
      return
    accept(
      await checked('foundation.models.update', () =>
        api.admin.models.rollback(item.value!.id, {
          revision: version.revision,
          expected_revision: item.value!.revision
        })
      )
    )
    historyOpen.value = false
    message.success('模型历史已恢复为草稿，发布后生效')
  })
}
function load() {
  void loadTask.run(async () => {
    const [catalog, current] = await Promise.all([
      api.admin.models.providers(),
      isNew ? Promise.resolve(undefined) : api.admin.models.detail(String(route.params.id))
    ])
    providers.value = catalog.providers
    if (current) accept(current)
    else {
      baseline.value = JSON.stringify(form)
      ready.value = true
    }
  })
}
onMounted(load)

const formTipId = useId()
</script>

<template>
  <AdminPage
    :title="item?.name ?? '新建模型'"
    description="保存更新工作草稿；发布后新执行采用当前配置，进行中的执行保持原版本"
  >
    <template #extra>
      <RouterLink to="/platform/models">
        <ElButton>返回列表</ElButton>
      </RouterLink>
      <ElTag v-if="dirty" type="warning">未保存</ElTag
      ><ElButton
        v-if="ready"
        v-permission="[item ? 'foundation.models.update' : 'foundation.models.create']"
        :type="dirty && allowed('foundation.models.publish') ? 'default' : 'primary'"
        :disabled="formBusy || !!pendingCreate || !!createStorageError || (!!item && !dirty)"
        :loading="action.busy.value"
        @click="save()"
        >保存草稿</ElButton
      >
      <ElButton
        v-if="
          ready &&
          dirty &&
          allowed(item ? 'foundation.models.update' : 'foundation.models.create') &&
          allowed('foundation.models.publish')
        "
        type="primary"
        :disabled="formBusy || !!pendingCreate || !!createStorageError"
        :loading="action.busy.value"
        @click="save(true)"
        >保存并继续发布</ElButton
      >
      <template v-if="item"
        ><span>当前发布修订 {{ item.published_revision ?? '尚未发布' }}</span
        ><ElTag v-if="item.has_unpublished_changes" type="warning">草稿待发布</ElTag>
        <ElButton
          v-permission="['foundation.models.publish']"
          type="primary"
          :disabled="dirty || formBusy || !item.has_unpublished_changes"
          @click="publish"
          >发布</ElButton
        ></template
      >
      <ElButton v-if="item" :disabled="formBusy" @click="loadHistory">修订历史</ElButton>
    </template>
    <section v-if="pendingCreate" class="admin-card" role="status">
      模型“{{ pendingCreate.name }}”创建结果尚未确认。请求 ID：<code>{{
        pendingCreate.requestId
      }}</code
      >；未查到回执不能证明创建失败，请勿重复创建。
      <ElButton
        v-if="allowed('foundation.models.read')"
        :loading="action.busy.value"
        @click="checkCreation"
        >核对创建结果</ElButton
      >
    </section>
    <p v-if="createStorageError" role="status">{{ createStorageError }}</p>
    <FeedbackNotice
      v-if="loadTask.error.value"
      :title="loadTask.error.value"
      :closable="false"
      type="error"
    >
      <ElButton @click="load">重试</ElButton>
    </FeedbackNotice>
    <FeedbackNotice
      v-if="discoveryTask.error.value"
      :title="discoveryTask.error.value"
      :closable="false"
      type="error"
    />
    <ElSkeleton v-if="loadTask.busy.value && !ready" :rows="8" animated />
    <ElCard class="admin-card model-settings-card" v-else-if="ready">
      <FeedbackNotice
        v-if="action.error.value"
        :title="action.error.value"
        :closable="false"
        type="error"
      />
      <ElForm
        ref="modelForm"
        @keydown.capture="guardFormEnter"
        label-position="top"
        :disabled="
          !allowed(item ? 'foundation.models.update' : 'foundation.models.create') ||
          formBusy ||
          !!pendingCreate ||
          !!createStorageError
        "
        @submit.prevent="save()"
      >
        <section
          v-if="Object.keys(fieldErrors).length > 1"
          class="model-validation-summary"
          aria-label="配置错误摘要"
        >
          <h3>请修正以下配置</h3>
          <ul>
            <li v-for="(error, field) in fieldErrors" :key="field">
              <ElButton link type="primary" @click="focusFieldError(field)">{{ error }}</ElButton>
            </li>
          </ul>
        </section>
        <h3>连接与模型</h3>
        <div class="admin-form-grid model-fields-grid">
          <ElFormItem label="名称" :error="fieldErrors['name']" required>
            <ElInput v-model="form.name" maxlength="200" @input="suggestedName = ''" />
          </ElFormItem>
          <ElFormItem label="类型">
            <ElSelect
              v-described-by="formTipId + '-field-1'"
              :aria-describedby="formTipId + '-field-1'"
              v-model="form.kind"
              :disabled="!!item"
              @change="kindChanged"
            >
              <ElOption
                v-for="(label, value) in modelKindLabels"
                :key="value"
                :value="value"
                :label="label"
              />
            </ElSelect>

            <FieldTip :id="formTipId + '-field-1'"
              >选择对话、向量、重排或媒体模型服务；创建后类型不可在此修改。</FieldTip
            >
          </ElFormItem>
          <ElFormItem label="提供方" :error="fieldErrors['provider_id']" required>
            <ElSelect
              v-described-by="formTipId + '-field-2'"
              :aria-describedby="formTipId + '-field-2'"
              v-model="form.provider_id"
              @change="providerChanged"
            >
              <ElOption
                v-for="p in kindProviders"
                :key="p.provider_id"
                :label="p.display_name"
                :value="p.provider_id"
              />
            </ElSelect>
            <FieldTip :id="formTipId + '-field-2'"
              >选择支持当前模型类型的国内或本地提供方，用于协议接入和计量归属。</FieldTip
            >
          </ElFormItem>
          <ElFormItem label="提供方服务" :error="fieldErrors['service_id']" required>
            <ElSelect
              v-described-by="formTipId + '-field-3'"
              :aria-describedby="formTipId + '-field-3'"
              v-model="form.service_id"
              @change="serviceChanged"
            >
              <ElOption
                v-for="s in kindServices"
                :key="s.service_id"
                :label="s.display_name"
                :value="s.service_id"
                :disabled="!configurableServices.some((value) => value.service_id === s.service_id)"
              />
            </ElSelect>

            <FieldTip :id="formTipId + '-field-3'"
              >仅有一个可配置服务时自动填入；地址、协议与认证方式取自提供方目录。</FieldTip
            >
          </ElFormItem>
          <ElFormItem
            v-if="
              form.service_id &&
              (protocols.length !== 1 || protocols[0]?.protocol !== form.protocol)
            "
            label="协议"
            :error="fieldErrors['protocol']"
          >
            <ElSelect
              v-described-by="formTipId + '-field-4'"
              :aria-describedby="formTipId + '-field-4'"
              v-model="form.protocol"
              @change="protocolChanged"
            >
              <ElOption v-for="p in protocols" :key="p.protocol" :value="p.protocol" />
            </ElSelect>

            <FieldTip :id="formTipId + '-field-4'">选择与提供方端点匹配的调用协议。</FieldTip>
          </ElFormItem>
          <ElFormItem
            v-if="
              form.service_id &&
              (authSchemes.length !== 1 || authSchemes[0] !== form.authentication_scheme)
            "
            label="认证方式"
            :error="fieldErrors['authentication_scheme']"
          >
            <ElSelect
              v-described-by="formTipId + '-field-5'"
              :aria-describedby="formTipId + '-field-5'"
              v-model="form.authentication_scheme"
            >
              <ElOption v-for="a in authSchemes" :key="a" :value="a" />
            </ElSelect>

            <FieldTip :id="formTipId + '-field-5'"
              >选择该服务支持的认证方式，凭据应与所选方式一致。</FieldTip
            >
          </ElFormItem>
          <ElFormItem label="服务地址" :error="fieldErrors['endpoint']" required>
            <ElInput
              v-described-by="'model-endpoint-help'"
              aria-describedby="model-endpoint-help"
              v-model="form.endpoint"
              maxlength="2048"
              :disabled="!!service && !service.custom_endpoint"
            />
            <p id="model-endpoint-help" class="admin-field-help">
              填写提供方实际 API 地址，须与选定服务和协议一致。
            </p>
          </ElFormItem>
          <ElFormItem label="API 凭据" :error="fieldErrors['api_key']">
            <ElInput
              v-described-by="'model-credential-help'"
              aria-describedby="model-credential-help"
              v-model="form.api_key"
              type="password"
              show-password
              autocomplete="new-password"
              :placeholder="
                item?.credential_configured ? '已配置，连接范围不变时留空保留' : '请输入凭据'
              "
            />
            <p id="model-credential-help" class="admin-field-help">
              新建或更换连接范围时必须填写凭据；连接范围不变时，保存、测试和发现模型均可留空使用已保存凭据。
            </p>
          </ElFormItem>
          <ElFormItem
            class="model-identity-field"
            label="模型标识"
            :error="fieldErrors['model_id']"
            required
          >
            <ElSelect
              v-described-by="formTipId + '-field-8'"
              :aria-describedby="formTipId + '-field-8'"
              v-model="form.model_id"
              @keydown.enter.capture="onModelIdEnter"
              filterable
              allow-create
              default-first-option
              :loading="discoveryTask.busy.value"
              loading-text="正在获取模型候选"
              popper-class="model-discovery-options"
              placeholder="输入模型标识，或展开选择"
              @visible-change="modelOptionsVisible"
              @change="modelSelected"
            >
              <template #header>
                <div class="model-discovery-header">
                  <span>{{ discoveryProviderLabel }}</span>
                  <ElButton
                    v-if="service?.model_discovery && allowed('foundation.models.create')"
                    text
                    :loading="discoveryTask.busy.value"
                    :disabled="formBusy"
                    @click="discover"
                    >{{ discoveryActionLabel }}</ElButton
                  >
                </div>
              </template>
              <template #empty>
                <p class="model-discovery-empty" role="status">{{ discoveryEmptyLabel }}</p>
              </template>
              <ElOption
                v-for="m in discovered"
                :key="m.model_id"
                :value="m.model_id"
                :label="m.metadata?.name ? `${m.metadata.name} · ${m.model_id}` : m.model_id"
              />
            </ElSelect>

            <FieldTip :id="formTipId + '-field-8'">{{ discoveryHelp }}</FieldTip>
            <p
              v-if="discoveryLoaded && discoveryTask.error.value"
              class="admin-field-help"
              role="status"
            >
              模型候选刷新失败，当前显示上次获取的结果；请重试获取后核对。
            </p>
          </ElFormItem>
        </div>
        <p v-if="form.protocol" class="admin-muted">
          连接协议：{{ form.protocol }} · 认证：{{ form.authentication_scheme }} · 服务地域：{{
            service?.region === 'CHINA_MAINLAND'
              ? '中国内地'
              : service?.region === 'LOCAL'
                ? '本地'
                : '未确认'
          }}
        </p>
        <h3>运行与容量</h3>
        <div class="admin-form-grid model-fields-grid">
          <ElFormItem label="启用">
            <ElSwitch
              v-described-by="formTipId + '-field-9'"
              :aria-describedby="formTipId + '-field-9'"
              v-model="form.enabled"
            />

            <FieldTip :id="formTipId + '-field-9'">发布后控制模型是否可用于新执行。</FieldTip>
          </ElFormItem>
          <ElFormItem label="超时（秒）" :error="fieldErrors['timeout_seconds']">
            <ElInputNumber
              v-described-by="formTipId + '-field-10'"
              :aria-describedby="formTipId + '-field-10'"
              v-model="form.timeout_seconds"
              @update:model-value="timeoutEdited = true"
              :min="1"
              :max="600"
              :precision="0"
            />

            <FieldTip :id="formTipId + '-field-10'"
              >单次调用的超时时间，单位为秒，范围为 1–600。</FieldTip
            >
          </ElFormItem>
          <template v-if="form.kind === 'LLM'">
            <ElFormItem
              v-for="f in [
                { key: 'supported_max_output_tokens', label: '支持最大输出 Token' },
                { key: 'context_window_tokens', label: '上下文窗口 Token' },
                { key: 'max_output_tokens', label: '本次输出 Token 上限' }
              ] as const"
              :key="f.key"
              :label="f.label"
              :error="fieldErrors[f.key]"
            >
              <ElInputNumber
                v-described-by="formTipId + '-field-11' + '-' + encodeURIComponent(String(f.key))"
                :aria-describedby="
                  formTipId + '-field-11' + '-' + encodeURIComponent(String(f.key))
                "
                v-model="form[f.key]"
                :min="1"
                :max="2147483647"
                :precision="0"
              />

              <FieldTip :id="formTipId + '-field-11' + '-' + encodeURIComponent(String(f.key))">{{
                modelCapacityHelp(f.key)
              }}</FieldTip>
            </ElFormItem>
          </template>
          <ElFormItem
            v-if="form.kind === 'EMBEDDING'"
            label="向量维度"
            :error="fieldErrors['dimensions']"
          >
            <ElInputNumber
              v-described-by="formTipId + '-field-14'"
              :aria-describedby="formTipId + '-field-14'"
              v-model="form.dimensions"
              :min="1"
              :max="65536"
              :precision="0"
            />

            <FieldTip :id="formTipId + '-field-14'"
              >向量模型的输出维度，必须与模型实际输出及索引配置一致。</FieldTip
            >
          </ElFormItem>
          <ElFormItem v-if="form.kind === 'RERANK'" label="重排路径" :error="fieldErrors['path']">
            <ElInput
              v-described-by="formTipId + '-field-15'"
              :aria-describedby="formTipId + '-field-15'"
              v-model="form.path"
              placeholder="/rerank"
            />

            <FieldTip :id="formTipId + '-field-15'"
              >默认 /rerank；按提供方实际接口调整，路径相对于服务地址。</FieldTip
            >
          </ElFormItem>
        </div>
        <div
          v-if="discoveredModel && (discoveredCapacity.context || discoveredCapacity.output)"
          class="admin-field-help"
          role="status"
        >
          <p>
            供应商上次发现的容量候选：上下文
            {{ discoveredCapacity.context ?? '未报告' }} Tokens；最大输出
            {{ discoveredCapacity.output ?? '未报告' }} Tokens。
          </p>
          <ElButton :disabled="formBusy" @click="adoptDiscoveredCapacity">采用候选容量</ElButton>
          <p>
            点击后仅采用已报告的上下文容量和模型支持最大输出；本次输出上限仍由你设置。发现结果不会自动覆盖配置。
          </p>
        </div>
        <div v-if="discoveredModel?.metadata?.messages" class="admin-field-help">
          <p>供应商上次发现的系统指令更新候选：{{ capabilityLabels[discoveredSystemUpdates] }}。</p>
          <ElButton :disabled="formBusy" @click="adoptDiscoveredPrefix"
            >采用系统指令更新声明</ElButton
          >
          <p>
            仅采用已报告的历史内系统指令更新能力；工具目录保持固定，禁用工具选择时保留工具目录的能力不从发现结果推断。
          </p>
        </div>
        <ElCollapse v-model="modelSections">
          <ElCollapseItem
            v-if="form.kind === 'LLM'"
            name="generation"
            :title="`生成偏好 · 随机程度 ${form.temperature ?? '提供方默认'} · ${modelReasoningLabels[form.reasoning ?? 'DEFAULT']}`"
          >
            <p class="admin-field-help">
              当前前缀能力声明：历史内系统指令更新
              {{
                capabilityLabels[
                  form.prefix_capabilities?.system_updates_in_history ?? 'UNVERIFIED'
                ]
              }}；工具目录更新
              {{
                toolUpdateLabels[form.prefix_capabilities?.tool_updates ?? 'UNVERIFIED']
              }}；禁用工具选择时保留目录
              {{
                capabilityLabels[
                  form.prefix_capabilities?.retain_tools_with_tool_choice_none ?? 'UNVERIFIED'
                ]
              }}。
            </p>
            <div class="admin-form-grid model-fields-grid">
              <ElFormItem label="Temperature">
                <ElInputNumber
                  v-described-by="formTipId + '-field-12'"
                  :aria-describedby="formTipId + '-field-12'"
                  v-model="form.temperature"
                  :min="0"
                  :max="2"
                  :step="0.1"
                />

                <FieldTip :id="formTipId + '-field-12'"
                  >控制生成随机程度，范围为 0–2；实际支持情况以模型提供方为准。</FieldTip
                >
              </ElFormItem>
              <ElFormItem label="推理程度" :error="fieldErrors['reasoning']">
                <ElSelect
                  v-described-by="formTipId + '-field-13'"
                  :aria-describedby="formTipId + '-field-13'"
                  v-model="form.reasoning"
                  :loading="reasoningLoading"
                  :disabled="!effectiveReasoningOptions || reasoningLoading"
                  placeholder="先选择服务、协议和模型"
                  @change="reasoningChanged"
                >
                  <ElOption
                    v-if="reasoningInvalid && form.reasoning"
                    :label="`${modelReasoningLabels[form.reasoning]}（当前配置，待确认）`"
                    :value="form.reasoning"
                    disabled
                  />
                  <ElOption
                    v-for="option in effectiveReasoningOptions?.options ?? []"
                    :key="option"
                    :label="modelReasoningLabels[option]"
                    :value="option"
                  />
                </ElSelect>

                <FieldTip :id="formTipId + '-field-13'"
                  >选项随提供方、服务、协议和模型变化，以当前返回的能力定义为准。</FieldTip
                >
                <FieldTip v-if="reasoningInvalid" :id="formTipId + '-reasoning-invalid'">{{
                  reasoningInvalid
                }}</FieldTip>
                <FieldTip
                  v-if="discoveredLevels !== null"
                  :id="formTipId + '-discovered-reasoning'"
                >
                  发现接口声明的推理程度：{{
                    discoveredLevels.length
                      ? discoveredLevels.join('、')
                      : '空列表（未报告可用强度）'
                  }}。
                  此声明仅供参考；可保存选项保留协议思考开关，推理强度仅采用协议能够编码的交集。
                </FieldTip>
                <div v-if="reasoningLoading" role="status">正在读取推理选项…</div>
                <div v-else-if="reasoningError" role="status">
                  {{ reasoningError }}
                  <ElButton link type="primary" @click="reasoningRefresh++">重试读取</ElButton>
                </div>
                <FieldTip
                  v-else-if="effectiveReasoningOptions"
                  :id="formTipId + '-reasoning-source'"
                >
                  来源：{{
                    effectiveReasoningOptions.source === 'DISCOVERY_METADATA'
                      ? '供应商实时发现（候选，待兼容性测试）'
                      : effectiveReasoningOptions.source === 'OFFICIAL_DOCUMENTATION'
                        ? '供应商官方文档'
                        : effectiveReasoningOptions.source === 'PROTOCOL_DEFINITION'
                          ? '协议候选，待兼容性测试'
                          : '未验证，能力尚未确认'
                  }}
                  <a
                    v-if="
                      effectiveReasoningOptions.source_url &&
                      /^https?:\/\//.test(effectiveReasoningOptions.source_url)
                    "
                    :href="effectiveReasoningOptions.source_url"
                    target="_blank"
                    rel="noopener noreferrer"
                    >查看依据</a
                  >
                </FieldTip>
              </ElFormItem>
            </div>
          </ElCollapseItem>
          <ElCollapseItem name="pricing" :title="`成本计量 · ${form.currency || '未配置币种'}`">
            <div class="admin-form-grid model-fields-grid">
              <ElFormItem label="币种">
                <ElInput
                  v-described-by="formTipId + '-field-16'"
                  :aria-describedby="formTipId + '-field-16'"
                  v-model="form.currency"
                  maxlength="3"
                />

                <FieldTip :id="formTipId + '-field-16'"
                  >填写三位币种代码，例如 CNY 或 USD。</FieldTip
                >
              </ElFormItem>
              <template v-if="isMediaKind">
                <ElFormItem label="媒体计费单位" :error="fieldErrors['media_pricing.unit']">
                  <ElSelect
                    v-described-by="formTipId + '-media-price-unit'"
                    v-model="mediaPricingUnit"
                    clearable
                    placeholder="未配置价格"
                    :aria-describedby="formTipId + '-media-price-unit'"
                  >
                    <ElOption
                      v-for="unit in mediaPriceUnits[form.kind]"
                      :key="unit"
                      :value="unit"
                      :label="mediaPriceUnitLabels[unit]"
                    />
                  </ElSelect>
                  <FieldTip :id="formTipId + '-media-price-unit'">
                    按供应商对当前模型的计费规则选择。留空表示缺价，不会视为免费；填写 0
                    表示明确零价。
                  </FieldTip>
                </ElFormItem>
                <template v-if="form.media_pricing">
                  <ElFormItem
                    v-if="form.media_pricing.unit === 'VIDEO_SECOND'"
                    label="视频单价适用分辨率"
                    :error="fieldErrors['media_pricing.video_resolution']"
                  >
                    <ElSelect
                      v-described-by="formTipId + '-media-price-resolution'"
                      v-model="form.media_pricing.video_resolution"
                      clearable
                      placeholder="请选择分辨率"
                      :aria-describedby="formTipId + '-media-price-resolution'"
                    >
                      <ElOption
                        v-for="resolution in ['480P', '720P', '1080P']"
                        :key="resolution"
                        :value="resolution"
                        :label="resolution"
                      />
                    </ElSelect>
                    <FieldTip :id="formTipId + '-media-price-resolution'">
                      此单价仅适用于所选分辨率。实际任务分辨率不匹配时保留缺价。
                    </FieldTip>
                  </ElFormItem>
                  <template v-if="form.media_pricing.unit === 'TOKENS'">
                    <ElFormItem
                      v-for="f in [
                        { key: 'input_price_per_million_cents', label: '每百万输入 Token（分）' },
                        { key: 'output_price_per_million_cents', label: '每百万输出 Token（分）' }
                      ] as const"
                      :key="f.key"
                      :label="f.label"
                      :error="fieldErrors['media_pricing.' + f.key]"
                    >
                      <ElInput
                        v-described-by="formTipId + '-media-price-' + f.key"
                        v-model="form.media_pricing[f.key]"
                        inputmode="decimal"
                        :aria-describedby="formTipId + '-media-price-' + f.key"
                      />
                      <FieldTip :id="formTipId + '-media-price-' + f.key">
                        按每百万 Token 填写供应商成本，单位为分，最多 6
                        位小数。输入、输出单价均须明确填写。
                      </FieldTip>
                    </ElFormItem>
                  </template>
                  <ElFormItem
                    v-else
                    label="每单位价格（分）"
                    :error="fieldErrors['media_pricing.price_per_unit_cents']"
                  >
                    <ElInput
                      v-described-by="formTipId + '-media-price-quantity'"
                      v-model="form.media_pricing.price_per_unit_cents"
                      inputmode="decimal"
                      :aria-describedby="formTipId + '-media-price-quantity'"
                    />
                    <FieldTip :id="formTipId + '-media-price-quantity'">
                      {{
                        mediaPriceUnitLabels[form.media_pricing.unit]
                      }}的供应商成本，单位为分，最多 6 位小数。
                    </FieldTip>
                  </ElFormItem>
                </template>
              </template>
              <template v-else>
                <ElFormItem
                  v-for="f in [
                    { key: 'input_price_per_million_cents', label: '每百万输入 Token（分）' },
                    { key: 'output_price_per_million_cents', label: '每百万输出 Token（分）' },
                    { key: 'price_per_quantity_cents', label: '单次计量价格（分）' }
                  ] as const"
                  :key="f.key"
                  :label="f.label"
                  :error="fieldErrors[f.key]"
                >
                  <ElInput
                    v-described-by="
                      formTipId + '-field-17' + '-' + encodeURIComponent(String(f.key))
                    "
                    :aria-describedby="
                      formTipId + '-field-17' + '-' + encodeURIComponent(String(f.key))
                    "
                    v-model="form[f.key]"
                    inputmode="decimal"
                  />

                  <FieldTip :id="formTipId + '-field-17' + '-' + encodeURIComponent(String(f.key))"
                    >以分为单位填写成本单价，最多 6 位小数；Token 单价按每百万 Tokens
                    计量。</FieldTip
                  >
                </ElFormItem>
                <template v-if="form.kind === 'LLM'">
                  <ElFormItem label="缓存输入计价">
                    <ElSelect
                      v-described-by="formTipId + '-cache-pricing-mode'"
                      :aria-describedby="formTipId + '-cache-pricing-mode'"
                      v-model="cachePricingMode"
                    >
                      <ElOption value="UNIFIED" label="统一输入价" />
                      <ElOption value="CACHE_READ" label="缓存命中分价" />
                      <ElOption value="CACHE_READ_WRITE" label="缓存命中与写入分价" />
                    </ElSelect>
                    <FieldTip :id="formTipId + '-cache-pricing-mode'">
                      统一输入价使用上方输入单价；分价模式下，普通输入仍使用上方输入单价。
                      缓存价格留空表示未知，相关费用待核实；填写 0 表示明确零价。
                    </FieldTip>
                  </ElFormItem>
                  <template v-if="form.cache_pricing">
                    <ElFormItem
                      label="缓存命中价格（分 / 百万 Token）"
                      :error="fieldErrors['cache_pricing.read_price_per_million_cents']"
                    >
                      <ElInput
                        v-described-by="formTipId + '-cache-read-price'"
                        :aria-describedby="formTipId + '-cache-read-price'"
                        v-model="form.cache_pricing.read_price_per_million_cents"
                        inputmode="decimal"
                      />
                      <FieldTip :id="formTipId + '-cache-read-price'">
                        按供应商规则填写缓存命中部分的完整输入单价，单位为分 / 百万 Token，最多 6
                        位小数；不自动套用折扣。
                      </FieldTip>
                    </ElFormItem>
                    <ElFormItem
                      v-if="form.cache_pricing.mode === 'CACHE_READ_WRITE'"
                      label="缓存写入价格（分 / 百万 Token）"
                      :error="fieldErrors['cache_pricing.write_price_per_million_cents']"
                    >
                      <ElInput
                        v-described-by="formTipId + '-cache-write-price'"
                        :aria-describedby="formTipId + '-cache-write-price'"
                        v-model="form.cache_pricing.write_price_per_million_cents"
                        inputmode="decimal"
                      />
                      <FieldTip :id="formTipId + '-cache-write-price'">
                        写入价是该部分输入的完整单价，不是额外加价。单位为分 / 百万 Token，最多 6
                        位小数；留空保留未知。
                      </FieldTip>
                    </ElFormItem>
                  </template>
                  <ElFormItem
                    label="峰谷计价"
                    :error="fieldErrors.time_pricing"
                    data-time-pricing-field="time_pricing"
                  >
                    <ElSwitch
                      v-described-by="formTipId + '-time-pricing'"
                      :aria-describedby="formTipId + '-time-pricing'"
                      v-model="timePricingEnabled"
                      active-text="启用时段规则"
                    />
                    <FieldTip :id="formTipId + '-time-pricing'"
                      >模型单价乘以对应时段倍率进行估算。覆盖日期外、原调用开始时间缺失或倍率未知时，费用保留待核实；供应商实扣以账单为准。</FieldTip
                    >
                  </ElFormItem>
                  <ModelTimePricingEditor
                    v-if="form.time_pricing"
                    v-model="form.time_pricing"
                    :errors="fieldErrors"
                    error-prefix="time_pricing"
                    @field-change="refreshTimePricingErrors"
                  />
                  <ModelTimePricingPresets
                    :disabled="formBusy"
                    :can-apply="
                      allowed(item ? 'foundation.models.update' : 'foundation.models.create')
                    "
                    @apply="form.time_pricing = $event"
                  />
                </template>
              </template>
            </div>
          </ElCollapseItem>
          <ElCollapseItem
            v-if="form.kind === 'LLM' && form.image_policy"
            name="images"
            :title="`图片能力与限额 · ${form.image_policy.enabled ? '已启用' : '未启用'}`"
          >
            <template v-if="form.kind === 'LLM' && form.image_policy">
              <ElFormItem label="允许图片输入" :error="fieldErrors['image_policy.enabled']">
                <ElSwitch
                  v-described-by="formTipId + '-field-18'"
                  :aria-describedby="formTipId + '-field-18'"
                  v-model="form.image_policy.enabled"
                />

                <FieldTip :id="formTipId + '-field-18'"
                  >当前模型的用户图片能力：{{ capabilityLabels[imageSupport] }}；工具图片能力：{{
                    capabilityLabels[toolImageSupport]
                  }}。未验证的能力需依据供应商说明显式配置，并完成具体模型的图片测试。</FieldTip
                >
                <ElButton v-if="imageDefaults" @click="adoptImagePolicy">采用目录图片策略</ElButton>
              </ElFormItem>
              <div v-if="form.image_policy.enabled" class="admin-form-grid">
                <ElFormItem label="允许工具图片" :error="fieldErrors['image_policy.tool_images']">
                  <ElSwitch
                    v-described-by="formTipId + '-field-19'"
                    :aria-describedby="formTipId + '-field-19'"
                    v-model="form.image_policy.tool_images"
                  />

                  <FieldTip :id="formTipId + '-field-19'"
                    >允许将工具返回的图片加入模型输入，仍受图片资源限制。</FieldTip
                  >
                </ElFormItem>
                <ElFormItem
                  v-for="f in [
                    { key: 'max_images', label: '最多图片数' },
                    { key: 'max_image_bytes', label: '单图最大字节数' },
                    { key: 'max_request_bytes', label: '请求最大字节数' },
                    { key: 'max_dimension', label: '最大尺寸' },
                    { key: 'tile_size', label: '切片尺寸' },
                    { key: 'base_tokens', label: '基础 Token' },
                    { key: 'tile_tokens', label: '每切片 Token' }
                  ] as const"
                  :key="f.key"
                  :label="f.label"
                >
                  <ElInputNumber
                    v-described-by="
                      formTipId + '-field-20' + '-' + encodeURIComponent(String(f.key))
                    "
                    :aria-describedby="
                      formTipId + '-field-20' + '-' + encodeURIComponent(String(f.key))
                    "
                    v-model="form.image_policy[f.key]"
                    :min="0"
                    :precision="0"
                  />

                  <FieldTip
                    :id="formTipId + '-field-20' + '-' + encodeURIComponent(String(f.key))"
                    >{{ imagePolicyHelp(f.key) }}</FieldTip
                  >
                </ElFormItem>
                <ElFormItem label="策略版本">
                  <ElInput
                    v-described-by="formTipId + '-field-21'"
                    :aria-describedby="formTipId + '-field-21'"
                    v-model="form.image_policy.version"
                  />

                  <FieldTip :id="formTipId + '-field-21'"
                    >标识当前图片处理与计量策略，修改后随模型配置发布给新执行。</FieldTip
                  >
                </ElFormItem>
              </div>
            </template>
          </ElCollapseItem>
        </ElCollapse>
        <div class="admin-actions">
          <ElButton
            v-permission="[item ? 'foundation.models.update' : 'foundation.models.create']"
            type="primary"
            native-type="submit"
            :loading="action.busy.value"
            >保存草稿</ElButton
          >
          <ElButton
            v-permission="['foundation.models.run']"
            v-if="!isMediaKind"
            :disabled="formBusy"
            @click="test"
            >测试当前配置</ElButton
          >
          <span role="status">{{
            testedInput === JSON.stringify(form) && testResult
              ? (testIncomplete ? '本次测试尚未完成；上次结果：' : '') + testResult
              : ''
          }}</span>
          <p class="admin-field-help">
            可直接测试当前输入，无需先保存；测试会校验已填写的计价等完整配置，但不会保存或发布。
          </p>
        </div>
        <section
          v-if="compatibilityReport"
          class="compatibility-report"
          aria-label="模型兼容性测试结果"
        >
          <ElAlert
            v-if="compatibilityStale"
            title="配置已变更，以下测试结果已失效。请重新测试当前配置。"
            type="warning"
            :closable="false"
            show-icon
          />
          <p v-if="testIncomplete" class="admin-field-help" role="status">
            {{
              action.busy.value
                ? '正在测试当前配置；以下为之前的测试结果。'
                : '本次测试尚未完成；以下为之前的测试结果，请根据本次错误重试。'
            }}
          </p>
          <h3>兼容性测试 · {{ probeLabels[compatibilityReport.status] }}</h3>
          <p class="admin-field-help">
            {{
              compatibilityReport.draft_revision === 0
                ? '未保存配置（仅本次测试）'
                : `草稿修订 ${compatibilityReport.draft_revision}`
            }}
            · {{ formatDateTime(compatibilityReport.tested_at) }} · 总耗时
            {{ compatibilityReport.duration_ms }} ms
          </p>
          <ElTable :data="compatibilityChecks" aria-label="逐项兼容性检查">
            <ElTableColumn prop="label" label="检查项目" min-width="160" />
            <ElTableColumn label="结果" min-width="110"
              ><template #default="{ row }">{{
                probeLabels[row.status as ModelProbeStatus]
              }}</template></ElTableColumn
            >
            <ElTableColumn label="耗时" min-width="100"
              ><template #default="{ row }">{{ row.duration_ms }} ms</template></ElTableColumn
            >
            <ElTableColumn label="诊断说明" min-width="200"
              ><template #default="{ row }"
                ><span :title="row.reason_code || undefined">{{
                  probeReasonLabel(row.reason_code)
                }}</span></template
              ></ElTableColumn
            >
          </ElTable>
          <p v-if="compatibilityReport.reason_code" class="admin-field-help">
            <span :title="compatibilityReport.reason_code"
              >本次诊断：{{ probeReasonLabel(compatibilityReport.reason_code) }}</span
            >
          </p>
          <p class="admin-field-help">
            配置参数接受仅表示供应商接受了请求；不代表推理效果或缓存命中已验证。未执行和结果未知的项目均未通过验证。
          </p>
        </section>
      </ElForm>
      <ModelMediaProbe
        :kind="form.kind"
        :service-id="form.service_id"
        :protocol="form.protocol"
        :disabled="action.busy.value"
        :prepare="mediaConfiguration"
        @busy="mediaBusy = $event"
        @pending-operation="mediaPendingOperation = $event"
      />
    </ElCard>
    <ElDrawer
      class="admin-drawer"
      direction="rtl"
      append-to-body
      v-model="historyOpen"
      title="模型发布历史"
      :before-close="historyClose"
      :close-on-click-modal="false"
      size="680px"
    >
      <FeedbackNotice
        v-if="action.error.value"
        :title="action.error.value"
        :closable="false"
        type="error"
      />
      <ElSkeleton v-if="action.busy.value" :rows="4" />
      <ElEmpty v-else-if="!history.length" description="暂无发布历史" />
      <p v-if="dirty" class="admin-field-help">请先保存当前配置，再恢复历史修订。</p>
      <ElCard class="admin-card" v-for="version in history" :key="version.revision">
        <h3>修订 {{ version.revision }}</h3>
        <p>{{ version.name }} · {{ version.model_id }}</p>
        <ElTag v-if="version.revision === item?.published_revision">当前发布</ElTag>
        <ElCollapse
          ><ElCollapseItem title="查看配置">
            <pre class="readable">{{ JSON.stringify(version, null, 2) }}</pre>
          </ElCollapseItem></ElCollapse
        >
        <ElButton
          v-permission="['foundation.models.update']"
          :disabled="dirty || formBusy"
          @click="rollback(version)"
          >恢复为草稿</ElButton
        >
      </ElCard>
    </ElDrawer>
  </AdminPage>
</template>

<style scoped>
.model-settings-card {
  container-type: inline-size;
}
/* 长错误及放大文字参与布局，避免覆盖下一行字段。 */
.model-settings-card :deep(.el-form-item__error) {
  position: static;
  flex-basis: 100%;
}
.model-fields-grid {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}
.model-identity-field {
  grid-column: span 2;
}
@container (max-width: 780px) {
  .model-fields-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .model-identity-field {
    grid-column: auto;
  }
}
@container (max-width: 520px) {
  .model-fields-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
.model-discovery-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  color: var(--admin-muted);
}
.model-discovery-empty {
  margin: 0;
  padding: 12px 20px;
  color: var(--admin-muted);
  line-height: 1.6;
  overflow-wrap: anywhere;
}
:global(.model-discovery-options) {
  max-width: calc(100vw - 32px);
}
:global(.model-discovery-options .el-select-dropdown__item) {
  height: auto;
  min-height: 32px;
  padding-block: 8px;
  line-height: 1.5;
  white-space: normal;
  overflow-wrap: anywhere;
}
:global(.model-discovery-options .el-select-dropdown__item.is-selected) {
  color: var(--admin-text);
}
.model-validation-summary {
  margin-bottom: 24px;
  padding: 16px;
  border: 1px solid var(--admin-border);
  border-radius: var(--el-border-radius-base);
}
.model-validation-summary ul {
  margin-block: 8px 0;
  padding-inline-start: 24px;
}
.model-validation-summary :deep(.el-button) {
  white-space: normal;
  text-align: start;
  height: auto;
  min-height: 24px;
  color: var(--admin-text);
  text-decoration: underline;
}
.compatibility-report {
  margin-top: 16px;
  padding: 16px;
  border: 1px solid var(--el-border-color);
  border-radius: var(--el-border-radius-base);
}
</style>
