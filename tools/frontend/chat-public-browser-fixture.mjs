import assert from 'node:assert/strict';

export const frame = (id, event, data) => `id: ${id}\nevent: ${event}\ndata: ${JSON.stringify(data)}\n\n`;
const gate = () => { let resolve; const promise = new Promise(done => { resolve = done; }); return { promise, resolve }; };
const presentation = (title, results = [], references = []) => ({ title, results, references });
export const chatInputLimits = {
  attachments: { max_file_bytes: 50 * 1024 * 1024, max_files: 16, max_total_bytes: 200 * 1024 * 1024, extensions: ['csv', 'tsv', 'txt', 'md', 'json'] },
  references: { max_items: 64, max_file_selectors: 2048 }, selections: { max_selectable_files: 2048 },
};
export const artifactReference = { label: '订单数据', source: { kind: 'artifact_files', files: [{ artifact_id: 'artifact-private-marker', path: 'orders.csv' }] } };
const process = (id, type, sequence, ordinal, title, results, references = []) => ({
  process_id: id, run_id: 'run-1', input_id: 'input-1', root_run_id: 'run-1', root_input_id: 'input-1',
  type, sequence, ordinal, updated_sequence: sequence + ordinal, status: 'succeeded', actor_agent_id: 'agent-private-marker', actor_name: '数据助手',
  presentation: type === 'commentary' ? { title, summary: results[0]?.text ?? '', results: [], references } : presentation(title, results, references),
});
export const processItems = [
  process('commentary-private-1', 'commentary', 1, 1, '读取说明', [{ kind: 'text', format: 'plain', text: '先读取订单数据，再计算汇总指标。' }]),
  process('tool-private-1', 'tool', 2, 0, '读取订单', [{ kind: 'table', columns: [{ key: 'order', label: '订单' }, { key: 'amount', label: '金额' }], rows: [['ORD-101', 45]] }], [artifactReference]),
  process('commentary-private-2', 'commentary', 2, 1, '计算说明', [{ kind: 'text', format: 'plain', text: '订单数据已就绪，继续计算平均值。' }]),
  process('tool-private-2', 'tool', 3, 0, '计算平均值', [{ kind: 'text', format: 'markdown', text: '平均值为 **9.0**。' }]),
];
export const finalMessage = { message_id: 'message-private-final', message_version: 1, references: [], run_id: 'run-1', root_run_id: 'run-1', root_input_id: 'input-1', phase: 'final', content: '总和 45，平均值 9.0。' };
export const pending = {
  execution_id: 'run-2', suspend_ref: 'suspend-private-marker', checkpoint_revision: 4, expected_revision: 5, resume_action_key: 'resume-private-marker',
  card: { type: 'tool_input', title: '补充报表名称', tool_wait: {
    actionRef: { tenantId: 'tenant-private-marker', actionId: 'action-private-marker' }, invocationId: 'invocation-private-marker',
    waitIdentity: { pendingId: 'pending-private-marker', wakeId: 'wake-private-marker' }, waitRevision: '9007199254740993',
  }, requests: [{ id: 'request-private-marker', message: '请填写报表名称', requested_schema: { type: 'object', properties: { name: { type: 'string', title: '报表名称' } }, required: ['name'] } }] },
};
export const forbiddenVisibleValues = ['session-1', 'run-1', 'run-2', 'input-1', 'input-2', 'agent-1', 'message-user', 'artifact-private-marker', 'agent-private-marker', 'commentary-private-', 'tool-private-', 'message-private-final', 'tenant-private-marker', 'action-private-marker', 'invocation-private-marker', 'pending-private-marker', 'wake-private-marker', 'resume-private-marker', 'suspend-private-marker', 'request-private-marker'];

export function publicChatScenario({ restore = false } = {}) {
  const stages = { second: gate(), completion: gate(), replay: gate(), tool: gate(), resumed: gate() };
  let eventReads = 0, submissions = 0;
  const posts = [];
  const history = () => ({ event_cursor: '2', messages: [{ message_id: 'message-user', message_version: 1, references: [], role: 'user', content: '计算这组数据的平均值', metadata: { input_id: 'input-1' } }], processes: processItems.slice(0, 2), activities: [{ node_type: 'agent_runtime', node_id: 'run-1', status: 'running', render: { root_run_id: 'run-1', root_input_id: 'input-1', updated_sequence: 2 } }], collaboration: { active: false, pending: false, selected_active: false, todos: [] } });
  async function events(route) {
    eventReads++;
    let body;
    if (eventReads === 1) body = frame(3, 'run.started', { session_id: 'session-1', run_id: 'run-1', root_run_id: 'run-1', root_input_id: 'input-1' }) + processItems.slice(0, 2).map((item, index) => frame(index + 4, 'process.finished', item)).join('');
    else if (eventReads === 2) { await stages.second.promise; body = processItems.slice(2).map((item, index) => frame(index + 6, 'process.finished', item)).join('') + frame(8, 'message.delta', finalMessage); }
    else if (eventReads === 3) { await stages.completion.promise; body = frame(9, 'execution.completed', { run_id: 'run-1', root_run_id: 'run-1', status: 'completed', updated_sequence: 10 }); }
    else if (eventReads === 4) { await stages.replay.promise; body = frame(10, 'execution.completed', { run_id: 'run-1', root_run_id: 'run-1', status: 'completed', updated_sequence: 11 }) + frame(11, 'message.delta', { ...finalMessage, message_version: 0, phase: 'temporary', content: '低版本临时内容不应覆盖最终结果' }) + frame(12, 'process.finished', { ...processItems[1], updated_sequence: 12, presentation: { ...processItems[1].presentation, summary: '再次核验数据一致' } }); }
    else if (eventReads === 5) { await stages.tool.promise; body = frame(13, 'run.started', { run_id: 'run-2', root_run_id: 'run-2', root_input_id: 'input-2' }) + frame(14, 'message.card', { message_id: 'tool-card-private-marker', message_version: 1, run_id: 'run-2', content: '请补充报表名称。', card: pending.card }) + frame(15, 'hitl.required', { ...pending, run_id: 'run-2' }) + frame(16, 'execution.completed', { run_id: 'run-2', status: 'waiting_hitl', updated_sequence: 16 }); }
    else if (eventReads === 6) { await stages.resumed.promise; body = frame(17, 'message.delta', { message_id: 'second-private-answer', message_version: 1, run_id: 'run-2', root_run_id: 'run-2', phase: 'final', content: '报表名称已确认。' }) + frame(18, 'execution.completed', { run_id: 'run-2', status: 'completed', updated_sequence: 18 }); }
    else { await new Promise(() => {}); return; }
    await route.fulfill({ contentType: 'text/event-stream', body });
  }
  function submit(body) {
    posts.push(body); submissions++;
    const initial = !restore && submissions === 1;
    if (initial) return { status: 'accepted', session_id: 'session-1', execution_id: 'run-1', input_id: 'input-1' };
    if (!body.hitl_response) { assert.equal(body.message, '继续生成报表'); assert.deepEqual(body.references, [artifactReference.source]); stages.tool.resolve(); }
    else {
      assert.equal(body.message, '');
      const response = body.hitl_response;
      assert.equal(response.execution_id, pending.execution_id); assert.equal(response.suspend_ref, pending.suspend_ref);
      assert.equal(response.checkpoint_revision, pending.checkpoint_revision); assert.equal(response.expected_revision, pending.expected_revision); assert.equal(response.action, pending.resume_action_key);
      assert.equal(response.response.schema_id, 'skillforge.runtime.tool-input-response.v1');
      const decoded = JSON.parse(response.response.canonical_json);
      assert.deepEqual(decoded.tool_wait, pending.card.tool_wait);
      assert.equal(decoded.responses['request-private-marker'].method, 'elicitation/create');
      assert.deepEqual(JSON.parse(decoded.responses['request-private-marker'].result.canonicalJson), { action: 'accept', content: { name: '月度订单汇总' } });
      stages.resumed.resolve();
    }
    return { status: 'accepted', session_id: 'session-1', execution_id: 'run-2', input_id: 'input-2' };
  }
  return { stages, history, events, submit, posts, get eventReads() { return eventReads; } };
}

export async function assertPublicVisibility(page) {
  const text = await page.locator('body').innerText();
  for (const value of forbiddenVisibleValues) assert.ok(!text.includes(value), `私有选择器不应显示：${value}`);
  assert.ok(!text.includes('低版本临时内容不应覆盖最终结果'));
}
