/* Current real-consumer selectors. All responses remain supplied by the service. */
import assert from 'node:assert/strict';

export function dataOf(response) {
  assert.equal(response?.code, 'OK', 'actual_service_envelope_required');
  return response.data;
}

export function nativeAgentLabel(agent, catalog) {
  return catalog.some(other => other.id !== agent.id && other.name === agent.name)
    ? `${agent.name}（${agent.id}）` : agent.name;
}

export async function connectNative(page, { origin, serviceBase = origin, channelId, clientRef, token, identityToken = '', nickname }) {
  assert.match(clientRef, /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i,
    'current_client_ref_uuid_contract_required');
  await page.addInitScript(({ key, clientRef, identityToken }) => {
    let existing;
    try { existing = JSON.parse(localStorage.getItem(key) || 'null'); } catch {}
    // 首次登记可有空凭证，已有引用仍由服务端要求原凭证；刷新不覆写新签发身份。
    if (existing?.client_ref !== clientRef || !existing?.identity_token)
      localStorage.setItem(key, JSON.stringify({ client_ref: clientRef, identity_token: identityToken }));
  }, { key: `skillforge.chat.profile:${serviceBase}:${channelId}`, clientRef, identityToken });
  await page.goto(origin, { waitUntil: 'domcontentloaded' });
  await page.getByRole('textbox', { name: '服务地址', exact: true }).fill(serviceBase);
  await page.getByRole('textbox', { name: '你的称呼', exact: true }).fill(nickname);
  const catalogReady = page.waitForResponse(response => response.request().method() === 'GET'
    && new URL(response.url()).pathname === '/api/v1/chat/agents');
  const profileReady = page.waitForResponse(response => response.request().method() === 'POST'
    && new URL(response.url()).pathname === '/api/v1/chat/client-profile');
  void catalogReady.catch(() => {});
  void profileReady.catch(() => {});
  await page.getByRole('combobox', { name: '认证方式', exact: true }).click();
  await page.getByRole('option', { name: '渠道令牌', exact: true }).click();
  // getByLabel exact includes the required marker in Playwright's label matching.
  await page.getByLabel(/^渠道令牌/).fill(token);
  await page.getByRole('button', { name: '进入会话', exact: true }).click();
  const catalogResponse = await catalogReady;
  const profileResponse = await profileReady;
  assert.equal(catalogResponse.status(), 200);
  assert.equal(profileResponse.status(), 200);
  const catalog = dataOf(await catalogResponse.json());
  const profile = dataOf(await profileResponse.json());
  assert.equal(String(catalog.channel_id), channelId, 'native_catalog_channel_identity_required');
  assert.equal(String(profile.channel_id), channelId, 'native_profile_channel_identity_required');
  assert.equal(typeof profile.end_user_id, 'string');
  assert.ok(profile.identity_token, 'server_issued_chat_user_credential_required');
  await page.getByRole('textbox', { name: '消息', exact: true }).waitFor({ timeout: 15_000 });
  return { catalog, profile };
}

export async function readNativeSession(page, wanted, catalog) {
  const agent = catalog.items.find(item => String(item.id) === wanted.agent_id);
  assert.ok(agent, 'native_authorized_agent_required');
  await page.getByRole('button', { name: '打开会话导航', exact: true }).click();
  await page.getByRole('combobox', { name: '对话对象', exact: true }).click();
  // The visible option also contains role_description; locate its exact name span.
  const label = nativeAgentLabel(agent, catalog.items);
  await page.getByRole('option').filter({ has: page.getByText(label, { exact: true }) }).click();
  await page.waitForFunction(target => {
    const value = sessionStorage.getItem('skillforge.chat.selection');
    return value && JSON.parse(value).target === target;
  }, `agent:${wanted.agent_id}`);
  const reloadStartedAt = Date.now();
  const sessionsReady = page.waitForResponse(response => response.request().method() === 'GET'
    && new URL(response.url()).pathname === '/api/v1/chat/sessions'
    && new URL(response.url()).searchParams.get('agentId') === wanted.agent_id
    && response.request().timing().startTime >= reloadStartedAt)
    .then(async response => ({ status: response.status(), sessions: dataOf(await response.json()) }));
  void sessionsReady.catch(() => {});
  await page.reload({ waitUntil: 'domcontentloaded' });
  const { status, sessions } = await sessionsReady;
  assert.equal(status, 200);
  const index = sessions.findIndex(item => item.session_id === wanted.session_id);
  assert.ok(index >= 0, 'native_authorized_session_required');
  await page.getByRole('button', { name: '打开会话导航', exact: true }).click();
  const historyReady = page.waitForResponse(response => response.request().method() === 'GET'
    && new URL(response.url()).pathname === '/api/v1/chat/history'
    && new URL(response.url()).searchParams.get('session_id') === wanted.session_id);
  void historyReady.catch(() => {});
  await page.getByRole('navigation', { name: '历史会话', exact: true }).getByRole('button').nth(index).click();
  const historyResponse = await historyReady;
  assert.equal(historyResponse.status(), 200);
  const history = dataOf(await historyResponse.json());
  assert.equal(history.session_id, wanted.session_id, 'native_actual_history_identity_required');
  return history;
}

export async function readAdminArtifacts(page, sessionId) {
  const actualReady = page.waitForResponse(response => response.request().method() === 'GET'
    && new URL(response.url()).pathname === '/api/v1/chat/sessions/deliverables'
    && new URL(response.url()).searchParams.get('sessionId') === sessionId);
  void actualReady.catch(() => {});
  await page.getByRole('tab', { name: '成果文件', exact: true }).click();
  const response = await actualReady;
  assert.equal(response.status(), 200);
  const value = dataOf(await response.json());
  assert.ok(Array.isArray(value), 'actual_artifact_collection_required');
  return value;
}
