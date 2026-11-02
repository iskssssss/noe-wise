/** 灵知网页桥接的公开信封；这里只传递一次性配对材料，不接受执行命令。 */
export interface PageRequest {
  source: 'noewise-page'; version: 1; request_id: string; operation: 'probe' | 'pair';
  payload: {pairing_url?: string; connection_request_id?: string; identity_scope?: string};
}
export const ADAPTER_VERSION = '1.0.1';
export const UPSTREAM_COMMIT = '3f10983c09fcd443c7420830ab984c9d409954bf';
/** 信任必须精确到 origin，HTTP 仅允许真正的 loopback 主机。 */
export function siteOrigin(input: string): string | null {
  try {
    const url = new URL(input);
    if (url.username || url.password || url.origin === 'null') return null;
    return url.protocol === 'https:' || (url.protocol === 'http:' && ['localhost','127.0.0.1','[::1]'].includes(url.hostname)) ? url.origin : null;
  } catch { return null; }
}
/** 严格封闭字段及长度，防止页面借桥接夹带扩展操作。 */
export function pageRequest(input: unknown): PageRequest | null {
  if (!input || typeof input !== 'object' || Array.isArray(input)) return null;
  const value = input as Record<string, unknown>;
  if (Object.keys(value).some(key => !['source','version','request_id','operation','payload'].includes(key)) ||
      value.source !== 'noewise-page' || value.version !== 1 || typeof value.request_id !== 'string' ||
      !/^[A-Za-z0-9_-]{1,128}$/.test(value.request_id) || !['probe','pair'].includes(String(value.operation))) return null;
  if (!value.payload || typeof value.payload !== 'object' || Array.isArray(value.payload)) return null;
  const payload = value.payload as Record<string, unknown>;
  if (Object.keys(payload).some(key => !['pairing_url','connection_request_id','identity_scope'].includes(key))) return null;
  if (value.operation === 'probe') return Object.keys(payload).length === 0 ? value as unknown as PageRequest : null;
  if (typeof payload.pairing_url !== 'string' || payload.pairing_url.length > 4096 ||
      (payload.connection_request_id !== undefined && (typeof payload.connection_request_id !== 'string' || !/^[A-Za-z0-9_-]{1,128}$/.test(payload.connection_request_id))) ||
      typeof payload.identity_scope !== 'string' || !payload.identity_scope.trim() || payload.identity_scope.length > 256) return null;
  return value as unknown as PageRequest;
}
export function publicResponse(request: PageRequest, ok: boolean, code?: string) {
  return {source:'noewise-extension',version:1,request_id:request.request_id,ok,...(code ? {code}: {}),
    version_info:{adapter_version:ADAPTER_VERSION,upstream_commit:UPSTREAM_COMMIT}};
}
