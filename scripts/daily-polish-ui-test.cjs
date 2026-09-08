const {chromium}=require('playwright');
const fs=require('node:fs'),http=require('node:http'),path=require('node:path'),assert=require('node:assert/strict');
const root=path.resolve(__dirname,'../frontend'),output=path.resolve(__dirname,'../.runtime');
const metrics={'消耗':12580.5,'现金消耗':11000,'预估佣金':13900,'现金利润':2900,'ROI':1.1,'现金ROI':1.26,'结算数':700,'结算单价':19.85,'转化数':1200,'注册数':2100,'有效订单数':700,'计费转化数':1100,'有效首购率':.63,'转化成本':10.48,'预估佣金合计':13900,'实际佣金合计':13800,'预估利润':2900,'实际利润':2800,'预估ROI':1.26,'实际ROI':1.25,'条件内预估赔付金额':1200};
const row=(key,value)=>({...metrics,[key]:value});
const report={rows:240,range:['2026-09-01','2026-09-07'],summary:metrics,excludeUnknownOptimizer:true,by_optimizer:[row('优化师','优化师 A'),row('优化师','优化师 B')],by_project:[row('项目','示例项目')],by_task:[row('任务名','示例任务')],by_account:[{...row('账户名称','示例账户'),账户ID:'100001'}],by_date:[row('日期','2026-09-07')],alerts:{date:'2026-09-07',items:[]}};
(async()=>{
 fs.mkdirSync(output,{recursive:true});
 const server=http.createServer((req,res)=>{const pathname=new URL(req.url,'http://localhost').pathname;const aliases={'/':'/index.html','/jd':'/jd.html'};const file=path.resolve(root,'.'+(aliases[pathname]||pathname));if(!file.startsWith(root+path.sep)||!fs.existsSync(file)||!fs.statSync(file).isFile()){res.writeHead(404).end();return;}res.setHeader('Content-Type',file.endsWith('.js')?'application/javascript':file.endsWith('.css')?'text/css':'text/html;charset=utf-8');res.end(fs.readFileSync(file));});
 await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));let browser;
 try{
  browser=await chromium.launch({channel:'chrome',headless:true});
  for(const [url,name] of [['/','dhh'],['/jd','jd']]){
   const page=await browser.newPage({viewport:{width:1440,height:1000}}),errors=[],requests=[];
   page.on('pageerror',e=>errors.push(e.message));
   await page.route('**/pet-loader.js*',route=>route.fulfill({contentType:'application/javascript',body:''}));
   await page.route('**/api/**',route=>{const pathname=new URL(route.request().url()).pathname;let data={};if(pathname==='/api/session')data={authenticated:true,user:{role:'admin'}};else if(pathname.endsWith('/analyze')){requests.push(route.request().postDataJSON());data=report;}return route.fulfill({json:data});});
   await page.goto(`http://127.0.0.1:${server.address().port}${url}`);await page.locator('#content:not(.hidden)').waitFor();
   assert.equal(await page.locator('.panel-heading h2').count(),2);
   assert.ok(await page.locator('#table tbody tr').count()>0);
   const beforePresets=requests.length;
   await page.locator('[data-range-preset="week"]').click();
   const expected=await page.evaluate(()=>BIReportDates.ranges().week);
   assert.equal(await page.locator('#start').inputValue(),expected[0]);assert.equal(await page.locator('#end').inputValue(),expected[1]);
   assert.equal(requests.length,beforePresets);
   await page.locator('#apply').click();await page.waitForFunction(()=>!document.querySelector('#apply').disabled);
   assert.equal(requests.at(-1).start,expected[0]);assert.equal(requests.at(-1).end,expected[1]);
   await page.locator('#start').fill('2026-09-01');await page.locator('#end').fill('2026-09-07');await page.locator('#apply').click();
   await page.waitForFunction(()=>!document.querySelector('#apply').disabled);
   assert.equal(requests.at(-1).start,'2026-09-01');assert.equal(requests.at(-1).end,'2026-09-07');
   await page.screenshot({path:path.join(output,`${name}-polished-desktop.png`),fullPage:true});
   await page.setViewportSize({width:390,height:844});await page.reload();await page.locator('#content:not(.hidden)').waitFor();await page.waitForTimeout(150);
   assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
   await page.screenshot({path:path.join(output,`${name}-polished-mobile.png`),fullPage:true});
   await page.route('**/api/session',route=>route.fulfill({json:{authenticated:true,user:{role:'member'}}}));
   await page.reload();await page.locator('#content:not(.hidden)').waitFor();
   assert.equal(await page.locator('body>.panel.admin-only').isVisible(),false);
   assert.equal(await page.locator('#filterPanel').isVisible(),true);assert.deepEqual(errors,[]);await page.close();
  }
  console.log('PASS: DHH/JD desktop and mobile render, date filters, no overflow, member sync controls remain hidden');
 }finally{await browser?.close();await new Promise(resolve=>server.close(resolve));}
})().catch(error=>{console.error(error);process.exitCode=1;});
