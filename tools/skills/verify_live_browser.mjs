/** 验收本地机构聊天历史、成果下载与隔离HTML预览；凭据仅存在测试进程。 */
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { createRequire } from 'node:module';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { pathToFileURL } from 'node:url';

const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../..');
const require=createRequire(path.join(root,'frontend-admin/package.json'));
const { chromium }=require('@playwright/test');
const evidence=path.join(root,'.agent-work-state/general-skill-adoption/live');
const command="import importlib.util,pathlib,json; s=importlib.util.spec_from_file_location('v',pathlib.Path('tools/skills/verify_live_skills.py')); v=importlib.util.module_from_spec(s); s.loader.exec_module(v); d=v.client(); print(json.dumps({'token':d.headers['institution']['Authorization'].removeprefix('Bearer '),'user':d.api('institution','/auth/me'),'sessions':d.api('institution','/chat/sessions')}))";
const auth=JSON.parse(execFileSync('python',['-X','utf8','-B','-c',command],{cwd:root,encoding:'utf8',stdio:['ignore','pipe','pipe']}));
const cases=process.argv.slice(2);
assert(cases.length,'provide completed case names');
await mkdir(path.join(evidence,'browser'),{recursive:true});
const browser=await chromium.launch({headless:true});
const report=[];
let latestPage;
try {
  for (const name of cases) {
    assert(/^[a-z-]+$/.test(name));
    const receipt=JSON.parse(await readFile(path.join(evidence,name+'-receipt.json'),'utf8'));
    const expectedFiles=JSON.parse(await readFile(path.join(evidence,name+'-downloads.json'),'utf8'));
    async function verifyBrowserDownload(download,expected) {
      assert.equal(await download.failure(),null);
      const data=await readFile(await download.path());
      assert.equal(data.length,expected.size);
      assert.equal(createHash('sha256').update(data).digest('hex'),expected.sha256);
      await download.saveAs(path.join(evidence,'browser',`${name}-r${expected.revision}-`+path.basename(expected.path)));
    }
    if(name==='web') {
      const files=JSON.parse(await readFile(path.join(evidence,'web-downloads.json'),'utf8'));
      for(const width of [1280,375]) {
        const offline=await browser.newContext({viewport:{width,height:900},offline:true});
        const authored=await offline.newPage();
        latestPage=authored;
        const errors=[],requests=[];
        authored.on('pageerror',e=>errors.push(e.message));
        authored.on('request',r=>{if(/^https?:/.test(r.url()))requests.push(r.url());});
        await authored.goto(pathToFileURL(files[0].path).href);
        assert.equal(await authored.locator('#items .item:visible').count(),4);
        await authored.getByRole('searchbox').fill('网页');
        assert.equal(await authored.locator('#items .item:visible').count(),1);
        await authored.getByRole('searchbox').fill('不存在999');
        assert.equal(await authored.locator('#items .item:visible').count(),0);
        await authored.getByRole('button',{name:'清空筛选'}).click();
        assert.equal(await authored.locator('#items .item:visible').count(),4);
        await authored.getByRole('searchbox').fill('文档');
        await authored.keyboard.press('Escape');
        assert.equal(await authored.locator('#items .item:visible').count(),4);
        assert(await authored.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));
        assert.deepEqual(errors,[]);
        assert.deepEqual(requests,[]);
        await authored.screenshot({path:path.join(evidence,'browser',`web-offline-${width}.png`),fullPage:true});
        report.push({case:'web-offline',width,filter:true,clear:true,keyboard:true,offline:true,errors,externalRequests:requests});
        await offline.close();
      }
    }
    const sid=receipt.request.session_id;
    if(name==='channel-web') {
      const channel=JSON.parse(await readFile(path.join(evidence,'channel.local.json'),'utf8'));
      const context=await browser.newContext({viewport:{width:1280,height:1000},acceptDownloads:true});
      await context.addInitScript(({channel,sid})=>{
        if(location.origin!=='http://127.0.0.1:3200')return;
        const address='http://127.0.0.1:8080';
        localStorage.setItem(`skillforge.chat.profile:${address}:${channel.id}`,channel.client_ref);
        sessionStorage.setItem('skillforge.chat.connection',JSON.stringify({address,mode:'token',token:channel.channel_token,name:'通用Skill独立体验'}));
        sessionStorage.setItem('skillforge.chat.selection',JSON.stringify({clientRef:channel.client_ref,target:'agent:'+channel.agent_id,session:sid}));
      },{channel,sid});
      const page=await context.newPage();
      latestPage=page;
      const errors=[];
      page.on('pageerror',e=>errors.push(e.message));
      await page.goto('http://127.0.0.1:3200');
      await page.locator('article.message.user').filter({hasText:receipt.request.content[0].text}).waitFor({timeout:90000});
      await page.locator('article.message.assistant').last().waitFor({timeout:90000});
      await page.getByText('正在读取会话…',{exact:true}).waitFor({state:'hidden',timeout:90000});
      await page.getByRole('button',{name:'会话工具',exact:true}).click();
      await page.getByRole('tab',{name:'成果',exact:true}).click();
      const [download]=await Promise.all([page.waitForEvent('download'),page.getByRole('button',{name:'下载',exact:true}).first().click()]);
      await verifyBrowserDownload(download,expectedFiles[0]);
      await page.getByRole('button',{name:'预览',exact:true}).first().click();
      const frame=page.locator('iframe[title="成果预览"]');
      await frame.waitFor();
      assert.equal(await frame.getAttribute('sandbox'),'allow-scripts');
      assert.equal(new URL(await frame.getAttribute('src')).port,'8091');
      await page.frameLocator('iframe[title="成果预览"]').getByRole('heading',{name:'渠道技能验收',exact:true}).waitFor();
      await page.screenshot({path:path.join(evidence,'browser','channel-web.png'),fullPage:true});
      assert.deepEqual(errors,[]);
      report.push({case:name,session_id:sid,independent_chat:true,history:true,download:true,download_digest:true,separate_preview_origin:true,sandbox:'allow-scripts',errors});
      await context.close();
      continue;
    }
    const session=auth.sessions.find(s=>s.session_id===sid);
    assert(session,'real session absent from institution catalog');
    const context=await browser.newContext({viewport:{width:1440,height:1000},acceptDownloads:true});
    await context.addInitScript(({token,user})=>{
      if(location.origin!=='http://127.0.0.1:3000')return;
      localStorage.setItem('skillforge.access_token',token);
      localStorage.setItem('skillforge.user',JSON.stringify(user));
    },{token:auth.token,user:auth.user});
    const page=await context.newPage();
    latestPage=page;
    const errors=[];
    page.on('pageerror',error=>errors.push(error.message));
    await page.goto('http://127.0.0.1:3000/admin/chat');
    const label=session.summary||sid;
    await page.locator('.toolbar').first().locator('.el-select').nth(2).click();
    await page.getByRole('option',{name:label,exact:true}).click();
    await page.getByText('connected',{exact:true}).waitFor();
    await page.getByRole('tab',{name:'成果文件',exact:true}).click();
    await page.getByRole('button',{name:'下载',exact:true}).first().waitFor();
    const catalog=JSON.parse(await readFile(path.join(evidence,name+'-deliverables.json'),'utf8'));
    let targetRow;
    for(const expected of expectedFiles) {
      const item=catalog.find(x=>x.id===expected.revision_id);
      assert(item,'formal revision missing from saved catalog');
      const row=page.getByRole('row').filter({hasText:item.name})
        .filter({has:page.getByRole('cell',{name:String(expected.revision),exact:true})});
      assert.equal(await row.count(),1,'select the exact logical result and revision');
      const fileRow=row.locator('.toolbar').filter({has:page.getByText(path.basename(expected.path),{exact:true})});
      const [download]=await Promise.all([page.waitForEvent('download'),fileRow.getByRole('button',{name:'下载',exact:true}).click()]);
      await verifyBrowserDownload(download,expected);
      targetRow=row;
    }
    let preview;
    if (name==='web') {
      await targetRow.getByRole('button',{name:'预览',exact:true}).first().click();
      const frame=page.locator('iframe[title="成果预览"]');
      await frame.waitFor();
      assert.equal(await frame.getAttribute('sandbox'),'allow-scripts');
      const source=await frame.getAttribute('src');
      assert.equal(new URL(source).port,'8091');
      const body=page.frameLocator('iframe[title="成果预览"]');
      await body.getByRole('heading',{name:'通用技能试点报告',exact:true}).waitFor();
      const external=[];
      page.on('request',req=>{if(new URL(req.url()).hostname!=='127.0.0.1'&&new URL(req.url()).hostname!=='localhost') external.push(req.url());});
      const input=body.locator('input').first();
      await input.fill('网页');
      await body.getByRole('button',{name:/清空/}).click();
      assert.equal(await input.inputValue(),'');
      await input.focus();
      await page.keyboard.type('文档');
      await page.keyboard.press('Escape');
      assert.equal(await input.inputValue(),'');
      preview={separate_origin:true,sandbox:'allow-scripts',filter_and_clear:true,keyboard:true,externalRequests:external};
      assert.deepEqual(external,[]);
    }
    await page.screenshot({path:path.join(evidence,'browser',name+'.png'),fullPage:true});
    assert.deepEqual(errors,[],'runtime page errors');
    report.push({case:name,session_id:sid,history:true,download:true,download_digest:true,verified_files:expectedFiles.length,revision_ids:[...new Set(expectedFiles.map(f=>f.revision_id))],preview,errors});
    await context.close();
  }
} catch(error) {
  if(latestPage) {
    await latestPage.screenshot({path:path.join(evidence,'browser/failure.png'),fullPage:true});
    await writeFile(path.join(evidence,'browser/failure.json'),JSON.stringify({url:latestPage.url(),text:await latestPage.locator('body').innerText(),error:error.message},null,2));
  }
  throw error;
} finally { await browser.close(); }
await writeFile(path.join(evidence,'browser',cases.join('-')+'-report.json'),JSON.stringify(report,null,2)+'\n');
console.log(JSON.stringify(report));
