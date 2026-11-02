import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { ElButton, ElInput } from 'element-plus'
import ReviewPage from './ReviewPage.vue'

const mocks = vi.hoisted(() => ({
  reviews: vi.fn(),
  detail: vi.fn(),
  approve: vi.fn(),
  reject: vi.fn(),
  confirm: vi.fn()
}))
vi.mock('../../api', () => ({ api: { marketplace: mocks } }))
vi.mock('../../composables/actionPermissions', () => ({
  useActionPermissions: () => ({
    allowed: () => true,
    checked: (_: string, run: () => unknown) => run()
  })
}))
vi.mock('@skillforge/shared/feedback', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@skillforge/shared/feedback')>()),
  confirm: mocks.confirm,
  message: { success: vi.fn(), error: vi.fn() }
}))

let wrapper: VueWrapper | undefined
const row = {
  id: 'workflow-1',
  workflow_name: '销售周报',
  version: '2.3',
  author: '提交者',
  status: 'pending',
  updated_at: '2026-10-24T01:02:03.123456Z'
}
const content = (target = row) => ({
  workflow_id: target.id,
  name: target.workflow_name,
  version: target.version,
  status: 'review',
  updated_at: target.updated_at,
  files: [{ name: 'workflow.yaml', size_bytes: 35, content: 'name: 销售周报\nversion: 2.3\n' }]
})
function button(label: string) {
  return wrapper!.findAllComponents(ElButton).find((value) => value.text() === label)!
}
async function open() {
  wrapper = mount(ReviewPage, {
    attachTo: document.body,
    global: {
      directives: { permission: () => {} },
      stubs: {
        CodeEditor: {
          props: ['modelValue', 'readonly', 'label'],
          template: '<pre>{{ modelValue }}</pre>'
        }
      }
    }
  })
  await flushPromises()
  await button('查看并审核').trigger('click')
  await flushPromises()
}
beforeEach(() => {
  vi.clearAllMocks()
  mocks.reviews.mockResolvedValue({ items: [row], total: 1 })
  mocks.detail.mockResolvedValue(content())
  mocks.confirm.mockResolvedValue(true)
  mocks.approve.mockResolvedValue(undefined)
  mocks.reject.mockResolvedValue(undefined)
})
afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  document.body.innerHTML = ''
})

it('读取所见微秒修订的真实包内容前禁用决定，内容就绪才允许批准', async () => {
  let resolve!: (value: ReturnType<typeof content>) => void
  mocks.detail.mockReturnValue(
    new Promise((value) => {
      resolve = value
    })
  )
  await open()
  expect(mocks.detail).toHaveBeenCalledWith(
    row.id,
    row.updated_at,
    expect.objectContaining({ silent: true })
  )
  expect(button('批准工作流').props('disabled')).toBe(true)
  expect(button('拒绝工作流').props('disabled')).toBe(true)
  resolve(content())
  await flushPromises()
  expect(document.body.textContent).toContain('version: 2.3')
  expect(button('批准工作流').props('disabled')).toBe(false)
  await button('批准工作流').trigger('click')
  await flushPromises()
  expect(mocks.approve).toHaveBeenCalledExactlyOnceWith(row.id, row.updated_at)
})

it('详情修订不一致时保持封锁，不把新内容当作列表所见版本', async () => {
  mocks.detail.mockResolvedValue({ ...content(), updated_at: '2026-10-24T02:00:00Z' })
  await open()
  expect(document.body.textContent).toContain('审核内容与列表修订不一致')
  expect(button('批准工作流').props('disabled')).toBe(true)
  expect(mocks.approve).not.toHaveBeenCalled()
})

it('409 后可针对同一原修订重读，拒绝原因保留且不会自动提交', async () => {
  await open()
  const reason = wrapper!
    .findAllComponents(ElInput)
    .find((value) => value.props('type') === 'textarea')!
  reason.vm.$emit('update:modelValue', '缺少用户数据隔离说明')
  mocks.reject.mockRejectedValue(new Error('409：所见修订已过期，请重新读取'))
  await button('拒绝工作流').trigger('click')
  await flushPromises()
  expect(mocks.reject).toHaveBeenCalledExactlyOnceWith(
    row.id,
    row.updated_at,
    '缺少用户数据隔离说明'
  )
  expect(reason.props('modelValue')).toBe('缺少用户数据隔离说明')
  expect(document.body.textContent).toContain('所见修订已过期')
})

it('详情读取失败可重试相同修订，缺 workflow.yaml 时仍禁止决定', async () => {
  mocks.detail.mockRejectedValueOnce(new Error('409：内容已变化'))
  await open()
  expect(button('批准工作流').props('disabled')).toBe(true)
  mocks.detail.mockResolvedValue({
    ...content(),
    files: [{ name: 'asset.bin', size_bytes: 8, content: null }]
  })
  await button('重试读取本修订').trigger('click')
  await flushPromises()
  expect(mocks.detail).toHaveBeenLastCalledWith(row.id, row.updated_at, expect.any(Object))
  expect(document.body.textContent).toContain('缺少可读取的 workflow.yaml')
  expect(button('批准工作流').props('disabled')).toBe(true)
})

it('切换审核对象后旧详情响应不能覆盖新对象或其决定身份', async () => {
  const other = { ...row, id: 'workflow-2', workflow_name: '客户回访', version: '3' }
  mocks.reviews.mockResolvedValue({ items: [row, other], total: 2 })
  let resolve!: (value: ReturnType<typeof content>) => void
  mocks.detail.mockImplementation((id: string) =>
    id === row.id
      ? new Promise((value) => {
          resolve = value
        })
      : Promise.resolve(content(other))
  )
  await open()
  await wrapper!
    .findAllComponents(ElButton)
    .filter((value) => value.text() === '查看并审核')[1]!
    .trigger('click')
  await flushPromises()
  resolve(content())
  await flushPromises()
  expect(document.body.textContent).toContain('审核：客户回访')
  await button('批准工作流').trigger('click')
  await flushPromises()
  expect(mocks.approve).toHaveBeenCalledExactlyOnceWith(other.id, other.updated_at)
})
