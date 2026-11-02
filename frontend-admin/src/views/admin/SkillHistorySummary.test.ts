import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { ElSelect } from 'element-plus'
import SkillWorkbenchPage from './SkillWorkbenchPage.vue'
const mocks = vi.hoisted(() => ({
  summaries: vi.fn(),
  fullList: vi.fn(),
  active: vi.fn(),
  version: vi.fn()
}))
vi.mock('../../api', () => ({
  api: {
    capabilities: {
      getDefinition: async () => ({
        id: '11',
        name: '技能',
        revision: 1,
        capabilityKey: 'skill',
        defaultVersionId: '20'
      }),
      listSkillVersionSummaries: mocks.summaries,
      versions: mocks.fullList
    },
    skillDrafts: { getActive: mocks.active, getVersion: mocks.version }
  }
}))
vi.mock('../../stores/auth', () => ({ useAuthStore: () => ({ user: null }) }))
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { definitionId: '11' } }),
  onBeforeRouteLeave: vi.fn(),
  onBeforeRouteUpdate: vi.fn()
}))
vi.mock('@skillforge/shared/components/PackageFileWorkbench.vue', () => ({
  default: { props: ['files'], template: '<pre class="exact-files">{{ files["SKILL.md"] }}</pre>' }
}))
let wrapper: VueWrapper | undefined
const content = (id: string, text: string, requiresPlan: boolean | null) => ({
  id,
  definitionId: '11',
  revision: 0,
  versionKey: 'v' + id,
  files: { 'SKILL.md': text },
  modelInvocable: true,
  userInvocable: true,
  activationMode: 'ON_DEMAND',
  conflictGroup: null,
  requiredCapabilities: [],
  requiresPlan
})
async function open() {
  wrapper = mount(SkillWorkbenchPage)
  await flushPromises()
}
function choice() {
  return wrapper!
    .findAllComponents(ElSelect)
    .find((item) => item.props('placeholder') === '工作版本')!
}
async function switchTo(id: string) {
  choice().vm.$emit('change', id)
  await flushPromises()
}
beforeEach(() => {
  vi.resetAllMocks()
  mocks.summaries.mockResolvedValue([
    { id: '20', versionKey: 'required-v', status: 'ARCHIVED' },
    { id: '21', versionKey: 'optional-v', status: 'PUBLISHED' },
    { id: '22', versionKey: 'unknown-v', status: 'DRAFT' }
  ])
  mocks.fullList.mockRejectedValue(new Error('不得读取完整历史列表'))
  mocks.active.mockResolvedValue(content('12', 'active-file', null))
  mocks.version.mockImplementation(async (id) =>
    id === '20'
      ? content(id, 'required-file', true)
      : id === '21'
        ? content(id, 'optional-file', false)
        : content(id, 'unknown-file', null)
  )
})
afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  document.body.innerHTML = ''
})
it('initial load reads only three-field summaries; selected content and exact plan come from the same single response', async () => {
  await open()
  expect(mocks.summaries).toHaveBeenCalledExactlyOnceWith('11')
  expect(mocks.fullList).not.toHaveBeenCalled()
  expect(mocks.version).not.toHaveBeenCalled()
  await switchTo('20')
  expect(mocks.version).toHaveBeenCalledExactlyOnceWith('20')
  expect(wrapper!.find('.exact-files').text()).toBe('required-file')
  expect(wrapper!.text()).toContain('计划要求：必须先计划')
  await switchTo('21')
  expect(mocks.version).toHaveBeenCalledTimes(2)
  expect(mocks.version).toHaveBeenLastCalledWith('21')
  expect(wrapper!.find('.exact-files').text()).toBe('optional-file')
  expect(wrapper!.text()).toContain('计划要求：按需计划')
  expect(mocks.summaries).toHaveBeenCalledTimes(1)
})
it.each(['read-failure', 'foreign-definition'])(
  'failed or foreign historical response preserves the prior selected identity, files and plan (%s)',
  async (failure) => {
    await open()
    await switchTo('20')
    if (failure === 'read-failure') mocks.version.mockRejectedValueOnce(new Error('版本读取失败'))
    else
      mocks.version.mockResolvedValueOnce({
        ...content('21', 'foreign-file', false),
        definitionId: '99'
      })
    await switchTo('21')
    expect(choice().props('modelValue')).toBe('20')
    expect(wrapper!.find('.exact-files').text()).toBe('required-file')
    expect(wrapper!.text()).toContain('计划要求：必须先计划')
  }
)
it('null plan is explicit and returning to active hides prior historical plan without guessing from draft files', async () => {
  await open()
  await switchTo('22')
  expect(wrapper!.text()).toContain('计划要求：尚未读取')
  expect(wrapper!.find('.exact-files').text()).toBe('unknown-file')
  await switchTo('')
  expect(choice().props('modelValue')).toBe('')
  expect(wrapper!.find('.exact-files').text()).toBe('active-file')
  expect(wrapper!.text()).not.toContain('计划要求：')
  expect(mocks.active).toHaveBeenCalledTimes(2)
})
