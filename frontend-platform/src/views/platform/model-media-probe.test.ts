import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { ElButton, ElFormItem, ElInput, ElSelect } from 'element-plus'
import { HttpClientError } from '@skillforge/shared/utils/http'
import ModelMediaProbe from './ModelMediaProbe.vue'
import type { ModelKind, ModelMediaJob } from '../../api'

const mocks = vi.hoisted(() => ({
  create: vi.fn(),
  detail: vi.fn(),
  query: vi.fn(),
  stopWaiting: vi.fn(),
  download: vi.fn(),
  showMessage: vi.fn(() => vi.fn()),
  prepare: vi.fn(),
  allowed: vi.fn(() => true)
}))
vi.mock('../../api', () => ({
  api: {
    admin: {
      models: {
        media: {
          create: mocks.create,
          detail: mocks.detail,
          query: mocks.query,
          stopWaiting: mocks.stopWaiting,
          download: mocks.download
        }
      }
    }
  }
}))
vi.mock('../../composables/actionPermissions', () => ({
  useActionPermissions: () => ({
    allowed: mocks.allowed,
    checked: (_permission: string, run: () => unknown) => run()
  })
}))
vi.mock('@skillforge/shared/feedback', () => ({
  showMessage: mocks.showMessage,
  message: { success: vi.fn() }
}))
let wrapper: VueWrapper | undefined
const result = (
  status: ModelMediaJob['status'],
  additions: Partial<ModelMediaJob> = {}
): ModelMediaJob => ({
  id: 'job-1',
  operation_id: 'operation-1',
  model_config_id: null,
  model_id: 'configured-model',
  model_revision: 0,
  kind: 'IMAGE_GENERATION',
  status,
  waiting_stopped: false,
  created_at: '2026-10-25T12:00:00Z',
  updated_at: '2026-10-25T12:00:01Z',
  assets: [],
  ...additions
})
function field(label: string) {
  return wrapper!.findAllComponents(ElFormItem).find((entry) => entry.props('label') === label)!
}
function button(label: string) {
  return wrapper!.findAllComponents(ElButton).find((entry) => entry.text() === label)!
}
async function open(
  kind: ModelKind = 'IMAGE_GENERATION',
  connection: { serviceId?: string; protocol?: string } = {}
) {
  wrapper = mount(ModelMediaProbe, {
    props: { kind, disabled: false, prepare: mocks.prepare, ...connection },
    global: { directives: { permission: () => {} } }
  })
  await flushPromises()
}
beforeEach(() => {
  vi.resetAllMocks()
  mocks.allowed.mockReturnValue(true)
  mocks.prepare.mockReturnValue({
    payload: {
      name: '未保存模型',
      kind: 'IMAGE_GENERATION',
      model_id: 'configured-model',
      provider_id: 'aliyun-bailian',
      api_key: 'synthetic-key'
    }
  })
})
afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
})

it('当前未保存配置与显式样本一起创建媒体任务，结果保留未知用量而非填零', async () => {
  mocks.create.mockResolvedValue(result('SUCCEEDED'))
  await open()
  field('图像提示词').getComponent(ElInput).vm.$emit('update:modelValue', '一张测试图片')
  await button('开始媒体测试').trigger('click')
  await flushPromises()
  expect(mocks.create).toHaveBeenCalledWith(
    expect.objectContaining({
      operation_id: expect.stringMatching(/^[0-9a-f-]{36}$/),
      payload: expect.objectContaining({ name: '未保存模型', api_key: 'synthetic-key' }),
      input: expect.objectContaining({ type: 'image', prompt: '一张测试图片' })
    })
  )
  expect(wrapper!.text()).toContain('已完成')
  expect(wrapper!.text()).toContain('未保存配置')
  expect(wrapper!.text()).toContain('供应商尚未返回实际用量')
  expect(wrapper!.text()).not.toContain('synthetic-key')
})

it('创建响应断开后锁定样本并保留原操作，用同键同内容核对而非生成新请求', async () => {
  mocks.create
    .mockRejectedValueOnce(new TypeError('network unavailable'))
    .mockResolvedValueOnce(result('PENDING'))
  await open()
  field('图像提示词').getComponent(ElInput).vm.$emit('update:modelValue', '保留原输入')
  await button('开始媒体测试').trigger('click')
  await flushPromises()
  const original = mocks.create.mock.calls[0]![0]
  expect(wrapper!.text()).toContain('原操作编号')
  expect(wrapper!.emitted('pendingOperation')?.at(-1)).toEqual([original.operation_id])
  expect(field('图像提示词').get('textarea').attributes('disabled')).toBeDefined()
  await button('核对原操作').trigger('click')
  await flushPromises()
  expect(mocks.create.mock.calls[1]![0]).toEqual(original)
  expect(mocks.prepare).toHaveBeenCalledTimes(1)
  expect(wrapper!.text()).toContain('已受理')
  expect(wrapper!.text()).not.toContain('原操作编号')
})

it('已知未派发的配置拒绝允许修正输入，UNKNOWN任务只能查询原任务', async () => {
  mocks.create
    .mockRejectedValueOnce(
      new HttpClientError({
        status: 400,
        code: 'model_media_configuration_invalid',
        userMessage: '配置无效'
      })
    )
    .mockResolvedValueOnce(result('UNKNOWN'))
  mocks.query.mockResolvedValue(result('UNKNOWN'))
  mocks.stopWaiting.mockResolvedValue(result('UNKNOWN', { waiting_stopped: true }))
  await open()
  field('图像提示词').getComponent(ElInput).vm.$emit('update:modelValue', '测试')
  await button('开始媒体测试').trigger('click')
  await flushPromises()
  expect(wrapper!.text()).not.toContain('原操作编号')
  await button('开始媒体测试').trigger('click')
  await flushPromises()
  expect(button('开始媒体测试').props('disabled')).toBe(true)
  await button('查询原任务').trigger('click')
  await flushPromises()
  expect(mocks.query).toHaveBeenCalledWith('job-1')
  expect(mocks.create).toHaveBeenCalledTimes(2)
  await button('停止本地等待').trigger('click')
  await flushPromises()
  expect(wrapper!.text()).toContain('供应商任务可能仍在执行')
  expect(button('开始媒体测试').props('disabled')).toBe(true)
  expect(wrapper!.emitted('pendingOperation')?.at(-1)).toEqual(['operation-1'])
})

it('语音合成传显式音色，文件识别使用公开URL输入', async () => {
  mocks.create.mockResolvedValue(result('SUCCEEDED', { kind: 'SPEECH_SYNTHESIS' }))
  await open('SPEECH_SYNTHESIS')
  field('合成文本').getComponent(ElInput).vm.$emit('update:modelValue', '你好')
  field('音色标识').getComponent(ElInput).vm.$emit('update:modelValue', 'configured-voice')
  await button('开始媒体测试').trigger('click')
  await flushPromises()
  expect(mocks.create).toHaveBeenLastCalledWith(
    expect.objectContaining({ input: { type: 'speech', text: '你好', voice: 'configured-voice' } })
  )
  await wrapper!.setProps({ kind: 'SPEECH_RECOGNITION' })
  field('待识别音频 URL')
    .getComponent(ElInput)
    .vm.$emit('update:modelValue', 'https://approved.example/audio.wav')
  await button('开始媒体测试').trigger('click')
  await flushPromises()
  expect(mocks.create).toHaveBeenLastCalledWith(
    expect.objectContaining({
      input: expect.objectContaining({
        type: 'transcription',
        file_urls: ['https://approved.example/audio.wav']
      })
    })
  )
})

it('没有运行权限不发送请求，读取已有任务不创建新供应商调用', async () => {
  mocks.detail.mockResolvedValue(result('RUNNING'))
  await open()
  await wrapper!.get('input[aria-label="已有媒体任务编号"]').setValue('existing-job')
  await button('读取已有任务').trigger('click')
  await flushPromises()
  expect(mocks.detail).toHaveBeenCalledWith('existing-job')
  expect(mocks.create).not.toHaveBeenCalled()
  mocks.allowed.mockReturnValue(false)
  await button('查询原任务').trigger('click')
  await flushPromises()
  expect(mocks.query).not.toHaveBeenCalled()
})

it('Qwen识别按精确服务和协议提交同步输入，不根据模型名称猜测文件识别接口', async () => {
  mocks.prepare.mockReturnValue({
    payload: { kind: 'SPEECH_RECOGNITION', model_id: 'arbitrary-model-id' }
  })
  mocks.create.mockResolvedValue(result('SUCCEEDED', { kind: 'SPEECH_RECOGNITION' }))
  await open('SPEECH_RECOGNITION', {
    serviceId: 'bailian-cn-beijing-qwen-asr',
    protocol: 'qwen-asr-native'
  })
  field('Qwen 待识别音频')
    .getComponent(ElInput)
    .vm.$emit('update:modelValue', 'https://approved.example/audio.mp3')
  field('识别语言').getComponent(ElInput).vm.$emit('update:modelValue', 'zh')
  field('数字等文本归一化').getComponent(ElSelect).vm.$emit('update:modelValue', 'YES')
  await button('开始媒体测试').trigger('click')
  await flushPromises()
  expect(mocks.create).toHaveBeenCalledWith(
    expect.objectContaining({
      input: {
        type: 'qwen-transcription',
        audio: 'https://approved.example/audio.mp3',
        language: 'zh',
        enable_itn: true
      }
    })
  )
  expect(
    wrapper!
      .findAllComponents(ElFormItem)
      .some((entry) => entry.props('label') === '待识别音频 URL')
  ).toBe(false)
})

it('Qwen识别复用已授权MP3产物，通过认证下载编码后再显式提交', async () => {
  mocks.download.mockResolvedValue(
    new Blob([new Uint8Array([0x49, 0x44, 0x33])], { type: 'audio/mpeg' })
  )
  mocks.create.mockResolvedValue(result('SUCCEEDED', { kind: 'SPEECH_RECOGNITION' }))
  await open('SPEECH_RECOGNITION', {
    serviceId: 'bailian-cn-beijing-qwen-asr',
    protocol: 'qwen-asr-native'
  })
  field('音频所属任务编号').getComponent(ElInput).vm.$emit('update:modelValue', 'tts-job')
  field('音频产物编号').getComponent(ElInput).vm.$emit('update:modelValue', 'tts-asset')
  await button('读取已有音频产物').trigger('click')
  await vi.waitFor(() =>
    expect(field('Qwen 待识别音频').getComponent(ElInput).props('modelValue')).toBe(
      'data:audio/mpeg;base64,SUQz'
    )
  )
  expect(mocks.download).toHaveBeenCalledWith('tts-job', 'tts-asset')
  expect(mocks.create).not.toHaveBeenCalled()
  await button('开始媒体测试').trigger('click')
  await flushPromises()
  expect(mocks.create).toHaveBeenCalledWith(
    expect.objectContaining({
      input: {
        type: 'qwen-transcription',
        audio: 'data:audio/mpeg;base64,SUQz'
      }
    })
  )
})

it('Paraformer服务保持异步文件识别输入，即使模型名含qwen也不切换协议', async () => {
  mocks.prepare.mockReturnValue({
    payload: { kind: 'SPEECH_RECOGNITION', model_id: 'qwen-named-custom-model' }
  })
  mocks.create.mockResolvedValue(result('PENDING', { kind: 'SPEECH_RECOGNITION' }))
  await open('SPEECH_RECOGNITION', {
    serviceId: 'bailian-cn-beijing-transcription',
    protocol: 'dashscope-asr'
  })
  field('待识别音频 URL')
    .getComponent(ElInput)
    .vm.$emit('update:modelValue', 'https://approved.example/audio.wav')
  await button('开始媒体测试').trigger('click')
  await flushPromises()
  expect(mocks.create).toHaveBeenCalledWith(
    expect.objectContaining({
      input: expect.objectContaining({
        type: 'transcription',
        file_urls: ['https://approved.example/audio.wav']
      })
    })
  )
  expect(
    wrapper!
      .findAllComponents(ElFormItem)
      .some((entry) => entry.props('label') === 'Qwen 待识别音频')
  ).toBe(false)
})
