import {beforeEach,describe,expect,it,vi} from 'vitest';
import {attachNoeWiseBridge} from './background';
import {pageRequest,publicResponse,siteOrigin} from './protocol';
const popup={id:'extension',url:'chrome-extension://extension/popup.html'};
const sender={id:'extension',frameId:0,documentId:'document1',url:'https://chat.example/session',tab:{id:7,url:'https://chat.example/session'}};
const pairing='wss://service.example/extension/owner#'+'a'.repeat(43);
let listener:any;let storage:Record<string,unknown>;let currentDocument:string;
const request=(id='one',scope='client-A')=>({source:'noewise-page',version:1,request_id:id,operation:'pair',payload:{pairing_url:pairing,connection_request_id:'connection-'+id,identity_scope:scope}});
function send(message:unknown,from:unknown=sender):Promise<any>{return new Promise(resolve=>listener(message,from,resolve));}
const state=()=>send({kind:'noewise-popup',action:'state'},popup);
const act=(action:string,more:Record<string,unknown>={})=>send({kind:'noewise-popup',action,...more},popup);
async function queue(id='one',scope='client-A'){
  const response=send({kind:'noewise-page-bridge',request:request(id,scope)});
  await vi.waitFor(async()=>expect((await state()).pending).toHaveLength(1));
  return {response};
}
beforeEach(()=>{
 storage={};currentDocument='document1';
 vi.stubGlobal('chrome',{runtime:{id:'extension',getURL:(x:string)=>'chrome-extension://extension/'+x,onMessage:{addListener:(x:any)=>{listener=x;}}},
  storage:{local:{get:async()=>storage,set:async(x:any)=>Object.assign(storage,x)}},
  webNavigation:{getFrame:async()=>({documentId:currentDocument,url:sender.url})},action:{setBadgeText:async()=>{}},
  tabs:{query:async()=>[sender.tab],onRemoved:{addListener:()=>{}}}});
});
describe('NoeWise exact-site bridge',()=>{
 it('accepts only secure exact origins and closed probe/pair envelopes',()=>{
  expect(siteOrigin('https://chat.example:443/path')).toBe('https://chat.example');
  expect(siteOrigin('http://127.0.0.1:8000/a')).toBe('http://127.0.0.1:8000');
  expect(siteOrigin('http://localhost.evil/')).toBeNull();expect(siteOrigin('http://192.168.1.2/')).toBeNull();expect(siteOrigin('https://u:p@chat.example')).toBeNull();
  const independent=request();delete (independent.payload as {connection_request_id?:string}).connection_request_id;
  expect(pageRequest(independent)).not.toBeNull();
  expect(pageRequest({...independent,payload:{...independent.payload,connection_request_id:''}})).toBeNull();
  expect(pageRequest(request())).not.toBeNull();expect(pageRequest({...request(),operation:'evaluate'})).toBeNull();
  expect(pageRequest({...request(),extra:true})).toBeNull();expect(pageRequest({...request(),payload:{...request().payload,token:'secret'}})).toBeNull();
  const response=publicResponse(pageRequest(request())!,true,'paired');expect(JSON.stringify(response)).not.toContain(pairing);
  expect(response.version_info.adapter_version).toBe('1.0.1');expect(response.version_info.upstream_commit).toHaveLength(40);
 });
 it('untrusted sites and iframe senders cannot authorize; probe requires trust',async()=>{
  const authorize=vi.fn();attachNoeWiseBridge(authorize);
  expect(await send({kind:'noewise-page-bridge',request:request()})).toEqual({ok:false,code:'site_not_trusted'});
  expect(await send({kind:'noewise-page-bridge',request:request()}, {...sender,frameId:1})).toEqual({ok:false,code:'invalid_request'});
  expect(await send({kind:'noewise-page-bridge',request:{source:'noewise-page',version:1,request_id:'probe',operation:'probe',payload:{}}})).toEqual({ok:false,code:'site_not_trusted'});
  await act('trust',{origin:'https://chat.example'});
  expect(await send({kind:'noewise-page-bridge',request:{source:'noewise-page',version:1,request_id:'probe-trusted',operation:'probe',payload:{}}})).toEqual({ok:true});
  expect(authorize).not.toHaveBeenCalled();
 });
 it('requires popup trust and explicit exact-service confirmation before official authorization',async()=>{
  const authorize=vi.fn(async()=>null);attachNoeWiseBridge(authorize);
  expect(await act('trust',{origin:'https://different.example'})).toEqual({ok:false,code:'page_changed'});
  await act('trust',{origin:'https://chat.example'});const queued=await queue();
  const pending=(await state()).pending[0];expect(pending.origin).toBe('https://chat.example');expect(pending.service).toBe('wss://service.example/extension/owner');
  expect(JSON.stringify(pending)).not.toContain('a'.repeat(43));expect(authorize).not.toHaveBeenCalled();
  expect(await act('confirm',{tab_id:7,request_id:'one'})).toEqual({ok:true});expect(await queued.response).toEqual({ok:true,code:'paired'});
  expect(authorize).toHaveBeenCalledExactlyOnceWith(pairing);
  const next=await queue('two','client-B');expect(authorize).toHaveBeenCalledTimes(1);
  await act('reject',{tab_id:7,request_id:'two'});expect(await next.response).toEqual({ok:false,code:'user_rejected'});
 });
 it('independent device pairing omits connection request and deduplicates its actual request id',async()=>{
  const authorize=vi.fn(async()=>null);attachNoeWiseBridge(authorize);await act('trust',{origin:'https://chat.example'});
  const independent=request('independent');delete (independent.payload as {connection_request_id?:string}).connection_request_id;
  const response=send({kind:'noewise-page-bridge',request:independent});
  await vi.waitFor(async()=>expect((await state()).pending).toHaveLength(1));
  await act('confirm',{tab_id:7,request_id:'independent'});expect(await response).toEqual({ok:true,code:'paired'});
  expect(authorize).toHaveBeenCalledExactlyOnceWith(pairing);
  expect(await send({kind:'noewise-page-bridge',request:independent})).toEqual({ok:false,code:'request_already_submitted'});
 });
 it('navigation and popup spoofing reject without authorization; duplicate request never sends twice',async()=>{
  const authorize=vi.fn(async()=>null);attachNoeWiseBridge(authorize);await act('trust',{origin:'https://chat.example'});
  const queued=await queue();expect(await send({kind:'noewise-popup',action:'confirm',tab_id:7,request_id:'one'},sender)).toEqual({ok:false,code:'invalid_popup'});
  currentDocument='document2';await act('confirm',{tab_id:7,request_id:'one'});expect(await queued.response).toEqual({ok:false,code:'page_changed'});
  currentDocument='document1';expect(await send({kind:'noewise-page-bridge',request:request()})).toEqual({ok:false,code:'request_already_submitted'});expect(authorize).not.toHaveBeenCalled();
 });
 it('concurrent confirmation invokes official authorize once and reports uncertain failure honestly',async()=>{
  let reject!:(reason:unknown)=>void;const authorize=vi.fn(()=>new Promise<null>((_,r)=>{reject=r;}));attachNoeWiseBridge(authorize);
  await act('trust',{origin:'https://chat.example'});const queued=await queue();const confirming=act('confirm',{tab_id:7,request_id:'one'});
  await vi.waitFor(()=>expect(authorize).toHaveBeenCalledTimes(1));expect((await act('confirm',{tab_id:7,request_id:'one'})).ok).toBe(false);
  reject(new Error('network secret must not leak'));expect(await confirming).toEqual({ok:false,code:'pairing_outcome_unknown'});
  expect(await queued.response).toEqual({ok:false,code:'pairing_outcome_unknown'});
 });
});
