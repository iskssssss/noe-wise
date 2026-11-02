import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ElementPlus, { ElTabs, ElTreeV2 } from 'element-plus'
import { h, nextTick } from 'vue'
import InstitutionWorkspace from './InstitutionWorkspace.vue'
import AdminPage from '../admin/AdminPage.vue'

const active: VueWrapper[] = []
const observations: Array<{ element: Element; callback: ResizeObserverCallback }> = []
let width = 1200

beforeEach(() => {
  width = 1200
  observations.splice(0)
  vi.stubGlobal(
    'ResizeObserver',
    class {
      constructor(private callback: ResizeObserverCallback) {}
      observe(element: Element) {
        observations.push({ element, callback: this.callback })
        if (element.classList.contains('institution-workspace'))
          this.callback(
            [{ contentRect: { width }, target: element } as ResizeObserverEntry],
            this as unknown as ResizeObserver
          )
      }
      unobserve() {}
      disconnect() {}
    }
  )
})
afterEach(() => {
  active.splice(0).forEach((wrapper) => wrapper.unmount())
  document.body.innerHTML = ''
  vi.unstubAllGlobals()
})

async function render(overrides = {}) {
  const wrapper = mount(InstitutionWorkspace, {
    props: {
      nodes: [
        { id: '1', label: '机构甲', status: 'active' },
        { id: '2', label: '机构乙', status: 'active' }
      ],
      selectedId: '1',
      actorLabel: '平台管理员',
      sections: [
        { key: 'overview', label: '概览' },
        { key: 'usage', label: '用量' }
      ],
      section: 'overview',
      ...overrides
    },
    slots: { default: () => h(AdminPage, { title: '机构设置' }, () => h('p', '维护内容')) },
    attachTo: document.body,
    global: { plugins: [ElementPlus] }
  })
  active.push(wrapper)
  await flushPromises()
  return wrapper
}

describe('机构工作台展示框架', () => {
  it('保持登录操作者与维护目标分别可见，内嵌领域面板只使用h2', async () => {
    const wrapper = await render()
    expect(wrapper.text()).toContain('当前操作身份：平台管理员')
    expect(wrapper.get('.institution-workspace__target').text()).toContain('机构甲')
    expect(wrapper.findAll('h1')).toHaveLength(1)
    expect(wrapper.get('.admin-page h2').text()).toBe('机构设置')
    expect(wrapper.find('.admin-page--embedded').exists()).toBe(true)
  })

  it('点击机构只发意图，父级未接受时目标和树高亮仍属于原机构', async () => {
    const wrapper = await render()
    await wrapper.get('[data-institution-id="2"]').trigger('click')
    await flushPromises()
    expect(wrapper.emitted('select')).toEqual([['2']])
    expect(wrapper.get('[data-institution-id="1"]').attributes('aria-current')).toBe('true')
    expect(wrapper.get('[data-institution-id="2"]').attributes('aria-current')).toBeUndefined()
    expect(wrapper.findComponent(ElTreeV2).vm.getCurrentKey()).toBe('1')
    expect(wrapper.get('.institution-workspace__target').text()).toContain('机构甲')
    await wrapper.setProps({ selectedId: '2' })
    await flushPromises()
    expect(wrapper.get('[data-institution-id="2"]').attributes('aria-current')).toBe('true')
  })

  it('分区切换由父级接受后生效，拒绝导航不先改变当前高亮', async () => {
    const wrapper = await render()
    const tabs = wrapper.findComponent(ElTabs)
    await wrapper.get('#tab-usage').trigger('click')
    await flushPromises()
    expect(wrapper.emitted('section')).toEqual([['usage']])
    expect(wrapper.get('#tab-overview').attributes('aria-selected')).toBe('true')
    expect(tabs.props('modelValue')).toBe('overview')
    await wrapper.setProps({ section: 'usage' })
    await flushPromises()
    expect(wrapper.get('#tab-usage').attributes('aria-selected')).toBe('true')
    expect(wrapper.emitted('section')).toHaveLength(1)
  })

  it.each(['onboarding', 'invalid-section'])(
    '非页签分区%s仍展示调用方流程或拒绝说明，不假选其他维护页',
    async (section) => {
      const wrapper = await render({ section })
      expect(wrapper.get('.institution-workspace__content').text()).toContain('维护内容')
      expect(wrapper.get('.institution-workspace__content .admin-page h2').text()).toBe('机构设置')
      expect(wrapper.findAll('.el-tabs__item[aria-selected="true"]')).toHaveLength(0)
      expect(wrapper.emitted('section')).toBeUndefined()
    }
  )

  it('刷新失败保留已有目录，不完整目录、空结果与错误分别表达', async () => {
    const wrapper = await render({
      error: '目录读取失败',
      directoryComplete: false,
      loadedCount: 2,
      total: 40
    })
    expect(wrapper.text()).toContain('已读取 2 / 40 个机构')
    expect(document.querySelector('.el-message--error')?.textContent).toContain('目录读取失败')
    expect(wrapper.findAll('[data-institution-id]')).toHaveLength(2)
    await wrapper.get('input[aria-label="搜索机构名称或标识"]').setValue('不存在')
    await flushPromises()
    expect(wrapper.findAll('[data-institution-id]')).toHaveLength(0)
    await wrapper.setProps({ error: '', directoryComplete: true })
    await flushPromises()
    expect(wrapper.text()).toContain('没有匹配的机构')
    expect(wrapper.text()).toContain('匹配 0 个机构')
  })

  it('窄屏目录关闭时仍提示读取失败，打开目录不会重复提示', async () => {
    width = 700
    const wrapper = await render({ error: '目录读取失败' })
    expect(document.querySelectorAll('.el-message--error')).toHaveLength(1)
    expect(document.querySelector('.el-message--error')?.textContent).toContain('目录读取失败')
    await wrapper.get('button[aria-expanded="false"]').trigger('click')
    await flushPromises()
    expect(document.querySelectorAll('.el-message--error')).toHaveLength(1)
  })

  it('虚拟树保留千级数据，按后部名称搜索定位且不渲染全部节点', async () => {
    const nodes = Array.from({ length: 1200 }, (_, index) => ({
      id: `机构-${index}`,
      label: `测试机构 ${index}`
    }))
    const wrapper = await render({ nodes, selectedId: '机构-0' })
    expect(wrapper.findComponent(ElTreeV2).props('data')).toHaveLength(1200)
    expect(wrapper.findAll('[data-institution-id]').length).toBeLessThan(100)
    await wrapper.get('input[aria-label="搜索机构名称或标识"]').setValue('测试机构 1199')
    await flushPromises()
    expect(wrapper.get('[data-institution-id="机构-1199"]').text()).toBe('测试机构 1199')
  })

  it('树名称按钮支持方向键移动焦点，焦点移动不改变维护目标', async () => {
    const wrapper = await render()
    const first = wrapper.get<HTMLButtonElement>('[data-institution-id="1"]')
    first.element.focus()
    await first.trigger('keydown', { key: 'ArrowDown' })
    await nextTick()
    expect(document.activeElement).toBe(wrapper.get('[data-institution-id="2"]').element)
    expect(wrapper.get('[data-institution-id="1"]').attributes('aria-current')).toBe('true')
    await wrapper.get('[data-institution-id="2"]').trigger('click')
    expect(wrapper.emitted('select')).toEqual([['2']])
  })

  it('窄工作区使用机构抽屉，未接受选择保持打开，接受后关闭', async () => {
    width = 700
    const wrapper = await render()
    expect(wrapper.classes()).toContain('institution-workspace--narrow')
    expect(wrapper.find('aside').exists()).toBe(false)
    const toggle = wrapper.get<HTMLButtonElement>('button[aria-expanded]')
    toggle.element.focus()
    await toggle.trigger('click')
    await flushPromises()
    expect(toggle.attributes('aria-expanded')).toBe('true')
    document.querySelector<HTMLButtonElement>('.el-drawer [data-institution-id="2"]')!.click()
    await flushPromises()
    expect(wrapper.emitted('select')).toEqual([['2']])
    expect(toggle.attributes('aria-expanded')).toBe('true')
    await wrapper.setProps({ selectedId: '2' })
    await flushPromises()
    expect(toggle.attributes('aria-expanded')).toBe('false')
  })

  it('窄窗口重新选择当前机构直接收起目录，搜索Esc先清空关键词', async () => {
    width = 700
    const wrapper = await render()
    const toggle = wrapper.get<HTMLButtonElement>('button[aria-expanded]')
    await toggle.trigger('click')
    await flushPromises()
    const input = document.querySelector<HTMLInputElement>(
      '.el-drawer input[aria-label="搜索机构名称或标识"]'
    )!
    input.value = '机构甲'
    input.dispatchEvent(new Event('input', { bubbles: true }))
    await flushPromises()
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    await flushPromises()
    expect(input.value).toBe('')
    expect(toggle.attributes('aria-expanded')).toBe('true')
    document.querySelector<HTMLButtonElement>('.el-drawer [data-institution-id="1"]')!.click()
    await flushPromises()
    expect(toggle.attributes('aria-expanded')).toBe('false')
  })
})
