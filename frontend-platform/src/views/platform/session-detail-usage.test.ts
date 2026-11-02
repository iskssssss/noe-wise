import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import SessionDetailPage from './SessionDetailPage.vue'

const mocks = vi.hoisted(() => ({ usage: vi.fn() }))
vi.mock('../../stores/auth', () => ({
  useAuthStore: () => ({ identityVersion: 1, user: { sub: 'platform-actor', permissions: [] } })
}))
vi.mock('../../api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../api')>()),
  api: {
    sessions: {
      detail: async () => ({ tenant_name: '测试机构', message_count: 8 }),
      rounds: async () => ({ items: [], next_cursor: null }),
      messages: async () => ({ items: [], next_cursor: null }),
      usage: mocks.usage
    }
  }
}))

const mounted: VueWrapper[] = []
afterEach(() => {
  mounted.forEach((wrapper) => wrapper.unmount())
  mounted.length = 0
})

/** 挂载实际详情页，保证用户看到的是百分比，而不是仅验证未被调用的格式函数。 */
async function openUsage(ratio: string | null) {
  mocks.usage.mockResolvedValue({
    coverage: { status: 'COMPLETE', reported_attempts: 12, total_attempts: 12 },
    totals: null,
    cache: { availability: ratio === null ? 'UNKNOWN' : 'AVAILABLE', hit_ratio: ratio },
    attempts: [],
    next_cursor: null
  })
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/platform/sessions/detail', component: SessionDetailPage },
      { path: '/platform/sessions', component: { template: '<div />' } }
    ]
  })
  await router.push('/platform/sessions/detail?tenant_id=7&session_id=session&tab=usage')
  await router.isReady()
  const wrapper = mount(SessionDetailPage, { global: { plugins: [router] } })
  mounted.push(wrapper)
  await flushPromises()
  const label = wrapper
    .findAll('.el-descriptions__label')
    .find((item) => item.text() === '缓存命中率')
  expect(label, wrapper.text()).toBeDefined()
  return label!.element.nextElementSibling?.textContent?.trim()
}

describe('会话详情缓存命中率', () => {
  it.each([
    ['0.275301', '27.53%'],
    ['0.27337', '27.34%'],
    ['0', '0.00%'],
    ['1', '100.00%'],
    [null, '—'],
    ['unknown', '—']
  ])('将比例 %s 呈现为 %s', async (ratio, expected) => {
    expect(await openUsage(ratio)).toBe(expected)
  })
})
