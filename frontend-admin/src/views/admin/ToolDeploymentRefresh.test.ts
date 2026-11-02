import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { ref } from 'vue'
import { createPermissionDirective } from '@skillforge/shared/directives/permission'
import { ElButton, ElDrawer, ElInput, ElSelect } from 'element-plus'
import { EntitySelect } from '@skillforge/shared/admin'
import { HttpClientError } from '@skillforge/shared/utils/http'
import ToolDeploymentsPage from './ToolDeploymentsPage.vue'

const calls = vi.hoisted(() => ({
  deployments: vi.fn(),
  version: vi.fn(),
  startDeployment: vi.fn(),
  stopDeployment: vi.fn(),
  definitions: vi.fn(),
  platformTools: vi.fn(),
  instances: vi.fn(),
  createDeployment: vi.fn(),
  getDeployment: vi.fn(),
  success: vi.fn(),
  warning: vi.fn(),
  error: vi.fn()
}))
vi.mock('@skillforge/shared/feedback', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@skillforge/shared/feedback')>()),
  message: { success: calls.success, warning: calls.warning, error: calls.error }
}))
vi.mock('../../api', () => ({
  api: {
    capabilities: {
      deployments: calls.deployments,
      getVersion: calls.version,
      startDeployment: calls.startDeployment,
      stopDeployment: calls.stopDeployment,
      definitions: calls.definitions,
      platformTools: calls.platformTools,
      createDeployment: calls.createDeployment,
      getDeployment: calls.getDeployment
    },
    connectors: { instances: calls.instances }
  }
}))
vi.mock('vue-router', () => ({ onBeforeRouteLeave: vi.fn(), onBeforeRouteUpdate: vi.fn() }))
let wrapper: VueWrapper | undefined
const deployment = (key: string, version: string) => ({
  deploymentKey: key,
  toolVersionId: version,
  providerType: 'MCP',
  providerRef: 'server',
  environment: 'DEVELOPMENT',
  status: 'ACTIVE',
  revision: 1
})
beforeEach(() => {
  vi.resetAllMocks()
  calls.deployments.mockResolvedValue([deployment('original', '1')])
  calls.version.mockImplementation(async (id: string) => ({
    id,
    name: id === '1' ? '已有工具' : '新工具',
    versionKey: 'v1'
  }))
  calls.definitions.mockResolvedValue([{ defaultVersionId: '2' }])
  calls.platformTools.mockResolvedValue([])
  calls.instances.mockResolvedValue({
    items: [{ instance_id: 'server', name: '客户系统', executor_type: 'mcp' }]
  })
  calls.createDeployment.mockResolvedValue(deployment('new-one', '2'))
})
afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  document.body.innerHTML = ''
})
async function refresh() {
  await wrapper!
    .findAllComponents(ElButton)
    .find((button) => button.text() === '刷新')!
    .trigger('click')
  await flushPromises()
}

it('refreshes tool names with new deployments so they can be found without reopening the page', async () => {
  wrapper = mount(ToolDeploymentsPage, { attachTo: document.body })
  await flushPromises()
  calls.deployments.mockResolvedValue([
    deployment('original', '1'),
    deployment('new-one', '2'),
    deployment('new-two', '2')
  ])
  await refresh()
  expect(calls.version.mock.calls.map(([id]) => id)).toEqual(['1', '1', '2'])
  expect(wrapper.text()).toContain('新工具 / v1')
  await wrapper.findComponent(ElInput).setValue('新工具')
  expect(wrapper.text()).toContain('new-one')
  expect(wrapper.text()).toContain('new-two')
  expect(wrapper.text()).toContain('2 项部署')
  expect(wrapper.text()).not.toContain('original')
})

it('keeps existing tool names after a failed list refresh without reading stale version metadata', async () => {
  wrapper = mount(ToolDeploymentsPage, { attachTo: document.body })
  await flushPromises()
  calls.deployments.mockRejectedValueOnce(new Error('部署目录暂不可用'))
  await refresh()
  expect(calls.version).toHaveBeenCalledTimes(1)
  expect(wrapper.text()).toContain('已有工具 / v1')
  expect(wrapper.text()).toContain('original')
})

it('preserves loaded names after metadata failure and recovers through the same refresh action', async () => {
  wrapper = mount(ToolDeploymentsPage, { attachTo: document.body })
  await flushPromises()
  calls.deployments.mockResolvedValue([deployment('original', '1'), deployment('new-one', '2')])
  calls.version.mockRejectedValueOnce(new Error('版本信息暂不可用'))
  await refresh()
  expect(wrapper.text()).toContain('已有工具 / v1')
  expect(wrapper.text()).toContain('new-one')
  await refresh()
  expect(wrapper.text()).toContain('新工具 / v1')
})

const permissions = ref<string[]>([])
function mountWithPermissions(codes: string[]) {
  permissions.value = codes
  wrapper = mount(ToolDeploymentsPage, {
    attachTo: document.body,
    global: { directives: { permission: createPermissionDirective(() => permissions) } }
  })
}
function action(label: string) {
  return wrapper!
    .findAllComponents(ElButton)
    .find((button) => button.text() === label && button.element.isConnected)
}
it.each([
  ['foundation.capabilities.run', 'ACTIVE', false],
  ['foundation.capabilities.cancel', 'ACTIVE', true],
  ['foundation.capabilities.update', 'ACTIVE', false],
  ['foundation.capabilities.run', 'INACTIVE', true],
  ['foundation.capabilities.cancel', 'INACTIVE', false],
  ['foundation.capabilities.update', 'INACTIVE', false]
])('启停操作按后端权限显示：%s %s', async (permission, status, visible) => {
  calls.deployments.mockResolvedValue([{ ...deployment('original', '1'), status }])
  mountWithPermissions([String(permission)])
  await flushPromises()
  expect(Boolean(action(status === 'ACTIVE' ? '停用' : '启用'))).toBe(visible)
})
it('停用成功后刷新失败保留正式状态和修订，只在明确点击后提交启用', async () => {
  mountWithPermissions(['foundation.capabilities.run', 'foundation.capabilities.cancel'])
  await flushPromises()
  calls.stopDeployment.mockResolvedValue({
    ...deployment('original', '1'),
    status: 'INACTIVE',
    revision: 2
  })
  calls.deployments.mockRejectedValueOnce(new Error('部署目录暂不可用'))
  await action('停用')!.trigger('click')
  await flushPromises()
  expect(calls.stopDeployment).toHaveBeenCalledExactlyOnceWith('original', 1)
  expect(action('停用')).toBeUndefined()
  expect(action('启用')).toBeDefined()
  expect(calls.warning).toHaveBeenCalledWith(
    '工具部署已停止，新调用不可使用；列表刷新失败。请点击“刷新”，不必重复操作。'
  )
  calls.startDeployment.mockResolvedValue({ ...deployment('original', '1'), revision: 3 })
  calls.deployments.mockResolvedValue([{ ...deployment('original', '1'), revision: 3 }])
  await action('启用')!.trigger('click')
  await flushPromises()
  expect(calls.startDeployment).toHaveBeenCalledExactlyOnceWith('original', 2)
  expect(calls.stopDeployment).toHaveBeenCalledTimes(1)
})

async function prepareCreate() {
  await action('新增部署')!.trigger('click')
  await flushPromises()
  const drawer = wrapper!.findComponent(ElDrawer)
  await drawer.findComponent(ElInput).find('input').setValue('new-one')
  drawer.findAllComponents(ElSelect)[0]!.vm.$emit('update:modelValue', '2')
  drawer.findComponent(EntitySelect).vm.$emit('update:modelValue', 'server')
  await flushPromises()
}

it('创建成功并刷新后展示新部署及一次成功消息', async () => {
  mountWithPermissions(['foundation.capabilities.create'])
  await flushPromises()
  await prepareCreate()
  calls.deployments.mockResolvedValue([deployment('original', '1'), deployment('new-one', '2')])
  await action('创建')!.trigger('click')
  await flushPromises()
  expect(wrapper!.text()).toContain('new-one')
  expect(calls.createDeployment).toHaveBeenCalledTimes(1)
  expect(calls.success).toHaveBeenCalledExactlyOnceWith('工具部署已创建')
  expect(calls.warning).not.toHaveBeenCalled()
})

it('创建成功后刷新失败保留回执和筛选，手动刷新只读取', async () => {
  mountWithPermissions(['foundation.capabilities.create'])
  await flushPromises()
  await wrapper!.findComponent(ElInput).find('input').setValue('new-one')
  await prepareCreate()
  calls.deployments.mockRejectedValueOnce(new Error('目录不可用'))
  await action('创建')!.trigger('click')
  await flushPromises()
  expect(calls.createDeployment).toHaveBeenCalledTimes(1)
  expect(wrapper!.text()).toContain('new-one')
  expect(wrapper!.text()).not.toContain('original')
  expect(calls.warning).toHaveBeenCalledExactlyOnceWith(
    '工具部署已创建；列表刷新失败。请点击“刷新”，不必重复操作。'
  )
  expect(calls.success).not.toHaveBeenCalled()
  calls.deployments.mockResolvedValue([deployment('original', '1'), deployment('new-one', '2')])
  await refresh()
  expect(wrapper!.text()).toContain('new-one')
  expect(wrapper!.text()).not.toContain('original')
  expect(calls.createDeployment).toHaveBeenCalledTimes(1)
})

it('创建回执使旧目录请求失效，迟到结果不能覆盖新部署', async () => {
  let resolveOld!: (items: ReturnType<typeof deployment>[]) => void
  calls.deployments.mockReturnValueOnce(
    new Promise((resolve) => {
      resolveOld = resolve
    })
  )
  mountWithPermissions(['foundation.capabilities.create'])
  await prepareCreate()
  calls.deployments.mockRejectedValueOnce(new Error('目录不可用'))
  await action('创建')!.trigger('click')
  await flushPromises()
  resolveOld([deployment('original', '1')])
  await flushPromises()
  expect(wrapper!.text()).toContain('new-one')
  expect(wrapper!.text()).not.toContain('original')
})

it.each([
  new Error('连接中断'),
  new HttpClientError({ status: 409, code: 'UNPROVEN_CONFLICT', userMessage: '冲突' })
])('创建结果未知时冻结输入，只读核对不能解除未知或重复提交：%s', async (failure) => {
  mountWithPermissions(['foundation.capabilities.create'])
  await flushPromises()
  await prepareCreate()
  calls.createDeployment.mockRejectedValueOnce(failure)
  await action('创建')!.trigger('click')
  await flushPromises()
  expect(wrapper!.findComponent(ElDrawer).findComponent(ElInput).find('input').element.value).toBe(
    'new-one'
  )
  expect(wrapper!.text()).toContain('original')
  expect(calls.warning).toHaveBeenCalledExactlyOnceWith(
    '工具部署创建结果尚未确认，请只读核对原标识，不要重复创建。'
  )
  expect(calls.success).not.toHaveBeenCalled()
  expect(calls.deployments).toHaveBeenCalledTimes(1)
  expect(wrapper!.findComponent(ElDrawer).findComponent(EntitySelect).props('disabled')).toBe(true)
  expect(action('创建')!.props('disabled')).toBe(true)
  await action('取消')!.trigger('click')
  await flushPromises()
  calls.getDeployment.mockResolvedValue(deployment('new-one', '2'))
  await action('查看当前部署')!.trigger('click')
  await flushPromises()
  expect(calls.getDeployment).toHaveBeenCalledExactlyOnceWith('new-one', { silent: true })
  expect(wrapper!.text()).toContain('当前部署：new-one')
  expect(action('新增部署')!.props('disabled')).toBe(true)
  expect(calls.createDeployment).toHaveBeenCalledTimes(1)
  calls.getDeployment.mockRejectedValueOnce(new Error('未查到部署'))
  await action('查看当前部署')!.trigger('click')
  await flushPromises()
  expect(calls.error).toHaveBeenCalledWith('未查到部署')
  expect(action('新增部署')!.props('disabled')).toBe(true)
  expect(calls.createDeployment).toHaveBeenCalledTimes(1)
})

it('首次读取失败提供刷新，不伪装成空目录或零项', async () => {
  calls.deployments.mockRejectedValueOnce(new Error('目录不可用'))
  mountWithPermissions([])
  await flushPromises()
  expect(wrapper!.text()).toContain('部署列表读取失败，请点击刷新重试')
  expect(wrapper!.text()).not.toContain('尚无部署')
  expect(wrapper!.text()).not.toContain('0 项部署')
  await refresh()
  expect(wrapper!.text()).toContain('original')
})

it('初次读取和创建后刷新均失败时保留回执并标明目录不完整', async () => {
  calls.deployments.mockRejectedValueOnce(new Error('初次读取失败'))
  mountWithPermissions(['foundation.capabilities.create'])
  await flushPromises()
  await prepareCreate()
  calls.deployments.mockRejectedValueOnce(new Error('刷新失败'))
  await action('创建')!.trigger('click')
  await flushPromises()
  expect(wrapper!.text()).toContain('new-one')
  expect(wrapper!.text()).toContain('部署目录尚未完整读取')
  expect(wrapper!.text()).not.toContain('1 项部署')
  calls.deployments.mockResolvedValue([deployment('original', '1'), deployment('new-one', '2')])
  await refresh()
  expect(wrapper!.text()).toContain('2 项部署')
  expect(calls.createDeployment).toHaveBeenCalledTimes(1)
})

it('明确业务拒绝保留输入且允许修改，不标记未知', async () => {
  mountWithPermissions(['foundation.capabilities.create'])
  await flushPromises()
  await prepareCreate()
  calls.createDeployment.mockRejectedValueOnce(
    new HttpClientError({
      status: 422,
      code: 'CAPABILITY_VERSION_NOT_PUBLISHED',
      userMessage: '工具版本未发布'
    })
  )
  await action('创建')!.trigger('click')
  await flushPromises()
  expect(calls.error).toHaveBeenCalledExactlyOnceWith('工具版本未发布')
  expect(wrapper!.findComponent(ElDrawer).findComponent(ElInput).find('input').element.value).toBe(
    'new-one'
  )
  expect(action('创建')!.props('disabled')).toBe(false)
  expect(wrapper!.text()).not.toContain('创建结果尚未确认')
  expect(calls.deployments).toHaveBeenCalledTimes(1)
})

it('部署标识重复是明确拒绝，可修改后重新创建', async () => {
  mountWithPermissions(['foundation.capabilities.create'])
  await flushPromises()
  await prepareCreate()
  calls.createDeployment.mockRejectedValueOnce(
    new HttpClientError({ status: 409, code: 'CAPABILITY_DUPLICATE', userMessage: '冲突' })
  )
  await action('创建')!.trigger('click')
  await flushPromises()
  expect(calls.error).toHaveBeenCalledExactlyOnceWith('部署标识已存在，请更换标识后创建。')
  expect(action('创建')!.props('disabled')).toBe(false)
  expect(wrapper!.text()).not.toContain('创建结果尚未确认')
  await wrapper!.findComponent(ElDrawer).findComponent(ElInput).find('input').setValue('another')
  await action('创建')!.trigger('click')
  await flushPromises()
  expect(calls.createDeployment).toHaveBeenCalledTimes(2)
  expect(calls.createDeployment).toHaveBeenLastCalledWith(
    expect.objectContaining({ deploymentKey: 'another' })
  )
})

it('部署标识 129 字符在入口拒绝，127 字符允许提交', async () => {
  mountWithPermissions(['foundation.capabilities.create'])
  await flushPromises()
  await prepareCreate()
  const input = wrapper!.findComponent(ElDrawer).findComponent(ElInput).find('input')
  await input.setValue('a'.repeat(129))
  await action('创建')!.trigger('click')
  await flushPromises()
  expect(calls.createDeployment).not.toHaveBeenCalled()
  await vi.waitFor(() => expect(document.body.textContent).toContain('部署标识不能超过 128 个字符'))
  await input.setValue('a'.repeat(127))
  await action('创建')!.trigger('click')
  await flushPromises()
  expect(calls.createDeployment).toHaveBeenCalledExactlyOnceWith(
    expect.objectContaining({ deploymentKey: 'a'.repeat(127) })
  )
})

it('创建请求在途时提供方引用不可编辑', async () => {
  mountWithPermissions(['foundation.capabilities.create'])
  await flushPromises()
  await prepareCreate()
  let resolveCreate!: (item: ReturnType<typeof deployment>) => void
  calls.createDeployment.mockReturnValueOnce(
    new Promise((resolve) => {
      resolveCreate = resolve
    })
  )
  await action('创建')!.trigger('click')
  await flushPromises()
  expect(wrapper!.findComponent(ElDrawer).findComponent(EntitySelect).props('disabled')).toBe(true)
  resolveCreate(deployment('new-one', '2'))
  await flushPromises()
})

it('离开页面后迟到创建回执不刷新或显示反馈', async () => {
  mountWithPermissions(['foundation.capabilities.create'])
  await flushPromises()
  await prepareCreate()
  let resolveCreate!: (item: ReturnType<typeof deployment>) => void
  calls.createDeployment.mockReturnValueOnce(
    new Promise((resolve) => {
      resolveCreate = resolve
    })
  )
  await action('创建')!.trigger('click')
  await flushPromises()
  wrapper!.unmount()
  wrapper = undefined
  resolveCreate(deployment('new-one', '2'))
  await flushPromises()
  expect(calls.deployments).toHaveBeenCalledTimes(1)
  expect(calls.success).not.toHaveBeenCalled()
  expect(calls.warning).not.toHaveBeenCalled()
})
it('状态切换后重新计算权限，新增授权可恢复相应操作', async () => {
  mountWithPermissions(['foundation.capabilities.cancel'])
  await flushPromises()
  calls.stopDeployment.mockResolvedValue({
    ...deployment('original', '1'),
    status: 'INACTIVE',
    revision: 2
  })
  calls.deployments.mockRejectedValueOnce(new Error('读取失败'))
  await action('停用')!.trigger('click')
  await flushPromises()
  expect(action('启用')).toBeUndefined()
  permissions.value = ['foundation.capabilities.run']
  await flushPromises()
  expect(action('启用')).toBeDefined()
})
it('启停写入失败保留旧状态，不自动重发或读取目录', async () => {
  mountWithPermissions(['foundation.capabilities.cancel'])
  await flushPromises()
  calls.stopDeployment.mockRejectedValue(new Error('写入未确认'))
  await action('停用')!.trigger('click')
  await flushPromises()
  expect(action('停用')).toBeDefined()
  expect(action('启用')).toBeUndefined()
  expect(calls.stopDeployment).toHaveBeenCalledExactlyOnceWith('original', 1)
  expect(calls.deployments).toHaveBeenCalledTimes(1)
})
