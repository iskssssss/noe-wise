import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {fileURLToPath} from 'node:url';

// 只在 loopback 提供自有测试数据；两个端口构成不同 origin。
export async function startFixture({directory, ports = [18581, 18582]}) {
  if (ports.length !== 2 || (ports[0] !== 0 && ports[0] === ports[1]) || ports.some(p => !Number.isInteger(p) || (p !== 0 && p < 1024) || p > 65535)) throw new Error('TWO_DISTINCT_PORTS_REQUIRED');
  fs.mkdirSync(directory, {recursive: true});
  const events = [];
  const run = crypto.randomUUID();
  const servers = ports.map((port, index) => http.createServer(async (req, res) => {
    const url = new URL(req.url, `http://127.0.0.1:${port}`);
    const send = (status, value) => {res.writeHead(status, {'Content-Type': 'application/json', 'Cache-Control': 'no-store'}); res.end(JSON.stringify(value));};
    if (req.method === 'GET' && url.pathname === '/__facts') return send(200, {run, origin: index, events: events.filter(e => e.origin === index)});
    if (req.method === 'GET' && url.pathname === '/') {
      res.writeHead(200, {'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store'});
      res.end(`<!doctype html><title>Owned BrowserSkill fixture ${index}</title><h1>Owned fixture ${index}</h1><label>Non-secret test value <input id="value" value="fixture"></label><button id="submit">Submit owned action</button><output id="result"></output><script>document.querySelector('#submit').onclick=async()=>{const r=await fetch('/api/echo?case=human-action',{method:'POST',body:document.querySelector('#value').value});document.querySelector('#result').textContent=await r.text()};</script>`);
      return;
    }
    if (url.pathname !== '/api/echo' || !['GET', 'POST'].includes(req.method)) return send(404, {error: 'OWNED_ROUTE_ONLY'});
    let body = '';
    for await (const chunk of req) {body += chunk.toString('utf8'); if (Buffer.byteLength(body) > 16384) return send(413, {error: 'BODY_TOO_LARGE'});}
    const event = {run, origin: index, sequence: events.length + 1, method: req.method, case: url.searchParams.get('case'), body, testHeader: req.headers['x-fixture-value'] ?? null};
    events.push(event);
    fs.appendFileSync(path.join(directory, 'server-requests.jsonl'), JSON.stringify(event) + '\n');
    send(200, {source: 'owned-server', ...event});
  }));
  try {for (let i = 0; i < servers.length; i++) await new Promise((resolve, reject) => {servers[i].once('error', reject); servers[i].listen(ports[i], '127.0.0.1', resolve);});}
  catch (error) {await Promise.all(servers.map(s => new Promise(resolve => s.close(resolve)))); throw error;}
  const manifest = {run, origins: servers.map(s => `http://127.0.0.1:${s.address().port}`), upstream: '3f10983c09fcd443c7420830ab984c9d409954bf'};
  fs.writeFileSync(path.join(directory, 'fixture.json'), JSON.stringify(manifest, null, 2));
  return {manifest, close: () => Promise.all(servers.map(s => new Promise(resolve => s.close(resolve))))};
}

if (path.resolve(process.argv[1] ?? '') === fileURLToPath(import.meta.url)) {
  const directory = process.argv[2];
  if (!directory) throw new Error('EXPLICIT_EVIDENCE_DIRECTORY_REQUIRED');
  const ports = process.argv.slice(3).map(Number);
  const fixture = await startFixture({directory: path.resolve(directory), ...(ports.length ? {ports} : {})});
  console.log(JSON.stringify({status: 'ready', ...fixture.manifest}));
  for (const signal of ['SIGINT', 'SIGTERM']) process.once(signal, async () => {await fixture.close(); process.exit(0);});
}
