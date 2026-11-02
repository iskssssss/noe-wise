import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import assert from 'node:assert/strict';

// 显式计划执行器：结果不明只读回执，绝不自动重发副作用。
const args = Object.fromEntries(process.argv.slice(2).reduce((a, value, i, all) => i % 2 ? a : [...a, [value.replace(/^--/, ''), all[i + 1]]], []));
assert.ok(args.plan && args.evidence && args.service && args.owner, 'EXPLICIT_PLAN_OWNER_AND_OUTPUT_REQUIRED');
assert.equal(args['isolated-owner'], 'confirmed', 'DEDICATED_TEST_OWNER_CONFIRMATION_REQUIRED');
const service = new URL(args.service);
assert.ok(service.hostname === '127.0.0.1' && service.protocol === 'http:' && service.pathname === '/', 'LOOPBACK_SERVICE_REQUIRED');
const token = process.env.BROWSER_ACCEPTANCE_SERVICE_TOKEN;
assert.ok(token, 'BROWSER_ACCEPTANCE_SERVICE_TOKEN_REQUIRED');
const plan = JSON.parse(fs.readFileSync(args.plan, 'utf8'));
const owner = JSON.parse(fs.readFileSync(args.owner, 'utf8'));
assert.equal(plan.upstream, '3f10983c09fcd443c7420830ab984c9d409954bf');
assert.ok(/^[1-9][0-9]*$/.test(owner.tenant) && /^[1-9][0-9]*$/.test(owner.endUser));
assert.ok(owner.browser && owner.session && owner.run, 'EXPLICIT_ISOLATED_BROWSER_AND_PROVENANCE_REQUIRED');
const output = path.resolve(args.evidence); fs.mkdirSync(output, {recursive: true});
const journalPath = path.join(output, 'execution.json');
// 同目录再次运行只允许读既有证据，避免模糊失败后另起任务。
assert.ok(!fs.existsSync(journalPath), 'EXISTING_JOURNAL_USE_RECEIPT_QUERY_INSTEAD_OF_RESUBMISSION');
const journal = {status: 'running', scope: 'browser-service-and-owned-network-only', productSessionVerified: false, humanInteractionVerified: false, upstream: plan.upstream, steps: [], assertions: []};
const save = () => fs.writeFileSync(journalPath, JSON.stringify(journal, null, 2));
save();
async function api(route, method, body) {
  const response = await fetch(new URL(route, service), {method, headers: {'X-Browser-Service-Token': token, ...(body ? {'Content-Type': 'application/json'} : {})}, body: body ? JSON.stringify(body) : undefined, redirect: 'error', signal: AbortSignal.timeout(95000)});
  assert.ok(response.ok, `BROWSER_HTTP_${response.status}`); return response.json();
}
async function facts(origin) {
  const url = new URL(origin); assert.equal(url.hostname, '127.0.0.1'); assert.equal(url.protocol, 'http:');
  const response = await fetch(new URL('/__facts', url), {redirect: 'error', signal: AbortSignal.timeout(5000)});
  assert.ok(response.ok); return response.json();
}
function substitute(value, context) {
  if (typeof value === 'string') return value.replace(/\$\{([\w.]+)\}/g, (_, key) => {const found = key.split('.').reduce((a, k) => a?.[k], context); assert.ok(found !== undefined, `UNRESOLVED_${key}`); return String(found);});
  if (Array.isArray(value)) return value.map(v => substitute(v, context));
  if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, substitute(v, context)]));
  return value;
}
const context = {browser: owner.browser, ...plan.variables};
let task;
try {
  for (const step of plan.steps) {
    assert.ok(/^[a-z0-9-]+$/.test(step.name) && !journal.steps.some(s => s.name === step.name), 'UNIQUE_STEP_NAME_REQUIRED');
    const invocation = `local-network:${crypto.randomUUID()}`;
    const request = {tenant: owner.tenant, end_user: owner.endUser, session: owner.session, run: owner.run, invocation, task: step.operation === 'start' ? null : task, operation: step.operation, parameters: {_operation: step.operation, ...substitute(step.parameters, context)}, deadline_at: new Date(Date.now() + 90000).toISOString()};
    const entry = {name: step.name, invocation, operation: step.operation, status: 'submitted'}; journal.steps.push(entry); save();
    let receipt;
    try {receipt = await api('/internal/browser/execute', 'POST', request);}
    catch {receipt = (await api(`/internal/browser/tasks?${new URLSearchParams({tenant: owner.tenant, user: owner.endUser})}`, 'GET')).find(r => r.invocation === invocation);}
    entry.receipt = receipt; entry.status = receipt?.status ?? 'unknown'; save();
    assert.ok(receipt, 'RESULT_UNKNOWN_NO_REPLAY');
    if (step.operation === 'start' && receipt.task) {task = receipt.task; context.task = task;}
    assert.equal(receipt.status, step.expectedStatus ?? 'succeeded', `${step.name}_STATUS`);
    context[step.name.replaceAll('-', '_')] = receipt;
    // 捕获固定上游返回字段时使用显式路径，不猜 request/rule ID。
    for (const [key, field] of Object.entries(step.capture ?? {})) {
      let value;
      if (typeof field === 'string') value = field.split('.').reduce((a, k) => a?.[k], receipt);
      else {const items = field.path.split('.').reduce((a, k) => a?.[k], receipt); assert.ok(Array.isArray(items), `CAPTURE_${key}_ARRAY_REQUIRED`); const matching = items.filter(item => item[field.where.field] === substitute(field.where.equals, context)); assert.equal(matching.length, 1, `CAPTURE_${key}_NOT_UNIQUE`); value = matching[0][field.value];}
      assert.ok(value !== undefined, `CAPTURE_${key}_MISSING`); context[key] = value;
    }
    for (const expectation of step.result ?? []) {const actual = expectation.path.split('.').reduce((a, k) => a?.[k], receipt); assert.deepEqual(actual, substitute(expectation.equals, context), `${step.name}_RESULT_${expectation.path}`);}
    for (const expectation of step.network ?? []) {
      const snapshot = await facts(substitute(expectation.origin, context));
      const events = snapshot.events.filter(e => e.case === expectation.case);
      assert.equal(events.length, expectation.count, `${step.name}_SERVER_COUNT`);
      for (const e of events) {if (expectation.method) assert.equal(e.method, expectation.method); if (expectation.body !== undefined) assert.equal(e.body, expectation.body); if (expectation.testHeader !== undefined) assert.equal(e.testHeader, expectation.testHeader);}
      journal.assertions.push({step: step.name, expectation, fixtureRun: snapshot.run, events}); save();
    }
  }
  journal.status = 'passed';
} catch (error) {journal.status = 'failed'; journal.error = error.message; process.exitCode = 1;}
finally {
  // 原任务显式停止；失败不改写已有动作结果。精确停止失败单独留证。
  if (task && !journal.steps.some(s => s.operation === 'stop' && s.status === 'succeeded')) {
    try {journal.cleanup = await api('/internal/browser/execute', 'POST', {tenant: owner.tenant, end_user: owner.endUser, session: owner.session, run: owner.run, invocation: `local-network-cleanup:${crypto.randomUUID()}`, task, operation: 'stop', parameters: {_operation: 'stop'}, deadline_at: new Date(Date.now() + 30000).toISOString()});}
    catch {journal.cleanup = {status: 'unknown'};}
    if (journal.cleanup.status !== 'succeeded') {journal.status = 'failed'; process.exitCode = 1;}
  }
  save(); console.log(JSON.stringify({status: journal.status, evidence: journalPath, humanInteractionVerified: false, productSessionVerified: false}));
}
