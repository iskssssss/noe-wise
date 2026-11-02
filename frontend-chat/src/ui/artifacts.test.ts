import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import type { Client } from '../client'
import type { Deliverable } from '../protocol'
import { defineComponent, h, ref } from 'vue'
import { useConversation } from '../session'
import { ChatMessage, ChatMessageNotice, ChatSelect } from './controls'
import ArtifactPanel from './ArtifactPanel.vue'
import BrowserDevices from './BrowserDevices.vue'
import { artifactFileReference, artifactsForRuns } from './artifacts'

const result = (id: string, run: string, adopted: string[] = []): Deliverable => ({
  id,
  deliverable_id: 'report',
  session_id: 's',
  type: 'website',
  size: 200,
  sha256: 'digest',
  expires_at: null,
  artifact_inputs: [],
  evidence: [],
  name: '经营报告',
  run_id: run,
  result_run_ids: adopted,
  revision: 2,
  current: true,
  status: 'ready',
  files: [
    {
      file_id: `file-${id}`,
      artifact_id: `artifact-${id}`,
      path: 'report.html',
      download_name: '报告.html',
      format: 'html',
      size: 200,
      sha256: 'digest',
      representation_id: 'html',
      primary_preview: true,
      entry: true
    }
  ]
})

const fullscreenCleanup: (() => void)[] = []
afterEach(() =>
  fullscreenCleanup
    .splice(0)
    .reverse()
    .forEach((restore) => restore())
)

/** 即使浏览器提供原生全屏能力，页面预览也不得调用它。 */
function nativeFullscreenSpies() {
  const request = vi.fn(async () => {})
  const exit = vi.fn(async () => {})
  const properties: [object, string, PropertyDescriptor][] = [
    [document, 'fullscreenEnabled', { get: () => true }],
    [document, 'fullscreenElement', { get: () => null }],
    [document, 'exitFullscreen', { value: exit }],
    [Element.prototype, 'requestFullscreen', { value: request }]
  ]
  for (const [target, key, descriptor] of properties) {
    const original = Object.getOwnPropertyDescriptor(target, key)
    fullscreenCleanup.push(() => {
      if (original) Object.defineProperty(target, key, original)
      else Reflect.deleteProperty(target, key)
    })
    Object.defineProperty(target, key, { ...descriptor, configurable: true })
  }
  return { request, exit }
}

function mountWebPreview() {
  const json = vi.fn(async (..._args: unknown[]): Promise<unknown> => ({
    url: 'https://preview.example/report?ticket=limited',
    kind: 'html'
  }))
  const wrapper = mount(ArtifactPanel, {
    attachTo: document.body,
    props: {
      modelValue: true,
      client: { json, base: 'https://chat.example' } as unknown as Client,
      session: 's',
      artifacts: [result('v1', 'root'), result('v2', 'root')],
      selectedId: 'v1'
    }
  })
  return { wrapper, json }
}

it('侧栏改选后再次从对话打开原成果，并在目录迟到时选中目标', async () => {
  const wrapper = mount(
    defineComponent({
      setup() {
        const selected = ref('v1')
        const artifacts = ref<Deliverable[]>([])
        return () =>
          h('div', [
            h('button', { onClick: () => (selected.value = 'v1') }, '打开原成果'),
            h(
              'button',
              { onClick: () => (artifacts.value = [result('v2', 'r'), result('v1', 'r')]) },
              '加载目录'
            ),
            h(ArtifactPanel, {
              modelValue: true,
              client: {
                json: vi.fn(async () => ({ url: 'https://preview.example', kind: 'html' })),
                base: 'https://chat.example'
              } as unknown as Client,
              session: 's',
              artifacts: artifacts.value,
              selectedId: selected.value,
              'onUpdate:selectedId': (value: string) => (selected.value = value)
            })
          ])
      }
    }),
    { attachTo: document.body }
  )
  try {
    await wrapper
      .findAll('button')
      .find((item) => item.text() === '加载目录')!
      .trigger('click')
    await flushPromises()
    const selector = wrapper
      .findAllComponents(ChatSelect)
      .find((item) => item.find('[aria-label="成果版本"]').exists())!
    expect(selector.props('modelValue')).toBe('v1')
    selector.vm.$emit('change', 'v2')
    await flushPromises()
    expect(selector.props('modelValue')).toBe('v2')
    await wrapper
      .findAll('button')
      .find((item) => item.text() === '打开原成果')!
      .trigger('click')
    await flushPromises()
    expect(selector.props('modelValue')).toBe('v1')
  } finally {
    wrapper.unmount()
  }
})

it('成果读取失败可原地重读，刷新失败时保留已读取的成果', async () => {
  const wrapper = mount(ArtifactPanel, {
    attachTo: document.body,
    props: {
      modelValue: true,
      client: { json: vi.fn(), base: 'https://chat.example' } as unknown as Client,
      session: 's',
      artifacts: [],
      error: '成果读取失败',
      loading: false
    }
  })
  try {
    expect(wrapper.text()).toContain('成果尚未读取成功')
    expect(wrapper.text()).not.toContain('成果会出现在这里')
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '重新读取成果')!
      .trigger('click')
    expect(wrapper.emitted('refresh')).toHaveLength(1)
    await wrapper.setProps({ loading: true, error: '' })
    expect(wrapper.text()).toContain('正在读取成果')
    expect(wrapper.text()).not.toContain('成果会出现在这里')
    await wrapper.setProps({ loading: false, artifacts: [result('v1', 'root')], error: '刷新失败' })
    expect(wrapper.text()).toContain('当前展示上次读取的内容')
    expect(wrapper.text()).toContain('经营报告')
    await wrapper.setProps({ artifacts: [], error: '' })
    expect(wrapper.text()).toContain('成果会出现在这里')
  } finally {
    wrapper.unmount()
  }
})

describe('成果文件差异与所选版本归属', () => {
  function mountComparison(diff: (path: string, ...args: unknown[]) => Promise<unknown>) {
    const json = vi.fn(async (path: string, ...args: unknown[]) =>
      path === '/chat/artifacts/diff'
        ? diff(path, ...args)
        : { url: 'https://preview.example/report?ticket=limited', kind: 'html' }
    )
    const wrapper = mount(ArtifactPanel, {
      attachTo: document.body,
      props: {
        modelValue: true,
        session: 's',
        selectedId: 'a',
        artifacts: [result('a', 'root'), result('b', 'root'), result('c', 'root')],
        client: { json, base: 'https://chat.example' } as unknown as Client
      }
    })
    const select = (label: string) =>
      wrapper
        .findAllComponents(ChatSelect)
        .find((item) => item.find(`[aria-label="${label}"]`).exists())!
    return { wrapper, json, select }
  }

  it('改选任一文件清除旧差异，新比较失败不冒充原结果，显式重试只比较新文件', async () => {
    const diff = vi
      .fn()
      .mockResolvedValueOnce({
        status: 'comparable',
        changes: [{ type: 'added', text: '仅属于A到B的内容' }]
      })
      .mockRejectedValueOnce(new Error('新文件比较失败'))
      .mockResolvedValueOnce({
        status: 'comparable',
        changes: [{ type: 'removed', text: '仅属于A到C的内容' }]
      })
    const { wrapper, json, select } = mountComparison(diff)
    try {
      await flushPromises()
      select('基础文件').vm.$emit('update:modelValue', 'a:file-a')
      select('目标文件').vm.$emit('update:modelValue', 'b:file-b')
      await flushPromises()
      await wrapper.get('.artifact-compare form').trigger('submit')
      await flushPromises()
      expect(wrapper.text()).toContain('仅属于A到B的内容')
      expect(wrapper.text()).toContain('文件文本差异如下。')
      select('基础文件').vm.$emit('update:modelValue', 'b:file-b')
      await flushPromises()
      expect(wrapper.text()).not.toContain('仅属于A到B的内容')
      select('基础文件').vm.$emit('update:modelValue', 'a:file-a')
      select('目标文件').vm.$emit('update:modelValue', 'c:file-c')
      await flushPromises()
      await wrapper.get('.artifact-compare form').trigger('submit')
      await flushPromises()
      expect(document.body.textContent).toContain('新文件比较失败')
      expect(wrapper.text()).not.toContain('仅属于A到B的内容')
      await wrapper.get('.artifact-compare form').trigger('submit')
      await flushPromises()
      expect(wrapper.text()).toContain('仅属于A到C的内容')
      expect(diff).toHaveBeenCalledTimes(3)
      expect(diff).toHaveBeenLastCalledWith(
        '/chat/artifacts/diff',
        'POST',
        {
          base: { artifact_id: 'artifact-a', path: 'report.html' },
          target: { artifact_id: 'artifact-c', path: 'report.html' }
        },
        expect.any(AbortSignal)
      )
      expect(json.mock.calls.filter(([path]) => path !== '/chat/artifacts/diff')).toHaveLength(1)
      expect(wrapper.emitted('reference')).toBeUndefined()
    } finally {
      wrapper.unmount()
    }
  })

  it.each([
    ['comparable', '两个文件的文本内容一致。'],
    ['binary', '所选文件包含非文本内容，无法显示文本差异；请下载后比较。'],
    ['too_large', '文件超过在线比较大小限制，请下载后比较。'],
    ['too_many_lines', '文件行数超过在线比较限制，请下载后比较。']
  ])('后端%s状态显示准确中文说明，等待结果时沿用禁用文件选择', async (status, message) => {
    let finish!: (value: unknown) => void
    const diff = vi.fn(
      () =>
        new Promise((resolve) => {
          finish = resolve
        })
    )
    const { wrapper, select } = mountComparison(diff)
    try {
      await flushPromises()
      select('基础文件').vm.$emit('update:modelValue', 'a:file-a')
      select('目标文件').vm.$emit('update:modelValue', 'b:file-b')
      await flushPromises()
      await wrapper.get('.artifact-compare form').trigger('submit')
      expect(select('基础文件').props('disabled')).toBe(true)
      expect(select('目标文件').props('disabled')).toBe(true)
      finish({ status, changes: [] })
      await flushPromises()
      expect(wrapper.get('.artifact-compare [role="status"]').text()).toBe(message)
      expect(select('目标文件').props('disabled')).toBe(false)
    } finally {
      wrapper.unmount()
    }
  })
})

describe('网页成果全屏预览', () => {
  it('只铺满页面可视区域，Escape 还原布局并保留 iframe、焦点和沙箱权限', async () => {
    const api = nativeFullscreenSpies()
    const background = document.createElement('button')
    document.body.append(background)
    const { wrapper, json } = mountWebPreview()
    try {
      await flushPromises()
      const frame = wrapper.get('iframe').element
      await wrapper.get('[aria-label="网页全屏预览"]').trigger('click')
      await flushPromises()
      expect(wrapper.get('.artifact-preview').classes()).toContain('is-fullscreen')
      expect(wrapper.get('.artifact-preview').attributes('aria-modal')).toBe('true')
      expect(background.inert).toBe(true)
      expect(document.activeElement).toBe(wrapper.get('[aria-label="退出网页全屏"]').element)
      expect(wrapper.get('iframe').attributes()).toMatchObject({
        sandbox: 'allow-scripts',
        referrerpolicy: 'no-referrer'
      })
      expect(document.fullscreenElement).toBeNull()
      expect(api.request).not.toHaveBeenCalled()
      await wrapper.get('.artifact-preview').trigger('keydown', { key: 'Escape' })
      await flushPromises()
      expect(wrapper.get('.artifact-preview').classes()).not.toContain('is-fullscreen')
      expect(wrapper.get('iframe').element).toBe(frame)
      expect(wrapper.emitted('update:modelValue')).toBeUndefined()
      expect(background.inert).toBeFalsy()
      expect(json).toHaveBeenCalledTimes(1)
      expect(api.exit).not.toHaveBeenCalled()
      expect(document.activeElement).toBe(wrapper.get('[aria-label="网页全屏预览"]').element)
    } finally {
      wrapper.unmount()
      background.remove()
    }
  })

  it('宽窄屏缩放保留 iframe 与面板控件焦点，不重读预览，进入全屏仍定位切换按钮', async () => {
    let resize!: (event: MediaQueryListEvent) => void
    vi.stubGlobal('matchMedia', (query: string) => ({
      matches: false,
      addEventListener: (_: string, listener: typeof resize) => {
        if (query === '(max-width: 800px)') resize = listener
      },
      removeEventListener: vi.fn()
    }))
    const { wrapper, json } = mountWebPreview()
    try {
      await flushPromises()
      const frame = wrapper.get('iframe').element
      frame.focus()
      expect(document.activeElement).toBe(frame)
      for (const narrow of [true, false, true]) {
        resize({ matches: narrow } as MediaQueryListEvent)
        await flushPromises()
        expect(wrapper.get('iframe').element).toBe(frame)
        expect(document.activeElement).toBe(frame)
      }
      resize({ matches: false } as MediaQueryListEvent)
      await flushPromises()
      const selector = wrapper.get('[aria-label="成果版本"]')
      ;(selector.element as HTMLElement).focus()
      resize({ matches: true } as MediaQueryListEvent)
      await flushPromises()
      expect(document.activeElement).toBe(selector.element)
      await wrapper.get('[aria-label="网页全屏预览"]').trigger('click')
      await flushPromises()
      expect(document.activeElement).toBe(wrapper.get('[aria-label="退出网页全屏"]').element)
      frame.focus()
      resize({ matches: false } as MediaQueryListEvent)
      await flushPromises()
      expect(document.activeElement).toBe(frame)
      expect(wrapper.get('iframe').element).toBe(frame)
      expect(json).toHaveBeenCalledTimes(1)
    } finally {
      wrapper.unmount()
      vi.unstubAllGlobals()
    }
  })

  it('按钮退出保留原网页，不受浏览器原生全屏事件影响', async () => {
    const { wrapper, json } = mountWebPreview()
    try {
      await flushPromises()
      const frame = wrapper.get('iframe').element
      await wrapper.get('[aria-label="网页全屏预览"]').trigger('click')
      document.dispatchEvent(new Event('fullscreenchange'))
      await flushPromises()
      expect(wrapper.get('.artifact-preview').classes()).toContain('is-fullscreen')
      await wrapper.get('[aria-label="退出网页全屏"]').trigger('click')
      await flushPromises()
      expect(wrapper.get('.artifact-preview').classes()).not.toContain('is-fullscreen')
      expect(wrapper.get('iframe').element).toBe(frame)
      expect(json).toHaveBeenCalledTimes(1)
    } finally {
      wrapper.unmount()
    }
  })

  it('切换成果版本还原页面布局，收起预览移除网页', async () => {
    const { wrapper } = mountWebPreview()
    try {
      await flushPromises()
      await wrapper.get('[aria-label="网页全屏预览"]').trigger('click')
      await wrapper.setProps({ selectedId: 'v2' })
      await flushPromises()
      expect(wrapper.get('.artifact-preview').classes()).not.toContain('is-fullscreen')
      await wrapper.get('[aria-label="网页全屏预览"]').trigger('click')
      await wrapper
        .findAll('button')
        .find((button) => button.text() === '收起预览')!
        .trigger('click')
      await flushPromises()
      expect(wrapper.find('iframe').exists()).toBe(false)
    } finally {
      wrapper.unmount()
    }
  })

  it('会话切换清除旧全屏预览并释放背景隔离', async () => {
    const background = document.createElement('button')
    document.body.append(background)
    const { wrapper } = mountWebPreview()
    try {
      await flushPromises()
      await wrapper.get('[aria-label="网页全屏预览"]').trigger('click')
      await flushPromises()
      await wrapper.setProps({ session: 'new', artifacts: [] })
      await flushPromises()
      expect(wrapper.find('.is-fullscreen').exists()).toBe(false)
      expect(wrapper.find('iframe').exists()).toBe(false)
      expect(background.inert).toBeFalsy()
    } finally {
      wrapper.unmount()
      background.remove()
    }
  })

  it('关闭全屏预览面板直接清理页面布局与背景隔离', async () => {
    const api = nativeFullscreenSpies()
    const background = document.createElement('button')
    document.body.append(background)
    const { wrapper } = mountWebPreview()
    try {
      await flushPromises()
      await wrapper.get('[aria-label="网页全屏预览"]').trigger('click')
      await flushPromises()
      const panel = wrapper.vm as unknown as { requestClose(): Promise<boolean> }
      expect(await panel.requestClose()).toBe(true)
      await flushPromises()
      expect(wrapper.find('iframe').exists()).toBe(false)
      expect(wrapper.emitted('update:modelValue')).toEqual([[false]])
      expect(background.inert).toBeFalsy()
      expect(api.request).not.toHaveBeenCalled()
      expect(api.exit).not.toHaveBeenCalled()
    } finally {
      wrapper.unmount()
      background.remove()
    }
  })
})

describe('网页预览加载与重试', () => {
  it('从加载到长等待提示，导航结束移除提示并取消计时，不声明业务成功', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    const { wrapper } = mountWebPreview()
    try {
      await flushPromises()
      expect(wrapper.get('.preview-web-status').text()).toBe('正在加载网页…')
      expect(wrapper.get('iframe').attributes('aria-busy')).toBe('true')
      await vi.advanceTimersByTimeAsync(15_000)
      expect(wrapper.get('.preview-web-status').text()).toContain('网页加载时间较长')
      await wrapper.get('iframe').trigger('load')
      expect(wrapper.find('.preview-web-status').exists()).toBe(false)
      expect(wrapper.get('iframe').attributes('aria-busy')).toBe('false')
      expect(vi.getTimerCount()).toBe(0)
      expect(wrapper.text()).not.toContain('加载成功')
    } finally {
      wrapper.unmount()
      vi.useRealTimers()
    }
  })

  it('全屏内重试仍请求原版本票据，同 URL 显式刷新且迟到事件不影响新导航', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    const { wrapper, json } = mountWebPreview()
    try {
      await flushPromises()
      const old = wrapper.get('iframe').element
      await wrapper.get('[aria-label="网页全屏预览"]').trigger('click')
      await flushPromises()
      await wrapper.get('[aria-label="重新加载网页"]').trigger('click')
      await flushPromises()
      expect(json).toHaveBeenCalledTimes(2)
      expect(json).toHaveBeenLastCalledWith(
        '/chat/deliverables/preview-ticket',
        'POST',
        { id: 'v1', file_id: 'file-v1' },
        expect.any(AbortSignal)
      )
      expect(wrapper.get('iframe').element).not.toBe(old)
      expect(wrapper.get('.artifact-preview').classes()).toContain('is-fullscreen')
      old.dispatchEvent(new Event('load'))
      await flushPromises()
      // 排空 jsdom 焦点变更产生的零延时任务，只统计仍待执行的加载超时。
      await vi.advanceTimersByTimeAsync(0)
      expect(wrapper.get('.preview-web-status').text()).toBe('正在加载网页…')
      expect(vi.getTimerCount()).toBe(1)
      await wrapper.get('iframe').trigger('error')
      expect(wrapper.get('.preview-web-status').text()).toContain('网页暂未载入')
      expect(vi.getTimerCount()).toBe(0)
    } finally {
      wrapper.unmount()
      vi.useRealTimers()
    }
  })

  it('全屏内重试票据失败时使用顶部 Message，并保留原网页与退出入口', async () => {
    const { wrapper, json } = mountWebPreview()
    try {
      await flushPromises()
      await wrapper.get('iframe').trigger('load')
      await wrapper.get('[aria-label="网页全屏预览"]').trigger('click')
      await flushPromises()
      const frame = wrapper.get('iframe').element
      json.mockRejectedValueOnce(new Error('票据暂不可用'))
      await wrapper.get('[aria-label="重新加载网页"]').trigger('click')
      await flushPromises()
      expect(document.querySelector('.chat-toast [role="alert"]')?.textContent).toContain(
        '票据暂不可用'
      )
      expect(wrapper.find('.artifact-preview [role="alert"]').exists()).toBe(false)
      expect(wrapper.get('iframe').element).toBe(frame)
      expect(wrapper.get('.artifact-preview').classes()).toContain('is-fullscreen')
      expect(wrapper.get('[aria-label="退出网页全屏"]').attributes('disabled')).toBeUndefined()
    } finally {
      wrapper.unmount()
    }
  })

  it('重试期间收起预览会取消票据请求，迟到结果不能重新打开网页', async () => {
    const { wrapper, json } = mountWebPreview()
    let finish!: (ticket: { url: string; kind: string }) => void
    try {
      await flushPromises()
      json.mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            finish = resolve
          })
      )
      await wrapper.get('[aria-label="重新加载网页"]').trigger('click')
      const signal = json.mock.calls.at(-1)?.[3] as AbortSignal
      await wrapper
        .findAll('button')
        .find((button) => button.text() === '收起预览')!
        .trigger('click')
      await flushPromises()
      expect(signal.aborted).toBe(true)
      expect(wrapper.find('iframe').exists()).toBe(false)
      finish({ url: 'https://preview.example/late-retry', kind: 'html' })
      await flushPromises()
      expect(wrapper.find('iframe').exists()).toBe(false)
      expect(wrapper.find('.artifact-loading').exists()).toBe(false)
    } finally {
      finish?.({ url: 'https://preview.example/late-retry', kind: 'html' })
      await flushPromises()
      wrapper.unmount()
    }
  })

  it('切换、关闭和卸载均清理等待计时，旧重试票据不能进入新会话', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    const { wrapper, json } = mountWebPreview()
    let finish!: (ticket: { url: string; kind: string }) => void
    try {
      await flushPromises()
      await vi.advanceTimersByTimeAsync(0)
      expect(vi.getTimerCount()).toBe(1)
      json.mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            finish = resolve
          })
      )
      await wrapper.get('[aria-label="重新加载网页"]').trigger('click')
      const signal = json.mock.calls.at(-1)?.[3] as AbortSignal
      await wrapper.setProps({ session: 'new', artifacts: [] })
      expect(signal.aborted).toBe(true)
      finish({ url: 'https://preview.example/old-retry', kind: 'html' })
      await flushPromises()
      await vi.advanceTimersByTimeAsync(0)
      expect(wrapper.find('iframe').exists()).toBe(false)
      expect(vi.getTimerCount()).toBe(0)
      await wrapper.setProps({ artifacts: [result('v2', 'new')] })
      await flushPromises()
      await vi.advanceTimersByTimeAsync(0)
      expect(vi.getTimerCount()).toBe(1)
      await wrapper.setProps({ modelValue: false })
      await vi.advanceTimersByTimeAsync(0)
      expect(vi.getTimerCount()).toBe(0)
      await wrapper.setProps({ modelValue: true })
      await flushPromises()
      await vi.advanceTimersByTimeAsync(0)
      expect(vi.getTimerCount()).toBe(1)
    } finally {
      finish?.({ url: 'https://preview.example/old-retry', kind: 'html' })
      await flushPromises()
      wrapper.unmount()
      try {
        await vi.advanceTimersByTimeAsync(0)
        expect(vi.getTimerCount()).toBe(0)
      } finally {
        vi.useRealTimers()
      }
    }
  })
})

describe('成果操作反馈生命周期', () => {
  it('预览读取失败只说明读取问题，不将任意HTTP错误当作链接过期', async () => {
    const fetch = vi.fn().mockResolvedValue({ ok: false, status: 503 })
    vi.stubGlobal('fetch', fetch)
    const json = vi.fn().mockResolvedValue({ url: 'https://preview.example/report', kind: 'text' })
    const item = result('v1', 'root')
    item.files[0]!.format = 'txt'
    const wrapper = mount(ArtifactPanel, {
      props: {
        modelValue: true,
        client: { json, base: 'https://chat.example' } as unknown as Client,
        session: 's',
        artifacts: [item],
        selectedId: 'v1'
      }
    })
    try {
      await flushPromises()
      expect(fetch).toHaveBeenCalledOnce()
      expect(document.querySelector('.chat-toast')?.textContent).toContain('未能读取文件预览')
      expect(document.querySelector('.chat-toast')?.textContent).toContain('再次点击“预览”获取链接')
      expect(wrapper.text()).not.toContain('已失效')
    } finally {
      wrapper.unmount()
      vi.unstubAllGlobals()
    }
  })

  for (const state of ['success', 'error'] as const) {
    for (const transition of ['session', 'close', 'removed'] as const) {
      it(`${state} 反馈在 ${transition} 后清除`, async () => {
        const { wrapper, json } = mountWebPreview()
        try {
          await flushPromises()
          if (state === 'success') {
            const click = vi
              .spyOn(HTMLAnchorElement.prototype, 'click')
              .mockImplementation(() => {})
            try {
              await wrapper
                .findAll('button')
                .find((button) => button.text() === '下载')!
                .trigger('click')
              await flushPromises()
              expect(json).toHaveBeenCalledWith(
                '/chat/deliverables/download-ticket',
                'POST',
                { id: 'v1', file_id: 'file-v1' },
                expect.any(AbortSignal)
              )
              expect(click).toHaveBeenCalledOnce()
            } finally {
              click.mockRestore()
            }
            expect(
              document.querySelector('.chat-toast .chat-alert--success')?.getAttribute('role')
            ).toBe('status')
            expect(
              document.querySelector('.chat-toast .chat-alert--success')?.textContent
            ).toContain('已发起下载，请在浏览器下载列表中查看。')
          } else {
            json.mockRejectedValueOnce(new Error('旧操作失败'))
            await wrapper.get('.artifact-continue').trigger('click')
            await flushPromises()
            expect(document.querySelector('.chat-toast .chat-alert--error')?.textContent).toContain(
              '旧操作失败'
            )
          }
          if (transition === 'session') {
            await wrapper.setProps({ session: 'new', artifacts: [] })
          } else if (transition === 'removed') {
            await wrapper.setProps({ artifacts: [] })
          } else {
            await wrapper.get('[aria-label="关闭成果面板"]').trigger('click')
            await flushPromises()
            expect(wrapper.emitted('update:modelValue')?.at(-1)).toEqual([false])
            await wrapper.setProps({ modelValue: false })
            await wrapper.setProps({ modelValue: true })
          }
          await flushPromises()
          expect(document.querySelector('.chat-toast .chat-alert--success')).toBeNull()
          expect(document.querySelector('.chat-toast .chat-alert--error')).toBeNull()
        } finally {
          wrapper.unmount()
        }
      })
    }
  }

  for (const outcome of ['success', 'error'] as const) {
    it(`成果失效后忽略迟到的 ${outcome} 响应`, async () => {
      const { wrapper, json } = mountWebPreview()
      let resolve!: (value: unknown) => void
      let reject!: (reason: Error) => void
      try {
        await flushPromises()
        json.mockImplementationOnce(
          () =>
            new Promise((done, fail) => {
              resolve = done
              reject = fail
            })
        )
        await wrapper.get('.artifact-continue').trigger('click')
        await wrapper.setProps({ artifacts: [] })
        if (outcome === 'success')
          resolve({
            references: [{ label: '旧成果', source: { kind: 'artifact_files', files: [] } }]
          })
        else reject(new Error('迟到的旧操作失败'))
        await flushPromises()
        expect(wrapper.emitted('reference')).toBeUndefined()
        expect(document.querySelector('.chat-toast .chat-alert--success')).toBeNull()
        expect(document.querySelector('.chat-toast .chat-alert--error')).toBeNull()
      } finally {
        wrapper.unmount()
      }
    })
  }
})

describe('公开成果与全局设备', () => {
  it('按生产运行和采用运行关联成果，文件引用保持所选版本的稳定身份', () => {
    const direct = result('v1', 'root'),
      adopted = result('v2', 'child', ['root']),
      other = result('v3', 'other')
    expect(artifactsForRuns([direct, adopted, other], ['root'])).toEqual([direct, adopted])
    expect(artifactsForRuns([direct, adopted, other], [])).toEqual([])
    expect(artifactFileReference(direct, direct.files[0]!)).toEqual({
      label: '经营报告 · v2 / 报告.html',
      source: {
        kind: 'artifact_files',
        files: [{ artifact_id: 'artifact-v1', path: 'report.html' }]
      }
    })
  })

  it('以固定版本请求HTML票据，并保留隔离预览和原版本引用', async () => {
    const json = vi.fn(async () => ({
      url: 'https://preview.example/report?ticket=limited',
      kind: 'html'
    }))
    const wrapper = mount(ArtifactPanel, {
      props: {
        modelValue: true,
        client: { json, base: 'https://chat.example' } as unknown as Client,
        session: 's',
        artifacts: [result('v1', 'root')],
        selectedId: 'v1'
      }
    })
    await flushPromises()
    expect(json).toHaveBeenCalledWith(
      '/chat/deliverables/preview-ticket',
      'POST',
      { id: 'v1', file_id: 'file-v1' },
      expect.any(AbortSignal)
    )
    expect(wrapper.get('iframe').attributes()).toMatchObject({
      src: 'https://preview.example/report?ticket=limited',
      sandbox: 'allow-scripts',
      referrerpolicy: 'no-referrer'
    })
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '引用')!
      .trigger('click')
    expect(wrapper.emitted('reference')?.[0]).toEqual([
      artifactFileReference(result('v1', 'root'), result('v1', 'root').files[0]!)
    ])
    wrapper.unmount()
  })

  it('切换会话后忽略迟到的预览票据，不把旧文件显示到新对话', async () => {
    let complete!: (ticket: { url: string; kind: string }) => void
    const json = vi.fn(
      () =>
        new Promise((resolve) => {
          complete = resolve
        })
    )
    const wrapper = mount(ArtifactPanel, {
      props: {
        modelValue: true,
        client: { json, base: 'https://chat.example' } as unknown as Client,
        session: 'old',
        artifacts: [result('v1', 'root')]
      }
    })
    await wrapper.setProps({ session: 'new', artifacts: [] })
    complete({ url: 'https://preview.example/old', kind: 'html' })
    await flushPromises()
    expect(wrapper.find('iframe').exists()).toBe(false)
    wrapper.unmount()
  })

  it('设备列表和配对仅提交访问者身份，不绑定会话', async () => {
    const pairingAddress = 'wss://browser.example/extension/owner#token=synthetic-pair'
    const json = vi.fn(async (path: string) =>
      path.startsWith('/chat/browser/devices')
        ? { items: [], installation_url: '' }
        : path.startsWith('/chat/browser/tasks')
          ? []
          : { pairing_url: pairingAddress }
    )
    const wrapper = mount(BrowserDevices, {
      props: {
        modelValue: true,
        client: { json, base: 'https://chat.example' } as unknown as Client,
        clientRef: 'visitor'
      }
    })
    await flushPromises()
    expect(json).toHaveBeenCalledWith(
      '/chat/browser/devices?client_ref=visitor',
      'GET',
      undefined,
      expect.any(AbortSignal)
    )
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '手动官方扩展配对')!
      .trigger('click')
    await flushPromises()
    expect(json).toHaveBeenCalledWith(
      '/chat/browser/pair',
      'POST',
      { client_ref: 'visitor' },
      expect.any(AbortSignal)
    )
    expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toBe(pairingAddress)
    expect(wrapper.get('textarea').attributes()).toHaveProperty('readonly')
    expect(wrapper.find('a').exists()).toBe(false)
    expect(wrapper.find('.chat-alert--error').exists()).toBe(false)
    expect(wrapper.text()).toContain('Remote connection')
    wrapper.unmount()
  })
})

describe('成果引用由实际草稿消费者确认反馈', () => {
  it.each(['file', 'recover'] as const)(
    '%s引用成功只产生一次真实消费者反馈，拒绝时不宣告加入',
    async (action) => {
      for (const outcome of ['success', 'limit', 'pending'] as const) {
        let conversation!: ReturnType<typeof useConversation>
        const artifact = result('reference-version', 'root')
        const reference = artifactFileReference(artifact, artifact.files[0]!)
        const json = vi.fn(async (path: string) =>
          path === '/chat/deliverables/recovery-inputs'
            ? { references: [reference] }
            : { url: 'https://preview.example/reference?ticket=limited', kind: 'html' }
        )
        const wrapper = mount(
          defineComponent({
            setup() {
              conversation = useConversation()
              conversation.limits.value = {
                attachments: {
                  max_file_bytes: 1,
                  max_files: 1,
                  max_total_bytes: 1,
                  extensions: []
                },
                references: { max_items: 1, max_file_selectors: 1 }
              }
              if (outcome === 'limit')
                conversation.references.value = [{ label: '已有引用', source: { id: 'existing' } }]
              if (outcome === 'pending')
                conversation.pendingInput.value = {
                  session_id: 's',
                  client_message_id: 'original-message',
                  content: [],
                  context: { end_user_id: 'user' }
                }
              return () =>
                h('div', [
                  h(ArtifactPanel, {
                    modelValue: true,
                    client: { json, base: 'https://chat.example' } as unknown as Client,
                    session: 's',
                    artifacts: [artifact],
                    selectedId: artifact.id,
                    onReference: conversation.reference
                  }),
                  h(ChatMessageNotice, { title: conversation.notice.value, type: 'info' }),
                  h(ChatMessageNotice, { title: conversation.error.value, type: 'error' })
                ])
            }
          }),
          { attachTo: document.body }
        )
        try {
          await flushPromises()
          await wrapper
            .findAll('button')
            .find((button) => button.text() === (action === 'file' ? '引用' : '引用并继续修改'))!
            .trigger('click')
          await flushPromises()
          const messages = document.querySelectorAll('.chat-toast')
          expect(messages).toHaveLength(1)
          if (outcome === 'success') {
            expect(conversation.references.value).toEqual([reference])
            expect(messages[0]!.textContent).toContain('已加入下一条消息的引用')
          } else {
            expect(conversation.references.value).toHaveLength(outcome === 'limit' ? 1 : 0)
            expect(messages[0]!.textContent).toContain(
              outcome === 'limit' ? '引用数量已达上限' : '请先恢复观察或重试原请求'
            )
            expect(messages[0]!.textContent).not.toContain('已加入')
          }
          if (action === 'recover')
            expect(json).toHaveBeenCalledWith(
              '/chat/deliverables/recovery-inputs',
              'POST',
              { revision_id: artifact.id },
              expect.any(AbortSignal)
            )
        } finally {
          wrapper.unmount()
          ChatMessage.closeAll()
        }
      }
    }
  )
})
