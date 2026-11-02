import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import Markdown from './Markdown.vue'

beforeEach(() => {
  vi.stubGlobal(
    'matchMedia',
    vi.fn(() => ({
      matches: true,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn()
    }))
  )
})
afterEach(() => vi.unstubAllGlobals())

describe('会话资料链接保留当前页面', () => {
  it('普通链接、跨块引用链接和原始HTML资料链接均在新标签页打开', () => {
    const wrapper = mount(Markdown, {
      props: {
        text: [
          '[资料](https://example.com/guide?q=source)',
          '',
          '[参考][manual]',
          '',
          '<a href="/documents/guide" target="_self" rel="opener">站内资料</a>',
          '',
          '[manual]: https://example.org/manual'
        ].join('\n')
      }
    })
    try {
      const links = wrapper.findAll('a')
      expect(links).toHaveLength(3)
      expect(links.map((link) => link.attributes('href'))).toEqual([
        'https://example.com/guide?q=source',
        'https://example.org/manual',
        '/documents/guide'
      ])
      for (const link of links) {
        expect(link.attributes('target')).toBe('_blank')
        expect(link.attributes('rel')).toBe('noopener noreferrer')
      }
    } finally {
      wrapper.unmount()
    }
  })

  it('知识依据页内锚点继续本页定位，资料链接策略不改变正文代码', () => {
    const wrapper = mount(Markdown, {
      props: {
        text: '[依据](#citation-k0123456789abcdef)\n\n`[代码示例](https://example.com)`'
      }
    })
    try {
      const link = wrapper.get('a')
      expect(link.attributes('href')).toBe('#citation-k0123456789abcdef')
      expect(link.attributes('target')).toBeUndefined()
      expect(link.attributes('rel')).toBeUndefined()
      expect(wrapper.get('code').text()).toBe('[代码示例](https://example.com)')
    } finally {
      wrapper.unmount()
    }
  })

  it('原始HTML危险地址及事件仍在入口净化，安全链接才获得页面保留属性', () => {
    const wrapper = mount(Markdown, {
      props: {
        text: '<a href="javascript:alert(1)" onclick="alert(1)">危险地址</a> <a href="https://example.com" onclick="alert(1)">资料</a>'
      }
    })
    try {
      const links = wrapper.findAll('a')
      expect(links[0]!.attributes('href')).toBeUndefined()
      expect(links[0]!.attributes('onclick')).toBeUndefined()
      expect(links[1]!.attributes('onclick')).toBeUndefined()
      expect(links[1]!.attributes('target')).toBe('_blank')
      expect(wrapper.html()).not.toContain('javascript:')
    } finally {
      wrapper.unmount()
    }
  })
})
