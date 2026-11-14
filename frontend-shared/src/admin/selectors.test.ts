import { mount, flushPromises } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import ElementPlus, { ElSelect } from 'element-plus'
import EntitySelect from './EntitySelect.vue'
import CapabilityRequirementFields from './CapabilityRequirementFields.vue'
import { statusLabel } from './presentation'
import { HttpClientError } from '../utils/http'
import { clearFeedback } from '../feedback'

afterEach(() => {
  clearFeedback()
  document.body.innerHTML = ''
})
function messages() {
  return [...document.querySelectorAll('.el-message')].map((item) => item.textContent).join(' ')
}

describe('关联选择表单', () => {
  it('重复依赖就近报错，删除前一行不复用另一行的输入实例', async () => {
    const wrapper = mount(CapabilityRequirementFields, {
      props: {
        modelValue: [
          { type: 'TOOL', capabilityId: 'same', versionId: '' },
          { type: 'TOOL', capabilityId: 'same', versionId: '' }
        ],
        loadOptions: async () => [{ id: 'same', name: '工具', versionId: 'v1', versionKey: '版本' }]
      },
      global: { plugins: [ElementPlus] }
    })
    await flushPromises()
    await expect(
      (wrapper.vm as unknown as { validate(): Promise<void> }).validate()
    ).rejects.toThrow('重复依赖')
    await vi.waitFor(() =>
      expect(wrapper.findAll('.requirement-row')[1]!.text()).toContain('请移除重复依赖')
    )
    const retained = wrapper.findAll('.requirement-row')[1]!.element
    await wrapper.setProps({ modelValue: [wrapper.props('modelValue')[1]!] })
    expect(wrapper.get('.requirement-row').element).toBe(retained)
    await expect(
      (wrapper.vm as unknown as { validate(): Promise<void> }).validate()
    ).resolves.toBeUndefined()
    wrapper.unmount()
  })
  it('远程搜索仅最新结果生效，回显独立加载已有名称', async () => {
    const finish: ((v: { id: string; label: string }[]) => void)[] = []
    const wrapper = mount(EntitySelect, {
      props: {
        modelValue: 'selected',
        resolve: async () => ({ id: 'selected', label: '已选机构' }),
        load: () => new Promise((resolve) => finish.push(resolve))
      },
      global: { plugins: [ElementPlus] }
    })
    await flushPromises()
    const search = wrapper.findComponent(ElSelect).props('remoteMethod')!
    const latest = search('new')
    finish[1]!([{ id: 'new', label: '新结果' }])
    await latest
    finish[0]!([{ id: 'old', label: '过期结果' }])
    await flushPromises()
    expect(wrapper.text()).toContain('已选机构')
    const options = wrapper
      .findAllComponents({ name: 'ElOption' })
      .map((option) => option.props('label'))
    expect(options).toContain('新结果')
    expect(options).not.toContain('过期结果')
    wrapper.unmount()
  })
  it('搜索失败后清除旧候选，重试原关键词并保留已选值', async () => {
    let rejectSearch!: (reason: Error) => void
    let finishRetry!: (value: { id: string; label: string }[]) => void
    let attempts = 0
    const load = vi.fn((query: string) => {
      if (!query)
        return Promise.resolve([
          { id: 'selected', label: '已选机构' },
          { id: 'old', label: '旧候选' }
        ])
      if (++attempts === 1) return new Promise((_resolve, reject) => (rejectSearch = reject))
      return new Promise((resolve) => (finishRetry = resolve))
    })
    const wrapper = mount(EntitySelect, {
      props: { modelValue: 'selected', load },
      global: { plugins: [ElementPlus] },
      attachTo: document.body
    })
    await flushPromises()
    const options = () =>
      wrapper.findAllComponents({ name: 'ElOption' }).map((option) => option.props('label'))
    expect(options()).toEqual(['已选机构', '旧候选'])
    await wrapper.find('input').trigger('click')
    await flushPromises()
    const search = wrapper.findComponent(ElSelect).props('remoteMethod')!
    const pending = search('新机构')
    await wrapper.vm.$nextTick()
    expect(options()).toEqual(['已选机构'])
    rejectSearch(new Error('搜索失败'))
    await pending
    await flushPromises()
    expect(options()).toEqual(['已选机构'])
    expect(messages()).toContain('搜索失败')
    expect(wrapper.findComponent(ElSelect).props('automaticDropdown')).toBe(true)
    const retry = [...document.querySelectorAll('button')].find((button) =>
      button.textContent?.includes('重新加载选项')
    )!
    retry.click()
    await wrapper.vm.$nextTick()
    expect(load.mock.calls.at(-1)?.[0]).toBe('新机构')
    expect(options()).toEqual(['已选机构'])
    finishRetry([{ id: 'new', label: '新机构' }])
    await flushPromises()
    expect(options()).toEqual(['已选机构', '新机构'])
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
    expect(messages()).not.toContain('搜索失败')
    expect(wrapper.findComponent(ElSelect).props('automaticDropdown')).toBe(false)
    wrapper.unmount()
  })
  it('候选加载器更换时用当前关键词查询新目录', async () => {
    const load = vi.fn(async (_query: string) => [{ id: 'old', label: '旧目录' }])
    const replacement = vi.fn(async (_query: string) => [{ id: 'new', label: '新目录' }])
    const wrapper = mount(EntitySelect, { props: { load }, global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.findComponent(ElSelect).props('remoteMethod')!('机构')
    await wrapper.setProps({ load: replacement })
    await flushPromises()
    expect(replacement.mock.calls.at(-1)?.[0]).toBe('机构')
    expect(
      wrapper.findAllComponents({ name: 'ElOption' }).map((item) => item.props('label'))
    ).toEqual(['新目录'])
    wrapper.unmount()
  })
  it('切换能力类型清除定义与版本，缺少依赖阻止保存', async () => {
    const wrapper = mount(CapabilityRequirementFields, {
      props: {
        modelValue: [{ type: 'TOOL', capabilityId: 'definition', versionId: 'version' }],
        loadOptions: async () => [
          { id: 'definition', name: '工具名称', versionId: 'version', versionKey: 'v1' }
        ]
      },
      global: { plugins: [ElementPlus] }
    })
    await flushPromises()
    await (wrapper.vm as unknown as { validate: () => Promise<void> }).validate()
    wrapper.findComponent(ElSelect).vm.$emit('update:modelValue', 'WORKFLOW')
    const emitted = wrapper.emitted('update:modelValue')!.at(-1)![0]
    expect(emitted).toEqual([{ type: 'WORKFLOW', capabilityId: '', versionId: '' }])
    await wrapper.setProps({
      modelValue: emitted as [{ type: 'WORKFLOW'; capabilityId: string; versionId: string }]
    })
    await expect(
      (wrapper.vm as unknown as { validate: () => Promise<void> }).validate()
    ).rejects.toThrow('未选择的能力')
    wrapper.unmount()
  })
  it('同一选项回显请求更换后，旧错误不能覆盖新名称', async () => {
    let rejectOld!: (reason: Error) => void
    const oldResolve = () =>
      new Promise<undefined>((_resolve, reject) => {
        rejectOld = reject
      })
    const wrapper = mount(EntitySelect, {
      props: { modelValue: 'same', resolve: oldResolve },
      global: { plugins: [ElementPlus] }
    })
    await wrapper.setProps({ resolve: async () => ({ id: 'same', label: '最新名称' }) })
    await flushPromises()
    rejectOld(new Error('旧请求失败'))
    await flushPromises()
    expect(wrapper.text()).toContain('最新名称')
    expect(wrapper.text()).not.toContain('旧请求失败')
    wrapper.unmount()
  })
  it('已选项回显失败可独立重试，恢复后清除错误', async () => {
    let calls = 0
    const wrapper = mount(EntitySelect, {
      props: {
        modelValue: 'id',
        resolve: async () => {
          if (++calls === 1) throw new Error('回显失败')
          return { id: 'id', label: '恢复名称' }
        }
      },
      global: { plugins: [ElementPlus] }
    })
    await flushPromises()
    expect(messages()).toContain('回显失败')
    await wrapper.find('button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('恢复名称')
    expect(messages()).not.toContain('回显失败')
    wrapper.unmount()
  })
  it('未知状态不伪装成正常状态', () => {
    expect(statusLabel('PUBLISHED')).toBe('已发布')
    expect(statusLabel('file')).toBe('文件')
    expect(statusLabel('projection')).toBe('平台投影')
    expect(statusLabel('tenant_admin')).toBe('机构管理员')
    expect(statusLabel('auditor')).toBe('审计成员')
    expect(statusLabel('approved')).toBe('已批准')
    expect(statusLabel('rejected')).toBe('已拒绝')
    expect(statusLabel('request')).toBe('请求')
    expect(statusLabel('future_state')).toBe('future_state')
  })
  it('失效引用保留原始标识并提示重新选择，不自动改绑第一个候选', async () => {
    const wrapper = mount(EntitySelect, {
      props: {
        modelValue: '9007199254740993',
        resolve: async () => undefined,
        options: [{ id: 'other', label: '其他机构' }]
      },
      global: { plugins: [ElementPlus] }
    })
    await flushPromises()
    expect(messages()).toContain('无法找到已选项')
    expect(wrapper.findComponent(ElSelect).props('modelValue')).toBe('9007199254740993')
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
    wrapper.unmount()
  })
  it('关联选择在字段内承接安全HTTP错误，底层异常不代替用户提示，原选择不变', async () => {
    const failure = new HttpClientError({
      status: 403,
      userMessage: '没有读取选项的权限，请联系管理员',
      technicalDetails: 'internal.repository.findById'
    })
    const wrapper = mount(EntitySelect, {
      props: {
        modelValue: 'selected',
        resolve: async () => {
          throw failure
        }
      },
      global: { plugins: [ElementPlus] }
    })
    await flushPromises()
    expect(failure.hasFeedbackOwner).toBe(true)
    expect(messages()).toContain('没有读取选项的权限，请联系管理员')
    expect(messages()).not.toContain('internal.repository')
    await wrapper.setProps({
      resolve: async () => {
        throw new TypeError('Failed to fetch /internal/options')
      }
    })
    await flushPromises()
    expect(messages()).toContain('未能读取已选项名称')
    expect(messages()).not.toContain('/internal/options')
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
    expect(wrapper.findComponent(ElSelect).props('modelValue')).toBe('selected')
    wrapper.unmount()
  })
  it('依赖选项读取异常保留表单并提供重试，避免显示原始解析信息', async () => {
    const wrapper = mount(CapabilityRequirementFields, {
      props: {
        modelValue: [{ type: 'TOOL', capabilityId: 'definition', versionId: 'version' }],
        loadOptions: async () => {
          throw new SyntaxError('Unexpected token < in JSON at position 0')
        }
      },
      global: { plugins: [ElementPlus] }
    })
    await flushPromises()
    expect(messages()).toContain('未能加载依赖能力')
    expect(wrapper.text()).toContain('重新加载选项')
    expect(wrapper.text()).not.toContain('Unexpected token')
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
    wrapper.unmount()
  })
})

it('目录只用于选择，已有身份由服务端判断可用性', async () => {
  const loadOptions = vi.fn(async (type: string) =>
    type === 'TOOL'
      ? [
          { id: '21', name: '查询工具', versionId: '9223372036854775807', versionKey: 'current' },
          { id: '22', name: '另一工具', versionId: '32', versionKey: 'current2' }
        ]
      : []
  )
  const rows = [
    { type: 'TOOL' as const, capabilityId: '21', versionId: 'old' },
    { type: 'TOOL' as const, capabilityId: '22', versionId: 'old2' }
  ]
  const wrapper = mount(CapabilityRequirementFields, {
    props: { modelValue: rows, loadOptions },
    global: { plugins: [ElementPlus] }
  })
  await flushPromises()
  expect(loadOptions).toHaveBeenCalledExactlyOnceWith('TOOL')
  await (wrapper.vm as unknown as { validate(): Promise<void> }).validate()
  expect(wrapper.emitted('update:modelValue')).toBeUndefined()
  expect(loadOptions).toHaveBeenCalledTimes(1)
  await wrapper.setProps({
    modelValue: [{ type: 'TOOL', capabilityId: 'missing', versionId: 'original' }]
  })
  await expect(
    (wrapper.vm as unknown as { validate(): Promise<void> }).validate()
  ).resolves.toBeUndefined()
  expect(wrapper.props('modelValue')).toEqual([
    { type: 'TOOL', capabilityId: 'missing', versionId: 'original' }
  ])
  expect(wrapper.emitted('update:modelValue')).toBeUndefined()
  wrapper.unmount()
})
it('a failed type directory can be retried without changing the existing chosen identity', async () => {
  const loadOptions = vi
    .fn()
    .mockRejectedValueOnce(new Error('目录暂不可用'))
    .mockResolvedValue([{ id: '21', name: '查询工具', versionId: '31', versionKey: 'current' }])
  const row = { type: 'TOOL' as const, capabilityId: '21', versionId: 'original' }
  const wrapper = mount(CapabilityRequirementFields, {
    props: { modelValue: [row], loadOptions },
    global: { plugins: [ElementPlus] }
  })
  await flushPromises()
  expect(wrapper.props('modelValue')).toEqual([row])
  expect(wrapper.emitted('update:modelValue')).toBeUndefined()
  await wrapper
    .findAll('button')
    .find((item) => item.text() === '重新加载选项')!
    .trigger('click')
  await flushPromises()
  expect(loadOptions).toHaveBeenCalledTimes(2)
  expect(wrapper.props('modelValue')).toEqual([row])
  await (wrapper.vm as unknown as { validate(): Promise<void> }).validate()
  expect(wrapper.emitted('update:modelValue')).toBeUndefined()
  wrapper.unmount()
})
