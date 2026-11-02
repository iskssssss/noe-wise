/** 检查真实生成 HTML 的断网交互、窄屏布局与控制台，截图留在指定证据目录。 */
import { createRequire } from 'node:module';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';
import path from 'node:path';
import assert from 'node:assert/strict';
const require = createRequire(new URL('../../frontend-admin/package.json', import.meta.url));
const { chromium } = require('@playwright/test');
const [htmlFile, reactFile, evidenceDirectory] = process.argv.slice(2);
assert(htmlFile && reactFile && evidenceDirectory, 'HTML ReactHTML evidenceDirectory required');
const output=path.resolve(evidenceDirectory);
await mkdir(output,{recursive:true});
const browser=await chromium.launch({headless:true});
const report=[];
try {
  for (const [kind,file] of [['html',htmlFile],['react',reactFile]]) {
    for (const width of [1280,375]) {
      const context=await browser.newContext({viewport:{width,height:900},offline:true});
      const page=await context.newPage();
      const errors=[],external=[];
      page.on('pageerror',e=>errors.push(e.message));
      page.on('request',req=>{if (/^https?:/.test(req.url())) external.push(req.url());});
      await page.goto(pathToFileURL(path.resolve(file)).href);
      if (kind==='html') {
        await page.getByRole('searchbox').fill('备份');
        assert.equal(await page.locator('tbody tr:visible').count(),1);
        assert.equal(await page.locator('#count').textContent(),'1 项需求');
        await page.getByRole('searchbox').fill('');
        assert.equal(await page.locator('tbody tr:visible').count(),2);
      } else {
        const button=page.getByRole('button',{name:'反转月份顺序'});
        await button.click();
        assert.equal(await button.getAttribute('aria-pressed'),'true');
        assert.equal(await page.locator('p[aria-live]').textContent(),'当前首月：三月');
        await button.focus();
        await page.keyboard.press('Enter');
        assert.equal(await button.getAttribute('aria-pressed'),'false');
      }
      assert.deepEqual(errors,[], 'browser runtime errors');
      assert.deepEqual(external,[], 'offline page tried external resources');
      assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'horizontal overflow');
      await page.screenshot({path:path.join(output,`${kind}-${width}.png`),fullPage:true});
      report.push({kind,width,offline:true,interaction:true,keyboard:kind==='react',errors,externalRequests:external});
      await context.close();
    }
  }
} finally {await browser.close();}
await writeFile(path.join(output,'browser.json'),JSON.stringify(report,null,2)+'\n');
console.log(JSON.stringify(report));
