import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ElementPlus, { ElMessageBox, ElPagination, ElSelect } from 'element-plus'
import { createMemoryHistory, createRouter } from 'vue-router'
import FoundationJobsPage from './FoundationJobsPage.vue'
import { HttpClientError } from '../../utils/http'
import type { FoundationJob, FoundationRequest, TransferHandler } from './contracts'

const jobs: FoundationJob[] = Array.from({ length: 27 }, (_, i) => ({
  id: `job-${i}`,
  handler: `members-${i % 3}`,
  operation: i % 2 ? 'export' : 'import',
  state: i % 3 ? 'succeeded' : 'failed',
  total_rows: 137,
  completed_rows: 137 - i,
  error_rows: i,
  error_message: i ? '部分行校验失败' : null,
  result_file_id: `result-${i}`,
  created_at: '2026-10-12T08:00:00+08:00',
  expires_at: '2026-10-19T08:00:00+08:00'
}))
const handlers: TransferHandler[] = Array.from({ length: 3 }, (_, i) => ({
  code: `members-${i}`,
  title: `成员领域${i}`,
  columns: ['username', 'roles', 'department_id'],
  can_import: false,
  can_export: true,
  option_fields: [
    { code: 'department_id', label: '部门范围', required: true, hint: '指定部门' },
    { code: 'roles', label: '角色列表', required: false, hint: '多个角色' },
    { code: 'query', label: '组合查询', required: false, hint: '结构化筛选JSON' }
  ]
}))
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
function pageResult(path: string, items = jobs) {
  const url = new URL(path, 'http://fixture'),
    page = Number(url.searchParams.get('page') || 1),
    size = Number(url.searchParams.get('page_size') || 20)
  return {
    items: items.slice((page - 1) * size, page * size),
    total: items.length,
    page,
    page_size: size
  }
}
async function render(
  request: FoundationRequest,
  allowed = ['foundation.jobs.read', 'foundation.jobs.create', 'foundation.jobs.cancel']
) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/', component: { template: '<div />' } }]
  })
  await router.push('/')
  await router.isReady()
  const wrapper = mount(FoundationJobsPage, {
    props: {
      request,
      apiBase: '/tenant/foundation',
      permissions: allowed
    },
    attachTo: document.body,
    global: { plugins: [router, ElementPlus] }
  })
  wrappers.push(wrapper)
  await flushPromises()
  return wrapper
}
function button(wrapper: VueWrapper, label: string) {
  return wrapper.findAll('button').find((item) => item.text() === label)!
}

describe('复杂导入导出任务状态和隔离', () => {
  it('已受理后查看任务只读取详情，不重复提交写请求', async () => {
    const commands: unknown[] = []
    const request: FoundationRequest = async <T>(method: string, path: string, body?: unknown) => {
      if (method === 'POST') {
        commands.push(body)
        return jobs[0] as T
      }
      if (path.endsWith('/handlers')) return [{ ...handlers[0], option_fields: [] }] as T
      if (path.includes('/errors?')) return [] as T
      if (path.includes('/detail?')) return jobs[0] as T
      return pageResult(path) as T
    }
    const wrapper = await render(request)
    wrapper.getComponent(ElSelect).vm.$emit('update:modelValue', 'members-0')
    await flushPromises()
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(commands).toHaveLength(1)
    await button(wrapper, '查看已受理任务').trigger('click')
    await flushPromises()
    expect(commands).toHaveLength(1)
    expect(wrapper.text()).toContain('任务详情')
  })

  it('先选处理器，再显示有可见帮助的适用模式和CSV上传步骤', async () => {
    const available = handlers.map((handler) => ({
      ...handler,
      can_import: true,
      option_fields: []
    }))
    const request: FoundationRequest = async <T>(_method: string, path: string) =>
      (path.endsWith('/handlers') ? available : pageResult(path)) as T
    const wrapper = await render(request)
    expect(wrapper.find('input[type="file"]').exists()).toBe(false)
    expect(wrapper.findAll('input[type="radio"]')).toHaveLength(0)
    wrapper.getComponent(ElSelect).vm.$emit('update:modelValue', 'members-0')
    await flushPromises()
    const controls = [
      wrapper.get('.el-form .el-select [role="combobox"]'),
      ...wrapper.findAll('input[type="radio"]'),
      wrapper.get('input[type="file"]'),
      wrapper.get('.el-pagination [role="combobox"]')
    ]
    expect(controls).toHaveLength(5)
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
  })

  it('27任务分页呈现137行部分成功与错误，不把完成行等同全部成功', async () => {
    const reads: string[] = []
    const request: FoundationRequest = async <T>(_method: string, path: string) => {
      reads.push(path)
      return (path.endsWith('/handlers') ? handlers : pageResult(path)) as T
    }
    const wrapper = await render(request)
    expect(wrapper.findAll('.el-table__body tbody tr')).toHaveLength(20)
    expect(wrapper.text()).toContain('错误 19')
    expect(wrapper.text()).toContain('失败')
    wrapper.getComponent(ElPagination).vm.$emit('current-change', 2)
    await flushPromises()
    expect(wrapper.findAll('.el-table__body tbody tr')).toHaveLength(7)
    expect(wrapper.text()).toContain('job-26')
    expect(reads.some((path) => path.includes('page=2&page_size=20'))).toBe(true)
  })

  it('动态处理器选项只提交声明字段，未知回执保留同幂等键并禁重复在途写', async () => {
    let reject!: (error: unknown) => void
    const commands: Array<Record<string, unknown>> = []
    const request: FoundationRequest = <T>(method: string, path: string, body?: unknown) => {
      if (method === 'GET')
        return Promise.resolve((path.endsWith('/handlers') ? handlers : pageResult(path)) as T)
      commands.push(body as Record<string, unknown>)
      return new Promise<T>((_resolve, fail) => {
        reject = fail
      })
    }
    const wrapper = await render(request)
    wrapper.getComponent(ElSelect).vm.$emit('update:modelValue', 'members-1')
    await flushPromises()
    await wrapper.get('input[placeholder="指定部门"]').setValue('department-depth-4')
    await wrapper.get('input[placeholder="多个角色"]').setValue('reader,auditor,finance')
    await wrapper
      .get('input[placeholder="结构化筛选JSON"]')
      .setValue('{"departments":["D1","D2"],"roles":["reader","auditor"]}')
    await wrapper.get('form').trigger('submit')
    await wrapper.get('form').trigger('submit')
    expect(commands).toHaveLength(1)
    expect(commands[0]).toMatchObject({
      handler: 'members-1',
      operation: 'export',
      options: { department_id: 'department-depth-4', roles: 'reader,auditor,finance' }
    })
    expect(commands[0]!.options).not.toHaveProperty('tenant_id')
    const key = commands[0]!.idempotency_key
    reject(new Error('网络回执未知'))
    await flushPromises()
    expect(document.querySelector('.el-message--error')?.textContent).toContain('网络回执未知')
    await wrapper.get('form').trigger('submit')
    expect(commands[1]!.idempotency_key).toBe(key)
    reject(new Error('尚待核验'))
    await flushPromises()
  })

  it('取消确认期间换宿主，不将旧job发送给新租户且销毁轮询', async () => {
    let confirm!: (value: 'confirm') => void
    vi.spyOn(ElMessageBox, 'confirm').mockImplementation(
      () =>
        new Promise((resolve) => {
          confirm = resolve
        })
    )
    const writes: unknown[] = [],
      running = [{ ...jobs[0]!, state: 'running' }, ...jobs.slice(1)]
    const request: FoundationRequest = async <T>(method: string, path: string, body?: unknown) => {
      if (method !== 'GET') writes.push(body)
      return (path.endsWith('/handlers') ? handlers : pageResult(path, running)) as T
    }
    const wrapper = await render(request)
    await button(wrapper, '取消').trigger('click')
    await wrapper.setProps({
      request: async <T>(_method: string, path: string) =>
        (path.endsWith('/handlers') ? handlers : pageResult(path)) as T,
      apiBase: '/admin/foundation',
      permissions: ['foundation.jobs.read']
    })
    await flushPromises()
    confirm('confirm')
    await flushPromises()
    expect(writes).toEqual([])
    expect(wrapper.find('form').exists()).toBe(false)
    expect(wrapper.findAll('button').some((item) => item.text() === '取消')).toBe(false)
  })
  it('字典任务按需读取真实目录，选择名称后只提交稳定编码', async () => {
    const request = vi.fn(async (method: string, path: string) => {
      if (method === 'POST') return jobs[0]
      if (path.endsWith('/handlers'))
        return [
          {
            code: 'dictionary_values',
            title: '字典值',
            can_import: false,
            can_export: true,
            columns: ['code', 'label'],
            option_fields: [{ code: 'dictionary_code', label: '字典', required: true }]
          }
        ]
      if (path.endsWith('/catalog/dictionaries'))
        return [{ code: 'contract_status', name: '合同状态', enabled: true }]
      if (path.includes('/errors?')) return []
      if (path.includes('/detail?')) return jobs[0]
      return pageResult(path)
    }) as FoundationRequest
    const wrapper = await render(request, [
      'foundation.jobs.read',
      'foundation.jobs.create',
      'foundation.dictionary.read'
    ])
    expect(
      vi.mocked(request).mock.calls.some(([, path]) => path.endsWith('/catalog/dictionaries'))
    ).toBe(false)
    wrapper.getComponent(ElSelect).vm.$emit('update:modelValue', 'dictionary_values')
    await flushPromises()
    expect(
      vi
        .mocked(request)
        .mock.calls.some(([, path]) => path === '/tenant/foundation/catalog/dictionaries')
    ).toBe(true)
    const selectors = wrapper.findAllComponents(ElSelect)
    expect(
      selectors[1]!.findAllComponents({ name: 'ElOption' }).map((item) => item.props('label'))
    ).toEqual(['合同状态 · contract_status'])
    selectors[1]!.vm.$emit('update:modelValue', 'contract_status')
    await flushPromises()
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(vi.mocked(request).mock.calls.find(([method]) => method === 'POST')?.[2]).toMatchObject({
      handler: 'dictionary_values',
      operation: 'export',
      options: { dictionary_code: 'contract_status' }
    })
    expect(wrapper.getComponent(ElSelect).props('disabled')).toBe(true)
    await button(wrapper, '新建任务').trigger('click')
    expect(wrapper.getComponent(ElSelect).props('disabled')).toBe(false)
  })
})

it('未知提交后冻结编辑，重试使用原完整命令而不生成新key', async () => {
  const commands: unknown[] = []
  const request: FoundationRequest = async <T>(method: string, path: string, body?: unknown) => {
    if (method === 'POST') {
      commands.push(structuredClone(body))
      throw Error('网络回执未知')
    }
    return (
      path.endsWith('/handlers') ? handlers : path.includes('/errors?') ? [] : pageResult(path)
    ) as T
  }
  const wrapper = await render(request)
  wrapper.getComponent(ElSelect).vm.$emit('update:modelValue', 'members-1')
  await flushPromises()
  await wrapper.get('input[placeholder="指定部门"]').setValue('原部门')
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  expect(wrapper.getComponent(ElSelect).props('disabled')).toBe(true)
  expect(wrapper.get('input[placeholder="指定部门"]').attributes('disabled')).toBeDefined()
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  expect(commands).toHaveLength(2)
  expect(commands[1]).toEqual(commands[0])
})
it('A错误成功后选择B而B错误读取失败，不能显示A的错误材料', async () => {
  const request: FoundationRequest = async <T>(_method: string, path: string) => {
    if (path.includes('/errors?')) {
      if (path.includes('job-0'))
        return [{ row_number: 1, field: 'name', message: '仅属于A的纠正说明' }] as T
      throw Error('B错误读取失败')
    }
    return (path.endsWith('/handlers') ? handlers : pageResult(path)) as T
  }
  const wrapper = await render(request)
  const details = () => wrapper.findAll('button').filter((item) => item.text() === '详情')
  await details()[0]!.trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('仅属于A的纠正说明')
  await details()[1]!.trigger('click')
  await flushPromises()
  expect(wrapper.text()).not.toContain('仅属于A的纠正说明')
  expect(wrapper.text()).toContain('job-1')
})

async function prepareExport(wrapper: VueWrapper) {
  wrapper.getComponent(ElSelect).vm.$emit('update:modelValue', 'members-1')
  await flushPromises()
  await wrapper.get('input[placeholder="指定部门"]').setValue('原部门')
}
function terminalReads(path: string) {
  if (path.endsWith('/handlers')) return handlers
  if (path.includes('/errors?')) return []
  if (path.includes('/detail?')) return jobs[0]
  return pageResult(path)
}
it.each(['503', 'broken-error', 'broken-success'])(
  '不确定回执%s冻结原提交，重试body/key相同',
  async (kind) => {
    const commands: unknown[] = []
    const request: FoundationRequest = async <T>(method: string, path: string, body?: unknown) => {
      if (method !== 'POST') return terminalReads(path) as T
      commands.push(structuredClone(body))
      if (kind === 'broken-success') return {} as T
      throw new HttpClientError({ status: kind === '503' ? 503 : 400, userMessage: '结果未知' })
    }
    const wrapper = await render(request)
    await prepareExport(wrapper)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.getComponent(ElSelect).props('disabled')).toBe(true)
    expect(button(wrapper, '重试原提交')).toBeDefined()
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(commands[1]).toEqual(commands[0])
  }
)
it('首次完整拒绝恢复编辑并使用新key，旧UNKNOWN后续拒绝仍锁定原key', async () => {
  const commands: Array<Record<string, unknown>> = []
  let failure: Error = new HttpClientError({
    status: 422,
    code: 'INVALID',
    traceId: 'trace',
    userMessage: '明确拒绝'
  })
  const request: FoundationRequest = async <T>(method: string, path: string, body?: unknown) => {
    if (method !== 'POST') return terminalReads(path) as T
    commands.push(structuredClone(body) as Record<string, unknown>)
    throw failure
  }
  const wrapper = await render(request)
  await prepareExport(wrapper)
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  expect(wrapper.getComponent(ElSelect).props('disabled')).toBe(false)
  failure = new HttpClientError({ status: 0, userMessage: '回执未知' })
  await wrapper.get('input[placeholder="指定部门"]').setValue('修改后部门')
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  expect(commands[1]!.idempotency_key).not.toBe(commands[0]!.idempotency_key)
  failure = new HttpClientError({
    status: 403,
    code: 'FORBIDDEN',
    traceId: 'trace',
    userMessage: '权限拒绝'
  })
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  expect(wrapper.getComponent(ElSelect).props('disabled')).toBe(true)
  expect(commands[2]).toEqual(commands[1])
})
it('查看已受理任务始终指向本次任务，选历史详情不改原提交身份', async () => {
  let writes = 0
  const request: FoundationRequest = async <T>(method: string, path: string) => {
    if (method === 'POST') {
      writes++
      return jobs[0] as T
    }
    return terminalReads(path) as T
  }
  const wrapper = await render(request)
  await prepareExport(wrapper)
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  await wrapper
    .findAll('button')
    .filter((item) => item.text() === '详情')[1]!
    .trigger('click')
  await flushPromises()
  expect(wrapper.get('.el-descriptions').text()).toContain('job-1')
  await button(wrapper, '查看已受理任务').trigger('click')
  await flushPromises()
  expect(wrapper.get('.el-descriptions').text()).toContain('job-0')
  expect(writes).toBe(1)
})
it('逐行错误失败可单独重读，不覆盖新任务草稿或重发任务', async () => {
  const reads: string[] = []
  let fail = false
  const request: FoundationRequest = async <T>(method: string, path: string) => {
    reads.push(`${method} ${path}`)
    if (path.includes('/errors?')) {
      if (!path.includes('job-1'))
        return [{ row_number: 1, field: 'name', message: 'A专属错误' }] as T
      if (fail) throw Error('B错误失败')
      return [{ row_number: 2, field: 'name', message: 'B专属错误' }] as T
    }
    return terminalReads(path) as T
  }
  const wrapper = await render(request)
  const details = () => wrapper.findAll('button').filter((item) => item.text() === '详情')
  await details()[0]!.trigger('click')
  await flushPromises()
  fail = true
  await details()[1]!.trigger('click')
  await flushPromises()
  await prepareExport(wrapper)
  const count = reads.length
  fail = false
  await button(wrapper, '重试读取逐行错误').trigger('click')
  await flushPromises()
  expect(reads.slice(count)).toEqual([
    'GET /tenant/foundation/jobs/errors?job_id=job-1&page_size=200'
  ])
  expect(wrapper.text()).toContain('B专属错误')
  expect(wrapper.text()).not.toContain('A专属错误')
  expect((wrapper.get('input[placeholder="指定部门"]').element as HTMLInputElement).value).toBe(
    '原部门'
  )
  expect(wrapper.get('.el-descriptions').text()).toContain('job-1')
})
it('提交新B不复用A错误，A迟到错误也不能挂到B', async () => {
  let finishA!: (value: unknown) => void
  const request: FoundationRequest = async <T>(method: string, path: string) => {
    if (method === 'POST') return jobs[1] as T
    if (path.includes('/errors?')) {
      if (path.includes('job-0'))
        return await new Promise<T>((resolve) => {
          finishA = resolve
        })
      throw Error('B错误失败')
    }
    return terminalReads(path) as T
  }
  const wrapper = await render(request)
  await button(wrapper, '详情').trigger('click')
  await flushPromises()
  await prepareExport(wrapper)
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  finishA([{ row_number: 1, field: 'name', message: '迟到A错误' }])
  await flushPromises()
  expect(wrapper.text()).not.toContain('迟到A错误')
  expect(wrapper.get('.el-descriptions').text()).toContain('job-1')
  expect(button(wrapper, '重试读取逐行错误')).toBeDefined()
})
it.each(['success', 'failure'])('旧宿主提交迟到%s不恢复pending或发布原任务', async (outcome) => {
  let finish!: (value: unknown) => void
  let reject!: (reason: Error) => void
  const old: FoundationRequest = async <T>(method: string, path: string) => {
    if (method === 'POST')
      return await new Promise<T>((resolve, fail) => {
        finish = resolve
        reject = fail
      })
    return terminalReads(path) as T
  }
  const wrapper = await render(old)
  await prepareExport(wrapper)
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  const next: FoundationRequest = async <T>(_method: string, path: string) =>
    terminalReads(path) as T
  await wrapper.setProps({ request: next, apiBase: '/admin/foundation' })
  await flushPromises()
  if (outcome === 'success') finish(jobs[0])
  else reject(Error('旧提交未知'))
  await flushPromises()
  expect(wrapper.text()).not.toContain('原提交结果尚未确认')
  expect(wrapper.text()).not.toContain('任务已受理：')
  expect(wrapper.find('.el-descriptions').exists()).toBe(false)
  expect(wrapper.getComponent(ElSelect).props('disabled')).toBe(false)
})

it('空选项导出UNKNOWN本身触发离开保护，成功原回执后解除未知且不重放', async () => {
  const commands: unknown[] = []
  let succeed = false
  const request: FoundationRequest = async <T>(method: string, path: string, body?: unknown) => {
    if (method === 'POST') {
      commands.push(structuredClone(body))
      if (!succeed) throw new HttpClientError({ status: 0, userMessage: '回执未知' })
      return jobs[0] as T
    }
    if (path.endsWith('/handlers')) return [{ ...handlers[0], option_fields: [] }] as T
    return terminalReads(path) as T
  }
  const wrapper = await render(request)
  wrapper.getComponent(ElSelect).vm.$emit('update:modelValue', 'members-0')
  await flushPromises()
  const before = new Event('beforeunload', { cancelable: true })
  window.dispatchEvent(before)
  expect(before.defaultPrevented).toBe(false)
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  const unknown = new Event('beforeunload', { cancelable: true })
  window.dispatchEvent(unknown)
  expect(unknown.defaultPrevented).toBe(true)
  succeed = true
  await button(wrapper, '重试原提交').trigger('click')
  await flushPromises()
  expect(commands).toHaveLength(2)
  expect(commands[1]).toEqual(commands[0])
  expect(wrapper.text()).not.toContain('原提交结果尚未确认')
  const accepted = new Event('beforeunload', { cancelable: true })
  window.dispatchEvent(accepted)
  expect(accepted.defaultPrevented).toBe(false)
  await button(wrapper, '查看已受理任务').trigger('click')
  await flushPromises()
  expect(commands).toHaveLength(2)
})
