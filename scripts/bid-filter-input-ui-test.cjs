const {chromium}=require('playwright');
const http=require('node:http'),fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const root=path.resolve(__dirname,'../frontend'),output=path.resolve(__dirname,'../.runtime');
const rows=Array.from({length:3000},(_,i)=>({promotion_id:String(10000+i),promotion_name:`目标计划 ${i}`,advertiser_id:'111',media_account_name:'客户A',user_name:'张三',stat_cost:100+i,convert_cnt:10,active_register:100,cpa_bid:10}));
(async()=>{
 fs.mkdirSync(output,{recursive:true});
 const server=http.createServer((req,res)=>{
  const file=path.resolve(root,'.'+new URL(req.url,'http://localhost').pathname);
  if(!file.startsWith(root+path.sep)||!fs.existsSync(file)||!fs.statSync(file).isFile())return res.writeHead(404).end();
  res.setHeader('Content-Type',file.endsWith('.js')?'application/javascript':file.endsWith('.css')?'text/css':'text/html;charset=utf-8');res.end(fs.readFileSync(file));
 });
 await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));let browser;
 try{
  browser=await chromium.launch({channel:'chrome',headless:true});const page=await browser.newPage({viewport:{width:1440,height:1000}});
  const errors=[],requests=[];page.on('pageerror',e=>errors.push(e.message));
  await page.route('**/pet-loader.js*',r=>r.fulfill({body:''}));
  await page.route('**/api/**',r=>{
   const url=new URL(r.request().url());requests.push(url.pathname);let data={};
   if(url.pathname==='/api/session')data={authenticated:true};
   else if(url.pathname==='/api/report-visibility')data={dhh:true,jd:true,jdLowActivity:true,bidMonitor:true};
   else if(url.pathname==='/api/tool-visibility')data={bidMonitor:true};
   else if(url.pathname==='/api/bid-monitor/shared-report')data={userId:'2',canManage:false,sharedOwnerId:'1',sharedOwnerName:'管理员',version:'v1',snapshot:url.searchParams.get('after')==='v1'?null:{date:'2026-09-08',updatedAt:'2026-09-08T01:00:00Z',rows},rules:[{name:'任务A',keyword:'客户A',price:2}],pricingRevision:'p1',strategies:[],strategyRevision:'s1'};
   else if(url.pathname==='/api/bid-monitor/gap')data={anchor:'2026-09-08',start:'2026-09-05',end:'2026-09-07',priceDate:'2026-09-06',accounts:{111:{gap:.8,validDays:3,days:[]}}};
   return r.fulfill({json:data});
  });
  await page.goto(`http://127.0.0.1:${server.address().port}/bid-monitor.html`);
  await page.waitForFunction(()=>document.querySelector('#count').textContent==='3000 条'&&document.querySelector('#rows td:nth-child(16)')?.textContent==='1.60');
  await page.evaluate(()=>{window.__filterRenders=0;document.addEventListener('bid:rendered',()=>window.__filterRenders++);});
  const dataRequests=()=>requests.filter(path=>!['/api/bid-monitor/shared-report','/api/bid-monitor/gap/revision'].includes(path)).length;
  const initialRequests=dataRequests();
  await page.evaluate(()=>{
   const input=document.getElementById('search');
   for(let i=0;i<20;i++){input.value=i===19?'目标计划 2999':`输入 ${i}`;input.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText'}));}
   if(window.__filterRenders!==0)throw Error('Burst should not render intermediate search results');
   if(input.getAttribute('aria-busy')!=='true')throw Error('Pending search should be indicated');
  });
  await page.waitForFunction(()=>document.querySelector('#count').textContent==='1 条');
  assert.equal(await page.evaluate(()=>window.__filterRenders),1,'20 rapid inputs coalesce into one render');
  assert.equal(await page.locator('.console-search-pending').isVisible(),false);
  assert.match(await page.locator('#rows').innerText(),/目标计划 2999/);
  assert.equal(dataRequests(),initialRequests,'Local search never re-queries data API');
  await page.locator('#clearBidSearch').click();assert.equal(await page.locator('#count').innerText(),'3000 条');
  assert.equal(await page.locator('#clearBidSearch').isVisible(),false);
  await page.evaluate(()=>{
   window.__filterRenders=0;const input=document.getElementById('search');
   input.value='准备';input.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText'}));
   input.dispatchEvent(new CompositionEvent('compositionstart',{bubbles:true}));
   input.value='尚未选字';input.dispatchEvent(new InputEvent('input',{bubbles:true,isComposing:true,inputType:'insertCompositionText'}));
  });
  await page.waitForTimeout(180);assert.equal(await page.evaluate(()=>window.__filterRenders),0);
  await page.evaluate(()=>{const input=document.getElementById('search');input.value='目标计划 2000';input.dispatchEvent(new CompositionEvent('compositionend',{bubbles:true}));});
  await page.waitForFunction(()=>document.querySelector('#count').textContent==='1 条');
  assert.equal(await page.evaluate(()=>window.__filterRenders),1);
  await page.locator('#search').fill('目标计划 2999');await page.locator('#search').press('Enter');
  assert.match(await page.locator('#rows').innerText(),/目标计划 2999/);
  const waiting=page.waitForEvent('download');
  await page.evaluate(()=>{const input=document.getElementById('search');input.value='目标计划 2000';input.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText'}));document.getElementById('export').click();});
  const downloaded=await waiting,csv=fs.readFileSync(await downloaded.path(),'utf8');
  assert.match(csv,/目标计划 2000/);assert.doesNotMatch(csv,/目标计划 2999/);
  let invalidDownloads=0;page.on('download',()=>invalidDownloads++);
  await page.evaluate(()=>{const input=document.getElementById('search');input.value='无匹配计划';input.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText'}));document.getElementById('export').click();});
  assert.equal(await page.locator('#count').innerText(),'0 条');
  assert.equal(await page.locator('#export').isDisabled(),true);await page.waitForTimeout(180);assert.equal(invalidDownloads,0);
  await page.locator('#clearBidSearch').click();
  await page.evaluate(()=>{window.__filterRenders=0;const input=document.getElementById('search');input.value='目标计划 1000';input.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText'}));const platform=document.getElementById('platformFilter');platform.dispatchEvent(new Event('input',{bubbles:true}));});
  assert.equal(await page.locator('#count').innerText(),'1 条');await page.waitForTimeout(180);
  assert.equal(await page.evaluate(()=>window.__filterRenders),1,'Discrete filter cancels delayed duplicate render');
  await page.locator('#clearBidSearch').click();
  await page.evaluate(()=>{const input=document.getElementById('search');input.value='目标计划 2999';input.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText'}));document.getElementById('lastPage').click();});
  assert.equal(await page.locator('#pageLabel').innerText(),'第 1 / 1 页','Last page uses the newest matching row count');
  const context=await page.evaluate(()=>{const input=document.getElementById('search');input.value='目标计划 2000';input.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText'}));return window.getPetReportContext();});
  assert.equal(context.plans.length,1);assert.equal(context.plans[0]['计划'],'目标计划 2000');
  await page.locator('#clearBidSearch').click();
  await page.locator('#search').scrollIntoViewIfNeeded();await page.screenshot({path:path.join(output,'bid-search-desktop.png')});
  await page.setViewportSize({width:390,height:844});await page.waitForFunction(()=>document.documentElement.scrollWidth<=innerWidth);
  await page.locator('#search').fill('目标计划 2999');await page.locator('#search').press('Enter');
  await page.locator('#search').scrollIntoViewIfNeeded();assert.equal(await page.locator('#clearBidSearch').isVisible(),true);
  await page.screenshot({path:path.join(output,'bid-search-mobile.png')});
  await page.locator('#clearBidSearch').click();assert.equal(await page.locator('#count').innerText(),'3000 条');
  assert.deepEqual(errors,[]);assert.equal(dataRequests(),initialRequests);
  console.log('PASS: 3000 plans, 20 inputs to one render, IME, Enter/clear, fresh CSV/empty export, discrete filters, paging, assistant context and mobile layout');
 }finally{await browser?.close();await new Promise(resolve=>server.close(resolve));}
})().catch(error=>{console.error(error);process.exitCode=1;});
