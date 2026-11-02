import {pageRequest, publicResponse, siteOrigin} from './protocol';
/** 只把顶层同源 window.postMessage 转发给后台；扩展结果再次白名单投影。 */
export function attachPageBridge() {
  if (window.top !== window || !siteOrigin(location.href)) return;
  const active = new Set<string>();
  window.addEventListener('message', event => {
    if (event.source !== window || event.origin !== location.origin) return;
    const request = pageRequest(event.data);
    if (!request || active.has(request.request_id) || active.size >= 4) return;
    active.add(request.request_id);
    void chrome.runtime.sendMessage({kind:'noewise-page-bridge',request}).then(result => {
      const ok = result?.ok === true;
      window.postMessage(publicResponse(request,ok,typeof result?.code === 'string' ? result.code : undefined),location.origin);
    }, () => window.postMessage(publicResponse(request,false,'extension_unavailable'),location.origin))
      .finally(() => active.delete(request.request_id));
  });
}
