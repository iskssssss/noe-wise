import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {createRequire} from 'node:module';
import {randomUUID} from 'node:crypto';
const require=createRequire(new URL('../../frontend-admin/package.json',import.meta.url));
const {JSDOM}=require('jsdom');
const html=readFileSync(new URL('./index.html',import.meta.url),'utf8');
const script=readFileSync(new URL('./group-console.js',import.meta.url),'utf8');
const tick=()=>new Promise(resolve=>setImmediate(resolve));
const reply=(status,value)=>({status,ok:status>=200&&status<300,json:async()=>structuredClone(value)});
const ready=data=>({state:'ready',data,error_code:null});
function snapshot() {
  return {
    group:ready({status:'active'}),
    members:ready({items:[{end_user_id:'9007199254740993',role:'manager',status:'active',revision:7,source_revision:8}],next_after:'9007199254740993',has_more:true}),
    agents:ready({items:[{agent_id:'9007199254740995',status:'active',revision:2,source_revision:3}],next_after:'9007199254740995',has_more:true}),
    history:ready({items:[{message_id:'msg-1',message_version:2,role:'assistant',content:'<img src=x onerror=alert(1)>',metadata:{},references:[]}],next_cursor:'cursor-next',has_more:true}),
    memories:ready([{fact_id:'fact-1',scope:'GROUP_MEMBER',subject_end_user_id:'9007199254740993',subject:'客户',predicate:'偏好',revision:3,status:'PENDING_CONFIRM',object_json:JSON.stringify({value:{text:'偏好上午'},conditions:[],temporal:{kind:'stable',from:null,to:null}})}]),
    extraction_policy:ready({enabled:false,extractor_agent_id:null,revision:4}),usage:ready({items:[]}),
    control:ready({control_revision:11,latest_trigger_sequence:20,states:[],blocked_boundaries:[{boundary_ref:'boundary-1',run_id:'run-1',through_trigger_sequence:19}],endpoints:[{endpoint_id:'ep-1',generation:3,status:'RETIRING',connection_state:'UNKNOWN',last_confirmed_at:'2026-10-23',retirement_receipt_ref:'retire-proof',applied_revision:10,fence_revision:10,ledger_ref:'ledger-1'}],latest_command:{command_id:'cmd-1',control_revision:11,status:'PARTIAL',required_participants:[{endpoint_id:'ep-1',generation:3,status:'PENDING'}],durable_acks:[{endpoint_id:'ep-2',applied_boundary_ref:'proof-ack'}],in_flight:[{delivery_id:'flight-1',status:'UNKNOWN'}]}}),
    sender:ready({ledger_ref:'ledger-1',intents:[{intent_id:'intent-1',revision:4,active:true,status:'REJECTED',command_id:'rejected-cmd',boundary_ref:'local-block',scopes:['send'],attempts:[{status:'REJECTED'}]}],deliveries:[],endpoints:[],audit:[]}),
    run:ready({run_id:'run-1',revision:'9007199254740997',status:'WAITING',phase:'WAITING',pending_interactions:[{execution_id:'run-1',run_revision:'9007199254740997',interaction_type:'tool_input',tool_wait:{action_id:'action-1',generation:'4'},card:{type:'tool_input',title:'预约确认',requests:[{id:'confirm_business',message:'确认预约时间',requested_schema:{type:'object',properties:{confirmed:{type:'boolean',title:'确认提交'}},required:['confirmed'],additionalProperties:false}}]}}]}),
    permissions:Object.fromEntries(['write','read','reconcile','release','ack','manage'].map(action=>[action,ready({authorized:true,action})]))
  };
}
async function harness() {
  const dom=new JSDOM(html,{url:'http://localhost:18888/',runScripts:'outside-only'}),w=dom.window,calls=[];
  const mock={snapshot:snapshot(),post:async()=>reply(200,{state:'CONFIRMED',data:{status:'APPLIED'}}),read:null};
  Object.defineProperty(w.crypto,'randomUUID',{value:randomUUID});w.confirm=()=>true;
  w.fetch=async(url,options={})=>{
    calls.push({url,options});
    if(url==='/api/groups/contexts')return reply(200,{items:['one','two'].map(id=>({id,label:id,group_binding_id:'100',operator_end_user_id:'200',agent_id:'9007199254740995',sender_configured:true}))});
    if(options.method==='POST')return mock.post(url,options);
    if(mock.read)return mock.read(url,options);
    return reply(200,mock.snapshot);
  };
  w.eval(script.replace('  initialize();','  window.groupTest={state,refresh,changeContext,edit,submit,reviewLatest,controlDraft,releaseDraft,memberDraft,render,renderEditor};\n  initialize();'));
  await tick();await tick();
  const api=w.groupTest,el=id=>w.document.getElementById(id),field=name=>el('group-editor').querySelector(`[data-field="${name}"]`);
  const click=(label,within=el('group-console'))=>{const b=[...within.querySelectorAll('button')].find(b=>b.textContent===label);assert.ok(b,`missing button ${label}`);b.click();return b;};
  return{dom,w,calls,mock,api,el,field,click};
}
test('renders official control evidence and local intent separately; external text stays inert',async()=>{
  const h=await harness();const text=h.el('group-panels').textContent;
  for(const value of ['PARTIAL','部分参与方已应用','UNKNOWN','RETIRING','retire-proof','proof-ack','flight-1','本地阻断仍有效','REJECTED','local-block'])assert.ok(text.includes(value),value);
  assert.equal(h.el('group-panels').querySelector('img'),null);
  assert.ok(text.includes('<img src=x onerror=alert(1)>'));
  assert.equal(h.w.localStorage.length,0);assert.equal(h.w.sessionStorage.length,0);h.dom.window.close();
});
test('member, Agent and history paging forward exact separate cursors',async()=>{
  const h=await harness();
  for(const [panel,param,value] of [['members','members_after','9007199254740993'],['agents','agents_after','9007199254740995'],['history','history_cursor','cursor-next']]){
    h.click('下一页',h.el('group-panels').querySelector(`[data-panel="${panel}"]`));await tick();
    assert.equal(new URL(h.calls.at(-1).url,'http://localhost').searchParams.get(param),value);
  }
  assert.ok(h.el('group-panels').textContent.includes('不是全群总数'));h.dom.window.close();
});
test('member writes retain big string IDs, never submit configured identity and preserve UNKNOWN original key/body',async()=>{
  const h=await harness();h.mock.post=async()=>reply(202,{state:'UNKNOWN',error_code:'UPSTREAM_TIMEOUT'});
  h.api.memberDraft(h.mock.snapshot.members.data.items[0]);h.field('source_event_id').value='event-1';await h.api.submit(false);
  const first=h.calls.find(c=>c.options.method==='POST');const body=JSON.parse(first.options.body);
  assert.equal(body.end_user_id,'9007199254740993');assert.equal(body.expected_revision,7);
  for(const name of ['operator_end_user_id','group_binding_id','agent_id','tenant_id','principal_id'])assert.equal(Object.hasOwn(body,name),false);
  assert.equal(h.api.state().draft.state,'unknown');assert.equal(h.field('end_user_id').disabled,true);
  h.api.controlDraft();assert.equal(h.api.state().draft.operation,'member');
  await h.api.submit(true);const writes=h.calls.filter(c=>c.options.method==='POST');assert.equal(writes[1].options.body,first.options.body);assert.equal(writes[1].options.headers['Idempotency-Key'],first.options.headers['Idempotency-Key']);
  h.mock.post=async()=>reply(409,{error_code:'CONFLICT'});await h.api.submit(true);assert.equal(h.api.state().draft.state,'unknown');h.dom.window.close();
});
test('CAS conflict retains input and updates revision only after explicit review',async()=>{
  const h=await harness();h.api.memberDraft(h.mock.snapshot.members.data.items[0]);h.field('source_event_id').value='saved-event';h.field('role').value='member';
  h.mock.post=async()=>reply(409,{error_code:'REVISION_CONFLICT'});h.mock.snapshot.members.data.items[0].revision=9;
  await h.api.submit(false);assert.equal(h.api.state().draft.state,'conflict');assert.equal(h.field('source_event_id').value,'saved-event');assert.equal(h.field('expected_revision').value,'7');
  h.api.reviewLatest();assert.equal(h.field('expected_revision').value,'9');assert.equal(h.field('role').value,'member');assert.equal(h.api.state().draft.key,null);h.dom.window.close();
});
test('failed section and request preserve previous data, typed draft and context-scoped receipts',async()=>{
  const h=await harness();h.api.memberDraft(h.mock.snapshot.members.data.items[0]);h.field('source_event_id').value='keep-me';
  h.mock.snapshot.members={state:'failed',data:null,error_code:'TEMPORARY_FAILURE'};await h.api.refresh();
  assert.ok(h.el('group-panels').textContent.includes('9007199254740993'));assert.ok(h.el('group-panels').textContent.includes('保留上次读取数据'));assert.equal(h.field('source_event_id').value,'keep-me');
  h.mock.read=async()=>{throw Error('offline');};await h.api.refresh();assert.ok(h.el('group-status').textContent.includes('保留已显示的数据'));assert.equal(h.field('source_event_id').value,'keep-me');h.dom.window.close();
});
test('permission revocation clears protected old section and failed authorization disables control writes',async()=>{
  const h=await harness();h.click('更正记忆');h.mock.snapshot.members={state:'forbidden',data:null,error_code:'DENIED'};h.mock.snapshot.memories={state:'forbidden',data:null,error_code:'DENIED'};
  h.mock.snapshot.permissions.write={state:'failed',data:null,error_code:'UNAVAILABLE'};await h.api.refresh();
  const members=h.el('group-panels').querySelector('[data-panel="members"]'),memories=h.el('group-panels').querySelector('[data-panel="memories"]');
  assert.equal(members.textContent.includes('9007199254740993'),false);assert.equal(memories.textContent.includes('fact-1'),false);assert.ok(members.textContent.includes('当前身份无权读取'));
  assert.equal([...h.el('group-panels').querySelectorAll('button')].find(b=>b.textContent==='暂停 / 恢复').disabled,true);
  assert.equal([...h.el('group-panels').querySelectorAll('button')].find(b=>b.textContent==='创建本地暂停意图').disabled,true);
  assert.equal(h.el('group-editor').textContent.includes('偏好上午'),false);assert.equal(h.field('text'),null);await h.api.submit(false);assert.equal(h.calls.some(c=>c.options.method==='POST'),false);h.dom.window.close();
});
test('memory correction encodes MemoryContent once and target service Agent is explicit',async()=>{
  const h=await harness();h.click('更正记忆');assert.equal(h.field('text').value,'偏好上午');h.field('text').value='偏好下午';await h.api.submit(false);
  const body=JSON.parse(h.calls.find(c=>c.url.endsWith('/memory-correct')).options.body);assert.equal(typeof body.object_json,'string');assert.deepEqual(JSON.parse(body.object_json),{value:{text:'偏好下午'},conditions:[],temporal:{kind:'stable',from:null,to:null}});
  h.click('添加服务 Agent');h.field('agent_id').value='9007199254740999';h.field('source_event_id').value='agent-event';await h.api.submit(false);
  const agent=JSON.parse(h.calls.find(c=>c.url.endsWith('/agent')).options.body);assert.equal(agent.agent_id,'9007199254740999');assert.equal(agent.expected_revision,0);h.dom.window.close();
});
test('snapshot HTTP403 also removes protected data and hides an existing memory draft',async()=>{
  const h=await harness();h.click('更正记忆');h.mock.read=async()=>reply(403,{error_code:'REVOKED'});await h.api.refresh();
  assert.equal(h.el('group-panels').textContent.includes('fact-1'),false);assert.equal(h.el('group-editor').textContent.includes('偏好上午'),false);assert.ok(h.el('group-status').textContent.includes('已清除受保护'));h.dom.window.close();
});
test('CAS refresh failure cannot rebind a draft to retained old data',async()=>{
  const h=await harness();h.api.memberDraft(h.mock.snapshot.members.data.items[0]);h.field('source_event_id').value='saved-event';
  h.mock.post=async()=>reply(409,{error_code:'REVISION_CONFLICT'});h.mock.read=async()=>{throw Error('offline');};
  await h.api.submit(false);h.api.reviewLatest();assert.equal(h.api.state().draft.state,'conflict');assert.equal(h.field('expected_revision').value,'7');assert.ok(h.el('group-editor').textContent.includes('不能使用保留的旧版本'));h.dom.window.close();
});
test('context switch aborts old read and ignores its late response',async()=>{
  const h=await harness();let finish,signal;
  h.mock.read=async(url,options)=>{if(url.includes('/one/')){signal=options.signal;return new Promise(resolve=>finish=resolve);}return reply(200,h.mock.snapshot);};
  const old=h.api.refresh();h.api.changeContext('two');await tick();assert.equal(signal.aborted,true);
  const stale=snapshot();stale.members.data.items[0].end_user_id='STALE-SECRET';finish(reply(200,stale));await old;
  assert.equal(h.el('group-panels').textContent.includes('STALE-SECRET'),false);h.dom.window.close();
});
test('Run business confirmation binds current wait and original exact revision; input ID matches header',async()=>{
  const h=await harness();h.click('填写当前等待回复');h.field('request_0_confirmed').checked=true;await h.api.submit(false);
  const call=h.calls.find(c=>c.url.endsWith('/run-respond'));const body=JSON.parse(call.options.body);assert.equal(body.input_id,call.options.headers['Idempotency-Key']);assert.equal(body.run_id,'run-1');
  const response=body.hitl_response;assert.equal(response.run_revision,'9007199254740997');assert.deepEqual(response.tool_input.wait,{action_id:'action-1',generation:'4'});
  assert.deepEqual(JSON.parse(response.tool_input.responses.confirm_business.result.canonical_json),{action:'accept',content:{confirmed:true}});assert.equal(response.tool_input.responses.confirm_business.method,'elicitation/create');h.dom.window.close();
});
test('resume and exact delivery release are separate intents with explicit message/version/boundary',async()=>{
  const h=await harness();h.api.controlDraft();h.field('desired_state').value='running';await h.api.submit(false);
  const resumeCall=h.calls.find(c=>c.url.endsWith('/control'));const resume=JSON.parse(resumeCall.options.body);assert.equal(resume.desired_state,'running');assert.equal(Object.hasOwn(resume,'messages'),false);assert.equal(resume.command_id,resumeCall.options.headers['Idempotency-Key']);
  h.api.releaseDraft();h.field('run_id').value='run-1';h.field('message_id').value='msg-1';h.field('message_version').value='2';h.field('delivery_id').value='delivery-1';h.field('boundary_0').checked=true;await h.api.submit(false);
  const release=JSON.parse(h.calls.find(c=>c.url.endsWith('/control-release')).options.body);assert.deepEqual(release.messages,[{message_id:'msg-1',message_version:2,delivery_id:'delivery-1'}]);assert.deepEqual(release.blocked_boundary_refs,['boundary-1']);h.dom.window.close();
});
test('sender intent reconcile and release use original intent/revision and explicit resume receipt',async()=>{
  const h=await harness();h.click('对账原意图');await h.api.submit(false);
  const reconcile=JSON.parse(h.calls.find(c=>c.url.endsWith('/sender-intent/reconcile')).options.body);assert.deepEqual(reconcile,{intent_id:'intent-1',expected_revision:4,action:'query'});
  h.click('凭恢复回执放行本地意图');h.field('resume_command_id').value='resume-applied-1';await h.api.submit(false);
  const release=JSON.parse(h.calls.find(c=>c.url.endsWith('/sender-intent/release')).options.body);assert.deepEqual(release,{intent_id:'intent-1',expected_revision:4,resume_command_id:'resume-applied-1'});h.dom.window.close();
});
