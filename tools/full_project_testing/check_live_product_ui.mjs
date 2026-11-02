/* Read-only current-consumer preflight. No channel/session/resource creation or paid input. */
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { createServer as createHttpServer, request as upstreamRequest } from 'node:http';
import { dirname, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { readAdminArtifacts } from './live_product_controls.mjs';

const repo = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const require = createRequire(resolve(repo, 'frontend-admin/package.json'));
const { chromium, expect } = require('@playwright/test');
const { createServer } = await import(pathToFileURL(require.resolve('vite')).href);
const environmentRoot = resolve(repo, '.runtime-logs/full-project-test-20261012/environment');
const refs = JSON.parse(await readFile(resolve(environmentRoot, 'provision.json'), 'utf8'));
const environment = { ...process.env };
for (const line of (await readFile(resolve(environmentRoot, 'isolated.env'), 'utf8')).split(/\r?\n/)) {
  const match = line.match(/^\s*([A-Za-z_][A-Za-z0-9_]*)=(.*)$/);
  if (match) {
    let value = match[2].trim();
    if (value.length > 1 && value[0] === value.at(-1) && ['"', "'"].includes(value[0])) value = value.slice(1, -1);
    environment[match[1]] = value;
  }
}
const initialRoot = resolve(repo, '.runtime-logs/full-project-test-20261012/product-ui-preflight');
const diagnostic = process.argv.find(value => /^--diagnostic(?:=\d+)?$/.test(value));
const outputRoot = diagnostic ? resolve(initialRoot, `diagnostic-${diagnostic.split('=')[1] || '1'}`) : initialRoot;
await mkdir(outputRoot, { recursive: false });
const target = new URL(refs.api);
assert.equal(target.protocol, 'http:');
assert.equal(target.hostname, '127.0.0.1');
const requests = [], blocked = [], servers = [];
const allowedWrites = new Set(['/api/v1/auth/login', '/api/v1/auth/refresh']);
const gate = createHttpServer((request, response) => {
  const path = new URL(request.url, 'http://127.0.0.1').pathname;
  const entry = { method: request.method, path };
  if (!['GET', 'HEAD', 'OPTIONS'].includes(request.method) && !allowedWrites.has(path)) {
    blocked.push(entry);
    response.writeHead(503, { 'Content-Type': 'application/json' });
    response.end('{"code":"READONLY_PREFLIGHT","data":null}');
    return;
  }
  const upstream = upstreamRequest({ hostname: target.hostname, port: target.port,
    path: request.url, method: request.method, headers: { ...request.headers, host: target.host } }, actual => {
    requests.push({ ...entry, status: actual.statusCode });
    response.writeHead(actual.statusCode, actual.headers);
    actual.pipe(response);
  });
  upstream.on('error', () => { response.writeHead(502); response.end(); });
  request.pipe(upstream);
  response.on('close', () => upstream.destroy());
});
await new Promise(resolveReady => gate.listen(0, '127.0.0.1', resolveReady));
const gateOrigin = `http://127.0.0.1:${gate.address().port}`;
async function frontend(name, origin) {
  const server = await createServer({ root: resolve(repo, name), configFile: resolve(repo, name, 'vite.config.ts'),
    server: { host: '127.0.0.1', port: 0, strictPort: false, proxy: {
      '/api': { target: gateOrigin, changeOrigin: true, configure(proxy) {
        proxy.on('proxyReq', request => request.setHeader('Origin', origin));
      } }, '/health': { target: gateOrigin, changeOrigin: true }
    } } });
  servers.push(server);
  await server.listen();
  return `http://127.0.0.1:${server.httpServer.address().port}`;
}
let browser, qaPage;
const result = { scope: 'real_browser_readonly_ui_preflight', institution: refs.api,
  model_calls: 0, business_writes: 0, mocked_responses: false, checks: {}, errors: [] };
try {
  const admin = await frontend('frontend-admin', 'http://localhost:3000');
  const chat = await frontend('frontend-chat', 'http://localhost:3200');
  browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await context.newPage();
  qaPage = page;
  page.on('pageerror', error => result.errors.push({ class: error.name }));
  await page.goto(`${admin}/login?redirect=/admin/chat`, { waitUntil: 'domcontentloaded' });
  await page.locator('input[autocomplete="organization"]').fill(environment.SF_TENANT);
  await page.locator('input[autocomplete="username"]').fill(environment.SF_ADMIN_USER);
  await page.locator('input[autocomplete="current-password"]').fill(environment.SF_ADMIN_PASSWORD);
  const catalogReady = page.waitForResponse(response => new URL(response.url()).pathname === '/api/v1/chat/sessions'
    && response.request().method() === 'GET');
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await expect(page).toHaveURL(/\/admin\/chat$/);
  const catalogResponse = await catalogReady;
  assert.equal(catalogResponse.status(), 200);
  const envelope = await catalogResponse.json();
  assert.equal(envelope.code, 'OK');
  result.checks.admin_real_login = 'PASS';
  result.stage = 'current_admin_selectors';
  await expect(page.locator('.el-select').filter({ hasText: '历史会话' }).getByRole('combobox')).toBeVisible();
  await expect(page.getByPlaceholder('输入消息', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: '停止运行', exact: true })).toBeVisible();
  await expect(page.getByRole('tab', { name: '成果文件', exact: true })).toBeVisible();
  result.checks.current_admin_selectors = 'PASS';
  const selected = envelope.data?.find(row => row.session_id && row.message_count > 0);
  if (selected) {
    await page.locator('.el-select').filter({ hasText: '历史会话' }).getByRole('combobox').click();
    const historyReady = page.waitForResponse(response => new URL(response.url()).pathname === '/api/v1/chat/history'
      && new URL(response.url()).searchParams.get('session_id') === selected.session_id);
    await page.getByRole('option', { name: selected.summary || selected.session_id, exact: true }).click();
    const historyResponse = await historyReady;
    assert.equal(historyResponse.status(), 200);
    const history = await historyResponse.json();
    assert.equal(history.code, 'OK');
    assert.equal(history.data.session_id, selected.session_id);
    result.checks.admin_actual_history_identity = 'PASS';
    result.selected_session_id = selected.session_id;
    result.stage = 'admin_actual_artifact_tab';
    const artifacts = await readAdminArtifacts(page, selected.session_id);
    result.checks.admin_actual_artifact_tab = 'PASS';
    result.artifact_count = artifacts.length;
  } else result.checks.admin_actual_history_identity = 'NOT_EXERCISED_no_populated_session';
  // Verify current native bootstrap and deterministic refusal without a profile/model request.
  const native = await context.newPage();
  qaPage = native;
  result.stage = 'native_current_bootstrap';
  native.on('pageerror', error => result.errors.push({ class: error.name }));
  await native.goto(chat, { waitUntil: 'domcontentloaded' });
  await expect(native.getByRole('textbox', { name: '服务地址', exact: true })).toBeVisible();
  await native.getByRole('textbox', { name: '服务地址', exact: true }).fill(chat);
  await expect(native.getByRole('textbox', { name: '你的称呼', exact: true })).toBeVisible();
  await native.getByRole('textbox', { name: '你的称呼', exact: true }).fill('只读预检用户');
  result.stage = 'native_authentication_picker';
  await native.getByRole('combobox', { name: '认证方式', exact: true }).click();
  await native.getByRole('option', { name: '渠道令牌', exact: true }).click();
  result.stage = 'native_token_field';
  await native.getByLabel(/^渠道令牌/).fill('synthetic-invalid-preflight-token');
  const rejected = native.waitForResponse(response => new URL(response.url()).pathname === '/api/v1/chat/agents');
  result.stage = 'native_invalid_channel_token';
  await native.getByRole('button', { name: '进入会话', exact: true }).click();
  assert.equal((await rejected).status(), 401);
  await expect(native.getByRole('button', { name: '进入会话', exact: true })).toBeVisible();
  result.checks.native_current_bootstrap = 'PASS';
  result.checks.native_invalid_channel_token_401 = 'PASS';
  assert.equal(requests.filter(item => item.method === 'POST' && item.path === '/api/v1/chat').length, 0);
  assert.equal(blocked.length, 0);
  assert.equal(result.errors.length, 0);
  result.status = 'PASS';
} catch (error) {
  result.status = 'FAIL';
  result.failure_class = error?.name || 'Error';
  if (qaPage) {
    await qaPage.screenshot({ path: resolve(outputRoot, 'failure.png'), fullPage: true }).catch(() => {});
    result.controls = await qaPage.getByRole('combobox').evaluateAll(nodes => nodes.map(node => ({
      placeholder: node.getAttribute('placeholder'), aria_label: node.getAttribute('aria-label'),
      closest_select_text: node.closest('.el-select')?.textContent?.slice(0, 150)
    }))).catch(() => []);
  }
} finally {
  result.requests = requests;
  result.blocked_write_attempts = blocked;
  await writeFile(resolve(outputRoot, 'result.json'), JSON.stringify(result, null, 2));
  await browser?.close();
  await Promise.allSettled(servers.map(server => server.close()));
  await new Promise(resolveClosed => gate.close(resolveClosed));
}
process.stdout.write(JSON.stringify({ status: result.status, checks: result.checks,
  model_calls: 0, business_writes: 0, result: resolve(outputRoot, 'result.json') }) + '\n');
process.exitCode = result.status === 'PASS' ? 0 : 1;
