const {chromium}=require('playwright');
const fs=require('node:fs'),http=require('node:http'),path=require('node:path'),assert=require('node:assert/strict');
const root=path.resolve(__dirname,'../frontend'),output=path.resolve(__dirname,'../.runtime');
(async()=>{
  const server=http.createServer((req,res)=>{
    const pathname=new URL(req.url,'http://localhost').pathname;
    const file=path.join(root,path.basename(pathname));
    if(!fs.existsSync(file)||!fs.statSync(file).isFile()){res.writeHead(404).end();return;}
    res.setHeader('Content-Type',file.endsWith('.js')?'application/javascript':file.endsWith('.css')?'text/css':'text/html;charset=utf-8');res.end(fs.readFileSync(file));
  });
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));let browser;
  try{
    browser=await chromium.launch({channel:'chrome',headless:true});
    const page=await browser.newPage({viewport:{width:1440,height:900}}),errors=[];
    page.on('pageerror',error=>errors.push(error.message));
    await page.route('**/pet-loader.js*',route=>route.fulfill({contentType:'application/javascript',body:''}));
    await page.route('**/api/**',route=>{
      const pathname=new URL(route.request().url()).pathname;
      const data=pathname==='/api/session'?{authenticated:true,user:{id:'member-test',role:'member'}}:pathname==='/api/report-visibility'?{dhh:true,jd:false,jdLowActivity:false,adpflux:false}:pathname==='/api/tool-visibility'?{bidMonitor:true}:{};
      return route.fulfill({json:data});
    });
    const url=`http://127.0.0.1:${server.address().port}`;
    await page.goto(`${url}/tools.html`);
    await page.locator('.app-sidebar-link[data-module="bidMonitor"]').waitFor();
    assert.equal(await page.locator('.app-sidebar-link:visible').count(),5);
    assert.equal(await page.locator('.app-sidebar-link[aria-current="page"]').getAttribute('data-module'),'tools');
    assert.equal(await page.locator('.app-sidebar-link[data-module="jd"]').isVisible(),false);
    await page.locator('.app-sidebar-collapse').click();
    assert.equal(await page.locator('.app-sidebar').evaluate(el=>el.getBoundingClientRect().width),72);
    await page.reload();await page.locator('.app-sidebar-link[data-module="bidMonitor"]').waitFor();
    assert.equal(await page.locator('.app-sidebar').evaluate(el=>el.getBoundingClientRect().width),72);
    await page.setViewportSize({width:390,height:844});
    assert.equal(await page.locator('.app-sidebar').isVisible(),false);
    await page.locator('.app-nav-toggle').click();
    assert.equal(await page.locator('.app-sidebar').getAttribute('aria-modal'),'true');
    assert.equal(await page.locator('.app-sidebar-link[data-module="bidMonitor"]').isVisible(),true);
    assert.equal(await page.locator('.app-sidebar-link[data-module="jd"]').isVisible(),false);
    await page.keyboard.press('Shift+Tab');
    assert.equal(await page.evaluate(()=>document.activeElement.dataset.module),'account');
    await page.keyboard.press('Tab');
    assert.equal(await page.locator('.app-sidebar-close').evaluate(el=>el===document.activeElement),true);
    fs.mkdirSync(output,{recursive:true});await page.screenshot({path:path.join(output,'app-navigation-mobile.png')});
    await page.keyboard.press('Escape');
    assert.equal(await page.locator('.app-sidebar').isVisible(),false);
    assert.equal(await page.locator('.app-nav-toggle').evaluate(el=>el===document.activeElement),true);
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
    await page.route('**/api/session',route=>route.fulfill({json:{authenticated:false}}));
    await page.setViewportSize({width:1440,height:900});await page.goto(`${url}/login.html`);
    assert.equal(await page.locator('.app-sidebar').count(),0);
    assert.equal(await page.locator('main').evaluate(el=>el.getBoundingClientRect().width),420);
    await page.screenshot({path:path.join(output,'login-polished-desktop.png')});
    await page.setViewportSize({width:390,height:844});
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
    assert.deepEqual(errors,[]);
    console.log('PASS: permitted navigation, active route, persisted collapse, mobile focus/escape, login width and no overflow');
  }finally{await browser?.close();await new Promise(resolve=>server.close(resolve));}
})().catch(error=>{console.error(error);process.exitCode=1;});
