const {chromium}=require('playwright');
const fs=require('node:fs'), http=require('node:http'), path=require('node:path'), assert=require('node:assert/strict');
const root=path.resolve(__dirname,'../frontend');
const metrics={'消耗':100,'现金消耗':90,'预估佣金':120,'现金利润':30,'现金ROI':1.33,'预估ROI':1.2,'实际ROI':1.1};
const people=Array.from({length:80},(_,i)=>({...metrics,优化师:`优化师 ${i}`}));
const dates=people.flatMap((person,i)=>['2026-09-01','2026-09-02'].map(date=>({...person,日期:date,消耗:i===1?222:100})));
const report={rows:160,range:['2026-09-01','2026-09-02'],summary:metrics,by_optimizer:people,by_optimizer_date:dates,by_date:dates.slice(0,2),by_project:[],by_task:[],by_account:[],by_media:[],by_promoter:[],alerts:{items:[]},excludeUnknownOptimizer:true};
(async()=>{
  fs.mkdirSync(path.resolve(__dirname,'../.runtime'),{recursive:true});
  const server=http.createServer((req,res)=>{
    const url=new URL(req.url,'http://localhost').pathname;
    const file=path.resolve(root,'.'+({'/':'/index.html','/jd':'/jd.html'}[url]||url));
    if(!file.startsWith(root+path.sep)||!fs.existsSync(file))return res.writeHead(404).end();
    res.setHeader('Content-Type',file.endsWith('.js')?'application/javascript':file.endsWith('.css')?'text/css':'text/html;charset=utf-8');
    res.end(fs.readFileSync(file));
  });
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  let browser;
  try{
    browser=await chromium.launch({channel:'chrome',headless:true});
    for(const url of ['/','/jd']){
      const page=await browser.newPage({viewport:{width:1440,height:900}});
      const errors=[];page.on('pageerror',error=>errors.push(error.message));
      let mode='hold',chartRequests=0,apiRequests=0,release,spendOverride=null;
      await page.route('**/pet-loader.js*',route=>route.fulfill({body:''}));
      await page.route('**/api/**',route=>{
        const name=new URL(route.request().url()).pathname;
        if(name==='/api/session')return route.fulfill({json:{authenticated:true,user:{role:'member'}}});
        if(name.endsWith('/analyze')){
          apiRequests++;
          return route.fulfill({json:spendOverride===null?report:{...report,by_optimizer_date:dates.map(row=>({...row,消耗:spendOverride}))}});
        }
        return route.fulfill({json:{}});
      });
      await page.route('**/echarts.min.js',async route=>{
        chartRequests++;
        if(mode==='hold')await new Promise(resolve=>{release=resolve;});
        if(mode==='fail')return route.abort('failed');
        const instrument=`;window.__chartDraws=0;const originalInit=echarts.init;echarts.init=function(...args){const chart=originalInit.apply(this,args),originalSet=chart.setOption;chart.setOption=function(...values){window.__chartDraws++;return originalSet.apply(this,values);};return chart;};`;
        return route.fulfill({contentType:'application/javascript',body:fs.readFileSync(path.join(root,'echarts.min.js'),'utf8')+instrument});
      });
      await page.goto(`http://127.0.0.1:${server.address().port}${url}`,{waitUntil:'domcontentloaded'});
      await page.locator('#content:not(.hidden)').waitFor();
      await page.waitForTimeout(100);
      assert.equal(chartRequests,0,'Offscreen charts must not download the chart library');
      assert.equal(apiRequests,1);
      const chart=page.locator('#drillChart');
      await chart.scrollIntoViewIfNeeded();
      await page.waitForFunction(()=>document.querySelector('script[src="/echarts.min.js"]'));
      assert.equal(chartRequests,1);
      assert.equal(await page.locator('#apply').isEnabled(),true,'Slow chart loading must not lock report controls');
      await page.locator('#apply').click();
      await page.waitForFunction(()=>!document.querySelector('#apply').disabled);
      assert.equal(apiRequests,2,'Report refresh must complete while chart script is held');
      await page.locator('#drillSelect').selectOption('优化师 1');
      await chart.scrollIntoViewIfNeeded();
      mode='real';release();
      await page.locator('#drillChart[data-chart-state="ready"]').waitFor();
      const option=await page.evaluate(()=>echarts.getInstanceByDom(document.querySelector('#drillChart')).getOption());
      assert.deepEqual(option.series[0].data,[222,222],'Pending chart renders latest drilldown selection');
      const before=await page.evaluate(()=>window.__chartDraws);
      await page.locator('#pager [data-step="1"]').click();
      await chart.scrollIntoViewIfNeeded();
      await page.waitForTimeout(100);
      assert.equal(await page.evaluate(()=>window.__chartDraws),before,'Paging must not redraw unchanged drilldown chart');
      assert.equal(chartRequests,1);
      await page.screenshot({path:path.resolve(__dirname,`../.runtime/${url==='/'?'dhh':'jd'}-lazy-chart.png`),fullPage:true});
      spendOverride=987;
      await page.locator('#apply').click();
      await page.waitForFunction(()=>!document.querySelector('#apply').disabled);
      await chart.scrollIntoViewIfNeeded();
      await page.waitForFunction(()=>echarts.getInstanceByDom(document.querySelector('#drillChart'))?.getOption().series[0].data[0]===987);
      assert.equal(chartRequests,1,'Data refresh must reuse the chart library');
      await page.locator('#tabs [data-key="by_date"]').click();
      const mainChart=page.locator(url==='/'?'#chart':'#mainChart');
      await mainChart.scrollIntoViewIfNeeded();
      await page.locator(`${url==='/'?'#chart':'#mainChart'}[data-chart-state="ready"]`).waitFor();
      assert.equal(chartRequests,1,'Main and drilldown charts must share one library download');

      mode='fail';
      await page.reload({waitUntil:'domcontentloaded'});
      await page.locator('#content:not(.hidden)').waitFor();
      await page.locator('#drillChart').scrollIntoViewIfNeeded();
      await page.locator('#drillChart[data-chart-state="error"]').waitFor();
      assert.equal(await page.locator('#table').isVisible(),true);
      mode='real';
      await page.getByRole('button',{name:'重试图表',exact:true}).click();
      await page.locator('#drillChart[data-chart-state="ready"]').waitFor();
      assert.deepEqual(errors,[]);
      await page.close();
    }
    console.log('PASS: DHH/JD render tables before chart download, defer offscreen charts, keep queries working, draw latest selection, reuse charts on paging and retry failed scripts');
  }finally{await browser?.close();await new Promise(resolve=>server.close(resolve));}
})().catch(error=>{console.error(error);process.exitCode=1;});
