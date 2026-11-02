// 自有72行页面的真实Browser service操作与独立文件/服务器oracle，无模型调用。
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import http from 'node:http';
import path from 'node:path';

const args = Object.fromEntries(process.argv.slice(2).reduce((all, arg, index, source) =>
  index % 2 ? all : [...all, [arg.replace(/^--/, ''), source[index + 1]]], []));
for (const key of ['service-config', 'extension-receipt', 'fixtures', 'evidence']) assert.ok(args[key], `EXPLICIT_${key}_REQUIRED`);
const configuration = JSON.parse(fs.readFileSync(args['service-config'], 'utf8'));
const screenshotMode = args['screenshot-mode'] ?? 'full-page';
assert.ok(['full-page', 'viewport'].includes(screenshotMode));
const extension = JSON.parse(fs.readFileSync(args['extension-receipt'], 'utf8'));
const records = JSON.parse(fs.readFileSync(path.join(args.fixtures, 'data.json'), 'utf8')).records;
const oracle = JSON.parse(fs.readFileSync(path.join(args.fixtures, 'oracle.json'), 'utf8'));
assert.equal(records.length, 72); assert.equal(oracle.total_cents, 903600);
const evidence = path.resolve(args.evidence); fs.mkdirSync(evidence, {recursive: false});
const events = [];
const dataset = JSON.stringify(records).replaceAll('<', '\\u003c');
const html = `<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>72行复杂销售验收</title>
<style>body{font:16px sans-serif;margin:28px;background:#eef5fa;color:#123}h1{color:#065}table{border-collapse:collapse;width:100%}th,td{border:1px solid #abc;padding:10px;text-align:left}tr:nth-child(even){background:#def}th{background:#164;color:white}button,input{font:inherit;margin:8px;padding:8px}#stats{display:block;padding:12px;background:#fff}canvas{border:1px solid #789}</style>
<h1>72行复杂销售验收</h1><p>六部门十二个月，重复金额保留，中文引号与换行保留，空负责人保留。</p>
<label for="query">筛选部门、月份、状态或备注</label><input id="query" aria-describedby="tip"><span id="tip">筛选后检查行数与金额，清空恢复全部72条。</span>
<button id="filter">筛选</button><button id="clear">清空</button><button id="submit">提交本批合成记录</button><output id="stats"></output><output id="submitted"></output>
<canvas id="chart" width="1000" height="180"></canvas><table><thead><tr><th>记录</th><th>部门</th><th>月份</th><th>金额分</th><th>状态</th><th>负责人</th><th>备注</th></tr></thead><tbody id="rows"></tbody></table>
<script>const records=${dataset};let shown=records;const q=document.querySelector('#query'),out=document.querySelector('#stats');
function draw(){const totals=shown.reduce((a,r)=>(a.total_cents+=r.amount_cents,a.null_owners+=r.owner===null?1:0,a),{rows:shown.length,total_cents:0,null_owners:0});out.textContent=JSON.stringify(totals);const tbody=document.querySelector('#rows');tbody.replaceChildren();for(const r of shown){const tr=document.createElement('tr');for(const field of ['record_id','department','month','amount_cents','status','owner','note']){const td=document.createElement('td');td.textContent=r[field]===null?'空负责人':String(r[field]);tr.append(td)}tbody.append(tr)}const c=document.querySelector('#chart').getContext('2d');c.clearRect(0,0,1000,180);c.font='16px sans-serif';for(let i=0;i<6;i++){const d='部门'+(i+1),t=shown.filter(r=>r.department===d).reduce((s,r)=>s+r.amount_cents,0);c.fillStyle=['#159','#075','#a43','#948','#b81','#458'][i];c.fillRect(i*160+10,170-t/1200,100,t/1200);c.fillText(d+' '+t,i*160+10,20)}}
document.querySelector('#filter').onclick=()=>{shown=records.filter(r=>[r.department,r.month,r.status,r.note,r.owner].some(v=>v!==null&&String(v).includes(q.value)));draw()};document.querySelector('#clear').onclick=()=>{q.value='';shown=records;draw()};
document.querySelector('#submit').onclick=async()=>{const response=await fetch('/submit?round='+new URLSearchParams(location.search).get('round'),{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({revision:1,records:shown})});document.querySelector('#submitted').textContent=JSON.stringify(await response.json())};draw();</script></html>`;
const server = http.createServer(async (request, response) => {
  const url = new URL(request.url, 'http://127.0.0.1');
  if (request.method === 'GET' && url.pathname === '/complex') {
    response.writeHead(200, {'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store'}); response.end(html); return;
  }
  if (request.method === 'POST' && url.pathname === '/submit') {
    let body = ''; for await (const chunk of request) {body += chunk; if (Buffer.byteLength(body) > 65536) {response.writeHead(413);response.end();return;}}
    const parsed = JSON.parse(body); assert.equal(parsed.revision, 1); assert.ok(Array.isArray(parsed.records));
    events.push({round: url.searchParams.get('round'), body: parsed, sha256: crypto.createHash('sha256').update(body).digest('hex')});
    fs.appendFileSync(path.join(evidence, 'server-events.jsonl'), JSON.stringify(events.at(-1)) + '\n');
    response.writeHead(200, {'Content-Type': 'application/json'});response.end(JSON.stringify({accepted_rows: parsed.records.length, revision: 1}));return;
  }
  response.writeHead(404); response.end();
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const origin = `http://127.0.0.1:${server.address().port}`;
const owner = extension.owner;
const headers = {'X-Browser-Service-Token': configuration.token, 'Content-Type': 'application/json'};
async function api(route, method = 'GET', body) {
  const response = await fetch(new URL(route, configuration.endpoint), {method, headers,
    body: body ? JSON.stringify(body) : undefined, redirect: 'error', signal: AbortSignal.timeout(95000)});
  assert.ok(response.ok, `SERVICE_HTTP_${response.status}`); return response.json();
}
const summary = {status: 'running', origin, rows: 72, independent_total_cents: oracle.total_cents,
  screenshotMode, serviceLayerVerified: true, runtimeModelToolVerified: false, humanInteractionVerified: false, paidModelCalls: 0, rounds: []};
fs.writeFileSync(path.join(evidence, 'scope.json'), JSON.stringify(summary, null, 2));
try {
  for (const round of [1, 2, 3]) {
    const directory = path.join(evidence, `round-${round}`);fs.mkdirSync(directory);
    const journal = {round, status: 'running', steps: []}; let task;
    const save = () => fs.writeFileSync(path.join(directory, 'execution.json'), JSON.stringify(journal, null, 2));
    async function call(operation, parameters = {}, expected = 'succeeded') {
      const invocation = `complex-page:${crypto.randomUUID()}`;
      const request = {tenant: owner.tenant, end_user: owner.endUser, session: owner.session, run: owner.run,
        invocation, task: operation === 'start' ? null : task, operation,
        parameters: {_operation: operation, ...parameters}, deadline_at: new Date(Date.now() + 90000).toISOString()};
      const step = {operation, invocation, status: 'unknown'};journal.steps.push(step);save();
      let receipt;
      try {receipt = await api('/internal/browser/execute', 'POST', request);}
      catch {receipt = (await api(`/internal/browser/tasks?${new URLSearchParams({tenant: owner.tenant, user: owner.endUser})}`)).find(item => item.invocation === invocation);}
      step.receipt = receipt;step.status = receipt?.status ?? 'unknown';save();
      assert.ok(receipt, 'UNKNOWN_NO_REPLAY'); if (operation === 'start' && receipt.task) task = receipt.task;
      assert.equal(receipt.status, expected, `${operation}_STATUS`);return {receipt, request};
    }
    const evaluate = async expression => (await call('evaluate', {expression, return_by_value: true, await_promise: true})).receipt.result.value;
    try {
      await call('start', {browser: owner.browser}, 'active');
      await call('navigate', {url: origin + '/complex?round=' + round});
      assert.deepEqual(await evaluate('JSON.parse(document.querySelector("#stats").textContent)'), {rows: 72, total_cents: 903600, null_owners: 8});
      for (const operation of ['snapshot', 'observe']) {
        const receipt = (await call(operation, {max_tokens: 6000})).receipt;
        assert.ok(JSON.stringify(receipt.result).includes('72行复杂销售验收'), `${operation}_REAL_PAGE_CONTENT`);
      }
      const filter = round === 1 ? '部门2' : round === 2 ? '2026-10' : '不存在的合成记录';
      await call('fill', {selector: '#query', value: filter});await call('click', {selector: '#filter'});
      const expected = records.filter(record => [record.department, record.month].includes(filter));
      assert.equal(await evaluate('document.querySelectorAll("#rows tr").length'), expected.length);
      if (round === 1) assert.equal(await evaluate('JSON.parse(document.querySelector("#stats").textContent).total_cents'), oracle.department_totals_cents['部门2']);
      await call('click', {selector: '#clear'});
      assert.equal(await evaluate('document.querySelectorAll("#rows tr").length'), 72);
      const clicked = await call('click', {selector: '#submit'});
      assert.deepEqual(await evaluate('JSON.parse(document.querySelector("#submitted").textContent)'), {accepted_rows: 72, revision: 1});
      // 只有已明确成功的同一invocation才做去重验收；不重放unknown。
      const replay = await api('/internal/browser/execute', 'POST', clicked.request);
      assert.deepEqual(replay, clicked.receipt);
      const actual = events.filter(event => event.round === String(round));assert.equal(actual.length, 1);
      assert.deepEqual(actual[0].body.records, records);
      const screenshot = (await call('screenshot', screenshotMode === 'full-page'
        ? {full_page: true, scope: 'current', timeout: '30s'} : {})).receipt;
      const response = await fetch(new URL(`/internal/browser/screenshot?${new URLSearchParams({tenant: owner.tenant,user: owner.endUser,invocation: screenshot.invocation})}`, configuration.endpoint), {headers,redirect: 'error',signal: AbortSignal.timeout(10000)});
      assert.ok(response.ok);const png = Buffer.from(await response.arrayBuffer());
      assert.deepEqual(png.subarray(0, 8), Buffer.from([137,80,78,71,13,10,26,10]));assert.ok(png.length > 8192);
      const dimensions = {width: png.readUInt32BE(16), height: png.readUInt32BE(20)};
      assert.ok(dimensions.width > 300 && dimensions.height >= (screenshotMode === 'full-page' ? 900 : 600));
      fs.writeFileSync(path.join(directory, 'page.png'), png);
      journal.image = {byteLength: png.length, sha256: crypto.createHash('sha256').update(png).digest('hex'), ...dimensions, modelVisualReview: false};
      await call('stop');journal.status = 'PASS';
    } catch (error) {journal.status = 'FAIL';journal.failure = {name: error.name, message: error.message};process.exitCode = 1;}
    finally {
      if (task && !journal.steps.some(step => step.operation === 'stop' && step.status === 'succeeded')) {
        try {journal.cleanup = (await call('stop')).receipt;} catch {journal.cleanup = {status: 'unknown'};}
      }
      save();summary.rounds.push({round,status:journal.status,steps:journal.steps.length,image:journal.image});
      console.log(JSON.stringify({round,status:journal.status,steps:journal.steps.length,image:journal.image}));
    }
    if (journal.status !== 'PASS') break;
  }
  summary.status = summary.rounds.length === 3 && summary.rounds.every(round => round.status === 'PASS') ? 'PASS' : 'FAIL';
} finally {
  await new Promise(resolve => {server.close(resolve);server.closeAllConnections();});
  fs.writeFileSync(path.join(evidence,'summary.json'), JSON.stringify(summary,null,2));
}
