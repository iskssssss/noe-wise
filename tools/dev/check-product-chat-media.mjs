import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import zlib from 'node:zlib';

// 两例产品 HTTP 验收；只有显式 --run 才创建会话、上传图片和提交模型输入。
const args={};
for(let i=2;i<process.argv.length;i++) {
 const key=process.argv[i];
 if(['--help','--run'].includes(key)){args[key.slice(2)]=true;continue;}
 if(!['--base','--fixture-file','--output-dir'].includes(key) || !process.argv[i+1])throw new Error('INVALID_OPTION');
 args[key.slice(2)]=process.argv[++i];
}
if(args.help || !args.base || !args['fixture-file']) {
 console.log('Usage: node tools/dev/check-product-chat-media.mjs --base <local gateway URL> --fixture-file <media-fixture.local.json> [--output-dir <evidence directory>] [--run]');
 console.log('Fixture JSON fields: channel_token, agent_id, end_user_id. Supply existing authorized resources; this tool creates no Agent or channel.');
 console.log('Without --run: validates local selectors and generates no resources or model calls. With --run: exactly two submissions (image-only and interleaved).');
 process.exit(args.help?0:2);
}
const base=new URL(args.base);
assert.ok(['localhost','127.0.0.1','[::1]'].includes(base.hostname) && ['http:','https:'].includes(base.protocol) && !base.username && !base.password && !base.search && !base.hash,'LOCAL_BASE_REQUIRED');
// 通用媒体验收只读取调用者明确提供的已授权资源，不依赖已下线的业务种子及准备回执。
console.debug('读取媒体验收夹具: phase=entry');
const fixtureFile=path.resolve(args['fixture-file']);
const dir=path.dirname(fixtureFile);
const fixture=JSON.parse(fs.readFileSync(fixtureFile,'utf8'));
assert.ok(typeof fixture.channel_token==='string' && fixture.channel_token.trim(),'CHANNEL_CREDENTIAL_MISSING');
const channel={token:fixture.channel_token};
const agent=fixture.agent_id;
const endUser=fixture.end_user_id;
assert.ok(typeof agent==='string' && /^\d+$/.test(agent) && typeof endUser==='string' && /^\d+$/.test(endUser),'PRODUCT_SELECTORS_MISSING');
console.debug('读取媒体验收夹具: phase=exit selectors_valid=true');
if(!args.run){console.log(JSON.stringify({status:'ready',cases:['image-only','image-text-image'],agent_id:agent,end_user_id:endUser,model_submissions:0}));process.exit(0);}
const output=path.resolve(args['output-dir'] ?? path.join(dir,'product-media'));
fs.mkdirSync(output,{recursive:true});
const reportFile=path.join(output,'verification.json');
const report=fs.existsSync(reportFile)?JSON.parse(fs.readFileSync(reportFile,'utf8')):{started_at:new Date().toISOString(),cases:[],submissions:0};
function save(){fs.writeFileSync(path.join(output,'verification.json'),JSON.stringify(report,null,2));}
async function request(route,method='GET',body) {
 const headers={'X-Channel-Token':channel.token};
 if(body!==undefined && !(body instanceof FormData))headers['Content-Type']='application/json';
 const response=await fetch(new URL('/api/v1'+route,base),{method,headers,body:body===undefined?undefined:body instanceof FormData?body:JSON.stringify(body),signal:AbortSignal.timeout(25000)});
 assert.ok(response.ok,`HTTP_${response.status}_${route.split('?')[0]}`);
 if(route.startsWith('/chat/sessions/media/content'))return response;
 const envelope=await response.json();assert.equal(envelope.code,'OK',`API_${envelope.code}`);return envelope.data;
}
function crc(bytes){let v=0xffffffff;for(const byte of bytes){v^=byte;for(let i=0;i<8;i++)v=(v>>>1)^((v&1)?0xedb88320:0);}return (v^0xffffffff)>>>0;}
function chunk(type,data){const name=Buffer.from(type),size=Buffer.alloc(4),check=Buffer.alloc(4);size.writeUInt32BE(data.length);check.writeUInt32BE(crc(Buffer.concat([name,data])));return Buffer.concat([size,name,data,check]);}
function png(color){const header=Buffer.alloc(13);header.writeUInt32BE(64,0);header.writeUInt32BE(64,4);header[8]=8;header[9]=2;const rows=Buffer.alloc(64*(1+64*3));for(let y=0;y<64;y++)for(let x=0;x<64;x++)for(let c=0;c<3;c++)rows[y*193+1+x*3+c]=color[c];return Buffer.concat([Buffer.from([137,80,78,71,13,10,26,10]),chunk('IHDR',header),chunk('IDAT',zlib.deflateSync(rows)),chunk('IEND',Buffer.alloc(0))]);}
const fixtures=[{name:'red.png',bytes:png([220,30,30])},{name:'blue.png',bytes:png([30,60,220])}];
for(const image of fixtures)fs.writeFileSync(path.join(output,image.name),image.bytes);
async function upload(session,image){const form=new FormData();form.set('session_id',session);form.set('upload_id',crypto.randomUUID());form.set('file',new Blob([image.bytes],{type:'image/png'}),image.name);const media=await request('/chat/sessions/media/upload','POST',form);assert.equal(media.mime_type,'image/png');assert.equal(media.width,64);assert.equal(media.height,64);const response=await request(`/chat/sessions/media/content?${new URLSearchParams({session_id:session,media_id:media.media_id})}`);assert.equal(response.headers.get('Content-Type')?.split(';')[0],'image/png');assert.ok((await response.arrayBuffer()).byteLength>0);return media;}
try {
 const limits=await request('/chat/sessions/media/limits');assert.ok(limits.mime_types.includes('image/png'));
 for(const key of ['image-only','image-text-image']) {
  let evidence=report.cases.find(item=>item.key===key);
  if(evidence?.assistant_completed && evidence?.history_content_verified)continue;
  if(!evidence){
  const session=(await request('/chat/sessions','POST',{end_user_id:endUser,agent_id:agent})).session_id;
  const red=await upload(session,fixtures[0]);
  const content=key==='image-only'?[{type:'image',media_id:red.media_id}]:[{type:'image',media_id:red.media_id},{type:'text',text:'请按图片出现顺序说明两张图片的主要颜色，各用一个词。'},{type:'image',media_id:(await upload(session,fixtures[1])).media_id}];
  const client=crypto.randomUUID();report.submissions++;save();
  const receipt=await request('/chat','POST',{session_id:session,agent_id:agent,client_message_id:client,context:{end_user_id:endUser},content});
  evidence={key,session_id:session,client_message_id:client,receipt,expected_content:content,history_content_verified:false,assistant_completed:false};report.cases.push(evidence);save();
  }
  const {session_id:session,client_message_id:client,receipt,expected_content:content}=evidence;
  assert.ok(['accepted','queued','running','processing'].includes(receipt.status),'SUBMISSION_NOT_ACCEPTED');
  const deadline=Date.now()+120000;
  do {
   const history=await request(`/chat/history?${new URLSearchParams({session_id:session,limit:'30'})}`);
   const user=history.messages.find(message=>message.role==='user' && (message.message_id===client || message.metadata?.client_message_id===client || message.metadata?.input_id===receipt.input_id));
   if(user){assert.deepEqual(user.metadata.content_parts,content,'ORDERED_HISTORY_CONTENT_CHANGED');evidence.history_content_verified=true;evidence.user_message_id=user.message_id;if(key==='image-only')assert.equal(user.content,'','PURE_IMAGE_TEXT_CHANGED');}
   const answer=history.messages.find(message=>message.role==='assistant' && message.content?.trim() && (message.run_id===receipt.execution_id || message.metadata?.run_id===receipt.execution_id || message.metadata?.root_run_id===receipt.execution_id));
   if(answer){evidence.assistant_completed=true;evidence.answer=answer.content;break;}
   const failed=history.activities?.find(activity=>activity.status==='failed'||activity.status==='cancelled');if(failed)throw new Error('PRODUCT_RUN_FAILED');
   await new Promise(resolve=>setTimeout(resolve,1000));
  } while(Date.now()<deadline);
  assert.ok(evidence.history_content_verified,'USER_HISTORY_MISSING');assert.ok(evidence.assistant_completed,'ASSISTANT_TIMEOUT');save();
 }
 if(report.error){report.diagnostics=[...(report.diagnostics ?? []),{kind:'prior_script_assertion',message:report.error,resolved:true}];delete report.error;}
 report.status='passed';report.completed_at=new Date().toISOString();save();console.log(JSON.stringify({status:'passed',cases:2,model_submissions:report.submissions,evidence_file:path.join(output,'verification.json')}));
} catch(error){report.status='failed';report.error=error instanceof Error?error.message:'UNKNOWN';save();console.error(JSON.stringify({status:'failed',reason:report.error,evidence_file:path.join(output,'verification.json')}));process.exitCode=1;}
