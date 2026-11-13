import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ElementPlus, { ElMessageBox, ElPagination, ElTabs } from 'element-plus'
import OperationsPage from './OperationsPage.vue'
import type { OperationsRequest } from './contracts'

const audits = Array.from({ length: 137 }, (_, i) => ({
  id: String(9007199254740993n + BigInt(i)),
  actor_id: `actor-${i % 7}`,
  action: i % 3 ? 'auth.login' : 'permissions.update',
  category: i % 3 ? 'authentication' : 'authorization',
  outcome: i % 4 ? 'success' : 'denied',
  resource_type: 'member',
  message: `脱敏审计-${i}`,
  trace_id: `trace-${i % 9}`,
  created_at: '2026-10-12T08:00:00+08:00'
}))
const permissions = ['audit_read', 'retention_read', 'retention_manage', 'retention_execute'].map(
  (code) => `foundation.operations.${code}`
)
const policy = { resource: 'audit', retention_days: 180, enabled: true, version: 7 }
const preview = {
  id: 'preview-v7',
  resource: 'audit',
  cutoff: '2026-04-01T00:00:00Z',
  candidates: 41,
  protected_count: 12,
  deleted_count: 0,
  status: 'ready',
  expires_at: '2026-10-13T00:00:00Z',
  created_at: '2026-10-12T08:00:00Z',
  completed_at: null,
  failure_code: null
}
const wrappers: VueWrapper[] = []
beforeEach(() => {
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe() {}
      unobserve() {}
      disconnect() {}
    }
  )
})
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  document.body.innerHTML = ''
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})
function read(path: string) {
  if (path.endsWith('/audit/options'))
    return {
      actions: [
        { value: 'auth.login', label: '登录' },
        { value: 'permissions.update', label: '更新权限' }
      ],
      actors: [{ value: 'actor-0', label: '演示管理员' }],
      categories: [
        { value: 'authentication', label: '认证' },
        { value: 'authorization', label: '授权' }
      ],
      outcomes: [
        { value: 'success', label: '成功' },
        { value: 'denied', label: '拒绝' }
      ],
      resource_types: [{ value: 'member', label: '成员' }]
    }
  if (path.endsWith('/retention')) return policy
  if (path.endsWith('/runs')) return []
  if (path.endsWith('/holds'))
    return Array.from({ length: 12 }, (_, i) => ({
      id: `hold-${i}`,
      audit_id: audits[i]!.id,
      reason: '调查保护',
      expires_at: null,
      released_at: i % 3 ? null : '2026-10-11T00:00:00Z'
    }))
  const url = new URL(path, 'http://fixture'),
    page = Number(url.searchParams.get('page') || 1)
  return {
    items: audits.slice((page - 1) * 50, page * 50),
    total: audits.length,
    page,
    page_size: 50
  }
}
async function render(
  request: OperationsRequest,
  apiBase = '/tenant/foundation/operations',
  auditOptionsPath?: string
) {
  const wrapper = mount(OperationsPage, {
    props: { request, apiBase, permissions, auditOptionsPath },
    attachTo: document.body,
    global: { plugins: [ElementPlus] }
  })
  wrappers.push(wrapper)
  await flushPromises()
  return wrapper
}
function button(wrapper: VueWrapper, label: string) {
  return wrapper.findAll('button').find((item) => item.text() === label)!
}

describe('复杂安全审计、CAS和冻结清理快照', () => {
  it.each(['/tenant/foundation/operations', '/admin/foundation/operations'])(
    '双宿主 %s 仅请求九字段摘要，翻页保留精确总数和调查身份',
    async (apiBase) => {
      const calls: Array<{ method: string; path: string; body?: unknown }> = []
      const request: OperationsRequest = async <T>(
        method: string,
        path: string,
        body?: unknown
      ) => {
        calls.push({ method, path, body })
        return read(path) as T
      }
      const wrapper = await render(request, apiBase)
      expect(new URL(calls[0]!.path, 'http://fixture').pathname).toBe(`${apiBase}/audit/summaries`)
      expect(Object.keys(audits[0]!)).toHaveLength(9)
      expect(audits[0]).not.toHaveProperty('payload')
      expect(audits[0]).not.toHaveProperty('tenant_id')
      expect(audits[0]).not.toHaveProperty('resource_id')
      expect(wrapper.getComponent(ElPagination).props('total')).toBe(137)
      wrapper.getComponent(ElPagination).vm.$emit('update:currentPage', 3)
      wrapper.getComponent(ElPagination).vm.$emit('current-change', 3)
      await flushPromises()
      expect(calls.at(-1)!.path).toContain('/audit/summaries?')
      expect(calls.at(-1)!.path).toContain('page=3')
      await button(wrapper, '调查保护').trigger('click')
      await flushPromises()
      expect(wrapper.get('form.filters input').element).toHaveProperty('value', audits[100]!.id)
      expect(calls.every((call) => call.method === 'GET')).toBe(true)
      expect(
        calls.some((call) => new URL(call.path, 'http://fixture').pathname === `${apiBase}/audit`)
      ).toBe(false)
    }
  )

  it('摘要刷新失败保留旧页和筛选，恢复仅读摘要且不请求完整审计', async () => {
    let fail = false
    const calls: string[] = []
    const request: OperationsRequest = async <T>(_method: string, path: string) => {
      calls.push(path)
      if (fail) throw new Error('摘要暂时不可读')
      return read(path) as T
    }
    const wrapper = await render(request)
    await button(wrapper, '更多条件').trigger('click')
    await wrapper.get('input[placeholder="auth.login"]').setValue('auth.login')
    await wrapper.get('form.filters').trigger('submit')
    await flushPromises()
    fail = true
    await wrapper.get('form.filters').trigger('submit')
    await flushPromises()
    expect(wrapper.findAll('.el-table__body tbody tr')).toHaveLength(50)
    expect(wrapper.get('input[placeholder="auth.login"]').element).toHaveProperty(
      'value',
      'auth.login'
    )
    fail = false
    await wrapper.get('form.filters').trigger('submit')
    await flushPromises()
    expect(calls.at(-1)).toContain('/audit/summaries?')
    expect(calls.at(-1)).toContain('action=auth.login')
    expect(calls.every((path) => path.includes('/audit/summaries?'))).toBe(true)
  })

  it('机构宿主使用服务端审计枚举并在列表中展示可读值', async () => {
    const request: OperationsRequest = async <T>(_method: string, path: string) => read(path) as T
    const wrapper = await render(request, '/tenant/foundation/operations', '/tenant/audit/options')
    expect(wrapper.text()).toContain('登录')
    expect(wrapper.text()).toContain('认证')
    expect(wrapper.text()).toContain('成功')
    expect(wrapper.text()).toContain('成员')
    await button(wrapper, '更多条件').trigger('click')
    expect(wrapper.find('input[placeholder="auth.login"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('操作类型')
    expect(wrapper.text()).toContain('操作者')
    expect(wrapper.text()).toContain('资源类型')
  })

  it('默认四个条件，展开后八个条件及保护表单均有可见说明', async () => {
    const request: OperationsRequest = async <T>(_method: string, path: string) => read(path) as T
    const wrapper = await render(request)
    const described = (selector: string, count: number) => {
      const controls = wrapper.findAll(selector).filter((control) => control.isVisible())
      expect(controls).toHaveLength(count)
      for (const control of controls) {
        const ids = control.attributes('aria-describedby')?.split(/\s+/).filter(Boolean) ?? []
        expect(ids.length).toBeGreaterThan(0)
        for (const id of ids) {
          const targets = wrapper.findAll(`[id="${id}"]`)
          expect(targets).toHaveLength(1)
          expect(targets[0]!.text().trim()).not.toBe('')
          expect(targets[0]!.isVisible()).toBe(true)
        }
      }
    }
    described('form.filters input:not([type="hidden"])', 4)
    await button(wrapper, '更多条件').trigger('click')
    described('form.filters input:not([type="hidden"])', 8)
    wrapper.getComponent(ElTabs).vm.$emit('update:modelValue', 'retention')
    await flushPromises()
    described('.policy input, .policy [role="switch"]', 2)
    described('form.filters input:not([type="hidden"])', 3)
  })

  it('137审计跨3页保持提交的动作范围，未提交筛选不改变翻页请求', async () => {
    const paths: string[] = []
    const request: OperationsRequest = async <T>(_method: string, path: string) => {
      paths.push(path)
      return read(path) as T
    }
    const wrapper = await render(request)
    expect(wrapper.findAll('.el-table__body tbody tr')).toHaveLength(50)
    await button(wrapper, '更多条件').trigger('click')
    await wrapper.get('input[placeholder="auth.login"]').setValue('permissions.update')
    wrapper.getComponent(ElPagination).vm.$emit('update:currentPage', 3)
    wrapper.getComponent(ElPagination).vm.$emit('current-change', 3)
    await flushPromises()
    expect(paths.at(-1)).toContain('page=3')
    expect(paths.at(-1)).not.toContain('permissions.update')
    expect(wrapper.findAll('.el-table__body tbody tr')).toHaveLength(37)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(paths.at(-1)).toContain('action=permissions.update')
    expect(paths.at(-1)).toContain('page=1')
  })

  it('从审计行登记保护自动带入精确身份且不自动写入，重置清空已提交范围', async () => {
    const calls: Array<{ method: string; path: string }> = []
    const request: OperationsRequest = async <T>(method: string, path: string) => {
      calls.push({ method, path })
      return read(path) as T
    }
    const wrapper = await render(request)
    await button(wrapper, '调查保护').trigger('click')
    await flushPromises()
    expect(wrapper.get('form.filters input').element).toHaveProperty('value', audits[0]!.id)
    expect(calls.every((item) => item.method === 'GET')).toBe(true)
    wrapper.getComponent(ElTabs).vm.$emit('update:modelValue', 'audit')
    await flushPromises()
    await button(wrapper, '更多条件').trigger('click')
    await wrapper.get('input[placeholder="auth.login"]').setValue('auth.login')
    await wrapper.get('form.filters').trigger('submit')
    await flushPromises()
    await button(wrapper, '重置').trigger('click')
    await flushPromises()
    expect(calls.at(-1)!.path).not.toContain('action=')
  })

  it('12保护与41候选预览不能替代执行，取消无写且策略CAS失败不重放', async () => {
    const commands: Array<{ path: string; body: unknown }> = []
    vi.spyOn(ElMessageBox, 'confirm').mockRejectedValue('cancel')
    const request: OperationsRequest = async <T>(method: string, path: string, body?: unknown) => {
      if (method === 'GET') return read(path) as T
      commands.push({ path, body })
      if (path.endsWith('/preview')) return preview as T
      throw new Error('409: policy version 8')
    }
    const wrapper = await render(request)
    wrapper.getComponent(ElTabs).vm.$emit('update:modelValue', 'retention')
    await flushPromises()
    await button(wrapper, '生成清理预览').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('候选41，受保护12')
    await button(wrapper, '执行这次预览').trigger('click')
    await flushPromises()
    expect(commands.filter((item) => item.path.endsWith('/execute'))).toHaveLength(0)
    await wrapper.get('form.policy input').setValue('181')
    await wrapper.get('form.policy').trigger('submit')
    await flushPromises()
    expect(commands.at(-1)).toMatchObject({
      path: '/tenant/foundation/operations/retention',
      body: { retention_days: 181, enabled: true, expected_version: 7 }
    })
    expect(document.querySelector('.el-message--error')?.textContent).toContain(
      '409: policy version 8'
    )
    expect(commands.filter((item) => item.path.endsWith('/retention'))).toHaveLength(1)
  })

  it('冻结preview确认期间换主体清除保护与预览，旧确认无执行副作用', async () => {
    let confirm!: (value: 'confirm') => void
    vi.spyOn(ElMessageBox, 'confirm').mockImplementation(
      () =>
        new Promise((resolve) => {
          confirm = resolve
        })
    )
    const executed: string[] = []
    const request: OperationsRequest = async <T>(method: string, path: string) => {
      if (path.endsWith('/execute')) executed.push(path)
      return (method === 'GET' ? read(path) : preview) as T
    }
    const wrapper = await render(request)
    wrapper.getComponent(ElTabs).vm.$emit('update:modelValue', 'retention')
    await flushPromises()
    await button(wrapper, '生成清理预览').trigger('click')
    await flushPromises()
    await button(wrapper, '执行这次预览').trigger('click')
    await wrapper.setProps({
      apiBase: '/admin/foundation/operations',
      permissions: ['foundation.operations.audit_read']
    })
    await flushPromises()
    confirm('confirm')
    await flushPromises()
    expect(executed).toEqual([])
    expect(wrapper.text()).not.toContain('preview-v7')
    expect(wrapper.findAll('button').some((item) => item.text() === '执行这次预览')).toBe(false)
  })
  it('未保存策略不能预览，重读保留草稿，取消修改不写入', async () => {
    const request = vi.fn(async (_method: string, path: string) => read(path)) as OperationsRequest
    const wrapper = await render(request)
    wrapper.getComponent(ElTabs).vm.$emit('update:modelValue', 'retention')
    await flushPromises()
    expect(button(wrapper, '保存策略').attributes('disabled')).toBeDefined()
    await wrapper.get('form.policy input').setValue('250')
    expect(button(wrapper, '生成清理预览').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('策略有未保存修改')
    wrapper.getComponent(ElTabs).vm.$emit('update:modelValue', 'audit')
    await flushPromises()
    wrapper.getComponent(ElTabs).vm.$emit('update:modelValue', 'retention')
    await flushPromises()
    expect(wrapper.get('form.policy input').element).toHaveProperty('value', '250')
    await button(wrapper, '取消修改').trigger('click')
    expect(wrapper.get('form.policy input').element).toHaveProperty('value', '180')
    expect(vi.mocked(request).mock.calls.every(([method]) => method === 'GET')).toBe(true)
  })
})

function holdInputs(wrapper: VueWrapper) {
  return wrapper.get('form.filters').findAll('input')
}
async function selectAudit(wrapper: VueWrapper, index: number) {
  wrapper.getComponent(ElTabs).vm.$emit('update:modelValue', 'audit')
  await flushPromises()
  await wrapper
    .findAll('button')
    .filter((item) => item.text() === '调查保护')
    [index]!.trigger('click')
  await flushPromises()
}
it('调查保护A草稿换B前需确认，取消保留A且不写入', async () => {
  const request = vi.fn(async (_method: string, path: string) => read(path)) as OperationsRequest
  const wrapper = await render(request)
  await selectAudit(wrapper, 0)
  await holdInputs(wrapper)[1]!.setValue('A调查依据草稿')
  await holdInputs(wrapper)[2]!.setValue('2099-01-01T12:00')
  const confirm = vi.spyOn(ElMessageBox, 'confirm').mockRejectedValue('cancel')
  await selectAudit(wrapper, 1)
  expect(confirm).toHaveBeenCalledOnce()
  wrapper.getComponent(ElTabs).vm.$emit('update:modelValue', 'retention')
  await flushPromises()
  expect(holdInputs(wrapper)[0]!.element).toHaveProperty('value', audits[0]!.id)
  expect(holdInputs(wrapper)[1]!.element).toHaveProperty('value', 'A调查依据草稿')
  expect(holdInputs(wrapper)[2]!.element).toHaveProperty('value', '2099-01-01T12:00')
  expect(vi.mocked(request).mock.calls.every(([method]) => method === 'GET')).toBe(true)
})

it('确认切换清除旧保护草稿并绑定B，仅自动带ID不重复确认', async () => {
  const request = vi.fn(async (_method: string, path: string) => read(path)) as OperationsRequest
  const wrapper = await render(request)
  const confirm = vi
    .spyOn(ElMessageBox, 'confirm')
    .mockResolvedValue('confirm' as Awaited<ReturnType<typeof ElMessageBox.confirm>>)
  await selectAudit(wrapper, 0)
  await selectAudit(wrapper, 1)
  expect(confirm).not.toHaveBeenCalled()
  await holdInputs(wrapper)[1]!.setValue('B旧依据')
  await selectAudit(wrapper, 2)
  expect(confirm).toHaveBeenCalledOnce()
  expect(holdInputs(wrapper)[0]!.element).toHaveProperty('value', audits[2]!.id)
  expect(holdInputs(wrapper)[1]!.element).toHaveProperty('value', '')
  expect(holdInputs(wrapper)[2]!.element).toHaveProperty('value', '')
  const leave = new Event('beforeunload', { cancelable: true })
  window.dispatchEvent(leave)
  expect(leave.defaultPrevented).toBe(false)
  expect(vi.mocked(request).mock.calls.every(([method]) => method === 'GET')).toBe(true)
})
it('再次选择同一审计保留草稿，草稿本身触发离开保护，成功登记后解除', async () => {
  const commands: unknown[] = []
  const request: OperationsRequest = async <T>(method: string, path: string, body?: unknown) => {
    if (method === 'POST') {
      commands.push(body)
      return { id: 'new-hold' } as T
    }
    return read(path) as T
  }
  const wrapper = await render(request)
  const confirm = vi.spyOn(ElMessageBox, 'confirm')
  await selectAudit(wrapper, 0)
  await holdInputs(wrapper)[1]!.setValue('保留的调查依据')
  await holdInputs(wrapper)[2]!.setValue('2099-01-01T12:00')
  await selectAudit(wrapper, 0)
  expect(confirm).not.toHaveBeenCalled()
  expect(holdInputs(wrapper)[1]!.element).toHaveProperty('value', '保留的调查依据')
  expect(holdInputs(wrapper)[2]!.element).toHaveProperty('value', '2099-01-01T12:00')
  const before = new Event('beforeunload', { cancelable: true })
  window.dispatchEvent(before)
  expect(before.defaultPrevented).toBe(true)
  await wrapper.get('form.filters').trigger('submit')
  await flushPromises()
  expect(commands).toHaveLength(1)
  expect(commands[0]).toMatchObject({ audit_id: audits[0]!.id, reason: '保留的调查依据' })
  expect(holdInputs(wrapper)[0]!.element).toHaveProperty('value', '')
  expect(holdInputs(wrapper)[1]!.element).toHaveProperty('value', '')
  const after = new Event('beforeunload', { cancelable: true })
  window.dispatchEvent(after)
  expect(after.defaultPrevented).toBe(false)
})
it('作用域改变清除旧调查保护草稿，旧确认不填回新作用域', async () => {
  const request: OperationsRequest = async <T>(_method: string, path: string) => read(path) as T
  const wrapper = await render(request)
  await selectAudit(wrapper, 0)
  await holdInputs(wrapper)[1]!.setValue('旧范围草稿')
  let finish!: (value: Awaited<ReturnType<typeof ElMessageBox.confirm>>) => void
  vi.spyOn(ElMessageBox, 'confirm').mockImplementation(
    () =>
      new Promise((resolve) => {
        finish = resolve
      })
  )
  await selectAudit(wrapper, 1)
  await wrapper.setProps({ apiBase: '/admin/foundation/operations' })
  await flushPromises()
  finish('confirm' as Awaited<ReturnType<typeof ElMessageBox.confirm>>)
  await flushPromises()
  wrapper.getComponent(ElTabs).vm.$emit('update:modelValue', 'retention')
  await flushPromises()
  expect(holdInputs(wrapper)[0]!.element).toHaveProperty('value', '')
  expect(holdInputs(wrapper)[1]!.element).toHaveProperty('value', '')
})
