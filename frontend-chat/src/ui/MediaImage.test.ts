import { afterEach, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import MediaImage from './MediaImage.vue'
import type { Client } from '../client'

afterEach(() => vi.unstubAllGlobals())

it('图片解码失败后可以重新读取原图片，并释放旧地址', async () => {
  const create = vi.fn().mockReturnValueOnce('blob:first').mockReturnValueOnce('blob:second')
  const revoke = vi.fn()
  class ImageURL extends URL {
    static createObjectURL = create
    static revokeObjectURL = revoke
  }
  vi.stubGlobal('URL', ImageURL)
  const blob = vi.fn().mockResolvedValue(new Blob(['image']))
  const wrapper = mount(MediaImage, {
    props: { client: { blob } as unknown as Client, session: 'session', media: 'media' }
  })
  try {
    await flushPromises()
    await wrapper.get('img').trigger('error')
    expect(wrapper.text()).toContain('图片暂时无法显示')
    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(blob).toHaveBeenCalledTimes(2)
    expect(blob.mock.calls[1]![0]).toBe(blob.mock.calls[0]![0])
    expect(revoke).toHaveBeenCalledWith('blob:first')
    expect(wrapper.get('img').attributes('src')).toBe('blob:second')
  } finally {
    wrapper.unmount()
  }
  expect(revoke).toHaveBeenCalledWith('blob:second')
})
