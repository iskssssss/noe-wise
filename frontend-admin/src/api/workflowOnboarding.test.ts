import { expect, it, vi } from 'vitest'
const post = vi.hoisted(() => vi.fn())
vi.mock('./index', () => ({ get: vi.fn(), post }))
import { workflowOnboardingApi } from './workflowOnboarding'
it('组合接入在 HTTP 边界序列化非空能力槽身份', async () => {
  await workflowOnboardingApi.submit({
    request_id: 'r',
    agent_id: '1',
    workflow_id: 'w',
    expected_definition_version_id: '2',
    mode: 'INSTALL',
    expected_installation_revision: 0,
    config: {},
    custom_prompt: '',
    bindings: {
      capabilities: [
        {
          slot: 'lookup',
          capability: {
            type: 'KNOWLEDGE_SOURCE',
            capabilityId: '9007199254740999',
            versionId: '9007199254741001'
          },
          knowledge_revision: 4
        }
      ],
      workflows: [],
      agents: []
    }
  })
  expect(post).toHaveBeenCalledWith(
    '/agents/workflows/onboard',
    expect.objectContaining({
      bindings: {
        capabilities: [
          {
            slot: 'lookup',
            capability: {
              type: 'KNOWLEDGE_SOURCE',
              capability_id: '9007199254740999',
              version_id: '9007199254741001'
            },
            knowledge_revision: 4
          }
        ],
        workflows: [],
        agents: []
      }
    }),
    { retryOnUnauthorized: false }
  )
})
