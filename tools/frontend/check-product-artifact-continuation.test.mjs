import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
const source = readFileSync(new URL('./check-product-sessions.mjs', import.meta.url), 'utf8');
test('机构端缺失版本引用控件时D09使用显式Native消费者，移除旧文本注入helper', () => {
  assert.equal(source.includes('async function prepareArtifactContinuation('), false);
  assert.ok(source.includes('dispatchNativeRevision(nativePage, wanted, initial.run_id, index)'));
  assert.ok(source.includes("planned_turn_consumers: ['institution', 'chat']"));
  assert.ok(source.includes('.map(request => request.consumer)'));
  assert.ok(!source.includes('artifact_inputs base_revision_id'), 'legacy plain-text reference injection must stay removed');
});
