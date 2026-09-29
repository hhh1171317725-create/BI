const {chromium}=require('playwright');
const fs=require('node:fs'),http=require('node:http'),path=require('node:path'),assert=require('node:assert/strict');
const root=path.resolve(__dirname,'../frontend');
(async()=>{
 const server=http.createServer((req,res)=>{const file=path.join(root,path.basename(new URL(req.url,'http://localhost').pathname));if(!fs.existsSync(file)||!fs.statSync(file).isFile()){res.writeHead(404).end();return;}res.setHeader('Content-Type',file.endsWith('.js')?'application/javascript':file.endsWith('.css')?'text/css':'text/html;charset=utf-8');res.end(fs.readFileSync(file));});
 await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));let browser;
 try{
  browser=await chromium.launch({channel:'chrome',headless:true});const page=await browser.newPage({viewport:{width:1440,height:900}}),errors=[];
  page.on('pageerror',error=>errors.push(error.message));
  let releaseReport,toolsRequested=false,fail=false;
  const gate=new Promise(resolve=>releaseReport=resolve);
  await page.route('**/api/**',async route=>{
   const p=new URL(route.request().url()).pathname;
   if(p==='/api/report-visibility'){await gate;if(fail)return route.fulfill({status:502,json:{}});return route.fulfill({json:{dhh:true,jd:false,jdLowActivity:false,adpflux:false}});}
   if(p==='/api/tool-visibility'){toolsRequested=true;return route.fulfill({json:{bidMonitor:true}});}
   return route.fulfill({json:p==='/api/session'?{authenticated:true,user:{id:42,role:'member'}}:p.endsWith('/meta')?{total:0,pinned:0,trash:0,tags:{}}:{items:[],nextOffset:0,hasMore:false}});
  });
  const url=`http://127.0.0.1:${server.address().port}/memos.html`;
  const toolRequest=page.waitForRequest('**/api/tool-visibility');
  await page.goto(url,{waitUntil:'domcontentloaded'});
  await toolRequest;
  assert.equal(toolsRequested,true,'tool permissions must begin while report permissions are pending');releaseReport();
  await page.locator('.app-sidebar-link[data-module=bidMonitor]:visible').waitFor();
  await page.keyboard.press('Control+k');await page.locator('.app-quick-dialog[open]').waitFor();
  await page.getByRole('combobox',{name:'搜索页面'}).fill('京东');assert.equal(await page.locator('.app-quick-option').count(),0);
  await page.getByRole('combobox',{name:'搜索页面'}).fill('预警');assert.equal(await page.locator('.app-quick-option').count(),1);
  assert.match(await page.locator('.app-quick-option').textContent(),/出价监测/);
  await page.getByRole('combobox',{name:'搜索页面'}).fill('');await page.keyboard.press('ArrowDown');
  assert.equal(await page.locator('.app-quick-option[aria-selected=true]').count(),1);
  fs.mkdirSync(path.resolve(__dirname,'../.runtime'),{recursive:true});await page.screenshot({path:path.resolve(__dirname,'../.runtime/quick-navigation.png')});
  await page.keyboard.press('Escape');assert.equal(await page.locator('.app-quick-dialog').isVisible(),false);
  fail=true;await page.reload();await page.locator('#permissionRetry').waitFor();
  assert.match(page.url(),/memos.html$/);assert.equal(await page.locator('.app-sidebar-link[data-module=bidMonitor]').isVisible(),true);
  fail=false;await page.locator('#permissionRetry').click();await page.waitForFunction(()=>!document.querySelector('#permissionNotice'));
  await page.setViewportSize({width:390,height:844});await page.locator('.app-nav-toggle').click();await page.locator('.app-quick-trigger').click();
  assert.equal(await page.locator('.app-sidebar').isVisible(),false);assert.equal(await page.locator('.app-quick-dialog').isVisible(),true);
  await page.keyboard.press('Escape');assert.equal(await page.locator('.app-nav-toggle').evaluate(el=>el===document.activeElement),true);
  // Storage restrictions must not break navigation or the editor.
  await page.addInitScript(()=>Object.defineProperty(window,'sessionStorage',{get(){throw new DOMException('blocked','SecurityError');}}));
  await page.reload();await page.locator('.app-sidebar-link[data-module=bidMonitor]:not([hidden])').waitFor({state:'attached'});
  await page.locator('#newMemo').click();await page.locator('#title').fill('浏览器禁用存储也可编辑');
  await page.locator('#content').fill('test');await page.waitForTimeout(450);
  assert.deepEqual(errors,[]);
  console.log('PASS: concurrent permission requests, authorized palette, keyboard/mobile focus, partial failure/retry, blocked browser storage');
 }finally{await browser?.close();await new Promise(resolve=>server.close(resolve));}
})().catch(error=>{console.error(error);process.exitCode=1;});
