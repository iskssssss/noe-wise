import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { ElButton, ElFormItem, ElSelect, ElTabs } from 'element-plus'
import { createPermissionDirective } from '@skillforge/shared/directives/permission'
import { HttpClientError } from '@skillforge/shared/utils/http'
import { computed, nextTick, reactive } from 'vue'
import KnowledgeManagePage from './KnowledgeManagePage.vue'

const mocks = vi.hoisted(() => ({
  base: vi.fn(),
  revisions: vi.fn(),
  models: vi.fn(),
  documents: vi.fn(),
  document: vi.fn(),
  chunks: vi.fn(),
  updateDocument: vi.fn(),
  reindex: vi.fn(),
  repairProjection: vi.fn(),
  removeDocument: vi.fn(),
  rebuild: vi.fn(),
  post: vi.fn(),
  confirm: vi.fn(),
  session: 'session',
  success: vi.fn(),
  showMessage: vi.fn()
}))
const authState = reactive({
  user: {
    tenant_id: '1',
    sub: '1',
    permissions: ['foundation.knowledge.read', 'foundation.knowledge.publish']
  },
  identityVersion: 0,
  sessionIdentity: 'session'
})
const routeState = reactive({ params: { id: 'kb-1' } })
vi.mock('../../stores/auth', () => ({ useAuthStore: () => authState }))
vi.mock('../../api', () => ({
  getSessionId: () => mocks.session,
  api: {
    knowledge: {
      getBase: mocks.base,
      listRevisions: mocks.revisions,
      listDocuments: mocks.documents,
      getDocument: mocks.document,
      listChunks: mocks.chunks,
      reindexDocument: mocks.reindex,
      repairProjection: mocks.repairProjection,
      removeDocument: mocks.removeDocument,
      updateDocument: mocks.updateDocument
    },
    settings: { availableModels: mocks.models }
  },
  get: mocks.rebuild,
  post: mocks.post
}))
vi.mock('vue-router', () => ({
  useRoute: () => routeState,
  onBeforeRouteLeave: vi.fn(),
  onBeforeRouteUpdate: vi.fn()
}))
vi.mock('@skillforge/shared/components/CodeEditor.vue', () => ({
  default: { template: '<div />' }
}))
vi.mock('@skillforge/shared/feedback', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@skillforge/shared/feedback')>()
  return {
    ...actual,
    confirm: mocks.confirm,
    showMessage: mocks.showMessage,
    message: { ...actual.message, success: mocks.success }
  }
})
let wrapper: VueWrapper
const documentFixture = {
  id: 'doc-1',
  kb_id: 'kb-1',
  title: '原文标题',
  content: '原文正文',
  source_version: 'v1'
}
const job = (status = 'IDLE') => ({
  job_id: status === 'IDLE' ? null : 'job-1',
  kb_id: 'kb-1',
  status,
  revision: 2,
  base_revision: 3,
  document_count: 1,
  ready_documents: status === 'READY' ? 1 : 0,
  unknown_batches: 0,
  write_locked: ['RUNNING', 'READY'].includes(status)
})
beforeEach(() => {
  vi.resetAllMocks()
  sessionStorage.clear()
  mocks.session = 'session'
  routeState.params.id = 'kb-1'
  authState.identityVersion = 0
  authState.sessionIdentity = 'session'
  authState.user.permissions = ['foundation.knowledge.read', 'foundation.knowledge.publish']
  mocks.base.mockResolvedValue({ id: 'kb-1', name: '知识库', indexing_mode: 'high_quality' })
  mocks.revisions.mockResolvedValue({ items: [] })
  mocks.models.mockResolvedValue([])
  mocks.documents.mockResolvedValue({
    items: [{ ...documentFixture, status: 'ready', chunk_count: 1 }],
    total: 1
  })
  mocks.document.mockResolvedValue(documentFixture)
  mocks.updateDocument.mockResolvedValue({ ...documentFixture, source_version: 'v2' })
  mocks.chunks.mockResolvedValue({
    items: [
      {
        id: 'chunk-1',
        chunk_index: 0,
        content: '分块正文',
        source_version: 'v1',
        has_embedding: true
      }
    ],
    total: 1
  })
  mocks.rebuild.mockResolvedValue(job())
  mocks.confirm.mockResolvedValue(true)
  mocks.showMessage.mockReturnValue(() => {})
})
afterEach(() => {
  wrapper?.unmount()
  document.body.innerHTML = ''
  vi.useRealTimers()
})
async function open() {
  wrapper = mount(KnowledgeManagePage, { attachTo: document.body })
  await flushPromises()
}
async function click(text: string) {
  const button = wrapper.findAllComponents(ElButton).find((value) => value.text() === text)
  expect(button, text).toBeDefined()
  await button!.trigger('click')
  await flushPromises()
}
async function drawerTab(value: string) {
  wrapper.findAllComponents(ElTabs).at(-1)!.vm.$emit('update:modelValue', value)
  await flushPromises()
}
async function rebuildTab() {
  wrapper.findAllComponents(ElTabs)[0]!.vm.$emit('update:modelValue', 'rebuild')
  await flushPromises()
}

describe('知识详情按需读取与已发布状态恢复', () => {
  it.each([
    { permission: true, mode: 'high_quality', status: 'ready', source: 'v1', visible: true },
    { permission: false, mode: 'high_quality', status: 'ready', source: 'v1', visible: false },
    { permission: true, mode: 'economical', status: 'ready', source: 'v1', visible: false },
    { permission: true, mode: 'high_quality', status: 'failed', source: 'v1', visible: false },
    { permission: true, mode: 'high_quality', status: 'ready', source: '', visible: false }
  ])('投影修复入口遵循权限、索引方式与来源状态：$mode $status', async (scenario) => {
    authState.user.permissions = scenario.permission
      ? ['foundation.knowledge.read', 'foundation.knowledge.run']
      : ['foundation.knowledge.read']
    mocks.base.mockResolvedValue({ id: 'kb-1', name: '知识库', indexing_mode: scenario.mode })
    mocks.documents.mockResolvedValue({
      items: [
        {
          ...documentFixture,
          status: scenario.status,
          source_version: scenario.source,
          chunk_count: 1
        }
      ],
      total: 1
    })
    wrapper = mount(KnowledgeManagePage, {
      attachTo: document.body,
      global: {
        directives: {
          permission: createPermissionDirective(() => computed(() => authState.user.permissions))
        }
      }
    })
    await flushPromises()
    const visible = Array.from(document.body.querySelectorAll('button')).some(
      (button) => button.textContent?.trim() === '修复检索投影'
    )
    expect(visible).toBe(scenario.visible)
    expect(mocks.repairProjection).not.toHaveBeenCalled()
  })

  it('修复返回 503 后由用户显式重试同一来源，不重新索引或发布', async () => {
    mocks.repairProjection
      .mockRejectedValueOnce(new HttpClientError({ status: 503, userMessage: '投影修复未完成' }))
      .mockResolvedValueOnce({ source_version: 'v1', chunk_count: 1 })
    await open()
    await click('修复检索投影')
    expect(mocks.repairProjection).toHaveBeenCalledExactlyOnceWith('kb-1', 'v1')
    expect(mocks.success).not.toHaveBeenCalled()
    expect(mocks.reindex).not.toHaveBeenCalled()
    expect(mocks.post).not.toHaveBeenCalled()
    await click('修复检索投影')
    expect(mocks.repairProjection).toHaveBeenCalledTimes(2)
    expect(mocks.repairProjection).toHaveBeenLastCalledWith('kb-1', 'v1')
    expect(mocks.success).toHaveBeenCalledWith('检索投影已修复')
    expect(mocks.reindex).not.toHaveBeenCalled()
    expect(mocks.post).not.toHaveBeenCalled()
  })

  it('失败且无分块时引导补充正文并在详情持续解释，不提供无效索引重试', async () => {
    const emptyDocument = { ...documentFixture, content: ' ', status: 'failed', chunk_count: 0 }
    mocks.documents.mockResolvedValue({ items: [emptyDocument], total: 1 })
    mocks.document.mockResolvedValue({ ...documentFixture, content: ' ', status: 'failed' })
    await open()
    expect(wrapper.text()).toContain('无可检索正文')
    expect(wrapper.findAllComponents(ElButton).some((button) => button.text() === '重试索引')).toBe(
      false
    )
    await click('补充正文')
    expect(document.body.textContent).toContain('当前文档没有可检索分块，请补充正文后保存。')
    expect(mocks.document).toHaveBeenCalledExactlyOnceWith('doc-1')
    expect(mocks.reindex).not.toHaveBeenCalled()
  })

  it('有分块的失败文档保留编辑和重试索引入口', async () => {
    mocks.documents.mockResolvedValue({
      items: [{ ...documentFixture, status: 'failed', chunk_count: 1 }],
      total: 1
    })
    await open()
    const buttons = wrapper.findAllComponents(ElButton).map((button) => button.text())
    expect(buttons).toContain('编辑 / 分块')
    expect(buttons).toContain('重试索引')
    expect(buttons).not.toContain('补充正文')
  })

  it('浏览器会话更换后手动刷新成功，重新恢复自动观察', async () => {
    mocks.rebuild.mockResolvedValue(job('RUNNING'))
    await open()
    vi.useFakeTimers()
    wrapper.findAllComponents(ElTabs)[0]!.vm.$emit('update:modelValue', 'rebuild')
    await nextTick()
    mocks.session = 'new-session'
    await vi.advanceTimersByTimeAsync(5000)
    expect(mocks.rebuild).toHaveBeenCalledTimes(1)
    await wrapper
      .findAllComponents(ElButton)
      .find((item) => item.text() === '刷新重建进展')!
      .trigger('click')
    await Promise.resolve()
    await nextTick()
    expect(mocks.rebuild).toHaveBeenCalledTimes(2)
    await vi.advanceTimersByTimeAsync(5000)
    expect(mocks.rebuild).toHaveBeenCalledTimes(3)
    expect(mocks.post).not.toHaveBeenCalled()
  })
  it('运行状态读取始终单飞，不因定时触发并发请求', async () => {
    mocks.rebuild.mockResolvedValue(job('RUNNING'))
    await open()
    vi.useFakeTimers()
    wrapper.findAllComponents(ElTabs)[0]!.vm.$emit('update:modelValue', 'rebuild')
    await nextTick()
    let finish!: (value: unknown) => void
    mocks.rebuild.mockReturnValueOnce(
      new Promise((resolve) => {
        finish = resolve
      })
    )
    await vi.advanceTimersByTimeAsync(25000)
    expect(mocks.rebuild).toHaveBeenCalledTimes(2)
    finish(job('RUNNING'))
    await nextTick()
    await Promise.resolve()
    await vi.advanceTimersByTimeAsync(5000)
    expect(mocks.rebuild).toHaveBeenCalledTimes(3)
    expect(mocks.post).not.toHaveBeenCalled()
  })
  it('页面隐藏停止自动读取，恢复可见后才重新调度', async () => {
    mocks.rebuild.mockResolvedValue(job('RUNNING'))
    await open()
    vi.useFakeTimers()
    wrapper.findAllComponents(ElTabs)[0]!.vm.$emit('update:modelValue', 'rebuild')
    await nextTick()
    const visibility = vi.spyOn(window.document, 'visibilityState', 'get').mockReturnValue('hidden')
    try {
      window.document.dispatchEvent(new Event('visibilitychange'))
      await vi.advanceTimersByTimeAsync(10000)
      expect(mocks.rebuild).toHaveBeenCalledTimes(1)
      visibility.mockReturnValue('visible')
      window.document.dispatchEvent(new Event('visibilitychange'))
      await vi.advanceTimersByTimeAsync(5000)
      expect(mocks.rebuild).toHaveBeenCalledTimes(2)
    } finally {
      visibility.mockRestore()
    }
  })
  it('原发布结果未知时自动任务观察保持停止，不重复发布', async () => {
    const owner = JSON.stringify(['1', '1', 'kb-1'])
    sessionStorage.setItem(
      `skillforge.knowledge-rebuild-publication.${owner}`,
      JSON.stringify({
        requestId: '11111111-1111-4111-8111-111111111111',
        kbId: 'kb-1',
        jobId: 'job-1',
        jobRevision: 2,
        baseRevision: 3,
        policy: {
          model_invocable: true,
          user_invocable: true,
          allowed_operators: ['VECTOR'],
          freshness_policy: 'PINNED',
          citation_required: true
        }
      })
    )
    mocks.rebuild.mockImplementation(async (path: string) =>
      path.includes('publish-resolution') ? { status: 'UNKNOWN', snapshot: null } : job('RUNNING')
    )
    await open()
    const before = mocks.rebuild.mock.calls.length
    vi.useFakeTimers()
    wrapper.findAllComponents(ElTabs)[0]!.vm.$emit('update:modelValue', 'rebuild')
    await nextTick()
    await vi.advanceTimersByTimeAsync(20000)
    expect(mocks.rebuild).toHaveBeenCalledTimes(before)
    expect(mocks.post).not.toHaveBeenCalled()
  })
  it('运行任务限频只读更新至就绪后停止，保留用户筛选输入', async () => {
    mocks.rebuild.mockResolvedValue(job('RUNNING'))
    await open()
    await wrapper.get('input[aria-label="查找文档标题"]').setValue('未提交的筛选')
    vi.useFakeTimers()
    wrapper.findAllComponents(ElTabs)[0]!.vm.$emit('update:modelValue', 'rebuild')
    await nextTick()
    mocks.rebuild.mockResolvedValueOnce({ ...job('READY'), revision: 3 })
    await vi.advanceTimersByTimeAsync(5000)
    await nextTick()
    expect(mocks.rebuild).toHaveBeenCalledTimes(2)
    expect(wrapper.findAllComponents(ElButton).some((item) => item.text() === '发布重建结果')).toBe(
      true
    )
    expect(
      (wrapper.get('input[aria-label="查找文档标题"]').element as HTMLInputElement).value
    ).toBe('未提交的筛选')
    await vi.advanceTimersByTimeAsync(20000)
    expect(mocks.rebuild).toHaveBeenCalledTimes(2)
    expect(mocks.post).not.toHaveBeenCalled()
  })
  it('后台读取失败后停止轮询，不执行任何写请求', async () => {
    mocks.rebuild.mockResolvedValue(job('RUNNING'))
    await open()
    vi.useFakeTimers()
    wrapper.findAllComponents(ElTabs)[0]!.vm.$emit('update:modelValue', 'rebuild')
    await nextTick()
    mocks.rebuild.mockRejectedValueOnce(Error('读取失败'))
    await vi.advanceTimersByTimeAsync(5000)
    expect(wrapper.text()).toContain('自动读取重建进展已停止')
    await vi.advanceTimersByTimeAsync(20000)
    expect(mocks.rebuild).toHaveBeenCalledTimes(2)
    expect(mocks.post).not.toHaveBeenCalled()
  })
  it.each(['不同任务', '修订倒退'])('后台读取到%s时停止且不覆盖当前任务', async (kind) => {
    mocks.rebuild.mockResolvedValue(job('RUNNING'))
    await open()
    vi.useFakeTimers()
    wrapper.findAllComponents(ElTabs)[0]!.vm.$emit('update:modelValue', 'rebuild')
    await nextTick()
    mocks.rebuild.mockResolvedValueOnce(
      kind === '不同任务'
        ? { ...job('READY'), job_id: 'other', revision: 3 }
        : { ...job('READY'), revision: 1 }
    )
    await vi.advanceTimersByTimeAsync(5000)
    expect(wrapper.text()).toContain('自动读取重建进展已停止')
    expect(wrapper.findAllComponents(ElButton).some((item) => item.text() === '发布重建结果')).toBe(
      false
    )
    expect(mocks.post).not.toHaveBeenCalled()
  })
  it('用户取消时在途后台读取失效，不覆盖取消结果', async () => {
    mocks.rebuild.mockResolvedValue(job('RUNNING'))
    await open()
    vi.useFakeTimers()
    wrapper.findAllComponents(ElTabs)[0]!.vm.$emit('update:modelValue', 'rebuild')
    await nextTick()
    let finish!: (value: unknown) => void
    mocks.rebuild.mockReturnValueOnce(
      new Promise((resolve) => {
        finish = resolve
      })
    )
    await vi.advanceTimersByTimeAsync(5000)
    mocks.post.mockResolvedValueOnce({ ...job('CANCELLED'), revision: 3 })
    await wrapper
      .findAllComponents(ElButton)
      .find((item) => item.text() === '取消重建')!
      .trigger('click')
    await nextTick()
    await Promise.resolve()
    await nextTick()
    finish({ ...job('READY'), revision: 4 })
    await nextTick()
    await Promise.resolve()
    await nextTick()
    expect(wrapper.findAllComponents(ElButton).some((item) => item.text() === '继续重建')).toBe(
      true
    )
    expect(wrapper.findAllComponents(ElButton).some((item) => item.text() === '发布重建结果')).toBe(
      false
    )
    expect(mocks.post).toHaveBeenCalledTimes(1)
  })
  it('浏览器会话提前变化时丢弃在途状态，不再自动读取', async () => {
    mocks.rebuild.mockResolvedValue(job('RUNNING'))
    await open()
    vi.useFakeTimers()
    wrapper.findAllComponents(ElTabs)[0]!.vm.$emit('update:modelValue', 'rebuild')
    await nextTick()
    let finish!: (value: unknown) => void
    mocks.rebuild.mockReturnValueOnce(
      new Promise((resolve) => {
        finish = resolve
      })
    )
    await vi.advanceTimersByTimeAsync(5000)
    mocks.session = 'another-session'
    finish({ ...job('READY'), revision: 3 })
    await nextTick()
    await Promise.resolve()
    await nextTick()
    expect(wrapper.findAllComponents(ElButton).some((item) => item.text() === '发布重建结果')).toBe(
      false
    )
    await vi.advanceTimersByTimeAsync(10000)
    expect(mocks.rebuild).toHaveBeenCalledTimes(2)
    expect(mocks.post).not.toHaveBeenCalled()
  })
  it('重建发布的大整数 ID 从读取到原请求和回执全程保持精确字符串', async () => {
    const kb = '9007199254740993'
    const jobId = '9223372036854775807'
    routeState.params.id = kb
    mocks.base.mockResolvedValue({ id: kb, name: '知识库', indexing_mode: 'high_quality' })
    mocks.rebuild.mockResolvedValue({ ...job('READY'), kb_id: kb, job_id: jobId })
    mocks.post.mockResolvedValue({
      status: 'SUCCEEDED',
      snapshot: {
        ...job('PUBLISHED'),
        kb_id: kb,
        job_id: jobId,
        revision: 3,
        published_revision: 1
      }
    })
    await open()
    await rebuildTab()
    await click('发布重建结果')
    expect(mocks.post.mock.calls[0]![1]).toMatchObject({ kb_id: kb, job_id: jobId })
    expect(wrapper.text()).not.toContain('重建发布结果尚未确认')
    expect(mocks.success).toHaveBeenCalledWith('重建结果已发布')
    expect(sessionStorage.length).toBe(0)
  })
  it('旧记录中数值化的任务 ID 不被转换或发送，保留原键供人工核对', async () => {
    const requestId = '11111111-1111-4111-8111-111111111111'
    const key = `skillforge.knowledge-rebuild-publication.${JSON.stringify(['1', '1', 'kb-1'])}`
    sessionStorage.setItem(
      key,
      JSON.stringify({
        requestId,
        kbId: 'kb-1',
        jobId: JSON.parse('9007199254740993'),
        jobRevision: 2,
        baseRevision: 3,
        policy: {
          model_invocable: true,
          user_invocable: true,
          allowed_operators: ['VECTOR'],
          freshness_policy: 'PINNED',
          citation_required: true
        }
      })
    )
    await open()
    expect(wrapper.text()).toContain('原任务标识不完整，无法安全核对')
    expect(wrapper.text()).toContain(requestId)
    expect(mocks.post).not.toHaveBeenCalled()
    expect(
      mocks.rebuild.mock.calls.some((call) => String(call[0]).includes('publish-resolution'))
    ).toBe(false)
    expect(sessionStorage.getItem(key)).not.toBeNull()
  })
  it('重建发布返回 401 后刷新只读原请求，确认成功前不重发', async () => {
    mocks.rebuild.mockResolvedValue(job('READY'))
    mocks.post.mockRejectedValueOnce(new HttpClientError({ status: 401, userMessage: '登录失效' }))
    await open()
    await rebuildTab()
    await click('发布重建结果')
    const command = mocks.post.mock.calls[0]![1]
    expect(mocks.post.mock.calls[0]![2]).toEqual({ retryOnUnauthorized: false })
    expect(wrapper.text()).toContain('重建发布结果尚未确认')
    expect(sessionStorage.getItem(sessionStorage.key(0)!)).toContain(command.request_id)
    wrapper.unmount()
    mocks.rebuild.mockImplementation(async (path: string) =>
      path.includes('publish-resolution')
        ? { status: 'UNKNOWN', snapshot: null }
        : { ...job('READY'), job_id: 'newer-job' }
    )
    await open()
    expect(
      mocks.rebuild.mock.calls.some((call) =>
        String(call[0]).includes(`job_id=job-1&request_id=${command.request_id}`)
      )
    ).toBe(true)
    expect(wrapper.text()).toContain('重建发布结果尚未确认')
    expect(mocks.post).toHaveBeenCalledTimes(1)
    mocks.rebuild.mockImplementation(async (path: string) =>
      path.includes('publish-resolution')
        ? {
            status: 'SUCCEEDED',
            snapshot: { ...job('PUBLISHED'), revision: 3, published_revision: 1 }
          }
        : { ...job('READY'), job_id: 'newer-job' }
    )
    await click('核对原重建发布')
    expect(sessionStorage.length).toBe(0)
    expect(mocks.post).toHaveBeenCalledTimes(1)
  })
  it('只读权限可显式封存未知发布，断连后可同键再次封存', async () => {
    mocks.rebuild.mockResolvedValue(job('READY'))
    mocks.post.mockRejectedValueOnce(Error('原发布断连')).mockRejectedValueOnce(Error('封存断连'))
    await open()
    await rebuildTab()
    await click('发布重建结果')
    authState.user.permissions = ['foundation.knowledge.read']
    await flushPromises()
    await click('结束未确认的重建发布')
    const fenceCommand = mocks.post.mock.calls[1]![1]
    mocks.post.mockResolvedValueOnce({ status: 'FENCED', snapshot: null })
    await click('结束未确认的重建发布')
    expect(mocks.post.mock.calls[2]![1]).toEqual(fenceCommand)
    expect(mocks.post.mock.calls[1]![2]).toEqual({ retryOnUnauthorized: false })
    expect(mocks.post.mock.calls[2]![0]).toBe('/knowledge/bases/rebuilds/publish-fence')
    expect(sessionStorage.length).toBe(0)
  })
  it('核对返回不匹配任务时保留原请求并禁止重复发布', async () => {
    mocks.rebuild.mockResolvedValue(job('READY'))
    mocks.post.mockRejectedValueOnce(Error('断连'))
    await open()
    await rebuildTab()
    await click('发布重建结果')
    mocks.rebuild.mockResolvedValueOnce({
      status: 'SUCCEEDED',
      snapshot: { ...job('PUBLISHED'), job_id: 'other-job', revision: 3, published_revision: 1 }
    })
    await click('核对原重建发布')
    expect(wrapper.text()).toContain('重建发布结果尚未确认')
    expect(mocks.post).toHaveBeenCalledTimes(1)
    expect(sessionStorage.length).toBe(1)
  })
  it('原请求无法持久化时不发送重建发布', async () => {
    mocks.rebuild.mockResolvedValue(job('READY'))
    await open()
    await rebuildTab()
    const blocked = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw Error('blocked')
    })
    try {
      await click('发布重建结果')
      expect(mocks.post).not.toHaveBeenCalled()
      expect(wrapper.text()).toContain('无法保存原请求记录，未发送重建发布')
    } finally {
      blocked.mockRestore()
    }
  })
  it('初始文档读取在途时切换知识库，旧响应不能写入且忙碌结束后加载新库', async () => {
    let finish!: (value: unknown) => void
    mocks.base.mockImplementation(async (id: string) => ({
      id,
      name: id,
      indexing_mode: 'high_quality'
    }))
    mocks.documents.mockReturnValueOnce(
      new Promise((resolve) => {
        finish = resolve
      })
    )
    mocks.documents.mockResolvedValue({
      items: [{ ...documentFixture, title: '新库文档' }],
      total: 1
    })
    await open()
    routeState.params.id = 'kb-2'
    await flushPromises()
    finish({ items: [{ ...documentFixture, title: '旧库文档' }], total: 1 })
    await flushPromises()
    expect(mocks.base).toHaveBeenCalledWith('kb-2')
    expect(wrapper.text()).toContain('新库文档')
    expect(wrapper.text()).not.toContain('旧库文档')
  })
  it('初始文档读取在途时同主体重新登录，旧响应失效并重新加载', async () => {
    let finish!: (value: unknown) => void
    mocks.documents.mockReturnValueOnce(
      new Promise((resolve) => {
        finish = resolve
      })
    )
    mocks.documents.mockResolvedValue({
      items: [{ ...documentFixture, title: '新会话文档' }],
      total: 1
    })
    await open()
    mocks.session = 'new-session'
    authState.identityVersion++
    authState.sessionIdentity = 'new-session'
    await flushPromises()
    finish({ items: [{ ...documentFixture, title: '旧会话文档' }], total: 1 })
    await flushPromises()
    expect(mocks.base).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('新会话文档')
    expect(wrapper.text()).not.toContain('旧会话文档')
  })
  it('浏览器会话先变化而认证状态尚未同步时，普通列表不写入旧响应', async () => {
    let finish!: (value: unknown) => void
    mocks.documents.mockReturnValueOnce(
      new Promise((resolve) => {
        finish = resolve
      })
    )
    mocks.documents.mockResolvedValue({
      items: [{ ...documentFixture, title: '当前会话文档' }],
      total: 1
    })
    await open()
    mocks.session = 'new-session'
    finish({ items: [{ ...documentFixture, title: '旧会话迟到文档' }], total: 1 })
    await flushPromises()
    expect(wrapper.text()).not.toContain('旧会话迟到文档')
    expect(wrapper.text()).not.toContain('暂时无法确认操作结果')
    authState.identityVersion++
    authState.sessionIdentity = 'new-session'
    await flushPromises()
    expect(wrapper.text()).toContain('当前会话文档')
    expect(wrapper.text()).not.toContain('旧会话迟到文档')
  })
  it('经济索引禁止新建向量重建，现有就绪任务仍能查看、取消和发布', async () => {
    mocks.base.mockResolvedValue({
      id: 'kb-1',
      name: '知识库',
      indexing_mode: null,
      effective_indexing_mode: 'economical'
    })
    mocks.rebuild.mockResolvedValue(job('READY'))
    mocks.models.mockResolvedValue([{ id: 'model-1', name: '向量模型', kind: 'EMBEDDING' }])
    await open()
    await rebuildTab()
    const modelSelect = wrapper
      .findAllComponents(ElFormItem)
      .find((item) => item.props('label') === '目标向量模型')!
      .getComponent(ElSelect)
    expect(modelSelect.props('disabled')).toBe(true)
    expect(
      wrapper
        .findAllComponents(ElButton)
        .find((item) => item.text() === '刷新模型目录')!
        .props('disabled')
    ).toBe(true)
    const start = wrapper.findAllComponents(ElButton).find((item) => item.text() === '开始重建')!
    expect(start.props('disabled')).toBe(true)
    expect(wrapper.text()).toContain('当前为经济索引，仅支持全文检索')
    expect(wrapper.text()).not.toContain('当前索引方式无法确认')
    expect(wrapper.findAllComponents(ElButton).some((item) => item.text() === '刷新重建进展')).toBe(
      true
    )
    expect(wrapper.findAllComponents(ElButton).some((item) => item.text() === '取消重建')).toBe(
      true
    )
    expect(wrapper.findAllComponents(ElButton).some((item) => item.text() === '发布重建结果')).toBe(
      true
    )
    expect(
      wrapper
        .findAllComponents(ElButton)
        .find((item) => item.text() === '取消重建')!
        .props('disabled')
    ).toBe(false)
    expect(
      wrapper
        .findAllComponents(ElButton)
        .find((item) => item.text() === '发布重建结果')!
        .props('disabled')
    ).toBe(true)
    expect(mocks.post).not.toHaveBeenCalled()
  })
  it('经济索引的已有失败任务仍可显式重试', async () => {
    mocks.base.mockResolvedValue({ id: 'kb-1', name: '知识库', indexing_mode: 'economical' })
    mocks.rebuild.mockResolvedValue(job('FAILED'))
    mocks.post.mockResolvedValue(job('QUEUED'))
    await open()
    await rebuildTab()
    await click('重试失败批次')
    expect(mocks.post).toHaveBeenCalledOnce()
    expect(mocks.post.mock.calls[0]![0]).toBe('/knowledge/bases/rebuilds/retry')
  })
  it('索引方式未确定时不推断为向量索引', async () => {
    mocks.base.mockResolvedValue({ id: 'kb-1', name: '知识库', indexing_mode: null })
    mocks.rebuild.mockResolvedValue(job('READY'))
    await open()
    await rebuildTab()
    expect(wrapper.text()).toContain('当前索引方式无法确认')
    expect(
      wrapper
        .findAllComponents(ElButton)
        .find((item) => item.text() === '开始重建')!
        .props('disabled')
    ).toBe(true)
    expect(
      wrapper
        .findAllComponents(ElButton)
        .find((item) => item.text() === '发布重建结果')!
        .props('disabled')
    ).toBe(true)
    expect(
      wrapper
        .findAllComponents(ElButton)
        .find((item) => item.text() === '取消重建')!
        .props('disabled')
    ).toBe(false)
  })
  it('高质量索引仍可使用目标模型发起重建', async () => {
    mocks.base.mockResolvedValue({
      id: 'kb-1',
      name: '知识库',
      indexing_mode: null,
      effective_indexing_mode: 'high_quality'
    })
    mocks.models.mockResolvedValue([
      { id: 'model-1', name: '向量模型', kind: 'EMBEDDING', revision: 2 }
    ])
    mocks.post.mockResolvedValue(job('QUEUED'))
    await open()
    await rebuildTab()
    const modelSelect = wrapper
      .findAllComponents(ElFormItem)
      .find((item) => item.props('label') === '目标向量模型')!
      .getComponent(ElSelect)
    expect(modelSelect.props('disabled')).toBe(false)
    modelSelect.vm.$emit('update:modelValue', 'model-1')
    await flushPromises()
    await click('开始重建')
    expect(mocks.post).toHaveBeenCalledOnce()
    expect(mocks.post.mock.calls[0]![0]).toBe('/knowledge/bases/rebuilds/create')
  })
  it.each(['重新索引', '删除'])(
    '%s成功但读取失败时保留旧目录并只允许读取恢复',
    async (operation) => {
      mocks.reindex.mockResolvedValue({})
      mocks.removeDocument.mockResolvedValue({})
      await open()
      mocks.documents.mockRejectedValueOnce(new Error('文档列表暂不可读'))
      await click(operation)
      expect(mocks.success).toHaveBeenCalledWith(
        operation === '删除' ? '文档已删除' : '文档重建已提交'
      )
      expect(wrapper.text()).toContain('文档操作已完成，但列表尚未更新')
      expect(wrapper.text()).toContain('原文标题')
      const action = (label: string) =>
        wrapper.findAllComponents(ElButton).find((value) => value.text() === label)!
      expect(action('重新索引').props('disabled')).toBe(true)
      expect(action('删除').props('disabled')).toBe(true)
      expect(action('编辑 / 分块').props('disabled')).toBe(true)
      await click('重新索引')
      await click('删除')
      expect(mocks.reindex).toHaveBeenCalledTimes(operation === '重新索引' ? 1 : 0)
      expect(mocks.removeDocument).toHaveBeenCalledTimes(operation === '删除' ? 1 : 0)
      await click('读取最新文档')
      expect(wrapper.text()).not.toContain('文档操作已完成，但列表尚未更新')
      expect(action('重新索引').props('disabled')).toBe(false)
    }
  )

  it('文档首次读取失败时不显示空知识库，刷新成功后显示真实结果', async () => {
    mocks.documents.mockRejectedValueOnce(new Error('文档列表暂时不可读'))
    await open()
    const table = wrapper.findAllComponents({ name: 'ElTable' })[0]!
    expect(table.props('emptyText')).toBe('文档列表尚未读取成功，请点击刷新重试')
    expect(wrapper.text()).not.toContain('尚无文档')
    await click('刷新')
    expect(wrapper.text()).toContain('原文标题')
    expect(mocks.documents).toHaveBeenCalledTimes(2)
  })
  it('知识库基本信息读取失败时不显示空文档提示', async () => {
    mocks.base.mockRejectedValueOnce(new Error('知识库暂时不可读'))
    await open()
    expect(wrapper.findAllComponents({ name: 'ElTable' })[0]!.props('emptyText')).toBe(
      '文档列表尚未读取成功，请点击刷新重试'
    )
    expect(mocks.documents).not.toHaveBeenCalled()
    await click('刷新')
    expect(wrapper.text()).toContain('原文标题')
  })
  it('打开和保存原文不预读分块，首次查看分块读取一次，切回不重复读取', async () => {
    await open()
    await click('编辑 / 分块')
    expect(mocks.document).toHaveBeenCalledExactlyOnceWith('doc-1')
    expect(mocks.chunks).not.toHaveBeenCalled()
    await click('保存原文')
    expect(mocks.updateDocument).toHaveBeenCalledWith('doc-1', 'v1', {
      title: '原文标题',
      content: '原文正文'
    })
    expect(mocks.chunks).not.toHaveBeenCalled()
    await drawerTab('chunks')
    expect(mocks.chunks).toHaveBeenCalledExactlyOnceWith('doc-1', { page: 1, page_size: 20 })
    await drawerTab('source')
    await drawerTab('chunks')
    expect(mocks.chunks).toHaveBeenCalledTimes(1)
  })
  it('分块首次读取失败仍保留已打开的原文，重试只读取当前文档分块', async () => {
    await open()
    await click('编辑 / 分块')
    mocks.chunks.mockRejectedValueOnce(new Error('分块暂时不可读'))
    await drawerTab('chunks')
    expect(wrapper.text()).toContain('原文标题')
    await click('读取分块')
    expect(mocks.document).toHaveBeenCalledTimes(1)
    expect(mocks.chunks).toHaveBeenCalledTimes(2)
    expect(mocks.chunks).toHaveBeenLastCalledWith('doc-1', { page: 1, page_size: 20 })
  })
  it.each(['RUNNING', 'UNKNOWN'])(
    '刷新%s重建进展只读取任务状态，不重复查询文档、模型和修订',
    async (status) => {
      mocks.rebuild.mockResolvedValue(job(status))
      await open()
      await rebuildTab()
      await click('刷新重建进展')
      expect(mocks.rebuild).toHaveBeenCalledTimes(2)
      expect(mocks.base).toHaveBeenCalledTimes(1)
      expect(mocks.revisions).toHaveBeenCalledTimes(1)
      expect(mocks.models).toHaveBeenCalledTimes(1)
      expect(mocks.documents).toHaveBeenCalledTimes(1)
      expect(mocks.post).not.toHaveBeenCalled()
    }
  )
  it('明确模式冲突保留就绪任务，模式恢复后一次只读刷新允许显式发布', async () => {
    mocks.rebuild.mockResolvedValue(job('READY'))
    mocks.post.mockRejectedValueOnce(
      new HttpClientError({
        status: 409,
        code: 'KNOWLEDGE_REBUILD_MODE_CHANGED',
        userMessage: '模式冲突'
      })
    )
    await open()
    await rebuildTab()
    mocks.base.mockResolvedValueOnce({
      id: 'kb-1',
      indexing_mode: null,
      effective_indexing_mode: 'economical'
    })
    await click('发布重建结果')
    expect(wrapper.text()).toContain('当前索引方式与向量重建不兼容，原任务已保留')
    expect(
      wrapper
        .findAllComponents(ElButton)
        .find((item) => item.text() === '发布重建结果')!
        .props('disabled')
    ).toBe(true)
    expect(
      wrapper
        .findAllComponents(ElButton)
        .find((item) => item.text() === '取消重建')!
        .props('disabled')
    ).toBe(false)
    expect(mocks.post).toHaveBeenCalledTimes(1)
    expect(mocks.success).not.toHaveBeenCalled()
    mocks.base.mockResolvedValueOnce({
      id: 'kb-1',
      indexing_mode: null,
      effective_indexing_mode: 'high_quality'
    })
    await click('刷新重建进展')
    expect(
      wrapper
        .findAllComponents(ElButton)
        .find((item) => item.text() === '发布重建结果')!
        .props('disabled')
    ).toBe(false)
    expect(mocks.post).toHaveBeenCalledTimes(1)
  })
  it('发布网络异常不被当作模式冲突，也不自动重发', async () => {
    mocks.rebuild.mockResolvedValue(job('READY'))
    mocks.post.mockRejectedValueOnce(Error('连接中断'))
    await open()
    await rebuildTab()
    await click('发布重建结果')
    expect(wrapper.text()).not.toContain('当前索引方式与向量重建不兼容')
    expect(mocks.base).toHaveBeenCalledTimes(1)
    expect(mocks.post).toHaveBeenCalledTimes(1)
  })
  it('发布迟到错误在浏览器会话变化后不污染页面', async () => {
    mocks.rebuild.mockResolvedValue(job('READY'))
    let fail!: (reason: unknown) => void
    mocks.post.mockReturnValueOnce(
      new Promise((_resolve, reject) => {
        fail = reject
      })
    )
    await open()
    await rebuildTab()
    await click('发布重建结果')
    mocks.session = 'new-session'
    fail(new Error('旧会话发布失败'))
    await flushPromises()
    expect(wrapper.text()).not.toContain('旧会话发布失败')
    expect(mocks.base).toHaveBeenCalledTimes(1)
  })
  it('刷新迟到错误在浏览器会话变化后不污染页面', async () => {
    mocks.rebuild.mockResolvedValue(job('READY'))
    await open()
    await rebuildTab()
    let fail!: (reason: unknown) => void
    mocks.rebuild.mockReturnValueOnce(
      new Promise((_resolve, reject) => {
        fail = reject
      })
    )
    await click('刷新重建进展')
    mocks.session = 'new-session'
    fail(new Error('旧会话读取失败'))
    await flushPromises()
    expect(wrapper.text()).not.toContain('旧会话读取失败')
  })
  it('任务因模式变化停止时保留恢复入口', async () => {
    mocks.base.mockResolvedValue({ id: 'kb-1', name: '知识库', indexing_mode: 'economical' })
    mocks.rebuild.mockResolvedValue({
      ...job('FAILED'),
      error_code: 'KNOWLEDGE_REBUILD_MODE_CHANGED'
    })
    await open()
    await rebuildTab()
    expect(wrapper.text()).toContain('任务因索引方式变化而停止')
    expect(
      wrapper
        .findAllComponents(ElButton)
        .find((item) => item.text() === '重试失败批次')!
        .props('disabled')
    ).toBe(true)
    mocks.base.mockResolvedValueOnce({ id: 'kb-1', name: '知识库', indexing_mode: 'high_quality' })
    await click('刷新重建进展')
    expect(
      wrapper
        .findAllComponents(ElButton)
        .find((item) => item.text() === '重试失败批次')!
        .props('disabled')
    ).toBe(false)
    expect(mocks.post).not.toHaveBeenCalled()
  })
  it('发布后文档GET失败只反馈一次，保留成功回执并仅重试读取', async () => {
    mocks.rebuild.mockResolvedValue(job('READY'))
    mocks.post.mockResolvedValue({
      status: 'SUCCEEDED',
      snapshot: { ...job('PUBLISHED'), revision: 3, published_revision: 1 }
    })
    await open()
    await rebuildTab()
    mocks.documents.mockRejectedValueOnce(new Error('文档列表暂时不可读'))
    await click('发布重建结果')
    expect(mocks.success).toHaveBeenCalledWith('重建结果已发布')
    expect(wrapper.text()).toContain('重建结果已发布，知识库与文档信息尚未刷新')
    const errors = mocks.showMessage.mock.calls.filter((call) => call[0] === 'error')
    expect(errors).toHaveLength(1)
    expect(errors[0]![1]).toBe('文档列表暂时不可读')
    expect(mocks.documents).toHaveBeenCalledTimes(2)
    await click('重新读取发布信息')
    expect(mocks.post).toHaveBeenCalledTimes(1)
    expect(mocks.documents).toHaveBeenCalledTimes(3)
    expect(mocks.models).toHaveBeenCalledTimes(1)
    expect(mocks.rebuild).toHaveBeenCalledTimes(1)
    expect(mocks.showMessage.mock.calls.filter((call) => call[0] === 'error')).toHaveLength(1)
    expect(wrapper.text()).not.toContain('重建结果已发布，知识库与文档信息尚未刷新')
  })
  it('发布回执后定向刷新来源版本，刷新失败可只重试GET而不再次发布', async () => {
    mocks.rebuild.mockResolvedValue(job('READY'))
    mocks.post.mockResolvedValue({
      status: 'SUCCEEDED',
      snapshot: { ...job('PUBLISHED'), revision: 3, published_revision: 1 }
    })
    await open()
    await rebuildTab()
    mocks.base.mockRejectedValueOnce(new Error('发布信息暂时不可读'))
    await click('发布重建结果')
    expect(mocks.success).toHaveBeenCalledWith('重建结果已发布')
    expect(wrapper.text()).toContain('重建结果已发布，知识库与文档信息尚未刷新')
    expect(mocks.post).toHaveBeenCalledTimes(1)
    expect(mocks.post.mock.calls[0]![0]).toBe('/knowledge/bases/rebuilds/publish-with-receipt')
    await click('重新读取发布信息')
    expect(mocks.post).toHaveBeenCalledTimes(1)
    expect(mocks.models).toHaveBeenCalledTimes(1)
    expect(mocks.rebuild).toHaveBeenCalledTimes(1)
    expect(mocks.base).toHaveBeenCalledTimes(3)
    expect(mocks.documents).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).not.toContain('重建结果已发布，知识库与文档信息尚未刷新')
  })
  it.each(['base', 'revisions'] as const)(
    '发布后 %s 迟到错误在会话变化后忽略',
    async (resource) => {
      mocks.rebuild.mockResolvedValue(job('READY'))
      mocks.post.mockResolvedValue({
        status: 'SUCCEEDED',
        snapshot: { ...job('PUBLISHED'), revision: 3, published_revision: 1 }
      })
      await open()
      await rebuildTab()
      let fail!: (reason: unknown) => void
      mocks[resource].mockReturnValueOnce(
        new Promise((_resolve, reject) => {
          fail = reject
        })
      )
      await click('发布重建结果')
      mocks.session = 'new-session'
      fail(new Error('旧会话发布后读取失败'))
      await flushPromises()
      expect(wrapper.text()).not.toContain('旧会话发布后读取失败')
    }
  )
  it('发布后文档迟到响应在会话变化后不写入列表', async () => {
    mocks.rebuild.mockResolvedValue(job('READY'))
    mocks.post.mockResolvedValue({
      status: 'SUCCEEDED',
      snapshot: { ...job('PUBLISHED'), revision: 3, published_revision: 1 }
    })
    await open()
    await rebuildTab()
    let finish!: (value: unknown) => void
    mocks.documents.mockReturnValueOnce(
      new Promise((resolve) => {
        finish = resolve
      })
    )
    await click('发布重建结果')
    mocks.session = 'new-session'
    finish({ items: [{ ...documentFixture, title: '旧会话迟到文档' }], total: 1 })
    await flushPromises()
    expect(wrapper.text()).not.toContain('旧会话迟到文档')
    expect(wrapper.text()).toContain('重建结果已发布，知识库与文档信息尚未刷新')
  })
  it('发布后的文档读取使较早筛选失效，旧响应不能覆盖结果或回滚筛选', async () => {
    mocks.rebuild.mockResolvedValue(job('READY'))
    mocks.post.mockResolvedValue({
      status: 'SUCCEEDED',
      snapshot: { ...job('PUBLISHED'), revision: 3, published_revision: 1 }
    })
    await open()
    let finish!: (value: unknown) => void
    mocks.documents.mockReturnValueOnce(
      new Promise((resolve) => {
        finish = resolve
      })
    )
    await wrapper.get('input[aria-label="查找文档标题"]').setValue('目标')
    await wrapper.get('form.knowledge-document-filters').trigger('submit')
    await flushPromises()
    mocks.documents.mockResolvedValueOnce({
      items: [{ ...documentFixture, title: '目标新结果' }],
      total: 1
    })
    await rebuildTab()
    await click('发布重建结果')
    finish({ items: [{ ...documentFixture, title: '旧筛选响应' }], total: 1 })
    await flushPromises()
    expect(wrapper.text()).toContain('目标新结果')
    expect(wrapper.text()).not.toContain('旧筛选响应')
    expect(wrapper.text()).toContain('标题包含“目标”')
  })
  it('较新的文档筛选读取使在途发布读取失效，不回滚新筛选', async () => {
    mocks.rebuild.mockResolvedValue(job('READY'))
    mocks.post.mockResolvedValue({
      status: 'SUCCEEDED',
      snapshot: { ...job('PUBLISHED'), revision: 3, published_revision: 1 }
    })
    await open()
    await rebuildTab()
    let finish!: (value: unknown) => void
    mocks.documents.mockReturnValueOnce(
      new Promise((resolve) => {
        finish = resolve
      })
    )
    await click('发布重建结果')
    const state = wrapper.vm as unknown as {
      appliedDocumentFilters: { titleQuery: string; status: string }
      loadDocuments: () => Promise<unknown>
    }
    state.appliedDocumentFilters = { titleQuery: '新筛选', status: '' }
    mocks.documents.mockResolvedValueOnce({
      items: [{ ...documentFixture, title: '新筛选结果' }],
      total: 1
    })
    await state.loadDocuments()
    await flushPromises()
    finish({ items: [{ ...documentFixture, title: '旧发布响应' }], total: 1 })
    await flushPromises()
    expect(wrapper.text()).toContain('新筛选结果')
    expect(wrapper.text()).not.toContain('旧发布响应')
    expect(wrapper.text()).toContain('标题包含“新筛选”')
  })
})
