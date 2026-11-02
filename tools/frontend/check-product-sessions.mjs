/*
 * Capture real, two-client product-process evidence.  This script deliberately
 * has no page.route()/route.fulfill() calls: every response comes from the
 * configured service.  Credentials are read only from the process environment
 * and never enter normal evidence, console, screenshot names, or errors.
 * Profile preparation has a separate private credential output.
 *
 * Required environment:
 *   PRODUCT_PROCESS_SESSIONS       JSON file, {"sessions":[...]} or array
 *   PRODUCT_PROCESS_OUTPUT         output JSON (or PRODUCT_PROCESS_RUN_ROOT)
 *   PRODUCT_PROCESS_ADMIN_ORIGIN   institution UI origin
 *   PRODUCT_PROCESS_ADMIN_TENANT / _USERNAME / _PASSWORD
 *   PRODUCT_PROCESS_CHAT_ORIGIN    standalone chat origin
 *   PRODUCT_PROCESS_CHANNEL_TOKEN / _CHANNEL_ID / _CLIENT_REF
 *   PRODUCT_PROCESS_IDENTITY_TOKEN required when restoring a registered profile
 *   PRODUCT_PROCESS_PRIVATE_FILE   optional JSON object with the credential
 *                                  keys above; its content is never emitted
 *
 * Set PRODUCT_PROCESS_PREPARE_PROFILE=1 with PRODUCT_PROCESS_AGENT_ID to run
 * only real client-profile registration; its private output includes identity_token;
 * this mode does not require PRODUCT_PROCESS_SESSIONS or institution credentials.
 * Every normal session record needs session_id, end_user_id, and agent_id; optional
 * turns is a frozen list of {text}, {hitl_action}, or {cancel:true} institution
 * UI actions. D09's second turn uses the standalone client's formal revision
 * continuation control; the frozen dataset's institution declaration is retained
 * in the output together with this explicit consumer difference.
 * The supplied client_ref must already map to the same end user as the session.
 * Prepare it first by registering that ref through this UI, then have the API
 * runner create its session with the returned end_user_id.  client-profile has
 * no external_user_id/end_user_id override.  The standalone UI intentionally
 * has no arbitrary-session URL or selector.
 */
import { appendFile, mkdir, readFile, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { dirname, resolve } from 'node:path';
import { createRequire } from 'node:module';
import { connectNative, readNativeSession, readAdminArtifacts } from '../full_project_testing/live_product_controls.mjs';
import { prepareNativeContinuation, submitNativeContinuation, readNativeDeliverables } from '../full_project_testing/live_product_continuation.mjs';
import { verifyProductDownload } from '../full_project_testing/live_product_download.mjs';
const require = createRequire(new URL('../../frontend-admin/package.json', import.meta.url));
const { chromium } = require('@playwright/test');

const privateFile = process.env.PRODUCT_PROCESS_PRIVATE_FILE;
const privateValues = privateFile ? JSON.parse(await readFile(privateFile, 'utf8')) : {};
const required = name => {
  const value = process.env[name] || privateValues[name];
  if (!value) throw new Error(`missing_required_environment:${name}`);
  return value;
};
const prepareProfileOnly = process.env.PRODUCT_PROCESS_PREPARE_PROFILE === '1';
const sessionsFile = process.env.PRODUCT_PROCESS_SESSIONS;
const runRoot = process.env.PRODUCT_PROCESS_RUN_ROOT;
const output = process.env.PRODUCT_PROCESS_OUTPUT || (runRoot && resolve(runRoot, 'product-process-browser.json'));
if (!output) throw new Error('missing_required_environment:PRODUCT_PROCESS_OUTPUT');
const readOnly = process.env.PRODUCT_PROCESS_READ_ONLY === '1';
const runTimeoutMs = Number.parseInt(process.env.PRODUCT_PROCESS_RUN_TIMEOUT_MS || '60000', 10);
if (!Number.isSafeInteger(runTimeoutMs) || runTimeoutMs < 1) throw new Error('invalid_PRODUCT_PROCESS_RUN_TIMEOUT_MS');
let privacyMarkers = [];
if (process.env.PRODUCT_PROCESS_PRIVACY_MARKERS) {
  try {
    const parsed = JSON.parse(process.env.PRODUCT_PROCESS_PRIVACY_MARKERS);
    privacyMarkers = Array.isArray(parsed) ? [...new Set(parsed.filter(value => typeof value === 'string' && value))] : [];
  } catch {
    throw new Error('invalid_PRODUCT_PROCESS_PRIVACY_MARKERS');
  }
}
const adminOrigin = prepareProfileOnly ? undefined : required('PRODUCT_PROCESS_ADMIN_ORIGIN').replace(/\/$/, '');
const chatOrigin = required('PRODUCT_PROCESS_CHAT_ORIGIN').replace(/\/$/, '');
const admin = prepareProfileOnly ? undefined : {
  tenant: required('PRODUCT_PROCESS_ADMIN_TENANT'),
  username: required('PRODUCT_PROCESS_ADMIN_USERNAME'),
  password: required('PRODUCT_PROCESS_ADMIN_PASSWORD'),
};
const chat = {
  token: required('PRODUCT_PROCESS_CHANNEL_TOKEN'),
  channelId: required('PRODUCT_PROCESS_CHANNEL_ID'),
  clientRef: required('PRODUCT_PROCESS_CLIENT_REF'),
  identityToken: process.env.PRODUCT_PROCESS_IDENTITY_TOKEN || privateValues.PRODUCT_PROCESS_IDENTITY_TOKEN || '',
  nickname: process.env.PRODUCT_PROCESS_CHAT_NICKNAME || privateValues.PRODUCT_PROCESS_CHAT_NICKNAME || '产品过程验收',
};
const nativeConnections = new WeakMap();
const raw = sessionsFile ? JSON.parse(await readFile(sessionsFile, 'utf8')) : undefined;
const sessions = Array.isArray(raw) ? raw : raw?.sessions;
if (!prepareProfileOnly && (!Array.isArray(sessions) || !sessions.length || !sessions.every(item => item && typeof item.session_id === 'string'
  && typeof item.end_user_id === 'string' && typeof item.agent_id === 'string'))) {
  throw new Error('invalid_sessions_contract');
}
if (prepareProfileOnly && !process.env.PRODUCT_PROCESS_AGENT_ID) throw new Error('missing_required_environment:PRODUCT_PROCESS_AGENT_ID');
if (!prepareProfileOnly && sessions.some(item => item.case_id === 'D12') && !privacyMarkers.length) {
  throw new Error('privacy_markers_missing');
}

await mkdir(dirname(output), { recursive: true });
const journalPath = `${output}.journal.jsonl`;
const journalLines = (await readFile(journalPath, 'utf8').catch(() => '')).split(/\r?\n/).filter(Boolean);
const journalledSessions = new Set(journalLines.flatMap(line => {
  try {
    const value = JSON.parse(line);
    return typeof value?.session_id === 'string' && value.session_id ? [value.session_id] : [];
  } catch {
    return [];
  }
}));
const textDigest = value => createHash('sha256').update(value, 'utf8').digest('hex');
const appendJournal = record => appendFile(journalPath, `${JSON.stringify(record)}\n`, 'utf8');

const status = (state, reference, observed = {}) => ({ status: state, reference, observed });
const unknown = (reference, reason) => status('unknown', reference, { reason });
const failed = (reference, reason) => status('failed', reference, { reason });
const resultReference = (client, session, part) => `live:${client}:${session}:${part}`;
const dataOf = body => body && typeof body === 'object' && body.code === 'OK' ? body.data : undefined;
const identity = value => typeof value === 'string' && value ? value : undefined;
const COMPLETED_RUN_STATUSES = new Set(['completed', 'cancelled', 'failed']);

function terminalStatusesFor(caseId, kind) {
  // D10 的首轮必须等待人工确认；其余消息只在 Run 真正终态后才能进入下一步。
  return caseId === 'D10' && kind === 'message'
    ? new Set([...COMPLETED_RUN_STATUSES, 'waiting_hitl']) : COMPLETED_RUN_STATUSES;
}

function terminalFactMatches(runId, statuses, fact) {
  return fact.run_id === runId && statuses.has(fact.status);
}

function capturePublicPrivacy(markers, sessionId) {
  const snapshots = [];
  const pending = [];
  const markerDigests = markers.map(textDigest).sort();
  const record = (source, kind, content) => {
    if (typeof content !== 'string') return;
    snapshots.push({ source, kind, observed: true, content_sha256: textDigest(content),
      character_count: content.length, contains_forbidden: markers.some(marker => content.includes(marker)) });
  };
  return {
    observeResponses(page, client) {
      page.on('response', response => {
        const url = new URL(response.url());
        if (response.request().method() !== 'GET' || !url.pathname.endsWith('/api/v1/chat/history')
            || url.searchParams.get('session_id') !== sessionId) return;
        pending.push(response.text().then(content => record(`${client}:response`, 'response', content)).catch(() => {}));
      });
    },
    async observeDom(page, client) {
      try {
        record(`${client}:dom`, 'dom', await page.locator('body').innerText());
      } catch {
        // 无法读取页面正文时不伪造已检查的 DOM 快照。
      }
    },
    async report(reference) {
      await Promise.allSettled(pending);
      // 保留每次响应，后来的干净快照不能覆盖先前的泄露证据。
      const publicSnapshots = snapshots;
      return { marker_digests: markerDigests, marker_count: markerDigests.length,
        public_snapshots: publicSnapshots, exposure_detected: publicSnapshots.some(snapshot => snapshot.contains_forbidden),
        sample_source: process.env.PRODUCT_PROCESS_PRIVACY_SAMPLE_SOURCE || 'browser-live', sampled_at_epoch_ms: Date.now(), reference };
    },
  };
}

function publicHistory(snapshot) {
  const messages = Array.isArray(snapshot?.messages) ? snapshot.messages : [];
  const processes = Array.isArray(snapshot?.processes) ? [...snapshot.processes]
    .sort((left, right) => left.ordinal - right.ordinal || left.sequence - right.sequence || left.process_id.localeCompare(right.process_id)) : [];
  return {
    mode: 'live', session_id: snapshot?.session_id,
    process_ids: processes.map(item => item?.process_id).filter(identity),
    final_message_ids: messages.filter(item => item?.role === 'assistant'
      && item?.metadata?.phase === 'final')
      .map(item => item.message_id).filter(identity),
  };
}

function processArtifact(snapshot) {
  return (Array.isArray(snapshot?.processes) ? [...snapshot.processes] : [])
    .sort((left, right) => left.ordinal - right.ordinal || left.sequence - right.sequence || left.process_id.localeCompare(right.process_id))
    .map(item => ({
    process_id: item?.process_id, run_id: item?.run_id, root_run_id: item?.root_run_id,
    root_input_id: item?.root_input_id, type: item?.type, sequence: item?.sequence, ordinal: item?.ordinal,
    reference: `persisted:${item?.process_id || 'unknown'}`,
    }));
}

function interleaved(processes) {
  const ordered = [...processes].sort((a, b) => a.ordinal - b.ordinal || a.sequence - b.sequence || a.process_id.localeCompare(b.process_id));
  const kinds = ordered.map(item => item.type);
  return kinds.some((kind, index) => kind === 'commentary' && kinds.slice(index + 1).some(next => next !== 'commentary'))
    && kinds.some((kind, index) => kind !== 'commentary' && kinds.slice(index + 1).some(next => next === 'commentary'));
}

async function responseJson(page, predicate, action) {
  const response = page.waitForResponse(response => predicate(response.url(), response.request().method()), { timeout: 15_000 });
  // 操作失败关闭页面时，观察 Promise 的拒绝，保留原始操作错误；成功路径仍 await 原 Promise。
  void response.catch(() => {});
  await action();
  return dataOf(await (await response).json());
}

async function captureTerminalFacts(page, sessionId) {
  const facts = [];
  const pending = [];
  const waiters = new Map();
  const settle = fact => {
    facts.push(fact);
    const waiter = waiters.get(fact.run_id);
    if (waiter && waiter.matches(fact)) {
      waiters.delete(fact.run_id);
      waiter.resolve(fact);
    }
  };
  await page.exposeBinding('__productTerminalFact', (_source, fact) => settle(fact));
  // 刷新后的页面只轮询持久历史；旁读页面真实响应，不能把它伪装成 SSE。
  page.on('response', response => {
    const url = new URL(response.url());
    if (response.request().method() !== 'GET' || !response.ok()
        || !url.pathname.endsWith('/api/v1/chat/history') || url.searchParams.get('session_id') !== sessionId) return;
    const read = (async () => {
      const history = dataOf(await response.json());
      for (const activity of history?.activities ?? []) {
        if (activity.node_type !== 'agent_runtime' || typeof activity.node_id !== 'string'
            || !['completed', 'failed', 'cancelled'].includes(activity.status)) continue;
        settle({ event: 'history.run_terminal', source: 'browser_history_response', session_id: sessionId,
          run_id: activity.node_id, status: activity.status });
      }
    })().catch(() => {});
    pending.push(read);
  });
  const install = ({ sessionId }) => {
    if (window.__productStreamObserved) return;
    window.__productStreamObserved = true;
    const original = window.fetch;
    window.fetch = async function (...args) {
      const response = await original.apply(this, args);
      const url = new URL(response.url, location.href);
      if (url.pathname.endsWith('/api/v1/chat/events') && url.searchParams.get('session_id') === sessionId) {
        // 只旁读真实 SSE 的副本，不等待长连接关闭，也不更改页面收到的字节。
        const copy = response.clone();
        void (async () => {
          const reader = copy.body.getReader();
          const decoder = new TextDecoder();
          let buffer = '';
          while (true) {
            const chunk = await reader.read();
            if (chunk.done) break;
            buffer += decoder.decode(chunk.value, { stream: true }).replace(/\r\n/g, '\n');
            let boundary;
            while ((boundary = buffer.indexOf('\n\n')) >= 0) {
              const block = buffer.slice(0, boundary); buffer = buffer.slice(boundary + 2);
              const event = block.match(/^event:\s*(.+)$/m)?.[1]?.trim();
              if (!['execution.completed', 'hitl.required'].includes(event)) continue;
              const data = block.split('\n').filter(line => line.startsWith('data:')).map(line => line.slice(5).trimStart()).join('\n');
              const parsed = JSON.parse(data); const runId = parsed.run_id || parsed.execution_id;
              if (parsed.session_id === sessionId && typeof runId === 'string') {
                await window.__productTerminalFact({ event, session_id: sessionId, run_id: runId,
                  input_id: parsed.input_id, status: parsed.status || (event === 'hitl.required' ? 'waiting_hitl' : undefined) });
              }
            }
          }
        })().catch(() => {});
      }
      return response;
    };
  };
  await page.addInitScript(install, { sessionId });
  await page.evaluate(install, { sessionId });
  return {
    facts,
    pending,
    mark: () => facts.length,
    waitForRun: (runId, after = 0, statuses = COMPLETED_RUN_STATUSES) => {
      const matches = item => terminalFactMatches(runId, statuses, item);
      const existing = facts.slice(after).find(matches);
      if (existing) return Promise.resolve(existing);
      return new Promise((resolve, reject) => {
        const timer = setTimeout(() => {
          waiters.delete(runId);
          reject(new Error('terminal_fact_missing'));
        }, runTimeoutMs);
        waiters.set(runId, {
          matches,
          resolve: fact => { clearTimeout(timer); resolve(fact); },
        });
        const raced = facts.slice(after).find(matches);
        if (raced) {
          waiters.delete(runId);
          clearTimeout(timer);
          resolve(raced);
        }
      });
    },
  };
}

async function loginAdmin(page) {
  await page.goto(`${adminOrigin}/login`, { waitUntil: 'domcontentloaded' });
  await page.locator('input[autocomplete="organization"]').fill(admin.tenant);
  await page.locator('input[autocomplete="username"]').fill(admin.username);
  await page.locator('input[autocomplete="current-password"]').fill(admin.password);
  await Promise.all([
    page.waitForURL(url => new URL(url).pathname.startsWith('/admin/'), { timeout: 15_000 }),
    page.getByRole('button', { name: '登录', exact: true }).click(),
  ]);
}

async function selectAdminSession(page, wanted) {
  const sessionListResponse = page.waitForResponse(response => response.request().method() === 'GET'
    && new URL(response.url()).pathname.endsWith('/api/v1/chat/sessions'), { timeout: 15_000 });
  void sessionListResponse.catch(() => {});
  await page.goto(`${adminOrigin}/admin/chat`, { waitUntil: 'domcontentloaded' });
  const list = dataOf(await (await sessionListResponse).json());
  const selected = Array.isArray(list) ? list.find(item => item?.session_id === wanted.session_id) : undefined;
  if (!selected) throw new Error('admin_session_not_listed');
  await page.locator('.el-select').filter({ hasText: '历史会话' }).getByRole('combobox').click();
  const history = await responseJson(page,
    (url, method) => method === 'GET' && new URL(url).pathname.endsWith('/api/v1/chat/history')
      && new URL(url).searchParams.get('session_id') === wanted.session_id,
    () => page.getByRole('option', { name: selected.summary || selected.session_id, exact: true }).click());
  if (history?.session_id !== wanted.session_id) throw new Error('admin_history_session_mismatch');
  return history;
}

async function observeAdmin(page, wanted, nativePage) {
  const terminalCapture = await captureTerminalFacts(page, wanted.session_id);
  await selectAdminSession(page, wanted);
  const requests = await dispatchInstitutionTurns(page, wanted, terminalCapture, nativePage);
  await Promise.allSettled(terminalCapture.pending);
  // 每次提交完成后重新进入同一持久化会话，不能将提交前快照作为两端一致性证据。
  const history = await selectAdminSession(page, wanted);
  const ref = part => resultReference('institution', wanted.session_id, part);
  const processes = processArtifact(history);
  const group = page.locator('.process-group').first();
  const hasGroup = await group.count() > 0;
  const processInterleave = hasGroup && interleaved(processes)
    ? status('passed', ref('process-interleave'), { process_count: processes.length })
    : unknown(ref('process-interleave'), 'live_session_did_not_exercise_interleave');
  let manualExpand = unknown(ref('manual-expand'), 'process_group_missing');
  if (hasGroup) {
    const toggle = group.locator('.process-group-toggle');
    if (await toggle.getAttribute('aria-expanded') === 'false') await toggle.click();
    const visible = await group.locator('.process-item').count();
    manualExpand = visible > 0 ? status('passed', ref('manual-expand'), { visible_items: visible })
      : failed(ref('manual-expand'), 'manual_expand_did_not_reveal_items');
  }
  const pending = history?.pending_interactions?.length;
  const hitlReceipt = requests.find(request => request.kind === 'hitl' && request.terminal_event);
  const waiting = pending || hitlReceipt ? status('passed', ref('waiting-input'), {
    pending: Boolean(pending), visible: Boolean(pending || hitlReceipt),
    ...(hitlReceipt ? { hitl_action_submitted: true } : {}),
  })
    : unknown(ref('waiting-input'), 'live_session_not_waiting_input');
  const collaboration = history?.collaboration;
  const parentChild = Array.isArray(collaboration?.todos) && collaboration.todos.length
    ? status('passed', ref('parent-child'), { todo_count: collaboration.todos.length })
    : unknown(ref('parent-child'), 'live_session_has_no_child_summary');
  // 当前机构调试页使用真实成果表格；不存在旧稿的“调试面板”按钮。
  const artifacts = await readAdminArtifacts(page, wanted.session_id);
  let artifactDownload = unknown(ref('artifact-download'), 'live_session_has_no_downloadable_artifact');
  if (wanted.case_id === 'D09' && artifacts.length) {
    const revision = artifacts.find(item => item.status === 'ready' && item.files?.length);
    const file = revision?.files[0];
    if (file) {
      const rows = page.getByRole('row').filter({ has: page.getByText(revision.name, { exact: true }) });
      const row = rows.filter({ has: page.getByRole('cell', { name: String(revision.revision), exact: true }) });
      const control = row.locator('.toolbar').filter({ has: page.getByText(file.path, { exact: true }) })
        .getByRole('button', { name: '下载', exact: true });
      let rendered = false;
      try { await control.waitFor({ state: 'visible', timeout: 15_000 }); rendered = true; } catch { /* Preserve unobserved UI as UNKNOWN. */ }
      if (!rendered) artifactDownload = unknown(ref('artifact-download'), 'download_control_render_not_observed');
      else if (await control.count() === 1) {
        const verified = await verifyProductDownload({ page, click: () => control.click(), revisionId: revision.id, file,
          evidenceDir: resolve(dirname(output), 'downloads', textDigest(wanted.session_id)) });
        artifactDownload = status(verified.status, ref('artifact-download'), verified);
      } else artifactDownload = unknown(ref('artifact-download'), 'download_control_identity_ambiguous');
    }
  }
  const cancelRequest = requests.find(request => request.kind === 'cancel');
  const cancel = cancelRequest?.terminal_status === 'cancelled' ? status('passed', ref('cancel'), {
    requested: true, status: cancelRequest.terminal_status, terminal_event: cancelRequest.terminal_event,
    terminal_source: cancelRequest.terminal_source,
  })
    : unknown(ref('cancel'), 'cancel_not_present_in_frozen_turns');
  return { history, processes, artifacts, waiting, collaboration, requests, terminal_facts: terminalCapture.facts, operations: {
    process_interleave: processInterleave,
    duplicate_replay: unknown(ref('duplicate-replay'), 'no_live_duplicate_injection_endpoint'),
    out_of_order_replay: unknown(ref('out-of-order-replay'), 'no_live_reorder_injection_endpoint'),
    final_before_completion: unknown(ref('final-before-completion'), 'transition_not_observed_in_static_history_read'),
    auto_collapse_once: unknown(ref('auto-collapse-once'), 'transition_count_not_observable_from_static_history_read'),
    manual_expand: manualExpand,
    waiting_input_visible: waiting,
    parent_child_status: parentChild,
    history_read: status('passed', ref('history-read'), { message_count: Array.isArray(history?.messages) ? history.messages.length : 0, refreshed: true }),
    artifact_read: artifacts.length
      ? status('passed', ref('artifact-read'), { artifact_count: artifacts.length })
      : unknown(ref('artifact-read'), 'live_session_has_no_artifact'),
    artifact_download: artifactDownload,
    cancel,
  }};
}

async function connectChat(page, wanted) {
  const existing = nativeConnections.get(page);
  if (existing) return existing;
  const connection = await connectNative(page, { origin: chatOrigin, channelId: chat.channelId, clientRef: chat.clientRef,
                                             token: chat.token, identityToken: chat.identityToken, nickname: chat.nickname });
  nativeConnections.set(page, connection);
  return connection;
}

async function dispatchNativeRevision(page, wanted, initialRun, index) {
  if (!page) throw new Error('d09_native_consumer_page_required');
  const terminalCapture = await captureTerminalFacts(page, wanted.session_id);
  const { profile, catalog } = await connectChat(page, wanted);
  if (profile?.end_user_id !== wanted.end_user_id) throw new Error('d09_native_profile_end_user_mismatch');
  await readNativeSession(page, wanted, catalog);
  const continuation = await prepareNativeContinuation(page, wanted, initialRun);
  const text = wanted.turns[index].text;
  await page.getByRole('textbox', { name: '消息', exact: true }).fill(text);
  await appendJournal({ session_id: wanted.session_id, turn_index: index, kind: 'message', phase: 'intent',
    consumer: 'chat', text_sha256: textDigest(text), artifact_continuation: continuation });
  journalledSessions.add(wanted.session_id);
  const mark = terminalCapture.mark();
  const { receipt, observed } = await submitNativeContinuation(page, wanted, text, continuation,
    actual => appendJournal({ ...actual, turn_index: index, kind: 'message', phase: 'request_observed' }));
  const accepted = { turn_index: index, kind: 'message', consumer: 'chat', session_id: receipt.session_id,
    run_id: receipt.execution_id, input_id: receipt.input_id,
    artifact_continuation: { ...continuation, actual_submitted: observed } };
  await appendJournal({ ...accepted, phase: 'receipt' });
  const terminal = await terminalCapture.waitForRun(receipt.execution_id, mark);
  await appendJournal({ session_id: wanted.session_id, turn_index: index, kind: 'message', phase: 'terminal',
    consumer: 'chat', event: terminal.event, source: terminal.source || 'browser_native_sse',
    run_id: terminal.run_id, input_id: terminal.input_id, status: terminal.status });
  return { receipt: { ...accepted, terminal_event: terminal.event,
    terminal_source: terminal.source || 'browser_native_sse', terminal_status: terminal.status },
    facts: terminalCapture.facts };
}

function turnKind(turn) {
  if (typeof turn?.text === 'string' && turn.text) return 'message';
  if (typeof turn?.hitl_action === 'string' && turn.hitl_action) return 'hitl';
  if (turn?.cancel === true) return 'cancel';
  if (turn?.upload) return 'upload';
  if (turn?.modify) return 'modify';
  return undefined;
}

async function dispatchInstitutionTurns(page, wanted, terminalCapture, nativePage) {
  const receipts = [];
  if (readOnly) {
    // 恢复时只复用持久回执；绝不再次点击发送。
    return journalLines.flatMap(line => {
      const row = JSON.parse(line);
      return row.session_id === wanted.session_id && row.phase === 'receipt' ? [row] : [];
    });
  }
  for (const [index, turn] of (Array.isArray(wanted.turns) ? wanted.turns : []).entries()) {
    if (!turn || typeof turn !== 'object') throw new Error('invalid_frozen_browser_turn');
    const kind = turnKind(turn);
    if (!kind) throw new Error('unsupported_frozen_browser_turn');
    const prior = journalLines.map(line => JSON.parse(line)).filter(row => row.session_id === wanted.session_id && row.turn_index === index);
    if (prior.length) {
      const recovered = wanted.recovered_turns?.find(row => row.turn_index === index);
      if (!recovered?.receipt?.run_id || !recovered?.terminal) throw new Error('browser_input_unknown_do_not_resend');
      if (!terminalStatusesFor(wanted.case_id, kind).has(recovered.terminal.status)
          && !(wanted.turns[index + 1]?.cancel === true && recovered.terminal.status === 'running')) {
        throw new Error('browser_previous_input_not_successful');
      }
      receipts.push({ ...recovered.receipt, terminal_event: recovered.terminal.event || 'run.status',
        terminal_status: recovered.terminal.status, terminal_source: recovered.terminal.source || 'run_status_readback' });
      if (recovered.terminal.status === 'failed' || recovered.terminal.status === 'cancelled') break;
      continue;
    }
    if (wanted.case_id === 'D09' && index === 1 && kind === 'message') {
      const initial = receipts.find(receipt => receipt.turn_index === 0 && receipt.kind === 'message');
      if (!initial?.run_id) throw new Error('d09_initial_actual_run_required');
      const actual = await dispatchNativeRevision(nativePage, wanted, initial.run_id, index);
      receipts.push(actual.receipt);
      terminalCapture.facts.push(...actual.facts);
      if (['failed', 'cancelled'].includes(actual.receipt.terminal_status)) break;
      continue;
    }
    // 草稿准备不发送聊天；只有准备完毕才登记提交意图，避免把 UI 失败误记为未知请求。
    if (kind === 'message') {
      await page.getByPlaceholder('输入消息', { exact: true }).fill(turn.text);
    }
    await appendJournal({ session_id: wanted.session_id, turn_index: index, kind, phase: 'intent',
      ...(kind === 'message' ? { text_sha256: textDigest(turn.text) } : {}) });
    journalledSessions.add(wanted.session_id);
    if (typeof turn.text === 'string' && turn.text) {
      const mark = terminalCapture.mark();
      const firstTool = wanted.turns[index + 1]?.cancel === true ? page.waitForResponse(async response => {
        const url = new URL(response.url());
        if (response.request().method() !== 'GET' || !response.ok()
            || !url.pathname.endsWith('/api/v1/chat/history') || url.searchParams.get('session_id') !== wanted.session_id) return false;
        try { return dataOf(await response.json())?.processes?.some(item => item.type === 'tool') === true; }
        catch { return false; }
      }, { timeout: 120_000 }) : undefined;
      void firstTool?.catch(() => {});
      const receipt = await responseJson(page,
        (url, method) => method === 'POST' && new URL(url).pathname.endsWith('/api/v1/chat'),
        async () => {
          await page.getByRole('button', { name: '发送' }).click();
        });
      if (!receipt?.session_id || !receipt?.execution_id || !receipt?.input_id) throw new Error('chat_submit_identity_missing');
      if (receipt.session_id !== wanted.session_id) throw new Error('institution_submit_session_mismatch');
      const accepted = { turn_index: index, kind: 'message', consumer: 'institution', session_id: receipt.session_id,
        run_id: receipt.execution_id, input_id: receipt.input_id };
      await appendJournal({ ...accepted, phase: 'receipt' });
      if (wanted.turns[index + 1]?.cancel === true) {
        // 先观察真实工具过程，再刷新切断页面连接；回到原会话取消在途任务。
        await firstTool;
        await page.reload({ waitUntil: 'domcontentloaded' });
        await selectAdminSession(page, wanted);
        receipts.push({ ...accepted, reconnected: true });
        continue;
      }
      const terminal = await terminalCapture.waitForRun(receipt.execution_id, mark,
        terminalStatusesFor(wanted.case_id, 'message'));
      await appendJournal({ session_id: wanted.session_id, turn_index: index, kind, phase: 'terminal',
        event: terminal.event, source: terminal.source, run_id: terminal.run_id, input_id: terminal.input_id, status: terminal.status });
      receipts.push({ ...accepted, terminal_event: terminal.event, terminal_source: terminal.source, terminal_status: terminal.status });
      // 前置消息失败后没有可供修改或确认的结果；采集失败事实，不能继续派发依赖轮次。
      if (terminal.status === 'failed' || terminal.status === 'cancelled') break;
      continue;
    }
    if (typeof turn.hitl_action === 'string' && turn.hitl_action) {
      const mark = terminalCapture.mark();
      const receipt = await responseJson(page,
        (url, method) => method === 'POST' && new URL(url).pathname.endsWith('/api/v1/chat'),
        () => page.getByRole('button', { name: turn.hitl_action, exact: true }).click());
      if (!receipt?.session_id || !receipt?.execution_id || !receipt?.input_id) throw new Error('chat_hitl_identity_missing');
      if (receipt.session_id !== wanted.session_id) throw new Error('institution_hitl_session_mismatch');
      const accepted = { turn_index: index, kind: 'hitl', session_id: receipt.session_id,
        run_id: receipt.execution_id, input_id: receipt.input_id };
      await appendJournal({ ...accepted, phase: 'receipt' });
      const terminal = await terminalCapture.waitForRun(receipt.execution_id, mark);
      await appendJournal({ session_id: wanted.session_id, turn_index: index, kind, phase: 'terminal',
        event: terminal.event, source: terminal.source, run_id: terminal.run_id, input_id: terminal.input_id, status: terminal.status });
      receipts.push({ ...accepted, terminal_event: terminal.event, terminal_source: terminal.source, terminal_status: terminal.status });
      continue;
    }
    if (turn.cancel === true) {
      const mark = terminalCapture.mark();
      const receipt = await responseJson(page,
        (url, method) => method === 'POST' && new URL(url).pathname.endsWith('/api/v1/chat/runs/cancel'),
        () => page.getByRole('button', { name: '停止运行', exact: true }).click());
      if (!receipt?.session_id || !receipt?.execution_id) throw new Error('chat_cancel_identity_missing');
      if (receipt.session_id !== wanted.session_id) throw new Error('institution_cancel_session_mismatch');
      const accepted = { turn_index: index, kind: 'cancel', session_id: receipt.session_id,
        run_id: receipt.execution_id, status: receipt.status };
      await appendJournal({ ...accepted, phase: 'receipt' });
      const terminal = await terminalCapture.waitForRun(receipt.execution_id, mark);
      await appendJournal({ session_id: wanted.session_id, turn_index: index, kind, phase: 'terminal',
        event: terminal.event, source: terminal.source, run_id: terminal.run_id, input_id: terminal.input_id, status: terminal.status });
      receipts.push({ ...accepted, terminal_event: terminal.event, terminal_source: terminal.source, terminal_status: terminal.status });
      continue;
    }
    if (turn.upload || turn.modify) {
      // 当前机构聊天 UI 没有文件上传或历史消息编辑控件；不能用 API 旁路伪装为浏览器操作。
      receipts.push({ turn_index: index, kind: turn.upload ? 'upload' : 'modify', status: 'unknown',
        reason: 'institution_chat_ui_has_no_upload_or_history_modify_control' });
      continue;
    }
  }
  return receipts;
}

async function observeChat(page, wanted) {
  const { profile, catalog } = await connectChat(page, wanted);
  if (profile?.end_user_id !== wanted.end_user_id) throw new Error('chat_profile_end_user_mismatch');
  const ref = part => resultReference('chat', wanted.session_id, part);
  const history = await readNativeSession(page, wanted, catalog);
  const group = page.locator('.process-group').first();
  const hasGroup = await group.count() > 0;
  let manualExpand = unknown(ref('manual-expand'), 'process_group_missing');
  if (hasGroup) {
    const toggle = group.locator('.process-toggle');
    if (await toggle.getAttribute('aria-expanded') === 'false') await toggle.click();
    const count = await group.locator('.process-step').count();
    manualExpand = count ? status('passed', ref('manual-expand'), { visible_items: count })
      : failed(ref('manual-expand'), 'manual_expand_did_not_reveal_items');
  }
  const refreshHistory = await responseJson(page,
    (url, method) => method === 'GET' && new URL(url).pathname.endsWith('/api/v1/chat/history')
      && new URL(url).searchParams.get('session_id') === wanted.session_id,
    () => page.reload({ waitUntil: 'domcontentloaded' }));
  const pending = refreshHistory?.pending_interactions?.length ?? history?.pending_interactions?.length;
  const processes = processArtifact(refreshHistory);
  let nativeArtifactControls;
  if (process.env.PRODUCT_PROCESS_NATIVE_ARTIFACT_PREFLIGHT === '1') {
    const actual = await readNativeDeliverables(page, wanted);
    nativeArtifactControls = status('passed', ref('native-artifact-controls'), { artifact_count: actual.length,
      actual_collection_endpoint: '/api/v1/chat/sessions/deliverables', model_dispatch: false });
  }
  return { history: refreshHistory, processes, profile, operations: {
    ...(nativeArtifactControls ? { native_artifact_controls: nativeArtifactControls } : {}),
    process_interleave: hasGroup && interleaved(processes)
      ? status('passed', ref('process-interleave'), { process_count: processes.length })
      : unknown(ref('process-interleave'), 'live_session_did_not_exercise_interleave'),
    duplicate_replay: unknown(ref('duplicate-replay'), 'no_live_duplicate_injection_endpoint'),
    out_of_order_replay: unknown(ref('out-of-order-replay'), 'no_live_reorder_injection_endpoint'),
    final_before_completion: unknown(ref('final-before-completion'), 'transition_not_observed_in_static_history_read'),
    auto_collapse_once: unknown(ref('auto-collapse-once'), 'transition_count_not_observable_from_static_history_read'),
    manual_expand: manualExpand,
    waiting_input_visible: pending ? status('passed', ref('waiting-input'), { pending: true })
      : unknown(ref('waiting-input'), 'live_session_not_waiting_input'),
    parent_child_status: unknown(ref('parent-child'), 'standalone_client_has_summary_only_no_child_session_selector'),
    history_read: status('passed', ref('history-read'), { message_count: Array.isArray(refreshHistory?.messages) ? refreshHistory.messages.length : 0, refreshed: true }),
  }};
}

const browser = await chromium.launch({ headless: process.env.PRODUCT_PROCESS_HEADFUL !== '1' });
const bundles = [];
const profiles = [];
try {
  const work = prepareProfileOnly ? [{ agent_id: process.env.PRODUCT_PROCESS_AGENT_ID }] : sessions;
  for (const wanted of work) {
    const adminContext = await browser.newContext({ acceptDownloads: true });
    const chatContext = await browser.newContext({ acceptDownloads: true });
    try {
      const chatPage = await chatContext.newPage();
      chatPage.setDefaultTimeout(15_000);
      if (prepareProfileOnly) {
        const { profile } = await connectChat(chatPage, wanted);
        if (!profile?.end_user_id || !profile?.channel_id) throw new Error('client_profile_identity_missing');
        profiles.push({ end_user_id: profile.end_user_id, channel_id: profile.channel_id,
          identity_token: profile.identity_token, agent_id: wanted.agent_id });
        continue;
      }
      const adminPage = await adminContext.newPage();
      adminPage.setDefaultTimeout(15_000);
      const privacyCapture = capturePublicPrivacy(privacyMarkers, wanted.session_id);
      privacyCapture.observeResponses(adminPage, 'institution');
      privacyCapture.observeResponses(chatPage, 'chat');
      await loginAdmin(adminPage);
      const institution = await observeAdmin(adminPage, wanted, chatPage);
      const standalone = await observeChat(chatPage, wanted);
      await privacyCapture.observeDom(adminPage, 'institution');
      await privacyCapture.observeDom(chatPage, 'chat');
      const institutionHistory = { ...publicHistory(institution.history), reference: resultReference('institution', wanted.session_id, 'history') };
      const chatHistory = { ...publicHistory(standalone.history), reference: resultReference('chat', wanted.session_id, 'history') };
      bundles.push({
        ...(typeof wanted.case_id === 'string' ? { case_id: wanted.case_id } : {}),
        ...(wanted.case_id === 'D09' ? { consumer_contract: {
          dataset_declared_consumer: 'institution', planned_turn_consumers: ['institution', 'chat'],
          actual_turn_consumers: institution.requests.filter(request => request.kind === 'message')
            .sort((left, right) => left.turn_index - right.turn_index).map(request => request.consumer),
          difference: 'native_formal_revision_continuation_control', dataset_modified: false
        } } : {}),
        session: { session_id: wanted.session_id, reference: `live:session:${wanted.session_id}` },
        artifacts: {
          processes: institution.processes, history: { institution: institutionHistory, chat: chatHistory },
          waiting: { status: institution.history?.pending_interactions?.length ? 'waiting_input' : 'not_waiting', visible: Boolean(institution.history?.pending_interactions?.length), reference: resultReference('institution', wanted.session_id, 'waiting') },
          collaboration: { parent_session_id: wanted.session_id, children: [], reference: resultReference('institution', wanted.session_id, 'collaboration') },
          browser_artifacts: institution.artifacts,
        },
        browser: {
          institution: { mode: 'live', session_id: wanted.session_id, reference: resultReference('institution', wanted.session_id, 'client'), operations: institution.operations },
          chat: { mode: 'live', session_id: wanted.session_id, reference: resultReference('chat', wanted.session_id, 'client'), operations: standalone.operations },
        },
        network_requests: institution.requests,
        terminal_facts: institution.terminal_facts,
        privacy: await privacyCapture.report(`live:privacy:${wanted.session_id}`),
      });
    } finally { await adminContext.close(); await chatContext.close(); }
  }
} finally { await browser.close(); }

await mkdir(dirname(output), { recursive: true });
await writeFile(output, JSON.stringify({ format: 'skillforge-product-process-browser-evidence', mode: 'live', bundles, profiles }, null, 2), 'utf8');
console.log(`product process browser evidence written: ${bundles.length} session(s), ${profiles.length} profile(s)`);
