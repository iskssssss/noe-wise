import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ElementPlus, { ElMessageBox, ElPagination } from 'element-plus'
import FoundationFilesPage from './FoundationFilesPage.vue'
import { HttpClientError } from '../../utils/http'
import type { FoundationFile, FoundationRequest } from './contracts'

const files: FoundationFile[] = Array.from({ length: 47 }, (_, i) => ({
  id: String(9007199254740993n + BigInt(i)),
  name: `合同-${i}-中文.pdf`,
  mime_type: 'application/pdf',
  size_bytes: 4096 + i,
  sha256: `${i}`.padStart(64, '0'),
  reference_count: i % 3,
  created_at: '2026-10-12T08:01:02+08:00'
}))
const mounted: VueWrapper[] = []
function result(path: string, data = files) {
  const url = new URL(path, 'http://fixture'),
    page = Number(url.searchParams.get('page') || 1),
    size = Number(url.searchParams.get('page_size') || 20)
  return {
    items: data.slice((page - 1) * size, page * size),
    total: data.length,
    page,
    page_size: size
  }
}
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
  for (const wrapper of mounted.splice(0)) wrapper.unmount()
  document.body.innerHTML = ''
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})
function render(
  request: FoundationRequest,
  permissions = ['foundation.files.read', 'foundation.files.upload', 'foundation.files.delete']
) {
  const wrapper = mount(FoundationFilesPage, {
    props: { request, apiBase: '/tenant/foundation', permissions },
    attachTo: document.body,
    global: { plugins: [ElementPlus] }
  })
  mounted.push(wrapper)
  return wrapper
}

describe('复杂公共文件目录及宿主隔离', () => {
  it('读取失败不显示成功空态，重新查询成功后才显示暂无公共文件', async () => {
    const request = vi
      .fn()
      .mockRejectedValueOnce(new Error('文件列表暂不可用'))
      .mockResolvedValueOnce({
        items: [],
        total: 0,
        page: 1,
        page_size: 20
      }) as unknown as FoundationRequest
    const wrapper = render(request)
    await flushPromises()
    expect(wrapper.text()).toContain('文件列表尚未读取成功')
    expect(wrapper.text()).not.toContain('暂无公共文件')
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '刷新')!
      .trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('暂无公共文件')
    expect(vi.mocked(request).mock.calls.every(([method]) => method === 'GET')).toBe(true)
  })
  it('原生上传和分页条数输入都有唯一可见的非空帮助', async () => {
    const request: FoundationRequest = async <T>(_method: string, path: string) => result(path) as T
    const wrapper = render(request)
    await flushPromises()
    for (const selector of ['input[type="file"]', '.el-pagination [role="combobox"]']) {
      const control = wrapper.get(selector)
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

  it('47文件分页和大小切换单次读取，精确ID和引用保护随页保持', async () => {
    const calls: string[] = []
    const request: FoundationRequest = async <T>(_method: string, path: string) => {
      calls.push(path)
      return result(path) as T
    }
    const wrapper = render(request)
    await flushPromises()
    expect(wrapper.findAll('.el-table__body tbody tr')).toHaveLength(20)
    wrapper.getComponent(ElPagination).vm.$emit('current-change', 2)
    await flushPromises()
    expect(wrapper.text()).toContain(files[20]!.name)
    wrapper.getComponent(ElPagination).vm.$emit('size-change', 50)
    await flushPromises()
    expect(calls).toEqual([
      '/tenant/foundation/files?page=1&page_size=20',
      '/tenant/foundation/files?page=2&page_size=20',
      '/tenant/foundation/files?page=1&page_size=50'
    ])
    expect(wrapper.findAll('.el-table__body tbody tr')).toHaveLength(47)
    const referenced = wrapper.findAll('.el-table__body tbody tr')[1]!
    expect(referenced.findAll('button').some((button) => button.text() === '删除')).toBe(false)
    expect(referenced.text()).toContain('使用中')
  })

  it('跨宿主切换丢弃旧机构47文件迟到页，不泄露旧文件及写入口', async () => {
    let finish!: (value: unknown) => void
    const old: FoundationRequest = <T>() =>
      new Promise<T>((resolve) => {
        finish = resolve
      })
    const wrapper = render(old)
    const nextFiles = files.map((file) => ({ ...file, name: `平台-${file.name}` }))
    const next: FoundationRequest = async <T>(_method: string, path: string) =>
      result(path, nextFiles) as T
    await wrapper.setProps({
      request: next,
      apiBase: '/admin/foundation',
      permissions: ['foundation.files.read']
    })
    await flushPromises()
    finish(result('/files?page=2'))
    await flushPromises()
    const names = wrapper
      .getComponent({ name: 'ElTable' })
      .props('data')
      .map((file: FoundationFile) => file.name)
    expect(names).toEqual(nextFiles.slice(0, 20).map((file) => file.name))
    expect(wrapper.find('input[type=file]').exists()).toBe(false)
    expect(wrapper.findAll('button').some((button) => button.text() === '删除')).toBe(false)
  })

  it('引用目录删除确认取消无写；真实拒绝不重放且保留47文件投影', async () => {
    const commands: unknown[] = []
    const request: FoundationRequest = async <T>(method: string, path: string, body?: unknown) => {
      if (method === 'GET') return result(path) as T
      commands.push(body)
      throw new Error('409: 文件新增业务引用')
    }
    const confirm = vi
      .spyOn(ElMessageBox, 'confirm')
      .mockRejectedValueOnce('cancel')
      .mockResolvedValueOnce('confirm')
    const wrapper = render(request)
    await flushPromises()
    const remove = () =>
      wrapper
        .findAll('.el-table__body tbody tr')[0]!
        .findAll('button')
        .find((button) => button.text() === '删除')!
    await remove().trigger('click')
    await flushPromises()
    expect(commands).toEqual([])
    await remove().trigger('click')
    await flushPromises()
    expect(commands).toEqual([{ file_id: files[0]!.id }])
    expect(document.querySelector('.el-message--error')?.textContent).toContain(
      '409: 文件新增业务引用'
    )
    expect(wrapper.getComponent(ElPagination).props('total')).toBe(47)
    expect(confirm).toHaveBeenCalledTimes(2)
    expect(wrapper.findAll('.el-table__body tbody tr')).toHaveLength(20)
  })
})

function clickFileDelete(wrapper: VueWrapper) {
  return wrapper
    .findAll('.el-table__body tbody tr')[0]!
    .findAll('button')
    .find((button) => button.text() === '删除')!
    .trigger('click')
}
it('删除成功后GET503保留确认移除的行和total，刷新只读恢复', async () => {
  let removed = false
  let failRead = true
  const calls: string[] = []
  const request: FoundationRequest = async <T>(method: string, path: string) => {
    calls.push(`${method} ${path}`)
    if (method === 'POST') {
      removed = true
      return null as T
    }
    if (removed && failRead) throw new HttpClientError({ status: 503, userMessage: '目录暂不可用' })
    return result(path, removed ? files.slice(1) : files) as T
  }
  vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm')
  const wrapper = render(request)
  await flushPromises()
  await clickFileDelete(wrapper)
  await flushPromises()
  expect(wrapper.text()).not.toContain(files[0]!.name)
  expect(wrapper.getComponent(ElPagination).props('total')).toBe(46)
  expect(wrapper.text()).not.toContain('暂无公共文件')
  expect(document.body.textContent).toContain('文件已删除，但列表刷新失败')
  expect(document.querySelectorAll('.el-message')).toHaveLength(1)
  failRead = false
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '刷新')!
    .trigger('click')
  await flushPromises()
  expect(calls.filter((call) => call.startsWith('POST'))).toHaveLength(1)
  expect(wrapper.getComponent(ElPagination).props('total')).toBe(46)
})

it('末页最后一行删除后只读取有效上一页，失败不显示成功空态', async () => {
  const items = files.slice(0, 21).map((file) => ({ ...file, reference_count: 0 }))
  const reads: string[] = []
  let removed = false
  const request: FoundationRequest = async <T>(method: string, path: string) => {
    if (method === 'POST') {
      removed = true
      return null as T
    }
    reads.push(path)
    if (removed) throw Error('刷新失败')
    return result(path, items) as T
  }
  vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm')
  const wrapper = render(request)
  await flushPromises()
  wrapper.getComponent(ElPagination).vm.$emit('current-change', 2)
  await flushPromises()
  await clickFileDelete(wrapper)
  await flushPromises()
  expect(reads).toEqual([
    '/tenant/foundation/files?page=1&page_size=20',
    '/tenant/foundation/files?page=2&page_size=20',
    '/tenant/foundation/files?page=1&page_size=20'
  ])
  expect(wrapper.getComponent(ElPagination).props('currentPage')).toBe(1)
  expect(wrapper.getComponent(ElPagination).props('total')).toBe(20)
  expect(wrapper.text()).not.toContain('暂无公共文件')
})
it.each(['refused', 'unknown'])('删除POST%s保留原行与total，不自动重发或刷新', async (kind) => {
  const request = vi.fn(async (method: string, path: string) => {
    if (method === 'POST')
      throw new HttpClientError({
        status: kind === 'refused' ? 409 : 0,
        userMessage: kind === 'refused' ? '文件被引用' : '结果尚未确认'
      })
    return result(path)
  }) as unknown as FoundationRequest
  vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm')
  const wrapper = render(request)
  await flushPromises()
  await clickFileDelete(wrapper)
  await flushPromises()
  expect(wrapper.text()).toContain(files[0]!.name)
  expect(wrapper.getComponent(ElPagination).props('total')).toBe(47)
  expect(vi.mocked(request).mock.calls.map(([method]) => method)).toEqual(['GET', 'POST'])
})
it('删除成功使之前在途列表失效，迟到旧列表不能复活已删行', async () => {
  let readCount = 0
  let finishOld!: (value: unknown) => void
  const request: FoundationRequest = async <T>(method: string, path: string) => {
    if (method === 'POST') return null as T
    readCount++
    if (readCount === 2)
      return await new Promise<T>((resolve) => {
        finishOld = resolve
      })
    if (readCount === 3) throw Error('删除后刷新失败')
    return result(path) as T
  }
  vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm')
  const wrapper = render(request)
  await flushPromises()
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '刷新')!
    .trigger('click')
  await flushPromises()
  await clickFileDelete(wrapper)
  await flushPromises()
  finishOld(result('/files'))
  await flushPromises()
  expect(wrapper.text()).not.toContain(files[0]!.name)
  expect(wrapper.getComponent(ElPagination).props('total')).toBe(46)
})
it('删除POST迟到成功不污染新宿主的文件和消息，不触发新宿主额外读取', async () => {
  let finishDelete!: (value: unknown) => void
  const old: FoundationRequest = async <T>(method: string, path: string) => {
    if (method === 'POST')
      return await new Promise<T>((resolve) => {
        finishDelete = resolve
      })
    return result(path) as T
  }
  vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm')
  const wrapper = render(old)
  await flushPromises()
  await clickFileDelete(wrapper)
  await flushPromises()
  const nextFiles = files.map((file) => ({ ...file, name: `平台-${file.name}` }))
  const next = vi.fn(async (_method: string, path: string) =>
    result(path, nextFiles)
  ) as unknown as FoundationRequest
  await wrapper.setProps({
    request: next,
    apiBase: '/admin/foundation',
    permissions: ['foundation.files.read']
  })
  await flushPromises()
  finishDelete(null)
  await flushPromises()
  expect(wrapper.text()).toContain(nextFiles[0]!.name)
  expect(wrapper.getComponent(ElPagination).props('total')).toBe(47)
  expect(vi.mocked(next)).toHaveBeenCalledTimes(1)
  expect(document.body.textContent).not.toContain('文件已删除')
})

it('先删除成功再删除另一文件被拒绝，只展示最新失败一条Message', async () => {
  let writes = 0
  const items = files.slice(0, 3).map((file) => ({ ...file, reference_count: 0 }))
  const request: FoundationRequest = async <T>(method: string, path: string) => {
    if (method === 'POST') {
      if (++writes === 2)
        throw new HttpClientError({ status: 409, userMessage: '第二个文件已被引用' })
      return null as T
    }
    if (writes) throw Error('列表刷新失败')
    return result(path, items) as T
  }
  vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm')
  const wrapper = render(request)
  await flushPromises()
  await clickFileDelete(wrapper)
  await flushPromises()
  expect(document.body.textContent).toContain('文件已删除，但列表刷新失败')
  await clickFileDelete(wrapper)
  await flushPromises()
  expect(document.querySelectorAll('.el-message')).toHaveLength(1)
  expect(document.body.textContent).toContain('第二个文件已被引用')
  expect(document.body.textContent).not.toContain('文件已删除')
  expect(wrapper.getComponent(ElPagination).props('total')).toBe(2)
  expect(writes).toBe(2)
})
