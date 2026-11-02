import type { ObservationNode } from './types'

export function node(id: string, parent: string | null = null): ObservationNode {
  return {
    node_id: id,
    parent_node_id: parent,
    kind: 'TOOL',
    name: id,
    status: 'SUCCEEDED',
    source_session_id: 'session-A',
    root_input_id: 'root-A',
    input_id: 'input-A',
    run_id: 'run-A',
    action_id: 'action-' + id,
    step_id: 'step-A',
    iteration: '1',
    trace_id: 'trace-A',
    span_id: 'span-' + id,
    started_at: '2026-10-25T00:00:00Z',
    finished_at: '2026-10-25T00:00:01Z',
    duration_ms: '1000',
    sequence: '9007199254740993',
    summary: null,
    input: { ref: null, availability: 'NOT_RECORDED' },
    output: { ref: null, availability: 'NOT_RECORDED' },
    source_event_ids: [],
    attempts: []
  }
}
