import { expect, test } from '@playwright/test'
import { mockConsole } from './support/mock-console'

test('任务操作成功但刷新失败时锁定旧修订，原地刷新恢复', async ({ page }) => {
  const fixture = await mockConsole(page, 'admin')
  let reads = 0
  const task = {
    task_id: 'task-1',
    tenant_id: { value: 'tenant-1' },
    end_user_id: { value: 'user-1' },
    agent_id: { value: 'agent-1' },
    revision: 1,
    status: 'OPEN',
    title: '核对资料',
    summary: '等待资料',
    state: { schema_id: 'task.v1', canonical_json: '{}' },
    sources: [],
    updated_at: '2026-10-29T00:00:00Z'
  }
  fixture.setHook(async (request, url) => {
    if (url.pathname === '/api/v1/chat/sessions')
      return { data: [{ session_id: 's1', summary: '资料会话' }] }
    if (url.pathname === '/api/v1/conversation-tasks/list') {
      reads++
      return reads === 2
        ? { status: 503, code: 'TEMPORARY' }
        : { data: [reads > 2 ? { ...task, revision: 2, status: 'COMPLETED' } : task] }
    }
    if (url.pathname === '/api/v1/conversation-tasks/update' && request.method() === 'POST')
      return { data: { ...task, revision: 2, status: 'COMPLETED' } }
    return undefined
  })
  await page.goto('/admin/conversation-tasks?sessionId=s1')
  await expect(page.getByText('核对资料')).toBeVisible()
  await page.getByRole('button', { name: '完成', exact: true }).click()
  await expect(
    page.getByText('任务操作已完成，但列表尚未更新。请刷新当前任务后继续操作。')
  ).toBeVisible()
  await expect(page.getByRole('button', { name: '完成', exact: true })).toBeDisabled()
  await page.getByRole('button', { name: '刷新当前任务' }).click()
  await expect(page.getByRole('button', { name: '重新打开' })).toBeEnabled()
  expect(
    fixture.requests.filter((request) => request.path === '/conversation-tasks/update')
  ).toHaveLength(1)
  expect(reads).toBe(3)
  fixture.assertHandled()
})

test('任务请求断连后刷新页面保留原标识，未确认时只读核对', async ({ page }) => {
  const fixture = await mockConsole(page, 'admin')
  const task = {
    task_id: 'task-1',
    tenant_id: { value: 'tenant-1' },
    end_user_id: { value: 'user-1' },
    agent_id: { value: 'agent-1' },
    revision: 1,
    status: 'OPEN',
    title: '核对资料',
    summary: '等待资料',
    state: { schema_id: 'task.v1', canonical_json: '{}' },
    sources: [],
    updated_at: '2026-10-29T00:00:00Z'
  }
  let receiptReads = 0
  let operationId = ''
  fixture.setHook(async (request, url) => {
    if (url.pathname === '/api/v1/chat/sessions')
      return { data: [{ session_id: 's1', summary: '资料会话' }] }
    if (url.pathname === '/api/v1/conversation-tasks/list') return { data: [task] }
    if (url.pathname === '/api/v1/conversation-tasks/select') {
      operationId = request.postDataJSON().operation_id
      return { status: 503, code: 'TEMPORARY' }
    }
    if (url.pathname === '/api/v1/conversation-tasks/receipt') {
      expect(url.searchParams.get('operation_id')).toBe(operationId)
      receiptReads++
      return {
        data: receiptReads === 1 ? { status: 'UNKNOWN', task: null } : { status: 'SUCCEEDED', task }
      }
    }
    return undefined
  })
  await page.goto('/admin/conversation-tasks?sessionId=s1')
  await page.getByRole('button', { name: '设为当前任务' }).click()
  await expect(page.getByText('任务操作结果尚未确认', { exact: false })).toBeVisible()
  await page.reload()
  await expect(page.getByText(operationId, { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '核对原操作' }).click()
  await expect(page.getByRole('button', { name: '设为当前任务' })).toBeDisabled()
  await page.getByRole('button', { name: '核对原操作' }).click()
  await expect(page.getByRole('button', { name: '设为当前任务' })).toBeEnabled()
  expect(
    fixture.requests.filter((request) => request.path === '/conversation-tasks/select')
  ).toHaveLength(1)
  expect(receiptReads).toBe(2)
  fixture.assertHandled()
})
