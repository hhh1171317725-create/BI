const {chromium}=require('playwright');
const http=require('node:http');
const fs=require('node:fs');
const path=require('node:path');
const assert=require('node:assert/strict');
const root=path.resolve(__dirname,'../frontend');
const sample=Array.from({length:105},(_,i)=>({promotion_id:String(10000+i),promotion_name:`测试计划 ${i}`,media_account_name:i%2?'客户-B-01':'客户-A-01',media_account_id:String(900+i%2),stat_cost:100+i,convert_cnt:30,active_register:200,cpa_bid:100+i}));
(async()=>{
 const server=http.createServer((req,res)=>{const file=path.join(root,path.basename(new URL(req.url,'http://localhost').pathname));if(!fs.existsSync(file)){res.writeHead(404);res.end();return}res.setHeader('Content-Type',file.endsWith('.js')?'application/javascript':'text/html;charset=utf-8');res.end(fs.readFileSync(file))});
 await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
 let browser;
 try{
  browser=await chromium.launch({headless:true,channel:'chrome'});const page=await browser.newPage({viewport:{width:1440,height:1000}});const errors=[];page.on('pageerror',e=>errors.push(e.message));
  page.on('dialog',dialog=>dialog.accept());
  const syncCommands=[];let savedRules=[],pricingRevision='';
  let syncStatus={userId:'1',configured:false,enabled:false,state:'stopped',minutes:10,createdDays:4};
  await page.route('**/api/bid-monitor/server-sync**',async route=>{
   const request=route.request(),action=new URL(request.url()).pathname.split('/').pop();
   if(action==='pricing'){
    if(request.method()==='POST'){const input=request.postDataJSON();assert.equal(input.expectedUserId,'1');assert.equal(input.revision,pricingRevision);savedRules=input.rules;pricingRevision='saved';}
    await route.fulfill({json:{userId:'1',revision:pricingRevision,rules:savedRules}});return;
   }
   if(request.method()==='POST'){
    const input=request.postDataJSON();assert.equal(input.expectedUserId,'1');syncCommands.push(action);
    if(action==='start'){assert.equal(input.cookie,'test-session');syncStatus={...syncStatus,configured:true,enabled:true,state:'ready',clientUser:input.clientUser,mainUserId:input.mainUserId};}
    if(action==='stop')syncStatus={...syncStatus,enabled:false,state:'stopped'};
    if(action==='forget')syncStatus={...syncStatus,configured:false,enabled:false,state:'stopped'};
   }
   await route.fulfill({json:syncStatus});
  });
  const queriedPages=[];
  await page.route('**/api/**',route=>{const url=route.request().url();if(url.includes('/server-sync'))return route.fallback();let data={};if(url.endsWith('/session'))data={authenticated:true};else if(url.endsWith('/tool-visibility'))data={bidMonitor:true};else if(url.endsWith('/import'))data={rows:sample};else if(url.endsWith('/page')){const p=route.request().postDataJSON().page;queriedPages.push(p);data={total:350,rows:Array.from({length:p===4?50:100},(_,i)=>({...sample[0],promotion_id:String((p-1)*100+i)}))};}else if(url.endsWith('/snapshot'))data={userId:'1',snapshot:{date:'2026-09-03',updatedAt:'2026-09-03T04:00:00Z',rows:sample.slice(0,2)}};route.fulfill({json:data})});
  await page.goto(`http://127.0.0.1:${server.address().port}/bid-monitor.html`);await page.locator('body.ready').waitFor();
  await page.locator('#startDate').fill('2026-08-01');await page.locator('#endDate').fill('2026-08-02');await page.waitForFunction(()=>!document.querySelector('#pricingFields').disabled);
  for(const [name,keyword,price] of [['任务A','客户-A','21.5'],['任务B','客户-B','30']]){
   await page.locator('#pricingAdd').click();const last=page.locator('#pricingRows tr').last();await last.locator('[data-key=name]').fill(name);await last.locator('[data-key=keyword]').fill(keyword);await last.locator('[data-key=price]').fill(price);
  }
  await page.locator('#pricingSave').click();await page.waitForFunction(()=>document.querySelector('#pricingStatus').textContent==='任务价格已保存');
  await page.locator('#pricingReload').click();await page.waitForFunction(()=>document.querySelector('#pricingStatus').textContent==='任务价格已从服务器读取');
  assert.equal(savedRules.length,2);
  await page.locator('#file').setInputFiles({name:'fixture.xlsx',mimeType:'application/octet-stream',buffer:Buffer.from('fixture')});
  await page.waitForFunction(()=>document.querySelector('#count').textContent==='105 条');assert.equal(await page.locator('#rows tr').count(),50);
  await page.locator('#next').click();assert.match(await page.locator('#pageLabel').textContent(),/2/);
  await page.locator('#search').fill('测试计划 104');assert.equal(await page.locator('#rows tr').count(),1);assert.match(await page.locator('#rows').textContent(),/超过保本线/);
  assert.equal(await page.locator('#rows tr td').nth(13).textContent(),'-1,820.00');
  assert.equal(await page.locator('#rows tr td').nth(14).textContent(),'0.70');
  await page.locator('#search').fill('');await page.locator('#taskFilter').selectOption('task:1');assert.equal(await page.locator('#count').textContent(),'52 条');
  await page.locator('#taskFilter').selectOption('');await page.locator('#margin').fill('10');
  assert.match(await page.locator('#metrics').textContent(),/61,100.00/);assert.match(await page.locator('#metrics').textContent(),/1.13 倍/);
  const download=page.waitForEvent('download');await page.locator('#export').click();assert.match((await download).suggestedFilename(),/出价监测/);
  await page.screenshot({path:path.resolve(__dirname,'../.runtime/bid-monitor-desktop.png'),fullPage:true});
  await page.setViewportSize({width:390,height:844});assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);await page.screenshot({path:path.resolve(__dirname,'../.runtime/bid-monitor-mobile.png'),fullPage:true});
  assert.equal(await page.locator('#syncDetect').count(),0);
  await page.locator('#cookie').fill('test-session');await page.locator('#clientUser').fill('123');await page.locator('#mainUserId').fill('456');
  await page.locator('#syncStart').click();await page.waitForFunction(()=>document.querySelector('#syncStatus').textContent.includes('已开启'));
  assert.equal(await page.locator('#cookie').inputValue(),'');assert.deepEqual(syncCommands,['start']);
  await page.locator('#syncRun').click();await page.waitForFunction(()=>!document.querySelector('#syncRun').disabled);
  assert.equal(await page.locator('#syncStop').isDisabled(),false);
  await page.locator('#syncStop').click();await page.waitForFunction(()=>document.querySelector('#syncStop').disabled&&!document.querySelector('#syncStart').disabled);
  assert.deepEqual(syncCommands,['start','run','stop']);
  await page.locator('#syncForget').click();await page.waitForFunction(()=>document.querySelector('#credentialStatus').textContent==='未保存');
  await page.locator('#syncLoad').click();await page.waitForFunction(()=>document.querySelector('#count').textContent==='2 条');
  assert.match(await page.locator('#source').textContent(),/2026-09-03/);
  await page.locator('#cookie').fill('test-session');await page.locator('#fetch').click();
  await page.waitForFunction(()=>document.querySelector('#count').textContent==='350 条');assert.deepEqual(queriedPages,[1,2,3,4]);
  assert.match(await page.locator('#source').textContent(),/全量查询/);
  assert.deepEqual(errors,[]);console.log('UI PASS: import, formulas, search, pagination, export, mobile width, full pagination, per-task prices, projected profit/ROI, sync start/stop/load, no script errors');
 }finally{if(browser)await browser.close();await new Promise(resolve=>server.close(resolve))}
})().catch(e=>{console.error(e);process.exitCode=1});
