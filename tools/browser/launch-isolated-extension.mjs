// 固定官方扩展的独立 Chromium profile 与真实 popup 配对；不注入 grant 或用户 profile。
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {createRequire} from 'node:module';
import {startFixture} from './local-network-fixture.mjs';

const args = Object.fromEntries(process.argv.slice(2).reduce((all, arg, index, source) =>
  index % 2 ? all : [...all, [arg.replace(/^--/, ''), source[index + 1]]], []));
for (const key of ['fixture', 'pair', 'extension', 'profile', 'evidence', 'playwright-module']) assert.ok(args[key], `EXPLICIT_${key}_REQUIRED`);
const evidence = path.resolve(args.evidence), profile = path.resolve(args.profile), extension = path.resolve(args.extension);
assert.ok(profile.includes('full-test-20261012') && evidence.includes('full-test-20261012'), 'OWNED_BATCH_PATH_REQUIRED');
fs.mkdirSync(evidence, {recursive: true});
const receiptPath = path.join(evidence, 'extension.json');
assert.ok(!fs.existsSync(receiptPath), 'EXISTING_EXTENSION_RECEIPT_NO_PAIR_REPLAY');
const receipt = {status: 'starting', profile, extension, humanInteractionVerified: false, paidModelCalls: 0, steps: []};
const save = () => fs.writeFileSync(receiptPath, JSON.stringify(receipt, null, 2));
save();
const fixture = JSON.parse(fs.readFileSync(args.fixture, 'utf8'));
const pair = JSON.parse(fs.readFileSync(args.pair, 'utf8'));
const require = createRequire(import.meta.url);
const {chromium} = require(path.resolve(args['playwright-module']));
const manifest = JSON.parse(fs.readFileSync(path.join(extension, 'manifest.json'), 'utf8'));
assert.equal(manifest.version, '0.3.2');
let context, network, popup;
try {
  context = await chromium.launchPersistentContext(profile, {channel: 'chromium', headless: true,
    locale: 'en-US', viewport: {width: 1280, height: 900},
    args: [`--disable-extensions-except=${extension}`, `--load-extension=${extension}`]});
  receipt.browserVersion = context.browser()?.version() ?? 'persistent-context';
  const worker = context.serviceWorkers()[0] ?? await context.waitForEvent('serviceworker', {timeout: 20000});
  const id = new URL(worker.url()).host;
  receipt.extensionId = id;
  popup = await context.newPage();
  await popup.goto(`chrome-extension://${id}/popup.html`);
  await popup.locator('details[data-slot="popup-connection-settings"] > summary').click();
  if (args['resume-receipt']) {
    const previous = JSON.parse(fs.readFileSync(args['resume-receipt'], 'utf8'));
    assert.equal(previous.profile, profile);
    assert.ok(previous.steps.some(step => step.name === 'popup-pair' && step.status === 'observed'));
    receipt.steps.push({name: 'resume-existing-grant', status: 'observed'}); save();
  } else {
    await popup.getByRole('button', {name: 'Remote', exact: true}).click();
    await popup.locator('#remote-pairing').fill(pair.pairing_url);
    receipt.steps.push({name: 'popup-pair', status: 'unknown'}); save();
    await popup.locator('details[data-slot="popup-connection-settings"] form button[type="submit"]').click();
    await popup.waitForFunction(() => document.querySelector('[data-slot="popup-current-connection"]')?.textContent?.includes('Remote'), null, {timeout: 20000});
    receipt.steps.at(-1).status = 'observed'; save();
  }
  const headers = {'Authorization': 'Bearer ' + fixture.institution_token, 'Content-Type': 'application/json'};
  const devicesUrl = new URL('/api/v1/chat/browser/devices', fixture.origins.institution);
  devicesUrl.searchParams.set('session_id', fixture.browser_session_id);
  let devices;
  const deadline = Date.now() + 45000;
  while (Date.now() < deadline) {
    try {
      const response = await fetch(devicesUrl, {headers, redirect: 'error', signal: AbortSignal.timeout(5000)});
      receipt.lastDeviceHttp = response.status;
      if (response.ok) {
        const envelope = await response.json();
        if (envelope.code === 'OK' && Array.isArray(envelope.data?.items)) devices = envelope.data;
      }
      if (devices?.items?.some(device => device.connected)) break;
    } catch { receipt.lastDeviceQuery = 'unavailable'; }
    await new Promise(resolve => setTimeout(resolve, 1000));
  }
  const connected = devices?.items?.filter(device => device.connected) ?? [];
  receipt.lastDevices = devices;
  assert.equal(connected.length, 1, 'ONE_CONNECTED_OWNED_DEVICE_REQUIRED');
  const device = connected[0];
  receipt.steps.push({name: 'product-device-select', status: 'unknown'}); save();
  const response = await fetch(new URL('/api/v1/chat/browser/select', fixture.origins.institution), {
    method: 'POST', headers, body: JSON.stringify({session_id: fixture.browser_session_id, device_id: device.device_id}),
    redirect: 'error', signal: AbortSignal.timeout(10000)});
  assert.ok(response.ok, `PRODUCT_SELECT_HTTP_${response.status}`);
  const selected = (await response.json()).data;
  receipt.steps.at(-1).status = 'observed';
  receipt.owner = {tenant: fixture.tenant_id, endUser: fixture.end_user_id, session: fixture.browser_session_id,
    browser: device.browser_id, device: device.device_id, run: 'isolated-service-acceptance'};
  receipt.productPairingVerified = true;
  receipt.selected = selected;
  network = await startFixture({directory: path.join(evidence, 'network-fixture'), ports: [0, 0]});
  receipt.network = network.manifest;
  await popup.screenshot({path: path.join(evidence, 'extension-paired.png')});
  receipt.status = 'ready'; save();
  console.log(JSON.stringify({status: 'ready', evidence: receiptPath, browser: device.browser_id,
    device: device.device_id, origins: network.manifest.origins, productPairingVerified: true}));
  while (!fs.existsSync(path.join(evidence, 'stop'))) await new Promise(resolve => setTimeout(resolve, 1000));
  receipt.status = 'stopped';
} catch (error) {
  receipt.status = 'failed'; receipt.failure = {name: error.name, phase: receipt.steps.at(-1)?.name ?? 'launch'};
  if (/^[A-Z0-9_]+$/.test(error.message)) receipt.failure.code = error.message;
  if (popup) {
    await popup.screenshot({path: path.join(evidence, 'extension-failure.png')}).catch(() => {});
    receipt.popupText = await popup.locator('body').innerText().catch(() => 'unavailable');
  }
  console.error(JSON.stringify({status: 'failed', name: error.name, phase: receipt.failure.phase}));
  process.exitCode = 1;
} finally {
  if (network) await network.close();
  if (context) await context.close();
  save();
}
