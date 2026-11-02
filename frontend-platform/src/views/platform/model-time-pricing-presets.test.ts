import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { ElButton, ElDrawer, ElFormItem, ElInput } from 'element-plus'
import ModelTimePricingPresets from './ModelTimePricingPresets.vue'
import ModelTimePricingEditor from './ModelTimePricingEditor.vue'
import { cloneTimePricingPolicy } from './model-time-pricing'
import type { ModelTimePricingPolicy, ModelTimePricingPreset } from '../../api'
const state = vi.hoisted(() => ({ confirm: vi.fn(async () => true), denied: [] as string[] }))
vi.mock('vue-router', () => ({ onBeforeRouteLeave: vi.fn(), onBeforeRouteUpdate: vi.fn() }))
vi.mock('../../router', () => ({ default: {} }))
vi.mock('../../stores/auth', () => ({ useAuthStore: () => ({ logout: vi.fn() }) }))
vi.mock('../../composables/actionPermissions', () => ({
  useActionPermissions: () => ({
    allowed: (permission: string) => !state.denied.includes(permission),
    checked: (_permission: string, action: () => unknown) => action()
  })
}))
vi.mock('@skillforge/shared/feedback', async (importOriginal) => ({
  ...(await importOriginal<object>()),
  confirm: state.confirm
}))
function policy(): ModelTimePricingPolicy {
  return {
    time_zone: 'Asia/Shanghai',
    valid_from: '2032-01-01',
    valid_through: '2032-12-31',
    peak_weekdays: ['MONDAY'],
    peak_periods: [{ start: '01:00:00.000000001', end: '04:00:00.999999999' }],
    peak_date_ranges: [],
    off_peak_date_ranges: [],
    peak_multiplier: '1',
    off_peak_multiplier: '0.125000000001',
    applicable_models: [
      {
        provider_id: 'editable-provider',
        service_id: null,
        profile_version: null,
        model_id: 'new-model'
      }
    ],
    source_url: 'https://example.cn/pricing',
    source_version: '2032-v1',
    calendar_source_url: null
  }
}
const root = '/api/v1/admin/models/time-pricing-presets'
const calls: { path: string; method: string; body: string | undefined }[] = []
let wrapper: VueWrapper | undefined
let draft: ModelTimePricingPreset
let history: ModelTimePricingPreset[]
let failList: boolean
let failHistory: boolean
let failDetail: boolean
function response(value: unknown) {
  return new Response(
    JSON.stringify({ code: 'OK', data: value, message: '成功', trace_id: 'preset-fixture' }),
    {
      status: 200,
      headers: { 'Content-Type': 'application/json' }
    }
  )
}
beforeEach(() => {
  state.confirm.mockReset().mockResolvedValue(true)
  state.denied.length = 0
  calls.length = 0
  failList = false
  failHistory = false
  failDetail = false
  draft = {
    id: '912345678901234567',
    name: '可维护预设',
    revision: 2,
    published_revision: 1,
    has_unpublished_changes: true,
    policy: policy()
  }
  history = [
    {
      ...draft,
      revision: 1,
      has_unpublished_changes: false,
      policy: cloneTimePricingPolicy(draft.policy)
    }
  ]
  localStorage.clear()
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = new URL(String(input), 'http://127.0.0.1')
      const path = url.pathname
      const method = init?.method ?? 'GET'
      const body = init?.body === undefined ? undefined : String(init.body)
      calls.push({ path, method, body })
      if (path === root && method === 'GET') {
        if (failList) throw new Error('预设目录读取失败')
        return response([draft])
      }
      if (path === root + '/detail') {
        expect(url.searchParams.get('id')).toBe(draft.id)
        if (failDetail) throw new Error('草稿读取失败')
        return response(draft)
      }
      if (path === root + '/history') {
        if (failHistory) throw new Error('发布历史读取失败')
        return response(history)
      }
      if (path === root && method === 'POST') {
        draft = {
          ...JSON.parse(body!),
          id: '912345678901234568',
          revision: 1,
          published_revision: null,
          has_unpublished_changes: true
        }
        history = []
        return response(draft)
      }
      if (path === root + '/update') {
        const command = JSON.parse(body!)
        expect(command.id).toBe(draft.id)
        expect(command.payload.expected_revision).toBe(draft.revision)
        draft = {
          ...draft,
          name: command.payload.name,
          policy: command.payload.policy,
          revision: draft.revision + 1,
          has_unpublished_changes: true
        }
        return response(draft)
      }
      if (path === root + '/publish') {
        expect(JSON.parse(body!)).toEqual({ id: draft.id, expected_revision: draft.revision })
        draft = {
          ...draft,
          published_revision: (draft.published_revision ?? 0) + 1,
          has_unpublished_changes: false
        }
        history = [
          {
            ...draft,
            revision: draft.published_revision!,
            policy: cloneTimePricingPolicy(draft.policy)
          },
          ...history
        ]
        return response(draft)
      }
      throw new Error('Unexpected request: ' + path)
    })
  )
})
afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  vi.unstubAllGlobals()
  localStorage.clear()
  document.body.innerHTML = ''
})
async function click(text: string) {
  const button = wrapper!.findAllComponents(ElButton).find((v) => v.text() === text)
  if (!button) throw new Error('未找到按钮 ' + text)
  await button.trigger('click')
  await flushPromises()
}
async function open(canApply = true) {
  wrapper = mount(ModelTimePricingPresets, {
    props: { disabled: false, canApply },
    attachTo: document.body
  })
  await click('管理或采用峰谷预设')
}
function field(label: string) {
  return wrapper!.findAllComponents(ElFormItem).find((v) => v.props('label') === label)!
}
async function configure(value = policy()) {
  wrapper!.getComponent(ModelTimePricingEditor).vm.$emit('update:modelValue', value)
  await flushPromises()
}

it('新建保存发布预设与模型写入分离，采用完整已发布版本且不共享引用', async () => {
  await open()
  await click('新建预设')
  await field('预设名称').getComponent(ElInput).get('input').setValue('新供应商规则')
  await configure()
  await click('保存预设草稿')
  const create = calls.find((v) => v.path === root && v.method === 'POST')!
  expect(JSON.parse(create.body!)).toEqual({ name: '新供应商规则', policy: policy() })
  expect(wrapper!.emitted('apply')).toBeUndefined()
  await click('发布预设')
  expect(calls.filter((v) => !v.path.startsWith(root))).toHaveLength(0)
  await click('采用修订 1')
  const emitted = wrapper!.emitted('apply')![0]![0] as ModelTimePricingPolicy
  expect(emitted).toEqual(policy())
  emitted.peak_periods[0]!.start = '02:00:00'
  expect(history[0]!.policy.peak_periods[0]!.start).toBe('01:00:00.000000001')
  expect(localStorage.length).toBe(0)
})

it('当前草稿已改价仍可采用较早已发布修订，保存不会自动发布或修改模型', async () => {
  await open()
  await click('可维护预设')
  const changed = policy()
  changed.off_peak_multiplier = '0.75'
  changed.source_version = '2032-v2'
  await configure(changed)
  await click('保存预设草稿')
  expect(calls.filter((v) => v.path.endsWith('/publish'))).toHaveLength(0)
  await click('采用修订 1')
  expect(wrapper!.emitted('apply')![0]![0]).toEqual(policy())
  expect(draft.policy.off_peak_multiplier).toBe('0.75')
})

it('目录读取失败不显示空列表，重试成功后可选择', async () => {
  failList = true
  await open()
  expect(wrapper!.text()).not.toContain('暂无峰谷预设')
  expect(wrapper!.findAllComponents(ElButton).some((v) => v.text() === '可维护预设')).toBe(false)
  failList = false
  await click('刷新预设')
  await click('可维护预设')
  expect(field('预设名称').getComponent(ElInput).props('modelValue')).toBe('可维护预设')
})

it('发布历史失败不阻断草稿编辑，重试历史保留未保存输入', async () => {
  failHistory = true
  await open()
  await click('可维护预设')
  expect(field('预设名称').getComponent(ElInput).props('modelValue')).toBe('可维护预设')
  expect(document.body.textContent).not.toContain('尚无已发布版本')
  await field('预设名称').getComponent(ElInput).get('input').setValue('未保存名称')
  const detailsBefore = calls.filter((call) => call.path === root + '/detail').length
  failHistory = false
  await click('重试读取发布历史')
  expect(calls.filter((call) => call.path === root + '/detail')).toHaveLength(detailsBefore)
  expect(field('预设名称').getComponent(ElInput).props('modelValue')).toBe('未保存名称')
  expect(
    wrapper!.findAllComponents(ElButton).some((button) => button.text() === '采用修订 1')
  ).toBe(true)
})

it('草稿读取失败后只重试草稿，保留已取得的发布历史', async () => {
  failDetail = true
  await open()
  await click('可维护预设')
  const historyCalls = calls.filter((call) => call.path === root + '/history').length
  failDetail = false
  await click('重试读取所选预设')
  expect(calls.filter((call) => call.path === root + '/history')).toHaveLength(historyCalls)
  expect(field('预设名称').getComponent(ElInput).props('modelValue')).toBe('可维护预设')
  expect(
    wrapper!.findAllComponents(ElButton).some((button) => button.text() === '采用修订 1')
  ).toBe(true)
})

it('草稿读取失败后新建预设不再显示旧的重试入口', async () => {
  failDetail = true
  await open()
  await click('可维护预设')
  await click('新建预设')
  expect(
    wrapper!.findAllComponents(ElButton).some((button) => button.text() === '重试读取所选预设')
  ).toBe(false)
  expect(field('预设名称').getComponent(ElInput).props('modelValue')).toBe('')
})

it('关闭未保存预设要求丢弃确认，取消后保留输入', async () => {
  await open()
  await click('可维护预设')
  await field('预设名称').getComponent(ElInput).get('input').setValue('未保存名称')
  state.confirm.mockResolvedValue(false)
  await wrapper!.getComponent(ElDrawer).props('beforeClose')!(() => {})
  expect(wrapper!.getComponent(ElDrawer).props('modelValue')).toBe(true)
  expect(field('预设名称').getComponent(ElInput).props('modelValue')).toBe('未保存名称')
  state.confirm.mockResolvedValue(true)
  await wrapper!.getComponent(ElDrawer).props('beforeClose')!(() => {})
  expect(wrapper!.getComponent(ElDrawer).props('modelValue')).toBe(false)
  expect(calls.filter((v) => v.method !== 'GET')).toHaveLength(0)
})

it('无模型编辑权限仍可读取已发布规则，但不能采用或创建预设', async () => {
  state.denied.push(
    'foundation.models.create',
    'foundation.models.update',
    'foundation.models.publish'
  )
  await open(false)
  await click('可维护预设')
  expect(
    wrapper!
      .findAllComponents(ElButton)
      .find((v) => v.text() === '采用修订 1')!
      .props('disabled')
  ).toBe(true)
  expect(wrapper!.findAllComponents(ElButton).some((v) => v.text() === '新建预设')).toBe(false)
  expect(wrapper!.findAllComponents(ElButton).some((v) => v.text() === '发布预设')).toBe(false)
})
