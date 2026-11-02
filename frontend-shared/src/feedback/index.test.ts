import { afterEach, describe, expect, it, vi } from 'vitest'
import { ElMessage, ElMessageBox } from 'element-plus'
import { clearFeedback, confirm, message } from './index'

vi.mock('element-plus', () => ({
  ElButton: {},
  ElCollapse: {},
  ElCollapseItem: {},
  ElLoading: {},
  ElMessage: Object.assign(
    vi.fn(() => ({ close: vi.fn() })),
    { closeAll: vi.fn() }
  ),
  ElNotification: Object.assign(
    vi.fn(() => ({ close: vi.fn() })),
    { closeAll: vi.fn() }
  ),
  ElMessageBox: { confirm: vi.fn(), alert: vi.fn(), close: vi.fn() }
}))
afterEach(() => {
  clearFeedback()
  vi.clearAllMocks()
})

describe('统一业务反馈', () => {
  it('确认中的补充信息就地展开，不叠加另一层弹窗或自动确认', async () => {
    vi.mocked(ElMessageBox.confirm).mockRejectedValueOnce('cancel')
    expect(
      await confirm({ title: '核对发布', content: '发布当前内容？', details: '仅供核对的版本信息' })
    ).toBe(false)
    const value = vi.mocked(ElMessageBox.confirm).mock.calls[0]![0]
    expect(JSON.stringify(value)).toContain('details')
    expect(JSON.stringify(value)).toContain('summary')
    expect(JSON.stringify(value)).toContain('仅供核对的版本信息')
    expect(ElMessageBox.alert).not.toHaveBeenCalled()
  })
  it('顶部只呈现简短原因与排查入口，诊断放在独立只读弹层', async () => {
    message.error('暂时无法读取最新内容', { details: 'trace-id: example' })
    const content = vi.mocked(ElMessage).mock.calls[0]![0] as {
      message: {
        children: Array<{
          type: unknown
          props?: { onClick: () => Promise<void> }
          children?: unknown
        }>
      }
    }
    const diagnostic = content.message.children[1]!
    expect(diagnostic.type).not.toBe('pre')
    expect(JSON.stringify(content.message)).not.toContain('trace-id: example')
    vi.mocked(ElMessageBox.alert).mockResolvedValueOnce('confirm')
    await diagnostic.props!.onClick()
    expect(ElMessageBox.alert).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'div' }),
      '问题排查信息',
      expect.objectContaining({ confirmButtonText: '关闭' }),
      undefined
    )
    expect(JSON.stringify(vi.mocked(ElMessageBox.alert).mock.calls[0]![0])).toContain(
      'trace-id: example'
    )
    expect(ElMessageBox.confirm).not.toHaveBeenCalled()
  })
  it('同文案去重，不同消息最多同时保留三条，错误保持可阅读', () => {
    message.success('已保存')
    message.success('已保存')
    expect(ElMessage).toHaveBeenCalledTimes(1)
    message.info('已开始下载')
    message.warning('请先保存')
    const oldest = vi.mocked(ElMessage).mock.results[0]!.value
    message.error('暂时无法读取')
    expect(oldest.close).toHaveBeenCalledOnce()
    expect(vi.mocked(ElMessage).mock.calls[3]![0]).toMatchObject({ duration: 0, showClose: true })
  })
  it('确认取消只返回false，保留中文动作与禁遮罩关闭', async () => {
    vi.mocked(ElMessageBox.confirm).mockRejectedValueOnce('cancel')
    expect(
      await confirm({
        title: '删除渠道',
        content: '删除测试渠道？',
        confirmText: '删除',
        danger: true
      })
    ).toBe(false)
    expect(ElMessageBox.confirm).toHaveBeenCalledWith(
      '删除测试渠道？',
      '删除渠道',
      expect.objectContaining({
        confirmButtonText: '删除',
        cancelButtonText: '取消',
        closeOnClickModal: false,
        autofocus: false
      }),
      undefined
    )
  })
  it('普通确认保留Element Plus默认自动聚焦', async () => {
    vi.mocked(ElMessageBox.confirm).mockResolvedValueOnce('confirm')
    expect(await confirm({ title: '继续', content: '继续查看详情？', danger: false })).toBe(true)
    expect(ElMessageBox.confirm).toHaveBeenCalledWith(
      '继续查看详情？',
      '继续',
      expect.objectContaining({ autofocus: true }),
      undefined
    )
  })
  it('确认等待期间重复点击不共享成功结果，避免重复写入', async () => {
    let resolve!: (value: 'confirm') => void
    vi.mocked(ElMessageBox.confirm).mockImplementationOnce(
      () =>
        new Promise((done) => {
          resolve = done
        })
    )
    const first = confirm({ title: '发布', content: '发布当前版本？' })
    expect(await confirm({ title: '发布', content: '发布当前版本？' })).toBe(false)
    resolve('confirm')
    expect(await first).toBe(true)
    expect(ElMessageBox.confirm).toHaveBeenCalledOnce()
  })
  it('身份清理终止尚未完成的确认', async () => {
    vi.mocked(ElMessageBox.confirm).mockImplementationOnce(() => new Promise(() => {}))
    const pending = confirm({ title: '停用', content: '停用当前机构？' })
    clearFeedback()
    expect(await pending).toBe(false)
  })
})
