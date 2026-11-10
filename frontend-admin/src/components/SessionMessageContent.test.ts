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
  ElTag: { template: '<span><slot /></span>' }
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
