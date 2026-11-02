import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';
import test from 'node:test';

// 隔离纯终态匹配规则，避免运行生产脚本的浏览器和登录顶层逻辑。
const source = readFileSync(new URL('./check-product-sessions.mjs', import.meta.url), 'utf8');
const factory = runInNewContext(`(() => {${source.slice(source.indexOf('const COMPLETED_RUN_STATUSES'),
  source.indexOf('\nfunction capturePublicPrivacy('))}; return { terminalStatusesFor, terminalFactMatches };})()`, { Set });

test('D11 的 DELEGATED 等待态不能结束浏览器消息轮次', () => {
  const statuses = factory.terminalStatusesFor('D11', 'message');
  assert.equal(factory.terminalFactMatches('run-1', statuses, { run_id: 'run-1', status: 'waiting_hitl' }), false);
  assert.equal(factory.terminalFactMatches('run-1', statuses, { run_id: 'run-1', status: 'delegated' }), false);
  assert.equal(factory.terminalFactMatches('run-1', statuses, { run_id: 'run-1', status: 'completed' }), true);
});

test('D10 首轮仍接受人工确认等待态，确认轮仅接受真正终态', () => {
  assert.equal(factory.terminalStatusesFor('D10', 'message').has('waiting_hitl'), true);
  assert.equal(factory.terminalStatusesFor('D10', 'hitl').has('waiting_hitl'), false);
  assert.equal(factory.terminalStatusesFor('D10', 'hitl').has('completed'), true);
});

test('D09 首轮失败后不准备产物续写或发送第二轮', async () => {
  const fills = [];
  const journal = [];
  const dispatch = runInNewContext(`(${source.slice(source.indexOf('async function dispatchInstitutionTurns('),
    source.indexOf('\nasync function observeChat('))})`, {
    readOnly: false, journalLines: [], journalledSessions: new Set(),
    turnKind: () => 'message', textDigest: () => 'digest',
    appendJournal: async row => journal.push(row),
    responseJson: async () => ({ session_id: 'session', execution_id: 'run', input_id: 'input' }),
    terminalStatusesFor: () => new Set(['completed', 'failed', 'cancelled']),
    selectAdminSession: async () => { throw new Error('must not open continuation'); },
    prepareArtifactContinuation: async () => { throw new Error('must not prepare artifact'); },
  });
  const page = { getByPlaceholder: () => ({ fill: async text => fills.push(text) }) };
  const receipts = await dispatch(page, { case_id: 'D09', session_id: 'session',
    turns: [{ text: 'generate' }, { text: 'modify' }] },
    { mark: () => 0, waitForRun: async () => ({ event: 'execution.completed', run_id: 'run', status: 'failed' }) });
  assert.equal(fills.length, 1);
  assert.equal(fills[0], 'generate');
  assert.equal(receipts.length, 1);
  assert.equal(journal.at(-1).status, 'failed');
});

test('D09首轮成功后仅从Native发第二轮，沿用实际首轮Run并合并真实终态', async () => {
  const sends = [], nativeCalls = [], journal = [];
  const dispatch = runInNewContext(`(${source.slice(source.indexOf('async function dispatchInstitutionTurns('),
    source.indexOf('async function observeChat('))})`, {
    readOnly: false, journalLines: [], journalledSessions: new Set(),
    turnKind: () => 'message', textDigest: () => 'digest', appendJournal: async row => journal.push(row),
    responseJson: async () => ({ session_id: 'session', execution_id: 'run-first', input_id: 'input-first' }),
    terminalStatusesFor: () => new Set(['completed', 'failed', 'cancelled']),
    dispatchNativeRevision: async (page, wanted, initialRun, index) => {
      nativeCalls.push({ page, wanted, initialRun, index });
      return { receipt: { turn_index: index, kind: 'message', consumer: 'chat', run_id: 'run-second',
        terminal_status: 'completed' }, facts: [{ run_id: 'run-second', status: 'completed' }] };
    }
  });
  const page = { getByPlaceholder: () => ({ fill: async text => sends.push(text) }) }, native = {};
  const terminalCapture = { facts: [], mark: () => 0,
    waitForRun: async () => ({ event: 'execution.completed', run_id: 'run-first', status: 'completed' }) };
  const receipts = await dispatch(page, { case_id: 'D09', session_id: 'session',
    turns: [{ text: 'generate' }, { text: 'modify' }] }, terminalCapture, native);
  assert.equal(sends.length, 1); assert.equal(nativeCalls.length, 1);
  assert.equal(nativeCalls[0].page, native); assert.equal(nativeCalls[0].initialRun, 'run-first');
  assert.equal(nativeCalls[0].index, 1); assert.equal(receipts[1].consumer, 'chat');
  assert.equal(terminalCapture.facts[0].run_id, 'run-second');
});

test('刷新后旁读页面历史终态，保留来源且不接受其他会话或非终态', async () => {
  const listeners = {};
  const capture = runInNewContext(`(${source.slice(source.indexOf('async function captureTerminalFacts('),
    source.indexOf('\nasync function loginAdmin('))})`, {
    URL, setTimeout, clearTimeout, runTimeoutMs: 1000,
    COMPLETED_RUN_STATUSES: new Set(['completed', 'failed', 'cancelled']),
    terminalFactMatches: factory.terminalFactMatches,
    dataOf: body => body.code === 'OK' ? body.data : undefined,
  });
  const page = { exposeBinding: async () => {}, addInitScript: async () => {}, evaluate: async () => {},
    on: (event, callback) => { listeners[event] = callback; } };
  const observer = await capture(page, 'session');
  const emit = (session, status, nodeType = 'agent_runtime', code = 'OK') => listeners.response({
    url: () => `http://localhost/api/v1/chat/history?session_id=${session}`,
    request: () => ({ method: () => 'GET' }), ok: () => true,
    json: async () => ({ code, data: { activities: [{ node_type: nodeType, node_id: 'run', status }] } }),
  });
  emit('other', 'cancelled');
  emit('session', 'running');
  emit('session', 'waiting_hitl');
  emit('session', 'cancelled', 'tool');
  emit('session', 'cancelled', 'agent_runtime', 'ERROR');
  await Promise.all(observer.pending);
  assert.equal(observer.facts.length, 0);
  const waiting = observer.waitForRun('run');
  emit('session', 'cancelled');
  const terminal = await waiting;
  assert.equal(terminal.event, 'history.run_terminal');
  assert.equal(terminal.source, 'browser_history_response');
  assert.equal(terminal.status, 'cancelled');
  assert.equal((await observer.waitForRun('run')).run_id, 'run');
});
