import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createClient } from '../client'
import WorkspaceStatus from './WorkspaceStatus.vue'
import { ChatMessage } from './controls'

const mounted: VueWrapper[] = []
afterEach(() => {
  ChatMessage.closeAll()
  for (const wrapper of mounted.splice(0)) wrapper.unmount()
})

/** 以已创建工作区构造服务事实，测试不通过状态读取创建文件或工作区。 */
function workspace(sessionId: string, isChild = false, revision = 3) {
  return {
    sessionId,
    isChild,
    status: {
      workspaceId: `private-workspace-${sessionId}`,
      revision,
      backupRevision: 2,
      localAvailable: true,
      phase: 'ready',
      backupError: null as string | null,
      lastBackupAt: '2026-10-10T03:00:00Z'
    }
  }
}

describe('会话工作区摘要', () => {
  it('首次读取失败仍显示工作区和重试入口，成功读取空列表后隐藏', async () => {
    const client = createClient('http://localhost', { mode: 'token', token: 'test' })
    const read = vi
      .spyOn(client, 'json')
      .mockRejectedValueOnce(new Error('private diagnostic'))
      .mockResolvedValue({ workspaces: [] })
    const wrapper = mount(WorkspaceStatus, { props: { client, session: 'current' } })
    mounted.push(wrapper)
    await flushPromises()
    expect(wrapper.find('[aria-label="工作区状态"]').exists()).toBe(true)
    expect(wrapper.get('button').text()).toContain('刷新状态')
    expect(document.body.textContent).toContain('工作区状态暂时无法读取')
    expect(document.body.textContent).not.toContain('private diagnostic')
    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(read).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[aria-label="工作区状态"]').exists()).toBe(false)
    expect(document.body.textContent).not.toContain('工作区状态暂时无法读取')
  })

  it('备份时间使用固定北京时间格式，保留接口 datetime 原值', async () => {
    const client = createClient('http://localhost', { mode: 'token', token: 'test' })
    const primary = workspace('current')
    primary.status.lastBackupAt = '2026-01-01T19:04:05Z'
    vi.spyOn(client, 'json').mockResolvedValue({ workspaces: [primary] })
    const wrapper = mount(WorkspaceStatus, { props: { client, session: 'current' } })
    mounted.push(wrapper)
    await flushPromises()
    expect(wrapper.get('time').text()).toBe('备份时间 2026-01-02 03:04:05')
    expect(wrapper.get('time').attributes('datetime')).toBe(primary.status.lastBackupAt)
  })

  it.each(['invalid', '2026-02-30T03:04:05Z'])(
    '已有无效备份时间展示占位符，不输出无效 datetime：%s',
    async (lastBackupAt) => {
      const client = createClient('http://localhost', { mode: 'token', token: 'test' })
      const primary = workspace('current')
      primary.status.lastBackupAt = lastBackupAt
      vi.spyOn(client, 'json').mockResolvedValue({ workspaces: [primary] })
      const wrapper = mount(WorkspaceStatus, { props: { client, session: 'current' } })
      mounted.push(wrapper)
      await flushPromises()
      expect(wrapper.get('time').text()).toBe('备份时间 —')
      expect(wrapper.get('time').attributes('datetime')).toBeUndefined()
    }
  )

  it('未提供备份时间时继续隐藏时间字段，不新增占位信息', async () => {
    const client = createClient('http://localhost', { mode: 'token', token: 'test' })
    const primary = workspace('current')
    primary.status.lastBackupAt = ''
    vi.spyOn(client, 'json').mockResolvedValue({ workspaces: [primary] })
    const wrapper = mount(WorkspaceStatus, { props: { client, session: 'current' } })
    mounted.push(wrapper)
    await flushPromises()
    expect(wrapper.find('time').exists()).toBe(false)
  })

  it('没有会话时不请求，功能关闭或未创建时不显示', async () => {
    const client = createClient('http://localhost', { mode: 'token', token: 'test' })
    const read = vi.spyOn(client, 'json').mockResolvedValue({ workspaces: [] })
    const wrapper = mount(WorkspaceStatus, { props: { client, session: '' } })
    mounted.push(wrapper)
    expect(read).not.toHaveBeenCalled()
    await wrapper.setProps({ session: 'current' })
    await flushPromises()
    expect(read).toHaveBeenCalledWith(
      '/chat/sessions/workspace?sessionId=current',
      'GET',
      undefined,
      expect.any(AbortSignal)
    )
    expect(wrapper.find('[aria-label="工作区状态"]').exists()).toBe(false)
  })

  it('展示主和授权子工作区保存/备份事实，不展示原始错误或标识', async () => {
    const client = createClient('http://localhost', { mode: 'token', token: 'test' })
    const primary = workspace('private-session')
    primary.status.phase = 'backing_up'
    const child = workspace('delegation:private-child', true)
    child.status.localAvailable = false
    child.status.phase = 'archived'
    child.status.backupError = 'SECRET_INTERNAL_PATH_AND_REASON'
    vi.spyOn(client, 'json').mockResolvedValue({ workspaces: [primary, child] })
    const wrapper = mount(WorkspaceStatus, { props: { client, session: 'current' } })
    mounted.push(wrapper)
    await flushPromises()
    expect(wrapper.text()).toContain('当前会话')
    expect(wrapper.text()).toContain('协作工作区 1')
    expect(wrapper.text()).toContain('本地可用')
    expect(wrapper.text()).toContain('已归档')
    expect(wrapper.text()).toContain('已保存修订 3')
    expect(wrapper.text()).toContain('已备份到修订 2')
    expect(wrapper.text()).toContain('备份同步中')
    expect(wrapper.text()).toContain('备份失败，等待重试')
    expect(wrapper.text()).not.toContain('private-')
    expect(wrapper.text()).not.toContain(child.status.backupError)
  })

  it('明确区分恢复与归档，不把本地不可用直接显示成已归档', async () => {
    const client = createClient('http://localhost', { mode: 'token', token: 'test' })
    const restoring = workspace('restoring')
    restoring.status.phase = 'restoring'
    restoring.status.localAvailable = false
    vi.spyOn(client, 'json').mockResolvedValue({ workspaces: [restoring] })
    const wrapper = mount(WorkspaceStatus, { props: { client, session: 'current' } })
    mounted.push(wrapper)
    await flushPromises()
    expect(wrapper.text()).toContain('正在恢复')
    expect(wrapper.text()).not.toContain('已归档')
  })

  it('切换会话后取消旧请求，迟到响应不能覆盖新会话；完结标记只触发一次读取', async () => {
    const client = createClient('http://localhost', { mode: 'token', token: 'test' })
    let finishOld!: (value: unknown) => void
    const pending = new Promise<unknown>((resolve) => {
      finishOld = resolve
    })
    const read = vi
      .spyOn(client, 'json')
      .mockImplementationOnce(() => pending)
      .mockResolvedValue({ workspaces: [workspace('new', false, 4)] })
    const wrapper = mount(WorkspaceStatus, {
      props: { client, session: 'old', refreshKey: 'initial' }
    })
    mounted.push(wrapper)
    const oldSignal = read.mock.calls[0]![3]!
    await wrapper.setProps({ session: 'new' })
    await flushPromises()
    expect(oldSignal.aborted).toBe(true)
    expect(wrapper.text()).toContain('已保存修订 4')
    finishOld({ workspaces: [workspace('old', false, 99)] })
    await flushPromises()
    expect(wrapper.text()).not.toContain('修订 99')
    await wrapper.setProps({ refreshKey: 'completed' })
    await flushPromises()
    expect(read).toHaveBeenCalledTimes(3)
  })

  it('已展示状态的刷新失败保留事实并给出安全提示，手动刷新可恢复', async () => {
    const client = createClient('http://localhost', { mode: 'token', token: 'test' })
    const read = vi
      .spyOn(client, 'json')
      .mockResolvedValueOnce({ workspaces: [workspace('current')] })
      .mockRejectedValueOnce(new Error('SECRET_ERROR'))
      .mockResolvedValue({ workspaces: [workspace('current', false, 2)] })
    const wrapper = mount(WorkspaceStatus, { props: { client, session: 'current' } })
    mounted.push(wrapper)
    await flushPromises()
    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('已保存修订 3')
    expect(document.body.textContent).toContain('工作区状态暂时无法读取')
    expect(wrapper.text()).not.toContain('SECRET_ERROR')
    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('已保存修订 2')
    expect(wrapper.text()).toContain('已备份')
    expect(document.body.textContent).not.toContain('工作区状态暂时无法读取')
    expect(read).toHaveBeenCalledTimes(3)
  })
})
