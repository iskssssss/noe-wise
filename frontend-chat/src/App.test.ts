import { afterAll, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, reactive } from 'vue'
import { ChatMessageBox, ChatPopover, ChatUpload } from './ui/controls'
import App from './App.vue'
import TeamManager from './ui/TeamManager.vue'
import WorkspaceStatus from './ui/WorkspaceStatus.vue'
import PendingForm from './ui/PendingForm.vue'
import TaskDetailsPanel from './ui/TaskDetailsPanel.vue'
import ArtifactPanel from './ui/ArtifactPanel.vue'
import ConversationTimeline from './ui/ConversationTimeline.vue'
import { readFileSync } from 'node:fs'
import type {
  Attachment,
  ChatInput,
  Media,
  Message,
  Pending,
  Process,
  Reference,
  Session,
  SteeringInput,
  Team,
  Work
} from './protocol'
import type { PastedTextAttachment } from './pastedText'
const holder = vi.hoisted(() => ({ state: undefined as unknown as ReturnType<typeof createState> }))
vi.mock('./session', () => ({ useConversation: () => holder.state }))
const stylesheet = document.createElement('style')
const resizeCallbacks = new Map<Element, () => void>()
const animationFrames = new Map<number, FrameRequestCallback>()
let animationTime = 0
let animationId = 0
const motionPreference = {
  matches: false,
  addEventListener: vi.fn(),
  removeEventListener: vi.fn()
}
function advanceFrames(count = 1) {
  for (let index = 0; index < count; index++) {
    animationTime += 16
    const pending = [...animationFrames.values()]
    animationFrames.clear()
    pending.forEach((callback) => callback(animationTime))
  }
}
beforeAll(() => {
  Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', {
    configurable: true,
    writable: true,
    value: vi.fn()
  })
  stylesheet.textContent = readFileSync('src/style.css', 'utf8')
  document.head.append(stylesheet)
  vi.stubGlobal(
    'ResizeObserver',
    class {
      constructor(private callback: () => void) {}
      observe(element: Element) {
        resizeCallbacks.set(element, this.callback)
      }
      unobserve() {}
      disconnect() {}
    }
  )
})
afterAll(() => {
  Reflect.deleteProperty(HTMLElement.prototype, 'scrollIntoView')
  stylesheet.remove()
  vi.unstubAllGlobals()
})
function createState() {
  return reactive({
    client: {
      json: vi.fn(async (_path: string): Promise<unknown> => ({
        items: [
          { command: '/plan', title: '规划', description: '规划命令' },
          { command: '/goal', title: '目标', description: '目标命令' }
        ]
      }))
    },
    target: '',
    userId: 'u',
    clientRef: 'c',
    selectedTarget: {},
    sessionId: '',
    agents: [{ id: 'a', name: '助手' }],
    teams: [] as Team[],
    sessions: [] as Session[],
    targetName: '助手',
    connection: '已连接',
    runId: '',
    runStatus: '',
    history: {
      messages: [] as Message[],
      processes: [] as Process[],
      work_plans: [] as Work[],
      pending_interactions: [] as Pending[],
      has_more: false,
      collaboration: { active: false, pending: false, selected_active: false }
    },
    draft: '',
    supplementDraft: '',
    inputMode: 'message',
    get composerDraft(): string {
      return this.inputMode === 'supplement' ? this.supplementDraft : this.draft
    },
    set composerDraft(value: string) {
      if (this.inputMode === 'supplement') this.supplementDraft = value
      else this.draft = value
    },
    runStates: {},
    artifacts: [],
    artifactsRevision: 0,
    steering: { items: [] },
    steeringSending: false,
    steeringLoading: false,
    steeringError: '',
    pendingSteering: undefined as SteeringInput | undefined,
    canSteer: false,
    submitSupplement: vi.fn(),
    refreshSteering: vi.fn(),
    refreshArtifacts: vi.fn(),
    setInputMode(mode: string) {
      this.inputMode = mode
    },
    attachments: [] as Attachment[],
    pastedTexts: [] as PastedTextAttachment[],
    attachmentFiles: {} as Record<string, File>,
    attachmentTexts: {} as Record<string, string>,
    stagePastedText: vi.fn((_text: string, _start?: number, _end?: number) => false),
    removePastedText: vi.fn(),
    removeAttachment: vi.fn(),
    restorePastedText: vi.fn((_id: string): number | undefined => undefined),
    restoreAttachmentText: vi.fn((_id: string): number | undefined => undefined),
    images: [] as Media[],
    references: [] as Reference[],
    error: '',
    notice: '',
    locked: false,
    active: false,
    uploading: false,
    sending: false,
    connecting: false,
    cancelling: false,
    loading: false,
    loadEarlier: vi.fn(async () => {}),
    openSession: vi.fn(),
    pendingInput: undefined as ChatInput | undefined,
    reconnect: vi.fn(),
    upload: vi.fn(async () => {}),
    submit: vi.fn(),
    ensureSession: vi.fn(async () => 's'),
    cancel: vi.fn(),
    newSession: vi.fn(),
    changeTarget: vi.fn(),
    disconnect: vi.fn(),
    fail: vi.fn()
  })
}
beforeEach(() => {
  resizeCallbacks.clear()
  animationFrames.clear()
  animationTime = 0
  animationId = 0
  motionPreference.matches = false
  vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => {
    const id = ++animationId
    animationFrames.set(id, callback)
    return id
  })
  vi.stubGlobal('cancelAnimationFrame', (id: number) => animationFrames.delete(id))
  vi.stubGlobal('matchMedia', (query: string) =>
    query.includes('prefers-reduced-motion')
      ? motionPreference
      : { matches: false, addEventListener: vi.fn(), removeEventListener: vi.fn() }
  )
  sessionStorage.clear()
  holder.state = createState()
})

describe('对话任务入口与单一侧栏', () => {
  it('取消关闭当前侧栏后不提前更改成果选择', async () => {
    holder.state.target = 'agent:a'
    holder.state.sessionId = 's'
    let allowClose = false
    const teamStub = defineComponent({
      setup(_, { expose }) {
        expose({ requestClose: async () => allowClose })
        return () => null
      }
    })
    const wrapper = mount(App, {
      global: { stubs: { TeamManager: teamStub, ArtifactPanel: true, BrowserDevices: true } }
    })
    try {
      await flushPromises()
      await wrapper.get('button[aria-label="打开会话导航"]').trigger('click')
      const teamButton = wrapper.findAll('button').find((item) => item.text().includes('团队管理'))!
      await teamButton.trigger('click')
      wrapper.findComponent(ConversationTimeline).vm.$emit('artifact', 'artifact-1')
      await flushPromises()
      expect(wrapper.findComponent(ArtifactPanel).props('selectedId')).toBe('')
      expect(wrapper.findComponent(ArtifactPanel).props('modelValue')).toBe(false)
      allowClose = true
      wrapper.findComponent(ConversationTimeline).vm.$emit('artifact', 'artifact-1')
      await flushPromises()
      expect(wrapper.findComponent(ArtifactPanel).props('selectedId')).toBe('artifact-1')
      expect(wrapper.findComponent(ArtifactPanel).props('modelValue')).toBe(true)
    } finally {
      wrapper.unmount()
    }
  })
  it('终止后准确展示状态并恢复发送入口，隐藏停止按钮', async () => {
    holder.state.target = 'agent:a'
    holder.state.runStatus = 'terminated'
    holder.state.draft = '新的消息'
    const wrapper = mount(App, {
      global: {
        stubs: {
          TeamManager: true,
          WorkspaceStatus: true,
          ArtifactPanel: true,
          BrowserDevices: true
        }
      }
    })
    try {
      await flushPromises()
      expect(wrapper.text()).toContain('已终止')
      expect(wrapper.find('button[aria-label="停止运行"]').exists()).toBe(false)
      expect(wrapper.find('button[aria-label="正在取消运行"]').exists()).toBe(false)
      expect(wrapper.get('#message-input').attributes('disabled')).toBeUndefined()
      expect(wrapper.get('button[aria-label="发送消息"]').attributes('disabled')).toBeUndefined()
      expect(holder.state.submit).not.toHaveBeenCalled()
    } finally {
      wrapper.unmount()
    }
  })
  it('显式目标没有普通聊天消息时，执行中不显示新会话欢迎页', async () => {
    holder.state.target = 'agent:a'
    holder.state.active = true
    const wrapper = mount(App, {
      global: {
        stubs: {
          TeamManager: true,
          WorkspaceStatus: true,
          ArtifactPanel: true,
          BrowserDevices: true
        }
      }
    })
    try {
      expect(wrapper.find('.empty-state').exists()).toBe(false)
      holder.state.active = false
      await flushPromises()
      expect(wrapper.find('.empty-state').exists()).toBe(true)
    } finally {
      wrapper.unmount()
    }
  })

  it('结果和输入留在对话中，显式查看任务才打开侧栏，后续进度保留旧任务选择及输入焦点', async () => {
    const old: Work = {
      work_id: 'old',
      input_id: 'input',
      session_id: 's',
      run_id: 'r',
      revision: 1,
      status: 'active',
      run_status: 'running',
      plan_revision: 2,
      approval_required: true,
      title: '经营复核',
      tasks: [
        {
          task_id: 'one',
          task_kind: 'plan',
          executor_type: 'PRIMARY',
          title: '读取原始数据',
          agent_name: '主助手',
          status: 'waiting',
          details_access: 'not_started'
        }
      ],
      results: []
    }
    holder.state.target = 'agent:a'
    holder.state.sessionId = 's'
    holder.state.history.work_plans = [old]
    holder.state.history.messages = [
      { message_id: 'input', message_version: 1, role: 'user', content: '核对经营数据' },
      {
        message_id: 'reply',
        message_version: 1,
        role: 'assistant',
        content: '已完成的核对结果',
        state: 'completed',
        phase: 'final'
      }
    ]
    holder.state.client.json.mockImplementation(async () => ({ items: [old] }))
    const wrapper = mount(App, {
      attachTo: document.body,
      global: {
        stubs: {
          TeamManager: true,
          WorkspaceStatus: true,
          ArtifactPanel: true,
          BrowserDevices: true
        }
      }
    })
    try {
      expect(wrapper.get('[data-message-id="reply"]').text()).toContain('已完成的核对结果')
      expect(wrapper.find('.task-details-panel').exists()).toBe(false)
      expect(wrapper.find('.task-plan-card ol').exists()).toBe(false)
      expect(wrapper.get('.task-plan-card').text()).toContain('第 2 版')
      await wrapper.get('.task-plan-card button').trigger('click')
      await flushPromises()
      expect(wrapper.get('.workspace').classes()).toContain('has-tasks')
      expect(wrapper.findAll('.task-details-panel')).toHaveLength(1)
      expect(wrapper.find('.task-details-panel dialog').exists()).toBe(true)
      expect(wrapper.get('.task-details-panel dialog').classes()).not.toContain('chat-drawer')
      const input = wrapper.get('.composer-dock textarea')
      await input.setValue('继续写下一条消息')
      ;(input.element as HTMLTextAreaElement).focus()
      holder.state.history.work_plans = [
        { ...old, revision: 2 },
        { ...old, work_id: 'new', input_id: 'new-input' }
      ]
      await flushPromises()
      expect(wrapper.findComponent(TaskDetailsPanel).props('selectedId')).toBe('old')
      expect(document.activeElement).toBe(input.element)
      expect(input.attributes('disabled')).toBeUndefined()
      expect(holder.state.draft).toBe('继续写下一条消息')
      await wrapper.get('[aria-label="关闭任务详情"]').trigger('click')
      await flushPromises()
      expect(wrapper.find('.task-details-panel').exists()).toBe(false)
      expect(wrapper.get('[data-message-id="reply"]').text()).toContain('已完成的核对结果')
    } finally {
      wrapper.unmount()
    }
  })
})

describe('顶部执行状态', () => {
  it('挂起执行展示通用中文状态，不将挂起推断为计划待批', async () => {
    holder.state.target = 'agent:a'
    holder.state.runStatus = 'suspended'
    const wrapper = mount(App, {
      global: {
        stubs: {
          TeamManager: true,
          WorkspaceStatus: true,
          ArtifactPanel: true,
          BrowserDevices: true
        }
      }
    })
    try {
      expect(wrapper.get('.connection-status').text()).toBe('已连接 · 已挂起')
      holder.state.runStatus = 'waiting_hitl'
      await flushPromises()
      expect(wrapper.get('.connection-status').text()).toBe('已连接 · 等待你的输入')
    } finally {
      wrapper.unmount()
    }
  })
})

describe('长文本粘贴附件接入', () => {
  function renderComposer() {
    holder.state.target = 'agent:a'
    return mount(App, {
      attachTo: document.body,
      global: {
        stubs: {
          TeamManager: true,
          WorkspaceStatus: true,
          ArtifactPanel: true,
          BrowserDevices: true
        }
      }
    })
  }
  it.each(['完整命令', '命令前缀', '中间选区'] as const)(
    '长目标%s原生粘贴保留完整目标并只发目标创建',
    async (mode) => {
      const long = '详细目标要求'.repeat(450)
      const original =
        mode === '完整命令' ? '' : mode === '命令前缀' ? '/goal ' : '/goal 前文待替换后文'
      const text = mode === '完整命令' ? '/goal ' + long : long
      const start = mode === '中间选区' ? original.indexOf('待替换') : original.length
      const end = mode === '中间选区' ? start + '待替换'.length : start
      const pasted = original.slice(0, start) + text + original.slice(end)
      holder.state.draft = original
      holder.state.sessionId = 's'
      holder.state.stagePastedText.mockReturnValue(true)
      holder.state.client.json.mockImplementation(async (path) =>
        path === '/chat/goals/create'
          ? {
              goal: {
                objective: pasted.slice(6),
                state: 'ACTIVE',
                rounds_started: 0,
                max_rounds: 256
              }
            }
          : { items: [] }
      )
      const wrapper = renderComposer()
      try {
        const input = wrapper.get<HTMLTextAreaElement>('#message-input')
        input.element.setSelectionRange(start, end)
        const event = new Event('paste', { bubbles: true, cancelable: true })
        Object.defineProperty(event, 'clipboardData', { value: { files: [], getData: () => text } })
        input.element.dispatchEvent(event)
        expect(event.defaultPrevented).toBe(false)
        expect(holder.state.stagePastedText).not.toHaveBeenCalled()
        // jsdom does not perform native clipboard insertion; apply the unchanged browser action.
        await input.setValue(pasted)
        await wrapper.get('.composer-form').trigger('submit')
        await flushPromises()
        expect(holder.state.client.json).toHaveBeenCalledWith('/chat/goals/create', 'POST', {
          session_id: 's',
          command_id: expect.any(String),
          objective: pasted.slice(6),
          max_rounds: 256
        })
        expect(holder.state.submit).not.toHaveBeenCalled()
        expect(holder.state.upload).not.toHaveBeenCalled()
        expect(holder.state.draft).toBe('')
        expect(holder.state.notice).toBe(
          '持续目标：进行中 · 0/256 轮。完整目标可在“会话工具”中查看。'
        )
        expect(holder.state.notice).not.toContain(long)
        expect(holder.state.notice.length).toBeLessThan(100)
      } finally {
        wrapper.unmount()
      }
    }
  )
  it('目标状态查询保留未知状态边界，顶部不铺目标正文', async () => {
    holder.state.sessionId = 's'
    holder.state.draft = '/goal status'
    holder.state.client.json.mockImplementation(async () => ({
      goal: {
        objective: '完整目标'.repeat(600),
        state: 'FUTURE_STATE',
        rounds_started: 2,
        max_rounds: 5
      }
    }))
    const wrapper = renderComposer()
    try {
      await wrapper.get('.composer-form').trigger('submit')
      await flushPromises()
      expect(holder.state.notice).toBe(
        '持续目标：状态待核实 · 2/5 轮。完整目标可在“会话工具”中查看。'
      )
      expect(holder.state.notice).not.toContain('完整目标完整目标')
      expect(holder.state.submit).not.toHaveBeenCalled()
    } finally {
      wrapper.unmount()
    }
  })
  it.each(['/goal ' + '目'.repeat(8001), '/goal --rounds invalid ' + '目'.repeat(2200)])(
    '无效长目标仍由发送校验拒绝而不转换附件',
    async (text) => {
      holder.state.stagePastedText.mockReturnValue(true)
      const wrapper = renderComposer()
      try {
        const input = wrapper.get<HTMLTextAreaElement>('#message-input')
        const event = new Event('paste', { bubbles: true, cancelable: true })
        Object.defineProperty(event, 'clipboardData', { value: { files: [], getData: () => text } })
        input.element.dispatchEvent(event)
        expect(event.defaultPrevented).toBe(false)
        expect(holder.state.stagePastedText).not.toHaveBeenCalled()
        await input.setValue(text)
        await wrapper.get('.composer-form').trigger('submit')
        await flushPromises()
        expect(holder.state.fail).toHaveBeenCalledWith(expect.any(Error))
        expect(holder.state.fail).toHaveBeenCalledWith(
          expect.objectContaining({ message: expect.stringMatching(/8000|格式/) })
        )
        expect(holder.state.ensureSession).not.toHaveBeenCalled()
        expect(
          holder.state.client.json.mock.calls.some(([path]) => path === '/chat/goals/create')
        ).toBe(false)
        expect(holder.state.submit).not.toHaveBeenCalled()
        expect(holder.state.draft).toBe(text)
      } finally {
        wrapper.unmount()
      }
    }
  )
  it.each(['/unknown ', '/goalkeeper '])('未知斜杠前缀%s仍按普通长消息转换', async (prefix) => {
    holder.state.stagePastedText.mockReturnValue(true)
    const wrapper = renderComposer()
    try {
      const text = prefix + '普通消息'.repeat(600)
      const event = new Event('paste', { bubbles: true, cancelable: true })
      Object.defineProperty(event, 'clipboardData', { value: { files: [], getData: () => text } })
      wrapper.get('#message-input').element.dispatchEvent(event)
      expect(event.defaultPrevented).toBe(true)
      expect(holder.state.stagePastedText).toHaveBeenCalledWith(text, 0, 0)
    } finally {
      wrapper.unmount()
    }
  })
  it('还原多种换行后同步 textarea 坐标，光标停在原文之后、已有后文之前', async () => {
    const raw = '第一行\r\n第二行\r第三行'
    holder.state.pastedTexts = [{ id: 'p', name: '粘贴文本.txt', text: raw, size: 40 }]
    holder.state.restorePastedText.mockImplementation(() => {
      holder.state.draft = `前文${raw}后文`
      holder.state.pastedTexts = []
      return 2 + raw.length
    })
    const wrapper = renderComposer()
    try {
      await wrapper.get('.draft-file-restore').trigger('click')
      await flushPromises()
      const input = wrapper.get('textarea').element as HTMLTextAreaElement
      expect(holder.state.draft).toBe('前文第一行\n第二行\n第三行后文')
      expect(input.value).toBe(holder.state.draft)
      expect(input.selectionStart).toBe(2 + raw.replace(/\r\n?/g, '\n').length)
      expect(input.selectionEnd).toBe(input.selectionStart)
    } finally {
      wrapper.unmount()
    }
  })
  it('长文本转换成功才阻止粘贴，并记录当时选区，保持已有草稿', async () => {
    const text = '长文本'.repeat(800)
    holder.state.draft = '前文选中内容后文'
    holder.state.stagePastedText.mockReturnValue(true)
    const wrapper = renderComposer()
    try {
      const input = wrapper.get('textarea')
      ;(input.element as HTMLTextAreaElement).setSelectionRange(2, 6)
      const event = new Event('paste', { bubbles: true, cancelable: true })
      Object.defineProperty(event, 'clipboardData', { value: { files: [], getData: () => text } })
      input.element.dispatchEvent(event)
      await flushPromises()
      expect(event.defaultPrevented).toBe(true)
      expect(holder.state.stagePastedText).toHaveBeenCalledWith(text, 2, 6)
      expect(holder.state.draft).toBe('前文选中内容后文')
      expect(holder.state.upload).not.toHaveBeenCalled()
    } finally {
      wrapper.unmount()
    }
  })
  it('短文本或转换被限制拒绝时不阻止原生粘贴；补充模式不转换', async () => {
    const wrapper = renderComposer()
    try {
      const input = wrapper.get('textarea')
      for (const text of ['简短的正文', '很长的受限文本'.repeat(1000)]) {
        const event = new Event('paste', { bubbles: true, cancelable: true })
        Object.defineProperty(event, 'clipboardData', { value: { files: [], getData: () => text } })
        input.element.dispatchEvent(event)
        expect(event.defaultPrevented).toBe(false)
      }
      holder.state.stagePastedText.mockClear()
      holder.state.inputMode = 'supplement'
      await flushPromises()
      const event = new Event('paste', { bubbles: true, cancelable: true })
      Object.defineProperty(event, 'clipboardData', {
        value: { files: [], getData: () => '补充'.repeat(2000) }
      })
      input.element.dispatchEvent(event)
      expect(event.defaultPrevented).toBe(false)
      expect(holder.state.stagePastedText).not.toHaveBeenCalled()
    } finally {
      wrapper.unmount()
    }
  })
  it.each([false, true])(
    '运行中粘贴资料继续交给下一条草稿上传，不发送：image=%s',
    async (image) => {
      holder.state.active = true
      holder.state.draft = '下一条文字'
      const wrapper = renderComposer()
      try {
        const file = new File(['abc'], image ? 'clipboard.png' : 'clipboard.txt', {
          type: image ? 'image/png' : 'text/plain'
        })
        const event = new Event('paste', { bubbles: true, cancelable: true })
        Object.defineProperty(event, 'clipboardData', {
          value: { files: [file], getData: () => '' }
        })
        wrapper.get('textarea').element.dispatchEvent(event)
        await flushPromises()
        expect(event.defaultPrevented).toBe(true)
        expect(holder.state.upload).toHaveBeenCalledExactlyOnceWith([file])
        expect(holder.state.submit).not.toHaveBeenCalled()
        expect(holder.state.draft).toBe('下一条文字')
      } finally {
        wrapper.unmount()
      }
    }
  )
  it('同时粘贴图片和短文本时上传图片并保留原生文本输入', async () => {
    const wrapper = renderComposer()
    try {
      const file = new File(['synthetic-image'], 'clipboard.png', { type: 'image/png' })
      const event = new Event('paste', { bubbles: true, cancelable: true })
      Object.defineProperty(event, 'clipboardData', {
        value: { files: [file], getData: () => '图片说明' }
      })
      wrapper.get('textarea').element.dispatchEvent(event)
      await flushPromises()
      expect(event.defaultPrevented).toBe(false)
      expect(holder.state.upload).toHaveBeenCalledWith([file])
      expect(holder.state.stagePastedText).toHaveBeenCalledWith('图片说明', 0, 0)
    } finally {
      wrapper.unmount()
    }
  })
  it('同时粘贴图片和长文本时保留文本附件并上传图片', async () => {
    holder.state.stagePastedText.mockReturnValue(true)
    const wrapper = renderComposer()
    try {
      const file = new File(['image'], 'clipboard.png', { type: 'image/png' })
      const text = '长文本'.repeat(800)
      const event = new Event('paste', { bubbles: true, cancelable: true })
      Object.defineProperty(event, 'clipboardData', {
        value: { files: [file], getData: () => text }
      })
      wrapper.get('textarea').element.dispatchEvent(event)
      await flushPromises()
      expect(event.defaultPrevented).toBe(true)
      expect(holder.state.stagePastedText).toHaveBeenCalledWith(text, 0, 0)
      expect(holder.state.upload).toHaveBeenCalledExactlyOnceWith([file])
    } finally {
      wrapper.unmount()
    }
  })
  it('文件卡片处于输入框之前，附件-only可发送，还原调用保存选区而非当前光标', async () => {
    holder.state.pastedTexts = [{ id: 'p', name: '粘贴文本.txt', text: '原文', size: 6 }]
    holder.state.restorePastedText.mockImplementation(() => {
      holder.state.draft = '原文'
      holder.state.pastedTexts = []
      return 2
    })
    const wrapper = renderComposer()
    try {
      const resources = wrapper.get('.draft-resources').element
      const input = wrapper.get('textarea').element as HTMLTextAreaElement
      expect(
        resources.compareDocumentPosition(input) & Node.DOCUMENT_POSITION_FOLLOWING
      ).toBeTruthy()
      expect(wrapper.get('button[aria-label="发送消息"]').attributes('disabled')).toBeUndefined()
      await wrapper.get('.draft-file-restore').trigger('click')
      await flushPromises()
      expect(holder.state.restorePastedText).toHaveBeenCalledWith('p')
      expect(input.selectionStart).toBe(2)
      expect(input.selectionEnd).toBe(2)
      expect(document.activeElement).toBe(input)
    } finally {
      wrapper.unmount()
    }
  })
})

describe('流式滚动跟随', () => {
  it('加载旧消息时保持阅读位置，用户回到最新后不再执行旧位置补偿', async () => {
    holder.state.sessionId = 's'
    holder.state.target = 'agent:a'
    holder.state.history.has_more = true
    let complete!: () => void
    holder.state.loadEarlier.mockImplementation(
      () =>
        new Promise<void>((resolve) => {
          complete = resolve
        })
    )
    const wrapper = mount(App, { global: { stubs: { TeamManager: true, WorkspaceStatus: true } } })
    try {
      await flushPromises()
      const timeline = wrapper.get('.timeline').element as HTMLElement
      let height = 1000,
        top = 100
      Object.defineProperties(timeline, {
        scrollHeight: { configurable: true, get: () => height },
        clientHeight: { configurable: true, get: () => 500 },
        scrollTop: {
          configurable: true,
          get: () => top,
          set: (value: number) => {
            top = value
          }
        }
      })
      await wrapper.get('.timeline button').trigger('click')
      height = 1300
      complete()
      await flushPromises()
      expect(top).toBe(400)
      await wrapper.get('.timeline button').trigger('click')
      await wrapper.get('.latest-button').trigger('click')
      expect(top).toBe(1300)
      height = 1600
      complete()
      await flushPromises()
      expect(top).toBe(1300)
    } finally {
      wrapper.unmount()
    }
  })
  it('切换会话后旧分页结果不能移动新会话位置', async () => {
    holder.state.sessionId = 's'
    holder.state.target = 'agent:a'
    holder.state.history.has_more = true
    let complete!: () => void
    holder.state.loadEarlier.mockImplementation(
      () =>
        new Promise<void>((resolve) => {
          complete = resolve
        })
    )
    const wrapper = mount(App, { global: { stubs: { TeamManager: true, WorkspaceStatus: true } } })
    try {
      await flushPromises()
      const timeline = wrapper.get('.timeline').element as HTMLElement
      let height = 1000,
        top = 100
      Object.defineProperties(timeline, {
        scrollHeight: { configurable: true, get: () => height },
        clientHeight: { configurable: true, get: () => 500 },
        scrollTop: {
          configurable: true,
          get: () => top,
          set: (value: number) => {
            top = value
          }
        }
      })
      await wrapper.get('.timeline button').trigger('click')
      holder.state.sessionId = 'other'
      await flushPromises()
      height = 1600
      complete()
      await flushPromises()
      expect(top).toBe(100)
    } finally {
      wrapper.unmount()
    }
  })
  it('接近底部跟随递增正文，滚走后正文和异步布局均不能抢回位置', async () => {
    holder.state.sessionId = 's'
    holder.state.target = 'agent:a'
    holder.state.history.messages = [
      {
        message_id: 'a',
        message_version: 1,
        role: 'assistant',
        content: '首段安全正文',
        phase: 'pending',
        state: 'streaming',
        root_run_id: 'r'
      }
    ]
    const wrapper = mount(App, { global: { stubs: { TeamManager: true, WorkspaceStatus: true } } })
    try {
      await flushPromises()
      const timeline = wrapper.get('.timeline').element as HTMLElement
      let height = 1000,
        top = 500
      const write = vi.fn((value: number) => {
        top = Math.min(value, height - 500)
      })
      Object.defineProperties(timeline, {
        scrollHeight: { configurable: true, get: () => height },
        clientHeight: { configurable: true, get: () => 500 },
        scrollTop: { configurable: true, get: () => top, set: write }
      })
      timeline.dispatchEvent(new Event('scroll'))
      height = 1300
      holder.state.history.messages = [
        {
          ...holder.state.history.messages[0]!,
          message_version: 2,
          content: '首段安全正文持续增加'
        }
      ]
      await flushPromises()
      expect(top).toBe(500)
      advanceFrames()
      expect(top).toBeGreaterThan(500)
      expect(top).toBeLessThan(800)
      // 自己写入引发的 scroll 不得误判为用户脱离底部。
      timeline.dispatchEvent(new Event('scroll'))
      advanceFrames(18)
      expect(top).toBe(800)
      top = 150
      timeline.dispatchEvent(new Event('scroll'))
      write.mockClear()
      height = 1600
      holder.state.history.messages = [
        {
          ...holder.state.history.messages[0]!,
          message_version: 3,
          content: '首段安全正文持续增加，用户正在阅读更早内容'
        }
      ]
      resizeCallbacks.get(wrapper.get('.timeline-content').element)?.()
      await flushPromises()
      advanceFrames(20)
      expect(top).toBe(150)
      expect(write).not.toHaveBeenCalled()
      expect(wrapper.find('.latest-button').exists()).toBe(true)
      await wrapper.get('.latest-button').trigger('click')
      expect(top).toBe(1100)
      // 布局跟随已经排队，但用户在 nextTick 前滚离底部时必须撤销该次移动。
      resizeCallbacks.get(wrapper.get('.timeline-content').element)?.()
      top = 200
      timeline.dispatchEvent(new Event('scroll'))
      write.mockClear()
      await flushPromises()
      advanceFrames(20)
      expect(write).not.toHaveBeenCalled()
      expect(top).toBe(200)
    } finally {
      wrapper.unmount()
    }
    expect(animationFrames.size).toBe(0)
  })
  it('用户在底部附近上滚也立即暂停，布局回调和键盘不能抢回阅读位置', async () => {
    holder.state.sessionId = 's'
    holder.state.target = 'agent:a'
    const wrapper = mount(App, { global: { stubs: { TeamManager: true, WorkspaceStatus: true } } })
    try {
      await flushPromises()
      const timeline = wrapper.get('.timeline').element as HTMLElement
      let height = 1000,
        top = 500
      const write = vi.fn((value: number) => {
        top = Math.min(value, height - 500)
      })
      Object.defineProperties(timeline, {
        scrollHeight: { configurable: true, get: () => height },
        clientHeight: { configurable: true, get: () => 500 },
        scrollTop: { configurable: true, get: () => top, set: write }
      })
      advanceFrames(2)
      height = 1040
      resizeCallbacks.get(wrapper.get('.timeline-content').element)?.()
      await flushPromises()
      expect(animationFrames.size).toBe(1)
      timeline.dispatchEvent(new WheelEvent('wheel', { deltaY: -20 }))
      top = 480
      timeline.dispatchEvent(new Event('scroll'))
      write.mockClear()
      resizeCallbacks.get(wrapper.get('.timeline-content').element)?.()
      await flushPromises()
      advanceFrames(20)
      expect(top).toBe(480)
      expect(write).not.toHaveBeenCalled()
      expect(wrapper.find('.latest-button').exists()).toBe(true)
      await wrapper.get('.latest-button').trigger('click')
      expect(top).toBe(540)
      timeline.dispatchEvent(new KeyboardEvent('keydown', { key: 'PageUp' }))
      height = 1080
      resizeCallbacks.get(wrapper.get('.timeline-content').element)?.()
      await flushPromises()
      advanceFrames(20)
      expect(top).toBe(540)
      expect(wrapper.find('.latest-button').exists()).toBe(true)
    } finally {
      wrapper.unmount()
    }
  })
  it('减少动态效果时直接贴底，销毁或切换会话取消上一帧', async () => {
    motionPreference.matches = true
    holder.state.sessionId = 's'
    holder.state.target = 'agent:a'
    const wrapper = mount(App, { global: { stubs: { TeamManager: true, WorkspaceStatus: true } } })
    try {
      await flushPromises()
      const timeline = wrapper.get('.timeline').element as HTMLElement
      let top = 500
      Object.defineProperties(timeline, {
        scrollHeight: { configurable: true, get: () => 1300 },
        clientHeight: { configurable: true, get: () => 500 },
        scrollTop: {
          configurable: true,
          get: () => top,
          set: (value: number) => {
            top = value
          }
        }
      })
      advanceFrames()
      expect(top).toBe(800)
      expect(animationFrames.size).toBe(0)
      resizeCallbacks.get(wrapper.get('.timeline-content').element)?.()
      await flushPromises()
      expect(animationFrames.size).toBe(1)
      holder.state.sessionId = 'another'
      await flushPromises()
      expect(animationFrames.size).toBe(0)
      resizeCallbacks.get(wrapper.get('.timeline-content').element)?.()
      await flushPromises()
      expect(animationFrames.size).toBe(1)
    } finally {
      wrapper.unmount()
    }
    expect(animationFrames.size).toBe(0)
  })
  it('缩放导致物理滚动上限带小数时停止动画，不在底部持续申请帧', async () => {
    holder.state.sessionId = 's'
    holder.state.target = 'agent:a'
    const wrapper = mount(App, { global: { stubs: { TeamManager: true, WorkspaceStatus: true } } })
    try {
      await flushPromises()
      const timeline = wrapper.get('.timeline').element as HTMLElement
      let top = 500
      Object.defineProperties(timeline, {
        scrollHeight: { configurable: true, get: () => 1300 },
        clientHeight: { configurable: true, get: () => 500 },
        scrollTop: {
          configurable: true,
          get: () => top,
          set: (value: number) => {
            top = Math.min(799.25, value)
          }
        }
      })
      advanceFrames(20)
      expect(top).toBe(799.25)
      expect(animationFrames.size).toBe(0)
    } finally {
      wrapper.unmount()
    }
  })
})
describe('会话输入控件', () => {
  it('查询原补充期间禁用重试入口', async () => {
    holder.state.pendingSteering = { session_id: 's', run_id: 'r', command_id: 'c', text: '补充' }
    holder.state.steeringLoading = true
    const wrapper = mount(App, { global: { stubs: { TeamManager: true } } })
    try {
      await flushPromises()
      expect(
        wrapper
          .findAll('button')
          .find((button) => button.text() === '重试原补充')!
          .attributes('disabled')
      ).toBeDefined()
    } finally {
      wrapper.unmount()
    }
  })
  it('正常提交不显示未知结果警告，失败后才显示恢复与原请求重试', async () => {
    holder.state.pendingInput = {
      session_id: 's',
      client_message_id: 'input',
      content: [],
      context: { end_user_id: 'u' }
    }
    holder.state.sending = true
    const wrapper = mount(App, { global: { stubs: { TeamManager: true } } })
    try {
      await flushPromises()
      expect(wrapper.text()).not.toContain('上次提交结果尚未确认')
      holder.state.sending = false
      await flushPromises()
      expect(wrapper.text()).toContain('上次提交结果尚未确认')
      holder.state.loading = true
      await flushPromises()
      for (const label of ['恢复观察', '重试原请求'])
        expect(
          wrapper
            .findAll('button')
            .find((button) => button.text() === label)!
            .attributes('disabled')
        ).toBeDefined()
      holder.state.loading = false
      await flushPromises()
      await wrapper
        .findAll('button')
        .find((button) => button.text() === '恢复观察')!
        .trigger('click')
      expect(holder.state.reconnect).toHaveBeenCalledOnce()
      await wrapper
        .findAll('button')
        .find((button) => button.text() === '重试原请求')!
        .trigger('click')
      expect(holder.state.submit).toHaveBeenCalledWith(undefined, true)
    } finally {
      wrapper.unmount()
    }
  })
  it('工作区摘要在切换会话和运行完结后刷新，运行中的变化不持续请求', async () => {
    holder.state.sessionId = 'session-a'
    holder.state.client.json.mockImplementation(async (path: string) =>
      path.startsWith('/chat/sessions/workspace?')
        ? {
            workspaces: [
              {
                sessionId: holder.state.sessionId,
                isChild: false,
                status: {
                  workspaceId: 'private-workspace',
                  revision: 1,
                  backupRevision: 0,
                  localAvailable: true,
                  phase: 'ready',
                  backupError: null,
                  lastBackupAt: null
                }
              }
            ]
          }
        : { items: [] }
    )
    const wrapper = mount(App, {
      global: {
        stubs: {
          TeamManager: true,
          ConversationTools: true,
          ArtifactPanel: true,
          BrowserDevices: true
        }
      }
    })
    try {
      await flushPromises()
      expect(wrapper.getComponent(WorkspaceStatus).text()).toContain('已保存修订 1')
      const statusCalls = () =>
        holder.state.client.json.mock.calls.filter(([path]) =>
          path.startsWith('/chat/sessions/workspace?')
        )
      expect(statusCalls()).toHaveLength(1)
      Object.assign(holder.state.runStates, { run: 'running' })
      await flushPromises()
      expect(statusCalls()).toHaveLength(1)
      Object.assign(holder.state.runStates, { run: 'completed' })
      await flushPromises()
      expect(statusCalls()).toHaveLength(2)
      Object.assign(holder.state.runStates, { run: 'terminated' })
      await flushPromises()
      expect(statusCalls()).toHaveLength(3)
      holder.state.sessionId = 'session-b'
      await flushPromises()
      expect(statusCalls()).toHaveLength(4)
      expect(statusCalls().at(-1)![0]).toContain('sessionId=session-b')
    } finally {
      wrapper.unmount()
    }
  })
  it('两个待办分别显示且允许新消息，停止按钮只指向所属执行', async () => {
    holder.state.target = 'agent:a'
    holder.state.history.pending_interactions = ['first', 'second'].map((id) => ({
      execution_id: id,
      run_revision: 2,
      checkpoint_revision: 2,
      pending_ref: id,
      question: `确认 ${id}`
    }))
    const wrapper = mount(App, { global: { stubs: { TeamManager: true } } })
    await flushPromises()
    expect(wrapper.text()).toContain('确认 first')
    expect(wrapper.text()).toContain('确认 second')
    expect(wrapper.get('#message-input').attributes('disabled')).toBeUndefined()
    const stops = wrapper.findAll('button[aria-label="停止此等待任务"]')
    expect(stops).toHaveLength(2)
    await stops[0]!.trigger('click')
    expect(holder.state.cancel).toHaveBeenCalledWith('first')
    wrapper.unmount()
  })
  it('同一运行的不同工具等待使用各自表单，同一等待刷新保留草稿', async () => {
    holder.state.sessionId = 'session'
    const wait = (action: string, field: string): Pending => ({
      execution_id: 'run',
      run_revision: '2',
      interaction_type: 'tool_input',
      tool_wait: { action_id: action, generation: '1' },
      card: {
        type: 'tool_input',
        title: '补充信息',
        requests: [
          {
            id: 'request',
            message: '填写参数',
            requested_schema: { type: 'object', properties: { [field]: { type: 'string' } } }
          }
        ]
      }
    })
    holder.state.history.pending_interactions = [wait('action-a', 'first')]
    const wrapper = mount(App, { global: { stubs: { TeamManager: true } } })
    try {
      await flushPromises()
      await wrapper.getComponent(PendingForm).get('input').setValue('上一动作草稿')
      holder.state.history.pending_interactions = [
        { ...wait('action-a', 'first'), run_revision: '3' }
      ]
      await flushPromises()
      expect(wrapper.getComponent(PendingForm).get<HTMLInputElement>('input').element.value).toBe(
        '上一动作草稿'
      )
      holder.state.history.pending_interactions = [wait('action-b', 'second')]
      await flushPromises()
      const form = wrapper.getComponent(PendingForm)
      expect(form.get<HTMLInputElement>('input').element.value).toBe('')
      await form.get('input').setValue('新动作内容')
      await form.get('form').trigger('submit')
      await flushPromises()
      expect(holder.state.submit).toHaveBeenCalledWith(
        expect.objectContaining({
          tool_input: expect.objectContaining({
            wait: { action_id: 'action-b', generation: '1' },
            responses: expect.objectContaining({
              request: expect.objectContaining({
                result: expect.objectContaining({
                  canonical_json: '{"action":"accept","content":{"second":"新动作内容"}}'
                })
              })
            })
          })
        })
      )
    } finally {
      wrapper.unmount()
    }
  })
  it('仅隐藏同一执行已经在正文中展示的问题，不吞掉其他执行的同名提问', async () => {
    holder.state.target = 'agent:a'
    holder.state.history.pending_interactions = ['first', 'second'].map((id) => ({
      execution_id: id,
      run_revision: 2,
      checkpoint_revision: 2,
      question: '希望如何处理附件？'
    }))
    holder.state.history.messages = [
      {
        message_id: 'question',
        message_version: 1,
        run_id: 'first',
        role: 'assistant',
        content: '希望如何处理附件？'
      }
    ]
    const wrapper = mount(App, { global: { stubs: { TeamManager: true } } })
    try {
      await flushPromises()
      const forms = wrapper.findAllComponents(PendingForm)
      expect(forms[0]!.props('questionDisplayed')).toBe(true)
      expect(forms[1]!.props('questionDisplayed')).toBe(false)
      expect(forms[0]!.find('.pending-question').exists()).toBe(false)
      expect(forms[1]!.get('.pending-question').text()).toBe('希望如何处理附件？')
    } finally {
      wrapper.unmount()
    }
  })
  it.each(['loading', 'sending', 'pendingInput'] as const)(
    '历史导航只放行可取消读取：%s',
    async (operation) => {
      holder.state.sessions = [{ session_id: 'next', summary: '下一段会话', message_count: 1 }]
      holder.state.locked = true
      holder.state.loading = true
      if (operation === 'sending') holder.state.sending = true
      if (operation === 'pendingInput')
        holder.state.pendingInput = {
          session_id: 's',
          client_message_id: 'input',
          content: [{ type: 'text', text: '未知结果' }],
          context: { end_user_id: 'u' }
        }
      const wrapper = mount(App, {
        attachTo: document.body,
        global: { stubs: { TeamManager: true } }
      })
      holder.state.target = 'agent:a'
      await wrapper.get('[aria-label="打开会话导航"]').trigger('click')
      await flushPromises()
      const button = document.querySelector<HTMLButtonElement>('.sessions button')!
      expect(button.disabled).toBe(operation !== 'loading')
      // 直接触发原生 click 同时覆盖 navigate 的处理器边界，而不只检查 disabled 展示。
      button.dispatchEvent(new MouseEvent('click', { bubbles: true }))
      await flushPromises()
      if (operation === 'loading') expect(holder.state.openSession).toHaveBeenCalledWith('next')
      else expect(holder.state.openSession).not.toHaveBeenCalled()
      wrapper.unmount()
    }
  )

  it('点击当前会话不丢弃草稿或重新读取，切换其他会话仍需确认', async () => {
    holder.state.target = 'agent:a'
    holder.state.sessionId = 'current'
    holder.state.draft = '未发送的内容'
    holder.state.sessions = [
      { session_id: 'current', title: '当前会话', message_count: 1 },
      { session_id: 'other', title: '其他会话', message_count: 1 }
    ]
    const confirm = vi.spyOn(ChatMessageBox, 'confirm').mockRejectedValue('cancel')
    const wrapper = mount(App, {
      attachTo: document.body,
      global: { stubs: { TeamManager: true } }
    })
    try {
      await wrapper.get('[aria-label="打开会话导航"]').trigger('click')
      await wrapper.findAll('.sessions button')[0]!.trigger('click')
      await flushPromises()
      expect(confirm).not.toHaveBeenCalled()
      expect(holder.state.openSession).not.toHaveBeenCalled()
      expect(holder.state.draft).toBe('未发送的内容')
      await wrapper.findAll('.sessions button')[1]!.trigger('click')
      await flushPromises()
      expect(confirm).toHaveBeenCalledOnce()
      expect(holder.state.openSession).not.toHaveBeenCalled()
      expect(holder.state.draft).toBe('未发送的内容')
    } finally {
      confirm.mockRestore()
      wrapper.unmount()
    }
  })

  it('实际组件命中发送区、长历史标题及命令滚动样式', async () => {
    holder.state.sessions = [
      { session_id: 's', title: '很长的历史会话标题'.repeat(30), message_count: 12 }
    ]
    const wrapper = mount(App, {
      attachTo: document.body,
      global: { stubs: { TeamManager: true } }
    })
    holder.state.target = 'agent:a'
    await flushPromises()
    expect(getComputedStyle(wrapper.get('.composer-actions').element).display).toBe('contents')
    await wrapper.get('[aria-label="打开会话导航"]').trigger('click')
    await flushPromises()
    const title = wrapper.get('.sessions .chat-button > span')
    expect(getComputedStyle(title.element).textOverflow).toBe('ellipsis')
    await wrapper.get('textarea').setValue('/')
    await flushPromises()
    const menu = document.querySelector<HTMLElement>('.command-menu')!
    expect(menu).not.toBeNull()
    expect(getComputedStyle(menu).maxHeight).toBe('230px')
    expect(getComputedStyle(menu).overflow).toBe('auto')
    expect(wrapper.get('#message-input').attributes('aria-expanded')).toBeUndefined()
    expect(wrapper.get('#message-input').attributes('aria-controls')).toBe('command-options')
    const option = menu.querySelector<HTMLElement>('.chat-button')!
    expect(getComputedStyle(option).height).toBe('auto')
    expect(getComputedStyle(option).whiteSpace).toBe('normal')
    wrapper.unmount()
  })

  it('键盘切换命令时让当前选项进入可见范围', async () => {
    const wrapper = mount(App, {
      attachTo: document.body,
      global: { stubs: { TeamManager: true } }
    })
    holder.state.target = 'agent:a'
    await flushPromises()
    const input = wrapper.get('#message-input')
    await input.setValue('/')
    await flushPromises()
    const option = document.getElementById('command-option-1')!
    option.scrollIntoView = vi.fn()
    await input.trigger('keydown', { key: 'ArrowDown' })
    await flushPromises()
    expect(input.attributes('aria-activedescendant')).toBe('command-option-1')
    expect(option.scrollIntoView).toHaveBeenCalledWith({ block: 'nearest' })
    wrapper.unmount()
  })

  it('选择对话对象时可按角色说明筛选并用键盘选中', async () => {
    Object.assign(holder.state.agents[0]!, { role_description: '负责订单查询' })
    holder.state.agents.push({ id: 'b', name: '报告助手' })
    const wrapper = mount(App, {
      attachTo: document.body,
      global: { stubs: { TeamManager: true } }
    })
    await wrapper.get('[aria-label="打开会话导航"]').trigger('click')
    await flushPromises()
    await wrapper.get('.target-picker [role="combobox"]').trigger('click')
    await flushPromises()
    const search = document.querySelector<HTMLInputElement>('input[aria-label="搜索选项"]')!
    search.value = '订单查询'
    search.dispatchEvent(new Event('input', { bubbles: true }))
    await flushPromises()
    const active = document.getElementById(search.getAttribute('aria-activedescendant')!)!
    expect(active.textContent).toContain('助手')
    expect(active.textContent).toContain('负责订单查询')
    search.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }))
    await flushPromises()
    expect(holder.state.changeTarget).toHaveBeenCalledWith('agent:a')
    wrapper.unmount()
  })
  it('历史搜索使用稳定的首条消息标题而非画像摘要或列表序号', async () => {
    holder.state.sessions = [
      { session_id: 'a', title: '预约复查', summary: '用户画像', message_count: 2 },
      { session_id: 'b', title: null, message_count: 1 }
    ]
    const wrapper = mount(App, {
      attachTo: document.body,
      global: { stubs: { TeamManager: true } }
    })
    holder.state.target = 'agent:a'
    await wrapper.get('[aria-label="打开会话导航"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('.sessions').text()).toContain('预约复查')
    expect(wrapper.get('.sessions').text()).toContain('无文字或空会话')
    await wrapper.get('[aria-label="搜索会话"]').setValue('复查')
    expect(wrapper.get('.sessions').text()).toContain('预约复查')
    expect(wrapper.get('.sessions').text()).not.toContain('无文字或空会话')
    await wrapper.get('[aria-label="搜索会话"]').setValue('用户画像')
    expect(wrapper.get('.sessions').text()).toContain('没有匹配的会话')
    wrapper.unmount()
  })

  it('停用当前团队保留会话和草稿，仅禁用新建会话', async () => {
    const team: Team = {
      id: 'team-1',
      revision: 1,
      name: '咨询团队',
      description: '',
      status: 'enabled',
      primary_agent_id: 'a',
      members: [{ agent_id: 'a' }]
    }
    holder.state.target = 'team:team-1'
    holder.state.sessionId = 'existing-session'
    holder.state.teams = [team]
    holder.state.draft = '尚未提交的内容'
    const wrapper = mount(App, {
      attachTo: document.body,
      global: { stubs: { TeamManager: true, ConversationTools: true } }
    })
    wrapper.getComponent(TeamManager).vm.$emit('saved', { ...team, status: 'disabled' })
    await flushPromises()
    await wrapper.get('[aria-label="打开会话导航"]').trigger('click')
    await flushPromises()
    expect(holder.state.changeTarget).not.toHaveBeenCalled()
    expect(holder.state.target).toBe('team:team-1')
    expect(holder.state.sessionId).toBe('existing-session')
    expect(holder.state.draft).toBe('尚未提交的内容')
    expect(holder.state.notice).toContain('已保留当前会话与草稿')
    expect(
      wrapper
        .findAll('button')
        .find((item) => item.text().includes('新对话'))!
        .attributes('disabled')
    ).toBeDefined()
    wrapper.unmount()
  })

  it('新会话与断开连接保留被取消放弃的草稿', async () => {
    const confirm = vi.spyOn(ChatMessageBox, 'confirm').mockRejectedValue('cancel')
    const wrapper = mount(App, {
      attachTo: document.body,
      global: { stubs: { TeamManager: true } }
    })
    holder.state.target = 'agent:a'
    holder.state.draft = '尚未发送'
    await flushPromises()
    await wrapper.get('[aria-label="打开会话导航"]').trigger('click')
    await flushPromises()
    await wrapper
      .findAll('button')
      .find((item) => item.text().includes('新对话'))!
      .trigger('click')
    await flushPromises()
    expect(holder.state.newSession).not.toHaveBeenCalled()
    await wrapper
      .findAll('button')
      .find((item) => item.text() === '断开连接')!
      .trigger('click')
    await flushPromises()
    expect(holder.state.disconnect).not.toHaveBeenCalled()
    expect(holder.state.draft).toBe('尚未发送')
    expect(confirm).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })
  it('输入法确认和 Shift Enter 不发送，普通 Enter 提交', async () => {
    const wrapper = mount(App, {
      attachTo: document.body,
      global: { stubs: { TeamManager: true } }
    })
    holder.state.target = 'agent:a'
    await flushPromises()
    const input = wrapper.get('textarea')
    await input.setValue('测试输入')
    await input.trigger('keydown', { key: 'Enter', ctrlKey: true, isComposing: true })
    await input.trigger('keydown', { key: 'Enter', ctrlKey: true, keyCode: 229 })
    await input.trigger('keydown', { key: 'Enter', shiftKey: true })
    await flushPromises()
    expect(holder.state.submit).not.toHaveBeenCalled()
    await input.trigger('keydown', { key: 'Enter' })
    await flushPromises()
    expect(holder.state.submit).toHaveBeenCalledOnce()
    expect(input.attributes('aria-describedby')).toBe('composer-help')
    wrapper.unmount()
  })

  it('命令上下键选择、Enter 填写以及 Escape 隐藏，不提前发送', async () => {
    const wrapper = mount(App, {
      attachTo: document.body,
      global: { stubs: { TeamManager: true } }
    })
    holder.state.target = 'agent:a'
    await flushPromises()
    const input = wrapper.get('textarea')
    await input.setValue('/')
    await input.trigger('keydown', { key: 'ArrowDown' })
    await input.trigger('keydown', { key: 'Enter' })
    await flushPromises()
    expect(holder.state.draft).toBe('/goal ')
    expect(holder.state.submit).not.toHaveBeenCalled()
    await input.setValue('/')
    await input.trigger('keydown', { key: 'Escape' })
    await flushPromises()
    expect(
      wrapper
        .findAllComponents(ChatPopover)
        .find((popover) => popover.props('placement') === 'top-start')
        ?.props('visible')
    ).toBe(false)
    wrapper.unmount()
  })
  it('一次选择的多份附件合并交给签名上传方法', async () => {
    const wrapper = mount(App, {
      attachTo: document.body,
      global: { stubs: { TeamManager: true } }
    })
    holder.state.target = 'agent:a'
    await flushPromises()
    const upload = wrapper.findAllComponents(ChatUpload)[0]!
    const first = Object.assign(new File(['1'], 'one.txt'), { uid: 1 }),
      second = Object.assign(new File(['2'], 'two.txt'), { uid: 2 })
    const input = upload.get('input[type="file"]')
    Object.defineProperty(input.element, 'files', { value: [first, second], configurable: true })
    await input.trigger('change')
    await flushPromises()
    expect(holder.state.upload).toHaveBeenCalledTimes(1)
    expect(holder.state.upload).toHaveBeenCalledWith([first, second])
    wrapper.unmount()
  })
  it('同一次选择的图片和文档作为一个批次上传', async () => {
    const wrapper = mount(App, {
      global: { stubs: { TeamManager: true, ArtifactPanel: true, BrowserDevices: true } }
    })
    holder.state.target = 'agent:a'
    await flushPromises()
    expect(wrapper.findAllComponents(ChatUpload)).toHaveLength(1)
    const input = wrapper.getComponent(ChatUpload).get('input[type="file"]')
    const document = Object.assign(new File(['report'], 'report.txt', { type: 'text/plain' }), {
      uid: 1
    })
    const image = Object.assign(new File(['pixels'], 'photo.png', { type: 'image/png' }), {
      uid: 2
    })
    Object.defineProperty(input.element, 'files', { value: [document, image], configurable: true })
    await input.trigger('change')
    await flushPromises()
    expect(holder.state.upload).toHaveBeenCalledOnce()
    expect(holder.state.upload).toHaveBeenCalledWith([document, image])
    wrapper.unmount()
  })
  it('运行中补充和下一条草稿独立，Enter 只提交补充', async () => {
    holder.state.target = 'agent:a'
    holder.state.active = true
    holder.state.canSteer = true
    holder.state.inputMode = 'supplement'
    holder.state.draft = '下一条消息'
    const wrapper = mount(App, {
      global: { stubs: { TeamManager: true, ArtifactPanel: true, BrowserDevices: true } }
    })
    await wrapper.get('textarea').setValue('补充要求')
    await wrapper.get('textarea').trigger('keydown', { key: 'Enter' })
    await flushPromises()
    expect(holder.state.submitSupplement).toHaveBeenCalledOnce()
    expect(holder.state.submit).not.toHaveBeenCalled()
    expect(holder.state.draft).toBe('下一条消息')
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '下一条草稿')!
      .trigger('click')
    // 输入模式切换会连续调度 textarea 尺寸计算，等组件更新静止后再卸载。
    await flushPromises()
    expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toBe('下一条消息')
    wrapper.unmount()
  })
})

describe('浏览器离开保护', () => {
  it('未发送正文和补充草稿在刷新前确认，清空和卸载后释放保护', async () => {
    const wrapper = mount(App, {
      global: {
        stubs: {
          TeamManager: true,
          WorkspaceStatus: true,
          BrowserDevices: true,
          ArtifactPanel: true
        }
      }
    })
    const protectedByBrowser = () => {
      const event = new Event('beforeunload', { cancelable: true })
      window.dispatchEvent(event)
      return event.defaultPrevented
    }
    expect(protectedByBrowser()).toBe(false)
    holder.state.draft = '尚未发送的正文'
    await flushPromises()
    expect(protectedByBrowser()).toBe(true)
    holder.state.draft = ''
    holder.state.supplementDraft = '尚未发送的补充'
    await flushPromises()
    expect(protectedByBrowser()).toBe(true)
    holder.state.supplementDraft = ''
    await flushPromises()
    expect(protectedByBrowser()).toBe(false)
    holder.state.draft = '卸载前的草稿'
    await flushPromises()
    wrapper.unmount()
    expect(protectedByBrowser()).toBe(false)
  })
})
