const {chromium}=require('playwright');
const http=require('node:http'),fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');

const root=path.resolve(__dirname,'../frontend'),output=path.resolve(__dirname,'../.runtime');
const today=new Intl.DateTimeFormat('sv-SE',{timeZone:'Asia/Shanghai'}).format(new Date());
const daysAgo=days=>new Date(new Date(today+'T00:00:00Z').getTime()-days*86400000).toISOString().slice(0,10);
const stamp=minute=>today+`T01:${String(minute).padStart(2,'0')}:00Z`;
const rows=Array.from({length:35},(_,i)=>({promotion_id:String(10000+i),promotion_name:`保留计划 ${i+1}`,
 source_platform:'byte',platform_text:'字节',advertiser_id:'111',media_account_id:'111',media_account_name:'客户A',
 user_name:'张三',promotion_create_time:today+' 08:00:00',stat_cost:100+i,convert_cnt:10,active_register:100,cpa_bid:10}));
const rules=[{name:'任务A',keyword:'客户A',price:2}];
const alertVisible=page=>page.locator('#bidDataAlert').isVisible();
const notices=page=>page.locator('#bidDataFailureToast').evaluate(el=>Number(el.dataset.notificationCount||0));
const rawData=page=>page.evaluate(()=>JSON.stringify(raw));
const waitAlert=page=>page.waitForFunction(()=>!document.getElementById('bidDataAlert').hidden);
const waitClear=page=>page.waitForFunction(()=>document.getElementById('bidDataAlert').hidden);

async function fixture(browser,url,{member=false,viewerId=member?'2':'1',ownerId='1'}={}){
 const page=await browser.newPage({viewport:{width:1440,height:1000}}),errors=[],forbidden=[],requests=[];
 const state={ownerId,viewerId,member,snapshot:{date:today,updatedAt:stamp(0),rows,selection:'created_window_all'},
  status:{userId:ownerId,configured:true,enabled:true,state:'ready',lastSuccess:stamp(0),minutes:10,createdDays:4,clientUser:'123',mainUserId:'456'},
  queryMode:'ok',commandFailed:false,gapFailed:false,historyFailed:false,sharedFailed:false,statusFailed:false,snapshotFailed:false,sharedReads:[],commands:[]};
 page.on('pageerror',error=>errors.push(error.message));
 await page.route('**/pet-loader.js*',route=>route.fulfill({body:''}));
 await page.route('**/api/**',async route=>{
  const request=route.request(),requestUrl=new URL(request.url()),pathname=requestUrl.pathname;
  requests.push(pathname);
  const fail=error=>route.fulfill({status:502,json:{error}});
  let data;
  if(pathname==='/api/session')data={authenticated:true};
  else if(pathname==='/api/tool-visibility')data={bidMonitor:true};
  else if(pathname==='/api/report-visibility')data={dhh:true,jd:true,jdLowActivity:true,bidMonitor:true};
  else if(pathname==='/api/bid-monitor/shared-report'){
   if(state.sharedFailed)return fail('共享报表连接失败');
   const version=state.ownerId+':'+state.snapshot.updatedAt,unchanged=requestUrl.searchParams.get('after')===version;
   state.sharedReads.push({after:requestUrl.searchParams.get('after'),snapshotNull:unchanged,owner:state.ownerId});
   data={userId:state.viewerId,sharedOwnerId:state.ownerId,sharedOwnerName:'来源管理员',canManage:!state.member,version,
    snapshot:unchanged?null:state.snapshot,status:{...state.status,snapshotUpdatedAt:state.snapshot.updatedAt},
    rules,pricingRevision:'p1',strategies:[],strategyRevision:'s1'};
  }else if(pathname==='/api/bid-monitor/gap/revision')data={sourceRevision:'daily-v1'};
  else if(pathname==='/api/bid-monitor/gap'){
   if(state.gapFailed)return fail('日报任务与 gap 连接失败');
   data={sourceRevision:'daily-v1',anchor:requestUrl.searchParams.get('endDate'),start:daysAgo(4),end:daysAgo(2),priceDate:daysAgo(2),
    accounts:{111:{gap:.8,validDays:3,days:[]}},basis:'测试口径'};
   if(state.gapHold){state.heldGap={route,data};return;}
  }else if(pathname==='/api/bid-monitor/history/conversions')data={startDate:daysAgo(4),endDate:daysAgo(1),rows:[]};
  else if(pathname==='/api/bid-monitor/history'){
   if(state.historyFailed)return fail('历史归档连接失败');
   data={startDate:requestUrl.searchParams.get('startDate'),endDate:requestUrl.searchParams.get('endDate'),count:rows.length,
    rows:rows.map(row=>({...row,report_date:requestUrl.searchParams.get('endDate')}))};
  }else if(state.member){
   forbidden.push(pathname);return route.fulfill({status:403,json:{error:'member read-only'}});
  }else if(pathname==='/api/bid-monitor/snapshot'){
   if(state.snapshotFailed)return fail('快照连接失败');
   data={userId:state.ownerId,snapshot:state.snapshot};
  }else if(pathname==='/api/bid-monitor/server-sync'){
   if(state.statusFailed)return fail('同步状态连接失败');
   data=state.status;
  }else if(pathname==='/api/bid-monitor/server-sync/pricing')data={userId:state.ownerId,rules,revision:'p1'};
  else if(pathname==='/api/bid-monitor/server-sync/strategies')data={userId:state.ownerId,strategies:[],revision:'s1'};
  else if(pathname==='/api/bid-monitor/dingtalk')data={userId:state.ownerId,configured:false,enabled:false,time:'18:00',tasks:[],availableTasks:['任务A'],pricingRevision:'p1',revision:'d1'};
  else if(pathname==='/api/bid-monitor/server-sync/prepare-query'){
   assert.equal(request.postDataJSON().expectedUserId,state.ownerId);
   data={...state.status,queryRevision:'fixture-revision'};
  }else if(pathname==='/api/bid-monitor/server-sync/page'){
   const input=request.postDataJSON();assert.equal(input.expectedUserId,state.ownerId);assert.equal(input.queryRevision,'fixture-revision');
   if(state.queryMode==='fail')return fail('计划分页读取失败，保留原有结果');
   if(state.queryMode==='hold'){state.heldPage=route;return;}
   data=input.platform==='gdt'?{total:0,rows:[]}:{total:rows.length,rows};
  }else if(pathname==='/api/bid-monitor/server-sync/run'){
   state.commands.push(request.postDataJSON());state.status={...state.status,state:'waiting'};data=state.status;
  }else if(pathname==='/api/bid-monitor/server-sync/stop'){
   if(state.commandFailed)return fail('同步配置保存失败');
   state.status={...state.status,state:'stopped',enabled:false};data=state.status;
  }else{forbidden.push(pathname);return route.fulfill({status:404,json:{error:'unmocked '+pathname}});}
  return route.fulfill({json:data});
 });
 await page.goto(url+'/bid-monitor.html#report');
 await page.waitForFunction(()=>raw.length===35&&gapData?.accounts&&window.BidDataAlerts&&!window.BidDataAlerts.state.failed);
 return{page,state,errors,forbidden,requests};
}

async function checkLayout(page,label){
 await page.locator('#bidDataFailureToast button').click();
 await page.evaluate(()=>scrollTo(0,0));
 await page.screenshot({path:path.join(output,`bid-data-alerts-${label}-desktop.png`)});
 await page.evaluate(()=>scrollTo(0,450));
 const desktop=await page.locator('#bidDataAlert').evaluate(el=>({top:el.getBoundingClientRect().top,expected:parseFloat(getComputedStyle(el).top),position:getComputedStyle(el).position,scrollY}));
 assert.equal(desktop.position,'sticky');assert.ok(desktop.scrollY>0);assert.ok(Math.abs(desktop.top-desktop.expected)<2,'Desktop failure banner remains below the navigation');
 await page.setViewportSize({width:390,height:844});
 await page.evaluate(()=>scrollTo(0,450));
 await page.waitForFunction(()=>Math.abs(document.getElementById('bidDataAlert').getBoundingClientRect().top-parseFloat(getComputedStyle(document.getElementById('bidDataAlert')).top))<2);
 assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true,'Failure UI must fit a mobile viewport');
 assert.equal(await alertVisible(page),true);
 await page.screenshot({path:path.join(output,`bid-data-alerts-${label}-mobile.png`)});
 await page.setViewportSize({width:1440,height:1000});await page.evaluate(()=>scrollTo(0,0));
}

async function adminScenarios(browser,url){
 const {page,state,errors,forbidden,requests}=await fixture(browser,url);
 try{
  const preserved=await rawData(page);
  state.status={...state.status,state:'retrying',error:'本次查询失败，已保留旧数据；10 分钟后自动重试',failureAt:stamp(1)};
  await waitAlert(page); // Exercise the actual background polling timer.
  assert.match(await page.locator('#bidDataAlertTitle').innerText(),/计划同步失败/);
  assert.match(await page.locator('#bidDataAlertMeta').innerText(),/已保留当前数据|最近成功/);
  assert.equal(await notices(page),1);assert.equal(await rawData(page),preserved);
  assert.match(await page.title(),/^⚠/);assert.equal(await page.locator('#bidDataStatus').getAttribute('data-state'),'error');
  assert.equal(await page.locator('#bidDataAlert').evaluate(el=>el.closest('section')),null);
  await page.evaluate(async()=>{await syncLoad(true);await syncRefresh();await syncRefresh();});
  assert.equal(await alertVisible(page),true,'Reading an old snapshot must not recover a failed pull');
  assert.equal(await notices(page),1,'Repeated status reads must not repeat the same incident notification');
  const legacyFailureDate=await page.evaluate(value=>new Date(value).toLocaleString('zh-CN'),stamp(1));
  assert.ok((await page.locator('#bidDataAlertMeta').innerText()).includes('最近失败：'+legacyFailureDate),'Older backends still display the incident failure timestamp');
  const latestFailure=stamp(1).replace(':00Z',':30Z'),failureReason='字节 · 第 2 页：上游 HTTP 503（已尝试 3 次）';
  const failureDate=await page.evaluate(value=>new Date(value).toLocaleString('zh-CN'),latestFailure);
  state.status={...state.status,failureReason,lastFailureAt:latestFailure,failureCount:3};
  await page.evaluate(()=>syncRefresh());
  const diagnosticMeta=await page.locator('#bidDataAlertMeta').innerText();
  assert.ok(diagnosticMeta.includes('最近失败：'+failureDate),'Banner displays the latest failed attempt, not just the incident start');
  assert.match(diagnosticMeta,/连续失败：3次/);
  assert.ok((await page.locator('#syncStatus').innerText()).includes('最近失败：'+failureDate));
  assert.match(await page.locator('#syncStatus').innerText(),/连续失败：3次/);
  assert.equal((await page.locator('#syncStatus').innerText()).split(failureReason).length-1,1,'Configuration displays the separate safe failure reason');
  assert.equal((await page.locator('#bidDataAlertReason').innerText()).split(failureReason).length-1,1,'The banner displays the separate safe failure reason');
  assert.equal(diagnosticMeta.includes(failureReason),false,'Metadata must not repeat the error category');
  assert.equal(await notices(page),1,'More failed attempts within the same incident update diagnostics without notifying again');
  state.status={...state.status,error:state.status.error+'；原因：'+failureReason};
  await page.evaluate(()=>syncRefresh());
  assert.equal((await page.locator('#syncStatus').innerText()).split(failureReason).length-1,1,'Configuration does not duplicate a reason already included in the error');
  assert.equal((await page.locator('#bidDataAlertReason').innerText()).split(failureReason).length-1,1,'The banner does not duplicate a reason already included in the error');
  await page.evaluate(value=>window.BidDataAlerts.syncStatus({...syncState,state:'ready',error:'',failureAt:null,lastSuccess:value,snapshotUpdatedAt:value}),stamp(1).replace(':00Z',':15Z'));
  assert.equal(await alertVisible(page),true,'A committed timestamp after the first failure but before the latest failure cannot clear the incident');
  await page.evaluate(value=>syncShow({...syncState,snapshotUpdatedAt:value}),state.snapshot.updatedAt);
  const unchangedWork=await page.evaluate(async()=>{
   let mutations=0,events=0;const observe=new MutationObserver(records=>{mutations+=records.length;}),count=()=>events++;
   observe.observe(document.getElementById('bidDataAlert'),{subtree:true,childList:true,characterData:true,attributes:true});
   document.addEventListener('bid:data-status',count);
   for(let i=0;i<20;i++)syncShow({...syncState});
   await new Promise(resolve=>setTimeout(resolve,0));observe.disconnect();document.removeEventListener('bid:data-status',count);
   return{mutations,events};
  });
  assert.deepEqual(unchangedWork,{mutations:0,events:0},'Unchanged sync polls must not rewrite the alert or publish redundant state');
  await checkLayout(page,'admin');

  state.status={...state.status,state:'paused',enabled:false,error:'保存的凭据无法解密，请更新登录凭据后重新启用',failureReason:'保存的凭据无法解密'};
  await page.evaluate(()=>syncRefresh());
  await page.waitForFunction(()=>document.getElementById('bidDataAlertRetry').textContent==='检查同步配置');
  await page.locator('#bidDataAlertRetry').click();
  assert.equal(new URL(page.url()).hash,'#sync-settings');assert.equal(await alertVisible(page),true);
  await page.locator('#cookie').waitFor({state:'visible'});
  assert.equal(await page.locator('#cookie').evaluate(el=>el.closest('details').open),true);
  assert.equal(await notices(page),1);
  state.status={...state.status,state:'retrying',enabled:true};await page.evaluate(()=>syncRefresh());
  await page.locator('#bidDataAlertRetry').click();
  await page.waitForFunction(()=>document.getElementById('bidDataAlertRetry').disabled);
  assert.equal(state.commands.length,1);assert.equal(state.commands[0].expectedUserId,'1');
  assert.equal(await alertVisible(page),true,'A queued retry is not a successful data commit');
  state.status={...state.status,state:'running'};await page.evaluate(()=>syncRefresh());
  assert.equal(await alertVisible(page),true);assert.equal(await notices(page),1);
  assert.match(await page.locator('#bidDataAlertMeta').innerText(),/连续失败：3次/,'Starting a retry must not increment the failure count');
  assert.match(await page.locator('#bidDataAlertReason').innerText(),/保存的凭据无法解密/,'Running retries retain the previous error until a commit');
  state.snapshot={...state.snapshot,updatedAt:stamp(2)};
  state.status={...state.status,state:'ready',lastSuccess:stamp(2),error:'',failureReason:'',lastFailureAt:null,failureCount:0};delete state.status.failureAt;
  await page.evaluate(()=>syncRefresh());await waitClear(page);
  assert.doesNotMatch(await page.title(),/^⚠/);
  assert.doesNotMatch(await page.locator('#syncStatus').innerText(),/最近失败：|连续失败：/,'A successful commit clears failure diagnostics');
  state.status={...state.status,state:'retrying',error:'新一轮计划同步失败',failureAt:stamp(3),lastFailureAt:stamp(3),failureCount:1};
  await page.evaluate(()=>syncRefresh());await waitAlert(page);assert.equal(await notices(page),2);
  state.status={...state.status,state:'ready',lastSuccess:stamp(1),error:''};delete state.status.failureAt;
  await page.evaluate(()=>syncRefresh());
  assert.equal(await alertVisible(page),true,'A stale ready response cannot clear a newer failure incident');
  assert.equal(await notices(page),2);
  state.snapshot={...state.snapshot,updatedAt:stamp(4)};
  state.status={...state.status,state:'ready',lastSuccess:stamp(4),error:'',failureReason:'',lastFailureAt:null,failureCount:0};delete state.status.failureAt;
  await page.evaluate(()=>syncRefresh());await waitClear(page);
  console.log('PASS: admin background failure, durable banner, old-snapshot protection, one notice per incident, paused configuration and committed recovery');

  // Non-live manual page reads have local errors even when the shared server schedule is healthy.
  await page.locator('.section-nav a[href="#sync-settings"]').click();
  await page.locator('#startDate').fill(daysAgo(1));await page.locator('#endDate').fill(daysAgo(1));
  state.queryMode='fail';const beforeQuery=await rawData(page),beforeQueryNotices=await notices(page);
  await page.locator('#fetch').click();await waitAlert(page);await page.waitForFunction(()=>!busy);
  assert.match(await page.locator('#bidDataAlertTitle').innerText(),/计划查询失败/);
  assert.equal(new URL(page.url()).hash,'#sync-settings');assert.equal(await rawData(page),beforeQuery);
  assert.equal(await notices(page),beforeQueryNotices+1);
  state.queryMode='ok';await page.locator('#fetch').click();await page.waitForFunction(()=>!busy);await waitClear(page);
  state.queryMode='hold';const beforeCancel=await rawData(page),cancelNotices=await notices(page);
  const pending=page.waitForRequest(request=>new URL(request.url()).pathname==='/api/bid-monitor/server-sync/page');
  await page.locator('#fetch').click();await pending;await page.locator('#cancel').click();
  await page.waitForFunction(()=>!busy);if(state.heldPage)await state.heldPage.abort().catch(()=>{});
  assert.equal(await notices(page),cancelNotices,'Explicit cancellation must not notify as a failed pull');
  assert.equal(await alertVisible(page),false);assert.equal(await rawData(page),beforeCancel);
  assert.match(await page.locator('#message').innerText(),/已取消查询/);state.queryMode='ok';
  console.log('PASS: manual query failure is visible in the configuration tab, keeps the report, and explicit AbortError cancellation does not notify');

  await page.locator('.section-nav a[href="#report"]').click();
  state.gapFailed=true;const beforeGap=await rawData(page);
  await page.evaluate(()=>loadGap());await waitAlert(page);
  assert.match(await page.locator('#bidDataAlertTitle').innerText(),/gap 更新失败/);
  assert.equal(await rawData(page),beforeGap);assert.equal(await page.evaluate(()=>gapData.accounts['111'].gap),.8);
  assert.match(await page.locator('#gapStatus').innerText(),/保留上次关联结果/);
  state.gapFailed=false;state.gapHold=true;
  const retryRequest=page.waitForRequest(request=>new URL(request.url()).pathname==='/api/bid-monitor/gap');
  const gapReads=requests.filter(value=>value==='/api/bid-monitor/gap').length;
  await page.locator('#bidDataAlertRetry').click();await retryRequest;
  await page.evaluate(()=>syncRefresh());
  assert.equal(await page.locator('#bidDataAlertRetry').isDisabled(),true,'Fresh status polls must not unlock a retry that is still reading data');
  await page.locator('#bidDataAlertRetry').evaluate(button=>button.click());
  assert.equal(requests.filter(value=>value==='/api/bid-monitor/gap').length,gapReads+1,'Repeated clicks cannot duplicate the pending retry');
  state.gapHold=false;await state.heldGap.route.fulfill({json:state.heldGap.data});await waitClear(page);
  const beforeHistory=await rawData(page);state.historyFailed=true;
  await page.evaluate(({start,end})=>{document.getElementById('historyStart').value=start;document.getElementById('historyEnd').value=end;},{start:daysAgo(2),end:daysAgo(1)});
  await page.evaluate(()=>loadHistory());await waitAlert(page);
  assert.match(await page.locator('#bidDataAlertTitle').innerText(),/历史数据读取失败/);assert.equal(await rawData(page),beforeHistory);
  state.historyFailed=false;await page.locator('#bidDataAlertRetry').click();await waitClear(page);
  await page.waitForFunction(()=>historyMode&&historyFinancialReady);
  assert.equal(await page.evaluate(()=>raw.length),35);
  console.log('PASS: gap and historical read failures preserve current data and recover through their real retry actions');

  const archiveNotices=await notices(page);
  state.status={...state.status,historyState:'retrying',historyError:'昨日历史归档失败，10 分钟后自动重试',historyFailureAt:stamp(5),historyLastSuccess:daysAgo(2)+'T16:30:00Z'};
  await page.evaluate(()=>syncRefresh());await waitAlert(page);
  assert.match(await page.locator('#bidDataAlertTitle').innerText(),/每日归档失败/);
  assert.equal(await notices(page),archiveNotices+1);
  state.status={...state.status,historyState:'running'};await page.evaluate(()=>syncRefresh());
  assert.equal(await alertVisible(page),true);assert.equal(await notices(page),archiveNotices+1);
  state.status={...state.status,historyState:'ready',historyError:'',historyLastSuccess:stamp(6),historyLastDate:daysAgo(1)};delete state.status.historyFailureAt;
  await page.evaluate(()=>syncRefresh());await waitClear(page);
  console.log('PASS: daily archive failure remains visible through retries and clears only after a successful archive commit');
  state.statusFailed=true;await page.evaluate(()=>syncRefresh());await waitAlert(page);
  assert.match(await page.locator('#bidDataAlertTitle').innerText(),/同步状态读取失败/);
  state.commandFailed=true;await page.locator('.section-nav a[href="#sync-settings"]').click();await page.locator('#syncStop').click();
  await page.waitForFunction(()=>document.getElementById('syncStatus').textContent==='同步配置保存失败');
  assert.equal(await alertVisible(page),true,'Restoring cached controls after a failed command must not clear a real status-read failure');
  state.statusFailed=false;state.commandFailed=false;await page.evaluate(()=>syncRefresh());await waitClear(page);
  console.log('PASS: cached control restoration preserves status failures, and unchanged polls do not redraw alerts');
  assert.deepEqual(forbidden,[]);assert.deepEqual(errors,[]);
 }finally{await page.close();}
}

async function memberScenarios(browser,url){
 // A different administrator viewing this source has the same read-only API contract as members.
 const {page,state,errors,forbidden,requests}=await fixture(browser,url,{member:true,viewerId:'9',ownerId:'7'});
 try{
  const preserved=await rawData(page);
  const latestFailure=stamp(1).replace(':00Z',':30Z');
  const failureDate=await page.evaluate(value=>new Date(value).toLocaleString('zh-CN'),latestFailure);
  state.status={...state.status,state:'paused',enabled:false,error:'来源管理员的计划同步失败',failureReason:'字节 · 第 2 页：上游 HTTP 503（已尝试 3 次）',failureAt:stamp(1),lastFailureAt:latestFailure,failureCount:3};
  await page.evaluate(()=>bidRefreshShared());await waitAlert(page);
  assert.equal(state.sharedReads.at(-1).snapshotNull,true,'New failure status arrives even when the snapshot is unchanged');
  assert.equal(await notices(page),1);assert.equal(await rawData(page),preserved);
  assert.ok((await page.locator('#bidDataAlertMeta').innerText()).includes('最近失败：'+failureDate),'Shared viewers receive the latest diagnostic timestamp');
  assert.match(await page.locator('#bidDataAlertMeta').innerText(),/连续失败：3次/);
  assert.ok((await page.locator('#bidDataAlertReason').innerText()).includes(state.status.failureReason),'Shared viewers see the separate safe failure reason');
  assert.equal(await page.locator('#bidDataAlertRetry').innerText(),'重新读取共享状态');
  await page.locator('.section-nav a[href="#strategy-lab"]').click();assert.equal(await alertVisible(page),true);
  await page.locator('.section-nav a[href="#report"]').click();
  await page.evaluate(()=>bidRefreshShared());assert.equal(await notices(page),1);
  await checkLayout(page,'member');

  // Snapshot and sync state are separate reads; a later commit may be represented by status first.
  state.status={...state.status,state:'ready',enabled:true,error:'',lastSuccess:stamp(2),failureReason:'',lastFailureAt:null,failureCount:0};delete state.status.failureAt;
  await page.evaluate(()=>bidRefreshShared());
  assert.equal(await alertVisible(page),true,'Old snapshotUpdatedAt plus newer lastSuccess must not clear prematurely');
  assert.equal(await rawData(page),preserved);
  state.snapshot={...state.snapshot,updatedAt:stamp(2)};
  await page.evaluate(()=>bidRefreshShared());await waitClear(page);

  // Keep viewer identity fixed and change only report owner; the same timestamp is a new source incident.
  state.status={...state.status,state:'retrying',error:'来源 7 再次同步失败',failureAt:stamp(3),lastFailureAt:stamp(3),failureCount:1};
  await page.evaluate(()=>bidRefreshShared());await waitAlert(page);assert.equal(await notices(page),2);
  state.ownerId='8';state.status={...state.status,userId:'8',error:'来源 8 同步失败'};
  await page.evaluate(()=>bidRefreshShared());await waitAlert(page);
  assert.equal(await notices(page),3,'Incident identity follows sharedOwnerId, not the unchanged viewer userId');
  await page.locator('#bidDataAlertRetry').click();assert.equal(await notices(page),3);
  assert.equal(new URL(page.url()).hash,'#report');assert.equal(await page.locator('#sync-settings').isVisible(),false);
  assert.equal(requests.some(value=>value.startsWith('/api/bid-monitor/server-sync')||value==='/api/bid-monitor/snapshot'||value==='/api/bid-monitor/dingtalk'),false);
  assert.deepEqual(forbidden,[]);assert.deepEqual(errors,[]);
  console.log('PASS: members and other admins see failures on unchanged shared snapshots, use exact source identity, retain alerts through commit races, and never call private APIs');
 }finally{await page.close();}
}

(async()=>{
 fs.mkdirSync(output,{recursive:true});
 const server=http.createServer((request,response)=>{
  const file=path.resolve(root,'.'+new URL(request.url,'http://localhost').pathname);
  if(!file.startsWith(root+path.sep)||!fs.existsSync(file)||!fs.statSync(file).isFile())return response.writeHead(404).end();
  response.setHeader('Content-Type',file.endsWith('.js')?'application/javascript':file.endsWith('.css')?'text/css':file.endsWith('.png')?'image/png':'text/html;charset=utf-8');response.end(fs.readFileSync(file));
 });
 await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));let browser;
 try{
  browser=await chromium.launch({channel:'chrome',headless:true});
  const url=`http://127.0.0.1:${server.address().port}`;
  await adminScenarios(browser,url);await memberScenarios(browser,url);
  console.log('PASS: desktop and mobile failure banners remain sticky without horizontal overflow; four screenshots saved in .runtime');
 }finally{if(browser)await browser.close();await new Promise(resolve=>server.close(resolve));}
})().catch(error=>{console.error(error);process.exitCode=1;});
