import assert from 'node:assert/strict';
import test from 'node:test';
import { originalReport, validateRecovery, validateNativeSubmission, submitNativeContinuation } from './live_product_continuation.mjs';

const wanted = { session_id: 'session-1', agent_id: 'agent-1', end_user_id: 'user-1' };
const original = { id: 'revision-1', deliverable_id: 'deliverable-1', revision: 1, name: '业务报告',
  session_id: wanted.session_id, run_id: 'run-1', result_run_ids: ['run-1'], current: true, status: 'ready',
  files: [{ artifact_id: 'artifact-1', file_id: 'file-1', path: 'business-report.txt', size: 80, sha256: 'a'.repeat(64) }] };
const recovery = { status: 'validated', session_id: wanted.session_id, base_revision_id: original.id,
  deliverable_id: original.deliverable_id,
  artifact_inputs: original.files.map(file => ({ artifact_id: file.artifact_id, path: file.path,
    file_id: file.file_id, size: file.size, sha256: file.sha256 })),
  references: [{ label: '成果版本 1', source: { kind: 'deliverable_revision', revision_id: original.id, purpose: 'revision_base' } }] };
const body = { ...wanted, client_message_id: '38df88bd-1744-471b-a61c-40feb1df379b',
  content: [{ type: 'text', text: '数量改成32，沿用预算和日期' }],
  context: { end_user_id: wanted.end_user_id, attachment_ids: [] }, references: recovery.references.map(row => row.source) };

test('只能选择同Session首轮Run的唯一current发布报告，重复/错归属失败', () => {
  assert.equal(originalReport([original], wanted, 'run-1'), original);
  for (const rows of [[original, original], [{ ...original, session_id: 'other' }], [{ ...original, run_id: 'other' }],
    [{ ...original, result_run_ids: ['other'] }], [{ ...original, current: false }], [{ ...original, status: 'failed' }]]) {
    assert.throws(() => originalReport(rows, wanted, 'run-1'), /exact_initial_current_business_report_required/);
  }
});

test('正式recovery必须返回首轮版本和全部原文件事实，其他版本不能冒充', () => {
  const actual = validateRecovery(recovery, original, wanted);
  assert.deepEqual(actual.references, body.references);
  for (const key of ['session_id', 'base_revision_id', 'deliverable_id']) {
    assert.throws(() => validateRecovery({ ...recovery, [key]: 'other' }, original, wanted));
  }
  for (const mutate of [value => value.references[0].source.revision_id = 'revision-2',
    value => value.references[0].source.purpose = 'input', value => value.artifact_inputs[0].sha256 = 'b'.repeat(64),
    value => value.references.push(value.references[0])]) {
    const value = structuredClone(recovery); mutate(value);
    assert.throws(() => validateRecovery(value, original, wanted));
  }
});

test('实际POST的用户/Agent/Session/原版引用和文字逐项匹配，旧字段或不同引用拒绝', () => {
  const continuation = validateRecovery(recovery, original, wanted);
  const actual = validateNativeSubmission(body, wanted, body.content[0].text, continuation);
  assert.equal(actual.input_id, body.client_message_id);
  assert.equal(actual.references_sha256.length, 64);
  for (const mutate of [value => value.session_id = 'other', value => value.agent_id = 'other',
    value => value.context.end_user_id = 'other', value => value.references[0].revision_id = 'revision-2',
    value => value.references = [], value => value.content[0].text += '\n', value => value.message = 'legacy',
    value => value.artifact_inputs = recovery.artifact_inputs, value => value.base_revision_id = original.id]) {
    const value = structuredClone(body); mutate(value);
    assert.throws(() => validateNativeSubmission(value, wanted, body.content[0].text, continuation));
  }
});

test('一次实际发送记录真实request再确认相同input回执，不自动重发错身份', async () => {
  const continuation = validateRecovery(recovery, original, wanted);
  let sends = 0, recorded = [];
  const page = {
    waitForRequest: async () => ({ postDataJSON: () => body }),
    waitForResponse: async () => ({ status: () => 200,
      json: async () => ({ code: 'OK', data: { session_id: wanted.session_id, execution_id: 'run-2', input_id: body.client_message_id } }) }),
    getByRole: (_role, options) => { assert.equal(options.name, '发送消息'); return { click: async () => sends++ }; }
  };
  const actual = await submitNativeContinuation(page, wanted, body.content[0].text, continuation,
    async value => recorded.push(value));
  assert.equal(sends, 1); assert.equal(recorded.length, 1);
  assert.equal(actual.receipt.input_id, recorded[0].input_id);
  page.waitForResponse = async () => ({ status: () => 200,
    json: async () => ({ code: 'OK', data: { session_id: wanted.session_id, execution_id: 'run-3', input_id: 'wrong' } }) });
  await assert.rejects(submitNativeContinuation(page, wanted, body.content[0].text, continuation,
    async value => recorded.push(value)), /native_receipt_actual_input_identity_required/);
  assert.equal(sends, 2); assert.equal(recorded.length, 2);
});
