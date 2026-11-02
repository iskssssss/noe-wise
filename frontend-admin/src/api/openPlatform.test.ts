import { describe, expect, it, vi } from 'vitest'
import { createOpenPlatformClient } from '@skillforge/shared/open-platform/v1'
import type {
  GroupControlReceipt,
  GroupHistoryPage,
  GroupMessageAuthor,
  GroupSenderEndpoint
} from '@skillforge/shared/open-platform/v1'

function transport() {
  return {
    get: vi.fn().mockResolvedValue([]),
    post: vi.fn().mockResolvedValue({ status: 'accepted' }),
    put: vi.fn().mockResolvedValue({ status: 'published' })
  }
}

describe('Open Platform v1 typed client', () => {
  it('keeps ACTIVE registration distinct from UNKNOWN connection and preserves nullable confirmation time', async () => {
    const http = transport()
    const client = createOpenPlatformClient(http)
    const endpoint: GroupSenderEndpoint = {
      endpoint_id: 'sender/1',
      generation: 3,
      ledger_ref: 'ledger/stable',
      status: 'ACTIVE',
      applied_revision: 7,
      fence_revision: 8,
      retirement_receipt_ref: null,
      last_confirmed_at: null,
      connection_state: 'UNKNOWN'
    }
    for (const lastConfirmedAt of [null, '2026-10-22T14:00:00.123456Z']) {
      const current = {
        group_binding_id: 'group/1',
        control_revision: 7,
        latest_trigger_sequence: 5,
        states: [],
        blocked_boundaries: [],
        endpoints: [{ ...endpoint, last_confirmed_at: lastConfirmedAt }],
        latest_command: null
      }
      http.get.mockResolvedValueOnce(current)
      const result = await client.groups.controls.current('group/1')
      expect(result).toBe(current)
      expect(result.endpoints[0]?.status).toBe('ACTIVE')
      expect(result.endpoints[0]?.connection_state).toBe('UNKNOWN')
      expect(result.endpoints[0]?.last_confirmed_at).toBe(lastConfirmedAt)
    }
  })

  it('keeps sender generation, durable fence and original in-flight evidence in each explicit action', async () => {
    const http = transport()
    const client = createOpenPlatformClient(http)
    const registered = { endpoint_id: 'sender/1', generation: 3, status: 'REGISTERING' }
    http.post.mockResolvedValue(registered)
    const registration = {
      group_binding_id: 'group/1',
      endpoint_id: 'sender/1',
      generation: 3,
      expected_generation: 2,
      ledger_ref: 'ledger/stable'
    }
    expect(await client.groups.senderEndpoints.register(registration, 'register/key')).toBe(
      registered
    )
    const inFlight = [{ delivery_id: 'delivery/unknown', status: 'UNKNOWN', result_ref: null }]
    const retirement = {
      group_binding_id: 'group/1',
      endpoint_id: 'sender/1',
      old_generation: 2,
      new_generation: 3,
      ledger_ref: 'ledger/stable',
      fence_revision: 8,
      retirement_receipt_ref: 'retirement/2',
      applied_boundary_ref: 'boundary/7',
      in_flight: inFlight
    }
    await client.groups.senderEndpoints.retire(retirement, 'retire/key')
    const ack = {
      group_binding_id: 'group/1',
      endpoint_id: 'sender/1',
      generation: 3,
      command_id: 'control/1',
      control_revision: 7,
      ledger_ref: 'ledger/stable',
      applied_boundary_ref: 'boundary/7',
      fence_revision: 8,
      in_flight: inFlight
    }
    await client.groups.senderEndpoints.ack(ack, 'ack/key')
    for (const [index, [route, body, key]] of [
      ['register', registration, 'register/key'],
      ['retire', retirement, 'retire/key'],
      ['ack', ack, 'ack/key']
    ].entries()) {
      const [path, sent, options] = http.post.mock.calls[index]!
      expect(path).toBe(`/open-platform/groups/sender-endpoints/${route}`)
      expect(sent).toBe(body)
      expect(new Headers(options.headers).get('Idempotency-Key')).toBe(key)
    }
    expect(retirement.in_flight).toBe(inFlight)
    expect(ack.in_flight).toBe(inFlight)
  })

  it('preserves cross-service command receipts and exact release boundaries without claiming application', async () => {
    const http = transport()
    const client = createOpenPlatformClient(http)
    const partial: GroupControlReceipt = {
      command_id: 'control/1',
      group_binding_id: 'group/1',
      run_id: null,
      fingerprint: 'a'.repeat(64),
      control_revision: 7,
      desired_state: 'paused',
      scopes: ['send'],
      status: 'PARTIAL',
      result_kind: 'accepted',
      result_ref: 'control/1',
      required_participants: [
        {
          endpoint_id: 'sender/1',
          generation: 3,
          required_revision: 7,
          status: 'REGISTERING',
          applied_boundary_ref: null
        }
      ],
      durable_acks: [],
      in_flight: [{ delivery_id: 'delivery/unknown', status: 'UNKNOWN', result_ref: null }],
      boundary_ref: 'boundary/7',
      through_trigger_sequence: null
    }
    http.post.mockResolvedValue(partial)
    const command = {
      command_id: 'control/1',
      group_binding_id: 'group/1',
      run_id: null,
      expected_revision: 6,
      desired_state: 'paused' as const,
      scopes: ['send' as const]
    }
    expect(await client.groups.controls.command(command, 'original/key')).toBe(partial)
    const terminalize = {
      group_binding_id: 'group/1',
      command_id: 'control/unknown',
      fingerprint: 'b'.repeat(64),
      local_intent_id: 'intent/1',
      expected_revision: 7
    }
    await client.groups.controls.terminalize(terminalize, 'terminal/key')
    const release = {
      group_binding_id: 'group/1',
      run_id: 'run/1',
      expected_revision: 7,
      messages: [{ message_id: 'message/1', message_version: 2, delivery_id: 'delivery/1' }],
      blocked_boundary_refs: ['boundary/7']
    }
    await client.groups.controls.release(release, 'release/key')
    for (const [index, [route, body, key]] of [
      ['controls', command, 'original/key'],
      ['controls/terminalize', terminalize, 'terminal/key'],
      ['controls/release', release, 'release/key']
    ].entries()) {
      const [path, sent, options] = http.post.mock.calls[index]!
      expect(path).toBe(`/open-platform/groups/${route}`)
      expect(sent).toBe(body)
      expect(new Headers(options.headers).get('Idempotency-Key')).toBe(key)
    }
  })

  it('queries current control and original receipts separately and keeps qualify and authorize identities exact', async () => {
    const http = transport()
    const client = createOpenPlatformClient(http)
    const abort = new AbortController()
    const options = { signal: abort.signal }
    await client.groups.controls.detail('group/1', 'command/unknown', options)
    await client.groups.controls.current('group/1', options)
    expect(http.get.mock.calls[0]).toEqual([
      '/open-platform/groups/controls/detail?group_binding_id=group%2F1&command_id=command%2Funknown',
      options
    ])
    expect(http.get.mock.calls[1]).toEqual([
      '/open-platform/groups/controls/current?group_binding_id=group%2F1',
      options
    ])
    const authorize = { group_binding_id: 'group/1', action: 'release' as const }
    await client.groups.controls.authorize(authorize, options)
    const qualify = {
      group_binding_id: 'group/1',
      run_id: 'run/1',
      message_id: 'message/1',
      message_version: 2,
      delivery_id: 'delivery/1',
      endpoint_id: 'sender/1',
      generation: 3
    }
    await client.groups.deliveries.qualify(qualify, options)
    expect(http.post.mock.calls).toEqual([
      ['/open-platform/groups/controls/authorize', authorize, options],
      ['/open-platform/groups/deliveries/qualify', qualify, options]
    ])
  })

  it('reads group public history and events with the original reader and opaque cursor', async () => {
    const http = transport()
    const client = createOpenPlatformClient(http)
    const abort = new AbortController()
    const query = {
      group_binding_id: 'group/1',
      reader_end_user_id: '9007199254740993123',
      cursor: 'opaque/+cursor=?',
      page_size: 50
    }
    const page: GroupHistoryPage = {
      group_binding_id: query.group_binding_id,
      session_id: 'session/1',
      items: [],
      next_cursor: null,
      has_more: false,
      event_cursor: 'outbox/watermark'
    }
    http.get.mockResolvedValue(page)
    expect(await client.groups.history(query, { signal: abort.signal })).toBe(page)
    await client.groups.events(query, { signal: abort.signal })
    for (const [index, route] of ['history', 'events'].entries()) {
      const [path, options] = http.get.mock.calls[index]!
      const url = new URL(path, 'http://localhost')
      expect(url.pathname).toBe(`/open-platform/groups/${route}`)
      expect(Object.fromEntries(url.searchParams)).toEqual({ ...query, page_size: '50' })
      expect(options.signal).toBe(abort.signal)
    }
  })

  it('opens group SSE through the authenticated transport without changing the resume cursor', async () => {
    const http = transport()
    const client = createOpenPlatformClient(http)
    const abort = new AbortController()
    const response = new Response('event: ping\ndata: {}\n\n')
    http.get.mockResolvedValue(response)
    const result = await client.groups.stream(
      { group_binding_id: 'group/1', reader_end_user_id: '42', cursor: 'opaque/cursor' },
      { signal: abort.signal, headers: { 'Last-Event-ID': 'original/event', 'X-Request': 'trace' } }
    )
    expect(result).toBe(response)
    const [path, options] = http.get.mock.calls[0]!
    expect(new URL(path, 'http://localhost').searchParams.get('cursor')).toBe('opaque/cursor')
    expect(options.responseType).toBe('stream')
    expect(options.signal).toBe(abort.signal)
    const headers = new Headers(options.headers)
    expect(headers.get('Last-Event-ID')).toBe('original/event')
    expect(headers.get('X-Request')).toBe('trace')
    expect(headers.get('Accept')).toBe('text/event-stream')
    expect(response.bodyUsed).toBe(false)
    expect(http.post).not.toHaveBeenCalled()
  })

  it('records a group source without requesting a Run and preserves each author proof branch', async () => {
    const http = transport()
    const client = createOpenPlatformClient(http)
    const receipt = {
      group_binding_id: 'group/1',
      session_id: 'session/1',
      source_message_id: 'source/1',
      source_sequence: 7,
      source_event_id: 'event/1',
      source_revision: 1
    }
    http.post.mockResolvedValue(receipt)
    const authors: GroupMessageAuthor[] = [
      { kind: 'end_user', end_user_id: '9007199254740993101' },
      {
        kind: 'agent',
        agent_id: '9007199254740993102',
        run_id: 'run/1',
        source_ref: 'message/final'
      },
      { kind: 'system', source_ref: 'event/system' },
      {
        kind: 'tool',
        agent_id: '9007199254740993102',
        run_id: 'run/1',
        source_ref: 'action/tool-result'
      }
    ]
    for (const author of authors) {
      const request = {
        group_binding_id: 'group/1',
        source_event_id: 'event/1',
        source_revision: 1,
        author,
        content: [{ type: 'text' as const, text: '消息正文 @助手不自动触发' }]
      }
      expect(await client.groups.messages.record(request, 'message-key')).toBe(receipt)
      const [path, sent, options] = http.post.mock.lastCall!
      expect(path).toBe('/open-platform/groups/messages')
      expect(sent).toEqual(request)
      expect(sent.author).toBe(author)
      expect(sent).not.toHaveProperty('requester_end_user_id')
      expect(sent).not.toHaveProperty('trigger')
      expect((options.headers as Headers).get('Idempotency-Key')).toBe('message-key')
    }
    expect(http.get).not.toHaveBeenCalled()
    expect(receipt).not.toHaveProperty('input_id')
    expect(receipt).not.toHaveProperty('run_id')
  })

  it('keeps the immutable trigger receipt separate from its current admitted Run projection', async () => {
    const http = transport()
    const client = createOpenPlatformClient(http)
    const request = {
      group_binding_id: 'group/+1',
      source_message_id: 'source/1',
      requester_end_user_id: '9007199254740993101',
      agent_id: '9007199254740993102'
    }
    const original = {
      ...request,
      session_id: 'session/1',
      trigger_id: 'trigger/+1',
      trigger_sequence: 9,
      status: 'queued',
      input_id: null,
      run_id: null,
      rejection_code: null
    }
    const current = { ...original, status: 'accepted', input_id: 'input/1', run_id: 'run/1' }
    http.post.mockResolvedValue(original)
    http.get.mockResolvedValue(current)
    const options = { headers: { 'X-Client-Trace': 'group-trigger' } }
    expect(await client.groups.triggers.create(request, 'trigger-key', options)).toBe(original)
    expect(http.get).not.toHaveBeenCalled()
    expect(
      await client.groups.triggers.detail(request.group_binding_id, original.trigger_id, options)
    ).toBe(current)
    expect(await client.groups.triggers.create(request, 'trigger-key', options)).toBe(original)
    expect(http.get).toHaveBeenCalledWith(
      '/open-platform/groups/triggers/detail?group_binding_id=group%2F%2B1&trigger_id=trigger%2F%2B1',
      options
    )
    expect(original.status).toBe('queued')
    expect(original.run_id).toBeNull()
    for (const [path, body, sentOptions] of http.post.mock.calls) {
      expect(path).toBe('/open-platform/groups/triggers')
      expect(body).toEqual(request)
      expect(body).not.toHaveProperty('content')
      expect((sentOptions.headers as Headers).get('Idempotency-Key')).toBe('trigger-key')
      expect((sentOptions.headers as Headers).get('X-Client-Trace')).toBe('group-trigger')
    }
  })

  it('answers the original group Run wait with the existing exact Chat response and stable input identity', async () => {
    const http = transport()
    const client = createOpenPlatformClient(http)
    const hitl = {
      execution_id: 'run/+1',
      run_revision: '9007199254740993101',
      human_input: {
        wait: { action_id: 'action/1', generation: '9007199254740993102' },
        text: '确认'
      }
    }
    const request = {
      group_binding_id: 'group/+1',
      run_id: hitl.execution_id,
      input_id: 'response-input/1',
      responder_end_user_id: '9007199254740993103',
      hitl_response: hitl
    }
    const response = {
      group_binding_id: request.group_binding_id,
      session_id: 'session/1',
      run_id: request.run_id,
      input_id: request.input_id,
      status: 'running',
      replayed: false
    }
    const detail = {
      ...response,
      agent_id: '9007199254740993104',
      phase: 'WAITING',
      revision: hitl.run_revision,
      pending_interactions: [
        {
          execution_id: hitl.execution_id,
          run_revision: hitl.run_revision,
          tool_wait: hitl.human_input.wait
        }
      ]
    }
    http.post.mockResolvedValue(response)
    http.get.mockResolvedValue(detail)
    expect(await client.groups.runs.detail(request.group_binding_id, request.run_id)).toBe(detail)
    expect(await client.groups.runs.respond(request)).toBe(response)
    expect(http.get).toHaveBeenCalledWith(
      '/open-platform/groups/runs/detail?group_binding_id=group%2F%2B1&run_id=run%2F%2B1',
      undefined
    )
    const [path, sent, options] = http.post.mock.lastCall!
    expect(path).toBe('/open-platform/groups/runs/respond')
    expect(sent).toEqual(request)
    expect(sent.hitl_response).toBe(hitl)
    expect(sent.hitl_response.run_revision).toBe('9007199254740993101')
    expect(sent.hitl_response.human_input.wait.generation).toBe('9007199254740993102')
    expect((options.headers as Headers).get('Idempotency-Key')).toBe(request.input_id)
    expect(sent).not.toHaveProperty('content')
    expect(sent).not.toHaveProperty('requester_end_user_id')
  })

  it('targets cancel and steer at the original group Run using input identity as the only command key', async () => {
    const http = transport()
    const client = createOpenPlatformClient(http)
    const base = {
      group_binding_id: 'group/1',
      run_id: 'original/run',
      operator_end_user_id: '9007199254740993101'
    }
    const steer = {
      ...base,
      input_id: 'steer-input/1',
      action: 'steer' as const,
      text: '补充原任务的准确业务信息'
    }
    const cancel = {
      ...base,
      input_id: 'cancel-input/1',
      action: 'cancel_execution' as const,
      text: null
    }
    const response = {
      group_binding_id: base.group_binding_id,
      session_id: 'session/1',
      run_id: base.run_id,
      input_id: cancel.input_id,
      status: 'cancelling',
      replayed: false
    }
    http.post.mockResolvedValue(response)
    const options = {
      headers: { 'Idempotency-Key': 'unrelated-key', 'X-Client-Trace': 'original-run' }
    }
    await client.groups.runs.command(steer, options)
    expect(await client.groups.runs.command(cancel, options)).toBe(response)
    for (const [index, [path, body, sentOptions]] of http.post.mock.calls.entries()) {
      expect(path).toBe('/open-platform/groups/runs/commands')
      expect(body).toEqual(index === 0 ? steer : cancel)
      expect(body.run_id).toBe(base.run_id)
      expect(body.operator_end_user_id).toBe(base.operator_end_user_id)
      expect(body).not.toHaveProperty('requester_end_user_id')
      expect(body).not.toHaveProperty('source_message_id')
      expect((sentOptions.headers as Headers).get('Idempotency-Key')).toBe(body.input_id)
      expect((sentOptions.headers as Headers).get('X-Client-Trace')).toBe('original-run')
    }
    expect(http.get).not.toHaveBeenCalled()
  })

  it('keeps a stable group owner separate from member identities and selected service Agents', async () => {
    const http = transport()
    const client = createOpenPlatformClient(http)
    const group = {
      group_binding_id: 'opaque/group:+1',
      session_id: 'opaque/session:1',
      namespace: 'wecom',
      external_group_ref: 'room/1',
      lifecycle_ref: 'lifecycle/1',
      business_scope_id: '9007199254740993100',
      status: 'active',
      revision: 1,
      subject: { kind: 'group', group_binding_id: 'opaque/group:+1' }
    }
    http.post.mockResolvedValue(group)
    http.get.mockResolvedValue(group)
    const request = {
      namespace: group.namespace,
      external_group_ref: group.external_group_ref,
      lifecycle_ref: group.lifecycle_ref,
      business_scope_id: group.business_scope_id
    }
    const controller = new AbortController()
    const options = { signal: controller.signal, headers: { 'X-Client-Trace': 'group-1' } }
    expect(await client.groups.ensure(request, 'stable-ensure-key', options)).toBe(group)
    expect(await client.groups.detail(group.group_binding_id, options)).toBe(group)
    expect(
      await client.groups.close(
        { group_binding_id: group.group_binding_id, expected_revision: 1 },
        'stable-close-key',
        options
      )
    ).toBe(group)
    expect(http.post.mock.calls[0][1]).toEqual(request)
    expect(http.post.mock.calls[0][1]).not.toHaveProperty('end_user_id')
    expect(http.post.mock.calls[0][1]).not.toHaveProperty('agent_id')
    expect(http.get).toHaveBeenCalledWith(
      '/open-platform/groups/detail?group_binding_id=opaque%2Fgroup%3A%2B1',
      options
    )
    expect(http.post.mock.calls[1][1]).toEqual({
      group_binding_id: group.group_binding_id,
      expected_revision: 1
    })
    expect(
      http.post.mock.calls.map((call) => (call[2].headers as Headers).get('Idempotency-Key'))
    ).toEqual(['stable-ensure-key', 'stable-close-key'])
    for (const [, , sentOptions] of http.post.mock.calls) {
      expect(sentOptions.signal).toBe(controller.signal)
      expect((sentOptions.headers as Headers).get('X-Client-Trace')).toBe('group-1')
    }
  })

  it('preserves object CAS, source ordering and exact member/Agent IDs on group updates', async () => {
    const http = transport()
    const client = createOpenPlatformClient(http)
    const member = {
      group_binding_id: 'opaque-group',
      end_user_id: '9007199254740993101',
      role: 'manager' as const,
      status: 'left' as const,
      expected_revision: 0,
      source_event_id: 'member-event/1',
      source_revision: 7
    }
    const agent = {
      group_binding_id: 'opaque-group',
      agent_id: '9007199254740993102',
      status: 'removed' as const,
      expected_revision: 4,
      source_event_id: 'agent-event/1',
      source_revision: 8
    }
    await client.groups.members.update(member, 'stable-member-key')
    await client.groups.agents.update(agent, 'stable-agent-key')
    expect(http.post.mock.calls[0][0]).toBe('/open-platform/groups/members/update')
    expect(http.post.mock.calls[0][1]).toEqual(member)
    expect(http.post.mock.calls[1][0]).toBe('/open-platform/groups/agents/update')
    expect(http.post.mock.calls[1][1]).toEqual(agent)
    expect(
      http.post.mock.calls.map((call) => (call[2].headers as Headers).get('Idempotency-Key'))
    ).toEqual(['stable-member-key', 'stable-agent-key'])
  })

  it('returns the explicit user subject on the personal Session creation path', async () => {
    const http = transport()
    const client = createOpenPlatformClient(http)
    const session = {
      session_id: 'personal-session',
      integration_id: 'integration',
      external_conversation_ref: 'conversation',
      end_user_id: '9007199254740993101',
      agent_id: '9007199254740993102',
      subject: { kind: 'user', end_user_id: '9007199254740993101' }
    }
    http.post.mockResolvedValue(session)
    expect(
      await client.sessions.create({
        externalUserRef: 'customer',
        externalConversationRef: 'conversation',
        endUserId: session.end_user_id,
        agentId: session.agent_id
      })
    ).toBe(session)
    expect(http.post.mock.calls[0][1].end_user_id).toBe(session.end_user_id)
    expect(http.post.mock.calls[0][1]).not.toHaveProperty('group_binding_id')
  })

  it('switches a same-source authorization with exact IDs, numeric deployment CAS and the original command key', async () => {
    const http = transport()
    const client = createOpenPlatformClient(http)
    const request = {
      deployment_id: '9007199254740993101',
      expected_revision: 7,
      grant_id: '9007199254740993102'
    }
    const chain = {
      definition_id: '9007199254740993103',
      owner_kind: 'platform',
      owner_tenant_id: null,
      hops: [
        {
          grant_id: request.grant_id,
          revision: 3,
          grantor_kind: 'platform',
          grantor_tenant_id: null,
          target_tenant_id: '9007199254740993104',
          parent_grant_id: null,
          parent_grant_revision: null,
          relation_ref: null,
          relation_revision: null
        }
      ]
    }
    const response = {
      deployment_id: request.deployment_id,
      agent_id: '9007199254740993105',
      deployment_revision: 8,
      grant_chain: chain
    }
    http.post.mockResolvedValue(response)
    const controller = new AbortController()
    const options = { signal: controller.signal, headers: { 'X-Client-Trace': 'switch-1' } }
    expect(await client.agentDeployments.authorization(request, 'stable-switch-key', options)).toBe(
      response
    )
    await client.agentDeployments.authorization(request, 'stable-switch-key', options)
    expect(http.post).toHaveBeenCalledTimes(2)
    for (const [path, payload, sentOptions] of http.post.mock.calls) {
      expect(path).toBe('/open-platform/agent-deployments/authorization')
      expect(payload).toEqual(request)
      expect(payload).not.toHaveProperty('tenant_id')
      expect(payload).not.toHaveProperty('definition_id')
      expect(payload.expected_revision).toBeTypeOf('number')
      expect(sentOptions.signal).toBe(controller.signal)
      expect((sentOptions.headers as Headers).get('Idempotency-Key')).toBe('stable-switch-key')
      expect((sentOptions.headers as Headers).get('X-Client-Trace')).toBe('switch-1')
    }
  })

  it('keeps fixed execution scopes and stable idempotency keys on direct deployment writes', async () => {
    const http = transport()
    const client = createOpenPlatformClient(http)
    const request = {
      definition_id: '9007199254740993101',
      grant_id: '9007199254740993102',
      integration_id: 'fixed-integration',
      external_agent_ref: 'service/1',
      resource_bindings: [],
      delegation_bindings: [],
      parameters: { role: 'service' }
    }
    await client.agentDeployments.create(request, 'stable-deployment-key')
    await client.agentDeployments.create(request, 'stable-deployment-key')
    await client.businessScopes.ensure(
      { scope_type: 'store', external_ref: 'store/1', display_name: '门店' },
      'stable-scope-key'
    )
    await client.agentDeployments.bindings(
      {
        deployment_id: '9007199254740993103',
        expected_revision: 7,
        resource_bindings: [],
        delegation_bindings: [],
        parameters: {}
      },
      'stable-binding-key'
    )
    expect(http.post.mock.calls[0][0]).toBe('/open-platform/agent-deployments')
    expect(http.post.mock.calls[0][1]).toEqual(request)
    expect(http.post.mock.calls[0][1]).not.toHaveProperty('tenant_id')
    expect(http.post.mock.calls[0][1]).not.toHaveProperty('application_id')
    for (const call of http.post.mock.calls.slice(0, 2)) {
      expect((call[2].headers as Headers).get('Idempotency-Key')).toBe('stable-deployment-key')
    }
    expect(http.post.mock.calls[2][1]).toEqual({
      scope_type: 'store',
      external_ref: 'store/1',
      display_name: '门店'
    })
    expect((http.put.mock.calls[0][2].headers as Headers).get('Idempotency-Key')).toBe(
      'stable-binding-key'
    )
    expect(http.put.mock.calls[0][1]).toMatchObject({
      deployment_id: '9007199254740993103',
      expected_revision: 7
    })
  })

  it('preserves opaque authorization-bound usage cursors and current deployment identities', async () => {
    const http = transport()
    const client = createOpenPlatformClient(http)
    const cursor = 'signed:opaque/+=='
    await client.usage.events({ cursor, limit: 50, business_scope_id: 'store/1' })
    await client.agentDeployments.detail('9007199254740993123')
    await client.businessScopes.status(
      { scope_id: '9007199254740993124', status: 'suspended', expected_revision: 2 },
      'scope-status'
    )
    const url = new URL(http.get.mock.calls[0][0], 'https://client.example')
    expect(url.pathname).toBe('/open-platform/usage/events')
    expect(url.searchParams.get('cursor')).toBe(cursor)
    expect(url.searchParams.get('business_scope_id')).toBe('store/1')
    expect(http.get.mock.calls[1][0]).toBe(
      '/open-platform/agent-deployments/detail?deployment_id=9007199254740993123'
    )
    expect(http.post.mock.calls[0][1]).toEqual({
      scope_id: '9007199254740993124',
      status: 'suspended',
      expected_revision: 2
    })
  })

  it('encodes catalog filters and exact resource revision', async () => {
    const http = transport()
    const client = createOpenPlatformClient(http)

    await client.resources.list({ resourceType: 'knowledge_base', status: 'published', limit: 20 })
    await client.resources.revision('agent', 'agent/a', 3)

    expect(http.get).toHaveBeenNthCalledWith(
      1,
      '/open-platform/resources?resourceType=knowledge_base&status=published&limit=20',
      undefined
    )
    expect(http.get).toHaveBeenNthCalledWith(
      2,
      '/open-platform/resources/revisions/detail?resourceType=agent&resourceId=agent%2Fa&revision=3',
      undefined
    )
  })

  it('keeps idempotency headers on operation and all inbound session writes', async () => {
    const http = transport()
    const client = createOpenPlatformClient(http)

    await client.operations.accept(
      { operationId: 'op-1', operationType: 'resource_publish' },
      'idem-op'
    )
    await client.sessions.message(
      'session/1',
      { content: [{ type: 'text', text: 'hello' }] },
      'idem-msg'
    )
    await client.sessions.action(
      'session/1',
      { executionId: 'exec-1', actionKey: 'yes' },
      'idem-action'
    )
    await client.sessions.callback(
      'session/1',
      { callbackId: 'cb-1', eventType: 'business_event' },
      'idem-callback'
    )
    await client.sessions.command('session/1', { command: 'close_session' }, 'idem-command')

    expect(http.post).toHaveBeenNthCalledWith(
      2,
      '/open-platform/sessions/messages',
      {
        session_id: 'session/1',
        payload: { content: [{ type: 'text', text: 'hello' }], context: {}, references: [] }
      },
      expect.any(Object)
    )
    for (const call of http.post.mock.calls) {
      expect((call[2].headers as Headers).get('Idempotency-Key')).toMatch(/^idem-/)
    }
  })

  it('maps declared wire keys without rewriting business context or callback payload', async () => {
    const http = transport()
    const client = createOpenPlatformClient(http)
    const business = { customKey: { nestedValue: 'kept' } }
    await client.sessions.action(
      's/1',
      { executionId: 'exec', actionKey: 'yes', context: business },
      'action'
    )
    await client.sessions.callback(
      's/1',
      { callbackId: 'cb', eventType: 'business_event', payload: business },
      'callback'
    )
    await client.sessions.create({
      endUserId: '17',
      agentId: '18',
      externalUserRef: 'customer',
      externalConversationRef: 'conversation'
    })
    await client.operations.accept(
      {
        operationId: 'op',
        operationType: 'resource_publish',
        items: [{ itemKey: 'i', resourceRef: 'r' }]
      },
      'operation'
    )
    expect(http.post.mock.calls[0][1]).toEqual({
      session_id: 's/1',
      payload: { execution_id: 'exec', action_key: 'yes', context: business }
    })
    expect(http.post.mock.calls[0][1].payload.context).toBe(business)
    expect(http.post.mock.calls[1][1].payload.payload).toBe(business)
    expect(http.post.mock.calls[1][1].payload.callback_id).toBe('cb')
    expect(http.post.mock.calls[1][1].payload.event_type).toBe('business_event')
    expect(http.post.mock.calls[2][1]).toMatchObject({
      end_user_id: '17',
      external_conversation_ref: 'conversation'
    })
    expect(http.post.mock.calls[3][1].items).toEqual([{ item_key: 'i', resource_ref: 'r' }])
    expect(http.post.mock.calls[3][1].operation_type).toBe('resource_publish')
    const page = { session_id: 's/1', next_cursor: 'event-1', events: [] }
    http.get.mockResolvedValueOnce(page)
    expect(await client.sessions.events('s/1')).toBe(page)
  })

  it('keeps event cursor and limit in the read-only session projection', async () => {
    const http = transport()
    const client = createOpenPlatformClient(http)

    await client.sessions.events('session/1', 'cursor/a', 50)

    expect(http.get).toHaveBeenCalledWith(
      '/open-platform/sessions/events?sessionId=session%2F1&cursor=cursor%2Fa&limit=50',
      undefined
    )
  })

  it('preserves ordered media and exact material versions without rewriting business context', async () => {
    const http = transport()
    const client = createOpenPlatformClient(http)
    const context = { businessScopeRef: 'store-1', businessKey: { nested: 'preserved' } }
    const references = [
      {
        kind: 'message' as const,
        source: { kind: 'session' as const },
        message_id: 'msg',
        message_version: 2
      }
    ]
    const content = [
      { type: 'text' as const, text: '看一下这张图片' },
      { type: 'image' as const, media_id: 'authorized-media' }
    ]
    await client.sessions.message('session', { content, context, references }, 'stable-key')
    expect(http.post.mock.calls[0][1]).toEqual({
      session_id: 'session',
      payload: { content, context, references }
    })
    expect(http.post.mock.calls[0][1].payload.context).toBe(context)
    expect(http.post.mock.calls[0][1].payload.references).toBe(references)
  })

  it('preserves large EndUser and Agent decimal identities through ensure and session creation', async () => {
    const http = transport()
    const client = createOpenPlatformClient(http)
    const user = {
      end_user_id: '9007199254740993123',
      integration_id: 'integration',
      external_user_ref: 'customer/1'
    }
    http.post.mockResolvedValueOnce(user)
    const ensured = await client.endUsers.ensure('customer/1', '客户')
    await client.sessions.create({
      endUserId: ensured.end_user_id,
      agentId: '9007199254740993456',
      externalUserRef: 'customer/1',
      externalConversationRef: 'conversation/1'
    })
    expect(http.post.mock.calls[0].slice(0, 2)).toEqual([
      '/open-platform/end-users/ensure',
      { external_user_ref: 'customer/1', display_name: '客户' }
    ])
    expect(http.post.mock.calls[1][1]).toMatchObject({
      end_user_id: '9007199254740993123',
      agent_id: '9007199254740993456'
    })
  })

  it('covers resource lifecycle writes with CAS headers and encoded paths', async () => {
    const http = transport()
    const client = createOpenPlatformClient(http)

    await client.resources.createDraft('knowledge_base', 'kb/a', { title: 'draft' })
    await client.resources.latest('knowledge_base', 'kb/a')
    await client.resources.getMapping('agent', 'external/a')
    await client.resources.updateDraft('knowledge_base', 'kb/a', 2, { title: 'r2' }, '"etag-2"')
    await client.resources.upsertMapping(
      'agent',
      'external/a',
      {
        resourceId: 'agent-1',
        contentHash: 'sha256:a'
      },
      1,
      '"mapping-etag"'
    )
    await client.resources.ready('knowledge_base', 'kb/a', 2, '"etag-2"')
    await client.resources.publish('knowledge_base', 'kb/a', 2, '"etag-3"')
    await client.resources.deploy(
      'deploy/1',
      {
        resourceType: 'knowledge_base',
        resourceId: 'kb/a',
        resourceRevision: 2,
        environment: 'test'
      },
      4,
      '"deploy-etag"'
    )
    await client.resources.bindChannel('7', { deploymentId: 'deploy/1' }, 3, '"binding-etag"')

    expect(http.post).toHaveBeenNthCalledWith(
      1,
      '/open-platform/resources/revisions',
      {
        resource_type: 'knowledge_base',
        resource_id: 'kb/a',
        payload: { content: { title: 'draft' } }
      },
      undefined
    )
    expect(http.get).toHaveBeenCalledWith(
      '/open-platform/resources/revisions/latest?resourceType=knowledge_base&resourceId=kb%2Fa',
      undefined
    )
    expect(http.get).toHaveBeenCalledWith(
      '/open-platform/resources/detail?resourceType=agent&externalRef=external%2Fa',
      undefined
    )
    expect(http.put).toHaveBeenNthCalledWith(
      1,
      '/open-platform/resources/revisions/update',
      {
        resource_type: 'knowledge_base',
        resource_id: 'kb/a',
        revision: 2,
        payload: { content: { title: 'r2' } }
      },
      expect.objectContaining({ headers: expect.any(Headers) })
    )
    expect(http.put.mock.calls[1].slice(0, 2)).toEqual([
      '/open-platform/resources/update',
      {
        resource_type: 'agent',
        external_ref: 'external/a',
        payload: { resource_id: 'agent-1', content_hash: 'sha256:a' }
      }
    ])
    expect(http.put.mock.calls[2][0]).toBe('/open-platform/deployments/update')
    expect(http.put.mock.calls[2][1].deployment_id).toBe('deploy/1')
    expect(http.put.mock.calls[3].slice(0, 2)).toEqual([
      '/open-platform/channels/binding',
      { channel_id: '7', payload: { deployment_id: 'deploy/1' } }
    ])
    expect((http.put.mock.calls[0][2].headers as Headers).get('If-Match')).toBe('"etag-2"')
    expect((http.put.mock.calls[1][2].headers as Headers).get('X-Resource-Revision')).toBe('1')
    expect((http.put.mock.calls[1][2].headers as Headers).get('If-Match')).toBe('"mapping-etag"')
    expect((http.put.mock.calls[2][2].headers as Headers).get('X-Deployment-Revision')).toBe('4')
    expect((http.put.mock.calls[3][2].headers as Headers).get('X-Binding-Revision')).toBe('3')
  })
})
