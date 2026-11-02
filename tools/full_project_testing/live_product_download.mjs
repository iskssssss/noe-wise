import { mkdir, readFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { resolve } from 'node:path';

/** Observe a real UI download; never include signed URLs or raw errors in evidence. */
export async function verifyProductDownload({ page, click, revisionId, file, evidenceDir, timeout = 15_000 }) {
  const identity = { revision_id: revisionId, file_id: file.file_id };
  const observed = { browser_managed_file_verified: false };
  const result = (status, reason, extra = {}) => ({ status, ...identity, reason, ...observed, ...extra });
  if (!Number.isSafeInteger(file.size) || file.size < 0 || !/^[a-f0-9]{64}$/i.test(file.sha256 || ''))
    return result('unknown', 'formal_file_metadata_unavailable');
  const observers = [];
  const observe = (event, matches) => new Promise(resolveObserved => {
    let timer;
    const cleanup = () => { clearTimeout(timer); page.off(event, listener); };
    const listener = value => {
      if (!matches(value)) return;
      cleanup(); resolveObserved({ value });
    };
    observers.push(() => { cleanup(); resolveObserved({}); });
    page.on(event, listener);
    timer = setTimeout(() => { cleanup(); resolveObserved({}); }, timeout);
  });
  const downloadReady = observe('download', () => true);
  const ticketReady = observe('response', response => {
    const request = response.request();
    if (request.method() !== 'POST' || !new URL(response.url()).pathname.endsWith('/chat/deliverables/download-ticket')) return false;
    try {
      const body = request.postDataJSON();
      return body.id === revisionId && body.file_id === file.file_id;
    } catch { return false; }
  });
  try {
    try { await click(); } catch { return result('unknown', 'download_control_not_activated'); }
    const { value: ticket } = await ticketReady;
    if (!ticket) return result('unknown', 'matching_download_ticket_not_observed');
    observed.ticket_status = ticket.status();
    if (!ticket.ok()) return result('failed', 'download_ticket_rejected', { ticket_status: ticket.status() });
    let ticketUrl;
    try {
      const body = await ticket.json();
      if (body.code !== 'OK' || typeof body.data?.url !== 'string')
        return result('failed', 'download_ticket_invalid_response');
      ticketUrl = new URL(body.data.url, ticket.url()).href;
    } catch { return result('failed', 'download_ticket_invalid_response'); }
    const { value: download } = await downloadReady;
    if (!download) return result('unknown', 'browser_download_not_observed');
    observed.suggested_filename = download.suggestedFilename();
    try {
      if (new URL(download.url()).href !== ticketUrl)
        return result('failed', 'download_ticket_url_mismatch', { ticket_status: ticket.status() });
      if (await download.failure()) return result('failed', 'browser_download_failed');
      const path = await download.path();
      if (!path) return result('failed', 'browser_download_path_unavailable');
      const bytes = await readFile(path);
      const sha256 = createHash('sha256').update(bytes).digest('hex');
      if (bytes.length !== file.size || sha256 !== file.sha256.toLowerCase())
        return result('failed', 'download_bytes_mismatch', { size: bytes.length, sha256 });
      observed.browser_managed_file_verified = true;
      await mkdir(evidenceDir, { recursive: true });
      // Only a content digest becomes a filename; user-controlled names cannot escape the evidence directory.
      const savedPath = resolve(evidenceDir, `${sha256}.download`);
      await download.saveAs(savedPath);
      const saved = await readFile(savedPath);
      if (saved.length !== file.size || createHash('sha256').update(saved).digest('hex') !== sha256)
        return result('failed', 'saved_download_bytes_mismatch');
      return result('passed', 'download_saved_and_verified', { size: bytes.length, sha256, saved_path: savedPath,
        suggested_filename: download.suggestedFilename(), ticket_status: ticket.status(), browser_managed_file_verified: true });
    } catch { return result('failed', 'download_read_or_save_failed'); }
  } finally { for (const cleanup of observers) cleanup(); }
}
