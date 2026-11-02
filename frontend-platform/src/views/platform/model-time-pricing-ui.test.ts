import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { defineComponent, ref } from 'vue'
import { DOMWrapper, flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { ElButton, ElCollapseItem, ElDrawer, ElForm, ElFormItem, ElInput } from 'element-plus'
import ModelTimePricingEditor from './ModelTimePricingEditor.vue'
import ModelTimePricingPresets from './ModelTimePricingPresets.vue'
import { emptyTimePricingPolicy, timePricingErrors } from './model-time-pricing'
import type { ModelTimePricingPolicy, ModelTimePricingPreset } from '../../api'
const state = vi.hoisted(() => ({ denied: [] as string[], confirm: vi.fn(async () => true) }))
vi.mock('vue-router', () => ({ onBeforeRouteLeave: vi.fn(), onBeforeRouteUpdate: vi.fn() }))
vi.mock('../../router', () => ({ default: {} }))
vi.mock('../../stores/auth', () => ({ useAuthStore: () => ({ logout: vi.fn() }) }))
vi.mock('../../composables/actionPermissions', () => ({
  useActionPermissions: () => ({
    allowed: (permission: string) => !state.denied.includes(permission),
    checked: (_permission: string, action: () => unknown) => action()
  })
}))
vi.mock('@skillforge/shared/feedback', async (original) => ({
  ...(await original<object>()),
  confirm: state.confirm
}))
function policy(): ModelTimePricingPolicy {
  return {
    ...emptyTimePricingPolicy(),
    time_zone: 'Asia/Shanghai',
    valid_from: '2032-01-01',
    valid_through: '2032-12-31',
    peak_weekdays: ['MONDAY'],
    peak_multiplier: '1',
    off_peak_multiplier: '0',
    peak_periods: [
      { start: '09:00:00.000000001', end: '12:00:00' },
      { start: '12:00', end: '14:00:00.987654321' }
    ]
  }
}
let wrapper: VueWrapper | undefined
const writes: { path: string; body: unknown }[] = []
const root = '/api/v1/admin/models/time-pricing-presets'
let failRefresh = false
beforeEach(() => {
  writes.length = 0
  state.denied.length = 0
  failRefresh = false
  state.confirm.mockReset().mockResolvedValue(true)
  localStorage.clear()
  const draft: ModelTimePricingPreset = {
    id: '912345678901234567',
    name: '页面规则验收',
    revision: 2,
    published_revision: 1,
    has_unpublished_changes: false,
    policy: policy()
  }
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = new URL(String(input), 'http://127.0.0.1').pathname
      if (init?.method && init.method !== 'GET') {
        const body = JSON.parse(String(init.body))
        writes.push({ path, body })
        return response({ ...draft, ...body, has_unpublished_changes: true })
      }
      if (path === root) {
        if (failRefresh) throw new Error('预设目录刷新失败')
        return response([draft])
      }
      if (path === root + '/detail') return response(draft)
      if (path === root + '/history') return response([{ ...draft, revision: 1 }])
      throw new Error('Unexpected request ' + path)
    })
  )
})
function response(data: unknown) {
  return new Response(JSON.stringify({ code: 'OK', message: '成功', trace_id: 'ui-test', data }), {
    status: 200,
    headers: { 'Content-Type': 'application/json' }
  })
}
afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  document.body.innerHTML = ''
  localStorage.clear()
  vi.unstubAllGlobals()
})
async function click(label: string) {
  const button = wrapper!.findAllComponents(ElButton).find((value) => value.text() === label)
  if (!button) throw new Error('Missing button ' + label)
  await button.trigger('click')
  await flushPromises()
}
async function editor(initial = policy(), validated = false) {
  wrapper = mount(
    defineComponent({
      components: { ElForm, ModelTimePricingEditor },
      setup() {
        const value = ref(initial)
        const errors = ref(validated ? timePricingErrors(initial) : {})
        function changed() {
          if (validated) errors.value = timePricingErrors(value.value)
        }
        return { value, errors, changed }
      },
      template:
        '<ElForm><ModelTimePricingEditor v-model="value" :errors="errors" @field-change="changed" /></ElForm>'
    }),
    { attachTo: document.body }
  )
  await flushPromises()
}
function field(path: string) {
  const input = document.querySelector<HTMLInputElement>(
    `[data-time-pricing-field="policy.${path}"] input`
  )
  if (!input) throw new Error('Missing field ' + path)
  return new DOMWrapper(input)
}
async function presets() {
  wrapper = mount(ModelTimePricingPresets, {
    props: { disabled: false, canApply: true },
    attachTo: document.body
  })
  await click('管理或采用峰谷预设')
}
it('新增时段聚焦新行首字段，输入不重新创建控件或改变其他纳秒值', async () => {
  await editor()
  await click('添加峰时段')
  const input = field('peak_periods.2')
  expect(document.activeElement).toBe(input.element)
  await input.setValue('15:00')
  expect(field('peak_periods.2').element).toBe(input.element)
  expect(document.activeElement).toBe(input.element)
  expect(field('peak_periods.0').element).toHaveProperty('value', '09:00:00.000000001')
})
it('本地删除可撤销，剩余行保持控件身份和原值，删除后焦点有可达落点', async () => {
  await editor()
  const second = field('peak_periods.1').element
  await wrapper!.get('button[aria-label="删除峰时段 1"]').trigger('click')
  await flushPromises()
  expect(field('peak_periods.0').element).toBe(second)
  expect(document.activeElement?.textContent).toContain('添加峰时段')
  await click('撤销删除')
  expect(field('peak_periods.1').element).toBe(second)
  expect(field('peak_periods.0').element).toHaveProperty('value', '09:00:00.000000001')
  expect(document.activeElement).toBe(field('peak_periods.0').element)
  expect(writes).toHaveLength(0)
})
it('删除与恢复后按当前行重算已提交错误，不让原第二行错误串到别处', async () => {
  const initial = policy()
  initial.peak_periods[1] = { start: '10:00', end: '11:00' }
  await editor(initial, true)
  await vi.waitFor(() => expect(wrapper!.text()).toContain('峰时段不能重叠'))
  const originalSecond = field('peak_periods.1').element
  expect(field('peak_periods.0').attributes('aria-invalid')).toBe('false')
  expect(field('peak_periods.1').attributes('aria-invalid')).toBe('true')
  await wrapper!.get('button[aria-label="删除峰时段 1"]').trigger('click')
  await flushPromises()
  const remaining = wrapper!
    .findAllComponents(ElFormItem)
    .find((item) => item.props('label') === '峰时段 1')!
  expect(remaining.props('error')).toBeUndefined()
  expect(field('peak_periods.0').element).toBe(originalSecond)
  expect(field('peak_periods.0').attributes('aria-invalid')).toBe('false')
  expect(field('peak_periods.0').attributes('aria-describedby')).not.toContain('-error-')
  await click('撤销删除')
  expect(field('peak_periods.0').attributes('aria-invalid')).toBe('false')
  expect(field('peak_periods.1').element).toBe(originalSecond)
  expect(field('peak_periods.1').attributes('aria-invalid')).toBe('true')
  expect(field('peak_periods.1').attributes('aria-describedby')).toContain('-error-peak_periods-1')
  expect(
    wrapper!
      .findAllComponents(ElFormItem)
      .find((item) => item.props('label') === '峰时段 2')!
      .props('error')
  ).toBe('峰时段不能重叠')
})
it('真实输入的无效状态与错误描述在修正后更新，帮助描述继续保留', async () => {
  const initial = policy()
  initial.time_zone = 'Invalid/TimeZone'
  initial.peak_multiplier = '-1'
  await editor(initial, true)
  const zone = field('time_zone')
  await vi.waitFor(() =>
    expect(
      document.querySelector('[data-time-pricing-field="policy.time_zone"] .el-form-item__error')
        ?.textContent
    ).toContain('有效的 IANA 时区')
  )
  const described = zone.attributes('aria-describedby')!.split(' ')
  const errorId = described.find((id) =>
    document.getElementById(id)?.classList.contains('el-form-item__error')
  )!
  const helpId = described.find((id) =>
    document.getElementById(id)?.classList.contains('admin-field-help')
  )!
  expect(zone.attributes('aria-invalid')).toBe('true')
  expect(document.getElementById(errorId)?.textContent).toContain('有效的 IANA 时区')
  expect(document.getElementById(helpId)?.textContent).toContain('Asia/Shanghai')
  expect(field('peak_multiplier').attributes('aria-invalid')).toBe('true')
  await zone.setValue('Asia/Shanghai')
  await flushPromises()
  expect(zone.attributes('aria-invalid')).toBe('false')
  expect(zone.attributes('aria-describedby')!.split(' ')).toContain(helpId)
  expect(zone.attributes('aria-describedby')!.split(' ')).not.toContain(errorId)
  await vi.waitFor(() => expect(document.getElementById(errorId)).toBeNull())
  expect(field('peak_multiplier').attributes('aria-invalid')).toBe('true')
  await field('peak_multiplier').setValue('1')
  await flushPromises()
  expect(field('peak_multiplier').attributes('aria-invalid')).toBe('false')
  expect(writes).toHaveLength(0)
})
it('星期组、日期范围、适用范围与来源的真实控件反映错误，清除后不残留错误描述', async () => {
  const value = policy()
  value.peak_date_ranges = [{ from: '2032-01-02', through: '2032-01-03' }]
  value.applicable_models = [
    { provider_id: 'provider', service_id: null, profile_version: null, model_id: null }
  ]
  wrapper = mount(ModelTimePricingEditor, {
    props: {
      modelValue: value,
      errors: {
        'policy.peak_weekdays': '请修正常规峰日',
        'policy.peak_date_ranges.0': '日期范围须有效且位于规则覆盖日期内',
        'policy.applicable_models': '当前模型不在规则适用范围内',
        'policy.source_version': '来源版本最多 256 个字符'
      }
    },
    attachTo: document.body
  })
  await flushPromises()
  const weekdays = wrapper.get('.el-checkbox-group')
  await vi.waitFor(() => expect(wrapper!.text()).toContain('请修正常规峰日'))
  expect(weekdays.attributes('aria-invalid')).toBe('true')
  const weekdaysErrorId = weekdays
    .attributes('aria-describedby')!
    .split(' ')
    .find((id) => document.getElementById(id)?.textContent?.includes('请修正常规峰日'))!
  expect(weekdaysErrorId).toBeTruthy()
  const dateInputs = wrapper
    .get('[data-time-pricing-field="policy.peak_date_ranges.0"]')
    .findAll('input')
  expect(dateInputs).toHaveLength(2)
  expect(dateInputs[0]!.element.closest('.time-pricing-date-row')?.textContent).toContain('至')
  for (const input of dateInputs) expect(input.attributes('aria-invalid')).toBe('true')
  const scopeInput = field('applicable_models.0.provider_id')
  expect(scopeInput.attributes('aria-invalid')).toBe('true')
  expect(
    scopeInput
      .attributes('aria-describedby')!
      .split(' ')
      .some((id) =>
        document.getElementById(id)?.textContent?.includes('当前模型不在规则适用范围内')
      )
  ).toBe(true)
  expect(field('source_version').attributes('aria-invalid')).toBe('true')
  await wrapper.setProps({ errors: {} })
  await flushPromises()
  expect(weekdays.attributes('aria-invalid')).toBe('false')
  expect(weekdays.attributes('aria-describedby')!.split(' ')).not.toContain(weekdaysErrorId)
  for (const input of dateInputs) expect(input.attributes('aria-invalid')).toBe('false')
  expect(scopeInput.attributes('aria-invalid')).toBe('false')
  expect(scopeInput.attributes('aria-describedby')).not.toContain('-error-')
  expect(field('source_version').attributes('aria-invalid')).toBe('false')
})
it('日期例外与适用范围新增后聚焦各自首字段，空值说明可见并关联真实输入', async () => {
  await editor()
  await click('添加额外峰日')
  expect(document.activeElement).toBe(field('peak_date_ranges.0').element)
  const scope = wrapper!
    .findAllComponents(ElCollapseItem)
    .find((item) => item.props('name') === 'scope')!
  await scope.get('.el-collapse-item__header').trigger('click')
  await flushPromises()
  await click('添加适用范围')
  expect(document.activeElement).toBe(field('applicable_models.0.provider_id').element)
  const multiplier = field('off_peak_multiplier')
  const ids = multiplier.attributes('aria-describedby')!.split(' ')
  expect(
    ids.some((id) => document.getElementById(id)?.textContent?.includes('0 表示明确零费用'))
  ).toBe(true)
  expect(wrapper!.text()).toContain('留空表示未知')
})
it('外部更换规则清除上一个对象的撤销内容', async () => {
  wrapper = mount(ModelTimePricingEditor, {
    props: { modelValue: policy() },
    attachTo: document.body
  })
  await wrapper.get('button[aria-label="删除峰时段 1"]').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('撤销删除')
  await wrapper.setProps({ modelValue: { ...policy(), time_zone: 'Asia/Tokyo' } })
  expect(wrapper.text()).not.toContain('撤销删除')
})
it('长表单保存与发布操作留在抽屉footer，历史规则使用可复制正文', async () => {
  await presets()
  await click('页面规则验收')
  expect(document.querySelector('.el-drawer__footer')?.textContent).toContain('保存预设草稿')
  expect(document.querySelector('.el-drawer__footer')?.textContent).toContain('发布预设')
  const history = wrapper!
    .findAllComponents(ElCollapseItem)
    .find((item) => item.props('title') === '查看修订 1 规则')!
  expect(history.findAll('input')).toHaveLength(0)
  expect(history.text()).toContain('09:00:00.000000001')
  expect(history.text()).toContain('14:00:00.987654321')
  expect(wrapper!.getComponent(ElDrawer).props('size')).toBe('min(960px, 100vw)')
})
it('预设提交错误可跳转首错，修改后清除已修正错误且不发送写请求', async () => {
  await presets()
  await click('新建预设')
  const name = wrapper!
    .findAllComponents(ElFormItem)
    .find((item) => item.props('label') === '预设名称')!
  await name.getComponent(ElInput).get('input').setValue('错误定位验收')
  await click('保存预设草稿')
  expect(document.activeElement).toBe(field('time_zone').element)
  expect(document.querySelector('[aria-label="预设配置错误摘要"]')).not.toBeNull()
  await field('time_zone').setValue('Asia/Shanghai')
  const zone = wrapper!
    .findAllComponents(ElFormItem)
    .find((item) => item.props('label') === '计价时区')!
  expect(zone.props('error')).toBeUndefined()
  expect(writes).toHaveLength(0)
})
it('预设名称校验关联真实错误正文，修正名称后清除invalid和错误引用', async () => {
  await presets()
  await click('新建预设')
  await click('保存预设草稿')
  const name = new DOMWrapper(
    document.querySelector<HTMLInputElement>('[data-time-pricing-field="name"] input')!
  )
  await vi.waitFor(() =>
    expect(
      document.querySelector('[data-time-pricing-field="name"] .el-form-item__error')?.textContent
    ).toContain('请填写预设名称')
  )
  expect(name.attributes('aria-invalid')).toBe('true')
  const errorId = name
    .attributes('aria-describedby')!
    .split(' ')
    .find((id) => document.getElementById(id)?.textContent?.includes('请填写预设名称'))!
  expect(errorId).toBeTruthy()
  await name.setValue('无效状态修正验收')
  await flushPromises()
  expect(name.attributes('aria-invalid')).toBe('false')
  expect((name.attributes('aria-describedby') ?? '').split(' ')).not.toContain(errorId)
  await vi.waitFor(() => expect(document.getElementById(errorId)).toBeNull())
  expect(writes).toHaveLength(0)
})
it('无编辑权限时草稿和历史正文均可读取，不展示编辑输入与保存入口', async () => {
  state.denied.push(
    'foundation.models.create',
    'foundation.models.update',
    'foundation.models.publish'
  )
  await presets()
  await click('页面规则验收')
  expect(wrapper!.findAllComponents(ModelTimePricingEditor)).toHaveLength(0)
  expect(document.body.textContent).toContain('09:00:00.000000001')
  expect(wrapper!.findAllComponents(ElButton).some((item) => item.text() === '保存预设草稿')).toBe(
    false
  )
})
it('目录刷新失败保留当前预设输入与历史，不将失败伪装成空目录', async () => {
  await presets()
  await click('页面规则验收')
  const name = wrapper!
    .findAllComponents(ElFormItem)
    .find((item) => item.props('label') === '预设名称')!
  await name.getComponent(ElInput).get('input').setValue('未保存输入')
  failRefresh = true
  await click('刷新预设')
  expect(name.getComponent(ElInput).props('modelValue')).toBe('未保存输入')
  expect(document.body.textContent).not.toContain('暂无峰谷预设')
  expect(document.body.textContent).toContain('09:00:00.000000001')
  expect(writes).toHaveLength(0)
})
it('普通文本字段支持Enter提交，输入法确认不触发提交', async () => {
  await presets()
  await click('新建预设')
  const name = wrapper!
    .findAllComponents(ElFormItem)
    .find((item) => item.props('label') === '预设名称')!
    .getComponent(ElInput)
    .get('input')
  await name.setValue('Enter提交验收')
  wrapper!.getComponent(ModelTimePricingEditor).vm.$emit('update:modelValue', policy())
  await flushPromises()
  await name.trigger('keydown', { key: 'Enter', isComposing: true })
  await flushPromises()
  expect(writes).toHaveLength(0)
  await name.trigger('keydown', { key: 'Enter' })
  await flushPromises()
  expect(writes).toHaveLength(1)
  expect(writes[0]!.path).toBe(root)
})

it('低频字段出错时自动展开，字符上限按真实码点校验且不截断输入', async () => {
  const initial = policy()
  initial.source_version = '😀'.repeat(257)
  initial.applicable_models = [
    { provider_id: '😀'.repeat(129), service_id: null, profile_version: null, model_id: null }
  ]
  await editor(initial, true)
  for (const name of ['scope', 'sources']) {
    const section = wrapper!
      .findAllComponents(ElCollapseItem)
      .find((item) => item.props('name') === name)!
    expect(section.get('.el-collapse-item__header').attributes('aria-expanded')).toBe('true')
  }
  expect(field('source_version').element.value).toBe(initial.source_version)
  expect(timePricingErrors(initial)).toHaveProperty('policy.source_version')
  expect(timePricingErrors(initial)).toHaveProperty('policy.applicable_models.0.provider_id')
  initial.source_version = '😀'.repeat(256)
  initial.applicable_models[0]!.provider_id = '😀'.repeat(128)
  expect(timePricingErrors(initial)).toEqual({})
})

it('预设首错只在提交禁用解除后聚焦，不尝试聚焦disabled输入', async () => {
  await presets()
  await click('新建预设')
  const name = wrapper!
    .findAllComponents(ElFormItem)
    .find((item) => item.props('label') === '预设名称')!
    .getComponent(ElInput)
    .get('input')
  await name.setValue('提交后焦点验收')
  const disabledAtFocus: boolean[] = []
  const originalFocus = HTMLInputElement.prototype.focus
  const focus = vi.spyOn(HTMLInputElement.prototype, 'focus').mockImplementation(function (
    this: HTMLInputElement,
    options?: FocusOptions
  ) {
    if (this.closest('[data-time-pricing-field="policy.time_zone"]'))
      disabledAtFocus.push(this.disabled)
    originalFocus.call(this, options)
  })
  try {
    const save = wrapper!
      .findAllComponents(ElButton)
      .find((item) => item.text() === '保存预设草稿')!
    ;(save.element as HTMLButtonElement).focus()
    await save.trigger('click')
    await flushPromises()
    expect(disabledAtFocus).toEqual([false])
    expect(document.activeElement).toBe(field('time_zone').element)
    expect(field('time_zone').element.disabled).toBe(false)
    expect(writes).toHaveLength(0)
  } finally {
    focus.mockRestore()
  }
})
