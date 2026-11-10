import { expect, it } from 'vitest'
import { shallowMount } from '@vue/test-utils'
import type { ChatHistoryMessage } from '../api'
import SessionMessageContent from './SessionMessageContent.vue'
import SessionMediaImage from './SessionMediaImage.vue'

const message: ChatHistoryMessage = {
  message_id: 'input-1',
  message_version: 1,
  role: 'user',
  content: '纯文字正文',
  created_at: '2026-11-10T00:00:00Z',
  references: [],
  metadata: {}
}
const stubs = {
  MarkdownContent: { props: ['content'], template: '<p>{{ content }}</p>' },
  ElTag: { template: '<span><slot /></span>' },
  ElButton: { template: '<button><slot /></button>' }
}

it('按持久顺序显示文字和图片，使用当前会话读取媒体并显示附件名', async () => {
  const wrapper = shallowMount(SessionMessageContent, {
    props: {
      session: 'session-1',
      message: {
        ...message,
        metadata: {
          content_parts: [
            { type: 'text', text: '图前说明' },
            { type: 'image', media_id: 'image-1' },
            { type: 'text', text: '图后说明' }
          ],
          attachments: [{ id: 'file-1', name: '说明.txt' }]
        }
      }
    },
    global: { stubs }
  })
  expect(wrapper.findAll('p, session-media-image-stub').map((part) => part.text())).toEqual([
    '图前说明',
    '',
    '图后说明'
  ])
  expect(wrapper.text()).not.toContain(message.content)
  expect(wrapper.findComponent(SessionMediaImage).props()).toMatchObject({
    session: 'session-1',
    media: 'image-1'
  })
  expect(wrapper.get('[aria-label="已发送附件"]').text()).toBe('说明.txt')
  await wrapper.setProps({ session: 'session-2' })
  expect(wrapper.findComponent(SessionMediaImage).props('session')).toBe('session-2')
  wrapper.unmount()
})

it('无结构化内容时显示正文，只有附件身份时保留附件标记', () => {
  const wrapper = shallowMount(SessionMessageContent, {
    props: {
      session: 'session-1',
      message: { ...message, metadata: { attachment_ids: ['file-1', 'file-2'] } }
    },
    global: { stubs }
  })
  expect(wrapper.get('p').text()).toBe(message.content)
  expect(wrapper.get('[aria-label="已发送附件"]').text()).toBe('附件 1附件 2')
  expect(wrapper.findComponent(SessionMediaImage).exists()).toBe(false)
  wrapper.unmount()
})

it('智能体回复保持公开正文，不将其他元数据解释成用户图片或附件', () => {
  const wrapper = shallowMount(SessionMessageContent, {
    props: {
      session: 'session-1',
      message: {
        ...message,
        role: 'assistant',
        metadata: {
          content_parts: [{ type: 'image', media_id: 'image-1' }],
          attachments: [{ id: 'file-1', name: '说明.txt' }]
        }
      }
    },
    global: { stubs }
  })
  expect(wrapper.text()).toBe(message.content)
  expect(wrapper.findComponent(SessionMediaImage).exists()).toBe(false)
  expect(wrapper.find('[aria-label="已发送附件"]').exists()).toBe(false)
  wrapper.unmount()
})

it('显示知识依据锚点并以当前会话和消息身份选择引用', async () => {
  const citedMessage: ChatHistoryMessage = {
    ...message,
    message_id: 'reply-1',
    role: 'assistant',
    metadata: {
      citations: [
        {
          ordinal: 1,
          item_id: 'item-1',
          source_id: 'source-1',
          source_version: 'version-1',
          citation_id: 'k1234567890abcdef',
          label: '知识依据 1'
        }
      ]
    }
  }
  const wrapper = shallowMount(SessionMessageContent, {
    props: { session: 'session-1', message: citedMessage },
    global: { stubs }
  })
  const list = wrapper.get('[aria-label="知识依据"]')
  expect(list.get('li').attributes()).toMatchObject({
    id: 'citation-k1234567890abcdef',
    tabindex: '-1'
  })
  expect(list.get('button').text()).toBe('知识依据 1')
  await list.get('button').trigger('click')
  await wrapper.setProps({
    session: 'session-2',
    message: { ...citedMessage, message_id: 'reply-2' }
  })
  await list.get('button').trigger('click')
  expect(wrapper.emitted('citation')).toEqual([
    [{ session: 'session-1', message: 'reply-1', citation: 'k1234567890abcdef' }],
    [{ session: 'session-2', message: 'reply-2', citation: 'k1234567890abcdef' }]
  ])
  wrapper.unmount()
})

it('点击正文中本消息的知识依据直接选择来源并阻止锚点导航', () => {
  const wrapper = shallowMount(SessionMessageContent, {
    props: {
      session: 'session-1',
      message: {
        ...message,
        message_id: 'reply-1',
        role: 'assistant',
        content: '回答见 [**知识依据 1**](#citation-k1234567890abcdef)',
        metadata: {
          citations: [
            {
              ordinal: 1,
              item_id: 'item-1',
              source_id: 'source-1',
              source_version: 'version-1',
              citation_id: 'k1234567890abcdef',
              label: '知识依据 1'
            }
          ]
        }
      }
    },
    global: { stubs: { ...stubs, MarkdownContent: false } }
  })
  const click = new MouseEvent('click', { bubbles: true, cancelable: true })
  wrapper.get('.markdown-content a strong').element.dispatchEvent(click)
  expect(click.defaultPrevented).toBe(true)
  expect(wrapper.emitted('citation')).toEqual([
    [{ session: 'session-1', message: 'reply-1', citation: 'k1234567890abcdef' }]
  ])
  wrapper.unmount()
})

it.each(['#citation-k0000000000000000', 'https://example.com/#citation-k1234567890abcdef'])(
  '正文链接 %s 未匹配本消息引用时保留默认行为',
  (href) => {
    const wrapper = shallowMount(SessionMessageContent, {
      props: {
        session: 'session-1',
        message: {
          ...message,
          role: 'assistant',
          content: `[查看材料](${href})`,
          metadata: {
            citations: [
              {
                ordinal: 1,
                item_id: 'item-1',
                source_id: 'source-1',
                source_version: 'version-1',
                citation_id: 'k1234567890abcdef',
                label: '知识依据 1'
              }
            ]
          }
        }
      },
      global: { stubs: { ...stubs, MarkdownContent: false } }
    })
    const click = new Event('click', { bubbles: true, cancelable: true })
    wrapper.get('.markdown-content a').element.dispatchEvent(click)
    expect(click.defaultPrevented).toBe(false)
    expect(wrapper.emitted('citation')).toBeUndefined()
    wrapper.unmount()
  }
)
