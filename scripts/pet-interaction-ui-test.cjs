const {chromium}=require('playwright');
const http=require('node:http');
const fs=require('node:fs');
const path=require('node:path');
const assert=require('node:assert/strict');

(async()=>{
  const root=path.resolve(__dirname,'../frontend');
  const server=http.createServer((req,res)=>{
    const name=new URL(req.url,'http://localhost').pathname;
    if(name==='/'){
      res.setHeader('Content-Type','text/html;charset=utf-8');
      res.end('<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><link rel="stylesheet" href="/pet.css"><input id="outside" aria-label="其他输入"><script>window.context={reportType:"大航海日报",loaded:true,range:["2026-10-01","2026-10-09"],accountId:""};window.getPetReportContext=()=>window.context;</script><script src="/pet.js" defer></script>');return;
    }
    const file=path.join(root,name.slice(1));
    if(!fs.existsSync(file)){res.writeHead(404);res.end();return;}
    res.setHeader('Content-Type',name.endsWith('.js')?'application/javascript':name.endsWith('.css')?'text/css':'image/png');res.end(fs.readFileSync(file));
  });
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  let browser;
  try{
    browser=await chromium.launch({channel:'chrome',headless:true});
    const page=await browser.newPage({viewport:{width:1280,height:900}}),errors=[];
    page.on('pageerror',error=>errors.push(error.message));
    await page.route('**/api/pet/config',route=>route.fulfill({json:{configured:true,canManage:true,provider:'deepseek'}}));
    await page.goto(`http://127.0.0.1:${server.address().port}/`);
    await page.waitForFunction(()=>document.querySelector('.data-pet-mode').textContent.includes('已配置'));
    await page.evaluate(()=>{
      const originalFetch=window.fetch,originalTimeout=window.setTimeout;
      window.pendingChats=[];window.fastTimeout=false;
      window.setTimeout=(fn,delay,...args)=>originalTimeout(fn,window.fastTimeout&&delay===60000?40:delay,...args);
      window.fetch=(url,options)=>url==='/api/pet/chat'?new Promise(resolve=>{
        window.pendingChats.push({body:JSON.parse(options.body),signal:options.signal,resolve});
      }):originalFetch(url,options);
    });
    await page.locator('.data-pet-toggle').focus();await page.keyboard.press('Enter');
    const input=page.locator('.data-pet-input');
    const count=()=>page.evaluate(()=>pendingChats.length);
    const complete=(index,result,status=200)=>page.evaluate(({index,result,status})=>pendingChats[index].resolve(new Response(JSON.stringify(result),{status,headers:{'Content-Type':'application/json'}})),{index,result,status});
    const ready=()=>page.waitForFunction(()=>!document.querySelector('.data-pet-send').disabled);
    const ask=async text=>{await input.fill(text);await page.locator('.data-pet-send').click();};

    await input.fill('中文选字');await input.dispatchEvent('compositionstart');await input.press('Enter');
    assert.equal(await count(),0,'IME Enter must not submit');
    await input.dispatchEvent('compositionend');await input.press('Shift+Enter');
    assert.ok((await input.inputValue()).includes('\n'));await input.press('Enter');
    assert.equal(await count(),1);assert.ok(await page.locator('.data-pet-stop').isVisible());
    assert.equal(await page.locator('.data-pet-quick button:enabled').count(),0);
    await complete(0,{error:'测试连接失败'},502);await ready();
    assert.equal(await page.locator('.data-pet-message.thinking').count(),0);
    await input.fill('准备发送的下一条草稿');
    await page.getByRole('button',{name:'重试',exact:true}).click();
    assert.equal(await input.inputValue(),'准备发送的下一条草稿','retry must preserve a new draft');
    assert.equal(await count(),2);assert.equal(await page.locator('.data-pet-message.user').count(),1,'retry retains one question');
    const reply='**结论：亏损集中在账户甲**\n\n### 关键指标\n| 指标 | 数值 |\n| --- | --- |\n| 消耗 | 100.00 元 |\n| 注册成本 | 2.00 元 |\n\n- 核对 `gap` 与注册回传\n- <img src=x onerror=alert(1)>\n\n```text\n<script>alert(1)</script>\n```';
    await complete(1,{mode:'ai',provider:'deepseek',reply,scope:'2026-10-01 至 2026-10-09 · 账户甲',queryState:{conditions:{账户:['账户甲']}}});await ready();
    assert.equal(await page.locator('.data-pet-answer table').count(),1);
    assert.equal(await page.locator('.data-pet-answer strong').count(),1);
    assert.equal(await page.locator('.data-pet-answer li').count(),2);
    assert.equal(await page.locator('.data-pet-answer img,.data-pet-answer script').count(),0);
    assert.match(await page.locator('.data-pet-answer pre').textContent(),/<script>/);
    await page.evaluate(()=>{Object.defineProperty(navigator,'clipboard',{configurable:true,value:{writeText:async text=>{window.copied=text;}}});});
    await page.getByRole('button',{name:'复制回答',exact:true}).click();
    assert.match(await page.evaluate(()=>copied),/账户甲/);assert.match(await page.evaluate(()=>copied),/注册成本/);
    const runtime=path.resolve(__dirname,'../.runtime');fs.mkdirSync(runtime,{recursive:true});
    await page.screenshot({path:path.join(runtime,'pet-improved-desktop.png')});

    await ask('待取消问题');assert.equal(await count(),3);
    await page.locator('.data-pet-stop').click();await ready();
    assert.equal(await page.evaluate(()=>pendingChats[2].signal.aborted),true);
    await page.getByRole('button',{name:'重试',exact:true}).click();assert.equal(await count(),4);
    await complete(2,{mode:'ai',reply:'迟到响应不得写入'});
    await page.waitForTimeout(30);
    assert.ok(await page.locator('.data-pet-stop').isVisible(),'old finally must not unlock new request');
    assert.equal(await page.getByText('迟到响应不得写入',{exact:true}).count(),0);
    await complete(3,{mode:'local',reply:'重试成功',notice:'AI 未配置'});await ready();
    assert.equal(await page.getByText('重试成功',{exact:true}).count(),1);

    await ask('正在运行时重置');await page.getByRole('button',{name:'新对话',exact:true}).click();await ready();
    assert.equal(await page.evaluate(()=>pendingChats[4].signal.aborted),true);
    await complete(4,{mode:'ai',reply:'重置前迟到回答'});await page.waitForTimeout(30);
    assert.equal(await page.getByText('重置前迟到回答',{exact:true}).count(),0);
    await ask('新对话后的问题');
    assert.deepEqual(await page.evaluate(()=>pendingChats[5].body.history),[]);
    assert.equal(await page.evaluate(()=>pendingChats[5].body.queryState),null);
    await page.getByRole('button',{name:'关闭对话',exact:true}).click();
    await page.locator('#outside').focus();
    await complete(5,{mode:'ai',provider:'deepseek',reply:'关闭时完成'});await ready();
    assert.equal(await page.evaluate(()=>document.activeElement.id),'outside','completion must not steal focus');
    await page.locator('.data-pet-toggle').focus();await page.keyboard.press('Enter');
    await page.keyboard.press('Escape');assert.ok(await page.locator('.data-pet-panel').isHidden());
    assert.equal(await page.evaluate(()=>document.activeElement.className),'data-pet-toggle');
    await page.keyboard.press('Enter');

    await ask('长回答');await complete(6,{mode:'ai',reply:Array.from({length:65},(_,i)=>`- 第 ${i+1} 项：核对注册数和结算完整性。`).join('\n')});await ready();
    await ask('等待时阅读旧回复');
    await page.waitForTimeout(50);
    await page.locator('.data-pet-messages').evaluate(element=>{element.scrollTop=0;});
    await complete(7,{mode:'ai',reply:'这是阅读时的新回复'});await ready();await page.waitForTimeout(30);
    assert.ok(await page.locator('.data-pet-messages').evaluate(element=>element.scrollTop<20),'keep reading position');
    assert.ok(await page.locator('.data-pet-latest').isVisible());
    await page.locator('.data-pet-latest').click();
    await page.waitForFunction(()=>{const e=document.querySelector('.data-pet-messages');return e.scrollHeight-e.scrollTop-e.clientHeight<5;});

    await page.evaluate(()=>{window.fastTimeout=true;});await ask('模拟超时');
    await page.getByText('本次等待已超时，请重试或缩小报表日期范围。',{exact:false}).waitFor();await ready();
    assert.equal(await page.evaluate(()=>pendingChats[8].signal.aborted),true);
    await complete(8,{mode:'ai',reply:'超时后迟到回复'});await page.waitForTimeout(30);
    assert.equal(await page.getByText('超时后迟到回复',{exact:true}).count(),0);

    await page.setViewportSize({width:390,height:844});
    await page.getByRole('button',{name:'关闭对话',exact:true}).click();
    await page.locator('.data-pet-toggle').focus();await page.keyboard.press('Enter');
    assert.equal(await input.evaluate(element=>element===document.activeElement),false,'mobile open should not force keyboard');
    let box=await page.locator('.data-pet-panel').boundingBox();assert.ok(box.x>=0&&box.x+box.width<=390);
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
    await page.getByRole('button',{name:'新对话',exact:true}).click();
    await page.evaluate(()=>{window.fastTimeout=false;Object.defineProperty(visualViewport,'height',{configurable:true,value:440});visualViewport.dispatchEvent(new Event('resize'));});
    box=await page.locator('.data-pet-panel').boundingBox();assert.ok(box.y+box.height<=440,'composer inside virtual keyboard viewport');
    await page.screenshot({path:path.join(runtime,'pet-improved-mobile-keyboard.png')});
    await page.evaluate(()=>{delete visualViewport.height;visualViewport.dispatchEvent(new Event('resize'));});
    await ask('手机报表分析');await complete(9,{mode:'ai',provider:'deepseek',reply,scope:'2026-10-01 至 2026-10-09 · 账户甲'});await ready();
    await page.screenshot({path:path.join(runtime,'pet-improved-mobile.png')});
    assert.deepEqual(errors,[]);
    console.log('PASS: IME and multiline composer, safe Markdown/table/copy, retry, stop/timeout, stale responses, reset, focus, reading position and mobile viewport');
  }finally{if(browser)await browser.close();await new Promise(resolve=>server.close(resolve));}
})().catch(error=>{console.error(error);process.exitCode=1;});
