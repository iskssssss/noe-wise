import assert from 'node:assert/strict';
import { mkdir, writeFile, readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { createHash, randomUUID } from 'node:crypto';
import { createRequire } from 'node:module';
import { connectNative } from '../full_project_testing/live_product_controls.mjs';
import { frame, chatInputLimits } from './chat-public-browser-fixture.mjs';
const require = createRequire(new URL('../../frontend-admin/package.json', import.meta.url));
const { chromium, expect } = require('@playwright/test');
const origin = process.env.CHAT_TEST_ORIGIN || 'http://127.0.0.1:3200';
const root = resolve(process.env.PUBLICATION_BROWSER_OUTPUT || '.runtime-logs/publication-browser-20261019/controlled');
const baseline = process.env.PUBLICATION_BROWSER_BASELINE === '1';
const failedText = '成果发布未完成，本次交付失败。';
const candidate = 'CANDIDATE_PRIVATE_SUCCESS_MUST_NOT_APPEAR';
const readyText = '成果已正式发布，可以交付。';
const ok = data => ({ code: 'OK', message: '成功', data, trace_id: 'controlled-publication-browser' });
const gate = () => { let resolve; const promise = new Promise(done => { resolve = done; }); return { promise, resolve }; };
const history = (id, status = '', text = '') => ({ session_id: id, event_cursor: '10', messages: text ? [{ message_id: 'answer', message_version: 1, role: 'assistant', phase: 'final', content: text, run_id: 'run', root_run_id: 'run' }] : [], processes: [], pending_interactions: [], activities: status ? [{ node_type: 'agent_runtime', status, render: { root_run_id: 'run' } }] : [], collaboration: { active: false, pending: false, selected_active: false, todos: [] }, has_more: false });
const receipt = { scope: 'real frontend with controlled HTTP and SSE; not real host negative E2E', status: 'RUNNING', origin, baseline, cases: [], source_hashes: [], page_errors: [], unexpected_requests: [] };
let browser;
await mkdir(root, { recursive: true });
async function hashSources() {
  for (const path of ['frontend-chat/src/App.vue', 'frontend-chat/src/session.ts', 'frontend-chat/src/client.ts', 'tools/frontend/check-publication-exposure-browser.mjs']) {
    receipt.source_hashes.push({ path, sha256: createHash('sha256').update(await readFile(path)).digest('hex') });
  }
}
async function scenario(name, run) {
  if (process.env.PUBLICATION_BROWSER_CASE && process.env.PUBLICATION_BROWSER_CASE !== name) return;
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, reducedMotion: 'reduce' });
  const page = await context.newPage();
  page.setDefaultTimeout(10000);
  page.on('pageerror', error => receipt.page_errors.push({ name, message: error.message }));
  const state = { histories: {}, events: {}, requests: [], chatPosts: [], historyHandler: async id => ({ json: ok(history(id)) }), eventHandler: async () => ': heartbeat\n\n' };
  await page.route('**/api/v1/**', async route => {
    const request = route.request(), url = new URL(request.url()), path = url.pathname;
    const id = url.searchParams.get('session_id') || '';
    state.requests.push({ method: request.method(), path, session: id });
    let data;
    try {
      if (path === '/api/v1/chat/agents') data = { channel_id: 'controlled-channel', items: [{ id: 'agent', name: '受控发布验收助手', is_default: true }] };
      else if (path === '/api/v1/chat/client-profile') data = { channel_id: 'controlled-channel', end_user_id: '101', identity_token: 'controlled-identity-token' };
      else if (path === '/api/v1/chat/teams') data = [];
      else if (path === '/api/v1/chat/sessions' && request.method() === 'GET') data = [{ session_id: 'old', summary: '发布验收旧会话', message_count: 1, agent_id: 'agent' }, { session_id: 'new', summary: '发布验收新会话', message_count: 1, agent_id: 'agent' }];
      else if (path === '/api/v1/chat/history') { state.histories[id] = (state.histories[id] || 0) + 1; await route.fulfill(await state.historyHandler(id, state.histories[id])); return; }
      else if (path === '/api/v1/chat/events') { state.events[id] = (state.events[id] || 0) + 1; const body = await state.eventHandler(id, state.events[id]); if (!context.pages().length) return; await route.fulfill({ contentType: 'text/event-stream', body }); return; }
      else if (path === '/api/v1/chat/limits') data = chatInputLimits;
      else if (path === '/api/v1/chat/sessions/workspace') data = { workspaces: [] };
      else if (path === '/api/v1/chat/sessions/deliverables') data = [];
      else if (path === '/api/v1/chat/runs/status') data = { status: 'running' };
      else if (path === '/api/v1/chat/runs/steering') data = { run_id: 'run', accepting: false, items: [] };
      else if (path === '/api/v1/chat/commands') data = { items: [] };
      else if (path === '/api/v1/chat/work') data = null;
      else if (path === '/api/v1/chat/goals') data = { goal: null, continuation_enabled: false };
      else if (path === '/api/v1/chat') { state.chatPosts.push(request.postDataJSON()); await route.abort('failed'); return; }
      else { receipt.unexpected_requests.push({ name, path, method: request.method() }); await route.abort('blockedbyclient'); return; }
      await route.fulfill({ json: ok(data) });
    } catch (error) { if (context.pages().length && !String(error).includes('Target closed')) throw error; }
  });
  const item = { name, status: 'RUNNING', controlled_http: true, real_host_api: false };
  receipt.cases.push(item);
  try {
    await connectNative(page, { origin, channelId: 'controlled-channel', clientRef: randomUUID(), token: 'controlled-token', nickname: '受控浏览器验收' });
    await run({ page, state, context, item });
    assert.equal(state.chatPosts.length, name === 'unknown-post-not-replayed' ? 1 : 0);
    item.status = 'PASS';
  } catch (error) {
    item.status = 'FAILED'; item.error = String(error);
    await page.screenshot({ path: resolve(root, `${name}-failure.png`), fullPage: true }).catch(() => {});
    throw error;
  } finally {
    item.network = { histories: state.histories, events: state.events, chat_posts: state.chatPosts.length, requests: state.requests };
    await context.close();
  }
}
async function navigate(page, name) {
  if (!await page.getByRole('navigation', { name: '历史会话', exact: true }).isVisible()) await page.getByRole('button', { name: '打开会话导航', exact: true }).click();
  await page.getByRole('navigation', { name: '历史会话', exact: true }).getByRole('button', { name: new RegExp(name) }).click();
}
async function reconnect(page) {
  await page.getByRole('button', { name: '更多会话操作', exact: true }).click();
  await page.getByRole('button', { name: '重新连接', exact: true }).click();
}
async function safe(page) {
  assert.ok(!(await page.locator('body').innerText()).includes(candidate));
}
const pending = () => ({ status: 503, json: { code: 'TASK_OUTPUT_PUBLICATION_PENDING', message: '成果正在准备发布，请稍后重试。', trace_id: 'controlled-pending', data: null, retryable: true } });
try {
  await hashSources();
  browser = await chromium.launch({ headless: true });
  if (baseline) {
    await scenario('baseline-navigation-blocked', async ({ page, state, item }) => {
      state.historyHandler = async () => pending();
      await navigate(page, '发布验收旧会话');
      await expect.poll(() => state.histories.old).toBe(1);
      if (!await page.getByRole('navigation', { name: '历史会话', exact: true }).isVisible()) await page.getByRole('button', { name: '打开会话导航', exact: true }).click();
      const next = page.getByRole('navigation', { name: '历史会话', exact: true }).getByRole('button', { name: /发布验收新会话/ });
      await expect(next).toBeDisabled();
      item.observation = 'Read-only loading disables historical navigation; App.navigate also checks c.locked.';
      await page.screenshot({ path: resolve(root, 'baseline-navigation-disabled.png'), fullPage: true });
    });
  } else {
    await scenario('pending-to-ready', async ({ page, state }) => {
      const ready = gate(), events = gate();
      state.historyHandler = async (id, count) => { if (count === 1) return pending(); await ready.promise; return { json: ok(history(id, 'completed', readyText)) }; };
      state.eventHandler = async () => { await events.promise; return ': heartbeat\n\n'; };
      await navigate(page, '发布验收旧会话');
      await expect.poll(() => state.histories.old).toBe(1);
      await page.getByText('正在读取会话…', { exact: true }).waitFor();
      assert.equal(state.events.old || 0, 0);
      assert.equal(await page.locator('article.message.assistant').count(), 0);
      await safe(page);
      await page.screenshot({ path: resolve(root, 'pending.png'), fullPage: true });
      ready.resolve();
      await page.getByText(readyText, { exact: true }).waitFor();
      await expect.poll(() => state.events.old).toBe(1);
      await page.screenshot({ path: resolve(root, 'recovered.png'), fullPage: true });
    });
    await scenario('failed-history-live-reconnect', async ({ page, state }) => {
      const live = gate();
      state.historyHandler = async id => ({ json: ok(history(id, id === 'old' ? 'failed' : '', id === 'old' ? failedText : '')) });
      state.eventHandler = async (id, count) => { if (id === 'new' && count === 1) { await live.promise; return frame('11', 'message.delta', { message_id: 'failed-answer', message_version: 1, role: 'assistant', content: failedText, phase: 'final', run_id: 'run' }) + frame('12', 'execution.completed', { run_id: 'run', status: 'failed' }); } await new Promise(() => {}); return ''; };
      await navigate(page, '发布验收旧会话');
      await page.getByText(failedText, { exact: true }).waitFor(); await safe(page); await expect(page.locator('.conversation-header')).toContainText('失败');
      await page.screenshot({ path: resolve(root, 'failed-history.png'), fullPage: true });
      await reconnect(page); await page.getByText(failedText, { exact: true }).waitFor(); await safe(page); await expect(page.locator('.conversation-header')).toContainText('失败');
      await navigate(page, '发布验收新会话');
      await expect.poll(() => state.events.new).toBe(1);
      live.resolve();
      await page.getByText(failedText, { exact: true }).waitFor(); await safe(page); await expect(page.locator('.conversation-header')).toContainText('失败');
      await page.screenshot({ path: resolve(root, 'failed-live.png'), fullPage: true });
      state.historyHandler = async id => ({ json: ok(history(id, 'failed', failedText)) });
      await reconnect(page); await page.getByText(failedText, { exact: true }).waitFor(); await safe(page); await expect(page.locator('.conversation-header')).toContainText('失败');
      await expect.poll(() => state.events.new).toBe(2);
      await page.getByText(failedText, { exact: true }).waitFor(); await safe(page);
      await expect(page.locator('.conversation-header')).toContainText('失败');
    });
    await scenario('exhaustion-manual-reconnect', async ({ page, state }) => {
      state.historyHandler = async () => pending();
      await navigate(page, '发布验收旧会话');
      await expect.poll(() => state.histories.old, { timeout: 20000 }).toBe(5);
      await page.locator('.banner').filter({ hasText: '成果正在准备发布' }).waitFor();
      assert.equal(state.events.old || 0, 0);
      assert.equal(await page.getByText('正在读取会话…', { exact: true }).count(), 0);
      await safe(page);
      await page.screenshot({ path: resolve(root, 'exhausted.png'), fullPage: true });
      state.historyHandler = async id => ({ json: ok(history(id, 'completed', readyText)) });
      await reconnect(page);
      await page.getByText(readyText, { exact: true }).waitFor();
      await expect.poll(() => state.events.old).toBeGreaterThan(0);
      assert.equal(state.histories.old, 6);
      await page.screenshot({ path: resolve(root, 'manual-recovered.png'), fullPage: true });
    });
    await scenario('switch-cancels-old-read', async ({ page, state }) => {
      const stale = gate();
      state.historyHandler = async (id, count) => { if (id === 'old') { if (count === 1) return pending(); await stale.promise; return { json: ok(history(id, 'completed', candidate)) }; } return { json: ok(history(id, 'completed', '新会话已就绪。')) }; };
      await navigate(page, '发布验收旧会话');
      await expect.poll(() => state.histories.old).toBe(1);
      await navigate(page, '发布验收新会话');
      await page.getByText('新会话已就绪。', { exact: true }).waitFor();
      // 等待超出旧会话首个重试定时器；该短等待验证取消后没有安排第二个 GET。
      await page.waitForTimeout(1200);
      assert.equal(state.histories.old, 1);
      stale.resolve();
      await safe(page);
      assert.equal(state.events.old || 0, 0);
      await page.screenshot({ path: resolve(root, 'switch-recovered.png'), fullPage: true });
    });
    await scenario('unknown-post-not-replayed', async ({ page, state }) => {
      await navigate(page, '发布验收旧会话');
      await page.getByRole('textbox', { name: '消息', exact: true }).fill('本次只能提交一次');
      await page.getByRole('button', { name: '发送消息', exact: true }).click();
      await expect.poll(() => state.chatPosts.length).toBe(1);
      await page.locator('.banner').waitFor();
      let reads = 0;
      state.historyHandler = async id => ++reads === 1 ? pending() : ({ json: ok(history(id)) });
      await reconnect(page);
      await expect.poll(() => reads).toBe(2);
      await expect.poll(() => state.events.old).toBeGreaterThan(1);
      assert.equal(state.chatPosts.length, 1);
      assert.deepEqual(state.chatPosts[0].content, [{ type: 'text', text: '本次只能提交一次' }]);
      await safe(page);
    });
  }
  assert.deepEqual(receipt.page_errors, []);
  assert.deepEqual(receipt.unexpected_requests, []);
  receipt.status = 'PASS';
} catch (error) { receipt.status = 'FAILED'; receipt.error = String(error); process.exitCode = 1; }
finally { await browser?.close(); receipt.finished_at = new Date().toISOString(); await writeFile(resolve(root, 'results.json'), JSON.stringify(receipt, null, 2)); console.log(JSON.stringify({ status: receipt.status, cases: receipt.cases.map(({ name, status }) => ({ name, status })), evidence: root, error: receipt.error })); }
