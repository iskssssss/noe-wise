import { describe, expect, it, vi } from 'vitest'
import { createWorkflowDraftApi } from './workflowDrafts'

const summary = {
  definition_version_id: '44',
  definition_version: 2,
  capability_version_id: '77',
  created_at: '2026-10-29T00:00:00Z',
  current: true
}
describe('工作流发布摘要与按版本详情读取', () => {
  it('组合命令不自动重发，原回执以精确身份读取', async () => {
    const get = vi.fn().mockResolvedValue(undefined)
    const post = vi.fn().mockResolvedValue({ status: 'PROCESSING' })
    const api = createWorkflowDraftApi({ get, post, put: vi.fn() })
    const command = {
      request_id: 'request-1',
      workflow_id: 'flow',
      revision: 2,
      definition: {},
      layout: {},
      viewport: {}
    }
    await api.saveAndPublish(command)
    expect(post).toHaveBeenCalledWith('/builder/workflows/save-and-publish', command, {
      retryOnUnauthorized: false
    })
    expect(await api.saveAndPublishResult('flow/1', 'request+1')).toBeUndefined()
    expect(get).toHaveBeenCalledWith(
      '/builder/workflows/save-and-publish/result?workflow_id=flow%2F1&request_id=request%2B1'
    )
  })

  it('组合发布拒绝时保留服务端问题的位置供页面定位', async () => {
    const post = vi.fn().mockResolvedValue({
      request_id: 'r',
      workflow_id: 'flow',
      status: 'REJECTED',
      error_detail: {
        message: '校验失败',
        problems: [
          {
            code: 'WF',
            severity: 'error',
            object_ref: 'node:start',
            field_path: 'type',
            message: '节点错误'
          }
        ]
      }
    })
    const api = createWorkflowDraftApi({ get: vi.fn(), post, put: vi.fn() })
    const result = await api.saveAndPublish({
      request_id: 'r',
      workflow_id: 'flow',
      revision: 1,
      definition: {},
      layout: {},
      viewport: {}
    })
    expect(result?.error_detail).toMatchObject({
      problems: [{ objectRef: 'node:start', fieldPath: 'type' }]
    })
  })
  it('历史只映射摘要，详情按工作流及精确版本读取完整定义', async () => {
    const get = vi
      .fn()
      .mockResolvedValueOnce({ items: [summary] })
      .mockResolvedValueOnce({
        ...summary,
        workflow_id: 'flow/中文',
        definition: { entry: 'start', nodes: [] }
      })
    const api = createWorkflowDraftApi({ get, post: vi.fn(), put: vi.fn() })
    const history = await api.history('flow/中文')
    expect(get).toHaveBeenNthCalledWith(
      1,
      '/builder/workflows/history?workflowId=flow%2F%E4%B8%AD%E6%96%87'
    )
    expect(history).toEqual([
      {
        definitionVersionId: '44',
        definitionVersion: 2,
        capabilityVersionId: '77',
        createdAt: summary.created_at,
        current: true
      }
    ])
    expect(history[0]).not.toHaveProperty('definition')
    const detail = await api.publicationDetail('flow/中文', '44')
    expect(get).toHaveBeenNthCalledWith(
      2,
      '/builder/workflows/history/detail?workflowId=flow%2F%E4%B8%AD%E6%96%87&definitionVersionId=44'
    )
    expect(detail).toEqual({
      ...history[0],
      workflowId: 'flow/中文',
      definition: { entry: 'start', nodes: [] }
    })
  })

  it('安装实例使用精确字符串主键，拒绝数字形式防止精度丢失', async () => {
    const get = vi.fn().mockResolvedValueOnce({
      total: 1,
      items: [
        {
          id: '9007199254740999',
          instance_id: 'flow_1',
          workflow_id: 'flow',
          definition_version_id: '9007199254741001',
          installation_revision: 1,
          capability_version_id: '9007199254741003',
          status: 'published',
          created_at: ''
        }
      ]
    })
    const api = createWorkflowDraftApi({ get, post: vi.fn(), put: vi.fn() })
    expect((await api.instances()).items[0]).toMatchObject({
      id: '9007199254740999',
      definitionVersionId: '9007199254741001'
    })
    get.mockResolvedValueOnce({
      total: 1,
      items: [{ id: Number('9007199254740999'), definition_version_id: '9' }]
    })
    await expect(api.instances()).rejects.toThrow('能力标识无效')
  })
})
