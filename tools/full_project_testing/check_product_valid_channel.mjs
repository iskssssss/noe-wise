/* Exercise the actual Product bridge with a valid profile and an empty real Session.
 * The transport gate denies every paid/business action except profile + one owned empty fixture Session.
 * It never alters service response payloads or credentials. */
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { createServer as createHttpServer, request as upstreamRequest } from 'node:http';
import { dirname, resolve, sep } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { spawn } from 'node:child_process';
import { randomUUID } from 'node:crypto';

const repo = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const logsRoot = resolve(repo, '.runtime-logs/full-project-test-20261012');
const fixtureRoot = process.env.PRODUCT_VALID_PREFLIGHT_FIXTURE
  ? resolve(process.env.PRODUCT_VALID_PREFLIGHT_FIXTURE) : resolve(logsRoot, 'product-valid-channel-preflight');
assert.ok(fixtureRoot.startsWith(logsRoot + sep), 'preflight_fixture_must_be_within_owned_evidence_root');
const diagnostic = process.argv.find(value => /^--diagnostic=[1-9][0-9]*$/.test(value));
const root = diagnostic ? resolve(fixtureRoot, 'diagnostic-' + diagnostic.split('=')[1]) : fixtureRoot;
if (diagnostic) await mkdir(root, { recursive: false });
const privateFixture = JSON.parse(await readFile(resolve(fixtureRoot, 'fixture-private.json'), 'utf8'));
const runnerSession = process.env.PRODUCT_VALID_PREFLIGHT_USE_RUNNER_SESSION === '1'
  ? JSON.parse(await readFile(resolve(fixtureRoot, 'target-session-receipt.json'), 'utf8')) : undefined;
if (runnerSession) {
  assert.equal(runnerSession.status, 'CONFIRMED');
  assert.equal(runnerSession.case_id, 'D09');
  assert.equal(runnerSession.channel_id, privateFixture.channel_id);
  assert.equal(runnerSession.session.agent_id, privateFixture.agent_id);
  assert.equal(runnerSession.root_input_count, 0);
  assert.equal(runnerSession.source, 'behavior_product_runner.execute_product_case_before_execute_browser_case');
}
const reuse = process.argv.find(value => /^--reuse-known=[1-9][0-9]*$/.test(value));
let known;
if (reuse) {
  assert.ok(diagnostic, 'known_fixture_reuse_requires_distinct_diagnostic');
  const original = resolve(fixtureRoot, 'diagnostic-' + reuse.split('=')[1]);
  const prior = JSON.parse(await readFile(resolve(original, 'result.json'), 'utf8'));
  assert.equal(prior.checks.empty_real_session_fixture, 'PASS', 'only_confirmed_fixture_creation_can_be_reused');
  known = { profile: JSON.parse(await readFile(resolve(original, 'actual-profile-private.json'), 'utf8')).profiles[0],
            wanted: JSON.parse(await readFile(resolve(original, 'actual-sessions.json'), 'utf8')).sessions[0] };
  privateFixture.client_ref = JSON.parse(await readFile(resolve(original, 'client-ref-fixture.json'), 'utf8')).client_ref;
  assert.equal(known.profile.end_user_id, known.wanted.end_user_id);
  assert.equal(known.profile.channel_id, privateFixture.channel_id);
  assert.equal(known.wanted.agent_id, privateFixture.agent_id);
  await writeFile(resolve(root, 'known-fixture-reuse.json'), JSON.stringify({ original, session_id: known.wanted.session_id,
    original_session_creation_confirmed: true, unknown_request_replay: false, new_session_created: false }), { flag: 'wx' });
}
if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(privateFixture.client_ref)) {
  assert.ok(diagnostic, 'invalid_original_fixture_requires_explicit_diagnostic');
  privateFixture.client_ref = randomUUID();
  await writeFile(resolve(root, 'client-ref-fixture.json'), JSON.stringify({ client_ref: privateFixture.client_ref,
    reason: 'formal_uuid_client_profile_contract', original_invalid_profile_response_preserved: true }), { flag: 'wx' });
}
const target = new URL(privateFixture.api);
assert.equal(target.hostname, '127.0.0.1');
assert.equal(target.protocol, 'http:');
const require = createRequire(resolve(repo, 'frontend-admin/package.json'));
const { createServer } = await import(pathToFileURL(require.resolve('vite')).href);
const requests = [], blocked = [], servers = [];
const allowedWrites = new Set(['/api/v1/auth/login', '/api/v1/auth/refresh', '/api/v1/chat/client-profile']);
const gate = createHttpServer((request, response) => {
  const path = new URL(request.url, 'http://127.0.0.1').pathname;
  if (!['GET', 'HEAD', 'OPTIONS'].includes(request.method) && !allowedWrites.has(path)) {
    blocked.push({ method: request.method, path });
    response.writeHead(503, { 'Content-Type': 'application/json' });
    response.end('{"code":"NONPAID_PREFLIGHT_REFUSAL","data":null}');
    return;
  }
  const upstream = upstreamRequest({ hostname: target.hostname, port: target.port, path: request.url,
    method: request.method, headers: { ...request.headers, host: target.host } }, actual => {
    requests.push({ method: request.method, path, status: actual.statusCode });
    response.writeHead(actual.statusCode, actual.headers); actual.pipe(response);
  });
  upstream.on('error', () => { response.writeHead(502); response.end(); });
  request.pipe(upstream); response.on('close', () => upstream.destroy());
});
await new Promise(resolveReady => gate.listen(0, '127.0.0.1', resolveReady));
async function frontend(name, origin) {
  const server = await createServer({ root: resolve(repo, name), configFile: resolve(repo, name, 'vite.config.ts'),
    server: { host: '127.0.0.1', port: 0, strictPort: false, proxy: { '/api': {
      target: `http://127.0.0.1:${gate.address().port}`, changeOrigin: true,
      configure(proxy) { proxy.on('proxyReq', request => request.setHeader('Origin', origin)); }
    } } } });
  servers.push(server); await server.listen(); return `http://127.0.0.1:${server.httpServer.address().port}`;
}
let childCount = 0;
async function invoke(environment) {
  const ordinal = ++childCount;
  await new Promise((resolveDone, reject) => {
    const child = spawn(process.execPath, [resolve(repo, 'tools/frontend/check-product-sessions.mjs')], {
      env: environment, stdio: ['ignore', 'ignore', 'pipe'], windowsHide: true
    });
    let errors = '';
    child.stderr.on('data', chunk => { if (errors.length < 65536) errors += chunk.toString('utf8').slice(0, 65536 - errors.length); });
    const timer = setTimeout(() => { child.kill(); reject(new Error('product_preflight_child_timeout')); }, 120_000);
    child.on('error', () => { clearTimeout(timer); reject(new Error('product_preflight_child_launch_failed')); });
    child.on('exit', async code => {
      clearTimeout(timer);
      await writeFile(resolve(root, `private-bridge-${ordinal}-stderr.log`), errors, { flag: 'wx' });
      code === 0 ? resolveDone() : reject(new Error('product_preflight_child_failed'));
    });
  });
}
const result = { scope: 'real_product_bridge_valid_channel_empty_session', model_calls: 0,
                 paid_actor_used: false, service_responses_mocked: false, checks: {}, status: 'RUNNING' };
try {
  const admin = await frontend('frontend-admin', 'http://localhost:3000');
  const chat = await frontend('frontend-chat', 'http://localhost:3200');
  const environment = { ...process.env, PRODUCT_PROCESS_ADMIN_ORIGIN: admin, PRODUCT_PROCESS_CHAT_ORIGIN: chat,
    PRODUCT_PROCESS_ADMIN_TENANT: privateFixture.admin_tenant, PRODUCT_PROCESS_ADMIN_USERNAME: privateFixture.admin_username,
    PRODUCT_PROCESS_ADMIN_PASSWORD: privateFixture.admin_password, PRODUCT_PROCESS_CHANNEL_ID: privateFixture.channel_id,
    PRODUCT_PROCESS_CHANNEL_TOKEN: privateFixture.channel_token, PRODUCT_PROCESS_CLIENT_REF: privateFixture.client_ref,
    PRODUCT_PROCESS_AGENT_ID: privateFixture.agent_id, PRODUCT_PROCESS_RUN_TIMEOUT_MS: '15000',
    PRODUCT_PROCESS_NATIVE_ARTIFACT_PREFLIGHT: '1' };
  const profileFile = resolve(root, 'actual-profile-private.json');
  result.stage = 'real_profile_registration';
  if (!known) await invoke({ ...environment, PRODUCT_PROCESS_PREPARE_PROFILE: '1', PRODUCT_PROCESS_OUTPUT: profileFile });
  const profile = known?.profile || JSON.parse(await readFile(profileFile, 'utf8')).profiles[0];
  assert.ok(profile.identity_token, 'profile_credential_required_no_ref_only_restore');
  environment.PRODUCT_PROCESS_IDENTITY_TOKEN = profile.identity_token;
  assert.equal(profile.channel_id, privateFixture.channel_id);
  assert.equal(profile.agent_id, privateFixture.agent_id);
  result.checks.real_valid_profile = 'PASS';
  let wanted = known?.wanted || runnerSession?.session;
  if (wanted) assert.equal(profile.end_user_id, wanted.end_user_id, 'actual_profile_matches_actual_runner_target');
  if (!wanted) {
    await writeFile(resolve(root, 'session-fixture-intent.json'), JSON.stringify({ channel_id: profile.channel_id,
      end_user_id: profile.end_user_id, agent_id: profile.agent_id, model_dispatch: false }), { flag: 'wx' });
    result.stage = 'owned_empty_session_fixture';
    const response = await fetch(privateFixture.api + '/chat/sessions', { method: 'POST', headers: {
      'Content-Type': 'application/json', 'X-Channel-Token': privateFixture.channel_token,
      'X-Chat-User-Token': profile.identity_token
    }, body: JSON.stringify({ agent_id: profile.agent_id, end_user_id: profile.end_user_id }) });
    assert.equal(response.status, 200);
    const envelope = await response.json(); assert.equal(envelope.code, 'OK');
    wanted = { session_id: envelope.data.session_id, agent_id: profile.agent_id, end_user_id: profile.end_user_id };
  }
  const sessionsFile = resolve(root, 'actual-sessions.json');
  await writeFile(sessionsFile, JSON.stringify({ sessions: [wanted] }), { flag: 'wx' });
  result.checks.empty_real_session_fixture = 'PASS';
  if (runnerSession) result.checks.actual_product_runner_allocated_session_readback = 'PASS';
  result.stage = 'actual_two_client_readback';
  const bundleFile = resolve(root, 'actual-two-client-readback.json');
  await invoke({ ...environment, PRODUCT_PROCESS_PREPARE_PROFILE: '0', PRODUCT_PROCESS_READ_ONLY: '1',
                 PRODUCT_PROCESS_SESSIONS: sessionsFile, PRODUCT_PROCESS_OUTPUT: bundleFile });
  const bundle = JSON.parse(await readFile(bundleFile, 'utf8')).bundles[0];
  assert.equal(bundle.session.session_id, wanted.session_id);
  assert.equal(bundle.browser.institution.session_id, wanted.session_id);
  assert.equal(bundle.browser.chat.session_id, wanted.session_id);
  assert.equal(bundle.artifacts.history.institution.session_id, wanted.session_id);
  assert.equal(bundle.artifacts.history.chat.session_id, wanted.session_id);
  assert.equal(bundle.browser.institution.operations.history_read.status, 'passed');
  assert.equal(bundle.browser.chat.operations.history_read.status, 'passed');
  assert.equal(bundle.browser.chat.operations.native_artifact_controls.status, 'passed');
  assert.equal(blocked.length, 0, 'preflight_must_never_attempt_a_paid_or_business_action');
  assert.equal(requests.some(row => row.path === '/api/v1/chat'), false);
  result.checks.actual_admin_and_native_history_identity = 'PASS';
  result.checks.actual_admin_artifact_endpoint = requests.some(row => row.path === '/api/v1/chat/sessions/deliverables' && row.status === 200) ? 'PASS' : 'MISSING';
  result.checks.actual_native_artifact_open_refresh_endpoint = 'PASS';
  result.status = 'PASS';
} catch (error) {
  result.status = 'FAILED'; result.failure_class = error?.name || 'Error';
  result.failure_code = ['product_preflight_child_timeout', 'product_preflight_child_launch_failed', 'product_preflight_child_failed'].includes(error?.message)
    ? error.message : 'actual_consumer_assertion_failed';
  process.exitCode = 1;
} finally {
  result.requests = requests; result.blocked = blocked;
  await writeFile(resolve(root, 'result.json'), JSON.stringify(result, null, 2), { flag: 'wx' });
  await Promise.allSettled(servers.map(server => server.close()));
  await new Promise(resolveDone => gate.close(resolveDone));
}
process.stdout.write(JSON.stringify({ status: result.status, stage: result.stage, checks: result.checks, model_calls: 0 }) + '\n');
