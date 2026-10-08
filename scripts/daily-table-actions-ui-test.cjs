const {chromium}=require('playwright');
const fs=require('node:fs'),http=require('node:http'),path=require('node:path'),assert=require('node:assert/strict');
const root=path.resolve(__dirname,'../frontend'),output=path.resolve(__dirname,'../.runtime');
const metrics={消耗:100,现金消耗:90,预估佣金:120,现金利润:30,ROI:1.2,现金ROI:1.33,预估ROI:1.2,实际ROI:1.1};
const people=Array.from({length:35},(_,i)=>({...metrics,优化师:`优化师 ${i}`,消耗:i+1}));
const account={...metrics,账户:'账户-key',账户名称:'测试账户',账户ID:'7686336798147510315',媒体账户名称:'测试账户',媒体账户ID:'7686336798147510315'};
const report={rows:35,range:['2026-10-01','2026-10-07'],summary:metrics,by_optimizer:people,by_optimizer_date:[{...metrics,优化师:'优化师 0',日期:'2026-10-01'}],by_date:[{...metrics,日期:'2026-10-01'}],by_account:[account],by_account_date:[],by_project:[],by_task:[],by_media:[],by_promoter:[],alerts:{items:[]},excludeUnknownOptimizer:true};
(async()=>{
  fs.mkdirSync(output,{recursive:true});
  const server=http.createServer((req,res)=>{
    const url=new URL(req.url,'http://localhost').pathname,file=path.resolve(root,'.'+({'/':'/index.html','/jd':'/jd.html'}[url]||url));
    if(!file.startsWith(root+path.sep)||!fs.existsSync(file))return res.writeHead(404).end();
    res.setHeader('Content-Type',file.endsWith('.js')?'application/javascript':file.endsWith('.css')?'text/css':'text/html;charset=utf-8');res.end(fs.readFileSync(file));
  });
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));let browser;
  try{
    browser=await chromium.launch({channel:'chrome',headless:true});
    for(const route of ['/','/jd']){
      const page=await browser.newPage({viewport:{width:1440,height:1000}}),errors=[];let requests=0;
      page.on('pageerror',error=>errors.push(error.message));
      await page.route('**/pet-loader.js*',r=>r.fulfill({body:''}));
      await page.route('**/api/**',r=>{
        const url=new URL(r.request().url()).pathname;
        if(url==='/api/session')return r.fulfill({json:{authenticated:true,user:{role:'member'}}});
        if(url.endsWith('/analyze')){requests++;return r.fulfill({json:report});}
        return r.fulfill({json:{}});
      });
      await page.goto(`http://127.0.0.1:${server.address().port}${route}`);
      await page.locator('#content:not(.hidden)').waitFor();
      const bar=page.locator('#content .daily-table-actions'),input=bar.getByRole('searchbox');
      const summary=await page.locator('#cards').innerText();
      const detailBar=page.locator('#drillPanel > .daily-table-actions');
      await detailBar.getByRole('searchbox').fill('2099');
      await detailBar.getByRole('searchbox').press('Enter');
      assert.equal(await detailBar.getByRole('button',{name:'导出表格',exact:true}).isDisabled(),true);
      await detailBar.getByRole('button',{name:'清除搜索',exact:true}).click();
      const detailWaiting=page.waitForEvent('download');
      await detailBar.getByRole('button',{name:'导出表格',exact:true}).click();
      const detailDownload=await detailWaiting,detailFile=path.join(output,`details-${route==='/'?'dhh':'jd'}.csv`);
      await detailDownload.saveAs(detailFile);
      assert.match(fs.readFileSync(detailFile,'utf8'),/2026-10-01/);
      assert.match(detailDownload.suggestedFilename(),/日期明细/);
      async function exportCsv(){
        const waiting=page.waitForEvent('download');
        await bar.getByRole('button',{name:'导出表格',exact:true}).click();
        const download=await waiting,file=path.join(output,`actions-${route==='/'?'dhh':'jd'}.csv`);
        await download.saveAs(file);
        assert.match(download.suggestedFilename(),/2026-10-01_2026-10-07/);
        return fs.readFileSync(file,'utf8');
      }
      let exported=await exportCsv();
      assert.equal(exported.split('\r\n').length,36,'Export must include rows on all pages');
      assert.ok(exported.includes('"优化师 34"'));
      await page.locator('#table [data-sort-column="消耗"]').click();
      await page.locator('#table [data-sort-column="消耗"]').click();
      exported=await exportCsv();assert.ok(exported.split('\r\n')[1].startsWith('"优化师 34"'));
      await page.locator('#pager [data-step="1"]').click();
      await input.fill('优化师 2');await input.press('Enter');
      assert.match(await page.locator('#pager').innerText(),/共 13 条，第 1\/1 页/);
      exported=await exportCsv();assert.equal(exported.split('\r\n').length,14);
      assert.equal(await page.locator('#cards').innerText(),summary);
      assert.equal(requests,1,'Table search must not request server data');
      await input.fill('优化师 2 9');await input.press('Enter');
      assert.equal(await page.locator('#table tbody tr').count(),1);
      assert.match(await page.locator('#table tbody').innerText(),/优化师 29/);
      await input.fill('无匹配内容');await input.press('Enter');
      assert.equal(await bar.getByRole('button',{name:'导出表格',exact:true}).isDisabled(),true);
      await bar.getByRole('button',{name:'清除搜索',exact:true}).click();
      assert.match(await page.locator('#pager').innerText(),/共 35 条，第 1\/2 页/);
      await input.evaluate(el=>{el.value='优化师 2 9';el.dispatchEvent(new InputEvent('input',{bubbles:true,isComposing:true}));});
      await page.waitForTimeout(220);assert.equal(await page.locator('#table tbody tr').count(),20);
      await input.evaluate(el=>el.dispatchEvent(new CompositionEvent('compositionend',{bubbles:true})));
      await page.waitForFunction(()=>document.querySelector('#table tbody')?.innerText.includes('优化师 29')&&document.querySelectorAll('#table tbody tr').length===1);
      await page.locator('#tabs [data-key="by_date"]').click();
      assert.equal(await input.inputValue(),'');
      await page.locator('#tabs [data-key="by_optimizer"]').click();
      assert.equal(await input.inputValue(),'优化师 2 9');
      await input.press('Escape');
      await page.locator('#mainTableTools .uc-columns-button').click();
      await page.locator('#unifiedControlsDialog .uc-choices input[data-key="消耗"]').uncheck();
      await page.locator('#unifiedControlsDialog .uc-apply').click();
      exported=await exportCsv();assert.equal(exported.split('\r\n')[0].includes('"消耗"'),false);
      await page.locator('#tabs [data-key="by_account"]').click();
      exported=await exportCsv();assert.ok(exported.includes("'7686336798147510315"));
      await page.setViewportSize({width:390,height:844});
      await bar.scrollIntoViewIfNeeded();
      assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
      await page.screenshot({path:path.join(output,`table-actions-${route==='/'?'dhh':'jd'}-mobile.png`),fullPage:true});
      assert.deepEqual(errors,[]);await page.close();
    }
    console.log('PASS: DHH/JD table search, paging, sort, scoped search, IME, selected-column/all-page CSV, long IDs and mobile layout');
  }finally{await browser?.close();await new Promise(resolve=>server.close(resolve));}
})().catch(error=>{console.error(error);process.exitCode=1;});
