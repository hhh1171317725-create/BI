const {chromium} = require('playwright');
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
(async()=>{
  const root=path.resolve(__dirname,'../frontend');
  const files=fs.readdirSync(root).filter(name=>name.endsWith('.html'));
  const server=http.createServer((req,res)=>{
    const name=new URL(req.url,'http://localhost').pathname.slice(1);
    const file=path.join(root,name);
    if(!fs.existsSync(file)||!fs.statSync(file).isFile()){res.writeHead(404);res.end();return;}
    if(name.endsWith('.html')){
      const html=fs.readFileSync(file,'utf8');
      assert.equal((html.match(/<script src="\/pet-loader.js/g)||[]).length,1,name);
      // Isolate the assistant while retaining every page's real markup and CSS.
      res.setHeader('Content-Type','text/html;charset=utf-8');
      res.end(html.replace(/<script\b[^>]*>[\s\S]*?<\/script>/gi,script=>script.includes('src="/pet-loader.js')?script:''));return;
    }
    res.setHeader('Content-Type',name.endsWith('.js')?'application/javascript':name.endsWith('.css')?'text/css':'image/png');
    res.end(fs.readFileSync(file));
  });
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  let browser;
  try{
    browser=await chromium.launch({channel:'chrome',headless:true});
    const page=await browser.newPage({viewport:{width:390,height:844}});
    const errors=[],requests=[];let configCalls=0;
    page.on('pageerror',e=>errors.push(e.message));
    await page.route('**/api/pet/config',route=>{configCalls++;return route.fulfill({json:{configured:false,canManage:false}})});
    await page.route('**/api/pet/chat',route=>{requests.push(route.request().postDataJSON());return route.fulfill({json:{mode:'local',reply:'当前页面帮助',notice:'AI 未配置'}})});
    for(const file of files){
      const before=configCalls;
      await page.goto(`http://127.0.0.1:${server.address().port}/${file}`);
      await page.waitForSelector('.data-pet',{state:'attached'});
      await page.evaluate(()=>{document.body.classList.add('ready');document.body.style.visibility='visible';});
      assert.equal(await page.locator('.data-pet').count(),1,file);
      // Keyboard activation also validates accessibility without interfering with drag.
      await page.locator('.data-pet-toggle').focus();await page.keyboard.press('Enter');
      await page.locator('.data-pet-panel').waitFor({state:'visible'});
      const box=await page.locator('.data-pet-panel').boundingBox();
      assert.ok(box.x>=0&&box.x+box.width<=391&&box.y>=0&&box.y+box.height<=844,file);
      if(file==='login.html'){
        const count=requests.length;
        await page.locator('.data-pet-input').fill('如何登录');await page.locator('.data-pet-send').click();
        assert.equal(requests.length,count);assert.equal(configCalls,before);
        assert.ok(page.url().endsWith('/login.html'));
      }
      if(file==='terminal.html'){
        await page.locator('.data-pet-input').fill('这个页面能做什么');await page.locator('.data-pet-send').click();
        await page.waitForFunction(()=>!document.querySelector('.data-pet-send').disabled);
        assert.deepEqual(requests.at(-1).context,{mode:'page',pagePath:'/terminal.html'});
        fs.mkdirSync(path.resolve(__dirname,'../.runtime'),{recursive:true});
        await page.screenshot({path:path.resolve(__dirname,'../.runtime/pet-global-terminal.png')});
      }
    }
    assert.deepEqual(errors,[]);
    console.log(`PASS: assistant loads once on ${files.length} pages, mobile containment, login without authenticated requests, terminal page-only context`);
  }finally{if(browser)await browser.close();await new Promise(resolve=>server.close(resolve));}
})().catch(e=>{console.error(e);process.exitCode=1});
