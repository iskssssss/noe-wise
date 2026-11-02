import { flushPromises, mount } from '@vue/test-utils'
import { computed, defineComponent, effectScope, h, nextTick, ref, withDirectives } from 'vue'
import ElementPlus, { ElSelect } from 'element-plus'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useResource, useTask } from './tasks'
import EntitySelect from './EntitySelect.vue'
import CapabilityRequirementFields from './CapabilityRequirementFields.vue'
import { vDescribedBy } from '../directives/describedBy'
import { createPermissionDirective } from '../directives/permission'
import { schemaFieldHelp } from '../utils/formHelp'
import type { SkillRequirement } from '../capabilities/skillDrafts'

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (value: unknown) => void
  const promise = new Promise<T>((complete, fail) => {
    resolve = complete
    reject = fail
  })
  return { promise, resolve, reject }
}
afterEach(() => {
  document.body.innerHTML = ''
  vi.restoreAllMocks()
})

describe('复杂后台共享状态与schema消费', () => {
  it('137条跨版本目录连续翻3页，旧失败不清最新loading且最新20行身份不丢', async () => {
    const catalog = Array.from({ length: 137 }, (_, i) => ({
      id: String(9007199254740993n + BigInt(i)),
      tenant: i % 2 ? 'B' : 'A',
      revision: (i % 5) + 1,
      bindings: [{ version: `v${(i % 4) + 1}`, roles: ['reader', 'auditor'] }]
    }))
    const scope = effectScope(),
      gates = [deferred<typeof catalog>(), deferred<typeof catalog>(), deferred<typeof catalog>()]
    let calls = 0
    const signals: AbortSignal[] = []
    const resource = scope.run(() =>
      useResource((signal) => {
        signals.push(signal)
        return gates[calls++]!.promise
      })
    )!
    try {
      const old = resource.reload(),
        middle = resource.reload(),
        latest = resource.reload()
      gates[0]!.reject(new Error('第一页已过期'))
      await old
      expect(resource.loading.value).toBe(true)
      expect(resource.error.value).toBe('')
      expect(signals.slice(0, 2).every((signal) => signal.aborted)).toBe(true)
      gates[2]!.resolve(catalog.slice(40, 60))
      await latest
      gates[1]!.resolve(catalog.slice(20, 40))
      await middle
      expect(resource.data.value).toEqual(catalog.slice(40, 60))
      expect(resource.data.value![0]!.id).toBe('9007199254741033')
      expect(resource.loading.value).toBe(false)
    } finally {
      scope.stop()
    }
  })

  it('嵌套绑定CAS失败只提交一次，保留复杂草稿；恢复读取不会自动重放写入', async () => {
    const scope = effectScope(),
      task = scope.run(useTask)!
    const draft = {
      expected_revision: 7,
      bindings: Array.from({ length: 5 }, (_, i) => ({
        slot: `s-${i}`,
        capability: `c-${i}`,
        version: `v-${i % 3}`,
        arguments: { filters: [{ field: '部门', values: ['研发', '审计'] }], threshold: 0 }
      }))
    }
    const original = structuredClone(draft),
      gate = deferred<unknown>(),
      send = vi.fn(() => gate.promise)
    try {
      const first = task.run(() => send())
      await task.run(() => send())
      gate.reject(new Error('409: expected_revision=7 current_revision=8'))
      await first
      expect(send).toHaveBeenCalledTimes(1)
      expect(task.error.value).toContain('current_revision=8')
      expect(draft).toEqual(original)
      expect(task.busy.value).toBe(false)
      expect(send).toHaveBeenCalledTimes(1)
    } finally {
      scope.stop()
    }
  })

  it('73个远程同名选项保留超出首页的已选ID，连续搜索丢弃迟到失败', async () => {
    const initial = deferred<Array<{ id: string; label: string }>>()
    const latest = deferred<Array<{ id: string; label: string }>>()
    let calls = 0
    const options = Array.from({ length: 73 }, (_, i) => ({
      id: `tenant-${i}`,
      label: `机构同名-${i % 4}`,
      description: `角色-${i % 3} / 版本-${i % 5}`
    }))
    const wrapper = mount(EntitySelect, {
      props: {
        modelValue: 'tenant-72',
        resolve: async () => options[72],
        load: () => (calls++ ? latest.promise : initial.promise)
      },
      global: { plugins: [ElementPlus] }
    })
    try {
      await flushPromises()
      const pending = wrapper.getComponent(ElSelect).props('remoteMethod')!('机构同名-1')
      latest.resolve(options.filter((option) => option.label === '机构同名-1'))
      await pending
      initial.reject(new Error('旧机构范围读取失败'))
      await flushPromises()
      const ids = wrapper
        .findAllComponents({ name: 'ElOption' })
        .map((option) => option.props('value'))
      expect(ids).toContain('tenant-72')
      expect(ids).toHaveLength(19)
      expect(ids).not.toContain('tenant-70')
      expect(wrapper.text()).not.toContain('旧机构范围读取失败')
      expect(wrapper.getComponent(ElSelect).props('loading')).toBe(false)
    } finally {
      wrapper.unmount()
    }
  })

  it('4种能力8行依赖校验保留身份，换其中1行类型不污染其他7行', async () => {
    const types = ['TOOL', 'SKILL', 'WORKFLOW', 'KNOWLEDGE_SOURCE'] as const
    const rows: SkillRequirement[] = Array.from({ length: 8 }, (_, i) => ({
      type: types[i % 4]!,
      capabilityId: `definition-${i}`,
      versionId: `stale-v${i}`
    }))
    const wrapper = mount(CapabilityRequirementFields, {
      props: {
        modelValue: rows,
        loadOptions: async () =>
          rows.map((row) => ({
            id: row.capabilityId,
            name: `能力-${row.capabilityId}`,
            versionId: `${row.capabilityId}-published-5`,
            versionKey: '发布修订5'
          }))
      },
      global: { plugins: [ElementPlus] }
    })
    try {
      await flushPromises()
      await (wrapper.vm as unknown as { validate: () => Promise<void> }).validate()
      expect(wrapper.emitted('update:modelValue')).toBeUndefined()
      wrapper.getComponent(ElSelect).vm.$emit('update:modelValue', 'WORKFLOW')
      const changed = wrapper.emitted('update:modelValue')!.at(-1)![0] as SkillRequirement[]
      expect(changed[0]).toEqual({ type: 'WORKFLOW', capabilityId: '', versionId: '' })
      expect(changed.slice(1)).toEqual(rows.slice(1))
    } finally {
      wrapper.unmount()
    }
  })

  it('schema数组增删与身份变化保持唯一可见帮助，保留既有字段错误关联', async () => {
    const identity = ref('tenant-A'),
      rows = ref([0, 1, 2, 3]),
      invalid = ref(false)
    const Host = defineComponent({
      setup: () => () =>
        h(
          'form',
          rows.value.map((id) =>
            h('section', { key: id }, [
              withDirectives(
                h('input', { 'aria-describedby': invalid.value ? `error-${id}` : undefined }),
                [[vDescribedBy, `${identity.value}-help-${id}`]]
              ),
              h(
                'p',
                { id: `${identity.value}-help-${id}` },
                schemaFieldHelp(
                  { type: 'integer', minimum: 0, maximum: 9, description: `嵌套数组第${id}项` },
                  true
                )
              ),
              ...(invalid.value ? [h('p', { id: `error-${id}` }, '范围错误')] : [])
            ])
          )
        )
    })
    const wrapper = mount(Host, { attachTo: document.body })
    try {
      rows.value = [0, 2, 4, 5, 6]
      identity.value = 'tenant-B'
      invalid.value = true
      await nextTick()
      expect(wrapper.findAll('input')).toHaveLength(5)
      for (const input of wrapper.findAll('input')) {
        const ids = input.attributes('aria-describedby')!.split(/\s+/)
        expect(ids).toHaveLength(2)
        expect(ids[1]).toContain('tenant-B')
        for (const id of ids) expect(wrapper.findAll(`[id="${id}"]`)).toHaveLength(1)
      }
      expect(wrapper.find('[id^="tenant-A"]').exists()).toBe(false)
      expect(wrapper.text()).toContain('最小值 0')
    } finally {
      wrapper.unmount()
    }
  })

  it('多角色显式动作权限增减独立，父管理员角色不放行及恢复旧DOM草稿', async () => {
    const permissions = ref([
      'foundation.agents.read',
      'foundation.knowledge.read',
      'foundation.memory.read'
    ])
    const directive = createPermissionDirective(() => computed(() => permissions.value))
    const wrapper = mount(
      defineComponent({
        setup: () => () =>
          h('div', [
            withDirectives(h('button', '发布'), [[directive, ['foundation.agents.publish']]]),
            withDirectives(h('input', { value: '嵌套草稿-版本7' }), [
              [
                directive,
                ['foundation.knowledge.read', 'foundation.knowledge.update'],
                undefined,
                { all: true }
              ]
            ])
          ])
      }),
      { attachTo: document.body }
    )
    try {
      expect(wrapper.find('button').exists()).toBe(false)
      expect(wrapper.find('input').exists()).toBe(false)
      permissions.value.push('foundation.knowledge.update', 'foundation.agents.publish')
      await nextTick()
      expect(wrapper.get('input').element.value).toBe('嵌套草稿-版本7')
      expect(wrapper.get('button').text()).toBe('发布')
      permissions.value = ['admin']
      await nextTick()
      expect(wrapper.findAll('button,input')).toHaveLength(0)
    } finally {
      wrapper.unmount()
    }
  })
})
