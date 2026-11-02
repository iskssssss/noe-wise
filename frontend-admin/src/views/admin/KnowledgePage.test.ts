import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { computed, ref } from 'vue'
import { HttpClientError } from '@skillforge/shared/utils/http'
import { confirm } from '@skillforge/shared/feedback'
import { createPermissionDirective } from '@skillforge/shared/directives/permission'
import {
  ElButton,
  ElCollapseItem,
  ElFormItem,
  ElForm,
  ElInputNumber,
  ElOption,
  ElSelect,
  ElSwitch,
  ElTable
} from 'element-plus'
import KnowledgePage from './KnowledgePage.vue'
const permissions = ref(['foundation.knowledge.create', 'foundation.knowledge.update'])
vi.mock('../../stores/auth', () => ({
  useAuthStore: () => ({ user: { tenant_id: '7', sub: '8', permissions: permissions.value } })
}))

const api = vi.hoisted(() => ({
  listBases: vi.fn(),
  createBase: vi.fn(),
  updateBase: vi.fn(),
  getBase: vi.fn()
}))
const createReceipt = vi.hoisted(() => vi.fn())
const push = vi.hoisted(() => vi.fn())
vi.mock('../../api', () => ({
  api: {
    knowledge: {
      ...api,
      createWithReceipt: async (requestId: string, payload: unknown) => {
        const result = await api.createBase(payload)
        return { request_id: requestId, kb_id: result?.id }
      },
      createReceipt
    }
  }
}))
vi.mock('@skillforge/shared/feedback', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@skillforge/shared/feedback')>()),
  confirm: vi.fn(),
  message: { success: vi.fn(), warning: vi.fn() }
}))
vi.mock('vue-router', () => ({
  onBeforeRouteLeave: vi.fn(),
  onBeforeRouteUpdate: vi.fn(),
  useRouter: () => ({ push })
}))
const wrappers: VueWrapper[] = []

beforeEach(() => {
  vi.resetAllMocks()
  sessionStorage.clear()
  permissions.value = ['foundation.knowledge.create', 'foundation.knowledge.update']
  api.listBases.mockResolvedValue({ items: [], total: 0 })
  api.createBase.mockResolvedValue({ id: 'kb-owned', name: '知识配置' })
  push.mockResolvedValue(undefined)
})
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  document.body.innerHTML = ''
})

/** 按真实表单标签选择三个数字组件的原生输入，不替换 Element Plus。 */
function numeric(wrapper: VueWrapper, label: string) {
  const item = wrapper.findAllComponents(ElFormItem).find((item) => item.props('label') === label)
  if (!item) throw Error('未找到字段 ' + label)
  return item.getComponent(ElInputNumber).get<HTMLInputElement>('input[type="number"]')
}

/** 可见保存入口仍走原生产表单和 API 边界。 */
function button(wrapper: VueWrapper, label: string) {
  const found = wrapper.findAllComponents(ElButton).find((button) => button.text() === label)
  if (!found) throw Error('未找到按钮 ' + label)
  return found
}

describe('知识库数字控件的原生和 ARIA 状态', () => {
  it('首次读取失败不显示空库，刷新失败时保留旧结果并说明', async () => {
    api.listBases
      .mockRejectedValueOnce(new Error('目录暂不可用'))
      .mockResolvedValueOnce({ items: [{ id: 'existing', name: '已有知识库' }], total: 1 })
      .mockRejectedValueOnce(new Error('刷新失败'))
    const wrapper = mount(KnowledgePage, {
      attachTo: document.body,
      global: { stubs: { RouterLink: true } }
    })
    wrappers.push(wrapper)
    await flushPromises()
    expect(wrapper.text()).toContain('知识库列表读取失败，请点击刷新重试')
    expect(wrapper.text()).not.toContain('尚无知识库')
    expect(wrapper.text()).not.toContain('0 个知识库')
    await button(wrapper, '刷新').trigger('click')
    await flushPromises()
    expect((wrapper.getComponent(ElTable).props() as { data: unknown }).data).toEqual([
      { id: 'existing', name: '已有知识库' }
    ])
    await button(wrapper, '刷新').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('知识库列表刷新失败，以下保留上次读取的结果')
    expect((wrapper.getComponent(ElTable).props() as { data: unknown }).data).toEqual([
      { id: 'existing', name: '已有知识库' }
    ])
  })

  it('打开、保存中及失败恢复后状态一致，键盘与原640/40/7配置保留', async () => {
    let rejectSave!: (error: Error) => void
    api.createBase.mockImplementationOnce(
      () =>
        new Promise((_resolve, reject) => {
          rejectSave = reject
        })
    )
    const wrapper = mount(KnowledgePage, {
      attachTo: document.body,
      global: { stubs: { RouterLink: true } }
    })
    wrappers.push(wrapper)
    await flushPromises()
    await button(wrapper, '新建知识库').trigger('click')
    await flushPromises()
    await wrapper.findComponent(ElCollapseItem).get('.el-collapse-item__header').trigger('click')
    await flushPromises()
    const labels = ['分块大小', '重叠长度', '召回条数']
    const inputs = labels.map((label) => numeric(wrapper, label))
    for (const input of inputs) {
      expect(input.element.disabled).toBe(false)
      expect(input.attributes('aria-disabled')).toBe('false')
      expect(input.element.tabIndex).toBeGreaterThanOrEqual(0)
      input.element.focus()
      expect(document.activeElement).toBe(input.element)
    }
    const name = wrapper
      .findAllComponents(ElFormItem)
      .find((item) => item.props('label') === '名称')!
    await name.get('input').setValue('知识配置')
    for (const [index, value] of ['640', '40', '7'].entries()) await inputs[index]!.setValue(value)
    inputs[0]!.element.focus()
    await inputs[0]!.trigger('keydown', { key: 'ArrowUp', code: 'ArrowUp' })
    await inputs[0]!.trigger('keydown', { key: 'ArrowDown', code: 'ArrowDown' })
    expect(inputs.map((input) => input.element.value)).toEqual(['640', '40', '7'])
    await button(wrapper, '保存').trigger('click')
    await flushPromises()
    expect(api.createBase).toHaveBeenCalledWith(
      expect.objectContaining({ chunk_size: 640, chunk_overlap: 40, top_k: 7 })
    )
    expect(api.updateBase).not.toHaveBeenCalled()
    for (const input of inputs) {
      expect(input.element.disabled).toBe(true)
      expect(input.attributes('aria-disabled')).toBe('true')
    }
    rejectSave(new Error('配置保存失败'))
    await flushPromises()
    expect(
      Array.from(document.body.querySelectorAll('.el-message__content')).at(-1)?.textContent
    ).toContain('配置保存失败')
    for (const input of inputs) {
      expect(input.element.disabled).toBe(true)
      expect(input.attributes('aria-disabled')).toBe('true')
    }
    expect(inputs.map((input) => input.element.value)).toEqual(['640', '40', '7'])
  })
  it('新建默认使用平台当前设置，仅填写名称时不追加覆盖写入', async () => {
    const wrapper = mount(KnowledgePage, {
      attachTo: document.body,
      global: { stubs: { RouterLink: true } }
    })
    wrappers.push(wrapper)
    await flushPromises()
    await button(wrapper, '新建知识库').trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('推荐使用平台当前默认设置')
    await wrapper
      .findAllComponents(ElFormItem)
      .find((item) => item.props('label') === '名称')!
      .get('input')
      .setValue('采用平台设置')
    await button(wrapper, '保存').trigger('click')
    await flushPromises()
    expect(api.createBase).toHaveBeenCalledWith({ name: '采用平台设置', description: '' })
    expect(api.updateBase).not.toHaveBeenCalled()
  })
  it('编辑未覆盖的字段保留空值，保存名称不把前端数字或关闭状态写入', async () => {
    api.listBases.mockResolvedValue({ items: [{ id: 'existing', name: '已有知识库' }], total: 1 })
    api.getBase.mockResolvedValue({
      id: 'existing',
      name: '已有知识库',
      chunk_size: null,
      chunk_overlap: null,
      retrieval_mode: null,
      top_k: null,
      score_threshold_enabled: null,
      score_threshold: null,
      vector_weight: null,
      rerank_enabled: null
    })
    const wrapper = mount(KnowledgePage, {
      attachTo: document.body,
      global: { stubs: { RouterLink: true } }
    })
    wrappers.push(wrapper)
    await flushPromises()
    await button(wrapper, '检索配置').trigger('click')
    await flushPromises()
    expect(numeric(wrapper, '分块大小').element.value).toBe('')
    await button(wrapper, '保存').trigger('click')
    await flushPromises()
    expect(api.updateBase).toHaveBeenCalledWith('existing', { name: '已有知识库', description: '' })
  })
  it('检索方式提供现行语义和全文wire值，明确覆盖才提交', async () => {
    const wrapper = mount(KnowledgePage, {
      attachTo: document.body,
      global: { stubs: { RouterLink: true } }
    })
    wrappers.push(wrapper)
    await flushPromises()
    await button(wrapper, '新建知识库').trigger('click')
    await flushPromises()
    await wrapper.findComponent(ElCollapseItem).get('.el-collapse-item__header').trigger('click')
    const retrieval = wrapper
      .findAllComponents(ElFormItem)
      .find((item) => item.props('label') === '检索方式')!
      .getComponent(ElSelect)
    expect(retrieval.findAllComponents(ElOption).map((option) => option.props('value'))).toEqual([
      'semantic',
      'full_text',
      'hybrid'
    ])
    retrieval.vm.$emit('update:modelValue', 'full_text')
    await wrapper
      .findAllComponents(ElFormItem)
      .find((item) => item.props('label') === '名称')!
      .get('input')
      .setValue('全文知识库')
    await button(wrapper, '保存').trigger('click')
    await flushPromises()
    expect(api.createBase).toHaveBeenCalledWith({
      name: '全文知识库',
      description: '',
      retrieval_mode: 'full_text'
    })
    expect(api.updateBase).not.toHaveBeenCalled()
  })
  it('真实动作指令允许只有create权限的账号使用平台默认创建，并禁用高级设置', async () => {
    permissions.value = ['foundation.knowledge.create']
    const wrapper = mount(KnowledgePage, {
      attachTo: document.body,
      global: {
        stubs: { RouterLink: true },
        directives: {
          permission: createPermissionDirective(() => computed(() => permissions.value))
        }
      }
    })
    wrappers.push(wrapper)
    await flushPromises()
    expect(button(wrapper, '新建知识库').element.isConnected).toBe(true)
    await button(wrapper, '新建知识库').trigger('click')
    await flushPromises()
    expect(button(wrapper, '保存').element.isConnected).toBe(true)
    expect(button(wrapper, '保存').props('disabled')).toBe(false)
    expect(numeric(wrapper, '分块大小').element.disabled).toBe(true)
    expect(document.body.textContent).toContain('自定义分块、召回条数和阈值需要更新权限')
    await wrapper
      .findAllComponents(ElFormItem)
      .find((item) => item.props('label') === '名称')!
      .get('input')
      .setValue('默认设置创建')
    await button(wrapper, '保存').trigger('click')
    await flushPromises()
    expect(api.createBase).toHaveBeenCalledWith({ name: '默认设置创建', description: '' })
    expect(api.updateBase).not.toHaveBeenCalled()
  })
  it('高级覆盖填好后失去update权限时，表单提交在创建前拒绝，避免部分成功', async () => {
    const wrapper = mount(KnowledgePage, {
      attachTo: document.body,
      global: {
        stubs: { RouterLink: true },
        directives: {
          permission: createPermissionDirective(() => computed(() => permissions.value))
        }
      }
    })
    wrappers.push(wrapper)
    await flushPromises()
    await button(wrapper, '新建知识库').trigger('click')
    await flushPromises()
    await wrapper
      .findAllComponents(ElFormItem)
      .find((item) => item.props('label') === '名称')!
      .get('input')
      .setValue('需要覆盖的库')
    wrapper.findAllComponents(ElInputNumber)[0]!.vm.$emit('update:modelValue', 640)
    await flushPromises()
    permissions.value.splice(permissions.value.indexOf('foundation.knowledge.update'), 1)
    await flushPromises()
    expect(button(wrapper, '保存').element.isConnected).toBe(false)
    expect(numeric(wrapper, '分块大小').element.disabled).toBe(true)
    await wrapper.findComponent(ElForm).trigger('submit')
    await flushPromises()
    expect(api.createBase).not.toHaveBeenCalled()
    expect(api.updateBase).not.toHaveBeenCalled()
    expect(document.body.textContent).toContain('当前账号没有更新知识库权限，无法保存自定义配置')
  })
})

function field(wrapper: VueWrapper, label: string) {
  return wrapper.findAllComponents(ElFormItem).find((item) => item.props('label') === label)!
}
async function createForm(name: string) {
  const wrapper = mount(KnowledgePage, {
    attachTo: document.body,
    global: {
      stubs: { RouterLink: true },
      directives: {
        permission: createPermissionDirective(() => computed(() => permissions.value))
      }
    }
  })
  wrappers.push(wrapper)
  await flushPromises()
  await button(wrapper, '新建知识库').trigger('click')
  await flushPromises()
  await field(wrapper, '名称').get('input').setValue(name)
  await wrapper.findComponent(ElCollapseItem).get('.el-collapse-item__header').trigger('click')
  await flushPromises()
  return wrapper
}

it('create-only角色可选择经济索引并一次创建全文且无重排的知识库', async () => {
  permissions.value = ['foundation.knowledge.create']
  const wrapper = await createForm('经济资料库')
  field(wrapper, '索引方式').getComponent(ElSelect).vm.$emit('update:modelValue', 'economical')
  await flushPromises()
  expect(field(wrapper, '检索方式').getComponent(ElSelect).props('modelValue')).toBe('full_text')
  expect(field(wrapper, '检索方式').getComponent(ElSelect).props('disabled')).toBe(true)
  expect(field(wrapper, '重排序').getComponent(ElSwitch).props('modelValue')).toBe(false)
  expect(field(wrapper, '重排序').getComponent(ElSwitch).props('disabled')).toBe(true)
  expect(field(wrapper, '分数阈值').getComponent(ElSwitch).props('disabled')).toBe(true)
  expect(document.body.textContent).toContain('不需要 Embedding 模型')
  expect(document.body.textContent).toContain('不建立向量')
  expect(button(wrapper, '保存').props('disabled')).toBe(false)
  await button(wrapper, '保存').trigger('click')
  await flushPromises()
  expect(api.createBase).toHaveBeenCalledWith({
    name: '经济资料库',
    description: '',
    indexing_mode: 'economical',
    retrieval_mode: 'full_text',
    rerank_enabled: false
  })
  expect(api.updateBase).not.toHaveBeenCalled()
})

it('创建原生检索和重排设置只需创建权限，不再先建库再更新', async () => {
  permissions.value = ['foundation.knowledge.create']
  const wrapper = await createForm('高质量资料库')
  field(wrapper, '索引方式').getComponent(ElSelect).vm.$emit('update:modelValue', 'high_quality')
  field(wrapper, '检索方式').getComponent(ElSelect).vm.$emit('update:modelValue', 'hybrid')
  field(wrapper, '重排序').getComponent(ElSwitch).vm.$emit('update:modelValue', true)
  await flushPromises()
  expect(field(wrapper, '检索方式').getComponent(ElSelect).props('disabled')).toBe(false)
  expect(field(wrapper, '重排序').getComponent(ElSwitch).props('disabled')).toBe(false)
  await button(wrapper, '保存').trigger('click')
  await flushPromises()
  expect(api.createBase).toHaveBeenCalledWith({
    name: '高质量资料库',
    description: '',
    indexing_mode: 'high_quality',
    retrieval_mode: 'hybrid',
    rerank_enabled: true
  })
  expect(api.updateBase).not.toHaveBeenCalled()
})

it('经济索引屏蔽不适配参数，切回后恢复显式检索和重排输入', async () => {
  const wrapper = await createForm('保留输入')
  const indexing = field(wrapper, '索引方式').getComponent(ElSelect)
  field(wrapper, '检索方式').getComponent(ElSelect).vm.$emit('update:modelValue', 'hybrid')
  field(wrapper, '重排序').getComponent(ElSwitch).vm.$emit('update:modelValue', true)
  await flushPromises()
  field(wrapper, '向量权重').getComponent(ElInputNumber).vm.$emit('update:modelValue', 0.4)
  indexing.vm.$emit('update:modelValue', 'economical')
  await flushPromises()
  expect(
    wrapper.findAllComponents(ElFormItem).some((item) => item.props('label') === '向量权重')
  ).toBe(false)
  expect(field(wrapper, '检索方式').getComponent(ElSelect).props('modelValue')).toBe('full_text')
  expect(field(wrapper, '重排序').getComponent(ElSwitch).props('modelValue')).toBe(false)
  indexing.vm.$emit('update:modelValue', 'high_quality')
  await flushPromises()
  expect(field(wrapper, '检索方式').getComponent(ElSelect).props('modelValue')).toBe('hybrid')
  expect(field(wrapper, '重排序').getComponent(ElSwitch).props('modelValue')).toBe(true)
  expect(field(wrapper, '向量权重').getComponent(ElInputNumber).props('modelValue')).toBe(0.4)
})

it('创建时一次提交经济索引和分块配置，无需追加更新', async () => {
  const wrapper = await createForm('两阶段资料库')
  field(wrapper, '索引方式').getComponent(ElSelect).vm.$emit('update:modelValue', 'economical')
  await numeric(wrapper, '分块大小').setValue('640')
  await flushPromises()
  await button(wrapper, '保存').trigger('click')
  await flushPromises()
  expect(api.createBase).toHaveBeenCalledTimes(1)
  expect(api.createBase).toHaveBeenLastCalledWith({
    name: '两阶段资料库',
    description: '',
    indexing_mode: 'economical',
    retrieval_mode: 'full_text',
    rerank_enabled: false,
    chunk_size: 640
  })
  expect(api.updateBase).not.toHaveBeenCalled()
})

it('编辑经济库保留原索引语义，不发送索引切换参数', async () => {
  api.listBases.mockResolvedValue({ items: [{ id: 'economical-existing', name: '已有经济库' }] })
  api.getBase.mockResolvedValue({
    id: 'economical-existing',
    name: '已有经济库',
    indexing_mode: 'economical',
    retrieval_mode: 'full_text',
    rerank_enabled: false
  })
  const wrapper = mount(KnowledgePage, {
    attachTo: document.body,
    global: { stubs: { RouterLink: true } }
  })
  wrappers.push(wrapper)
  await flushPromises()
  await button(wrapper, '检索配置').trigger('click')
  await flushPromises()
  expect(field(wrapper, '索引方式').findComponent(ElSelect).exists()).toBe(false)
  expect(field(wrapper, '索引方式').text()).toContain('经济索引')
  expect(field(wrapper, '检索方式').getComponent(ElSelect).props('disabled')).toBe(true)
  await button(wrapper, '保存').trigger('click')
  await flushPromises()
  expect(api.createBase).not.toHaveBeenCalled()
  expect(api.updateBase).toHaveBeenCalledWith('economical-existing', {
    name: '已有经济库',
    description: '',
    retrieval_mode: 'full_text',
    rerank_enabled: false
  })
})

it('创建成功直接进入新知识库，导航失败时保留原身份入口', async () => {
  permissions.value.push('foundation.knowledge.read')
  push.mockRejectedValueOnce(new Error('导航失败'))
  const wrapper = await createForm('连续维护')
  await button(wrapper, '保存').trigger('click')
  await flushPromises()
  expect(push).toHaveBeenCalledWith('/admin/knowledge/kb-owned')
  expect(api.createBase).toHaveBeenCalledTimes(1)
  expect(wrapper.text()).toContain('知识库“连续维护”已创建，标识：kb-owned')
  expect(wrapper.find('router-link-stub').attributes('to')).toBe('/admin/knowledge/kb-owned')
})

it('创建后导航失败时提示首个文件待上传，放弃前保护文件选择', async () => {
  permissions.value.push('foundation.knowledge.read')
  push.mockRejectedValueOnce(new Error('导航失败'))
  const wrapper = await createForm('连续维护')
  const input = document.querySelector<HTMLInputElement>('input[type="file"]')!
  Object.defineProperty(input, 'files', {
    value: [new File(['内容'], '资料.txt')],
    configurable: true
  })
  input.dispatchEvent(new Event('change', { bubbles: true }))
  await flushPromises()
  await button(wrapper, '保存').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('文件“资料.txt”尚未上传')
  expect(wrapper.find('router-link-stub').attributes('to')).toBe('/admin/knowledge/kb-owned')
  vi.mocked(confirm).mockResolvedValueOnce(false).mockResolvedValueOnce(true)
  await button(wrapper, '新建知识库').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('文件“资料.txt”尚未上传')
  expect(api.createBase).toHaveBeenCalledTimes(1)
  await button(wrapper, '新建知识库').trigger('click')
  await flushPromises()
  expect(wrapper.text()).not.toContain('文件“资料.txt”尚未上传')
  expect(vi.mocked(confirm)).toHaveBeenCalledTimes(2)
})

it('没有读取权限时保留已创建信息且不进入详情', async () => {
  permissions.value = ['foundation.knowledge.create']
  const wrapper = await createForm('无读取权限')
  await button(wrapper, '保存').trigger('click')
  await flushPromises()
  expect(push).not.toHaveBeenCalled()
  expect(wrapper.text()).toContain('当前账号没有知识库读取权限')
  expect(api.createBase).toHaveBeenCalledTimes(1)
})

it('创建结果未知时锁定原输入并禁止再次创建', async () => {
  api.listBases.mockResolvedValue({ items: [{ id: 'other', name: '其他知识库' }] })
  api.createBase.mockRejectedValueOnce(new Error('连接中断'))
  const wrapper = await createForm('待核对知识库')
  await button(wrapper, '保存').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('创建结果尚未确认')
  expect(button(wrapper, '保存').props('disabled')).toBe(true)
  expect(button(wrapper, '检索配置').props('disabled')).toBe(true)
  expect(button(wrapper, '删除').props('disabled')).toBe(true)
  expect(field(wrapper, '名称').get<HTMLInputElement>('input').element.value).toBe('待核对知识库')
  expect(numeric(wrapper, '分块大小').element.disabled).toBe(true)
  expect(field(wrapper, '检索方式').getComponent(ElSelect).props('disabled')).toBe(true)
  expect(field(wrapper, '重排序').getComponent(ElSwitch).props('disabled')).toBe(true)
  await wrapper.findComponent(ElForm).trigger('submit')
  await flushPromises()
  expect(api.createBase).toHaveBeenCalledTimes(1)
})

it('创建回执缺少标识时视为结果未知，不重复创建', async () => {
  api.createBase.mockResolvedValueOnce({ name: '缺少标识' })
  const wrapper = await createForm('缺少标识')
  await button(wrapper, '保存').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('创建结果尚未确认')
  expect(button(wrapper, '保存').props('disabled')).toBe(true)
  expect(api.createBase).toHaveBeenCalledTimes(1)
  expect(push).not.toHaveBeenCalled()
})

it('结果未知跨页面重建保留原请求，只读核对后进入原知识库', async () => {
  permissions.value.push('foundation.knowledge.read')
  api.createBase.mockRejectedValueOnce(new Error('连接中断'))
  const first = await createForm('回执恢复知识库')
  await button(first, '保存').trigger('click')
  await flushPromises()
  const stored = JSON.parse(sessionStorage.getItem('skillforge.knowledge-create:["7","8"]')!) as {
    requestId: string
  }
  expect(first.text()).toContain(stored.requestId)
  first.unmount()
  const second = mount(KnowledgePage, {
    attachTo: document.body,
    global: { stubs: { RouterLink: true } }
  })
  wrappers.push(second)
  await flushPromises()
  expect(second.text()).toContain(stored.requestId)
  expect(second.findAllComponents(ElButton).some((item) => item.text() === '查看原创建输入')).toBe(
    false
  )
  createReceipt
    .mockResolvedValueOnce(undefined)
    .mockResolvedValueOnce({ request_id: stored.requestId, kb_id: 'kb-owned' })
  await button(second, '核对创建结果').trigger('click')
  await flushPromises()
  expect(second.text()).toContain(stored.requestId)
  await button(second, '核对创建结果').trigger('click')
  await flushPromises()
  expect(push).toHaveBeenCalledWith('/admin/knowledge/kb-owned')
  expect(api.createBase).toHaveBeenCalledTimes(1)
  expect(sessionStorage.getItem('skillforge.knowledge-create:["7","8"]')).toBeNull()
})

it('知识库写入明确拒绝后可修正并再次创建', async () => {
  api.createBase.mockRejectedValueOnce(
    new HttpClientError({
      status: 422,
      code: 'KNOWLEDGE_WRITE_REJECTED',
      userMessage: '索引设置无效'
    })
  )
  const wrapper = await createForm('需修正')
  await button(wrapper, '保存').trigger('click')
  await flushPromises()
  expect(button(wrapper, '保存').props('disabled')).toBe(false)
  await button(wrapper, '保存').trigger('click')
  await flushPromises()
  expect(api.createBase).toHaveBeenCalledTimes(2)
})
