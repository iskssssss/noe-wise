import { afterEach, expect, it } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { ElButton, ElInput, ElSelect, ElTable } from 'element-plus'
import ToolOnboarding from './ToolOnboarding.vue'

let wrapper: VueWrapper | undefined
const alpha = { name: 'read_alpha', description: '读取资料' }
const beta = { name: 'read_beta', description: '读取目录' }
function button(text: string) {
  return wrapper!.findAllComponents(ElButton).find((item) => item.text() === text)!
}
function page() {
  wrapper = mount(ToolOnboarding, {
    props: {
      connectors: [{ instance_id: 'server', name: '资料服务' }],
      connectorId: 'server',
      tools: [alpha, beta],
      loading: false,
      busy: false,
      stale: false,
      hasCatalog: true
    }
  })
  return wrapper
}
afterEach(() => wrapper?.unmount())

it('shows unsupported names and prevents selecting them', async () => {
  page()
  await wrapper!.setProps({
    tools: [alpha, { name: 'bad name', unsupported_reason: '名称不受支持，请联系工具提供方' }]
  })
  await flushPromises()
  const rows = wrapper!.findAll('.el-table tbody tr')
  expect(rows[1].text()).toContain('名称不受支持，请联系工具提供方')
  expect(rows[1].find<HTMLInputElement>('input[type="checkbox"]').element.disabled).toBe(true)
  expect(rows[0].find<HTMLInputElement>('input[type="checkbox"]').element.disabled).toBe(false)
})

it('按标识区分同名连接器并保留原始连接器身份', async () => {
  page()
  await wrapper!.setProps({
    connectors: [
      { instance_id: 'east', name: '资料服务' },
      { instance_id: 'west', name: '资料服务' }
    ]
  })
  const select = wrapper!.findComponent(ElSelect)
  ;(select.props('filterMethod') as (query: string) => void)('west')
  await flushPromises()
  expect(
    select.findAllComponents({ name: 'ElOption' }).map((option) => option.props('value'))
  ).toEqual(['west'])
  select.vm.$emit('update:modelValue', 'west')
  expect(wrapper!.emitted('update:connectorId')).toEqual([['west']])
})

it('区分首次读取、成功空目录和读取失败', async () => {
  page()
  await wrapper!.setProps({ tools: [], hasCatalog: false, stale: false })
  expect(wrapper!.text()).toContain('选择连接器并读取工具后')
  await wrapper!.setProps({ hasCatalog: true })
  expect(wrapper!.text()).toContain('此连接器未返回工具')
  await wrapper!.setProps({ hasCatalog: false, stale: true })
  expect(wrapper!.text()).toContain('工具读取失败，请重新读取')
  expect(button('确认接入并授权调用 0 项工具').props('disabled')).toBe(true)
})

it('confirms selected original tool names in one action', async () => {
  page()
  expect(wrapper!.text()).toContain('已配置使用这些工具的智能体可自动调用')
  expect(wrapper!.text()).toContain('工具可能读写外部数据，请确认连接器与所选工具可信')
  expect(wrapper!.emitted('confirm')).toBeUndefined()
  await button('读取工具').trigger('click')
  expect(wrapper!.emitted('discover')).toHaveLength(1)
  expect(wrapper!.emitted('confirm')).toBeUndefined()
  wrapper!.findComponent(ElTable).vm.$emit('selection-change', [alpha, beta])
  await wrapper!.vm.$nextTick()
  await button('确认接入并授权调用 2 项工具').trigger('click')
  expect(wrapper!.emitted('confirm')).toEqual([[['read_alpha', 'read_beta']]])
})

it('切换连接器后需要重新选择', async () => {
  page()
  wrapper!.findComponent(ElTable).vm.$emit('selection-change', [alpha])
  await wrapper!.vm.$nextTick()
  wrapper!.findComponent(ElSelect).vm.$emit('update:modelValue', 'other')
  expect(wrapper!.emitted('update:connectorId')).toEqual([['other']])
  await wrapper!.setProps({ connectorId: 'other', tools: [] })
  expect(button('确认接入并授权调用 0 项工具').props('disabled')).toBe(true)
})

it('同一连接器重新读取后保留可用选择，移除失效工具并保留搜索', async () => {
  page()
  await flushPromises()
  const checkboxes = wrapper!.findAll<HTMLInputElement>('.el-table tbody input[type="checkbox"]')
  await checkboxes[0]!.setValue(true)
  await checkboxes[1]!.setValue(true)
  wrapper!.findComponent(ElInput).vm.$emit('update:modelValue', '资料')
  await flushPromises()
  await wrapper!.setProps({
    tools: [
      { ...alpha, description: '读取资料新版' },
      { ...beta, unsupported_reason: '已停用' }
    ]
  })
  await flushPromises()
  expect(wrapper!.findComponent(ElInput).props('modelValue')).toBe('资料')
  expect(wrapper!.text()).toContain('read_beta')
  expect(wrapper!.text()).toContain('已移出选择')
  expect(button('确认接入并授权调用 1 项工具').props('disabled')).toBe(false)
  expect(
    wrapper!.find<HTMLInputElement>('.el-table tbody input[type="checkbox"]').element.checked
  ).toBe(true)
  await button('确认接入并授权调用 1 项工具').trigger('click')
  expect(wrapper!.emitted('confirm')).toEqual([[['read_alpha']]])
})

it('刷新后保留当前搜索隐藏的已选工具', async () => {
  page()
  await flushPromises()
  const boxes = wrapper!.findAll<HTMLInputElement>('.el-table tbody input[type="checkbox"]')
  await boxes[0]!.setValue(true)
  await boxes[1]!.setValue(true)
  wrapper!.findComponent(ElInput).vm.$emit('update:modelValue', '资料')
  await flushPromises()
  await wrapper!.setProps({ tools: [{ ...alpha }, { ...beta }] })
  await flushPromises()
  wrapper!.findComponent(ElInput).vm.$emit('update:modelValue', '')
  await flushPromises()
  expect(
    wrapper!
      .findAll<HTMLInputElement>('.el-table tbody input[type="checkbox"]')
      .map((item) => item.element.checked)
  ).toEqual([true, true])
  await button('确认接入并授权调用 2 项工具').trigger('click')
  expect(wrapper!.emitted('confirm')).toEqual([[['read_alpha', 'read_beta']]])
})

it('旧连接器不在当前目录时不能读取或接入旧工具', async () => {
  page()
  wrapper!.findComponent(ElTable).vm.$emit('selection-change', [alpha])
  await wrapper!.setProps({ connectors: [{ instance_id: 'other', name: '其他服务' }] })
  expect(button('读取工具').props('disabled')).toBe(true)
  expect(button('确认接入并授权调用 1 项工具').props('disabled')).toBe(true)
  expect(wrapper!.text()).toContain('所选连接器已不在当前目录中')
  expect(wrapper!.emitted('confirm')).toBeUndefined()
})

it('preserves selection across search filters and submits the complete original names', async () => {
  page()
  await flushPromises()
  const table = wrapper!.find('.el-table')
  await table.find('tbody input[type="checkbox"]').setValue(true)
  await wrapper!.vm.$nextTick()
  const search = wrapper!.findComponent(ElInput)
  search.vm.$emit('update:modelValue', '目录')
  await wrapper!.vm.$nextTick()
  expect(table.findAll('tbody tr').map((row) => row.text())).toEqual([
    expect.stringContaining('read_beta')
  ])
  await table.find('tbody input[type="checkbox"]').setValue(true)
  await wrapper!.vm.$nextTick()
  search.vm.$emit('update:modelValue', 'missing')
  await wrapper!.vm.$nextTick()
  expect(table.findAll('tbody tr')).toHaveLength(0)
  expect(table.text()).toContain('没有匹配')
  await button('确认接入并授权调用 2 项工具').trigger('click')
  expect(wrapper!.emitted('confirm')).toEqual([[['read_alpha', 'read_beta']]])
  search.vm.$emit('update:modelValue', '')
  await wrapper!.vm.$nextTick()
  expect(table.findAll('tbody tr').map((row) => row.text())).toEqual([
    expect.stringContaining('read_alpha'),
    expect.stringContaining('read_beta')
  ])
  expect(
    table
      .findAll<HTMLInputElement>('tbody input[type="checkbox"]')
      .map((checkbox) => checkbox.element.checked)
  ).toEqual([true, true])
})
