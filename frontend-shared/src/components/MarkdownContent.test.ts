import { expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import MarkdownContent from './MarkdownContent.vue'

it('后台Markdown净化脚本、事件、危险链接和srcdoc嵌套HTML', () => {
  const wrapper = mount(MarkdownContent, {
    props: {
      content:
        '<script>alert(1)</script><img src="x" onerror="alert(1)"><a href="javascript:alert(1)" onclick="alert(1)">说明</a><iframe srcdoc="<img src=x onerror=alert(1)>"></iframe><p style="position:fixed">正文</p>'
    }
  })
  try {
    expect(wrapper.find('script, iframe, form, input').exists()).toBe(false)
    expect(wrapper.find('[onerror], [onclick], [srcdoc], [style]').exists()).toBe(false)
    expect(wrapper.get('a').attributes('href')).toBeUndefined()
    expect(wrapper.text()).toContain('正文')
  } finally {
    wrapper.unmount()
  }
})

it('安全正文保留标题、表格、代码和普通链接', () => {
  const wrapper = mount(MarkdownContent, {
    props: {
      content:
        '# 操作说明 😀\n\n| 项目 | 结果 |\n| --- | --- |\n| 检查 | 通过 |\n\n`<script>` [文档](https://example.com/docs)'
    }
  })
  try {
    expect(wrapper.get('h1').text()).toBe('操作说明 😀')
    expect(wrapper.get('table').text()).toContain('通过')
    expect(wrapper.get('code').text()).toBe('<script>')
    expect(wrapper.get('a').attributes('href')).toBe('https://example.com/docs')
    expect(wrapper.find('script').exists()).toBe(false)
  } finally {
    wrapper.unmount()
  }
})
