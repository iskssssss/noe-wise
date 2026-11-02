import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { randomUUID } from 'node:crypto';

// 复用项目已安装的 DOM 测试依赖；不会启动浏览器或共享服务。
const require = createRequire(new URL('../../frontend-admin/package.json', import.meta.url));
const { JSDOM } = require('jsdom');
const html = readFileSync(new URL('./index.html', import.meta.url), 'utf8');
const script = html.match(/<script>([\s\S]*?)<\/script>/)[1];

function fixture() {
  return {
    capabilities: { settlement_reconciliation: true },
    billing: { pricing: { revision: 'price-1', currency: 'CNY', input_cents_per_thousand_tokens: '2', output_cents_per_thousand_tokens: '7', tool_cents_per_call: '3' } },
    wallets: [], inbox: [], pauses: [], media_receipts: [], usage_events: [],
    settlement: {
      objects: [{ object_id: 'object-1', revision: 3, execution_tenant_id: '9007199254740993', currency: 'CNY', metering_object_ref: 'attempt:original', pricing_revision: 'price-1', measurement_status: 'known', pricing_status: 'priced', target_amount: '100.000000000001', confirmed_net_amount: '0', settlement_gap: '100.000000000001' }],
      instructions: [{ object_id: 'object-1', instruction_id: 'rejected-1', sequence: 1, previous_instruction_id: null, status: 'rejected', target_amount: '100.000000000001', receipt: { receipt_id: 'receipt-1', reason: '<img src=x onerror=alert(1)>', delta: '0' } }],
    },
  };
}

const tick = () => new Promise(resolve => setImmediate(resolve));
function response(status, body) { return { status, ok: status >= 200 && status < 300, json: async () => body }; }

async function harness(snapshot = fixture()) {
  const dom = new JSDOM(html.replace(/<script>[\s\S]*?<\/script>/, ''), { url: 'http://127.0.0.1:18888/', runScripts: 'outside-only' });
  const w = dom.window, calls = [];
  const state = { snapshot, post: async () => response(200, { instruction_id: 'successor-2', dispatch: { applied: 1 } }), readFails: false };
  w.console.debug = () => {};
  w.confirm = () => true;
  w.setInterval = () => 1;
  w.clearInterval = () => {};
  Object.defineProperty(w.crypto, 'randomUUID', { value: randomUUID });
  w.fetch = async (url, options = {}) => {
    calls.push({ url, options });
    if (options.method === 'POST') return state.post(url, options);
    if (state.readFails) throw Error('simulated snapshot outage');
    return response(200, structuredClone(state.snapshot));
  };
  w.eval(script + '\nwindow.uiTest={get draft(){return reconciliationDraft;},get snapshot(){return settlementSnapshot;},beginReconciliation,submitReconciliation,reconcileLatest,settlementView,canReconcile,instructionState};');
  await tick();
  const el = id => w.document.getElementById(id);
  const edit = () => {
    w.uiTest.beginReconciliation(w.uiTest.snapshot.settlement.objects[0]);
    el('reconciliation-reason').value = '余额恢复，人工核验后重新结算';
    el('reconciliation-token').value = 'synthetic-management-token';
  };
  return { dom, w, calls, state, el, edit, close: () => dom.window.close() };
}

test('真实状态字段显示序号、前序、差额和拒绝原因，未执行来源 HTML', async () => {
  const h = await harness();
  try {
    const text = h.w.document.body.textContent;
    assert.match(text, /100\.000000000001/);
    assert.match(text, /9007199254740993/);
    assert.match(text, /明确拒绝/);
    assert.match(text, /首笔，无前序/);
    assert.match(text, /<img src=x onerror=alert\(1\)>/);
    assert.equal(h.w.document.querySelectorAll('img').length, 0);
    assert.equal(h.el('reconciliation-section').hidden, false);
    assert.equal(h.el('charges').querySelectorAll('button').length, 1);
    assert.equal(h.w.uiTest.instructionState({ status: 'unknown', receipt: { outcome: 'waiting_predecessor' } }), 'waiting_predecessor');
  } finally { h.close(); }
});

test('管理能力关闭时不展示写入口；UNKNOWN 与等待前序没有重新结算入口', async () => {
  const snapshot = fixture();snapshot.capabilities.settlement_reconciliation = false;
  const h = await harness(snapshot);
  try {
    assert.equal(h.el('reconciliation-section').hidden, true);
    assert.equal(h.el('charges').querySelectorAll('button').length, 0);
    snapshot.capabilities.settlement_reconciliation = true;
    snapshot.settlement.instructions[0].status = 'unknown';
    snapshot.settlement.instructions[0].receipt = { outcome: 'waiting_predecessor' };
    h.w.uiTest.settlementView(snapshot);
    assert.match(h.el('instructions').textContent, /等待前序终结/);
    assert.equal(h.el('charges').querySelectorAll('button').length, 0);
    snapshot.settlement.instructions[0].receipt = null;
    h.w.uiTest.settlementView(snapshot);
    assert.match(h.el('instructions').textContent, /UNKNOWN/);
    assert.equal(h.el('charges').querySelectorAll('button').length, 0);
  } finally { h.close(); }
});

test('提交固定对象、版本和幂等键；重复点击只发一次，凭证不进入存储或快照', async () => {
  const h = await harness();
  try {
    h.edit();let release;
    h.state.post = () => new Promise(resolve => { release = resolve; });
    const first = h.w.uiTest.submitReconciliation();
    await h.w.uiTest.submitReconciliation();
    assert.equal(h.calls.filter(call => call.options.method === 'POST').length, 1);
    const sent = h.calls.find(call => call.options.method === 'POST');
    assert.equal(sent.url, '/api/v1/settlement-reconciliations');
    assert.equal(JSON.parse(sent.options.body).expected_revision, 3);
    assert.equal(sent.options.headers.Authorization, 'Bearer synthetic-management-token');
    assert.match(sent.options.headers['Idempotency-Key'], /^demo-reconciliation:/);
    assert.equal(h.el('reconciliation-token').value, '');
    assert.equal(h.el('reconciliation-submit').disabled, true);
    assert.equal(h.w.localStorage.length, 0);assert.equal(h.w.sessionStorage.length, 0);
    assert.equal(JSON.stringify(h.w.uiTest.snapshot).includes('synthetic-management-token'), false);
    release(response(200, { instruction_id: 'successor-2', dispatch: { applied: 1 } }));await first;
    assert.match(h.el('reconciliation-status').textContent, /已受理后继指令/);
    // 写入已受理但旧快照尚未见到新指令时，不重新开放旧拒绝入口。
    assert.equal(h.el('charges').querySelectorAll('button').length, 0);
  } finally { h.close(); }
});

test('网络 UNKNOWN 保留原因及原请求身份；显式核对仍使用同 key/body', async () => {
  const h = await harness();
  try {
    h.edit();h.state.post = async () => { throw Error('lost response'); };
    await h.w.uiTest.submitReconciliation();
    const first = h.calls.find(call => call.options.method === 'POST');
    assert.equal(h.w.uiTest.draft.state, 'unknown');
    assert.equal(h.el('reconciliation-reason').readOnly, true);
    assert.equal(h.el('reconciliation-close').disabled, true);
    assert.match(h.el('reconciliation-status').textContent, /UNKNOWN/);
    h.el('reconciliation-token').value = 'synthetic-rotated-management-token';
    h.state.post = async () => response(200, { instruction_id: 'successor-2', dispatch: { unknown: 1 } });
    await h.w.uiTest.submitReconciliation();
    const posts = h.calls.filter(call => call.options.method === 'POST');
    assert.equal(posts.length, 2);assert.equal(posts[1].options.body, first.options.body);
    assert.equal(posts[1].options.headers['Idempotency-Key'], first.options.headers['Idempotency-Key']);
    assert.equal(h.w.uiTest.draft.state, 'accepted');
    assert.equal(h.el('reconciliation-close').disabled, false);
  } finally { h.close(); }
});

test('当前管理指令已入账不受另一对象 UNKNOWN 的全局派发计数锁住', async () => {
  const h = await harness();
  try {
    h.edit();
    h.state.snapshot.settlement.objects.push({ ...h.state.snapshot.settlement.objects[0], object_id: 'object-2', metering_object_ref: 'attempt:other' });
    h.state.snapshot.settlement.instructions.push({ object_id: 'object-2', instruction_id: 'other-unknown', sequence: 1, status: 'unknown', target_amount: '100', receipt: null });
    h.state.post = async () => {
      h.state.snapshot.settlement.instructions.push({ object_id: 'object-1', instruction_id: 'successor-2', sequence: 2, previous_instruction_id: 'rejected-1', status: 'applied', target_amount: '100.000000000001', receipt: { receipt_id: 'receipt-2', outcome: 'applied', delta: '100.000000000001' } });
      h.state.snapshot.settlement.objects[0].confirmed_net_amount = '100.000000000001';
      h.state.snapshot.settlement.objects[0].settlement_gap = '0';
      return response(200, { instruction_id: 'successor-2', dispatch: { applied: 1, unknown: 1, waiting: 1 } });
    };
    await h.w.uiTest.submitReconciliation();
    assert.equal(h.w.uiTest.draft.state, 'accepted');
    assert.equal(h.el('reconciliation-close').disabled, false);
    assert.match(h.el('reconciliation-status').textContent, /该指令钱包状态：已入账/);
    assert.doesNotMatch(h.el('reconciliation-status').textContent, /UNKNOWN/);
    assert.match(h.el('instructions').textContent, /UNKNOWN/);
    h.el('reconciliation-close').click();
    assert.equal(h.w.uiTest.draft, undefined);
  } finally { h.close(); }
});

test('管理已受理但钱包未定或刷新失败仍可关闭，并保留原指令及旧快照写保护', async () => {
  const h = await harness();
  try {
    h.edit();
    h.state.post = async () => {
      h.state.readFails = true;
      return response(200, { instruction_id: 'successor-2', dispatch: { unknown: 1 } });
    };
    await h.w.uiTest.submitReconciliation();
    assert.equal(h.w.uiTest.draft.state, 'accepted');
    assert.match(h.el('reconciliation-status').textContent, /已受理后继指令 successor-2/);
    assert.match(h.el('reconciliation-status').textContent, /尚未读取到该指令的钱包回执/);
    assert.equal(h.el('reconciliation-close').disabled, false);
    assert.equal(h.el('reconciliation-reason').value, '余额恢复，人工核验后重新结算');
    assert.equal(h.el('charges').querySelectorAll('button').length, 0);
    h.el('reconciliation-close').click();
    assert.equal(h.el('charges').querySelectorAll('button').length, 0);
  } finally { h.close(); }
});

test('409 主动重读但不重试；保留原因，由用户按最新版本核对后生成新请求', async () => {
  const h = await harness();
  try {
    h.edit();h.state.post = async () => response(409, { error: 'SETTLEMENT_OBJECT_REVISION_CONFLICT' });
    h.state.snapshot.settlement.objects[0].revision = 4;
    await h.w.uiTest.submitReconciliation();
    assert.equal(h.w.uiTest.draft.state, 'conflict');
    assert.equal(h.calls.filter(call => call.options.method === 'POST').length, 1);
    assert.match(h.el('reconciliation-current').textContent, /本次核验版本 3 · 当前版本 4/);
    assert.equal(h.el('reconciliation-reason').value, '余额恢复，人工核验后重新结算');
    const oldKey = h.w.uiTest.draft.key;
    h.w.uiTest.reconcileLatest();
    assert.equal(h.w.uiTest.draft.expectedRevision, 4);
    h.el('reconciliation-token').value = 'synthetic-management-token';
    h.state.post = async () => response(200, { instruction_id: 'successor-2', dispatch: { rejected: 1 } });
    await h.w.uiTest.submitReconciliation();
    const posts = h.calls.filter(call => call.options.method === 'POST');
    assert.notEqual(posts[1].options.headers['Idempotency-Key'], oldKey);
    assert.equal(JSON.parse(posts[1].options.body).expected_revision, 4);
    assert.doesNotMatch(h.el('reconciliation-status').textContent, /扣款成功/);
  } finally { h.close(); }
});

test('409 后重读失败不能拿旧快照重新提交；管理拒绝保留可修正原因', async () => {
  const h = await harness();
  try {
    h.edit();h.state.post = async () => response(409, {});h.state.readFails = true;
    await h.w.uiTest.submitReconciliation();
    assert.equal(h.el('reconciliation-latest').disabled, true);
    h.w.uiTest.reconcileLatest();assert.equal(h.w.uiTest.draft.state, 'conflict');
    h.state.readFails = false;await h.w.load();h.w.uiTest.reconcileLatest();
    h.el('reconciliation-token').value = 'synthetic-wrong-token';
    h.state.post = async () => response(403, {});
    await h.w.uiTest.submitReconciliation();
    assert.equal(h.w.uiTest.draft.state, 'failed');
    assert.equal(h.el('reconciliation-reason').readOnly, false);
    assert.equal(h.el('reconciliation-reason').value, '余额恢复，人工核验后重新结算');
    assert.equal(h.el('reconciliation-token').value, '');
  } finally { h.close(); }
});
