import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import App from './App.vue'
import type { useConversation } from './session'
import type { Client } from './client'
import { ChatMessage } from './ui/controls'

const holder = vi.hoisted(() => ({
  state: undefined as unknown as ReturnType<typeof useConversation>
}))
vi.mock('./session', async (original) => {
  const actual = await original<typeof import('./session')>()
  return {
    ...actual,
    useConversation() {
      const state = actual.useConversation()
      // 模板会缓存事件处理函数；挂载前替换动作，保留真实composable的ref返回契约。
      state.refreshSessions = vi.fn(async () => {})
      state.reconnect = vi.fn(async () => {})
      holder.state = state
      return state
    }
  }
})
let wrapper: VueWrapper
beforeEach(async () => {
  sessionStorage.clear()
  localStorage.clear()
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe() {}
      unobserve() {}
      disconnect() {}
    }
  )
  vi.stubGlobal('requestAnimationFrame', () => 1)
  vi.stubGlobal('cancelAnimationFrame', () => {})
  vi.stubGlobal('matchMedia', () => ({
    matches: false,
    addEventListener() {},
    removeEventListener() {}
  }))
  wrapper = mount(App, {
    attachTo: document.body,
    global: {
      stubs: {
        WorkspaceStatus: true,
        TeamManager: true,
        ArtifactPanel: true,
        BrowserDevices: true,
        BrowserConnectionCard: true,
        ConversationTools: true
      }
    }
  })
  holder.state.agents.value = [{ id: 'agent', name: '助手', is_default: true }]
  holder.state.client.value = {
    base: '/api/v1',
    json: vi.fn(async () => ({ items: [] }))
  } as unknown as Client
  holder.state.target.value = 'agent:agent'
  await flushPromises()
  await wrapper.get('[aria-label="打开会话导航"]').trigger('click')
  await flushPromises()
})
afterEach(() => {
  wrapper.unmount()
  ChatMessage.closeAll()
  vi.unstubAllGlobals()
  document.body.innerHTML = ''
})
const button = (text: string) => wrapper.findAll('button').find((value) => value.text() === text)!

describe('历史读取状态的真实页面入口', () => {
  it('历史列表加载和失败不显示空历史，并只通过已有顶部Message显示原因', async () => {
    holder.state.sessionsLoading.value = true
    await flushPromises()
    expect(wrapper.get('nav[aria-label="历史会话"]').text()).toContain('正在读取历史会话')
    expect(wrapper.text()).not.toContain('还没有历史会话')
    holder.state.sessionsLoading.value = false
    holder.state.sessionsError.value = '列表暂时不可用'
    holder.state.error.value = '列表暂时不可用'
    await flushPromises()
    expect(wrapper.text()).not.toContain('还没有历史会话')
    expect(wrapper.get('nav[aria-label="历史会话"]').text()).toContain('历史会话列表尚未读取成功')
    expect(document.querySelectorAll('.chat-toast')).toHaveLength(1)
    expect(document.querySelector('.chat-toast')?.textContent).toContain('列表暂时不可用')
    const retry = holder.state.refreshSessions
    await button('刷新会话').trigger('click')
    expect(retry).toHaveBeenCalledOnce()
  })

  it('主动刷新会话列表时保留搜索、当前会话和草稿，加载期间不可重复刷新', async () => {
    holder.state.sessions.value = [{ session_id: 'saved', title: '已保存历史', message_count: 3 }]
    holder.state.sessionId.value = 'saved'
    holder.state.draft.value = '尚未发送的内容'
    await wrapper.get('input[aria-label="搜索会话"]').setValue('已保存')
    await button('刷新会话').trigger('click')
    expect(holder.state.refreshSessions).toHaveBeenCalledOnce()
    expect(holder.state.sessionId.value).toBe('saved')
    expect(holder.state.draft.value).toBe('尚未发送的内容')
    expect((wrapper.get('input[aria-label="搜索会话"]').element as HTMLInputElement).value).toBe('已保存')
    holder.state.sessionsLoading.value = true
    await flushPromises()
    expect(button('刷新会话').attributes('disabled')).toBeDefined()
    expect(wrapper.get('nav[aria-label="历史会话"]').text()).toContain('已保存历史')
  })

  it('首次历史失败不显示新会话欢迎页，提供原地只读恢复入口', async () => {
    await wrapper.get('[aria-label="收起会话导航"]').trigger('click')
    holder.state.sessionId.value = 'existing'
    holder.state.historyReadFailed.value = true
    await flushPromises()
    expect(wrapper.find('.empty-state').exists()).toBe(false)
    expect(wrapper.text()).toContain('会话记录尚未读取成功')
    holder.state.draft.value = '保留的草稿'
    await flushPromises()
    expect(wrapper.get('[aria-label="发送消息"]').attributes('disabled')).toBeDefined()
    const retry = holder.state.reconnect
    await button('重新读取会话记录').trigger('click')
    expect(retry).toHaveBeenCalledOnce()
    expect(holder.state.sessionId.value).toBe('existing')
  })

  it('刷新列表失败保留可点击旧会话，真正成功空列表才展示首次空态', async () => {
    holder.state.sessions.value = [{ session_id: 'saved', title: '已保存历史', message_count: 3 }]
    holder.state.sessionsError.value = '刷新失败'
    await flushPromises()
    expect(wrapper.get('nav[aria-label="历史会话"]').text()).toContain('已有会话仍可查看')
    expect(wrapper.get('nav[aria-label="历史会话"]').text()).toContain('已保存历史')
    holder.state.sessionsError.value = ''
    holder.state.sessions.value = []
    await flushPromises()
    expect(wrapper.text()).toContain('还没有历史会话')
  })

  it('切换对话对象时清除旧搜索，刷新同一对象时保留搜索', async () => {
    holder.state.sessions.value = [{ session_id: 'saved', title: '旧对象会话', message_count: 3 }]
    const search = wrapper.get('input[aria-label="搜索会话"]')
    await search.setValue('旧对象')
    holder.state.sessions.value = [{ session_id: 'other', title: '新对象会话', message_count: 1 }]
    await flushPromises()
    expect((search.element as HTMLInputElement).value).toBe('旧对象')
    expect(wrapper.get('nav[aria-label="历史会话"]').text()).not.toContain('新对象会话')
    holder.state.target.value = 'agent:other'
    await flushPromises()
    expect((search.element as HTMLInputElement).value).toBe('')
    expect(wrapper.get('nav[aria-label="历史会话"]').text()).toContain('新对象会话')
    await search.setValue('新对象')
    holder.state.userId.value = 'another-user'
    await flushPromises()
    expect((search.element as HTMLInputElement).value).toBe('')
  })

  it('刷新失败且搜索无匹配时提示清空筛选，恢复后仍可查看旧会话', async () => {
    holder.state.sessions.value = [{ session_id: 'saved', title: '已保存历史', message_count: 3 }]
    holder.state.sessionsError.value = '刷新失败'
    await wrapper.get('input[aria-label="搜索会话"]').setValue('不存在的标题')
    await flushPromises()
    const history = wrapper.get('nav[aria-label="历史会话"]')
    expect(history.text()).toContain('已有会话仍可查看')
    expect(history.text()).toContain('当前已读取的列表中没有匹配的会话')
    expect(history.text()).not.toContain('已保存历史')
    await wrapper.get('input[aria-label="搜索会话"]').setValue('')
    await flushPromises()
    expect(history.text()).toContain('已保存历史')
    expect(history.text()).not.toContain('没有匹配的会话')
  })
})
