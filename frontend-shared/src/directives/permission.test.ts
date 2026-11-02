import { defineComponent, nextTick, ref } from 'vue'
import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import { createPermissionDirective } from './permission'

describe('current action permission directive', () => {
  it('按当前显式平台与机构动作显示，撤权立即移除且角色名不能放行', async () => {
    const permissions = ref(['admin'])
    const wrapper = mount(
      defineComponent({
        template: `<div><button v-permission="['platform.agent_definitions.publish']">发布</button><button v-permission="['tenant.agent_deployments.switch_authorization']">切换授权</button></div>`
      }),
      { global: { directives: { permission: createPermissionDirective(() => permissions) } } }
    )
    expect(wrapper.findAll('button')).toHaveLength(0)
    permissions.value = [
      'platform.agent_definitions.publish',
      'tenant.agent_deployments.switch_authorization'
    ]
    await nextTick()
    expect(wrapper.findAll('button').map((button) => button.text())).toEqual(['发布', '切换授权'])
    permissions.value = ['tenant.agent_deployments.switch_authorization']
    await nextTick()
    expect(wrapper.findAll('button').map((button) => button.text())).toEqual(['切换授权'])
    wrapper.unmount()
  })
  it('removes on revocation and restores the same button and event state on grant', async () => {
    const permissions = ref<string[]>([]),
      clicks = ref(0)
    const component = defineComponent({
      setup: () => ({ clicks }),
      template: `<div><button v-permission="['foundation.agents.update']" @click="clicks++">保存 {{ clicks }}</button></div>`
    })
    const wrapper = mount(component, {
      global: { directives: { permission: createPermissionDirective(() => permissions) } }
    })
    expect(wrapper.find('button').exists()).toBe(false)
    permissions.value = ['foundation.agents.update']
    await nextTick()
    await wrapper.get('button').trigger('click')
    expect(wrapper.get('button').text()).toBe('保存 1')
    const element = wrapper.get('button').element
    permissions.value = []
    await nextTick()
    expect(wrapper.find('button').exists()).toBe(false)
    permissions.value = ['foundation.agents.update']
    await nextTick()
    expect(wrapper.get('button').element).toBe(element)
    expect(wrapper.get('button').text()).toBe('保存 1')
    wrapper.unmount()
  })
  it('reacts to a changed create/update action and refuses legacy role or empty bindings', async () => {
    const permissions = ref(['foundation.agents.create', 'admin']),
      required = ref(['foundation.agents.create'])
    const wrapper = mount(
      defineComponent({
        setup: () => ({ required }),
        template: `<div><button v-permission="required">保存</button></div>`
      }),
      { global: { directives: { permission: createPermissionDirective(() => permissions) } } }
    )
    expect(wrapper.find('button').exists()).toBe(true)
    required.value = ['foundation.agents.update']
    await nextTick()
    expect(wrapper.find('button').exists()).toBe(false)
    permissions.value.push('foundation.agents.update')
    await nextTick()
    expect(wrapper.find('button').exists()).toBe(true)
    required.value = ['admin']
    await nextTick()
    expect(wrapper.find('button').exists()).toBe(false)
    required.value = []
    await nextTick()
    expect(wrapper.find('button').exists()).toBe(false)
    wrapper.unmount()
  })
  it('requires every action for a combined create-and-update operation', async () => {
    const permissions = ref(['foundation.knowledge.create'])
    const wrapper = mount(
      defineComponent({
        template: `<div><button v-permission.all="['foundation.knowledge.create', 'foundation.knowledge.update']">保存</button></div>`
      }),
      { global: { directives: { permission: createPermissionDirective(() => permissions) } } }
    )
    expect(wrapper.find('button').exists()).toBe(false)
    permissions.value.push('foundation.knowledge.update')
    await nextTick()
    expect(wrapper.find('button').exists()).toBe(true)
    permissions.value = ['foundation.knowledge.update']
    await nextTick()
    expect(wrapper.find('button').exists()).toBe(false)
    wrapper.unmount()
  })
})
