import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { reactive, ref } from 'vue'
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
  setDeploymentActiveWithReceipt: vi.fn(),
  deploymentStateReceipt: vi.fn(),
  definitions: vi.fn(),
  platformTools: vi.fn(),
  instances: vi.fn(),
  createDeployment: vi.fn(),
  deploymentCreateReceipt: vi.fn(),
  getDeployment: vi.fn(),
  success: vi.fn(),
  warning: vi.fn(),
  error: vi.fn()
}))
const browser = vi.hoisted(() => ({ session: 'test-session' }))
vi.mock('@skillforge/shared/feedback', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@skillforge/shared/feedback')>()),
  message: { success: calls.success, warning: calls.warning, error: calls.error }
}))
vi.mock('../../api', () => ({
  getSessionId: () => browser.session,
  api: {
    capabilities: {
      deployments: calls.deployments,
      getVersion: calls.version,
      startDeployment: calls.startDeployment,
      stopDeployment: calls.stopDeployment,
      setDeploymentActiveWithReceipt: calls.setDeploymentActiveWithReceipt,
      deploymentStateReceipt: calls.deploymentStateReceipt,
      definitions: calls.definitions,
      platformTools: calls.platformTools,
      createDeploymentWithReceipt: calls.createDeployment,
      deploymentCreateReceipt: calls.deploymentCreateReceipt,
      getDeployment: calls.getDeployment
    },
    connectors: { instances: calls.instances }
  }
}))
const authState = reactive({
  user: { tenant_id: '7', sub: '8', permissions: ['foundation.capabilities.read'] },
  identityVersion: 1
})
vi.mock('../../stores/auth', () => ({ useAuthStore: () => authState }))
vi.mock('vue-router', () => ({ onBeforeRouteLeave: vi.fn(), onBeforeRouteUpdate: vi.fn() }))
let wrapper: VueWrapper | undefined
const deployment = (key: string, version: string) => ({
  deploymentKey: key,
  toolVersionId: version,
  toolName: version === '1' ? '已有工具' : '新工具',
  toolVersionKey: 'v1',
  providerType: 'MCP',
  providerRef: 'server',
  environment: 'DEVELOPMENT',
  status: 'ACTIVE',
  revision: 1
})
beforeEach(() => {
  vi.resetAllMocks()
  sessionStorage.clear()
  localStorage.clear()
  browser.session = 'test-session'
  authState.user = { tenant_id: '7', sub: '8', permissions: ['foundation.capabilities.read'] }
  authState.identityVersion = 1
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
  calls.createDeployment.mockImplementation(async (requestId: string) => ({
    status: 'SUCCEEDED',
    request_id: requestId,
    deployment_id: '123',
    deployment_key: 'new-one'
  }))
  calls.deploymentCreateReceipt.mockResolvedValue(undefined)
  calls.deploymentStateReceipt.mockResolvedValue(undefined)
  calls.setDeploymentActiveWithReceipt.mockImplementation(
    async (requestId: string, key: string, revision: number, active: boolean) => {
      const result = await (active ? calls.startDeployment : calls.stopDeployment)(key, revision)
      return {
        request_id: requestId,
        status: 'SUCCEEDED',
        deployment_key: key,
        deployment_status: active ? 'ACTIVE' : 'DISABLED',
        revision: result.revision,
        error_code: null,
        error_message: null
      }
    }
  )
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

it('部署列表直接返回名称，刷新后可按完整标签筛选而不补读版本', async () => {
  wrapper = mount(ToolDeploymentsPage, { attachTo: document.body })
  await flushPromises()
  calls.deployments.mockResolvedValue([
    deployment('original', '1'),
    deployment('new-one', '2'),
    deployment('new-two', '2')
  ])
  await refresh()
  expect(calls.version).not.toHaveBeenCalled()
  expect(wrapper.text()).toContain('新工具 / v1')
  await wrapper.findComponent(ElInput).setValue('新工具 / v1')
  expect(wrapper.text()).toContain('new-one')
  expect(wrapper.text()).toContain('new-two')
  expect(wrapper.text()).toContain('2 项部署')
  expect(wrapper.text()).not.toContain('original')
})

it('刷新失败保留上次部署与名称', async () => {
  wrapper = mount(ToolDeploymentsPage, { attachTo: document.body })
  await flushPromises()
  calls.deployments.mockRejectedValueOnce(new Error('部署目录暂不可用'))
  await refresh()
  expect(calls.version).not.toHaveBeenCalled()
  expect(wrapper.text()).toContain('已有工具 / v1')
  expect(wrapper.text()).toContain('original')
})

it('不可见版本保留部署行且不影响其他部署名称筛选', async () => {
  wrapper = mount(ToolDeploymentsPage, { attachTo: document.body })
  await flushPromises()
  calls.deployments.mockResolvedValue([
    deployment('original', '1'),
    { ...deployment('hidden-version', '2'), toolName: null, toolVersionKey: null }
  ])
  await refresh()
  expect(wrapper.text()).toContain('已有工具 / v1')
  expect(wrapper.text()).toContain('hidden-version')
  expect(wrapper.text()).toContain('版本信息不可用')
  await wrapper.findComponent(ElInput).setValue('已有工具 / v1')
  expect(wrapper.text()).toContain('original')
  expect(wrapper.text()).not.toContain('hidden-version')
  expect(calls.version).not.toHaveBeenCalled()
})

const permissions = ref<string[]>([])
function mountWithPermissions(codes: string[]) {
  permissions.value = codes
  authState.user.permissions = [
    ...new Set([...codes, 'foundation.capabilities.read', 'foundation.connectors.read'])
  ]
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
    '原停用请求已确认（修订 2）；当前状态以列表为准；列表刷新失败。请点击“刷新”，不必重复操作。'
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
  drawer.findAllComponents(ElSelect)[0]!.vm.$emit('update:modelValue', '2')
  drawer.findComponent(EntitySelect).vm.$emit('update:modelValue', 'server')
  await flushPromises()
}

it('断连后关闭页面并以新会话重开，只读原回执且不重复创建', async () => {
  mountWithPermissions(['foundation.capabilities.create'])
  await flushPromises()
  await prepareCreate()
  calls.createDeployment.mockRejectedValueOnce(new Error('连接中断'))
  await action('创建')!.trigger('click')
  await flushPromises()
  const requestId = calls.createDeployment.mock.calls[0]![0] as string
  expect(calls.deploymentCreateReceipt).toHaveBeenCalledExactlyOnceWith(requestId)
  expect(wrapper!.text()).toContain('创建结果尚未确认')
  wrapper!.unmount()
  browser.session = 'new-session'
  calls.deploymentCreateReceipt.mockResolvedValue({
    status: 'SUCCEEDED',
    request_id: requestId,
    deployment_id: '123',
    deployment_key: 'new-one'
  })
  mountWithPermissions(['foundation.capabilities.create'])
  await flushPromises()
  expect(calls.deploymentCreateReceipt).toHaveBeenCalledTimes(2)
  expect(wrapper!.text()).toContain('工具部署已创建：new-one')
  expect(action('新增部署')!.props('disabled')).toBe(false)
  expect(calls.createDeployment).toHaveBeenCalledTimes(1)
  expect(
    localStorage.getItem(`skillforge.tool-deployment-create:["7","8"]:${requestId}`)
  ).toBeNull()
})

it('不匹配的原回执和身份变化的迟到响应都不能解锁', async () => {
  mountWithPermissions(['foundation.capabilities.create'])
  await flushPromises()
  await prepareCreate()
  calls.createDeployment.mockRejectedValueOnce(new Error('连接中断'))
  let complete!: (receipt: unknown) => void
  calls.deploymentCreateReceipt.mockReturnValueOnce(
    new Promise((resolve) => {
      complete = resolve
    })
  )
  await action('创建')!.trigger('click')
  await flushPromises()
  const requestId = calls.createDeployment.mock.calls[0]![0] as string
  authState.user = { tenant_id: '7', sub: '9', permissions: ['foundation.capabilities.read'] }
  authState.identityVersion++
  complete({
    status: 'SUCCEEDED',
    request_id: requestId,
    deployment_id: '123',
    deployment_key: 'new-one'
  })
  await flushPromises()
  expect(wrapper!.text()).not.toContain('工具部署已创建：new-one')
  expect(
    localStorage.getItem(`skillforge.tool-deployment-create:["7","8"]:${requestId}`)
  ).not.toBeNull()
  authState.user = { tenant_id: '7', sub: '8', permissions: ['foundation.capabilities.read'] }
  await flushPromises()
  calls.deploymentCreateReceipt.mockResolvedValueOnce({
    status: 'SUCCEEDED',
    request_id: '11111111-1111-4111-8111-111111111111',
    deployment_id: '123',
    deployment_key: 'other'
  })
  await action('核对原创建结果')!.trigger('click')
  await flushPromises()
  expect(wrapper!.text()).toContain('创建结果尚未确认')
  expect(calls.createDeployment).toHaveBeenCalledTimes(1)
})

it('不同标签保存的两个原请求分别恢复，确认一个不覆盖另一个', async () => {
  const first = '11111111-1111-4111-8111-111111111111'
  const second = '22222222-2222-4222-8222-222222222222'
  const key = (id: string) => `skillforge.tool-deployment-create:["7","8"]:${id}`
  localStorage.setItem(key(first), JSON.stringify({ requestId: first, deploymentKey: 'one' }))
  mountWithPermissions(['foundation.capabilities.create'])
  await flushPromises()
  const record = JSON.stringify({ requestId: second, deploymentKey: 'two' })
  localStorage.setItem(key(second), record)
  window.dispatchEvent(
    new StorageEvent('storage', { key: key(second), newValue: record, storageArea: localStorage })
  )
  await flushPromises()
  expect(wrapper!.text()).toContain('部署“一”的创建结果尚未确认'.replace('一', 'one'))
  expect(wrapper!.text()).toContain('部署“two”的创建结果尚未确认')
  expect(action('新增部署')!.props('disabled')).toBe(true)
  calls.deploymentCreateReceipt.mockResolvedValueOnce({
    status: 'SUCCEEDED',
    request_id: first,
    deployment_id: '123',
    deployment_key: 'one'
  })
  const cards = wrapper!.findAll('section.admin-card')
  const firstCard = cards.find((card) => card.text().includes(first))!
  await firstCard
    .findAllComponents(ElButton)
    .find((button: { text: () => string }) => button.text() === '核对原创建结果')!
    .trigger('click')
  await flushPromises()
  expect(localStorage.getItem(key(first))).toBeNull()
  expect(localStorage.getItem(key(second))).toBe(record)
  expect(wrapper!.text()).toContain('部署“two”的创建结果尚未确认')
  localStorage.removeItem(key(second))
  window.dispatchEvent(
    new StorageEvent('storage', { key: key(second), oldValue: record, storageArea: localStorage })
  )
  await flushPromises()
  expect(wrapper!.text()).toContain('部署“two”的创建结果尚未确认')
  expect(action('新增部署')!.props('disabled')).toBe(true)
  expect(calls.createDeployment).not.toHaveBeenCalled()
})

it('旧待核对记录仍可按原标识只读查看部署', async () => {
  const requestId = '11111111-1111-4111-8111-111111111111'
  localStorage.setItem(
    `skillforge.tool-deployment-create:["7","8"]:${requestId}`,
    JSON.stringify({ requestId, deploymentKey: 'old-key' })
  )
  calls.getDeployment.mockResolvedValue(deployment('old-key', '2'))
  mountWithPermissions([])
  await flushPromises()
  await action('查看当前部署')!.trigger('click')
  await flushPromises()
  expect(calls.getDeployment).toHaveBeenCalledExactlyOnceWith('old-key', { silent: true })
  expect(wrapper!.text()).toContain('当前部署：old-key')
  expect(action('新增部署')!.props('disabled')).toBe(true)
})

it('切换主体后清除旧目录且丢弃迟到的名称与部署读取', async () => {
  mountWithPermissions([])
  await flushPromises()
  expect(wrapper!.text()).toContain('original')
  let finish!: (rows: ReturnType<typeof deployment>[]) => void
  calls.deployments.mockReturnValueOnce(
    new Promise((resolve) => {
      finish = resolve
    })
  )
  await refresh()
  authState.user = { tenant_id: '7', sub: '9', permissions: ['foundation.capabilities.read'] }
  authState.identityVersion++
  await flushPromises()
  expect(wrapper!.text()).not.toContain('original')
  finish([{ ...deployment('old-row', '1'), toolName: '旧身份工具' }])
  await flushPromises()
  expect(wrapper!.text()).not.toContain('旧身份工具')
  expect(wrapper!.text()).not.toContain('original')
})

it('不同主体仅恢复各自原请求', async () => {
  const first = '11111111-1111-4111-8111-111111111111'
  const second = '22222222-2222-4222-8222-222222222222'
  localStorage.setItem(
    `skillforge.tool-deployment-create:["7","8"]:${first}`,
    JSON.stringify({ requestId: first, deploymentKey: 'first-actor' })
  )
  localStorage.setItem(
    `skillforge.tool-deployment-create:["7","9"]:${second}`,
    JSON.stringify({ requestId: second, deploymentKey: 'second-actor' })
  )
  mountWithPermissions([])
  await flushPromises()
  expect(wrapper!.text()).toContain('first-actor')
  expect(wrapper!.text()).not.toContain('second-actor')
  expect(calls.deploymentCreateReceipt).toHaveBeenCalledExactlyOnceWith(first)
  authState.user = { tenant_id: '7', sub: '9', permissions: ['foundation.capabilities.read'] }
  authState.identityVersion++
  await flushPromises()
  expect(wrapper!.text()).toContain('second-actor')
  expect(wrapper!.text()).not.toContain('first-actor')
})

it('切换到没有待核对请求的主体不展示或保留原主体记录', async () => {
  const first = '11111111-1111-4111-8111-111111111111'
  localStorage.setItem(
    `skillforge.tool-deployment-create:["7","8"]:${first}`,
    JSON.stringify({ requestId: first, deploymentKey: 'first-actor' })
  )
  mountWithPermissions([])
  await flushPromises()
  expect(wrapper!.text()).toContain('first-actor')
  authState.user = { tenant_id: '99', sub: '9', permissions: ['foundation.capabilities.read'] }
  authState.identityVersion++
  await flushPromises()
  expect(wrapper!.text()).not.toContain('first-actor')
  expect(wrapper!.text()).not.toContain('创建结果尚未确认')
  expect(
    localStorage.getItem(`skillforge.tool-deployment-create:["7","8"]:${first}`)
  ).not.toBeNull()
})

it('切换主体后迟到目录与读取失败均不进入新主体页面', async () => {
  let finish!: (value: ReturnType<typeof deployment>[]) => void
  calls.deployments.mockReturnValueOnce(
    new Promise((resolve) => {
      finish = resolve
    })
  )
  mountWithPermissions([])
  await flushPromises()
  authState.user = { tenant_id: '7', sub: '9', permissions: ['foundation.capabilities.read'] }
  authState.identityVersion++
  finish([deployment('旧租户部署', '1')])
  await flushPromises()
  expect(wrapper!.text()).not.toContain('旧租户部署')
  expect(calls.version).not.toHaveBeenCalled()
  expect(calls.error).not.toHaveBeenCalled()
})

it('创建响应前撤销权限不清除原请求或展示迟到结果', async () => {
  mountWithPermissions(['foundation.capabilities.create'])
  await flushPromises()
  await prepareCreate()
  let finish!: (receipt: unknown) => void
  calls.createDeployment.mockReturnValueOnce(
    new Promise((resolve) => {
      finish = resolve
    })
  )
  await action('创建')!.trigger('click')
  await flushPromises()
  const requestId = calls.createDeployment.mock.calls[0]![0] as string
  authState.user.permissions = ['foundation.capabilities.read']
  finish({
    status: 'SUCCEEDED',
    request_id: requestId,
    deployment_id: '123',
    deployment_key: 'new-one'
  })
  await flushPromises()
  expect(
    localStorage.getItem(`skillforge.tool-deployment-create:["7","8"]:${requestId}`)
  ).not.toBeNull()
  expect(wrapper!.text()).toContain('创建结果尚未确认')
  expect(calls.success).not.toHaveBeenCalled()
})

it('主体切换后查看当前部署的迟到失败不显示给新主体', async () => {
  const requestId = '11111111-1111-4111-8111-111111111111'
  localStorage.setItem(
    `skillforge.tool-deployment-create:["7","8"]:${requestId}`,
    JSON.stringify({ requestId, deploymentKey: 'first-actor' })
  )
  mountWithPermissions([])
  await flushPromises()
  let fail!: (reason: unknown) => void
  calls.getDeployment.mockReturnValueOnce(
    new Promise((_, reject) => {
      fail = reject
    })
  )
  await action('查看当前部署')!.trigger('click')
  await flushPromises()
  authState.user = { tenant_id: '7', sub: '9', permissions: ['foundation.capabilities.read'] }
  authState.identityVersion++
  fail(new HttpClientError({ status: 403, code: 'FORBIDDEN', userMessage: '旧身份无权访问' }))
  await flushPromises()
  expect(wrapper!.text()).not.toContain('first-actor')
  expect(calls.error).not.toHaveBeenCalled()
})

it('创建成功并刷新后展示新部署及一次成功消息', async () => {
  mountWithPermissions(['foundation.capabilities.create'])
  await flushPromises()
  await prepareCreate()
  calls.deployments.mockResolvedValue([deployment('original', '1'), deployment('new-one', '2')])
  await action('创建')!.trigger('click')
  await flushPromises()
  expect(wrapper!.text()).toContain('new-one')
  expect(calls.createDeployment).toHaveBeenCalledTimes(1)
  expect(calls.success).toHaveBeenCalledExactlyOnceWith('工具部署已创建：new-one')
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
    '工具部署已创建：new-one；列表刷新失败。请点击“刷新”，不必重复操作。'
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
  expect(wrapper!.findComponent(ElDrawer).findComponent(EntitySelect).props('modelValue')).toBe(
    'server'
  )
  expect(wrapper!.text()).toContain('original')
  expect(calls.warning).toHaveBeenCalledExactlyOnceWith(
    '工具部署创建结果尚未确认，请核对原请求，不要重复创建。'
  )
  expect(calls.success).not.toHaveBeenCalled()
  expect(calls.deployments).toHaveBeenCalledTimes(1)
  expect(wrapper!.findComponent(ElDrawer).findComponent(EntitySelect).props('disabled')).toBe(true)
  expect(action('创建')!.props('disabled')).toBe(true)
  await action('取消')!.trigger('click')
  await flushPromises()
  expect(action('查看当前部署')).toBeUndefined()
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

it('持久拒绝回执保留输入且允许修改，不标记未知', async () => {
  mountWithPermissions(['foundation.capabilities.create'])
  await flushPromises()
  await prepareCreate()
  calls.createDeployment.mockImplementationOnce(async (requestId: string) => ({
    request_id: requestId,
    status: 'REJECTED',
    deployment_id: null,
    deployment_key: 'new-one',
    error_code: 'TOOL_DEPLOYMENT_CREATE_REJECTED',
    message: '工具版本未发布'
  }))
  await action('创建')!.trigger('click')
  await flushPromises()
  expect(calls.error).toHaveBeenCalledExactlyOnceWith('工具版本未发布')
  expect(wrapper!.findComponent(ElDrawer).findComponent(EntitySelect).props('modelValue')).toBe(
    'server'
  )
  expect(action('创建')!.props('disabled')).toBe(false)
  expect(wrapper!.text()).not.toContain('创建结果尚未确认')
  expect(calls.deployments).toHaveBeenCalledTimes(1)
})

it('创建部署时不要求或提交手填标识', async () => {
  mountWithPermissions(['foundation.capabilities.create'])
  await flushPromises()
  await prepareCreate()
  expect(wrapper!.findComponent(ElDrawer).text()).not.toContain('部署标识')
  await action('创建')!.trigger('click')
  await flushPromises()
  expect(calls.createDeployment).toHaveBeenCalledTimes(1)
  expect(calls.createDeployment.mock.calls[0]![1]).not.toHaveProperty('deploymentKey')
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

it('平台工具从发布契约自动绑定同名处理器，创建无需再次选择引用', async () => {
  calls.version.mockImplementation(async (id: string) => ({
    id,
    name: '当前时间',
    versionKey: 'v1',
    descriptor: { tool_name: 'get_current_time' }
  }))
  calls.platformTools.mockResolvedValue([
    { name: 'get_current_time', description: '读取当前时间' },
    { name: 'template_render', description: '渲染模板' }
  ])
  mountWithPermissions(['foundation.capabilities.create'])
  await flushPromises()
  await action('新增部署')!.trigger('click')
  await flushPromises()
  const drawer = wrapper!.findComponent(ElDrawer)
  const selects = drawer.findAllComponents(ElSelect)
  selects[0]!.vm.$emit('update:modelValue', '2')
  selects[1]!.vm.$emit('update:modelValue', 'PLATFORM')
  await flushPromises()
  expect(drawer.findComponent(EntitySelect).exists()).toBe(false)
  expect(document.body.querySelector('[role="dialog"]')?.textContent).toContain('get_current_time')
  expect(calls.createDeployment).not.toHaveBeenCalled()
  await action('创建')!.trigger('click')
  await flushPromises()
  expect(calls.createDeployment).toHaveBeenCalledExactlyOnceWith(
    expect.any(String),
    expect.objectContaining({
      toolVersionId: '2',
      providerType: 'PLATFORM',
      providerRef: 'get_current_time'
    })
  )
})

it('平台部署切换工具后更新处理器绑定，不沿用上一工具或显示名称', async () => {
  calls.definitions.mockResolvedValue([{ defaultVersionId: '2' }, { defaultVersionId: '3' }])
  calls.version.mockImplementation(async (id: string) => ({
    id,
    name: id === '3' ? '模板显示名' : '时钟显示名',
    versionKey: 'v1',
    descriptor: { tool_name: id === '3' ? 'template_render' : 'get_current_time' }
  }))
  calls.platformTools.mockResolvedValue([
    { name: 'get_current_time', description: '读取当前时间' },
    { name: 'template_render', description: '渲染模板' }
  ])
  mountWithPermissions(['foundation.capabilities.create'])
  await flushPromises()
  await action('新增部署')!.trigger('click')
  await flushPromises()
  const drawer = wrapper!.findComponent(ElDrawer)
  const selects = drawer.findAllComponents(ElSelect)
  selects[0]!.vm.$emit('update:modelValue', '2')
  selects[1]!.vm.$emit('update:modelValue', 'PLATFORM')
  await flushPromises()
  expect(document.body.querySelector('[role="dialog"]')?.textContent).toContain('get_current_time')
  selects[0]!.vm.$emit('update:modelValue', '3')
  await flushPromises()
  expect(document.body.querySelector('[role="dialog"]')?.textContent).toContain('template_render')
  await action('创建')!.trigger('click')
  await flushPromises()
  expect(calls.createDeployment).toHaveBeenCalledExactlyOnceWith(
    expect.any(String),
    expect.objectContaining({
      toolVersionId: '3',
      providerType: 'PLATFORM',
      providerRef: 'template_render'
    })
  )
})

it('停用响应丢失后锁定原部署，刷新和高修订状态不能代替原回执', async () => {
  mountWithPermissions(['foundation.capabilities.cancel'])
  await flushPromises()
  calls.setDeploymentActiveWithReceipt.mockRejectedValueOnce(new Error('响应丢失'))
  await action('停用')!.trigger('click')
  await flushPromises()
  const [requestId] = calls.setDeploymentActiveWithReceipt.mock.calls[0]!
  expect(action('停用')!.props('disabled')).toBe(true)
  expect(wrapper!.text()).toContain(requestId)
  calls.deployments.mockResolvedValue([
    { ...deployment('original', '1'), status: 'DISABLED', revision: 3 }
  ])
  await refresh()
  expect(wrapper!.text()).toContain('结果尚未确认')
  expect(calls.deploymentStateReceipt).toHaveBeenCalledWith(requestId)
  expect(calls.setDeploymentActiveWithReceipt).toHaveBeenCalledTimes(1)
  calls.deploymentStateReceipt.mockResolvedValueOnce({
    request_id: requestId,
    status: 'SUCCEEDED',
    deployment_key: 'original',
    deployment_status: 'DISABLED',
    revision: 2,
    error_code: null,
    error_message: null
  })
  await action('核对原启停结果')!.trigger('click')
  await flushPromises()
  expect(wrapper!.text()).not.toContain('结果尚未确认')
  expect(localStorage.length).toBe(0)
  expect(calls.setDeploymentActiveWithReceipt).toHaveBeenCalledTimes(1)
})
it('刷新页面按持久原请求只读核对，未匹配或读取失败保留UNKNOWN', async () => {
  mountWithPermissions(['foundation.capabilities.cancel'])
  await flushPromises()
  calls.setDeploymentActiveWithReceipt.mockRejectedValueOnce(new Error('响应丢失'))
  await action('停用')!.trigger('click')
  await flushPromises()
  const [requestId] = calls.setDeploymentActiveWithReceipt.mock.calls[0]!
  wrapper!.unmount()
  calls.deploymentStateReceipt.mockRejectedValueOnce(new Error('回执不可读'))
  mountWithPermissions(['foundation.capabilities.cancel'])
  await flushPromises()
  expect(calls.deploymentStateReceipt).toHaveBeenCalledWith(requestId)
  expect(wrapper!.text()).toContain(requestId)
  expect(action('停用')!.props('disabled')).toBe(true)
  calls.deploymentStateReceipt.mockResolvedValueOnce({
    request_id: 'wrong',
    status: 'SUCCEEDED',
    deployment_key: 'original',
    deployment_status: 'DISABLED',
    revision: 2
  })
  await action('核对原启停结果')!.trigger('click')
  await flushPromises()
  expect(wrapper!.text()).toContain(requestId)
  expect(calls.setDeploymentActiveWithReceipt).toHaveBeenCalledTimes(1)
})
it('派发前保存失败不发送启停请求', async () => {
  mountWithPermissions(['foundation.capabilities.cancel'])
  await flushPromises()
  const store = vi.spyOn(Storage.prototype, 'setItem').mockImplementationOnce(() => {
    throw Error('unavailable')
  })
  try {
    await action('停用')!.trigger('click')
    await flushPromises()
    expect(calls.setDeploymentActiveWithReceipt).not.toHaveBeenCalled()
    expect(wrapper!.text()).toContain('尚未发送请求')
  } finally {
    store.mockRestore()
  }
})

it('原启停被明确拒绝后清除UNKNOWN，不提交新的启停', async () => {
  mountWithPermissions(['foundation.capabilities.cancel'])
  await flushPromises()
  calls.setDeploymentActiveWithReceipt.mockImplementationOnce(async (requestId: string) => ({
    request_id: requestId,
    status: 'REJECTED',
    deployment_key: 'original',
    deployment_status: null,
    revision: null,
    error_code: 'STATE_REJECTED',
    error_message: '原修订已变化'
  }))
  await action('停用')!.trigger('click')
  await flushPromises()
  expect(calls.error).toHaveBeenCalledWith('原修订已变化')
  expect(action('核对原启停结果')).toBeUndefined()
  expect(localStorage.length).toBe(0)
  expect(calls.setDeploymentActiveWithReceipt).toHaveBeenCalledTimes(1)
})
it('切换身份隐藏原启停记录，切回后仍保留原请求，迟到回执不跨身份回填', async () => {
  mountWithPermissions(['foundation.capabilities.cancel'])
  await flushPromises()
  let finish!: (value: unknown) => void
  calls.setDeploymentActiveWithReceipt.mockReturnValueOnce(
    new Promise((resolve) => {
      finish = resolve
    })
  )
  await action('停用')!.trigger('click')
  await flushPromises()
  const [requestId] = calls.setDeploymentActiveWithReceipt.mock.calls[0]!
  authState.user.sub = 'another'
  authState.identityVersion++
  await flushPromises()
  expect(wrapper!.text()).not.toContain(requestId)
  finish({
    request_id: requestId,
    status: 'SUCCEEDED',
    deployment_key: 'original',
    deployment_status: 'DISABLED',
    revision: 2,
    error_code: null,
    error_message: null
  })
  await flushPromises()
  expect(calls.success).not.toHaveBeenCalled()
  expect(localStorage.length).toBe(1)
  authState.user.sub = '8'
  authState.identityVersion++
  await flushPromises()
  expect(wrapper!.text()).toContain(requestId)
  expect(calls.setDeploymentActiveWithReceipt).toHaveBeenCalledTimes(1)
})
it('清除启停原记录失败仍锁定，失去读取权限后不核对', async () => {
  mountWithPermissions(['foundation.capabilities.cancel'])
  await flushPromises()
  calls.setDeploymentActiveWithReceipt.mockRejectedValueOnce(new Error('响应丢失'))
  await action('停用')!.trigger('click')
  await flushPromises()
  const [requestId] = calls.setDeploymentActiveWithReceipt.mock.calls[0]!
  calls.deploymentStateReceipt.mockResolvedValue({
    request_id: requestId,
    status: 'SUCCEEDED',
    deployment_key: 'original',
    deployment_status: 'DISABLED',
    revision: 2,
    error_code: null,
    error_message: null
  })
  const remove = vi.spyOn(Storage.prototype, 'removeItem').mockImplementationOnce(() => {
    throw Error('unavailable')
  })
  try {
    await action('核对原启停结果')!.trigger('click')
    await flushPromises()
    expect(wrapper!.text()).toContain('无法清除本地请求记录')
    expect(action('停用')!.props('disabled')).toBe(true)
  } finally {
    remove.mockRestore()
  }
  authState.user.permissions = ['foundation.capabilities.cancel']
  await flushPromises()
  expect(action('核对原启停结果')!.props('disabled')).toBe(true)
  expect(calls.deploymentStateReceipt).toHaveBeenCalledTimes(1)
})

it('原成功回执不回退更高修订的当前状态，后续列表失败仍保留当前事实', async () => {
  mountWithPermissions(['foundation.capabilities.cancel'])
  await flushPromises()
  calls.setDeploymentActiveWithReceipt.mockRejectedValueOnce(new Error('响应丢失'))
  await action('停用')!.trigger('click')
  await flushPromises()
  const [requestId] = calls.setDeploymentActiveWithReceipt.mock.calls[0]!
  calls.deployments.mockResolvedValueOnce([
    { ...deployment('original', '1'), status: 'ACTIVE', revision: 3 }
  ])
  await refresh()
  calls.deploymentStateReceipt.mockResolvedValueOnce({
    request_id: requestId,
    status: 'SUCCEEDED',
    deployment_key: 'original',
    deployment_status: 'DISABLED',
    revision: 2,
    error_code: null,
    error_message: null
  })
  calls.deployments.mockRejectedValueOnce(new Error('当前列表不可读'))
  await action('核对原启停结果')!.trigger('click')
  await flushPromises()
  expect(action('停用')).toBeDefined()
  expect(action('启用')).toBeUndefined()
  expect(wrapper!.text()).toContain('此回执不代表当前状态')
  expect(wrapper!.text()).toContain('原启停回执')
  expect(wrapper!.text()).not.toContain('结果尚未确认')
  calls.setDeploymentActiveWithReceipt.mockRejectedValueOnce(new Error('第二次响应丢失'))
  await action('停用')!.trigger('click')
  await flushPromises()
  expect(calls.setDeploymentActiveWithReceipt.mock.calls[1]!.slice(1)).toEqual([
    'original',
    3,
    false
  ])
})

it('跨标签新增原启停身份及时锁定，删除或清空事件不抹去本页UNKNOWN', async () => {
  mountWithPermissions(['foundation.capabilities.cancel'])
  await flushPromises()
  const requestId = '12345678-1234-4234-8234-123456789012'
  const key = `skillforge.tool-deployment-state:${JSON.stringify(['7', '8'])}:${requestId}`
  const record = JSON.stringify({
    requestId,
    deploymentKey: 'original',
    revision: 1,
    active: false
  })
  localStorage.setItem(key, record)
  window.dispatchEvent(
    new StorageEvent('storage', { key, newValue: record, storageArea: localStorage })
  )
  await flushPromises()
  expect(wrapper!.text()).toContain(requestId)
  expect(action('停用')!.props('disabled')).toBe(true)
  expect(calls.setDeploymentActiveWithReceipt).not.toHaveBeenCalled()
  localStorage.removeItem(key)
  window.dispatchEvent(
    new StorageEvent('storage', { key, oldValue: record, storageArea: localStorage })
  )
  await flushPromises()
  expect(wrapper!.text()).toContain(requestId)
  expect(action('停用')!.props('disabled')).toBe(true)
  window.dispatchEvent(new StorageEvent('storage', { key: null, storageArea: localStorage }))
  await flushPromises()
  expect(wrapper!.text()).toContain(requestId)
  calls.deploymentStateReceipt.mockResolvedValueOnce({
    request_id: requestId,
    status: 'SUCCEEDED',
    deployment_key: 'original',
    deployment_status: 'DISABLED',
    revision: 2,
    error_code: null,
    error_message: null
  })
  await action('核对原启停结果')!.trigger('click')
  await flushPromises()
  expect(action('核对原启停结果')).toBeUndefined()
  expect(calls.setDeploymentActiveWithReceipt).not.toHaveBeenCalled()
})
it('其他主体的启停storage事件不污染当前页面', async () => {
  mountWithPermissions(['foundation.capabilities.cancel'])
  await flushPromises()
  const requestId = '12345678-1234-4234-8234-123456789012'
  const key = `skillforge.tool-deployment-state:${JSON.stringify(['7', 'another'])}:${requestId}`
  const record = JSON.stringify({
    requestId,
    deploymentKey: 'original',
    revision: 1,
    active: false
  })
  localStorage.setItem(key, record)
  window.dispatchEvent(
    new StorageEvent('storage', { key, newValue: record, storageArea: localStorage })
  )
  await flushPromises()
  expect(wrapper!.text()).not.toContain(requestId)
  expect(action('停用')!.props('disabled')).toBe(false)
})

it('首次启停未到达后原回执为空仍锁定，显式继续沿用同一请求和原参数', async () => {
  mountWithPermissions(['foundation.capabilities.cancel'])
  await flushPromises()
  calls.setDeploymentActiveWithReceipt.mockRejectedValueOnce(new Error('请求未到达'))
  await action('停用')!.trigger('click')
  await flushPromises()
  const original = calls.setDeploymentActiveWithReceipt.mock.calls[0]!
  await action('核对原启停结果')!.trigger('click')
  await flushPromises()
  expect(action('停用')!.props('disabled')).toBe(true)
  expect(calls.setDeploymentActiveWithReceipt).toHaveBeenCalledTimes(1)
  calls.setDeploymentActiveWithReceipt.mockResolvedValueOnce({
    request_id: original[0],
    status: 'SUCCEEDED',
    deployment_key: 'original',
    deployment_status: 'DISABLED',
    revision: 2,
    error_code: null,
    error_message: null
  })
  calls.deployments.mockResolvedValueOnce([
    { ...deployment('original', '1'), status: 'DISABLED', revision: 2 }
  ])
  await action('继续原启停操作')!.trigger('click')
  await flushPromises()
  expect(calls.setDeploymentActiveWithReceipt.mock.calls).toEqual([original, original])
  expect(action('核对原启停结果')).toBeUndefined()
  expect(wrapper!.text()).toContain('原停用请求已确认（修订 2）；当前状态以列表为准')
  expect(localStorage.length).toBe(0)
})
it('继续原启停操作重验原动作权限，不因当前状态变化改为另一动作', async () => {
  mountWithPermissions(['foundation.capabilities.cancel'])
  await flushPromises()
  calls.setDeploymentActiveWithReceipt.mockRejectedValueOnce(new Error('请求未到达'))
  await action('停用')!.trigger('click')
  await flushPromises()
  authState.user.permissions = ['foundation.capabilities.read', 'foundation.capabilities.run']
  permissions.value = [...authState.user.permissions]
  await flushPromises()
  expect(action('继续原启停操作')).toBeUndefined()
  expect(calls.setDeploymentActiveWithReceipt).toHaveBeenCalledTimes(1)
  expect(action('核对原启停结果')).toBeDefined()
})
