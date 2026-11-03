import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { ElInput, ElSelect, ElUpload, type UploadRawFile } from 'element-plus'
import ChatPage from './ChatPage.vue'
vi.mock('../stores/auth', () => ({
  useAuthStore: () => ({
    identityVersion: 1,
    user: {
      tenant_id: '1',
      sub: 'member-1',
      permissions: [
        'foundation.sessions.read',
        'foundation.sessions.create',
        'foundation.sessions.cancel',
        'foundation.sessions.run',
        'foundation.agents.read',
        'foundation.teams.read'
      ]
    }
  })
}))
import { HttpClientError } from '@skillforge/shared/utils/http'
import { clearFeedback } from '@skillforge/shared/feedback'

const mocks = vi.hoisted(() => ({
  query: {} as Record<string, string>,
  agentList: vi.fn(),
  teamList: vi.fn(),
  createSession: vi.fn(),
  sessionCreationResult: vi.fn(),
  submit: vi.fn(),
  history: vi.fn(),
  observe: vi.fn(),
  uploadAttachment: vi.fn(),
  uploadMedia: vi.fn(),
  attachments: vi.fn(),
  mediaUploadReceipt: vi.fn(),
  mediaContent: vi.fn()
}))
vi.mock('../api', () => ({
  api: {
    chat: {
      endUsers: async () => [{ id: '42', display_name: '目标回归用户' }],
      sessions: async () => [{ session_id: 'historical-session', summary: '未选择目标的历史会话' }],
      createSessionWithReceipt: mocks.createSession,
      sessionCreationResult: mocks.sessionCreationResult,
      submit: mocks.submit,
      sessionDetail: async (id: string) => ({
        session: { session_id: id },
        end_user_id: null,
        target_agent_id: null,
        closed: false
      }),
      history: mocks.history,
      observeSession: mocks.observe,
      uploadAttachment: mocks.uploadAttachment,
      uploadMedia: mocks.uploadMedia,
      attachments: mocks.attachments,
      mediaUploadReceipt: mocks.mediaUploadReceipt,
      mediaContent: mocks.mediaContent
    },
    agents: {
      list: mocks.agentList
    },
    teams: { list: mocks.teamList }
  }
}))
vi.mock('vue-router', () => ({
  useRoute: () => ({ query: mocks.query }),
  onBeforeRouteLeave: vi.fn(),
  onBeforeRouteUpdate: vi.fn()
}))
vi.mock('../components/TeamDebugPanel.vue', () => ({ default: { template: '<div />' } }))

let wrapper: VueWrapper | undefined
beforeEach(() => {
  vi.clearAllMocks()
  mocks.query = {}
  mocks.agentList.mockResolvedValue({
    items: [{ id: '101', name: '已发布智能体', status: 'published' }]
  })
  mocks.teamList.mockResolvedValue([{ id: '202', name: '已发布团队', status: 'published' }])
  sessionStorage.clear()
  mocks.createSession.mockImplementation(async (request_id: string) => ({
    request_id,
    session_id: 'created-session'
  }))
  mocks.sessionCreationResult.mockResolvedValue(null)
  mocks.submit.mockResolvedValue({ session_id: 'created-session', status: 'accepted' })
  mocks.history.mockImplementation(async (session_id: string) => ({
    session_id,
    messages: [],
    pending_interactions: [],
    processes: [],
    has_more: false
  }))
  mocks.observe.mockResolvedValue(undefined)
  mocks.uploadAttachment.mockImplementation(async (_session: string, id: string) => ({
    id,
    name: '原文件.txt'
  }))
  mocks.uploadMedia.mockResolvedValue({
    media_id: 'image-original',
    mime_type: 'image/png',
    byte_length: 1,
    width: 1,
    height: 1
  })
  mocks.mediaContent.mockResolvedValue(new Blob(['image'], { type: 'image/png' }))
  let imageUrl = 0
  vi.stubGlobal(
    'URL',
    class extends URL {
      static createObjectURL = vi.fn(() => 'blob:history-' + ++imageUrl)
      static revokeObjectURL = vi.fn()
    }
  )
})
afterEach(() => {
  wrapper?.unmount()
  clearFeedback()
  vi.unstubAllGlobals()
  wrapper = undefined
  document.body.innerHTML = ''
})
async function open() {
  wrapper = mount(ChatPage, { attachTo: document.body })
  await flushPromises()
}
async function choose(placeholder: string, label: string) {
  const field = wrapper!
    .findAllComponents(ElSelect)
    .find((select) => select.props('placeholder') === placeholder)!
  await field.get('input[role="combobox"]').trigger('click')
  await flushPromises()
  const option = Array.from(
    document.body.querySelectorAll<HTMLElement>('.el-select-dropdown__item')
  ).find((item) => item.textContent?.includes(label))!
  expect(option).toBeDefined()
  option.click()
  await flushPromises()
}
async function send(text: string) {
  const input = wrapper!
    .findAllComponents(ElInput)
    .find((field) => field.props('placeholder') === '输入消息')!
  await input.get('textarea').setValue(text)
  await wrapper!
    .findAll('button')
    .find((button) => button.text() === '发送')!
    .trigger('click')
  await flushPromises()
}

describe('后台 Chat 页面发送显式目标', () => {
  it.each([
    ['已发布智能体', { agent_id: '101' }],
    ['已发布团队', { team_id: '202' }]
  ] as const)('%s的原生选择同时用于创建及同Session两次发送', async (label, target) => {
    await open()
    await choose('终端用户', '目标回归用户')
    await choose('选择智能体或团队', label)
    await send('第一条 Unicode 消息🙂')
    await send('同一 Session 的第二条消息')

    expect(mocks.createSession).toHaveBeenCalledExactlyOnceWith(expect.any(String), '42', target)
    expect(mocks.submit).toHaveBeenCalledTimes(2)
    for (const [index, text] of ['第一条 Unicode 消息🙂', '同一 Session 的第二条消息'].entries()) {
      expect(mocks.submit.mock.calls[index]![0]).toEqual({
        session_id: 'created-session',
        ...target,
        content: [{ type: 'text', text }],
        client_message_id: expect.any(String),
        context: { end_user_id: '42', attachment_ids: [] }
      })
    }
    expect(mocks.submit.mock.calls[0]![0].client_message_id).not.toBe(
      mocks.submit.mock.calls[1]![0].client_message_id
    )
  })

  it('存在Session也不猜测缺失目标，不向发送接口提交并保留输入', async () => {
    await open()
    await choose('历史会话', '未选择目标的历史会话')
    await send('未选择目标的草稿')
    expect(mocks.createSession).not.toHaveBeenCalled()
    expect(mocks.submit).not.toHaveBeenCalled()
    expect(
      Array.from(document.body.querySelectorAll('.el-message__content')).at(-1)?.textContent
    ).toContain('会话缺少已确认的用户或目标绑定，仅可查看历史')
    const input = wrapper!
      .findAllComponents(ElInput)
      .find((field) => field.props('placeholder') === '输入消息')!
    expect(input.props('modelValue')).toBe('未选择目标的草稿')
  })
})

describe('唯一可用对话对象', () => {
  it('两个目录读取成功且仅有一个对象时预选，不选择终端用户或创建会话', async () => {
    mocks.teamList.mockResolvedValueOnce([])
    await open()
    const target = wrapper!
      .findAllComponents(ElSelect)
      .find((item) => item.props('placeholder') === '选择智能体或团队')!
    const user = wrapper!
      .findAllComponents(ElSelect)
      .find((item) => item.props('placeholder') === '终端用户')!
    expect(target.props('modelValue')).toBe('agent:101')
    expect(user.props('modelValue')).toBe('')
    expect(wrapper!.text()).toContain('已选择唯一可用的对话对象')
    expect(mocks.createSession).not.toHaveBeenCalled()
  })
  it('唯一对象为团队时同样预选', async () => {
    mocks.agentList.mockResolvedValueOnce({ items: [] })
    await open()
    expect(
      wrapper!
        .findAllComponents(ElSelect)
        .find((item) => item.props('placeholder') === '选择智能体或团队')!
        .props('modelValue')
    ).toBe('team:202')
    expect(mocks.createSession).not.toHaveBeenCalled()
  })
  it('任一目录读取失败或存在多个对象时不预选', async () => {
    mocks.teamList.mockRejectedValueOnce(new Error('团队读取失败'))
    await open()
    expect(
      wrapper!
        .findAllComponents(ElSelect)
        .find((item) => item.props('placeholder') === '选择智能体或团队')!
        .props('modelValue')
    ).toBe('')
    wrapper!.unmount()
    mocks.teamList.mockResolvedValueOnce([{ id: '202', name: '已发布团队', status: 'published' }])
    await open()
    expect(
      wrapper!
        .findAllComponents(ElSelect)
        .find((item) => item.props('placeholder') === '选择智能体或团队')!
        .props('modelValue')
    ).toBe('')
  })
  it('用户已手动选择时，迟到的另一个目录不会覆盖', async () => {
    let finish!: (items: unknown[]) => void
    mocks.teamList.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve
        })
    )
    await open()
    await choose('选择智能体或团队', '已发布智能体')
    finish([])
    await flushPromises()
    expect(
      wrapper!
        .findAllComponents(ElSelect)
        .find((item) => item.props('placeholder') === '选择智能体或团队')!
        .props('modelValue')
    ).toBe('agent:101')
    expect(wrapper!.text()).not.toContain('已选择唯一可用的对话对象')
  })
})

describe('从智能体配置进入当前发布版本试用', () => {
  it('按字符串 ID 预选准确对象，仅在手动发送后创建会话', async () => {
    mocks.query = { agent_id: '101' }
    await open()
    const target = wrapper!
      .findAllComponents(ElSelect)
      .find((item) => item.props('placeholder') === '选择智能体或团队')!
    expect(target.props('modelValue')).toBe('agent:101')
    expect(wrapper!.text()).toContain('草稿修改不会用于本次试用')
    expect(mocks.createSession).not.toHaveBeenCalled()
    expect(mocks.submit).not.toHaveBeenCalled()
    await choose('终端用户', '目标回归用户')
    await send('试用发布版本')
    expect(mocks.createSession).toHaveBeenCalledExactlyOnceWith(expect.any(String), '42', {
      agent_id: '101'
    })
    expect(mocks.submit.mock.calls[0]![0].agent_id).toBe('101')
  })

  it('不存在或已撤权的目标不回退到其他对象，也不自动发送', async () => {
    mocks.query = { agent_id: '9223372036854775807' }
    await open()
    const target = wrapper!
      .findAllComponents(ElSelect)
      .find((item) => item.props('placeholder') === '选择智能体或团队')!
    expect(target.props('modelValue')).toBe('')
    expect(wrapper!.text()).toContain('访问授权已撤销')
    await choose('终端用户', '目标回归用户')
    await send('不可发送')
    expect(mocks.createSession).not.toHaveBeenCalled()
    expect(mocks.submit).not.toHaveBeenCalled()
  })
})

describe('后台Chat原消息受理恢复', () => {
  async function start() {
    await open()
    await choose('终端用户', '目标回归用户')
    await choose('选择智能体或团队', '已发布智能体')
  }
  async function action(label: string) {
    await wrapper!
      .findAll('button')
      .find((button) => button.text() === label)!
      .trigger('click')
    await flushPromises()
  }
  function composer() {
    return wrapper!
      .findAllComponents(ElInput)
      .find((field) => field.props('placeholder') === '输入消息')!
  }
  function history(messages: Record<string, unknown>[], session_id = 'created-session') {
    return {
      session_id,
      messages,
      pending_interactions: [],
      processes: [],
      activities: [],
      has_more: false
    }
  }

  it.each(['附件', '图片'])('纯%s可通过真实发送按钮提交，不构造空文本块', async (kind) => {
    await start()
    const uploads = wrapper!.findAllComponents(ElUpload)
    await (uploads[kind === '附件' ? 0 : 1]!.props('onChange') as (file: unknown) => Promise<void>)(
      { raw: new File(['data'], kind === '附件' ? '文件.txt' : '图片.png') }
    )
    await flushPromises()
    expect(
      wrapper!
        .findAll('button')
        .find((button) => button.text() === '发送')!
        .attributes('disabled')
    ).toBeUndefined()
    await action('发送')
    expect(mocks.submit).toHaveBeenCalledOnce()
    expect(mocks.mediaContent).not.toHaveBeenCalled()
    expect(mocks.submit.mock.calls[0]![0]).toMatchObject({
      content: kind === '附件' ? [] : [{ type: 'image', media_id: 'image-original' }],
      context: {
        end_user_id: '42',
        attachment_ids: kind === '附件' ? [mocks.uploadAttachment.mock.calls[0]![1]] : []
      }
    })
  })

  it.each(['附件', '图片'])(
    '纯%s首次明确拒绝保留资源，修正重发使用新UUID且仍无空文本',
    async (kind) => {
      mocks.submit.mockRejectedValueOnce(
        new HttpClientError({ status: 422, code: 'VALIDATION_ERROR', userMessage: '明确拒绝' })
      )
      await start()
      const uploads = wrapper!.findAllComponents(ElUpload)
      await (
        uploads[kind === '附件' ? 0 : 1]!.props('onChange') as (file: unknown) => Promise<void>
      )({ raw: new File(['data'], '资源') })
      await action('发送')
      const first = mocks.submit.mock.calls[0]![0]
      expect(wrapper!.text()).not.toContain('受理结果尚未确认')
      expect(wrapper!.text()).toContain(kind === '附件' ? '原文件.txt' : '图片 image-original')
      await action('发送')
      const second = mocks.submit.mock.calls[1]![0]
      expect(second.client_message_id).not.toBe(first.client_message_id)
      expect(second.content).toEqual(first.content)
      expect(second.content.some((part: { type: string }) => part.type === 'text')).toBe(false)
      expect(second.context.attachment_ids).toEqual(first.context.attachment_ids)
    }
  )

  it.each(['附件', '图片'])(
    '纯%s结果UNKNOWN仍只用冻结body重试，接受后才清选中资源',
    async (kind) => {
      mocks.submit.mockRejectedValueOnce(new TypeError('网络结果未知'))
      await start()
      const uploads = wrapper!.findAllComponents(ElUpload)
      await (
        uploads[kind === '附件' ? 0 : 1]!.props('onChange') as (file: unknown) => Promise<void>
      )({ raw: new File(['data'], '资源') })
      await action('发送')
      const first = mocks.submit.mock.calls[0]![0]
      expect(wrapper!.text()).toContain('受理结果尚未确认')
      expect(wrapper!.text()).toContain(kind === '附件' ? '原文件.txt' : '图片 image-original')
      await action('核对原消息受理')
      expect(mocks.submit).toHaveBeenCalledOnce()
      await action('重试原消息')
      expect(mocks.submit.mock.calls[1]![0]).toBe(first)
      expect(wrapper!.text()).not.toContain(kind === '附件' ? '原文件.txt' : '图片 image-original')
      expect(composer().props('modelValue')).toBe('')
    }
  )

  it('完全空稿和仅空白且无资源仍不创建会话或提交', async () => {
    await start()
    await send('  \n  ')
    await composer().get('textarea').trigger('keydown', { key: 'Enter', ctrlKey: true })
    await flushPromises()
    expect(
      wrapper!
        .findAll('button')
        .find((button) => button.text() === '发送')!
        .attributes('disabled')
    ).toBeDefined()
    expect(mocks.createSession).not.toHaveBeenCalled()
    expect(mocks.submit).not.toHaveBeenCalled()
  })

  it('图片与附件随原正文冻结，显式重试发送相同对象与UUID，成功只清原快照', async () => {
    mocks.submit.mockRejectedValueOnce(new TypeError('连接中断'))
    await start()
    const uploads = wrapper!.findAllComponents(ElUpload)
    await (uploads[0]!.props('onChange') as (file: unknown) => Promise<void>)({
      raw: new File(['原文件'], '原文件.txt')
    })
    await (uploads[1]!.props('onChange') as (file: unknown) => Promise<void>)({
      raw: new File(['image'], '原图.png', { type: 'image/png' })
    })
    await send('原正文')
    const original = mocks.submit.mock.calls[0]![0]
    expect(original).toMatchObject({
      session_id: 'created-session',
      agent_id: '101',
      content: [
        { type: 'text', text: '原正文' },
        { type: 'image', media_id: 'image-original' }
      ],
      context: { end_user_id: '42', attachment_ids: [mocks.uploadAttachment.mock.calls[0]![1]] }
    })
    expect(composer().get('textarea').attributes('disabled')).toBeDefined()
    expect(wrapper!.findAll('.el-tag__close')).toHaveLength(0)
    expect(
      wrapper!
        .findAll('button')
        .find((button) => button.text() === '新会话')!
        .attributes('disabled')
    ).toBeDefined()
    expect(
      wrapper!
        .findAllComponents(ElSelect)
        .find((field) => field.props('placeholder') === '历史会话')!
        .props('disabled')
    ).toBe(true)
    await action('重试原消息')
    expect(mocks.submit.mock.calls[1]![0]).toBe(original)
    expect(composer().props('modelValue')).toBe('')
    expect(wrapper!.text()).not.toContain('受理结果尚未确认')
    expect(wrapper!.text()).not.toContain('原文件.txt')
    expect(wrapper!.text()).not.toContain('图片 image-original')
    expect(mocks.createSession).toHaveBeenCalledOnce()
  })

  it('核对仅GET，其他消息同文、助手同ID或其他Session都不算证明，精确用户metadata才解除', async () => {
    mocks.submit.mockRejectedValueOnce(new TypeError('连接中断'))
    await start()
    await send('原正文')
    const original = mocks.submit.mock.calls[0]![0]
    for (const value of [
      history([{ role: 'user', message_id: '其他消息', content: '原正文', metadata: {} }]),
      history([
        {
          role: 'assistant',
          message_id: original.client_message_id,
          content: '同ID助手',
          metadata: {}
        }
      ]),
      history(
        [{ role: 'user', message_id: original.client_message_id, content: '原正文', metadata: {} }],
        '其他会话'
      )
    ]) {
      mocks.history.mockResolvedValueOnce(value)
      await action('核对原消息受理')
      expect(wrapper!.text()).toContain('受理结果尚未确认')
      expect(composer().props('modelValue')).toBe('原正文')
    }
    mocks.history.mockResolvedValueOnce(
      history([
        {
          role: 'user',
          message_id: '归档消息',
          content: '原正文',
          metadata: { client_message_id: original.client_message_id }
        }
      ])
    )
    await action('核对原消息受理')
    expect(wrapper!.text()).not.toContain('受理结果尚未确认')
    expect(composer().props('modelValue')).toBe('')
    expect(mocks.submit).toHaveBeenCalledOnce()
    expect(mocks.history).toHaveBeenCalledTimes(4)
    for (const call of mocks.history.mock.calls) expect(call[0]).toBe(original.session_id)
  })

  it('刷新事件历史确认先于迟到HTTP失败不重新锁定', async () => {
    let reject!: (reason: Error) => void
    mocks.submit.mockImplementationOnce(
      () =>
        new Promise((_resolve, fail) => {
          reject = fail
        })
    )
    await start()
    await send('原正文')
    const original = mocks.submit.mock.calls[0]![0]
    const observer = mocks.observe.mock.calls[0]![1]
    observer.onHistory(
      original.session_id,
      history([
        { role: 'user', message_id: original.client_message_id, content: '原正文', metadata: {} }
      ])
    )
    await flushPromises()
    reject(new TypeError('迟到HTTP失败'))
    await flushPromises()
    expect(wrapper!.text()).not.toContain('受理结果尚未确认')
    expect(composer().props('modelValue')).toBe('')
    await send('下一条正文')
    expect(mocks.submit).toHaveBeenCalledTimes(2)
    expect(mocks.submit.mock.calls[1]![0].client_message_id).not.toBe(original.client_message_id)
  })

  it.each([
    [408, 'GATEWAY_TIMEOUT'],
    [500, 'VALIDATION_ERROR'],
    [503, 'SERVICE_UNAVAILABLE'],
    [409, 'CONFLICT']
  ])('HTTP%s/%s保持原请求，不把未明错误当派发前拒绝', async (status, code) => {
    mocks.submit.mockRejectedValueOnce(
      new HttpClientError({ status: Number(status), code: String(code), userMessage: '结果待核对' })
    )
    await start()
    await send('原正文')
    expect(wrapper!.text()).toContain('受理结果尚未确认')
    expect(composer().get('textarea').attributes('disabled')).toBeDefined()
    await action('核对原消息受理')
    expect(mocks.submit).toHaveBeenCalledOnce()
    expect(wrapper!.text()).toContain('受理结果尚未确认')
    expect(document.body.textContent).toContain('尚未查到原消息的受理记录')
  })

  it.each([
    [400, 'VALIDATION_ERROR'],
    [401, 'UNAUTHORIZED'],
    [403, 'FORBIDDEN'],
    [404, 'NOT_FOUND'],
    [422, 'VALIDATION_ERROR']
  ])('正式派发前HTTP%s/%s拒绝保稿，修正后使用新UUID', async (status, code) => {
    mocks.submit.mockRejectedValueOnce(
      new HttpClientError({ status: Number(status), code: String(code), userMessage: '明确拒绝' })
    )
    await start()
    await send('原正文')
    expect(wrapper!.text()).not.toContain('受理结果尚未确认')
    expect(composer().props('modelValue')).toBe('原正文')
    const originalId = mocks.submit.mock.calls[0]![0].client_message_id
    await send('修正后的正文')
    expect(mocks.submit).toHaveBeenCalledTimes(2)
    expect(mocks.submit.mock.calls[1]![0].client_message_id).not.toBe(originalId)
  })

  it.each([
    [403, 'FORBIDDEN'],
    [404, 'NOT_FOUND'],
    [409, 'CONFLICT']
  ])('已有UNKNOWN后重试HTTP%s/%s及查询404都不能证明首写未受理', async (status, code) => {
    mocks.submit.mockRejectedValueOnce(new TypeError('首次结果未知')).mockRejectedValueOnce(
      new HttpClientError({
        status: Number(status),
        code: String(code),
        userMessage: '当前访问被拒绝'
      })
    )
    await start()
    await send('原请求正文')
    const original = mocks.submit.mock.calls[0]![0]
    await action('重试原消息')
    expect(wrapper!.text()).toContain('受理结果尚未确认')
    expect(composer().props('modelValue')).toBe('原请求正文')
    expect(mocks.submit.mock.calls[1]![0]).toBe(original)
    mocks.history.mockRejectedValueOnce(
      new HttpClientError({ status: 404, code: 'NOT_FOUND', userMessage: '暂未查到' })
    )
    await action('核对原消息受理')
    expect(wrapper!.text()).toContain('受理结果尚未确认')
    mocks.history.mockResolvedValueOnce(
      history([
        {
          role: 'user',
          message_id: original.client_message_id,
          content: '原请求正文',
          metadata: {}
        }
      ])
    )
    await action('核对原消息受理')
    expect(wrapper!.text()).not.toContain('受理结果尚未确认')
    expect(mocks.submit).toHaveBeenCalledTimes(2)
  })

  it('已有UNKNOWN后重试额度拒绝仍保留原请求，原历史未找到不解除', async () => {
    mocks.submit
      .mockRejectedValueOnce(new TypeError('首次结果未知'))
      .mockResolvedValueOnce({ status: 'quota_exhausted', session_id: 'created-session' })
    await start()
    await send('原正文')
    const original = mocks.submit.mock.calls[0]![0]
    await action('重试原消息')
    expect(wrapper!.text()).toContain('受理结果尚未确认')
    expect(mocks.submit.mock.calls[1]![0]).toBe(original)
    await action('核对原消息受理')
    expect(wrapper!.text()).toContain('受理结果尚未确认')
  })

  it('精确历史确认只清原正文，已改变的草稿不被确认后的迟到HTTP成功清除', async () => {
    let resolve!: (value: unknown) => void
    mocks.submit.mockImplementationOnce(
      () =>
        new Promise((done) => {
          resolve = done
        })
    )
    await start()
    await send('原正文')
    const original = mocks.submit.mock.calls[0]![0]
    // 模拟回执期间的受控草稿更新，验证回执只能清理自己持有的原快照。
    composer().vm.$emit('update:modelValue', '后续草稿')
    mocks.observe.mock.calls[0]![1].onHistory(
      original.session_id,
      history([
        { role: 'user', message_id: original.client_message_id, content: '原正文', metadata: {} }
      ])
    )
    await flushPromises()
    expect(composer().props('modelValue')).toBe('后续草稿')
    resolve({ status: 'accepted', session_id: original.session_id })
    await flushPromises()
    expect(composer().props('modelValue')).toBe('后续草稿')
    expect(wrapper!.text()).not.toContain('受理结果尚未确认')
  })

  it('额度明确拒绝保留草稿，不伪装成消息受理成功', async () => {
    mocks.submit.mockResolvedValueOnce({
      status: 'quota_exhausted',
      session_id: 'created-session',
      message: '本月额度用尽'
    })
    await start()
    await send('待发送正文')
    expect(composer().props('modelValue')).toBe('待发送正文')
    expect(document.body.textContent).toContain('本月额度用尽')
    expect(wrapper!.text()).not.toContain('受理结果尚未确认')
  })

  it('发送超时后不把修改稿提交为新请求，保持原会话且只能按原请求恢复', async () => {
    mocks.submit.mockRejectedValueOnce(
      new HttpClientError({ status: 408, code: 'GATEWAY_TIMEOUT', userMessage: '发送超时' })
    )
    await open()
    await choose('终端用户', '目标回归用户')
    await choose('选择智能体或团队', '已发布智能体')
    await send('原消息')
    await send('超时后修改稿')
    expect(mocks.submit).toHaveBeenCalledOnce()
    expect(wrapper!.text()).toContain('受理结果尚未确认')
  })
})

describe('后台纯资源用户消息历史可见', () => {
  it('按正式content_parts与附件摘要展示图片和文件名，ids-only用短标签且不复制文本', async () => {
    mocks.history.mockResolvedValueOnce({
      session_id: 'historical-session',
      pending_interactions: [],
      messages: [
        {
          message_id: 'image-message',
          role: 'user',
          content: '',
          metadata: { content_parts: [{ type: 'image', media_id: 'private-image-uuid' }] }
        },
        {
          message_id: 'file-message',
          role: 'user',
          content: '',
          metadata: {
            attachment_ids: ['private-file-uuid'],
            attachments: [
              {
                id: 'private-file-uuid',
                name: '已确认报告.pdf',
                inputPath: 'private/path',
                size: 42,
                sha256: 'frozen-sha',
                expiresAt: '2026-11-01T00:00:00Z'
              }
            ]
          }
        },
        {
          message_id: 'ids-message',
          role: 'user',
          content: '',
          metadata: { attachment_ids: ['ids-only-uuid'] }
        },
        {
          message_id: 'ordered-message',
          role: 'user',
          content: '前文后文',
          metadata: {
            content_parts: [
              { type: 'text', text: '前文' },
              { type: 'image', media_id: 'private-second-image' },
              { type: 'text', text: '后文' }
            ]
          }
        }
      ]
    })
    await open()
    await choose('历史会话', '未选择目标的历史会话')
    const messages = wrapper!.findAll('.debug-message')
    expect(messages[0]!.get('img').attributes('alt')).toBe('会话图片')
    expect(messages[1]!.text()).toContain('已确认报告.pdf')
    expect(messages[2]!.text()).toContain('附件 1')
    expect(messages[3]!.text().match(/前文/g)).toHaveLength(1)
    expect(messages[3]!.text().match(/后文/g)).toHaveLength(1)
    expect(messages[3]!.get('img').attributes('alt')).toBe('会话图片')
    expect(mocks.mediaContent.mock.calls.map((call) => call.slice(0, 2))).toEqual([
      ['historical-session', 'private-image-uuid'],
      ['historical-session', 'private-second-image']
    ])
    expect(wrapper!.text()).not.toContain('private-image-uuid')
    expect(wrapper!.text()).not.toContain('private-file-uuid')
    expect(wrapper!.text()).not.toContain('ids-only-uuid')
  })
})

it('会话创建响应中断后按原请求核对，未查到时不重复创建或发送', async () => {
  mocks.createSession.mockRejectedValueOnce(new Error('连接中断'))
  await open()
  await choose('终端用户', '目标回归用户')
  await choose('选择智能体或团队', '已发布智能体')
  await send('保留草稿')
  const requestId = mocks.createSession.mock.calls[0]![0]
  expect(mocks.submit).not.toHaveBeenCalled()
  expect(wrapper!.text()).toContain('会话创建结果尚未确认')
  const check = () => wrapper!.findAll('button').find((button) => button.text() === '核对创建结果')!
  await check().trigger('click')
  await flushPromises()
  expect(mocks.sessionCreationResult).toHaveBeenCalledWith(requestId)
  expect(mocks.createSession).toHaveBeenCalledOnce()
  expect(wrapper!.text()).toContain('会话创建结果尚未确认')
  mocks.sessionCreationResult.mockResolvedValueOnce({
    request_id: requestId,
    session_id: 'created-session'
  })
  await check().trigger('click')
  await flushPromises()
  expect(wrapper!.text()).not.toContain('会话创建结果尚未确认')
  expect(mocks.submit).not.toHaveBeenCalled()
  await send('保留草稿')
  expect(mocks.createSession).toHaveBeenCalledOnce()
  expect(mocks.submit).toHaveBeenCalledOnce()
})

it.each(['file', 'image'] as const)(
  '会话创建结果未知时保留未发送的%s并在确认后继续上传',
  async (kind) => {
    mocks.createSession.mockRejectedValueOnce(new Error('连接中断'))
    await open()
    await choose('终端用户', '目标回归用户')
    await choose('选择智能体或团队', '已发布智能体')
    const file = Object.assign(new File(['内容'], kind === 'file' ? '资料.txt' : '图片.png'), {
      uid: 123
    }) as UploadRawFile
    wrapper!.findAllComponents(ElUpload)[kind === 'file' ? 0 : 1]!.vm.handleStart(file)
    await flushPromises()
    const requestId = mocks.createSession.mock.calls[0]![0]
    expect(wrapper!.text()).toContain('文件尚未上传')
    expect(mocks.uploadAttachment).not.toHaveBeenCalled()
    expect(mocks.uploadMedia).not.toHaveBeenCalled()
    mocks.sessionCreationResult.mockResolvedValueOnce({
      request_id: requestId,
      session_id: 'created-session'
    })
    await wrapper!
      .findAll('button')
      .find((item) => item.text() === '核对创建结果')!
      .trigger('click')
    await flushPromises()
    expect(wrapper!.text()).toContain('继续上传原文件')
    expect(mocks.createSession).toHaveBeenCalledTimes(1)
    expect(mocks.uploadAttachment).not.toHaveBeenCalled()
    expect(mocks.uploadMedia).not.toHaveBeenCalled()
    await wrapper!
      .findAll('button')
      .find((item) => item.text() === '继续上传原文件')!
      .trigger('click')
    await flushPromises()
    const upload = kind === 'file' ? mocks.uploadAttachment : mocks.uploadMedia
    expect(upload).toHaveBeenCalledExactlyOnceWith('created-session', expect.any(String), file)
    expect(mocks.submit).not.toHaveBeenCalled()
    expect(wrapper!.text()).not.toContain('文件尚未上传')
  }
)

it.each(['file', 'image'] as const)('先选择%s再补全会话对象时无需重新选择文件', async (kind) => {
  await open()
  const file = Object.assign(new File(['内容'], kind === 'file' ? '资料.txt' : '图片.png'), {
    uid: 456
  }) as UploadRawFile
  wrapper!.findAllComponents(ElUpload)[kind === 'file' ? 0 : 1]!.vm.handleStart(file)
  await flushPromises()
  expect(wrapper!.text()).toContain('请选择终端用户和对话对象后继续')
  const resume = () => wrapper!.findAll('button').find((item) => item.text() === '继续上传原文件')!
  expect(resume().attributes('disabled')).toBeDefined()
  expect(mocks.createSession).not.toHaveBeenCalled()
  await choose('终端用户', '目标回归用户')
  await choose('选择智能体或团队', '已发布智能体')
  expect(resume().attributes('disabled')).toBeUndefined()
  await resume().trigger('click')
  await flushPromises()
  expect(mocks.createSession).toHaveBeenCalledTimes(1)
  const upload = kind === 'file' ? mocks.uploadAttachment : mocks.uploadMedia
  expect(upload).toHaveBeenCalledExactlyOnceWith('created-session', expect.any(String), file)
  expect(mocks.submit).not.toHaveBeenCalled()
  expect(wrapper!.text()).not.toContain('文件尚未上传')
})

it.each(['file', 'image'] as const)('%s上传响应中断后仅核对原标识，未查到不重传', async (kind) => {
  const upload = kind === 'file' ? mocks.uploadAttachment : mocks.uploadMedia
  upload.mockRejectedValueOnce(new Error('响应中断'))
  await open()
  await choose('终端用户', '目标回归用户')
  await choose('选择智能体或团队', '已发布智能体')
  const file = Object.assign(new File(['内容'], kind === 'file' ? '资料.txt' : '图片.png'), {
    uid: 789
  }) as UploadRawFile
  wrapper!.findAllComponents(ElUpload)[kind === 'file' ? 0 : 1]!.vm.handleStart(file)
  await flushPromises()
  const uploadId = upload.mock.calls[0]![1]
  expect(wrapper!.text()).toContain('上传结果尚未确认')
  const key = 'skillforge.chat-upload:1:member-1'
  expect(JSON.parse(sessionStorage.getItem(key)!)[0]).toMatchObject({
    uploadId,
    session: 'created-session',
    kind
  })
  if (kind === 'file')
    mocks.attachments
      .mockResolvedValueOnce([])
      .mockResolvedValueOnce([{ id: uploadId, name: '资料.txt' }])
  else
    mocks.mediaUploadReceipt
      .mockResolvedValueOnce({ status: 'UNKNOWN', media: null })
      .mockResolvedValueOnce({
        status: 'FOUND',
        media: { media_id: 'media-1', mime_type: 'image/png', byte_length: 1, width: 1, height: 1 }
      })
  const inspect = () => wrapper!.findAll('button').find((item) => item.text() === '核对原上传')!
  await inspect().trigger('click')
  await flushPromises()
  expect(wrapper!.text()).toContain('上传结果尚未确认')
  expect(upload).toHaveBeenCalledTimes(1)
  await inspect().trigger('click')
  await flushPromises()
  expect(wrapper!.text()).not.toContain('上传结果尚未确认')
  expect(wrapper!.text()).toContain(kind === 'file' ? '资料.txt' : '图片 media-1')
  expect(upload).toHaveBeenCalledTimes(1)
  expect(mocks.submit).not.toHaveBeenCalled()
  expect(sessionStorage.getItem(key)).toBeNull()
})

it.each([
  ['SESSION_ATTACHMENT_UPLOAD_REJECTED', false],
  ['UNPROCESSABLE_ENTITY', true]
])('附件上传返回 %s 时按明确回执决定是否保留待核对', async (code, unknown) => {
  mocks.uploadAttachment.mockRejectedValueOnce(
    new HttpClientError({ status: 422, code: String(code), userMessage: '文件不合法' })
  )
  await open()
  await choose('终端用户', '目标回归用户')
  await choose('选择智能体或团队', '已发布智能体')
  const upload = (uid: number) =>
    wrapper!
      .findAllComponents(ElUpload)[0]!
      .vm.handleStart(Object.assign(new File(['内容'], '资料.txt'), { uid }) as UploadRawFile)
  upload(1)
  await flushPromises()
  expect(wrapper!.text().includes('上传结果尚未确认')).toBe(unknown)
  expect(sessionStorage.getItem('skillforge.chat-upload:1:member-1') !== null).toBe(unknown)
  upload(2)
  await flushPromises()
  expect(mocks.uploadAttachment).toHaveBeenCalledTimes(unknown ? 1 : 2)
})

it.each([
  ['SESSION_MEDIA_UPLOAD_REJECTED', false],
  ['UNPROCESSABLE_ENTITY', true]
])('图片上传返回 %s 时按明确回执决定是否保留待核对', async (code, unknown) => {
  mocks.uploadMedia.mockRejectedValueOnce(
    new HttpClientError({ status: 422, code: String(code), userMessage: '图片不合法' })
  )
  await open()
  await choose('终端用户', '目标回归用户')
  await choose('选择智能体或团队', '已发布智能体')
  const upload = (uid: number) =>
    wrapper!
      .findAllComponents(ElUpload)[1]!
      .vm.handleStart(Object.assign(new File(['内容'], '图片.png'), { uid }) as UploadRawFile)
  upload(1)
  await flushPromises()
  expect(wrapper!.text().includes('上传结果尚未确认')).toBe(unknown)
  expect(sessionStorage.getItem('skillforge.chat-upload:1:member-1') !== null).toBe(unknown)
  upload(2)
  await flushPromises()
  expect(mocks.uploadMedia).toHaveBeenCalledTimes(unknown ? 1 : 2)
})

it('未确认上传阻止同一文件重复上传，但允许继续上传其他文件', async () => {
  mocks.uploadAttachment.mockRejectedValueOnce(new Error('响应中断'))
  await open()
  await choose('终端用户', '目标回归用户')
  await choose('选择智能体或团队', '已发布智能体')
  const upload = (name: string, uid: number) =>
    wrapper!
      .findAllComponents(ElUpload)[0]!
      .vm.handleStart(Object.assign(new File(['内容'], name), { uid }) as UploadRawFile)
  upload('资料.txt', 1)
  await flushPromises()
  upload('资料.txt', 2)
  await flushPromises()
  expect(mocks.uploadAttachment).toHaveBeenCalledTimes(1)
  expect(wrapper!.text()).toContain('上传结果尚未确认')
  upload('其他.txt', 3)
  await flushPromises()
  expect(mocks.uploadAttachment).toHaveBeenCalledTimes(2)
  expect(wrapper!.text()).toContain('上传结果尚未确认')
  expect(mocks.submit).not.toHaveBeenCalled()
})

it('附件上传返回其他标识时保留原请求供核对', async () => {
  mocks.uploadAttachment.mockResolvedValueOnce({ id: 'other', name: '资料.txt' })
  await open()
  await choose('终端用户', '目标回归用户')
  await choose('选择智能体或团队', '已发布智能体')
  wrapper!
    .findAllComponents(ElUpload)[0]!
    .vm.handleStart(Object.assign(new File(['内容'], '资料.txt'), { uid: 321 }) as UploadRawFile)
  await flushPromises()
  expect(wrapper!.text()).toContain('上传结果尚未确认')
  expect(sessionStorage.getItem('skillforge.chat-upload:1:member-1')).not.toBeNull()
  expect(mocks.uploadAttachment).toHaveBeenCalledTimes(1)
})

it('上传标识无法保存时保留原文件，恢复后可继续且未提前发送', async () => {
  await open()
  await choose('终端用户', '目标回归用户')
  await choose('选择智能体或团队', '已发布智能体')
  const originalSet = Storage.prototype.setItem
  let fail = true
  const storage = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(function (
    this: Storage,
    key,
    value
  ) {
    if (key.startsWith('skillforge.chat-upload:') && fail) {
      fail = false
      throw Error('存储不可用')
    }
    return originalSet.call(this, key, value)
  })
  try {
    const file = Object.assign(new File(['内容'], '资料.txt'), { uid: 987 }) as UploadRawFile
    wrapper!.findAllComponents(ElUpload)[0]!.vm.handleStart(file)
    await flushPromises()
    expect(wrapper!.text()).toContain('文件尚未上传')
    expect(mocks.uploadAttachment).not.toHaveBeenCalled()
    await wrapper!
      .findAll('button')
      .find((item) => item.text() === '继续上传原文件')!
      .trigger('click')
    await flushPromises()
    expect(mocks.uploadAttachment).toHaveBeenCalledExactlyOnceWith(
      'created-session',
      expect.any(String),
      file
    )
    expect(mocks.createSession).toHaveBeenCalledTimes(1)
  } finally {
    storage.mockRestore()
  }
})

it('重新进入后恢复原会话并核对未确认上传，不重传', async () => {
  const uploadId = '7a904f16-0dfe-40d0-afae-496c76e9c208'
  sessionStorage.setItem(
    'skillforge.chat-upload:1:member-1',
    JSON.stringify([{ session: 'original-session', uploadId, name: '资料.txt', kind: 'file' }])
  )
  mocks.attachments.mockResolvedValueOnce([{ id: uploadId, name: '资料.txt' }])
  await open()
  expect(wrapper!.text()).toContain('上传结果尚未确认')
  await wrapper!
    .findAll('button')
    .find((item) => item.text() === '恢复原会话')!
    .trigger('click')
  await flushPromises()
  await wrapper!
    .findAll('button')
    .find((item) => item.text() === '核对原上传')!
    .trigger('click')
  await flushPromises()
  expect(mocks.attachments).toHaveBeenCalledExactlyOnceWith('original-session')
  expect(mocks.uploadAttachment).not.toHaveBeenCalled()
  expect(wrapper!.text()).toContain('资料.txt')
  expect(sessionStorage.getItem('skillforge.chat-upload:1:member-1')).toBeNull()
})

it('重新进入时自动核对原会话，确认后无需再次点击或发送', async () => {
  const requestId = 'f082e124-0dfc-4cf1-bcdc-f5af8bda1234'
  sessionStorage.setItem(
    'skillforge.chat-session-create:1:member-1',
    JSON.stringify({ requestId, endUserId: '42', targetKey: 'agent:101' })
  )
  mocks.sessionCreationResult.mockResolvedValueOnce({
    request_id: requestId,
    session_id: 'created-session'
  })
  await open()
  expect(mocks.sessionCreationResult).toHaveBeenCalledExactlyOnceWith(requestId)
  expect(wrapper!.text()).not.toContain('会话创建结果尚未确认')
  expect(mocks.observe).toHaveBeenCalled()
  expect(mocks.createSession).not.toHaveBeenCalled()
  expect(mocks.submit).not.toHaveBeenCalled()
  expect(sessionStorage.getItem('skillforge.chat-session-create:1:member-1')).toBeNull()
})

it('自动核对尚无结果时保留原请求并可手动核对', async () => {
  const requestId = 'f082e124-0dfc-4cf1-bcdc-f5af8bda1234'
  sessionStorage.setItem(
    'skillforge.chat-session-create:1:member-1',
    JSON.stringify({ requestId, endUserId: '42', targetKey: 'agent:101' })
  )
  await open()
  expect(mocks.sessionCreationResult).toHaveBeenCalledExactlyOnceWith(requestId)
  expect(wrapper!.text()).toContain('会话创建结果尚未确认')
  expect(
    wrapper!
      .findAll('button')
      .find((button) => button.text() === '核对创建结果')!
      .attributes('disabled')
  ).toBeUndefined()
  expect(mocks.createSession).not.toHaveBeenCalled()
  expect(mocks.submit).not.toHaveBeenCalled()
})

it('自动核对失败后保留原请求，用户可再次只读查询', async () => {
  const requestId = 'f082e124-0dfc-4cf1-bcdc-f5af8bda1234'
  sessionStorage.setItem(
    'skillforge.chat-session-create:1:member-1',
    JSON.stringify({ requestId, endUserId: '42', targetKey: 'agent:101' })
  )
  mocks.sessionCreationResult.mockRejectedValueOnce(new Error('暂时无法核对'))
  await open()
  expect(wrapper!.text()).toContain('会话创建结果尚未确认')
  expect(mocks.sessionCreationResult).toHaveBeenCalledExactlyOnceWith(requestId)
  mocks.sessionCreationResult.mockResolvedValueOnce({
    request_id: requestId,
    session_id: 'created-session'
  })
  await wrapper!
    .findAll('button')
    .find((button) => button.text() === '核对创建结果')!
    .trigger('click')
  await flushPromises()
  expect(mocks.sessionCreationResult).toHaveBeenCalledTimes(2)
  expect(wrapper!.text()).not.toContain('会话创建结果尚未确认')
  expect(mocks.createSession).not.toHaveBeenCalled()
  expect(mocks.submit).not.toHaveBeenCalled()
})

describe('未确认消息刷新恢复', () => {
  const key = 'skillforge.chat-input:1:member-1'
  const composer = () =>
    wrapper!.findAllComponents(ElInput).find((field) => field.props('placeholder') === '输入消息')!
  const action = async (name: string) => {
    await wrapper!
      .findAll('button')
      .find((button) => button.text() === name)!
      .trigger('click')
    await flushPromises()
  }
  async function unknownMessage() {
    mocks.submit.mockRejectedValueOnce(new TypeError('受理响应丢失'))
    await open()
    await choose('终端用户', '目标回归用户')
    await choose('选择智能体或团队', '已发布智能体')
    await send('原正文 Unicode🙂')
    expect(wrapper!.text()).toContain('受理结果尚未确认')
    return mocks.submit.mock.calls[0]![0]
  }
  async function rebuild() {
    wrapper!.unmount()
    wrapper = undefined
    await open()
  }
  it('重建保留原正文和UUID，只读失败后仍可显式重试同一请求', async () => {
    const original = await unknownMessage()
    expect(JSON.parse(sessionStorage.getItem(key)!).body).toEqual(original)
    mocks.history.mockRejectedValueOnce(new Error('暂时不能读取历史'))
    await rebuild()
    expect(wrapper!.text()).toContain('受理结果尚未确认')
    expect(composer().props('modelValue')).toBe('原正文 Unicode🙂')
    expect(mocks.submit).toHaveBeenCalledOnce()
    expect(mocks.createSession).toHaveBeenCalledOnce()
    expect(mocks.history).toHaveBeenLastCalledWith(original.session_id)
    expect(JSON.parse(sessionStorage.getItem(key)!).body).toEqual(original)
    await action('重试原消息')
    expect(mocks.submit.mock.calls[1]![0]).toEqual(original)
    expect(wrapper!.text()).not.toContain('受理结果尚未确认')
    expect(sessionStorage.getItem(key)).toBeNull()
  })
  it('重建后的同文历史不解除，精确受理历史只读清理原请求', async () => {
    const original = await unknownMessage()
    mocks.history.mockResolvedValue({
      session_id: original.session_id,
      messages: [
        { role: 'user', message_id: 'another', content: '原正文 Unicode🙂', metadata: {} }
      ],
      pending_interactions: [],
      processes: [],
      has_more: false
    })
    await rebuild()
    expect(wrapper!.text()).toContain('受理结果尚未确认')
    expect(mocks.submit).toHaveBeenCalledOnce()
    expect(sessionStorage.getItem(key)).not.toBeNull()
    mocks.history.mockResolvedValueOnce({
      session_id: original.session_id,
      messages: [
        {
          role: 'user',
          message_id: 'archive',
          content: '原正文 Unicode🙂',
          metadata: { client_message_id: original.client_message_id }
        }
      ],
      pending_interactions: [],
      processes: [],
      has_more: false
    })
    await action('核对原消息受理')
    expect(wrapper!.text()).not.toContain('受理结果尚未确认')
    expect(composer().props('modelValue')).toBe('')
    expect(sessionStorage.getItem(key)).toBeNull()
    expect(mocks.submit).toHaveBeenCalledOnce()
  })
  it('其他机构或操作人的原请求不被恢复或读取', async () => {
    const original = await unknownMessage()
    const frozen = sessionStorage.getItem(key)!
    wrapper!.unmount()
    wrapper = undefined
    sessionStorage.clear()
    sessionStorage.setItem('skillforge.chat-input:other-tenant:member-1', frozen)
    sessionStorage.setItem('skillforge.chat-input:1:other-member', frozen)
    mocks.history.mockClear()
    await open()
    expect(wrapper!.text()).not.toContain('受理结果尚未确认')
    expect(composer().props('modelValue')).toBe('')
    expect(mocks.history).not.toHaveBeenCalledWith(original.session_id)
    expect(mocks.submit).toHaveBeenCalledOnce()
    expect(sessionStorage.getItem('skillforge.chat-input:1:other-member')).toBe(frozen)
  })
  it('原消息无法持久保存时不派发，保留草稿供恢复存储后发送', async () => {
    await open()
    await choose('终端用户', '目标回归用户')
    await choose('选择智能体或团队', '已发布智能体')
    const originalSet = Storage.prototype.setItem
    const storage = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(function (
      this: Storage,
      name,
      value
    ) {
      if (name.startsWith('skillforge.chat-input:')) throw Error('存储不可用')
      return originalSet.call(this, name, value)
    })
    try {
      await send('不能丢失的草稿')
      expect(mocks.submit).not.toHaveBeenCalled()
      expect(composer().props('modelValue')).toBe('不能丢失的草稿')
    } finally {
      storage.mockRestore()
    }
    await action('发送')
    expect(mocks.submit).toHaveBeenCalledOnce()
    expect(mocks.submit.mock.calls[0]![0].content).toEqual([
      { type: 'text', text: '不能丢失的草稿' }
    ])
  })
})
