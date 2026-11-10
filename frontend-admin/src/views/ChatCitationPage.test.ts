import { afterEach, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { ElSelect } from 'element-plus'
import ChatPage from './ChatPage.vue'
vi.mock('../stores/auth', () => ({
  useAuthStore: () => ({
    identityVersion: 0,
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

const mocks = vi.hoisted(() => ({ get: vi.fn(), history: vi.fn(), detail: vi.fn() }))
vi.mock('../api', () => ({
  get: mocks.get,
  api: {
    chat: {
      endUsers: async () => [],
      sessions: async () => [{ session_id: 'A' }, { session_id: 'B' }],
      sessionDetail: mocks.detail,
      history: mocks.history,
      observeSession: async () => {},
      goals: async () => ({ goal: null }),
      artifacts: async () => [],
      collaboration: async () => ({})
    },
    agents: { list: async () => ({ items: [] }) },
    teams: { list: async () => [] }
  }
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
afterEach(() => {
  document.body.innerHTML = ''
  vi.clearAllMocks()
})

it('消息依据使用当前会话与消息身份，切换会话清除已显示的片段', async () => {
  mocks.detail.mockImplementation(async (id: string) => ({
    session: { session_id: id },
    end_user_id: '1',
    target_agent_id: '2',
    closed: false
  }))
  mocks.history.mockImplementation(async (id: string) => ({
    session_id: id,
    messages: [
      {
        message_id: `msg-${id}`,
        role: 'assistant',
        content: '回答',
        metadata: {
          citations: [
            {
              ordinal: 1,
              item_id: 'item',
              source_id: 'source',
              source_version: 'v1',
              citation_id: 'k1234567890abcdef',
              label: '知识依据 1'
            }
          ]
        }
      }
    ],
    pending_interactions: [],
    has_more: false
  }))
  mocks.get.mockResolvedValue({ label: '知识依据', source_version: 'v1', text: '原始片段' })
  const wrapper = mount(ChatPage, { attachTo: document.body })
  await flushPromises()
  const sessions = wrapper
    .findAllComponents(ElSelect)
    .find((field) => field.props('placeholder') === '历史会话')!
  sessions.vm.$emit('change', 'A')
  await flushPromises()
  const button = wrapper.findAll('button').find((item) => item.text() === '知识依据 1')!
  await button.trigger('click')
  await flushPromises()
  expect(mocks.get.mock.calls[0]![0]).toBe(
    '/chat/citations/content?session_id=A&message_id=msg-A&citation_id=k1234567890abcdef'
  )
  expect(document.body.textContent).toContain('原始片段')
  sessions.vm.$emit('change', 'B')
  await flushPromises()
  expect(document.body.textContent).not.toContain('原始片段')
  wrapper.unmount()
})
