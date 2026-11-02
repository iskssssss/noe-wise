import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { defineComponent, nextTick, onUnmounted, ref } from 'vue'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import type { AuthUser } from './stores/auth'
const state = vi.hoisted(() => ({
  token: 'token-71' as string | null,
  session: 'browser-71' as string | null,
  current: null as AuthUser | null,
  unmounted: 0
}))
vi.mock('./api', () => ({
  api: {
    auth: { me: vi.fn(async () => state.current), logout: vi.fn(async () => ({ status: 'ok' })) }
  },
  getAccessToken: () => state.token,
  getSessionId: () => state.session,
  authSessionKey: 'skillforge.access_token.session',
  saveTokens: (tokens: { access_token: string; session_id: string }) => {
    state.token = tokens.access_token
    state.session = 'browser-' + tokens.session_id
  },
  clearTokens: () => {
    state.token = null
    state.session = null
  }
}))
import App from './App.vue'
import { api } from './api'
import { useAuthStore } from './stores/auth'
let wrapper: VueWrapper | undefined
let auth: ReturnType<typeof useAuthStore>
let router: Router
const baseUser = (): AuthUser => ({
  sub: '42',
  tenant_id: '7',
  username: 'member',
  roles: ['administrator'],
  permissions: ['foundation.access.manage'],
  authorization_version: 1,
  mfa_state: 'full',
  session_id: '71'
})
const Editor = defineComponent({
  setup() {
    const draft = ref('')
    onUnmounted(() => state.unmounted++)
    return { draft }
  },
  template: '<input data-test="draft" v-model="draft" />'
})
async function settle() {
  await nextTick()
  await flushPromises()
  await nextTick()
  await flushPromises()
}
beforeEach(async () => {
  localStorage.clear()
  state.token = 'token-71'
  state.session = 'browser-71'
  state.current = baseUser()
  state.unmounted = 0
  const pinia = createPinia()
  setActivePinia(pinia)
  auth = useAuthStore(pinia)
  await auth.fetchMe()
  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/admin/foundation/access', component: Editor, meta: { requiresAuth: true } },
      { path: '/admin/foundation/catalog', component: Editor, meta: { requiresAuth: true } },
      {
        path: '/admin/home',
        component: { template: '<p data-test="home">home</p>' },
        meta: { requiresAuth: true }
      },
      { path: '/login', component: { template: '<p data-test="login">login</p>' } }
    ]
  })
  // 与生产守卫相同，受保护导航先读取当前权限；App 不能用版本更新覆盖这次导航。
  router.beforeEach(async (to) => {
    if (!to.meta.requiresAuth) return
    if (!auth.accessToken) return '/login'
    try {
      await auth.fetchMe()
    } catch {
      return '/login'
    }
    if (!auth.isAuthenticated) return '/login'
  })
  await router.push('/admin/foundation/access?tab=editor')
  await router.isReady()
  wrapper = mount(App, { global: { plugins: [pinia, router] } })
  await settle()
})
afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  auth?.$dispose()
  localStorage.clear()
})
describe('App 的授权刷新和登录会话边界', () => {
  it('守卫刷新权限版本清理旧草稿，并保留已经检查的目标导航', async () => {
    await wrapper!.get('[data-test="draft"]').setValue('旧授权下的草稿')
    const version = auth.identityVersion
    state.current = { ...baseUser(), authorization_version: 2 }
    await router.push('/admin/foundation/catalog?mode=dictionary')
    await settle()
    expect(router.currentRoute.value.fullPath).toBe('/admin/foundation/catalog?mode=dictionary')
    expect(auth.identityVersion).toBe(version + 1)
    expect(state.unmounted).toBeGreaterThan(0)
    expect((wrapper!.get('[data-test="draft"]').element as HTMLInputElement).value).toBe('')
  })
  it('真正换号先清除旧草稿，然后以新用户通过首页守卫', async () => {
    await wrapper!.get('[data-test="draft"]').setValue('旧账号私有草稿')
    state.current = { ...baseUser(), sub: '43', username: 'other', session_id: '72' }
    auth.applyTokens({
      access_token: 'token-72',
      token_type: 'Bearer',
      expires_in: 3600,
      ...state.current
    })
    await auth.fetchMe()
    await settle()
    expect(auth.user?.sub).toBe('43')
    expect(router.currentRoute.value.path).toBe('/admin/home')
    expect(state.unmounted).toBeGreaterThan(0)
    expect(wrapper!.find('[data-test="draft"]').exists()).toBe(false)
  })
  it('同账号的新安全会话也废弃旧编辑器，并重新导航', async () => {
    await wrapper!.get('[data-test="draft"]').setValue('旧会话草稿')
    const version = auth.identityVersion
    state.current = { ...baseUser(), session_id: '72' }
    await auth.fetchMe()
    await settle()
    expect(auth.identityVersion).toBe(version + 1)
    expect(router.currentRoute.value.path).toBe('/admin/home')
    expect(state.unmounted).toBeGreaterThan(0)
  })
  it('失效后同步丢弃身份与草稿，并进入登录页', async () => {
    await wrapper!.get('[data-test="draft"]').setValue('失效会话草稿')
    auth.logout(false)
    await settle()
    expect(auth.user).toBeNull()
    expect(auth.isAuthenticated).toBe(false)
    expect(router.currentRoute.value.path).toBe('/login')
    expect(wrapper!.find('[data-test="draft"]').exists()).toBe(false)
    expect(state.unmounted).toBeGreaterThan(0)
  })
  it('同一会话的跨标签令牌刷新保留导航和编辑器', async () => {
    await wrapper!.get('[data-test="draft"]').setValue('当前会话草稿')
    const version = auth.identityVersion
    state.token = 'rotated-token'
    window.dispatchEvent(
      new StorageEvent('storage', { key: 'skillforge.access_token', storageArea: localStorage })
    )
    await settle()
    expect(router.currentRoute.value.fullPath).toBe('/admin/foundation/access?tab=editor')
    expect(auth.identityVersion).toBe(version)
    expect((wrapper!.get('[data-test="draft"]').element as HTMLInputElement).value).toBe(
      '当前会话草稿'
    )
  })
  it('同账号跨标签换会话先清身份，后续令牌事件不能复活旧缓存', async () => {
    await wrapper!.get('[data-test="draft"]').setValue('旧会话草稿')
    state.session = 'browser-72'
    state.token = 'token-72'
    window.dispatchEvent(
      new StorageEvent('storage', {
        key: 'skillforge.access_token.session',
        storageArea: localStorage
      })
    )
    window.dispatchEvent(
      new StorageEvent('storage', { key: 'skillforge.access_token', storageArea: localStorage })
    )
    expect(auth.user).toBeNull()
    await settle()
    expect(router.currentRoute.value.path).toBe('/login')
    expect(wrapper!.find('[data-test="draft"]').exists()).toBe(false)
    expect(state.unmounted).toBeGreaterThan(0)
  })
  it('换会话导航尚未完成时退出，旧回调不能覆盖登录页和清理状态', async () => {
    await wrapper!.get('[data-test="draft"]').setValue('旧会话私有草稿')
    const next = { ...baseUser(), session_id: '72' }
    let finish!: (value: AuthUser) => void
    let entered!: () => void
    const started = new Promise<void>((resolve) => {
      entered = resolve
    })
    vi.mocked(api.auth.me)
      .mockResolvedValueOnce(next)
      .mockImplementationOnce(() => {
        entered()
        return new Promise((resolve) => {
          finish = resolve
        })
      })
    await auth.fetchMe()
    await started
    expect(finish).toBeTypeOf('function')
    auth.logout(false)
    await nextTick()
    finish(next)
    await settle()
    expect(auth.user).toBeNull()
    expect(router.currentRoute.value.path).toBe('/login')
    expect(wrapper!.find('[data-test="draft"]').exists()).toBe(false)
    expect(wrapper!.find('[data-test="login"]').exists()).toBe(true)
  })
})
