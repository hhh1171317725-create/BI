const {chromium}=require('playwright');
const http=require('node:http');
const fs=require('node:fs');
const path=require('node:path');
const assert=require('node:assert/strict');
const root=path.resolve(__dirname,'../frontend');
const sample=Array.from({length:105},(_,i)=>({promotion_id:String(10000+i),promotion_name:`测试计划 ${i}`,user_name:i%2?'李四':'张三',media_account_name:i%2?'客户-B-01':'客户-A-01',advertiser_id:i%2?'1870049327502852':'1866402186668232',media_account_id:String(900+i%2),stat_cost:100+i,convert_cnt:30,active_register:200,cpa_bid:100+i}));
(async()=>{
 const server=http.createServer((req,res)=>{const file=path.join(root,path.basename(new URL(req.url,'http://localhost').pathname));if(!fs.existsSync(file)){res.writeHead(404);res.end();return}res.setHeader('Content-Type',file.endsWith('.js')?'application/javascript':'text/html;charset=utf-8');res.end(fs.readFileSync(file))});
 await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
 let browser;
 try{
  browser=await chromium.launch({headless:true,channel:'chrome'});const page=await browser.newPage({viewport:{width:1440,height:1000}});const errors=[];page.on('pageerror',e=>errors.push(e.message));
  page.on('dialog',dialog=>dialog.accept());
  let snapshot={date:'2026-09-03',updatedAt:'2026-09-03T04:00:00Z',rows:sample.slice(0,2)},snapshotQueries=0;
  const syncCommands=[],queriedPages=[],preparedQueries=[];let savedRules=[],pricingRevision='';
  let syncStatus={userId:'1',configured:false,enabled:false,state:'stopped',minutes:10,createdDays:4};
  let ding={userId:'1',configured:false,enabled:false,time:'18:00',tasks:[],keyword:'',revision:'',state:'',lastResult:'尚未发送'},dingSent=0;
  await page.route('**/api/bid-monitor/dingtalk**',async route=>{
   const request=route.request(),action=new URL(request.url()).pathname.split('/').pop();
   if(request.method()==='POST'){
    const input=request.postDataJSON();assert.equal(input.expectedUserId,'1');
    if(action==='dingtalk'){
     assert.equal(input.pricingRevision,pricingRevision);assert.equal(input.time,'18:00');assert.deepEqual(input.tasks,['任务A']);
     ding={...ding,configured:true,enabled:input.enabled,time:input.time,tasks:input.tasks,keyword:input.keyword,revision:'d1',state:'saved'};
    }else if(action==='preview'){await route.fulfill({json:{userId:'1',messages:[{task:'任务A',text:"【HS TOP5】\n1. 消耗 5952.87 | 回传 5.99% | 出价 22.00 | 出价利润率 12.10%\n优化师 张三 | 账户ID 1846208955218314 | 计划ID 7680247482655588371\n【增量HS TOP5】\n1. 消耗 1367.00 | 回传 7.29% | 出价 5.83 | 出价利润率 14.95%\n优化师 李四 | 账户ID 1866402186668232 | 计划ID 7680973376249348115"}]}});return;}
    else if(action==='send'){dingSent++;ding={...ding,state:'sent',lastResult:'已发送 1 条消息，包含 1 个任务'};}
   }
   await route.fulfill({json:{...ding,availableTasks:savedRules.map(r=>r.name),pricingRevision}});
  });
  await page.route('**/api/bid-monitor/server-sync**',async route=>{
   const request=route.request(),action=new URL(request.url()).pathname.split('/').pop();
   if(action==='pricing'){
    if(request.method()==='POST'){const input=request.postDataJSON();assert.equal(input.expectedUserId,'1');assert.equal(input.revision,pricingRevision);savedRules=input.rules;pricingRevision='saved';}
    await route.fulfill({json:{userId:'1',revision:pricingRevision,rules:savedRules}});return;
   }
   if(action==='prepare-query'){
    const input=request.postDataJSON();assert.equal(input.expectedUserId,'1');preparedQueries.push(input);
    assert.ok(syncStatus.configured||input.cookie==='test-session');
    syncStatus={...syncStatus,configured:true,enabled:true,state:'ready',minutes:10,clientUser:'123',mainUserId:'456'};
    await route.fulfill({json:{...syncStatus,queryRevision:'owned-revision'}});return;
   }
   if(action==='query-snapshot'){
    const input=request.postDataJSON();assert.equal(input.expectedUserId,'1');assert.equal(input.queryRevision,'owned-revision');snapshotQueries++;
    snapshot={date:input.startDate,updatedAt:new Date(Date.now()+snapshotQueries).toISOString(),rows:Array.from({length:400},(_,i)=>({...sample[0],promotion_id:String(i)})),selection:'spend_desc_top_400'};
    syncStatus={...syncStatus,lastSuccess:snapshot.updatedAt};
    await route.fulfill({json:{userId:'1',snapshot}});return;
   }
   if(action==='page'){
    const input=request.postDataJSON();assert.equal(input.expectedUserId,'1');assert.equal(input.queryRevision,'owned-revision');
    assert.equal(input.cookie,undefined);assert.equal(input.clientUser,undefined);
    const p=input.page;queriedPages.push(p);
    await route.fulfill({json:{total:335367,rows:Array.from({length:100},(_,i)=>({...sample[0],promotion_id:String((p-1)*100+i)}))}});return;
   }
   if(request.method()==='POST'){
    const input=request.postDataJSON();assert.equal(input.expectedUserId,'1');syncCommands.push(action);
    if(action==='start'){assert.equal(input.cookie,'test-session');syncStatus={...syncStatus,configured:true,enabled:true,state:'ready',clientUser:input.clientUser,mainUserId:input.mainUserId};}
    if(action==='stop')syncStatus={...syncStatus,enabled:false,state:'stopped'};
    if(action==='forget')syncStatus={...syncStatus,configured:false,enabled:false,state:'stopped'};
   }
   await route.fulfill({json:syncStatus});
  });
  await page.route('**/api/**',route=>{const url=route.request().url();if(url.includes('/server-sync')||url.includes('/dingtalk'))return route.fallback();let data={};if(url.endsWith('/session'))data={authenticated:true};else if(url.endsWith('/tool-visibility'))data={bidMonitor:true};else if(url.endsWith('/import'))data={rows:sample};else if(url.endsWith('/page')){const p=route.request().postDataJSON().page;queriedPages.push(p);data={total:335367,rows:Array.from({length:100},(_,i)=>({...sample[0],promotion_id:String((p-1)*100+i)}))};}else if(url.endsWith('/snapshot'))data={userId:'1',snapshot};route.fulfill({json:data})});
  await page.goto(`http://127.0.0.1:${server.address().port}/bid-monitor.html`);await page.locator('body.ready').waitFor();
  await page.locator('#startDate').fill('2026-08-01');await page.locator('#endDate').fill('2026-08-02');await page.waitForFunction(()=>!document.querySelector('#pricingFields').disabled);
  for(const [name,keyword,price] of [['任务A','客户-A','21.5'],['任务B','客户-B','30']]){
   await page.locator('#pricingAdd').click();const last=page.locator('#pricingRows tr').last();await last.locator('[data-key=name]').fill(name);await last.locator('[data-key=keyword]').fill(keyword);await last.locator('[data-key=price]').fill(price);
  }
  await page.locator('#pricingSave').click();await page.waitForFunction(()=>document.querySelector('#pricingStatus').textContent==='任务价格已保存');
  await page.locator('#pricingReload').click();await page.waitForFunction(()=>document.querySelector('#pricingStatus').textContent==='任务价格已从服务器读取');
  assert.equal(savedRules.length,2);
  await page.locator('#dingReload').click();await page.locator('#dingTasks input[value="任务A"]').waitFor();
  assert.equal(await page.locator('#dingTime').inputValue(),'18:00');
  await page.locator('#dingWebhook').fill('https://oapi.dingtalk.com/robot/send?access_token=test-only-token');
  await page.locator('#dingKeyword').fill('TOP5');await page.locator('#dingTasks input[value="任务A"]').check();
  await page.locator('#dingSave').click();await page.waitForFunction(()=>document.querySelector('#dingStatus').textContent.includes('机器人已加密保存'));
  assert.equal(await page.locator('#dingWebhook').inputValue(),'');assert.equal(ding.enabled,true);
  await page.locator('#dingPreviewButton').click();await page.locator('#dingPreview').waitFor({state:'visible'});
  assert.match(await page.locator('#dingPreview').textContent(),/^【HS TOP5】/);assert.equal(dingSent,0);
  assert.doesNotMatch(await page.locator('#dingPreview').textContent(),/\n[ \t]*\n/);
  const copied=await page.locator('#dingPreview').evaluate(el=>{const range=document.createRange();range.selectNodeContents(el);const selection=window.getSelection();selection.removeAllRanges();selection.addRange(range);const text=selection.toString();selection.removeAllRanges();return text;});
  assert.match(copied,/账户ID 1846208955218314/);assert.match(copied,/计划ID 7680247482655588371/);
  assert.equal(await page.locator('#dingPreview').evaluate(el=>getComputedStyle(el).whiteSpace),'pre-wrap');
  await page.locator('#dingPreview').screenshot({path:path.resolve(__dirname,'../.runtime/ding-copyable-desktop.png')});
  await page.setViewportSize({width:390,height:844});
  assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
  assert.equal(await page.locator('#dingPreview').evaluate(el=>el.scrollWidth<=el.clientWidth),true);
  await page.locator('#dingPreview').screenshot({path:path.resolve(__dirname,'../.runtime/ding-copyable-mobile.png')});
  await page.setViewportSize({width:1440,height:1000});
  await page.locator('#dingSend').click();await page.waitForFunction(()=>document.querySelector('#dingStatus').textContent.includes('已发送 1 条消息'));
  assert.equal(dingSent,1);
  await page.locator('#file').setInputFiles({name:'fixture.xlsx',mimeType:'application/octet-stream',buffer:Buffer.from('fixture')});
  await page.waitForFunction(()=>document.querySelector('#count').textContent==='105 条');assert.equal(await page.locator('#rows tr').count(),50);
  await page.locator('#next').click();assert.match(await page.locator('#pageLabel').textContent(),/2/);
  await page.locator('#search').fill('测试计划 104');assert.equal(await page.locator('#rows tr').count(),1);
  assert.equal(await page.locator('#rows tr td').count(),13);
  assert.equal(await page.locator('#rows tr td').nth(10).textContent(),'21.078');
  assert.equal(await page.locator('#rows tr td').nth(11).textContent(),'143.33');
  assert.equal(await page.locator('.table-wrap:not(.pricing-table) th').nth(10).textContent(),'预估 ROI');
  assert.equal(await page.locator('.table-wrap:not(.pricing-table) th').nth(11).textContent(),'盈亏线出价');
  assert.equal(await page.locator('#rows tr td').nth(2).textContent(),'张三');
  assert.equal(await page.locator('#rows tr td').nth(1).locator('small').textContent(),'1866402186668232');
  await page.locator('#search').fill('李四');assert.equal(await page.locator('#count').textContent(),'52 条');
  await page.locator('#search').fill('测试计划 104');
  assert.equal(await page.locator('#rows tr td').nth(12).textContent(),'-42.33%');
  assert.equal(await page.locator('#rows tr td').nth(12).getAttribute('title'),'盈亏线出价：143.33');
  await page.locator('#search').fill('');await page.locator('#taskFilter').selectOption('task:1');assert.equal(await page.locator('#count').textContent(),'52 条');
  await page.locator('#taskFilter').selectOption('');
  assert.equal(await page.locator('#metrics .metric').count(),2);assert.match(await page.locator('main').textContent(),/预估 ROI/);
  assert.doesNotMatch(await page.locator('main').textContent(),/预估利润|注册成本|理论保本价|目标出价上限|实际消耗利润|目标毛利率/);
  const download=page.waitForEvent('download');await page.locator('#export').click();const exported=await download;assert.match(exported.suggestedFilename(),/出价监测/);
  const csv=fs.readFileSync(await exported.path(),'utf8');assert.match(csv,/1866402186668232/);assert.doesNotMatch(csv,/"900"/);assert.match(csv,/优化师/);assert.match(csv,/张三/);assert.match(csv,/预估ROI/);assert.match(csv,/预估赔付金额/);assert.match(csv,/出价利润率/);assert.match(csv,/现金消耗/);assert.doesNotMatch(csv,/预估利润|注册成本|理论保本价/);
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
  await page.waitForFunction(()=>document.querySelector('#count').textContent==='400 条');assert.deepEqual(queriedPages,[1,2,3,4]);
  assert.match(await page.locator('#source').textContent(),/消耗降序前 400 条/);
  assert.equal(await page.locator('#cookie').inputValue(),'');assert.equal(preparedQueries[0].cookie,'test-session');
  await page.locator('#fetch').click();await page.waitForFunction(()=>!document.querySelector('#fetch').disabled);
  assert.deepEqual(queriedPages,[1,2,3,4,1,2,3,4]);assert.equal(preparedQueries[1].cookie,'');
  // Historical queries and imports are not replaced by automatic snapshot polling.
  await page.evaluate(()=>syncLoad());assert.equal(await page.locator('#count').textContent(),'400 条');
  const dates=await page.evaluate(()=>{const end=today(),d=new Date(end+'T00:00:00Z');d.setUTCDate(d.getUTCDate()-3);return{end,start:d.toISOString().slice(0,10)}});
  await page.locator('#startDate').fill(dates.end);await page.locator('#endDate').fill(dates.end);
  await page.locator('#createdStart').fill(dates.start);await page.locator('#createdEnd').fill(dates.end);
  await page.locator('#fetch').click();await page.waitForFunction(()=>!document.querySelector('#fetch').disabled);
  await page.evaluate(()=>syncLoad());assert.equal(await page.locator('#count').textContent(),'400 条');
  assert.equal(snapshotQueries,1);assert.equal(await page.locator('#rows tr td').nth(2).textContent(),'张三');
  assert.equal(await page.locator('#rows tr td').nth(1).locator('small').textContent(),'1866402186668232');
  await page.reload();
  await page.waitForFunction(()=>document.querySelector('#count').textContent==='400 条');
  assert.equal(await page.locator('#rows tr td').nth(2).textContent(),'张三');
  assert.equal(await page.locator('#rows tr td').nth(1).locator('small').textContent(),'1866402186668232');await page.waitForFunction(()=>document.querySelector('#credentialStatus').textContent==='已加密保存');
  assert.equal(await page.locator('#cookie').inputValue(),'');assert.equal(await page.locator('#clientUser').inputValue(),'123');
  await page.locator('#fetch').click();await page.waitForFunction(()=>document.querySelector('#count').textContent==='400 条');
  assert.equal(preparedQueries.at(-1).cookie,'');
  await page.evaluate(()=>receive([
   {promotion_id:'grant',promotion_name:'grant plan',media_account_name:'客户-A',stat_cost:150,convert_cnt:10,active_register:20,cpa_bid:10},
   {promotion_id:'no-grant',promotion_name:'six conversions',media_account_name:'客户-B',stat_cost:600,convert_cnt:6,active_register:10,cpa_bid:10}
  ],'fixture',{start:'2026-08-01',end:'2026-08-01'}));
  assert.equal(await page.locator('#metrics strong').first().textContent(),'78.08%');
  assert.equal(await page.locator('#metrics strong').nth(1).textContent(),'1.760');
  await page.locator('#search').fill('grant plan');
  assert.equal(await page.locator('#rows tr td').nth(10).textContent(),'3.200');
  assert.equal(await page.locator('#rows tr td').nth(10).getAttribute('title'),'预估赔付金额：50.00');
  assert.equal(await page.locator('#rows tr td').nth(11).textContent(),'43.00');
  assert.equal(await page.locator('#rows tr td').nth(2).textContent(),'--');
  await page.locator('#search').fill('');await page.locator('#sort').selectOption('bidProfitRate');
  assert.match(await page.locator('#rows tr').first().textContent(),/six conversions/);
  await page.evaluate(()=>receive([{promotion_id:'zero',media_account_name:'客户-A',stat_cost:1,convert_cnt:0,active_register:20,cpa_bid:5}],'fixture',{start:'2026-08-01',end:'2026-08-01'}));
  assert.equal(await page.locator('#rows tr td').nth(10).textContent(),'430.000');
  assert.equal(await page.locator('#rows tr td').nth(11).textContent(),'--');
  assert.equal(await page.locator('#rows tr td').nth(12).textContent(),'--');
  assert.equal(await page.locator('#metrics strong').first().textContent(),'--');
  await page.locator('#filter').selectOption('available');assert.equal(await page.locator('#count').textContent(),'0 条');
  await page.locator('#filter').selectOption('unavailable');assert.equal(await page.locator('#count').textContent(),'1 条');
  assert.deepEqual(errors,[]);console.log('UI PASS: optimizer column/search/export, estimated ROI, bid profit rate, export, task prices, mobile width, top-400, encrypted credential reuse, 10-minute schedule, daily snapshot following, historical data preservation');
 }finally{if(browser)await browser.close();await new Promise(resolve=>server.close(resolve))}
})().catch(e=>{console.error(e);process.exitCode=1});
