import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus, { ElSelect } from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import FoundationOptionSelect from './FoundationOptionSelect.vue'
import type { FoundationOption, FoundationOptionPage, FoundationOptionRequest } from './optionTypes'
import { HttpClientError } from '../utils/http'

const option = (id: string, name = '同名成员'): FoundationOption => ({
  id,
  name,
  code: `member-${id}`,
  status: 'active'
})
const wrappers: VueWrapper[] = []
beforeEach(() => {
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe() {}
      unobserve() {}
      disconnect() {}
    }
  )
})
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  document.body.innerHTML = ''
  vi.unstubAllGlobals()
})
function render(request: FoundationOptionRequest, modelValue: string | string[] = '') {
  const wrapper = mount(FoundationOptionSelect, {
    attachTo: document.body,
    props: {
      request,
      path: '/tenant/foundation/options/members?purpose=security',
      modelValue,
      label: '选择成员',
      multiple: Array.isArray(modelValue)
    },
    global: { plugins: [ElementPlus] }
  })
  wrappers.push(wrapper)
  return wrapper
}
function choices(wrapper: VueWrapper) {
  return wrapper.findAllComponents({ name: 'ElOption' }).map((item) => item.props('value'))
}

describe('foundation 真实分页选择目录', () => {
  it('没有候选时空多选保持稳定', async () => {
    const wrapper = render(async <T>() => ({ items: [], has_more: false }) as T, [])
    await flushPromises()
    expect(choices(wrapper)).toEqual([])
  })
  it('数字内部标识不进入用户可见选项文本', async () => {
    const resource = {
      id: '870176814764037',
      name: '灵知助理',
      code: '870176814755845',
      status: 'draft'
    }
    const wrapper = render(
      async <T>() => ({ items: [resource], has_more: false }) as T,
      resource.id
    )
    await flushPromises()
    await wrapper.get('.el-select__wrapper').trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('灵知助理')
    expect(document.body.textContent).not.toContain(resource.id)
    expect(document.body.textContent).not.toContain(resource.code)
  })
  it('搜索、加载下一页与精确已选解析保持完整字符串身份和已选名称', async () => {
    const paths: string[] = []
    const chosen = option('9223372036854775806', '已选成员')
    const request: FoundationOptionRequest = async <T>(_method: string, path: string) => {
      paths.push(path)
      const url = new URL(path, 'https://fixture')
      const value: FoundationOptionPage = url.searchParams.has('ids')
        ? { items: [chosen], has_more: false }
        : {
            items: [option(url.searchParams.get('page') === '2' ? '52' : '51')],
            has_more: url.searchParams.get('page') !== '2'
          }
      return value as T
    }
    const wrapper = render(request, chosen.id)
    await flushPromises()
    expect(paths.some((path) => path.includes('ids=9223372036854775806'))).toBe(true)
    await wrapper.get('.el-select__wrapper').trigger('click')
    await flushPromises()
    await wrapper.getComponent(ElSelect).props('remoteMethod')('成员')
    await flushPromises()
    expect(choices(wrapper)).toEqual([chosen.id, '51'])
    const more = [...document.querySelectorAll<HTMLButtonElement>('button')].find(
      (button) => button.textContent?.trim() === '加载更多'
    )!
    more.click()
    await flushPromises()
    expect(paths.at(-1)).toContain('page=2')
    expect(paths.at(-1)).toContain('keyword=%E6%88%90%E5%91%98')
    expect(choices(wrapper)).toEqual([chosen.id, '51', '52'])
  })

  it('账号或目录切换后，迟到候选与已选解析不能污染新目录', async () => {
    const finish: Array<(value: FoundationOptionPage) => void> = []
    const oldRequest = vi.fn(
      () => new Promise<FoundationOptionPage>((resolve) => finish.push(resolve))
    ) as FoundationOptionRequest
    const wrapper = render(oldRequest, '9223372036854775806')
    await wrapper.setProps({
      request: async <T>() => ({ items: [option('71', '新账号成员')], has_more: false }) as T,
      modelValue: '',
      path: '/admin/foundation/options/members?purpose=security'
    })
    await flushPromises()
    finish.forEach((resolve) =>
      resolve({ items: [option('9223372036854775806', '旧账号秘密')], has_more: false })
    )
    await flushPromises()
    expect(choices(wrapper)).toEqual(['71'])
    expect(wrapper.text()).not.toContain('旧账号秘密')
  })
  it('明确丢弃旧目录的迟到错误也认领反馈，防止旧错误再次全局弹出', async () => {
    let reject!: (reason: unknown) => void
    const oldRequest: FoundationOptionRequest = <T>() =>
      new Promise<T>((_resolve, fail) => {
        reject = fail
      })
    const wrapper = render(oldRequest)
    await wrapper.setProps({
      request: async <T>() => ({ items: [], has_more: false }) as T,
      path: '/admin/foundation/options/members'
    })
    await flushPromises()
    const failure = new HttpClientError({ status: 503, userMessage: '旧账号的过期错误' })
    reject(failure)
    await flushPromises()
    expect(failure.hasFeedbackOwner).toBe(true)
    expect(wrapper.text()).not.toContain('旧账号的过期错误')
  })

  it('多选范围以数组提交，已选失效提供持续反馈且不伪造候选', async () => {
    const wrapper = render(
      async <T>() => ({ items: [], has_more: false }) as T,
      ['9007199254740999']
    )
    await flushPromises()
    expect(document.querySelector('.el-message--error')?.textContent).toContain(
      '部分已选项目已不可用或不在当前授权范围'
    )
    expect(choices(wrapper)).toEqual([])
    await wrapper.getComponent(ElSelect).props('remoteMethod')('另一位成员')
    await flushPromises()
    expect(document.querySelector('.el-message--error')?.textContent).toContain(
      '部分已选项目已不可用或不在当前授权范围'
    )
    wrapper.getComponent(ElSelect).vm.$emit('update:modelValue', ['71', '72'])
    expect(wrapper.emitted('update:modelValue')?.at(-1)).toEqual([['71', '72']])
  })

  it('已选不在搜索页且首次解析失败，重试同时刷新候选和重新解析并恢复名称', async () => {
    let resolving = 0
    const failure = new HttpClientError({ status: 503, userMessage: '已选成员读取暂不可用' })
    const chosen = option('9223372036854775806', '保留的已选成员')
    const request: FoundationOptionRequest = async <T>(_method: string, path: string) => {
      if (path.includes('ids=')) {
        resolving++
        if (resolving === 1) throw failure
        return { items: [chosen], has_more: false } as T
      }
      return { items: [option('51')], has_more: false } as T
    }
    const wrapper = render(request, chosen.id)
    await flushPromises()
    expect(document.querySelector('.el-message--error')?.textContent).toContain(
      '已选成员读取暂不可用'
    )
    expect(failure.hasFeedbackOwner).toBe(true)
    await wrapper.getComponent(ElSelect).props('remoteMethod')('未选中的成员')
    await flushPromises()
    expect(document.querySelector('.el-message--error')?.textContent).toContain(
      '已选成员读取暂不可用'
    )
    document.querySelector<HTMLButtonElement>('.el-message--error button.el-button')!.click()
    await flushPromises()
    await flushPromises()
    expect(resolving).toBe(2)
    expect(wrapper.text()).not.toContain('已选成员读取暂不可用')
    expect(choices(wrapper)).toEqual([chosen.id, '51'])
  })
})
