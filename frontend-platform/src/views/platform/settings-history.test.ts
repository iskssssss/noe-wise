import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { computed } from 'vue'
import { clearFeedback } from '@skillforge/shared/feedback'
import { createPermissionDirective } from '@skillforge/shared/directives/permission'
import {
  ElMessageBox,
  ElInput,
  ElInputNumber,
  ElTable,
  ElCard,
  ElButton,
  ElDescriptions,
  ElSelect,
  ElFormItem
} from 'element-plus'
import SystemSettingsPage from './SystemSettingsPage.vue'
const currentAuth = vi.hoisted(() => ({
  identityVersion: 1,
  user: { sub: 'platform-actor', permissions: ['foundation.runtime_settings.update'] }
}))
vi.mock('../../stores/auth', () => ({ useAuthStore: () => currentAuth }))
const mocks = vi.hoisted(() => ({
  list: vi.fn(),
  contextPolicy: vi.fn().mockResolvedValue({ version: 'context-policy.v2' }),
  history: vi.fn(),
  rollback: vi.fn(),
  update: vi.fn()
}))
vi.mock('../../api', () => ({ api: { admin: { systemSettings: mocks } } }))
let wrapper: VueWrapper

beforeEach(() => vi.clearAllMocks())
afterEach(() => {
  wrapper?.unmount()
  clearFeedback()
  vi.restoreAllMocks()
  document.body.innerHTML = ''
})
describe('系统设置历史请求归属', () => {
  it('本组重读仅替换确认的分组，保留其他脏组；无效枚举与空数值就近阻止提交', async () => {
    const original = [
      {
        key: 'A',
        label: '设置A',
        revision: 1,
        fields: [{ key: 'amount', label: '数量', type: 'number', minimum: 1, maximum: 10 }],
        values: { amount: 3 }
      },
      {
        key: 'B',
        label: '设置B',
        revision: 2,
        fields: [{ key: 'mode', label: '模式', type: 'select', choices: ['AVAILABLE'] }],
        values: { mode: 'AVAILABLE' }
      }
    ]
    mocks.list.mockResolvedValue(structuredClone(original))
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/', component: SystemSettingsPage }]
    })
    await router.push('/')
    await router.isReady()
    wrapper = mount(SystemSettingsPage, {
      attachTo: document.body,
      global: {
        plugins: [router],
        directives: {
          permission: createPermissionDirective(() => computed(() => currentAuth.user.permissions))
        }
      }
    })
    await flushPromises()
    wrapper.getComponent(ElInputNumber).vm.$emit('update:modelValue', undefined)
    const select = wrapper.getComponent(ElSelect)
    select.vm.$emit('update:modelValue', 'RETIRED')
    await flushPromises()
    await wrapper.findAll('form')[0]!.trigger('submit')
    await flushPromises()
    await wrapper.findAll('form')[1]!.trigger('submit')
    await flushPromises()
    expect(mocks.update).not.toHaveBeenCalled()
    expect(
      wrapper
        .findAllComponents(ElFormItem)
        .find((value) => value.props('label') === '数量')!
        .props('error')
    ).toBe('请填写有效数值')
    expect(
      wrapper
        .findAllComponents(ElFormItem)
        .find((value) => value.props('label') === '模式')!
        .props('error')
    ).toBe('请选择当前可用选项')
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue(
      'confirm' as Awaited<ReturnType<typeof ElMessageBox.confirm>>
    )
    mocks.list.mockResolvedValue([
      { ...original[0], revision: 3, values: { amount: 5 } },
      { ...original[1], revision: 4, values: { mode: 'SERVER' } }
    ])
    await wrapper
      .findAll('button')
      .filter((value) => value.text() === '重新读取本组')[0]!
      .trigger('click')
    await flushPromises()
    expect(wrapper.getComponent(ElInputNumber).props('modelValue')).toBe(5)
    expect(select.props('modelValue')).toBe('RETIRED')
    expect(wrapper.text()).toContain('未保存')
    expect(ElMessageBox.confirm).toHaveBeenCalledTimes(1)
  })
  it('旧知识契约仅展示原版本元数据，禁用详情和回滚且不覆盖当前表单', async () => {
    const group = {
      key: 'knowledge',
      label: '知识库',
      revision: 8,
      fields: [{ key: 'default_top_k', label: '默认检索条数', type: 'number', minimum: 1 }],
      values: { default_top_k: 5 }
    }
    const retired = {
      revision: 4,
      created_at: '2026-10-10T12:00:00Z',
      actor_id: 'original-actor',
      contract_status: 'RETIRED',
      values: {}
    }
    mocks.list.mockResolvedValue([group])
    mocks.history.mockResolvedValue([
      retired,
      {
        revision: 7,
        created_at: '2026-10-11T12:00:00Z',
        contract_status: 'SUPPORTED',
        values: { default_top_k: 6 }
      }
    ])
    mocks.update.mockImplementation(async (_key, request) => ({
      ...group,
      revision: 9,
      values: request.values
    }))
    const confirmation = vi.spyOn(ElMessageBox, 'confirm')
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/', component: SystemSettingsPage }]
    })
    await router.push('/')
    await router.isReady()
    wrapper = mount(SystemSettingsPage, {
      attachTo: document.body,
      global: {
        plugins: [router],
        directives: {
          permission: createPermissionDirective(() => computed(() => currentAuth.user.permissions))
        }
      }
    })
    await flushPromises()
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '历史版本')!
      .trigger('click')
    await flushPromises()
    const retiredCard = wrapper
      .findAllComponents(ElCard)
      .find((card) => card.text().includes('修订 4'))!
    expect(retiredCard.text()).toContain('旧契约')
    expect(retiredCard.text()).toContain('详情不可用')
    expect(retiredCard.text()).toContain('无法回滚')
    expect(retiredCard.text()).toContain('original-actor')
    expect(retiredCard.findComponent(ElDescriptions).exists()).toBe(false)
    const restore = retiredCard.getComponent(ElButton)
    expect(restore.props('disabled')).toBe(true)
    await restore.trigger('click')
    restore.vm.$emit('click')
    await flushPromises()
    expect(confirmation).not.toHaveBeenCalled()
    expect(mocks.rollback).not.toHaveBeenCalled()
    expect(wrapper.getComponent(ElInputNumber).props('modelValue')).toBe(5)
    const supportedCard = wrapper
      .findAllComponents(ElCard)
      .find((card) => card.text().includes('修订 7'))!
    expect(supportedCard.findComponent(ElDescriptions).exists()).toBe(true)
    expect(supportedCard.getComponent(ElButton).props('disabled')).toBe(false)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mocks.update).toHaveBeenCalledWith('knowledge', {
      expected_revision: 8,
      values: { default_top_k: 5 }
    })
  })
  it('会话工作区真实checkbox有非空可见说明，编辑仍在保存时提交boolean', async () => {
    const group = {
      key: 'workspace',
      label: '会话工作区',
      revision: 7,
      fields: [{ key: 'enabled', label: '启用会话工作区', type: 'boolean', secret: false }],
      values: { enabled: false }
    }
    mocks.list.mockResolvedValue([group])
    mocks.update.mockImplementation(async (_key, request) => ({
      ...group,
      revision: 8,
      values: request.values
    }))
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/', component: SystemSettingsPage }]
    })
    await router.push('/')
    await router.isReady()
    wrapper = mount(SystemSettingsPage, {
      attachTo: document.body,
      global: {
        plugins: [router],
        directives: {
          permission: createPermissionDirective(() => computed(() => currentAuth.user.permissions))
        }
      }
    })
    await flushPromises()
    const checkbox = wrapper.get('input[type="checkbox"]')
    const help = wrapper.get('.field-tip')
    const helpId = help.attributes('id')
    expect(helpId).toBeTruthy()
    expect(checkbox.attributes('aria-describedby')?.split(/\s+/).filter(Boolean)).toEqual([helpId])
    expect(
      [...document.querySelectorAll('[id]')].filter((element) => element.id === helpId)
    ).toHaveLength(1)
    expect(help.isVisible()).toBe(true)
    expect(help.text()).toContain('文件工作区')
    expect(help.text()).toContain('保存')
    expect(wrapper.text()).toContain('启用会话工作区')
    await checkbox.setValue(true)
    await flushPromises()
    expect(mocks.update).not.toHaveBeenCalled()
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mocks.update).toHaveBeenCalledWith('workspace', {
      expected_revision: 7,
      values: { enabled: true }
    })
    expect(wrapper.get('input[type="checkbox"]').attributes('aria-describedby')).toBe(helpId)
    expect(help.isVisible()).toBe(true)
  })

  it('三个场景的十五项预算统一保存一个修订，角色额度与策略版本只读展示', async () => {
    const fields = ['standard_root', 'delegation_root', 'team_root'].flatMap((prefix) =>
      ['business_models', 'tools', 'knowledge', 'total_tokens', 'active_seconds'].map(
        (dimension) => ({
          key: `${prefix}_${dimension}`,
          type: 'number',
          label: dimension,
          minimum: 1
        })
      )
    )
    const group = {
      key: 'task_budget',
      label: '任务额度',
      revision: 42,
      fields,
      values: Object.fromEntries(fields.map((field) => [field.key, 100])),
      derived_budgets: [
        {
          scenario: 'STANDARD',
          role: 'PRIMARY',
          business_models: 50,
          auxiliary_models: 25,
          tools: 50,
          knowledge: 50,
          total_tokens: 80,
          active_seconds: 100
        }
      ]
    }
    mocks.list.mockResolvedValue([group])
    mocks.update.mockClear()
    mocks.update.mockImplementation(async (_key, request) => ({
      ...group,
      revision: 43,
      values: request.values
    }))
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/', component: SystemSettingsPage }]
    })
    await router.push('/')
    await router.isReady()
    wrapper = mount(SystemSettingsPage, {
      attachTo: document.body,
      global: {
        plugins: [router],
        directives: {
          permission: createPermissionDirective(() => computed(() => currentAuth.user.permissions))
        }
      }
    })
    await flushPromises()
    expect(wrapper.findAll('section h3').map((heading) => heading.text())).toEqual([
      '普通任务',
      '协作任务',
      '团队任务'
    ])
    const inputs = wrapper.findAllComponents(ElInputNumber)
    expect(inputs).toHaveLength(15)
    expect((wrapper.getComponent(ElTable).props() as { data: unknown }).data).toEqual(
      group.derived_budgets
    )
    expect(wrapper.getComponent(ElTable).findAll('input')).toHaveLength(0)
    expect(wrapper.text()).toContain('context-policy.v2 · 由代码管理')
    inputs[0]!.vm.$emit('update:modelValue', 200)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mocks.update).toHaveBeenCalledTimes(1)
    expect(mocks.update).toHaveBeenCalledWith('task_budget', {
      expected_revision: 42,
      values: { ...group.values, standard_root_business_models: 200 }
    })
    expect(wrapper.text()).toContain('修订 43')
  })

  it('切换组后旧历史迟到不污染当前组，回滚确认期间锁定组', async () => {
    const groups = [
      { key: 'A', label: '设置A', revision: 10, fields: [], values: {} },
      {
        key: 'B',
        label: '设置B',
        revision: 12,
        fields: [{ key: 'content', type: 'string', label: '内容' }],
        values: { content: 'old' }
      }
    ]
    mocks.list.mockResolvedValue(groups)
    mocks.rollback.mockResolvedValue({
      ...groups[1],
      revision: 13,
      values: { content: 'restored' }
    })
    mocks.update.mockImplementation(async (_key, request) => ({
      ...groups[1],
      revision: 14,
      values: request.values
    }))
    let finishA!: (value: unknown) => void
    mocks.history.mockImplementation((key: string) =>
      key === 'A'
        ? new Promise((resolve) => {
            finishA = resolve
          })
        : Promise.resolve([
            {
              revision: 9,
              created_at: '2026-10-11T12:00:00',
              contract_status: 'SUPPORTED',
              values: { origin: 'B' }
            }
          ])
    )
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/', component: SystemSettingsPage }]
    })
    await router.push('/')
    await router.isReady()
    wrapper = mount(SystemSettingsPage, {
      attachTo: document.body,
      global: {
        plugins: [router],
        directives: {
          permission: createPermissionDirective(() => computed(() => currentAuth.user.permissions))
        }
      }
    })
    await flushPromises()
    const historyButtons = wrapper.findAll('button').filter((b) => b.text() === '历史版本')
    await historyButtons[0]!.trigger('click')
    await flushPromises()
    await historyButtons[1]!.trigger('click')
    await flushPromises()
    finishA([
      {
        revision: 3,
        created_at: '2026-10-10T12:00:00',
        contract_status: 'SUPPORTED',
        values: { origin: 'A' }
      }
    ])
    await flushPromises()
    expect(document.body.textContent).toContain('修订 9')
    expect(document.body.textContent).not.toContain('修订 3')
    expect(mocks.history.mock.calls[0]?.[1].signal.aborted).toBe(true)
    let confirm!: (value: Awaited<ReturnType<typeof ElMessageBox.confirm>>) => void
    vi.spyOn(ElMessageBox, 'confirm').mockImplementation(
      () =>
        new Promise((resolve) => {
          confirm = resolve
        })
    )
    const restore = Array.from(document.body.querySelectorAll('button')).find((b) =>
      b.textContent?.includes('恢复此版本')
    )!
    restore.click()
    await flushPromises()
    expect(vi.mocked(ElMessageBox.confirm).mock.lastCall?.[0]).toContain(
      '将“设置B”当前配置替换为修订 9 的历史配置并保存'
    )
    expect(vi.mocked(ElMessageBox.confirm).mock.lastCall?.[0]).toContain('其他分组不受影响')
    expect(historyButtons[0]!.attributes('disabled')).toBeDefined()
    await historyButtons[0]!.trigger('click')
    confirm('confirm' as Awaited<ReturnType<typeof ElMessageBox.confirm>>)
    await flushPromises()
    expect(mocks.rollback).toHaveBeenCalledWith('B', { expected_revision: 12, target_revision: 9 })
    expect(mocks.list).toHaveBeenCalledTimes(1)
    await wrapper.findAll('form')[1]!.getComponent(ElInput).get('input').setValue('new-input')
    await wrapper.get('input[aria-label="查找系统参数"]').setValue('设置A')
    expect(wrapper.findAll('form')).toHaveLength(2)
    expect(wrapper.text()).toContain('有未保存的参数')
    await wrapper.findAll('form')[1]!.trigger('submit')
    await flushPromises()
    expect(mocks.update).toHaveBeenCalledWith('B', {
      expected_revision: 13,
      values: { content: 'new-input' }
    })
  })
})

async function renderGroupHistory() {
  const groups = ['A', 'B'].map((key, index) => ({
    key,
    label: `设置${key}`,
    revision: index + 10,
    fields: [{ key: 'content', type: 'string', label: '内容' }],
    values: { content: `${key}原值` }
  }))
  mocks.list.mockResolvedValue(structuredClone(groups))
  mocks.history.mockResolvedValue([
    {
      revision: 3,
      created_at: '2026-10-11T12:00:00Z',
      contract_status: 'SUPPORTED',
      values: { content: 'B历史值' }
    }
  ])
  mocks.rollback.mockResolvedValue({ ...groups[1], revision: 12, values: { content: 'B历史值' } })
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/', component: SystemSettingsPage }]
  })
  await router.push('/')
  await router.isReady()
  wrapper = mount(SystemSettingsPage, {
    attachTo: document.body,
    global: {
      plugins: [router],
      directives: {
        permission: createPermissionDirective(() => computed(() => currentAuth.user.permissions))
      }
    }
  })
  await flushPromises()
}
async function openGroupHistory(index: number) {
  await wrapper
    .findAll('button')
    .filter((button) => button.text() === '历史版本')
    [index]!.trigger('click')
  await flushPromises()
}
function historyRestoreButton() {
  return Array.from(document.body.querySelectorAll<HTMLButtonElement>('button')).find((button) =>
    button.textContent?.includes('恢复此版本')
  )!
}
it('A未保存不阻断恢复B，成功后A草稿和全局离开保护保留且不写A', async () => {
  await renderGroupHistory()
  await wrapper.findAll('form')[0]!.getComponent(ElInput).get('input').setValue('A未保存草稿')
  await openGroupHistory(1)
  expect(historyRestoreButton().disabled).toBe(false)
  vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue(
    'confirm' as Awaited<ReturnType<typeof ElMessageBox.confirm>>
  )
  historyRestoreButton().click()
  await flushPromises()
  expect(mocks.rollback).toHaveBeenCalledExactlyOnceWith('B', {
    expected_revision: 11,
    target_revision: 3
  })
  expect(mocks.update).not.toHaveBeenCalled()
  expect(wrapper.findAll('form')[0]!.getComponent(ElInput).props('modelValue')).toBe('A未保存草稿')
  expect(wrapper.findAll('form')[1]!.getComponent(ElInput).props('modelValue')).toBe('B历史值')
  expect(wrapper.text()).toContain('有未保存的参数')
  const leave = new Event('beforeunload', { cancelable: true })
  window.dispatchEvent(leave)
  expect(leave.defaultPrevented).toBe(true)
})

it.each(['confirm', 'cancel', 'failure'])(
  'B未保存时%s恢复历史版本，保留其他组输入',
  async (outcome) => {
    await renderGroupHistory()
    await wrapper.findAll('form')[0]!.getComponent(ElInput).get('input').setValue('A未保存草稿')
    await wrapper.findAll('form')[1]!.getComponent(ElInput).get('input').setValue('B未保存草稿')
    await openGroupHistory(1)
    expect(historyRestoreButton().disabled).toBe(false)
    expect(document.body.textContent).toContain('恢复历史版本将丢弃本组未保存修改')
    const confirm = vi.spyOn(ElMessageBox, 'confirm')
    if (outcome === 'cancel') confirm.mockRejectedValue('cancel')
    else confirm.mockResolvedValue('confirm' as Awaited<ReturnType<typeof ElMessageBox.confirm>>)
    if (outcome === 'failure') mocks.rollback.mockRejectedValueOnce(Error('B修订冲突'))
    historyRestoreButton().click()
    await flushPromises()
    expect(String(confirm.mock.calls[0]?.[0])).toContain('本组未保存修改将被丢弃')
    expect(mocks.rollback).toHaveBeenCalledTimes(outcome === 'cancel' ? 0 : 1)
    expect(mocks.update).not.toHaveBeenCalled()
    expect(wrapper.findAll('form')[0]!.getComponent(ElInput).props('modelValue')).toBe(
      'A未保存草稿'
    )
    expect(wrapper.findAll('form')[1]!.getComponent(ElInput).props('modelValue')).toBe(
      outcome === 'confirm' ? 'B历史值' : 'B未保存草稿'
    )
  }
)
it.each(['cancel', 'failure'])('A脏B净恢复B的%s保持A与B原投影，不写A', async (outcome) => {
  await renderGroupHistory()
  await wrapper.findAll('form')[0]!.getComponent(ElInput).get('input').setValue('A未保存草稿')
  await openGroupHistory(1)
  const confirm = vi.spyOn(ElMessageBox, 'confirm')
  if (outcome === 'cancel') confirm.mockRejectedValue('cancel')
  else {
    confirm.mockResolvedValue('confirm' as Awaited<ReturnType<typeof ElMessageBox.confirm>>)
    mocks.rollback.mockRejectedValueOnce(Error('B修订冲突'))
  }
  historyRestoreButton().click()
  await flushPromises()
  expect(mocks.rollback).toHaveBeenCalledTimes(outcome === 'cancel' ? 0 : 1)
  expect(mocks.update).not.toHaveBeenCalled()
  expect(wrapper.findAll('form')[0]!.getComponent(ElInput).props('modelValue')).toBe('A未保存草稿')
  expect(wrapper.findAll('form')[1]!.getComponent(ElInput).props('modelValue')).toBe('B原值')
  expect(wrapper.text()).toContain('有未保存的参数')
  expect(historyRestoreButton()).toBeDefined()
})

describe('系统参数与只读上下文策略独立读取', () => {
  it('策略失败仍展示参数，单独重试策略保留两组草稿及搜索条件', async () => {
    mocks.contextPolicy.mockRejectedValueOnce(Error('上下文策略暂不可用'))
    await renderGroupHistory()
    expect(wrapper.findAll('form')).toHaveLength(2)
    const first = wrapper.findAll('form')[0]!.getComponent(ElInput)
    const second = wrapper.findAll('form')[1]!.getComponent(ElInput)
    await first.get('input').setValue('A未保存草稿')
    await second.get('input').setValue('B未保存草稿')
    await wrapper.get('input[aria-label="查找系统参数"]').setValue('设置A')
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '重新读取上下文策略')!
      .trigger('click')
    await flushPromises()
    expect(mocks.list).toHaveBeenCalledOnce()
    expect(mocks.contextPolicy).toHaveBeenCalledTimes(2)
    expect(first.props('modelValue')).toBe('A未保存草稿')
    expect(second.props('modelValue')).toBe('B未保存草稿')
    expect(
      (wrapper.get('input[aria-label="查找系统参数"]').element as HTMLInputElement).value
    ).toBe('设置A')
    expect(wrapper.text()).toContain('上下文策略 context-policy.v2')
    expect(wrapper.text()).toContain('有未保存的参数')
    expect(wrapper.findAll('button').some((button) => button.text() === '重新读取系统参数')).toBe(
      false
    )
    expect(mocks.update).not.toHaveBeenCalled()
    expect(mocks.rollback).not.toHaveBeenCalled()
  })

  it('初始参数失败仍展示已成功策略，重读只请求参数且不显示假空态', async () => {
    mocks.list.mockRejectedValueOnce(Error('系统参数暂不可用'))
    await renderGroupHistory()
    expect(wrapper.text()).toContain('上下文策略 context-policy.v2')
    expect(wrapper.text()).not.toContain('暂无系统参数')
    expect(wrapper.findAll('form')).toHaveLength(0)
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '重新读取系统参数')!
      .trigger('click')
    await flushPromises()
    expect(wrapper.findAll('form')).toHaveLength(2)
    expect(mocks.list).toHaveBeenCalledTimes(2)
    expect(mocks.contextPolicy).toHaveBeenCalledOnce()
  })

  it('两项初始失败聚合一次顶部反馈，先恢复参数再重试策略不清新草稿', async () => {
    mocks.list.mockRejectedValueOnce(Error('系统参数暂不可用'))
    mocks.contextPolicy.mockRejectedValueOnce(Error('上下文策略暂不可用'))
    await renderGroupHistory()
    expect(document.querySelectorAll('.el-message--error')).toHaveLength(1)
    expect(document.querySelector('.el-message--error')?.textContent).toContain('系统参数暂不可用')
    expect(document.querySelector('.el-message--error')?.textContent).toContain(
      '上下文策略暂不可用'
    )
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '重新读取系统参数')!
      .trigger('click')
    await flushPromises()
    const input = wrapper.findAll('form')[0]!.getComponent(ElInput)
    await input.get('input').setValue('恢复后的新草稿')
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '重新读取上下文策略')!
      .trigger('click')
    await flushPromises()
    expect(input.props('modelValue')).toBe('恢复后的新草稿')
    expect(wrapper.text()).toContain('有未保存的参数')
    expect(mocks.list).toHaveBeenCalledTimes(2)
    expect(mocks.contextPolicy).toHaveBeenCalledTimes(2)
  })
})
