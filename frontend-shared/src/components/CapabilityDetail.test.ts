import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import ElementPlus, { ElButton, ElFormItem } from 'element-plus'
import CapabilityDetail from './CapabilityDetail.vue'
import JsonSchemaFields from './JsonSchemaFields.vue'
import { confirm } from '../feedback'
import type { CapabilityVersion, CapabilityVersionInput } from '../capabilities/catalog'

vi.mock('../feedback', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../feedback')>()),
  confirm: vi.fn(),
  message: { success: vi.fn() }
}))

vi.mock('@skillforge/shared/components/CodeEditor.vue', () => ({
  default: {
    props: ['modelValue', 'label'],
    emits: ['update:modelValue'],
    template:
      '<textarea :aria-label="label" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />'
  }
}))

const toolWrappers: VueWrapper[] = []
afterEach(() => {
  toolWrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  vi.unstubAllGlobals()
})

/** 真实 Element Plus 表单消费已保存草稿的回读值，API 替身只隔离传输边界。 */
async function toolForm() {
  // 隔离浏览器摘要端口，断言其输入仍是实际 descriptor wire，不把 Node 类型带入宿主编译。
  const digest = vi.fn(
    async (_algorithm: string, _bytes: Uint8Array) => new Uint8Array(32).fill(1).buffer
  )
  vi.stubGlobal('crypto', { subtle: { digest } })
  let saved: CapabilityVersion | undefined
  const api = {
    getDefinition: vi.fn().mockResolvedValue({
      id: '1',
      tenantId: null,
      capabilityType: 'TOOL',
      capabilityKey: 'owned-tool',
      name: '资料检索',
      description: '检索提供的资料',
      revision: 1,
      status: 'DRAFT'
    }),
    listVersionSummaries: vi.fn(async () => (saved ? [structuredClone(saved)] : [])),
    getVersion: vi.fn(async () => structuredClone(saved!)),
    createDraftVersion: vi.fn(async (_id: string, input: CapabilityVersionInput) => {
      saved = {
        id: '2',
        definitionId: '1',
        capabilityType: 'TOOL',
        versionKey: input.versionKey,
        name: input.name,
        description: input.description ?? '',
        descriptor: JSON.parse(JSON.stringify(input.descriptor)),
        status: 'DRAFT'
      }
      return saved
    }),
    validateVersion: vi.fn().mockResolvedValue({ valid: true, errors: [], definitionRevision: 1 }),
    publishVersion: vi.fn(),
    updateDraftVersion: vi.fn(
      async (
        _id: string,
        _version: string,
        _revision: number,
        input: Omit<CapabilityVersionInput, 'versionKey'>
      ) => {
        saved = {
          ...saved!,
          name: input.name,
          description: input.description ?? '',
          descriptor: JSON.parse(JSON.stringify(input.descriptor))
        }
        return saved
      }
    )
  }
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/capabilities/:id', component: CapabilityDetail }]
  })
  await router.push('/capabilities/1')
  await router.isReady()
  const wrapper = mount(CapabilityDetail, {
    props: {
      api: api as never,
      basePath: '/capabilities',
      skillBasePath: '/skills',
      permissions: ['foundation.capabilities.create', 'foundation.capabilities.update']
    },
    global: { plugins: [router, ElementPlus] }
  })
  toolWrappers.push(wrapper)
  await flushPromises()
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '编辑工作草稿')!
    .trigger('click')
  await flushPromises()
  await field(wrapper, '版本标识').get('input').setValue('1.0.0')
  await field(wrapper, '工具名称').get('input').setValue('ui_owned_tool')
  return { wrapper, api, digest }
}

/** 按可见字段标签选择原生控件，保持现有 ID 与表单布局。 */
function field(wrapper: VueWrapper, label: string) {
  const item = wrapper.findAllComponents(ElFormItem).find((item) => item.props('label') === label)
  if (!item) throw Error('未找到字段 ' + label)
  return item
}
async function rawSchema(wrapper: VueWrapper, label: string) {
  const editor = wrapper
    .findAllComponents(JsonSchemaFields)
    .find((item) => item.props('label') === label)!
  await editor
    .findAll('button')
    .find((button) => button.text() === '原始 JSON（专业）')!
    .trigger('click')
  await flushPromises()
  return wrapper.get(`textarea[aria-label="${label}"]`)
}

describe('能力版本表单', () => {
  it('结构字段改动进入真实descriptor与摘要，未保存时验证和发布入口及处理器均禁用', async () => {
    const { wrapper, api, digest } = await toolForm()
    const schema = {
      type: 'object',
      additionalProperties: false,
      'x-contract': { keep: true },
      properties: {
        name: { type: 'string', minLength: 1 },
        records: { type: 'array', items: { anyOf: [{ type: 'integer' }, { type: 'null' }] } }
      },
      required: ['name']
    }
    await (await rawSchema(wrapper, '输入 JSON Schema')).setValue(JSON.stringify(schema))
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    await wrapper.setProps({
      permissions: [
        'foundation.capabilities.create',
        'foundation.capabilities.update',
        'foundation.capabilities.publish'
      ]
    })
    const input = wrapper
      .findAllComponents(JsonSchemaFields)
      .find((item) => item.props('label') === '输入 JSON Schema')!
    const name = input.findAll('.schema-field').find((row) => row.get('strong').text() === 'name')!
    await name.get('textarea').setValue('向模型说明检索的名称')
    expect(wrapper.text()).toContain('请先保存版本草稿，再验证或发布')
    for (const label of ['验证', '发布']) {
      const button = wrapper.findAllComponents(ElButton).find((item) => item.text() === label)!
      expect(button.props('disabled')).toBe(true)
      button.vm.$emit('click')
    }
    await flushPromises()
    expect(api.validateVersion).not.toHaveBeenCalled()
    expect(api.publishVersion).not.toHaveBeenCalled()
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    const updated = api.updateDraftVersion.mock.lastCall![3]
    expect(updated.descriptor.input_schema).toEqual({
      ...schema,
      properties: {
        ...schema.properties,
        name: { ...schema.properties.name, description: '向模型说明检索的名称' }
      }
    })
    expect(digest.mock.lastCall?.[1]).toEqual(
      new TextEncoder().encode(JSON.stringify(updated.descriptor))
    )
    expect(
      wrapper
        .findAllComponents(ElButton)
        .find((item) => item.text() === '验证')!
        .props('disabled')
    ).toBe(false)
  })
  it.each(['', '可读工具标题'])(
    '工具标题为“%s”时创建、回读与编辑保持可选 wire 契约',
    async (title) => {
      const { wrapper, api, digest } = await toolForm()
      const schema = { type: 'object', properties: { label: { type: 'string' } } }
      await (await rawSchema(wrapper, '输入 JSON Schema')).setValue(JSON.stringify(schema))
      if (title) await field(wrapper, '工具标题').get('input').setValue(title)
      await wrapper.get('form').trigger('submit')
      await flushPromises()
      expect(api.createDraftVersion).toHaveBeenCalledTimes(1)
      const created = api.createDraftVersion.mock.calls[0]![1]
      expect(created.versionKey).toBe('1.0.0')
      expect(created.descriptor.input_schema).toEqual(schema)
      expect(created.descriptor.tool_name).toBe('ui_owned_tool')
      if (title) expect(created.descriptor.title).toBe(title)
      else expect(created.descriptor).not.toHaveProperty('title')
      expect(digest).toHaveBeenCalledWith(
        'SHA-256',
        new TextEncoder().encode(JSON.stringify(created.descriptor))
      )
      expect(created.contentHash).toBe('01'.repeat(32))

      await wrapper
        .findAll('button')
        .find((button) => button.text() === '编辑草稿')!
        .trigger('click')
      await flushPromises()
      expect(field(wrapper, '工具标题').get<HTMLInputElement>('input').element.value).toBe(title)
      await wrapper.get('form').trigger('submit')
      await flushPromises()
      expect(api.updateDraftVersion).toHaveBeenCalledTimes(1)
      const updated = api.updateDraftVersion.mock.calls[0]![3]
      expect(updated.descriptor).toEqual(created.descriptor)
      expect(updated.contentHash).toBe(created.contentHash)
      expect(field(wrapper, '版本标识').get<HTMLInputElement>('input').element.value).toBe('1.0.0')
    }
  )

  it('可选标题省略不放宽名称和 JSON 的客户端负控', async () => {
    const { wrapper, api } = await toolForm()
    await field(wrapper, '名称').get('input').setValue('')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    // FormItem 的可见错误经过 debounce，等待真实字段错误完成，不把 Promise 清空当作渲染完成。
    await vi.waitFor(() => expect(field(wrapper, '名称').text()).toContain('请填写名称'))
    expect(api.createDraftVersion).not.toHaveBeenCalled()
    expect(api.updateDraftVersion).not.toHaveBeenCalled()
    await field(wrapper, '名称').get('input').setValue('资料检索')
    await (await rawSchema(wrapper, '输入 JSON Schema')).setValue('{')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    await vi.waitFor(() =>
      expect(field(wrapper, '输入 JSON Schema').text()).toContain('JSON 格式不正确')
    )
    expect(api.createDraftVersion).not.toHaveBeenCalled()
    expect(api.updateDraftVersion).not.toHaveBeenCalled()
  })

  it('打开带数组描述字段的已有版本并编辑，不改变服务端版本快照', async () => {
    const descriptor = { sensitive_fields: ['password'], provider_requirements: ['provider-a'] }
    const api = {
      getDefinition: vi
        .fn()
        .mockResolvedValue({ id: '1', name: '工具', capabilityType: 'TOOL', revision: 1 }),
      listVersionSummaries: vi
        .fn()
        .mockResolvedValue([
          { id: '2', versionKey: 'v1', name: '工具版本', description: '', status: 'DRAFT' }
        ]),
      getVersion: vi.fn().mockResolvedValue({
        id: '2',
        definitionId: '1',
        capabilityType: 'TOOL',
        name: '工具版本',
        description: '',
        versionKey: 'v1',
        status: 'DRAFT',
        descriptor
      })
    }
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/capabilities/:id', component: CapabilityDetail }]
    })
    await router.push('/capabilities/1')
    await router.isReady()
    const wrapper = mount(CapabilityDetail, {
      props: {
        api: api as never,
        basePath: '/capabilities',
        skillBasePath: '/skills',
        permissions: ['foundation.capabilities.create', 'foundation.capabilities.update']
      },
      global: { plugins: [router, ElementPlus] }
    })
    await flushPromises()
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '编辑草稿')!
      .trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('保存版本草稿')
    expect(wrapper.text()).toContain('password')
    expect(wrapper.text()).toContain('provider-a')
    expect(descriptor.sensitive_fields).toEqual(['password'])
    wrapper.unmount()
  })
  it('发布动作缺少验证所需的修改权限时不提供入口，撤权后表单立即只读', async () => {
    const api = {
      getDefinition: vi
        .fn()
        .mockResolvedValue({ id: '1', name: '工具', capabilityType: 'TOOL', revision: 1 }),
      listVersionSummaries: vi
        .fn()
        .mockResolvedValue([
          { id: '2', versionKey: 'v1', name: '工具版本', description: '', status: 'DRAFT' }
        ]),
      getVersion: vi.fn().mockResolvedValue({
        id: '2',
        definitionId: '1',
        capabilityType: 'TOOL',
        name: '工具版本',
        description: '',
        versionKey: 'v1',
        status: 'DRAFT',
        descriptor: {}
      }),
      validateVersion: vi.fn(),
      publishVersion: vi.fn()
    }
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/capabilities/:id', component: CapabilityDetail }]
    })
    await router.push('/capabilities/1')
    await router.isReady()
    const wrapper = mount(CapabilityDetail, {
      props: {
        api: api as never,
        basePath: '/capabilities',
        skillBasePath: '/skills',
        permissions: ['foundation.capabilities.publish']
      },
      global: { plugins: [router, ElementPlus] }
    })
    await flushPromises()
    expect(wrapper.findAll('button').some((button) => button.text() === '发布')).toBe(false)
    expect(wrapper.findAll('button').some((button) => button.text() === '验证')).toBe(false)
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '查看草稿')!
      .trigger('click')
    await flushPromises()
    expect(wrapper.findAll('button').some((button) => button.text() === '保存版本草稿')).toBe(false)
    await wrapper.setProps({
      permissions: ['foundation.capabilities.update', 'foundation.capabilities.publish']
    })
    expect(wrapper.findAll('button').some((button) => button.text() === '保存版本草稿')).toBe(true)
    await wrapper.setProps({ permissions: [] })
    expect(wrapper.findAll('button').some((button) => button.text() === '保存版本草稿')).toBe(false)
    expect(wrapper.get('input').attributes('disabled')).toBeDefined()
    expect(api.validateVersion).not.toHaveBeenCalled()
    expect(api.publishVersion).not.toHaveBeenCalled()
    wrapper.unmount()
  })
  it('发布确认期间撤权会中止验证和发布请求', async () => {
    let resolveConfirmation!: (confirmed: boolean) => void
    vi.mocked(confirm).mockImplementationOnce(
      () =>
        new Promise<boolean>((resolve) => {
          resolveConfirmation = resolve
        })
    )
    const api = {
      getDefinition: vi
        .fn()
        .mockResolvedValue({ id: '1', name: '工具', capabilityType: 'TOOL', revision: 1 }),
      listVersionSummaries: vi
        .fn()
        .mockResolvedValue([
          { id: '2', versionKey: 'v1', name: '工具版本', description: '', status: 'DRAFT' }
        ]),
      getVersion: vi.fn().mockResolvedValue({
        id: '2',
        definitionId: '1',
        capabilityType: 'TOOL',
        name: '工具版本',
        description: '',
        versionKey: 'v1',
        status: 'DRAFT',
        descriptor: {}
      }),
      validateVersion: vi.fn(),
      publishVersion: vi.fn()
    }
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/capabilities/:id', component: CapabilityDetail }]
    })
    await router.push('/capabilities/1')
    await router.isReady()
    const wrapper = mount(CapabilityDetail, {
      props: {
        api: api as never,
        basePath: '/capabilities',
        skillBasePath: '/skills',
        permissions: ['foundation.capabilities.update', 'foundation.capabilities.publish']
      },
      global: { plugins: [router, ElementPlus] }
    })
    await flushPromises()
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '发布')!
      .trigger('click')
    await wrapper.setProps({ permissions: ['foundation.capabilities.publish'] })
    resolveConfirmation(true)
    await flushPromises()
    expect(api.validateVersion).not.toHaveBeenCalled()
    expect(api.publishVersion).not.toHaveBeenCalled()
    wrapper.unmount()
  })
})

it('基本信息默认可编辑，高级维护按需展开且字段校验失败可见', async () => {
  const { wrapper, api } = await toolForm()
  const maintenance = wrapper.get('details.capability-maintenance')
  expect(maintenance.attributes('open')).toBeUndefined()
  expect(wrapper.findAllComponents(JsonSchemaFields)).toHaveLength(2)
  expect(wrapper.get('input[aria-label="输入 JSON Schema新字段名"]').isVisible()).toBe(true)
  await field(wrapper, '超时（毫秒）').get('input').setValue('')
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  await vi.waitFor(() => expect(maintenance.attributes('open')).toBeDefined())
  expect(api.createDraftVersion).not.toHaveBeenCalled()
})

it('当前草稿与当前发布优先呈现，历史版本通过明确入口查看', async () => {
  const api = {
    getDefinition: vi.fn().mockResolvedValue({
      id: '1',
      name: '能力',
      capabilityType: 'TOOL',
      defaultVersionId: 'current',
      revision: 1
    }),
    listVersionSummaries: vi.fn().mockResolvedValue([
      { id: 'draft', versionKey: '待发布', status: 'DRAFT', descriptor: {} },
      { id: 'current', versionKey: '当前版本', status: 'PUBLISHED', descriptor: {} },
      { id: 'history', versionKey: '旧版本', status: 'PUBLISHED', descriptor: {} }
    ])
  }
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/capabilities/:id', component: CapabilityDetail }]
  })
  await router.push('/capabilities/1')
  const wrapper = mount(CapabilityDetail, {
    props: {
      api: api as never,
      basePath: '/capabilities',
      skillBasePath: '/skills',
      permissions: []
    },
    global: { plugins: [router, ElementPlus] }
  })
  toolWrappers.push(wrapper)
  await flushPromises()
  expect(wrapper.text()).toContain('当前版本')
  expect(wrapper.text()).not.toContain('旧版本')
  await wrapper
    .findAll('button')
    .find((item) => item.text() === '历史与维护（1）')!
    .trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('旧版本')
  expect(wrapper.findAll('button').some((item) => item.text() === '恢复为草稿')).toBe(false)
})

async function summaryEditor(withDraft = true, type: 'TOOL' | 'SKILL' = 'TOOL', empty = false) {
  vi.mocked(confirm).mockResolvedValue(true)
  const version = (id: string, status: 'DRAFT' | 'PUBLISHED', key: string): CapabilityVersion => ({
    id,
    definitionId: '1',
    capabilityType: type,
    versionKey: key,
    name: key,
    description: '版本说明',
    status,
    descriptor: {
      tool_name: key,
      input_schema: { type: 'object', properties: { [key]: { type: 'string' } } }
    }
  })
  const draft = version('2', 'DRAFT', 'draft-key'),
    publication = version('3', 'PUBLISHED', 'published-key')
  const full = empty ? [] : withDraft ? [draft, publication] : [publication]
  const api = {
    getDefinition: vi.fn().mockResolvedValue({
      id: '1',
      name: '能力',
      capabilityType: type,
      defaultVersionId: '3',
      revision: 7
    }),
    listVersionSummaries: vi.fn().mockResolvedValue(
      full.map(({ id, versionKey, name, description, status }) => ({
        id,
        versionKey,
        name,
        description,
        status
      }))
    ),
    getVersion: vi.fn(async (id: string) => structuredClone(full.find((item) => item.id === id)!)),
    createDraftVersion: vi.fn().mockResolvedValue(draft),
    updateDraftVersion: vi.fn().mockResolvedValue(draft),
    validateVersion: vi.fn().mockResolvedValue({ valid: true, errors: [], definitionRevision: 7 }),
    publishVersion: vi.fn()
  }
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/capabilities/:id', component: CapabilityDetail },
      { path: '/skills/:id', component: { template: '<div />' } }
    ]
  })
  await router.push('/capabilities/1')
  await router.isReady()
  const wrapper = mount(CapabilityDetail, {
    props: {
      api: api as never,
      basePath: '/capabilities',
      skillBasePath: '/skills',
      permissions: [
        'foundation.capabilities.create',
        'foundation.capabilities.update',
        'foundation.capabilities.publish'
      ]
    },
    global: { plugins: [router, ElementPlus] }
  })
  toolWrappers.push(wrapper)
  await flushPromises()
  return { wrapper, api, draft, publication }
}
async function clickCard(wrapper: VueWrapper, key: string, label: string) {
  const card = wrapper.findAll('.capability-version').find((item) => item.text().includes(key))!
  await card
    .findAll('button')
    .find((item) => item.text() === label)!
    .trigger('click')
  await flushPromises()
}
it('initial capability cards use summaries only and selecting one detail restores its original schema', async () => {
  const { wrapper, api } = await summaryEditor()
  expect(api.listVersionSummaries).toHaveBeenCalledExactlyOnceWith('1')
  expect(api.getVersion).not.toHaveBeenCalled()
  await clickCard(wrapper, 'draft-key', '编辑草稿')
  expect(api.getVersion).toHaveBeenCalledExactlyOnceWith('2')
  expect(field(wrapper, '版本标识').get<HTMLInputElement>('input').element.value).toBe('draft-key')
  expect(wrapper.findAllComponents(JsonSchemaFields)[0]!.props('modelValue')).toContain('draft-key')
})
it.each(['read-failure', 'wrong-id', 'wrong-definition', 'wrong-type'])(
  'failed detail leaves old draft fields, identity and dirty state intact (%s)',
  async (failure) => {
    const { wrapper, api, publication } = await summaryEditor()
    await clickCard(wrapper, 'draft-key', '编辑草稿')
    await field(wrapper, '名称').get('input').setValue('本地未保存名称')
    if (failure === 'read-failure') api.getVersion.mockRejectedValueOnce(new Error('读取失败'))
    else
      api.getVersion.mockResolvedValueOnce({
        ...publication,
        ...(failure === 'wrong-id'
          ? { id: '99' }
          : failure === 'wrong-definition'
            ? { definitionId: '99' }
            : { capabilityType: 'WORKFLOW' as const })
      })
    await clickCard(wrapper, 'published-key', '查看历史发布')
    expect(field(wrapper, '名称').get<HTMLInputElement>('input').element.value).toBe(
      '本地未保存名称'
    )
    expect(field(wrapper, '版本标识').get<HTMLInputElement>('input').element.value).toBe(
      'draft-key'
    )
    expect(wrapper.text()).toContain('工作草稿')
    const event = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(event)
    expect(event.defaultPrevented).toBe(true)
  }
)
it('restore cancellation and failure never clear the current draft identity or version key; successful restore requires a new key', async () => {
  const { wrapper, api } = await summaryEditor()
  await clickCard(wrapper, 'draft-key', '编辑草稿')
  vi.mocked(confirm).mockResolvedValueOnce(false)
  await clickCard(wrapper, 'published-key', '恢复为草稿')
  expect(api.getVersion).toHaveBeenCalledTimes(1)
  expect(field(wrapper, '版本标识').get<HTMLInputElement>('input').element.value).toBe('draft-key')
  api.getVersion.mockRejectedValueOnce(new Error('读取失败'))
  await clickCard(wrapper, 'published-key', '恢复为草稿')
  expect(field(wrapper, '版本标识').get<HTMLInputElement>('input').element.value).toBe('draft-key')
  await clickCard(wrapper, 'published-key', '恢复为草稿')
  expect(field(wrapper, '版本标识').get<HTMLInputElement>('input').element.value).toBe('')
  expect(wrapper.text()).toContain('工作草稿')
  expect(api.createDraftVersion).not.toHaveBeenCalled()
  const digest = vi.fn(async () => new Uint8Array(32).buffer)
  vi.stubGlobal('crypto', { subtle: { digest } })
  await field(wrapper, '版本标识').get('input').setValue('new-key')
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  expect(api.createDraftVersion).toHaveBeenCalledWith(
    '1',
    expect.objectContaining({ versionKey: 'new-key' })
  )
  expect(api.updateDraftVersion).not.toHaveBeenCalled()
})
it('working-draft copy of published content clears the old key only after a successful exact read', async () => {
  const { wrapper, api } = await summaryEditor(false)
  api.getVersion.mockRejectedValueOnce(new Error('读取失败'))
  await wrapper
    .findAll('button')
    .find((item) => item.text() === '编辑工作草稿')!
    .trigger('click')
  await flushPromises()
  expect(wrapper.find('form').exists()).toBe(false)
  await wrapper
    .findAll('button')
    .find((item) => item.text() === '编辑工作草稿')!
    .trigger('click')
  await flushPromises()
  expect(field(wrapper, '版本标识').get<HTMLInputElement>('input').element.value).toBe('')
  expect(wrapper.text()).toContain('工作草稿')
})
it('Skill cards keep file navigation without pre-reading descriptors and blank new editing does not fetch detail', async () => {
  const { wrapper, api } = await summaryEditor(false, 'SKILL')
  expect(wrapper.find('a[href="/skills/1?versionId=3"]').exists()).toBe(true)
  expect(api.getVersion).not.toHaveBeenCalled()
  const fresh = await summaryEditor(false, 'TOOL', true)
  await fresh.wrapper
    .findAll('button')
    .find((item) => item.text() === '编辑工作草稿')!
    .trigger('click')
  await flushPromises()
  expect(fresh.api.getVersion).not.toHaveBeenCalled()
})

it('cancelling a published-template reread preserves the local new version key and dirty draft', async () => {
  const { wrapper, api } = await summaryEditor(false)
  await wrapper
    .findAll('button')
    .find((item) => item.text() === '编辑工作草稿')!
    .trigger('click')
  await flushPromises()
  await field(wrapper, '版本标识').get('input').setValue('local-key')
  await field(wrapper, '名称').get('input').setValue('本地草稿')
  vi.mocked(confirm).mockResolvedValueOnce(false)
  await wrapper
    .findAll('button')
    .find((item) => item.text() === '编辑工作草稿')!
    .trigger('click')
  await flushPromises()
  expect(api.getVersion).toHaveBeenCalledTimes(1)
  expect(field(wrapper, '版本标识').get<HTMLInputElement>('input').element.value).toBe('local-key')
  expect(field(wrapper, '名称').get<HTMLInputElement>('input').element.value).toBe('本地草稿')
  const event = new Event('beforeunload', { cancelable: true })
  window.dispatchEvent(event)
  expect(event.defaultPrevented).toBe(true)
})
