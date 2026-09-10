const {chromium}=require('playwright');
const fs=require('node:fs'),http=require('node:http'),path=require('node:path'),assert=require('node:assert/strict');
const root=path.resolve(__dirname,'../frontend'),output=path.resolve(__dirname,'../.runtime');
const metric={消耗:120,现金消耗:100,现金利润:20,预估佣金:120,佣金:150,利润:30,ROI:1.2,现金ROI:1.2,转化数:12,注册数:24,点击:50,成功转化数:10,有效父订单数:9};
const account={...metric,账户名称:'测试账户',账户ID:'10001',advertiserId:'10001',advertiserName:'测试账户',totalSpend:120,balance:40,clicks:50,conversions:12,cpa:10,status:'enabled',date:'2026-09-08',latestDate:'2026-09-08'};
const report={rows:2,range:['2026-09-01','2026-09-08'],summary:metric,by_optimizer:[{...metric,优化师:'优化师 A'}],by_project:[{...metric,项目:'测试项目'}],by_task:[{...metric,任务名:'测试任务',任务:'测试任务'}],by_account:[account],by_date:[{...account,日期:'2026-09-08'}],by_account_date:[{...account,日期:'2026-09-08'}],by_plan:[{...metric,计划名称:'测试计划',计划ID:'P1'}],by_admin:[{...metric,管理员:'A'}],alerts:{items:[]}};
report.by_optimizer_date=[{...metric,优化师:'优化师 A',日期:'2026-09-08'}];
report.by_optimizer_project_date=[{...metric,优化师:'优化师 A',项目:'测试项目',日期:'2026-09-08'}];
report.by_project_date=[{...metric,项目:'测试项目',日期:'2026-09-08'}];
report.by_optimizer_project=report.by_optimizer_project_date;
(async()=>{
 fs.mkdirSync(output,{recursive:true});
 const server=http.createServer((req,res)=>{let url=new URL(req.url,'http://localhost').pathname;if(!path.extname(url))url=url==='/'?'/index.html':url+'.html';const file=path.resolve(root,'.'+url);if(!file.startsWith(root+path.sep)||!fs.existsSync(file)){res.writeHead(404).end();return}res.setHeader('Content-Type',file.endsWith('.js')?'application/javascript':file.endsWith('.css')?'text/css':'text/html;charset=utf-8');res.end(fs.readFileSync(file));});
 await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));let browser;
 try{
  browser=await chromium.launch({channel:'chrome',headless:true});
  for(const [url,table,tools,remove,move] of [
   ['/','#table','#mainTableTools','注册数','消耗'],
   ['/jd','#table','#mainTableTools','有效订单数','消耗'],
   ['/jd-low-activity','#table','#mainColumnTools','点击','消耗'],
   ['/adpflux','#table','.table-meta','时区','总消耗'],
   ['/account-vault','#dataTable','.toolbar-actions','style ID','投放国家'],
  ]){
   const page=await browser.newPage({viewport:{width:1440,height:1000}}),errors=[],requests=[];await page.emulateMedia({reducedMotion:'reduce'});
   page.on('pageerror',e=>errors.push(e.message));
   await page.route('**/pet-loader.js*',route=>route.fulfill({contentType:'application/javascript',body:''}));
   await page.route('**/api/**',route=>{const p=new URL(route.request().url()).pathname;requests.push({p,method:route.request().method(),body:route.request().postData()});let data={};if(p==='/api/session')data={authenticated:true,user:{id:1,role:'admin'}};else if(p.endsWith('/analyze')||p.endsWith('/metrics'))data=report;else if(p==='/api/account-vault')data={entries:[{id:1,keyword:'测试关键词',accountIds:'10001',country:'意大利',channelId:'C1',styleId:'S1',updatedAt:'2026-09-08T12:00:00',revenueSourceIds:''}],total:1};else if(p.endsWith('/options'))data={channels:[],styleIds:[]};else if(p.endsWith('/operators'))data={users:[{id:1,username:'管理员'}]};else if(p.endsWith('/revenue'))data={rows:[]};return route.fulfill({json:data});});
   await page.goto(`http://127.0.0.1:${server.address().port}${url}`);await page.locator(`${table} tbody tr`).first().waitFor();await page.locator(`${tools} .uc-columns-button`).first().waitFor();
   const headers=async()=>{await page.locator(`${table} th:visible`).first().waitFor();return page.locator(`${table} th:visible`).allTextContents()};const before=await headers();
   const openColumns=()=>page.locator(`${tools} .uc-columns-button`).first().click();const modal=page.locator('#unifiedControlsDialog');
   await openColumns();
   await modal.locator('.uc-clear-optional').click();
   assert.equal(await modal.locator('.uc-choices input:checked:not(:disabled)').count(),0);
   await modal.locator('.uc-search').fill(remove);
   await modal.locator('.uc-select-visible').click();
   assert.equal(await modal.locator('.uc-choices input:not(:checked)').count(),0);
   await modal.locator('.uc-cancel').click();assert.deepEqual(await headers(),before);
   await openColumns();await modal.getByRole('button',{name:`移除${remove}`,exact:true}).click();await modal.locator('.uc-cancel').click();assert.deepEqual(await headers(),before);
   await openColumns();await modal.locator('.uc-search').fill(remove);assert.ok((await modal.locator('.uc-choices label').allTextContents()).every(text=>text.includes(remove)));await modal.getByRole('button',{name:`移除${remove}`,exact:true}).click();await modal.getByRole('button',{name:`下移${move}`,exact:true}).click();await modal.locator('.uc-apply').click();await page.waitForTimeout(60);
   const customized=await headers();assert.equal(customized.some(h=>h.trim()===remove),false);assert.notDeepEqual(customized,before.filter(h=>h.trim()!==remove));
   await openColumns();await modal.locator('.uc-reset').click();await page.keyboard.press('Escape');assert.deepEqual(await headers(),customized);
   await page.reload();await page.locator(`${table} tbody tr`).first().waitFor();assert.deepEqual(await headers(),customized);
   if(url!=='/account-vault'){
    const originalView=url==='/'||url==='/jd'?'by_optimizer':'by_account';
    await page.locator('[data-key="by_date"],[data-view="by_date"]').click();assert.equal((await headers())[0].trim().startsWith('日期'),true);
    await page.locator(`[data-key="${originalView}"],[data-view="${originalView}"]`).click();assert.deepEqual(await headers(),customized);
   }
   if(url==='/adpflux'||url==='/jd-low-activity'){
    const downloadPromise=page.waitForEvent('download');await page.locator('#export').click();const download=await downloadPromise;
    const csv=fs.readFileSync(await download.path(),'utf8');const exportHeaders=csv.replace(/^\ufeff/,'').split(/\r?\n/)[0].split(',').map(c=>c.replace(/^"|"$/g,''));
    assert.deepEqual(exportHeaders,customized.map(h=>h.trim().replace(/\s*[↑↓]$/,'')));
   }
   if(url==='/'||url==='/jd'||url==='/jd-low-activity'){
    const detailTable=url==='/jd-low-activity'?'#accountTable':'#drillTable',detailTools=url==='/jd-low-activity'?'#detailColumnTools':'#drillTableTools';
    await page.locator(`${detailTools} .uc-columns-button`).click();await modal.getByRole('button',{name:'移除消耗',exact:true}).click();await modal.locator('.uc-apply').click();
    assert.equal((await page.locator(`${detailTable} th`).allTextContents()).some(h=>h.trim()==='消耗'),false);assert.deepEqual(await headers(),customized);
   }
   if(url==='/'){
    await page.locator('[data-key="by_project"]').click();await page.locator('#optimizerBreakdownTools .uc-columns-button').click({timeout:5000}).catch(async error=>{console.error(await page.locator('#drillPanel').innerHTML(),errors);throw error});await modal.getByRole('button',{name:'移除注册数',exact:true}).click();await modal.locator('.uc-apply').click();
    assert.equal((await page.locator('#optimizerBreakdownTable th').allTextContents()).includes('注册数'),false);await page.locator('[data-key="by_optimizer"]').click();assert.deepEqual(await headers(),customized);
   }
   const filter=page.locator('.uc-more-filters').first();await filter.click();assert.equal(await modal.locator('input[type=password]').count(),0);
   const priorRequests=requests.length;const draft=modal.locator('[data-original]').first();const old=await draft.inputValue();if(await draft.getAttribute('type')==='date')await draft.fill('2026-09-01');await modal.locator('.uc-cancel').click();assert.equal(requests.length,priorRequests);
   await filter.click();const start=modal.locator('[data-original="start"],[data-original="dataStart"]'),end=modal.locator('[data-original="end"],[data-original="dataEnd"]');await start.fill('2026-09-08');await end.fill('2026-09-01');await modal.locator('.uc-apply').click();assert.equal(await modal.isVisible(),true);assert.match(await modal.locator('.uc-error').innerText(),/开始日期/);assert.equal(requests.length,priorRequests);await end.fill('2026-09-08');await modal.locator('.uc-apply').click();await page.waitForTimeout(100);assert.equal(await modal.isVisible(),false);
   await openColumns();await page.screenshot({path:path.join(output,`controls-${url==='/'?'dhh':url.slice(1)}-desktop.png`)});await page.keyboard.press('Escape');
   await page.setViewportSize({width:390,height:844});await openColumns();assert.equal(await modal.evaluate(e=>e.getBoundingClientRect().left>=0&&e.getBoundingClientRect().right<=innerWidth),true);assert.equal(await modal.evaluate(e=>e.scrollWidth<=e.clientWidth),true);await page.screenshot({path:path.join(output,`controls-${url==='/'?'dhh':url.slice(1)}-mobile.png`)});await page.keyboard.press('Escape');
   await page.route('**/api/session',route=>route.fulfill({json:{authenticated:true,user:{id:2,role:'member'}}}));await page.reload();await page.locator(`${table} tbody tr`).first().waitFor();await page.locator('.uc-more-filters').first().click();assert.equal(await modal.locator('[data-original="operatorFilter"],input[type=password]').count(),0);await page.keyboard.press('Escape');
   assert.deepEqual(errors,[]);assert.equal(requests.some(r=>r.method==='POST'&&!r.p.endsWith('/analyze')&&!r.p.endsWith('/metrics')),false);
   console.log(`PASS ${url}: columns/search/order/persistence, draft cancel/reset, date validation, mobile, member permissions`);await page.close();
  }
 }finally{await browser?.close();await new Promise(resolve=>server.close(resolve));}
})().catch(error=>{console.error(error);process.exitCode=1;});
