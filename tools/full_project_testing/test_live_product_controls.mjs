import assert from 'node:assert/strict';
import test from 'node:test';
import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';
import { dataOf, nativeAgentLabel, connectNative, readNativeSession } from './live_product_controls.mjs';

test('同名智能体按实际完整ID消歧，不依赖角色描述作为名称', () => {
  const one = { id: '100000000000001', name: '订单助手', role_description: '合成职责' };
  const two = { id: '100000000000002', name: '订单助手' };
  assert.equal(nativeAgentLabel(one, [one]), one.name);
  assert.equal(nativeAgentLabel(one, [one, two]), '订单助手（100000000000001）');
  assert.equal(nativeAgentLabel(two, [one, two]), '订单助手（100000000000002）');
});

test('错误或缺失真实服务信封不能被视为成功数据', () => {
  for (const response of [undefined, {}, { code: 'UNAUTHORIZED', data: [] }]) {
    assert.throws(() => dataOf(response), /actual_service_envelope_required/);
  }
  const data = [{ id: 'exact-id' }];
  assert.equal(dataOf({ code: 'OK', data }), data);
});

test('无效本机身份在任何浏览器请求前拒绝，不绕过正式UUID合同', async () => {
  const page = new Proxy({}, { get() { throw new Error('must_not_touch_page'); } });
  await assert.rejects(connectNative(page, { clientRef: 'legacy-test-prefix' }), /current_client_ref_uuid_contract_required/);
});

test('刷新回读忽略导航前目录响应，并读取实际Session的历史身份', async () => {
  const wanted = { agent_id: 'agent-1', session_id: 'session-1' };
  const history = { session_id: wanted.session_id, messages: [] };
  let directoryWait, historyWait, staleBodyRead = false, freshBodyRead = false;
  const response = (path, startedAt, data, stale = false) => ({
    url: () => `http://127.0.0.1/api/v1/chat/${path}`,
    request: () => ({ method: () => 'GET', timing: () => ({ startTime: startedAt }) }),
    status: () => 200,
    json: async () => {
      if (stale) { staleBodyRead = true; throw new Error('navigated_away_response_body'); }
      if (path.startsWith('sessions')) freshBodyRead = true;
      return { code: 'OK', data };
    }
  });
  const locator = {
    click: async () => {}, filter() { return this; }, getByRole() { return this; },
    nth: () => ({ click: async () => {
      const actual = response(`history?session_id=${wanted.session_id}`, Date.now(), history);
      assert.equal(historyWait.predicate(actual), true); historyWait.resolve(actual);
    } })
  };
  const page = {
    getByRole: () => locator, getByText: () => locator, waitForFunction: async () => {},
    waitForResponse(predicate) {
      return new Promise(resolve => {
        if (!directoryWait) directoryWait = { predicate, resolve };
        else historyWait = { predicate, resolve };
      });
    },
    async reload() {
      const stale = response(`sessions?agentId=${wanted.agent_id}`, 1, [], true);
      assert.equal(directoryWait.predicate(stale), false);
      const fresh = response(`sessions?agentId=${wanted.agent_id}`, Date.now(), [wanted]);
      assert.equal(directoryWait.predicate(fresh), true); directoryWait.resolve(fresh);
    }
  };
  assert.equal(await readNativeSession(page, wanted, { items: [{ id: wanted.agent_id, name: '助手' }] }), history);
  assert.equal(staleBodyRead, false);
  assert.equal(freshBodyRead, true);
});

test('D09已经连接的Native页用于第二轮后回读，不重复查找bootstrap或创建profile', async () => {
  const source = readFileSync(new URL('../frontend/check-product-sessions.mjs', import.meta.url), 'utf8');
  const known = { profile: { end_user_id: 'user-1' }, catalog: { items: [] } }, page = {};
  let registrations = 0;
  const connect = runInNewContext(`(${source.slice(source.indexOf('async function connectChat('),
    source.indexOf('async function dispatchNativeRevision('))})`, {
    nativeConnections: new WeakMap(), chatOrigin: 'http://127.0.0.1',
    chat: { channelId: 'channel', clientRef: 'client', token: 'private', nickname: '用户' },
    connectNative: async () => { registrations++; return known; }
  });
  assert.equal(await connect(page, {}), known);
  assert.equal(await connect(page, {}), known);
  assert.equal(registrations, 1);
});
