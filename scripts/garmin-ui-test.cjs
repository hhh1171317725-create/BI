const {chromium}=require('playwright');
const http=require('node:http');
const fs=require('node:fs');
const path=require('node:path');
const assert=require('node:assert/strict');
const root=path.resolve(__dirname,'../frontend');
const delay=ms=>new Promise(resolve=>setTimeout(resolve,ms));

(async()=>{
  let owner='user-1',status={userId:'user-1',connected:false,region:'global',state:'idle',available:true,error:'',lastSuccess:null,readKey:{enabled:false,canCreate:false,createdAt:0,expiresAt:0},settings:{autoSync:false,intervalHours:1,days:7}};
  let rows=[],dataError=false,dataDelay=0,dayDelay=0,statusReads=0,dataReads=0,dayReads=0,invalidMfa=true,accessDenied=false;
  const mutations=[],errors=[];
  const server=http.createServer(async(req,res)=>{
    const url=new URL(req.url,'http://localhost'),json=(value,code=200)=>{res.writeHead(code,{'Content-Type':'application/json;charset=utf-8'});res.end(JSON.stringify(value));};
    if(url.pathname==='/api/session'){json({authenticated:true,user:{id:owner,role:'member'}});return;}
    if(url.pathname==='/api/report-visibility'){json({dhh:false,jd:false,jdLowActivity:false,adpflux:false});return;}
    if(url.pathname==='/api/tool-visibility'){json({garmin:true});return;}
    if(url.pathname==='/api/garmin/status'){statusReads++;if(accessDenied)json({error:'佳明工具权限已关闭'},403);else json(status);return;}
    if(url.pathname==='/api/garmin/day'){
      dayReads++;const savedOwner=owner,date=url.searchParams.get('date'),row=JSON.parse(JSON.stringify(rows.find(row=>row.date===date)||null));const pause=dayDelay;dayDelay=0;if(pause)await delay(pause);
      if(accessDenied){json({error:'佳明工具权限已关闭'},403);return;}
      if(!row){json({error:'这一天尚未保存记录'},404);return;}json({userId:savedOwner,day:row});return;
    }
    if(url.pathname==='/api/garmin/data'){
      dataReads++;const savedOwner=owner,startDate=url.searchParams.get('startDate'),endDate=url.searchParams.get('endDate');
      const savedRows=JSON.parse(JSON.stringify(rows)),pause=dataDelay;dataDelay=0;
      if(pause)await delay(pause);
      if(dataError){json({error:'历史记录暂时无法读取，请稍后重试。'},503);return;}
      const count=Math.round((Date.parse(endDate)-Date.parse(startDate))/86400000)+1;
      json({userId:savedOwner,startDate,endDate,days:savedRows,missingDates:Array.from({length:Math.max(0,count-savedRows.length)},(_,i)=>`missing-${i}`),lastSuccess:status.lastSuccess});return;
    }
    if(url.pathname.startsWith('/api/garmin/')&&req.method==='POST'){
      let body='';for await(const chunk of req)body+=chunk;const payload=JSON.parse(body||'{}');mutations.push({path:url.pathname,payload});
      if(String(payload.expectedUserId)!==owner){json({code:'ACCOUNT_CHANGED',error:'网站登录账户已更换，请刷新页面后继续'},409);return;}
      if(url.pathname.endsWith('/connect'))status={...status,state:'connecting',region:payload.region,jobId:'connect-job',error:'',mfa:{required:false}};
      else if(url.pathname.endsWith('/mfa'))status=invalidMfa?{...status,state:'mfa_required',mfa:{...status.mfa,required:true,invalidCode:true}}:{...status,connected:true,state:'ready',error:'',mfa:{required:false}};
      else if(url.pathname.endsWith('/sync'))status={...status,state:'syncing',jobId:'sync-job',progress:{done:1,total:7},error:''};
      else if(url.pathname.endsWith('/settings'))status={...status,settings:{autoSync:payload.autoSync,intervalHours:payload.intervalHours,days:payload.days}};
      else if(url.pathname.endsWith('/disconnect'))status={...status,connected:false,state:'idle',jobId:null,mfa:{required:false},settings:{...status.settings,autoSync:false}};
      else if(url.pathname.endsWith('/access-key/create')){
        status={...status,readKey:{enabled:true,canCreate:true,createdAt:Date.now(),expiresAt:Date.now()+payload.days*86400000}};
        json({...status,apiKey:'ghk_fixture_'+ 'a'.repeat(43)});return;
      }
      else if(url.pathname.endsWith('/access-key/revoke'))status={...status,readKey:{enabled:false,canCreate:true,createdAt:0,expiresAt:0}};
      if(status.connected)status.readKey={...status.readKey,canCreate:true};
      json(status);return;
    }
    const file=path.join(root,path.basename(url.pathname));
    if(!fs.existsSync(file)){res.writeHead(404);res.end();return;}
    res.setHeader('Content-Type',file.endsWith('.js')?'application/javascript':file.endsWith('.css')?'text/css':'text/html;charset=utf-8');res.end(fs.readFileSync(file));
  });
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));let browser;
  try{
    browser=await chromium.launch({headless:true,channel:'chrome'});
    const page=await browser.newPage({viewport:{width:1440,height:1000}});
    page.on('pageerror',error=>errors.push(error.message));
    await page.addInitScript(()=>{
      window.__garminActive=true;
      document.hasFocus=()=>window.__garminActive;
      Object.defineProperty(document,'hidden',{get:()=>!window.__garminActive});
      Object.defineProperty(navigator,'clipboard',{value:{writeText:async value=>{window.__copiedGarminKey=value;}}});
      const original=window.setTimeout;window.setTimeout=(callback,ms,...args)=>original(callback,ms===3000?100:ms,...args);
    });
    await page.goto(`http://127.0.0.1:${server.address().port}/garmin.html`);
    await page.locator('html.authenticated').waitFor();await page.locator('#dataEmpty').waitFor();
    assert.equal(await page.locator('#connectionState').textContent(),'未连接');
    assert.equal(await page.locator('#autoSync').isChecked(),false);assert.equal(await page.locator('#syncButton').isDisabled(),true);
    assert.equal(await page.locator('#createReadKey').isDisabled(),true);
    const end=await page.locator('#endDate').inputValue(),start=await page.locator('#startDate').inputValue();
    assert.equal((Date.parse(end)-Date.parse(start))/86400000+1,7);
    assert.equal(await page.locator('a[href="/tools.html"]').getAttribute('href'),'/tools.html');
    await page.locator('#email').fill('synthetic@example.invalid');await page.locator('#password').fill('synthetic-secret');await page.locator('#region').selectOption('cn');
    await page.locator('#connectButton').click();await page.waitForFunction(()=>document.getElementById('connectionState').textContent==='连接中');
    assert.deepEqual(mutations.at(-1),{path:'/api/garmin/connect',payload:{email:'synthetic@example.invalid',password:'synthetic-secret',region:'cn',expectedUserId:'user-1'}});
    assert.equal(await page.locator('#password').inputValue(),'');assert.equal(await page.locator('#email').inputValue(),'');
    const saved=await page.evaluate(()=>JSON.stringify({local:{...localStorage},session:{...sessionStorage}}));
    for(const credential of ['synthetic@example.invalid','synthetic-secret'])assert.equal(saved.includes(credential),false);
    status={...status,state:'mfa_required',mfa:{required:true,method:'email',expiresAt:Date.now()+120000}};
    await page.locator('#refreshStatus').click();await page.locator('#mfaForm').waitFor();
    assert.equal((await page.locator('#mfaDescription').textContent()).includes('尚无记录'),false);
    assert.equal(await page.locator('#connectButton').isDisabled(),true);
    status={...status,mfa:{...status.mfa,expiresAt:Date.now()-1000}};await page.locator('#refreshStatus').click();
    await page.waitForFunction(()=>document.getElementById('mfaDescription').textContent.includes('已过期'));assert.equal(await page.locator('#mfaButton').isDisabled(),true);
    status={...status,mfa:{...status.mfa,expiresAt:Date.now()+120000}};await page.locator('#refreshStatus').click();await page.waitForFunction(()=>!document.getElementById('mfaButton').disabled);
    await page.locator('#mfaCode').fill('111111');await page.locator('#mfaButton').click();
    await page.waitForFunction(()=>document.getElementById('mfaDescription').textContent.includes('验证码无效'));
    assert.equal(await page.locator('#mfaCode').inputValue(),'');assert.equal(mutations.at(-1).payload.jobId,'connect-job');
    assert.equal(await page.locator('#mfaCode').evaluate(node=>node===document.activeElement),true);
    invalidMfa=false;await page.locator('#mfaCode').fill('222222');await page.locator('#mfaButton').click();
    await page.waitForFunction(()=>document.getElementById('connectionState').textContent==='已连接');assert.equal(await page.locator('#mfaForm').isVisible(),false);
    assert.equal(await page.locator('#syncButton').isEnabled(),true);
    await page.locator('#createReadKey').click();await page.locator('#readKeySecret').waitFor();
    assert.deepEqual(mutations.at(-1).payload,{days:30,expectedUserId:'user-1'});
    assert.equal(await page.locator('#readKeyValue').inputValue(),'ghk_fixture_'+ 'a'.repeat(43));
    assert.equal((await page.evaluate(()=>JSON.stringify({local:{...localStorage},session:{...sessionStorage}}))).includes('ghk_fixture_'),false);
    await page.locator('#copyReadKey').click();await page.waitForFunction(()=>document.getElementById('readKeyCopyState').textContent.includes('已复制'));
    assert.equal(await page.evaluate(()=>window.__copiedGarminKey),'ghk_fixture_'+ 'a'.repeat(43));
    await page.locator('#hideReadKey').click();assert.equal(await page.locator('#readKeyValue').inputValue(),'');
    await page.locator('#createReadKey').click();await page.locator('#readKeySecret').waitFor();
    await page.locator('#revokeReadKey').click();await page.waitForFunction(()=>document.getElementById('readKeyState').textContent==='尚未启用');
    assert.equal(await page.locator('#readKeyValue').inputValue(),'');assert.equal(await page.locator('#revokeReadKey').isDisabled(),true);
    await page.locator('.api-card').screenshot({path:path.resolve(__dirname,'../.runtime/garmin-api-desktop.png')});
    await page.locator('#createReadKey').click();await page.locator('#readKeySecret').waitFor();
    await page.evaluate(()=>window.dispatchEvent(new Event('pagehide')));
    assert.equal(await page.locator('#readKeyValue').inputValue(),'');assert.equal(await page.locator('#readKeySecret').isVisible(),false);
    await page.locator('#autoSync').check();await page.locator('#intervalHours').selectOption('24');await page.locator('#syncDays').fill('30');await page.locator('#saveSettings').click();
    await page.waitForFunction(()=>document.getElementById('autoSyncState').textContent.includes('24'));
    assert.deepEqual(mutations.at(-1).payload,{autoSync:true,intervalHours:24,days:30,expectedUserId:'user-1'});
    await page.locator('#autoSync').uncheck();await page.locator('#saveSettings').click();await page.waitForFunction(()=>document.getElementById('autoSyncState').textContent==='关闭');
    await page.locator('#syncButton').click();await page.waitForFunction(()=>document.getElementById('jobStatus').textContent.includes('1 / 7'));
    assert.deepEqual(mutations.at(-1).payload,{startDate:start,endDate:end,includeDetails:true,expectedUserId:'user-1'});
    const beforePoll=statusReads;await page.waitForTimeout(250);assert.ok(statusReads>beforePoll,'active job should poll');
    await page.evaluate(()=>{window.__garminActive=false;document.dispatchEvent(new Event('visibilitychange'));});
    const paused=statusReads;await page.waitForTimeout(350);assert.equal(statusReads,paused,'inactive page must not poll');
    status={...status,state:'error',error:'佳明服务暂时不可用，请稍后重试。',progress:{done:1,total:7}};
    await page.evaluate(()=>{window.__garminActive=true;document.dispatchEvent(new Event('visibilitychange'));});
    await page.waitForFunction(()=>document.getElementById('syncError').textContent.includes('佳明服务暂时不可用'));
    assert.equal(await page.locator('#lastSuccess').textContent(),'最近成功：尚无记录');
    rows=[{date:end,summary:{steps:1000,activeCalories:200,totalCalories:2200,restingHeartRate:60,heartRateAverage:70,heartRateMin:50,heartRateMax:120,sleepSeconds:25200,deepSleepSeconds:3600,sleepStartTime:'2026-10-11T15:00:00Z',sleepEndTime:'2026-10-11T22:00:00Z',weightKg:65.4,workoutSeconds:1800,stress:20,bodyBattery:75,hrvMs:42.5},activities:[{id:'run-1',name:'<img src=x onerror=alert(1)>',type:'跑步',startTime:'2026-10-12T01:00:00Z',durationSeconds:1800,distanceMeters:5000,calories:200,averageHeartRate:120}],warnings:['unknown_private_code']},{date:start,summary:{steps:0,activeCalories:0,workoutSeconds:0,restingHeartRate:null,sleepSeconds:null,weightKg:null,stress:null,bodyBattery:null,hrvMs:null},activities:[],warnings:['sleep']}];
    status={...status,state:'ready',error:'',lastSuccess:'2026-10-12T02:00:00Z'};
    await page.locator('#refreshStatus').click();await page.waitForFunction(()=>document.querySelectorAll('#dailyRows tr').length===2);
    assert.ok((await page.locator('#summaryCards').textContent()).includes('有数据 2 / 7 天'));
    assert.equal(await page.locator('#summaryCards .summary-metric').nth(0).locator('strong').textContent(),'1,000');
    assert.equal(await page.locator('#summaryCards .summary-metric').nth(1).locator('strong').textContent(),'200 千卡');
    assert.equal(await page.locator('#summaryCards .summary-metric').nth(3).locator('strong').textContent(),'7 小时 0 分');
    const emptyRow=page.locator(`#dailyRows tr[data-date="${start}"]`);
    assert.equal(await emptyRow.locator('td').nth(0).textContent(),'0');assert.equal(await emptyRow.locator('td').nth(2).textContent(),'—');
    assert.ok((await page.locator('#dailyRows tr').first().textContent()).includes('42.5 毫秒'));
    assert.ok((await page.locator('#dailyRows tr').first().locator('td').nth(5).getAttribute('title')).includes('23:00:00'));
    await page.locator('#activitiesPanel summary').click();assert.equal(await page.locator('#activitiesList img').count(),0);
    assert.equal(await page.locator('#activitiesList h3').textContent(),'<img src=x onerror=alert(1)>');
    const record=value=>({state:'available',collectedAt:'2026-10-12T02:00:00Z',truncated:false,value});
    rows[0].details={daily:record({totalSteps:1000,dailyStepGoal:8000,newProviderMetric:88}),sleep:record({dailySleepDTO:{deepSleepSeconds:3600,sleepScores:{overall:{value:90}}}}),heart:record({heartRateValues:[[Date.parse(end),60],[Date.parse(end)+60000,-1],[Date.parse(end)+120000,80],[Date.parse(end)+180000,70]]}),spo2:{state:'unavailable',collectedAt:'2026-10-12T02:00:00Z',value:null},training_readiness:record([{score:65,description:'<img src=x onerror=alert(1)>'}])};
    rows[0].activities[0].id='123';rows[0].activities[0].overview=record({averagePower:200,elevationGain:42});rows[0].activities[0].details={splits:record([{distance:1000,duration:360}])};
    assert.equal(dayReads,0,'details must not be fetched for the daily table');
    await page.locator('#dailyRows tr').first().getByRole('button').click();await page.waitForFunction(()=>document.getElementById('detailStatus').textContent.includes('已采集 5 类'));
    assert.ok((await page.locator('#detailContent').textContent()).includes('步数目标'));
    assert.ok((await page.locator('#detailContent').textContent()).includes('newProviderMetric'));
    await page.locator('#detailSearch').fill('newProviderMetric');await page.waitForFunction(()=>document.getElementById('detailContent').textContent.includes('匹配 1 个字段'));
    assert.ok((await page.locator('#detailContent').textContent()).includes('88'));
    await page.locator('#detailSearch').fill('no-such-field');await page.waitForFunction(()=>document.getElementById('detailContent').textContent.includes('没有匹配字段'));
    await page.locator('#detailSearch').fill('');await page.waitForFunction(()=>document.getElementById('detailContent').textContent.includes('每日统计'));
    assert.equal(await page.locator('#dayDialog img').count(),0);
    const downloadEvent=page.waitForEvent('download');await page.locator('#exportDetail').click();const exported=await downloadEvent;assert.equal(exported.suggestedFilename(),`garmin-${end}.json`);
    await page.locator('#detailTabs').getByRole('button',{name:'睡眠',exact:true}).click();assert.ok((await page.locator('#detailContent').textContent()).includes('深睡'));
    await page.locator('#detailTabs').getByRole('button',{name:'心率与恢复'}).click();assert.equal(await page.locator('#detailContent svg').count(),1);
    assert.ok((await page.locator('#detailContent').textContent()).includes('佳明未提供记录'));
    assert.ok((await page.locator('#detailContent').textContent()).includes('尚未采集'));
    await page.setViewportSize({width:1440,height:1000});await page.screenshot({path:path.resolve(__dirname,'../.runtime/garmin-details-desktop.png')});
    await page.setViewportSize({width:390,height:844});assert.equal(await page.locator('#dayDialog').evaluate(node=>node.scrollWidth<=node.clientWidth),true);
    await page.screenshot({path:path.resolve(__dirname,'../.runtime/garmin-details-mobile.png')});await page.keyboard.press('Escape');assert.equal(await page.locator('#dayDialog').evaluate(node=>node.open),false);await page.setViewportSize({width:1440,height:1000});
    await page.locator('#dailyRows tr').first().getByRole('button').click();await page.waitForFunction(()=>!document.getElementById('exportDetail').disabled);
    await page.locator('#detailTabs').getByRole('button',{name:'运动详情'}).click();assert.ok((await page.locator('#detailContent').textContent()).includes('分段'));
    await page.getByRole('button',{name:'补采本次运动详情'}).click();assert.deepEqual(mutations.at(-1).payload,{startDate:end,endDate:end,activityId:'123',includeDetails:false,expectedUserId:'user-1'});
    await page.waitForFunction(()=>document.getElementById('pageMessage').textContent.includes('补采任务已提交'));
    await page.locator('#closeDetail').click();status={...status,state:'ready'};await page.locator('#refreshStatus').click();
    dayDelay=350;const beforeDay=dayReads;await page.locator('#dailyRows tr').first().getByRole('button').click();while(dayReads===beforeDay)await delay(10);
    await page.locator('#detailDate').selectOption(start);await page.waitForFunction(()=>document.getElementById('detailStatus').textContent.includes('已采集 0 类'));await page.waitForTimeout(450);
    assert.equal(await page.locator('#detailTitle').textContent(),start+' 的健康记录');assert.equal((await page.locator('#detailContent').textContent()).includes('newProviderMetric'),false);
    await page.locator('#syncDetail').click();assert.deepEqual(mutations.at(-1).payload,{startDate:start,endDate:start,includeDetails:true,expectedUserId:'user-1'});
    await page.locator('#closeDetail').click();status={...status,state:'ready'};await page.locator('#refreshStatus').click();
    await page.locator('#warningsPanel summary').click();assert.equal((await page.locator('#warningsList').textContent()).includes('unknown_private_code'),false);
    const beforeInvalid=mutations.length;await page.locator('#startDate').fill(new Date(Date.parse(end)-90*86400000).toISOString().slice(0,10));await page.locator('#syncButton').click();
    assert.ok((await page.locator('#pageMessage').textContent()).includes('最多查询或同步 90 天'));assert.equal(mutations.length,beforeInvalid);
    await page.locator('#startDate').fill(start);dataError=true;await page.locator('#queryButton').click();
    await page.waitForFunction(()=>document.getElementById('dataStatus').textContent.includes('下方仍显示此前'));
    assert.equal(await page.locator('#dailyRows tr').count(),2);dataError=false;
    dataDelay=350;const beforeRace=dataReads;await page.locator('#queryButton').click();await page.waitForFunction(()=>document.getElementById('dataStatus').textContent.includes('正在读取'));
    while(dataReads===beforeRace)await delay(10);
    rows=[{date:end,summary:{steps:987},activities:[],warnings:[]}];await page.locator('#queryButton').click();
    await page.waitForFunction(()=>document.querySelector('#dailyRows tr td')?.textContent==='987');await page.waitForTimeout(450);
    assert.equal(await page.locator('#dailyRows tr').count(),1);assert.equal(await page.locator('#dailyRows tr td').first().textContent(),'987');
    await page.setViewportSize({width:390,height:844});assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
    await page.locator('.app-nav-toggle').click();await page.getByRole('button',{name:'关闭导航',exact:true}).first().waitFor();await page.keyboard.press('Escape');
    assert.equal(await page.locator('.app-nav-toggle').getAttribute('aria-expanded'),'false');
    fs.mkdirSync(path.resolve(__dirname,'../.runtime'),{recursive:true});await page.screenshot({path:path.resolve(__dirname,'../.runtime/garmin-mobile.png'),fullPage:true});
    await page.locator('.api-card').screenshot({path:path.resolve(__dirname,'../.runtime/garmin-api-mobile.png')});
    await page.setViewportSize({width:1440,height:1000});await page.screenshot({path:path.resolve(__dirname,'../.runtime/garmin-desktop.png'),fullPage:true});
    page.once('dialog',dialog=>{assert.ok(dialog.message().includes('历史数据会保留'));dialog.accept();});await page.locator('#disconnect').click();
    await page.waitForFunction(()=>document.getElementById('pageMessage').textContent.includes('已断开连接'));
    assert.equal(await page.locator('#dailyRows tr').count(),1);assert.equal(await page.locator('#syncButton').isDisabled(),true);assert.equal(await page.locator('#queryButton').isEnabled(),true);
    await page.locator('#email').fill('another@example.invalid');await page.locator('#password').fill('other-synthetic-secret');await page.locator('#connectButton').click();
    rows=[];status={...status,connected:true,state:'ready',lastSuccess:null,accountChanged:true,mfa:{required:false}};
    await page.locator('#refreshStatus').click();await page.waitForFunction(()=>document.getElementById('pageMessage').textContent.includes('已切换佳明账户'));
    assert.equal(await page.locator('#dailyRows tr').count(),0);await page.locator('#dataEmpty').waitFor();
    owner='user-2';await page.locator('#syncButton').click();
    await page.waitForFunction(()=>document.getElementById('pageMessage').textContent.includes('网站登录账户已更换'));
    assert.equal(await page.locator('#dailyRows tr').count(),0);assert.equal(await page.locator('#connectButton').isDisabled(),true);assert.equal(await page.locator('#queryButton').isDisabled(),true);
    status={userId:owner,connected:true,region:'global',state:'ready',available:true,lastSuccess:null,settings:{autoSync:false,intervalHours:1,days:7}};
    rows=[{date:end,summary:{steps:22},activities:[],warnings:[]}];await page.reload();await page.waitForFunction(()=>document.querySelector('#dailyRows tr td')?.textContent==='22');
    owner='user-3';await page.locator('#queryButton').click();await page.waitForFunction(()=>document.getElementById('pageMessage').textContent.includes('网站登录账户已更换'));
    assert.equal(await page.locator('#dailyRows tr').count(),0);assert.equal(await page.locator('#lastSuccess').textContent(),'最近成功：尚无记录');
    status={...status,userId:owner};rows=[{date:end,summary:{steps:88},activities:[],warnings:[]}];await page.reload();await page.waitForFunction(()=>document.querySelector('#dailyRows tr td')?.textContent==='88');
    await page.locator('#dailyRows tr').first().getByRole('button').click();await page.waitForFunction(()=>!document.getElementById('exportDetail').disabled);await page.locator('#closeDetail').click();
    await page.locator('#connectionPanel summary').click();await page.locator('#email').fill('revoked@example.invalid');await page.locator('#password').fill('revoked-synthetic-secret');
    dataDelay=350;const beforeDenied=dataReads;await page.locator('#queryButton').click();while(dataReads===beforeDenied)await delay(10);
    accessDenied=true;await page.locator('#refreshStatus').click();await page.waitForFunction(()=>document.getElementById('pageMessage').textContent.includes('请联系网站管理员'));
    assert.equal(await page.locator('#dayDialog').evaluate(node=>node.open),false);assert.equal(await page.locator('#detailContent').textContent(),'');
    assert.equal((await page.locator('#pageMessage').textContent()).includes('更换'),false);assert.equal(await page.locator('#dailyRows tr').count(),0);
    assert.equal(await page.locator('#password').inputValue(),'');assert.equal(await page.locator('#email').inputValue(),'');
    assert.equal(await page.locator('#readKeyValue').inputValue(),'');assert.equal(await page.locator('#createReadKey').isDisabled(),true);
    assert.equal(await page.locator('#connectButton').isDisabled(),true);assert.equal(await page.locator('#queryButton').isDisabled(),true);assert.equal(await page.locator('#refreshStatus').isDisabled(),true);
    const afterRevoked=statusReads;await page.waitForTimeout(450);assert.equal(statusReads,afterRevoked);assert.equal((await page.locator('#pageMessage').textContent()).includes('更换'),false);
    assert.deepEqual(errors,[]);console.log('GARMIN UI PASS: owner isolation, connection/MFA expiry, no browser credentials, permission revocation, settings, active polling, safe missing metrics/range totals, races/errors, disconnect history, mobile/accessibility');
  }finally{await browser?.close();server.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
