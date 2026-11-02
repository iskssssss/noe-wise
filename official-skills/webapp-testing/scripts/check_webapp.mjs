/** 在无外部连接的独立浏览器上下文中检查明确挂载的 HTML；报告失败不等于执行故障。 */
import { readFile, readdir, realpath, stat, mkdir, mkdtemp, rm, writeFile } from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';

const INPUT_DATA = '/input/data';
const INPUT_ARTIFACTS = '/input/artifacts';
const OUTPUT = '/workspace/output';
const ENTRY_URL = 'https://skillforge-preview.invalid/index.html';
const MAX_HTML_BYTES = 10 * 1024 * 1024;
const MAX_LOGS = 100;
const CSP = "default-src 'none'; script-src 'unsafe-inline' 'unsafe-eval' data: blob:; style-src 'unsafe-inline'; img-src data: blob:; font-src data:; connect-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'; worker-src 'none'";

function object(value, label, keys) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error(`${label} 必须是对象`);
  const extra = Object.keys(value).filter(key => !keys.includes(key));
  if (extra.length) throw new Error(`${label} 不支持字段：${extra.join(', ')}`);
}

function text(value, label, max = 1000, empty = false) {
  if (typeof value !== 'string' || (!empty && !value.trim()) || value.length > max || value.includes('\0'))
    throw new Error(`${label} 必须是${empty ? '' : '非空'}字符串，长度不超过 ${max}`);
  return value;
}

function integer(value, label, min, max) {
  if (!Number.isInteger(value) || value < min || value > max) throw new Error(`${label} 必须是 ${min} 到 ${max} 的整数`);
  return value;
}

export function validatePlan(raw) {
  object(raw, 'webapp-check.json', ['source_path', 'viewports', 'steps', 'timeout_ms', 'ready_selector']);
  text(raw.source_path, 'source_path', 2000);
  const viewports = raw.viewports ?? [{ width: 1280, height: 900 }, { width: 375, height: 900 }];
  if (!Array.isArray(viewports) || viewports.length < 1 || viewports.length > 4) throw new Error('viewports 需要 1 到 4 项');
  for (const viewport of viewports) {
    object(viewport, 'viewport', ['width', 'height']);
    integer(viewport.width, 'viewport.width', 320, 1920);
    integer(viewport.height, 'viewport.height', 320, 1200);
  }
  const timeoutMs = integer(raw.timeout_ms ?? 60000, 'timeout_ms', 5000, 90000);
  const steps = raw.steps ?? [];
  if (!Array.isArray(steps) || steps.length > 40) throw new Error('steps 最多 40 项');
  const ops = {
    fill: ['value'], click: [], focus: [], press: ['key'], select: ['value'],
    check: [], uncheck: [], assert_text: ['expected'], assert_value: ['expected'],
    assert_attribute: ['attribute', 'expected'], assert_count: ['expected'],
    assert_visible_count: ['expected'], assert_visible: [], assert_hidden: [], assert_focused: []
  };
  for (const [index, step] of steps.entries()) {
    if (!step || typeof step !== 'object' || !Object.hasOwn(ops, step.op)) throw new Error(`steps[${index}].op 不支持`);
    object(step, `steps[${index}]`, ['op', 'selector', 'label', ...ops[step.op]]);
    text(step.selector, `steps[${index}].selector`, 500);
    if (step.label !== undefined) text(step.label, `steps[${index}].label`, 160);
    if (ops[step.op].includes('value')) text(step.value, 'step.value', 4000, true);
    if (step.op === 'press') {
      if (!['Enter', 'Space', 'Tab', 'Shift+Tab', 'Escape', 'ArrowDown', 'ArrowUp', 'ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(step.key))
        throw new Error('press.key 仅支持契约中的键盘按键');
    }
    if (['assert_count', 'assert_visible_count'].includes(step.op)) integer(step.expected, 'step.expected', 0, 10000);
    else if (ops[step.op].includes('expected')) {
      if (step.op !== 'assert_attribute' || step.expected !== null) text(step.expected, 'step.expected', 4000, true);
    }
    if (step.op === 'assert_attribute' && !/^[a-zA-Z][\w:-]{0,79}$/.test(step.attribute ?? '')) throw new Error('attribute 名称不合法');
  }
  if (raw.ready_selector !== undefined) text(raw.ready_selector, 'ready_selector', 500);
  return { source_path: raw.source_path, viewports, steps, timeout_ms: timeoutMs, ready_selector: raw.ready_selector };
}

export async function resolveInput(candidate, roots = [INPUT_DATA, INPUT_ARTIFACTS]) {
  text(candidate, '输入路径', 2000);
  if (!path.isAbsolute(candidate) || candidate.includes('\\') && process.platform !== 'win32' || candidate.split(/[\\/]/).includes('..'))
    throw new Error('输入必须是明确挂载的绝对路径，禁止 ..');
  const resolved = await realpath(candidate);
  const allowed = await Promise.all(roots.map(async root => { try { return await realpath(root); } catch (error) { if (error.code === 'ENOENT') return null; throw error; } }));
  if (!allowed.some(root => root && path.relative(root, resolved) !== '' && !path.relative(root, resolved).startsWith('..') && !path.isAbsolute(path.relative(root, resolved))))
    throw new Error('输入超出明确挂载的根目录');
  const info = await stat(resolved);
  if (!info.isFile() || info.size > MAX_HTML_BYTES) throw new Error('输入必须是小于 10 MiB 的普通文件');
  return resolved;
}

async function locatePlan() {
  const candidates = [];
  const consider = async candidate => { try { if ((await stat(candidate)).isFile()) candidates.push(candidate); } catch (error) { if (error.code !== 'ENOENT') throw error; } };
  await consider(path.join(INPUT_DATA, 'webapp-check.json'));
  try {
    for (const dir of await readdir(INPUT_ARTIFACTS, { withFileTypes: true }))
      if (dir.isDirectory()) await consider(path.join(INPUT_ARTIFACTS, dir.name, 'webapp-check.json'));
  } catch (error) { if (error.code !== 'ENOENT') throw error; }
  if (candidates.length !== 1) throw new Error('明确输入中需要且只能提供一个 webapp-check.json');
  return JSON.parse(await readFile(await resolveInput(candidates[0]), 'utf8'));
}

function pushLimited(list, value) {
  // 达到日志上限仍记录溢出事实，不能因截断而把错误漏判成通过。
  if (list.length < MAX_LOGS) list.push(value);
  else if (list.length === MAX_LOGS) list.push({ truncated: true });
}

async function retryAssertion(check, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  let last;
  do {
    try { await check(); return; } catch (error) { last = error; }
    if (Date.now() >= deadline) break;
    // 轮询真实 DOM 条件；时间间隔本身不是通过证据。
    await new Promise(resolve => setTimeout(resolve, Math.min(50, deadline - Date.now())));
  } while (Date.now() < deadline);
  throw last;
}

async function runStep(page, step, budget) {
  const locator = page.locator(`css=${step.selector}`);
  const assertEqual = (actual, expected) => { if (actual !== expected) throw new Error(`预期 ${JSON.stringify(expected)}，实际 ${JSON.stringify(actual)}`); };
  const assertOptions = { timeout: budget };
  switch (step.op) {
    case 'fill': await locator.fill(step.value, assertOptions); break;
    case 'click': await locator.click(assertOptions); break;
    case 'focus': await locator.focus(assertOptions); break;
    case 'press': await locator.press(step.key, assertOptions); break;
    case 'select': await locator.selectOption(step.value, assertOptions); break;
    case 'check': await locator.check(assertOptions); break;
    case 'uncheck': await locator.uncheck(assertOptions); break;
    case 'assert_visible': await locator.waitFor({ state: 'visible', timeout: budget }); break;
    case 'assert_hidden': await locator.waitFor({ state: 'hidden', timeout: budget }); break;
    case 'assert_count': await retryAssertion(async () => assertEqual(await locator.count(), step.expected), budget); break;
    case 'assert_visible_count': await retryAssertion(async () => assertEqual(await locator.filter({ visible: true }).count(), step.expected), budget); break;
    case 'assert_text': await retryAssertion(async () => assertEqual(await locator.innerText(assertOptions), step.expected), budget); break;
    case 'assert_value': await retryAssertion(async () => assertEqual(await locator.inputValue(assertOptions), step.expected), budget); break;
    case 'assert_attribute': await retryAssertion(async () => assertEqual(await locator.getAttribute(step.attribute, assertOptions), step.expected), budget); break;
    case 'assert_focused': await retryAssertion(async () => assertEqual(await locator.evaluate(element => element === document.activeElement, undefined, assertOptions), true), budget); break;
  }
}

export async function checkWebapp(plan, { chromium, html, outputDirectory, temporaryRoot = os.tmpdir(), launchOptions = {} }) {
  plan = validatePlan(plan);
  const start = Date.now();
  const deadline = start + plan.timeout_ms;
  const budget = (maximum = 3000) => {
    if (Date.now() >= deadline) throw new Error('整体检查超时');
    return Math.max(1, Math.min(maximum, deadline - Date.now()));
  };
  const report = { schema_version: 1, status: 'error', execution: 'completed', checks_passed: false,
    source_sha256: createHash('sha256').update(html).digest('hex'), source_bytes: Buffer.byteLength(html),
    offline: true, external_access_allowed: false, virtual_origin: ENTRY_URL,
    visual_review: 'not_performed', interaction: plan.steps.length ? 'requested' : 'not_requested',
    viewports: [], screenshots: [], duration_ms: 0 };
  let browser;
  let watchdog;
  let browserTemporary;
  await mkdir(outputDirectory, { recursive: true });
  try {
    await mkdir(temporaryRoot, { recursive: true });
    browserTemporary = await mkdtemp(path.join(temporaryRoot, 'skillforge-chromium-'));
    // Runtime 的 /tmp 是 64 MiB tmpfs；禁用 /dev/shm 后 Chromium 共享文件必须进入受管工作目录。
    // 仅传给当前 Chromium 子进程，不修改进程级环境，也不提高内存或 tmpfs 限额。
    browser = await chromium.launch({ headless: true, timeout: budget(15000),
      env: { ...process.env, TMPDIR: browserTemporary, TMP: browserTemporary, TEMP: browserTemporary },
      args: ['--no-sandbox', '--disable-dev-shm-usage', '--disable-background-networking', '--disable-features=MediaRouter'], ...launchOptions });
    // 关闭浏览器使所有等待退出；finally 再等待其真正关闭，防止遗留进程。
    watchdog = setTimeout(() => { void browser.close().catch(() => {}); }, budget(plan.timeout_ms));
    for (const [index, viewport] of plan.viewports.entries()) {
      budget();
      const entry = { viewport, status: 'failed', steps: [], console_errors: [], page_errors: [], blocked_requests: [],
        policy_violations: [], blocked_popups: [], blocked_websockets: [], console: [], screenshot: null };
      report.viewports.push(entry);
      const context = await browser.newContext({ viewport, offline: true, serviceWorkers: 'block', acceptDownloads: false });
      try {
        let initialDocument = true;
        await context.route('**/*', async route => {
          const request = route.request();
          if (initialDocument && request.url() === ENTRY_URL && request.isNavigationRequest() && request.method() === 'GET') {
            initialDocument = false;
            await route.fulfill({ status: 200, contentType: 'text/html; charset=utf-8', headers: { 'Content-Security-Policy': CSP }, body: html });
          } else {
            pushLimited(entry.blocked_requests, { url: request.url().slice(0, 1000), method: request.method(), type: request.resourceType() });
            await route.abort('blockedbyclient');
          }
        });
        await context.routeWebSocket('**', socket => {
          pushLimited(entry.blocked_websockets, socket.url().slice(0, 1000));
          socket.close({ code: 1008, reason: 'offline check' });
        });
        await context.exposeBinding('__skillforgePolicyViolation', (_source, violation) => pushLimited(entry.policy_violations, violation));
        await context.addInitScript(() => {
          document.addEventListener('securitypolicyviolation', event => {
            void window.__skillforgePolicyViolation({ directive: event.effectiveDirective, blocked_uri: event.blockedURI.slice(0, 1000) });
          });
          // 这些协议不经过 HTTP route；检查环境不允许建立外部连接。
          for (const name of ['RTCPeerConnection', 'webkitRTCPeerConnection', 'WebTransport']) {
            try { Object.defineProperty(window, name, { value: undefined, writable: false, configurable: false }); } catch {}
          }
        });
        const page = await context.newPage();
        context.on('page', popup => {
          if (popup !== page) { pushLimited(entry.blocked_popups, 'popup blocked'); void popup.close().catch(() => {}); }
        });
        page.on('console', message => {
          const item = { type: message.type(), text: message.text().slice(0, 1500) };
          pushLimited(entry.console, item);
          if (message.type() === 'error') pushLimited(entry.console_errors, item.text);
        });
        page.on('pageerror', error => pushLimited(entry.page_errors, error.message.slice(0, 1500)));
        page.on('dialog', dialog => { pushLimited(entry.blocked_popups, `${dialog.type()} dialog blocked`); void dialog.dismiss().catch(() => {}); });
        page.on('download', download => { pushLimited(entry.blocked_popups, 'download blocked'); void download.cancel().catch(() => {}); });
        try {
          page.setDefaultTimeout(budget());
          await page.goto(ENTRY_URL, { waitUntil: 'load', timeout: budget(10000) });
          if (plan.ready_selector) await page.locator(`css=${plan.ready_selector}`).filter({ visible: true }).first().waitFor({ state: 'visible', timeout: budget() });
          await page.evaluate(() => document.fonts.ready);
          for (const [stepIndex, step] of plan.steps.entries()) {
            try {
              await runStep(page, step, budget());
              entry.steps.push({ index: stepIndex, op: step.op, selector: step.selector, label: step.label, status: 'passed' });
            } catch (error) {
              entry.steps.push({ index: stepIndex, op: step.op, selector: step.selector, label: step.label, status: 'failed', error: error.message.slice(0, 1500) });
              break;
            }
          }
        } catch (error) { entry.error = error.message.slice(0, 1500); }
        // ready 或业务断言失败也留下当前页面的真实证据，保证已登记视口截图与报告可采集。
        // 无法捕获像素属于执行故障，不能用占位图或 exit 0 掩盖文件缺失。
        try {
          entry.document = await page.evaluate(() => ({ title: document.title, language: document.documentElement.lang,
            viewport_meta: Boolean(document.querySelector('meta[name="viewport"]')), width: innerWidth,
            document_width: document.documentElement.scrollWidth, horizontal_overflow: document.documentElement.scrollWidth > innerWidth + 1,
            body_text: (document.body?.innerText ?? '').slice(0, 2000),
            controls: [...document.querySelectorAll('button,input,select,textarea,a')].slice(0, 40).map(element => ({
              tag: element.tagName.toLowerCase(), id: element.id, type: element.getAttribute('type'),
              label: (element.getAttribute('aria-label') ?? element.innerText ?? '').slice(0, 120) })) }));
          const screenshot = `viewport-${index}.png`;
          await page.screenshot({ path: path.join(outputDirectory, screenshot), fullPage: false, timeout: budget(5000) });
          entry.screenshot = screenshot;
          report.screenshots.push({ path: screenshot, viewport, generated: true, visual_review: 'not_performed', full_page: false });
        } catch (error) { entry.capture_error = error.message.slice(0, 1500); }
      } finally { await context.close(); }
      // 浏览器上下文关闭后才汇总，避免关闭前已触发的错误回调晚于通过判定。
      entry.status = !entry.error && !entry.capture_error && entry.document && entry.screenshot &&
        entry.steps.every(step => step.status === 'passed') && entry.steps.length === plan.steps.length &&
        !entry.console_errors.length && !entry.page_errors.length && !entry.blocked_requests.length && !entry.policy_violations.length &&
        !entry.blocked_popups.length && !entry.blocked_websockets.length && !entry.document.horizontal_overflow &&
        Boolean(entry.document.title.trim()) && Boolean(entry.document.language.trim()) && entry.document.viewport_meta ? 'passed' : 'failed';
    }
    report.checks_passed = report.viewports.length === plan.viewports.length && report.viewports.every(entry => entry.status === 'passed');
    report.status = report.viewports.some(entry => entry.capture_error) ? 'error' : report.checks_passed ? 'passed' : 'failed';
    if (report.status === 'error') report.execution = 'error';
  } catch (error) {
    report.status = 'error';
    report.execution = 'error';
    report.error = error.message.slice(0, 1500);
  } finally {
    clearTimeout(watchdog);
    if (browser) await browser.close();
    if (browserTemporary) {
      // 删除前复核真实路径仍是本次根目录下原子分配的专用目录，浏览器已停止使用文件。
      const resolvedRoot = await realpath(temporaryRoot);
      const resolvedTemporary = await realpath(browserTemporary);
      const relative = path.relative(resolvedRoot, resolvedTemporary);
      if (!relative || relative.startsWith('..') || path.isAbsolute(relative) || !path.basename(resolvedTemporary).startsWith('skillforge-chromium-'))
        throw new Error('浏览器临时目录清理边界不合法');
      await rm(resolvedTemporary, { recursive: true, force: true });
    }
    report.duration_ms = Date.now() - start;
  }
  await writeFile(path.join(outputDirectory, 'check-report.json'), JSON.stringify(report, null, 2) + '\n');
  const publicText = report.status === 'passed'
    ? `网页自动检查通过：${report.viewports.length} 个视口，${plan.steps.length} 项操作或断言在每个视口执行。截图已生成，尚未完成模型视觉审阅。`
    : `网页自动检查${report.status === 'failed' ? '未通过' : '执行失败'}。请读取 check-report.json 中的错误与逐项结果；不得宣称网页已通过验收。截图是否生成以报告为准，模型视觉审阅尚未完成。`;
  await writeFile(path.join(outputDirectory, 'result.json'), JSON.stringify({ results: [{ kind: 'text', format: 'plain', text: publicText }] }, null, 2) + '\n');
  return report;
}

async function main() {
  const plan = validatePlan(await locatePlan());
  const source = await resolveInput(plan.source_path);
  if (path.extname(source).toLowerCase() !== '.html') throw new Error('source_path 仅支持明确挂载的单文件 .html');
  const bytes = await readFile(source);
  const html = new TextDecoder('utf-8', { fatal: true }).decode(bytes);
  const { chromium } = await import('playwright-core');
  const report = await checkWebapp(plan, { chromium, html, outputDirectory: OUTPUT, temporaryRoot: '/workspace/work' });
  console.log(JSON.stringify({ status: report.status, checks_passed: report.checks_passed, viewports: report.viewports.length }));
  // failed 是真实完成的检查业务结果，保留报告与截图供受管读取；依赖和浏览器故障属于执行失败。
  if (report.status === 'error') {
    console.error(report.error ?? report.viewports.find(entry => entry.capture_error)?.capture_error ?? '浏览器检查或截图捕获失败');
    process.exitCode = 1;
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch(error => { console.error(error.message); process.exitCode = 1; });
}
