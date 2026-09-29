const {chromium}=require('playwright');
const fs=require('node:fs'),http=require('node:http'),path=require('node:path'),assert=require('node:assert/strict');
const root=path.resolve(__dirname,'../frontend');
(async()=>{
  const server=http.createServer((req,res)=>{
    const file=path.join(root,path.basename(new URL(req.url,'http://localhost').pathname));
    if(!fs.existsSync(file)||!fs.statSync(file).isFile()){res.writeHead(404).end();return;}
    res.setHeader('Content-Type',file.endsWith('.js')?'application/javascript':file.endsWith('.css')?'text/css':'text/html;charset=utf-8');res.end(fs.readFileSync(file));
  });
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));let browser;
  try{
    browser=await chromium.launch({channel:'chrome',headless:true});
    const page=await browser.newPage({viewport:{width:1440,height:960}}),errors=[];
    page.on('pageerror',error=>errors.push(error.message));
    const now=Date.now();let next=34,failSave=false;
    let notes=Array.from({length:33},(_,i)=>({id:i+1,title:i===0?'投放操作 · 常用说明':`账户记录 ${i+1}`,content:i===0?'注册数使用 reg_pv\n<script>window.injected=true</script>':`账户 ${i+1} 的操作记录`,tags:i===0?'投放,常用':'账户',pinned:i===0,deleted:false,version:1,createdAt:now,updatedAt:now-i*1000}));
    await page.route('**/api/**',async route=>{
      const url=new URL(route.request().url()),p=url.pathname,m=route.request().method();let body={};
      if(p==='/api/session')body={authenticated:true,user:{id:42,role:'member'}};
      else if(p==='/api/report-visibility')body={dhh:true};
      else if(p==='/api/memos/meta'){
        const active=notes.filter(n=>!n.deleted),tags={};active.forEach(n=>n.tags.split(',').filter(Boolean).forEach(t=>tags[t]=(tags[t]||0)+1));
        body={total:active.length,pinned:active.filter(n=>n.pinned).length,trash:notes.length-active.length,tags};
      }else if(p==='/api/memos'&&m==='GET'){
        const q=url.searchParams.get('q')||'',tag=url.searchParams.get('tag'),view=url.searchParams.get('view'),offset=Number(url.searchParams.get('offset'));
        const matches=notes.filter(n=>n.deleted===(view==='trash')&&(view!=='pinned'||n.pinned)&&(!tag||n.tags.split(',').includes(tag))&&q.split(/\s+/).every(t=>(n.title+n.content+n.tags).includes(t))).sort((a,b)=>Number(b.pinned)-Number(a.pinned)||b.updatedAt-a.updatedAt);
        const items=matches.slice(offset,offset+30).map(n=>({...n,preview:n.content.slice(0,180),content:undefined}));body={items,hasMore:offset+items.length<matches.length,nextOffset:offset+items.length};
      }else if(p==='/api/memos'&&m==='POST'){
        const payload=route.request().postDataJSON();body={...payload,id:next++,version:1,deleted:false,createdAt:now,updatedAt:Date.now()};notes.push(body);
      }else if(p.startsWith('/api/memos/')){
        const id=Number(p.split('/')[3]),note=notes.find(n=>n.id===id);assert.ok(note);
        if(m==='PUT'){
          if(failSave){await route.fulfill({status:409,json:{error:'备忘录已变更，请重新打开核对'}});return;}
          Object.assign(note,route.request().postDataJSON(),{version:note.version+1,updatedAt:Date.now()});
        }else if(m==='DELETE'){note.deleted=true;note.version++;}
        else if(m==='POST'){note.deleted=false;note.version++;}
        body=note;
      }
      await route.fulfill({json:body});
    });
    await page.goto(`http://127.0.0.1:${server.address().port}/memos.html`);
    await page.locator('.memo-card').first().waitFor();
    assert.equal(await page.locator('.memo-card').count(),30);
    await page.locator('#loadMore').click();await page.waitForFunction(()=>document.querySelectorAll('.memo-card').length===33);
    await page.locator('.memo-card').first().click();await page.locator('#title').waitFor();
    assert.match(await page.locator('#content').inputValue(),/<script>/);assert.equal(await page.evaluate(()=>window.injected),undefined);
    await page.locator('#search').fill('reg_pv');await page.waitForFunction(()=>document.querySelectorAll('.memo-card').length===1);
    assert.match(await page.locator('.memo-card').textContent(),/投放操作/);
    await page.locator('#tagFilter').selectOption('账户');await page.locator('.list-empty').waitFor();
    await page.locator('#tagFilter').selectOption('');await page.locator('#search').fill('');
    await page.waitForFunction(()=>document.querySelectorAll('.memo-card').length===30);
    await page.locator('#newMemo').click();await page.locator('#title').fill('新策略跟进');await page.locator('#tags').fill('策略,重点');await page.locator('#content').fill('第一步：核对注册数\n第二步：复核结算单价');await page.locator('#pinned').check();
    page.once('dialog',dialog=>dialog.dismiss());await page.locator('.memo-card').first().click();assert.equal(await page.locator('#title').inputValue(),'新策略跟进');
    await page.keyboard.press('Control+s');await page.waitForFunction(()=>document.querySelector('#saveState').textContent==='已保存到网站');
    assert.equal(notes.length,34);assert.equal(notes.at(-1).pinned,true);
    await page.locator('#content').fill('冲突时也要保留这份内容');failSave=true;await page.locator('#save').click();await page.waitForFunction(()=>document.querySelector('#message').textContent.includes('已变更'));
    assert.equal(await page.locator('#content').inputValue(),'冲突时也要保留这份内容');assert.equal(await page.locator('#save').isEnabled(),true);
    failSave=false;await page.locator('#save').click();await page.waitForFunction(()=>document.querySelector('#saveState').textContent==='已保存到网站');
    page.once('dialog',dialog=>dialog.accept());await page.locator('#remove').click();await page.waitForFunction(()=>document.querySelector('#trashCount').textContent==='1');
    await page.locator('[data-view=trash]').click();await page.waitForFunction(()=>document.querySelectorAll('.memo-card').length===1);
    await page.locator('.memo-card').click();await page.locator('#restore').waitFor();assert.equal(await page.locator('#content').isDisabled(),true);
    await page.locator('#restore').click();await page.waitForFunction(()=>document.querySelector('#trashCount').textContent==='0');
    await page.locator('[data-view=all]').click();await page.locator('.memo-card').first().waitFor();await page.locator('.memo-card').first().click();
    await page.waitForFunction(()=>document.querySelector('#saveState').textContent==='已保存到网站');
    fs.mkdirSync(path.resolve(__dirname,'../.runtime'),{recursive:true});
    await page.screenshot({path:path.resolve(__dirname,'../.runtime/memos-desktop.png'),fullPage:true});
    await page.setViewportSize({width:390,height:844});await page.screenshot({path:path.resolve(__dirname,'../.runtime/memos-mobile.png'),fullPage:true});
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
    assert.equal(await page.evaluate(()=>document.querySelector('#loadMore').getBoundingClientRect().bottom<=document.querySelector('.memo-detail').getBoundingClientRect().top),true);
    assert.deepEqual(errors,[]);
    console.log('PASS: search body/tags, pagination, safe text, create, pin, Ctrl+S, dirty guard, conflict preservation, trash/restore, mobile width');
  }finally{await browser?.close();await new Promise(resolve=>server.close(resolve));}
})().catch(error=>{console.error(error);process.exitCode=1;});
