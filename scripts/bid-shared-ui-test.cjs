const {chromium}=require('playwright');
const http=require('node:http'),fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
(async()=>{
 const root=path.resolve(__dirname,'../frontend');
 const server=http.createServer((req,res)=>{
  const file=path.join(root,new URL(req.url,'http://localhost').pathname.replace(/^\//,''));
  if(!fs.existsSync(file)||!fs.statSync(file).isFile()){res.writeHead(404);res.end();return;}
  res.setHeader('Content-Type',file.endsWith('.js')?'application/javascript':file.endsWith('.css')?'text/css':file.endsWith('.png')?'image/png':'text/html;charset=utf-8');res.end(fs.readFileSync(file));
 });
 await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));let browser;
 try{
  browser=await chromium.launch({channel:'chrome',headless:true});const page=await browser.newPage({viewport:{width:1440,height:1000}});
  const errors=[],forbidden=[],petRequests=[];page.on('pageerror',e=>errors.push(e.message));
  let stamp='2026-09-08T01:00:00Z',price=2,revision='p1';
  const rows=[{promotion_id:'12345',promotion_name:'共享计划',advertiser_id:'111',media_account_name:'客户A',user_name:'张三',stat_cost:100,convert_cnt:10,active_register:100,cpa_bid:10}];
  await page.route('**/api/**',route=>{
   const url=new URL(route.request().url());let data;
   if(url.pathname==='/api/session')data={authenticated:true};
   else if(url.pathname==='/api/tool-visibility')data={bidMonitor:true};
   else if(url.pathname==='/api/pet/config')data={configured:false,canManage:false};
   else if(url.pathname==='/api/pet/chat'){petRequests.push(route.request().postDataJSON());data={mode:'local',reply:'已读取出价监测，消耗100元',scope:'出价监测 · 当前筛选结果'};}
   else if(url.pathname==='/api/bid-monitor/shared-report')data={userId:'2',canManage:false,sharedOwnerId:'1',sharedOwnerName:'管理员',version:'1:'+stamp,
    snapshot:url.searchParams.get('after')==='1:'+stamp?null:{date:'2026-09-08',updatedAt:stamp,rows},status:{enabled:true},rules:[{name:'共享任务',keyword:'客户',price}],pricingRevision:revision};
   else if(url.pathname==='/api/bid-monitor/gap')data={anchor:'2026-09-08',start:'2026-09-05',end:'2026-09-07',basis:'测试口径',accounts:{111:{gap:.8,validDays:3,days:[]}}};
   else{forbidden.push(url.pathname);return route.fulfill({status:403,json:{error:'member read-only'}});}
   return route.fulfill({json:data});
  });
  await page.goto(`http://127.0.0.1:${server.address().port}/bid-monitor.html#pricing-settings`);
  await page.waitForFunction(()=>document.querySelector('#rows tr td:nth-child(16)')?.textContent==='1.60');
  assert.equal(new URL(page.url()).hash,'#report');
  assert.equal(await page.locator('#sync-settings').isVisible(),false);
  assert.equal(await page.locator('#pricing-settings').isVisible(),false);
  assert.equal(await page.locator('#dingtalk-settings').isVisible(),false);
  assert.equal(await page.locator('#fetch').isVisible(),false);
  assert.match(await page.locator('#sharedReportNotice').textContent(),/查看、筛选和导出/);
  await page.locator('.data-pet-toggle').focus();await page.keyboard.press('Enter');
  await page.locator('.data-pet-input').fill('读取页面内容');await page.locator('.data-pet-send').click();
  await page.waitForFunction(()=>!document.querySelector('.data-pet-send').disabled);
  assert.equal(petRequests[0].context.mode,'bid');assert.equal(petRequests[0].context.summary['消耗'],100);
  assert.equal(petRequests[0].context.plans[0]['计划'],'共享计划');
  assert.equal(petRequests[0].context.summary['佣金'],160);
  assert.doesNotMatch(JSON.stringify(petRequests[0].context),/cookie|apiKey|webhook|secret/i);
  await page.locator('.data-pet-toggle').focus();await page.keyboard.press('Enter');
  await page.locator('#viewMode').selectOption('accounts');
  assert.equal(await page.locator('#tableHead th').nth(0).textContent(),'账户名称');
  assert.equal(await page.locator('#tableHead th').nth(1).textContent(),'账户ID');
  assert.match(await page.locator('#count').textContent(),/^1 个账户（1 条计划）$/);
  await page.locator('.account-drill-link').click();
  assert.equal(await page.locator('#accountDrill').isVisible(),true);
  assert.equal(await page.locator('#count').textContent(),'1 条');
  await page.locator('#accountDrillBack').click();
  await page.locator('#viewMode').selectOption('plans');
  await page.locator('#openBidColumns').click();await page.locator('[data-column-key="optimizer"]').uncheck();
  await page.locator('#bidColumnsDialog').getByRole('button',{name:'取消',exact:true}).click();
  assert.equal(await page.locator('#tableHead [data-sort-key="optimizer"]').count(),1);
  await page.locator('#openBidFilters').click();await page.locator('#draft-deepCpaBidMin').fill('100');await page.keyboard.press('Escape');
  assert.equal(await page.locator('#deepCpaBidMin').inputValue(),'');
  await page.locator('#search').fill('不存在');assert.equal(await page.locator('#count').textContent(),'0 条');
  await page.locator('#search').fill('张三');assert.equal(await page.locator('#count').textContent(),'1 条');
  const download=page.waitForEvent('download');await page.locator('#export').click();
  const csv=fs.readFileSync(await (await download).path(),'utf8');assert.match(csv,/共享计划/);assert.match(csv,/"1.6"/);
  price=3;revision='p2';stamp='2026-09-08T01:10:00Z';
  await page.getByRole('button',{name:'读取最新快照',exact:true}).click();
  await page.waitForFunction(()=>document.querySelector('#rows tr td:nth-child(16)')?.textContent==='2.40');
  assert.match(await page.locator('#summaryCards [data-summary="cost"]').textContent(),/100.00/);
  await page.locator('#taskFilterButton').click();await page.locator('#bidMultiFilterDropdown input[value="task:0"]').check();await page.locator('#bidMultiFilterDropdown .multi-filter-done').click();
  await page.locator('#optimizerFilterButton').click();await page.locator('#bidMultiFilterDropdown input[value="张三"]').check();await page.locator('#bidMultiFilterDropdown .multi-filter-done').click();
  assert.equal(await page.locator('#count').textContent(),'1 条');
  await page.locator('.plan-detail-link').click();
  assert.match(await page.locator('#bidPlanDetail').textContent(),/共享任务/);
  assert.match(await page.locator('#bidPlanDetail').textContent(),/现金利润 = 佣金 − 现金消耗140.00/);
  await page.keyboard.press('Escape');
  await page.locator('#clearReportFilters').click();
  assert.deepEqual(forbidden,[]);assert.deepEqual(errors,[]);
  await page.setViewportSize({width:390,height:844});
  assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
  fs.mkdirSync(path.resolve(__dirname,'../.runtime'),{recursive:true});
  await page.screenshot({path:path.resolve(__dirname,'../.runtime/bid-shared-member.png')});
  console.log('PASS: member sees admin snapshot/pricing/gap, cannot see configuration, can filter/export/refresh, no private API calls');
 }finally{if(browser)await browser.close();await new Promise(resolve=>server.close(resolve));}
})().catch(e=>{console.error(e);process.exitCode=1});
