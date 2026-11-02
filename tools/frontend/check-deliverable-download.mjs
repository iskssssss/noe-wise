/*
 * Read-only live browser acceptance for published deliverable downloads.
 * DOWNLOAD_FIXTURE is process-only JSON: {origin, channelId, clientRef, token,
 * identityToken, session:{session_id,agent_id,end_user_id}, expected:[Deliverable]}.
 * For institution UI use origin + admin:{tenant,username,password} instead of
 * the channel credentials. expected must be a frozen accepted formal collection.
 * DOWNLOAD_OUTPUT is a fresh evidence directory. No model input is submitted,
 * no response is mocked, and credentials/ticket URLs are excluded from evidence.
 */
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { connectNative, readNativeSession, readAdminArtifacts, dataOf } from '../full_project_testing/live_product_controls.mjs';
import { readNativeDeliverables } from '../full_project_testing/live_product_continuation.mjs';
import { verifyProductDownload } from '../full_project_testing/live_product_download.mjs';

const require = createRequire(new URL('../../frontend-admin/package.json', import.meta.url));
const { chromium, expect } = require('@playwright/test');
const fixture = JSON.parse(process.env.DOWNLOAD_FIXTURE);
const output = resolve(process.env.DOWNLOAD_OUTPUT);
const admin = fixture.admin;
assert.ok(['localhost', '127.0.0.1'].includes(new URL(fixture.origin).hostname));
assert.ok((admin || fixture.identityToken) && fixture.expected.length > 0);
await mkdir(output, { recursive: false });
const evidence = { status: 'RUNNING', started_at: new Date().toISOString(),
  session_id: fixture.session.session_id, browser: 'Playwright Chromium headless',
  consumer: admin ? 'institution' : 'chat',
  checks: [], submitted_chat_inputs: 0, page_error_count: 0, network: [], download_events: [] };
let browser, context, page, stage = 'launch';
try {
  browser = await chromium.launch({ headless: true });
  evidence.browser_version = browser.version();
  context = await browser.newContext({ acceptDownloads: true, viewport: { width: 1440, height: 1000 } });
  context.on('page', opened => {
    opened.on('download', download => evidence.download_events.push({
      filename: download.suggestedFilename(), page: opened === page ? 'main' : 'popup' }));
  });
  context.on('response', response => {
    const url = new URL(response.url());
    if (url.pathname.includes('/deliverable')) evidence.network.push({
      method: response.request().method(), origin: url.origin,
      endpoint: url.pathname.endsWith('-ticket') ? url.pathname : 'deliverable-content',
      status: response.status(), content_type: response.headers()['content-type'],
      attachment: /attachment/i.test(response.headers()['content-disposition'] || '') });
  });
  context.on('requestfailed', request => {
    const url = new URL(request.url());
    evidence.network.push({ method: request.method(), origin: url.origin,
      event: 'requestfailed', error: request.failure()?.errorText });
  });
  page = await context.newPage();
  page.setDefaultTimeout(15_000);
  page.on('pageerror', () => { evidence.page_error_count++; });
  context.on('request', request => {
    if (request.method() === 'POST' && new URL(request.url()).pathname === '/api/v1/chat')
      evidence.submitted_chat_inputs++;
  });
  stage = 'connect';
  if (admin) {
    await page.goto(`${fixture.origin}/login`);
    await page.locator('input[autocomplete="organization"]').fill(admin.tenant);
    await page.locator('input[autocomplete="username"]').fill(admin.username);
    await page.locator('input[autocomplete="current-password"]').fill(admin.password);
    await Promise.all([page.waitForURL(url => url.pathname.startsWith('/admin/')),
      page.getByRole('button', { name: '登录', exact: true }).click()]);
    stage = 'open_existing_session';
    const sessionsReady = page.waitForResponse(response => response.request().method() === 'GET'
      && new URL(response.url()).pathname === '/api/v1/chat/sessions');
    void sessionsReady.catch(() => {});
    await page.goto(`${fixture.origin}/admin/chat`);
    const sessions = dataOf(await (await sessionsReady).json());
    const selected = sessions.find(item => item.session_id === fixture.session.session_id);
    assert.ok(selected);
    await page.locator('.el-select').filter({ hasText: '历史会话' }).getByRole('combobox').click();
    const historyReady = page.waitForResponse(response => response.request().method() === 'GET'
      && new URL(response.url()).pathname === '/api/v1/chat/history'
      && new URL(response.url()).searchParams.get('session_id') === fixture.session.session_id);
    void historyReady.catch(() => {});
    await page.getByRole('option', { name: selected.summary || selected.session_id, exact: true }).click();
    assert.equal(dataOf(await (await historyReady).json()).session_id, fixture.session.session_id);
    await expect(page.getByRole('button', { name: '新会话', exact: true })).toBeEnabled();
  } else {
    const connection = await connectNative(page, { ...fixture, nickname: '成果下载验收' });
    assert.equal(connection.profile.end_user_id, fixture.session.end_user_id);
    stage = 'open_existing_session';
    await readNativeSession(page, fixture.session, connection.catalog);
  }
  stage = 'read_published_revisions';
  const listed = admin ? await readAdminArtifacts(page, fixture.session.session_id)
    : await readNativeDeliverables(page, fixture.session);
  assert.equal(listed.length, fixture.expected.length);
  // The expected files come from the prior accepted formal collection, not downloads.
  for (const expected of [...fixture.expected].sort((a, b) => b.revision - a.revision)) {
    const actual = listed.find(item => item.id === expected.id);
    assert.ok(actual, 'original_revision_required');
    for (const key of ['deliverable_id', 'revision', 'current', 'session_id', 'status'])
      assert.deepEqual(actual[key], expected[key], `published_identity:${key}`);
    assert.deepEqual(actual.files, expected.files, 'original_file_metadata_required');
    stage = `select_revision_${actual.revision}`;
    if (!admin) {
      await page.getByRole('combobox', { name: '成果版本', exact: true }).click();
      await page.getByRole('option', {
        name: `${actual.name} · v${actual.revision}${actual.current ? '（当前）' : ''}`, exact: true
      }).click();
      await page.locator('.artifact-loading').waitFor({ state: 'hidden' });
      await expect(page.locator('.artifact-publication')).toHaveText('已发布 · 可下载');
      const files = page.locator('.artifact-file-section');
      if (await files.getAttribute('open') === null) await files.locator('summary').click();
    }
    for (const file of actual.files) {
      stage = `download_revision_${actual.revision}`;
      const row = admin ? page.getByRole('row').filter({ has: page.getByText(actual.name, { exact: true }) })
        .filter({ has: page.getByRole('cell', { name: String(actual.revision), exact: true }) })
        .locator('.toolbar').filter({ has: page.getByText(file.path, { exact: true }) })
        : page.locator('.artifact-files li').filter({
        has: page.getByText(file.download_name, { exact: true })
      });
      await row.waitFor({ state: 'visible' });
      assert.equal(await row.count(), 1);
      const verified = await verifyProductDownload({ page,
        click: () => row.getByRole('button', { name: '下载', exact: true }).click(),
        revisionId: actual.id, file, evidenceDir: resolve(output, 'downloads') });
      evidence.checks.push({ ...verified, revision: actual.revision, current: actual.current });
      assert.equal(verified.status, 'passed', verified.reason);
      // Historical versions deliberately carry -vN (DeliverableFilename.legacyVersion).
      const dot = file.download_name.lastIndexOf('.');
      const filename = actual.current ? file.download_name
        : `${file.download_name.slice(0, dot)}-v${actual.revision}${file.download_name.slice(dot)}`;
      assert.equal(verified.suggested_filename, filename, 'download_filename_contract');
      await page.screenshot({ path: resolve(output, `revision-${actual.revision}.png`), fullPage: true });
      await writeFile(resolve(output, `revision-${actual.revision}.txt`), await page.locator('body').innerText());
    }
  }
  assert.equal(evidence.submitted_chat_inputs, 0);
  assert.equal(evidence.page_error_count, 0);
  if (!admin) evidence.published_status_visible = true;
  evidence.status = 'PASS';
} catch (error) {
  evidence.status = 'FAILED';
  // Browser exceptions may contain credentials or signed URLs; retain only safe facts.
  evidence.failure = { stage, type: error.name };
  if (error.code === 'ERR_ASSERTION') evidence.failure.assertion = error.message.split('\n')[0];
  if (page && stage !== 'connect') {
    await page.screenshot({ path: resolve(output, 'failure.png'), fullPage: true }).catch(() => {});
    await writeFile(resolve(output, 'failure.txt'), await page.locator('body').innerText()).catch(() => {});
  }
  process.exitCode = 1;
} finally {
  if (context) await context.close();
  if (browser) await browser.close();
  evidence.browser_closed = !browser || !browser.isConnected();
  evidence.finished_at = new Date().toISOString();
  await writeFile(resolve(output, 'acceptance.json'), JSON.stringify(evidence, null, 2) + '\n');
  console.log(JSON.stringify(evidence));
}
