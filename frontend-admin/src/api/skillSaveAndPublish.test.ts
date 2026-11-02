import { afterEach, expect, it, vi } from 'vitest'
import { skillDraftSavePayload } from '@skillforge/shared/capabilities/skillDrafts'
import { api } from './index'
afterEach(() => {
  vi.unstubAllGlobals()
  localStorage.clear()
})
it('组合发布复用技能文本与二进制编码，并在 401 时不自动重发', async () => {
  const fetchMock = vi.fn().mockResolvedValue(
    new Response(JSON.stringify({ code: 'DENIED', message: '拒绝', trace_id: 'trace' }), {
      status: 401,
      headers: { 'Content-Type': 'application/json' }
    })
  )
  vi.stubGlobal('fetch', fetchMock)
  localStorage.setItem('skillforge.access_token', 'old')
  localStorage.setItem('skillforge.refresh_token', 'refresh')
  const payload = skillDraftSavePayload({
    draftId: '12',
    revision: 2,
    versionKey: 'v1',
    files: { 'SKILL.md': '正文', 'assets/data.bin': 'AP8=' },
    modelInvocable: true,
    userInvocable: false,
    activationMode: 'ON_DEMAND',
    conflictGroup: null,
    requiredCapabilities: [{ type: 'TOOL', capabilityId: '21', versionId: 'old-version' }]
  })
  await expect(api.skillDrafts.saveAndPublish('request', 7, 2, payload)).rejects.toBeTruthy()
  expect(fetchMock).toHaveBeenCalledTimes(1)
  expect(fetchMock.mock.calls[0]![0]).toBe('/api/v1/capabilities/skill-drafts/save-and-publish')
  expect(JSON.parse(fetchMock.mock.calls[0]![1].body)).toMatchObject({
    request_id: 'request',
    draft_id: '12',
    revision: 2,
    definition_revision: 7,
    files: { 'SKILL.md': '5q2j5paH', 'assets/data.bin': 'AP8=' },
    required_capabilities: [{ type: 'TOOL', capability_id: '21' }]
  })
})
