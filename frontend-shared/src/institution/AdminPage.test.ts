import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import AdminPage from '../admin/AdminPage.vue'
import { institutionWorkspaceEmbeddedKey } from './workspace'

describe('工作台领域页嵌入', () => {
  it('独立页保留h1，显式或展示上下文启用嵌入模式，false可覆盖', () => {
    const standalone = mount(AdminPage, { props: { title: '独立页面' } })
    expect(standalone.get('h1').text()).toBe('独立页面')
    standalone.unmount()
    const inherited = mount(AdminPage, {
      props: { title: '领域面板' },
      global: { provide: { [institutionWorkspaceEmbeddedKey as symbol]: true } }
    })
    expect(inherited.get('h2').text()).toBe('领域面板')
    inherited.unmount()
    const explicit = mount(AdminPage, {
      props: { title: '显式独立', embedded: false },
      global: { provide: { [institutionWorkspaceEmbeddedKey as symbol]: true } }
    })
    expect(explicit.get('h1').text()).toBe('显式独立')
    explicit.unmount()
  })
})
