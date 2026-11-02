import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { ElButton, ElSelect } from 'element-plus'
import { HttpClientError } from '@skillforge/shared/utils/http'
import SkillWorkbenchPage from './SkillWorkbenchPage.vue'
const mocks = vi.hoisted(() => ({
  member: 'user',
  permissions: ['foundation.skills.read', 'foundation.skills.update', 'foundation.skills.publish'],
  definition: vi.fn(),
  versions: vi.fn(),
  active: vi.fn(),
  save: vi.fn(),
  publish: vi.fn(),
  combine: vi.fn(),
  result: vi.fn(),
  confirm: vi.fn(),
  notify: vi.fn(),
  setDefault: vi.fn(),
  getVersion: vi.fn()
}))
vi.mock('../../stores/auth', () => ({
  useAuthStore: () => ({
    user: {
      tenant_id: 'tenant',
      get sub() {
        return mocks.member
      },
      get permissions() {
        return mocks.permissions
      }
    }
  })
}))
vi.mock('./skillPublicationEvents', () => ({ notifySkillPublished: mocks.notify }))
vi.mock('../../api', () => ({
  api: {
    capabilities: {
      getDefinition: mocks.definition,
      listSkillVersionSummaries: mocks.versions,
      listRequirementOptions: vi.fn(),
      setSkillDefault: mocks.setDefault
    },
    skillDrafts: {
      getActive: mocks.active,
      getVersion: mocks.getVersion,
      save: mocks.save,
      publish: mocks.publish,
      saveAndPublish: mocks.combine,
      saveAndPublishResult: mocks.result
    }
  }
}))
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { definitionId: '11' } }),
  onBeforeRouteLeave: vi.fn(),
  onBeforeRouteUpdate: vi.fn()
}))
vi.mock('@skillforge/shared/feedback', async (original) => ({
  ...(await original<typeof import('@skillforge/shared/feedback')>()),
  confirm: mocks.confirm
}))
vi.mock('@skillforge/shared/components/PackageFileWorkbench.vue', () => ({
  default: {
    props: ['files', 'readonly'],
    emits: ['update:files'],
    template: `<button @click="$emit('update:files', {'SKILL.md': '修改后'})">编辑文件</button><button @click="$emit('update:files', {'SKILL.md': String.fromCharCode(0xd800)})">非法字符</button>`
  }
}))
const draft = {
  id: '12',
  definitionId: '11',
  revision: 2,
  versionKey: '1.0',
  files: { 'SKILL.md': '原文' },
  modelInvocable: true,
  userInvocable: true,
  activationMode: 'ON_DEMAND',
  conflictGroup: null,
  requiredCapabilities: []
}
let wrapper: VueWrapper
beforeEach(() => {
  vi.resetAllMocks()
  sessionStorage.clear()
  mocks.member = 'user'
  mocks.permissions = [
    'foundation.skills.read',
    'foundation.skills.update',
    'foundation.skills.publish'
  ]
  mocks.definition.mockResolvedValue({
    id: '11',
    name: '技能',
    revision: 7,
    capabilityKey: 'skill'
  })
  mocks.versions.mockResolvedValue([])
  mocks.active.mockImplementation(async () => structuredClone(draft))
  mocks.confirm.mockResolvedValue(true)
})
afterEach(() => {
  wrapper?.unmount()
  document.body.innerHTML = ''
  vi.restoreAllMocks()
})
async function open() {
  wrapper = mount(SkillWorkbenchPage, { attachTo: document.body })
  await flushPromises()
}
async function click(text: string) {
  const button = wrapper.findAllComponents(ElButton).find((item) => item.text() === text)
  expect(button, text).toBeDefined()
  await button!.trigger('click')
  await flushPromises()
}
async function edit(text = '编辑文件') {
  await wrapper
    .findAll('button')
    .find((item) => item.text() === text)!
    .trigger('click')
  await flushPromises()
}
describe('技能保存并发布', () => {
  it('成功回执已确认但存储清理失败时仍通知，并保留待核对记录防止重复提交', async () => {
    mocks.combine.mockImplementation(async (request_id: string) => ({
      request_id,
      status: 'SUCCEEDED',
      draft_id: '12',
      definition_id: '11',
      revision: 3,
      publication: { version_id: '42', definition_revision: 8 }
    }))
    await open()
    await edit()
    vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => {
      throw Error('storage')
    })
    await click('保存并发布')
    expect(mocks.notify).toHaveBeenCalledExactlyOnceWith(JSON.stringify(['tenant', 'user']))
    expect(document.body.textContent).toContain('无法清除原发布记录')
    expect(sessionStorage.length).toBe(1)
    expect(mocks.combine).toHaveBeenCalledOnce()
    const submit = wrapper.findAllComponents(ElButton).find((item) => item.text() === '保存并发布')
    expect(submit?.props('disabled')).toBe(true)
  })
  it('普通发布成功才通知同账号；失败不通知', async () => {
    mocks.publish.mockRejectedValueOnce(Error('发布失败'))
    await open()
    await click('发布')
    expect(mocks.notify).not.toHaveBeenCalled()
    mocks.publish.mockResolvedValueOnce({ versionId: '42', definitionRevision: 8 })
    await click('发布')
    expect(mocks.notify).toHaveBeenCalledExactlyOnceWith(JSON.stringify(['tenant', 'user']))
  })
  it('设为当前发布只有确认同一技能与版本后通知', async () => {
    mocks.versions.mockResolvedValueOnce([{ id: '42', versionKey: '1', status: 'PUBLISHED' }])
    mocks.getVersion.mockResolvedValue(structuredClone(draft))
    await open()
    wrapper.findComponent(ElSelect).vm.$emit('change', '42')
    await flushPromises()
    mocks.setDefault.mockResolvedValueOnce({ id: '11', defaultVersionId: 'other' })
    await click('设为当前发布')
    expect(mocks.notify).not.toHaveBeenCalled()
    mocks.setDefault.mockResolvedValueOnce({ id: '11', defaultVersionId: '42' })
    await click('设为当前发布')
    expect(mocks.notify).toHaveBeenCalledExactlyOnceWith(JSON.stringify(['tenant', 'user']))
  })
  it('组合发布在途时更换账号，迟到成功不通知新账号', async () => {
    let finish!: (value: unknown) => void
    mocks.combine.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve
        })
    )
    await open()
    await edit()
    await click('保存并发布')
    const request = mocks.combine.mock.calls[0]![0] as string
    mocks.member = 'other'
    finish({
      request_id: request,
      status: 'SUCCEEDED',
      draft_id: '12',
      definition_id: '11',
      revision: 3,
      publication: { version_id: '42', definition_revision: 8 }
    })
    await flushPromises()
    expect(mocks.notify).not.toHaveBeenCalled()
  })
  it('原请求只读核对成功才发送发布通知', async () => {
    mocks.combine.mockRejectedValueOnce(Error('断连'))
    await open()
    await edit()
    await click('保存并发布')
    const request = mocks.combine.mock.calls[0]![0] as string
    expect(mocks.notify).not.toHaveBeenCalled()
    mocks.result.mockResolvedValueOnce({
      request_id: request,
      status: 'SUCCEEDED',
      draft_id: '12',
      definition_id: '11',
      revision: 3,
      publication: { version_id: '42', definition_revision: 8 }
    })
    await click('核对原操作')
    expect(mocks.notify).toHaveBeenCalledExactlyOnceWith(JSON.stringify(['tenant', 'user']))
    expect(mocks.combine).toHaveBeenCalledOnce()
  })
  it('一次组合调用绑定两项修订并显示回执', async () => {
    mocks.combine.mockImplementation(async (request_id: string) => ({
      request_id,
      status: 'SUCCEEDED',
      draft_id: '12',
      definition_id: '11',
      revision: 3,
      publication: { version_id: '42', definition_revision: 8 }
    }))
    await open()
    await edit()
    await click('保存并发布')
    expect(mocks.combine).toHaveBeenCalledTimes(1)
    expect(mocks.combine).toHaveBeenCalledWith(
      expect.any(String),
      7,
      2,
      expect.objectContaining({
        draft_id: '12',
        files: { 'SKILL.md': btoa(unescape(encodeURIComponent('修改后'))) }
      })
    )
    expect(mocks.save).not.toHaveBeenCalled()
    expect(mocks.publish).not.toHaveBeenCalled()
    expect(mocks.notify).toHaveBeenCalledExactlyOnceWith(JSON.stringify(['tenant', 'user']))
    expect(document.body.textContent).toContain('版本 42')
  })
  it('UNKNOWN 重建后仅原主体恢复并只读核对', async () => {
    mocks.combine.mockRejectedValueOnce(Error('断连'))
    mocks.result.mockResolvedValueOnce(undefined)
    await open()
    await edit()
    await click('保存并发布')
    const request = mocks.combine.mock.calls[0]![0]
    wrapper.unmount()
    mocks.member = 'other'
    await open()
    expect(document.body.textContent).not.toContain(request)
    wrapper.unmount()
    mocks.member = 'user'
    await open()
    expect(document.body.textContent).toContain(request)
    await click('核对原操作')
    expect(mocks.result).toHaveBeenCalledExactlyOnceWith(request)
    expect(document.body.textContent).toContain(request)
    expect(mocks.combine).toHaveBeenCalledTimes(1)
    expect(mocks.notify).not.toHaveBeenCalled()
  })
  it('确定拒绝显示校验并保留正文；非法编码不提交也不锁定', async () => {
    mocks.combine.mockImplementationOnce(async (request_id: string) => ({
      request_id,
      status: 'REJECTED',
      error_message: '内容无效',
      validation: {
        valid: false,
        issues: [
          { path: 'SKILL.md', line: 1, message: '格式无效', code: 'INVALID', severity: 'ERROR' }
        ]
      }
    }))
    await open()
    await edit()
    await click('保存并发布')
    expect(document.body.textContent).toContain('格式无效')
    await edit('非法字符')
    await click('保存并发布')
    expect(mocks.combine).toHaveBeenCalledTimes(1)
    expect(document.body.textContent).not.toContain('写入已锁定')
  })
  it('存储失败不派发；首次前置拒绝解锁，查询拒绝不清除 UNKNOWN', async () => {
    await open()
    await edit()
    const fail = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw Error('blocked')
    })
    await click('保存并发布')
    expect(mocks.combine).not.toHaveBeenCalled()
    fail.mockRestore()
    wrapper.unmount()
    await open()
    await edit()
    mocks.combine.mockRejectedValueOnce(
      new HttpClientError({
        status: 403,
        code: 'SKILL_PUBLISH_PRE_DISPATCH_REJECTED',
        userMessage: '拒绝'
      })
    )
    await click('保存并发布')
    expect(document.body.textContent).not.toContain('写入已锁定')
    expect(mocks.combine).toHaveBeenCalledTimes(1)
    expect(
      wrapper
        .findAllComponents(ElButton)
        .find((item) => item.text() === '保存并发布')!
        .props('disabled')
    ).toBe(false)
    mocks.combine.mockRejectedValueOnce(Error('断连'))
    await click('保存并发布')
    const request = mocks.combine.mock.calls[1]![0]
    mocks.result.mockRejectedValueOnce(
      new HttpClientError({
        status: 403,
        code: 'SKILL_PUBLISH_PRE_DISPATCH_REJECTED',
        userMessage: '拒绝'
      })
    )
    await click('核对原操作')
    expect(document.body.textContent).toContain(request)
  })
})
