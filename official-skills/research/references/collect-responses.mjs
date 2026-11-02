// 将本文件原样作为 run_code.code；tools 由现有受管 Node SDK 注入，不通过沙箱直接联网。
import { createHash } from 'node:crypto';
import { lstat, mkdir, readFile, realpath, writeFile } from 'node:fs/promises';
import { isIP } from 'node:net';
import path from 'node:path';

const inputDirectory = path.resolve('/input/data');
const artifactDirectory = path.resolve('/input/artifacts');
const outputDirectory = path.resolve('/workspace/output');
const maximumBytes = 4 * 1024 * 1024;
const digest = value => createHash('sha256').update(value, 'utf8').digest('hex');
const inside = (file, root) => file.startsWith(root + path.sep);
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);

async function mountedJson(file) {
  if (typeof file !== 'string' || !path.isAbsolute(file) || path.normalize(file) !== file || !file.endsWith('.json') ||
      ![inputDirectory, artifactDirectory].some(root => inside(file, root))) throw new Error('input must be an explicitly mounted JSON file');
  for (let current = file; current !== path.dirname(current); current = path.dirname(current)) {
    if ((await lstat(current)).isSymbolicLink()) throw new Error('input links are forbidden');
  }
  const resolved = await realpath(file);
  const attributes = await lstat(resolved);
  if (![inputDirectory, artifactDirectory].some(root => inside(resolved, root)) || !attributes.isFile() || attributes.size > maximumBytes)
    throw new Error('input file exceeds mount or byte budget');
  return JSON.parse(await readFile(resolved, 'utf8'));
}

function shortText(value, maximum) {
  if (typeof value !== 'string' || !value.trim() || value.length > maximum || /[\u0000-\u001f\u007f]/u.test(value))
    throw new Error('query or handle is invalid');
  return value.trim();
}

function publicUrl(value) {
  value = shortText(value, 4096);
  if (/[\s\\<>"']/u.test(value)) throw new Error('extract URL contains unsafe characters');
  const url = new URL(value);
  const host = url.hostname.replace(/\.$/u, '').toLowerCase();
  if (url.protocol !== 'https:' || url.username || url.password || url.port && url.port !== '443' || !host.includes('.') ||
      isIP(host) || host.startsWith('[') || !/^[a-z0-9.-]+$/u.test(host) || host === 'metadata.google.internal' ||
      /\.(local|internal|localhost)$/u.test(host)) throw new Error('extract URL must use a public HTTPS domain');
  url.hostname = host; url.hash = '';
  return url.toString();
}

const plan = await mountedJson(path.join(inputDirectory, 'research-requests.json'));
const allowed = new Set(['http_tool_handle', 'queries', 'urls', 'advanced_urls', 'extract_query', 'chunks_per_source', 'previous_responses_file']);
if (!object(plan) || Object.keys(plan).some(key => !allowed.has(key))) throw new Error('unsupported collector input');
const handle = shortText(plan.http_tool_handle, 128);
if (!/^tool:[A-Za-z0-9:_-]+$/u.test(handle)) throw new Error('use the currently visible http_request handle');
const queries = plan.queries ?? [];
const urls = plan.urls ?? [];
if (!Array.isArray(queries) || queries.length > 4 || !Array.isArray(urls) || urls.length > 12) throw new Error('collector request limit exceeded');
const requests = queries.map(query => ({ operation: 'search', body: {
  query: shortText(query, 400), search_depth: 'basic', max_results: 5, include_answer: false
} }));
let extractQuery;
if (plan.extract_query !== undefined) extractQuery = shortText(plan.extract_query, 400);
if (plan.chunks_per_source !== undefined && (!extractQuery || !Number.isInteger(plan.chunks_per_source) || plan.chunks_per_source < 1 || plan.chunks_per_source > 5))
  throw new Error('chunks_per_source requires an explicit extract_query and 1..5 chunks');
const previous = plan.previous_responses_file ? await mountedJson(plan.previous_responses_file) : { responses: [] };
if (!object(previous) || Object.keys(previous).length !== 1 || !Array.isArray(previous.responses)) throw new Error('previous receipts are invalid');
const responses = previous.responses;
const failedUrls = new Set(), successfulUrls = new Set();
for (const entry of responses) {
  if (!object(entry) || !object(entry.http_response) || typeof entry.http_response.body !== 'string' ||
      entry.body_sha256 !== digest(entry.http_response.body)) throw new Error('previous raw body digest mismatch');
  const response = entry.http_response;
  if (entry.operation !== 'extract' || !response.success || response.status < 200 || response.status >= 300 || response.truncated) continue;
  let body;
  try { body = JSON.parse(response.body); } catch { continue; }
  for (const result of body.results ?? []) if (typeof result?.url === 'string') successfulUrls.add(publicUrl(result.url));
  for (const result of body.failed_results ?? []) if (typeof result?.url === 'string') failedUrls.add(publicUrl(result.url));
}
const normalizedUrls = [...new Set(urls.map(publicUrl))];
const advanced = plan.advanced_urls ?? [];
if (!Array.isArray(advanced) || advanced.length > 12) throw new Error('advanced_urls must be an explicit URL list');
const advancedUrls = new Set(advanced.map(publicUrl));
for (const url of advancedUrls) {
  if (!normalizedUrls.includes(url) || !failedUrls.has(url) || successfulUrls.has(url))
    throw new Error('advanced extraction requires a previously recorded definite per-URL failure and no successful receipt');
}
for (const url of normalizedUrls) {
  const body = { urls: [url], extract_depth: advancedUrls.has(url) ? 'advanced' : 'basic', format: 'markdown', timeout: 20 };
  if (extractQuery) { body.query = extractQuery; body.chunks_per_source = plan.chunks_per_source ?? 3; }
  requests.push({ operation: 'extract', body });
}
if (!requests.length || responses.length + requests.length > 12) throw new Error('use 1..12 explicitly scoped total receipts');

// 所有请求在首次 RPC 前完成固定与校验；一个官方 URL 一次 extract，避免合并大正文越过 60k/64KiB。
const headers = { 'Content-Type': 'application/json', 'X-Tavily-Access-Mode': 'keyless',
  'X-Client-Source': 'skillforge-keyless', 'X-Client-Name': 'NoeWise' };
await mkdir(outputDirectory, { recursive: true });
const save = async () => {
  const raw = JSON.stringify({ responses }) + '\n';
  if (Buffer.byteLength(raw, 'utf8') > maximumBytes) throw new Error('saved receipts exceed byte budget');
  await writeFile(path.join(outputDirectory, 'responses.json'), raw, 'utf8');
};
let completed = 0;
for (const request of requests) {
  // SDK 未知或中断会直接抛错；本程序没有重试，不重跑已发送请求。
  const result = await tools.call(handle, { method: 'POST', url: 'https://api.tavily.com/' + request.operation, headers, body: request.body });
  const response = result.structuredContent;
  if (!object(response) || typeof response.body !== 'string' || typeof response.success !== 'boolean' ||
      !Number.isInteger(response.status) || typeof response.truncated !== 'boolean')
    throw new Error('managed http_request did not return a bounded raw receipt; do not replay this request');
  responses.push({ operation: request.operation, retrieved_at: new Date().toISOString(),
    body_sha256: digest(response.body), http_response: response });
  await save(); completed++;
  if (!response.success || response.status < 200 || response.status >= 300 || response.truncated) break;
}
const summary = { completed_requests: completed, planned_requests: requests.length,
  unexecuted_requests: requests.length - completed, response_count: responses.length, responses_file: 'responses.json',
  responses_sha256: digest(await readFile(path.join(outputDirectory, 'responses.json'), 'utf8')),
  responses: responses.map(entry => ({ operation: entry.operation, retrieved_at: entry.retrieved_at,
    success: entry.http_response.success, status: entry.http_response.status, truncated: entry.http_response.truncated,
    body_sha256: entry.body_sha256 })) };
await writeFile(path.join(outputDirectory, 'result.json'), JSON.stringify({ results: [
  { kind: 'text', format: 'plain', text: JSON.stringify(summary) }
] }), 'utf8');
