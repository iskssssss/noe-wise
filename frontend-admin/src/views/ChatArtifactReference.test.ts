import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { ElInput, ElSelect } from 'element-plus'
import { reactive } from 'vue'
import ChatPage from './ChatPage.vue'
import type { ChatArtifact, ChatHistoryMessage } from '../api'
import type { ChatReferenceView } from '@skillforge/shared/components/chatReferences'
import { clearFeedback } from '@skillforge/shared/feedback'
import { HttpClientError } from '@skillforge/shared/utils/http'

const mocks = vi.hoisted(() => ({
  detail: vi.fn(),
  history: vi.fn(),
  observe: vi.fn(),
  submit: vi.fn(),
  artifacts: vi.fn(),
  recoveryInputs: vi.fn(),
  confirm: vi.fn(),
  auth: { identityVersion: 1, permissions: [] as string[] }
}))
const identity = reactive(mocks.auth)
vi.mock('../stores/auth', () => ({
  useAuthStore: () => ({
    get identityVersion() {
      return identity.identityVersion
    },
    get user() {
      return { tenant_id: '1', sub: 'member-1', permissions: identity.permissions }
    }
  })
}))
vi.mock('../api', () => ({
  api: {
    chat: {
      endUsers: async () => [],
      sessions: async () => [{ session_id: 'A' }, { session_id: 'B' }],
      sessionDetail: mocks.detail,
      history: mocks.history,
      observeSession: mocks.observe,
      submit: mocks.submit,
      artifacts: mocks.artifacts,
      recoveryInputs: mocks.recoveryInputs,
      goals: async () => ({ goal: null }),
      collaboration: async () => ({})
    },
    agents: { list: async () => ({ items: [] }) },
    teams: { list: async () => [] }
  }
}))
vi.mock('@skillforge/shared/feedback', async (original) => ({
  ...(await original<typeof import('@skillforge/shared/feedback')>()),
  confirm: mocks.confirm
}))
vi.mock('vue-router', () => ({
  useRoute: () => ({ query: {} }),
  onBeforeRouteLeave: vi.fn(),
  onBeforeRouteUpdate: vi.fn()
}))
vi.mock('../components/TeamDebugPanel.vue', () => ({ default: { template: '<div />' } }))
vi.mock('@skillforge/shared/components/MarkdownContent.vue', () => ({
  default: { props: ['content'], template: '<div>{{ content }}</div>' }
}))

let wrapper: VueWrapper | undefined
const messageKey = 'skillforge.chat-input:1:member-1'
const sourceFile = { artifact_id: 'immutable-artifact', path: 'reports/appointment.csv' }
const artifact = (status: ChatArtifact['status'] = 'ready'): ChatArtifact => ({
  id: 'revision-selected',
  deliverable_id: 'appointment-report',
  revision: 3,
  current: true,
  result_run_ids: ['run:original'],
  session_id: 'A',
  run_id: 'run:original',
  type: 'file',
  name: '预约报表',
  size: 32,
  sha256: 'a'.repeat(64),
  expires_at: null,
  status,
  files: [
    {
      ...sourceFile,
      file_id: 'immutable-file',
      download_name: '预约明细.csv',
      size: 32,
      sha256: 'b'.repeat(64),
      format: 'csv',
      representation_id: 'csv-representation',
      primary_preview: true,
      entry: true
    }
  ],
  artifact_inputs: [],
  evidence: []
})
const revisionReferences: ChatReferenceView[] = [
  {
    label: '成果版本 3',
    source: {
      kind: 'deliverable_revision',
      revision_id: 'revision-selected',
      purpose: 'revision_base'
    }
  }
]
const history = (session_id: string) => ({
  session_id,
  messages: [],
  pending_interactions: [],
  processes: [],
  has_more: false
})

beforeEach(() => {
  sessionStorage.clear()
  vi.resetAllMocks()
  identity.identityVersion = 1
  identity.permissions = [
    'foundation.sessions.read',
    'foundation.sessions.create',
    'foundation.sessions.cancel',
    'foundation.sessions.run',
    'foundation.agents.read',
    'foundation.teams.read'
  ]
  mocks.detail.mockImplementation(async (id: string) => ({
    session: { session_id: id },
    end_user_id: '42',
    target_agent_id: '101',
    closed: false
  }))
  mocks.history.mockImplementation(async (id: string) => history(id))
  mocks.observe.mockResolvedValue(undefined)
  mocks.submit.mockResolvedValue({ session_id: 'A', status: 'accepted' })
  mocks.artifacts.mockImplementation(async (id: string) => (id === 'A' ? [artifact()] : []))
  mocks.recoveryInputs.mockResolvedValue({
    status: 'validated',
    session_id: 'A',
    references: revisionReferences,
    artifact_inputs: []
  })
  mocks.confirm.mockResolvedValue(true)
})
afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  clearFeedback()
  document.body.innerHTML = ''
})

async function open() {
  wrapper = mount(ChatPage, { attachTo: document.body })
  await flushPromises()
}
function sessions() {
  return wrapper!
    .findAllComponents(ElSelect)
    .find((field) => field.props('placeholder') === '历史会话')!
}
function composer() {
  return wrapper!
    .findAllComponents(ElInput)
    .find((field) => field.props('placeholder') === '输入消息')!
}
function action(name: string) {
  const button = wrapper!.findAll('button').find((item) => item.text() === name)
  expect(button, `页面应提供“${name}”操作`).toBeDefined()
  return button!
}
async function click(name: string) {
  await action(name).trigger('click')
  await flushPromises()
}
async function tab(name: string) {
  await wrapper!
    .findAll('[role="tab"]')
    .find((item) => item.text() === name)!
    .trigger('click')
  await flushPromises()
}
async function start() {
  await open()
  sessions().vm.$emit('change', 'A')
  await flushPromises()
  await tab('成果文件')
}
const draftTags = () => wrapper!.find('.debug-composer').findAll('.el-tag')

describe('机构调试页成果引用', () => {
  it('文件引用固定原文件身份、去重且独立于正文发送，受理后清除草稿', async () => {
    await start()
    await click('引用文件')
    expect(wrapper!.find('[role="tab"][aria-selected="true"]').text()).toBe('对话')
    expect(draftTags()).toHaveLength(1)
    expect(draftTags()[0]!.text()).toContain('预约明细.csv')
    expect(mocks.submit).not.toHaveBeenCalled()
    expect(composer().props('modelValue')).toBe('')
    await tab('成果文件')
    await click('引用文件')
    expect(draftTags()).toHaveLength(1)
    composer().vm.$emit('update:modelValue', '统计这份表的预约数量')
    await flushPromises()
    await click('发送')
    expect(mocks.submit).toHaveBeenCalledOnce()
    expect(mocks.submit.mock.calls[0]![0]).toMatchObject({
      session_id: 'A',
      content: [{ type: 'text', text: '统计这份表的预约数量' }],
      references: [{ kind: 'artifact_files', files: [sourceFile] }]
    })
    expect(draftTags()).toHaveLength(0)
    expect(composer().props('modelValue')).toBe('')
  })

  it('继续修改使用宿主返回的准确版本和修改基线用途，不将标签注入正文', async () => {
    await start()
    await click('引用并继续修改')
    expect(mocks.recoveryInputs.mock.calls[0]![0]).toEqual({ revision_id: 'revision-selected' })
    expect(mocks.submit).not.toHaveBeenCalled()
    expect(draftTags().map((tag) => tag.text())).toEqual(revisionReferences.map((ref) => ref.label))
    composer().vm.$emit('update:modelValue', '增加按日期汇总')
    await flushPromises()
    await click('发送')
    expect(mocks.submit.mock.calls[0]![0]).toMatchObject({
      content: [{ type: 'text', text: '增加按日期汇总' }],
      references: revisionReferences.map((ref) => ref.source)
    })
    expect(mocks.recoveryInputs).toHaveBeenCalledOnce()
  })

  it('仅引用消息受理后实时显示已发送引用，重建读取历史仍保留名称', async () => {
    await start()
    await click('引用文件')
    await click('发送')
    const sent: ChatHistoryMessage = {
      message_id: mocks.submit.mock.calls[0]![0].client_message_id,
      message_version: 1,
      role: 'user',
      content: '',
      created_at: '2026-11-29T00:00:00Z',
      references: [
        {
          label: '预约明细.csv',
          source: { kind: 'artifact_files', files: [sourceFile] }
        }
      ],
      metadata: {}
    }
    mocks.observe.mock.calls[0]![1].onEvent({ event: 'message.delta', data: sent })
    await flushPromises()
    expect(draftTags()).toHaveLength(0)
    expect(wrapper!.get('.debug-message [aria-label="已发送引用"]').text()).toBe('预约明细.csv')
    expect(wrapper!.findAll('.debug-message')).toHaveLength(1)

    mocks.history.mockResolvedValue({ ...history('A'), messages: [sent] })
    wrapper!.unmount()
    wrapper = undefined
    await open()
    expect(wrapper!.get('.debug-message [aria-label="已发送引用"]').text()).toBe('预约明细.csv')
    expect(wrapper!.findAll('.debug-message')).toHaveLength(1)
    expect(mocks.submit).toHaveBeenCalledOnce()
  })

  it('引用本身构成未发送草稿，可移除或单独发送，切换会话须确认并清空', async () => {
    await start()
    await click('引用文件')
    expect(action('发送').attributes('disabled')).toBeUndefined()
    await draftTags()[0]!.get('.el-tag__close').trigger('click')
    await flushPromises()
    expect(action('发送').attributes('disabled')).toBeDefined()
    await tab('成果文件')
    await click('引用文件')
    mocks.confirm.mockResolvedValueOnce(false)
    sessions().vm.$emit('change', 'B')
    await flushPromises()
    expect(mocks.confirm).toHaveBeenCalledOnce()
    expect(draftTags()).toHaveLength(1)
    await click('发送')
    expect(mocks.submit.mock.calls[0]![0]).toMatchObject({
      session_id: 'A',
      content: [],
      references: [{ kind: 'artifact_files', files: [sourceFile] }]
    })
    await tab('成果文件')
    await click('引用文件')
    sessions().vm.$emit('change', 'B')
    await flushPromises()
    expect(mocks.confirm).toHaveBeenCalledTimes(2)
    expect(draftTags()).toHaveLength(0)
    expect(action('发送').attributes('disabled')).toBeDefined()
  })

  it('受理响应丢失后重建保留标签和原引用，显式重试使用原请求身份', async () => {
    await start()
    await click('引用文件')
    composer().vm.$emit('update:modelValue', '根据原文件补充说明')
    mocks.submit.mockRejectedValueOnce(new TypeError('受理响应丢失'))
    await flushPromises()
    await click('发送')
    const original = structuredClone(mocks.submit.mock.calls[0]![0])
    expect(original.references).toEqual([{ kind: 'artifact_files', files: [sourceFile] }])
    expect(JSON.parse(sessionStorage.getItem(messageKey)!).body).toEqual(original)
    expect(draftTags()).toHaveLength(1)
    expect(wrapper!.findAll('.debug-composer .el-tag__close')).toHaveLength(0)
    wrapper!.unmount()
    wrapper = undefined
    await open()
    expect(mocks.submit).toHaveBeenCalledOnce()
    expect(draftTags()[0]!.text()).toContain('预约明细.csv')
    expect(composer().props('modelValue')).toBe('根据原文件补充说明')
    await click('重试原消息')
    expect(mocks.submit.mock.calls[1]![0]).toEqual(original)
    expect(sessionStorage.getItem(messageKey)).toBeNull()
    expect(draftTags()).toHaveLength(0)
  })

  it('明确的首次受理拒绝保留引用供修正，下一次发送使用新消息身份', async () => {
    await start()
    await click('引用文件')
    mocks.submit.mockRejectedValueOnce(
      new HttpClientError({ status: 422, code: 'VALIDATION_ERROR', userMessage: '请补充处理要求' })
    )
    await click('发送')
    const original = mocks.submit.mock.calls[0]![0]
    expect(draftTags()).toHaveLength(1)
    expect(sessionStorage.getItem(messageKey)).toBeNull()
    composer().vm.$emit('update:modelValue', '统计预约数量')
    await flushPromises()
    await click('发送')
    const next = mocks.submit.mock.calls[1]![0]
    expect(next.client_message_id).not.toBe(original.client_message_id)
    expect(next.references).toEqual(original.references)
    expect(next.content).toEqual([{ type: 'text', text: '统计预约数量' }])
  })

  it('成果版本核验的迟到结果不进入新会话草稿', async () => {
    await start()
    let resolve!: (value: unknown) => void
    mocks.recoveryInputs.mockReturnValueOnce(new Promise((done) => (resolve = done)))
    await click('引用并继续修改')
    sessions().vm.$emit('change', 'B')
    await flushPromises()
    resolve({ status: 'validated', session_id: 'A', references: revisionReferences })
    await flushPromises()
    await tab('对话')
    expect(draftTags()).toHaveLength(0)
    expect(document.body.textContent).not.toContain('成果版本 3')
    expect(mocks.submit).not.toHaveBeenCalled()
  })

  it('身份更新后忽略原版本核验的迟到成功', async () => {
    await start()
    let resolve!: (value: unknown) => void
    mocks.recoveryInputs.mockReturnValueOnce(new Promise((done) => (resolve = done)))
    await click('引用并继续修改')
    identity.identityVersion++
    await flushPromises()
    resolve({ status: 'validated', session_id: 'A', references: revisionReferences })
    await flushPromises()
    await tab('对话')
    expect(draftTags()).toHaveLength(0)
    expect(document.body.textContent).not.toContain('成果版本 3')
    expect(mocks.submit).not.toHaveBeenCalled()
  })

  it.each(['expired', 'failed', 'closed', 'readOnly'] as const)(
    '%s 时仍能查看成果列表，但不能将其加入待发送草稿',
    async (condition) => {
      if (condition === 'expired' || condition === 'failed')
        mocks.artifacts.mockResolvedValue([artifact(condition)])
      if (condition === 'closed')
        mocks.detail.mockResolvedValue({
          session: { session_id: 'A' },
          end_user_id: '42',
          target_agent_id: '101',
          closed: true
        })
      if (condition === 'readOnly')
        identity.permissions = identity.permissions.filter(
          (value) => value !== 'foundation.sessions.run'
        )
      await start()
      expect(wrapper!.get('#pane-artifacts').text()).toContain('预约报表')
      for (const name of ['引用文件', '引用并继续修改']) {
        expect(action(name).attributes('disabled')).toBeDefined()
        await click(name)
      }
      expect(mocks.recoveryInputs).not.toHaveBeenCalled()
      expect(mocks.submit).not.toHaveBeenCalled()
      expect(draftTags()).toHaveLength(0)
    }
  )
})
