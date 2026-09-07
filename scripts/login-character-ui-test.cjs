const {chromium}=require('playwright');
const http=require('node:http'),fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const root=path.resolve(__dirname,'../frontend');
(async()=>{
 const server=http.createServer((req,res)=>{
   const url=new URL(req.url,'http://localhost'),name=url.pathname==='/login'?'/login.html':url.pathname;
   const file=path.resolve(root,'.'+name);if(!file.startsWith(root+path.sep)||!fs.existsSync(file)){res.writeHead(404);res.end();return;}
   res.setHeader('Content-Type',file.endsWith('.js')?'text/javascript':file.endsWith('.css')?'text/css':'text/html; charset=utf-8');res.end(fs.readFileSync(file));
 });await new Promise(r=>server.listen(0,'127.0.0.1',r));
 let browser;try{
  browser=await chromium.launch({channel:'chrome',headless:true});const page=await browser.newPage({viewport:{width:1440,height:960}}),errors=[];
  page.on('pageerror',e=>errors.push(e.message));
  await page.route('**/api/login',async route=>{assert.deepEqual(route.request().postDataJSON(),{username:'test-user',password:'test-password'});await route.fulfill({status:401,json:{error:'测试登录失败'}});});
  await page.goto(`http://127.0.0.1:${server.address().port}/login`);
  await page.waitForSelector('#characterStage[data-ready=true]');await page.waitForTimeout(1100);
  await page.screenshot({path:path.resolve(__dirname,'../.runtime/login-character-desktop.png')});
  await page.locator('#greetCharacter').click();assert.match(await page.locator('#characterStatus').textContent(),/旋转展示/);
  await page.waitForTimeout(600);await page.screenshot({path:path.resolve(__dirname,'../.runtime/login-character-wave.png')});
  await page.locator('#resetCharacter').click();await page.locator('#rotateCharacter').click();await page.locator('#resetCharacter').click();
  await page.locator('#username').fill('test-user');await page.locator('#password').fill('test-password');await page.locator('#submit').click();await page.waitForSelector('#error:has-text("测试登录失败")');assert.equal(await page.locator('#submit').isEnabled(),true);
  await page.locator('#zoomCharacter').click();await page.locator('#resetCharacter').click();
  const buffer=fs.readFileSync(path.join(root,'assets/models/subaru-supplied.glb'));assert.equal(buffer.toString('utf8',0,4),'glTF');
  await page.setViewportSize({width:390,height:844});await page.waitForTimeout(300);assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
  await page.screenshot({path:path.resolve(__dirname,'../.runtime/login-character-mobile.png'),fullPage:true});
  await page.emulateMedia({reducedMotion:'reduce'});await page.locator('#greetCharacter').click();await page.locator('#resetCharacter').click();
  const fallback=await browser.newPage();await fallback.addInitScript(()=>{const original=HTMLCanvasElement.prototype.getContext;HTMLCanvasElement.prototype.getContext=function(type,...args){return type.includes('webgl')?null:original.call(this,type,...args);};});
  await fallback.goto(`http://127.0.0.1:${server.address().port}/login`);await fallback.waitForSelector('#characterStage.unavailable');assert.equal(await fallback.locator('#submit').isEnabled(),true);assert.equal(await fallback.locator('#greetCharacter').isDisabled(),true);await fallback.close();
  await page.route('**/api/login',route=>route.fulfill({json:{redirect:'/logged-in'}}));await page.route('**/logged-in',route=>route.fulfill({contentType:'text/html',body:'Login success'}));await page.locator('#submit').click();await page.waitForURL('**/logged-in');
  const failed=await browser.newPage();await failed.route('**/subaru-supplied.glb',route=>route.abort());await failed.goto(`http://127.0.0.1:${server.address().port}/login`);await failed.waitForSelector('#characterStage.unavailable');assert.equal(await failed.locator('#submit').isEnabled(),true);await failed.close();
  assert.deepEqual(errors,[]);console.log('PASS: supplied 3D renders, rotation/zoom/reset, login success/failure, mobile overflow, reduced motion, WebGL and load fallback; GLB '+buffer.length+' bytes');
 }finally{await browser?.close();await new Promise(r=>server.close(r));}
})().catch(e=>{console.error(e);process.exitCode=1;});
