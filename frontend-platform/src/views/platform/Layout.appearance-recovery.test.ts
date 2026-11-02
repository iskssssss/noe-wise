import { afterEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { defineComponent, onMounted, ref } from 'vue'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import Layout from './Layout.vue'
import { clearFeedback } from '@skillforge/shared/feedback'

const requests = vi.hoisted(() => ({ foundation: vi.fn() }))
vi.mock('../../api', () => ({ foundationRequest: requests.foundation }))
vi.mock('../../stores/auth', () => ({
  useAuthStore: () => ({
    user: {
      sub: 'operator',
      username: '管理员',
      session_id: 'session',
      authorization_version: 1,
      permissions: ['foundation.preferences.manage', 'foundation.settings.read']
    },
    isAuthenticated: true,
    logout: vi.fn()
  })
}))
vi.mock('@skillforge/shared/admin', () => ({
  AdminLayout: { template: '<section><slot /></section>' }
}))
vi.mock('@skillforge/shared/foundation/navigation', async () => {
  const { ref } = await import('vue')
  return {
    useFoundationNavigation: () => ({ menus: ref([]), error: ref(''), reload: vi.fn() })
  }
})

let wrapper: VueWrapper | undefined
afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  requests.foundation.mockReset()
  clearFeedback()
  document.body.innerHTML = ''
})

it('展示设置失败后重新读取期间保留当前编辑页面及未保存正文', async () => {
  let preferenceReads = 0
  let completeRead!: (value: object) => void
  const pendingRead = new Promise<object>((resolve) => {
    completeRead = resolve
  })
  requests.foundation.mockImplementation((_method: string, path: string) => {
    if (path.endsWith('/preferences')) {
      if (++preferenceReads === 1) return Promise.reject(new Error('展示设置暂时不可用'))
      return pendingRead
    }
    return Promise.resolve({
      display_name: '机构',
      logo_url: null,
      primary_color: '#2563eb',
      revision: 1
    })
  })
  let mounts = 0
  const Editor = defineComponent({
    setup() {
      const draft = ref('')
      onMounted(() => mounts++)
      return { draft }
    },
    template: '<input aria-label="未保存正文" v-model="draft" />'
  })
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/', component: Layout, children: [{ path: 'edit', component: Editor }] }]
  })
  await router.push('/edit')
  await router.isReady()
  wrapper = mount(RouterView, { attachTo: document.body, global: { plugins: [router] } })
  await flushPromises()
  const editor = wrapper.getComponent(Editor).vm.$.uid
  await wrapper.get('input[aria-label="未保存正文"]').setValue('保留我的草稿')
  const retry = Array.from(document.body.querySelectorAll('button')).find(
    (button) => button.textContent?.trim() === '重新读取展示设置'
  )!
  expect(retry).toBeDefined()
  retry.click()
  await flushPromises()
  expect(wrapper.getComponent(Editor).vm.$.uid).toBe(editor)
  expect((wrapper.get('input[aria-label="未保存正文"]').element as HTMLInputElement).value).toBe(
    '保留我的草稿'
  )
  expect(mounts).toBe(1)
  completeRead({
    theme: 'light',
    locale: 'zh-CN',
    page_size: 20,
    compact_navigation: false,
    revision: 1
  })
  await flushPromises()
  expect(wrapper.getComponent(Editor).vm.$.uid).toBe(editor)
  expect((wrapper.get('input[aria-label="未保存正文"]').element as HTMLInputElement).value).toBe(
    '保留我的草稿'
  )
  expect(mounts).toBe(1)
})
