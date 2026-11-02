/** 真实 Chromium 正负向检查，直接运行受管 runner；不以 mock 替代页面行为。 */
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { mkdtemp, mkdir, readFile, writeFile, symlink, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { validatePlan, resolveInput, checkWebapp } from '../../official-skills/webapp-testing/scripts/check_webapp.mjs';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const require = createRequire(path.join(ROOT, 'frontend-admin/package.json'));
const { chromium } = require('@playwright/test');
const evidence = path.resolve(process.argv[2] ?? path.join(ROOT, '.agent-work-state/skill-strengthening/web-testing/host'));
const results = [];
await mkdir(evidence, { recursive: true });

const HTML = `<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><title>需求验收</title>
<meta name="viewport" content="width=device-width,initial-scale=1"><style>body{margin:16px;font-family:sans-serif}table{width:100%;max-width:800px}button,input{max-width:100%;box-sizing:border-box}</style></head>
<body><h1>需求清单</h1><label for="search">筛选需求</label><input id="search" type="search"><p id="count" aria-live="polite">2 项需求</p>
<table><tbody><tr><td>数据备份</td></tr><tr><td>账户管理</td></tr></tbody></table>
<button id="toggle" aria-pressed="false">切换状态</button><script>
document.querySelector('#search').addEventListener('input',event=>{let count=0;for(const row of document.querySelectorAll('tbody tr')){row.hidden=!row.innerText.includes(event.target.value);if(!row.hidden)count++}document.querySelector('#count').textContent=count+' 项需求'});
document.querySelector('#toggle').addEventListener('click',event=>event.target.setAttribute('aria-pressed',String(event.target.getAttribute('aria-pressed')!=='true')));
</script></body></html>`;
const example = JSON.parse(await readFile(path.join(ROOT, 'official-skills/webapp-testing/examples/webapp-check.json'), 'utf8'));

async function run(name, html, override = {}) {
  const directory = path.join(evidence, name);
  const report = await checkWebapp({ ...example, source_path: '/input/data/index.html', timeout_ms: 10000, ...override },
    { chromium, html, outputDirectory: directory });
  assert.equal(report.visual_review, 'not_performed');
  assert.equal(report.offline, true);
  assert.equal(report.external_access_allowed, false);
  const publicReport = JSON.parse(await readFile(path.join(directory, 'result.json'), 'utf8'));
  assert.equal(publicReport.results[0].kind, 'text');
  if (report.status !== 'passed') assert(publicReport.results[0].text.includes('不得宣称网页已通过验收'));
  results.push({ name, status: report.status, checks_passed: report.checks_passed, screenshots: report.screenshots.length,
    page_errors: report.viewports.reduce((count, viewport) => count + viewport.page_errors.length, 0),
    policy_violations: report.viewports.reduce((count, viewport) => count + viewport.policy_violations.length, 0), duration_ms: report.duration_ms });
  return report;
}

const healthy = await run('offline-interaction-keyboard', HTML);
assert.equal(healthy.status, 'passed');
assert.equal(healthy.checks_passed, true);
assert.equal(healthy.viewports.length, 2);
assert.equal(healthy.screenshots.length, 2);
for (const viewport of healthy.viewports) assert.equal(viewport.steps.length, example.steps.length);

const multipleReady = await run('ready-multiple-matches', HTML, { ready_selector: 'tbody tr' });
assert.equal(multipleReady.status, 'passed');
assert.equal(multipleReady.screenshots.length, 2);

const missingReady = await run('ready-failed-preserves-screenshots', HTML, { ready_selector: '#missing-ready', steps: [] });
assert.equal(missingReady.status, 'failed');
assert.equal(missingReady.screenshots.length, 2);
assert(missingReady.viewports.every(viewport => viewport.error && viewport.screenshot && viewport.document));

const wrongAssertion = await run('wrong-expected-count', HTML, { steps: [{ op: 'assert_visible_count', selector: 'tbody tr', expected: 3 }] });
assert.equal(wrongAssertion.status, 'failed');
assert.equal(wrongAssertion.viewports[0].steps[0].status, 'failed');

const runtimeError = await run('runtime-error', HTML.replace('</script>', "setTimeout(()=>{throw new Error('真实页面故障')},0);</script>"), { steps: [] });
assert.equal(runtimeError.status, 'failed');
assert(runtimeError.viewports.some(viewport => viewport.page_errors.some(error => error.includes('真实页面故障'))));

const external = await run('external-resource-blocked', HTML.replace('</body>', '<img src="https://example.invalid/forbidden.png"></body>'), { steps: [] });
assert.equal(external.status, 'failed');
assert(external.viewports.some(viewport => viewport.policy_violations.length || viewport.blocked_requests.length));

const outboundPost = await run('outbound-post-blocked', HTML.replace('</script>', "document.querySelector('#toggle').addEventListener('click',()=>fetch('https://example.invalid/write',{method:'POST',body:'must not leave'}).catch(()=>{}));</script>"),
  { steps: [{ op: 'click', selector: '#toggle' }] });
assert.equal(outboundPost.status, 'failed');
assert(outboundPost.viewports.some(viewport => viewport.policy_violations.length || viewport.blocked_requests.length));

const overflow = await run('narrow-overflow', HTML.replace('</style>', 'h1{width:1200px}</style>'), { steps: [] });
assert.equal(overflow.status, 'failed');
assert.equal(overflow.viewports[0].status, 'passed');
assert.equal(overflow.viewports[1].document.horizontal_overflow, true);

const noInteractions = await run('basic-only-report', HTML, { steps: [] });
assert.equal(noInteractions.status, 'passed');
assert.equal(noInteractions.interaction, 'not_requested');

const launchFailure = await checkWebapp({ ...example, source_path: '/input/data/index.html', steps: [] },
  { chromium, html: HTML, outputDirectory: path.join(evidence, 'missing-browser'),
    launchOptions: { executablePath: path.join(evidence, 'browser-does-not-exist') } });
assert.equal(launchFailure.status, 'error');
assert.equal(launchFailure.execution, 'error');
assert.equal(launchFailure.checks_passed, false);
assert.equal(launchFailure.screenshots.length, 0);
results.push({ name: 'missing-browser', status: launchFailure.status, checks_passed: false, screenshots: 0 });

assert.throws(() => validatePlan({ ...example, url: 'https://example.org' }), /不支持字段/);
assert.throws(() => validatePlan({ ...example, steps: [{ op: 'evaluate', selector: 'body', code: 'alert(1)' }] }), /不支持/);
assert.throws(() => validatePlan({ ...example, steps: [{ op: 'press', selector: 'body', key: 'Control+L' }] }), /仅支持/);
assert.throws(() => validatePlan({ ...example, viewports: [{ width: 100, height: 900 }] }), /整数/);

const temporary = await mkdtemp(path.join(os.tmpdir(), 'skillforge-web-check-'));
try {
  const input = path.join(temporary, 'input');
  await mkdir(input);
  const source = path.join(input, 'index.html');
  await writeFile(source, HTML);
  assert.equal(await resolveInput(source, [input]), await import('node:fs/promises').then(fs => fs.realpath(source)));
  const secret = path.join(temporary, 'outside.html');
  await writeFile(secret, 'outside');
  await assert.rejects(resolveInput(secret, [input]), /超出/);
  await assert.rejects(resolveInput(input + path.sep + '..' + path.sep + 'outside.html', [input]), /禁止/);
  try {
    const escaped = path.join(input, 'escape.html');
    await symlink(secret, escaped, 'file');
    await assert.rejects(resolveInput(escaped, [input]), /超出/);
    results.push({ name: 'symlink-escape', status: 'rejected' });
  } catch (error) {
    if (error.code !== 'EPERM') throw error;
    results.push({ name: 'symlink-escape', status: 'not_run', reason: 'Windows does not grant symlink permission' });
  }
} finally { await rm(temporary, { recursive: true, force: true }); }

await writeFile(path.join(evidence, 'summary.json'), JSON.stringify({ browser: 'Chromium', playwright: require('@playwright/test/package.json').version,
  results, visual_review: 'not_performed' }, null, 2) + '\n');
console.log(JSON.stringify({ result: 'passed', evidence, checks: results.length, results }));
