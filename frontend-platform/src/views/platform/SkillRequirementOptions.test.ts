import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { ElButton } from 'element-plus'
import SkillWorkbenchPage from './SkillWorkbenchPage.vue'
vi.mock('../../stores/auth', () => ({ useAuthStore: () => ({ user: { sub: 'platform-test' } }) }))
const mocks = vi.hoisted(() => ({
  options: vi.fn(),
  definition: vi.fn(),
  versions: vi.fn(),
  detail: vi.fn(),
  active: vi.fn(),
  save: vi.fn(),
  oldDefinitions: vi.fn()
}))
vi.mock('../../api', () => ({
  api: {
    capabilities: {
      getDefinition: mocks.definition,
      versions: mocks.versions,
      getVersion: mocks.detail,
      definitions: mocks.oldDefinitions,
      listDefinitions: mocks.oldDefinitions,
      listRequirementOptions: mocks.options
    },
    skillDrafts: { getActive: mocks.active, save: mocks.save }
  }
}))
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { definitionId: '11' }, query: {} }),
  onBeforeRouteLeave: vi.fn(),
  onBeforeRouteUpdate: vi.fn()
}))
vi.mock('@skillforge/shared/components/CodeEditor.vue', () => ({
  default: { template: '<div />' }
}))
vi.mock('@skillforge/shared/components/PackageFileWorkbench.vue', () => ({
  default: { template: '<div />' }
}))
vi.mock('../../composables/actionPermissions', () => ({
  useActionPermissions: () => ({
    allowed: () => true,
    checked: (_p: string, run: () => unknown) => run()
  })
}))
let wrapper: VueWrapper | undefined
const rows = [
  { type: 'TOOL', capabilityId: '21', versionId: 'old' },
  { type: 'TOOL', capabilityId: '22', versionId: 'old2' }
]
const draft = {
  id: '12',
  definitionId: '11',
  revision: 2,
  versionKey: '1.0',
  files: {},
  modelInvocable: true,
  userInvocable: true,
  activationMode: 'ON_DEMAND',
  conflictGroup: null,
  requiredCapabilities: rows
}
beforeEach(() => {
  vi.resetAllMocks()
  mocks.definition.mockResolvedValue({
    id: '11',
    name: '技能',
    revision: 1,
    capabilityKey: 'skill',
    defaultVersionId: null
  })
  mocks.versions.mockResolvedValue([])
  mocks.detail.mockRejectedValue(new Error('依赖不得读取完整版本'))
  mocks.oldDefinitions.mockRejectedValue(new Error('依赖不得读取旧目录'))
  mocks.active.mockResolvedValue({
    ...draft,
    requiredCapabilities: rows.map((row) => ({ ...row }))
  })
  mocks.options.mockResolvedValue([
    { id: '21', name: '工具甲', versionId: '9223372036854775807', versionKey: 'current' },
    { id: '22', name: '工具乙', versionId: '32', versionKey: 'current2' }
  ])
  mocks.save.mockImplementation(async (input) => ({ ...draft, ...input, id: '12', revision: 3 }))
})
afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  document.body.innerHTML = ''
})
async function open() {
  wrapper = mount(SkillWorkbenchPage, {
    attachTo: document.body,
    global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } }
  })
  await flushPromises()
}
async function save() {
  await wrapper!
    .findAllComponents(ElButton)
    .find((item) => item.text() === '保存草稿')!
    .trigger('click')
  await flushPromises()
}
it('multiple dependencies of one type use one option directory and save exact published IDs without dependency detail requests', async () => {
  await open()
  await save()
  expect(mocks.options).toHaveBeenCalledTimes(1)
  expect(mocks.options).toHaveBeenLastCalledWith('TOOL')
  expect(mocks.definition).toHaveBeenCalledExactlyOnceWith('11')
  expect(mocks.detail).not.toHaveBeenCalled()
  expect(mocks.oldDefinitions).not.toHaveBeenCalled()
  expect(mocks.save).toHaveBeenCalledExactlyOnceWith(
    expect.objectContaining({
      requiredCapabilities: [
        { type: 'TOOL', capabilityId: '21', versionId: 'old' },
        { type: 'TOOL', capabilityId: '22', versionId: 'old2' }
      ]
    })
  )
})
it('目录暂不可读时仍可提交已选身份，由服务端核对', async () => {
  mocks.options.mockRejectedValueOnce(new Error('依赖目录暂不可用'))
  await open()
  expect(mocks.save).not.toHaveBeenCalled()
  await save()
  expect(mocks.save).toHaveBeenCalledTimes(1)
  await wrapper!
    .findAllComponents(ElButton)
    .find((item) => item.text() === '重新加载选项')!
    .trigger('click')
  await flushPromises()
  await save()
  expect(mocks.options).toHaveBeenCalledTimes(2)
  expect(mocks.save).toHaveBeenCalledWith(
    expect.objectContaining({
      requiredCapabilities: [
        { type: 'TOOL', capabilityId: '21', versionId: 'old' },
        { type: 'TOOL', capabilityId: '22', versionId: 'old2' }
      ]
    })
  )
  expect(mocks.detail).not.toHaveBeenCalled()
})
it('服务端拒绝不可用依赖后保留选择，可刷新后重试', async () => {
  await open()
  mocks.save.mockRejectedValueOnce(new Error('依赖已不可用'))
  await save()
  expect(mocks.save).toHaveBeenCalledTimes(1)
  mocks.options.mockResolvedValueOnce([
    { id: '21', name: '工具甲', versionId: 'new-21', versionKey: 'new' },
    { id: '22', name: '工具乙', versionId: 'new-22', versionKey: 'new' }
  ])
  await wrapper!
    .findAllComponents(ElButton)
    .find((item) => item.text() === '刷新依赖选项')!
    .trigger('click')
  await flushPromises()
  await save()
  expect(mocks.save).toHaveBeenCalledWith(
    expect.objectContaining({
      requiredCapabilities: [
        { type: 'TOOL', capabilityId: '21', versionId: 'old' },
        { type: 'TOOL', capabilityId: '22', versionId: 'old2' }
      ]
    })
  )
})
