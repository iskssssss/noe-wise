import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { mount, type VueWrapper } from '@vue/test-utils'
import { useConversation } from './session'
import { emptyHistory } from './projection'
import type { Attachment } from './protocol'

const response = (data: unknown) =>
  new Response(JSON.stringify({ code: 'OK', message: '成功', data, trace_id: 'upload-test' }), {
    headers: { 'content-type': 'application/json' }
  })
let wrapper: VueWrapper
let state: ReturnType<typeof useConversation>
let uploads: FormData[]
let upload: (body: FormData) => Promise<Response>
let mediaUpload: () => Promise<Response>
let attachmentList: Attachment[]
let attachmentReads: number
let attachmentReadFails: boolean
let attachmentRead: (() => Promise<Response>) | undefined
beforeEach(async () => {
  localStorage.clear()
  sessionStorage.clear()
  uploads = []
  attachmentList = []
  attachmentReads = 0
  attachmentReadFails = false
  attachmentRead = undefined
  mediaUpload = async () =>
    response({ media_id: 'media', mime_type: 'image/png', byte_length: 2, width: 1, height: 1 })
  upload = async (body) =>
    response({
      id: String(body.get('upload_id')),
      name: (body.get('file') as File).name,
      input_path: 'inputs/confirmed.txt',
      size: 2,
      sha256: 'controlled-hash',
      expires_at: '2026-10-30T00:00:00Z'
    } satisfies Attachment)
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: URL, init: RequestInit = {}) => {
      const url = new URL(input)
      const path = url.pathname
      if (path.endsWith('/chat/agents'))
        return response({
          channel_id: 'channel',
          items: [{ id: 'agent', name: '助手', is_default: true }]
        })
      if (path.endsWith('/chat/client-profile'))
        return response({ end_user_id: '42', identity_token: 'synthetic-upload-test' })
      if (path.endsWith('/chat/teams') || path.endsWith('/chat/sessions')) return response([])
      if (path.endsWith('/chat/history'))
        return response({ ...emptyHistory(), session_id: 'existing' })
      if (path.endsWith('/chat/sessions/deliverables')) return response([])
      if (path.endsWith('/chat/sessions/attachments')) {
        attachmentReads++
        if (attachmentReadFails) throw new TypeError('list offline')
        if (attachmentRead) return attachmentRead()
        return response(attachmentList)
      }
      if (path.endsWith('/chat/limits'))
        return response({
          attachments: {
            max_file_bytes: 10,
            max_files: 10,
            max_total_bytes: 100,
            extensions: ['txt']
          },
          references: { max_items: 10, max_file_selectors: 20 }
        })
      if (path.endsWith('/chat/sessions/media/limits'))
        return response({ max_file_bytes: 10, mime_types: ['image/png'] })
      if (path.endsWith('/upload')) {
        const body = init.body as FormData
        uploads.push(body)
        if (path.endsWith('/media/upload')) return mediaUpload()
        return upload(body)
      }
      if (path.endsWith('/chat/events'))
        return new Response(
          new ReadableStream({
            start(controller) {
              init.signal?.addEventListener('abort', () => controller.close(), { once: true })
            }
          }),
          { headers: { 'content-type': 'text/event-stream' } }
        )
      throw new Error(`Unexpected request: ${path}`)
    })
  )
  wrapper = mount(
    defineComponent({
      setup() {
        state = useConversation()
        return () => h('div')
      }
    })
  )
  await state.connect('http://localhost:8000', { mode: 'token', token: 'upload-test' }, '访客')
  await state.openSession('existing')
  state.draft.value = '已有正文草稿'
})
afterEach(() => {
  wrapper.unmount()
  vi.unstubAllGlobals()
})

describe('上传明确拒绝', () => {
  it.each([
    ['attachment', 'SESSION_ATTACHMENT_UPLOAD_REJECTED', false],
    ['media', 'SESSION_MEDIA_UPLOAD_REJECTED', false],
    ['attachment', 'UNPROCESSABLE_ENTITY', true],
    ['media', 'UNPROCESSABLE_ENTITY', true]
  ])('%s 的 %s 按原请求回执保留或清除记录', async (kind, code, unknown) => {
    const rejected = async () =>
      new Response(
        JSON.stringify({ code, message: '内容不合法', data: null, trace_id: 'upload-test' }),
        { status: 422, headers: { 'content-type': 'application/json' } }
      )
    if (kind === 'media') mediaUpload = rejected
    else upload = rejected
    const file =
      kind === 'media'
        ? new File(['bad'], 'bad.png', { type: 'image/png' })
        : new File(['bad'], 'bad.txt', { type: 'text/plain' })
    await state.upload([file])
    expect(uploads).toHaveLength(1)
    expect(state.currentUnconfirmedUploads.value).toHaveLength(unknown ? 1 : 0)
    expect(
      JSON.parse(sessionStorage.getItem('skillforge.chat.unconfirmedUploads') || '[]')
    ).toHaveLength(unknown ? 1 : 0)
    if (!unknown) expect(state.error.value).toContain('内容不合法')
    expect(state.draft.value).toBe('已有正文草稿')
  })
})

it('明确拒绝后无法清除浏览器记录时保留原记录', async () => {
  upload = async () =>
    new Response(
      JSON.stringify({
        code: 'SESSION_ATTACHMENT_UPLOAD_REJECTED',
        message: '内容不合法',
        data: null,
        trace_id: 'upload-test'
      }),
      { status: 422, headers: { 'content-type': 'application/json' } }
    )
  const remove = vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => {
    throw new Error('storage unavailable')
  })
  try {
    await state.upload([new File(['bad'], 'bad.txt', { type: 'text/plain' })])
    expect(state.uploadStorageUnavailable.value).toBe(true)
    expect(state.currentUnconfirmedUploads.value).toHaveLength(1)
    expect(JSON.parse(sessionStorage.getItem('skillforge.chat.unconfirmedUploads')!)).toHaveLength(
      1
    )
    expect(uploads).toHaveLength(1)
  } finally {
    remove.mockRestore()
  }
})

it.each(['attachment', 'media'] as const)('%s 上传成功响应不完整时保留原标识', async (kind) => {
  if (kind === 'media')
    mediaUpload = async () => response({ media_id: 'image', mime_type: 'image/png' })
  else
    upload = async () =>
      response({
        id: 'other',
        name: 'bad.txt',
        input_path: 'x',
        size: 2,
        sha256: 'hash',
        expires_at: 'later'
      })
  const file =
    kind === 'media'
      ? new File(['ok'], 'bad.png', { type: 'image/png' })
      : new File(['ok'], 'bad.txt', { type: 'text/plain' })
  expect(await state.upload([file])).toEqual([])
  expect(uploads).toHaveLength(1)
  expect(state.currentUnconfirmedUploads.value[0]?.uploadId).toBe(uploads[0]!.get('upload_id'))
  expect(state.currentConfirmedUploads.value).toEqual([])
  expect(state.attachments.value).toEqual([])
  expect(state.images.value).toEqual([])
  expect(JSON.parse(sessionStorage.getItem('skillforge.chat.unconfirmedUploads')!)).toHaveLength(1)
})

it('切换会话后收到不完整上传回执，原会话仍可核对', async () => {
  let finish!: (value: Response) => void
  upload = () =>
    new Promise((resolve) => {
      finish = resolve
    })
  const pending = state.upload([new File(['ok'], 'bad.txt', { type: 'text/plain' })])
  await vi.waitFor(() => expect(uploads).toHaveLength(1))
  const id = uploads[0]!.get('upload_id')
  await state.openSession('other')
  finish(response(null))
  await pending
  await state.openSession('existing')
  expect(state.currentUnconfirmedUploads.value[0]?.uploadId).toBe(id)
  expect(state.currentConfirmedUploads.value).toEqual([])
  expect(state.attachments.value).toEqual([])
})

it.each(['attachment', 'media'] as const)(
  '%s 上传结果未知时阻止同名重传，其他文件和会话可继续上传',
  async (kind) => {
    if (kind === 'media')
      mediaUpload = async () => {
        throw new TypeError('offline')
      }
    else
      upload = async () => {
        throw new TypeError('offline')
      }
    const file = (name: string) =>
      kind === 'media'
        ? new File(['ok'], `${name}.png`, { type: 'image/png' })
        : new File(['ok'], `${name}.txt`, { type: 'text/plain' })
    await state.upload([file('same')])
    const originalId = uploads[0]!.get('upload_id')
    await state.upload([file('same')])
    expect(uploads).toHaveLength(1)
    expect(state.error.value).toContain('请先核对原上传')
    expect(state.currentUnconfirmedUploads.value[0]?.uploadId).toBe(originalId)
    await state.upload([file('other')])
    expect(uploads).toHaveLength(2)
    await state.openSession('other')
    await state.upload([file('same')])
    expect(uploads).toHaveLength(3)
    expect(uploads[2]!.get('session_id')).toBe('other')
  }
)

it('原附件查证完成后允许用户再次选择同名文件', async () => {
  const normal = upload
  upload = async () => {
    throw new TypeError('offline')
  }
  const file = new File(['ok'], 'same.txt', { type: 'text/plain' })
  await state.upload([file])
  const id = String(uploads[0]!.get('upload_id'))
  attachmentList = [
    {
      id,
      name: file.name,
      input_path: 'inputs/same.txt',
      size: 2,
      sha256: 'hash',
      expires_at: '2027-01-01T00:00:00Z'
    }
  ]
  await state.checkUnconfirmedUpload(id)
  expect(state.currentUnconfirmedUploads.value).toEqual([])
  upload = normal
  await state.upload([file])
  expect(uploads).toHaveLength(2)
  expect(uploads[1]!.get('upload_id')).not.toBe(id)
})

it.each(['supplement', 'locked'] as const)(
  '附件核对期间进入 %s 时保留回执，不改写草稿',
  async (mode) => {
    upload = async () => {
      throw new TypeError('offline')
    }
    await state.upload([new File(['ok'], 'pending.txt', { type: 'text/plain' })])
    const id = String(uploads[0]!.get('upload_id'))
    const item = {
      id,
      name: 'pending.txt',
      input_path: 'inputs/pending.txt',
      size: 2,
      sha256: 'hash',
      expires_at: '2027-01-01T00:00:00Z'
    }
    let finish!: (value: Response) => void
    attachmentRead = () =>
      new Promise((resolve) => {
        finish = resolve
      })
    const checking = state.checkUnconfirmedUpload(id)
    await vi.waitFor(() => expect(finish).toBeDefined())
    if (mode === 'supplement') state.inputMode.value = 'supplement'
    else state.pendingInput.value = {} as never
    finish(response([item]))
    await checking
    expect(state.attachments.value).toEqual([])
    expect(state.draft.value).toBe('已有正文草稿')
    expect(state.currentUnconfirmedUploads.value).toEqual([])
    expect(state.currentConfirmedUploads.value[0]?.uploadId).toBe(id)
    expect(
      JSON.parse(sessionStorage.getItem('skillforge.chat.unconfirmedUploads')!)[0].receipt.id
    ).toBe(id)
    state.inputMode.value = 'message'
    state.pendingInput.value = undefined
    attachmentRead = undefined
    attachmentList = [item]
    await state.claimConfirmedUpload(id)
    expect(state.attachments.value.map((value) => value.id)).toEqual([id])
    expect(state.currentConfirmedUploads.value).toEqual([])
    expect(uploads).toHaveLength(1)
  }
)

describe('上传批次的本地预检', () => {
  it.each([
    new File(['bad'], 'invalid.pdf', { type: 'application/pdf' }),
    new File(['x'.repeat(11)], 'oversized.txt', { type: 'text/plain' })
  ])('非法尾项$file.name不使合法首项产生上传POST', async (invalid) => {
    const first = new File(['ok'], 'first.txt', { type: 'text/plain' })
    const confirmed = await state.upload([first, invalid])
    expect(confirmed).toEqual([])
    expect(uploads).toHaveLength(0)
    expect(state.attachments.value).toEqual([])
    expect(state.draft.value).toBe('已有正文草稿')
    expect(state.error.value).toContain(invalid.name)
    expect(state.uploading.value).toBe(false)
  })

  it('图片批次也在POST前校验全部MIME与大小', async () => {
    const first = new File(['ok'], 'first.png', { type: 'image/png' })
    const invalid = new File(['bad'], 'last.jpeg', { type: 'image/jpeg' })
    expect(await state.upload([first, invalid])).toEqual([])
    expect(uploads).toHaveLength(0)
    expect(state.images.value).toEqual([])
    expect(state.error.value).toContain('last.jpeg')
  })

  it.each([
    [
      new File(['bad'], 'invalid.pdf', { type: 'application/pdf' }),
      new File(['ok'], 'valid.png', { type: 'image/png' })
    ],
    [
      new File(['ok'], 'valid.txt', { type: 'text/plain' }),
      new File(['bad'], 'invalid.jpeg', { type: 'image/jpeg' })
    ]
  ])('混合批次任何文件不合规则不上传并保留失败提示', async (first, second) => {
    expect(await state.upload([first, second])).toEqual([])
    expect(uploads).toHaveLength(0)
    expect(state.error.value).toContain(first.name.startsWith('invalid') ? first.name : second.name)
    expect(state.draft.value).toBe('已有正文草稿')
  })

  it.each([
    new File([], 'empty.txt', { type: 'text/plain' }),
    new File([], 'empty.png', { type: 'image/png' }),
    new File(['bad'], `${'a'.repeat(129)}.txt`, { type: 'text/plain' }),
    new File(['bad'], 'bad\u0001.txt', { type: 'text/plain' })
  ])('空文件或不合规名称 $name 在批次上传前拒绝', async (invalid) => {
    expect(
      await state.upload([new File(['ok'], 'valid.txt', { type: 'text/plain' }), invalid])
    ).toEqual([])
    expect(uploads).toHaveLength(0)
    expect(state.error.value).toContain(invalid.name)
  })

  it('服务端错误回执不能证明未写入，保留原上传身份且切换会话后可见', async () => {
    upload = async () =>
      new Response(
        JSON.stringify({ code: 'CONFLICT', message: '冲突', data: null, trace_id: 'test' }),
        { status: 409, headers: { 'content-type': 'application/json' } }
      )
    await state.upload([new File(['ok'], 'first.txt', { type: 'text/plain' })])
    const id = uploads[0]!.get('upload_id')
    expect(state.currentUnconfirmedUploads.value[0]).toMatchObject({
      session: 'existing',
      uploadId: id,
      name: 'first.txt'
    })
    expect(state.currentUnconfirmedUploads.value).toHaveLength(1)
    await state.openSession('other')
    expect(state.currentUnconfirmedUploads.value).toEqual([])
    await state.openSession('existing')
    expect(state.currentUnconfirmedUploads.value[0]!.uploadId).toBe(id)
    expect(
      JSON.parse(sessionStorage.getItem('skillforge.chat.unconfirmedUploads')!)[0].uploadId
    ).toBe(id)
    expect(uploads).toHaveLength(1)
  })

  it('只读核对未找到时保持未确认，找到原标识才恢复草稿', async () => {
    upload = async () => {
      throw new TypeError('offline')
    }
    await state.upload([new File(['ok'], 'check.txt', { type: 'text/plain' })])
    const id = uploads[0]!.get('upload_id') as string
    await state.checkUnconfirmedUpload(id)
    expect(state.currentUnconfirmedUploads.value).toHaveLength(1)
    expect(state.notice.value).toContain('仍未确认')
    attachmentList = [
      {
        id,
        name: 'check.txt',
        input_path: 'inputs/check.txt',
        size: 2,
        sha256: 'hash',
        expires_at: '2027-01-01T00:00:00Z'
      }
    ]
    state.limits.value!.attachments.max_files = 0
    await state.checkUnconfirmedUpload(id)
    expect(state.currentUnconfirmedUploads.value).toHaveLength(1)
    expect(state.attachments.value).toEqual([])
    expect(state.error.value).toContain('超过当前会话限制')
    state.limits.value!.attachments.max_files = 10
    await state.checkUnconfirmedUpload(id)
    expect(state.currentUnconfirmedUploads.value).toEqual([])
    expect(state.attachments.value.map((item) => item.id)).toEqual([id])
    expect(uploads).toHaveLength(1)
    expect(sessionStorage.getItem('skillforge.chat.unconfirmedUploads')).toBeNull()
  })

  it('上传回执在途时禁止核对，确认回执只添加一次', async () => {
    let finish!: (value: Response) => void
    upload = () =>
      new Promise((resolve) => {
        finish = resolve
      })
    const pending = state.upload([new File(['ok'], 'pending.txt', { type: 'text/plain' })])
    await vi.waitFor(() => expect(uploads).toHaveLength(1))
    const id = uploads[0]!.get('upload_id') as string
    expect(state.isUploadInFlight(state.currentUnconfirmedUploads.value[0]!)).toBe(true)
    await state.checkUnconfirmedUpload(id)
    expect(attachmentReads).toBe(0)
    finish(
      response({
        id,
        name: 'pending.txt',
        input_path: 'inputs/pending.txt',
        size: 2,
        sha256: 'hash',
        expires_at: '2027-01-01T00:00:00Z'
      })
    )
    await pending
    expect(state.attachments.value.map((item) => item.id)).toEqual([id])
    expect(state.currentUnconfirmedUploads.value).toEqual([])
    await state.checkUnconfirmedUpload(id)
    expect(attachmentReads).toBe(0)
  })

  it('切换后返回原会话，未终结上传仍不可核对，迟到失败保留原身份', async () => {
    let reject!: (reason: Error) => void
    upload = () =>
      new Promise((_, fail) => {
        reject = fail
      })
    const pending = state.upload([new File(['ok'], 'late.txt', { type: 'text/plain' })])
    await vi.waitFor(() => expect(uploads).toHaveLength(1))
    const id = uploads[0]!.get('upload_id') as string
    await state.openSession('other')
    await state.openSession('existing')
    await state.checkUnconfirmedUpload(id)
    expect(attachmentReads).toBe(0)
    reject(new TypeError('offline'))
    await pending
    expect(state.currentUnconfirmedUploads.value[0]!.uploadId).toBe(id)
    expect(state.attachments.value).toEqual([])
    expect(uploads).toHaveLength(1)
  })

  it.each([
    new File(['ok'], 'new.txt', { type: 'text/plain' }),
    new File(['ok'], 'new.png', { type: 'image/png' })
  ])('存储不可写时 $name 不派发，保留之前的未知上传记录', async (file) => {
    upload = async () => {
      throw new TypeError('offline')
    }
    await state.upload([new File(['ok'], 'prior.txt', { type: 'text/plain' })])
    expect(uploads).toHaveLength(1)
    const key = 'skillforge.chat.unconfirmedUploads'
    const original = sessionStorage.getItem(key)
    expect(original).not.toBeNull()
    const remove = vi.spyOn(Storage.prototype, 'removeItem')
    const set = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('storage unavailable')
    })
    expect(await state.upload([file])).toEqual([])
    expect(uploads).toHaveLength(1)
    expect(sessionStorage.getItem(key)).toBe(original)
    expect(remove).not.toHaveBeenCalledWith(key)
    expect(state.currentUnconfirmedUploads.value).toHaveLength(1)
    expect(state.currentUnconfirmedUploads.value[0]!.name).toBe('prior.txt')
    expect(state.error.value).toContain('尚未上传')
    expect(state.draft.value).toBe('已有正文草稿')
    expect(state.uploadStorageUnavailable.value).toBe(true)
    set.mockRestore()
    remove.mockRestore()
  })

  it('批次中途无法保存标识时只保留已完成附件，后续文件不发送', async () => {
    const original = Storage.prototype.setItem
    let writes = 0
    const storage = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(function (
      this: Storage,
      key: string,
      value: string
    ) {
      if (key === 'skillforge.chat.unconfirmedUploads' && ++writes === 2)
        throw new Error('storage unavailable')
      original.call(this, key, value)
    })
    const result = await state.upload([
      new File(['ok'], 'first.txt', { type: 'text/plain' }),
      new File(['ok'], 'second.txt', { type: 'text/plain' }),
      new File(['ok'], 'third.txt', { type: 'text/plain' })
    ])
    expect(result).toHaveLength(1)
    expect(uploads).toHaveLength(1)
    expect(state.attachments.value.map((item) => item.name)).toEqual(['first.txt'])
    expect(state.currentUnconfirmedUploads.value).toEqual([])
    expect(state.error.value).toContain('second.txt')
    expect(state.error.value).toContain('尚未上传')
    storage.mockRestore()
  })

  it('原上传记录无法读取时不覆盖记录或派发新上传', async () => {
    wrapper.unmount()
    const storage = vi.spyOn(Storage.prototype, 'getItem')
    const original = storage.getMockImplementation() ?? Storage.prototype.getItem
    storage.mockImplementation(function (this: Storage, key: string) {
      if (key === 'skillforge.chat.unconfirmedUploads') throw new Error('storage unavailable')
      return original.call(this, key)
    })
    wrapper = mount(
      defineComponent({
        setup() {
          state = useConversation()
          return () => h('div')
        }
      })
    )
    storage.mockRestore()
    await state.connect('http://localhost:8000', { mode: 'token', token: 'upload-test' }, '访客')
    await state.openSession('existing')
    expect(state.uploadStorageUnavailable.value).toBe(true)
    expect(await state.upload([new File(['ok'], 'new.txt', { type: 'text/plain' })])).toEqual([])
    expect(uploads).toHaveLength(0)
    expect(state.error.value).toContain('尚未上传')
  })

  it('清除持久记录失败时持续提示存储异常', async () => {
    upload = async () => {
      throw new TypeError('offline')
    }
    await state.upload([new File(['ok'], 'check.txt', { type: 'text/plain' })])
    const id = uploads[0]!.get('upload_id') as string
    attachmentList = [
      {
        id,
        name: 'check.txt',
        input_path: 'inputs/check.txt',
        size: 2,
        sha256: 'hash',
        expires_at: '2027-01-01T00:00:00Z'
      }
    ]
    const remove = vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => {
      throw new Error('storage unavailable')
    })
    await state.checkUnconfirmedUpload(id)
    expect(state.currentUnconfirmedUploads.value).toEqual([])
    expect(state.uploadStorageUnavailable.value).toBe(true)
    expect(state.attachments.value.map((item) => item.id)).toEqual([id])
    remove.mockRestore()
  })

  it('合法的文档和图片均进入当前草稿', async () => {
    const files = [
      new File(['ok'], 'valid.txt', { type: 'text/plain' }),
      new File(['ok'], 'valid.png', { type: 'image/png' })
    ]
    expect(await state.upload(files)).toHaveLength(2)
    expect(uploads).toHaveLength(2)
    expect(state.attachments.value.map((item) => item.name)).toEqual(['valid.txt'])
    expect(state.images.value.map((item) => item.media_id)).toEqual(['media'])
  })

  it('混合批次中途失败时停止后续图片，保留已确认的文件', async () => {
    const normalUpload = upload
    upload = (body) =>
      uploads.length === 2 ? Promise.reject(new TypeError('offline')) : normalUpload(body)
    const files = [
      new File(['ok'], 'first.txt', { type: 'text/plain' }),
      new File(['ok'], 'second.txt', { type: 'text/plain' }),
      new File(['ok'], 'last.png', { type: 'image/png' })
    ]
    const confirmed = await state.upload(files)
    expect(uploads).toHaveLength(2)
    expect(confirmed.map((item) => item.file.name)).toEqual(['first.txt'])
    expect(state.attachments.value.map((item) => item.name)).toEqual(['first.txt'])
    expect(state.images.value).toEqual([])
    expect(state.currentUnconfirmedUploads.value[0]!.name).toBe('second.txt')
  })

  it('上传中切换会话后不将后续文件上传到新会话', async () => {
    let finish!: (value: Response) => void
    upload = () =>
      new Promise((resolve) => {
        finish = resolve
      })
    const pending = state.upload([
      new File(['ok'], 'first.txt', { type: 'text/plain' }),
      new File(['ok'], 'last.png', { type: 'image/png' })
    ])
    await vi.waitFor(() => expect(uploads).toHaveLength(1))
    await state.openSession('new-session')
    finish(response({ id: 'old', name: 'first.txt', size: 2 }))
    expect(await pending).toEqual([])
    expect(uploads).toHaveLength(1)
    expect(state.attachments.value).toEqual([])
    expect(state.images.value).toEqual([])
  })

  it.each(['attachment', 'media'] as const)(
    '切换后迟到的 %s 成功回执保留在原会话，可领取一次',
    async (kind) => {
      let finish!: (value: Response) => void
      const delayed = () =>
        new Promise<Response>((resolve) => {
          finish = resolve
        })
      if (kind === 'attachment') upload = delayed
      else mediaUpload = delayed
      const file =
        kind === 'media'
          ? new File(['ok'], 'late.png', { type: 'image/png' })
          : new File(['ok'], 'late.txt', { type: 'text/plain' })
      const pending = state.upload([file])
      await vi.waitFor(() => expect(uploads).toHaveLength(1))
      const id = uploads[0]!.get('upload_id') as string
      await state.openSession('new-session')
      finish(
        response(
          kind === 'media'
            ? {
                media_id: 'late-media',
                mime_type: 'image/png',
                byte_length: 2,
                width: 1,
                height: 1
              }
            : {
                id,
                name: file.name,
                input_path: 'inputs/late.txt',
                size: 2,
                sha256: 'hash',
                expires_at: '2027-01-01T00:00:00Z'
              }
        )
      )
      await pending
      expect(state.currentConfirmedUploads.value).toEqual([])
      await state.openSession('existing')
      expect(state.currentUnconfirmedUploads.value).toEqual([])
      expect(state.currentConfirmedUploads.value[0]).toMatchObject({ uploadId: id, kind })
      expect(
        JSON.parse(sessionStorage.getItem('skillforge.chat.unconfirmedUploads')!)[0].receipt
      ).toBeTruthy()
      if (kind === 'attachment')
        attachmentList = [
          {
            id,
            name: file.name,
            input_path: 'inputs/late.txt',
            size: 2,
            sha256: 'hash',
            expires_at: '2027-01-01T00:00:00Z'
          }
        ]
      await state.claimConfirmedUpload(id)
      await state.claimConfirmedUpload(id)
      expect(state.currentConfirmedUploads.value).toEqual([])
      expect(
        kind === 'media'
          ? state.images.value.map((item) => item.media_id)
          : state.attachments.value.map((item) => item.id)
      ).toEqual([kind === 'media' ? 'late-media' : id])
    }
  )

  it('补充输入或锁定期间不领取已确认附件，异步读取后模式变化仍保留回执', async () => {
    let finishUpload!: (value: Response) => void
    upload = () =>
      new Promise((resolve) => {
        finishUpload = resolve
      })
    const operation = state.upload([new File(['ok'], 'late.txt', { type: 'text/plain' })])
    await vi.waitFor(() => expect(uploads).toHaveLength(1))
    const id = uploads[0]!.get('upload_id') as string
    await state.openSession('other')
    const item = {
      id,
      name: 'late.txt',
      input_path: 'inputs/late.txt',
      size: 2,
      sha256: 'hash',
      expires_at: '2027-01-01T00:00:00Z'
    }
    finishUpload(response(item))
    await operation
    await state.openSession('existing')
    state.inputMode.value = 'supplement'
    const reads = attachmentReads
    await state.claimConfirmedUpload(id)
    expect(attachmentReads).toBe(reads)
    expect(state.currentConfirmedUploads.value).toHaveLength(1)
    state.inputMode.value = 'message'
    state.pendingInput.value = {} as never
    await state.claimConfirmedUpload(id)
    expect(attachmentReads).toBe(reads)
    expect(state.currentConfirmedUploads.value).toHaveLength(1)
    state.pendingInput.value = undefined
    let finishRead!: (value: Response) => void
    attachmentRead = () =>
      new Promise((resolve) => {
        finishRead = resolve
      })
    const claim = state.claimConfirmedUpload(id)
    await vi.waitFor(() => expect(attachmentReads).toBe(reads + 1))
    state.inputMode.value = 'supplement'
    finishRead(response([item]))
    await claim
    expect(state.attachments.value).toEqual([])
    expect(state.currentConfirmedUploads.value).toHaveLength(1)
    state.inputMode.value = 'message'
    attachmentRead = undefined
    attachmentList = [item]
    await state.claimConfirmedUpload(id)
    expect(state.attachments.value.map((value) => value.id)).toEqual([id])
    expect(state.currentConfirmedUploads.value).toEqual([])
  })

  it('已确认附件失效、读取失败或超容量时保留回执', async () => {
    let finish!: (value: Response) => void
    upload = () =>
      new Promise((resolve) => {
        finish = resolve
      })
    const operation = state.upload([new File(['ok'], 'late.txt', { type: 'text/plain' })])
    await vi.waitFor(() => expect(uploads).toHaveLength(1))
    const id = uploads[0]!.get('upload_id') as string
    await state.openSession('other')
    const item = {
      id,
      name: 'late.txt',
      input_path: 'inputs/late.txt',
      size: 2,
      sha256: 'hash',
      expires_at: '2027-01-01T00:00:00Z'
    }
    finish(response(item))
    await operation
    await state.openSession('existing')
    await state.claimConfirmedUpload(id)
    expect(state.notice.value).toContain('已不可用')
    expect(state.currentConfirmedUploads.value).toHaveLength(1)
    attachmentReadFails = true
    await state.claimConfirmedUpload(id)
    expect(state.error.value).toContain('list offline')
    expect(state.currentConfirmedUploads.value).toHaveLength(1)
    attachmentReadFails = false
    attachmentList = [item]
    state.limits.value!.attachments.max_files = 0
    await state.claimConfirmedUpload(id)
    expect(state.error.value).toContain('超过当前会话限制')
    expect(state.currentConfirmedUploads.value).toHaveLength(1)
    expect(state.attachments.value).toEqual([])
  })

  it('迟到粘贴附件在原页面保留原文恢复，正文不写浏览器存储', async () => {
    state.limits.value!.attachments.max_file_bytes = 10000
    state.limits.value!.attachments.max_total_bytes = 20000
    const raw = '敏感内容'.repeat(600)
    expect(state.stagePastedText(raw)).toBe(true)
    let finish!: (value: Response) => void
    upload = () =>
      new Promise((resolve) => {
        finish = resolve
      })
    const operation = state.submit()
    await vi.waitFor(() => expect(uploads).toHaveLength(1))
    const id = uploads[0]!.get('upload_id') as string
    await state.openSession('other')
    const item = {
      id,
      name: '粘贴文本.txt',
      input_path: 'inputs/late.txt',
      size: 2,
      sha256: 'hash',
      expires_at: '2027-01-01T00:00:00Z'
    }
    finish(response(item))
    await operation
    expect(sessionStorage.getItem('skillforge.chat.unconfirmedUploads')).not.toContain(raw)
    await state.openSession('existing')
    attachmentList = [item]
    await state.claimConfirmedUpload(id)
    expect(state.attachmentTexts.value[id]).toBe(raw)
    expect(state.restoreAttachmentText(id)).toBe(raw.length)
    expect(state.draft.value).toBe(raw)
  })

  it('页面重建后领取已确认粘贴附件时提示原文恢复不可用', async () => {
    state.limits.value!.attachments.max_file_bytes = 10000
    state.limits.value!.attachments.max_total_bytes = 20000
    const raw = '私密内容'.repeat(600)
    expect(state.stagePastedText(raw)).toBe(true)
    let finish!: (value: Response) => void
    upload = () =>
      new Promise((resolve) => {
        finish = resolve
      })
    const operation = state.submit()
    await vi.waitFor(() => expect(uploads).toHaveLength(1))
    const id = uploads[0]!.get('upload_id') as string
    await state.openSession('other')
    const item = {
      id,
      name: '粘贴文本.txt',
      input_path: 'inputs/late.txt',
      size: 2,
      sha256: 'hash',
      expires_at: '2027-01-01T00:00:00Z'
    }
    finish(response(item))
    await operation
    wrapper.unmount()
    wrapper = mount(
      defineComponent({
        setup() {
          state = useConversation()
          return () => h('div')
        }
      })
    )
    await state.connect('http://localhost:8000', { mode: 'token', token: 'upload-test' }, '访客')
    await state.openSession('existing')
    attachmentList = [item]
    await state.claimConfirmedUpload(id)
    expect(state.attachments.value.map((entry) => entry.id)).toEqual([id])
    expect(state.attachmentTexts.value[id]).toBeUndefined()
    expect(state.notice.value).toContain('刷新后原文恢复不可用')
  })

  it.each(['restore', 'remove'] as const)('未确认粘贴项被 %s 后核对不自动添加', async (action) => {
    state.limits.value!.attachments.max_file_bytes = 10000
    state.limits.value!.attachments.max_total_bytes = 20000
    const raw = '敏感内容'.repeat(600)
    state.stagePastedText(raw)
    upload = async () => {
      throw new TypeError('offline')
    }
    await state.submit()
    const id = uploads[0]!.get('upload_id') as string
    if (action === 'restore') state.restorePastedText(id)
    else state.removePastedText(id)
    attachmentList = [
      {
        id,
        name: '粘贴文本.txt',
        input_path: 'inputs/a.txt',
        size: 2,
        sha256: 'hash',
        expires_at: '2027-01-01T00:00:00Z'
      }
    ]
    await state.checkUnconfirmedUpload(id)
    expect(state.attachments.value).toEqual([])
    expect(state.currentConfirmedUploads.value[0]!.uploadId).toBe(id)
    expect(state.notice.value).toContain('未自动加入草稿')
    expect(state.draft.value.includes(raw)).toBe(action === 'restore')
  })

  it('核对在途期间还原粘贴项后不自动添加附件', async () => {
    state.limits.value!.attachments.max_file_bytes = 10000
    state.limits.value!.attachments.max_total_bytes = 20000
    const raw = '敏感内容'.repeat(600)
    state.stagePastedText(raw)
    upload = async () => {
      throw new TypeError('offline')
    }
    await state.submit()
    const id = uploads[0]!.get('upload_id') as string
    let finish!: (value: Response) => void
    attachmentRead = () =>
      new Promise((resolve) => {
        finish = resolve
      })
    const checking = state.checkUnconfirmedUpload(id)
    await vi.waitFor(() => expect(attachmentReads).toBe(1))
    state.restorePastedText(id)
    finish(
      response([
        {
          id,
          name: '粘贴文本.txt',
          input_path: 'inputs/a.txt',
          size: 2,
          sha256: 'hash',
          expires_at: '2027-01-01T00:00:00Z'
        }
      ])
    )
    await checking
    expect(state.attachments.value).toEqual([])
    expect(state.currentConfirmedUploads.value[0]!.uploadId).toBe(id)
    expect(state.draft.value).toContain(raw)
  })

  it('网络结果未知仍停止在原失败项，保留此前确认项且不自动重放', async () => {
    const normalUpload = upload
    upload = (body) =>
      uploads.length === 2 ? Promise.reject(new TypeError('offline')) : normalUpload(body)
    const files = ['first.txt', 'second.txt', 'third.txt'].map(
      (name) => new File(['ok'], name, { type: 'text/plain' })
    )
    const confirmed = await state.upload(files)
    expect(uploads).toHaveLength(2)
    expect(confirmed.map((item) => item.file.name)).toEqual(['first.txt'])
    expect(state.attachments.value.map((item) => item.name)).toEqual(['first.txt'])
    expect(
      uploads.every((body) => typeof body.get('upload_id') === 'string' && body.get('upload_id'))
    ).toBe(true)
    expect(state.attachmentFiles.value[state.attachments.value[0]!.id]).toBe(files[0])
    expect(state.draft.value).toBe('已有正文草稿')
    expect(state.uploading.value).toBe(false)
  })
})
