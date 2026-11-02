import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import type { Client } from '../client'
import type { Attachment } from '../protocol'
import ComposerAttachments from './ComposerAttachments.vue'

function attachment(id = 'file', name = 'report.txt'): Attachment {
  return { id, name, input_path: name, size: 20, sha256: '', expires_at: '' }
}
function render(extra: Partial<InstanceType<typeof ComposerAttachments>['$props']> = {}) {
  return mount(ComposerAttachments, {
    attachTo: document.body,
    props: {
      pastedTexts: [],
      attachments: [],
      images: [],
      references: [],
      attachmentFiles: {},
      attachmentTexts: {},
      client: {} as Client,
      session: 's',
      locked: false,
      ...extra
    },
    global: { stubs: { MediaImage: { template: '<span class="test-image">图片</span>' } } }
  })
}

describe('输入框顶部附件卡片', () => {
  it('粘贴附件可点击打开完整原文，HTML保持纯文本，恢复和关闭操作独立', async () => {
    const text = '  第一行\r\n<script>alert("x")</script>\n' + '内容'.repeat(1100)
    const wrapper = render({
      pastedTexts: [
        { id: 'paste', name: '粘贴文本.txt', text, size: new TextEncoder().encode(text).length }
      ]
    })
    try {
      expect(wrapper.find('button button').exists()).toBe(false)
      await wrapper.get('button[aria-label="预览 粘贴文本.txt"]').trigger('click')
      await flushPromises()
      expect(wrapper.get('pre').element.textContent).toBe(text)
      expect(wrapper.find('script').exists()).toBe(false)
      expect(wrapper.get('dialog').attributes('open')).toBeDefined()
      await wrapper.get('.attachment-preview footer button').trigger('click')
      await flushPromises()
      expect(wrapper.emitted('restore')).toEqual([['paste', false]])
      expect(wrapper.find('dialog').exists()).toBe(false)
    } finally {
      wrapper.unmount()
    }
  })

  it('普通上传附件只预览，不显示粘贴还原；粘贴来源上传后仍可还原', async () => {
    const file = Object.assign(new File(['正文'], 'report.txt'), {
      text: vi.fn(async () => '正文')
    })
    const wrapper = render({ attachments: [attachment()], attachmentFiles: { file } })
    try {
      expect(wrapper.find('.draft-file-restore').exists()).toBe(false)
      await wrapper.get('.draft-file-open').trigger('click')
      await flushPromises()
      expect(wrapper.get('pre').text()).toBe('正文')
      expect(wrapper.get('footer').text()).toBe('关闭')
      await wrapper.setProps({ attachmentTexts: { file: '粘贴的原文' } })
      expect(wrapper.get('.draft-file-restore').text()).toBe('粘贴原文至输入框')
      await wrapper.get('.draft-file-restore').trigger('click')
      await flushPromises()
      expect(wrapper.emitted('restore')).toEqual([['file', true]])
    } finally {
      wrapper.unmount()
    }
  })

  it.each(['pdf', 'docx', 'xlsx', 'pptx'])('已上传的 %s 不按文本解码，仍保留附件', async (ext) => {
    const file = Object.assign(new File(['binary'], 'report.' + ext), {
      text: vi.fn(async () => '乱码')
    })
    const wrapper = render({
      attachments: [attachment('file', file.name)],
      attachmentFiles: { file }
    })
    try {
      await wrapper.get('.draft-file-open').trigger('click')
      await flushPromises()
      expect(file.text).not.toHaveBeenCalled()
      expect(wrapper.text()).toContain('当前格式暂不支持文本预览；附件已添加，将随消息发送')
      expect(wrapper.find('pre').exists()).toBe(false)
      expect(wrapper.emitted('removeAttachment')).toBeUndefined()
    } finally {
      wrapper.unmount()
    }
  })

  it('关闭、删除或切换会话后，迟到的本地文件读取不能重开预览', async () => {
    let finish!: (text: string) => void
    const file = Object.assign(new File(['正文'], 'report.txt'), {
      text: () =>
        new Promise<string>((resolve) => {
          finish = resolve
        })
    })
    const wrapper = render({ attachments: [attachment()], attachmentFiles: { file } })
    try {
      await wrapper.get('.draft-file-open').trigger('click')
      expect(wrapper.text()).toContain('正在读取原文')
      await wrapper.setProps({ session: 'another-session' })
      finish('旧会话原文')
      await flushPromises()
      expect(wrapper.find('dialog').exists()).toBe(false)
      await wrapper.get('.draft-file-open').trigger('click')
      await wrapper.setProps({ attachments: [] })
      finish('删除的原文')
      await flushPromises()
      expect(wrapper.find('dialog').exists()).toBe(false)
    } finally {
      wrapper.unmount()
    }
  })

  it('图片排在文件前方，长文件名有完整可访问名称；忙碌时禁止移除与还原', async () => {
    const name = 'very-long-file-name-'.repeat(10) + '.txt'
    const wrapper = render({
      pastedTexts: [{ id: 'p', name, text: '原文', size: 6 }],
      images: [
        { media_id: 'm', mime_type: 'image/png', byte_length: 100, width: 100, height: 100 }
      ],
      locked: true
    })
    try {
      expect(wrapper.get('.draft-resources').element.firstElementChild?.className).toBe(
        'draft-image'
      )
      expect(wrapper.get('.draft-file-open').attributes('aria-label')).toBe(`预览 ${name}`)
      expect(wrapper.get('.draft-file-open').attributes('title')).toBe(name)
      expect(wrapper.get('.draft-file-restore').attributes('disabled')).toBeDefined()
      expect(
        wrapper
          .findAll('.draft-remove')
          .every((button) => button.attributes('disabled') !== undefined)
      ).toBe(true)
      await wrapper.get('.draft-remove').trigger('click')
      expect(wrapper.emitted('removeImage')).toBeUndefined()
    } finally {
      wrapper.unmount()
    }
  })
})
