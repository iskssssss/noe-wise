import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { createRequire } from 'node:module';

// 复用机构端已安装的依赖；脚本不启动 Vite、不创建聊天或调用模型。
const require = createRequire(new URL('../../frontend-admin/package.json', import.meta.url));
const { chromium } = require('@playwright/test');

class VerificationError extends Error {
  constructor(code) { super(code); this.name = 'VerificationError'; this.code = code; }
}
const check = (condition, code) => { if (!condition) throw new VerificationError(code); };
const receipt = { status: 'failed', stage: 'configuration', browserClosed: false, previews: [], invalidTickets: [], blockedRequests: 0, previewNetwork: [] };
let directory;
let browser;
let context;
let page;
let failed = false;

try {
  check(process.argv.length === 2, 'STDIN_CONFIGURATION_REQUIRED');
  const chunks = [];
  let bytes = 0;
  for await (const chunk of process.stdin) {
    bytes += chunk.length;
    check(bytes <= 65_536, 'CONFIGURATION_TOO_LARGE');
    chunks.push(chunk);
  }
  const configuration = JSON.parse(Buffer.concat(chunks).toString('utf8'));
  check(typeof configuration.evidenceDirectory === 'string' && configuration.evidenceDirectory.length > 0,
    'EVIDENCE_DIRECTORY_REQUIRED');
  directory = path.resolve(configuration.evidenceDirectory);
  await mkdir(directory, { recursive: true });
  check(typeof configuration.expectedTitle === 'string' && configuration.expectedTitle.length > 0,
    'EXPECTED_TITLE_REQUIRED');
  receipt.stage = 'identity-validation';
  const identityFields = ['sessionId', 'artifactId', 'revisionId', 'fileIdSha256'];
  const identities = configuration.identities;
  const identityObject = identities !== null && typeof identities === 'object' && !Array.isArray(identities);
  // 安全诊断只报告固定字段名、类型、长度和校验事实，不输出未经验证的原始身份。
  receipt.identityValidation = identityFields.map(field => {
    const value = identityObject ? identities[field] : undefined;
    return { field, type: typeof value, length: typeof value === 'string' ? value.length : 0,
      allowedCharacters: typeof value === 'string' && /^[A-Za-z0-9:_-]+$/.test(value) };
  });
  check(identityObject && Object.keys(identities).length === identityFields.length
    && receipt.identityValidation.every(value => value.allowedCharacters)
    && /^[0-9a-f]{64}$/.test(identities.fileIdSha256), 'SYNTHETIC_IDENTITIES_REQUIRED');
  receipt.identities = configuration.identities;

  receipt.stage = 'origin-validation';
  const origin = value => {
    const url = new URL(value);
    check(url.protocol === 'http:' && ['localhost', '127.0.0.1'].includes(url.hostname)
      && url.port !== '' && !url.username && !url.password && !url.search && !url.hash
      && url.pathname === '/', 'LOOPBACK_ORIGIN_REQUIRED');
    return url.origin;
  };
  const hostOrigin = origin(configuration.hostOrigin);
  const previewUrl = (value, prefix) => {
    const url = new URL(value);
    origin(url.origin);
    check(url.hostname === '127.0.0.1' && !url.username && !url.password && !url.search && !url.hash
      && url.pathname.startsWith(prefix) && url.pathname.endsWith('/index.html'), 'PREVIEW_URL_INVALID');
    return url;
  };
  const candidate = previewUrl(configuration.candidateUrl, '/p/');
  const formal = previewUrl(configuration.formalUrl, '/d/');
  check(candidate.origin === formal.origin && candidate.origin !== hostOrigin, 'PREVIEW_ORIGIN_MISMATCH');
  check(new URL(hostOrigin).hostname !== candidate.hostname, 'PREVIEW_HOST_MUST_DIFFER');
  const allowedOrigins = new Set([hostOrigin, candidate.origin]);
  const hostUrl = `${hostOrigin}/__code-smoke-host`;
  const holder = `<!doctype html><html lang="zh"><meta charset="utf-8"><title>产物预览验收</title>
    <style>body{font:16px sans-serif;margin:24px;color:#243047}iframe{width:100%;height:480px;border:1px solid #ccd4df}h1{font-size:24px}</style>
    <h1>产物预览验收</h1><p id="preview-kind"></p><p id="host-sentinel">宿主内容保持不变</p>
    <iframe id="artifact-preview" title="网站预览" sandbox="allow-scripts" referrerpolicy="no-referrer"></iframe></html>`;

  receipt.stage = 'browser-launch';
  browser = await chromium.launch({ headless: true, timeout: 20_000, args: [
    '--disable-background-networking', '--disable-component-update', '--disable-default-apps',
    '--disable-sync', '--metrics-recording-only', '--no-first-run',
  ] });
  receipt.browserStarted = true;
  receipt.stage = 'context-setup';
  // Playwright 的 block 选项会向 opaque sandbox 注入 navigator.serviceWorker getter，产生测试自身的 SecurityError。
  // 预览仍由真实 sandbox/CSP 限制；另外观察并拒绝任何 service worker 创建。
  context = await browser.newContext({ viewport: { width: 1280, height: 800 }, reducedMotion: 'reduce' });
  receipt.serviceWorkersCreated = 0;
  context.on('serviceworker', () => { receipt.serviceWorkersCreated += 1; });
  let pageErrors = 0;
  // 宿主先真实导航取得 loopback 地址空间，随后只替换 DOM；预览响应全部来自真实服务。
  await context.route('**/*', async route => {
    let url;
    try { url = new URL(route.request().url()); }
    catch { receipt.blockedRequests += 1; await route.abort('blockedbyclient'); return; }
    if (url.protocol === 'data:' || url.protocol === 'blob:') { await route.continue(); return; }
    if (url.protocol !== 'http:' || !allowedOrigins.has(url.origin)) {
      receipt.blockedRequests += 1;
      await route.abort('blockedbyclient');
      return;
    }
    if (url.origin === hostOrigin && url.pathname === '/favicon.ico') { await route.fulfill({ status: 204 }); return; }
    await route.continue();
  });
  // WebSocket 不属于此次验收流量；即使产物新增连接，也不能落到外网。
  await context.routeWebSocket('**/*', socket => { receipt.blockedRequests += 1; socket.close(); });
  page = await context.newPage();
  page.setDefaultTimeout(10_000);
  page.setDefaultNavigationTimeout(15_000);
  page.on('pageerror', () => { pageErrors += 1; });
  const previewKind = request => request.url() === candidate.href ? 'candidate'
    : request.url() === formal.href ? 'formal' : null;
  page.on('request', request => {
    const kind = previewKind(request);
    if (kind && receipt.previewNetwork.length < 32) receipt.previewNetwork.push({ kind, event: 'request', resourceType: request.resourceType() });
  });
  page.on('requestfailed', request => {
    const kind = previewKind(request);
    if (kind && receipt.previewNetwork.length < 32) {
      const text = request.failure()?.errorText;
      receipt.previewNetwork.push({ kind, event: 'requestfailed',
        reason: typeof text === 'string' && /^net::ERR_[A-Z0-9_]+$/.test(text) ? text : 'REQUEST_FAILED' });
    }
  });
  page.on('response', response => {
    const kind = previewKind(response.request());
    if (kind && receipt.previewNetwork.length < 32) receipt.previewNetwork.push({ kind, event: 'response', status: response.status() });
  });

  for (const [kind, url, label] of [
    ['candidate', candidate, '候选网站'], ['formal', formal, '正式成果'],
  ]) {
    const facts = { kind };
    receipt.previews.push(facts);
    receipt.stage = `${kind}-host`;
    await page.goto(hostUrl, { waitUntil: 'domcontentloaded' });
    // route.fulfill 生成的主文档没有真实连接地址空间，会被 Chromium 的本地网络访问检查阻止加载预览。
    check(page.url() === hostUrl, 'HOST_NAVIGATION_ORIGIN_CHANGED');
    await page.setContent(holder, { waitUntil: 'domcontentloaded' });
    await page.locator('#preview-kind').evaluate((element, text) => { element.textContent = text; }, label);
    receipt.stage = `${kind}-response`;
    const responsePromise = page.waitForResponse(response => response.url() === url.href);
    await page.locator('#artifact-preview').evaluate((element, source) => { element.src = source; }, url.href);
    const response = await responsePromise;
    check(response.status() === 200, 'PREVIEW_HTTP_REJECTED');
    facts.httpStatus = response.status();
    receipt.stage = `${kind}-policy`;
    const headers = await response.allHeaders();
    const directives = new Map((headers['content-security-policy'] ?? '').split(';').map(value => {
      const [name, ...tokens] = value.trim().split(/\s+/); return [name, tokens];
    }));
    const scripts = directives.get('script-src') ?? [];
    check(scripts.some(value => /^'sha256-[A-Za-z0-9+/]+=*'$/.test(value)), 'SCRIPT_HASH_REQUIRED');
    check(!scripts.includes("'unsafe-inline'") && !scripts.includes("'unsafe-eval'"), 'UNSAFE_SCRIPT_POLICY');
    check(JSON.stringify(directives.get('sandbox')) === JSON.stringify(['allow-scripts']), 'SANDBOX_POLICY_INVALID');
    check(directives.get('frame-ancestors')?.includes(hostOrigin), 'HOST_FRAME_NOT_ALLOWED');
    check(directives.get('connect-src')?.includes("'none'"), 'NETWORK_POLICY_INVALID');
    check(headers['referrer-policy'] === 'no-referrer' && headers['x-content-type-options'] === 'nosniff'
      && headers['cache-control'] === 'no-store', 'PREVIEW_HEADERS_INVALID');
    facts.cspScriptHashVerified = true;
    facts.sandboxPolicyVerified = true;
    const requestHeaders = await response.request().allHeaders();
    check(!requestHeaders.authorization && !requestHeaders.cookie && !requestHeaders.referer, 'PREVIEW_CREDENTIALS_SENT');
    facts.previewHasNoCredentialsOrReferrer = true;
    check(await page.locator('#artifact-preview').getAttribute('sandbox') === 'allow-scripts', 'IFRAME_SANDBOX_INVALID');
    check(await page.locator('#artifact-preview').getAttribute('referrerpolicy') === 'no-referrer', 'IFRAME_REFERRER_INVALID');
    receipt.stage = `${kind}-render`;
    const frame = page.frameLocator('#artifact-preview');
    await frame.locator('h1').filter({ hasText: configuration.expectedTitle }).waitFor({ state: 'visible' });
    check(await frame.locator('h1').innerText() === configuration.expectedTitle, 'PREVIEW_TITLE_INVALID');
    check(await frame.locator('h1').evaluate(element => getComputedStyle(element).color) === 'rgb(18, 52, 86)', 'PREVIEW_CSS_INVALID');
    await frame.locator('#origin-result').filter({ hasText: 'null' }).waitFor();
    check(await frame.locator('#origin-result').innerText() === 'null', 'PREVIEW_ORIGIN_NOT_OPAQUE');
    check(await frame.locator('#counter').innerText() === '0', 'INITIAL_COUNTER_INVALID');
    receipt.stage = `${kind}-interaction`;
    await frame.getByRole('button', { name: '增加计数', exact: true }).click();
    await frame.locator('#counter').filter({ hasText: '1' }).waitFor();
    check(await frame.locator('#counter').innerText() === '1', 'SCRIPT_INTERACTION_FAILED');
    check(await frame.locator('#isolation-result').innerText() === 'SecurityError', 'PARENT_DOM_ACCESS_NOT_BLOCKED');
    check(await page.locator('#host-sentinel').innerText() === '宿主内容保持不变', 'HOST_DOM_CHANGED');
    facts.renderingVerified = true;
    facts.interactionVerified = true;
    facts.opaqueOriginVerified = true;
    facts.parentDomRejected = true;
    facts.hostDomUnchanged = true;
    receipt.stage = `${kind}-screenshot`;
    const screenshot = `${kind}-preview.png`;
    await page.screenshot({ path: path.join(directory, screenshot), fullPage: true });
    facts.screenshot = screenshot;
  }

  // APIRequestContext 不经过页面 route；这里仅使用已经校验的本次预览 origin，并禁止重定向。
  for (const [kind, prefix] of [['candidate', '/p/'], ['formal', '/d/']]) {
    receipt.stage = `${kind}-invalid-ticket`;
    const response = await context.request.get(`${candidate.origin}${prefix}invalid-ticket/index.html`, {
      maxRedirects: 0, timeout: 10_000,
    });
    const body = await response.text();
    check(response.status() === 404, 'INVALID_TICKET_NOT_REJECTED');
    check(!body.includes(configuration.expectedTitle) && !body.includes('id="increment"'), 'INVALID_TICKET_EXPOSED_ARTIFACT');
    receipt.invalidTickets.push({ kind, httpStatus: response.status(), artifactNotExposed: true });
    await response.dispose();
  }
  receipt.stage = 'network-completion';
  receipt.pageErrors = pageErrors;
  check(pageErrors === 0, 'UNEXPECTED_PAGE_ERROR');
  check(receipt.serviceWorkersCreated === 0 && context.serviceWorkers().length === 0, 'UNEXPECTED_SERVICE_WORKER');
  check(receipt.blockedRequests === 0, 'UNEXPECTED_NETWORK_REQUEST');
  receipt.status = 'passed';
  receipt.stage = 'verified';
} catch (error) {
  failed = true;
  receipt.status = 'failed';
  receipt.failureCode = error instanceof VerificationError ? error.code : 'BROWSER_RUNTIME_FAILURE';
  receipt.errorName = typeof error?.name === 'string' && /^[A-Za-z][A-Za-z0-9_]{0,63}$/.test(error.name)
    ? error.name : 'UnknownError';
  // 不输出 Playwright 异常消息、stack 或页面 URL：它们可能包含短期签名票据。
  console.error(receipt.failureCode);
  if (directory && page && !page.isClosed()) {
    try {
      await page.screenshot({ path: path.join(directory, 'failed-preview.png'), fullPage: true, timeout: 5_000 });
      receipt.failureScreenshot = 'failed-preview.png';
    } catch { receipt.failureScreenshotUnavailable = true; }
  }
} finally {
  try {
    if (context) await context.close();
    if (browser) {
      await browser.close();
      receipt.browserClosed = !browser.isConnected();
    }
  } catch {
    failed = true;
    receipt.status = 'failed';
    receipt.cleanupFailed = true;
    receipt.cleanupStage = 'browser-close';
    console.error('BROWSER_CLEANUP_FAILED');
  }
  if (directory) {
    if (receipt.status === 'passed' && receipt.browserClosed) receipt.stage = 'completed';
    try { await writeFile(path.join(directory, 'browser-receipt.json'), JSON.stringify(receipt, null, 2)); }
    catch { failed = true; console.error('BROWSER_RECEIPT_WRITE_FAILED'); }
  }
  if (failed) process.exitCode = 1;
}
