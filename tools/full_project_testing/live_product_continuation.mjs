/* D09 uses the native consumer's real revision reference control and actual POST. */
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { dataOf } from './live_product_controls.mjs';

const digest = value => createHash('sha256').update(JSON.stringify(value), 'utf8').digest('hex');
const endpoint = response => new URL(response.url()).pathname;

export function originalReport(items, wanted, initialRun) {
  assert.ok(Array.isArray(items), 'native_actual_deliverable_collection_required');
  const matches = items.filter(item => item.session_id === wanted.session_id && item.run_id === initialRun
    && item.result_run_ids?.includes(initialRun) && item.current === true && item.status === 'ready'
    && typeof item.id === 'string' && item.id && typeof item.deliverable_id === 'string' && item.deliverable_id
    && Number.isSafeInteger(item.revision) && item.revision > 0
    && item.files?.some(file => file.path?.split('/').at(-1) === 'business-report.txt'));
  assert.equal(matches.length, 1, 'exact_initial_current_business_report_required');
  return matches[0];
}

export function validateRecovery(value, original, wanted) {
  assert.equal(value?.status, 'validated', 'formal_recovery_validation_required');
  assert.equal(value.session_id, wanted.session_id, 'recovery_session_identity_required');
  assert.equal(value.base_revision_id, original.id, 'recovery_original_revision_required');
  assert.equal(value.deliverable_id, original.deliverable_id, 'recovery_deliverable_identity_required');
  assert.deepEqual(value.references, [{ label: `成果版本 ${original.revision}`, source: {
    kind: 'deliverable_revision', revision_id: original.id, purpose: 'revision_base'
  } }], 'formal_original_revision_reference_required');
  assert.deepEqual(value.artifact_inputs, original.files.map(file => ({ artifact_id: file.artifact_id,
    path: file.path, file_id: file.file_id, size: file.size, sha256: file.sha256 })),
  'recovery_original_file_facts_required');
  return { consumer: 'chat', original_revision_id: original.id, original_deliverable_id: original.deliverable_id,
    original_revision: original.revision, original_run_id: original.run_id,
    recovery_endpoint: '/api/v1/chat/deliverables/recovery-inputs',
    references: value.references.map(reference => structuredClone(reference.source)),
    artifact_inputs: structuredClone(value.artifact_inputs) };
}

export async function readNativeDeliverables(page, wanted) {
  await page.getByRole('button', { name: /^成果(?:\s*\d+)?$/ }).click();
  const listing = page.waitForResponse(response => response.request().method() === 'GET'
    && endpoint(response) === '/api/v1/chat/sessions/deliverables'
    && new URL(response.url()).searchParams.get('sessionId') === wanted.session_id)
    .then(async response => ({ status: response.status(), items: dataOf(await response.json()) }));
  void listing.catch(() => {});
  await page.getByRole('button', { name: '刷新成果', exact: true }).click();
  const { status, items } = await listing;
  assert.equal(status, 200);
  assert.ok(Array.isArray(items), 'native_actual_deliverable_collection_required');
  return items;
}

export async function prepareNativeContinuation(page, wanted, initialRun) {
  const items = await readNativeDeliverables(page, wanted);
  const original = originalReport(items, wanted, initialRun);
  await page.getByRole('combobox', { name: '成果版本', exact: true }).click();
  await page.getByRole('option', { name: `${original.name} · v${original.revision}（当前）`, exact: true }).click();
  const recovery = page.waitForResponse(response => response.request().method() === 'POST'
    && endpoint(response) === '/api/v1/chat/deliverables/recovery-inputs'
    && response.request().postDataJSON()?.revision_id === original.id)
    .then(async response => ({ status: response.status(), value: dataOf(await response.json()) }));
  void recovery.catch(() => {});
  await page.getByRole('button', { name: '继续修改', exact: true }).click();
  const actual = await recovery;
  assert.equal(actual.status, 200);
  const continuation = validateRecovery(actual.value, original, wanted);
  await page.locator('.draft-reference').filter({ hasText: `成果版本 ${original.revision}` }).waitFor();
  assert.equal(await page.locator('.draft-reference').count(), continuation.references.length,
    'exact_visible_revision_reference_count_required');
  await page.getByRole('button', { name: '关闭成果面板', exact: true }).click();
  return continuation;
}

export function validateNativeSubmission(body, wanted, text, continuation) {
  assert.equal(body?.session_id, wanted.session_id, 'native_submit_session_identity_required');
  assert.equal(body.agent_id, wanted.agent_id, 'native_submit_agent_identity_required');
  assert.equal(body.context?.end_user_id, wanted.end_user_id, 'native_submit_end_user_identity_required');
  assert.match(body.client_message_id, /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i);
  assert.deepEqual(body.content, [{ type: 'text', text }], 'exact_native_user_text_required');
  assert.equal(Object.hasOwn(body, 'message'), false, 'legacy_chat_message_forbidden');
  assert.deepEqual(body.references, continuation.references, 'actual_sent_original_revision_reference_required');
  assert.equal(Object.hasOwn(body, 'artifact_inputs'), false, 'legacy_artifact_inputs_forbidden');
  assert.equal(Object.hasOwn(body, 'base_revision_id'), false, 'legacy_base_revision_forbidden');
  return { consumer: 'chat', session_id: body.session_id, agent_id: body.agent_id,
    end_user_id: body.context.end_user_id, input_id: body.client_message_id,
    references: structuredClone(body.references), references_sha256: digest(body.references),
    content_sha256: digest(body.content) };
}

export async function submitNativeContinuation(page, wanted, text, continuation, onRequest) {
  const requestReady = page.waitForRequest(request => request.method() === 'POST'
    && new URL(request.url()).pathname === '/api/v1/chat');
  const responseReady = page.waitForResponse(response => response.request().method() === 'POST'
    && endpoint(response) === '/api/v1/chat')
    .then(async response => ({ status: response.status(), value: dataOf(await response.json()) }));
  void requestReady.catch(() => {}); void responseReady.catch(() => {});
  await page.getByRole('button', { name: '发送消息', exact: true }).click();
  const observed = validateNativeSubmission((await requestReady).postDataJSON(), wanted, text, continuation);
  await onRequest(observed);
  const response = await responseReady;
  assert.equal(response.status, 200);
  const receipt = response.value;
  assert.equal(receipt?.session_id, wanted.session_id, 'native_receipt_session_identity_required');
  assert.equal(receipt.input_id, observed.input_id, 'native_receipt_actual_input_identity_required');
  assert.ok(typeof receipt.execution_id === 'string' && receipt.execution_id, 'native_receipt_run_identity_required');
  return { receipt, observed };
}
