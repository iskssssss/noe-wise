import type { Component } from 'vue'
import { HttpClientError } from '@skillforge/shared/utils/http'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import {
  ElButton,
  ElPagination,
  ElInput,
  ElStatistic,
  ElUpload,
  ElSelect,
  ElForm,
  ElFormItem,
  ElDrawer,
  ElCheckbox,
  type UploadRawFile
} from 'element-plus'
import KnowledgeManagePage from './KnowledgeManagePage.vue'
import SkillWorkbenchPage from './SkillWorkbenchPage.vue'
import ConversationTasksPage from './ConversationTasksPage.vue'
import HomePage from './HomePage.vue'
import DashboardPage from './DashboardPage.vue'
import ChannelsPage from './ChannelsPage.vue'
const currentAuth = vi.hoisted(() => ({
  identityVersion: 1,
  user: {
    sub: 'institution-member',
    tenant_id: 'tenant-1',
    permissions: [
      'foundation.agents.read',
      'foundation.knowledge.read',
      'foundation.channels.read',
      'foundation.sessions.read'
    ]
  }
}))
vi.mock('../../stores/auth', () => ({ useAuthStore: () => currentAuth }))

const mocks = vi.hoisted(() => ({
  getDocument: vi.fn(),
  listChunks: vi.fn(),
  updateDocument: vi.fn(),
  updateChunk: vi.fn(),
  removeChunk: vi.fn(),
  saveSkill: vi.fn(),
  publishSkill: vi.fn(),
  validateSkill: vi.fn(),
  validateSkillContent: vi.fn(),
  getVersion: vi.fn(),
  rotateToken: vi.fn(),
  listDocuments: vi.fn(),
  getBase: vi.fn(),
  listRevisions: vi.fn(),
  availableModels: vi.fn(),
  get: vi.fn(),
  uploadFile: vi.fn(),
  uploadResult: vi.fn(),
  definition: vi.fn(),
  versions: vi.fn(),
  getActive: vi.fn(),
  importZip: vi.fn(),
  confirm: vi.fn(),
  usage: vi.fn(),
  health: vi.fn(),
  sessions: vi.fn(),
  tasks: vi.fn(),
  agents: vi.fn(),
  bases: vi.fn(),
  channels: vi.fn()
}))
vi.mock('../../api', () => ({
  api: {
    knowledge: {
      getDocument: mocks.getDocument,
      listChunks: mocks.listChunks,
      updateDocument: mocks.updateDocument,
      updateChunk: mocks.updateChunk,
      removeChunk: mocks.removeChunk,
      listDocuments: mocks.listDocuments,
      getBase: mocks.getBase,
      listRevisions: mocks.listRevisions,
      uploadFile: mocks.uploadFile,
      uploadResult: mocks.uploadResult,
      listBases: mocks.bases
    },
    settings: { availableModels: mocks.availableModels },
    capabilities: { getDefinition: mocks.definition, listSkillVersionSummaries: mocks.versions },
    skillDrafts: {
      save: mocks.saveSkill,
      publish: mocks.publishSkill,
      validate: mocks.validateSkill,
      validateContent: mocks.validateSkillContent,
      getVersion: mocks.getVersion,
      getActive: mocks.getActive,
      importZip: mocks.importZip
    },
    chat: { sessions: mocks.sessions },
    conversationTasks: { list: mocks.tasks },
    agents: { list: mocks.agents },
    channels: { list: mocks.channels, rotateToken: mocks.rotateToken },
    billing: { usage: mocks.usage },
    health: mocks.health
  },
  get: mocks.get,
  post: vi.fn()
}))
vi.mock('@skillforge/shared/feedback', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@skillforge/shared/feedback')>()),
  confirm: mocks.confirm
}))
vi.mock('vue-router', () => ({
  useRoute: () => ({
    params: { id: 'kb-1', definitionId: '2' },
    query: { sessionId: 'session-1' }
  }),
  useRouter: () => ({ push: vi.fn() }),
  onBeforeRouteLeave: vi.fn(),
  onBeforeRouteUpdate: vi.fn()
}))
vi.mock('@skillforge/shared/components/CodeEditor.vue', () => ({ default: { template: '<div/>' } }))
vi.mock('@skillforge/shared/components/PackageFileWorkbench.vue', () => ({
  default: { template: '<div/>' }
}))
const wrappers: VueWrapper[] = []
function render(component: Component) {
  const wrapper = mount(component, {
    attachTo: document.body,
    global: { stubs: { RouterLink: true } }
  })
  wrappers.push(wrapper)
  return wrapper
}
function button(wrapper: VueWrapper, label: string) {
  const found = wrapper.findAllComponents(ElButton).find((b) => b.text() === label)
  if (!found) throw Error('未找到按钮 ' + label)
  return found
}
function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (error: Error) => void
  const promise = new Promise<T>((yes, no) => {
    resolve = yes
    reject = no
  })
  return { promise, resolve, reject }
}
const draft = () => ({
  id: '4',
  definitionId: '2',
  requiresPlan: null,
  revision: 7,
  versionKey: '1.0.0',
  activationMode: 'ON_DEMAND',
  files: { 'SKILL.md': 'hello' },
  requiredCapabilities: [],
  modelInvocable: true,
  userInvocable: true,
  publishedVersionId: null
})
const rows = Array.from({ length: 30 }, (_, i) => ({
  id: String(i),
  title: '任务' + i,
  status: 'OPEN',
  revision: 1
}))
beforeEach(() => {
  vi.resetAllMocks()
  sessionStorage.clear()
  mocks.getDocument.mockResolvedValue({
    id: 'doc-1',
    title: '原文',
    content: '正文',
    source_version: 'v1'
  })
  mocks.listChunks.mockResolvedValue({ items: [], total: 0 })
  mocks.updateDocument.mockResolvedValue({
    id: 'doc-1',
    title: '已保存',
    content: '正文',
    source_version: 'v2'
  })
  mocks.getVersion.mockResolvedValue({
    ...draft(),
    id: '11',
    publishedVersionId: '11',
    requiresPlan: false
  })
  mocks.listDocuments.mockResolvedValue({ items: [], total: 0 })
  mocks.getBase.mockResolvedValue({ id: 'kb-1', name: '知识库' })
  mocks.listRevisions.mockResolvedValue({ items: [] })
  mocks.availableModels.mockResolvedValue([])
  mocks.get.mockResolvedValue({
    status: 'IDLE',
    write_locked: false,
    ready_documents: 0,
    document_count: 0
  })
  mocks.uploadFile.mockImplementation(async (_kb: string, _file: FormData, requestId: string) => ({
    request_id: requestId,
    status: 'SUCCEEDED',
    document: { document_id: 'doc-1', source_version: 'v1', chunks: 1 }
  }))
  mocks.definition.mockResolvedValue({ id: '2', name: '技能' })
  mocks.versions.mockResolvedValue([])
  mocks.getActive.mockResolvedValue(draft())
  mocks.importZip.mockResolvedValue({ ...draft(), revision: 8 })
  mocks.confirm.mockResolvedValue(true)
  mocks.usage.mockResolvedValue({ total_amount: 1.125, total_tokens: 0, unavailable: null })
  mocks.health.mockResolvedValue({ deps: {} })
  mocks.sessions.mockResolvedValue([])
  mocks.tasks.mockResolvedValue(rows)
  mocks.agents.mockResolvedValue({ total: 0 })
  mocks.bases.mockResolvedValue({ total: 0 })
  mocks.channels.mockResolvedValue({ total: 0 })
})
afterEach(() => {
  wrappers.splice(0).forEach((w) => w.unmount())
  document.body.innerHTML = ''
})

describe('Element Plus 控件与业务交互', () => {
  it('重新进入知识库时自动核对原上传并恢复文档列表', async () => {
    const requestId = 'd4999064-006f-4af4-af0f-04344f345ca5'
    const key = `skillforge.knowledge-upload.${JSON.stringify(['tenant-1', 'institution-member', 'kb-1'])}`
    sessionStorage.setItem(key, JSON.stringify({ name: '文档.txt', size: 4, requestId }))
    mocks.uploadResult.mockResolvedValueOnce({
      request_id: requestId,
      status: 'SUCCEEDED',
      document: { document_id: 'doc-2', source_version: 'v2', chunks: 1 }
    })
    const wrapper = render(KnowledgeManagePage)
    await flushPromises()
    expect(mocks.uploadResult).toHaveBeenCalledExactlyOnceWith('kb-1', requestId)
    expect(sessionStorage.getItem(key)).toBeNull()
    expect(wrapper.text()).not.toContain('上传结果尚未确认')
    expect(mocks.uploadFile).not.toHaveBeenCalled()
  })
  it('自动核对仍在处理时保留原请求，刷新列表不重复查询', async () => {
    const requestId = 'd4999064-006f-4af4-af0f-04344f345ca5'
    const key = `skillforge.knowledge-upload.${JSON.stringify(['tenant-1', 'institution-member', 'kb-1'])}`
    sessionStorage.setItem(key, JSON.stringify({ name: '文档.txt', size: 4, requestId }))
    mocks.uploadResult.mockResolvedValueOnce({ request_id: requestId, status: 'PROCESSING' })
    const wrapper = render(KnowledgeManagePage)
    await flushPromises()
    expect(wrapper.text()).toContain('上传结果尚未确认')
    expect(sessionStorage.getItem(key)).not.toBeNull()
    await button(wrapper, '刷新文档列表').trigger('click')
    await flushPromises()
    expect(mocks.uploadResult).toHaveBeenCalledTimes(1)
    expect(mocks.uploadFile).not.toHaveBeenCalled()
  })
  it('自动核对失败后仍保留原请求并允许只读重查', async () => {
    const requestId = 'd4999064-006f-4af4-af0f-04344f345ca5'
    const key = `skillforge.knowledge-upload.${JSON.stringify(['tenant-1', 'institution-member', 'kb-1'])}`
    sessionStorage.setItem(key, JSON.stringify({ name: '文档.txt', size: 4, requestId }))
    mocks.uploadResult.mockRejectedValueOnce(new Error('暂时无法核对'))
    const wrapper = render(KnowledgeManagePage)
    await flushPromises()
    expect(wrapper.text()).toContain('上传结果尚未确认')
    expect(sessionStorage.getItem(key)).not.toBeNull()
    mocks.uploadResult.mockResolvedValueOnce({
      request_id: requestId,
      status: 'REJECTED',
      error_message: '文件不符合要求'
    })
    await button(wrapper, '核对上传回执').trigger('click')
    await flushPromises()
    expect(mocks.uploadResult).toHaveBeenCalledTimes(2)
    expect(sessionStorage.getItem(key)).toBeNull()
    expect(mocks.uploadFile).not.toHaveBeenCalled()
  })
  it('没有知识读取权限时不自动核对上传回执', async () => {
    const requestId = 'd4999064-006f-4af4-af0f-04344f345ca5'
    const key = `skillforge.knowledge-upload.${JSON.stringify(['tenant-1', 'institution-member', 'kb-1'])}`
    sessionStorage.setItem(key, JSON.stringify({ name: '文档.txt', size: 4, requestId }))
    const permissions = currentAuth.user.permissions
    currentAuth.user.permissions = permissions.filter(
      (permission) => permission !== 'foundation.knowledge.read'
    )
    try {
      const wrapper = render(KnowledgeManagePage)
      await flushPromises()
      expect(wrapper.text()).toContain('上传结果尚未确认')
      expect(mocks.uploadResult).not.toHaveBeenCalled()
      expect(sessionStorage.getItem(key)).not.toBeNull()
    } finally {
      currentAuth.user.permissions = permissions
    }
  })
  it('上传按钮在写锁时明确禁用，不能绕过知识库写保护', async () => {
    mocks.get.mockResolvedValue({
      status: 'RUNNING',
      write_locked: true,
      ready_documents: 2,
      document_count: 10
    })
    const wrapper = render(KnowledgeManagePage)
    await flushPromises()
    const upload = wrapper.findComponent(ElUpload)
    expect(upload.props('disabled')).toBe(true)
    expect(upload.findComponent(ElButton).attributes('disabled')).toBeDefined()
    upload.vm.handleStart(Object.assign(new File(['x'], 'x.txt'), { uid: 1 }) as UploadRawFile)
    await flushPromises()
    expect(mocks.uploadFile).not.toHaveBeenCalled()
  })
  it('上传结果未知保留原文件并禁止重复上传，刷新页面后仍可只读核对', async () => {
    const pending = deferred<unknown>()
    mocks.uploadFile.mockReturnValueOnce(pending.promise)
    const wrapper = render(KnowledgeManagePage)
    await flushPromises()
    const upload = wrapper.findComponent(ElUpload)
    const file = Object.assign(new File(['data'], 'same.txt'), { uid: 2 }) as UploadRawFile
    upload.vm.handleStart(file)
    await flushPromises()
    expect(upload.findComponent(ElButton).attributes('disabled')).toBeDefined()
    expect(mocks.uploadFile).toHaveBeenCalledWith('kb-1', expect.any(FormData), expect.any(String))
    expect(mocks.uploadFile.mock.calls[0]![1].get('file').name).toBe('same.txt')
    pending.reject(new Error('上传失败'))
    await flushPromises()
    expect(
      Array.from(document.body.querySelectorAll('.el-message__content')).at(-1)?.textContent
    ).toContain('上传失败')
    expect(upload.props('disabled')).toBe(true)
    expect(wrapper.text()).toContain('same.txt')
    upload.vm.handleStart(file)
    await flushPromises()
    expect(mocks.uploadFile).toHaveBeenCalledTimes(1)
    const restored = render(KnowledgeManagePage)
    await flushPromises()
    expect(restored.text()).toContain('same.txt')
    expect(restored.findComponent(ElUpload).props('disabled')).toBe(true)
    await button(restored, '刷新文档列表').trigger('click')
    await flushPromises()
    expect(restored.text()).toContain('上传结果尚未确认')
    expect(mocks.uploadFile).toHaveBeenCalledTimes(1)
    const requestId = mocks.uploadFile.mock.calls[0]![2] as string
    mocks.uploadResult
      .mockResolvedValueOnce(undefined)
      .mockResolvedValueOnce({
        request_id: requestId,
        status: 'SUCCEEDED',
        document: { document_id: 9007199254740992, source_version: 'v2', chunks: 1 }
      })
      .mockResolvedValueOnce({
        request_id: requestId,
        status: 'SUCCEEDED',
        document: { document_id: 'doc-2', source_version: 'v2', chunks: 1 }
      })
    await button(restored, '核对上传回执').trigger('click')
    await flushPromises()
    expect(restored.text()).toContain('上传结果尚未确认')
    await button(restored, '核对上传回执').trigger('click')
    await flushPromises()
    expect(restored.text()).toContain('上传结果尚未确认')
    await button(restored, '核对上传回执').trigger('click')
    await flushPromises()
    expect(restored.text()).not.toContain('上传结果尚未确认')
    expect(restored.findComponent(ElUpload).props('disabled')).toBe(false)
    expect(mocks.uploadFile).toHaveBeenCalledTimes(1)
  })
  it.each(['KNOWLEDGE_UPLOAD_REJECTED', 'KNOWLEDGE_DOCUMENT_CHUNK_LIMIT'])(
    '%s 写入前拒绝允许修正文件后重试',
    async (code) => {
      mocks.uploadFile.mockRejectedValueOnce(
        new HttpClientError({ status: 422, code, userMessage: '文件不符合要求' })
      )
      const wrapper = render(KnowledgeManagePage)
      await flushPromises()
      const upload = wrapper.findComponent(ElUpload)
      upload.vm.handleStart(Object.assign(new File(['x'], 'bad.bin'), { uid: 3 }) as UploadRawFile)
      await flushPromises()
      expect(upload.props('disabled')).toBe(false)
      upload.vm.handleStart(
        Object.assign(new File(['ok'], 'good.txt'), { uid: 4 }) as UploadRawFile
      )
      await flushPromises()
      expect(mocks.uploadFile).toHaveBeenCalledTimes(2)
      expect(wrapper.text()).not.toContain('上传结果尚未确认')
    }
  )
  it('无法保存上传记录时不发送文件', async () => {
    const wrapper = render(KnowledgeManagePage)
    await flushPromises()
    const storage = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw Error('storage')
    })
    try {
      wrapper
        .findComponent(ElUpload)
        .vm.handleStart(Object.assign(new File(['x'], 'file.txt'), { uid: 5 }) as UploadRawFile)
      await flushPromises()
      expect(mocks.uploadFile).not.toHaveBeenCalled()
      expect(wrapper.text()).toContain('无法保存上传记录，未发送文件')
    } finally {
      storage.mockRestore()
    }
  })
  it('成功回执后无法清除记录时保留已完成状态', async () => {
    const wrapper = render(KnowledgeManagePage)
    await flushPromises()
    const storage = vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => {
      throw Error('storage')
    })
    try {
      const upload = wrapper.findComponent(ElUpload)
      upload.vm.handleStart(Object.assign(new File(['x'], 'file.txt'), { uid: 7 }) as UploadRawFile)
      await flushPromises()
      expect(wrapper.text()).toContain('状态：已完成')
      expect(wrapper.text()).not.toContain('上传结果尚未确认')
      expect(upload.props('disabled')).toBe(true)
      expect(mocks.uploadFile).toHaveBeenCalledTimes(1)
    } finally {
      storage.mockRestore()
    }
  })
  it('处理中回执保留原请求，确定拒绝后可以重新选择文件', async () => {
    mocks.uploadFile.mockImplementationOnce(
      async (_kb: string, _file: FormData, requestId: string) => ({
        request_id: requestId,
        status: 'PROCESSING'
      })
    )
    const wrapper = render(KnowledgeManagePage)
    await flushPromises()
    const upload = wrapper.findComponent(ElUpload)
    upload.vm.handleStart(Object.assign(new File(['x'], 'file.txt'), { uid: 6 }) as UploadRawFile)
    await flushPromises()
    expect(upload.props('disabled')).toBe(true)
    const requestId = mocks.uploadFile.mock.calls[0]![2] as string
    mocks.uploadResult.mockResolvedValueOnce({
      request_id: requestId,
      status: 'REJECTED',
      error_message: '文件不符合要求'
    })
    await button(wrapper, '核对上传回执').trigger('click')
    await flushPromises()
    expect(upload.props('disabled')).toBe(false)
    expect(wrapper.text()).not.toContain('上传结果尚未确认')
    expect(mocks.uploadFile).toHaveBeenCalledTimes(1)
  })
  it('可按原请求 ID 只读查询历史回执，编辑 ID 后不保留旧结果', async () => {
    const id = 'd4999064-006f-4af4-af0f-04344f345ca5'
    mocks.uploadResult.mockResolvedValueOnce({
      request_id: id,
      status: 'SUCCEEDED',
      document: { document_id: 'doc-history', source_version: 'v1', chunks: 1 }
    })
    const wrapper = render(KnowledgeManagePage)
    await flushPromises()
    const input = wrapper.get('input[aria-label="上传请求 ID"]')
    await input.setValue(id.toUpperCase())
    await button(wrapper, '查询上传回执').trigger('click')
    await flushPromises()
    expect(mocks.uploadResult).toHaveBeenCalledWith('kb-1', id)
    expect(wrapper.text()).toContain('上传已完成，文档标识：doc-history')
    await input.setValue('other')
    expect(wrapper.text()).not.toContain('doc-history')
    expect(mocks.uploadFile).not.toHaveBeenCalled()
  })
  it('技能 ZIP 导入保留放弃编辑确认与草稿修订，取消后可重复选择', async () => {
    const wrapper = render(SkillWorkbenchPage)
    await flushPromises()
    await wrapper
      .findAll('[role="tab"]')
      .find((tab) => tab.text() === '发布设置')!
      .trigger('click')
    await wrapper
      .findAllComponents(ElFormItem)
      .find((item) => item.props('label') === '版本号')!
      .getComponent(ElInput)
      .find('input')
      .setValue('2.0.0')
    const upload = wrapper.findComponent(ElUpload)
    const file = Object.assign(new File(['zip'], 'skill.zip'), { uid: 3 }) as UploadRawFile
    mocks.confirm.mockResolvedValueOnce(false)
    upload.vm.handleStart(file)
    await flushPromises()
    expect(mocks.confirm).toHaveBeenCalledTimes(1)
    expect(mocks.importZip).not.toHaveBeenCalled()
    upload.vm.handleStart(file)
    await flushPromises()
    expect(mocks.importZip).toHaveBeenCalledWith('4', 7, file)
  })
  it('技能格式通过时仍显示扩展与环境提示，编辑后不沿用旧校验结果', async () => {
    mocks.validateSkill.mockResolvedValue({
      valid: true,
      issues: [
        {
          path: 'SKILL.md',
          line: null,
          severity: 'WARNING',
          code: 'UNSUPPORTED',
          message: 'hooks 不会执行'
        },
        {
          path: 'SKILL.md',
          line: null,
          severity: 'INFO',
          code: 'UNVERIFIED',
          message: '运行依赖尚未核实'
        }
      ]
    })
    const wrapper = render(SkillWorkbenchPage)
    await flushPromises()
    await button(wrapper, '校验').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('包格式与平台配置校验通过')
    expect(wrapper.find('.el-alert--warning').text()).toContain('hooks 不会执行')
    expect(wrapper.find('.el-alert--info').text()).toContain('运行依赖尚未核实')
    expect(wrapper.text()).toContain('导入与校验不会执行包内命令')
    await wrapper
      .findAll('[role="tab"]')
      .find((tab) => tab.text() === '发布设置')!
      .trigger('click')
    await wrapper
      .findAllComponents(ElFormItem)
      .find((item) => item.props('label') === '版本号')!
      .getComponent(ElInput)
      .find('input')
      .setValue('platform-v2')
    expect(wrapper.text()).not.toContain('hooks 不会执行')
  })
  it('校验未保存技能不写入草稿并保留编辑内容', async () => {
    mocks.validateSkillContent.mockResolvedValueOnce({ valid: true, issues: [] })
    const wrapper = render(SkillWorkbenchPage)
    await flushPromises()
    await wrapper
      .findAll('[role="tab"]')
      .find((tab) => tab.text() === '发布设置')!
      .trigger('click')
    const version = wrapper
      .findAllComponents(ElFormItem)
      .find((item) => item.props('label') === '版本号')!
    await version.getComponent(ElInput).find('input').setValue('v2')
    await button(wrapper, '校验').trigger('click')
    await flushPromises()
    expect(mocks.validateSkillContent).toHaveBeenCalledWith(
      expect.objectContaining({ draftId: '4', revision: 7, versionKey: 'v2' })
    )
    expect(mocks.saveSkill).not.toHaveBeenCalled()
    expect((version.get('input').element as HTMLInputElement).value).toBe('v2')
  })
  it('任务翻页请求未完成时禁止连击跳页，不编造任务总数', async () => {
    const pending = deferred<typeof rows>()
    mocks.tasks.mockResolvedValueOnce(rows).mockReturnValueOnce(pending.promise)
    const wrapper = render(ConversationTasksPage)
    await flushPromises()
    const pagination = wrapper.findComponent(ElPagination)
    pagination.vm.$emit('current-change', 2)
    pagination.vm.$emit('current-change', 3)
    await flushPromises()
    expect(mocks.tasks).toHaveBeenCalledTimes(2)
    expect(mocks.tasks).toHaveBeenLastCalledWith('session-1', '', false, 30)
    expect(pagination.props('disabled')).toBe(true)
    expect(pagination.props('layout')).toBe('prev,next')
    pending.resolve([])
    await flushPromises()
    expect(pagination.props('currentPage')).toBe(2)
    expect(pagination.props('pageCount')).toBe(2)
  })
  it('翻页失败后保留原列表及页码，允许重试', async () => {
    mocks.tasks
      .mockResolvedValueOnce(rows)
      .mockRejectedValueOnce(new Error('读取失败'))
      .mockResolvedValueOnce([])
    const wrapper = render(ConversationTasksPage)
    await flushPromises()
    const pagination = wrapper.findComponent(ElPagination)
    pagination.vm.$emit('current-change', 2)
    await flushPromises()
    expect(pagination.props('currentPage')).toBe(1)
    expect(
      Array.from(document.body.querySelectorAll('.el-message__content')).at(-1)?.textContent
    ).toContain('读取失败')
    pagination.vm.$emit('current-change', 2)
    await flushPromises()
    expect(mocks.tasks).toHaveBeenLastCalledWith('session-1', '', false, 30)
    expect(pagination.props('currentPage')).toBe(2)
  })
  it('修改待查询条件后翻页沿用已应用条件，点击查询从零开始', async () => {
    const wrapper = render(ConversationTasksPage)
    await flushPromises()
    await wrapper.findComponent(ElInput).find('input').setValue('新查询')
    wrapper.findComponent(ElCheckbox).vm.$emit('update:modelValue', true)
    wrapper.findComponent(ElPagination).vm.$emit('current-change', 2)
    await flushPromises()
    expect(mocks.tasks).toHaveBeenLastCalledWith('session-1', '', false, 30)
    await button(wrapper, '查询').trigger('click')
    await flushPromises()
    expect(mocks.tasks).toHaveBeenLastCalledWith('session-1', '新查询', true, 0)
    expect(wrapper.findComponent(ElPagination).props('currentPage')).toBe(1)
  })
  it('保存文档使用提交快照，晚到的编辑仍保留未保存保护', async () => {
    mocks.listDocuments.mockResolvedValue({ items: [{ id: 'doc-1', title: '原文' }], total: 1 })
    const pending = deferred<unknown>()
    mocks.updateDocument.mockReturnValue(pending.promise)
    const wrapper = render(KnowledgeManagePage)
    await flushPromises()
    await button(wrapper, '编辑 / 分块').trigger('click')
    await flushPromises()
    const drawer = wrapper
      .findAllComponents(ElDrawer)
      .find((item) => item.props('title') === '原文')!
    const title = drawer.findAllComponents(ElInput).find((c) => c.props('modelValue') === '原文')!
    await title.find('input').setValue('提交标题')
    await button(wrapper, '保存原文').trigger('click')
    await flushPromises()
    expect(title.props('disabled')).toBe(true)
    expect(mocks.updateDocument).toHaveBeenCalledWith('doc-1', 'v1', {
      title: '提交标题',
      content: '正文'
    })
    title.vm.$emit('update:modelValue', '晚到标题')
    pending.resolve({ id: 'doc-1', title: '提交标题', content: '正文', source_version: 'v2' })
    await flushPromises()
    expect(title.props('modelValue')).toBe('晚到标题')
    const leaving = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(leaving)
    expect(leaving.defaultPrevented).toBe(true)
  })
  it('从已发布技能切回草稿失败时保留版本及只读身份', async () => {
    mocks.versions.mockResolvedValue([{ id: '11', versionKey: '1.0.0', status: 'PUBLISHED' }])
    const wrapper = render(SkillWorkbenchPage)
    await flushPromises()
    const select = wrapper.findAllComponents(ElSelect)[0]!
    select.vm.$emit('change', '11')
    await flushPromises()
    expect(select.props('modelValue')).toBe('11')
    expect(wrapper.findAllComponents(ElForm).every((c) => c.props('disabled'))).toBe(true)
    mocks.getActive.mockRejectedValueOnce(new Error('草稿读取失败'))
    select.vm.$emit('change', '')
    await flushPromises()
    expect(
      Array.from(document.body.querySelectorAll('.el-message__content')).at(-1)?.textContent
    ).toContain('草稿读取失败')
    expect(select.props('modelValue')).toBe('11')
    expect(wrapper.findAllComponents(ElForm).every((c) => c.props('disabled'))).toBe(true)
    expect(wrapper.findComponent(ElUpload).exists()).toBe(false)
  })
  it('轮换渠道凭据期间底部关闭受保护，返回的一次性凭据可见', async () => {
    mocks.channels.mockResolvedValue({
      items: [{ id: 'channel-1', name: '网页', type: 'web_widget' }],
      total: 1
    })
    const pending = deferred<{ channel_token: string }>()
    mocks.rotateToken.mockReturnValue(pending.promise)
    const wrapper = render(ChannelsPage)
    await flushPromises()
    await button(wrapper, '访问凭据').trigger('click')
    await flushPromises()
    const drawer = wrapper
      .findAllComponents(ElDrawer)
      .find((c) => String(c.props('title')).startsWith('渠道访问凭据'))!
    await button(wrapper, '轮换渠道令牌').trigger('click')
    await flushPromises()
    const close = button(wrapper, '关闭')
    expect(close.props('disabled')).toBe(true)
    await close.trigger('click')
    await flushPromises()
    expect(drawer.props('modelValue')).toBe(true)
    pending.resolve({ channel_token: 'new-one-time-token' })
    await flushPromises()
    expect(document.body.textContent).toContain('new-one-time-token')
    await close.trigger('click')
    await flushPromises()
    expect(drawer.props('modelValue')).toBe(false)
  })
  it('仪表盘统计保留小数精度与未知状态', async () => {
    const wrapper = render(DashboardPage)
    await flushPromises()
    expect(wrapper.text()).toContain('1.125')
    expect(wrapper.text()).toContain('—')
    expect(wrapper.findAllComponents(ElStatistic).map((c) => c.props('value'))).toEqual([1.125, 0])
  })
  it('服务状态读取失败时保留独立读取成功的机构用量', async () => {
    mocks.health.mockRejectedValueOnce(Error('服务检查超时'))
    const wrapper = render(DashboardPage)
    await flushPromises()
    expect(wrapper.findAllComponents(ElStatistic).map((item) => item.props('value'))).toEqual([
      1.125, 0
    ])
    expect(
      Array.from(document.body.querySelectorAll('.el-message__content')).at(-1)?.textContent
    ).toContain('服务状态读取失败：服务检查超时')
    expect(wrapper.text()).toContain('依赖服务')
  })
  it('首页未加载时展示未知，真实零值使用 Statistic', async () => {
    const pending = deferred<{ total: number }>()
    mocks.agents.mockReturnValue(pending.promise)
    const wrapper = render(HomePage)
    await flushPromises()
    expect(wrapper.findAllComponents(ElStatistic)).toHaveLength(0)
    expect(wrapper.text()).toContain('—')
    pending.resolve({ total: 0 })
    await flushPromises()
    expect(wrapper.findAllComponents(ElStatistic)).toHaveLength(4)
    expect(wrapper.findAllComponents(ElStatistic).every((c) => c.props('value') === 0)).toBe(true)
  })
  it('技能依赖校验异常阻止保存请求', async () => {
    mocks.getActive.mockResolvedValue({
      ...draft(),
      requiredCapabilities: [{ type: 'TOOL', capabilityId: '', versionId: '' }]
    })
    const wrapper = render(SkillWorkbenchPage)
    await flushPromises()
    await button(wrapper, '保存草稿').trigger('click')
    await flushPromises()
    expect(mocks.saveSkill).not.toHaveBeenCalled()
  })
  it('技能发布成功后读取失败保留成功事实，并禁止重复发布', async () => {
    mocks.validateSkill.mockResolvedValue({ valid: true, issues: [] })
    mocks.publishSkill.mockResolvedValue({ versionId: '11' })
    mocks.getActive
      .mockResolvedValueOnce(draft())
      .mockRejectedValueOnce(new Error('发布结果读取失败'))
    const wrapper = render(SkillWorkbenchPage)
    await flushPromises()
    await button(wrapper, '发布').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('已发布 · 待更新显示')
    expect(mocks.confirm).toHaveBeenCalledWith(
      expect.objectContaining({
        content: expect.stringContaining('已固定版本的 Agent 需显式更新挂载')
      })
    )
    expect(
      Array.from(document.body.querySelectorAll('.el-message__content')).at(-1)?.textContent
    ).toContain('发布结果读取失败')
    expect(button(wrapper, '发布').props('disabled')).toBe(true)
    expect(button(wrapper, '保存草稿').props('disabled')).toBe(true)
    expect(button(wrapper, '读取发布结果').exists()).toBe(true)
    await button(wrapper, '发布').trigger('click')
    await flushPromises()
    expect(mocks.publishSkill).toHaveBeenCalledTimes(1)
    expect(mocks.validateSkill).not.toHaveBeenCalled()
  })
  it('技能发布直接使用服务端校验结果并保留警告', async () => {
    mocks.publishSkill.mockResolvedValueOnce({
      versionId: '11',
      definitionRevision: 8,
      validation: {
        valid: true,
        issues: [
          { path: 'SKILL.md', severity: 'WARNING', code: 'UNSUPPORTED', message: 'hooks 不会执行' }
        ]
      }
    })
    const wrapper = render(SkillWorkbenchPage)
    await flushPromises()
    await button(wrapper, '发布').trigger('click')
    await flushPromises()
    expect(mocks.publishSkill).toHaveBeenCalledTimes(1)
    expect(mocks.validateSkill).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('hooks 不会执行')
  })
  it('技能发布明确校验失败后只读取问题，不重复发布', async () => {
    currentAuth.user.permissions.push('foundation.skills.update')
    mocks.publishSkill.mockRejectedValueOnce(
      new HttpClientError({
        status: 422,
        code: 'CAPABILITY_DESCRIPTOR_INVALID',
        userMessage: '技能无效'
      })
    )
    mocks.validateSkill.mockResolvedValueOnce({
      valid: false,
      issues: [{ path: 'SKILL.md', severity: 'ERROR', code: 'INVALID', message: '内容无效' }]
    })
    const wrapper = render(SkillWorkbenchPage)
    await flushPromises()
    await button(wrapper, '发布').trigger('click')
    await flushPromises()
    expect(mocks.publishSkill).toHaveBeenCalledTimes(1)
    expect(mocks.validateSkill).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('内容无效')
    currentAuth.user.permissions = currentAuth.user.permissions.filter(
      (item) => item !== 'foundation.skills.update'
    )
  })
  it('保存单个分块保留其他脏分块，保存文档丢弃确认取消时不写入', async () => {
    mocks.listDocuments.mockResolvedValue({
      items: [{ id: 'doc-1', title: '文档', source_version: 'v1' }],
      total: 1
    })
    const chunks = [
      { id: 'a', content: '内容A', chunk_index: 0, source_version: 'v1' },
      { id: 'b', content: '内容B', chunk_index: 1, source_version: 'v1' }
    ]
    mocks.listChunks.mockResolvedValue({ items: chunks.map((c) => ({ ...c })), total: 2 })
    const nextChunks = [
      { ...chunks[0], id: 'a2', content: '改动A', source_version: 'v2' },
      { ...chunks[1], id: 'b2', source_version: 'v2' }
    ]
    mocks.listChunks
      .mockResolvedValueOnce({ items: chunks.map((c) => ({ ...c })), total: 2 })
      .mockResolvedValue({ items: nextChunks, total: 2 })
    mocks.getDocument
      .mockResolvedValueOnce({ id: 'doc-1', title: '原文', content: '正文', source_version: 'v1' })
      .mockResolvedValue({
        id: 'doc-1',
        title: '原文',
        content: '改动A\n内容B',
        source_version: 'v2'
      })
    mocks.updateChunk.mockResolvedValue(nextChunks[0])
    const wrapper = render(KnowledgeManagePage)
    await flushPromises()
    await button(wrapper, '编辑 / 分块').trigger('click')
    await flushPromises()
    const chunksTab = Array.from(document.body.querySelectorAll<HTMLElement>('[role="tab"]')).find(
      (tab) => tab.textContent?.trim() === '分块'
    )!
    chunksTab.click()
    await flushPromises()
    const fields = wrapper.findAllComponents(ElInput)
    fields
      .find((field) => field.props('modelValue') === '内容A')!
      .vm.$emit('update:modelValue', '改动A')
    fields
      .find((field) => field.props('modelValue') === '内容B')!
      .vm.$emit('update:modelValue', '改动B')
    await flushPromises()
    await button(wrapper, '保存分块').trigger('click')
    await flushPromises()
    expect(mocks.updateChunk).toHaveBeenCalledWith('a', 'v1', { content: '改动A' })
    expect(
      wrapper.findAllComponents(ElInput).some((field) => field.props('modelValue') === '改动B')
    ).toBe(true)
    expect(mocks.listChunks).toHaveBeenCalledTimes(2)
    const sourceTab = Array.from(document.body.querySelectorAll<HTMLElement>('[role="tab"]')).find(
      (tab) => tab.textContent?.trim() === '原文'
    )!
    sourceTab.click()
    await flushPromises()
    mocks.confirm.mockResolvedValue(false)
    await button(wrapper, '保存原文').trigger('click')
    await flushPromises()
    expect(mocks.updateDocument).not.toHaveBeenCalled()
    chunksTab.click()
    await flushPromises()
    expect(
      wrapper.findAllComponents(ElInput).some((field) => field.props('modelValue') === '改动B')
    ).toBe(true)
    await wrapper
      .findAllComponents(ElButton)
      .filter((item) => item.text() === '保存分块')[1]!
      .trigger('click')
    await flushPromises()
    expect(mocks.updateChunk).toHaveBeenLastCalledWith('b2', 'v2', { content: '改动B' })
  })
  it('分块写入成功后读取失败会锁住旧身份，只读重试保留相邻草稿且不重复写入', async () => {
    mocks.listDocuments.mockResolvedValue({
      items: [{ id: 'doc-1', title: '文档', source_version: 'v1' }],
      total: 1
    })
    mocks.listChunks
      .mockResolvedValueOnce({
        items: [
          { id: 'a', content: '内容A', chunk_index: 0, source_version: 'v1' },
          { id: 'b', content: '内容B', chunk_index: 1, source_version: 'v1' }
        ],
        total: 2
      })
      .mockRejectedValueOnce(Error('分块读取失败'))
      .mockResolvedValue({
        items: [
          { id: 'a2', content: '改动A', chunk_index: 0, source_version: 'v2' },
          { id: 'b2', content: '内容B', chunk_index: 1, source_version: 'v2' }
        ],
        total: 2
      })
    mocks.getDocument
      .mockResolvedValueOnce({ id: 'doc-1', title: '原文', content: '正文', source_version: 'v1' })
      .mockResolvedValue({
        id: 'doc-1',
        title: '原文',
        content: '改动A\n内容B',
        source_version: 'v2'
      })
    mocks.updateChunk.mockResolvedValue({})
    const wrapper = render(KnowledgeManagePage)
    await flushPromises()
    await button(wrapper, '编辑 / 分块').trigger('click')
    await flushPromises()
    const chunksTab = Array.from(document.body.querySelectorAll<HTMLElement>('[role="tab"]')).find(
      (tab) => tab.textContent?.trim() === '分块'
    )!
    chunksTab.click()
    await flushPromises()
    for (const [before, after] of [
      ['内容A', '改动A'],
      ['内容B', '改动B']
    ])
      wrapper
        .findAllComponents(ElInput)
        .find((item) => item.props('modelValue') === before)!
        .vm.$emit('update:modelValue', after)
    await flushPromises()
    await button(wrapper, '保存分块').trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('修改已保存，最新文档与分块尚未读取完成')
    expect(button(wrapper, '保存分块').props('disabled')).toBe(true)
    await button(wrapper, '重新读取已保存内容').trigger('click')
    await flushPromises()
    expect(mocks.updateChunk).toHaveBeenCalledTimes(1)
    expect(
      wrapper.findAllComponents(ElInput).some((item) => item.props('modelValue') === '改动B')
    ).toBe(true)
    await wrapper
      .findAllComponents(ElButton)
      .filter((item) => item.text() === '保存分块')[1]!
      .trigger('click')
    await flushPromises()
    expect(mocks.updateChunk).toHaveBeenLastCalledWith('b2', 'v2', { content: '改动B' })
  })
  it('删除分块后保留相邻草稿并使用新版本分块身份继续保存', async () => {
    mocks.listDocuments.mockResolvedValue({
      items: [{ id: 'doc-1', title: '文档', source_version: 'v1' }],
      total: 1
    })
    mocks.listChunks
      .mockResolvedValueOnce({
        items: [
          { id: 'a', content: '内容A', chunk_index: 0, source_version: 'v1' },
          { id: 'b', content: '内容B', chunk_index: 1, source_version: 'v1' }
        ],
        total: 2
      })
      .mockResolvedValue({
        items: [{ id: 'b2', content: '内容B', chunk_index: 0, source_version: 'v2' }],
        total: 1
      })
    mocks.getDocument
      .mockResolvedValueOnce({ id: 'doc-1', title: '原文', content: '正文', source_version: 'v1' })
      .mockResolvedValue({ id: 'doc-1', title: '原文', content: '内容B', source_version: 'v2' })
    mocks.removeChunk.mockResolvedValue({ status: 'deleted' })
    mocks.updateChunk.mockResolvedValue({
      id: 'b3',
      content: '改动B',
      chunk_index: 0,
      source_version: 'v3'
    })
    const wrapper = render(KnowledgeManagePage)
    await flushPromises()
    await button(wrapper, '编辑 / 分块').trigger('click')
    await flushPromises()
    const chunksTab = Array.from(document.body.querySelectorAll<HTMLElement>('[role="tab"]')).find(
      (tab) => tab.textContent?.trim() === '分块'
    )!
    chunksTab.click()
    await flushPromises()
    wrapper
      .findAllComponents(ElInput)
      .find((field) => field.props('modelValue') === '内容B')!
      .vm.$emit('update:modelValue', '改动B')
    await flushPromises()
    await button(wrapper, '删除分块').trigger('click')
    await flushPromises()
    expect(mocks.removeChunk).toHaveBeenCalledWith('a', 'v1')
    expect(
      wrapper.findAllComponents(ElInput).some((field) => field.props('modelValue') === '改动B')
    ).toBe(true)
    await button(wrapper, '保存分块').trigger('click')
    await flushPromises()
    expect(mocks.updateChunk).toHaveBeenCalledWith('b2', 'v2', { content: '改动B' })
  })
})
