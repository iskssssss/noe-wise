/* Opt-in integration companion: real HTTP and UI, no response interception or model input. */
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {readFile,writeFile,access} from 'node:fs/promises';
import {resolve,dirname} from 'node:path';
import {fileURLToPath} from 'node:url';

const repo=resolve(dirname(fileURLToPath(import.meta.url)),'../..');
const evidenceArg=process.argv.find(x=>x.startsWith('--evidence='));
assert.ok(evidenceArg,'explicit evidence directory required');
const evidence=resolve(evidenceArg.slice('--evidence='.length));
const require=createRequire(resolve(repo,'frontend-admin/package.json'));
const {chromium,expect}=require('@playwright/test');
const ready=JSON.parse(await readFile(resolve(evidence,'ready.private.json'),'utf8'));
await writeFile(resolve(evidence,'browser-script.mjs'),await readFile(fileURLToPath(import.meta.url)));
assert.equal(new URL(ready.backend_url).hostname,'127.0.0.1');
const save=(name,value)=>writeFile(resolve(evidence,name),JSON.stringify(value,null,2),'utf8');
const exists=async name=>{try{await access(resolve(evidence,name));return true;}catch{return false;}};
async function waitRelease(){
 const deadline=Date.now()+60000;
 while(!(await exists('publication-released.json'))){
  assert.ok(!(await exists('abort.json')),'host aborted');assert.ok(Date.now()<deadline,'publication release timeout');
  await new Promise(r=>setTimeout(r,100));
 }
}
let browser;const requests=[],responses=[],errors=[],responseReads=[],publishedCollections=[];
async function connect(identity){
 const context=await browser.newContext({viewport:{width:1440,height:1000}}),page=await context.newPage();
 context.on('request',r=>{const u=new URL(r.url());if(u.pathname.startsWith('/api/'))requests.push({method:r.method(),path:u.pathname});});
 context.on('response',r=>{const u=new URL(r.url());if(!u.pathname.startsWith('/api/'))return;
  const record={path:u.pathname,status:r.status()};responses.push(record);
  if(r.status()>=400||u.pathname==='/api/v1/chat/sessions/deliverables')responseReads.push((async()=>{
   const body=await r.json();if(r.status()>=400)record.code=body.code;
   if(u.pathname==='/api/v1/chat/sessions/deliverables'&&r.status()===200)publishedCollections.push(body.data);
  })());});
 page.on('pageerror',e=>errors.push({name:e.name,message:e.message.slice(0,160)}));
 await page.addInitScript(({base,identity})=>localStorage.setItem(`skillforge.chat.profile:${base}:${identity.channel_id}`,
  JSON.stringify({client_ref:identity.client_ref,identity_token:identity.identity_token})),{base:ready.backend_url,identity});
 await page.goto(ready.frontend_url,{waitUntil:'domcontentloaded'});
 await page.getByRole('textbox',{name:'服务地址',exact:true}).fill(ready.backend_url);
 await page.getByRole('textbox',{name:'你的称呼',exact:true}).fill('发布状态验收用户');
 await page.getByRole('combobox',{name:'认证方式',exact:true}).click();await page.getByRole('option',{name:'渠道令牌',exact:true}).click();
 await page.getByLabel(/^渠道令牌/).fill(identity.channel_token);
 const catalogResponse=page.waitForResponse(r=>new URL(r.url()).pathname==='/api/v1/chat/agents'&&r.request().method()==='GET');
 const profileResponse=page.waitForResponse(r=>new URL(r.url()).pathname==='/api/v1/chat/client-profile'&&r.request().method()==='POST');
 await page.getByRole('button',{name:'进入会话',exact:true}).click();
 const profile=await profileResponse;assert.equal(profile.status(),200);assert.equal((await profile.json()).data.end_user_id,identity.end_user_id);
 const catalog=(await (await catalogResponse).json()).data;
 assert.ok(catalog.items.some(x=>String(x.id)===identity.agent_id));
 await page.getByRole('textbox',{name:'消息',exact:true}).waitFor();
 await page.getByRole('button',{name:'打开会话导航',exact:true}).click();
 await page.getByRole('combobox',{name:'对话对象',exact:true}).click();
 const agent=catalog.items.find(x=>String(x.id)===identity.agent_id);
 await page.getByRole('option').filter({has:page.getByText(agent.name,{exact:true})}).click();
 // This is an existing fixture session; no session or chat input is created by UI.
 const sessionsResponse=page.waitForResponse(r=>new URL(r.url()).pathname==='/api/v1/chat/sessions'&&r.request().method()==='GET');
 await page.reload({waitUntil:'domcontentloaded'});
 const sessions=(await (await sessionsResponse).json()).data,index=sessions.findIndex(x=>x.session_id===identity.session_id);assert.ok(index>=0);
 await page.getByRole('button',{name:'打开会话导航',exact:true}).click();
 const response=page.waitForResponse(r=>new URL(r.url()).pathname==='/api/v1/chat/history'&&new URL(r.url()).searchParams.get('session_id')===identity.session_id);
 await page.getByRole('navigation',{name:'历史会话',exact:true}).getByRole('button').nth(index).click();
 return {context,page,response:await response};
}
try{
 browser=await chromium.launch({headless:true});
 if(ready.pending){
 const pending=await connect(ready.pending),blocked=await pending.response.json();
 assert.equal(pending.response.status(),503);assert.equal(blocked.code,'TASK_OUTPUT_PUBLICATION_PENDING');
 assert.ok(!JSON.stringify(blocked).includes(ready.pending.answer));
 assert.ok(!(await pending.page.locator('body').innerText()).includes(ready.pending.answer));
 await pending.page.screenshot({path:resolve(evidence,'pending-real-host.png'),fullPage:true});
 const restored=pending.page.waitForResponse(r=>r.status()===200&&new URL(r.url()).pathname==='/api/v1/chat/history'&&new URL(r.url()).searchParams.get('session_id')===ready.pending.session_id);
 void restored.catch(()=>{});
 await save('pending-observed.json',{status:'PASS',http_status:pending.response.status(),code:blocked.code,candidate_visible:false,response_mocking:false});
 await waitRelease();
 const restoredResponse=await restored;assert.equal(restoredResponse.status(),200);
 const restoredBody=await restoredResponse.json();assert.ok(JSON.stringify(restoredBody).includes(ready.pending.answer));
 await expect(pending.page.getByText(ready.pending.answer,{exact:true})).toBeVisible();
 await expect(pending.page.locator('.artifact-cards')).toContainText('已发布 · 可下载');
 await expect.poll(()=>publishedCollections.some(rows=>rows.some(row=>row.run_id===ready.pending.run_id&&row.status==='ready'))).toBe(true);
 await pending.page.screenshot({path:resolve(evidence,'published-recovered-real-host.png'),fullPage:true});
 await pending.context.close();
 }
 const failed=await connect(ready.failed);assert.equal(failed.response.status(),200);
 const failedBody=await failed.response.json();assert.ok(!JSON.stringify(failedBody).includes(ready.failed.forbidden_answer));
 await expect(failed.page.getByText('成果发布未完成，本次交付失败。',{exact:true})).toBeVisible();
 await expect(failed.page.locator('header.conversation-header')).toContainText('失败');
 assert.ok(!(await failed.page.locator('body').innerText()).includes(ready.failed.forbidden_answer));
 await failed.page.getByRole('button',{name:/^成果(?:\s*\d+)?$/}).click();
 await expect(failed.page.getByRole('complementary',{name:'会话成果',exact:true})).toContainText('成果会出现在这里');
 await failed.page.screenshot({path:resolve(evidence,'failed-real-host.png'),fullPage:true});
 await failed.context.close();
 await Promise.all(responseReads);
 assert.deepEqual(responses.filter(r=>r.status>=400 && !(r.path==='/api/v1/chat/history'&&r.status===503&&r.code==='TASK_OUTPUT_PUBLICATION_PENDING')),[],
  'only the expected retryable pending history response may fail');
 assert.deepEqual(requests.filter(r=>r.method!=='GET'&&r.path!=='/api/v1/chat/client-profile'),[]);
 assert.deepEqual(errors,[]);
 await save('browser-result.json',{status:'PASS',pending_http_gate:ready.pending?true:'NOT_RUN',published_recovery:ready.pending?true:'NOT_RUN',recovery:ready.pending?'automatic history GET retry without page reload':'NOT_RUN',failure_scenario:ready.failure_scenario??'legal-abandon',failed_candidate_hidden:true,
  failed_public_message:true,published_collections:publishedCollections,requests,responses,page_errors:errors,response_mocking:false,new_model_calls:0,new_business_inputs:0,
  scope:'real browser direct RANDOM_PORT test host, PostgreSQL/SessionLog/Outbox fixture; publication timing controlled; no deployed gateway or model validation'});
}catch(error){await save('browser-failure.json',{name:error.name,message:error.message,requests,responses,page_errors:errors});throw error;}
finally{await browser?.close();}
