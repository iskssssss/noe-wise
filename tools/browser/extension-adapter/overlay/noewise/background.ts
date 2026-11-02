import {updateRemoteConnection} from '@/transport/remote-authorization';
import {parseRemoteEndpoint} from '@/transport/remote-endpoint';
import {pageRequest, siteOrigin, type PageRequest} from './protocol';
const TRUST_KEY = 'noewise_trusted_origins';
const REQUEST_TTL = 120000;
/** 配对材料仅驻留后台内存；popup只能读取不含credential的待确认摘要。 */
interface Pending {request:PageRequest; origin:string; service:string; tabId:number; documentId:string; reply:(value:unknown)=>void; timer:ReturnType<typeof setTimeout>; busy:boolean;}
/** 上游授权入口由依赖传入，测试不伪造设备凭据。 */
export function attachNoeWiseBridge(authorize = updateRemoteConnection) {
  const pending = new Map<number,Pending>();
  const seen = new Set<string>();
  let confirming = false;
  const popup = (sender:chrome.runtime.MessageSender) => sender.id === chrome.runtime.id && sender.url === chrome.runtime.getURL('popup.html');
  const trusted = async () => {
    const values = await chrome.storage.local.get(TRUST_KEY);
    return Array.isArray(values[TRUST_KEY]) ? (values[TRUST_KEY] as unknown[]).filter((x):x is string => typeof x === 'string' && siteOrigin(x) === x) : [];
  };
  const finish = (item:Pending, ok:boolean, code:string) => {
    clearTimeout(item.timer);pending.delete(item.tabId);item.reply({ok,code});
    void chrome.action.setBadgeText({text:pending.size ? '!':''}).catch(()=>undefined);
  };
  const current = async (item:Pick<Pending,'tabId'|'documentId'|'origin'>) => {
    const frame = await chrome.webNavigation.getFrame({tabId:item.tabId,frameId:0});
    return frame?.documentId === item.documentId && siteOrigin(frame.url) === item.origin;
  };
  chrome.runtime.onMessage.addListener((message,sender,reply) => {
    if (message?.kind === 'noewise-page-bridge') {
      const request = pageRequest(message.request);
      if (!request || sender.id !== chrome.runtime.id || sender.frameId !== 0 || !sender.tab?.id || !sender.documentId || !sender.url) {reply({ok:false,code:'invalid_request'});return false;}
      const origin = siteOrigin(sender.url);
      if (!origin || siteOrigin(sender.tab.url ?? '') !== origin) {reply({ok:false,code:'invalid_origin'});return false;}
      void (async () => {
        if (!(await trusted()).includes(origin)) {reply({ok:false,code:'site_not_trusted'});return;}
        if (request.operation === 'probe') {reply({ok:true});return;}
        const tabId=sender.tab!.id!, documentId=sender.documentId!;
        if (!(await current({tabId,documentId,origin}))) {reply({ok:false,code:'page_changed'});return;}
        let service:string;try {service=parseRemoteEndpoint(request.payload.pairing_url!).url;} catch {reply({ok:false,code:'invalid_pairing_url'});return;}
        const key=`${origin}:${request.payload.connection_request_id ?? request.request_id}`;
        if (pending.has(tabId) || confirming) {reply({ok:false,code:'confirmation_busy'});return;}
        if (seen.has(key)) {reply({ok:false,code:'request_already_submitted'});return;}
        if (seen.size >= 256) {reply({ok:false,code:'request_limit'});return;}
        seen.add(key);
        const item:Pending={request,origin,service,tabId,documentId,reply,busy:false,timer:setTimeout(()=>{if(!item.busy)finish(item,false,'confirmation_expired');},REQUEST_TTL)};
        pending.set(tabId,item);void chrome.action.setBadgeText({text:'!'}).catch(()=>undefined);
      })().catch(()=>reply({ok:false,code:'extension_unavailable'}));
      return true;
    }
    if (message?.kind !== 'noewise-popup') return false;
    if (!popup(sender)) {reply({ok:false,code:'invalid_popup'});return false;}
    void (async () => {
      if (message.action === 'state') {
        const [tab] = await chrome.tabs.query({active:true,currentWindow:true});
        const origin=siteOrigin(tab?.url ?? '');const items=[...pending.values()].map(item=>({request_id:item.request.request_id,origin:item.origin,service:item.service,identity_scope:item.request.payload.identity_scope,tab_id:item.tabId,busy:item.busy}));
        reply({ok:true,origin,trusted:origin ? (await trusted()).includes(origin):false,pending:items});return;
      }
      if (message.action === 'trust' || message.action === 'untrust') {
        const [tab]=await chrome.tabs.query({active:true,currentWindow:true});const origin=siteOrigin(tab?.url ?? '');
        if (!origin || origin !== message.origin) {reply({ok:false,code:'page_changed'});return;}
        const origins=await trusted();await chrome.storage.local.set({[TRUST_KEY]:message.action==='trust' ? [...new Set([...origins,origin])]:origins.filter(x=>x!==origin)});
        if(message.action==='untrust') for(const item of [...pending.values()])if(item.origin===origin&&!item.busy)finish(item,false,'site_not_trusted');
        reply({ok:true});return;
      }
      if (!['confirm','reject'].includes(message.action)) {reply({ok:false,code:'invalid_action'});return;}
      const item=pending.get(message.tab_id);
      if (!item || item.request.request_id!==message.request_id || item.busy) {reply({ok:false,code:'request_missing'});return;}
      if (message.action==='reject') {finish(item,false,'user_rejected');reply({ok:true});return;}
      if (confirming) {reply({ok:false,code:'confirmation_busy'});return;}
      confirming=true;item.busy=true;clearTimeout(item.timer);
      // 即使相同站点曾配对，每次服务或身份请求也必须在popup重新确认。
      try {
        if (!(await current(item)) || !(await trusted()).includes(item.origin)) {finish(item,false,'page_changed');reply({ok:false,code:'page_changed'});return;}
        await authorize(item.request.payload.pairing_url!);finish(item,true,'paired');reply({ok:true});}
      catch {finish(item,false,'pairing_outcome_unknown');reply({ok:false,code:'pairing_outcome_unknown'});}
      finally {confirming=false;}
    })().catch(()=>reply({ok:false,code:'extension_unavailable'}));
    return true;
  });
  chrome.tabs.onRemoved.addListener(tabId=>{const item=pending.get(tabId);if(item&&!item.busy)finish(item,false,'page_closed');});
}
