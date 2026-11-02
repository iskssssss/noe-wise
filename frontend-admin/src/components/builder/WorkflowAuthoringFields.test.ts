import { afterEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { ElButton, ElInput, ElSelect } from 'element-plus'
import WorkflowGovernanceFields from './WorkflowGovernanceFields.vue'
import WorkflowConfigFields from './WorkflowConfigFields.vue'
import WorkflowScriptResources from './WorkflowScriptResources.vue'
import { recommendedWorkflowCapability } from '../../utils/workflow'
import { clearFeedback } from '@skillforge/shared/feedback'

afterEach(() => {
  clearFeedback()
  vi.unstubAllGlobals()
})
describe('工作流治理和资源编辑', () => {
  it.each(['switch', 'upload'] as const)(
    '脚本未保存时%s不能覆盖当前编辑，明确清空后可切换',
    async (action) => {
      const resources = [
        { path: 'scripts/a.js', content_base64: btoa('A'), sha256: 'a'.repeat(64) },
        { path: 'scripts/b.js', content_base64: btoa('B'), sha256: 'b'.repeat(64) }
      ]
      const wrapper = mount(WorkflowScriptResources, {
        props: { modelValue: resources },
        attachTo: document.body
      })
      try {
        await flushPromises()
        const buttons = () => wrapper.findAllComponents(ElButton)
        await buttons()
          .filter((button) => button.text() === '查看/编辑')[0]!
          .trigger('click')
        wrapper.findAllComponents(ElInput)[1]!.vm.$emit('update:modelValue', '未保存的A')
        await flushPromises()
        const file = {
          name: 'new.js',
          arrayBuffer: vi.fn(async () => new TextEncoder().encode('new').buffer)
        }
        const input = wrapper.get<HTMLInputElement>('input[type="file"]')
        Object.defineProperty(input.element, 'files', { value: [file] })
        if (action === 'switch')
          await buttons()
            .filter((button) => button.text() === '查看/编辑')[1]!
            .trigger('click')
        else await input.trigger('change')
        await flushPromises()
        expect(
          wrapper.findAllComponents(ElInput).map((field) => field.props('modelValue'))
        ).toEqual(['scripts/a.js', '未保存的A'])
        expect(wrapper.emitted('pending-change')?.at(-1)).toEqual([true])
        expect(wrapper.emitted('update:modelValue')).toBeUndefined()
        expect(file.arrayBuffer).not.toHaveBeenCalled()
        expect(document.querySelectorAll('.el-message')).toHaveLength(1)
        expect(document.querySelector('.el-message')?.textContent).toContain(
          '先保存资源或清空编辑器'
        )
        expect(input.element.value).toBe('')
        await buttons()
          .find((button) => button.text() === '清空编辑器')!
          .trigger('click')
        await flushPromises()
        expect(document.querySelectorAll('.el-message')).toHaveLength(0)
        await buttons()
          .filter((button) => button.text() === '查看/编辑')[1]!
          .trigger('click')
        await flushPromises()
        expect(
          wrapper.findAllComponents(ElInput).map((field) => field.props('modelValue'))
        ).toEqual(['scripts/b.js', 'B'])
        expect(document.querySelectorAll('.el-message')).toHaveLength(0)
        expect(wrapper.emitted('pending-change')?.at(-1)).toEqual([false])
      } finally {
        wrapper.unmount()
      }
    }
  )
  it('已有脚本编辑为全空仍是未保存修改，不被父页面当成可保存状态', async () => {
    const wrapper = mount(WorkflowScriptResources, {
      props: {
        modelValue: [{ path: 'scripts/a.js', content_base64: btoa('A'), sha256: 'a'.repeat(64) }]
      }
    })
    try {
      await flushPromises()
      await wrapper
        .findAllComponents(ElButton)
        .find((button) => button.text() === '查看/编辑')!
        .trigger('click')
      for (const input of wrapper.findAllComponents(ElInput))
        input.vm.$emit('update:modelValue', '')
      await flushPromises()
      expect(wrapper.emitted('pending-change')?.at(-1)).toEqual([true])
      expect(wrapper.emitted('update:modelValue')).toBeUndefined()
    } finally {
      wrapper.unmount()
    }
  })
  it('保存资源后可直接切换，新增内容和SHA仍绑定原脚本', async () => {
    vi.stubGlobal('crypto', {
      subtle: { digest: vi.fn(async () => new Uint8Array(32).fill(2).buffer) }
    })
    const resources = [
      { path: 'scripts/a.js', content_base64: btoa('A'), sha256: 'a'.repeat(64) },
      { path: 'scripts/b.js', content_base64: btoa('B'), sha256: 'b'.repeat(64) }
    ]
    const wrapper = mount(WorkflowScriptResources, { props: { modelValue: resources } })
    try {
      await flushPromises()
      const buttons = () => wrapper.findAllComponents(ElButton)
      await buttons()
        .filter((button) => button.text() === '查看/编辑')[0]!
        .trigger('click')
      wrapper.findAllComponents(ElInput)[1]!.vm.$emit('update:modelValue', 'updated A')
      await buttons()
        .find((button) => button.text() === '保存资源')!
        .trigger('click')
      await flushPromises()
      expect(wrapper.emitted('update:modelValue')?.at(-1)?.[0]).toEqual([
        resources[1],
        {
          path: 'scripts/a.js',
          content_base64: btoa('updated A'),
          sha256: '02'.repeat(32)
        }
      ])
      await buttons()
        .filter((button) => button.text() === '查看/编辑')[1]!
        .trigger('click')
      expect(wrapper.findAllComponents(ElInput).map((input) => input.props('modelValue'))).toEqual([
        'scripts/b.js',
        'B'
      ])
      expect(wrapper.emitted('pending-change')?.at(-1)).toEqual([false])
    } finally {
      wrapper.unmount()
    }
  })
  it('读取本地脚本期间锁住保存，成功后原子更新路径和内容并保持待保存状态', async () => {
    const wrapper = mount(WorkflowScriptResources, { props: { modelValue: [] } })
    let finish!: (value: ArrayBuffer) => void
    const file = {
      name: 'b.js',
      arrayBuffer: vi.fn(
        () =>
          new Promise<ArrayBuffer>((resolve) => {
            finish = resolve
          })
      )
    }
    const input = wrapper.get('input[type="file"]')
    Object.defineProperty(input.element, 'files', { value: [file] })
    await input.trigger('change')
    expect(
      wrapper
        .findAllComponents(ElButton)
        .find((button) => button.text() === '保存资源')!
        .props('disabled')
    ).toBe(true)
    expect(wrapper.emitted('pending-change')?.at(-1)).toEqual([true])
    expect(wrapper.findAllComponents(ElInput)[0]!.props('modelValue')).toBe('')
    finish(new TextEncoder().encode('new B').buffer)
    await flushPromises()
    expect(wrapper.findAllComponents(ElInput)[0]!.props('modelValue')).toBe('scripts/b.js')
    expect(wrapper.findAllComponents(ElInput)[1]!.props('modelValue')).toBe('new B')
    expect(wrapper.emitted('pending-change')?.at(-1)).toEqual([true])
    expect(
      wrapper
        .findAllComponents(ElButton)
        .find((button) => button.text() === '保存资源')!
        .props('disabled')
    ).toBe(false)
    wrapper.unmount()
  })
  it('缺失治理不会自动补值，显式采用后可编辑并阻止无效Schema进入定义', async () => {
    const wrapper = mount(WorkflowGovernanceFields)
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
    await wrapper.findComponent(ElButton).trigger('click')
    expect(wrapper.emitted('update:modelValue')?.at(-1)?.[0]).toEqual(
      recommendedWorkflowCapability()
    )
    await wrapper.setProps({ modelValue: recommendedWorkflowCapability() })
    const schema = wrapper.findAllComponents(ElInput).at(-2)!
    schema.vm.$emit('update:modelValue', '{')
    await flushPromises()
    expect(wrapper.emitted('validity-change')?.at(-1)).toEqual([false])
    expect(wrapper.text()).toContain('修正前仍使用上一次有效配置')
    schema.vm.$emit(
      'update:modelValue',
      '{"type":"object","properties":{"city":{"type":"string"}}}'
    )
    await flushPromises()
    expect(wrapper.emitted('validity-change')?.at(-1)).toEqual([true])
    expect(wrapper.emitted('update:modelValue')?.at(-1)?.[0]).toMatchObject({
      input_schema: { properties: { city: { type: 'string' } } }
    })
    wrapper.unmount()
  })
  it('参数改类型清理不匹配默认值，高级JSON错误保留原文本', async () => {
    const wrapper = mount(WorkflowConfigFields, {
      props: { modelValue: '[{"key":"enabled","type":"boolean","default":false}]' }
    })
    const type = wrapper.findAllComponents(ElSelect)[0]!
    type.vm.$emit('update:modelValue', 'integer')
    await flushPromises()
    const next = JSON.parse(String(wrapper.emitted('update:modelValue')?.at(-1)?.[0]))
    expect(next).toEqual([{ key: 'enabled', type: 'integer' }])
    await wrapper.setProps({ modelValue: '{bad' })
    expect(wrapper.text()).toContain('高级 JSON 编辑')
    expect(wrapper.findAllComponents(ElInput).at(-1)?.props('modelValue')).toBe('{bad')
    wrapper.unmount()
  })
  it('把UTF-8原始字节传给SHA-256并保存规范Base64及摘要', async () => {
    const digest = vi.fn(async () => new Uint8Array(32).fill(1).buffer)
    vi.stubGlobal('crypto', { subtle: { digest } })
    const wrapper = mount(WorkflowScriptResources, { props: { modelValue: [] } })
    const inputs = wrapper.findAllComponents(ElInput)
    inputs[0]!.vm.$emit('update:modelValue', 'scripts/check.js')
    inputs[1]!.vm.$emit('update:modelValue', 'return "中文";')
    await wrapper
      .findAllComponents(ElButton)
      .find((button) => button.text() === '保存资源')!
      .trigger('click')
    await flushPromises()
    const result = wrapper.emitted('update:modelValue')?.at(-1)?.[0] as Array<{
      path: string
      content_base64: string
      sha256: string
    }>
    expect(result[0]).toEqual({
      path: 'scripts/check.js',
      content_base64: btoa(String.fromCharCode(...new TextEncoder().encode('return "中文";'))),
      sha256: '01'.repeat(32)
    })
    expect(digest).toHaveBeenCalledWith('SHA-256', new TextEncoder().encode('return "中文";'))
    expect(wrapper.emitted('pending-change')?.at(-1)).toEqual([false])
    wrapper.unmount()
  })
  it('摘要等待中锁住资源身份，点击B不能把A内容写到B路径', async () => {
    let finish!: (value: ArrayBuffer) => void
    vi.stubGlobal('crypto', {
      subtle: {
        digest: vi.fn(
          () =>
            new Promise<ArrayBuffer>((resolve) => {
              finish = resolve
            })
        )
      }
    })
    const resources = [
      { path: 'scripts/a.js', content_base64: 'QQ==', sha256: 'a'.repeat(64) },
      { path: 'scripts/b.js', content_base64: 'Qg==', sha256: 'b'.repeat(64) }
    ]
    const wrapper = mount(WorkflowScriptResources, { props: { modelValue: resources } })
    await flushPromises()
    await wrapper
      .findAllComponents(ElButton)
      .find((button) => button.text() === '查看/编辑')!
      .trigger('click')
    wrapper.findAllComponents(ElInput)[1]!.vm.$emit('update:modelValue', 'updated A')
    await wrapper
      .findAllComponents(ElButton)
      .find((button) => button.text() === '保存资源')!
      .trigger('click')
    const editB = wrapper
      .findAllComponents(ElButton)
      .filter((button) => button.text() === '查看/编辑')[1]!
    expect(editB.props('disabled')).toBe(true)
    await editB.trigger('click')
    finish(new Uint8Array(32).buffer)
    await flushPromises()
    const result = wrapper.emitted('update:modelValue')?.at(-1)?.[0] as typeof resources
    expect(result.find((resource) => resource.path === 'scripts/b.js')).toEqual(resources[1])
    expect(result.find((resource) => resource.path === 'scripts/a.js')?.content_base64).toBe(
      btoa('updated A')
    )
    wrapper.unmount()
  })
})
