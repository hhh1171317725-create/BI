const {chromium}=require('playwright');
const http=require('node:http');
const fs=require('node:fs');
const path=require('node:path');
const assert=require('node:assert/strict');
(async()=>{
  const server=http.createServer((req,res)=>{
    if(req.url.startsWith('/api/session')){res.setHeader('Content-Type','application/json');res.end(JSON.stringify({authenticated:true}));return;}
    const file=path.join(__dirname,'../frontend',path.basename(new URL(req.url,'http://127.0.0.1').pathname));
    if(!fs.existsSync(file)){res.writeHead(404);res.end();return;}res.setHeader('Content-Type','text/html;charset=utf-8');res.end(fs.readFileSync(file));
  });
  await new Promise(r=>server.listen(0,'127.0.0.1',r));let browser;
  try{
    browser=await chromium.launch({headless:true,channel:'chrome'});const page=await browser.newPage();const errors=[];page.on('pageerror',e=>errors.push(e.message));
    await page.goto(`http://127.0.0.1:${server.address().port}/life-health.html`);await page.locator('html.authenticated').waitFor();
    assert.equal(await page.locator('#copy-ingest').isDisabled(),true);
    for(const bad of ['http://health.invalid','https://person:password@health.invalid','https://health.invalid/api?key=mock','https://health.invalid/settings']){
      await page.locator('#origin').fill(bad);await page.locator('button[type=submit]').click();assert.ok(await page.locator('#message').textContent());assert.equal(await page.evaluate(()=>localStorage.getItem('bi-personal-life-origin-v1')),null);
    }
    await page.setViewportSize({width:390,height:844});await page.locator('summary').click();assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
    assert.ok((await page.locator('details').textContent()).includes('不覆盖已有 .env'));
    await page.locator('#origin').fill('https://health.invalid');assert.equal(await page.locator('#ingest-address').textContent(),'https://health.invalid/api/health/ingest');assert.equal(await page.locator('#copy-ingest').isDisabled(),false);
    await page.context().grantPermissions(['clipboard-read','clipboard-write']);await page.locator('#copy-ingest').click();assert.equal(await page.evaluate(()=>navigator.clipboard.readText()),'https://health.invalid/api/health/ingest');
    await page.route('https://health.invalid/',route=>route.fulfill({contentType:'text/html;charset=utf-8',body:'<!doctype html><meta charset="utf-8"><title>独立健康登录（测试）</title><h1>独立健康登录</h1>'}));
    await page.locator('#origin').fill('https://health.invalid');await Promise.all([page.waitForURL('https://health.invalid/'),page.locator('button[type=submit]').click()]);
    assert.equal(await page.title(),'独立健康登录（测试）');assert.deepEqual(errors,[]);console.log('LIFE HEALTH ENTRY PASS: HTTPS validation, no credentials/query/path, mobile layout, independent navigation');
  }finally{await browser?.close();server.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
