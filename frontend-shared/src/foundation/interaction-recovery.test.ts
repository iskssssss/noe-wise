import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ElementPlus, { ElInputNumber, ElSelect, ElSwitch, ElTabs } from 'element-plus'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import { defineComponent, h, type Component } from 'vue'
import FoundationPage from './FoundationPage.vue'
import CatalogPage from './catalog/CatalogPage.vue'
import OperationsPage from './operations/OperationsPage.vue'
import FoundationFilesPage from './assets/FoundationFilesPage.vue'
import FoundationJobsPage from './assets/FoundationJobsPage.vue'
import FoundationOptionSelect from './FoundationOptionSelect.vue'
import type { FoundationFile, FoundationRequest } from './assets/contracts'
import type { SecurityApi, SecurityAuthApi } from './security/api'
import { clearFeedback, confirm } from '../feedback'

vi.mock('../feedback', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../feedback')>()),
  confirm: vi.fn()
}))
const wrappers: VueWrapper[] = []
beforeEach(() => {
  vi.mocked(confirm).mockReset()
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
  clearFeedback()
  document.body.innerHTML = ''
  vi.unstubAllGlobals()
})
async function render(component: Component, props: Record<string, unknown>) {
  const page = defineComponent({ setup: () => () => h(component, props) })
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/', component: page }]
  })
  await router.push('/')
  const wrapper = mount(
    { render: () => h(RouterView) },
    {
      attachTo: document.body,
      global: { plugins: [router, ElementPlus] }
    }
  )
  wrappers.push(wrapper)
  await flushPromises()
  return wrapper
}
const handler = {
  code: 'members',
  title: '成员材料',
  columns: ['name'],
  can_import: true,
  can_export: true,
  option_fields: [{ code: 'query', label: '查询条件', required: false, hint: '输入条件' }]
}
const emptyPage = { items: [], total: 0, page: 1, page_size: 20 }
const uploaded: FoundationFile = {
  id: '1',
  name: 'members.csv',
  mime_type: 'text/csv',
  size_bytes: 10,
  sha256: 'test',
  reference_count: 0,
  created_at: '2026-10-25T00:00:00Z'
}
function filesAndJobsRequest(upload?: () => Promise<FoundationFile>): FoundationRequest {
  return async <T>(method: string, path: string) => {
    if (path.endsWith('/handlers')) return [handler] as T
    if (method === 'POST' && upload) return (await upload()) as T
    return emptyPage as T
  }
}
const foundationProps = (request: FoundationRequest) => ({
  request,
  platform: true,
  area: 'jobs',
  permissions: ['foundation.files.read', 'foundation.jobs.read', 'foundation.jobs.create'],
  securityApi: {} as SecurityApi,
  securityAuthApi: {} as SecurityAuthApi,
  onAuthentication: vi.fn(),
  onLogout: vi.fn()
})
function chooseFile(input: HTMLInputElement) {
  const file = new File(['name\nmember'], 'members.csv', { type: 'text/csv' })
  Object.defineProperty(input, 'files', { configurable: true, value: [file] })
  return vi.spyOn(input, 'value', 'set')
}
const limits = {
  agents: 5,
  workflows: null,
  knowledge_bases: null,
  members: null,
  channels: null,
  file_bytes: null,
  admin_tasks: null,
  sub_institutions: null
}

describe('共享业务页面草稿与失败恢复', () => {
  it('文件页签先确认任务草稿，取消保持输入，确认后才卸载', async () => {
    const wrapper = await render(FoundationPage, foundationProps(filesAndJobsRequest()))
    wrapper.getComponent(ElSelect).vm.$emit('update:modelValue', 'members')
    await flushPromises()
    await wrapper.get('input[placeholder="输入条件"]').setValue('保留的草稿')
    vi.mocked(confirm).mockResolvedValueOnce(false)
    await wrapper.get('#tab-files').trigger('click')
    await flushPromises()
    expect(wrapper.get('input[placeholder="输入条件"]').element).toHaveProperty(
      'value',
      '保留的草稿'
    )
    expect(wrapper.getComponent(ElTabs).props('modelValue')).toBe('jobs')
    vi.mocked(confirm).mockResolvedValueOnce(true)
    await wrapper.get('#tab-files').trigger('click')
    await flushPromises()
    expect(wrapper.getComponent(ElTabs).props('modelValue')).toBe('files')
    expect(wrapper.findComponent(FoundationJobsPage).exists()).toBe(false)
  })

  it('材料上传在途阻止切换文件页签，回执仍交付当前任务', async () => {
    let finish!: (value: FoundationFile) => void
    const wrapper = await render(
      FoundationPage,
      foundationProps(
        filesAndJobsRequest(
          () =>
            new Promise((resolve) => {
              finish = resolve
            })
        )
      )
    )
    wrapper.getComponent(ElSelect).vm.$emit('update:modelValue', 'members')
    await flushPromises()
    const input = wrapper.get<HTMLInputElement>('input[type=file]')
    chooseFile(input.element)
    await input.trigger('change')
    await wrapper.get('#tab-files').trigger('click')
    await flushPromises()
    expect(wrapper.getComponent(ElTabs).props('modelValue')).toBe('jobs')
    expect(confirm).not.toHaveBeenCalled()
    finish(uploaded)
    await flushPromises()
    expect(wrapper.text()).toContain(uploaded.name)
  })

  it.each(['plan', 'tenant'])('功能授权保存保留%s限额草稿及原CAS基线', async (target) => {
    const plan = target === 'plan'
    let refreshed = false
    const commands: Array<{ path: string; body: unknown }> = []
    const request = async <T>(method: string, path: string, body?: unknown) => {
      if (method === 'PUT') {
        commands.push({ path, body })
        refreshed = true
        return {} as T
      }
      if (path.endsWith('/features'))
        return [
          { code: 'feature_a', name: '功能甲', description: '', enabled: true, revision: 1 }
        ] as T
      if (path.endsWith('/plans'))
        return [{ id: '1', code: 'a', name: '套餐甲', enabled: true, revision: 1 }] as T
      if (path.includes('plan-quota'))
        return {
          plan_id: '1',
          limits: { ...limits, agents: refreshed ? 6 : 5 },
          revision: refreshed ? 2 : 1
        } as T
      if (path.includes('tenant-entitlements'))
        return {
          tenant_id: '1',
          limits,
          overrides: { ...limits, agents: refreshed ? 6 : 5 },
          revision: refreshed ? 2 : 1,
          usage: [],
          features: refreshed ? ['feature_a'] : []
        } as T
      if (path.includes('features'))
        return [{ feature_code: 'feature_a', enabled: refreshed, revision: refreshed ? 2 : 1 }] as T
      throw new Error(path)
    }
    const wrapper = await render(CatalogPage, {
      request,
      platform: true,
      permissions: ['foundation.entitlements.read', 'foundation.entitlements.manage']
    })
    if (plan) wrapper.getComponent(ElSelect).vm.$emit('change', '1')
    else
      wrapper.getComponent(FoundationOptionSelect).vm.$emit('selected', { id: '1', name: '机构甲' })
    await flushPromises()
    wrapper.findAllComponents(ElInputNumber)[0]!.vm.$emit('change', 99)
    await flushPromises()
    if (plan) wrapper.getComponent(ElSwitch).vm.$emit('change', true)
    else
      wrapper
        .findAllComponents(ElSelect)
        .find((select) => typeof select.props('modelValue') === 'boolean')!
        .vm.$emit('change', true)
    await flushPromises()
    expect(wrapper.findAllComponents(ElInputNumber)[0]!.props('modelValue')).toBe(99)
    expect(commands).toHaveLength(1)
    expect(confirm).not.toHaveBeenCalled()
    if (!plan) expect(wrapper.text()).toContain('功能甲')
    await wrapper
      .findAll('button')
      .find((button) => button.text() === (plan ? '保存套餐限额' : '保存机构覆盖'))!
      .trigger('click')
    await flushPromises()
    expect(commands[1]!.body).toMatchObject({ limits: { agents: 99 }, expected_revision: 1 })
  })

  it('策略保存中输入和页签禁用，回执完成后推进基线并恢复编辑', async () => {
    let finish!: (value: unknown) => void
    let saved = false
    const request = async <T>(method: string, path: string) => {
      if (method === 'PUT')
        return new Promise<T>((resolve) => {
          finish = (value) => {
            saved = true
            resolve(value as T)
          }
        })
      if (path.endsWith('/retention'))
        return {
          resource: 'audit',
          retention_days: saved ? 181 : 180,
          enabled: true,
          version: saved ? 8 : 7
        } as T
      if (path.endsWith('/runs') || path.endsWith('/holds')) return [] as T
      return emptyPage as T
    }
    const wrapper = await render(OperationsPage, {
      request,
      apiBase: '/tenant/foundation/operations',
      permissions: ['audit_read', 'retention_read', 'retention_manage'].map(
        (p) => `foundation.operations.${p}`
      )
    })
    await wrapper.get('#tab-retention').trigger('click')
    await flushPromises()
    await wrapper.get('form.policy input').setValue('181')
    await wrapper.get('form.policy').trigger('submit')
    await flushPromises()
    expect(wrapper.get('form.policy input').attributes('disabled')).toBeDefined()
    expect(wrapper.get('#tab-audit').classes()).toContain('is-disabled')
    await wrapper.get('#tab-audit').trigger('click')
    await flushPromises()
    expect(wrapper.getComponent(ElTabs).props('modelValue')).toBe('retention')
    finish({ resource: 'audit', retention_days: 181, enabled: true, version: 8 })
    await flushPromises()
    expect(wrapper.get('form.policy input').element).toHaveProperty('value', '181')
    expect(wrapper.get('form.policy input').attributes('disabled')).toBeUndefined()
    expect(wrapper.text()).not.toContain('策略有未保存修改')
  })

  it.each([FoundationFilesPage, FoundationJobsPage])(
    '上传失败后文件选择器已复位，同文件只能由用户重新选择触发',
    async (component) => {
      const upload = vi.fn().mockRejectedValue(new Error('上传已拒绝'))
      const wrapper = await render(component, {
        request: filesAndJobsRequest(upload),
        apiBase: '/tenant/foundation',
        permissions: [
          'foundation.files.read',
          'foundation.files.upload',
          'foundation.jobs.read',
          'foundation.jobs.create'
        ]
      })
      if (component === FoundationJobsPage) {
        wrapper.getComponent(ElSelect).vm.$emit('update:modelValue', 'members')
        await flushPromises()
      }
      const input = wrapper.get<HTMLInputElement>('input[type=file]')
      const reset = chooseFile(input.element)
      await input.trigger('change')
      await flushPromises()
      expect(reset).toHaveBeenCalledWith('')
      expect(upload).toHaveBeenCalledTimes(1)
      await flushPromises()
      expect(upload).toHaveBeenCalledTimes(1)
      await input.trigger('change')
      await flushPromises()
      expect(upload).toHaveBeenCalledTimes(2)
      expect(reset).toHaveBeenCalledTimes(2)
      reset.mockRestore()
    }
  )
})
