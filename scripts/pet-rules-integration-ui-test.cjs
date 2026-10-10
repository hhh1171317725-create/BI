const {chromium}=require('playwright');
const fs=require('node:fs'),http=require('node:http'),path=require('node:path'),assert=require('node:assert/strict');
(async()=>{
  const frontend=path.resolve(__dirname,'../frontend'),runtime=path.resolve(__dirname,'../.runtime');
  const server=http.createServer((request,response)=>{
    const pathname=new URL(request.url,'http://localhost').pathname;
    if(pathname==='/'){
      response.setHeader('Content-Type','text/html;charset=utf-8');
      response.end('<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><link rel="stylesheet" href="/app-ui.css"><link rel="stylesheet" href="/pet.css"><body><h1>工作台</h1><script>window.context={mode:"bid",reportType:"出价监测",loaded:true,range:["2026-10-01","2026-10-10"],summary:{消耗:200,预估ROI:0.8}};window.contextArgs=[];window.getPetReportContext=(...args)=>{contextArgs.push(args);return context};</script><script src="/pet.js" defer></script></body></html>');return;
    }
    const file=path.resolve(frontend,'.'+pathname);
    if(!file.startsWith(frontend+path.sep)||!fs.existsSync(file)){response.writeHead(404).end();return;}
    response.setHeader('Content-Type',file.endsWith('.js')?'application/javascript':file.endsWith('.css')?'text/css':'image/png');
    response.end(fs.readFileSync(file));
  });
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  let browser;
  try{
    browser=await chromium.launch({channel:'chrome',headless:true});
    const page=await browser.newPage({viewport:{width:1280,height:900}}),errors=[],chats=[];
    let gets=0,posts=0;
    const config={version:1,updatedAt:Date.now(),canManage:true,rules:[{id:'b829909a-f079-4a8a-bd8c-c8156c682801',title:'分析顺序',content:'先讲消耗，再讲ROI',scope:'all',enabled:true,type:'text',dimension:'summary',conditions:[]}]};
    page.on('pageerror',error=>errors.push(error.message));
    await page.route('**/api/**',route=>{
      const url=new URL(route.request().url()).pathname;
      if(url==='/api/pet/config')return route.fulfill({json:{canManage:true,configured:true,provider:'deepseek'}});
      if(url==='/api/pet/rules'){
        if(route.request().method()==='GET'){gets++;return route.fulfill({json:config});}
        posts++;const body=route.request().postDataJSON();config.version++;config.rules=body.rules;return route.fulfill({json:config});
      }
      assert.equal(url,'/api/pet/chat');chats.push(route.request().postDataJSON());
      return route.fulfill({json:{mode:'ai',provider:'deepseek',reply:'按照规则分析当前数据。',rulesVersion:config.version,
        rulesApplied:config.rules.map(({id,title,type})=>({id,title,type})),ruleChecks:'条件依据：<img src=x onerror="window.unsafe=1">',scope:'当前筛选'}});
    });
    await page.goto(`http://127.0.0.1:${server.address().port}/`);
    await page.waitForSelector('.data-pet-toggle');await page.locator('.data-pet-toggle').focus();await page.keyboard.press('Enter');
    assert.equal(gets,0);assert.equal(await page.locator('script[src*="pet-rules.js"]').count(),0,'editor stays deferred');
    await page.getByRole('button',{name:'分析规则',exact:true}).click();
    await page.locator('.pet-rules-save-state').getByText('已与网站同步').waitFor();
    assert.equal(gets,1);assert.equal(await page.locator('script[src*="pet-rules.js"]').count(),1);
    assert.equal(await page.locator('.pet-rules-dialog').evaluate(element=>element.open),true);
    await page.getByRole('textbox',{name:'分析指令',exact:true}).fill('先报告现金利润，然后检查注册成本。');
    await page.getByRole('button',{name:'保存全部规则',exact:true}).click();
    await page.getByText('全部规则已保存，全站数据助手将在新的分析中使用。',{exact:true}).waitFor();
    assert.equal(posts,1);assert.equal(chats.length,0,'saving rules never runs AI automatically');
    await page.keyboard.press('Escape');
    assert.equal(await page.locator('.data-pet-panel').isVisible(),true);
    assert.equal(await page.evaluate(()=>document.activeElement.className),'data-pet-rules-toggle');
    await page.getByText('规则分析',{exact:true}).click();
    await page.waitForFunction(()=>document.querySelector('.data-pet-send').disabled===false);
    assert.equal(chats.length,1);assert.deepEqual(await page.evaluate(()=>contextArgs.at(-1)),[false,true]);
    assert.equal(chats[0].rules,undefined,'only the server decides active rules');
    assert.match(await page.locator('.data-pet-rule-status').textContent(),/启用 1 条规则 · v2/);
    await page.getByText('查看条件判断依据',{exact:true}).click();
    assert.equal(await page.locator('.data-pet-rule-checks img').count(),0);assert.equal(await page.evaluate(()=>window.unsafe),undefined);
    await page.locator('.data-pet-rule-status button').click();
    await page.locator('.pet-rules-save-state').getByText('已与网站同步').waitFor();
    assert.equal(gets,2);assert.equal(await page.locator('script[src*="pet-rules.js"]').count(),1);
    await page.screenshot({path:path.join(runtime,'pet-rules-integrated-desktop.png')});
    await page.setViewportSize({width:390,height:844});
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
    assert.equal(await page.locator('.pet-rules-dialog').evaluate(e=>e.scrollWidth<=e.clientWidth),true);
    await page.screenshot({path:path.join(runtime,'pet-rules-integrated-mobile.png')});
    await page.keyboard.press('Escape');
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
    await page.screenshot({path:path.join(runtime,'pet-rules-assistant-mobile.png')});
    assert.deepEqual(errors,[]);
    const retryPage=await browser.newPage();let styleAttempts=0;
    await retryPage.route('**/api/**',route=>route.fulfill({json:new URL(route.request().url()).pathname==='/api/pet/rules'?config:{configured:false,canManage:true}}));
    await retryPage.route('**/pet-rules.css?*',async route=>{
      if(styleAttempts++===0){await new Promise(resolve=>setTimeout(resolve,100));return route.abort();}
      return route.continue();
    });
    await retryPage.goto(`http://127.0.0.1:${server.address().port}/`);
    await retryPage.locator('.data-pet-toggle').focus();await retryPage.keyboard.press('Enter');
    await retryPage.getByRole('button',{name:'分析规则',exact:true}).click();
    await retryPage.getByText('分析规则组件加载失败，请重试',{exact:true}).waitFor();
    await retryPage.getByRole('button',{name:'分析规则',exact:true}).click();
    await retryPage.locator('.pet-rules-dialog').waitFor();
    assert.equal(styleAttempts,2,'retry reloads missing CSS even when JS is already present');
    assert.equal(await retryPage.locator('link[data-pet-rules-style]').evaluate(e=>Boolean(e.sheet)),true);
    await retryPage.close();
    console.log('PASS: assistant rules entry, lazy assets, save event, next-query scope, version badge, literal evidence, focus return and mobile/global CSS');
  }finally{if(browser)await browser.close();await new Promise(resolve=>server.close(resolve));}
})().catch(error=>{console.error(error);process.exitCode=1});
