import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElDrawer } from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import { confirm, message } from '../../feedback'
import MessagingPage from './MessagingPage.vue'
import FoundationOptionSelect from '../FoundationOptionSelect.vue'
import type { MessagingRequest } from './messaging'

vi.mock('../../feedback', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../feedback')>()),
  confirm: vi.fn(),
  message: { success: vi.fn(), warning: vi.fn() }
}))

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => {
    resolve = done
  })
  return { promise, resolve }
}

async function mountPage(
  permissions: string[],
  request: MessagingRequest,
  inlineDrawer = false,
  initialLocation = '/messaging'
) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/messaging', component: MessagingPage }]
  })
  await router.push(initialLocation)
  await router.isReady()
  const wrapper = mount(MessagingPage, {
    attachTo: document.body,
    props: {
      permissions,
      request,
      basePath: '/tenant/foundation/messaging',
      identityKey: 'tenant-1/member-1'
    },
    global: { plugins: [router, ElementPlus], stubs: inlineDrawer ? { teleport: true } : {} }
  })
  await flushPromises()
  return wrapper
}

const deliveries = {
  items: [
    {
      id: '1',
      notification_id: '11',
      status: 'unknown',
      attempts: 1,
      error_code: 'SMTP_RESULT_UNKNOWN',
      updated_at: '2026-10-11T00:00:00Z'
    },
    {
      id: '2',
      notification_id: '12',
      status: 'rejected',
      attempts: 1,
      error_code: 'SMTP_REJECTED',
      updated_at: '2026-10-11T00:00:00Z'
    },
    {
      id: '3',
      notification_id: '13',
      status: 'accepted',
      attempts: 1,
      error_code: null,
      updated_at: '2026-10-11T00:00:00Z'
    }
  ],
  has_more: false,
  next_cursor: null
}
const mailbox = {
  configured: true,
  enabled: false,
  host: 'smtp.example.com',
  port: 587,
  username: 'sender',
  from_address: 'sender@example.com',
  tls_mode: 'starttls',
  credential_configured: true
}

describe('消息页真实状态与账号边界', () => {
  beforeEach(() => {
    vi.clearAllMocks()
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
    vi.unstubAllGlobals()
    document.body.innerHTML = ''
  })
  it('从地址恢复公告分页，上一页沿原游标读取，切页签清空分页且只读取一次', async () => {
    const request = vi.fn(async (_method: string, path: string) =>
      path.endsWith('/unread') ? 0 : { items: [], has_more: true, next_cursor: 'page-3' }
    )
    const wrapper = await mountPage(
      ['foundation.messaging.inbox', 'foundation.messaging.read'],
      request as MessagingRequest,
      false,
      '/messaging?tab=announcements&cursor=page-2&previous_cursor='
    )
    expect(request.mock.calls.filter((call) => call[1].includes('/announcements'))).toEqual([
      [
        'GET',
        '/tenant/foundation/messaging/announcements?limit=20&cursor=page-2',
        undefined,
        expect.anything()
      ]
    ])
    await wrapper
      .findAll('button')
      .find((item) => item.text() === '上一页')!
      .trigger('click')
    await flushPromises()
    expect(wrapper.vm.$router.currentRoute.value.query.cursor).toBeUndefined()
    expect(request.mock.calls.at(-2)?.[1]).toBe(
      '/tenant/foundation/messaging/announcements?limit=20'
    )
    await wrapper
      .findAll('button')
      .find((item) => item.text() === '下一页')!
      .trigger('click')
    await flushPromises()
    expect(wrapper.vm.$router.currentRoute.value.query).toMatchObject({
      tab: 'announcements',
      cursor: 'page-3',
      previous_cursor: ['']
    })
    request.mockClear()
    await wrapper
      .findAll('[role="tab"]')
      .find((item) => item.text() === '我的消息')!
      .trigger('click')
    await flushPromises()
    expect(wrapper.vm.$router.currentRoute.value.query).toEqual({ tab: 'inbox' })
    expect(request.mock.calls.filter((call) => call[1].includes('/inbox'))).toHaveLength(1)
    expect(request.mock.calls.every((call) => call[0] === 'GET')).toBe(true)
    wrapper.unmount()
  })

  it('未读筛选在重新挂载后恢复，权限不允许的地址回到可用页签且不查询原游标', async () => {
    const request = vi.fn(async (_method: string, path: string) =>
      path.endsWith('/unread') ? 0 : { items: [], has_more: false, next_cursor: null }
    )
    const wrapper = await mountPage(['foundation.messaging.inbox'], request as MessagingRequest)
    await wrapper.get('input[type="checkbox"]').setValue(true)
    await flushPromises()
    const savedLocation = wrapper.vm.$router.currentRoute.value.fullPath
    expect(savedLocation).toContain('unread=1')
    wrapper.unmount()
    const restored = await mountPage(
      ['foundation.messaging.inbox'],
      request as MessagingRequest,
      false,
      savedLocation
    )
    expect((restored.get('input[type="checkbox"]').element as HTMLInputElement).checked).toBe(true)
    expect(request.mock.calls.at(-2)?.[1]).toContain('inbox?limit=20&unread=true')
    await restored.setProps({ permissions: ['foundation.messaging.inbox'] })
    await flushPromises()
    request.mockClear()
    await restored.vm.$router.replace(
      '/messaging?tab=mailbox&cursor=private-page&previous_cursor=old'
    )
    await flushPromises()
    expect(request.mock.calls.filter((call) => call[1].includes('/inbox'))[0]?.[1]).toBe(
      '/tenant/foundation/messaging/inbox?limit=20&unread=false'
    )
    expect(request.mock.calls.some((call) => call[1].includes('/mailbox'))).toBe(false)
    restored.unmount()
  })

  it('邮箱草稿取消切页签时地址和表单不变，确认离开后清除密码且不写入配置', async () => {
    const request = vi.fn(async (_method: string, path: string) =>
      path.endsWith('/mailbox') ? { ...mailbox } : []
    )
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        {
          path: '/messaging',
          component: MessagingPage,
          props: {
            permissions: ['foundation.messaging.configure'],
            request,
            basePath: '/tenant/foundation/messaging',
            identityKey: 'draft-test'
          }
        }
      ]
    })
    await router.push('/messaging?tab=mailbox')
    await router.isReady()
    const wrapper = mount(RouterView, {
      attachTo: document.body,
      global: { plugins: [router, ElementPlus] }
    })
    await flushPromises()
    await wrapper.get('input[type="password"]').setValue('unsubmitted-secret')
    vi.mocked(confirm).mockResolvedValueOnce(false)
    await wrapper
      .findAll('[role="tab"]')
      .find((item) => item.text() === '通知模板')!
      .trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.query.tab).toBe('mailbox')
    expect(wrapper.get('[role="tab"][aria-selected="true"]').text()).toBe('SMTP 邮箱')
    expect((wrapper.get('input[type="password"]').element as HTMLInputElement).value).toBe(
      'unsubmitted-secret'
    )
    expect(router.currentRoute.value.fullPath).not.toContain('unsubmitted-secret')
    vi.mocked(confirm).mockResolvedValueOnce(true)
    await wrapper
      .findAll('[role="tab"]')
      .find((item) => item.text() === '通知模板')!
      .trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.query.tab).toBe('templates')
    await wrapper
      .findAll('[role="tab"]')
      .find((item) => item.text() === 'SMTP 邮箱')!
      .trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.query.tab).toBe('mailbox')
    expect((wrapper.get('input[type="password"]').element as HTMLInputElement).value).toBe('')
    expect(confirm).toHaveBeenCalledTimes(2)
    expect(request.mock.calls.every((call) => call[0] === 'GET')).toBe(true)
    wrapper.unmount()
  })
  it('未读数量读取失败有独立可见提示，不把消息列表成功误当成未读数成功', async () => {
    const request = vi.fn(async (_method: string, path: string) => {
      if (path.endsWith('/unread')) throw new Error('未读统计暂不可用')
      return { items: [], has_more: false, next_cursor: null }
    })
    const wrapper = await mountPage(['foundation.messaging.inbox'], request as MessagingRequest)
    expect(document.querySelector('.el-message--error')?.textContent).toContain(
      '未读数量读取失败：未读统计暂不可用'
    )
    expect(wrapper.text()).toContain('未读：—')
    expect(wrapper.text()).toContain('暂无消息')
    wrapper.unmount()
  })
  it('邮箱读取失败不会把未读到的配置标成凭据未配置', async () => {
    const request = vi.fn(async (_method: string, path: string) => {
      if (path.endsWith('/mailbox')) throw new Error('邮箱配置读取暂不可用')
      return []
    })
    const wrapper = await mountPage(['foundation.messaging.configure'], request as MessagingRequest)
    await wrapper
      .findAll('[role="tab"]')
      .find((item) => item.text() === 'SMTP 邮箱')!
      .trigger('click')
    await flushPromises()
    expect(document.querySelector('.el-message--error')?.textContent).toContain(
      '邮箱配置读取暂不可用'
    )
    expect(wrapper.text()).not.toContain('凭据未配置；默认不发送邮件')
    wrapper.unmount()
  })

  it.each(['inbox', 'read'] as const)(
    '仅%s权限可读完整纯文本，展开不写已读且身份切换清空正文',
    async (permission) => {
      const body = '第一段\n\n<img src=x onerror=alert(1)>\n' + '长正文'.repeat(400)
      const item = {
        id: '41',
        title: '完整阅读',
        body,
        created_at: '2026-11-25T06:00:00Z',
        ...(permission === 'inbox'
          ? { read_at: null }
          : { status: 'withdrawn', recipients: 1, audience: 'members' })
      }
      const request = vi.fn(async (_method: string, path: string) =>
        path.endsWith('/unread') ? 1 : { items: [item], has_more: false, next_cursor: null }
      )
      const wrapper = await mountPage(
        [`foundation.messaging.${permission}`],
        request as MessagingRequest,
        true
      )
      const read = wrapper.findAll('button').find((button) => button.text() === '阅读全文')!
      expect(read.attributes('aria-label')).toBe('阅读全文：完整阅读')
      await read.trigger('click')
      await flushPromises()
      expect(wrapper.get('.messaging-body').text()).toBe(body)
      expect(wrapper.find('.messaging-body img').exists()).toBe(false)
      expect(wrapper.get('.messaging-reading').text()).toContain(
        permission === 'inbox' ? '未读' : '已撤回'
      )
      expect(request.mock.calls.every((call) => call[0] === 'GET')).toBe(true)
      await wrapper
        .findAll('button')
        .find((button) => button.text() === '关闭')!
        .trigger('click')
      await flushPromises()
      await read.trigger('click')
      await flushPromises()
      await wrapper.setProps({ identityKey: 'tenant-2/member-2', permissions: [] })
      await flushPromises()
      expect(wrapper.find('.messaging-body').exists()).toBe(false)
      expect(request.mock.calls.every((call) => call[0] === 'GET')).toBe(true)
      wrapper.unmount()
    }
  )

  it('邮件投递空态说明 SMTP 未配置原因并可跳到配置', async () => {
    const request = vi.fn(async (_method: string, path: string) => {
      if (path.endsWith('/mailbox')) return mailbox
      if (path.endsWith('/unread')) return { count: 0 }
      if (path.endsWith('/templates')) return []
      return { items: [], has_more: false, next_cursor: null }
    })
    const wrapper = await mountPage(
      ['foundation.messaging.operate', 'foundation.messaging.configure'],
      request as MessagingRequest
    )
    await wrapper
      .findAll('[role="tab"]')
      .find((item) => item.text() === '邮件投递')!
      .trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('邮件仅在 SMTP 已配置并启用时实际投递')
    await wrapper
      .findAll('button')
      .find((item) => item.text() === '去配置 SMTP')!
      .trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('SMTP 主机')
    wrapper.unmount()
  })

  it('只有发布权限仍能按姓名选收件人，不额外读取组织目录或公告历史', async () => {
    const request = vi.fn(async (_method: string, path: string) =>
      path.includes('/options/')
        ? { items: [], has_more: false }
        : { items: [], has_more: false, next_cursor: null }
    )
    const wrapper = await mountPage(
      ['foundation.messaging.publish'],
      request as MessagingRequest,
      false
    )
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '发布公告')!
      .trigger('click')
    await flushPromises()
    const recipientRadio = document.querySelector<HTMLInputElement>('input[value="members"]')!
    recipientRadio.click()
    await flushPromises()
    expect(wrapper.getComponent(FoundationOptionSelect).props('path')).toBe(
      '/tenant/foundation/options/members?purpose=messaging'
    )
    expect(
      request.mock.calls.some((call) => call[1].includes('/options/members?purpose=messaging'))
    ).toBe(true)
    expect(
      request.mock.calls.some(
        (call) => call[1].includes('/access/') || call[1].includes('/announcements?')
      )
    ).toBe(false)
    wrapper.unmount()
  })

  it.each(['发布', '取消'] as const)(
    '公告%s后关闭动画保留原表单，再打开时重新初始化',
    async (action) => {
      vi.mocked(confirm).mockResolvedValue(true)
      const request = vi.fn(async (method: string) =>
        method === 'POST' ? 'announcement-1' : { items: [], has_more: false, next_cursor: null }
      )
      const wrapper = await mountPage(
        ['foundation.messaging.publish'],
        request as MessagingRequest,
        true
      )
      const open = wrapper.findAll('button').find((button) => button.text() === '发布公告')!
      await open.trigger('click')
      await flushPromises()
      const drawer = wrapper.get('.el-drawer')
      await drawer.get('input').setValue('公告关闭测试')
      await drawer.get('textarea').setValue('关闭动画期间保留此正文')
      await drawer
        .findAll('button')
        .find((button) => button.text() === action)!
        .trigger('click')
      await flushPromises()
      expect(wrapper.getComponent(ElDrawer).props('modelValue')).toBe(false)
      expect(drawer.text()).toContain('发布公告')
      expect(drawer.text()).not.toContain('标题模板')
      expect((drawer.get('textarea').element as HTMLTextAreaElement).value).toBe(
        '关闭动画期间保留此正文'
      )
      expect(request.mock.calls.filter((call) => call[0] === 'POST')).toHaveLength(
        action === '发布' ? 1 : 0
      )
      if (action === '发布') expect(confirm).not.toHaveBeenCalled()
      else expect(confirm).toHaveBeenCalledOnce()
      await open.trigger('click')
      await flushPromises()
      expect(wrapper.getComponent(ElDrawer).props('modelValue')).toBe(true)
      const reopened = wrapper.get('.el-drawer')
      expect((reopened.get('input').element as HTMLInputElement).value).toBe('')
      expect((reopened.get('textarea').element as HTMLTextAreaElement).value).toBe('')
      wrapper.unmount()
    }
  )

  it('模板变量从正文和标题自动提取去重，发布无需重复填写变量声明', async () => {
    const request = vi.fn(async (method: string) => (method === 'POST' ? '12' : []))
    const wrapper = await mountPage(
      ['foundation.messaging.configure'],
      request as MessagingRequest,
      true
    )
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '发布模板')!
      .trigger('click')
    await flushPromises()
    const drawer = wrapper.get('.el-drawer')
    const inputs = drawer.findAll('input')
    await inputs[0]!.setValue('business.notice')
    await inputs[1]!.setValue('你好 {{name}}')
    await drawer.get('textarea').setValue('{{name}}，处理结果为 {{status}}')
    expect(drawer.text()).toContain('自动识别变量：name、status')
    await drawer
      .findAll('button')
      .find((button) => button.text() === '发布')!
      .trigger('click')
    await flushPromises()
    expect(request.mock.calls.find((call) => call[0] === 'POST')).toMatchObject([
      'POST',
      '/tenant/foundation/messaging/templates/publish',
      { key: 'business.notice', variable_names: ['name', 'status'] }
    ])
    wrapper.unmount()
  })

  it('只看未读的原生复选框关联唯一可见说明，切换仍只发送读取', async () => {
    const request = vi
      .fn()
      .mockImplementation((_method: string, path: string) =>
        Promise.resolve(
          path.endsWith('/unread') ? 0 : { items: [], has_more: false, next_cursor: null }
        )
      )
    const wrapper = await mountPage(['foundation.messaging.inbox'], request as MessagingRequest)
    const control = wrapper.get('input[type="checkbox"]')
    const ids = control.attributes('aria-describedby')?.split(/\s+/).filter(Boolean) ?? []
    expect(ids.length).toBeGreaterThan(0)
    for (const id of ids) {
      const targets = wrapper.findAll(`[id="${id}"]`)
      expect(targets).toHaveLength(1)
      expect(targets[0]!.text().trim()).not.toBe('')
      expect(targets[0]!.isVisible()).toBe(true)
    }
    await control.setValue(true)
    await flushPromises()
    expect(request.mock.calls.every((call) => call[0] === 'GET')).toBe(true)
    wrapper.unmount()
  })

  it('unknown只有核验入口，SMTP已接受的邮件不提供重试', async () => {
    const request = vi.fn().mockResolvedValue(deliveries)
    const wrapper = await mountPage(['foundation.messaging.operate'], request as MessagingRequest)
    const rows = wrapper.findAll('.el-table__row')
    expect(rows).toHaveLength(3)
    expect(rows[0]!.text()).toContain('记录核验')
    expect(rows[0]!.text()).not.toContain('重新受理')
    expect(rows[1]!.text()).toContain('重新受理')
    expect(rows[2]!.text()).toContain('SMTP 已接受')
    expect(rows[2]!.find('button').exists()).toBe(false)
    expect(request.mock.calls.every((call) => call[0] === 'GET')).toBe(true)
    wrapper.unmount()
  })

  it('确认重试期间切换账号会取消旧确认，不向新账号提交旧投递ID', async () => {
    const confirmation = deferred<boolean>()
    vi.mocked(confirm).mockReturnValueOnce(confirmation.promise)
    const request = vi.fn().mockResolvedValue(deliveries)
    const wrapper = await mountPage(['foundation.messaging.operate'], request as MessagingRequest)
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '重新受理')!
      .trigger('click')
    await wrapper.setProps({ identityKey: 'tenant-1/member-2' })
    confirmation.resolve(true)
    await flushPromises()
    expect(request.mock.calls.every((call) => call[0] === 'GET')).toBe(true)
    expect(message.success).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('切换账号清空SMTP密码，旧账号保存的迟到成功不会覆盖新表单或提示成功', async () => {
    const saved = deferred<unknown>()
    const request = vi.fn().mockImplementation((method: string, path: string) => {
      if (method === 'PUT') return saved.promise
      return Promise.resolve(path.endsWith('/mailbox') ? mailbox : [])
    })
    const wrapper = await mountPage(['foundation.messaging.configure'], request as MessagingRequest)
    await wrapper
      .findAll('.el-tabs__item')
      .find((item) => item.text() === 'SMTP 邮箱')!
      .trigger('click')
    await flushPromises()
    await wrapper.get('input[type="password"]').setValue('private-smtp-password')
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '保存配置')!
      .trigger('click')
    expect(request.mock.calls.find((call) => call[0] === 'PUT')?.[2]).toMatchObject({
      password: 'private-smtp-password'
    })
    await wrapper.setProps({ identityKey: 'tenant-1/member-2' })
    await flushPromises()
    expect((wrapper.get('input[type="password"]').element as HTMLInputElement).value).toBe('')
    saved.resolve(undefined)
    await flushPromises()
    expect(message.success).not.toHaveBeenCalled()
    expect((wrapper.get('input[type="password"]').element as HTMLInputElement).value).toBe('')
    wrapper.unmount()
  })
})
